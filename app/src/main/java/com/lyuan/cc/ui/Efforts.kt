package com.lyuan.cc.ui

/** Claude Code 的思考档位。 */
data class EffortOption(val value: String, val label: String)

val effortOptions = listOf(
    EffortOption("low", "Low"),
    EffortOption("medium", "Medium"),
    EffortOption("high", "High"),
    EffortOption("xhigh", "Max"),
    EffortOption("max", "Ultra"),
)

fun effortLabel(value: String?): String =
    effortOptions.firstOrNull { it.value == value }?.label ?: value.orEmpty()