#!/usr/bin/env python3
"""从 data/termux-packages.txt 生成 app/src/main/assets/env/termux-packages.json。

输入支持两种格式：
  - Termux 设备的 `apt list --installed` 输出（含仓库与 automatic 状态）；
  - 简单的 `名称<TAB>版本` 快照（如 2026-11-08 的 262 条清单）。

过滤规则见 这次要用的/按需.md：
  - 跳过 Termux 专属包（依赖 Termux 运行环境，独立 APK 内不可用）；
  - 跳过 glibc 仓库的 -glibc 变体与 glibc / glibc-repo / glibc-runner；
  - 跳过包管理、DNS 与 X11 构建胶水包；
  - 跳过仓库标记包（*-repo）；
  - 跳过 catalog.json 已有的内置包（内置目录优先，避免重复）。
保留的包中，基础依赖标记 automatic，三个核心补充标记 recommended。

用法（项目根目录）：
  python3 -I scripts/build-termux-package-list.py
"""
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "data/termux-packages.txt")
CATALOG = os.path.join(ROOT, "app/src/main/assets/env/catalog.json")
OUT = os.path.join(ROOT, "app/src/main/assets/env/termux-packages.json")

# catalog id 与 Termux 包名不一致时，视为已内置。
ALIASES = {
    "xz-utils": "xz",
    "nodejs": "node",
    "openjdk-21": "jdk",
    "openjdk-17": "jdk",
}

# 依赖 Termux 自身运行环境，独立 APK 内不可用。
TERMUX_ONLY = {
    "termux-am",
    "termux-am-socket",
    "termux-auth",
    "termux-core",
    "termux-exec",
    "termux-exec-glibc",
    "termux-keyring",
    "termux-licenses",
    "termux-tools",
}

# 应用自带安装器 / DNS / 构建期头文件，不需要在列表中出现。
GLUE = {
    "apt",
    "dpkg",
    "command-not-found",
    "resolv-conf",
    "libresolv-wrapper",
    "xorg-util-macros",
    "xorgproto",
}

# 基础依赖：没有 automatic 状态的快照里用这份名单标记。
AUTOMATIC = {
    "attr",
    "c-ares",
    "libacl",
    "libandroid-execinfo",
    "libandroid-glob",
    "libandroid-posix-semaphore",
    "libandroid-selinux",
    "libandroid-shmem",
    "libandroid-spawn",
    "libandroid-support",
    "libbz2",
    "libcap-ng",
    "libcrypt",
    "libcurl",
    "libexpat",
    "libffi",
    "libiconv",
    "libidn",
    "libidn2",
    "liblz4",
    "liblzma",
    "liblzo",
    "libmd",
    "libnghttp2",
    "libnghttp3",
    "libngtcp2",
    "libssh2",
    "libsmartcols",
    "libtirpc",
    "libunistring",
    "ncurses-ui-libs",
    "pcre2",
    "readline",
    "xxhash",
    "zlib",
}

# 需要优先补充的通用包。
RECOMMENDED = {
    "bzip2": "补 .bz2 压缩/解压；内置只有 gzip / xz / zstd / brotli",
    "openssl": "TLS、证书与密钥命令行，也是常用依赖",
    "debianutils": "提供 which、tempfile 等脚本常用命令",
}

PATTERN = re.compile(
    r"^([^/\s]+)/([^,\s]+)(?:,now)?\s+(\S+)\s+(\S+)\s+\[(.*)\]$"
)


def is_glibc(name):
    return name == "glibc" or name.startswith("glibc-") or name.endswith("-glibc")


def parse():
    entries, skipped = [], {
        "termux": 0,
        "glibc": 0,
        "glue": 0,
        "repo": 0,
        "builtin": 0,
        "other": 0,
    }
    with open(CATALOG, encoding="utf-8") as fh:
        catalog = json.load(fh)
    builtin = {pkg["id"] for pkg in catalog["packages"]}

    seen = set()
    with open(SRC, encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            match = PATTERN.match(line)
            if match is not None:
                name, repo, version, _arch, status = match.groups()
                automatic = "automatic" in status
            else:
                # 简单快照格式：名称<TAB>版本。
                parts = line.split()
                if len(parts) < 2:
                    if not line.startswith(("=", "WARNING", "Listing")):
                        skipped["other"] += 1
                    continue
                name, version = parts[0], parts[1]
                repo, automatic = "", name in AUTOMATIC

            if name in seen:
                continue
            seen.add(name)

            if name in TERMUX_ONLY:
                skipped["termux"] += 1
                continue
            if is_glibc(name) or repo == "glibc":
                skipped["glibc"] += 1
                continue
            if name in GLUE:
                skipped["glue"] += 1
                continue
            if name.endswith("-repo"):
                skipped["repo"] += 1
                continue
            if name in builtin or ALIASES.get(name) in builtin:
                skipped["builtin"] += 1
                continue

            entry = {
                "name": name,
                "version": version,
                "automatic": automatic,
                "recommended": name in RECOMMENDED,
            }
            if name in RECOMMENDED:
                entry["description"] = RECOMMENDED[name]
            entries.append(entry)

    entries.sort(key=lambda item: item["name"])
    return entries, skipped


def main():
    entries, skipped = parse()
    payload = {
        "source": "data/termux-packages.txt",
        "packages": entries,
    }
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print(
        "wrote {} packages -> {}\nskipped: termux={}, glibc={}, glue={}, "
        "repo={}, builtin={}, other={}".format(
            len(entries),
            os.path.relpath(OUT, ROOT),
            skipped["termux"],
            skipped["glibc"],
            skipped["glue"],
            skipped["repo"],
            skipped["builtin"],
            skipped["other"],
        )
    )


if __name__ == "__main__":
    main()