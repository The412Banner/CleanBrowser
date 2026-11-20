package com.banner.cleanbrowser.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.banner.cleanbrowser.App
import com.banner.cleanbrowser.core.Favicons
import com.banner.cleanbrowser.core.Urls

/** Round site badge: the site's own icon when we have it, otherwise its first letter. */
@Composable
fun SiteIcon(url: String, size: Dp, modifier: Modifier = Modifier) {
    val host = remember(url) { Favicons.key(url) }
    val enabled = App.prefs.state.siteIcons
    var img by remember(host, enabled) { mutableStateOf(if (enabled) Favicons.cached(host) else null) }
    LaunchedEffect(host, enabled) { if (enabled && img == null) img = Favicons.load(host) }
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(outlineStroke(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val i = img
        if (i != null) {
            Image(i, null, contentScale = ContentScale.Fit, modifier = Modifier.size(size * 0.6f).clip(RoundedCornerShape(size * 0.12f)))
        } else {
            Text(
                Urls.display(url).take(1).uppercase(),
                color = MaterialTheme.colorScheme.primary,
                fontSize = (size.value * 0.38f).sp,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
