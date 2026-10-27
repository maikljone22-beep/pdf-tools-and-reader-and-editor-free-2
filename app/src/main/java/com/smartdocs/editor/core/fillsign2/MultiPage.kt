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
     * The same box on a page of size ([toW], [toH]): its centre keeps the same relative position as on the
     * ([fromW], [fromH]) page, its size in points is kept (shrunk only when it does not fit), and it stays on the page.
     */
    fun relocate(b: Box, fromW: Float, fromH: Float, toW: Float, toH: Float): Box {
        val s = minOf(1f, toW / b.width.coerceAtLeast(0.01f), toH / b.height.coerceAtLeast(0.01f))
        val w = b.width * s; val h = b.height * s
        val cx = (b.left + b.width / 2f) / fromW.coerceAtLeast(1f) * toW
        val cy = (b.bottom + b.height / 2f) / fromH.coerceAtLeast(1f) * toH
        val l = (cx - w / 2f).coerceIn(0f, (toW - w).coerceAtLeast(0f))
        val bt = (cy - h / 2f).coerceIn(0f, (toH - h).coerceAtLeast(0f))
        return Box(l, bt, l + w, bt + h)
    }

    /** Paste: the same spot when the box fits on the ([pageW], [pageH]) page, otherwise centred on it. */
    fun pasteBox(b: Box, pageW: Float, pageH: Float): Box {
        if (b.left >= 0f && b.bottom >= 0f && b.right <= pageW && b.top <= pageH) return b
        val s = minOf(1f, pageW / b.width.coerceAtLeast(0.01f), pageH / b.height.coerceAtLeast(0.01f))
        val w = b.width * s; val h = b.height * s
        val l = (pageW - w) / 2f; val bt = (pageH - h) / 2f
        return Box(l, bt, l + w, bt + h)
    }
}
