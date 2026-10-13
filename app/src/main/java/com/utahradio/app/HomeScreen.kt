package com.utahradio.app

import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

@Composable
fun HomeScreen(
    player: Player?,
    ui: PlayerUi,
    stations: List<Station>,
    history: List<PlayedSong>,
    onClearHistory: () -> Unit,
    onAddStation: () -> Unit,
    onFindSimilar: (Station) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var editing by remember { mutableStateOf<Station?>(null) }

    fun tap(station: Station) {
        val p = player ?: return
        if ((ui.currentId == station.id || ui.requestedId == station.id) && ui.isActive) {
            stopPlayback(p, ui)
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
                        Text("Utah Radio", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextMain)
                        Text(
                            "${stations.size} station${if (stations.size == 1) "" else "s"}",
                            color = TextDim, fontSize = 14.sp,
                        )
                    }
                    IconButton(onClick = onAddStation) {
                        Icon(Icons.Filled.Add, contentDescription = "Add station", tint = TextMain)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TextMain)
                    }
                }
            }

            item { CrashBanner() }

            item { NowPlayingCard(player, ui, stations, onFindSimilar, onOpenPlayer) }

            item {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text("My stations", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold,
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
                    Text("Recently played", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold,
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
                items(history.take(10)) { HistoryRow(it) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    editing?.let { s ->
        EditStationDialog(
            station = s,
            onDismiss = { editing = null },
            onFindSimilar = { editing = null; onFindSimilar(s) },
            onStreamChanged = {
                val p = player
                if (p != null && ui.currentId == s.id && ui.isActive) playStation(p, ui, s)
            },
        )
    }
}

/** Shown once after the app closed unexpectedly: offers to share the report. */
@Composable
private fun CrashBanner() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.pending(context)) }
    val text = report ?: return
    Surface(shape = RoundedCornerShape(16.dp), color = Danger.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Utah Radio closed unexpectedly last time", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Sending the report helps get it fixed. You choose where it goes (email, text…).",
                color = TextDim, fontSize = 13.sp)
            Row {
                TextButton(onClick = {
                    shareText(context, "Utah Radio problem report", text)
                    CrashLog.clear(context); report = null
                }) { Text("Send report", color = Accent) }
                TextButton(onClick = { CrashLog.clear(context); report = null }) { Text("Dismiss", color = TextDim) }
            }
        }
    }
}

fun shareText(context: android.content.Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun NowPlayingCard(
    player: Player?,
    ui: PlayerUi,
    stations: List<Station>,
    onFindSimilar: (Station) -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val now = rememberNow()
    val station = shownStation(ui, stations)
    val status = playerStatus(ui, station)
    val isCurrent = station != null && station.id == ui.currentId

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = CardColor,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable(enabled = station != null, onClick = onOpenPlayer),
    ) {
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
                NowArt(station, if (isCurrent) ui.coverKey else null, 112)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    StatusPill(status)
                    Spacer(Modifier.height(6.dp))
                    if (station == null) {
                        Text("Pick a station", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Tap one below to start listening", color = TextDim, fontSize = 14.sp)
                    } else {
                        Text(station.name, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (isCurrent) ui.song else null) ?: "Live radio",
                            color = TextMain, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 25.sp,
                        )
                        Text(
                            (if (isCurrent) ui.artist else null) ?: station.subtitle,
                            color = TextDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val since = if (ui.song != null) ui.songStartedAt else ui.listeningSince
                        if (isCurrent && ui.isPlaying && since > 0) {
                            Text(
                                (if (ui.song != null) "Song playing " else "Listening ") + formatElapsed(now - since),
                                color = TextFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
            status.message?.let {
                Text(it, color = status.color, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.height(14.dp))
            PlayControls(player, ui, stations, station)
            if (station != null) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SleepButton()
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onFindSimilar(station) }) {
                        Text("More like this", color = TextDim, fontSize = 13.sp)
                    }
                    if (StationRepository.contains(LocalContext.current, station.id)) FavoriteButton(station)
                }
            }
        }
    }
}

/** Album cover (with the station logo in the corner), or the station's picture. */
@Composable
fun NowArt(station: Station?, coverKey: String?, sizeDp: Int) {
    val size = sizeDp.dp
    when {
        station != null && coverKey != null -> Box(Modifier.size(size)) {
            CoverImage(coverKey, Modifier.size(size), corner = 18.dp) {
                StationArt(station, size = size, corner = 18.dp)
            }
            StationArt(
                station,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                size = (sizeDp * 0.27f).dp, corner = 8.dp,
            )
        }
        station != null -> StationArt(station, size = size, corner = 18.dp)
        else -> Box(
            Modifier.size(size).clip(RoundedCornerShape(18.dp)).background(CardColor2),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.PlayArrow, null, tint = TextFaint, modifier = Modifier.size(48.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationTile(station: Station, playing: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
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
            Box {
                StationArt(station, modifier = Modifier.fillMaxWidth().aspectRatio(1f), corner = 12.dp)
                // Heart: tap to make this a favorite (favorites stay at the top).
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable { StationRepository.toggleFavorite(context, station.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (station.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (station.favorite) "Remove from favorites" else "Add to favorites",
                        tint = if (station.favorite) Accent else Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(station.name, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    playing -> "Playing"
                    station.genreLabel.isNotBlank() -> station.genreLabel
                    else -> station.subtitle.ifBlank { " " }
                },
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
        border = BorderStroke(2.dp, TextMain.copy(alpha = 0.18f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, null, tint = Accent, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("Add station", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Nearby, genre or search", color = TextDim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun HistoryRow(song: PlayedSong) {
    val time = remember(song.time) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(song.time)) }
    val key = remember(song.title, song.artist) { CoverArt.key(song.artist.ifBlank { null }, song.title) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CoverImage(key, Modifier.size(46.dp), corner = 8.dp) {
            Text("♪", color = TextFaint, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
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
