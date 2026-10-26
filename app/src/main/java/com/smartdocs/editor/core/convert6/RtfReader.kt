package com.smartdocs.editor.core.convert6

import java.nio.charset.Charset

/**
 * RTF → [FlowDoc]: paragraphs, bold / italic, RTL paragraphs (\rtlpar), tables (\cell / \row), page breaks,
 * Unicode (\uN) and code-page (\'hh, \ansicpg, font \fcharset 178 = Arabic) text. Pictures and fields are skipped
 * (field results are kept). Pure.
 */
object RtfReader {

    private class State(var bold: Boolean = false, var italic: Boolean = false, var skip: Boolean = false, var uc: Int = 1, var charset: Charset? = null) {
        fun copy() = State(bold, italic, skip, uc, charset)
    }

    private val skipDest = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "headerl", "headerr", "headerf", "footerl", "footerr", "footerf",
        "listtable", "listoverridetable", "revtbl", "rsidtbl", "generator", "xmlnstbl", "themedata", "colorschememapping", "latentstyles", "datastore", "fldinst", "bkmkstart", "bkmkend", "nonshppict", "shppict", "filetbl", "mmathPr", "pgdsctbl")

    private fun cs(cp: Int): Charset? = runCatching { Charset.forName(if (cp == 65001) "UTF-8" else "windows-$cp") }.getOrNull()

    private fun charsetFor(fcharset: Int): Charset? = when (fcharset) {
        178, 179, 180 -> cs(1256); 177, 181 -> cs(1255); 161 -> cs(1253); 162 -> cs(1254); 204 -> cs(1251); 238 -> cs(1250); 186 -> cs(1257); 222 -> cs(874)
        128 -> runCatching { Charset.forName("Shift_JIS") }.getOrNull(); 134 -> runCatching { Charset.forName("GBK") }.getOrNull(); 129 -> runCatching { Charset.forName("x-windows-949") }.getOrNull()
        else -> null
    }

    fun parse(rtf: String, title: String = ""): FlowDoc {
        // font table: \fN ... \fcharsetM
        val fontCs = HashMap<Int, Charset>()
        Regex("""\\f(\d+)[^;{}]*?\\fcharset(\d+)""").findAll(rtf.substringAfter("\\fonttbl", "").take(20000)).forEach { m ->
            charsetFor(m.groupValues[2].toInt())?.let { fontCs[m.groupValues[1].toInt()] = it }
        }
        var docCs: Charset = Regex("""\\ansicpg(\d+)""").find(rtf)?.groupValues?.get(1)?.toIntOrNull()?.let { cs(it) } ?: cs(1252) ?: Charsets.ISO_8859_1
        val out = ArrayList<FBlock>()
        val runs = ArrayList<FRun>()
        val text = StringBuilder()
        var st = State()
        val stack = ArrayList<State>()
        var rtlPar = false; var center = false; var inTable = false
        val row = ArrayList<String>(); val rows = ArrayList<List<String>>()
        val cell = StringBuilder()
        val bytes = java.io.ByteArrayOutputStream()
        var skipChars = 0

        fun flushBytes() {
            if (bytes.size() == 0) return
            val s = String(bytes.toByteArray(), st.charset ?: docCs)
            bytes.reset()
            if (inTable) cell.append(s) else text.append(s)
        }
        fun flushRun() {
            flushBytes()
            if (text.isNotEmpty()) { runs += FRun(text.toString(), st.bold, st.italic); text.clear() }
        }
        fun emit(s: String) { flushBytes(); if (inTable) cell.append(s) else text.append(s) }
        fun endTable() {
            if (rows.isNotEmpty()) out += FBlock.Table(ArrayList(rows), false, rtlPar || Text6.rtlDominant(rows.flatten().joinToString(" ")))
            rows.clear(); row.clear()
        }
        fun endPara() {
            flushRun()
            if (!inTable && rows.isNotEmpty()) { endTable(); if (runs.isEmpty()) return }
            val t = Text6.plain(runs)
            out += FBlock.Para(if (t.isBlank()) emptyList() else ArrayList(runs), rtlPar || Text6.rtlDominant(t), if (center) FAlign.CENTER else FAlign.START)
            runs.clear()
        }

        var i = 0
        val n = rtf.length
        while (i < n) {
            val c = rtf[i]
            when (c) {
                '{' -> { flushRun(); stack += st.copy(); i++ }
                '}' -> { flushRun(); st = if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else State(); i++ }
                '\\' -> {
                    if (i + 1 >= n) { i++; continue }
                    val d = rtf[i + 1]
                    when {
                        d == '\'' && i + 3 < n -> {
                            val v = rtf.substring(i + 2, i + 4).toIntOrNull(16)
                            i += 4
                            if (st.skip) continue
                            if (skipChars > 0) { skipChars--; continue }
                            if (v != null) bytes.write(v)
                        }
                        d == '*' -> { st.skip = true; i += 2 }
                        d == '\\' || d == '{' || d == '}' -> { if (!st.skip) emit(d.toString()); i += 2 }
                        d == '~' -> { if (!st.skip) emit("\u00A0"); i += 2 }
                        d == '-' || d == '_' -> { if (!st.skip && d == '_') emit("-"); i += 2 }
                        d == '\n' || d == '\r' -> { if (!st.skip) endPara(); i += 2 }
                        d.isLetter() -> {
                            var j = i + 1
                            while (j < n && rtf[j].isLetter() && rtf[j].code < 128) j++
                            val word = rtf.substring(i + 1, j)
                            var k = j
                            if (k < n && (rtf[k] == '-' || rtf[k].isDigit())) { k++; while (k < n && rtf[k].isDigit()) k++ }
                            val param = rtf.substring(j, k).toIntOrNull()
                            if (k < n && rtf[k] == ' ') k++
                            i = k
                            if (word in skipDest) { st.skip = true; continue }
                            if (st.skip) continue
                            when (word) {
                                "par", "sect" -> if (inTable) cell.append('\n') else endPara()
                                "line" -> emit("\n")
                                "tab" -> emit("\t")
                                "page" -> { endPara(); out += FBlock.PageBreak }
                                "b" -> { flushRun(); st.bold = param != 0 }
                                "i" -> { flushRun(); st.italic = param != 0 }
                                "plain" -> { flushRun(); st.bold = false; st.italic = false }
                                "rtlpar" -> rtlPar = true
                                "ltrpar" -> rtlPar = false
                                "qc" -> center = true
                                "pard" -> { center = false; rtlPar = false }
                                "intbl" -> { if (!inTable) { flushRun(); if (runs.isNotEmpty()) endPara() }; inTable = true }
                                "cell" -> { flushBytes(); row += cell.toString().trim(); cell.clear() }
                                "row" -> { flushBytes(); if (cell.isNotBlank()) { row += cell.toString().trim(); cell.clear() }; rows += ArrayList(row); row.clear(); inTable = false }
                                "trowd" -> { inTable = true }
                                "u" -> if (param != null) { emit(String(Character.toChars(if (param < 0) param + 65536 else param))); skipChars = st.uc }
                                "uc" -> st.uc = param ?: 1
                                "f" -> { flushBytes(); st.charset = param?.let { fontCs[it] } }
                                "ansicpg" -> param?.let { p -> cs(p)?.let { docCs = it } }
                                "emdash" -> emit("\u2014"); "endash" -> emit("\u2013"); "bullet" -> emit("\u2022")
                                "lquote" -> emit("\u2018"); "rquote" -> emit("\u2019"); "ldblquote" -> emit("\u201C"); "rdblquote" -> emit("\u201D")
                                "rtlmark" -> emit("\u200F"); "ltrmark" -> emit("\u200E")
                            }
                        }
                        else -> i += 2
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (st.skip) { i++; continue }
                    if (skipChars > 0) { skipChars--; i++; continue }
                    if (!inTable && rows.isNotEmpty()) endTable()
                    emit(c.toString()); i++
                }
            }
        }
        flushRun()
        if (rows.isNotEmpty()) endTable()
        if (runs.isNotEmpty()) endPara()
        // trailing empty paragraphs are noise
        while (out.lastOrNull().let { it is FBlock.Para && it.runs.isEmpty() }) out.removeAt(out.size - 1)
        return FlowDoc(title, out)
    }

    /** RTF bytes are 7-bit; decode as Latin-1 so \'hh escapes survive untouched. */
    fun parse(bytes: ByteArray, title: String = ""): FlowDoc = parse(String(bytes, Charsets.ISO_8859_1), title)
}
