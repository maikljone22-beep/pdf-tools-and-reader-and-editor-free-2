package com.smartdocs.editor.core.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fc-sync: WebDAV version markers (getctag / ETag), changed-link detection and the server presets. */
class DavWatchTest {
    private fun fx(name: String): String = javaClass.classLoader!!.getResource("fc_sync/$name")!!.readText()
    private val req = "https://cloud.example.com/remote.php/dav/files/me/Reports/"

    @Test fun versionsPreferCtagThenEtag() {
        val v = DavXml.parseVersions(fx("dav_versions.xml"), req)
        assertEquals("ctag-77", v[DavXml.normalize(req)])
        assertEquals("\"abc123\"", v[DavXml.normalize("https://cloud.example.com/remote.php/dav/files/me/Reports/Q3%20plan.pdf")])
        assertEquals("W/\"9\"", v[DavXml.normalize("https://cloud.example.com/remote.php/dav/files/me/Reports/old.pdf")])
        assertEquals(3, v.size)
    }

    @Test fun changedLinksIgnoreQuotingAndUnknowns() {
        val a = "https://h/a.pdf"; val b = "https://h/b.pdf"; val c = "https://h/c.pdf"
        val links = mapOf(a to "abc123", b to "\"9\"", c to null)
        val server = mapOf(a to "\"abc123\"", b to "W/\"10\"")
        assertEquals(setOf(b), DavWatch.changedLinks(links, server))     // c: not on the server listing → not flagged
        assertEquals("9", DavWatch.bare("W/\"9\""))
        assertTrue(DavWatch.changedLinks(mapOf(c to null), mapOf(c to "x")).contains(c))
    }

    @Test fun backoff() {
        assertEquals(DavWatch.INTERVAL_MS, DavWatch.nextDelay(0))
        assertEquals(DavWatch.INTERVAL_MS * 2, DavWatch.nextDelay(2))
        assertEquals(DavWatch.MAX_BACKOFF_MS, DavWatch.nextDelay(30))
    }

    @Test fun presets() {
        val egnyte = DavPresets.ALL.first { it.key == "egnyte" }
        assertEquals("https://acme.egnyte.com/webdav/", DavPresets.url(egnyte, "acme", ""))
        assertEquals("https://acme.egnyte.com/webdav/", DavPresets.url(egnyte, "https://Acme.egnyte.com/app", ""))
        assertNull(DavPresets.url(egnyte, "", ""))
        assertNull(DavPresets.url(egnyte, "bad domain!", ""))
        val nc = DavPresets.ALL.first { it.key == "nextcloud" }
        assertNull(DavPresets.url(nc, "cloud.example.com", ""))
        assertEquals("https://cloud.example.com/remote.php/dav/files/ann%20lee/", DavPresets.url(nc, "cloud.example.com", "ann lee"))
        assertEquals("https://webdav.pcloud.com/", DavPresets.url(DavPresets.ALL.first { it.key == "pcloud" }, "", ""))
    }
}
