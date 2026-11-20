package com.lyuan.cc.proxy

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** OpenAI Chat Completions 响应 → Anthropic Messages 响应 / 事件流。 */
object OpenAiResponseMapper {

    fun toAnthropic(
        json: JSONObject,
        requestedModel: String,
        fallbackId: String = "",
    ): JSONObject {
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message") ?: JSONObject()

        val content = JSONArray()
        val text = messageText(message)
        if (text.isNotEmpty()) {
            content.put(JSONObject().put("type", "text").put("text", text))
        }

        val toolCalls = mutableListOf<JSONObject>()
        message.optJSONArray("tool_calls")?.let { array ->
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let(toolCalls::add)
            }
        }
        message.optJSONObject("function_call")?.let { legacy ->
            toolCalls.add(
                JSONObject().put(
                    "function",
                    JSONObject()
                        .put("name", legacy.optString("name"))
                        .put("arguments", legacy.optString("arguments")),
                )
            )
        }
        toolCalls.forEach { call ->
            content.put(toolBlock(call))
        }

        if (content.length() == 0) {
            content.put(JSONObject().put("type", "text").put("text", ""))
        }

        val hasTools = toolCalls.isNotEmpty()
        val finish = finishReason(choice)
        return JSONObject()
            .put("id", messageId(json.optString("id"), fallbackId))
            .put("type", "message")
            .put("role", "assistant")
            .put(
                "model",
                requestedModel.ifBlank { json.optString("model") },
            )
            .put("content", content)
            .put("stop_reason", stopReason(finish, hasTools))
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", usageOf(json))
    }

    /** 非流式 message 转成完整的 Anthropic SSE 事件序列，用于上游忽略 stream=true 的降级。 */
    fun emitAsStream(
        message: JSONObject,
        emit: (event: String, data: JSONObject) -> Unit,
    ) {
        val usage = message.optJSONObject("usage") ?: JSONObject()
        val inputTokens = usage.optInt("input_tokens", 0)
        val content = message.optJSONArray("content") ?: JSONArray()

        val startMessage = JSONObject(message.toString())
        startMessage.put("content", JSONArray())
        startMessage.put(
            "usage",
            JSONObject().put("input_tokens", inputTokens).put("output_tokens", 0),
        )
        emit("message_start", JSONObject().put("type", "message_start").put("message", startMessage))

        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            when (block.optString("type")) {
                "text" -> {
                    emit(
                        "content_block_start",
                        JSONObject()
                            .put("type", "content_block_start")
                            .put("index", index)
                            .put(
                                "content_block",
                                JSONObject().put("type", "text").put("text", ""),
                            ),
                    )
                    emit(
                        "content_block_delta",
                        JSONObject()
                            .put("type", "content_block_delta")
                            .put("index", index)
                            .put(
                                "delta",
                                JSONObject()
                                    .put("type", "text_delta")
                                    .put("text", block.optString("text")),
                            ),
                    )
                    emit(
                        "content_block_stop",
                        JSONObject().put("type", "content_block_stop").put("index", index),
                    )
                }
                "tool_use" -> {
                    emit(
                        "content_block_start",
                        JSONObject()
                            .put("type", "content_block_start")
                            .put("index", index)
                            .put(
                                "content_block",
                                JSONObject()
                                    .put("type", "tool_use")
                                    .put("id", block.optString("id"))
                                    .put("name", block.optString("name"))
                                    .put("input", JSONObject()),
                            ),
                    )
                    emit(
                        "content_block_delta",
                        JSONObject()
                            .put("type", "content_block_delta")
                            .put("index", index)
                            .put(
                                "delta",
                                JSONObject()
                                    .put("type", "input_json_delta")
                                    .put(
                                        "partial_json",
                                        (block.optJSONObject("input") ?: JSONObject()).toString(),
                                    ),
                            ),
                    )
                    emit(
                        "content_block_stop",
                        JSONObject().put("type", "content_block_stop").put("index", index),
                    )
                }
            }
        }

        emit(
            "message_delta",
            JSONObject()
                .put("type", "message_delta")
                .put(
                    "delta",
                    JSONObject()
                        .put("stop_reason", message.optString("stop_reason", "end_turn"))
                        .put("stop_sequence", JSONObject.NULL),
                )
                .put("usage", usage),
        )
        emit("message_stop", JSONObject().put("type", "message_stop"))
    }

    fun stopReason(finish: String?, hasTools: Boolean): String = when (finish) {
        "length", "max_tokens" -> "max_tokens"
        "tool_calls", "function_call" -> "tool_use"
        else -> if (hasTools) "tool_use" else "end_turn"
    }

    fun usageOf(json: JSONObject?): JSONObject {
        val usage = json?.optJSONObject("usage")
        val prompt = usage?.optInt("prompt_tokens", 0) ?: 0
        val cached = usage?.optJSONObject("prompt_tokens_details")
            ?.optInt("cached_tokens", 0) ?: 0
        val completion = usage?.optInt("completion_tokens", 0) ?: 0
        val result = JSONObject()
            .put("input_tokens", (prompt - cached).coerceAtLeast(0))
            .put("output_tokens", completion)
        if (cached > 0) {
            result.put("cache_read_input_tokens", cached)
            result.put("cache_creation_input_tokens", 0)
        }
        return result
    }

    fun finishReason(choice: JSONObject?): String? {
        if (choice == null || choice.isNull("finish_reason")) return null
        return choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }
    }

    fun messageId(upstream: String, fallback: String): String {
        val base = upstream.ifBlank { fallback }
        if (base.startsWith("msg_")) return base
        if (base.isNotBlank()) return "msg_$base"
        return "msg_${UUID.randomUUID()}"
    }

    private fun messageText(message: JSONObject): String {
        val content = message.opt("content")
        val refusal = message.optString("refusal")
        val text = when (content) {
            is String -> content
            is JSONArray -> textFromArray(content)
            else -> ""
        }
        return if (text.isBlank() && refusal.isNotBlank()) refusal else text
    }

    private fun textFromArray(array: JSONArray): String = buildString {
        for (i in 0 until array.length()) {
            val part = array.optJSONObject(i) ?: continue
            if (part.optString("type") == "text") append(part.optString("text"))
        }
    }

    private fun toolBlock(call: JSONObject): JSONObject {
        val function = call.optJSONObject("function") ?: JSONObject()
        val id = call.optString("id").ifBlank { "toolu_${UUID.randomUUID()}" }
        val name = function.optString("name").ifBlank { "unknown_tool" }
        return JSONObject()
            .put("type", "tool_use")
            .put("id", id)
            .put("name", name)
            .put("input", parseArguments(function.optString("arguments")))
    }

    private fun parseArguments(raw: String): JSONObject {
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse {
            JSONObject().put("_invalid_json", raw.take(1_000))
        }
    }
}