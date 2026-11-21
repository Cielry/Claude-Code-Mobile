package com.lyuan.cc.proxy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicResponsesRequestMapperTest {

    private val config = ProxyConfig(
        providerBaseUrl = "https://example.test/v1",
        providerApiKey = "k",
        modelId = "fallback-model",
    )

    @Test
    fun mapsSystemMessagesToolsAndToolResults() {
        val body = JSONObject(
            """
            {
              "model": "model-a",
              "max_tokens": 128,
              "stream": true,
              "temperature": 0.3,
              "system": [{"type": "text", "text": "system prompt"}],
              "messages": [
                {"role": "user", "content": [
                  {"type": "text", "text": "look"},
                  {"type": "image", "source": {
                    "type": "base64", "media_type": "image/png", "data": "AAA"
                  }}
                ]},
                {"role": "assistant", "content": [
                  {"type": "text", "text": "reading"},
                  {"type": "tool_use", "id": "toolu_1", "name": "Read", "input": {"path": "/a"}}
                ]},
                {"role": "user", "content": [
                  {"type": "tool_result", "tool_use_id": "toolu_1", "content": "file data"}
                ]}
              ],
              "tools": [{
                "name": "Read",
                "description": "read a file",
                "input_schema": {
                  "type": "object",
                  "properties": {"path": {"type": "string"}},
                  "cache_control": {"type": "ephemeral"}
                }
              }],
              "tool_choice": {"type": "auto", "disable_parallel_tool_use": true},
              "stop_sequences": ["STOP"]
            }
            """.trimIndent()
        )

        val out = AnthropicResponsesRequestMapper.toResponses(body, config)

        assertEquals("model-a", out.getString("model"))
        assertEquals("system prompt", out.getString("instructions"))
        assertEquals(128, out.getInt("max_output_tokens"))
        assertTrue(out.getBoolean("stream"))
        assertEquals(0.3, out.getDouble("temperature"), 0.0001)
        assertFalse(out.has("stop"))
        assertFalse(out.has("stream_options"))
        assertFalse(out.has("max_tokens"))
        assertEquals(false, out.getBoolean("parallel_tool_calls"))
        assertEquals("auto", out.getString("tool_choice"))

        val input = out.getJSONArray("input")
        val user = input.getJSONObject(0)
        assertEquals("user", user.getString("role"))
        val parts = user.getJSONArray("content")
        assertEquals("input_text", parts.getJSONObject(0).getString("type"))
        assertEquals("look", parts.getJSONObject(0).getString("text"))
        assertEquals("input_image", parts.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/png;base64,AAA",
            parts.getJSONObject(1).getString("image_url"),
        )

        val assistant = input.getJSONObject(1)
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("reading", assistant.getString("content"))
        val call = input.getJSONObject(2)
        assertEquals("function_call", call.getString("type"))
        assertEquals("toolu_1", call.getString("call_id"))
        assertEquals("Read", call.getString("name"))
        assertEquals("""{"path":"/a"}""", call.getString("arguments"))

        val result = input.getJSONObject(3)
        assertEquals("function_call_output", result.getString("type"))
        assertEquals("toolu_1", result.getString("call_id"))
        assertEquals("file data", result.getString("output"))

        val tool = out.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("Read", tool.getString("name"))
        assertFalse(tool.has("function"))
        val schema = tool.getJSONObject("parameters")
        assertEquals("object", schema.getString("type"))
        assertFalse(schema.has("cache_control"))
        assertNotNull(schema.getJSONObject("properties"))
    }

    @Test
    fun mapsToolErrorsAndStringMessages() {
        val body = JSONObject(
            """
            {
              "model": "",
              "messages": [
                {"role": "system", "content": "extra system"},
                {"role": "assistant", "content": "plain"},
                {"role": "user", "content": [
                  {"type": "tool_result", "tool_use_id": "toolu_2", "is_error": true,
                   "content": [{"type": "text", "text": "boom"}]}
                ]}
              ]
            }
            """.trimIndent()
        )

        val out = AnthropicResponsesRequestMapper.toResponses(body, config)
        assertEquals("fallback-model", out.getString("model"))
        assertEquals("extra system", out.getString("instructions"))
        assertFalse(out.has("max_output_tokens"))
        assertFalse(out.has("stream"))

        val input = out.getJSONArray("input")
        assertEquals("plain", input.getJSONObject(0).getString("content"))
        val result = input.getJSONObject(1)
        assertEquals("function_call_output", result.getString("type"))
        assertEquals("Error: boom", result.getString("output"))
    }
}