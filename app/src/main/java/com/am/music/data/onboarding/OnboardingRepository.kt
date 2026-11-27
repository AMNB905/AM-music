package com.am.music.data.onboarding

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 新手引导的「看过没」：一份极小的持久化 + 一个「请再放一次」的信号。
 *
 * 为什么单独放一层：
 * - 不该塞进 SettingsRepository（那边是一堆音频 / 界面偏好）；
 * - 设置页要能「再看一次」，而引导层挂在根页面上，两边不需要互相持有引用，
 *   所以用一个计数信号 [requestFlow] 传递，根页面收流后自己弹层。
 */
class OnboardingRepository(context: Context) {

    private val prefs = context.getSharedPreferences("ammusic_onboarding", Context.MODE_PRIVATE)

    private val _requestFlow = MutableStateFlow(0)

    /** 设置页「再看一次」发出的信号：根页面收到变化就弹一层。 */
    val requestFlow: StateFlow<Int> = _requestFlow.asStateFlow()

    /** 已经完整走过一遍（或主动跳过）。 */
    fun isDone(): Boolean = prefs.getBoolean(KEY_DONE, false)

    fun markDone() {
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }

    /** 冷启动时要不要弹：只在「没看过」时弹。 */
    fun shouldShowOnLaunch(): Boolean = !isDone()

    /** 设置页点「再看一次」：清标记并发一次信号，根页面不用等重启就弹。 */
    fun replay() {
        prefs.edit().putBoolean(KEY_DONE, false).apply()
        _requestFlow.value += 1
    }

    private companion object {
        const val KEY_DONE = "onboarding_done"
    }
}
