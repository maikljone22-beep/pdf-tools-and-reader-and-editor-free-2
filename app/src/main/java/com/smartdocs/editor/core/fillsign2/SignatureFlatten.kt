package com.smartdocs.editor.core.fillsign2

import com.smartdocs.editor.core.pdf.FillSignKeys
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.util.Matrix

/**
 * Flattening core shared by the Flatten tool (PdfOps.flattenAnnotations) and "Apply All Signatures"
 * (nitro-fillsign2): the normal appearance of every selected annotation is drawn into the page content (mapped
 * from its /BBox x /Matrix onto /Rect, as a viewer does) and the annotation is removed. Links, popups, hidden
 * annotations and annotations without an appearance are always kept.
 */
object SignatureFlatten {

    /** A placed QuickSign signature / initials stamp (written with /SDSig by AnnotationWriter). */
    fun isSignatureStamp(a: PDAnnotation): Boolean = a.subtype == "Stamp" && a.cosObject.getBoolean(FillSignKeys.SIG, false)

    /** Flattens the annotations of [doc] chosen by [select]; returns how many were drawn into the pages. */
    fun flattenIn(doc: PDDocument, select: (PDAnnotation) -> Boolean): Int {
        var n = 0
        doc.pages.forEach { page ->
            val annots = runCatching { page.annotations }.getOrNull() ?: return@forEach
            val keep = ArrayList<PDAnnotation>()
            val flat = ArrayList<PDAnnotation>()
            annots.forEach { a ->
                val sub = a.subtype
                val hidden = a.isHidden || a.isNoView
                if (sub == "Link" || sub == "Popup" || hidden || a.normalAppearanceStream == null || a.rectangle == null || !select(a)) keep += a else flat += a
            }
            if (flat.isEmpty()) return@forEach
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                flat.forEach { a ->
                    runCatching {
                        val ap = a.normalAppearanceStream
                        val rect = a.rectangle
                        val bbox = ap.bBox ?: return@runCatching
                        val m = ap.matrix
                        // transformed bbox corners
                        val pts = listOf(bbox.lowerLeftX to bbox.lowerLeftY, bbox.upperRightX to bbox.lowerLeftY, bbox.lowerLeftX to bbox.upperRightY, bbox.upperRightX to bbox.upperRightY)
                            .map { (x, y) -> transform(m, x, y) }
                        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
                        val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
                        val tw = maxX - minX; val th = maxY - minY
                        if (tw <= 0f || th <= 0f) return@runCatching
                        val sx = rect.width / tw; val sy = rect.height / th
                        cs.saveGraphicsState()
                        cs.transform(Matrix(sx, 0f, 0f, sy, rect.lowerLeftX - minX * sx, rect.lowerLeftY - minY * sy))
                        cs.drawForm(ap)
                        cs.restoreGraphicsState()
                        n++
                    }
                }
            }
            page.annotations = keep
        }
        return n
    }

    /** [m] applied to (x, y), computed here (PdfBox-Android's transformPoint returns an android.graphics.PointF). */
    internal fun transform(m: Matrix, x: Float, y: Float): Pair<Float, Float> =
        (m.scaleX * x + m.shearX * y + m.translateX) to (m.shearY * x + m.scaleY * y + m.translateY)

    /** "Apply All Signatures": bakes every placed signature / initials stamp of [doc] into its pages. */
    fun flattenSignatures(doc: PDDocument): Int = flattenIn(doc, ::isSignatureStamp)
}
