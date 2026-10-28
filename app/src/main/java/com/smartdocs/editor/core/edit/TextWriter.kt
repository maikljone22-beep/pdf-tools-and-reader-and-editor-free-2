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
    // NVE-text: rich text and advanced formatting (any non-default value goes through RichWriter)
    val underline: Boolean = false,
    val strike: Boolean = false,
    val script: Script = Script.NONE,
    /** Extra space after every glyph (Tc), points. */
    val charSpacing: Float = 0f,
    /** Extra space after every space character, points (TJ adjustments: Tw is ignored by multi-byte fonts). */
    val wordSpacing: Float = 0f,
    /** Line height multiplier. */
    val lineSpacing: Float = 1f,
    /** Horizontal scaling (Tz), percent. */
    val hScale: Float = 100f,
    /** Baseline offset (Ts), points. */
    val rise: Float = 0f,
    /** Outline the glyphs with [strokeColor] / [strokeWidth] (0 = automatic). */
    val stroke: Boolean = false,
    val strokeColor: Int = 0x000000,
    val strokeWidth: Float = 0f,
    /** False with [stroke]: outline only (no fill). */
    val fillText: Boolean = true,
    /** Custom font face (see EditFonts ids: "asset:\u2026", "res:\u2026", "file:\u2026"); null = family / original font. */
    val fontPath: String? = null,
    val embed: FontEmbed = FontEmbed.SUBSET,
    val list: ListKind = ListKind.NONE,
    /** Per-range styles of the text (rich text). */
    val runs: List<RunStyle> = emptyList(),
    /** Bullet character for [ListKind.BULLET]. */
    val bullet: String = "\u2022",
    val numbering: NumberFormat = NumberFormat.DECIMAL,
    /** Start-side indent of every line, points. */
    val indent: Float = 0f,
    /** Extra space after each paragraph, points. */
    val paraSpacing: Float = 0f,
    /** Counter-clockwise rotation of the whole box around its top-start corner, degrees. */
    val rotation: Float = 0f,
    /** Paragraph direction override: null = from the text, true = right-to-left, false = left-to-right. */
    val rtlOverride: Boolean? = null,
    /** Vertical text: glyphs stacked top to bottom, columns right to left. */
    val vertical: Boolean = false,
    /** Text mode "invisible" (rendering mode 3, e.g. a searchable layer). */
    val invisible: Boolean = false,
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

/**
 * audit 5 C-7: the characters of [text] that no font can draw ([fontFor] returns null): [TextLayout] silently drops
 * them (`fontFor() ?: continue`). Whitespace and control characters are ignored. Pure, see TextEncodeCheckTest.
 */
internal fun unencodableChars(text: String, fontFor: (Char) -> PDFont?): Set<Char> {
    val out = LinkedHashSet<Char>()
    for (c in text) if (!c.isWhitespace() && !c.isISOControl() && c !in out && fontFor(c) == null) out += c
    return out
}

/** audit 5 C-7: pre-check of new text before it is written into the page content. */
object TextEncodeCheck {
    /**
     * The characters of [text] that the edit engine's fonts (a standard-14 font, the bundled Arabic font) cannot
     * encode; written text would silently lose them. Surrogate pairs (emoji) are reported as one string each.
     */
    fun missing(context: Context, text: String): List<String> = PDDocument().use { d ->
        val fonts = FontSet(context, d, null, EditTextStyle())
        val bad = unencodableChars(text) { c -> if (Character.isSurrogate(c)) null else fonts.fontFor(c) }
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i); val n = Character.charCount(cp)
            if (text.substring(i, i + n).any { it in bad }) out += String(Character.toChars(cp))
            i += n
        }
        out.toList()
    }
}

internal class Run(val font: PDFont, val text: String, val width: Float)

/** One laid-out line: [logical] text (for /ActualText), visual-order runs and total width in points. */
internal class LaidLine(val logical: String, val rtl: Boolean, val runs: List<Run>, val width: Float)

/** Line breaking + bidi/shaping + drawing of a text box written as real page content. */
internal class TextLayout(private val fonts: FontSet, private val style: EditTextStyle) {

    private fun width(f: PDFont, s: String): Float = try { f.getStringWidth(s) / 1000f * style.size } catch (e: Exception) { 0f }

    /** Visual-order string for one line of logical text. */
    // Shaping (contextual forms) + bidi reordering in one place: Arabic reversed, digits and Latin phrases kept
    // left-to-right ([ArabicShaper.shape] alone would reverse Arabic-Indic digits and Latin word order).
    private fun visual(line: String, rtl: Boolean): String = Bidi.shapeVisual(line, rtl)

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
                TextAlign.JUSTIFY -> if (line.rtl) right - line.width else left      // NVE-text: justified text uses RichWriter
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
