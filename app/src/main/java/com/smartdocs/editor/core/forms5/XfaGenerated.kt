package com.smartdocs.editor.core.forms5

import android.content.Context
import android.graphics.RectF
import com.smartdocs.editor.core.forms.XfaJs
import com.smartdocs.editor.core.forms.XfaLayout
import com.smartdocs.editor.core.forms.XfaMerge
import com.smartdocs.editor.core.forms.XfaSupport
import com.smartdocs.editor.core.forms.XfaTemplateParser
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.FieldType
import com.smartdocs.editor.core.pdf.FormBuilder
import com.smartdocs.editor.core.pdf.FormOps
import com.smartdocs.editor.core.pdf.NewField
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.showArabic
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Dynamic XFA without an XFA layout engine: the template's fields (XfaSupport.readFields, data-bound) are
 * laid out as a generated AcroForm PDF — one row per field (caption + real field, headings per subform,
 * right-to-left when the captions are Arabic). The result is a normal PDF that the reader fills in place;
 * [writeBack] copies its values into the datasets packet of the original XFA document. The mapping
 * (generated field name -> XFA data path) is kept in the document information dictionary.
 */
object XfaGenerated {

    const val KEY_SOURCE = "SmartDocsXfaSource"
    const val KEY_MAP = "SmartDocsXfaMap"
    const val KEY_REPEAT = "SmartDocsXfaRepeat"
    const val KEY_LAYOUT = "SmartDocsXfaLayout"

    private const val PW = 595f; private const val PH = 842f; private const val M = 40f
    private const val CAPTION_W = 190f; private const val GAP = 10f

    private data class Row(val f: XfaSupport.XField, val name: String, val height: Float, val type: FieldType?)

    private fun rowOf(f: XfaSupport.XField, i: Int): Row? {
        val name = "xfa_$i"
        return when (f.type) {
            XfaSupport.XType.BUTTON -> null
            XfaSupport.XType.CHECK -> Row(f, name, 16f, FieldType.CHECKBOX)
            XfaSupport.XType.RADIO -> Row(f, name, 18f * f.options.size.coerceAtLeast(1), FieldType.RADIO)
            XfaSupport.XType.CHOICE -> Row(f, name, 22f, FieldType.COMBO)
            XfaSupport.XType.SIGNATURE -> Row(f, name, 50f, FieldType.SIGNATURE)
            XfaSupport.XType.IMAGE -> Row(f, name, 18f, null)      // shown as a caption only
            else -> Row(f, name, if (f.multiline) 60f else 22f, FieldType.TEXT)
        }
    }

    private fun width(font: PDFont, s: String, size: Float) = runCatching { font.getStringWidth(s) / 1000f * size }.getOrNull()

    /** Draw [text] ending at [right] (RTL) or starting at [left]; shortened with an ellipsis to fit [maxW]. */
    private fun PDPageContentStream.label(latin: PDFont, arabic: PDFont, text: String, left: Float, right: Float, y: Float, size: Float, rtl: Boolean) {
        val isAr = ArabicShaper.isArabic(text)
        val font = if (isAr) arabic else latin
        var t = text.replace('\n', ' ').trim()
        fun shown(s: String) = if (isAr) ArabicShaper.shape(s) else s
        var w = width(font, shown(t), size) ?: run { if (!isAr) { t = t.filter { it.code in 32..126 }; width(font, t, size) } else null } ?: return
        while (w > right - left && t.length > 2) { t = t.dropLast(2) + "…"; w = width(font, shown(t), size) ?: return }
        beginText(); setFont(font, size)
        newLineAtOffset(if (rtl) right - w else left, y)
        if (isAr) showArabic(t, shown(t)) else showText(t)
        endText()
    }

    /**
     * Generate the fillable PDF for the dynamic XFA document [src] into [out]: the form in its own XFA layout
     * ([XfaLayout], scripts on the Rhino runtime), or the one-column list when the layout cannot be built.
     * Returns the number of fields.
     */
    suspend fun generate(context: Context, src: File, password: String?, out: File): Int = withContext(Dispatchers.IO) {
        runCatching { generateLayout(context, src, password, out) }.getOrNull() ?: generateList(context, src, password, out)
    }

    /** The XFA form in its original layout; null when it has no template or nothing to lay out. */
    private suspend fun generateLayout(context: Context, src: File, password: String?, out: File): Int? {
        val bytes = PdfOps.load(src, password).use { it.documentCatalog.acroForm?.xfa?.bytes } ?: return null
        val (xml, data) = XfaSupport.dataRoot(bytes)
        val template = XfaTemplateParser.parse(xml) ?: return null
        val merged = XfaMerge.merge(template, data)
        runCatching { XfaJs.run(merged) }
        val tmp = File(out.parentFile, ".${out.name}.xfa.pdf")
        val made: List<XfaRender.Made>
        try {
            PDDocument().use { doc ->
                val r = XfaRender(context, doc)
                val layout = XfaLayout(template, r.metrics).layout(merged)
                if (layout.pages.isEmpty()) return null
                val (m, repeats) = r.render(layout)
                made = m
                val map = buildJsonObject {
                    m.forEach { f ->
                        put(f.name, buildJsonObject {
                            put("p", JsonPrimitive(f.path)); put("t", JsonPrimitive(f.type.name))
                            put("o", buildJsonArray { f.options.forEach { add(JsonPrimitive(it)) } })
                            put("e", buildJsonArray { f.exports.forEach { add(JsonPrimitive(it)) } })
                        })
                    }
                }
                val info = doc.documentInformation ?: PDDocumentInformation().also { doc.documentInformation = it }
                info.setCustomMetadataValue(KEY_SOURCE, src.absolutePath)
                info.setCustomMetadataValue(KEY_MAP, map.toString())
                info.setCustomMetadataValue(KEY_REPEAT, XfaRepeat.toJson(repeats))
                info.setCustomMetadataValue(KEY_LAYOUT, "1")
                info.title = src.nameWithoutExtension
                doc.save(tmp)
            }
            // text and choice values get their appearances (Arabic font, shaping) through FormOps
            val initial = made.filter { it.type != XfaSupport.XType.CHECK && it.type != XfaSupport.XType.RADIO && it.initial.isNotEmpty() }.associate { it.name to it.initial }
            if (initial.isNotEmpty()) FormOps.fillFields(tmp, initial, out, null, context) else tmp.copyTo(out, overwrite = true)
        } finally { tmp.delete() }
        return made.size
    }

    /**
     * Add one instance of the repeating subform [r] to the XFA source of the generated form [generated]:
     * the current values are written back first, then the data group gets the new instance, then the form is
     * generated again into [out] (pass a temp file and swap it in).
     */
    suspend fun addRow(context: Context, generated: File, r: XfaRepeat, out: File): File = withContext(Dispatchers.IO) {
        val (src, _) = writeBack(generated, null)
        val tmp = File(src.parentFile, ".${src.name}.row.pdf")
        XfaSupport.addInstances(src, r.dataParent, r.name, r.count + 1, tmp)
        if (!tmp.renameTo(src)) { tmp.copyTo(src, overwrite = true); tmp.delete() }
        generate(context, src, null, out)
        src
    }

    /** The one-column fallback: one row per data-bound field. */
    private suspend fun generateList(context: Context, src: File, password: String?, out: File): Int = withContext(Dispatchers.IO) {
        val fields = XfaSupport.readFields(src, password).filter { it.bound }
        val rows = fields.mapIndexedNotNull { i, f -> rowOf(f, i) }
        val rtl = fields.count { ArabicShaper.isArabic(it.caption) } * 2 >= fields.size.coerceAtLeast(1) && fields.any { ArabicShaper.isArabic(it.caption) }
        val newFields = ArrayList<NewField>()
        val tmp = File(out.parentFile, ".${out.name}.layout.pdf")
        PDDocument().use { doc ->
            val arabic = PdfOps.arabicFont(context, doc); val latin = PDType1Font.HELVETICA
            var cs: PDPageContentStream? = null; var pageIndex = -1
            var y = 0f
            fun newPage() {
                cs?.close()
                val p = PDPage(PDRectangle(PW, PH)); doc.addPage(p); pageIndex++
                cs = PDPageContentStream(doc, p); y = PH - M
            }
            newPage()
            var lastGroup: String? = null
            for (r in rows) {
                val group = r.f.path.substringBeforeLast('.', "")
                val heading = group.substringAfterLast('.').replace(Regex("""\[\d+]"""), "")
                val needHeading = group != lastGroup && heading.isNotBlank() && !heading.equals("form1", true)
                val need = r.height + GAP + if (needHeading) 26f else 0f
                if (y - need < M) newPage()
                val c = cs!!
                if (needHeading) {
                    y -= 16f
                    c.label(latin, arabic, heading, M, PW - M, y, 12f, rtl)
                    c.setStrokingColor(0.6f, 0.6f, 0.6f); c.moveTo(M, y - 4f); c.lineTo(PW - M, y - 4f); c.stroke()
                    y -= 10f
                }
                lastGroup = group
                val top = y; val bottom = y - r.height
                val capL = if (rtl) PW - M - CAPTION_W else M; val capR = capL + CAPTION_W
                val fL = if (rtl) M else M + CAPTION_W + GAP; val fR = if (rtl) PW - M - CAPTION_W - GAP else PW - M
                c.setNonStrokingColor(0.1f, 0.1f, 0.1f)
                c.label(latin, arabic, r.f.caption.ifBlank { r.f.name }, capL, capR, top - 14f, 10f, rtl)
                when (r.type) {
                    FieldType.RADIO -> {
                        val opts = r.f.options.ifEmpty { r.f.exportValues }
                        opts.forEachIndexed { k, label ->
                            val rowTop = top - k * 18f
                            // the circle sits at the field rect's left edge (FormBuilder); its caption follows
                            c.label(latin, arabic, label, fL + 22f, fR, rowTop - 13f, 10f, rtl = false)
                        }
                        newFields += NewField(FieldType.RADIO, r.name, pageIndex, RectF(fL, top, fL + 18f, bottom),
                            options = r.f.exportValues.ifEmpty { opts }, tooltip = r.f.caption)
                    }
                    null -> c.label(latin, arabic, "—", fL, fR, top - 14f, 10f, rtl)
                    else -> newFields += NewField(r.type, r.name, pageIndex, RectF(fL, top, if (r.type == FieldType.CHECKBOX) fL + 16f else fR, bottom),
                        options = if (r.type == FieldType.COMBO) r.f.options.ifEmpty { r.f.exportValues } else emptyList(),
                        multiline = r.f.multiline, tooltip = r.f.caption)
                }
                y = bottom - GAP
            }
            cs?.close()
            val map = buildJsonObject {
                rows.forEach { r ->
                    put(r.name, buildJsonObject {
                        put("p", JsonPrimitive(r.f.path)); put("t", JsonPrimitive(r.f.type.name))
                        put("o", buildJsonArray { r.f.options.forEach { add(JsonPrimitive(it)) } })
                        put("e", buildJsonArray { r.f.exportValues.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            }
            val info = doc.documentInformation ?: PDDocumentInformation().also { doc.documentInformation = it }
            info.setCustomMetadataValue(KEY_SOURCE, src.absolutePath)
            info.setCustomMetadataValue(KEY_MAP, map.toString())
            info.title = src.nameWithoutExtension
            doc.save(tmp)
        }
        val withFields = File(out.parentFile, ".${out.name}.fields.pdf")
        try {
            val names = if (newFields.isEmpty()) emptyList() else FormBuilder.addFields(tmp, newFields, withFields)
            if (newFields.isEmpty()) tmp.copyTo(withFields, overwrite = true)
            // names come back in order; FormBuilder keeps "xfa_i" unique already
            val initial = HashMap<String, String>()
            rows.filter { it.type != null }.forEachIndexed { k, r ->
                val n = names.getOrNull(k) ?: r.name
                initial[n] = toAcro(r.f)
            }
            if (initial.isNotEmpty()) FormOps.fillFields(withFields, initial.filterValues { it.isNotEmpty() }, out, null, context)
            else withFields.copyTo(out, overwrite = true)
        } finally { tmp.delete(); withFields.delete() }
        newFields.size
    }

    private fun toAcro(f: XfaSupport.XField): String = when (f.type) {
        XfaSupport.XType.CHECK -> XfaSupport.isChecked(f).toString()
        XfaSupport.XType.CHOICE -> f.exportValues.indexOf(f.value).takeIf { it >= 0 }?.let { f.options.getOrNull(it) } ?: f.value
        else -> f.value
    }

    /** True when [file] was produced by [generate]. */
    suspend fun sourceOf(file: File): File? = withContext(Dispatchers.IO) {
        runCatching { PDDocument.load(file).use { it.documentInformation?.getCustomMetadataValue(KEY_SOURCE) } }.getOrNull()?.let { File(it) }?.takeIf { it.exists() }
    }

    /**
     * Copy the values of the generated form [generated] into the XFA datasets of its source document,
     * written to [out] (pass the source itself to update it in place). Returns the number of values.
     */
    suspend fun writeBack(generated: File, sourcePassword: String?, out: File? = null): Pair<File, Int> = withContext(Dispatchers.IO) {
        val (srcPath, mapJson) = PDDocument.load(generated).use { d ->
            val info = d.documentInformation ?: throw IllegalStateException("not generated")
            (info.getCustomMetadataValue(KEY_SOURCE) ?: throw IllegalStateException("not generated")) to (info.getCustomMetadataValue(KEY_MAP) ?: "{}")
        }
        val src = File(srcPath)
        if (!src.exists()) throw IllegalStateException(srcPath)
        val map = Json.parseToJsonElement(mapJson).jsonObject
        val acro = FormOps.listFields(generated).associate { it.name to it.value }
        val values = LinkedHashMap<String, String>()
        map.forEach { (name, el) ->
            val o = el.jsonObject
            val path = o["p"]?.jsonPrimitive?.content ?: return@forEach
            val type = o["t"]?.jsonPrimitive?.content
            val opts = o["o"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val exps = o["e"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val v = acro[name] ?: return@forEach
            values[path] = when (type) {
                "RADIO" -> if (v == "Off") "" else v
                "CHECK" -> XfaSupport.checkValue(XfaSupport.XField(path, name, "", XfaSupport.XType.CHECK, opts, exps), v.equals("true", true))
                "CHOICE" -> opts.indexOf(v).takeIf { it >= 0 }?.let { exps.getOrNull(it) } ?: v
                else -> v
            }
        }
        val target = out ?: src
        val tmp = File(target.parentFile, ".${target.name}.xfa.pdf")
        XfaSupport.saveValues(src, values, tmp, sourcePassword)
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        target to values.size
    }
}
