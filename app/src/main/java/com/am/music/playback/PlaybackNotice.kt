package com.am.music.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 播放链路的「一句话回执」。
 *
 * 只解决一件事：当一条歌没能顺利播下去（缓存不完整、直链失效、网络断了、卡在缓冲），
 * 用户必须看得见一句话，而不是对着静止的进度条猜。根布局在迷你播放器上方显示它。
 *
 * 不做队列、不做历史：新的覆盖旧的，过几秒自动消失。
 */
object PlaybackNotice {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun set(value: String?) {
        _message.value = value
    }

    /** 说一句话，几秒后自己收掉；后到的覆盖先到的。 */
    fun brief(text: String, holdMs: Long = 3_400L) {
        set(text)
        scope.launch {
            delay(holdMs)
            // 期间又来了新的提示就不抢它的位置
            if (_message.value == text) set(null)
        }
    }
}
