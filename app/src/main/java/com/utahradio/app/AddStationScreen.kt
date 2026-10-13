package com.utahradio.app

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Find a station in the free Radio Browser directory, or paste a stream address. */
@Composable
fun AddStationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val stations by StationRepository.stations.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StreamResolver.DirectoryStation>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var addingId by remember { mutableStateOf<String?>(null) }

    var manualName by rememberSaveable { mutableStateOf("") }
    var manualUrl by rememberSaveable { mutableStateOf("") }

    fun search() {
        if (query.isBlank()) return
        focus.clearFocus()
        searching = true
        scope.launch {
            results = runCatching { StreamResolver.searchDirectory(query) }.getOrDefault(emptyList())
            searching = false
        }
    }

    fun idFor(d: StreamResolver.DirectoryStation) =
        "rb-" + d.uuid.ifBlank { d.url.hashCode().toUInt().toString() }.lowercase()

    fun addFromDirectory(d: StreamResolver.DirectoryStation) {
        val id = idFor(d)
        addingId = id
        scope.launch {
            val gotLogo = withContext(Dispatchers.IO) { ArtStore.downloadLogo(context, id, d.favicon) }
            StationRepository.add(
                context,
                Station(
                    id = id,
                    name = d.name.take(40).trim(),
                    subtitle = d.place,
                    streamUrl = d.url,
                    radioBrowserUuid = d.uuid.ifBlank { null },
                    artKind = if (gotLogo) "logo" else "tile",
                    matchWords = listOf(d.name),
                ),
            )
            addingId = null
            Toast.makeText(context, "Added ${d.name}", Toast.LENGTH_SHORT).show()
        }
    }

    fun addManual() {
        val name = manualName.trim()
        val url = manualUrl.trim()
        if (name.isBlank()) {
            Toast.makeText(context, "Type a name for the station", Toast.LENGTH_SHORT).show(); return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(context, "The address should start with http:// or https://", Toast.LENGTH_LONG).show(); return
        }
        StationRepository.add(
            context,
            Station(id = "c-${System.currentTimeMillis()}", name = name, streamUrl = url, matchWords = listOf(name)),
        )
        manualName = ""; manualUrl = ""
        focus.clearFocus()
        Toast.makeText(context, "Added $name", Toast.LENGTH_SHORT).show()
    }

    Box(Modifier.fillMaxSize().background(BackgroundBrush)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text("Add a station", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search stations") },
                        placeholder = { Text("Name, call letters or frequency") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { search() }) { Icon(Icons.Filled.Search, "Search") }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Examples: KSL, \"Mix 105\", X96, KSFI, 98.7. Results come from radio-browser.info, a free station directory.",
                        color = TextFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }

                if (searching) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Accent)
                        }
                    }
                } else {
                    val list = results
                    if (list != null && list.isEmpty()) {
                        item {
                            Text("No stations found. Try a shorter name, or the call letters (like KSL).",
                                color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                    if (list != null) {
                        items(list, key = { it.uuid + it.url }) { d ->
                            val id = idFor(d)
                            ResultRow(
                                d = d,
                                added = stations.any { it.id == id },
                                adding = addingId == id,
                                onAdd = { addFromDirectory(d) },
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    Text("Have a stream address?", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "If a station isn't in the search, paste its stream link (often ends in .mp3, .aac, .m3u8 or .pls).",
                        color = TextFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = manualName, onValueChange = { manualName = it },
                        label = { Text("Station name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualUrl, onValueChange = { manualUrl = it },
                        label = { Text("Stream address") }, placeholder = { Text("https://…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addManual() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { addManual() },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg1),
                    ) { Text("Add station", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(d: StreamResolver.DirectoryStation, added: Boolean, adding: Boolean, onAdd: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = CardColor, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(CardColor2),
                contentAlignment = Alignment.Center,
            ) {
                Text(ArtStore.shortLabel(d.name).take(3), color = Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = listOf(d.place, d.details).filter { it.isNotBlank() }.joinToString(" · ")
                if (line.isNotBlank()) {
                    Text(line, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                added -> Icon(Icons.Filled.Check, contentDescription = "Added", tint = Accent)
                adding -> CircularProgressIndicator(Modifier.size(22.dp), color = Accent, strokeWidth = 2.dp)
                else -> Button(
                    onClick = onAdd,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg1),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                ) { Text("Add") }
            }
        }
    }
}
