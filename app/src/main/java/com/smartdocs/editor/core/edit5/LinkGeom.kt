package com.smartdocs.editor.core.edit5

/**
 * Geometry for moving, resizing, aligning and distributing several links (Nitro "Edit links": align, distribute,
 * same size). Plain numbers in PDF user space (y up), so it runs in JVM tests.
 */
object LinkGeom {
    data class Box(val left: Float, val bottom: Float, val right: Float, val top: Float) {
        val width get() = right - left
        val height get() = top - bottom
        val cx get() = (left + right) / 2
        val cy get() = (bottom + top) / 2
        fun moved(dx: Float, dy: Float) = Box(left + dx, bottom + dy, right + dx, top + dy)
    }

    enum class Align { LEFT, RIGHT, TOP, BOTTOM, CENTER_H, CENTER_V }
    enum class Spread { HORIZONTAL, VERTICAL }
    enum class Size { WIDTH, HEIGHT, BOTH }

    /** Align [boxes] on the first one (the anchor, as in Nitro: the first selected link stays). */
    fun align(boxes: List<Box>, mode: Align): List<Box> {
        val a = boxes.firstOrNull() ?: return boxes
        return boxes.map { b ->
            when (mode) {
                Align.LEFT -> b.moved(a.left - b.left, 0f)
                Align.RIGHT -> b.moved(a.right - b.right, 0f)
                Align.TOP -> b.moved(0f, a.top - b.top)
                Align.BOTTOM -> b.moved(0f, a.bottom - b.bottom)
                Align.CENTER_H -> b.moved(a.cx - b.cx, 0f)        // same vertical centre line
                Align.CENTER_V -> b.moved(0f, a.cy - b.cy)        // same horizontal centre line
            }
        }
    }

    /** Spread [boxes] evenly between the two outermost ones (equal gaps). Order of the result = order of the input. */
    fun distribute(boxes: List<Box>, mode: Spread): List<Box> {
        if (boxes.size < 3) return boxes
        val idx = boxes.indices.sortedBy { if (mode == Spread.HORIZONTAL) boxes[it].left else -boxes[it].top }
        val out = boxes.toMutableList()
        if (mode == Spread.HORIZONTAL) {
            val first = boxes[idx.first()]; val last = boxes[idx.last()]
            val total = idx.sumOf { boxes[it].width.toDouble() }.toFloat()
            val gap = ((last.right - first.left) - total) / (boxes.size - 1)
            var x = first.left
            for (i in idx) { val b = boxes[i]; out[i] = b.moved(x - b.left, 0f); x += b.width + gap }
        } else {
            val first = boxes[idx.first()]; val last = boxes[idx.last()]
            val total = idx.sumOf { boxes[it].height.toDouble() }.toFloat()
            val gap = ((first.top - last.bottom) - total) / (boxes.size - 1)
            var y = first.top
            for (i in idx) { val b = boxes[i]; out[i] = b.moved(0f, y - b.top); y -= b.height + gap }
        }
        return out
    }

    /** Give every box the size of the first one (kept at its own top-left corner). */
    fun sameSize(boxes: List<Box>, mode: Size): List<Box> {
        val a = boxes.firstOrNull() ?: return boxes
        return boxes.map { b ->
            val w = if (mode != Size.HEIGHT) a.width else b.width
            val h = if (mode != Size.WIDTH) a.height else b.height
            Box(b.left, b.top - h, b.left + w, b.top)
        }
    }

    /** Map QuadPoints (8 numbers per quad) from box [from] to box [to] (translate + scale). */
    fun mapQuads(q: FloatArray, from: Box, to: Box): FloatArray {
        val sx = if (from.width != 0f) to.width / from.width else 1f
        val sy = if (from.height != 0f) to.height / from.height else 1f
        return FloatArray(q.size) { i -> if (i % 2 == 0) to.left + (q[i] - from.left) * sx else to.bottom + (q[i] - from.bottom) * sy }
    }
}
