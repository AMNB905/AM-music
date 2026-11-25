package com.am.music.ui.components

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import com.am.music.AppContainer
import com.am.music.playback.PlaybackNotice
import com.am.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 按帧推进播放位置。
 *
 * 播放器的位置回调是 500ms 一次，直接拿来画进度条就是一跳一跳的。
 * 这里以最近一次回调为锚点，用帧时间往上补，屏幕刷新率下进度是连续走的；
 * 每次回调到达就把锚点重设，长按拖动、暂停、切歌都会自动校准。
 */
@Composable
fun rememberSmoothPosition(positionMs: Long, isPlaying: Boolean): Long {
    var value by remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(isPlaying, positionMs) {
        value = positionMs
        if (!isPlaying) return@LaunchedEffect
        val startFrame = withFrameMillis { it }
        while (true) {
            val frame = withFrameMillis { it }
            value = positionMs + (frame - startFrame)
        }
    }
    return value
}

/**
 * 正在解析、还没出声的那首歌。
 *
 * 点播在线上曲目时要先取直链（快则几百毫秒，慢则一两秒），期间界面上什么都不变，
 * 很容易被当成「点了没反应」。这里把这件事抽成一条全局状态，由根布局显示
 * 「正在准备『歌名』」的一行提示，出声即消失。
 */
object PlaybackPending {
    private val _song = MutableStateFlow<Song?>(null)
    val song: StateFlow<Song?> = _song.asStateFlow()

    fun set(value: Song?) {
        _song.value = value
    }
}

/**
 * 歌单 / 收藏 / 队列里点播的统一入口。
 *
 * 本地曲目直接入队；在线曲目先解析被点的那一首立刻出声，其余后台补进队列
 * （和在线页同一套「先出声、后补队」策略，避免歌单里混了在线曲时点一下没反应）。
 */
@Composable
fun rememberPlayAction(
    container: AppContainer,
    onFailed: (String) -> Unit = {},
): (List<Song>, Int) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(container) {
        { songs: List<Song>, index: Int ->
            val song = songs.getOrNull(index)
            if (song == null) {
                Unit
            } else {
                scope.launch {
                    PlaybackPending.set(song)
                    try {
                        val resolved = withContext(Dispatchers.IO) {
                            when {
                                song.uri != Uri.EMPTY -> song
                                song.isOnline -> container.onlineMusicRepository.resolve(song)
                                else -> null
                            }
                        }
                        if (resolved == null) {
                            // 解析不出地址（多半是断网、或缓存只有半首）时必须出声，
                            // 否则点歌在用户眼里就是「点了没反应」
                            val reason = if (song.isOnline) {
                                "连不上服务器，检查下网络"
                            } else {
                                "没可播地址：离线只能放缓存/本地的歌"
                            }
                            PlaybackNotice.brief(reason)
                            onFailed(reason)
                            return@launch
                        }
                        container.playbackManager.playSongs(listOf(resolved), 0)
                        prefetch(container, songs, index, resolved.key)
                    } finally {
                        // 出声（或失败）就把提示收掉；prefetch 是后台的，不该继续占着这一行
                        PlaybackPending.set(null)
                    }
                }
            }
        }
    }
}

/** 首曲已在放，后台按 4 首一批把后面的在线曲补进队列；用户切歌就停。 */
private suspend fun prefetch(
    container: AppContainer,
    songs: List<Song>,
    index: Int,
    anchorKey: String,
) = coroutineScope {
    val rest = songs.drop(index + 1).take(PREFETCH_COUNT)
    for (chunk in rest.chunked(PREFETCH_PARALLELISM)) {
        if (container.playbackManager.state.value.currentSong?.key != anchorKey) return@coroutineScope
        val resolved = chunk.map { candidate ->
            async {
                when {
                    candidate.uri != Uri.EMPTY -> candidate
                    candidate.isOnline -> container.onlineMusicRepository.resolve(candidate)
                    else -> null
                }
            }
        }.awaitAll().filterNotNull()
        if (resolved.isEmpty()) continue
        if (container.playbackManager.state.value.currentSong?.key != anchorKey) return@coroutineScope
        container.playbackManager.appendSongs(resolved)
    }
}

private const val PREFETCH_COUNT = 20
private const val PREFETCH_PARALLELISM = 4
