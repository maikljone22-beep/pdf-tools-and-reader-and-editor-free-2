package com.smartdocs.editor.core.annot

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** fix/pdf39-comments-forms: cloudy ovals (Nitro 7.27) and scallops inward / outward (Foxit 24.33). */
class CloudShapesTest {
    private val square = listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)

    // the control points of a bump sit on one side of its chord: outside the square or inside it
    private fun controlsOutside(segs: List<Cubic>) = segs.all { c -> c.x1 !in 0.5f..99.5f || c.y1 !in 0.5f..99.5f }
    private fun controlsInside(segs: List<Cubic>) = segs.all { c -> c.x1 in 0f..100f && c.y1 in 0f..100f }

    @Test fun scallopsOutwardAndInward() {
        val out = CloudPath.build(square, 2, 1f)
        val inn = CloudPath.build(square, 2, 1f, inward = true)
        assertEquals(out.size, inn.size)
        assertTrue(controlsOutside(out)); assertTrue(controlsInside(inn))
        // clockwise input gives the same sides
        assertTrue(controlsInside(CloudPath.build(square.reversed(), 2, 1f, inward = true)))
        assertTrue(controlsOutside(CloudPath.build(square.reversed(), 2, 1f)))
    }

    @Test fun ellipseIsOneBumpPerEdge() {
        val pts = CloudPath.ellipse(0f, 0f, 200f, 100f, 1, 1f)
        assertTrue(pts.size >= 8)
        pts.forEach { (x, y) -> val nx = (x - 100f) / 100f; val ny = (y - 50f) / 50f; assertEquals(1f, nx * nx + ny * ny, 0.001f) }
        val segs = CloudPath.build(pts, 1, 1f)
        assertEquals(pts.size, segs.size)
        val edge = hypot(pts[1].first - pts[0].first, pts[1].second - pts[0].second)
        assertTrue(edge <= 2f * CloudPath.radius(1, 1f) + 0.01f)
    }

    @Test fun inwardFlagRoundTripsAndCloudyOvalDraws() {
        val c = COSDictionary()
        AnnotStyle.setCloudy(c, 2, inward = true)
        assertEquals(2, AnnotStyle.cloudyOf(c)); assertTrue(AnnotStyle.cloudInwardOf(c))
        AnnotStyle.setCloudy(c, 0, inward = true)
        assertEquals(0, AnnotStyle.cloudyOf(c)); assertFalse(AnnotStyle.cloudInwardOf(c))
        PDDocument().use { d ->
            val plain = AnnotStyle.shapeStream(d, 0f, 0f, 120f, 80f, true, 0xFF0000, null, 1f, 0, false, 1f)
            val cloudy = AnnotStyle.shapeStream(d, 0f, 0f, 120f, 80f, true, 0xFF0000, null, 1f, 0, false, 1f, cloudy = 2, inward = true)
            fun curves(s: com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream) =
                String(s.contentStream.toByteArray()).split('\n').count { it.trim().endsWith(" c") }
            assertEquals(4, curves(plain))
            assertTrue(curves(cloudy) > 8)
        }
    }
}
