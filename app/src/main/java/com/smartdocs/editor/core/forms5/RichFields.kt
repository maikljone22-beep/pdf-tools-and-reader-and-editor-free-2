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

    // ---------------------------------------------------------------- appearance (Latin text)

    private val STYLED_FONTS = mapOf("HeBo" to "Helvetica-Bold", "HeOb" to "Helvetica-Oblique", "HeBO" to "Helvetica-BoldOblique")
    private val DA_FONT = Regex("""/(\S+)\s+(-?[\d.]+)\s+Tf""")

    /**
     * The /DA of a rich field for [style]: Helvetica Bold / Oblique / BoldOblique for bold / italic text, plain
     * Helvetica when a style of ours is switched off again; any other font stays.
     */
    fun daFor(da: String, style: AnnotStyle.Rich?): String {
        val m = DA_FONT.find(da) ?: return da
        val want = when {
            style?.bold == true && style.italic -> "HeBO"; style?.bold == true -> "HeBo"; style?.italic == true -> "HeOb"
            m.groupValues[1] in STYLED_FONTS -> "Helv"
            else -> return da
        }
        return da.replaceRange(m.range, "/$want ${m.groupValues[2]} Tf")
    }

    /** Make sure the AcroForm /DR has the Helvetica face that [da] names. */
    fun ensureFont(acro: PDAcroForm, da: String) {
        val name = DA_FONT.find(da)?.groupValues?.get(1) ?: return
        val base = STYLED_FONTS[name] ?: return
        val dr = acro.defaultResources ?: com.tom_roush.pdfbox.pdmodel.PDResources().also { acro.defaultResources = it }
        if (dr.getFont(COSName.getPDFName(name)) != null) return
        val font = when (base) {
            "Helvetica-Bold" -> com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA_BOLD
            "Helvetica-Oblique" -> com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA_OBLIQUE
            else -> com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA_BOLD_OBLIQUE
        }
        dr.put(COSName.getPDFName(name), font)
    }

    /**
     * [content] of a single-line text appearance with an underline added under its text: the line starts at the
     * first `Td` position and is [textWidth] long ([size] from the `Tf` of the stream). Null when there is no text.
     */
    fun underlined(content: String, textWidth: Float): String? {
        val td = Regex("""(-?[\d.]+)\s+(-?[\d.]+)\s+Td""").find(content) ?: return null
        val size = DA_FONT.find(content)?.groupValues?.get(2)?.toFloatOrNull()?.takeIf { it > 0f } ?: 12f
        val x = td.groupValues[1].toFloat(); val y = td.groupValues[2].toFloat() - size * 0.12f
        val lw = (size / 16f).coerceAtLeast(0.5f)
        fun n(v: Float) = String.format(java.util.Locale.US, "%.2f", v)
        return content.trimEnd() + "\nq 0 G ${n(lw)} w ${n(x)} ${n(y)} m ${n(x + textWidth)} ${n(y)} l S Q\n"
    }

    /** After the appearance of [f] was generated for [text]: underline it when [style] asks for it (single-line fields). */
    fun underlineAppearance(acro: PDAcroForm, f: PDTextField, style: AnnotStyle.Rich?, text: String) {
        if (style?.underline != true || f.isMultiline || text.isEmpty()) return
        for (w in f.widgets) {
            val ap = w.normalAppearanceStream ?: continue
            val content = ap.cosObject.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }
            val size = DA_FONT.find(content)?.groupValues?.get(2)?.toFloatOrNull()?.takeIf { it > 0f } ?: 12f
            val fontName = DA_FONT.find(content)?.groupValues?.get(1)
            val font = fontName?.let { ap.resources?.getFont(COSName.getPDFName(it)) ?: acro.defaultResources?.getFont(COSName.getPDFName(it)) }
            val width = font?.let { runCatching { it.getStringWidth(text) / 1000f * size }.getOrNull() } ?: (text.length * size * 0.5f)
            val out = underlined(content, width) ?: continue
            ap.cosObject.createOutputStream().use { it.write(out.toByteArray(Charsets.ISO_8859_1)) }
        }
    }
}
