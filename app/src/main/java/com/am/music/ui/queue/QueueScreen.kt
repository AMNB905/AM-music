package com.am.music.ui.queue

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import kotlinx.coroutines.launch
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
import com.am.music.ui.components.stableKeys
import com.am.music.ui.theme.pressFeedback

/** 队列行的固定高度：拖动排序靠它换算「挪了几格」。 */
private val ROW_HEIGHT = 68.dp

/** 手指进到这个距离以内，就开始自动滚屏。 */
private val DRAG_EDGE = 96.dp

/** 自动滚屏每帧走多远：约 4 行/秒，快但不飘。 */
private val AUTO_SCROLL_STEP = 11.dp

/** 一帧里最多补几次位，防止极端滚动下一直循环。 */
private const val MAX_SWAPS_PER_FRAME = 6

@Composable
fun QueueScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QueueViewModel = viewModel { QueueViewModel(container) },
) {
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    // 拖动排序的现场：哪一首在被拖、它此刻排在第几格、相对自己那一格偏了多少
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 拖到列表上下边缘时自动滚屏的方向：-1 往上、1 往下、0 不滚
    var autoScrollDir by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()
    val queue = state.queue
    val queueState = rememberUpdatedState(queue)
    val density = LocalDensity.current
    val rowPx = with(density) { ROW_HEIGHT.toPx() }
    val edgePx = with(density) { DRAG_EDGE.toPx() }
    val scrollStepPx = with(density) { AUTO_SCROLL_STEP.toPx() }
    val haptics = LocalHapticFeedback.current
    val openPlayerHandler by rememberUpdatedState(onOpenPlayer)
    val playHandler by rememberUpdatedState<(Int) -> Unit>({ viewModel.playAt(it) })

    /** 越半行就真的换一格：换完把偏移折算回去，手感还是「拎在手里」。 */
    fun settleDrag() {
        var guard = 0
        while (guard++ < MAX_SWAPS_PER_FRAME) {
            val count = queueState.value.size
            if (dragIndex < 0) return
            if (dragOffset > rowPx / 2f && dragIndex < count - 1) {
                viewModel.moveBy(dragIndex, 1)
                dragIndex++
                dragOffset -= rowPx
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            } else if (dragOffset < -rowPx / 2f && dragIndex > 0) {
                viewModel.moveBy(dragIndex, -1)
                dragIndex--
                dragOffset += rowPx
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            } else {
                return
            }
        }
    }

    /** 按被拖那行当前在屏幕上的位置，决定要不要开始滚屏。 */
    fun updateAutoScroll() {
        val key = draggingKey
        if (key == null) {
            autoScrollDir = 0
            return
        }
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val viewportH = info.viewportSize.height
        if (viewportH <= 0) return
        val top = item.offset + dragOffset
        val bottom = top + rowPx
        autoScrollDir = when {
            top < edgePx -> -1
            bottom > viewportH - edgePx -> 1
            else -> 0
        }
    }

    // 滚屏循环：手指停在边缘就不动，列表自己走，滚过的像素补给被拖那行，它才不会掉队
    LaunchedEffect(autoScrollDir, draggingKey) {
        if (draggingKey == null || autoScrollDir == 0) return@LaunchedEffect
        while (dragIndex >= 0) {
            val dir = autoScrollDir
            if (dir == 0) break
            val consumed = listState.scrollBy(scrollStepPx * dir)
            if (consumed == 0f) break
            dragOffset += consumed
            settleDrag()
            withFrameNanos { }
        }
    }

    // 打开队列先落在正在播那首附近：以前每次都从第一首开始翻，用户得自己找
    var jumpedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(queue.size, state.currentIndex) {
        if (jumpedOnce || queue.isEmpty()) return@LaunchedEffect
        val target = state.currentIndex
        if (target < 0) return@LaunchedEffect
        jumpedOnce = true
        // 列表第 0 行是提示文案，所以队列第 i 首是第 i+1 行
        listState.scrollToItem((target + 1).coerceIn(0, queue.lastIndex + 1))
    }

    // 当前那首滚出屏幕时，底部浮一颗「回到正在播」，不用再自己找
    val currentOutOfView by remember(listState) {
        derivedStateOf {
            val q = queueState.value
            if (q.isEmpty()) return@derivedStateOf false
            val info = listState.layoutInfo
            info.visibleItemsInfo.none { it.index == state.currentIndex + 1 }
        }
    }
    val revealScope = rememberCoroutineScope()

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
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (queue.isEmpty()) "空" else "${queue.size} 首",
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
            Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item(key = "hint") {
                    Text(
                        text = "长按一行可以拖动排序 · 拖到边缘会自动滚屏 · 播放中 " +
                            "${(state.currentIndex + 1).coerceAtLeast(0)}/${queue.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 19.dp, top = 2.dp, bottom = 6.dp),
                    )
                }
                val queueKeys = queue.stableKeys { it.key }
                itemsIndexed(queue, key = { i, _ -> queueKeys[i] }) { index, song ->
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
                        // 正在播的那首点一下是「回到播放页」，其它歌点一下才开始放
                        onPlay = {
                            if (isCurrent) openPlayerHandler() else playHandler(index)
                        },
                        onRemove = { viewModel.removeAt(index) },
                        onDragStart = {
                            draggingKey = song.key
                            dragIndex = queueState.value.indexOfFirst { it.key == song.key }
                            dragOffset = 0f
                            autoScrollDir = 0
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { amount ->
                            dragOffset += amount
                            settleDrag()
                            updateAutoScroll()
                        },
                        onDragEnd = {
                            draggingKey = null
                            dragIndex = -1
                            dragOffset = 0f
                            autoScrollDir = 0
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
                // 滚出屏幕时给一条「回到正在播」：落点始终是当前那首，不用自己找
                if (currentOutOfView && state.currentIndex >= 0) {
                    Text(
                        text = "回到正在播 ${state.currentIndex + 1}/${queue.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 22.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.94f))
                            .pressFeedback(scale = 0.95f, haptic = true)
                            .clickable {
                                revealScope.launch {
                                    listState.animateScrollToItem(
                                        (state.currentIndex + 1).coerceIn(0, queue.lastIndex + 1)
                                    )
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
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
