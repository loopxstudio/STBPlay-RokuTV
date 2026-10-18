package com.stbplay.app.data

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stbplay.app.*
import com.stbplay.app.data.db.AppDatabase
import com.stbplay.app.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackAndNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(30)
        assertTrue(message, condition())
    }
    private fun playerView(view: View): ZappingPlayerView? {
        if (view is ZappingPlayerView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) playerView(view.getChildAt(i))?.let { return it }
        return null
    }
    @Test fun xtreamAndM3uRenderAndHiddenLiveControlsReturnForEveryNavigationKey() {
        val rate = 8000; val samples = rate * 60
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(samples * 2 + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort((kotlin.math.sin(2 * Math.PI * 440 * it / rate) * 6000).toInt().toShort()) }
        val audio = bytes.array()
        val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val server = ServerSocket(0)
        val serverThread = thread(isDaemon = true) {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    val request = input.readLine() ?: return@use
                    val path = request.split(' ')[1]
                    val headers = mutableMapOf<String, String>()
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                    requests.add(path to headers["user-agent"].orEmpty())
                    val isApi = path.startsWith("/player_api.php")
                    val isPlaylist = path == "/playlist.m3u"
                    val json = when {
                        "action=get_live_streams" in path -> """[{"stream_id":"1","name":"Live"}]"""
                        "action=get_vod_streams" in path -> """[{"stream_id":"2","name":"Film","container_extension":"wav"}]"""
                        "action=get_series_info" in path -> """{"episodes":{"1":[{"id":"3","title":"Episode","container_extension":"wav"}]}}"""
                        else -> "[]"
                    }
                    val allowed = isApi || isPlaylist || if (path.startsWith("/protected.wav"))
                        headers["user-agent"] == "Provider Player" && headers["referer"] == "https://provider.invalid/watch"
                        else headers["user-agent"] == PlaybackRequests.APP_USER_AGENT
                    val playlist = "#EXTM3U\n#EXTINF:-1,Protected M3U\n#EXTVLCOPT:http-user-agent=Provider Player\n#EXTVLCOPT:http-referrer=https://provider.invalid/watch\nprotected.wav\n"
                    val body = if (!allowed) "Denied playback".toByteArray() else if (isApi) json.toByteArray() else if (isPlaylist) playlist.toByteArray() else audio
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 ${if (allowed) "200 OK" else "403 Forbidden"}\r\nContent-Type: ${if (isApi) "application/json" else "audio/wav"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(body); output.flush()
                }
            } catch (_: java.io.IOException) { }
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repo = IptvRepository(db)
        val credentials = XtreamCredentials("http://127.0.0.1:${server.localPort}", "user", "pass")
        val items = runBlocking { listOf(repo.xtreamLive(credentials).single(), repo.xtreamVod(credentials).single(), repo.seriesEpisodes(credentials, "1").single(), repo.loadM3u("${credentials.server}/playlist.m3u").items.single()) }
        try {
            items.forEach { item ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    var zaps = 0
                    scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                        PlayerScreen(item, null, repo, Preferences(context), "xtream-test", onClose = {}, onNextChannel = { zaps++ }, onPreviousChannel = { zaps++ })
                    } } }
                    lateinit var view: ZappingPlayerView
                    await("${item.kind} ${item.name} playback renders with provider-compatible request") {
                        var ready = false
                        scenario.onActivity { activity -> playerView(activity.window.decorView)?.let {
                            view = it; val player = it.player as ExoPlayer
                            ready = player.playbackState == Player.STATE_READY && (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
                        } }; ready
                    }
                    if (item.id == "live-1") {
                        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
                        try {
                            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
                            var control: android.media.session.MediaController? = null
                            await("System MediaSession exposes the current title") {
                                control = manager.getActiveSessions(null).firstOrNull { it.packageName == context.packageName && it.metadata?.description?.title?.toString() == item.name }
                                control != null
                            }
                            assertTrue("Provider URL is not published as session metadata", requireNotNull(control!!.metadata).getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_URI).isNullOrEmpty())
                            control!!.transportControls.pause()
                            await("System media pause works") { var paused = false; scenario.onActivity { paused = view.player?.isPlaying == false }; paused }
                            control!!.transportControls.play()
                            await("System media play works") { var playing = false; scenario.onActivity { playing = view.player?.isPlaying == true }; playing }
                        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
                        for (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                            scenario.onActivity { view.controllerShowTimeoutMs = 600; view.showController() }
                            await("Live controls auto-hide") { var hidden = false; scenario.onActivity { hidden = !view.isControllerFullyVisible && view.hasFocus() && view.hasWindowFocus() }; hidden }
                            scenario.onActivity { view.controllerShowTimeoutMs = 5000 }
                            instrumentation.sendKeyDownUpSync(key)
                            await("Key $key reveals live controls") { var shown = false; scenario.onActivity { shown = view.isControllerFullyVisible }; shown }
                            scenario.onActivity { assertEquals("Reveal must not zap the channel", 0, zaps) }
                        }
                    }
                }
            }
            assertTrue(requests.any { it.first.startsWith("/live/") })
            assertTrue(requests.any { it.first.startsWith("/movie/") })
            assertTrue(requests.any { it.first.startsWith("/series/") })
            assertTrue(requests.any { it.first.startsWith("/protected.wav") && it.second == "Provider Player" })
            assertTrue(requests.all { !it.second.contains("MAG") })
        } finally { server.close(); serverThread.join(1000); db.close() }
    }
    @Test fun guideIsAbsentNavPositionsStayAndStatusOnlyAppearsInSources() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ViewModelStore()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("nav067_$name", mode)
        }
        lateinit var vm: MainViewModel
        instrumentation.runOnMainSync { vm = MainViewModel(IptvRepository(db), Preferences(isolated), db); store.put("test", vm) }
        fun node(text: String): AccessibilityNodeInfo? {
            fun visit(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (n.text?.toString() == text) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it)?.let { found -> return found } }
                return null
            }
            return instrumentation.uiAutomation.rootInActiveWindow?.let { visit(it) }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { StbPlayApp(vm) } }
                await("Navigation ready") { node("Sources") != null }
                scenario.onActivity { vm.message = "Connected test marker"; vm.connectionStatus = "CONNECTED" }
                for (section in listOf(Section.HOME, Section.LIVE, Section.MOVIES, Section.SERIES, Section.SETTINGS, Section.SOURCES)) {
                    scenario.onActivity { vm.section = section }
                    instrumentation.waitForIdleSync()
                    assertNull("No TV Guide tab", node("TV Guide"))
                    if (section == Section.SOURCES) {
                        await("Sources keeps status") { node("CONNECTED") != null && node("Connected test marker") != null }
                    } else {
                        assertNull("No connection badge in $section", node("CONNECTED"))
                        assertNull("No connection message in $section", node("Connected test marker"))
                    }
                }
                fun center(label: String): Int { val rect = android.graphics.Rect(); node(label)!!.getBoundsInScreen(rect); return rect.centerY() }
                assertTrue("Keep the removed guide slot between Series and Sources", kotlin.math.abs(2 * (center("Series") - center("Movies")) - (center("Sources") - center("Series"))) <= 6)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.cacheDir, "sources-067.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        } finally { instrumentation.runOnMainSync { store.clear() }; db.close() }
    }
}
