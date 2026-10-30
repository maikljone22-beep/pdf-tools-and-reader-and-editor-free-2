package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.annot.AnnotStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichSpansTest {
    private val base = AnnotStyle.Rich(12f, 0, bold = false, italic = false)

    @Test fun toggleOnASelectionThenOffAgain() {
        val text = "Hello world"
        var sp = RichSpans.toggle(emptyList(), base, 0, 5, 'b', text.length)
        assertEquals(listOf(RichSpan(0, 5, bold = true)), sp)
        assertTrue(RichSpans.effective(sp, base, 2, 'b')); assertFalse(RichSpans.effective(sp, base, 7, 'b'))
        // a selection that is partly bold becomes all bold; all bold → off (back to the base: no span)
        sp = RichSpans.toggle(sp, base, 3, 8, 'b', text.length)
        assertEquals(listOf(RichSpan(0, 8, bold = true)), sp)
        sp = RichSpans.toggle(sp, base, 0, 8, 'b', text.length)
        assertTrue(sp.isEmpty())
        // italic inside bold splits the span
        sp = RichSpans.toggle(RichSpans.toggle(emptyList(), base, 0, 11, 'b', 11), base, 6, 11, 'i', 11)
        assertEquals(listOf(RichSpan(0, 6, bold = true), RichSpan(6, 11, bold = true, italic = true)), sp)
    }

    @Test fun typingKeepsSpansInPlace() {
        val sp = listOf(RichSpan(6, 11, bold = true))          // "Hello [world]"
        assertEquals(listOf(RichSpan(7, 12, bold = true)), RichSpans.adjust(sp, "Hello world", "Hello, world"))   // insert before
        assertEquals(listOf(RichSpan(6, 12, bold = true)), RichSpans.adjust(sp, "Hello world", "Hello worlds"))   // append inside the span's end
        assertEquals(listOf(RichSpan(6, 12, bold = true)), RichSpans.adjust(sp, "Hello world", "Hello world!"))   // typing at the span end continues it
        assertEquals(listOf(RichSpan(4, 9, bold = true)), RichSpans.adjust(sp, "Hello world", "Hel world"))       // delete before
        assertEquals(listOf(RichSpan(6, 9, bold = true)), RichSpans.adjust(sp, "Hello world", "Hello wod"))       // delete inside
        assertTrue(RichSpans.adjust(sp, "Hello world", "Hello ").isEmpty())                                        // span deleted
    }

    @Test fun bodyAndParseRoundTrip() {
        val text = "Name: Ali\nCity: Aden"
        val sp = listOf(RichSpan(6, 9, bold = true), RichSpan(10, 14, italic = true, underline = true))
        val body = RichSpans.body(text, base, sp, rtl = false)
        assertTrue(body.contains("<p>Name: <span style=\"font-weight:bold\">Ali</span></p>"))
        assertTrue(body.contains("<p><span style=\"font-style:italic;text-decoration:underline\">City</span>: Aden</p>"))
        val (t, back) = RichSpans.parse(body)
        assertEquals(text, t)
        assertEquals(sp, back)
    }

    @Test fun parseAcrobatStyleBody() {
        val rv = "<?xml version=\"1.0\"?><body xmlns=\"http://www.w3.org/1999/xhtml\" style=\"font-size:12pt\"><p style=\"text-align:left\">Plain <b>bold</b> and <span style=\"font-style:italic;font-weight:bold\">both</span> &amp; done</p></body>"
        val (t, sp) = RichSpans.parse(rv)
        assertEquals("Plain bold and both & done", t)
        assertEquals(listOf(RichSpan(6, 10, bold = true), RichSpan(15, 19, bold = true, italic = true)), sp)
    }
}
