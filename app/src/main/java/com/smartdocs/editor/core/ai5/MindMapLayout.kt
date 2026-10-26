package com.smartdocs.editor.core.ai5

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/** A laid-out node: box in layout units (origin top-left), depth and the index path from the root. */
data class PlacedNode(val node: MindNode, val depth: Int, val x: Float, val y: Float, val w: Float, val h: Float, val parent: Int, val path: String)

/**
 * Tree layout for the mind map: columns per depth, leaves stacked vertically, parents centred on their children.
 * [rtl] puts the root on the right (Arabic). [collapsed] holds node paths whose children are hidden.
 */
object MindMapLayout {
    const val ROW = 46f
    const val GAP_X = 56f
    const val PAD = 14f
    const val MAX_LABEL = 34

    fun label(t: String) = if (t.length <= MAX_LABEL) t else t.take(MAX_LABEL - 1) + "…"

    fun layout(root: MindNode, measure: (String, Int) -> Float, rtl: Boolean, collapsed: Set<String> = emptySet()): List<PlacedNode> {
        val colW = HashMap<Int, Float>()
        fun widths(n: MindNode, d: Int, path: String) {
            colW[d] = maxOf(colW[d] ?: 0f, measure(label(n.title), d) + PAD * 2)
            if (path !in collapsed) n.children.forEachIndexed { i, c -> widths(c, d + 1, "$path.$i") }
        }
        widths(root, 0, "0")
        val maxD = colW.keys.maxOrNull() ?: 0
        val colX = FloatArray(maxD + 1)
        var acc = 0f
        for (d in 0..maxD) { colX[d] = acc; acc += (colW[d] ?: 0f) + GAP_X }
        val total = acc - GAP_X
        val out = ArrayList<PlacedNode>()
        var nextLeafY = 0f
        fun place(n: MindNode, d: Int, parent: Int, path: String): Float {
            val idx = out.size
            val w = colW[d] ?: 0f
            out += PlacedNode(n, d, 0f, 0f, w, ROW - 10f, parent, path)
            val kids = if (path in collapsed) emptyList() else n.children
            val y = if (kids.isEmpty()) { val y0 = nextLeafY; nextLeafY += ROW; y0 }
            else { val ys = kids.mapIndexed { i, c -> place(c, d + 1, idx, "$path.$i") }; (ys.first() + ys.last()) / 2f }
            val x = if (rtl) total - colX[d] - w else colX[d]
            out[idx] = out[idx].copy(x = x, y = y)
            return y
        }
        place(root, 0, -1, "0")
        return out
    }

    fun bounds(nodes: List<PlacedNode>): RectF {
        if (nodes.isEmpty()) return RectF(0f, 0f, 1f, 1f)
        return RectF(nodes.minOf { it.x }, nodes.minOf { it.y }, nodes.maxOf { it.x + it.w }, nodes.maxOf { it.y + it.h })
    }

    val PALETTE = intArrayOf(0xFF5E35B1.toInt(), 0xFF1E88E5.toInt(), 0xFF00897B.toInt(), 0xFFF4511E.toInt(), 0xFFC0CA33.toInt(), 0xFF8E24AA.toInt(), 0xFF3949AB.toInt())

    /** Branch colour: the root is dark, every first-level branch keeps its own colour for its subtree. */
    fun colorOf(path: String): Int {
        val parts = path.split('.')
        if (parts.size < 2) return 0xFF263238.toInt()
        return PALETTE[(parts[1].toIntOrNull() ?: 0) % PALETTE.size]
    }

    private fun textPaint(d: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = if (d == 0) 20f else if (d == 1) 16f else 14f
        typeface = if (d <= 1) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun measureWith(): (String, Int) -> Float = { s, d -> textPaint(d).measureText(s) }

    /** Draw on an Android canvas (used by both exports). */
    fun draw(c: Canvas, nodes: List<PlacedNode>, rtl: Boolean, dx: Float, dy: Float) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        nodes.forEach { n ->
            if (n.parent < 0) return@forEach
            val p = nodes[n.parent]
            line.color = colorOf(n.path)
            val (sx, ex) = if (rtl) p.x to (n.x + n.w) else (p.x + p.w) to n.x
            val sy = p.y + p.h / 2; val ey = n.y + n.h / 2
            val path = Path().apply { moveTo(sx + dx, sy + dy); cubicTo((sx + ex) / 2 + dx, sy + dy, (sx + ex) / 2 + dx, ey + dy, ex + dx, ey + dy) }
            c.drawPath(path, line)
        }
        nodes.forEach { n ->
            val col = colorOf(n.path)
            val r = RectF(n.x + dx, n.y + dy, n.x + n.w + dx, n.y + n.h + dy)
            fill.color = if (n.depth == 0) col else (col and 0x00FFFFFF) or 0x26000000
            c.drawRoundRect(r, 14f, 14f, fill)
            val tp = textPaint(n.depth).apply { color = if (n.depth == 0) Color.WHITE else 0xFF212121.toInt() }
            val t = label(n.node.title)
            val tw = tp.measureText(t)
            c.drawText(t, r.centerX() - tw / 2, r.centerY() + tp.textSize / 3, tp)
        }
    }

    fun toPng(root: MindNode, rtl: Boolean, out: File, scale: Float = 2f): File {
        val nodes = layout(root, measureWith(), rtl)
        val b = bounds(nodes); val m = 32f
        // cap the bitmap at ~16 MP (64 MB): an 8000×8000 ARGB bitmap (256 MB) throws OutOfMemoryError on tablets
        val bw = (b.width() + m * 2).coerceAtLeast(1f); val bh = (b.height() + m * 2).coerceAtLeast(1f)
        val s = minOf(scale, kotlin.math.sqrt(16_000_000f / (bw * bh)), 8000f / bw, 8000f / bh).coerceAtLeast(0.05f)
        val w = (bw * s).toInt().coerceIn(200, 8000); val h = (bh * s).toInt().coerceIn(200, 8000)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE); c.scale(s, s)
        draw(c, nodes, rtl, m - b.left, m - b.top)
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return out
    }

    /** Vector PDF (one page sized to the map, text stays selectable in most viewers). */
    fun toPdf(root: MindNode, rtl: Boolean, out: File): File {
        val nodes = layout(root, measureWith(), rtl)
        val b = bounds(nodes); val m = 32f
        val doc = PdfDocument()
        try {
            val info = PdfDocument.PageInfo.Builder((b.width() + m * 2).toInt().coerceAtLeast(200), (b.height() + m * 2).toInt().coerceAtLeast(200), 1).create()
            val page = doc.startPage(info)
            page.canvas.drawColor(Color.WHITE)
            draw(page.canvas, nodes, rtl, m - b.left, m - b.top)
            doc.finishPage(page)
            out.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
        return out
    }
}
