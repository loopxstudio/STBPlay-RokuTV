package com.stbplay.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.stbplay.app.SourceProtocol

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val protocol: SourceProtocol,
    val url: String,
    val usernameOrMac: String = "",
    val password: String = "",
    val lastUpdated: Long = 0,
    val isActive: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "''") val epgUrl: String = ""
)