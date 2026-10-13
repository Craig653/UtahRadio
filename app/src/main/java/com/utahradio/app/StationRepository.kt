package com.utahradio.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Your list of stations, saved on the phone. The phone screen, the player and Android Auto all
 * read from here, so a station you add shows up everywhere.
 */
object StationRepository {
    private const val KEY = "stations_v2"

    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = _stations

    @Volatile private var loaded = false
    private lateinit var appContext: Context

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            appContext = context.applicationContext
            _stations.value = load()
            loaded = true
        }
    }

    fun all(context: Context): List<Station> {
        init(context); return _stations.value
    }

    fun byId(context: Context, id: String?): Station? = all(context).firstOrNull { it.id == id }

    fun search(context: Context, query: String?): Station? {
        if (query.isNullOrBlank()) return null
        return all(context).firstOrNull { it.matches(query) }
    }

    fun contains(context: Context, id: String) = byId(context, id) != null

    fun add(context: Context, station: Station) {
        init(context)
        if (contains(context, station.id)) return
        set(_stations.value + station)
    }

    /** Adds a directory station (downloads its logo first; call from a coroutine). */
    suspend fun addFromDirectory(context: Context, d: DirStation): Station {
        val id = idFor(d)
        byId(context, id)?.let { return it }
        val gotLogo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ArtStore.downloadLogo(context, id, d.favicon)
        }
        val station = Station(
            id = id,
            name = d.name.take(40).trim(),
            subtitle = listOf(d.state, d.countryCode.takeIf { it != "US" } ?: "").filter { it.isNotBlank() }
                .joinToString(", ").ifBlank { d.country },
            streamUrl = d.url,
            radioBrowserUuid = d.uuid.ifBlank { null },
            artKind = if (gotLogo) "logo" else "tile",
            matchWords = listOf(d.name),
            tags = d.tags,
            state = d.state,
            lat = d.lat,
            lon = d.lon,
        )
        add(context, station)
        return station
    }

    fun idFor(d: DirStation) = "rb-" + d.uuid.ifBlank { d.url.hashCode().toUInt().toString() }.lowercase()

    fun update(context: Context, station: Station) {
        init(context)
        set(_stations.value.map { if (it.id == station.id) station else it })
    }

    fun remove(context: Context, id: String) {
        init(context)
        set(_stations.value.filterNot { it.id == id })
        StationPrefs(context).apply { setOverride(id, null); setCached(id, null) }
        ArtStore.delete(context, id)
    }

    fun move(context: Context, id: String, delta: Int) {
        init(context)
        val list = _stations.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        val s = list.removeAt(i)
        list.add(j, s)
        set(list)
    }

    fun toggleFavorite(context: Context, id: String) {
        init(context)
        set(_stations.value.map { if (it.id == id) it.copy(favorite = !it.favorite) else it })
    }

    private fun set(list: List<Station>) {
        val sorted = list.sortedBy { !it.favorite } // favorites first, otherwise keep your order
        _stations.value = sorted
        save(sorted)
    }

    // ---------------------------------------------------------------- backup / restore

    /** Everything needed to restore your stations on another phone. */
    fun exportJson(context: Context): String {
        init(context)
        val prefs = StationPrefs(context)
        val arr = JSONArray()
        _stations.value.forEach { s ->
            arr.put(toJson(s).apply { prefs.override(s.id)?.let { put("override", it) } })
        }
        return JSONObject()
            .put("app", "Utah Radio")
            .put("version", 1)
            .put("exported", System.currentTimeMillis())
            .put("stations", arr)
            .toString(2)
    }

    /** Adds stations from a backup (ones you already have are kept). Returns how many were added. */
    fun importJson(context: Context, text: String): Int {
        init(context)
        val root = JSONObject(text)
        val arr = root.optJSONArray("stations") ?: throw IllegalArgumentException("Not a Utah Radio backup")
        val prefs = StationPrefs(context)
        var added = 0
        val current = _stations.value.toMutableList()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = fromJson(o) ?: continue
            o.optString("override").takeIf { it.isNotBlank() }?.let { prefs.setOverride(s.id, it) }
            val existing = current.indexOfFirst { it.id == s.id }
            if (existing >= 0) {
                if (s.favorite && !current[existing].favorite) current[existing] = current[existing].copy(favorite = true)
            } else {
                current.add(s); added++
            }
        }
        set(current)
        return added
    }

    // ---------------------------------------------------------------- storage

    private fun load(): List<Station> {
        val prefs = appContext.getSharedPreferences("radio", Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, null) ?: return BuiltInStations.all
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> fromJson(arr.getJSONObject(i)) }
        }.getOrElse { BuiltInStations.all }
    }

    private fun save(list: List<Station>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        appContext.getSharedPreferences("radio", Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(s: Station) = JSONObject().apply {
        put("id", s.id)
        put("builtIn", s.builtIn)
        put("name", s.name)
        put("subtitle", s.subtitle)
        s.streamUrl?.let { put("streamUrl", it) }
        s.radioBrowserUuid?.let { put("uuid", it) }
        put("artKind", s.artKind)
        put("favorite", s.favorite)
        if (!s.builtIn) {
            put("tags", JSONArray(s.tags))
            put("state", s.state)
            s.lat?.let { put("lat", it) }
            s.lon?.let { put("lon", it) }
        }
    }

    private fun fromJson(o: JSONObject): Station? {
        val id = o.optString("id").ifBlank { return null }
        if (o.optBoolean("builtIn")) {
            val base = BuiltInStations.byId(id) ?: return null
            return base.copy(
                name = o.optString("name").ifBlank { base.name },
                subtitle = o.optString("subtitle", base.subtitle),
                favorite = o.optBoolean("favorite"),
            )
        }
        val name = o.optString("name").ifBlank { return null }
        return Station(
            id = id,
            name = name,
            subtitle = o.optString("subtitle"),
            streamUrl = o.optString("streamUrl").ifBlank { null },
            radioBrowserUuid = o.optString("uuid").ifBlank { null },
            artKind = o.optString("artKind").ifBlank { "tile" },
            matchWords = listOf(name),
            tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            state = o.optString("state"),
            lat = if (o.has("lat")) o.optDouble("lat") else null,
            lon = if (o.has("lon")) o.optDouble("lon") else null,
            favorite = o.optBoolean("favorite"),
        )
    }
}
