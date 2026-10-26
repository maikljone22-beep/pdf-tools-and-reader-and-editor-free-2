package com.smartdocs.editor.core.office5

import java.math.RoundingMode
import kotlin.math.abs

/** Implementation of one worksheet function. */
fun interface FxImpl { fun call(a: FxArgs): FV }

class FxFn(val name: String, val category: String, val minArgs: Int, val maxArgs: Int, val volatile: Boolean, val impl: FxImpl)

/**
 * Function registry. Adding a function is one [reg] call; [FxArgs] gives lazy, Excel-coerced arguments.
 * Categories group the function list in the UI ("math", "stat", "logical", "text", "date", "lookup", "info").
 */
object FxFunctions {
    private val map = HashMap<String, FxFn>()

    operator fun get(name: String): FxFn? = map[name.uppercase()]
    val all: Collection<FxFn> get() = map.values
    fun isVolatile(name: String) = map[name.uppercase()]?.volatile == true

    fun reg(name: String, category: String, min: Int, max: Int = min, volatile: Boolean = false, impl: FxImpl) {
        map[name] = FxFn(name, category, min, max, volatile, impl)
    }

    private const val MANY = 255

    init {
        // ---------------- math ----------------
        reg("SUM", "math", 1, MANY) { a -> FV.of(a.numbers().sum()) }
        reg("PRODUCT", "math", 1, MANY) { a -> FV.of(a.numbers().fold(1.0) { x, y -> x * y }) }
        reg("ABS", "math", 1) { a -> FV.of(abs(a.num(0))) }
        reg("ROUND", "math", 2) { a -> FV.of(FxCoerce.round(a.num(0), a.int(1))) }
        reg("ROUNDUP", "math", 2) { a -> FV.of(FxCoerce.round(a.num(0), a.int(1), RoundingMode.UP)) }
        reg("ROUNDDOWN", "math", 2) { a -> FV.of(FxCoerce.round(a.num(0), a.int(1), RoundingMode.DOWN)) }
        reg("INT", "math", 1) { a -> FV.of(Math.floor(a.num(0))) }
        reg("MOD", "math", 2) { a -> val n = a.num(0); val d = a.num(1); if (d == 0.0) FV.DIV0 else FV.of(n - d * Math.floor(n / d)) }
        reg("POWER", "math", 2) { a -> FV.of(Math.pow(a.num(0), a.num(1))) }
        reg("SQRT", "math", 1) { a -> val x = a.num(0); if (x < 0) FV.NUM else FV.of(Math.sqrt(x)) }
        reg("PI", "math", 0) { FV.Num(Math.PI) }
        reg("SIGN", "math", 1) { a -> FV.of(Math.signum(a.num(0))) }
        reg("SUMIF", "math", 2, 3) { a -> sumIf(a) }
        // ---------------- statistics ----------------
        reg("AVERAGE", "stat", 1, MANY) { a -> val n = a.numbers(); if (n.isEmpty()) FV.DIV0 else FV.of(n.sum() / n.size) }
        reg("MIN", "stat", 1, MANY) { a -> FV.of(a.numbers().minOrNull() ?: 0.0) }
        reg("MAX", "stat", 1, MANY) { a -> FV.of(a.numbers().maxOrNull() ?: 0.0) }
        reg("COUNT", "stat", 1, MANY) { a ->
            var n = 0
            a.each { v, ref -> if (v is FV.Num || (!ref && (v is FV.Bool || (v is FV.Str && FxCoerce.parseNumber(v.v) != null)))) n++ }
            FV.Num(n.toDouble())
        }
        reg("COUNTA", "stat", 1, MANY) { a -> var n = 0; a.each { v, _ -> if (v !is FV.Blank) n++ }; FV.Num(n.toDouble()) }
        reg("COUNTBLANK", "stat", 1) { a -> var n = 0; a.each { v, _ -> if (v is FV.Blank || v == FV.Str("")) n++ }; FV.Num(n.toDouble()) }
        reg("COUNTIF", "stat", 2) { a -> val m = FxCriteria.of(a.value(1)); var n = 0; a.array(0).data.forEach { if (m(it)) n++ }; FV.Num(n.toDouble()) }
        reg("AVERAGEIF", "stat", 2, 3) { a ->
            val m = FxCriteria.of(a.value(1)); val test = a.array(0); val vals = if (a.missing(2)) test else a.array(2)
            var s = 0.0; var n = 0
            for (i in test.data.indices) if (m(test.data[i])) (vals.data.getOrNull(i) as? FV.Num)?.let { s += it.v; n++ }
            if (n == 0) FV.DIV0 else FV.of(s / n)
        }
        // ---------------- logical ----------------
        reg("IF", "logical", 1, 3) { a ->
            val c = a.value(0); if (c is FV.Err) c
            else if (FxCoerce.bool(c)) (if (a.missing(1)) (if (a.size > 1) FV.Num(0.0) else FV.TRUE) else a.value(1))
            else (if (a.size < 3) FV.FALSE else if (a.missing(2)) FV.Num(0.0) else a.value(2))
        }
        reg("IFERROR", "logical", 2) { a -> val v = a.value(0); if (v is FV.Err) a.value(1) else v }
        reg("IFNA", "logical", 2) { a -> val v = a.value(0); if (v == FV.NA) a.value(1) else v }
        reg("AND", "logical", 1, MANY) { a -> logical(a, true) }
        reg("OR", "logical", 1, MANY) { a -> logical(a, false) }
        reg("NOT", "logical", 1) { a -> FV.Bool(!a.bool(0)) }
        reg("TRUE", "logical", 0) { FV.TRUE }
        reg("FALSE", "logical", 0) { FV.FALSE }
        // ---------------- text ----------------
        reg("CONCAT", "text", 1, MANY) { a -> val sb = StringBuilder(); a.each { v, _ -> sb.append(FxCoerce.str(v)) }; FV.Str(sb.toString()) }
        reg("CONCATENATE", "text", 1, MANY) { a -> FV.Str((0 until a.size).joinToString("") { a.str(it) }) }
        reg("LEN", "text", 1) { a -> FV.Num(a.str(0).length.toDouble()) }
        reg("LEFT", "text", 1, 2) { a -> val n = a.int(1, 1); if (n < 0) FV.VALUE else FV.Str(a.str(0).take(n)) }
        reg("RIGHT", "text", 1, 2) { a -> val n = a.int(1, 1); if (n < 0) FV.VALUE else FV.Str(a.str(0).takeLast(n)) }
        reg("MID", "text", 3) { a ->
            val s = a.str(0); val st = a.int(1); val n = a.int(2)
            if (st < 1 || n < 0) FV.VALUE else FV.Str(if (st > s.length) "" else s.substring(st - 1, minOf(s.length, st - 1 + n)))
        }
        reg("UPPER", "text", 1) { a -> FV.Str(a.str(0).uppercase()) }
        reg("LOWER", "text", 1) { a -> FV.Str(a.str(0).lowercase()) }
        reg("TRIM", "text", 1) { a -> FV.Str(a.str(0).trim().replace(Regex(" +"), " ")) }
        reg("TEXT", "text", 2) { a -> FV.Str(NumFmt5.format(a.value(0), a.str(1)).text) }
        reg("VALUE", "text", 1) { a -> FV.of(FxCoerce.num(FV.Str(a.str(0)))) }
        // ---------------- date ----------------
        reg("TODAY", "date", 0, volatile = true) { FV.Num(FxDates.today()) }
        reg("NOW", "date", 0, volatile = true) { FV.Num(FxDates.now()) }
        reg("DATE", "date", 3) { a ->
            val d = runCatching { java.time.LocalDate.of(a.int(0), 1, 1).plusMonths(a.int(1) - 1L).plusDays(a.int(2) - 1L) }.getOrNull()
            if (d == null) FV.NUM else FV.Num(FxDates.serial(d))
        }
        reg("YEAR", "date", 1) { a -> FV.Num(FxDates.date(a.num(0)).year.toDouble()) }
        reg("MONTH", "date", 1) { a -> FV.Num(FxDates.date(a.num(0)).monthValue.toDouble()) }
        reg("DAY", "date", 1) { a -> FV.Num(FxDates.date(a.num(0)).dayOfMonth.toDouble()) }
        // ---------------- lookup ----------------
        reg("VLOOKUP", "lookup", 3, 4) { a -> lookup(a, vertical = true) }
        reg("HLOOKUP", "lookup", 3, 4) { a -> lookup(a, vertical = false) }
        reg("CHOOSE", "lookup", 2, MANY) { a -> val i = a.int(0); if (i < 1 || i >= a.size) FV.VALUE else a.value(i) }
        // ---------------- information ----------------
        reg("ISBLANK", "info", 1) { a -> FV.Bool(a.value(0) is FV.Blank) }
        reg("ISNUMBER", "info", 1) { a -> FV.Bool(a.value(0) is FV.Num) }
        reg("ISTEXT", "info", 1) { a -> FV.Bool(a.value(0) is FV.Str) }
        reg("ISERROR", "info", 1) { a -> FV.Bool(a.value(0) is FV.Err) }
        reg("NA", "info", 0) { FV.NA }
    }

    private fun logical(a: FxArgs, and: Boolean): FV {
        var any = false; var acc = and
        a.each { v, ref ->
            val b: Boolean? = when (v) {
                is FV.Bool -> v.v
                is FV.Num -> v.v != 0.0
                is FV.Err -> throw FxError(v)
                is FV.Str -> if (ref) null else FxCoerce.bool(v)
                else -> null
            }
            if (b != null) { any = true; acc = if (and) acc && b else acc || b }
        }
        return if (!any) FV.VALUE else FV.Bool(acc)
    }

    private fun sumIf(a: FxArgs): FV {
        val m = FxCriteria.of(a.value(1)); val test = a.array(0); val vals = if (a.missing(2)) test else a.array(2)
        var s = 0.0
        for (i in test.data.indices) if (m(test.data[i])) (vals.data.getOrNull(i) as? FV.Num)?.let { s += it.v }
        return FV.of(s)
    }

    private fun lookup(a: FxArgs, vertical: Boolean): FV {
        val key = a.value(0); if (key is FV.Err) return key
        val t = a.array(1)
        val idx = a.int(2)
        val approx = a.bool(3, true)
        val n = if (vertical) t.rows else t.cols
        val width = if (vertical) t.cols else t.rows
        if (idx < 1) return FV.VALUE
        if (idx > width) return FV.REF
        fun keyAt(i: Int) = if (vertical) t[i, 0] else t[0, i]
        fun res(i: Int) = if (vertical) t[i, idx - 1] else t[idx - 1, i]
        if (!approx) {
            val wild = key is FV.Str && (key.v.contains('*') || key.v.contains('?'))
            val m = if (wild) FxCriteria.of(FV.Str("=" + (key as FV.Str).v)) else null
            for (i in 0 until n) { val k = keyAt(i); if (if (m != null) m(k) else FxCoerce.compare(k, key) == 0 && k !is FV.Blank) return res(i) }
            return FV.NA
        }
        // approximate: last key <= lookup value in an ascending first column/row
        var found = -1
        for (i in 0 until n) {
            val k = keyAt(i)
            if (k is FV.Blank) continue
            if ((k is FV.Num) != (key is FV.Num) && !(k is FV.Str && key is FV.Str)) continue
            if (FxCoerce.compare(k, key) <= 0) found = i else break
        }
        return if (found < 0) FV.NA else res(found)
    }
}

/** COUNTIF-style criteria: "5", ">=10", "<>x", "a*", "=", with * and ? wildcards (~ escapes). */
object FxCriteria {
    fun of(c: FV): (FV) -> Boolean {
        if (c is FV.Num || c is FV.Bool) return { v -> v !is FV.Blank && FxCoerce.compare(v, c) == 0 }
        val s = (c as? FV.Str)?.v ?: return { v -> v is FV.Blank }
        val op = listOf("<=", ">=", "<>", "<", ">", "=").firstOrNull { s.startsWith(it) } ?: ""
        val rest = s.substring(op.length)
        val num = FxCoerce.parseNumber(rest)
        if (num != null) {
            val n = FV.Num(num)
            return { v ->
                val d = when (v) { is FV.Num -> v; is FV.Str -> FxCoerce.parseNumber(v.v)?.let { FV.Num(it) }; else -> null }
                if (d == null) op == "<>" else cmp(op, FxCoerce.compare(d, n))
            }
        }
        if (rest.isEmpty()) return when (op) { "<>" -> { v -> v !is FV.Blank && v != FV.Str("") }; else -> { v -> v is FV.Blank || v == FV.Str("") } }
        val rx = wildcard(rest)
        return { v ->
            val t = when (v) { is FV.Str -> v.v; is FV.Blank -> null; is FV.Bool -> if (v.v) "TRUE" else "FALSE"; else -> null }
            when (op) {
                "", "=" -> t != null && rx.matches(t)
                "<>" -> t == null || !rx.matches(t)
                else -> t != null && cmp(op, t.compareTo(rest, ignoreCase = true))
            }
        }
    }

    private fun cmp(op: String, c: Int) = when (op) { "<" -> c < 0; ">" -> c > 0; "<=" -> c <= 0; ">=" -> c >= 0; "<>" -> c != 0; else -> c == 0 }

    fun wildcard(p: String): Regex {
        val sb = StringBuilder(); var i = 0
        while (i < p.length) {
            val ch = p[i]
            when {
                ch == '~' && i + 1 < p.length -> { sb.append(Regex.escape(p[i + 1].toString())); i++ }
                ch == '*' -> sb.append(".*")
                ch == '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
            i++
        }
        return Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}
