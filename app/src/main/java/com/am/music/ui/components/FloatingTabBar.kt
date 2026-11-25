package com.am.music.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 底部浮岛里的一个格子。 */
data class FloatingTabSpec(
    val icon: ImageVector,
    val label: String,
)

/**
 * 浮岛式底部导航：一颗离底边、左右留白的胶囊，内容可以从它下面滚过去。
 *
 * 5 个格子平分宽度，图标在上文字在下；选中只靠颜色深浅区分，没有 indicator 底色、没有阴影。
 */
@Composable
fun FloatingTabBar(
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    items: List<FloatingTabSpec>,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                TabCell(onClick = { onTabSelected(index) }) {
                    TabContent(item = item, selected = index == selectedIndex)
                }
            }
        }
    }
}

@Composable
private fun TabContent(item: FloatingTabSpec, selected: Boolean) {
    // 选中的格子图标轻微放大 + 上浮，切换时是「亮起来」而不是生硬换色
    val emphasis by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(220, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)),
        label = "tabEmphasis",
    )
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(
        imageVector = item.icon,
        contentDescription = null,
        modifier = Modifier
            .size(20.dp)
            .graphicsLayer {
                val s = 0.94f + 0.06f * emphasis
                scaleX = s
                scaleY = s
                translationY = -1.5f * emphasis
            },
        tint = contentColor,
    )
    Spacer(Modifier.height(3.dp))
    Text(
        text = item.label,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
    )
}

@Composable
private fun RowScope.TabCell(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}
