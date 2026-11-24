package com.lyuan.cc.env

/**
 * Debian 版本号比较（epoch:upstream-revision）。
 *
 * 只用于判断软件源里的版本是否比已安装版本更新，规则与 dpkg 基本一致：
 * `~` 排在所有字符之前，字母排在非字母之前，数字段按数值比较。
 */
object DebVersion {

    fun compare(left: String, right: String): Int {
        val a = split(left)
        val b = split(right)
        if (a.epoch != b.epoch) return a.epoch.compareTo(b.epoch)
        comparePart(a.upstream, b.upstream).let { if (it != 0) return it }
        return comparePart(a.revision, b.revision)
    }

    /** 软件源版本比已安装版本新时返回 true。 */
    fun isNewer(candidate: String, installed: String): Boolean =
        compare(candidate, installed) > 0

    private data class Parts(val epoch: Int, val upstream: String, val revision: String)

    private fun split(raw: String): Parts {
        val text = raw.trim()
        val colon = text.indexOf(':')
        val epoch = if (colon > 0) {
            text.substring(0, colon).toIntOrNull() ?: 0
        } else {
            0
        }
        val rest = if (colon > 0 && text.substring(0, colon).toIntOrNull() != null) {
            text.substring(colon + 1)
        } else {
            text
        }
        val dash = rest.lastIndexOf('-')
        return if (dash >= 0) {
            Parts(epoch, rest.substring(0, dash), rest.substring(dash + 1))
        } else {
            Parts(epoch, rest, "")
        }
    }

    private fun comparePart(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length || j < right.length) {
            // 先比较非数字段，dpkg 对 `~`、字母和非字母有固定顺序。
            while ((i < left.length && !left[i].isDigit()) ||
                (j < right.length && !right[j].isDigit())
            ) {
                // 只推进非数字字符；另一方若正处在数字段，视作非数字段结束（order 0）。
                val a = order(
                    if (i < left.length && !left[i].isDigit()) left[i] else null
                )
                val b = order(
                    if (j < right.length && !right[j].isDigit()) right[j] else null
                )
                if (a != b) return a.compareTo(b)
                if (i < left.length && !left[i].isDigit()) i++
                if (j < right.length && !right[j].isDigit()) j++
            }
            // 跳过前导 0，再比较数字段长度和内容。
            while (i < left.length && left[i] == '0') i++
            while (j < right.length && right[j] == '0') j++
            var endA = i
            while (endA < left.length && left[endA].isDigit()) endA++
            var endB = j
            while (endB < right.length && right[endB].isDigit()) endB++
            val lenA = endA - i
            val lenB = endB - j
            if (lenA != lenB) return if (lenA < lenB) -1 else 1
            while (i < endA) {
                if (left[i] != right[j]) return left[i].compareTo(right[j])
                i++
                j++
            }
        }
        return 0
    }

    private fun order(c: Char?): Int = when {
        c == null -> 0
        c == '~' -> -1
        c.isLetter() -> c.code
        else -> c.code + 256
    }
}