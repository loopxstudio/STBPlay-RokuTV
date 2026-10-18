package com.stbplay.app.data

import android.content.Context
import com.stbplay.app.model.StalkerCredentials
import com.stbplay.app.model.XtreamCredentials

class Preferences(val context: Context) {
    companion object {
        fun sourceKey(protocol: String, server: String, account: String): String {
            var normalized = server.trim().trimEnd('/')
            if (protocol == "STALKER") {
                for (suffix in listOf("/server/load.php", "/portal.php", "/c")) normalized = normalized.removeSuffix(suffix).trimEnd('/')
                normalized = normalized.removeSuffix("/stalker_portal").trimEnd('/')
            }
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest("$protocol|$normalized|$account".toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
    fun currentSourceKey(): String = when (val protocol = sourceProtocol()) {
        "STALKER" -> stalkerCredentials().let { sourceKey(protocol, it.cleanServer, it.cleanMac) }
        "XTREAM" -> xtreamCredentials().let { sourceKey(protocol, it.server, it.username) }
        else -> sourceKey(protocol, playlistUrl(), "")
    }
    fun favoriteItems(source: String) = FavoriteCodec.decode(prefs.getString("favorite_items_$source", "[]") ?: "[]")
    fun saveFavoriteItems(source: String, items: List<com.stbplay.app.model.MediaItem>) {
        prefs.edit().putString("favorite_items_$source", FavoriteCodec.encode(items)).apply()
    }
    fun option(key: String, fallback: String = "") = prefs.getString("player_$key", fallback) ?: fallback
    fun saveOption(key: String, value: String) { prefs.edit().putString("player_$key", value).apply() }

    fun watchHistory(source: String) = FavoriteCodec.decode(option("history_$source", "[]"))
    fun resumePosition(source: String, item: com.stbplay.app.model.MediaItem): Long =
        if (item.kind == com.stbplay.app.model.MediaKind.LIVE) 0 else PlaybackProgress.resumePosition(
            option("position_${source}_${item.kind}_${item.id}", "0").toLongOrNull() ?: 0,
            option("duration_${source}_${item.kind}_${item.id}", "0").toLongOrNull() ?: 0)
    fun recordPlayback(source: String, item: com.stbplay.app.model.MediaItem, position: Long, duration: Long) {
        saveOption("history_$source", FavoriteCodec.encode((listOf(item) + watchHistory(source).filterNot { it.id == item.id && it.kind == item.kind }).take(12)))
        if (item.kind != com.stbplay.app.model.MediaKind.LIVE) {
            saveOption("position_${source}_${item.kind}_${item.id}", position.coerceAtLeast(0).toString())
            saveOption("duration_${source}_${item.kind}_${item.id}", duration.coerceAtLeast(0).toString())
        }
    }
    fun clearWatchHistory(source: String) {
        watchHistory(source).forEach {
            saveOption("position_${source}_${it.kind}_${it.id}", "0")
            saveOption("duration_${source}_${it.kind}_${it.id}", "0")
        }
        saveOption("history_$source", "[]")
    }

    private val prefs = SecurePreferences(context, "stbplay_preferences")

    fun clearAllWatchHistory() { prefs.removeMatching { it.startsWith("player_history_") || it.startsWith("player_position_") || it.startsWith("player_duration_") } }

    fun forgetSource(source: String) {
        val current = currentSourceKey() == source
        prefs.removeMatching { it.contains(source) || current && it in setOf("source_protocol", "stalker_server", "stalker_mac", "xtream_server", "xtream_username", "xtream_password", "playlist_url") }
    }

    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
    fun saveFavorites(ids: Set<String>) { prefs.edit().putStringSet("favorites", ids.toSet()).apply() }

    fun stalkerCredentials(): StalkerCredentials = StalkerCredentials(
        prefs.getString("stalker_server", "") ?: "",
        prefs.getString("stalker_mac", "") ?: ""
    )
    fun saveStalkerCredentials(c: StalkerCredentials) {
        prefs.edit()
            .putString("source_protocol", "STALKER")
            .putString("stalker_server", c.server)
            .putString("stalker_mac", c.mac)
            .apply()
    }
    fun xtreamCredentials() = XtreamCredentials(
        prefs.getString("xtream_server", "") ?: "",
        prefs.getString("xtream_username", "") ?: "",
        prefs.getString("xtream_password", "") ?: ""
    )
    fun playlistUrl(): String = prefs.getString("playlist_url", "") ?: ""
    fun sourceProtocol(): String = prefs.getString("source_protocol", "STALKER") ?: "STALKER"
    fun saveSource(protocol: String, stalker: StalkerCredentials, xtream: XtreamCredentials, playlist: String) {
        prefs.edit().putString("source_protocol", protocol)
            .putString("stalker_server", stalker.server).putString("stalker_mac", stalker.mac)
            .putString("xtream_server", xtream.server).putString("xtream_username", xtream.username)
            .putString("xtream_password", xtream.password).putString("playlist_url", playlist).apply()
    }
}
