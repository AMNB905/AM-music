package com.am.music

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.am.music.data.settings.ThemeMode
import com.am.music.data.island.IslandPrefs
import com.am.music.service.DynamicIslandService
import com.am.music.ui.AMMusicRoot
import com.am.music.ui.theme.AMMusicTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全局沉浸：状态栏 / 导航栏一律透明，页面背景（纸白或墨黑）铺到屏幕四边。
        // SystemBarStyle.auto 只管图标明暗，真正的图标颜色在 AMMusicRoot 里按主题再对齐一次。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // 关掉系统给导航栏加的对比蒙层，否则纸白页面底部会多出一条灰带
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val container = (application as AMMusicApp).container
        ensureIslandRunning()
        setContent {
            val themeMode by container.settingsRepository.themeMode.collectAsStateWithLifecycle()
            val accentArgb by container.settingsRepository.accentColor.collectAsStateWithLifecycle()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            AMMusicTheme(
                darkTheme = darkTheme,
                accent = accentArgb.takeIf { it != 0 }?.let { androidx.compose.ui.graphics.Color(it) },
            ) {
                AMMusicRoot(container = container)
            }
        }
    }

    /**
     * 岛是普通服务，进程被系统回收后不会自己回来。这里每次回到 App 都补一次：
     * 开关还开着、悬浮窗权限也在，就重新起点。
     *
     * 只在「服务已经没了」时起：ACTION_REFRESH 会整窗重建，正在显示的岛没必要抖一下。
     */
    private fun ensureIslandRunning() {
        if (!IslandPrefs.isEnabled(this)) return
        if (!DynamicIslandService.canDrawOverlays(this)) return
        if (isIslandServiceRunning()) return
        runCatching {
            startService(
                Intent(this, DynamicIslandService::class.java)
                    .setAction(DynamicIslandService.ACTION_REFRESH)
            )
        }
    }

    private fun isIslandServiceRunning(): Boolean = DynamicIslandService.isRunning
}
