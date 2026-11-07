#!/usr/bin/env python3
"""Build bundled environment packages (assets/env/bundled/*.tgz).

Sources:
  - Termux packages installed under /data/data/com.termux/files/usr (read-only copy)

Usage (from the repository root):
  python3 -I scripts/build-bundled-tools.py                # A+B 工具组（默认）
  python3 -I scripts/build-bundled-tools.py --only jq,curl,openssh,git

tar 条目顺序：目录 → 实体文件 → 符号链接。别名一律用同目录相对符号链接
（设备内核禁止硬链接，且 argv[0] 取符号链接路径的 basename 即命令名）。
"""
import argparse
import hashlib
import json
import os
import shutil
import struct
import sys
import tarfile
import tempfile

PREFIX = os.environ.get("TERMUX_PREFIX", "/data/data/com.termux/files/usr")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/main/assets/env/bundled")
DPKG_STATUS = os.path.join(PREFIX, "var/lib/dpkg/status")

CA_BUNDLE = os.path.join(PREFIX, "etc/tls/cert.pem")
TERMINFO = "share/terminfo"

# A+B 组工具包。bins 是实体文件（按 realpath 复制）；symlinks 的别名以
# 同目录相对符号链接指向实体（argv[0] 按链接名分发）；trees 整目录复制；
# dirs 追加空目录并设权限。版本取 dpkg status，去掉末段纯数字 revision。
# 注意：内核禁止硬链接（ln 同目录也 EPERM），不能用 LNKTYPE。
SPECS = [
    {"id": "bash", "bins": ["bash"], "trees": [TERMINFO]},
    {"id": "coreutils", "bins": ["coreutils"], "symlinks": {n: "coreutils" for n in [
        "b2sum", "base32", "base64", "basename", "basenc", "cat", "chcon", "chgrp",
        "chmod", "chown", "chroot", "cksum", "comm", "cp", "csplit", "cut", "date",
        "dd", "dir", "dircolors", "dirname", "du", "echo", "env", "expand", "expr",
        "factor", "false", "fmt", "fold", "groups", "head", "id", "install", "join",
        "kill", "link", "ln", "logname", "ls", "md5sum", "mkdir", "mkfifo", "mknod",
        "mktemp", "mv", "nice", "nl", "nohup", "nproc", "numfmt", "od", "paste",
        "pathchk", "pr", "printenv", "printf", "ptx", "pwd", "readlink", "realpath",
        "rm", "rmdir", "runcon", "seq", "sha1sum", "sha224sum", "sha256sum",
        "sha384sum", "sha512sum", "shred", "shuf", "sleep", "sort", "split", "stat",
        "stdbuf", "stty", "sum", "sync", "tac", "tail", "tee", "test", "timeout",
        "touch", "tr", "true", "truncate", "tsort", "tty", "uname", "unexpand",
        "uniq", "unlink", "vdir", "wc", "whoami", "yes",
    ]}},
    {"id": "grep", "bins": ["grep"]},
    {"id": "sed", "bins": ["sed"]},
    {"id": "gawk", "bins": ["gawk"], "symlinks": {"awk": "gawk"}},
    {"id": "findutils", "dpkg": "findutils", "bins": ["find", "xargs"]},
    {"id": "tar", "bins": ["tar"]},
    {"id": "less", "bins": ["less"], "trees": [TERMINFO]},
    {"id": "diffutils", "bins": ["diff", "cmp", "diff3", "sdiff"]},
    {"id": "patch", "bins": ["patch"]},
    {"id": "ed", "bins": ["ed"]},
    {"id": "dos2unix", "bins": ["dos2unix", "unix2dos", "mac2unix", "unix2mac"]},
    {"id": "gzip", "bins": ["gzip"],
     "symlinks": {"gunzip": "gzip", "zcat": "gzip", "uncompress": "gzip"}},
    {"id": "xz", "dpkg": "xz-utils", "bins": ["xz", "xzdec", "lzmadec", "lzmainfo"],
     "symlinks": {"unxz": "xz", "xzcat": "xz", "lzma": "xz", "unlzma": "xz",
                   "lzcat": "xz"}},
    {"id": "zstd", "bins": ["zstd"],
     "symlinks": {"unzstd": "zstd", "zstdcat": "zstd", "zstdmt": "zstd"}},
        {"id": "brotli", "bins": ["brotli"]},
    {"id": "unzip", "bins": ["unzip", "funzip", "zipinfo", "unzipsfx"]},
    {"id": "ncurses", "bins": ["clear", "tset"], "symlinks": {"reset": "tset"},
     "trees": [TERMINFO]},
    {"id": "screen", "bins": ["screen"], "trees": [TERMINFO],
     "dirs": [(".screen", 0o700)]},
    {"id": "procps", "bins": ["free", "ps", "watch", "uptime", "vmstat", "pgrep",
                              "pkill", "pidof", "pmap", "pwdx", "sysctl", "tload",
                              "hugetop"],
     "trees": [TERMINFO]},
    {"id": "psmisc", "bins": ["killall", "pstree", "fuser", "prtstat", "pslog",
                              "peekfd"]},
    {"id": "lsof", "bins": ["lsof"]},
    {"id": "nano", "bins": ["nano"], "trees": [TERMINFO]},
    {"id": "dialog", "bins": ["dialog"], "trees": [TERMINFO]},
    {"id": "util-linux", "bins": ["cal", "col", "colcrt", "colrm", "column",
                                  "flock", "getopt", "hexdump", "look", "more",
                                  "namei", "rename", "renice", "rev", "script",
                                  "scriptlive", "scriptreplay", "setterm",
                                  "setsid", "ul", "whereis", "lscpu", "lsfd",
                                  "mcookie"],
     "trees": [TERMINFO]},
]


def dpkg_versions():
    versions, name = {}, None
    with open(DPKG_STATUS, encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            if line.startswith("Package: "):
                name = line[9:].strip()
            elif line.startswith("Version: ") and name:
                versions.setdefault(name, line[9:].strip())
                name = None
    return versions


def strip_revision(version):
    head, _, last = version.rpartition("-")
    return head if head and last.isdigit() else version


def needed(path):
    data = open(path, "rb").read()
    if data[:4] != b"\x7fELF":
        return []
    endian = "<" if data[5] == 1 else ">"
    phoff = struct.unpack_from(endian + "Q", data, 32)[0]
    phentsize = struct.unpack_from(endian + "H", data, 54)[0]
    phnum = struct.unpack_from(endian + "H", data, 56)[0]
    loads, dyn = [], None
    for i in range(phnum):
        off = phoff + i * phentsize
        p_type = struct.unpack_from(endian + "I", data, off)[0]
        p_offset, p_vaddr, _, p_filesz = struct.unpack_from(endian + "QQQQ", data, off + 8)
        if p_type == 1:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:
            dyn = (p_offset, p_filesz)
    if dyn is None:
        return []
    off, size = dyn
    strtab, needed_offsets = None, []
    for j in range(0, size, 16):
        tag, val = struct.unpack_from(endian + "qQ", data, off + j)
        if tag == 0:
            break
        if tag == 1:
            needed_offsets.append(val)
        elif tag == 5:
            strtab = val
    if strtab is None:
        return []
    base = None
    for vaddr, poff, fsz in loads:
        if vaddr <= strtab < vaddr + fsz:
            base = poff + (strtab - vaddr)
            break
    if base is None:
        return []
    result = []
    for n in needed_offsets:
        end = data.find(b"\0", base + n)
        result.append(data[base + n:end].decode())
    return result


def closure(paths):
    resolved, queue = {}, list(paths)
    while queue:
        path = queue.pop()
        for lib in needed(path):
            if lib in resolved:
                continue
            candidate = os.path.join(PREFIX, "lib", lib)
            if os.path.exists(candidate):
                resolved[lib] = candidate
                queue.append(candidate)
            else:
                resolved[lib] = None
    return [p for p in resolved.values() if p]


def add_libs(tree, libs):
    libdir = os.path.join(tree, "lib")
    os.makedirs(libdir, exist_ok=True)
    for src in libs:
        shutil.copyfile(os.path.realpath(src), os.path.join(libdir, os.path.basename(src)))


def is_elf(path):
    try:
        with open(path, "rb") as fh:
            return fh.read(4) == b"\x7fELF"
    except OSError:
        return False


def add_bins(tree, bundle_id, names):
    bindir = os.path.join(tree, "bin")
    os.makedirs(bindir, exist_ok=True)
    copied = []
    for name in names:
        src = os.path.join(PREFIX, "bin", name)
        real = os.path.realpath(src)
        if not os.path.isfile(real):
            sys.exit(f"{bundle_id}: Termux 中缺少 {name}，包清单需调整")
        if not is_elf(real):
            sys.exit(f"{bundle_id}: {name} 不是 ELF 二进制（可能是脚本），不能直接内置")
        dst = os.path.join(bindir, name)
        shutil.copyfile(real, dst)
        os.chmod(dst, 0o755)
        copied.append(real)
    return copied


def add_ca(tree):
    if not os.path.isfile(CA_BUNDLE):
        return
    etc = os.path.join(tree, "etc")
    os.makedirs(etc, exist_ok=True)
    shutil.copyfile(CA_BUNDLE, os.path.join(etc, "cert.pem"))


def add_tree(src, dst):
    if not os.path.isdir(src):
        return
    for root, dirs, files in os.walk(src):
        rel = os.path.relpath(root, src)
        target_dir = os.path.join(dst, rel) if rel != "." else dst
        os.makedirs(target_dir, exist_ok=True)
        for name in files:
            src_path = os.path.join(root, name)
            dst_path = os.path.join(target_dir, name)
            if os.path.islink(src_path):
                if os.path.lexists(dst_path):
                    os.remove(dst_path)
                os.symlink(os.readlink(src_path), dst_path)
            else:
                shutil.copyfile(src_path, dst_path)
                os.chmod(dst_path, 0o755)


def write_entries(tar, version, tree, symlinks):
    """目录 → 实体文件 → 符号链接，全部手写以保证实体先于别名。"""
    def info(arc):
        ti = tarfile.TarInfo(arc)
        ti.uid = ti.gid = 0
        ti.uname = ti.gname = ""
        return ti

    trees = []
    for root, dirnames, filenames in os.walk(tree):
        dirnames.sort()
        filenames.sort()
        rel = os.path.relpath(root, tree)
        trees.append(os.path.normpath(rel))
    for rel in trees:
        arc = version if rel == "." else f"{version}/{rel}"
        entry = info(arc)
        entry.type = tarfile.DIRTYPE
        src = tree if rel == "." else os.path.join(tree, rel)
        entry.mode = os.stat(src).st_mode & 0o7777
        tar.addfile(entry)

    for rel in trees:
        base = tree if rel == "." else os.path.join(tree, rel)
        for name in sorted(os.listdir(base)):
            path = os.path.join(base, name)
            arc = f"{version}/{rel}/{name}" if rel != "." else f"{version}/{name}"
            st = os.lstat(path)
            if os.path.islink(path):
                entry = info(arc)
                entry.type = tarfile.SYMTYPE
                entry.linkname = os.readlink(path)
                entry.mode = 0o777
                tar.addfile(entry)
            elif os.path.isfile(path):
                entry = info(arc)
                entry.type = tarfile.REGTYPE
                entry.size = st.st_size
                entry.mode = 0o755
                with open(path, "rb") as fh:
                    tar.addfile(entry, fh)

    for alias in sorted(symlinks):
        # symlinks 的键是 bin/ 下的命令名
        entry = info(f"{version}/bin/{alias}")
        entry.type = tarfile.SYMTYPE
        entry.linkname = symlinks[alias]  # 同目录相对链接
        entry.mode = 0o777
        tar.addfile(entry)


def write_bundle(bundle_id, version, tree, symlinks):
    os.makedirs(OUT, exist_ok=True)
    # 不用 .tar.gz：Android 打包会把 assets 里的 .gz 解压并去掉后缀，
    # 运行时按原名打不开；.tgz 不在特殊处理范围内。
    archive = os.path.join(OUT, bundle_id + ".tgz")
    with tarfile.open(archive, "w:gz", format=tarfile.GNU_FORMAT) as tar:
        write_entries(tar, version, tree, symlinks)
    return archive


def report(bundle_id, version, archive):
    sha = hashlib.sha256(open(archive, "rb").read()).hexdigest()
    print(json.dumps({
        "id": bundle_id, "version": version,
        "size": os.path.getsize(archive), "sha256": sha,
    }))


def pack(bundle_id, version, populate):
    staging = tempfile.mkdtemp(prefix="ccm-bundle-")
    tree = os.path.join(staging, version)
    os.makedirs(tree, exist_ok=True)
    populate(tree)
    archive = write_bundle(bundle_id, version, tree, {})
    shutil.rmtree(staging)
    return archive


def pack_spec(spec, versions):
    bundle_id = spec["id"]
    version = strip_revision(versions[spec.get("dpkg", bundle_id)])
    symlinks = spec.get("symlinks", {})
    for alias, source in symlinks.items():
        if source not in spec["bins"]:
            sys.exit(f"{bundle_id}: 符号链接 {alias} 的源 {source} 不在 bins 中")
        if not os.path.lexists(os.path.join(PREFIX, "bin", alias)):
            sys.exit(f"{bundle_id}: Termux 中不存在 {alias}，清单可能有误")
    staging = tempfile.mkdtemp(prefix="ccm-bundle-")
    try:
        tree = os.path.join(staging, version)
        os.makedirs(tree)
        srcs = add_bins(tree, bundle_id, spec["bins"])
        add_libs(tree, closure(srcs))
        for rel in spec.get("trees", []):
            add_tree(os.path.join(PREFIX, rel), os.path.join(tree, rel))
        for rel, mode in spec.get("dirs", []):
            os.makedirs(os.path.join(tree, rel), exist_ok=True)
            os.chmod(os.path.join(tree, rel), mode)
        archive = write_bundle(bundle_id, version, tree, symlinks)
    finally:
        shutil.rmtree(staging)
    return bundle_id, version, archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", default="",
                        help="逗号分隔的包 id；缺省构建 A+B 工具组")
    args = parser.parse_args()
    versions = dpkg_versions()

    if args.only:
        wanted = [x.strip() for x in args.only.split(",") if x.strip()]
    else:
        wanted = [spec["id"] for spec in SPECS]

    for bundle_id in wanted:
        if bundle_id == "jq":
            def jq(tree):
                srcs = add_bins(tree, "jq", ["jq"])
                add_libs(tree, closure(srcs))
            report(bundle_id, "1.8.2", pack("jq", "1.8.2", jq))
        elif bundle_id == "curl":
            def curl(tree):
                srcs = add_bins(tree, "curl", ["curl"])
                add_libs(tree, closure(srcs))
                add_ca(tree)
            report(bundle_id, "8.22.0", pack("curl", "8.22.0", curl))
        elif bundle_id == "openssh":
            def openssh(tree):
                srcs = add_bins(tree, "openssh",
                                ["ssh", "scp", "sftp", "ssh-keygen", "ssh-add"])
                add_libs(tree, closure(srcs))
            report(bundle_id, "10.6p1", pack("openssh", "10.6p1", openssh))
        elif bundle_id == "git":
            def git(tree):
                srcs = add_bins(tree, "git", ["git"])
                add_tree(os.path.join(PREFIX, "libexec/git-core"),
                         os.path.join(tree, "libexec/git-core"))
                add_tree(os.path.join(PREFIX, "share/git-core"),
                         os.path.join(tree, "share/git-core"))
                srcs.append(os.path.join(PREFIX, "libexec/git-core/git-remote-http"))
                add_libs(tree, closure(srcs))
                add_ca(tree)
            report(bundle_id, "2.56.0", pack("git", "2.56.0", git))
        else:
            spec = next((s for s in SPECS if s["id"] == bundle_id), None)
            if spec is None:
                sys.exit(f"未知包 id：{bundle_id}")
            pid, version, archive = pack_spec(spec, versions)
            report(pid, version, archive)


if __name__ == "__main__":
    if not os.path.isdir(PREFIX):
        sys.exit("Termux prefix not found")
    main()
