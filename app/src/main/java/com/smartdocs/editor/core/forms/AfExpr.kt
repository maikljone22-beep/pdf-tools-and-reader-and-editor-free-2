package com.smartdocs.editor.core.forms

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Tiny arithmetic expression evaluator used for Acrobat form calculations (no JS engine):
 * numbers, identifiers, + - * / %, parentheses, unary +/-, and a few functions
 * (Math.round/abs/floor/ceil/min/max/pow/sqrt, AFMakeNumber, Number, parseFloat, parseInt).
 *
 * Identifiers are resolved through [resolve]. In SFN mode (simple field notation) identifiers may contain
 * dots, spaces are separators and `\` escapes any following character (e.g. `Qty\ 1` or `Total\-A`).
 */
class AfExpr(private val src: String, private val sfn: Boolean, private val resolve: (String) -> Double?) {

    class ParseException(msg: String) : Exception(msg)

    private var pos = 0

    fun evaluate(): Double {
        val v = expr()
        skipWs()
        if (pos < src.length) throw ParseException("unexpected '${src[pos]}' at $pos")
        return v
    }

    private fun skipWs() { while (pos < src.length && src[pos].isWhitespace()) pos++ }

    private fun peek(): Char? { skipWs(); return if (pos < src.length) src[pos] else null }

    private fun expr(): Double {
        var v = term()
        while (true) {
            when (peek()) {
                '+' -> { pos++; v += term() }
                '-' -> { pos++; v -= term() }
                else -> return v
            }
        }
    }

    private fun term(): Double {
        var v = unary()
        while (true) {
            when (peek()) {
                '*' -> { pos++; v *= unary() }
                '/' -> { pos++; val d = unary(); v = if (d == 0.0) 0.0 else v / d }   // Acrobat shows an empty/0 result instead of Infinity
                '%' -> { pos++; val d = unary(); v = if (d == 0.0) 0.0 else v % d }
                else -> return v
            }
        }
    }

    private fun unary(): Double = when (peek()) {
        '-' -> { pos++; -unary() }
        '+' -> { pos++; unary() }
        else -> primary()
    }

    private fun primary(): Double {
        val c = peek() ?: throw ParseException("unexpected end")
        if (c == '(') {
            pos++
            val v = expr()
            if (peek() != ')') throw ParseException("missing )")
            pos++
            return v
        }
        if (c.isDigit() || c == '.') return number()
        if (c == '"' || c == '\'') return AfNumbers.parse(stringLiteral()) ?: 0.0
        if (isIdentStart(c)) {
            val id = identifier()
            if (!sfn && peek() == '(') return call(id)
            return resolve(id) ?: throw ParseException("unknown identifier $id")
        }
        throw ParseException("unexpected '$c'")
    }

    private fun number(): Double {
        val start = pos
        while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            while (pos < src.length && src[pos].isDigit()) pos++
        }
        return src.substring(start, pos).toDoubleOrNull() ?: throw ParseException("bad number")
    }

    private fun stringLiteral(): String {
        val q = src[pos++]
        val sb = StringBuilder()
        while (pos < src.length && src[pos] != q) {
            if (src[pos] == '\\' && pos + 1 < src.length) pos++
            sb.append(src[pos++])
        }
        pos++
        return sb.toString()
    }

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$' || (sfn && c == '\\')

    private fun identifier(): String {
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos]
            when {
                sfn && c == '\\' && pos + 1 < src.length -> { sb.append(src[pos + 1]); pos += 2 }
                c.isLetterOrDigit() || c == '_' || c == '$' || c == '.' -> { sb.append(c); pos++ }
                else -> break
            }
        }
        return sb.toString()
    }

    private fun call(name: String): Double {
        pos++ // (
        val args = ArrayList<Double>()
        if (peek() != ')') {
            while (true) {
                args += expr()
                when (peek()) {
                    ',' -> pos++
                    ')' -> break
                    else -> throw ParseException("bad argument list")
                }
            }
        }
        pos++ // )
        val a0 = args.getOrElse(0) { 0.0 }
        return when (name) {
            "AFMakeNumber", "Number", "parseFloat" -> a0
            "parseInt" -> a0.toLong().toDouble()
            "Math.round" -> round(a0)
            "Math.abs" -> abs(a0)
            "Math.floor" -> floor(a0)
            "Math.ceil" -> ceil(a0)
            "Math.sqrt" -> sqrt(a0)
            "Math.pow" -> a0.pow(args.getOrElse(1) { 1.0 })
            "Math.min" -> args.minOrNull() ?: 0.0
            "Math.max" -> args.maxOrNull() ?: 0.0
            "util.printf" -> args.getOrElse(1) { a0 }
            else -> throw ParseException("unsupported function $name")
        }
    }

    companion object {
        fun eval(expr: String, sfn: Boolean, resolve: (String) -> Double?): Double? =
            runCatching { AfExpr(expr, sfn, resolve).evaluate() }.getOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
    }
}

/** AFMakeNumber-like parsing of a field value (handles currency symbols, separators, Arabic-Indic digits). */
object AfNumbers {
    fun normalizeDigits(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                '٫' -> '.'            // Arabic decimal separator
                '٬' -> ','            // Arabic thousands separator
                '−' -> '-'
                else -> c
            }
        )
    }

    /** Returns null for blank/non-numeric input. */
    fun parse(raw: String?): Double? {
        if (raw == null) return null
        var s = normalizeDigits(raw).trim()
        if (s.isEmpty()) return null
        val negativeParens = s.startsWith("(") && s.endsWith(")")
        s = s.filter { it.isDigit() || it == '.' || it == ',' || it == '-' || it == 'e' || it == 'E' }
        if (s.isEmpty() || s.none { it.isDigit() }) return null
        if (s.contains('.') && s.contains(',')) {
            // the last separator is the decimal one
            s = if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.') else s.replace(",", "")
        } else if (s.contains(',')) {
            val parts = s.split(',')
            s = if (parts.size == 2 && parts[1].length != 3) parts[0] + "." + parts[1] else s.replace(",", "")
        }
        val v = s.toDoubleOrNull() ?: Regex("-?\\d+(\\.\\d+)?").find(s)?.value?.toDoubleOrNull() ?: return null
        return if (negativeParens) -abs(v) else v
    }

    /** Raw value string Acrobat would store for a computed number. */
    fun raw(v: Double): String {
        if (v == floor(v) && abs(v) < 1e15) return v.toLong().toString()
        return java.math.BigDecimal(v).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }
}
