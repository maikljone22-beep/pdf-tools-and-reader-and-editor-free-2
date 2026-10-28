package com.smartdocs.editor.core.forms

import com.smartdocs.editor.core.util.BarcodeEncoder

/**
 * nav-prefs (Nitro §24 / Foxit §37 "barcode form fields"): draws the barcode of an XFA barcode field as filled boxes.
 * Encoding goes through the app's shared zxing encoder ([BarcodeEncoder], also used by the form designer's barcode
 * fields): QR, Data Matrix, PDF417 and the linear Code 128 / 39 / 93, EAN-13 / 8, UPC-A / E, ITF and Codabar.
 * Other symbologies (postal codes, Code 11, …) return null and the caller shows the value as text.
 */
object NpfBarcode {

    /** Filled boxes for XFA [type] / [value] inside (x, y, w, h); null = not drawable (the caller shows the value). */
    fun boxes(type: String, value: String, x: Float, y: Float, w: Float, h: Float): List<XItem>? {
        val kind = BarcodeEncoder.fromXfa(type) ?: return null
        if (value.isEmpty()) return null
        return if (kind.twoD) BarcodeEncoder.matrix(kind, value)?.let { matrix(it, x, y, w, h) }
        else BarcodeEncoder.linear(kind, value)?.let { linear(runs(it), x, y, w, h) }
    }

    /** Pure: alternating bar / space run lengths of a module row, starting with a bar (a leading space is dropped). */
    fun runs(modules: BooleanArray): IntArray {
        val first = modules.indexOfFirst { it }
        if (first < 0) return IntArray(0)
        val out = ArrayList<Int>()
        var cur = true; var n = 0
        for (i in first until modules.size) {
            if (modules[i] == cur) n++ else { out += n; cur = modules[i]; n = 1 }
        }
        if (cur) out += n   // a trailing space run is dropped
        return out.toIntArray()
    }

    /** Pure: x offsets and widths of the bars of [widths] scaled into [w] (quiet zone of 10 modules on each side). */
    fun bars(widths: IntArray, x: Float, w: Float): List<Pair<Float, Float>> {
        val modules = widths.sum() + 20
        val m = w / modules
        val out = ArrayList<Pair<Float, Float>>()
        var cx = x + 10 * m
        widths.forEachIndexed { i, n -> if (i % 2 == 0) out += cx to n * m; cx += n * m }
        return out
    }

    private fun linear(widths: IntArray, x: Float, y: Float, w: Float, h: Float): List<XItem> =
        bars(widths, x, w).map { (bx, bw) -> XItem.Box(bx, y, bw, h, List(4) { null }, BLACK) }

    /** A 2D symbol centred in the box with a 4-module quiet zone; PDF417 rows are [BarcodeEncoder.Matrix.rowHeight] tall. */
    private fun matrix(mx: BarcodeEncoder.Matrix, x: Float, y: Float, w: Float, h: Float): List<XItem>? {
        val rows = mx.modules.size
        if (rows == 0) return null
        val cols = mx.modules[0].size
        val cell = minOf(w / (cols + 8), h / (rows * mx.rowHeight + 8))
        val cellH = cell * mx.rowHeight
        val ox = x + (w - cols * cell) / 2; val oy = y + (h - rows * cellH) / 2
        val out = ArrayList<XItem>()
        for (r in 0 until rows) {
            val row = mx.modules[r]
            var c = 0
            while (c < cols) {
                if (!row[c]) { c++; continue }
                val start = c
                while (c < cols && row[c]) c++
                out += XItem.Box(ox + start * cell, oy + r * cellH, (c - start) * cell, cellH, List(4) { null }, BLACK)
            }
        }
        return out
    }

    private const val BLACK = 0xFF000000.toInt()
}
