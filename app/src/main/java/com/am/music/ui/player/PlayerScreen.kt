package com.am.music.ui.player

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.playback.PlaybackNotice
import com.am.music.ui.components.MotionIconButton
import com.am.music.ui.components.SleepTimerDialog
import com.am.music.ui.onboarding.OnboardingSignals
import com.am.music.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * 播放页：只做编排。
 *
 * - 上半场是 [PlayerCoverStage]（封面 / 手势）与 [PlayerLyricsStage]（歌词 / 回封面手势）；
 * - 下半场是 [PlayerTransportBar]（进度与传输控制）；
 * - 顶栏只留返回 + 更多，歌词切换不再是菜单项——点封面进歌词，
 *   歌词页下滑或轻点空白回封面，两条手势自带方向感。
 */
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
    // 顶栏「更多」：收藏 / 清屏 / 倍速 / 定时关闭都从这里进（队列交给播放条右侧那个图标）
    var showMenu by remember { mutableStateOf(false) }
    // 定时关闭：睡前在播放页就能设，不用退出去翻设置
    var showSleepDialog by remember { mutableStateOf(false) }
    // 清屏模式下系统返回键先退出清屏，不要直接把人踢出播放页
    BackHandler(enabled = clearScreen) { clearScreen = false }

    // ---- 封面入场：和小播放器那颗小封面接上 ----
    // 面板从底部升起，封面再额外长一层：从 44% 大小、偏左下、半透明长到原位——
    // 两段叠在一起就是「小封面被拎到画面中央」。
    val coverEntry = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        coverEntry.snapTo(0f)
        // 稍微等面板先抬起来一点，封面再跟着长，先后有层次但整体仍是一段动作
        coverEntry.animateTo(1f, tween(420, delayMillis = 50, easing = Motion.EmphasizedDecel))
    }
    // 退出时反过来缩回去，同时让路由把页面落下去：两段一起走，
    // 观感是封面缩回底部那颗小播放器、面板同时往下沉，而不是先停一下再翻页
    val exitScope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            exitScope.launch {
                coverEntry.animateTo(0f, tween(280, easing = Motion.EmphasizedAccel))
            }
            onBack()
            Unit
        }
    }
    // 系统返回键也走同一条退出曲线（清屏模式那条 BackHandler 优先）
    BackHandler(enabled = !clearScreen) { leave() }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importLyrics(uri)
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            onPick = { minutes ->
                showSleepDialog = false
                container.sleepTimer.start(minutes)
                PlaybackNotice.brief("$minutes 分钟后渐隐暂停")
            },
            onDismiss = { showSleepDialog = false },
        )
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

    Box(modifier = modifier.fillMaxSize()) {
        // 环境底：当前封面放大虚化铺满，换歌时整屏颜色跟着交叉淡入
        if (song != null) {
            PlayerAmbientBackground(artUri = song.albumArtUri)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                // 播放页隐藏了系统栏，状态栏 inset 会变 0；挖孔屏的 cutout 仍然存在，单独让位
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 24.dp),
        ) {
            PlayerTopBar(
                visible = song != null,
                isFavorite = isFavorite,
                onBack = leave,
                onToggleFavorite = viewModel::toggleFavorite,
                onEnterClearScreen = { clearScreen = true },
                speed = state.speed,
                onCycleSpeed = viewModel::cycleSpeed,
                onOpenSleepTimer = { showSleepDialog = true },
                menuExpanded = showMenu,
                onMenuExpandedChange = { showMenu = it },
            )

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
                    .padding(vertical = 20.dp)
                    // 新手引导要指着这块封面：先把它在根布局里的位置量下来
                    .onGloballyPositioned { OnboardingSignals.coverBounds = it.boundsInRoot() },
                contentAlignment = Alignment.Center,
            ) {
                // 封面 ↔ 歌词：不是硬切。旧的缩小一点淡出，新的从 86% 弹到原位，
                // 像同一块屏在换内容，而不是换了一页。放大走的弹簧，落位时会轻微过冲。
                AnimatedContent(
                    targetState = showLyrics,
                    transitionSpec = {
                        (fadeIn(tween(Motion.SWAP, delayMillis = 60)) +
                            scaleIn(
                                animationSpec = spring(dampingRatio = 0.72f, stiffness = 280f),
                                initialScale = 0.86f,
                            )) togetherWith (fadeOut(tween(180)) +
                            scaleOut(animationSpec = tween(Motion.EXIT), targetScale = 0.94f))
                    },
                    label = "coverLyrics",
                ) { lyricsMode ->
                    if (lyricsMode) {
                        PlayerLyricsStage(
                            lyricsState = lyricsState,
                            positionMs = state.positionMs,
                            isPlaying = state.isPlaying,
                            coverUrl = song.coverUrl ?: song.albumArtUri?.toString(),
                            onSeek = viewModel::seekTo,
                            onAlignLine = viewModel::alignLyricLine,
                            onImportLyrics = { importLauncher.launch(arrayOf("*/*")) },
                            onBackToCover = { showLyrics = false },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        val queue = state.queue
                        val songIndex = state.currentIndex
                        PlayerCoverStage(
                            song = song,
                            prevSong = if (songIndex >= 1) queue.getOrNull(songIndex - 1) else null,
                            nextSong = if (songIndex >= 0) queue.getOrNull(songIndex + 1) else null,
                            isPlaying = state.isPlaying,
                            entryProgress = coverEntry.value,
                            onOpenLyrics = {
                                showLyrics = true
                                OnboardingSignals.lyricsOpened = true
                            },
                            onToggleFavorite = viewModel::toggleFavorite,
                            onPrevious = viewModel::previous,
                            onNext = viewModel::next,
                        )
                    }
                }
            }

            PlayerSongMeta(song = song)

            Spacer(Modifier.height(20.dp))

            PlayerTransportBar(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                isPlaying = state.isPlaying,
                playMode = state.playMode,
                onSeek = viewModel::seekTo,
                onTogglePlayPause = viewModel::togglePlayPause,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onCyclePlayMode = viewModel::cyclePlayMode,
                onOpenQueue = onOpenQueue,
            )

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 顶栏：只留返回 + 更多。播放页的主角是封面，一排图标会把视线从中间拉走。 */
@Composable
private fun PlayerTopBar(
    visible: Boolean,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEnterClearScreen: () -> Unit,
    speed: Float,
    onCycleSpeed: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MotionIconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.weight(1f))
        if (visible) {
            Box {
                MotionIconButton(
                    onClick = { onMenuExpandedChange(true) },
                    modifier = Modifier
                        .size(36.dp)
                        .onGloballyPositioned { OnboardingSignals.menuBounds = it.boundsInRoot() },
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) },
                    offset = DpOffset((-140).dp, 0.dp),
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isFavorite) "取消收藏" else "收藏") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                tint = if (isFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        onClick = {
                            onToggleFavorite()
                            onMenuExpandedChange(false)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("清屏模式") },
                        leadingIcon = { Icon(Icons.Default.Fullscreen, contentDescription = null) },
                        onClick = {
                            onEnterClearScreen()
                            onMenuExpandedChange(false)
                        },
                    )
                    // 队列在下方播放条右侧已经有一个图标入口了，菜单里再放一遍是重复的
                    DropdownMenuItem(
                        text = { Text("倍速 " + formatSpeed(speed)) },
                        leadingIcon = {
                            Icon(Icons.Default.Speed, contentDescription = null)
                        },
                        onClick = { onCycleSpeed() },
                    )
                    DropdownMenuItem(
                        text = { Text("定时关闭") },
                        leadingIcon = {
                            Icon(Icons.Default.Bedtime, contentDescription = null)
                        },
                        onClick = {
                            onMenuExpandedChange(false)
                            onOpenSleepTimer()
                        },
                    )
                }
            }
        }
    }
}

/** 倍速显示成 1× / 1.25× 这种短写法，菜单一行放得下。 */
private fun formatSpeed(speed: Float): String {
    val text = if (speed == speed.toInt().toFloat()) {
        speed.toInt().toString()
    } else {
        speed.toString().trimEnd('0').trimEnd('.')
    }
    return text + "×"
}
