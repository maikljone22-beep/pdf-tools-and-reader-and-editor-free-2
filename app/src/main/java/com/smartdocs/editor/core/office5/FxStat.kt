package com.smartdocs.editor.core.office5

import kotlin.math.truncate

/** Statistical functions beyond the basic set in [FxFunctions]. */
internal fun FxFunctions.registerStat() {
    reg("MEDIAN", "stat", 1, MANY) { a ->
        val n = a.numbers().also { it.sort() }
        if (n.isEmpty()) FV.NUM else FV.of(if (n.size % 2 == 1) n[n.size / 2] else (n[n.size / 2 - 1] + n[n.size / 2]) / 2)
    }
    for (name in listOf("MODE", "MODE.SNGL")) reg(name, "stat", 1, MANY) { a ->
        // the most frequent number, the first one met among equals; #N/A when nothing repeats
        val seen = LinkedHashMap<Double, Int>()
        for (x in a.numbers()) seen[x + 0.0] = (seen[x + 0.0] ?: 0) + 1
        val best = seen.maxByOrNull { it.value }
        if (best == null || best.value < 2) FV.NA else FV.Num(best.key)
    }
    for (name in listOf("STDEV", "STDEV.S")) reg(name, "stat", 1, MANY) { a -> variance(a.numbers(), sample = true, root = true) }
    for (name in listOf("STDEV.P", "STDEVP")) reg(name, "stat", 1, MANY) { a -> variance(a.numbers(), sample = false, root = true) }
    for (name in listOf("VAR", "VAR.S")) reg(name, "stat", 1, MANY) { a -> variance(a.numbers(), sample = true, root = false) }
    for (name in listOf("VAR.P", "VARP")) reg(name, "stat", 1, MANY) { a -> variance(a.numbers(), sample = false, root = false) }
    reg("LARGE", "stat", 2) { a -> val n = numbersOf(a.array(0)).also { it.sort() }; val k = a.int(1); if (k < 1 || k > n.size) FV.NUM else FV.Num(n[n.size - k]) }
    reg("SMALL", "stat", 2) { a -> val n = numbersOf(a.array(0)).also { it.sort() }; val k = a.int(1); if (k < 1 || k > n.size) FV.NUM else FV.Num(n[k - 1]) }
    for (name in listOf("RANK", "RANK.EQ")) reg(name, "stat", 2, 3) { a ->
        val x = a.num(0); val n = numbersOf(a.array(1)); val ascending = a.num(2, 0.0) != 0.0
        if (n.none { it == x }) FV.NA else FV.Num(1.0 + n.count { if (ascending) it < x else it > x })
    }
    for (name in listOf("PERCENTILE", "PERCENTILE.INC")) reg(name, "stat", 2) { a -> percentile(numbersOf(a.array(0)), a.num(1)) }
    for (name in listOf("QUARTILE", "QUARTILE.INC")) reg(name, "stat", 2) { a ->
        val n = numbersOf(a.array(0)); val q = truncate(a.num(1))
        if (q < 0 || q > 4) FV.NUM else percentile(n, q / 4)
    }
    reg("AVERAGEA", "stat", 1, MANY) { a -> val n = numbersA(a); if (n.isEmpty()) FV.DIV0 else FV.of(n.sum() / n.size) }
    reg("MAXA", "stat", 1, MANY) { a -> FV.of(numbersA(a).maxOrNull() ?: 0.0) }
    reg("MINA", "stat", 1, MANY) { a -> FV.of(numbersA(a).minOrNull() ?: 0.0) }
    reg("GEOMEAN", "stat", 1, MANY) { a ->
        val n = a.numbers()
        if (n.isEmpty() || n.any { it <= 0 }) FV.NUM else FV.of(Math.exp(n.sumOf { Math.log(it) } / n.size))
    }
    reg("HARMEAN", "stat", 1, MANY) { a ->
        val n = a.numbers()
        if (n.isEmpty() || n.any { it <= 0 }) FV.NUM else FV.of(n.size / n.sumOf { 1 / it })
    }
    // ---------------- conditions ----------------
    reg("COUNTIFS", "stat", 2, MANY) { a -> FV.Num(ifsMask(a, 0, null).count { it }.toDouble()) }
    reg("AVERAGEIFS", "stat", 3, MANY) { a -> val n = ifsNumbers(a); if (n.isEmpty()) FV.DIV0 else FV.of(n.sum() / n.size) }
    reg("MAXIFS", "stat", 3, MANY) { a -> FV.of(ifsNumbers(a).maxOrNull() ?: 0.0) }
    reg("MINIFS", "stat", 3, MANY) { a -> FV.of(ifsNumbers(a).minOrNull() ?: 0.0) }
    // ---------------- two variables ----------------
    reg("CORREL", "stat", 2) { a -> val f = Fit(a.array(0), a.array(1), FV.DIV0); if (f.sxx == 0.0 || f.syy == 0.0) FV.DIV0 else FV.of(f.sxy / Math.sqrt(f.sxx * f.syy)) }
    reg("SLOPE", "stat", 2) { a -> val f = Fit(a.array(0), a.array(1)); if (f.sxx == 0.0) FV.DIV0 else FV.of(f.sxy / f.sxx) }
    reg("INTERCEPT", "stat", 2) { a -> val f = Fit(a.array(0), a.array(1)); if (f.sxx == 0.0) FV.DIV0 else FV.of(f.my - f.sxy / f.sxx * f.mx) }
    for (name in listOf("FORECAST", "FORECAST.LINEAR")) reg(name, "stat", 3) { a ->
        val x = a.num(0); val f = Fit(a.array(1), a.array(2))
        if (f.sxx == 0.0) FV.DIV0 else FV.of(f.my + f.sxy / f.sxx * (x - f.mx))
    }
}

/** Numbers of an array argument (LARGE, RANK, PERCENTILE…): text, booleans and blanks are skipped, an error is the result. */
internal fun numbersOf(arr: FV.Arr): DoubleArray {
    val out = ArrayList<Double>()
    for (v in arr.data) when (v) { is FV.Num -> out.add(v.v); is FV.Err -> throw FxError(v); else -> {} }
    return out.toDoubleArray()
}

/** What AVERAGEA / MAXA / MINA count: text inside references is 0, booleans are 1 and 0, blanks are skipped. */
private fun numbersA(a: FxArgs): DoubleArray {
    val out = ArrayList<Double>()
    a.each { v, ref ->
        when (v) {
            is FV.Num -> out.add(v.v)
            is FV.Bool -> out.add(if (v.v) 1.0 else 0.0)
            is FV.Str -> out.add(if (ref) 0.0 else FxCoerce.num(v))
            is FV.Err -> throw FxError(v)
            else -> {}
        }
    }
    return out.toDoubleArray()
}

private fun variance(n: DoubleArray, sample: Boolean, root: Boolean): FV {
    val d = if (sample) n.size - 1 else n.size
    if (d <= 0) return FV.DIV0
    val m = n.sum() / n.size; var s = 0.0
    for (x in n) s += (x - m) * (x - m)
    return FV.of(if (root) Math.sqrt(s / d) else s / d)
}

/** Inclusive percentile: [k] in 0..1 picks a position between the sorted values, interpolating linearly. */
private fun percentile(n: DoubleArray, k: Double): FV {
    if (n.isEmpty() || k < 0 || k > 1) return FV.NUM
    n.sort()
    val p = k * (n.size - 1); val i = Math.floor(p).toInt()
    return FV.of(if (i + 1 < n.size) n[i] + (p - i) * (n[i + 1] - n[i]) else n[i])
}

/**
 * Cells matched by the (range, criteria) pairs from argument [from] on. Every range must have the shape of
 * [shape] (or of the first range when it is null), else #VALUE!.
 */
internal fun ifsMask(a: FxArgs, from: Int, shape: FV.Arr?): BooleanArray {
    if ((a.size - from) % 2 != 0) throw FxError(FV.VALUE)
    var ref = shape; var mask: BooleanArray? = null; var i = from
    while (i < a.size) {
        val r = a.array(i); val m = FxCriteria.of(a.value(i + 1))
        val first = ref ?: r
        if (r.rows != first.rows || r.cols != first.cols) throw FxError(FV.VALUE)
        ref = first
        val mk = mask ?: BooleanArray(r.data.size) { true }
        for (k in r.data.indices) if (mk[k] && !m(r.data[k])) mk[k] = false
        mask = mk; i += 2
    }
    return mask ?: BooleanArray(0)
}

/** Numbers of the first argument in the cells the following (range, criteria) pairs match (SUMIFS, AVERAGEIFS, MAXIFS, MINIFS). */
internal fun ifsNumbers(a: FxArgs): DoubleArray {
    val vals = a.array(0); val mask = ifsMask(a, 1, vals)
    val out = ArrayList<Double>()
    for (i in mask.indices) if (mask[i]) when (val v = vals.data[i]) { is FV.Num -> out.add(v.v); is FV.Err -> throw FxError(v); else -> {} }
    return out.toDoubleArray()
}

/**
 * Sums of a least-squares fit of [ys] on [xs]. A pair counts when both cells are numbers; arrays of different
 * sizes are #N/A, no pair at all is [empty], an error in either array is the result.
 */
private class Fit(ys: FV.Arr, xs: FV.Arr, empty: FV.Err = FV.NA) {
    val mx: Double; val my: Double
    var sxx = 0.0; var syy = 0.0; var sxy = 0.0

    init {
        if (ys.data.size != xs.data.size) throw FxError(FV.NA)
        val x = ArrayList<Double>(); val y = ArrayList<Double>()
        for (i in xs.data.indices) {
            val p = xs.data[i]; val q = ys.data[i]
            if (q is FV.Err) throw FxError(q)
            if (p is FV.Err) throw FxError(p)
            if (p is FV.Num && q is FV.Num) { x.add(p.v); y.add(q.v) }
        }
        if (x.isEmpty()) throw FxError(empty)
        mx = x.sum() / x.size; my = y.sum() / y.size
        for (i in x.indices) { val dx = x[i] - mx; val dy = y[i] - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
    }
}
