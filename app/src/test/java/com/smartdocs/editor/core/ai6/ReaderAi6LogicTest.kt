package com.smartdocs.editor.core.ai6

import com.smartdocs.editor.core.ai.AiEditPatterns
import com.smartdocs.editor.core.ai.DocCmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAi6LogicTest {

    // ---------------- B1 quote locator ----------------

    private val page = "مقدمة\r\nتأسست الشركة في عام 1998\r\nفي مدينة عدن. وتعمل في الشحن."

    @Test fun locatesQuoteAcrossLineBreaksDiacriticsAndDigits() {
        val r = QuoteLocator.locate(page, "تأسَّست الشركة في عام ١٩٩٨ في مدينة عدن")
        assertNotNull(r)
        assertEquals(page.indexOf("تأسست"), r!!.first)
        assertEquals(page.indexOf("عدن") + 2, r.last)
    }

    @Test fun fallsBackToPrefixWhenTailIsParaphrased() {
        val r = QuoteLocator.locate(page, "تأسست الشركة في عام 1998 وهي شركة كبيرة جدا")
        assertNotNull(r)
        assertEquals(page.indexOf("تأسست"), r!!.first)
    }

    @Test fun returnsNullWhenAbsent() {
        assertNull(QuoteLocator.locate(page, "The board approved the merger in March"))
        assertNull(QuoteLocator.locate(page, ""))
    }

    // ---------------- B10 patterns ----------------

    @Test fun redactRangesByKindAndCustomRegex() {
        val t = "Mail me at a.b@x.com or call +967 777 123 456 on 12/05/2024"
        val email = AiEditPatterns.redactRanges(t, "email", null)
        assertEquals(listOf("a.b@x.com"), email.map { t.substring(it.first, it.last + 1) })
        assertEquals(1, AiEditPatterns.redactRanges(t, "التواريخ", null).size)
        assertEquals(2, AiEditPatterns.redactRanges("ab-123 cd-456", "custom", "[a-z]{2}-\\d{3}").size)
        assertTrue(AiEditPatterns.redactRanges(t, "all", null).size >= 2)
        try { AiEditPatterns.redactRanges(t, "unknown-kind", null); throw AssertionError("expected failure") } catch (_: IllegalArgumentException) {}
        try { AiEditPatterns.regexRanges(t, "([unclosed"); throw AssertionError("expected failure") } catch (_: IllegalArgumentException) {}
    }

    @Test fun highlightRangesColorsAndCommands() {
        assertEquals(2, AiEditPatterns.highlightRanges("in 2021 and 2022", "20\\d\\d", isRegex = true).size)
        assertEquals(1, AiEditPatterns.highlightRanges("كتب أحمد التقرير", "احمد", isRegex = false).size)
        assertEquals(0x7CFC00, AiEditPatterns.color("green"))
        assertEquals(0x123456, AiEditPatterns.color("#123456"))
        assertEquals(0xFFEB3B, AiEditPatterns.color(null))
        assertEquals("new", AiEditPatterns.cleanRewrite("«new»"))
        assertEquals(DocCmd.HIGHLIGHT_TEXT, DocCmd.of("highlight_text"))
        assertEquals(DocCmd.REWRITE_TEXT, DocCmd.of("rewrite_block"))
        assertEquals(DocCmd.REPLACE_TEXT, DocCmd.of("replace"))
        assertEquals(DocCmd.REDACT_PATTERN, DocCmd.of("redact-pattern"))
    }

    // ---------------- C1 reading order ----------------

    /** Lines of text, each spread evenly over [l, r] at height [y]; "\n" chars have no box. */
    private fun mkPage(vararg lines: Triple<String, Pair<Float, Float>, Float>): Pair<String, List<ReadingOrder.Box?>> {
        val sb = StringBuilder(); val boxes = ArrayList<ReadingOrder.Box?>()
        lines.forEachIndexed { i, (t, x, y) ->
            if (i > 0) { sb.append('\n'); boxes += null }
            val w = (x.second - x.first) / t.length
            t.forEachIndexed { k, c -> sb.append(c); boxes += ReadingOrder.Box(x.first + k * w, y + 10f, x.first + (k + 1) * w, y) }
        }
        return sb.toString() to boxes
    }

    private val L = 50f to 250f
    private val R = 350f to 550f
    private val two = mkPage(
        Triple("TITLE", 50f to 550f, 800f),
        Triple("L1", L, 700f), Triple("R1", R, 700f),
        Triple("L2", L, 680f), Triple("R2", R, 680f),
        Triple("L3", L, 660f), Triple("R3", R, 660f),
    )

    @Test fun twoColumnsLeftToRight() {
        val o = ReadingOrder.order(two.first, two.second, rtl = false)
        assertEquals("TITLE\nL1\nL2\nL3\nR1\nR2\nR3", o.text)
        // every char maps back to the same char of the source
        o.text.forEachIndexed { i, c -> if (o.map[i] >= 0) assertEquals(c, two.first[o.map[i]]) }
    }

    @Test fun twoColumnsRightToLeftForArabicPages() {
        val o = ReadingOrder.order(two.first, two.second, rtl = true)
        assertEquals("TITLE\nR1\nR2\nR3\nL1\nL2\nL3", o.text)
    }

    @Test fun singleColumnKeepsSourceOrder() {
        val one = mkPage(Triple("a", 50f to 550f, 800f), Triple("bb", 50f to 500f, 780f), Triple("ccc", 50f to 540f, 760f), Triple("dd", 50f to 530f, 740f))
        val o = ReadingOrder.order(one.first, one.second)
        assertEquals(one.first, o.text)
        assertFalse(ReadingOrder.isRtlText("hello"))
        assertTrue(ReadingOrder.isRtlText("مرحبا بك"))
    }

    @Test fun sourceRunsMergeConsecutiveIndexes() {
        val o = ReadingOrder.order(two.first, two.second, rtl = false)
        val start = o.text.indexOf("L1"); val end = o.text.indexOf("L3") + 2
        val runs = o.sourceRuns(start, end)
        assertEquals(3, runs.size)
        assertEquals(two.first.indexOf("L1"), runs[0].first)
    }

    @Test fun piecesKeepSentenceMapsAfterWhitespaceCollapse() {
        val src = "Hello world.  Second   one! Third"
        val p = ReadingOrder.pieces(src)
        assertEquals(1, p.size)
        assertEquals("Hello world. Second one! Third", p[0].text)
        assertEquals(3, p[0].sentences.size)
        val s = ReadingOrder.sentenceAt(p[0], 15)
        assertEquals(13..23, s)
        assertEquals(src.indexOf("Second"), p[0].map[s.first])
        assertEquals(src.indexOf('!'), p[0].map[s.last])
        // long input splits into several pieces of at most max chars
        val long = (1..60).joinToString(" ") { "Sentence number $it." }
        assertTrue(ReadingOrder.pieces(long, 200).all { it.text.length <= 200 })
    }
}
