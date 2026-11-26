package com.am.music.data.recent

import com.am.music.data.db.RecentSongEntity
import com.am.music.data.db.RecentSongsDao
import com.am.music.data.db.toRecentSongEntity
import com.am.music.data.db.toSong
import com.am.music.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RecentlyPlayedRepository(private val dao: RecentSongsDao) {

    val count: Flow<Int> = dao.observeCount()

    /** 全部历史，不截断：听过的每一首都在，靠 [RecentSongEntity.playedAt] 排序。 */
    fun observeSongs(localLibrary: Map<String, Song>): Flow<List<Song>> =
        dao.observeRecent().map { rows -> rows.mapNotNull { it.toSong(localLibrary) } }

    suspend fun record(song: Song) {
        if (song.key.isBlank()) return
        dao.record(song.toRecentSongEntity())
    }

    suspend fun clear() = dao.clear()
}
