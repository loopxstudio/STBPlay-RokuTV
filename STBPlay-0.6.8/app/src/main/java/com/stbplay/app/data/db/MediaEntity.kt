package com.stbplay.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index
import com.stbplay.app.model.MediaKind

@Entity(
    tableName = "media_items",
    primaryKeys = ["playlistId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["kind"]),
        Index(value = ["group"])
    ]
)
data class MediaEntity(
    val id: String, // format: "m3u-1234", "stalker-live-56", etc.
    val playlistId: Long,
    val name: String,
    val kind: MediaKind,
    val streamUrl: String,
    val group: String,
    val logo: String? = null,
    val tvgId: String? = null,
    val cmd: String? = null, // specific for stalker
    val isFavorite: Boolean = false // Simplifies favorites vs mapping tables for now
) {
    fun toModel(): com.stbplay.app.model.MediaItem {
        return com.stbplay.app.model.MediaItem(
            id = id,
            name = name,
            streamUrl = streamUrl,
            group = group,
            logo = logo,
            epgId = tvgId,
            kind = kind,
            cmd = cmd
        )
    }
}
