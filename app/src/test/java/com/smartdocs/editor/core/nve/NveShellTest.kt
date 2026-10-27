package com.smartdocs.editor.core.nve

import com.smartdocs.editor.core.nve.shell.FeatureGuides
import com.smartdocs.editor.core.nve.shell.Favorites
import com.smartdocs.editor.core.nve.shell.PdfRepair
import com.smartdocs.editor.core.nve.shell.RecoveryNames
import com.smartdocs.editor.core.nve.shell.ToolbarModel
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Shell work (fork G): favourites, toolbar model, recovery names, PDF repair and feature-guide generation. */
class NveShellTest {
    private fun tmp(name: String) = File.createTempFile("nve_$name", ".pdf").also { it.deleteOnExit() }

    @Test fun favouritesToggleMoveLimit() {
        var l = Favorites.parse(" a, b ,a,,c")
        assertEquals(listOf("a", "b", "c"), l)
        l = Favorites.toggle(l, "b"); assertEquals(listOf("a", "c"), l)
        l = Favorites.toggle(l, "d"); assertEquals(listOf("a", "c", "d"), l)
        l = Favorites.move(l, 2, 0); assertEquals(listOf("d", "a", "c"), l)
        assertEquals(l, Favorites.move(l, 0, 9))
        var full = (1..Favorites.MAX).map { "t$it" }
        full = Favorites.toggle(full, "x"); assertEquals(Favorites.MAX, full.size); assertFalse("x" in full)
        assertEquals("d,a,c", Favorites.format(l))
        assertTrue(Favorites.removeAll().isEmpty())
    }

    @Test fun toolbarOrderAndVisibility() {
        val o = ToolbarModel.order(listOf("TOOLS", "BOGUS", "VIEW", "TOOLS"))
        assertEquals("TOOLS", o[0]); assertEquals("VIEW", o[1]); assertEquals(ToolbarModel.DEFAULT_TABS.size, o.size); assertFalse("BOGUS" in o)
        assertEquals(listOf("TOOLS"), ToolbarModel.visible(o, o.toSet()))
        assertFalse("EDIT" in ToolbarModel.visible(o, setOf("EDIT")))
    }

    @Test fun recoveryNamesAndRetention() {
        assertEquals(RecoveryNames.key("/a/b.pdf"), RecoveryNames.key("/a/b.pdf"))
        assertTrue(RecoveryNames.key("/a/b.pdf") != RecoveryNames.key("/a/c.pdf"))
        assertEquals("report (Recovered).pdf", RecoveryNames.recoveredName("report.pdf"))
        val now = 100L * 24 * 3600 * 1000
        val files = listOf("old" to now - 8L * 24 * 3600 * 1000, "new" to now - 1000, "mid" to now - 5000)
        assertEquals(listOf("old"), RecoveryNames.expired(files, now))
        assertEquals(setOf("mid", "old"), RecoveryNames.expired(files, now, keep = 1).toSet())
    }

    private fun sample(pages: Int): File {
        val f = tmp("src")
        PDDocument().use { d ->
            repeat(pages) { i ->
                val p = PDPage(PDRectangle.A4); d.addPage(p)
                PDPageContentStream(d, p).use { cs -> cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.newLineAtOffset(50f, 700f); cs.showText("Page ${i + 1}"); cs.endText() }
            }
            d.save(f)
        }
        return f
    }

    @Test fun repairRebuildsBrokenXref() {
        val src = sample(3)
        val bytes = src.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        // cut the file right after the last page object: no xref table, no trailer, no %%EOF
        val cut = text.lastIndexOf("xref").let { if (it > 0) it else bytes.size - 40 }
        val broken = tmp("broken"); broken.writeBytes(bytes.copyOf(cut))
        val out = tmp("repaired")
        val r = PdfRepair.repair(broken, out)
        assertEquals(3, r.pages)
        PDDocument.load(out).use { d ->
            assertEquals(3, d.numberOfPages)
            assertTrue(PDFTextStripper().getText(d).contains("Page 2"))
        }
        assertEquals("a (repaired).pdf", PdfRepair.repairedName("a.pdf"))
    }

    @Test fun featureGuidesWritePages() {
        for (g in FeatureGuides.Guide.entries) {
            val out = tmp(g.name)
            val n = FeatureGuides.write(g, out)
            PDDocument.load(out).use { d ->
                assertEquals(n, d.numberOfPages)
                when (g) {
                    FeatureGuides.Guide.ORGANIZE -> assertEquals(4, n)
                    FeatureGuides.Guide.FORM -> { val f = d.documentCatalog.acroForm; assertNotNull(f); assertEquals(setOf("name", "agree"), f.fields.map { it.partialName }.toSet()) }
                    FeatureGuides.Guide.COMMENT -> assertEquals(2, d.getPage(0).annotations.size)
                    else -> assertTrue(n >= 1)
                }
            }
        }
    }
}
