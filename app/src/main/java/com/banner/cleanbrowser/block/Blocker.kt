package com.banner.cleanbrowser.block

import com.banner.cleanbrowser.core.Urls

enum class ReqType(val bit: Int) {
    DOCUMENT(1), SUBDOCUMENT(2), SCRIPT(4), IMAGE(8), STYLESHEET(16), FONT(32),
    MEDIA(64), XHR(128), PING(256), WEBSOCKET(512), OTHER(1024), UNKNOWN(0)
}

/**
 * One network filter (Adblock Plus syntax subset).
 * [body] is the pattern without leading "||"/"|" and trailing "|", lower case.
 */
class Rule(
    val body: String,
    val domainAnchor: Boolean,
    val startAnchor: Boolean,
    val endAnchor: Boolean,
    val thirdParty: Int,          // 0 any, 1 third-party only, -1 first-party only
    val types: Int,               // 0 = any type
    val notTypes: Int,
    val includeDomains: Array<String>?,
    val excludeDomains: Array<String>?,
    val important: Boolean,
) {
    fun optionsMatch(host: String, pageHost: String, type: ReqType, third: Boolean): Boolean {
        if (thirdParty == 1 && !third) return false
        if (thirdParty == -1 && third) return false
        if (types != 0 && type != ReqType.UNKNOWN && (types and type.bit) == 0) return false
        if (notTypes != 0 && (notTypes and type.bit) != 0) return false
        if (includeDomains != null && (pageHost.isEmpty() || includeDomains.none { hostMatches(pageHost, it) })) return false
        if (excludeDomains != null && excludeDomains.any { hostMatches(pageHost, it) }) return false
        return true
    }

    fun patternMatches(url: String, hostStart: Int, hostEnd: Int): Boolean {
        if (domainAnchor) {
            var s = hostStart
            while (s < hostEnd) {
                if (Matcher.matchAt(body, url, s, endAnchor)) return true
                val dot = url.indexOf('.', s)
                if (dot < 0 || dot >= hostEnd) break
                s = dot + 1
            }
            return false
        }
        if (startAnchor) return Matcher.matchAt(body, url, 0, endAnchor)
        // Unanchored: start at each place the first literal run occurs.
        val lit = firstLiteral
        if (lit.isEmpty()) {
            for (s in 0..url.length) if (Matcher.matchAt(body, url, s, endAnchor)) return true
            return false
        }
        var from = 0
        while (true) {
            val at = url.indexOf(lit, from)
            if (at < 0) return false
            val s = at - firstLiteralOffset
            if (s >= 0 && Matcher.matchAt(body, url, s, endAnchor)) return true
            from = at + 1
        }
    }

    // The first run of plain characters in the pattern, used to find start positions quickly.
    private val firstLiteralOffset: Int
    private val firstLiteral: String

    init {
        var i = 0
        while (i < body.length && (body[i] == '*' || body[i] == '^')) i++
        if (i > 0 && body.substring(0, i).contains('*')) {
            firstLiteralOffset = -1
            firstLiteral = ""
        } else {
            var j = i
            while (j < body.length && body[j] != '*' && body[j] != '^') j++
            firstLiteralOffset = i
            firstLiteral = body.substring(i, j)
        }
    }

    companion object {
        fun hostMatches(host: String, domain: String): Boolean =
            host == domain || (host.length > domain.length && host.endsWith(domain) && host[host.length - domain.length - 1] == '.')
    }
}

object Matcher {
    fun isSeparator(c: Char): Boolean = !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

    /** ABP wildcard match of [p] against [u] starting at [start]; '*' any run, '^' separator or end. */
    fun matchAt(p: String, u: String, start: Int, endAnchor: Boolean): Boolean {
        var pi = 0
        var ui = start
        var starP = -1
        var starU = -1
        while (true) {
            if (pi == p.length) {
                if (!endAnchor || ui == u.length) return true
            } else {
                val c = p[pi]
                if (c == '*') {
                    starP = pi; starU = ui; pi++; continue
                }
                if (c == '^') {
                    if (ui == u.length) { pi++; continue }
                    if (isSeparator(u[ui])) { pi++; ui++; continue }
                } else if (ui < u.length && u[ui] == c) {
                    pi++; ui++; continue
                }
            }
            if (starP >= 0 && starU < u.length) {
                starU++; ui = starU; pi = starP + 1; continue
            }
            return false
        }
    }
}

/** Immutable, thread-safe filter set. Build with [Blocker.Builder]. */
class Blocker private constructor(
    private val blockHosts: HashSet<String>,
    private val blockHostRules: HashMap<String, MutableList<Rule>>,
    private val blockTokens: HashMap<String, MutableList<Rule>>,
    private val blockNoToken: List<Rule>,
    private val allowHostRules: HashMap<String, MutableList<Rule>>,
    private val allowTokens: HashMap<String, MutableList<Rule>>,
    private val allowNoToken: List<Rule>,
    private val pageAllow: List<Rule>,
    val ruleCount: Int,
) {
    /** True when the request should be blocked. [pageHost] is the host of the top page. */
    fun shouldBlock(url: String, pageHost: String, type: ReqType): Boolean {
        if (ruleCount == 0) return false
        val u = url.lowercase()
        val hs = u.indexOf("://")
        if (hs < 0) return false
        val host = Urls.host(u)
        if (host.isEmpty()) return false
        val hostStart = u.indexOf(host, hs + 3).takeIf { it >= 0 } ?: return false
        val hostEnd = hostStart + host.length
        val third = Urls.isThirdParty(host, pageHost)

        var hit = false
        var important = false

        // Whole-domain rules.
        forEachSuffix(host) { suffix ->
            if (!hit && blockHosts.contains(suffix)) hit = true
            blockHostRules[suffix]?.forEach { r ->
                if (r.optionsMatch(host, pageHost, type, third)) {
                    hit = true
                    if (r.important) important = true
                }
            }
        }
        // Pattern rules.
        if (!important) {
            val check: (Rule) -> Unit = { r ->
                if ((!hit || r.important) && r.optionsMatch(host, pageHost, type, third) && r.patternMatches(u, hostStart, hostEnd)) {
                    hit = true
                    if (r.important) important = true
                }
            }
            forEachToken(u) { t -> blockTokens[t]?.forEach(check) }
            blockNoToken.forEach(check)
        }
        if (!hit) return false
        if (important) return true

        // Page-level allow (@@...$document) and request exceptions.
        if (pageHost.isNotEmpty() && pageAllow.any { Rule.hostMatches(pageHost, it.body.trimEnd('^')) }) return false
        var allowed = false
        forEachSuffix(host) { suffix ->
            if (!allowed) allowHostRules[suffix]?.forEach { r -> if (r.optionsMatch(host, pageHost, type, third)) allowed = true }
        }
        if (allowed) return false
        val checkAllow: (Rule) -> Unit = { r ->
            if (!allowed && r.optionsMatch(host, pageHost, type, third) && r.patternMatches(u, hostStart, hostEnd)) allowed = true
        }
        forEachToken(u) { t -> if (!allowed) allowTokens[t]?.forEach(checkAllow) }
        if (!allowed) allowNoToken.forEach(checkAllow)
        return !allowed
    }

    private inline fun forEachSuffix(host: String, f: (String) -> Unit) {
        var s = host
        while (true) {
            f(s)
            val dot = s.indexOf('.')
            if (dot < 0) break
            s = s.substring(dot + 1)
        }
    }

    private inline fun forEachToken(u: String, f: (String) -> Unit) {
        var i = 0
        val n = u.length
        while (i < n) {
            while (i < n && !isTokenChar(u[i])) i++
            val s = i
            while (i < n && isTokenChar(u[i])) i++
            if (i - s >= 2) f(u.substring(s, i))
        }
    }

    class Builder {
        private val blockHosts = HashSet<String>()
        private val blockHostRules = HashMap<String, MutableList<Rule>>()
        private val blockTokens = HashMap<String, MutableList<Rule>>()
        private val blockNoToken = ArrayList<Rule>()
        private val allowHostRules = HashMap<String, MutableList<Rule>>()
        private val allowTokens = HashMap<String, MutableList<Rule>>()
        private val allowNoToken = ArrayList<Rule>()
        private val pageAllow = ArrayList<Rule>()
        private var count = 0

        /** Adds one line of an Adblock Plus style list. Unsupported lines are skipped. */
        fun addFilterLine(raw: String) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '!' || line[0] == '[' || line[0] == '#') return
            if (line.contains("##") || line.contains("#@#") || line.contains("#?#") ||
                line.contains("#$#") || line.contains("#%#") || line.contains("#+js")) return
            val allow = line.startsWith("@@")
            var text = if (allow) line.substring(2) else line
            var opts = ""
            val dollar = text.lastIndexOf('$')
            if (dollar >= 0 && dollar < text.length - 1 && !text.substring(dollar + 1).contains('/')) {
                opts = text.substring(dollar + 1)
                text = text.substring(0, dollar)
            }
            var thirdParty = 0
            var types = 0
            var notTypes = 0
            var include: MutableList<String>? = null
            var exclude: MutableList<String>? = null
            var important = false
            var document = false
            if (opts.isNotEmpty()) {
                for (o0 in opts.split(',')) {
                    val o = o0.trim().lowercase()
                    val neg = o.startsWith("~")
                    val name = o.removePrefix("~").substringBefore('=')
                    when (name) {
                        "third-party", "3p" -> thirdParty = if (neg) -1 else 1
                        "first-party", "1p" -> thirdParty = if (neg) 1 else -1
                        "important" -> important = true
                        "match-case" -> {}
                        "domain", "from" -> {
                            for (d in o.substringAfter('=').split('|')) {
                                if (d.isEmpty()) continue
                                if (d.startsWith("~")) (exclude ?: ArrayList<String>().also { exclude = it }).add(d.substring(1))
                                else (include ?: ArrayList<String>().also { include = it }).add(d)
                            }
                        }
                        "document", "doc" -> if (neg) return else document = true
                        else -> {
                            val bit = typeBit(name) ?: return // unknown or unsupported option: skip the rule
                            if (neg) notTypes = notTypes or bit else types = types or bit
                        }
                    }
                }
            }
            if (document && !allow) return
            if (text.length > 2 && text.startsWith("/") && text.endsWith("/")) return // regular expression filter
            text = text.lowercase()
            var domainAnchor = false
            var startAnchor = false
            var endAnchor = false
            if (text.startsWith("||")) { domainAnchor = true; text = text.substring(2) }
            else if (text.startsWith("|")) { startAnchor = true; text = text.substring(1) }
            if (text.endsWith("|")) { endAnchor = true; text = text.dropLast(1) }
            while (text.startsWith("*") && !domainAnchor && !startAnchor) text = text.substring(1)
            if (text.isEmpty() || text.all { it == '*' || it == '^' }) return
            if (!domainAnchor && !startAnchor && text.length < 3) return

            val rule = Rule(
                text, domainAnchor, startAnchor, endAnchor, thirdParty, types, notTypes,
                include?.toTypedArray(), exclude?.toTypedArray(), important,
            )
            count++
            if (document) {
                if (domainAnchor && isPlainHost(text.trimEnd('^'))) pageAllow.add(rule)
                return
            }
            val plainHost = domainAnchor && !endAnchor && isPlainHost(text.removeSuffix("^"))
            if (plainHost) {
                val h = text.removeSuffix("^")
                if (allow) {
                    allowHostRules.getOrPut(h) { ArrayList(1) }.add(rule)
                } else if (opts.isEmpty()) {
                    blockHosts.add(h)
                } else {
                    blockHostRules.getOrPut(h) { ArrayList(1) }.add(rule)
                }
                return
            }
            val token = bestToken(text, domainAnchor || startAnchor, endAnchor)
            if (allow) {
                if (token == null) allowNoToken.add(rule) else allowTokens.getOrPut(token) { ArrayList(1) }.add(rule)
            } else {
                if (token == null) blockNoToken.add(rule) else blockTokens.getOrPut(token) { ArrayList(1) }.add(rule)
            }
        }

        /** Adds one line of a hosts file ("0.0.0.0 example.com") or a plain domain list. */
        fun addHostsLine(raw: String) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return
            val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
            val names = if (parts.size >= 2 && looksLikeIp(parts[0])) parts.drop(1) else if (parts.size == 1) parts else return
            for (n0 in names) {
                val n = n0.lowercase().trimEnd('.')
                if (n in ignoredHosts || !n.contains('.') || !isPlainHost(n)) continue
                if (blockHosts.add(n)) count++
            }
        }

        fun build() = Blocker(
            blockHosts, blockHostRules, blockTokens, blockNoToken,
            allowHostRules, allowTokens, allowNoToken, pageAllow, count,
        )
    }

    companion object {
        val EMPTY: Blocker = Builder().build()

        private val ignoredHosts = setOf(
            "localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost",
            "ip6-loopback", "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters", "0.0.0.0",
        )

        fun isTokenChar(c: Char): Boolean = (c in 'a'..'z') || (c in '0'..'9') || c == '%'

        private fun isPlainHost(s: String): Boolean =
            s.isNotEmpty() && s.all { (it in 'a'..'z') || (it in '0'..'9') || it == '.' || it == '-' } && !s.startsWith(".")

        private fun looksLikeIp(s: String) = s == "0.0.0.0" || s == "127.0.0.1" || s == "::" || s == "::1" || s == "0"

        private fun typeBit(name: String): Int? = when (name) {
            "script" -> ReqType.SCRIPT.bit
            "image" -> ReqType.IMAGE.bit
            "stylesheet", "css" -> ReqType.STYLESHEET.bit
            "font" -> ReqType.FONT.bit
            "media" -> ReqType.MEDIA.bit
            "xmlhttprequest", "xhr" -> ReqType.XHR.bit
            "subdocument", "frame" -> ReqType.SUBDOCUMENT.bit
            "ping", "beacon" -> ReqType.PING.bit
            "websocket" -> ReqType.WEBSOCKET.bit
            "other", "object", "object-subrequest" -> ReqType.OTHER.bit
            else -> null
        }

        /**
         * Longest run of token characters that is bounded on both sides in the pattern,
         * so it is guaranteed to appear as a whole token in any matching URL.
         */
        internal fun bestToken(p: String, anchoredStart: Boolean, endAnchor: Boolean): String? {
            var best: String? = null
            var i = 0
            val n = p.length
            while (i < n) {
                while (i < n && !isTokenChar(p[i])) i++
                val s = i
                while (i < n && isTokenChar(p[i])) i++
                if (i - s < 2) continue
                val leftOk = if (s == 0) anchoredStart else p[s - 1] != '*'
                val rightOk = if (i == n) endAnchor else p[i] != '*'
                if (leftOk && rightOk) {
                    val t = p.substring(s, i)
                    if (best == null || t.length > best.length) best = t
                }
            }
            return best
        }
    }
}
