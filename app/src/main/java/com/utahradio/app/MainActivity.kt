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
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, surface = CardColor)) {
                RadioApp(controller)
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
    private var lastSongKey: String? = null
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
        val songKey = "$currentId|$song|$artist"
        if (songKey != lastSongKey) { lastSongKey = songKey; songStartedAt = now }
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

private enum class Screen { Home, Add }

@Composable
private fun RadioApp(player: Player?) {
    val context = LocalContext.current
    val ui = remember { PlayerUi() }
    val stations by StationRepository.stations.collectAsState()
    var screen by remember { mutableStateOf(Screen.Home) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = ui.read(p)
        }
        player?.let { ui.read(it); it.addListener(listener) }
        onDispose { player?.removeListener(listener) }
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

    when (screen) {
        Screen.Home -> HomeScreen(
            player = player,
            ui = ui,
            stations = stations,
            history = history,
            onClearHistory = { prefs.clearHistory() },
            onAddStation = { screen = Screen.Add },
        )
        Screen.Add -> {
            BackHandler { screen = Screen.Home }
            AddStationScreen(onBack = { screen = Screen.Home })
        }
    }
}
