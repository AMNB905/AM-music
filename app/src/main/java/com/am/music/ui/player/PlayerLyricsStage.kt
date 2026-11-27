package com.am.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.am.music.ui.components.RotatingLoadingHint
import com.am.music.ui.components.SkeletonBlock
import com.am.music.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 下拉多少就认作「想回封面」。 */
private val PULL_BACK_THRESHOLD = 96.dp

@Composable
internal fun PlayerLyricsStage(
    lyricsState: LyricsUiState,
    positionMs: Long,
    isPlaying: Boolean,
    coverUrl: String?,
    onSeek: (Long) -> Unit,
    onAlignLine: (Long) -> Unit,
    onImportLyrics: () -> Unit,
    onBackToCover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 回封面的手势是「下滑 / 轻点空白」：不占位置、不出现按钮。
    // 下拉时整块歌词跟着手指走，松手够远就落回封面，不够远就弹回去。
    val pull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val maxPullPx = with(LocalDensity.current) { 220.dp.toPx() }
    val thresholdPx = with(LocalDensity.current) { PULL_BACK_THRESHOLD.toPx() }

    // 刚进歌词页给一句提示，教一次手势就退场（之后完全靠手势，不再有按钮）
    var hintVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(3200)
        hintVisible = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = pull.value
                alpha = 1f - 0.35f * (pull.value / maxPullPx).coerceIn(0f, 1f)
            }
            // 空白处轻点即回封面；列表行自己会消费点击（那是「跳到这句」），互不抢
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onBackToCover() })
            }
            .draggable(
                state = rememberDraggableState { delta ->
                    scope.launch {
                        pull.snapTo((pull.value + delta).coerceIn(0f, maxPullPx))
                    }
                },
                orientation = Orientation.Vertical,
                onDragStopped = {
                    if (pull.value > thresholdPx) onBackToCover() else pull.animateTo(0f, Motion.settle())
                },
            ),
    ) {
        when (val lyrics = lyricsState) {
            LyricsUiState.Loading -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 歌词占位：三行长短不一的骨架，像正在成形的歌词，不像空转的圈
                listOf(0.62f, 0.44f, 0.74f, 0.36f, 0.56f).forEach { fraction ->
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(12.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Spacer(Modifier.height(8.dp))
                RotatingLoadingHint(
                    messages = listOf("正在取歌词…", "正在对齐时间轴…", "马上就好"),
                    modifier = Modifier.padding(horizontal = 0.dp),
                )
            }

            LyricsUiState.NotFound -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "暂无歌词",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onImportLyrics) {
                    Text("导入 LRC 文件", style = MaterialTheme.typography.labelSmall)
                }
            }

            is LyricsUiState.Ready -> LyricsView(
                lines = lyrics.lines,
                positionMs = positionMs,
                isPlaying = isPlaying,
                onSeek = onSeek,
                onAlignLine = onAlignLine,
                coverUrl = coverUrl,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 手势提示：一块很淡的胶囊，3 秒后自己退场，不是按钮
        AnimatedVisibility(
            visible = hintVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
            enter = fadeIn(tween(320)),
            exit = fadeOut(tween(420)),
        ) {
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                        RoundedCornerShape(20.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = "下滑 · 或轻点空白，回到封面",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
