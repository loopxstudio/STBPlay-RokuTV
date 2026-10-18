package com.stbplay.app.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** In-memory playback cookies. One instance per connected source, never persisted. */
internal class StreamCookieJar : CookieJar {
    private val stored = mutableListOf<Cookie>()

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        stored.removeAll { it.expiresAt <= now }
        cookies.forEach { cookie ->
            // Domain validation is normally performed by Cookie.parseAll as well.
            if (cookie.domain != url.host && (cookie.hostOnly || !url.host.endsWith(".${cookie.domain}"))) return@forEach
            stored.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > now) stored.add(cookie)
        }
        while (stored.size > 128) stored.removeAt(0)
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        stored.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return stored.filter { it.matches(url) }
    }
}
