package com.am.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.am.music.ui.theme.Motion
import com.am.music.ui.theme.pressScale
import com.am.music.ui.components.AnimatedIcon
import com.am.music.ui.components.animatedIconColor
import com.am.music.ui.components.IconAppear
import com.am.music.ui.components.MotionIconButton

/**
 * 会换动作的图标。
 *
 * 以前凡是「收藏 ↔ 已收藏」「加 ↔ 已加」「播放 ↔ 暂停」这类，都是把 imageVector 换成另一个，
 * 整块图标在同一帧里硬切过去——状态确实变了，但眼睛只看到闪了一下。
 * 这里统一成：新图标从 55% 弹到 100%（带过冲），旧图标缩到 82% 淡出。
 * 所有页面都用同一个手感，图标换状态这件事就有了一致的「语言」。
 */
@Composable
fun AnimatedIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    AnimatedContent(
        targetState = imageVector,
        transitionSpec = {
            (fadeIn(tween(150, delayMillis = 20)) +
                scaleIn(Motion.pop(), initialScale = 0.55f)) togetherWith
                (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.82f))
        },
        label = "animatedIcon",
    ) { vec ->
        Icon(
            imageVector = vec,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = tint,
        )
    }
}

/**
 * 按下去会缩一下的图标按钮。
 *
 * Material 的 IconButton 只有水波纹；水波纹在深色底上很淡，手指盖住的地方什么也看不见。
 * 这一层额外把整个按钮缩到 90% 再弹回来，按哪都能感到「按到了」。
 */
@Composable
fun MotionIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressTo: Float = 0.9f,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.pressScale(pressTo),
        enabled = enabled,
    ) {
        content()
    }
}

/**
 * 图标出现 / 消失。给「当前播放标记」「清空按钮」这种时有时无的图标用：
 * 出现时弹出来，消失时缩回去，而不是在布局里凭空多一格 / 少一格。
 */
@Composable
fun IconAppear(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(160)) + scaleIn(Motion.pop(), initialScale = 0.6f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.8f),
    ) {
        content()
    }
}

/** 图标颜色跟着状态渐变，而不是一帧之内从灰跳到主色。 */
@Composable
fun animatedIconColor(
    target: Color,
    durationMs: Int = 200,
): Color {
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMs),
        label = "iconTint",
    )
    return color
}
