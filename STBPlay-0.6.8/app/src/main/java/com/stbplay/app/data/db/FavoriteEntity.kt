package com.stbplay.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

@Entity(tableName = "favorite_items", primaryKeys = ["playlistId", "id"],
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE)])
data class FavoriteEntity(val playlistId: Long, val id: String, val payload: String)

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite_items WHERE playlistId = :playlistId")
    fun observeStored(playlistId: Long): Flow<List<FavoriteEntity>>
    @Query("SELECT * FROM favorite_items WHERE playlistId = :playlistId")
    suspend fun getSavedStored(playlistId: Long): List<FavoriteEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveStored(items: List<FavoriteEntity>)
    @Query("DELETE FROM favorite_items WHERE playlistId = :playlistId AND id = :id")
    suspend fun remove(playlistId: Long, id: String)
    fun observe(playlistId: Long) = observeStored(playlistId).map { it.map { row -> row.reveal() } }.flowOn(Dispatchers.IO)
    suspend fun getSaved(playlistId: Long) = getSavedStored(playlistId).map { it.reveal() }
    suspend fun save(items: List<FavoriteEntity>) = saveStored(items.map { it.protect() })
}
