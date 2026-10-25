package com.smartdocs.editor.core.ai5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiiAndStudyTest {

    private fun kinds(t: String) = PiiDetector.detect(t).map { it.kind }

    @Test fun detectsCommonPersonalData() {
        val t = "راسلنا على ali.saleh@example.com أو اتصل +967 777 123 456. البطاقة 4111 1111 1111 1111 والحساب GB82 WEST 1234 5698 7654 32 ."
        val found = PiiDetector.detect(t)
        assertEquals(listOf(PiiKind.EMAIL, PiiKind.PHONE, PiiKind.CARD, PiiKind.IBAN), found.map { it.kind })
        assertEquals("ali.saleh@example.com", found[0].value)
        // indices point into the original text
        found.forEach { assertEquals(it.value, t.substring(it.start, it.end)) }
    }

    @Test fun luhnAndIbanChecksRejectRandomNumbers() {
        assertFalse(PiiKind.CARD in kinds("رقم 4111 1111 1111 1112"))
        assertFalse(PiiKind.IBAN in kinds("GB82 WEST 1234 5698 7654 33 ."))
        assertTrue(PiiDetector.luhn("79927398713"))
    }

    @Test fun arabicDigitsLabelsAndIds() {
        assertEquals(listOf(PiiKind.PHONE), kinds("جوال: ٠٥٥١٢٣٤٥٦٧"))
        assertEquals(listOf(PiiKind.NATIONAL_ID), kinds("رقم الهوية: 1234567890"))
        assertEquals(listOf(PiiKind.PASSPORT), kinds("رقم الجواز A1234567"))
        assertTrue(PiiKind.DATE in kinds("تاريخ الميلاد 12/05/1990"))
        assertTrue(kinds("السعر 1,500,000 ريال").isEmpty())
        assertTrue(PiiKind.URL in kinds("انظر https://example.org/x."))
        assertTrue(PiiDetector.detect("بريد a@b.co", setOf(PiiKind.PHONE)).isEmpty())
    }

    @Test fun srsSchedulesLikeSm2() {
        val now = 1_000_000L
        val s1 = Srs.review(CardSrs(), Grade.GOOD, now)
        assertEquals(1, s1.interval); assertEquals(1, s1.reps); assertEquals(now + Srs.DAY, s1.due)
        val s2 = Srs.review(s1, Grade.GOOD, now)
        assertEquals(6, s2.interval)
        val s3 = Srs.review(s2, Grade.GOOD, now)
        assertTrue(s3.interval >= 14)
        val lapse = Srs.review(s3, Grade.AGAIN, now)
        assertEquals(0, lapse.reps); assertEquals(1, lapse.lapses); assertTrue(lapse.ease < s3.ease); assertTrue(lapse.due < now + Srs.DAY)
        val cards = listOf(Flashcard("a", "b", srs = CardSrs(due = now + Srs.DAY)), Flashcard("c", "d"))
        assertEquals(listOf(1), Srs.queue(cards, now))
    }

    @Test fun quizParserAcceptsLettersBooleansAndFences() {
        val raw = "إليك الاختبار:\n```json\n{\"questions\":[" +
            "{\"type\":\"mcq\",\"question\":\"عاصمة اليمن؟\",\"options\":[\"أ) عدن\",\"ب) صنعاء\",\"ج) تعز\"],\"answer\":\"ب\",\"explanation\":\"...\",\"page\":\"٣\"}," +
            "{\"type\":\"tf\",\"question\":\"الشمس كوكب\",\"answer\":false}," +
            "{\"question\":\"بلا خيارات\"}]}\n```"
        val q = StudyParse.quiz(raw)
        assertEquals(2, q.size)
        assertEquals(1, q[0].answer); assertEquals("صنعاء", q[0].options[1]); assertEquals(3, q[0].page)
        assertEquals(QuizKind.TRUE_FALSE, q[1].kind); assertEquals(1, q[1].answer)
    }

    @Test fun flashcardsAndGlossaryAndRoundTrip() {
        val cards = StudyParse.flashcards("[{\"front\":\"س\",\"back\":\"ج\",\"page\":2},{\"q\":\"Q\",\"a\":\"A\"},{\"front\":\"x\"}]")
        assertEquals(2, cards.size)
        val back = StudyParse.cardsFromJson(StudyParse.cardsToJson(cards.map { it.copy(srs = CardSrs(2.2, 6, 2, 99L, 1)) }))
        assertEquals(CardSrs(2.2, 6, 2, 99L, 1), back[0].srs)
        assertEquals(2, back[0].page)
        val g = StudyParse.glossary("{\"terms\":[{\"term\":\"ب\",\"definition\":\"2\"},{\"term\":\"أ\",\"definition\":\"1\"}]}")
        assertEquals(listOf("أ", "ب"), g.map { it.term })
    }

    @Test fun mindMapFromJsonOrOutline() {
        val j = StudyParse.mindMap("{\"title\":\"جذر\",\"children\":[{\"title\":\"أ\",\"children\":[{\"title\":\"أ1\"}]},\"ب\"]}")
        assertNotNull(j); assertEquals(4, j!!.size)
        val o = StudyParse.mindMap("# الكتاب\n## الفصل الأول\n- فكرة 1\n- فكرة 2\n## الفصل الثاني\n- فكرة 3")
        assertNotNull(o)
        assertEquals("الكتاب", o!!.title)
        assertEquals(2, o.children.size)
        assertEquals(2, o.children[0].children.size)
    }

    @Test fun lenientJsonExtraction() {
        assertNotNull(AiJson.extract("text before {\"a\": [1, 2,], } after"))
        assertEquals(listOf(12, 13), AiJson.pages(kotlinx.serialization.json.JsonPrimitive("ص ١٢-١٣")))
        assertEquals("ar", AiLang.detect("هذا نص عربي طويل نسبياً فيه كلمات قليلة with English"))
        assertEquals("en", AiLang.detect("Mostly English text with one word عربي"))
    }
}
