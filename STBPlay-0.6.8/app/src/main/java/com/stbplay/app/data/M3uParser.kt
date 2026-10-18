package com.stbplay.app.data

import com.stbplay.app.model.*
import java.net.URI

object M3uParser {
    data class M3uResult(val items: List<MediaItem>, val epgUrl: String?)

    private val attrs = Regex("([\\w-]+)\\s*=\\s*\"([^\"]*)\"")
    
    fun parse(text: String, baseUrl: String? = null): M3uResult {
        val result = mutableListOf<MediaItem>()
        var info: String? = null
        var groupDirective: String? = null
        var epgUrl: String? = null
        val headers = linkedMapOf<String, String>()
        
        text.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.startsWith("#EXTM3U", true) -> {
                    val meta = attrs.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    epgUrl = meta["x-tvg-url"] ?: meta["url-tvg"]
                }
                line.startsWith("#EXTINF", true) -> { info = line; headers.clear() }
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val option = line.substringAfter(':')
                    val name = when (option.substringBefore('=').lowercase()) {
                        "http-user-agent" -> "User-Agent"
                        "http-referrer", "http-referer" -> "Referer"
                        "http-origin" -> "Origin"
                        else -> null
                    }
                    if (name != null && '=' in option) headers[name] = option.substringAfter('=')
                }
                line.startsWith("#EXTGRP:", true) -> groupDirective = line.substringAfter(':').trim()
                line.isNotEmpty() && !line.startsWith('#') && info != null -> {
                    val meta = attrs.findAll(info.orEmpty()).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val address = line.substringBefore('|')
                    val resolved = runCatching { if (baseUrl == null) address else URI(baseUrl).resolve(address).toString() }.getOrDefault(address)
                    val suffix = line.substringAfter('|', "")
                    val options = headers.entries.joinToString("&") { (name, value) -> "$name=${java.net.URLEncoder.encode(value, "UTF-8")}" }
                    val combined = listOf(options, suffix).filter { it.isNotEmpty() }.joinToString("&")
                    val url = resolved + if (combined.isEmpty()) "" else "|$combined"
                    val title = info.orEmpty().substringAfter(',', "").trim().ifBlank { meta["tvg-name"] ?: "Unnamed channel" }
                    val group = meta["group-title"].orEmpty().ifBlank { groupDirective.orEmpty().ifBlank { "Uncategorized" } }
                    result += MediaItem("m3u-${result.size}-${url.hashCode()}", title, url, group,
                        meta["tvg-logo"]?.takeIf { it.isNotBlank() }, meta["tvg-id"]?.takeIf { it.isNotBlank() },
                        kind = when {
                            group.contains("series", true) -> MediaKind.EPISODE
                            group.contains("movie", true) || group.contains("vod", true) || url.substringBefore('?').matches(Regex("(?i).*\\.(mp4|mkv|avi|mov)$")) -> MediaKind.MOVIE
                            else -> MediaKind.LIVE
                        })
                    info = null
                    groupDirective = null
                    headers.clear()
                }
            }
        }
        return M3uResult(result, epgUrl)
    }
}
