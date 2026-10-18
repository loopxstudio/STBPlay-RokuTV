package com.stbplay.app.data

import com.stbplay.app.model.StalkerCredentials
import com.stbplay.app.model.MediaKind
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class StalkerVodHierarchyTest {
    private fun portal(legacy: Boolean = false, fileCommand: String = "/media/file_7000.mpg", denyPlayback: Boolean = false, block: (IptvRepository, StalkerCredentials, MutableList<Map<String, String>>) -> Unit) {
        val server = MockWebServer()
        val calls = mutableListOf<Map<String, String>>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val params = url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }
                calls.add(params)
                val body = when (params["action"]) {
                    "handshake" -> """{"js":{"token":"fixture-token"}}"""
                    "get_profile" -> """{"js":{"id":1}}"""
                    "get_categories" -> """{"js":[{"id":"1","title":"Shows"}]}"""
                    "get_ordered_list" -> when {
                        params["movie_id"] == null -> if (legacy)
                            """{"js":{"total_items":1,"data":[{"id":"42","name":"Legacy show","cmd":"/media/42.mpg","series":[1,2,3]}]}}"""
                            else """{"js":{"total_items":1,"data":[{"id":"42","name":"Show","cmd":"/media/42.mpg","is_series":1}]}}"""
                        params["season_id"] == null -> """{"js":{"total_items":1,"data":[{"id":"500","name":"Season 1","is_season":true,"season_number":1}]}}"""
                        params["episode_id"] == null -> """{"js":{"total_items":1,"data":[{"id":"900","name":"Episode 2","is_episode":true,"series_number":2}]}}"""
                        else -> """{"js":{"total_items":1,"data":[{"id":"7000","name":"HD","is_file":true,"cmd":"$fileCommand","container_extension":"mkv"}]}}"""
                    }
                    "create_link" -> if (!denyPlayback && ((params["cmd"] == "/media/file_7000.mpg" && params["series"] == "2") ||
                        (legacy && params["cmd"] == "/media/42.mpg" && params["series"] == "2")))
                        """{"js":{"cmd":"http://127.0.0.1:${server.port}/video.mp4"}}"""
                        else """{"js":{"cmd":"","error":"nothing_to_play"}}"""
                    else -> """{"js":false}"""
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        try { block(testRepository(), StalkerCredentials(server.url("/c/").toString(), "00:1A:79:00:00:01"), calls) }
        finally { server.shutdown() }
    }

    @Test fun vodShowBrowsesSeasonEpisodeAndFileBeforeCreatingLink() = portal { repository, credentials, calls -> runBlocking {
        val show = repository.stalkerVod(credentials).items.single()
        assertEquals(MediaKind.MOVIE, show.kind)
        assertTrue(StalkerVodRoute.decode(show.cmd)!!.browsable)
        val season = repository.stalkerVodChildren(credentials, show).single()
        val episode = repository.stalkerVodChildren(credentials, season).single()
        val file = repository.stalkerVodChildren(credentials, episode).single()
        assertEquals(MediaKind.EPISODE, file.kind)
        assertFalse(calls.any { it["action"] == "create_link" })
        val link = repository.resolveStalkerStream(credentials, file.cmd!!, true)
        assertTrue(link.endsWith("/video.mp4"))
        val request = calls.last()
        assertEquals("/media/file_7000.mpg", request["cmd"])
        assertEquals("2", request["series"])
        assertEquals("", request["forced_storage"])
        assertTrue(calls.any { it["movie_id"] == "42" && it["season_id"] == "500" && it["episode_id"] == "900" })
    } }

    @Test fun customFileUrlUsesSelectedFileTokenInsteadOfSubmittingUrlToCreateLink() = portal(fileCommand = "ffmpeg http://storage.invalid/episode.mkv") { repository, credentials, calls -> runBlocking {
        val show = repository.stalkerVod(credentials).items.single()
        val season = repository.stalkerVodChildren(credentials, show).single()
        val episode = repository.stalkerVodChildren(credentials, season).single()
        val file = repository.stalkerVodChildren(credentials, episode).single()
        val restored = FavoriteCodec.decode(FavoriteCodec.encode(listOf(file))).single()
        assertEquals("7000", StalkerVodRoute.decode(restored.cmd)!!.fileId)
        assertTrue(repository.resolveStalkerStream(credentials, restored.cmd!!, true).endsWith("/video.mp4"))
        assertEquals("/media/file_7000.mpg", calls.last()["cmd"])
        assertEquals("2", calls.last()["series"])
    } }

    @Test fun fileContainerSuffixDoesNotChangePortalApiToken() = portal(fileCommand = "/media/file_7000.mkv") { repository, credentials, calls -> runBlocking {
        val show = repository.stalkerVod(credentials).items.single()
        val season = repository.stalkerVodChildren(credentials, show).single()
        val episode = repository.stalkerVodChildren(credentials, season).single()
        val file = repository.stalkerVodChildren(credentials, episode).single()
        assertTrue(repository.resolveStalkerStream(credentials, file.cmd!!, true).endsWith("/video.mp4"))
        // Older cached routes had no separate file ID; recover it from the token.
        val old = StalkerVodRoute.decode(file.cmd)!!.copy(fileId = null)
        assertTrue(repository.resolveStalkerStream(credentials, old.encode(), true).endsWith("/video.mp4"))
        assertEquals("/media/file_7000.mpg", calls.last()["cmd"])
    } }

    @Test fun rejectedSelectedFileNeverPlaysRawUrlOrAnotherEpisode() = portal(fileCommand = "http://storage.invalid/episode.mkv", denyPlayback = true) { repository, credentials, calls -> runBlocking {
        val show = repository.stalkerVod(credentials).items.single()
        val season = repository.stalkerVodChildren(credentials, show).single()
        val episode = repository.stalkerVodChildren(credentials, season).single()
        val file = repository.stalkerVodChildren(credentials, episode).single()
        try { repository.resolveStalkerStream(credentials, file.cmd!!, true); fail("Must preserve rejection") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("nothing_to_play")) }
        assertEquals(listOf("/media/file_7000.mpg"), calls.filter { it["action"] == "create_link" }.map { it["cmd"] })
    } }

    @Test fun legacyVodEpisodeListPreservesSelectedEpisodeNumber() = portal(legacy = true) { repository, credentials, calls -> runBlocking {
        val show = repository.stalkerVod(credentials).items.single()
        val episodes = repository.stalkerVodChildren(credentials, show)
        assertEquals(listOf("Episode 1", "Episode 2", "Episode 3"), episodes.map { it.name })
        assertTrue(repository.resolveStalkerStream(credentials, episodes[1].cmd!!, true).endsWith("/video.mp4"))
        assertEquals("2", calls.last()["series"])
        assertFalse(calls.any { it["season_id"] != null })
    } }

    @Test fun favoriteRoundTripRetainsEpisodeContext() {
        val route = StalkerVodRoute("42", StalkerVodRoute.PLAY, "500", "900", 2, "/media/file_7000.mpg")
        val item = com.stbplay.app.model.MediaItem("stalker-vod-fixture", "Episode 2", "", kind = MediaKind.EPISODE, cmd = route.encode())
        val restored = FavoriteCodec.decode(FavoriteCodec.encode(listOf(item))).single()
        assertEquals(route, StalkerVodRoute.decode(restored.cmd))
    }

    @Test fun browsingNodeCannotBeMistakenForAPlayableFile() = portal { repository, credentials, calls -> runBlocking {
        val show = repository.stalkerVod(credentials).items.single()
        try { repository.resolveStalkerStream(credentials, show.cmd!!, true); fail("Show must be browsed before playback") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Select a season or episode")) }
        assertFalse(calls.any { it["action"] == "create_link" })
    } }
}
