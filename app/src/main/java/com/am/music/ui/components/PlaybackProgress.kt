package com.am.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 播放进度条（极简版）。
 *
 * 不用 M3 Slider：Slider 自带水波纹、48dp 触摸区和 10dp 拖柄，和「纸白 + 墨黑」的静态排版不搭，
 * 而且它每帧重排一次 thumb，位置回调 500ms 一跳时看起来就是一顿一顿的。
 *
 * 这里是一条 2dp 细线 + 拖动时才出现的圆点：
 * - 点击任意位置直接 seek（不用先按住拖）；
 * - 横向拖动实时跟随手指，进度数字同步（通过 [onScrub] 回调，null 表示拖完）；
 * - 触摸高度 28dp，比线本身高，手指不容易点偏。
 *
 * 平滑由调用方负责：传入按帧插值后的 [progressMs]（见 [rememberSmoothPosition]）。
 */
@Composable
fun PlaybackProgress(
    progressMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Long?) -> Unit = {},
) {
    // 默认主题下 primary == onSurface（墨/白），选了主题色就跟着变色
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val duration = durationMs.coerceAtLeast(1L)
    val fraction = when {
        dragging -> dragFraction
        else -> (progressMs.toFloat() / duration).coerceIn(0f, 1f)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
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
                        report(offset.x)
                    },
                    onHorizontalDrag = { change, _ ->
                        report(change.position.x)
                    },
                    onDragEnd = {
                        dragging = false
                        onScrub(null)
                        onSeek((dragFraction * duration).toLong().coerceIn(0L, duration))
                    },
                    onDragCancel = {
                        dragging = false
                        onScrub(null)
                    },
                )
            },
    ) {
        val centerY = size.height / 2f
        val stroke = 2.dp.toPx()

        drawLine(
            color = track,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        if (fraction > 0f) {
            drawLine(
                color = active,
                start = Offset(0f, centerY),
                end = Offset(size.width * fraction, centerY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        if (dragging) {
            drawCircle(
                color = active,
                radius = 4.dp.toPx(),
                center = Offset(size.width * fraction, centerY),
            )
        }
    }
}
