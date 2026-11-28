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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.drop
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.core.spring
import com.am.music.AppContainer
import com.am.music.BuildConfig
import com.am.music.data.announce.AnnouncementFeed
import com.am.music.data.island.IslandPrefs
import com.am.music.data.model.Song
import com.am.music.data.update.UpdateInfo
import com.am.music.data.update.UpdateVerdict
import com.am.music.ui.theme.Motion
import com.am.music.ui.components.AlbumArt
import com.am.music.ui.components.FloatingIsland
import com.am.music.ui.components.FloatingTabBar
import com.am.music.ui.components.FloatingTabSpec
import com.am.music.ui.components.UpdateDialog
import com.am.music.ui.components.AnnouncementDialog
import com.am.music.ui.components.AmAlertDialog
import com.am.music.ui.components.LocalBottomBarInset
import com.am.music.ui.island.IslandNavigator
import com.am.music.ui.navigation.AppNavGraph
import com.am.music.ui.onboarding.OnboardingAction
import com.am.music.ui.onboarding.OnboardingOverlay
import com.am.music.ui.onboarding.OnboardingSignal
import com.am.music.ui.onboarding.OnboardingSignals
import com.am.music.ui.onboarding.OnboardingTargets
import com.am.music.ui.onboarding.rememberOnboardingSteps
import com.am.music.util.CrashLog
import com.am.music.playback.PlaybackNotice
import com.am.music.ui.components.MiniPlayer
import com.am.music.ui.components.PlaybackPending
import com.am.music.ui.components.NoticeBar
import com.am.music.ui.components.PreparingBar
import com.am.music.ui.components.navigationBarBottomPadding
import com.am.music.ui.navigation.Screen

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

    // ---------------------------------------------------------------- 新手引导
    // 第一次进来弹一次；之后只能从设置页「再看一次」叫回来（走 onboardingRepository 的信号）。
    var showOnboarding by remember { mutableStateOf(container.onboardingRepository.shouldShowOnLaunch()) }
    // 手把手引导的进度：由根页面持有，因为「这一步过没过」要看真实的路由 / 信号，而不在黑罩里
    var onboardingIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        container.onboardingRepository.requestFlow.drop(1).collect {
            OnboardingSignals.reset()
            onboardingIndex = 0
            showOnboarding = true
        }
    }
    // 引导要挖洞指着真实控件，所以先量好底部两颗胶囊的位置；控件还没排版时先空着
    var tabBounds by remember { mutableStateOf<Rect?>(null) }
    var miniPlayerBounds by remember { mutableStateOf<Rect?>(null) }
    // 播放页里的封面 / ⋯ 由那个页面自己量，经 OnboardingSignals 回传（这里读到状态变化会自动重组）
    val coverBounds = OnboardingSignals.coverBounds
    val menuBounds = OnboardingSignals.menuBounds
    val onboardingTargets = remember(tabBounds, miniPlayerBounds, coverBounds, menuBounds) {
        buildMap {
            tabBounds?.let { put(OnboardingTargets.BOTTOM_TABS, it) }
            miniPlayerBounds?.let { put(OnboardingTargets.MINI_PLAYER, it) }
            coverBounds?.let { put(OnboardingTargets.PLAYER_COVER, it) }
            menuBounds?.let { put(OnboardingTargets.PLAYER_QUEUE_MENU, it) }
        }
    }

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
            AppNavGraph(
                navController = navController,
                container = container,
                hasAudioPermission = hasAudioPermission,
                onRequestAudioPermission = { permissionLauncher.launch(arrayOf(audioPermission)) },
            )
        }

        AnimatedVisibility(
            visible = showBottomBars,
            // 底部浮岛跟随页面进出：滑上来/滑下去，而不是突然出现
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
            ) { it } + fadeIn(tween(240)),
            // 从上滑手势进播放页时，浮岛不能慢慢淡出去——它会飘在已经出现的播放页上面，
            // 一眼就看出「两页在打架」。这一种情况让它直接消失（封面飞行层已经盖在上面了）。
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
                            modifier = Modifier
                                .fillMaxWidth()
                                // 新手引导要指着这颗胶囊，先把它在根布局里的位置量下来
                                .onGloballyPositioned { miniPlayerBounds = it.boundsInRoot() },
                        ) {
                            MiniPlayer(
                                state = playbackState,
                                onTogglePlayPause = container.playbackManager::togglePlayPause,
                                onNext = container.playbackManager::next,
                                // 迷你条上右滑回上一首（左滑下一首由组件自己处理）
                                onPrevious = container.playbackManager::previous,
                                onClick = { navController.navigate(Screen.Player.route) },
                                // 浮块里不要那条通栏分隔线，进度轨道也透明，胶囊里才是一整块
                                showDivider = false,
                                progressTrackColor = Color.Transparent,
                            )
                        }
                        Spacer(Modifier.height(BOTTOM_GAP))
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { tabBounds = it.boundsInRoot() },
                ) {
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
        }

        // 新手引导：盖在浮岛之上，但不跟启动公告 / 云更新抢同一时刻——
        // 那两个是「必须现在知道」的信息，引导可以等它们说完再讲手势。
        val onboardingSteps = rememberOnboardingSteps()
        // 完成一拍：这一步「App 真的变了」之后先记一下，交给遮罩层播完确认动画再往下走，
        // 而不是当场翻页——否则用户看到的是「点完立刻换内容」，不知道自己那一下成功了没有。
        var onboardingDoneTick by remember { mutableIntStateOf(0) }
        val advanceOnboarding: () -> Unit = {
            if (onboardingIndex < onboardingSteps.lastIndex) {
                onboardingIndex += 1
            } else {
                container.onboardingRepository.markDone()
                showOnboarding = false
            }
        }

        // 引导整体淡入淡出：开场别硬闪一块黑，收工也别「啪」一下没了
        AnimatedVisibility(
            visible = showOnboarding && !showAnnouncement && !showUpdate,
            enter = fadeIn(tween(Motion.ENTER)),
            exit = fadeOut(tween(Motion.EXIT)),
        ) {
            val step = onboardingSteps.getOrNull(onboardingIndex)
            // 通关判定看「App 真的变了」：路由变了 / 歌词真的开了。
            // 放在这里而不是遮罩里，是因为这些信号只有根页面能看到。
            LaunchedEffect(onboardingIndex, currentRoute, OnboardingSignals.lyricsOpened) {
                val action = step?.action as? OnboardingAction.Tap ?: return@LaunchedEffect
                val done = when {
                    action.expectSignal == OnboardingSignal.LyricsOpened ->
                        OnboardingSignals.lyricsOpened
                    action.expectRoute == OnboardingAction.Tap.ANY_TAB ->
                        currentRoute != null && currentRoute != Screen.Online.route && currentRoute in tabRoots
                    action.expectRoute != null -> currentRoute == action.expectRoute
                    else -> false
                }
                if (done) onboardingDoneTick += 1
            }
            OnboardingOverlay(
                steps = onboardingSteps,
                targets = onboardingTargets,
                index = onboardingIndex,
                doneTick = onboardingDoneTick,
                onAdvance = advanceOnboarding,
                onFinish = {
                    container.onboardingRepository.markDone()
                    showOnboarding = false
                    OnboardingSignals.reset()
                },
            )
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


