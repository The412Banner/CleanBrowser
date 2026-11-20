package com.banner.cleanbrowser.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.browser.Ui
import com.banner.cleanbrowser.core.HomePage
import com.banner.cleanbrowser.core.Settings
import com.banner.cleanbrowser.core.Suggest
import com.banner.cleanbrowser.core.Weather
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun setStart(change: (Settings) -> Settings) = App.prefs.update(change)

@Composable
fun StartPageSettings() {
    val s = App.prefs.state
    val scope = rememberCoroutineScope()
    var cityDialog by remember { mutableStateOf(false) }
    var confirmUnhide by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        ChoiceRow(
            "Home page", s.homePage,
            listOf(HomePage.START to "Start page", HomePage.BLANK to "Blank", HomePage.ENGINE to "Search engine", HomePage.CUSTOM to "Custom"),
        ) { v -> setStart { it.copy(homePage = v) } }
        if (s.homePage == HomePage.CUSTOM) {
            TextRow("Home page address", s.homeUrl, "https://", androidx.compose.ui.text.input.KeyboardType.Uri) { v -> setStart { it.copy(homeUrl = v) } }
        }
        val on = s.homePage == HomePage.START
        SectionTitle("Sections")
        SwitchRow("Logo", s.spLogo, enabled = on) { v -> setStart { it.copy(spLogo = v) } }
        SwitchRow("Clock & date", s.spClock, enabled = on) { v -> setStart { it.copy(spClock = v) } }
        SwitchRow("Weather", s.spWeather, enabled = on) { v -> setStart { it.copy(spWeather = v) } }
        SwitchRow("Device info", s.spDevice, enabled = on) { v -> setStart { it.copy(spDevice = v) } }
        SwitchRow("Bookmarks", s.spBookmarks, enabled = on) { v -> setStart { it.copy(spBookmarks = v) } }
        SwitchRow("Frequent sites", s.spFrequent, enabled = on) { v -> setStart { it.copy(spFrequent = v) } }

        SectionTitle("Weather")
        RowItem("Location", value = s.weatherCity.ifEmpty { "Not set" }, icon = Icons.Filled.LocationOn, onClick = { cityDialog = true })
        ChoiceRow("Temperature", s.weatherUnit, listOf("auto" to "Automatic", "f" to "°F", "c" to "°C")) { v ->
            setStart { it.copy(weatherUnit = v) }
            scope.launch { Weather.refresh(force = true) }
        }
        RowItem("Refresh weather", icon = Icons.Filled.Refresh, enabled = s.weatherLat.isNotEmpty(), onClick = {
            scope.launch { Weather.refresh(force = true); Ui.toast(if (Weather.error == null) "Weather updated" else Weather.error!!) }
        })

        SectionTitle("Sites")
        RowItem("Hidden sites", value = "${s.hiddenSites.size}", enabled = s.hiddenSites.isNotEmpty(), onClick = { confirmUnhide = true })
        RowItem("Clear frequent sites", icon = Icons.Filled.Delete, onClick = {
            App.io { App.db.resetVisits() }; Suggest.invalidate(); Ui.toast("Frequent sites cleared")
        })
        RowItem("Reset start page", icon = Icons.Filled.Refresh, onClick = {
            val d = Settings()
            setStart {
                it.copy(
                    homePage = d.homePage, spLogo = d.spLogo, spClock = d.spClock, spWeather = d.spWeather, spDevice = d.spDevice,
                    spBookmarks = d.spBookmarks, spFrequent = d.spFrequent, hiddenSites = emptySet(),
                )
            }
            Ui.toast("Start page reset")
        })
    }
    if (cityDialog) CityDialog { cityDialog = false }
    if (confirmUnhide) CleanDialog(
        onDismissRequest = { confirmUnhide = false },
        title = { Text("Show hidden sites again?") },
        text = { Text(s.hiddenSites.sorted().joinToString("\n"), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { confirmUnhide = false; setStart { it.copy(hiddenSites = emptySet()) }; Suggest.invalidate() }) { Text("Show all") } },
        dismissButton = { TextButton(onClick = { confirmUnhide = false }) { Text("Cancel") } },
    )
}

@Composable
private fun CityDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Weather.Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        searching = true
        results = Weather.search(query)
        searching = false
    }
    fun pick(name: String, lat: Double, lon: Double) {
        setStart { it.copy(weatherCity = name, weatherLat = Weather.round(lat), weatherLon = Weather.round(lon)) }
        Weather.clearCache()
        scope.launch { Weather.refresh(force = true) }
        onDismiss()
    }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) myLocation(ctx) { loc -> if (loc != null) pick("My location", loc.latitude, loc.longitude) else Ui.toast("Location not available") }
        else Ui.toast("Location permission needed")
    }
    CleanDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weather location") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("City") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    if (searching) Text("Searching…", modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    results.forEachIndexed { i, p ->
                        if (i > 0) Divider()
                        Column(Modifier.fillMaxWidth().clickable { pick(p.name, p.lat, p.lon) }.padding(vertical = 10.dp)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            if (p.detail.isNotEmpty()) Text(p.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    myLocation(ctx) { loc -> if (loc != null) pick("My location", loc.latitude, loc.longitude) else Ui.toast("Location not available") }
                } else locLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }) { Text("Use my location") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@SuppressLint("MissingPermission")
private fun myLocation(ctx: Context, done: (Location?) -> Unit) {
    val lm = ctx.getSystemService(LocationManager::class.java)
    val last = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    if (last != null) { done(last); return }
    if (Build.VERSION.SDK_INT >= 30) {
        val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
        runCatching { lm.getCurrentLocation(provider, null, ctx.mainExecutor) { done(it) } }.onFailure { done(null) }
    } else done(null)
}
