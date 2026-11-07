#!/usr/bin/env bash
# 编译「环境」命令启动器（arm64-v8a），放入 app 的 jniLibs。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
OUT="$OUT_DIR/libenv_exec.so"
SRC="$ROOT/native/env_exec.c"

[ -f "$SRC" ] || { echo "缺少源文件: $SRC" >&2; exit 1; }
mkdir -p "$OUT_DIR"
clang --target=aarch64-linux-android34 -O2 -Wall -Wextra -o "$OUT" "$SRC"
echo "已生成 $OUT"