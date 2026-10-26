package com.smartdocs.editor.core.convert6

import java.util.Base64

/*
 * A small reflowable document model shared by the Convert hub writers (DOCX / ODT / EPUB / Markdown / HTML / TXT)
 * and readers (Markdown, HTML, RTF, ODF, Word 97, PDF layout). Pure Kotlin.
 */

data class FRun(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val link: String? = null)

enum class FAlign { START, CENTER, END }

sealed class FBlock {
    /** [level] 1..6. */
    data class Heading(val level: Int, val runs: List<FRun>, val rtl: Boolean) : FBlock()
    data class Para(val runs: List<FRun>, val rtl: Boolean, val align: FAlign = FAlign.START) : FBlock()
    /** [number] > 0 for an ordered item. */
    data class Item(val runs: List<FRun>, val rtl: Boolean, val level: Int = 0, val number: Int = 0) : FBlock()
    data class Quote(val runs: List<FRun>, val rtl: Boolean) : FBlock()
    data class Code(val text: String) : FBlock()
    data class Table(val rows: List<List<String>>, val header: Boolean, val rtl: Boolean) : FBlock()
    class Image(val bytes: ByteArray, val ext: String, val widthPt: Float = 0f, val heightPt: Float = 0f, val alt: String = "") : FBlock()
    object PageBreak : FBlock()
    object Rule : FBlock()
    /** Start of a chapter (EPUB file split, outline entry). */
    data class Chapter(val title: String) : FBlock()
}

class FlowDoc(val title: String, val blocks: List<FBlock>, val author: String = "") {

    val rtl: Boolean get() {
        var r = 0; var l = 0
        blocks.forEach { b -> textOf(b)?.let { s -> s.forEach { c -> if (Text6.isRtlChar(c)) r++ else if (c.isLetter()) l++ } } }
        return r > l
    }

    /** Blocks split into chapters: explicit [FBlock.Chapter] markers, else level-1 headings, else one chapter. */
    fun chapters(): List<Pair<String, List<FBlock>>> {
        val hasMarkers = blocks.any { it is FBlock.Chapter }
        val out = ArrayList<Pair<String, MutableList<FBlock>>>()
        fun start(t: String) { out += t to ArrayList() }
        blocks.forEach { b ->
            when {
                b is FBlock.Chapter -> start(b.title)
                !hasMarkers && b is FBlock.Heading && b.level == 1 && (out.isEmpty() || out.last().second.isNotEmpty()) -> { start(Text6.plain(b.runs)); out.last().second += b }
                else -> { if (out.isEmpty()) start(title); out.last().second += b }
            }
        }
        return out.filter { it.second.isNotEmpty() || out.size == 1 }.ifEmpty { listOf(title to mutableListOf()) }
    }

    companion object {
        fun textOf(b: FBlock): String? = when (b) {
            is FBlock.Heading -> Text6.plain(b.runs)
            is FBlock.Para -> Text6.plain(b.runs)
            is FBlock.Item -> Text6.plain(b.runs)
            is FBlock.Quote -> Text6.plain(b.runs)
            is FBlock.Table -> b.rows.joinToString(" ") { it.joinToString(" ") }
            else -> null
        }

        /** Plain text paragraphs (one block per non-empty line group) from a text file. */
        fun fromText(text: String, title: String): FlowDoc {
            val blocks = ArrayList<FBlock>()
            text.replace("\r\n", "\n").replace('\r', '\n').split('\u000C').forEachIndexed { pi, page ->
                if (pi > 0) blocks += FBlock.PageBreak
                page.split('\n').forEach { line ->
                    val t = line.trimEnd()
                    blocks += FBlock.Para(if (t.isEmpty()) emptyList() else listOf(FRun(t)), Text6.rtlDominant(t))
                }
            }
            return FlowDoc(title, blocks)
        }
    }
}

object Text6 {
    fun isRtlChar(c: Char): Boolean {
        val d = Character.getDirectionality(c)
        return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }

    fun rtlDominant(s: String): Boolean { var r = 0; var l = 0; s.forEach { if (isRtlChar(it)) r++ else if (it.isLetter()) l++ }; return r > l }

    fun plain(runs: List<FRun>) = runs.joinToString("") { it.text }

    fun xml(s: String): String = buildString(s.length + 16) {
        s.forEach { c ->
            when {
                c == '<' -> append("&lt;"); c == '>' -> append("&gt;"); c == '&' -> append("&amp;"); c == '"' -> append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> append(c)
                c.code < 0x20 || c.code == 0xFFFE || c.code == 0xFFFF -> Unit
                else -> append(c)
            }
        }
    }
}

/** Markdown writer (GitHub flavour: pipe tables, fenced code). */
object FlowMarkdown {

    private fun escInline(s: String) = s.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`").replace("|", "\\|")

    fun runs(rs: List<FRun>): String = buildString {
        rs.forEach { r ->
            if (r.text.isEmpty()) return@forEach
            val lead = r.text.takeWhile { it == ' ' }; val trail = r.text.takeLastWhile { it == ' ' }
            val core = r.text.trim()
            if (core.isEmpty()) { append(r.text); return@forEach }
            var t = if (r.code) "`" + core.replace("`", "'") + "`" else escInline(core)
            if (r.italic && !r.code) t = "*$t*"
            if (r.bold && !r.code) t = "**$t**"
            if (r.link != null) t = "[$t](${r.link})"
            append(lead).append(t).append(trail)
        }
    }

    fun write(doc: FlowDoc, imageName: (Int, FBlock.Image) -> String? = { _, _ -> null }): String = buildString {
        var img = 0
        var prevItem = false
        doc.blocks.forEach { b ->
            if (prevItem && b !is FBlock.Item) append('\n')
            prevItem = b is FBlock.Item
            when (b) {
                is FBlock.Heading -> append("#".repeat(b.level.coerceIn(1, 6))).append(' ').append(runs(b.runs).replace('\n', ' ')).append("\n\n")
                is FBlock.Para -> { val t = runs(b.runs); if (t.isNotBlank()) append(t.replace("\n", "  \n")).append("\n\n") }
                is FBlock.Item -> append("  ".repeat(b.level)).append(if (b.number > 0) "${b.number}. " else "- ").append(runs(b.runs).replace('\n', ' ')).append('\n')
                is FBlock.Quote -> append("> ").append(runs(b.runs).replace("\n", "\n> ")).append("\n\n")
                is FBlock.Code -> append("```\n").append(b.text.trimEnd()).append("\n```\n\n")
                is FBlock.Table -> if (b.rows.isNotEmpty()) {
                    val cols = b.rows.maxOf { it.size }
                    fun row(r: List<String>) = "| " + (0 until cols).joinToString(" | ") { escInline(r.getOrElse(it) { "" }.replace('\n', ' ')) } + " |\n"
                    append(row(b.rows[0]))
                    append("|").append((0 until cols).joinToString("|") { " --- " }).append("|\n")
                    b.rows.drop(1).forEach { append(row(it)) }
                    append('\n')
                }
                is FBlock.Image -> { img++; val n = imageName(img, b); if (n != null) append("![").append(escInline(b.alt)).append("](").append(n).append(")\n\n") }
                FBlock.PageBreak -> append("\n---\n\n")
                FBlock.Rule -> append("---\n\n")
                is FBlock.Chapter -> if (b.title.isNotBlank()) append("# ").append(escInline(b.title)).append("\n\n")
            }
        }
    }.trimEnd() + "\n"
}

/** Plain-text writer. */
object FlowText {
    fun write(doc: FlowDoc): String = buildString {
        doc.blocks.forEach { b ->
            when (b) {
                is FBlock.Heading, is FBlock.Para, is FBlock.Quote -> append(FlowDoc.textOf(b)).append('\n')
                is FBlock.Item -> append("  ".repeat(b.level)).append(if (b.number > 0) "${b.number}. " else "• ").append(Text6.plain(b.runs)).append('\n')
                is FBlock.Code -> append(b.text).append('\n')
                is FBlock.Table -> { b.rows.forEach { append(it.joinToString("\t") { c -> c.replace('\n', ' ') }).append('\n') }; append('\n') }
                FBlock.PageBreak -> append("\n\u000C\n")
                FBlock.Rule -> append("\n")
                is FBlock.Chapter -> append('\n').append(b.title).append("\n\n")
                is FBlock.Image -> {}
            }
        }
    }
}

/** HTML writer (self-contained, images as data URIs); also used to print hub inputs to PDF through the WebView. */
object FlowHtml {

    fun runs(rs: List<FRun>): String = buildString {
        rs.forEach { r ->
            var t = Text6.xml(r.text).replace("\n", "<br>")
            if (r.code) t = "<code>$t</code>"
            if (r.italic) t = "<i>$t</i>"
            if (r.bold) t = "<b>$t</b>"
            if (r.link != null) t = "<a href=\"" + Text6.xml(r.link) + "\">$t</a>"
            append(t)
        }
    }

    private fun dir(rtl: Boolean) = if (rtl) "rtl" else "ltr"

    fun body(blocks: List<FBlock>): String = buildString {
        var list: String? = null
        fun close() { list?.let { append("</").append(it).append(">\n") }; list = null }
        blocks.forEach { b ->
            if (b !is FBlock.Item) close()
            when (b) {
                is FBlock.Heading -> append("<h${b.level.coerceIn(1, 6)} dir=\"${dir(b.rtl)}\">").append(runs(b.runs)).append("</h${b.level.coerceIn(1, 6)}>\n")
                is FBlock.Para -> if (b.runs.isEmpty() || Text6.plain(b.runs).isBlank()) append("<p>&#160;</p>\n") else {
                    val al = when (b.align) { FAlign.CENTER -> " style=\"text-align:center\""; FAlign.END -> " style=\"text-align:end\""; else -> "" }
                    append("<p dir=\"${dir(b.rtl)}\"$al>").append(runs(b.runs)).append("</p>\n")
                }
                is FBlock.Item -> {
                    val tag = if (b.number > 0) "ol" else "ul"
                    if (list != tag) { close(); append("<").append(tag).append(" dir=\"${dir(b.rtl)}\">\n"); list = tag }
                    append("<li style=\"margin-inline-start:${b.level * 20}px\">").append(runs(b.runs)).append("</li>\n")
                }
                is FBlock.Quote -> append("<blockquote dir=\"${dir(b.rtl)}\">").append(runs(b.runs)).append("</blockquote>\n")
                is FBlock.Code -> append("<pre dir=\"ltr\"><code>").append(Text6.xml(b.text)).append("</code></pre>\n")
                is FBlock.Table -> {
                    append("<table dir=\"${dir(b.rtl)}\">\n")
                    b.rows.forEachIndexed { i, r ->
                        val tag = if (i == 0 && b.header) "th" else "td"
                        append("<tr>").append(r.joinToString("") { "<$tag dir=\"auto\">" + Text6.xml(it).replace("\n", "<br>") + "</$tag>" }).append("</tr>\n")
                    }
                    append("</table>\n")
                }
                is FBlock.Image -> {
                    val mime = Images6.mimeOf(b.ext)
                    val w = if (b.widthPt > 0) " style=\"width:${b.widthPt.toInt()}pt;max-width:100%\"" else ""
                    append("<img alt=\"").append(Text6.xml(b.alt)).append("\"$w src=\"data:$mime;base64,").append(Base64.getEncoder().encodeToString(b.bytes)).append("\">\n")
                }
                FBlock.PageBreak -> append("<div style=\"page-break-after:always\"></div>\n")
                FBlock.Rule -> append("<hr>\n")
                is FBlock.Chapter -> if (b.title.isNotBlank()) append("<h1 class=\"chapter\" dir=\"auto\">").append(Text6.xml(b.title)).append("</h1>\n")
            }
        }
        close()
    }

    const val CSS = "body{font-family:'Noto Naskh Arabic','Noto Sans','Segoe UI',Tahoma,sans-serif;font-size:12pt;line-height:1.6;margin:0 auto;max-width:900px;padding:8px 16px;word-wrap:break-word}" +
        "img{max-width:100%;height:auto;display:block;margin:8px auto}pre{white-space:pre-wrap;background:#f4f4f4;padding:8px;border-radius:4px}" +
        "table{border-collapse:collapse;margin:8px 0}td,th{border:1px solid #999;padding:4px 8px;vertical-align:top}th{background:#e8eef6}" +
        "blockquote{border-inline-start:4px solid #ccc;margin:8px 0;padding:4px 12px;color:#444}h1.chapter{page-break-before:always}"

    fun write(doc: FlowDoc): String {
        val rtl = doc.rtl
        return "<!DOCTYPE html>\n<html lang=\"${if (rtl) "ar" else "en"}\" dir=\"${dir(rtl)}\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>" + Text6.xml(doc.title) + "</title><style>" + CSS + "</style></head><body>\n" + body(doc.blocks) + "</body></html>\n"
    }
}
