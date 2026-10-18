package com.stbplay.app.data

import android.content.ContextWrapper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stbplay.app.*
import com.stbplay.app.data.db.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SourcesAndPlayerUpdateTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private suspend fun settled(vm: MainViewModel) = withTimeout(10_000) {
        while (true) {
            var loading = true
            main { loading = vm.loading }
            if (!loading) break
            delay(20)
        }
    }

    @Test fun fourProfileLimitAllowsUpdatesAndDeleteFreesASlot() = runBlocking {
        val context = instrumentation.targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("limit_test_$name", mode)
        }
        isolated.getSharedPreferences("stbplay_preferences", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: MainViewModel
        main { vm = MainViewModel(IptvRepository(db), Preferences(isolated), db); store.put("test", vm) }
        try {
            for (i in 1..4) {
                main { vm.newProfile(); vm.playlistUrl = "https://example.invalid/$i.m3u"; vm.saveProfile(SourceProtocol.M3U, "Profile $i") }
                settled(vm)
            }
            assertEquals(4, db.playlistDao().getAllPlaylists().first().size)
            main { vm.newProfile(); vm.playlistUrl = "https://example.invalid/5.m3u"; vm.saveProfile(SourceProtocol.M3U, "Fifth") }
            settled(vm)
            assertEquals(4, db.playlistDao().getAllPlaylists().first().size)
            main { assertTrue(vm.message.contains("up to 4")) }
            val original = db.playlistDao().getAllPlaylists().first().first()
            main { vm.editPlaylist(original); vm.playlistUrl = "https://example.invalid/changed.m3u"; vm.saveProfile(SourceProtocol.M3U, original.name) }
            settled(vm)
            assertEquals("https://example.invalid/changed.m3u", db.playlistDao().getPlaylistById(original.id)?.url)
            assertEquals(4, db.playlistDao().getAllPlaylists().first().size)
            main { vm.saveProfile(SourceProtocol.M3U, "Renamed copy") }
            settled(vm)
            assertEquals(4, db.playlistDao().getAllPlaylists().first().size)
            main { vm.deletePlaylist(original) }
            withTimeout(10_000) { db.playlistDao().getAllPlaylists().first { it.size == 3 } }
            main { vm.newProfile(); vm.playlistUrl = "https://example.invalid/new.m3u"; vm.saveProfile(SourceProtocol.M3U, "Replacement") }
            settled(vm)
            assertEquals(4, db.playlistDao().getAllPlaylists().first().size)
        } finally { main { store.clear() }; db.close() }
    }

    @Test fun sourceCardsStartCollapsedToggleAndStayExclusive() {
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: MainViewModel
        main { vm = MainViewModel(IptvRepository(db), Preferences(instrumentation.targetContext), db); store.put("test", vm) }
        fun node(text: String): android.view.accessibility.AccessibilityNodeInfo? {
            fun visit(n: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (n.text?.contains(text) == true) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it)?.let { found -> return found } }
                return null
            }
            return instrumentation.uiAutomation.rootInActiveWindow?.let { visit(it) }
        }
        fun click(text: String) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            var target = node(text)
            while (target == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50)
                target = node(text)
            }
            while (target != null && !target.isClickable) target = target.parent
            assertNotNull("Find source header $text", target)
            assertTrue(target!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> vm.section = Section.SOURCES; activity.setContent { androidx.compose.runtime.key(vm) { StbPlayApp(vm) } } }
                instrumentation.waitForIdleSync()
                main { assertNull(vm.expandedSource) }
                click("STALKER PORTAL")
                main { assertEquals(SourceProtocol.STALKER, vm.expandedSource) }
                click("STALKER PORTAL")
                main { assertNull(vm.expandedSource) }
                click("XTREAM CODES API")
                main { assertEquals(SourceProtocol.XTREAM, vm.expandedSource) }
                click("STALKER PORTAL")
                main { assertEquals(SourceProtocol.STALKER, vm.expandedSource) }
                main { vm.section = Section.HOME; vm.section = Section.SOURCES; assertNull(vm.expandedSource) }
                assertNull(node("Active playlist XMLTV"))
                assertNull(node("Provider privacy"))
                main {
                    vm.playlists = listOf(
                        com.stbplay.app.data.db.PlaylistEntity(id = 1, name = "Home", protocol = SourceProtocol.STALKER, url = "https://portal.example.invalid/c/"),
                        com.stbplay.app.data.db.PlaylistEntity(id = 2, name = "Family", protocol = SourceProtocol.XTREAM, url = "https://tv.example.invalid"),
                        com.stbplay.app.data.db.PlaylistEntity(id = 3, name = "News", protocol = SourceProtocol.M3U, url = "https://list.example.invalid/news.m3u"),
                        com.stbplay.app.data.db.PlaylistEntity(id = 4, name = "Movies", protocol = SourceProtocol.M3U, url = "https://list.example.invalid/movies.m3u")
                    )
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(400)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(instrumentation.targetContext.cacheDir, "sources-064.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        } finally { main { store.clear() }; db.close() }
    }

    @Test fun playerArrowsReachTopProgressVolumeAndHighlightFocus() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: ZappingPlayerView
            scenario.onActivity { activity ->
                activity.setContent { MaterialTheme {} }
                view = ZappingPlayerView(activity)
                view.useController = true
                view.controllerShowTimeoutMs = 0
                view.installHeader({}, {}, {})
                view.updateHeader("Test channel", "News", true, false)
                val controls = view.findViewById<android.widget.LinearLayout>(androidx.media3.ui.R.id.exo_basic_controls)
                val volume = android.widget.ImageButton(activity).apply { contentDescription = "Volume down"; isFocusable = true }
                controls.addView(volume, 0)
                activity.setContentView(view)
                view.showController()
                view.updateHeaderVisibility(true)
                view.styleControllerFocus()
            }
            instrumentation.waitForIdleSync()
            val focusDeadline = SystemClock.uptimeMillis() + 5_000
            var windowReady = false
            while (!windowReady && SystemClock.uptimeMillis() < focusDeadline) {
                scenario.onActivity { windowReady = view.hasWindowFocus() }
                if (!windowReady) SystemClock.sleep(50)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
            scenario.onActivity {
                fun find(root: View, label: String): View? {
                    if (root.contentDescription?.toString() == label && (label != "Previous" || root is android.widget.Button)) return root
                    if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), label)?.let { return it }
                    return null
                }
                val previous = find(view, "Previous")!!
                val volume = find(view, "Volume down")!!
                val progress = view.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
                progress.isEnabled = true
                assertTrue("Top action can focus: touchMode=${view.isInTouchMode}, window=${view.hasWindowFocus()}, visible=${previous.isShown}, enabled=${previous.isEnabled}, focusable=${previous.isFocusable}, size=${previous.width}x${previous.height}, controller=${view.isControllerFullyVisible}", previous.requestFocus())
                val plain = previous.background.current
                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
                assertTrue("Down reaches seek bar", progress.hasFocus())
                assertNotSame("Focus drawable changes", plain, previous.background.current)
                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
                assertTrue("Down reaches volume", volume.hasFocus())
                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
                assertTrue(progress.hasFocus())
                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
                assertTrue("Up returns to top actions", previous.hasFocus())
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.cacheDir, "player-focus-064.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
