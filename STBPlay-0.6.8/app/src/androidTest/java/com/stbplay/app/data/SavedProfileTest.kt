package com.stbplay.app.data

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stbplay.app.MainViewModel
import com.stbplay.app.SourceProtocol
import com.stbplay.app.data.db.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class SavedProfileTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(10_000) {
        while (true) {
            var done = false
            main { done = condition() }
            if (done) break
            delay(20)
        }
    }

    @Test fun namedSaveUpdateRenameAndDeleteForEveryProtocol() = runBlocking {
        val context = instrumentation.targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("profile_test_$name", mode)
        }
        val prefs = isolated.getSharedPreferences("stbplay_preferences", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: MainViewModel
        main { vm = MainViewModel(IptvRepository(db), Preferences(isolated), db); store.put("test", vm) }
        try {
            for (protocol in SourceProtocol.entries) {
                val name = "Profile $protocol"
                main {
                    vm.newProfile()
                    vm.stalkerServer = "http://one.invalid/c/"; vm.stalkerMac = "00:1A:79:12:34:56"
                    vm.server = "http://one.invalid"; vm.username = "user"; vm.password = "pass"
                    vm.playlistUrl = "http://one.invalid/list.m3u"
                    vm.saveProfile(protocol, name)
                }
                waitUntil { vm.editingPlaylist?.name == name }
                val original = db.playlistDao().getAllPlaylists().first().single { it.name == name }
                main {
                    vm.editPlaylist(original)
                    vm.stalkerServer = "http://two.invalid/c/"; vm.server = "http://two.invalid"; vm.playlistUrl = "http://two.invalid/list.m3u"
                    vm.saveProfile(protocol, name)
                }
                withTimeout(10_000) { db.playlistDao().getAllPlaylists().first { rows -> rows.any { it.id == original.id && it.url.contains("two.invalid") } } }
                assertEquals(1, db.playlistDao().getAllPlaylists().first().size)
                main { vm.saveProfile(protocol, "$name copy") }
                waitUntil { vm.editingPlaylist?.name == "$name copy" }
                val rows = db.playlistDao().getAllPlaylists().first()
                assertEquals(2, rows.size)
                assertNotEquals(original.id, rows.single { it.name == "$name copy" }.id)
                for (row in rows) {
                    main { vm.deletePlaylist(row) }
                    withTimeout(10_000) { db.playlistDao().getAllPlaylists().first { list -> list.none { it.id == row.id } } }
                }
                waitUntil { vm.editingPlaylist == null }
            }
        } finally {
            main { store.clear() }
            db.close(); prefs.edit().clear().commit()
        }
    }

    @Test fun connectIsTemporaryAndEditedConnectAndReconnectKeepTheSameId() = runBlocking {
        val context = instrumentation.targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("connect_test_$name", mode)
        }
        val prefs = isolated.getSharedPreferences("stbplay_preferences", 0)
        prefs.edit().clear().commit()
        val server = ServerSocket(0)
        val body = "#EXTM3U\n#EXTINF:-1,Test channel\nhttp://example.invalid/live.ts\n"
        val serving = Thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n" + body).toByteArray())
                }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        val url = "http://127.0.0.1:${server.localPort}/list.m3u"
        val store = ViewModelStore()
        lateinit var vm: MainViewModel
        main { vm = MainViewModel(IptvRepository(db), Preferences(isolated), db); store.put("test", vm) }
        try {
            main { vm.playlistUrl = url; vm.connectSource(SourceProtocol.M3U) }
            waitUntil { !vm.loading }
            main { assertEquals(1, vm.items.size) }
            assertTrue(db.playlistDao().getAllPlaylists().first().isEmpty())
            main {
                assertEquals("CONNECTED", vm.connectionStatus)
                vm.toggleFavorite(vm.items.single().id)
                assertTrue(vm.needsProfileSave)
                vm.needsProfileSave = false
                vm.saveProfile(SourceProtocol.M3U, "ABC")
            }
            waitUntil { vm.editingPlaylist?.name == "ABC" }
            val saved = db.playlistDao().getAllPlaylists().first().single()
            waitUntil { vm.activePlaylist?.id == saved.id && !vm.loading }
            withTimeout(10_000) { db.favoriteDao().observe(saved.id).first { it.isNotEmpty() } }
            main { vm.editPlaylist(saved); vm.playlistUrl = "$url?edited=1"; vm.connectSource(SourceProtocol.M3U) }
            waitUntil { !vm.loading }
            val updated = db.playlistDao().getAllPlaylists().first().single()
            assertEquals(saved.id, updated.id); assertEquals("ABC", updated.name)
            assertEquals("$url?edited=1", updated.url)
            main { assertEquals(1, vm.items.size); vm.refreshPlaylist(updated) }
            waitUntil { !vm.loading }
            assertEquals(saved.id, db.playlistDao().getAllPlaylists().first().single().id)
            main { assertEquals(1, vm.items.size) }
        } finally {
            main { store.clear() }
            server.close(); serving.join(1000)
            db.close(); prefs.edit().clear().commit()
        }
    }
}
