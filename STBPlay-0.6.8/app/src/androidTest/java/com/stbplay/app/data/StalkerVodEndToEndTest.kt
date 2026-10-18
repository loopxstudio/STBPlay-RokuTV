package com.stbplay.app.data

import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stbplay.app.*
import com.stbplay.app.data.db.AppDatabase
import com.stbplay.app.model.StalkerCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StalkerVodEndToEndTest {
    @Test fun portalSessionToSeasonEpisodeFileAndRealVideoPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val clip = instrumentation.context.assets.open("stalker-test.mp4").use { it.readBytes() }
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val url = request.requestUrl!!
                if (url.encodedPath == "/video.mp4") {
                    val range = request.getHeader("Range")
                    if (range != null) {
                        val start = range.substringAfter("bytes=").substringBefore('-').toIntOrNull() ?: 0
                        val end = range.substringAfter('-').toIntOrNull()?.coerceAtMost(clip.lastIndex) ?: clip.lastIndex
                        return MockResponse().setResponseCode(206).setHeader("Content-Type", "video/mp4")
                            .setHeader("Content-Range", "bytes $start-$end/${clip.size}")
                            .setBody(Buffer().write(clip.copyOfRange(start, end + 1)))
                    }
                    return MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(clip))
                }
                val body = when (url.queryParameter("action")) {
                    "handshake" -> """{"js":{"token":"fixture-token"}}"""
                    "get_profile" -> """{"js":{"id":1}}"""
                    "get_categories" -> """{"js":[{"id":"1","title":"Shows"}]}"""
                    "get_ordered_list" -> when {
                        url.queryParameter("movie_id") == null -> """{"js":{"total_items":1,"data":[{"id":"42","name":"Fixture Show","cmd":"/media/42.mpg","is_series":1,"category_id":"1"}]}}"""
                        url.queryParameter("season_id") == null -> """{"js":{"total_items":1,"data":[{"id":"500","name":"Fixture Season","is_season":true}]}}"""
                        url.queryParameter("episode_id") == null -> """{"js":{"total_items":1,"data":[{"id":"900","name":"Fixture Episode","is_episode":true,"series_number":2}]}}"""
                        else -> """{"js":{"total_items":1,"data":[{"id":"7000","name":"Fixture HD file","is_file":true,"cmd":"ffmpeg http://storage.invalid/episode.mkv","protocol":"custom"}]}}"""
                    }
                    "create_link" -> if (url.queryParameter("cmd") == "/media/file_7000.mpg" && url.queryParameter("series") == "2")
                        """{"js":{"cmd":"${server.url("/video.mp4")}"}}""" else """{"js":{"cmd":"","error":"nothing_to_play"}}"""
                    else -> """{"js":false}"""
                }
                return MockResponse().setBody(body).apply {
                    if (url.queryParameter("action") == "handshake") setHeader("Set-Cookie", "PHPSESSID=fixture-session; Path=/")
                }
            }
        }
        server.start()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("vod_e2e_$name", mode)
        }
        isolated.getSharedPreferences("stbplay_preferences", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = IptvRepository(db)
        val credentials = StalkerCredentials(server.url("/c/").toString(), "00:1A:79:00:00:01")
        val page = runBlocking { repository.stalkerVod(credentials) }
        val store = ViewModelStore()
        lateinit var vm: MainViewModel
        instrumentation.runOnMainSync {
            vm = MainViewModel(repository, Preferences(isolated), db)
            store.put("fixture", vm)
            vm.connectedStalker = credentials
            vm.movieSource = credentials
            vm.vod = page.items
            vm.movieCursor = StalkerCatalogCursor(1, listOf("42"), 1, "")
            vm.movieLibraryTotal = 1
            vm.section = Section.MOVIES
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertTrue(message, condition())
        }
        fun node(text: String): AccessibilityNodeInfo? {
            fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (n.text?.toString() == text) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { find(it)?.let { found -> return found } }
                return null
            }
            return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
        }
        fun click(text: String) {
            await("Find $text") { node(text) != null }
            var target = node(text)
            while (target != null && !target.isClickable) target = target.parent
            assertTrue("Activate $text", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        }
        fun player(view: View): Player? {
            if (view is PlayerView) return view.player
            if (view is ViewGroup) for (i in 0 until view.childCount) player(view.getChildAt(i))?.let { return it }
            return null
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { StbPlayApp(vm) } }
                click("Fixture Show")
                click("Fixture Season")
                click("Fixture Episode")
                click("Fixture HD file")
                await("Media3 renders the selected episode video") {
                    var ready = false
                    scenario.onActivity { activity -> player(activity.window.decorView)?.let { p ->
                        ready = p.playbackState == Player.STATE_READY && p.currentPosition > 200 &&
                            p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } &&
                            ((p as? androidx.media3.exoplayer.ExoPlayer)?.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
                    } }
                    ready
                }
                assertTrue(requests.any { it.requestUrl?.queryParameter("action") == "get_profile" &&
                    it.getHeader("Authorization") == "Bearer fixture-token" && it.getHeader("Cookie")?.contains("PHPSESSID=fixture-session") == true })
                assertTrue(requests.any { it.requestUrl?.queryParameter("action") == "create_link" && it.requestUrl?.queryParameter("series") == "2" })
                val playback = requests.first { it.requestUrl?.encodedPath == "/video.mp4" && it.getHeader("Range") != "bytes=0-511" }
                assertTrue(playback.getHeader("User-Agent")!!.contains("MAG200"))
                assertNotNull(playback.getHeader("Referer"))
                instrumentation.runOnMainSync {
                    vm.selected = null
                    assertTrue(vm.backFromStalkerEpisodes())
                }
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            db.close(); server.shutdown()
        }
    }
}
