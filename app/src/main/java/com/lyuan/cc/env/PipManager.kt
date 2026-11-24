package com.lyuan.cc.env

import android.content.Context
import com.lyuan.cc.CcmOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PipPackage(val name: String, val version: String)

data class PipOutdated(val name: String, val version: String, val latestVersion: String)

data class PipState(
    val pythonInstalled: Boolean = false,
    /** 当前 Python 来自软件源（bionic），不是内置的 glibc Python。 */
    val pythonFromMirror: Boolean = false,
    val packages: List<PipPackage> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val stage: String = "",
    val message: String = "",
    val updateRunning: Boolean = false,
    val updateDone: Int = 0,
    val updateTotal: Int = 0,
    val updateSucceeded: Int = 0,
    val updateSkipped: Int = 0,
    val updateFailed: Int = 0,
    val reportPath: String = "",
)

data class PipFailure(
    val name: String,
    val version: String,
    val stage: String,
    val output: String,
)

/** pip 包列表与安装/更新。执行的是环境页内置的 Python，不依赖 Termux。 */
class PipManager(
    private val context: Context,
    private val env: EnvManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PipState(pythonInstalled = python.isFile))
    val state: StateFlow<PipState> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var process: Process? = null

    private val python: File
        get() {
            val direct = File(env.binDir, "python")
            return if (direct.isFile) direct else File(env.binDir, "python3")
        }

    fun refresh() {
        val current = _state.value
        if (current.busy) return
        _state.value = current.copy(busy = true, loading = true, stage = "读取 pip 列表…")
        job = scope.launch {
            try {
                val packages = loadPackages()
                val fromMirror = pythonIsFromMirror()
                _state.value = _state.value.copy(
                    pythonInstalled = python.isFile,
                    pythonFromMirror = fromMirror,
                    packages = packages,
                    loading = false,
                    busy = false,
                    stage = "",
                    message = if (!python.isFile) {
                        "未安装 Python。请先在上方包列表安装「Python」（含 pip）。"
                    } else {
                        ""
                    },
                )
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(busy = false, loading = false, stage = "")
                throw cancelled
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    pythonInstalled = python.isFile,
                    loading = false,
                    busy = false,
                    stage = "",
                    message = "读取 pip 列表失败：${t.message ?: t.javaClass.simpleName}",
                )
            } finally {
                job = null
            }
        }
    }

    fun install(input: String, workspace: String?) {
        if (_state.value.busy) return
        when (val command = PipCommandParser.parse(input)) {
            is PipCommand.Error -> {
                _state.value = _state.value.copy(message = command.message)
                return
            }
            is PipCommand.Install -> startInstall(command.args, workspace)
        }
    }

    fun startUpdate(workspace: String?) {
        if (_state.value.busy) return
        if (!python.isFile) {
            _state.value = _state.value.copy(
                message = "未安装 Python。请先在上方包列表安装「Python」（含 pip）。",
            )
            return
        }
        _state.value = _state.value.copy(
            busy = true,
            updateRunning = true,
            stage = "检查可更新包…",
            message = "",
            updateDone = 0,
            updateTotal = 0,
            updateSucceeded = 0,
            updateSkipped = 0,
            updateFailed = 0,
            reportPath = "",
        )
        job = scope.launch {
            var succeeded = 0
            var skipped = 0
            val failures = mutableListOf<PipFailure>()
            try {
                val installed = loadPackages()
                _state.value = _state.value.copy(
                    pythonInstalled = true,
                    packages = installed,
                )
                val check = runPip(
                    listOf("list", "--outdated", "--format=json",
                        "--disable-pip-version-check"),
                    env.homeDir,
                ) { line -> stage(line) }
                if (check.code != 0) {
                    error("检查可更新包失败：${check.output.takeLast(500)}")
                }
                val outdated = PipJson.parseOutdated(check.output)
                skipped = (installed.size - outdated.size).coerceAtLeast(0)
                _state.value = _state.value.copy(
                    updateTotal = outdated.size,
                    updateSkipped = skipped,
                )
                if (outdated.isEmpty()) {
                    _state.value = _state.value.copy(
                        busy = false,
                        updateRunning = false,
                        stage = "",
                        message = "所有 pip 包都是最新版本（共检查 ${installed.size} 个）。",
                    )
                    return@launch
                }
                outdated.forEachIndexed { index, pkg ->
                    _state.value = _state.value.copy(
                        updateDone = index,
                        stage = "更新 ${pkg.name}（${index + 1}/${outdated.size}）…",
                    )
                    val result = runPip(
                        listOf("install", "--upgrade", pkg.name),
                        env.homeDir,
                    ) { line -> stage(line) }
                    if (result.code == 0) {
                        succeeded++
                    } else {
                        failures += PipFailure(
                            name = pkg.name,
                            version = pkg.version,
                            stage = "pip install --upgrade",
                            output = result.output,
                        )
                    }
                    _state.value = _state.value.copy(
                        updateDone = index + 1,
                        updateSucceeded = succeeded,
                        updateFailed = failures.size,
                    )
                }
                val report = if (failures.isNotEmpty()) {
                    writeUpdateReport(workspace, outdated.size, skipped, succeeded, failures)
                } else {
                    ""
                }
                val refreshed = runCatching { loadPackages() }.getOrElse { installed }
                val fromMirror = pythonIsFromMirror()
                _state.value = _state.value.copy(
                    pythonInstalled = true,
                    pythonFromMirror = fromMirror,
                    packages = refreshed,
                    busy = false,
                    updateRunning = false,
                    stage = "",
                    updateDone = outdated.size,
                    updateSucceeded = succeeded,
                    updateSkipped = skipped,
                    updateFailed = failures.size,
                    reportPath = report,
                    message = buildString {
                        append("pip 更新完成：成功 ").append(succeeded)
                        if (skipped > 0) append("，已是最新 ").append(skipped)
                        if (failures.isNotEmpty()) append("，失败 ").append(failures.size)
                        if (report.isNotBlank()) append("。报告：").append(report)
                    },
                )
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(
                    busy = false,
                    updateRunning = false,
                    stage = "",
                    message = "pip 更新已停止（完成 ${_state.value.updateDone}/" +
                        "${_state.value.updateTotal}）",
                )
                throw cancelled
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    busy = false,
                    updateRunning = false,
                    stage = "",
                    message = "pip 更新失败：${t.message ?: t.javaClass.simpleName}",
                )
            } finally {
                job = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
        process?.destroyForcibly()
    }

    fun exportText(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("# CCM pip 包清单")
            appendLine("# 导出时间：$stamp")
            _state.value.packages.sortedBy { it.name.lowercase(Locale.US) }.forEach {
                appendLine("${it.name}==${it.version}")
            }
        }
    }

    private fun startInstall(args: List<String>, workspace: String?) {
        if (!python.isFile) {
            _state.value = _state.value.copy(
                message = "未安装 Python。请先在上方包列表安装「Python」（含 pip）。",
            )
            return
        }
        _state.value = _state.value.copy(
            busy = true,
            loading = false,
            stage = "准备安装…",
            message = "",
        )
        job = scope.launch {
            try {
                val result = runPip(args, installWorkDir(workspace)) { line -> stage(line) }
                val message = if (result.code == 0) {
                    result.output.lineSequence()
                        .lastOrNull { it.startsWith("Successfully installed") }
                        ?: "安装完成。"
                } else {
                    val report = writeInstallReport(workspace, args, result.code, result.output)
                    "pip 安装失败，报告：" + report.ifBlank { "无法写入工作区" }
                }
                // 刷新期间继续保持 busy，避免列表读取和下一次安装并发。
                val refreshed = runCatching { loadPackages() }
                    .getOrElse { _state.value.packages }
                val fromMirror = pythonIsFromMirror()
                _state.value = _state.value.copy(
                    pythonInstalled = true,
                    pythonFromMirror = fromMirror,
                    packages = refreshed,
                    busy = false,
                    stage = "",
                    message = message,
                )
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(
                    busy = false,
                    stage = "",
                    message = "pip 安装已停止",
                )
                throw cancelled
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    busy = false,
                    stage = "",
                    message = "pip 安装失败：${t.message ?: t.javaClass.simpleName}",
                )
            } finally {
                job = null
            }
        }
    }

    private fun stage(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        val short = if (text.length > 120) text.take(120) + "…" else text
        _state.update { it.copy(stage = short) }
    }

    private fun installWorkDir(workspace: String?): File {
        val dir = workspace?.let(::File)
        return if (dir != null && dir.isDirectory && dir.canRead()) dir else env.workDir
    }

    /** 判断当前 `python` 是软件源 bionic 包还是内置 glibc 包。 */
    private suspend fun pythonIsFromMirror(): Boolean {
        val record = env.installed.first().firstOrNull { it.id == "python" } ?: return false
        record.mirror?.let { return it }
        val catalog = env.packageById("python") ?: return true
        return record.version != catalog.version
    }

    private suspend fun loadPackages(): List<PipPackage> = withContext(Dispatchers.IO) {
        if (!python.isFile) return@withContext emptyList()
        val result = runPip(
            listOf("list", "--format=json", "--disable-pip-version-check"),
            env.homeDir,
        ) { line -> stage(line) }
        if (result.code != 0) {
            val message = result.output.takeLast(500)
            if (message.contains("No module named pip")) {
                throw IllegalStateException("Python 已安装，但没有 pip")
            }
            throw IllegalStateException(message.ifBlank { "exit=${result.code}" })
        }
        PipJson.parseList(result.output)
    }

    private data class ProcessResult(val code: Int, val output: String)

    private suspend fun runPip(
        args: List<String>,
        workDir: File,
        onLine: (String) -> Unit,
    ): ProcessResult = withContext(Dispatchers.IO) {
        val executable = python
        if (!executable.isFile) throw IllegalStateException("Python 未安装")
        // 所有调用方只传 pip 子命令（install/list/...）；这里统一补上 `-m pip`。
        val command = commandFor(executable.absolutePath, args)
        val builder = ProcessBuilder(command)
            .redirectErrorStream(true)
            .directory(workDir)
        val environment = builder.environment()
        environment.clear()
        environment.putAll(buildEnvironment())
        val started = builder.start()
        process = started
        val output = StringBuilder()
        try {
            started.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (output.length < MAX_OUTPUT) {
                        output.append(line).append('\n')
                    }
                    onLine(line)
                }
            }
            // pip 安装大包可能超过 10 分钟；不设固定超时，由用户点「停止」结束。
            started.waitFor()
            ProcessResult(started.exitValue(), output.toString())
        } finally {
            process = null
        }
    }

    /**
 * pip 在构建隔离/子进程里会直接用 `sys.executable` 再执行一次 Python。
 * targetSdk 36 下应用私有目录的真实二进制不能直接 execve，会报
 * `Permission denied`。这里用 sitecustomize 把子进程的 sys.executable 指回
 * `env/bin/python`（指向 nativeLibraryDir 启动器的符号链接），由启动器负责执行。
 */
private fun ensurePipBootstrap(): File {
        val dir = File(env.root, "pip-utils").apply { mkdirs() }
        val file = File(dir, "sitecustomize.py")
        if (!file.isFile || file.readText() != PIP_BOOTSTRAP) {
            runCatching { file.writeText(PIP_BOOTSTRAP) }
        }
        return dir
    }

    private fun buildEnvironment(): Map<String, String> = buildMap {
        put("HOME", env.homeDir.absolutePath)
        put("TMPDIR", env.tmpDir.absolutePath)
        put("PATH", env.binDir.absolutePath + ":/system/bin")
        put("TERM", "dumb")
        put("LANG", "C.UTF-8")
        put("TZ", "Asia/Shanghai")
        put("CCM_LD", env.loader.absolutePath)
        put("CCM_LIBDIR", env.libDir.absolutePath)
        put("CCM_ENV_DIR", env.root.absolutePath)
        put("PYTHONUNBUFFERED", "1")
        put("PYTHONPATH", ensurePipBootstrap().absolutePath)
        put("CCM_PYTHON_LAUNCHER", python.absolutePath)
        put("PIP_DISABLE_PIP_VERSION_CHECK", "1")
        put("PIP_NO_INPUT", "1")
        put("PIP_CACHE_DIR", File(env.root, "pip-cache").apply { mkdirs() }.absolutePath)
        if (env.certFile.isFile && env.certFile.length() > 1000) {
            put("SSL_CERT_FILE", env.certFile.absolutePath)
            put("CURL_CA_BUNDLE", env.certFile.absolutePath)
            put("PIP_CERT", env.certFile.absolutePath)
        }
    }

    private fun writeInstallReport(
        workspace: String?,
        args: List<String>,
        code: Int,
        output: String,
    ): String = runCatching {
        val file = reportFile(workspace, "ccm-pip-install-report")
        val command = PipCommandParser.display(args)
        file.writeText(
            buildString {
                appendLine("# CCM pip 安装报告")
                appendLine("时间：${timestamp()}")
                appendLine("命令：$command")
                appendLine("退出码：$code")
                appendLine()
                appendLine("## 输出")
                appendLine(redact(output.take(MAX_REPORT_OUTPUT)))
            }
        )
        file.absolutePath
    }.getOrDefault("")

    private fun writeUpdateReport(
        workspace: String?,
        total: Int,
        skipped: Int,
        succeeded: Int,
        failures: List<PipFailure>,
    ): String = runCatching {
        val file = reportFile(workspace, "ccm-pip-update-report")
        file.writeText(
            buildString {
                appendLine("# CCM pip 一键更新报告")
                appendLine("时间：${timestamp()}")
                appendLine(
                    "总数：$total  成功：$succeeded  已是最新：$skipped  " +
                        "失败：${failures.size}"
                )
                appendLine()
                appendLine("## 失败明细")
                failures.forEachIndexed { index, failure ->
                    appendLine("${index + 1}. ${failure.name} ${failure.version}")
                    appendLine("   阶段：${failure.stage}")
                    appendLine("   输出：")
                    appendLine(redact(failure.output.take(MAX_REPORT_PER_PACKAGE)))
                }
            }
        )
        file.absolutePath
    }.getOrDefault("")

    private fun reportFile(workspace: String?, prefix: String): File {
        val dir = workspace?.let(::File)
            ?.takeIf { it.isDirectory && it.canRead() }
            ?: CcmOutput.outputDir(context)
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "$prefix-$stamp.txt")
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        /** 统一生成 `python -m pip <args>`，避免调用方漏掉 `-m pip`。 */
        fun commandFor(pythonPath: String, pipArgs: List<String>): List<String> =
            listOf(pythonPath, "-m", "pip") + pipArgs

        /** sitecustomize：把 pip 子进程的 sys.executable 指回可执行的启动器路径。 */
        val PIP_BOOTSTRAP: String = """
            import os
            import sys

            _launcher = os.environ.get("CCM_PYTHON_LAUNCHER")
            if _launcher and os.path.isfile(_launcher):
                try:
                    sys.executable = _launcher
                except Exception:
                    pass
        """.trimIndent() + "\n"

        private const val MAX_OUTPUT = 512 * 1024
        private const val MAX_REPORT_OUTPUT = 256 * 1024
        private const val MAX_REPORT_PER_PACKAGE = 64 * 1024

        /** 报告里去掉 URL 中的凭据与明显的 token 字段。 */
        fun redact(text: String): String {
            var result = text
            result = result.replace(
                Regex("(https?://)[^/\\s@]+@"),
                "$1***@",
            )
            result = result.replace(
                Regex("(?i)(token|password|passwd|pwd)\\s*[=:]\\s*\\S+"),
                "$1=***",
            )
            return result
        }
    }
}

/** pip 输出的 JSON 解析，抽成纯对象便于单元测试。 */
object PipJson {

    fun parseList(text: String): List<PipPackage> {
        val array = parseArray(text) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val name = obj.optString("name").trim()
            if (name.isEmpty()) null else PipPackage(name, obj.optString("version"))
        }.sortedBy { it.name.lowercase(Locale.US) }
    }

    fun parseOutdated(text: String): List<PipOutdated> {
        val array = parseArray(text) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val name = obj.optString("name").trim()
            if (name.isEmpty()) {
                null
            } else {
                PipOutdated(
                    name = name,
                    version = obj.optString("version"),
                    latestVersion = obj.optString("latest_version"),
                )
            }
        }
    }

    private fun parseArray(text: String): JSONArray? {
        // pip 的警告/提示里也可能出现方括号，逐个尝试以某个 '[' 开头的 JSON 数组。
        val end = text.lastIndexOf(']')
        if (end <= 0) return null
        var start = text.indexOf('[')
        while (start in 0 until end) {
            val candidate = text.substring(start, end + 1)
            runCatching { JSONArray(candidate) }.getOrNull()?.let { return it }
            start = text.indexOf('[', start + 1)
        }
        return null
    }
}