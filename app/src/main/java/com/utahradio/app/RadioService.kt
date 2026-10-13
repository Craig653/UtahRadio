package com.utahradio.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Plays the stations. The phone screen, the notification, the widget, Bluetooth buttons and
 * Android Auto all talk to this one service, so they always stay in sync.
 */
class RadioService : MediaLibraryService() {

    companion object {
        private const val ROOT_ID = "root"
        private const val MY_ID = "my"
        private const val RECENT_ID = "recent"
        private const val NEARBY_ID = "nearby"

        /** After even a short pause, resuming jumps back to live instead of playing old audio. */
        private const val STALE_PAUSE_MS = 3_000L
        /** How far behind "now" we allow before jumping forward to live. */
        private const val MAX_LIVE_OFFSET_MS = 15_000L
        /** Where we sit when jumping to live (a little room so it doesn't stutter). */
        private const val LIVE_EDGE_MS = 6_000L
        /** Waits between reconnect attempts when the signal drops. */
        private val RETRY_DELAYS_MS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)
        /** Data saver: highest audio quality on mobile data (for stations that offer choices). */
        private const val DATA_SAVER_BITRATE = 64_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var prefs: StationPrefs
    private var session: MediaLibrarySession? = null
    private var pausedAt = 0L
    /** Set whenever a station (re)starts; the first time it's ready we make sure it's at live. */
    private var checkLive = true

    private var retryJob: Job? = null
    private var retryAttempt = 0
    private var sleepJob: Job? = null
    private var fading = false

    override fun onCreate() {
        super.onCreate()
        prefs = StationPrefs(this)
        StationRepository.init(this)
        AppSettings.init(this)
        NetworkMonitor.start(this)
        DataUsage.init(this)

        // Counts data used (shown in Settings).
        val usage = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork) DataUsage.add(bytesTransferred)
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("UtahRadio/1.5 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
            .setTransferListener(usage)

        // "radio://station/<id>" addresses are looked up the moment a station starts loading,
        // so every station can sit in the queue (car next/previous) without looking them all up first.
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme == RADIO_SCHEME) {
                val station = findStation(this, spec.uri.lastPathSegment)
                    ?: throw IOException("Unknown station")
                val url = runBlocking { StreamResolver.resolve(this@RadioService, station) }
                    ?: throw IOException("No stream found for ${station.name}")
                spec.withUri(Uri.parse(url))
            } else {
                spec
            }
        }

        trackSelector = DefaultTrackSelector(this)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setTrackSelector(trackSelector)
            // Never speed up or slow down to chase "live" — always normal speed.
            .setLivePlaybackSpeedControl(
                DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMinPlaybackSpeed(1f)
                    .setFallbackMaxPlaybackSpeed(1f)
                    .setMaxLiveOffsetErrorMsForUnitSpeed(Long.MAX_VALUE / 2)
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true, // pauses for navigation prompts / phone calls
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK) // keep streaming with the screen off
            .build()
        player.setPlaybackSpeed(1f)
        player.repeatMode = Player.REPEAT_MODE_ALL // "next" on the last station wraps around
        player.addListener(PlayerEvents())

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, StationSwitchingPlayer(player), LibraryCallback())
            .setSessionActivity(openApp)
            .build()

        // Refresh the car screen when your stations change.
        scope.launch {
            StationRepository.stations.drop(1).collect { list ->
                session?.notifyChildrenChanged(MY_ID, list.size, null)
            }
        }
        // Signal came back: reconnect right away.
        scope.launch {
            NetworkMonitor.online.drop(1).collect { online ->
                if (online && PlaybackStatus.reconnecting.value) retryNow()
            }
        }
        // Data saver: lower quality on mobile data (for stations that offer more than one quality).
        scope.launch {
            combine(NetworkMonitor.cellular, snapshotFlow { AppSettings.dataSaver }) { cell, saver -> cell && saver }
                .collect { limit ->
                    trackSelector.parameters = trackSelector.buildUponParameters()
                        .setMaxAudioBitrate(if (limit) DATA_SAVER_BITRATE else Int.MAX_VALUE)
                        .build()
                }
        }
        // Sleep timer.
        scope.launch {
            SleepTimer.state.collect { st ->
                sleepJob?.cancel()
                if (st.endsAt > 0) {
                    sleepJob = scope.launch {
                        val wait = st.endsAt - System.currentTimeMillis() - 8_000
                        if (wait > 0) delay(wait)
                        fadeOutAndStop()
                    }
                } else if (!st.active) {
                    if (fading) { fading = false; player.volume = 1f }
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away while stopped closes the service; while playing, keep the music going.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        PlaybackStatus.reconnecting.value = false
        RadioWidget.update(this, null, null, null, null, false)
        session?.run {
            release()
        }
        player.release()
        session = null
        super.onDestroy()
    }

    /** Next/previous always change station (never "rewind to start" on live radio). */
    private class StationSwitchingPlayer(p: Player) : ForwardingPlayer(p) {
        override fun seekToNext() = wrappedPlayer.seekToNextMediaItem()
        override fun seekToPrevious() = wrappedPlayer.seekToPreviousMediaItem()
        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            return if (wrappedPlayer.mediaItemCount > 1) {
                base.buildUpon()
                    .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            } else base
        }
        override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
    }

    // ------------------------------------------------------------------ player events

    private inner class PlayerEvents : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                retryAttempt = 0
                retryJob?.cancel()
                PlaybackStatus.reconnecting.value = false
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                pausedAt = SystemClock.elapsedRealtime()
                stopRetrying()
            } else if (pausedAt != 0L && SystemClock.elapsedRealtime() - pausedAt > STALE_PAUSE_MS) {
                // Live radio: reconnect so you hear what's on now, not what was buffered.
                pausedAt = 0L
                checkLive = true
                player.seekToDefaultPosition()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { prefs.lastStationId = it }
            checkLive = true
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_IDLE) {
                checkLive = true
                if (player.playerError == null) stopRetrying() // stopped on purpose
            }
            if (playbackState == Player.STATE_READY && checkLive) {
                checkLive = false
                goLive()
            }
        }

        override fun onEvents(p: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED,
                )
            ) updateWidget()
        }

        /** Song info sent inside the stream ("Artist - Title"). */
        override fun onMetadata(metadata: Metadata) {
            var title: String? = null
            var artist: String? = null
            for (i in 0 until metadata.length()) {
                when (val entry = metadata.get(i)) {
                    is IcyInfo -> parseIcy(entry.title)?.let { (a, t) -> artist = a; title = t }
                    is TextInformationFrame -> when (entry.id) {
                        "TIT2" -> title = entry.values.firstOrNull()
                        "TPE1" -> artist = entry.values.firstOrNull()
                    }
                    else -> Unit
                }
            }
            showSong(title, artist)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!player.playWhenReady) return
            val station = findStation(this@RadioService, player.currentMediaItem?.mediaId) ?: return
            PlaybackStatus.reconnecting.value = true
            scheduleRetry(station)
        }
    }

    // ------------------------------------------------------------------ reconnecting

    /**
     * Lost the stream (no signal, station hiccup). First try: look the address up again in case it
     * changed. Then keep trying, waiting a bit longer each time — and immediately when the phone
     * gets back online. Stops when you press stop/pause.
     */
    private fun scheduleRetry(station: Station) {
        retryJob?.cancel()
        val attempt = retryAttempt++
        retryJob = scope.launch {
            if (attempt > 0) {
                val wait = RETRY_DELAYS_MS[minOf(attempt - 1, RETRY_DELAYS_MS.size - 1)]
                delay(wait)
            }
            if (!NetworkMonitor.online.value) return@launch // wait for the network callback
            val fresh = attempt == 0 || attempt % 4 == 0
            if (fresh) prefs.setCached(station.id, null)
            val url = StreamResolver.resolve(this@RadioService, station, useCache = !fresh)
            val index = player.currentMediaItemIndex
            if (url != null && index < player.mediaItemCount && player.currentMediaItem?.mediaId == station.id) {
                val keep = player.currentMediaItem?.mediaMetadata
                var item = station.toMediaItem(this@RadioService, url)
                if (keep != null) item = item.buildUpon().setMediaMetadata(keep).build()
                player.replaceMediaItem(index, item)
            }
            checkLive = true
            player.prepare()
            player.play()
        }
    }

    private fun retryNow() {
        if (!player.playWhenReady) { stopRetrying(); return }
        if (findStation(this, player.currentMediaItem?.mediaId) == null) return
        retryAttempt = 1 // skip the long waits
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(1_000) // give the new connection a moment
            checkLive = true
            player.prepare()
            player.play()
        }
    }

    private fun stopRetrying() {
        retryJob?.cancel()
        retryAttempt = 0
        PlaybackStatus.reconnecting.value = false
    }

    // ------------------------------------------------------------------ sleep timer

    private suspend fun fadeOutAndStop() {
        if (!player.playWhenReady) { SleepTimer.cancel(); return }
        fading = true
        val steps = 40
        for (i in steps downTo 0) {
            if (!fading) return
            player.volume = i / steps.toFloat()
            delay(200)
        }
        player.pause()
        player.stop()
        player.volume = 1f
        fading = false
        SleepTimer.cancel()
    }

    // ------------------------------------------------------------------ live

    /**
     * Some stations' streams start you at the beginning of the current song (or minutes back).
     * Skip ahead to "now" whenever we're noticeably behind.
     */
    private fun goLive() {
        if (!player.isCurrentMediaItemLive || !player.isCurrentMediaItemSeekable) return
        val offset = player.currentLiveOffset
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        if (offset == C.TIME_UNSET || offset > MAX_LIVE_OFFSET_MS) {
            player.seekTo(maxOf(0L, duration - LIVE_EDGE_MS))
        }
    }

    // ------------------------------------------------------------------ song info, covers, widget

    /** Puts the current song on the phone, notification and car screen, and in "Recently played". */
    private fun showSong(rawTitle: String?, rawArtist: String?) {
        val title = rawTitle?.trim()?.takeIf { it.isNotBlank() }
        val artist = rawArtist?.trim()?.takeIf { it.isNotBlank() }
        val item = player.currentMediaItem ?: return
        val station = findStation(this, item.mediaId) ?: return
        // Some stations send their own name or slogan as the "song" — show that as no song.
        val isReallySong = title != null && !title.equals(station.name, ignoreCase = true)
        val current = item.mediaMetadata
        val newTitle = if (isReallySong) title else null
        val currentSong = if (current.extras?.getBoolean(EXTRA_SONG) == true) current.title?.toString() else null
        if (newTitle == currentSong && (artist ?: "") == (if (currentSong != null) current.artist?.toString() ?: "" else "")) return

        // Sleep timer set to "end of this song": the song just changed, so stop now.
        if (SleepTimer.state.value.endOfSong && currentSong != null && newTitle != currentSong) {
            scope.launch { fadeOutAndStop() }
        }

        val coverKey = newTitle?.let { CoverArt.key(artist, it) }
        SongClock.mark(station.id, coverKey)
        val haveCover = coverKey != null && CoverArt.has(this, coverKey)
        val updated = item.buildUpon()
            .setMediaMetadata(station.metadata(this, newTitle, artist, if (haveCover) coverKey else null))
            .build()
        player.replaceMediaItem(player.currentMediaItemIndex, updated) // same stream: keeps playing
        if (newTitle != null) {
            prefs.addToHistory(PlayedSong(newTitle, artist ?: "", station.name, System.currentTimeMillis(), station.id))
            session?.notifyChildrenChanged(RECENT_ID, 10, null)
        }

        // Look up the album cover in the background, then show it if the song is still playing.
        if (newTitle != null && coverKey != null && !haveCover) {
            scope.launch {
                val found = withContext(Dispatchers.IO) { CoverArt.fetch(this@RadioService, artist, newTitle, coverKey) }
                if (!found) return@launch
                val now = player.currentMediaItem ?: return@launch
                val md = now.mediaMetadata
                if (now.mediaId != station.id || md.extras?.getString(EXTRA_COVER) != null ||
                    md.title?.toString() != newTitle
                ) return@launch
                val withCover = now.buildUpon()
                    .setMediaMetadata(station.metadata(this@RadioService, newTitle, artist, coverKey))
                    .build()
                player.replaceMediaItem(player.currentMediaItemIndex, withCover)
            }
        }
    }

    private fun updateWidget() {
        val item = player.currentMediaItem
        val station = findStation(this, item?.mediaId)
        val md = item?.mediaMetadata
        val hasSong = md?.extras?.getBoolean(EXTRA_SONG) == true
        val active = player.playWhenReady && player.playbackState != Player.STATE_IDLE
        RadioWidget.update(
            this,
            station,
            if (hasSong) md?.title?.toString() else null,
            if (hasSong) md?.artist?.toString() else null,
            md?.extras?.getString(EXTRA_COVER),
            active,
        )
    }

    // ------------------------------------------------------------------ Android Auto / controllers

    private fun folder(id: String, title: String, grid: Boolean): MediaItem {
        val extras = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                if (grid) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    private suspend fun children(parentId: String): List<MediaItem> = when (parentId) {
        ROOT_ID -> listOf(
            folder(MY_ID, "My stations", grid = true),
            folder(RECENT_ID, "Recent", grid = false),
            folder(NEARBY_ID, "Nearby", grid = true),
        )
        MY_ID -> StationRepository.all(this).map { it.toMediaItem(this, null) }
        RECENT_ID -> {
            val seen = LinkedHashSet<String>()
            prefs.history().mapNotNull { h ->
                val s = findStation(this, h.stationId.ifBlank { null })
                    ?: StationRepository.all(this).firstOrNull { it.name == h.station }
                s?.takeIf { seen.add(it.id) }
            }.map { it.toMediaItem(this, null) }
        }
        NEARBY_ID -> {
            val place = Directory.savedPlace(this)
            if (place == null) emptyList() else {
                val mine = StationRepository.all(this).map { it.id }.toSet()
                runCatching { Directory.nearby(place) }.getOrDefault(emptyList())
                    .map { it.toStation() }
                    .filter { it.id !in mine }
                    .take(25)
                    .onEach { TempStations.put(it) }
                    .map { it.toMediaItem(this, null) }
            }
        }
        else -> emptyList()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        private val rootItem = MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(getString(R.string.app_name))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .build()
            )
            .build()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            LibraryResult.ofItemList(ImmutableList.copyOf(children(parentId)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val station = findStation(this@RadioService, mediaId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(
                LibraryResult.ofItem(station.toMediaItem(this@RadioService, null), null)
            )
        }

        /**
         * Called when anything (phone screen, car screen, voice "play Q92" / "play country") picks a
         * station. Your stations go in the queue so the car's next/previous buttons switch stations.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val requested = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
                ?: mediaItems.firstOrNull()
            queueStartingAt(pickStation(requested))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.map { item ->
                val station = pickStation(item)
                station.toMediaItem(
                    this@RadioService,
                    StreamResolver.resolve(this@RadioService, station) ?: UNREACHABLE,
                )
            }.toMutableList()
        }

        /** "Resume" from the car or a Bluetooth play button when the app isn't open. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val all = StationRepository.all(this@RadioService)
            queueStartingAt(
                StationRepository.byId(this@RadioService, prefs.lastStationId)
                    ?: all.firstOrNull() ?: BuiltInStations.all.first()
            )
        }
    }

    // ------------------------------------------------------------------ choosing a station

    private suspend fun pickStation(item: MediaItem?): Station {
        findStation(this, item?.mediaId)?.let { return it }
        val query = item?.requestMetadata?.searchQuery?.trim()?.lowercase()
        if (!query.isNullOrBlank()) {
            StationRepository.search(this, query)?.let { return it }
            byGenre(query)?.let { return it }
        }
        val all = StationRepository.all(this)
        return StationRepository.byId(this, prefs.lastStationId)
            ?: all.firstOrNull()
            ?: BuiltInStations.all.first()
    }

    /** "play country", "play some rock", "play the news", "play something local". */
    private suspend fun byGenre(query: String): Station? {
        val q = query.lowercase()
        val genre = GENRES.firstOrNull { (label, tag) -> q.contains(label.lowercase()) || q.contains(tag) }
            ?.second
        val mine = StationRepository.all(this)
        if (genre != null) {
            mine.firstOrNull { s -> s.tags.any { it.lowercase().contains(genre) } }?.let { return it }
        }
        val wantsLocal = genre != null || listOf("local", "nearby", "near me", "something").any { q.contains(it) }
        if (!wantsLocal) return null
        val place = Directory.savedPlace(this) ?: return null
        val found = runCatching { Directory.nearby(place, genre) }.getOrDefault(emptyList()).firstOrNull()
            ?: return null
        return StationRepository.byId(this, StationRepository.idFor(found))
            ?: found.toStation().also { TempStations.put(it) }
    }

    private suspend fun queueStartingAt(station: Station): MediaSession.MediaItemsWithStartPosition {
        // Look up the chosen station now (so it starts right away); the rest are looked up when reached.
        val chosenUrl = StreamResolver.resolve(this, station) ?: UNREACHABLE
        val stations = StationRepository.all(this).ifEmpty { listOf(station) }
        val queue = stations.map { s ->
            if (s.id == station.id) {
                s.toMediaItem(this, chosenUrl)
            } else {
                s.toMediaItem(this, StreamResolver.knownUrl(this, s) ?: s.lazyUri().toString())
            }
        }.let { if (it.none { item -> item.mediaId == station.id }) it + station.toMediaItem(this, chosenUrl) else it }
        val index = queue.indexOfFirst { it.mediaId == station.id }.coerceAtLeast(0)
        return MediaSession.MediaItemsWithStartPosition(queue, index, C.TIME_UNSET)
    }
}

/** "Artist - Title" (or iHeart-style text="Title") → (artist, title). */
private fun parseIcy(raw: String?): Pair<String?, String>? {
    val s = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    Regex("""text="([^"]*)"""").find(s)?.let { m ->
        val artist = s.substringBefore(" - ", "").takeIf { it.isNotBlank() && !it.contains('=') }
        return artist to m.groupValues[1]
    }
    val idx = s.indexOf(" - ")
    return if (idx > 0) s.substring(0, idx).trim() to s.substring(idx + 3).trim() else null to s
}

/** Used when no stream could be found; makes the player report a clear "can't connect" error. */
private const val UNREACHABLE = "https://stream-not-found.invalid/"
