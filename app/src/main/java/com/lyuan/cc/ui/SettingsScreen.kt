package com.lyuan.cc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyuan.cc.runtime.RuntimeManager
import kotlinx.coroutines.launch

private const val REPO_URL = "https://github.com/Cielry/Claude-Code-Mobile"

/** 设置页：运行环境状态与关于信息。运行时在首次启动时自动准备。 */
@Composable
fun SettingsScreen(manager: RuntimeManager) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val initState by manager.initState.collectAsState()
    var status by remember { mutableStateOf(manager.quickStatus()) }
    var retrying by remember { mutableStateOf(false) }
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    LaunchedEffect(initState) {
        if (!initState.preparing) status = manager.quickStatus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("运行环境", style = MaterialTheme.typography.labelLarge)
                val text = when {
                    initState.preparing -> initState.message
                    status.ready -> "已就绪"
                    initState.message.isNotBlank() -> initState.message
                    else -> "尚未就绪"
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.ready) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!status.ready && !initState.preparing) {
                    TextButton(
                        enabled = !retrying,
                        onClick = {
                            scope.launch {
                                retrying = true
                                manager.ensureInitialized()
                                status = manager.quickStatus()
                                retrying = false
                            }
                        },
                    ) { Text(if (retrying) "准备中…" else "重试") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("关于", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = if (versionName.isNotBlank()) "v$versionName" else "版本号不可用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = REPO_URL,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        runCatching { uriHandler.openUri(REPO_URL) }
                    },
                )
            }
        }

        Text(
            "意见反馈：aaalyuan@outlook.com",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}