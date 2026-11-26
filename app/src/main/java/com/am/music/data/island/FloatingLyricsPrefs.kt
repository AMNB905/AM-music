package com.am.music.data.island

import android.content.Context
import android.content.SharedPreferences

/**
 * 悬浮歌词的开关与位置。
 *
 * 和岛一样独立一份 SharedPreferences：服务（可能 App 不在前台）与设置页都要读写，
 * 直接读盘最省心，进程被回收重建后也能恢复到用户拖到的位置。
 */
object FloatingLyricsPrefs {

    private const val PREFS = "ammusic_floating_lyrics"
    private const val KEY_ON = "fl_on"
    private const val KEY_X = "fl_x"
    private const val KEY_Y = "fl_y"
    private const val KEY_LOCKED = "fl_locked"
    private const val KEY_COLOR = "fl_color"

    /** 歌词字色预设：白、暖黄、薄荷、天蓝、粉、橙。深色浅色壁纸都能挑到一个看得清的。 */
    val COLOR_PRESETS: List<Int> = listOf(
        0xFFFFFFFF.toInt(),
        0xFFFFD666.toInt(),
        0xFF7FE7C4.toInt(),
        0xFF8FD3FF.toInt(),
        0xFFFFA8C8.toInt(),
        0xFFFFC08A.toInt(),
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
    }

    /** -1 表示还没拖过，服务按「靠下三分之一居中」摆。 */
    fun x(context: Context): Int = prefs(context).getInt(KEY_X, -1)

    fun y(context: Context): Int = prefs(context).getInt(KEY_Y, -1)

    fun setPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    /**
     * 锁定：窗不再接收触摸（点击、滑动直接落到下面的 App），位置也拖不动。
     * 歌词条摆在阅读 / 滚动区域上方时必开，否则每次划屏都先命中它。
     */
    fun isLocked(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCKED, false)

    fun setLocked(context: Context, locked: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCKED, locked).apply()
    }

    /** 当前行字色；下一行在服务里按同色降透明度推导，不用单独存。 */
    fun color(context: Context): Int = prefs(context).getInt(KEY_COLOR, 0xFFFFFFFF.toInt())

    fun setColor(context: Context, argb: Int) {
        prefs(context).edit().putInt(KEY_COLOR, argb).apply()
    }
}
