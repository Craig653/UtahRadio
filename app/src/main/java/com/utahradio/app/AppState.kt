package com.utahradio.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar

/** Settings chosen on the Settings page. Reading them in the UI updates the UI automatically. */
object AppSettings {
    const val THEME_SYSTEM = 0
    const val THEME_DARK = 1
    const val THEME_LIGHT = 2

    private lateinit var prefs: SharedPreferences

    var theme by mutableStateOf(THEME_DARK)
        private set
    var largeText by mutableStateOf(false)
        private set
    var dataSaver by mutableStateOf(false)
        private set
    var autoplayOnOpen by mutableStateOf(false)
        private set
    var onboarded by mutableStateOf(true)
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        theme = prefs.getInt("theme", THEME_DARK)
        largeText = prefs.getBoolean("large_text", false)
        dataSaver = prefs.getBoolean("data_saver", false)
        autoplayOnOpen = prefs.getBoolean("autoplay", false)
        // People upgrading from an earlier version already have stations — skip first-run setup.
        val radio = context.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)
        onboarded = prefs.getBoolean("onboarded", radio.contains("stations_v2") || radio.contains("last_station"))
    }

    fun updateTheme(v: Int) { theme = v; prefs.edit().putInt("theme", v).apply() }
    fun updateLargeText(v: Boolean) { largeText = v; prefs.edit().putBoolean("large_text", v).apply() }
    fun updateDataSaver(v: Boolean) { dataSaver = v; prefs.edit().putBoolean("data_saver", v).apply() }
    fun updateAutoplay(v: Boolean) { autoplayOnOpen = v; prefs.edit().putBoolean("autoplay", v).apply() }
    fun finishOnboarding() { onboarded = true; prefs.edit().putBoolean("onboarded", true).apply() }
}

/** What the player is doing beyond play/pause (shared between the player service and the screen). */
object PlaybackStatus {
    /** True while the app is trying to get a dropped stream back. */
    val reconnecting = MutableStateFlow(false)
}

/**
 * Sleep timer: stop after a number of minutes, or at the end of the current song.
 * The player service watches this and fades out when it's time.
 */
object SleepTimer {
    data class State(val endsAt: Long = 0L, val endOfSong: Boolean = false) {
        val active get() = endsAt > 0L || endOfSong
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun setMinutes(minutes: Int) { _state.value = State(endsAt = System.currentTimeMillis() + minutes * 60_000L) }
    fun setEndOfSong() { _state.value = State(endOfSong = true) }
    fun cancel() { _state.value = State() }
}

/** Counts how much data streaming uses, this month, split by Wi-Fi and mobile data. */
object DataUsage {
    private lateinit var prefs: SharedPreferences
    private var pendingWifi = 0L
    private var pendingMobile = 0L
    private var lastFlush = 0L

    private val _mobileBytes = MutableStateFlow(0L)
    val mobileBytes: StateFlow<Long> = _mobileBytes
    private val _wifiBytes = MutableStateFlow(0L)
    val wifiBytes: StateFlow<Long> = _wifiBytes

    private fun monthKey(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}-${c.get(Calendar.MONTH) + 1}"
    }

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("data_usage", Context.MODE_PRIVATE)
        if (prefs.getString("month", null) != monthKey()) {
            prefs.edit().putString("month", monthKey()).putLong("mobile", 0).putLong("wifi", 0).apply()
        }
        _mobileBytes.value = prefs.getLong("mobile", 0)
        _wifiBytes.value = prefs.getLong("wifi", 0)
    }

    /** Called from the download thread for every chunk of audio received. */
    @Synchronized
    fun add(bytes: Int) {
        if (!::prefs.isInitialized) return
        if (NetworkMonitor.cellular.value) pendingMobile += bytes else pendingWifi += bytes
        val now = System.currentTimeMillis()
        if (now - lastFlush > 15_000) flush(now)
    }

    @Synchronized
    private fun flush(now: Long) {
        lastFlush = now
        if (prefs.getString("month", null) != monthKey()) {
            prefs.edit().putString("month", monthKey()).putLong("mobile", 0).putLong("wifi", 0).apply()
        }
        val mobile = prefs.getLong("mobile", 0) + pendingMobile
        val wifi = prefs.getLong("wifi", 0) + pendingWifi
        pendingMobile = 0; pendingWifi = 0
        prefs.edit().putLong("mobile", mobile).putLong("wifi", wifi).apply()
        _mobileBytes.value = mobile
        _wifiBytes.value = wifi
    }

    fun format(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
        else -> "${bytes / 1000} KB"
    }
}

/**
 * Stations you're trying out (from "Nearby" on the car screen or voice search) that aren't in
 * your list. They can play, but don't clutter "My stations".
 */
object TempStations {
    private val map = HashMap<String, Station>()

    @Synchronized fun put(s: Station) { map[s.id] = s }
    @Synchronized fun get(id: String?): Station? = id?.let { map[it] }
}

/** Your station, or a station you're trying out. */
fun findStation(context: Context, id: String?): Station? =
    StationRepository.byId(context, id) ?: TempStations.get(id)

/** Turns a directory listing into a playable station (not added to your list). */
fun DirStation.toStation(): Station = Station(
    id = StationRepository.idFor(this),
    name = name.take(40).trim(),
    subtitle = state.ifBlank { country },
    streamUrl = url,
    radioBrowserUuid = uuid.ifBlank { null },
    matchWords = listOf(name),
    tags = tags,
    state = state,
    lat = lat,
    lon = lon,
)
