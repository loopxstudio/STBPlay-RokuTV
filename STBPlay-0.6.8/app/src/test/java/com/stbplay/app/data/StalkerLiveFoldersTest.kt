package com.stbplay.app.data

import com.stbplay.app.model.StalkerCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class StalkerLiveFoldersTest {
    private fun portal(genres: String, paginated: Boolean = false, test: (IptvRepository, StalkerCredentials, MutableList<RecordedRequest>) -> Unit) {
        val server = MockWebServer()
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val url = request.requestUrl!!
                val body = when (url.queryParameter("action")) {
                    "handshake" -> """{"js":{"token":"test"}}"""
                    "get_profile" -> """{"js":{"id":1}}"""
                    "get_genres" -> genres
                    "get_all_channels" -> if (paginated) """{"js":{"total_items":3,"data":[{"id":"1","name":"One","tv_genre_id":"10","cmd":"http://example.invalid/1"}]}}"""
                        else """{"js":{"data":[{"id":"1","name":"One","category_id":"10","cmd":"http://example.invalid/1"},{"id":"2","name":"Two","tv_genre_id":"20","cmd":"http://example.invalid/2"}]}}"""
                    "get_ordered_list" -> when (url.queryParameter("p")) {
                        "0", "1" -> """{"js":{"total_items":3,"data":[{"id":"1","name":"One","tv_genre_id":"10","cmd":"http://example.invalid/1"}]}}"""
                        "2" -> """{"js":{"total_items":3,"data":[{"id":"2","name":"Two","tv_genre_id":"20","cmd":"http://example.invalid/2"},{"id":"3","name":"Three","tv_genre_id":"30","cmd":"http://example.invalid/3"}]}}"""
                        else -> """{"js":{"data":[]}}"""
                    }
                    else -> """{"js":false}"""
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        try { test(testRepository(), StalkerCredentials(server.url("/").toString(), "00:1A:79:00:00:01"), requests) }
        finally { server.shutdown() }
    }
    @Test fun categoryAliasesMapChannelsAndEmptyProviderFoldersAreRetained() = portal("""{"js":[{"category_id":"10","category_name":"News"},{"id":"20","title":"Sports"},{"genre_id":"30","name":"Kids"}]}""") { repo, c, _ ->
        val items = runBlocking { repo.stalkerLive(c) }
        assertEquals(listOf("News", "Sports"), items.map { it.group })
        assertEquals(setOf("News", "Sports", "Kids"), repo.liveFolderMetadata.map { it.title }.toSet())
    }
    @Test fun partialAllChannelsLoadsRemainingOrderedPagesWithAllGenres() = portal("""{"js":[{"id":"10","title":"News"},{"id":"20","title":"Sports"},{"id":"30","title":"Kids"}]}""", true) { repo, c, requests ->
        val items = runBlocking { repo.stalkerLive(c) }
        assertEquals(listOf("stalker-live-1", "stalker-live-2", "stalker-live-3"), items.map { it.id })
        assertEquals(listOf("News", "Sports", "Kids"), items.map { it.group })
        val pages = requests.filter { it.requestUrl!!.queryParameter("action") == "get_ordered_list" }
        assertEquals(listOf("0", "1", "2"), pages.map { it.requestUrl!!.queryParameter("p") })
        assertTrue(pages.all { it.requestUrl!!.queryParameter("genre") == "*" })
    }
    @Test fun keyedGenreMapsRetainFolderNames() = portal("""{"js":{"10":"News","20":"Sports"}}""") { repo, c, _ ->
        assertEquals(setOf("News", "Sports"), runBlocking { repo.stalkerLive(c) }.map { it.group }.toSet())
    }
}
