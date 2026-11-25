package com.am.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentSongsDao {

    @Query("SELECT * FROM recent_songs ORDER BY playedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentSongEntity>>

    @Query("SELECT COUNT(*) FROM recent_songs")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(entity: RecentSongEntity)

    @Query("DELETE FROM recent_songs")
    suspend fun clear()

    @Query(
        "DELETE FROM recent_songs WHERE songId NOT IN " +
            "(SELECT songId FROM recent_songs ORDER BY playedAt DESC LIMIT :keep)"
    )
    suspend fun trim(keep: Int)
}
