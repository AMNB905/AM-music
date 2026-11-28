package com.am.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.am.music.ui.theme.Motion

/**
 * 同一块区域的「状态置换」：加载中 / 空 / 有内容之间不再硬切。
 *
 * 之前每个页面各写一个 `Crossfade` 或者干脆 `when`（硬切）：
 * 骨架换成列表时高度从 0 一张到满屏，滚动位置跟着抖一下。
 * 这里统一成一套——新内容淡入 + 从下方推 1/14 屏，旧内容更快收走，
 * 高度变化交给 `Motion.settle()` 收尾，列表是「长」出来的而不是「弹」出来的。
 */
@Composable
fun PhaseTransition(
    targetState: Any?,
    modifier: Modifier = Modifier,
    label: String = "phase",
    content: @Composable (Any?) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            (
                fadeIn(tween(Motion.SWAP, delayMillis = 26)) +
                    slideInVertically(tween(Motion.ENTER, easing = Motion.EmphasizedDecel)) { it / 14 }
                ) togetherWith
                fadeOut(tween(Motion.EXIT - 50, easing = Motion.EmphasizedAccel)) using
                SizeTransform(clip = false) { _, _ -> Motion.settle() }
        },
        contentKey = { it },
        label = label,
    ) { state ->
        content(state)
    }
}

/**
 * 图片落位：解码完再淡上来，并配一层极轻的「从 0.97 长到位」。
 *
 * 直接 `AsyncImage` 的图是「啪」地贴满的——尤其弱网下先空一块、再瞬间填满。
 * 全 App 的封面 / 头像 / 相册缩略图都走这一个，落位手感只有一份。
 */
@Composable
fun FadingImage(
    model: Any?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = 1f,
) {
    if (model == null) return
    var loaded by remember(model) { mutableStateOf(false) }
    val appear by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = tween(300, easing = Motion.EmphasizedDecel),
        label = "imageAppear",
    )
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = contentScale,
        onSuccess = { loaded = true },
        modifier = modifier.graphicsLayer {
            this.alpha = appear * alpha
            val s = 0.97f + 0.03f * appear
            scaleX = s
            scaleY = s
        },
    )
}
