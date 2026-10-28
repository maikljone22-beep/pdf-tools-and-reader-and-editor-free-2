package com.smartdocs.editor.core.rpf

import com.smartdocs.editor.core.security.DocAction

/**
 * reader-prefs (§37 / PREF-5): what PDF/A view mode refuses. PDF/A allows form filling, so only changes to the
 * original's content and annotations are refused: annotating, modifying, rearranging pages and security (PDF/A
 * forbids encryption). Reading, copying, printing, sharing, "Save a copy" (an unprotected copy), filling form
 * fields and Fill & Sign stay allowed. Pure (unit-tested).
 */
object PdfaViewRule {
    private val BLOCKED = setOf(DocAction.ANNOTATE, DocAction.MODIFY, DocAction.ASSEMBLE, DocAction.CHANGE_SECURITY)

    /** True when PDF/A view mode ([pdfaView]) refuses [action]. */
    fun blocks(pdfaView: Boolean, action: DocAction): Boolean = pdfaView && action in BLOCKED

    /** Fill & Sign tools (FS_* and the signature) fill the form rather than annotate the document. */
    fun isFillSignTool(toolName: String): Boolean = toolName.startsWith("FS_") || toolName == "SIGNATURE"

    /** The action PDF/A view mode weighs for a reader tool needing [action]. */
    fun toolAction(toolName: String, action: DocAction): DocAction = if (isFillSignTool(toolName)) DocAction.FILL_FORMS else action

    /** An annotation change made on the Fill & Sign tab or with a Fill & Sign tool counts as form filling. */
    fun annotAction(fillSignTab: Boolean, toolName: String?): DocAction =
        if (fillSignTab || (toolName != null && isFillSignTool(toolName))) DocAction.FILL_FORMS else DocAction.ANNOTATE
}
