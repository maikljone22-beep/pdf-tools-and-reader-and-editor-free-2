package com.smartdocs.editor.cmr

import com.smartdocs.editor.core.annot.AnnotStyle
import com.smartdocs.editor.core.annot.ForeignKeys
import com.smartdocs.editor.core.annot.NavKey
import com.smartdocs.editor.core.annot.NavOrder
import com.smartdocs.editor.core.annot.NavOrderLogic
import com.smartdocs.editor.core.annot.TextLists
import com.smartdocs.editor.core.forms5.Box5
import com.smartdocs.editor.core.forms5.FieldNavigation
import com.smartdocs.editor.core.forms5.NavItem
import com.smartdocs.editor.core.view5.MeasureCalc
import com.smartdocs.editor.core.view5.MeasureScale
import com.smartdocs.editor.core.view5.MeasureType
import com.smartdocs.editor.core.view5.MeasureWriter
import com.smartdocs.editor.core.view5.Measurement
import com.smartdocs.editor.core.view5.MUnit
import com.smartdocs.editor.core.view5.P
import com.smartdocs.editor.core.view5.StoredEdit
import com.smartdocs.editor.core.view5.V5Pdf
import com.smartdocs.editor.ui.reader.annot.PencilLogic
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/comments-rest, part 2: navigation order, pencil, text lists, foreign keys, measuring. */
class CommentsRestTest2 {
    @Test fun navigationOrders() {
        val a = NavKey(0, 700f, 300f, 2); val b = NavKey(0, 701f, 50f, 1); val c = NavKey(0, 500f, 60f, 0); val d = NavKey(1, 800f, 0f, 0)
        val all = listOf(d, c, a, b)
        assertEquals(listOf(b, a, c, d), NavOrderLogic.sort(all, NavOrder.ROWS) { it })
        assertEquals(listOf(b, c, a, d), NavOrderLogic.sort(all, NavOrder.COLUMNS) { it })
        assertEquals(listOf(c, b, a, d), NavOrderLogic.sort(all, NavOrder.STRUCTURE) { it })
        assertEquals(listOf(a, b, c, d), NavOrderLogic.sort(all, NavOrder.ROWS, rtl = true) { it })
    }

    @Test fun fieldDocumentOrderFallback() {
        val items = listOf(NavItem("x", 0, Box5(300f, 700f, 350f, 680f), annotIndex = 1), NavItem("y", 0, Box5(10f, 700f, 60f, 680f), annotIndex = 0),
            NavItem("z", 0, Box5(10f, 500f, 60f, 480f), annotIndex = 2))
        assertEquals(listOf("y", "x", "z"), FieldNavigation.order(items, tabs = { "A" }))
        assertEquals(listOf("y", "x", "z"), FieldNavigation.order(items, tabs = { "R" }))
        assertEquals(listOf("y", "z", "x"), FieldNavigation.order(items, tabs = { "C" }))
        assertEquals("A", NavOrder.STRUCTURE.tabs)
    }

    @Test fun pencilStrokeOverTextBand() {
        val band = PencilLogic.textBand(listOf(10f to 100f, 40f to 101f, 80f to 99f))!!
        assertEquals(8f, band[0], 0.01f); assertEquals(97f, band[1], 0.01f); assertEquals(82f, band[2], 0.01f); assertEquals(103f, band[3], 0.01f)
        assertNull(PencilLogic.textBand(listOf(10f to 100f, 40f to 130f, 80f to 99f)))     // a scribble, not a line
        assertNull(PencilLogic.textBand(listOf(10f to 100f, 14f to 100f)))                 // too short
        assertNotNull(PencilLogic.textBand(listOf(100f to 10f, 101f to 60f)))              // vertical text (rotated page)
    }

    @Test fun numberedListsRestartAfterEmptyLine() {
        assertEquals(listOf("1. a", "2. b", "", "1. c"), TextLists.decorate("a\nb\n\nc", bullets = false, numbering = true))
        assertEquals(listOf("• a", ""), TextLists.decorate("a\n", bullets = true, numbering = false))
        assertEquals(listOf("a"), TextLists.decorate("a", bullets = false, numbering = false))
        assertEquals("a\nb", TextLists.strip("1. a\n12. b", bullets = false, numbering = true))
        assertEquals("a\n1. b", TextLists.strip("• a\n1. b", bullets = true, numbering = false))
    }

    @Test fun richTextKeepsNumberingIndentAndParagraphSpacing() {
        val c = COSDictionary()
        AnnotStyle.applyRich(c, "one\ntwo", AnnotStyle.Rich(12f, 0x112233, false, false, numbering = true, indent = 18f, paraSpacing = 6f), rtl = false)
        val r = AnnotStyle.readRich(c)!!
        assertTrue(r.numbering); assertFalse(r.bullets)
        assertEquals(18f, r.indent, 0.01f); assertEquals(6f, r.paraSpacing, 0.01f)
        val b = COSDictionary()
        AnnotStyle.applyRich(b, "one", AnnotStyle.Rich(12f, 0, false, false, bullets = true), rtl = true)
        val rb = AnnotStyle.readRich(b)!!
        assertTrue(rb.bullets); assertFalse(rb.numbering); assertEquals(0f, rb.indent, 0.01f); assertEquals(0f, rb.paraSpacing, 0.01f)
    }

    @Test fun foreignKeysAreKeptAndStandardOnesAreNot() {
        val src = COSDictionary().apply {
            setName(COSName.SUBTYPE, "Square"); setString(COSName.CONTENTS, "x")
            setItem(COSName.getPDFName("IC"), COSArray().apply { add(COSFloat(1f)) })
            setItem(COSName.getPDFName("BSIColumnData"), COSArray().apply { add(COSName.getPDFName("A")); add(COSDictionary().apply { setString(COSName.getPDFName("V"), "7") }) })
            setString(COSName.getPDFName("MyApp"), "custom")
            setItem(COSName.P, COSDictionary())
        }
        val snap = ForeignKeys.snapshot(src)!!
        assertEquals(setOf("BSIColumnData", "MyApp"), snap.keySet().map { it.name }.toSet())
        val target = COSDictionary().apply { setString(COSName.getPDFName("MyApp"), "new") }
        ForeignKeys.merge(target, snap)
        assertEquals("new", target.getString(COSName.getPDFName("MyApp")))          // the rewritten value wins
        assertTrue(target.containsKey(COSName.getPDFName("BSIColumnData")))
        assertFalse(target.containsKey(COSName.getPDFName("IC")))                     // a removed fill stays removed
        assertNull(ForeignKeys.snapshot(COSDictionary().apply { setName(COSName.SUBTYPE, "Text") }))
    }

    @Test fun newMeasureTypes() {
        val s = MeasureScale(1.0, MUnit.IN, 1.0, MUnit.IN)      // 72 pt = 1 in
        val sq = listOf(P(0.0, 0.0), P(72.0, 0.0), P(72.0, 72.0), P(0.0, 72.0))
        assertEquals(2.0, MeasureCalc.value(MeasureType.VOLUME, sq, s, depth = 2.0), 1e-9)
        assertEquals(3.0, MeasureCalc.value(MeasureType.POLYLENGTH, sq, s), 1e-9)
        assertEquals(2.0, MeasureCalc.value(MeasureType.DIAMETER, listOf(P(0.0, 0.0), P(72.0, 0.0)), s), 1e-9)
        assertEquals(1.0, MeasureCalc.value(MeasureType.RADIUS, listOf(P(0.0, 0.0), P(72.0, 0.0)), s), 1e-9)
        assertEquals(6.0, MeasureCalc.wallArea(sq, s, 2.0), 1e-9)
        assertEquals("2.00 in³", MeasureCalc.label(MeasureType.VOLUME, 2.0, s, 2, false))
        assertEquals(3, MeasureCalc.minPoints(MeasureType.VOLUME))
    }

    @Test fun labelOffsetMovesTheLabel() {
        val m = Measurement(MeasureType.DISTANCE, 0, listOf(P(0.0, 0.0), P(100.0, 0.0)), 1.0, "1", MeasureScale.IDENTITY_CM)
        assertEquals(P(50.0, 3.0), MeasureWriter.labelAt(m))
        assertEquals(P(60.0, 23.0), MeasureWriter.labelAt(m.copy(labelOffset = P(10.0, 20.0))))
        val area = m.copy(type = MeasureType.AREA, pts = listOf(P(0.0, 0.0), P(30.0, 0.0), P(0.0, 30.0)))
        assertEquals(P(10.0, 10.0), MeasureWriter.labelAnchor(area))
    }

    private fun floats(vararg f: Float) = COSArray().apply { f.forEach { add(COSFloat(it)) } }

    @Test fun storedMeasurementAndCalloutAreEditable() {
        val line = COSDictionary().apply {
            setName(COSName.TYPE, "Annot"); setName(COSName.SUBTYPE, "Line"); setItem(COSName.RECT, floats(0f, 0f, 200f, 50f))
            setItem(COSName.L, floats(10f, 10f, 82f, 10f)); setName(V5Pdf.IT, "LineDimension"); setName(V5Pdf.SD_TYPE, "DISTANCE")
            setItem(V5Pdf.MEASURE, MeasureWriter.measureDict(MeasureScale(1.0, MUnit.IN, 1.0, MUnit.IN), 1))
            setItem(MeasureWriter.SD_LABEL_OFF, floats(0f, 12f))
        }
        val e = StoredEdit.readOne(PDAnnotation.createAnnotation(line), 0, 3)!!
        assertEquals(StoredEdit.Kind.MEASURE, e.kind)
        assertEquals(listOf(P(10.0, 10.0), P(82.0, 10.0)), e.pts)
        assertEquals(P(46.0, 25.0), e.label)
        val moved = StoredEdit.changedMeasurement(e, StoredEdit.Change(listOf(P(10.0, 10.0), P(154.0, 10.0))))!!
        assertEquals(2.0, moved.value, 1e-6)
        assertEquals("2.0 in", moved.label)
        assertEquals(P(0.0, 12.0), moved.labelOffset)                                    // the label follows the line
        val dragged = StoredEdit.changedMeasurement(e, StoredEdit.Change(e.pts, label = P(0.0, 0.0)))!!
        assertEquals(P(-46.0, -13.0), dragged.labelOffset)

        val callout = COSDictionary().apply {
            setName(COSName.TYPE, "Annot"); setName(COSName.SUBTYPE, "FreeText"); setName(V5Pdf.IT, "FreeTextCallout")
            setItem(COSName.RECT, floats(0f, 0f, 200f, 100f)); setItem(V5Pdf.RD, floats(100f, 50f, 0f, 0f))
            setItem(V5Pdf.CL, floats(5f, 5f, 50f, 40f, 100f, 75f)); setString(COSName.CONTENTS, "note"); setString(V5Pdf.DA, "/Helv 10 Tf 0 0 1 rg")
        }
        val c = StoredEdit.readOne(PDAnnotation.createAnnotation(callout), 0, 0)!!
        assertEquals(StoredEdit.Kind.CALLOUT, c.kind)
        assertEquals(listOf(P(5.0, 5.0), P(50.0, 40.0)), c.pts)
        assertEquals(100f, c.text!!.box.x0, 0.01f); assertEquals(10f, c.text!!.fontSize, 0.01f); assertEquals(0x0000FF, c.text!!.textColor)

        val plain = COSDictionary().apply { setName(COSName.TYPE, "Annot"); setName(COSName.SUBTYPE, "Line"); setItem(COSName.RECT, floats(0f, 0f, 9f, 9f)); setItem(COSName.L, floats(1f, 2f, 3f, 4f)) }
        assertEquals(StoredEdit.Kind.LINE, StoredEdit.readOne(PDAnnotation.createAnnotation(plain), 0, 0)!!.kind)
        val locked = COSDictionary().apply { addAll(plain); setInt(COSName.F, 128) }
        assertNull(StoredEdit.readOne(PDAnnotation.createAnnotation(locked), 0, 0))
    }
}
