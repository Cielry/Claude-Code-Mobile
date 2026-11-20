package com.lyuan.cc.proxy

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class ProxyHttpRequest(
    val method: String,
    val path: String,
    val query: String?,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** 单个连接的响应写入器。只支持一次响应；JSON 或 SSE。 */
class ProxyHttpResponse internal constructor(private val socket: Socket) {

    private val output: OutputStream = BufferedOutputStream(socket.getOutputStream())
    private var headersSent = false
    private var closed = false

    @Synchronized
    fun sendJson(status: Int, body: String) {
        if (headersSent || closed) return
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
        headersSent = true
    }

    @Synchronized
    fun startSse() {
        if (headersSent || closed) return
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.flush()
        headersSent = true
    }

    @Synchronized
    fun sendEvent(name: String, data: JSONObject) {
        if (closed) return
        if (!headersSent) startSse()
        val payload = "event: $name\ndata: ${data.toString()}\n\n"
        output.write(payload.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    @Synchronized
    fun sendPing() {
        sendEvent("ping", JSONObject().put("type", "ping"))
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runCatching { output.flush() }
        runCatching { socket.close() }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Error"
    }
}

/**
 * 最小 HTTP/1.1 环回服务器：只接受 127.0.0.1 连接，每个连接处理一次请求后关闭。
 * 不依赖第三方库，SSE 逐事件 flush。
 */
class LoopbackHttpServer(
    private val logger: ProxyLogger,
    private val handler: (ProxyHttpRequest, ProxyHttpResponse) -> Unit,
) {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ccm-proxy-worker").apply { isDaemon = true }
    }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var running = true
    private val acceptThread = Thread(::acceptLoop, "ccm-proxy-accept").apply { isDaemon = true }

    val port: Int get() = server.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun start(): LoopbackHttpServer {
        acceptThread.start()
        return this
    }

    fun close() {
        running = false
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        executor.shutdownNow()
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                server.accept()
            } catch (_: Throwable) {
                break
            }
            sockets.add(socket)
            executor.execute {
                try {
                    handleConnection(socket)
                } finally {
                    sockets.remove(socket)
                    runCatching { socket.close() }
                }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val request = parseRequest(input, socket) ?: return
            val response = ProxyHttpResponse(socket)
            try {
                handler(request, response)
            } finally {
                response.close()
            }
        } catch (_: UnsupportedBodyEncoding) {
            runCatching {
                ProxyHttpResponse(socket).sendJson(
                    415,
                    """{"type":"error","error":{"type":"invalid_request_error","message":"不支持 Content-Encoding"}}""",
                )
            }
        } catch (t: Throwable) {
            logger.log("环回连接异常：${t.javaClass.simpleName}")
            runCatching {
                ProxyHttpResponse(socket).sendJson(
                    400,
                    """{"type":"error","error":{"type":"invalid_request_error","message":"请求格式不正确"}}""",
                )
            }
        }
    }

    private fun parseRequest(input: InputStream, socket: Socket): ProxyHttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw IOException("请求行格式不正确")
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = LinkedHashMap<String, String>()
        var headerBytes = requestLine.length
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > ProxyLimits.MAX_HEADER_BYTES) throw IOException("请求头过大")
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            headers[name] = if (headers.containsKey(name)) headers[name] + ", " + value else value
        }

        headers["content-encoding"]?.let {
            if (it.isNotBlank() && !it.equals("identity", ignoreCase = true)) {
                throw UnsupportedBodyEncoding()
            }
        }

        if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            socket.getOutputStream().apply {
                write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
        }

        val body = readBody(input, headers)
        val rawPath = target.substringBefore('?')
        val path = if (rawPath.startsWith("http://") || rawPath.startsWith("https://")) {
            runCatching { java.net.URI(rawPath).path }.getOrDefault("/")
        } else {
            rawPath.ifBlank { "/" }
        }
        val query = target.substringAfter('?', "").takeIf { '?' in target }
        return ProxyHttpRequest(method, path, query, headers, body)
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val transferEncoding = headers["transfer-encoding"].orEmpty().lowercase()
        if (transferEncoding.contains("chunked")) return readChunked(input)
        val length = headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
        if (length <= 0) return ByteArray(0)
        if (length > ProxyLimits.MAX_BODY_BYTES) throw IOException("请求体过大")
        val data = ByteArray(length.toInt())
        var offset = 0
        while (offset < data.size) {
            val read = input.read(data, offset, data.size - offset)
            if (read < 0) throw IOException("请求体不完整")
            offset += read
        }
        return data
    }

    private fun readChunked(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: throw IOException("chunked 请求体不完整")
            val size = line.substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("chunked 长度无效")
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            if (output.size().toLong() + size > ProxyLimits.MAX_BODY_BYTES) {
                throw IOException("请求体过大")
            }
            val chunk = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = input.read(chunk, offset, size - offset)
                if (read < 0) throw IOException("chunked 数据不完整")
                offset += read
            }
            output.write(chunk)
            readLine(input)
        }
        return output.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (buffer.size() == 0) return null
                break
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
            if (buffer.size() > ProxyLimits.MAX_HEADER_BYTES) throw IOException("行过长")
        }
        return buffer.toString(Charsets.UTF_8.name())
    }
}

class UnsupportedBodyEncoding : IOException("不支持的 Content-Encoding")