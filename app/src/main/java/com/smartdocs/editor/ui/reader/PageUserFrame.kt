package com.smartdocs.editor.ui.reader

import com.smartdocs.editor.core.pdf.PdfOps
import java.io.File

/**
 * How a page's user space sits in the rendered page (audit AN-06): its /Rotate (0/90/180/270) and the lower-left
 * corner of its crop box. PDFium reports page sizes with /Rotate applied but text boxes in user space.
 */
data class PageUserFrame(val rotate: Int, val cropX: Float, val cropY: Float) {
    companion object {
        /** Normalised /Rotate: multiples of 90 in 0..270 (negative and > 360 values occur in the wild). */
        fun normRotate(r: Int): Int = (((r / 90) * 90) % 360 + 360) % 360

        /** Frames of the pages that are not "0° at (0, 0)", read once with PdfBox (only the page tree is touched). */
        fun read(file: File, password: String?): Map<Int, PageUserFrame> = PdfOps.load(file, password).use { d ->
            val out = HashMap<Int, PageUserFrame>()
            for ((i, p) in d.pages.withIndex()) {
                val rot = normRotate(p.rotation)
                val c = p.cropBox
                val fr = PageUserFrame(rot, c.lowerLeftX, c.lowerLeftY)
                if (rot != 0 || fr.cropX != 0f || fr.cropY != 0f) out[i] = fr
            }
            out
        }
    }
}
