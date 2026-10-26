package com.smartdocs.editor.core.office5

/** What the evaluator needs from the workbook. Sheet null = the sheet of the formula being evaluated. */
interface FxContext {
    fun value(sheet: String?, row: Int, col: Int): FV
    fun sheetExists(sheet: String): Boolean
    /** Used area of a sheet (to bound A:A / 1:1 ranges), or null when empty. */
    fun usedRange(sheet: String?): CRange?
    /** Row/col of the formula cell (implicit intersection). */
    val row: Int
    val col: Int
}

/** Raised for a function the engine does not implement: the caller keeps Excel's cached value. */
class FxUnsupported(val name: String) : RuntimeException(name, null, false, false)

/** Evaluates formula trees. Areas evaluate to [FV.Arr]; [scalar] applies Excel's implicit intersection. */
class FxEval(val ctx: FxContext) {

    fun evaluate(f: Fx): FV = try { scalar(f) } catch (e: FxError) { e.err }

    /** Value of [f]; an area becomes an array. */
    fun eval(f: Fx): FV = when (f) {
        is Fx.Num -> FV.Num(f.v)
        is Fx.Str -> FV.Str(f.v)
        is Fx.Bool -> FV.Bool(f.v)
        is Fx.Err -> FV.Err(f.code)
        is Fx.Ref -> { checkSheet(f.sheet); ctx.value(f.sheet, f.ref.row, f.ref.col) }
        is Fx.Area -> area(f)
        is Fx.Paren -> eval(f.e)
        is Fx.Unary -> { val v = FxCoerce.num(scalar(f.e)); FV.of(if (f.op == '-') -v else v) }
        is Fx.Percent -> FV.of(FxCoerce.num(scalar(f.e)) / 100.0)
        is Fx.Bin -> binary(f)
        is Fx.Call -> call(f)
        is Fx.Name -> FV.NAME
        Fx.Missing -> FV.Blank
    }

    private fun checkSheet(s: String?) { if (s != null && !ctx.sheetExists(s)) throw FxError(FV.REF) }

    /** Bounded rectangle of an area (whole columns/rows are clipped to the used range). */
    fun bounds(a: Fx.Area): CRange {
        val r = a.range
        if (a.kind == Fx.Area.Kind.CELLS) return r
        val used = ctx.usedRange(a.sheet) ?: return CRange(0, 0, -1, -1)
        return if (a.kind == Fx.Area.Kind.COLS) CRange(0, r.c1, minOf(used.r2, MAX_ROWS - 1), r.c2)
        else CRange(r.r1, 0, r.r2, minOf(used.c2, MAX_COLS - 1))
    }

    fun area(a: Fx.Area): FV {
        checkSheet(a.sheet)
        val b = bounds(a)
        if (b.r2 < b.r1 || b.c2 < b.c1) return FV.Arr(0, 0, emptyArray())
        if (b.size > 2_000_000) throw FxError(FV.NUM)
        val data = Array<FV>((b.rows * b.cols)) { FV.Blank }
        var i = 0
        for (r in b.r1..b.r2) for (c in b.c1..b.c2) data[i++] = ctx.value(a.sheet, r, c)
        return FV.Arr(b.rows, b.cols, data)
    }

    /** Scalar value: errors propagate as values, areas use implicit intersection with the formula cell. */
    fun scalar(f: Fx): FV {
        val v = eval(f)
        if (v !is FV.Arr) return v
        if (v.rows == 1 && v.cols == 1) return v[0, 0]
        if (f is Fx.Area) {
            val b = bounds(f)
            if (b.cols == 1 && ctx.row in b.r1..b.r2) return v[ctx.row - b.r1, 0]
            if (b.rows == 1 && ctx.col in b.c1..b.c2) return v[0, ctx.col - b.c1]
            return FV.VALUE
        }
        return v.first()
    }

    private fun binary(f: Fx.Bin): FV {
        val a = scalar(f.a); if (a is FV.Err) return a
        val b = scalar(f.b); if (b is FV.Err) return b
        return when (f.op) {
            "+" -> FV.of(FxCoerce.num(a) + FxCoerce.num(b))
            "-" -> FV.of(FxCoerce.num(a) - FxCoerce.num(b))
            "*" -> FV.of(FxCoerce.num(a) * FxCoerce.num(b))
            "/" -> { val d = FxCoerce.num(b); val n = FxCoerce.num(a); if (d == 0.0) FV.DIV0 else FV.of(n / d) }
            "^" -> {
                val x = FxCoerce.num(a); val y = FxCoerce.num(b)
                if (x == 0.0 && y == 0.0) FV.NUM else if (x == 0.0 && y < 0) FV.DIV0 else FV.of(Math.pow(x, y))
            }
            "&" -> FV.Str(FxCoerce.str(a) + FxCoerce.str(b))
            "=" -> FV.Bool(FxCoerce.compare(a, b) == 0)
            "<>" -> FV.Bool(FxCoerce.compare(a, b) != 0)
            "<" -> FV.Bool(FxCoerce.compare(a, b) < 0)
            ">" -> FV.Bool(FxCoerce.compare(a, b) > 0)
            "<=" -> FV.Bool(FxCoerce.compare(a, b) <= 0)
            ">=" -> FV.Bool(FxCoerce.compare(a, b) >= 0)
            else -> FV.VALUE
        }
    }

    private fun call(f: Fx.Call): FV {
        val name = f.name.removePrefix("_XLFN.").removePrefix("_XLWS.").removePrefix("COM.MICROSOFT.")
        val fn = FxFunctions[name] ?: throw FxUnsupported(name)
        if (f.args.size < fn.minArgs || f.args.size > fn.maxArgs) return FV.VALUE
        return try { fn.impl.call(FxArgs(this, f.args)) } catch (e: FxError) { e.err }
    }
}

/** Lazy access to call arguments, so IF/IFERROR/CHOOSE evaluate only what they need. */
class FxArgs(val ev: FxEval, val nodes: List<Fx>) {
    val size: Int get() = nodes.size
    fun missing(i: Int) = i >= nodes.size || nodes[i] === Fx.Missing

    /** Scalar value (errors returned as values). */
    fun value(i: Int): FV = if (missing(i)) FV.Blank else ev.scalar(nodes[i])
    /** Raw value: areas stay arrays. */
    fun raw(i: Int): FV = if (missing(i)) FV.Blank else ev.eval(nodes[i])
    fun isRef(i: Int) = !missing(i) && (nodes[i] is Fx.Area || nodes[i] is Fx.Ref)

    fun num(i: Int, def: Double? = null): Double = if (missing(i) && def != null) def else FxCoerce.num(value(i))
    fun int(i: Int, def: Int? = null): Int = if (missing(i) && def != null) def else num(i).let { if (it >= 0) Math.floor(it) else Math.ceil(it) }.toInt()
    fun str(i: Int, def: String? = null): String = if (missing(i) && def != null) def else FxCoerce.str(value(i))
    fun bool(i: Int, def: Boolean? = null): Boolean = if (missing(i) && def != null) def else FxCoerce.bool(value(i))

    /** Array (or 1x1 array of a scalar) of argument [i]. */
    fun array(i: Int): FV.Arr = when (val v = raw(i)) { is FV.Arr -> v; else -> FV.Arr(1, 1, arrayOf(v)) }

    /**
     * Calls [f] for each value of every argument from [from], Excel aggregate style: values inside references
     * are passed with fromRef = true (callers usually skip their text/booleans), direct values with false.
     */
    inline fun each(from: Int = 0, f: (FV, Boolean) -> Unit) {
        for (i in from until size) {
            if (missing(i)) continue
            val n = nodes[i]
            if (n is Fx.Area || n is Fx.Ref) {
                when (val v = ev.eval(n)) { is FV.Arr -> v.data.forEach { f(it, true) }; else -> f(v, true) }
            } else when (val v = ev.eval(n)) { is FV.Arr -> v.data.forEach { f(it, true) }; else -> f(v, false) }
        }
    }

    /** Numbers of the arguments (aggregate rules: text and booleans in references are skipped, errors propagate). */
    fun numbers(from: Int = 0): DoubleArray {
        val out = ArrayList<Double>()
        each(from) { v, ref ->
            when (v) {
                is FV.Num -> out.add(v.v)
                is FV.Err -> throw FxError(v)
                is FV.Bool -> if (!ref) out.add(if (v.v) 1.0 else 0.0)
                is FV.Str -> if (!ref) out.add(FxCoerce.num(v))
                else -> {}
            }
        }
        return out.toDoubleArray()
    }
}
