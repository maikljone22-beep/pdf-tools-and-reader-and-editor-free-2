package com.smartdocs.editor.core.forms

import kotlin.math.max
import kotlin.math.min

/** Text widths for layout ([ApproxMetrics] in tests; the PDF renderer measures with its real fonts). */
interface XfaMetrics { fun width(text: String, size: Float, bold: Boolean): Float }

object ApproxMetrics : XfaMetrics {
    override fun width(text: String, size: Float, bold: Boolean): Float = text.sumOf { c ->
        when {
            c == ' ' -> 0.28; c.isDigit() -> 0.556; c.isUpperCase() -> 0.68; c.code in 0x0600..0x06FF -> 0.48; else -> 0.52
        }
    }.toFloat() * size * (if (bold) 1.06f else 1f)
}

enum class XWKind { TEXT, CHECK, RADIO, COMBO, LIST, SIGNATURE }

/** Output primitives in page space: points, origin top-left, y down. Colours 0xAARRGGBB. */
sealed class XItem {
    data class Box(val x: Float, val y: Float, val w: Float, val h: Float, val edges: List<XEdge?>, val fill: Int?, val radius: Float = 0f) : XItem()
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val edge: XEdge) : XItem()
    data class Ellipse(val x: Float, val y: Float, val w: Float, val h: Float, val edge: XEdge?, val fill: Int?) : XItem()
    data class Text(val x: Float, val y: Float, val w: Float, val h: Float, val lines: List<String>, val font: XFont, val hAlign: String, val vAlign: String, val rtl: Boolean) : XItem()
    data class Image(val x: Float, val y: Float, val w: Float, val h: Float, val data: ByteArray, val aspect: String) : XItem()
    data class Widget(
        val x: Float, val y: Float, val w: Float, val h: Float, val kind: XWKind, val dataPath: String?, val label: String, val value: String,
        val options: List<String> = emptyList(), val exports: List<String> = emptyList(), val onValue: String = "", val offValue: String = "",
        val group: String? = null, val multiline: Boolean = false, val maxChars: Int = 0, val comb: Int = 0, val fontSize: Float = 10f,
        val hAlign: String = "left", val readOnly: Boolean = false, val required: Boolean = false, val password: Boolean = false,
        val type: XfaSupport.XType = XfaSupport.XType.TEXT, val editable: Boolean = false, val round: Boolean = false,
        val picture: String = "", val editPicture: String = "", val validatePicture: String = "", val locale: String = "",
    ) : XItem()
    data class AddRow(val x: Float, val y: Float, val w: Float, val h: Float, val repeat: XRepeatInfo) : XItem()
}

class XPageOut(val index: Int, val w: Float, val h: Float, val areaTop: Float, val areaBottom: Float) { val items = ArrayList<XItem>() }

class XLayoutResult(val pages: List<XPageOut>) {
    val widgets: List<Pair<Int, XItem.Widget>> get() = pages.flatMap { p -> p.items.filterIsInstance<XItem.Widget>().map { p.index to it } }
}

/**
 * XFA template layout: positioned subforms (x / y / anchorType), flowed subforms (tb, lr-tb, rl-tb, table / row),
 * captions (left / right / top / bottom with reserve), borders, fills, margins, fonts, draws (text, lines,
 * rectangles, arcs, images), page areas and content areas, page breaks (breakBefore / breakAfter / break) and
 * overflow of flowed content to the next content area, presence hidden / invisible / inactive.
 */
class XfaLayout(private val t: XTemplate, private val metrics: XfaMetrics = ApproxMetrics) {

    private class LBox(val inst: XInst?, val w: Float, val h: Float) {
        var dx = 0f
        var flow: List<LBox>? = null
        var fixed: List<Triple<Float, Float, LBox>>? = null
        var padTop = 0f; var padBottom = 0f
        var paint: ((MutableList<XItem>, Float, Float) -> Unit)? = null
        var decor: ((MutableList<XItem>, Float, Float, Float) -> Unit)? = null   // container border per fragment (x, y, height)
        var splittable = false
        var invisible = false
        var breakBefore: XBreak? = null; var breakAfter: XBreak? = null
    }

    private fun lineH(f: XFont) = f.size * 1.2f
    private fun isRtl(s: String) = s.any { it.code in 0x0590..0x08FF }

    fun wrap(text: String, maxW: Float, f: XFont): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            if (maxW <= 0f) { out += para; continue }
            var line = StringBuilder()
            for (word in para.split(' ')) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && metrics.width(cand, f.size, f.bold) > maxW) { out += line.toString(); line = StringBuilder(word) } else line = StringBuilder(cand)
            }
            out += line.toString()
        }
        return out
    }

    // ------------------------------------------------------------------ measuring

    private fun measure(i: XInst, availW: Float, stretch: Boolean, inherited: XFont, invisible: Boolean): LBox? {
        if (i.hidden) return null
        val inv = invisible || i.invisible
        val b = when (val n = i.node) {
            is XFieldT -> field(i, n, availW, stretch, n.font ?: inherited)
            is XDraw -> draw(i, n, availW, stretch, n.font ?: inherited)
            is XSub -> sub(i, n, availW, stretch, n.font ?: inherited, inv)
        }
        b.invisible = inv
        return b
    }

    private fun capSize(c: XCaption?, f: XFont): Pair<Float, Float> {
        if (c == null || c.hidden || (c.text.isBlank() && c.reserve == null)) return 0f to 0f
        val cf = c.font ?: f
        val lines = c.text.split('\n')
        return (lines.maxOf { metrics.width(it, cf.size, cf.bold) } + 4f) to (lines.size * lineH(cf))
    }

    private fun field(i: XInst, n: XFieldT, availW: Float, stretch: Boolean, f: XFont): LBox {
        val (cw, ch) = capSize(n.caption, f)
        val place = n.caption?.placement ?: "left"
        val side = place == "left" || place == "right" || place == "inline"
        val contentH = if (n.ui == XUi.CHECK) max(n.checkSize, lineH(f)) else lineH(f) + 2f
        val natW = (if (side) (n.caption?.reserve ?: cw) else 0f) + (if (n.ui == XUi.CHECK) n.checkSize + 4f else 120f) + n.margin.left + n.margin.right
        val w = n.box.w ?: if (stretch) availW else max(n.box.minW ?: 0f, if (side) natW else max(natW, cw))
        val natH = n.margin.top + n.margin.bottom + n.uiMargin.top + n.uiMargin.bottom +
            if (side) max(contentH, ch) else contentH + (n.caption?.reserve ?: ch)
        val h = n.box.h ?: max(n.box.minH ?: 0f, natH).let { v -> n.box.maxH?.let { min(v, it) } ?: v }
        return LBox(i, w, h).apply { paint = { out, x, y -> paintField(out, i, n, f, x, y, w, h) } }
    }

    private fun draw(i: XInst, n: XDraw, availW: Float, stretch: Boolean, f: XFont): LBox {
        val c = n.content
        val text = (c as? XContent.Text)?.text ?: ""
        val mw = n.margin.left + n.margin.right; val mh = n.margin.top + n.margin.bottom
        val w = n.box.w ?: when {
            c is XContent.Text -> if (stretch) availW else min(availW.coerceAtLeast(1f), text.split('\n').maxOf { metrics.width(it, f.size, f.bold) } + mw + 2f).let { max(it, n.box.minW ?: 0f) }
            else -> n.box.minW ?: 0f
        }
        val h = n.box.h ?: when (c) {
            is XContent.Text -> max(n.box.minH ?: 0f, wrap(text, w - mw, f).size * lineH(f) + mh + (n.para?.spaceAbove ?: 0f) + (n.para?.spaceBelow ?: 0f))
            else -> n.box.minH ?: 0f
        }
        return LBox(i, w, h).apply {
            paint = { out, x, y ->
                n.border?.takeIf { it.visible }?.let { out += XItem.Box(x, y, w, h, it.edges, it.fill, it.radius) }
                val ix = x + n.margin.left; val iy = y + n.margin.top; val iw = w - mw; val ih = h - mh
                when (c) {
                    is XContent.Text -> if (text.isNotBlank()) {
                        val rtl = isRtl(text)
                        out += XItem.Text(ix, iy + (n.para?.spaceAbove ?: 0f), iw, ih, wrap(text, iw, f), f,
                            n.para?.hAlign ?: if (rtl) "right" else "left", n.para?.vAlign ?: "top", rtl)
                    }
                    is XContent.Line -> c.edge?.let { e ->
                        out += when {
                            ih <= 0.5f -> XItem.Line(ix, iy, ix + iw, iy, e)
                            iw <= 0.5f -> XItem.Line(ix, iy, ix, iy + ih, e)
                            c.slope == "/" -> XItem.Line(ix, iy + ih, ix + iw, iy, e)
                            else -> XItem.Line(ix, iy, ix + iw, iy + ih, e)
                        }
                    }
                    is XContent.Rect -> out += XItem.Box(ix, iy, iw, ih, c.border.edges, c.border.fill, c.border.radius)
                    is XContent.Arc -> out += XItem.Ellipse(ix, iy, iw, ih, c.edge, c.fill)
                    is XContent.Image -> c.data?.let { out += XItem.Image(ix, iy, iw, ih, it, c.aspect) }
                    null -> {}
                }
            }
        }
    }

    private fun anchor(x: Float, y: Float, w: Float, h: Float, a: String): Pair<Float, Float> {
        val ax = when { a.endsWith("Center") -> x - w / 2f; a.endsWith("Right") -> x - w; else -> x }
        val ay = when { a.startsWith("middle") -> y - h / 2f; a.startsWith("bottom") -> y - h; else -> y }
        return ax to ay
    }

    private fun sub(i: XInst, n: XSub, availW: Float, stretch: Boolean, f: XFont, inv: Boolean): LBox {
        val m = n.margin
        val layout = n.layout
        val w0 = n.box.w ?: if (layout == "position" && !stretch) null else availW
        val inner = (w0 ?: availW) - m.left - m.right
        val kids = i.children
        val box: LBox
        when (layout) {
            "tb", "table" -> {
                val rows = kids.mapNotNull { measure(it, inner, true, f, inv) }
                val w = w0 ?: availW
                val content = rows.sumOf { it.h.toDouble() }.toFloat()
                box = LBox(i, w, n.box.h ?: max(n.box.minH ?: 0f, content + m.top + m.bottom))
                if (layout == "table" && n.columnWidths.isNotEmpty()) rows.forEach { r -> r.dx = 0f }
                box.flow = rows; box.padTop = m.top; box.padBottom = m.bottom
                rows.forEach { it.dx += m.left }
            }
            "lr-tb", "rl-tb", "row" -> {
                val rtl = layout == "rl-tb"
                val cols = (i.parent?.node as? XSub)?.columnWidths.orEmpty()
                val measured = kids.mapIndexedNotNull { k, c ->
                    val cw = if (layout == "row") cols.getOrNull(k)?.takeIf { it > 0f } else null
                    if (cw != null) measureFixedW(c, cw, f, inv) else measure(c, inner, false, f, inv)
                }
                val rows = ArrayList<LBox>()
                var cur = ArrayList<LBox>(); var x = 0f
                fun flush() {
                    if (cur.isEmpty()) return
                    val rh = cur.maxOf { it.h }
                    val row = LBox(null, inner, rh)
                    var cx = 0f
                    row.fixed = cur.map { c -> val px = if (rtl) inner - cx - c.w else cx; cx += c.w; Triple(px, 0f, c) }
                    row.dx = m.left
                    rows += row; cur = ArrayList(); x = 0f
                }
                for (c in measured) {
                    if (layout != "row" && cur.isNotEmpty() && x + c.w > inner + 0.5f) flush()
                    cur += c; x += c.w
                }
                flush()
                val w = w0 ?: availW
                val content = rows.sumOf { it.h.toDouble() }.toFloat()
                box = LBox(i, w, n.box.h ?: max(n.box.minH ?: 0f, content + m.top + m.bottom))
                box.flow = rows; box.padTop = m.top; box.padBottom = m.bottom
            }
            else -> {      // position
                val placed = kids.mapNotNull { c ->
                    val lb = measure(c, inner, false, f, inv) ?: return@mapNotNull null
                    val g = c.node.box
                    val (ax, ay) = anchor(g.x, g.y, lb.w, lb.h, g.anchor)
                    Triple(ax + m.left, ay + m.top, lb)
                }
                val w = w0 ?: max(n.box.minW ?: 0f, (placed.maxOfOrNull { it.first + it.third.w } ?: 0f) + m.right)
                val h = n.box.h ?: max(n.box.minH ?: 0f, (placed.maxOfOrNull { it.second + it.third.h } ?: 0f) + m.bottom)
                box = LBox(i, w, h)
                box.fixed = placed
            }
        }
        box.splittable = box.flow != null && !n.keepIntact && n.box.h == null
        box.breakBefore = n.breakBefore; box.breakAfter = n.breakAfter
        n.border?.takeIf { it.visible }?.let { bd -> box.decor = { out, x, y, h -> out += XItem.Box(x, y, box.w, h, bd.edges, bd.fill, bd.radius) } }
        return box
    }

    private fun measureFixedW(c: XInst, w: Float, f: XFont, inv: Boolean): LBox? {
        val b = measure(c, w, true, f, inv) ?: return null
        if (b.w == w) return b
        // a cell takes its column width
        return LBox(b.inst, w, b.h).also { nb -> nb.paint = b.paint?.let { p -> { out, x, y -> p(out, x, y) } }; nb.fixed = b.fixed; nb.flow = b.flow; nb.decor = b.decor; nb.invisible = b.invisible }
    }

    // ------------------------------------------------------------------ fields

    private fun paintField(out: MutableList<XItem>, i: XInst, n: XFieldT, f: XFont, x: Float, y: Float, w: Float, h: Float) {
        n.border?.takeIf { it.visible }?.let { out += XItem.Box(x, y, w, h, it.edges, it.fill, it.radius) }
        var cx = x + n.margin.left; var cy = y + n.margin.top
        var cw = w - n.margin.left - n.margin.right; var ch = h - n.margin.top - n.margin.bottom
        val cap = n.caption?.takeIf { !it.hidden && (it.text.isNotBlank() || it.reserve != null) }
        val label = cap?.text?.takeIf { it.isNotBlank() } ?: n.tooltip.ifBlank { n.name }
        if (cap != null) {
            val cf = cap.font ?: f
            val (mw, mh) = capSize(cap, f)
            val rtl = isRtl(cap.text)
            val side = cap.placement == "left" || cap.placement == "right" || cap.placement == "inline"
            val r = cap.reserve ?: if (side) mw else mh
            val (rx, ry, rw, rh) = when (cap.placement) {
                "right" -> floatArrayOf(cx + cw - r, cy, r, ch).also { cw -= r }
                "top" -> floatArrayOf(cx, cy, cw, r).also { cy += r; ch -= r }
                "bottom" -> floatArrayOf(cx, cy + ch - r, cw, r).also { ch -= r }
                else -> floatArrayOf(cx, cy, r, ch).also { cx += r; cw -= r }
            }.let { Quad(it[0], it[1], it[2], it[3]) }
            if (cap.text.isNotBlank()) out += XItem.Text(rx, ry, rw, rh, wrap(cap.text, rw, cf), cf,
                cap.para?.hAlign ?: if (rtl) "right" else "left", cap.para?.vAlign ?: if (side) "middle" else "top", rtl)
        }
        n.uiBorder?.takeIf { it.visible && n.ui != XUi.CHECK }?.let { out += XItem.Box(cx, cy, cw, ch, it.edges, it.fill, it.radius) }
        cx += n.uiMargin.left; cy += n.uiMargin.top; cw -= n.uiMargin.left + n.uiMargin.right; ch -= n.uiMargin.top + n.uiMargin.bottom
        if (cw < 1f || ch < 1f) return
        val hAlign = n.para?.hAlign ?: if (isRtl(i.value)) "right" else "left"
        val group = i.parent?.takeIf { (it.node as? XSub)?.isExclGroup == true }
        val type = when (n.ui) {
            XUi.NUMERIC -> XfaSupport.XType.NUMBER; XUi.DATE -> XfaSupport.XType.DATE; XUi.PASSWORD -> XfaSupport.XType.PASSWORD
            XUi.CHECK -> if (group != null) XfaSupport.XType.RADIO else XfaSupport.XType.CHECK; XUi.CHOICE -> XfaSupport.XType.CHOICE
            XUi.SIGNATURE -> XfaSupport.XType.SIGNATURE; else -> XfaSupport.XType.TEXT
        }
        when (n.ui) {
            XUi.CHECK -> {
                val s = min(n.checkSize, min(cw, ch))
                val bx = when (n.para?.hAlign) { "center" -> cx + (cw - s) / 2f; "right" -> cx + cw - s; else -> cx }
                val by = cy + (ch - s) / 2f
                n.uiBorder?.takeIf { it.visible }?.let { out += XItem.Box(bx, by, s, s, it.edges, it.fill) }
                    ?: run { out += XItem.Box(bx, by, s, s, List(4) { XEdge(0xFF000000.toInt(), 0.5f) }, null) }
                out += if (group != null) XItem.Widget(bx, by, s, s, XWKind.RADIO, group.dataPath, group.name.ifBlank { label }, group.value,
                    onValue = i.value, group = group.som, readOnly = n.readOnly, type = type, round = true, required = (group.node as XSub).required)
                else XItem.Widget(bx, by, s, s, XWKind.CHECK, i.dataPath, label, i.value, onValue = n.saveItems.getOrNull(0) ?: n.items.getOrNull(0) ?: "1",
                    offValue = n.saveItems.getOrNull(1) ?: n.items.getOrNull(1) ?: "0", readOnly = n.readOnly, type = type, round = n.roundCheck, required = n.required)
            }
            XUi.CHOICE -> out += XItem.Widget(cx, cy, cw, ch, if (n.listOpen == "always" || n.listOpen == "multiSelect") XWKind.LIST else XWKind.COMBO,
                i.dataPath, label, i.value, n.items, n.saveItems.ifEmpty { n.items }, fontSize = f.size, hAlign = hAlign, readOnly = n.readOnly,
                required = n.required, type = type, editable = n.textEntry)
            XUi.SIGNATURE -> out += XItem.Widget(cx, cy, cw, ch, XWKind.SIGNATURE, i.dataPath, label, "", type = type)
            XUi.BUTTON -> {
                out += XItem.Box(cx, cy, cw, ch, List(4) { XEdge(0xFF7F7F7F.toInt(), 0.75f) }, 0xFFD4D0C8.toInt(), 2f)
                if (label.isNotBlank()) out += XItem.Text(cx, cy, cw, ch, wrap(label, cw, f), f, "center", "middle", isRtl(label))
            }
            XUi.IMAGE -> (i.image ?: n.image)?.let { out += XItem.Image(cx, cy, cw, ch, it, "fit") }
            // nav-prefs: QR / Code 128 / Code 39 barcodes are drawn; other symbologies show their value
            XUi.BARCODE -> if (i.value.isNotBlank()) out += (NpfBarcode.boxes(n.barcodeType, i.value, cx, cy, cw, ch)
                ?: listOf(XItem.Text(cx, cy, cw, ch, listOf(i.value), f, "center", "middle", false)))
            else -> out += XItem.Widget(cx, cy, cw, ch, XWKind.TEXT, i.dataPath, label, i.value, multiline = n.multiline, maxChars = n.maxChars,
                comb = n.combCells, fontSize = f.size, hAlign = hAlign, readOnly = n.readOnly, required = n.required, password = n.ui == XUi.PASSWORD, type = type,
                picture = n.picture, editPicture = n.editPicture, validatePicture = n.validatePicture, locale = n.locale)
        }
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    // ------------------------------------------------------------------ pagination

    private val pages = ArrayList<XPageOut>()
    private var pa = 0; private var paUses = 0; private var ca = 0
    private var y = 0f
    private var fresh = true

    private fun area(): XArea = t.pages[pa].areas[ca]
    private val bottom: Float get() = area().y + area().h
    private val page: XPageOut get() = pages.last()

    private fun startPage(target: String?) {
        if (target != null) {
            val idx = t.pages.indexOfFirst { it.name == target || it.id == target }
            if (idx >= 0 && idx != pa) { pa = idx; paUses = 0 }
        } else if (pages.isNotEmpty()) {
            val cur = t.pages[pa]
            if (cur.occurMax in 1..paUses && pa + 1 < t.pages.size) { pa++; paUses = 0 }
        }
        if (fresh && pages.isNotEmpty() && page.items.size == masterCount) pages.removeAt(pages.size - 1)
        paUses++; ca = 0
        val pg = t.pages[pa]
        pages += XPageOut(pages.size, pg.w, pg.h, area().y, area().y + area().h)
        y = area().y; fresh = true
        // master page content (backgrounds, headers, static text)
        val f = XFont()
        for (c in pg.content) {
            val inst = XInst(c, null, "master." + c.name).also { it.value = (c as? XFieldT)?.defaultValue.orEmpty() }
            val lb = (if (c is XFieldT) draw(inst, XDraw(c.name, c.box, c.presence, c.margin, c.border, c.font, c.para, c.scripts,
                XContent.Text(inst.value.ifBlank { c.caption?.text.orEmpty() }), null), pg.w, false, c.font ?: f) else measure(inst, pg.w, false, f, false)) ?: continue
            val (ax, ay) = anchor(c.box.x, c.box.y, lb.w, lb.h, c.box.anchor)
            paint(lb, ax, ay, page.items)
        }
        masterCount = page.items.size
    }
    private var masterCount = 0

    private fun nextArea() {
        if (pages.isNotEmpty() && ca + 1 < t.pages[pa].areas.size) { ca++; y = area().y; fresh = true } else startPage(null)
    }

    private fun xOf(dx: Float) = area().x + dx

    private fun paint(b: LBox, x: Float, y: Float, out: MutableList<XItem>) {
        if (!b.invisible) { b.decor?.invoke(out, x, y, b.h); b.paint?.invoke(out, x, y) }
        b.fixed?.forEach { (px, py, c) -> paint(c, x + px, y + py, out) }
        b.flow?.let { var cy = y + b.padTop; for (c in it) { paint(c, x + c.dx, cy, out); cy += c.h } }
        b.inst?.repeat?.let { r -> if (!b.invisible) out += XItem.AddRow(x, y, b.w, b.h, r) }
    }

    private fun applyBreak(br: XBreak?) {
        if (br == null) return
        if (br.page) startPage(br.target) else if (br.contentArea) nextArea()
    }

    /** Place [b] at the current position (relative x [dx] in the content area), splitting flowed content. */
    private fun place(b: LBox, dx: Float) {
        if (pages.isEmpty()) startPage(b.breakBefore?.target)
        else applyBreak(b.breakBefore)
        if (b.splittable && b.h > bottom - y + 0.01f) {
            var fragPage = pages.size - 1; var fragTop = y; var insertAt = page.items.size
            fun close(endY: Float) {
                if (!b.invisible) b.decor?.let { d -> val tmp = ArrayList<XItem>(); d(tmp, xOf(dx), fragTop, endY - fragTop); pages[fragPage].items.addAll(insertAt.coerceAtMost(pages[fragPage].items.size), tmp) }
            }
            y += b.padTop
            for (c in b.flow.orEmpty()) {
                val before = pages.size - 1; val beforeArea = ca
                place(c, dx + c.dx)
                if (pages.size - 1 != before || ca != beforeArea) {
                    close(pages[fragPage].areaBottom)
                    fragPage = pages.size - 1; fragTop = area().y; insertAt = masterCount
                }
            }
            y += b.padBottom
            close(y)
            b.inst?.repeat?.let { r -> if (!b.invisible) page.items += XItem.AddRow(xOf(dx), fragTop, b.w, y - fragTop, r) }
        } else {
            if (b.h > bottom - y + 0.01f && !fresh) nextArea()
            paint(b, xOf(dx), y, page.items)
            y += b.h
        }
        fresh = false
        applyBreak(b.breakAfter)
    }

    fun layout(m: XMerged): XLayoutResult {
        pages.clear(); pa = 0; paUses = 0; ca = 0; fresh = true; masterCount = 0
        val rootNode = m.root.node as XSub
        val f = rootNode.font ?: XFont()
        val firstW = t.pages.first().areas.first().w
        // the root's children flow into the content areas; a positioned root is one fixed block
        if (rootNode.layout == "position") measure(m.root, firstW, false, f, false)?.let { place(it, 0f) }
        else for (c in m.root.children) measure(c, firstW - rootNode.margin.left - rootNode.margin.right, true, f, false)?.let { place(it, rootNode.margin.left) }
        if (pages.isEmpty()) startPage(null)
        return XLayoutResult(pages.toList())
    }
}
