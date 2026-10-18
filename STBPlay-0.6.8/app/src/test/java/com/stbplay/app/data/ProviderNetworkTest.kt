package com.stbplay.app.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProviderNetworkTest {
    @Test fun httpRemainsSupportedAndRedirectsKeepTheFinalAddress() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
            server.enqueue(MockResponse().setBody("ok"))
            val conn = ProviderNetwork.open(server.url("/start").toString())
            try { assertEquals(server.url("/final").toString(), conn.url.toString()); assertEquals("ok", conn.inputStream.bufferedReader().readText()) }
            finally { conn.disconnect() }
        } finally { server.shutdown() }
    }
    @Test fun authenticationHeadersAreNotForwardedToAnotherAuthority() {
        val first = MockWebServer(); val second = MockWebServer(); first.start(); second.start()
        try {
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/final")))
            second.enqueue(MockResponse().setBody("ok"))
            val conn = ProviderNetwork.open(first.url("/start").toString()) { it.setRequestProperty("Authorization", "Bearer PRIVATE"); it.setRequestProperty("Cookie", "mac=PRIVATE") }
            conn.disconnect()
            val request = second.takeRequest()
            assertFalse(request.getHeader("Authorization").orEmpty().contains("PRIVATE"))
            assertFalse(request.getHeader("Cookie").orEmpty().contains("PRIVATE"))
        } finally { first.shutdown(); second.shutdown() }
    }
}
