package com.smartdocs.editor.core.ai6

/**
 * ai6 (C1): reading order for read aloud. PDFium returns page text in content-stream order, which mixes the
 * columns of multi-column pages. [order] rebuilds lines from the character boxes, finds column gutters, and
 * emits full-width lines (titles) in place and the column blocks between them column by column: right to left
 * for Arabic pages, left to right otherwise; top to bottom inside a column.
 * Pure Kotlin (no Android types) so it is unit tested. Box coordinates are PDF user space (y grows upwards).
 */
object ReadingOrder {

    /** A character box; [valid] false for chars without geometry (line breaks, generated spaces). */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val valid: Boolean get() = right > left && top != bottom
        val l get() = minOf(left, right)
        val r get() = maxOf(left, right)
        val t get() = maxOf(top, bottom)
        val b get() = minOf(top, bottom)
    }

    /** Reordered text; [map] gives, for each char, its index in the source text (-1 for inserted chars). */
    class Ordered(val text: String, val map: IntArray) {
        /** Source ranges (inclusive) covered by [start, end) of [text], merged into consecutive runs. */
        fun sourceRuns(start: Int, end: Int): List<IntRange> {
            val out = ArrayList<IntRange>()
            var runStart = -1; var prev = -2
            for (i in start.coerceAtLeast(0) until end.coerceAtMost(map.size)) {
                val s = map[i]
                if (s < 0) continue
                if (runStart >= 0 && s == prev + 1) { prev = s; continue }
                if (runStart >= 0) out += runStart..prev
                runStart = s; prev = s
            }
            if (runStart >= 0) out += runStart..prev
            return out
        }
    }

    private class Line(val start: Int, val end: Int) {   // [start, end) in the source text
        var l = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var t = -Float.MAX_VALUE; var b = Float.MAX_VALUE
        var has = false
        fun add(x: Box) { if (!x.valid) return; has = true; l = minOf(l, x.l); r = maxOf(r, x.r); t = maxOf(t, x.t); b = minOf(b, x.b) }
        val cy get() = (t + b) / 2f
    }

    fun isRtlText(text: String): Boolean {
        var ar = 0; var lat = 0
        for (c in text) { if (c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ﭐ'..'﻿') ar++ else if (c.isLetter()) lat++ }
        return ar > 0 && ar >= lat
    }

    fun identity(text: String) = Ordered(text, IntArray(text.length) { it })

    fun order(text: String, boxes: List<Box?>, rtl: Boolean = isRtlText(text)): Ordered {
        if (text.isEmpty() || boxes.isEmpty()) return identity(text)
        // 1. lines: split at line breaks
        val lines = ArrayList<Line>()
        var s = 0
        for (i in text.indices) {
            if (text[i] == '\n') { if (i > s) lines += Line(s, i); s = i + 1 }
        }
        if (s < text.length) lines += Line(s, text.length)
        lines.forEach { ln -> for (i in ln.start until ln.end) boxes.getOrNull(i)?.let { ln.add(it) } }
        val geo = lines.filter { it.has }
        if (geo.size < 4) return identity(text)
        val pageL = geo.minOf { it.l }; val pageR = geo.maxOf { it.r }
        val width = pageR - pageL
        if (width <= 0f) return identity(text)
        // 2. gutters: x ranges in the middle of the page covered by no narrow line
        val narrow = geo.filter { (it.r - it.l) < width * 0.62f }
        if (narrow.size < 4) return identity(text)
        val bins = 200
        val covered = BooleanArray(bins)
        narrow.forEach { ln ->
            val a = (((ln.l - pageL) / width) * bins).toInt().coerceIn(0, bins - 1)
            val z = (((ln.r - pageL) / width) * bins).toInt().coerceIn(0, bins - 1)
            for (k in a..z) covered[k] = true
        }
        val gutters = ArrayList<Float>()   // x of each gutter centre
        var k = (bins * 0.15f).toInt()
        val stop = (bins * 0.85f).toInt()
        while (k < stop) {
            if (!covered[k]) {
                var e = k
                while (e < stop && !covered[e]) e++
                if (e - k >= 2) gutters += pageL + width * ((k + e) / 2f / bins)
                k = e
            } else k++
        }
        if (gutters.isEmpty()) return identity(text)
        // both sides of each gutter must hold text, else it is just a ragged margin
        val bounds = listOf(-Float.MAX_VALUE) + gutters + listOf(Float.MAX_VALUE)
        fun colOf(ln: Line): Int? {
            for (c in 0 until bounds.size - 1) if (ln.l >= bounds[c] && ln.r <= bounds[c + 1]) return c
            return null   // spans a gutter: full-width line
        }
        val colsUsed = narrow.mapNotNull { colOf(it) }.toSet()
        if (colsUsed.size < 2) return identity(text)
        // 3. emit: lines sorted top-down; spanning lines split the page into bands
        val sorted = lines.filter { it.has }.sortedByDescending { it.cy }
        val noGeo = lines.filter { !it.has }
        val out = StringBuilder(text.length + 16)
        val map = ArrayList<Int>(text.length + 16)
        fun emit(ln: Line) {
            if (out.isNotEmpty()) { out.append('\n'); map += -1 }
            for (i in ln.start until ln.end) { out.append(text[i]); map += i }
        }
        val band = ArrayList<Pair<Int, Line>>()
        fun flush() {
            val colOrder = band.map { it.first }.distinct().sorted().let { if (rtl) it.reversed() else it }
            colOrder.forEach { c -> band.filter { it.first == c }.forEach { emit(it.second) } }
            band.clear()
        }
        for (ln in sorted) {
            val c = colOf(ln)
            if (c == null) { flush(); emit(ln) } else band += c to ln
        }
        flush()
        noGeo.forEach { emit(it) }
        return Ordered(out.toString(), map.toIntArray())
    }

    /** One spoken piece: [text] with whitespace collapsed; [map] maps each char of it to the source index. */
    class Piece(val text: String, val map: IntArray, val sentences: List<IntRange>)

    /**
     * Split [src] into pieces of at most [max] chars at sentence ends, collapsing whitespace for speech while keeping
     * a map back to [src] offsets (for highlighting). [sentences] are char ranges of [Piece.text] (inclusive).
     */
    fun pieces(src: String, max: Int = 400): List<Piece> {
        // collapse whitespace with a map
        val sb = StringBuilder(src.length); val m = ArrayList<Int>(src.length)
        var space = true
        for (i in src.indices) {
            val c = src[i]
            if (c.isWhitespace()) { if (!space) { sb.append(' '); m += i; space = true } }
            else { sb.append(c); m += i; space = false }
        }
        while (sb.isNotEmpty() && sb.last() == ' ') { sb.setLength(sb.length - 1); m.removeAt(m.size - 1) }
        val t = sb.toString()
        if (t.isBlank()) return emptyList()
        // sentence ranges in t
        val sentences = ArrayList<IntRange>()
        var st = 0
        for (i in t.indices) {
            val c = t[i]
            val end = (c == '.' || c == '!' || c == '?' || c == '؟' || c == '。' || c == '؛') && (i + 1 == t.length || t[i + 1] == ' ')
            if (end) { sentences += st..i; st = i + 2 }
        }
        if (st < t.length) sentences += st until t.length
        // split very long sentences
        val parts = ArrayList<IntRange>()
        sentences.filter { !it.isEmpty() }.forEach { r ->
            var a = r.first
            while (r.last - a + 1 > max) { parts += a until a + max; a += max }
            if (a <= r.last) parts += a..r.last
        }
        val out = ArrayList<Piece>()
        var cur = ArrayList<IntRange>()
        fun close() {
            if (cur.isEmpty()) return
            val a = cur.first().first; val z = cur.last().last
            val text = t.substring(a, z + 1)
            val map = IntArray(text.length) { m[a + it] }
            out += Piece(text, map, cur.map { (it.first - a)..(it.last - a) })
            cur = ArrayList()
        }
        for (p in parts) {
            val len = if (cur.isEmpty()) 0 else cur.last().last - cur.first().first + 1
            if (cur.isNotEmpty() && len + 1 + (p.last - p.first + 1) > max) close()
            cur += p
        }
        close()
        return out
    }

    /** The sentence of [piece] that contains char [at] (falls back to the whole piece). */
    fun sentenceAt(piece: Piece, at: Int): IntRange = piece.sentences.firstOrNull { at in it } ?: (0 until piece.text.length)
}
