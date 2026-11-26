package com.am.music.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.unit.dp

/**
 * 沉浸式页面：内容从屏幕顶端开始画，用「内边距」而不是「窗口 inset」让出状态栏那一条。
 *
 * 区别在于滚动：inset 会让整块内容停在状态栏下方（栏下永远是一条空色带）；
 * 内边距则只有第一屏让位，往下滑内容就从状态栏下面穿过去——这才是全屏的观感。
 * 配合 `enableEdgeToEdge` 与透明状态栏，整页纸白/墨黑一直铺到屏幕边缘。
 */
@Composable
fun statusBarTopPadding(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/** 系统手势条 / 三键导航占的高度（底部）。 */
@Composable
fun navigationBarBottomPadding(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * 底部浮层（小播放器 + 导航栏）此刻占掉的高度。
 *
 * 这一层是**浮在内容之上**的（内容从它下面滚过去，才叫真沉浸），所以滚动容器必须自己
 * 留出这段内边距，否则最后几行会被浮层盖住、再也滑不到。高度由根布局按
 * 「有没有正在播放的歌」动态算好后放进这个 CompositionLocal，页面只管读。
 */
val LocalBottomBarInset = staticCompositionLocalOf { 0.dp }

/** 滚动内容底部要留的额外空间（不含页面自己的 24dp 留白）。 */
@Composable
fun bottomBarInset(): Dp = LocalBottomBarInset.current

/**
 * 让 Compose 弹窗（AlertDialog 等）也保持「系统小白条隐藏」。
 *
 * 弹窗有自己独立的 window，Activity 上隐藏的导航栏管不到它，弹窗一出现小白条就回来；
 * 用 `ViewCompat.getWindowInsetsController(view)` 拿**本视图所在窗口**的控制器（不需要
 * 冒任何类型转换的风险），在弹窗自己的窗口上再 hide 一次即可。
 */
@Composable
fun DialogImmersive() {
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = ViewCompat.getWindowInsetsController(view)
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.navigationBars())
        onDispose { }
    }
}
