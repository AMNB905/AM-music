package com.am.music.ui.player

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.data.model.Song
import com.am.music.playback.PlayMode
import com.am.music.ui.components.AlbumArt
import com.am.music.ui.components.PlaybackProgress
import com.am.music.ui.components.RotatingLoadingHint
import com.am.music.ui.components.SkeletonBlock
import com.am.music.ui.components.rememberSmoothPosition
import com.am.music.util.formatDuration
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = viewModel { PlayerViewModel(container) },
) {
    val state by viewModel.playbackState.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val song = state.currentSong

    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var clearScreen by rememberSaveable { mutableStateOf(false) }
    // 清屏模式下系统返回键先退出清屏，不要直接把人踢出播放页
    BackHandler(enabled = clearScreen) { clearScreen = false }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importLyrics(uri)
    }

    if (song != null && clearScreen) {
        ClearScreenPlayer(
            song = song,
            showLyrics = showLyrics,
            lyricsState = lyricsState,
            positionMs = state.positionMs,
            isPlaying = state.isPlaying,
            onSeek = viewModel::seekTo,
            onAlignLine = viewModel::alignLyricLine,
            onExit = { clearScreen = false },
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // 播放页隐藏了系统栏，状态栏 inset 会变 0；挖孔屏的 cutout 仍然存在，单独让位
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 28.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (song != null) {
                IconButton(
                    onClick = { clearScreen = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "清屏模式",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
                TextButton(onClick = { showLyrics = !showLyrics }) {
                    Text(
                        text = if (showLyrics) "封面" else "歌词",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::toggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "取消收藏" else "收藏",
                        modifier = Modifier.size(18.dp),
                        tint = if (isFavorite) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        if (song == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无播放中的歌曲",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 封面 ↔ 歌词：不是硬切。旧的淡出同时新的从 96% 放大到位，
            // 像同一块屏在换内容，而不是换了一页。
            AnimatedContent(
                targetState = showLyrics,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 40)) +
                        scaleIn(
                            animationSpec = tween(260, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)),
                            initialScale = 0.96f,
                        )) togetherWith fadeOut(tween(150))
                },
                label = "coverLyrics",
            ) { lyricsMode ->
            if (lyricsMode) {
                when (val lyrics = lyricsState) {
                    LyricsUiState.Loading -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // 歌词占位：三行长短不一的骨架，像正在成形的歌词，不像空转的圈
                        listOf(0.62f, 0.44f, 0.74f, 0.36f, 0.56f).forEach { fraction ->
                            SkeletonBlock(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(12.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        RotatingLoadingHint(
                            messages = listOf("正在取歌词…", "正在对齐时间轴…", "马上就好"),
                            modifier = Modifier.padding(horizontal = 0.dp),
                        )
                    }

                    LyricsUiState.NotFound -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "暂无歌词",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                            Text("导入 LRC 文件", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    is LyricsUiState.Ready -> LyricsView(
                        lines = lyrics.lines,
                        positionMs = state.positionMs,
                        isPlaying = state.isPlaying,
                        onSeek = viewModel::seekTo,
                        onAlignLine = viewModel::alignLyricLine,
                        coverUrl = song.coverUrl ?: song.albumArtUri?.toString(),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                AlbumArt(
                    uri = song.albumArtUri,
                    size = 260.dp,
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier.clickable { showLyrics = true },
                )
            }
            }
        }

        // 换歌时标题和副标题也是淡入淡出，而不是原地换字
        AnimatedContent(
            targetState = song,
            transitionSpec = {
                (fadeIn(tween(240, delayMillis = 60)) + slideInVertically { it / 12 }) togetherWith
                    fadeOut(tween(140))
            },
            label = "songMeta",
        ) { current ->
            Column {
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOf(current.artist, current.album)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 进度：按帧插值后的位置喂给细线进度条，拖动时才出现圆点；数字跟着手指走。
        val smoothPosition = rememberSmoothPosition(state.positionMs, state.isPlaying)
        var scrubMs by remember { mutableStateOf<Long?>(null) }
        PlaybackProgress(
            progressMs = scrubMs ?: smoothPosition,
            durationMs = state.durationMs,
            onSeek = viewModel::seekTo,
            onScrub = { scrubMs = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = formatDuration(scrubMs ?: smoothPosition),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatDuration(state.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::cyclePlayMode, modifier = Modifier.size(40.dp)) {
                AnimatedContent(
                    targetState = state.playMode,
                    transitionSpec = {
                        (fadeIn(tween(180, delayMillis = 30)) + scaleIn(tween(200), initialScale = 0.88f)) togetherWith
                            fadeOut(tween(120))
                    },
                    label = "playMode",
                ) { mode ->
                    Icon(
                        imageVector = when (mode) {
                            PlayMode.LIST_LOOP -> Icons.Default.Repeat
                            PlayMode.SINGLE_LOOP -> Icons.Default.RepeatOne
                            PlayMode.SHUFFLE -> Icons.Default.Shuffle
                        },
                        contentDescription = mode.label,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            IconButton(onClick = viewModel::previous, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "上一首",
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(64.dp)) {
                // 播放 / 暂停换图标：淡出淡入 + 轻微放大，点下去有「按到了」的回馈
                AnimatedContent(
                    targetState = state.isPlaying,
                    transitionSpec = {
                        (fadeIn(tween(160, delayMillis = 40)) + scaleIn(tween(180), initialScale = 0.8f)) togetherWith
                            fadeOut(tween(110))
                    },
                    label = "playPause",
                ) { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = viewModel::next, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "下一首",
                    modifier = Modifier.size(24.dp),
                )
            }
            IconButton(onClick = onOpenQueue, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "播放队列",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * 清屏模式：只留封面（或歌词），其余界面全部隐去，点击任意位置退出。
 * 提示文字 2.6 秒后自动淡出，避免长期遮挡画面。
 */
@Composable
private fun ClearScreenPlayer(
    song: Song,
    showLyrics: Boolean,
    lyricsState: LyricsUiState,
    positionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onAlignLine: (Long) -> Unit,
    onExit: () -> Unit,
) {
    var hintVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(2600)
        hintVisible = false
    }

    // 系统栏的隐藏/恢复统一由 AMMusicRoot 按路由处理，这里只管画面本身。

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onExit,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight) * 0.9f
        if (showLyrics && lyricsState is LyricsUiState.Ready) {
            LyricsView(
                lines = lyricsState.lines,
                positionMs = positionMs,
                isPlaying = isPlaying,
                onSeek = onSeek,
                onAlignLine = onAlignLine,
                coverUrl = song.coverUrl ?: song.albumArtUri?.toString(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 64.dp),
            )
        } else {
            AlbumArt(
                uri = song.albumArtUri,
                size = side,
                shape = RoundedCornerShape(3.dp),
            )
        }

        if (hintVisible) {
            Text(
                text = "点击任意位置退出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 44.dp),
            )
        }
    }
}


