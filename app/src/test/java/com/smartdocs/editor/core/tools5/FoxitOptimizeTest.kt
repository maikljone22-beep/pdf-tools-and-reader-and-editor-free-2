package com.smartdocs.editor.core.tools5

import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.random.Random

/** Foxit §20 optimizer gaps. */
class FoxitOptimizeTest {

    // ---- resampling ----

    @Test fun resampleMethodsKeepFlatColourAndSize() {
        val c = 0xFF336699.toInt()
        val px = IntArray(40 * 30) { c }
        for (m in listOf(Downsample.AVERAGE, Downsample.SUBSAMPLE, Downsample.BICUBIC)) {
            val r = Resample.resize(px, 40, 30, 13, 10, m)
            assertEquals(130, r.size)
            assertTrue("$m", r.all { it == c })
        }
        assertTrue(Resample.resize(px, 40, 30, 13, 10, Downsample.OFF) === px)
    }

    @Test fun averageOfTwoColours() {
        val px = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        val r = Resample.average(px, 2, 2, 1, 1)
        assertEquals(0x7F, r[0] and 0xFF)
        // bicubic stays within range on a hard edge
        val edge = IntArray(64 * 8) { i -> if (i % 64 < 32) 0xFF000000.toInt() else -1 }
        Resample.bicubic(edge, 64, 8, 16, 2).forEach { v -> assertTrue((v ushr 24) == 0xFF) }
    }

    // ---- JBIG2 / RunLength ----

    private fun sample(w: Int, h: Int, seed: Int): ByteArray {
        val rb = (w + 7) / 8
        val d = ByteArray(rb * h) { 0xFF.toByte() }
        val r = Random(seed)
        repeat(60) { val x0 = r.nextInt(w); val y0 = r.nextInt(h)
            for (y in y0 until minOf(h, y0 + r.nextInt(1, 9))) for (x in x0 until minOf(w, x0 + r.nextInt(1, 30))) d[y * rb + (x shr 3)] = (d[y * rb + (x shr 3)].toInt() and (0x80 ushr (x and 7)).inv()).toByte() }
        return d
    }

    @Test fun jbig2DecodesBackToTheImage() {
        val w = 131; val h = 47
        val data = sample(w, h, 3)
        val enc = Jbig2.encode(data, w, h)
        // javax.imageio is not on the Android compile classpath of unit tests, but it is in the test JVM: use reflection
        val stream = Class.forName("javax.imageio.ImageIO").getMethod("createImageInputStream", Any::class.java).invoke(null, ByteArrayInputStream(enc))
        val spi = Class.forName("org.apache.pdfbox.jbig2.JBIG2ImageReaderSpi").getConstructor().newInstance()
        val readerCls = Class.forName("org.apache.pdfbox.jbig2.JBIG2ImageReader")
        val reader = readerCls.getConstructor(Class.forName("javax.imageio.spi.ImageReaderSpi")).newInstance(spi)
        readerCls.getMethod("setInput", Any::class.java).invoke(reader, stream)
        val img = readerCls.getMethod("read", Int::class.javaPrimitiveType).invoke(reader, 0)
        val ic = img.javaClass
        assertEquals(w, ic.getMethod("getWidth").invoke(img) as Int); assertEquals(h, ic.getMethod("getHeight").invoke(img) as Int)
        val getRGB = ic.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val rb = (w + 7) / 8
        for (y in 0 until h) for (x in 0 until w) {
            val black = (data[y * rb + (x shr 3)].toInt() shr (7 - (x and 7)) and 1) == 0
            val shownBlack = ((getRGB.invoke(img, x, y) as Int) and 0xFFFFFF) == 0
            assertEquals("pixel $x,$y", black, shownBlack)
        }
        assertTrue(enc.size < data.size)
    }

    @Test fun runLengthRoundTrip() {
        val data = ByteArray(500) { i -> if (i in 100..300) 7 else (i % 13).toByte() }
        val s = COSStream()
        s.setItem(COSName.FILTER, COSName.RUN_LENGTH_DECODE)
        s.createRawOutputStream().use { it.write(RunLength.encode(data)) }
        assertArrayEquals(data, s.createInputStream().use { it.readBytes() })
        assertArrayEquals(byteArrayOf(-128), RunLength.encode(ByteArray(0)))
    }

    // ---- fonts ----

    @Test fun ttfStripKeepsUsedGlyphsAndIds() {
        val font = File("src/main/assets/fonts/NotoNaskhArabic-Regular.ttf").readBytes()
        val before = TTFParser(false, true).parse(ByteArrayInputStream(font))   // on demand: GSUB parsing logs through android.util.Log
        val n = before.numberOfGlyphs
        val keep = setOf(0, 5, 40, 200)
        val out = TtfStrip.keep(font, keep)
        assertTrue(out.size < font.size / 2)
        val after = TTFParser(false, true).parse(ByteArrayInputStream(out))
        assertEquals(n, after.numberOfGlyphs)
        for (g in keep) assertEquals(before.glyph.getGlyph(g)?.boundingBox?.width ?: 0f, after.glyph.getGlyph(g)?.boundingBox?.width ?: 0f, 0.001f)
        val dropped = (1 until n).first { it !in keep && (before.glyph.getGlyph(it)?.description?.pointCount ?: 0) > 0 }
        assertEquals(0, after.glyph.getGlyph(dropped)?.description?.pointCount ?: 0)
    }

    // ---- lines to curves ----

    @Test fun smoothPolylineBecomesCurves() {
        val t = ArrayList<Any>()
        t += COSFloat(0f); t += COSFloat(0f); t += Operator.getOperator("m")
        for (i in 1..40) { val a = i * Math.PI / 80; t += COSFloat((100 * Math.cos(a)).toFloat()); t += COSFloat((100 * Math.sin(a)).toFloat()); t += Operator.getOperator("l") }
        t += Operator.getOperator("S")
        // start the arc at its first point: m must be on the circle for a smooth run
        t[0] = COSFloat(100f); t[1] = COSFloat(0f)
        val (out, replaced) = LineFit.toCurves(t)
        assertEquals(40, replaced)
        assertTrue(out.count { it is Operator && it.name == "c" } in 1 until 40)
        assertTrue(out.none { it is Operator && it.name == "l" })
        // a zig-zag is not smooth and stays as lines
        val z = ArrayList<Any>(listOf(COSInteger.get(0), COSInteger.get(0), Operator.getOperator("m")))
        for (i in 1..10) { z += COSInteger.get(i * 10L); z += COSInteger.get(if (i % 2 == 0) 0L else 10L); z += Operator.getOperator("l") }
        assertEquals(0, LineFit.toCurves(z).second)
    }

    @Test fun douglasPeuckerKeepsCorners() {
        val pts = listOf(0.0 to 0.0, 1.0 to 0.01, 2.0 to 0.0, 2.0 to 5.0)
        assertEquals(listOf(0, 2, 3), LineFit.simplify(pts, 0.1))
    }

    // ---- clean-up ----

    @Test fun cleanupsRemoveWhatTheyName() {
        PDDocument().use { doc ->
            repeat(2) { doc.addPage(PDPage()) }
            val cat = doc.documentCatalog.cosObject
            cat.setItem(COSName.VIEWER_PREFERENCES, COSDictionary().apply { setName("PrintScaling", "None"); setName("Duplex", "Simplex"); setBoolean(COSName.getPDFName("HideToolbar"), true) })
            assertEquals(2, OptimizeCleanup.printSettings(doc))
            assertTrue((cat.getDictionaryObject(COSName.VIEWER_PREFERENCES) as COSDictionary).containsKey(COSName.getPDFName("HideToolbar")))
            // form actions
            val w = PDAnnotationWidget()
            w.cosObject.setItem(COSName.A, COSDictionary().apply { setName(COSName.S, "SubmitForm") })
            val web = PDAnnotationLink().apply { action = PDActionURI().apply { uri = "https://x" } }
            val dead = PDAnnotationLink().apply { action = PDActionGoTo().apply { destination = PDNamedDestination("nowhere") } }
            val good = PDAnnotationLink().apply { action = PDActionGoTo().apply { destination = PDPageFitDestination().apply { setPage(doc.getPage(1)) } } }
            doc.getPage(0).annotations = mutableListOf(w, web, dead, good)
            assertEquals(1, OptimizeCleanup.removeActions(doc) { it == "SubmitForm" })
            assertFalse(w.cosObject.containsKey(COSName.A))
            assertEquals(1, OptimizeCleanup.invalidLinks(doc))                      // the link to an unknown name
            assertEquals(3, doc.getPage(0).annotations.size)
            assertEquals(2, OptimizeCleanup.run(doc, CleanupOptions(externalLinks = true))["externalLinks"])   // the URI action, then its empty link
            assertEquals(2, doc.getPage(0).annotations.size)                         // the URI link went with its action
        }
    }

    @Test fun unusedNamedDestinationsGo() {
        PDDocument().use { doc ->
            repeat(2) { doc.addPage(PDPage()) }
            val d1 = PDPageFitDestination().apply { setPage(doc.getPage(0)) }; val d2 = PDPageFitDestination().apply { setPage(doc.getPage(1)) }
            val names = COSArray().apply { add(COSString("a")); add(d1.cosObject); add(COSString("b")); add(d2.cosObject) }
            doc.documentCatalog.cosObject.setItem(COSName.NAMES, COSDictionary().apply { setItem(COSName.DESTS, COSDictionary().apply { setItem(COSName.NAMES, names) }) })
            doc.getPage(0).annotations = mutableListOf(PDAnnotationLink().apply { action = PDActionGoTo().apply { destination = PDNamedDestination("b") } })
            assertEquals(1, OptimizeCleanup.unusedDests(doc))
            assertEquals(2, names.size()); assertEquals("b", (names.getObject(0) as COSString).string)
        }
    }

    @Test fun presetJsonRoundTrip() {
        val o = OptimizeOptions(imageDpi = 120, monoMode = MonoCompression.JBIG2, downsample = Downsample.AVERAGE, aboveDpi = 225, pdfVersion = "1.6",
            subsetFonts = true, mrc = true, flattenTransparencyDpi = 300, separateClasses = true, grayMode = ImgCompression.ZIP,
            cleanup = CleanupOptions(formActions = true, linesToCurves = true, unusedDests = true))
        assertEquals(o, OptimizePresets.fromJson(OptimizePresets.toJson(o)))
        assertNotNull(OptimizePresets.fromJson("{}"))
    }
}
