package com.smartdocs.editor.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocSecurityTest {
    private val sites = listOf("example.com", "intranet.local")

    @Test fun hostOfWebAddresses() {
        assertEquals("www.example.com", InternetAccess.hostOf("https://WWW.Example.com/path?q=1#x"))
        assertEquals("host.org", InternetAccess.hostOf("http://user:pw@host.org:8080/a"))
        assertEquals("bare.net", InternetAccess.hostOf("bare.net/page"))
        assertEquals("ftp.site.io", InternetAccess.hostOf("ftp://ftp.site.io"))
        assertEquals("example.com", InternetAccess.hostOf("Example.com:8080/x"))     // opened as https by the reader
        assertNull(InternetAccess.hostOf("mailto:a@b.com"))
        assertNull(InternetAccess.hostOf(""))
    }

    @Test fun normalizeUserInput() {
        assertEquals("example.com", InternetAccess.normalizeHost(" https://Example.com/x "))
        assertEquals("example.com", InternetAccess.normalizeHost("example.com."))
        assertEquals("example.com", InternetAccess.normalizeHost("example.com:443"))
        assertNull(InternetAccess.normalizeHost("bad host"))
        assertNull(InternetAccess.normalizeHost("a..b"))
        assertNull(InternetAccess.normalizeHost("   "))
    }

    @Test fun trustedMatchesSubdomainsOnly() {
        assertTrue(InternetAccess.trusted("example.com", sites))
        assertTrue(InternetAccess.trusted("www.example.com", sites))
        assertFalse(InternetAccess.trusted("badexample.com", sites))
        assertFalse(InternetAccess.trusted("example.com.evil.org", sites))
    }

    @Test fun decideByMode() {
        val url = "https://other.org/form"
        assertEquals(NetDecision.ALLOW, InternetAccess.decide(NetMode.ALLOW_ALL, emptyList(), url))
        assertEquals(NetDecision.BLOCK, InternetAccess.decide(NetMode.BLOCK_ALL, sites, "https://example.com"))
        assertEquals(NetDecision.ASK, InternetAccess.decide(NetMode.ASK, sites, url))
        assertEquals(NetDecision.ALLOW, InternetAccess.decide(NetMode.ASK, sites, "http://docs.example.com/a"))
        assertEquals(NetDecision.ASK, InternetAccess.decide(NetMode.ASK, sites, "www.unknown.com"))
        // hand-over schemes are not Internet access
        assertEquals(NetDecision.ALLOW, InternetAccess.decide(NetMode.BLOCK_ALL, emptyList(), "mailto:x@y.z"))
        assertEquals(NetDecision.ALLOW, InternetAccess.decide(NetMode.ASK, emptyList(), "tel:123"))
        // a bare host with a port is still a web address, not a scheme
        assertEquals(NetDecision.BLOCK, InternetAccess.decide(NetMode.BLOCK_ALL, emptyList(), "evil.org:8080/x"))
        assertEquals(NetDecision.ALLOW, InternetAccess.decide(NetMode.ASK, sites, "www.example.com:8443/x"))
    }

    @Test fun launchPolicy() {
        for (n in listOf("setup.exe", "run.BAT", "x.cmd", "a.com", "s.scr", "i.msi", "evil.js", "v.vbs", "p.ps1", "app.apk", "dir/sub\\tool.exe", "trick.exe. "))
            assertEquals(n, LaunchVerdict.BLOCKED_EXECUTABLE, LaunchPolicy.check(n, blockAll = false))
        assertEquals(LaunchVerdict.ALLOWED_WITH_WARNING, LaunchPolicy.check("report.docx", blockAll = false))
        assertEquals(LaunchVerdict.ALLOWED_WITH_WARNING, LaunchPolicy.check("other.pdf", blockAll = false))
        assertEquals(LaunchVerdict.BLOCKED_BY_SETTING, LaunchPolicy.check("other.pdf", blockAll = true))
        assertEquals(LaunchVerdict.BLOCKED_EXECUTABLE, LaunchPolicy.check("setup.exe", blockAll = true))
        assertFalse(LaunchPolicy.isExecutable("notes"))
    }
}
