package com.lyuan.cc.proxy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicSseTranslatorTest {

    private data class Event(val name: String, val data: JSONObject)

    private fun collect(): Pair<AnthropicSseTranslator, MutableList<Event>> {
        val events = mutableListOf<Event>()
        val translator = AnthropicSseTranslator("model-a") { name, data ->
            events.add(Event(name, data))
        }
        return translator to events
    }

    @Test
    fun streamsTextAndBuffersToolCalls() {
        val (translator, events) = collect()
        translator.begin(123)
        translator.consume(JSONObject("""{"choices":[{"delta":{"content":"He"}}]}"""))
        translator.consume(JSONObject("""{"choices":[{"delta":{"content":"llo"}}]}"""))
        translator.consume(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[
                    {"index":0,"id":"call_1","function":{"name":"Read","arguments":"{\"pa"}}
                ]}}]}"""
            )
        )
        translator.consume(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[
                    {"index":0,"function":{"arguments":"th\":\"/a\"}"}}
                ]},"finish_reason":"tool_calls"}]}"""
            )
        )
        translator.consume(
            JSONObject("""{"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":7}}""")
        )
        translator.complete()

        assertEquals(
            listOf(
                "message_start",
                "content_block_start",
                "content_block_delta",
                "content_block_delta",
                "content_block_stop",
                "content_block_start",
                "content_block_delta",
                "content_block_stop",
                "message_delta",
                "message_stop",
            ),
            events.map { it.name },
        )
        assertEquals("He", events[2].data.getJSONObject("delta").getString("text"))
        assertEquals("llo", events[3].data.getJSONObject("delta").getString("text"))
        assertEquals(
            0,
            events[1].data.getInt("index"),
        )
        val toolStart = events[5].data
        assertEquals(1, toolStart.getInt("index"))
        val toolBlock = toolStart.getJSONObject("content_block")
        assertEquals("tool_use", toolBlock.getString("type"))
        assertEquals("Read", toolBlock.getString("name"))
        assertEquals(
            "/a",
            JSONObject(events[6].data.getJSONObject("delta").getString("partial_json"))
                .getString("path"),
        )
        val delta = events[8].data
        assertEquals("tool_use", delta.getJSONObject("delta").getString("stop_reason"))
        assertEquals(7, delta.getJSONObject("usage").getInt("output_tokens"))
        assertEquals(120, delta.getJSONObject("usage").getInt("input_tokens"))
    }

    @Test
    fun emptyStreamProducesEmptyTextBlock() {
        val (translator, events) = collect()
        translator.begin(1)
        translator.complete()
        assertEquals(
            listOf(
                "message_start",
                "content_block_start",
                "content_block_stop",
                "message_delta",
                "message_stop",
            ),
            events.map { it.name },
        )
        assertEquals("end_turn", events[3].data.getJSONObject("delta").getString("stop_reason"))
    }

    @Test
    fun errorChunkThrowsProxyStreamException() {
        val (translator, _) = collect()
        translator.begin(1)
        assertThrows(ProxyStreamException::class.java) {
            translator.consume(JSONObject("""{"error":{"type":"rate_limit","message":"slow down"}}"""))
        }
    }

    @Test
    fun multipleToolCallsGetSequentialBlocks() {
        val (translator, events) = collect()
        translator.begin(1)
        translator.consume(
            JSONObject(
                """{"choices":[{"delta":{"tool_calls":[
                    {"index":1,"id":"b","function":{"name":"Bash","arguments":"{}"}},
                    {"index":0,"id":"a","function":{"name":"Read","arguments":"{}"}}
                ]},"finish_reason":"tool_calls"}]}"""
            )
        )
        translator.complete()
        val starts = events.filter { it.name == "content_block_start" }
        assertEquals(2, starts.size)
        assertEquals("Read", starts[0].data.getJSONObject("content_block").getString("name"))
        assertEquals("Bash", starts[1].data.getJSONObject("content_block").getString("name"))
        assertEquals(0, starts[0].data.getInt("index"))
        assertEquals(1, starts[1].data.getInt("index"))
        assertTrue(events.last().name == "message_stop")
    }
}