package com.smartdocs.editor.ui

import androidx.compose.ui.geometry.Offset
import com.smartdocs.editor.core.nve.shell.Customization
import com.smartdocs.editor.core.pdf.SignedSave
import com.smartdocs.editor.core.security.ReaderRestrictions
import com.smartdocs.editor.core.view5.P
import com.smartdocs.editor.ui.reader.PageUserFrame
import com.smartdocs.editor.ui.view5.WbView
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** fix/tab-comment: workbench mapping on rotated / cropped pages, signed-safe writer, customisation, tool routing. */
class TabCommentTest {

    @Test fun workbenchMapsRotatedPagesToUserSpace() {
        // CM-03: a 595 × 842 page with /Rotate 90 is displayed 842 × 595
        val v = WbView(842f, 595f, PageUserFrame(90, 0f, 0f)).apply { vw = 842f; vh = 595f }   // scale 1
        val tap = Offset(100f, 100f)
        val p = v.toPdf(tap)
        // displayed (100, 100) on a page turned 90° clockwise is user (100, 100) measured from the bottom-left of the crop box
        assertEquals(100.0, p.x, 0.01); assertEquals(100.0, p.y, 0.01)
        val back = v.toScreen(p); assertEquals(100f, back.x, 0.01f); assertEquals(100f, back.y, 0.01f)
        // unrotated page: the old formula (x, pageH − y)
        val flat = WbView(595f, 842f, null).apply { vw = 595f; vh = 842f }
        val q = flat.toPdf(Offset(100f, 100f)); assertEquals(100.0, q.x, 0.01); assertEquals(742.0, q.y, 0.01)
        // cropped page: user space is offset by the crop origin
        val crop = WbView(500f, 700f, PageUserFrame(0, 50f, 60f)).apply { vw = 500f; vh = 700f }
        val c = crop.toPdf(Offset(0f, 0f)); assertEquals(50.0, c.x, 0.01); assertEquals(760.0, c.y, 0.01)
        // box corners stay ordered on rotated pages
        val tl = v.boxTopLeft(100f, 100f, 160f, 130f); val br = v.boxBottomRight(100f, 100f, 160f, 130f)
        assertTrue(tl.x < br.x && tl.y < br.y)
        // grid points round-trip through the displayed page
        val d = v.toDisplay(P(200.0, 300.0)); val g = v.fromDisplay(d.x.toDouble(), d.y.toDouble())
        assertEquals(200.0, g.x, 0.01); assertEquals(300.0, g.y, 0.01)
    }

    @Test fun signedSafeEditOnAnUnsignedFile() {
        val src = File.createTempFile("tc_src", ".pdf").apply { deleteOnExit() }
        val out = File.createTempFile("tc_out", ".pdf").apply { deleteOnExit() }
        PDDocument().use { d -> d.addPage(PDPage(PDRectangle.A4)); d.addPage(PDPage(PDRectangle.A4)); d.save(src) }
        SignedSave.editFile(src, out, null) { d -> d.documentInformation.title = "edited"; listOf(1) }
        PDDocument.load(out).use { assertEquals("edited", it.documentInformation.title); assertEquals(2, it.numberOfPages) }
        PDDocument.load(src).use { d -> SignedSave.requireContentChangeAllowed(d) }     // unsigned: no exception
    }

    @Test fun customisationCarriesTheToolset() {
        val c = Customization(readerToolset = listOf("HIGHLIGHT", "INK", "CLOUD"))
        assertEquals(listOf("HIGHLIGHT", "INK", "CLOUD"), Customization.fromJson(c.toJson()).readerToolset)
        // an older file without the field leaves the toolset alone
        assertNull(Customization.fromJson(Customization().toJson()).readerToolset)
    }

    @Test fun documentFreeToolsNeedNoPermission() {
        assertNull(ReaderRestrictions.actionForTool("scan"))
        assertNull(ReaderRestrictions.actionForTool("blank"))
        assertTrue("redact" !in ReaderRestrictions.DOCUMENT_FREE)
    }
}
