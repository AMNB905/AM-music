package com.am.music.ui.queue

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.Motion
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.data.model.Song
import com.am.music.util.formatTotalDuration
import com.am.music.ui.components.IconAppear
import com.am.music.ui.components.MotionIconButton
import com.am.music.ui.components.AlbumArt
import com.am.music.ui.components.AmAlertDialog

@Composable
fun QueueScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QueueViewModel = viewModel { QueueViewModel(container) },
) {
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MotionIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "队列",
                style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            // 队列页最常问的是「还剩多久」，直接算出来，不用自己一首首加
            val remainingMs = remember(state.queue, state.currentIndex) {
                val from = state.currentIndex.coerceIn(0, state.queue.size)
                (from until state.queue.size).sumOf { state.queue[it].durationMs }
            }
            Text(
                text = if (state.queue.isEmpty()) {
                    "空"
                } else {
                    "${state.queue.size} 首 · 剩约 ${formatTotalDuration(remainingMs)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state.queue.isNotEmpty()) {
                TextButton(onClick = { showClearDialog = true }) {
                    Text("清空")
                }
            }
        }

        if (state.queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "队列空的，去放几首",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(state.queue, key = { _, song -> song.key }) { index, song ->
                    QueueSongRow(
                        song = song,
                        isCurrent = index == state.currentIndex,
                        onPlay = { viewModel.playAt(index) },
                        onRemove = { viewModel.removeAt(index) },
                        modifier = Modifier.animateItem(placementSpec = Motion.place(), fadeInSpec = tween(280), fadeOutSpec = tween(170)),
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AmAlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text("清空播放队列")
            },
            text = { Text("会停止播放并清空队列。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clear()
                        showClearDialog = false
                    }
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun QueueSongRow(
    song: Song,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val emphasis by animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "queueEmphasis",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 当前行左边一条主色小竖条：从中间长出来，切走时收回去。
        // 队列里歌多时，眼睛一扫就知道现在唱的是哪一首。
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(26.dp)
                .graphicsLayer {
                    scaleY = emphasis
                    alpha = emphasis
                }
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(9.dp))
        Box(
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    val s = 1f + 0.04f * emphasis
                    scaleX = s
                    scaleY = s
                }
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f * emphasis),
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            AlbumArt(uri = song.albumArtUri, size = 48.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 「正在播放」标记跟着歌走：切到这首时喇叭弹出来，切走时缩回去
        IconAppear(visible = isCurrent) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "正在播放",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        MotionIconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "从队列移除",
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}