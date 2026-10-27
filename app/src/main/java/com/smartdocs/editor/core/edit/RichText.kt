package com.smartdocs.editor.core.edit

import com.smartdocs.editor.core.pdf.ArabicShaper
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.max

/** Superscript / subscript of a run. */
enum class Script { NONE, SUPER, SUB }

/** Paragraph list marker added in front of every paragraph. */
enum class ListKind { NONE, BULLET, NUMBER }

/**
 * How the fonts of new text are stored: [SUBSET] (only the used glyphs embedded, default), [FULL] (whole font
 * embedded, the text stays editable in other editors) or [SYSTEM] (standard-14 fonts, not embedded; Arabic still
 * needs an embedded font because no standard font has Arabic glyphs).
 */
enum class FontEmbed { SUBSET, FULL, SYSTEM }

enum class TextCase { UPPER, LOWER, TITLE, SENTENCE }

/** Numbering library of [ListKind.NUMBER]. */
enum class NumberFormat { DECIMAL, DECIMAL_PAREN, LOWER_ALPHA, UPPER_ALPHA, LOWER_ROMAN, UPPER_ROMAN, ARABIC_INDIC }

/**
 * Style of the characters [start]..[end) of the edited text (rich text): null fields keep the base style. Later runs
 * win over earlier ones.
 */
data class RunStyle(
    val start: Int, val end: Int,
    val bold: Boolean? = null, val italic: Boolean? = null, val color: Int? = null, val size: Float? = null,
    val underline: Boolean? = null, val strike: Boolean? = null, val script: Script? = null,
)

/** Resolved style of one character. */
data class CharStyle(val bold: Boolean, val italic: Boolean, val color: Int, val size: Float, val underline: Boolean, val strike: Boolean, val script: Script) {
    /** Point size the glyphs are drawn at (super / subscript are smaller). */
    val drawSize: Float get() = if (script == Script.NONE) size else size * 0.65f
    /** Text rise for this character on top of the base [baseRise]. */
    fun rise(baseRise: Float): Float = baseRise + when (script) { Script.SUPER -> size * 0.35f; Script.SUB -> -size * 0.15f; Script.NONE -> 0f }
}

/** Pure helpers of the rich-text model (no Android classes: unit tested on the JVM). */
object RichText {
    fun base(st: EditTextStyle) = CharStyle(st.bold, st.italic, st.color, st.size, st.underline, st.strike, st.script)

    /** Style of every character of [text]. */
    fun styles(st: EditTextStyle, text: String): Array<CharStyle> {
        val b = base(st)
        val out = Array(text.length) { b }
        for (r in st.runs) {
            val s = r.start.coerceIn(0, text.length); val e = r.end.coerceIn(0, text.length)
            for (i in s until e) {
                val c = out[i]
                out[i] = CharStyle(r.bold ?: c.bold, r.italic ?: c.italic, r.color ?: c.color, r.size ?: c.size, r.underline ?: c.underline,
                    r.strike ?: c.strike, r.script ?: c.script)
            }
        }
        return out
    }

    /** True when every character of [start]..[end) satisfies [pred] (false for an empty range). */
    fun all(st: EditTextStyle, text: String, start: Int, end: Int, pred: (CharStyle) -> Boolean): Boolean {
        val s = start.coerceIn(0, text.length); val e = end.coerceIn(0, text.length)
        if (e <= s) return false
        val cs = styles(st, text)
        return (s until e).all { pred(cs[it]) }
    }

    /** [runs] after the text changed from [old] to [new] (the edited region is found by common prefix / suffix). */
    fun shiftRuns(runs: List<RunStyle>, old: String, new: String): List<RunStyle> {
        if (runs.isEmpty() || old == new) return runs
        var p = 0
        val maxP = minOf(old.length, new.length)
        while (p < maxP && old[p] == new[p]) p++
        var s = 0
        while (s < old.length - p && s < new.length - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        val oldEnd = old.length - s            // replaced region in old: [p, oldEnd)
        val delta = new.length - old.length
        val inserted = new.length - s - p      // characters typed at p
        fun map(i: Int, isEnd: Boolean): Int = when {
            i < p -> i
            i >= oldEnd && !(i == oldEnd && i == p) -> i + delta
            // inside the replaced region (or exactly at an insertion point)
            isEnd -> p + inserted
            else -> p
        }
        return runs.mapNotNull { r ->
            // typing at the end of a run continues it (like a word processor)
            val ns = map(r.start, false)
            val ne = if (r.end == p && r.start < p) p + inserted else map(r.end, true)
            if (ne <= ns) null else r.copy(start = ns, end = ne)
        }
    }

    /** Drops empty or out-of-range runs. */
    fun normalize(runs: List<RunStyle>, length: Int): List<RunStyle> =
        runs.mapNotNull { r -> val s = r.start.coerceIn(0, length); val e = r.end.coerceIn(0, length); if (e > s) r.copy(start = s, end = e) else null }

    /** [text] with its case changed (the whole text, or the range [start]..[end) when given). */
    fun changeCase(text: String, mode: TextCase, start: Int = 0, end: Int = text.length): String {
        val s = start.coerceIn(0, text.length); val e = end.coerceIn(s, text.length)
        val part = text.substring(s, e)
        val changed = when (mode) {
            TextCase.UPPER -> part.uppercase()
            TextCase.LOWER -> part.lowercase()
            TextCase.TITLE -> {
                val sb = StringBuilder(part.length)
                var startWord = s == 0 || !text[s - 1].isLetterOrDigit()
                for (c in part) { sb.append(if (startWord) c.titlecaseChar() else c.lowercaseChar()); startWord = !c.isLetterOrDigit() && c != '\'' }
                sb.toString()
            }
            TextCase.SENTENCE -> {
                val sb = StringBuilder(part.length)
                var startSentence = s == 0 || text.substring(0, s).trimEnd().let { it.isEmpty() || it.last() in ".!?\n\u061F" }
                for (c in part) {
                    if (c.isLetter()) { sb.append(if (startSentence) c.titlecaseChar() else c.lowercaseChar()); startSentence = false }
                    else { sb.append(c); if (c in ".!?\n\u061F") startSentence = true }
                }
                sb.toString()
            }
        }
        // uppercase may change the length (German sharp s): keep it simple, the range is replaced as a whole
        return text.substring(0, s) + changed + text.substring(e)
    }

    /** True when [st] needs the rich writer ([RichWriter]) instead of the plain one. */
    fun isRich(st: EditTextStyle): Boolean = st.runs.isNotEmpty() || st.align == TextAlign.JUSTIFY || st.charSpacing != 0f || st.wordSpacing != 0f ||
        st.lineSpacing != 1f || st.hScale != 100f || st.rise != 0f || st.stroke || !st.fillText || st.underline || st.strike ||
        st.script != Script.NONE || st.list != ListKind.NONE || st.fontPath != null || st.embed != FontEmbed.SUBSET ||
        st.indent != 0f || st.paraSpacing != 0f || st.rotation != 0f || st.rtlOverride != null || st.vertical || st.invisible

    /** Marker in front of paragraph number [n] (1-based). */
    fun marker(kind: ListKind, n: Int, bullet: String = "\u2022", format: NumberFormat = NumberFormat.DECIMAL): String = when (kind) {
        ListKind.NONE -> ""
        ListKind.BULLET -> "$bullet "
        ListKind.NUMBER -> when (format) {
            NumberFormat.DECIMAL -> "$n. "
            NumberFormat.DECIMAL_PAREN -> "$n) "
            NumberFormat.LOWER_ALPHA -> alpha(n) + ". "
            NumberFormat.UPPER_ALPHA -> alpha(n).uppercase() + ". "
            NumberFormat.LOWER_ROMAN -> roman(n).lowercase() + ". "
            NumberFormat.UPPER_ROMAN -> roman(n) + ". "
            NumberFormat.ARABIC_INDIC -> n.toString().map { c -> if (c.isDigit()) (0x0660 + (c - '0')).toChar() else c }.joinToString("") + ". "
        }
    }

    /** Bullet library offered by the UI. */
    val BULLETS = listOf("\u2022", "\u25E6", "\u25AA", "\u2013", "\u2713", "\u27A2", "\u2605", "\u25C6")

    fun alpha(n: Int): String { var k = n; val sb = StringBuilder(); while (k > 0) { k--; sb.append('a' + k % 26); k /= 26 }; return sb.reverse().toString() }

    fun roman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var k = n; val sb = StringBuilder()
        for (i in v.indices) while (k >= v[i]) { sb.append(s[i]); k -= v[i] }
        return sb.toString()
    }
}

/** Fonts of the rich writer: custom face, original font, standard-14 variants, Arabic fallback. */
internal class RichFonts(
    private val original: PDFont?,
    private val base: EditTextStyle,
    private val arabicLoader: () -> PDFont,
    customLoader: (() -> PDFont?)?,
) {
    private var arabicCache: PDFont? = null
    val arabic: PDFont get() = arabicCache ?: arabicLoader().also { arabicCache = it }
    val custom: PDFont? = if (base.embed == FontEmbed.SYSTEM) null else runCatching { customLoader?.invoke() }.getOrNull()
    /** The custom face could not be loaded (e.g. a CFF OpenType font): the writer fell back to a standard font. */
    val customFailed: Boolean = base.fontPath != null && base.embed != FontEmbed.SYSTEM && custom == null
    private val std = HashMap<Pair<Boolean, Boolean>, PDFont>()
    private val encodable = HashMap<Pair<PDFont, Char>, Boolean>()

    fun canEncode(f: PDFont, c: Char): Boolean = encodable.getOrPut(f to c) { try { f.encode(c.toString()); true } catch (e: Exception) { false } }

    private fun standard(bold: Boolean, italic: Boolean) = std.getOrPut(bold to italic) { FontSet.standard(base.family, bold, italic) }

    fun latin(st: CharStyle): PDFont = custom ?: original?.takeIf { st.bold == base.bold && st.italic == base.italic } ?: standard(st.bold, st.italic)

    fun fontFor(c: Char, st: CharStyle): PDFont? {
        if (PageTextModel.isArabicChar(c)) return custom?.takeIf { canEncode(it, c) } ?: arabic
        val l = latin(st)
        return when { canEncode(l, c) -> l; canEncode(arabic, c) -> arabic; else -> null }
    }

    private fun isStd(f: PDFont) = std.values.any { it === f }
    fun fakeBold(f: PDFont, st: CharStyle) = st.bold && !isStd(f) && !(f === original && st.bold == base.bold)
    fun fakeItalic(f: PDFont, st: CharStyle) = st.italic && !isStd(f) && !(f === original && st.italic == base.italic)
}

/** One drawn run: glyphs of one font and one style, in visual order. [width] in points (after horizontal scaling). */
internal class RPiece(val font: PDFont, val text: String, val st: CharStyle, val width: Float, val spaces: Int)

/** One laid-out line. [indent]: hanging indent of list continuation lines (on the start side). */
internal class RLine(val logical: String, val rtl: Boolean, val runs: List<RPiece>, val width: Float, val lastOfPara: Boolean, val indent: Float, val maxSize: Float)

/** A character with its style (list markers get the paragraph's first style). */
internal class SC(val ch: Char, val st: CharStyle)

/** Line breaking, bidi (per style run), shaping and drawing of rich text. */
internal class RichLayout(private val fonts: RichFonts, private val style: EditTextStyle) {
    private val th = style.hScale / 100f

    fun runWidth(f: PDFont, s: String, st: CharStyle): Float {
        val w = try { f.getStringWidth(s) / 1000f * st.drawSize } catch (e: Exception) { 0f }
        return (w + style.charSpacing * s.length + style.wordSpacing * s.count { it == ' ' }) * th
    }

    /** Visual-order pieces of one logical line. */
    fun pieces(line: List<SC>, rtl: Boolean): List<RPiece> {
        if (line.isEmpty()) return emptyList()
        val s = String(CharArray(line.size) { line[it].ch })
        val lv = Bidi.levels(s, rtl)
        // logical segments: same level and same style
        class Seg(val a: Int, val b: Int, val level: Int)
        val segs = ArrayList<Seg>()
        var a = 0
        for (i in 1..s.length) {
            if (i == s.length || lv[i] != lv[a] || line[i].st != line[a].st) { segs += Seg(a, i, lv[a]); a = i }
        }
        val maxL = lv.maxOrNull() ?: 0
        val minOdd = (lv.filter { it % 2 == 1 }.minOrNull() ?: (maxL + 1)).coerceAtLeast(1)
        val order = segs.indices.toMutableList()
        var level = maxL
        while (level >= minOdd) {
            var i = 0
            while (i < order.size) {
                if (segs[order[i]].level < level) { i++; continue }
                var j = i; while (j < order.size && segs[order[j]].level >= level) j++
                order.subList(i, j).reverse()
                i = j
            }
            level--
        }
        val out = ArrayList<RPiece>()
        for (k in order) {
            val seg = segs[k]
            val st = line[seg.a].st
            val shaped = Bidi.shapeLogicalRuns(s.substring(seg.a, seg.b))
            val flips = (minOdd..maxL).count { it <= seg.level }
            val visual = if (flips % 2 == 1) buildString { for (c in shaped.reversed()) append(Bidi.mirror(c)) } else shaped
            // split by font
            var font: PDFont? = null
            val sb = StringBuilder()
            fun flush() { val f = font; if (f != null && sb.isNotEmpty()) out += RPiece(f, sb.toString(), st, runWidth(f, sb.toString(), st), sb.count { it == ' ' }); sb.clear() }
            for (c in visual) {
                val f = if (c == ' ' && font != null && fonts.canEncode(font!!, ' ')) font else fonts.fontFor(c, st) ?: continue
                if (f !== font) { flush(); font = f }
                sb.append(c)
            }
            flush()
        }
        return out
    }

    private fun measure(line: List<SC>, rtl: Boolean) = pieces(line, rtl).sumOf { it.width.toDouble() }.toFloat()

    fun layout(text: String, maxWidth: Float, defaultRtl: Boolean): List<RLine> {
        val styles = RichText.styles(style, text)
        val out = ArrayList<RLine>()
        var offset = 0
        var number = 0
        for (para in text.split('\n')) {
            val start = offset
            offset += para.length + 1
            val clean = para.replace("\r", "")
            val strong = clean.firstOrNull { it.isLetter() }
            val rtl = style.rtlOverride ?: if (strong == null) defaultRtl else PageTextModel.isArabicChar(strong)
            val chars = ArrayList<SC>(para.length + 4)
            for (i in para.indices) if (para[i] != '\r') chars += SC(para[i], styles[start + i])
            if (clean.isBlank()) { out += RLine("", rtl, emptyList(), 0f, true, 0f, style.size); continue }
            val first = chars.first().st
            val marker = if (style.list != ListKind.NONE) RichText.marker(style.list, ++number, style.bullet, style.numbering) else ""
            val markerChars = marker.map { SC(it, first) }
            val indent = if (marker.isEmpty()) 0f else measure(markerChars, rtl)
            // words with their trailing space handling: split on ' '
            val words = ArrayList<List<SC>>()
            var cur = ArrayList<SC>()
            for (c in chars) { if (c.ch == ' ') { words += cur; cur = ArrayList() } else cur += c }
            words += cur
            var line = ArrayList<SC>(markerChars)
            var firstLine = true
            fun width(l: List<SC>) = measure(l, rtl)
            fun avail() = (if (firstLine) maxWidth - style.indent else maxWidth - indent - style.indent).coerceAtLeast(style.size)
            fun emit(l: List<SC>, last: Boolean) {
                val p = pieces(l, rtl)
                out += RLine(l.joinToString("") { it.ch.toString() }, rtl, p, p.sumOf { it.width.toDouble() }.toFloat(), last,
                    style.indent + if (firstLine) 0f else indent, l.maxOfOrNull { it.st.size } ?: style.size)
                firstLine = false
            }
            val lineStart = markerChars.size
            for (w in words) {
                val cand = ArrayList<SC>(line)
                if (line.size > (if (firstLine) lineStart else 0)) cand += SC(' ', w.firstOrNull()?.st ?: first)
                cand += w
                if (line.size > (if (firstLine) lineStart else 0) && width(cand) > avail()) {
                    emit(line, false); line = ArrayList(w)
                } else line = cand
                // a single word wider than the line: hard-break left-to-right words
                while (!rtl && width(line) > avail() && line.size > 1) {
                    var cut = line.size - 1
                    while (cut > 1 && width(line.subList(0, cut)) > avail()) cut--
                    emit(line.subList(0, cut).toList(), false); line = ArrayList(line.subList(cut, line.size))
                }
            }
            emit(line, true)
        }
        return out
    }

    /** Draw [lines] (display space, the caller concatenated display→user) between [left]..[right], first baseline [firstBaseline]. */
    fun draw(cs: PDPageContentStream, lines: List<RLine>, left: Float, right: Float, firstBaseline: Float) {
        val baseLh = (style.lineHeight ?: (style.size * 1.25f)) * style.lineSpacing
        var baseline = firstBaseline
        val skew = 0.21f
        lines.forEachIndexed { i, line ->
            if (i > 0) baseline -= max(baseLh, line.maxSize * 1.2f * style.lineSpacing) + if (lines[i - 1].lastOfPara) style.paraSpacing else 0f
            if (line.runs.isEmpty()) return@forEachIndexed
            val l0 = if (line.rtl) left else left + line.indent
            val r0 = if (line.rtl) right - line.indent else right
            val avail = r0 - l0
            val spaces = line.runs.sumOf { it.spaces }
            val justify = style.align == TextAlign.JUSTIFY && !line.lastOfPara && spaces > 0 && line.width < avail
            val extra = if (justify) (avail - line.width) / spaces else 0f
            val w = if (justify) avail else line.width
            var x = when (style.align) {
                TextAlign.LEFT -> l0
                TextAlign.RIGHT -> r0 - w
                TextAlign.CENTER -> l0 + (avail - w) / 2f
                TextAlign.JUSTIFY -> if (justify || !line.rtl) l0 else r0 - w
            }
            val tagged = ArabicShaper.isArabic(line.logical)
            if (tagged) {
                val props = COSDictionary()
                props.setItem(COSName.getPDFName("ActualText"), COSString(line.logical))
                cs.beginMarkedContent(COSName.getPDFName("Span"), PDPropertyList.create(props))
            }
            for (run in line.runs) {
                val st = run.st
                val ds = st.drawSize
                val r = (st.color shr 16) and 0xFF; val g = (st.color shr 8) and 0xFF; val b = st.color and 0xFF
                val sc = if (style.stroke) style.strokeColor else st.color
                cs.saveGraphicsState()
                cs.setNonStrokingColor(r, g, b)
                cs.setStrokingColor((sc shr 16) and 0xFF, (sc shr 8) and 0xFF, sc and 0xFF)
                val fake = fonts.fakeBold(run.font, st)
                val mode = when {
                    style.invisible -> RenderingMode.NEITHER
                    style.stroke || !style.fillText -> if (style.fillText) RenderingMode.FILL_STROKE else RenderingMode.STROKE
                    fake -> RenderingMode.FILL_STROKE
                    else -> RenderingMode.FILL
                }
                cs.setLineWidth(if (style.stroke || !style.fillText) (if (style.strokeWidth > 0f) style.strokeWidth else ds * 0.04f) else ds * 0.035f)
                cs.beginText()
                cs.setFont(run.font, ds)
                cs.setRenderingMode(mode)
                if (style.charSpacing != 0f) cs.setCharacterSpacing(style.charSpacing)
                if (th != 1f) cs.setHorizontalScaling(style.hScale)
                val rise = st.rise(style.rise)
                if (rise != 0f) cs.setTextRise(rise)
                cs.setTextMatrix(Matrix(1f, 0f, if (fonts.fakeItalic(run.font, st)) skew else 0f, 1f, x, baseline))
                // word spacing (and justification) as TJ adjustments after spaces: Tw is ignored by multi-byte fonts
                val gap = style.wordSpacing + extra / th
                if (gap != 0f && run.spaces > 0) {
                    val parts = ArrayList<Any>()
                    val sb = StringBuilder()
                    for (c in run.text) {
                        sb.append(c)
                        if (c == ' ') { parts += sb.toString(); sb.clear(); parts += -gap * 1000f / ds }
                    }
                    if (sb.isNotEmpty()) parts += sb.toString()
                    cs.showTextWithPositioning(parts.toTypedArray())
                } else cs.showText(run.text)
                cs.endText()
                val runW = run.width + extra * run.spaces
                if (st.underline || st.strike) {
                    cs.setStrokingColor(r, g, b)
                    cs.setLineWidth(ds * 0.06f)
                    if (st.underline) { val y = baseline + rise - ds * 0.12f; cs.moveTo(x, y); cs.lineTo(x + runW, y); cs.stroke() }
                    if (st.strike) { val y = baseline + rise + ds * 0.28f; cs.moveTo(x, y); cs.lineTo(x + runW, y); cs.stroke() }
                }
                cs.restoreGraphicsState()
                x += runW
            }
            if (tagged) cs.endMarkedContent()
        }
    }

    /**
     * Vertical text: every paragraph is one column, glyphs stacked top to bottom (upright), columns from the right edge
     * leftwards; spaces leave a gap. Arabic letters are drawn in their isolated forms.
     */
    fun drawVertical(cs: PDPageContentStream, text: String, left: Float, right: Float, top: Float) {
        val styles = RichText.styles(style, text)
        val colW = style.size * 1.3f
        var colX = right - colW
        var offset = 0
        for (para in text.split('\n')) {
            var y = top
            for (i in para.indices) {
                val st = styles[offset + i]
                val ds = st.drawSize
                y -= ds * 1.1f + style.charSpacing
                val c = para[i]
                if (c == ' ' || c == '\r') continue
                val f = fonts.fontFor(c, st) ?: continue
                val w = runWidth(f, c.toString(), st)
                val r = (st.color shr 16) and 0xFF; val g = (st.color shr 8) and 0xFF; val b = st.color and 0xFF
                cs.saveGraphicsState()
                cs.setNonStrokingColor(r, g, b); cs.setStrokingColor(r, g, b)
                cs.beginText()
                cs.setFont(f, ds)
                cs.setRenderingMode(if (style.invisible) RenderingMode.NEITHER else if (fonts.fakeBold(f, st) || style.stroke) RenderingMode.FILL_STROKE else RenderingMode.FILL)
                if (th != 1f) cs.setHorizontalScaling(style.hScale)
                cs.setTextMatrix(Matrix(1f, 0f, 0f, 1f, colX + (colW - w) / 2f, y))
                cs.showText(c.toString())
                cs.endText()
                cs.restoreGraphicsState()
            }
            offset += para.length + 1
            colX -= colW
            if (colX < left - colW * 4) break
        }
    }

    /** Total height of [lines] from the first baseline to the last one. */
    fun height(lines: List<RLine>): Float {
        val baseLh = (style.lineHeight ?: (style.size * 1.25f)) * style.lineSpacing
        return lines.indices.drop(1).sumOf { i -> (max(baseLh, lines[i].maxSize * 1.2f * style.lineSpacing) + if (lines[i - 1].lastOfPara) style.paraSpacing else 0f).toDouble() }.toFloat()
    }
}

/** Writes rich text as page content (pure PDFBox; fonts come from the loaders). */
internal object RichWriter {
    /**
     * Lays out and draws [text] on [page] between [left]..[right] (display space; [toUser] maps display → user
     * space), first baseline at [baseline]. Returns the lines that were drawn (empty for blank text).
     */
    fun write(
        doc: PDDocument, page: PDPage, toUser: Matrix, original: PDFont?, text: String, style: EditTextStyle,
        left: Float, right: Float, baseline: Float, defaultRtl: Boolean,
        arabicLoader: () -> PDFont, customLoader: (() -> PDFont?)?,
    ): RichFonts {
        val fonts = RichFonts(original?.takeIf { style.embed != FontEmbed.SYSTEM && style.fontPath == null }, style, arabicLoader, customLoader)
        val layout = RichLayout(fonts, style)
        val lines = layout.layout(text, (right - left).coerceAtLeast(style.size * 2), defaultRtl)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.saveGraphicsState()
            cs.transform(toUser)
            val top = baseline + style.size * 0.85f
            if (style.rotation != 0f) {
                // rotate the whole box around its top-start corner
                val rtl = style.rtlOverride ?: lines.firstOrNull { it.runs.isNotEmpty() }?.rtl ?: defaultRtl
                cs.transform(Affine.rotateAround(style.rotation, if (rtl) right else left, top))
            }
            if (style.vertical) layout.drawVertical(cs, text, left, right, top) else layout.draw(cs, lines, left, right, baseline)
            cs.restoreGraphicsState()
        }
        return fonts
    }

    /** Lines of [text] for [style] in a box [width] wide (for overflow into linked boxes). */
    fun lines(text: String, style: EditTextStyle, width: Float, defaultRtl: Boolean, original: PDFont?, arabicLoader: () -> PDFont, customLoader: (() -> PDFont?)?): List<RLine> {
        val fonts = RichFonts(original?.takeIf { style.embed != FontEmbed.SYSTEM && style.fontPath == null }, style, arabicLoader, customLoader)
        return RichLayout(fonts, style).layout(text, width.coerceAtLeast(style.size * 2), defaultRtl)
    }

    fun height(style: EditTextStyle, lines: List<RLine>, fonts: RichFonts): Float = RichLayout(fonts, style).height(lines)
}
