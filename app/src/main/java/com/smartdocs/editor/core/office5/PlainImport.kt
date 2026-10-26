package com.smartdocs.editor.core.office5

import java.io.File

/**
 * Read-only import of documents the Word editor cannot edit natively: OpenDocument text (.odt), RTF and
 * plain text become a list of paragraphs, which the UI shows and can turn into an editable .docx copy.
 */
object PlainImport {
    class Doc(val paragraphs: List<String>, val headings: Set<Int> = emptySet())

    fun read(file: File): Doc = when (file.extension.lowercase()) {
        "odt" -> odt(file.readBytes())
        "rtf" -> rtf(file.readBytes())
        else -> Doc(com.smartdocs.editor.core.office.OoxmlReader.readText(file).text.replace("\r\n", "\n").split('\n'))
    }

    /** ODF text: text:p / text:h (with spans, tabs, spaces, line breaks) in content.xml, lists flattened. */
    fun odt(bytes: ByteArray): Doc {
        val pkg = OPackage.read(bytes)
        val root = pkg.dom("content.xml")?.root ?: return Doc(emptyList())
        val body = root.find("text") ?: root
        val out = ArrayList<String>(); val heads = HashSet<Int>()
        fun walk(e: XElem) {
            for (k in e.elems()) when (k.local) {
                "p", "h" -> { if (k.local == "h") heads.add(out.size); out.add(inline(k)) }
                "list", "list-item", "section", "table", "table-row", "table-cell", "table-header-rows", "table-rows" -> walk(k)
            }
        }
        walk(body)
        return Doc(out, heads)
    }

    private fun inline(e: XElem): String = buildString {
        for (n in e.kids) when (n) {
            is XText -> append(n.text)
            is XElem -> when (n.local) {
                "tab" -> append('\t'); "line-break" -> append('\n')
                "s" -> append(" ".repeat(n.attr("c")?.toIntOrNull() ?: 1))
                "note", "annotation" -> {}
                else -> append(inline(n))
            }
            else -> {}
        }
    }

    /** RTF to text: control words dropped, \par / \line breaks, \'hh and \uN escapes decoded (ansicpg aware). */
    fun rtf(bytes: ByteArray): Doc {
        val s = String(bytes, Charsets.ISO_8859_1)
        val cp = Regex("""\\ansicpg(\d+)""").find(s)?.groupValues?.get(1)?.let { runCatching { charset("windows-$it") }.getOrNull() } ?: runCatching { charset("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
        val sb = StringBuilder(); val pending = java.io.ByteArrayOutputStream()
        fun flush() { if (pending.size() > 0) { sb.append(String(pending.toByteArray(), cp)); pending.reset() } }
        var i = 0; var depth = 0; var skipDepth = -1; var ucSkip = 1; var skip = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '{' -> { depth++; i++ }
                c == '}' -> { if (depth == skipDepth) skipDepth = -1; depth--; i++ }
                c == '\\' -> {
                    val n = s.getOrNull(i + 1) ?: break
                    when {
                        n == '\'' -> { val hex = s.substring(i + 2, minOf(i + 4, s.length)); if (skipDepth < 0 && skip == 0) hex.toIntOrNull(16)?.let { pending.write(it) } else if (skip > 0) skip--; i += 4 }
                        n == '*' -> { skipDepth = depth; i += 2 }
                        n == '\\' || n == '{' || n == '}' -> { flush(); if (skipDepth < 0) sb.append(n); i += 2 }
                        n == '~' -> { flush(); if (skipDepth < 0) sb.append('\u00A0'); i += 2 }
                        n.isLetter() -> {
                            var j = i + 1; while (j < s.length && s[j].isLetter()) j++
                            val word = s.substring(i + 1, j)
                            var k = j; if (k < s.length && (s[k] == '-' || s[k].isDigit())) { k++; while (k < s.length && s[k].isDigit()) k++ }
                            val arg = s.substring(j, k).toIntOrNull()
                            if (k < s.length && s[k] == ' ') k++
                            flush()
                            if (skipDepth < 0) when (word) {
                                "par", "line", "sect", "page" -> sb.append('\n')
                                "tab" -> sb.append('\t')
                                "uc" -> ucSkip = arg ?: 1
                                "u" -> { arg?.let { sb.append((if (it < 0) it + 65536 else it).toChar()) }; skip = ucSkip }
                                "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "themedata", "listtable", "listoverridetable" -> skipDepth = depth
                            }
                            i = k
                        }
                        else -> i += 2
                    }
                }
                c == '\r' || c == '\n' -> i++
                else -> { if (skipDepth < 0) { if (skip > 0) skip-- else pending.write(c.code) }; i++ }
            }
        }
        flush()
        return Doc(sb.toString().trimEnd('\n').split('\n'))
    }
}
