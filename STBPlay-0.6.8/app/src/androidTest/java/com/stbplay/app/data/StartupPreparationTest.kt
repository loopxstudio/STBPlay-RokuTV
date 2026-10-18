package com.stbplay.app.data

import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stbplay.app.data.db.*
import com.stbplay.app.model.MediaKind
import com.stbplay.app.SourceProtocol
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupPreparationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open(name: String, protected: Boolean) = Room.databaseBuilder(context, AppDatabase::class.java, name).apply {
        if (protected) addCallback(ProtectedDatabaseMigration)
    }.build()
    private fun version(db: AppDatabase) = db.openHelper.writableDatabase.query("SELECT protectionVersion FROM stbplay_storage_state WHERE id=1").use { it.moveToFirst(); it.getInt(0) }

    @Test fun legacyUpgradeAndInterruptedCleanupPreserveProfilesAndCatalogue() = runBlocking<Unit> {
        val name = "startup-legacy-0673.db"
        context.deleteDatabase(name)
        try {
            val source = PlaylistEntity(name="Saved", protocol=SourceProtocol.XTREAM, url="http://provider.invalid", usernameOrMac="user", password="private", isActive=true)
            val seed = open(name, false)
            val id = seed.playlistDao().insertStored(source)
            seed.mediaDao().insertAllStored((0 until 2000).map {
                MediaEntity("item-$it", id, "Item $it", MediaKind.MOVIE, "http://provider.invalid/$it.mp4", "Movies", null, null, null)
            })
            seed.close()
            val upgraded = open(name, true)
            assertEquals("private", upgraded.playlistDao().getPlaylistById(id)!!.password)
            assertEquals(2000, upgraded.mediaDao().getAll(id).first().size)
            val raw = upgraded.playlistDao().getPlaylistByIdStored(id)!!.password
            assertTrue(SecureValues.encrypted(raw))
            assertEquals(2, version(upgraded))
            // Model process death after encrypted rows committed but before compaction.
            upgraded.openHelper.writableDatabase.execSQL("UPDATE stbplay_storage_state SET protectionVersion=1 WHERE id=1")
            upgraded.close()
            val recovered = open(name, true)
            assertEquals("private", recovered.playlistDao().getPlaylistById(id)!!.password)
            assertEquals(raw, recovered.playlistDao().getPlaylistByIdStored(id)!!.password)
            assertEquals(2000, recovered.mediaDao().getAll(id).first().size)
            assertEquals(2, version(recovered))
            recovered.close()
        } finally { context.deleteDatabase(name) }
    }

    @Test fun alreadyProtectedLargeCatalogueOpensRepeatedlyWithoutReprocessingValues() = runBlocking<Unit> {
        val name = "startup-large-0673.db"
        context.deleteDatabase(name)
        try {
            val seed = open(name, false)
            val id = seed.playlistDao().insert(PlaylistEntity(name="Large", protocol=SourceProtocol.XTREAM, url="http://provider.invalid", usernameOrMac="user", password="private", isActive=true))
            val encryptedUrl = SecureValues.encrypt("http://provider.invalid/episode.mp4")
            seed.mediaDao().insertAllStored((0 until 20000).map {
                MediaEntity("item-$it", id, "Item $it", MediaKind.EPISODE, encryptedUrl, "Season 1", null, null, null)
            })
            seed.close()
            repeat(3) {
                val start = SystemClock.elapsedRealtime()
                val reopened = open(name, true)
                assertEquals(2, version(reopened))
                reopened.openHelper.writableDatabase.query("SELECT count(*),min(streamUrl),max(streamUrl) FROM media_items").use { rows ->
                    assertTrue(rows.moveToFirst()); assertEquals(20000, rows.getInt(0))
                    assertEquals(encryptedUrl, rows.getString(1)); assertEquals(encryptedUrl, rows.getString(2))
                }
                val elapsed = SystemClock.elapsedRealtime() - start
                android.util.Log.i("STBPlayStartupTest", "Protected 20000-item catalogue open ${it+1}: ${elapsed}ms")
                assertTrue("Protected cache should open without a lengthy preparation pass ($elapsed ms)", elapsed < 15_000)
                reopened.close()
            }
        } finally { context.deleteDatabase(name) }
    }
}
