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

    private fun set(list: List<Station>) {
        _stations.value = list
        save(list)
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
    }

    private fun fromJson(o: JSONObject): Station? {
        val id = o.optString("id").ifBlank { return null }
        if (o.optBoolean("builtIn")) {
            val base = BuiltInStations.byId(id) ?: return null
            return base.copy(
                name = o.optString("name").ifBlank { base.name },
                subtitle = o.optString("subtitle", base.subtitle),
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
        )
    }
}
