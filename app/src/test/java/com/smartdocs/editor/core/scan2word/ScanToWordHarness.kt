package com.smartdocs.editor.core.scan2word

import com.smartdocs.editor.core.convert.DocxBuilder
import com.smartdocs.editor.core.convert.LayoutAnalyzer
import com.smartdocs.editor.core.tools5.PageQuad
import com.smartdocs.editor.core.tools5.ScanEnhance
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * Tuning harness (not a CI test): runs the photo → Word pipeline on a real page photo on the desktop JVM with the
 * Tesseract CLI standing in for the phone's Tesseract. Runs only when S2W_IMAGE is set:
 *   S2W_IMAGE=page.jpg S2W_OUT=dir S2W_TESSDATA=dir ./gradlew :app:testDebugUnitTest --tests '*ScanToWordHarness'
 */
class ScanToWordHarness {
    @Test fun run() {
        val img = System.getenv("S2W_IMAGE"); assumeTrue(img != null && File(img).isFile)
        val outDir = File(System.getenv("S2W_OUT") ?: "/tmp/s2w").apply { mkdirs() }
        val tessdata = System.getenv("S2W_TESSDATA") ?: "/usr/share/tesseract-ocr/5/tessdata"
        val langs = System.getenv("S2W_LANGS") ?: "ara+eng"
        // the photo as raw big-endian ARGB with an 8-byte header (w, h) — made by the script (no AWT on the test classpath)
        val raw = java.io.DataInputStream(File(img).inputStream().buffered()).use { d -> val ww = d.readInt(); val hh = d.readInt(); Triple(IntArray(ww * hh) { d.readInt() }, ww, hh) }
        val px0 = raw.first; val w0 = raw.second; val h0 = raw.third
        // 1. the page: edges on a small copy, then perspective correction at full size
        val k = maxOf(1, maxOf(w0, h0) / 640); val sw = w0 / k; val sh = h0 / k
        val small = IntArray(sw * sh) { i -> px0[(i / sw) * k * w0 + (i % sw) * k] }
        val q = if (System.getenv("S2W_NOQUAD") == "1") null else PageQuad.detect(small, sw, sh)
        println("S2W quad=" + (q?.let { (it.x + it.y).joinToString(",") { v -> "%.3f".format(v) } } ?: "none"))
        val (px1, w1, h1) = if (q == null) Triple(px0, w0, h0) else PageQuad.warp(px0, w0, h0, q, maxDim = 3508)
        // upscale small photos so the text is at least ~30 px tall for OCR (phone photos sent by chat are small)
        val target = (System.getenv("S2W_DIM") ?: "2600").toInt()
        val (px, w, h) = if (maxOf(w1, h1) >= target) Triple(px1, w1, h1) else scale(px1, w1, h1, target.toFloat() / maxOf(w1, h1))
        write(px, w, h, File(outDir, "page_warp.png"))
        // 2. the scanner's ENHANCE filter
        if (System.getenv("S2W_ENHANCE") != "0") ScanEnhance.enhance(px, w, h)
        write(px, w, h, File(outDir, "page_clean.png"))
        val rules = ScanToWord.rules(px, w, h)
        write(ScanToWord.forOcr(px, w, h, rules), w, h, File(outDir, "page_ocr.png"))
        // 3. OCR (TSV: words, boxes, confidences, lines)
        val clean = ScanToWord.forOcr(px, w, h, ScanToWord.longRules(rules, w, h))
        val regions = ScanToWord.ocrRegions(rules, w, h)
        val psm = System.getenv("S2W_PSM") ?: "6"
        val boxes = ArrayList<ScanToWord.OcrBox>()
        val tsvAll = StringBuilder()
        regions.forEachIndexed { ri, r ->
            val rw = r[2] - r[0]; val rh = r[3] - r[1]
            val f = File(outDir, "region.png"); write(IntArray(rw * rh) { i -> clean[(r[1] + i / rw) * w + r[0] + i % rw] }, rw, rh, f)
            val base = File(outDir, "region")
            val p = ProcessBuilder("tesseract", f.path, base.path, "--tessdata-dir", tessdata, "-l", langs, "--psm", psm, "-c", "preserve_interword_spaces=1", "tsv")
                .redirectErrorStream(true).start(); p.inputStream.readBytes(); p.waitFor()
            File(base.path + ".tsv").readLines().drop(1).forEach { l ->
                val c = l.split('\t'); if (c.size < 12 || c[0] != "5") return@forEach
                val text = c[11].trim(); if (text.isEmpty()) return@forEach
                val left = c[6].toInt() + r[0]; val top = c[7].toInt() + r[1]
                boxes += ScanToWord.OcrBox(text, left, top, left + c[8].toInt(), top + c[9].toInt(), c[10].toFloat(), ri * 100000 + c[2].toInt() * 10000 + c[3].toInt() * 100 + c[4].toInt())
                tsvAll.appendLine("$ri\t$l")
            }
        }
        // second reading: lines no region reading covered, one at a time (PSM 7) on a white margin
        if (System.getenv("S2W_SECOND") != "0") ScanToWord.missedLines(clean, w, h, regions, boxes).forEachIndexed { mi, band ->
            val (pp, pw, ph) = ScanToWord.padded(clean, w, band)
            val f = File(outDir, "missed.png"); write(pp, pw, ph, f)
            val base = File(outDir, "missed")
            val p = ProcessBuilder("tesseract", f.path, base.path, "--tessdata-dir", tessdata, "-l", langs, "--psm", "7", "-c", "preserve_interword_spaces=1", "tsv")
                .redirectErrorStream(true).start(); p.inputStream.readBytes(); p.waitFor()
            File(base.path + ".tsv").readLines().drop(1).forEach { l ->
                val c = l.split('\t'); if (c.size < 12 || c[0] != "5") return@forEach
                val text = c[11].trim(); if (text.isEmpty()) return@forEach
                val left = c[6].toInt() + band[0] - 20; val top = c[7].toInt() + band[1] - 20
                boxes += ScanToWord.OcrBox(text, left, top, left + c[8].toInt(), top + c[9].toInt(), c[10].toFloat(), 90000000 + mi * 100 + c[4].toInt())
                tsvAll.appendLine("M$mi\t$l")
            }
        }
        File(outDir, "ocr.tsv").writeText(tsvAll.toString())
        println("S2W words=${boxes.size} size=${w}x$h meanConf=${"%.1f".format(boxes.map { it.conf }.average())}")
        // 4. page content → layout → Word
        val crop: (IntArray) -> Pair<ByteArray, String>? = { f ->
            val fw = f[2] - f[0]; val fh = f[3] - f[1]
            png(IntArray(fw * fh) { i -> px[(f[1] + i / fw) * w + f[0] + i % fw] }, fw, fh) to "png"
        }
        val keptRules = ScanToWord.dropWordStrokes(rules, boxes, w, h)
        File(outDir, "debug.txt").writeText(buildString {
            ScanToWord.ocrRegions(rules, w, h).forEach { appendLine("F ${it.joinToString(" ")}") }
            keptRules.forEach { appendLine("R ${if (it.horizontal) "H" else "V"} ${it.x0} ${it.y0} ${it.x1} ${it.y1}") }
            boxes.filterNot(ScanToWord::junk).forEach { appendLine("W ${it.l} ${it.t} ${it.r} ${it.b} ${ScanToWord.strokeRatio(px, w, it.l, it.t, it.r, it.b)} ${it.text}") }
        })
        val layout = ScanToWord.layout(0, px, w, h, boxes, System.getenv("S2W_FONT") ?: "Simplified Arabic", "Times New Roman", rules, crop)
        println("S2W page=${layout.width}x${layout.height} blocks=" + layout.blocks.groupingBy { it::class.simpleName }.eachCount())
        val docx = File(outDir, "page.docx")
        docx.outputStream().use { os -> DocxBuilder(os, "page", exact = System.getenv("S2W_EXACT") == "1", tight = true).use { b -> b.addPage(layout); b.finish() } }
        println("S2W docx=${docx.path} ${docx.length()}")
    }

    /** Bilinear resize by [f]. */
    private fun scale(px: IntArray, w: Int, h: Int, f: Float): Triple<IntArray, Int, Int> {
        val nw = (w * f).toInt(); val nh = (h * f).toInt()
        val out = IntArray(nw * nh)
        for (y in 0 until nh) { val sy = ((y + 0.5f) / f - 0.5f).coerceIn(0f, h - 1f); val y0 = sy.toInt(); val y1 = minOf(h - 1, y0 + 1); val fy = sy - y0
            for (x in 0 until nw) { val sx = ((x + 0.5f) / f - 0.5f).coerceIn(0f, w - 1f); val x0 = sx.toInt(); val x1 = minOf(w - 1, x0 + 1); val fx = sx - x0
                fun ch(c: Int, s: Int) = (c shr s) and 0xFF
                var o = 0xFF shl 24
                for (s in intArrayOf(16, 8, 0)) {
                    val v = ch(px[y0 * w + x0], s) * (1 - fx) * (1 - fy) + ch(px[y0 * w + x1], s) * fx * (1 - fy) + ch(px[y1 * w + x0], s) * (1 - fx) * fy + ch(px[y1 * w + x1], s) * fx * fy
                    o = o or (v.toInt().coerceIn(0, 255) shl s)
                }
                out[y * nw + x] = o } }
        return Triple(out, nw, nh)
    }

    /** A minimal RGB PNG encoder. */
    private fun png(px: IntArray, w: Int, h: Int): ByteArray {
        val bos = ByteArrayOutputStream(); val d = DataOutputStream(bos)
        d.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) { d.writeInt(data.size); val c = CRC32(); val t = type.toByteArray(); d.write(t); d.write(data); c.update(t); c.update(data); d.writeInt(c.value.toInt()) }
        chunk("IHDR", ByteArrayOutputStream().also { o -> DataOutputStream(o).apply { writeInt(w); writeInt(h); writeByte(8); writeByte(2); writeByte(0); writeByte(0); writeByte(0) } }.toByteArray())
        val z = ByteArrayOutputStream(); DeflaterOutputStream(z).use { o -> for (y in 0 until h) { o.write(0); for (x in 0 until w) { val c = px[y * w + x]; o.write((c shr 16) and 0xFF); o.write((c shr 8) and 0xFF); o.write(c and 0xFF) } } }
        chunk("IDAT", z.toByteArray()); chunk("IEND", ByteArray(0))
        return bos.toByteArray()
    }

    private fun write(px: IntArray, w: Int, h: Int, f: File) { f.writeBytes(png(px, w, h)) }
}
