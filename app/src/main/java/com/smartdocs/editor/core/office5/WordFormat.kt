package com.smartdocs.editor.core.office5

import kotlin.math.roundToInt

/** Explicit run properties (null = not set at this level). */
data class RunProps(
    val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null, val strike: Boolean? = null,
    val sizePt: Float? = null, val color: String? = null, val highlight: String? = null, val font: String? = null,
    val vertAlign: String? = null, val rtl: Boolean? = null, val caps: Boolean? = null,
) {
    /** [o] layered over this. */
    fun over(o: RunProps?): RunProps = if (o == null) this else RunProps(
        o.bold ?: bold, o.italic ?: italic, o.underline ?: underline, o.strike ?: strike, o.sizePt ?: sizePt, o.color ?: color,
        o.highlight ?: highlight, o.font ?: font, o.vertAlign ?: vertAlign, o.rtl ?: rtl, o.caps ?: caps,
    )
}

/** Paragraph alignment in logical terms (START = right edge in an RTL paragraph). */
enum class PAlign { START, CENTER, END, JUSTIFY }

object WordFormat {
    private val HIGHLIGHTS = linkedMapOf(
        "yellow" to "FFFF00", "green" to "00FF00", "cyan" to "00FFFF", "magenta" to "FF00FF", "blue" to "0000FF", "red" to "FF0000",
        "darkBlue" to "000080", "darkCyan" to "008080", "darkGreen" to "008000", "darkMagenta" to "800080", "darkRed" to "800000",
        "darkYellow" to "808000", "darkGray" to "808080", "lightGray" to "C0C0C0", "black" to "000000", "white" to "FFFFFF",
    )
    /** Highlight colours Word supports (hex), in palette order. */
    val highlightPalette: List<String> get() = HIGHLIGHTS.values.toList()

    private fun on(e: XElem?): Boolean? = e?.let { when (it.attr("val")?.lowercase()) { null, "1", "true", "on" -> true; else -> false } }

    fun color(v: String?): String? {
        if (v == null || v.equals("auto", true)) return null
        val h = v.removePrefix("#")
        return when (h.length) { 6 -> h.uppercase(); 8 -> h.substring(2).uppercase(); else -> null }
    }

    fun read(rPr: XElem?): RunProps {
        if (rPr == null) return RunProps()
        return RunProps(
            bold = on(rPr.child("b")) ?: on(rPr.child("bCs")),
            italic = on(rPr.child("i")) ?: on(rPr.child("iCs")),
            underline = rPr.child("u")?.let { it.attr("val") != "none" },
            strike = on(rPr.child("strike")) ?: on(rPr.child("dstrike")),
            sizePt = (rPr.child("sz") ?: rPr.child("szCs"))?.attr("val")?.toFloatOrNull()?.div(2f),
            color = rPr.child("color")?.attr("val")?.let { color(it) },
            highlight = rPr.child("highlight")?.attr("val")?.let { HIGHLIGHTS[it] } ?: rPr.child("shd")?.attr("fill")?.let { color(it) },
            font = rPr.child("rFonts")?.let { it.attr("cs") ?: it.attr("ascii") ?: it.attr("hAnsi") },
            vertAlign = rPr.child("vertAlign")?.attr("val")?.takeIf { it != "baseline" },
            rtl = on(rPr.child("rtl")),
            caps = on(rPr.child("caps")),
        )
    }

    private fun pf(rPr: XElem) = rPr.prefix.let { if (it.isEmpty()) "" else "$it:" }

    private fun setFlag(rPr: XElem, local: String, value: Boolean) {
        val e = XElem(pf(rPr) + local)
        if (!value) e.set(pf(rPr) + "val", "0")
        rPr.setOrdered(e, WordRuns.RPR_ORDER)
    }

    fun setBold(rPr: XElem, v: Boolean) { setFlag(rPr, "b", v); setFlag(rPr, "bCs", v) }
    fun setItalic(rPr: XElem, v: Boolean) { setFlag(rPr, "i", v); setFlag(rPr, "iCs", v) }
    fun setStrike(rPr: XElem, v: Boolean) { rPr.removeChildren("dstrike"); setFlag(rPr, "strike", v) }
    fun setUnderline(rPr: XElem, v: Boolean) = rPr.setOrdered(XElem(pf(rPr) + "u").set(pf(rPr) + "val", if (v) "single" else "none"), WordRuns.RPR_ORDER)

    fun setSize(rPr: XElem, pt: Float) {
        val hp = (pt * 2).roundToInt().coerceIn(2, 3276).toString()
        rPr.setOrdered(XElem(pf(rPr) + "sz").set(pf(rPr) + "val", hp), WordRuns.RPR_ORDER)
        rPr.setOrdered(XElem(pf(rPr) + "szCs").set(pf(rPr) + "val", hp), WordRuns.RPR_ORDER)
    }

    fun setColor(rPr: XElem, hex: String?) {
        if (hex == null) { rPr.removeChildren("color"); return }
        rPr.setOrdered(XElem(pf(rPr) + "color").set(pf(rPr) + "val", hex.uppercase()), WordRuns.RPR_ORDER)
    }

    /** Highlight by palette colour; other colours become a run shading. null removes both. */
    fun setHighlight(rPr: XElem, hex: String?) {
        rPr.removeChildren("highlight")
        if (hex == null) { rPr.child("shd")?.let { if (it.attr("val") == "clear") rPr.remove(it) }; return }
        val name = HIGHLIGHTS.entries.firstOrNull { it.value.equals(hex, true) }?.key
        if (name != null) rPr.setOrdered(XElem(pf(rPr) + "highlight").set(pf(rPr) + "val", name), WordRuns.RPR_ORDER)
        else rPr.setOrdered(XElem(pf(rPr) + "shd").set(pf(rPr) + "val", "clear").set(pf(rPr) + "color", "auto").set(pf(rPr) + "fill", hex.uppercase()), WordRuns.RPR_ORDER)
    }

    fun setFont(rPr: XElem, name: String) {
        val f = rPr.child("rFonts") ?: XElem(pf(rPr) + "rFonts").also { rPr.setOrdered(it, WordRuns.RPR_ORDER) }
        listOf("ascii", "hAnsi", "cs", "eastAsia").forEach { f.set(pf(rPr) + it, name) }
        listOf("asciiTheme", "hAnsiTheme", "cstheme", "eastAsiaTheme").forEach { f.removeAttr(it) }
    }

    fun setVertAlign(rPr: XElem, v: String?) {
        if (v == null) rPr.removeChildren("vertAlign") else rPr.setOrdered(XElem(pf(rPr) + "vertAlign").set(pf(rPr) + "val", v), WordRuns.RPR_ORDER)
    }

    fun setRtl(rPr: XElem, v: Boolean) { if (v) setFlag(rPr, "rtl", true) else rPr.removeChildren("rtl") }

    // ---------------- paragraph properties ----------------

    fun align(p: XElem): PAlign? = when (p.child("pPr")?.child("jc")?.attr("val")) {
        "center" -> PAlign.CENTER; "both", "distribute", "justify", "thaiDistribute" -> PAlign.JUSTIFY
        "left", "start" -> PAlign.START; "right", "end" -> PAlign.END
        else -> null
    }

    /**
     * Writes `w:jc`. Word treats left/right as the leading/trailing edge of the paragraph direction, so START
     * is written as "left" (read correctly by Word 2007+ and LibreOffice).
     */
    fun setAlign(p: XElem, a: PAlign?) {
        val pp = WordRuns.pPr(p)
        if (a == null) { pp.removeChildren("jc"); return }
        val v = when (a) { PAlign.START -> "left"; PAlign.CENTER -> "center"; PAlign.END -> "right"; PAlign.JUSTIFY -> "both" }
        pp.setOrdered(XElem(pf(pp) + "jc").set(pf(pp) + "val", v), WordRuns.PPR_ORDER)
    }

    fun bidi(p: XElem): Boolean? = on(p.child("pPr")?.child("bidi"))

    fun setBidi(p: XElem, v: Boolean) {
        val pp = WordRuns.pPr(p)
        if (v) pp.setOrdered(XElem(pf(pp) + "bidi"), WordRuns.PPR_ORDER)
        else pp.setOrdered(XElem(pf(pp) + "bidi").set(pf(pp) + "val", "0"), WordRuns.PPR_ORDER)
        // runs of an RTL paragraph use the complex-script properties
        WordRuns.runs(p).forEach { r -> if (v) setRtl(WordRuns.rPr(r), true) else r.child("rPr")?.let { setRtl(it, false) } }
    }

    fun styleId(p: XElem): String? = p.child("pPr")?.child("pStyle")?.attr("val")

    fun setStyleId(p: XElem, id: String?) {
        val pp = WordRuns.pPr(p)
        if (id == null) pp.removeChildren("pStyle") else pp.setOrdered(XElem(pf(pp) + "pStyle").set(pf(pp) + "val", id), WordRuns.PPR_ORDER)
    }

    /** (numId, ilvl) of a direct list paragraph. */
    fun numbering(p: XElem): Pair<String, Int>? {
        val np = p.child("pPr")?.child("numPr") ?: return null
        val id = np.child("numId")?.attr("val") ?: return null
        return id to (np.child("ilvl")?.attr("val")?.toIntOrNull() ?: 0)
    }

    fun setNumbering(p: XElem, numId: String?, ilvl: Int = 0) {
        val pp = WordRuns.pPr(p)
        if (numId == null) { pp.removeChildren("numPr"); pp.child("ind")?.let { if (it.attr("hanging") != null) pp.remove(it) }; return }
        val w = pf(pp)
        val np = XElem(w + "numPr").add(XElem(w + "ilvl").set(w + "val", ilvl.coerceIn(0, 8).toString())).add(XElem(w + "numId").set(w + "val", numId))
        pp.setOrdered(np, WordRuns.PPR_ORDER)
    }

    /** Start indent in twips (w:ind start/left). */
    fun indent(p: XElem): Int = p.child("pPr")?.child("ind")?.let { (it.attr("start") ?: it.attr("left"))?.toIntOrNull() } ?: 0

    fun setIndent(p: XElem, twips: Int) {
        val pp = WordRuns.pPr(p); val w = pf(pp)
        val ind = pp.child("ind") ?: XElem(w + "ind").also { pp.setOrdered(it, WordRuns.PPR_ORDER) }
        ind.removeAttr("start"); ind.set(w + "left", twips.coerceAtLeast(0).toString())
    }

    fun pageBreakBefore(p: XElem): Boolean = on(p.child("pPr")?.child("pageBreakBefore")) == true
}

/** styles.xml: paragraph/character styles with their run props, for rendering and for finding style ids. */
class WordStyles(doc: XDoc?) {
    class Def(val id: String, val type: String, val name: String, val basedOn: String?, val run: RunProps?, val align: PAlign?, val bidi: Boolean?, val outline: Int?, val numId: String?, val ilvl: Int)

    val defs = HashMap<String, Def>()
    var defaults = RunProps(); private set
    var defaultParagraph: String? = null; private set

    init {
        doc?.root?.let { root ->
            root.child("docDefaults")?.child("rPrDefault")?.child("rPr")?.let { defaults = WordFormat.read(it).copy(bold = null, italic = null) }
            for (s in root.children("style")) {
                val id = s.attr("styleId") ?: continue
                val ppr = s.child("pPr")
                val jc = ppr?.let { XElem("w:p").add(it) }?.let { WordFormat.align(it) }
                val d = Def(
                    id, s.attr("type") ?: "paragraph", s.child("name")?.attr("val") ?: id, s.child("basedOn")?.attr("val"),
                    s.child("rPr")?.let { WordFormat.read(it) }, jc,
                    ppr?.child("bidi")?.let { it.attr("val").let { v -> v == null || v == "1" || v == "true" } },
                    ppr?.child("outlineLvl")?.attr("val")?.toIntOrNull(),
                    ppr?.child("numPr")?.child("numId")?.attr("val"), ppr?.child("numPr")?.child("ilvl")?.attr("val")?.toIntOrNull() ?: 0,
                )
                defs[id] = d
                if (s.attr("default").let { it == "1" || it == "true" } && d.type == "paragraph") defaultParagraph = id
            }
        }
    }

    fun chain(id: String?): List<Def> {
        val out = ArrayList<Def>(); var cur = id ?: defaultParagraph; var guard = 0
        while (cur != null && guard++ < 12) { val d = defs[cur] ?: break; out.add(d); cur = d.basedOn }
        return out
    }

    /** Effective run props of a paragraph style (base first). */
    fun runProps(styleId: String?): RunProps = chain(styleId).asReversed().fold(defaults) { acc, d -> acc.over(d.run) }

    /** 0 = body, 1..9 heading level, -1 title. */
    fun headingLevel(styleId: String?): Int {
        for (d in chain(styleId)) {
            val n = d.name.lowercase()
            if (n == "title") return -1
            if (n.startsWith("heading ")) return n.removePrefix("heading ").trim().toIntOrNull() ?: 1
            d.outline?.let { if (it in 0..8) return it + 1 }
        }
        return 0
    }

    /** Style id whose name is [name] (case-insensitive, e.g. "heading 1"). */
    fun idByName(name: String): String? = defs.values.firstOrNull { it.name.equals(name, true) }?.id
}

/** numbering.xml model: list level formats for markers. */
class WordNumbering(doc: XDoc?) {
    class Level(val fmt: String, val text: String, val start: Int)
    private val abstracts = HashMap<String, Map<Int, Level>>()
    private val nums = HashMap<String, String>()
    private val counters = HashMap<String, IntArray>()

    init {
        doc?.root?.let { root ->
            for (a in root.children("abstractNum")) {
                val id = a.attr("abstractNumId") ?: continue
                abstracts[id] = a.children("lvl").associate { l ->
                    (l.attr("ilvl")?.toIntOrNull() ?: 0) to Level(l.child("numFmt")?.attr("val") ?: "decimal", l.child("lvlText")?.attr("val") ?: "%1.", l.child("start")?.attr("val")?.toIntOrNull() ?: 1)
                }
            }
            for (n in root.children("num")) { val id = n.attr("numId") ?: continue; n.child("abstractNumId")?.attr("val")?.let { nums[id] = it } }
        }
    }

    fun isBullet(numId: String, ilvl: Int): Boolean = abstracts[nums[numId]]?.get(ilvl)?.fmt == "bullet"

    fun reset() = counters.clear()

    /** Marker for the next paragraph of list [numId] at [ilvl], advancing the counters (call in document order). */
    fun next(numId: String, ilvl: Int): String {
        val levels = abstracts[nums[numId]] ?: return "\u2022"
        val c = counters.getOrPut(numId) { IntArray(9) { -1 } }
        val lv = ilvl.coerceIn(0, 8)
        c[lv] = if (c[lv] < 0) (levels[lv]?.start ?: 1) else c[lv] + 1
        for (i in lv + 1 until 9) c[i] = -1
        val def = levels[lv] ?: return "\u2022"
        if (def.fmt == "bullet") return bullet(def.text)
        if (def.fmt == "none") return ""
        var t = def.text
        for (i in 0..lv) {
            val v = if (c[i] < 0) (levels[i]?.start ?: 1) else c[i]
            t = t.replace("%${i + 1}", com.smartdocs.editor.core.office.NumberText.format(v, levels[i]?.fmt ?: "decimal"))
        }
        return t
    }

    private fun bullet(t: String): String {
        val c = t.firstOrNull() ?: return "\u2022"
        return when {
            c.code in 0xF000..0xF0FF || c.code == 0xB7 -> when (c.code and 0xFF) { 0xA7 -> "\u25AA"; 0xD8 -> "\u27A2"; 0xFC -> "\u2713"; 0x6F -> "\u25CB"; else -> "\u2022" }
            c == 'o' -> "\u25CB"
            else -> t
        }
    }
}
