package com.stbplay.app.model

data class MediaItem(
    val id: String,
    val name: String,
    val streamUrl: String,
    val group: String = "Uncategorized",
    val logo: String? = null,
    val epgId: String? = null,
    val kind: MediaKind = MediaKind.LIVE,
    val cmd: String? = null
)

enum class MediaKind { LIVE, MOVIE, SERIES, EPISODE }

data class XtreamCredentials(val server: String, val username: String, val password: String)

data class StalkerCredentials(val server: String, val mac: String) {
    val cleanServer: String
        get() {
            var s = server.trim().trimEnd('/')
            if (s.endsWith("/c")) s = s.removeSuffix("/c").trimEnd('/')
            if (s.endsWith("/portal.php")) s = s.removeSuffix("/portal.php").trimEnd('/')
            return s
        }
    val cleanMac: String
        get() = mac.trim().uppercase()
}

data class EpgProgram(val channelId: String, val title: String, val startMillis: Long, val endMillis: Long)
