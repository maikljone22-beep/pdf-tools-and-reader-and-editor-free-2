package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.office.Block
import com.smartdocs.editor.core.office.DocModel
import com.smartdocs.editor.core.office.Inline
import com.smartdocs.editor.core.office.PresentationModel
import com.smartdocs.editor.core.office.SlideShape
import java.util.Base64

/** Markdown → [FlowDoc] (CommonMark subset + GFM pipe tables). Pure. */
object MdFlow {

    /** Inline Markdown → runs: **bold**, __bold__, *italic*, _italic_, `code`, [text](url), escapes. */
    fun inline(s: String): List<FRun> {
        val out = ArrayList<FRun>()
        val buf = StringBuilder()
        var bold = false; var italic = false
        fun flush() { if (buf.isNotEmpty()) { out += FRun(buf.toString(), bold, italic); buf.clear() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!|>" -> { buf.append(s[i + 1]); i += 2; continue }
                c == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end > i) { flush(); out += FRun(s.substring(i + 1, end), bold, italic, code = true); i = end + 1; continue }
                }
                (c == '*' || c == '_') && i + 1 < s.length && s[i + 1] == c -> { flush(); bold = !bold; i += 2; continue }
                (c == '*' || c == '_') && (italic || (i + 1 < s.length && !s[i + 1].isWhitespace())) &&
                    (c == '*' || i == 0 || !s[i - 1].isLetterOrDigit()) -> { flush(); italic = !italic; i++; continue }
                c == '!' && i + 1 < s.length && s[i + 1] == '[' -> {
                    val m = link(s, i + 1)
                    if (m != null) { buf.append(m.text); i = m.end; continue }
                }
                c == '[' -> {
                    val m = link(s, i)
                    // only http(s) / mailto / tel / in-document links survive (convert audit C34: no javascript: in outputs)
                    if (m != null && m.text.isNotEmpty()) { flush(); out += FRun(m.text, bold, italic, link = Text6.safeLink(m.url)); i = m.end; continue }
                }
            }
            buf.append(c); i++
        }
        flush()
        return out
    }

    /** `[text](url "title")` starting at [start] (the `[`), with balanced parentheses in the URL (Wikipedia links). */
    class Link(val text: String, val url: String, val end: Int)

    fun link(s: String, start: Int): Link? {
        if (start >= s.length || s[start] != '[') return null
        val close = s.indexOf(']', start + 1)
        if (close < 0 || close + 1 >= s.length || s[close + 1] != '(') return null
        var depth = 0; var j = close + 2
        val url = StringBuilder()
        while (j < s.length) {
            val c = s[j]
            if (c == '(') depth++
            else if (c == ')') { if (depth == 0) break; depth-- }
            url.append(c); j++
        }
        if (j >= s.length) return null
        val target = url.toString().trim().let { u -> Regex("""^(\S+)(\s+["'(].*)?$""").find(u)?.groupValues?.get(1) ?: u }.removePrefix("<").removeSuffix(">")
        if (target.isEmpty()) return null
        return Link(s.substring(start + 1, close), target, j + 1)
    }

    /** A line that is only a picture: `![alt](src)` (convert audit C35: pictures were dropped). */
    private val imageLine = Regex("""^!\[([^\]]*)]\((.+)\)$""")

    /** Bytes of a picture source: a base64 data: URI, or what [resolve] returns for a relative path. */
    fun imageBytes(src: String, resolve: ((String) -> ByteArray?)?): ByteArray? {
        val u = src.trim().substringBefore(' ').removePrefix("<").removeSuffix(">")
        if (u.startsWith("data:image/", true) && u.contains(";base64,")) return runCatching { Base64.getMimeDecoder().decode(u.substringAfter(";base64,")) }.getOrNull()
        if (u.contains("://") || u.startsWith("/") || u.contains("..") || u.startsWith("data:", true)) return null
        return runCatching { resolve?.invoke(java.net.URLDecoder.decode(u, "UTF-8")) }.getOrNull()
    }

    /**
     * Clipboard text is Markdown only with strong signs (convert audit A-M9): a fenced block, a pipe table with its
     * separator row, or at least two kinds of Markdown constructs (headings, lists of two or more items, **bold**,
     * links, quotes). A note with one "1. item" line or "5*3*2" stays plain text.
     */
    fun looksLikeMarkdown(text: String): Boolean {
        val lines = text.lines().map { it.trim() }
        val fence = lines.count { it.startsWith("```") || it.startsWith("~~~") } >= 2
        val table = lines.zipWithNext().any { (a, b) -> a.startsWith("|") && Regex("""^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?$""").matches(b) }
        if (fence || table) return true
        val heading = lines.any { Regex("""^#{1,6}\s+\S""").containsMatchIn(it) }
        val list = lines.count { Regex("""^([-*+]|\d+[.)])\s+\S""").containsMatchIn(it) } >= 2
        val emph = Regex("""(\*\*[^*\s][^*]*\*\*|__[^_\s][^_]*__)""").containsMatchIn(text)
        val links = Regex("""!?\[[^\]]+]\([^)\s]+\)""").containsMatchIn(text)
        val quote = lines.any { it.startsWith("> ") }
        return listOf(heading, list, emph, links, quote).count { it } >= 2
    }

    private fun cells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }

    /**
     * [hardBreaks]: every line break inside a paragraph is kept (clipboard text, convert audit A-M9).
     * [resolve] reads a picture referenced by a relative path (null = only data: URIs are embedded).
     */
    fun parse(md: String, title: String = "", hardBreaks: Boolean = false, resolve: ((String) -> ByteArray?)? = null): FlowDoc {
        val lines = md.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<FBlock>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) { val t = para.toString().trim(); out += FBlock.Para(inline(t), Text6.rtlDominant(t)) }; para.clear() }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]; val t = l.trim()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flush()
                    val fence = t.take(3); val code = StringBuilder(); i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) { code.append(lines[i]).append('\n'); i++ }
                    out += FBlock.Code(code.toString())
                }
                t.isEmpty() -> flush()
                imageLine.matches(t) && imageBytes(imageLine.find(t)!!.groupValues[2], resolve) != null -> {
                    flush()
                    val m = imageLine.find(t)!!
                    val bytes = imageBytes(m.groupValues[2], resolve)!!
                    out += FBlock.Image(bytes, Images6.sniffExt(bytes) ?: "png", alt = m.groupValues[1])
                }
                Regex("""^#{1,6}\s""").containsMatchIn(t) -> {
                    flush()
                    val n = t.takeWhile { it == '#' }.length
                    val h = t.drop(n).trim().trimEnd('#').trim()
                    out += FBlock.Heading(n, inline(h), Text6.rtlDominant(h))
                }
                i + 1 < lines.size && para.isEmpty() && t.isNotEmpty() && Regex("""^(=+|-+)\s*$""").matches(lines[i + 1].trim()) && !t.startsWith("|") -> {
                    out += FBlock.Heading(if (lines[i + 1].trim().startsWith("=")) 1 else 2, inline(t), Text6.rtlDominant(t)); i++
                }
                Regex("""^([-*_])(\s*\1){2,}\s*$""").matches(t) -> { flush(); out += FBlock.Rule }
                t.startsWith(">") -> {
                    flush()
                    val q = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { q.append(lines[i].trim().drop(1).trim()).append(' '); i++ }
                    i--
                    val s = q.toString().trim(); out += FBlock.Quote(inline(s), Text6.rtlDominant(s))
                }
                t.startsWith("|") && i + 1 < lines.size && Regex("""^\|?\s*:?-{2,}""").containsMatchIn(lines[i + 1].trim()) -> {
                    flush()
                    val rows = ArrayList<List<String>>()
                    rows += cells(t); i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i]); i++ }
                    i--
                    out += FBlock.Table(rows.map { r -> r.map { c -> Text6.plain(inline(c)) } }, true, rows.flatten().joinToString(" ").let(Text6::rtlDominant))
                }
                Regex("""^\s*([-*+]|\d+[.)])\s+""").containsMatchIn(l) -> {
                    flush()
                    val indent = l.takeWhile { it == ' ' || it == '\t' }.replace("\t", "    ").length
                    val m = Regex("""^\s*([-*+]|\d+[.)])\s+""").find(l)!!
                    val marker = m.groupValues[1]
                    var text = l.substring(m.value.length)
                    Regex("""^\[([ xX])]\s+""").find(text)?.let { cb -> text = (if (cb.groupValues[1].isNotBlank()) "☑ " else "☐ ") + text.substring(cb.value.length) }
                    val num = if (marker[0].isDigit()) marker.dropLast(1).toIntOrNull() ?: 1 else 0
                    out += FBlock.Item(inline(text), Text6.rtlDominant(text), (indent / 2).coerceIn(0, 8), num)
                }
                l.endsWith("  ") || hardBreaks -> { para.append(t).append('\n') }
                else -> para.append(t).append(' ')
            }
            i++
        }
        flush()
        val docTitle = title.ifBlank { out.firstOrNull { it is FBlock.Heading }?.let { FlowDoc.textOf(it) } ?: "" }
        return FlowDoc(docTitle, out)
    }
}

/** Lenient HTML → [FlowDoc] (headings, paragraphs, lists, quotes, pre, tables, br, b/i, links, data: images). Pure. */
object HtmlFlow {

    private val entity = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0", "laquo" to "«", "raquo" to "»", "hellip" to "…", "mdash" to "—", "ndash" to "–", "rlm" to "\u200F", "lrm" to "\u200E", "zwnj" to "\u200C", "zwj" to "\u200D", "copy" to "©")

    /** A numeric character reference; an invalid one (above U+10FFFF, a lone surrogate, NUL) becomes U+FFFD (audit C19). */
    private fun codePoint(cp: Long?): String =
        if (cp == null || cp <= 0L || cp > 0x10FFFFL || cp in 0xD800L..0xDFFFL) "�" else String(Character.toChars(cp.toInt()))

    fun decode(s: String): String = entity.replace(s) { m ->
        val v = m.groupValues[1]
        when {
            v.startsWith("#x") || v.startsWith("#X") -> codePoint(v.drop(2).take(9).toLongOrNull(16))
            v.startsWith("#") -> codePoint(v.drop(1).take(12).toLongOrNull())
            else -> named[v.lowercase()] ?: m.value
        }
    }

    /** Text of an HTML fragment without tags (block tags become line breaks). */
    fun plain(html: String): String {
        var s = html.replace(Regex("""(?is)<(script|style|head)\b.*?</\1>"""), "")
        s = s.replace(Regex("""(?i)<br\s*/?>"""), "\n").replace(Regex("""(?i)</(p|div|h[1-6]|li|tr|blockquote|pre|section|article)>"""), "\n")
        s = s.replace(Regex("""(?i)</t[dh]>"""), "\t")
        s = decode(s.replace(Regex("""<[^>]+>"""), ""))
        return s.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    private val tagRx = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    fun parse(html0: String, title: String = ""): FlowDoc {
        val docTitle = title.ifBlank { Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html0)?.groupValues?.get(1)?.let { decode(it).trim() } ?: "" }
        val html = html0.replace(Regex("""(?is)<(script|style|head|noscript)\b.*?</\1>"""), "")
        val out = ArrayList<FBlock>()
        val runs = ArrayList<FRun>()
        var bold = 0; var italic = 0; var code = 0; var link: String? = null
        var block = "p"; var level = 0; val listStack = ArrayList<Pair<String, Int>>()
        var pre = false; val preBuf = StringBuilder()
        var table: ArrayList<MutableList<String>>? = null; var cell: StringBuilder? = null; var headerRow = false
        fun text(t: String) {
            if (cell != null) { cell!!.append(t); return }
            if (pre) { preBuf.append(t); return }
            val norm = t.replace(Regex("""\s+"""), " ")
            if (norm.isEmpty()) return
            if (runs.isEmpty() && norm.isBlank()) return
            runs += FRun(norm, bold > 0, italic > 0, code > 0, link)
        }
        fun flush() {
            if (runs.isEmpty()) return
            val merged = ArrayList<FRun>()
            runs.forEach { r -> val l = merged.lastOrNull(); if (l != null && l.copy(text = "") == r.copy(text = "")) merged[merged.size - 1] = l.copy(text = l.text + r.text) else merged += r }
            if (merged.isNotEmpty()) merged[0] = merged[0].copy(text = merged[0].text.trimStart())
            if (merged.isNotEmpty()) merged[merged.size - 1] = merged.last().copy(text = merged.last().text.trimEnd())
            val t = Text6.plain(merged)
            runs.clear()
            if (t.isBlank()) return
            val rtl = Text6.rtlDominant(t)
            out += when {
                block.length == 2 && block[0] == 'h' && block[1].isDigit() -> FBlock.Heading(block[1].digitToInt(), merged, rtl)
                block == "li" -> { val top = listStack.lastOrNull(); if (top != null && top.first == "ol") listStack[listStack.size - 1] = "ol" to top.second + 1; FBlock.Item(merged, rtl, (listStack.size - 1).coerceAtLeast(0), if (top?.first == "ol") top.second + 1 else 0) }
                block == "blockquote" -> FBlock.Quote(merged, rtl)
                else -> FBlock.Para(merged, rtl)
            }
        }
        var pos = 0
        for (m in tagRx.findAll(html)) {
            if (m.range.first > pos) text(decode(html.substring(pos, m.range.first)))
            pos = m.range.last + 1
            if (m.value.startsWith("<!--")) continue
            val close = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            when (tag) {
                "b", "strong" -> bold += if (close) -1 else 1
                "i", "em", "cite" -> italic += if (close) -1 else 1
                "code", "kbd", "tt" -> if (!pre) code += if (close) -1 else 1
                "a" -> link = if (close) null else Regex("""(?i)href\s*=\s*["']([^"']+)["']""").find(attrs)?.groupValues?.get(1)?.let { Text6.safeLink(decode(it)) }?.takeIf { !it.startsWith("#") }
                "br" -> if (cell != null) cell!!.append('\n') else if (pre) preBuf.append('\n') else runs += FRun("\n", bold > 0, italic > 0)
                "p", "div", "section", "article", "header", "footer", "figure", "figcaption", "dd", "dt", "caption" -> { flush(); block = if (close && listStack.isNotEmpty()) "li" else "p" }
                "h1", "h2", "h3", "h4", "h5", "h6" -> { flush(); block = if (close) "p" else tag }
                "blockquote" -> { flush(); block = if (close) "p" else "blockquote" }
                "ul", "ol" -> { flush(); if (close) { if (listStack.isNotEmpty()) listStack.removeAt(listStack.size - 1) } else listStack += tag to 0; block = "p" }
                "li" -> { flush(); block = if (close) "p" else "li" }
                "hr" -> { flush(); out += FBlock.Rule }
                "pre" -> if (!close) { flush(); pre = true; preBuf.clear() } else { pre = false; out += FBlock.Code(decode(preBuf.toString())); preBuf.clear() }
                "table" -> if (!close) { flush(); table = ArrayList(); headerRow = false } else table?.let { t ->
                    val rows = t.filter { r -> r.any { it.isNotBlank() } }
                    if (rows.isNotEmpty()) out += FBlock.Table(rows, headerRow, Text6.rtlDominant(rows.flatten().joinToString(" ")))
                    table = null
                }
                "tr" -> if (!close) table?.add(ArrayList())
                "td", "th" -> if (!close) { cell = StringBuilder(); if (tag == "th" && (table?.size ?: 0) <= 1) headerRow = true } else {
                    val c = cell; cell = null
                    if (c != null) table?.lastOrNull()?.add(decode(c.toString()).replace(Regex("""[ \t]+"""), " ").trim())
                }
                "img" -> if (!close) {
                    val src = Regex("""(?i)src\s*=\s*["']([^"']+)["']""").find(attrs)?.groupValues?.get(1)
                    val alt = Regex("""(?i)alt\s*=\s*["']([^"']*)["']""").find(attrs)?.groupValues?.get(1).orEmpty()
                    if (src != null && src.startsWith("data:image/") && src.contains(";base64,")) {
                        val bytes = runCatching { Base64.getMimeDecoder().decode(src.substringAfter(";base64,")) }.getOrNull()
                        if (bytes != null) { flush(); out += FBlock.Image(bytes, Images6.sniffExt(bytes) ?: "png", alt = decode(alt)) }
                    }
                }
            }
        }
        if (pos < html.length) text(decode(html.substring(pos)))
        flush()
        return FlowDoc(docTitle, out)
    }
}

/** Office models (core/office readers) → [FlowDoc]. Pure. */
object OfficeFlow {

    private fun runsOf(inl: List<Inline>): List<FRun> = inl.mapNotNull {
        when (it) {
            is Inline.Text -> FRun(it.text, it.style.bold, it.style.italic, link = it.link)
            Inline.Tab -> FRun("\t")
            Inline.LineBreak -> FRun("\n")
            else -> null
        }
    }

    fun docx(model: DocModel, title: String): FlowDoc {
        val out = ArrayList<FBlock>()
        fun add(bs: List<Block>) {
            bs.forEach { b ->
                when (b) {
                    is Block.Para -> {
                        val p = b.p
                        if (p.pageBreakBefore) out += FBlock.PageBreak
                        val runs = runsOf(p.inlines)
                        val rtl = p.rtl || Text6.rtlDominant(Text6.plain(runs))
                        p.inlines.filterIsInstance<Inline.Image>().forEach { im -> im.image?.let { d -> out += FBlock.Image(d.bytes, Images6.sniffExt(d.bytes) ?: d.mime.substringAfter('/'), im.widthEmu / 12700f, im.heightEmu / 12700f, im.alt.orEmpty()) } }
                        when {
                            p.heading > 0 -> out += FBlock.Heading(p.heading.coerceIn(1, 6), runs, rtl)
                            p.heading < 0 -> out += FBlock.Heading(1, runs, rtl)
                            p.listLevel >= 0 -> out += FBlock.Item(runs, rtl, p.listLevel, p.listMarker?.filter { it.isDigit() }?.toIntOrNull() ?: 0)
                            else -> out += FBlock.Para(runs, rtl, when (p.align) { com.smartdocs.editor.core.office.Align.CENTER -> FAlign.CENTER; else -> FAlign.START })
                        }
                        if (p.inlines.any { it is Inline.PageBreak }) out += FBlock.PageBreak
                    }
                    is Block.Table -> {
                        val rows = b.rows.map { r -> r.cells.filter { !it.mergedAway }.map { c -> DocModel(c.blocks).plainText.trim() } }
                        out += FBlock.Table(rows, b.rows.firstOrNull()?.header == true, b.rtl || Text6.rtlDominant(rows.flatten().joinToString(" ")))
                    }
                    Block.PageBreak -> out += FBlock.PageBreak
                    Block.SectionBreak -> {}
                }
            }
        }
        add(model.blocks)
        return FlowDoc(model.title?.ifBlank { null } ?: title, out)
    }

    /** Slide texts: (title, other lines) per slide, top-to-bottom shape order. */
    fun slides(p: PresentationModel): List<Pair<String, List<String>>> = p.slides.map { s ->
        val texts = s.shapes.sortedWith(compareBy({ it.y }, { it.x })).mapNotNull { sh ->
            when (sh) {
                is SlideShape.TextBox -> sh.paragraphs.joinToString("\n") { para -> runsOf(para.runs).joinToString("") { it.text } }.trim().takeIf { it.isNotEmpty() }
                is SlideShape.Table -> sh.rows.joinToString("\n") { r -> r.joinToString(" | ") { c -> c.joinToString(" ") { para -> runsOf(para.runs).joinToString("") { it.text } } } }.takeIf { it.isNotBlank() }
                else -> null
            }
        }
        (texts.firstOrNull() ?: "") to texts.drop(1).flatMap { it.split('\n') }.filter { it.isNotBlank() }
    }
}
