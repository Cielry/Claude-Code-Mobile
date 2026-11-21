package com.lyuan.cc.proxy

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Anthropic Messages 请求 → OpenAI Responses 请求。
 *
 * 与 Chat Completions 的主要差异：
 * - system 提示词放进 `instructions`；
 * - 消息放进 `input`，工具调用是独立的 `function_call` 项，
 *   工具结果是 `function_call_output` 项；
 * - 工具定义是扁平结构，参数上限字段叫 `max_output_tokens`；
 * - 不支持 `stop` / `stream_options`，因此不发送。
 */
object AnthropicResponsesRequestMapper {

    fun toResponses(body: JSONObject, config: ProxyConfig): JSONObject {
        val out = JSONObject()
        out.put("model", body.optString("model").ifBlank { config.modelId })

        val systemParts = mutableListOf<String>()
        AnthropicRequestMapper.appendSystem(systemParts, body.opt("system"))

        val input = JSONArray()
        val source = body.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until source.length()) {
            val message = source.optJSONObject(index) ?: continue
            when (message.optString("role").lowercase()) {
                "system" -> AnthropicRequestMapper.appendSystem(systemParts, message.opt("content"))
                "user" -> mapUser(message, input)
                "assistant" -> mapAssistant(message, input)
                "tool" -> mapToolRole(message, input)
            }
        }

        val systemText = systemParts.joinToString("\n\n").trim()
        if (systemText.isNotEmpty()) out.put("instructions", systemText)
        out.put("input", input)

        mapTools(body.optJSONArray("tools"))?.let { out.put("tools", it) }
        mapToolChoice(body.opt("tool_choice"))?.let { out.put("tool_choice", it) }
        val choice = body.optJSONObject("tool_choice")
        if (choice?.optBoolean("disable_parallel_tool_use", false) == true) {
            out.put("parallel_tool_calls", false)
        }

        val maxTokens = body.optInt("max_tokens", 0)
        if (maxTokens > 0) out.put("max_output_tokens", maxTokens)

        if (body.optBoolean("stream", false)) out.put("stream", true)
        if (body.has("temperature")) out.put("temperature", body.optDouble("temperature"))
        if (body.has("top_p")) out.put("top_p", body.optDouble("top_p"))

        return out
    }

    private fun mapUser(message: JSONObject, target: JSONArray) {
        val content = message.opt("content")
        if (content is String) {
            if (content.isNotBlank()) target.put(inputMessage("user", content))
            return
        }
        val array = content as? JSONArray ?: return

        val parts = mutableListOf<JSONObject>()

        fun flush() {
            if (parts.isEmpty()) return
            val item = JSONObject().put("role", "user")
            if (parts.all { it.optString("type") == "input_text" }) {
                item.put("content", parts.joinToString("") { it.optString("text") })
            } else {
                item.put("content", JSONArray(parts))
            }
            target.put(item)
            parts.clear()
        }

        for (index in 0 until array.length()) {
            val block = array.optJSONObject(index) ?: continue
            when (block.optString("type")) {
                "text" -> block.optString("text").takeIf { it.isNotEmpty() }?.let {
                    parts.add(JSONObject().put("type", "input_text").put("text", it))
                }
                "image" -> imagePart(block)?.let(parts::add)
                "tool_result" -> {
                    flush()
                    toolResultItem(block)?.let(target::put)
                }
                "document" -> {
                    val name = block.optJSONObject("source")?.optString("name").orEmpty()
                    val placeholder = "[document omitted${if (name.isBlank()) "" else ": $name"}]"
                    parts.add(JSONObject().put("type", "input_text").put("text", placeholder))
                }
                "thinking", "redacted_thinking" -> Unit
                else -> block.optString("text").takeIf { it.isNotBlank() }?.let {
                    parts.add(JSONObject().put("type", "input_text").put("text", it))
                }
            }
        }
        flush()
    }

    private fun mapAssistant(message: JSONObject, target: JSONArray) {
        val content = message.opt("content")
        if (content is String) {
            if (content.isNotBlank()) target.put(inputMessage("assistant", content))
            return
        }
        val array = content as? JSONArray ?: return

        val text = StringBuilder()
        val calls = mutableListOf<JSONObject>()
        for (index in 0 until array.length()) {
            val block = array.optJSONObject(index) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "tool_use" -> {
                    val id = block.optString("id").ifBlank { "call_${UUID.randomUUID()}" }
                    val name = block.optString("name").ifBlank { "unknown_tool" }
                    val input = block.optJSONObject("input") ?: JSONObject()
                    calls.add(
                        JSONObject()
                            .put("type", "function_call")
                            .put("call_id", id)
                            .put("name", name)
                            .put("arguments", input.toString())
                    )
                }
                else -> Unit
            }
        }
        if (text.isNotBlank()) target.put(inputMessage("assistant", text.toString()))
        calls.forEach(target::put)
    }

    private fun mapToolRole(message: JSONObject, target: JSONArray) {
        val id = message.optString("tool_call_id")
        val content = AnthropicRequestMapper.contentText(message.opt("content"))
        if (id.isBlank()) {
            if (content.isNotBlank()) target.put(inputMessage("user", content))
            return
        }
        target.put(functionOutput(id, content))
    }

    private fun toolResultItem(block: JSONObject): JSONObject? {
        val id = block.optString("tool_use_id")
        if (id.isBlank()) return null
        var text = AnthropicRequestMapper.contentText(block.opt("content"))
        if (block.optBoolean("is_error", false)) {
            text = "Error: " + text.ifBlank { "unknown error" }
        }
        return functionOutput(id, text)
    }

    private fun functionOutput(callId: String, output: String): JSONObject = JSONObject()
        .put("type", "function_call_output")
        .put("call_id", callId)
        .put("output", output.ifBlank { "(empty)" })

    private fun inputMessage(role: String, text: String): JSONObject = JSONObject()
        .put("role", role)
        .put("content", text)

    private fun imagePart(block: JSONObject): JSONObject? {
        val source = block.optJSONObject("source") ?: return null
        val url = when (source.optString("type")) {
            "base64" -> {
                val mediaType = source.optString("media_type").ifBlank { "image/png" }
                val data = source.optString("data")
                if (data.isBlank()) null else "data:$mediaType;base64,$data"
            }
            "url" -> source.optString("url").takeIf { it.isNotBlank() }
            else -> null
        } ?: return null
        return JSONObject().put("type", "input_image").put("image_url", url)
    }

    private fun mapTools(tools: JSONArray?): JSONArray? {
        if (tools == null) return null
        val out = JSONArray()
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: continue
            val name = tool.optString("name")
            if (name.isBlank()) continue
            val function = JSONObject().put("type", "function").put("name", name)
            tool.optString("description").takeIf { it.isNotBlank() }?.let {
                function.put("description", it)
            }
            function.put(
                "parameters",
                AnthropicRequestMapper.sanitizeSchema(tool.optJSONObject("input_schema") ?: JSONObject()),
            )
            out.put(function)
        }
        return if (out.length() > 0) out else null
    }

    private fun mapToolChoice(value: Any?): Any? = when (value) {
        is JSONObject -> when (value.optString("type")) {
            "auto" -> "auto"
            "any" -> "required"
            "none" -> "none"
            "tool" -> JSONObject()
                .put("type", "function")
                .put("name", value.optString("name"))
            else -> null
        }
        is String -> value.takeIf { it.isNotBlank() }
        else -> null
    }
}