package com.am.music.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.Motion
import com.am.music.ui.theme.pressFeedback
import kotlin.math.roundToInt

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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            // 选中格背后那枚会滑动的光片：换 tab 时它从一格滑到另一格（弹簧，带一点过冲），
            // 不再是「这边暗下去、那边亮起来」两个互不相干的局部变化
            TabGlowIndicator(selectedIndex = selectedIndex, count = items.size)

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
}

/**
 * 滑动光片：位置走数值动画（不是每格各自淡入），所以中间的格子是被「路过」的。
 * 光片很淡（主色 9%），只在深色底上做一层「气」，不喧宾夺主。
 */
@Composable
private fun TabGlowIndicator(selectedIndex: Int, count: Int) {
    if (count <= 0) return
    val position by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = Motion.travel(),
        label = "tabGlowPos",
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        val cellWidth = maxWidth / count
        val density = LocalDensity.current
        val pillWidth = cellWidth - 8.dp
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = with(density) { (cellWidth * position + 4.dp).roundToPx() },
                        y = 0,
                    )
                }
                .width(pillWidth.coerceAtLeast(0.dp))
                .height(52.dp)
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)),
        )
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
    // 点 tab 不要水波纹：这个浮岛里已经有滑动光片 + 按压缩放两重反馈，
    // 再叠一层圆形波纹会显得脏。这里把 indication 置空，点击照常生效。
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(percent = 50))
            // 切 tab 给一下轻震：底部这条是全局入口，按下去该「有回应」
            .pressFeedback(scale = 0.92f, haptic = true)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

