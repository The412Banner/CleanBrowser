package com.banner.cleanbrowser.core

import java.net.URLDecoder
import java.net.URLEncoder

data class SearchEngine(val id: String, val name: String, val searchUrl: String, val suggestUrl: String?) {
    val home: String get() = if (searchUrl.isEmpty()) "" else searchUrl.substring(0, searchUrl.indexOf('/', 8) + 1)
}

object SearchEngines {
    val all = listOf(
        SearchEngine("google", "Google", "https://www.google.com/search?q=%s",
            "https://suggestqueries.google.com/complete/search?client=firefox&q=%s"),
        SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s",
            "https://duckduckgo.com/ac/?type=list&q=%s"),
        SearchEngine("brave", "Brave Search", "https://search.brave.com/search?q=%s",
            "https://search.brave.com/api/suggest?q=%s"),
        SearchEngine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s", null),
        SearchEngine("bing", "Bing", "https://www.bing.com/search?q=%s",
            "https://api.bing.com/osjson.aspx?query=%s"),
        SearchEngine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s", null),
        SearchEngine("qwant", "Qwant", "https://www.qwant.com/?q=%s", null),
        SearchEngine("mojeek", "Mojeek", "https://www.mojeek.com/search?q=%s", null),
        SearchEngine("custom", "Custom", "", null),
    )

    fun byId(id: String): SearchEngine = all.firstOrNull { it.id == id } ?: all.first()

    fun searchUrl(s: Settings, query: String): String {
        val e = byId(s.engine)
        val template = if (e.id == "custom") s.customSearchUrl.takeIf { it.contains("%s") } ?: all.first().searchUrl else e.searchUrl
        return template.replace("%s", encode(query))
    }

    fun homeUrl(s: Settings): String {
        val e = byId(s.engine)
        if (e.id != "custom") return e.home
        val u = s.customSearchUrl
        val slash = u.indexOf('/', 8)
        return if (Urls.isWeb(u) && slash > 0) u.substring(0, slash + 1) else ""
    }

    fun suggestUrl(s: Settings, query: String): String? =
        byId(s.engine).suggestUrl?.replace("%s", encode(query))

    private fun encode(q: String): String = URLEncoder.encode(q, "UTF-8")
}

object Urls {
    private val schemeRe = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val hostLike = Regex("^(localhost|[\\p{L}0-9-]+(\\.[\\p{L}0-9-]+)*\\.[\\p{L}]{2,}|\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9a-fA-F:]+])(:\\d{1,5})?([/?#].*)?$")

    /** Turns address-bar text into a URL to load, or null when it should be searched. */
    fun toUrl(input: String): String? {
        val t = unwrap(input)
        if (t.isEmpty() || t.contains(' ')) return null
        val lower = t.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return t
        if (lower.startsWith("about:") || lower.startsWith("magnet:")) return t
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:")) return null
        if (schemeRe.containsMatchIn(t) && !t.contains("://") && !hostLike.matches(t)) {
            // e.g. "mailto:x", "intent:..." stay as they are; "example.com:8080" is a host.
            return t
        }
        if (hostLike.matches(t)) return "https://$t"
        return null
    }

    fun upgradeToHttps(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    fun isHttp(url: String) = url.startsWith("http://", ignoreCase = true)
    fun isHttps(url: String) = url.startsWith("https://", ignoreCase = true)
    fun isWeb(url: String) = isHttp(url) || isHttps(url)

    /** Lower-case host of an http(s)/ws(s) URL without port and user info, or "" when there is none. */
    fun host(url: String): String {
        val start = url.indexOf("://")
        if (start < 0) return ""
        val i = start + 3
        val end = url.length
        var stop = i
        while (stop < end) {
            val c = url[stop]
            if (c == '/' || c == '?' || c == '#') break
            stop++
        }
        var authority = url.substring(i, stop)
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            return if (close > 0) authority.substring(0, close + 1).lowercase() else authority.lowercase()
        }
        val colon = authority.indexOf(':')
        if (colon >= 0) authority = authority.substring(0, colon)
        return authority.trimEnd('.').lowercase()
    }

    /** Short form for the address bar: host without "www.". */
    fun display(url: String): String {
        if (!isWeb(url)) return url
        val h = host(url)
        return if (h.startsWith("www.")) h.substring(4) else h
    }

    private val twoPartSecondLevel = setOf(
        "co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "gob", "mil", "nic",
        "ltd", "plc", "sch", "nhs", "info", "biz", "int", "web", "nom", "gen", "firm", "ind",
    )
    private val privateSuffixes = setOf(
        "github.io", "gitlab.io", "blogspot.com", "appspot.com", "herokuapp.com", "netlify.app",
        "vercel.app", "pages.dev", "workers.dev", "web.app", "firebaseapp.com", "cloudfront.net",
        "azurewebsites.net", "s3.amazonaws.com", "fly.dev", "onrender.com", "glitch.me", "neocities.org",
    )

    /** Approximate registrable domain (eTLD+1) without a full public suffix list. */
    fun site(host: String): String {
        if (host.isEmpty() || host.startsWith("[") || host.all { it.isDigit() || it == '.' }) return host
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val n = labels.size
        val lastTwo = labels[n - 2] + "." + labels[n - 1]
        if (lastTwo in privateSuffixes && n >= 3) return labels.subList(n - 3, n).joinToString(".")
        if (labels[n - 1].length == 2 && labels[n - 2] in twoPartSecondLevel) {
            return labels.subList(n - 3, n).joinToString(".")
        }
        return lastTwo
    }

    fun isThirdParty(requestHost: String, pageHost: String): Boolean {
        if (pageHost.isEmpty() || requestHost.isEmpty()) return false
        return site(requestHost) != site(pageHost)
    }

    private val lineBreak = Regex("\\s*[\\r\\n]+\\s*")

    /** Trims [input]; a pasted link also loses the line breaks (and spaces around them) that copying a wrapped line adds. */
    fun unwrap(input: String): String {
        val t = input.trim()
        val lower = t.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("magnet:"))) return t
        return t.replace(lineBreak, "")
    }

    /**
     * The magnet link in [url]: the link itself, or the one inside a μTorrent / BitTorrent Web
     * share page (…/gui/share.html#link=magnet:…), which only hands it over on desktop systems.
     */
    fun magnetIn(url: String): String? {
        val t = unwrap(url)
        if (t.startsWith("magnet:", ignoreCase = true)) return t
        if (!isWeb(t)) return null
        val noFragment = t.substringBefore('#')
        val path = noFragment.substringBefore('?').substringAfter("://").substringAfter('/', "")
        if (!path.endsWith("gui/share.html", ignoreCase = true)) return null
        val params = t.substringAfter('#', "").split('&') + noFragment.substringAfter('?', "").split('&')
        for (p in params) {
            if (p.substringBefore('=') != "link") continue
            val v = runCatching { URLDecoder.decode(p.substringAfter('=', ""), "UTF-8") }.getOrNull() ?: continue
            if (v.startsWith("magnet:?", ignoreCase = true)) return v
        }
        return null
    }

    /** First http(s) URL inside shared text, if any. */
    fun firstUrlIn(text: String): String? =
        Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE).find(text)?.value
}
