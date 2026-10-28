package com.smartdocs.editor.ui.reader

/** Tabs audit CM-19: the Comment tool groups of the phone layout (workbench tools are added by the toolbar). Pure. */
object CommentGroups {
    val MARKUP = listOf(ReaderTool.HIGHLIGHT, ReaderTool.UNDERLINE, ReaderTool.STRIKEOUT, ReaderTool.SQUIGGLY, ReaderTool.REPLACE, ReaderTool.CARET)
    val TEXT = listOf(ReaderTool.NOTE, ReaderTool.FREETEXT)
    val DRAW = listOf(ReaderTool.INK, ReaderTool.ERASER)
    val SHAPES = listOf(ReaderTool.LINE, ReaderTool.ARROW, ReaderTool.RECT, ReaderTool.OVAL, ReaderTool.POLYLINE, ReaderTool.POLYGON, ReaderTool.CLOUD)
    val MEDIA = listOf(ReaderTool.STAMP, ReaderTool.ATTACH, ReaderTool.SOUND, ReaderTool.VIDEO, ReaderTool.SNAPSHOT)
    /** Shown as their own chips next to the groups. */
    val SINGLE = listOf(ReaderTool.SELECT, ReaderTool.TICKMARK)
    val ALL = listOf(MARKUP, TEXT, DRAW, SHAPES, MEDIA, SINGLE)
}
