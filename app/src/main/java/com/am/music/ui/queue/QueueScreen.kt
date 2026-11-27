package com.am.music.ui.queue

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.data.model.Song
import com.am.music.ui.components.AlbumArt
import com.am.music.ui.components.AmAlertDialog
import com.am.music.ui.components.IconAppear
import com.am.music.ui.components.MotionIconButton
import com.am.music.ui.theme.Motion
import com.am.music.util.formatTotalDuration
import kotlin.math.roundToInt

/** 队列行的固定高度：拖动排序靠它换算「挪了几格」。 */
private val ROW_HEIGHT = 68.dp

@Composable
fun QueueScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QueueViewModel = viewModel { QueueViewModel(container) },
) {
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    // 拖动排序的现场：只记「哪一首正在被拖」和「相对它自己那一格的偏移」
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val rowPx = with(density) { ROW_HEIGHT.toPx() }
    val haptics = LocalHapticFeedback.current
    val queue = state.queue

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
            val remainingMs = remember(queue, state.currentIndex) {
                val from = state.currentIndex.coerceIn(0, queue.size)
                (from until queue.size).sumOf { queue[it].durationMs }
            }
            Text(
                text = if (queue.isEmpty()) {
                    "空"
                } else {
                    "${queue.size} 首 · 剩约 ${formatTotalDuration(remainingMs)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (queue.isNotEmpty()) {
                TextButton(onClick = { showClearDialog = true }) {
                    Text("清空")
                }
            }
        }

        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "队列空的，去放几首",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "hint") {
                    Text(
                        text = "长按一行可以拖动排序 · 播放中 ${(state.currentIndex + 1).coerceAtLeast(0)}/${queue.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 19.dp, top = 2.dp, bottom = 6.dp),
                    )
                }
                itemsIndexed(queue, key = { _, song -> song.key }) { index, song ->
                    val isCurrent = index == state.currentIndex
                    val isDragging = draggingKey == song.key
                    // 拖动时这一格自己不再跟随列表动画，交给手指；其它行正常做位置动画
                    val dragY = if (isDragging) dragOffset else 0f
                    QueueSongRow(
                        song = song,
                        isCurrent = isCurrent,
                        isDragging = isDragging,
                        progress = if (isCurrent && state.durationMs > 0) {
                            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                        } else {
                            null
                        },
                        onPlay = { viewModel.playAt(index) },
                        onRemove = { viewModel.removeAt(index) },
                        onDragStart = {
                            draggingKey = song.key
                            dragOffset = 0f
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { amount ->
                            dragOffset += amount
                            val from = queue.indexOfFirst { it.key == song.key }
                            if (from >= 0) {
                                // 每越过半行就真的换一次位置：列表跟着做位移动画，手感是「被拎着走」
                                var moved = false
                                while (dragOffset > rowPx / 2f && from + 1 <= queue.lastIndex && !moved) {
                                    viewModel.moveBy(from, 1)
                                    dragOffset -= rowPx
                                    moved = true
                                }
                                while (!moved && dragOffset < -rowPx / 2f && from > 0) {
                                    viewModel.moveBy(from, -1)
                                    dragOffset += rowPx
                                    moved = true
                                }
                                if (moved) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                        onDragEnd = {
                            draggingKey = null
                            dragOffset = 0f
                        },
                        modifier = Modifier
                            .animateItem(
                                placementSpec = if (isDragging) null else spring(
                                    dampingRatio = 0.72f,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                                fadeInSpec = tween(280),
                                fadeOutSpec = tween(170),
                            )
                            .zIndex(if (isDragging) 2f else 0f)
                            .graphicsLayer {
                                translationY = dragY
                                if (isDragging) {
                                    val s = 1.02f
                                    scaleX = s
                                    scaleY = s
                                }
                            },
                        dragKey = song.key,
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AmAlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空播放队列") },
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
    isDragging: Boolean,
    progress: Float?,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    dragKey: String,
) {
    val emphasis by animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "queueEmphasis",
    )
    // 排序一变，行里的回调就是新的一份：手势节点不重启，但要一直拿到最新的那几个 lambda
    val dragStartHandler by rememberUpdatedState(onDragStart)
    val dragHandler by rememberUpdatedState(onDrag)
    val dragEndHandler by rememberUpdatedState(onDragEnd)
    val playHandler by rememberUpdatedState(onPlay)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isDragging) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                } else if (isCurrent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.06f * emphasis)
                } else {
                    Color.Transparent
                },
            )
            .pointerInput(dragKey) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragStartHandler() },
                    onDrag = { change, amount ->
                        change.consume()
                        dragHandler(amount.y)
                    },
                    onDragEnd = { dragEndHandler() },
                    onDragCancel = { dragEndHandler() },
                )
            }
            .pointerInput(dragKey) {
                detectTapGestures(onTap = { playHandler() })
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 当前行左边一条主色小竖条：从中间长出来，切走时收回去
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 当前这行底下一条极细的进度：不用退回播放页也知道放到哪儿了
                if (progress != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
                        )
                    }
                }
            }
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
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "拖动排序",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDragging) 0.9f else 0.35f),
            modifier = Modifier
                .size(22.dp)
                .padding(start = 2.dp),
        )
        MotionIconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "从队列移除",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
