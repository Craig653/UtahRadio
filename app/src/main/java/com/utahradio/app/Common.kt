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

val Accent = Color(0xFFFFB547)
val Bg1 = Color(0xFF14121F)
val Bg2 = Color(0xFF231B3A)
val CardColor = Color(0xFF2A2440)
val CardColor2 = Color(0xFF342C50)
val Danger = Color(0xFFFF7A7A)
val TextDim = Color.White.copy(alpha = 0.6f)
val TextFaint = Color.White.copy(alpha = 0.42f)

val BackgroundBrush = Brush.verticalGradient(listOf(Bg2, Bg1))

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
