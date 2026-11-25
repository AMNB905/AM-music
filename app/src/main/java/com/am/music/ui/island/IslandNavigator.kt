package com.am.music.ui.island

import android.content.Context
import android.content.Intent
import com.am.music.MainActivity

/**
 * 岛 → 播放页的通道。
 *
 * 两个来源：
 * 1. App 正在前台（AMMusicRoot 还活着）：直接调注入进来的 lambda，走 NavController，
 *    不闪一下、不重建 Activity。
 * 2. App 不在前台：拉起 MainActivity，把「待打开播放页」记成一次性标记，
 *    AMMusicRoot 一挂载就消费它并跳过去。
 *
 * 为什么不直接 startActivity 到某个播放 Activity：本项目只有一个 MainActivity，
 * 播放页是 NavHost 里的一个 route，外部没法直接指定。
 */
object IslandNavigator {

    /** 由 AMMusicRoot 在组合期注入；离开组合时清空。 */
    var inAppOpenPlayer: (() -> Unit)? = null

    private var pendingOpenPlayer = false

    fun requestOpenPlayer(context: Context) {
        val inApp = inAppOpenPlayer
        if (inApp != null) {
            inApp()
            // 已经在 App 里也顺手把 Activity 提到前台，防止用户正停在别的应用
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                )
            }
            return
        }
        pendingOpenPlayer = true
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
    }

    /** 一次性消费：返回 true 表示这次挂载应当跳到播放页。 */
    fun consumePendingOpenPlayer(): Boolean {
        val value = pendingOpenPlayer
        pendingOpenPlayer = false
        return value
    }
}
