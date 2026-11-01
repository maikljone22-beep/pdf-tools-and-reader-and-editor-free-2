package com.smartdocs.editor.core.tools5

import java.io.File

/** Scanner UX audit S3: page edges of a picture that did not come from the scanner camera (gallery, shared photo). */
object ScanEdges {
    /** The page corners (x0..x3, y0..y3, 0..1 of the upright picture) when a clear page is found, else null (use as is). */
    fun detect(f: File): FloatArray? {
        val b = ScanImage.load(f, 700) ?: return null
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h); b.recycle()
        val q = PageQuad.detect(px, w, h) ?: return null
        // a page that already fills the picture (a screenshot, a cropped photo), or a small box inside it, is left alone
        return if (q.area() in 0.2f..0.95f) q.x + q.y else null
    }
}
