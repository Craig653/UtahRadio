package com.utahradio.app

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

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

        player = ExoPlayer.Builder(this)
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

        override fun onPlayerError(error: PlaybackException) {
            // The stream address may have changed. Look it up again once and retry.
            val index = player.currentMediaItemIndex
            val station = Stations.byId(player.currentMediaItem?.mediaId) ?: return
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
            val items = Stations.all.map { it.toMediaItem(this@RadioService, null) }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val station = Stations.byId(mediaId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(
                LibraryResult.ofItem(station.toMediaItem(this@RadioService, null), null)
            )
        }

        /**
         * Called when anything (phone screen, car screen, voice "play Q92") picks a station.
         * We load every station into the queue so the car's next/previous buttons switch stations.
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
            val station = pickStation(requested)
            queueStartingAt(station)
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
            queueStartingAt(Stations.byId(prefs.lastStationId) ?: Stations.all.first())
        }
    }

    private fun pickStation(item: MediaItem?): Station =
        Stations.byId(item?.mediaId)
            ?: Stations.search(item?.requestMetadata?.searchQuery)
            ?: Stations.byId(prefs.lastStationId)
            ?: Stations.all.first()

    private suspend fun queueStartingAt(station: Station): MediaSession.MediaItemsWithStartPosition {
        val resolved = Stations.all.map { s ->
            scope.async { s to StreamResolver.resolve(this@RadioService, s) }
        }.awaitAll()
        // Keep stations we found a stream for, plus the requested one (it will show an error if unreachable).
        val queue = resolved
            .filter { (s, url) -> url != null || s.id == station.id }
            .map { (s, url) -> s.toMediaItem(this, url ?: UNREACHABLE) }
        val index = queue.indexOfFirst { it.mediaId == station.id }.coerceAtLeast(0)
        return MediaSession.MediaItemsWithStartPosition(queue, index, C.TIME_UNSET)
    }
}

/** Used when no stream could be found; makes the player report a clear "can't connect" error. */
private const val UNREACHABLE = "https://stream-not-found.invalid/"
