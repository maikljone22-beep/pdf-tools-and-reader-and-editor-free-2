package com.smartdocs.editor.core.office5

import java.io.File

/**
 * An editable .docx: the package stays in memory, word/document.xml is edited as a DOM in place (so any
 * markup the editor does not know is kept), and saving rewrites only the parts that were edited.
 *
 * Every change goes through [change] (one undo step). Block elements handed out before an [undo]/[redo]
 * are stale; callers re-read [blocks] when [version] changes.
 */
class WordDoc private constructor(val pkg: OPackage) {
    val docPath: String = pkg.mainPart("word/document.xml")
    private val undoStack = SnapshotUndo(pkg)

    lateinit var doc: XDoc; private set
    lateinit var styles: WordStyles; private set
    lateinit var numbering: WordNumbering; private set
    /** Prefix of the WordprocessingML namespace in document.xml (normally "w"). */
    var w: String = "w"; private set

    var version: Int = 0; private set
    var modified: Boolean = false
    val canUndo get() = undoStack.canUndo
    val canRedo get() = undoStack.canRedo

    init { reload() }

    private fun reload() {
        doc = pkg.dom(docPath) ?: throw XmlParseException("document.xml missing")
        w = doc.prefixOf(WordParts.W_NS) ?: "w"
        reloadAux()
    }

    private fun reloadAux() {
        styles = WordStyles(pkg.rels(docPath).firstOrNull { it.type == WordParts.REL_STYLES }?.let { pkg.dom(it.target) })
        numbering = WordNumbering(pkg.rels(docPath).firstOrNull { it.type == WordParts.REL_NUMBERING }?.let { pkg.dom(it.target) })
    }

    val body: XElem get() = doc.root.child("body") ?: XElem("$w:body").also { doc.root.add(it) }

    /** Body-level blocks (paragraphs, tables, content controls…) without the final section properties. */
    fun blocks(): List<XElem> = body.elems().filter { it.local != "sectPr" }

    /** Runs [block] as one undoable change. */
    fun <T> change(block: () -> T): T {
        undoStack.checkpoint()
        pkg.edit(docPath)
        val r = block()
        modified = true; version++
        return r
    }

    /** A change that continues the previous undo step (typing in the same paragraph). */
    fun <T> continueChange(block: () -> T): T { pkg.edit(docPath); val r = block(); modified = true; version++; return r }

    fun checkpoint() = undoStack.checkpoint()

    fun undo(): Boolean = undoStack.undo().also { if (it) { reload(); modified = true; version++ } }
    fun redo(): Boolean = undoStack.redo().also { if (it) { reload(); modified = true; version++ } }

    /** Call after changing styles.xml / numbering.xml. */
    fun stylesChanged() = reloadAux()

    fun save(target: File) { pkg.saveTo(target); modified = false }

    // ---------------- blocks ----------------

    /** Index where a new body block goes after [after] (null = at the end, before the section properties). */
    private fun insertIndex(after: XElem?): Int {
        val b = body
        if (after != null) { val i = b.indexOf(after); if (i >= 0) return i + 1 }
        val sect = b.kids.indexOfLast { it is XElem && it.local == "sectPr" }
        return if (sect >= 0) sect else b.kids.size
    }

    /** A new empty paragraph formatted like [like] (direction, alignment, style except headings, run props). */
    fun newParagraph(like: XElem?, keepStyle: Boolean = false): XElem {
        val p = XElem("$w:p")
        like?.child("pPr")?.copy()?.let { pp ->
            pp.removeChildren("sectPr"); pp.removeChildren("pageBreakBefore"); pp.removeChildren("pPrChange")
            if (!keepStyle) { val sid = pp.child("pStyle")?.attr("val"); if (sid != null && styles.headingLevel(sid) != 0) pp.removeChildren("pStyle") }
            if (pp.kids.isNotEmpty()) p.add(pp)
        }
        return p
    }

    fun insertParagraphAfter(after: XElem?, text: String = "", like: XElem? = after?.takeIf { it.local == "p" }): XElem {
        val p = newParagraph(like)
        if (like == null && com.smartdocs.editor.core.office.hasRtlChars(text)) WordFormat.setBidi(p, true)
        body.add(insertIndex(after), p)
        if (text.isNotEmpty()) WordRuns.insertText(p, 0, text)
        return p
    }

    fun deleteBlock(b: XElem) {
        body.remove(b)
        if (blocks().none { it.local == "p" || it.local == "tbl" }) body.add(insertIndex(null), XElem("$w:p"))
    }

    fun moveBlock(b: XElem, delta: Int) {
        val list = body.kids
        val i = list.indexOf(b); if (i < 0) return
        val sibs = list.withIndex().filter { (_, n) -> n is XElem && n.local != "sectPr" }.map { it.index }
        val pos = sibs.indexOf(i); val to = pos + delta
        if (to !in sibs.indices) return
        list.removeAt(i); list.add(sibs[to], b)
    }

    /** Inserts a page break paragraph after [after]. */
    fun insertPageBreak(after: XElem?): XElem {
        val p = XDom.elem("<$w:p><$w:r><$w:br $w:type=\"page\"/></$w:r></$w:p>")
        after?.child("pPr")?.child("bidi")?.let { p.add(0, XElem("$w:pPr").add(it.copy())) }
        body.add(insertIndex(after), p)
        return p
    }

    fun isPageBreak(p: XElem): Boolean = p.local == "p" && WordRuns.text(p) == "\n" && p.findAll("br").any { it.attr("type") == "page" }

    // ---------------- styles & lists ----------------

    fun applyStyle(p: XElem, k: WordParts.StyleKind) {
        if (k == WordParts.StyleKind.NORMAL) { WordFormat.setStyleId(p, null); return }
        val id = WordParts.ensureStyle(pkg, docPath, k)
        stylesChanged()
        WordFormat.setStyleId(p, id)
    }

    fun styleKindOf(p: XElem): WordParts.StyleKind {
        val sid = WordFormat.styleId(p) ?: return WordParts.StyleKind.NORMAL
        return when (styles.headingLevel(sid)) { -1 -> WordParts.StyleKind.TITLE; 1 -> WordParts.StyleKind.HEADING1; 2 -> WordParts.StyleKind.HEADING2; 3 -> WordParts.StyleKind.HEADING3; else -> if (styles.defs[sid]?.name.equals("Quote", true)) WordParts.StyleKind.QUOTE else WordParts.StyleKind.NORMAL }
    }

    /** Toggles a bullet (or numbered) list on [paras]. */
    fun toggleList(paras: List<XElem>, bullet: Boolean) {
        val numId = WordParts.ensureList(pkg, docPath, bullet)
        stylesChanged()
        val allOn = paras.all { p -> WordFormat.numbering(p)?.let { it.first == numId } == true }
        paras.forEach { p -> if (allOn) WordFormat.setNumbering(p, null) else WordFormat.setNumbering(p, numId, WordFormat.numbering(p)?.second ?: 0) }
    }

    fun changeListLevel(p: XElem, delta: Int) {
        val n = WordFormat.numbering(p)
        if (n == null) { WordFormat.setIndent(p, (WordFormat.indent(p) + 720 * delta).coerceAtLeast(0)); return }
        WordFormat.setNumbering(p, n.first, (n.second + delta).coerceIn(0, 8))
    }

    // ---------------- pictures ----------------

    /** Usable text width from the last section's page size and margins, in EMU (fallback A4 with 1 inch margins). */
    fun contentWidthEmu(): Long {
        val sect = body.child("sectPr")
        val pw = sect?.child("pgSz")?.attr("w")?.toIntOrNull() ?: 11906
        val m = sect?.child("pgMar")
        val l = m?.attr("left")?.toIntOrNull() ?: 1440; val r = m?.attr("right")?.toIntOrNull() ?: 1440
        return (pw - l - r).coerceAtLeast(1440).toLong() * 635
    }

    /** Inserts a picture in its own paragraph after [after]; returns the paragraph. */
    fun insertImage(after: XElem?, bytes: ByteArray, name: String = "Picture"): XElem {
        val rId = WordParts.addImagePart(pkg, docPath, bytes)
        val (pw, ph) = OPackage.imageSize(bytes) ?: (400 to 300)
        var cx = pw.toLong() * 9525; var cy = ph.toLong() * 9525
        val max = contentWidthEmu()
        if (cx > max) { cy = cy * max / cx; cx = max }
        val id = (doc.root.findAll("docPr").mapNotNull { it.attr("id")?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val p = newParagraph(after?.takeIf { it.local == "p" })
        WordFormat.setAlign(p, PAlign.CENTER)
        p.add(XDom.elem(WordParts.inlinePictureXml(w, rId, cx, cy, id, "$name $id")))
        body.add(insertIndex(after), p)
        return p
    }

    /** Pictures of a paragraph: the `w:drawing` elements with their size in EMU. */
    fun drawings(p: XElem): List<XElem> = p.findAll("drawing")

    fun drawingSize(d: XElem): Pair<Long, Long> {
        val e = d.find("extent") ?: return 0L to 0L
        return (e.attr("cx")?.toLongOrNull() ?: 0L) to (e.attr("cy")?.toLongOrNull() ?: 0L)
    }

    fun resizeDrawing(d: XElem, cx: Long, cy: Long) {
        d.find("extent")?.set("cx", cx.toString())?.set("cy", cy.toString())
        d.find("graphic")?.find("xfrm")?.child("ext")?.set("cx", cx.toString())?.set("cy", cy.toString())
    }

    /** Image bytes of a drawing (blip r:embed), or null. */
    fun drawingImage(d: XElem): ByteArray? {
        val blip = d.find("blip") ?: return null
        val id = blip.attr("r:embed") ?: blip.attr("embed") ?: return null
        val rel = pkg.rel(docPath, id) ?: return null
        return if (rel.external) null else pkg.bytes(rel.target)
    }

    // ---------------- page setup ----------------

    /** All section properties (the body one is last); one is created when the document has none. */
    fun sections(): List<XElem> {
        val all = doc.root.findAll("sectPr").filter { WordRuns.parentOf(doc.root, it)?.local != "rPr" }
        if (all.isNotEmpty()) return all
        val s = XElem("$w:sectPr"); body.add(s); return listOf(s)
    }

    fun isLandscape(): Boolean = sections().last().child("pgSz")?.let { pg ->
        pg.attr("orient") == "landscape" || (pg.attr("w")?.toIntOrNull() ?: 0) > (pg.attr("h")?.toIntOrNull() ?: 0)
    } == true

    fun setLandscape(on: Boolean) {
        for (s in sections()) {
            val pg = s.child("pgSz") ?: XElem("$w:pgSz").also { s.setOrdered(it, SECT_ORDER) }
            val a = pg.attr("w")?.toIntOrNull() ?: 11906; val b = pg.attr("h")?.toIntOrNull() ?: 16838
            val long = maxOf(a, b); val short = minOf(a, b)
            pg.set("$w:w", (if (on) long else short).toString()).set("$w:h", (if (on) short else long).toString())
            if (on) pg.set("$w:orient", "landscape") else pg.removeAttr("orient")
        }
    }

    /** Sets all four page margins (twips) in every section. */
    fun setMargins(twips: Int) {
        for (s in sections()) {
            val m = s.child("pgMar") ?: XElem("$w:pgMar").set("$w:header", "708").set("$w:footer", "708").set("$w:gutter", "0").also { s.setOrdered(it, SECT_ORDER) }
            listOf("top", "bottom", "left", "right").forEach { m.set("$w:$it", twips.toString()) }
        }
    }

    // ---------------- find / replace / statistics ----------------

    data class Match(val paragraph: XElem, val start: Int, val end: Int)

    /** All paragraphs of the body including table cells, in document order. */
    fun allParagraphs(): List<XElem> = body.findAll("p")

    fun find(query: String, matchCase: Boolean = false): List<Match> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        for (p in allParagraphs()) {
            val t = WordRuns.text(p)
            var i = t.indexOf(query, 0, !matchCase)
            while (i >= 0) { out.add(Match(p, i, i + query.length)); i = t.indexOf(query, i + query.length, !matchCase) }
        }
        return out
    }

    fun replace(m: Match, with: String) {
        WordRuns.insertText(m.paragraph, m.end, with)
        WordRuns.deleteRange(m.paragraph, m.start, m.end)
    }

    /** Replaces every occurrence; returns the count. One undo step. */
    fun replaceAll(query: String, with: String, matchCase: Boolean = false): Int {
        val ms = find(query, matchCase)
        if (ms.isEmpty()) return 0
        change { ms.asReversed().forEach { replace(it, with) } }
        return ms.size
    }

    data class Stats(val words: Int, val chars: Int, val charsNoSpaces: Int, val paragraphs: Int, val pagesEstimate: Int)

    fun stats(): Stats {
        var words = 0; var chars = 0; var nospace = 0; var paras = 0
        for (p in allParagraphs()) {
            val t = WordRuns.text(p).replace(WordRuns.OBJ.toString(), "")
            if (t.isNotBlank()) paras++
            chars += t.length; nospace += t.count { !it.isWhitespace() }
            words += t.split(Regex("\\s+")).count { s -> s.any { it.isLetterOrDigit() } }
        }
        return Stats(words, chars, nospace, paras, (words / 450 + 1).coerceAtLeast(1))
    }

    companion object {
        val SECT_ORDER = listOf("headerReference", "footerReference", "footnotePr", "endnotePr", "type", "pgSz", "pgMar", "paperSrc", "pgBorders",
            "lnNumType", "pgNumType", "cols", "formProt", "vAlign", "noEndnote", "titlePg", "textDirection", "bidi", "rtlGutter", "docGrid", "printerSettings", "sectPrChange")

        fun open(file: File): WordDoc = WordDoc(OPackage.open(file))
        fun of(pkg: OPackage): WordDoc = WordDoc(pkg)
        fun blank(paragraphs: List<String> = listOf(""), rtl: Boolean = true): WordDoc = WordDoc(WordParts.blankDocx(paragraphs, rtl))
    }
}
