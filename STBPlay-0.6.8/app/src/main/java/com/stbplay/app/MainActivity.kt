package com.stbplay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.stbplay.app.data.db.PlaylistEntity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.stbplay.app.data.ProviderRateLimitException
import com.stbplay.app.data.CatalogFolder
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import kotlinx.coroutines.delay
import com.stbplay.app.data.StalkerCatalogCursor
import com.stbplay.app.data.IptvRepository
import com.stbplay.app.model.MediaItem
import com.stbplay.app.model.MediaKind
import com.stbplay.app.model.StalkerCredentials
import com.stbplay.app.model.XtreamCredentials
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.stbplay.app.data.Preferences

internal val Ink = Color(0xFF080D18)
internal val Panel = Color(0xFF111A2B)
internal val Panel2 = Color(0xFF18243A)
internal val AccentBlue = Color(0xFF159DFF)
private val LocalPlaybackFocusBookmark = staticCompositionLocalOf<(FocusRequester) -> Unit> { {} }
private val LogoBlue = Color(0xFF159DFF)
internal val Muted = Color(0xFF9BAAC0)

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {
    internal val accessSession: AppAccessSession by viewModels()
    internal var stopProtectedPlayback: (() -> Unit)? = null
    internal var prepareForAppLock: (() -> Unit)? = null
    internal var authorizedDocumentPicker = false
    override fun onStart() {
        super.onStart()
        accessSession.refreshEnabled()
    }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !authorizedDocumentPicker) {
            if (accessSession.enabled) {
                stopProtectedPlayback?.invoke()
                prepareForAppLock?.invoke()
            }
            accessSession.lock()
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (accessSession.enabled && intent.action == android.content.Intent.ACTION_MAIN) {
            authorizedDocumentPicker = false
            stopProtectedPlayback?.invoke()
            prepareForAppLock?.invoke()
            accessSession.lock()
        }
    }

    var inPip by mutableStateOf(false)
        private set
    // TV release deliberately has no PiP entry or automatic PiP transition.
    fun updatePipPlayback(playing: Boolean) = Unit
    override fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean, config: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, config)
        inPip = inPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 13, 24)
        window.navigationBarColor = android.graphics.Color.rgb(8, 13, 24)
        setContent { AppAccessGate(accessSession) { StorageReadyGate { StbPlayApp() } } }
    }
}



@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun StbPlayApp(providedViewModel: MainViewModel? = null) {
    val context = LocalContext.current
    val database = remember { com.stbplay.app.data.db.AppDatabase.get(context) }
    val repository = remember { IptvRepository(database) }
    val preferences = remember { Preferences(context) }
    
    val viewModel: MainViewModel = providedViewModel ?: androidx.lifecycle.viewmodel.compose.viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(repository, preferences, database) as T
            }
        }
    )

    val host = context as? MainActivity
    DisposableEffect(viewModel) {
        host?.prepareForAppLock = {
            viewModel.selected?.let { viewModel.startPosition = preferences.resumePosition(viewModel.sourceKey, it) }
        }
        onDispose { host?.prepareForAppLock = null }
    }

    var saveProtocol by remember { mutableStateOf<SourceProtocol?>(null) }
    var profileName by remember { mutableStateOf("") }
    fun requestSave(protocol: SourceProtocol) {
        if (!viewModel.validateSource(protocol)) return
        profileName = viewModel.editingPlaylist?.takeIf { it.protocol == protocol }?.name ?: ""
        saveProtocol = protocol
    }
    var deleteCandidate by remember { mutableStateOf<PlaylistEntity?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    val dialogs: @Composable () -> Unit = {
    saveProtocol?.let { protocol ->
        AlertDialog(
            onDismissRequest = { saveProtocol = null; viewModel.dismissFavoriteSave() },
            title = { Text("Save profile") },
            text = { SourceTextField(value = profileName, onValueChange = { profileName = it }, label = { Text("Profile name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { viewModel.saveProfile(protocol, profileName); saveProtocol = null }, enabled = profileName.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { saveProtocol = null; viewModel.dismissFavoriteSave() }) { Text("Cancel") } }
        )
    }
    deleteCandidate?.let { profile ->
        ProfileDeleteDialog(profile,
            onDelete = { viewModel.deletePlaylist(profile); deleteCandidate = null },
            onCancel = { deleteCandidate = null })
    }
    if (viewModel.needsProfileSave) AlertDialog(
        onDismissRequest = { viewModel.dismissFavoriteSave() },
        title = { Text("Save your source first") },
        text = { Text("Favorites are stored with a saved profile. Give this source a name to keep your favorites.") },
        confirmButton = { TextButton(onClick = {
            viewModel.needsProfileSave = false
            viewModel.selected = null
            viewModel.section = Section.SOURCES
            viewModel.lastProtocol?.let { viewModel.expandedSource = it; requestSave(it) }
        }) { Text("Save profile") } },
        dismissButton = { TextButton(onClick = { viewModel.dismissFavoriteSave() }) { Text("Later") } }
    )
    if (viewModel.loading && viewModel.episodeBrowseCandidate != null) AlertDialog(
        onDismissRequest = { viewModel.cancelStalkerBrowse() },
        title = { Text("Loading episodes…") },
        text = { CircularProgressIndicator() },
        confirmButton = { TextButton(onClick = { viewModel.cancelStalkerBrowse() }) { Text("Cancel") } })
    viewModel.episodeError?.takeIf { viewModel.episodeBrowseCandidate != null }?.let { problem ->
        AlertDialog(onDismissRequest = { viewModel.episodeError = null },
            title = { Text("Could not load episodes") }, text = { Text(problem) },
            confirmButton = { TextButton(onClick = { viewModel.episodeBrowseCandidate?.let { viewModel.browseStalkerVod(it) } }) { Text("Retry") } },
            dismissButton = { TextButton(onClick = { viewModel.episodeError = null; viewModel.episodeBrowseCandidate = null }) { Text("Close") } })
    }
    viewModel.resumeCandidate?.let { item ->
        AlertDialog(
            onDismissRequest = { viewModel.resumeCandidate = null },
            title = { Text("Continue ${item.name}?") },
            text = { Text("Resume where you left off, or watch from the beginning.") },
            confirmButton = { TextButton(onClick = { viewModel.beginPlayback(item, true) }) { Text("Resume") } },
            dismissButton = { TextButton(onClick = { viewModel.beginPlayback(item, false) }) { Text("Start over") } }
        )
    }
    }

    var section by viewModel::section
    var expandedSource by viewModel::expandedSource
    var playlistUrl by viewModel::playlistUrl
    var server by viewModel::server
    var username by viewModel::username
    var password by viewModel::password
    var stalkerServer by viewModel::stalkerServer
    var stalkerMac by viewModel::stalkerMac

    var items by viewModel::items
    var vod by viewModel::vod
    var series by viewModel::series
    var liveFolder by viewModel::liveFolder
    var movieFolders by viewModel::movieFolders
    var seriesFolders by viewModel::seriesFolders
    var movieFolder by viewModel::movieFolder
    var seriesFolder by viewModel::seriesFolder
    var movieLibraryTotal by viewModel::movieLibraryTotal
    var seriesLibraryTotal by viewModel::seriesLibraryTotal
    var movieBrowseTotal by viewModel::movieBrowseTotal
    var seriesBrowseTotal by viewModel::seriesBrowseTotal
    var movieError by viewModel::movieError
    var seriesError by viewModel::seriesError
    var movieCursor by viewModel::movieCursor
    var seriesCursor by viewModel::seriesCursor
    var movieSource by viewModel::movieSource
    var seriesSource by viewModel::seriesSource
    var movieSearch by viewModel::movieSearch
    var seriesSearch by viewModel::seriesSearch
    var connectedStalker by viewModel::connectedStalker
    var connectedXtream by viewModel::connectedXtream
    var episodeList by viewModel::episodeList
    var selected by viewModel::selected
    var loading by viewModel::loading
    var message by viewModel::message
    var query by viewModel::query
    var recents by viewModel::recents
    var sourceKey by viewModel::sourceKey
    var favoriteItems by viewModel::favoriteItems
    var legacyFavorites by viewModel::legacyFavorites
    val favorites = viewModel.favorites

    LaunchedEffect(sourceKey, items, vod, series, episodeList) {
        viewModel.syncFavorites()
    }

    LaunchedEffect(section, viewModel.activePlaylist?.id) {
        if (connectedStalker != null && !loading) {
            if (section == Section.MOVIES && movieCursor == null) viewModel.loadStalkerCatalog(false)
            if (section == Section.SERIES && seriesCursor == null) viewModel.loadStalkerCatalog(true)
        }
    }

    fun toggleFavorite(id: String) = viewModel.toggleFavorite(id)
    fun runLoad(block: suspend () -> List<MediaItem>, onSuccess: (List<MediaItem>) -> Unit) = viewModel.runLoad(block, onSuccess)
    fun loadStalkerCatalog(isSeries: Boolean, more: Boolean = false, search: String = "", category: String? = null) = viewModel.loadStalkerCatalog(isSeries, more, search, category)
    fun connectSource(protocol: SourceProtocol) = viewModel.connectSource(protocol)
    fun stalkerCreds() = viewModel.stalkerCreds()
    fun xtreamCreds() = viewModel.xtreamCreds()

    val sectionHistory = remember { mutableStateListOf(Section.HOME) }
    val browserFocus = remember { FocusRequester() }
    var playbackFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var wasPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(section) {
        if (section != Section.EPISODES && sectionHistory.lastOrNull() == Section.EPISODES) sectionHistory.removeAt(sectionHistory.lastIndex)
        if (section == Section.HOME) { sectionHistory.clear(); sectionHistory.add(Section.HOME) }
        else if (sectionHistory.lastOrNull() != section) sectionHistory.add(section)
    }
    LaunchedEffect(selected, sourceKey) {
        if (selected == null) {
            recents = preferences.watchHistory(sourceKey)
            if (wasPlaying) {
                val restored = playbackFocus?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
                if (!restored && !browserFocus.restoreFocusedChild()) browserFocus.requestFocus()
                wasPlaying = false
            }
        } else {
            browserFocus.saveFocusedChild()
            wasPlaying = true
        }
    }
    BackHandler(enabled = selected != null || section != Section.HOME) {
        if (selected != null) selected = null
        else if (section == Section.EPISODES && viewModel.backFromStalkerEpisodes()) { /* parent restored */ }
        else {
            if (sectionHistory.lastOrNull() == section) sectionHistory.removeAt(sectionHistory.lastIndex)
            section = sectionHistory.lastOrNull() ?: Section.HOME
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = AccentBlue, onPrimary = Color.White, background = Ink, onBackground = Color.White,
        surface = Panel, onSurface = Color.White, secondary = AccentBlue, surfaceVariant = Panel2
    )) {
        CompositionLocalProvider(LocalContentColor provides Color.White, LocalPlaybackFocusBookmark provides { playbackFocus = it }) {
        Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()
            .graphicsLayer { alpha = if (selected == null) 1f else 0f }
            .then(if (selected != null) Modifier.clearAndSetSemantics {} else Modifier)
            .focusProperties { enter = { if (selected != null) FocusRequester.Cancel else FocusRequester.Default } }
            .focusRequester(browserFocus).focusRestorer().focusGroup()
            .background(Brush.verticalGradient(listOf(Color(0xFF101B2D), Ink)))) {
            key(sourceKey) {
                Column(Modifier.width(210.dp).fillMaxHeight().background(Color(0xE609101D)).padding(horizontal = 18.dp, vertical = 26.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(painterResource(R.drawable.stbplay_logo), contentDescription = "STBPlay logo",
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                        Column {
                            Text(buildAnnotatedString {
                                withStyle(SpanStyle(color = Color.White)) { append("STB") }
                                withStyle(SpanStyle(color = LogoBlue)) { append("Play") }
                            }, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                            Text("YOUR TV, YOUR WAY", fontSize = 9.sp, color = Muted, letterSpacing = 1.sp)
                        }
                    }
                    Spacer(Modifier.height(34.dp))
                    Section.entries.filter { it != Section.EPISODES }.forEach { nav ->
                        if (nav == Section.GUIDE) {
                            // Preserve the old guide slot without a visible/focusable tab.
                            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).padding(horizontal = 14.dp, vertical = 13.dp)) {
                                Text(" ", fontSize = 17.sp)
                            }
                            return@forEach
                        }
                        val active = nav == section
                        var focused by remember { mutableStateOf(false) }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .background(if (active) Panel2 else if (focused) Panel else Color.Transparent, RoundedCornerShape(12.dp))
                                .then(if (active || focused) Modifier.border(1.dp, AccentBlue.copy(alpha = .85f), RoundedCornerShape(12.dp)) else Modifier)
                                .onFocusChanged { focused = it.isFocused }
                                .clickable { section = nav }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(navIcon(nav), fontSize = 17.sp, color = if (active) AccentBlue else Muted)
                            Spacer(Modifier.width(12.dp))
                            Text(nav.label, color = if (active) Color.White else Muted, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("STBPLAY", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                }

                if (section == Section.LIVE) {
                    Column(Modifier.weight(1f).fillMaxHeight().padding(start = 30.dp, end = 34.dp, top = 26.dp, bottom = 30.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(section.label.uppercase(), color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                                Text("Live channels from your playlist", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            }
                            if (section == Section.SOURCES) ConnectionIndicator(viewModel)
                        }
                        Spacer(Modifier.height(24.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            SourceTextField(value = query, onValueChange = { query = it }, label = { Text("Search channels") }, modifier = Modifier.weight(1f), singleLine = true)
                            Text("${items.size} CHANNELS", color = Muted, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                        if (items.isEmpty() && favoriteItems.none { it.kind == MediaKind.LIVE }) {
                            EmptyPanel("No channels loaded", "Connect your source first.")
                        } else {
                            val folders = remember(items, viewModel.liveFolders) {
                                val counts = items.groupingBy { it.group }.eachCount()
                                (viewModel.liveFolders.map { it.title } + counts.keys).distinct().map { CatalogFolder(it, it, counts[it] ?: 0) }.sortedBy { it.title.lowercase() }
                            }
                            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                FolderPane(folders, liveFolder, items.size, favoriteCount = favoriteItems.count { it.kind == MediaKind.LIVE }, enabled = !loading, onSelect = { liveFolder = it })
                                Column(Modifier.weight(1f)) {
                                    Text(if (liveFolder == FAVORITES_FOLDER) "★ Favorites" else liveFolder ?: "All channels", color = AccentBlue, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(8.dp))
                                    val filtered = (if (liveFolder == FAVORITES_FOLDER) favoriteItems.filter { it.kind == MediaKind.LIVE } else items).filter { (liveFolder == null || liveFolder == FAVORITES_FOLDER || it.group == liveFolder) && (it.name.contains(query, true) || it.group.contains(query, true)) }
                                    Text("${filtered.size} channels", color = Muted, fontSize = 11.sp)
                                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                        items(filtered, key = { it.id }) { channel ->
                                            ChannelRow(channel, channel.id in favorites, program = viewModel.guidePrograms.firstOrNull { it.channelId == channel.epgId && it.startMillis <= viewModel.guideNow && it.endMillis > viewModel.guideNow }, now = viewModel.guideNow, onClick = { selected = channel; recents = (listOf(channel) + recents.filterNot { it.id == channel.id }).take(12) }, onFavorite = { toggleFavorite(channel.id) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (section == Section.MOVIES || section == Section.SERIES) {
                    val isSeries = section == Section.SERIES
                    val media = if (isSeries) series else vod
                    val portal = (if (isSeries) seriesSource else movieSource) != null
                    val favoriteView = (if (isSeries) seriesFolder else movieFolder) == FAVORITES_FOLDER
                    val remote = portal && !favoriteView
                    val sectionFavorites = favoriteItems.filter { if (isSeries) it.kind == MediaKind.SERIES || it.kind == MediaKind.EPISODE else it.kind == MediaKind.MOVIE }
                    val folders = if (portal) { if (isSeries) seriesFolders else movieFolders }
                        else media.groupingBy { it.group }.eachCount().map { CatalogFolder(it.key, it.key, it.value) }
                    val selectedFolder = if (isSeries) seriesFolder else movieFolder
                    val cursor = if (isSeries) seriesCursor else movieCursor
                    CatalogBrowser(
                        title = if (isSeries) "Series" else "Movies", folders = folders, selectedFolder = selectedFolder,
                        media = if (favoriteView) sectionFavorites else media, favoriteCount = sectionFavorites.size, libraryTotal = if (isSeries) seriesLibraryTotal else movieLibraryTotal,
                        browseTotal = if (isSeries) seriesBrowseTotal else movieBrowseTotal,
                        remote = remote, loading = loading, cursor = cursor, pageError = if (favoriteView) null else if (isSeries) seriesError else movieError, favorites = favorites,
                        searchValue = if (isSeries) seriesSearch else movieSearch, onSearchInput = { if (isSeries) seriesSearch = it else movieSearch = it },
                        onFolder = { folder ->
                            if (isSeries) seriesSearch = "" else movieSearch = ""
                            if (portal && folder != FAVORITES_FOLDER) loadStalkerCatalog(isSeries, category = folder)
                            else if (isSeries) seriesFolder = folder else movieFolder = folder
                        },
                        onSearch = { search -> if (remote) loadStalkerCatalog(isSeries, search = search, category = selectedFolder) },
                        onMore = { loadStalkerCatalog(isSeries, more = true) },
                        onRetry = { loadStalkerCatalog(isSeries, more = media.isNotEmpty() && cursor != null, search = if (isSeries) seriesSearch else movieSearch, category = selectedFolder) },
                        onSelect = { item ->
                            if (!loading) {
                                if (!isSeries || item.kind == MediaKind.EPISODE) { viewModel.requestPlayback(item) }
                                else if (!item.id.startsWith("stalker-") && connectedXtream != null) {
                                    runLoad({ repository.seriesEpisodes(connectedXtream!!, item.id.removePrefix("series-")) }) { episodeList = it; section = Section.EPISODES }
                                } else if (item.id.startsWith("stalker-")) viewModel.requestPlayback(item)
                                else message = "Connect your source to browse this series."
                            }
                        },
                        onFavorite = { id -> toggleFavorite(id) }
                    )
                } else {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 30.dp, end = 34.dp, top = 26.dp, bottom = 30.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(section.label.uppercase(), color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                                Text(when (section) {
                                    Section.EPISODES -> viewModel.episodeHeading
                                    Section.HOME -> "Everything you love. One place."
                                    Section.LIVE -> "Live channels from your playlist"
                                    Section.MOVIES -> "Your on-demand collection"
                                    Section.SERIES -> "Series and episodes"
                                    Section.GUIDE -> ""
                                    Section.SOURCES -> "Connect your IPTV provider"
                                    Section.SETTINGS -> "Make STBPlay yours"
                                }, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            }
                            if (section == Section.SOURCES) ConnectionIndicator(viewModel)
                        }
                        Spacer(Modifier.height(24.dp))
                        when (section) {
                            Section.HOME -> {
                                Box(Modifier.fillMaxWidth().height(270.dp).background(Brush.linearGradient(listOf(Color(0xFF1E3A53), Color(0xFF17243C), Color(0xFF26334B))), RoundedCornerShape(24.dp)).padding(26.dp)) {
                                    Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(.68f)) {
                                        Text("WELCOME TO STBPLAY", color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                                        Spacer(Modifier.height(8.dp))
                                        Text("A better way to\nwatch television.", fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.ExtraBold)
                                        Spacer(Modifier.height(10.dp))
                                        Text("Bring your live channels, movies and series together.", color = Muted, fontSize = 13.sp)
                                        Spacer(Modifier.height(14.dp))
                                        Button(onClick = { section = Section.SOURCES }) { Text("Add a source  →") }
                                    }
                                    Image(painterResource(R.drawable.stbplay_logo), contentDescription = "STBPlay", modifier = Modifier.align(Alignment.CenterEnd).size(128.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Fit)
                                }
                                Spacer(Modifier.height(25.dp))
                                Text("CONTINUE WATCHING", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                Spacer(Modifier.height(12.dp))
                                if (recents.isEmpty()) EmptyPanel("Nothing here yet", "Play a channel or movie and it will appear here.")
                                else MediaRow(recents, favorites, onSelect = { viewModel.requestPlayback(it) }, onFavorite = { id -> toggleFavorite(id) })
                                Spacer(Modifier.height(20.dp))
                                Text("QUICK ACCESS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    QuickCard("Live TV", "${items.size} channels", "▶") { section = Section.LIVE }
                                    QuickCard("Movies", "${movieLibraryTotal.takeIf { it > 0 } ?: vod.size} catalog titles", "▣") { section = Section.MOVIES }
                                    QuickCard("Series", "${seriesLibraryTotal.takeIf { it > 0 } ?: series.size} shows", "▤") { section = Section.SERIES }
                                }
                            }
                            Section.SOURCES -> {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("Saved playlists (${viewModel.playlists.size}/4)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Panel2) {
                                        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Text("Connection type", Modifier.width(132.dp), color = Muted, fontSize = 12.sp, maxLines = 1)
                                        Text("Connection details", Modifier.weight(1f), color = Muted, fontSize = 12.sp, maxLines = 1)
                                        Text("Actions", Modifier.width(350.dp), color = Muted, fontSize = 12.sp)
                                    }
                                    HorizontalDivider(color = Muted.copy(alpha = .2f))
                                    if (viewModel.playlists.isEmpty()) {
                                        Text("No saved playlists. Use Save in a source below to add one.", color = Muted, modifier = Modifier.padding(16.dp))
                                    }
                                    viewModel.playlists.forEachIndexed { index, saved ->
                                        if (index > 0) HorizontalDivider(color = Muted.copy(alpha = .2f))
                                        Box(Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                                            ) {
                                                val type = when (saved.protocol) { SourceProtocol.STALKER -> "Stalker"; SourceProtocol.XTREAM -> "Xtream Codes"; SourceProtocol.M3U -> "M3U" }
                                                Text("${if (saved.id == viewModel.activePlaylist?.id) "● " else ""}$type · ${saved.name}", Modifier.width(132.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(com.stbplay.app.data.VisibleSourceUrl.mask(saved.url), Modifier.weight(1f), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Row(Modifier.width(350.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Button(onClick = { viewModel.refreshPlaylist(saved) }, modifier = Modifier.weight(1.8f), contentPadding = PaddingValues(horizontal = 8.dp), enabled = !loading) { Text("Refresh / reconnect", fontSize = 12.sp, maxLines = 1) }
                                                    OutlinedButton(onClick = { viewModel.editPlaylist(saved) }, modifier = Modifier.weight(0.65f), contentPadding = PaddingValues(horizontal = 8.dp), enabled = !loading) { Text("Edit", fontSize = 12.sp, maxLines = 1) }
                                                    OutlinedButton(onClick = { deleteCandidate = saved }, modifier = Modifier.weight(0.75f), contentPadding = PaddingValues(horizontal = 8.dp), enabled = !loading) { Text("Delete", fontSize = 12.sp, maxLines = 1) }
                                                }
                                            }
                                        }
                                    }
                                        }
                                    }
                                    Text("Connect a source", fontSize = 20.sp, fontWeight = FontWeight.Bold)

                                    ExpandableSourceCard(
                                        title = "STALKER PORTAL (MAC AUTHENTICATION)",
                                        subtitle = "Connect using Portal URL and MAC Address (MAG200/250)",
                                        expanded = expandedSource == SourceProtocol.STALKER,
                                        onToggle = { if (!loading) expandedSource = if (expandedSource == SourceProtocol.STALKER) null else SourceProtocol.STALKER }
                                    ) {
                                        SourceTextField(value = stalkerServer, onValueChange = { stalkerServer = it; viewModel.fieldErrors = viewModel.fieldErrors - "portal" }, error = viewModel.fieldErrors["portal"], label = { Text("Portal URL (http://portal.example.com/c/)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                        Spacer(Modifier.height(8.dp))
                                        SourceTextField(value = stalkerMac, onValueChange = { stalkerMac = it; viewModel.fieldErrors = viewModel.fieldErrors - "mac" }, error = viewModel.fieldErrors["mac"], label = { Text("MAC Address (00:1A:79:XX:XX:XX)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                        Spacer(Modifier.height(12.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            OutlinedButton(onClick = { requestSave(SourceProtocol.STALKER) }, enabled = !loading) { Text("Save") }
                                            Button(onClick = { connectSource(SourceProtocol.STALKER) }, enabled = !loading) {
                                                Text(if (loading) "Connecting…" else "Connect")
                                            }
                                        }


                                    }

                                    ExpandableSourceCard(
                                        title = "XTREAM CODES API",
                                        subtitle = "Connect using Server URL, Username and Password",
                                        expanded = expandedSource == SourceProtocol.XTREAM,
                                        onToggle = { if (!loading) expandedSource = if (expandedSource == SourceProtocol.XTREAM) null else SourceProtocol.XTREAM }
                                    ) {
                                        SourceTextField(value = server, onValueChange = { server = it; viewModel.fieldErrors = viewModel.fieldErrors - "server" }, error = viewModel.fieldErrors["server"], label = { Text("Server URL (e.g. https://provider.example)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                        Spacer(Modifier.height(8.dp))
                                        SourceTextField(value = username, onValueChange = { username = it; viewModel.fieldErrors = viewModel.fieldErrors - "username" }, error = viewModel.fieldErrors["username"], label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                        Spacer(Modifier.height(8.dp))
                                        SourceTextField(value = password, onValueChange = { password = it; viewModel.fieldErrors = viewModel.fieldErrors - "password" }, error = viewModel.fieldErrors["password"], label = { Text("Password") }, visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                                        TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide password" else "Show password") }
                                        Spacer(Modifier.height(12.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            OutlinedButton(onClick = { requestSave(SourceProtocol.XTREAM) }, enabled = !loading) { Text("Save") }
                                            Button(onClick = { connectSource(SourceProtocol.XTREAM) }, enabled = !loading) { Text("Connect Xtream") }
                                        }


                                    }

                                    ExpandableSourceCard(
                                        title = "M3U / M3U8 PLAYLIST",
                                        subtitle = "Connect using a direct M3U or M3U8 playlist web address",
                                        expanded = expandedSource == SourceProtocol.M3U,
                                        onToggle = { if (!loading) expandedSource = if (expandedSource == SourceProtocol.M3U) null else SourceProtocol.M3U }
                                    ) {
                                        SourceTextField(value = playlistUrl, onValueChange = { playlistUrl = it; viewModel.fieldErrors = viewModel.fieldErrors - "playlist" }, error = viewModel.fieldErrors["playlist"], label = { Text("Playlist URL (https://…)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                        Spacer(Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            OutlinedButton(onClick = { requestSave(SourceProtocol.M3U) }, enabled = !loading) { Text("Save") }
                                            Button(onClick = { connectSource(SourceProtocol.M3U) }, enabled = !loading) { Text("Connect playlist") }
                                        }

                                    }

                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                            Section.EPISODES -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { if (!viewModel.backFromStalkerEpisodes()) section = Section.SERIES }) { Text("Back to previous list") }
                                CatalogScreen(episodeList, "No episodes", viewModel.episodeError ?: "The provider did not return playable episodes.", favorites,
                                    onSelect = { if (!loading) viewModel.requestPlayback(it) }, onFavorite = { id -> toggleFavorite(id) })
                            }
                            Section.GUIDE -> Unit
                            Section.SETTINGS -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                ParentalControlSettings()
                                PrivacyAndDataSettings(viewModel)
                                AboutSettings()
                            }
                            else -> {}
                        }
                        Spacer(Modifier.height(16.dp))
                        if (section == Section.SOURCES && message.isNotBlank() && message != "Add an authorized playlist in Sources to get started.") Text(message, color = if (message.startsWith("Could not")) Color(0xFFFF9D9D) else Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        selected?.let { playing ->
            PlayerScreen(item = playing, stalkerCreds = connectedStalker, repository = repository, preferences = preferences, sourceKey = sourceKey, startPosition = viewModel.startPosition, onClose = { selected = null }, onNextChannel = { viewModel.zap(1) }, onPreviousChannel = { viewModel.zap(-1) })
        }
        dialogs()
        }
    }
}
}

@Composable
private fun ExpandableSourceCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (expanded) Panel else Panel.copy(alpha = .75f), RoundedCornerShape(18.dp))
            .then(if (focused) Modifier.border(1.dp, AccentBlue, RoundedCornerShape(18.dp)) else Modifier)
            .padding(18.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (expanded) AccentBlue else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text(if (expanded) "▲" else "▼", color = AccentBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
        }
        if (expanded) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Panel2)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}


@Composable
private fun ChannelRow(item: MediaItem, favorite: Boolean, program: com.stbplay.app.data.db.EpgProgramEntity? = null, now: Long = System.currentTimeMillis(), onClick: () -> Unit, onFavorite: () -> Unit) {
    val playbackFocus = remember(item.id) { FocusRequester() }
    val bookmark = LocalPlaybackFocusBookmark.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().focusRequester(playbackFocus)
            .background(if (focused) Panel2 else Panel.copy(alpha = .8f), RoundedCornerShape(13.dp))
            .then(if (focused) Modifier.border(1.dp, AccentBlue, RoundedCornerShape(13.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable { bookmark(playbackFocus); onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).background(Color(0xFF263650), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text(item.name.take(1).uppercase(), color = AccentBlue, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.group, color = Muted, fontSize = 11.sp, maxLines = 1)
            if (program != null) {
                Text(program.title, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = ((now - program.startMillis).toFloat() / (program.endMillis - program.startMillis)).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = AccentBlue)
            }
        }
        TextButton(onClick = onFavorite) { Text(if (favorite) "★" else "☆", color = AccentBlue, fontSize = 21.sp) }
        Text("▶", color = AccentBlue, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun MediaRow(items: List<MediaItem>, favorites: Set<String>, onSelect: (MediaItem) -> Unit, onFavorite: (String) -> Unit) {
    val bookmark = LocalPlaybackFocusBookmark.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.id }) { item ->
            val playbackFocus = remember(item.id) { FocusRequester() }
            var focused by remember { mutableStateOf(false) }
            Column(
                Modifier.width(150.dp).focusRequester(playbackFocus)
                    .background(if (focused) Panel2 else Panel, RoundedCornerShape(14.dp))
                    .then(if (focused) Modifier.border(1.dp, AccentBlue, RoundedCornerShape(14.dp)) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { bookmark(playbackFocus); onSelect(item) }
                    .padding(12.dp)
            ) {
                Box(Modifier.fillMaxWidth().height(72.dp).background(Brush.linearGradient(listOf(Panel2, Color(0xFF243B52))), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text("▶", color = AccentBlue, fontSize = 24.sp) }
                Spacer(Modifier.height(8.dp))
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                TextButton(onClick = { onFavorite(item.id) }) { Text(if (item.id in favorites) "★ Favorite" else "☆ Favorite", color = AccentBlue, fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun CatalogScreen(items: List<MediaItem>, emptyTitle: String, emptyText: String, favorites: Set<String>, onSelect: (MediaItem) -> Unit, onFavorite: (String) -> Unit) {
    val bookmark = LocalPlaybackFocusBookmark.current
    if (items.isEmpty()) EmptyPanel(emptyTitle, emptyText) else {
        var query by remember { mutableStateOf("") }
        SourceTextField(query, { query = it }, label = { Text("Search titles") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(14.dp))
        items.filter { it.name.contains(query, true) }.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    val playbackFocus = remember(item.id) { FocusRequester() }
                    var focused by remember { mutableStateOf(false) }
                    Column(
                        Modifier.weight(1f).focusRequester(playbackFocus)
                            .padding(bottom = 12.dp)
                            .background(if (focused) Panel2 else Panel, RoundedCornerShape(14.dp))
                            .then(if (focused) Modifier.border(1.dp, AccentBlue, RoundedCornerShape(14.dp)) else Modifier)
                            .onFocusChanged { focused = it.isFocused }
                            .clickable { bookmark(playbackFocus); onSelect(item) }
                            .padding(12.dp)
                    ) {
                        Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.linearGradient(listOf(Color(0xFF233A52), Color(0xFF172238))), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text(if (item.kind == MediaKind.SERIES) "SERIES" else "MOVIE", color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(9.dp))
                        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { onFavorite(item.id) }) { Text(if (item.id in favorites) "★ Favorite" else "☆ Favorite", color = AccentBlue) }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun EmptyPanel(title: String, body: String) {
    Column(Modifier.fillMaxWidth().background(Panel.copy(alpha = .8f), RoundedCornerShape(18.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(body, color = Muted, fontSize = 13.sp)
    }
}

@Composable
internal fun InfoPanel(title: String, body: String) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(body, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun RowScope.QuickCard(title: String, subtitle: String, symbol: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.weight(1f)
            .height(112.dp)
            .background(if (focused) Panel2 else Panel, RoundedCornerShape(16.dp))
            .then(if (focused) Modifier.border(1.dp, AccentBlue, RoundedCornerShape(16.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(15.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(symbol, color = AccentBlue, fontSize = 22.sp)
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
    }
}

private fun navIcon(section: Section): String = when (section) {
    Section.EPISODES -> "▤"
    Section.HOME -> "⌂"
    Section.LIVE -> "▣"
    Section.MOVIES -> "▷"
    Section.SERIES -> "▤"
    Section.GUIDE -> "▦"
    Section.SOURCES -> "＋"
    Section.SETTINGS -> "⚙"
}

private const val FAVORITES_FOLDER = "__favorites__"

@Composable
private fun FolderPane(folders: List<CatalogFolder>, selected: String?, total: Int, favoriteCount: Int, enabled: Boolean, onSelect: (String?) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val sorted = remember(folders) { folders.sortedBy { it.title.lowercase() } }
    Column(Modifier.width(185.dp).fillMaxHeight().background(Panel, RoundedCornerShape(14.dp)).padding(10.dp)) {
        Text("FOLDERS", color = AccentBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        SourceTextField(filter, { filter = it }, label = { Text("Find a folder", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "__all__") { FolderRow("All", total.takeIf { it > 0 }, selected == null, enabled) { onSelect(null) } }
            item(key = FAVORITES_FOLDER) { FolderRow("★ Favorites", favoriteCount, selected == FAVORITES_FOLDER, enabled) { onSelect(FAVORITES_FOLDER) } }
            items(sorted.filter { it.title.contains(filter, true) }, key = { it.id }) { folder ->
                FolderRow(folder.title, folder.count, selected == folder.id, enabled) { onSelect(folder.id) }
            }
        }
    }
}

@Composable
private fun FolderRow(title: String, count: Int?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(if (selected || focused) Panel2 else Color.Transparent, RoundedCornerShape(9.dp))
        .then(if (selected || focused) Modifier.border(1.dp, AccentBlue, RoundedCornerShape(9.dp)) else Modifier)
        .onFocusChanged { focused = it.isFocused }.clickable { if (enabled) onClick() }.padding(10.dp)) {
        Text("▣  $title", color = if (selected) AccentBlue else Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (count != null) Text("$count items", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun RowScope.CatalogBrowser(
    title: String, folders: List<CatalogFolder>, selectedFolder: String?, media: List<MediaItem>, favoriteCount: Int, libraryTotal: Int, browseTotal: Int,
    remote: Boolean, loading: Boolean, cursor: StalkerCatalogCursor?, pageError: String?, favorites: Set<String>,
    searchValue: String, onSearchInput: (String) -> Unit,
    onFolder: (String?) -> Unit, onSearch: (String) -> Unit, onMore: () -> Unit, onRetry: () -> Unit,
    onSelect: (MediaItem) -> Unit, onFavorite: (String) -> Unit
) {
    val bookmark = LocalPlaybackFocusBookmark.current
    val search = searchValue
    val shown = if (remote) media else media.filter { (selectedFolder == null || selectedFolder == FAVORITES_FOLDER || it.group == selectedFolder) && it.name.contains(search, true) }
    val folderTitle = if (selectedFolder == FAVORITES_FOLDER) "★ Favorites" else folders.firstOrNull { it.id == selectedFolder }?.title ?: "All $title"
    val grid = rememberLazyGridState()
    val nearEnd by remember(shown.size, grid) { derivedStateOf {
        shown.isNotEmpty() && (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= shown.size - 3
    } }
    LaunchedEffect(cursor, nearEnd, loading, pageError, remote) {
        if (remote && cursor != null && nearEnd && !loading && pageError == null) {
            delay(400)
            onMore()
        }
    }
    LaunchedEffect(title, selectedFolder, media.firstOrNull()?.id) { grid.scrollToItem(0) }
    Column(Modifier.weight(1f).fillMaxHeight().padding(start = 30.dp, end = 34.dp, top = 26.dp, bottom = 24.dp)) {
        Text(title.uppercase(), color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("Browse by folder", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FolderPane(folders, selectedFolder, libraryTotal.takeIf { it > 0 } ?: media.size, favoriteCount, !loading, onFolder)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(folderTitle, color = AccentBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceTextField(search, onSearchInput, onSubmit = { if (remote) onSearch(search.trim()) }, label = { Text("Search this folder") }, singleLine = true, modifier = Modifier.weight(1f))
                    if (remote) Button(onClick = { onSearch(search) }, enabled = !loading) { Text("Search") }
                }
                Text(if (remote && browseTotal > 0) "${media.size} of $browseTotal titles loaded · more load as you scroll" else "${shown.size} titles", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                if (pageError != null) {
                    Text(pageError, color = Color(0xFFFF9D9D), fontSize = 12.sp)
                    OutlinedButton(onClick = onRetry, enabled = !loading) { Text("Retry") }
                }
                if (loading && media.isEmpty()) {
                    CircularProgressIndicator(color = AccentBlue)
                    Text("Loading this folder…", color = Muted)
                } else if (shown.isEmpty()) {
                    EmptyPanel("No titles here", if (folders.isEmpty() && media.isEmpty()) "Connect your source first. This provider may not offer this catalog." else "Choose another folder or search term.")
                } else {
                    LazyVerticalGrid(columns = GridCells.Fixed(2), state = grid, modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        gridItems(shown, key = { it.id }) { item ->
                            val playbackFocus = remember(item.id) { FocusRequester() }
                            var focused by remember { mutableStateOf(false) }
                            Column(Modifier.fillMaxWidth().focusRequester(playbackFocus).background(if (focused) Panel2 else Panel, RoundedCornerShape(12.dp))
                                .then(if (focused) Modifier.border(1.dp, AccentBlue, RoundedCornerShape(12.dp)) else Modifier)
                                .onFocusChanged { focused = it.isFocused }.clickable { bookmark(playbackFocus); onSelect(item) }.padding(12.dp)) {
                                Box(Modifier.fillMaxWidth().height(70.dp).background(Panel2, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text("▶", color = AccentBlue, fontSize = 24.sp) }
                                Spacer(Modifier.height(8.dp))
                                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(item.group, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 10.sp)
                                TextButton(onClick = { onFavorite(item.id) }) { Text(if (item.id in favorites) "★ Favorite" else "☆ Favorite", color = AccentBlue, fontSize = 11.sp) }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            if (loading) Text("Loading more…", color = AccentBlue)
                            else if (remote && cursor != null && pageError == null) TextButton(onClick = onMore) { Text("Load next page", color = AccentBlue) }
                            else if (remote && cursor == null && pageError == null) Text("All titles in this result loaded", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}


