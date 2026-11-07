package com.lyuan.cc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.lyuan.cc.data.ProviderEntity
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
    var effort by remember { mutableStateOf<String?>(null) }
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("工作区", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { workspaceId = "" }) {
                        Text((if (workspaceId.isBlank()) "• " else "") + "默认")
                    }
                    workspaces.forEach { item ->
                        TextButton(onClick = { workspaceId = item.id }) {
                            Text((if (workspaceId == item.id) "• " else "") + item.name)
                        }
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

                Text("服务商", style = MaterialTheme.typography.labelLarge)
                if (providers.isEmpty()) {
                    Text(
                        "请先在“配置”中添加服务商。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    providers.forEach { provider ->
                        TextButton(onClick = {
                            providerId = provider.id
                            modelId = modelsOf(provider).firstOrNull() ?: ""
                        }) {
                            Text(
                                (if (provider.id == providerId) "• " else "") + provider.name
                            )
                        }
                    }
                }

                Text("模型", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    models.forEach { model ->
                        TextButton(onClick = { modelId = model }) {
                            Text((if (model == modelId) "• " else "") + model)
                        }
                    }
                }

                Text("思考档位", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    effortOptions.forEach { option ->
                        TextButton(onClick = { effort = option.value }) {
                            Text((if (effort == option.value) "• " else "") + option.label)
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