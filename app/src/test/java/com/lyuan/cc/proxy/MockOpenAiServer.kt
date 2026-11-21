package com.lyuan.cc.proxy

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** 测试用的最小 OpenAI 兼容上游：支持非流式、SSE、鉴权错误和 stream_options 拒绝。 */
internal class MockOpenAiServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "mock-openai").apply { isDaemon = true }
    }

    @Volatile var lastAuthorization: String? = null
    @Volatile var lastBody: JSONObject? = null
    @Volatile var requestCount = 0
    @Volatile var chatRequests = 0
    @Volatile var responsesRequests = 0
    @Volatile var lastPath: String = ""
    @Volatile var rejectStreamOptions = false
    @Volatile var ignoreStream = false
    @Volatile var includeToolCall = true
    /** 第一次请求返回 Bash 工具调用，收到 tool 结果后返回文本 “tool-ok”。 */
    @Volatile var toolThenText = false
    /** 拒绝对 /v1/... 的请求，用于验证端点回退与缓存。 */
    @Volatile var rejectV1 = false
    val paths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    val port: Int get() = server.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port/v1"

    init {
        Thread({
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                executor.execute { runCatching { handle(socket) } }
            }
        }, "mock-openai-accept").apply { isDaemon = true }.start()
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()
            paths.add(path)
            lastPath = path
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(body, offset, length - offset)
                if (read < 0) break
                offset += read
            }
            val output = BufferedOutputStream(socket.getOutputStream())
            if (rejectV1 && path.startsWith("/v1/")) {
                respond(
                    output,
                    404,
                    """{"error":{"message":"not found"}}""",
                    "application/json",
                )
            } else if (path.endsWith("/chat/completions")) {
                handleChat(JSONObject(String(body, Charsets.UTF_8)), headers, output)
            } else if (path.endsWith("/responses")) {
                handleResponses(JSONObject(String(body, Charsets.UTF_8)), headers, output)
            } else {
                respond(output, 200, """{"data":[{"id":"model-a"}]}""", "application/json")
            }
            output.flush()
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleChat(
        json: JSONObject,
        headers: Map<String, String>,
        output: BufferedOutputStream,
    ) {
        requestCount++
        chatRequests++
        lastBody = json
        lastAuthorization = headers["authorization"]
        if (json.optString("model") == "force-401") {
            respond(
                output,
                401,
                """{"error":{"message":"invalid key","type":"invalid_request_error"}}""",
                "application/json",
            )
            return
        }
        if (rejectStreamOptions && json.has("stream_options")) {
            respond(
                output,
                400,
                """{"error":{"message":"Unsupported parameter: stream_options"}}""",
                "application/json",
            )
            return
        }
        if (!json.optBoolean("stream") || ignoreStream) {
            respond(
                output,
                200,
                """
                {
                  "id": "chatcmpl-1",
                  "choices": [{
                    "finish_reason": "stop",
                    "message": {"role": "assistant", "content": "Hi"}
                  }],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 1}
                }
                """.trimIndent(),
                "application/json",
            )
            return
        }
        output.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        output.flush()
        val hasToolResult = json.optJSONArray("messages")?.let { messages ->
            (0 until messages.length()).any {
                messages.optJSONObject(it)?.optString("role") == "tool"
            }
        } ?: false
        val events = when {
            toolThenText && !hasToolResult -> listOf(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"Bash","arguments":"{\"command\":\"echo proxy-tool-ok\"}"}}]},"finish_reason":"tool_calls"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2}}""",
                "[DONE]",
            )
            toolThenText -> listOf(
                """{"choices":[{"delta":{"role":"assistant","content":"tool-ok"}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":20,"completion_tokens":2}}""",
                "[DONE]",
            )
            includeToolCall -> listOf(
                """{"choices":[{"delta":{"role":"assistant","content":"Hi"}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"Read","arguments":"{\"path\":\"/a\"}"}}]},"finish_reason":"tool_calls"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2}}""",
                "[DONE]",
            )
            else -> listOf(
                """{"choices":[{"delta":{"role":"assistant","content":"Hi"}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2}}""",
                "[DONE]",
            )
        }
        events.forEach { line ->
            output.write("data: $line\n\n".toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun handleResponses(
        json: JSONObject,
        headers: Map<String, String>,
        output: BufferedOutputStream,
    ) {
        requestCount++
        responsesRequests++
        lastBody = json
        lastAuthorization = headers["authorization"]
        if (json.optString("model") == "force-401") {
            respond(
                output,
                401,
                """{"error":{"message":"invalid key","type":"invalid_request_error"}}""",
                "application/json",
            )
            return
        }

        val input = json.optJSONArray("input")
        val hasToolResult = (0 until (input?.length() ?: 0)).any { index ->
            input?.optJSONObject(index)?.optString("type") == "function_call_output"
        }
        val emitTool = if (toolThenText) !hasToolResult else includeToolCall
        val text = if (toolThenText && hasToolResult) "tool-ok" else "Hi"
        val toolName = if (toolThenText) "Bash" else "Read"
        val toolArguments =
            if (toolThenText) """{"command":"echo proxy-tool-ok"}""" else """{"path":"/a"}"""

        val messageItem = JSONObject()
            .put("type", "message")
            .put("role", "assistant")
            .put(
                "content",
                org.json.JSONArray().put(
                    JSONObject().put("type", "output_text").put("text", text)
                ),
            )
        val toolItem = JSONObject()
            .put("type", "function_call")
            .put("id", "fc_1")
            .put("call_id", "call_1")
            .put("name", toolName)
            .put("arguments", toolArguments)
        val outputItems = org.json.JSONArray()
        if (text.isNotEmpty()) outputItems.put(messageItem)
        if (emitTool) outputItems.put(toolItem)

        val completed = JSONObject()
            .put("type", "response.completed")
            .put(
                "response",
                JSONObject()
                    .put("id", "resp_1")
                    .put("object", "response")
                    .put("status", "completed")
                    .put("output", outputItems)
                    .put(
                        "usage",
                        JSONObject()
                            .put("input_tokens", 10)
                            .put("output_tokens", 2)
                            .put("total_tokens", 12),
                    ),
            )

        if (!json.optBoolean("stream") || ignoreStream) {
            respond(
                output,
                200,
                JSONObject()
                    .put("id", "resp_1")
                    .put("object", "response")
                    .put("status", "completed")
                    .put("output", outputItems)
                    .put(
                        "usage",
                        JSONObject()
                            .put("input_tokens", 10)
                            .put("output_tokens", 2)
                            .put("total_tokens", 12),
                    )
                    .toString(),
                "application/json",
            )
            return
        }

        output.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        output.flush()

        val events = mutableListOf<Pair<String, JSONObject>>()
        events.add("response.created" to JSONObject().put("type", "response.created"))
        if (text.isNotEmpty()) {
            events.add(
                "response.output_item.added" to JSONObject()
                    .put("type", "response.output_item.added")
                    .put("output_index", 0)
                    .put(
                        "item",
                        JSONObject().put("type", "message").put("role", "assistant"),
                    ),
            )
            events.add(
                "response.output_text.delta" to JSONObject()
                    .put("type", "response.output_text.delta")
                    .put("output_index", 0)
                    .put("content_index", 0)
                    .put("delta", text),
            )
            events.add(
                "response.output_text.done" to JSONObject()
                    .put("type", "response.output_text.done")
                    .put("output_index", 0)
                    .put("content_index", 0)
                    .put("text", text),
            )
            events.add(
                "response.output_item.done" to JSONObject()
                    .put("type", "response.output_item.done")
                    .put("output_index", 0)
                    .put("item", messageItem),
            )
        }
        if (emitTool) {
            val toolIndex = if (text.isNotEmpty()) 1 else 0
            events.add(
                "response.output_item.added" to JSONObject()
                    .put("type", "response.output_item.added")
                    .put("output_index", toolIndex)
                    .put(
                        "item",
                        JSONObject()
                            .put("type", "function_call")
                            .put("call_id", "call_1")
                            .put("name", toolName)
                            .put("arguments", ""),
                    ),
            )
            val split = toolArguments.length / 2
            events.add(
                "response.function_call_arguments.delta" to JSONObject()
                    .put("type", "response.function_call_arguments.delta")
                    .put("output_index", toolIndex)
                    .put("delta", toolArguments.substring(0, split)),
            )
            events.add(
                "response.function_call_arguments.delta" to JSONObject()
                    .put("type", "response.function_call_arguments.delta")
                    .put("output_index", toolIndex)
                    .put("delta", toolArguments.substring(split)),
            )
            events.add(
                "response.function_call_arguments.done" to JSONObject()
                    .put("type", "response.function_call_arguments.done")
                    .put("output_index", toolIndex)
                    .put("arguments", toolArguments),
            )
            events.add(
                "response.output_item.done" to JSONObject()
                    .put("type", "response.output_item.done")
                    .put("output_index", toolIndex)
                    .put("item", toolItem),
            )
        }
        events.add(completed.getString("type") to completed)

        events.forEach { (name, payload) ->
            output.write("event: $name\ndata: $payload\n\n".toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun respond(
        output: BufferedOutputStream,
        code: Int,
        body: String,
        contentType: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        output.write(
            ("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
        }
        return buffer.toString("UTF-8")
    }

    override fun close() {
        runCatching { server.close() }
        executor.shutdownNow()
    }
}