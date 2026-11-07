package com.lyuan.cc.env

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 最小但完整的 tar.gz 解压器：支持文件、目录、符号链接、硬链接、PAX 与 GNU 长文件名。
 * 仅用于已校验 SHA-256 的上游包；仍然做路径穿越防护。
 */
class TarGzExtractor(
    private val destDir: File,
    private val stripComponents: Int = 0,
) {
    private val destCanonical: File = destDir.canonicalFile
    private var longName: String? = null
    private var longLink: String? = null
    private var paxPath: String? = null
    private var paxLink: String? = null
    private var paxSize: Long? = null

    suspend fun extract(archive: File, onProgress: suspend (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            destCanonical.mkdirs()
            GZIPInputStream(FileInputStream(archive), 64 * 1024).use { raw ->
                extractTarStream(raw, onProgress)
            }
        }

    /** 接受裸 tar 流（如 .deb 中已解压的 data.tar）；调用方负责关闭流。 */
    suspend fun extractTar(input: InputStream, onProgress: suspend (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            destCanonical.mkdirs()
            extractTarStream(input, onProgress)
        }

    private suspend fun extractTarStream(raw: InputStream, onProgress: suspend (Long) -> Unit) {
                val input = raw.buffered(64 * 1024)
                val header = ByteArray(512)
                var total = 0L
                while (true) {
                    if (!readFully(input, header)) break
                    if (header.all { it.toInt() == 0 }) break

                    val name = readString(header, 0, 100)
                    val mode = readOctal(header, 100, 8)
                    val size = readSize(header, 124, 12)
                    val type = header[156].toInt().toChar()
                    val linkName = readString(header, 157, 100)
                    val prefix = readString(header, 345, 155)
                    val fullName = if (prefix.isEmpty()) name else "$prefix/$name"

                    when (type) {
                        'L' -> {
                            longName = readData(input, size).toString(Charsets.UTF_8)
                                .trimEnd('\u0000', '\n')
                            skipPadding(input, size)
                            continue
                        }
                        'K' -> {
                            longLink = readData(input, size).toString(Charsets.UTF_8)
                                .trimEnd('\u0000', '\n')
                            skipPadding(input, size)
                            continue
                        }
                        'x', 'g' -> {
                            parsePax(readData(input, size))
                            skipPadding(input, size)
                            continue
                        }
                    }

                    val entryName = longName ?: paxPath ?: fullName
                    val entryLink = longLink ?: paxLink ?: linkName
                    val entrySize = paxSize ?: size
                    longName = null
                    longLink = null
                    paxPath = null
                    paxLink = null
                    paxSize = null

                    val relative = strip(entryName)
                    if (relative == null) {
                        skipData(input, entrySize)
                        skipPadding(input, entrySize)
                        continue
                    }
                    val target = safeTarget(relative)
                    if (target == null) {
                        skipData(input, entrySize)
                        skipPadding(input, entrySize)
                        continue
                    }

                    when (type) {
                        '5' -> {
                            target.mkdirs()
                            chmod(target, mode)
                        }
                        '0', '\u0000', '7' -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                copyData(input, out, entrySize) { chunk ->
                                    total += chunk
                                    onProgress(total)
                                }
                            }
                            chmod(target, mode)
                        }
                        '2' -> {
                            target.parentFile?.mkdirs()
                            if (safeLinkTarget(target, entryLink)) {
                                runCatching {
                                    if (target.exists()) target.delete()
                                    Os.symlink(entryLink, target.absolutePath)
                                }
                            }
                        }
                        '1' -> {
                            val linkRelative = strip(entryLink)
                            val source = linkRelative?.let { safeTarget(it) }
                            if (source != null && source.exists()) {
                                target.parentFile?.mkdirs()
                                runCatching {
                                    if (target.exists()) target.delete()
                                    Os.link(source.absolutePath, target.absolutePath)
                                }.onFailure {
                                    // 应用数据目录禁止硬链接（EPERM）时退化为复制。
                                    runCatching { source.copyTo(target, overwrite = true) }
                                }
                            }
                        }
                        else -> skipData(input, entrySize)
                    }

                    if (type == '0' || type == '\u0000' || type == '7') {
                        skipPadding(input, entrySize)
                    } else {
                        skipData(input, entrySize)
                        skipPadding(input, entrySize)
                    }
                }
    }

    private fun strip(entryName: String): String? {
        val parts = entryName.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        val stripped = parts.drop(stripComponents)
        if (stripped.isEmpty()) return null
        return stripped.joinToString("/")
    }

    private fun safeTarget(relative: String): File? {
        val target = File(destCanonical, relative).canonicalFile
        val base = destCanonical.path
        if (target.path == base) return null
        if (!target.path.startsWith(base + File.separator)) return null
        return target
    }

    private fun safeLinkTarget(linkTarget: File, rawLink: String): Boolean {
        if (rawLink.isBlank() || rawLink.startsWith("/")) return false
        val resolved = File(linkTarget.parentFile, rawLink).canonicalFile
        val base = destCanonical.path
        return resolved.path == base || resolved.path.startsWith(base + File.separator)
    }

    private fun parsePax(data: ByteArray) {
        var position = 0
        while (position < data.size) {
            val space = indexOfSpace(data, position)
            if (space < 0) return
            val length = String(data, position, space - position, Charsets.US_ASCII)
                .toIntOrNull() ?: return
            if (length <= 0 || position + length > data.size) return
            val record = String(
                data,
                space + 1,
                position + length - space - 2,
                Charsets.UTF_8,
            )
            val eq = record.indexOf('=')
            if (eq > 0) {
                val key = record.substring(0, eq)
                val value = record.substring(eq + 1)
                when (key) {
                    "path" -> paxPath = value
                    "linkpath" -> paxLink = value
                    "size" -> value.toLongOrNull()?.let { paxSize = it }
                }
            }
            position += length
        }
    }

    private fun indexOfSpace(data: ByteArray, from: Int): Int {
        for (i in from until data.size) {
            if (data[i].toInt() == ' '.code) return i
        }
        return -1
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end].toInt() != 0) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun readOctal(header: ByteArray, offset: Int, length: Int): Int {
        val text = readString(header, offset, length).trim().trim('\u0000')
        if (text.isEmpty()) return 0
        return text.toIntOrNull(8) ?: 0
    }

    private fun readSize(header: ByteArray, offset: Int, length: Int): Long {
        val first = header[offset].toInt() and 0xff
        if (first and 0x80 != 0) {
            var value = (first and 0x7f).toLong()
            for (i in 1 until length) {
                value = (value shl 8) or (header[offset + i].toInt() and 0xff).toLong()
            }
            return value
        }
        val text = readString(header, offset, length).trim().trim('\u0000')
        if (text.isEmpty()) return 0
        return text.toLongOrNull(8) ?: 0
    }

    private fun readData(input: InputStream, size: Long): ByteArray {
        if (size <= 0 || size > 8 * 1024 * 1024) return ByteArray(0)
        val data = ByteArray(size.toInt())
        var offset = 0
        while (offset < data.size) {
            val read = input.read(data, offset, data.size - offset)
            if (read < 0) throw IOException("tar 数据不完整")
            offset += read
        }
        return data
    }

    private suspend fun copyData(
        input: InputStream,
        output: FileOutputStream,
        size: Long,
        onBytes: suspend (Long) -> Unit,
    ) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, want)
            if (read < 0) throw IOException("tar 数据不完整")
            output.write(buffer, 0, read)
            remaining -= read
            onBytes(read.toLong())
        }
    }

    private fun skipData(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, want)
            if (read < 0) throw IOException("tar 数据不完整")
            remaining -= read
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = ((512 - (size % 512)) % 512).toInt()
        if (padding == 0) return
        var remaining = padding
        val buffer = ByteArray(padding)
        while (remaining > 0) {
            val read = input.read(buffer, 0, remaining)
            if (read < 0) throw IOException("tar 填充不完整")
            remaining -= read
        }
    }

    private fun chmod(file: File, mode: Int) {
        val safeMode = if (mode == 0) 0b110_100_100 else mode and 0b111_111_111
        runCatching { Os.chmod(file.absolutePath, safeMode) }
    }
}