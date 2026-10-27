package com.smartdocs.editor.core.tools5

import android.graphics.Bitmap
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject

/**
 * Scan-oriented optimizer steps (Android: they decode images to bitmaps):
 * - [mrc]: Foxit "High compression (MRC)" / "Optimize scanned PDF": a scanned page image becomes a 1-bit text layer
 *   (CCITT G4 stencil in the ink colour) over a low-resolution JPEG background with the paper whitened;
 * - [mergeFragments]: "Detect and merge image fragments" (images stacked in strips become one image).
 */
object OptimizeScan {

    /** Images at least this big (pixels on the long side) are treated as scans by [mrc]. */
    const val MRC_MIN_SIDE = 1000

    /**
     * An image as a form XObject drawing the MRC layers, or null when the image is not a plain scan (masks,
     * 1-bit, small) or the layers are not smaller. The form fills the unit square, so it replaces the image in
     * place (`Do` keeps its matrix and every other page object stays).
     */
    fun mrc(doc: PDDocument, img: PDImageXObject, oldLen: Long, bgQuality: Int = 45, bgFactor: Int = 3): PDFormXObject? {
        if (img.isStencil || img.bitsPerComponent == 1 || img.cosObject.containsKey(COSName.SMASK) || img.cosObject.containsKey(COSName.MASK)) return null
        val w = img.width; val h = img.height
        if (maxOf(w, h) < MRC_MIN_SIDE) return null
        val bmp = runCatching { img.image }.getOrNull() ?: return null
        val px = IntArray(w * h)
        try { bmp.getPixels(px, 0, w, 0, 0, w, h) } finally { bmp.recycle() }
        PixelOps.normalizeBackground(px, w, h)
        val bw = px.copyOf()
        PixelOps.sauvola(bw, w, h)
        // ink mask (0 = paint) and the mean ink colour
        val rb = (w + 7) / 8
        val mask = ByteArray(rb * h) { 0xFF.toByte() }
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (PixelOps.lum(bw[i]) < 128) {
                mask[y * rb + (x shr 3)] = (mask[y * rb + (x shr 3)].toInt() and (0x80 ushr (x and 7)).inv()).toByte()
                val c = px[i]; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                px[i] = -1   // the background layer does not carry the ink
            }
        }
        if (n == 0L || n > w.toLong() * h / 3) return null   // no text, or a photo: not a text scan
        val ink = if (n == 0L) intArrayOf(0, 0, 0) else intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        val nw = maxOf(1, w / bgFactor); val nh = maxOf(1, h / bgFactor)
        val small = Resample.average(px, w, h, nw, nh)
        val bgBmp = Bitmap.createBitmap(small, nw, nh, Bitmap.Config.ARGB_8888)
        val bg = try { JPEGFactory.createFromImage(doc, bgBmp, bgQuality / 100f) } finally { bgBmp.recycle() }
        val stencil = doc.document.createCOSStream().apply {
            setItem(COSName.TYPE, COSName.XOBJECT); setItem(COSName.SUBTYPE, COSName.IMAGE)
            setInt(COSName.WIDTH, w); setInt(COSName.HEIGHT, h); setBoolean(COSName.IMAGE_MASK, true); setInt(COSName.BITS_PER_COMPONENT, 1)
            setItem(COSName.FILTER, COSName.CCITTFAX_DECODE)
            setItem(COSName.DECODE_PARMS, COSDictionary().apply { setInt(COSName.K, -1); setInt(COSName.COLUMNS, w); setInt(COSName.ROWS, h) })
            createRawOutputStream().use { it.write(Ccitt.encodeG4(mask, w, h)) }
        }
        val maskImg = PDImageXObject(PDStream(stencil), null)
        val form = PDFormXObject(doc)
        form.bBox = PDRectangle(0f, 0f, 1f, 1f)
        val res = PDResources()
        val bgName = res.add(bg); val mName = res.add(maskImg)
        form.resources = res
        form.contentStream.createOutputStream(COSName.FLATE_DECODE).use { os ->
            val k = ink.map { it / 255f }
            os.write(("q /${bgName.name} Do Q q %.3f %.3f %.3f rg /${mName.name} Do Q".format(java.util.Locale.US, k[0], k[1], k[2])).toByteArray())
        }
        val newLen = runCatching { bg.cosObject.length + stencil.length }.getOrDefault(Long.MAX_VALUE)
        return if (newLen < oldLen * 0.9) form else null
    }

    private class Block(val start: Int, val end: Int, val a: Float, val d: Float, val e: Float, val f: Float, val name: COSName, val img: PDImageXObject)

    /**
     * Merges runs of `q a 0 0 d e f cm /Im Do Q` blocks whose images are stacked edge to edge (same x and width)
     * into one image. Returns the number of fragments merged away.
     */
    fun mergeFragments(doc: PDDocument, page: PDPage): Int {
        val res = page.resources ?: return 0
        val parser = PDFStreamParser(page); parser.parse()
        val t = parser.tokens
        fun op(i: Int, name: String) = (t.getOrNull(i) as? Operator)?.name == name
        fun num(i: Int) = (t.getOrNull(i) as? COSNumber)?.floatValue()
        val blocks = ArrayList<Block>()
        var i = 0
        while (i < t.size) {
            // q a b c d e f cm /Name Do Q  → 11 tokens
            if (op(i, "q") && op(i + 7, "cm") && t.getOrNull(i + 8) is COSName && op(i + 9, "Do") && op(i + 10, "Q")) {
                val a = num(i + 1); val b = num(i + 2); val c = num(i + 3); val d = num(i + 4); val e = num(i + 5); val f = num(i + 6)
                val name = t[i + 8] as COSName
                val img = runCatching { res.getXObject(name) as? PDImageXObject }.getOrNull()
                if (a != null && b == 0f && c == 0f && d != null && e != null && f != null && img != null && !img.isStencil &&
                    !img.cosObject.containsKey(COSName.SMASK) && !img.cosObject.containsKey(COSName.MASK) && img.bitsPerComponent == 8) {
                    blocks += Block(i, i + 11, a, d, e, f, name, img); i += 11; continue
                }
            }
            i++
        }
        // runs of consecutive blocks (adjacent in the token list) that stack vertically
        val runs = ArrayList<List<Block>>()
        var cur = ArrayList<Block>()
        for (bk in blocks) {
            val last = cur.lastOrNull()
            val joins = last != null && last.end == bk.start && kotlin.math.abs(last.a - bk.a) < 0.5f && kotlin.math.abs(last.e - bk.e) < 0.5f &&
                (kotlin.math.abs(last.f - (bk.f + bk.d)) < 0.75f || kotlin.math.abs(bk.f - (last.f + last.d)) < 0.75f) &&
                last.img.colorSpace.numberOfComponents == bk.img.colorSpace.numberOfComponents
            if (!joins) { if (cur.size > 1) runs += cur; cur = ArrayList() }
            cur += bk
        }
        if (cur.size > 1) runs += cur
        if (runs.isEmpty()) return 0
        val out = ArrayList<Any>(t)
        var merged = 0
        for (grp in runs.reversed()) {
            val top = grp.sortedByDescending { it.f + it.d }   // top strip first (PDF y grows upwards)
            val wpx = top.maxOf { it.img.width }
            val heights = top.map { (it.img.height.toFloat() * wpx / it.img.width).toInt().coerceAtLeast(1) }
            val total = heights.sum()
            if (wpx.toLong() * total > 40_000_000L) continue
            val all = IntArray(wpx * total)
            var y0 = 0
            var ok = true
            top.forEachIndexed { k, bk ->
                val bm = runCatching { bk.img.image }.getOrNull() ?: run { ok = false; return@forEachIndexed }
                val sc = if (bm.width == wpx && bm.height == heights[k]) bm else Bitmap.createScaledBitmap(bm, wpx, heights[k], true)
                sc.getPixels(all, y0 * wpx, wpx, 0, 0, wpx, heights[k])
                if (sc !== bm) sc.recycle(); bm.recycle()
                y0 += heights[k]
            }
            if (!ok) continue
            val bmp = Bitmap.createBitmap(all, wpx, total, Bitmap.Config.ARGB_8888)
            val jpeg = top.all { it.img.suffix == "jpg" }
            val made = try { if (jpeg) JPEGFactory.createFromImage(doc, bmp, 0.85f) else LosslessFactory.createFromImage(doc, bmp) } finally { bmp.recycle() }
            val name = res.add(made)
            val minF = grp.minOf { minOf(it.f, it.f + it.d) }; val height = grp.sumOf { kotlin.math.abs(it.d).toDouble() }.toFloat()
            val first = grp.first().start; val last = grp.last().end
            val repl = listOf<Any>(Operator.getOperator("q"), COSFloat(grp.first().a), COSInteger.ZERO, COSInteger.ZERO, COSFloat(height), COSFloat(grp.first().e), COSFloat(minF),
                Operator.getOperator("cm"), name, Operator.getOperator("Do"), Operator.getOperator("Q"))
            for (k in last - 1 downTo first) out.removeAt(k)
            out.addAll(first, repl)
            merged += grp.size - 1
        }
        if (merged == 0) return 0
        val s = PDStream(doc)
        s.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(out) }
        page.setContents(s)
        return merged
    }
}
