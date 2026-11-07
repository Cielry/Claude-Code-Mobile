#!/usr/bin/env python3
"""从 data/termux-packages.txt 生成 app/src/main/assets/env/termux-packages.json。

输入是 Termux 设备的 `apt list --installed` 输出。过滤规则：
  - 只保留 Termux main 仓库的包（跳过 /glibc 仓库的 -glibc 变体）；
  - 跳过仓库标记包（*-repo）；
  - 跳过 catalog.json 已有的包（内置目录优先，避免重复）。

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

PATTERN = re.compile(
    r"^([^/\s]+)/([^,\s]+)(?:,now)?\s+(\S+)\s+(\S+)\s+\[(.*)\]$"
)


def parse():
    entries, skipped = [], {"glibc": 0, "repo": 0, "builtin": 0, "other": 0}
    with open(CATALOG, encoding="utf-8") as fh:
        catalog = json.load(fh)
    builtin = {pkg["id"] for pkg in catalog["packages"]}

    with open(SRC, encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            match = PATTERN.match(line)
            if match is None:
                # 表头、警告行等非包条目。
                if not line.startswith(("=", "WARNING", "Listing")):
                    skipped["other"] += 1
                continue
            name, repo, version, _arch, status = match.groups()
            if repo == "glibc":
                skipped["glibc"] += 1
                continue
            if name.endswith("-repo"):
                skipped["repo"] += 1
                continue
            if name in builtin or ALIASES.get(name) in builtin:
                skipped["builtin"] += 1
                continue
            entries.append(
                {
                    "name": name,
                    "version": version,
                    "automatic": "automatic" in status,
                }
            )

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
        "wrote {} packages -> {}\nskipped: glibc={}, repo={}, builtin={}, other={}".format(
            len(entries),
            os.path.relpath(OUT, ROOT),
            skipped["glibc"],
            skipped["repo"],
            skipped["builtin"],
            skipped["other"],
        )
    )


if __name__ == "__main__":
    main()