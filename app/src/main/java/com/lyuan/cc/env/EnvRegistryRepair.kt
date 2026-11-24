package com.lyuan.cc.env

/**
 * 修复旧 registry 配置里把 glibc `libbase` 混入 bionic 命令 LD_LIBRARY_PATH 的问题。
 *
 * 早期安装的软件源包会把 `env/libbase`（glibc 版 OpenSSL/zlib 等）写进
 * `ENV_LD_LIBRARY_PATH` 和 `LIBS`；bionic linker 可能优先选到这些 glibc 库，
 * 继而报 `library "libc.so.6" not found`。这里只做纯文本处理，便于单元测试。
 */
object EnvRegistryRepair {

    fun needsRepair(text: String, baseAbsolute: String): Boolean =
        text.contains("LIBS=libbase") ||
            text.contains(":libbase") ||
            text.contains(baseAbsolute.trimEnd('/'))

    /** 去掉 LIBS 中的相对 `libbase` 与 ENV_LD_LIBRARY_PATH 中的绝对 base 目录。 */
    fun stripBase(text: String, baseAbsolute: String): String {
        val base = baseAbsolute.trimEnd('/')
        val source = text.lines().let { lines ->
            if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
        }
        val lines = source.mapNotNull { line ->
            when {
                line.startsWith("LIBS=") -> {
                    val parts = line.removePrefix("LIBS=")
                        .split(':')
                        .filter { it.isNotBlank() && it.trimEnd('/') != "libbase" }
                    if (parts.isEmpty()) null else "LIBS=" + parts.joinToString(":")
                }
                line.startsWith("ENV_LD_LIBRARY_PATH=") -> {
                    val parts = line.removePrefix("ENV_LD_LIBRARY_PATH=")
                        .split(':')
                        .filter { it.isNotBlank() && it.trimEnd('/') != base }
                    if (parts.isEmpty()) null
                    else "ENV_LD_LIBRARY_PATH=" + parts.joinToString(":")
                }
                else -> line
            }
        }
        return lines.joinToString("\n", postfix = "\n")
    }
}