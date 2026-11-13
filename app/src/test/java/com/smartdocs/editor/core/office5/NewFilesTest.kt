package com.smartdocs.editor.core.office5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** New blank files ("create new") and the streaming package loader. */
class NewFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun blankPptxIsAWellFormedPackage() {
        val pkg = PptParts.blankPptx(rtl = true)
        // every XML part parses, and every internal relationship target exists
        for (n in pkg.names) if (n.endsWith(".xml") || n.endsWith(".rels")) assertNotNull("$n must parse", pkg.dom(n))
        for (n in pkg.names.filter { it.endsWith(".rels") }) {
            val owner = n.replace("_rels/", "").removeSuffix(".rels")
            pkg.rels(owner.let { if (it == "") "" else it }).filter { !it.external }.forEach { assertTrue("${it.target} (from $n)", pkg.has(it.target)) }
        }
        val d = PptDoc.of(pkg)
        assertEquals(1, d.slideCount)
        assertEquals(12192000L to 6858000L, d.slideSize)
    }

    @Test fun blankPptxCanBeEditedSavedAndReopened() {
        val d = PptDoc.blank(rtl = false)
        d.change { d.newSlide(0) }
        assertEquals(2, d.slideCount)
        val f = tmp.newFile("deck.pptx")
        d.save(f)
        val back = PptDoc.open(f)
        assertEquals(2, back.slideCount)
        // the placeholders of both slides resolve through layout and master to a real position
        for (i in 0 until back.slideCount) assertTrue(PptView.build(back, i).shapes.any { it.elem != null })
    }

    @Test fun emptyCsvOpensAsAnEmptySheet() {
        val f = tmp.newFile("table.csv")
        val wb = CsvIO.load(f)
        assertEquals(1, wb.book.sheets.size)
    }

    @Test fun streamedOpenEqualsReadFromBytes() {
        val bytes = PptParts.blankPptx().toBytes()
        val f = tmp.newFile("x.pptx").also { it.writeBytes(bytes) }
        val a = OPackage.open(f); val b = OPackage.read(bytes)
        assertEquals(b.names, a.names)
        for (n in a.names) assertTrue(n, a.bytes(n)!!.contentEquals(b.bytes(n)!!))
    }
}
