package com.lyuan.cc.proxy

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.util.Collections

class ResponsesProxyServerTest {

    private lateinit var mock: MockOpenAiServer
    private lateinit var proxy: OpenAiProxyServer
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        mock = MockOpenAiServer()
        proxy = OpenAiProxyServer.start(
            ProxyConfig(
                providerBaseUrl = "http://127.0.0.1:${mock.port}/v1",
                providerApiKey = "sk-test-secret",
                modelId = "model-a",
                models = listOf("model-a"),
                responsesApi = true,
                logger = ProxyLogger { logs.add(it) },
            )
        )
    }

    @After
    fun tearDown() {
        proxy.close()
        mock.close()
    }

    @Test
    fun nonStreamingRequestUsesResponsesEndpoint() {
        val (code, body) = post(
            "/v1/messages",
            """
            {
              "model": "model-a",
              "max_tokens": 64,
              "system": "be brief",
              "messages": [{"role": "user", "content": "hi"}]
            }
            """.trimIndent(),
        )
        assertEquals(200, code)
        val message = JSONObject(body)
        assertEquals("message", message.getString("type"))
        assertEquals("tool_use", message.getString("stop_reason"))
        val content = message.getJSONArray("content")
        assertEquals("Hi", content.getJSONObject(0).getString("text"))
        assertEquals("Read", content.getJSONObject(1).getString("name"))
        assertEquals("call_1", content.getJSONObject(1).getString("id"))

        assertEquals("/v1/responses", mock.lastPath)
        assertEquals("Bearer sk-test-secret", mock.lastAuthorization)
        val request = mock.lastBody
        assertEquals("be brief", request?.getString("instructions"))
        assertEquals(64, request?.getInt("max_output_tokens"))
        assertTrue(request?.has("input") == true)
        assertFalse(request?.has("messages") == true)
        assertFalse(request?.has("stream_options") == true)
        assertFalse(request?.has("stop") == true)
    }

    @Test
    fun streamingRequestProducesAnthropicEvents() {
        val (code, body) = post(
            "/v1/messages",
            """{"model":"model-a","max_tokens":64,"stream":true,
                "messages":[{"role":"user","content":"hi"}]}""",
        )
        assertEquals(200, code)
        assertTrue(body.contains("event: message_start"))
        assertTrue(body.contains("event: content_block_delta"))
        assertTrue(body.contains("event: message_stop"))
        assertTrue(body.contains("\"text\":\"Hi\""))
        assertTrue(body.contains("tool_use"))
        assertTrue(body.contains("call_1"))
        assertTrue(mock.lastBody?.getBoolean("stream") == true)
        assertFalse(mock.lastBody?.has("stream_options") == true)
        assertFalse(body.contains("sk-test-secret"))
    }

    @Test
    fun toolResultIsSentAsFunctionCallOutput() {
        mock.toolThenText = true
        val (code, body) = post(
            "/v1/messages",
            """
            {
              "model": "model-a",
              "max_tokens": 64,
              "messages": [
                {"role": "user", "content": "run echo"},
                {"role": "assistant", "content": [
                  {"type": "tool_use", "id": "toolu_abc", "name": "Bash",
                   "input": {"command": "echo proxy-tool-ok"}}
                ]},
                {"role": "user", "content": [
                  {"type": "tool_result", "tool_use_id": "toolu_abc",
                   "content": "proxy-tool-ok"}
                ]}
              ]
            }
            """.trimIndent(),
        )
        assertEquals(200, code)
        assertEquals(
            "tool-ok",
            JSONObject(body).getJSONArray("content").getJSONObject(0).getString("text"),
        )

        val input = mock.lastBody?.getJSONArray("input")
            ?: error("缺少 input")
        val call = (0 until input.length())
            .map { input.getJSONObject(it) }
            .first { it.optString("type") == "function_call" }
        assertEquals("toolu_abc", call.getString("call_id"))
        assertEquals("Bash", call.getString("name"))
        val result = (0 until input.length())
            .map { input.getJSONObject(it) }
            .first { it.optString("type") == "function_call_output" }
        assertEquals("toolu_abc", result.getString("call_id"))
        assertEquals("proxy-tool-ok", result.getString("output"))
    }

    @Test
    fun upstreamAuthenticationErrorIsMapped() {
        val (code, body) = post(
            "/v1/messages",
            """{"model":"force-401","max_tokens":8,"messages":[]}""",
        )
        assertEquals(401, code)
        assertEquals("authentication_error", JSONObject(body).getJSONObject("error").getString("type"))
        assertFalse(body.contains("sk-test-secret"))
        assertFalse(logs.any { it.contains("sk-test-secret") })
    }

    private fun post(path: String, body: String): Pair<Int, String> {
        Socket("127.0.0.1", proxy.port).use { socket ->
            socket.soTimeout = 20_000
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Authorization: Bearer ${proxy.authToken}\r\n")
                append("Content-Type: application/json\r\n")
                append("Connection: close\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(bodyBytes)
                flush()
            }
            val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val code = raw.substringAfter("HTTP/1.1 ").substringBefore(' ').toIntOrNull() ?: 0
            return code to raw.substringAfter("\r\n\r\n", "")
        }
    }
}