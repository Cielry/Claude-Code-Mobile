package com.lyuan.cc.ui

/** Claude Code 的思考档位，null 表示不设置（跟随默认）。 */
data class EffortOption(val value: String?, val label: String)

val effortOptions = listOf(
    EffortOption(null, "默认"),
    EffortOption("low", "低"),
    EffortOption("medium", "中"),
    EffortOption("high", "高"),
    EffortOption("max", "最高"),
)

fun effortLabel(value: String?): String =
    effortOptions.firstOrNull { it.value == value }?.label ?: value.orEmpty()