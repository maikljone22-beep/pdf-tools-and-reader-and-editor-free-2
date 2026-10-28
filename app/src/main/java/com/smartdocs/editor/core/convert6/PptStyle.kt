package com.smartdocs.editor.core.convert6

import com.smartdocs.editor.core.office.Align
import com.smartdocs.editor.core.office.Inline
import com.smartdocs.editor.core.office.RunStyle
import com.smartdocs.editor.core.office.SlideParagraph

/**
 * PowerPoint 97-2003 text styling ([MS-PPT] StyleTextPropAtom): paragraph runs (alignment; every other field skipped
 * by its mask) and character runs (bold, italic, underline, size, RGB colour). Scheme colours and fonts are not
 * resolved. Pure; a malformed atom gives null (the text then keeps the default style).
 */
object PptStyle {
    data class ParaRun(val count: Int, val align: Align?)
    data class CharRun(val count: Int, val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false,
                       val sizePt: Float? = null, val color: String? = null)
    class Styles(val paras: List<ParaRun>, val chars: List<CharRun>)

    private fun u16(b: ByteArray, o: Int) = CfbReader.le16(b, o)
    private fun u32(b: ByteArray, o: Int) = CfbReader.le32(b, o)

    /** The atom body [start]..[end] for a text of [textLen] characters (runs cover textLen + 1). */
    fun parse(b: ByteArray, start: Int, end: Int, textLen: Int): Styles? = runCatching {
        var o = start
        fun need(n: Int) { if (o + n > end) throw IndexOutOfBoundsException() }
        val total = textLen + 1
        val paras = ArrayList<ParaRun>(); var covered = 0
        while (covered < total) {
            need(10)
            val count = u32(b, o); o += 4; o += 2                       // count, indentLevel
            val m = u32(b, o); o += 4
            var align: Align? = null
            if (m and 0xF != 0) { need(2); o += 2 }                     // bulletFlags
            if (m and 0x80 != 0) { need(2); o += 2 }                    // bulletChar
            if (m and 0x10 != 0) { need(2); o += 2 }                    // bulletFontRef
            if (m and 0x40 != 0) { need(2); o += 2 }                    // bulletSize
            if (m and 0x20 != 0) { need(4); o += 4 }                    // bulletColor
            if (m and 0x800 != 0) { need(2); align = when (u16(b, o)) { 1 -> Align.CENTER; 2 -> Align.RIGHT; 3 -> Align.JUSTIFY; 0 -> Align.LEFT; else -> null }; o += 2 }
            for (bit in intArrayOf(0x1000, 0x2000, 0x4000, 0x100, 0x400, 0x8000)) if (m and bit != 0) { need(2); o += 2 }   // spacing, margins, tab size
            if (m and 0x100000 != 0) { need(2); val n = u16(b, o); o += 2; need(n * 4); o += n * 4 }                         // tabStops
            if (m and 0x10000 != 0) { need(2); o += 2 }                 // fontAlign
            if (m and 0xE0000 != 0) { need(2); o += 2 }                 // wrapFlags
            if (m and 0x200000 != 0) { need(2); o += 2 }                // textDirection
            if (count <= 0) throw IllegalStateException()
            paras += ParaRun(count, align); covered += count
        }
        val chars = ArrayList<CharRun>(); covered = 0
        while (covered < total && o + 8 <= end) {
            val count = u32(b, o); o += 4
            val m = u32(b, o); o += 4
            var style = 0; var size: Float? = null; var color: String? = null
            if (m and 0x3FFF != 0) { need(2); style = u16(b, o); o += 2 }
            for (bit in intArrayOf(0x10000, 0x200000, 0x400000, 0x800000)) if (m and bit != 0) { need(2); o += 2 }          // font refs
            if (m and 0x20000 != 0) { need(2); size = u16(b, o).toFloat().takeIf { it in 1f..4000f }; o += 2 }
            if (m and 0x40000 != 0) {
                need(4)
                val r = b[o].toInt() and 0xFF; val g = b[o + 1].toInt() and 0xFF; val bl = b[o + 2].toInt() and 0xFF; val idx = b[o + 3].toInt() and 0xFF
                if (idx == 0xFE) color = String.format("%02X%02X%02X", r, g, bl)
                o += 4
            }
            if (m and 0x80000 != 0) { need(2); o += 2 }                 // position
            if (m and 0x100000 != 0) { need(4); o += 4 }                // pp10runid
            if (m and 0x1000000 != 0) { need(2); o += 2 }               // newEAFontRef
            if (m and 0x2000000 != 0) { need(2); o += 2 }               // csFontRef
            if (m and 0x4000000 != 0) { need(4); o += 4 }               // pp11ext
            if (count <= 0) throw IllegalStateException()
            chars += CharRun(count, style and 1 != 0, style and 2 != 0, style and 4 != 0, size, color)
            covered += count
        }
        Styles(paras, chars)
    }.getOrNull()

    /** [text] (paragraphs split by CR / VT) as slide paragraphs with the runs' styles and alignment. */
    fun paragraphs(text: String, styles: Styles?): List<SlideParagraph> {
        val t = text.replace('\u000B', '\r').replace('\n', '\r')
        val out = ArrayList<SlideParagraph>()
        var pos = 0
        val starts = ArrayList<Int>()
        for (line in t.split('\r')) { starts += pos; pos += line.length + 1 }
        t.split('\r').forEachIndexed { pi, line ->
            val s0 = starts[pi]
            val runs = ArrayList<Inline>()
            if (styles == null || styles.chars.isEmpty()) runs += Inline.Text(line, RunStyle())
            else {
                var acc = 0; var i = 0
                for (r in styles.chars) {
                    val a = maxOf(acc, s0); val e = minOf(acc + r.count, s0 + line.length)
                    if (e > a) { runs += Inline.Text(line.substring(a - s0, e - s0), RunStyle(bold = r.bold, italic = r.italic, underline = r.underline, sizePt = r.sizePt, color = r.color)); i = e }
                    acc += r.count
                    if (acc >= s0 + line.length) break
                }
                if (i < s0 + line.length && runs.isEmpty()) runs += Inline.Text(line, RunStyle())
            }
            // paragraph alignment: the run that covers the paragraph's first character
            var acc = 0
            val align = styles?.paras?.firstOrNull { p -> (s0 in acc until acc + p.count).also { acc += p.count } }?.align
            out += SlideParagraph(runs, align = align, rtl = Text6.rtlDominant(line))
        }
        return out
    }
}
