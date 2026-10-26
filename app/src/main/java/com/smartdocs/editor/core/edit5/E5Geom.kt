package com.smartdocs.editor.core.edit5

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle

/**
 * Geometry of one page, detached from PdfBox so the UI can map touches. "Display" space = what the viewer shows:
 * origin at the bottom-left of the crop box after /Rotate, y up, points (same as `core.edit.PageFrame`).
 * "User" space = PDF user space of the page (where annotations and content live).
 */
data class PageInfo(
    val rotation: Int,
    val cropX: Float, val cropY: Float, val cropW: Float, val cropH: Float,
    val mediaX: Float, val mediaY: Float, val mediaW: Float, val mediaH: Float,
) {
    val displayW get() = if (rotation % 180 == 0) cropW else cropH
    val displayH get() = if (rotation % 180 == 0) cropH else cropW
    val landscape get() = displayW > displayH

    fun toUser(x: Float, y: Float): Pair<Float, Float> = when (rotation) {
        90 -> (cropW + cropX - y) to (cropY + x)
        180 -> (cropW + cropX - x) to (cropH + cropY - y)
        270 -> (cropX + y) to (cropH + cropY - x)
        else -> (x + cropX) to (y + cropY)
    }

    fun toDisplay(x: Float, y: Float): Pair<Float, Float> = when (rotation) {
        90 -> (y - cropY) to (cropW + cropX - x)
        180 -> (cropW + cropX - x) to (cropH + cropY - y)
        270 -> (cropH + cropY - y) to (x - cropX)
        else -> (x - cropX) to (y - cropY)
    }

    /** Display rect (any corner order) → user rect with PDF convention (left < right, top > bottom). */
    fun rectToUser(r: RectF): RectF {
        val a = toUser(r.left, r.top); val b = toUser(r.right, r.bottom)
        return RectF(minOf(a.first, b.first), maxOf(a.second, b.second), maxOf(a.first, b.first), minOf(a.second, b.second))
    }

    fun rectToDisplay(r: RectF): RectF {
        val a = toDisplay(r.left, r.top); val b = toDisplay(r.right, r.bottom)
        return RectF(minOf(a.first, b.first), maxOf(a.second, b.second), maxOf(a.first, b.first), minOf(a.second, b.second))
    }

    companion object {
        fun of(page: PDPage): PageInfo {
            val c = page.cropBox; val m = page.mediaBox
            return PageInfo(((page.rotation % 360) + 360) % 360, c.lowerLeftX, c.lowerLeftY, c.width, c.height, m.lowerLeftX, m.lowerLeftY, m.width, m.height)
        }
    }
}

object E5Geom {
    /** PDF-convention RectF (top > bottom) → PDRectangle. */
    fun rect(r: RectF): PDRectangle = PDRectangle(minOf(r.left, r.right), minOf(r.top, r.bottom), kotlin.math.abs(r.right - r.left), kotlin.math.abs(r.top - r.bottom))

    fun rectF(p: PDRectangle): RectF = RectF(p.lowerLeftX, p.upperRightY, p.upperRightX, p.lowerLeftY)

    /** Union of rects (PDF convention). */
    fun union(rs: List<RectF>): RectF? {
        if (rs.isEmpty()) return null
        return RectF(rs.minOf { minOf(it.left, it.right) }, rs.maxOf { maxOf(it.top, it.bottom) }, rs.maxOf { maxOf(it.left, it.right) }, rs.minOf { minOf(it.top, it.bottom) })
    }

    /** QuadPoints (x1 y1 x2 y2 x3 y3 x4 y4 per rect: upper-left, upper-right, lower-left, lower-right). */
    fun quads(rs: List<RectF>): FloatArray = rs.flatMap { r ->
        val l = minOf(r.left, r.right); val rr = maxOf(r.left, r.right); val t = maxOf(r.top, r.bottom); val b = minOf(r.top, r.bottom)
        listOf(l, t, rr, t, l, b, rr, b)
    }.toFloatArray()
}
