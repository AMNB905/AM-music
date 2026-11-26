package com.am.music.data.favorites

import com.am.music.data.db.FavoriteEntity
import com.am.music.data.db.FavoritesDao
import com.am.music.data.db.toFavoriteEntity
import com.am.music.data.db.toSong
import com.am.music.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FavoritesRepository(private val dao: FavoritesDao) {

    val favoriteIds: Flow<List<String>> = dao.observeIds()

    /** 收藏列表（含在线曲目）：本地曲目用传入的本地曲库刷新，其余按库里存的元数据还原。 */
    fun observeSongs(localLibrary: Map<String, Song>): Flow<List<Song>> =
        dao.observeAll().map { entries -> entries.mapNotNull { it.toSong(localLibrary) } }

    suspend fun isFavorite(songId: String): Boolean = dao.isFavorite(songId)

    /** 一次清空：收藏页给「清空」，逐首点取消太慢。 */
    suspend fun clearAll() = dao.clearAll()

    suspend fun toggle(songId: String): Boolean {
        return if (dao.isFavorite(songId)) {
            dao.remove(songId)
            false
        } else {
            dao.add(FavoriteEntity(songId, System.currentTimeMillis()))
            true
        }
    }

    /** 收藏时把曲目信息一起落库，这样在线曲目重开 App 也还在、也能播。 */
    suspend fun toggle(song: Song): Boolean {
        return if (dao.isFavorite(song.key)) {
            dao.remove(song.key)
            false
        } else {
            dao.add(song.toFavoriteEntity())
            true
        }
    }
}
