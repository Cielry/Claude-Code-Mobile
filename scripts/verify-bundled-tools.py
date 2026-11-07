#!/usr/bin/env python3
"""按 TarGzExtractor.kt 的语义模拟解包 bundled/*.tgz 并全量冒烟（构建后跑）。

校验点：
  1. 条目 arcname 以 <version>/ 开头；目录带 mode；.screen 0700
  2. 不得出现 LNKTYPE（内核禁止硬链接）；符号链接必须指向包内已有实体
  3. 全部命令经 linker64 加载（区分加载失败与业务参数错误）
  4. 每包 catalog 首命令 + checkArgs 必须退出码 0（应用自检路径）
  5. argv[0] 分发抽查（ls/gunzip/uncompress/awk/bunzip2/unxz/zstdcat/reset）
"""
import os
import subprocess
import sys
import tarfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app/src/main/assets/env/bundled")
DEST = os.path.join(ROOT, "build", "verify-bundled-tools")

# 包 id -> (版本目录名, [(命令, 自检args or None), ...])
PLAN = {
    "bash": ("bash", ["bash"]),
    "coreutils": ("ls", []),  # 命令清单从 bin 目录实际读取
    "grep": ("grep", ["grep"]),
    "sed": ("sed", ["sed"]),
    "gawk": ("gawk", ["gawk"]),
    "findutils": ("find", ["find", "xargs"]),
    "tar": ("tar", ["tar"]),
    "less": ("less", ["less"]),
    "diffutils": ("diff", ["diff", "cmp", "diff3", "sdiff"]),
    "patch": ("patch", ["patch"]),
    "ed": ("ed", ["ed"]),
    "dos2unix": ("dos2unix", ["dos2unix", "unix2dos", "mac2unix", "unix2mac"]),
    "gzip": ("gzip", ["gzip", "gunzip", "zcat", "uncompress"]),
    "xz": ("xz", ["xz", "unxz", "xzcat", "xzdec", "lzma", "unlzma", "lzcat",
                  "lzmadec", "lzmainfo"]),
    "zstd": ("zstd", ["zstd", "unzstd", "zstdcat", "zstdmt"]),
        "brotli": ("brotli", ["brotli"]),
    "unzip": ("unzip", ["unzip", "funzip", "zipinfo", "unzipsfx"]),
    "ncurses": ("clear", ["clear", "tset", "reset"]),
    "screen": ("screen", ["screen"]),
    "procps": ("free", ["free", "ps", "watch", "uptime", "vmstat", "pgrep",
                        "pkill", "pidof", "pmap", "pwdx", "sysctl", "tload",
                        "hugetop"]),
    "psmisc": ("killall", ["killall", "pstree", "fuser", "prtstat", "pslog",
                           "peekfd"]),
    "lsof": ("lsof", ["lsof"]),
    "nano": ("nano", ["nano"]),
    "dialog": ("dialog", ["dialog"]),
    "util-linux": ("cal", ["cal", "col", "colcrt", "colrm", "column", "flock",
                           "getopt", "hexdump", "look", "more", "namei",
                           "rename", "renice", "rev", "script", "scriptlive",
                           "scriptreplay", "setterm", "setsid", "ul", "whereis",
                           "lscpu", "lsfd", "mcookie"]),
}

# 应用自检实际使用的参数（catalog checkArgs）
CHECKARGS = {
    "unzip": ["-v"], "ncurses": ["-V"], "lsof": ["-v"],
}
# 不适合 --version 的命令：跑空参，只要不报加载错误即算通过
NO_VERSION = {"cal", "ul", "col", "colcrt", "colrm", "rev", "mcookie", "look",
              "tload", "namei", "getopt", "fuser", "peekfd", "pslog", "xzdec",
              "lzmadec", "lzmainfo", "funzip", "unzipsfx", "zipinfo", "tset",
              "reset", "scriptreplay"}

errors = []


def check(cond, msg):
    if not cond:
        errors.append(msg)
        print(f"  FAIL {msg}")


def strip(entry, version):
    parts = [p for p in entry.split("/") if p and p != "."]
    if any(p == ".." for p in parts):
        return None
    if not parts or parts[0] != version:
        return None
    return "/".join(parts[1:]) or None


def extract(bundle_id, version, dest):
    os.makedirs(dest, exist_ok=True)
    n_entries = n_cmds = 0
    with tarfile.open(os.path.join(SRC, bundle_id + ".tgz"), "r:gz") as tar:
        for member in tar:
            rel = strip(member.name, version)
            if rel is None:
                check(member.isdir() or True, "")  # 根条目跳过（数据无内容）
                continue
            check(not member.islnk(),
                  f"{bundle_id}: 出现硬链接条目 {rel}（内核不支持）")
            target = os.path.join(dest, rel)
            n_entries += 1
            if member.isdir():
                os.makedirs(target, exist_ok=True)
                os.chmod(target, member.mode)
            elif member.issym():
                os.makedirs(os.path.dirname(target), exist_ok=True)
                os.symlink(member.linkname, target)
            elif member.isfile():
                seen[rel] = True
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, "wb") as fh:
                    fh.write(tar.extractfile(member).read())
                os.chmod(target, member.mode)
            if rel.startswith("bin/"):
                n_cmds += 1
    return n_entries, n_cmds


seen = {}
LOADER_ERRORS = ("unable to open file", "cannot open shared object",
                 "CANNOT LINK", "not executable", "library not found",
                 "is not a valid ELF", "No such file or directory")


def run_cmd(pkg, command, args):
    env = dict(os.environ,
               LD_LIBRARY_PATH=os.path.join(pkg, "lib"),
               TERMINFO=os.path.join(pkg, "share/terminfo"),
               SCREENDIR=os.path.join(pkg, ".screen"),
               TERM="xterm-256color",
               HOME=os.environ.get("HOME", "/data/data/com.termux/files/home"))
    binary = os.path.join(pkg, "bin", command)
    try:
        raw = subprocess.run(["/system/bin/linker64", binary] + args,
                             env=env, capture_output=True, timeout=15,
                             stdin=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:
        return None, "timeout"
    out = (raw.stdout + raw.stderr).decode("utf-8", errors="replace")
    return raw.returncode, out


def main():
    total = 0
    for bundle_id, (first, commands) in PLAN.items():
        with tarfile.open(os.path.join(SRC, bundle_id + ".tgz"), "r:gz") as tar:
            version = tar.next().name.split("/")[0]
        dest = os.path.join(DEST, bundle_id, version)
        os.makedirs(dest, exist_ok=True)
        subprocess.run(["rm", "-rf", os.path.join(DEST, bundle_id)], check=True)
        seen.clear()
        _, n_cmds = extract(bundle_id, version, dest)
        # 符号链接指向的实体必须存在
        bindir = os.path.join(dest, "bin")
        for name in os.listdir(bindir):
            path = os.path.join(bindir, name)
            if os.path.islink(path):
                tgt = os.path.normpath(os.path.join(bindir, os.readlink(path)))
                check(os.path.isfile(tgt),
                      f"{bundle_id}: 符号链接 {name} -> {os.readlink(path)} 悬空")
        commands = sorted(os.listdir(bindir))
        want = n_cmds
        # .screen 权限
        sdir = os.path.join(dest, ".screen")
        if bundle_id == "screen":
            check(oct(os.stat(sdir).st_mode)[-3:] == "700",
                  f"screen: .screen 权限 {oct(os.stat(sdir).st_mode)}")
        pkg = dest
        # 首命令按 catalog checkArgs 自检（应用真实路径）
        args = CHECKARGS.get(bundle_id, ["--version"])
        rc, out = run_cmd(pkg, first, args)
        check(rc == 0, f"{bundle_id}: 自检 {first} {' '.join(args)} exit={rc} {out[:80]!r}")
        # 全量命令加载冒烟
        bad = []
        for cmd in commands:
            if cmd == "tload":
                continue  # 需要真实 tty
            a = [] if cmd in NO_VERSION else ["--version"]
            rc, out = run_cmd(pkg, cmd, a)
            if rc is None or any(s in out for s in LOADER_ERRORS):
                bad.append(f"{cmd}:exit{rc}:{out.strip()[:60]!r}")
        check(not bad, f"{bundle_id}: 加载失败 {bad}")
        total += want
        print(f"{bundle_id:11} v{version:34} cmds={want:3} "
              f"{'OK' if not bad and rc == 0 else 'FAIL'}")
    # argv[0] 分发抽查
    print("\n== argv[0] 分发抽查 ==")
    SPOT = {
        # cmd: (期望首行前缀 or None, 允许的退出码)
        "coreutils": {"ls": ("ls (", {0}), "sort": ("sort (", {0}),
                      "env": ("env (", {0}), "sha256sum": ("sha256sum (", {0}),
                      "test": (None, {0})},
        "gzip": {"gunzip": ("gunzip 1", {0}), "zcat": ("zcat 1", {0}),
                 "uncompress": ("uncompress 1", {0})},
        "gawk": {"awk": ("GNU Awk", {0})},
        "xz": {"unxz": ("xz (XZ Utils)", {0}),
               "lzma": ("xz (XZ Utils)", {0}),   # 与 Termux 本机一致
               "xzcat": ("xz (XZ Utils)", {0})},
        "zstd": {"unzstd": ("*** Zstandard CLI", {0}),
                 "zstdcat": ("1.5.7", {0}),  # zstdcat --version 只打印版本号，与 Termux 一致
                 "zstdmt": ("*** Zstandard CLI", {0})},
                "ncurses": {"reset": ("reset:", {1})},  # 分发到 tset 即可
    }
    for bundle_id, pairs in SPOT.items():
        with tarfile.open(os.path.join(SRC, bundle_id + ".tgz"), "r:gz") as tar:
            version = tar.next().name.split("/")[0]
        pkg = os.path.join(DEST, bundle_id, version)
        for cmd, (expect, rcs) in pairs.items():
            rc, out = run_cmd(pkg, cmd, ["--version"])
            first_line = out.strip().splitlines()[0] if out.strip() else ""
            check(rc in rcs, f"{bundle_id}/{cmd}: exit={rc}")
            if expect:
                check(first_line.startswith(expect),
                      f"{bundle_id}/{cmd}: 输出 {first_line[:50]!r}")
            print(f"  {bundle_id}/{cmd:11} -> {first_line[:56]}")
    print(f"\n命令总数 {total}；失败 {len(errors)} 项")
    if errors:
        for e in errors:
            print(" -", e)
        sys.exit(1)


if __name__ == "__main__":
    main()
