package com.stbplay.app.data

import com.stbplay.app.model.StalkerCredentials
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class StalkerRepositoryTest {
    private suspend fun allMovies(repo: IptvRepository, c: StalkerCredentials): List<com.stbplay.app.model.MediaItem> {
        val items = mutableListOf<com.stbplay.app.model.MediaItem>()
        var cursor: StalkerCatalogCursor? = null
        do {
            val page = repo.stalkerVod(c, cursor = cursor)
            items.addAll(page.items)
            cursor = page.next
        } while (cursor != null)
        return items
    }

    private fun withPortal(zeroBased: Boolean = false, linkMode: String = "httpFailure", seriesUnsupported: Boolean = false, rateLimited: Boolean = false, streamStatus: Int = 206, fileCommand: String = "http://example.invalid/raw-file", test: (MockWebServer, MutableList<Map<String, String>>) -> Unit) {
        val calls = mutableListOf<Map<String, String>>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(exchange: RecordedRequest): MockResponse {
            if (exchange.requestUrl!!.encodedPath == "/stream") return MockResponse().setResponseCode(if (exchange.getHeader("Range") != null) streamStatus else 200)
                .setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U\n#EXT-X-VERSION:3\n")
            val params = exchange.requestUrl!!.encodedQuery!!.split("&").associate {
                val pair = it.split("=", limit = 2)
                pair[0] to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }
            calls.add(params + ("path" to exchange.requestUrl!!.encodedPath) +
                ("cookie" to (exchange.getHeader("Cookie") ?: "")))
            val action = params["action"]
            val body = when {
                exchange.requestUrl!!.encodedPath.startsWith("/bad/") -> "<html>Not the API</html>"
                action == "handshake" -> """{"js":{"token":"test-token"}}"""
                action == "get_profile" -> """{"js":{"id":123}}"""
                params["type"] == "series" && seriesUnsupported -> """{"js":false}"""
                action == "get_categories" -> """{"js":[{"id":"1","title":"Test"},{"id":"2","title":"French"}]}"""
                action == "create_link" -> {
                    if ((linkMode == "valid" && (params["cmd"]!!.startsWith("/media/file_") || params["cmd"]!!.startsWith("http"))) ||
                        (linkMode == "metadataOnly" && params["cmd"] == "http://example.invalid/raw-file"))
                        """{"js":{"cmd":"ffmpeg http://127.0.0.1:${server.port}/stream","error":""}}"""
                    else """{"js":{"cmd":"","error":"nothing_to_play"}}"""
                }
                action == "get_ordered_list" && params["movie_id"] != null ->
                    """{"js":{"data":[{"id":"909","cmd":"$fileCommand","is_file":1}]}}"""
                action == "get_ordered_list" -> {
                    val page = params["p"]!!.toInt()
                    // One-based portal: zero and one both yield the first page.
                    val id = if (zeroBased) page + 1 else if (page <= 1) 1 else page
                    """{"js":{"total_items":3,"data":[{"id":"$id","name":"Movie $id","cmd":"ffmpeg http://localhost/ch/$id","category_id":"${params["category"].takeIf { it != "*" } ?: "1"}"}]}}"""
                }
                else -> """{"js":false}"""
            }
            val status = if (rateLimited) 429 else if (params["action"] == "create_link" && linkMode == "httpFailure") 503 else 200
            return MockResponse().setResponseCode(status).setBody(body).apply {
                if (rateLimited) addHeader("Retry-After", "60")
                if (action == "handshake") addHeader("Set-Cookie", "PHPSESSID=test-session; Path=/")
            }
            }
        }
        server.start()
        try { test(server, calls) } finally { server.shutdown() }
    }

    @Test fun liveHttpCatalogCommandsRequestFreshPortalLinks() = withPortal(linkMode = "valid") { server, calls ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        val repo = testRepository()
        val command = "http://example.invalid/expired-live-stream"
        repeat(2) {
            val resolved = runBlocking { repo.resolveStalkerStream(c, "ffmpeg $command") }
            assertEquals("http://127.0.0.1:${server.port}/stream", resolved)
        }
        val links = calls.filter { it["action"] == "create_link" }
        assertEquals(2, links.size)
        assertTrue(links.all { it["type"] == "itv" && it["cmd"] == command })
    }

    @Test fun rejectedLiveLinkDoesNotFallBackToExpiredCatalogUrl() = withPortal(linkMode = "empty") { server, calls ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        try {
            runBlocking { testRepository().resolveStalkerStream(c, "http://example.invalid/expired-live-stream") }
            fail("A rejected link must not be played directly")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("nothing_to_play"))
        }
        assertEquals("itv", calls.last { it["action"] == "create_link" }["type"])
    }

    @Test fun firstPageReturnsWithoutDownloadingEntireCatalog() = withPortal { server, calls ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        val page = runBlocking { testRepository().stalkerVod(c) }
        assertEquals(1, page.items.size)
        assertNotNull(page.next)
        assertEquals(1, calls.count { it["action"] == "get_ordered_list" })
    }

    @Test fun moviesUseOrderedListAndLoadAllPages() = withPortal { server, calls ->
        val repo = testRepository()
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        val items = runBlocking { allMovies(repo, c) }
        assertEquals(listOf("Movie 1", "Movie 2", "Movie 3"), items.map { it.name })
        assertEquals("get_profile", calls[1]["action"])
        assertTrue(calls[1]["cookie"]!!.contains("PHPSESSID=test-session"))
        assertEquals(1, calls.count { it["action"] == "handshake" })
        assertTrue(calls.any { it["cookie"]!!.contains("mac=00%3A1A%3A79") })
        assertFalse(calls.any { it["action"] == "get_vod" })
    }

    @Test fun zeroBasedPaginationDoesNotSkipFirstPage() = withPortal(zeroBased = true) { server, _ ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        assertEquals(listOf("Movie 1", "Movie 2", "Movie 3"), runBlocking { allMovies(testRepository(), c) }.map { it.name })
    }

    @Test fun seriesUseSeriesTypeAndMacChangesReauthenticate() = withPortal { server, calls ->
        val repo = testRepository()
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        runBlocking { repo.stalkerVod(c, true); repo.stalkerVod(c.copy(mac = "00:1A:79:00:00:02"), true) }
        assertEquals(2, calls.count { it["action"] == "handshake" })
        assertTrue(calls.filter { it["action"] == "get_ordered_list" }.all { it["type"] == "series" })
    }

    @Test fun htmlHandshakeDoesNotLockInWrongEndpoint() = withPortal { server, calls ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/bad/c/", "00:1A:79:00:00:01")
        assertEquals(3, runBlocking { allMovies(testRepository(), c) }.size)
        assertTrue(calls.any { it["path"] == "/stalker_portal/server/load.php" })
        assertFalse(calls.any { it["path"]!!.startsWith("/bad/") && it["action"] == "get_profile" })
    }

    @Test fun httpFailureIsPreservedAndLocalhostUsesCreateLink() = withPortal { server, calls ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        try {
            runBlocking { testRepository().resolveStalkerStream(c, "ffmpeg http://localhost/ch/1") }
            fail("Expected HTTP failure")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("503")) }
        assertTrue(calls.any { it["action"] == "create_link" })
    }

    @Test fun folderMetadataIncludesFoldersOutsideFirstPageAndIsCached() = withPortal { server, calls ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        val repo = testRepository()
        runBlocking {
            assertEquals(listOf("Test", "French"), repo.stalkerFolders(c).map { it.title })
            repo.stalkerVod(c)
            assertEquals(1, calls.count { it["action"] == "get_categories" })
            repo.stalkerFolders(c.copy(mac = "00:1A:79:00:00:02"))
            assertEquals(2, calls.count { it["action"] == "get_categories" })
        }
    }

    @Test fun categoryAndSearchArePreservedAcrossPages() = withPortal { server, calls ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        val repo = testRepository()
        runBlocking {
            val first = repo.stalkerVod(c, search = "hello", category = "2")
            assertEquals("French", first.items.single().group)
            repo.stalkerVod(c, cursor = first.next, search = "ignored", category = "1")
        }
        assertTrue(calls.filter { it["action"] == "get_ordered_list" }.all { it["category"] == "2" && it["search"] == "hello" })
    }

    @Test fun movieCatalogIdResolvesFileIdAndDetectsExtensionlessHls() = withPortal(linkMode = "valid") { server, calls ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        val stream = runBlocking { testRepository().resolveStalkerPlayback(c, "/media/42.mpg", true, "42") }
        assertEquals("application/x-mpegURL", stream.mimeType)
        assertTrue(stream.url.endsWith("/stream"))
        assertTrue(calls.any { it["movie_id"] == "42" })
        assertEquals("http://example.invalid/raw-file", calls.last { it["action"] == "create_link" }["cmd"])
    }

    @Test fun movieFileUsesProviderCommandInsteadOfGuessingFromId() = withPortal(linkMode = "metadataOnly") { server, calls ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        val resolved = runBlocking { testRepository().resolveStalkerStream(c, "/media/42.mpg", true, "42") }
        assertEquals("http://127.0.0.1:${server.port}/stream", resolved)
        assertEquals(listOf("/media/42.mpg", "http://example.invalid/raw-file"), calls.filter { it["action"] == "create_link" }.map { it["cmd"] })
    }

    @Test fun movieFileWithoutCommandKeepsLegacyIdFallback() = withPortal(linkMode = "valid", fileCommand = "") { server, calls ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        val resolved = runBlocking { testRepository().resolveStalkerStream(c, "/media/42.mpg", true, "42") }
        assertEquals("http://127.0.0.1:${server.port}/stream", resolved)
        assertEquals("/media/file_909.mpg", calls.last { it["action"] == "create_link" }["cmd"])
    }

    @Test fun rejectedRangeProbeDoesNotRejectAResolvedMovie() {
        for (status in listOf(403, 405, 416, 503)) {
            withPortal(linkMode = "valid", streamStatus = status) { server, _ ->
                val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
                val stream = runBlocking { testRepository().resolveStalkerPlayback(c, "http://example.invalid/movie", true) }
                assertEquals("http://127.0.0.1:${server.port}/stream", stream.url)
                assertNull("Rejected probe must not claim a media format", stream.mimeType)
                val playback = java.net.URL(stream.url).openConnection() as java.net.HttpURLConnection
                try { assertEquals("The ordinary playback GET is still allowed", 200, playback.responseCode) }
                finally { playback.disconnect() }
            }
        }
    }

    @Test fun providerNothingToPlayIsNotReturnedAsAnInvalidUrl() = withPortal(linkMode = "nothing") { server, _ ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        try {
            runBlocking { testRepository().resolveStalkerStream(c, "/media/file_909.mpg", true, "42") }
            fail("Expected a provider error")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("nothing_to_play")) }
    }

    @Test fun remoteVodCommandsStillRequestPortalSigning() = withPortal(linkMode = "valid") { server, calls ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        val link = runBlocking { testRepository().resolveStalkerStream(c, "https://example.invalid/movie", true) }
        assertTrue(link.endsWith("/stream"))
        assertTrue(calls.any { it["action"] == "create_link" })
    }

    @Test fun unsupportedSeriesDoesNotInvalidateTheWorkingSession() = withPortal(seriesUnsupported = true) { server, calls ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        val repo = testRepository()
        try { runBlocking { repo.stalkerFolders(c, true) }; fail("Expected unsupported catalog") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Series")) }
        runBlocking { repo.stalkerVod(c) }
        assertEquals(1, calls.count { it["action"] == "handshake" })
    }

    @Test fun rateLimitStopsEndpointProbingAndRepeatedRequests() = withPortal(rateLimited = true) { server, calls ->
        val c = StalkerCredentials("http://127.0.0.1:${server.port}/c/", "00:1A:79:00:00:01")
        val repo = testRepository()
        repeat(2) {
            try { runBlocking { repo.stalkerFolders(c) }; fail("Expected a rate limit") }
            catch (e: ProviderRateLimitException) { assertTrue(e.retryAfterSeconds > 0) }
        }
        assertEquals(1, calls.size)
    }
}
