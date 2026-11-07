/*
 * claude_exec.c — 供 Claude Code 的 CLAUDE_CODE_EXECPATH 使用。
 *
 * Claude Code 会把 grep/find 之类的调用以自定义 argv[0]（ugrep/bfs）重新 exec
 * 这个路径。因此这里必须是一个原生可执行文件（放在 APK 的 nativeLibraryDir），
 * 它把 argv[0] 透传给 glibc loader，由 loader 映射 claude-native。
 *
 * 依赖环境变量：
 *   CCM_LD       glibc 动态加载器路径
 *   CCM_LIBDIR   运行时库目录
 *   CCM_NATIVE   claude-native 路径
 */
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char *value_or(const char *name, const char *fallback) {
    const char *value = getenv(name);
    return (value && *value) ? value : fallback;
}

int main(int argc, char **argv) {
    const char *ld = value_or("CCM_LD", "");
    const char *libdir = value_or("CCM_LIBDIR", "");
    const char *native = value_or("CCM_NATIVE", "");
    if (!*ld || !*native) {
        fprintf(stderr, "claude-exec: CCM_LD / CCM_NATIVE 未设置\n");
        return 127;
    }

    char **next = calloc((size_t)argc + 8, sizeof(*next));
    if (!next) {
        perror("claude-exec: calloc");
        return 126;
    }
    size_t i = 0;
    next[i++] = (char *)ld;
    next[i++] = "--library-path";
    next[i++] = (char *)libdir;
    next[i++] = "--argv0";
    next[i++] = argv[0];
    next[i++] = (char *)native;
    for (int a = 1; a < argc; a++) next[i++] = argv[a];
    next[i] = NULL;

    execv(ld, next);
    fprintf(stderr, "claude-exec: exec %s: %s\n", ld, strerror(errno));
    return 126;
}