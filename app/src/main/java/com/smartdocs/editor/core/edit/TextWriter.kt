package com.smartdocs.editor.core.edit

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
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix

/** Formatting of text written into the page content. [size] in points, [color] 0xRRGGBB. */
data class EditTextStyle(
    val size: Float = 14f,
    val color: Int = 0x000000,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: TextAlign = TextAlign.LEFT,
    val family: FontFamily = FontFamily.SANS,
    /** Baseline-to-baseline distance; null = 1.25 × size. */
    val lineHeight: Float? = null,
)

/**
 * Chooses fonts for new text: the block's original font when it can be reused (not subset, encodes the
 * characters, same bold/italic), otherwise a standard-14 font of the same family; Arabic always goes to the
 * bundled Noto Naskh Arabic (shaped with [ArabicShaper]). Missing bold/italic is synthesised
 * (fill+stroke / skew).
 */
internal class FontSet(private val context: Context, private val doc: PDDocument, original: PDFont?, private val style: EditTextStyle) {
    private var arabicCache: PDFont? = null
    val arabic: PDFont get() = arabicCache ?: PdfOps.arabicFont(context, doc).also { arabicCache = it }
    val latin: PDFont = original ?: standard(style.family, style.bold, style.italic)
    private val latinIsStd = original == null
    private val encodable = HashMap<Pair<PDFont, Char>, Boolean>()

    fun canEncode(f: PDFont, c: Char): Boolean = encodable.getOrPut(f to c) {
        try { f.encode(c.toString()); true } catch (e: Exception) { false }
    }

    /** Font for one character of shaped (visual) text, or null when no font has a glyph for it. */
    fun fontFor(c: Char): PDFont? = when {
        PageTextModel.isArabicChar(c) -> arabic
        canEncode(latin, c) -> latin
        canEncode(arabic, c) -> arabic
        else -> null
    }

    /** Whether runs drawn with [f] need synthetic bold / italic. */
    fun fakeBold(f: PDFont) = style.bold && (f === arabic || !latinIsStd)
    fun fakeItalic(f: PDFont) = style.italic && (f === arabic || !latinIsStd)

    companion object {
        fun standard(family: FontFamily, bold: Boolean, italic: Boolean): PDFont = when (family) {
            FontFamily.SERIF -> when { bold && italic -> PDType1Font.TIMES_BOLD_ITALIC; bold -> PDType1Font.TIMES_BOLD; italic -> PDType1Font.TIMES_ITALIC; else -> PDType1Font.TIMES_ROMAN }
            FontFamily.MONO -> when { bold && italic -> PDType1Font.COURIER_BOLD_OBLIQUE; bold -> PDType1Font.COURIER_BOLD; italic -> PDType1Font.COURIER_OBLIQUE; else -> PDType1Font.COURIER }
            FontFamily.SANS -> when { bold && italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE; bold -> PDType1Font.HELVETICA_BOLD; italic -> PDType1Font.HELVETICA_OBLIQUE; else -> PDType1Font.HELVETICA }
        }
    }
}

internal class Run(val font: PDFont, val text: String, val width: Float)

/** One laid-out line: [logical] text (for /ActualText), visual-order runs and total width in points. */
internal class LaidLine(val logical: String, val rtl: Boolean, val runs: List<Run>, val width: Float)

/** Line breaking + bidi/shaping + drawing of a text box written as real page content. */
internal class TextLayout(private val fonts: FontSet, private val style: EditTextStyle) {

    private fun width(f: PDFont, s: String): Float = try { f.getStringWidth(s) / 1000f * style.size } catch (e: Exception) { 0f }

    /** Visual-order string for one line of logical text. */
    private fun visual(line: String, rtl: Boolean): String {
        if (!ArabicShaper.isArabic(line)) return line
        if (rtl) return ArabicShaper.shape(line)
        // left-to-right line with embedded Arabic words: shape each Arabic stretch on its own
        val sb = StringBuilder(); val run = StringBuilder()
        fun flush() { if (run.isNotEmpty()) { sb.append(ArabicShaper.shape(run.toString())); run.clear() } }
        for (c in line) {
            if (PageTextModel.isArabicChar(c) || (c == ' ' && run.isNotEmpty())) run.append(c) else { flush(); sb.append(c) }
        }
        flush()
        return sb.toString()
    }

    private fun runsOf(visual: String): List<Run> {
        val runs = ArrayList<Run>()
        var font: PDFont? = null
        val sb = StringBuilder()
        fun flush() { val f = font; if (f != null && sb.isNotEmpty()) runs.add(Run(f, sb.toString(), width(f, sb.toString()))); sb.clear() }
        for (c in visual) {
            val f = if (c == ' ' && font != null && fonts.canEncode(font!!, ' ')) font else fonts.fontFor(c) ?: continue
            if (f !== font) { flush(); font = f }
            sb.append(c)
        }
        flush()
        return runs
    }

    private fun measure(line: String, rtl: Boolean): Float = runsOf(visual(line, rtl)).sumOf { it.width.toDouble() }.toFloat()

    private fun make(line: String, rtl: Boolean): LaidLine {
        val runs = runsOf(visual(line, rtl))
        return LaidLine(line, rtl, runs, runs.sumOf { it.width.toDouble() }.toFloat())
    }

    /** Break [text] into lines no wider than [maxWidth]; [defaultRtl] is used for paragraphs without strong characters. */
    fun layout(text: String, maxWidth: Float, defaultRtl: Boolean): List<LaidLine> {
        val out = ArrayList<LaidLine>()
        for (para in text.replace("\r", "").split('\n')) {
            val strong = para.firstOrNull { it.isLetter() }
            val rtl = if (strong == null) defaultRtl else PageTextModel.isArabicChar(strong)
            if (para.isBlank()) { out.add(LaidLine("", rtl, emptyList(), 0f)); continue }
            var cur = ""
            for (word in para.split(' ')) {
                val cand = if (cur.isEmpty()) word else "$cur $word"
                if (cur.isNotEmpty() && measure(cand, rtl) > maxWidth) {
                    out.add(make(cur, rtl)); cur = word
                } else cur = cand
                // a single word wider than the box (URL, long number): hard-break left-to-right words
                while (!rtl && measure(cur, rtl) > maxWidth && cur.length > 1) {
                    var cut = cur.length - 1
                    while (cut > 1 && measure(cur.substring(0, cut), rtl) > maxWidth) cut--
                    out.add(make(cur.substring(0, cut), rtl)); cur = cur.substring(cut)
                }
            }
            out.add(make(cur, rtl))
        }
        return out
    }

    /**
     * Draw [lines] in display space (the caller already concatenated the display→user matrix):
     * between x = [left]..[right], first baseline at [firstBaseline].
     */
    fun draw(cs: PDPageContentStream, lines: List<LaidLine>, left: Float, right: Float, firstBaseline: Float) {
        val lh = style.lineHeight ?: (style.size * 1.25f)
        val r = (style.color shr 16) and 0xFF; val g = (style.color shr 8) and 0xFF; val b = style.color and 0xFF
        cs.setNonStrokingColor(r, g, b)
        cs.setStrokingColor(r, g, b)
        cs.setLineWidth(style.size * 0.035f)
        val skew = 0.21f
        lines.forEachIndexed { i, line ->
            if (line.runs.isEmpty()) return@forEachIndexed
            val baseline = firstBaseline - i * lh
            var x = when (style.align) {
                TextAlign.LEFT -> left
                TextAlign.RIGHT -> right - line.width
                TextAlign.CENTER -> left + (right - left - line.width) / 2f
            }
            val tagged = ArabicShaper.isArabic(line.logical)
            if (tagged) {
                val props = COSDictionary()
                props.setItem(COSName.getPDFName("ActualText"), COSString(line.logical))
                cs.beginMarkedContent(COSName.getPDFName("Span"), PDPropertyList.create(props))
            }
            for (run in line.runs) {
                val bold = fonts.fakeBold(run.font)
                cs.setRenderingMode(if (bold) RenderingMode.FILL_STROKE else RenderingMode.FILL)
                cs.beginText()
                cs.setFont(run.font, style.size)
                cs.setTextMatrix(Matrix(1f, 0f, if (fonts.fakeItalic(run.font)) skew else 0f, 1f, x, baseline))
                cs.showText(run.text)
                cs.endText()
                x += run.width
            }
            if (tagged) cs.endMarkedContent()
        }
        cs.setRenderingMode(RenderingMode.FILL)
    }
}
