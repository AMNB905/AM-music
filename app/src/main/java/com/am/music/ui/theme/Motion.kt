package com.am.music.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.spring

/**
 * 全 App 的动效规格。
 *
 * 之前每个页面各写各的 tween：有的 220ms，有的 260ms，曲线也各挑各的。
 * 单独看都不错，串起来用就有点「每个部件一个性格」。
 * 集中到这里之后，进 / 出、位移、弹性三件事只有一套口径，
 * 想要整体再猛一点或再收一点，改一个常量就够了。
 */
object Motion {

    /** 进场：快起步、慢收尾，物体像被推了一下后自己稳住。 */
    val EmphasizedDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 退场：慢慢起步、快速收走，不抢下一帧的注意力。 */
    val EmphasizedAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val ENTER = 320
    const val EXIT = 200
    const val SWAP = 260

    /** 会「过冲一点点再回来」的弹性：给图标、小元素用，点下去有手感。 */
    fun <T> pop() = spring<T>(dampingRatio = 0.42f, stiffness = 620f)

    /** 稳稳落位的弹性：给大面积元素用，过了会晕。 */
    fun <T> settle() = spring<T>(dampingRatio = 0.78f, stiffness = 360f)

    /** 列表重排：行 / 格子互相让位时用它，滑过去而不是瞬移。 */
    fun <T> place() = spring<T>(dampingRatio = 0.82f, stiffness = 300f)

    /** 高度变化（浮岛长高 / 缩回）：从底部顶出来，落定时不回弹。 */
    fun <T> grow() = spring<T>(dampingRatio = 0.9f, stiffness = 420f)

    /** 屏幕整体切换：横向位移用弹簧收尾，比 tween 更「活」。 */
    fun <T> travel() = spring<T>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
}

/**
 * 按下去缩一点、松手弹回来的手感。
 *
 * 不去碰 clickable：这里只观察按下 / 抬起，不消费事件，
 * 所以外面照旧挂 clickable、长按、拖动都不会被抢。
 */
@Composable
fun Modifier.pressScale(pressedScale: Float = 0.955f): Modifier =
    pressFeedback(scale = pressedScale)

/**
 * 全 App 统一的「按一下」反馈：缩放 + 可选的一层淡色 + 可选的一下轻震。
 *
 * 为什么要合成一个：列表行缩放会把文字带着抖，只适合叠色；图标按钮只适合缩放；
 * 播放 / 识曲这种「按下去就该知道按到了」的还要再加一下震动。
 * 三者原来各写各的，串起来有的地方有、有的地方没有，同一个 App 里手感不成套。
 *
 * 三个参数就是三种档位：
 * - 行 / 卡片：`pressFeedback(scale = 1f, dim = 0.06f)` —— 整行泛一层色，文字不动；
 * - 图标 / 小按钮：`pressFeedback(scale = 0.9f)`；
 * - 主操作：`pressFeedback(scale = 0.94f, haptic = true)`。
 *
 * 只观察手势，一帧都不消费，clickable / 长按 / 拖动照常工作。
 */
@Composable
fun Modifier.pressFeedback(
    scale: Float = 0.955f,
    dim: Float = 0f,
    dimColor: Color = Color.White,
    haptic: Boolean = false,
): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scaleValue by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = Motion.pop(),
        label = "pressScale",
    )
    val dimValue by animateFloatAsState(
        targetValue = if (pressed) dim else 0f,
        animationSpec = tween(140),
        label = "pressDim",
    )
    val haptics = LocalHapticFeedback.current

    return this
        .graphicsLayer {
            scaleX = scaleValue
            scaleY = scaleValue
        }
        .drawBehind {
            if (dimValue > 0f) {
                drawRect(dimColor.copy(alpha = dimValue))
            }
        }
        .pointerInput(haptic) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                // 主操作按下即震：轻一下，不抢系统返回 / 长按那套震动
                if (haptic) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.none { it.pressed }) break
                }
                pressed = false
            }
        }
}


