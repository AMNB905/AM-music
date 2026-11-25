package com.am.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.Song

@Composable
fun SongActionsDialog(
    song: Song,
    container: AppContainer,
    onDismiss: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    viewModel: SongActionsViewModel = viewModel { SongActionsViewModel(container) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(song.key) { viewModel.open(song.key) }

    // 两个新入口（相似歌曲 / 歌曲资料）：点开是满屏纸面，不占常驻界面
    var overlay by remember(song.key) { mutableStateOf(0) }

    AmAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(Modifier.heightIn(max = 360.dp)) {
                ActionRow(
                    icon = if (state.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    label = if (state.isFavorite) "取消收藏" else "收藏",
                    tint = if (state.isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    onClick = { viewModel.toggleFavorite(song) },
                )
                if (song.platform == OnlinePlatform.NETEASE || !song.sourceId.isNullOrBlank()) {
                    ActionRow(
                        icon = Icons.Default.Search,
                        label = "相似歌曲",
                        onClick = { overlay = 1 },
                    )
                }
                ActionRow(
                    icon = Icons.Default.Info,
                    label = "歌曲资料",
                    onClick = { overlay = 2 },
                )
                if (song.isOnline) {
                    ActionRow(
                        icon = Icons.Default.Download,
                        label = "下载",
                        onClick = { overlay = 3 },
                    )
                }
                if (onRemoveFromPlaylist != null) {
                    ActionRow(
                        icon = Icons.Default.PlaylistRemove,
                        label = "从歌单移除",
                        onClick = {
                            onRemoveFromPlaylist()
                            onDismiss()
                        },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    text = "添加到歌单",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
                if (state.playlists.isEmpty()) {
                    Text(
                        text = "暂无歌单，可在「歌单」页新建",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                } else {
                    LazyColumn {
                        items(state.playlists, key = { it.id }) { playlist ->
                            val isMember = playlist.id in state.memberPlaylistIds
                            ActionRow(
                                icon = if (isMember) Icons.Default.Check else Icons.AutoMirrored.Filled.QueueMusic,
                                label = playlist.name,
                                tint = if (isMember) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                onClick = { viewModel.toggleMembership(song, playlist.id) },
                            )
                        }
                    }
                }
            }
        },
    )

    if (overlay == 1) {
        SimilarSongsDialog(song = song, container = container, onDismiss = { overlay = 0 })
    }
    if (overlay == 2) {
        SongFactsDialog(song = song, container = container, onDismiss = { overlay = 0 })
    }
    if (overlay == 3) {
        DownloadDialog(song = song, container = container, onDismiss = { overlay = 0 })
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = LocalContentColor.current,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}