package com.smartdocs.editor.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/tab-comment-2 (CM-19): every Comment tool is in exactly one group of the phone layout. */
class CommentGroupsTest {
    @Test fun eachToolOnce() {
        val all = CommentGroups.ALL.flatten()
        assertEquals(all.size, all.toSet().size)
        val expected = setOf(ReaderTool.HIGHLIGHT, ReaderTool.UNDERLINE, ReaderTool.STRIKEOUT, ReaderTool.SQUIGGLY, ReaderTool.NOTE, ReaderTool.FREETEXT,
            ReaderTool.INK, ReaderTool.ERASER, ReaderTool.LINE, ReaderTool.ARROW, ReaderTool.RECT, ReaderTool.OVAL, ReaderTool.STAMP,
            ReaderTool.REPLACE, ReaderTool.CARET, ReaderTool.POLYLINE, ReaderTool.POLYGON, ReaderTool.CLOUD, ReaderTool.ATTACH, ReaderTool.SOUND,
            ReaderTool.VIDEO, ReaderTool.SNAPSHOT, ReaderTool.SELECT, ReaderTool.TICKMARK)
        assertEquals(expected, all.toSet())
        assertTrue(CommentGroups.ALL.all { it.isNotEmpty() })
    }
}
