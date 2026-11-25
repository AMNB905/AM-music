package com.am.music.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 全 App 统一弹窗。
 *
 * 之前直接用 Material3 的 `AlertDialog` 默认值：28dp 大圆角、surfaceContainerHigh 的灰底、
 * 带 tonal elevation —— 和页面的「纸白 / 墨黑 / 2dp 圆角 / 0.5dp 分隔线 / 无阴影」完全是两套语言，
 * 所以弹窗一出现就像另一个 App。
 *
 * 这里只把它拧回页面那套：2dp 圆角、surface 底、零 elevation，并把沉浸式（隐藏系统小白条）
 * 一并处理掉，调用方不用再各自 `DialogImmersive()`。
 */
@Composable
fun AmAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        title = title,
        text = {
            // 弹窗有自己的 window，Activity 上隐藏的导航栏管不到它；
            // 放在 text 这一层，所有弹窗（无论有没有标题）都会执行到。
            DialogImmersive()
            text?.invoke()
        },
        dismissButton = dismissButton,
        shape = RoundedCornerShape(2.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    )
}
