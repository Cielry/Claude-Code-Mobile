package com.lyuan.cc.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyuan.cc.env.AptMirror
import com.lyuan.cc.env.CatalogPackage
import com.lyuan.cc.env.EnvManager
import com.lyuan.cc.env.InstalledPackage
import com.lyuan.cc.env.TermuxPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class InstallTask(
    val stage: String,
    val progress: Pair<Long, Long>? = null,
)

private fun taskLabel(task: InstallTask): String {
    val percent = task.progress?.let { (done, total) ->
        if (total > 0) (done * 100 / total).toInt() else null
    }
    return if (percent != null) "${task.stage} $percent%" else task.stage
}

private data class PackageEntry(
    val id: String,
    val name: String,
    val version: String,
    val source: String,
    val size: Long,
    val catalog: CatalogPackage?,
    val termux: TermuxPackage?,
)

private data class UninstallTarget(val id: String, val name: String)

/**
 * 把内置目录、Termux 设备包列表与已安装记录合并成一份去重清单。
 * 加入顺序：内置/下载包 → 设备包 → 仅已安装的自定义/依赖包。
 */
private fun buildPackageEntries(
    catalog: List<CatalogPackage>,
    termux: List<TermuxPackage>,
    installed: List<InstalledPackage>,
): List<PackageEntry> {
    val entries = LinkedHashMap<String, PackageEntry>()
    catalog.forEach { pkg ->
        entries[pkg.id] = PackageEntry(
            id = pkg.id,
            name = pkg.name,
            version = pkg.version,
            source = if (pkg.bundled != null) "内置" else "下载",
            size = pkg.size,
            catalog = pkg,
            termux = null,
        )
    }
    termux.forEach { pkg ->
        if (!entries.containsKey(pkg.name)) {
            entries[pkg.name] = PackageEntry(
                id = pkg.name,
                name = pkg.name,
                version = pkg.version,
                source = "软件源",
                size = 0L,
                catalog = null,
                termux = pkg,
            )
        }
    }
    installed.forEach { record ->
        if (!entries.containsKey(record.id)) {
            entries[record.id] = PackageEntry(
                id = record.id,
                name = record.id,
                version = record.version,
                source = if (record.automatic) "依赖" else "自定义",
                size = 0L,
                catalog = null,
                termux = null,
            )
        }
    }
    return entries.values.sortedBy { it.name.lowercase(Locale.US) }
}

@Composable
fun EnvironmentScreen(
    manager: EnvManager,
    installed: List<InstalledPackage>,
) {
    val scope = rememberCoroutineScope()
    val probe by manager.probe.collectAsState()
    val context = LocalContext.current

    var detecting by remember { mutableStateOf(false) }
    var packagesExpanded by rememberSaveable { mutableStateOf(false) }
    var packageQuery by rememberSaveable { mutableStateOf("") }
    var uninstallTarget by remember { mutableStateOf<UninstallTarget?>(null) }

    var pendingExport by remember { mutableStateOf("") }
    var customName by rememberSaveable { mutableStateOf("") }
    var mirrorIndex by rememberSaveable { mutableIntStateOf(0) }
    var mirrorMenu by remember { mutableStateOf(false) }

    // 并发安装：按任务键（包 id 或输入内容）记录阶段与进度，任务之间互不阻塞。
    var tasks by remember { mutableStateOf<Map<String, InstallTask>>(emptyMap()) }
    var customKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var messages by remember { mutableStateOf<List<String>>(emptyList()) }

    fun addMessage(text: String) {
        messages = (messages + text).takeLast(6)
    }

    fun setStage(key: String, text: String) {
        val current = tasks[key]
        tasks = tasks + (key to InstallTask(text, current?.progress))
    }

    fun setProgress(key: String, value: Pair<Long, Long>?) {
        val current = tasks[key] ?: InstallTask("", null)
        tasks = tasks + (key to current.copy(progress = value))
    }

    fun removeTask(key: String) {
        tasks = tasks - key
        customKeys = customKeys - key
    }

    // 软件源安装动作：输入框与包列表共用；同一输入不会重复启动。
    fun startCustomInstall(input: String) {
        val key = input.trim()
        if (key.isEmpty() || key in tasks) return
        val mirror = AptMirror.ALL[mirrorIndex]
        customKeys = customKeys + key
        tasks = tasks + (key to InstallTask("准备中…", null))
        scope.launch {
            val result = manager.installCustom(
                input,
                mirror,
                onProgress = { done, total ->
                    withContext(Dispatchers.Main) { setProgress(key, done to total) }
                },
                onStage = { text ->
                    withContext(Dispatchers.Main) { setStage(key, text) }
                },
            )
            removeTask(key)
            result.fold(
                onSuccess = {
                    addMessage("${it.id} 已安装（${it.commands} 个命令）。新开或重启对话后可用。")
                },
                onFailure = { addMessage("安装失败：${it.message}") },
            )
        }
    }

    fun startCatalogInstall(pkg: CatalogPackage) {
        if (pkg.id in tasks) return
        tasks = tasks + (pkg.id to InstallTask("准备中…", 0L to pkg.size))
        scope.launch {
            val result = manager.install(
                pkg,
                onProgress = { done, total ->
                    withContext(Dispatchers.Main) {
                        setProgress(pkg.id, done to total)
                    }
                },
                onStage = { text ->
                    withContext(Dispatchers.Main) { setStage(pkg.id, text) }
                },
            )
            removeTask(pkg.id)
            addMessage(
                result.fold(
                    onSuccess = {
                        "${pkg.name} 已安装。新开或重启对话后，Claude Shell 即可调用。"
                    },
                    onFailure = { "安装失败：${it.message}" },
                )
            )
        }
    }

    fun recheckPackage(id: String, name: String) {
        if (id in tasks) return
        tasks = tasks + (id to InstallTask("自检中…", null))
        scope.launch {
            val result = manager.recheck(id)
            removeTask(id)
            addMessage(
                if (result.ok) {
                    "$name 自检通过。"
                } else {
                    "$name 自检失败：${result.output}"
                }
            )
        }
    }

    fun uninstallPackage(id: String, name: String) {
        if (id in tasks) return
        tasks = tasks + (id to InstallTask("卸载中…", null))
        scope.launch {
            val result = manager.uninstall(id)
            removeTask(id)
            addMessage(
                result.fold(
                    onSuccess = { "$name 已卸载。" },
                    onFailure = { "卸载失败：${it.message}" },
                )
            )
        }
    }

    // 已安装包清单：一行一个（id + 版本 + 来源）。
    fun exportText(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("# CCM 环境包清单")
            appendLine("# 导出时间：$stamp")
            installed.sortedBy { it.id }.forEach { record ->
                val source = when {
                    record.automatic -> "依赖"
                    manager.packageById(record.id)?.bundled != null -> "内置"
                    manager.packageById(record.id) != null -> "下载"
                    else -> "自定义"
                }
                appendLine("${record.id} ${record.version} $source")
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(pendingExport.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选位置")
            }
            addMessage(
                result.fold(
                    onSuccess = { "已导出 ${installed.size} 个包。" },
                    onFailure = { "导出失败：${it.message}" },
                )
            )
        }
    }

    val entries = remember(manager.catalog, manager.termuxPackages, installed) {
        buildPackageEntries(manager.catalog, manager.termuxPackages, installed)
    }
    val installedById = remember(installed) { installed.associateBy { it.id } }
    val filtered = remember(entries, packageQuery) {
        val query = packageQuery.trim()
        if (query.isEmpty()) {
            entries
        } else {
            entries.filter {
                it.id.contains(query, ignoreCase = true) ||
                    it.name.contains(query, ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Text("环境", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        // ---------- 执行能力 ----------
        item(key = "probe") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("执行能力", style = MaterialTheme.typography.labelLarge)
                    val result = probe
                    when {
                        result == null -> Text(
                            "检测中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        result.ok -> Text(
                            "✓ 可执行：安装的工具可被 Claude Shell 调用",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50),
                        )
                        !result.runtimeReady -> Text(
                            "运行时未就绪：${result.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Text(
                            "✗ 受限：${result.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedButton(
                        enabled = !detecting,
                        onClick = {
                            scope.launch {
                                detecting = true
                                manager.runProbe()
                                manager.recheckAll()
                                detecting = false
                            }
                        },
                    ) { Text(if (detecting) "检测中…" else "环境检测") }
                }
            }
        }

        // ---------- 软件源安装 ----------
        item(key = "source") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("从软件源安装", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = customName,
                            onValueChange = { customName = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                            placeholder = { Text("包名，或 pkg install 包名") },
                        )
                        Box {
                            OutlinedButton(
                                onClick = { mirrorMenu = true },
                            ) { Text(AptMirror.ALL[mirrorIndex].label + " ▾") }
                            DropdownMenu(
                                expanded = mirrorMenu,
                                onDismissRequest = { mirrorMenu = false },
                            ) {
                                AptMirror.ALL.forEachIndexed { index, mirror ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                mirror.label + "  " +
                                                    mirror.baseUrl.substringAfter("://")
                                                        .substringBefore('/')
                                            )
                                        },
                                        onClick = {
                                            mirrorIndex = index
                                            mirrorMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        enabled = customName.isNotBlank() &&
                            customName.trim() !in tasks,
                        onClick = { startCustomInstall(customName) },
                    ) { Text("下载并安装") }
                    val activeCustom = customKeys.mapNotNull { key ->
                        tasks[key]?.let { key to it }
                    }
                    if (activeCustom.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            activeCustom.forEach { (key, task) ->
                                Text(
                                    text = key + "：" + taskLabel(task),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------- 消息 ----------
        items(messages) { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------- 统一包列表 ----------
        item(key = "packages-header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { packagesExpanded = !packagesExpanded }) {
                    Text(
                        text = (if (packagesExpanded) "▾ " else "▸ ") +
                            "包（${entries.size}）",
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已安装 ${installed.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        enabled = installed.isNotEmpty(),
                        onClick = {
                            pendingExport = exportText()
                            val name = "ccm-packages-" +
                                SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                                    .format(Date()) + ".txt"
                            exportLauncher.launch(name)
                        },
                    ) { Text("导出") }
                }
            }
        }

        if (packagesExpanded) {
            item(key = "search") {
                OutlinedTextField(
                    value = packageQuery,
                    onValueChange = { packageQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索包名") },
                )
            }
            if (filtered.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "没有匹配的包。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(filtered, key = { it.id }) { entry ->
                val record = installedById[entry.id]
                val task = tasks[entry.id]
                PackageRow(
                    entry = entry,
                    record = record,
                    busy = task != null,
                    task = task,
                    onInstall = {
                        val catalog = entry.catalog
                        if (catalog != null) {
                            startCatalogInstall(catalog)
                        } else {
                            startCustomInstall(entry.id)
                        }
                    },
                    onUninstall = {
                        uninstallTarget = UninstallTarget(entry.id, entry.name)
                    },
                    onCheck = {
                        recheckPackage(entry.id, entry.name)
                    },
                )
            }
        }

        item(key = "footer") {
            Text(
                "安装位置：应用私有目录；PATH 已接入 Claude 会话。安装/卸载后需要新开或重启对话。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    uninstallTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            title = { Text("卸载 ${target.name}") },
            text = {
                Text("将删除 ${target.name} 及其命令入口；依赖它的其他命令可能失效。")
            },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    onClick = {
                        uninstallTarget = null
                        uninstallPackage(target.id, target.name)
                    },
                ) { Text("卸载") }
            },
            dismissButton = {
                TextButton(onClick = { uninstallTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PackageRow(
    entry: PackageEntry,
    record: InstalledPackage?,
    busy: Boolean,
    task: InstallTask?,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onCheck: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = buildString {
                        append('v').append(record?.version ?: entry.version)
                        append(" · ").append(entry.source)
                        if (entry.size > 0) {
                            append(" · ")
                            append("%.1f".format(entry.size / 1048576.0))
                            append(" MB")
                        }
                        if (record?.automatic == true) append(" · 依赖")
                        if (record != null) append(" · 已安装")
                        when (record?.status) {
                            "ok" -> append(" · ✓")
                            "failed" -> append(" · 自检失败")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy && task != null) {
                    Text(
                        taskLabel(task),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!busy) {
                if (record != null) {
                    Row {
                        TextButton(onClick = onCheck) { Text("自检") }
                        TextButton(
                            onClick = onUninstall,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("卸载") }
                    }
                } else {
                    Button(onClick = onInstall) { Text("安装") }
                }
            }
        }
        HorizontalDivider()
    }
}