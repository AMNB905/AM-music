package com.am.music.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.am.music.data.announce.Announcement
import com.am.music.data.announce.AnnouncementFeed

/**
 * 启动公告弹窗。
 *
 * 视觉沿用全局极简口径：纸白底、无阴影（tonalElevation = 0）、2dp 圆角、
 * 0.5dp 实用分隔线、页边距 24dp。正文最多占 400dp 高度，超出内部滚动——
 * 长公告不会把弹窗撑出屏幕，也不会把按钮挤走。
 */
@Composable
fun AnnouncementDialog(
    feed: AnnouncementFeed,
    onDismiss: () -> Unit,
    onDismissForever: () -> Unit,
) {
    AmAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (feed.items.size == 1) "公告" else "公告 · ${feed.items.size} 条",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                feed.items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 14.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    AnnouncementBlock(item = item)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = MaterialTheme.colorScheme.onSurface)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissForever) {
                Text("不再显示", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun AnnouncementBlock(item: Announcement) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (item.content.isNotEmpty()) {
            Text(
                text = item.content,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.link.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(item.link))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text("查看详情", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
