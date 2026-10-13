package com.utahradio.app

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(onBack: () -> Unit, onClearHistory: () -> Unit) {
    val context = LocalContext.current
    val mobile by DataUsage.mobileBytes.collectAsState()
    val wifi by DataUsage.wifiBytes.collectAsState()
    var place by remember { mutableStateOf(Directory.savedPlace(context)) }

    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(StationRepository.exportJson(context).toByteArray()) }
        }.onSuccess { Toast.makeText(context, "Stations backed up", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "Couldn't save the backup", Toast.LENGTH_LONG).show() }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: error("empty")
            StationRepository.importJson(context, text)
        }.onSuccess { n -> Toast.makeText(context, if (n == 0) "No new stations in that backup" else "Restored $n station${if (n == 1) "" else "s"}", Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(context, "That file isn't a Utah Radio backup", Toast.LENGTH_LONG).show() }
    }

    Box(Modifier.fillMaxSize().background(BackgroundBrush)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextMain)
                }
                Text("Settings", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Section("Appearance") {
                        Text("Theme", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Row(Modifier.padding(top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Choice("Dark", AppSettings.theme == AppSettings.THEME_DARK) { AppSettings.updateTheme(AppSettings.THEME_DARK) }
                            Choice("Light", AppSettings.theme == AppSettings.THEME_LIGHT) { AppSettings.updateTheme(AppSettings.THEME_LIGHT) }
                            Choice("Match phone", AppSettings.theme == AppSettings.THEME_SYSTEM) { AppSettings.updateTheme(AppSettings.THEME_SYSTEM) }
                        }
                        Toggle("Larger text", "Easier to read at a glance.", AppSettings.largeText) { AppSettings.updateLargeText(it) }
                    }
                }
                item {
                    Section("Playback") {
                        Toggle(
                            "Play when the app opens",
                            "Starts your last station automatically.",
                            AppSettings.autoplayOnOpen,
                        ) { AppSettings.updateAutoplay(it) }
                        Toggle(
                            "Data saver on mobile data",
                            "Uses a lower quality when a station offers one. (Most stations have a single quality.)",
                            AppSettings.dataSaver,
                        ) { AppSettings.updateDataSaver(it) }
                        Text("Data used this month", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 10.dp))
                        Text("Mobile data: ${DataUsage.format(mobile)}  ·  Wi-Fi: ${DataUsage.format(wifi)}",
                            color = TextDim, fontSize = 13.sp)
                        Text("Radio uses roughly 20–60 MB per hour.", color = TextFaint, fontSize = 12.sp)
                    }
                }
                item {
                    Section("Location") {
                        Text(
                            place?.let { "Finding stations near ${it.label}." } ?: "Not set. Used only to list nearby stations first.",
                            color = TextDim, fontSize = 13.sp,
                        )
                        if (place != null) {
                            Action("Forget my location") {
                                Directory.clearPlace(context); place = null
                                Toast.makeText(context, "Location forgotten", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                item {
                    Section("Your stations") {
                        Action("Back up stations to a file", "Save to your phone or Google Drive.") {
                            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                            backup.launch("utah-radio-stations-$day.json")
                        }
                        Action("Restore from a backup", "Adds the stations from a backup file.") {
                            restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        }
                        Action("Find all streams again", "If stations stop playing, this looks up fresh addresses.") {
                            val prefs = StationPrefs(context)
                            StationRepository.all(context).forEach { prefs.setCached(it.id, null) }
                            Toast.makeText(context, "Done — streams will be looked up again", Toast.LENGTH_SHORT).show()
                        }
                        Action("Clear recently played") {
                            onClearHistory()
                            Toast.makeText(context, "Cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                item {
                    Section("About") {
                        Text("Utah Radio ${BuildConfigInfo.version(context)}", color = TextMain, fontSize = 15.sp)
                        Text(
                            "Station listings from radio-browser.info. Album covers from the iTunes Search API. " +
                                "Your stations, location and history stay on your phone.",
                            color = TextFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                        )
                        Action("Report a problem", "Opens a message with details about your phone and the app.") {
                            val crash = CrashLog.pending(context)
                            val report = buildString {
                                appendLine("Describe what happened:")
                                appendLine()
                                appendLine()
                                appendLine("---")
                                appendLine("Utah Radio ${BuildConfigInfo.version(context)}")
                                appendLine(BuildConfigInfo.device())
                                appendLine("Stations: ${StationRepository.all(context).joinToString { it.name }}")
                                appendLine("Online: ${NetworkMonitor.online.value}, mobile data: ${NetworkMonitor.cellular.value}")
                                if (crash != null) { appendLine(); appendLine(crash) }
                            }
                            shareText(context, "Utah Radio problem report", report)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title.uppercase(), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Surface(shape = RoundedCornerShape(18.dp), color = CardColor, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) { content() }
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent),
        )
    }
}

@Composable
private fun Action(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
    ) {
        Text(title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (subtitle != null) Text(subtitle, color = TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
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
