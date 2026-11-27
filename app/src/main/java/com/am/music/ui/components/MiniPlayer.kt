package com.am.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.am.music.playback.PlaybackState
import com.am.music.ui.theme.Motion
import com.am.music.ui.components.MotionIconButton
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun MiniPlayer(
    state: PlaybackState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // 浮岛形态下这颗小播放器自己就是一块独立的胶囊，不需要通栏分隔线；
    // 进度轨道也交给调用方（浮岛理态用透明，普通态用 surface），否则会在胶囊里切出一道横线。
    showDivider: Boolean = true,
    progressTrackColor: Color? = null,
    // 在迷你条上往右滑 = 上一首；不传就只有下一首可滑
    onPrevious: (() -> Unit)? = null,
    // 上滑预览时胶囊里这颗小封面要「飞出去」：交给调用方把它淡掉，避免画面里出现两张封面
    coverAlpha: Float = 1f,
) {
    val song = state.currentSong ?: return
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // 横滑时整块内容跟着手指走，松手过 36dp 就换歌，否则弹回去
    val slide = remember { Animatable(0f) }
    // Canvas 的绘制 lambda 不是 @Composable，颜色先在外面取好
    val primary = MaterialTheme.colorScheme.primary
    val trackTint = progressTrackColor ?: MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    Surface(
        // 半透明浮层由根布局统一铺底，这里保持透明，内容才能从下面滚过去
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            if (showDivider) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            val progress = if (state.durationMs > 0) {
                (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            // 进度线：播过的一段从左到右由淡转满，头部一颗小亮点——播放中它在走，
            // 比一条纯色细线更能说明「现在到哪儿了」
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            ) {
                val y = size.height / 2f
                val stroke = 2.dp.toPx()
                drawLine(
                    color = trackTint,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                val headX = size.width * progress
                if (headX > 0f) {
                    drawLine(
                        brush = Brush.horizontalGradient(
                            0f to primary.copy(alpha = 0.55f),
                            1f to primary,
                            startX = 0f,
                            endX = headX.coerceAtLeast(1f),
                        ),
                        start = Offset(0f, y),
                        end = Offset(headX, y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(
                        color = primary.copy(alpha = 0.16f),
                        radius = 4.dp.toPx(),
                        center = Offset(headX, y),
                    )
                    drawCircle(
                        color = primary,
                        radius = 1.4.dp.toPx(),
                        center = Offset(headX, y),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationX = slide.value
                        // 拖远了稍微淡一点：像一张牌被抽出来
                        alpha = 1f - 0.22f * (abs(slide.value) / 72.dp.toPx()).coerceIn(0f, 1f)
                    }
                    .pointerInput(song.key) {
                        val limit = 72.dp.toPx()
                        val trigger = 36.dp.toPx()
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                val target = (slide.value + amount).coerceIn(-limit, limit)
                                scope.launch { slide.snapTo(target) }
                            },
                            onDragEnd = {
                                val offset = slide.value
                                when {
                                    offset <= -trigger -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onNext()
                                    }

                                    offset >= trigger && onPrevious != null -> {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onPrevious()
                                    }
                                }
                                scope.launch { slide.animateTo(0f, Motion.settle()) }
                            },
                            onDragCancel = {
                                scope.launch { slide.animateTo(0f, Motion.settle()) }
                            },
                        )
                    }
                    .clickable(onClick = onClick)
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArt(
                    uri = song.albumArtUri,
                    size = 38.dp,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.graphicsLayer { alpha = coverAlpha },
                )
                Spacer(Modifier.width(12.dp))
                // 换歌时标题淡出淡入 + 从下方轻推上来，眼睛能跟上「换了一首」这件事
                AnimatedContent(
                    targetState = song,
                    transitionSpec = {
                        // 位移加厚一档（1/10 → 1/6），换歌在迷你条上也能看出来
                        (fadeIn(tween(Motion.SWAP, delayMillis = 50)) +
                            slideInVertically(tween(Motion.ENTER, easing = Motion.EmphasizedDecel)) { it / 6 }) togetherWith
                            fadeOut(tween(170))
                    },
                    modifier = Modifier.weight(1f),
                    label = "miniMeta",
                ) { current ->
                    Column {
                        Text(
                            text = current.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = current.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                MotionIconButton(onClick = onTogglePlayPause, modifier = Modifier.size(40.dp)) {
                    AnimatedContent(
                        targetState = state.isPlaying,
                        transitionSpec = {
                            // 和播放页同一套手感：小图标弹出来，旧的缩回去
                            (fadeIn(tween(140, delayMillis = 20)) +
                                scaleIn(Motion.pop(), initialScale = 0.56f)) togetherWith
                                fadeOut(tween(110))
                        },
                        label = "miniPlayPause",
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                MotionIconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}
