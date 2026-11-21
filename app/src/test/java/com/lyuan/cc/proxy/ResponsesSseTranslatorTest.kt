package com.lyuan.cc.proxy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesSseTranslatorTest {

    private val events = mutableListOf<Pair<String, JSONObject>>()
    private val translator = ResponsesSseTranslator("model-a") { event, data ->
        events.add(event to data)
    }

    @Test
    fun translatesTextAndToolCallDeltas() {
        translator.begin(11)
        consume(
            """{"type":"response.output_text.delta","output_index":0,"delta":"Hel"}"""
        )
        consume(
            """{"type":"response.output_text.delta","output_index":0,"delta":"lo"}"""
        )
        consume(
            """{"type":"response.output_text.done","output_index":0,"text":"Hello"}"""
        )
        consume(
            """{"type":"response.output_item.added","output_index":1,
                "item":{"type":"function_call","call_id":"call_1","name":"Bash","arguments":""}}"""
        )
        consume(
            """{"type":"response.function_call_arguments.delta","output_index":1,
                "delta":"{\"command\":"}"""
        )
        consume(
            """{"type":"response.function_call_arguments.delta","output_index":1,
                "delta":"\"ls\"}"}"""
        )
        consume(
            """{"type":"response.output_item.done","output_index":1,
                "item":{"type":"function_call","call_id":"call_1","name":"Bash",
                        "arguments":"{\"command\":\"ls\"}"}}"""
        )
        consume(
            """{"type":"response.completed","response":{
                 "id":"resp_1","status":"completed",
                 "output":[
                   {"type":"message","role":"assistant","content":[
                     {"type":"output_text","text":"Hello"}]},
                   {"type":"function_call","call_id":"call_1","name":"Bash",
                    "arguments":"{\"command\":\"ls\"}"}
                 ],
                 "usage":{"input_tokens":30,"output_tokens":5,
                          "input_tokens_details":{"cached_tokens":4}}}}"""
        )

        assertTrue(translator.isFinished)
        assertEquals("message_start", events.first().first)
        assertEquals("message_stop", events.last().first)

        val textDeltas = events.filter { it.first == "content_block_delta" }
            .map { it.second }
            .filter { it.getJSONObject("delta").optString("type") == "text_delta" }
        assertEquals(2, textDeltas.size)
        assertEquals("Hel", textDeltas[0].getJSONObject("delta").getString("text"))
        assertEquals("lo", textDeltas[1].getJSONObject("delta").getString("text"))

        val toolStart = events.filter { it.first == "content_block_start" }
            .map { it.second }
            .first { it.getJSONObject("content_block").getString("type") == "tool_use" }
        val tool = toolStart.getJSONObject("content_block")
        assertEquals("call_1", tool.getString("id"))
        assertEquals("Bash", tool.getString("name"))

        val inputJson = events.filter { it.first == "content_block_delta" }
            .map { it.second }
            .filter { it.getJSONObject("delta").optString("type") == "input_json_delta" }
            .joinToString("") { it.getJSONObject("delta").getString("partial_json") }
        assertEquals("""{"command":"ls"}""", inputJson)

        val delta = events.first { it.first == "message_delta" }.second
        assertEquals("tool_use", delta.getJSONObject("delta").getString("stop_reason"))
        assertEquals(26, delta.getJSONObject("usage").getInt("input_tokens"))
        assertEquals(5, delta.getJSONObject("usage").getInt("output_tokens"))
        assertEquals(4, delta.getJSONObject("usage").getInt("cache_read_input_tokens"))
    }

    @Test
    fun completedOutputWithoutDeltasStillEmitsTextAndTools() {
        translator.begin(3)
        consume(
            """{"type":"response.completed","response":{
                 "status":"completed",
                 "output":[
                   {"type":"message","role":"assistant","content":[
                     {"type":"output_text","text":"Hello"}]},
                   {"type":"function_call","call_id":"call_2","name":"Read",
                    "arguments":"{\"path\":\"/b\"}"}
                 ],
                 "usage":{"input_tokens":3,"output_tokens":2}}}"""
        )

        val text = events.filter { it.first == "content_block_delta" }
            .map { it.second }
            .filter { it.getJSONObject("delta").optString("type") == "text_delta" }
            .joinToString("") { it.getJSONObject("delta").getString("text") }
        assertEquals("Hello", text)

        val args = events.filter { it.first == "content_block_delta" }
            .map { it.second }
            .filter { it.getJSONObject("delta").optString("type") == "input_json_delta" }
            .joinToString("") { it.getJSONObject("delta").getString("partial_json") }
        assertEquals("""{"path":"/b"}""", args)
        assertEquals("message_stop", events.last().first)
    }

    @Test
    fun incompleteMarksMaxTokens() {
        translator.begin(1)
        consume(
            """{"type":"response.incomplete","response":{
                 "status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                 "usage":{"input_tokens":1,"output_tokens":2}}}"""
        )
        val delta = events.first { it.first == "message_delta" }.second
        assertEquals("max_tokens", delta.getJSONObject("delta").getString("stop_reason"))
    }

    @Test
    fun failedEventThrowsWithUpstreamMessage() {
        translator.begin(1)
        val error = assertThrows(ProxyStreamException::class.java) {
            consume(
                """{"type":"response.failed","response":{
                     "status":"failed","error":{"code":"server_error","message":"boom"}}}"""
            )
        }
        assertEquals("boom", error.message)
    }

    private fun consume(raw: String) {
        translator.consume(JSONObject(raw))
    }
}