package com.lyuan.cc.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lyuan.cc.data.Permissions
import com.lyuan.cc.data.ProviderEntity
import com.lyuan.cc.data.ProviderRepository
import com.lyuan.cc.data.SettingsRepository
import com.lyuan.cc.data.Workspace
import com.lyuan.cc.data.WorkspaceUtils
import com.lyuan.cc.runtime.RuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ConfigScreen(
    settings: SettingsRepository,
    providerRepo: ProviderRepository,
    runtime: RuntimeManager,
    providers: List<ProviderEntity>,
    workspaces: List<Workspace>,
    selectedWorkspaceId: String,
    defaultProviderId: String,
    defaultModelId: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }

    var showAddProvider by remember { mutableStateOf(false) }
    var expandedProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteProviderTarget by remember { mutableStateOf<ProviderEntity?>(null) }
    var workspaceExpanded by remember { mutableStateOf(false) }
    var workspaceToDelete by remember { mutableStateOf<Workspace?>(null) }
    var allFilesGranted by remember { mutableStateOf(Permissions.hasAllFilesAccess(context)) }
    var editingClaudeMd by remember { mutableStateOf(false) }
    var claudeMdText by remember { mutableStateOf("") }
    var pendingApiExport by remember { mutableStateOf("") }

    // 从系统设置返回后刷新「所有文件访问」状态。
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesGranted = Permissions.hasAllFilesAccess(context)
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(providers, editingProviderId) {
        if (editingProviderId != null && providers.none { it.id == editingProviderId }) {
            editingProviderId = null
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val path = WorkspaceUtils.pathFromTreeUri(uri)
            if (path.isNullOrBlank()) {
                Toast.makeText(
                    context,
                    "无法解析文件夹路径，请先授予“所有文件访问”权限",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                val name = File(path).name.ifBlank { "工作区" }
                scope.launch {
                    settings.addWorkspace(name, uri.toString(), path)
                    workspaceExpanded = true
                    allFilesGranted = Permissions.hasAllFilesAccess(context)
                }
            }
        }
    }

    val apiExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(pendingApiExport.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选位置")
            }
            Toast.makeText(
                context,
                result.fold(
                    onSuccess = { "API 配置已导出（含明文 Key）" },
                    onFailure = { "导出失败：${it.message}" },
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    suspend fun buildApiExport(): String {
        val array = JSONArray()
        providers.forEach { provider ->
            array.put(
                JSONObject()
                    .put("id", provider.id)
                    .put("name", provider.name)
                    .put("baseUrl", provider.baseUrl.replace("\\/", "/"))
                    .put("apiKey", providerRepo.tokenOf(provider))
                    .put("smallModelId", provider.smallModelId.orEmpty())
                    .put("models", JSONArray(providerRepo.modelsOf(provider)))
            )
        }
        val defaultProviderName = providers
            .firstOrNull { it.id == defaultProviderId }
            ?.name
            .orEmpty()
        return JSONObject()
            .put("format", "ccm-api-config")
            .put("version", 1)
            .put(
                "exportedAt",
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            )
            .put("defaultProviderId", defaultProviderId)
            .put("defaultProviderName", defaultProviderName)
            .put("defaultModelId", defaultModelId)
            .put("providers", array)
            .toString(2)
            // org.json 会把 "/" 转义成 "\/"，导出文件里恢复成正常 URL。
            .replace("\\/", "/")
    }

    suspend fun importApiConfig(text: String): String {
        val root = JSONObject(text)
        val array = root.optJSONArray("providers") ?: error("文件格式不正确：缺少 providers")
        val keys = providerRepo.observeProviders().first()
            .map { it.name to it.baseUrl.replace("\\/", "/") }
            .toMutableSet()
        var added = 0
        var skipped = 0
        val failures = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val name = obj.optString("name").trim()
            val baseUrl = obj.optString("baseUrl").trim().replace("\\/", "/")
            if (name.isBlank() || baseUrl.isBlank() || !keys.add(name to baseUrl)) {
                skipped++
                continue
            }
            runCatching {
                val models = obj.optJSONArray("models")?.let { list ->
                    (0 until list.length()).mapNotNull { i ->
                        list.optString(i).takeIf(String::isNotBlank)
                    }
                }.orEmpty()
                val id = providerRepo.addProvider(
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = obj.optString("apiKey"),
                    firstModelId = models.firstOrNull().orEmpty(),
                    smallModelId = obj.optString("smallModelId")
                        .takeIf { it.isNotBlank() },
                )
                models.drop(1).forEach { providerRepo.addModel(id, it) }
                added++
            }.onFailure { failures += "$name：${it.message}" }
        }
        val defaultName = root.optString("defaultProviderName").trim()
        if (defaultName.isNotBlank()) {
            val provider = providerRepo.observeProviders().first()
                .firstOrNull { it.name == defaultName }
            if (provider != null) {
                settings.setDefaultProvider(provider.id)
                val model = root.optString("defaultModelId").trim()
                if (model.isNotBlank() && providerRepo.modelsOf(provider).contains(model)) {
                    settings.setDefaultModel(model)
                }
            }
        }
        return buildString {
            append("导入完成：新增 ").append(added).append("，跳过 ").append(skipped)
            if (failures.isNotEmpty()) {
                append("，失败 ").append(failures.size)
                append('\n').append(failures.joinToString("\n"))
            }
        }
    }

    val apiImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?: error("无法读取所选文件")
                    }
                    importApiConfig(text)
                }
                Toast.makeText(
                    context,
                    result.getOrElse { "导入失败：${it.message}" },
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val selectedWorkspace = workspaces.firstOrNull { it.id == selectedWorkspaceId }
    val modelsByProvider = remember(providers) {
        providers.associate { it.id to providerRepo.modelsOf(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("配置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

        // ---------- 工作区 ----------
        Text("工作区", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedWorkspaceId.isBlank()) {
                Button(onClick = { scope.launch { settings.selectWorkspace("") } }) {
                    Text("默认")
                }
            } else {
                OutlinedButton(onClick = { scope.launch { settings.selectWorkspace("") } }) {
                    Text("默认")
                }
            }
            if (workspaceExpanded) {
                Button(onClick = { workspaceExpanded = false }) { Text("自定义 ▴") }
            } else {
                OutlinedButton(onClick = { workspaceExpanded = true }) { Text("自定义 ▾") }
            }
        }
        Text(
            text = selectedWorkspace?.let { "${it.name}：${it.path}" }
                ?: ("默认工作区：" + WorkspaceUtils.defaultDir(context).absolutePath),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (workspaceExpanded) {
            Text(
                "点选切换，长按删除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            workspaces.forEach { workspace ->
                val active = workspace.id == selectedWorkspaceId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                scope.launch { settings.selectWorkspace(workspace.id) }
                            },
                            onLongClick = { workspaceToDelete = workspace },
                        )
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = (if (active) "• " else "") + workspace.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        )
                        Text(
                            text = workspace.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (active) {
                        Text(
                            "使用中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { treeLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("添加工作区") }
        }

        if (selectedWorkspace != null && !WorkspaceUtils.isUsable(selectedWorkspace.path)) {
            Text(
                "该路径当前不可直接访问，需要授予“所有文件访问”权限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!allFilesGranted) {
            OutlinedButton(onClick = {
                runCatching {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    } else {
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        )
                    }
                    context.startActivity(intent)
                }
            }) { Text("授予“所有文件访问”权限") }
            Text(
                "Claude Code 需要真实路径作为工作目录；自定义文件夹通常需要此权限才能直接读写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        // ---------- Claude 配置 ----------
        Text("Claude 配置", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(
            "对应 ~/.claude/CLAUDE.md，对全部对话生效；保存后需新开或重启对话。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = {
            claudeMdText = runCatching {
                File(runtime.homeDir, ".claude/CLAUDE.md").readText()
            }.getOrDefault("")
            editingClaudeMd = true
        }) { Text("编辑 CLAUDE.md") }

        HorizontalDivider()

        // ---------- 服务商 ----------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("服务商", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Button(onClick = { showAddProvider = true }) { Text("添加服务商") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { apiImportLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                Text("导入")
            }
            OutlinedButton(
                enabled = providers.isNotEmpty(),
                onClick = {
                    scope.launch {
                        pendingApiExport = buildApiExport()
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                            .format(Date())
                        apiExportLauncher.launch("ccm-api-config-$stamp.json")
                    }
                },
            ) { Text("导出") }
        }
        Text(
            "导出 JSON 包含明文 API Key；导入会新增服务商，同名同地址的配置会跳过。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (providers.isEmpty()) {
            Text(
                "还没有服务商。添加后可以为每家配置多个模型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        providers.forEach { provider ->
            val expanded = provider.id == expandedProviderId
            val isDefaultProvider = provider.id == defaultProviderId
            val models = modelsByProvider[provider.id].orEmpty()
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedProviderId = if (expanded) null else provider.id
                            }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = provider.name,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (expanded) "▾" else "▸",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (expanded) {
                        HorizontalDivider()
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (models.isEmpty()) {
                                Text(
                                    "还没有模型，点“编辑”添加。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            models.forEach { model ->
                                Text(
                                    text = model +
                                        if (isDefaultProvider && model == defaultModelId) {
                                            "  · 默认"
                                        } else {
                                            ""
                                        },
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = { editingProviderId = provider.id }) {
                                    Text("编辑")
                                }
                                TextButton(
                                    onClick = { deleteProviderTarget = provider },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddProvider) {
        AddProviderDialog(
            onFetch = { baseUrl, apiKey -> providerRepo.fetchModelsFor(baseUrl, apiKey) },
            onDismiss = { showAddProvider = false },
            onAdd = { name, url, key, model, small ->
                scope.launch {
                    val id = providerRepo.addProvider(name, url, key, model, small)
                    if (defaultProviderId.isBlank()) {
                        settings.setDefaultProvider(id)
                        settings.setDefaultModel(model)
                    }
                    showAddProvider = false
                }
            },
        )
    }

    deleteProviderTarget?.let { provider ->
        AlertDialog(
            onDismissRequest = { deleteProviderTarget = null },
            title = { Text("删除服务商") },
            text = {
                Text("删除「${provider.name}」及其全部模型配置？API Key 也会一并清除，此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    onClick = {
                        deleteProviderTarget = null
                        scope.launch {
                            providerRepo.deleteProvider(provider.id)
                            if (provider.id == defaultProviderId) {
                                settings.setDefaultProvider(null)
                                settings.setDefaultModel(null)
                            }
                            if (expandedProviderId == provider.id) expandedProviderId = null
                            if (editingProviderId == provider.id) editingProviderId = null
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteProviderTarget = null }) { Text("取消") }
            },
        )
    }

    val editingProvider = providers.firstOrNull { it.id == editingProviderId }
    if (editingProvider != null) {
        EditProviderDialog(
            provider = editingProvider,
            initialModels = providerRepo.modelsOf(editingProvider),
            isDefaultProvider = editingProvider.id == defaultProviderId,
            defaultModelId = defaultModelId,
            onFetch = { baseUrl, apiKey ->
                providerRepo.fetchModelsFor(
                    baseUrl,
                    apiKey.ifBlank { providerRepo.tokenOf(editingProvider) },
                )
            },
            onDismiss = { editingProviderId = null },
            onSave = { name, baseUrl, apiKey, smallModelId, models, newDefaultModel ->
                scope.launch {
                    val ok = providerRepo.updateProvider(
                        editingProvider.id,
                        name,
                        baseUrl,
                        apiKey,
                        smallModelId,
                        models,
                    )
                    if (!ok) {
                        Toast.makeText(context, "服务商已不存在", Toast.LENGTH_SHORT).show()
                        editingProviderId = null
                        return@launch
                    }
                    val effective = newDefaultModel?.takeIf { it in models }
                    if (effective != null) {
                        settings.setDefaultProvider(editingProvider.id)
                        settings.setDefaultModel(effective)
                    } else if (editingProvider.id == defaultProviderId) {
                        val fallback = defaultModelId.takeIf { it in models }
                            ?: models.firstOrNull()
                        if (fallback != null) {
                            settings.setDefaultProvider(editingProvider.id)
                            settings.setDefaultModel(fallback)
                        } else {
                            settings.setDefaultProvider(null)
                            settings.setDefaultModel(null)
                        }
                    }
                    editingProviderId = null
                }
            },
        )
    }

    workspaceToDelete?.let { workspace ->
        AlertDialog(
            onDismissRequest = { workspaceToDelete = null },
            title = { Text("删除工作区") },
            text = { Text("删除「${workspace.name}」？只会移除这条记录，不会删除手机上的文件夹。") },
            confirmButton = {
                TextButton(onClick = {
                    workspaceToDelete = null
                    scope.launch { settings.removeWorkspace(workspace.id) }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { workspaceToDelete = null }) { Text("取消") }
            },
        )
    }

    if (editingClaudeMd) {
        AlertDialog(
            onDismissRequest = { editingClaudeMd = false },
            title = { Text("编辑 CLAUDE.md") },
            text = {
                OutlinedTextField(
                    value = claudeMdText,
                    onValueChange = { claudeMdText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 420.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    singleLine = false,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val file = File(runtime.homeDir, ".claude/CLAUDE.md")
                    runCatching {
                        file.parentFile?.mkdirs()
                        file.writeText(claudeMdText)
                    }
                    editingClaudeMd = false
                    Toast.makeText(
                        context,
                        "CLAUDE.md 已保存，新开或重启对话后生效",
                        Toast.LENGTH_LONG,
                    ).show()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingClaudeMd = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EditProviderDialog(
    provider: ProviderEntity,
    initialModels: List<String>,
    isDefaultProvider: Boolean,
    defaultModelId: String,
    onFetch: suspend (baseUrl: String, apiKey: String) -> Result<List<String>>,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        baseUrl: String,
        apiKey: String,
        smallModelId: String,
        models: List<String>,
        newDefaultModel: String?,
    ) -> Unit,
) {
    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var apiKey by remember(provider.id) { mutableStateOf("") }
    var smallModelId by remember(provider.id) {
        mutableStateOf(provider.smallModelId.orEmpty())
    }
    val models = remember(provider.id) {
        mutableStateListOf<String>().apply { addAll(initialModels) }
    }
    var selectedDefault by remember(provider.id) {
        mutableStateOf(
            defaultModelId.takeIf { isDefaultProvider && it in initialModels }
        )
    }
    var showAddModel by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name.ifBlank { "编辑服务商" },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showAddModel = true }) { Text("添加") }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("Base URL") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("留空则沿用已保存的 Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = smallModelId, onValueChange = { smallModelId = it },
                    label = { Text("Small Model（可选）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    "模型（点击设为默认）",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (models.isEmpty()) {
                    Text(
                        "还没有模型，点右上角“添加”。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                models.forEach { model ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = model +
                                if (model == selectedDefault) "  · 默认" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (model == selectedDefault) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedDefault = model }
                                .padding(vertical = 10.dp),
                        )
                        TextButton(
                            onClick = {
                                models.remove(model)
                                if (selectedDefault == model) selectedDefault = null
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("移除") }
                    }
                    HorizontalDivider()
                }
                Text(
                    "保存后，使用该服务商的进行中对话会重新启动。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && baseUrl.isNotBlank(),
                onClick = {
                    onSave(
                        name.trim(),
                        baseUrl.trim(),
                        apiKey.trim(),
                        smallModelId.trim(),
                        models.toList(),
                        selectedDefault,
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    if (showAddModel) {
        AddModelDialog(
            existing = models.toSet(),
            onFetch = { onFetch(baseUrl.trim(), apiKey.trim()) },
            onAdd = { id ->
                if (id.isNotBlank() && id !in models) models.add(id)
            },
            onDismiss = { showAddModel = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddModelDialog(
    existing: Set<String>,
    onFetch: suspend () -> Result<List<String>>,
    onAdd: (modelId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var modelId by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加模型") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = modelId, onValueChange = { modelId = it },
                        label = { Text("模型 ID") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = modelId.isNotBlank() &&
                            modelId.trim() !in existing,
                        onClick = {
                            onAdd(modelId.trim())
                            modelId = ""
                        },
                    ) {
                        Text(if (modelId.trim() in existing) "已添加" else "添加")
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = !fetching,
                        onClick = {
                            fetching = true
                            fetchError = ""
                            fetched = emptyList()
                            scope.launch {
                                onFetch()
                                    .onSuccess { fetched = it }
                                    .onFailure { fetchError = it.message ?: "拉取失败" }
                                fetching = false
                            }
                        },
                    ) { Text(if (fetching) "正在拉取…" else "拉取") }
                    Text(
                        "从服务商获取可用模型",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (fetchError.isNotBlank()) {
                    Text(
                        fetchError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (fetched.isNotEmpty()) {
                    Text(
                        "点选添加",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    fetched.forEach { id ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                id,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 8.dp),
                            )
                            TextButton(
                                enabled = id !in existing,
                                onClick = {
                                    onAdd(id)
                                    fetched = fetched - id
                                },
                            ) { Text(if (id in existing) "已添加" else "添加") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddProviderDialog(
    onFetch: suspend (baseUrl: String, apiKey: String) -> Result<List<String>>,
    onDismiss: () -> Unit,
    onAdd: (
        name: String,
        baseUrl: String,
        apiKey: String,
        modelId: String,
        smallModelId: String,
    ) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var smallModelId by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchError by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加服务商") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("预设", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    providerPresets.forEach { preset ->
                        TextButton(onClick = {
                            name = preset.name
                            baseUrl = preset.baseUrl
                            modelId = preset.modelId
                        }) { Text(if (name == preset.name) "• ${preset.name}" else preset.name) }
                    }
                    TextButton(onClick = {
                        name = ""
                        baseUrl = ""
                        modelId = ""
                        smallModelId = ""
                    }) { Text("自定义") }
                }

                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true,
                )
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("Base URL") }, singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API Key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = modelId, onValueChange = { modelId = it },
                    label = { Text("模型 ID（可后续添加更多）") }, singleLine = true,
                )
                OutlinedTextField(
                    value = smallModelId, onValueChange = { smallModelId = it },
                    label = { Text("Small Model（可选）") }, singleLine = true,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = !fetching && baseUrl.isNotBlank() && apiKey.isNotBlank(),
                        onClick = {
                            fetching = true
                            fetchError = ""
                            fetched = emptyList()
                            scope.launch {
                                onFetch(baseUrl.trim(), apiKey.trim())
                                    .onSuccess { fetched = it }
                                    .onFailure { fetchError = it.message ?: "拉取失败" }
                                fetching = false
                            }
                        },
                    ) { Text(if (fetching) "正在拉取…" else "拉取") }
                    if (baseUrl.isBlank() || apiKey.isBlank()) {
                        Text(
                            "填写 Base URL 和 API Key 后可拉取",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (fetchError.isNotBlank()) {
                    Text(
                        fetchError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (fetched.isNotEmpty()) {
                    Text(
                        "点选填入模型 ID",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fetched.forEach { item ->
                            TextButton(onClick = { modelId = item }) {
                                Text((if (modelId == item) "• " else "") + item)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && baseUrl.isNotBlank() && modelId.isNotBlank(),
                onClick = {
                    onAdd(
                        name.trim(),
                        baseUrl.trim(),
                        apiKey.trim(),
                        modelId.trim(),
                        smallModelId.trim(),
                    )
                },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private data class ProviderPreset(
    val name: String,
    val baseUrl: String,
    val modelId: String,
)

/** 市面主流服务商预设：选中后只需填 API Key。 */
private val providerPresets = listOf(
    ProviderPreset("DeepSeek", "https://api.deepseek.com/anthropic", "deepseek-flash"),
    ProviderPreset("GLM", "https://open.bigmodel.cn/api/anthropic", "glm-5.3-flash"),
    ProviderPreset("Kimi", "https://api.moonshot.cn/anthropic", "kimi-k2-0905-preview"),
    ProviderPreset("SiliconFlow", "https://api.siliconflow.cn", "deepseek-ai/DeepSeek-V4.1-Flash"),
    ProviderPreset("Qwen", "https://maas.qianwenaiapi.com/apps/anthropic", "deepseek-v4.1-flash-1017"),
)