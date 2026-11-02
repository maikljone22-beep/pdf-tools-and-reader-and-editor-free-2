package com.smartdocs.editor.core.tools5

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Red-team stress / edge cases for the pure scanner core (PageQuad, PixelOps, filters, manifests, presets, barcodes). */
class ScannerStressTest {
    private val white = 0xFFFFFFFF.toInt(); private val black = 0xFF000000.toInt()

    private fun noise(n: Int, seed: Int = 7): IntArray { val r = Random(seed); return IntArray(n) { 0xFF000000.toInt() or r.nextInt(0x1000000) } }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val t0 = System.nanoTime(); val v = block(); println("TIMING $label: ${(System.nanoTime() - t0) / 1_000_000} ms"); return v
    }

    /** A light quad (sheet) on a dark desk, corners in pixels. */
    private fun sheet(w: Int, h: Int, xs: FloatArray, ys: FloatArray): IntArray {
        val px = IntArray(w * h) { 0xFF303030.toInt() }
        for (y in 0 until h) for (x in 0 until w) {
            var inside = true
            for (i in 0 until 4) { val j = (i + 1) % 4
                val c = (xs[j] - xs[i]) * (y - ys[i]) - (ys[j] - ys[i]) * (x - xs[i]); if (c < 0) { inside = false; break } }
            if (inside) px[y * w + x] = 0xFFF2F2F2.toInt()
        }
        return px
    }

    // ------------------------------------------------------------------ PageQuad.detect

    @Test fun detectDegenerateSizesReturnNull() {
        assertNull(PageQuad.detect(IntArray(0), 0, 0))
        assertNull(PageQuad.detect(IntArray(1) { white }, 1, 1))
        assertNull(PageQuad.detect(IntArray(31 * 31) { white }, 31, 31))
        assertNull(PageQuad.detect(IntArray(5000 * 3) { white }, 5000, 3))
        assertNull(PageQuad.detect(IntArray(3 * 5000) { white }, 3, 5000))
    }

    @Test fun detectUniformAndNoiseFramesNoCrash() {
        assertNull(PageQuad.detect(IntArray(200 * 200) { white }, 200, 200))
        assertNull(PageQuad.detect(IntArray(200 * 200) { black }, 200, 200))
        PageQuad.detect(noise(320 * 240), 320, 240)   // any answer, no exception
        PageQuad.detect(IntArray(32 * 32) { white }, 32, 32)
        PageQuad.detect(noise(5000 * 32), 5000, 32)
    }

    @Test fun detectRotatedSheet() {
        val w = 480; val h = 640
        val a = Math.toRadians(12.0); val cx = w / 2f; val cy = h / 2f; val hw = 150f; val hh = 210f
        val bx = floatArrayOf(-hw, hw, hw, -hw); val by = floatArrayOf(-hh, -hh, hh, hh)
        val xs = FloatArray(4) { (cx + bx[it] * cos(a) - by[it] * sin(a)).toFloat() }
        val ys = FloatArray(4) { (cy + bx[it] * sin(a) + by[it] * cos(a)).toFloat() }
        val q = PageQuad.detect(sheet(w, h, xs, ys), w, h)
        assertNotNull(q)
        for (i in 0 until 4) assertTrue("corner $i ${q!!.x[i] * w},${q.y[i] * h} vs ${xs[i]},${ys[i]}", abs(q.x[i] * w - xs[i]) < 12 && abs(q.y[i] * h - ys[i]) < 12)
    }

    @Test fun detectLargeFrameTiming() {
        val w = 3000; val h = 4000
        val px = sheet(w, h, floatArrayOf(400f, 2600f, 2600f, 400f), floatArrayOf(500f, 500f, 3500f, 3500f))
        // a dark picture over the centre: the seed search must walk outwards
        for (y in 1200 until 2800) for (x in 900 until 2100) px[y * w + x] = 0xFF101010.toInt()
        val q = timed("PageQuad.detect 3000x4000 (dark centre)") { PageQuad.detect(px, w, h) }
        assertNotNull(q)
    }

    // ------------------------------------------------------------------ PageQuad.warp

    private fun q(vararg v: Float) = PageQuad.Quad(floatArrayOf(v[0], v[2], v[4], v[6]), floatArrayOf(v[1], v[3], v[5], v[7]))

    @Test fun warpQuadOutsideFrameAndHugeCap() {
        val src = noise(64 * 64)
        val (o, w, h) = PageQuad.warp(src, 64, 64, q(-0.5f, -0.5f, 1.5f, -0.5f, 1.5f, 1.5f, -0.5f, 1.5f))
        assertEquals(w * h, o.size)
        val (o2, w2, h2) = PageQuad.warp(src, 64, 64, q(0f, 0f, 1000f, 0f, 1000f, 1000f, 0f, 1000f), maxDim = 512)
        assertTrue(w2 <= 512 && h2 <= 512); assertEquals(w2 * h2, o2.size)
    }

    @Test fun warpCollapsedQuadNoCrash() {
        val (o, w, h) = PageQuad.warp(noise(64 * 64), 64, 64, q(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f))
        assertEquals(8, w); assertEquals(8, h); assertEquals(64, o.size)
    }

    @Test fun warpTriangleQuadNoCrash() {
        // two corners on the same point (a triangle): a singular homography
        PageQuad.warp(noise(64 * 64), 64, 64, q(0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f))
        // three corners on one line
        PageQuad.warp(noise(64 * 64), 64, 64, q(0f, 0f, 0.5f, 0f, 1f, 0f, 0f, 1f))
    }

    @Test
    fun warpInfiniteCornerNoCrash() {
        val (o, w, h) = PageQuad.warp(noise(64 * 64), 64, 64, q(0f, 0f, Float.POSITIVE_INFINITY, 0f, 1f, 1f, 0f, 1f))
        assertEquals(w * h, o.size)
    }

    @Test
    fun warpNaNCornerNoCrash() {
        val (o, w, h) = PageQuad.warp(noise(64 * 64), 64, 64, q(0f, 0f, Float.NaN, 0f, 1f, 1f, 0f, 1f))
        assertEquals(w * h, o.size)
    }

    @Test
    fun warpEmptySourceNoCrash() {
        val (o, w, h) = PageQuad.warp(IntArray(0), 0, 0, PageQuad.Quad.FULL)
        assertEquals(w * h, o.size)
    }

    @Test fun warpThinSource() {
        val (o, w, h) = timed("warp 5000x3") { PageQuad.warp(noise(5000 * 3), 5000, 3, PageQuad.Quad.FULL) }
        assertEquals(4096, w); assertEquals(8, h); assertEquals(w * h, o.size)
    }

    @Test fun warpIdentityKeepsPixels() {
        val src = noise(40 * 30)
        val (o, w, h) = PageQuad.warp(src, 40, 30, PageQuad.Quad.FULL)
        assertEquals(40, w); assertEquals(30, h)
        for (i in src.indices) assertEquals(src[i], o[i])
    }

    // ------------------------------------------------------------------ normalize / homography

    @Test fun normalizeCrossedCollapsedNaN() {
        val bow = q(0.1f, 0.1f, 0.9f, 0.9f, 0.9f, 0.1f, 0.1f, 0.9f)  // TL, BR, TR, BL: crossed
        val n = PageQuad.normalize(bow)!!
        assertEquals(0.1f, n.x[0]); assertEquals(0.1f, n.y[0]); assertEquals(0.9f, n.x[1]); assertEquals(0.1f, n.y[1])
        assertNull(PageQuad.normalize(q(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)))
        assertNull(PageQuad.normalize(q(0f, 0f, 1f, 0f, 1f, 0f, 1f, 0f)))
        assertNull(PageQuad.normalize(q(Float.NaN, 0f, 1f, 0f, 1f, 1f, 0f, 1f)))
        assertNull(PageQuad.normalize(q(Float.NEGATIVE_INFINITY, 0f, 1f, 0f, 1f, 1f, 0f, 1f)))
        assertNull(PageQuad.normalize(q(0.1f, 0.1f, 0.12f, 0.1f, 0.12f, 0.12f, 0.1f, 0.12f)))  // too small
    }

    @Test fun normalizeIsOrderInvariant() {
        val r = Random(3)
        repeat(500) {
            // a random convex quad: four angles around a centre
            val angs = List(4) { r.nextDouble(0.0, 2 * Math.PI) }.sorted()
            val xs = FloatArray(4) { (0.5 + 0.4 * cos(angs[it])).toFloat() }; val ys = FloatArray(4) { (0.5 + 0.4 * sin(angs[it])).toFloat() }
            val base = PageQuad.normalize(PageQuad.Quad(xs, ys)) ?: return@repeat
            for (s in 0 until 4) {
                val p = IntArray(4) { (it + s) % 4 }
                val rev = IntArray(4) { (s - it + 4) % 4 }
                for (perm in listOf(p, rev)) {
                    val n = PageQuad.normalize(PageQuad.Quad(FloatArray(4) { xs[perm[it]] }, FloatArray(4) { ys[perm[it]] }))!!
                    for (i in 0 until 4) { assertEquals(base.x[i], n.x[i], 1e-6f); assertEquals(base.y[i], n.y[i], 1e-6f) }
                }
            }
        }
    }

    @Test fun normalizeKeepsQuadOutsideFrame() {
        // documents current behaviour: corners dragged off the picture are kept as they are (callers must clamp)
        val n = PageQuad.normalize(q(-0.2f, -0.2f, 1.2f, -0.2f, 1.2f, 1.2f, -0.2f, 1.2f))
        assertNotNull(n)
    }

    @Test fun homographyMapsCorners() {
        val sx = floatArrayOf(0f, 100f, 100f, 0f); val sy = floatArrayOf(0f, 0f, 50f, 50f)
        val dx = floatArrayOf(10f, 90f, 120f, -5f); val dy = floatArrayOf(5f, 0f, 70f, 60f)
        val hm = PageQuad.homography(sx, sy, dx, dy)
        for (i in 0 until 4) {
            val d = hm[6] * sx[i] + hm[7] * sy[i] + 1
            assertEquals(dx[i].toDouble(), (hm[0] * sx[i] + hm[1] * sy[i] + hm[2]) / d, 1e-6)
            assertEquals(dy[i].toDouble(), (hm[3] * sx[i] + hm[4] * sy[i] + hm[5]) / d, 1e-6)
        }
        // singular input: no exception, finite or not
        PageQuad.homography(FloatArray(4), FloatArray(4), FloatArray(4), FloatArray(4))
    }

    // ------------------------------------------------------------------ sharpness, dilate / erode

    @Test fun sharpnessDegenerate() {
        assertEquals(0.0, PageQuad.sharpness(IntArray(0), 0, 0), 0.0)
        assertEquals(0.0, PageQuad.sharpness(IntArray(1), 1, 1), 0.0)
        assertEquals(0.0, PageQuad.sharpness(IntArray(16) { white }, 4, 4), 0.0)
        assertEquals(0.0, PageQuad.sharpness(IntArray(100 * 100) { white }, 100, 100), 0.0)
        assertTrue(PageQuad.sharpness(noise(100 * 100), 100, 100) > 10.0)
        PageQuad.sharpness(noise(5000 * 3), 5000, 3)
    }

    private fun bruteDilate(m: BooleanArray, w: Int, h: Int, r: Int) = BooleanArray(w * h) { i ->
        val x = i % w; val y = i / w; var any = false
        for (yy in maxOf(0, y - r)..minOf(h - 1, y + r)) for (xx in maxOf(0, x - r)..minOf(w - 1, x + r)) if (m[yy * w + xx]) any = true
        any
    }

    @Test fun dilateErodeMatchBruteForce() {
        val rnd = Random(11)
        for ((w, h) in listOf(1 to 1, 1 to 7, 7 to 1, 3 to 3, 13 to 9, 40 to 3)) for (r in listOf(0, 1, 2, 5, 50)) {
            val m = BooleanArray(w * h) { rnd.nextInt(5) == 0 }
            val d = PageQuad.dilate(m, w, h, r)
            assertTrue("dilate ${w}x$h r=$r", d.contentEquals(bruteDilate(m, w, h, r)))
            val e = PageQuad.erode(m, w, h, r)
            val inv = BooleanArray(m.size) { !m[it] }
            assertTrue("erode ${w}x$h r=$r", e.contentEquals(BooleanArray(m.size) { !bruteDilate(inv, w, h, r)[it] }))
        }
        assertTrue(PageQuad.erode(BooleanArray(25) { true }, 5, 5, 3).all { it })
        assertTrue(PageQuad.dilate(BooleanArray(0), 0, 0, 3).isEmpty())
    }

    // ------------------------------------------------------------------ ScanSavePlan

    @Test fun savePlanChunkBoundaries() {
        assertEquals(0, ScanSavePlan.chunk(-5, ScanKind.DOCUMENT, false, 3))
        assertEquals(0, ScanSavePlan.chunk(3, ScanKind.DOCUMENT, true, 1))
        assertEquals(3, ScanSavePlan.chunk(3, ScanKind.DOCUMENT, true, 2))
        assertEquals(1, ScanSavePlan.chunk(1, ScanKind.BOOK, false, 1))
        assertEquals(2, ScanSavePlan.chunk(1, ScanKind.ID_CARD, false, 1))
    }

    @Test
    fun savePlanChunkIdCardOverflow() {
        assertTrue(ScanSavePlan.chunk(Int.MAX_VALUE, ScanKind.ID_CARD, false, 1) > 0)
    }

    @Test fun savePlanCancelEdgeCases() {
        assertEquals(listOf(listOf(1, 2)), ScanSavePlan.remainingAfterCancel(listOf(listOf(1, 2)), emptyList()))
        assertEquals(emptyList<List<Int>>(), ScanSavePlan.remainingAfterCancel(listOf(listOf(1, 2)), listOf(listOf(1, 2))))
        assertEquals(emptyList<List<Int>>(), ScanSavePlan.remainingAfterCancel(emptyList<List<Int>>(), listOf(listOf(1))))
        assertEquals(listOf(listOf(3)), ScanSavePlan.remainingAfterCancel(listOf(listOf(1, 2), listOf(3)), listOf(listOf(1, 2), emptyList())))
    }

    // ------------------------------------------------------------------ ScanProjects manifest

    @Test fun projectRoundTripUnicodeLongPipes() {
        val e = PageEdit(ScanFilter.BW, 0.25f, 1.5f, 270, true, listOf(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)), true, FloatArray(8) { it / 10f })
        for (name in listOf("مسح ضوئي ١", "a|b|c", "x".repeat(100_000), "%25;=", "emoji 📄")) {
            val (n, g) = ScanProjects.decode(ScanProjects.encode(name, listOf(listOf("p0_0.jpg" to e), listOf("p1_0.png" to PageEdit()))))
            assertEquals(name, n)
            assertEquals(2, g.size); assertEquals("p0_0.jpg", g[0][0].first)
            assertEquals(ScanFilter.BW, g[0][0].second.filter); assertEquals(270, g[0][0].second.rotation)
            assertEquals(1, g[0][0].second.erase.size); assertEquals(8, g[0][0].second.crop!!.size)
        }
        // newline in the name becomes a space
        assertEquals("a b", ScanProjects.decode(ScanProjects.encode("a\nb", emptyList())).first)
    }

    @Test
    fun projectNameWithCarriageReturn() {
        assertEquals("a b", ScanProjects.decode(ScanProjects.encode("a\rb", emptyList())).first.replace('\r', ' '))
    }

    @Test fun projectDecodeCorrupt() {
        assertEquals("" to emptyList<List<Pair<String, PageEdit>>>(), ScanProjects.decode(""))
        val text = listOf(
            "name=x",
            "0|a.jpg|AUTO",                       // too few fields
            "zz|b.jpg|AUTO|0|1|0",                // bad group
            "0|../c.jpg|AUTO|0|1|0",              // traversal
            "0|d/e.jpg|AUTO|0|1|0",
            "0|..|AUTO|0|1|0",
            "0||AUTO|0|1|0",
            "0|ok.jpg|NOPE|abc|def|ghi|maybe|1,2,3/4,5,6,7,8|x|1,2,NaN,4,5,6,7,8|extra|pipes|||",
            "99999999999|big.jpg|AUTO|0|1|0",
            "-1|neg.jpg|GRAY|0|1|90",
        ).joinToString("\r\n")
        val (n, g) = ScanProjects.decode(text)
        assertEquals("x", n)
        val all = g.flatten()
        assertEquals(listOf("neg.jpg", "ok.jpg"), all.map { it.first })
        val ok = all[1].second
        assertEquals(ScanFilter.AUTO, ok.filter); assertEquals(0f, ok.brightness); assertEquals(1f, ok.contrast); assertEquals(0, ok.rotation)
        assertFalse(ok.despeckle); assertEquals(0, ok.erase.size); assertNull(ok.crop)
    }

    @Test
    fun projectDecodeRejectsNonFiniteEdits() {
        val (_, g) = ScanProjects.decode("name=x\n0|a.jpg|AUTO|NaN|Infinity|45\n")
        val e = g[0][0].second
        assertTrue(e.brightness.isFinite() && e.contrast.isFinite())
        assertTrue(e.rotation % 90 == 0)
    }

    // ------------------------------------------------------------------ ScanPreset

    @Test fun presetRoundTripTrickyNames() {
        for (name in listOf("a;b=c%d", "%3D", "%253B", "%%25%3B;;==", "عربي ١٢", "x".repeat(50_000), "trailing%", "=lead", "  spaced  ")) {
            val p = ScanPreset(name, ScanKind.RECEIPT, ScanPaper.A4, false, "ara+eng;x", true, 3, "A=B;C%", 2, "Times;New", 1, true)
            assertEquals(name, ScanPreset.decode(p.encode())!!.name)
            assertEquals(p, ScanPreset.decode(p.encode()))
        }
    }

    @Test fun presetRoundTripFuzz() {
        val r = Random(5); val alpha = "%;=3DB25a é|"
        repeat(2000) {
            val s = "N" + String(CharArray(r.nextInt(1, 12)) { alpha[r.nextInt(alpha.length)] })
            val p = ScanPreset(s, author = s)
            assertEquals(s, ScanPreset.decode(p.encode())!!.name)
            assertEquals(s, ScanPreset.decode(p.encode())!!.author)
        }
    }

    @Test
    fun presetCarriageReturnInName() {
        val list = listOf(ScanPreset("a\rb", perFile = 4), ScanPreset("c"))
        val back = ScanPreset.decodeAll(ScanPreset.encodeAll(list))
        assertEquals(2, back.size); assertEquals(4, back[0].perFile)
    }

    @Test fun presetDecodeGarbage() {
        assertNull(ScanPreset.decode(""))
        assertNull(ScanPreset.decode("name"))
        assertNull(ScanPreset.decode("=x;name="))
        assertNull(ScanPreset.decode(";;;;"))
        val p = ScanPreset.decode("kind=NOPE;name=a=b;per=x;format=9;size=-1;fingers=yes;ocr=TRUE;;;extra=1")!!
        assertEquals("a=b", p.name); assertEquals(ScanKind.DOCUMENT, p.kind); assertEquals(0, p.perFile)
        assertNull(p.format); assertNull(p.size); assertNull(p.fingers); assertTrue(p.ocr)
        assertEquals(emptyList<ScanPreset>(), ScanPreset.decodeAll("\n\n  \n"))
    }

    // ------------------------------------------------------------------ ScanExtras.chunks

    @Test fun chunksBoundaries() {
        assertEquals(listOf(listOf(1, 2, 3)), ScanExtras.chunks(listOf(1, 2, 3), 0))
        assertEquals(listOf(listOf(1, 2, 3)), ScanExtras.chunks(listOf(1, 2, 3), -1))
        assertEquals(listOf(listOf(1, 2, 3)), ScanExtras.chunks(listOf(1, 2, 3), 3))
        assertEquals(listOf(listOf(1, 2), listOf(3)), ScanExtras.chunks(listOf(1, 2, 3), 2))
        assertEquals(listOf(listOf(1), listOf(2), listOf(3)), ScanExtras.chunks(listOf(1, 2, 3), 1))
        assertEquals(listOf(listOf(1, 2, 3)), ScanExtras.chunks(listOf(1, 2, 3), Int.MAX_VALUE))
        // documents current behaviour: no pages gives one empty document
        assertEquals(listOf(emptyList<Int>()), ScanExtras.chunks(emptyList<Int>(), 2))
    }

    // ------------------------------------------------------------------ BarcodeScan

    @Test fun isLinkCases() {
        for (s in listOf("https://a.b", "  HTTP://X", "mailto:a@b", "tel:+967", "geo:1,2", "sms:1", "market://details?id=x")) assertTrue(s, BarcodeScan.isLink(s))
        for (s in listOf("", " ", "WIFI:S:x;;", "javascript:alert(1)", "file:///sdcard/x", "intent://x", "www.example.com", "xhttps://a", "ftp://a")) assertFalse(s, BarcodeScan.isLink(s))
    }

    @Test fun decodeDegenerateNoCrash() {
        assertTrue(BarcodeScan.decode(IntArray(1) { white }, 1, 1).isEmpty())
        assertTrue(BarcodeScan.decode(IntArray(31 * 31) { black }, 31, 31).isEmpty())
        assertTrue(BarcodeScan.decode(IntArray(5000 * 3) { white }, 5000, 3).isEmpty())
        assertTrue(BarcodeScan.decode(IntArray(3 * 5000) { white }, 3, 5000).isEmpty())
        timed("BarcodeScan.decode noise 1500x2000") { BarcodeScan.decode(noise(1500 * 2000), 1500, 2000) }
    }

    @Test fun decodeEmptyImageNoCrash() {
        assertTrue(BarcodeScan.decode(IntArray(0), 0, 0).isEmpty())
    }

    @Test fun decodeTwoCodesAndThinStrip() {
        val size = 150; val w = 500; val h = 200
        val page = IntArray(w * h) { white }
        for ((k, text) in listOf("https://a.example/1", "second code").withIndex()) {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            for (y in 0 until size) for (x in 0 until size) if (m.get(x, y)) page[(y + 25) * w + x + 20 + k * 250] = black
        }
        val found = BarcodeScan.decode(page, w, h).map { it.text }.toSet()
        assertEquals(setOf("https://a.example/1", "second code"), found)
    }

    // ------------------------------------------------------------------ PixelOps / filters / enhance / orient

    @Test fun pixelOpsDegenerate() {
        PixelOps.autoLevels(IntArray(0)); PixelOps.toGray(IntArray(0))
        assertTrue(PixelOps.isBilevel(IntArray(0)))
        assertEquals(0, PixelOps.background(IntArray(0), 0, 0).size)
        assertEquals(1, PixelOps.background(IntArray(1) { white }, 1, 1).size)
        assertFalse(PixelOps.unevenLight(IntArray(1) { white }, 1, 1))
        assertFalse(PixelOps.unevenLight(IntArray(0), 0, 0))
        val one = IntArray(1) { 0xFF808080.toInt() }; PixelOps.sauvola(one, 1, 1)
        val black1 = IntArray(9) { black }; PixelOps.normalizeBackground(black1, 3, 3); assertTrue(black1.all { it == black })
        PixelOps.brightnessContrast(IntArray(4) { white }, Float.NaN, Float.NaN)
        val s = IntArray(4) { 0xFF336699.toInt() }; PixelOps.saturate(s, Float.POSITIVE_INFINITY)
        assertEquals(2, PixelOps.bindingColumn(IntArray(5), 5, 1))
        assertTrue(PixelOps.bindingColumn(IntArray(8) { white }, 8, 1) in 0 until 8)
    }

    @Test
    fun bindingColumnZeroHeightNoCrash() {
        assertTrue(PixelOps.bindingColumn(IntArray(0), 100, 0) in 0..100)
    }

    @Test
    fun sauvolaEmptyNoCrash() {
        PixelOps.sauvola(IntArray(0), 0, 0)
        assertEquals(0, ScanFilters.toBilevel(IntArray(0), 0, 0).size)
    }

    @Test fun pixelOpsThinAndHuge() {
        val thin = noise(5000 * 3)
        PixelOps.sauvola(thin.copyOf(), 5000, 3); PixelOps.normalizeBackground(thin.copyOf(), 5000, 3)
        PixelOps.unevenLight(thin, 5000, 3); PixelOps.unevenLight(noise(3 * 5000), 3, 5000)
        val w = 3000; val h = 4000
        val big = noise(w * h)
        timed("PixelOps.sauvola 3000x4000") { PixelOps.sauvola(big.copyOf(), w, h) }
        timed("PixelOps.normalizeBackground 3000x4000") { PixelOps.normalizeBackground(big.copyOf(), w, h) }
        timed("PixelOps.unevenLight 3000x4000") { PixelOps.unevenLight(big, w, h) }
    }

    @Test fun sauvolaWhiteAndBlackStayPure() {
        val wpx = IntArray(50 * 50) { white }; PixelOps.sauvola(wpx, 50, 50); assertTrue(wpx.all { it == -1 })
        val bpx = IntArray(50 * 50) { black }; PixelOps.sauvola(bpx, 50, 50); assertTrue(PixelOps.isBilevel(bpx))
    }

    @Test fun filtersDegenerate() {
        assertEquals(0, ScanFilters.descreen(IntArray(0), 0, 0).size)
        assertEquals(1, ScanFilters.descreen(IntArray(1) { white }, 1, 1).size)
        assertEquals(white, ScanFilters.sharpen(IntArray(1) { white }, 1, 1)[0])
        assertEquals(1, ScanFilters.toBilevel(IntArray(1) { white }, 1, 1).size)
        val bl = ScanFilters.toBilevel(IntArray(9 * 2) { black }, 9, 2)
        assertEquals(4, bl.size)
        val thin = ScanFilters.toBilevel(noise(5000 * 3), 5000, 3); assertEquals(625 * 3, thin.size)
        val big = noise(3000 * 4000)
        timed("ScanFilters.sharpen 3000x4000") { ScanFilters.sharpen(big, 3000, 4000) }
    }

    @Test fun enhanceDegenerate() {
        ScanEnhance.enhance(IntArray(0), 0, 0); ScanEnhance.enhance(IntArray(49) { white }, 7, 7)
        val b = IntArray(64) { black }; ScanEnhance.enhance(b, 8, 8)
        val wpx = IntArray(64 * 64) { white }; ScanEnhance.enhance(wpx, 64, 64); assertTrue(wpx.all { it == white })
        ScanEnhance.enhance(noise(5000 * 8), 5000, 8)
        ScanEnhance.enhance(noise(8 * 5000), 8, 5000)
        assertEquals(5000 * 3, ScanEnhance.pictureWeight(noise(5000 * 3), 5000, 3).size)
        timed("ScanEnhance.enhance 2000x3000") { ScanEnhance.enhance(noise(2000 * 3000), 2000, 3000) }
    }

    @Test
    fun paperMapEmptyNoCrash() {
        assertEquals(0, ScanEnhance.paperMap(IntArray(0), 0, 0).size)
    }

    @Test fun orientLineRatio() {
        assertNull(ScanOrient.lineRatio(IntArray(15 * 15), 15, 15))
        assertNull(ScanOrient.lineRatio(IntArray(100 * 100) { white }, 100, 100))
        assertNull(ScanOrient.lineRatio(IntArray(100 * 100) { black }, 100, 100))
        val w = 200; val h = 200
        val rows = IntArray(w * h) { white }; for (y in 20 until 180 step 10) for (x in 20 until 180) rows[y * w + x] = black
        assertTrue(ScanOrient.lineRatio(rows, w, h)!! > 1f); assertFalse(ScanOrient.sideways(rows, w, h))
        val cols = IntArray(w * h) { white }; for (x in 20 until 180 step 10) for (y in 20 until 180) cols[y * w + x] = black
        assertTrue(ScanOrient.lineRatio(cols, w, h)!! < 1f); assertTrue(ScanOrient.sideways(cols, w, h))
        ScanOrient.lineRatio(noise(5000 * 16), 5000, 16); ScanOrient.lineRatio(noise(16 * 5000), 16, 5000)
    }

    @Test fun despeckleAndEraseEdges() {
        val px = IntArray(10 * 10) { white }; px[55] = black
        assertEquals(1, ScanClean.despeckle(px, 10, 10)); assertEquals(white, px[55])
        assertEquals(0, ScanClean.despeckle(IntArray(0), 0, 0))
        val e = IntArray(10 * 10) { black }
        ScanClean.erase(e, 10, 10, listOf(floatArrayOf(Float.NaN, -1f, 2f, Float.POSITIVE_INFINITY), floatArrayOf(0.5f), floatArrayOf(0.9f, 0.9f, 0.1f, 0.1f)))
        ScanClean.erase(IntArray(0), 0, 0, listOf(floatArrayOf(0f, 0f, 1f, 1f)))
        val big = IntArray(2000 * 2000) { black }
        timed("ScanClean.despeckle 2000x2000 all-dark") { ScanClean.despeckle(big, 2000, 2000) }
        assertEquals(0, ScanClean.removeFingers(IntArray(0), 0, 0))
    }
}
