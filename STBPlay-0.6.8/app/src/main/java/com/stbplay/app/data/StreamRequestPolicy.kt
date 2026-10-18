package com.stbplay.app.data

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference

/** Credentials belong to the resolved stream origin, including later HLS segment requests. */
internal class StreamRequestPolicy(
    private val origin: AtomicReference<HttpUrl?>,
    private val cookies: StreamCookieJar
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val root = origin.get() ?: chain.call().request().url
        if (chain.call().request().url.isHttps && !request.url.isHttps)
            throw java.io.IOException("HTTPS downgrade refused")
        val sameOrigin = root.scheme == request.url.scheme && root.host == request.url.host && root.port == request.url.port
        val builder = request.newBuilder().removeHeader("Cookie")
        if (!sameOrigin) builder.removeHeader("Authorization")
        val values = linkedMapOf<String, String>()
        if (sameOrigin) chain.call().request().header("Cookie")?.split(';')?.forEach { pair ->
            if ('=' in pair) values[pair.substringBefore('=').trim()] = pair.substringAfter('=').trim()
        }
        // Latest server cookies override stale values supplied with the playlist.
        cookies.loadForRequest(request.url).forEach { values[it.name] = it.value }
        if (values.isNotEmpty()) builder.header("Cookie", values.entries.joinToString("; ") { "${it.key}=${it.value}" })
        return chain.proceed(builder.build())
    }
}
