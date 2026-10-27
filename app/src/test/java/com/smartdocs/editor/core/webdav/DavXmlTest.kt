package com.smartdocs.editor.core.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** nav-attach: PROPFIND / LOCK parsing and URL helpers of the WebDAV place. */
class DavXmlTest {

    private val listing = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
  <d:response>
    <d:href>/remote.php/dav/files/me/Docs/</d:href>
    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getlastmodified>Tue, 27 Oct 2026 10:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/me/Docs/My%20Report.pdf</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype/>
        <d:getcontentlength>12345</d:getcontentlength>
        <d:getlastmodified>Mon, 26 Oct 2026 08:30:00 GMT</d:getlastmodified>
        <d:getetag>"abc123"</d:getetag>
        <d:getcontenttype>application/pdf</d:getcontenttype>
        <d:lockdiscovery><d:activelock><d:locktype><d:write/></d:locktype><d:lockscope><d:exclusive/></d:lockscope><d:locktoken><d:href>opaquelocktoken:1234</d:href></d:locktoken></d:activelock></d:lockdiscovery>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
    <d:propstat><d:prop><d:displayname/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
  </d:response>
  <d:response>
    <d:href>https://cloud.example.com/remote.php/dav/files/me/Docs/Sub/</d:href>
    <d:propstat><d:prop><d:displayname>Sub folder</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
</d:multistatus>"""

    private val base = "https://cloud.example.com/remote.php/dav/files/me/Docs/"

    @Test fun parsesAListing() {
        val all = DavXml.parseMultistatus(listing, base)
        assertEquals(3, all.size)
        val kids = DavXml.children(all, base)
        assertEquals(listOf("Sub folder", "My Report.pdf"), kids.map { it.name })
        val pdf = kids[1]
        assertFalse(pdf.isFolder)
        assertEquals(12345L, pdf.size)
        assertEquals("\"abc123\"", pdf.etag)
        assertEquals("application/pdf", pdf.contentType)
        assertEquals("opaquelocktoken:1234", pdf.lockedBy)
        assertEquals("https://cloud.example.com/remote.php/dav/files/me/Docs/My%20Report.pdf", pdf.url)
        assertEquals(1793003400000L, pdf.modified)
        assertTrue(kids[0].isFolder)
    }

    @Test fun lockToken() {
        assertEquals("opaquelocktoken:x-1", DavXml.lockToken("<opaquelocktoken:x-1>", null))
        val body = """<?xml version="1.0"?><D:prop xmlns:D="DAV:"><D:lockdiscovery><D:activelock><D:locktoken><D:href>urn:uuid:9f</D:href></D:locktoken></D:activelock></D:lockdiscovery></D:prop>"""
        assertEquals("urn:uuid:9f", DavXml.lockToken(null, body))
        assertNull(DavXml.lockToken(null, ""))
        assertTrue(DavXml.supportsLocking("1, 2, access-control"))
        assertFalse(DavXml.supportsLocking("1"))
        assertFalse(DavXml.supportsLocking(null))
    }

    @Test fun rejectsDoctypes() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"><d:response><d:href>&e;</d:href></d:response></d:multistatus>"""
        val r = runCatching { DavXml.parseMultistatus(evil, base) }
        assertTrue("a DOCTYPE is refused or never expanded", r.isFailure || r.getOrThrow().none { it.url.contains("root:") })
    }

    @Test fun urls() {
        assertEquals("https://h/a/b%20c.pdf", DavXml.childUrl("https://h/a/", "b c.pdf"))
        assertEquals("https://h/a/%D9%85%D9%84%D9%81/", DavXml.childUrl("https://h/a", "ملف", folder = true))
        assertEquals("https://h/a/", DavXml.parentOf("https://h/a/b.pdf"))
        assertEquals("https://h/a/", DavXml.parentOf("https://h/a/sub/"))
        assertEquals("b c.pdf", DavXml.nameOf("https://h/a/b%20c.pdf"))
        assertEquals("a+b.pdf", DavXml.nameOf("https://h/a+b.pdf"))
        assertEquals("sub/x.pdf", DavXml.relativePath("https://H/dav/", "https://h/dav/sub/x.pdf"))
        assertEquals("https://h/x/y", DavXml.resolve("https://h/dav/", "/x/y"))
        assertEquals("https://h/dav/my%20file", DavXml.resolve("https://h/dav/", "/dav/my file"))
        assertEquals("https://dav.example.com/", DavXml.cleanPlaceUrl("dav.example.com"))
        assertEquals("http://nas.local:5005/home/", DavXml.cleanPlaceUrl("webdav://nas.local:5005/home"))
        assertEquals("https://nas/x/", DavXml.cleanPlaceUrl("davs://nas/x/"))
        assertNull(DavXml.cleanPlaceUrl("ftp://x/"))
        assertNull(DavXml.cleanPlaceUrl("  "))
        assertEquals(listOf("Home" to "https://h/d/", "a" to "https://h/d/a/", "b" to "https://h/d/a/b/"), DavXml.crumbs("https://h/d/", "https://h/d/a/b/", "Home"))
        assertEquals("&lt;a&gt; &amp; &quot;", DavXml.escape("<a> & \""))
    }

    @Test fun dates() {
        assertEquals(784111777000L, DavXml.parseHttpDate("Sun, 06 Nov 1994 08:49:37 GMT"))
        assertEquals(0L, DavXml.parseHttpDate("not a date"))
        assertEquals(0L, DavXml.parseHttpDate(null))
    }

    @Test fun openDecisionAndConflictName() {
        val l = DavLink("p", "https://h/a.pdf", etag = "\"1\"")
        assertEquals(DavLinks.OpenAction.DOWNLOAD, DavLinks.openAction(false, l, "\"1\""))
        assertEquals(DavLinks.OpenAction.REUSE, DavLinks.openAction(true, l, "\"1\""))
        assertEquals(DavLinks.OpenAction.DOWNLOAD, DavLinks.openAction(true, l, "\"2\""))
        assertEquals(DavLinks.OpenAction.KEEP_LOCAL, DavLinks.openAction(true, l.copy(dirty = true), "\"2\""))
        assertEquals(DavLinks.OpenAction.DOWNLOAD, DavLinks.openAction(true, null, null))
        val n = DavLinks.conflictName("Report.pdf", 0L)
        assertTrue(n, n.startsWith("Report (SmartDocs ") && n.endsWith(").pdf"))
        assertFalse(File(n).name.contains('/'))
    }
}
