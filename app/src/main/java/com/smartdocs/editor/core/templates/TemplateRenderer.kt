package com.smartdocs.editor.core.templates

import android.content.Context
import com.smartdocs.editor.core.pdf.FormBuilder
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * Renders [TemplateKind]s into PDF files entirely on device. Page content is drawn with PdfBox (Arabic shaped
 * with the bundled Noto Naskh font); fillable parts become AcroForm fields through [FormBuilder], and invoice
 * totals get Acrobat calculate/format actions (AFSimple_Calculate / AFNumber_Format) that both Acrobat and the
 * app's own form-calculation engine understand.
 */
object TemplateRenderer {

    const val M = 40f   // page margin (points)

    suspend fun render(context: Context, kind: TemplateKind, input: TemplateInput, out: File) = withContext(Dispatchers.IO) {
        val base = File(context.cacheDir, "tpl_${kind.id}_${System.nanoTime()}.pdf")
        val withForm = File(context.cacheDir, "tpl_${kind.id}_${System.nanoTime()}_f.pdf")
        try {
            var fieldsCollected: List<com.smartdocs.editor.core.pdf.NewField> = emptyList()
            var scripts: Map<String, FieldScript> = emptyMap()
            var order: List<String> = emptyList()
            PDDocument().use { doc ->
                TplCanvas(context, doc).use { cv ->
                    draw(cv, kind, input)
                    cv.close()
                    fieldsCollected = cv.fields.toList(); scripts = cv.scripts.toMap(); order = cv.calcOrder.toList()
                }
                doc.documentInformation = PDDocumentInformation().apply {
                    title = input.title.ifBlank { TemplateDefaults.title(kind) }.ifBlank { kind.id }
                    creator = "SmartDocs"
                    creationDate = Calendar.getInstance()
                }
                doc.save(base)
            }
            if (input.withFields && fieldsCollected.isNotEmpty()) {
                FormBuilder.addFields(base, fieldsCollected, withForm)
                if (scripts.isNotEmpty()) applyScripts(withForm, out, scripts, order) else withForm.copyTo(out, overwrite = true)
            } else base.copyTo(out, overwrite = true)
            out
        } finally { base.delete(); withForm.delete() }
    }

    fun draw(cv: TplCanvas, kind: TemplateKind, input: TemplateInput) {
        when (kind) {
            TemplateKind.LINED -> TemplatePapers.lined(cv, input)
            TemplateKind.GRID -> TemplatePapers.grid(cv, input)
            TemplateKind.GRAPH -> TemplatePapers.graph(cv, input)
            TemplateKind.MUSIC -> TemplatePapers.music(cv, input)
            TemplateKind.TODO -> TemplatePapers.todo(cv, input)
            TemplateKind.TIMETABLE -> TemplatePapers.timetable(cv, input)
            TemplateKind.LETTER -> TemplateDocs.letter(cv, input)
            TemplateKind.INVOICE -> TemplateDocs.invoice(cv, input)
            TemplateKind.CERTIFICATE -> TemplateDocs.certificate(cv, input)
            TemplateKind.MINUTES -> TemplateDocs.minutes(cv, input)
            TemplateKind.EXAM -> TemplateDocs.exam(cv, input)
        }
    }

    /** Attach calculate/format/keystroke JavaScript actions, the calculation order and read-only flags. */
    private fun applyScripts(src: File, out: File, scripts: Map<String, FieldScript>, order: List<String>) {
        PDDocument.load(src).use { doc ->
            val acro = doc.documentCatalog.acroForm ?: run { src.copyTo(out, true); return }
            val co = COSArray()
            scripts.forEach { (name, s) ->
                val f = acro.getField(name) ?: return@forEach
                val aa = f.cosObject.getDictionaryObject(COSName.AA) as? COSDictionary ?: COSDictionary().also { f.cosObject.setItem(COSName.AA, it) }
                s.calc?.let { aa.setItem(COSName.C, PDActionJavaScript(it).cosObject) }
                s.format?.let { aa.setItem(COSName.F, PDActionJavaScript(it).cosObject) }
                s.keystroke?.let { aa.setItem(COSName.K, PDActionJavaScript(it).cosObject) }
                if (s.calc != null) f.isReadOnly = true
            }
            order.forEach { n -> acro.getField(n)?.let { co.add(it.cosObject) } }
            if (co.size() > 0) acro.cosObject.setItem(COSName.CO, co)
            doc.save(out)
        }
    }

    // ---------------------------------------------------------------- shared pieces

    /**
     * Letterhead: logo at the right (RTL), organisation name + subtitle next to it, an accent rule below.
     * Returns the y just under the header.
     */
    fun letterhead(cv: TplCanvas, input: TemplateInput, top: Float = cv.h - M): Float {
        val accent = input.accent
        val logoSize = 56f
        var textRight = cv.w - M
        if (input.logo != null) {
            cv.image(input.logo, cv.w - M - logoSize, top - logoSize, logoSize, logoSize)
            textRight -= logoSize + 12f
        }
        val org = input.org
        if (org.isNotBlank()) cv.text(org, textRight, top - 22f, 17f, Align.RIGHT, accent, bold = true)
        if (input.subtitle.isNotBlank()) cv.text(input.subtitle, textRight, top - 42f, 10.5f, Align.RIGHT, 0x616161)
        val bottom = top - logoSize - 10f
        cv.rect(M, bottom, cv.w - 2 * M, 2.2f, stroke = null, fill = accent)
        cv.rect(M, bottom - 3.5f, cv.w - 2 * M, 0.7f, stroke = null, fill = cv.tint(accent, 0.5f))
        return bottom - 14f
    }

    /** Footer rule + centred text. */
    fun footer(cv: TplCanvas, text: String, accent: Int) {
        cv.rect(M, M + 14f, cv.w - 2 * M, 0.8f, stroke = null, fill = cv.tint(accent, 0.3f))
        if (text.isNotBlank()) cv.text(text, cv.w / 2f, M, 9f, Align.CENTER, 0x757575)
    }

    /** Section heading with an accent bar at the right. Returns y below it. */
    fun sectionTitle(cv: TplCanvas, title: String, y: Float, accent: Int, right: Float = cv.w - M): Float {
        cv.rect(right - 4f, y - 3f, 4f, 16f, stroke = null, fill = accent)
        cv.text(title, right - 10f, y, 12.5f, Align.RIGHT, accent, bold = true)
        return y - 14f
    }

    val ordinals = listOf("الأول", "الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن", "التاسع", "العاشر")
    val days = listOf("السبت", "الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس")
}
