package com.smartdocs.editor.core.pdf

import android.graphics.RectF
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A field the user wants to add. [rect] is in PDF points (left, top, right, bottom; origin bottom-left). */
data class NewField(
    val type: FieldType,
    val name: String,
    val page: Int,
    val rect: RectF,
    val options: List<String> = emptyList(),   // combo/list/radio choices
    val required: Boolean = false,
    val multiline: Boolean = false,
    val defaultValue: String = "",
    val tooltip: String = "",
    val maxLength: Int = 0,
)

/** Creates AcroForm fields (text, checkbox, radio group, combo, list, signature, button) with PdfBox. */
object FormBuilder {

    private fun load(src: File, password: String?): PDDocument =
        if (password.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, password)

    private fun acroForm(doc: PDDocument): PDAcroForm {
        val existing = doc.documentCatalog.acroForm
        if (existing != null) return existing
        val form = PDAcroForm(doc)
        doc.documentCatalog.acroForm = form
        val res = PDResources()
        res.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
        form.defaultResources = res
        form.defaultAppearance = "/Helv 0 Tf 0 g"
        return form
    }

    private fun uniqueName(form: PDAcroForm, base: String): String {
        val clean = base.ifBlank { "field" }.replace('.', '_')
        if (form.getField(clean) == null) return clean
        var n = 2; while (form.getField("${clean}_$n") != null) n++
        return "${clean}_$n"
    }

    private fun widget(doc: PDDocument, pageIndex: Int, r: RectF, border: Boolean = true): PDAnnotationWidget {
        val page = doc.getPage(pageIndex)
        val w = PDAnnotationWidget()
        w.rectangle = PDRectangle(minOf(r.left, r.right), minOf(r.top, r.bottom), kotlin.math.abs(r.width()), kotlin.math.abs(r.height()))
        w.page = page
        w.isPrinted = true
        if (border) {
            w.borderStyle = PDBorderStyleDictionary().apply { width = 1f; style = PDBorderStyleDictionary.STYLE_SOLID }
            val mk = PDAppearanceCharacteristicsDictionary(com.tom_roush.pdfbox.cos.COSDictionary())
            mk.setBorderColour(com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor(floatArrayOf(0.45f, 0.45f, 0.45f), com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE))
            mk.setBackground(com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor(floatArrayOf(0.96f, 0.97f, 1f), com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE))
            w.appearanceCharacteristics = mk
        }
        page.annotations.add(w)
        return w
    }

    /** Add all [fields] to a copy of [src] saved as [out]. Returns the final field names. */
    suspend fun addFields(src: File, fields: List<NewField>, out: File, password: String? = null): List<String> = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val form = acroForm(doc)
            val names = ArrayList<String>()
            fields.forEach { f ->
                val name = uniqueName(form, f.name)
                names += name
                when (f.type) {
                    FieldType.TEXT -> {
                        val tf = PDTextField(form); tf.partialName = name
                        tf.defaultAppearance = "/Helv ${if (f.multiline) 10 else 0} Tf 0 g"
                        tf.isMultiline = f.multiline; tf.isRequired = f.required
                        if (f.maxLength > 0) tf.maxLen = f.maxLength
                        if (f.tooltip.isNotBlank()) tf.alternateFieldName = f.tooltip
                        tf.widgets = listOf(widget(doc, f.page, f.rect))
                        form.fields.add(tf)
                        if (f.defaultValue.isNotBlank()) runCatching { tf.value = f.defaultValue }
                    }
                    FieldType.CHECKBOX -> {
                        val cb = PDCheckBox(form); cb.partialName = name; cb.isRequired = f.required
                        if (f.tooltip.isNotBlank()) cb.alternateFieldName = f.tooltip
                        val w = widget(doc, f.page, f.rect)
                        cb.widgets = listOf(w)
                        form.fields.add(cb)
                        cb.cosObject.setItem(COSName.V, COSName.Off)
                        w.cosObject.setItem(COSName.AS, COSName.Off)
                        runCatching { if (f.defaultValue == "true") cb.check() else cb.unCheck() }
                    }
                    FieldType.RADIO -> {
                        val rb = PDRadioButton(form); rb.partialName = name; rb.isRequired = f.required
                        val opts = f.options.ifEmpty { listOf("1", "2") }
                        // stack one circle per option vertically inside the rect
                        val h = kotlin.math.abs(f.rect.height()) / opts.size
                        val ws = opts.mapIndexed { i, _ ->
                            val top = maxOf(f.rect.top, f.rect.bottom) - i * h
                            widget(doc, f.page, RectF(f.rect.left, top, f.rect.left + minOf(h, 18f), top - minOf(h, 18f)))
                        }
                        rb.widgets = ws
                        rb.setExportValues(opts)
                        form.fields.add(rb)
                        ws.forEach { w -> w.cosObject.setItem(COSName.AS, COSName.Off) }
                        rb.cosObject.setItem(COSName.V, COSName.Off)
                        if (f.defaultValue in opts) runCatching { rb.setValue(f.defaultValue) }
                    }
                    FieldType.COMBO, FieldType.LIST -> {
                        val ch: PDChoice = if (f.type == FieldType.COMBO) PDComboBox(form).also { it.isEdit = false } else PDListBox(form)
                        ch.partialName = name; ch.isRequired = f.required
                        ch.defaultAppearance = "/Helv 0 Tf 0 g"
                        ch.options = f.options.ifEmpty { listOf("خيار 1", "خيار 2") }
                        if (f.tooltip.isNotBlank()) ch.alternateFieldName = f.tooltip
                        ch.widgets = listOf(widget(doc, f.page, f.rect))
                        form.fields.add(ch)
                        if (f.defaultValue.isNotBlank()) runCatching { ch.setValue(f.defaultValue) }
                    }
                    FieldType.SIGNATURE -> {
                        val sf = PDSignatureField(form); sf.partialName = name
                        if (f.tooltip.isNotBlank()) sf.alternateFieldName = f.tooltip
                        sf.widgets = listOf(widget(doc, f.page, f.rect))
                        form.fields.add(sf)
                    }
                    FieldType.BUTTON, FieldType.UNKNOWN -> {
                        val pb = PDPushButton(form); pb.partialName = name
                        if (f.tooltip.isNotBlank()) pb.alternateFieldName = f.tooltip
                        val w = widget(doc, f.page, f.rect)
                        val mk = w.appearanceCharacteristics ?: PDAppearanceCharacteristicsDictionary(com.tom_roush.pdfbox.cos.COSDictionary())
                        mk.normalCaption = f.defaultValue.ifBlank { name }
                        w.appearanceCharacteristics = mk
                        pb.widgets = listOf(w)
                        form.fields.add(pb)
                    }
                }
            }
            form.needAppearances = true
            runCatching { form.refreshAppearances() }
            doc.save(out)
            names
        }
    }

    /** Remove a field (and its widgets) by fully-qualified name. */
    suspend fun removeField(src: File, name: String, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val form = doc.documentCatalog.acroForm ?: return@use
            val f = form.getField(name) ?: return@use
            (f as? PDTerminalField)?.widgets?.forEach { w ->
                val p = w.page ?: doc.pages.firstOrNull { pg -> runCatching { pg.annotations.any { it.cosObject == w.cosObject } }.getOrDefault(false) }
                p?.let { pg -> val l = pg.annotations; l.removeAll { it.cosObject == w.cosObject }; pg.annotations = l }
            }
            val fields = form.fields; fields.remove(f); form.fields = fields
            doc.save(out)
        }
    }
}
