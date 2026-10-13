package com.utahradio.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes

/**
 * A radio station.
 *
 * Built-in stations (FM100.3, Q92) find their stream automatically from the station website.
 * Stations you add yourself carry their stream address in [streamUrl]; ones added from the
 * Radio Browser directory also remember [radioBrowserUuid] so a changed address can be looked up.
 */
data class Station(
    val id: String,
    val name: String,
    val subtitle: String = "",
    val streamUrl: String? = null,
    val radioBrowserUuid: String? = null,
    /** "res" = built-in picture, "logo" = station logo downloaded when added, "tile" = generated. */
    val artKind: String = "tile",
    val artRes: Int = 0,
    val webPages: List<String> = emptyList(),
    val directorySearches: List<String> = emptyList(),
    val matchWords: List<String> = emptyList(),
    val tuneInId: String? = null,
    val builtIn: Boolean = false,
) {
    /** Does spoken/typed text (e.g. "play Q92") refer to this station? */
    fun matches(query: String): Boolean {
        val q = query.lowercase().replace("point", ".").replace(" ", "")
        val words = matchWords + name
        return words.any { w ->
            val k = w.lowercase().replace(" ", "")
            k.length >= 2 && q.contains(k)
        }
    }
}

object BuiltInStations {
    val all = listOf(
        Station(
            id = "fm100",
            name = "FM100.3",
            subtitle = "Salt Lake City",
            artKind = "res",
            artRes = R.drawable.station_fm100,
            webPages = listOf(
                "https://tuner.bonneville.com/live/KSFI-FM",
                "https://fm100.com/",
            ),
            directorySearches = listOf("FM100.3", "FM 100.3", "KSFI"),
            matchWords = listOf("fm100", "fm 100", "ksfi", "100.3"),
            tuneInId = "s111316",
            builtIn = true,
        ),
        Station(
            id = "q92",
            name = "Q92",
            subtitle = "Logan · 92.9 FM",
            artKind = "res",
            artRes = R.drawable.station_q92,
            webPages = listOf(
                "https://q929online.com/listen/",
                "https://streamdb7web.securenetsystems.net/v5/KBLQ",
                "https://q929online.com/",
                "https://q92.fm/",
            ),
            directorySearches = listOf("Q92", "Q 92.9", "KBLQ"),
            matchWords = listOf("q92", "q 92", "kblq", "92.9", "q929"),
            tuneInId = "s31767",
            builtIn = true,
        ),
    )

    fun byId(id: String?): Station? = all.firstOrNull { it.id == id }
}

const val RADIO_SCHEME = "radio"

/** Placeholder address; the player looks up the real stream when it starts loading. */
fun Station.lazyUri(): Uri = Uri.parse("$RADIO_SCHEME://station/$id")

/** Picture address Android Auto and the notification can load (served by [ArtProvider]). */
fun Station.artUri(context: Context): Uri =
    Uri.parse("content://${context.packageName}.art/$id?v=${(name + artKind).hashCode()}")

const val EXTRA_SONG = "song"
const val EXTRA_COVER = "cover"

/**
 * What the car screen / notification / home screen show.
 * With a song: title = song, artist = artist. Without: title = station, artist = city.
 */
fun Station.metadata(
    context: Context,
    song: String? = null,
    artist: String? = null,
    coverKey: String? = null,
): MediaMetadata {
    val hasSong = !song.isNullOrBlank()
    return MediaMetadata.Builder()
        .setTitle(if (hasSong) song else name)
        .setDisplayTitle(if (hasSong) song else name)
        .setArtist(if (hasSong) (artist?.takeIf { it.isNotBlank() } ?: name) else subtitle.ifBlank { "Live radio" })
        .setSubtitle(if (hasSong) name else subtitle)
        .setAlbumTitle(name)
        .setStation(name)
        .setArtworkUri(if (coverKey != null) CoverArt.uri(context, coverKey) else artUri(context))
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .setExtras(Bundle().apply {
            putBoolean(EXTRA_SONG, hasSong)
            if (coverKey != null) putString(EXTRA_COVER, coverKey)
        })
        .build()
}

/** [streamUrl] null = browse-list item (no address needed). */
fun Station.toMediaItem(context: Context, streamUrl: String?): MediaItem {
    val builder = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(metadata(context))
    // Ask the player to stay close to live (only matters for streams that allow rewinding).
    builder.setLiveConfiguration(
        MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(6_000).setMaxOffsetMs(15_000).build()
    )
    if (streamUrl != null) {
        builder.setUri(streamUrl)
        if (streamUrl.lowercase().substringBefore('?').endsWith(".m3u8")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
    }
    return builder.build()
}
