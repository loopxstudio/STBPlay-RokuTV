package com.stbplay.app.data

import com.stbplay.app.model.StalkerCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in integration check. Credentials are read only from the test environment. */
class PortalSmokeTest {
    @Test fun authenticatedPortalReturnsMoviesAndLiveChannels() {
        val url = System.getenv("STBPLAY_TEST_URL") ?: ""
        val mac = System.getenv("STBPLAY_TEST_MAC") ?: ""
        assumeTrue(url.isNotBlank() && mac.isNotBlank())
        val repository = testRepository()
        val credentials = StalkerCredentials(url, mac)
        runBlocking {
            val first = repository.stalkerVod(credentials)
            assertTrue("Expected a movie catalog", first.items.isNotEmpty())
            if (first.next != null) {
                val second = repository.stalkerVod(credentials, cursor = first.next)
                assertTrue("Expected a distinct next page", second.items.isNotEmpty() && first.items.map { it.id } != second.items.map { it.id })
            }
            val live = repository.stalkerLive(credentials)
            assertTrue("Expected a live channel catalog", live.isNotEmpty())
            println("Portal smoke test: ${first.items.size} movies on first page, ${first.total} catalog entries, ${live.size} live channels")
        }
    }
}
