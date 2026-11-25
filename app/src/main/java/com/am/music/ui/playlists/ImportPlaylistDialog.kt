package com.am.music.ui.playlists

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.am.music.ui.components.AmAlertDialog

/**
 * 导入歌单：粘贴分享链接（整段分享文案也行）→ 认平台和歌单 id → 取全量曲目 → 落成本地一个歌单。
 *
 * 只保留「粘贴」一条入口：QQ 的分享卡片二维码里装的其实是一条短链（要再跟一次 302 才拿到歌单 id），
 * 端上还得先解码再展开，链路长、失败点还多，收益不如直接粘贴。粘贴短链照样能导：
 * 解析层认 `c6.y.qq.com/base/fcgi-bin/u?__=…` 这种短链，会自己跟一次跳转。
 *
 * 三步都有话说（读取中显示到第几步，失败说原因，成功说导入了多少首），
 * 不出现「点了按钮，界面上什么都没变」。
 */
@Composable
fun ImportPlaylistDialog(
    state: ImportState,
    onImport: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 刚打开时先看剪贴板：用户通常刚复制完链接切过来，能省一次粘贴
    val context = LocalContext.current
    val clipboardText = remember {
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull().orEmpty()
    }
    var link by rememberSaveable {
        mutableStateOf(clipboardText.takeIf { it.contains("http") }.orEmpty())
    }

    val busy = state.running
    val finished = state.done != null

    AmAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("导入歌单") },
        text = {
            Column {
                when {
                    busy -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = state.step.ifBlank { "正在读取歌单…" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "大歌单要一两分钟，别退出。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    finished -> Text(state.done.orEmpty(), style = MaterialTheme.typography.bodyMedium)

                    else -> {
                        if (state.error != null) {
                            Text(state.error, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "要「歌单」链接，不是单曲/专辑/电台。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            OutlinedTextField(
                                value = link,
                                onValueChange = { link = it },
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier.padding(top = 4.dp),
                                placeholder = {
                                    Text(
                                        text = "粘贴网易云 / QQ 歌单链接\n整段分享文案也行",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "只存歌单信息，播放走在线；没版权的放不了。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // 教程入口做成实心按钮：它以前是一行小灰字，用户根本看不见
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = { openGuide(context) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(2.dp),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 10.dp,
                            ),
                        ) {
                            Text(
                                text = "看图学怎么复制链接",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                busy -> TextButton(onClick = onCancel) { Text("取消") }
                finished -> TextButton(onClick = onDismiss) { Text("完成") }
                else -> TextButton(
                    onClick = { onImport(link) },
                    enabled = link.isNotBlank(),
                ) { Text("导入") }
            }
        },
        dismissButton = {
            if (!busy && !finished && state.error == null) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 配图步骤页（服务器上的静态页，`…/guide/`）。
 *
 * 用系统浏览器打开而不是内置 WebView：这一步是「怎么操作 QQ 音乐」的说明，
 * 内置页面会让用户以为还得在 App 里再点一遍，外链更清楚，也不占包体积。
 */
private fun openGuide(context: Context) {
    // 带上版本号当查询串：静态页会被 CDN 缓存，改完文案不带参数会有一段时间还看旧页
    val url = com.am.music.data.security.Secrets.baseUrl.trimEnd('/') +
        "/guide/?v=" + com.am.music.BuildConfig.VERSION_NAME
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
