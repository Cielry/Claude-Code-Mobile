package com.lyuan.cc.env

import android.content.Context
import android.system.Os
import com.lyuan.cc.CcmOutput
import com.lyuan.cc.runtime.RuntimeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ProbeResult(
    val ok: Boolean,
    val runtimeReady: Boolean,
    val message: String,
    val output: String,
    val timestamp: Long,
)

data class CheckResult(val ok: Boolean, val output: String)

data class CustomInstallResult(val id: String, val commands: Int)

/** 一键安装目标：内置目录包或软件源包。 */
data class BatchTarget(
    val id: String,
    val name: String,
    val version: String,
    val catalog: CatalogPackage?,
)

data class BatchFailure(
    val name: String,
    val version: String,
    val stage: String,
    val message: String,
)

data class BatchInstallState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val succeeded: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val current: String = "",
    val message: String = "",
    val reportPath: String = "",
    val failures: List<BatchFailure> = emptyList(),
)

private data class RegistryCommand(
    val name: String,
    val bin: String,
    val script: String?,
)

/**
 * 「环境」包管理器：管理应用私有目录中的命令行工具，并让它们通过 nativeLibraryDir
 * 的启动器被 Claude Code 的 Shell 直接调用。
 */
class EnvManager(private val context: Context) {

    private val runtime = RuntimeManager(context)
    private val repo = EnvRepository(context)

    val root = File(context.filesDir, "env")
    val binDir = File(root, "bin")
    val pkgsDir = File(root, "pkgs")
    val registryDir = File(root, "registry")
    val activeDir = File(root, "active")
    val baseDir = File(root, "libbase")
    val downloadsDir = File(root, "downloads")
    val aptCacheDir = File(root, "apt-cache")
    val certFile = File(root, "etc/cert.pem")

    private val launcher = File(context.applicationInfo.nativeLibraryDir, "libenv_exec.so")

    // 允许同时安装多个包：同一包的下载/解压/注册串行，不同包互不阻塞；
    // 并发安装共享依赖时，后到者拿到锁后会看到已安装记录并直接复用。
    private val packageLocks = ConcurrentHashMap<String, Mutex>()
    private val baseLibsMutex = Mutex()
    private val certMutex = Mutex()

    private fun lockFor(id: String): Mutex = packageLocks.computeIfAbsent(id) { Mutex() }

    val catalog: List<CatalogPackage> = EnvCatalog.load(context)
    /** 设备 Termux 包列表（data/termux-packages.txt 生成），按需从软件源安装。 */
    val termuxPackages: List<TermuxPackage> = EnvCatalog.loadTermuxPackages(context)
    val installed: Flow<List<InstalledPackage>> = repo.installed

    private val _probe = MutableStateFlow<ProbeResult?>(null)
    val probe: StateFlow<ProbeResult?> = _probe.asStateFlow()

    private val batchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var batchJob: Job? = null
    private val _batchState = MutableStateFlow(BatchInstallState())
    val batchState: StateFlow<BatchInstallState> = _batchState.asStateFlow()

    fun packageById(id: String): CatalogPackage? = catalog.firstOrNull { it.id == id }

    /**
     * 一键安装：按顺序安装给定包，已安装的由调用方过滤。失败不中断，
     * 结束后把失败明细写入自定义工作区。
     */
    fun startBatchInstall(
        targets: List<BatchTarget>,
        mirror: AptMirror,
        workspace: String,
        skipped: Int,
    ) {
        if (batchJob?.isActive == true || targets.isEmpty()) return
        val targetDir = workspace.trim()
        _batchState.value = BatchInstallState(
            running = true,
            total = targets.size,
            skipped = skipped,
            message = "准备一键安装…",
        )
        batchJob = batchScope.launch {
            var succeeded = 0
            var skippedCount = skipped
            val failures = mutableListOf<BatchFailure>()
            try {
                for ((index, target) in targets.withIndex()) {
                    _batchState.value = _batchState.value.copy(
                        done = index,
                        current = target.name,
                        message = "正在安装 ${target.name}（${index + 1}/${targets.size}）",
                    )
                    // 前面的包可能已经把它作为依赖装好，直接跳过，不算失败。
                    if (repo.find(target.id) != null) {
                        skippedCount++
                        _batchState.value = _batchState.value.copy(
                            done = index + 1,
                            skipped = skippedCount,
                        )
                        continue
                    }
                    var stage = ""
                    val catalogPkg = target.catalog
                    val result = runCatching {
                        if (catalogPkg != null) {
                            install(
                                catalogPkg,
                                onProgress = { _, _ -> },
                                onStage = { stage = it },
                            ).getOrThrow()
                        } else {
                            installCustom(
                                target.id,
                                mirror,
                                onProgress = { _, _ -> },
                                onStage = { stage = it },
                            ).getOrThrow()
                        }
                    }
                    if (result.isSuccess) {
                        succeeded++
                    } else {
                        val error = result.exceptionOrNull()
                        if (error is CancellationException) throw error
                        failures += BatchFailure(
                            name = target.name,
                            version = target.version,
                            stage = stage.ifBlank { "安装" },
                            message = error?.message
                                ?: error?.javaClass?.simpleName
                                ?: "未知错误",
                        )
                    }
                    _batchState.value = _batchState.value.copy(
                        done = index + 1,
                        succeeded = succeeded,
                        failed = failures.size,
                    )
                }
                val report = if (failures.isNotEmpty()) {
                    writeBatchReport(
                        targetDir,
                        mirror,
                        targets.size + skipped,
                        skippedCount,
                        succeeded,
                        failures,
                    )
                } else {
                    ""
                }
                _batchState.value = BatchInstallState(
                    running = false,
                    total = targets.size,
                    done = targets.size,
                    succeeded = succeeded,
                    skipped = skippedCount,
                    failed = failures.size,
                    message = buildString {
                        append("一键安装完成：成功 ").append(succeeded)
                        if (skippedCount > 0) append("，跳过 ").append(skippedCount)
                        if (failures.isNotEmpty()) append("，失败 ").append(failures.size)
                        if (report.isNotBlank()) append("。错误报告：").append(report)
                    },
                    reportPath = report,
                    failures = failures,
                )
            } catch (cancelled: CancellationException) {
                _batchState.value = _batchState.value.copy(
                    running = false,
                    current = "",
                    message = "一键安装已停止（完成 ${_batchState.value.done}/${targets.size}）",
                )
                throw cancelled
            } finally {
                batchJob = null
            }
        }
    }

    fun cancelBatchInstall() {
        batchJob?.cancel()
    }

    private fun writeBatchReport(
        workspace: String,
        mirror: AptMirror,
        total: Int,
        skipped: Int,
        succeeded: Int,
        failures: List<BatchFailure>,
    ): String = runCatching {
        val dir = File(workspace)
        if (!dir.isDirectory) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "ccm-install-report-$stamp.txt")
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("# CCM 环境一键安装报告")
            appendLine("时间：$now")
            appendLine("工作区：${dir.absolutePath}")
            appendLine("镜像：${mirror.label} ${mirror.baseUrl}")
            appendLine(
                "总数：$total  已安装跳过：$skipped  成功：$succeeded  " +
                    "失败：${failures.size}"
            )
            appendLine()
            appendLine("## 失败明细")
            failures.forEachIndexed { index, failure ->
                appendLine("${index + 1}. ${failure.name} ${failure.version}")
                appendLine("   阶段：${failure.stage}")
                appendLine("   错误：${failure.message}")
            }
        }
        file.writeText(text)
        file.absolutePath
    }.getOrDefault("")

    fun ensureDirs() {
        listOf(
            root, binDir, pkgsDir, registryDir, activeDir, baseDir, downloadsDir, aptCacheDir,
        ).forEach {
            it.mkdirs()
        }
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        ensureDirs()
        runCatching { extractBaseLibs() }
        // APK 升级会改变 nativeLibraryDir，旧的命令链接需要重新指向当前启动器。
        runCatching { repairCommandLinks() }
        // targetSdk 36 起，应用私有目录中的静态 ELF 无法直接执行；已下线的包自动清理。
        // bzip2 / make 现在可从软件源列表安装（CH-0046），不能再按旧内置包清理。
        runCatching {
            listOf("go", "sqlite3").forEach { id ->
                if (repo.find(id) != null) uninstall(id)
            }
        }
        // 旧安装的 git/curl 补上 CA 证书（新安装时也会复制）。
        runCatching { ensureCaAssets() }
        // 旧安装的 git 也需要补上子命令桥接（新安装时同样会执行）。
        runCatching { ensureGitPackage() }
        runProbe()
    }

    private fun repairCommandLinks() {
        registryDir.listFiles()?.forEach { conf ->
            if (!conf.name.endsWith(".conf")) return@forEach
            val name = conf.name.removeSuffix(".conf")
            createSymlink(launcher, File(binDir, name))
        }
    }

    // ---------- 基础库 ----------

    private suspend fun extractBaseLibs() {
        baseLibsMutex.withLock {
            val marker = File(baseDir, ".version")
            if (marker.isFile && marker.readText() == BASE_LIBS_VERSION) return@withLock
            baseDir.mkdirs()
            val assets = context.assets.list("env/libbase").orEmpty()
            assets.forEach { name ->
                context.assets.open("env/libbase/$name").use { input ->
                    FileOutputStream(File(baseDir, name)).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            marker.writeText(BASE_LIBS_VERSION)
        }
    }

    // ---------- 执行能力探针 ----------

    suspend fun runProbe(): ProbeResult = withContext(Dispatchers.IO) {
        ensureDirs()
        val result = try {
            val ld = runtime.loader
            val native = runtime.binary
            if (!ld.isFile || !native.isFile || native.length() < 200_000_000L) {
                ProbeResult(
                    ok = false,
                    runtimeReady = false,
                    message = "运行时尚未就绪，正在自动准备",
                    output = "",
                    timestamp = System.currentTimeMillis(),
                )
            } else {
                val link = File(binDir, "ccmprobe")
                createSymlink(
                    File(context.applicationInfo.nativeLibraryDir, "libclaude_exec.so"),
                    link,
                )
                val process = ProcessBuilder(link.absolutePath, "--version")
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment()["HOME"] = runtime.homeDir.absolutePath
                        environment()["TMPDIR"] = runtime.tmpDir.absolutePath
                        environment()["PATH"] = "/system/bin"
                        environment()["TERM"] = "xterm-256color"
                        environment()["LANG"] = "C.UTF-8"
                        environment()["LD_LIBRARY_PATH"] = runtime.libDir.absolutePath
                        environment()["CCM_LD"] = ld.absolutePath
                        environment()["CCM_LIBDIR"] = runtime.libDir.absolutePath
                        environment()["CCM_NATIVE"] = native.absolutePath
                    }
                    .start()
                val finished = process.waitFor(20, TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                val exit = if (finished) process.exitValue() else -1
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val ok = finished && exit == 0 &&
                    Regex("\\d+\\.\\d+").containsMatchIn(output)
                ProbeResult(
                    ok = ok,
                    runtimeReady = true,
                    message = if (ok) {
                        "可以执行应用私有目录中的程序"
                    } else {
                        "执行受限：${output.ifBlank { "exit=$exit" }}"
                    },
                    output = output,
                    timestamp = System.currentTimeMillis(),
                )
            }
        } catch (t: Throwable) {
            ProbeResult(
                ok = false,
                runtimeReady = runtime.quickStatus().ready,
                message = t.message ?: t.javaClass.simpleName,
                output = "",
                timestamp = System.currentTimeMillis(),
            )
        }
        _probe.value = result
        writeProbeFile(result)
        result
    }

    private fun writeProbeFile(result: ProbeResult) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            append("time=").append(time).append('\n')
            append("ok=").append(result.ok).append('\n')
            append("runtimeReady=").append(result.runtimeReady).append('\n')
            append("message=").append(result.message).append('\n')
            append("output=").append(result.output).append('\n')
        }
        runCatching { File(root, "probe.txt").writeText(text) }
        CcmOutput.write(context, "env-probe.txt", text)
    }

    // ---------- git 子命令桥接 ----------

    /**
     * git 会从 GIT_EXEC_PATH 直接 exec 子命令（git-remote-https 等）。targetSdk 36 起
     * 应用私有目录不能作为 execve 目标，因此把这些入口换成指向 nativeLibraryDir
     * 启动器的符号链接，再由启动器经 linker64 运行真实文件。
     */
    private fun prepareGitHelpers(versionDir: File): List<RegistryCommand> {
        val core = File(versionDir, "libexec/git-core")
        if (!core.isDirectory) return emptyList()
        val real = File(versionDir, "libexec/git-core.real").apply { mkdirs() }
        val names = listOf(
            "git-remote-http", "git-remote-https", "git-remote-ftp", "git-remote-ftps",
            "git-upload-pack", "git-receive-pack", "git-upload-archive",
            "git-http-backend", "git-credential-store", "git-credential-cache",
            "git-credential-cache--daemon",
        )
        val result = mutableListOf<RegistryCommand>()
        for (name in names) {
            val entry = File(core, name)
            val resolved = runCatching { entry.canonicalFile }.getOrNull() ?: continue
            if (!resolved.isFile) continue
            val moved = File(real, name)
            val already = runCatching { Os.readlink(entry.absolutePath) == launcher.absolutePath }
                .getOrDefault(false)
            if (already) {
                if (moved.exists()) {
                    result += RegistryCommand(name, "libexec/git-core.real/$name", null)
                }
                continue
            }
            if (!isElf(resolved)) continue
            moved.delete()
            if (!entry.renameTo(moved)) {
                runCatching { resolved.copyTo(moved, overwrite = true) }
            }
            if (!moved.exists()) continue
            createSymlink(launcher, entry)
            result += RegistryCommand(name, "libexec/git-core.real/$name", null)
        }
        return result
    }

    private suspend fun ensureCaAssets() {
        listOf("git", "curl").forEach { id ->
            val record = repo.find(id) ?: return@forEach
            val dir = File(pkgsDir, "$id/${record.version}")
            if (dir.isDirectory) copyCaAsset(dir)
        }
    }

    private fun copyCaAsset(versionDir: File) {
        val dst = File(versionDir, "etc/cert.pem")
        if (dst.isFile && dst.length() > 1000) return
        dst.parentFile?.mkdirs()
        runCatching {
            context.assets.open("env/certs/cert.pem").use { input ->
                FileOutputStream(dst).use { output -> input.copyTo(output) }
            }
        }
    }

    private suspend fun ensureGitPackage() {
        val pkg = packageById("git") ?: return
        val record = repo.find("git") ?: return
        val versionDir = File(pkgsDir, "git/${record.version}")
        if (!versionDir.isDirectory) return
        val helpers = prepareGitHelpers(versionDir)
        if (helpers.isEmpty()) return
        val commands = resolveCommands(pkg, versionDir) + helpers
        writeRegistry(pkg, "pkgs/git/${record.version}", commands)
        val names = commands.map { it.name }
        if (record.commands.toSet() != names.toSet()) {
            repo.upsert(record.copy(commands = names, updatedAt = System.currentTimeMillis()))
        }
    }

    // ---------- 安装 / 卸载 ----------

    suspend fun install(
        pkg: CatalogPackage,
        onProgress: suspend (done: Long, total: Long) -> Unit,
        onStage: suspend (String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        lockFor(pkg.id).withLock {
            // 并发安装/共享依赖时，后到者直接复用已完成的安装。
            if (repo.find(pkg.id) != null) return@withLock Result.success(Unit)
            runCatching {
                ensureDirs()
                extractBaseLibs()

            val usable = root.usableSpace
            val need = pkg.size * 3
            if (usable > 0 && usable < need) {
                error("磁盘空间不足：需要约 ${need / 1048576} MB，当前可用 ${usable / 1048576} MB")
            }

            val archive = File(downloadsDir, "${pkg.id}-${pkg.version}.tar.gz")
            if (pkg.bundled != null) {
                archive.delete()
                onStage("释放内置包…")
                context.assets.open(pkg.bundled).use { input ->
                    FileOutputStream(archive).use { output -> input.copyTo(output) }
                }
            } else {
                if (!archive.isFile || archive.length() != pkg.size ||
                    !sha256(archive).equals(pkg.sha256, ignoreCase = true)
                ) {
                    archive.delete()
                    onStage("下载中…")
                    EnvDownloader.download(pkg.urls, archive, pkg.size, onProgress).getOrThrow()
                }

                onStage("校验中…")
                if (!sha256(archive).equals(pkg.sha256, ignoreCase = true)) {
                    archive.delete()
                    error("SHA-256 校验失败")
                }
            }

            onStage("解压中…")
            val pkgRoot = File(pkgsDir, pkg.id).apply { mkdirs() }
            val staging = File(pkgRoot, "${pkg.version}.part")
            staging.deleteRecursively()
            staging.mkdirs()
            TarGzExtractor(staging, pkg.stripComponents).extract(archive) { }

            onStage("注册命令…")
            val commands = resolveCommands(pkg, staging)
            if (commands.isEmpty()) error("安装包中没有找到可执行命令")

            val finalDir = File(pkgRoot, pkg.version)
            finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) error("安装目录重命名失败")
            if (pkg.id == "git" || pkg.id == "curl") copyCaAsset(finalDir)

            val relBase = "pkgs/${pkg.id}/${pkg.version}"
            File(activeDir, pkg.id).writeText(pkg.version)
            writeRegistry(pkg, relBase, commands)

            val now = System.currentTimeMillis()
            repo.upsert(
                InstalledPackage(
                    id = pkg.id,
                    version = pkg.version,
                    commands = commands.map { it.name },
                    status = "installed",
                    output = "",
                    installedAt = now,
                    updatedAt = now,
                )
            )
            // git 安装后立即建立子命令桥接，保证 clone/push 可用。
            runCatching { ensureGitPackage() }

            archive.delete()

            onStage("自检中…")
            val check = checkCommand(commands.first().name, pkg.checkArgs)
            repo.upsert(
                InstalledPackage(
                    id = pkg.id,
                    version = pkg.version,
                    commands = commands.map { it.name },
                    status = if (check.ok) "ok" else "failed",
                    output = check.output,
                    installedAt = now,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            if (!check.ok) error("已安装，但自检失败：${check.output}")
            }
        }
    }

    // ---------- 软件源安装（Termux 镜像） ----------

    private val packageNameRegex = Regex("[A-Za-z0-9][A-Za-z0-9+._~-]*")

    /** catalog id 与 Termux 包名不一致的内置包。 */
    private val catalogAliases = mapOf("xz-utils" to "xz")

    suspend fun installCustom(
        input: String,
        mirror: AptMirror,
        onProgress: suspend (Long, Long) -> Unit,
        onStage: suspend (String) -> Unit,
    ): Result<CustomInstallResult> = withContext(Dispatchers.IO) {
        runCatching {
            ensureDirs()
            extractBaseLibs()
            val name = normalizePackageName(input) ?: error("请输入要安装的包名")
            if (!packageNameRegex.matches(name)) error("包名不合法：$name")
            if (packageById(name) != null) {
                error("「$name」是内置目录中的包，请从上方列表安装")
            }
            if (repo.find(name) != null) error("「$name」已安装")
            catalogAliases[name]?.let { alias ->
                if (repo.find(alias) != null) {
                    error("对应的内置包「$alias」已安装，无需重复安装")
                }
            }

            onStage("读取软件源…")
            var index = AptRepo.loadIndex(mirror, aptCacheDir)
            val target = index.get(name) ?: run {
                // 缓存可能过期，强制刷新一次再找。
                index = AptRepo.loadIndex(mirror, aptCacheDir, force = true)
                index.get(name) ?: error("软件源中找不到「$name」")
            }

            // 依赖闭包：内置目录里已有的包优先（免下载），其余从镜像安装。
            val catalogDeps = LinkedHashSet<CatalogPackage>()
            val mirrorDeps = mutableListOf<AptPackage>()
            val visited = LinkedHashSet<String>()
            val resolving = mutableSetOf<String>()

            suspend fun visit(dep: String) {
                if (dep == name || !visited.add(dep)) return
                if (repo.find(dep) != null) return
                val catalogPkg = bundledCatalogPackage(dep)
                if (catalogPkg != null) {
                    if (repo.find(catalogPkg.id) == null) catalogDeps += catalogPkg
                    return
                }
                if (!resolving.add(dep)) return
                val entry = index.get(dep) ?: error("缺少依赖：$dep")
                entry.depends.forEach { visit(it) }
                resolving.remove(dep)
                mirrorDeps += entry
            }
            // Termux 的 bionic 程序普遍依赖 libandroid-support；部分包未在索引里声明。
            if (name != "libandroid-support" && index.get("libandroid-support") != null) {
                visit("libandroid-support")
            }
            target.depends.forEach { visit(it) }

            for (pkg in catalogDeps) {
                onStage("安装依赖 ${pkg.name}（内置）…")
                install(pkg, onProgress = { _, _ -> }, onStage = { }).getOrThrow()
            }

            for (dep in mirrorDeps) {
                onStage("安装依赖 ${dep.name}…")
                installMirrorPackage(
                    dep,
                    mirror,
                    dependencyLibs = libDirsOf(visited),
                    automatic = true,
                    onProgress = { _, _ -> },
                    onStage = onStage,
                )
            }

            onStage("安装 $name…")
            val record = installMirrorPackage(
                target,
                mirror,
                dependencyLibs = libDirsOf(visited),
                automatic = false,
                onProgress = onProgress,
                onStage = onStage,
            )

            onStage("自检中…")
            val check = record.commands.firstOrNull()
                ?.let { checkCommandLoose(it) }
                ?: CheckResult(true, "库/数据包，无命令")
            repo.upsert(
                record.copy(
                    status = if (check.ok) "ok" else "failed",
                    output = check.output,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            if (!check.ok) {
                uninstall(name)
                error("已安装，但无法运行：${check.output}")
            }
            CustomInstallResult(name, record.commands.size)
        }
    }

    /** 支持直接输入包名，或粘贴 `pkg install xxx` / `apt install xxx`。 */
    private fun normalizePackageName(input: String): String? {
        val ignored = setOf("pkg", "apt", "apt-get", "install", "sudo", "reinstall")
        return input.trim()
            .split(Regex("\\s+"))
            .map { it.trim('"', '\'', '`') }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("-") }
            .filterNot { it in ignored }
            .lastOrNull()
            ?.takeIf { it.isNotEmpty() && it.length <= 128 }
    }

    private fun bundledCatalogPackage(termuxName: String): CatalogPackage? {
        val id = catalogAliases[termuxName] ?: termuxName
        return packageById(id)?.takeIf { it.bundled != null }
    }

    /** 已安装依赖的库目录（用于拼 LD_LIBRARY_PATH）。 */
    private suspend fun libDirsOf(names: Collection<String>): List<File> {
        val dirs = LinkedHashSet<File>()
        names.forEach { name ->
            val record = repo.find(name)
                ?: repo.find(catalogAliases[name] ?: return@forEach)
                ?: return@forEach
            listOf("lib", "lib64").forEach { sub ->
                val dir = File(pkgsDir, "${record.id}/${safeVersion(record.version)}/$sub")
                if (dir.isDirectory) dirs += dir
            }
        }
        return dirs.toList()
    }

    private fun safeVersion(version: String): String =
        version.replace(Regex("[^A-Za-z0-9._+-]"), "_").ifBlank { "unknown" }

    /** 扫描解压后的 bin/，注册所有能解析到 ELF 的条目（含符号链接别名）。 */
    private fun scanBinCommands(versionDir: File): List<String> {
        val bin = File(versionDir, "bin")
        return bin.listFiles().orEmpty()
            .filter { it.isFile }
            .mapNotNull { entry ->
                val real = runCatching { entry.canonicalFile }.getOrNull()
                    ?: return@mapNotNull null
                if (real.isFile && isElf(real)) entry.name else null
            }
            .sorted()
    }

    private suspend fun installMirrorPackage(
        pkg: AptPackage,
        mirror: AptMirror,
        dependencyLibs: List<File>,
        automatic: Boolean,
        onProgress: suspend (Long, Long) -> Unit,
        onStage: suspend (String) -> Unit,
    ): InstalledPackage = lockFor(pkg.name).withLock {
        // 并发安装共享依赖时，后到者直接复用已安装的包。
        repo.find(pkg.name) ?: installMirrorPackageLocked(
            pkg, mirror, dependencyLibs, automatic, onProgress, onStage,
        )
    }

    private suspend fun installMirrorPackageLocked(
        pkg: AptPackage,
        mirror: AptMirror,
        dependencyLibs: List<File>,
        automatic: Boolean,
        onProgress: suspend (Long, Long) -> Unit,
        onStage: suspend (String) -> Unit,
    ): InstalledPackage {
        val version = safeVersion(pkg.version)
        val usable = root.usableSpace
        if (pkg.size > 0 && usable in 1 until pkg.size * 3) {
            error("磁盘空间不足：${pkg.name} 需要约 ${pkg.size / 1048576} MB 以上")
        }
        val deb = File(downloadsDir, "${pkg.name}_$version.deb")
        if (!deb.isFile || deb.length() != pkg.size ||
            !sha256(deb).equals(pkg.sha256, ignoreCase = true)
        ) {
            deb.delete()
            onStage("下载 ${pkg.name}…")
            EnvDownloader.download(listOf(mirror.debUrl(pkg.filename)), deb, pkg.size, onProgress)
                .getOrThrow()
            if (!sha256(deb).equals(pkg.sha256, ignoreCase = true)) {
                deb.delete()
                error("${pkg.name} SHA-256 校验失败")
            }
        }

        onStage("解压 ${pkg.name}…")
        val pkgRoot = File(pkgsDir, pkg.name).apply { mkdirs() }
        val staging = File(pkgRoot, "$version.part")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            AptRepo.openDataTar(deb).use { input ->
                // Termux deb 路径为 ./data/data/com.termux/files/usr/bin/...；
                // 去掉 data/data/com.termux/files/usr 五段后，bin/lib/share 位于包根目录。
                TarGzExtractor(staging, 5).extractTar(input) { }
            }
        } catch (t: Throwable) {
            staging.deleteRecursively()
            deb.delete()
            throw t
        }

        // 库/数据/脚本包可能没有可注册的 ELF 命令；仍然保留文件供其它包链接使用。
        val commands = scanBinCommands(staging)

        val finalDir = File(pkgRoot, version)
        finalDir.deleteRecursively()
        if (!staging.renameTo(finalDir)) {
            staging.deleteRecursively()
            error("安装目录重命名失败")
        }

        val relBase = "pkgs/${pkg.name}/$version"
        val libDirs = LinkedHashSet<File>()
        listOf(File(finalDir, "lib"), File(finalDir, "lib64")).forEach {
            if (it.isDirectory) libDirs += it
        }
        dependencyLibs.forEach { if (it.isDirectory) libDirs += it }
        // 应用自带兼容库（zlib / OpenSSL / libstdc++ 等）。
        if (baseDir.isDirectory) libDirs += baseDir
        // Termux 部分包没有在依赖元数据里声明全部共享库；统一把已安装包的
        // lib/lib64 加入搜索路径，避免 libandroid-support / readline 等找不到。
        runCatching {
            repo.installed.first().forEach { record ->
                val pkgDir = File(pkgsDir, "${record.id}/${safeVersion(record.version)}")
                listOf("lib", "lib64").forEach { sub ->
                    val dir = File(pkgDir, sub)
                    if (dir.isDirectory) libDirs += dir
                }
            }
        }
        val libs = libDirs.joinToString(":") {
            root.toPath().relativize(it.toPath()).toString()
        }

        val env = mutableMapOf<String, String>()
        if (libDirs.isNotEmpty()) {
            env["LD_LIBRARY_PATH"] = libDirs.joinToString(":") { it.absolutePath }
        }
        if (File(finalDir, "share/terminfo").isDirectory) {
            env["TERMINFO"] = "$root/$relBase/share/terminfo"
        }
        ensureCertFile()
        if (certFile.length() > 1000) {
            env["SSL_CERT_FILE"] = certFile.absolutePath
            env["CURL_CA_BUNDLE"] = certFile.absolutePath
            env["GIT_SSL_CAINFO"] = certFile.absolutePath
        }

        val registry = commands.map { RegistryCommand(it, "bin/$it", null) }
        writeRegistryEntries(pkg.name, pkg.version, relBase, libs, env, registry)
        File(activeDir, pkg.name).writeText(version)

        val now = System.currentTimeMillis()
        val record = InstalledPackage(
            id = pkg.name,
            version = pkg.version,
            commands = commands,
            status = "installed",
            output = "",
            installedAt = now,
            updatedAt = now,
            automatic = automatic,
        )
        repo.upsert(record)
        deb.delete()
        return record
    }

    private suspend fun ensureCertFile() {
        certMutex.withLock {
            if (certFile.isFile && certFile.length() > 1000) return@withLock
            certFile.parentFile?.mkdirs()
            runCatching {
                context.assets.open("env/certs/cert.pem").use { input ->
                    FileOutputStream(certFile).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /** 自定义包自检：退出码非 0 但不像加载失败时，视为命令可用（只是不接受 --version）。 */
    private fun checkCommandLoose(name: String): CheckResult {
        val result = checkCommand(name, listOf("--version"))
        if (result.ok) return result
        val text = result.output.lowercase(Locale.US)
        val loaderFailure = listOf(
            "error while loading shared libraries",
            "cannot link executable",
            "cannot locate symbol",
            "dlopen failed",
            "ccm-env: exec",
            "exec format error",
            "permission denied",
        ).any { text.contains(it) }
        return if (loaderFailure) result else CheckResult(true, result.output)
    }

    suspend fun uninstall(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        lockFor(id).withLock {
            runCatching {
                val record = repo.find(id)
            record?.commands?.forEach { command ->
                val link = File(binDir, command)
                if (isOurSymlink(link)) link.delete()
                File(registryDir, "$command.conf").delete()
            }
            // 兼容旧记录：扫描 registry 中属于该包的配置
            registryDir.listFiles()?.forEach { conf ->
                val text = runCatching { conf.readText() }.getOrDefault("")
                if (text.contains("PKG=$id\n") || text.endsWith("PKG=$id")) {
                    val name = conf.name.removeSuffix(".conf")
                    val link = File(binDir, name)
                    if (isOurSymlink(link)) link.delete()
                    conf.delete()
                }
            }
            File(activeDir, id).delete()
            File(pkgsDir, id).deleteRecursively()
            repo.remove(id)
            }
        }
    }

    suspend fun recheck(id: String): CheckResult = withContext(Dispatchers.IO) {
        runCatching { repairCommandLinks() }
        val pkg = packageById(id)
        val record = repo.find(id)
        if (record == null) {
            return@withContext CheckResult(false, "未安装")
        }
        val command = record.commands.firstOrNull()
        if (command == null) {
            val dir = File(pkgsDir, "${record.id}/${safeVersion(record.version)}")
            val result = CheckResult(
                dir.isDirectory,
                if (dir.isDirectory) "库/数据包，无命令" else "安装目录缺失",
            )
            repo.upsert(
                record.copy(
                    status = if (result.ok) "ok" else "failed",
                    output = result.output,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return@withContext result
        }
        val result = if (pkg == null) {
            checkCommandLoose(command)
        } else {
            checkCommand(command, pkg.checkArgs)
        }
        repo.upsert(
            record.copy(
                status = if (result.ok) "ok" else "failed",
                output = result.output,
                updatedAt = System.currentTimeMillis(),
            )
        )
        result
    }

    suspend fun recheckAll(): Map<String, CheckResult> = withContext(Dispatchers.IO) {
        val records = installed.first()
        records.associate { record -> record.id to recheck(record.id) }
    }

    // ---------- 命令注册与自检 ----------

    private fun resolveCommands(pkg: CatalogPackage, versionDir: File): List<RegistryCommand> {
        val binRoot = File(versionDir, pkg.binDir)
        val base = versionDir.canonicalFile.toPath()

        // BIN 保留条目本身的文件名：多调用二进制（coreutils/gzip/xz 等）靠
        // argv[0] 的 basename 分发，符号链接别名若被规范化成真实路径就散不了命令。
        // 因此只规范化父目录（防穿越），文件名原样保留。
        fun relative(entry: File): String {
            val parent = runCatching { entry.parentFile?.canonicalFile }
                .getOrNull() ?: entry.parentFile ?: return entry.path
            return base.relativize(parent.toPath()).resolve(entry.name).toString()
        }

        val entries = pkg.commands.mapNotNull { name ->
            val entry = File(binRoot, name)
            if (!entry.isFile) return@mapNotNull null
            val real = runCatching { entry.canonicalFile }.getOrNull() ?: return@mapNotNull null
            if (!real.isFile) return@mapNotNull null
            name to entry
        }
        // 脚本类命令（npm/pip 等）统一用包内第一个 ELF 命令作为解释器。
        val primary = entries.firstOrNull { (_, entry) -> isElf(entry) }?.second

        return entries.mapNotNull { (name, entry) ->
            if (isElf(entry)) {
                RegistryCommand(name = name, bin = relative(entry), script = null)
            } else {
                val interpreter = primary ?: return@mapNotNull null
                RegistryCommand(
                    name = name,
                    bin = relative(interpreter),
                    script = relative(entry.canonicalFile),
                )
            }
        }
    }

    private fun writeRegistry(
        pkg: CatalogPackage,
        relBase: String,
        commands: List<RegistryCommand>,
    ) {
        val libs = pkg.libs.map { "$relBase/$it" }.joinToString(":")
        writeRegistryEntries(pkg.id, pkg.version, relBase, libs, pkg.env, commands)
    }

    private fun writeRegistryEntries(
        id: String,
        version: String,
        relBase: String,
        libs: String,
        env: Map<String, String>,
        commands: List<RegistryCommand>,
    ) {
        commands.forEach { command ->
            val text = buildString {
                append("PKG=").append(id).append('\n')
                append("VERSION=").append(version).append('\n')
                append("BIN=").append(relBase).append('/').append(command.bin).append('\n')
                if (command.script != null) {
                    append("SCRIPT=").append(relBase).append('/')
                        .append(command.script).append('\n')
                }
                if (libs.isNotBlank()) append("LIBS=").append(libs).append('\n')
                env.forEach { (key, value) ->
                    val resolved = value
                        .replace("\$ROOT", root.absolutePath)
                        .replace("\$PKG", "$root/$relBase")
                    append("ENV_").append(key).append('=').append(resolved).append('\n')
                }
            }
            File(registryDir, "${command.name}.conf").writeText(text)
            createSymlink(launcher, File(binDir, command.name))
        }
    }

    private fun checkCommand(name: String, args: List<String>): CheckResult {
        val link = File(binDir, name)
        if (!isOurSymlink(link)) return CheckResult(false, "命令链接不存在")
        return try {
            val process = ProcessBuilder(listOf(link.absolutePath) + args)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment()["HOME"] = runtime.homeDir.absolutePath
                    environment()["TMPDIR"] = runtime.tmpDir.absolutePath
                    environment()["PATH"] = "${binDir.absolutePath}:/system/bin"
                    environment()["TERM"] = "xterm-256color"
                    environment()["LANG"] = "C.UTF-8"
                    environment()["TZ"] = "Asia/Shanghai"
                    environment()["CCM_LD"] = runtime.loader.absolutePath
                    environment()["CCM_LIBDIR"] = runtime.libDir.absolutePath
                    environment()["CCM_ENV_DIR"] = root.absolutePath
                }
                .start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                CheckResult(false, "自检超时")
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                CheckResult(process.exitValue() == 0, output.ifBlank { "exit=${process.exitValue()}" })
            }
        } catch (t: Throwable) {
            CheckResult(false, t.message ?: t.javaClass.simpleName)
        }
    }

    // ---------- 工具方法 ----------

    private fun createSymlink(target: File, link: File) {
        link.parentFile?.mkdirs()
        if (runCatching { Os.lstat(link.absolutePath) }.isSuccess) link.delete()
        Os.symlink(target.absolutePath, link.absolutePath)
    }

    private fun isOurSymlink(link: File): Boolean {
        return runCatching {
            Os.readlink(link.absolutePath) == launcher.absolutePath
        }.getOrDefault(false)
    }

    private fun isElf(file: File): Boolean {
        return runCatching {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                if (input.read(magic) != 4) return@use false
                magic[0].toInt() == 0x7f &&
                    magic[1].toInt() == 'E'.code &&
                    magic[2].toInt() == 'L'.code &&
                    magic[3].toInt() == 'F'.code
            }
        }.getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BASE_LIBS_VERSION = "1"
    }
}