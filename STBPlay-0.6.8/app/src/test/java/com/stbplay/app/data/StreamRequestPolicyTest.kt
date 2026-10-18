package com.stbplay.app.data

import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class StreamRequestPolicyTest {
    @Test fun explicitProviderCookieAndNewServerSessionBothReachTheNextRequest() {
        val server = MockWebServer(); server.start()
        try {
            val cookies = StreamCookieJar()
            val origin = AtomicReference<HttpUrl?>(server.url("/index.m3u8"))
            val client = OkHttpClient.Builder().cookieJar(cookies).addNetworkInterceptor(StreamRequestPolicy(origin, cookies)).build()
            server.enqueue(MockResponse().setHeader("Set-Cookie", "session=new; Path=/"))
            server.enqueue(MockResponse())
            repeat(2) { i -> client.newCall(Request.Builder().url(server.url(if (i == 0) "/index.m3u8" else "/segment.ts"))
                .header("Cookie", "provider=fixture; session=old").build()).execute().close() }
            server.takeRequest()
            assertEquals("provider=fixture; session=new", server.takeRequest().getHeader("Cookie"))
        } finally { server.shutdown() }
    }

    @Test fun independentCdnSegmentRequestsDoNotReceiveProviderCredentials() {
        val provider = MockWebServer(); val cdn = MockWebServer()
        provider.start(); cdn.start()
        try {
            val cookies = StreamCookieJar()
            val origin = AtomicReference<HttpUrl?>(provider.url("/index.m3u8"))
            val client = OkHttpClient.Builder().cookieJar(cookies).addNetworkInterceptor(StreamRequestPolicy(origin, cookies)).build()
            val destination = cdn.url("/segment.ts").newBuilder().host("localhost").build()
            cookies.saveFromResponse(destination, listOf(Cookie.parse(destination, "cdn_session=fixture; Path=/")!!))
            cdn.enqueue(MockResponse())
            client.newCall(Request.Builder().url(destination).header("Authorization", "Bearer provider-fixture")
                .header("Cookie", "provider_secret=fixture").build()).execute().close()
            val request = cdn.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertEquals("cdn_session=fixture", request.getHeader("Cookie"))
        } finally { provider.shutdown(); cdn.shutdown() }
    }
}
