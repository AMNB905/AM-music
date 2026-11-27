package com.am.music.ui.playlistcover

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.am.music.data.playlist.PlaylistCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.am.music.ui.theme.pressFeedback

/** 选择器里的一格：相册图片或视频。 */
private data class MediaEntry(val uri: Uri, val isVideo: Boolean)

/**
 * 自建媒体选择器。
 *
 * 不用系统相册选择器（`PickVisualMedia`）的原因：它只能给「一张图」，
 * 拿不到视频，也没法把「视频抽一帧当封面」这件事做得像同一个流程。
 * 这里用 MediaStore 自己列：图片一栏、视频一栏，选了就进 [CoverCropDialog] 裁成方版。
 *
 * 权限：Android 13+ 是 READ_MEDIA_IMAGES / READ_MEDIA_VIDEO，12 及以下是 READ_EXTERNAL_STORAGE。
 */
@Composable
fun MediaPickerDialog(
    onDismiss: () -> Unit,
    onPick: (Uri, isVideo: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) } // 0 图片 / 1 视频
    var granted by remember { mutableStateOf(hasMediaPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> granted = result.values.any { it } || hasMediaPermission(context) }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(mediaPermissions())
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 28.dp),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "选择封面",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("取消") }
                }

                TabRow(
                    selectedTabIndex = tab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("图片") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("视频") })
                }

                when {
                    !granted -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "读不到相册。需要「照片和视频」权限才能列出素材。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = { permissionLauncher.launch(mediaPermissions()) }) {
                            Text("去授权")
                        }
                    }

                    else -> MediaGrid(
                        isVideo = tab == 1,
                        onPick = { entry -> onPick(entry.uri, entry.isVideo) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaGrid(isVideo: Boolean, onPick: (MediaEntry) -> Unit) {
    val context = LocalContext.current
    val entries by produceState(initialValue = emptyList<MediaEntry>(), isVideo) {
        value = withContext(Dispatchers.IO) { queryMedia(context, isVideo) }
    }

    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (isVideo) "相册里没有视频" else "相册里没有图片",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(entries, key = { it.uri.toString() }) { entry ->
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pressFeedback(scale = 0.95f)
                    .clickable { onPick(entry) },
            ) {
                if (entry.isVideo) {
                    val thumb by produceState<Bitmap?>(initialValue = null, entry.uri) {
                        value = withContext(Dispatchers.IO) {
                            PlaylistCovers.videoThumb(context, entry.uri)
                        }
                    }
                    thumb?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(14.dp),
                    )
                } else {
                    AsyncImage(
                        model = entry.uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            // 只列最近 400 条：相册动辄几千张，全列出来既慢也没人翻到底
            Text(
                text = "只列最近的素材，找不到就先用相册裁好再回来",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}

/**
 * 裁剪：把选中的图/视频帧裁成 1:1 方版。
 *
 * 视口本身就是一个正方形，图按「短边铺满」放进来（16:9 的视频会左右溢出），
 * 单指拖动、双指缩放，确认时按视口对应的原图区域 `Bitmap.createBitmap` 出方版。
 * 这样不管素材是竖图、横图、还是视频帧，最终封面都是同一个比例，海报墙不会被撑歪。
 */
@Composable
fun CoverCropDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    val image = remember(bitmap) { bitmap.asImageBitmap() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "裁剪封面（1:1）",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "双指缩放、单指拖动，框里的就是封面",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                )

                CropViewport(
                    image = image,
                    scale = scale,
                    panX = panX,
                    panY = panY,
                    onSize = { viewport = it },
                    onGesture = { zoom, pan ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        panX += pan.x
                        panY += pan.y
                        val clamped = clampPan(
                            viewport = viewport,
                            imageW = bitmap.width,
                            imageH = bitmap.height,
                            scale = scale,
                            panX = panX,
                            panY = panY,
                        )
                        panX = clamped.x
                        panY = clamped.y
                    },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            scale = 1f
                            panX = 0f
                            panY = 0f
                        },
                    ) { Text("重设") }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = {
                            if (viewport == IntSize.Zero) {
                                onDismiss()
                                return@TextButton
                            }
                            val vw = viewport.width.toFloat()
                            val draw = drawScale(vw, bitmap.width, bitmap.height, scale)
                            // 视口边长 ÷ 每原图像素的显示尺寸 = 落在原图上的裁剪边长
                            val side = vw / draw
                            // 视口左/上边在显示坐标里是 imgLeft（可能为负），换回原图像素再取反
                            val left = -panXOffset(vw, bitmap.width, bitmap.height, scale, panX) / draw
                            val top = -panYOffset(viewport.height.toFloat(), bitmap.width, bitmap.height, scale, panY) / draw
                            val cropSide = side.toInt().coerceAtLeast(1)
                            val x = left.toInt().coerceIn(0, (bitmap.width - cropSide).coerceAtLeast(0))
                            val y = top.toInt().coerceIn(0, (bitmap.height - cropSide).coerceAtLeast(0))
                            val safeSide = cropSide.coerceAtMost(minOf(bitmap.width - x, bitmap.height - y))
                            val cropped = Bitmap.createBitmap(bitmap, x, y, safeSide, safeSide)
                            onConfirm(cropped)
                        },
                    ) { Text("确定") }
                }
            }
        }
    }
}

@Composable
private fun CropViewport(
    image: ImageBitmap,
    scale: Float,
    panX: Float,
    panY: Float,
    onSize: (IntSize) -> Unit,
    onGesture: (zoom: Float, pan: Offset) -> Unit,
) {
    val imageW = image.width
    val imageH = image.height

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .onSizeChanged(onSize)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ -> onGesture(zoom, pan) }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val draw = drawScale(size.width, imageW, imageH, scale)
            val drawW = imageW * draw
            val drawH = imageH * draw
            val left = (size.width - drawW) / 2f + panX
            val top = (size.height - drawH) / 2f + panY
            clipRect(0f, 0f, size.width, size.height) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset(left.toInt(), top.toInt()),
                    dstSize = IntSize(drawW.toInt(), drawH.toInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}

/** 每张原图像素在屏幕上占多少像素：短边铺满视口，再乘用户缩放。 */
private fun drawScale(viewport: Float, imageW: Int, imageH: Int, scale: Float): Float =
    (viewport / minOf(imageW, imageH)) * scale

/** 视口左/上边在原图中的坐标（负值被夹到 0 之外时由调用方 coerce）。 */
private fun panXOffset(viewport: Float, imageW: Int, imageH: Int, scale: Float, panX: Float): Float {
    val draw = drawScale(viewport, imageW, imageH, scale)
    val drawW = imageW * draw
    return (viewport - drawW) / 2f + panX
}

private fun panYOffset(viewport: Float, imageW: Int, imageH: Int, scale: Float, panY: Float): Float {
    val draw = drawScale(viewport, imageW, imageH, scale)
    val drawH = imageH * draw
    return (viewport - drawH) / 2f + panY
}

/** 拖动不能把图拖出方框：边缘顶到视口边就停。 */
private fun clampPan(
    viewport: IntSize,
    imageW: Int,
    imageH: Int,
    scale: Float,
    panX: Float,
    panY: Float,
): Offset {
    if (viewport.width == 0) return Offset(panX, panY)
    val vw = viewport.width.toFloat()
    val draw = drawScale(vw, imageW, imageH, scale)
    val drawW = imageW * draw
    val drawH = imageH * draw
    val maxX = ((drawW - vw) / 2f).coerceAtLeast(0f)
    val maxY = ((drawH - vw) / 2f).coerceAtLeast(0f)
    return Offset(panX.coerceIn(-maxX, maxX), panY.coerceIn(-maxY, maxY))
}

private fun hasMediaPermission(context: Context): Boolean {
    val perms = mediaPermissions()
    return perms.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/** 只查最近 400 条；按添加时间倒序，和相册的观感一致。 */
private fun queryMedia(context: Context, isVideo: Boolean): List<MediaEntry> {
    val collection = if (isVideo) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val idColumn = if (isVideo) MediaStore.Video.Media._ID else MediaStore.Images.Media._ID
    val dateColumn = if (isVideo) {
        MediaStore.Video.Media.DATE_ADDED
    } else {
        MediaStore.Images.Media.DATE_ADDED
    }

    return runCatching {
        context.contentResolver.query(
            collection,
            arrayOf(idColumn),
            null,
            null,
            "$dateColumn DESC",
        )?.use { cursor ->
            val ids = mutableListOf<Uri>()
            while (cursor.moveToNext() && ids.size < 400) {
                ids += Uri.withAppendedPath(collection, cursor.getString(0))
            }
            ids
        }?.map { MediaEntry(it, isVideo) }
    }.getOrNull().orEmpty()
}
