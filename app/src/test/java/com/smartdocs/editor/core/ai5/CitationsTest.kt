package com.smartdocs.editor.core.ai5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationsTest {
    private val sources = listOf(
        Source(1, "k", "doc.pdf", "/doc.pdf", 4, "تأسست الشركة في عام 1998 في مدينة عدن. وتعمل في مجال الشحن البحري."),
        Source(2, "k", "doc.pdf", "/doc.pdf", 11, "بلغت الإيرادات 12 مليون دولار في عام 2022 بزيادة كبيرة عن العام السابق."),
        Source(3, "k", "doc.pdf", "/doc.pdf", 30, "The board approved the merger in March."),
    )

    @Test fun parsesMarkersQuotesAndFollowUps() {
        val raw = "تأسست الشركة عام 1998 [1]، وبلغت إيراداتها 12 مليون دولار [2، 3].\n@@@\n" +
            "{\"quotes\":{\"1\":\"تأسست الشركة في عام 1998\",\"2\":\"بلغت الإيرادات 12 مليون دولار\"},\"followups\":[\"ما مجال عمل الشركة؟\",\"كم كانت الإيرادات في 2021؟\"]}"
        val a = CitationFormat.parse(raw, sources)
        assertEquals(listOf(1, 2, 3), a.citations.map { it.n })
        assertEquals(listOf(4, 11, 30), a.citations.map { it.page })
        assertEquals("تأسست الشركة في عام 1998", a.citations[0].quote)
        assertEquals(2, a.followUps.size)
        assertFalse(a.text.contains("@@@"))
        assertTrue(a.text.contains("[2][3]"))
    }

    @Test fun dropsUnknownNumbersAndAcceptsArabicDigitsAndRanges() {
        val a = CitationFormat.parse("أولاً [١] ثانياً [9] ثالثاً [2-3]", sources)
        assertEquals(listOf(1, 2, 3), a.citations.map { it.n })
        assertFalse(a.text.contains("[9]"))
        assertEquals(listOf(1, 2, 3), CitationFormat.numbers("1-3"))
        assertEquals(listOf(1, 4), CitationFormat.numbers("1، 4"))
    }

    @Test fun missingTailStillParses() {
        val a = CitationFormat.parse("The merger was approved [3].", sources)
        assertEquals(1, a.citations.size)
        assertTrue(a.followUps.isEmpty())
        // fallback quote is a real sentence of the passage
        assertTrue(sources[2].text.contains(a.citations[0].quote.removeSuffix("…")))
    }

    @Test fun wrongQuoteFallsBackToBestSentence() {
        val q = CitationFormat.bestQuote("نص لا يوجد في المقطع إطلاقاً", sources[0].text, "تعمل الشركة في الشحن البحري")
        assertTrue(q.contains("الشحن"))
    }

    @Test fun visibleHidesTailWhileStreaming() {
        assertEquals("جواب [1]", CitationFormat.visible("جواب [1]\n@@@\n{\"quotes\""))
        assertEquals("جواب", CitationFormat.visible("جواب@@"))
        assertEquals("جواب", CitationFormat.visible("جواب"))
    }

    @Test fun partsAndPlain() {
        val parts = CitationFormat.parts("أ [1] ب [2][3].")
        assertEquals(listOf(1, 2, 3), parts.filterIsInstance<AnswerPart.Cite>().map { it.n })
        assertEquals("أ ب.", CitationFormat.plain("أ [1] ب [2][3]."))
    }

    @Test fun plainWithSourcesListsPages() {
        val a = CitationFormat.parse("نص [2].", sources)
        val t = a.plainWithSources("ص")
        assertTrue(t.startsWith("نص."))
        assertTrue(t.contains("[2] doc.pdf — ص 12"))
    }

    @Test fun citationJsonRoundTrip() {
        val c = Citation(2, "k", "/a.pdf", "a.pdf", 7, "quote")
        assertEquals(listOf(c), CitationFormat.listFromJson(CitationFormat.listToJson(listOf(c))))
    }
}
