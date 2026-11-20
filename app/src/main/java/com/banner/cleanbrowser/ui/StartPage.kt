package com.banner.cleanbrowser.ui

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.R
import com.banner.cleanbrowser.browser.Tabs
import com.banner.cleanbrowser.browser.Ui
import com.banner.cleanbrowser.core.Bookmark
import com.banner.cleanbrowser.core.Db
import com.banner.cleanbrowser.core.DeviceInfo
import com.banner.cleanbrowser.core.HomePage
import com.banner.cleanbrowser.core.Suggest
import com.banner.cleanbrowser.core.Urls
import com.banner.cleanbrowser.core.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

@Composable
fun StartPage(onOpen: (String) -> Unit) {
    val s = App.prefs.state
    val private = Tabs.current?.isPrivate == true
    if (s.homePage == HomePage.BLANK) {
        Box(Modifier.fillMaxSize())
        return
    }
    var version by remember { mutableIntStateOf(0) }
    var marks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var frequent by remember { mutableStateOf<List<Db.Frequent>>(emptyList()) }
    LaunchedEffect(version, s.hiddenSites, s.spBookmarks, s.spFrequent) {
        withContext(Dispatchers.IO) {
            marks = if (s.spBookmarks) App.db.bookmarks().take(16) else emptyList()
            frequent = if (s.spFrequent && !private) App.db.frequent(s.hiddenSites) else emptyList()
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        if (s.spLogo) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(64.dp))
            Spacer(Modifier.height(10.dp))
            Text(if (private) "Private tab" else "Clean Browser", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
        }
        if (s.spClock) { Clock(); Spacer(Modifier.height(16.dp)) }
        val cards = s.spWeather || s.spDevice
        if (cards) {
            Row(Modifier.fillMaxWidth().widthIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.spWeather) WeatherCard(Modifier.weight(1f))
                if (s.spDevice) DeviceCard(Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
        }
        if (marks.isNotEmpty()) {
            SectionLabel("Bookmarks")
            Tiles(marks.map { Tile(it.url, it.title, it.url) }, onOpen) { t, close ->
                DropdownMenuItem(text = { Text("Open in new tab") }, onClick = { close(); Tabs.open(t.url, private) })
                MenuDivider()
                DropdownMenuItem(text = { Text("Remove bookmark") }, onClick = {
                    close(); App.io { App.db.removeBookmark(t.key) }; marks = marks.filter { it.url != t.key }; Ui.toast("Bookmark removed")
                })
            }
            Spacer(Modifier.height(18.dp))
        }
        if (frequent.isNotEmpty()) {
            SectionLabel("Frequent sites")
            Tiles(frequent.map { Tile(it.url, it.title.ifBlank { it.host }, it.host) }, onOpen) { t, close ->
                DropdownMenuItem(text = { Text("Open in new tab") }, onClick = { close(); Tabs.open(t.url, private) })
                MenuDivider()
                DropdownMenuItem(text = { Text("Hide") }, onClick = {
                    close(); App.prefs.update { it.copy(hiddenSites = it.hiddenSites + t.key) }; Suggest.invalidate()
                })
                MenuDivider()
                DropdownMenuItem(text = { Text("Remove from history") }, onClick = {
                    close(); App.io { App.db.removeHistoryHost(t.key) }; frequent = frequent.filter { it.host != t.key }; Suggest.invalidate()
                })
            }
            Spacer(Modifier.height(18.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun Clock() {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L - now % 1000L)
        }
    }
    val time = DateFormat.getTimeFormat(ctx).format(Date(now))
    val date = DateFormat.format("EEEE, MMMM d", now).toString()
    Text(time, fontSize = 44.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface)
    Text(date, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Card(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(outlineStroke(), MaterialTheme.shapes.large)
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun WeatherCard(modifier: Modifier) {
    val s = App.prefs.state
    LaunchedEffect(s.weatherLat, s.weatherLon, s.weatherUnit) {
        Weather.loadCached()
        Weather.refresh()
    }
    Card(modifier.combinedClickableCompat(onClick = {}, onLongClick = { Nav.open(Screen.SETTINGS); Nav.settingsStack.add("start") })) {
        val w = Weather.now
        Column {
            when {
                s.weatherLat.isEmpty() -> {
                    Text("Weather", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text("Set your city", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.combinedClickableCompat(onClick = { Nav.open(Screen.SETTINGS); Nav.settingsStack.add("start") }))
                }
                w == null -> Text(Weather.error ?: "Loading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Weather.symbol(w.code, w.isDay), fontSize = 26.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("${w.temp}°", fontSize = 30.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(Weather.describe(w.code), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text("H ${w.high}°  L ${w.low}°", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(w.place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Open-Meteo", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(modifier: Modifier) {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            info = withContext(Dispatchers.IO) { runCatching { DeviceInfo.read(ctx) }.getOrNull() }
            delay(10_000)
        }
    }
    Card(modifier) {
        val d = info
        Column {
            Text(d?.model ?: "Device", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d != null) {
                Spacer(Modifier.height(4.dp))
                InfoLine("Battery", if (d.battery >= 0) "${d.battery}%" + if (d.charging) " ⚡" else "" else "—")
                InfoLine("Storage", Formatter.formatShortFileSize(ctx, d.storageFree) + " free")
                InfoLine("RAM", Formatter.formatShortFileSize(ctx, d.ramFree) + " free")
                InfoLine("Network", d.network)
                InfoLine("Android", d.android)
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

private data class Tile(val url: String, val title: String, val key: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tiles(tiles: List<Tile>, onOpen: (String) -> Unit, menu: @Composable (Tile, () -> Unit) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().widthIn(max = 560.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 4,
    ) {
        tiles.forEach { t ->
            var open by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                Column(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .combinedClickableCompat(onClick = { onOpen(t.url) }, onLongClick = { open = true })
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SiteIcon(t.url, 48.dp)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        t.title.ifBlank { Urls.display(t.url) }, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                CleanMenu(expanded = open, onDismissRequest = { open = false }) { menu(t) { open = false } }
            }
        }
        // Keep the last row aligned to the grid.
        repeat((4 - tiles.size % 4) % 4) { Spacer(Modifier.weight(1f)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
