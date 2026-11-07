# Claude Code Mobile

在 Android ARM64 设备上独立运行原版 Claude Code CLI 的 Android 应用。应用负责运行时、
服务商与模型配置、项目/对话管理、真实 PTY 终端与 Android 系统适配，不依赖 Termux、
Root 或 Shizuku。

- Application ID：`com.lyuan.cc`
- 版本：`0.8.7`（versionCode 37）
- 架构：`arm64-v8a`
- Android：`minSdk 28` / `targetSdk 36` / `compileSdk 34`

本仓库只包含构建 APK 所需源码，不包含说明文档和 267 MB 的 `claude-native` 运行时二进制。

## 构建环境

- JDK 17
- Android SDK Platform 34
- Android Build-Tools 34.0.0
- Gradle 8.11.1（仓库已附带 Gradle Wrapper，也可使用本机 Gradle）

## 快速开始

```bash
# 1. 配置 SDK 路径
cp local.properties.example local.properties
# 编辑 local.properties，填入 sdk.dir

# 2. 放入 Claude Code 运行时（可选，见下文）
#    app/src/main/assets/runtime/claude-native

# 3. 构建
./gradlew :app:assembleDebug
```

产物：

```
app/build/outputs/apk/debug/app-debug.apk
```

也可以使用本机 Gradle：

```bash
gradle :app:assembleDebug
```

### Termux / ARM64 本机构建

Android SDK 自带的 `aapt2` 只有 x86_64 版本，在该设备上需要指定本机 `aapt2`：

```bash
gradle :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride="$HOME/ccm-toolchain/sdk/build-tools/34.0.0/aapt2"
```

或在 `~/.gradle/gradle.properties` 中设置：

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/home/ccm-toolchain/sdk/build-tools/34.0.0/aapt2
android.builder.sdkDownload=false
```

## Claude Code 运行时

APK 运行时需要 `claude-native` 二进制。由于体积和分发许可原因，仓库不包含该文件：

| 项目 | 值 |
|---|---|
| 版本 | `2.1.301` |
| 大小 | `267,247,608` 字节 |
| SHA-256 | `1e119036a802d359ba5f9886428e031586e8796f7a858d5a8ee179ac7b8604f0` |
| 放置路径 | `app/src/main/assets/runtime/claude-native` |

缺少该文件时仍可编译，但生成的 APK 无法运行 Claude Code。请自行从合法渠道获取，
并确认 Anthropic 的分发许可。

## 项目结构

```
app/                    Android 应用（Kotlin + Jetpack Compose）
terminal-emulator/      Termux 终端模拟器（GPLv3）
terminal-view/          Termux 终端视图（GPLv3）
native/                 JNI 包装器源码（claude-exec / env-exec）
scripts/                内置包、JNI 与包列表构建脚本
data/                   Termux 设备包列表快照
licenses/               第三方许可证原文
```

应用内诊断输出优先写入 `Android/data/com.lyuan.cc/files/ccm/`，不可用时回退到
`Download/CCM`。

## 脚本说明

`scripts/` 主要用于本项目当前的 Termux 开发环境：

- `build-termux-jni.sh`：编译 `libtermux.so`
- `build-claude-exec.sh` / `build-env-exec.sh`：编译 JNI 包装器
- `build-bundled-tools.py`：从 Termux 前缀生成 `assets/env/bundled/*.tgz`
- `build-termux-package-list.py`：生成设备包列表 JSON
- `verify-bundled-tools.py`：解包并冒烟验证内置包

可通过 `TERMUX_PREFIX` 覆盖默认 Termux 前缀；JDK 路径可用 `JAVA_HOME` 指定。

## 许可证

本项目整体按 **GPLv3** 发布，见 `LICENSE`。

`terminal-emulator` / `terminal-view` 来自 Termux，遵循 GPLv3；其余第三方组件与许可证
见 `THIRD_PARTY_NOTICES.md` 和 `licenses/`。分发 APK 或修改后的源码时请保留这些文件。