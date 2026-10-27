package com.smartdocs.editor.core.convert

import java.text.Normalizer

/** Text helpers: Arabic presentation-form normalisation, visual → logical reordering, number parsing. */
object BidiText {

    enum class Dir { L, R, N }

    /** Bidi class of a code point, simplified: numbers count as L (they run left → right inside RTL text). */
    fun dirOf(c: Char): Dir = when (Character.getDirectionality(c)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING, Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> Dir.R
        Character.DIRECTIONALITY_LEFT_TO_RIGHT, Character.DIRECTIONALITY_EUROPEAN_NUMBER,
        Character.DIRECTIONALITY_ARABIC_NUMBER -> Dir.L
        else -> Dir.N
    }

    fun dirOf(s: String): Dir {
        var r = 0; var l = 0
        for (c in s) when (dirOf(c)) { Dir.R -> r++; Dir.L -> if (!c.isDigit()) l++; else -> Unit }
        return when {
            r > 0 && r >= l -> Dir.R
            l > 0 -> Dir.L
            s.any { it.isDigit() } -> Dir.L
            else -> Dir.N
        }
    }

    fun isRtlChar(c: Char) = dirOf(c) == Dir.R

    /** Strong RTL and strong LTR letter counts (digits ignored). */
    fun strongCounts(s: String): IntArray {
        val rl = IntArray(2)
        for (c in s) when (dirOf(c)) { Dir.R -> rl[0]++; Dir.L -> if (!c.isDigit()) rl[1]++; else -> Unit }
        return rl
    }

    /** More strong RTL letters than strong LTR letters (digits ignored). */
    fun rtlDominant(s: String): Boolean = strongCounts(s).let { it[0] > it[1] }

    /**
     * Drops a fathatan (U+064B) that is not word-final. Some generators map one glyph to "ا + ً" in the
     * ToUnicode table and every other use of that alef then carries the stray mark.
     */
    fun fixTanween(word: String): String {
        if (word.indexOf('\u064B') < 0) return word
        val sb = StringBuilder(word.length)
        for (i in word.indices) {
            val c = word[i]
            if (c == '\u064B') {
                var j = i + 1
                while (j < word.length && isMark(word[j].toString())) j++
                val rest = word.substring(j)
                val ok = rest.isEmpty() || ((rest[0] == '\u0627' || rest[0] == '\u0649') && rest.drop(1).all { !Character.isLetter(it) })
                if (!ok) continue
            }
            sb.append(c)
        }
        return sb.toString()
    }

    fun isMark(s: String) = s.isNotEmpty() && s.all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() || Character.getType(it) == Character.ENCLOSING_MARK.toInt() }

    /**
     * Normalise a glyph's Unicode: Arabic presentation forms (U+FB50–FDFF, U+FE70–FEFF) and ligatures are
     * folded to their base letters (NFKC), tatweel is dropped, and private-use / control chars removed.
     */
    fun normalize(s: String): String {
        if (s.isEmpty()) return s
        val needs = s.any { it.code in 0xFB00..0xFEFF || it.code == 0x0640 || it.code < 0x20 || it.code in 0xE000..0xF8FF }
        val n = if (needs) Normalizer.normalize(s, Normalizer.Form.NFKC) else s
        val sb = StringBuilder(n.length)
        for (c in n) {
            if (c.code == 0x0640 || c.code < 0x20 || c.code in 0xE000..0xF8FF || c == '\uFEFF' || c == '‎' || c == '‏') continue
            sb.append(c)
        }
        return sb.toString()
    }

    /**
     * Reorders [units] given in VISUAL left → right order into LOGICAL order for a paragraph whose base
     * direction is RTL ([rtlBase]) or LTR. [dir] gives each unit's class. Neutral units take the direction of
     * their strong neighbours when both agree, otherwise the base direction (a simplified inverse of UAX #9,
     * which is exact for the usual single-level Arabic + numbers + Latin mix found in PDFs).
     */
    fun <T> visualToLogical(units: List<T>, rtlBase: Boolean, dir: (T) -> Dir): List<T> {
        if (units.size < 2) return units
        val raw = units.map(dir)
        val resolved = Array(units.size) { raw[it] }
        val base = if (rtlBase) Dir.R else Dir.L
        for (i in units.indices) {
            if (raw[i] != Dir.N) continue
            var p = i - 1; while (p >= 0 && raw[p] == Dir.N) p--
            var n = i + 1; while (n < units.size && raw[n] == Dir.N) n++
            val pd = if (p >= 0) raw[p] else base
            val nd = if (n < units.size) raw[n] else base
            resolved[i] = if (pd == nd) pd else base
        }
        val out = ArrayList<T>(units.size)
        if (rtlBase) {
            val rev = units.indices.reversed().toList()
            var i = 0
            while (i < rev.size) {
                if (resolved[rev[i]] == Dir.L) {
                    var j = i; while (j < rev.size && resolved[rev[j]] == Dir.L) j++
                    for (k in j - 1 downTo i) out += units[rev[k]]
                    i = j
                } else { out += units[rev[i]]; i++ }
            }
        } else {
            var i = 0
            while (i < units.size) {
                if (resolved[i] == Dir.R) {
                    var j = i; while (j < units.size && resolved[j] == Dir.R) j++
                    for (k in j - 1 downTo i) out += units[k]
                    i = j
                } else { out += units[i]; i++ }
            }
        }
        return out
    }

    /** Arabic-Indic / Extended Arabic-Indic digits and separators → ASCII. */
    fun asciiDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(
            when (c.code) {
                in 0x0660..0x0669 -> '0' + (c.code - 0x0660)
                in 0x06F0..0x06F9 -> '0' + (c.code - 0x06F0)
                0x066B -> '.'   // Arabic decimal separator
                0x066C -> ','   // Arabic thousands separator
                0x066A -> '%'   // Arabic percent sign
                0x2212 -> '-'
                else -> c
            }
        )
        return sb.toString()
    }

    /** A parsed numeric cell. [format]: 0 general, 1 `#,##0`, 2 `#,##0.00`, 3 `0%`, 4 `0.00%`. */
    data class Num(val value: Double, val format: Int)

    private val plain = Regex("""^[-+]?(\d+)(\.\d+)?$""")
    private val grouped = Regex("""^[-+]?\d{1,3}(,\d{3})+(\.\d+)?$""")

    /** Parse a cell text as a number (Arabic-Indic digits accepted). Codes with leading zeros stay text. */
    fun parseNumber(text: String): Num? {
        var s = asciiDigits(text.trim()).replace(" ", "").replace(" ", "")
        if (s.isEmpty() || s.length > 22) return null
        var neg = false
        if (s.startsWith("(") && s.endsWith(")")) { neg = true; s = s.substring(1, s.length - 1) }
        var pct = false
        if (s.endsWith("%")) { pct = true; s = s.dropLast(1) } else if (s.startsWith("%")) { pct = true; s = s.drop(1) }
        if (s.isEmpty()) return null
        val isGrouped = grouped.matches(s)
        if (!isGrouped && !plain.matches(s)) return null
        val digitsOnly = s.trimStart('-', '+')
        if (digitsOnly.length > 1 && digitsOnly[0] == '0' && digitsOnly[1] != '.' && digitsOnly[1] != ',') return null
        if (digitsOnly.count { it.isDigit() } > 15) return null
        val v = s.replace(",", "").toDoubleOrNull() ?: return null
        val decimals = s.contains('.')
        val value = (if (neg) -v else v) / (if (pct) 100.0 else 1.0)
        val fmt = when {
            pct -> if (decimals) 4 else 3
            isGrouped -> if (decimals) 2 else 1
            else -> 0
        }
        return Num(value, fmt)
    }

    /** XML-escape and strip characters that are illegal in XML 1.0. */
    fun xml(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                c.code < 0x20 || c.code == 0xFFFE || c.code == 0xFFFF -> Unit
                c.isSurrogate() -> sb.append(c) // pairs pass through; lone surrogates are rare in PDF text
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
