package com.lyuan.cc.env

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 环境包下载：依次尝试多个镜像，写入 .part 文件。 */
object EnvDownloader {

    private const val BUFFER = 256 * 1024

    suspend fun download(
        urls: List<String>,
        dest: File,
        expectedSize: Long,
        onProgress: suspend (done: Long, total: Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        for (url in urls) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                try {
                    dest.parentFile?.mkdirs()
                    downloadOne(url, dest, expectedSize, onProgress)
                    return@withContext Result.success(Unit)
                } catch (t: Throwable) {
                    last = t
                    dest.delete()
                    // 4xx 说明地址本身不可用，换下一个镜像，不再重试。
                    if (t.message.orEmpty().startsWith("HTTP 4")) break
                    delay(400)
                }
            }
        }
        Result.failure(last ?: IOException("没有可用下载地址"))
    }

    private suspend fun downloadOne(
        url: String,
        dest: File,
        expectedSize: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ClaudeCodeMobile/0.1")
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                    output.flush()
                }
            }
            if (expectedSize > 0 && dest.length() != expectedSize) {
                throw IOException("下载大小不符：${dest.length()} != $expectedSize")
            }
        } finally {
            conn.disconnect()
        }
    }
}