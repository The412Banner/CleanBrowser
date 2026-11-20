package com.banner.cleanbrowser

import com.banner.cleanbrowser.core.Urls
import com.banner.cleanbrowser.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsTest {
    @Test fun addressOrSearch() {
        assertEquals("https://example.com", Urls.toUrl("example.com"))
        assertEquals("https://example.com:8080/x", Urls.toUrl("example.com:8080/x"))
        assertEquals("http://a.b", Urls.toUrl("http://a.b"))
        assertEquals("https://192.168.1.1", Urls.toUrl("192.168.1.1"))
        assertNull(Urls.toUrl("how to cook rice"))
        assertNull(Urls.toUrl("hello"))
        assertNull(Urls.toUrl("javascript:alert(1)"))
        assertEquals("mailto:a@b.com", Urls.toUrl("mailto:a@b.com"))
    }

    @Test fun hosts() {
        assertEquals("www.example.com", Urls.host("https://user:pw@WWW.Example.com:443/path?q#f"))
        assertEquals("example.com", Urls.display("https://www.example.com/a"))
        assertEquals("", Urls.host("about:blank"))
    }

    @Test fun sites() {
        assertEquals("example.co.uk", Urls.site("a.b.example.co.uk"))
        assertEquals("example.com", Urls.site("cdn.example.com"))
        assertEquals("me.github.io", Urls.site("x.me.github.io"))
        assertTrue(Urls.isThirdParty("tracker.net", "news.com"))
        assertFalse(Urls.isThirdParty("static.news.com", "www.news.com"))
    }

    @Test fun versions() {
        assertTrue(Updater.isNewer("0.2.0", "0.1.9"))
        assertTrue(Updater.isNewer("0.2.0", "0.2.0-pre3"))
        assertTrue(Updater.isNewer("0.2.0-pre4", "0.2.0-pre3"))
        assertFalse(Updater.isNewer("0.2.0-pre1", "0.2.0"))
        assertFalse(Updater.isNewer("0.1.0", "0.1.0"))
    }
}
