package com.lyuan.cc.proxy

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI 兼容服务商的本机转换代理。
 *
 * 每个会话一个实例，只监听 127.0.0.1 的临时端口。Claude Code 使用随机代理 token
 * 访问本机；真实服务商 Key 只保存在应用内存中，并只发送给用户配置的上游。
 */
class OpenAiProxyServer private constructor(
    private val config: ProxyConfig,
    private val server: LoopbackHttpServer,
    val authToken: String,
) {
    /** 整个代理共享一个上游客户端，复用已探测成功的端点。 */
    private val upstreamClient = OpenAiUpstreamClient(config, config.logger)

    @Volatile
    private var closed = false

    val baseUrl: String get() = server.baseUrl
    val port: Int get() = server.port

    fun close() {
        if (closed) return
        closed = true
        server.close()
        runCatching { upstreamClient.close() }
    }

    private fun handle(request: ProxyHttpRequest, response: ProxyHttpResponse) {
        try {
            if (!authorized(request)) {
                response.sendJson(
                    401,
                    errorJson("authentication_error", "invalid proxy token"),
                )
                return
            }
            when {
                request.method == "POST" && request.path == "/v1/messages" ->
                    handleMessages(request, response)
                request.method == "POST" && request.path.endsWith("/count_tokens") ->
                    handleCountTokens(request, response)
                request.method in setOf("POST", "GET") && request.path.endsWith("/cache_touch") ->
                    response.sendJson(200, """{"type":"cache_touch"}""")
                request.method == "GET" && request.path == "/v1/models" ->
                    handleModels(response, null)
                request.method == "GET" && request.path.startsWith("/v1/models/") ->
                    handleModels(response, request.path.removePrefix("/v1/models/"))
                else ->
                    response.sendJson(404, errorJson("not_found_error", "未知路径"))
            }
        } catch (t: Throwable) {
            config.logger.log("代理请求失败：${t.javaClass.simpleName}")
            runCatching {
                response.sendJson(
                    500,
                    errorJson("api_error", ProxyText.redact(t.message ?: "代理内部错误", config.providerApiKey)),
                )
            }
        }
    }

    private fun authorized(request: ProxyHttpRequest): Boolean {
        val bearer = request.headers["authorization"].orEmpty()
            .takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.trim()
            .orEmpty()
        val apiKey = request.headers["x-api-key"].orEmpty().trim()
        val provided = bearer.ifBlank { apiKey }
        if (provided.isEmpty()) return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            authToken.toByteArray(Charsets.UTF_8),
        )
    }

    private fun handleCountTokens(request: ProxyHttpRequest, response: ProxyHttpResponse) {
        val body = parseBody(request) ?: run {
            response.sendJson(400, errorJson("invalid_request_error", "请求体不是有效 JSON"))
            return
        }
        response.sendJson(
            200,
            JSONObject().put("input_tokens", ProxyText.estimateTokens(body)).toString(),
        )
    }

    private fun handleModels(response: ProxyHttpResponse, id: String?) {
        val models = configuredModels()
        if (id != null) {
            val match = models.firstOrNull { it == id }
            if (match == null) {
                response.sendJson(404, errorJson("not_found_error", "模型不存在：$id"))
            } else {
                response.sendJson(200, modelObject(match).toString())
            }
            return
        }
        val data = JSONArray()
        models.forEach { data.put(modelObject(it)) }
        response.sendJson(
            200,
            JSONObject()
                .put("data", data)
                .put("has_more", false)
                .put("first_id", models.firstOrNull().orEmpty())
                .put("last_id", models.lastOrNull().orEmpty())
                .toString(),
        )
    }

    private fun configuredModels(): List<String> = buildList {
        addAll(config.models.filter { it.isNotBlank() })
        config.modelId.takeIf { it.isNotBlank() }?.let(::add)
        config.smallModelId.takeIf { it.isNotBlank() }?.let(::add)
    }.distinct()

    private fun modelObject(id: String): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "model")
        .put("display_name", id)
        .put("created_at", "1970-01-01T00:00:00Z")

    private fun handleMessages(request: ProxyHttpRequest, response: ProxyHttpResponse) {
        val body = parseBody(request) ?: run {
            response.sendJson(400, errorJson("invalid_request_error", "请求体不是有效 JSON"))
            return
        }
        val stream = body.optBoolean("stream", false)
        val openAiBody = AnthropicRequestMapper.toOpenAi(body, config)
        var upstream: UpstreamResponse? = null
        try {
            val result = openWithRetries(upstreamClient, openAiBody, stream)
            upstream = result
            if (result.code !in 200..299) {
                sendUpstreamError(response, result)
                return
            }
            if (stream && result.contentType.contains("text/event-stream", ignoreCase = true)) {
                relayStream(response, body, openAiBody, result)
            } else {
                val text = result.input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(text) }.getOrElse {
                    if (stream) {
                        response.startSse()
                        response.sendEvent(
                            "error",
                            JSONObject()
                                .put("type", "error")
                                .put(
                                    "error",
                                    JSONObject()
                                        .put("type", "api_error")
                                        .put("message", "上游返回了无法解析的响应"),
                                ),
                        )
                    } else {
                        response.sendJson(502, errorJson("api_error", "上游返回了无法解析的响应"))
                    }
                    return
                }
                if (json.has("error")) {
                    sendJsonError(response, json)
                    return
                }
                val message = OpenAiResponseMapper.toAnthropic(
                    json,
                    openAiBody.optString("model"),
                )
                if (stream) {
                    response.startSse()
                    OpenAiResponseMapper.emitAsStream(message) { event, data ->
                        response.sendEvent(event, data)
                    }
                } else {
                    response.sendJson(200, message.toString())
                }
            }
        } finally {
            runCatching { upstream?.close() }
        }
    }

    private fun openWithRetries(
        client: OpenAiUpstreamClient,
        body: JSONObject,
        stream: Boolean,
    ): UpstreamResponse {
        var current = client.open(body, stream)
        if (current.code != 400) return current

        val error = current.errorBody.lowercase()
        var changed = false
        val retryBody = JSONObject(body.toString())
        when {
            "stream_options" in error && retryBody.has("stream_options") -> {
                retryBody.remove("stream_options")
                changed = true
            }
            "max_completion_tokens" in error && retryBody.has("max_tokens") -> {
                val value: Any = retryBody.opt("max_tokens") ?: 0
                retryBody.remove("max_tokens")
                retryBody.put("max_completion_tokens", value)
                changed = true
            }
            "max_tokens" in error && retryBody.has("max_completion_tokens") -> {
                val value: Any = retryBody.opt("max_completion_tokens") ?: 0
                retryBody.remove("max_completion_tokens")
                retryBody.put("max_tokens", value)
                changed = true
            }
            "temperature" in error && retryBody.has("temperature") -> {
                retryBody.remove("temperature")
                changed = true
            }
        }
        if (!changed) return current
        current.close()
        return client.open(retryBody, stream)
    }

    private fun relayStream(
        response: ProxyHttpResponse,
        requestBody: JSONObject,
        openAiBody: JSONObject,
        upstream: UpstreamResponse,
    ) {
        val clientGone = AtomicBoolean(false)
        val translator = AnthropicSseTranslator(openAiBody.optString("model")) { event, data ->
            response.sendEvent(event, data)
        }
        val heartbeat = Heartbeat(
            ping = { response.sendPing() },
            onClientGone = {
                clientGone.set(true)
                runCatching { upstream.close() }
            },
        )
        try {
            response.startSse()
            translator.begin(ProxyText.estimateTokens(requestBody))
            heartbeat.start()
            response.sendPing()

            val reader = upstream.input?.bufferedReader(Charsets.UTF_8)
                ?: throw IllegalStateException("上游没有响应体")
            consumeSse(reader, translator, heartbeat, clientGone)
            if (!clientGone.get() && !translator.isFinished) translator.complete()
        } catch (e: ProxyStreamException) {
            if (!clientGone.get()) {
                runCatching { translator.fail(e.anthropicType, e.message) }
            }
        } catch (t: Throwable) {
            if (!clientGone.get()) {
                runCatching {
                    translator.fail(
                        "api_error",
                        ProxyText.redact(t.message ?: "上游连接中断", config.providerApiKey),
                    )
                }
            }
        } finally {
            heartbeat.stop()
        }
    }

    private fun consumeSse(
        reader: BufferedReader,
        translator: AnthropicSseTranslator,
        heartbeat: Heartbeat,
        clientGone: AtomicBoolean,
    ) {
        val data = StringBuilder()
        while (!clientGone.get()) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    dispatchSseData(data.toString(), translator, heartbeat)
                    data.setLength(0)
                }
                continue
            }
            when {
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
                line.startsWith("event:") || line.startsWith(":") -> Unit
                else -> Unit
            }
        }
        if (data.isNotEmpty() && !clientGone.get()) {
            dispatchSseData(data.toString(), translator, heartbeat)
        }
    }

    private fun dispatchSseData(
        payload: String,
        translator: AnthropicSseTranslator,
        heartbeat: Heartbeat,
    ) {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return
        if (trimmed == "[DONE]") {
            translator.complete()
            return
        }
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return
        heartbeat.firstEvent()
        translator.consume(json)
    }

    private fun sendUpstreamError(response: ProxyHttpResponse, upstream: UpstreamResponse) {
        val parsed = runCatching { JSONObject(upstream.errorBody) }.getOrNull()
        val message = parsed?.optJSONObject("error")?.optString("message")
            ?.takeIf { it.isNotBlank() }
            ?: upstream.errorBody.take(500).takeIf { it.isNotBlank() }
            ?: if (upstream.code in 300..399) {
                "上游发生重定向，请直接填写最终地址（HTTP ${upstream.code}）"
            } else {
                "上游返回 HTTP ${upstream.code}"
            }
        val status = if (upstream.code in 400..599) upstream.code else 502
        response.sendJson(
            status,
            errorJson(
                errorType(upstream.code),
                ProxyText.redact(message, config.providerApiKey),
            ),
        )
    }

    private fun sendJsonError(response: ProxyHttpResponse, json: JSONObject) {
        val message = json.optJSONObject("error")?.optString("message")
            .orEmpty()
            .ifBlank { "上游返回错误" }
        response.sendJson(
            502,
            errorJson("api_error", ProxyText.redact(message, config.providerApiKey)),
        )
    }

    private fun errorType(status: Int): String = when (status) {
        400 -> "invalid_request_error"
        401 -> "authentication_error"
        403 -> "permission_error"
        404 -> "not_found_error"
        413 -> "request_too_large"
        429 -> "rate_limit_error"
        503, 529 -> "overloaded_error"
        else -> "api_error"
    }

    private fun errorJson(type: String, message: String): String = JSONObject()
        .put("type", "error")
        .put("error", JSONObject().put("type", type).put("message", message))
        .toString()

    private fun parseBody(request: ProxyHttpRequest): JSONObject? =
        runCatching { JSONObject(String(request.body, Charsets.UTF_8)) }.getOrNull()

    private class Heartbeat(
        private val ping: () -> Unit,
        private val onClientGone: () -> Unit,
    ) {
        private val first = AtomicBoolean(false)

        @Volatile
        private var stopped = false

        private val thread = Thread({
            while (!stopped && !first.get()) {
                try {
                    Thread.sleep(ProxyLimits.HEARTBEAT_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (stopped || first.get()) break
                runCatching { ping() }.onFailure {
                    stopped = true
                    onClientGone()
                }
            }
        }, "ccm-proxy-heartbeat").apply { isDaemon = true }

        fun start() = thread.start()

        fun firstEvent() {
            first.set(true)
        }

        fun stop() {
            stopped = true
            thread.interrupt()
        }
    }

    companion object {
        fun start(config: ProxyConfig): OpenAiProxyServer {
            OpenAiEndpoints.validate(config.providerBaseUrl)?.let {
                throw IllegalArgumentException(it)
            }
            val token = randomToken()
            val holder = AtomicReference<OpenAiProxyServer>()
            val server = LoopbackHttpServer(config.logger) { request, response ->
                val proxy = holder.get()
                if (proxy == null) {
                    response.sendJson(503, errorJson("api_error", "代理尚未就绪"))
                } else {
                    proxy.handle(request, response)
                }
            }
            val proxy = OpenAiProxyServer(config, server, token)
            holder.set(proxy)
            server.start()
            config.logger.log("OpenAI 转换代理已启动：${proxy.baseUrl}")
            return proxy
        }

        private fun errorJson(type: String, message: String): String = JSONObject()
            .put("type", "error")
            .put("error", JSONObject().put("type", type).put("message", message))
            .toString()

        private fun randomToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}