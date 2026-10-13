package com.utahradio.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

private val Accent = Color(0xFFFFB547)
private val Bg1 = Color(0xFF14121F)
private val Bg2 = Color(0xFF231B3A)
private val CardColor = Color(0xFF2A2440)

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationPermission()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
                RadioScreen(controller)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({ controller = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(this))
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
private class PlayerUi {
    var currentId by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(false)
    var hasError by mutableStateOf(false)
    var requestedId by mutableStateOf<String?>(null)

    fun read(p: Player) {
        currentId = p.currentMediaItem?.mediaId
        isPlaying = p.isPlaying
        isBuffering = p.playWhenReady && p.playbackState == Player.STATE_BUFFERING
        hasError = p.playerError != null
        if (requestedId != null && requestedId == currentId && (isPlaying || hasError)) requestedId = null
    }
}

private fun playStation(player: Player, station: Station) {
    player.setMediaItem(MediaItem.Builder().setMediaId(station.id).build())
    player.prepare()
    player.play()
}

@Composable
private fun RadioScreen(player: Player?) {
    val ui = remember { PlayerUi() }
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = ui.read(p)
        }
        player?.let { ui.read(it); it.addListener(listener) }
        onDispose { player?.removeListener(listener) }
    }

    fun onStationTapped(station: Station) {
        val p = player ?: return
        if (ui.currentId == station.id && (ui.isPlaying || ui.isBuffering)) {
            p.stop()
        } else {
            ui.requestedId = station.id
            ui.hasError = false
            playStation(p, station)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Bg2, Bg1)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Utah Radio", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Your stations, live", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White.copy(alpha = 0.8f))
                }
            }

            Spacer(Modifier.height(24.dp))

            Stations.all.forEach { station ->
                val isCurrent = ui.currentId == station.id
                val connecting = ui.requestedId == station.id ||
                    (isCurrent && ui.isBuffering)
                val status = when {
                    connecting -> "Connecting…"
                    isCurrent && ui.isPlaying -> "Playing now"
                    isCurrent && ui.hasError -> "Can't connect — check internet or Settings"
                    else -> "Tap to listen"
                }
                StationCard(
                    station = station,
                    selected = isCurrent && (ui.isPlaying || connecting),
                    connecting = connecting,
                    status = status,
                    isError = isCurrent && ui.hasError && !connecting,
                    onClick = { onStationTapped(station) },
                )
                Spacer(Modifier.height(16.dp))
            }

            Spacer(Modifier.weight(1f))

            // Big play / stop button
            val playing = ui.isPlaying || ui.isBuffering || ui.requestedId != null
            Box(Modifier.fillMaxWidth().padding(bottom = 28.dp), contentAlignment = Alignment.Center) {
                Surface(
                    shape = CircleShape,
                    color = Accent,
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .clickable(enabled = player != null) {
                            val p = player ?: return@clickable
                            if (playing) {
                                p.stop(); ui.requestedId = null
                            } else {
                                val station = Stations.byId(ui.currentId)
                                    ?: Stations.byId(StationPrefs(context).lastStationId)
                                    ?: Stations.all.first()
                                ui.requestedId = station.id
                                playStation(p, station)
                            }
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (playing) {
                            Box(Modifier.size(28.dp).background(Bg1, RoundedCornerShape(4.dp)))
                        } else {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Bg1, modifier = Modifier.size(48.dp))
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onSaved = {
                showSettings = false
                // Re-tune the current station so a new address takes effect right away.
                val p = player
                val current = Stations.byId(ui.currentId)
                if (p != null && current != null && (ui.isPlaying || ui.isBuffering)) playStation(p, current)
            },
        )
    }
}

@Composable
private fun StationCard(
    station: Station,
    selected: Boolean,
    connecting: Boolean,
    status: String,
    isError: Boolean,
    onClick: () -> Unit,
) {
    val border by animateColorAsState(if (selected) Accent else Color.Transparent, label = "border")
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = CardColor,
        border = BorderStroke(2.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(station.artRes),
                contentDescription = null,
                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(station.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(station.subtitle, color = Color.White.copy(alpha = 0.65f), fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    status,
                    color = when {
                        isError -> Color(0xFFFF7A7A)
                        selected -> Accent
                        else -> Color.White.copy(alpha = 0.45f)
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (connecting) {
                CircularProgressIndicator(Modifier.size(26.dp), color = Accent, strokeWidth = 3.dp)
            }
        }
    }
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { StationPrefs(context) }
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            Stations.all.forEach { put(it.id, prefs.override(it.id) ?: "") }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stream addresses") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Leave these blank and the app finds each station's stream for you. " +
                        "If a station stops working, you can paste its stream address here.",
                    fontSize = 14.sp,
                )
                Stations.all.forEach { s ->
                    OutlinedTextField(
                        value = values[s.id] ?: "",
                        onValueChange = { values[s.id] = it },
                        label = { Text(s.name) },
                        placeholder = { Text("Automatic") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    prefs.cached(s.id)?.let {
                        Text("Currently using: $it", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Stations.all.forEach { s ->
                    prefs.setOverride(s.id, values[s.id])
                    prefs.setCached(s.id, null) // look the stream up fresh next time
                }
                onSaved()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
