package com.stbplay.app.data

import com.stbplay.app.model.MediaItem
import com.stbplay.app.model.MediaKind
import org.json.JSONArray
import org.json.JSONObject

object FavoriteCodec {
    fun encode(items: List<MediaItem>): String = JSONArray().apply {
        items.distinctBy { it.id }.forEach { item -> put(JSONObject().apply {
            put("id", item.id); put("name", item.name); put("url", item.streamUrl)
            put("group", item.group); put("kind", item.kind.name)
            put("cmd", item.cmd); put("logo", item.logo); put("epgId", item.epgId)
        }) }
    }.toString()
    fun decode(json: String): List<MediaItem> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index -> runCatching {
            val o = array.getJSONObject(index)
            fun optional(key: String) = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }
            MediaItem(o.getString("id"), o.getString("name"), o.optString("url"),
                o.optString("group", "Uncategorized"), optional("logo"), optional("epgId"),
                MediaKind.valueOf(o.getString("kind")), optional("cmd"))
        }.getOrNull() }.distinctBy { it.id }
    }.getOrDefault(emptyList())
}
