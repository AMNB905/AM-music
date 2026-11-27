package com.am.music.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.am.music.AppContainer
import com.am.music.ui.favorites.FavoritesScreen
import com.am.music.ui.local.LocalMusicScreen
import com.am.music.ui.online.OnlineMusicScreen
import com.am.music.ui.playlists.AddSongsScreen
import com.am.music.ui.playlists.PlaylistDetailScreen
import com.am.music.ui.playlists.PlaylistsScreen
import com.am.music.ui.player.PlayerScreen
import com.am.music.ui.queue.QueueScreen
import com.am.music.ui.ranking.RankingScreen
import com.am.music.ui.recognize.RecognizeScreen
import com.am.music.ui.recent.RecentScreen
import com.am.music.ui.settings.SettingsScreen
import com.am.music.ui.theme.Motion

/**
 * 全部页面的路由表。
 *
 * 从根布局里拆出来单独成文件，是因为「有哪些页面、页面之间怎么转场」
 * 跟「壳层（浮岛、权限、系统栏、启动弹窗）」是两回事：
 * 前者只在加页面时改，后者经常跟着交互调整。放一起时两边都难读。
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    container: AppContainer,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Online.route,
        modifier = modifier.fillMaxSize(),
        // 页面切换：横向位移给足（1/12 屏）+ 淡入，退场反向且走得更急。
        // 再叠一层极轻的缩放：新页从 97% 长到原位，旧页缩到 99% 退场，
        // 位移解决「从哪里来」，缩放解决「深度」——两页不再像并排贴着的两张纸。
        enterTransition = {
            fadeIn(tween(220)) +
                slideInHorizontally(tween(Motion.ENTER, easing = Motion.EmphasizedDecel)) { it / 12 } +
                scaleIn(tween(Motion.ENTER, easing = Motion.EmphasizedDecel), initialScale = 0.97f)
        },
        exitTransition = {
            fadeOut(tween(170)) +
                slideOutHorizontally(tween(Motion.SWAP, easing = Motion.EmphasizedAccel)) { -it / 16 } +
                scaleOut(tween(200, easing = Motion.EmphasizedAccel), targetScale = 0.99f)
        },
        popEnterTransition = {
            fadeIn(tween(220)) +
                slideInHorizontally(tween(Motion.ENTER, easing = Motion.EmphasizedDecel)) { -it / 12 } +
                scaleIn(tween(Motion.ENTER, easing = Motion.EmphasizedDecel), initialScale = 0.97f)
        },
        popExitTransition = {
            fadeOut(tween(170)) +
                slideOutHorizontally(tween(Motion.SWAP, easing = Motion.EmphasizedAccel)) { it / 16 } +
                scaleOut(tween(200, easing = Motion.EmphasizedAccel), targetScale = 0.99f)
        },
    ) {
        composable(Screen.Local.route) {
            LocalMusicScreen(
                hasPermission = hasAudioPermission,
                onRequestPermission = onRequestAudioPermission,
                container = container,
            )
        }
        composable(Screen.Playlists.route) {
            PlaylistsScreen(
                container = container,
                onFavoritesClick = { navController.navigate(Screen.Favorites.route) },
                onRecentClick = { navController.navigate(Screen.Recent.route) },
                onPlaylistClick = { id -> navController.navigate(Screen.PlaylistDetail.routeFor(id)) },
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
                // 点队列里正在播的那首：直接回到播放页（已经在队列页，不再叠一层）
                onOpenPlayer = {
                    navController.navigate(Screen.Player.route) { launchSingleTop = true }
                },
            )
        }
    }
}

