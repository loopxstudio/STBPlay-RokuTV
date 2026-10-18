package com.stbplay.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.Button as NativeButton
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.datasource.DefaultDataSource
import com.stbplay.app.data.AspectMode
import com.stbplay.app.data.PlayerOptions
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.composed
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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PlayerScreen(item: MediaItem, stalkerCreds: StalkerCredentials?, repository: IptvRepository, preferences: Preferences, sourceKey: String, startPosition: Long = 0, onClose: () -> Unit, onNextChannel: () -> Unit, onPreviousChannel: () -> Unit) {
    val context = LocalContext.current
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    val inPip = activity?.inPip == true
    val nextChannel by rememberUpdatedState(onNextChannel)
    val previousChannel by rememberUpdatedState(onPreviousChannel)
    val live by rememberUpdatedState(item.kind == MediaKind.LIVE)

    val subtitleKey = "subtitle_${sourceKey}_${item.kind}_${item.id}"
    var subtitleUri by remember(item.id, sourceKey) { mutableStateOf(preferences.option(subtitleKey)) }
    var subtitleName by remember(item.id, sourceKey) { mutableStateOf(preferences.option("${subtitleKey}_name")) }
    var subtitleEnabled by remember { mutableStateOf(preferences.option("subtitles", "false").toBoolean()) }
    var aspect by remember { mutableStateOf(runCatching { AspectMode.valueOf(preferences.option("aspect", "FIT")) }.getOrDefault(AspectMode.FIT)) }
    var volume by remember { mutableStateOf(PlayerOptions.volume(preferences.option("volume", "100").toIntOrNull() ?: 100)) }
    var preferStereo by remember { mutableStateOf(preferences.option("prefer_stereo", "false").toBoolean()) }
    var settings by remember { mutableStateOf(false) }
    var settingsPage by remember { mutableStateOf("Player settings") }
    var notice by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var resolvedUrl by remember(item.id, item.streamUrl, item.cmd) { mutableStateOf<String?>(null) }
    var resolvedMimeType by remember(item.id) { mutableStateOf<String?>(null) }
    var resolving by remember(item.id) { mutableStateOf(!item.cmd.isNullOrBlank() && item.id.startsWith("stalker-")) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var retryCount by remember(item.id) { mutableStateOf(0) }
    var pendingResume by remember(item.id) { mutableStateOf(startPosition.takeIf { it > 0 }) }


    LaunchedEffect(item.id, item.streamUrl, item.cmd, retryCount) {
        error = null
        if (item.id.startsWith("stalker-") && stalkerCreds == null) {
            error = "Connect your Stalker source before playing saved favorites."
            resolving = false
            return@LaunchedEffect
        }
        if (item.id.startsWith("stalker-") && !item.cmd.isNullOrBlank() && stalkerCreds != null && stalkerCreds.server.isNotBlank() && stalkerCreds.mac.isNotBlank()) {
            resolving = true
            try {
                val stream = repository.resolveStalkerPlayback(stalkerCreds, item.cmd, isVod = (item.kind != MediaKind.LIVE),
                    movieId = item.id.takeIf { it.startsWith("stalker-movie-") }?.removePrefix("stalker-movie-"))
                resolvedMimeType = stream.mimeType
                resolvedUrl = stream.url
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.w("STBPlayPlayback", "stage=stalker_link_resolution code=${com.stbplay.app.data.UserErrors.playbackResolutionTag(e)}")
                error = com.stbplay.app.data.UserErrors.playbackResolution(e)
                resolvedUrl = null
            } finally {
                resolving = false
            }
        } else {
            resolvedUrl = item.streamUrl.ifBlank { item.cmd }
            resolving = false
        }
    }

    val currentItem by rememberUpdatedState(item)
    val playbackError by rememberUpdatedState<(PlaybackException) -> Unit>({ exception ->
        val http = generateSequence<Throwable>(exception) { it.cause }.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        error = if (exception is androidx.media3.exoplayer.ExoPlaybackException && exception.rendererName?.contains("Audio", ignoreCase = true) == true) {
            "This device could not play the audio. Try Automatic audio or a different audio track in Player settings."
        } else when (http?.responseCode) {
            401, 403 -> "The provider did not allow playback. Reconnect your source or check your account."
            404 -> "This stream is no longer available. Refresh your source or choose another title."
            429 -> "The provider is busy. Wait a minute before retrying."
            else -> "Playback stopped. Check your connection, then try again."
        }
    })
    val streamOrigin = remember(sourceKey) { java.util.concurrent.atomic.AtomicReference<okhttp3.HttpUrl?>(null) }
    val streamCookies = remember(sourceKey) { com.stbplay.app.data.StreamCookieJar() }
    val httpFactory = remember(sourceKey) {
        val cookies = streamCookies
        val client = okhttp3.OkHttpClient.Builder().cookieJar(cookies).connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .addNetworkInterceptor(com.stbplay.app.data.StreamRequestPolicy(streamOrigin, cookies)).build()
        androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client)
            .setUserAgent(com.stbplay.app.data.PlaybackRequests.APP_USER_AGENT)
    }
    val trackSelector = remember(sourceKey) { androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context) }
    val player = remember(sourceKey) {
        ExoPlayer.Builder(context, androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setTrackSelector(trackSelector)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_500, 5_000).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context, httpFactory)))
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onTracksChanged(value: Tracks) { tracks = value }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED && currentItem.kind != MediaKind.LIVE) preferences.recordPlayback(sourceKey, currentItem, 0, 0)
                    }
                    override fun onPlayerError(exception: PlaybackException) { playbackError(exception) }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { activity?.updatePipPlayback(isPlaying) }
                })
            }
    }
    val mediaSession = remember(player) {
        fun publicItem(item: ExoMediaItem) = ExoMediaItem.Builder().setMediaId(item.mediaId).setMediaMetadata(item.mediaMetadata).build()
        val publicPlayer = object : androidx.media3.common.ForwardingPlayer(player) {
            private fun canResume() = activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true && (!activity.accessSession.enabled || activity.accessSession.unlocked)
            override fun play() { if (canResume()) super.play() }
            override fun setPlayWhenReady(value: Boolean) { if (!value || canResume()) super.setPlayWhenReady(value) }
            override fun getCurrentMediaItem(): ExoMediaItem? = player.currentMediaItem?.let(::publicItem)
            override fun getMediaItemAt(index: Int): ExoMediaItem = publicItem(player.getMediaItemAt(index))
            override fun getCurrentTimeline(): androidx.media3.common.Timeline = object : androidx.media3.exoplayer.source.ForwardingTimeline(player.currentTimeline) {
                override fun getWindow(index: Int, window: androidx.media3.common.Timeline.Window, projectionUs: Long): androidx.media3.common.Timeline.Window {
                    super.getWindow(index, window, projectionUs)
                    window.mediaItem = publicItem(window.mediaItem)
                    return window
                }
            }
        }
        androidx.media3.session.MediaSession.Builder(context, publicPlayer).setId("stbplay-${System.identityHashCode(player)}").setCallback(object : androidx.media3.session.MediaSession.Callback {
            override fun onConnect(session: androidx.media3.session.MediaSession, controller: androidx.media3.session.MediaSession.ControllerInfo): androidx.media3.session.MediaSession.ConnectionResult {
                if (!controller.isTrusted && controller.packageName != context.packageName) return androidx.media3.session.MediaSession.ConnectionResult.reject()
                return androidx.media3.session.MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
                        Player.COMMAND_GET_METADATA, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME,
                        Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_SET_SPEED_AND_PITCH).build()).build()
            }
        }).build()
    }
    val sessionReleased = remember(mediaSession) { booleanArrayOf(false) }
    fun releaseSession() { if (!sessionReleased[0]) { sessionReleased[0] = true; mediaSession.release() } }
    LaunchedEffect(player, item.id) { player.stop(); player.clearMediaItems(); tracks = Tracks.EMPTY }
    LaunchedEffect(player, resolvedUrl, resolvedMimeType, retryCount) {
        val url = resolvedUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val request = com.stbplay.app.data.PlaybackRequests.parse(url)
        streamOrigin.set(request.url.toHttpUrlOrNull())
        httpFactory.setUserAgent(com.stbplay.app.data.PlaybackRequests.userAgent(item.id.startsWith("stalker-") && stalkerCreds != null, request.headers))
        httpFactory.setDefaultRequestProperties((if (item.id.startsWith("stalker-") && stalkerCreds != null) repository.stalkerPlaybackHeaders(stalkerCreds) else emptyMap()) + request.headers)
        val media = ExoMediaItem.Builder().setUri(request.url).setMimeType(resolvedMimeType)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(item.name).setArtist(item.group).build()).build()
        if (item.kind == MediaKind.LIVE) player.setMediaItem(media)
        else player.setMediaItem(media, pendingResume ?: 0)
        player.prepare(); player.playWhenReady = true
    }
    DisposableEffect(player, activity) {
        var resume = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isInPictureInPictureMode != true) {
                resume = player.playWhenReady
                if (player.currentMediaItem != null) preferences.recordPlayback(sourceKey, currentItem, player.currentPosition, player.duration)
                player.pause()
            } else if (event == Lifecycle.Event.ON_START && resume) {
                if (activity == null || !activity.accessSession.enabled || activity.accessSession.unlocked) player.play()
                resume = false
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer); activity?.updatePipPlayback(false) }
    }
    val released = remember(player) { booleanArrayOf(false) }
    fun checkpoint() {
        if (!released[0] && player.currentMediaItem != null && player.playbackState != Player.STATE_IDLE) {
            val position = if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition
            preferences.recordPlayback(sourceKey, item, position, player.duration)
        }
    }
    LaunchedEffect(player, item.id) {
        while (true) {
            delay(5_000)
            checkpoint()
        }
    }
    DisposableEffect(player, item.id, sourceKey) { onDispose { checkpoint() } }
    val checkpointOnExit by rememberUpdatedState<() -> Unit>({ checkpoint() })
    DisposableEffect(player, activity) {
        val stop: () -> Unit = { checkpointOnExit(); player.pause(); releaseSession() }
        activity?.stopProtectedPlayback = stop
        onDispose {
            if (activity?.stopProtectedPlayback === stop) activity.stopProtectedPlayback = null
            checkpointOnExit(); released[0] = true; releaseSession(); player.release()
        }
    }
    LaunchedEffect(player, preferStereo) {
        trackSelector.setParameters(trackSelector.parameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setMaxAudioChannelCount(if (preferStereo) 2 else Int.MAX_VALUE)
            .setExceedAudioConstraintsIfNecessary(true))
        preferences.saveOption("prefer_stereo", preferStereo.toString())
    }
    LaunchedEffect(player, volume) {
        player?.volume = volume / 100f
        preferences.saveOption("volume", volume.toString())
    }
    LaunchedEffect(player, subtitleEnabled) {
        player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitleEnabled).build() }
        preferences.saveOption("subtitles", subtitleEnabled.toString())
    }
    LaunchedEffect(aspect) { preferences.saveOption("aspect", aspect.name) }
    LaunchedEffect(player, resolvedUrl, subtitleUri, subtitleName) {
        if (resolvedUrl.isNullOrBlank()) return@LaunchedEffect
        val p = player ?: return@LaunchedEffect
        val mime = PlayerOptions.subtitleMime(subtitleName)
        val configs = if (subtitleUri.isNotBlank() && mime != null) {
            val readable = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(subtitleUri), "r")?.use { true } == true }.getOrDefault(false)
            if (!readable) {
                notice = "Subtitle file is unavailable. Import it again from Settings."
                subtitleUri = ""; preferences.saveOption(subtitleKey, "")
                return@LaunchedEffect
            }
            listOf(ExoMediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUri)).setMimeType(mime)
                .setLabel(subtitleName).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build())
        } else emptyList()
        val media = p.currentMediaItem ?: return@LaunchedEffect
        val position = p.currentPosition
        val playing = p.playWhenReady
        p.setMediaItem(media.buildUpon().setSubtitleConfigurations(configs).build(), position)
        p.prepare(); p.playWhenReady = playing
    }
    var resumeAfterPicker by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        activity?.authorizedDocumentPicker = false
        if (uri != null) {
            val name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } }.getOrNull() ?: uri.lastPathSegment.orEmpty()
            if (PlayerOptions.subtitleMime(name) == null) notice = "Choose an SRT, VTT, ASS or SSA subtitle file."
            else {
                val durable = runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
                subtitleName = name; subtitleUri = uri.toString(); subtitleEnabled = true
                player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).build() }
                preferences.saveOption(subtitleKey, if (durable) subtitleUri else "")
                preferences.saveOption("${subtitleKey}_name", name)
                notice = if (durable) "Imported $name" else "Imported $name for this session."
            }
        }
        player?.playWhenReady = resumeAfterPicker
        settings = true; settingsPage = "Subtitles"
    }
    fun changeVolume(delta: Int) { volume = PlayerOptions.volume(volume + delta); notice = "Player volume: $volume%" }
    fun chooseTrack(group: Tracks.Group, index: Int) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
        if (group.type == C.TRACK_TYPE_TEXT) subtitleEnabled = true
        settings = false
    }

    var isControllerVisible by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (player != null) {
            val fixed = aspect.ratio?.let { PlayerOptions.frame(
                LocalContext.current.resources.displayMetrics.widthPixels.toFloat(),
                LocalContext.current.resources.displayMetrics.heightPixels.toFloat(),
                it
            ) }
            val frame = if (fixed == null) Modifier.fillMaxSize() else Modifier.size(fixed.first.dp, fixed.second.dp).align(Alignment.Center)
            
            AndroidView(factory = { ctx -> ZappingPlayerView(ctx).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                post { requestFocus(); showController() }
                onZap = { direction -> if (direction > 0) nextChannel() else previousChannel() }

                this.player = player; useController = true; keepScreenOn = true
                installHeader(onPrevious = { previousChannel() }, onNext = { nextChannel() }, onClose = onClose)
                setShowSubtitleButton(true)
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                    isControllerVisible = visibility == View.VISIBLE
                    updateHeaderVisibility(visibility == View.VISIBLE)
                    if (visibility != View.VISIBLE) post { if (useController && hasWindowFocus()) requestFocus() }
                })
                findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener { settingsPage = "Player settings"; settings = true }
                findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)?.setOnClickListener { settingsPage = "Subtitles"; settings = true }
                installVolumeControls { delta -> changeVolume(delta) }
                post { styleControllerFocus() }
            } }, update = { view ->
                view.player = player
                view.updateVolume(volume)
                view.updateHeader(item.name, item.group, live, inPip)
                view.zappingEnabled = live && !settings && !inPip
                view.useController = !inPip
                if (inPip) view.hideController()
                view.resizeMode = when (aspect) {
                    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
            }, modifier = frame)
        } else {
            Text(if (error != null) "Playback error: $error" else "Preparing stream link…", color = if (error != null) Color(0xFFFF9D9D) else Muted, modifier = Modifier.align(Alignment.Center))
        }

        if (resolving) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = AccentBlue)
        if (error != null && !inPip) Column(modifier = Modifier.align(Alignment.Center).background(Panel).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(error.orEmpty(), color = Color(0xFFFF9D9D))
            Row {
                Button(onClick = { pendingResume = player.currentPosition.takeIf { it > 0 }; resolvedUrl = null; retryCount++ }) { Text("Retry playback") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onClose, modifier = Modifier.playerFocusOutline()) { Text("Back to list") }
            }
        }

    }
    if (settings && !inPip) AlertDialog(
        onDismissRequest = { settings = false },
        title = { Text(settingsPage) },
        confirmButton = { TextButton(onClick = { settings = false }, modifier = Modifier.playerFocusOutline()) { Text("Done") } },
        dismissButton = { if (settingsPage != "Player settings") TextButton(onClick = { settingsPage = "Player settings" }, modifier = Modifier.playerFocusOutline()) { Text("← Settings") } },
        text = {
            LazyColumn(Modifier.heightIn(max = 310.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                fun option(label: String, action: () -> Unit) { item { OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth().playerFocusOutline()) { Text(label) } } }
                when (settingsPage) {
                    "Player settings" -> {
                        option("Subtitles: ${if (subtitleEnabled) "On" else "Off"}") { settingsPage = "Subtitles" }
                        option("Aspect ratio: ${aspect.label}") { settingsPage = "Aspect ratio" }
                        option("Audio track / sound") { settingsPage = "Audio track" }
                        option("Audio information / no sound?") { settingsPage = "Audio information" }
                        option("Playback speed") { settingsPage = "Playback speed" }
                        option("Volume −  ($volume%)") { changeVolume(-10) }
                        option("Volume +  ($volume%)") { changeVolume(10) }
                    }
                    "Audio information" -> {
                        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        val count = audio.sumOf { it.length }
                        val supported = audio.sumOf { group -> (0 until group.length).count { group.isTrackSupported(it) } }
                        val selected = audio.sumOf { group -> (0 until group.length).count { group.isTrackSelected(it) } }
                        val manager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        val muted = manager.isStreamMute(android.media.AudioManager.STREAM_MUSIC) || manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0
                        item { Text("Player volume: $volume%") }
                        item { Text("Audio tracks: $count; supported: $supported; selected: $selected") }
                        com.stbplay.app.data.AudioDiagnostics.messages(player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED,
                            count, supported, selected, volume, muted).forEach { message -> item { Text(message) } }
                        audio.forEach { group -> (0 until group.length).forEach { index ->
                            val format = group.getTrackFormat(index)
                            item { Text("${if (group.isTrackSelected(index)) "● " else ""}${format.label ?: format.language ?: "Audio ${index + 1}"}: ${format.sampleMimeType ?: "unknown codec"}, ${format.channelCount.takeIf { it > 0 } ?: "?"} channels, ${format.sampleRate.takeIf { it > 0 } ?: "?"} Hz${if (!group.isTrackSupported(index)) " (unsupported)" else ""}") }
                        } }
                        option("Automatic audio track") {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
                            settingsPage = "Audio track"
                        }
                    }
                    "Aspect ratio" -> AspectMode.entries.forEach { mode -> option("${if (mode == aspect) "● " else ""}${mode.label}") { aspect = mode } }
                    "Playback speed" -> listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { speed -> option("${speed}×") { player?.setPlaybackSpeed(speed); settings = false } }
                    else -> {
                        val type = if (settingsPage == "Subtitles") C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
                        if (type == C.TRACK_TYPE_AUDIO) {
                            option("Automatic audio track") {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
                            }
                            option("Prefer stereo tracks: ${if (preferStereo) "On" else "Off"}") { preferStereo = !preferStereo }
                            option("Audio information / no sound?") { settingsPage = "Audio information" }
                            item { Text("Stereo preference chooses a stereo track when one is available; it does not convert an unsupported codec or create missing audio.", color = Muted) }
                        }
                        if (type == C.TRACK_TYPE_TEXT) {
                            option("${if (!subtitleEnabled) "● " else ""}Subtitles Off") { subtitleEnabled = false }
                            option("${if (subtitleEnabled) "● " else ""}Subtitles On / automatic") {
                                subtitleEnabled = true
                                player?.let { p ->
                                    val builder = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    val group = tracks.groups.firstOrNull { g -> g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.isTrackSupported(it) } }
                                    if (group != null) builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, (0 until group.length).first { group.isTrackSupported(it) }))
                                    p.trackSelectionParameters = builder.build()
                                }
                            }
                            option("Import subtitle file…") {
                                resumeAfterPicker = player?.playWhenReady == true
                                player?.pause(); settings = false
                                try {
                                    val intent = ActivityResultContracts.OpenDocument().createIntent(context, arrayOf("*/*"))
                                    val handler = context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                                    if (handler?.activityInfo?.packageName == "com.android.tv.frameworkpackagestubs") throw android.content.ActivityNotFoundException()
                                    activity?.authorizedDocumentPicker = true
                                    picker.launch(arrayOf("*/*"))
                                }
                                catch (_: android.content.ActivityNotFoundException) { activity?.authorizedDocumentPicker = false; player?.playWhenReady = resumeAfterPicker; notice = "Install a document picker on this TV to import subtitle files." }
                            }
                            if (subtitleUri.isNotBlank()) option("Remove file: $subtitleName") {
                                subtitleUri = ""; preferences.saveOption(subtitleKey, ""); notice = "Imported subtitle removed."
                            }
                        }
                        val groups = tracks.groups.filter { it.type == type }
                        if (groups.isEmpty()) item { Text(if (type == C.TRACK_TYPE_TEXT) "No subtitle tracks reported by this stream. You can import a file." else "No audio tracks reported yet.") }
                        if (type == C.TRACK_TYPE_AUDIO && groups.isNotEmpty() && groups.none { group -> (0 until group.length).any { group.isTrackSupported(it) } }) {
                            item { Text("The stream's audio formats are not supported on this device. Open Audio information for details.") }
                        }
                        groups.forEach { group -> (0 until group.length).filter { group.isTrackSupported(it) }.forEach { index ->
                            val format = group.getTrackFormat(index)
                            val baseLabel = format.label ?: format.language ?: "Track ${index + 1}"
                            val label = if (type == C.TRACK_TYPE_AUDIO) "$baseLabel · ${format.sampleMimeType?.removePrefix("audio/") ?: "audio"} · ${format.channelCount.takeIf { it > 0 } ?: "?"} ch" else baseLabel
                            option("${if (group.isTrackSelected(index) && (type != C.TRACK_TYPE_TEXT || subtitleEnabled)) "● " else ""}$label") { chooseTrack(group, index) }
                        } }
                    }
                }
            }
        }
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ZappingPlayerView(context: android.content.Context) : PlayerView(context) {
    var zappingEnabled = false
    var onZap: (Int) -> Unit = {}
    private var volumeBar: android.widget.ProgressBar? = null
    fun installVolumeControls(onChange: (Int) -> Unit) {
        val controls = findViewById<LinearLayout>(androidx.media3.ui.R.id.exo_basic_controls) ?: return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            tag = "stbplay_volume_controls"
        }
        val bar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = "stbplay_volume_bar"
            max = 100
            isIndeterminate = false
            isFocusable = false
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF159DFF.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF33465C.toInt())
        }
        volumeBar = bar
        column.addView(bar, LinearLayout.LayoutParams(dp(88), dp(6)))
        val icons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(R.drawable.ic_vol_down to -10, R.drawable.ic_vol_up to 10).forEach { (resource, delta) ->
            val button = android.widget.ImageButton(context).apply {
                id = View.generateViewId()
                setImageResource(resource)
                contentDescription = if (delta < 0) "Volume down" else "Volume up"
                isFocusable = true
                background = playerFocusBackground(context, true)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setOnClickListener { onChange(delta); showController() }
            }
            icons.addView(button, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        column.addView(icons, LinearLayout.LayoutParams(dp(96), dp(48)))
        controls.addView(column, 0, LinearLayout.LayoutParams(dp(96), dp(54)).apply { gravity = android.view.Gravity.CENTER_VERTICAL })
    }
    fun updateVolume(value: Int) {
        val percent = PlayerOptions.volume(value)
        volumeBar?.setProgress(percent, false)
        volumeBar?.contentDescription = "Player volume $percent percent"
    }
    private fun firstFocusable(view: View): View? {
        if (!view.isShown || !view.isEnabled) return null
        if (view.isFocusable) return view
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) firstFocusable(view.getChildAt(index))?.let { return it }
        return null
    }
    private var header: LinearLayout? = null
    private var heading: android.widget.TextView? = null
    private var subheading: android.widget.TextView? = null
    private var previous: View? = null
    private var next: View? = null
    private var back: View? = null
    private var pip = false

    fun installHeader(onPrevious: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
        val overlay = overlayFrameLayout ?: return
        val density = resources.displayMetrics.density
        val idleTimeout = controllerShowTimeoutMs
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((22 * density).toInt(), (14 * density).toInt(), (22 * density).toInt(), (14 * density).toInt())
            setBackgroundColor(android.graphics.Color.parseColor("#B3080D18"))
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        heading = android.widget.TextView(context).apply { textSize = 22f; setTextColor(android.graphics.Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        subheading = android.widget.TextView(context).apply { textSize = 12f; setTextColor(android.graphics.Color.LTGRAY); maxLines = 1 }
        titles.addView(heading); titles.addView(subheading)
        bar.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fun action(label: String, click: () -> Unit): View {
            val button = NativeButton(context).apply {
                id = View.generateViewId()
                text = label
                contentDescription = label
                isAllCaps = false
                textSize = 13f
                isFocusable = true
                setTextColor(android.graphics.Color.WHITE)
                background = playerFocusBackground(context, circular = false)
                stateListAnimator = null
                setOnFocusChangeListener { _, focused ->
                    if (focused) {
                        controllerShowTimeoutMs = 0
                    } else post {
                        if (!inside(findFocus(), header)) controllerShowTimeoutMs = idleTimeout
                    }
                }
                setOnClickListener { click() }
            }
            bar.addView(button, LinearLayout.LayoutParams((100 * density).toInt(), (48 * density).toInt()).apply { marginStart = (10 * density).toInt() })
            return button
        }
        previous = action("Previous", onPrevious)
        next = action("Next", onNext)
        back = action("Back to list", onClose)
        header = bar
        overlay.addView(bar, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP))
        // Media3 inserts its controller after exo_overlay. Keep the header above
        // the controller scrim so its text and static focus border stay bright.
        overlay.bringToFront()
    }

    fun updateHeader(title: String, group: String, live: Boolean, inPip: Boolean) {
        heading?.text = title
        subheading?.text = group
        previous?.visibility = if (live) View.VISIBLE else View.GONE
        next?.visibility = if (live) View.VISIBLE else View.GONE
        pip = inPip
        updateHeaderVisibility(isControllerFullyVisible)
    }

    fun updateHeaderVisibility(visible: Boolean) { header?.visibility = if (visible && !pip) View.VISIBLE else View.GONE }

    fun styleControllerFocus() {
        fun visit(view: View) {
            if (view.isFocusable && (view is android.widget.ImageButton || view is android.widget.TextView)) {
                view.background = playerFocusBackground(context, view !is NativeButton)
            }
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        findViewById<View>(androidx.media3.ui.R.id.exo_controller)?.let { visit(it) }
    }

    private fun inside(view: View?, container: View?): Boolean {
        if (view == null || container == null) return false
        var current: android.view.ViewParent? = view.parent
        if (view === container) return true
        while (current != null) { if (current === container) return true; current = current.parent }
        return false
    }

    private var touchX = 0f
    private var touchY = 0f
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            touchX = event.x; touchY = event.y
        } else if (event.actionMasked == android.view.MotionEvent.ACTION_UP && zappingEnabled) {
            val dy = event.y - touchY
            if (kotlin.math.abs(dy) > 80 * resources.displayMetrics.density && kotlin.math.abs(dy) > kotlin.math.abs(event.x - touchX)) {
                val cancel = android.view.MotionEvent.obtain(event)
                cancel.action = android.view.MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel); cancel.recycle()
                onZap(if (dy < 0) 1 else -1)
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }
    private var revealKey = KeyEvent.KEYCODE_UNKNOWN
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == revealKey && event.action == KeyEvent.ACTION_UP) {
            revealKey = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        val navigationKey = event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        if (useController && !pip && !isControllerFullyVisible && navigationKey) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                revealKey = event.keyCode
                showController()
                updateHeaderVisibility(true)
                // Reveal first; do not zap or activate a hidden button on this press.
                post { firstFocusable(findViewById<View>(androidx.media3.ui.R.id.exo_basic_controls))?.requestFocus() }
            }
            return true
        }
        if (zappingEnabled && event.keyCode in listOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onZap(if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
            return true
        }
        if (isControllerFullyVisible && event.action == KeyEvent.ACTION_DOWN) {
            val focused = findFocus()
            val progress = findViewById<View>(androidx.media3.ui.R.id.exo_progress)
            val controls = findViewById<android.view.ViewGroup>(androidx.media3.ui.R.id.exo_basic_controls)
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && inside(focused, header)) {
                if (progress?.isShown == true && progress.requestFocus()) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && focused === progress) {
                val target = if (previous?.isShown == true) previous else back
                if (target?.requestFocus() == true) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focused === progress && controls != null) {
                for (i in 0 until controls.childCount) {
                    val target = controls.getChildAt(i)
                    if (firstFocusable(target)?.requestFocus() == true) return true
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && inside(focused, controls)) {
                if (progress?.isShown == true && progress.requestFocus()) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

private fun playerFocusBackground(context: android.content.Context, circular: Boolean): android.graphics.drawable.StateListDrawable {
    val density = context.resources.displayMetrics.density
    fun shape(fill: Int, stroke: Int, width: Float) = android.graphics.drawable.GradientDrawable().apply {
        if (circular) shape = android.graphics.drawable.GradientDrawable.OVAL else cornerRadius = 12 * density
        setColor(fill)
        setStroke((width * density).toInt().coerceAtLeast(1), stroke)
    }
    val focused = android.graphics.drawable.LayerDrawable(arrayOf(
        shape(0x402394D8, 0x8056C8FF.toInt(), 5f),
        shape(0xCC19364F.toInt(), 0xFF68D5FF.toInt(), 2f)
    )).apply { val inset = (3 * density).toInt(); setLayerInset(1, inset, inset, inset, inset) }
    return android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), focused)
        addState(intArrayOf(), shape(0x40000000, 0x609BAAC0, 1f))
        setEnterFadeDuration(0); setExitFadeDuration(0)
    }
}

private fun Modifier.playerFocusOutline(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused }.then(if (focused) Modifier
        .border(5.dp, AccentBlue.copy(alpha = .45f), RoundedCornerShape(14.dp))
        .border(2.dp, Color(0xFF68D5FF), RoundedCornerShape(14.dp)) else Modifier)
}
