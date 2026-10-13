package com.utahradio.app

import android.Manifest
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        StationRepository.init(this)
        askForNotificationPermission()
        setContent {
            val systemDark = isSystemInDarkTheme()
            val dark = when (AppSettings.theme) {
                AppSettings.THEME_LIGHT -> false
                AppSettings.THEME_SYSTEM -> systemDark
                else -> true
            }
            val palette = if (dark) DarkPalette else LightPalette
            SideEffect { CurrentPalette = palette }
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                )
            }
            val scheme = if (dark) {
                darkColorScheme(primary = palette.accent, surface = palette.card, background = palette.bg1)
            } else {
                lightColorScheme(primary = palette.accent, surface = palette.card, background = palette.bg1)
            }
            val density = LocalDensity.current
            val scaled = if (AppSettings.largeText) Density(density.density, density.fontScale * 1.18f) else density
            CompositionLocalProvider(LocalDensity provides scaled) {
                MaterialTheme(colorScheme = scheme) {
                    RadioApp(controller)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            { controller = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun onStop() {
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun askForNotificationPermission() {
        // Android 13+ needs permission to show the "now playing" notification.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

/** Live view of what the player is doing. */
class PlayerUi {
    var currentId by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(false)
    var hasError by mutableStateOf(false)
    var requestedId by mutableStateOf<String?>(null)
    var song by mutableStateOf<String?>(null)
    var artist by mutableStateOf<String?>(null)
    /** When the current song started (we saw it change), and when this station started playing. */
    var songStartedAt by mutableLongStateOf(0L)
    var listeningSince by mutableLongStateOf(0L)
    /** Album cover for the current song (null = show the station's picture). */
    var coverKey by mutableStateOf<String?>(null)
    private var lastPlayingId: String? = null

    val isActive get() = isPlaying || isBuffering || requestedId != null

    fun read(p: Player) {
        currentId = p.currentMediaItem?.mediaId
        isPlaying = p.isPlaying
        isBuffering = p.playWhenReady && p.playbackState == Player.STATE_BUFFERING
        hasError = p.playerError != null
        val md = p.mediaMetadata
        val hasSong = md.extras?.getBoolean(EXTRA_SONG) == true
        song = if (hasSong) md.title?.toString() else null
        artist = if (hasSong) md.artist?.toString() else null
        val now = System.currentTimeMillis()
        coverKey = md.extras?.getString(EXTRA_COVER)
        val s = song
        songStartedAt = if (s != null) SongClock.startedAt(currentId, CoverArt.key(artist, s)) else 0L
        val playingId = if (isPlaying) currentId else null
        if (playingId != lastPlayingId) {
            lastPlayingId = playingId
            if (playingId != null) listeningSince = now
        }
        if (requestedId != null && requestedId == currentId && (isPlaying || hasError)) requestedId = null
    }
}

fun playStation(player: Player, ui: PlayerUi, station: Station) {
    ui.requestedId = station.id
    ui.hasError = false
    player.setMediaItem(MediaItem.Builder().setMediaId(station.id).build())
    player.prepare()
    player.play()
}

private enum class Screen { Home, Add, Player, Settings, Onboarding }

@Composable
private fun RadioApp(player: Player?) {
    val context = LocalContext.current
    val ui = remember { PlayerUi() }
    val stations by StationRepository.stations.collectAsState()
    var screen by remember { mutableStateOf(if (AppSettings.onboarded) Screen.Home else Screen.Onboarding) }
    var similarTo by remember { mutableStateOf<Station?>(null) }
    var autoplayDone by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = ui.read(p)
        }
        player?.let { ui.read(it); it.addListener(listener) }
        onDispose { player?.removeListener(listener) }
    }

    // "Play when the app opens": start your last station once.
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        if (autoplayDone) return@LaunchedEffect
        autoplayDone = true
        if (AppSettings.autoplayOnOpen && AppSettings.onboarded && !p.playWhenReady) {
            val last = StationRepository.byId(context, StationPrefs(context).lastStationId) ?: stations.firstOrNull()
            last?.let { playStation(p, ui, it) }
        }
    }

    // Recently played songs (written by the player service).
    val prefs = remember { StationPrefs(context) }
    var history by remember { mutableStateOf(prefs.history()) }
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == StationPrefs.HISTORY || key == null) history = prefs.history()
        }
        prefs.prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { prefs.prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }

    val goHome = { screen = Screen.Home }
    val findSimilar: (Station) -> Unit = { similarTo = it; screen = Screen.Add }

    when (screen) {
        Screen.Onboarding -> OnboardingScreen(onDone = goHome)
        Screen.Home -> HomeScreen(
            player = player,
            ui = ui,
            stations = stations,
            history = history,
            onClearHistory = { prefs.clearHistory() },
            onAddStation = { similarTo = null; screen = Screen.Add },
            onFindSimilar = findSimilar,
            onOpenPlayer = { screen = Screen.Player },
            onOpenSettings = { screen = Screen.Settings },
        )
        Screen.Add -> {
            BackHandler(onBack = goHome)
            AddStationScreen(onBack = goHome, similarTo = similarTo)
        }
        Screen.Player -> {
            BackHandler(onBack = goHome)
            FullPlayerScreen(player, ui, stations, onClose = goHome, onFindSimilar = findSimilar)
        }
        Screen.Settings -> {
            BackHandler(onBack = goHome)
            SettingsScreen(onBack = goHome, onClearHistory = { prefs.clearHistory() })
        }
    }
}
