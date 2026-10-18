package com.stbplay.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg_programs WHERE playlistId = :playlistId AND channelId = :channelId AND endMillis > :currentTimeMillis ORDER BY startMillis ASC")
    fun getProgramsForChannel(playlistId: Long, channelId: String, currentTimeMillis: Long): Flow<List<EpgProgramEntity>>

    @Query("SELECT * FROM epg_programs WHERE playlistId = :playlistId AND channelId = :channelId AND startMillis <= :currentTimeMillis AND endMillis > :currentTimeMillis LIMIT 1")
    fun getCurrentProgram(playlistId: Long, channelId: String, currentTimeMillis: Long): Flow<EpgProgramEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(programs: List<EpgProgramEntity>): List<Long>

    @Query("DELETE FROM epg_programs WHERE endMillis < :currentTimeMillis")
    suspend fun clearOldPrograms(currentTimeMillis: Long): Int

    @Query("DELETE FROM epg_programs WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long): Int

    @Query("SELECT * FROM epg_programs WHERE playlistId = :playlistId AND endMillis > :now ORDER BY startMillis")
    fun upcoming(playlistId: Long, now: Long): Flow<List<EpgProgramEntity>>
}