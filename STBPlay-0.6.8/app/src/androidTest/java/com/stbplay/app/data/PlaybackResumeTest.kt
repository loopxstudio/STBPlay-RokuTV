package com.stbplay.app.data

import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stbplay.app.MainActivity
import com.stbplay.app.PlayerScreen
import com.stbplay.app.data.db.AppDatabase
import com.stbplay.app.model.MediaItem
import com.stbplay.app.model.MediaKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackResumeTest {
    private fun findPlayer(view: View): Player? {
        if (view is PlayerView) return view.player
        if (view is ViewGroup) for (index in 0 until view.childCount) findPlayer(view.getChildAt(index))?.let { return it }
        return null
    }
    @Test fun actualLocalPlaybackSeeksToSavedPositionAndStartOverResetsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("resume_test_$name", mode)
        }
        val prefs = Preferences(isolated)
        isolated.getSharedPreferences("stbplay_preferences", 0).edit().clear().commit()
        // A generated 90-second PCM fixture exercises real Media3 seeking offline.
        val audio = java.io.File(context.cacheDir, "resume-test.wav")
        val bytes = 90 * 8_000 * 2
        val wav = ByteBuffer.allocate(bytes + 44).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(bytes + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes)
        audio.writeBytes(wav.array())
        val item = MediaItem("resume-fixture", "Resume fixture", audio.toURI().toString(), kind = MediaKind.MOVIE)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = IptvRepository(database)
        fun playAt(position: Long): Long {
            var actualPosition = -1L
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { MaterialTheme {
                        PlayerScreen(item, null, repository, prefs, "resume-source", startPosition = position, onClose = {}, onNextChannel = {}, onPreviousChannel = {})
                    } }
                }
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (SystemClock.uptimeMillis() < deadline) {
                    var ready = false
                    scenario.onActivity { activity ->
                        findPlayer(activity.window.decorView)?.let { player ->
                            if (player.playbackState == Player.STATE_READY) {
                                player.pause()
                                actualPosition = player.currentPosition
                                ready = true
                            }
                        }
                    }
                    if (ready) break
                    SystemClock.sleep(20)
                }
                assertTrue("Local fixture reaches READY", actualPosition >= 0)
                scenario.onActivity { activity ->
                    fun showControls(view: View) {
                        if (view is com.stbplay.app.ZappingPlayerView) {
                            view.controllerShowTimeoutMs = 0
                            view.showController()
                            view.updateHeaderVisibility(true)
                        }
                        if (view.contentDescription?.toString() == "Back to list") view.requestFocus()
                        if (view is ViewGroup) for (index in 0 until view.childCount) showControls(view.getChildAt(index))
                    }
                    showControls(activity.window.decorView)
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(700)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.cacheDir, "player-real-focus-064.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            return actualPosition
        }
        try {
            assertTrue(playAt(20_000) >= 20_000)
            assertTrue(prefs.resumePosition("resume-source", item) >= 20_000)
            assertEquals(item, prefs.watchHistory("resume-source").single())
            assertTrue(playAt(0) < 2_000)
            assertEquals(0L, prefs.resumePosition("resume-source", item))
        } finally {
            database.close(); audio.delete()
            isolated.getSharedPreferences("stbplay_preferences", 0).edit().clear().commit()
        }
    }
}
