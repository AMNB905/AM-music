package com.am.music.ui.playlists

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.am.music.ui.components.keyedRows
import com.am.music.ui.components.bottomBarInset
import com.am.music.ui.components.InlineLoading
import com.am.music.ui.components.SongActionsDialog
import com.am.music.ui.components.SongListSkeleton
import com.am.music.ui.components.rememberPlayAction
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.am.music.ui.theme.pressFeedback
import com.am.music.ui.components.EmptyState
import com.am.music.ui.components.PhaseTransition
import com.am.music.ui.components.SearchField
import com.am.music.ui.theme.Layout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

    // 歌单内搜索：搜索条「展开/收起」有动画，筛选走 120ms 防抖 + 后台线程，
    // 700 首的歌单边打边筛也不会把输入框卡住。
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(uiState.songs) }
    var computing by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // 展开那一刻才要焦点：等位移起头再弹键盘，两者不抢
    LaunchedEffect(searching) {
        if (searching) {
            delay(80)
            searchFocus.requestFocus()
        } else {
            query = ""
            computing = false
            focusManager.clearFocus()
        }
    }

    LaunchedEffect(query, uiState.songs, searching) {
        if (!searching || query.isBlank()) {
            // 没在搜 / 清空了：保持「全部曲目」这份结果，别停留在上一次的关键词
            results = uiState.songs
            computing = false
            return@LaunchedEffect
        }
        computing = true
        delay(120)
        val found = withContext(Dispatchers.Default) { filterPlaylistSongs(uiState.songs, query) }
        results = found
        computing = false
    }

    val displaySongs = if (searching) results else uiState.songs

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
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (uiState.songs.isNotEmpty()) {
                MotionIconButton(onClick = { searching = !searching }) {
                    Icon(
                        imageVector = if (searching) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (searching) "关闭歌单搜索" else "在歌单里搜索",
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            TextButton(onClick = onAddSongs) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加歌曲")
            }
        }

        // 读库 / 空 / 有歌 三态走同一套置换：骨架换列表时是「塌下去、长上来」
        val phase = when {
            uiState.isLoading -> 0
            uiState.songs.isEmpty() -> 1
            else -> 2
        }
        PhaseTransition(
            targetState = phase,
            modifier = Modifier.fillMaxSize(),
            label = "playlistDetailPhase",
        ) { stage ->
        when (stage) {
            0 -> Column(Modifier.fillMaxSize()) {
                InlineLoading("正在读取歌单…")
                SongListSkeleton(rows = 6)
            }
            1 -> EmptyState(
                title = "歌单还是空的",
                subtitle = "点右上角「添加歌曲」，从本地或在线挑几首",
            )
            else -> Column(Modifier.fillMaxSize()) {
                // 搜索条从标题栏下面「展开」：高度和透明度一起动，不是突然多出一行
                AnimatedVisibility(
                    visible = searching,
                    enter = fadeIn(tween(200)) +
                        expandVertically(
                            animationSpec = tween(Motion.ENTER, easing = Motion.EmphasizedDecel),
                            expandFrom = Alignment.Top,
                        ),
                    exit = fadeOut(tween(140)) +
                        shrinkVertically(
                            animationSpec = tween(200, easing = Motion.EmphasizedAccel),
                            shrinkTowards = Alignment.Top,
                        ),
                ) {
                    Column {
                        Spacer(Modifier.height(4.dp))
                        SearchField(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "在歌单里搜歌名、歌手、专辑",
                            onClear = { query = "" },
                            focusRequester = searchFocus,
                        )
                        // 后台正在筛：一条 1dp 细线扫过，是「真的在算」的状态，不是假转圈。
                        // 位置固定留好、只淡入淡出——不然每打一个字列表都会被顶一下。
                        val progressAlpha by animateFloatAsState(
                            targetValue = if (computing) 1f else 0f,
                            animationSpec = tween(160),
                            label = "searchProgress",
                        )
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Layout.Gutter, vertical = 5.dp)
                                .height(1.dp)
                                .alpha(progressAlpha),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.Transparent,
                        )
                    }
                }

                val countText = when {
                    !searching -> "${uiState.songs.size} 首 · 拖右边手柄排序"
                    query.isBlank() -> "${uiState.songs.size} 首 · 输入关键词开始搜"
                    computing -> "正在筛选…"
                    else -> "找到 ${displaySongs.size} 首 · 共 ${uiState.songs.size} 首"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(
                        targetState = countText,
                        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                        label = "playlistCount",
                        modifier = Modifier.weight(1f),
                    ) { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = { playAction(displaySongs, 0) },
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("全部播放")
                    }
                }
                val noResult = searching && !computing && query.isNotBlank() && displaySongs.isEmpty()
                if (noResult) {
                    EmptyState(
                        title = "没找到「$query」",
                        subtitle = "换个歌名、歌手或专辑试试",
                        modifier = Modifier.weight(1f),
                    )
                } else LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomBarInset()),
                ) {
                    val songRows = displaySongs.keyedRows { it.key }
                    itemsIndexed(songRows, key = { _, row -> row.first }) { index, row ->
                        val song = row.second
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
                                    .pressFeedback(scale = 1f, dim = 0.05f)
                                    .clickable {
                                        playAction(displaySongs, index)
                                    }
                                    .padding(start = 24.dp, end = 24.dp, top = 9.dp, bottom = 9.dp),
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
                                if (!searching) Icon(
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

