package com.am.music.data.playlist

import com.am.music.data.db.PlaylistDao
import com.am.music.data.db.PlaylistEntity
import com.am.music.data.db.PlaylistSongEntity
import com.am.music.data.db.PlaylistWithCount
import com.am.music.data.db.toPlaylistSongEntity
import com.am.music.data.db.toSong
import com.am.music.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PlaylistRepository(private val dao: PlaylistDao) {

    fun observePlaylists(): Flow<List<PlaylistWithCount>> = dao.observePlaylistsWithCount()

    fun observePlaylist(playlistId: Long): Flow<PlaylistEntity?> = dao.observePlaylist(playlistId)

    fun observeSongIds(playlistId: Long): Flow<List<String>> = dao.observeSongIds(playlistId)

    /** 歌单曲目（含在线曲目），本地曲目用传入的本地曲库刷新。 */
    fun observeSongs(playlistId: Long, localLibrary: Map<String, Song>): Flow<List<Song>> =
        dao.observeSongs(playlistId).map { rows -> rows.mapNotNull { it.toSong(localLibrary) } }

    suspend fun create(name: String): Long =
        dao.create(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))

    /**
     * 建一个带曲目的歌单（导入用）。
     *
     * 曲目按传入顺序落 position；同一首歌重复出现时只留第一条（`playlist_songs` 的主键是
     * 「歌单 + songId」，上游歌单里重复收同一首是常事，重复插会被 REPLACE 顶掉并打乱顺序）。
     */
    suspend fun createWithSongs(name: String, songs: List<Song>): Long {
        val now = System.currentTimeMillis()
        val unique = songs.distinctBy { it.key }
        val rows = unique.mapIndexed { index, song ->
            song.toPlaylistSongEntity(playlistId = 0, position = index).copy(addedAt = now)
        }
        return dao.createWithSongs(
            PlaylistEntity(name = name, createdAt = now),
            rows,
        )
    }

    suspend fun rename(playlistId: Long, name: String) = dao.rename(playlistId, name)

    /** 自定义封面：传 null = 恢复默认（海报墙退回第一首歌的封面）。文件生命周期由调用方管。 */
    suspend fun setCover(playlistId: Long, path: String?) = dao.updateCoverPath(playlistId, path)

    suspend fun delete(playlistId: Long) = dao.delete(playlistId)

    suspend fun addSong(playlistId: Long, song: Song) {
        val position = dao.songCount(playlistId)
        dao.addSong(song.toPlaylistSongEntity(playlistId, position))
    }

    suspend fun addSong(playlistId: Long, songId: String) {
        val position = dao.songCount(playlistId)
        dao.addSong(
            PlaylistSongEntity(
                playlistId = playlistId,
                songId = songId,
                position = position,
                addedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun removeSong(playlistId: Long, songId: String) = dao.removeSong(playlistId, songId)

    suspend fun reorder(playlistId: Long, songIds: List<String>) =
        dao.updatePositions(playlistId, songIds)

    suspend fun playlistIdsFor(songId: String): List<Long> = dao.playlistIdsFor(songId)
}
