package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebCaptureTest {
    private val page = """<html><head><title>Home</title></head><body>
        <a href="/about">About</a> <a href='docs/intro.html#top'>Intro</a> <a href="https://other.example/x">Other</a>
        <a href="mailto:a@b.c">mail</a> <a href="/about">dup</a> <a href="/file.pdf">pdf</a></body></html>"""

    @Test fun linksResolveAndFilter() {
        val same = WebCapture.links(page, "https://site.example/start/", sameSite = true)
        assertEquals(listOf("https://site.example/about", "https://site.example/start/docs/intro.html"), same)
        val all = WebCapture.links(page, "https://site.example/start/", sameSite = false)
        assertTrue(all.contains("https://other.example/x"))
    }

    @Test fun planIsBreadthFirstAndCapped() {
        val pages = mapOf(
            "https://s.example/" to """<a href="/a">a</a><a href="/b">b</a>""",
            "https://s.example/a" to """<a href="/c">c</a>""",
            "https://s.example/b" to """<a href="/d">d</a>""",
        )
        val plan = WebCapture.plan("s.example/", levels = 2, sameSite = true, maxPages = 4) { pages[it] }
        assertEquals(listOf("https://s.example/" to 0, "https://s.example/a" to 1, "https://s.example/b" to 1, "https://s.example/c" to 2), plan)
        assertEquals(1, WebCapture.plan("https://s.example/", 0, true, 10) { pages[it] }.size)
    }

    @Test fun screenScript() {
        assertNull(WebCapture.screenJs(false, 100))
        assertNotNull(WebCapture.screenJs(true, 100))
        assertTrue(WebCapture.screenJs(false, 150)!!.contains("zoom='150%'"))
    }
}
