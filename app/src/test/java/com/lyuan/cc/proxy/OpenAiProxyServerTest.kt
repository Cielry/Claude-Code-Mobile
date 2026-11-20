package com.lyuan.cc.proxy

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors

class OpenAiProxyServerTest {

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
    fun nonStreamingRequestIsTranslatedBothWays() {
        val (code, body) = post(
            "/v1/messages",
            """{"model":"model-a","max_tokens":64,"messages":[{"role":"user","content":"hi"}]}""",
        )
        assertEquals(200, code)
        val message = JSONObject(body)
        assertEquals("message", message.getString("type"))
        assertEquals("end_turn", message.getString("stop_reason"))
        assertEquals("Hi", message.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("Bearer sk-test-secret", mock.lastAuthorization)
        assertEquals("model-a", mock.lastBody?.getString("model"))
        assertEquals("hi", mock.lastBody?.getJSONArray("messages")?.getJSONObject(0)?.getString("content"))
    }

    @Test
    fun streamingRequestProducesAnthropicEvents() {
        val (code, body) = post(
            "/v1/messages",
            """{"model":"model-a","max_tokens":64,"stream":true,"messages":[{"role":"user","content":"hi"}]}""",
        )
        assertEquals(200, code)
        assertTrue(body.contains("event: message_start"))
        assertTrue(body.contains("event: content_block_delta"))
        assertTrue(body.contains("event: message_stop"))
        assertTrue(body.contains("\"text\":\"Hi\""))
        assertTrue(body.contains("tool_use"))
        assertTrue(body.contains("call_1"))
        assertTrue(mock.lastBody?.getBoolean("stream") == true)
        assertTrue(mock.lastBody?.getJSONObject("stream_options")?.getBoolean("include_usage") == true)
        assertFalse(body.contains("sk-test-secret"))
    }

    @Test
    fun wrongProxyTokenIsRejected() {
        val (code, body) = post(
            "/v1/messages",
            """{"model":"model-a","max_tokens":1,"messages":[]}""",
            token = "wrong",
        )
        assertEquals(401, code)
        assertEquals("authentication_error", JSONObject(body).getJSONObject("error").getString("type"))
    }

    @Test
    fun countTokensCacheTouchAndModelsAreServedLocally() {
        val (countCode, countBody) = post(
            "/v1/messages/count_tokens",
            """{"model":"model-a","messages":[{"role":"user","content":"hello world"}]}""",
        )
        assertEquals(200, countCode)
        assertTrue(JSONObject(countBody).getInt("input_tokens") > 0)

        val (touchCode, _) = post("/v1/messages/cache_touch", """{"request_id":"r1"}""")
        assertEquals(200, touchCode)

        val models = get("/v1/models")
        assertEquals(200, models.first)
        assertTrue(models.second.contains("model-a"))
        assertEquals(0, mock.requestCount)
    }

    @Test
    fun upstreamAuthenticationErrorIsMapped() {
        val (code, body) = post(
            "/v1/messages",
            """{"model":"force-401","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}""",
        )
        assertEquals(401, code)
        assertEquals("authentication_error", JSONObject(body).getJSONObject("error").getString("type"))
        assertFalse(body.contains("sk-test-secret"))
    }

    @Test
    fun retriesWithoutStreamOptionsWhenProviderRejectsIt() {
        mock.rejectStreamOptions = true
        val (code, body) = post(
            "/v1/messages",
            """{"model":"model-a","max_tokens":8,"stream":true,"messages":[{"role":"user","content":"hi"}]}""",
        )
        assertEquals(200, code)
        assertTrue(body.contains("event: message_stop"))
        assertEquals(2, mock.chatRequests)
        assertFalse(mock.lastBody?.has("stream_options") == true)
    }

    @Test
    fun providerIgnoringStreamFallsBackToJson() {
        mock.ignoreStream = true
        val (code, body) = post(
            "/v1/messages",
            """{"model":"model-a","max_tokens":8,"stream":true,"messages":[{"role":"user","content":"hi"}]}""",
        )
        assertEquals(200, code)
        assertTrue(body.contains("event: message_start"))
        assertTrue(body.contains("event: message_stop"))
        assertTrue(body.contains("\"text\":\"Hi\""))
    }

    @Test
    fun acceptsRealCapturedRequestShape() {
        val captured = javaClass.getResource("/captured_request.json")?.readText()
            ?: error("缺少 captured_request.json")
        val request = JSONObject(captured).put("stream", false).toString()
        val (code, body) = post("/v1/messages", request)
        assertEquals(200, code)
        assertEquals("Hi", JSONObject(body).getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(21, mock.lastBody?.getJSONArray("tools")?.length())
        val messages = mock.lastBody?.getJSONArray("messages")
        assertEquals("system", messages?.getJSONObject(0)?.getString("role"))
        assertTrue((messages?.getJSONObject(0)?.getString("content") ?: "").contains("# Environment"))
    }

    @Test
    fun cachesWorkingChatEndpointAfterFallback() {
        mock.rejectV1 = true
        val local = OpenAiProxyServer.start(
            ProxyConfig(
                providerBaseUrl = "http://127.0.0.1:${mock.port}",
                providerApiKey = "sk-test-secret",
                modelId = "model-a",
            )
        )
        try {
            val body =
                """{"model":"model-a","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}"""
            assertEquals(200, postTo(local, "/v1/messages", body).first)
            assertEquals(200, postTo(local, "/v1/messages", body).first)
            assertEquals(2, mock.paths.count { it == "/chat/completions" })
            assertEquals(1, mock.paths.count { it == "/v1/chat/completions" })
        } finally {
            local.close()
        }
    }

    @Test
    fun logsNeverContainProviderKey() {
        post(
            "/v1/messages",
            """{"model":"force-401","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}""",
        )
        assertFalse(logs.any { it.contains("sk-test-secret") })
    }

    private fun post(
        path: String,
        body: String,
        token: String = proxy.authToken,
    ): Pair<Int, String> = requestTo(proxy, "POST", path, body, token)

    private fun postTo(
        target: OpenAiProxyServer,
        path: String,
        body: String,
    ): Pair<Int, String> = requestTo(target, "POST", path, body, target.authToken)

    private fun get(path: String): Pair<Int, String> =
        requestTo(proxy, "GET", path, null, proxy.authToken)

    private fun requestTo(
        target: OpenAiProxyServer,
        method: String,
        path: String,
        body: String?,
        token: String,
    ): Pair<Int, String> {
        Socket("127.0.0.1", target.port).use { socket ->
            socket.soTimeout = 20_000
            val bodyBytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Authorization: Bearer $token\r\n")
                append("Content-Type: application/json\r\n")
                append("Connection: close\r\n")
                if (body != null) append("Content-Length: ${bodyBytes.size}\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                if (body != null) write(bodyBytes)
                flush()
            }
            val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val code = raw.substringAfter("HTTP/1.1 ").substringBefore(' ').toIntOrNull() ?: 0
            val responseBody = raw.substringAfter("\r\n\r\n", "")
            return code to responseBody
        }
    }
}
