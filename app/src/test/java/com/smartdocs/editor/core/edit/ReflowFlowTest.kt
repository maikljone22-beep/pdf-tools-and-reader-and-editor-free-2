package com.smartdocs.editor.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §14d: reflow editing of a page range as one flow (pure model). */
class ReflowFlowTest {

    private fun blk(page: Int, text: String, top: Float, l: Float = 50f, r: Float = 540f, size: Float = 10f, bold: Boolean = false, h: Float = 12f, color: Int = 0) =
        FlowSrc(page, text, l, top, r, top - h, size, color, bold, false, false)

    @Test fun blockLinesBecomeOneParagraphLine() {
        assertEquals("hyphenated text more", ReflowFlow.oneLine("hyphen-\nated text\r\nmore"))
        assertEquals("well-Known", ReflowFlow.oneLine("well-\nKnown"))
    }

    @Test fun paragraphCrossingAPageBreakStaysOne() {
        val p0 = listOf(
            blk(0, "Title", 800f, size = 18f, bold = true, h = 20f),
            blk(0, "First paragraph ends here.", 760f),
            blk(0, "Second paragraph runs on", 730f),
        )
        val p1 = listOf(blk(1, "to the next page.", 800f), blk(1, "Third one.", 770f))
        val f = ReflowFlow.build(0..1, mapOf(0 to p0, 1 to p1))
        assertEquals("Title\nFirst paragraph ends here.\nSecond paragraph runs on to the next page.\nThird one.", f.text)
        // the title keeps its size and weight as a run, the body is the base style
        assertEquals(10f, f.base.size, 0.01f)
        assertEquals(listOf(RunStyle(0, 5, bold = true, size = 18f)), f.runs)
        assertEquals(2, f.frames.size)
        assertEquals(listOf(0, 1), f.frames.map { it.page })
    }

    @Test fun columnsAreFramesInReadingOrder() {
        val left = (0 until 4).map { k -> blk(0, "Left $k text", 760f - k * 30f, 50f, 280f) }
        val right = (0 until 4).map { k -> blk(0, "Right $k text", 760f - k * 30f, 320f, 550f) }
        val f = ReflowFlow.build(0..0, mapOf(0 to right + left))
        assertEquals(2, f.frames.size)
        assertEquals(50f, f.frames[0].left, 0.01f)
        assertEquals(320f, f.frames[1].left, 0.01f)
        assertTrue(f.text.indexOf("Left 3") < f.text.indexOf("Right 0"))
    }

    @Test fun aTallGapStartsANewFrame() {
        val f = ReflowFlow.build(0..0, mapOf(0 to listOf(blk(0, "Above the picture.", 760f), blk(0, "Below the picture.", 500f))))
        assertEquals(2, f.frames.size)
        assertEquals(500f - 10f, f.frames[1].firstBaseline, 0.01f)
    }

    @Test fun continuesNeedsAnOpenSentenceAndTheSameLook() {
        assertTrue(ReflowFlow.continues(blk(0, "and so", 100f), blk(1, "on", 800f)))
        assertFalse(ReflowFlow.continues(blk(0, "done.", 100f), blk(1, "Next", 800f)))
        assertFalse(ReflowFlow.continues(blk(0, "انتهى؟", 100f), blk(1, "x", 800f)))
        assertFalse(ReflowFlow.continues(blk(0, "Heading", 100f, size = 18f), blk(1, "body", 800f)))
    }

    @Test fun runsOnlyForBlocksThatDiffer() {
        val base = EditTextStyle(size = 10f)
        assertNull(ReflowFlow.runOf(blk(0, "x", 0f), base, 0, 1))
        assertEquals(RunStyle(2, 4, color = 0xFF0000), ReflowFlow.runOf(blk(0, "x", 0f, color = 0xFF0000), base, 2, 4))
        assertEquals(listOf(RunStyle(0, 9, bold = true)), ReflowFlow.merge(listOf(RunStyle(0, 4, bold = true), RunStyle(5, 9, bold = true))))
    }

    @Test fun fitCountsLinesLikeTheWriter() {
        val st = EditTextStyle(size = 10f)
        // 12.5 points per line: three lines need 25 points
        assertEquals(3, ReflowFlow.fit(List(5) { 10f }, List(5) { false }, st, 30f))
        // a paragraph gap after the first line pushes the third out
        assertEquals(2, ReflowFlow.fit(List(5) { 10f }, listOf(true, false, false, false, false), st.copy(paraSpacing = 6f), 30f))
        // a bigger run line takes more room
        assertEquals(2, ReflowFlow.fit(listOf(10f, 10f, 20f), List(3) { false }, st, 30f))
        // the first line always fits
        assertEquals(1, ReflowFlow.fit(listOf(10f, 10f), listOf(false, false), st, 0f))
    }

    @Test fun cutDropsTheBreakBetweenHeadAndTail() {
        val text = "aaa bbb\nccc ddd"
        assertEquals(7 to 8, ReflowFlow.cut(text, listOf("aaa", "bbb", "ccc", "ddd"), 2))
        assertEquals(3 to 4, ReflowFlow.cut(text, listOf("aaa", "bbb", "ccc", "ddd"), 1))
        assertEquals(text.length to text.length, ReflowFlow.cut(text, listOf("aaa", "bbb", "ccc", "ddd"), 4))
        // blank paragraphs are empty lines
        assertEquals(4 to 5, ReflowFlow.cut("aaa\n\nbbb", listOf("aaa", "", "bbb"), 2))
        assertEquals(listOf(RunStyle(0, 2, bold = true)), ReflowFlow.slice(listOf(RunStyle(3, 10, bold = true)), 8, 12))
    }
}
