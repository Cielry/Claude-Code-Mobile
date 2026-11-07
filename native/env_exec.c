/*
 * env_exec.c — 「环境」包管理器的命令启动器。
 *
 * 安装的工具都放在应用私有目录，无法直接 execve（SELinux W^X）。本文件被放进
 * APK 的 nativeLibraryDir，由 <files>/env/bin/<命令> 符号链接指向它。
 * 启动时按 argv[0] 的命令名读取 registry/<命令>.conf，再通过 glibc loader
 * 映射运行应用私有目录中的目标程序。
 *
 * registry 配置格式（key=value，每行一个）：
 *   PKG=node
 *   VERSION=24.22.2
 *   BIN=pkgs/node/24.22.2/bin/node        # 必填，相对 CCM_ENV_DIR
 *   SCRIPT=pkgs/node/.../npm-cli.js       # 可选，作为第一个参数传给 BIN
 *   LIBS=pkgs/node/24.22.2/lib:libbase    # 可选，冒号分隔，相对 CCM_ENV_DIR
 *   ENV_JAVA_HOME=/path                   # 可选，运行前 setenv
 *
 * 依赖环境变量：
 *   CCM_LD       glibc loader（nativeLibraryDir）
 *   CCM_LIBDIR   运行时 glibc 库目录
 *   CCM_ENV_DIR  环境根目录
 *   CCM_NATIVE   无 registry 时的回退目标（仅供探针）
 */
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define MAX_ENV_LINES 64
#define PATH_BUF 32768

static const char *value_or(const char *name, const char *fallback) {
    const char *value = getenv(name);
    return (value && *value) ? value : fallback;
}

/*
 * 判断目标程序类型：
 *   0 = 静态（无 PT_INTERP）
 *   1 = glibc 动态（需要 libld_linux.so）
 *   2 = bionic 动态（/system/bin/linker64，直接 exec）
 *  -1 = 无法判断
 * interp 返回 PT_INTERP 字符串（可为空）。
 */
static int executable_kind(const char *path, char *interp, size_t interp_len) {
    if (interp != NULL && interp_len > 0) interp[0] = '\0';
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    unsigned char ident[16];
    if (read(fd, ident, sizeof(ident)) != (ssize_t)sizeof(ident) ||
        memcmp(ident, ELFMAG, SELFMAG) != 0 ||
        ident[EI_CLASS] != ELFCLASS64) {
        close(fd);
        return -1;
    }
    Elf64_Ehdr eh;
    if (lseek(fd, 0, SEEK_SET) < 0 ||
        read(fd, &eh, sizeof(eh)) != (ssize_t)sizeof(eh)) {
        close(fd);
        return -1;
    }
    for (int i = 0; i < eh.e_phnum; i++) {
        Elf64_Phdr ph;
        off_t offset = (off_t)(eh.e_phoff + (off_t)i * eh.e_phentsize);
        if (lseek(fd, offset, SEEK_SET) < 0 ||
            read(fd, &ph, sizeof(ph)) != (ssize_t)sizeof(ph)) {
            close(fd);
            return -1;
        }
        if (ph.p_type != PT_INTERP) continue;
        if (interp != NULL && interp_len > 1) {
            size_t want = (ph.p_filesz < interp_len - 1) ? (size_t)ph.p_filesz : interp_len - 1;
            if (lseek(fd, (off_t)ph.p_offset, SEEK_SET) < 0 ||
                read(fd, interp, want) != (ssize_t)want) {
                close(fd);
                return -1;
            }
            interp[want] = '\0';
        }
        close(fd);
        if (interp != NULL &&
            (strncmp(interp, "/system", 7) == 0 || strstr(interp, "linker") != NULL)) {
            return 2;
        }
        return 1;
    }
    close(fd);
    return 0;
}

static void append_path(char *dst, size_t *used, const char *path) {
    if (!path || !*path) return;
    size_t len = strlen(path);
    if (*used + len + 2 >= PATH_BUF) return;
    if (*used) dst[(*used)++] = ':';
    memcpy(dst + *used, path, len);
    *used += len;
    dst[*used] = '\0';
}

int main(int argc, char **argv) {
    const char *ld = value_or("CCM_LD", "");
    const char *libdir = value_or("CCM_LIBDIR", "");
    const char *root = value_or("CCM_ENV_DIR", "");
    const char *probe_native = value_or("CCM_NATIVE", "");
    if (!*ld) {
        fprintf(stderr, "ccm-env: CCM_LD 未设置\n");
        return 127;
    }

    const char *arg0 = (argv[0] != NULL) ? argv[0] : "";
    const char *slash = strrchr(arg0, '/');
    const char *name = (slash != NULL && slash[1] != '\0') ? slash + 1 : arg0;
    if (!*name) {
        fprintf(stderr, "ccm-env: 缺少命令名\n");
        return 127;
    }

    char bin[4096] = "";
    char script[4096] = "";
    char libs[8192] = "";
    char *env_lines[MAX_ENV_LINES];
    int env_count = 0;

    if (*root) {
        char conf[4096];
        snprintf(conf, sizeof(conf), "%s/registry/%s.conf", root, name);
        FILE *fp = fopen(conf, "r");
        if (fp != NULL) {
            char line[8192];
            while (fgets(line, sizeof(line), fp) != NULL) {
                size_t len = strlen(line);
                while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
                    line[--len] = '\0';
                }
                if (strncmp(line, "BIN=", 4) == 0) {
                    snprintf(bin, sizeof(bin), "%s", line + 4);
                } else if (strncmp(line, "SCRIPT=", 7) == 0) {
                    snprintf(script, sizeof(script), "%s", line + 7);
                } else if (strncmp(line, "LIBS=", 5) == 0) {
                    snprintf(libs, sizeof(libs), "%s", line + 5);
                } else if (strncmp(line, "ENV_", 4) == 0 && env_count < MAX_ENV_LINES) {
                    env_lines[env_count++] = strdup(line + 4);
                }
            }
            fclose(fp);
        }
    }

    char target[4096];
    if (*bin && *root) {
        snprintf(target, sizeof(target), "%s/%s", root, bin);
    } else if (*probe_native) {
        snprintf(target, sizeof(target), "%s", probe_native);
    } else {
        fprintf(stderr, "ccm-env: %s: 未安装（缺少 registry 配置）\n", name);
        return 127;
    }

    char *libpath = calloc(1, PATH_BUF);
    if (libpath == NULL) {
        perror("ccm-env: calloc");
        return 126;
    }
    size_t used = 0;
    append_path(libpath, &used, libdir);
    if (*root) {
        char base[4096];
        snprintf(base, sizeof(base), "%s/libbase", root);
        append_path(libpath, &used, base);
        char *copy = strdup(libs);
        if (copy != NULL) {
            char *save = NULL;
            for (char *part = strtok_r(copy, ":", &save);
                 part != NULL;
                 part = strtok_r(NULL, ":", &save)) {
                if (!*part) continue;
                char dir[4096];
                snprintf(dir, sizeof(dir), "%s/%s", root, part);
                append_path(libpath, &used, dir);
            }
            free(copy);
        }
    }

    for (int i = 0; i < env_count; i++) {
        char *line = env_lines[i];
        char *eq = strchr(line, '=');
        if (eq != NULL) {
            *eq = '\0';
            setenv(line, eq + 1, 1);
        }
    }

    /*
     * 静态二进制没有 PT_INTERP，glibc loader 无法映射运行，必须直接 execve。
     */
    char interp[256];
    int kind = executable_kind(target, interp, sizeof(interp));
    if (kind == 0) {
        char **args = calloc((size_t)argc + 2, sizeof(*args));
        if (args == NULL) {
            perror("ccm-env: calloc");
            return 126;
        }
        size_t ai = 0;
        args[ai++] = target;
        for (int a = 1; a < argc; a++) args[ai++] = argv[a];
        args[ai] = NULL;
        execv(target, args);
        fprintf(stderr, "ccm-env: exec %s: %s\n", target, strerror(errno));
        return 126;
    }

    /*
     * bionic 动态程序不能再直接 execve（targetSdk 29+ 的 W^X 会拒绝执行应用私有目录）。
     * 改为执行系统自带的 linker64，由它映射目标程序；目标程序不在 execve 路径上，
     * 因此不受限制。targetSdk 28 下同样可用。
     */
    if (kind == 2) {
        char **args = calloc((size_t)argc + 2, sizeof(*args));
        if (args == NULL) {
            perror("ccm-env: calloc");
            return 126;
        }
        size_t ai = 0;
        args[ai++] = "/system/bin/linker64";
        args[ai++] = target;
        for (int a = 1; a < argc; a++) args[ai++] = argv[a];
        args[ai] = NULL;
        execv("/system/bin/linker64", args);
        fprintf(stderr, "ccm-env: exec linker64 %s: %s\n", target, strerror(errno));
        return 126;
    }

    char **next = calloc((size_t)argc + 8, sizeof(*next));
    if (next == NULL) {
        perror("ccm-env: calloc");
        return 126;
    }
    size_t i = 0;
    next[i++] = (char *)ld;
    next[i++] = "--library-path";
    next[i++] = libpath;
    next[i++] = target;
    char script_path[4096];
    if (*script && *root) {
        snprintf(script_path, sizeof(script_path), "%s/%s", root, script);
        next[i++] = script_path;
    }
    for (int a = 1; a < argc; a++) next[i++] = argv[a];
    next[i] = NULL;

    execv(ld, next);
    fprintf(stderr, "ccm-env: exec %s: %s\n", ld, strerror(errno));
    return 126;
}