package com.smartdocs.editor.core.office5

import java.math.BigDecimal
import java.math.MathContext

/** A spreadsheet value. */
sealed class FV {
    data class Num(val v: Double) : FV()
    data class Str(val v: String) : FV()
    data class Bool(val v: Boolean) : FV()
    data class Err(val code: String) : FV()
    object Blank : FV() { override fun toString() = "Blank" }
    /** A rectangular block of values (a range or an array result), row-major. */
    class Arr(val rows: Int, val cols: Int, val data: Array<FV>) : FV() {
        operator fun get(r: Int, c: Int): FV = data[r * cols + c]
        fun first(): FV = if (data.isEmpty()) Blank else data[0]
    }

    val isErr: Boolean get() = this is Err

    companion object {
        val DIV0 = Err("#DIV/0!"); val VALUE = Err("#VALUE!"); val REF = Err("#REF!"); val NAME = Err("#NAME?")
        val NA = Err("#N/A"); val NUM = Err("#NUM!"); val NULL = Err("#NULL!")
        /** Not an Excel error: shown for cells in a reference cycle (saved as 0). */
        val CIRC = Err("#CIRC!")
        val ERRORS = setOf("#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#N/A", "#NUM!", "#NULL!", "#GETTING_DATA", "#SPILL!", "#CALC!")
        val TRUE = Bool(true); val FALSE = Bool(false)
        fun of(d: Double): FV = if (d.isNaN() || d.isInfinite()) NUM else Num(if (d == 0.0) 0.0 else d)
    }
}

/** Thrown inside evaluation to short-circuit with an error value. */
class FxError(val err: FV.Err) : RuntimeException(err.code, null, false, false)

object FxCoerce {
    private val numRx = Regex("""^[+-]?(\d+([.,]\d*)?|[.,]\d+)([eE][+-]?\d+)?$""")

    /** Western digits for Arabic-Indic / Persian digits and the Arabic decimal/thousands separators. */
    fun normalizeDigits(s: String): String {
        if (s.none { it.code in 0x0660..0x0669 || it.code in 0x06F0..0x06F9 || it.code == 0x066B || it.code == 0x066C }) return s
        return buildString(s.length) {
            for (ch in s) when (ch.code) {
                in 0x0660..0x0669 -> append('0' + (ch.code - 0x0660))
                in 0x06F0..0x06F9 -> append('0' + (ch.code - 0x06F0))
                0x066B -> append('.'); 0x066C -> append(',')
                else -> append(ch)
            }
        }
    }

    /** Parses typed or stored text as a number ("1,234.5", "12%", "-3e2", Arabic digits); null when not numeric. */
    fun parseNumber(text: String): Double? {
        var s = normalizeDigits(text.trim())
        if (s.isEmpty()) return null
        var scale = 1.0
        if (s.endsWith("%")) { scale = 0.01; s = s.dropLast(1).trim() }
        if (s.length > 1 && s.contains(',') && Regex("""^[+-]?\d{1,3}(,\d{3})+(\.\d*)?$""").matches(s)) s = s.replace(",", "")
        if (!numRx.matches(s)) return null
        return s.replace(',', '.').toDoubleOrNull()?.times(scale)
    }

    fun num(v: FV): Double = when (v) {
        is FV.Num -> v.v
        is FV.Bool -> if (v.v) 1.0 else 0.0
        is FV.Blank -> 0.0
        is FV.Str -> if (v.v.isEmpty()) throw FxError(FV.VALUE) else parseNumber(v.v) ?: FxDates.parse(v.v) ?: throw FxError(FV.VALUE)
        is FV.Err -> throw FxError(v)
        is FV.Arr -> num(v.first())
    }

    fun str(v: FV): String = when (v) {
        is FV.Num -> general(v.v)
        is FV.Str -> v.v
        is FV.Bool -> if (v.v) "TRUE" else "FALSE"
        is FV.Blank -> ""
        is FV.Err -> throw FxError(v)
        is FV.Arr -> str(v.first())
    }

    fun bool(v: FV): Boolean = when (v) {
        is FV.Bool -> v.v
        is FV.Num -> v.v != 0.0
        is FV.Blank -> false
        is FV.Str -> when (v.v.trim().uppercase()) { "TRUE" -> true; "FALSE" -> false; else -> throw FxError(FV.VALUE) }
        is FV.Err -> throw FxError(v)
        is FV.Arr -> bool(v.first())
    }

    /** Excel "General" number text: integers as is, others with up to 15 significant digits, no trailing zeros. */
    fun general(d: Double): String {
        if (d == 0.0) return "0"
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
        val a = kotlin.math.abs(d)
        if (a >= 1e15 || a < 1e-9) {
            val s = String.format(java.util.Locale.US, "%.14E", d)
            val mant = s.substringBefore('E').trimEnd('0').trimEnd('.')
            val exp = s.substringAfter('E').toInt()
            return mant + "E" + (if (exp < 0) "-" else "+") + kotlin.math.abs(exp).toString().padStart(2, '0')
        }
        return BigDecimal(d).round(MathContext(15)).stripTrailingZeros().toPlainString()
    }

    /** Excel ordering for comparisons: numbers < text < booleans; text compares case-insensitively. */
    fun compare(a: FV, b: FV): Int {
        val x = if (a is FV.Blank) blankLike(b) else a
        val y = if (b is FV.Blank) blankLike(a) else b
        fun rank(v: FV) = when (v) { is FV.Num -> 0; is FV.Str -> 1; is FV.Bool -> 2; else -> 3 }
        val rx = rank(x); val ry = rank(y)
        if (rx != ry) return rx.compareTo(ry)
        return when (x) {
            is FV.Num -> x.v.compareTo((y as FV.Num).v)
            is FV.Str -> x.v.compareTo((y as FV.Str).v, ignoreCase = true)
            is FV.Bool -> x.v.compareTo((y as FV.Bool).v)
            else -> 0
        }
    }

    private fun blankLike(o: FV): FV = when (o) { is FV.Str -> FV.Str(""); is FV.Bool -> FV.FALSE; else -> FV.Num(0.0) }

    /** Rounds half away from zero to [digits] (negative digits round left of the point). */
    fun round(x: Double, digits: Int, mode: java.math.RoundingMode = java.math.RoundingMode.HALF_UP): Double {
        if (x.isNaN() || x.isInfinite()) return x
        return BigDecimal(java.lang.Double.toString(x)).setScale(digits, mode).toDouble()
    }
}

/** Excel serial dates (1900 system, with the 1900-02-29 quirk), pure java.time. */
object FxDates {
    private val EPOCH = java.time.LocalDate.of(1899, 12, 30)

    fun serial(d: java.time.LocalDate): Double {
        val n = java.time.temporal.ChronoUnit.DAYS.between(EPOCH, d).toDouble()
        return if (n < 61) n - 1 else n
    }

    fun date(serial: Double, date1904: Boolean = false): java.time.LocalDateTime {
        var days = Math.floor(serial).toLong()
        val frac = serial - days
        val base = if (date1904) java.time.LocalDate.of(1904, 1, 1) else EPOCH
        if (!date1904 && days < 61) days += 1 // before the fake 1900-02-29
        val secs = Math.round(frac * 86400.0)
        return base.plusDays(days).atStartOfDay().plusSeconds(secs)
    }

    fun now(): Double {
        val t = java.time.LocalDateTime.now()
        return serial(t.toLocalDate()) + t.toLocalTime().toSecondOfDay() / 86400.0
    }

    fun today(): Double = serial(java.time.LocalDate.now())

    private val isoRx = Regex("""^(\d{4})[-/](\d{1,2})[-/](\d{1,2})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?$""")
    private val dmyRx = Regex("""^(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})$""")
    private val timeRx = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""")

    /** Date/time typed as text (2024-05-01, 2024/5/1 13:30, 01/05/2024 as day/month/year, 13:30); null otherwise. */
    fun parse(text: String): Double? {
        val s = FxCoerce.normalizeDigits(text.trim())
        isoRx.matchEntire(s)?.let { m ->
            val d = runCatching { java.time.LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() ?: return null
            val t = if (m.groupValues[4].isNotEmpty()) (m.groupValues[4].toInt() * 3600 + m.groupValues[5].toInt() * 60 + (m.groupValues[6].toIntOrNull() ?: 0)) / 86400.0 else 0.0
            return serial(d) + t
        }
        dmyRx.matchEntire(s)?.let { m ->
            val d = runCatching { java.time.LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull() ?: return null
            return serial(d)
        }
        timeRx.matchEntire(s)?.let { m ->
            val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
            if (h > 23 || mi > 59) return null
            return (h * 3600 + mi * 60 + (m.groupValues[3].toIntOrNull() ?: 0)) / 86400.0
        }
        return null
    }

    fun isTime(text: String) = timeRx.matches(FxCoerce.normalizeDigits(text.trim()))
}
