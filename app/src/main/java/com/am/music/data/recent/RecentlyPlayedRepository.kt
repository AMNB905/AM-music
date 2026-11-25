package com.am.music.data.recent

import com.am.music.data.db.RECENT_LIMIT
import com.am.music.data.db.RecentSongEntity
import com.am.music.data.db.RecentSongsDao
import com.am.music.data.db.toRecentSongEntity
import com.am.music.data.db.toSong
import com.am.music.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RecentlyPlayedRepository(private val dao: RecentSongsDao) {

    val count: Flow<Int> = dao.observeCount()

    fun observeSongs(localLibrary: Map<String, Song>, limit: Int = RECENT_LIMIT): Flow<List<Song>> =
        dao.observeRecent(limit).map { rows -> rows.mapNotNull { it.toSong(localLibrary) } }

    suspend fun record(song: Song) {
        if (song.key.isBlank()) return
        dao.record(song.toRecentSongEntity())
        dao.trim(RECENT_LIMIT)
    }

    suspend fun clear() = dao.clear()
}
