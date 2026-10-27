package com.smartdocs.editor.core.tools5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Nitro gaps of section 14: CCITT encoding, 1-bit downsampling, unembedding fonts, named destinations, tips. */
class NitroOptimizeTest {

    /** A scan-like 1-bit page: white with black text-ish blocks and noise (0 = black). */
    private fun sample(w: Int, h: Int, seed: Int): ByteArray {
        val rb = (w + 7) / 8
        val d = ByteArray(rb * h) { 0xFF.toByte() }
        val r = Random(seed)
        fun black(x: Int, y: Int) { d[y * rb + (x shr 3)] = (d[y * rb + (x shr 3)].toInt() and (0x80 ushr (x and 7)).inv()).toByte() }
        repeat(40) { val x0 = r.nextInt(w); val y0 = r.nextInt(h); val bw = r.nextInt(1, 60); val bh = r.nextInt(1, 12)
            for (y in y0 until minOf(h, y0 + bh)) for (x in x0 until minOf(w, x0 + bw)) black(x, y) }
        repeat(300) { black(r.nextInt(w), r.nextInt(h)) }
        for (x in 0 until w) black(x, h - 1)          // a full black row
        black(w - 1, 0)                                // the last pixel of a row
        return d
    }

    private fun decode(enc: ByteArray, w: Int, h: Int, k: Int, eol: Boolean): ByteArray {
        val s = COSStream()
        s.setItem(COSName.FILTER, COSName.CCITTFAX_DECODE)
        s.setItem(COSName.DECODE_PARMS, COSDictionary().apply {
            setInt(COSName.K, k); setInt(COSName.COLUMNS, w); setInt(COSName.ROWS, h); if (eol) setBoolean(COSName.getPDFName("EndOfLine"), true)
        })
        s.createRawOutputStream().use { it.write(enc) }
        return s.createInputStream().use { it.readBytes() }
    }

    @Test fun g4RoundTrip() {
        for ((w, h, seed) in listOf(Triple(203, 61, 1), Triple(64, 40, 2), Triple(1, 3, 3), Triple(2600, 8, 4))) {
            val data = sample(w, h, seed)
            val enc = Ccitt.encodeG4(data, w, h)
            assertArrayEquals("G4 ${w}x$h", data, decode(enc, w, h, -1, false).copyOf(data.size))
            assertTrue(enc.size < data.size || w < 8)
        }
    }

    @Test fun g3RoundTrip() {
        val w = 150; val h = 33
        val data = sample(w, h, 7)
        assertArrayEquals(data, decode(Ccitt.encodeG3(data, w, h), w, h, 0, true).copyOf(data.size))
    }

    @Test fun changesListAlternatesFromWhite() {
        // row: W W B B B W  → turns black at 2, white at 5
        val row = byteArrayOf(0b11000111.toByte())
        assertArrayEquals(intArrayOf(2, 5, 6, 6), Ccitt.changes(row, 0, 6))
    }

    @Test fun monoDownsampleKeepsMostlyBlackAreas() {
        val w = 8; val h = 2
        val d = byteArrayOf(0x0F, 0x0F)                 // left half black on both rows
        val half = Ccitt.downsample(d, w, h, 4, 1)
        assertEquals(0x30, half[0].toInt() and 0xF0)    // pixels 0,1 black (0), 2,3 white (1)
    }

    @Test fun unembedsOnlyStandardSimpleFonts() {
        PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            fun font(name: String, subtype: COSName): COSDictionary {
                val desc = COSDictionary().apply { setName(COSName.FONT_NAME, name); setItem(COSName.FONT_FILE2, COSStream()) }
                return COSDictionary().apply { setItem(COSName.TYPE, COSName.FONT); setItem(COSName.SUBTYPE, subtype); setName(COSName.BASE_FONT, name); setItem(COSName.FONT_DESC, desc) }
            }
            val helv = font("ABCDEF+Helvetica", COSName.TRUE_TYPE); val other = font("MyFont", COSName.TRUE_TYPE)
            page.resources = PDResources().apply { cosObject.setItem(COSName.FONT, COSDictionary().apply { setItem(COSName.getPDFName("F1"), helv); setItem(COSName.getPDFName("F2"), other) }) }
            assertEquals(1, OptimizeExtras.unembedFonts(doc, FontUnembed.STANDARD))
            assertFalse((helv.getDictionaryObject(COSName.FONT_DESC) as COSDictionary).containsKey(COSName.FONT_FILE2))
            assertEquals("Helvetica", helv.getCOSName(COSName.BASE_FONT).name)
            assertTrue((other.getDictionaryObject(COSName.FONT_DESC) as COSDictionary).containsKey(COSName.FONT_FILE2))
            assertEquals(1, OptimizeExtras.unembedFonts(doc, FontUnembed.ALL))
            assertEquals(0, OptimizeExtras.unembedFonts(doc, FontUnembed.NONE))
            assertTrue(OptimizeExtras.isStandard("XYZABC+Arial,Bold")); assertFalse(OptimizeExtras.isStandard("NotoNaskhArabic"))
        }
    }

    @Test fun namedDestinationsAreResolvedBeforeRemoval() {
        PDDocument().use { doc ->
            repeat(3) { doc.addPage(PDPage()) }
            val dest = PDPageFitDestination().apply { setPage(doc.getPage(2)) }
            doc.documentCatalog.cosObject.setItem(COSName.DESTS, COSDictionary().apply { setItem(COSName.getPDFName("chap3"), dest.cosObject) })
            val link = PDAnnotationLink().apply { action = PDActionGoTo().apply { destination = PDNamedDestination("chap3") } }
            doc.getPage(0).annotations = mutableListOf(link)
            assertEquals(1, OptimizeExtras.removeNamedDestinations(doc))
            assertFalse(doc.documentCatalog.cosObject.containsKey(COSName.DESTS))
            val d = (link.cosObject.getDictionaryObject(COSName.A) as COSDictionary).getDictionaryObject(COSName.D)
            assertTrue(d is COSArray)
            assertEquals(2, (PDPageDestination.create(d) as PDPageDestination).retrievePageNumber())
            assertTrue(COSString("x").string == "x")
        }
    }

    @Test fun tipsFollowTheAudit() {
        val imagesHeavy = SpaceAudit(5_000_000, mapOf(SpaceCat.IMAGES to 4_000_000L, SpaceCat.FONTS to 100_000L))
        val t = OptimizeExtras.tips(imagesHeavy, OptimizeOptions(imageDpi = 150, jpegQuality = 70), false)
        assertTrue(OptimizeTip.LOWER_DPI in t && OptimizeTip.LOWER_QUALITY in t && OptimizeTip.MONO_CCITT in t)
        assertFalse(OptimizeTip.FONTS in t)
        assertEquals(listOf(OptimizeTip.ALREADY_SMALL), OptimizeExtras.tips(SpaceAudit(100_000, emptyMap()), OptimizeOptions(), false))
        val fonts = SpaceAudit(1_000_000, mapOf(SpaceCat.FONTS to 600_000L))
        assertTrue(OptimizeTip.FONTS in OptimizeExtras.tips(fonts, OptimizeOptions(), false))
    }
}
