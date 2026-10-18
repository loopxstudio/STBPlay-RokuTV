package com.stbplay.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Local catalog context, stored in the existing protected command field. Not a stream URL. */
internal data class StalkerVodRoute(
    val movieId: String,
    val level: String = ROOT,
    val seasonId: String? = null,
    val episodeId: String? = null,
    val episodeNumber: Int? = null,
    val command: String = "",
    val legacyEpisodes: List<Int> = emptyList(),
    val catalogType: String = "vod",
    val fileId: String? = null
) {
    val browsable get() = level != PLAY
    fun encode(): String = PREFIX + JSONObject().apply {
        put("movie", movieId); put("level", level); put("season", seasonId)
        put("episode", episodeId); put("number", episodeNumber); put("command", command)
        put("episodes", JSONArray(legacyEpisodes)); put("type", catalogType); put("file", fileId)
    }.toString()

    companion object {
        const val ROOT = "root"
        const val SEASON = "season"
        const val EPISODE = "episode"
        const val PLAY = "play"
        private const val PREFIX = "stbplay-stalker-vod:"
        fun decode(command: String?): StalkerVodRoute? {
            if (command?.startsWith(PREFIX) != true) return null
            return runCatching {
                val o = JSONObject(command.removePrefix(PREFIX))
                fun optional(key: String) = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }
                val level = o.getString("level")
                require(level in listOf(ROOT, SEASON, EPISODE, PLAY))
                val type = o.optString("type", "vod")
                require(type in listOf("vod", "series"))
                StalkerVodRoute(o.getString("movie"), level, optional("season"), optional("episode"),
                    o.optInt("number").takeIf { it > 0 }, o.optString("command"), numbers(o.opt("episodes")), type, optional("file"))
            }.getOrNull()
        }

        fun numbers(value: Any?): List<Int> {
            val array = when (value) {
                is JSONArray -> value
                is String -> runCatching { JSONArray(value) }.getOrNull()
                else -> null
            } ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optString(it).toIntOrNull()?.takeIf { n -> n > 0 } }.distinct().sorted()
        }

        fun text(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key).trim()
        fun flag(o: JSONObject, key: String) = o.optBoolean(key) || o.optInt(key) == 1
    }
}
