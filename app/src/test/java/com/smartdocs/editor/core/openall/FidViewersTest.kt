package com.smartdocs.editor.core.openall

import com.smartdocs.editor.core.convert6.LegacyDocFlow
import com.smartdocs.editor.core.convert6.XlsNumFmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.hypot

/** fix/files-index: KF8 pictures, XPS gradients / arcs / visual brushes, DXF hatches / dimensions / binary, Excel formats, .doc runs. */
class FidViewersTest {

    // ---- AZW3 / KF8

    @Test fun kindleEmbedReferences() {
        assertEquals(1, MobiReader.base32("0001"))
        assertEquals(32, MobiReader.base32("0010"))
        assertEquals(31, MobiReader.base32("000V"))
        assertNull(MobiReader.base32("00W1"))
        val html = """<img src="kindle:embed:0002?mime=image/jpeg"/><div style="background:url(kindle:embed:0009)"></div>"""
        val out = MobiReader.kindleEmbeds(html) { n -> if (n == 2) "data:image/jpeg;base64,AA" else null }
        assertEquals("""<img src="data:image/jpeg;base64,AA"/><div style="background:url(kindle:embed:0009)"></div>""", out)
    }

    // ---- XPS

    private fun page(body: String) = XpsReader.page(
        """<FixedPage xmlns="http://schemas.microsoft.com/xps/2005/06" Width="100" Height="100">$body</FixedPage>""".toByteArray(), "/Documents/1/Pages/1.fpage")

    @Test fun arcEndsOnTargetAndBulges() {
        // a half circle of radius 10 from (0,0) to (20,0), clockwise on the y-down page: it goes through y = -10
        val segs = XpsReader.arc(0f, 0f, 10f, 10f, 0f, false, true, 20f, 0f)
        assertTrue(segs.isNotEmpty())
        val last = segs.last()
        assertEquals(20f, last[4], 1e-3f); assertEquals(0f, last[5], 1e-3f)
        val mid = segs.first()
        assertEquals(10f, hypot(mid[4] - 10f, mid[5]), 0.05f)
        assertTrue(segs.all { it[5] <= 0.001f })
        // counter-clockwise goes the other way
        assertTrue(XpsReader.arc(0f, 0f, 10f, 10f, 0f, false, false, 20f, 0f).all { it[5] >= -0.001f })
        // zero radius: a straight line
        assertEquals(1, XpsReader.arc(0f, 0f, 0f, 5f, 0f, false, true, 3f, 4f).size)
    }

    @Test fun pathGeometryArcSegment() {
        val p = page("""<Path Fill="#FF000000"><Path.Data><PathGeometry><PathFigure StartPoint="0,0" IsClosed="true">
            <ArcSegment Point="20,0" Size="10,10" RotationAngle="0" IsLargeArc="false" SweepDirection="Clockwise"/></PathFigure></PathGeometry></Path.Data></Path>""")
        val path = p.root.children.single() as XPathEl
        assertTrue(path.geom.cmds.count { it.op == 'C' } >= 2)
        // abbreviated syntax too
        assertTrue(XpsReader.geometry("M 0,0 A 10,10 0 0 1 20,0").cmds.any { it.op == 'C' })
    }

    @Test fun gradientBrushes() {
        val p = page("""<Path Data="M 0,0 L 10,0 10,10 Z"><Path.Fill><LinearGradientBrush MappingMode="Absolute" StartPoint="0,0" EndPoint="10,0" SpreadMethod="Reflect">
            <LinearGradientBrush.GradientStops><GradientStop Color="#FF0000FF" Offset="1"/><GradientStop Color="#FFFF0000" Offset="0"/></LinearGradientBrush.GradientStops>
            </LinearGradientBrush></Path.Fill></Path>
            <Path Data="M 0,0 L 10,0 10,10 Z"><Path.Fill><RadialGradientBrush Center="5,5" GradientOrigin="4,4" RadiusX="5" RadiusY="3">
            <RadialGradientBrush.GradientStops><GradientStop Color="#FFFFFFFF" Offset="0"/><GradientStop Color="#FF000000" Offset="1"/></RadialGradientBrush.GradientStops>
            </RadialGradientBrush></Path.Fill></Path>""")
        val lin = (p.root.children[0] as XPathEl).fill as XBrush.Gradient
        assertTrue(!lin.radial); assertEquals(1, lin.spread)
        assertEquals(listOf(0f to 0xFFFF0000L, 1f to 0xFF0000FFL), lin.stops)
        assertEquals(10f, lin.p[2], 0f)
        val rad = (p.root.children[1] as XPathEl).fill as XBrush.Gradient
        assertTrue(rad.radial); assertEquals(5f, rad.p[4], 0f); assertEquals(3f, rad.p[5], 0f); assertEquals(4f, rad.p[2], 0f)
    }

    @Test fun visualBrush() {
        val p = page("""<Path Data="M 0,0 L 50,0 50,50 0,50 Z"><Path.Fill><VisualBrush Viewbox="0,0,10,10" Viewport="0,0,25,25" TileMode="Tile">
            <VisualBrush.Visual><Path Data="M 0,0 L 10,10" Stroke="#FF000000"/></VisualBrush.Visual></VisualBrush></Path.Fill></Path>""")
        val b = (p.root.children.single() as XPathEl).fill as XBrush.Visual
        assertEquals("Tile", b.tile)
        assertTrue(b.visual is XPathEl)
        assertEquals(25f, b.viewport[2], 0f)
    }

    // ---- DXF

    private fun dxf(vararg pairs: Any): String = pairs.toList().chunked(2).joinToString("\n") { "${it[0]}\n${it[1]}" } + "\n"

    @Test fun hatchSolidAndPattern() {
        val text = dxf(0, "SECTION", 2, "ENTITIES",
            0, "HATCH", 8, "0", 10, 0, 20, 0, 2, "SOLID", 70, 1, 71, 0, 91, 1,
            92, 2, 72, 0, 73, 1, 93, 4, 10, 0, 20, 0, 10, 10, 20, 0, 10, 10, 20, 10, 10, 0, 20, 10, 97, 0, 75, 0, 76, 1, 98, 0,
            0, "HATCH", 8, "0", 2, "ANSI31", 70, 0, 91, 1,
            92, 1, 93, 2, 72, 1, 10, 0, 20, 0, 11, 10, 21, 0, 72, 2, 10, 5, 20, 0, 40, 5, 50, 0, 51, 180, 73, 1, 97, 0,
            75, 0, 76, 1, 52, 0, 41, 1, 77, 0, 78, 1, 53, 45, 43, 0, 44, 0, 45, -2.2449, 46, 2.2449, 79, 0,
            0, "ENDSEC", 0, "EOF")
        val d = DxfReader.read(text)
        val solid = d.shapes[0] as DxfShape.Hatch
        assertTrue(solid.solid); assertEquals(8, solid.loops.single().size)
        val pat = d.shapes[1] as DxfShape.Hatch
        assertTrue(!pat.solid); assertEquals(45.0, pat.angles.single(), 1e-9)
        assertEquals(3.1747, pat.spacing, 1e-3)
        assertTrue(pat.loops.single().size > 8)          // line + arc points
    }

    @Test fun dimensionUsesBlockOrFallback() {
        val text = dxf(0, "SECTION", 2, "BLOCKS", 0, "BLOCK", 2, "*D1", 10, 0, 20, 0, 0, "LINE", 10, 0, 20, 0, 11, 5, 21, 0, 0, "ENDBLK", 0, "ENDSEC",
            0, "SECTION", 2, "ENTITIES",
            0, "DIMENSION", 2, "*D1", 10, 0, 20, 0, 11, 2, 21, 1, 13, 0, 23, 0, 14, 5, 24, 0, 42, 5.0,
            0, "DIMENSION", 2, "*D9", 11, 2, 21, 1, 13, 0, 23, 0, 14, 7, 24, 0, 42, 7.25, 1, "L=<> mm",
            0, "ENDSEC", 0, "EOF")
        val d = DxfReader.read(text)
        assertTrue(d.shapes[0] is DxfShape.Line)
        assertEquals("L=7.25 mm", (d.shapes.last() as DxfShape.Text).text)
        assertEquals("5", DxfReader.dimText(null, 5.0))
        assertEquals("", DxfReader.dimText(" ", 5.0))
    }

    @Test fun binaryDxf() {
        val o = ByteArrayOutputStream()
        o.write("AutoCAD Binary DXF\r\n".toByteArray(Charsets.ISO_8859_1)); o.write(0x1A); o.write(0)
        fun code(c: Int) { o.write(c and 0xFF); o.write(c shr 8) }
        fun str(c: Int, s: String) { code(c); o.write(s.toByteArray()); o.write(0) }
        fun dbl(c: Int, v: Double) { code(c); val b = java.lang.Double.doubleToLongBits(v); for (k in 0 until 8) o.write((b shr (8 * k)).toInt() and 0xFF) }
        fun i16(c: Int, v: Int) { code(c); o.write(v and 0xFF); o.write(v shr 8) }
        str(0, "SECTION"); str(2, "ENTITIES")
        str(0, "LINE"); str(8, "0"); i16(62, 1); dbl(10, 1.0); dbl(20, 2.0); dbl(11, 3.5); dbl(21, 4.0)
        str(0, "ENDSEC"); str(0, "EOF")
        val b = o.toByteArray()
        assertTrue(DxfReader.isBinary(b))
        val d = DxfReader.read(DxfReader.binaryPairs(b))
        val l = d.shapes.single() as DxfShape.Line
        assertEquals(1.0, l.x1, 0.0); assertEquals(3.5, l.x2, 0.0); assertEquals(4.0, l.y2, 0.0)
        assertEquals(DxfReader.aci(1), l.color)
    }

    // ---- Excel number formats

    @Test fun excelNumberFormats() {
        assertEquals("1,234.50", XlsNumFmt.format(1234.5, "#,##0.00"))
        assertEquals("(1,235)", XlsNumFmt.format(-1234.5, "#,##0_);(#,##0)"))
        assertEquals("-1235", XlsNumFmt.format(-1234.5, "0"))
        assertEquals("12.5%", XlsNumFmt.format(0.125, "0.0%"))
        assertEquals("1.23E+04", XlsNumFmt.format(12345.0, "0.00E+00"))
        assertEquals("1 1/2", XlsNumFmt.format(1.5, "# ?/?"))
        assertEquals("$1,000.00", XlsNumFmt.format(1000.0, "\"$\"#,##0.00"))
        assertEquals("€5", XlsNumFmt.format(5.0, "[\$€-407]0"))
        assertEquals("12 SAR", XlsNumFmt.format(12.0, "0 \"SAR\""))
        assertEquals("1,235K", XlsNumFmt.format(1234567.0, "#,##0,\"K\""))
        assertEquals("zero", XlsNumFmt.format(0.0, "0;-0;\"zero\""))
        assertEquals("3/15/2024", XlsNumFmt.format(45366.0, XlsNumFmt.BUILT_IN[14]))
        assertEquals("15-Mar-24", XlsNumFmt.format(45366.0, "d-mmm-yy"))
        assertEquals("2024-03-15 18:30", XlsNumFmt.format(45366.0 + 18.5 / 24, "yyyy-mm-dd hh:mm"))
        assertEquals("6:30 PM", XlsNumFmt.format(45366.0 + 18.5 / 24, "h:mm AM/PM"))
        assertEquals("27:00:00", XlsNumFmt.format(27.0 / 24, "[h]:mm:ss"))
        assertEquals("01:30.5", XlsNumFmt.format(90.5 / 86400, "mm:ss.0"))
        assertEquals("1/2/1904", XlsNumFmt.format(1.0, "m/d/yyyy", date1904 = true))
        assertEquals("0.1", XlsNumFmt.format(0.1, "General"))
        assertEquals("42", XlsNumFmt.format(42.0, "@"))
    }

    // ---- Word 97 character runs

    @Test fun docSprms() {
        // sprmCFBold on, sprmCFItalic toggle (0x81), sprmCHps (2-byte operand), sprmCPicLocation
        val g = byteArrayOf(0x35, 0x08, 1, 0x36, 0x08, 0x81.toByte(), 0x43, 0x4A, 24, 0, 0x03, 0x6A, 0x10, 0x20, 0, 0)
        val c = LegacyDocFlow.chp(0, 10, g, 0, g.size)
        assertTrue(c.bold); assertTrue(c.italic); assertEquals(0x2010, c.picLocation)
        assertEquals(3, LegacyDocFlow.sprmSize(g, 0)); assertEquals(4, LegacyDocFlow.sprmSize(g, 6)); assertEquals(6, LegacyDocFlow.sprmSize(g, 10))
    }
}
