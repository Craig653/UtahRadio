package com.utahradio.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Plays the stations. The phone screen, the notification, Bluetooth buttons and Android Auto all
 * talk to this one service, so they always stay in sync.
 */
class RadioService : MediaLibraryService() {

    companion object {
        private const val ROOT_ID = "root"
        /** After this long paused, resuming jumps back to live instead of playing stale audio. */
        private const val STALE_PAUSE_MS = 20_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var player: ExoPlayer
    private lateinit var prefs: StationPrefs
    private var session: MediaLibrarySession? = null
    private var retriedStationId: String? = null
    private var pausedAt = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = StationPrefs(this)
        StationRepository.init(this)

        // "radio://station/<id>" addresses are looked up the moment a station starts loading,
        // so every station can sit in the queue (car next/previous) without looking them all up first.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("UtahRadio/1.1 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            if (spec.uri.scheme == RADIO_SCHEME) {
                val station = StationRepository.byId(this, spec.uri.lastPathSegment)
                    ?: throw IOException("Unknown station")
                val url = runBlocking { StreamResolver.resolve(this@RadioService, station) }
                    ?: throw IOException("No stream found for ${station.name}")
                spec.withUri(Uri.parse(url))
            } else {
                spec
            }
        }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
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
        player.repeatMode = Player.REPEAT_MODE_ALL // "next" on the last station wraps around
        player.addListener(PlayerEvents())

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()

        // When you add/remove/rename stations, refresh the list on the car screen.
        scope.launch {
            StationRepository.stations.drop(1).collect { list ->
                session?.notifyChildrenChanged(ROOT_ID, list.size, null)
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
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ player events

    private inner class PlayerEvents : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { prefs.lastStationId = it }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) retriedStationId = null
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                pausedAt = SystemClock.elapsedRealtime()
            } else if (pausedAt != 0L && SystemClock.elapsedRealtime() - pausedAt > STALE_PAUSE_MS) {
                // Live radio: reconnect so you hear what's on now, not what was buffered.
                pausedAt = 0L
                player.seekToDefaultPosition()
            }
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
            // The stream address may have changed. Look it up again once and retry.
            val index = player.currentMediaItemIndex
            val station = StationRepository.byId(this@RadioService, player.currentMediaItem?.mediaId) ?: return
            if (retriedStationId == station.id) return
            retriedStationId = station.id
            prefs.setCached(station.id, null)
            scope.launch {
                val url = StreamResolver.resolve(this@RadioService, station, useCache = false)
                    ?: return@launch
                if (index < player.mediaItemCount) {
                    player.replaceMediaItem(index, station.toMediaItem(this@RadioService, url))
                }
                player.prepare()
                player.play()
            }
        }
    }

    /** Puts the current song on the phone, notification and car screen, and in "Recently played". */
    private fun showSong(rawTitle: String?, rawArtist: String?) {
        val title = rawTitle?.trim()?.takeIf { it.isNotBlank() }
        val artist = rawArtist?.trim()?.takeIf { it.isNotBlank() }
        val item = player.currentMediaItem ?: return
        val station = StationRepository.byId(this, item.mediaId) ?: return
        // Some stations send their own name or slogan as the "song" — show that as no song.
        val isReallySong = title != null && !title.equals(station.name, ignoreCase = true)
        val current = item.mediaMetadata
        val newTitle = if (isReallySong) title else null
        val currentSong = if (current.extras?.getBoolean(EXTRA_SONG) == true) current.title?.toString() else null
        if (newTitle == currentSong && (artist ?: "") == (if (currentSong != null) current.artist?.toString() ?: "" else "")) return

        val coverKey = newTitle?.let { CoverArt.key(artist, it) }
        SongClock.mark(station.id, coverKey)
        val haveCover = coverKey != null && CoverArt.has(this, coverKey)
        val updated = item.buildUpon()
            .setMediaMetadata(station.metadata(this, newTitle, artist, if (haveCover) coverKey else null))
            .build()
        player.replaceMediaItem(player.currentMediaItemIndex, updated) // same stream: keeps playing
        if (newTitle != null) {
            prefs.addToHistory(PlayedSong(newTitle, artist ?: "", station.name, System.currentTimeMillis()))
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

    // ------------------------------------------------------------------ Android Auto / controllers

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
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT_ID) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
            val items = StationRepository.all(this@RadioService).map { it.toMediaItem(this@RadioService, null) }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val station = StationRepository.byId(this@RadioService, mediaId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(
                LibraryResult.ofItem(station.toMediaItem(this@RadioService, null), null)
            )
        }

        /**
         * Called when anything (phone screen, car screen, voice "play Q92") picks a station.
         * Every station goes in the queue so the car's next/previous buttons switch stations.
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
            queueStartingAt(StationRepository.byId(this@RadioService, prefs.lastStationId) ?: all.first())
        }
    }

    private fun pickStation(item: MediaItem?): Station {
        val all = StationRepository.all(this)
        return StationRepository.byId(this, item?.mediaId)
            ?: StationRepository.search(this, item?.requestMetadata?.searchQuery)
            ?: StationRepository.byId(this, prefs.lastStationId)
            ?: all.firstOrNull()
            ?: BuiltInStations.all.first()
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
