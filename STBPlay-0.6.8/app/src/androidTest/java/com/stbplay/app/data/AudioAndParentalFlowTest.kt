package com.stbplay.app.data

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stbplay.app.*
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
class AudioAndParentalFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < until) SystemClock.sleep(30)
        assertTrue(message, condition())
    }
    private fun node(text: String): AccessibilityNodeInfo? {
        fun visit(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.text?.toString() == text) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let { visit(it) }
    }
    private fun click(text: String) {
        fun actionable(): AccessibilityNodeInfo? {
            var target = node(text)
            while (target != null && (!target.isClickable || !target.isEnabled)) target = target.parent
            return target
        }
        await("Clickable $text") { actionable() != null }
        assertTrue("Activate $text", actionable()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun enter(scenario: ActivityScenario<MainActivity>, label: String, value: String) {
        fun accepting(): Boolean {
            var active = false
            scenario.onActivity { active = (it.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).isAcceptingText }
            return active
        }
        await("Previous PIN input session has closed") { !accepting() }
        click(label)
        await("PIN editor is focused and active") {
            val focused = instrumentation.uiAutomation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            focused?.isEditable == true && focused.isEnabled && accepting()
        }
        instrumentation.waitForIdleSync()
        // Set text through the editor's accessibility action. sendStringSync injects
        // separate key events into the TV IME and can leave its last edit queued.
        val editor = instrumentation.uiAutomation.rootInActiveWindow!!.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)!!
        val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue("Set PIN text", editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        instrumentation.waitForIdleSync()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("PIN editing is finished") { !accepting() }
        instrumentation.waitForIdleSync()
    }
    @Test fun appLockDefaultsOffEnablesUnlocksRelocksAndRequiresPinToDisable() {
        val store = AppLockStore(context)
        store.disable()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertFalse(activity.accessSession.enabled)
                    activity.setContent { MaterialTheme { ParentalControlSettings() } }
                }
                click("Set PIN / enable")
                enter(scenario, "New PIN (4–6 digits)", "5842")
                enter(scenario, "Confirm new PIN", "5842")
                click("Save")
                await("PIN enabled") { var ok = false; scenario.onActivity { ok = it.accessSession.enabled && it.accessSession.unlocked }; ok }
                scenario.onActivity { assertTrue(it.accessSession.unlocked) }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await("PIN gate visible") { node("STBPlay is locked") != null }
                scenario.onActivity {
                    assertFalse(it.accessSession.unlocked)
                    assertTrue(it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                }
                enter(scenario, "PIN", "5842")
                await("Correct PIN unlocks") { var ok = false; scenario.onActivity { ok = it.accessSession.unlocked }; ok }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                await("Background return relocks") { node("STBPlay is locked") != null }
                enter(scenario, "PIN", "5842")
                await("Unlock after returning") { var ok = false; scenario.onActivity { ok = it.accessSession.unlocked }; ok }
                scenario.onActivity { activity -> activity.setContent { MaterialTheme { ParentalControlSettings() } } }
                click("Disable PIN lock")
                enter(scenario, "Current PIN", "0000")
                click("Disable")
                await("Wrong PIN rejected") { node("Incorrect current PIN.") != null }
                assertTrue(store.enabled)
                enter(scenario, "Current PIN", "5842")
                click("Disable")
                await("Correct PIN disables") { var off = false; scenario.onActivity { off = !it.accessSession.enabled }; off }
                scenario.onActivity { assertFalse(it.accessSession.enabled) }
            }
        } finally { store.disable() }
    }
    @Test fun backgroundPinSetupCannotUnlockANewerForegroundSession() {
        val store = AppLockStore(context)
        store.disable()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var generation = 0
                scenario.onActivity { generation = it.accessSession.generation }
                scenario.moveToState(Lifecycle.State.CREATED)
                assertTrue(store.setPin("482915"))
                scenario.onActivity {
                    it.accessSession.settingsChanged(true, generation)
                    assertTrue(it.accessSession.enabled)
                    assertFalse(it.accessSession.unlocked)
                    assertFalse(it.accessSession.completeUnlock(generation, true))
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                await("Late PIN setup stays locked on return") { node("STBPlay is locked") != null }
            }
        } finally { store.disable() }
    }
    @Test fun wrongPinCooldownSurvivesStoreRecreation() {
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("rate_test_$name", mode)
        }
        val store = AppLockStore(isolated)
        store.disable()
        try {
            assertTrue(store.setPin("123456"))
            repeat(5) { assertFalse(store.verify("999999")) }
            assertTrue(store.waitSeconds() in 1..30)
            val reopened = AppLockStore(isolated)
            assertTrue(reopened.waitSeconds() > 0)
            assertFalse(reopened.verify("123456"))
        } finally { store.disable() }
    }
    @Test fun localToneRendersAudioAndVolumeBarFitsAndUpdatesAboveSpeakerPair() {
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("audio_test_$name", mode)
        }
        val prefs = Preferences(isolated)
        prefs.saveOption("volume", "60")
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val audio = java.io.File(context.cacheDir, "audible-tone.wav")
        val rate = 8000; val samples = 20 * rate
        val bytes = ByteBuffer.allocate(samples * 2 + 44).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(samples * 2 + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { i -> bytes.putShort((kotlin.math.sin(2.0 * Math.PI * 440 * i / rate) * 6000).toInt().toShort()) }
        audio.writeBytes(bytes.array())
        val item = MediaItem("tone-test", "Audio test", audio.toURI().toString(), kind = MediaKind.MOVIE)
        fun findPlayer(root: View): ZappingPlayerView? {
            if (root is ZappingPlayerView) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) findPlayer(root.getChildAt(i))?.let { return it }
            return null
        }
        fun findLabel(root: View, label: String): View? {
            if (root.contentDescription?.toString() == label) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) findLabel(root.getChildAt(i), label)?.let { return it }
            return null
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var view: ZappingPlayerView
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    PlayerScreen(item, null, IptvRepository(db), prefs, "audio-test", onClose = {}, onNextChannel = {}, onPreviousChannel = {})
                } } }
                await("Audio renderer consumes the local tone") {
                    var ready = false
                    scenario.onActivity { activity ->
                        findPlayer(activity.window.decorView)?.let {
                            view = it
                            val player = it.player as ExoPlayer
                            ready = it.isLaidOut && player.playbackState == Player.STATE_READY && player.audioFormat != null && (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
                        }
                    }
                    ready
                }
                scenario.onActivity {
                    val player = view.player as ExoPlayer
                    assertEquals(C.USAGE_MEDIA, player.audioAttributes.usage)
                    assertEquals(8000, player.audioFormat!!.sampleRate)
                    assertTrue(player.audioSessionId > 0)
                    assertEquals(0.6f, player.volume, 0.001f)
                    val bar = view.findViewWithTag<android.widget.ProgressBar>("stbplay_volume_bar")
                    val down = findLabel(view, "Volume down")!!
                    val up = findLabel(view, "Volume up")!!
                    val barPosition = IntArray(2); bar.getLocationOnScreen(barPosition)
                    val downPosition = IntArray(2); down.getLocationOnScreen(downPosition)
                    val upPosition = IntArray(2); up.getLocationOnScreen(upPosition)
                    assertTrue("Bar starts within speaker pair", barPosition[0] >= downPosition[0])
                    assertTrue("Bar ends within speaker pair", barPosition[0] + bar.width <= upPosition[0] + up.width)
                    assertTrue("Bar is above speakers", barPosition[1] + bar.height <= downPosition[1])
                    assertEquals(60, bar.progress)
                    up.performClick()
                }
                await("Volume bar and player both reach 70 percent") {
                    var ok = false
                    scenario.onActivity { ok = view.findViewWithTag<android.widget.ProgressBar>("stbplay_volume_bar").progress == 70 && kotlin.math.abs(view.player!!.volume - 0.7f) < .001f }
                    ok
                }
                scenario.onActivity {
                    view.controllerShowTimeoutMs = 0; view.showController(); view.updateHeaderVisibility(true)
                    findLabel(view, "Volume up")!!.requestFocus()
                }
                instrumentation.waitForIdleSync(); SystemClock.sleep(500)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.cacheDir, "volume-bar-065.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                val lock = AppLockStore(context)
                var generation = 0
                scenario.onActivity { generation = it.accessSession.beginSettingsChange() }
                try {
                    assertTrue(lock.setPin("482915"))
                    scenario.onActivity { it.accessSession.settingsChanged(true, generation); assertTrue(it.accessSession.unlocked) }
                    scenario.moveToState(Lifecycle.State.CREATED)
                    scenario.onActivity { assertFalse(view.player!!.playWhenReady); assertFalse(it.accessSession.unlocked) }
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    scenario.onActivity { assertFalse("Locked foreground must not resume audio", view.player!!.playWhenReady) }
                } finally { lock.disable() }
            }
            assertEquals("70", prefs.option("volume"))
        } finally { db.close(); audio.delete(); isolated.getSharedPreferences("stbplay_preferences", 0).edit().clear().commit() }
    }
}
