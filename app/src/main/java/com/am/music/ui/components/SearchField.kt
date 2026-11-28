package com.am.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.Layout
import com.am.music.ui.theme.pressFeedback

/**
 * 全 App 唯一的搜索框。
 *
 * 之前在线页和本地页各写了一份，连图标大小、占位文案的透明度、清空按钮的进出都逐行复制，
 * 唯一差别是「回车做什么」：在线页发请求，本地页收键盘。
 * 合并之后改样式只改这一处，也不会出现两页慢慢长得不一样。
 *
 * @param onSearch 回车 / 点右侧「搜索」时做什么。传 null 表示边输边筛（本地页），
 *   回车只是收起键盘，右侧只显示清空按钮。
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    /** 打开搜索框时由外部要焦点（歌单页展开搜索条后直接弹键盘）。 */
    focusRequester: FocusRequester? = null,
) {
    val focusManager = LocalFocusManager.current
    val submit: () -> Unit = {
        if (onSearch != null) onSearch() else focusManager.clearFocus()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Layout.Gutter)
            .clip(RoundedCornerShape(Layout.ArtRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        )
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            // 清空按钮弹出来（不是突然多一格），按下会缩
            IconAppear(visible = true) {
                MotionIconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清空",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        } else if (onSearch != null) {
            // 清空之后「搜索」两个字弹回来，和清空按钮是同一套进出
            IconAppear(visible = true) {
                Text(
                    text = "搜索",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .pressFeedback(scale = 0.96f)
                        .padding(start = 8.dp)
                        .clickable(onClick = submit),
                )
            }
        }
    }
}
