package com.smartdocs.editor.core.tools5

/**
 * The scanner's save decisions, apart from the screen so they are tested (scanner critique: the save flow had no test;
 * a slip in one condition — append vs pages per file, the cancel path — lost or doubled documents).
 */
object ScanSavePlan {
    enum class Format { PDF, JPG, WORD }

    /** Kinds whose result is always a PDF (contact / receipt parsing, form fields). */
    fun needsPdf(k: ScanKind) = k == ScanKind.BUSINESS_CARD || k == ScanKind.RECEIPT || k == ScanKind.FORM

    /** The format really written: Word wins over pictures; kinds that need a PDF always get one. */
    fun format(asImages: Boolean, asWord: Boolean, k: ScanKind): Format = when {
        needsPdf(k) -> Format.PDF
        asWord -> Format.WORD
        asImages -> Format.JPG
        else -> Format.PDF
    }

    /** OCR is run for Word, when asked, and for kinds that read their text; pictures never need it. */
    fun needsText(f: Format, autoOcr: Boolean, k: ScanKind) = f != Format.JPG && (f == Format.WORD || autoOcr || needsPdf(k))

    /**
     * Photos per document for "pages per file" ([perFile] output pages; 0 = one document per group). An ID card is two
     * photos per page, a book photo two pages. Adding to an existing PDF (one group) makes one document.
     */
    fun chunk(perFile: Int, k: ScanKind, appending: Boolean, groups: Int): Int = when {
        perFile <= 0 || (appending && groups == 1) -> 0
        k == ScanKind.ID_CARD -> perFile * 2
        k == ScanKind.BOOK -> maxOf(1, perFile / 2)
        else -> perFile
    }

    /** The pages left on screen after a cancel: the documents already written leave, so a retry does not repeat them. */
    fun <T> remainingAfterCancel(groups: List<List<T>>, done: List<List<T>>): List<List<T>> {
        val saved = done.flatten().toSet()
        if (saved.isEmpty()) return groups
        return groups.map { g -> g.filter { it !in saved } }.filter { it.isNotEmpty() }
    }
}
