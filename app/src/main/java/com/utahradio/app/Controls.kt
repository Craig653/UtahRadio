package com.utahradio.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/** Stop on purpose (so the app doesn't try to reconnect). */
fun stopPlayback(player: Player, ui: PlayerUi) {
    player.pause()
    player.stop()
    ui.requestedId = null
}

fun neighborOf(stations: List<Station>, id: String?, delta: Int): Station? {
    if (stations.isEmpty()) return null
    val i = stations.indexOfFirst { it.id == id }
    return stations[if (i < 0) 0 else Math.floorMod(i + delta, stations.size)]
}

/** The station to show: playing / starting, or your last one ready to play. */
@Composable
fun shownStation(ui: PlayerUi, stations: List<Station>): Station? {
    val context = LocalContext.current
    val id = ui.requestedId ?: ui.currentId
    return stations.firstOrNull { it.id == id }
        ?: findStation(context, id)
        ?: stations.firstOrNull { it.id == StationPrefs(context).lastStationId }
}

/** Current time, updated every second. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(1000L - now % 1000L) }
    }
    return now
}

fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Short status ("● LIVE", "RECONNECTING…") plus an explanation when something's wrong. */
data class Status(val label: String, val color: Color, val message: String? = null)

@Composable
fun playerStatus(ui: PlayerUi, station: Station?): Status {
    val online by NetworkMonitor.online.collectAsState()
    val reconnecting by PlaybackStatus.reconnecting.collectAsState()
    val connecting = ui.requestedId != null || ui.isBuffering
    val isCurrent = station != null && (station.id == ui.currentId || station.id == ui.requestedId)
    return when {
        station == null -> Status("NOTHING PLAYING", TextDim)
        !online && (reconnecting || connecting || (isCurrent && ui.hasError)) ->
            Status("OFFLINE", Danger, "No internet connection. The radio will pick back up when you're online again.")
        reconnecting -> Status("RECONNECTING…", Accent, "Lost the stream — reconnecting. No need to do anything.")
        connecting -> Status("CONNECTING…", TextDim)
        ui.isPlaying -> Status("● LIVE", Accent)
        isCurrent && ui.hasError -> Status("CAN'T CONNECT", Danger,
            "This station isn't responding. Try again in a bit, or hold the station and paste its stream address.")
        !isCurrent -> Status("READY", TextDim)
        else -> Status("STOPPED", TextDim)
    }
}

@Composable
fun StatusPill(status: Status) {
    Text(
        status.label,
        color = status.color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(status.color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
fun RoundButton(
    size: Dp,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) color else color.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Previous station · play/stop · next station. */
@Composable
fun PlayControls(player: Player?, ui: PlayerUi, stations: List<Station>, station: Station?, big: Boolean = false) {
    val reconnecting by PlaybackStatus.reconnecting.collectAsState()
    val connecting = ui.requestedId != null || ui.isBuffering
    val isCurrent = station != null && (station.id == ui.currentId || station.id == ui.requestedId)
    val active = isCurrent && (ui.isActive || reconnecting)
    val side = if (big) 60.dp else 52.dp
    val main = if (big) 88.dp else 76.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(side, CardColor2, player != null && stations.size > 1, onClick = {
            val p = player ?: return@RoundButton
            neighborOf(stations, station?.id, -1)?.let { playStation(p, ui, it) }
        }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous station", tint = TextMain, modifier = Modifier.size(32.dp))
        }
        RoundButton(main, Accent, player != null && (station != null || stations.isNotEmpty()), onClick = {
            val p = player ?: return@RoundButton
            if (active) stopPlayback(p, ui)
            else (station ?: stations.firstOrNull())?.let { playStation(p, ui, it) }
        }) {
            when {
                active && (connecting || reconnecting) ->
                    CircularProgressIndicator(Modifier.size(30.dp), color = OnAccent, strokeWidth = 3.dp)
                active -> Box(Modifier.size(if (big) 28.dp else 24.dp).background(OnAccent, RoundedCornerShape(4.dp)))
                else -> Icon(Icons.Filled.PlayArrow, "Play", tint = OnAccent, modifier = Modifier.size(if (big) 52.dp else 44.dp))
            }
        }
        RoundButton(side, CardColor2, player != null && stations.size > 1, onClick = {
            val p = player ?: return@RoundButton
            neighborOf(stations, station?.id, 1)?.let { playStation(p, ui, it) }
        }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next station", tint = TextMain, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
fun FavoriteButton(station: Station, size: Dp = 24.dp) {
    val context = LocalContext.current
    IconButton(onClick = { StationRepository.toggleFavorite(context, station.id) }) {
        Icon(
            if (station.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (station.favorite) "Remove from favorites" else "Add to favorites",
            tint = if (station.favorite) Accent else TextDim,
            modifier = Modifier.size(size),
        )
    }
}

/** "Sleep" button: shows time left, opens the timer choices. */
@Composable
fun SleepButton() {
    val timer by SleepTimer.state.collectAsState()
    val now = rememberNow()
    var open by remember { mutableStateOf(false) }
    val label = when {
        timer.endOfSong -> "Sleep: end of song"
        timer.endsAt > 0 -> "Sleep in " + formatElapsed(timer.endsAt - now)
        else -> "Sleep timer"
    }
    TextButton(onClick = { open = true }) {
        Text(label, color = if (timer.active) Accent else TextDim, fontSize = 13.sp)
    }
    if (open) SleepDialog(onDismiss = { open = false })
}

@Composable
fun SleepDialog(onDismiss: () -> Unit) {
    val timer by SleepTimer.state.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                Text("The radio fades out and stops.", fontSize = 13.sp, color = TextDim)
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    TextButton(onClick = { SleepTimer.setMinutes(m); onDismiss() }) {
                        Text(if (m < 60) "$m minutes" else if (m == 60) "1 hour" else "1½ hours")
                    }
                }
                TextButton(onClick = { SleepTimer.setEndOfSong(); onDismiss() }) {
                    Text("At the end of this song")
                }
                Text("(Only for stations that show song names.)", fontSize = 11.sp, color = TextFaint,
                    modifier = Modifier.padding(start = 12.dp))
            }
        },
        confirmButton = {
            if (timer.active) TextButton(onClick = { SleepTimer.cancel(); onDismiss() }) { Text("Turn off timer", color = Danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
