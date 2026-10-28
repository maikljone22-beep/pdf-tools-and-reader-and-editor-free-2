package com.smartdocs.editor.core.ocr

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure maths of the PP-OCRv5 pipeline ("Pro offline" engine, PRO_OCR_r5): DB text-detection post-processing, greedy CTC
 * decoding, visual -> logical Arabic order, line building and reading order (XY-cut, right-to-left columns). No Android
 * types, so every step runs in JVM unit tests; [ProOcr] feeds it the ONNX Runtime outputs. It mirrors the PC harness
 * `ppocr.py` that was measured on the ground-truth pages.
 */
object ProOcrCore {

    /** A detected text box in image pixels. */
    data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val score: Float)

    /** One decoded character: [c], its x centre in image pixels and the CTC probability. */
    data class Ch(val c: String, val x: Float, val p: Float)

    /** Detection input size: the longer side scaled to [limit] (never up), both sides rounded to multiples of 32. */
    fun detSize(w: Int, h: Int, limit: Int): Pair<Int, Int> {
        val r = if (limit > 0) min(1.0, limit.toDouble() / max(w, h)) else 1.0
        val nw = max(32, (w * r / 32).roundToInt() * 32)
        val nh = max(32, (h * r / 32).roundToInt() * 32)
        return nw to nh
    }

    /**
     * DB post-processing: connected components (4-neighbour) of `prob > thresh` on the [pw]×[ph] probability map; a
     * component is kept when its mean probability is ≥ [boxThresh]; its box is grown by the DB unclip offset
     * (area × [unclip] / perimeter) and scaled to the [imgW]×[imgH] image.
     */
    fun dbBoxes(prob: FloatArray, pw: Int, ph: Int, imgW: Int, imgH: Int, thresh: Float = 0.3f, boxThresh: Float = 0.5f, unclip: Float = 1.6f): List<Box> {
        val n = pw * ph
        val label = IntArray(n)
        val out = ArrayList<Box>()
        val stack = IntArray(n)
        val sx = imgW.toFloat() / pw; val sy = imgH.toFloat() / ph
        var next = 0
        for (start in 0 until n) {
            if (label[start] != 0 || prob[start] <= thresh) continue
            next++
            var sp = 0; stack[sp++] = start; label[start] = next
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
            var area = 0; var sum = 0.0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % pw; val y = i / pw
                area++; sum += prob[i]
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0) { val j = i - 1; if (label[j] == 0 && prob[j] > thresh) { label[j] = next; stack[sp++] = j } }
                if (x < pw - 1) { val j = i + 1; if (label[j] == 0 && prob[j] > thresh) { label[j] = next; stack[sp++] = j } }
                if (y > 0) { val j = i - pw; if (label[j] == 0 && prob[j] > thresh) { label[j] = next; stack[sp++] = j } }
                if (y < ph - 1) { val j = i + pw; if (label[j] == 0 && prob[j] > thresh) { label[j] = next; stack[sp++] = j } }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (bw < 3 || bh < 3) continue
            val score = (sum / area).toFloat()
            if (score < boxThresh) continue
            val d = area * unclip / (2f * (bw + bh))
            out += Box(
                max(0, ((minX - d) * sx).toInt()), max(0, ((minY - d) * sy).toInt()),
                min(imgW, ceil((minX + bw + d) * sx).toInt()), min(imgH, ceil((minY + bh + d) * sy).toInt()), score)
        }
        return out
    }

    /** Recognition input width for a crop of [w]×[h] scaled to height [height] (16..[maxW]). */
    fun recWidth(w: Int, h: Int, height: Int = 48, maxW: Int = 3200): Int = ceil(height.toDouble() * w / max(1, h)).toInt().coerceIn(16, maxW)

    /**
     * Greedy CTC over [t]×[c] softmax rows ([probs] row-major): repeated and blank (index 0) labels collapse. [dict] holds the
     * model's characters (index i+1 = dict[i]; the last class is the space). x is mapped to the crop's [x0]..[x0]+[cropW].
     */
    fun ctcDecode(probs: FloatArray, t: Int, c: Int, dict: List<String>, x0: Int, cropW: Int): List<Ch> {
        val out = ArrayList<Ch>()
        var prev = -1
        for (step in 0 until t) {
            var best = 0; var bp = -1f; val base = step * c
            for (k in 0 until c) { val v = probs[base + k]; if (v > bp) { bp = v; best = k } }
            if (best != prev && best != 0) {
                val s = if (best - 1 < dict.size) dict[best - 1] else " "
                out += Ch(s, x0 + (step + 0.5f) * cropW / t, bp)
            }
            prev = best
        }
        return out
    }

    /** The characters of a PP-OCR dictionary file (one per line, BOM and CR removed); the space class is appended by [ctcDecode]. */
    fun parseDict(text: String): List<String> = text.removePrefix("﻿").split('\n').map { it.removeSuffix("\r") }.let { l ->
        if (l.isNotEmpty() && l.last().isEmpty()) l.dropLast(1) else l
    }

    private fun isLtr(c: String): Boolean = c.length == 1 && c[0].let { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it.code in 0x0660..0x0669 || it.code in 0x06F0..0x06F9 }
    private const val NUMSEP = ".,:/%+-×٫٬="
    private val MIRROR = mapOf("(" to ")", ")" to "(", "[" to "]", "]" to "[", "{" to "}", "}" to "{", "<" to ">", ">" to "<", "«" to "»", "»" to "«")

    /** Arabic letter (not an Arabic-Indic digit). */
    fun isArabic(s: String): Boolean = s.any { ch -> (ch.code in 0x0600..0x06FF && ch.code !in 0x0660..0x0669 && ch.code !in 0x06F0..0x06F9) || ch.code in 0xFB50..0xFEFC }

    /**
     * The recogniser reads pixels left to right. With an Arabic letter in the run the text is right-to-left: reversed,
     * brackets mirrored, but runs of Latin letters / digits (with number punctuation between them) keep their order.
     */
    fun visualToLogical(chars: List<Ch>): List<Ch> {
        if (chars.none { isArabic(it.c) }) return chars
        val runs = ArrayList<List<Ch>>(); var cur = ArrayList<Ch>()
        chars.forEachIndexed { i, ch ->
            val ltr = isLtr(ch.c) || (ch.c.length == 1 && ch.c[0] in NUMSEP && cur.isNotEmpty() && i + 1 < chars.size && isLtr(chars[i + 1].c))
            if (ltr) cur.add(ch)
            else {
                if (cur.isNotEmpty()) { runs += cur; cur = ArrayList() }
                runs += listOf(ch.copy(c = MIRROR[ch.c] ?: ch.c))
            }
        }
        if (cur.isNotEmpty()) runs += cur
        return runs.asReversed().flatten()
    }

    /** A recognised detection box. */
    class Seg(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val chars: List<Ch>) {
        val arabic: Int = chars.count { isArabic(it.c) }
        val latin: Int = chars.count { it.c.length == 1 && it.c[0].let { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' } }
    }

    /** Line chunk: segments on the same row closer than [gapK] × their height. */
    class Line(var x0: Int, var y0: Int, var x1: Int, var y1: Int) { val segs = ArrayList<Seg>() }

    fun makeLines(segs: List<Seg>, gapK: Float = 2f): List<Line> {
        val lines = ArrayList<Line>()
        for (s in segs.sortedBy { it.x0 }) {
            val hh = s.y1 - s.y0
            val best = lines.firstOrNull { l ->
                val ov = min(s.y1, l.y1) - max(s.y0, l.y0)
                val mh = min(hh, l.y1 - l.y0)
                ov >= 0.5f * mh && max(l.x0 - s.x1, s.x0 - l.x1) < gapK * mh
            }
            if (best == null) lines += Line(s.x0, s.y0, s.x1, s.y1).also { it.segs += s }
            else { best.segs += s; best.x0 = min(best.x0, s.x0); best.y0 = min(best.y0, s.y0); best.x1 = max(best.x1, s.x1); best.y1 = max(best.y1, s.y1) }
        }
        return lines
    }

    private fun gaps(spans: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val iv = spans.sortedBy { it.first }
        val out = ArrayList<Pair<Int, Int>>(); var end = iv.first().second
        for ((s, e) in iv.drop(1)) { if (s > end) out += end to s; end = max(end, e) }
        return out
    }

    /** Reading order: split at the widest vertical gap into columns (right first when [rtl]), else at horizontal gaps into rows. */
    fun xyCut(items: List<Line>, rtl: Boolean): List<Line> {
        if (items.size <= 1) return items
        val medH = items.map { it.y1 - it.y0 }.sorted()[items.size / 2]
        val vs = gaps(items.map { it.x0 to it.x1 }).filter { it.second - it.first >= 0.8f * medH }
        if (vs.isNotEmpty()) {
            val g = vs.maxBy { it.second - it.first }; val cut = (g.first + g.second) / 2f
            val l = items.filter { (it.x0 + it.x1) / 2f < cut }; val r = items.filter { (it.x0 + it.x1) / 2f >= cut }
            if (l.isNotEmpty() && r.isNotEmpty()) return if (rtl) xyCut(r, rtl) + xyCut(l, rtl) else xyCut(l, rtl) + xyCut(r, rtl)
        }
        val hs = gaps(items.map { it.y0 to it.y1 })
        if (hs.isNotEmpty()) {
            val g = hs.maxBy { it.second - it.first }; val cut = (g.first + g.second) / 2f
            val t = items.filter { (it.y0 + it.y1) / 2f < cut }; val b = items.filter { (it.y0 + it.y1) / 2f >= cut }
            if (t.isNotEmpty() && b.isNotEmpty()) return xyCut(t, rtl) + xyCut(b, rtl)
        }
        return if (rtl) items.sortedByDescending { it.x1 } else items.sortedBy { it.x0 }
    }

    /** Words (logical order, reading order, line ids) from the recognised segments of one page. Confidence 0..100. */
    fun words(segs: List<Seg>): List<OcrRefine.Tok> {
        val pageRtl = segs.sumOf { it.arabic } >= segs.sumOf { it.latin } * 0.5
        val out = ArrayList<OcrRefine.Tok>()
        xyCut(makeLines(segs), pageRtl).forEachIndexed { li, line ->
            val rtl = line.segs.any { it.arabic > 0 } || pageRtl
            val ordered = if (rtl) line.segs.sortedByDescending { it.x1 } else line.segs.sortedBy { it.x0 }
            for (s in ordered) {
                val log = if (rtl) visualToLogical(s.chars) else s.chars
                val pad = (s.y1 - s.y0) * 0.25f
                var cur = ArrayList<Ch>()
                fun flush() {
                    if (cur.isEmpty()) return
                    val xs = cur.map { it.x }
                    val text = cur.joinToString("") { it.c }
                    out += OcrRefine.Tok(text, max(s.x0, (xs.min() - pad).toInt()), s.y0, min(s.x1, (xs.max() + pad).toInt()), s.y1,
                        (cur.map { it.p }.average() * 100).toFloat(), li)
                    cur = ArrayList()
                }
                for (ch in log) if (ch.c.isBlank()) flush() else cur.add(ch)
                flush()
            }
        }
        return out
    }
}
