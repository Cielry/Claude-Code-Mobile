package com.lyuan.cc.env

/**
 * 解析 pip 安装栏的输入。
 *
 * 支持三种写法：
 *  - 裸包名/版本约束：`requests`、`requests==2.32.0`
 *  - 完整 pip 命令：`pip install requests`、`pip3 install -U requests`
 *  - `python -m pip ...` 形式
 *
 * 只生成 argv，不经过 shell，因此参数里的引号不会引发命令注入。
 */
sealed class PipCommand {
    data class Install(val args: List<String>) : PipCommand()
    data class Error(val message: String) : PipCommand()
}

object PipCommandParser {

    private val pipName = Regex("pip(?:3(?:\\.[0-9]+)*)?")
    private val pythonName = Regex("python(?:3(?:\\.[0-9]+)*)?")

    private val otherSubcommands = setOf(
        "list", "show", "freeze", "uninstall", "download", "wheel", "hash",
        "check", "config", "cache", "index", "search", "help", "debug", "inspect",
    )

    fun parse(input: String): PipCommand {
        val raw = tokenize(input)
            ?: return PipCommand.Error("引号未闭合，请检查输入")
        if (raw.isEmpty()) return PipCommand.Error("请输入要安装的 pip 包或命令")
        // 允许直接粘贴 `sudo pip install ...`；应用本身不需要提权。
        val tokens = raw.dropWhile { it == "sudo" }.ifEmpty { raw }

        var rest = tokens
        when {
            pipName.matches(tokens[0]) -> rest = tokens.drop(1)
            pythonName.matches(tokens[0]) -> {
                if (tokens.size >= 3 && tokens[1] == "-m" && tokens[2] == "pip") {
                    rest = tokens.drop(3)
                } else {
                    return PipCommand.Error("此栏只用于 pip 安装，请输入包名或 pip install ...")
                }
            }
        }
        if (rest.isEmpty()) return PipCommand.Error("请输入要安装的 pip 包或命令")

        val first = rest[0]
        val args = when {
            first == "install" -> rest
            first in otherSubcommands ->
                return PipCommand.Error("此栏只用于安装，请使用 pip install ...")
            first.startsWith("-") -> listOf("install") + rest
            else -> listOf("install") + rest
        }
        return PipCommand.Install(args)
    }

    /** 供测试与报告使用：把参数拼成可读命令。 */
    fun display(args: List<String>): String = buildString {
        append("python -m pip")
        args.forEach { arg ->
            append(' ')
            append(if (arg.any { it.isWhitespace() } || arg.isEmpty()) quote(arg) else arg)
        }
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\"", "\\\"") + "\""

    /**
     * 按 shell 常见规则分词：支持单引号、双引号与反斜杠转义，
     * 但不会做变量展开或命令替换。
     */
    fun tokenize(input: String): List<String>? {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var hasToken = false
        for (ch in input) {
            if (escaped) {
                current.append(ch)
                hasToken = true
                escaped = false
                continue
            }
            when {
                quote == '\'' -> {
                    if (ch == '\'') {
                        quote = null
                    } else {
                        current.append(ch)
                    }
                    hasToken = true
                }
                quote == '"' -> {
                    when (ch) {
                        '"' -> quote = null
                        '\\' -> escaped = true
                        else -> current.append(ch)
                    }
                    hasToken = true
                }
                ch == '\\' -> {
                    escaped = true
                    hasToken = true
                }
                ch == '\'' || ch == '"' -> {
                    quote = ch
                    hasToken = true
                }
                ch.isWhitespace() -> {
                    if (hasToken) {
                        result += current.toString()
                        current.setLength(0)
                        hasToken = false
                    }
                }
                else -> {
                    current.append(ch)
                    hasToken = true
                }
            }
        }
        if (escaped) current.append('\\')
        if (quote != null) return null
        if (hasToken) result += current.toString()
        return result.filter { it.isNotEmpty() }
    }
}