package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.annot.AnnotStyle

/**
 * A character range of a rich-text field whose bold / italic / underline differ from the field's style (null =
 * as the field). [end] exclusive, in characters of the field value.
 */
data class RichSpan(val start: Int, val end: Int, val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null) {
    val empty: Boolean get() = bold == null && italic == null && underline == null
    fun sameStyle(o: RichSpan) = bold == o.bold && italic == o.italic && underline == o.underline
}

/**
 * FINAL_CHECK_2 #14 — rich text per character at fill time: spans over the value of a rich-text field, toggled on a
 * selection, kept in step with typing, written into /RV as `<span style>` runs and read back from it. Pure Kotlin,
 * unit-tested.
 */
object RichSpans {

    /** Clamped to [len], empty and null-only spans dropped, neighbours with the same style merged, sorted. */
    fun normalize(spans: List<RichSpan>, len: Int): List<RichSpan> {
        val sorted = spans.map { RichSpan(it.start.coerceIn(0, len), it.end.coerceIn(0, len), it.bold, it.italic, it.underline) }
            .filter { it.end > it.start && !it.empty }.sortedBy { it.start }
        val out = ArrayList<RichSpan>()
        for (s in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.end == s.start && last.sameStyle(s)) out[out.lastIndex] = last.copy(end = s.end) else out += s
        }
        return out
    }

    private fun flag(s: RichSpan?, what: Char): Boolean? = when (what) { 'b' -> s?.bold; 'i' -> s?.italic; else -> s?.underline }
    private fun with(s: RichSpan, what: Char, v: Boolean?): RichSpan = when (what) { 'b' -> s.copy(bold = v); 'i' -> s.copy(italic = v); else -> s.copy(underline = v) }
    private fun baseFlag(base: AnnotStyle.Rich?, what: Char): Boolean = when (what) { 'b' -> base?.bold == true; 'i' -> base?.italic == true; else -> base?.underline == true }

    /** The effective flag of character [i]. */
    fun effective(spans: List<RichSpan>, base: AnnotStyle.Rich?, i: Int, what: Char): Boolean =
        flag(spans.firstOrNull { i >= it.start && i < it.end }, what) ?: baseFlag(base, what)

    /**
     * Toggle 'b' / 'i' / 'u' on [start, end): off when every character of the range has it on, else on. The result
     * is stored as overrides relative to [base] (an override equal to the base is dropped).
     */
    fun toggle(spans: List<RichSpan>, base: AnnotStyle.Rich?, start: Int, end: Int, what: Char, len: Int): List<RichSpan> {
        val a = minOf(start, end).coerceIn(0, len); val b = maxOf(start, end).coerceIn(0, len)
        if (b <= a) return spans
        val allOn = (a until b).all { effective(spans, base, it, what) }
        val want = !allOn
        val override: Boolean? = if (want == baseFlag(base, what)) null else want
        // cut the existing spans at a and b, then set the flag inside
        val cut = ArrayList<RichSpan>()
        for (s in normalize(spans, len)) {
            if (s.end <= a || s.start >= b) { cut += s; continue }
            if (s.start < a) cut += s.copy(end = a)
            cut += with(s.copy(start = maxOf(s.start, a), end = minOf(s.end, b)), what, override)
            if (s.end > b) cut += s.copy(start = b)
        }
        // the gaps inside [a, b) that no span covered
        var pos = a
        val inside = cut.filter { it.start >= a && it.end <= b }.sortedBy { it.start }
        for (s in inside) { if (s.start > pos) cut += with(RichSpan(pos, s.start), what, override); pos = s.end }
        if (pos < b) cut += with(RichSpan(pos, b), what, override)
        return normalize(cut, len)
    }

    /** Spans of [old] moved to fit [new] (one edit: the common prefix and suffix stay, the middle was replaced). */
    fun adjust(spans: List<RichSpan>, old: String, new: String): List<RichSpan> {
        if (spans.isEmpty() || old == new) return normalize(spans, new.length)
        var p = 0
        while (p < old.length && p < new.length && old[p] == new[p]) p++
        var s = 0
        while (s < old.length - p && s < new.length - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        val removed = old.length - p - s; val inserted = new.length - p - s
        val delta = inserted - removed
        val out = ArrayList<RichSpan>()
        for (sp in spans) {
            when {
                // typing right at the end of a span continues its style (as editors do)
                sp.end <= p -> out += if (sp.end == p && removed == 0 && inserted > 0) sp.copy(end = sp.end + inserted) else sp
                sp.start >= p + removed -> out += sp.copy(start = sp.start + delta, end = sp.end + delta)
                else -> {
                    // the span overlaps the edit: it keeps what lies before and after, and takes in inserted text when it surrounded the edit
                    val ns = minOf(sp.start, p)
                    val ne = if (sp.end >= p + removed) sp.end + delta else p + (if (sp.start < p) inserted else 0)
                    if (ne > ns) out += sp.copy(start = ns, end = ne)
                }
            }
        }
        return normalize(out, new.length)
    }

    private fun esc(s: String) = buildString { s.forEach { ch -> when (ch) { '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;"); '"' -> append("&quot;"); else -> append(ch) } } }

    /** The XHTML body of [text] with [base] as the body style and the spans as `<span style>` runs (lines → `<p>`). */
    fun body(text: String, base: AnnotStyle.Rich, spans: List<RichSpan>, rtl: Boolean): String {
        val plain = AnnotStyle.richBody(text, base, rtl)
        val sp = normalize(spans, text.length)
        if (sp.isEmpty()) return plain
        // replace the paragraphs of the plain body with styled runs, line by line
        val head = plain.substring(0, plain.indexOf('>', plain.indexOf("<body")) + 1)
        val sb = StringBuilder(head)
        var offset = 0
        text.split('\n').forEach { line ->
            sb.append("<p>")
            var i = 0
            while (i < line.length) {
                val gi = offset + i
                val s = sp.firstOrNull { gi >= it.start && gi < it.end }
                val runEnd = if (s == null) (sp.firstOrNull { it.start > gi }?.start ?: Int.MAX_VALUE).coerceAtMost(offset + line.length) - offset else minOf(s.end - offset, line.length)
                val run = line.substring(i, runEnd)
                if (s == null) sb.append(esc(run)) else {
                    val css = ArrayList<String>()
                    s.bold?.let { css += "font-weight:" + if (it) "bold" else "normal" }
                    s.italic?.let { css += "font-style:" + if (it) "italic" else "normal" }
                    s.underline?.let { css += "text-decoration:" + if (it) "underline" else "none" }
                    sb.append("<span style=\"").append(css.joinToString(";")).append("\">").append(esc(run)).append("</span>")
                }
                i = runEnd
            }
            sb.append("</p>")
            offset += line.length + 1
        }
        return sb.append("</body>").toString()
    }

    private val TAG = Regex("""<(/?)([a-zA-Z0-9]+)([^>]*)>""")
    private val STYLE = Regex("""style\s*=\s*"([^"]*)"""")

    /** The text and the spans of an /RV body (span / b / i / u tags; p, li, br break lines). */
    fun parse(rv: String): Pair<String, List<RichSpan>> {
        val text = StringBuilder()
        val spans = ArrayList<RichSpan>()
        data class Open(val tag: String, val start: Int, val b: Boolean?, val i: Boolean?, val u: Boolean?)
        val stack = ArrayDeque<Open>()
        var pos = 0
        var firstBlock = true
        fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        val bodyStart = rv.indexOf("<body").let { if (it < 0) 0 else rv.indexOf('>', it) + 1 }
        for (m in TAG.findAll(rv.substring(bodyStart))) {
            val chunk = rv.substring(bodyStart + pos, bodyStart + m.range.first)
            if (chunk.isNotEmpty()) text.append(unesc(chunk.replace(Regex("\\s*\\n\\s*"), "")))
            pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"; val tag = m.groupValues[2].lowercase(); val attrs = m.groupValues[3]
            when (tag) {
                "p", "li", "div" -> if (!closing) { if (!firstBlock) text.append('\n'); firstBlock = false }
                "br" -> text.append('\n')
                "span", "b", "strong", "i", "em", "u" -> if (!closing) {
                    var b: Boolean? = null; var i: Boolean? = null; var u: Boolean? = null
                    when (tag) { "b", "strong" -> b = true; "i", "em" -> i = true; "u" -> u = true }
                    STYLE.find(attrs)?.groupValues?.get(1)?.lowercase()?.let { css ->
                        if (css.contains("font-weight")) b = css.contains("bold") || Regex("font-weight:\\s*[6-9]00").containsMatchIn(css)
                        if (css.contains("font-style")) i = css.contains("italic") || css.contains("oblique")
                        if (css.contains("text-decoration")) u = css.contains("underline")
                    }
                    stack.addLast(Open(tag, text.length, b, i, u))
                } else {
                    val o = stack.lastOrNull { it.tag == tag } ?: continue
                    stack.remove(o)
                    if (text.length > o.start && (o.b != null || o.i != null || o.u != null)) spans += RichSpan(o.start, text.length, o.b, o.i, o.u)
                }
            }
        }
        val tail = rv.substring(bodyStart + pos)
        if (tail.isNotEmpty() && !tail.contains('<')) text.append(unesc(tail))
        val t = text.toString()
        return t to normalize(spans, t.length)
    }
}
