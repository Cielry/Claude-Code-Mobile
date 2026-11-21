package com.lyuan.cc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyuan.cc.data.ProviderEntity
import com.lyuan.cc.data.ProviderProtocol
import com.lyuan.cc.data.Workspace

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewConversationDialog(
    providers: List<ProviderEntity>,
    modelsOf: (ProviderEntity) -> List<String>,
    defaultProviderId: String,
    defaultModelId: String,
    defaultWorkspace: String,
    workspaces: List<Workspace>,
    selectedWorkspaceId: String,
    onDismiss: () -> Unit,
    onCreate: (
        title: String,
        workspace: String,
        providerId: String,
        modelId: String,
        effort: String?,
    ) -> Unit,
) {
    var title by remember { mutableStateOf("新对话") }
    var providerId by remember { mutableStateOf(defaultProviderId) }
    var modelId by remember { mutableStateOf(defaultModelId) }
    var effort by remember { mutableStateOf<String?>("low") }
    var workspaceId by remember { mutableStateOf(selectedWorkspaceId) }

    LaunchedEffect(workspaces, selectedWorkspaceId) {
        if (selectedWorkspaceId.isNotBlank() && workspaces.none { it.id == selectedWorkspaceId }) {
            workspaceId = ""
        }
    }

    LaunchedEffect(providers) {
        if (providers.none { it.id == providerId }) {
            providerId = providers.firstOrNull()?.id ?: ""
        }
        val provider = providers.firstOrNull { it.id == providerId }
        val models = provider?.let(modelsOf) ?: emptyList()
        if (models.none { it == modelId }) {
            modelId = models.firstOrNull() ?: ""
        }
    }

    val selectedProvider = providers.firstOrNull { it.id == providerId }
    val models = selectedProvider?.let(modelsOf) ?: emptyList()
    val selectedWorkspace = workspaces.firstOrNull { it.id == workspaceId }
    val workspace = selectedWorkspace?.path ?: defaultWorkspace

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建对话") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )

                Section("工作区") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OptionChip("默认", workspaceId.isBlank()) { workspaceId = "" }
                        workspaces.forEach { item ->
                            OptionChip(item.name, workspaceId == item.id) { workspaceId = item.id }
                        }
                    }
                    Text(
                        workspace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (workspaces.isEmpty()) {
                        Text(
                            "可在「配置 → 工作区」添加自定义文件夹。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Section("服务商") {
                    if (providers.isEmpty()) {
                        Text(
                            "请先在“配置”中添加服务商。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Column {
                        providers.forEach { provider ->
                            val protocol = ProviderProtocol.fromWire(provider.protocol)
                            val suffix = when (protocol) {
                                ProviderProtocol.OPENAI -> " · OpenAI"
                                ProviderProtocol.RESPONSES -> " · Responses"
                                else -> ""
                            }
                            Text(
                                text = (if (provider.id == providerId) "• " else "") +
                                    provider.name + suffix,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        providerId = provider.id
                                        modelId = modelsOf(provider).firstOrNull() ?: ""
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Section("模型") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        models.forEach { model ->
                            OptionChip(model, model == modelId) { modelId = model }
                        }
                    }
                }

                Section("思考档位") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        effortOptions.forEach { option ->
                            OptionChip(option.label, effort == option.value) { effort = option.value }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = providerId.isNotBlank() && modelId.isNotBlank() && workspace.isNotBlank(),
                onClick = {
                    onCreate(
                        title.trim().ifBlank { "新对话" },
                        workspace,
                        providerId,
                        modelId,
                        effort,
                    )
                },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun Section(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = (if (selected) "• " else "") + label,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}