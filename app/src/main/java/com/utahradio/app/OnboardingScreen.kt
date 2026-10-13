package com.utahradio.app

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** First-run setup: find where you are, pick a few local stations, done. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var place by remember { mutableStateOf(Directory.savedPlace(context)) }
    var locating by remember { mutableStateOf(false) }
    var stateInput by remember { mutableStateOf("") }
    var nearby by remember { mutableStateOf<List<DirStation>?>(null) }
    val picked = remember { mutableStateListOf<String>() }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(place) {
        val p = place ?: return@LaunchedEffect
        nearby = null
        nearby = runCatching { Directory.nearby(p) }.getOrDefault(emptyList()).take(30)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) return@rememberLauncherForActivityResult
        locating = true
        scope.launch {
            val found = Directory.locate(context)
            locating = false
            if (found != null) place = found
            else Toast.makeText(context, "Couldn't get your location — type your state instead.", Toast.LENGTH_LONG).show()
        }
    }

    fun finish() {
        val chosen = nearby.orEmpty().filter { StationRepository.idFor(it) in picked }
        if (chosen.isEmpty()) { AppSettings.finishOnboarding(); onDone(); return }
        adding = true
        scope.launch {
            chosen.forEach { runCatching { StationRepository.addFromDirectory(context, it) } }
            adding = false
            AppSettings.finishOnboarding()
            onDone()
        }
    }

    Box(Modifier.fillMaxSize().background(BackgroundBrush)) {
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Welcome to Utah Radio", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Live local radio on your phone and in your car. Let's find the stations near you.",
                    color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (place == null) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = CardColor, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Button(
                                onClick = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                                enabled = !locating,
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                            ) {
                                if (locating) CircularProgressIndicator(Modifier.size(18.dp), color = OnAccent, strokeWidth = 2.dp)
                                else Icon(Icons.Filled.LocationOn, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Use my location", fontWeight = FontWeight.Bold)
                            }
                            Text("Approximate only. It stays on your phone.", color = TextFaint, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp))
                            Spacer(Modifier.height(14.dp))
                            Text("Or type your state:", color = TextDim, fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = stateInput, onValueChange = { stateInput = it },
                                    placeholder = { Text("e.g. Utah") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = {
                                        if (stateInput.isNotBlank()) {
                                            val p = Place(null, null, stateInput.trim().toTitle())
                                            Directory.savePlace(context, p); place = p
                                        }
                                    }),
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    if (stateInput.isNotBlank()) {
                                        val p = Place(null, null, stateInput.trim().toTitle())
                                        Directory.savePlace(context, p); place = p
                                    }
                                }) { Text("Go", color = Accent) }
                            }
                        }
                    }
                }
            } else {
                item {
                    Text("Stations near ${place?.label}", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Pick the ones you like — you can add more anytime.", color = TextDim, fontSize = 13.sp)
                }
                val list = nearby
                if (list == null) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Accent)
                        }
                    }
                } else if (list.isEmpty()) {
                    item { Text("Couldn't find stations nearby. You can search for them later.", color = TextDim) }
                } else {
                    items(list, key = { it.uuid + it.url }) { d ->
                        val id = StationRepository.idFor(d)
                        val checked = id in picked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CardColor)
                                .clickable { if (checked) picked.remove(id) else picked.add(id) }
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { if (it) picked.add(id) else picked.remove(id) },
                                colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = OnAccent),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(d.name, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val line = listOfNotNull(
                                    d.genreLabel.ifBlank { null },
                                    d.miles?.let { "${it.toInt()} mi" },
                                ).joinToString(" · ")
                                if (line.isNotBlank()) Text(line, color = TextDim, fontSize = 12.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { finish() },
                    enabled = !adding,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (adding) CircularProgressIndicator(Modifier.size(18.dp), color = OnAccent, strokeWidth = 2.dp)
                    else Text(
                        if (picked.isEmpty()) "Start listening" else "Add ${picked.size} station${if (picked.size == 1) "" else "s"} and start",
                        fontWeight = FontWeight.Bold,
                    )
                }
                TextButton(onClick = { AppSettings.finishOnboarding(); onDone() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Skip for now", color = TextDim)
                }
            }
        }
    }
}
