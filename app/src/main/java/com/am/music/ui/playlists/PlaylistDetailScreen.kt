package com.am.music.ui.playlists

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.am.music.ui.theme.Motion
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.data.model.Song
import com.am.music.ui.components.MotionIconButton
import com.am.music.ui.components.AlbumArt
import com.am.music.ui.components.bottomBarInset
import com.am.music.ui.components.InlineLoading
import com.am.music.ui.components.SongActionsDialog
import com.am.music.ui.components.SongListSkeleton
import com.am.music.ui.components.rememberPlayAction
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistDetailScreen(
    container: AppContainer,
    playlistId: Long,
    onBack: () -> Unit,
    onAddSongs: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistDetailViewModel = viewModel(key = "playlist-$playlistId") {
        PlaylistDetailViewModel(container, playlistId)
    },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playbackState by container.playbackManager.state.collectAsStateWithLifecycle()
    var actionSong by remember { mutableStateOf<Song?>(null) }
    val playAction = rememberPlayAction(container)

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        viewModel.moveSong(from.index, to.index)
    }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MotionIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = uiState.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAddSongs) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加歌曲")
            }
        }

        when {
            uiState.isLoading -> Column(Modifier.fillMaxSize()) {
                InlineLoading("正在读取歌单…")
                SongListSkeleton(rows = 6)
            }
            uiState.songs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "还空着，点「添加歌曲」",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${uiState.songs.size} 首 · 拖右边手柄排序",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { playAction(uiState.songs, 0) },
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("全部播放")
                    }
                }
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomBarInset()),
                ) {
                    itemsIndexed(uiState.songs, key = { _, song -> song.key }) { index, song ->
                        ReorderableItem(
                            reorderableState,
                            key = song.key,
                            modifier = Modifier.animateItem(placementSpec = Motion.place(), fadeInSpec = tween(280), fadeOutSpec = tween(170)),
                        ) { isDragging ->
                            val interactionSource = remember { MutableInteractionSource() }
                            var wasDragging by remember { mutableStateOf(false) }
                            LaunchedEffect(isDragging) {
                                if (wasDragging && !isDragging) viewModel.commitOrder()
                                wasDragging = isDragging
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isDragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                                    )
                                    .clickable {
                                        playAction(uiState.songs, index)
                                    }
                                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AlbumArt(uri = song.albumArtUri, size = 48.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (playbackState.currentSong?.key == song.key) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = song.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                MotionIconButton(onClick = { actionSong = song }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "更多操作",
                                        tint = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.DragHandle,
                                    contentDescription = "拖动排序",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .size(24.dp)
                                        .draggableHandle(interactionSource = interactionSource),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    actionSong?.let { song ->
        SongActionsDialog(
            song = song,
            container = container,
            onDismiss = { actionSong = null },
            onRemoveFromPlaylist = { viewModel.removeSong(song.key) },
        )
    }
}

