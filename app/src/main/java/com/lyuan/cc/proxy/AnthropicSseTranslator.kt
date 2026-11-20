package com.lyuan.cc.proxy

import org.json.JSONObject
import java.util.UUID

class ProxyStreamException(
    val anthropicType: String,
    override val message: String,
) : Exception(message)

/**
 * 有状态的 OpenAI SSE → Anthropic SSE 转换器。
 *
 * 文本立即流式输出；工具调用先按 index 缓冲，收到 finish_reason 后按顺序输出完整参数，
 * 避免并发工具块交错或让 Claude Code 在参数未完成时执行工具。
 */
class AnthropicSseTranslator(
    private val requestedModel: String,
    private val emit: (event: String, data: JSONObject) -> Unit,
) {
    private data class ToolBuffer(
        var id: String = "",
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder(),
    )

    private var started = false
    private var finished = false
    private var nextIndex = 0
    private var textOpen = false
    private var emittedText = false
    private var textIndex = -1
    private val tools = sortedMapOf<Int, ToolBuffer>()
    private var finishReason: String? = null
    private var usage: JSONObject? = null
    private var generatedChars = 0
    private val messageId = "msg_${UUID.randomUUID()}"

    fun begin(inputTokens: Int) {
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

    fun consume(json: JSONObject) {
        if (finished) return
        if (json.has("error")) {
            val error = json.optJSONObject("error")
            val message = error?.optString("message").orEmpty()
                .ifBlank { ProxyText.redact(json.optString("error")) }
            throw ProxyStreamException("api_error", message.ifBlank { "上游返回错误" })
        }
        ensureStarted()

        json.optJSONObject("usage")?.let { usage = it }

        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
        OpenAiResponseMapper.finishReason(choice)?.let { finishReason = it }
        val delta = choice.optJSONObject("delta") ?: return

        val content = delta.opt("content")
        if (content is String && content.isNotEmpty()) {
            openTextBlock()
            emit(
                "content_block_delta",
                JSONObject()
                    .put("type", "content_block_delta")
                    .put("index", textIndex)
                    .put(
                        "delta",
                        JSONObject().put("type", "text_delta").put("text", content),
                    ),
            )
            emittedText = true
            generatedChars += content.length
        }

        delta.optJSONArray("tool_calls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val index = if (call.has("index")) call.optInt("index", i) else i
                val buffer = tools.getOrPut(index) { ToolBuffer() }
                call.optString("id").takeIf { it.isNotBlank() }?.let { buffer.id = it }
                call.optJSONObject("function")?.let { function ->
                    function.optString("name").takeIf { it.isNotBlank() }?.let {
                        buffer.name.append(it)
                    }
                    buffer.arguments.append(function.optString("arguments"))
                }
                closeTextBlock()
            }
        }

        delta.optJSONObject("function_call")?.let { legacy ->
            val buffer = tools.getOrPut(0) { ToolBuffer() }
            legacy.optString("name").takeIf { it.isNotBlank() }?.let { buffer.name.append(it) }
            buffer.arguments.append(legacy.optString("arguments"))
            closeTextBlock()
        }
    }

    /** 收到 [DONE] 或 EOF 时调用；重复调用安全。 */
    fun complete() {
        if (finished) return
        ensureStarted()
        if (!emittedText && tools.isEmpty()) {
            // Anthropic 允许空文本块；避免 Claude Code 对空 content 数组报错。
            openTextBlock()
        }
        closeTextBlock()

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
                            .put("name", buffer.name.toString().ifBlank { "unknown_tool" })
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

        val stopReason = OpenAiResponseMapper.stopReason(finishReason, tools.isNotEmpty())
        val usageOut = JSONObject()
        val upstream = usage
        if (upstream != null) {
            val prompt = upstream.optInt("prompt_tokens", 0)
            val cached = upstream.optJSONObject("prompt_tokens_details")
                ?.optInt("cached_tokens", 0) ?: 0
            usageOut.put("input_tokens", (prompt - cached).coerceAtLeast(0))
            val output = upstream.optInt("completion_tokens", 0)
            usageOut.put(
                "output_tokens",
                if (output > 0) output else (generatedChars + 3) / 4,
            )
            if (cached > 0) usageOut.put("cache_read_input_tokens", cached)
        } else {
            usageOut.put("output_tokens", (generatedChars + 3) / 4)
        }
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
                .put("usage", usageOut),
        )
        emit("message_stop", JSONObject().put("type", "message_stop"))
        finished = true
    }

    fun fail(type: String, message: String) {
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

    val isFinished: Boolean get() = finished

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
}