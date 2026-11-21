package com.lyuan.cc.proxy

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Anthropic Messages 请求 → OpenAI Chat Completions 请求。
 *
 * 只转换双方语义明确重叠的字段；Anthropic 专有字段（thinking、cache_control、
 * context_management、output_config 等）一律丢弃，避免兼容服务商拒绝请求。
 */
object AnthropicRequestMapper {

    fun toOpenAi(body: JSONObject, config: ProxyConfig): JSONObject {
        val out = JSONObject()
        out.put("model", body.optString("model").ifBlank { config.modelId })

        val systemParts = mutableListOf<String>()
        appendSystem(systemParts, body.opt("system"))

        val messages = mutableListOf<JSONObject>()
        val source = body.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until source.length()) {
            val message = source.optJSONObject(index) ?: continue
            when (message.optString("role").lowercase()) {
                "system" -> appendSystem(systemParts, message.opt("content"))
                "user" -> mapUser(message, messages)
                "assistant" -> mapAssistant(message, messages)
                "tool" -> mapToolRole(message, messages)
            }
        }

        val merged = mutableListOf<JSONObject>()
        merged.addAll(mergeAdjacent(messages))
        val systemText = systemParts.joinToString("\n\n").trim()
        if (systemText.isNotEmpty()) {
            merged.add(0, JSONObject().put("role", "system").put("content", systemText))
        }
        out.put("messages", JSONArray(merged))

        mapTools(body.optJSONArray("tools"))?.let { out.put("tools", it) }
        mapToolChoice(body.opt("tool_choice"))?.let { out.put("tool_choice", it) }
        val choice = body.optJSONObject("tool_choice")
        if (choice?.optBoolean("disable_parallel_tool_use", false) == true) {
            out.put("parallel_tool_calls", false)
        }

        val maxTokens = body.optInt("max_tokens", 0)
        if (maxTokens > 0) out.put("max_tokens", maxTokens)

        val stream = body.optBoolean("stream", false)
        if (stream) {
            out.put("stream", true)
            out.put("stream_options", JSONObject().put("include_usage", true))
        }

        if (body.has("temperature")) out.put("temperature", body.optDouble("temperature"))
        if (body.has("top_p")) out.put("top_p", body.optDouble("top_p"))

        val stop = mutableListOf<String>()
        body.optJSONArray("stop_sequences")?.let { array ->
            for (i in 0 until array.length()) {
                if (stop.size >= 4) break
                array.optString(i).takeIf { it.isNotEmpty() }?.let(stop::add)
            }
        }
        if (stop.isNotEmpty()) out.put("stop", JSONArray(stop))

        return out
    }

    internal fun appendSystem(target: MutableList<String>, value: Any?) {
        when (value) {
            is String -> value.trim().takeIf { it.isNotEmpty() }?.let(target::add)
            is JSONArray -> for (i in 0 until value.length()) {
                val block = value.optJSONObject(i)
                val text = block?.optString("text").orEmpty()
                if (text.isNotBlank()) target.add(text)
            }
            is JSONObject -> value.optString("text")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let(target::add)
        }
    }

    private fun mapUser(message: JSONObject, target: MutableList<JSONObject>) {
        val content = message.opt("content")
        if (content is String) {
            if (content.isNotBlank()) {
                target.add(JSONObject().put("role", "user").put("content", content))
            }
            return
        }
        val array = content as? JSONArray ?: return

        val parts = JSONArray()
        val plainText = StringBuilder()
        val toolMessages = mutableListOf<JSONObject>()
        var hasImage = false

        for (i in 0 until array.length()) {
            val block = array.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> {
                    val text = block.optString("text")
                    if (text.isNotEmpty()) {
                        plainText.append(text)
                        parts.put(JSONObject().put("type", "text").put("text", text))
                    }
                }
                "image" -> {
                    val part = imagePart(block)
                    if (part != null) {
                        hasImage = true
                        parts.put(part)
                    }
                }
                "tool_result" -> toolResultMessage(block)?.let(toolMessages::add)
                "document" -> {
                    val name = block.optJSONObject("source")?.optString("name").orEmpty()
                    val placeholder = "[document omitted${if (name.isBlank()) "" else ": $name"}]"
                    plainText.append(placeholder)
                    parts.put(JSONObject().put("type", "text").put("text", placeholder))
                }
                "thinking", "redacted_thinking" -> Unit
                else -> {
                    val text = block.optString("text")
                    if (text.isNotBlank()) {
                        plainText.append(text)
                        parts.put(JSONObject().put("type", "text").put("text", text))
                    }
                }
            }
        }

        target.addAll(toolMessages)
        if (plainText.isBlank() && !hasImage) return
        val user = JSONObject().put("role", "user")
        if (hasImage) {
            user.put("content", parts)
        } else {
            user.put("content", plainText.toString())
        }
        target.add(user)
    }

    private fun mapAssistant(message: JSONObject, target: MutableList<JSONObject>) {
        val content = message.opt("content")
        if (content is String) {
            if (content.isNotBlank()) {
                target.add(JSONObject().put("role", "assistant").put("content", content))
            }
            return
        }
        val array = content as? JSONArray ?: return

        val texts = mutableListOf<String>()
        val toolCalls = JSONArray()
        for (i in 0 until array.length()) {
            val block = array.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> block.optString("text").takeIf { it.isNotEmpty() }?.let(texts::add)
                "tool_use" -> {
                    val id = block.optString("id").ifBlank { "call_${UUID.randomUUID()}" }
                    val name = block.optString("name").ifBlank { "unknown_tool" }
                    val input = block.optJSONObject("input") ?: JSONObject()
                    toolCalls.put(
                        JSONObject()
                            .put("id", id)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject().put("name", name).put("arguments", input.toString()),
                            )
                    )
                }
                else -> Unit
            }
        }
        if (texts.isEmpty() && toolCalls.length() == 0) return
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", texts.joinToString("\n"))
        if (toolCalls.length() > 0) assistant.put("tool_calls", toolCalls)
        target.add(assistant)
    }

    private fun mapToolRole(message: JSONObject, target: MutableList<JSONObject>) {
        val id = message.optString("tool_call_id")
        val content = contentText(message.opt("content"))
        if (id.isBlank()) {
            if (content.isNotBlank()) {
                target.add(JSONObject().put("role", "user").put("content", content))
            }
            return
        }
        target.add(
            JSONObject()
                .put("role", "tool")
                .put("tool_call_id", id)
                .put("content", content.ifBlank { "(empty)" })
        )
    }

    private fun toolResultMessage(block: JSONObject): JSONObject? {
        val id = block.optString("tool_use_id")
        var text = contentText(block.opt("content"))
        if (block.optBoolean("is_error", false)) {
            text = "Error: " + text.ifBlank { "unknown error" }
        }
        if (id.isBlank()) return null
        return JSONObject()
            .put("role", "tool")
            .put("tool_call_id", id)
            .put("content", text.ifBlank { "(empty)" })
    }

    internal fun contentText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                val block = value.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> append(block.optString("text"))
                    "image" -> append("[image omitted]")
                    "document" -> append("[document omitted]")
                    else -> append(block.optString("text"))
                }
            }
        }
        is JSONObject -> value.optString("text")
        else -> value.toString()
    }

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
        return JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", url))
    }

    private fun mapTools(tools: JSONArray?): JSONArray? {
        if (tools == null) return null
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            val name = tool.optString("name")
            if (name.isBlank()) continue
            val function = JSONObject().put("name", name)
            tool.optString("description").takeIf { it.isNotBlank() }?.let {
                function.put("description", it)
            }
            val schema = sanitizeSchema(tool.optJSONObject("input_schema") ?: JSONObject())
            function.put("parameters", schema)
            out.put(JSONObject().put("type", "function").put("function", function))
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
                .put("function", JSONObject().put("name", value.optString("name")))
            else -> null
        }
        is String -> value.takeIf { it.isNotBlank() }
        else -> null
    }

    /** 保留 JSON Schema 主体，去掉 Anthropic 专有/服务商易拒的元数据。 */
    internal fun sanitizeSchema(schema: JSONObject): JSONObject {
        val out = copySchema(schema)
        if (!out.has("type")) out.put("type", "object")
        if (!out.has("properties")) out.put("properties", JSONObject())
        return out
    }

    private fun copySchema(value: JSONObject): JSONObject {
        val out = JSONObject()
        val iterator = value.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key in IGNORED_SCHEMA_KEYS) continue
            when (val child = value.opt(key)) {
                is JSONObject -> out.put(key, copySchema(child))
                is JSONArray -> out.put(key, copySchemaArray(child))
                else -> out.put(key, child)
            }
        }
        return out
    }

    private fun copySchemaArray(array: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until array.length()) {
            when (val child = array.opt(i)) {
                is JSONObject -> out.put(copySchema(child))
                is JSONArray -> out.put(copySchemaArray(child))
                else -> out.put(child)
            }
        }
        return out
    }

    private fun mergeAdjacent(messages: List<JSONObject>): List<JSONObject> {
        val merged = mutableListOf<JSONObject>()
        for (message in messages) {
            val previous = merged.lastOrNull()
            val role = message.optString("role")
            val canMerge = previous != null &&
                role in setOf("user", "assistant") &&
                previous.optString("role") == role &&
                !previous.has("tool_calls") && !message.has("tool_calls") &&
                previous.opt("content") is String && message.opt("content") is String
            if (canMerge) {
                previous!!.put(
                    "content",
                    previous.optString("content") + "\n" + message.optString("content"),
                )
            } else {
                merged.add(message)
            }
        }
        return merged
    }

    private val IGNORED_SCHEMA_KEYS = setOf(
        "\$schema", "cache_control", "defer_loading", "strict", "\$comment",
    )
}