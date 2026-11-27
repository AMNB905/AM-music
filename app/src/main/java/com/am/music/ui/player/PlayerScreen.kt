package com.am.music.ui.player

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.am.music.AppContainer
import com.am.music.data.model.Song
import com.am.music.playback.PlayMode
import com.am.music.ui.components.MotionIconButton
import com.am.music.ui.components.AlbumArt
import com.am.music.ui.components.PlaybackProgress
import com.am.music.ui.components.RotatingLoadingHint
import com.am.music.ui.components.SkeletonBlock
import com.am.music.ui.components.rememberSmoothPosition
import com.am.music.ui.theme.Motion
import com.am.music.ui.theme.pressScale
import com.am.music.util.formatDuration
import com.am.music.ui.components.AnimatedIcon
import com.am.music.ui.components.animatedIconColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            .padding(horizontal = 28.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MotionIconButton(onClick = leave) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (song != null) {
                MotionIconButton(
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
                MotionIconButton(onClick = viewModel::toggleFavorite) {
                    // 收藏是有重量的动作：空心心弹成实心，配上颜色渐变
                    AnimatedIcon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "取消收藏" else "收藏",
                        modifier = Modifier.size(18.dp),
                        tint = animatedIconColor(
                            target = if (isFavorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ),
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
                // 封面自带一圈很淡的主色光：播放时以 3.2 秒的周期明暗呼吸，暂停就静下来。
                // 光是从封面「发出来」的，不是页面背景，所以整块画面有中心。
                val coverGlow = rememberInfiniteTransition(label = "coverGlow")
                val glowRaw by coverGlow.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(3200, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "coverGlowValue",
                )
                // 入场时光晕跟着封面一起亮起来，不会先出现一团光等人
                val glowAlpha = (if (state.isPlaying) 0.10f + 0.12f * glowRaw else 0.07f) *
                    (0.2f + 0.8f * coverEntry.value)

                // ---- 封面手势 ----
                // 左右拖动 = 换歌；单击 = 看歌词；双击 = 收藏（让一颗心从封面上浮起来）。
                // 拖动时整块封面跟手走并轻微倾斜，隔壁那首的封面会从边上跟着滑进来——
                // 让人看见后面还有牌，松手过了阈值就真的翻过去。
                val coverScope = rememberCoroutineScope()
                val haptics = LocalHapticFeedback.current
                val queue = state.queue
                val songIndex = state.currentIndex
                val prevSong = if (songIndex >= 1) queue.getOrNull(songIndex - 1) else null
                val nextSong = if (songIndex >= 0) queue.getOrNull(songIndex + 1) else null
                val dragX = remember { Animatable(0f) }
                // 换歌方向：+1 下一首（从右侧进场）、-1 上一首。AnimatedContent 按它决定滑入方向
                var swapDir by remember { mutableIntStateOf(1) }
                var burstKey by remember { mutableIntStateOf(0) }

                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                    val dragFraction = (dragX.value / widthPx).coerceIn(-1.2f, 1.2f)

                    // 光晕垫在最底层，所有封面都压在它上面
                    Box(
                        modifier = Modifier
                            .size(320.dp)
                            .graphicsLayer { alpha = glowAlpha }
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.34f),
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                        Color.Transparent,
                                    ),
                                ),
                                CircleShape,
                            ),
                    )

                    // 往右拖：上一首的封面从左边滑进来（暗一点、小一点、反着斜）
                    if (dragFraction > 0.01f && prevSong != null) {
                        val p = dragFraction
                        AlbumArt(
                            uri = prevSong.albumArtUri,
                            size = 236.dp,
                            shape = RoundedCornerShape(3.dp),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .graphicsLayer {
                                    translationX = -widthPx * 0.72f + p * widthPx * 0.8f
                                    rotationZ = -p * 6f
                                    val s = 0.84f + 0.08f * p
                                    scaleX = s
                                    scaleY = s
                                    alpha = (p * 0.62f).coerceIn(0f, 0.5f)
                                },
                        )
                    }
                    // 往左拖：下一首的封面从右边滑进来
                    if (dragFraction < -0.01f && nextSong != null) {
                        val p = -dragFraction
                        AlbumArt(
                            uri = nextSong.albumArtUri,
                            size = 236.dp,
                            shape = RoundedCornerShape(3.dp),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .graphicsLayer {
                                    translationX = widthPx * 0.72f - p * widthPx * 0.8f
                                    rotationZ = p * 6f
                                    val s = 0.84f + 0.08f * p
                                    scaleX = s
                                    scaleY = s
                                    alpha = (p * 0.62f).coerceIn(0f, 0.5f)
                                },
                        )
                    }

                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                val e = coverEntry.value
                                // 入场：从小播放器那个尺寸（44%）长大，同时从偏左下往上抬一点
                                val entryScale = 0.44f + 0.56f * e
                                translationX = dragX.value + (1f - e) * (28.dp.toPx() * -1f)
                                translationY = (1f - e) * 76.dp.toPx()
                                rotationZ = dragFraction * 5f
                                val s = (1f - 0.05f * kotlin.math.abs(dragFraction)) * entryScale
                                scaleX = s
                                scaleY = s
                                alpha = (1f - 0.18f * kotlin.math.abs(dragFraction)) *
                                    (0.38f + 0.62f * e)
                            }
                            .pointerInput(song.key, widthPx) {
                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { change, amount ->
                                        change.consume()
                                        val target = (dragX.value + amount)
                                            .coerceIn(-widthPx * 0.9f, widthPx * 0.9f)
                                        coverScope.launch { dragX.snapTo(target) }
                                    },
                                    onDragEnd = {
                                        val p = dragX.value / widthPx
                                        when {
                                            p < -0.2f && nextSong != null -> {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                swapDir = 1
                                                viewModel.next()
                                                coverScope.launch { dragX.animateTo(0f, Motion.travel()) }
                                            }

                                            p > 0.2f && prevSong != null -> {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                swapDir = -1
                                                viewModel.previous()
                                                coverScope.launch { dragX.animateTo(0f, Motion.travel()) }
                                            }

                                            else -> coverScope.launch {
                                                dragX.animateTo(0f, Motion.settle())
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        coverScope.launch { dragX.animateTo(0f, Motion.settle()) }
                                    },
                                )
                            }
                            .pointerInput(song.key) {
                                detectTapGestures(
                                    onTap = { showLyrics = true },
                                    onDoubleTap = {
                                        viewModel.toggleFavorite()
                                        burstKey += 1
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        // 换歌：沿拖动方向滑进来（新封面从 92% 长到原位），旧封面反向滑出——
                        // 和手势连起来看，就像一叠卡片被一张张翻过去
                        AnimatedContent(
                            targetState = song,
                            transitionSpec = {
                                (fadeIn(tween(Motion.SWAP, delayMillis = 40)) +
                                    slideInHorizontally(
                                        animationSpec = tween(Motion.SWAP, easing = Motion.EmphasizedDecel),
                                    ) { full -> full / 2 * swapDir } +
                                    scaleIn(Motion.settle(), initialScale = 0.92f)) togetherWith
                                    (fadeOut(tween(190)) +
                                        slideOutHorizontally(
                                            animationSpec = tween(200, easing = Motion.EmphasizedAccel),
                                        ) { full -> -full / 3 * swapDir } +
                                        scaleOut(tween(200), targetScale = 1.04f))
                            },
                            label = "coverSwap",
                        ) { current ->
                            AlbumArt(
                                uri = current.albumArtUri,
                                size = 260.dp,
                                shape = RoundedCornerShape(3.dp),
                                // 播放中让封面极缓推近（14 秒一个来回），静止的图也有气息
                                drift = state.isPlaying,
                                modifier = Modifier.pressScale(0.97f),
                            )
                        }

                        // 收藏迸发：每双击一次播一遍，纯视觉，不拦手势
                        if (burstKey > 0) {
                            key(burstKey) {
                                HeartBurst(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            }
        }

        // 换歌时标题和副标题整体上推 / 上移：位移量给足（1/6 屏），
        // 才看得出「换了一首」；之前 1/12 太细，几乎只剩淡入。
        AnimatedContent(
            targetState = song,
            transitionSpec = {
                (fadeIn(tween(Motion.SWAP, delayMillis = 70)) +
                    slideInVertically(tween(Motion.ENTER, easing = Motion.EmphasizedDecel)) { it / 6 }) togetherWith
                    (fadeOut(tween(180)) +
                        slideOutVertically(tween(Motion.EXIT)) { -it / 10 })
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
        // 拖动时左侧时间染成主色：眼睛不用来回找，就知道现在显示的是「将要跳到的位置」
        val timeColor by animateColorAsState(
            targetValue = if (scrubMs != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            animationSpec = tween(160),
            label = "scrubTimeColor",
        )
        PlaybackProgress(
            progressMs = scrubMs ?: smoothPosition,
            durationMs = state.durationMs,
            onSeek = viewModel::seekTo,
            isPlaying = state.isPlaying,
            onScrub = { scrubMs = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = formatDuration(scrubMs ?: smoothPosition),
                style = MaterialTheme.typography.labelSmall,
                // 等宽字体：每帧刷新时数字宽度不变，整行不会跟着左右抽动
                fontFamily = FontFamily.Monospace,
                color = timeColor,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatDuration(state.durationMs),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MotionIconButton(onClick = viewModel::cyclePlayMode, modifier = Modifier.size(40.dp)) {
                AnimatedContent(
                    targetState = state.playMode,
                    transitionSpec = {
                        (fadeIn(tween(150, delayMillis = 20)) +
                            scaleIn(Motion.pop(), initialScale = 0.56f)) togetherWith
                            (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f))
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
            MotionIconButton(onClick = viewModel::previous, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "上一首",
                    modifier = Modifier.size(24.dp),
                )
            }
            MotionIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(64.dp)) {
                // 播放 / 暂停换图标：新图标从 56% 弹到 100%，落位带一下过冲；
                // 旧图标同时缩小淡出。点一下能明显感到「按到了」。
                AnimatedContent(
                    targetState = state.isPlaying,
                    transitionSpec = {
                        (fadeIn(tween(140, delayMillis = 20)) +
                            scaleIn(Motion.pop(), initialScale = 0.56f)) togetherWith
                            (fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.82f))
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
            MotionIconButton(onClick = viewModel::next, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "下一首",
                    modifier = Modifier.size(24.dp),
                )
            }
            MotionIconButton(onClick = onOpenQueue, modifier = Modifier.size(40.dp)) {
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

        // 提示文字淡出，而不是到点就凭空消失
        AnimatedVisibility(
            visible = hintVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(420)),
        ) {
            Text(
                text = "点击任意位置退出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.padding(bottom = 44.dp),
            )
        }
    }
}

/**
 * 双击封面时的「收藏迸发」：一颗心从封面中央浮起来并放大淡出，
 * 同时一圈很淡的光环向外散开。720ms 一次，不接手势、不占布局。
 */
@Composable
private fun HeartBurst(color: Color) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, tween(720, easing = Motion.EmphasizedDecel))
    }
    val p = t.value
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = (14f + 72f * p) * density
            drawCircle(
                color = color.copy(alpha = 0.30f * (1f - p)),
                radius = radius,
                style = Stroke(width = (2.4f * (1f - p) + 0.4f) * density),
            )
        }
        Icon(
            imageVector = Icons.Default.Favorite,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(34.dp)
                .graphicsLayer {
                    alpha = 1f - p * p
                    translationY = -30.dp.toPx() * p
                    val s = 0.72f + 0.9f * p
                    scaleX = s
                    scaleY = s
                },
        )
    }
}





