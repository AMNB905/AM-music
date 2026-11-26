package com.am.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentSongsDao {

    /**
     * 全部播放记录，按时间倒序，没有 LIMIT。
     *
     * 原来这条查询带 `LIMIT :limit`、写入后还会 `trim(50)`，于是听过第 51 首，
     * 第 1 首就永久消失。现在不裁：一行只有 songId/时间/标题这些短字段，
     * 攒到几千首也就几百 KB，换来「翻了三个月前听过的歌还能找回来」。
     */
    @Query("SELECT * FROM recent_songs ORDER BY playedAt DESC")
    fun observeRecent(): Flow<List<RecentSongEntity>>

    @Query("SELECT COUNT(*) FROM recent_songs")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(entity: RecentSongEntity)

    @Query("DELETE FROM recent_songs")
    suspend fun clear()
}
