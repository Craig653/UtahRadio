package com.utahradio.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * The stations in the app. To add another station, add another entry to [Stations.all].
 *
 * The app finds the live stream address automatically (see [StreamResolver]):
 *  1. a stream address you typed in Settings, if any
 *  2. the last address that worked
 *  3. the station's own website / web player
 *  4. the public Radio Browser directory (radio-browser.info)
 *  5. TuneIn
 */
data class Station(
    val id: String,
    val name: String,
    val subtitle: String,
    val artRes: Int,
    /** Station web pages that contain (or link to) the "Listen Live" player. */
    val webPages: List<String>,
    /** Names to look up in the Radio Browser directory. */
    val directorySearches: List<String>,
    /** Words that identify this station (used to match directory results and voice searches). */
    val matchWords: List<String>,
    /** TuneIn station id, used as a last-resort way to find the stream. */
    val tuneInId: String? = null,
)

object Stations {
    val all = listOf(
        Station(
            id = "fm100",
            name = "FM100.3",
            subtitle = "Salt Lake City",
            artRes = R.drawable.station_fm100,
            webPages = listOf(
                "https://tuner.bonneville.com/live/KSFI-FM",
                "https://fm100.com/",
            ),
            directorySearches = listOf("FM100.3", "FM 100.3", "KSFI"),
            matchWords = listOf("fm100", "fm 100", "ksfi", "100.3"),
            tuneInId = "s111316",
        ),
        Station(
            id = "q92",
            name = "Q92",
            subtitle = "Logan · 92.9 FM",
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
        ),
    )

    fun byId(id: String?): Station? = all.firstOrNull { it.id == id }

    /** Matches spoken / typed text like "play Q92" or "FM 100". */
    fun search(query: String?): Station? {
        if (query.isNullOrBlank()) return null
        val q = query.lowercase().replace("point", ".").replace(" ", "")
        return all.firstOrNull { s ->
            s.matchWords.any { q.contains(it.replace(" ", "")) } ||
                q.contains(s.name.lowercase().replace(" ", ""))
        }
    }
}

private val artCache = HashMap<Int, ByteArray>()

private fun artBytes(context: Context, res: Int): ByteArray =
    artCache.getOrPut(res) { context.resources.openRawResource(res).use { it.readBytes() } }

fun Station.artUri(context: Context): Uri =
    Uri.parse("android.resource://${context.packageName}/$artRes")

/** Builds the item shown in Android Auto / the notification. [streamUrl] is null for browse lists. */
fun Station.toMediaItem(context: Context, streamUrl: String?): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(name)
        .setDisplayTitle(name)
        .setArtist(subtitle)
        .setSubtitle(subtitle)
        .setStation(name)
        .setArtworkUri(artUri(context))
        .setArtworkData(artBytes(context, artRes), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .build()
    val builder = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(metadata)
    if (streamUrl != null) builder.setUri(streamUrl)
    return builder.build()
}
