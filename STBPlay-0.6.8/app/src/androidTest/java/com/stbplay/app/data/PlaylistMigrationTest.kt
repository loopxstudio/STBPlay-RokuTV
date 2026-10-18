package com.stbplay.app.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stbplay.app.data.db.*
import com.stbplay.app.SourceProtocol
import com.stbplay.app.model.MediaItem
import com.stbplay.app.model.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistMigrationTest {
    @Test fun migrateAndKeepPlaylistsFavoritesAndGuideIsolated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "feature-migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `protocol` TEXT NOT NULL, `url` TEXT NOT NULL, `usernameOrMac` TEXT NOT NULL, `password` TEXT NOT NULL, `lastUpdated` INTEGER NOT NULL, `isActive` INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE IF NOT EXISTS `media_items` (`id` TEXT NOT NULL, `playlistId` INTEGER NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `streamUrl` TEXT NOT NULL, `group` TEXT NOT NULL, `logo` TEXT, `tvgId` TEXT, `cmd` TEXT, `isFavorite` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            old.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_playlistId` ON `media_items` (`playlistId`)")
            old.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_kind` ON `media_items` (`kind`)")
            old.execSQL("CREATE INDEX IF NOT EXISTS `index_media_items_group` ON `media_items` (`group`)")
            old.execSQL("CREATE TABLE IF NOT EXISTS `epg_programs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `channelId` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `startMillis` INTEGER NOT NULL, `endMillis` INTEGER NOT NULL)")
            old.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programs_channelId_startMillis` ON `epg_programs` (`channelId`, `startMillis`)")
            old.execSQL("CREATE INDEX IF NOT EXISTS `index_epg_programs_channelId` ON `epg_programs` (`channelId`)")
            old.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            old.execSQL("INSERT INTO playlists VALUES(1,'First','M3U','https://one.invalid','','',0,1)")
            old.execSQL("INSERT INTO media_items VALUES('same',1,'One','LIVE','https://one.invalid/live','Group',NULL,'channel',NULL,1)")
            old.execSQL("INSERT INTO epg_programs VALUES(1,'channel','Show',NULL,10,20)")
            old.version = 2
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_2_3).build()
        try {
            assertEquals("One", database.mediaDao().getAll(1).first().single().name)
            assertTrue(database.mediaDao().getAll(1).first().single().isFavorite)
            assertEquals(1L, database.epgDao().upcoming(1, 0).first().single().playlistId)
            database.mediaDao().updateFavorite(1, "same", false)
            val testContext = object : android.content.ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                    context.getSharedPreferences("feature_test_$name", mode)
            }
            val preferences = Preferences(testContext)
            testContext.getSharedPreferences("stbplay_preferences", 0).edit().clear().commit()
            preferences.saveSource("M3U", com.stbplay.app.model.StalkerCredentials("", ""), com.stbplay.app.model.XtreamCredentials("", "", ""), "https://one.invalid")
            preferences.saveFavorites(setOf("same"))
            val store = androidx.lifecycle.ViewModelStore()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                store.put("migration", com.stbplay.app.MainViewModel(IptvRepository(database), preferences, database))
            }
            try {
                val migrated = kotlinx.coroutines.withTimeout(10_000) { database.favoriteDao().observe(1).first { it.isNotEmpty() } }
                assertEquals("same", migrated.single().id)
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
                testContext.getSharedPreferences("stbplay_preferences", 0).edit().clear().commit()
            }
            val second = database.playlistDao().insert(PlaylistEntity(name="Second", protocol=SourceProtocol.M3U, url="https://two.invalid"))
            val repo = IptvRepository(database)
            repo.cacheMedia(second, MediaKind.LIVE, listOf(MediaItem("same", "Two", "https://two.invalid/live")))
            database.favoriteDao().save(listOf(FavoriteEntity(1,"same","[]")))
            database.favoriteDao().save(listOf(FavoriteEntity(second,"same","[]")))
            repo.cacheMedia(1, MediaKind.LIVE, listOf(MediaItem("same", "One refreshed", "https://one.invalid/live")))
            assertEquals("One refreshed", FavoriteCodec.decode(database.favoriteDao().observe(1).first().single().payload).single().name)
            assertEquals("Two", database.mediaDao().getAll(second).first().single().name)
            database.withTransaction {
                database.playlistDao().clearActivePlaylist()
                database.playlistDao().setActivePlaylist(second)
            }
            assertEquals(second, database.playlistDao().getActivePlaylistSync()?.id)
            database.epgDao().insertAll(listOf(EpgProgramEntity(channelId="channel",title="Second show",description=null,startMillis=10,endMillis=20,playlistId=second)))
            database.epgDao().clearPlaylist(second)
            assertEquals(1, database.epgDao().upcoming(1,0).first().size)
            database.playlistDao().getPlaylistById(second)?.let { database.playlistDao().delete(it) }
            assertTrue(database.favoriteDao().observe(second).first().isEmpty())
            assertEquals(1, database.favoriteDao().observe(1).first().size)
            database.close()
            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_2_3).build()
            try {
                assertEquals("One refreshed", reopened.mediaDao().getAll(1).first().single().name)
                assertEquals("One refreshed", FavoriteCodec.decode(reopened.favoriteDao().observe(1).first().single().payload).single().name)
            } finally { reopened.close() }
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
