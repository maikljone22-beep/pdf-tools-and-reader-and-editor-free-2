package com.smartdocs.editor.core.office5

import kotlin.math.abs

/**
 * Financial functions. Cash paid out is negative and cash received positive, like Excel; `type` 0 pays at the end of
 * each period and 1 at its start.
 */
internal fun FxFunctions.registerFinance() {
    reg("PMT", "fin", 3, 5) { a -> FV.of(payment(a.num(0), a.num(1), a.num(2), a.num(3, 0.0), due(a, 4))) }
    reg("FV", "fin", 3, 5) { a -> FV.of(futureValue(a.num(0), a.num(1), a.num(2), a.num(3, 0.0), due(a, 4))) }
    reg("PV", "fin", 3, 5) { a ->
        val r = a.num(0); val n = a.num(1); val pmt = a.num(2); val fv = a.num(3, 0.0)
        if (r == 0.0) FV.of(-(fv + pmt * n)) else { val p = Math.pow(1 + r, n); FV.of(-(fv + pmt * (1 + r * due(a, 4)) * (p - 1) / r) / p) }
    }
    reg("NPER", "fin", 3, 5) { a ->
        val r = a.num(0); val pmt = a.num(1); val pv = a.num(2); val fv = a.num(3, 0.0)
        if (r == 0.0) (if (pmt == 0.0) FV.NUM else FV.of(-(pv + fv) / pmt))
        else { val z = pmt * (1 + r * due(a, 4)) / r; FV.of(Math.log((z - fv) / (z + pv)) / Math.log(1 + r)) }   // a log of a negative is #NUM!
    }
    reg("RATE", "fin", 3, 6) { a ->
        val n = a.num(0); val pmt = a.num(1); val pv = a.num(2); val fv = a.num(3, 0.0); val t = due(a, 4)
        // the rate at which the balance after n periods is zero
        solveRate(a.num(5, 0.1), abs(pv) + abs(pmt * n) + abs(fv)) { r ->
            // (1 + r)^n - 1 through expm1 / log1p, which stays exact when the rate is close to 0
            if (r == 0.0) pv + pmt * n + fv else { val g = Math.expm1(n * Math.log1p(r)); pv * (g + 1) + pmt * (1 + r * t) * g / r + fv }
        }
    }
    reg("IPMT", "fin", 4, 6) { a ->
        val per = a.num(1); val n = a.num(2)
        if (per < 1 || per > n) FV.NUM else FV.of(interest(a.num(0), per, n, a.num(3), a.num(4, 0.0), due(a, 5)))
    }
    reg("PPMT", "fin", 4, 6) { a ->
        val r = a.num(0); val per = a.num(1); val n = a.num(2); val pv = a.num(3); val fv = a.num(4, 0.0); val t = due(a, 5)
        if (per < 1 || per > n) FV.NUM else FV.of(payment(r, n, pv, fv, t) - interest(r, per, n, pv, fv, t))
    }
    reg("NPV", "fin", 2, MANY) { a ->
        val r = a.num(0); var s = 0.0; var d = 1.0
        if (r == -1.0) FV.DIV0 else { for (v in a.numbers(1)) { d *= 1 + r; s += v / d }; FV.of(s) }
    }
    reg("IRR", "fin", 1, 2) { a ->
        val v = numbersOf(a.array(0))
        if (v.none { it > 0 } || v.none { it < 0 }) FV.NUM
        else solveRate(a.num(1, 0.1), v.sumOf { abs(it) }) { r -> var s = 0.0; var d = 1.0; for (x in v) { s += x / d; d *= 1 + r }; s }
    }
    reg("SLN", "fin", 3) { a -> val life = a.num(2); if (life == 0.0) FV.DIV0 else FV.of((a.num(0) - a.num(1)) / life) }
}

/** The `type` argument as 1.0 (payments at the start of the period) or 0.0. */
private fun due(a: FxArgs, i: Int): Double = if (a.num(i, 0.0) != 0.0) 1.0 else 0.0

private fun payment(r: Double, n: Double, pv: Double, fv: Double, t: Double): Double {
    if (r == 0.0) return -(pv + fv) / n
    val p = Math.pow(1 + r, n)
    return -(pv * p + fv) * r / ((p - 1) * (1 + r * t))
}

private fun futureValue(r: Double, n: Double, pmt: Double, pv: Double, t: Double): Double {
    if (r == 0.0) return -(pv + pmt * n)
    val p = Math.pow(1 + r, n)
    return -(pv * p + pmt * (1 + r * t) * (p - 1) / r)
}

/** Interest part of payment [per]: the rate on the balance left after the payments before it. */
private fun interest(r: Double, per: Double, n: Double, pv: Double, fv: Double, t: Double): Double {
    if (t != 0.0 && per == 1.0) return 0.0
    val i = futureValue(r, per - 1, payment(r, n, pv, fv, t), pv, t) * r
    return if (t != 0.0) i / (1 + r) else i
}

/**
 * Newton's method from [guess] with a numeric slope, for the rate where [f] is zero. [scale] is the size of the cash
 * involved: a step that stops moving only counts as the answer when [f] is really zero against it (near -100% the
 * steps shrink without one). #NUM! when it does not settle on a rate above -100%.
 */
private inline fun solveRate(guess: Double, scale: Double, f: (Double) -> Double): FV {
    var r = guess
    repeat(100) {
        val y = f(r); val h = 1e-6 * maxOf(1.0, abs(r)); val slope = (f(r + h) - f(r - h)) / (2 * h)
        if (y.isNaN() || slope.isNaN() || slope == 0.0) return FV.NUM
        var next = r - y / slope
        if (next <= -1.0) next = (r - 1.0) / 2                    // never step past -100%: go half way there instead
        if (abs(next - r) <= 1e-11 * maxOf(1.0, abs(next)) && abs(f(next)) <= 1e-7 * scale) return FV.of(next)
        r = next
    }
    return FV.NUM
}
