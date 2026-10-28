package com.smartdocs.editor.core.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FINAL_CHECK §19: paragraph / heading starts for read-aloud block navigation. */
class ReadBlocksTest {
    private val text = "Introduction\nThe first line of a paragraph\ncontinues here.\nA second paragraph.\n\n  Methods\nWe did this.\nالفصل الثاني\nنص عربي طويل هنا."

    @Test fun paragraphs() {
        val s = ReadBlocks.starts(text, ReadBlocks.Kind.PARAGRAPH)
        val words = s.map { text.substring(it).substringBefore('\n').take(12) }
        assertEquals(listOf("Introduction", "A second par", "Methods", "الفصل الثاني"), words)
    }

    @Test fun headings() {
        val s = ReadBlocks.starts(text, ReadBlocks.Kind.HEADING)
        val words = s.map { text.substring(it).substringBefore('\n') }
        assertEquals(listOf("Introduction", "The first line of a paragraph", "Methods", "الفصل الثاني"), words)
    }

    @Test fun nextAndPrevious() {
        val s = listOf(0, 50, 100)
        assertEquals(50, ReadBlocks.next(s, 0)); assertEquals(100, ReadBlocks.next(s, 60)); assertNull(ReadBlocks.next(s, 100))
        assertEquals(50, ReadBlocks.previous(s, 80))     // back to the start of the current block
        assertEquals(0, ReadBlocks.previous(s, 55))      // just started: the block before
        assertNull(ReadBlocks.previous(s, 5))
    }
}
