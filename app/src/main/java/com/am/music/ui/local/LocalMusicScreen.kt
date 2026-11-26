package com.am.music.ui.local

import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.Motion
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.data.download.DownloadsRepository
import com.am.music.data.model.Song
import com.am.music.ui.components.RotatingLoadingHint
import com.am.music.ui.components.SongActionsDialog
import com.am.music.ui.components.SongItem
import com.am.music.ui.components.SongListSkeleton
import com.am.music.ui.components.bottomBarInset
import com.am.music.ui.components.statusBarTopPadding
import com.am.music.util.formatBytes

@Composable
fun LocalMusicScreen(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    container: AppContainer,
    modifier: Modifier = Modifier,
    viewModel: LocalMusicViewModel = viewModel { LocalMusicViewModel(container) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playbackState by container.playbackManager.state.collectAsStateWithLifecycle()
    var actionSong by remember { mutableStateOf<Song?>(null) }

    LaunchedEffect(hasPermission) {
        if (hasPermission) viewModel.load()
    }

    val hasDownloads = uiState.downloads.isNotEmpty()
    // 「播放全部」= 已下载 + 本机音乐；下标要跨过前一段，否则点到的是隔壁的歌
    val visible = uiState.downloads + uiState.songs

    when {
        // 没有任何可显示的曲目时，才把权限当障碍；已下载的歌不需要任何权限就能放
        !hasPermission && !hasDownloads -> PermissionPlaceholder(onRequestPermission, modifier)
        // 扫库期间先给骨架 + 一句「在扫什么」，不转圈、不假装卡住
        uiState.isLoading && !hasDownloads -> Column(
            modifier = modifier
                .fillMaxSize()
                .padding(top = statusBarTopPadding() + 22.dp),
        ) {
            RotatingLoadingHint(
                messages = listOf("正在扫描本机音乐…", "正在读取时长与封面…", "马上就好"),
            )
            Spacer(Modifier.height(18.dp))
            SongListSkeleton(rows = 7)
        }

        uiState.songs.isEmpty() && !hasDownloads -> EmptyPlaceholder(modifier)

        // 头部作为列表第一项：往下滑标题跟着走，歌曲从状态栏下面穿过（全局沉浸）
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = statusBarTopPadding(), bottom = 24.dp + bottomBarInset()),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "本地",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (hasDownloads) {
                                "${uiState.downloads.size} 首已下载 · ${formatBytes(uiState.downloadBytes)}"
                            } else {
                                "${uiState.songs.size} 首"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (visible.isNotEmpty()) {
                        TextButton(
                            onClick = { container.playbackManager.playSongs(visible, 0) },
                        ) {
                            Text("播放全部", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    IconButton(onClick = viewModel::load, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "刷新",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }

            if (hasDownloads) {
                item(key = "section-downloads") {
                    SectionTitle(
                        title = "已下载",
                        meta = "${uiState.downloads.size} 首 · ${formatBytes(uiState.downloadBytes)}",
                    )
                }
                itemsIndexed(uiState.downloads, key = { _, song -> "dl:${song.key}" }) { index, song ->
                    SongItem(
                        song = song,
                        isCurrent = playbackState.currentSong?.key == song.key,
                        modifier = Modifier.animateItem(placementSpec = Motion.place(), fadeInSpec = tween(280), fadeOutSpec = tween(170)),
                        onClick = { container.playbackManager.playSongs(visible, index) },
                        onMoreClick = { actionSong = song },
                        badge = DownloadsRepository.qualityLabel(song.downloadedQuality ?: ""),
                    )
                }
            }

            if (uiState.isLoading && hasDownloads) {
                item(key = "section-scanning") {
                    RotatingLoadingHint(messages = listOf("正在扫描本机音乐…"))
                }
            }

            if (uiState.songs.isNotEmpty()) {
                item(key = "section-local") {
                    SectionTitle(title = "本机音乐", meta = "${uiState.songs.size} 首")
                }
                itemsIndexed(uiState.songs, key = { _, song -> "local:${song.key}" }) { index, song ->
                    SongItem(
                        song = song,
                        isCurrent = playbackState.currentSong?.key == song.key,
                        modifier = Modifier.animateItem(placementSpec = Motion.place(), fadeInSpec = tween(280), fadeOutSpec = tween(170)),
                        onClick = {
                            container.playbackManager.playSongs(visible, uiState.downloads.size + index)
                        },
                        onMoreClick = { actionSong = song },
                    )
                }
            }

            if (!hasPermission) {
                item(key = "permission-hint") {
                    PermissionHintRow(onRequestPermission)
                }
            }
        }
    }

    actionSong?.let { song ->
        SongActionsDialog(
            song = song,
            container = container,
            onDismiss = { actionSong = null },
        )
    }
}

@Composable
private fun PermissionPlaceholder(onRequestPermission: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("要音频权限", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "给权限才能扫手机里的歌",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequestPermission) {
            Text("授予权限")
        }
    }
}

@Composable
private fun EmptyPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("未找到本地音乐", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "把歌放进手机，再点右上角刷新",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 小节标题：一条 0.5dp 线 + 左标题右计数，和整站的「列表分段」语言一致。 */
@Composable
private fun SectionTitle(title: String, meta: String) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 14.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 没给权限时也不挡着已下载的歌：只在列表尾部留一行，点一下就去要权限。 */
@Composable
private fun PermissionHintRow(onRequestPermission: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 14.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRequestPermission)
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "扫手机里的歌，先给音频权限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text("授予", style = MaterialTheme.typography.labelSmall)
        }
    }
}



