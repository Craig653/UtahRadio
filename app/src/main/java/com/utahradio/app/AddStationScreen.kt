package com.utahradio.app

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.launch

/** What the results list is showing. */
private sealed interface FindMode {
    data class Nearby(val genreTag: String? = null) : FindMode
    data class Search(val text: String) : FindMode
    data class Similar(val name: String, val tags: List<String>) : FindMode
}

/**
 * Find stations: nearby first (by your location), filter by genre, search by name,
 * "Similar" to any station, or paste a stream address.
 */
@Composable
fun AddStationScreen(onBack: () -> Unit, similarTo: Station? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val myStations by StationRepository.stations.collectAsState()

    var place by remember { mutableStateOf(Directory.savedPlace(context)) }
    var locating by remember { mutableStateOf(false) }
    var stateInput by rememberSaveable { mutableStateOf("") }

    var mode by remember {
        mutableStateOf<FindMode>(
            if (similarTo != null) FindMode.Similar(similarTo.name, similarTo.tags) else FindMode.Nearby()
        )
    }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DirStation>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var addingId by remember { mutableStateOf<String?>(null) }

    var manualName by rememberSaveable { mutableStateOf("") }
    var manualUrl by rememberSaveable { mutableStateOf("") }

    // Back goes from search/similar results to "Near me", then out.
    BackHandler(enabled = mode != FindMode.Nearby() && similarTo == null) {
        mode = FindMode.Nearby(); query = ""
    }

    // Station added by paste or older version may have no genre yet: look it up.
    LaunchedEffect(similarTo?.id) {
        val s = similarTo ?: return@LaunchedEffect
        if (s.tags.isEmpty() && s.radioBrowserUuid != null) {
            Directory.lookup(s.radioBrowserUuid)?.let { d ->
                if (d.tags.isNotEmpty()) {
                    StationRepository.update(context, s.copy(tags = d.tags, state = d.state, lat = d.lat, lon = d.lon))
                    mode = FindMode.Similar(s.name, d.tags)
                }
            }
        }
    }

    // Load results whenever the mode or location changes.
    LaunchedEffect(mode, place) {
        val m = mode
        val p = place
        if (m is FindMode.Nearby && p == null) { results = null; return@LaunchedEffect }
        loading = true
        results = runCatching {
            when (m) {
                is FindMode.Nearby -> Directory.nearby(p!!, m.genreTag)
                is FindMode.Search -> Directory.search(m.text, p)
                is FindMode.Similar -> Directory.similar(m.tags, m.name, p)
            }
        }.getOrDefault(emptyList())
        loading = false
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            locating = true
            scope.launch {
                val found = Directory.locate(context)
                locating = false
                if (found != null) place = found
                else Toast.makeText(context, "Couldn't get your location. Type your state instead.", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "No problem — type your state instead.", Toast.LENGTH_SHORT).show()
        }
    }

    fun useMyLocation() = locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun useTypedState() {
        val st = stateInput.trim()
        if (st.isBlank()) return
        val p = Place(null, null, st.toTitle())
        Directory.savePlace(context, p)
        place = p
        focus.clearFocus()
    }

    fun search() {
        if (query.isBlank()) return
        focus.clearFocus()
        mode = FindMode.Search(query.trim())
    }

    fun add(d: DirStation) {
        addingId = StationRepository.idFor(d)
        scope.launch {
            StationRepository.addFromDirectory(context, d)
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
                IconButton(onClick = {
                    if (mode != FindMode.Nearby() && similarTo == null) { mode = FindMode.Nearby(); query = "" } else onBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextMain)
                }
                Text(
                    when (val m = mode) {
                        is FindMode.Similar -> "Like ${m.name}"
                        is FindMode.Search -> "Search results"
                        else -> "Find stations"
                    },
                    color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Search box
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search by name") },
                        placeholder = { Text("Name, call letters or frequency") },
                        singleLine = true,
                        trailingIcon = {
                            if (mode is FindMode.Search) {
                                IconButton(onClick = { query = ""; mode = FindMode.Nearby() }) { Icon(Icons.Filled.Clear, "Clear") }
                            } else {
                                IconButton(onClick = { search() }) { Icon(Icons.Filled.Search, "Search") }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Location + genre filters (Near me)
                val m = mode
                if (m is FindMode.Nearby) {
                    item {
                        val p = place
                        if (p == null) {
                            LocationCard(
                                locating = locating,
                                stateInput = stateInput,
                                onStateInput = { stateInput = it },
                                onUseLocation = { useMyLocation() },
                                onUseState = { useTypedState() },
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.LocationOn, null, tint = Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Near ${p.label}", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f))
                                if (locating) {
                                    CircularProgressIndicator(Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
                                } else {
                                    TextButton(onClick = { useMyLocation() }) { Text("Update", color = TextDim) }
                                    TextButton(onClick = { place = null }) { Text("Change", color = TextDim) }
                                }
                            }
                        }
                    }
                    if (place != null) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { Chip("All", m.genreTag == null) { mode = FindMode.Nearby(null) } }
                                items(GENRES) { (label, tag) ->
                                    Chip(label, m.genreTag == tag) { mode = FindMode.Nearby(tag) }
                                }
                            }
                        }
                    }
                }
                if (m is FindMode.Similar) {
                    item {
                        Text(
                            if (m.tags.isEmpty()) "We don't know this station's genre. Pick one below to see stations near you."
                            else "Stations playing ${m.tags.take(3).joinToString(", ") { it.toTitle() }} — nearby ones first.",
                            color = TextDim, fontSize = 13.sp,
                        )
                        if (m.tags.isEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(GENRES) { (label, tag) ->
                                    Chip(label, false) { mode = FindMode.Similar(m.name, listOf(tag)) }
                                }
                            }
                        }
                    }
                }

                // Results
                if (loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Accent)
                        }
                    }
                } else {
                    val list = results
                    if (list != null && list.isEmpty() && !(m is FindMode.Similar && m.tags.isEmpty())) {
                        item {
                            Text(
                                when (m) {
                                    is FindMode.Nearby -> "No stations found nearby${if (m.genreTag != null) " for that genre" else ""}. Try another genre or search by name."
                                    else -> "No stations found. Try a shorter name or the call letters (like KSL)."
                                },
                                color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                    if (list != null) {
                        items(list.take(150), key = { it.uuid + it.url }) { d ->
                            val id = StationRepository.idFor(d)
                            ResultRow(
                                d = d,
                                added = myStations.any { it.id == id },
                                adding = addingId == id,
                                onAdd = { add(d) },
                                onSimilar = if (d.tags.isNotEmpty()) ({ mode = FindMode.Similar(d.name, d.tags) }) else null,
                            )
                        }
                    }
                }

                if (m is FindMode.Nearby) {
                    item {
                        ManualAdd(
                            name = manualName, onName = { manualName = it },
                            url = manualUrl, onUrl = { manualUrl = it },
                            onAdd = { addManual() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationCard(
    locating: Boolean,
    stateInput: String,
    onStateInput: (String) -> Unit,
    onUseLocation: () -> Unit,
    onUseState: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = CardColor, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Stations near you", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Use your approximate location to list local stations first. It stays on your phone and is only used for finding stations.",
                color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(
                onClick = onUseLocation,
                enabled = !locating,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
            ) {
                if (locating) CircularProgressIndicator(Modifier.size(18.dp), color = OnAccent, strokeWidth = 2.dp)
                else Icon(Icons.Filled.LocationOn, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Use my location", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text("Or type your state:", color = TextDim, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = stateInput, onValueChange = onStateInput,
                    placeholder = { Text("e.g. Utah") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onUseState() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onUseState) { Text("Go", color = Accent) }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) Accent else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) Accent else TextMain.copy(alpha = 0.25f)),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = if (selected) OnAccent else TextMain,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun ResultRow(
    d: DirStation,
    added: Boolean,
    adding: Boolean,
    onAdd: () -> Unit,
    onSimilar: (() -> Unit)?,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = CardColor, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(CardColor2),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ArtStore.shortLabel(d.name).take(3), color = TextMain, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (d.genreLabel.isNotBlank()) {
                        Text(d.genreLabel, color = Accent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val miles = d.miles
                    val where = listOfNotNull(
                        miles?.let { if (it < 1) "Under 1 mi away" else "${it.toInt()} mi away" },
                        d.state.ifBlank { null } ?: d.country.ifBlank { null },
                        if (d.bitrate > 0) "${d.bitrate} kbps" else null,
                    ).joinToString(" · ")
                    if (where.isNotBlank()) {
                        Text(where, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                when {
                    added -> Icon(Icons.Filled.Check, contentDescription = "Added", tint = Accent)
                    adding -> CircularProgressIndicator(Modifier.size(22.dp), color = Accent, strokeWidth = 2.dp)
                    else -> Button(
                        onClick = onAdd,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    ) { Text("Add") }
                }
            }
            if (onSimilar != null) {
                TextButton(onClick = onSimilar, modifier = Modifier.align(Alignment.End)) {
                    Text("Similar stations", color = TextDim, fontSize = 13.sp)
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ManualAdd(name: String, onName: (String) -> Unit, url: String, onUrl: (String) -> Unit, onAdd: () -> Unit) {
    Column {
        Spacer(Modifier.height(16.dp))
        Text("Have a stream address?", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "If a station isn't listed, paste its stream link (often ends in .mp3, .aac, .m3u8 or .pls).",
            color = TextFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = name, onValueChange = onName,
            label = { Text("Station name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url, onValueChange = onUrl,
            label = { Text("Stream address") }, placeholder = { Text("https://…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAdd() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
        ) { Text("Add station", fontWeight = FontWeight.Bold) }
        Text(
            "Station listings come from radio-browser.info, a free community directory.",
            color = TextFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp),
        )
    }
}
