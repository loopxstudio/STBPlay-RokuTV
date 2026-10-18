package com.stbplay.app.data

import com.stbplay.app.data.db.AppDatabase
import com.stbplay.app.data.db.MediaEntity
import androidx.room.withTransaction

import com.stbplay.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class StalkerCatalogCursor(val page: Int, val previousIds: List<String>, val loaded: Int, val search: String, val category: String? = null)
class ProviderRateLimitException(val retryAfterSeconds: Long) : IllegalStateException("Provider rate limit (HTTP 429). Wait $retryAfterSeconds seconds before retrying.")
data class StalkerPlayback(val url: String, val mimeType: String? = null)
data class CatalogFolder(val id: String, val title: String, val count: Int? = null)
data class StalkerCatalogPage(val items: List<MediaItem>, val next: StalkerCatalogCursor?, val total: Int)

/** Network access stays on IO; URLs and credentials are never written to logs. */

class IptvRepository(private val database: AppDatabase) {
    suspend fun cacheMedia(playlistId: Long, kind: MediaKind, items: List<MediaItem>, replace: Boolean = true) {
        database.withTransaction {
            if (replace) database.mediaDao().deleteByKind(playlistId, kind)
            database.mediaDao().insertAll(items.map {
                MediaEntity(it.id, playlistId, it.name, it.kind, it.streamUrl, it.group, it.logo, it.epgId, it.cmd)
            })
            val loaded = items.associateBy { it.id }
            val refreshedFavorites = database.favoriteDao().getSaved(playlistId).mapNotNull { favorite ->
                loaded[favorite.id]?.let { favorite.copy(payload = FavoriteCodec.encode(listOf(it))) }
            }
            database.favoriteDao().save(refreshedFavorites)
        }
    }

    private var rateLimitedUntil = 0L
    private var rateLimitedSource: String? = null
    private var stalkerToken: String? = null
    private var workingStalkerUrl: String? = null
    private var stalkerIdentity: Pair<String, String>? = null
    private val folderCache = mutableMapOf<Triple<String, String, String>, Map<String, String>>()
    private val stalkerCookies = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER)

    suspend fun loadM3u(source: String): M3uParser.M3uResult = withContext(Dispatchers.IO) {
        val (text, finalUrl) = fetchText(source)
        require(text.trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("#EXTM3U", true)) { "The source did not return an M3U playlist" }
        if (text.lineSequence().any { it.trim().startsWith("#EXT-X-TARGETDURATION:", true) || it.trim().startsWith("#EXT-X-STREAM-INF:", true) }) {
            // An HLS manifest is one playable stream, not a catalog of its segments.
            val name = URL(finalUrl).path.substringAfterLast('/').ifBlank { "Live stream" }
            M3uParser.M3uResult(listOf(MediaItem("m3u-hls-${source.hashCode()}", name, finalUrl, "Playlist", kind = MediaKind.LIVE)), null)
        } else M3uParser.parse(text, finalUrl)
    }

    suspend fun xtreamLive(c: XtreamCredentials): List<MediaItem> = withContext(Dispatchers.IO) {
        val groups = categories(c, "get_live_categories")
        val json = api(c, "get_live_streams") as? JSONArray ?: error("Invalid live TV response")
        (0 until json.length()).mapNotNull { i ->
            val o = json.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("stream_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MediaItem("live-$id", o.optString("name", "Channel $id"),
                xtreamPlaybackUrl(c, o, "live", id, "ts"),
                groups[o.optString("category_id")] ?: "Live TV", o.optString("stream_icon").takeIf { it.startsWith("http") },
                o.optString("epg_channel_id").ifBlank { null }, MediaKind.LIVE)
        }
    }

    suspend fun xtreamVod(c: XtreamCredentials, series: Boolean = false): List<MediaItem> = withContext(Dispatchers.IO) {
        val groups = categories(c, if (series) "get_series_categories" else "get_vod_categories")
        val json = api(c, if (series) "get_series" else "get_vod_streams") as? JSONArray ?: error("Invalid catalog response")
        (0 until json.length()).mapNotNull { i ->
            val o = json.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString(if (series) "series_id" else "stream_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ext = o.optString("container_extension", "mp4").ifBlank { "mp4" }
            MediaItem("${if (series) "series" else "movie"}-$id", o.optString("name", "Title $id"),
                if (series) "" else xtreamPlaybackUrl(c, o, "movie", id, ext),
                groups[o.optString("category_id")] ?: if (series) "Series" else "Movies",
                o.optString(if (series) "cover" else "stream_icon").takeIf { it.startsWith("http") },
                kind = if (series) MediaKind.SERIES else MediaKind.MOVIE)
        }
    }

    /** A series is a catalog entry, never a playable URL. Resolve its episodes first. */
    suspend fun seriesEpisodes(c: XtreamCredentials, seriesId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val response = api(c, "get_series_info", "series_id" to seriesId) as? JSONObject ?: error("Invalid series response")
        val seasons = response.optJSONObject("episodes") ?: return@withContext emptyList()
        buildList {
            for (season in seasons.keys()) {
                val episodes = seasons.optJSONArray(season) ?: continue
                for (i in 0 until episodes.length()) {
                    val o = episodes.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val ext = o.optString("container_extension", "mp4").ifBlank { "mp4" }
                    add(MediaItem("episode-$seriesId-$id", "S$season E${o.optString("episode_num", "${i+1}")} · ${o.optString("title", "Episode")}",
                        xtreamPlaybackUrl(c, o, "series", id, ext),
                        "Season $season", kind = MediaKind.EPISODE))
                }
            }
        }
    }

    // ==========================================
    // STALKER PORTAL (MAC AUTH) SUPPORT
    // ==========================================

    var liveFolderMetadata: List<CatalogFolder> = emptyList()
        private set

    suspend fun stalkerLive(c: StalkerCredentials): List<MediaItem> = withContext(Dispatchers.IO) {
        val genres = stalkerGenres(c)
        val response = try { stalkerApi(c, "itv", "get_all_channels") }
            catch (e: ProviderRateLimitException) { throw e }
            catch (_: Exception) { null }
        var rawItems = extractObjectList(response)
        val payload = (response as? JSONObject)?.optJSONObject("js") ?: response as? JSONObject
        val total = payload?.optInt("total_items", 0)?.takeIf { it > 0 } ?: payload?.optInt("total", 0) ?: 0
        if (rawItems.isEmpty() || total > rawItems.size) {
            val pages = stalkerOrderedList(c, "itv")
            // Keep the complete list, refreshing duplicate entries with ordered-list metadata.
            rawItems = (rawItems + pages).associateBy { it.optString("id").ifBlank { it.optString("number") } }.values.toList()
        }
        liveFolderMetadata = genres.values.distinct().map { CatalogFolder(it, it) }
        if (rawItems.isEmpty()) error("Portal authenticated but returned an empty live catalog; check subscription access")

        rawItems.mapNotNull { o ->
            val id = o.optString("id").ifBlank { o.optString("number") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = o.optString("name", "Channel $id")
            val genreId = o.optString("tv_genre_id").ifBlank { o.optString("genre_id") }.ifBlank { o.optString("category_id") }
                .ifBlank { o.optJSONObject("tv_genre")?.optString("id").orEmpty() }
            val folder = genres[genreId] ?: o.optString("group-title").ifBlank { o.optString("category_name") }
                .ifBlank { o.optString("genre_title") }.ifBlank { o.optJSONObject("tv_genre")?.optString("title").orEmpty() }.ifBlank { "Live TV" }
            val cmd = o.optString("cmd").ifBlank { o.optString("url") }
            val logo = o.optString("logo").ifBlank { o.optString("icon") }.takeIf { it.startsWith("http") }
            MediaItem("stalker-live-$id", name, cmd, folder, logo, kind = MediaKind.LIVE, cmd = cmd)
        }
    }

    suspend fun stalkerVod(c: StalkerCredentials, series: Boolean = false, cursor: StalkerCatalogCursor? = null, search: String = "", category: String? = null): StalkerCatalogPage = withContext(Dispatchers.IO) {
        val type = if (series) "series" else "vod"
        val categories = stalkerVodCategories(c, type)
        var page = cursor?.page ?: 0
        val searchTerm = cursor?.search ?: search.trim()
        val categoryId = if (cursor != null) cursor.category else category
        var response = stalkerApi(c, type, "get_ordered_list", "p" to page.toString(), "search" to searchTerm, "category" to (categoryId ?: "*"))
        var rawItems = extractObjectList(response)
        fun ids() = rawItems.map { it.optString("id").ifBlank { it.optString("cmd") } }
        // One-based portals often return the same first page for both p=0 and p=1.
        if (page == 1 && rawItems.isNotEmpty() && ids() == cursor?.previousIds) {
            page++
            response = stalkerApi(c, type, "get_ordered_list", "p" to page.toString(), "search" to searchTerm, "category" to (categoryId ?: "*"))
            rawItems = extractObjectList(response)
        }
        check(rawItems.isEmpty() || ids() != cursor?.previousIds) { "Portal repeated a catalog page; try searching for a title" }
        val total = (response as? JSONObject)?.optJSONObject("js")?.optInt("total_items", 0) ?: 0
        val loaded = (cursor?.loaded ?: 0) + rawItems.size
        val next = if (rawItems.isEmpty() || (total > 0 && loaded >= total)) null
            else StalkerCatalogCursor(page + 1, ids(), loaded, searchTerm, categoryId)

        val items = rawItems.mapNotNull { o ->
            val id = o.optString("id").ifBlank { o.optString("vod_id") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = o.optString("name").ifBlank { o.optString("title", "Title $id") }
            val categoryId = o.optString("category_id").ifBlank { o.optString("genre_id") }
            val rawCommand = StalkerVodRoute.text(o, "cmd").ifBlank { StalkerVodRoute.text(o, "url") }
            val episodeNumbers = StalkerVodRoute.numbers(o.opt("series"))
            val isShow = series || StalkerVodRoute.flag(o, "is_series") || episodeNumbers.isNotEmpty()
            val cmd = if (isShow) StalkerVodRoute(id, command = rawCommand,
                legacyEpisodes = episodeNumbers, catalogType = type).encode() else rawCommand.ifBlank { "/media/$id.mpg" }
            val logo = o.optString("screenshot_uri").ifBlank { o.optString("poster").ifBlank { o.optString("logo") } }.takeIf { it.startsWith("http") }
            MediaItem("stalker-${if (series) "series" else "movie"}-$id", name, cmd, categories[categoryId] ?: if (series) "Series" else "Movies", logo, kind = if (series) MediaKind.SERIES else MediaKind.MOVIE, cmd = cmd)
        }
        StalkerCatalogPage(items, next, total)
    }

    /** Browse a show/season/episode without pretending that its row ID is a media file. */
    suspend fun stalkerVodChildren(c: StalkerCredentials, parent: MediaItem): List<MediaItem> = withContext(Dispatchers.IO) {
        val route = StalkerVodRoute.decode(parent.cmd) ?: error("Missing Stalker catalog context; refresh your source")
        require(route.browsable)
        if (route.level == StalkerVodRoute.ROOT && route.legacyEpisodes.isNotEmpty()) {
            return@withContext route.legacyEpisodes.map { number ->
                val play = route.copy(level = StalkerVodRoute.PLAY, episodeNumber = number,
                    command = route.command.ifBlank { "/media/${route.movieId}.mpg" }, legacyEpisodes = emptyList())
                MediaItem("stalker-vod-${route.movieId}-legacy-$number", "Episode $number", "",
                    parent.name, parent.logo, kind = MediaKind.EPISODE, cmd = play.encode())
            }
        }
        val parameters = mutableListOf("movie_id" to route.movieId)
        route.seasonId?.let { parameters.add("season_id" to it) }
        route.episodeId?.let { parameters.add("episode_id" to it) }
        val rows = linkedMapOf<String, JSONObject>()
        var previous = emptyList<String>()
        for (page in 0 until 100) {
            val response = stalkerApi(c, route.catalogType, "get_ordered_list", *(parameters + ("p" to page.toString())).toTypedArray())
            val data = extractObjectList(response)
            if (data.isEmpty()) break
            val ids = data.map { StalkerVodRoute.text(it, "id") }
            if (ids == previous) {
                if (page == 1) continue // Portals that number their first page as 1.
                error("Portal repeated an episode page")
            }
            data.forEach { row -> StalkerVodRoute.text(row, "id").takeIf { it.isNotBlank() }?.let { rows[it] = row } }
            previous = ids
            val total = (response as? JSONObject)?.optJSONObject("js")?.optInt("total_items", 0) ?: 0
            if (total > 0 && rows.size >= total) break
            check(page < 99) { "Portal episode list exceeded pagination limit" }
        }
        rows.values.map { row ->
            val id = StalkerVodRoute.text(row, "id")
            val season = StalkerVodRoute.flag(row, "is_season")
            val episode = StalkerVodRoute.flag(row, "is_episode")
            val number = row.optInt("series_number").takeIf { it > 0 }
            val name = StalkerVodRoute.text(row, "name").ifBlank {
                if (season) "Season ${row.optInt("season_number")}" else if (episode) "Episode ${number ?: id}" else "Video file $id"
            }
            val raw = StalkerVodRoute.text(row, "cmd").ifBlank { StalkerVodRoute.text(row, "url") }
            val legacy = StalkerVodRoute.numbers(row.opt("series"))
            val child = when {
                !episode && !season && legacy.isNotEmpty() -> StalkerVodRoute(id, command = raw,
                    legacyEpisodes = legacy, catalogType = route.catalogType)
                season -> route.copy(level = StalkerVodRoute.SEASON, seasonId = id, command = "", legacyEpisodes = emptyList())
                episode -> route.copy(level = StalkerVodRoute.EPISODE, episodeId = id, episodeNumber = number, command = "", legacyEpisodes = emptyList())
                else -> route.copy(level = StalkerVodRoute.PLAY, command = raw.ifBlank { "/media/file_$id.mpg" }, legacyEpisodes = emptyList(), fileId = id)
            }
            MediaItem("stalker-vod-${route.movieId}-${route.seasonId ?: "0"}-${route.episodeId ?: "0"}-$id", name, "",
                parent.name, parent.logo, kind = if (child.browsable) MediaKind.SERIES else MediaKind.EPISODE, cmd = child.encode())
        }
    }

    suspend fun resolveStalkerStream(c: StalkerCredentials, cmd: String, isVod: Boolean = false, movieId: String? = null): String = withContext(Dispatchers.IO) {
        val route = StalkerVodRoute.decode(cmd)
        check(route?.browsable != true) { "Select a season or episode before requesting playback" }
        // The portal player rebuilds is_file rows using their file ID, even when
        // the catalog includes a custom URL. The .mpg suffix is an API token,
        // not the actual media container. Keep the chosen file and episode together.
        val selectedFileId = route?.fileId ?: route?.command?.let {
            Regex("^/media/file_([0-9]+)\\.[A-Za-z0-9]+$").matchEntire(it)?.groupValues?.get(1)
        }
        val playbackCommand = selectedFileId?.let { "/media/file_$it.mpg" } ?: route?.command ?: cmd
        val cleanCmd = playbackCommand.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
        val resolvedMovieId = route?.movieId ?: movieId
        // A catalog HTTP command can still be a placeholder or expired session URL.
        // Resolve live channels through the portal, just as VOD resolves its command.
        val type = if (isVod) "vod" else "itv"
        fun create(command: String): JSONObject? {
            val extra = mutableListOf("cmd" to command, "forced_storage" to "",
                "disable_ad" to "0", "download" to "0", "force_ch_link_check" to "0")
            route?.episodeNumber?.let { extra.add("series" to it.toString()) }
            val response = stalkerApi(c, type, "create_link", *extra.toTypedArray()) as? JSONObject
            return response?.optJSONObject("js") ?: response?.optJSONObject("result") ?: response
        }
        var link = create(cleanCmd)
        // Catalog /media/<movie-id> commands differ from /media/file_<file-id> commands.
        if (isVod && link?.optString("cmd").isNullOrBlank() && resolvedMovieId != null && cleanCmd.startsWith("/media/") && !cleanCmd.startsWith("/media/file_")) {
            val files = extractObjectList(stalkerApi(c, "vod", "get_ordered_list", "movie_id" to resolvedMovieId, "p" to "0"))
            val file = files.filterNot { StalkerVodRoute.flag(it, "is_season") || StalkerVodRoute.flag(it, "is_episode") }
                .firstOrNull { it.optString("cmd").isNotBlank() || it.optString("url").isNotBlank() || it.optString("id").isNotBlank() }
                ?: error("Provider returned no playable file for this movie")
            // A file's command can differ from /media/file_<id>.<extension>.
            // Preserve the provider's command instead of silently discarding it.
            val fileCommand = file.optString("cmd").takeIf { it.isNotBlank() && it != "null" }
                ?: file.optString("url").takeIf { it.isNotBlank() && it != "null" }
            if (fileCommand != null) {
                link = create(fileCommand.removePrefix("ffrt ").removePrefix("ffmpeg ").trim())
            }
            if (link?.optString("cmd").isNullOrBlank() && file.optString("id").isNotBlank()) {
                // Portal file commands always use .mpg regardless of the file container.
                link = create("/media/file_${file.optString("id")}.mpg")
            }
        }
        val returned = link?.optString("cmd")?.takeIf { it.isNotBlank() && it != "null" }
        if (returned == null) {
            val reason = link?.optString("error").orEmpty()
            if (reason == "nothing_to_play") error("Provider returned nothing_to_play: this stream has no available playback link")
            error("Provider did not return a playable stream link")
        }
        val resolved = returned.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
        val address = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(resolved)?.value
            ?: error("Provider returned an unsupported playback command")
        check(URL(address).host !in listOf("localhost", "127.0.0.1", "::1") || URL(c.server).host in listOf("localhost", "127.0.0.1", "::1")) { "Provider returned an unresolved localhost playback link" }
        address
    }

    fun stalkerPlaybackHeaders(c: StalkerCredentials): Map<String, String> {
        val endpoint = URL(workingStalkerUrl ?: candidateLoadUrls(c).first())
        val root = endpoint.path.substringBeforeLast('/').removeSuffix("/server")
        return mapOf("Referer" to "${endpoint.protocol}://${endpoint.authority}$root/c/", "X-User-Agent" to "Model: MAG250; Link: WiFi")
    }

    suspend fun resolveStalkerPlayback(c: StalkerCredentials, cmd: String, isVod: Boolean = false, movieId: String? = null): StalkerPlayback = withContext(Dispatchers.IO) {
        val address = resolveStalkerStream(c, cmd, isVod, movieId)
        if (!isVod) return@withContext StalkerPlayback(address)
        // This request only discovers extensionless HLS. Some otherwise playable
        // storage servers reject Range or limit preliminary requests. A
        // format hint must never be a prerequisite for the player's real GET.
        var conn: java.net.HttpURLConnection? = null
        val mime = try {
            conn = ProviderNetwork.open(address, 12000) { connection ->
                connection.setRequestProperty("User-Agent", PlaybackRequests.STALKER_USER_AGENT)
                stalkerPlaybackHeaders(c).forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.setRequestProperty("Range", "bytes=0-511")
            }
            if (conn.responseCode !in 200..299) null else {
                val contentType = conn.contentType.orEmpty().lowercase()
                val prefix = conn.inputStream.use { input ->
                    val bytes = ByteArray(512)
                    val length = input.read(bytes)
                    if (length <= 0) "" else String(bytes, 0, length, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n')
                }
                if (contentType.contains("mpegurl") || prefix.startsWith("#EXTM3U")) "application/x-mpegURL" else null
            }
        } catch (_: java.io.IOException) {
            // Let Media3 make the playback request and report its actual error.
            null
        } finally { conn?.disconnect() }
        StalkerPlayback(address, mime)
    }

    private fun stalkerGenres(c: StalkerCredentials): Map<String, String> {
        val res = try { stalkerApi(c, "itv", "get_genres") }
        catch (e: ProviderRateLimitException) { throw e }
        catch (_: Exception) { return emptyMap() }
        val folders = extractObjectList(res).mapNotNull {
            val id = it.optString("id").ifBlank { it.optString("genre_id") }.ifBlank { it.optString("category_id") }.ifBlank { it.optString("tv_genre_id") }
            val title = it.optString("title").ifBlank { it.optString("name") }.ifBlank { it.optString("category_name") }
            if (id.isBlank() || title.isBlank() || id in listOf("*", "all")) null else id to title
        }.toMap().toMutableMap()
        val keyed = (res as? JSONObject)?.optJSONObject("js")
        keyed?.keys()?.forEach { key ->
            val value = keyed.opt(key)
            if (value is String && value.isNotBlank() && key !in listOf("*", "all")) folders.putIfAbsent(key, value)
        }
        return folders
    }

    suspend fun stalkerFolders(c: StalkerCredentials, series: Boolean = false): List<CatalogFolder> = withContext(Dispatchers.IO) {
        stalkerVodCategories(c, if (series) "series" else "vod")
            .filterKeys { it != "*" }.map { CatalogFolder(it.key, it.value) }
    }

    private fun stalkerVodCategories(c: StalkerCredentials, type: String): Map<String, String> {
        val key = Triple(c.server.trim().trimEnd('/'), c.cleanMac, type)
        folderCache[key]?.let { return it }
        val res = stalkerApi(c, type, "get_categories")
        val folders = extractObjectList(res).mapNotNull {
            val id = it.optString("id").ifBlank { it.optString("category_id") }
            val title = it.optString("category_name").ifBlank { it.optString("title").ifBlank { it.optString("name", "Other") } }
            if (id.isBlank()) null else id to title
        }.toMap()
        // Limit retained metadata when users switch between many sources.
        if (folderCache.size >= 8) folderCache.clear()
        folderCache[key] = folders
        return folders
    }

    private fun extractObjectList(res: Any?): List<JSONObject> {
        if (res == null) return emptyList()
        val rootObj = res as? JSONObject
        val rootArr = res as? JSONArray

        if (rootArr != null) {
            return (0 until rootArr.length()).mapNotNull { rootArr.optJSONObject(it) }
        }

        if (rootObj != null) {
            val target = rootObj.opt("js") ?: rootObj.opt("result") ?: rootObj.opt("data") ?: rootObj

            when (target) {
                is JSONArray -> {
                    return (0 until target.length()).mapNotNull { target.optJSONObject(it) }
                }
                is JSONObject -> {
                    val data = target.opt("data") ?: target.opt("items") ?: target.opt("channels") ?: target.opt("rows")
                    when (data) {
                        is JSONArray -> {
                            return (0 until data.length()).mapNotNull { data.optJSONObject(it) }
                        }
                        is JSONObject -> {
                            return data.keys().asSequence().mapNotNull { data.optJSONObject(it) }.toList()
                        }
                    }
                    val list = mutableListOf<JSONObject>()
                    for (key in target.keys()) {
                        val childObj = target.optJSONObject(key)
                        if (childObj != null) {
                            list.add(childObj)
                        } else {
                            val childArr = target.optJSONArray(key)
                            if (childArr != null) {
                                for (i in 0 until childArr.length()) {
                                    childArr.optJSONObject(i)?.let { list.add(it) }
                                }
                            }
                        }
                    }
                    return list
                }
            }
        }
        return emptyList()
    }

    private fun stalkerOrderedList(c: StalkerCredentials, type: String): List<JSONObject> {
        val items = linkedMapOf<String, JSONObject>()
        var previousIds: List<String>? = null
        for (page in 0 until 10000) {
            val response = if (type == "itv") stalkerApi(c, type, "get_ordered_list", "p" to page.toString(), "genre" to "*")
                else stalkerApi(c, type, "get_ordered_list", "p" to page.toString())
            val batch = extractObjectList(response)
            if (batch.isEmpty()) return items.values.toList()
            val ids = batch.map { it.optString("id").ifBlank { it.optString("cmd") } }
            // Some portals map both p=0 and p=1 to their first page.
            if (ids == previousIds) {
                if (page == 1) continue
                error("Portal repeated catalog page; could not load the complete catalog")
            }
            previousIds = ids
            batch.forEachIndexed { index, item -> items[ids[index]] = item }
            val payload = (response as? JSONObject)?.optJSONObject("js")
            val total = payload?.optInt("total_items", 0) ?: 0
            if (total > 0 && items.size >= total) return items.values.toList()
        }
        error("Portal catalog exceeded pagination limit")
    }

    private class PortalHttpException(val status: Int) : IllegalStateException("Stalker Portal returned HTTP $status")

    private fun stalkerApi(c: StalkerCredentials, type: String, action: String, vararg extra: Pair<String, String>): Any? {
        if (rateLimitedSource == URL(c.server).authority && System.currentTimeMillis() < rateLimitedUntil)
            throw ProviderRateLimitException(((rateLimitedUntil - System.currentTimeMillis()) / 1000 + 1).coerceAtLeast(1))
        val identity = c.server.trim().trimEnd('/') to c.cleanMac
        if (stalkerIdentity != identity) {
            stalkerToken = null
            workingStalkerUrl = null
            stalkerIdentity = identity
        }
        if (stalkerToken == null || workingStalkerUrl == null) stalkerHandshake(c)
        fun call(): Any? = stalkerApiCall(c, type, action, *extra)
        val response = try { call() } catch (e: PortalHttpException) {
            if (e.status != 401 && e.status != 403) throw e
            stalkerHandshake(c)
            call()
        }
        if (response is JSONObject && response.opt("js") == false) {
            check(type != "series") { "Provider does not support or allow a separate Series catalog" }
            stalkerHandshake(c)
            val retry = call()
            check(retry !is JSONObject || retry.opt("js") != false) { "Portal rejected $action after authentication; check MAC and subscription" }
            return retry
        }
        return response
    }

    private fun candidateLoadUrls(c: StalkerCredentials): List<String> {
        val s = c.server.trim().trimEnd('/')
        require(s.startsWith("https://") || s.startsWith("http://")) { "Use an HTTP(S) server URL" }

        val parsed = URL(s)
        val path = parsed.path.trimEnd('/')
            .removeSuffix("/index.html").removeSuffix("/server/load.php").removeSuffix("/portal.php").removeSuffix("/c")
        val origin = "${parsed.protocol}://${parsed.authority}"
        val base = origin + path
        return buildList {
            if (s.endsWith(".php")) add(s)
            if (path.isBlank()) add("$origin/stalker_portal/server/load.php")
            add("$base/server/load.php")
            add("$base/portal.php")
            add("$origin/stalker_portal/server/load.php")
            add("$origin/server/load.php")
            add("$origin/portal.php")
        }.distinct()
    }

    private fun stalkerHandshake(c: StalkerCredentials) {
        val candidates = if (workingStalkerUrl != null) listOf(workingStalkerUrl!!) + candidateLoadUrls(c) else candidateLoadUrls(c)
        var lastException: Exception? = null
        var profileException: Exception? = null

        for (candidate in candidates.distinct()) {
            val url = "$candidate?type=stb&action=handshake&JsHttpRequest=1-xml"
            stalkerCookies.cookieStore.removeAll()
            stalkerToken = null
            workingStalkerUrl = null
            try {
                val raw = stalkerFetch(url, c.cleanMac, null)
                val json = runCatching { (JSONTokener(raw).nextValue() as? JSONObject)?.optJSONObject("js") }.getOrNull()
                val token = json?.optString("token")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: error("Portal handshake did not return an authentication token")
                stalkerToken = token
                workingStalkerUrl = candidate
                // Establish the STB session before requesting its catalogs.
                val profile = stalkerApiCall(c, "stb", "get_profile", "stb_type" to "MAG250")
                val profileData = (profile as? JSONObject)?.optJSONObject("js")
                    ?: error("Portal rejected the STB profile; check MAC/device registration")
                check(profileData.length() > 0) { "Portal returned an empty STB profile" }
                return
            } catch (e: Exception) {
                if (e is ProviderRateLimitException) throw e
                lastException = e
                if (stalkerToken != null) profileException = e
            }
        }
        workingStalkerUrl = null
        stalkerToken = null
        throw profileException ?: lastException ?: error("Could not connect to Stalker Portal")
    }

    private fun stalkerLoadUrl(c: StalkerCredentials): String {
        return workingStalkerUrl ?: candidateLoadUrls(c).first()
    }

    private fun stalkerApiCall(c: StalkerCredentials, type: String, action: String, vararg extra: Pair<String, String>): Any? {
        val extraParam = extra.joinToString("") { "&${enc(it.first)}=${enc(it.second)}" }
        val url = "${stalkerLoadUrl(c)}?type=$type&action=$action$extraParam&JsHttpRequest=1-xml"
        val raw = stalkerFetch(url, c.cleanMac, stalkerToken)
        return try { JSONTokener(raw).nextValue().also {
            check(it is JSONObject || it is JSONArray) { "Invalid portal response" }
        } } catch (e: Exception) {
            error("Portal returned invalid JSON for $action (possible incorrect endpoint or access page)")
        }
    }

    private fun stalkerFetch(address: String, mac: String, token: String?): String {
        val conn = ProviderNetwork.open(address) { conn ->
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp main.js safari/533.3")
            conn.setRequestProperty("X-User-Agent", "Model: MAG250; Link: WiFi")
            conn.setRequestProperty("Accept", "application/json")
            val endpoint = conn.url
            val portalRoot = endpoint.path.substringBeforeLast('/').removeSuffix("/server")
            conn.setRequestProperty("Referer", "${endpoint.protocol}://${endpoint.authority}$portalRoot/c/")
            val sessionCookies = stalkerCookies.get(endpoint.toURI(), emptyMap())
                .filterKeys { it.equals("Cookie", ignoreCase = true) }.values.flatten().joinToString("; ")
            conn.setRequestProperty("Cookie", listOf(
                "mac=${enc(mac)}; stb_lang=en; stb_type=MAG250; timezone=Europe%2FKiev",
                sessionCookies
            ).filter { it.isNotBlank() }.joinToString("; "))
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            val endpoint = conn.url
            val status = conn.responseCode
            stalkerCookies.put(endpoint.toURI(), conn.headerFields.filterKeys { it != null })
            if (status == 429) {
                val retry = conn.getHeaderField("Retry-After")?.toLongOrNull()?.coerceAtLeast(1) ?: 60L
                rateLimitedSource = endpoint.authority
                rateLimitedUntil = System.currentTimeMillis() + retry.coerceAtMost(86400) * 1000
                throw ProviderRateLimitException(retry)
            }
            if (status !in 200..299) throw PortalHttpException(status)
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: java.net.UnknownHostException) {
            error("Portal DNS lookup failed; check hostname and network DNS")
        } catch (e: javax.net.ssl.SSLException) {
            error("Portal TLS connection failed; check certificate and device date/time")
        } catch (e: java.net.SocketTimeoutException) {
            error("Portal connection timed out; check server availability and network")
        } catch (e: java.net.ConnectException) {
            error("Portal connection refused or unreachable; check server port and network")
        } finally { conn.disconnect() }
    }

    private fun categories(c: XtreamCredentials, action: String): Map<String, String> = runCatching {
        val a = api(c, action) as? JSONArray ?: return@runCatching emptyMap()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associate { it.optString("category_id") to it.optString("category_name", "Other") }
    }.getOrDefault(emptyMap())

    private fun api(c: XtreamCredentials, action: String, extra: Pair<String, String>? = null): Any? {
        val url = "${root(c)}/player_api.php?username=${enc(c.username)}&password=${enc(c.password)}&action=$action" +
            (extra?.let { "&${it.first}=${enc(it.second)}" } ?: "")
        return JSONTokener(fetch(url)).nextValue()
    }

    private fun root(c: XtreamCredentials): String {
        val s = c.server.trim().trimEnd('/')
        require(s.startsWith("https://") || s.startsWith("http://")) { "Use an HTTP(S) server URL" }
        return s
    }
    private fun xtreamPlaybackUrl(c: XtreamCredentials, item: JSONObject, kind: String, id: String, extension: String): String {
        // Some providers publish a different playback host or signed URL in direct_source.
        val direct = item.optString("direct_source").trim()
        if (direct.startsWith("https://", true) || direct.startsWith("http://", true)) return direct
        val ext = extension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: if (kind == "live") "ts" else "mp4"
        fun segment(value: String) = enc(value).replace("+", "%20")
        return "${root(c)}/$kind/${segment(c.username)}/${segment(c.password)}/${segment(id)}.$ext"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fetch(address: String): String = fetchText(address).first

    private fun fetchText(address: String): Pair<String, String> {
        require(address.startsWith("https://") || address.startsWith("http://")) { "Use an HTTP(S) URL" }
        val conn = ProviderNetwork.open(address) { it.setRequestProperty("User-Agent", PlaybackRequests.APP_USER_AGENT) }
        return try {
            if (conn.responseCode !in 200..299) error("Provider returned HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() } to conn.url.toString()
        } finally { conn.disconnect() }
    }
}
