package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Field visibility changed while filling (Hide actions, `this.removeField` in scripts): written into the widgets'
 * annotation flags on save. A name also matches its descendants ("addr" hides "addr.street", "addr.city").
 */
object FieldVisibility {

    /** Set (true) or clear (false) the Hidden flag of every widget of the fields in [hidden]. Returns widgets changed. */
    fun apply(doc: PDDocument, hidden: Map<String, Boolean>): Int {
        val acro = doc.documentCatalog.acroForm ?: return 0
        var n = 0
        for ((name, hide) in hidden) {
            val f: PDField = acro.getField(name) ?: continue
            val terminals = ArrayList<PDTerminalField>()
            fun collect(x: PDField) { when (x) { is PDTerminalField -> terminals += x; is PDNonTerminalField -> x.children.forEach { collect(it) } } }
            collect(f)
            for (t in terminals) for (w in t.widgets) {
                if (w.isHidden != hide || (!hide && w.isNoView)) {
                    w.isHidden = hide
                    if (!hide) w.isNoView = false
                    n++
                }
            }
        }
        return n
    }

    /**
     * After fill-time values were written into [file]: apply [hidden] and re-encode barcode fields from the new
     * values (core/formsx FxBarcodes). The file is rewritten in place only when something changed.
     */
    suspend fun applyTo(file: File, password: String?, hidden: Map<String, Boolean>) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, ".${file.name}.vis.pdf")
        val changed = PdfOps.load(file, password).use { d ->
            val n = apply(d, hidden) + runCatching { com.smartdocs.editor.core.formsx.FxBarcodes.refreshAll(d) }.getOrDefault(0)
            if (n > 0) { d.save(tmp); true } else false
        }
        if (changed && !tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }
}
