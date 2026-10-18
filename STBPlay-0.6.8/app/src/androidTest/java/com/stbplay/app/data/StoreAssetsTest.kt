package com.stbplay.app.data

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stbplay.app.*
import com.stbplay.app.data.db.*
import com.stbplay.app.model.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real UI captures with fictional catalog data; no demo content ships in the app APK. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StoreAssetsTest {
    @Test fun captureCurrentAppPagesWithoutProviderOrPersonalData() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("store_assets_$name", mode)
        }
        isolated.getSharedPreferences("stbplay_preferences", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: MainViewModel
        inst.runOnMainSync { vm = MainViewModel(IptvRepository(db), Preferences(isolated), db); store.put("assets",vm) }
        val clip = inst.context.assets.open("stalker-test.mp4").use { it.readBytes() }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                val end = range?.substringAfter('-')?.toIntOrNull()?.coerceAtMost(clip.lastIndex) ?: clip.lastIndex
                return MockResponse().setResponseCode(if(range==null)200 else 206).setHeader("Content-Type","video/mp4")
                    .apply { if(range!=null)setHeader("Content-Range","bytes $start-$end/${clip.size}") }
                    .setBody(Buffer().write(clip.copyOfRange(start,end+1)))
            }
        }
        server.start()
        val demoVideoUrl = server.url("/clip.mp4").toString()
        fun await(message:String, condition:()->Boolean) {
            val deadline=SystemClock.uptimeMillis()+30_000
            while(!condition() && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(70)
            assertTrue(message,condition())
        }
        fun node(text:String):AccessibilityNodeInfo? {
            fun find(n:AccessibilityNodeInfo):AccessibilityNodeInfo? {
                if(n.text?.toString()==text)return n
                for(i in 0 until n.childCount)n.getChild(i)?.let{find(it)?.let{return it}}
                return null
            }
            return inst.uiAutomation.rootInActiveWindow?.let(::find)
        }
        fun capture(name:String) {
            inst.waitForIdleSync(); SystemClock.sleep(500)
            val bitmap=inst.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
            val folder=java.io.File(context.cacheDir,"play-store-assets").apply{mkdirs()}
            java.io.File(folder,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        fun scroll() {
            fun visit(n:AccessibilityNodeInfo):Boolean {
                if(n.isScrollable && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true
                for(i in 0 until n.childCount)n.getChild(i)?.let{if(visit(it))return true}
                return false
            }
            inst.uiAutomation.rootInActiveWindow?.let(::visit); inst.waitForIdleSync();SystemClock.sleep(250)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    vm.items=listOf("Demo Nature","Demo Culture","Demo Travel","Demo Music","Demo Studio","Demo Panorama").mapIndexed{i,n->MediaItem("demo-live-$i",n,"https://media.example.invalid/$i.m3u8",if(i%2==0)"Lifestyle" else "Entertainment",kind=MediaKind.LIVE)}
                    vm.vod=listOf("Demo Horizon","Demo City Lights","Demo Oceans","Demo Open Road","Demo Night Sky","Demo Landscapes").mapIndexed{i,n->MediaItem("demo-movie-$i",n,"https://media.example.invalid/$i.mp4",if(i%2==0)"Discovery" else "Travel",kind=MediaKind.MOVIE)}
                    vm.series=listOf("Demo Journeys","Demo Wild Places","Demo Creative Studio","Demo Everyday Stories").mapIndexed{i,n->MediaItem("demo-series-$i",n,"",if(i%2==0)"Discovery" else "Lifestyle",kind=MediaKind.SERIES)}
                    vm.movieLibraryTotal=vm.vod.size;vm.movieBrowseTotal=vm.vod.size
                    vm.seriesLibraryTotal=vm.series.size;vm.seriesBrowseTotal=vm.series.size
                    vm.liveFolders=listOf(CatalogFolder("Lifestyle","Lifestyle"),CatalogFolder("Entertainment","Entertainment"))
                    vm.sourceKey="store-demo"
                    vm.playlists=listOf(
                        PlaylistEntity(id=1,name="Living room demo",protocol=SourceProtocol.STALKER,url="https://portal.example.invalid/c/"),
                        PlaylistEntity(id=2,name="Family demo",protocol=SourceProtocol.XTREAM,url="https://tv.example.invalid"),
                        PlaylistEntity(id=3,name="Travel demo",protocol=SourceProtocol.M3U,url="https://playlist.example.invalid/demo.m3u")
                    )
                    vm.activePlaylist=vm.playlists.first()
                    vm.favoriteItems=listOf(vm.items.first(),vm.vod.first(),vm.series.first())
                    vm.connectionStatus="SAVED CATALOG"
                    vm.message="Demo catalog. Your provider supplies the available content."
                    activity.setContent { StbPlayApp(vm) }
                }
                await("Main app visible"){node("Home")!=null}
                scenario.onActivity { vm.recents=listOf(vm.vod[1],vm.items[1],vm.vod[2]) }
                for((section,name) in listOf(Section.HOME to "01-home.png",Section.LIVE to "02-live-tv.png",Section.MOVIES to "03-movies.png",Section.SERIES to "04-series.png",Section.SOURCES to "05-sources.png",Section.SETTINGS to "06-settings.png")) {
                    scenario.onActivity{vm.section=section};capture(name)
                }
                repeat(5){if(node("Privacy policy")?.isVisibleToUser!=true)scroll()}
                capture("07-about-and-privacy.png")
                scenario.onActivity{vm.selected=MediaItem("demo-playback","STBPlay demo video",demoVideoUrl,"Synthetic test footage",kind=MediaKind.MOVIE)}
                fun player(view:View):PlayerView? {
                    if(view is PlayerView)return view
                    if(view is ViewGroup)for(i in 0 until view.childCount)player(view.getChildAt(i))?.let{return it}
                    return null
                }
                await("Demo video renders") {
                    var ready=false
                    scenario.onActivity { activity ->
                        val p=player(activity.window.decorView)?.player
                        ready=p?.playbackState==Player.STATE_READY && ((p as? androidx.media3.exoplayer.ExoPlayer)?.videoDecoderCounters?.renderedOutputBufferCount ?:0)>0
                    }
                    ready
                }
                scenario.onActivity{activity->player(activity.window.decorView)?.apply{player?.pause();showController()}}
                capture("08-player.png")
                scenario.onActivity{vm.selected=null}
            }
        } finally { inst.runOnMainSync{store.clear()};db.close();server.shutdown() }
    }
}
