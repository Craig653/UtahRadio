package com.utahradio.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A song heard on the radio. */
data class PlayedSong(val title: String, val artist: String, val station: String, val time: Long)

/** Small saved settings: custom stream addresses, last working address, last station, song history. */
class StationPrefs(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)

    fun override(id: String): String? = prefs.getString("override_$id", null)?.takeIf { it.isNotBlank() }
    fun setOverride(id: String, url: String?) {
        prefs.edit().apply {
            if (url.isNullOrBlank()) remove("override_$id") else putString("override_$id", url.trim())
        }.apply()
    }

    fun cached(id: String): String? = prefs.getString("cache_$id", null)
    fun setCached(id: String, url: String?) {
        prefs.edit().apply {
            if (url == null) remove("cache_$id") else putString("cache_$id", url)
        }.apply()
    }

    var lastStationId: String?
        get() = prefs.getString("last_station", null)
        set(value) = prefs.edit().putString("last_station", value).apply()

    // ---------------------------------------------------------------- recently played

    fun history(): List<PlayedSong> = runCatching {
        val arr = JSONArray(prefs.getString(HISTORY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PlayedSong(o.optString("t"), o.optString("a"), o.optString("s"), o.optLong("w"))
        }
    }.getOrDefault(emptyList())

    fun addToHistory(song: PlayedSong) {
        val list = history()
        val last = list.firstOrNull()
        if (last != null && last.title == song.title && last.artist == song.artist && last.station == song.station) return
        val updated = (listOf(song) + list).take(30)
        val arr = JSONArray()
        updated.forEach {
            arr.put(JSONObject().put("t", it.title).put("a", it.artist).put("s", it.station).put("w", it.time))
        }
        prefs.edit().putString(HISTORY, arr.toString()).apply()
    }

    fun clearHistory() = prefs.edit().remove(HISTORY).apply()

    companion object {
        const val HISTORY = "history"
    }
}
