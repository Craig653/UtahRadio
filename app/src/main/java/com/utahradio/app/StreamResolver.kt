package com.utahradio.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finds a playable internet stream address for a station.
 *
 * Station stream addresses change from time to time, so instead of hard-coding one the app looks
 * it up: first on the station's own website / web player, then in the public Radio Browser
 * directory. Every candidate is checked to make sure it really is audio before it is used.
 */
object StreamResolver {
    private const val TAG = "StreamResolver"
    private const val USER_AGENT = "UtahRadio/1.1 (Android)"
    private const val TIMEOUT_MS = 6000

    private val DIRECTORY_SERVERS = listOf(
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
    )

    private val AUDIO_EXTENSIONS = listOf(".aac", ".mp3", ".m3u8", ".pls", ".m3u", ".ogg", ".opus")
    private val STREAM_HOSTS = listOf(
        "cdnstream1.com", "streamtheworld.com", "securenetsystems.net", "streamguys",
        "amperwave.net", "radio.co", "zeno.fm", "streamon.fm", "icecast", "shoutcast",
        "listen.", "stream.", "live.", "ice.", "playerservices", "revma", "lightningstream",
        "cast", "spacial",
    )
    private val NOT_AUDIO = listOf(
        ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".ico", ".woff",
        ".woff2", ".ttf", ".json", ".xml", ".pdf", ".html", ".php",
        "facebook.com", "instagram.com", "youtube.com", "twitter.com", "x.com/", "google",
        "apple.com", "doubleclick", "cookiepro", "/podcast", "play.cdnstream1.com/s/",
    )

    /**
     * Returns a stream address to play, or null if none could be found.
     * [useCache] = false forces a fresh lookup (used after a playback error).
     */
    suspend fun resolve(context: Context, station: Station, useCache: Boolean = true): String? =
        withContext(Dispatchers.IO) {
            val prefs = StationPrefs(context)
            prefs.override(station.id)?.let { return@withContext expandPlaylist(it) ?: it }

            // Stations you added: use their saved address (look it up again after an error).
            if (station.streamUrl != null) {
                if (!useCache && station.radioBrowserUuid != null) {
                    runCatching { fromDirectoryUuid(station.radioBrowserUuid) }.getOrNull()?.let { fresh ->
                        if (fresh != station.streamUrl) {
                            StationRepository.update(context, station.copy(streamUrl = fresh))
                        }
                        return@withContext fresh
                    }
                }
                return@withContext expandPlaylist(station.streamUrl) ?: station.streamUrl
            }

            if (useCache) prefs.cached(station.id)?.let { return@withContext it }

            val found = runCatching { fromWebsite(station) }.getOrNull()
                ?: runCatching { fromDirectory(station) }.getOrNull()
                ?: runCatching { fromTuneIn(station) }.getOrNull()
            Log.i(TAG, "${station.name}: resolved to $found")
            if (found != null) prefs.setCached(station.id, found)
            found
        }

    /** An address we already know without going online (null if a lookup is needed). */
    fun knownUrl(context: Context, station: Station): String? {
        val prefs = StationPrefs(context)
        val url = prefs.override(station.id) ?: station.streamUrl ?: prefs.cached(station.id) ?: return null
        val path = url.lowercase().substringBefore('?')
        return if (path.endsWith(".pls") || path.endsWith(".m3u")) null else url
    }

    // ---------------------------------------------------------------- directory search (Add station)

    data class DirectoryStation(
        val uuid: String,
        val name: String,
        val url: String,
        val favicon: String,
        val place: String,
        val details: String,
    )

    /** Searches the free Radio Browser directory (radio-browser.info) by station name. */
    suspend fun searchDirectory(query: String): List<DirectoryStation> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        for (server in DIRECTORY_SERVERS) {
            val json = fetchText(
                "$server/json/stations/search?name=$q&hidebroken=true&order=clickcount&reverse=true&limit=60"
            ) ?: continue
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: continue
            val seen = HashSet<String>()
            return@withContext (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url_resolved").ifBlank { o.optString("url") }
                val name = o.optString("name").trim()
                if (url.isBlank() || name.isBlank() || !seen.add(name.lowercase() + url)) return@mapNotNull null
                val place = listOf(o.optString("state"), o.optString("country"))
                    .filter { it.isNotBlank() }.joinToString(", ")
                val bitrate = o.optInt("bitrate")
                val details = listOf(
                    o.optString("codec").takeIf { it.isNotBlank() && it != "UNKNOWN" },
                    if (bitrate > 0) "$bitrate kbps" else null,
                    o.optString("tags").split(",").firstOrNull()?.takeIf { it.isNotBlank() },
                ).filterNotNull().joinToString(" · ")
                DirectoryStation(o.optString("stationuuid"), name, url, o.optString("favicon"), place, details)
            }
        }
        emptyList()
    }

    private fun fromDirectoryUuid(uuid: String): String? {
        for (server in DIRECTORY_SERVERS) {
            val json = fetchText("$server/json/stations/byuuid/$uuid") ?: continue
            val o = runCatching { JSONArray(json).optJSONObject(0) }.getOrNull() ?: return null
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            if (url.isBlank()) return null
            return expandPlaylist(url) ?: url
        }
        return null
    }

    // ---------------------------------------------------------------- station website

    private fun fromWebsite(station: Station): String? {
        val pagesToVisit = ArrayDeque(station.webPages)
        val visited = HashSet<String>()
        val candidates = LinkedHashMap<String, Int>()

        while (pagesToVisit.isNotEmpty() && visited.size < 6) {
            val page = pagesToVisit.removeFirst()
            if (!visited.add(page)) continue
            val html = fetchText(page) ?: continue
            for (url in findUrls(html)) {
                var score = streamScore(url)
                // Station sites often list sister stations too; prefer streams named after this one.
                if (score > 0 && station.matchWords.any { url.lowercase().contains(it.replace(" ", "")) }) score += 3
                if (score > 0) {
                    candidates[url] = maxOf(candidates[url] ?: 0, score)
                } else if (looksLikePlayerPage(url) && url !in visited) {
                    pagesToVisit.addLast(url)
                }
            }
        }

        val ranked = candidates.entries.sortedByDescending { it.value }.map { it.key }.take(10)
        for (candidate in ranked) {
            val playable = expandPlaylist(candidate) ?: candidate
            if (isAudio(playable)) return playable
        }
        return null
    }

    private val URL_REGEX = Regex("""https?://[^\s"'<>()\\]+""")

    private fun findUrls(html: String): List<String> {
        val text = html.replace("\\/", "/").replace("&amp;", "&")
        return URL_REGEX.findAll(text).map { it.value.trimEnd(',', ';', '.') }.distinct().toList()
    }

    private fun streamScore(url: String): Int {
        val u = url.lowercase()
        if (NOT_AUDIO.any { u.contains(it) }) return 0
        val path = u.substringBefore('?')
        var score = 0
        if (AUDIO_EXTENSIONS.any { path.endsWith(it) }) score += 3
        val host = u.substringAfter("://").substringBefore('/')
        if (STREAM_HOSTS.any { host.contains(it) }) score += 2
        if (path.endsWith("/stream") || path.endsWith("/live") || path.contains("/stream/")) score += 1
        // A bare known stream host with no file is only a weak signal.
        return if (score >= 2) score else 0
    }

    private fun looksLikePlayerPage(url: String): Boolean {
        val u = url.lowercase()
        if (NOT_AUDIO.filterNot { it == ".html" || it == ".php" }.any { u.contains(it) }) return false
        return listOf("listen", "tuner.", "/live/", "player", "stream").any { u.contains(it) }
    }

    // ---------------------------------------------------------------- Radio Browser directory

    private fun fromDirectory(station: Station): String? {
        for (server in DIRECTORY_SERVERS) {
            for (name in station.directorySearches) {
                val q = URLEncoder.encode(name, "UTF-8")
                val json = fetchText(
                    "$server/json/stations/search?name=$q&countrycode=US&hidebroken=true" +
                        "&order=votes&reverse=true&limit=30"
                ) ?: continue
                val list = runCatching { JSONArray(json) }.getOrNull() ?: continue
                val matches = (0 until list.length()).map { list.getJSONObject(it) }.filter { o ->
                    val haystack = (o.optString("name") + " " + o.optString("homepage") + " " +
                        o.optString("tags")).lowercase()
                    val inUtah = o.optString("state").lowercase().contains("utah") ||
                        o.optString("homepage").lowercase().let { h ->
                            station.matchWords.any { w -> h.contains(w.replace(" ", "")) }
                        }
                    inUtah && station.matchWords.any { haystack.replace(" ", "").contains(it.replace(" ", "")) }
                }
                for (m in matches) {
                    val url = m.optString("url_resolved").ifBlank { m.optString("url") }
                    if (url.isBlank()) continue
                    val playable = expandPlaylist(url) ?: url
                    if (isAudio(playable)) return playable
                }
            }
            // The first server that answered is enough.
            break
        }
        return null
    }

    // ---------------------------------------------------------------- TuneIn

    private fun fromTuneIn(station: Station): String? {
        val id = station.tuneInId ?: return null
        val json = fetchText("https://opml.radiotime.com/Tune.ashx?id=$id&render=json&formats=aac,mp3")
            ?: return null
        val body = runCatching { JSONObject(json).optJSONArray("body") }.getOrNull() ?: return null
        for (i in 0 until body.length()) {
            val url = body.optJSONObject(i)?.optString("url").orEmpty()
            if (!url.startsWith("http")) continue
            val playable = expandPlaylist(url) ?: url
            if (isAudio(playable)) return playable
        }
        return null
    }

    // ---------------------------------------------------------------- helpers

    /** .pls / .m3u files are tiny text files that point at the real stream; open them. */
    private fun expandPlaylist(url: String): String? {
        val path = url.lowercase().substringBefore('?')
        if (!path.endsWith(".pls") && !path.endsWith(".m3u")) return null
        val body = fetchText(url) ?: return null
        return body.lineSequence()
            .map { it.trim() }
            .map { if (it.lowercase().startsWith("file") && it.contains('=')) it.substringAfter('=') else it }
            .firstOrNull { it.startsWith("http") }
    }

    /** Opens the address and checks the server says it is audio (or an HLS playlist). */
    private fun isAudio(url: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(url)
            val code = conn.responseCode
            val type = (conn.contentType ?: "").lowercase()
            val path = url.lowercase().substringBefore('?')
            code in 200..299 && (
                type.startsWith("audio/") ||
                    type.contains("mpegurl") ||
                    type.contains("ogg") ||
                    type.contains("aac") ||
                    type.contains("mpeg") ||
                    (type.contains("octet-stream") && AUDIO_EXTENSIONS.any { path.endsWith(it) })
                )
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchText(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(url)
            if (conn.responseCode !in 200..299) return null
            val type = (conn.contentType ?: "").lowercase()
            if (type.startsWith("audio/")) return null // never download a live stream as text
            conn.inputStream.use { input ->
                val bytes = input.readNBytesCompat(1_500_000)
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetch failed $url: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "*/*")
        return conn
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
