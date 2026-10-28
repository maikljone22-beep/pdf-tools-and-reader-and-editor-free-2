package com.smartdocs.editor.core.nve

import com.smartdocs.editor.core.nve.shapes.Gradient
import com.smartdocs.editor.core.nve.shapes.GradientStop
import com.smartdocs.editor.core.nve.shapes.NveShape
import com.smartdocs.editor.core.nve.shapes.NveShapes
import com.smartdocs.editor.core.nve.shapes.ShapeKind
import com.smartdocs.editor.core.nve.shapes.ShapeStyle
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.shading.PDShadingType2
import com.tom_roush.pdfbox.pdmodel.graphics.shading.PDShadingType3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Vector shapes and gradients written as page content (core/nve/shapes): write, save, reload, edit, delete. */
class NveShapesTest {

    private fun tmp() = File.createTempFile("nve_shapes", ".pdf").also { it.deleteOnExit() }

    private fun withOriginalContent(doc: PDDocument): PDPage {
        val p = PDPage(PDRectangle.A4); doc.addPage(p)
        // an unbalanced cm in the original content must not move our shapes
        PDPageContentStream(doc, p).use { cs -> cs.transform(com.tom_roush.pdfbox.util.Matrix(2f, 0f, 0f, 2f, 10f, 10f)); cs.addRect(0f, 0f, 10f, 10f); cs.fill() }
        return p
    }

    private fun contentText(p: PDPage): String = p.contents.readBytes().toString(Charsets.ISO_8859_1)

    @Test fun everyKindRoundTrips() {
        val f = tmp()
        val styles = ShapeStyle(stroke = 0xFF0000, fill = 0x00FF00, width = 3f, dash = listOf(4f, 2f), cap = 1, join = 2, miter = 4f, opacity = 0.5f)
        val shapes = listOf(
            NveShape(0, ShapeKind.LINE, listOf(10f, 10f, 100f, 100f), styles),
            NveShape(0, ShapeKind.RECT, listOf(20f, 20f, 120f, 80f), styles),
            NveShape(0, ShapeKind.ROUND_RECT, listOf(20f, 200f, 120f, 280f), styles, radius = 12f),
            NveShape(0, ShapeKind.ELLIPSE, listOf(200f, 200f, 300f, 260f), styles),
            NveShape(0, ShapeKind.POLYLINE, listOf(10f, 400f, 50f, 450f, 90f, 400f), styles),
            NveShape(0, ShapeKind.POLYGON, listOf(110f, 400f, 150f, 450f, 190f, 400f), styles),
            NveShape(0, ShapeKind.BEZIER, listOf(210f, 400f, 250f, 450f, 290f, 400f, 330f, 450f), styles),
            NveShape(0, ShapeKind.CLOSED_BEZIER, listOf(400f, 400f, 450f, 450f, 500f, 400f), styles),
            NveShape(0, ShapeKind.FREE, listOf(10f, 600f, 11f, 602f, 13f, 605f, 20f, 610f), styles),
        )
        PDDocument().use { d ->
            val p = withOriginalContent(d)
            shapes.forEach { NveShapes.add(d, p, it) }
            d.save(f)
        }
        PDDocument.load(f).use { d ->
            val p = d.getPage(0)
            val back = NveShapes.list(p)
            assertEquals(shapes.size, back.size)
            back.forEachIndexed { i, s ->
                assertEquals(i + 1, s.id)
                assertEquals(shapes[i].kind, s.kind)
                assertEquals(shapes[i].points, s.points)
                assertEquals(0xFF0000, s.style.stroke); assertEquals(0x00FF00, s.style.fill)
                assertEquals(3f, s.style.width, 1e-3f); assertEquals(listOf(4f, 2f), s.style.dash)
                assertEquals(1, s.style.cap); assertEquals(2, s.style.join); assertEquals(4f, s.style.miter, 1e-3f); assertEquals(0.5f, s.style.opacity, 1e-3f)
            }
            assertEquals(12f, back[2].radius, 1e-3f)
            val text = contentText(p)
            // the original content is isolated in q … Q before the first span
            assertTrue(text.trimStart().startsWith("q"))
            assertTrue(text.contains("/NVEShape"))
            assertTrue(text.contains(" re"))       // rect
            assertTrue(text.contains(" c"))        // curves
            assertTrue(text.contains(" B"))        // fill + stroke
            assertTrue(text.contains("[4 2] 0 d"))
            assertTrue(text.contains("1 J") && text.contains("2 j") && text.contains("4 M"))
            assertNotNull(p.resources.extGStateNames.firstOrNull())
        }
    }

    @Test fun lineIsNeverFilled() {
        val (path, closed) = NveShapes.pathOps(NveShape(1, ShapeKind.LINE, listOf(0f, 0f, 10f, 10f)))
        assertFalse(closed)
        assertEquals(2, path.lines().count { it.endsWith(" m") || it.endsWith(" l") })
    }

    @Test fun editStyleKeepsOrderAndDeleteRemovesOnlyThatSpan() {
        val f = tmp()
        PDDocument().use { d ->
            val p = withOriginalContent(d)
            NveShapes.add(d, p, NveShape(0, ShapeKind.RECT, listOf(0f, 0f, 50f, 50f)))
            NveShapes.add(d, p, NveShape(0, ShapeKind.ELLIPSE, listOf(100f, 100f, 150f, 150f)))
            NveShapes.add(d, p, NveShape(0, ShapeKind.LINE, listOf(0f, 0f, 300f, 300f)))
            d.save(f)
        }
        PDDocument.load(f).use { d ->
            val p = d.getPage(0)
            val ell = NveShapes.list(p)[1]
            assertTrue(NveShapes.replace(d, p, ell.id, ell.copy(style = ell.style.copy(stroke = 0x0000FF, width = 7f)).moved(10f, 5f)))
            val l = NveShapes.list(p)
            assertEquals(listOf(ShapeKind.RECT, ShapeKind.ELLIPSE, ShapeKind.LINE), l.map { it.kind })
            assertEquals(0x0000FF, l[1].style.stroke); assertEquals(7f, l[1].style.width, 1e-3f)
            assertEquals(listOf(110f, 105f, 160f, 155f), l[1].points)
            assertTrue(NveShapes.replace(d, p, l[0].id, null))
            assertEquals(listOf(ShapeKind.ELLIPSE, ShapeKind.LINE), NveShapes.list(p).map { it.kind })
            assertFalse(NveShapes.replace(d, p, 999, null))
            d.save(f)
        }
        PDDocument.load(f).use { d ->
            val p = d.getPage(0)
            assertEquals(2, NveShapes.list(p).size)
            // the original filled square is still there
            assertTrue(contentText(p).contains("0 0 10 10 re"))
            // new ids continue after the highest one
            assertEquals(4, NveShapes.nextId(p))
        }
    }

    @Test fun axialTwoStopsUsesType2Function() {
        val sh = NveShapes.shading(Gradient(radial = false), 0f, 0f, 100f, 50f)
        assertTrue(sh is PDShadingType2)
        val fn = sh.cosObject.getDictionaryObject(COSName.FUNCTION) as com.tom_roush.pdfbox.cos.COSDictionary
        assertEquals(2, fn.getInt(COSName.FUNCTION_TYPE))
    }

    @Test fun radialManyStopsUsesStitching() {
        val g = Gradient(radial = true, stops = listOf(GradientStop(0f, 0xFF0000), GradientStop(0.3f, 0x00FF00), GradientStop(1f, 0x0000FF)), x0 = 0.5f, y0 = 0.5f, r = 0.5f)
        val sh = NveShapes.shading(g, 0f, 0f, 100f, 100f)
        assertTrue(sh is PDShadingType3)
        val fn = sh.cosObject.getDictionaryObject(COSName.FUNCTION) as com.tom_roush.pdfbox.cos.COSDictionary
        assertEquals(3, fn.getInt(COSName.FUNCTION_TYPE))
        assertEquals(2, (fn.getDictionaryObject(COSName.FUNCTIONS) as com.tom_roush.pdfbox.cos.COSArray).size())
        assertEquals(1, (fn.getDictionaryObject(COSName.BOUNDS) as com.tom_roush.pdfbox.cos.COSArray).size())
    }

    @Test fun gradientShapeRoundTripsAndPaintsWithSh() {
        val f = tmp()
        val g = Gradient(radial = false, stops = listOf(GradientStop(0f, 0x112233), GradientStop(0.5f, 0x445566), GradientStop(1f, 0x778899)))
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            NveShapes.add(d, p, NveShape(0, ShapeKind.ROUND_RECT, listOf(10f, 10f, 200f, 100f), ShapeStyle(stroke = null, gradient = g)))
            d.save(f)
        }
        PDDocument.load(f).use { d ->
            val p = d.getPage(0)
            val s = NveShapes.list(p).single()
            assertNull(s.style.stroke)
            assertEquals(g.stops, s.style.gradient!!.stops)
            val text = contentText(p)
            assertTrue(text.contains(" sh"))
            assertTrue(text.contains("W n"))
            assertNotNull(p.resources.shadingNames.firstOrNull())
        }
    }

    @Test fun fittedAndHit() {
        val s = NveShape(1, ShapeKind.POLYGON, listOf(0f, 0f, 10f, 0f, 10f, 20f), ShapeStyle(width = 0f))
        val g = s.fitted(100f, 100f, 120f, 140f)
        assertEquals(listOf(100f, 100f, 120f, 100f, 120f, 140f), g.points)
        assertEquals(g, NveShapes.hit(listOf(s, g), 110f, 120f, 2f))
        assertNull(NveShapes.hit(listOf(s, g), 500f, 500f, 2f))
    }
}
