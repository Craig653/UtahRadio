package com.utahradio.app

import android.content.Context

/** Small saved settings: custom stream addresses, last working address, last station played. */
class StationPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)

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
}
