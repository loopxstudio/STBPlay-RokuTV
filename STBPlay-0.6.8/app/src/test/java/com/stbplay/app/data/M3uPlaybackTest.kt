package com.stbplay.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class M3uPlaybackTest {
    @Test fun vlcHeadersAndUrlSuffixAreScopedToTheirOwnEntry() {
        val result = M3uParser.parse("""#EXTM3U
#EXTINF:-1,Protected channel
#EXTVLCOPT:http-user-agent=Provider Player
#EXTVLCOPT:http-referrer=https://provider.invalid/watch
stream.m3u8|Origin=https%3A%2F%2Fprovider.invalid
#EXTINF:-1,Normal channel
other.ts
""", "https://provider.invalid/folder/list.m3u")
        val first = PlaybackRequests.parse(result.items[0].streamUrl)
        assertEquals("https://provider.invalid/folder/stream.m3u8", first.url)
        assertEquals("Provider Player", first.headers["User-Agent"])
        assertEquals("Provider Player", PlaybackRequests.userAgent(false, first.headers))
        assertEquals("https://provider.invalid/watch", first.headers["Referer"])
        assertEquals("https://provider.invalid", first.headers["Origin"])
        assertTrue(PlaybackRequests.parse(result.items[1].streamUrl).headers.isEmpty())
        assertEquals(result.items, FavoriteCodec.decode(FavoriteCodec.encode(result.items)))
    }
    @Test fun encodedHeaderValuesPreservePlusAndCannotInjectNewHeaders() {
        val request = PlaybackRequests.parse("https://provider.invalid/live|User-Agent=Test%2BPlayer&Referer=bad%0D%0AX-Test%3Aevil")
        assertEquals("Test+Player", request.headers["User-Agent"])
        assertFalse(request.headers.containsKey("Referer"))
    }
    @Test fun redirectedCatalogResolvesRelativeStreamsFromFinalLocation() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/new/list.m3u"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,News\nnews.ts\n"))
            val result = runBlocking { testRepository().loadM3u(server.url("/old/list.m3u").toString()) }
            assertEquals(server.url("/new/news.ts").toString(), result.items.single().streamUrl)
        } finally { server.shutdown() }
    }
    @Test fun hlsMediaAndMasterManifestsRemainSingleStreams() {
        val server = MockWebServer(); server.start()
        try {
            listOf("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nsegment.ts\n", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nvariant.m3u8\n").forEach { manifest ->
                server.enqueue(MockResponse().setBody(manifest))
                val source = server.url("/live.m3u8").toString()
                val result = runBlocking { testRepository().loadM3u(source) }
                assertEquals(source, result.items.single().streamUrl)
            }
        } finally { server.shutdown() }
    }
    @Test fun htmlLoginResponseIsNotAcceptedAsAPlaylist() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("<html>Login required</html>"))
            try { runBlocking { testRepository().loadM3u(server.url("/list").toString()) }; fail("Reject non-playlist") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("M3U")) }
        } finally { server.shutdown() }
    }
}
