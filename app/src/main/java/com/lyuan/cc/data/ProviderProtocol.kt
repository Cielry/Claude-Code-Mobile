package com.lyuan.cc.data

/**
 * 服务商接口协议。
 *
 * [ANTHROPIC] 直连 Claude Code 原生的 Anthropic Messages 协议；
 * [OPENAI] 由应用内的本机代理转换为 OpenAI Chat Completions；
 * [RESPONSES] 由应用内的本机代理转换为 OpenAI Responses API。
 */
enum class ProviderProtocol(val wire: String, val label: String) {
    ANTHROPIC("anthropic", "Anthropic 兼容"),
    OPENAI("openai", "OpenAI 兼容"),
    RESPONSES("responses", "Responses 兼容");

    /** 除 Anthropic 直连外，其他协议都需要本机转换代理。 */
    val usesProxy: Boolean get() = this != ANTHROPIC

    companion object {
        fun fromWire(value: String?): ProviderProtocol =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
                ?: ANTHROPIC
    }
}