package com.smartdocs.editor.core.tools5

/*
 * Minimal RTF 1.9 writer: paragraphs of styled runs, right-to-left paragraphs, headings, page breaks and simple
 * tables. Non-ASCII text is written as \uN? escapes so the file is pure 7-bit ASCII. Plain Kotlin, unit-tested.
 */

data class RtfRun(val text: String, val bold: Boolean = false, val italic: Boolean = false, val size: Float = 11f)

enum class RtfAlign { START, CENTER, END, JUSTIFY }

sealed class RtfBlock

data class RtfPara(val runs: List<RtfRun>, val rtl: Boolean = false, val heading: Int = 0, val align: RtfAlign = RtfAlign.START) : RtfBlock()

/** Rows of cell texts; [rtl] mirrors the column order. */
data class RtfTable(val rows: List<List<String>>, val rtl: Boolean = false, val header: Boolean = false, val widthTwips: Int = 9000) : RtfBlock()

object RtfPageBreak : RtfBlock()

object RtfWriter {

    /** Escape text for an RTF body: control characters, braces, backslash, non-ASCII as \uN? (signed 16-bit). */
    fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) {
            val k = c.code
            when {
                c == '\\' -> append("\\\\")
                c == '{' -> append("\\{")
                c == '}' -> append("\\}")
                c == '\n' -> append("\\line ")
                c == '\t' -> append("\\tab ")
                c == '\r' -> {}
                TextNorm.isInvisible(c) && k != 0x200F && k != 0x200E -> {}
                k < 0x20 -> {}
                k < 0x80 -> append(c)
                else -> { append("\\u").append(if (k > 32767) k - 65536 else k).append('?') }
            }
        }
    }

    private fun halfPoints(size: Float) = (size * 2).toInt().coerceIn(8, 400)

    fun write(blocks: List<RtfBlock>, title: String = "", author: String = ""): String = buildString {
        append("{\\rtf1\\ansi\\ansicpg1252\\deff0\\deflang1033\\adeflang1025\n")
        append("{\\fonttbl{\\f0\\fswiss\\fcharset0 Arial;}{\\f1\\fnil\\fcharset178 Arial;}}\n")
        append("{\\colortbl;\\red0\\green0\\blue0;\\red200\\green200\\blue200;}\n")
        if (title.isNotBlank() || author.isNotBlank()) {
            append("{\\info")
            if (title.isNotBlank()) append("{\\title ").append(escape(title)).append('}')
            if (author.isNotBlank()) append("{\\author ").append(escape(author)).append('}')
            append("}\n")
        }
        append("\\paperw11906\\paperh16838\\margl1134\\margr1134\\margt1134\\margb1134\\viewkind4\\uc1\n")
        blocks.forEach { b ->
            when (b) {
                is RtfPara -> para(b)
                is RtfTable -> table(b)
                RtfPageBreak -> append("\\page\n")
            }
        }
        append("}")
    }

    private fun StringBuilder.para(p: RtfPara) {
        append("\\pard\\plain")
        append(if (p.rtl) "\\rtlpar" else "\\ltrpar")
        append(
            when (p.align) {
                RtfAlign.CENTER -> "\\qc"
                RtfAlign.JUSTIFY -> "\\qj"
                RtfAlign.START -> if (p.rtl) "\\qr" else "\\ql"
                RtfAlign.END -> if (p.rtl) "\\ql" else "\\qr"
            },
        )
        if (p.heading in 1..6) append("\\outlinelevel").append(p.heading - 1)
        append("\\sa120 ")
        p.runs.forEach { r -> run(r, p.rtl, p.heading) }
        append("\\par\n")
    }

    private fun StringBuilder.run(r: RtfRun, rtlPara: Boolean, heading: Int) {
        if (r.text.isEmpty()) return
        val arabic = TextNorm.hasArabic(r.text)
        val size = if (heading in 1..6) maxOf(r.size, 22f - heading * 2) else r.size
        append('{')
        append(if (arabic || rtlPara) "\\rtlch" else "\\ltrch")
        append(if (arabic) "\\f1" else "\\f0")
        val hp = halfPoints(size)
        append("\\fs").append(hp).append("\\afs").append(hp)
        if (r.bold || heading in 1..6) append("\\b\\ab")
        if (r.italic) append("\\i\\ai")
        append(' ').append(escape(r.text)).append('}')
    }

    private fun StringBuilder.table(t: RtfTable) {
        val cols = t.rows.maxOfOrNull { it.size } ?: return
        if (cols == 0) return
        val w = t.widthTwips / cols
        t.rows.forEachIndexed { ri, row0 ->
            val row = row0 + List(cols - row0.size) { "" }
            val cells = if (t.rtl) row.reversed() else row
            append("\\trowd\\trgaph108")
            if (t.rtl) append("\\rtlrow")
            for (c in 1..cols) append("\\clbrdrt\\brdrs\\clbrdrl\\brdrs\\clbrdrb\\brdrs\\clbrdrr\\brdrs\\cellx").append(w * c)
            append('\n')
            cells.forEach { txt ->
                val rtl = TextNorm.hasArabic(txt)
                append("\\pard\\intbl").append(if (rtl) "\\rtlpar\\qr" else "\\ltrpar\\ql").append(' ')
                run(RtfRun(txt, bold = t.header && ri == 0), rtl, 0)
                append("\\cell\n")
            }
            append("\\row\n")
        }
        append("\\pard\\par\n")
    }
}
