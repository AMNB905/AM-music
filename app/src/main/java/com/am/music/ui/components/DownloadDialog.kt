package com.am.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.am.music.AppContainer
import com.am.music.data.db.DownloadEntity
import com.am.music.data.download.DownloadStatus
import com.am.music.data.download.DownloadsRepository
import com.am.music.data.model.Song
import com.am.music.playback.PlaybackNotice
import kotlinx.coroutines.launch
import com.am.music.util.formatBytes
import com.am.music.ui.theme.Motion
import com.am.music.ui.theme.pressFeedback

/**
 * 下载面板：一首歌、三档音质，各下一份、各占一格，互不覆盖。
 *
 * 每行的右侧就是它此刻的真实状态（未下载 / 45% / 已下载 12.3 MB · 实测 320K / 失败），
 * 点一下按状态做对应的唯一动作——下载、取消、重试、删除。没有第二层菜单。
 */
@Composable
fun DownloadDialog(
    song: Song,
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    val repo = container.downloadsRepository
    val tasks by repo.tasks.collectAsStateWithLifecycle()
    var rows by remember(song.key) { mutableStateOf<List<DownloadEntity>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<DownloadEntity?>(null) }
    val scope = rememberCoroutineScope()

    // 每次下载状态变化都重取一次：下完那一刻行内文字要立刻从「45%」变成「已下载」
    LaunchedEffect(song.key, tasks) {
        rows = repo.recordsOf(song.key)
    }

    AmAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = {
            Column {
                Text("下载", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 300.dp)) {
                DownloadsRepository.QUALITIES.forEachIndexed { index, quality ->
                    if (index > 0) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    val row = rows.firstOrNull { it.quality == quality }
                    val task = tasks["${song.key}@$quality"]
                    QualityRow(
                        quality = quality,
                        row = row,
                        task = task,
                        onClick = {
                            when {
                                task?.status == DownloadStatus.RUNNING ->
                                    repo.cancel(song.key, quality)

                                task?.status == DownloadStatus.FAILED ->
                                    repo.download(song, quality)

                                row != null -> pendingDelete = row

                                else -> repo.download(song, quality)
                            }
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "下到手机里，断网也能放；清缓存不会删掉。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    pendingDelete?.let { target ->
        AmAlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val victim = target
                        pendingDelete = null
                        scope.launch {
                            container.downloadsRepository.delete(victim.songKey, victim.quality)
                            PlaybackNotice.brief(
                                "已删除「${victim.title}」的${DownloadsRepository.qualityLabel(victim.quality)}下载",
                            )
                        }
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
            title = {
                Text("删除这一档？")
            },
            text = {
                Text(
                    text = "${DownloadsRepository.qualityLabel(target.quality)} · " +
                        "${formatBytes(target.bytes)}，删了要重下。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        )
    }
}

@Composable
private fun QualityRow(
    quality: String,
    row: DownloadEntity?,
    task: com.am.music.data.download.DownloadTask?,
    onClick: () -> Unit,
) {
    val running = task?.status == DownloadStatus.RUNNING
    val failed = task?.status == DownloadStatus.FAILED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .pressFeedback(scale = 1f, dim = 0.06f)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = DownloadsRepository.qualityLabel(quality),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(2.dp))
                // 未下载 → 获取地址 → 42% → 已下载：每换一次状态淡换一次，行高不跳
                AnimatedContent(
                    targetState = when {
                        running && task.total > 0 ->
                            "${(task.fraction * 100).toInt()}% · ${formatBytes(task.received)} / ${formatBytes(task.total)}"

                        running -> "正在获取地址…"
                        failed -> task.error ?: "失败"
                        row != null -> "${formatBytes(row.bytes)} · 实测 ${row.kbps}K"
                        else -> "未下载"
                    },
                    transitionSpec = {
                        fadeIn(tween(190, delayMillis = 20)) togetherWith fadeOut(tween(140))
                    },
                    label = "qualityStatus",
                ) { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = when {
                    running -> "取消"
                    failed -> "重试"
                    row != null -> "已下载"
                    else -> "下载"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (running || failed) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
        }
        // 进度线是「撑开」的：出现/收回都带高度动画，取消下载时不会把下面几行往上顶
        AnimatedVisibility(
            visible = running,
            enter = expandVertically(tween(Motion.SWAP, easing = Motion.EmphasizedDecel)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180, easing = Motion.EmphasizedAccel)) + fadeOut(tween(140)),
        ) {
            Column {
                Spacer(Modifier.height(6.dp))
                // 下载进度本身也平滑一下：上游是几百毫秒一跳的数字，直接喂给进度条会一格一格蹦
                val fraction by animateFloatAsState(
                    targetValue = task?.fraction ?: 0f,
                    animationSpec = tween(420),
                    label = "qualityProgress",
                )
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    trackColor = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
