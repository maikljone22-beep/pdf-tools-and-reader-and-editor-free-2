package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.office.PresentationModel

/** Row 103 (PowerPoint add-in "slide range", "convert hidden slides"): which slides are printed. Pure. */
object SlideSelect {
    /** The slides of [range] ("2-5, 8"; blank = all), without hidden slides unless [includeHidden]; never empty when [m] has slides. */
    fun select(m: PresentationModel, range: String, includeHidden: Boolean): PresentationModel {
        val n = m.slides.size
        if (n == 0) return m
        val wanted = com.smartdocs.editor.core.pdf.PdfOps.parseRange(range, n).toSet()
        val keep = m.slides.filterIndexed { i, s -> i in wanted && (includeHidden || !s.hidden) }
        return PresentationModel(keep.ifEmpty { m.slides.filterIndexed { i, _ -> i in wanted }.ifEmpty { m.slides } })
    }
}
