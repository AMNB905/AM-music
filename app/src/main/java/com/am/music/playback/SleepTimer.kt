package com.am.music.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 定时关闭（睡眠定时器）。
 *
 * 只做一件事：数到 0 就调 [onExpire]，具体「怎么停」交给外面注入（当前是
 * `PlaybackManager.pauseForSleepTimer()`，走 240ms 渐隐后暂停，不是硬切）。
 *
 * 为什么不放在服务里：计时器要跟着「用户设置的那一刻」走，而播放服务随控制器连接、
 * 进程生命周期起落；放在 Application 层的容器里，只要进程还在，倒计时就一直准。
 * 进程被杀 = 播放也已停止，定时器随进程消失反而是合理的。
 *
 * 剩余时间用秒级 StateFlow 暴露，设置页每一秒刷新一次「还剩 12:30」。
 */
class SleepTimer(
    private val onExpire: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _remainingSeconds = MutableStateFlow(0L)
    val remainingSeconds: StateFlow<Long> = _remainingSeconds.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean
        get() = _remainingSeconds.value > 0L

    /** 开始（或重设）一个倒计时；分钟数 ≤ 0 等价于取消。 */
    fun start(minutes: Int) {
        if (minutes <= 0) {
            cancel()
            return
        }
        job?.cancel()
        _remainingSeconds.value = minutes * 60L
        job = scope.launch {
            while (_remainingSeconds.value > 0L) {
                delay(1_000L)
                _remainingSeconds.update { (it - 1L).coerceAtLeast(0L) }
            }
            // 先清零再回调，设置页那一行当场从「还剩 00:01」变回「未开启」
            runCatching { onExpire() }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remainingSeconds.value = 0L
    }
}
