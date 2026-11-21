package com.lyuan.cc.proxy

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class UpstreamResponse(
    val code: Int,
    val contentType: String,
    val input: InputStream?,
    val errorBody: String,
    val url: String = "",
    private val disconnectAction: () -> Unit,
) : AutoCloseable {
    override fun close() = disconnectAction()
}

/** OpenAI 兼容上游客户端：不自动跟随重定向，不跨主机发送密钥。 */
class OpenAiUpstreamClient(
    private val config: ProxyConfig,
    private val logger: ProxyLogger,
    private val candidates: (String) -> List<String> = OpenAiEndpoints::chatCandidates,
    private val endpointLabel: String = "Chat Completions",
) {
    private val active: MutableSet<HttpURLConnection> =
        Collections.newSetFromMap(ConcurrentHashMap<HttpURLConnection, Boolean>())

    @Volatile
    private var closed = false

    /** 记住探测成功的 Chat Completions 地址，避免每次请求都先撞 404。 */
    @Volatile
    private var preferredUrl: String? = null

    fun open(body: JSONObject, stream: Boolean): UpstreamResponse {
        val payload = body.toString()
        val candidates = this.candidates(config.providerBaseUrl)
        val ordered = buildList {
            preferredUrl?.takeIf { it in candidates }?.let(::add)
            addAll(candidates.filter { it != preferredUrl })
        }
        var lastCode = 0
        var lastBody = ""
        var lastUrl = ordered.lastOrNull().orEmpty()
        for (url in ordered) {
            check(!closed) { "代理已关闭" }
            val response = post(url, payload, stream)
            if (response.code == 404 || response.code == 405) {
                lastCode = response.code
                lastBody = response.errorBody
                lastUrl = url
                response.close()
                continue
            }
            preferredUrl = url
            return response
        }
        return UpstreamResponse(
            code = lastCode.coerceAtLeast(404),
            contentType = "application/json",
            input = null,
            errorBody = lastBody.ifBlank { "没有可用的 $endpointLabel 地址" },
            url = lastUrl,
            disconnectAction = {},
        )
    }

    fun close() {
        closed = true
        active.toList().forEach { connection ->
            runCatching { connection.disconnect() }
        }
        active.clear()
    }

    private fun post(url: String, payload: String, stream: Boolean): UpstreamResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = ProxyLimits.CONNECT_TIMEOUT_MS
            readTimeout = ProxyLimits.READ_TIMEOUT_MS
            doInput = true
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty(
                "Accept",
                if (stream) "text/event-stream, application/json" else "application/json",
            )
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Connection", "close")
            setRequestProperty("User-Agent", ProxyLimits.USER_AGENT)
            if (config.providerApiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${config.providerApiKey}")
            }
        }
        active.add(connection)
        try {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val contentType = connection.contentType.orEmpty()
            if (code in 200..299) {
                val input = connection.inputStream
                return UpstreamResponse(code, contentType, input, "", url) {
                    release(connection)
                }
            }
            val error = readError(connection.errorStream)
            logger.log("上游返回 HTTP $code（${url.substringBefore('?')}）")
            return UpstreamResponse(code, contentType, null, error, url) {
                release(connection)
            }
        } catch (t: Throwable) {
            release(connection)
            throw t
        }
    }

    private fun release(connection: HttpURLConnection) {
        active.remove(connection)
        runCatching { connection.disconnect() }
    }

    private fun readError(stream: InputStream?): String {
        if (stream == null) return ""
        return runCatching {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8 * 1024)
                val output = StringBuilder()
                var total = 0
                while (total < ProxyLimits.MAX_ERROR_BODY_BYTES) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    output.append(buffer, 0, read)
                    total += read
                }
                output.toString()
            }
        }.getOrDefault("")
    }
}