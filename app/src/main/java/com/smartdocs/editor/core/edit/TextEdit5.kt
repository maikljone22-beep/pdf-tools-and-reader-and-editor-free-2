package com.smartdocs.editor.core.edit

import android.content.Context
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDTrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDVectorFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Where a text box overflows to (linked text boxes): page and box, display space (top > bottom). */
data class LinkTarget(val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * NVE-text: text operations beyond [ContentEdit]: rotated and form-XObject text, moving / resizing a box, join and
 * split, linked text boxes, kerning removal, text to outlines, whole-page reflow, rich writing. Every operation
 * reads [src] and writes [out] (never [src]); the reader turns it into one undo step (applyInPlace).
 */
object TextEdit5 {
    private const val MARGIN = 18f

    private suspend fun <T> edit(src: File, out: File, password: String?, block: (PDDocument) -> T): T = withContext(Dispatchers.IO) {
        EditIo.load(src, password).use { doc -> val r = block(doc); EditIo.save(doc, out); r }
    }

    private fun findBlock(a: PageAnalysis, ops: IntArray): TextBlock = a.model.blocks.firstOrNull { it.ops.contentEquals(ops) } ?: throw StalePageException()

    // ------------------------------------------------------------------ writing

    internal fun arabicLoader(context: Context, doc: PDDocument, embed: FontEmbed): () -> PDFont = { EditFonts.arabic(context, doc, embed) }

    internal fun customLoader(context: Context, doc: PDDocument, style: EditTextStyle): (() -> PDFont?)? =
        style.fontPath?.let { id -> { EditFonts.load(context, doc, id, style.embed) } }

    /** Rich writing from [ContentEdit] (display frame of the page). */
    internal fun writeRich(context: Context, doc: PDDocument, page: PDPage, frame: PageFrame, original: PDFont?, text: String, style: EditTextStyle,
                           left: Float, right: Float, baseline: Float, defaultRtl: Boolean) {
        RichWriter.write(doc, page, frame.toUser, original, text, style, left, right, baseline, defaultRtl, arabicLoader(context, doc, style.embed), customLoader(context, doc, style))
    }

    /** Writes [text] in a frame whose coordinates map to user space by [toUser]; returns the laid-out height (first to last baseline). */
    private fun writeIn(context: Context, doc: PDDocument, page: PDPage, toUser: Matrix, original: PDFont?, text: String, style: EditTextStyle,
                        left: Float, right: Float, baseline: Float, rtl: Boolean): Float {
        if (text.isBlank()) return 0f
        if (RichText.isRich(style)) {
            val fonts = RichWriter.write(doc, page, toUser, original, text, style, left, right, baseline, rtl, arabicLoader(context, doc, style.embed), customLoader(context, doc, style))
            return RichLayout(fonts, style).let { l -> l.height(l.layout(text, (right - left).coerceAtLeast(style.size * 2), rtl)) }
        }
        val fonts = FontSet(context, doc, original, style)
        val layout = TextLayout(fonts, style)
        val lines = layout.layout(text, (right - left).coerceAtLeast(style.size * 2), rtl)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.saveGraphicsState(); cs.transform(toUser); layout.draw(cs, lines, left, right, baseline); cs.restoreGraphicsState()
        }
        return (lines.size - 1).coerceAtLeast(0) * (style.lineHeight ?: (style.size * 1.25f))
    }

    // ------------------------------------------------------------------ removing (page and form text)

    /** Copy of a form XObject (copy-on-write: the original may be painted elsewhere). */
    internal fun cloneForm(doc: PDDocument, form: PDFormXObject): PDFormXObject {
        val src = form.cosObject
        val cs = doc.document.createCOSStream()
        for ((k, v) in src.entrySet()) if (k != COSName.LENGTH && k != COSName.FILTER && k != COSName.DECODE_PARMS) cs.setItem(k, v)
        src.createInputStream().use { inp -> cs.createOutputStream().use { inp.copyTo(it) } }
        return PDFormXObject(cs)
    }

    /**
     * Removes the text operators [ops] (page operators and first-level form operators, see [FormOps]). Forms are
     * copied first and the page paints the copy, so other pages using the same form keep it.
     */
    internal fun removeOps(doc: PDDocument, pageIndex: Int, a: PageAnalysis, ops: IntArray, rw: ContentRewriter = ContentRewriter(doc, doc.getPage(pageIndex)), commit: Boolean = true): ContentRewriter {
        val page = doc.getPage(pageIndex)
        ops.filter { !FormOps.isForm(it) }.forEach { rw.removeText(it, a.scanner.textOps[it]) }
        ops.filter { FormOps.isForm(it) }.groupBy { FormOps.doOp(it) }.forEach { (doOp, list) ->
            val form = a.scanner.forms[doOp] ?: return@forEach
            val copy = cloneForm(doc, form)
            val frw = ContentRewriter(doc, copy)
            list.forEach { frw.removeText(FormOps.formOp(it), a.scanner.textOps[it]) }
            frw.commit()
            val res = page.resources ?: PDResources().also { page.resources = it }
            val name = res.add(copy, "Fm")
            rw.replace(doOp, listOf(name, Operator.getOperator("Do")))
        }
        if (commit) rw.commit()
        return rw
    }

    // ------------------------------------------------------------------ geometry of a block

    /** Frame of a block: display-to-user matrix of its own (rotated) frame, its rect in that frame. */
    private class BlockFrame(val toUser: Matrix, val rect: android.graphics.RectF, val rotated: Boolean)

    private fun frameOf(a: PageAnalysis, b: TextBlock): BlockFrame =
        if (b.angle != 0f && b.frameRect != null) BlockFrame(Affine.mul(Affine.rotateAround(b.angle, 0f, 0f), a.frame.toUser), b.frameRect, true)
        else BlockFrame(a.frame.toUser, b.rect, false)

    /** Left / right bounds for rewriting [b] in [style] ([dw]: width change of a resized box, display points). */
    private fun bounds(a: PageAnalysis, b: TextBlock, f: BlockFrame, style: EditTextStyle, dw: Float?): Pair<Float, Float> {
        val r = f.rect
        if (dw != null) return r.left to (r.right + dw).coerceAtLeast(r.left + style.size * 2)
        if (b.lineCount > 1) return r.left to r.right
        val wide = if (f.rotated) 5000f else 0f
        return when (style.align) {
            TextAlign.LEFT -> r.left to if (f.rotated) r.left + wide else (a.frame.width - MARGIN).coerceAtLeast(r.right)
            TextAlign.RIGHT -> (if (f.rotated) r.right - wide else MARGIN.coerceAtMost(r.left)) to r.right
            TextAlign.CENTER, TextAlign.JUSTIFY -> r.left to r.right
        }
    }

    private fun originalFont(a: PageAnalysis, b: TextBlock, style: EditTextStyle): PDFont? =
        a.fonts[b.id]?.takeIf { b.fontReusable && style.bold == b.bold && style.italic == b.italic && style.family == b.family && style.fontPath == null && style.embed != FontEmbed.SYSTEM }

    private fun lineHeightFor(b: TextBlock, style: EditTextStyle) =
        style.copy(lineHeight = style.lineHeight ?: if (b.lineCount > 1) b.lineHeight * (style.size / b.size) else null)

    // ------------------------------------------------------------------ operations

    /**
     * Replace block [ops] with [text] in [style]; works for upright, rotated and form text. [dx]/[dy] move the box
     * (display points), [dw] changes its width (the text re-wraps). Empty [text] deletes the block.
     */
    suspend fun replace(context: Context, src: File, out: File, password: String?, page: Int, ops: IntArray, text: String, style: EditTextStyle,
                        dx: Float = 0f, dy: Float = 0f, dw: Float? = null) = edit(src, out, password) { doc ->
        val a = PageTextModel.analyze(doc, page)
        replaceIn(context, doc, page, a, findBlock(a, ops), text, style, dx, dy, dw)
    }

    /** [replace] inside an open document (also used by [ContentEdit.replaceText] for rotated / form text). */
    internal fun replaceIn(context: Context, doc: PDDocument, page: Int, a: PageAnalysis, b: TextBlock, text: String, style: EditTextStyle,
                           dx: Float = 0f, dy: Float = 0f, dw: Float? = null) {
        removeOps(doc, page, a, b.ops)
        if (text.isNotBlank()) {
            val f = frameOf(a, b)
            // the move is given in display space: into the block's own frame
            val (mx, my) = if (f.rotated) Affine.apply(Affine.rotateAround(-b.angle, 0f, 0f), dx, dy) else dx to dy
            val st = lineHeightFor(b, style)
            val (l, r) = bounds(a, b, f, st, dw)
            val base = b.firstBaseline          // in the block's own frame (rotated blocks too)
            writeIn(context, doc, doc.getPage(page), f.toUser, originalFont(a, b, st), text, st, l + mx, r + mx, base + my, st.rtlOverride ?: b.rtl)
        }
    }

    /** Delete block [ops] (page or form text). */
    suspend fun delete(src: File, out: File, password: String?, page: Int, ops: IntArray) = edit(src, out, password) { doc ->
        val a = PageTextModel.analyze(doc, page)
        removeOps(doc, page, a, findBlock(a, ops).ops)
        Unit
    }

    /** Join blocks [opsA] and [opsB] into one block (the upper one first), separated by [separator], in [style]. */
    suspend fun join(context: Context, src: File, out: File, password: String?, page: Int, opsA: IntArray, opsB: IntArray, separator: String, style: EditTextStyle) =
        edit(src, out, password) { doc ->
            val a = PageTextModel.analyze(doc, page)
            val b1 = findBlock(a, opsA); val b2 = findBlock(a, opsB)
            require(b1.angle == b2.angle) { "rotation differs" }
            val (first, second) = if (b1.rect.top >= b2.rect.top) b1 to b2 else b2 to b1
            removeOps(doc, page, a, first.ops + second.ops)
            val f1 = frameOf(a, first); val f2 = frameOf(a, second)
            val left = minOf(f1.rect.left, f2.rect.left); val right = max(f1.rect.right, f2.rect.right)
            val text = first.text + separator + second.text
            val st = lineHeightFor(first, style)
            writeIn(context, doc, doc.getPage(page), f1.toUser, originalFont(a, first, st), text, st, left, right, first.firstBaseline, first.rtl)
        }

    /** Split the block [ops] whose edited text is [text] at [at]: the second part becomes its own block below the first. */
    suspend fun split(context: Context, src: File, out: File, password: String?, page: Int, ops: IntArray, text: String, at: Int, style: EditTextStyle) =
        edit(src, out, password) { doc ->
            val a = PageTextModel.analyze(doc, page)
            val b = findBlock(a, ops)
            val k = at.coerceIn(0, text.length)
            val t1 = text.substring(0, k).trimEnd(); val t2 = text.substring(k).trimStart()
            require(t1.isNotBlank() && t2.isNotBlank()) { "nothing to split" }
            removeOps(doc, page, a, b.ops)
            val f = frameOf(a, b)
            val st = lineHeightFor(b, style)
            val (l, r) = bounds(a, b, f, st, null)
            val p = doc.getPage(page)
            val h = writeIn(context, doc, p, f.toUser, originalFont(a, b, st), t1, st, l, r, b.firstBaseline, b.rtl)
            // more than the paragraph gap of the text model: the two parts are read back as separate blocks
            val gap = max(st.size * 2.2f, (st.lineHeight ?: st.size * 1.25f) * 2f)
            writeIn(context, doc, p, f.toUser, originalFont(a, b, st), t2, st, l, r, b.firstBaseline - h - gap, b.rtl)
        }

    /**
     * Linked text boxes: block [ops] keeps the lines of [text] that fit [boxHeight] (display points); the rest replaces
     * the text of the linked box [target] (or is written into it when it holds no text).
     */
    suspend fun flowLinked(context: Context, src: File, out: File, password: String?, page: Int, ops: IntArray, text: String, style: EditTextStyle,
                           boxHeight: Float, target: LinkTarget) = edit(src, out, password) { doc ->
        val a = PageTextModel.analyze(doc, page)
        val b = findBlock(a, ops)
        val f = frameOf(a, b)
        val st = lineHeightFor(b, style)
        val (l, r) = bounds(a, b, f, st, 0f)
        val arabic = arabicLoader(context, doc, st.embed)
        val lines = RichWriter.lines(text, st, r - l, b.rtl, originalFont(a, b, st), arabic, customLoader(context, doc, st))
        val (head, tail) = splitLines(lines, st, boxHeight)
        val ta = if (target.page == page) a else PageTextModel.analyze(doc, target.page)
        val cx = (target.left + target.right) / 2f; val cy = (target.top + target.bottom) / 2f
        val tb = ta.model.blocks.firstOrNull { it !== b && !it.ops.contentEquals(b.ops) && cx in it.rect.left..it.rect.right && cy in it.rect.bottom..it.rect.top }
        if (target.page == page) {
            removeOps(doc, page, a, if (tb != null) b.ops + tb.ops else b.ops)
        } else {
            removeOps(doc, page, a, b.ops)
            if (tb != null) removeOps(doc, target.page, ta, tb.ops)
        }
        val p = doc.getPage(page)
        writeIn(context, doc, p, f.toUser, originalFont(a, b, st), head, st, l, r, b.firstBaseline, b.rtl)
        if (tail.isNotBlank()) {
            val tst = st.copy(lineHeight = null)
            writeIn(context, doc, doc.getPage(target.page), ta.frame.toUser, null, tail, tst, target.left, target.right, target.top - tst.size * 0.85f, b.rtl)
        }
    }

    /** Text of the lines that fit [boxHeight] and of the rest (paragraph breaks kept). */
    internal fun splitLines(lines: List<RLine>, style: EditTextStyle, boxHeight: Float): Pair<String, String> {
        val baseLh = (style.lineHeight ?: (style.size * 1.25f)) * style.lineSpacing
        var used = style.size
        var k = 0
        while (k < lines.size) {
            val add = if (k == 0) 0f else max(baseLh, lines[k].maxSize * 1.2f * style.lineSpacing)
            if (k > 0 && used + add > boxHeight) break
            used += add; k++
        }
        fun join(l: List<RLine>) = buildString { l.forEachIndexed { i, ln -> append(ln.logical); if (i < l.size - 1) append(if (ln.lastOfPara) '\n' else ' ') } }
        return join(lines.subList(0, k)) to join(lines.subList(k, lines.size))
    }

    /** Remove kerning: every `[(a) -20 (b)] TJ` of the block becomes `[(a)(b)] TJ` (page operators only). */
    suspend fun removeKerning(src: File, out: File, password: String?, page: Int, ops: IntArray) = edit(src, out, password) { doc ->
        val a = PageTextModel.analyze(doc, page)
        val b = findBlock(a, ops)
        val rw = ContentRewriter(doc, doc.getPage(page))
        var changed = 0
        for (op in b.ops) {
            if (FormOps.isForm(op) || rw.operatorName(op) != "TJ") continue
            val unkerned = unkern(rw.original(op)) ?: continue
            rw.replace(op, unkerned); changed++
        }
        require(changed > 0) { "no kerning" }
        rw.commit()
    }

    /** `[… n …] TJ` without its numbers (null when there is nothing to remove). Pure: unit tested. */
    internal fun unkern(tokens: List<Any>): List<Any>? {
        val arr = tokens.firstOrNull { it is COSArray } as? COSArray ?: return null
        if ((0 until arr.size()).none { arr.getObject(it) is COSNumber }) return null
        val n = COSArray()
        for (i in 0 until arr.size()) { val o = arr.getObject(i); if (o is COSString) n.add(o) }
        return listOf(n, Operator.getOperator("TJ"))
    }

    /**
     * Text to outlines: the glyphs of block [ops] are painted as filled paths (from the font outlines) and the text is
     * removed. Glyphs of fonts without outlines (Type 3) stay text. Returns the number of converted glyphs.
     */
    suspend fun toOutlines(src: File, out: File, password: String?, page: Int, ops: IntArray): Int = edit(src, out, password) { doc ->
        val a = PageTextModel.analyze(doc, page)
        val b = findBlock(a, ops)
        val set = b.ops.toHashSet()
        val glyphs = (a.scanner.glyphs + a.scanner.rotated.values.flatten()).filter { it.op in set && it.trm != null }
        val p = doc.getPage(page)
        var converted = 0
        val doneOps = HashSet<Int>()
        val failedOps = HashSet<Int>()
        val paths = ArrayList<Pair<Int, List<List<Pair<Float, Float>>>>>()
        for (g in glyphs) {
            val f = g.font
            val poly = if (f is PDVectorFont) runCatching { glyphPolygons(f, g.code, g.trm!!) }.getOrNull() else null
            if (poly == null) { failedOps += g.op; continue }
            if (poly.isNotEmpty()) paths += g.color to poly
            doneOps += g.op; converted++
        }
        require(converted > 0) { "no outlines" }
        // an operator is removed only when all its glyphs were converted
        val removable = doneOps.filter { it !in failedOps }.toIntArray()
        removeOps(doc, page, a, removable)
        PDPageContentStream(doc, p, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.saveGraphicsState()
            cs.transform(a.frame.toUser)
            for ((color, polys) in paths) {
                cs.setNonStrokingColor((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
                for (poly in polys) {
                    if (poly.size < 2) continue
                    cs.moveTo(poly[0].first, poly[0].second)
                    for (i in 1 until poly.size) cs.lineTo(poly[i].first, poly[i].second)
                    cs.closePath()
                }
                cs.fill()
            }
            cs.restoreGraphicsState()
        }
        converted
    }

    /** Outline of glyph [code] as polygons in display space ([trm]: the glyph's text rendering matrix in display space). */
    private fun glyphPolygons(font: PDVectorFont, code: Int, trm: FloatArray): List<List<Pair<Float, Float>>> {
        val path = font.getPath(code)
        val units = when (font) {
            is PDTrueTypeFont -> 1000f / font.trueTypeFont.header.unitsPerEm
            is PDType0Font -> (font.descendantFont as? PDCIDFontType2)?.let { 1000f / it.trueTypeFont.header.unitsPerEm } ?: 1f
            else -> 1f
        }
        val m = Affine.mul(Affine.mul(Affine.scale(units, units), (font as PDFont).fontMatrix), Affine.fromValues(trm))
        val pts = path.approximate(2f)
        val out = ArrayList<List<Pair<Float, Float>>>()
        var cur = ArrayList<Pair<Float, Float>>()
        var lastFraction = -1f
        var i = 0
        while (i + 2 < pts.size) {
            val fr = pts[i]; val x = pts[i + 1]; val y = pts[i + 2]
            if (fr == lastFraction && cur.isNotEmpty()) { out += cur; cur = ArrayList() }      // same fraction twice: a move
            cur += Affine.apply(m, x, y)
            lastFraction = fr
            i += 3
        }
        if (cur.isNotEmpty()) out += cur
        return out.filter { it.size >= 2 }
    }

    /**
     * Reflow editing of a whole page: every upright text block is removed and [text] (the page's text in reading
     * order, edited) is written as one flowing text in the area the blocks covered, in [style].
     */
    suspend fun reflowPage(context: Context, src: File, out: File, password: String?, page: Int, text: String, style: EditTextStyle) = edit(src, out, password) { doc ->
        val a = PageTextModel.analyze(doc, page)
        val blocks = a.model.blocks.filter { it.angle == 0f && !it.unreadable }
        require(blocks.isNotEmpty()) { "no text" }
        removeOps(doc, page, a, blocks.flatMap { it.ops.toList() }.distinct().toIntArray())
        val left = blocks.minOf { it.rect.left }; val right = blocks.maxOf { it.rect.right }
        val first = blocks.maxBy { it.rect.top }
        val rtl = blocks.count { it.rtl } * 2 > blocks.size
        writeIn(context, doc, doc.getPage(page), a.frame.toUser, null, text, style, left, right, first.firstBaseline, rtl)
    }

    // ------------------------------------------------------------------ §14d reflow editing of a page range

    /** Most pages one reflow edit takes (the whole range is analysed and rewritten in one go). */
    const val FLOW_MAX_PAGES = 30

    private fun flowSources(a: PageAnalysis, page: Int): List<FlowSrc> = a.model.blocks.filter { it.angle == 0f && !it.unreadable }.map { b ->
        FlowSrc(page, b.text, b.rect.left, b.rect.top, b.rect.right, b.rect.bottom, b.size, b.color, b.bold, b.italic, b.rtl, b.family, b.align,
            b.firstBaseline, if (b.lineCount > 1) b.lineHeight else null)
    }

    /** The flow of [pages] (clipped to the document): text in column reading order, paragraphs joined over page breaks. */
    suspend fun loadFlow(src: File, password: String?, pages: IntRange): Flow = withContext(Dispatchers.IO) {
        EditIo.load(src, password).use { doc ->
            val r = pages.first.coerceIn(0, doc.numberOfPages - 1)..pages.last.coerceIn(0, doc.numberOfPages - 1)
            require(r.last - r.first < FLOW_MAX_PAGES) { "too many pages" }
            ReflowFlow.build(r, r.associateWith { flowSources(PageTextModel.analyze(doc, it), it) })
        }
    }

    /**
     * §14d: reflow editing of a page range. The upright text of every page of [flow] (as loaded by [loadFlow]; a
     * changed document throws [StalePageException]) is removed and the edited [text] with its [runs] (bold /
     * italic / size / colour) is written back as one flow: frame after frame (the column areas in reading order,
     * page after page), so a paragraph crossing a page break stays one. Text that does not fit continues on new
     * pages inserted after the range. Returns the number of pages added.
     */
    suspend fun reflowRange(context: Context, src: File, out: File, password: String?, flow: Flow, text: String, runs: List<RunStyle>): Int = edit(src, out, password) { doc ->
        require(flow.pages.last < doc.numberOfPages) { "page range" }
        val analyses = flow.pages.associateWith { PageTextModel.analyze(doc, it) }
        if (ReflowFlow.build(flow.pages, analyses.mapValues { (p, a) -> flowSources(a, p) }).text != flow.text) throw StalePageException()
        for ((p, a) in analyses) {
            val bs = a.model.blocks.filter { it.angle == 0f && !it.unreadable }
            if (bs.isNotEmpty()) removeOps(doc, p, a, bs.flatMap { it.ops.toList() }.distinct().toIntArray())
        }
        val base = flow.base
        val arabic = arabicLoader(context, doc, base.embed)
        var rest = text.filter { it.code != 13 }      // no carriage returns
        var restRuns = RichText.normalize(runs, rest.length)
        var last = flow.pages.last
        var added = 0
        var fi = 0
        while (rest.isNotBlank()) {
            val fr: FlowFrame; val toUser: Matrix; val bottom: Float
            if (fi < flow.frames.size) {
                fr = flow.frames[fi]
                toUser = analyses.getValue(fr.page).frame.toUser
                // the last frame of the range may grow down to the bottom margin
                bottom = if (fi == flow.frames.size - 1) minOf(fr.bottom, MARGIN * 2) else fr.bottom
            } else {
                require(added < 50) { "text too long" }
                val ref = doc.getPage(last)
                val np = PDPage(ref.mediaBox).also { it.cropBox = ref.cropBox; it.rotation = ref.rotation }
                doc.pages.insertAfter(np, ref)
                last++; added++
                val a = PageTextModel.analyze(doc, last)
                val m = MARGIN * 3
                fr = FlowFrame(last, m, a.frame.width - m, a.frame.height - m - base.size, m)
                toUser = a.frame.toUser
                bottom = m
            }
            fi++
            val st = base.copy(runs = restRuns)
            val width = fr.right - fr.left
            val lines = RichWriter.lines(rest, st, width, flow.rtl, null, arabic, customLoader(context, doc, st))
            val k = ReflowFlow.fit(lines.map { it.maxSize }, lines.map { it.lastOfPara }, st, fr.firstBaseline - bottom - base.size * 0.2f)
            var (headEnd, tailStart) = ReflowFlow.cut(rest, lines.map { it.logical }, k)
            if (tailStart <= 0) { headEnd = rest.length; tailStart = rest.length }       // never loop without progress
            writeIn(context, doc, doc.getPage(fr.page), toUser, null, rest.substring(0, headEnd), base.copy(runs = ReflowFlow.slice(restRuns, 0, headEnd)),
                fr.left, fr.right, fr.firstBaseline, flow.rtl)
            restRuns = ReflowFlow.slice(restRuns, tailStart, rest.length)
            rest = rest.substring(tailStart)
        }
        added
    }
}
