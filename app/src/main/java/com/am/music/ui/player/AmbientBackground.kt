package com.am.music.ui.player

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.am.music.ui.components.rememberUriModel

/**
 * 播放页的环境底：当前封面放大铺满、重度虚化，再压一层底色渐变。
 *
 * 之前播放页是一块纯色，封面单独浮在上面，换歌除了封面和标题之外整屏没有变化。
 * 现在封面本身就是背景：换歌时整屏的颜色跟着换（900ms 交叉淡入，不是硬切），
 * 同时底图极慢地在动（14 秒一个来回，1.04↔1.16 缩放 + 十几像素的位移），
 * 静止盯着也不会觉得是张图片贴在那儿。
 *
 * 虚化在 Android 12 以下会被系统忽略，所以底色蒙版给到 0.88 起步：
 * 就算没虚化，文字和封面照样压得住。
 */
@Composable
fun PlayerAmbientBackground(
    artUri: Uri?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val model = rememberUriModel(artUri)
    val bg = MaterialTheme.colorScheme.background

    // 慢漂移：只在播放页存在，离开页面就随 composition 一起销毁，不占后台资源
    val drift = rememberInfiniteTransition(label = "ambientDrift")
    val scale by drift.animateFloat(
        initialValue = 1.04f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambientScale",
    )
    val shiftX by drift.animateFloat(
        initialValue = -12f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambientShiftX",
    )
    val shiftY by drift.animateFloat(
        initialValue = 10f,
        targetValue = -10f,
        animationSpec = infiniteRepeatable(
            animation = tween(21000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambientShiftY",
    )

    Box(modifier.fillMaxSize().background(bg)) {
        AnimatedContent(
            targetState = model,
            transitionSpec = {
                fadeIn(tween(700)) togetherWith fadeOut(tween(700))
            },
            label = "ambientArt",
        ) { art ->
            if (art == null) {
                Box(Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(art)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = shiftX
                            translationY = shiftY
                        }
                        .blur(52.dp),
                )
            }
        }

        // 底色蒙版：上浅下深，顶部还能看见一点封面的颜色，底部要让控制键的对比度稳
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to bg.copy(alpha = 0.86f),
                        0.45f to bg.copy(alpha = 0.93f),
                        1f to bg,
                    )
                ),
        )
    }
}
