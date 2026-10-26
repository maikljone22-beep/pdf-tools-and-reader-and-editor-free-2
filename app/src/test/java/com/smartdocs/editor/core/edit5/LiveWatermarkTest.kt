package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Builds a PDF with a live watermark on the JVM (PdfBox-Android without Android: no standard-14 font objects, so
 * the static appearance uses a raw Helvetica dictionary) and checks the structure, then removal.
 */
class LiveWatermarkTest {

    /** Raw Helvetica face: no PDFont object (its AFM is an Android asset), text written as literal strings. */
    private class RawFace(override val size: Float) : LiveFace {
        private fun helv() = COSDictionary().apply {
            setName(COSName.TYPE, "Font"); setName(COSName.SUBTYPE, "Type1"); setName(COSName.BASE_FONT, "Helvetica"); setName(COSName.ENCODING, "WinAnsiEncoding")
        }
        override fun daFont(): COSBase = helv()
        override fun width(line: String): Float = line.length * size * 0.5f
        override fun draw(cs: PDPageContentStream, res: PDResources, line: String, x: Float, y: Float) {
            val fonts = res.cosObject.getCOSDictionary(COSName.FONT) ?: COSDictionary().also { res.cosObject.setItem(COSName.FONT, it) }
            fonts.setItem(COSName.getPDFName("F1"), helv())
            val s = line.filter { it.code in 32..126 }.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            @Suppress("DEPRECATION") cs.appendRawCommands("BT /F1 $size Tf 0.6 g $x $y Td ($s) Tj ET\n")
        }
    }

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b
    private fun dict(d: COSDictionary, k: String) = deref(d.getDictionaryObject(k)) as? COSDictionary
    private fun tmp(name: String) = File.createTempFile("e5_$name", ".pdf").also { it.deleteOnExit() }

    private fun source(): PDDocument {
        val doc = PDDocument()
        doc.addPage(PDPage(PDRectangle.A4))
        doc.addPage(PDPage(PDRectangle.A4).apply { rotation = 90 })
        doc.addPage(PDPage(PDRectangle.LETTER))
        // an existing open action (destination) and another document script that must survive
        doc.documentCatalog.cosObject.setItem(COSName.getPDFName("OpenAction"), COSArray().apply { add(doc.getPage(1).cosObject); add(COSName.getPDFName("Fit")) })
        val other = COSDictionary().apply { setName(COSName.S, "JavaScript"); setItem(COSName.JS, COSString("app.alert('x');")) }
        val tree = COSDictionary().apply { setItem(COSName.getPDFName("Names"), COSArray().apply { add(COSString("Other")); add(other) }) }
        doc.documentCatalog.cosObject.setItem(COSName.getPDFName("Names"), COSDictionary().apply { setItem(COSName.getPDFName("JavaScript"), tree) })
        return doc
    }

    private val spec = DecorSpec(type = DecorType.WATERMARK, source = DecorSource.TEXT, text = "Copy for {user}\n{date:dd/MM/yyyy} {time} p{page}/{pages}",
        style = E5TextStyle(size = 20f, color = 0xC62828), opacity = 0.4f, rotation = 80f, user = "Ali", live = true, onPrint = true, onScreen = true)

    private fun install(doc: PDDocument) =
        LiveWatermark.install(doc, spec, "report.pdf", { RawFace(it.size) }, LocalDate.of(2026, 3, 5), LocalTime.of(9, 7))

    private fun jsOf(action: COSDictionary?): String? = (deref(action?.getDictionaryObject(COSName.JS)) as? COSString)?.string
        ?: (deref(action?.getDictionaryObject(COSName.JS)) as? COSStream)?.toTextString()

    /** Our JS action at the end of a /Next chain starting at [a] (or [a] itself). */
    private fun findOurs(a: COSBase?): COSDictionary? {
        val d = deref(a) ?: return null
        if (d is COSArray) return (0 until d.size()).firstNotNullOfOrNull { findOurs(d.getObject(it)) }
        val dd = d as? COSDictionary ?: return null
        if (dd.getString(COSName.getPDFName("SDE5")) == LiveWmScript.MARK) return dd
        return findOurs(dd.getDictionaryObject("Next"))
    }

    private fun checkInstalled(doc: PDDocument) {
        val cat = doc.documentCatalog.cosObject
        assertTrue(LiveWatermark.present(doc))
        // fields: one parent with a terminal widget per page
        val form = dict(cat, "AcroForm")!!
        val fields = form.getCOSArray(COSName.getPDFName("Fields"))!!
        val parent = (0 until fields.size()).map { deref(fields.getObject(it)) as COSDictionary }.single { it.getString(COSName.T) == LiveWmScript.FIELD }
        assertEquals("Tx", parent.getNameAsString(COSName.FT))
        assertEquals(1, parent.getInt(COSName.FF) and 1)                 // read-only
        assertEquals(4096, parent.getInt(COSName.FF) and 4096)           // multi-line
        assertTrue(parent.getString(COSName.DA)!!.startsWith("/${LiveWmScript.DA_FONT} 20 Tf"))
        assertNotNull(dict(dict(form, "DR")!!, "Font")!!.getDictionaryObject(LiveWmScript.DA_FONT))
        assertNull(form.getDictionaryObject("NeedAppearances"))
        val kids = parent.getCOSArray(COSName.KIDS)!!
        assertEquals(3, kids.size())
        for (i in 0 until 3) {
            val w = deref(kids.getObject(i)) as COSDictionary
            assertEquals("Widget", w.getNameAsString(COSName.SUBTYPE))
            assertEquals("p${i + 1}", w.getString(COSName.T))
            assertEquals(4, w.getInt(COSName.F) and 4)                   // printed
            assertEquals(0, w.getInt(COSName.F) and 32)                  // visible on screen
            // widget is on its page
            val page = doc.getPage(i)
            val annots = page.cosObject.getCOSArray(COSName.ANNOTS)!!
            assertTrue((0 until annots.size()).any { deref(annots.getObject(it)) === w })
            // rotation snapped to 90 (+90 on the rotated page)
            assertEquals(if (i == 1) 180 else 90, dict(w, "MK")!!.getInt(COSName.R))
            // value = static values of the apply
            val v = (deref(w.getDictionaryObject(COSName.V)) as COSString).string
            assertEquals("Copy for Ali\r05/03/2026 09:07 p${i + 1}/3", v)
            // static appearance stream with the text
            val ap = deref(dict(w, "AP")!!.getDictionaryObject(COSName.N)) as COSStream
            val content = ap.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }
            assertTrue(content.contains("/Tx BMC"))
            assertTrue(content.contains("(Copy for Ali) Tj"))
            assertTrue(content.contains("p${i + 1}/3) Tj"))
            assertNotNull(ap.getDictionaryObject(COSName.BBOX))
            assertNotNull(ap.getDictionaryObject(COSName.MATRIX))
            // rect inside the page
            val r = PDRectangle(w.getCOSArray(COSName.RECT)!!)
            assertTrue(r.width > 0f && r.height > 0f)
            assertTrue(r.lowerLeftX >= -1f && r.upperRightX <= page.mediaBox.width + 1f)
        }
        // document script in /Names /JavaScript, the other script kept
        val tree = dict(dict(cat, "Names")!!, "JavaScript")!!
        val names = tree.getCOSArray(COSName.getPDFName("Names"))!!
        val entries = (0 until names.size() step 2).associate { (deref(names.getObject(it)) as COSString).string to deref(names.getObject(it + 1)) as COSDictionary }
        assertTrue(entries.containsKey("Other"))
        val named = jsOf(entries[LiveWmScript.JS_NAME])!!
        for (s in listOf("identity.name", "identity.loginName", "util.printd", "documentFileName", "getField(\"SDE5WM\")", "numPages", "dd/mm/yyyy", "HH:MM")) assertTrue(s, named.contains(s))
        // open action: the original destination wrapped in a GoTo whose /Next runs the script
        val open = deref(cat.getDictionaryObject("OpenAction")) as COSDictionary
        assertEquals("GoTo", open.getNameAsString(COSName.S))
        assertTrue(jsOf(findOurs(open))!!.contains("SDE5wm(this)"))
        // will print
        val wp = dict(dict(cat, "AA")!!, "WP")
        assertEquals("JavaScript", wp!!.getNameAsString(COSName.S))
        assertTrue(jsOf(wp)!!.contains("SDE5wm(this)"))
    }

    @Test fun buildCheckAndRemove() {
        val f1 = tmp("live")
        source().use { doc -> assertEquals(3, install(doc)); doc.save(f1) }
        System.getProperty("e5.out")?.let { f1.copyTo(File(it), overwrite = true) }
        PDDocument.load(f1).use { checkInstalled(it) }
        assertTrue(LiveWatermark.mayContain(f1))

        // applying again replaces (no duplicate fields, scripts or chain entries)
        val f2 = tmp("again")
        PDDocument.load(f1).use { doc -> install(doc); doc.save(f2) }
        PDDocument.load(f2).use { doc ->
            checkInstalled(doc)
            val fields = dict(doc.documentCatalog.cosObject, "AcroForm")!!.getCOSArray(COSName.getPDFName("Fields"))!!
            assertEquals(1, fields.size())
            val open = deref(doc.documentCatalog.cosObject.getDictionaryObject("OpenAction")) as COSDictionary
            assertFalse(deref(open.getDictionaryObject("Next")) is COSArray)
        }

        // remove: fields, widgets, scripts gone; the original destination is the open action again
        val f3 = tmp("removed")
        PDDocument.load(f2).use { doc -> assertEquals(3, LiveWatermark.remove(doc)); doc.save(f3) }
        assertFalse(LiveWatermark.mayContain(f3))
        PDDocument.load(f3).use { doc ->
            val cat = doc.documentCatalog.cosObject
            assertFalse(LiveWatermark.present(doc))
            assertNull(cat.getDictionaryObject("AcroForm"))
            assertNull(cat.getDictionaryObject("AA"))
            for (p in doc.pages) assertNull(p.cosObject.getDictionaryObject(COSName.ANNOTS))
            val open = deref(cat.getDictionaryObject("OpenAction"))
            assertTrue(open is COSArray)
            assertEquals("Fit", ((open as COSArray).getObject(1) as COSName).name)
            val names = dict(dict(cat, "Names")!!, "JavaScript")!!.getCOSArray(COSName.getPDFName("Names"))!!
            assertEquals(2, names.size())
            assertEquals("Other", (names.getObject(0) as COSString).string)
        }
    }

    @Test fun hiddenOnScreenAndNoExistingActions() {
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            LiveWatermark.install(doc, spec.copy(onScreen = false, range = "1"), "a.pdf", { RawFace(it.size) })
            val w = deref(doc.getPage(0).cosObject.getCOSArray(COSName.ANNOTS)!!.getObject(0)) as COSDictionary
            assertEquals(32, w.getInt(COSName.F) and 32)
            val open = deref(doc.documentCatalog.cosObject.getDictionaryObject("OpenAction")) as COSDictionary
            assertEquals("JavaScript", open.getNameAsString(COSName.S))
            LiveWatermark.remove(doc)
            assertNull(doc.documentCatalog.cosObject.getDictionaryObject("OpenAction"))
            assertNull(doc.documentCatalog.cosObject.getDictionaryObject("Names"))
        }
    }

    @Test fun encryptedRoundTrip() {
        val f = tmp("enc")
        source().use { doc ->
            install(doc)
            doc.protect(StandardProtectionPolicy("owner", "secret", AccessPermission()).apply { encryptionKeyLength = 128; setPreferAES(true) })
            doc.save(f)
        }
        assertEquals(LockState.NEEDS_PASSWORD, E5Locks.probeNow(f, null))
        assertEquals(LockState.WRONG_PASSWORD, E5Locks.probeNow(f, "nope"))
        assertEquals(LockState.UNLOCKED, E5Locks.probeNow(f, "secret"))
        PDDocument.load(f, "secret").use { checkInstalled(it) }
        assertTrue(LiveWatermark.mayContain(f))     // the marker key is a name, never encrypted
    }
}
