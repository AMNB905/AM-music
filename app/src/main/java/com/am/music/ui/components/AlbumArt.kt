package com.am.music.ui.components

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.am.music.ui.theme.Motion

@Composable
fun AlbumArt(
    uri: Uri?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(size / 2),
        )
        // 先查本地文件（在线看过一次就落盘），没有才让 Coil 走网络：
        // 这样离线时列表、播放页、队列里的封面一样在，不会只剩一个音符图标。
        val model = rememberUriModel(uri)
        if (model != null) {
            // 封面落位不要「啪」地贴上：200ms 淡入打底，再叠一层 0.92 → 1 的缩放，
            // 图从中间「长」到位，列表滚动时整屏都柔和一档。
            // 用 ImageRequest 而不是直接传 model，是因为 crossfade 只能在 request 上开。
            val context = LocalContext.current
            val request = remember(model) {
                ImageRequest.Builder(context)
                    .data(model)
                    .crossfade(200)
                    .build()
            }
            // 换图（换歌）时随 model 一起重置，自然又是一次落位动画
            var loaded by remember(model) { mutableStateOf(false) }
            val scale by animateFloatAsState(
                targetValue = if (loaded) 1f else 0.92f,
                animationSpec = Motion.settle(),
                label = "coverScale",
            )
            // 大封面（播放页）多转一点：0.88 的缩放入场 + 绕 Y 轴回正，像一张卡片被摆正；
            // 列表里的小图只转 6°，扫过去不至于整屏都在晃
            val flip by animateFloatAsState(
                targetValue = if (loaded) 0f else if (size >= 160.dp) -16f else -6f,
                animationSpec = Motion.settle(),
                label = "coverFlip",
            )
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        rotationY = flip
                        cameraDistance = 28.dp.toPx()
                    },
            )
        }
    }
}

