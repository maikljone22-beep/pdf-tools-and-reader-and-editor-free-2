package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.edit5.E5Io
import java.io.File

/** Foxit web-page conversion options that are CSS or a post-step (the WebView printer has no switches for them). */
object WebCss {
    /**
     * Row 93 "one single long page": the paper height (mils) that holds the whole page printed at [widthMils] wide —
     * the content's height ([contentCssH]) for its laid-out width ([viewCssW]), scaled to the printable width, plus
     * the margins and 3 % slack; at most 200 in (the PDF page limit).
     */
    fun longPageMils(widthMils: Int, marginMils: Int, contentCssH: Int, viewCssW: Float): Int {
        if (contentCssH <= 0 || viewCssW <= 0f) return widthMils
        val printable = (widthMils - 2 * marginMils).coerceAtLeast(500)
        val h = printable * (contentCssH / viewCssW) * 1.03f + 2 * marginMils
        return h.toInt().coerceIn(widthMils / 2, 200_000)
    }

    /** CSS injected before printing. Pure. */
    fun build(images: Boolean = true, backgrounds: Boolean = false, underlineLinks: Boolean = false): String = buildString {
        if (!images) append("img,picture,svg image{display:none!important}")
        if (backgrounds) append("*{-webkit-print-color-adjust:exact!important;print-color-adjust:exact!important}")
        if (underlineLinks) append("a[href]{text-decoration:underline!important}")
    }

    /** "Disable hyperlinks": removes the link annotations of [pdf] (in place). Returns how many. */
    fun stripLinks(pdf: File): Int {
        var n = 0
        E5Io.edit(pdf, pdf, null) { d ->
            d.pages.forEach { p ->
                val a = runCatching { p.annotations }.getOrNull() ?: return@forEach
                val keep = a.filterNot { it.subtype == "Link" }
                if (keep.size != a.size) { n += a.size - keep.size; p.annotations = keep }
            }
        }
        return n
    }
}
