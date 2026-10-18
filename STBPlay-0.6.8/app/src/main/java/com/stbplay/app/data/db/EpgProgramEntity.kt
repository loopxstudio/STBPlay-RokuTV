package com.stbplay.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "epg_programs",
    indices = [
        Index(value = ["playlistId", "channelId", "startMillis"]),
        Index(value = ["channelId"])
    ]
)
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val channelId: String,
    val title: String,
    val description: String?,
    val startMillis: Long,
    val endMillis: Long,
    @androidx.room.ColumnInfo(defaultValue = "0") val playlistId: Long = 0
)
