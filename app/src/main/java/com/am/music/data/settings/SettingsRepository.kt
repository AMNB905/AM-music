package com.am.music.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音质档位，取值范围与后端 qq/api/url、wy/api/url 的 quality 参数一致：
 * auto / flac / 320 / 128 / aac。
 * 只保留四档，避免设置页堆砌选项——「简洁」优先。
 */
enum class AudioQuality(val value: String, val label: String, val note: String) {
    AUTO("auto", "自动", "音源给什么用什么，不行就降档"),
    LOSSLESS("flac", "无损", "FLAC 原声，体积与流量最大"),
    HIGH("320", "高品", "320K，音质与流量均衡"),
    STANDARD("128", "标准", "128K，最省流量");

    companion object {
        fun fromValue(value: String?): AudioQuality =
            entries.firstOrNull { it.value == value } ?: AUTO
    }
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** 极简设置：只持久化真正影响体验的两项，其余走默认。 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("ammusic_settings", Context.MODE_PRIVATE)

    private val _quality = MutableStateFlow(
        AudioQuality.fromValue(prefs.getString(KEY_QUALITY, null))
    )
    val quality: StateFlow<AudioQuality> = _quality.asStateFlow()

    private val _themeMode = MutableStateFlow(
        ThemeMode.fromName(prefs.getString(KEY_THEME, null))
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** App 主题色（ARGB）：0 = 默认黑白，不染任何颜色。 */
    private val _accentColor = MutableStateFlow(prefs.getInt(KEY_ACCENT, 0))
    val accentColor: StateFlow<Int> = _accentColor.asStateFlow()

    /**
     * 默认独占音频焦点：别人一出声（导航播报、来电、另一个音乐 App）本 App 自动让路。
     * 打开这一项就是「与其他应用同时播放」：请求焦点时不再是强制独占，两路声音可以并存。
     */
    private val _mixWithOthers = MutableStateFlow(prefs.getBoolean(KEY_MIX, false))
    val mixWithOthers: StateFlow<Boolean> = _mixWithOthers.asStateFlow()

    /** 播放服务在建播放器时要同步读一次当前值。 */
    val mixWithOthersValue: Boolean
        get() = _mixWithOthers.value

    /**
     * 系统媒体岛（各家 ROM 的灵动岛/超级岛/媒体卡片）开关。
     *
     * false（默认）= 跟随系统：播放通知是标准 MediaStyle，系统想顶就顶。
     * true = 关闭：通知退化成普通前台通知（不带 MediaStyle、不挂 sessionToken），
     *        岛没有触发源，留给 App 自己实现；播放能力（MediaSession、蓝牙按键、
     *        锁屏卡片）不受影响。
     */
    private val _hideSystemMediaIsland = MutableStateFlow(prefs.getBoolean(KEY_HIDE_ISLAND, false))
    val hideSystemMediaIsland: StateFlow<Boolean> = _hideSystemMediaIsland.asStateFlow()

    /** 网络层等非 Composable 场景直接同步读取当前音质。 */
    val qualityValue: String
        get() = _quality.value.value

    fun setQuality(quality: AudioQuality) {
        if (_quality.value == quality) return
        _quality.value = quality
        prefs.edit().putString(KEY_QUALITY, quality.value).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        if (_themeMode.value == mode) return
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun setAccentColor(argb: Int) {
        if (_accentColor.value == argb) return
        _accentColor.value = argb
        prefs.edit().putInt(KEY_ACCENT, argb).apply()
    }

    fun setMixWithOthers(value: Boolean) {
        if (_mixWithOthers.value == value) return
        _mixWithOthers.value = value
        prefs.edit().putBoolean(KEY_MIX, value).apply()
    }

    fun setHideSystemMediaIsland(value: Boolean) {
        if (_hideSystemMediaIsland.value == value) return
        _hideSystemMediaIsland.value = value
        prefs.edit().putBoolean(KEY_HIDE_ISLAND, value).apply()
    }

    private companion object {
        const val KEY_QUALITY = "audio_quality"
        const val KEY_THEME = "theme_mode"
        const val KEY_MIX = "mix_with_others"
        const val KEY_HIDE_ISLAND = "hide_system_media_island"
        const val KEY_ACCENT = "theme_accent"
    }
}
