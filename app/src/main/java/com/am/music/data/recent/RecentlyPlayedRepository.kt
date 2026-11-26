package com.am.music.data.recent

import com.am.music.data.db.RecentSongEntity
import com.am.music.data.db.RecentSongsDao
import com.am.music.data.db.toRecentSongEntity
import com.am.music.data.db.toSong
import com.am.music.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 一条播放记录：歌 + 什么时候听的。最近页要按天分段，所以时间得跟着走。 */
data class PlayedSong(val song: Song, val playedAt: Long)

class RecentlyPlayedRepository(private val dao: RecentSongsDao) {

    val count: Flow<Int> = dao.observeCount()

    /** 全部历史，不截断：听过的每一首都在，靠 [RecentSongEntity.playedAt] 排序。 */
    fun observeSongs(localLibrary: Map<String, Song>): Flow<List<Song>> =
        dao.observeRecent().map { rows -> rows.mapNotNull { it.toSong(localLibrary) } }

    fun observeEntries(localLibrary: Map<String, Song>): Flow<List<PlayedSong>> =
        dao.observeRecent().map { rows ->
            rows.mapNotNull { entity -> entity.toSong(localLibrary)?.let { PlayedSong(it, entity.playedAt) } }
        }

    suspend fun record(song: Song) {
        if (song.key.isBlank()) return
        dao.record(song.toRecentSongEntity())
    }

    suspend fun clear() = dao.clear()
}
