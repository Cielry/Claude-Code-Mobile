package com.lyuan.cc.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyuan.cc.data.ConversationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationDrawerContent(
    conversations: List<ConversationEntity>,
    currentId: String,
    onSelect: (ConversationEntity) -> Unit,
    onDelete: (ConversationEntity) -> Unit,
    onNew: () -> Unit,
    onClose: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight(),
        drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerContentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("对话", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onClose) {
                Text("关闭", color = MaterialTheme.colorScheme.onBackground)
            }
        }
        Button(
            onClick = onNew,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) { Text("新建对话") }
        Text(
            text = "长按对话可删除",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        if (conversations.isEmpty()) {
            Text(
                "还没有对话",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(conversations, key = { it.id }) { conversation ->
                val active = conversation.id == currentId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (active) Color(0xFFD97757).copy(alpha = 0.18f)
                            else Color.Transparent
                        )
                        .combinedClickable(
                            onClick = { onSelect(conversation) },
                            onLongClick = { onDelete(conversation) },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = conversation.title.ifBlank { "未命名对话" },
                        fontSize = 15.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) Color(0xFFD97757)
                        else MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = (conversation.workspacePath ?: "默认工作区") +
                            "  ·  " + formatter.format(Date(conversation.updatedAt)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}