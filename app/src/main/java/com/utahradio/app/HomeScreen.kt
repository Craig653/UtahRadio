package com.utahradio.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    player: Player?,
    ui: PlayerUi,
    stations: List<Station>,
    history: List<PlayedSong>,
    onClearHistory: () -> Unit,
    onAddStation: () -> Unit,
) {
    var editing by remember { mutableStateOf<Station?>(null) }

    fun tap(station: Station) {
        val p = player ?: return
        if ((ui.currentId == station.id || ui.requestedId == station.id) && ui.isActive) {
            p.stop(); ui.requestedId = null
        } else {
            playStation(p, ui, station)
        }
    }

    Box(Modifier.fillMaxSize().background(BackgroundBrush)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Utah Radio", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            "${stations.size} station${if (stations.size == 1) "" else "s"}",
                            color = TextDim, fontSize = 14.sp,
                        )
                    }
                    IconButton(onClick = onAddStation) {
                        Icon(Icons.Filled.Add, contentDescription = "Add station", tint = Color.White)
                    }
                }
            }

            item { NowPlayingCard(player, ui, stations) }

            item {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text("My stations", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Text("Hold a station to edit", color = TextFaint, fontSize = 12.sp)
                }
            }

            val cells: List<Station?> = stations + listOf(null) // null = "Add station" tile
            items(cells.chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { s ->
                        Box(Modifier.weight(1f)) {
                            if (s == null) {
                                AddTile(onAddStation)
                            } else {
                                val isCurrent = ui.currentId == s.id || ui.requestedId == s.id
                                StationTile(
                                    station = s,
                                    playing = isCurrent && ui.isActive,
                                    onClick = { tap(s) },
                                    onLongClick = { editing = s },
                                )
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            item {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recently played", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    if (history.isNotEmpty()) {
                        TextButton(onClick = onClearHistory) { Text("Clear", color = TextDim) }
                    }
                }
            }
            if (history.isEmpty()) {
                item {
                    Text(
                        "Songs show up here as you listen (for stations that send song info).",
                        color = TextFaint, fontSize = 14.sp,
                    )
                }
            } else {
                items(history.take(20)) { HistoryRow(it) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    editing?.let { s ->
        EditStationDialog(
            station = s,
            onDismiss = { editing = null },
            onStreamChanged = {
                val p = player
                if (p != null && ui.currentId == s.id && ui.isActive) playStation(p, ui, s)
            },
        )
    }
}

@Composable
private fun NowPlayingCard(player: Player?, ui: PlayerUi, stations: List<Station>) {
    val context = LocalContext.current
    // Ticks every second for the clock and the elapsed time.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(1000L - now % 1000L) }
    }
    val station = stations.firstOrNull { it.id == (ui.requestedId ?: ui.currentId) }
    val connecting = ui.requestedId != null || ui.isBuffering
    val active = ui.isActive

    fun neighbor(delta: Int): Station? {
        if (stations.isEmpty()) return null
        val i = stations.indexOfFirst { it.id == station?.id }
        val next = if (i < 0) 0 else Math.floorMod(i + delta, stations.size)
        return stations[next]
    }

    Surface(shape = RoundedCornerShape(24.dp), color = CardColor, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("NOW PLAYING", color = TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
                    color = TextDim, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (station != null) {
                    StationArt(station, size = 112.dp, corner = 18.dp)
                } else {
                    Box(Modifier.size(112.dp).clip(RoundedCornerShape(18.dp)).background(CardColor2),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlayArrow, null, tint = TextFaint, modifier = Modifier.size(48.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    StatusPill(
                        when {
                            station == null -> "NOTHING PLAYING"
                            connecting -> "CONNECTING…"
                            ui.isPlaying -> "● LIVE"
                            ui.hasError -> "CAN'T CONNECT"
                            else -> "STOPPED"
                        },
                        color = when {
                            ui.hasError && !connecting -> Danger
                            ui.isPlaying -> Accent
                            else -> TextDim
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    if (station == null) {
                        Text("Pick a station", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Tap one below to start listening", color = TextDim, fontSize = 14.sp)
                    } else {
                        Text(station.name, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            ui.song ?: "Live radio",
                            color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 25.sp,
                        )
                        Text(
                            ui.artist ?: station.subtitle,
                            color = TextDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val since = if (ui.song != null) ui.songStartedAt else ui.listeningSince
                        if (ui.isPlaying && since > 0) {
                            Text(
                                (if (ui.song != null) "Song playing " else "Listening ") + formatElapsed(now - since),
                                color = TextFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
            if (ui.hasError && !connecting && station != null) {
                Text(
                    "Check your internet connection. If this keeps happening, hold the station below and paste its stream address.",
                    color = Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(size = 52, color = CardColor2, enabled = player != null && stations.size > 1, onClick = {
                    val p = player ?: return@RoundButton
                    neighbor(-1)?.let { playStation(p, ui, it) }
                }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous station", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                RoundButton(size = 76, color = Accent, enabled = player != null, onClick = {
                    val p = player ?: return@RoundButton
                    if (active) {
                        p.stop(); ui.requestedId = null
                    } else {
                        val lastId = StationPrefs(context).lastStationId
                        val s = station ?: stations.firstOrNull { it.id == lastId } ?: stations.firstOrNull()
                        s?.let { playStation(p, ui, it) }
                    }
                }) {
                    when {
                        connecting -> CircularProgressIndicator(Modifier.size(30.dp), color = Bg1, strokeWidth = 3.dp)
                        active -> Box(Modifier.size(24.dp).background(Bg1, RoundedCornerShape(4.dp)))
                        else -> Icon(Icons.Filled.PlayArrow, "Play", tint = Bg1, modifier = Modifier.size(44.dp))
                    }
                }
                RoundButton(size = 52, color = CardColor2, enabled = player != null && stations.size > 1, onClick = {
                    val p = player ?: return@RoundButton
                    neighbor(1)?.let { playStation(p, ui, it) }
                }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next station", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
private fun RoundButton(
    size: Int,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (enabled) color else color.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationTile(station: Station, playing: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val border by animateColorAsState(if (playing) Accent else Color.Transparent, label = "border")
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CardColor,
        border = BorderStroke(2.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(Modifier.padding(10.dp)) {
            StationArt(station, modifier = Modifier.fillMaxWidth().aspectRatio(1f), corner = 12.dp)
            Spacer(Modifier.height(8.dp))
            Text(station.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (playing) "Playing" else station.subtitle.ifBlank { " " },
                color = if (playing) Accent else TextDim,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(2.dp, Color.White.copy(alpha = 0.18f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, null, tint = Accent, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("Add station", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Search or paste", color = TextDim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun HistoryRow(song: PlayedSong) {
    val time = remember(song.time) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(song.time)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(song.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(song.artist, song.station).filter { it.isNotBlank() }.joinToString(" · "),
                color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(time, color = TextFaint, fontSize = 12.sp)
    }
}
