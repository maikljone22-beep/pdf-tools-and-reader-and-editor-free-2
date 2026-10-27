package com.smartdocs.editor.core.annot

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class CommentOps7Test {

    private fun square(d: PDDocument, p: PDPage, l: Float, b: Float, r: Float, t: Float): COSDictionary {
        val c = COSDictionary().apply {
            setName(COSName.TYPE, "Annot"); setName(COSName.SUBTYPE, "Square")
            setItem(COSName.RECT, COSArray().apply { listOf(l, b, r, t).forEach { add(COSFloat(it)) } })
            setString(COSName.getPDFName("NM"), "orig")
        }
        val s = AnnotStyle.shapeStream(d, l, b, r - l, t - b, false, 0xFF0000, null, 1f, 0, false, 1f)
        c.setItem(COSName.AP, PDAppearanceDictionary().apply { setNormalAppearance(s) }.cosObject)
        val list = p.annotations; list.add(PDAnnotation.createAnnotation(c)); p.annotations = list
        return c
    }

    private fun reload(d: PDDocument): PDDocument { val o = ByteArrayOutputStream(); d.save(o); d.close(); return PDDocument.load(o.toByteArray()) }

    @Test fun propsMovesAndRects() {
        val d = PDDocument(); val p = PDPage(); d.addPage(p)
        square(d, p, 10f, 10f, 50f, 50f); square(d, p, 100f, 100f, 120f, 130f)
        CommentOps7.setProps(d, 0, listOf(0, 1), CommentOps7.Props(author = "B", subject = "S", locked = true))
        CommentOps7.moveEach(d, 0, mapOf(1 to floatArrayOf(-90f, -90f)))
        CommentOps7.setRect(d, 0, 0, floatArrayOf(10f, 10f, 30f, 40f))
        reload(d).use { r ->
            val a = r.getPage(0).annotations
            assertEquals("B", AnnotStyle.authorOf(a[1].cosObject)); assertEquals("S", AnnotStyle.subjectOf(a[0].cosObject))
            assertTrue(a[0].isLocked)
            assertEquals(10f, a[1].rectangle.lowerLeftX, 0.01f); assertEquals(10f, a[1].rectangle.lowerLeftY, 0.01f)
            assertEquals(20f, a[0].rectangle.width, 0.01f)
        }
    }

    @Test fun copyWithinAndAcrossDocuments() {
        val d = PDDocument(); d.addPage(PDPage()); d.addPage(PDPage())
        square(d, d.getPage(0), 10f, 10f, 50f, 50f)
        CommentOps7.copyTo(d, 0, 0, d, 1, 0f, 0f)       // paste in place on another page
        CommentOps7.copyTo(d, 0, 0, d, 0, 12f, -12f)    // paste with offset
        val other = PDDocument(); other.addPage(PDPage())
        CommentOps7.copyTo(d, 0, 0, other, 0, 0f, 0f)
        reload(d).use { r ->
            val p0 = r.getPage(0).annotations; val p1 = r.getPage(1).annotations
            assertEquals(10f, p1[0].rectangle.lowerLeftX, 0.01f)
            assertEquals(22f, p0.first { it.annotationName != "orig" && it.subtype == "Square" }.rectangle.lowerLeftX, 0.01f)
            assertEquals(10f, p0[0].rectangle.lowerLeftX, 0.01f)       // the original did not move
            assertNotEquals("orig", p1[0].annotationName)
            assertTrue(p1.any { it.subtype == "Popup" })
        }
        reload(other).use { r -> assertTrue(r.getPage(0).annotations[0].normalAppearanceStream != null) }
    }

    @Test fun rotateKeepsCentreAndSwapsBox() {
        val d = PDDocument(); val p = PDPage(); d.addPage(p)
        square(d, p, 100f, 100f, 200f, 140f)
        assertTrue(CommentOps7.rotate(d, 0, 0, 90f))
        reload(d).use { r ->
            val rr = r.getPage(0).annotations[0].rectangle
            assertEquals(40f, rr.width, 0.1f); assertEquals(100f, rr.height, 0.1f)
            assertEquals(150f, rr.lowerLeftX + rr.width / 2, 0.1f); assertEquals(120f, rr.lowerLeftY + rr.height / 2, 0.1f)
        }
        val m = CommentOps7.mul(floatArrayOf(1f, 0f, 0f, 1f, 5f, 0f), floatArrayOf(0f, 1f, -1f, 0f, 0f, 0f))
        assertEquals(5f, m[5], 1e-4f)
    }

    @Test fun keepOnlySelectionAndFdfTarget() {
        val d = PDDocument(); val p = PDPage(); d.addPage(p)
        square(d, p, 10f, 10f, 50f, 50f); val second = square(d, p, 100f, 100f, 120f, 130f)
        AnnotStyle.addPopup(p, second)
        CommentOps7.keepOnly(d, mapOf(0 to setOf(1)))
        assertEquals(listOf("Square", "Popup"), p.annotations.map { it.subtype })
        val x = java.io.File.createTempFile("cmt", ".xfdf")
        x.writeText("<?xml version=\"1.0\"?><xfdf xmlns=\"http://ns.adobe.com/xfdf/\"><f href=\"dir/Report 1.pdf\"/></xfdf>")
        assertEquals("Report 1.pdf", CommentOps7.fdfTargetName(x))
        val dir = java.nio.file.Files.createTempDirectory("fdf").toFile()
        java.io.File(dir, "Report 1.pdf").writeText("x")
        assertEquals("Report 1.pdf", CommentOps7.findPdf("report 1.pdf", listOf(dir))?.name)
        x.delete(); dir.deleteRecursively(); d.close()
    }

    @Test fun popupsResetAndOpen() {
        val d = PDDocument(); val p = PDPage(); d.addPage(p)
        val c = square(d, p, 10f, 10f, 50f, 50f)
        AnnotStyle.addPopup(p, c)
        assertEquals(1, CommentOps7.setPopupsOpen(d, true))
        assertEquals(true, AnnotStyle.popupOpen(c))
        assertEquals(1, CommentOps7.resetPopups(d))
        assertEquals(false, AnnotStyle.popupOpen(c))
    }
}
