package com.banner.cleanbrowser.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class HomePage { START, BLANK, ENGINE, CUSTOM }
enum class SitePermission { ASK, BLOCK }
enum class AppLinks { ASK, NEVER }
enum class UserAgentMode { CHROME, WEBVIEW, DESKTOP, CUSTOM }
enum class NetMode { DIRECT, PROXY, TOR, WIREGUARD }

data class Settings(
    // Appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val pureBlack: Boolean = false,
    val wallpaperColors: Boolean = false,
    val accent: Int = 0,
    val toolbarBottom: Boolean = false,
    val homeButton: Boolean = true,
    val showFullUrl: Boolean = false,
    val animations: Boolean = true,
    val pullToRefresh: Boolean = true,
    val textZoom: Int = 100,
    val darkPages: Boolean = true,
    // Search
    val engine: String = "google",
    val customSearchUrl: String = "",
    val suggestions: Boolean = false,
    val historySuggestions: Boolean = true,
    val homePage: HomePage = HomePage.START,
    val homeUrl: String = "",
    val spLogo: Boolean = true,
    val spClock: Boolean = true,
    val spWeather: Boolean = true,
    val spDevice: Boolean = true,
    val spBookmarks: Boolean = true,
    val spFrequent: Boolean = true,
    val weatherCity: String = "",
    val weatherLat: String = "",
    val weatherLon: String = "",
    val weatherUnit: String = "auto",
    val autofillAddress: Boolean = true,
    val siteSuggestions: Boolean = true,
    val hiddenSites: Set<String> = emptySet(),
    // Privacy and security
    val blockAds: Boolean = true,
    val filterLists: Set<String> = setOf("easylist", "easyprivacy"),
    val httpsOnly: Boolean = true,
    val cookies: Boolean = true,
    val thirdPartyCookies: Boolean = false,
    val safeBrowsing: Boolean = false,
    val gpc: Boolean = true,
    val blockPopups: Boolean = true,
    val blockAutoplay: Boolean = true,
    val javascript: Boolean = true,
    val saveHistory: Boolean = true,
    val clearOnExit: Boolean = false,
    val clearHistory: Boolean = true,
    val clearCookies: Boolean = true,
    val clearCache: Boolean = true,
    val clearSiteData: Boolean = true,
    val clearTabs: Boolean = true,
    val privateByDefault: Boolean = false,
    val webrtcBlock: String = "auto",
    val privateNoScreenshots: Boolean = true,
    val privateIncognitoKeyboard: Boolean = true,
    val appLinks: AppLinks = AppLinks.ASK,
    val location: SitePermission = SitePermission.ASK,
    val camera: SitePermission = SitePermission.ASK,
    val microphone: SitePermission = SitePermission.ASK,
    val protectedMedia: SitePermission = SitePermission.ASK,
    val hideAppHeader: Boolean = true,
    val noSpeculative: Boolean = true,
    val noAttribution: Boolean = true,
    // Content
    val loadImages: Boolean = true,
    val siteIcons: Boolean = true,
    val desktopDefault: Boolean = false,
    val userAgent: UserAgentMode = UserAgentMode.CHROME,
    val customUserAgent: String = "",
    val restoreTabs: Boolean = true,
    val confirmDownloads: Boolean = true,
    // Advanced
    val remoteDebugging: Boolean = false,
    // Downloads, torrents, network
    val dlFolder: String = "",
    val dlConnections: Int = 8,
    val dlParallel: Int = 3,
    val dlWifiOnly: Boolean = false,
    val dlSpeedLimit: Int = 0,
    val dlNotifications: Boolean = true,
    val dlRetries: Int = 10,
    val dlPreallocate: Boolean = true,
    val torFolder: String = "",
    val torSeedRatio: Int = 100,
    val torSeedBackground: Boolean = false,
    val torSequential: Boolean = false,
    val torEncryption: String = "prefer",
    val torDht: Boolean = true,
    val torLsd: Boolean = true,
    val torUpnp: Boolean = true,
    val torDownLimit: Int = 0,
    val torUpLimit: Int = 0,
    val torMaxActive: Int = 3,
    val torOnlyVpn: Boolean = false,
    val torExtraTrackers: Boolean = true,
    val torConnections: Int = 200,
    val netMode: NetMode = NetMode.DIRECT,
    val proxyType: String = "SOCKS5",
    val proxyHost: String = "",
    val proxyPort: Int = 1080,
    val proxyUser: String = "",
    val proxyPass: String = "",
    val netBrowser: Boolean = true,
    val netDownloads: Boolean = true,
    val netTorrents: Boolean = true,
    val torExitCountry: String = "",
    val wgActive: String = "",
    val killSwitch: Boolean = true,
    // Updates
    val autoUpdateCheck: Boolean = true,
    val testBuilds: Boolean = true,
)

/** App settings in SharedPreferences, exposed as Compose state. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val misc: SharedPreferences = context.getSharedPreferences("state", Context.MODE_PRIVATE)

    var state by mutableStateOf(load())
        private set

    fun update(change: (Settings) -> Settings) {
        val next = change(state)
        if (next != state) {
            state = next
            save(next)
        }
    }

    fun resetAll() {
        sp.edit().clear().apply()
        state = Settings()
    }

    // Small app state outside the settings model.
    fun getString(key: String, def: String = ""): String = misc.getString(key, def) ?: def
    fun putString(key: String, value: String) = misc.edit().putString(key, value).apply()
    fun getLong(key: String, def: Long = 0L): Long = misc.getLong(key, def)
    fun putLong(key: String, value: Long) = misc.edit().putLong(key, value).apply()
    fun getBool(key: String, def: Boolean = false): Boolean = misc.getBoolean(key, def)
    fun putBool(key: String, value: Boolean) = misc.edit().putBoolean(key, value).commit()

    private inline fun <reified E : Enum<E>> SharedPreferences.enumOf(key: String, def: E): E =
        getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: def

    private fun load(): Settings {
        val d = Settings()
        with(sp) {
            return Settings(
                themeMode = enumOf("themeMode", d.themeMode),
                pureBlack = getBoolean("pureBlack", d.pureBlack),
                wallpaperColors = getBoolean("wallpaperColors", d.wallpaperColors),
                accent = getInt("accent", d.accent),
                toolbarBottom = getBoolean("toolbarBottom", d.toolbarBottom),
                homeButton = getBoolean("homeButton", d.homeButton),
                showFullUrl = getBoolean("showFullUrl", d.showFullUrl),
                animations = getBoolean("animations", d.animations),
                pullToRefresh = getBoolean("pullToRefresh", d.pullToRefresh),
                textZoom = getInt("textZoom", d.textZoom),
                darkPages = getBoolean("darkPages", d.darkPages),
                engine = getString("engine", d.engine) ?: d.engine,
                customSearchUrl = getString("customSearchUrl", d.customSearchUrl) ?: "",
                suggestions = getBoolean("suggestions", d.suggestions),
                historySuggestions = getBoolean("historySuggestions", d.historySuggestions),
                homePage = enumOf("homePage", d.homePage),
                homeUrl = getString("homeUrl", d.homeUrl) ?: "",
                spLogo = getBoolean("spLogo", d.spLogo),
                spClock = getBoolean("spClock", d.spClock),
                spWeather = getBoolean("spWeather", d.spWeather),
                spDevice = getBoolean("spDevice", d.spDevice),
                spBookmarks = getBoolean("spBookmarks", d.spBookmarks),
                spFrequent = getBoolean("spFrequent", d.spFrequent),
                weatherCity = getString("weatherCity", d.weatherCity) ?: d.weatherCity,
                weatherLat = getString("weatherLat", d.weatherLat) ?: d.weatherLat,
                weatherLon = getString("weatherLon", d.weatherLon) ?: d.weatherLon,
                weatherUnit = getString("weatherUnit", d.weatherUnit) ?: d.weatherUnit,
                autofillAddress = getBoolean("autofillAddress", d.autofillAddress),
                siteSuggestions = getBoolean("siteSuggestions", d.siteSuggestions),
                hiddenSites = getStringSet("hiddenSites", d.hiddenSites)?.toSet() ?: d.hiddenSites,
                blockAds = getBoolean("blockAds", d.blockAds),
                filterLists = getStringSet("filterLists", d.filterLists)?.toSet() ?: d.filterLists,
                httpsOnly = getBoolean("httpsOnly", d.httpsOnly),
                cookies = getBoolean("cookies", d.cookies),
                thirdPartyCookies = getBoolean("thirdPartyCookies", d.thirdPartyCookies),
                safeBrowsing = getBoolean("safeBrowsing", d.safeBrowsing),
                gpc = getBoolean("gpc", d.gpc),
                blockPopups = getBoolean("blockPopups", d.blockPopups),
                blockAutoplay = getBoolean("blockAutoplay", d.blockAutoplay),
                javascript = getBoolean("javascript", d.javascript),
                saveHistory = getBoolean("saveHistory", d.saveHistory),
                clearOnExit = getBoolean("clearOnExit", d.clearOnExit),
                clearHistory = getBoolean("clearHistory", d.clearHistory),
                clearCookies = getBoolean("clearCookies", d.clearCookies),
                clearCache = getBoolean("clearCache", d.clearCache),
                clearSiteData = getBoolean("clearSiteData", d.clearSiteData),
                clearTabs = getBoolean("clearTabs", d.clearTabs),
                privateByDefault = getBoolean("privateByDefault", d.privateByDefault),
                webrtcBlock = getString("webrtcBlock", d.webrtcBlock) ?: d.webrtcBlock,
                privateNoScreenshots = getBoolean("privateNoScreenshots", d.privateNoScreenshots),
                privateIncognitoKeyboard = getBoolean("privateIncognitoKeyboard", d.privateIncognitoKeyboard),
                appLinks = enumOf("appLinks", d.appLinks),
                location = enumOf("location", d.location),
                camera = enumOf("camera", d.camera),
                microphone = enumOf("microphone", d.microphone),
                protectedMedia = enumOf("protectedMedia", d.protectedMedia),
                hideAppHeader = getBoolean("hideAppHeader", d.hideAppHeader),
                noSpeculative = getBoolean("noSpeculative", d.noSpeculative),
                noAttribution = getBoolean("noAttribution", d.noAttribution),
                loadImages = getBoolean("loadImages", d.loadImages),
                siteIcons = getBoolean("siteIcons", d.siteIcons),
                desktopDefault = getBoolean("desktopDefault", d.desktopDefault),
                userAgent = enumOf("userAgent", d.userAgent),
                customUserAgent = getString("customUserAgent", d.customUserAgent) ?: "",
                restoreTabs = getBoolean("restoreTabs", d.restoreTabs),
                confirmDownloads = getBoolean("confirmDownloads", d.confirmDownloads),
                remoteDebugging = getBoolean("remoteDebugging", d.remoteDebugging),
                dlFolder = getString("dlFolder", d.dlFolder) ?: d.dlFolder,
                dlConnections = getInt("dlConnections", d.dlConnections),
                dlParallel = getInt("dlParallel", d.dlParallel),
                dlWifiOnly = getBoolean("dlWifiOnly", d.dlWifiOnly),
                dlSpeedLimit = getInt("dlSpeedLimit", d.dlSpeedLimit),
                dlNotifications = getBoolean("dlNotifications", d.dlNotifications),
                dlRetries = getInt("dlRetries", d.dlRetries),
                dlPreallocate = getBoolean("dlPreallocate", d.dlPreallocate),
                torFolder = getString("torFolder", d.torFolder) ?: d.torFolder,
                torSeedRatio = getInt("torSeedRatio", d.torSeedRatio),
                torSeedBackground = getBoolean("torSeedBackground", d.torSeedBackground),
                torSequential = getBoolean("torSequential", d.torSequential),
                torEncryption = getString("torEncryption", d.torEncryption) ?: d.torEncryption,
                torDht = getBoolean("torDht", d.torDht),
                torLsd = getBoolean("torLsd", d.torLsd),
                torUpnp = getBoolean("torUpnp", d.torUpnp),
                torDownLimit = getInt("torDownLimit", d.torDownLimit),
                torUpLimit = getInt("torUpLimit", d.torUpLimit),
                torMaxActive = getInt("torMaxActive", d.torMaxActive),
                torOnlyVpn = getBoolean("torOnlyVpn", d.torOnlyVpn),
                torExtraTrackers = getBoolean("torExtraTrackers", d.torExtraTrackers),
                torConnections = getInt("torConnections", d.torConnections),
                netMode = enumOf("netMode", d.netMode),
                proxyType = getString("proxyType", d.proxyType) ?: d.proxyType,
                proxyHost = getString("proxyHost", d.proxyHost) ?: d.proxyHost,
                proxyPort = getInt("proxyPort", d.proxyPort),
                proxyUser = getString("proxyUser", d.proxyUser) ?: d.proxyUser,
                proxyPass = getString("proxyPass", d.proxyPass) ?: d.proxyPass,
                netBrowser = getBoolean("netBrowser", d.netBrowser),
                netDownloads = getBoolean("netDownloads", d.netDownloads),
                netTorrents = getBoolean("netTorrents", d.netTorrents),
                torExitCountry = getString("torExitCountry", d.torExitCountry) ?: d.torExitCountry,
                wgActive = getString("wgActive", d.wgActive) ?: d.wgActive,
                killSwitch = getBoolean("killSwitch", d.killSwitch),
                autoUpdateCheck = getBoolean("autoUpdateCheck", d.autoUpdateCheck),
                testBuilds = getBoolean("testBuilds", d.testBuilds),
            )
        }
    }

    private fun save(s: Settings) {
        sp.edit().apply {
            putString("themeMode", s.themeMode.name)
            putBoolean("pureBlack", s.pureBlack)
            putBoolean("wallpaperColors", s.wallpaperColors)
            putInt("accent", s.accent)
            putBoolean("toolbarBottom", s.toolbarBottom)
            putBoolean("homeButton", s.homeButton)
            putBoolean("showFullUrl", s.showFullUrl)
            putBoolean("animations", s.animations)
            putBoolean("pullToRefresh", s.pullToRefresh)
            putInt("textZoom", s.textZoom)
            putBoolean("darkPages", s.darkPages)
            putString("engine", s.engine)
            putString("customSearchUrl", s.customSearchUrl)
            putBoolean("suggestions", s.suggestions)
            putBoolean("historySuggestions", s.historySuggestions)
            putString("homePage", s.homePage.name)
            putString("homeUrl", s.homeUrl)
            putBoolean("spLogo", s.spLogo)
            putBoolean("spClock", s.spClock)
            putBoolean("spWeather", s.spWeather)
            putBoolean("spDevice", s.spDevice)
            putBoolean("spBookmarks", s.spBookmarks)
            putBoolean("spFrequent", s.spFrequent)
            putString("weatherCity", s.weatherCity)
            putString("weatherLat", s.weatherLat)
            putString("weatherLon", s.weatherLon)
            putString("weatherUnit", s.weatherUnit)
            putBoolean("autofillAddress", s.autofillAddress)
            putBoolean("siteSuggestions", s.siteSuggestions)
            putStringSet("hiddenSites", s.hiddenSites)
            putBoolean("blockAds", s.blockAds)
            putStringSet("filterLists", s.filterLists)
            putBoolean("httpsOnly", s.httpsOnly)
            putBoolean("cookies", s.cookies)
            putBoolean("thirdPartyCookies", s.thirdPartyCookies)
            putBoolean("safeBrowsing", s.safeBrowsing)
            putBoolean("gpc", s.gpc)
            putBoolean("blockPopups", s.blockPopups)
            putBoolean("blockAutoplay", s.blockAutoplay)
            putBoolean("javascript", s.javascript)
            putBoolean("saveHistory", s.saveHistory)
            putBoolean("clearOnExit", s.clearOnExit)
            putBoolean("clearHistory", s.clearHistory)
            putBoolean("clearCookies", s.clearCookies)
            putBoolean("clearCache", s.clearCache)
            putBoolean("clearSiteData", s.clearSiteData)
            putBoolean("clearTabs", s.clearTabs)
            putBoolean("privateByDefault", s.privateByDefault)
            putString("webrtcBlock", s.webrtcBlock)
            putBoolean("privateNoScreenshots", s.privateNoScreenshots)
            putBoolean("privateIncognitoKeyboard", s.privateIncognitoKeyboard)
            putString("appLinks", s.appLinks.name)
            putString("location", s.location.name)
            putString("camera", s.camera.name)
            putString("microphone", s.microphone.name)
            putString("protectedMedia", s.protectedMedia.name)
            putBoolean("hideAppHeader", s.hideAppHeader)
            putBoolean("noSpeculative", s.noSpeculative)
            putBoolean("noAttribution", s.noAttribution)
            putBoolean("loadImages", s.loadImages)
            putBoolean("siteIcons", s.siteIcons)
            putBoolean("desktopDefault", s.desktopDefault)
            putString("userAgent", s.userAgent.name)
            putString("customUserAgent", s.customUserAgent)
            putBoolean("restoreTabs", s.restoreTabs)
            putBoolean("confirmDownloads", s.confirmDownloads)
            putBoolean("remoteDebugging", s.remoteDebugging)
            putString("dlFolder", s.dlFolder)
            putInt("dlConnections", s.dlConnections)
            putInt("dlParallel", s.dlParallel)
            putBoolean("dlWifiOnly", s.dlWifiOnly)
            putInt("dlSpeedLimit", s.dlSpeedLimit)
            putBoolean("dlNotifications", s.dlNotifications)
            putInt("dlRetries", s.dlRetries)
            putBoolean("dlPreallocate", s.dlPreallocate)
            putString("torFolder", s.torFolder)
            putInt("torSeedRatio", s.torSeedRatio)
            putBoolean("torSeedBackground", s.torSeedBackground)
            putBoolean("torSequential", s.torSequential)
            putString("torEncryption", s.torEncryption)
            putBoolean("torDht", s.torDht)
            putBoolean("torLsd", s.torLsd)
            putBoolean("torUpnp", s.torUpnp)
            putInt("torDownLimit", s.torDownLimit)
            putInt("torUpLimit", s.torUpLimit)
            putInt("torMaxActive", s.torMaxActive)
            putBoolean("torOnlyVpn", s.torOnlyVpn)
            putBoolean("torExtraTrackers", s.torExtraTrackers)
            putInt("torConnections", s.torConnections)
            putString("netMode", s.netMode.name)
            putString("proxyType", s.proxyType)
            putString("proxyHost", s.proxyHost)
            putInt("proxyPort", s.proxyPort)
            putString("proxyUser", s.proxyUser)
            putString("proxyPass", s.proxyPass)
            putBoolean("netBrowser", s.netBrowser)
            putBoolean("netDownloads", s.netDownloads)
            putBoolean("netTorrents", s.netTorrents)
            putString("torExitCountry", s.torExitCountry)
            putString("wgActive", s.wgActive)
            putBoolean("killSwitch", s.killSwitch)
            putBoolean("autoUpdateCheck", s.autoUpdateCheck)
            putBoolean("testBuilds", s.testBuilds)
        }.apply()
    }
}
