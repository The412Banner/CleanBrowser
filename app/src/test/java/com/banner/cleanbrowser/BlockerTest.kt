package com.banner.cleanbrowser

import com.banner.cleanbrowser.block.Blocker
import com.banner.cleanbrowser.block.Matcher
import com.banner.cleanbrowser.block.ReqType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockerTest {
    private fun build(vararg lines: String): Blocker = Blocker.Builder().apply { lines.forEach(::addFilterLine) }.build()

    @Test fun domainRuleBlocksHostAndSubdomains() {
        val b = build("||ads.example.com^")
        assertTrue(b.shouldBlock("https://ads.example.com/x.js", "news.org", ReqType.SCRIPT))
        assertTrue(b.shouldBlock("https://cdn.ads.example.com/x.js", "news.org", ReqType.SCRIPT))
        assertFalse(b.shouldBlock("https://example.com/x.js", "news.org", ReqType.SCRIPT))
        assertFalse(b.shouldBlock("https://badads.example.com/x.js", "news.org", ReqType.SCRIPT))
    }

    @Test fun thirdPartyOption() {
        val b = build("||tracker.net^\$third-party")
        assertTrue(b.shouldBlock("https://tracker.net/p.gif", "shop.com", ReqType.IMAGE))
        assertFalse(b.shouldBlock("https://tracker.net/p.gif", "www.tracker.net", ReqType.IMAGE))
    }

    @Test fun exceptionWins() {
        val b = build("||cdn.com^", "@@||cdn.com/lib.js")
        assertTrue(b.shouldBlock("https://cdn.com/ad.js", "a.org", ReqType.SCRIPT))
        assertFalse(b.shouldBlock("https://cdn.com/lib.js", "a.org", ReqType.SCRIPT))
    }

    @Test fun importantBeatsException() {
        val b = build("||cdn.com^\$important", "@@||cdn.com^")
        assertTrue(b.shouldBlock("https://cdn.com/x", "a.org", ReqType.UNKNOWN))
    }

    @Test fun genericPatternWithToken() {
        val b = build("/adserver/*")
        assertTrue(b.shouldBlock("https://site.com/adserver/banner.png", "site.com", ReqType.IMAGE))
        assertFalse(b.shouldBlock("https://site.com/server/banner.png", "site.com", ReqType.IMAGE))
    }

    @Test fun separatorAndWildcard() {
        val b = build("||example.org/*/track^")
        assertTrue(b.shouldBlock("https://example.org/a/b/track?x=1", "x.com", ReqType.UNKNOWN))
        assertFalse(b.shouldBlock("https://example.org/a/b/tracker", "x.com", ReqType.UNKNOWN))
    }

    @Test fun typeOptions() {
        val b = build("||media.com^\$script")
        assertTrue(b.shouldBlock("https://media.com/a.js", "x.com", ReqType.SCRIPT))
        assertFalse(b.shouldBlock("https://media.com/a.png", "x.com", ReqType.IMAGE))
    }

    @Test fun domainOption() {
        val b = build("||widget.io^\$domain=news.com|~safe.news.com")
        assertTrue(b.shouldBlock("https://widget.io/w.js", "www.news.com", ReqType.SCRIPT))
        assertFalse(b.shouldBlock("https://widget.io/w.js", "safe.news.com", ReqType.SCRIPT))
        assertFalse(b.shouldBlock("https://widget.io/w.js", "other.com", ReqType.SCRIPT))
    }

    @Test fun pageAllowDocument() {
        val b = build("||ads.net^", "@@||trusted.com^\$document")
        assertFalse(b.shouldBlock("https://ads.net/a.js", "trusted.com", ReqType.SCRIPT))
        assertTrue(b.shouldBlock("https://ads.net/a.js", "other.com", ReqType.SCRIPT))
    }

    @Test fun skipsUnsupported() {
        val b = build("example.com##.ad", "||popup.com^\$popup", "/ad[0-9]+/", "! comment", "[Adblock Plus 2.0]")
        assertEquals(0, b.ruleCount)
    }

    @Test fun hostsFormat() {
        val b = Blocker.Builder().apply {
            addHostsLine("0.0.0.0 bad.example")
            addHostsLine("127.0.0.1 localhost")
            addHostsLine("# comment")
        }.build()
        assertEquals(1, b.ruleCount)
        assertTrue(b.shouldBlock("https://bad.example/", "a.com", ReqType.UNKNOWN))
    }

    @Test fun matcher() {
        assertTrue(Matcher.matchAt("a*c^", "abbbc/", 0, false))
        assertTrue(Matcher.matchAt("abc^", "abc", 0, false))
        assertFalse(Matcher.matchAt("abc^", "abcd", 0, false))
        assertTrue(Matcher.matchAt("ab", "abc", 0, false))
        assertFalse(Matcher.matchAt("ab", "abc", 0, true))
    }
}
