package com.am.music.ui.playlists

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.ui.components.AnimatedIcon
import com.am.music.ui.components.MotionIconButton
import com.am.music.ui.components.animatedIconColor
import com.am.music.ui.components.AlbumArt
import com.am.music.ui.components.bottomBarInset
import com.am.music.ui.components.InlineLoading
import com.am.music.ui.components.SongListSkeleton

@Composable
fun AddSongsScreen(
    container: AppContainer,
    playlistId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddSongsViewModel = viewModel(key = "add-songs-$playlistId") {
        AddSongsViewModel(container, playlistId)
    },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MotionIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "添加歌曲（已添加 ${uiState.addedIds.size} 首）",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (uiState.isLoading) {
            InlineLoading("正在准备可添加的歌曲…")
            SongListSkeleton(rows = 6)
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomBarInset())) {
            items(uiState.songs, key = { it.id }) { song ->
                val added = song.id in uiState.addedIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggle(song) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArt(uri = song.albumArtUri, size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodyLarge,
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
                    // 加没加进去：图标弹出切换 + 颜色渐变，一眼能确认点到了
                    AnimatedIcon(
                        imageVector = if (added) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline,
                        contentDescription = if (added) "已添加" else "添加",
                        tint = animatedIconColor(
                            target = if (added) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

