package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.formsx.FieldSpec
import com.smartdocs.editor.core.formsx.FxEditor
import com.smartdocs.editor.core.formsx.FxRect
import com.smartdocs.editor.core.formsx.FxSigLock
import com.smartdocs.editor.core.formsx.FxSigLocks
import com.smartdocs.editor.core.formsx.FxType
import com.smartdocs.editor.core.formsx.FxWidget
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 8.84: document changes a form's JavaScript asks for while filling, written when the form is saved:
 * `this.addScript` (a document-level script), `field.setLock` (the SigFieldLock of a signature field, which the
 * signing code turns into FieldMDP) and `this.addField` (a new field through the form designer's writer).
 */
data class ScriptDocChanges(
    val scripts: Map<String, String> = emptyMap(),
    val locks: Map<String, FxSigLock> = emptyMap(),
    val fields: List<NewField> = emptyList(),
) {
    data class NewField(val name: String, val type: FxType, val page: Int, val rect: FxRect)

    val isEmpty: Boolean get() = scripts.isEmpty() && locks.isEmpty() && fields.isEmpty()

    /** Apply to an open document; returns how many changes were made. */
    fun apply(doc: PDDocument): Int {
        var n = 0
        scripts.forEach { (name, code) -> DocScripts.putNamed(doc, name.ifBlank { "script" }, code); n++ }
        val acro = doc.documentCatalog.getAcroForm(null)
        locks.forEach { (name, lock) ->
            val f = acro?.getField(name) as? PDSignatureField ?: return@forEach
            FxSigLocks.write(f.cosObject, lock); n++
        }
        fields.forEach { nf ->
            if (nf.page !in 0 until doc.numberOfPages) return@forEach
            if (doc.documentCatalog.getAcroForm(null)?.getField(nf.name) != null) return@forEach      // name taken
            runCatching { FxEditor.create(doc, FieldSpec(nf.name, nf.type, listOf(FxWidget(nf.page, nf.rect)))) }.onSuccess { n++ }
        }
        return n
    }

    /** Apply to [file] in place (loaded with [password], saved back only when something changed). */
    suspend fun applyTo(file: File, password: String?) = withContext(Dispatchers.IO) {
        if (isEmpty) return@withContext 0
        val tmp = File(file.parentFile, ".${file.name}.js.pdf")
        val n = PdfOps.load(file, password).use { d -> apply(d).also { if (it > 0) d.save(tmp) } }
        if (n > 0 && !tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        n
    }

    companion object {
        /** Acrobat field type names of addField. */
        fun typeOf(t: String): FxType? = when (t.trim().lowercase()) {
            "text" -> FxType.TEXT; "checkbox" -> FxType.CHECKBOX; "radiobutton" -> FxType.RADIO; "combobox" -> FxType.COMBO
            "listbox" -> FxType.LIST; "signature" -> FxType.SIGNATURE; "button" -> FxType.BUTTON
            else -> null
        }

        /** The lock of `field.setLock({ action, fields })`. */
        fun lockOf(action: String, fields: List<String>): FxSigLock = when (action.trim().lowercase()) {
            "include" -> FxSigLock(FxSigLock.Mode.INCLUDE, fields)
            "exclude" -> FxSigLock(FxSigLock.Mode.EXCLUDE, fields)
            else -> FxSigLock(FxSigLock.Mode.ALL)
        }

        /** addField's oCoords "x1,y1,x2,y2" (page user space) as a rect; null when not four numbers. */
        fun rectOf(coords: String): FxRect? {
            val v = coords.split(',').mapNotNull { it.trim().toFloatOrNull() }
            return if (v.size >= 4 && v[0] != v[2] && v[1] != v[3]) FxRect(v[0], v[1], v[2], v[3]) else null
        }
    }
}
