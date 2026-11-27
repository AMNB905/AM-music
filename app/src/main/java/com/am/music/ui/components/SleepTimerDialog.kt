package com.am.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 定时关闭的选择弹窗：常用时长一眼可选，也能自己填分钟数。
 *
 * 设置页和播放页 ⋯ 菜单共用这一个。之前只有设置页能进，睡前听歌得先退出播放页去翻设置，
 * 谁都不愿意这么干，所以同一个弹窗两处都用。
 */
@Composable
fun SleepTimerDialog(
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by rememberSaveable { mutableStateOf("") }

    AmAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时关闭") },
        text = {
            Column {
                Text(
                    text = "到点渐隐暂停，进度还在，回来接着放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45).forEach { minutes ->
                        Chip(
                            text = "$minutes 分钟",
                            selected = false,
                            onClick = { onPick(minutes) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60, 90).forEach { minutes ->
                        Chip(
                            text = "$minutes 分钟",
                            selected = false,
                            onClick = { onPick(minutes) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { input -> custom = input.filter { it.isDigit() }.take(3) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("自定义分钟数", style = MaterialTheme.typography.bodySmall)
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { custom.toIntOrNull()?.let(onPick) },
                        enabled = (custom.toIntOrNull() ?: 0) in 1..999,
                    ) { Text("开始") }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
