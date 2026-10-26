package com.smartdocs.editor.core.convert

import kotlin.math.abs

/**
 * A glyph in PDF user space relative to the crop-box origin: baseline origin ([x], [y]), text direction ([dx], [dy]),
 * advance [w] and height [h] (points).
 */
data class RawGlyph(
    val x: Float, val y: Float, val dx: Float, val dy: Float, val w: Float, val h: Float, val text: String, val fontSize: Float,
    val bold: Boolean = false, val italic: Boolean = false, val color: Int = 0, val font: String? = null,
)

/**
 * The one affine mapping used for a page (pure Kotlin): PDF user space (crop-box origin) → displayed page (/Rotate
 * applied, top-left origin, y down) → turned by [turn] quarter turns so the dominant text direction reads left → right.
 * [width] × [height] is the final frame.
 */
class PageFrame private constructor(private val cropW: Float, private val cropH: Float, val rotation: Int, val turn: Int) {

    private val dispW = if (rotation % 2 == 1) cropH else cropW
    private val dispH = if (rotation % 2 == 1) cropW else cropH
    val width: Float = if (turn % 2 == 1) dispH else dispW
    val height: Float = if (turn % 2 == 1) dispW else dispH

    /** Crop-relative user-space point → final frame (x right, y down). */
    fun map(a: Float, b: Float): FloatArray {
        val (x, y) = when (rotation) {
            1 -> b to a
            2 -> (cropW - a) to b
            3 -> (cropH - b) to (cropW - a)
            else -> a to (cropH - b)
        }
        return when (turn) {
            1 -> floatArrayOf(y, dispW - x)
            2 -> floatArrayOf(dispW - x, dispH - y)
            3 -> floatArrayOf(dispH - y, x)
            else -> floatArrayOf(x, y)
        }
    }

    /** Glyphs in the final frame: dominant-direction glyphs as they are, other directions laid out as horizontal runs. */
    fun glyphs(raw: List<RawGlyph>): List<Glyph> {
        val out = ArrayList<Glyph>(raw.size)
        val minority = HashMap<Int, MutableList<Pair<FloatArray, RawGlyph>>>()
        for (g in raw) {
            val p = map(g.x, g.y)
            if (p[0] < -1f || p[0] > width + 1f || p[1] < -1f || p[1] > height + g.h + 1f) continue // outside the crop box
            val rel = ((displayQuarter(rotation, g.dx, g.dy) - turn) % 4 + 4) % 4
            if (rel == 0) out += Glyph(p[0], p[1] - g.h, g.w, g.h, p[1], g.text, g.fontSize, g.bold, g.italic, g.color, g.font)
            else minority.getOrPut(rel) { ArrayList() } += p to g
        }
        for ((rel, list) in minority) out += layoutMinority(rel, list)
        return out
    }

    private fun layoutMinority(rel: Int, list: List<Pair<FloatArray, RawGlyph>>): List<Glyph> {
        // reading direction of these glyphs in the final frame (y down)
        val dir = when (rel) { 1 -> 0f to 1f; 2 -> -1f to 0f; else -> 0f to -1f }
        fun along(p: FloatArray) = p[0] * dir.first + p[1] * dir.second
        fun across(p: FloatArray) = -p[0] * dir.second + p[1] * dir.first
        val sorted = list.sortedWith(compareBy({ Math.round(across(it.first) / maxOf(it.second.fontSize, 2f)) }, { along(it.first) }))
        val groups = ArrayList<MutableList<Pair<FloatArray, RawGlyph>>>()
        for (e in sorted) {
            val last = groups.lastOrNull()?.last()
            val fs = maxOf(e.second.fontSize, 2f)
            if (last == null || abs(across(e.first) - across(last.first)) > 0.6f * fs || along(e.first) - (along(last.first) + last.second.w) > 3f * fs) groups += mutableListOf(e)
            else groups.last() += e
        }
        val out = ArrayList<Glyph>()
        for (gr in groups) {
            val x0 = gr.minOf { it.first[0] }; val y0 = gr.minOf { it.first[1] }
            val start = along(gr.first().first)
            val h = gr.map { it.second.h }.sorted()[gr.size / 2]
            val base = y0 + h
            for ((p, g) in gr) out += Glyph(x0 + (along(p) - start), base - g.h, g.w, g.h, base, g.text, g.fontSize, g.bold, g.italic, g.color, g.font)
        }
        return out
    }

    companion object {
        /** Quarter turn (0 right, 1 down, 2 left, 3 up) of a user-space direction vector on the displayed page. */
        fun displayQuarter(rotation: Int, vx: Float, vy: Float): Int {
            val (fx, fy) = when (rotation) {
                1 -> vy to vx
                2 -> -vx to vy
                3 -> -vy to -vx
                else -> vx to -vy
            }
            return if (abs(fx) >= abs(fy)) (if (fx >= 0f) 0 else 2) else (if (fy > 0f) 1 else 3)
        }

        /** [rotateDeg] = the page's /Rotate; the turn follows the direction most glyphs read in. */
        fun of(cropW: Float, cropH: Float, rotateDeg: Int, raw: List<RawGlyph>): PageFrame {
            val rotation = (((rotateDeg / 90) % 4) + 4) % 4
            val counts = IntArray(4)
            raw.forEach { if (it.text.isNotBlank()) counts[displayQuarter(rotation, it.dx, it.dy)] += it.text.length }
            val turn = if (counts.sum() == 0) 0 else counts.indices.maxByOrNull { counts[it] }!!.let { if (counts[it] > counts[0]) it else 0 }
            return PageFrame(cropW, cropH, rotation, turn)
        }

        /**
         * Turn (degrees clockwise) and horizontal mirror that make an image upright in the final frame, from the frame
         * vectors of the image's x axis ([vx], [vy]) and its up axis ([ux], [uy]).
         */
        fun imageOrientation(vx: Float, vy: Float, ux: Float, uy: Float): Pair<Int, Boolean> {
            val rot = if (abs(uy) >= abs(ux)) (if (uy < 0f) 0 else 180) else (if (ux > 0f) 90 else 270)
            val mirror = vx * uy - vy * ux > 0f
            return rot to mirror
        }
    }
}
