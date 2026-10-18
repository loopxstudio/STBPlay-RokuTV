package com.stbplay.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id ASC")
    fun getAllPlaylistsStored(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE isActive = 1 LIMIT 1")
    fun getActivePlaylistStored(): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE isActive = 1 LIMIT 1")
    suspend fun getActivePlaylistSyncStored(): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistByIdStored(id: Long): PlaylistEntity?

    @Insert
    suspend fun insertStored(playlist: PlaylistEntity): Long

    @Update
    suspend fun updateStored(playlist: PlaylistEntity): Int

    @Delete
    suspend fun deleteStored(playlist: PlaylistEntity): Int

    @Query("UPDATE playlists SET isActive = 0")
    suspend fun clearActivePlaylist(): Int

    @Query("UPDATE playlists SET isActive = 1 WHERE id = :id")
    suspend fun setActivePlaylist(id: Long): Int
    fun getAllPlaylists() = getAllPlaylistsStored().map { rows -> rows.map { it.reveal() } }.flowOn(Dispatchers.IO)
    fun getActivePlaylist() = getActivePlaylistStored().map { it?.reveal() }.flowOn(Dispatchers.IO)
    suspend fun getActivePlaylistSync() = getActivePlaylistSyncStored()?.reveal()
    suspend fun getPlaylistById(id: Long) = getPlaylistByIdStored(id)?.reveal()
    suspend fun insert(playlist: PlaylistEntity) = insertStored(playlist.protect())
    suspend fun update(playlist: PlaylistEntity) = updateStored(playlist.protect())
    suspend fun delete(playlist: PlaylistEntity) = deleteStored(playlist)
}