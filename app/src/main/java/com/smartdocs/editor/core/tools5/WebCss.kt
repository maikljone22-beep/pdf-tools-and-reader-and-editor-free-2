package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.edit5.E5Io
import java.io.File

/** Foxit web-page conversion options that are CSS or a post-step (the WebView printer has no switches for them). */
object WebCss {
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
