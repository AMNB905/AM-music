package com.am.music.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.Motion
import kotlin.math.roundToInt

/** 遮罩的暗度：够压住背景，又还能看出被指的控件在哪里。 */
private val SCRIM = Color(0xFF000000).copy(alpha = 0.62f)

/** 挖出来的洞比控件本身大一圈，控件不至于贴着边。 */
private val HOLE_PADDING = 8.dp

/**
 * 新手引导的遮罩层。
 *
 * 三步之内讲完整个 App 的手势与入口：有锚点的步骤在控件位置挖洞 + 描一圈主色呼吸光，
 * 卡片自动放到洞的另一侧；讲其它页面的步骤用居中卡片，不硬指一个不存在的位置。
 * 轻点空白处等于「下一步」，随时可以「跳过」，走完 / 跳过都会落到 [onFinish]。
 */
@Composable
fun OnboardingOverlay(
    steps: List<OnboardingStep>,
    targets: Map<String, Rect>,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) return

    var index by remember { mutableIntStateOf(0) }
    val step = steps[index]
    val density = LocalDensity.current
    val rawTarget = step.targetLabel?.let { targets[it] }
    val padded = rawTarget?.let { r ->
        val pad = with(density) { HOLE_PADDING.toPx() }
        Rect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
    }

    // 洞的位置用四条边各自做弹簧：换一步时它从上一个控件「飞」到下一个，而不是瞬移
    val animLeft by animateFloatAsState(padded?.left ?: 0f, spring(dampingRatio = 0.82f, stiffness = 380f), label = "holeL")
    val animTop by animateFloatAsState(padded?.top ?: 0f, spring(dampingRatio = 0.82f, stiffness = 380f), label = "holeT")
    val animRight by animateFloatAsState(padded?.right ?: 0f, spring(dampingRatio = 0.82f, stiffness = 380f), label = "holeR")
    val animBottom by animateFloatAsState(padded?.bottom ?: 0f, spring(dampingRatio = 0.82f, stiffness = 380f), label = "holeB")

    // 洞外一圈呼吸的描边：告诉眼睛「看这块」，而不是给一个按钮
    val pulse = rememberInfiniteTransition(label = "onboardPulse")
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringAlpha",
    )
    val accent = MaterialTheme.colorScheme.primary

    val advance: () -> Unit = {
        if (index < steps.lastIndex) index += 1 else onFinish()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenH = constraints.maxHeight.toFloat()
        val hole = if (padded != null) Rect(animLeft, animTop, animRight, animBottom) else null
        // 洞在下半屏就把卡片放上半屏，反之放下半屏——卡片永远不压在它指的东西上
        val cardOnTop = hole != null && hole.center.y > screenH / 2f

        // 1) 暗罩 + 挖洞：用四块矩形拼出来，不依赖混合模式，边角干净
        Canvas(Modifier.fillMaxSize()) {
            if (hole == null) {
                drawRect(SCRIM)
            } else {
                val holeL = hole.left.coerceAtLeast(0f)
                val holeT = hole.top.coerceAtLeast(0f)
                val holeR = hole.right.coerceAtMost(size.width)
                val holeB = hole.bottom.coerceAtMost(size.height)
                // 上
                drawRect(SCRIM, topLeft = Offset.Zero, size = Size(size.width, holeT))
                // 下
                drawRect(
                    SCRIM,
                    topLeft = Offset(0f, holeB),
                    size = Size(size.width, (size.height - holeB).coerceAtLeast(0f)),
                )
                // 左
                drawRect(
                    SCRIM,
                    topLeft = Offset(0f, holeT),
                    size = Size(holeL, (holeB - holeT).coerceAtLeast(0f)),
                )
                // 右
                drawRect(
                    SCRIM,
                    topLeft = Offset(holeR, holeT),
                    size = Size((size.width - holeR).coerceAtLeast(0f), (holeB - holeT).coerceAtLeast(0f)),
                )
            }
        }

        // 2) 洞的描边：一圈主色细线 + 外侧很淡的柔光
        if (hole != null) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(hole.left.roundToInt(), hole.top.roundToInt())
                    }
                    .size(with(density) { hole.width.toDp() }, with(density) { hole.height.toDp() })
                    .clip(RoundedCornerShape(18.dp))
                    .border(
                        width = 1.5.dp,
                        color = accent.copy(alpha = ringAlpha),
                        shape = RoundedCornerShape(18.dp),
                    ),
            )
        }

        // 3) 空白处的点击：轻点就下一步，不逼用户去点按钮
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(index) { detectTapGestures { advance() } },
        )

        // 4) 卡片
        OnboardingCard(
            step = step,
            index = index,
            total = steps.size,
            onNext = advance,
            onSkip = onFinish,
            modifier = Modifier
                .align(if (cardOnTop) Alignment.TopCenter else Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
                .padding(top = if (cardOnTop) 88.dp else 0.dp)
                .padding(bottom = if (cardOnTop) 0.dp else 112.dp),
        )
    }
}

@Composable
private fun OnboardingCard(
    step: OnboardingStep,
    index: Int,
    total: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                shape = RoundedCornerShape(22.dp),
            )
            // 卡片自己吃掉点击：点正文不该被当成「下一步」，只有空白处和按钮才算
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (step.icon) {
                        OnboardingIcon.Sparkle -> Icons.Default.AutoAwesome
                        OnboardingIcon.Tabs -> Icons.Default.Apps
                        OnboardingIcon.Player -> Icons.Default.Album
                        OnboardingIcon.Lyrics -> Icons.Default.Subtitles
                        OnboardingIcon.Queue -> Icons.AutoMirrored.Filled.QueueMusic
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${index + 1}/$total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (
                    fadeIn(tween(Motion.SWAP, delayMillis = 60)) +
                        slideInVertically(tween(Motion.SWAP, easing = Motion.EmphasizedDecel)) { it / 8 }
                    ) togetherWith fadeOut(tween(140))
            },
            label = "onboardBody",
        ) { current ->
            Text(
                text = current.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 步进点：当前那颗更宽，位置一眼可读
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(total) { i ->
                    val active = i == index
                    val w by animateFloatAsState(if (active) 16f else 6f, tween(220), label = "dotW")
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(w.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                                },
                            ),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSkip) {
                Text("跳过", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onNext) {
                Text(
                    text = if (index == total - 1) "开始使用" else "下一步",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
