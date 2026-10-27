package com.smartdocs.editor.ui.reader

/**
 * Why a document could not be opened (audit RD-01). Kept apart from the transient [ReaderState.error] that the
 * snackbar clears, so the reader shows a lasting error screen instead of falling back to an endless spinner.
 */
enum class OpenError {
    /** The file (or the content URI behind it) no longer exists. */
    MISSING,
    /** The app lost the permission to read the source. */
    PERMISSION,
    /** Not a PDF, truncated or damaged. */
    DAMAGED,
    OTHER;

    companion object {
        /** Classify [e]; [fileExists] is whether the local working copy exists (null: not known yet). Pure, unit tested. */
        fun of(e: Throwable, fileExists: Boolean?): OpenError {
            val chain = generateSequence(e) { it.cause }.take(5).toList()
            if (chain.any { it is SecurityException }) return PERMISSION
            if (fileExists == false || chain.any { it is java.io.FileNotFoundException }) return MISSING
            val msg = chain.joinToString(" ") { (it.message ?: "") + " " + it.javaClass.simpleName }.lowercase()
            return when {
                listOf("format", "corrupt", "damaged", "not a pdf", "header", "xref", "eof", "trailer", "pdfium", "invalid").any { it in msg } -> DAMAGED
                e is java.io.IOException -> DAMAGED
                else -> OTHER
            }
        }
    }
}
