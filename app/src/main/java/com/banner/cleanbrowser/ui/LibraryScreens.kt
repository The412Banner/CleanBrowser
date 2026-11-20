package com.banner.cleanbrowser.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.browser.Tabs
import com.banner.cleanbrowser.core.Bookmark
import com.banner.cleanbrowser.core.HistoryItem
import com.banner.cleanbrowser.core.Urls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

private fun openInCurrent(url: String) {
    val tab = Tabs.current
    if (tab == null) Tabs.open(url, false) else Tabs.load(tab, url)
    Nav.screen = Screen.BROWSER
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, hint: String) {
    Surface(
        shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(44.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp)) {
            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, "Clear", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SiteRow(url: String, title: String, meta: String?, onClick: () -> Unit, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteIcon(url, 38.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(Urls.display(url), meta).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
    Divider()
}

@Composable
fun BookmarksScreen() {
    BackHandler { Nav.back() }
    var query by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    LaunchedEffect(query, version) { items = withContext(Dispatchers.IO) { App.db.bookmarks(query) } }
    ScreenScaffold("Bookmarks", onBack = { Nav.back() }) {
        Column(Modifier.fillMaxSize()) {
            SearchField(query, { query = it }, "Search bookmarks")
            if (items.isEmpty()) EmptyState(if (query.isEmpty()) "No bookmarks" else "Nothing found")
            else LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(items, key = { it.id }) { b ->
                    SiteRow(b.url, b.title, null, onClick = { openInCurrent(b.url) }) {
                        IconButton(onClick = { editing = b }) { Icon(Icons.Filled.Edit, "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = {
                            App.io { App.db.removeBookmark(b.url) }
                            items = items - b
                        }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    editing?.let { b ->
        var title by remember(b) { mutableStateOf(b.title) }
        var url by remember(b) { mutableStateOf(b.url) }
        CleanDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit bookmark") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(url, { url = it }, label = { Text("Address") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = title.trim(); val u = url.trim()
                    App.io { App.db.renameBookmark(b.id, t.ifEmpty { u }, u); }
                    editing = null
                    version++
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

private fun dayLabel(ts: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
    val days = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && days == 0 -> "Today"
        sameYear && days == 1 -> "Yesterday"
        else -> DateUtils.formatDateTime(App.instance, ts, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
}

@Composable
fun HistoryScreen() {
    BackHandler { Nav.back() }
    var query by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(query, version) { items = withContext(Dispatchers.IO) { App.db.history(query) } }
    ScreenScaffold("History", onBack = { Nav.back() }, actions = {
        IconButton(onClick = { confirm = true }, enabled = items.isNotEmpty()) { Icon(Icons.Filled.Delete, "Clear history") }
    }) {
        Column(Modifier.fillMaxSize()) {
            SearchField(query, { query = it }, "Search history")
            if (items.isEmpty()) EmptyState(if (query.isEmpty()) "No history" else "Nothing found")
            else LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                var lastDay = ""
                items.forEach { h ->
                    val day = dayLabel(h.visited)
                    if (day != lastDay) {
                        lastDay = day
                        item(key = "d_$day") {
                            Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
                        }
                    }
                    item(key = h.id) {
                        val time = DateUtils.formatDateTime(App.instance, h.visited, DateUtils.FORMAT_SHOW_TIME)
                        SiteRow(h.url, h.title, time, onClick = { openInCurrent(h.url) }) {
                            IconButton(onClick = {
                                App.io { App.db.removeHistory(h.id) }
                                items = items - h
                            }) { Icon(Icons.Filled.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
    if (confirm) {
        CleanDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear all history?") },
            confirmButton = { TextButton(onClick = { confirm = false; App.io { App.db.clearHistory() }; items = emptyList() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
