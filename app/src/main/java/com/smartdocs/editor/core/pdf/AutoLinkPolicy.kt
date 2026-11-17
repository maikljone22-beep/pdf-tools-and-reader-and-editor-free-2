package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage

/** Page-local intent, saved inside the PDF and carried with copied/reordered pages.
 * Other readers may still recognise printed addresses; this is a SmartDocs reader preference,
 * not content redaction. Explicit links added afterwards continue to work.
 */
object AutoLinkPolicy {
    private val DISABLED = COSName.getPDFName("SmartDocsDisableAutoLinks")

    fun disable(page: PDPage) { page.cosObject.setBoolean(DISABLED, true) }
    fun disabledPages(doc: PDDocument): Set<Int> = doc.pages.withIndex()
        .filter { it.value.cosObject.getBoolean(DISABLED, false) }.map { it.index }.toSet()
}
