package com.smartdocs.editor.core.redact

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Late sub-audit 5b: D6 (pending marks with maximum security), D9 (Screen with the sanitize defaults), D10, countEof. */
class RedactSubauditTest {
    @get:Rule val tmp = TemporaryFolder()
    private val n = COSName::getPDFName
    private val secret = "SUBAUDIT-SECRET-0815"

    private fun redactAnnots(page: COSDictionary): List<COSDictionary> {
        val arr = page.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return emptyList()
        return (0 until arr.size()).mapNotNull { arr.getObject(it) as? COSDictionary }.filter { it.getCOSName(COSName.SUBTYPE)?.name == "Redact" }
    }

    @Test fun pendingMarksAreNotWrittenBeforeTheRasterStep() {
        val pending = listOf(RedactMark("keep-1", 0, emptyList(), wholePage = true))
        assertEquals(pending, RedactApplier.pendingBeforeRaster(pending, emptyList()))
        assertTrue(RedactApplier.pendingBeforeRaster(pending, listOf(0)).isEmpty())
        assertTrue(RedactApplier.pendingBeforeRaster(pending, setOf(3)).isEmpty())
    }

    @Test fun pendingMarkOfARasterisedPageLandsOnTheReplacementPage() {
        val saved = PDDocument().use { doc ->
            val p1 = PDPage(PDRectangle.A4); val p2 = PDPage(PDRectangle.A4)
            doc.addPage(p1); doc.addPage(p2)
            val s = PDStream(doc); s.createOutputStream().use { it.write("BT /F1 12 Tf 72 800 Td ($secret) Tj ET".toByteArray()) }
            p1.setContents(s)
            val pending = listOf(RedactMark("keep-p1", 0, emptyList(), wholePage = true, label = "pending one"),
                RedactMark("keep-p2", 1, emptyList(), wholePage = true))
            // what apply() does: the stored marks are removed before rendering (so they are not burned into the image),
            // the page is replaced, then the pending marks are written again
            RedactMarks.removeAll(doc)
            val old = doc.getPage(0).cosObject
            val np = PDPage(PDRectangle.A4)
            PageScrub.replacePage(doc, doc.getPage(0), np)
            RedactApplier.writePending(doc, pending, mapOf(0 to floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)), "me")
            assertTrue(redactAnnots(old).isEmpty())
            val onNew = redactAnnots(np.cosObject)
            assertEquals(1, onNew.size)
            assertEquals("keep-p1", onNew.single().getString(n("NM")))
            assertEquals(1, redactAnnots(doc.getPage(1).cosObject).size)
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        assertFalse(String(saved, Charsets.ISO_8859_1).contains(secret))
        PDDocument.load(saved).use { d ->
            assertEquals(2, d.numberOfPages)
            assertEquals(listOf("keep-p1"), redactAnnots(d.getPage(0).cosObject).map { it.getString(n("NM")) })
            assertEquals(listOf("keep-p2"), redactAnnots(d.getPage(1).cosObject).map { it.getString(n("NM")) })
            for (o in d.document.objects) {
                val b = o.getObject()
                if (b is COSStream) assertFalse(String(b.createInputStream().use { it.readBytes() }, Charsets.ISO_8859_1).contains(secret))
            }
        }
    }

    @Test fun sanitizeDefaultsRemoveRedactWatermarkAndScreenAnnotations() {
        PDDocument().use { doc ->
            val p = PDPage(); doc.addPage(p)
            fun annot(sub: String) = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.ANNOT); setItem(COSName.SUBTYPE, n(sub)); setItem(COSName.RECT, PDRectangle(10f, 10f, 50f, 20f).cosArray)
                setString(COSName.CONTENTS, "$sub-$secret")
            }
            p.cosObject.setItem(COSName.ANNOTS, COSArray().apply { add(annot("Redact")); add(annot("Watermark")); add(annot("Screen")); add(annot("Link")) })
            // both are in the defaults offered with redaction and on the sanitize screen
            assertTrue(SanitizeItem.COMMENTS in SanitizeItem.DEFAULTS && SanitizeItem.MULTIMEDIA in SanitizeItem.DEFAULTS)
            val r = Sanitizer.clean(doc, setOf(SanitizeItem.COMMENTS, SanitizeItem.MULTIMEDIA))
            assertEquals(2, r[SanitizeItem.COMMENTS])
            assertEquals(1, r[SanitizeItem.MULTIMEDIA])
            val left = p.cosObject.getDictionaryObject(COSName.ANNOTS) as COSArray
            assertEquals(listOf("Link"), (0 until left.size()).map { (left.getObject(it) as COSDictionary).getCOSName(COSName.SUBTYPE).name })
        }
    }

    @Test fun countEofStreamsAcrossChunkBorders() {
        assertEquals(0, Sanitizer.countEof(ByteArrayInputStream(ByteArray(0))))
        assertEquals(1, Sanitizer.countEof(ByteArrayInputStream("%PDF-1.7\n%%%EOF\n".toByteArray())))
        assertEquals(2, Sanitizer.countEof(ByteArrayInputStream("%%EOF%%EOF".toByteArray())))
        assertEquals(0, Sanitizer.countEof(ByteArrayInputStream("%%EO%EOF %EOF".toByteArray())))
        // a marker that straddles the 64 KiB read buffer, and one at the very end
        val chunk = 64 * 1024
        val bytes = ByteArray(3 * chunk) { ' '.code.toByte() }
        "%%EOF".toByteArray().copyInto(bytes, chunk - 2)
        "%%EOF".toByteArray().copyInto(bytes, 2 * chunk + 100)
        "%%EOF".toByteArray().copyInto(bytes, bytes.size - 5)
        assertEquals(3, Sanitizer.countEof(ByteArrayInputStream(bytes)))
        val f = tmp.newFile("big.pdf"); f.writeBytes(bytes)
        assertEquals(3, Sanitizer.countEof(f))
    }

    @Test fun moveIntoReplacesAnExistingOutput() {
        val out = File(tmp.root, "out.pdf").apply { writeText("old") }
        val t = File(tmp.root, ".out.pdf.1.tmp").apply { writeText("new") }
        RedactIo.moveInto(t, out)
        assertEquals("new", out.readText())
        assertFalse(t.exists())
        assertTrue(tmp.root.listFiles()!!.all { it.name == "out.pdf" })
        // the atomic save leaves no temp file behind either
        PDDocument().use { it.addPage(PDPage()); RedactIo.saveAtomic(it, out) }
        PDDocument.load(out).use { assertEquals(1, it.numberOfPages) }
        assertTrue(tmp.root.listFiles()!!.all { it.name == "out.pdf" })
    }

    @Test fun nothingToProtectIsRefused() {
        // audit 5b L2: the screen disables Protect for this case; the core still refuses it
        try {
            kotlinx.coroutines.runBlocking {
                val src = tmp.newFile("p.pdf").also { f -> PDDocument().use { it.addPage(PDPage()); it.save(f) } }
                ProtectAdvanced.protect(src, File(tmp.root, "o.pdf"), null, ProtectOptions(permissions = Permissions()))
            }
            org.junit.Assert.fail("protected nothing")
        } catch (e: IllegalArgumentException) {}
    }
}
