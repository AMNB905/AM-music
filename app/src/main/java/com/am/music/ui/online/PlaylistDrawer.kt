package com.am.music.ui.online

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.am.music.data.model.Song
import com.am.music.data.online.OnlinePlaylist
import com.am.music.ui.components.AlbumArt
import com.am.music.ui.components.RotatingLoadingHint
import com.am.music.ui.components.SongItem
import com.am.music.ui.components.SongListSkeleton
import com.am.music.ui.components.navigationBarBottomPadding

/**
 * 歌单抽屉（自绘浮层）。
 *
 * 原来用 `ModalBottomSheet`，它的整块拖拽手势会在列表滑到顶部时把「向下滑」抢走——
 * 你想滚回上面的歌，抽屉反而收起来了。这里把「下滑关闭」只绑在**把手**那一小条，
 * 列表里的上下滑完全归列表；关闭只有三条路：点遮罩、系统返回、拖把手。
 */
@Composable
internal fun BoxScope.PlaylistDrawer(
    playlist: OnlinePlaylist?,
    songs: List<Song>,
    loading: Boolean,
    currentKey: String?,
    onPlayAll: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 退出动画期间 playlist 已被清空，这里留一份「最后的内容」垫着，避免闪白
    var lastPlaylist by remember { mutableStateOf<OnlinePlaylist?>(null) }
    var lastSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var lastLoading by remember { mutableStateOf(false) }
    if (playlist != null) {
        lastPlaylist = playlist
        lastSongs = songs
        lastLoading = loading
    }

    val shown = playlist ?: lastPlaylist
    val open = playlist != null && shown != null
    val shownSongs = if (playlist != null) songs else lastSongs
    val shownLoading = if (playlist != null) loading else lastLoading

    BackHandler(enabled = open) { onDismiss() }

    // 遮罩：吃掉所有手势，别让下面的列表跟着滚；轻点关闭
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (change.positionChange().getDistance() > viewConfiguration.touchSlop) {
                                    moved = true
                                }
                                change.consume()
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                        if (!moved) onDismiss()
                    }
                },
        )
    }

    AnimatedVisibility(
        visible = open,
        enter = slideInVertically(
            animationSpec = tween(300, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)),
            initialOffsetY = { it },
        ) + fadeIn(tween(200)),
        exit = slideOutVertically(
            animationSpec = tween(240, easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)),
            targetOffsetY = { it },
        ) + fadeOut(tween(200)),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        val current = shown ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f),
        ) {
            Column {
                // 把手：只有这一小块认「下滑关闭」，阈值 64dp
                var dragged by remember { mutableStateOf(0f) }
                val closeThreshold = with(LocalDensity.current) { 64.dp.toPx() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { dragged = 0f },
                                onDragCancel = { dragged = 0f },
                                onDragEnd = {
                                    if (dragged > closeThreshold) onDismiss()
                                    dragged = 0f
                                },
                            ) { _, delta -> dragged += delta }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.outline),
                    )
                }

                PlaylistHeader(
                    playlist = current,
                    songCount = shownSongs.size,
                    onPlayAll = onPlayAll,
                )

                if (shownLoading) {
                    Box(Modifier.weight(1f)) {
                        Column {
                            RotatingLoadingHint(
                                messages = listOf("正在打开歌单…", "正在取全部歌曲…"),
                                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                            )
                            SongListSkeleton(rows = 7)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(
                            top = 4.dp,
                            bottom = 20.dp + navigationBarBottomPadding(),
                        ),
                    ) {
                        itemsIndexed(shownSongs, key = { _, song -> song.key }) { index, song ->
                            SongItem(
                                song = song,
                                isCurrent = song.key == currentKey,
                                onClick = { onPlayAt(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistHeader(
    playlist: OnlinePlaylist,
    songCount: Int,
    onPlayAll: () -> Unit,
) {
    Column {
        Spacer(Modifier.height(22.dp))
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArt(
                uri = playlist.coverUrl?.let { Uri.parse(it) },
                size = 84.dp,
                shape = RoundedCornerShape(2.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildList {
                        if (playlist.creator.isNotBlank()) add(playlist.creator)
                        // 头部写「歌单标注的总数」，让用户一眼能看到后端实际给了多少
                        if (playlist.trackCount > 0) add("标注 ${playlist.trackCount} 首")
                        if (songCount > 0) add("已载入 $songCount 首")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPlayAll, enabled = songCount > 0) {
                Text("播放全部", style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 24.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
