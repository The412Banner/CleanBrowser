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

    @Test fun magnets() {
        val m = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Example.File&tr=udp%3a%2f%2ftracker.example.org%3a1337%2fannounce"
        val enc = "magnet%3A%3Fxt%3Durn%3Abtih%3A0123456789abcdef0123456789abcdef01234567%26dn%3DExample.File" +
            "%26tr%3Dudp%253a%252f%252ftracker.example.org%253a1337%252fannounce"
        assertEquals(m, Urls.magnetIn(m))
        assertEquals(m, Urls.magnetIn("https://utweb.rainberrytv.com/gui/share.html#link=$enc"))
        assertEquals(m, Urls.magnetIn("https://btweb.rainberrytv.com/gui/share.html?link=$enc"))
        assertEquals(m, Urls.magnetIn("http://127.0.0.1:19575/gui/share.html#x=1&link=$enc"))
        assertNull(Urls.magnetIn("https://webtor.example/#/show?magnet=$enc"))
        assertNull(Urls.magnetIn("https://example.com/?link=$enc"))
        assertNull(Urls.magnetIn("https://utweb.rainberrytv.com/gui/share.html#link=https%3A%2F%2Fexample.com"))
        assertNull(Urls.magnetIn("https://example.com/"))
        assertEquals(m, Urls.toUrl(m))
        // A link copied from a wrapped line: line break + indent inside the hash.
        assertEquals(m, Urls.magnetIn(m.substring(0, 35) + "\n  " + m.substring(35)))
        assertEquals(m, Urls.magnetIn("https://utweb.rainberrytv.com/gui/share.html#link=" + enc.substring(0, 50) + "\r\n " + enc.substring(50)))
    }

    @Test fun wrappedLinks() {
        assertEquals("https://example.com/abc", Urls.toUrl("https://example.com/a\n  bc"))
        assertEquals("https://example.com/abc", Urls.unwrap("  https://example.com/ab\nc "))
        assertEquals("two\nlines", Urls.unwrap("two\nlines"))
        assertNull(Urls.toUrl("hello\nworld"))
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
