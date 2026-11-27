package com.am.music.playback

import android.content.Context
import android.os.SystemClock

/**
 * 「上次听到哪」的写盘节奏。
 *
 * 从 PlaybackManager 里拆出来的原因：落盘策略（节流、换歌强制、超长队列截取）
 * 跟播放状态机本身是两件独立的事，而且这块是唯一会碰磁盘的路径，
 * 单独一处好审、也好换（以后要换成 DataStore 只改这个文件）。
 *
 * 规则：
 * - 换歌、暂停/停止 → 立刻写；
 * - 播放中 → 3 秒最多写一次（位置每 500ms 更新一次，不节流就是每 2 秒碰一次盘）；
 * - 队列超过 [PlaybackResume.MAX_QUEUE] → 取当前前后一段，保顺序、保「接着放」。
 */
class PlaybackSnapshotter(context: Context) {

    private val appContext = context.applicationContext

    /** 恢复流程走完之前不许写，否则会把「刚恢复的空状态」盖掉真数据。 */
    var enabled: Boolean = false

    private var lastSongKey: String? = null
    private var lastPersistAt = 0L

    fun save(state: PlaybackState) {
        if (!enabled) return
        val song = state.currentSong
        if (song == null) {
            if (lastSongKey != null) {
                PlaybackResume.save(appContext, null, 0L, emptyList(), 0)
                lastSongKey = null
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        val force = song.key != lastSongKey || !state.isPlaying
        if (!force && now - lastPersistAt < PERSIST_INTERVAL_MS) return
        lastPersistAt = now
        lastSongKey = song.key

        val window = trim(state.queue, state.currentIndex)
        PlaybackResume.save(appContext, song, state.positionMs, window.first, window.second)
    }

    fun load(): PlaybackResume.Snapshot? = PlaybackResume.load(appContext)

    /** 700 首的歌单没法整条落盘：截当前前后一段，顺序仍然是对的。 */
    private fun trim(queue: List<com.am.music.data.model.Song>, index: Int): Pair<List<com.am.music.data.model.Song>, Int> {
        if (queue.size <= PlaybackResume.MAX_QUEUE) return queue to index
        val start = (index - HEAD_KEEP).coerceIn(0, (queue.size - PlaybackResume.MAX_QUEUE).coerceAtLeast(0))
        val end = (start + PlaybackResume.MAX_QUEUE).coerceAtMost(queue.size)
        return queue.subList(start, end) to (index - start)
    }

    private companion object {
        const val PERSIST_INTERVAL_MS = 3_000L

        /** 截断时当前曲目前面留几首：够往回切几下，又不至于占满预算。 */
        const val HEAD_KEEP = 40
    }
}
