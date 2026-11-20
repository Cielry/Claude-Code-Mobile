#!/usr/bin/env python3
"""一条命令完成 Android APK 的构建、校验与归档。

用法：
  python3 scripts/package.py                 # 跑单元测试 + 构建 + 校验 + 归档
  python3 scripts/package.py --skip-tests    # 跳过单元测试
  python3 scripts/package.py --clean         # 构建前 clean
  python3 scripts/package.py --name "Release 1.0.2.apk"
  python3 scripts/package.py --out /path/to/app.apk --force
  python3 scripts/package.py --verify-only   # 只校验现有 APK

工具链可用环境变量覆盖：
  CCM_JAVA_HOME、CCM_GRADLE、CCM_SDK、CCM_AAPT2、CCM_APKSIGNER

脚本只做构建与校验，不修改版本号，也不同步 源码/。
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD_FILE = ROOT / "app" / "build.gradle.kts"
DEFAULT_APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
APKS_DIR = ROOT / "apks"

PACKAGE_NAME = "com.lyuan.cc"
ABI = "arm64-v8a"
NATIVE_ASSET = "assets/runtime/claude-native"
NATIVE_SIZE = 267247608
REQUIRED_ENTRIES = [
    "AndroidManifest.xml",
    "classes.dex",
    NATIVE_ASSET,
    "assets/claude/settings.json",
    "lib/arm64-v8a/libld_linux.so",
]


class PackageError(RuntimeError):
    pass


def log(message: str) -> None:
    print(message, flush=True)


def fail(message: str) -> "NoReturn":  # type: ignore[name-defined]
    raise PackageError(message)


def read_version() -> tuple[int, str]:
    text = BUILD_FILE.read_text(encoding="utf-8")
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not code or not name:
        fail(f"无法从 {BUILD_FILE} 读取 versionCode / versionName")
    return int(code.group(1)), name.group(1)


def resolve_toolchain() -> dict[str, Path]:
    home = Path.home()
    java_home = Path(
        os.environ.get(
            "CCM_JAVA_HOME",
            home
            / "ccm-toolchain/root/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk",
        )
    )
    gradle = Path(
        os.environ.get(
            "CCM_GRADLE",
            home / "ccm-toolchain/gradle/gradle-8.11.1/bin/gradle",
        )
    )
    sdk = Path(os.environ.get("CCM_SDK", home / "ccm-toolchain/sdk"))
    build_tools = sdk / "build-tools" / "34.0.0"
    aapt2 = Path(os.environ.get("CCM_AAPT2", build_tools / "aapt2"))
    apksigner = Path(os.environ.get("CCM_APKSIGNER", build_tools / "apksigner"))
    toolchain = {
        "java_home": java_home,
        "gradle": gradle,
        "aapt2": aapt2,
        "apksigner": apksigner,
    }
    missing = [str(path) for path in toolchain.values() if not path.exists()]
    if missing:
        fail("构建工具链不完整：\n  " + "\n  ".join(missing))
    return toolchain


def run(command: list[str], env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    log("$ " + " ".join(command))
    return subprocess.run(
        command,
        cwd=ROOT,
        env=env,
        text=True,
        capture_output=True,
    )


def build(toolchain: dict[str, Path], skip_tests: bool, clean: bool) -> None:
    env = os.environ.copy()
    env["JAVA_HOME"] = str(toolchain["java_home"])
    tasks = []
    if clean:
        tasks.append("clean")
    if not skip_tests:
        tasks.append(":app:testDebugUnitTest")
    tasks.append(":app:assembleDebug")
    result = run(
        [str(toolchain["gradle"]), *tasks, "--console=plain"],
        env=env,
    )
    if result.returncode != 0:
        fail("Gradle 构建失败：\n" + result.stdout[-6000:] + result.stderr[-6000:])
    log("Gradle 构建通过")


def check_zip(apk: Path) -> None:
    try:
        with zipfile.ZipFile(apk) as archive:
            bad = archive.testzip()
            if bad:
                fail(f"APK ZIP 完整性校验失败：{bad}")
            names = set(archive.namelist())
            missing = [name for name in REQUIRED_ENTRIES if name not in names]
            if missing:
                fail("APK 缺少必需条目：\n  " + "\n  ".join(missing))
            size = archive.getinfo(NATIVE_ASSET).file_size
            if size != NATIVE_SIZE:
                fail(
                    f"{NATIVE_ASSET} 大小异常：{size}，期望 {NATIVE_SIZE}。"
                    "请确认已放回随包 claude-native。"
                )
    except zipfile.BadZipFile as exc:
        fail(f"APK 不是有效的 ZIP：{exc}")


def check_badging(toolchain: dict[str, Path], apk: Path, version_code: int, version_name: str) -> None:
    result = run([str(toolchain["aapt2"]), "dump", "badging", str(apk)])
    if result.returncode != 0:
        fail("aapt2 dump badging 失败：\n" + result.stderr[-3000:])
    badging = result.stdout
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    if not match:
        fail("无法解析 aapt2 badging 输出")
    name, code, version = match.group(1), int(match.group(2)), match.group(3)
    if name != PACKAGE_NAME:
        fail(f"包名不一致：{name}，期望 {PACKAGE_NAME}")
    if code != version_code or version != version_name:
        fail(
            f"APK 版本与源码不一致：{code}/{version}，"
            f"期望 {version_code}/{version_name}"
        )
    if f"native-code: '{ABI}'" not in badging:
        fail(f"APK 未包含期望的 ABI：{ABI}")
    log(f"badging：{name} · versionCode {code} · versionName {version} · {ABI}")


def check_signature(toolchain: dict[str, Path], apk: Path) -> None:
    env = os.environ.copy()
    env["JAVA_HOME"] = str(toolchain["java_home"])
    result = run(
        [str(toolchain["apksigner"]), "verify", "--verbose", "--print-certs", str(apk)],
        env=env,
    )
    if result.returncode != 0:
        fail("APK 签名校验失败：\n" + result.stdout[-4000:] + result.stderr[-4000:])
    digest = re.search(
        r"certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", result.stdout, re.IGNORECASE
    )
    signer = digest.group(1) if digest else "(未解析到证书摘要)"
    dn = re.search(r"certificate DN:\s*(.+)", result.stdout)
    log(f"签名有效 · 证书 SHA-256：{signer}")
    if dn:
        log(f"证书主体：{dn.group(1).strip()}")
    log("提示：当前构建使用 debug 签名，不是发布签名。")


def verify(toolchain: dict[str, Path], apk: Path) -> tuple[int, str]:
    if not apk.is_file():
        fail(f"APK 不存在：{apk}")
    if apk.stat().st_size <= 0:
        fail(f"APK 为空：{apk}")
    version_code, version_name = read_version()
    check_zip(apk)
    check_badging(toolchain, apk, version_code, version_name)
    check_signature(toolchain, apk)
    return version_code, version_name


def default_destination(version_name: str) -> Path:
    base = version_name.split()[0] if version_name.split() else version_name
    return APKS_DIR / f"Release {base}.apk"


def resolve_destination(args: argparse.Namespace, version_name: str) -> Path:
    if args.out:
        destination = Path(args.out).expanduser()
        if not destination.is_absolute():
            destination = (ROOT / destination).resolve()
    elif args.name:
        name = args.name
        if "/" in name or "\\" in name:
            fail("--name 只接受文件名；需要完整路径时请用 --out")
        destination = APKS_DIR / name
    else:
        destination = default_destination(version_name)
    destination = destination.resolve()
    if APKS_DIR.resolve() not in destination.parents and destination.parent != APKS_DIR.resolve():
        # 允许 --out 指向任意位置，但拒绝 .. 造成的意外覆盖。
        if ".." in Path(args.out or "").parts:
            fail("--out 不允许包含 ..")
    return destination


def archive(source: Path, destination: Path, force: bool) -> None:
    if destination.exists() and not force:
        fail(f"目标已存在：{destination}\n如需覆盖请加 --force")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".tmp")
    try:
        shutil.copy2(source, temporary)
        with temporary.open("rb") as handle:
            try:
                os.fsync(handle.fileno())
            except OSError:
                pass
        os.replace(temporary, destination)
    finally:
        if temporary.exists():
            temporary.unlink(missing_ok=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="构建、校验并归档 Claude Code Mobile APK")
    parser.add_argument("--skip-tests", action="store_true", help="跳过单元测试")
    parser.add_argument("--clean", action="store_true", help="构建前执行 gradle clean")
    parser.add_argument("--name", help="归档文件名（放在 apks/ 下）")
    parser.add_argument("--out", help="完整输出路径")
    parser.add_argument("--force", action="store_true", help="覆盖已存在的目标文件")
    parser.add_argument("--verify-only", action="store_true", help="只校验现有 APK，不构建")
    args = parser.parse_args()

    started = time.time()
    try:
        toolchain = resolve_toolchain()
        version_code, version_name = read_version()
        destination = resolve_destination(args, version_name)

        if args.verify_only:
            if not destination.exists():
                fail(f"--verify-only 的目标不存在：{destination}")
            verify(toolchain, destination)
            log(f"校验通过：{destination}")
            return 0

        build(toolchain, args.skip_tests, args.clean)
        verify(toolchain, DEFAULT_APK)
        archive(DEFAULT_APK, destination, args.force)
        verify(toolchain, destination)

        size = destination.stat().st_size
        digest = sha256(destination)
        log("")
        log("打包完成")
        log(f"  版本：{version_name}（versionCode {version_code}）")
        log(f"  文件：{destination}")
        log(f"  大小：{size:,} 字节")
        log(f"  SHA-256：{digest}")
        log(f"  用时：{time.time() - started:.1f} 秒")
        return 0
    except PackageError as exc:
        log(f"失败：{exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())