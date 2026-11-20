package com.banner.cleanbrowser.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.BuildConfig
import com.banner.cleanbrowser.block.FilterLists
import com.banner.cleanbrowser.browser.Tabs
import com.banner.cleanbrowser.browser.Ui
import com.banner.cleanbrowser.core.AppLinks
import com.banner.cleanbrowser.core.HomePage
import com.banner.cleanbrowser.core.SearchEngines
import com.banner.cleanbrowser.core.Settings
import com.banner.cleanbrowser.core.SitePermission
import com.banner.cleanbrowser.core.ThemeMode
import com.banner.cleanbrowser.core.UserAgentMode
import com.banner.cleanbrowser.update.Updater
import kotlinx.coroutines.launch

private data class Page(val id: String, val title: String)

private val pages = listOf(
    Page("appearance", "Appearance"),
    Page("search", "Search"),
    Page("start", "Start page"),
    Page("privacy", "Privacy & security"),
    Page("permissions", "Site permissions"),
    Page("blocking", "Ad & tracker blocking"),
    Page("content", "Content"),
    Page("downloads", "Downloads"),
    Page("vpn", "VPN & proxy"),
    Page("data", "Clear browsing data"),
    Page("advanced", "Advanced"),
    Page("about", "About"),
)

@Composable
fun SettingsScreen() {
    val page = Nav.settingsStack.lastOrNull()
    BackHandler { Nav.back() }
    val motion = LocalMotion.current
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (motion) (fadeIn(tween(220)) + slideInHorizontally(tween(240)) { it / 10 }) togetherWith fadeOut(tween(140))
            else EnterTransition.None togetherWith ExitTransition.None
        },
        label = "settings",
    ) { id ->
        val title = pages.firstOrNull { it.id == id }?.title ?: "Settings"
        ScreenScaffold(title, onBack = { Nav.back() }) {
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                when (id) {
                    null -> item { SettingsHome() }
                    "appearance" -> item { Appearance() }
                    "search" -> item { SearchPage() }
                    "start" -> item { StartPageSettings() }
                    "privacy" -> item { PrivacyPage() }
                    "permissions" -> item { PermissionsPage() }
                    "blocking" -> item { BlockingPage() }
                    "content" -> item { ContentPage() }
                    "downloads" -> item { DownloadSettingsPage() }
                    "vpn" -> item { VpnSettingsPage() }
                    "data" -> item { DataPage() }
                    "advanced" -> item { AdvancedPage() }
                    "about" -> item { AboutPage() }
                }
            }
        }
    }
}

private fun set(change: (Settings) -> Settings) {
    App.prefs.update(change)
    Tabs.reapplySettings()
}

@Composable
private fun SettingsHome() {
    val ctx = LocalContext.current
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val rm = ctx.getSystemService(Context.ROLE_SERVICE) as RoleManager
    if (rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)) {
        RowItem("Set as default browser", icon = Icons.Filled.Star, onClick = {
            runCatching { roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER)) }
        })
        Divider()
    }
    val icons = mapOf(
        "appearance" to AppIcons.Palette, "search" to Icons.Filled.Search, "start" to Icons.Filled.Home, "privacy" to Icons.Filled.Lock,
        "permissions" to AppIcons.Shield, "blocking" to AppIcons.Shield, "content" to AppIcons.Image,
        "downloads" to AppIcons.Download, "vpn" to AppIcons.Vpn, "data" to Icons.Filled.Delete,
        "advanced" to Icons.Filled.Build, "about" to Icons.Filled.Info,
    )
    pages.forEach { p -> RowItem(p.title, icon = icons[p.id], onClick = { Nav.settingsStack.add(p.id) }) }
}

@Composable
private fun Appearance() {
    val s = App.prefs.state
    ChoiceRow("Theme", s.themeMode, listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")) { v ->
        set { it.copy(themeMode = v) }
    }
    SwitchRow("Pure black", s.pureBlack) { v -> set { it.copy(pureBlack = v) } }
    if (Build.VERSION.SDK_INT >= 31) SwitchRow("Wallpaper colors", s.wallpaperColors) { v -> set { it.copy(wallpaperColors = v) } }
    RowItem("Accent", value = Accents.list.getOrNull(s.accent)?.first, enabled = !s.wallpaperColors)
    AccentPicker(s.accent, enabled = !s.wallpaperColors) { i -> set { it.copy(accent = i) } }
    ChoiceRow("Address bar", s.toolbarBottom, listOf(false to "Top", true to "Bottom")) { v -> set { it.copy(toolbarBottom = v) } }
    SwitchRow("Home button", s.homeButton) { v -> set { it.copy(homeButton = v) } }
    SwitchRow("Show full address", s.showFullUrl) { v -> set { it.copy(showFullUrl = v) } }
    SwitchRow("Animations", s.animations) { v -> set { it.copy(animations = v) } }
    SwitchRow("Pull to refresh", s.pullToRefresh) { v -> set { it.copy(pullToRefresh = v) } }
    SwitchRow("Dark pages in dark theme", s.darkPages) { v -> set { it.copy(darkPages = v) } }
    SliderRow("Text size", s.textZoom, 50..200, 10, "%") { v -> set { it.copy(textZoom = v) } }
}

@Composable
private fun SearchPage() {
    val s = App.prefs.state
    ChoiceRow("Search engine", s.engine, SearchEngines.all.map { it.id to it.name }) { v -> set { it.copy(engine = v) } }
    if (s.engine == "custom") {
        TextRow("Custom search address", s.customSearchUrl, "https://example.com/?q=%s", KeyboardType.Uri) { v ->
            set { it.copy(customSearchUrl = v) }
        }
    }
    SwitchRow("Search suggestions", s.suggestions, enabled = SearchEngines.byId(s.engine).suggestUrl != null) { v ->
        set { it.copy(suggestions = v) }
    }
    SwitchRow("History and bookmark suggestions", s.historySuggestions) { v -> set { it.copy(historySuggestions = v) } }
    SwitchRow("Autofill address", s.autofillAddress) { v -> set { it.copy(autofillAddress = v) } }
    SwitchRow("Site suggestions", s.siteSuggestions) { v -> set { it.copy(siteSuggestions = v) } }
}

@Composable
private fun PrivacyPage() {
    val s = App.prefs.state
    SwitchRow("HTTPS only", s.httpsOnly) { v -> set { it.copy(httpsOnly = v) } }
    SwitchRow("Cookies", s.cookies) { v -> set { it.copy(cookies = v) } }
    SwitchRow("Third-party cookies", s.thirdPartyCookies, enabled = s.cookies) { v -> set { it.copy(thirdPartyCookies = v) } }
    SwitchRow("Global Privacy Control", s.gpc) { v -> set { it.copy(gpc = v) } }
    SwitchRow("Safe Browsing", s.safeBrowsing) { v -> set { it.copy(safeBrowsing = v) } }
    SwitchRow("Hide app name from sites", s.hideAppHeader) { v -> set { it.copy(hideAppHeader = v) } }
    SwitchRow("No preloading", s.noSpeculative) { v -> set { it.copy(noSpeculative = v) } }
    SwitchRow("No ad measurement", s.noAttribution) { v -> set { it.copy(noAttribution = v) } }
    SwitchRow("Save history", s.saveHistory) { v -> set { it.copy(saveHistory = v) } }
    SwitchRow("Private tabs by default", s.privateByDefault) { v -> set { it.copy(privateByDefault = v) } }
    SwitchRow("No screenshots in private tabs", s.privateNoScreenshots) { v -> set { it.copy(privateNoScreenshots = v) } }
    SwitchRow("Incognito keyboard in private tabs", s.privateIncognitoKeyboard) { v -> set { it.copy(privateIncognitoKeyboard = v) } }
    ChoiceRow("Block WebRTC leaks", s.webrtcBlock, listOf("auto" to "With Tor or proxy", "always" to "Always", "never" to "Never")) { v ->
        set { it.copy(webrtcBlock = v) }
    }
    PasswordsRow()
    SwitchRow("Clear data on quit", s.clearOnExit) { v -> set { it.copy(clearOnExit = v) } }
    if (s.clearOnExit) {
        SwitchRow("History", s.clearHistory) { v -> set { it.copy(clearHistory = v) } }
        SwitchRow("Cookies", s.clearCookies) { v -> set { it.copy(clearCookies = v) } }
        SwitchRow("Cache", s.clearCache) { v -> set { it.copy(clearCache = v) } }
        SwitchRow("Site storage", s.clearSiteData) { v -> set { it.copy(clearSiteData = v) } }
        SwitchRow("Open tabs", s.clearTabs) { v -> set { it.copy(clearTabs = v) } }
    }
}

@Composable
private fun PermissionsPage() {
    val s = App.prefs.state
    val opts = listOf(SitePermission.ASK to "Ask", SitePermission.BLOCK to "Block")
    ChoiceRow("Location", s.location, opts) { v -> set { it.copy(location = v) } }
    ChoiceRow("Camera", s.camera, opts) { v -> set { it.copy(camera = v) } }
    ChoiceRow("Microphone", s.microphone, opts) { v -> set { it.copy(microphone = v) } }
    ChoiceRow("Protected content", s.protectedMedia, opts) { v -> set { it.copy(protectedMedia = v) } }
    ChoiceRow("Open links in apps", s.appLinks, listOf(AppLinks.ASK to "Ask", AppLinks.NEVER to "Never")) { v -> set { it.copy(appLinks = v) } }
    SwitchRow("Block pop-ups", s.blockPopups) { v -> set { it.copy(blockPopups = v) } }
    SwitchRow("Block autoplay", s.blockAutoplay) { v -> set { it.copy(blockAutoplay = v) } }
    SwitchRow("JavaScript", s.javascript) { v -> set { it.copy(javascript = v) } }
    var refresh by remember { mutableIntStateOf(0) }
    val sites = remember(refresh) { App.sites.all() }
    if (sites.isNotEmpty()) {
        RowItem("Site exceptions", value = "${sites.size}")
        sites.forEach { site ->
            val parts = buildList {
                if (site.blocking == 0) add("blocking off") else if (site.blocking == 1) add("blocking on")
                if (site.javascript == 0) add("JavaScript off") else if (site.javascript == 1) add("JavaScript on")
                if (site.desktop == 1) add("desktop") else if (site.desktop == 0) add("mobile")
            }
            RowItem(site.host, value = parts.joinToString(" · "), trailing = {
                TextButton(onClick = { App.sites.save(site.copy(javascript = -1, blocking = -1, desktop = -1)); refresh++ }) {
                    Text("Reset")
                }
            })
        }
    }
}

@Composable
private fun BlockingPage() {
    val s = App.prefs.state
    val ctx = LocalContext.current
    SwitchRow("Block ads & trackers", s.blockAds) { v -> set { it.copy(blockAds = v) } }
    val updated = if (FilterLists.lastUpdate > 0) DateUtils.getRelativeTimeSpanString(FilterLists.lastUpdate).toString() else "never"
    RowItem(
        if (FilterLists.updating) "Updating…" else "Update lists",
        value = "${FilterLists.ruleCount} rules · $updated",
        icon = AppIcons.Update,
        enabled = !FilterLists.updating,
        onClick = { FilterLists.refreshNow(ctx) },
    )
    Header("Lists")
    FilterLists.all.forEach { l ->
        SwitchRow(l.name, l.id in s.filterLists, enabled = s.blockAds) { on ->
            App.prefs.update { it.copy(filterLists = if (on) it.filterLists + l.id else it.filterLists - l.id) }
            FilterLists.onSelectionChanged(ctx)
        }
    }
}

@Composable
private fun Header(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun ContentPage() {
    val s = App.prefs.state
    SwitchRow("Load images", s.loadImages) { v -> set { it.copy(loadImages = v) } }
    SwitchRow("Site icons", s.siteIcons) { v -> set { it.copy(siteIcons = v) } }
    SwitchRow("Desktop sites by default", s.desktopDefault) { v -> set { it.copy(desktopDefault = v) } }
    ChoiceRow(
        "User agent", s.userAgent,
        listOf(UserAgentMode.CHROME to "Chrome", UserAgentMode.WEBVIEW to "Android WebView", UserAgentMode.DESKTOP to "Desktop", UserAgentMode.CUSTOM to "Custom"),
    ) { v -> set { it.copy(userAgent = v) } }
    if (s.userAgent == UserAgentMode.CUSTOM) TextRow("Custom user agent", s.customUserAgent) { v -> set { it.copy(customUserAgent = v) } }
    SwitchRow("Restore tabs", s.restoreTabs) { v -> set { it.copy(restoreTabs = v) } }
}

@Composable
private fun DataPage() {
    val s = App.prefs.state
    var history by remember { mutableStateOf(true) }
    var cookies by remember { mutableStateOf(true) }
    var cache by remember { mutableStateOf(true) }
    var site by remember { mutableStateOf(true) }
    var tabs by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    SwitchRow("History", history) { history = it }
    SwitchRow("Cookies", cookies) { cookies = it }
    SwitchRow("Cache", cache) { cache = it }
    SwitchRow("Site storage", site) { site = it }
    SwitchRow("Open tabs", tabs) { tabs = it }
    RowItem("Clear now", icon = Icons.Filled.Delete, enabled = history || cookies || cache || site || tabs, onClick = { confirm = true })
    if (confirm) {
        CleanDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear browsing data?") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    Cleaner.clear(history, cookies, cache, site, tabs)
                    Ui.toast("Cleared")
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
    if (s.clearOnExit) RowItem("Also cleared on quit", value = "On")
}

@Composable
private fun AdvancedPage() {
    val s = App.prefs.state
    var reset by remember { mutableStateOf(false) }
    SwitchRow("Remote debugging", s.remoteDebugging) { v -> set { it.copy(remoteDebugging = v) } }
    SwitchRow("Confirm downloads", s.confirmDownloads) { v -> set { it.copy(confirmDownloads = v) } }
    RowItem("Reset all settings", icon = Icons.Filled.Delete, onClick = { reset = true })
    if (reset) {
        CleanDialog(
            onDismissRequest = { reset = false },
            title = { Text("Reset all settings?") },
            confirmButton = { TextButton(onClick = { reset = false; App.prefs.resetAll(); Tabs.reapplySettings() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AboutPage() {
    val s = App.prefs.state
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.banner.cleanbrowser.R.drawable.logo), null,
            Modifier.padding(bottom = 8.dp).then(Modifier.size(72.dp)),
        )
        Text("Clean Browser", style = MaterialTheme.typography.titleMedium)
    }
    RowItem("Version", value = "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})", icon = Icons.Filled.Info)
    RowItem(
        when (val st = Updater.state) {
            is Updater.State.Checking -> "Checking…"
            is Updater.State.Downloading -> "Downloading… ${st.percent}%"
            else -> "Check for updates"
        },
        value = Updater.statusText(),
        icon = AppIcons.Update,
        enabled = Updater.state !is Updater.State.Checking && Updater.state !is Updater.State.Downloading,
        onClick = { scope.launch { Updater.check(ctx, manual = true) } },
    )
    SwitchRow("Check on start", s.autoUpdateCheck) { v -> set { it.copy(autoUpdateCheck = v) } }
    SwitchRow("Test builds", s.testBuilds) { v -> set { it.copy(testBuilds = v) } }
    RowItem("Source", value = "github.com/The412Banner/CleanBrowser")
    RowItem("Licence", value = "MIT")
    RowItem("Filter lists", value = "EasyList, EasyPrivacy, uBlock, Fanboy, Peter Lowe, StevenBlack — each under its own licence")
    RowItem("Weather", value = "Open-Meteo.com (CC BY 4.0)")
    RowItem("Torrents · Tor · WireGuard", value = "jlibtorrent (MIT) · tor-android (BSD-3) · WireGuard (Apache 2.0)")
    RowItem("Icons", value = "Material Icons (Apache 2.0)")
}

/** Clears browsing data; also used by "clear on quit". */
object Cleaner {
    fun clear(history: Boolean, cookies: Boolean, cache: Boolean, site: Boolean, tabs: Boolean) {
        if (history) {
            App.io { App.db.clearHistory() }
            com.banner.cleanbrowser.core.Favicons.clearAll()
        }
        if (cookies) {
            com.banner.cleanbrowser.browser.Engine.cookieManager(false).removeAllCookies(null)
            com.banner.cleanbrowser.browser.Engine.cookieManager(false).flush()
            Tabs.clearPrivateData()
        }
        if (site) {
            com.banner.cleanbrowser.browser.Engine.webStorage(false).deleteAllData()
            android.webkit.WebViewDatabase.getInstance(App.instance).clearHttpAuthUsernamePassword()
        }
        if (cache) {
            val wv = Tabs.list.firstNotNullOfOrNull { it.webView }
            if (wv != null) wv.clearCache(true) else Tabs.activity?.let { a -> android.webkit.WebView(a).apply { clearCache(true); destroy() } }
        }
        if (tabs) {
            Tabs.closeEverything()
            App.prefs.putString("session", "")
        }
    }

    fun onQuit() {
        val s = App.prefs.state
        if (!s.clearOnExit) return
        clear(s.clearHistory, s.clearCookies, s.clearCache, s.clearSiteData, s.clearTabs)
    }
}

/** Passwords are handled by the user's own password manager through Android autofill. */
@Composable
private fun PasswordsRow() {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { name = autofillServiceName(ctx) }
    RowItem("Passwords", value = name ?: "No autofill service", onClick = {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= 34) add(Intent(android.provider.Settings.ACTION_CREDENTIAL_PROVIDER))
            add(Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(android.net.Uri.parse("package:${ctx.packageName}")))
            add(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
        for (i in intents) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) break
        }
    })
}

private fun autofillServiceName(ctx: Context): String? = runCatching {
    val am = ctx.getSystemService(android.view.autofill.AutofillManager::class.java) ?: return null
    val cn = am.autofillServiceComponentName ?: return null
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(cn.packageName, 0)).toString()
}.getOrNull()
