package com.smartdocs.editor.core.forms5

import android.content.Context
import android.graphics.BitmapFactory
import com.smartdocs.editor.core.forms.XEdge
import com.smartdocs.editor.core.forms.XItem
import com.smartdocs.editor.core.forms.XLayoutResult
import com.smartdocs.editor.core.forms.XWKind
import com.smartdocs.editor.core.forms.XfaMetrics
import com.smartdocs.editor.core.forms.XfaSupport
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.showArabic
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField

/**
 * Draws an [XLayoutResult] (the XFA form in its own layout) into a new PDF: borders, fills, lines, arcs,
 * texts (Arabic shaped with the Naskh font), images, and real AcroForm fields at the laid-out positions
 * (text, check box, radio group, combo / list, signature). Returns the field mapping for [XfaGenerated].
 */
internal class XfaRender(private val context: Context, private val doc: PDDocument) {

    /** One generated AcroForm field: name, data path, type, options, exports and the initial AcroForm value. */
    data class Made(val name: String, val path: String, val type: XfaSupport.XType, val options: List<String>, val exports: List<String>, val initial: String)

    private val latin = PDType1Font.HELVETICA
    private val latinBold = PDType1Font.HELVETICA_BOLD
    private val latinItalic = PDType1Font.HELVETICA_OBLIQUE
    private val arabic: PDFont by lazy { PdfOps.arabicFont(context, doc) }

    /** Real font metrics for the layout pass. */
    val metrics = object : XfaMetrics {
        override fun width(text: String, size: Float, bold: Boolean): Float {
            val ar = ArabicShaper.isArabic(text)
            val f = if (ar) arabic else if (bold) latinBold else latin
            val s = if (ar) ArabicShaper.shape(text) else text
            return runCatching { f.getStringWidth(s) / 1000f * size }.getOrNull()
                ?: runCatching { f.getStringWidth(s.filter { it.code in 32..126 }) / 1000f * size }.getOrNull() ?: (s.length * size * 0.5f)
        }
    }

    private fun rgb(c: Int) = floatArrayOf((c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f)
    private fun PDPageContentStream.stroke(c: Int) { val a = rgb(c); setStrokingColor(a[0], a[1], a[2]) }
    private fun PDPageContentStream.fillC(c: Int) { val a = rgb(c); setNonStrokingColor(a[0], a[1], a[2]) }

    private fun PDPageContentStream.edge(e: XEdge) {
        stroke(e.color); setLineWidth(e.thickness.coerceAtLeast(0.1f))
        setLineDashPattern(if (e.dashed) floatArrayOf(3f, 2f) else floatArrayOf(), 0f)
    }

    private fun PDPageContentStream.ellipse(x: Float, y: Float, w: Float, h: Float) {
        val k = 0.5523f; val rx = w / 2f; val ry = h / 2f; val cx = x + rx; val cy = y + ry
        moveTo(cx + rx, cy)
        curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        closePath()
    }

    /** Draw one page's static items; returns the widgets to create. */
    fun drawPage(page: PDPage, items: List<XItem>, ph: Float) {
        PDPageContentStream(doc, page).use { cs ->
            fun py(y: Float) = ph - y
            for (it in items) runCatching {
                when (it) {
                    is XItem.Box -> {
                        if (it.fill != null && it.fill ushr 24 != 0) { cs.fillC(it.fill); cs.addRect(it.x, py(it.y + it.h), it.w, it.h); cs.fill() }
                        val e = it.edges
                        if (e.isNotEmpty() && e.all { x -> x != null && x == e[0] }) { cs.edge(e[0]!!); cs.addRect(it.x, py(it.y + it.h), it.w, it.h); cs.stroke() }
                        else {
                            val segs = listOf(floatArrayOf(it.x, it.y, it.x + it.w, it.y), floatArrayOf(it.x + it.w, it.y, it.x + it.w, it.y + it.h),
                                floatArrayOf(it.x, it.y + it.h, it.x + it.w, it.y + it.h), floatArrayOf(it.x, it.y, it.x, it.y + it.h))
                            e.forEachIndexed { k, ed -> if (ed != null && k < 4) { cs.edge(ed); val s = segs[k]; cs.moveTo(s[0], py(s[1])); cs.lineTo(s[2], py(s[3])); cs.stroke() } }
                        }
                    }
                    is XItem.Line -> { cs.edge(it.edge); cs.moveTo(it.x1, py(it.y1)); cs.lineTo(it.x2, py(it.y2)); cs.stroke() }
                    is XItem.Ellipse -> {
                        if (it.fill != null) { cs.fillC(it.fill); cs.ellipse(it.x, py(it.y + it.h), it.w, it.h); cs.fill() }
                        it.edge?.let { e -> cs.edge(e); cs.ellipse(it.x, py(it.y + it.h), it.w, it.h); cs.stroke() }
                    }
                    is XItem.Text -> text(cs, it, ph)
                    is XItem.Image -> image(cs, it, ph)
                    else -> {}
                }
            }
        }
    }

    private fun text(cs: PDPageContentStream, t: XItem.Text, ph: Float) {
        val f = t.font
        val lh = f.size * 1.2f
        val blockH = t.lines.size * lh
        val top = when (t.vAlign) { "middle" -> t.y + (t.h - blockH) / 2f; "bottom" -> t.y + t.h - blockH; else -> t.y }
        cs.fillC(f.color)
        t.lines.forEachIndexed { k, line ->
            if (line.isBlank()) return@forEachIndexed
            val ar = ArabicShaper.isArabic(line)
            val font = if (ar) arabic else if (f.bold) latinBold else if (f.italic) latinItalic else latin
            var shown = if (ar) ArabicShaper.shape(line) else line
            var w = runCatching { font.getStringWidth(shown) / 1000f * f.size }.getOrNull()
            if (w == null) { shown = shown.filter { it.code in 32..126 }; w = runCatching { font.getStringWidth(shown) / 1000f * f.size }.getOrNull() ?: return@forEachIndexed }
            val x = when (t.hAlign) { "center" -> t.x + (t.w - w) / 2f; "right" -> t.x + t.w - w; else -> t.x }
            val baseline = top + k * lh + f.size * 0.95f
            cs.beginText(); cs.setFont(font, f.size); cs.newLineAtOffset(x, ph - baseline)
            if (ar) cs.showArabic(line, shown) else cs.showText(shown)
            cs.endText()
        }
    }

    private fun image(cs: PDPageContentStream, it: XItem.Image, ph: Float) {
        val jpeg = it.data.size > 2 && it.data[0] == 0xFF.toByte() && it.data[1] == 0xD8.toByte()
        val img = if (jpeg) JPEGFactory.createFromByteArray(doc, it.data)
        else LosslessFactory.createFromImage(doc, BitmapFactory.decodeByteArray(it.data, 0, it.data.size) ?: return)
        var w = it.w; var h = it.h; var x = it.x; var y = it.y
        if (it.aspect != "none" && img.width > 0 && img.height > 0) {
            val k = minOf(it.w / img.width, it.h / img.height)
            w = img.width * k; h = img.height * k; x += (it.w - w) / 2f; y += (it.h - h) / 2f
        }
        cs.drawImage(img, x, ph - y - h, w, h)
    }

    // ------------------------------------------------------------------ fields

    private var counter = 0
    private val radios = HashMap<String, Pair<PDRadioButton, MutableList<PDAnnotationWidget>>>()

    private fun widget(page: PDPage, w: XItem.Widget, ph: Float): PDAnnotationWidget {
        val a = PDAnnotationWidget()
        a.rectangle = PDRectangle(w.x, ph - w.y - w.h, w.w, w.h)
        a.page = page; a.isPrinted = true
        page.annotations.add(a)
        return a
    }

    private fun buttonAp(on: Boolean, s: Float, round: Boolean, check: Boolean): PDAppearanceStream {
        val ap = PDAppearanceStream(doc)
        ap.bBox = PDRectangle(0f, 0f, s, s); ap.resources = PDResources()
        PDPageContentStream(doc, ap).use { cs ->
            if (!on) return@use
            cs.setNonStrokingColor(0f, 0f, 0f)
            if (check && !round) {
                cs.beginText(); cs.setFont(PDType1Font.ZAPF_DINGBATS, s * 0.8f); cs.newLineAtOffset(s * 0.15f, s * 0.2f); cs.showText("4"); cs.endText()
            } else { cs.ellipse(s * 0.25f, s * 0.25f, s * 0.5f, s * 0.5f); cs.fill() }
        }
        return ap
    }

    private fun states(a: PDAnnotationWidget, onName: String, s: Float, round: Boolean, check: Boolean, isOn: Boolean) {
        val n = COSDictionary()
        n.setItem(COSName.getPDFName(onName), buttonAp(true, s, round, check).cosObject)
        n.setItem(COSName.Off, buttonAp(false, s, round, check).cosObject)
        val ap = COSDictionary(); ap.setItem(COSName.N, n)
        a.cosObject.setItem(COSName.AP, ap)
        a.cosObject.setItem(COSName.AS, if (isOn) COSName.getPDFName(onName) else COSName.Off)
    }

    private fun flags(f: PDField, w: XItem.Widget) { f.isReadOnly = w.readOnly; f.isRequired = w.required; if (w.label.isNotBlank()) f.alternateFieldName = w.label }

    /** Create the AcroForm field(s) of one widget item. */
    fun field(acro: PDAcroForm, page: PDPage, w: XItem.Widget, ph: Float, out: MutableList<Made>) {
        val path = w.dataPath
        when (w.kind) {
            XWKind.RADIO -> {
                val key = w.group ?: return
                val entry = radios.getOrPut(key) {
                    val rb = PDRadioButton(acro); rb.partialName = "xfa_${counter++}"; flags(rb, w)
                    acro.fields.add(rb)
                    if (path != null) out += Made(rb.partialName, path, XfaSupport.XType.RADIO, emptyList(), emptyList(), w.value)
                    rb to ArrayList()
                }
                val on = w.onValue.ifBlank { "${entry.second.size + 1}" }
                val a = widget(page, w, ph)
                states(a, on, minOf(w.w, w.h), true, false, w.value.isNotEmpty() && w.value == on)
                entry.second += a; entry.first.widgets = entry.second
                if (w.value.isNotEmpty() && w.value == on) entry.first.cosObject.setItem(COSName.V, COSName.getPDFName(on))
                else if (entry.first.cosObject.getDictionaryObject(COSName.V) == null) entry.first.cosObject.setItem(COSName.V, COSName.Off)
            }
            XWKind.CHECK -> {
                val cb = PDCheckBox(acro); cb.partialName = "xfa_${counter++}"; flags(cb, w)
                val a = widget(page, w, ph)
                val checked = w.value.isNotBlank() && w.value == w.onValue
                states(a, "Yes", minOf(w.w, w.h), w.round, true, checked)
                cb.widgets = listOf(a); acro.fields.add(cb)
                cb.cosObject.setItem(COSName.V, if (checked) COSName.getPDFName("Yes") else COSName.Off)
                if (path != null) out += Made(cb.partialName, path, XfaSupport.XType.CHECK, emptyList(), listOf(w.onValue, w.offValue), checked.toString())
            }
            XWKind.COMBO, XWKind.LIST -> {
                val ch = if (w.kind == XWKind.COMBO) PDComboBox(acro).also { it.isEdit = w.editable } else PDListBox(acro)
                ch.partialName = "xfa_${counter++}"; flags(ch, w)
                ch.defaultAppearance = "/Helv ${fmt(w.fontSize)} Tf 0 g"
                val exports = w.exports.ifEmpty { w.options }
                val displays = w.options.ifEmpty { exports }
                if (exports.size == displays.size && exports != displays) ch.setOptions(exports, displays) else ch.setOptions(displays)
                ch.widgets = listOf(widget(page, w, ph)); acro.fields.add(ch)
                val stored = if (exports.size == displays.size && exports != displays) exports else displays
                if (path != null) out += Made(ch.partialName, path, XfaSupport.XType.CHOICE, stored, stored, w.value)
            }
            XWKind.BUTTON -> {
                val pb = PDPushButton(acro); pb.partialName = "xfa_${counter++}"; flags(pb, w)
                pb.cosObject.setBoolean(COSName.getPDFName(XfaEventRuntime.KEY_CLICK), true)
                pb.widgets = listOf(widget(page, w, ph)); acro.fields.add(pb)
                if (path != null) out += Made(pb.partialName, path, XfaSupport.XType.BUTTON, emptyList(), emptyList(), "")
            }
            XWKind.SIGNATURE -> {
                val sf = PDSignatureField(acro); sf.partialName = "xfa_${counter++}"
                sf.widgets = listOf(widget(page, w, ph)); acro.fields.add(sf)
            }
            XWKind.TEXT -> {
                val tf = PDTextField(acro); tf.partialName = "xfa_${counter++}"; flags(tf, w)
                tf.defaultAppearance = "/Helv ${fmt(w.fontSize)} Tf 0 g"
                tf.isMultiline = w.multiline; tf.isPassword = w.password
                if (w.maxChars > 0) tf.maxLen = w.maxChars
                if (w.comb > 0) { tf.maxLen = w.comb; tf.isComb = true }
                tf.q = when (w.hAlign) { "center" -> PDTextField.QUADDING_CENTERED; "right" -> PDTextField.QUADDING_RIGHT; else -> PDTextField.QUADDING_LEFT }
                // XFA picture clause: kept on the field, applied when the value is shown (FormFillState.display)
                val hint = when (w.type) {
                    XfaSupport.XType.NUMBER -> com.smartdocs.editor.core.forms.XfaPicture.Hint.NUMBER
                    XfaSupport.XType.DATE -> com.smartdocs.editor.core.forms.XfaPicture.Hint.DATE
                    else -> com.smartdocs.editor.core.forms.XfaPicture.Hint.TEXT
                }
                listOf(com.smartdocs.editor.core.forms.XfaPicture.KEY to w.picture, com.smartdocs.editor.core.forms.XfaPicture.KEY_EDIT to w.editPicture,
                    com.smartdocs.editor.core.forms.XfaPicture.KEY_VALIDATE to w.validatePicture).forEach { (k, p) ->
                    if (p.isNotBlank()) tf.cosObject.setString(COSName.getPDFName(k), com.smartdocs.editor.core.forms.XfaPicture.withCategory(p, hint, w.locale))
                }
                tf.widgets = listOf(widget(page, w, ph)); acro.fields.add(tf)
                if (path != null) out += Made(tf.partialName, path, w.type, emptyList(), emptyList(), w.value)
            }
        }
    }

    private fun fmt(f: Float) = if (f == kotlin.math.floor(f)) f.toInt().toString() else "%.1f".format(java.util.Locale.US, f)

    /** Draw all pages and create all fields; returns the mapping and the add-row places. */
    fun render(result: XLayoutResult): Pair<List<Made>, List<XfaRepeat>> {
        val acro = doc.documentCatalog.acroForm ?: PDAcroForm(doc).also { form ->
            doc.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 0 Tf 0 g"
        }
        val made = ArrayList<Made>(); val repeats = ArrayList<XfaRepeat>()
        for (p in result.pages) {
            val page = PDPage(PDRectangle(p.w, p.h)); doc.addPage(page)
            drawPage(page, p.items, p.h)
            for (it in p.items) when (it) {
                is XItem.Widget -> runCatching { field(acro, page, it, p.h, made) }
                is XItem.AddRow -> repeats += XfaRepeat(it.repeat.key, it.repeat.dataParent, it.repeat.name, it.repeat.count, it.repeat.max, p.index,
                    Box5(it.x, p.h - it.y, it.x + it.w, p.h - it.y - it.h))
                else -> {}
            }
        }
        return made to repeats
    }
}
