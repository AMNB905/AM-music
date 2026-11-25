package com.am.music.ui.island

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 岛的环形诊断日志（最近 40 条，全在内存里，不写盘）。
 *
 * 为什么需要它：悬浮窗的行为完全由 WindowManager / ROM 决定，出问题（点了展不开、
 * 窗口不跟着变尺寸）时只能看「窗口真实宽高 vs 我请求的 lp」，而这些信息在真机上是
 * 看不见的。设置页「灵动岛」分组里可以直接把这份记录显示出来，用户截图就能定位。
 */
object IslandDebug {

    private const val MAX = 40
    private const val TAG = "DynamicIsland"

    private val lines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(message: String) {
        val line = "${timeFormat.format(Date())} $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        Log.d(TAG, message)
    }

    fun dump(): String = synchronized(lines) {
        if (lines.isEmpty()) "（还没有记录，先播放一首歌再点一下岛）" else lines.joinToString("\n")
    }

    fun clear() = synchronized(lines) { lines.clear() }
}
