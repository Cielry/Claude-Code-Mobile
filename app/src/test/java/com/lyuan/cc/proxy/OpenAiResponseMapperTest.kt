package com.lyuan.cc.proxy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiResponseMapperTest {

    @Test
    fun mapsTextToolsAndUsage() {
        val response = JSONObject(
            """
            {
              "id": "chatcmpl-1",
              "model": "upstream-model",
              "choices": [{
                "finish_reason": "tool_calls",
                "message": {
                  "role": "assistant",
                  "content": "let me read it",
                  "tool_calls": [{
                    "id": "call_1",
                    "type": "function",
                    "function": {"name": "Read", "arguments": "{\"path\":\"/a\"}"}
                  }]
                }
              }],
              "usage": {"prompt_tokens": 100, "completion_tokens": 20}
            }
            """.trimIndent()
        )
        val message = OpenAiResponseMapper.toAnthropic(response, "model-a")
        assertEquals("msg_chatcmpl-1", message.getString("id"))
        assertEquals("model-a", message.getString("model"))
        assertEquals("tool_use", message.getString("stop_reason"))
        val content = message.getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("tool_use", content.getJSONObject(1).getString("type"))
        assertEquals("call_1", content.getJSONObject(1).getString("id"))
        assertEquals("/a", content.getJSONObject(1).getJSONObject("input").getString("path"))
        assertEquals(100, message.getJSONObject("usage").getInt("input_tokens"))
        assertEquals(20, message.getJSONObject("usage").getInt("output_tokens"))
    }

    @Test
    fun mapsCachedTokensAndLength() {
        val response = JSONObject(
            """
            {
              "choices": [{"finish_reason": "length", "message": {"content": "partial"}}],
              "usage": {
                "prompt_tokens": 80,
                "completion_tokens": 5,
                "prompt_tokens_details": {"cached_tokens": 30}
              }
            }
            """.trimIndent()
        )
        val message = OpenAiResponseMapper.toAnthropic(response, "m")
        assertEquals("max_tokens", message.getString("stop_reason"))
        val usage = message.getJSONObject("usage")
        assertEquals(50, usage.getInt("input_tokens"))
        assertEquals(30, usage.getInt("cache_read_input_tokens"))
    }

    @Test
    fun emptyResponseGetsEmptyTextBlock() {
        val response = JSONObject("""{"choices":[{"message":{"content":null},"finish_reason":"stop"}]}""")
        val message = OpenAiResponseMapper.toAnthropic(response, "m")
        val content = message.getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("", content.getJSONObject(0).getString("text"))
        assertEquals("end_turn", message.getString("stop_reason"))
    }

    @Test
    fun invalidToolArgumentsArePreserved() {
        val response = JSONObject(
            """
            {"choices":[{"finish_reason":"tool_calls","message":{"content":"","tool_calls":[
              {"id":"c1","function":{"name":"Read","arguments":"not-json"}}
            ]}}]}
            """.trimIndent()
        )
        val block = OpenAiResponseMapper.toAnthropic(response, "m")
            .getJSONArray("content").getJSONObject(0)
        assertEquals("not-json", block.getJSONObject("input").getString("_invalid_json"))
    }

    @Test
    fun emitsCompleteAnthropicStreamForNonStreamingUpstream() {
        val message = OpenAiResponseMapper.toAnthropic(
            JSONObject(
                """
                {
                  "choices":[{"finish_reason":"stop","message":{"content":"hello"}}],
                  "usage":{"prompt_tokens":10,"completion_tokens":2}
                }
                """.trimIndent()
            ),
            "m",
        )
        val events = mutableListOf<String>()
        val data = mutableListOf<JSONObject>()
        OpenAiResponseMapper.emitAsStream(message) { event, payload ->
            events.add(event)
            data.add(payload)
        }
        assertEquals(
            listOf(
                "message_start",
                "content_block_start",
                "content_block_delta",
                "content_block_stop",
                "message_delta",
                "message_stop",
            ),
            events,
        )
        assertEquals("hello", data[2].getJSONObject("delta").getString("text"))
        assertEquals("end_turn", data[4].getJSONObject("delta").getString("stop_reason"))
        assertTrue(data[0].getJSONObject("message").getJSONArray("content").isEmpty)
    }
}