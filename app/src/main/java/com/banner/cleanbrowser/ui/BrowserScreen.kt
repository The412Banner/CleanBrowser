package com.banner.cleanbrowser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.browser.Engine
import com.banner.cleanbrowser.browser.Tab
import com.banner.cleanbrowser.browser.Tabs
import com.banner.cleanbrowser.browser.Ui
import com.banner.cleanbrowser.core.Bookmark
import com.banner.cleanbrowser.core.HomePage
import com.banner.cleanbrowser.core.SearchEngines
import com.banner.cleanbrowser.core.Suggest
import com.banner.cleanbrowser.core.Urls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

object Browser {
    fun go(tab: Tab, input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = Urls.toUrl(text) ?: SearchEngines.searchUrl(App.prefs.state, text)
        Tabs.load(tab, url)
    }

    fun homeUrl(): String {
        val s = App.prefs.state
        return when (s.homePage) {
            HomePage.START, HomePage.BLANK -> ""
            HomePage.ENGINE -> SearchEngines.homeUrl(s)
            HomePage.CUSTOM -> Urls.toUrl(s.homeUrl) ?: ""
        }
    }

    /** Home: the start page or the chosen home page. Back returns to the page that was open. */
    fun goHome(tab: Tab) {
        val u = homeUrl()
        if (u.isNotEmpty()) { Tabs.load(tab, u); return }
        tab.webView?.stopLoading()
        tab.url = ""
        tab.title = ""
        tab.loading = false
        tab.blockedCount = 0
        Ui.findBar = false
    }

    fun newTab(private: Boolean, url: String = homeUrl()) {
        Tabs.open(url, private)
        Nav.screen = Screen.BROWSER
        if (url.isEmpty()) Nav.focusAddress = true
    }

    fun share(ctx: Context, url: String, title: String = "") {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        if (title.isNotEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title)
        ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", text))
    }
}

@Composable
fun BrowserScreen(onQuit: () -> Unit) {
    val s = App.prefs.state
    val tab = Tabs.current ?: return
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var siteSheet by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    /** What the user actually typed (the field may also hold an autofilled ending). */
    var typed by remember { mutableStateOf("") }

    LaunchedEffect(Nav.focusAddress, tab.id) {
        if (Nav.focusAddress) {
            Nav.focusAddress = false
            field = TextFieldValue("")
            typed = ""
            editing = true
        }
    }

    fun startEdit() {
        val t = if (tab.isBlank) "" else tab.url
        field = TextFieldValue(t, TextRange(0, t.length))
        typed = ""
        editing = true
    }

    val toolbar: @Composable () -> Unit = {
        Toolbar(
            tab = tab, editing = editing, field = field,
            onField = { v ->
                val grew = v.text.length > typed.length && v.selection.collapsed && v.selection.end == v.text.length
                val completion = if (s.autofillAddress && grew) Suggest.complete(v.text) else null
                typed = v.text
                field = if (completion != null) TextFieldValue(completion, TextRange(v.text.length, completion.length)) else v
            },
            onStartEdit = { startEdit() },
            onStopEdit = { editing = false },
            onGo = { Browser.go(tab, it); editing = false },
            onTabs = { Tabs.captureThumbnail(tab); Nav.showPrivateTabs = tab.isPrivate; Nav.open(Screen.TABS) },
            onNewTab = { Browser.newTab(tab.isPrivate) },
            onMenu = { menu = true },
            onShield = { siteSheet = true },
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
    IncognitoKeyboard(tab.isPrivate && s.privateIncognitoKeyboard) {
    Column(Modifier.fillMaxSize().imePadding()) {
        if (!s.toolbarBottom) {
            Box(Modifier.statusBarsPadding()) { toolbar() }
            ProgressLine(tab)
        } else {
            Spacer(Modifier.statusBarsPadding())
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab.isBlank) StartPage(onOpen = { Tabs.load(tab, it) }) else WebHost(tab, s.pullToRefresh)
            androidx.compose.animation.AnimatedVisibility(
                visible = editing,
                enter = fadeIn(Motion.fast()),
                exit = fadeOut(Motion.fast()),
            ) {
                Suggestions(
                    typed,
                    current = tab.takeIf { !it.isBlank && Urls.isWeb(it.url) },
                    onPick = { Browser.go(tab, it); editing = false },
                    onFill = {
                        field = TextFieldValue(it, TextRange(it.length))
                        typed = it
                    },
                    onDone = { editing = false },
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = Ui.findBar,
                enter = fadeIn(Motion.fast()) + slideInVertically(Motion.fast()) { -it / 2 },
                exit = fadeOut(Motion.fast()),
                modifier = Modifier.align(if (s.toolbarBottom) Alignment.BottomCenter else Alignment.TopCenter),
            ) { FindBar(tab) }
        }
        if (s.toolbarBottom) {
            ProgressLine(tab)
            Box(Modifier.navigationBarsPadding()) { toolbar() }
        } else {
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    }
    }

    if (menu) MenuSheet(tab, onDismiss = { menu = false }, onQuit = onQuit)
    if (siteSheet) SiteSheet(tab, onDismiss = { siteSheet = false })
}

@Composable
private fun Toolbar(
    tab: Tab,
    editing: Boolean,
    field: TextFieldValue,
    onField: (TextFieldValue) -> Unit,
    onStartEdit: () -> Unit,
    onStopEdit: () -> Unit,
    onGo: (String) -> Unit,
    onTabs: () -> Unit,
    onNewTab: () -> Unit,
    onMenu: () -> Unit,
    onShield: () -> Unit,
) {
    val s = App.prefs.state
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(start = if (s.homeButton && !editing) 2.dp else 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.homeButton && !editing) {
            IconButton(onClick = { Browser.goHome(tab) }) { Icon(Icons.Filled.Home, "Home", tint = MaterialTheme.colorScheme.onSurface) }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f).height(44.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp)) {
                if (!editing) {
                    LeadingBadge(tab, onShield)
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(CircleShape).clickable { onStartEdit() }.padding(start = 4.dp, end = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = when {
                                tab.isBlank -> "Search or type address"
                                s.showFullUrl -> tab.url
                                else -> Urls.display(tab.url)
                            },
                            color = if (tab.isBlank) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.5.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).size(20.dp))
                    var hadFocus by remember { mutableStateOf(false) }
                    BasicTextField(
                        value = field,
                        onValueChange = onField,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.5.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { fm.clearFocus(); onGo(field.text) }),
                        modifier = Modifier.weight(1f).padding(start = 10.dp).focusRequester(focus)
                            .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) onStopEdit() },
                    )
                    if (field.text.isNotEmpty()) {
                        IconButton(onClick = { onField(TextFieldValue("")) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, "Clear", modifier = Modifier.size(18.dp))
                        }
                    }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
        }
        if (!editing) {
            IconButton(onClick = onNewTab) { Icon(Icons.Filled.Add, "New tab", tint = MaterialTheme.colorScheme.onSurface) }
            TabsButton(Tabs.list.count { it.isPrivate == tab.isPrivate }, onTabs)
            IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreVert, "Menu", tint = MaterialTheme.colorScheme.onSurface) }
        } else {
            IconButton(onClick = { fm.clearFocus(); onStopEdit() }) { Icon(Icons.Filled.Close, "Cancel") }
        }
    }
}

@Composable
private fun LeadingBadge(tab: Tab, onShield: () -> Unit) {
    val secure = Urls.isHttps(tab.url)
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(enabled = !tab.isBlank) { onShield() },
        contentAlignment = Alignment.Center,
    ) {
        when {
            tab.isPrivate && tab.isBlank -> Icon(AppIcons.Private, "Private", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            tab.isBlank -> Icon(Icons.Filled.Search, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            tab.blockedCount > 0 -> {
                Icon(AppIcons.Shield, "Protection", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    if (tab.blockedCount > 99) "99" else tab.blockedCount.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            secure -> Icon(Icons.Filled.Lock, "Secure", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Icon(Icons.Filled.Warning, "Not secure", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TabsButton(count: Int, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(22.dp).border(1.8.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (count > 99) "∞" else count.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ProgressLine(tab: Tab) {
    val target = if (tab.loading) tab.progress.coerceIn(5, 100) / 100f else 1f
    val frac by animateFloatAsState(target, Motion.medium(), label = "progress")
    Box(Modifier.fillMaxWidth().height(2.5.dp)) {
        androidx.compose.animation.AnimatedVisibility(tab.loading, enter = fadeIn(Motion.fast()), exit = fadeOut(Motion.medium())) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun WebHost(tab: Tab, pull: Boolean) {
    val primary = MaterialTheme.colorScheme.primary.toArgbInt()
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh.toArgbInt()
    androidx.compose.runtime.key(tab.id, tab.generation) {
        AndroidView(
            factory = { ctx ->
                val srl = SwipeRefreshLayout(ctx)
                val wv: WebView? = Tabs.webViewFor(tab)
                if (wv != null) {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    srl.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    srl.setOnChildScrollUpCallback { _, _ -> wv.scrollY > 0 }
                }
                srl.setOnRefreshListener {
                    tab.webView?.reload()
                    srl.isRefreshing = false
                }
                srl
            },
            update = { srl ->
                srl.isEnabled = pull
                srl.setColorSchemeColors(primary)
                srl.setProgressBackgroundColorSchemeColor(bg)
            },
            onRelease = { srl -> srl.removeAllViews() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

@Composable
private fun Suggestions(text: String, current: Tab?, onPick: (String) -> Unit, onFill: (String) -> Unit, onDone: () -> Unit) {
    val s = App.prefs.state
    val ctx = LocalContext.current
    var local by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var remote by remember { mutableStateOf<List<String>>(emptyList()) }
    var sites by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Suggest.refresh() } }
    LaunchedEffect(text, s.hiddenSites) {
        if (text.isBlank()) { local = emptyList(); remote = emptyList(); sites = emptyList(); return@LaunchedEffect }
        sites = if (s.siteSuggestions) Suggest.sites(text) else emptyList()
        delay(90)
        local = if (s.historySuggestions) withContext(Dispatchers.IO) { runCatching { App.db.suggest(text) }.getOrDefault(emptyList()) } else emptyList()
        if (s.suggestions && Tabs.current?.isPrivate != true) {
            delay(200)
            remote = withContext(Dispatchers.IO) { fetchSuggestions(text) }
        } else remote = emptyList()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (text.isBlank() && current != null) {
                item { CurrentPageRow(current, ctx, onFill, onDone) }
            }
            if (text.isNotBlank()) {
                val asUrl = Urls.toUrl(text)
                item {
                    SuggestionRow(
                        icon = if (asUrl != null) AppIcons.OpenInNew else Icons.Filled.Search,
                        title = text, sub = if (asUrl != null) asUrl else SearchEngines.byId(s.engine).name,
                        onClick = { onPick(text) }, onFill = null,
                    )
                }
            }
            items(sites, key = { "s_$it" }) { host ->
                SuggestionRow(AppIcons.OpenInNew, host, null, siteUrl = "https://$host/", onClick = { onPick(host) }, onFill = { onFill(host) }, onLongClick = {
                    App.prefs.update { it.copy(hiddenSites = it.hiddenSites + host) }
                    Suggest.invalidate()
                    Ui.toast("Hidden: $host")
                })
            }
            items(remote, key = { "r_$it" }) { q ->
                SuggestionRow(Icons.Filled.Search, q, null, onClick = { onPick(q) }, onFill = { onFill(q) })
            }
            items(local, key = { "l_" + it.first }) { (url, title) ->
                SuggestionRow(AppIcons.History, title, url, siteUrl = url, onClick = { onPick(url) }, onFill = { onFill(url) }, onLongClick = {
                    App.io { App.db.removeHistoryUrl(url) }
                    local = local.filter { it.first != url }
                    Suggest.invalidate()
                    Ui.toast("Removed from history")
                })
            }
        }
    }
}

/** The open page's address with quick Copy and Share buttons. */
@Composable
private fun CurrentPageRow(tab: Tab, ctx: Context, onFill: (String) -> Unit, onDone: () -> Unit) {
    val url = tab.url
    Row(
        Modifier.fillMaxWidth().clickable { onFill(url) }.padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteIcon(url, 28.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(tab.title.ifBlank { Urls.display(url) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { Browser.copy(ctx, url); Ui.toast("Address copied"); onDone() }) {
            Icon(AppIcons.Copy, "Copy address", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { Browser.share(ctx, url, tab.title); onDone() }) {
            Icon(Icons.Filled.Share, "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Divider()
}

private fun fetchSuggestions(q: String): List<String> = runCatching {
    val u = SearchEngines.suggestUrl(App.prefs.state, q) ?: return emptyList()
    val c = URL(u).openConnection() as HttpURLConnection
    c.connectTimeout = 4000
    c.readTimeout = 4000
    c.setRequestProperty("User-Agent", "Mozilla/5.0")
    val body = c.inputStream.bufferedReader().use { it.readText() }
    val arr = JSONArray(body).getJSONArray(1)
    (0 until minOf(arr.length(), 5)).map { arr.getString(it) }
}.getOrDefault(emptyList())

@Composable
private fun SuggestionRow(icon: ImageVector, title: String, sub: String?, onClick: () -> Unit, onFill: (() -> Unit)?, onLongClick: (() -> Unit)? = null, siteUrl: String? = null) {
    Row(
        Modifier.fillMaxWidth().combinedClickableCompat(onClick = onClick, onLongClick = onLongClick).padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (siteUrl != null) SiteIcon(siteUrl, 28.dp)
        else Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onFill != null) {
            IconButton(onClick = onFill) {
                Icon(Icons.Filled.KeyboardArrowUp, "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Divider()
}

@Composable
private fun FindBar(tab: Tab) {
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    Surface(
        Modifier.fillMaxWidth().padding(10.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.height(52.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = q,
                onValueChange = { q = it; tab.webView?.findAllAsync(it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { tab.webView?.findNext(true) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (q.isEmpty()) Text("Find in page", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    }
                },
            )
            Text(
                if (q.isEmpty()) "" else "${tab.findActive}/${tab.findTotal}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { tab.webView?.findNext(false) }) { Icon(Icons.Filled.KeyboardArrowUp, "Previous") }
            IconButton(onClick = { tab.webView?.findNext(true) }) { Icon(Icons.Filled.KeyboardArrowDown, "Next") }
            IconButton(onClick = { tab.webView?.clearMatches(); Ui.findBar = false }) { Icon(Icons.Filled.Close, "Close") }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuSheet(tab: Tab, onDismiss: () -> Unit, onQuit: () -> Unit) {
    val ctx = LocalContext.current
    var bookmarked by remember { mutableStateOf(false) }
    LaunchedEffect(tab.url) { bookmarked = withContext(Dispatchers.IO) { App.db.isBookmarked(tab.url) } }
    CleanSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { tab.webView?.goBack(); onDismiss() }, enabled = tab.canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            IconButton(onClick = { tab.webView?.goForward(); onDismiss() }, enabled = tab.canForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
            IconButton(onClick = { if (tab.loading) tab.webView?.stopLoading() else tab.webView?.reload(); onDismiss() }, enabled = !tab.isBlank) {
                Icon(if (tab.loading) Icons.Filled.Close else Icons.Filled.Refresh, "Reload")
            }
            IconButton(onClick = {
                val u = tab.url; val t = tab.title
                App.io { if (App.db.isBookmarked(u)) App.db.removeBookmark(u) else App.db.addBookmark(u, t) }
                bookmarked = !bookmarked
                Ui.toast(if (bookmarked) "Bookmarked" else "Bookmark removed")
            }, enabled = !tab.isBlank && Urls.isWeb(tab.url)) {
                Icon(if (bookmarked) AppIcons.Bookmark else AppIcons.BookmarkBorder, "Bookmark",
                    tint = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { Browser.share(ctx, tab.url, tab.title); onDismiss() }, enabled = !tab.isBlank) { Icon(Icons.Filled.Share, "Share") }
            IconButton(onClick = { Browser.newTab(false); onDismiss() }) { Icon(Icons.Filled.Add, "New tab") }
        }
        Spacer(Modifier.height(4.dp))
        Divider()
        MenuItem(AppIcons.Private, "New private tab") { Browser.newTab(true); onDismiss() }
        MenuItem(AppIcons.Bookmark, "Bookmarks") { Nav.open(Screen.BOOKMARKS); onDismiss() }
        MenuItem(AppIcons.History, "History") { Nav.open(Screen.HISTORY); onDismiss() }
        MenuItem(AppIcons.Download, "Downloads") { Nav.open(Screen.DOWNLOADS); onDismiss() }
        MenuItem(AppIcons.FindInPage, "Find in page", enabled = !tab.isBlank) { Ui.findBar = true; onDismiss() }
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !tab.isBlank) { toggleDesktop(tab); onDismiss() }
                .padding(start = 24.dp, end = 20.dp).height(52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Desktop, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(20.dp))
            Text("Desktop site", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = tab.desktop, onCheckedChange = { toggleDesktop(tab); onDismiss() }, enabled = !tab.isBlank)
        }
        Divider()
        MenuItem(AppIcons.Vpn, "VPN & proxy") { Nav.open(Screen.SETTINGS); Nav.settingsStack.add("vpn"); onDismiss() }
        MenuItem(Icons.Filled.Settings, "Settings") { Nav.open(Screen.SETTINGS); onDismiss() }
        MenuItem(Icons.AutoMirrored.Filled.ExitToApp, "Quit") { onDismiss(); onQuit() }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

fun toggleDesktop(tab: Tab) {
    tab.desktop = !tab.desktop
    val wv = tab.webView ?: return
    Engine.userAgent(tab.desktop)?.let { wv.settings.userAgentString = it }
    wv.reload()
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp).height(50.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    }
    Divider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteSheet(tab: Tab, onDismiss: () -> Unit) {
    val host = Urls.host(tab.url)
    var site by remember(host) { mutableStateOf(App.sites.get(host)) }
    val p = App.prefs.state
    fun save(next: com.banner.cleanbrowser.core.SiteSettings) {
        site = next
        App.sites.save(next)
    }
    val blocking = when (site.blocking) { 1 -> true; 0 -> false; else -> p.blockAds }
    val js = when (site.javascript) { 1 -> true; 0 -> false; else -> p.javascript }
    CleanSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(Urls.display(tab.url), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                (if (Urls.isHttps(tab.url)) "Secure connection" else "Not secure") + " · ${tab.blockedCount} blocked",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow("Block ads & trackers", blocking, icon = AppIcons.Shield) {
            save(site.copy(blocking = if (it == p.blockAds) -1 else if (it) 1 else 0))
            tab.webView?.reload()
        }
        SwitchRow("JavaScript", js, icon = Icons.Filled.Build) {
            save(site.copy(javascript = if (it == p.javascript) -1 else if (it) 1 else 0))
            tab.webView?.let { wv -> wv.settings.javaScriptEnabled = it; wv.reload() }
        }
        SwitchRow("Desktop site", tab.desktop, icon = AppIcons.Desktop) {
            save(site.copy(desktop = if (it == p.desktopDefault) -1 else if (it) 1 else 0))
            toggleDesktop(tab)
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

@Composable
fun TabsScreen() {
    val showPrivate = Nav.showPrivateTabs
    val tabs = Tabs.list.filter { it.isPrivate == showPrivate }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { Nav.screen = Screen.BROWSER }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Spacer(Modifier.width(4.dp))
                Pill(listOf("Tabs ${Tabs.list.count { !it.isPrivate }}", "Private ${Tabs.list.count { it.isPrivate }}"), if (showPrivate) 1 else 0) {
                    Nav.showPrivateTabs = it == 1
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { Tabs.closeAll(showPrivate) }, enabled = tabs.isNotEmpty()) { Icon(AppIcons.Stop, "Close all") }
                IconButton(onClick = { Browser.newTab(showPrivate) }) { Icon(Icons.Filled.Add, "New tab") }
            }
            if (tabs.isEmpty()) {
                EmptyState(if (showPrivate) "No private tabs" else "No tabs")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                ) {
                    items(tabs, key = { it.id }) { t -> TabCard(t, Modifier.animateItem()) }
                }
            }
        }
    }
}

@Composable
private fun TabCard(t: Tab, modifier: Modifier) {
    val selected = t.id == Tabs.currentId
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .height(210.dp)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier)
            .clip(MaterialTheme.shapes.medium)
            .clickable { Tabs.select(t); Nav.screen = Screen.BROWSER },
    ) {
        Column {
            Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.title.ifBlank { if (t.isBlank) "New tab" else Urls.display(t.url) },
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { Tabs.close(t) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "Close tab", Modifier.size(18.dp))
                }
            }
            val thumb = t.thumbnail
            Box(
                Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(thumb, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        if (t.isBlank) "" else Urls.display(t.url).take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
fun Pill(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.clip(CircleShape)
                    .background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onPick(i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge,
                    color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun MessageHost() {
    val msg = Ui.message
    LaunchedEffect(msg) {
        if (msg != null) {
            delay(2200)
            Ui.message = null
        }
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 76.dp), contentAlignment = Alignment.BottomCenter) {
        androidx.compose.animation.AnimatedVisibility(
            visible = msg != null,
            enter = fadeIn(Motion.fast()) + slideInVertically(Motion.fast()) { it / 2 },
            exit = fadeOut(Motion.fast()) + slideOutVertically(Motion.fast()) { it / 2 },
        ) {
            var last by remember { mutableStateOf("") }
            if (msg != null) last = msg
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 4.dp) {
                Text(last, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
    }
}
