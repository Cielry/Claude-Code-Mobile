package com.lyuan.cc.runtime

import android.content.Context
import android.content.res.AssetManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * 运行时管理：解压 glibc 子集、改写 libc 内的硬编码路径、校验完整性、启动 claude-native。
 *
 * 目录布局：
 *   files/rt/bin/claude-native
 *   files/rt/lib/  …           （glibc 子集，libc 前缀已改写为本应用路径）
 *   files/rt/etc/resolv.conf
 *   files/rt/manifest.json     （版本与文件 SHA-256）
 *
 * loader（ld-linux）随 APK 放在 nativeLibraryDir，安装时解压，无需运行时准备。
 */
class RuntimeManager(private val context: Context) {

    companion object {
        const val ASSET_VERSION = "glibc-2.44-1"
        /** 随 APK 内置的 claude-native（锁定 2.1.301）。 */
        const val NATIVE_SIZE = 267247608L
        const val NATIVE_SHA256 = "1e119036a802d359ba5f9886428e031586e8796f7a858d5a8ee179ac7b8604f0"
        private const val NATIVE_ASSET = "runtime/claude-native"
        /** 随包 Claude 配置的版本；变化时重新写入一次，之后保留用户修改。 */
        private const val CLAUDE_CONFIG_VERSION = "2026-11-06-3"
        private const val OLD_PREFIX = "/data/data/com.termux/files/usr/glibc"
        val LIB_NAMES = listOf(
            "libc.so.6", "libm.so.6", "libpthread.so.0", "libdl.so.2", "librt.so.1",
            "libresolv.so.2", "libnss_dns.so.2", "libnss_files.so.2", "libgcc_s.so.1",
        )
    }

    val root = File(context.filesDir, "rt")
    val libDir = File(root, "lib")
    val binDir = File(root, "bin")
    val etcDir = File(root, "etc")
    val homeDir = File(root, "home")
    val tmpDir = File(root, "tmp")
    val workDir = File(root, "work")
    val manifestFile = File(root, "manifest.json")

    val loader: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libld_linux.so")
    val binary: File
        get() = File(binDir, "claude-native")

    data class Check(val name: String, val ok: Boolean, val detail: String)

    data class Status(
        val initialized: Boolean,
        val ready: Boolean,
        val assetVersion: String?,
        val checks: List<Check>,
    )

    suspend fun initialize(onProgress: (String) -> Unit = {}): Status = withContext(Dispatchers.IO) {
        ensureDirs()
        val already = manifestFile.exists() && readManifestVersion() == ASSET_VERSION &&
            libcPatched()
        if (!already) {
            onProgress("解压运行时库")
            val am = context.assets
            for (name in am.list("runtime/lib").orEmpty()) {
                val dst = File(libDir, name)
                copyAsset(am, "runtime/lib/$name", dst, overwrite = true)
            }
            onProgress("改写 libc 路径")
            patchLibc()
            writeResolvConf()
            onProgress("写入清单")
            writeManifest()
        } else {
            writeResolvConf()
        }
        onProgress("导入 claude-native（内置）")
        importBundledNative(onProgress)
        onProgress("写入 Claude 配置")
        ensureClaudeConfig()
        quickStatus()
    }

    /** 从 APK assets 导入锁定的 claude-native；已存在且大小一致时跳过。 */
    private fun importBundledNative(onProgress: (String) -> Unit): Boolean {
        if (binary.isFile && binary.length() == NATIVE_SIZE) return true
        val tmp = File(binDir, "claude-native.part")
        tmp.delete()
        return try {
            val total = NATIVE_SIZE
            context.assets.open(NATIVE_ASSET).use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (done - last >= 16L * 1024 * 1024) {
                            last = done
                            onProgress("导入 claude-native ${done * 100 / total}%")
                        }
                    }
                }
            }
            if (tmp.length() != NATIVE_SIZE || sha256(tmp) != NATIVE_SHA256) {
                tmp.delete()
                false
            } else {
                binary.delete()
                val ok = tmp.renameTo(binary)
                if (ok) binary.setExecutable(true, false)
                ok
            }
        } catch (t: Throwable) {
            tmp.delete()
            false
        }
    }

    /** 写入随包分发的 Claude Code 配置；配置版本变化时覆盖一次，之后保留用户修改。 */
    fun ensureClaudeConfig() {
        val dir = File(homeDir, ".claude")
        val hooksDir = File(dir, "hooks")
        dir.mkdirs()
        hooksDir.mkdirs()
        val marker = File(dir, ".ccm-config-version")
        val overwrite = !marker.isFile || marker.readText().trim() != CLAUDE_CONFIG_VERSION
        fun copy(asset: String, dst: File) {
            if (!overwrite && dst.isFile && dst.length() > 0) return
            runCatching {
                context.assets.open(asset).use { input ->
                    FileOutputStream(dst).use { output -> input.copyTo(output) }
                }
            }
        }
        copy("claude/settings.json", File(dir, "settings.json"))
        // CLAUDE.md 默认为空，由用户在「配置 → 编辑 CLAUDE.md」里自行填写。
        if (overwrite) runCatching { File(dir, "CLAUDE.md").writeText("") }
        copy("claude/statusline.sh", File(dir, "statusline.sh"))
        copy("claude/hooks/archive-conversation.js", File(hooksDir, "archive-conversation.js"))
        File(dir, "statusline.sh").setExecutable(true, false)
        if (overwrite) runCatching { marker.writeText(CLAUDE_CONFIG_VERSION) }
    }

    fun quickStatus(): Status {
        val checks = mutableListOf<Check>()
        checks += Check("loader", loader.exists() && loader.length() > 0,
            if (loader.exists()) loader.absolutePath else "缺失")
        checks += Check("glibc 库", libDir.listFiles()?.count { it.name in LIB_NAMES } == LIB_NAMES.size,
            "${libDir.listFiles()?.count { it.name in LIB_NAMES } ?: 0}/${LIB_NAMES.size}")
        checks += Check("libc 路径", libcPatched(), if (libcPatched()) "已改写" else "未改写")
        checks += Check("claude-native", binary.exists() && binary.length() > 200_000_000L,
            if (binary.exists()) "${binary.length()} 字节" else "未导入")
        val initialized = checks.take(3).all { it.ok } && manifestFile.exists()
        return Status(
            initialized = initialized,
            ready = initialized && checks.last().ok,
            assetVersion = readManifestVersion(),
            checks = checks,
        )
    }

    fun verifyAll(): Status {
        val checks = mutableListOf<Check>()
        val manifest = readManifest()
        if (manifest == null) {
            return Status(false, false, null, listOf(Check("manifest", false, "缺失")))
        }
        var okCount = 0
        val files = manifest.optJSONObject("files")
        if (files != null) {
            for (name in files.keys()) {
                val entry = files.getJSONObject(name)
                val f = File(libDir, name)
                val expected = entry.optString("sha256")
                val actual = if (f.exists()) sha256(f) else ""
                val ok = f.exists() && actual == expected
                if (ok) okCount++
                checks += Check("lib/$name", ok, if (ok) "OK" else "校验失败")
            }
        }
        checks += Check(
            "libc 路径", libcPatched(),
            if (libcPatched()) "已改写" else "未改写",
        )
        val nativeOk = binary.exists() && binary.length() == NATIVE_SIZE &&
            runCatching { sha256(binary) == NATIVE_SHA256 }.getOrDefault(false)
        checks += Check(
            "claude-native", nativeOk,
            when {
                !binary.exists() -> "未导入"
                nativeOk -> "${binary.length()} 字节 · SHA-256 通过"
                else -> "${binary.length()} 字节 · 校验失败"
            },
        )
        return Status(
            initialized = checks.all { it.ok },
            ready = checks.all { it.ok },
            assetVersion = manifest.optString("assetVersion"),
            checks = checks,
        )
    }

    /** 从外部输入流导入 claude-native（SAF 或其他来源）。 */
    suspend fun importBinary(input: InputStream, onProgress: (Long) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            ensureDirs()
            val tmp = File(binDir, "claude-native.part")
            try {
                var total = 0L
                input.use { ins ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(262144)
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total % (8L * 1024 * 1024) < buf.size) onProgress(total)
                        }
                    }
                }
                if (total < 200_000_000L) {
                    tmp.delete()
                    return@withContext false
                }
                if (binary.exists()) binary.delete()
                val ok = tmp.renameTo(binary)
                binary.setExecutable(true, false)
                ok
            } catch (t: Throwable) {
                tmp.delete()
                false
            }
        }

    /** 执行 claude --version，用于验证链路。 */
    suspend fun runVersion(): String = withContext(Dispatchers.IO) {
        if (!binary.exists()) return@withContext "claude-native 未导入"
        writeResolvConf()
        val cmd = listOf(
            loader.absolutePath,
            "--library-path", libDir.absolutePath,
            "--argv0", "claude",
            binary.absolutePath,
            "--version",
        )
        try {
            val pb = ProcessBuilder(cmd)
            pb.directory(workDir)
            pb.redirectErrorStream(true)
            pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            pb.environment().clear()
            pb.environment().put("HOME", homeDir.absolutePath)
            pb.environment().put("TMPDIR", tmpDir.absolutePath)
            pb.environment().put("PATH", "/system/bin")
            pb.environment().put("TERM", "dumb")
            pb.environment().put("LANG", "C.UTF-8")
            pb.environment().put("LD_LIBRARY_PATH", libDir.absolutePath)
            pb.environment().put("ANTHROPIC_MODEL", "placeholder")
            val p = pb.start()
            val text = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            "exit=$code $text"
        } catch (t: Throwable) {
            "EXCEPTION ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun writeResolvConf() {
        ensureDirs()
        val servers = mutableListOf<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net: Network? = cm.activeNetwork
            val lp: LinkProperties? = net?.let { cm.getLinkProperties(it) }
            lp?.dnsServers?.forEach { addr ->
                addr.hostAddress?.takeIf { it.isNotEmpty() }?.let { servers += it }
            }
        } catch (_: Throwable) {
        }
        if (servers.none { !it.contains(':') }) servers += "223.5.5.5"
        val text = servers.joinToString("") { "nameserver $it\n" }
        File(etcDir, "resolv.conf").writeText(text)
    }

    private fun ensureDirs() {
        listOf(root, libDir, binDir, etcDir, homeDir, tmpDir, workDir).forEach { it.mkdirs() }
    }

    private fun copyAsset(am: AssetManager, path: String, dst: File, overwrite: Boolean) {
        if (!overwrite && dst.exists() && dst.length() > 0) return
        am.open(path).use { input ->
            FileOutputStream(dst).use { out ->
                input.copyTo(out, 65536)
            }
        }
    }

    private fun patchLibc(): Int {
        val libc = File(libDir, "libc.so.6")
        if (!libc.exists()) return 0
        val old = OLD_PREFIX.toByteArray(Charsets.UTF_8)
        val new = (context.filesDir.absolutePath + "/rt").toByteArray(Charsets.UTF_8)
        if (new.size > old.size) return -1
        val data = libc.readBytes()
        var count = 0
        val handled = HashSet<Int>()
        var i = 0
        while (i <= data.size - old.size) {
            if (matchesAt(data, old, i)) {
                var start = i
                while (start > 0 && data[start - 1] != 0.toByte()) start--
                if (handled.add(start)) {
                    var end = start
                    while (end < data.size && data[end] != 0.toByte()) end++
                    val original = data.copyOfRange(start, end)
                    val replaced = replaceAll(original, old, new)
                    if (replaced.size <= original.size) {
                        System.arraycopy(replaced, 0, data, start, replaced.size)
                        for (k in start + replaced.size until end) data[k] = 0
                        count++
                    }
                }
            }
            i++
        }
        libc.writeBytes(data)
        return count
    }

    private fun libcPatched(): Boolean {
        val libc = File(libDir, "libc.so.6")
        if (!libc.exists()) return false
        val needle = (context.filesDir.absolutePath + "/rt/etc/resolv.conf").toByteArray(Charsets.UTF_8)
        val data = libc.readBytes()
        var i = 0
        while (i <= data.size - needle.size) {
            if (matchesAt(data, needle, i)) return true
            i++
        }
        return false
    }

    private fun matchesAt(data: ByteArray, needle: ByteArray, at: Int): Boolean {
        if (at + needle.size > data.size) return false
        for (k in needle.indices) if (data[at + k] != needle[k]) return false
        return true
    }

    private fun replaceAll(src: ByteArray, old: ByteArray, new: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(src.size)
        var i = 0
        while (i < src.size) {
            if (i <= src.size - old.size && matchesAt(src, old, i)) {
                out.write(new)
                i += old.size
            } else {
                out.write(src[i].toInt())
                i++
            }
        }
        return out.toByteArray()
    }

    private fun writeManifest() {
        val files = JSONObject()
        for (name in LIB_NAMES) {
            val f = File(libDir, name)
            if (f.exists()) {
                files.put(name, JSONObject().put("size", f.length()).put("sha256", sha256(f)))
            }
        }
        val json = JSONObject()
            .put("assetVersion", ASSET_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("files", files)
        manifestFile.writeText(json.toString())
    }

    private fun readManifest(): JSONObject? = try {
        if (manifestFile.exists()) JSONObject(manifestFile.readText()) else null
    } catch (_: Throwable) {
        null
    }

    private fun readManifestVersion(): String? = readManifest()?.optString("assetVersion")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
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