package com.stbplay.app.data

import com.stbplay.app.model.XtreamCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class XtreamPlaybackTest {
    private fun catalog(direct: String? = null, block: (IptvRepository, XtreamCredentials, MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        val directField = direct?.let { ",\"direct_source\":\"$it\"" }.orEmpty()
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("""[{"stream_id":"42","name":"Live"$directField}]"""))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("""[{"stream_id":"17","name":"Movie","container_extension":"mkv"$directField}]"""))
        server.enqueue(MockResponse().setBody("""{"episodes":{"1":[{"id":"99","title":"Episode","container_extension":"mp4"$directField}]}}"""))
        try { block(testRepository(), XtreamCredentials(server.url("/").toString(), "user name+", "p/a?&+"), server) }
        finally { server.shutdown() }
    }
    @Test fun generatedPlaybackPathsEncodeCredentialsAsPathSegmentsAndKeepPort() = catalog { repo, c, server ->
        runBlocking {
            val base = server.url("/").toString().trimEnd('/')
            assertEquals("$base/live/user%20name%2B/p%2Fa%3F%26%2B/42.ts", repo.xtreamLive(c).single().streamUrl)
            assertEquals("$base/movie/user%20name%2B/p%2Fa%3F%26%2B/17.mkv", repo.xtreamVod(c).single().streamUrl)
            assertEquals("$base/series/user%20name%2B/p%2Fa%3F%26%2B/99.mp4", repo.seriesEpisodes(c, "1").single().streamUrl)
        }
        val request = server.takeRequest()
        assertEquals(c.username, request.requestUrl!!.queryParameter("username"))
        assertEquals(c.password, request.requestUrl!!.queryParameter("password"))
        assertEquals(PlaybackRequests.APP_USER_AGENT, request.getHeader("User-Agent"))
    }
    @Test fun providerDirectSourceIsUsedForAllPlayableKinds() = catalog("https://media.example.invalid/signed?token=abc") { repo, c, _ ->
        runBlocking {
            assertEquals("https://media.example.invalid/signed?token=abc", repo.xtreamLive(c).single().streamUrl)
            assertEquals("https://media.example.invalid/signed?token=abc", repo.xtreamVod(c).single().streamUrl)
            assertEquals("https://media.example.invalid/signed?token=abc", repo.seriesEpisodes(c, "1").single().streamUrl)
        }
    }
    @Test fun nullDirectSourceFallsBackToGeneratedLinks() = catalog("null") { repo, c, _ ->
        runBlocking {
            assertTrue(repo.xtreamLive(c).single().streamUrl.endsWith("/42.ts"))
            assertTrue(repo.xtreamVod(c).single().streamUrl.endsWith("/17.mkv"))
            assertTrue(repo.seriesEpisodes(c, "1").single().streamUrl.endsWith("/99.mp4"))
        }
    }
    @Test fun magIdentificationIsOnlyUsedForStalker() {
        assertFalse(PlaybackRequests.userAgent(false).contains("MAG"))
        assertTrue(PlaybackRequests.userAgent(true).contains("MAG200"))
    }
}
