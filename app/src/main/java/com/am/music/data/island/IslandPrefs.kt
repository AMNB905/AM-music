package com.am.music.data.island

import android.content.Context
import android.content.SharedPreferences

/**
 * 灵动岛的设置项（独立一份 SharedPreferences，服务端与设置页都能同步读写）。
 *
 * 为什么不用 SettingsRepository 里的 StateFlow：岛的所有读写都发生在
 * 悬浮窗服务（可能 App 界面没在前台）与设置页两个地方，直接读盘最省心，
 * 也避免「服务重启后拿到的还是内存里的旧值」。
 */
enum class IslandAppearance(val label: String, val note: String) {
    BLACK("纯黑", "黑底白字，省电"),
    CUSTOM("自定义背景", "图片或视频当底，字自动压暗");

    companion object {
        /** 老数据里可能还留着已下线的 "GLASS"，一律回落到纯黑。 */
        fun fromName(name: String?): IslandAppearance =
            entries.firstOrNull { it.name == name } ?: BLACK
    }
}

object IslandPrefs {

    private const val PREFS = "ammusic_island"
    private const val KEY_ON = "island_on"
    private const val KEY_EXPANDED = "island_expanded"
    private const val KEY_APPEARANCE = "island_appearance"
    private const val KEY_CUSTOM_URI = "island_custom_uri"
    private const val KEY_ANCHOR = "island_anchor"
    private const val KEY_WIDTH = "island_width"
    private const val KEY_ANCHOR_X = "island_anchor_x"
    private const val KEY_ANCHOR_Y = "island_anchor_y"
    private const val KEY_ACCENT = "island_accent"

    /**
     * 主题色预设：白（默认）、粉、红、橙、黄、绿、青、蓝、紫。
     * 只染「进度条、频谱、播放键」这三处，文字保持白色——换色不影响可读性。
     */
    val ACCENT_PRESETS: IntArray = intArrayOf(
        0xFFFFFFFF.toInt(),
        0xFFFF8FB1.toInt(),
        0xFFFF5A5F.toInt(),
        0xFFFFA94D.toInt(),
        0xFFF2C94C.toInt(),
        0xFF63D471.toInt(),
        0xFF4FD6D6.toInt(),
        0xFF4D9BFF.toInt(),
        0xFFB98CFF.toInt(),
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
    }

    /** 上次是展开态还是收起态：服务重启时按原样恢复，不硬塞成收起。 */
    fun isExpanded(context: Context): Boolean = prefs(context).getBoolean(KEY_EXPANDED, false)

    fun setExpanded(context: Context, expanded: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXPANDED, expanded).apply()
    }

    fun appearance(context: Context): IslandAppearance =
        IslandAppearance.fromName(prefs(context).getString(KEY_APPEARANCE, null))

    fun setAppearance(context: Context, appearance: IslandAppearance) {
        prefs(context).edit().putString(KEY_APPEARANCE, appearance.name).apply()
    }

    /** 自定义背景：用户从系统文件选择器挑的图片/视频，带持久化读取权限的 content:// URI。 */
    fun customUri(context: Context): String? =
        prefs(context).getString(KEY_CUSTOM_URI, null)?.takeIf { it.isNotBlank() }

    fun setCustomUri(context: Context, uri: String?) {
        prefs(context).edit().putString(KEY_CUSTOM_URI, uri).apply()
    }

    /** 锚点尺寸（dp）：黑色圆点直径，胶囊高度 = 锚点 + 22。 */
    fun anchorSize(context: Context): Int =
        prefs(context).getInt(KEY_ANCHOR, 12).coerceIn(8, 40)

    fun setAnchorSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_ANCHOR, size.coerceIn(8, 40)).apply()
    }

    /** 收起态胶囊长度（dp）：实际宽度 = max(它, 56 + 锚点)。 */
    fun width(context: Context): Int =
        prefs(context).getInt(KEY_WIDTH, 80).coerceIn(60, 180)

    fun setWidth(context: Context, width: Int) {
        prefs(context).edit().putInt(KEY_WIDTH, width.coerceIn(60, 180)).apply()
    }

    /** 锚点中心 = 屏幕百分比坐标；-1 表示还没定位过，服务按默认 (50, 12) 摆。 */
    fun anchorX(context: Context): Float = prefs(context).getFloat(KEY_ANCHOR_X, -1f)

    fun anchorY(context: Context): Float = prefs(context).getFloat(KEY_ANCHOR_Y, -1f)

    fun isAnchorSet(context: Context): Boolean =
        anchorX(context) != -1f && anchorY(context) != -1f

    fun setAnchorPos(context: Context, xPercent: Float, yPercent: Float) {
        prefs(context).edit()
            .putFloat(KEY_ANCHOR_X, xPercent.coerceIn(0f, 100f))
            .putFloat(KEY_ANCHOR_Y, yPercent.coerceIn(0f, 100f))
            .apply()
    }

    /** 主题色（ARGB）：默认白，老数据没有这个键就回落白色。 */
    fun accentColor(context: Context): Int =
        prefs(context).getInt(KEY_ACCENT, ACCENT_PRESETS[0])

    fun setAccentColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_ACCENT, color).apply()
    }
}
