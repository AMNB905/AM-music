package com.am.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.am.music.ui.theme.pressFeedback

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
    /** 队列追踪：点这颗胶囊上的「12/700」直接打开队列，并落在正在播那首。 */
    onOpenQueue: (() -> Unit)? = null,
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
            // 进度条：比一条细线多说几件事——
            // 底色轨道 / 带动画的渐变进度 / 头部光点 + 呼吸光晕 / 一段沿着已播部分的微光扫过，
            // 暂停时整条收住（光点变暗、扫光停下），一眼能看出「在走」还是「停着」。
            val infinite = rememberInfiniteTransition(label = "miniProgress")
            val breathe by infinite.animateFloat(
                initialValue = 0.72f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "headBreathe",
            )
            val sweep by infinite.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2600, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "playedSweep",
            )
            val playing = state.isPlaying
            val headScale = if (playing) breathe else 0.82f
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp),
            ) {
                val barY = size.height * 0.62f
                val stroke = 3.dp.toPx()
                val radius = stroke / 2f
                val headX = size.width * progress

                // 轨道：一道很淡的底，告诉眼睛「这条还有多长」
                drawRoundRect(
                    color = trackTint.copy(alpha = 0.55f),
                    topLeft = Offset(0f, barY - radius),
                    size = Size(size.width, stroke),
                    cornerRadius = CornerRadius(radius, radius),
                )

                if (headX > 0f) {
                    // 已播段下面一层柔光：像灯带透出来的余晖，比纯色条有厚度
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to primary.copy(alpha = 0.10f),
                            1f to Color.Transparent,
                            startY = barY - 5.dp.toPx(),
                            endY = barY + 4.dp.toPx(),
                        ),
                        topLeft = Offset(0f, barY - 5.dp.toPx()),
                        size = Size(headX, 9.dp.toPx()),
                    )
                    // 进度本体：从左到右由淡转满，头部最亮
                    drawLine(
                        brush = Brush.horizontalGradient(
                            0f to primary.copy(alpha = 0.42f),
                            0.75f to primary.copy(alpha = 0.92f),
                            1f to primary,
                            startX = 0f,
                            endX = headX.coerceAtLeast(1f),
                        ),
                        start = Offset(radius, barY),
                        end = Offset(headX, barY),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    // 一段微光在已播段里来回扫：播放中它一直在动，暂停就停住
                    if (playing) {
                        val bandW = 26.dp.toPx()
                        val bandX = (headX * sweep * 1.35f - bandW * 0.2f)
                        val left = bandX.coerceIn(0f, (headX - 1f).coerceAtLeast(0f))
                        val right = (bandX + bandW).coerceAtMost(headX)
                        if (right > left) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0f to Color.White.copy(alpha = 0f),
                                    0.5f to Color.White.copy(alpha = 0.16f),
                                    1f to Color.White.copy(alpha = 0f),
                                    startX = left,
                                    endX = right,
                                ),
                                topLeft = Offset(left, barY - radius),
                                size = Size(right - left, stroke),
                            )
                        }
                    }
                    // 头部：外圈呼吸光晕 + 中心亮点
                    drawCircle(
                        color = primary.copy(alpha = 0.20f * headScale),
                        radius = 5.dp.toPx() * headScale,
                        center = Offset(headX, barY),
                    )
                    drawCircle(
                        color = primary.copy(alpha = if (playing) 1f else 0.6f),
                        radius = 1.9.dp.toPx(),
                        center = Offset(headX, barY),
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
                    .pressFeedback(scale = 0.98f, haptic = true)
                    .clickable(onClick = onClick)
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArt(
                    uri = song.albumArtUri,
                    size = 38.dp,
                    shape = RoundedCornerShape(2.dp),
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
                // 队列进度：12/700 一眼知道听到哪儿了，点一下直接进队列
                // 切歌时序号是「翻牌」而不是跳数字：旧数字往上滑走、新数字从下滑上来，
                // 700 这种总数不变的地方就不动，眼睛只跟着变化的那一位
                if (onOpenQueue != null && state.queue.isNotEmpty()) {
                    val position = (state.currentIndex + 1).coerceAtLeast(1)
                    Row(
                        modifier = Modifier
                            .pressFeedback(scale = 0.94f)
                            .clickable(onClick = onOpenQueue)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AnimatedContent(
                            targetState = position,
                            transitionSpec = {
                                if (targetState > initialState) {
                                    (fadeIn(tween(150, delayMillis = 40)) +
                                        slideInVertically(tween(Motion.SWAP, easing = Motion.EmphasizedDecel)) { it }) togetherWith
                                        (fadeOut(tween(140)) +
                                            slideOutVertically(tween(Motion.SWAP, easing = Motion.EmphasizedAccel)) { -it })
                                } else {
                                    (fadeIn(tween(150, delayMillis = 40)) +
                                        slideInVertically(tween(Motion.SWAP, easing = Motion.EmphasizedDecel)) { -it }) togetherWith
                                        (fadeOut(tween(140)) +
                                            slideOutVertically(tween(Motion.SWAP, easing = Motion.EmphasizedAccel)) { it })
                                }
                            },
                            label = "miniQueuePos",
                        ) { pos ->
                            Text(
                                text = "$pos",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = "/${state.queue.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
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
