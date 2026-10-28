package com.smartdocs.editor.core.security

import com.smartdocs.editor.core.pdf.SignedGuard

/**
 * Tab audit D8: reader tools a certification (DocMDP) or a FieldMDP lock with /P forbids, decided when the document
 * opens instead of at save time (after the work was done). Pure, unit-tested; the reader guard asks [blocks].
 *
 *  - P = 1: no changes at all: annotations and form filling are refused.
 *  - P = 2: form filling and signing only: annotations (Fill & Sign marks, signature stamps, comments) are refused.
 *  - P = 3 or no lock: allowed (a plain signed file appends annotations incrementally).
 */
object CertifiedLock {

    fun blocks(info: SignedGuard.Info?, action: DocAction): Boolean {
        if (info == null || info.lockP == null) return false
        val change = when (action) {
            DocAction.ANNOTATE -> SignedGuard.Change.ANNOTATE
            DocAction.FILL_FORMS -> SignedGuard.Change.FORM_FILL
            else -> return false      // page content / security changes: decided by SignedGuard when they are written
        }
        return SignedGuard.decide(info, change, incremental = true) == SignedGuard.Decision.REFUSE
    }

    /** True when the reader should show the "certified: annotations are not allowed" banner. */
    fun showBanner(info: SignedGuard.Info?): Boolean = blocks(info, DocAction.ANNOTATE)
}
