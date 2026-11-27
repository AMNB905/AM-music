package com.am.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.am.music.ui.theme.Motion
import com.am.music.util.formatDuration
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 播放进度条。
 *
 * 手感：
 * - 静止时是一条 2dp 细线 + 一个 2.5dp 的小头，一眼能看出播到哪；
 *   播放中头会呼吸（光晕一圈一圈亮到暗），像「有东西正在走」；
 * - 一按下，线弹到 6dp、头弹到 6dp 并带一圈淡光晕——整条线变成可以抓住的东西；
 * - 点任意位置会在落点上炸开一圈水纹再 seek：点得准不准有反馈，不用事后看数字；
 * - 拖动时头顶浮出一枚时间胶囊跟着手指走（两端收边），时间用等宽字体；
 * - 按下 / 松开各一次轻触反馈。
 */
@Composable
fun PlaybackProgress(
    progressMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    onScrub: (Long?) -> Unit = {},
) {
    // 默认主题下 primary == onSurface（墨/白），选了主题色就跟着变色
    val active = MaterialTheme.colorScheme.primary
    val onActive = MaterialTheme.colorScheme.onPrimary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    // 点击水纹：x 为落点，progress 0→1 由 Animatable 驱动，结束后复位
    var rippleX by remember { mutableFloatStateOf(-1f) }
    val ripple = remember { Animatable(0f) }

    val duration = durationMs.coerceAtLeast(1L)
    val fraction = when {
        dragging -> dragFraction
        else -> (progressMs.toFloat() / duration).coerceIn(0f, 1f)
    }

    // 呼吸：只在播放中、且没在拖动时跳。暂停时把光晕静下来，画面「熄灯」
    val breath = rememberInfiniteTransition(label = "headBreath")
    val breatheRaw by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "headBreathValue",
    )
    val breathe = if (isPlaying && !dragging) breatheRaw else 0f

    val lineHeight by animateDpAsState(
        targetValue = if (dragging) 6.dp else 2.dp,
        animationSpec = Motion.settle(),
        label = "lineHeight",
    )
    val headRadius by animateDpAsState(
        targetValue = if (dragging) 6.dp else 2.5.dp,
        animationSpec = Motion.pop(),
        label = "headRadius",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val chipWidth = 60.dp
        val chipWidthPx = with(density) { chipWidth.toPx() }
        val px = density.density

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        rippleX = offset.x
                        scope.launch {
                            ripple.snapTo(0f)
                            ripple.animateTo(1f, tween(420, easing = Motion.EmphasizedDecel))
                            rippleX = -1f
                        }
                        onSeek((offset.x / size.width * duration).toLong().coerceIn(0L, duration))
                    }
                }
                .pointerInput(duration) {
                    fun report(x: Float) {
                        dragFraction = (x / size.width).coerceIn(0f, 1f)
                        onScrub((dragFraction * duration).toLong())
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            report(offset.x)
                        },
                        onHorizontalDrag = { change, _ ->
                            report(change.position.x)
                        },
                        onDragEnd = {
                            dragging = false
                            onScrub(null)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSeek((dragFraction * duration).toLong().coerceIn(0L, duration))
                        },
                        onDragCancel = {
                            dragging = false
                            onScrub(null)
                        },
                    )
                },
        ) {
            // 线压在底部，上面留给时间胶囊
            val centerY = size.height - 12.dp.toPx()
            val stroke = lineHeight.toPx()
            val headX = size.width * fraction

            drawLine(
                color = track,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            if (fraction > 0f) {
                // 播过的一段从 62% 亮到满色：整条线有「往右走」的方向，不是一块死色
                drawLine(
                    brush = Brush.horizontalGradient(
                        0f to active.copy(alpha = 0.62f),
                        1f to active,
                        startX = 0f,
                        endX = headX.coerceAtLeast(1f),
                    ),
                    start = Offset(0f, centerY),
                    end = Offset(headX, centerY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }

            // 点击水纹：在落点炸开一圈，很快散掉
            if (rippleX >= 0f) {
                val p = ripple.value
                drawCircle(
                    color = active.copy(alpha = 0.22f * (1f - p)),
                    radius = (7f + 26f * p) * px,
                    center = Offset(rippleX, centerY),
                )
            }

            // 呼吸光晕：播放中一直在；拖动时换成更亮更大的那只
            val glowAlpha = if (dragging) 0.16f else 0.05f + 0.10f * breathe
            val glowRadius = if (dragging) 13.dp.toPx() else (8f + 5f * breathe) * px
            drawCircle(
                color = active.copy(alpha = glowAlpha),
                radius = glowRadius,
                center = Offset(headX, centerY),
            )
            drawCircle(
                color = active,
                radius = headRadius.toPx(),
                center = Offset(headX, centerY),
            )
        }

        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(tween(120)) + scaleIn(Motion.pop(), initialScale = 0.8f),
            exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.92f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset {
                    // 胶囊跟手指走，但两端收边，不会只露出半个圆角
                    val raw = fraction * widthPx - chipWidthPx / 2f
                    IntOffset(
                        x = raw.coerceIn(0f, (widthPx - chipWidthPx).coerceAtLeast(0f)).roundToInt(),
                        y = 0,
                    )
                },
        ) {
            Box(
                modifier = Modifier
                    .width(chipWidth)
                    .height(26.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(active),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatDuration((fraction * duration).toLong()),
                    color = onActive,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}
