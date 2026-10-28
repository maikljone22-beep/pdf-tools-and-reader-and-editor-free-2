package com.smartdocs.editor.ui.rpf

import com.smartdocs.editor.core.edit5.DestFit
import com.smartdocs.editor.core.edit5.DestView
import com.smartdocs.editor.core.edit5.Dests
import com.smartdocs.editor.core.pdf.PageLink
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.rpf.Frac
import com.smartdocs.editor.core.rpf.RpfPrefs
import com.smartdocs.editor.core.rpf.ViewMath
import com.smartdocs.editor.ui.nve.NveState
import com.smartdocs.editor.ui.nve.NveZoom
import com.smartdocs.editor.ui.nve.ZoomPreset
import com.smartdocs.editor.ui.reader.ReaderState
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * §37 "Keep zoom on Go-to-Destination": with the preference off, a link to a page of this document also applies its
 * destination's view (XYZ zoom, Fit, FitH / FitBH, FitV, FitR); on (default) the reader keeps its zoom.
 */
object RpfDest {
    /** Hook after the reader jumped for [link] (from [fromPage], the page the link was tapped on). */
    fun afterJump(s: ReaderState, link: PageLink, fromPage: Int) {
        if (RpfPrefs.keepZoomOnDestination) return
        val f = s.file ?: return
        val target = link.destPage ?: return
        val pw = s.password
        s.scope.launch {
            val view = withContext(Dispatchers.IO) { runCatching { PdfOps.loadRaw(f, pw).use { viewOf(it, fromPage, link, target) } }.getOrNull() } ?: return@launch
            apply(s, target, view)
        }
    }

    /** The view of the link annotation on [page] at [link]'s bounds that goes to [target] (null: none / no view). */
    internal fun viewOf(doc: PDDocument, page: Int, link: PageLink, target: Int): DestView? {
        val pages = listOf(page, page - 1, page + 1).filter { it in 0 until doc.numberOfPages }
        val cx = link.bounds.centerX(); val cy = link.bounds.centerY()
        for (p in pages) {
            val links = doc.getPage(p).annotations.filterIsInstance<PDAnnotationLink>()
            val hit = links.firstOrNull { a -> a.rectangle?.let { r -> cx in r.lowerLeftX - 2f..r.upperRightX + 2f && cy in r.lowerLeftY - 2f..r.upperRightY + 2f } == true }
                ?: continue
            val dest = runCatching { hit.destination }.getOrNull() ?: (hit.action as? PDActionGoTo)?.destination
            val pd = when (dest) {
                is PDPageDestination -> dest
                is PDNamedDestination -> runCatching { doc.documentCatalog.findNamedDestinationPage(dest) }.getOrNull()
                else -> null
            } ?: return null
            val idx = runCatching { pd.retrievePageNumber() }.getOrDefault(-1)
            if (idx >= 0 && idx != target) return null
            return Dests.view(pd.cosObject as? COSArray ?: return null)
        }
        return null
    }

    private suspend fun apply(s: ReaderState, page: Int, v: DestView) {
        delay(250)        // the jump scrolls first
        when (v.fit) {
            DestFit.XYZ -> if (v.zoom > 0f) NveZoom.setPercent(s, v.zoom * 100f)
            DestFit.FIT, DestFit.FITB -> NveZoom.preset(s, ZoomPreset.FIT_PAGE)
            DestFit.FITH, DestFit.FITBH -> NveZoom.preset(s, ZoomPreset.FIT_WIDTH)
            DestFit.FITV -> NveZoom.preset(s, ZoomPreset.FIT_HEIGHT)
            DestFit.FITR -> v.rect?.takeIf { it.size == 4 }?.let { r -> NveZoom.zoomToPageRect(s, page, r[0], r[1], r[2], r[3]) }
        }
    }
}

/** §36 print range "current view": what is on screen (the most visible page, cut to the visible part) as a one-page PDF. */
object RpfPrintView {
    /** The most visible page and its visible part (fractions of the page as displayed), or null. */
    fun visible(s: ReaderState): Pair<Int, Frac>? {
        if (s.vx.crop.enabled) return null
        val n = NveState.of(s)
        val vw = n.viewport.width; val vh = n.viewport.height
        return n.visiblePages().mapNotNull { vp -> ViewMath.visibleFrac(vp.rect.left, vp.rect.top, vp.rect.right, vp.rect.bottom, vw, vh)?.let { vp.page to it } }
            .maxByOrNull { (p, f) -> f.width * f.height * (n.visiblePages().firstOrNull { it.page == p }?.rect?.let { it.width * it.height } ?: 0f) }
    }

    /** Writes the visible part of [page] of [src] to [out] (the page with its crop box set to that part). */
    suspend fun write(src: File, password: String?, page: Int, shown: Frac, viewRotation: Int, out: File) = withContext(Dispatchers.IO) {
        PdfOps.loadRaw(src, password).use { doc ->
            val p = doc.getPage(page)
            val crop = p.cropBox
            val f = ViewMath.unrotate(shown, p.rotation + viewRotation)
            val u = ViewMath.toUserSpace(f, crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height)
            PDDocument().use { d ->
                val imp = d.importPage(p)
                imp.cropBox = PDRectangle(u[0], u[1], (u[2] - u[0]).coerceAtLeast(1f), (u[3] - u[1]).coerceAtLeast(1f))
                d.save(out)
            }
        }
    }
}
