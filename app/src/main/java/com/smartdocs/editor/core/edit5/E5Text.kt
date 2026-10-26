package com.smartdocs.editor.core.edit5

import android.content.Context
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/**
 * Fonts offered for header/footer/watermark/TOC text. Arabic families come from the bundled Noto Naskh
 * (`PdfOps.arabicFont`) or Android's system fonts when present; the Latin half of mixed text uses [latin].
 */
enum class E5Font(val arabicFile: String?, val latin: String) {
    NASKH(null, "Helvetica"),
    NASKH_SYSTEM("NotoNaskhArabic", "Helvetica"),
    KUFI("NotoKufiArabic", "Helvetica"),
    SANS_ARABIC("NotoSansArabic", "Helvetica"),
    NASKH_SERIF(null, "Times"),
    MONO(null, "Courier"),
    ROBOTO(null, "Roboto");

    /** True when the system font file this entry needs exists on the device (bundled entries are always available). */
    fun available(): Boolean = (arabicFile == null || systemFile(arabicFile, false) != null) && (latin != "Roboto" || File("/system/fonts/Roboto-Regular.ttf").exists())

    companion object {
        fun systemFile(base: String, bold: Boolean): File? {
            val names = if (bold) listOf("$base-Bold.ttf", "$base-Regular.ttf", "$base-VF.ttf", "$base.ttf") else listOf("$base-Regular.ttf", "$base-VF.ttf", "$base.ttf")
            return names.map { File("/system/fonts/$it") }.firstOrNull { it.exists() }
        }
    }
}

/** Arabic + Latin fonts loaded into one document (cached per document). */
class E5Fonts(private val context: Context, private val doc: PDDocument) {
    private val cache = HashMap<String, PDFont>()

    fun arabic(f: E5Font, bold: Boolean): PDFont = cache.getOrPut("a:${f.name}:$bold") {
        val file = f.arabicFile?.let { E5Font.systemFile(it, bold) }
        if (file != null) runCatching { PDType0Font.load(doc, file) }.getOrNull() ?: PdfOps.arabicFont(context, doc)
        else PdfOps.arabicFont(context, doc)
    }

    fun latin(f: E5Font, bold: Boolean): PDFont = cache.getOrPut("l:${f.name}:$bold") {
        when (f.latin) {
            "Times" -> if (bold) PDType1Font.TIMES_BOLD else PDType1Font.TIMES_ROMAN
            "Courier" -> if (bold) PDType1Font.COURIER_BOLD else PDType1Font.COURIER
            "Roboto" -> runCatching { PDType0Font.load(doc, File(if (bold) "/system/fonts/Roboto-Bold.ttf" else "/system/fonts/Roboto-Regular.ttf")) }
                .getOrElse { if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA }
            else -> if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
        }
    }
}

/** Style of one line of text. [color] 0xRRGGBB. */
data class E5TextStyle(val font: E5Font = E5Font.NASKH, val size: Float = 10f, val color: Int = 0x333333, val bold: Boolean = false, val underline: Boolean = false)

/**
 * One line of mixed Arabic/Latin text, shaped and split into font runs in visual order. Drawn as real page
 * content with /ActualText carrying the logical text (search and copy see normal Arabic).
 */
class E5Line(fonts: E5Fonts, val logical: String, val style: E5TextStyle) {
    private class Run(val font: PDFont, val text: String, val width: Float)
    private val runs: List<Run>
    val width: Float

    init {
        val ar = fonts.arabic(style.font, style.bold)
        val lat = fonts.latin(style.font, style.bold)
        val encodable = HashMap<Pair<PDFont, Char>, Boolean>()
        fun can(f: PDFont, c: Char) = encodable.getOrPut(f to c) { try { f.encode(c.toString()); true } catch (e: Exception) { false } }
        val visual = if (ArabicShaper.isArabic(logical)) ArabicShaper.shape(logical) else logical
        val list = ArrayList<Run>()
        var cur: PDFont? = null
        val sb = StringBuilder()
        fun flush() {
            val f = cur ?: return
            if (sb.isEmpty()) return
            val s = sb.toString()
            list += Run(f, s, runCatching { f.getStringWidth(s) / 1000f * style.size }.getOrDefault(0f)); sb.clear()
        }
        for (c in visual) {
            val arabicChar = c.code in 0x0600..0x06FF || c.code in 0xFB50..0xFDFF || c.code in 0xFE70..0xFEFF
            val f = when {
                c == ' ' && cur != null && can(cur!!, ' ') -> cur!!
                arabicChar -> if (can(ar, c)) ar else null
                can(lat, c) -> lat
                can(ar, c) -> ar
                else -> null
            } ?: continue
            if (f !== cur) { flush(); cur = f }
            sb.append(c)
        }
        flush()
        runs = list
        width = list.sumOf { it.width.toDouble() }.toFloat()
    }

    val isEmpty get() = runs.isEmpty()

    /** Draw with the baseline starting at ([x], [y]) in the current coordinate system. */
    fun draw(cs: PDPageContentStream, x: Float, y: Float) {
        if (runs.isEmpty()) return
        val c = E5Io.rgb(style.color)
        cs.setNonStrokingColor(c[0], c[1], c[2])
        val tagged = ArabicShaper.isArabic(logical)
        if (tagged) {
            val props = COSDictionary(); props.setItem(COSName.getPDFName("ActualText"), COSString(logical))
            cs.beginMarkedContent(COSName.getPDFName("Span"), PDPropertyList.create(props))
        }
        var cx = x
        for (r in runs) {
            cs.beginText(); cs.setFont(r.font, style.size); cs.setTextMatrix(Matrix(1f, 0f, 0f, 1f, cx, y)); cs.showText(r.text); cs.endText()
            cx += r.width
        }
        if (tagged) cs.endMarkedContent()
        if (style.underline) {
            cs.setStrokingColor(c[0], c[1], c[2]); cs.setLineWidth((style.size / 18f).coerceAtLeast(0.4f))
            cs.moveTo(x, y - style.size * 0.15f); cs.lineTo(x + width, y - style.size * 0.15f); cs.stroke()
        }
    }
}
