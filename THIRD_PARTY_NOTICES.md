# THIRD_PARTY_NOTICES

> 本文件登记所有被纳入本项目的第三方组件及其许可证。条目随实际引入更新。

| 组件 | 来源 | 版本 | 许可证 | 使用方式 |
|---|---|---|---|---|
| Termux `terminal-emulator` / `terminal-view` | https://github.com/termux/termux-app | master（2026-11-05 克隆） | GPLv3 | 源码 vendor 到 `terminal-emulator/`、`terminal-view/`；JNI（`libtermux.so`）由 `scripts/build-termux-jni.sh` 用 clang 预编译 |
| `libclaude_exec.so` | 本项目 | - | GPLv3（随本项目） | 原创的 `CLAUDE_CODE_EXECPATH` 原生包装器（`native/claude_exec.c`） |
| `libenv_exec.so` | 本项目 | - | GPLv3（随本项目） | 原创的「环境」命令启动器（`native/env_exec.c`）：glibc loader / linker64 / 静态分类执行 |
| glibc 运行时 | GNU C Library（Termux Android 移植） | 2.44 | LGPL-2.1+ | 随 APK 分发运行时子集：`assets/runtime/lib/` 与 `assets/env/libbase/`；许可证见 `licenses/glibc-LGPL-2.1.txt` |
| libstdc++ | GCC / Termux glibc 包 | 6.0.34（GCC 14.2.1） | GPLv3 + GCC Runtime Library Exception | 随 APK 分发 `assets/env/libbase/libstdc++.so.6`；例外条款见 `licenses/gcc-exception-3.1.html` |
| OpenSSL | OpenSSL Project | 3.6.5 | Apache-2.0 | 随 APK 分发 `assets/env/libbase/libssl.so.3`、`libcrypto.so.3`；许可证见 `licenses/openssl-copyright.txt` |
| zlib | Jean-loup Gailly / Mark Adler | 1.3.2 | zlib License | 随 APK 分发 `assets/env/libbase/libz.so.1`；许可证见 `licenses/zlib-copyright.txt` |
| Git | https://git-scm.com | 2.56.0（Termux 构建） | GPL-2.0 | 内置包 `assets/env/bundled/git.tgz`；许可证见 `licenses/git-copyright.txt` |
| curl | https://curl.se | 8.22.0（Termux 构建） | curl License（MIT 风格） | 内置包 `assets/env/bundled/curl.tgz`；许可证见 `licenses/curl-copyright.html` |
| OpenSSH 客户端 | https://www.openssh.com | 10.6p1（Termux 构建） | BSD 风格 | 内置包 `assets/env/bundled/openssh.tgz`；许可证见 `licenses/openssh-copyright.txt` |
| jq | https://jqlang.github.io/jq | 1.8.2（Termux 构建） | MIT | 内置包 `assets/env/bundled/jq.tgz`；许可证见 `licenses/jq-copyright.txt` |
| CA 证书包 | Mozilla CA / Termux ca-certificates | - | MPL-2.0 | `assets/env/certs/cert.pem`，供 git/curl 使用；许可证见 `licenses/ca-certificates-copyright.txt` |
| XZ for Java | https://tukaani.org/xz/java.html | 1.9 | Public Domain | 应用内解压 `.deb` 的 `data.tar.xz`（`org.tukaani:xz`）；许可声明见 `licenses/xz-java-PUBLIC-DOMAIN.txt` |
| Bash | https://www.gnu.org/software/bash/ | 5.3.20（Termux 构建） | GPL-3.0+ | 内置包 `assets/env/bundled/bash.tgz`；许可证见 `licenses/bash-copyright.txt` |
| GNU Coreutils | https://www.gnu.org/software/coreutils/ | 9.11（Termux 构建） | GPL-3.0+ | 内置包 `assets/env/bundled/coreutils.tgz`（ls / cp / sort 等 100 个命令入口，符号链接别名）；许可证见 `licenses/coreutils-copyright.txt` |
| Grep | https://www.gnu.org/software/grep/ | 3.12（Termux 构建） | GPL-3.0+ | 内置包 `assets/env/bundled/grep.tgz`；许可证见 `licenses/grep-copyright.txt` |
| GNU Sed | https://www.gnu.org/software/sed/ | 4.10（Termux 构建） | GPL-3.0+ | 内置包 `assets/env/bundled/sed.tgz`；许可证见 `licenses/sed-copyright.txt` |
| Gawk | https://www.gnu.org/software/gawk/ | 5.3.2（Termux 构建） | GPL-3.0+ | 内置包 `assets/env/bundled/gawk.tgz`（含 awk 兼容入口）；许可证见 `licenses/gawk-copyright.txt` |
| Findutils | https://www.gnu.org/software/findutils/ | 4.11.0（Termux 构建） | GPL-3.0+ | 内置包 `assets/env/bundled/findutils.tgz`；许可证见 `licenses/findutils-copyright.txt` |
| GNU Tar | https://www.gnu.org/software/tar/ | 1.35（Termux 构建） | GPL-3.0+ | 内置包 `assets/env/bundled/tar.tgz`；许可证见 `licenses/tar-copyright.txt` |
| Less | https://www.greenwoodsoftware.com/less/ | 710（Termux 构建） | GPL-3.0+ | 内置包 `assets/env/bundled/less.tgz`；许可证见 `licenses/less-copyright.txt` |
| Diffutils | https://www.gnu.org/software/diffutils/ | 3.12（Termux 构建） | GPL-3.0+ | 内置包 `assets/env/bundled/diffutils.tgz`；许可证见 `licenses/diffutils-copyright.txt` |
| Patch | https://www.gnu.org/software/patch/ | 2.8（Termux 构建） | GPL-3.0+ | 内置包 `assets/env/bundled/patch.tgz`；许可证见 `licenses/patch-copyright.txt` |
| GNU Ed | https://www.gnu.org/software/ed/ | 1.22.6（Termux 构建） | GPL-3.0+ | 内置包 `assets/env/bundled/ed.tgz`；许可证见 `licenses/ed-copyright.txt` |
| Dos2Unix | https://waterlan.home.xs4all.nl/dos2unix.html | 7.5.7（Termux 构建） | BSD-2-Clause | 内置包 `assets/env/bundled/dos2unix.tgz`；许可证见 `licenses/dos2unix-copyright.txt` |
| Gzip | https://www.gnu.org/software/gzip/ | 1.15（Termux 构建） | GPL-3.0+ | 内置包 `assets/env/bundled/gzip.tgz`（含 gunzip / zcat / uncompress 入口）；许可证见 `licenses/gzip-copyright.txt` |
| XZ Utils | https://tukaani.org/xz/ | 5.8.4（Termux 构建） | GPLv2+（工具）/ 0BSD（liblzma） | 内置包 `assets/env/bundled/xz.tgz`；许可证见 `licenses/xz-copyright.txt`、`licenses/xz-liblzma-0BSD-copyright.txt` |
| Zstandard | https://facebook.github.io/zstd/ | 1.5.7（Termux 构建） | BSD-3-Clause / GPLv2 双许可 | 内置包 `assets/env/bundled/zstd.tgz`；许可证见 `licenses/zstd-copyright.txt` |
| Brotli | https://github.com/google/brotli | 1.2.0（Termux 构建） | MIT | 内置包 `assets/env/bundled/brotli.tgz`；许可证见 `licenses/brotli-copyright.txt` |
| Unzip | http://www.info-zip.org | 6.0（Termux 构建） | Info-ZIP 许可 | 内置包 `assets/env/bundled/unzip.tgz`（附带 `libbz2.so.1.0`，用于 zip 内 bzip2 解压）；许可证见 `licenses/unzip-copyright.txt`、`licenses/libbz2-copyright.txt` |
| Ncurses | https://invisible-island.net/ncurses/ | 6.5.20250830（Termux 构建） | MIT 类（X11 风格） | 内置包 `assets/env/bundled/ncurses.tgz`（clear / tset / reset 与 terminfo 数据）；多包附带 `libncursesw.so.6`；许可证见 `licenses/ncurses-copyright.txt` |
| GNU Screen | https://www.gnu.org/software/screen/ | 5.0.2（Termux 构建） | GPL-3.0+ | 内置包 `assets/env/bundled/screen.tgz`；许可证见 `licenses/screen-copyright.txt` |
| Procps-ng | https://gitlab.com/procps-ng/procps | 4.0.7（Termux 构建） | GPL-2.0+（工具）/ LGPL-2.1+（libproc2） | 内置包 `assets/env/bundled/procps.tgz`；许可证见 `licenses/procps-copyright.txt` |
| Psmisc | https://gitlab.com/psmisc/psmisc | 23.7（Termux 构建） | GPL-2.0+ | 内置包 `assets/env/bundled/psmisc.tgz`；许可证见 `licenses/psmisc-copyright.txt` |
| Lsof | https://github.com/lsof-org/lsof | 4.99.7（Termux 构建） | lsof 许可（BSD 风格） | 内置包 `assets/env/bundled/lsof.tgz`；许可证见 `licenses/lsof-copyright.txt` |
| Nano | https://www.nano-editor.org | 9.2（Termux 构建） | GPL-3.0+ | 内置包 `assets/env/bundled/nano.tgz`；许可证见 `licenses/nano-copyright.txt` |
| Dialog | https://invisible-island.net/dialog/ | 1.3-20260721（Termux 构建） | LGPL-2.1+ | 内置包 `assets/env/bundled/dialog.tgz`；许可证见 `licenses/dialog-copyright.txt` |
| Util-linux | https://github.com/util-linux/util-linux | 2.42.4（Termux 构建） | GPL-2.0+（工具）/ LGPL-2.1+（库） | 内置包 `assets/env/bundled/util-linux.tgz`；许可证见 `licenses/util-linux-GPL-2.0.txt`，LGPL 文本见 `licenses/glibc-LGPL-2.1.txt` |
| Readline | https://tiswww.case.edu/php/chet/readline/rltop.html | 8.3.6（Termux 构建） | GPL-3.0+ | 随 bash / gawk 包分发 `libreadline.so.8`；许可证见 `licenses/readline-copyright.txt` |
| GMP | https://gmplib.org | 6.3.0（Termux 构建） | LGPL-3.0+ / GPL-2.0+ 双许可 | 随 coreutils / gawk 包分发 `libgmp.so`；许可证见 `licenses/gmp-copyright.txt` |
| MPFR | https://www.mpfr.org | 4.2.1（Termux 构建） | LGPL-3.0+ | 随 gawk 包分发 `libmpfr.so`；许可证见 `licenses/mpfr-copyright.txt` |
| libiconv | https://www.gnu.org/software/libiconv/ | 1.19（Termux 构建） | LGPL-2.1+ | 随 bash / coreutils / tar 包分发 `libiconv.so`；许可证见 `licenses/libiconv-copyright.txt` |
| PCRE2 | https://www.pcre.org | 10.49（Termux 构建） | BSD-3-Clause | 随 coreutils / grep / less / tar 包分发 `libpcre2-8.so`；许可证见 `licenses/pcre2-copyright.txt` |
| File / libmagic | https://www.darwinsys.com/file/ | 5.48（Termux 构建） | BSD-2-Clause 类 | 随 nano 包分发 `libmagic.so`；许可证见 `licenses/libmagic-copyright.txt` |
| ACL（libacl） | https://savannah.nongnu.org/projects/acl | 2.4.0（Termux 构建） | LGPL-2.1+ | 随 tar 包分发 `libacl.so`；许可证见 `licenses/libacl-copyright.txt` |
| libtirpc | https://sourceforge.net/projects/libtirpc/ | 1.3.8（Termux 构建） | BSD-3-Clause | 随 lsof 包分发 `libtirpc.so`；许可证见 `licenses/libtirpc-copyright.txt` |
| Termux-auth（libtermux-auth） | https://github.com/termux/termux-auth | 1.5.0（Termux 构建） | MIT | 随 screen 包分发 `libtermux-auth.so`；许可证见 `licenses/termux-auth-copyright.txt` |
| libandroid-support | Termux | - | BSD-3-Clause | 多包附带 `libandroid-support.so`；许可证见 `licenses/libandroid-support-copyright.txt` |
| libandroid-glob / libandroid-posix-semaphore / libandroid-selinux | Termux | - | BSD / Apache-2.0 类 | 随 tar / procps / util-linux / coreutils 包分发；许可证见 `licenses/libandroid-glob-copyright.txt`、`licenses/libandroid-posix-semaphore-copyright.txt`、`licenses/libandroid-selinux-copyright.txt` |
| zlib（更多分发场景） | Jean-loup Gailly / Mark Adler | 1.3.2 | zlib License | 另随 nano / zstd 包分发 `libz.so.1`；OpenSSL 另随 coreutils / screen 包分发 `libcrypto.so.3`；许可证同上表 zlib / OpenSSL 条目 |
| Node.js | https://nodejs.org | 24.22.2 | MIT | 「环境」页按用户操作下载并安装到应用私有目录，不随 APK 再分发 |
| Python | https://github.com/astral-sh/python-build-standalone | 3.13.18 | PSF-2.0 | 按用户操作下载安装，不随 APK 再分发 |
| JDK | Eclipse Temurin / Adoptium | 21.0.13+9 | GPL-2.0 + Classpath Exception | 按用户操作下载安装，不随 APK 再分发 |
| Claude Code CLI（`claude-native`） | Anthropic | 2.1.301 | 以官方分发包许可为准 | **随 APK 内置**于 `assets/runtime/claude-native`（用户要求，便于离线初始化）；详见下方说明 |
| Claude 星芒图标 | https://claude.ai/apple-touch-icon.png、https://claude.ai/images/claude_app_icon.png | 抓取日期 2026-11-05 | Anthropic 商标，非开源素材 | 仅作为应用图标使用，用于标识所连接的服务；本项目为非官方客户端 |

> 商标说明：Claude 名称与图标为 Anthropic 的商标。本应用与 Anthropic 无隶属或背书关系；如需公开分发，应自行确认商标使用许可或更换自有图标。

## 关于 `claude-native`

`claude-native` 是 Anthropic 的 Claude Code 官方二进制，本项目不修改其内部逻辑，仅负责启动与配置注入。
当前构建应开发者要求把它复制进 APK（源路径：
`/data/data/com.termux/files/usr/lib/node_modules/@anthropic-ai/claude-code/bin/claude-native`），
以减少首次初始化时的下载时间。该文件的再分发权利以 Anthropic 官方分发包许可为准；
对外公开分发 APK 前，应自行确认许可条款，或改回「首启下载 / 本地导入」模式。

## 对第三方源码的修改

| 文件 | 修改 | 原因 |
|---|---|---|
| `terminal-emulator/src/main/java/com/termux/terminal/TerminalBitmap.java` | `VERSION_CODES.VANILLA_ICE_CREAM` 改为数值 `35` | 本项目 compileSdk 为 34，常量在 SDK 35 才提供 |
| `assets/claude/statusline.sh` | shebang 改为 `/system/bin/sh`；增加 PATH 与 transcript 兜底 | 适配应用内环境 |
| `assets/claude/hooks/archive-conversation.js` | 「最近更新」时间改为 `Asia/Shanghai` 24 小时制 | 用户要求 |

除上述改动外，`terminal-emulator/` 与 `terminal-view/` 的源码保持上游原样。
`libtermux.so` 直接由原 `termux.c` 编译，未修改。

## 本项目许可证

由于包含 Termux 的 GPLv3 组件，本项目整体以 **GPLv3** 发布，见根目录 `LICENSE`；第三方许可证原文见 `licenses/`。

## 说明

- 本应用整体（自包含 Termux GPLv3 终端组件起）以 GPLv3 发布，源码随交付物提供。
- 第三方许可证原文存放于 `licenses/`。
- Go / GNU Make / SQLite 曾作为内置包试验，targetSdk 36 后已移除，不再随 APK 分发。
- Bzip2 内置包自 M10 起移除；`unzip` 包仍附带 `libbz2.so.1.0`，许可证见
  `licenses/libbz2-copyright.txt`。
- 「从软件源安装」的包由用户设备在运行时从 Termux 镜像下载，不随 APK 分发；
  这些包的许可证由各自的 `.deb` 提供，本项目不重新分发。