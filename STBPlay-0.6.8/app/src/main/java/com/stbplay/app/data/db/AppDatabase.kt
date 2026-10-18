package com.stbplay.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.ColumnInfo
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PlaylistEntity::class, MediaEntity::class, EpgProgramEntity::class, FavoriteEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun mediaDao(): MediaDao
    abstract fun epgDao(): EpgDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "stbplay.db")
                .addMigrations(MIGRATION_2_3).addCallback(ProtectedDatabaseMigration).build().also { instance = it }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN epgUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE media_items_new (id TEXT NOT NULL, playlistId INTEGER NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, streamUrl TEXT NOT NULL, `group` TEXT NOT NULL, logo TEXT, tvgId TEXT, cmd TEXT, isFavorite INTEGER NOT NULL, PRIMARY KEY(playlistId,id), FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO media_items_new SELECT * FROM media_items")
                db.execSQL("DROP TABLE media_items")
                db.execSQL("ALTER TABLE media_items_new RENAME TO media_items")
                db.execSQL("CREATE INDEX index_media_items_playlistId ON media_items(playlistId)")
                db.execSQL("CREATE INDEX index_media_items_kind ON media_items(kind)")
                db.execSQL("CREATE INDEX index_media_items_group ON media_items(`group`)")
                db.execSQL("ALTER TABLE epg_programs ADD COLUMN playlistId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE epg_programs SET playlistId = COALESCE((SELECT id FROM playlists WHERE isActive=1 LIMIT 1),0)")
                db.execSQL("DROP INDEX index_epg_programs_channelId_startMillis")
                db.execSQL("CREATE INDEX index_epg_programs_playlistId_channelId_startMillis ON epg_programs(playlistId,channelId,startMillis)")
                db.execSQL("CREATE TABLE favorite_items (playlistId INTEGER NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(playlistId,id), FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
    }
}
