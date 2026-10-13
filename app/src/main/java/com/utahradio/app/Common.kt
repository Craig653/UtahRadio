package com.utahradio.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Colors for one theme. */
data class Palette(
    val dark: Boolean,
    val accent: Color,
    val onAccent: Color,
    val bg1: Color,
    val bg2: Color,
    val card: Color,
    val card2: Color,
    val text: Color,
    val danger: Color,
)

val DarkPalette = Palette(
    dark = true,
    accent = Color(0xFFFFB547), onAccent = Color(0xFF14121F),
    bg1 = Color(0xFF14121F), bg2 = Color(0xFF231B3A),
    card = Color(0xFF2A2440), card2 = Color(0xFF342C50),
    text = Color.White, danger = Color(0xFFFF7A7A),
)

val LightPalette = Palette(
    dark = false,
    accent = Color(0xFFE08E0B), onAccent = Color(0xFF1B1726),
    bg1 = Color(0xFFF3EEF9), bg2 = Color(0xFFFBF8FE),
    card = Color(0xFFFFFFFF), card2 = Color(0xFFEAE3F3),
    text = Color(0xFF1B1726), danger = Color(0xFFC62828),
)

/** The palette in use (switched from Settings; the whole UI follows it). */
var CurrentPalette by mutableStateOf(DarkPalette)

val Accent: Color get() = CurrentPalette.accent
val OnAccent: Color get() = CurrentPalette.onAccent
val Bg1: Color get() = CurrentPalette.bg1
val Bg2: Color get() = CurrentPalette.bg2
val CardColor: Color get() = CurrentPalette.card
val CardColor2: Color get() = CurrentPalette.card2
val Danger: Color get() = CurrentPalette.danger
val TextMain: Color get() = CurrentPalette.text
val TextDim: Color get() = CurrentPalette.text.copy(alpha = 0.62f)
val TextFaint: Color get() = CurrentPalette.text.copy(alpha = 0.45f)

val BackgroundBrush: Brush get() = Brush.verticalGradient(listOf(Bg2, Bg1))

/** The station's picture (built-in, logo, or generated tile). */
@Composable
fun StationArt(station: Station, modifier: Modifier = Modifier, size: Dp? = null, corner: Dp = 14.dp) {
    val context = LocalContext.current
    val key = station.id + "|" + station.name + "|" + station.artKind
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { ArtStore.bitmap(context, station)?.asImageBitmap() }.getOrNull()
        }
    }
    val sized = if (size != null) modifier.size(size) else modifier
    Box(
        sized.clip(RoundedCornerShape(corner)).background(CardColor2),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** Album cover saved by [CoverArt]; shows [placeholder] while loading or if there is none. */
@Composable
fun CoverImage(
    coverKey: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
    placeholder: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    var bitmap by remember(coverKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(coverKey) {
        if (coverKey == null) { bitmap = null; return@LaunchedEffect }
        // The cover may still be downloading; check again for a little while.
        repeat(8) {
            val b = withContext(Dispatchers.IO) {
                runCatching {
                    val f = CoverArt.file(context, coverKey)
                    if (f.exists()) android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
                }.getOrNull()
            }
            if (b != null) { bitmap = b; return@LaunchedEffect }
            kotlinx.coroutines.delay(2500)
        }
    }
    Box(modifier.clip(RoundedCornerShape(corner)).background(CardColor2), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = "Album cover", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            placeholder()
        }
    }
}
