package com.utahradio.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Rename, change the stream address, reorder or remove a station. */
@Composable
fun EditStationDialog(station: Station, onDismiss: () -> Unit, onStreamChanged: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { StationPrefs(context) }
    val stations by StationRepository.stations.collectAsState()
    val index = stations.indexOfFirst { it.id == station.id }

    var name by remember { mutableStateOf(station.name) }
    var place by remember { mutableStateOf(station.subtitle) }
    val originalUrl = remember {
        if (station.builtIn) prefs.override(station.id) ?: "" else prefs.override(station.id) ?: station.streamUrl ?: ""
    }
    var url by remember { mutableStateOf(originalUrl) }
    var confirmRemove by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit station") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(place, { place = it }, label = { Text("City / description") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it },
                    label = { Text("Stream address") },
                    placeholder = { Text(if (station.builtIn) "Automatic" else "https://…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (station.builtIn) {
                    Text("Leave blank and the app finds this station's stream by itself.", fontSize = 12.sp, color = TextDim)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = index > 0, onClick = { StationRepository.move(context, station.id, -1) }) {
                        Text("Move up")
                    }
                    TextButton(enabled = index in 0 until stations.size - 1,
                        onClick = { StationRepository.move(context, station.id, 1) }) {
                        Text("Move down")
                    }
                }
                TextButton(onClick = {
                    if (confirmRemove) {
                        StationRepository.remove(context, station.id)
                        onDismiss()
                    } else {
                        confirmRemove = true
                    }
                }) {
                    Text(if (confirmRemove) "Tap again to remove ${station.name}" else "Remove station", color = Danger)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val newName = name.trim().ifBlank { station.name }
                val newUrl = url.trim()
                val latest = StationRepository.byId(context, station.id) ?: station
                var updated = latest.copy(name = newName, subtitle = place.trim())
                if (!station.builtIn) updated = updated.copy(matchWords = listOf(newName))
                if (newName != station.name) ArtStore.refreshTile(context, updated)

                val urlChanged = newUrl != originalUrl
                if (urlChanged) {
                    if (station.builtIn || newUrl.isBlank()) {
                        prefs.setOverride(station.id, newUrl.ifBlank { null })
                    } else {
                        prefs.setOverride(station.id, null)
                        updated = updated.copy(streamUrl = newUrl)
                    }
                    prefs.setCached(station.id, null)
                }
                StationRepository.update(context, updated)
                onDismiss()
                if (urlChanged) onStreamChanged()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
