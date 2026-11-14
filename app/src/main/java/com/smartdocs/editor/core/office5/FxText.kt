package com.smartdocs.editor.core.office5

import java.math.BigDecimal
import java.math.RoundingMode

/** Longest text a cell holds; functions that would build more return #VALUE!. */
private const val MAX_TEXT = 32767

/** CHAR / CODE use the Western "ANSI" code page, like Excel on Windows. */
private val ANSI = runCatching { java.nio.charset.Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

/** Text functions beyond the basic set in [FxFunctions]. */
internal fun FxFunctions.registerText() {
    reg("FIND", "text", 2, 3) { a -> find(a, wildcards = false) }
    reg("SEARCH", "text", 2, 3) { a -> find(a, wildcards = true) }
    reg("SUBSTITUTE", "text", 3, 4) { a ->
        val s = a.str(0); val old = a.str(1); val new = a.str(2); val only = if (a.missing(3)) 0 else a.int(3)
        if (!a.missing(3) && only < 1) FV.VALUE else {
            // occurrences are counted left to right without overlap; [only] = 0 replaces them all
            val sb = StringBuilder(); var from = 0; var k = 0
            var i = if (old.isEmpty()) -1 else s.indexOf(old)
            while (i >= 0 && sb.length <= MAX_TEXT) {
                if (only == 0 || ++k == only) { sb.append(s, from, i).append(new); from = i + old.length }
                i = if (only != 0 && k >= only) -1 else s.indexOf(old, i + old.length)
            }
            sb.append(s, from, s.length)
            if (sb.length > MAX_TEXT) FV.VALUE else FV.Str(sb.toString())
        }
    }
    reg("REPLACE", "text", 4) { a ->
        val s = a.str(0); val st = a.int(1); val n = a.int(2); val new = a.str(3)
        if (st < 1 || n < 0) FV.VALUE else {
            val from = minOf(st - 1, s.length); val to = minOf(s.length.toLong(), from.toLong() + n).toInt()
            FV.Str(s.substring(0, from) + new + s.substring(to))
        }
    }
    reg("REPT", "text", 2) { a -> val s = a.str(0); val n = a.int(1); if (n < 0 || s.length.toLong() * n > MAX_TEXT) FV.VALUE else FV.Str(s.repeat(n)) }
    reg("PROPER", "text", 1) { a ->
        // a letter is a capital when the character before it is not a letter
        val sb = StringBuilder(); var start = true
        for (ch in a.str(0)) { sb.append(if (start) ch.uppercaseChar() else ch.lowercaseChar()); start = !ch.isLetter() }
        FV.Str(sb.toString())
    }
    reg("EXACT", "text", 2) { a -> FV.Bool(a.str(0) == a.str(1)) }
    reg("TEXTJOIN", "text", 3, MANY) { a ->
        val sep = a.str(0); val skipEmpty = a.bool(1); val sb = StringBuilder(); var first = true
        a.each(2) { v, _ ->
            val t = FxCoerce.str(v)
            if (!skipEmpty || t.isNotEmpty()) {
                if (!first) sb.append(sep)
                sb.append(t); first = false
                if (sb.length > MAX_TEXT) throw FxError(FV.VALUE)
            }
        }
        FV.Str(sb.toString())
    }
    reg("CLEAN", "text", 1) { a -> FV.Str(a.str(0).filter { it.code >= 32 }) }
    reg("T", "text", 1) { a -> val v = a.value(0); if (v is FV.Err || v is FV.Str) v else FV.Str("") }
    // ---------------- characters ----------------
    reg("CHAR", "text", 1) { a -> val n = a.int(0); if (n < 1 || n > 255) FV.VALUE else FV.Str(String(byteArrayOf(n.toByte()), ANSI)) }
    reg("CODE", "text", 1) { a ->
        val s = a.str(0)
        if (s.isEmpty()) FV.VALUE else FV.Num((s.substring(0, 1).toByteArray(ANSI)[0].toInt() and 0xFF).toDouble())
    }
    reg("UNICHAR", "text", 1) { a ->
        val n = a.int(0)
        if (n < 1 || n > 0x10FFFF) FV.VALUE else if (n in 0xD800..0xDFFF) FV.NA else FV.Str(String(Character.toChars(n)))
    }
    reg("UNICODE", "text", 1) { a -> val s = a.str(0); if (s.isEmpty()) FV.VALUE else FV.Num(s.codePointAt(0).toDouble()) }
    // ---------------- numbers as text ----------------
    reg("FIXED", "text", 1, 3) { a ->
        val x = a.num(0); val d = a.int(1, 2)
        if (d > 127) FV.VALUE else FV.Str((if (x < 0) "-" else "") + fixed(x, d, group = !a.bool(2, false)))
    }
    reg("DOLLAR", "text", 1, 2) { a ->
        val x = a.num(0); val d = a.int(1, 2)
        if (d > 127) FV.VALUE else fixed(x, d, group = true).let { FV.Str(if (x < 0) "(\$$it)" else "\$$it") }
    }
    reg("NUMBERVALUE", "text", 1, 3) { a ->
        val dec = a.str(1, ".").take(1); val grp = a.str(2, if (dec == ",") "." else ",").take(1)
        if (dec.isEmpty() || grp.isEmpty() || dec == grp) FV.VALUE else numberValue(a.str(0), dec, grp)
    }
}

/** FIND (case-sensitive) and SEARCH (case-insensitive, with ? * ~ wildcards): 1-based position, #VALUE! when absent. */
private fun find(a: FxArgs, wildcards: Boolean): FV {
    val what = a.str(0); val s = a.str(1); val start = a.int(2, 1)
    if (start < 1 || start > maxOf(s.length, 1)) return FV.VALUE
    val i = if (wildcards) FxCriteria.wildcard(what).find(s, start - 1)?.range?.first ?: -1 else s.indexOf(what, start - 1)
    return if (i < 0) FV.VALUE else FV.Num(i + 1.0)
}

/** |x| rounded half away from zero to [decimals] (negative = left of the point), optionally with thousands separators. */
private fun fixed(x: Double, decimals: Int, group: Boolean): String {
    if (x.isNaN() || x.isInfinite()) throw FxError(FV.NUM)
    val plain = BigDecimal(java.lang.Double.toString(x)).abs().setScale(decimals.coerceAtLeast(-330), RoundingMode.HALF_UP).toPlainString()
    var ip = plain.substringBefore('.'); val dp = plain.substringAfter('.', "")
    if (group && ip.length > 3) ip = ip.reversed().chunked(3).joinToString(",").reversed()
    return if (dp.isEmpty()) ip else "$ip.$dp"
}

private val plainNumberRx = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

/** NUMBERVALUE: spaces are ignored, each trailing % divides by 100, the group separator may only come before the decimal one. */
private fun numberValue(text: String, dec: String, grp: String): FV {
    var s = FxCoerce.normalizeDigits(text).filter { !it.isWhitespace() }
    if (s.isEmpty()) return FV.Num(0.0)
    var scale = 1.0
    while (s.endsWith("%")) { scale *= 100; s = s.dropLast(1) }
    val at = s.indexOf(dec)
    val whole = if (at < 0) s else s.substring(0, at); val frac = if (at < 0) "" else s.substring(at + 1)
    if (frac.contains(dec) || frac.contains(grp)) return FV.VALUE
    val t = whole.replace(grp, "") + (if (at < 0) "" else ".$frac")
    return if (plainNumberRx.matches(t)) FV.of(t.toDouble() / scale) else FV.VALUE
}
