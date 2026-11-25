package com.am.music.ui.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.data.model.Song
import com.am.music.ui.components.EmptyState
import com.am.music.ui.components.InlineLoading
import com.am.music.ui.components.SongActionsDialog
import com.am.music.ui.components.SongItem
import com.am.music.ui.components.bottomBarInset
import com.am.music.ui.components.SongListSkeleton
import com.am.music.ui.components.rememberPlayAction
import androidx.compose.foundation.layout.height

@Composable
fun FavoritesScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = viewModel { FavoritesViewModel(container) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playbackState by container.playbackManager.state.collectAsStateWithLifecycle()
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val playAction = rememberPlayAction(container) { message = it }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "我喜欢的",
                style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (uiState.songs.isNotEmpty()) {
                TextButton(
                    onClick = { playAction(uiState.songs, 0) },
                ) {
                    Text("播放全部", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        when {
            uiState.isLoading -> Column(Modifier.fillMaxSize()) {
                InlineLoading("正在整理收藏…")
                Spacer(Modifier.height(6.dp))
                SongListSkeleton(rows = 6)
            }
            uiState.songs.isEmpty() -> EmptyState(
                title = "还没有收藏",
                subtitle = "点歌右边的「⋯」→ 收藏，本地在线都行",
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomBarInset())) {
                itemsIndexed(uiState.songs, key = { _, song -> song.key }) { index, song ->
                    SongItem(
                        song = song,
                        isCurrent = playbackState.currentSong?.key == song.key,
                        onClick = { playAction(uiState.songs, index) },
                        onMoreClick = { actionSong = song },
                    )
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