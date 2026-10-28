package com.smartdocs.editor.core.forms5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField

/**
 * PdfBox 2.0 writes only flagged objects in an incremental update: flags what a fill-time save changed on a
 * signed form (audit V-01) — the AcroForm, the touched fields up to their root, their widgets with the /AP and
 * /MK dictionaries — and returns the pages that hold those widgets (their /Annots are flagged by SignedSave).
 */
internal object SignedFormMarks {

    fun mark(doc: PDDocument, acro: PDAcroForm, names: Set<String>): List<PDPage> {
        acro.cosObject.isNeedToBeUpdated = true
        (acro.cosObject.getDictionaryObject(COSName.FIELDS) as? COSArray)?.isNeedToBeUpdated = true
        (acro.cosObject.getDictionaryObject(COSName.DR) as? COSDictionary)?.let { flagTree(it) }
        val widgets = HashSet<COSDictionary>()
        for (name in names) {
            val f = acro.getField(name) ?: continue
            terminals(f).forEach { t ->
                var d: COSDictionary? = t.cosObject
                while (d != null) { d.isNeedToBeUpdated = true; d = d.getDictionaryObject(COSName.PARENT) as? COSDictionary }
                (t.cosObject.getDictionaryObject(COSName.KIDS) as? COSArray)?.isNeedToBeUpdated = true
                for (w in t.widgets) {
                    val wd = w.cosObject
                    wd.isNeedToBeUpdated = true
                    (wd.getDictionaryObject(COSName.AP) as? COSDictionary)?.let { ap ->
                        ap.isNeedToBeUpdated = true
                        // a check box / radio /N is a dictionary of states
                        (ap.getDictionaryObject(COSName.N) as? COSDictionary)?.isNeedToBeUpdated = true
                        (ap.getDictionaryObject(COSName.D) as? COSDictionary)?.isNeedToBeUpdated = true
                    }
                    (wd.getDictionaryObject(COSName.MK) as? COSDictionary)?.isNeedToBeUpdated = true
                    widgets += wd
                }
            }
        }
        return doc.pages.filter { p -> p.annotations.any { it.cosObject in widgets } }
    }

    private fun terminals(f: PDField): List<PDTerminalField> = when (f) {
        is PDTerminalField -> listOf(f)
        is PDNonTerminalField -> f.children.flatMap { terminals(it) }
        else -> emptyList()
    }

    /** /DR gains the Arabic font when an Arabic value is filled: flag it and its /Font sub-dictionary. */
    private fun flagTree(dr: COSDictionary) {
        dr.isNeedToBeUpdated = true
        (dr.getDictionaryObject(COSName.FONT) as? COSDictionary)?.isNeedToBeUpdated = true
    }
}
