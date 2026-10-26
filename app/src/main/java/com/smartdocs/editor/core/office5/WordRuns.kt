package com.smartdocs.editor.core.office5

/**
 * Character-level editing of one WordprocessingML paragraph (`w:p`) in place: the paragraph's text is
 * the concatenation of its runs (also runs nested in hyperlinks, insertions, smart tags, simple fields and
 * content controls). Offsets are UTF-16 indexes in that text. Tabs, breaks and symbols count as one
 * character, pictures/objects count as one [OBJ] character, field codes and bookmarks count as zero.
 *
 * Runs are split at range boundaries, run properties (`w:rPr`) are edited child by child in schema order
 * so unknown properties survive, and adjacent runs with identical properties are merged afterwards.
 */
object WordRuns {
    const val OBJ = '\uFFFC'

    val RPR_ORDER = listOf(
        "rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline", "shadow", "emboss",
        "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing", "w", "kern", "position", "sz", "szCs",
        "highlight", "u", "effect", "bdr", "shd", "fitText", "vertAlign", "rtl", "cs", "em", "lang", "eastAsianLayout",
        "specVanish", "oMath", "rPrChange",
    )
    val PPR_ORDER = listOf(
        "pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr", "suppressLineNumbers", "pBdr",
        "shd", "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct", "topLinePunct", "autoSpaceDE", "autoSpaceDN",
        "bidi", "adjustRightInd", "snapToGrid", "spacing", "ind", "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc",
        "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr", "sectPr", "pPrChange",
    )

    private val CONTAINERS = setOf("hyperlink", "ins", "smartTag", "customXml", "fldSimple", "bdo", "dir", "moveTo", "sdt", "sdtContent")
    private val SIMPLE_PIECES = setOf("rPr", "t", "tab", "br", "cr")

    // ---------------- reading ----------------

    /** Text runs of [p] in document order. */
    fun runs(p: XElem): List<XElem> = ArrayList<XElem>().also { collectRuns(p, it) }

    private fun collectRuns(e: XElem, out: MutableList<XElem>) {
        for (k in e.kids) if (k is XElem) when (k.local) {
            "r" -> out.add(k)
            in CONTAINERS -> collectRuns(k, out)
        }
    }

    fun pieceLen(e: XElem): Int = when (e.local) {
        "t" -> e.textContent().length
        "tab", "ptab", "br", "cr", "noBreakHyphen", "softHyphen", "sym" -> 1
        "drawing", "pict", "object", "footnoteReference", "endnoteReference" -> 1
        "AlternateContent" -> 1
        else -> 0
    }

    fun pieceText(e: XElem): String = when (e.local) {
        "t" -> e.textContent()
        "tab", "ptab" -> "\t"
        "br", "cr" -> "\n"
        "noBreakHyphen" -> "-"
        "softHyphen" -> ""
        "sym" -> e.attr("char")?.toIntOrNull(16)?.let { c -> String(Character.toChars(if (c in 0xF000..0xF0FF) c - 0xF000 else c)) }?.take(1) ?: "?"
        "drawing", "pict", "object", "footnoteReference", "endnoteReference", "AlternateContent" -> OBJ.toString()
        else -> ""
    }.let { if (e.local == "softHyphen") "\u00AD" else it }

    fun runLen(r: XElem): Int = r.elems().sumOf { if (it.local == "rPr") 0 else pieceLen(it) }
    fun runText(r: XElem): String = buildString { r.elems().forEach { if (it.local != "rPr") append(pieceText(it)) } }
    fun text(p: XElem): String = buildString { runs(p).forEach { append(runText(it)) } }

    /** Text runs with their start offsets. */
    fun positioned(p: XElem): List<Pair<Int, XElem>> {
        var pos = 0
        return runs(p).map { r -> (pos to r).also { pos += runLen(r) } }
    }

    // ---------------- tree helpers ----------------

    fun parentOf(root: XElem, target: XNode): XElem? {
        for (k in root.kids) {
            if (k === target) return root
            if (k is XElem) parentOf(k, target)?.let { return it }
        }
        return null
    }

    private fun w(p: XElem) = p.prefix.let { if (it.isEmpty()) "" else "$it:" }

    private fun newT(prefix: String, s: String): XElem {
        val t = XElem("${prefix}t")
        if (s.isNotEmpty() && (s.first().isWhitespace() || s.last().isWhitespace() || s.contains("  "))) t.set("xml:space", "preserve")
        return t.setText(s)
    }

    private fun fixSpace(t: XElem) {
        val s = t.textContent()
        if (s.isNotEmpty() && (s.first().isWhitespace() || s.last().isWhitespace() || s.contains("  "))) t.set("xml:space", "preserve")
    }

    // ---------------- splitting / merging ----------------

    /** Splits run [r] (child of [parent]) so that the first [k] characters stay in [r]; returns the new right run. */
    fun splitRun(parent: XElem, r: XElem, k: Int): XElem {
        val right = XElem(r.name)
        r.attrs.forEach { right.attrs.add(XAttr(it.name, it.raw, it.quote)) }
        r.child("rPr")?.let { right.add(it.copy()) }
        val keep = ArrayList<XNode>(); var pos = 0
        for (n in r.kids) {
            if (n !is XElem) { (if (pos < k) keep else right.kids).add(n); continue }
            if (n.local == "rPr") { keep.add(n); continue }
            val len = pieceLen(n)
            when {
                pos >= k && !(len == 0 && pos == k && keep.isNotEmpty() && n.local != "t") -> right.add(n)
                n.local == "t" && pos < k && k < pos + len -> {
                    val s = n.textContent()
                    val a = newT(w(r), s.substring(0, k - pos)); n.attrs.forEach { if (it.name != "xml:space") a.attrs.add(XAttr(it.name, it.raw, it.quote)) }
                    keep.add(a); right.add(newT(w(r), s.substring(k - pos)))
                }
                else -> keep.add(n)
            }
            pos += len
        }
        r.kids.clear(); r.kids.addAll(keep); r.selfClose = keep.isEmpty()
        right.selfClose = right.kids.isEmpty()
        parent.add(parent.indexOf(r) + 1, right)
        return right
    }

    /** Ensures a run boundary at [offset] (no-op at 0, at the end or on an existing boundary). */
    fun splitAt(p: XElem, offset: Int) {
        for ((pos, r) in positioned(p)) {
            val len = runLen(r)
            if (offset > pos && offset < pos + len) { splitRun(parentOf(p, r) ?: return, r, offset - pos); return }
        }
    }

    private fun isSimple(r: XElem) = r.kids.all { it is XText && it.raw.isBlank() || it is XElem && it.local in SIMPLE_PIECES }

    private fun rPrKey(r: XElem): String = r.child("rPr")?.let { rp ->
        // revision marks of the properties do not block merging
        val c = rp.copy(); c.removeChildren("rPrChange"); if (c.kids.isEmpty()) "" else c.kids.joinToString("") { (it as? XElem)?.toXml() ?: "" }
    } ?: ""

    /** Merges adjacent simple runs with identical properties everywhere inside [p]; also joins adjacent `w:t`. */
    fun mergeRuns(p: XElem) {
        mergeIn(p)
        for (k in p.elems()) if (k.local in CONTAINERS) mergeRuns(k)
    }

    private fun mergeIn(c: XElem) {
        var i = 0
        while (i < c.kids.size) {
            val a = c.kids[i]
            if (a is XElem && a.local == "r") {
                var j = i + 1
                while (j < c.kids.size && c.kids[j].let { it is XText && it.raw.isBlank() }) j++
                val b = c.kids.getOrNull(j)
                if (b is XElem && b.local == "r" && isSimple(a) && isSimple(b) && rPrKey(a) == rPrKey(b)) {
                    b.kids.forEach { n -> if (!(n is XElem && n.local == "rPr") && !(n is XText && n.raw.isBlank())) a.add(n) }
                    for (x in j downTo i + 1) c.kids.removeAt(x)
                    joinTexts(a)
                    continue
                }
                joinTexts(a)
            }
            i++
        }
    }

    /** Joins adjacent `w:t` children of run [r]. */
    fun joinTexts(r: XElem) {
        var i = 0
        while (i < r.kids.size - 1) {
            val a = r.kids[i]; val b = r.kids[i + 1]
            if (a is XElem && b is XElem && a.local == "t" && b.local == "t") {
                a.setText(a.textContent() + b.textContent()); fixSpace(a); r.kids.removeAt(i + 1)
            } else i++
        }
    }

    // ---------------- text edits ----------------

    /** Pieces (t / br / tab) for [s] with prefix [pfx]. */
    fun pieces(pfx: String, s: String): List<XElem> {
        val out = ArrayList<XElem>(); val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out.add(newT(pfx, sb.toString())); sb.setLength(0) } }
        for (ch in s) when (ch) {
            '\n' -> { flush(); out.add(XElem("${pfx}br")) }
            '\t' -> { flush(); out.add(XElem("${pfx}tab")) }
            OBJ -> {}
            else -> sb.append(ch)
        }
        flush()
        return out
    }

    /** Run properties for text typed into an empty paragraph: the paragraph mark properties. */
    private fun markRunProps(p: XElem): XElem? = p.child("pPr")?.child("rPr")?.copy()?.also { rp ->
        rp.removeChildren("ins"); rp.removeChildren("del"); rp.removeChildren("rPrChange"); rp.removeChildren("moveFrom"); rp.removeChildren("moveTo")
    }?.takeIf { it.kids.isNotEmpty() }

    /** Inserts [s] at [offset]; the new text takes the formatting of the character before it (or after at 0). */
    fun insertText(p: XElem, offset: Int, s: String) {
        if (s.isEmpty()) return
        val pfx = w(p)
        splitAt(p, offset)
        val runs = positioned(p)
        val left = runs.lastOrNull { (pos, r) -> pos < offset && pos + runLen(r) == offset }?.second
            ?: runs.lastOrNull { (pos, r) -> pos == offset && r.elems().all { it.local == "rPr" } }?.second
        if (left != null && isSimple(left)) { pieces(pfx, s).forEach { left.add(it) }; joinTexts(left); return }
        val right = runs.firstOrNull { (pos, r) -> pos == offset && runLen(r) > 0 }?.second
        if (left == null && right != null && isSimple(right)) {
            val at = if (right.kids.firstOrNull { it is XElem && it.local == "rPr" } != null) right.kids.indexOfFirst { it is XElem && it.local == "rPr" } + 1 else 0
            pieces(pfx, s).asReversed().forEach { right.add(at, it) }; joinTexts(right); return
        }
        // a new run copying the neighbour's properties
        val model = left ?: right
        val nr = XElem("${pfx}r")
        (model?.child("rPr")?.copy() ?: markRunProps(p))?.let { nr.add(it) }
        pieces(pfx, s).forEach { nr.add(it) }
        when {
            left != null -> { val par = parentOf(p, left)!!; par.add(par.indexOf(left) + 1, nr) }
            right != null -> { val par = parentOf(p, right)!!; par.add(par.indexOf(right), nr) }
            else -> p.add(nr)
        }
    }

    /** Deletes the text in [start, end). Field characters and other zero-width markup in the range are kept. */
    fun deleteRange(p: XElem, start: Int, end: Int) {
        if (end <= start) return
        splitAt(p, start); splitAt(p, end)
        for ((pos, r) in positioned(p)) {
            val len = runLen(r)
            if (len == 0 || pos < start || pos + len > end) continue
            val parent = parentOf(p, r) ?: continue
            val keep = r.elems().filter { it.local != "rPr" && pieceLen(it) == 0 }
            if (keep.isEmpty()) parent.remove(r)
            else { r.kids.removeAll { it is XElem && it.local != "rPr" && pieceLen(it) > 0 } }
        }
        mergeRuns(p)
    }

    /** Replaces the paragraph text with [s] changing only the differing middle (formatting of the rest is kept). */
    fun setText(p: XElem, s: String) {
        val old = text(p)
        if (old == s) return
        var a = 0
        while (a < old.length && a < s.length && old[a] == s[a]) a++
        var b = 0
        while (b < old.length - a && b < s.length - a && old[old.length - 1 - b] == s[s.length - 1 - b]) b++
        deleteRange(p, a, old.length - b)
        insertText(p, a, s.substring(a, s.length - b))
    }

    /** Applies [mutate] to the properties of every run covering [start, end), then merges equal runs. */
    fun formatRange(p: XElem, start: Int, end: Int, mutate: (XElem) -> Unit) {
        if (end <= start) return
        splitAt(p, start); splitAt(p, end)
        for ((pos, r) in positioned(p)) {
            val len = runLen(r)
            if (len == 0 || pos < start || pos + len > end) continue
            mutate(rPr(r))
        }
        mergeRuns(p)
    }

    /** The run's `w:rPr`, created as first child when missing. */
    fun rPr(r: XElem): XElem = r.child("rPr") ?: XElem("${w(r)}rPr").also { r.add(0, it) }

    /** The paragraph's `w:pPr`, created as first child when missing. */
    fun pPr(p: XElem): XElem = p.child("pPr") ?: XElem("${w(p)}pPr").also { p.add(0, it) }

    /** Runs overlapping [start, end) (for reading their formatting). */
    fun runsIn(p: XElem, start: Int, end: Int): List<XElem> = positioned(p).filter { (pos, r) ->
        val len = runLen(r); len > 0 && pos < maxOf(end, start + 1) && pos + len > start
    }.map { it.second }

    // ---------------- paragraph split / join ----------------

    private fun treeLen(e: XElem): Int = when {
        e.local == "r" -> runLen(e)
        e.local in CONTAINERS -> e.elems().sumOf { treeLen(it) }
        else -> 0
    }

    /** Moves everything of [c] after [k] into a shallow copy of [c] (returned). */
    private fun splitTree(c: XElem, k: Int): XElem {
        val right = XElem(c.name)
        c.attrs.forEach { right.attrs.add(XAttr(it.name, it.raw, it.quote)) }
        var pos = 0
        val moved = ArrayList<XNode>()
        for (n in c.kids.toList()) {
            if (n !is XElem) { if (pos >= k) moved.add(n); continue }
            if (n.local == "pPr") continue
            val len = treeLen(n)
            when {
                pos >= k && !(len == 0 && pos == k && n.local != "r" && n.local !in CONTAINERS) -> moved.add(n)
                pos < k && k < pos + len && n.local in CONTAINERS -> right.add(splitTree(n, k - pos))
            }
            pos += len
        }
        moved.forEach { c.remove(it); right.add(it) }
        if (c.kids.isEmpty()) c.selfClose = true
        right.selfClose = right.kids.isEmpty()
        return right
    }

    /** Splits [p] at [offset] into two paragraphs; the new second one (inserted after [p] in [parent]) is returned. */
    fun splitParagraph(parent: XElem, p: XElem, offset: Int): XElem {
        splitAt(p, offset)
        val right = splitTree(p, offset)
        listOf("w14:paraId", "w14:textId").forEach { right.removeAttr(it) }
        p.child("pPr")?.let { pp ->
            val copy = pp.copy()
            // the section break belongs to the last paragraph of the section
            pp.children("sectPr").forEach { pp.remove(it) }
            copy.children("pageBreakBefore").forEach { copy.remove(it) }
            right.add(0, copy)
        }
        parent.add(parent.indexOf(p) + 1, right)
        return right
    }

    /** Appends the content of [second] to [first] and removes [second] from [parent]. */
    fun joinParagraphs(parent: XElem, first: XElem, second: XElem) {
        val sect = second.child("pPr")?.child("sectPr")
        second.kids.filter { !(it is XElem && it.local == "pPr") }.forEach { first.add(it) }
        if (sect != null) { val pp = pPr(first); pp.removeChildren("sectPr"); pp.setOrdered(sect, PPR_ORDER) }
        parent.remove(second)
        mergeRuns(first)
    }
}
