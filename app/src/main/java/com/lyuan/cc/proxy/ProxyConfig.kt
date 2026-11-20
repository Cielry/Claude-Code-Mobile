package com.lyuan.cc.proxy

import org.json.JSONArray
import org.json.JSONObject

/** 代理日志。默认静默，测试与 Android 侧可注入实现；调用方不得记录密钥。 */
fun interface ProxyLogger {
    fun log(message: String)
}

object ProxyLimits {
    const val CONNECT_TIMEOUT_MS = 20_000
    /** 与 OpenAI 会话的 API_TIMEOUT_MS 对齐，容忍长时间思考。 */
    const val READ_TIMEOUT_MS = 600_000
    const val MAX_BODY_BYTES = 64L * 1024 * 1024
    const val MAX_HEADER_BYTES = 64 * 1024
    const val HEARTBEAT_MS = 12_000L
    const val TOOL_ARGUMENT_CHUNK = 8 * 1024
    const val MAX_ERROR_BODY_BYTES = 64 * 1024
    const val USER_AGENT = "ccm-proxy/1.0"
}

data class ProxyConfig(
    val providerBaseUrl: String,
    /** 真实服务商 Key，只存在于应用内存，不写入子进程环境。 */
    val providerApiKey: String,
    val modelId: String = "",
    val smallModelId: String = "",
    val models: List<String> = emptyList(),
    val logger: ProxyLogger = ProxyLogger {},
)

object ProxyText {

    /** 粗略估算输入 token：统计 system / messages / tools 的文本长度，约 4 字符 1 token。 */
    fun estimateTokens(body: JSONObject?): Int {
        if (body == null) return 0
        var chars = 0L
        chars += stringLength(body.opt("system"))
        chars += stringLength(body.opt("messages"))
        chars += stringLength(body.opt("tools"))
        return ((chars + 3) / 4).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun stringLength(value: Any?): Long = when (value) {
        null, JSONObject.NULL -> 0L
        is String -> value.length.toLong()
        is JSONArray -> (0 until value.length()).sumOf { stringLength(value.opt(it)) }
        is JSONObject -> value.keys().asSequence().sumOf { key ->
            key.length + stringLength(value.opt(key))
        }
        else -> value.toString().length.toLong()
    }

    /** 从错误信息与日志中移除可能的密钥。 */
    fun redact(text: String, apiKey: String = ""): String {
        var result = text
        if (apiKey.isNotBlank()) result = result.replace(apiKey, "***")
        result = result.replace(Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]{8,}"""), "Bearer ***")
        result = result.replace(Regex("""sk-[A-Za-z0-9_-]{8,}"""), "***")
        return result.take(2_000)
    }
}