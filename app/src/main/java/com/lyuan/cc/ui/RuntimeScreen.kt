package com.lyuan.cc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyuan.cc.runtime.RuntimeManager
import kotlinx.coroutines.launch

@Composable
fun RuntimeScreen(manager: RuntimeManager) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(manager.quickStatus()) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("运行时", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = "路径：" + manager.root.absolutePath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (status.ready) "状态：就绪" else if (status.initialized) "状态：缺少二进制" else "状态：未初始化",
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "资源版本：" + (status.assetVersion ?: "-"),
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                status.checks.forEach { check ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = (if (check.ok) "✓ " else "✗ ") + check.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (check.ok) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = check.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    log = "初始化中…"
                    status = manager.initialize { log = it }
                    log = if (status.ready) "初始化完成" else "初始化完成，但仍有项目未就绪"
                    busy = false
                }
            }) { Text("初始化") }

            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    log = "校验中…"
                    status = manager.verifyAll()
                    log = if (status.ready) "校验通过" else "校验发现问题"
                    busy = false
                }
            }) { Text("校验") }
        }

        if (log.isNotEmpty()) {
            Text(
                text = "输出：$log",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "意见反馈：aaalyuan@outlook.com",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}