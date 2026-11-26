package com.am.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    /**
     * 歌单列表 + 首曲封面。
     *
     * 海报墙要给每个歌单一张图，封面取「position 最小的那首」的 `coverUrl`；
     * 本地曲目通常没有 coverUrl，退回它的 `uri`（相册封面/内嵌图由 Coil 自己取）。
     * 两个相关子查询都按歌单分组算一次，开销是一次全表扫，歌单数量在几十的量级。
     */
    @Query(
        "SELECT p.id AS id, p.name AS name, p.createdAt AS createdAt, " +
            "COUNT(ps.songId) AS songCount, " +
            "(SELECT x.coverUrl FROM playlist_songs x WHERE x.playlistId = p.id " +
            " ORDER BY x.position LIMIT 1) AS coverUrl, " +
            "(SELECT y.uri FROM playlist_songs y WHERE y.playlistId = p.id " +
            " ORDER BY y.position LIMIT 1) AS firstUri " +
            "FROM playlists p LEFT JOIN playlist_songs ps ON p.id = ps.playlistId " +
            "GROUP BY p.id ORDER BY p.createdAt DESC"
    )
    fun observePlaylistsWithCount(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun observePlaylist(playlistId: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun create(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    suspend fun rename(playlistId: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun delete(playlistId: Long)

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position")
    fun observeSongIds(playlistId: Long): Flow<List<String>>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position")
    fun observeSongs(playlistId: Long): Flow<List<PlaylistSongEntity>>

    @Query("SELECT playlistId FROM playlist_songs WHERE songId = :songId")
    suspend fun playlistIdsFor(songId: String): List<Long>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun songCount(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSong(entity: PlaylistSongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongs(entities: List<PlaylistSongEntity>)

    /**
     * 建歌单 + 一次性写入全部曲目，同一个事务里完成。
     *
     * 导入大歌单（实测 742 首）时如果分两次提交，中途被打断会留下一个「半个人」的歌单：
     * 名字有了、歌只进去三百首，用户还以为导入成功了。
     */
    @Transaction
    suspend fun createWithSongs(playlist: PlaylistEntity, rows: List<PlaylistSongEntity>): Long {
        val id = create(playlist)
        addSongs(rows.map { it.copy(playlistId = id) })
        return id
    }

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: Long, songId: String)

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun updatePosition(playlistId: Long, songId: String, position: Int)

    @Transaction
    suspend fun updatePositions(playlistId: Long, songIds: List<String>) {
        songIds.forEachIndexed { index, songId ->
            updatePosition(playlistId, songId, index)
        }
    }
}