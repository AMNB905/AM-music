package com.am.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.am.music.data.model.Song
import com.am.music.ui.components.AlbumArt
import com.am.music.ui.theme.Motion
import com.am.music.ui.theme.pressScale
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 播放页的封面舞台。
 *
 * 手势分工：
 * - 左右拖：换歌，隔壁封面跟着滑进来；
 * - 单击：进歌词（歌词页用「下滑 / 轻点空白」回封面，见 [PlayerLyricsStage]）；
 * - 双击：收藏，一颗心从封面上浮起来。
 *
 * 这里只管画面和手势，播放状态与导航都由 [PlayerScreen] 决定。
 */
@Composable
internal fun PlayerCoverStage(
    song: Song,
    prevSong: Song?,
    nextSong: Song?,
    isPlaying: Boolean,
    entryProgress: Float,
    onOpenLyrics: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 封面自带一圈很淡的主色光：播放时以 3.2 秒的周期明暗呼吸，暂停就静下来。
    // 光是从封面「发出来」的，不是页面背景，所以整块画面有中心。
    val coverGlow = rememberInfiniteTransition(label = "coverGlow")
    val glowRaw by coverGlow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "coverGlowValue",
    )
    // 入场时光晕跟着封面一起亮起来，不会先出现一团光等人
    val glowAlpha = (if (isPlaying) 0.10f + 0.12f * glowRaw else 0.07f) *
        (0.2f + 0.8f * entryProgress)

    val coverScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val dragX = remember { Animatable(0f) }
    // 换歌方向：+1 下一首（从右侧进场）、-1 上一首。AnimatedContent 按它决定滑入方向
    var swapDir by remember { mutableIntStateOf(1) }
    var burstKey by remember { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val dragFraction = (dragX.value / widthPx).coerceIn(-1.2f, 1.2f)

        // 光晕垫在最底层，所有封面都压在它上面
        Box(
            modifier = Modifier
                .size(320.dp)
                .graphicsLayer { alpha = glowAlpha }
                .background(
                    Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.34f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                    ),
                    CircleShape,
                ),
        )

        // 往右拖：上一首的封面从左边滑进来（暗一点、小一点、反着斜）
        if (dragFraction > 0.01f && prevSong != null) {
            val p = dragFraction
            AlbumArt(
                uri = prevSong.albumArtUri,
                size = 236.dp,
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        translationX = -widthPx * 0.72f + p * widthPx * 0.8f
                        rotationZ = -p * 6f
                        val s = 0.84f + 0.08f * p
                        scaleX = s
                        scaleY = s
                        alpha = (p * 0.62f).coerceIn(0f, 0.5f)
                    },
            )
        }
        // 往左拖：下一首的封面从右边滑进来
        if (dragFraction < -0.01f && nextSong != null) {
            val p = -dragFraction
            AlbumArt(
                uri = nextSong.albumArtUri,
                size = 236.dp,
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        translationX = widthPx * 0.72f - p * widthPx * 0.8f
                        rotationZ = p * 6f
                        val s = 0.84f + 0.08f * p
                        scaleX = s
                        scaleY = s
                        alpha = (p * 0.62f).coerceIn(0f, 0.5f)
                    },
            )
        }

        Box(
            modifier = Modifier
                .graphicsLayer {
                    val e = entryProgress
                    // 入场：从小播放器那个尺寸（44%）长大，同时从偏左下往上抬一点
                    val entryScale = 0.44f + 0.56f * e
                    translationX = dragX.value + (1f - e) * (28.dp.toPx() * -1f)
                    translationY = (1f - e) * 76.dp.toPx()
                    rotationZ = dragFraction * 5f
                    val s = (1f - 0.05f * abs(dragFraction)) * entryScale
                    scaleX = s
                    scaleY = s
                    alpha = (1f - 0.18f * abs(dragFraction)) * (0.38f + 0.62f * e)
                }
                .pointerInput(song.key, widthPx) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val target = (dragX.value + amount)
                                .coerceIn(-widthPx * 0.9f, widthPx * 0.9f)
                            coverScope.launch { dragX.snapTo(target) }
                        },
                        onDragEnd = {
                            val p = dragX.value / widthPx
                            when {
                                p < -0.2f && nextSong != null -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    swapDir = 1
                                    onNext()
                                    coverScope.launch { dragX.animateTo(0f, Motion.travel()) }
                                }

                                p > 0.2f && prevSong != null -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    swapDir = -1
                                    onPrevious()
                                    coverScope.launch { dragX.animateTo(0f, Motion.travel()) }
                                }

                                else -> coverScope.launch {
                                    dragX.animateTo(0f, Motion.settle())
                                }
                            }
                        },
                        onDragCancel = {
                            coverScope.launch { dragX.animateTo(0f, Motion.settle()) }
                        },
                    )
                }
                .pointerInput(song.key) {
                    detectTapGestures(
                        onTap = { onOpenLyrics() },
                        onDoubleTap = {
                            onToggleFavorite()
                            burstKey += 1
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // 换歌：沿拖动方向滑进来（新封面从 92% 长到原位），旧封面反向滑出——
            // 和手势连起来看，就像一叠卡片被一张张翻过去
            AnimatedContent(
                targetState = song,
                transitionSpec = {
                    (fadeIn(tween(Motion.SWAP, delayMillis = 40)) +
                        slideInHorizontally(
                            animationSpec = tween(Motion.SWAP, easing = Motion.EmphasizedDecel),
                        ) { full -> full / 2 * swapDir } +
                        scaleIn(Motion.settle(), initialScale = 0.92f)) togetherWith
                        (fadeOut(tween(190)) +
                            slideOutHorizontally(
                                animationSpec = tween(200, easing = Motion.EmphasizedAccel),
                            ) { full -> -full / 3 * swapDir } +
                            scaleOut(tween(200), targetScale = 1.04f))
                },
                label = "coverSwap",
            ) { current ->
                AlbumArt(
                    uri = current.albumArtUri,
                    size = 260.dp,
                    shape = RoundedCornerShape(3.dp),
                    // 播放中让封面极缓推近（14 秒一个来回），静止的图也有气息
                    drift = isPlaying,
                    modifier = Modifier.pressScale(0.97f),
                )
            }

            // 收藏迸发：每双击一次播一遍，纯视觉，不拦手势
            if (burstKey > 0) {
                key(burstKey) {
                    HeartBurst(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * 双击封面时的「收藏迸发」：一颗心从封面中央浮起来并放大淡出，
 * 同时一圈很淡的光环向外散开。720ms 一次，不接手势、不占布局。
 */
@Composable
private fun HeartBurst(color: Color) {
    val t = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        t.animateTo(1f, tween(720, easing = Motion.EmphasizedDecel))
    }
    val p = t.value
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().size(260.dp)) {
            val radius = (14f + 72f * p) * density
            drawCircle(
                color = color.copy(alpha = 0.30f * (1f - p)),
                radius = radius,
                style = Stroke(width = (2.4f * (1f - p) + 0.4f) * density),
            )
        }
        Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(34.dp)
                .graphicsLayer {
                    alpha = 1f - p * p
                    translationY = -30.dp.toPx() * p
                    val s = 0.72f + 0.9f * p
                    scaleX = s
                    scaleY = s
                },
        )
    }
}
