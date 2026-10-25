package com.smartdocs.editor.core.office

import org.xmlpull.v1.XmlPullParser

/**
 * WordprocessingML (.docx) reader: document.xml + styles/numbering/rels/media into a [DocModel].
 * Handles runs (b/i/u/strike/color/size/font/highlight), alignment, bidi, headings, lists,
 * tables (gridSpan, vMerge), inline/anchored pictures, VML images, hyperlinks, page/section breaks,
 * content controls, inserted revisions and text boxes (emitted after their paragraph).
 */
internal class DocxReader(private val pkg: OoxmlPackage) {
    private val docPath: String = pkg.rels("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target ?: "word/document.xml"

    private class StyleInfo(val heading: Int, val basedOn: String?, val align: Align?, val rtl: Boolean?, val run: RunStyle?, val numId: String?, val ilvl: Int)
    private val styles = HashMap<String, StyleInfo>()
    private var defaultRun = RunStyle()

    private class LevelDef(val fmt: String, val text: String, val start: Int)
    private val abstractLevels = HashMap<String, MutableMap<Int, LevelDef>>()
    private val numToAbstract = HashMap<String, String>()
    private val counters = HashMap<String, IntArray>()

    private var bodyIndex = 0
    private var pendingBlocks = ArrayList<Block>()

    fun read(): DocModel {
        loadStyles(); loadNumbering()
        val p = pkg.parser(docPath) ?: throw OfficeFormatException("word/document.xml missing")
        if (!p.seek("body")) return DocModel(emptyList())
        val blocks = readBlocks(p, topLevel = true)
        return DocModel(blocks, readTitle())
    }

    private fun readTitle(): String? {
        val p = pkg.parser("docProps/core.xml") ?: return null
        return if (p.seek("title")) p.readText().trim().ifEmpty { null } else null
    }

    // ---------------- blocks ----------------

    private fun readBlocks(p: XmlPullParser, topLevel: Boolean): List<Block> {
        val out = ArrayList<Block>()
        p.forEachChild { n ->
            when (n) {
                "p" -> {
                    val idx = if (topLevel) bodyIndex++ else -1
                    readParagraph(p, idx, out)
                    if (pendingBlocks.isNotEmpty()) { out.addAll(pendingBlocks); pendingBlocks = ArrayList() }
                }
                "tbl" -> out.add(readTable(p))
                // paragraphs nested in containers are not direct body children, so they are not editable by index
                "sdt" -> p.forEachChild { c -> if (c == "sdtContent") out.addAll(readBlocks(p, false)) }
                "customXml", "ins", "smartTag" -> out.addAll(readBlocks(p, false))
                "AlternateContent" -> p.forEachChild { c -> if (c == "Choice") out.addAll(readBlocks(p, false)) }
            }
        }
        return out
    }

    private fun readParagraph(p: XmlPullParser, idx: Int, out: MutableList<Block>) {
        var align: Align? = null; var rtl: Boolean? = null; var styleId: String? = null
        var numId: String? = null; var ilvl = 0; var pageBreakBefore = false; var section = false
        var indent = 0; var shading: String? = null
        val inlines = ArrayList<Inline>()
        p.forEachChild { n ->
            when (n) {
                "pPr" -> p.forEachChild { c ->
                    when (c) {
                        "pStyle" -> styleId = p.attr("val")
                        "jc" -> align = jc(p.attr("val"))
                        "bidi" -> rtl = p.onOff()
                        "numPr" -> p.forEachChild { k -> if (k == "numId") numId = p.attr("val"); if (k == "ilvl") ilvl = p.attr("val")?.toIntOrNull() ?: 0 }
                        "pageBreakBefore" -> pageBreakBefore = p.onOff()
                        "sectPr" -> section = true
                        "ind" -> indent = (p.attr("start") ?: p.attr("left") ?: p.attr("right"))?.toIntOrNull() ?: 0
                        "shd" -> shading = parseColor(p.attr("fill"))
                    }
                }
                else -> readInlineContainer(p, n, inlines, null)
            }
        }
        val st = styleId?.let { styleChain(it) } ?: emptyList()
        val heading = st.firstOrNull { it.heading != 0 }?.heading ?: 0
        val effAlign = align ?: st.firstNotNullOfOrNull { it.align }
        val effRtl = rtl ?: st.firstNotNullOfOrNull { it.rtl } ?: false
        if (numId == null) st.firstOrNull { it.numId != null }?.let { numId = it.numId; ilvl = it.ilvl }
        val marker = numId?.takeIf { it != "0" }?.let { listMarker(it, ilvl) }
        // apply paragraph style run props as a base for runs without explicit formatting
        val base = st.asReversed().fold(defaultRun) { acc, si -> si.run?.let { overlay(acc, it) } ?: acc }
        val styled = inlines.map { if (it is Inline.Text) it.copy(style = overlay(base, it.style)) else it }
        if (pageBreakBefore) out.add(Block.PageBreak)
        // split paragraphs on hard page breaks so the renderer can paginate
        out.add(Block.Para(Paragraph(styled, effAlign, effRtl, heading, if (marker != null) ilvl else -1, marker, false, idx, indent, shading)))
        if (section) out.add(Block.SectionBreak)
    }

    /** Reads inline content: runs, hyperlinks, fields, insertions, content controls. */
    private fun readInlineContainer(p: XmlPullParser, n: String, out: MutableList<Inline>, link: String?) {
        when (n) {
            "r" -> readRun(p, out, link)
            "hyperlink" -> {
                val rid = p.rAttr("id")
                val target = rid?.let { pkg.rels(docPath)[it]?.target } ?: p.attr("anchor")?.let { "#$it" }
                p.forEachChild { c -> readInlineContainer(p, c, out, target ?: link) }
            }
            "fldSimple", "ins", "smartTag", "customXml", "bdo", "dir", "moveTo" -> p.forEachChild { c -> readInlineContainer(p, c, out, link) }
            "sdt" -> p.forEachChild { c -> if (c == "sdtContent") p.forEachChild { k -> readInlineContainer(p, k, out, link) } }
            "AlternateContent" -> p.forEachChild { c -> if (c == "Choice") p.forEachChild { k -> readInlineContainer(p, k, out, link) } }
        }
    }

    private fun readRun(p: XmlPullParser, out: MutableList<Inline>, link: String?) {
        var style = RunStyle()
        p.forEachChild { n ->
            when (n) {
                "rPr" -> style = readRunProps(p, null)
                "t" -> out.add(Inline.Text(p.readText(), style, link))
                "tab", "ptab" -> out.add(Inline.Tab)
                "br" -> out.add(if (p.attr("type") == "page") Inline.PageBreak else Inline.LineBreak)
                "cr" -> out.add(Inline.LineBreak)
                "noBreakHyphen" -> out.add(Inline.Text("‑", style, link))
                "sym" -> p.attr("char")?.toIntOrNull(16)?.let { out.add(Inline.Text(symbolChar(it), style, link)) }
                "drawing" -> readDrawing(p, out)
                "pict", "object" -> readVml(p, out)
                "AlternateContent" -> p.forEachChild { c ->
                    if (c == "Choice") p.forEachChild { k -> if (k == "drawing") readDrawing(p, out) }
                }
            }
        }
    }

    private fun symbolChar(code: Int): String = when (code) {
        0xF0B7, 0xF0A7, 0xF076, 0xF0D8 -> "•"; 0xF0FC -> "✓"; 0xF0E0 -> "→"
        else -> if (code in 0xF000..0xF0FF) String(Character.toChars(code - 0xF000)) else String(Character.toChars(code))
    }

    private fun readDrawing(p: XmlPullParser, out: MutableList<Inline>) {
        var cx = 0L; var cy = 0L; var embed: String? = null; var alt: String? = null
        val d = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.depth == d)) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "extent" -> if (cx == 0L) { cx = p.attr("cx")?.toLongOrNull() ?: 0; cy = p.attr("cy")?.toLongOrNull() ?: 0 }
                "docPr" -> alt = p.attr("descr") ?: p.attr("name")
                "blip" -> if (embed == null) embed = p.rAttr("embed")
                "txbxContent" -> pendingBlocks.addAll(readBlocks(p, topLevel = false))
            }
        }
        if (embed != null) out.add(Inline.Image(pkg.image(docPath, embed), cx, cy, alt))
    }

    private fun readVml(p: XmlPullParser, out: MutableList<Inline>) {
        var rid: String? = null; var w = 0L; var h = 0L
        val d = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.depth == d)) break
            if (ev != XmlPullParser.START_TAG) continue
            when (p.name) {
                "shape", "rect" -> p.attr("style")?.let { s -> w = cssLenEmu(s, "width"); h = cssLenEmu(s, "height") }
                "imagedata" -> if (rid == null) rid = p.rAttr("id")
                "txbxContent" -> pendingBlocks.addAll(readBlocks(p, topLevel = false))
            }
        }
        if (rid != null) out.add(Inline.Image(pkg.image(docPath, rid), w, h))
    }

    private fun cssLenEmu(style: String, key: String): Long {
        val m = Regex("""(?:^|;)\s*$key\s*:\s*([\d.]+)(pt|in|px|cm|mm)?""").find(style) ?: return 0
        val v = m.groupValues[1].toDoubleOrNull() ?: return 0
        return (v * when (m.groupValues[2]) { "in" -> 914400.0; "cm" -> 360000.0; "mm" -> 36000.0; "px" -> 9525.0; else -> 12700.0 }).toLong()
    }

    private fun readRunProps(p: XmlPullParser, base: RunStyle?): RunStyle {
        var s = base ?: RunStyle()
        p.forEachChild { n ->
            s = when (n) {
                "b", "bCs" -> s.copy(bold = p.onOff())
                "i", "iCs" -> s.copy(italic = p.onOff())
                "u" -> s.copy(underline = p.attr("val").let { it != null && it != "none" })
                "strike", "dstrike" -> s.copy(strike = p.onOff())
                "color" -> s.copy(color = parseColor(p.attr("val")))
                "sz", "szCs" -> p.attr("val")?.toFloatOrNull()?.let { s.copy(sizePt = it / 2f) } ?: s
                "rFonts" -> s.copy(font = p.attr("cs") ?: p.attr("ascii") ?: p.attr("hAnsi") ?: s.font)
                "highlight" -> s.copy(background = highlight(p.attr("val")))
                "shd" -> parseColor(p.attr("fill"))?.let { s.copy(background = it) } ?: s
                "vertAlign" -> s.copy(vertAlign = p.attr("val")?.takeIf { it != "baseline" })
                "rtl" -> s.copy(rtl = p.onOff())
                else -> s
            }
        }
        return s
    }

    /** [r]'s explicit properties layered over [base]. */
    private fun overlay(base: RunStyle, r: RunStyle): RunStyle = RunStyle(
        bold = r.bold || base.bold, italic = r.italic || base.italic, underline = r.underline || base.underline,
        strike = r.strike || base.strike, color = r.color ?: base.color, background = r.background ?: base.background,
        sizePt = r.sizePt ?: base.sizePt, font = r.font ?: base.font, vertAlign = r.vertAlign ?: base.vertAlign, rtl = r.rtl || base.rtl,
    )

    private fun highlight(v: String?): String? = when (v) {
        "yellow" -> "FFFF00"; "green" -> "00FF00"; "cyan" -> "00FFFF"; "magenta" -> "FF00FF"; "blue" -> "0000FF"; "red" -> "FF0000"
        "darkBlue" -> "000080"; "darkCyan" -> "008080"; "darkGreen" -> "008000"; "darkMagenta" -> "800080"; "darkRed" -> "800000"
        "darkYellow" -> "808000"; "darkGray" -> "808080"; "lightGray" -> "C0C0C0"; "black" -> "000000"; "white" -> "FFFFFF"
        else -> null
    }

    private fun jc(v: String?): Align? = when (v) {
        "center" -> Align.CENTER; "both", "distribute", "justify" -> Align.JUSTIFY
        "start" -> Align.START; "end" -> Align.END; "left" -> Align.LEFT; "right" -> Align.RIGHT
        else -> null
    }

    // ---------------- tables ----------------

    private fun readTable(p: XmlPullParser): Block.Table {
        var rtl = false
        val grid = ArrayList<Int>()
        val rows = ArrayList<TableRow>()
        // per row: list of (gridCol, vMerge state)
        val vm = ArrayList<List<Pair<Int, String?>>>()
        p.forEachChild { n ->
            when (n) {
                "tblPr" -> p.forEachChild { c -> if (c == "bidiVisual") rtl = p.onOff() }
                "tblGrid" -> p.forEachChild { c -> if (c == "gridCol") grid.add(p.attr("w")?.toIntOrNull() ?: 0) }
                "tr" -> {
                    val cells = ArrayList<TableCell>(); val states = ArrayList<Pair<Int, String?>>()
                    var header = false; var col = 0
                    p.forEachChild { c ->
                        when (c) {
                            "trPr" -> p.forEachChild { k -> if (k == "tblHeader") header = p.onOff() }
                            "tc" -> {
                                var span = 1; var vMerge: String? = null; var shd: String? = null; var w = 0
                                val blocks = ArrayList<Block>()
                                p.forEachChild { k ->
                                    when (k) {
                                        "tcPr" -> p.forEachChild { q ->
                                            when (q) {
                                                "gridSpan" -> span = p.attr("val")?.toIntOrNull() ?: 1
                                                "vMerge" -> vMerge = p.attr("val") ?: "continue"
                                                "shd" -> shd = parseColor(p.attr("fill"))
                                                "tcW" -> w = p.attr("w")?.toIntOrNull() ?: 0
                                            }
                                        }
                                        "p" -> readParagraph(p, -1, blocks).also { if (pendingBlocks.isNotEmpty()) { blocks.addAll(pendingBlocks); pendingBlocks = ArrayList() } }
                                        "tbl" -> blocks.add(readTable(p))
                                        "sdt" -> p.forEachChild { q -> if (q == "sdtContent") blocks.addAll(readBlocks(p, false)) }
                                    }
                                }
                                cells.add(TableCell(blocks, span, 1, vMerge == "continue", shd, w))
                                states.add(col to vMerge); col += span
                            }
                        }
                    }
                    rows.add(TableRow(cells, header)); vm.add(states)
                }
            }
        }
        // resolve vertical merges into rowSpan on the restart cell
        for (r in rows.indices) for ((ci, st) in vm[r].withIndex()) {
            if (st.second != "restart") continue
            var span = 1
            var rr = r + 1
            while (rr < rows.size && vm[rr].any { it.first == st.first && it.second == "continue" }) { span++; rr++ }
            rows[r].cells[ci].rowSpan = span
        }
        return Block.Table(rows, rtl, grid)
    }

    // ---------------- styles & numbering ----------------

    private fun styleChain(id: String): List<StyleInfo> {
        val out = ArrayList<StyleInfo>(); var cur: String? = id; var guard = 0
        while (cur != null && guard++ < 10) { val s = styles[cur] ?: break; out.add(s); cur = s.basedOn }
        return out
    }

    private fun loadStyles() {
        val p = pkg.parser("word/styles.xml") ?: return
        if (!p.seek("styles")) return
        p.forEachChild { n ->
            when (n) {
                "docDefaults" -> p.forEachChild { c -> if (c == "rPrDefault") p.forEachChild { k -> if (k == "rPr") defaultRun = readRunProps(p, null).copy(bold = false, italic = false) } }
                "style" -> {
                    if (p.attr("type") != "paragraph") { p.skipElement(); return@forEachChild }
                    val id = p.attr("styleId") ?: return@forEachChild
                    var name = ""; var basedOn: String? = null; var align: Align? = null; var rtl: Boolean? = null
                    var outline = -1; var run: RunStyle? = null; var numId: String? = null; var ilvl = 0
                    p.forEachChild { c ->
                        when (c) {
                            "name" -> name = p.attr("val")?.lowercase() ?: ""
                            "basedOn" -> basedOn = p.attr("val")
                            "pPr" -> p.forEachChild { k ->
                                when (k) {
                                    "jc" -> align = jc(p.attr("val"))
                                    "bidi" -> rtl = p.onOff()
                                    "outlineLvl" -> outline = p.attr("val")?.toIntOrNull() ?: -1
                                    "numPr" -> p.forEachChild { q -> if (q == "numId") numId = p.attr("val"); if (q == "ilvl") ilvl = p.attr("val")?.toIntOrNull() ?: 0 }
                                }
                            }
                            "rPr" -> run = readRunProps(p, null)
                        }
                    }
                    val heading = when {
                        name == "title" -> -1
                        name.startsWith("heading ") -> name.removePrefix("heading ").trim().toIntOrNull()?.coerceIn(1, 6) ?: 0
                        id.matches(Regex("(?i)heading[1-6]")) -> id.last().digitToInt()
                        outline in 0..5 -> outline + 1
                        else -> 0
                    }
                    styles[id] = StyleInfo(heading, basedOn, align, rtl, run, numId, ilvl)
                }
            }
        }
    }

    private fun loadNumbering() {
        val p = pkg.parser("word/numbering.xml") ?: return
        if (!p.seek("numbering")) return
        p.forEachChild { n ->
            when (n) {
                "abstractNum" -> {
                    val id = p.attr("abstractNumId") ?: return@forEachChild
                    val levels = HashMap<Int, LevelDef>()
                    p.forEachChild { c ->
                        if (c == "lvl") {
                            val il = p.attr("ilvl")?.toIntOrNull() ?: 0
                            var fmt = "decimal"; var text = "%1."; var start = 1
                            p.forEachChild { k ->
                                when (k) { "numFmt" -> fmt = p.attr("val") ?: fmt; "lvlText" -> text = p.attr("val") ?: ""; "start" -> start = p.attr("val")?.toIntOrNull() ?: 1 }
                            }
                            levels[il] = LevelDef(fmt, text, start)
                        }
                    }
                    abstractLevels[id] = levels
                }
                "num" -> {
                    val id = p.attr("numId") ?: return@forEachChild
                    p.forEachChild { c -> if (c == "abstractNumId") p.attr("val")?.let { numToAbstract[id] = it } }
                }
            }
        }
    }

    private fun listMarker(numId: String, ilvl: Int): String {
        val levels = abstractLevels[numToAbstract[numId]] ?: return "•"
        val cnt = counters.getOrPut(numId) { IntArray(9) { -1 } }
        val lv = ilvl.coerceIn(0, 8)
        cnt[lv] = if (cnt[lv] < 0) (levels[lv]?.start ?: 1) else cnt[lv] + 1
        for (i in lv + 1 until 9) cnt[i] = -1
        val def = levels[lv] ?: return "•"
        if (def.fmt == "bullet") return bulletChar(def.text)
        if (def.fmt == "none") return ""
        var text = def.text
        for (i in 0..lv) {
            val f = levels[i]?.fmt ?: "decimal"
            val v = if (cnt[i] < 0) (levels[i]?.start ?: 1) else cnt[i]
            text = text.replace("%${i + 1}", NumberText.format(v, f))
        }
        return text
    }

    private fun bulletChar(t: String): String {
        val c = t.firstOrNull() ?: return "•"
        return when {
            c.code in 0xF000..0xF0FF || c == '·' -> when (c.code and 0xFF) { 0xA7 -> "▪"; 0xD8 -> "➢"; 0xFC -> "✓"; 0x6F -> "○"; else -> "•" }
            c == 'o' -> "○"
            else -> t
        }
    }
}

/** Number formatting for list labels. */
internal object NumberText {
    private const val ABJAD = "أبجدهوزحطيكلمنسعفصقرشتثخذضظغ"
    private const val ALPHA = "أبتثجحخدذرزسشصضطظعغفقكلمنهوي"

    fun format(n: Int, fmt: String): String = when (fmt) {
        "lowerLetter" -> letters(n, 'a'); "upperLetter" -> letters(n, 'A')
        "lowerRoman" -> roman(n).lowercase(); "upperRoman" -> roman(n)
        "arabicAbjad" -> ABJAD[(n - 1).mod(ABJAD.length)].toString()
        "arabicAlpha" -> ALPHA[(n - 1).mod(ALPHA.length)].toString()
        "hindiNumbers", "hindiCounting", "arabicNumbers" -> n.toString().map { if (it.isDigit()) '٠' + (it - '0') else it }.joinToString("")
        "decimalZero" -> if (n < 10) "0$n" else n.toString()
        else -> n.toString()
    }

    private fun letters(n: Int, base: Char): String {
        val k = (n - 1).coerceAtLeast(0)
        return (base + (k % 26)).toString().repeat(k / 26 + 1)
    }

    fun roman(n: Int): String {
        if (n <= 0) return n.toString()
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n; val sb = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }
}
