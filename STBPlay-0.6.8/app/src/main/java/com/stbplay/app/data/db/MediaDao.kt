package com.stbplay.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.stbplay.app.model.MediaKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_items WHERE playlistId = :playlistId AND kind = :kind ORDER BY name ASC")
    fun getMediaByKindStored(playlistId: Long, kind: MediaKind): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_items WHERE playlistId = :playlistId AND kind = :kind AND `group` = :groupName ORDER BY name ASC")
    fun getMediaByKindAndGroupStored(playlistId: Long, kind: MediaKind, groupName: String): Flow<List<MediaEntity>>

    @Query("SELECT DISTINCT `group` FROM media_items WHERE playlistId = :playlistId AND kind = :kind ORDER BY `group` ASC")
    fun getGroupsByKind(playlistId: Long, kind: MediaKind): Flow<List<String>>

    @Query("SELECT * FROM media_items WHERE playlistId = :playlistId AND name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchMediaStored(playlistId: Long, query: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_items WHERE playlistId = :playlistId AND isFavorite = 1 ORDER BY name ASC")
    fun getFavoritesStored(playlistId: Long): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_items WHERE playlistId = :playlistId ORDER BY name ASC")
    fun getAllStored(playlistId: Long): Flow<List<MediaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllStored(mediaItems: List<MediaEntity>): List<Long>

    @Query("UPDATE media_items SET isFavorite = :isFavorite WHERE playlistId = :playlistId AND id = :id")
    suspend fun updateFavorite(playlistId: Long, id: String, isFavorite: Boolean): Int

    @Query("DELETE FROM media_items WHERE playlistId = :playlistId AND kind = :kind")
    suspend fun deleteByKind(playlistId: Long, kind: MediaKind): Int
    fun getMediaByKind(playlistId: Long, kind: MediaKind) = getMediaByKindStored(playlistId, kind).map { it.map { row -> row.reveal() } }.flowOn(Dispatchers.IO)
    fun getMediaByKindAndGroup(playlistId: Long, kind: MediaKind, groupName: String) = getMediaByKindAndGroupStored(playlistId, kind, groupName).map { it.map { row -> row.reveal() } }.flowOn(Dispatchers.IO)
    fun searchMedia(playlistId: Long, query: String) = searchMediaStored(playlistId, query).map { it.map { row -> row.reveal() } }.flowOn(Dispatchers.IO)
    fun getFavorites(playlistId: Long) = getFavoritesStored(playlistId).map { it.map { row -> row.reveal() } }.flowOn(Dispatchers.IO)
    fun getAll(playlistId: Long) = getAllStored(playlistId).map { it.map { row -> row.reveal() } }.flowOn(Dispatchers.IO)
    suspend fun insertAll(mediaItems: List<MediaEntity>) = insertAllStored(mediaItems.map { it.protect() })
}