#!/usr/bin/env bash
# 预编译 Termux 终端 JNI（libtermux.so），供 arm64-v8a 打包。
# 使用 Termux 自带 clang 与 NDK sysroot；不修改 Termux 已安装的包。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
J="${JAVA_HOME:-$HOME/ccm-toolchain/root/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk}"
SRC="$ROOT/terminal-emulator/src/main/jni/termux.c"
OUT_DIR="$ROOT/terminal-emulator/src/main/jniLibs/arm64-v8a"
OUT="$OUT_DIR/libtermux.so"

[ -f "$SRC" ] || { echo "缺少源文件: $SRC" >&2; exit 1; }
[ -d "$J/include" ] || { echo "缺少 JDK 头文件: $J" >&2; exit 1; }

mkdir -p "$OUT_DIR"
clang --target=aarch64-linux-android34 -shared -fPIC -O2 -Wall \
  -I"$J/include" -I"$J/include/linux" \
  -o "$OUT" "$SRC"
echo "已生成 $OUT"