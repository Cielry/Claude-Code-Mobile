package com.lyuan.cc.env

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Termux 软件源（binary-aarch64 / stable main）。 */
data class AptMirror(val label: String, val baseUrl: String) {

    val indexUrl: String get() = "$baseUrl/dists/stable/main/binary-aarch64/Packages"

    fun debUrl(filename: String): String = "$baseUrl/$filename"

    val cacheKey: String
        get() = baseUrl.substringAfter("://").replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        val ALL = listOf(
            AptMirror("清华", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"),
            AptMirror("阿里", "https://mirrors.aliyun.com/termux/termux-main"),
            AptMirror("腾讯", "https://mirrors.cloud.tencent.com/termux/apt/termux-main"),
            AptMirror("华为", "https://mirrors.huaweicloud.com/termux/apt/termux-main"),
            AptMirror("中科大", "https://mirrors.ustc.edu.cn/termux/apt/termux-main"),
        )

        fun default(): AptMirror = ALL.first()
    }
}

/** Packages 索引中的一个条目。 */
data class AptPackage(
    val name: String,
    val version: String,
    val filename: String,
    val size: Long,
    val sha256: String,
    val depends: List<String>,
    val provides: List<String>,
)

/** 解析后的 Packages 索引；虚拟包名（Provides）也可查到提供者。 */
class AptIndex internal constructor(
    private val byName: Map<String, AptPackage>,
    private val byProvides: Map<String, AptPackage>,
) {
    val size: Int get() = byName.size

    fun get(name: String): AptPackage? = byName[name] ?: byProvides[name]

    companion object {
        private val nameRegex = Regex("[A-Za-z0-9][A-Za-z0-9+._~-]*")

        fun parse(text: String): AptIndex {
            val packages = LinkedHashMap<String, AptPackage>()
            val providers = LinkedHashMap<String, AptPackage>()

            var name: String? = null
            var version: String? = null
            var filename: String? = null
            var size = 0L
            var sha256 = ""
            var depends = emptyList<String>()
            var preDepends = emptyList<String>()
            var provides = emptyList<String>()

            fun flush() {
                val id = name
                val ver = version
                val file = filename
                if (id != null && ver != null && file != null) {
                    val pkg = AptPackage(
                        name = id,
                        version = ver,
                        filename = file,
                        size = size,
                        sha256 = sha256.lowercase(),
                        depends = (preDepends + depends).distinct(),
                        provides = provides,
                    )
                    packages[id] = pkg
                    provides.forEach { providers.putIfAbsent(it, pkg) }
                }
                name = null
                version = null
                filename = null
                size = 0L
                sha256 = ""
                depends = emptyList()
                preDepends = emptyList()
                provides = emptyList()
            }

            text.lineSequence().forEach { line ->
                if (line.isBlank()) {
                    flush()
                    return@forEach
                }
                // 续行（以空格开头）不属于需要解析的字段。
                if (line.first().isWhitespace()) return@forEach
                val colon = line.indexOf(':')
                if (colon <= 0) return@forEach
                val key = line.substring(0, colon)
                val value = line.substring(colon + 1).trim()
                when (key) {
                    "Package" -> name = value
                    "Version" -> version = value
                    "Filename" -> filename = value
                    "Size" -> size = value.toLongOrNull() ?: 0L
                    "SHA256" -> sha256 = value
                    "Depends" -> depends = parseRelations(value)
                    "Pre-Depends" -> preDepends = parseRelations(value)
                    "Provides" -> provides = parseRelations(value)
                }
            }
            flush()
            return AptIndex(packages, providers)
        }

        /**
         * 解析 Depends/Provides：去掉版本约束（括号）与备选项（`|` 取第一个），
         * 过滤掉不认识的包名。
         */
        private fun parseRelations(value: String): List<String> =
            value.split(',').mapNotNull { part ->
                val alternative = part.substringBefore('|').trim()
                val id = alternative.substringBefore('(').trim()
                id.takeIf { it.isNotEmpty() && nameRegex.matches(it) }
            }
    }
}

/** Termux 镜像索引、.deb 解包。 */
object AptRepo {

    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_INDEX_BYTES = 16 * 1024 * 1024
    private val indexMutex = Mutex()

    /**
     * 读取 Packages 索引：优先使用 6 小时内的缓存；下载失败时回退到旧缓存。
     * [force] 为 true 时忽略缓存（用于索引里找不到包时刷新）。
     */
    suspend fun loadIndex(mirror: AptMirror, cacheDir: File, force: Boolean = false): AptIndex =
        indexMutex.withLock {
        withContext(Dispatchers.IO) {
            val cache = File(cacheDir, mirror.cacheKey + ".Packages")
            val fresh = cache.isFile &&
                System.currentTimeMillis() - cache.lastModified() < CACHE_TTL_MS
            if (!force && fresh) {
                runCatching { AptIndex.parse(cache.readText()) }.getOrNull()
                    ?.let { return@withContext it }
            }
            val text = try {
                fetchText(mirror.indexUrl)
            } catch (t: Throwable) {
                if (cache.isFile) {
                    return@withContext AptIndex.parse(cache.readText())
                }
                throw t
            }
            cache.parentFile?.mkdirs()
            runCatching { cache.writeText(text) }
            AptIndex.parse(text)
        }
        }

    private fun fetchText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ClaudeCodeMobile/0.1")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream(2 * 1024 * 1024)
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_INDEX_BYTES) throw IOException("索引文件过大")
                    out.write(buffer, 0, read)
                }
                return out.toString("UTF-8")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 打开 .deb 中 data.tar 的解压流（支持 xz / gzip / 裸 tar）。调用方负责关闭。
     */
    fun openDataTar(deb: File): InputStream {
        val member = findMember(deb) { it.startsWith("data.tar") }
            ?: throw IOException(".deb 中缺少 data.tar")
        val buffered = BufferedInputStream(SliceInputStream(deb, member.offset, member.size), 64 * 1024)
        return try {
            when {
                member.name.endsWith(".xz") -> XZInputStream(buffered)
                member.name.endsWith(".gz") -> GZIPInputStream(buffered)
                member.name.endsWith(".zst") || member.name.endsWith(".lzma") ->
                    throw IOException("不支持的压缩格式：${member.name}")
                else -> buffered
            }
        } catch (t: Throwable) {
            runCatching { buffered.close() }
            throw t
        }
    }

    private data class Member(val name: String, val offset: Long, val size: Long)

    private fun findMember(deb: File, predicate: (String) -> Boolean): Member? {
        RandomAccessFile(deb, "r").use { raf ->
            if (raf.length() < 8) throw IOException("不是有效的 .deb 文件")
            val magic = ByteArray(8)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "!<arch>\n") {
                throw IOException("不是有效的 .deb 文件")
            }
            var offset = 8L
            val header = ByteArray(60)
            while (offset + 60 <= raf.length()) {
                raf.seek(offset)
                raf.readFully(header)
                val name = String(header, 0, 16, Charsets.US_ASCII)
                    .trim()
                    .trimEnd('/')
                val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLongOrNull()
                    ?: throw IOException(".deb 头损坏")
                val body = offset + 60
                if (predicate(name)) {
                    if (body + size > raf.length()) throw IOException(".deb 数据不完整")
                    return Member(name, body, size)
                }
                offset = body + size
                if (offset % 2L == 1L) offset += 1
            }
            return null
        }
    }
}

/** 读取 [File] 中 [start] 起 [length] 字节的只读切片。 */
private class SliceInputStream(
    file: File,
    start: Long,
    private var remaining: Long,
) : InputStream() {

    private val raf = RandomAccessFile(file, "r").apply { seek(start) }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        if (len == 0) return 0
        val want = minOf(len.toLong(), remaining).toInt()
        val read = raf.read(b, off, want)
        if (read > 0) remaining -= read
        return read
    }

    override fun skip(n: Long): Long {
        val want = minOf(n, remaining)
        if (want <= 0) return 0
        remaining -= want
        return want
    }

    override fun close() {
        raf.close()
    }
}