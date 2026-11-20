package com.lyuan.cc.proxy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicRequestMapperTest {

    private val config = ProxyConfig(
        providerBaseUrl = "https://api.example.com/v1",
        providerApiKey = "sk-test",
        modelId = "fallback-model",
    )

    @Test
    fun mapsSystemToolsAndChoices() {
        val request = JSONObject(
            """
            {
              "model": "model-a",
              "max_tokens": 512,
              "stream": true,
              "temperature": 0.3,
              "top_p": 0.9,
              "top_k": 40,
              "metadata": {"user_id": "u1"},
              "thinking": {"type": "adaptive"},
              "output_config": {"effort": "high"},
              "context_management": {"edits": []},
              "system": [
                {"type": "text", "text": "system one", "cache_control": {"type": "ephemeral"}},
                {"type": "text", "text": "system two"}
              ],
              "tools": [
                {
                  "name": "Read",
                  "description": "read a file",
                  "input_schema": {
                    "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
                    "type": "object",
                    "properties": {"path": {"type": "string"}},
                    "required": ["path"],
                    "cache_control": {"type": "ephemeral"}
                  },
                  "cache_control": {"type": "ephemeral"}
                }
              ],
              "tool_choice": {"type": "any", "disable_parallel_tool_use": true},
              "stop_sequences": ["STOP", "END"],
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "text", "text": "hello"},
                    {
                      "type": "image",
                      "source": {"type": "base64", "media_type": "image/png", "data": "QUJD"}
                    }
                  ]
                },
                {
                  "role": "assistant",
                  "content": [
                    {"type": "thinking", "thinking": "hidden"},
                    {"type": "text", "text": "using a tool"},
                    {"type": "tool_use", "id": "tu_1", "name": "Read", "input": {"path": "/a"}}
                  ]
                },
                {
                  "role": "user",
                  "content": [
                    {
                      "type": "tool_result",
                      "tool_use_id": "tu_1",
                      "content": [{"type": "text", "text": "file body"}]
                    },
                    {"type": "text", "text": "continue"}
                  ]
                },
                {
                  "role": "system",
                  "content": [{"type": "text", "text": "environment block"}]
                }
              ]
            }
            """.trimIndent()
        )

        val out = AnthropicRequestMapper.toOpenAi(request, config)

        assertEquals("model-a", out.getString("model"))
        assertEquals(512, out.getInt("max_tokens"))
        assertTrue(out.getBoolean("stream"))
        assertTrue(out.getJSONObject("stream_options").getBoolean("include_usage"))
        assertEquals(0.3, out.getDouble("temperature"), 0.0001)
        assertEquals(0.9, out.getDouble("top_p"), 0.0001)
        assertFalse(out.has("top_k"))
        assertFalse(out.has("metadata"))
        assertFalse(out.has("thinking"))
        assertFalse(out.has("output_config"))
        assertFalse(out.has("context_management"))

        val messages = out.getJSONArray("messages")
        assertEquals(5, messages.length())

        val system = messages.getJSONObject(0)
        assertEquals("system", system.getString("role"))
        val systemText = system.getString("content")
        assertTrue(systemText.contains("system one"))
        assertTrue(systemText.contains("system two"))
        assertTrue(systemText.contains("environment block"))

        val user = messages.getJSONObject(1)
        assertEquals("user", user.getString("role"))
        val parts = user.getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("hello", parts.getJSONObject(0).getString("text"))
        assertEquals(
            "data:image/png;base64,QUJD",
            parts.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )

        val assistant = messages.getJSONObject(2)
        val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("tu_1", call.getString("id"))
        assertEquals("Read", call.getJSONObject("function").getString("name"))
        assertEquals(
            "/a",
            JSONObject(call.getJSONObject("function").getString("arguments")).getString("path"),
        )

        val tool = messages.getJSONObject(3)
        assertEquals("tool", tool.getString("role"))
        assertEquals("tu_1", tool.getString("tool_call_id"))
        assertEquals("file body", tool.getString("content"))
        assertEquals("continue", messages.getJSONObject(4).getString("content"))

        val tools = out.getJSONArray("tools")
        val schema = tools.getJSONObject(0).getJSONObject("function").getJSONObject("parameters")
        assertEquals("object", schema.getString("type"))
        assertTrue(schema.getJSONObject("properties").has("path"))
        assertFalse(schema.has("\$schema"))
        assertFalse(schema.has("cache_control"))
        assertFalse(tools.getJSONObject(0).has("cache_control"))

        assertEquals("required", out.getString("tool_choice"))
        assertFalse(out.getBoolean("parallel_tool_calls"))
        assertEquals(2, out.getJSONArray("stop").length())
    }

    @Test
    fun separateToolResultMessageKeepsOrdering() {
        val request = JSONObject(
            """
            {
              "model": "m",
              "max_tokens": 64,
              "messages": [
                {"role": "assistant", "content": [
                  {"type": "tool_use", "id": "a", "name": "Read", "input": {}},
                  {"type": "tool_use", "id": "b", "name": "Read", "input": {}}
                ]},
                {"role": "user", "content": [
                  {"type": "tool_result", "tool_use_id": "a", "content": "one", "is_error": true},
                  {"type": "tool_result", "tool_use_id": "b", "content": "two"}
                ]}
              ]
            }
            """.trimIndent()
        )
        val messages = AnthropicRequestMapper.toOpenAi(request, config).getJSONArray("messages")
        assertEquals(3, messages.length())
        val assistant = messages.getJSONObject(0)
        assertEquals(2, assistant.getJSONArray("tool_calls").length())
        assertEquals("tool", messages.getJSONObject(1).getString("role"))
        assertEquals("tool", messages.getJSONObject(2).getString("role"))
        // 单独的用户工具消息会并入 assistant 之后的两个 tool 消息。
        val second = AnthropicRequestMapper.toOpenAi(
            JSONObject(
                """
                {
                  "model": "m", "max_tokens": 64,
                  "messages": [
                    {"role": "user", "content": "q"},
                    {"role": "assistant", "content": [
                      {"type": "tool_use", "id": "a", "name": "Read", "input": {}}
                    ]},
                    {"role": "user", "content": [
                      {"type": "tool_result", "tool_use_id": "a", "content": "one", "is_error": true},
                      {"type": "text", "text": "go on"}
                    ]}
                  ]
                }
                """.trimIndent()
            ),
            config,
        ).getJSONArray("messages")
        assertEquals(4, second.length())
        val toolMessage = second.getJSONObject(2)
        assertEquals("tool", toolMessage.getString("role"))
        assertEquals("Error: one", toolMessage.getString("content"))
        assertEquals("user", second.getJSONObject(3).getString("role"))
        assertEquals("go on", second.getJSONObject(3).getString("content"))
    }

    @Test
    fun invalidArgumentsAndEmptyBlocksAreHandled() {
        val request = JSONObject(
            """
            {
              "model": "m",
              "max_tokens": 64,
              "messages": [
                {"role": "user", "content": []},
                {"role": "assistant", "content": [{"type": "thinking", "thinking": "x"}]}
              ]
            }
            """.trimIndent()
        )
        val out = AnthropicRequestMapper.toOpenAi(request, config)
        assertEquals(0, out.getJSONArray("messages").length())
        assertEquals("m", out.getString("model"))
    }

    @Test
    fun mapsRealCapturedClaudeCodeRequest() {
        val text = javaClass.getResource("/captured_request.json")?.readText()
            ?: error("缺少 captured_request.json")
        val request = JSONObject(text)
        val out = AnthropicRequestMapper.toOpenAi(request, config)

        assertTrue(out.getJSONArray("messages").length() > 0)
        assertEquals("test-model", out.getString("model"))
        assertEquals(21, out.getJSONArray("tools").length())
        assertFalse(out.has("thinking"))
        assertFalse(out.has("context_management"))
        assertFalse(out.has("output_config"))
        val first = out.getJSONArray("messages").getJSONObject(0)
        assertEquals("system", first.getString("role"))
        assertTrue(first.getString("content").contains("# Environment"))
        val tool = out.getJSONArray("tools").getJSONObject(0)
        assertFalse(tool.getJSONObject("function").getJSONObject("parameters").has("\$schema"))
    }

    @Test
    fun fallsBackToConfiguredModelWhenMissing() {
        val request = JSONObject("""{"max_tokens": 10, "messages": []}""")
        val out = AnthropicRequestMapper.toOpenAi(request, config)
        assertEquals("fallback-model", out.getString("model"))
    }
}