package com.smartdocs.editor.core.pdf

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class FieldType { TEXT, CHECKBOX, RADIO, COMBO, LIST, SIGNATURE, BUTTON, UNKNOWN }

/**
 * One AcroForm field. [value] is a string in every case: "true"/"false" for check boxes, the selected
 * export value for radio groups, the selected option for choices, free text otherwise.
 */
data class FormField(
    val name: String,              // fully qualified name (used to find the field again)
    val label: String,             // partial name / alternate (TU) name for display
    val type: FieldType,
    val page: Int,                 // first widget's page (0-based), -1 when unknown
    val value: String,
    val options: List<String> = emptyList(),
    val readOnly: Boolean = false,
    val multiline: Boolean = false,
    val rect: RectF? = null,       // first widget rect in PDF points (left, top, right, bottom; origin bottom-left)
    val signed: Boolean = false,
)

/** Rect of a widget on a page, for the reader's field highlighting. */
data class FieldRect(val name: String, val type: FieldType, val rect: RectF)

/** AcroForm reading / filling built on PdfBox. Every write produces [out]; [src] is never modified. */
object FormOps {

    // PdfOps.load re-protects encrypted documents; PdfBox 2.0 otherwise refuses to save them
    private fun load(src: File, password: String?): PDDocument = PdfOps.load(src, password)

    private fun typeOf(f: PDField): FieldType = when (f) {
        is PDTextField -> FieldType.TEXT
        is PDCheckBox -> FieldType.CHECKBOX
        is PDRadioButton -> FieldType.RADIO
        is PDComboBox -> FieldType.COMBO
        is PDListBox -> FieldType.LIST
        is PDSignatureField -> FieldType.SIGNATURE
        is PDPushButton -> FieldType.BUTTON
        else -> FieldType.UNKNOWN
    }

    private fun pageIndex(doc: PDDocument, w: PDAnnotationWidget): Int {
        w.page?.let { p -> val i = doc.pages.indexOf(p); if (i >= 0) return i }
        // widget without /P: look for it in the page annotation arrays
        doc.pages.forEachIndexed { i, page ->
            if (runCatching { page.annotations.any { it.cosObject == w.cosObject } }.getOrDefault(false)) return i
        }
        return -1
    }

    private fun rectOf(w: PDAnnotationWidget): RectF? = runCatching { w.rectangle }.getOrNull()?.let { RectF(it.lowerLeftX, it.upperRightY, it.upperRightX, it.lowerLeftY) }

    private fun valueOf(f: PDField): String = runCatching {
        when (f) {
            is PDCheckBox -> f.isChecked.toString()
            is PDRadioButton -> f.value ?: ""
            is PDComboBox -> f.value.firstOrNull() ?: ""
            is PDListBox -> f.value.joinToString("\n")
            is PDTextField -> f.value ?: ""
            is PDSignatureField -> if (f.signature != null) "signed" else ""
            else -> f.valueAsString ?: ""
        }
    }.getOrDefault("")

    private fun optionsOf(f: PDField): List<String> = runCatching {
        when (f) {
            is PDRadioButton -> f.onValues.toList()
            is PDComboBox -> f.options
            is PDListBox -> f.options
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    /** All terminal fields of the document in tree order. */
    suspend fun listFields(src: File, password: String? = null): List<FormField> = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val acro = doc.documentCatalog.acroForm ?: return@withContext emptyList()
            val out = ArrayList<FormField>()
            for (f in acro.fieldTree) {
                if (f !is PDTerminalField) continue
                val widget = runCatching { f.widgets.firstOrNull() }.getOrNull()
                out += FormField(
                    name = f.fullyQualifiedName ?: f.partialName ?: "",
                    label = (f.alternateFieldName ?: f.partialName ?: f.fullyQualifiedName ?: "").ifBlank { f.fullyQualifiedName ?: "" },
                    type = typeOf(f),
                    page = widget?.let { pageIndex(doc, it) } ?: -1,
                    value = valueOf(f),
                    options = optionsOf(f),
                    readOnly = f.isReadOnly,
                    multiline = (f as? PDTextField)?.isMultiline ?: false,
                    rect = widget?.let { rectOf(it) },
                    signed = (f as? PDSignatureField)?.signature != null,
                )
            }
            out
        }
    }

    /** Widget rectangles on [page] (PDF points) for highlighting in the reader. */
    suspend fun fieldsOnPage(file: File, page: Int, password: String? = null): List<FieldRect> = withContext(Dispatchers.IO) {
        runCatching {
            load(file, password).use { doc ->
                val acro = doc.documentCatalog.acroForm ?: return@withContext emptyList()
                val out = ArrayList<FieldRect>()
                for (f in acro.fieldTree) {
                    if (f !is PDTerminalField) continue
                    val type = typeOf(f)
                    f.widgets.forEach { w -> if (pageIndex(doc, w) == page) rectOf(w)?.let { out += FieldRect(f.fullyQualifiedName ?: "", type, it) } }
                }
                out
            }
        }.getOrDefault(emptyList())
    }

    private fun applyValue(acro: PDAcroForm, f: PDField, value: String) {
        when (f) {
            is PDCheckBox -> if (value.equals("true", true) || value == f.onValue) f.check() else f.unCheck()
            is PDRadioButton -> if (value.isBlank()) runCatching { f.setValue("Off") } else f.setValue(value)
            is PDListBox -> if (f.isMultiSelect) f.setValue(value.split('\n').filter { it.isNotBlank() }) else f.setValue(value)
            is PDComboBox -> f.setValue(value)
            is PDTextField -> f.setValue(value)
            else -> f.setValue(value)
        }
    }

    /** Name of the embedded Arabic font in the AcroForm default resources (added on demand). */
    private val ARABIC_DR_NAME: com.tom_roush.pdfbox.cos.COSName = com.tom_roush.pdfbox.cos.COSName.getPDFName("SDNaskh")

    private fun ensureArabicFont(context: android.content.Context, doc: PDDocument, acro: PDAcroForm): Boolean = runCatching {
        val dr = acro.defaultResources ?: com.tom_roush.pdfbox.pdmodel.PDResources().also { acro.defaultResources = it }
        if (dr.getFont(ARABIC_DR_NAME) == null) {
            val font = context.assets.open("fonts/NotoNaskhArabic-Regular.ttf").use {
                com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(doc, it, false)
            }
            dr.put(ARABIC_DR_NAME, font)
        }
        true
    }.getOrDefault(false)

    /**
     * Generate the appearance of a text field from [shown] (formatted and/or Arabic-shaped text) while keeping
     * the logical [raw] value in /V, as Acrobat does for formatted fields.
     */
    private fun applyTextAppearance(context: android.content.Context?, doc: PDDocument, acro: PDAcroForm, f: PDTextField, raw: String, shown: String) {
        val arabic = ArabicShaper.isArabic(shown)
        if (arabic && context != null && ensureArabicFont(context, doc, acro)) {
            val size = Regex("""([\d.]+)\s+Tf""").find(f.defaultAppearance ?: "")?.groupValues?.get(1) ?: "0"
            f.defaultAppearance = "/${ARABIC_DR_NAME.name} $size Tf 0 g"
            if (f.q == PDTextField.QUADDING_LEFT) f.q = PDTextField.QUADDING_RIGHT
        }
        val visual = if (arabic) shown.split('\n').joinToString("\n") { ArabicShaper.shape(it) } else shown
        f.setValue(visual)
        f.cosObject.setString(com.tom_roush.pdfbox.cos.COSName.V, raw)
    }

    /**
     * Write [values] (fully-qualified name -> value) into a copy saved at [out].
     * [display] optionally gives the formatted text per field (AFNumber_Format etc.): the appearance shows it
     * while /V keeps the raw value. With a [context], Arabic text fields get an embedded Arabic font, shaped
     * right-aligned appearances. When an appearance cannot be generated NeedAppearances is set so the viewer
     * regenerates it. [dropXfa] removes the XFA packet of a static XFA form so AcroForm values win.
     */
    suspend fun fillFields(
        src: File, values: Map<String, String>, out: File, password: String? = null,
        context: android.content.Context? = null, display: Map<String, String> = emptyMap(), dropXfa: Boolean = false,
        rich: Map<String, com.smartdocs.editor.core.annot.AnnotStyle.Rich> = emptyMap(),
        richSpans: Map<String, List<com.smartdocs.editor.core.forms5.RichSpan>> = emptyMap(),
    ): Int = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val applied = fillIn(doc, values, context, display, dropXfa, rich = rich, richSpans = richSpans)
            doc.save(out)
            applied
        }
    }

    /** [fillFields] on an open document, without saving (the signed-file path saves it incrementally). */
    fun fillIn(
        doc: PDDocument, values: Map<String, String>, context: android.content.Context? = null,
        display: Map<String, String> = emptyMap(), dropXfa: Boolean = false, fixup: Boolean = true,
        rich: Map<String, com.smartdocs.editor.core.annot.AnnotStyle.Rich> = emptyMap(),
        richSpans: Map<String, List<com.smartdocs.editor.core.forms5.RichSpan>> = emptyMap(),
    ): Int {
        // signed files skip PdfBox's AcroForm fix-up (it would add /DR and /DA to the form in the update)
        val acro = (if (fixup) doc.documentCatalog.acroForm else doc.documentCatalog.getAcroForm(null)) ?: throw IllegalStateException("no form")
        var applied = 0
        var needViewer = false
        acro.needAppearances = false
        (values.keys + display.keys).distinct().forEach { name ->
            val f = acro.getField(name) ?: return@forEach
            if (f.isReadOnly && name !in display) return@forEach
            val value = values[name] ?: valueOf(f)
            val shown = display[name]
            // 8.28: a rich-text field's bold / italic face goes into /DA before the appearance is generated
            val richStyle = if (f is PDTextField && com.smartdocs.editor.core.forms5.RichFields.isRich(f))
                rich[name] ?: runCatching { com.smartdocs.editor.core.forms5.RichFields.styleOf(f.cosObject) }.getOrNull() else null
            if (richStyle != null && f is PDTextField && !ArabicShaper.isArabic(shown ?: value)) runCatching {
                val da = com.smartdocs.editor.core.forms5.RichFields.daFor(f.defaultAppearance ?: acro.defaultAppearance ?: "/Helv 0 Tf 0 g", richStyle)
                com.smartdocs.editor.core.forms5.RichFields.ensureFont(acro, da)
                f.defaultAppearance = da
            }
            val ok = runCatching {
                if (f is PDTextField && ((shown != null && shown != value) || ArabicShaper.isArabic(shown ?: value)))
                    applyTextAppearance(context, doc, acro, f, value, shown ?: value)
                else applyValue(acro, f, value)
            }.isSuccess
            // underline for every script; Arabic (one embedded face) also gets synthetic bold / italic
            if (ok && richStyle != null && f is PDTextField) runCatching {
                val text = shown ?: value
                val arabic = ArabicShaper.isArabic(text)
                val drawn = if (arabic) text.split('\n').joinToString("\n") { ArabicShaper.shape(it) } else text
                com.smartdocs.editor.core.forms5.RichFields.styleAppearance(acro, f, richStyle, drawn, syntheticFace = arabic)
            }
            if (!ok) { needViewer = true; runCatching { acro.needAppearances = true; applyValue(acro, f, value); acro.needAppearances = false } }
            if (name in values) applied++
        }
        if (needViewer) acro.needAppearances = true
        // 8.28: rich-text fields get their /RV again from the new value (and the style chosen while filling)
        runCatching { com.smartdocs.editor.core.forms5.RichFields.updateIn(acro, values, rich, richSpans) }
        if (dropXfa && acro.hasXFA()) acro.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.XFA)
        return applied
    }

    /** Clear every field to its empty state. */
    suspend fun resetAll(src: File, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val acro = doc.documentCatalog.acroForm ?: throw IllegalStateException("no form")
            acro.needAppearances = true
            for (f in acro.fieldTree) {
                if (f !is PDTerminalField || f.isReadOnly) continue
                runCatching {
                    when (f) {
                        is PDCheckBox -> f.unCheck()
                        is PDRadioButton -> f.setValue("Off")
                        is PDListBox -> f.setValue(emptyList<String>())
                        is PDComboBox -> f.setValue("")
                        is PDTextField -> f.setValue("")
                        is PDSignatureField, is PDPushButton -> Unit
                        else -> f.setValue("")
                    }
                }
            }
            doc.save(out)
        }
    }
}
