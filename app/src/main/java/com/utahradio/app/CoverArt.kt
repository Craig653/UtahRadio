package com.utahradio.app

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections

/**
 * Album covers for the song that's playing, looked up in Apple's free iTunes Search API
 * (no account needed). Covers are saved on the phone so each one is only downloaded once.
 */
object CoverArt {
    private const val TAG = "CoverArt"
    private val misses = Collections.synchronizedSet(HashSet<String>())

    fun key(artist: String?, title: String): String {
        val raw = (artist ?: "").lowercase().trim() + "|" + title.lowercase().trim()
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun dir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }
    fun file(context: Context, key: String) = File(dir(context), "$key.jpg")
    fun has(context: Context, key: String) = file(context, key).let { it.exists() && it.length() > 0 }
    fun uri(context: Context, key: String): Uri = Uri.parse("content://${context.packageName}.art/cover/$key")

    /** Finds and saves the cover (blocking; call off the main thread). Returns true if saved. */
    fun fetch(context: Context, artist: String?, title: String, key: String): Boolean {
        if (has(context, key)) return true
        if (key in misses) return false
        val found = runCatching { lookup(artist, title) }.getOrNull()
        val ok = found != null && runCatching { download(found, file(context, key)) }.getOrDefault(false)
        if (!ok) misses.add(key)
        Log.i(TAG, "$artist - $title: ${if (ok) "cover found" else "no cover"}")
        if (ok) trim(context)
        return ok
    }

    private fun clean(s: String) = s
        .replace(Regex("""\s*[(\[].*?[)\]]"""), "")
        .replace(Regex("""(?i)\s+(feat\.?|ft\.?|featuring)\s+.*$"""), "")
        .trim()

    private fun mainArtist(a: String) =
        a.split(Regex("""(?i)\s+(feat\.?|ft\.?|featuring|&|x|and|with|,)\s+|,\s*""")).first().trim()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun lookup(artist: String?, title: String): String? {
        val t = clean(title)
        val a = artist?.let { mainArtist(clean(it)) }?.takeIf { it.isNotBlank() }
        if (t.isBlank()) return null
        val term = URLEncoder.encode(listOfNotNull(a, t).joinToString(" "), "UTF-8")
        val json = get("https://itunes.apple.com/search?term=$term&media=music&entity=song&limit=8&country=US")
            ?: return null
        val results = JSONObject(json).optJSONArray("results") ?: return null
        val wantArtist = a?.let(::norm)
        val wantTitle = norm(t).take(8)
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val art = r.optString("artworkUrl100")
            if (art.isBlank()) continue
            val gotArtist = norm(r.optString("artistName"))
            val gotTitle = norm(r.optString("trackName"))
            val artistOk = wantArtist == null || wantArtist.isEmpty() ||
                gotArtist.contains(wantArtist) || wantArtist.contains(gotArtist)
            val titleOk = gotTitle.contains(wantTitle) || wantTitle.contains(gotTitle.take(8))
            // Without an artist, only trust a strong title match.
            if (artistOk && titleOk) return art.replace("100x100bb", "600x600bb")
        }
        return null
    }

    private fun download(url: String, dest: File): Boolean {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) return false
            val bytes = conn.inputStream.use { it.readBytes() }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) return false
            dest.writeBytes(bytes)
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String): String? {
        val conn = open(url)
        return try {
            if (conn.responseCode !in 200..299) null else conn.inputStream.use { String(it.readBytes()) }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6000
        readTimeout = 8000
        setRequestProperty("User-Agent", "UtahRadio/1.2 (Android)")
    }

    /** Keeps the newest 300 covers (a few MB at most). */
    private fun trim(context: Context) {
        val files = dir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(300).forEach { it.delete() }
    }
}

/**
 * Remembers when each station's current song started, so leaving a station and coming back
 * doesn't reset "Song playing 2:13" to 0:00.
 */
object SongClock {
    private data class Entry(val key: String, val startedAt: Long)
    private val entries = HashMap<String, Entry>()
    private const val MAX_SONG_MS = 15 * 60 * 1000L

    @Synchronized
    fun mark(stationId: String, songKey: String?): Long {
        val now = System.currentTimeMillis()
        if (songKey == null) {
            entries.remove(stationId); return now
        }
        val e = entries[stationId]
        if (e != null && e.key == songKey && now - e.startedAt < MAX_SONG_MS) return e.startedAt
        entries[stationId] = Entry(songKey, now)
        return now
    }

    @Synchronized
    fun startedAt(stationId: String?, songKey: String?): Long {
        val e = entries[stationId ?: return 0L] ?: return 0L
        return if (e.key == songKey) e.startedAt else 0L
    }
}
