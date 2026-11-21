package com.lyuan.cc.proxy

import java.net.URI

/** Base URL 归一化与 OpenAI 端点派生。 */
object OpenAiEndpoints {

    fun normalizeBase(raw: String): String =
        raw.trim().replace("\\/", "/").trimEnd('/')

    /** 返回 null 表示可用；否则返回面向用户的错误说明。 */
    fun validate(raw: String): String? {
        val base = normalizeBase(raw)
        if (base.isEmpty()) return "Base URL 为空"
        val uri = runCatching { URI(base) }.getOrNull()
            ?: return "Base URL 格式不正确"
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "Base URL 必须以 http:// 或 https:// 开头"
        if (uri.host.isNullOrBlank()) return "Base URL 缺少主机名"
        return null
    }

    /**
     * Chat Completions 候选地址。
     * - 已填写完整 `/chat/completions`：原样使用。
     * - 以 `/v1` 结尾：追加 `/chat/completions`。
     * - 其他：先尝试 `/v1/chat/completions`，再回退到 `/chat/completions`。
     */
    fun chatCandidates(raw: String): List<String> {
        val base = normalizeBase(raw)
        return when {
            base.endsWith("/chat/completions") -> listOf(base)
            base.endsWith("/v1") -> listOf("$base/chat/completions")
            else -> listOf("$base/v1/chat/completions", "$base/chat/completions")
        }.distinct()
    }

    /**
     * Responses API 候选地址。
     * - 已填写完整 `/responses`：原样使用。
     * - 以 `/v1` 结尾：追加 `/responses`。
     * - 其他：先尝试 `/v1/responses`，再回退到 `/responses`。
     */
    fun responsesCandidates(raw: String): List<String> {
        val base = normalizeBase(raw)
        return when {
            base.endsWith("/responses") -> listOf(base)
            base.endsWith("/v1") -> listOf("$base/responses")
            else -> listOf("$base/v1/responses", "$base/responses")
        }.distinct()
    }

    fun modelsCandidates(raw: String): List<String> {
        val base = normalizeBase(raw)
        return when {
            base.endsWith("/models") -> listOf(base)
            base.endsWith("/chat/completions") ->
                listOf(base.removeSuffix("/chat/completions") + "/models")
            base.endsWith("/responses") ->
                listOf(base.removeSuffix("/responses") + "/models")
            base.endsWith("/v1") -> listOf("$base/models")
            else -> listOf("$base/v1/models", "$base/models")
        }.distinct()
    }

    /** 与上游同源的 `{base}/models` 地址，用于代理对外提供模型列表。 */
    fun modelListUrl(raw: String): String = modelsCandidates(raw).first()
}