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
}
