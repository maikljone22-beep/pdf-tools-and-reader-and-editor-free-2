package com.smartdocs.editor.core.fillsign2

import com.smartdocs.editor.core.fillsign2.FsGeom.PageBox
import com.smartdocs.editor.core.fillsign2.MultiPage.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** Tab audit 4, D9 / D10 / D26 / D29: Fill & Sign geometry on turned and cropped pages, date formats, comb cells. */
class FsGeomTest {
    private val eps = 0.01f

    private fun assertBox(e: Box, a: Box) {
        assertEquals(e.left, a.left, eps); assertEquals(e.bottom, a.bottom, eps); assertEquals(e.right, a.right, eps); assertEquals(e.top, a.top, eps)
    }

    // ---------------- D10: the exact cases of the audit (§3) ----------------

    /** Rotated A4: /Rotate 90, displayed 842 x 595, user space 595 x 842. */
    private val rotatedA4 = PageBox.fromDisplayed(842f, 595f, 90)
    /** Crop box [100 100 695 942], unrotated. */
    private val cropped = PageBox.fromDisplayed(595f, 842f, 0, 100f, 100f)

    @Test fun rotatedPageBoxIsUserSpace() {
        assertEquals(595f, rotatedA4.w, eps); assertEquals(842f, rotatedA4.h, eps)
        assertEquals(842f, rotatedA4.dispW, eps); assertEquals(595f, rotatedA4.dispH, eps)
    }

    @Test fun relocateToTheSameRotatedPageKeepsTheBox() {
        val b = Box(450f, 700f, 550f, 750f)
        // the audit measured Box(450, 545, 550, 595) with the displayed size: a 155 pt move
        assertBox(b, MultiPage.relocate(b, rotatedA4, rotatedA4))
    }

    @Test fun pasteOnARotatedPageKeepsAStampThatFits() {
        val b = Box(450f, 700f, 550f, 750f)
        // the audit measured Box(371, 272.5, 471, 322.5): re-centred although it fits
        assertBox(b, MultiPage.pasteBox(b, rotatedA4))
    }

    @Test fun relocateAndPasteOnAnOffsetCropBoxKeepTheBox() {
        val b = Box(600f, 850f, 680f, 900f)
        // the audit measured (515, 792, 595, 842) for relocate and a re-centred paste
        assertBox(b, MultiPage.relocate(b, cropped, cropped))
        assertBox(b, MultiPage.pasteBox(b, cropped))
    }

    @Test fun relocateBetweenCropBoxesKeepsTheRelativePosition() {
        val b = Box(600f, 850f, 680f, 900f)      // centre (640, 875) in [100 100 695 942]
        val plain = PageBox.plain(595f, 842f)
        val r = MultiPage.relocate(b, cropped, plain)
        assertEquals(540f, (r.left + r.right) / 2, eps)
        assertEquals(775f, (r.bottom + r.top) / 2, eps)
        assertEquals(80f, r.width, eps); assertEquals(50f, r.height, eps)
    }

    @Test fun boxAtStaysInsideTheCropBox() {
        val b = Box(0f, 0f, 100f, 50f)
        val r = MultiPage.boxAt(b, 120f, 110f, cropped)
        assertBox(Box(100f, 100f, 200f, 150f), r)
        val far = MultiPage.boxAt(b, 690f, 940f, cropped)
        assertBox(Box(595f, 892f, 695f, 942f), far)
    }

    @Test fun pasteOutsideTheCropBoxIsCentredInIt() {
        val r = MultiPage.pasteBox(Box(0f, 0f, 100f, 50f), cropped)      // lower-left corner is outside [100 ...]
        assertBox(Box(347.5f, 496f, 447.5f, 546f), r)
    }

    // ---------------- D9: display <-> user space ----------------

    @Test fun displayRoundTripForEveryRotationAndOrigin() {
        for (rot in listOf(0, 90, 180, 270)) for (o in listOf(0f, 100f)) {
            val b = PageBox.fromDisplayed(if (rot % 180 == 0) 595f else 842f, if (rot % 180 == 0) 842f else 595f, rot, o, o)
            val (dx, dy) = FsGeom.toDisplay(b, o + 120f, o + 300f)
            val (x, y) = FsGeom.fromDisplay(b, dx, dy)
            assertEquals(o + 120f, x, eps); assertEquals(o + 300f, y, eps)
            assertTrue(dx in 0f..b.dispW && dy in 0f..b.dispH)
        }
    }

    @Test fun displayMatchesTheReaderPageGeometryFor90() {
        // PageGeometry.pdfToScreen for /Rotate 90: (v, u); the user space bottom-left corner is the top-left of the view
        assertEquals(0f to 0f, FsGeom.toDisplay(rotatedA4, 0f, 0f))
        assertEquals(842f to 595f, FsGeom.toDisplay(rotatedA4, 595f, 842f))
    }

    @Test fun userDeltaIsTheDisplayOffset() {
        for (rot in listOf(0, 90, 180, 270)) {
            val b = PageBox.fromDisplayed(500f, 700f, rot)
            val x = 200f; val y = 250f
            val (du, dv) = FsGeom.userDelta(rot, 10f, -4f)      // 10 right, 4 up on screen
            val a = FsGeom.toDisplay(b, x, y); val c = FsGeom.toDisplay(b, x + du, y + dv)
            assertEquals("rot $rot dx", 10f, c.first - a.first, eps)
            assertEquals("rot $rot dy", -4f, c.second - a.second, eps)
        }
    }

    @Test fun textBoxIsUprightAndClampedOnATurnedPage() {
        // 200 x 30 pt of text tapped near the right edge of the displayed page
        val tapDisplay = 800f to 100f
        val (x, y) = FsGeom.fromDisplay(rotatedA4, tapDisplay.first, tapDisplay.second)
        val box = FsGeom.textBox(rotatedA4, x, y, 200f, 30f, rtl = false)
        val d = FsGeom.userToDisplayRect(rotatedA4, box)
        assertEquals(200f, d[2] - d[0], eps)       // the text runs along the displayed width
        assertEquals(30f, d[3] - d[1], eps)
        assertEquals(842f - 2f, d[2], eps)         // clamped to the displayed right edge, not the user width (595)
        assertEquals(100f, d[1], eps)              // top edge at the tap
        // in user space the box is turned: 30 wide, 200 high
        assertEquals(30f, box.width, eps); assertEquals(200f, box.height, eps)
    }

    @Test fun rightToLeftTextEndsAtTheTap() {
        val b = PageBox.plain(595f, 842f)
        val box = FsGeom.textBox(b, 300f, 500f, 100f, 20f, rtl = true)
        assertBox(Box(200f, 480f, 300f, 500f), box)
    }

    @Test fun combBoxIsCentredOnTheTapAndInsideTheCropBox() {
        val box = FsGeom.combBox(cropped, 690f, 500f, 120f, 25f)
        assertBox(Box(575f, 487.5f, 695f, 512.5f), box)
    }

    @Test fun stampBoxTurnsTheImageSizeOnARotatedPage() {
        // a 150 x 50 signature (upright) on /Rotate 90: 50 wide and 150 high in user space
        val box = FsGeom.stampBox(rotatedA4, 300f, 400f, 150f, 50f)
        assertBox(Box(275f, 325f, 325f, 475f), box)
        assertBox(Box(225f, 375f, 375f, 425f), FsGeom.stampBox(PageBox.plain(595f, 842f), 300f, 400f, 150f, 50f))
    }

    @Test fun appearanceMatrixDrawsUprightForTheReader() {
        // the form's x axis must run to the right of the turned view and its y axis up (SigFields.matrixFor)
        for (rot in listOf(90, 180, 270)) {
            val b = PageBox.fromDisplayed(if (rot % 180 == 0) 595f else 842f, if (rot % 180 == 0) 842f else 595f, rot)
            val m = com.smartdocs.editor.core.sign.SigFields.matrixFor(rot)
            val cx = 300f; val cy = 300f
            fun disp(fx: Float, fy: Float): Pair<Float, Float> { val (u, v) = FsGeom.apply(m, fx, fy); return FsGeom.toDisplay(b, cx + u, cy + v) }
            val o = disp(0f, 0f); val right = disp(10f, 0f); val up = disp(0f, 10f)
            assertEquals("rot $rot x", 10f, right.first - o.first, eps); assertEquals(0f, right.second - o.second, eps)
            assertEquals("rot $rot y", -10f, up.second - o.second, eps); assertEquals(0f, up.first - o.first, eps)
        }
        assertEquals(40f to 100f, FsGeom.uprightSize(90, 100f, 40f))
        assertEquals(100f to 40f, FsGeom.uprightSize(180, 100f, 40f))
    }

    @Test fun stampReadBackCountsThePageRotation() {
        // written upright for /Rotate 90 ([0 1 -1 0]): reads back unturned; an old unturned stamp turns with the page
        assertEquals(0, StampRead.displayRotation(StampRead.rotationOf(0f, 1f), 90))
        assertEquals(90, StampRead.displayRotation(0, 90))
        assertEquals(0, StampRead.displayRotation(StampRead.rotationOf(0f, -1f), 270))
        assertEquals(0, StampRead.displayRotation(0, 0))
    }

    // ---------------- D29 / D26 ----------------

    @Test fun combGrowsInsteadOfDroppingCharacters() {
        assertEquals(3, CombLayout.overflow("123456789", 6))
        assertEquals(0, CombLayout.overflow("123", 6))
        assertEquals(9, CombLayout.cellsFor("123456789", 6))
        assertEquals(10, CombLayout.cellsFor("123", 10))
        assertEquals(4, CombLayout.cellsFor("2026", null))
        assertEquals(CombLayout.MAX_CELLS, CombLayout.cellsFor("x".repeat(150), null))
    }

    @Test fun dateFormats() {
        val utc = TimeZone.getTimeZone("UTC")
        val t = 1_798_416_000_000L      // 2026-12-28 UTC
        assertEquals("2026/12/28", FsDate.format(FsDate.DEFAULT, t, Locale.US, utc))
        assertEquals("28/12/2026", FsDate.format("dd/MM/yyyy", t, Locale.US, utc))
        assertEquals("12/28/2026", FsDate.format("MM/dd/yyyy", t, Locale.US, utc))
        assertEquals("28 December 2026", FsDate.format("d MMMM yyyy", t, Locale.US, utc))
        // numeric patterns keep Latin digits whatever the locale (the digits option converts them)
        assertEquals("28/12/2026", FsDate.format("dd/MM/yyyy", t, Locale("ar"), utc))
        assertEquals(FsDate.DEFAULT, FsDate.valid("bogus"))
    }
}
