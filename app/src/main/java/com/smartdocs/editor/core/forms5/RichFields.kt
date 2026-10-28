package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.annot.AnnotStyle
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField

/**
 * 8.28 rich-text fields at fill time: a text field with the RichText flag keeps its value also as XHTML (/RV)
 * with a default style (/DS). When such a field gets a new value, /RV is written again from the value and the
 * field's style (the one chosen while filling, else the one it had), so viewers that show /RV never show the
 * old text.
 */
object RichFields {
    private const val FF_RICHTEXT = 1 shl 25
    private val RV = COSName.getPDFName("RV")
    private val DS = COSName.getPDFName("DS")

    fun isRich(f: PDField): Boolean = f is PDTextField && (f.cosObject.getInt(COSName.FF, 0) and FF_RICHTEXT) != 0

    /** The style of a rich field from its /RV and /DS (null when it has neither). */
    fun styleOf(cos: COSDictionary): AnnotStyle.Rich? {
        val rv = cos.getString(RV); val ds = cos.getString(DS)
        if (rv == null && ds == null) return null
        // AnnotStyle reads the same XHTML / CSS conventions from an annotation's /RC and /DS
        val tmp = COSDictionary()
        rv?.let { tmp.setString(COSName.getPDFName("RC"), it) }
        ds?.let { tmp.setString(DS, it) }
        return AnnotStyle.readRich(tmp)
    }

    /** Write /RV and /DS of the rich fields among [values]; returns how many were written. */
    fun updateIn(acro: PDAcroForm, values: Map<String, String>, styles: Map<String, AnnotStyle.Rich>): Int {
        var n = 0
        values.forEach { (name, v) ->
            val f = acro.getField(name) ?: return@forEach
            if (!isRich(f)) return@forEach
            val style = styles[name] ?: styleOf(f.cosObject) ?: return@forEach
            f.cosObject.setString(RV, AnnotStyle.richBody(v, style, ArabicShaper.isArabic(v)))
            f.cosObject.setString(DS, AnnotStyle.defaultStyle(style))
            n++
        }
        return n
    }
}
