package com.am.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.Layout
import com.am.music.ui.theme.pressFeedback

/**
 * 设置页到处在用的选项片：选中就反色，不选中是灰底。
 *
 * 原来是 `SettingsScreen` 里的 internal 函数，定时关闭弹窗一独立出去就得跟着搬出来，
 * 索性放进 components，谁要用都能拿，样式也只有一份。
 */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .clip(RoundedCornerShape(Layout.ArtRadius))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .clickable(onClick = onClick)
            .pressFeedback(scale = 0.96f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
