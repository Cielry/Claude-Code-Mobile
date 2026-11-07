package com.lyuan.cc.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * 从 npm 镜像下载 claude-native（锁定版本），校验 tarball SHA-512 与二进制 SHA-256 后落盘。
 * 包内路径：package/claude。
 */
class RuntimeDownloader(private val runtime: RuntimeManager) {

    data class Release(
        val version: String,
        val tarballUrl: String,
        val tarballSha512Base64: String?,
        val binarySha256: String?,
        val binarySize: Long,
    )

    companion object {
        val LOCKED = Release(
            version = "2.1.301",
            tarballUrl = "https://registry.npmmirror.com/@anthropic-ai/claude-code-linux-arm64/-/claude-code-linux-arm64-2.1.301.tgz",
            tarballSha512Base64 = "vMA8QVkLBqO8fZLaCecONZhatIRtoieIwCZ8npn8b0UvinZz40i5PhBIQ52fvhnZYdX16wFdTzhij3iL1RRL7w==",
            binarySha256 = "1e119036a802d359ba5f9886428e031586e8796f7a858d5a8ee179ac7b8604f0",
            binarySize = 267247608L,
        )
    }

    /**
     * 下载并解包。onProgress(bytesDone, totalBytes) —— total 未知时为 -1。
     */
    suspend fun download(
        release: Release = LOCKED,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val dir = File(runtime.root, "downloads").apply { mkdirs() }
        val tgz = File(dir, "claude-${release.version}.tgz")
        val part = File(runtime.binDir, "claude-native.part")
        try {
            runtime.binDir.mkdirs()
            fetch(release.tarballUrl, tgz, onProgress)
            if (release.tarballSha512Base64 != null) {
                val actual = sha512Base64(tgz)
                if (actual != release.tarballSha512Base64) {
                    tgz.delete()
                    return@withContext Result.failure(IllegalStateException("tarball 校验失败"))
                }
            }
            extractBinary(tgz, part)
            val size = part.length()
            if (release.binarySize > 0 && size != release.binarySize) {
                part.delete()
                return@withContext Result.failure(
                    IllegalStateException("大小不符: $size（期望 ${release.binarySize}）")
                )
            }
            if (release.binarySha256 != null) {
                val digest = sha256Hex(part)
                if (digest != release.binarySha256) {
                    part.delete()
                    return@withContext Result.failure(IllegalStateException("二进制校验失败"))
                }
            }
            runtime.binary.delete()
            if (!part.renameTo(runtime.binary)) {
                return@withContext Result.failure(IllegalStateException("重命名失败"))
            }
            runtime.binary.setExecutable(true, false)
            tgz.delete()
            Result.success(Unit)
        } catch (t: Throwable) {
            part.delete()
            Result.failure(t)
        }
    }

    private fun fetch(url: String, dst: File, onProgress: (Long, Long) -> Unit) {
        var current = URL(url)
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "ClaudeCodeMobile/0.1")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: error("重定向缺少 Location")
                current = URL(current, location)
                conn.disconnect()
                return@repeat
            }
            if (code != 200) error("HTTP $code")
            val total = conn.contentLengthLong
            var done = 0L
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(dst).use { out ->
                    val buf = ByteArray(262144)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 8L * 1024 * 1024) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
            conn.disconnect()
            return
        }
        error("重定向过多")
    }

    private fun extractBinary(tgz: File, dst: File) {
        GZIPInputStream(BufferedInputStream(tgz.inputStream())).use { gz ->
            val header = ByteArray(512)
            while (true) {
                if (!readFully(gz, header)) break
                if (header.all { it == 0.toByte() }) break
                val name = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
                val sizeField = String(header, 124, 12, Charsets.US_ASCII)
                    .trim { it == '\u0000' || it == ' ' }
                val type = header[156].toInt().toChar()
                val size = sizeField.toLongOrNull(8) ?: 0L
                if (type == 'x' || type == 'g') {
                    skipExactly(gz, padded(size))
                    continue
                }
                if (name == "package/claude" && type != '5') {
                    FileOutputStream(dst).use { out ->
                        copyExactly(gz, out, size)
                    }
                    skipExactly(gz, padded(size) - size)
                    return
                }
                skipExactly(gz, padded(size))
            }
        }
        error("包内未找到 package/claude")
    }

    private fun padded(size: Long): Long = (size + 511) / 512 * 512

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun copyExactly(input: java.io.InputStream, out: java.io.OutputStream, size: Long) {
        var left = size
        val buf = ByteArray(262144)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) error("tar 数据截断")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skipExactly(input: java.io.InputStream, size: Long) {
        var left = size
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) return
            left -= n
        }
    }

    private fun sha512Base64(file: File): String {
        val digest = MessageDigest.getInstance("SHA-512")
        file.inputStream().use { input ->
            val buf = ByteArray(262144)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(262144)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}