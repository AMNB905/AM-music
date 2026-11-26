package com.am.music.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.pressScale

/** 底部浮岛里的一个格子。 */
data class FloatingTabSpec(
    val icon: ImageVector,
    val label: String,
)

/**
 * 浮岛的统一外观：比页面背景深一档的填充 + 一根发丝描边 + 极轻投影。
 *
 * 之前浮岛用的是 surface，和页面背景是同一个色值（浅色 Paper / 深色 NightInk），
 * 于是胶囊和背景糊成一片，看不出边界在哪。现在改成 surfaceContainer（浅色 Fog / 深色 NightFog），
 * 再补一根 outline 描边和 3dp 投影——浅色主题下是「白纸上一块浅灰」，深色主题下是
 * 「墨黑上一块略亮」；阴影在深色下几乎看不见，边界靠描边兜住。
 */
@Composable
fun FloatingIsland(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(percent = 50),
    content: @Composable () -> Unit,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier,
        content = content,
    )
}

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
    FloatingIsland(
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
    // 选中的格子图标放大 + 上浮，切换时是「亮起来」而不是生硬换色。
    // 走弹性曲线，图标会先窜高一点再落回，像被拨了一下。
    val emphasis by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 520f),
        label = "tabEmphasis",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(200),
        label = "tabColor",
    )
    Icon(
        imageVector = item.icon,
        contentDescription = null,
        modifier = Modifier
            .size(20.dp)
            .graphicsLayer {
                val s = 0.9f + 0.1f * emphasis
                scaleX = s
                scaleY = s
                translationY = -2.5f * emphasis
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
            .pressScale(0.92f)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

