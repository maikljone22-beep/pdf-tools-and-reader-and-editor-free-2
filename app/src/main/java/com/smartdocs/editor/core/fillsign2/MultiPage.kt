package com.smartdocs.editor.core.fillsign2

import com.smartdocs.editor.core.share5.ShareChoice

/**
 * "Place on multiple pages" for a signature / initials stamp and the stamp clipboard (nitro-fillsign2). Pure page
 * set and position math, unit-tested. Boxes are PDF space (left, bottom, right, top; y up).
 */
object MultiPage {
    enum class PageSet { ALL, ODD, EVEN, CUSTOM }

    data class Box(val left: Float, val bottom: Float, val right: Float, val top: Float) {
        val width: Float get() = right - left
        val height: Float get() = top - bottom
    }

    /**
     * Zero-based pages of [set] in a document of [count] pages ("odd" = pages 1, 3, 5... as numbered for the user).
     * [range] is the custom text ("1-3,5"; Arabic digits and open ends allowed: the share-dialog parser is reused).
     * [except] (the page the stamp is already on) is left out.
     */
    fun pages(set: PageSet, range: String, count: Int, except: Int? = null): List<Int> {
        val all = when (set) {
            PageSet.ALL -> (0 until count).toList()
            PageSet.ODD -> (0 until count step 2).toList()
            PageSet.EVEN -> (1 until count step 2).toList()
            PageSet.CUSTOM -> ShareChoice.parseRange(range, count)
        }
        return all.filter { it != except }
    }

    /**
     * The same box on page [to]: its centre keeps the same relative position inside the crop box as on page [from],
     * its size in points is kept (shrunk only when it does not fit), and it stays inside the crop box. Boxes and crop
     * boxes are user space (tab audit D10: the displayed size of a rotated page and a crop origin other than (0, 0)
     * moved the stamp).
     */
    fun relocate(b: Box, from: FsGeom.PageBox, to: FsGeom.PageBox): Box {
        val s = minOf(1f, to.w / b.width.coerceAtLeast(0.01f), to.h / b.height.coerceAtLeast(0.01f))
        val w = b.width * s; val h = b.height * s
        val cx = (b.left + b.width / 2f - from.x) / from.w.coerceAtLeast(1f) * to.w + to.x
        val cy = (b.bottom + b.height / 2f - from.y) / from.h.coerceAtLeast(1f) * to.h + to.y
        return place(cx, cy, w, h, to)
    }

    /** Paste at a point (long-press "Paste here"): the box centred on ([x], [y]), kept in [page] and shrunk only when bigger. */
    fun boxAt(b: Box, x: Float, y: Float, page: FsGeom.PageBox): Box {
        val s = minOf(1f, page.w / b.width.coerceAtLeast(0.01f), page.h / b.height.coerceAtLeast(0.01f))
        return place(x, y, b.width * s, b.height * s, page)
    }

    /** Paste: the same spot when the box fits in the crop box of [page], otherwise centred on it. */
    fun pasteBox(b: Box, page: FsGeom.PageBox): Box {
        if (b.left >= page.x && b.bottom >= page.y && b.right <= page.x + page.w && b.top <= page.y + page.h) return b
        val s = minOf(1f, page.w / b.width.coerceAtLeast(0.01f), page.h / b.height.coerceAtLeast(0.01f))
        val w = b.width * s; val h = b.height * s
        val l = page.x + (page.w - w) / 2f; val bt = page.y + (page.h - h) / 2f
        return Box(l, bt, l + w, bt + h)
    }

    private fun place(cx: Float, cy: Float, w: Float, h: Float, p: FsGeom.PageBox): Box {
        val l = (cx - w / 2f).coerceIn(p.x, p.x + (p.w - w).coerceAtLeast(0f))
        val bt = (cy - h / 2f).coerceIn(p.y, p.y + (p.h - h).coerceAtLeast(0f))
        return Box(l, bt, l + w, bt + h)
    }

    // unrotated pages at (0, 0): the sizes are the crop box size
    fun relocate(b: Box, fromW: Float, fromH: Float, toW: Float, toH: Float): Box = relocate(b, FsGeom.PageBox.plain(fromW, fromH), FsGeom.PageBox.plain(toW, toH))
    fun boxAt(b: Box, x: Float, y: Float, pageW: Float, pageH: Float): Box = boxAt(b, x, y, FsGeom.PageBox.plain(pageW, pageH))
    fun pasteBox(b: Box, pageW: Float, pageH: Float): Box = pasteBox(b, FsGeom.PageBox.plain(pageW, pageH))
}
