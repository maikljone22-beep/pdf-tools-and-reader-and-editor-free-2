package com.smartdocs.editor.core.office5

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.truncate

/** Math and trigonometry functions beyond the basic set in [FxFunctions]. */
internal fun FxFunctions.registerMath() {
    reg("SIN", "math", 1) { a -> FV.of(Math.sin(a.num(0))) }
    reg("COS", "math", 1) { a -> FV.of(Math.cos(a.num(0))) }
    reg("TAN", "math", 1) { a -> FV.of(Math.tan(a.num(0))) }
    reg("ASIN", "math", 1) { a -> FV.of(Math.asin(a.num(0))) }      // outside -1..1 is NaN, which FV.of turns into #NUM!
    reg("ACOS", "math", 1) { a -> FV.of(Math.acos(a.num(0))) }
    reg("ATAN", "math", 1) { a -> FV.of(Math.atan(a.num(0))) }
    reg("ATAN2", "math", 2) { a -> val x = a.num(0); val y = a.num(1); if (x == 0.0 && y == 0.0) FV.DIV0 else FV.of(Math.atan2(y, x)) }
    reg("SINH", "math", 1) { a -> FV.of(Math.sinh(a.num(0))) }
    reg("COSH", "math", 1) { a -> FV.of(Math.cosh(a.num(0))) }
    reg("TANH", "math", 1) { a -> FV.of(Math.tanh(a.num(0))) }
    reg("EXP", "math", 1) { a -> FV.of(Math.exp(a.num(0))) }
    reg("LN", "math", 1) { a -> val x = a.num(0); if (x <= 0) FV.NUM else FV.of(Math.log(x)) }
    reg("LOG10", "math", 1) { a -> val x = a.num(0); if (x <= 0) FV.NUM else FV.of(Math.log10(x)) }
    reg("LOG", "math", 1, 2) { a ->
        val x = a.num(0); val b = a.num(1, 10.0)
        if (x <= 0 || b <= 0) FV.NUM else if (b == 1.0) FV.DIV0 else FV.of(if (b == 10.0) Math.log10(x) else Math.log(x) / Math.log(b))
    }
    reg("RADIANS", "math", 1) { a -> FV.of(Math.toRadians(a.num(0))) }
    reg("DEGREES", "math", 1) { a -> FV.of(Math.toDegrees(a.num(0))) }
    // ---------------- rounding ----------------
    reg("TRUNC", "math", 1, 2) { a -> FV.of(FxCoerce.round(a.num(0), a.int(1, 0).coerceIn(-330, 330), RoundingMode.DOWN)) }
    reg("FLOOR", "math", 2) { a ->
        val x = a.num(0); val s = a.num(1)
        if (s == 0.0) FV.DIV0 else if (x > 0 && s < 0) FV.NUM else FV.of(multiple(x, s, RoundingMode.FLOOR))
    }
    reg("CEILING", "math", 2) { a ->
        val x = a.num(0); val s = a.num(1)
        if (s == 0.0) FV.Num(0.0) else if (x > 0 && s < 0) FV.NUM else FV.of(multiple(x, s, RoundingMode.CEILING))
    }
    // the .MATH forms ignore the sign of the significance; a non-zero mode rounds negative numbers the other way
    reg("FLOOR.MATH", "math", 1, 3) { a ->
        val x = a.num(0); val s = abs(a.num(1, 1.0)); val towardZero = x < 0 && a.num(2, 0.0) != 0.0
        if (s == 0.0) FV.Num(0.0) else FV.of(multiple(x, s, if (towardZero) RoundingMode.DOWN else RoundingMode.FLOOR))
    }
    reg("CEILING.MATH", "math", 1, 3) { a ->
        val x = a.num(0); val s = abs(a.num(1, 1.0)); val awayFromZero = x < 0 && a.num(2, 0.0) != 0.0
        if (s == 0.0) FV.Num(0.0) else FV.of(multiple(x, s, if (awayFromZero) RoundingMode.UP else RoundingMode.CEILING))
    }
    reg("MROUND", "math", 2) { a ->
        val x = a.num(0); val m = a.num(1)
        if (x == 0.0 || m == 0.0) FV.Num(0.0) else if ((x < 0) != (m < 0)) FV.NUM else FV.of(multiple(x, m, RoundingMode.HALF_UP))
    }
    reg("EVEN", "math", 1) { a -> val x = a.num(0); FV.of(Math.signum(x) * Math.ceil(abs(x) / 2) * 2) }
    reg("ODD", "math", 1) { a ->
        val x = a.num(0); val m = Math.ceil(abs(x)).let { if (it % 2 == 0.0) it + 1 else it }
        FV.of(if (x < 0) -m else m)
    }
    reg("QUOTIENT", "math", 2) { a -> val n = a.num(0); val d = a.num(1); if (d == 0.0) FV.DIV0 else FV.of(truncate(n / d)) }
    // ---------------- integers ----------------
    reg("FACT", "math", 1) { a ->
        val n = truncate(a.num(0))
        if (n < 0) FV.NUM else { var f = 1.0; var i = 2.0; while (i <= n && !f.isInfinite()) { f *= i; i++ }; FV.of(f) }
    }
    reg("COMBIN", "math", 2) { a ->
        val n = truncate(a.num(0)); val k = truncate(a.num(1))
        if (n < 0 || k < 0 || n < k) FV.NUM else {
            val m = minOf(k, n - k); var r = 1.0; var i = 1.0
            while (i <= m && !r.isInfinite()) { r = r * (n - m + i) / i; i++ }
            FV.of(Math.rint(r))
        }
    }
    reg("PERMUT", "math", 2) { a ->
        val n = truncate(a.num(0)); val k = truncate(a.num(1))
        if (n < 0 || k < 0 || n < k) FV.NUM else { var r = 1.0; var i = 0.0; while (i < k && !r.isInfinite()) { r *= n - i; i++ }; FV.of(r) }
    }
    reg("GCD", "math", 1, MANY) { a -> var g = 0.0; for (x in wholes(a)) g = gcd(g, x); FV.of(g) }
    reg("LCM", "math", 1, MANY) { a -> var l = 1.0; for (x in wholes(a)) l = if (x == 0.0 || l == 0.0) 0.0 else l / gcd(l, x) * x; FV.of(l) }
    // ---------------- random ----------------
    reg("RAND", "math", 0, volatile = true) { FV.Num(Math.random()) }
    reg("RANDBETWEEN", "math", 2, volatile = true) { a ->
        val lo = Math.ceil(a.num(0)); val hi = Math.floor(a.num(1))
        if (lo > hi) FV.NUM else FV.of(lo + Math.floor(Math.random() * (hi - lo + 1)))
    }
    // ---------------- sums ----------------
    reg("SUMSQ", "math", 1, MANY) { a -> FV.of(a.numbers().sumOf { it * it }) }
    reg("SUMPRODUCT", "math", 1, MANY) { a ->
        val arrs = (0 until a.size).map { a.arr(it) }
        if (arrs.any { it.rows != arrs[0].rows || it.cols != arrs[0].cols }) FV.VALUE else {
            var s = 0.0
            for (i in arrs[0].data.indices) {
                var p = 1.0
                // text, booleans and blanks count as 0; an error anywhere is the result
                for (x in arrs) when (val v = x.data[i]) { is FV.Num -> p *= v.v; is FV.Err -> throw FxError(v); else -> p = 0.0 }
                s += p
            }
            FV.of(s)
        }
    }
    reg("SUMIFS", "math", 3, MANY) { a -> FV.of(ifsNumbers(a).sum()) }
}

/**
 * The multiple of [step] that [x] rounds to with [mode]. Done in decimal arithmetic on the shortest text of the
 * doubles, so FLOOR(0.3, 0.1) is 0.3 and not 0.2 (0.3 / 0.1 is 2.9999999999999996 in binary).
 */
private fun multiple(x: Double, step: Double, mode: RoundingMode): Double {
    if (x.isNaN() || x.isInfinite() || step.isNaN() || step.isInfinite()) return Double.NaN
    val s = BigDecimal(java.lang.Double.toString(step))
    return BigDecimal(java.lang.Double.toString(x)).divide(s, 20, RoundingMode.HALF_EVEN).setScale(0, mode).multiply(s).toDouble()
}

/** Arguments of GCD / LCM truncated to whole numbers; a negative one is #NUM!. */
private fun wholes(a: FxArgs): List<Double> = a.numbers().map { val t = truncate(it); if (t < 0 || t >= 9.007199254740992E15) throw FxError(FV.NUM); t }

private fun gcd(a: Double, b: Double): Double { var x = a; var y = b; while (y != 0.0) { val t = x % y; x = y; y = t }; return x }
