package com.am.music.ui.recent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

@Composable
fun RecentScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecentViewModel = viewModel { RecentViewModel(container) },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playbackState by container.playbackManager.state.collectAsStateWithLifecycle()
    var actionSong by remember { mutableStateOf<Song?>(null) }
    val playAction = rememberPlayAction(container)

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "最近播放",
                style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (uiState.songs.isNotEmpty()) {
                TextButton(onClick = viewModel::clear) {
                    Text("清空", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        when {
            uiState.isLoading -> Column(Modifier.fillMaxSize()) {
                InlineLoading("正在读取播放记录…")
                SongListSkeleton(rows = 5)
            }
            uiState.songs.isEmpty() -> EmptyState(
                title = "还没有播放记录",
                subtitle = "听过就出现在这，按时间倒序，不设上限",
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
