package com.smartdocs.editor.core.convert

import org.junit.Assert.assertEquals
import org.junit.Test

/** fix/pdf39-convert-2: export a selected region as Word / Excel / PowerPoint (FINAL_CHECK_2 #4). */
class RegionExportTest {
    @Test fun regionAndCrop() {
        // PDF user space rect x 100..200, y 600..700 on a 842 pt high page (crop at 0,0) → frame top 142..242
        val rg = RegionExport.toFrame(100f, 600f, 200f, 700f, 0f, 842f, margin = 0f)
        assertEquals(RegionExport.Region(100f, 142f, 200f, 242f), rg)
        val inside = Glyph(120f, 150f, 6f, 8f, 158f, "a", 10f)
        val outside = Glyph(300f, 150f, 6f, 8f, 158f, "b", 10f)
        val c = PageContent(0, 595f, 842f, listOf(inside, outside), listOf(Segment(110f, 200f, 190f, 200f), Segment(10f, 10f, 500f, 10f)),
            fills = listOf(Fill(Rect(110f, 150f, 150f, 160f), 0xFF0000), Fill(Rect(0f, 0f, 595f, 842f), 0xFFFFFF)))
        val cut = RegionExport.crop(c, rg)
        assertEquals(listOf("a"), cut.glyphs.map { it.text })
        assertEquals(1, cut.segments.size); assertEquals(1, cut.fills.size)
    }
}
