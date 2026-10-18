package com.stbplay.app.data

import kotlinx.coroutines.flow.first
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stbplay.app.*
import com.stbplay.app.data.db.PlaylistEntity
import com.stbplay.app.model.MediaItem
import com.stbplay.app.model.MediaKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsabilityFlowTest {
    @Test fun historyAndProgressPersistAndStaySeparatedBySource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("history_test_$name", mode)
        }
        isolated.getSharedPreferences("stbplay_preferences", 0).edit().clear().commit()
        try {
            val movie = MediaItem("movie-1", "A film", "http://example.invalid/film.mp4", kind = MediaKind.MOVIE)
            val live = MediaItem("live-1", "A channel", "http://example.invalid/live.ts", kind = MediaKind.LIVE)
            Preferences(isolated).recordPlayback("one", movie, 120_000, 600_000)
            val reopened = Preferences(isolated)
            assertEquals(movie, reopened.watchHistory("one").single())
            assertEquals(120_000L, reopened.resumePosition("one", movie))
            assertTrue(reopened.watchHistory("two").isEmpty())
            assertEquals(0L, reopened.resumePosition("two", movie))
            reopened.recordPlayback("one", movie, 590_000, 600_000)
            assertEquals(0L, reopened.resumePosition("one", movie))
            reopened.recordPlayback("one", live, 120_000, 0)
            assertEquals(0L, reopened.resumePosition("one", live))
            assertEquals(2, reopened.watchHistory("one").size)
            reopened.clearWatchHistory("one")
            assertTrue(Preferences(isolated).watchHistory("one").isEmpty())
        } finally { isolated.getSharedPreferences("stbplay_preferences", 0).edit().clear().commit() }
    }

    @Test fun deleteDialogDefaultsToCancelOnTheRemote() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var deleted = false
        var cancelled = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        ProfileDeleteDialog(PlaylistEntity(name = "Test profile", protocol = SourceProtocol.M3U, url = "http://example.invalid"),
                            onDelete = { deleted = true }, onCancel = { cancelled = true })
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                val nodes = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Cancel").orEmpty()
                if (nodes.any { it.isFocused }) break
                SystemClock.sleep(20)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertTrue(cancelled); assertFalse(deleted) }
        }
    }
    @Test fun cancelledConnectionLeavesNoSavedProfileAndAllowsRetry() = kotlinx.coroutines.runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.stbplay.app.data.db.AppDatabase::class.java).build()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("cancel_test_$name", mode)
        }
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var vm: MainViewModel
        try {
            instrumentation.runOnMainSync {
                vm = MainViewModel(IptvRepository(db), Preferences(isolated), db)
                store.put("test", vm)
                vm.playlistUrl = "http://127.0.0.1:1/list.m3u"
                vm.connectSource(SourceProtocol.M3U)
                vm.cancelConnection()
                assertFalse(vm.loading)
                assertEquals("NOT CONNECTED", vm.connectionStatus)
                assertFalse(vm.connectionFailed)
            }
            kotlinx.coroutines.delay(150)
            instrumentation.runOnMainSync { assertFalse(vm.loading); assertFalse(vm.connectionFailed) }
            assertTrue(db.playlistDao().getAllPlaylists().first().isEmpty())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            db.close()
            isolated.getSharedPreferences("stbplay_preferences", 0).edit().clear().commit()
        }
    }

    @Test fun backFromPlaybackRestoresTheSelectedChannelFocus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.stbplay.app.data.db.AppDatabase::class.java).build()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("focus_test_$name", mode)
        }
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var vm: MainViewModel
        instrumentation.runOnMainSync {
            vm = MainViewModel(IptvRepository(db), Preferences(isolated), db)
            store.put("test", vm)
        }
        fun channelNode(): android.view.accessibility.AccessibilityNodeInfo? {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
            // Compose virtual nodes do not implement the native text-search shortcut.
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (node.text?.contains("Focus test channel") == true) {
                    var target: android.view.accessibility.AccessibilityNodeInfo? = node
                    while (target != null) {
                        if (target.isFocusable && target.isClickable) return target
                        target = target.parent
                    }
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let { child -> visit(child)?.let { return it } }
                return null
            }
            return visit(root)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    vm.section = Section.LIVE
                    vm.items = listOf(MediaItem("focus-live", "Focus test channel", "http://127.0.0.1:1/test.ts", kind = MediaKind.LIVE))
                    activity.setContent { androidx.compose.runtime.key(vm) { StbPlayApp(vm) } }
                }
                instrumentation.waitForIdleSync()
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (channelNode() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
                val node = channelNode() ?: run {
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        java.io.File(context.cacheDir, "focus-missing.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    val matches = root?.findAccessibilityNodeInfosByText("Focus test channel").orEmpty()
                    throw IllegalStateException("Channel focus target missing; section=${vm.section}, items=${vm.items.size}, package=${root?.packageName}, matches=${matches.map { "${it.className} focusable=${it.isFocusable} clickable=${it.isClickable} text=${it.text}" }}")
                }
                assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS))
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertEquals("focus-live", vm.selected?.id) }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertNull(vm.selected); assertEquals(Section.LIVE, vm.section) }
                val focusDeadline = SystemClock.uptimeMillis() + 5_000
                while (channelNode()?.isFocused != true && SystemClock.uptimeMillis() < focusDeadline) SystemClock.sleep(20)
                assertTrue("Previous channel regains remote focus", channelNode()?.isFocused == true)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            db.close()
            isolated.getSharedPreferences("stbplay_preferences", 0).edit().clear().commit()
        }
    }

}
