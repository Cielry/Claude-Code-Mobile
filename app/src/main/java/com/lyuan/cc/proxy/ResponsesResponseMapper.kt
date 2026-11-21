package com.lyuan.cc.proxy

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** OpenAI Responses 响应 → Anthropic Messages 响应。 */
object ResponsesResponseMapper {

    fun toAnthropic(
        json: JSONObject,
        requestedModel: String,
        fallbackId: String = "",
    ): JSONObject {
        val content = JSONArray()
        val text = StringBuilder()
        val tools = mutableListOf<JSONObject>()

        val output = json.optJSONArray("output") ?: JSONArray()
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "message" -> appendMessageText(item, text)
                "function_call" -> tools.add(toolBlock(item))
                // reasoning 等条目对 Claude Code 无意义，直接丢弃。
                else -> Unit
            }
        }

        if (text.isNotEmpty()) {
            content.put(JSONObject().put("type", "text").put("text", text.toString()))
        }
        tools.forEach(content::put)
        if (content.length() == 0) {
            content.put(JSONObject().put("type", "text").put("text", ""))
        }

        val incompleteReason = json.optJSONObject("incomplete_details")
            ?.optString("reason")
            .orEmpty()
        val stopReason = when {
            tools.isNotEmpty() -> "tool_use"
            json.optString("status") == "incomplete" && incompleteReason == "max_output_tokens" ->
                "max_tokens"
            else -> "end_turn"
        }

        return JSONObject()
            .put("id", OpenAiResponseMapper.messageId(json.optString("id"), fallbackId))
            .put("type", "message")
            .put("role", "assistant")
            .put("model", requestedModel.ifBlank { json.optString("model") })
            .put("content", content)
            .put("stop_reason", stopReason)
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", usageOf(json))
    }

    fun usageOf(json: JSONObject?): JSONObject {
        val usage = json?.optJSONObject("usage")
            ?: return JSONObject().put("input_tokens", 0).put("output_tokens", 0)
        val prompt = usage.optInt("input_tokens", 0)
        val cached = usage.optJSONObject("input_tokens_details")
            ?.optInt("cached_tokens", 0) ?: 0
        val completion = usage.optInt("output_tokens", 0)
        val result = JSONObject()
            .put("input_tokens", (prompt - cached).coerceAtLeast(0))
            .put("output_tokens", completion)
        if (cached > 0) {
            result.put("cache_read_input_tokens", cached)
            result.put("cache_creation_input_tokens", 0)
        }
        return result
    }

    private fun appendMessageText(item: JSONObject, target: StringBuilder) {
        val parts = item.optJSONArray("content") ?: return
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            when (part.optString("type")) {
                "output_text", "text" -> target.append(part.optString("text"))
                "refusal" -> target.append(part.optString("refusal"))
            }
        }
    }

    private fun toolBlock(item: JSONObject): JSONObject {
        val id = item.optString("call_id")
            .ifBlank { item.optString("id") }
            .ifBlank { "toolu_${UUID.randomUUID()}" }
        val name = item.optString("name").ifBlank { "unknown_tool" }
        return JSONObject()
            .put("type", "tool_use")
            .put("id", id)
            .put("name", name)
            .put("input", OpenAiResponseMapper.parseArguments(item.optString("arguments")))
    }
}