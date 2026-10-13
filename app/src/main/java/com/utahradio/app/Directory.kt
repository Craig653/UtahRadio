package com.utahradio.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A station in the free Radio Browser directory (radio-browser.info). */
data class DirStation(
    val uuid: String,
    val name: String,
    val url: String,
    val favicon: String,
    val state: String,
    val country: String,
    val countryCode: String,
    val tags: List<String>,
    val codec: String,
    val bitrate: Int,
    val lat: Double?,
    val lon: Double?,
    val popularity: Int,
    /** Miles from you, when both locations are known. */
    val miles: Double? = null,
) {
    val genreLabel: String get() = tags.take(3).joinToString(" · ") { it.toTitle() }
}

/** Where you are: from the phone's location, or a state you typed. */
data class Place(val lat: Double?, val lon: Double?, val state: String?, val countryCode: String = "US") {
    val label: String get() = state ?: "your area"
}

fun String.toTitle(): String =
    split(" ").joinToString(" ") { w -> w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() } }

/** Common genres shown as quick filters. The value is the directory tag searched. */
val GENRES = listOf(
    "Country" to "country", "Pop" to "pop", "Rock" to "rock", "Classic Rock" to "classic rock",
    "Adult Contemporary" to "adult contemporary", "Hip Hop" to "hip hop", "Alternative" to "alternative",
    "Oldies" to "oldies", "Christian" to "christian", "News" to "news", "Talk" to "talk",
    "Sports" to "sports", "Jazz" to "jazz", "Classical" to "classical", "Spanish" to "spanish",
    "Public Radio" to "npr",
)

object Directory {
    private const val UA = "UtahRadio/1.4 (Android)"
    private val SERVERS = listOf(
        "https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
    )

    /** "Nearby" means within this many miles (anything farther is "semi-local" or beyond). */
    private const val LOCAL_MILES = 75.0
    private const val REGION_MILES = 250.0

    // ---------------------------------------------------------------- your location

    fun savedPlace(context: Context): Place? {
        val p = StationPrefs(context).prefs
        val state = p.getString("place_state", null)
        val lat = p.getString("place_lat", null)?.toDoubleOrNull()
        val lon = p.getString("place_lon", null)?.toDoubleOrNull()
        if (state == null && lat == null) return null
        return Place(lat, lon, state)
    }

    fun savePlace(context: Context, place: Place) {
        StationPrefs(context).prefs.edit()
            .putString("place_state", place.state)
            .putString("place_lat", place.lat?.toString())
            .putString("place_lon", place.lon?.toString())
            .apply()
    }

    /** Uses the phone's approximate location (needs location permission). */
    @SuppressLint("MissingPermission")
    suspend fun locate(context: Context): Place? = withContext(Dispatchers.IO) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var loc: Location? = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (loc == null && Build.VERSION.SDK_INT >= 30) {
            val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER
            else LocationManager.GPS_PROVIDER
            loc = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine { cont ->
                    runCatching {
                        lm.getCurrentLocation(provider, null, context.mainExecutor) { cont.resume(it) }
                    }.onFailure { cont.resume(null) }
                }
            }
        }
        val l = loc ?: return@withContext null
        val state = runCatching {
            @Suppress("DEPRECATION")
            if (Geocoder.isPresent()) Geocoder(context, Locale.US).getFromLocation(l.latitude, l.longitude, 1)
                ?.firstOrNull()?.adminArea else null
        }.getOrNull()
        Place(l.latitude, l.longitude, state).also { savePlace(context, it) }
    }

    // ---------------------------------------------------------------- searches (all ranked local-first)

    /** Stations near you, optionally only one genre. */
    suspend fun nearby(place: Place, genreTag: String? = null): List<DirStation> = withContext(Dispatchers.IO) {
        val tag = genreTag?.let { "&tag=" + enc(it) } ?: ""
        val found = LinkedHashMap<String, DirStation>()
        // Stations with a map location in the country (we measure the distance ourselves)…
        if (place.lat != null) {
            query("search?countrycode=${place.countryCode}&has_geo_info=true$tag&hidebroken=true&order=clickcount&reverse=true&limit=4000")
                .forEach { found[it.uuid] = it }
        }
        // …plus stations listed in your state without a map location.
        place.state?.let { st ->
            query("search?countrycode=${place.countryCode}&state=${enc(st)}$tag&hidebroken=true&order=clickcount&reverse=true&limit=600")
                .forEach { found.putIfAbsent(it.uuid, it) }
        }
        rank(found.values.toList(), place).filter { s ->
            val m = s.miles
            (m != null && m <= REGION_MILES) || (m == null && sameState(s, place))
        }
    }

    /** Search by name / call letters; nearby matches come first. */
    suspend fun search(text: String, place: Place?): List<DirStation> = withContext(Dispatchers.IO) {
        rank(query("search?name=${enc(text.trim())}&hidebroken=true&order=clickcount&reverse=true&limit=150"), place)
    }

    /** Stations playing the same kind of music, local ones first. */
    suspend fun similar(tags: List<String>, excludeName: String, place: Place?): List<DirStation> =
        withContext(Dispatchers.IO) {
            val mainTags = tags.map { it.lowercase().trim() }.filter { it.isNotBlank() }.take(2)
            if (mainTags.isEmpty()) return@withContext emptyList()
            val found = LinkedHashMap<String, DirStation>()
            for (t in mainTags) {
                val cc = place?.countryCode ?: "US"
                query("search?tag=${enc(t)}&countrycode=$cc&hidebroken=true&order=clickcount&reverse=true&limit=800")
                    .forEach { found.putIfAbsent(it.uuid, it) }
            }
            val wanted = tags.map { it.lowercase() }.toSet()
            rank(found.values.filter { !it.name.equals(excludeName, true) }, place) { s ->
                // More shared genres = better match.
                -s.tags.count { it.lowercase() in wanted }
            }
        }

    /** Fills in genre/location for a station you already have (used by "Similar"). */
    suspend fun lookup(uuid: String): DirStation? = withContext(Dispatchers.IO) {
        query("stations/byuuid/${enc(uuid)}").firstOrNull()
    }

    // ---------------------------------------------------------------- ranking

    private fun sameState(s: DirStation, place: Place?) =
        place?.state != null && s.state.contains(place.state, ignoreCase = true)

    /**
     * Local first: within 75 mi → your state / within 250 mi → everything else.
     * Inside each group: [extra] (e.g. genre match), then distance, then popularity.
     */
    private fun rank(list: List<DirStation>, place: Place?, extra: (DirStation) -> Int = { 0 }): List<DirStation> {
        val withMiles = list.map { s ->
            val miles = if (place?.lat != null && place.lon != null && s.lat != null && s.lon != null)
                milesBetween(place.lat, place.lon, s.lat, s.lon) else null
            s.copy(miles = miles)
        }
        fun tier(s: DirStation): Int {
            val m = s.miles
            return when {
                m != null && m <= LOCAL_MILES -> 0
                (m != null && m <= REGION_MILES) || (m == null && sameState(s, place)) -> 1
                else -> 2
            }
        }
        return withMiles.sortedWith(
            compareBy<DirStation> { tier(it) }
                .thenBy { extra(it) }
                .thenBy { if (tier(it) == 0) (it.miles ?: 0.0) / 25 else 0.0 } // close stations: nearer first
                .thenByDescending { it.popularity }
        )
    }

    fun milesBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 3958.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // ---------------------------------------------------------------- network

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun query(path: String): List<DirStation> {
        for (server in SERVERS) {
            val text = get("$server/json/$path") ?: continue
            val arr = runCatching { JSONArray(text) }.getOrNull() ?: continue
            val seen = HashSet<String>()
            return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parse) }
                .filter { seen.add(it.name.lowercase() + "|" + it.url) }
        }
        return emptyList()
    }

    private fun parse(o: JSONObject): DirStation? {
        val url = o.optString("url_resolved").ifBlank { o.optString("url") }
        val name = o.optString("name").trim()
        if (url.isBlank() || name.isBlank()) return null
        fun d(k: String) = if (o.isNull(k)) null else o.optDouble(k).takeIf { !it.isNaN() && it != 0.0 }
        return DirStation(
            uuid = o.optString("stationuuid"),
            name = name,
            url = url,
            favicon = o.optString("favicon"),
            state = o.optString("state").trim(),
            country = o.optString("country"),
            countryCode = o.optString("countrycode"),
            tags = o.optString("tags").split(",").map { it.trim() }.filter { it.length in 2..30 },
            codec = o.optString("codec").takeIf { it != "UNKNOWN" } ?: "",
            bitrate = o.optInt("bitrate"),
            lat = d("geo_lat"),
            lon = d("geo_long"),
            popularity = o.optInt("clickcount") + o.optInt("votes"),
        )
    }

    private fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode !in 200..299) null else conn.inputStream.use { String(it.readBytes()) }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
