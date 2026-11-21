package com.lyuan.cc.proxy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesResponseMapperTest {

    @Test
    fun mapsTextToolsAndUsage() {
        val json = JSONObject(
            """
            {
              "id": "resp_123",
              "object": "response",
              "status": "completed",
              "output": [
                {"type": "reasoning", "id": "rs_1", "summary": []},
                {"type": "message", "role": "assistant", "content": [
                  {"type": "output_text", "text": "Hello"}
                ]},
                {"type": "function_call", "id": "fc_1", "call_id": "call_9",
                 "name": "Bash", "arguments": "{\"command\":\"ls\"}"}
              ],
              "usage": {
                "input_tokens": 30,
                "output_tokens": 7,
                "total_tokens": 37,
                "input_tokens_details": {"cached_tokens": 10}
              }
            }
            """.trimIndent()
        )

        val message = ResponsesResponseMapper.toAnthropic(json, "model-a")

        assertEquals("message", message.getString("type"))
        assertEquals("model-a", message.getString("model"))
        assertEquals("tool_use", message.getString("stop_reason"))
        val content = message.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("Hello", content.getJSONObject(0).getString("text"))
        val tool = content.getJSONObject(1)
        assertEquals("tool_use", tool.getString("type"))
        assertEquals("call_9", tool.getString("id"))
        assertEquals("Bash", tool.getString("name"))
        assertEquals("ls", tool.getJSONObject("input").getString("command"))

        val usage = message.getJSONObject("usage")
        assertEquals(20, usage.getInt("input_tokens"))
        assertEquals(7, usage.getInt("output_tokens"))
        assertEquals(10, usage.getInt("cache_read_input_tokens"))
        assertTrue(message.getString("id").startsWith("msg_"))
    }

    @Test
    fun mapsIncompleteMaxOutputTokens() {
        val json = JSONObject(
            """
            {
              "id": "resp_2",
              "status": "incomplete",
              "incomplete_details": {"reason": "max_output_tokens"},
              "output": [
                {"type": "message", "role": "assistant", "content": [
                  {"type": "output_text", "text": "partial"}
                ]}
              ],
              "usage": {"input_tokens": 3, "output_tokens": 99}
            }
            """.trimIndent()
        )

        val message = ResponsesResponseMapper.toAnthropic(json, "m")
        assertEquals("max_tokens", message.getString("stop_reason"))
        assertEquals("partial", message.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun emptyOutputBecomesEmptyTextBlock() {
        val message = ResponsesResponseMapper.toAnthropic(
            JSONObject("""{"id":"resp_3","status":"completed","output":[]}"""),
            "m",
        )
        val content = message.getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("", content.getJSONObject(0).getString("text"))
        assertEquals("end_turn", message.getString("stop_reason"))
    }
}