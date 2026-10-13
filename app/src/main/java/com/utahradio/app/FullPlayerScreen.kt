package com.utahradio.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Full-screen "Now Playing": big cover, background tinted to match it. */
@Composable
fun FullPlayerScreen(
    player: Player?,
    ui: PlayerUi,
    stations: List<Station>,
    onClose: () -> Unit,
    onFindSimilar: (Station) -> Unit,
) {
    val context = LocalContext.current
    val now = rememberNow()
    val station = shownStation(ui, stations)
    val isCurrent = station != null && station.id == ui.currentId
    val coverKey = if (isCurrent) ui.coverKey else null
    val status = playerStatus(ui, station)

    // Background color taken from the picture.
    var tint by remember { mutableStateOf(CardColor2) }
    LaunchedEffect(station?.id, station?.artKind, coverKey) {
        val c = withContext(Dispatchers.IO) {
            runCatching {
                val bmp: Bitmap? = coverKey?.let { CoverArt.file(context, it) }?.takeIf { it.exists() }
                    ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    ?: station?.let { ArtStore.bitmap(context, it) }
                bmp?.let { Bitmap.createScaledBitmap(it, 1, 1, true).getPixel(0, 0) }
            }.getOrNull()
        }
        if (c != null) tint = Color(c)
    }
    val top by animateColorAsState(
        lerp(tint, Bg1, if (CurrentPalette.dark) 0.45f else 0.7f),
        animationSpec = tween(700), label = "bg",
    )

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, Bg1)))) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close", tint = TextMain, modifier = androidx.compose.ui.Modifier)
                }
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)),
                    color = TextDim, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                if (station != null && StationRepository.contains(context, station.id)) FavoriteButton(station, 26.dp)
                else Spacer(Modifier.padding(24.dp))
            }

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f)) {
                if (station != null) {
                    if (coverKey != null) {
                        CoverImage(coverKey, Modifier.fillMaxSize(), corner = 24.dp) {
                            StationArt(station, modifier = Modifier.fillMaxSize(), corner = 24.dp)
                        }
                    } else {
                        StationArt(station, modifier = Modifier.fillMaxSize(), corner = 24.dp)
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            StatusPill(status)
            Spacer(Modifier.height(10.dp))
            Text(
                (if (isCurrent) ui.song else null) ?: "Live radio",
                color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 31.sp,
            )
            Text(
                (if (isCurrent) ui.artist else null) ?: station?.subtitle ?: "",
                color = TextDim, fontSize = 18.sp, textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(station?.name, station?.genreLabel?.ifBlank { null }).joinToString(" · "),
                color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            val since = if (ui.song != null) ui.songStartedAt else ui.listeningSince
            if (isCurrent && ui.isPlaying && since > 0) {
                Text(
                    (if (ui.song != null) "Song playing " else "Listening ") + formatElapsed(now - since),
                    color = TextFaint, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
            status.message?.let {
                Text(it, color = status.color, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp))
            }

            Spacer(Modifier.height(24.dp))
            PlayControls(player, ui, stations, station, big = true)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                SleepButton()
                if (station != null) {
                    TextButton(onClick = { onFindSimilar(station) }) {
                        Text("More like this", color = TextDim, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
