package com.am.music.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 遮罩的暗度：够压住背景，又还能看出被指的控件在哪里。 */
private val SCRIM = Color(0xFF000000).copy(alpha = 0.62f)

/** 挖出来的洞比控件本身大一圈，控件不至于贴着边。 */
private val HOLE_PADDING = 8.dp

/**
 * 新手引导的遮罩层：手把手模式。
 *
 * 和上一版最大的区别是「洞是真的能点的」：
 * - 洞外的四块暗罩各自挡输入——点歪了只会让描边抖一下，不会误触底下的东西；
 * - 洞里不铺任何东西，点击直接落到底下的真实控件上（小播放器就真的进播放页、tab 就真的切页、
 *   封面就真的切歌词）；
 * - 这一步算不算过，由根页面按「App 真的变了」来判断，然后调 [onAdvance]。
 *
 * 所以本层只负责画、挡、指，不负责掐时间，也不再有「点空白处就当下一步」——
 * 那正是上一版会让用户点了小播放器却毫无反应的原因。
 */
@Composable
fun OnboardingOverlay(
    steps: List<OnboardingStep>,
    targets: OnboardingTargetsMap,
    index: Int,
    onAdvance: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (index !in steps.indices) return
    val step = steps[index]
    val density = LocalDensity.current

    val rawTarget = step.targetLabel?.let { targets[it] }
    val padded = rawTarget?.let { r ->
        val pad = with(density) { HOLE_PADDING.toPx() }
        Rect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
    }
    // 要求动手、但目标暂时不在屏上（比如还没在播歌，小播放器没浮起来）
    val waiting = step.action is OnboardingAction.Tap && padded == null && step.targetLabel != null

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

    // 点歪了的反馈：描边横向抖三下 + 提示条换成主色，比一句「请点击高亮区域」直观
    var nudgeKey by remember { mutableIntStateOf(0) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val nudge: () -> Unit = {
        nudgeKey += 1
        scope.launch {
            shake.snapTo(0f)
            repeat(2) {
                shake.animateTo(7f, tween(70))
                shake.animateTo(-7f, tween(70))
            }
            shake.animateTo(0f, tween(90))
        }
        Unit
    }
    // 换一步就把抖动复位，别把上一轮的偏移带过来
    LaunchedEffect(index) { shake.snapTo(0f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenH = constraints.maxHeight.toFloat()
        val screenW = constraints.maxWidth.toFloat()
        val hole = if (padded != null) {
            Rect(animLeft, animTop, animRight, animBottom)
        } else {
            null
        }
        // 洞在下半屏就把卡片放上半屏，反之放下半屏——卡片永远不压在它指的东西上
        val cardOnTop = hole != null && hole.center.y > screenH / 2f

        val holeL = hole?.left?.coerceAtLeast(0f) ?: 0f
        val holeT = hole?.top?.coerceAtLeast(0f) ?: 0f
        val holeR = hole?.right?.coerceAtMost(screenW) ?: 0f
        val holeB = hole?.bottom?.coerceAtMost(screenH) ?: 0f

        // 1) 暗罩：洞外四块，各自吃输入。用 Box 而不是 Canvas —— Canvas 只画不挡，
        //    上一版的暗罩挡不住点击、同时又有全屏点击层挡在洞上，才导致「点了没反应」。
        //    等条件的那一步（比如还没在播歌、小播放器没浮起来）不挡屏幕：
        //    这时候用户正需要去 App 里点一首歌，挡着就等于把人锁死，只能「跳过」。
        if (!waiting) {
            BlockingScrim(
                left = 0f, top = 0f,
                right = screenW, bottom = if (hole != null) holeT else screenH,
                onNudge = nudge,
            )
            if (hole != null) {
                BlockingScrim(0f, holeB, screenW, screenH, nudge)
                BlockingScrim(0f, holeT, holeL, holeB, nudge)
                BlockingScrim(holeR, holeT, screenW, holeB, nudge)
            }
        }

        // 2) 洞的描边：一圈主色细线 + 外侧很淡的柔光，点歪时整圈抖一下
        if (hole != null) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (hole.left + shake.value).roundToInt(),
                            hole.top.roundToInt(),
                        )
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

        // 3) 洞里的小手指：一个向下指的箭头贴在洞的上沿，一直轻轻往下点
        if (hole != null && step.action is OnboardingAction.Tap) {
            FingerPointer(
                holeTop = hole.top,
                holeBottom = hole.bottom,
                onTop = !cardOnTop,
                tint = accent,
            )
        }

        // 4) 卡片
        OnboardingCard(
            step = step,
            index = index,
            total = steps.size,
            waiting = waiting,
            nudgeKey = nudgeKey,
            onNext = onAdvance,
            onSkip = onFinish,
            modifier = Modifier
                .align(if (cardOnTop) Alignment.TopCenter else Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
                .padding(top = if (cardOnTop) 88.dp else 0.dp)
                .padding(bottom = if (cardOnTop) 0.dp else 112.dp),
        )
    }
}

/** 洞外的一块暗罩：画一层黑，并吃掉落在这里的所有手势。 */
@Composable
private fun BlockingScrim(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    onNudge: () -> Unit,
) {
    val w = (right - left).coerceAtLeast(0f)
    val h = (bottom - top).coerceAtLeast(0f)
    if (w <= 0f || h <= 0f) return
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            .background(SCRIM)
            .pointerInput(left, top, w, h) {
                detectTapGestures { onNudge() }
            },
    )
}

/**
 * 贴在被指控件上方 / 下方的小箭头：上下浮动，明确「就是点这块」。
 *
 * 位置算在洞的下沿外侧（卡片在上半屏时）或上沿外侧（卡片在下半屏时），
 * 不压在控件上，也不跟卡片打架。
 */
@Composable
private fun FingerPointer(
    holeTop: Float,
    holeBottom: Float,
    onTop: Boolean,
    tint: Color,
) {
    val bob = rememberInfiniteTransition(label = "fingerBob")
    val dy by bob.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fingerDy",
    )
    val y = if (onTop) holeTop - 30f else holeBottom + 6f
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    ((holeTop + holeBottom) / 2f).roundToInt(),
                    (y + dy).roundToInt(),
                )
            }
            .offset(x = (-12).dp)
            .size(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun OnboardingCard(
    step: OnboardingStep,
    index: Int,
    total: Int,
    waiting: Boolean,
    nudgeKey: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAction = step.action is OnboardingAction.Tap
    // 点歪了一下就把提示条点亮两秒，接下来又回到低调的样子
    var hinted by remember { mutableIntStateOf(0) }
    LaunchedEffect(nudgeKey) { if (nudgeKey > 0) hinted = nudgeKey }

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
            // 卡片自己吃掉点击：点正文不该被当成「下一步」，只有卡片上的按钮才算
            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            .animateContentSize()
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

        // 动手步骤的提示条：告诉手指往哪儿去，点歪时它会亮起来
        if (isAction && step.hint != null) {
            Spacer(Modifier.height(12.dp))
            val hot = nudgeKey > 0 && hinted == nudgeKey
            val bg by animateFloatAsState(if (waiting) 0.06f else 1f, tween(200), label = "hintBg")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = if (hot) 0.18f else 0.10f * bg,
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dot = rememberInfiniteTransition(label = "hintDot")
                val dotAlpha by dot.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "dotAlpha",
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha)),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = if (waiting) "先让这首歌播起来，这块就会出现" else step.hint!!,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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
            // 动手步骤不给「下一步」：必须真点到那块控件，App 真动了这一步才算过
            if (!isAction) {
                TextButton(onClick = onNext) {
                    Text(
                        text = if (index == total - 1) "开始使用" else "下一步",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            } else if (waiting) {
                TextButton(onClick = onNext) {
                    Text("先跳过这步", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
