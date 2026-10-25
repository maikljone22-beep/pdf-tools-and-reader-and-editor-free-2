package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Extra field kinds of the form builder, made from the basic AcroForm fields FormBuilder creates. */
sealed class ExtraKind {
    /** Text field with Acrobat date format + keystroke actions (date picker in the reader). */
    data class Date(val pattern: String = FieldDates.DEFAULT_PATTERN) : ExtraKind()
    /** Push button that takes a picture (Acrobat image field: buttonImportIcon). */
    data object Image : ExtraKind()
}

object FormBuilderExtras {

    private fun jsAction(code: String) = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.getPDFName("Action"))
        setItem(COSName.S, COSName.getPDFName("JavaScript"))
        setItem(COSName.JS, COSString(code))
    }

    /**
     * Turn the fields named in [kinds] (already created by FormBuilder.addFields in [file]) into date or image
     * fields. The file is rewritten in place. Returns how many fields were converted.
     */
    suspend fun apply(file: File, kinds: Map<String, ExtraKind>, password: String? = null): Int = withContext(Dispatchers.IO) {
        if (kinds.isEmpty()) return@withContext 0
        val tmp = File(file.parentFile, ".${file.name}.x.pdf")
        var n = 0
        PdfOps.load(file, password).use { doc ->
            val acro = doc.documentCatalog.acroForm ?: return@withContext 0
            kinds.forEach { (name, kind) ->
                val f = acro.getField(name) ?: return@forEach
                when (kind) {
                    is ExtraKind.Date -> if (f is PDTextField) {
                        val aa = (f.cosObject.getDictionaryObject(COSName.AA) as? COSDictionary) ?: COSDictionary().also { f.cosObject.setItem(COSName.AA, it) }
                        aa.setItem(COSName.F, jsAction(FieldDates.formatJs(kind.pattern)))
                        aa.setItem(COSName.K, jsAction(FieldDates.keystrokeJs(kind.pattern)))
                        if (f.alternateFieldName.isNullOrBlank()) f.alternateFieldName = kind.pattern.uppercase()
                        n++
                    }
                    ExtraKind.Image -> if (f is PDPushButton) {
                        for (w in f.widgets) {
                            w.cosObject.setItem(COSName.A, jsAction("event.target.buttonImportIcon();"))
                            val mk = (w.cosObject.getDictionaryObject(COSName.getPDFName("MK")) as? COSDictionary) ?: COSDictionary().also { w.cosObject.setItem(COSName.getPDFName("MK"), it) }
                            mk.removeItem(COSName.getPDFName("CA"))
                            mk.setInt(COSName.getPDFName("TP"), 1)
                        }
                        n++
                    }
                }
            }
            doc.save(tmp)
        }
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        n
    }
}
