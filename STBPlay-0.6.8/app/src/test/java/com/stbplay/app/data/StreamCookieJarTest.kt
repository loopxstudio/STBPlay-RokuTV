package com.stbplay.app.data

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class StreamCookieJarTest {
    @Test fun manifestSessionCookieReachesTheFollowingMediaRequest() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Set-Cookie", "stream_session=fixture; Path=/media").setBody("manifest"))
            server.enqueue(MockResponse().setBody("segment"))
            val client = OkHttpClient.Builder().cookieJar(StreamCookieJar()).build()
            client.newCall(okhttp3.Request.Builder().url(server.url("/media/index.m3u8")).build()).execute().use { assertTrue(it.isSuccessful) }
            client.newCall(okhttp3.Request.Builder().url(server.url("/media/segment.ts")).build()).execute().use { assertTrue(it.isSuccessful) }
            assertNull(server.takeRequest().getHeader("Cookie"))
            assertEquals("stream_session=fixture", server.takeRequest().getHeader("Cookie"))
        } finally { server.shutdown() }
    }

    @Test fun cookiesRespectHostPathAndHttpsAndExpireOnDeletion() {
        val jar = StreamCookieJar()
        val origin = "https://portal.example/media/index.m3u8".toHttpUrl()
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "session=fixture; Path=/media; Secure")!!))
        assertEquals(1, jar.loadForRequest("https://portal.example/media/segment.ts".toHttpUrl()).size)
        assertTrue(jar.loadForRequest("https://other.example/media/segment.ts".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://portal.example/private/segment.ts".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://portal.example/media/segment.ts".toHttpUrl()).isEmpty())
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "session=; Path=/media; Max-Age=0")!!))
        assertTrue(jar.loadForRequest(origin).isEmpty())
        assertTrue(StreamCookieJar().loadForRequest(origin).isEmpty())
    }
}
