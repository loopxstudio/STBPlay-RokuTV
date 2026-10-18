package com.stbplay.app.data

/** Keep MAG identification specific to Stalker, rather than every provider. */
internal data class PlaybackRequest(val url: String, val headers: Map<String, String>)

internal object PlaybackRequests {
    const val APP_USER_AGENT = "STBPlay/0.6.8"
    const val STALKER_USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp main.js safari/533.3"
    fun userAgent(stalker: Boolean, headers: Map<String, String> = emptyMap()) = headers["User-Agent"] ?: if (stalker) STALKER_USER_AGENT else APP_USER_AGENT
    fun parse(address: String): PlaybackRequest {
        val headers = linkedMapOf<String, String>()
        val names = mapOf("user-agent" to "User-Agent", "referer" to "Referer", "referrer" to "Referer",
            "origin" to "Origin", "cookie" to "Cookie", "authorization" to "Authorization", "accept" to "Accept")
        address.substringAfter('|', "").split('&').forEach { part ->
            if ('=' !in part) return@forEach
            fun decode(value: String) = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            val name = names[decode(part.substringBefore('=')).lowercase()] ?: return@forEach
            val value = decode(part.substringAfter('='))
            if ('\r' !in value && '\n' !in value) headers[name] = value
        }
        return PlaybackRequest(address.substringBefore('|').trim(), headers)
    }
}
