package com.stbplay.app

import com.stbplay.app.data.db.PlaylistEntity
import com.stbplay.app.data.db.MediaEntity
import androidx.room.withTransaction
import com.stbplay.app.data.FavoriteCodec
import com.stbplay.app.data.db.FavoriteEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stbplay.app.data.CatalogFolder
import com.stbplay.app.data.IptvRepository
import com.stbplay.app.data.Preferences
import com.stbplay.app.data.ProviderRateLimitException
import com.stbplay.app.data.StalkerCatalogCursor
import com.stbplay.app.model.MediaItem
import com.stbplay.app.model.MediaKind
import com.stbplay.app.model.StalkerCredentials
import com.stbplay.app.model.XtreamCredentials
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

enum class Section(val label: String) { EPISODES("Episodes"), HOME("Home"), LIVE("Live TV"), MOVIES("Movies"), SERIES("Series"), GUIDE("TV Guide"), SOURCES("Sources"), SETTINGS("Settings") }
enum class SourceProtocol { STALKER, XTREAM, M3U }

class MainViewModel(
    val repository: IptvRepository,
    val preferences: Preferences,
    val database: com.stbplay.app.data.db.AppDatabase
) : ViewModel() {

    // --- View State ---
    private var currentSection by mutableStateOf(Section.HOME)
    var section: Section
        get() = currentSection
        set(value) {
            if (value == Section.SOURCES && currentSection != value) expandedSource = null
            if (currentSection == Section.EPISODES && value != Section.EPISODES) {
                stalkerEpisodeBrowser = false
                episodeBackStack.clear()
                episodeBrowseCandidate = null
                episodeError = null
            }
            currentSection = value
        }
    var expandedSource by mutableStateOf<SourceProtocol?>(null)

    var playlistUrl by mutableStateOf(preferences.playlistUrl())
    var server by mutableStateOf(preferences.xtreamCredentials().server)
    var username by mutableStateOf(preferences.xtreamCredentials().username)
    var password by mutableStateOf(preferences.xtreamCredentials().password)
    var stalkerServer by mutableStateOf(preferences.stalkerCredentials().server)
    var stalkerMac by mutableStateOf(preferences.stalkerCredentials().mac)

    var items by mutableStateOf<List<MediaItem>>(emptyList())
    var vod by mutableStateOf<List<MediaItem>>(emptyList())
    var series by mutableStateOf<List<MediaItem>>(emptyList())
    var liveFolders by mutableStateOf<List<CatalogFolder>>(emptyList())
    var episodeList by mutableStateOf<List<MediaItem>>(emptyList())
    var episodeHeading by mutableStateOf("Select an episode")
    var episodeError by mutableStateOf<String?>(null)
    private var stalkerEpisodeBrowser = false
    private var episodeParentSection = Section.MOVIES
    private val episodeBackStack = mutableListOf<Pair<List<MediaItem>, String>>()
    private var episodeBrowseJob: Job? = null
    var episodeBrowseCandidate by mutableStateOf<MediaItem?>(null)
    
    var liveFolder by mutableStateOf<String?>(null)
    var movieFolders by mutableStateOf<List<CatalogFolder>>(emptyList())
    var seriesFolders by mutableStateOf<List<CatalogFolder>>(emptyList())
    var movieFolder by mutableStateOf<String?>(null)
    var seriesFolder by mutableStateOf<String?>(null)
    
    var movieLibraryTotal by mutableStateOf(0)
    var seriesLibraryTotal by mutableStateOf(0)
    var movieBrowseTotal by mutableStateOf(0)
    var seriesBrowseTotal by mutableStateOf(0)
    
    var movieError by mutableStateOf<String?>(null)
    var seriesError by mutableStateOf<String?>(null)
    
    var movieCursor by mutableStateOf<StalkerCatalogCursor?>(null)
    var seriesCursor by mutableStateOf<StalkerCatalogCursor?>(null)
    var movieSource by mutableStateOf<StalkerCredentials?>(null)
    var seriesSource by mutableStateOf<StalkerCredentials?>(null)
    
    var movieSearch by mutableStateOf("")
    var seriesSearch by mutableStateOf("")
    
    var connectedStalker by mutableStateOf<StalkerCredentials?>(null)
    var connectedXtream by mutableStateOf<XtreamCredentials?>(null)
    
    var selected by mutableStateOf<MediaItem?>(null)
    var resumeCandidate by mutableStateOf<MediaItem?>(null)
    var startPosition by mutableStateOf(0L)
    var needsProfileSave by mutableStateOf(false)
    var connectionStatus by mutableStateOf("NOT CONNECTED")
    var lastProtocol by mutableStateOf<SourceProtocol?>(null)
    var connectionFailed by mutableStateOf(false)
    var fieldErrors by mutableStateOf<Map<String, String>>(emptyMap())
    private var connectionJob: Job? = null
    private var connectionSequence = 0
    private var connectedDetails: PlaylistEntity? = null
    private var pendingFavoriteId: String? = null
    var loading by mutableStateOf(false)
    var message by mutableStateOf("Add an authorized playlist in Sources to get started.")
    var query by mutableStateOf("")
    var recents by mutableStateOf<List<MediaItem>>(emptyList())
    
    var sourceKey by mutableStateOf(preferences.currentSourceKey())
    var favoriteItems by mutableStateOf(preferences.favoriteItems(sourceKey))
    var legacyFavorites by mutableStateOf(preferences.favorites())

    val favorites: Set<String>
        get() = favoriteItems.map { it.id }.toSet() + legacyFavorites

    var playlists by mutableStateOf<List<PlaylistEntity>>(emptyList())
    var activePlaylist by mutableStateOf<PlaylistEntity?>(null)
    var editingPlaylist by mutableStateOf<PlaylistEntity?>(null)
        private set
    var epgUrl by mutableStateOf("")
    var guidePrograms by mutableStateOf<List<com.stbplay.app.data.db.EpgProgramEntity>>(emptyList())
    var guideNow by mutableStateOf(System.currentTimeMillis())
    private val legacySourceKey = preferences.currentSourceKey()

    init {
        database.playlistDao().getAllPlaylists().onEach { playlists = it }.launchIn(viewModelScope)
        viewModelScope.launch {
            database.playlistDao().getActivePlaylist().collectLatest { playlist ->
                if (playlist == null) {
                    activePlaylist = null
                    favoriteItems = emptyList(); guidePrograms = emptyList()
                    return@collectLatest
                }
                val changed = activePlaylist?.id != playlist.id
                activePlaylist = playlist
                epgUrl = playlist.epgUrl
                if (changed) {
                    vod = emptyList(); series = emptyList(); liveFolders = emptyList()
                    selected = null; recents = preferences.watchHistory(sourceKey); episodeList = emptyList()
                    liveFolder = null; movieFolder = null; seriesFolder = null; query = ""
                    movieCursor = null; seriesCursor = null; movieFolders = emptyList(); seriesFolders = emptyList()
                    movieError = null; seriesError = null; movieSearch = ""; seriesSearch = ""
                    playlistUrl = if (playlist.protocol == SourceProtocol.M3U) playlist.url else playlistUrl
                    server = playlist.url; username = playlist.usernameOrMac; password = playlist.password
                    stalkerServer = playlist.url; stalkerMac = playlist.usernameOrMac
                    connectedStalker = if (playlist.protocol == SourceProtocol.STALKER) stalkerCreds() else null
                    connectedXtream = if (playlist.protocol == SourceProtocol.XTREAM) xtreamCreds() else null
                    movieSource = connectedStalker; seriesSource = connectedStalker
                    sourceKey = Preferences.sourceKey(playlist.protocol.name, playlist.url, playlist.usernameOrMac)
                    recents = preferences.watchHistory(sourceKey)
                    connectionFailed = false
                    legacyFavorites = if (sourceKey == legacySourceKey) preferences.favorites() else emptySet()
                    favoriteItems = emptyList()
                    preferences.saveSource(playlist.protocol.name, stalkerCreds(), xtreamCreds(), playlistUrl)
                    val cached = database.mediaDao().getAll(playlist.id).first()
                    connectionStatus = if (cached.isEmpty()) "SAVED PROFILE" else "SAVED CATALOG"
                    if (preferences.option("favorites_migrated_$sourceKey") != "true") {
                        val old = (preferences.favoriteItems(sourceKey) + cached.filter { it.isFavorite }.map { it.toModel() }).distinctBy { it.id }
                        database.favoriteDao().save(old.map { FavoriteEntity(playlist.id, it.id, FavoriteCodec.encode(listOf(it))) })
                        preferences.saveOption("favorites_migrated_$sourceKey", "true")
                    }
                    message = if (cached.isEmpty()) "Saved source selected. Use Refresh to download its catalog." else "Loaded saved catalog."
                }
                coroutineScope {
                    launch {
                        database.mediaDao().getAll(playlist.id).collect { media ->
                            items = media.filter { it.kind == MediaKind.LIVE }.map { it.toModel() }
                            if (playlist.protocol != SourceProtocol.STALKER || changed && movieCursor == null && vod.isEmpty()) {
                                vod = media.filter { it.kind == MediaKind.MOVIE }.map { it.toModel() }
                                series = media.filter { it.kind == MediaKind.SERIES || it.kind == MediaKind.EPISODE }.map { it.toModel() }
                                movieLibraryTotal = vod.size; movieBrowseTotal = vod.size
                                seriesLibraryTotal = series.size; seriesBrowseTotal = series.size
                            }
                            syncFavorites()
                        }
                    }
                    launch {
                        database.favoriteDao().observe(playlist.id).collect { entries ->
                            favoriteItems = entries.flatMap { FavoriteCodec.decode(it.payload) }
                        }
                    }

                }
            }
        }
    }

    fun activatePlaylist(playlist: PlaylistEntity) {
        if (loading || activePlaylist?.id == playlist.id) return
        editingPlaylist = null
        vod = emptyList(); series = emptyList()
        viewModelScope.launch {
            database.withTransaction {
                database.playlistDao().clearActivePlaylist()
                database.playlistDao().setActivePlaylist(playlist.id)
            }
        }
    }

    fun editPlaylist(playlist: PlaylistEntity) {
        if (loading) return
        fieldErrors = emptyMap()
        editingPlaylist = playlist
        expandedSource = playlist.protocol
        when (playlist.protocol) {
            SourceProtocol.M3U -> playlistUrl = playlist.url
            SourceProtocol.XTREAM -> { server = playlist.url; username = playlist.usernameOrMac; password = playlist.password }
            SourceProtocol.STALKER -> { stalkerServer = playlist.url; stalkerMac = playlist.usernameOrMac }
        }
    }

    fun newProfile() {
        dismissFavoriteSave()
        fieldErrors = emptyMap()
        editingPlaylist = null
        playlistUrl = ""; server = ""; username = ""; password = ""
        stalkerServer = ""; stalkerMac = ""
    }

    private fun profileDetails(protocol: SourceProtocol, name: String): PlaylistEntity {
        val existing = editingPlaylist?.takeIf { it.protocol == protocol }
        return PlaylistEntity(
            id = existing?.id ?: 0, name = name, protocol = protocol,
            url = when (protocol) { SourceProtocol.STALKER -> stalkerCreds().cleanServer; SourceProtocol.XTREAM -> xtreamCreds().server; SourceProtocol.M3U -> playlistUrl.trim() },
            usernameOrMac = when (protocol) { SourceProtocol.STALKER -> stalkerCreds().cleanMac; SourceProtocol.XTREAM -> xtreamCreds().username; else -> "" },
            password = if (protocol == SourceProtocol.XTREAM) xtreamCreds().password else "",
            epgUrl = existing?.epgUrl ?: "", lastUpdated = System.currentTimeMillis()
        )
    }

    fun saveProfile(protocol: SourceProtocol, name: String) {
        if (loading || name.isBlank()) return
        val details = profileDetails(protocol, name.trim())
        if (!validateSource(protocol)) return
        loading = true
        viewModelScope.launch {
            try {
                val saved = database.withTransaction {
                    val all = database.playlistDao().getAllPlaylists().first()
                    val existing = all.find { it.name == details.name }
                    if (existing == null && all.size >= 4) {
                        null
                    } else {
                        val candidate = details.copy(id = existing?.id ?: 0, isActive = existing?.isActive ?: false, epgUrl = details.epgUrl.ifBlank { existing?.epgUrl ?: "" })
                        val savedId = if (existing == null) database.playlistDao().insert(candidate) else {
                            database.playlistDao().update(candidate)
                            existing.id
                        }
                        candidate.copy(id = savedId)
                    }
                } ?: run {
                    message = "You can save up to 4 playlists. Edit an existing playlist or delete one before saving a new playlist."
                    return@launch
                }
                val id = saved.id
                editingPlaylist = saved
                val connected = connectedDetails
                if (connected != null && connected.protocol == saved.protocol && connected.url == saved.url &&
                    connected.usernameOrMac == saved.usernameOrMac && connected.password == saved.password) {
                    val activated = saved.copy(id = id, isActive = true, epgUrl = saved.epgUrl.ifBlank { connected.epgUrl })
                    activePlaylist = activated
                    database.withTransaction {
                        database.playlistDao().clearActivePlaylist()
                        database.playlistDao().update(activated)
                    }
                    for (kind in MediaKind.entries) repository.cacheMedia(id, kind, (items + vod + series + episodeList).filter { it.kind == kind })
                    pendingFavoriteId?.let { favoriteId -> pendingFavoriteId = null; toggleFavorite(favoriteId) }
                }
                message = "Profile saved: ${saved.name}"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "Could not save profile: ${e.message}" }
            finally { loading = false }
        }
    }

    fun clearWatchHistory() { preferences.clearAllWatchHistory(); recents = emptyList() }

    fun deletePlaylist(playlist: PlaylistEntity) {
        if (loading) return
        val wasActive = activePlaylist?.id == playlist.id
        val wasEditing = editingPlaylist?.id == playlist.id
        viewModelScope.launch {
            database.playlistDao().delete(playlist)
            preferences.forgetSource(Preferences.sourceKey(playlist.protocol.name, playlist.url, playlist.usernameOrMac))
            if (editingPlaylist?.id == playlist.id) editingPlaylist = null
            if (wasActive) {
                connectedDetails = null
                connectionFailed = false
                connectionStatus = "NOT CONNECTED"
                preferences.clearWatchHistory(Preferences.sourceKey(playlist.protocol.name, playlist.url, playlist.usernameOrMac))
                items = emptyList(); liveFolders = emptyList(); vod = emptyList(); series = emptyList()
                selected = null; recents = emptyList(); episodeList = emptyList()
                connectedStalker = null; connectedXtream = null
                movieSource = null; seriesSource = null; movieCursor = null; seriesCursor = null
            }
            if (wasActive || wasEditing) { server = ""; username = ""; password = ""; stalkerServer = ""; stalkerMac = ""; playlistUrl = "" }
            message = "Deleted profile: ${playlist.name}"
        }
    }

    fun refreshPlaylist(playlist: PlaylistEntity) {
        if (loading) return
        editPlaylist(playlist)
        connectSource(playlist.protocol)
    }

    fun zap(direction: Int) {
        val current = selected ?: return
        if (current.kind != MediaKind.LIVE) return
        val channels = if (liveFolder == "__favorites__") favoriteItems.filter { it.kind == MediaKind.LIVE }
            else items.filter { liveFolder == null || it.group == liveFolder }
        if (channels.isEmpty()) return
        val index = channels.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        selected = channels[(index + direction + channels.size) % channels.size]
        recents = (listOf(selected!!) + recents.filterNot { it.id == selected!!.id }).take(12)
    }

    fun toggleFavorite(id: String) {
        val playlist = activePlaylist ?: run {
            pendingFavoriteId = id
            needsProfileSave = true
            message = "Save this source as a profile to keep your favorites."
            return
        }
        val existing = id in favorites
        val entry = (items + vod + series + episodeList + recents + favoriteItems).firstOrNull { it.id == id } ?: return
        favoriteItems = if (existing) favoriteItems.filterNot { it.id == id } else favoriteItems + entry
        legacyFavorites = legacyFavorites - id
        if (sourceKey == legacySourceKey) preferences.saveFavorites(legacyFavorites)
        viewModelScope.launch {
            if (existing) database.favoriteDao().remove(playlist.id, id)
            else database.favoriteDao().save(listOf(FavoriteEntity(playlist.id, id, FavoriteCodec.encode(listOf(entry)))))
        }
    }

    fun syncFavorites() {
        val playlist = activePlaylist ?: return
        val migrated = (items + vod + series + episodeList).filter { it.id in legacyFavorites }
        if (migrated.isEmpty()) return
        legacyFavorites = legacyFavorites - migrated.map { it.id }.toSet()
        val remaining = legacyFavorites
        val key = sourceKey
        viewModelScope.launch {
            database.favoriteDao().save(migrated.distinctBy { it.id }.map { FavoriteEntity(playlist.id, it.id, FavoriteCodec.encode(listOf(it))) })
            if (key == legacySourceKey) preferences.saveFavorites(remaining)
        }
    }

    fun xtreamCreds() = XtreamCredentials(server.trim(), username.trim(), password)
    fun stalkerCreds() = StalkerCredentials(stalkerServer.trim(), stalkerMac.trim())

    fun runLoad(block: suspend () -> List<MediaItem>, onSuccess: (List<MediaItem>) -> Unit) {
        loading = true
        message = "Connecting to your provider…"
        viewModelScope.launch {
            try {
                val result = block()
                onSuccess(result)
                message = "Loaded ${result.size} items."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = com.stbplay.app.data.UserErrors.connection(e)
                message = "Could not load source ($err). Check server, MAC/credentials, and connection."
            } finally {
                loading = false
            }
        }
    }

    fun loadStalkerCatalog(isSeries: Boolean, more: Boolean = false, search: String = "", category: String? = null) {
        if (loading) return
        val c = (if (isSeries) seriesSource else movieSource) ?: connectedStalker ?: return
        val cursor = if (more) { if (isSeries) seriesCursor else movieCursor } else null
        if (more && cursor == null) return
        
        loading = true
        if (isSeries) seriesError = null else movieError = null
        if (!more) {
            if (isSeries) {
                series = emptyList(); seriesCursor = null; seriesFolder = category; seriesBrowseTotal = 0
            } else {
                vod = emptyList(); movieCursor = null; movieFolder = category; movieBrowseTotal = 0
            }
        }
        
        viewModelScope.launch {
            try {
                val folders = repository.stalkerFolders(c, isSeries)
                val page = repository.stalkerVod(c, isSeries, cursor, search, category)
                activePlaylist?.id?.let { repository.cacheMedia(it, if (isSeries) MediaKind.SERIES else MediaKind.MOVIE, page.items, replace = false) }
                if (isSeries) {
                    seriesFolders = folders
                    seriesSource = c
                    series = ((if (more) series else emptyList()) + page.items).distinctBy { it.id }
                    seriesCursor = page.next
                    seriesBrowseTotal = page.total
                    if (!more && category == null && search.isBlank()) seriesLibraryTotal = page.total
                } else {
                    movieFolders = folders
                    movieSource = c
                    vod = ((if (more) vod else emptyList()) + page.items).distinctBy { it.id }
                    movieCursor = page.next
                    movieBrowseTotal = page.total
                    if (!more && category == null && search.isBlank()) movieLibraryTotal = page.total
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isSeries) seriesError = e.message ?: "Could not load this folder"
                else movieError = e.message ?: "Could not load this folder"
            } finally {
                loading = false
            }
        }
    }

    fun dismissFavoriteSave() { needsProfileSave = false; pendingFavoriteId = null }

    fun browseStalkerVod(item: MediaItem) {
        val credentials = connectedStalker ?: run { message = "Connect your Stalker source first."; return }
        if (episodeBrowseJob?.isActive == true) return
        val previousSource = sourceKey
        val origin = section
        episodeBrowseCandidate = item
        episodeError = null
        loading = true
        episodeBrowseJob = viewModelScope.launch {
            try {
                val children = repository.stalkerVodChildren(credentials, item)
                if (sourceKey != previousSource || section != origin) return@launch
                if (origin == Section.EPISODES) episodeBackStack.add(episodeList to episodeHeading)
                else { episodeBackStack.clear(); episodeParentSection = origin }
                stalkerEpisodeBrowser = true
                episodeList = children
                episodeHeading = item.name
                section = Section.EPISODES
                episodeError = if (children.isEmpty()) "The portal returned no seasons, episodes or files for this title." else null
                episodeBrowseCandidate = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (sourceKey == previousSource && section == origin) {
                    episodeError = com.stbplay.app.data.UserErrors.connection(e)
                }
            } finally { if (sourceKey == previousSource && connectionJob?.isActive != true) loading = false }
        }
    }

    fun cancelStalkerBrowse() {
        episodeBrowseJob?.cancel()
        episodeBrowseCandidate = null
        episodeError = null
        if (connectionJob?.isActive != true) loading = false
    }

    fun backFromStalkerEpisodes(): Boolean {
        episodeBrowseJob?.cancel()
        episodeError = null
        episodeBrowseCandidate = null
        if (episodeBackStack.isNotEmpty()) {
            val (previous, heading) = episodeBackStack.removeAt(episodeBackStack.lastIndex)
            episodeList = previous; episodeHeading = heading
            return true
        }
        if (stalkerEpisodeBrowser) {
            stalkerEpisodeBrowser = false
            section = episodeParentSection
            return true
        }
        return false
    }

    fun requestPlayback(item: MediaItem) {
        if (com.stbplay.app.data.StalkerVodRoute.decode(item.cmd)?.browsable == true) {
            browseStalkerVod(item)
            return
        }
        if (preferences.resumePosition(sourceKey, item) > 0) resumeCandidate = item
        else beginPlayback(item, false)
    }

    fun beginPlayback(item: MediaItem, resume: Boolean) {
        startPosition = if (resume) preferences.resumePosition(sourceKey, item) else 0
        resumeCandidate = null
        selected = item
    }

    fun cancelConnection() {
        connectionSequence++
        connectionJob?.cancel()
        connectionJob = null
        loading = false
        connectionStatus = if (items.isNotEmpty() || vod.isNotEmpty() || series.isNotEmpty()) "PARTIAL CATALOG" else "NOT CONNECTED"
        connectionFailed = false
        message = "Connection cancelled. You can retry when ready."
    }

    fun validateSource(protocol: SourceProtocol): Boolean {
        fieldErrors = com.stbplay.app.data.SourceValidation.errors(protocol, stalkerServer, stalkerMac, server, username, password, playlistUrl)
        if (fieldErrors.isNotEmpty()) message = "Check the highlighted source fields."
        return fieldErrors.isEmpty()
    }

    fun connectSource(protocol: SourceProtocol) {
        val stalker = stalkerCreds()
        val xtream = xtreamCreds()
        val playlist = playlistUrl.trim()
        if (!validateSource(protocol)) return
        if (loading) return
        val editedProfile = editingPlaylist?.takeIf { it.protocol == protocol }
        val connectionDetails = profileDetails(protocol, editedProfile?.name ?: "")
        pendingFavoriteId = null
        val attemptSequence = ++connectionSequence
        connectedDetails = null
        lastProtocol = protocol
        connectionFailed = false
        connectionStatus = "CONNECTING"
        loading = true
        message = "Connecting to your provider…"
        
        connectionJob = viewModelScope.launch {
            val warnings = mutableListOf<String>()
            var loadedAny = false
            var rateLimited = false
            suspend fun attempt(label: String, action: suspend () -> Unit) {
                if (rateLimited) return
                message = "Connecting: $label…"
                try {
                    action()
                    loadedAny = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val detail = com.stbplay.app.data.UserErrors.connection(e)
                    warnings += "$label: $detail"
                    if (label == "Movies") movieError = detail
                    if (label == "Series") seriesError = detail
                    if (e is ProviderRateLimitException) {
                        rateLimited = true
                        movieError = detail
                        seriesError = detail
                    }
                }
            }
            try {
                episodeList = emptyList()
                movieCursor = null; seriesCursor = null; movieSource = null; seriesSource = null
                movieSearch = ""; seriesSearch = ""; liveFolder = null; movieFolder = null; seriesFolder = null; movieError = null; seriesError = null
                movieFolders = emptyList(); seriesFolders = emptyList()
                movieLibraryTotal = 0; seriesLibraryTotal = 0; movieBrowseTotal = 0; seriesBrowseTotal = 0
                recents = emptyList()
                
                sourceKey = Preferences.sourceKey(
                    protocol.name,
                    when (protocol) {
                        SourceProtocol.STALKER -> stalker.cleanServer
                        SourceProtocol.XTREAM -> xtream.server
                        SourceProtocol.M3U -> playlist
                    },
                    when (protocol) {
                        SourceProtocol.STALKER -> stalker.cleanMac
                        SourceProtocol.XTREAM -> xtream.username
                        else -> ""
                    }
                )
                recents = preferences.watchHistory(sourceKey)
                preferences.saveSource(protocol.name, stalker, xtream, playlist)
                connectedStalker = if (protocol == SourceProtocol.STALKER) stalker else null
                connectedXtream = if (protocol == SourceProtocol.XTREAM) xtream else null
                
                val existing = editedProfile?.let { database.playlistDao().getPlaylistById(it.id) }
                val guide = existing?.epgUrl?.takeIf { it.isNotBlank() } ?: if (protocol == SourceProtocol.XTREAM)
                    "${xtream.server.trimEnd('/')}/xmltv.php?username=${java.net.URLEncoder.encode(xtream.username, "UTF-8")}&password=${java.net.URLEncoder.encode(xtream.password, "UTF-8")}" else ""
                var connectedGuide = guide
                val activeId: Long? = database.withTransaction {
                    database.playlistDao().clearActivePlaylist()
                    existing?.let {
                        val updated = connectionDetails.copy(id = it.id, isActive = true, epgUrl = guide)
                        database.playlistDao().update(updated)
                        editingPlaylist = updated
                        // Avoid replacing the live connection with the cached catalog when the observer fires.
                        activePlaylist = updated
                        it.id
                    }
                }
                selected = null
                items = emptyList(); liveFolders = emptyList(); vod = emptyList(); series = emptyList()
                favoriteItems = emptyList(); legacyFavorites = emptySet(); guidePrograms = emptyList()

                when (protocol) {
                    SourceProtocol.STALKER -> {
                        attempt("Live TV") { 
                            val loaded = repository.stalkerLive(stalker)
                            liveFolders = repository.liveFolderMetadata
                            activeId?.let { repository.cacheMedia(it, MediaKind.LIVE, loaded) }
                            items = loaded
                        }
                        attempt("Movies") {
                            movieSource = stalker
                            movieFolders = repository.stalkerFolders(stalker)
                            val page = repository.stalkerVod(stalker)
                            activeId?.let { repository.cacheMedia(it, MediaKind.MOVIE, page.items, replace = false) }
                            vod = page.items
                            movieCursor = page.next
                            movieLibraryTotal = page.total
                            movieBrowseTotal = page.total
                        }
                        attempt("Series") {
                            val folders = repository.stalkerFolders(stalker, true)
                            val page = repository.stalkerVod(stalker, true)
                            activeId?.let { repository.cacheMedia(it, MediaKind.SERIES, page.items, replace = false) }
                            series = page.items
                            seriesCursor = page.next
                            seriesSource = stalker
                            seriesFolders = folders
                            seriesLibraryTotal = page.total
                            seriesBrowseTotal = page.total
                        }
                    }
                    SourceProtocol.XTREAM -> {
                        attempt("Live TV") { 
                            val loaded = repository.xtreamLive(xtream)
                            activeId?.let { repository.cacheMedia(it, MediaKind.LIVE, loaded) }
                            items = loaded
                        }
                        attempt("Movies") {
                            val loaded = repository.xtreamVod(xtream)
                            activeId?.let { repository.cacheMedia(it, MediaKind.MOVIE, loaded) }
                            vod = loaded
                            movieLibraryTotal = vod.size
                            movieBrowseTotal = vod.size
                        }
                        attempt("Series") {
                            val loaded = repository.xtreamVod(xtream, true)
                            activeId?.let { repository.cacheMedia(it, MediaKind.SERIES, loaded) }
                            series = loaded
                            seriesLibraryTotal = series.size
                            seriesBrowseTotal = series.size
                        }
                    }
                    SourceProtocol.M3U -> attempt("Playlist") { 
                        val loaded = repository.loadM3u(playlist) 
                        for (kind in MediaKind.entries) {
                            activeId?.let { repository.cacheMedia(it, kind, loaded.items.filter { it.kind == kind }) }
                        }
                        val discoveredGuide = loaded.epgUrl?.let { runCatching { java.net.URI(playlist).resolve(it).toString() }.getOrDefault(it) }
                        if (!discoveredGuide.isNullOrBlank() && connectedGuide.isBlank()) connectedGuide = discoveredGuide
                        if (activeId != null && !discoveredGuide.isNullOrBlank() && guide.isBlank()) {
                            database.playlistDao().getPlaylistById(activeId)?.let { database.playlistDao().update(it.copy(epgUrl = discoveredGuide)) }
                        }
                        items = loaded.items.filter { it.kind == MediaKind.LIVE }
                        vod = loaded.items.filter { it.kind == MediaKind.MOVIE }
                        series = loaded.items.filter { it.kind == MediaKind.SERIES || it.kind == MediaKind.EPISODE }

                    }
                }
                if (loadedAny) {
                    connectedDetails = connectionDetails.copy(epgUrl = connectedGuide)
                    connectionStatus = if (warnings.isEmpty()) "CONNECTED" else "PARTIALLY CONNECTED"
                    section = Section.HOME
                    message = "Connected: ${items.size} channels; movie catalog ${movieLibraryTotal.takeIf { it > 0 } ?: vod.size} titles (${vod.size} loaded). Open Movies to browse folders and load more automatically."
                    if (warnings.isNotEmpty()) message += " Unavailable — " + warnings.joinToString("; ")
                } else {
                    connectionStatus = "CONNECTION FAILED"
                    connectionFailed = true
                    message = "Could not connect. " + warnings.joinToString("; ")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                connectionStatus = "CONNECTION FAILED"
                connectionFailed = true
                message = com.stbplay.app.data.UserErrors.connection(e)
            } finally {
                if (attemptSequence == connectionSequence) loading = false
            }
        }
    }
}
