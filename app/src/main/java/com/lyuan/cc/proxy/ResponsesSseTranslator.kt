package com.lyuan.cc.proxy

import org.json.JSONObject
import java.util.UUID

/**
 * 有状态的 OpenAI Responses SSE → Anthropic SSE 转换器。
 *
 * Responses 的文本增量是 `response.output_text.delta`，工具参数增量是
 * `response.function_call_arguments.delta`。与 Chat Completions 一致，
 * 工具调用先缓存，收到 `response.completed` 后按顺序输出完整参数，
 * 避免 Claude Code 在参数未完成时执行工具。
 */
class ResponsesSseTranslator(
    private val requestedModel: String,
    private val emit: (event: String, data: JSONObject) -> Unit,
) : ProxySseTranslator {

    private data class ToolBuffer(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    private var started = false
    private var finished = false
    private var nextIndex = 0
    private var textOpen = false
    private var emittedText = false
    private var textIndex = -1
    private val tools = sortedMapOf<Int, ToolBuffer>()
    private var usage: JSONObject? = null
    private var stopOverride: String? = null
    private var generatedChars = 0
    private val messageId = "msg_${UUID.randomUUID()}"

    override fun begin(inputTokens: Int) {
        if (started || finished) return
        started = true
        val message = JSONObject()
            .put("id", messageId)
            .put("type", "message")
            .put("role", "assistant")
            .put("model", requestedModel)
            .put("content", org.json.JSONArray())
            .put("stop_reason", JSONObject.NULL)
            .put("stop_sequence", JSONObject.NULL)
            .put(
                "usage",
                JSONObject().put("input_tokens", inputTokens).put("output_tokens", 0),
            )
        emit("message_start", JSONObject().put("type", "message_start").put("message", message))
    }

    override fun consume(json: JSONObject) {
        if (finished) return
        val type = json.optString("type")
        if (type == "error") {
            throw ProxyStreamException(
                "api_error",
                json.optString("message").ifBlank {
                    json.optJSONObject("error")?.optString("message").orEmpty()
                }.ifBlank { "上游返回错误" },
            )
        }
        ensureStarted()

        when (type) {
            "response.output_text.delta", "response.refusal.delta" -> {
                emitTextDelta(json.optString("delta"))
            }

            "response.output_text.done", "response.refusal.done" -> {
                // 兼容只发 done、不发 delta 的服务商。
                if (!emittedText) {
                    emitTextDelta(json.optString("text").ifBlank { json.optString("refusal") })
                }
                closeTextBlock()
            }

            "response.content_part.done" -> {
                if (!emittedText) {
                    val part = json.optJSONObject("part")
                    if (part != null &&
                        (part.optString("type") == "output_text" ||
                            part.optString("type") == "refusal")
                    ) {
                        emitTextDelta(part.optString("text").ifBlank { part.optString("refusal") })
                    }
                }
                closeTextBlock()
            }

            "response.output_item.added" -> {
                val item = json.optJSONObject("item") ?: return
                when (item.optString("type")) {
                    "function_call" -> {
                        val buffer = tools.getOrPut(json.optInt("output_index", tools.size)) {
                            ToolBuffer()
                        }
                        buffer.id = item.optString("call_id")
                            .ifBlank { item.optString("id") }
                            .ifBlank { buffer.id }
                        item.optString("name").takeIf { it.isNotBlank() }?.let { buffer.name = it }
                        item.optString("arguments").takeIf { it.isNotEmpty() }?.let {
                            buffer.arguments.setLength(0)
                            buffer.arguments.append(it)
                        }
                        closeTextBlock()
                    }
                }
            }

            "response.function_call_arguments.delta" -> {
                val buffer = tools.getOrPut(json.optInt("output_index", 0)) { ToolBuffer() }
                buffer.arguments.append(json.optString("delta"))
                closeTextBlock()
            }

            "response.function_call_arguments.done" -> {
                val buffer = tools.getOrPut(json.optInt("output_index", 0)) { ToolBuffer() }
                json.optString("arguments").takeIf { it.isNotEmpty() }?.let {
                    buffer.arguments.setLength(0)
                    buffer.arguments.append(it)
                }
            }

            "response.output_item.done" -> {
                val item = json.optJSONObject("item") ?: return
                when (item.optString("type")) {
                    "function_call" -> {
                        val buffer = tools.getOrPut(json.optInt("output_index", 0)) {
                            ToolBuffer()
                        }
                        buffer.id = item.optString("call_id")
                            .ifBlank { item.optString("id") }
                            .ifBlank { buffer.id }
                        item.optString("name").takeIf { it.isNotBlank() }?.let { buffer.name = it }
                        item.optString("arguments").takeIf { it.isNotEmpty() }?.let {
                            buffer.arguments.setLength(0)
                            buffer.arguments.append(it)
                        }
                        closeTextBlock()
                    }
                    "message" -> {
                        // 兼容只有条目完成事件、没有文本增量的服务商。
                        if (!emittedText) emitTextDelta(messageText(item))
                        closeTextBlock()
                    }
                }
            }

            "response.completed", "response.incomplete" -> {
                val response = json.optJSONObject("response")
                usage = response?.optJSONObject("usage") ?: json.optJSONObject("usage")
                val incompleteReason = response?.optJSONObject("incomplete_details")
                    ?.optString("reason")
                    .orEmpty()
                if (type == "response.incomplete" || incompleteReason.isNotEmpty()) {
                    stopOverride = if (incompleteReason == "max_output_tokens") "max_tokens" else "end_turn"
                }
                reconcileText(response)
                reconcileTools(response)
                complete()
            }

            "response.failed" -> {
                val response = json.optJSONObject("response")
                val error = response?.optJSONObject("error")
                val message = error?.optString("message").orEmpty()
                    .ifBlank { response?.optString("error").orEmpty() }
                    .ifBlank { "上游返回错误" }
                throw ProxyStreamException(errorType(error?.optString("code").orEmpty()), message)
            }
        }
    }

    override fun complete() {
        if (finished) return
        ensureStarted()
        closeTextBlock()
        if (!emittedText && tools.isEmpty()) {
            // Anthropic 允许空文本块；避免 Claude Code 对空 content 数组报错。
            openTextBlock()
            closeTextBlock()
        }

        for ((_, buffer) in tools) {
            val index = nextIndex++
            emit(
                "content_block_start",
                JSONObject()
                    .put("type", "content_block_start")
                    .put("index", index)
                    .put(
                        "content_block",
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", buffer.id.ifBlank { "toolu_${UUID.randomUUID()}" })
                            .put("name", buffer.name.ifBlank { "unknown_tool" })
                            .put("input", JSONObject()),
                    ),
            )
            val arguments = buffer.arguments.toString().ifBlank { "{}" }
            arguments.chunked(ProxyLimits.TOOL_ARGUMENT_CHUNK).forEach { part ->
                emit(
                    "content_block_delta",
                    JSONObject()
                        .put("type", "content_block_delta")
                        .put("index", index)
                        .put(
                            "delta",
                            JSONObject()
                                .put("type", "input_json_delta")
                                .put("partial_json", part),
                        ),
                )
            }
            emit(
                "content_block_stop",
                JSONObject().put("type", "content_block_stop").put("index", index),
            )
        }

        val stopReason = stopOverride
            ?: if (tools.isNotEmpty()) "tool_use" else "end_turn"
        emit(
            "message_delta",
            JSONObject()
                .put("type", "message_delta")
                .put(
                    "delta",
                    JSONObject()
                        .put("stop_reason", stopReason)
                        .put("stop_sequence", JSONObject.NULL),
                )
                .put("usage", usageOut()),
        )
        emit("message_stop", JSONObject().put("type", "message_stop"))
        finished = true
    }

    override fun fail(type: String, message: String) {
        if (finished) return
        finished = true
        emit(
            "error",
            JSONObject()
                .put("type", "error")
                .put(
                    "error",
                    JSONObject()
                        .put("type", type)
                        .put("message", message.take(2_000)),
                ),
        )
    }

    override val isFinished: Boolean get() = finished

    private fun ensureStarted() {
        if (!started) begin(0)
    }

    private fun openTextBlock() {
        if (textOpen) return
        textIndex = nextIndex++
        emit(
            "content_block_start",
            JSONObject()
                .put("type", "content_block_start")
                .put("index", textIndex)
                .put(
                    "content_block",
                    JSONObject().put("type", "text").put("text", ""),
                ),
        )
        textOpen = true
    }

    private fun closeTextBlock() {
        if (!textOpen) return
        emit(
            "content_block_stop",
            JSONObject().put("type", "content_block_stop").put("index", textIndex),
        )
        textOpen = false
    }

    private fun emitTextDelta(text: String) {
        if (text.isEmpty()) return
        openTextBlock()
        emit(
            "content_block_delta",
            JSONObject()
                .put("type", "content_block_delta")
                .put("index", textIndex)
                .put(
                    "delta",
                    JSONObject().put("type", "text_delta").put("text", text),
                ),
        )
        emittedText = true
        generatedChars += text.length
    }

    private fun messageText(item: JSONObject): String = buildString {
        val parts = item.optJSONArray("content") ?: return@buildString
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            when (part.optString("type")) {
                "output_text", "text" -> append(part.optString("text"))
                "refusal" -> append(part.optString("refusal"))
            }
        }
    }

    /** 用 completed 响应里的完整 output 校正可能缺失的文本/工具增量。 */
    private fun reconcileText(response: JSONObject?) {
        if (emittedText) return
        val output = response?.optJSONArray("output") ?: return
        val text = StringBuilder()
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            if (item.optString("type") == "message") text.append(messageText(item))
        }
        emitTextDelta(text.toString())
    }

    private fun reconcileTools(response: JSONObject?) {
        val output = response?.optJSONArray("output") ?: return
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            if (item.optString("type") != "function_call") continue
            val buffer = tools.getOrPut(index) { ToolBuffer() }
            buffer.id = item.optString("call_id")
                .ifBlank { item.optString("id") }
                .ifBlank { buffer.id }
            item.optString("name").takeIf { it.isNotBlank() }?.let { buffer.name = it }
            item.optString("arguments").takeIf { it.isNotEmpty() }?.let {
                buffer.arguments.setLength(0)
                buffer.arguments.append(it)
            }
        }
    }

    private fun usageOut(): JSONObject {
        val estimated = (generatedChars + 3) / 4
        val upstream = usage ?: return JSONObject().put("output_tokens", estimated)
        val prompt = upstream.optInt("input_tokens", 0)
        val cached = upstream.optJSONObject("input_tokens_details")
            ?.optInt("cached_tokens", 0) ?: 0
        val output = upstream.optInt("output_tokens", 0)
        val result = JSONObject()
            .put("input_tokens", (prompt - cached).coerceAtLeast(0))
            .put("output_tokens", if (output > 0) output else estimated)
        if (cached > 0) {
            result.put("cache_read_input_tokens", cached)
            result.put("cache_creation_input_tokens", 0)
        }
        return result
    }

    private fun errorType(code: String): String = when {
        code.contains("auth") || code.contains("api_key") -> "authentication_error"
        code.contains("permission") -> "permission_error"
        code.contains("rate_limit") || code.contains("quota") -> "rate_limit_error"
        code.contains("not_found") -> "not_found_error"
        code.contains("invalid") -> "invalid_request_error"
        else -> "api_error"
    }
}