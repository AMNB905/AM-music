package com.am.music.ui.player

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.am.music.data.model.LyricLine
import com.am.music.ui.components.rememberCoverModel
import com.am.music.ui.components.rememberSmoothPosition as rememberSmoothPositionShared

/** 5 行窗口、行高 72dp：字大但行数少，视觉重心始终压在当前句上。 */
private val ROW_HEIGHT = 72.dp
private const val VISIBLE_ROWS = 5

/** 比人声早一点点切行：读字有反应时间。 */
private const val LINE_LEAD_MS = 90L

/** 切行过渡时长：够顺滑，又不会让人等。 */
private const val SWITCH_MS = 320

/** 当前句的最大字号 / 最多占几行：与 TextView 的 22sp、行高 1.4、行高 72dp 对齐。 */
private const val MAX_FONT_SP = 22f
private const val MAX_LINES = 2

/** 行内左右留白，测量可用宽度时要先扣掉。 */
private val LINE_H_PADDING = 8.dp

/** 测量时留一点余量，避免「量出来刚好、渲染时却换行」。 */
private const val WIDTH_SAFETY_PX = 4

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyricsView(
    lines: List<LyricLine>,
    positionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onAlignLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
    coverUrl: String? = null,
) {
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "暂无歌词",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // 播放时按帧推进时间，不再依赖 500ms 的状态轮询
    val smoothPosition = rememberSmoothPosition(positionMs, isPlaying) + LINE_LEAD_MS
    val currentIndex = remember(lines, smoothPosition) {
        lines.indexOfLast { it.timeMs <= smoothPosition }
    }
    val listState = rememberLazyListState()

    // 切行时平滑滑到居中位置（Apple Music 那种带缓动的一段位移）
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) listState.animateScrollToItem(currentIndex)
    }

    Box(modifier) {
        // 衬底：封面高斯模糊 + 纸白蒙层，让文字有层次又不抢戏
        if (coverUrl != null) {
            val blurRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 72.dp else 0.dp
            AsyncImage(
                model = rememberCoverModel(coverUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.20f)
                    .blur(blurRadius),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        )
                    )
                ),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT * VISIBLE_ROWS)
                .align(Alignment.Center),
        ) {
            // 按「最大字号」预先量出换行位置：长句在这里就切好并写死 \n，
            // 之后字号从 17sp 变到 22sp 时段落边界不再变动，不会出现「切行时突然重排」。
            val density = LocalDensity.current
            val textWidthPx = with(density) {
                (maxWidth - LINE_H_PADDING * 2).toPx().toInt()
            } - WIDTH_SAFETY_PX

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = ROW_HEIGHT * (VISIBLE_ROWS / 2)),
            ) {
                itemsIndexed(lines) { index, line ->
                    val distance = index - currentIndex
                    val isCurrent = distance == 0
                    // 字重、字号、透明度、颜色同时过渡，切行才有"浮起来"的感觉。
                    // 字号走弹簧：放大时轻轻过冲一点再稳住，像真的被抬起来；
                    // 颜色也插值（之前是硬切，字号在动而颜色已经变完了，看着别扭）。
                    val size by animateFloatAsState(
                        targetValue = if (isCurrent) MAX_FONT_SP else 17f,
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = 320f),
                        label = "lyricSize",
                    )
                    val alpha by animateFloatAsState(
                        targetValue = when {
                            isCurrent -> 1f
                            kotlin.math.abs(distance) == 1 -> 0.46f
                            else -> 0.28f
                        },
                        animationSpec = tween(SWITCH_MS),
                        label = "lyricAlpha",
                    )
                    val weight by animateFloatAsState(
                        targetValue = if (isCurrent) 1f else 0f,
                        animationSpec = tween(SWITCH_MS),
                        label = "lyricWeight",
                    )
                    val lineColor by animateColorAsState(
                        targetValue = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(SWITCH_MS),
                        label = "lyricColor",
                    )
                    val wrapped = rememberWrappedText(line.text, textWidthPx)

                    // 只有拿到逐字时间轴才做扫光。整句时间只能说明「整行从什么时候开始」，
                    // 用它推逐字进度只是匀速猜测，歌词一旦有停顿/拖腔就会和声音错开——
                    // 宁可整行亮，也不给一个看起来对不上的逐字。
                    val sweep = if (isCurrent && line.hasWords) {
                        wordSweep(line, smoothPosition)
                    } else {
                        1f
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ROW_HEIGHT)
                            .padding(horizontal = LINE_H_PADDING)
                            .combinedClickable(
                                onClick = { onSeek(line.timeMs) },
                                onLongClick = { onAlignLine(line.timeMs) },
                            ),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().alpha(alpha)) {
                            // 底色层：当前句先给一个偏暗的底，扫光才看得出「填过去」的过程
                            Text(
                                text = wrapped,
                                fontSize = size.sp,
                                lineHeight = (size * 1.4f).sp,
                                fontWeight = if (weight > 0.6f) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrent && line.hasWords && sweep < 1f) {
                                    lineColor.copy(alpha = 0.5f)
                                } else {
                                    lineColor
                                },
                                maxLines = MAX_LINES,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (isCurrent && line.hasWords && sweep < 1f) {
                                // 亮色层：按扫光比例裁切，只露出「已经唱过」的那一段
                                Text(
                                    text = wrapped,
                                    fontSize = size.sp,
                                    lineHeight = (size * 1.4f).sp,
                                    fontWeight = if (weight > 0.6f) FontWeight.SemiBold else FontWeight.Normal,
                                    color = lineColor,
                                    maxLines = MAX_LINES,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .drawWithContent {
                                            // 这个 lambda 里外层的 `size` 是字号（Float），
                                            // 画布尺寸要用 ContentDrawScope 的 size
                                            val canvasWidth = this.size.width
                                            clipRect(right = canvasWidth * sweep) {
                                                this@drawWithContent.drawContent()
                                            }
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 按「当前句最大字号 + SemiBold」预量宽度，把长句在视觉换行处插成真换行。
 *
 * 返回的字符串最多 [MAX_LINES] 行，超出部分截断并以 `…` 收尾，
 * 于是行高固定、行数固定，切行前后不会出现字号变化带来的二次重排。
 */
@Composable
private fun rememberWrappedText(text: String, widthPx: Int): String {
    val measurer = rememberTextMeasurer()
    return remember(text, widthPx) {
        if (widthPx <= 0) return@remember text
        val layout = measurer.measure(
            text = AnnotatedString(text),
            style = TextStyle(
                fontSize = MAX_FONT_SP.sp,
                lineHeight = (MAX_FONT_SP * 1.4f).sp,
                fontWeight = FontWeight.SemiBold,
            ),
            constraints = Constraints(maxWidth = widthPx),
        )
        if (layout.lineCount <= 1 && !layout.hasVisualOverflow) return@remember text

        buildString {
            val kept = minOf(layout.lineCount, MAX_LINES)
            for (index in 0 until kept) {
                if (index > 0) append('\n')
                val start = layout.getLineStart(index)
                val end = layout.getLineEnd(index, visibleEnd = true)
                append(text.substring(start, end).trimEnd())
            }
            if (layout.lineCount > MAX_LINES || layout.hasVisualOverflow) append('…')
        }
    }
}

/**
 * 逐字扫光进度：找到此刻正在唱的那个词，把「已唱完的词」字数 +
 * 「当前词里已唱的比例 × 当前词字数」除以整行字数。
 */
private fun wordSweep(line: LyricLine, positionMs: Long): Float {
    val words = line.words
    val total = line.text.length.coerceAtLeast(1)
    var chars = 0f
    for (i in words.indices) {
        val word = words[i]
        val end = words.getOrNull(i + 1)?.timeMs ?: (word.timeMs + 500L)
        when {
            positionMs >= end -> chars += word.text.length
            positionMs <= word.timeMs -> break
            else -> {
                val span = (end - word.timeMs).coerceAtLeast(1L)
                chars += word.text.length * ((positionMs - word.timeMs).toFloat() / span)
                break
            }
        }
    }
    return (chars / total).coerceIn(0f, 1f)
}

@Composable
private fun rememberSmoothPosition(positionMs: Long, isPlaying: Boolean): Long =
    rememberSmoothPositionShared(positionMs, isPlaying)

