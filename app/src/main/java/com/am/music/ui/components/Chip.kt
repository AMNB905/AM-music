package com.am.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.Layout
import com.am.music.ui.theme.Motion
import com.am.music.ui.theme.pressFeedback

/**
 * 设置页到处在用的选项片：选中就反色，不选中是灰底。
 *
 * 原来是 `SettingsScreen` 里的 internal 函数，定时关闭弹窗一独立出去就得跟着搬出来，
 * 索性放进 components，谁要用都能拿，样式也只有一份。
 *
 * 选中切换不再「这边一黑那边一白」：底色和字色各走 200ms 渐变，
 * 再叠一层 1 → 1.02 → 1 的轻弹，手指松开时能看出「确实切过去了」。
 */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(200, easing = Motion.EmphasizedDecel),
        label = "chipBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(200),
        label = "chipFg",
    )
    val pop by animateFloatAsState(
        targetValue = if (selected) 1.02f else 1f,
        animationSpec = Motion.pop(),
        label = "chipPop",
    )
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = contentColor,
        modifier = Modifier
            .graphicsLayer {
                scaleX = pop
                scaleY = pop
            }
            .clip(RoundedCornerShape(Layout.ArtRadius))
            .background(background)
            .clickable(onClick = onClick)
            .pressFeedback(scale = 0.96f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
