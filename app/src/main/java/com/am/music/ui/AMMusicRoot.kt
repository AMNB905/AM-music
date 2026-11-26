package com.am.music.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.animation.core.spring
import com.am.music.AppContainer
import com.am.music.BuildConfig
import com.am.music.data.announce.AnnouncementFeed
import com.am.music.data.island.IslandPrefs
import com.am.music.data.update.UpdateInfo
import com.am.music.data.update.UpdateVerdict
import com.am.music.ui.theme.Motion
import com.am.music.ui.components.FloatingIsland
import com.am.music.ui.components.FloatingTabBar
import com.am.music.ui.components.FloatingTabSpec
import com.am.music.ui.components.UpdateDialog
import com.am.music.ui.components.AnnouncementDialog
import com.am.music.ui.components.AmAlertDialog
import com.am.music.ui.components.LocalBottomBarInset
import com.am.music.ui.island.IslandNavigator
import com.am.music.util.CrashLog
import com.am.music.playback.PlaybackNotice
import com.am.music.ui.components.MiniPlayer
import com.am.music.ui.components.PlaybackPending
import com.am.music.ui.components.NoticeBar
import com.am.music.ui.components.PreparingBar
import com.am.music.ui.components.navigationBarBottomPadding
import com.am.music.ui.favorites.FavoritesScreen
import com.am.music.ui.local.LocalMusicScreen
import com.am.music.ui.navigation.Screen
import com.am.music.ui.online.OnlineMusicScreen
import com.am.music.ui.player.PlayerScreen
import com.am.music.ui.playlists.AddSongsScreen
import com.am.music.ui.playlists.PlaylistDetailScreen
import com.am.music.ui.playlists.PlaylistsScreen
import com.am.music.ui.queue.QueueScreen
import com.am.music.ui.recent.RecentScreen
import com.am.music.ui.recognize.RecognizeScreen
import com.am.music.ui.ranking.RankingScreen
import com.am.music.ui.settings.SettingsScreen

/** 浮岛导航栏的胶囊高度（不含离底留白与系统手势条）。 */
private val NAV_BAR_HEIGHT = 64.dp

/** 小播放器的高度，用来算滚动内容要让出多少。 */
private val MINI_PLAYER_HEIGHT = 62.dp

/** 浮岛离屏幕底边的留白，以及小播放器与浮岛之间的缝。 */
private val BOTTOM_GAP = 10.dp

@Composable
fun AMMusicRoot(container: AppContainer) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val playbackState by container.playbackManager.state.collectAsStateWithLifecycle()

    // 灵动岛点「封面/歌名」时把用户送回播放页：App 在前台走这里，不闪 Activity
    DisposableEffect(navController) {
        IslandNavigator.inAppOpenPlayer = { navController.navigate(Screen.Player.route) { launchSingleTop = true } }
        onDispose { IslandNavigator.inAppOpenPlayer = null }
    }
    // 岛先拉起 Activity、再要求跳播放页的情况：这里一次性消费
    LaunchedEffect(navController) {
        if (IslandNavigator.consumePendingOpenPlayer()) {
            navController.navigate(Screen.Player.route) { launchSingleTop = true }
        }
    }

    // ---------------------------------------------------------------- 沉浸式
    // 全局 edge-to-edge（MainActivity 已 enableEdgeToEdge）：状态栏/导航栏透明，
    // 图标明暗跟随当前主题，否则深色主题下会出现「黑字黑底」看不见。
    val view = LocalView.current
    val darkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    LaunchedEffect(darkSurface) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view) ?: return@LaunchedEffect
        controller.isAppearanceLightStatusBars = !darkSurface
        controller.isAppearanceLightNavigationBars = !darkSurface
    }

    // 沉浸式：**导航栏（系统那条小白条）全 App 隐藏**，从边缘上滑可临时唤出；
    // 状态栏平时留着显示时间/电量，但两种情况下要藏：
    //  ① 播放页（整块全屏）；
    //  ② 灵动岛开着的时候。原因：状态栏窗口的 Z 序盖在 TYPE_APPLICATION_OVERLAY 之上，
    //     它在，「摄像头那条带」的触摸就全归 SystemUI，岛点不动。参考项目就是全局隐藏
    //     状态栏，所以它的岛能贴摄像头并且可点。
    val immersive = currentRoute == Screen.Player.route
    val islandOn = runCatching { IslandPrefs.isEnabled(context) }.getOrDefault(false)
    val hideStatusBar = immersive || islandOn
    LaunchedEffect(immersive, hideStatusBar) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view) ?: return@LaunchedEffect
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // 小白条：所有页面都藏
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        // 状态栏：播放页或灵动岛开启时藏
        if (hideStatusBar) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = view.context.findActivity()?.window ?: return@onDispose
            WindowCompat.getInsetsController(window, view)
                ?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 启动公告：每次启动拉一次，内容指纹没被「不再显示」过就弹。
    var announcementFeed by remember { mutableStateOf<AnnouncementFeed?>(null) }
    var announcementSignature by remember { mutableStateOf("") }
    var showAnnouncement by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val feed = container.announcementRepository.load() ?: return@LaunchedEffect
        if (feed.items.isEmpty()) return@LaunchedEffect
        val signature = container.announcementRepository.signatureOf(feed)
        if (container.announcementRepository.isDismissed(signature)) return@LaunchedEffect
        announcementFeed = feed
        announcementSignature = signature
        showAnnouncement = true
    }

    // 云更新：低于 force.belowCode 一律强制，其余给「稍后」。检查失败就当没有，不影响启动。
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateForced by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val info = container.updateChecker.load() ?: return@LaunchedEffect
        when (container.updateChecker.verdict(info, BuildConfig.VERSION_CODE)) {
            UpdateVerdict.FORCE -> {
                updateInfo = info
                updateForced = true
                showUpdate = true
            }

            UpdateVerdict.SOFT -> {
                updateInfo = info
                updateForced = false
                showUpdate = true
            }

            UpdateVerdict.NONE -> Unit
        }
    }

    val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasAudioPermission = results[audioPermission] == true
    }
    LaunchedEffect(Unit) {
        if (!hasAudioPermission) {
            val permissions = buildList {
                add(audioPermission)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    // ---------------------------------------------------------------- 全局沉浸
    // 底部这一层（小播放器 + 导航栏）不再是「占位」，而是**浮在内容之上**的浮岛：
    // 页面内容一直画到屏幕底边，滚动时从浮岛下面穿过去。页面自己按
    // LocalBottomBarInset 留出底部内边距，保证最后几行还能滑出来。
    val showBottomBars = currentRoute != Screen.Player.route &&
        currentRoute != Screen.Queue.route &&
        currentRoute != Screen.Recognize.route
    val hasMiniPlayer = playbackState.currentSong != null
    val bottomInset = NAV_BAR_HEIGHT + BOTTOM_GAP + navigationBarBottomPadding() +
        if (hasMiniPlayer) MINI_PLAYER_HEIGHT + BOTTOM_GAP else 0.dp
    val preparing by PlaybackPending.song.collectAsStateWithLifecycle()
    // 播放链路的回执（缓存不完整 / 直链失效 / 正在重连），过几秒自己消失
    val notice by PlaybackNotice.message.collectAsStateWithLifecycle()

    // 这两条细条消失时文本会变 null，退出动画期间要留住最后一句，否则会先空一行再收起
    var lastNotice by remember { mutableStateOf("") }
    LaunchedEffect(notice) { notice?.let { lastNotice = it } }
    var lastPreparing by remember { mutableStateOf("") }
    LaunchedEffect(preparing) { preparing?.let { lastPreparing = it.title } }

    // 系统返回键：tab 页统一回到「在线」；已经在首页时再按一次才退出（防误触）。
    var lastBackAt by remember { mutableStateOf(0L) }
    val tabRoots = remember {
        setOf(
            Screen.Online.route,
            Screen.Ranking.route,
            Screen.Local.route,
            Screen.Playlists.route,
            Screen.Settings.route,
        )
    }
    BackHandler(enabled = currentRoute in tabRoots) {
        if (currentRoute != Screen.Online.route) {
            navController.navigate(Screen.Online.route) {
                launchSingleTop = true
                popUpTo(Screen.Online.route) { inclusive = false }
            }
        } else {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackAt < 2_000L) {
                context.findActivity()?.finish()
            } else {
                lastBackAt = now
                Toast.makeText(context, "再按一次退出", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        CompositionLocalProvider(
            LocalBottomBarInset provides if (showBottomBars) bottomInset else 0.dp,
        ) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Online.route,
                    modifier = Modifier.fillMaxSize(),
                    // 页面切换：横向位移给足（1/12 屏）+ 淡入，退场反向且走得更急。
                    // 位移量比之前大一倍，翻页的方向感才立得住；曲线用统一的那两条。
                    enterTransition = {
                        fadeIn(tween(220)) +
                            slideInHorizontally(tween(Motion.ENTER, easing = Motion.EmphasizedDecel)) { it / 12 }
                    },
                    exitTransition = {
                        fadeOut(tween(170)) +
                            slideOutHorizontally(tween(Motion.SWAP, easing = Motion.EmphasizedAccel)) { -it / 16 }
                    },
                    popEnterTransition = {
                        fadeIn(tween(220)) +
                            slideInHorizontally(tween(Motion.ENTER, easing = Motion.EmphasizedDecel)) { -it / 12 }
                    },
                    popExitTransition = {
                        fadeOut(tween(170)) +
                            slideOutHorizontally(tween(Motion.SWAP, easing = Motion.EmphasizedAccel)) { it / 16 }
                    },
                ) {
                composable(Screen.Local.route) {
                    LocalMusicScreen(
                        hasPermission = hasAudioPermission,
                        onRequestPermission = { permissionLauncher.launch(arrayOf(audioPermission)) },
                        container = container,
                    )
                }
                composable(Screen.Playlists.route) {
                    PlaylistsScreen(
                        container = container,
                        onFavoritesClick = { navController.navigate(Screen.Favorites.route) },
                        onRecentClick = { navController.navigate(Screen.Recent.route) },
                        onPlaylistClick = { id ->
                            navController.navigate(Screen.PlaylistDetail.routeFor(id))
                        },
                    )
                }
                composable(Screen.Online.route) {
                    OnlineMusicScreen(
                        container = container,
                        onRecognize = { navController.navigate(Screen.Recognize.route) },
                    )
                }
                composable(Screen.Ranking.route) {
                    RankingScreen(container = container)
                }
                composable(Screen.Recognize.route) {
                    RecognizeScreen(
                        container = container,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(container = container)
                }
                composable(Screen.Favorites.route) {
                    FavoritesScreen(
                        container = container,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.Recent.route) {
                    RecentScreen(
                        container = container,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Screen.PlaylistDetail.route,
                    arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
                ) { entry ->
                    val playlistId = entry.arguments?.getLong("playlistId") ?: 0L
                    PlaylistDetailScreen(
                        container = container,
                        playlistId = playlistId,
                        onBack = { navController.popBackStack() },
                        onAddSongs = { navController.navigate(Screen.AddSongs.routeFor(playlistId)) },
                    )
                }
                composable(
                    route = Screen.AddSongs.route,
                    arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
                ) { entry ->
                    val playlistId = entry.arguments?.getLong("playlistId") ?: 0L
                    AddSongsScreen(
                        container = container,
                        playlistId = playlistId,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Screen.Player.route,
                    // 播放页从底部升起，退出时落回去。位移给到整屏，配强调减速曲线，
                    // 像一块面板被抬起来，而不是淡出来一张图。
                    enterTransition = {
                        slideInVertically(tween(340, easing = Motion.EmphasizedDecel)) { it } +
                            fadeIn(tween(220, delayMillis = 40))
                    },
                    popExitTransition = {
                        slideOutVertically(tween(280, easing = Motion.EmphasizedAccel)) { it } +
                            fadeOut(tween(200))
                    },
                ) {
                    PlayerScreen(
                        container = container,
                        onBack = { navController.popBackStack() },
                        onOpenQueue = { navController.navigate(Screen.Queue.route) },
                    )
                }
                composable(Screen.Queue.route) {
                    QueueScreen(
                        container = container,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            }
        }

        AnimatedVisibility(
            visible = showBottomBars,
            // 底部浮岛跟随页面进出：滑上来/滑下去，而不是突然出现
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
            ) { it } + fadeIn(tween(240)),
            exit = slideOutVertically(
                animationSpec = tween(Motion.SWAP, easing = Motion.EmphasizedAccel),
            ) { it } + fadeOut(tween(170)),
        ) {
            // 浮岛：不再是一条贴满屏宽的栏，而是离底边、左右留白的两颗胶囊（小播放器一颗、导航一颗）。
            // 内容一直画到屏幕底边，从浮岛下面滚过去；底部该让多少由页面按 LocalBottomBarInset 自己留。
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = navigationBarBottomPadding() + BOTTOM_GAP),
            ) {
                // 播放出了状况：一句话说清楚，别让进度条停在那里没人解释。
                // 进出都给一段收缩动画——它是临时状态，不该在浮岛上硬生生顶出一块空隙
                AnimatedVisibility(
                    visible = notice != null,
                    enter = expandVertically(
                        animationSpec = spring(dampingRatio = 0.9f, stiffness = 380f),
                        expandFrom = Alignment.Bottom,
                    ) + fadeIn(tween(200)),
                    exit = shrinkVertically(animationSpec = tween(180), shrinkTowards = Alignment.Bottom) +
                        fadeOut(tween(140)),
                ) {
                    NoticeBar(text = lastNotice)
                }
                // 点歌到出声之间的空档：显式告诉用户在准备哪首，别让人以为点了没反应
                AnimatedVisibility(
                    visible = preparing != null,
                    enter = expandVertically(
                        animationSpec = spring(dampingRatio = 0.9f, stiffness = 380f),
                        expandFrom = Alignment.Bottom,
                    ) + fadeIn(tween(200)),
                    exit = shrinkVertically(animationSpec = tween(180), shrinkTowards = Alignment.Bottom) +
                        fadeOut(tween(140)),
                ) {
                    PreparingBar(title = lastPreparing)
                }
                // 迷你播放器：第一次点歌时从下面顶上来，清空播放后缩回去。
                // 以前是 if (hasMiniPlayer) 硬插一块，浮岛会「啪」地长高一截
                AnimatedVisibility(
                    visible = hasMiniPlayer,
                    // 从底部弹性撑开：第一次点歌时整块浮岛会「弹一下」再稳住，
                    // 比匀速 tween 更有实体感。
                    enter = expandVertically(
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 240f),
                        expandFrom = Alignment.Bottom,
                    ) + fadeIn(tween(220)),
                    exit = shrinkVertically(
                        animationSpec = tween(Motion.SWAP, easing = Motion.EmphasizedAccel),
                        shrinkTowards = Alignment.Bottom,
                    ) + fadeOut(tween(170)),
                ) {
                    Column {
                        FloatingIsland(
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            MiniPlayer(
                                state = playbackState,
                                onTogglePlayPause = container.playbackManager::togglePlayPause,
                                onNext = container.playbackManager::next,
                                onClick = { navController.navigate(Screen.Player.route) },
                                // 浮块里不要那条通栏分隔线，进度轨道也透明，胶囊里才是一整块
                                showDivider = false,
                                progressTrackColor = Color.Transparent,
                            )
                        }
                        Spacer(Modifier.height(BOTTOM_GAP))
                    }
                }
                FloatingTabBar(
                    selectedIndex = when (currentRoute) {
                        Screen.Ranking.route -> 1
                        Screen.Local.route -> 2
                        Screen.Playlists.route -> 3
                        Screen.Settings.route -> 4
                        else -> 0
                    },
                    onTabSelected = { index ->
                        val target = when (index) {
                            1 -> Screen.Ranking
                            2 -> Screen.Local
                            3 -> Screen.Playlists
                            4 -> Screen.Settings
                            else -> Screen.Online
                        }
                        navController.navigate(target.route) {
                            launchSingleTop = true
                            popUpTo(Screen.Online.route)
                        }
                    },
                    items = listOf(
                        FloatingTabSpec(Icons.Default.Radio, "在线"),
                        FloatingTabSpec(Icons.Default.Leaderboard, "榜单"),
                        FloatingTabSpec(Icons.Default.LibraryMusic, "本地"),
                        FloatingTabSpec(Icons.AutoMirrored.Filled.QueueMusic, "歌单"),
                        FloatingTabSpec(Icons.Default.Tune, "设置"),
                    ),
                )
            }
        }
    }

    val feed = announcementFeed
    if (showAnnouncement && feed != null && !(showUpdate && updateForced)) {
        AnnouncementDialog(
            feed = feed,
            onDismiss = { showAnnouncement = false },
            onDismissForever = {
                container.announcementRepository.dismiss(announcementSignature)
                showAnnouncement = false
            },
        )
    }

    val pendingUpdate = updateInfo
    if (showUpdate && pendingUpdate != null) {
        UpdateDialog(
            info = pendingUpdate,
            forced = updateForced,
            currentName = BuildConfig.VERSION_NAME,
            onFinish = { showUpdate = false },
            onLater = if (updateForced) {
                null
            } else {
                {
                    container.updateChecker.snooze(pendingUpdate)
                    showUpdate = false
                }
            },
        )
    }

    // 上一次崩溃的栈：真有崩溃就原样弹出来（可长按复制），看完即删。
    // 远端没有 logcat 的时候，这一块就是唯一的现场；宁可在启动时多一个不常出现的弹窗。
    var crashText by remember { mutableStateOf(CrashLog.read(context)) }
    val crash = crashText
    if (crash != null) {
        AmAlertDialog(
            onDismissRequest = {
                CrashLog.clear(context)
                crashText = null
            },
            title = { Text("上次崩溃了") },
            text = {
                SelectionContainer {
                    Column(
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(text = crash, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        CrashLog.clear(context)
                        crashText = null
                    },
                ) {
                    Text("知道了")
                }
            },
        )
    }
}


/** 从任意 Context 往上找到宿主 Activity（沉浸式要拿它的 window）。 */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return context as? Activity
}


