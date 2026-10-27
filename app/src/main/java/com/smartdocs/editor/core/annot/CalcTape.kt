package com.smartdocs.editor.core.annot

import java.util.Locale

/**
 * Accounting calculator with a tape (Foxit 25.24-25.26): an adding-machine model. Every entry is a line
 * (operator + value); subtotal shows the running result, total closes it (and adds it to the grand total).
 * Lines can be edited or deleted and everything is recomputed; undo / redo keep whole tape states. The tape can
 * be printed as text and placed on the page as a text box comment. Pure Kotlin (unit tested).
 */
class CalcTape {
    enum class Op(val sign: String) { ADD("+"), SUB("−"), MUL("×"), DIV("÷"), SUBTOTAL("◇"), TOTAL("*") }

    data class Line(val op: Op, val value: Double = 0.0, val percent: Boolean = false)

    /** A computed tape row: the line and the running result after it. */
    data class Row(val line: Line, val result: Double)

    private val lines = ArrayList<Line>()
    private val undo = ArrayDeque<List<Line>>()
    private val redo = ArrayDeque<List<Line>>()
    var memory = 0.0; private set

    val size: Int get() = lines.size
    fun lines(): List<Line> = lines.toList()

    private fun snapshot() { undo.addLast(lines.toList()); if (undo.size > 100) undo.removeFirst(); redo.clear() }

    fun add(op: Op, value: Double, percent: Boolean = false) { snapshot(); lines += Line(op, value, percent) }
    fun subtotal() { snapshot(); lines += Line(Op.SUBTOTAL) }
    fun total() { snapshot(); lines += Line(Op.TOTAL) }
    fun delete(i: Int) { if (i in lines.indices) { snapshot(); lines.removeAt(i) } }
    fun edit(i: Int, value: Double) { if (i in lines.indices && lines[i].op !in setOf(Op.SUBTOTAL, Op.TOTAL)) { snapshot(); lines[i] = lines[i].copy(value = value) } }
    fun clear() { if (lines.isNotEmpty()) { snapshot(); lines.clear() } }
    fun undo(): Boolean { val p = undo.removeLastOrNull() ?: return false; redo.addLast(lines.toList()); lines.clear(); lines += p; return true }
    fun redo(): Boolean { val n = redo.removeLastOrNull() ?: return false; undo.addLast(lines.toList()); lines.clear(); lines += n; return true }

    fun memoryAdd(v: Double) { memory += v }
    fun memorySub(v: Double) { memory -= v }
    fun memoryClear() { memory = 0.0 }

    /** Recompute the whole tape: running results per row. */
    fun rows(): List<Row> {
        var acc = 0.0
        return lines.map { l ->
            val v = if (l.percent) acc * l.value / 100.0 else l.value
            when (l.op) {
                Op.ADD -> acc += v
                Op.SUB -> acc -= v
                Op.MUL -> acc *= v
                Op.DIV -> acc = if (v == 0.0) Double.NaN else acc / v
                Op.SUBTOTAL -> {}
                Op.TOTAL -> {}
            }
            val row = Row(l, acc)
            if (l.op == Op.TOTAL) acc = 0.0
            row
        }
    }

    /** Running result (after the last line). */
    fun current(): Double = rows().lastOrNull()?.let { if (it.line.op == Op.TOTAL) 0.0 else it.result } ?: 0.0

    /** Sum of every total line. */
    fun grandTotal(): Double = rows().filter { it.line.op == Op.TOTAL }.sumOf { it.result }

    /** Plain-text tape (for the page text box). */
    fun text(decimals: Int, header: String? = null): String = buildString {
        if (!header.isNullOrBlank()) append(header).append('\n')
        rows().forEach { r ->
            when (r.line.op) {
                Op.SUBTOTAL -> append(fmt(r.result, decimals)).append("  ").append(Op.SUBTOTAL.sign)
                Op.TOTAL -> append(fmt(r.result, decimals)).append("  ").append(Op.TOTAL.sign)
                else -> append(fmt(r.line.value, decimals)).append(if (r.line.percent) "%" else "").append("  ").append(r.line.op.sign)
            }
            append('\n')
        }
        if (rows().count { it.line.op == Op.TOTAL } > 1) append("GT ").append(fmt(grandTotal(), decimals)).append('\n')
    }.trimEnd()

    companion object {
        fun fmt(v: Double, decimals: Int): String =
            if (v.isNaN() || v.isInfinite()) "E" else String.format(Locale.US, "%,.${decimals.coerceIn(0, 8)}f", v)

        /** Unary keys applied to the entry before it is added. */
        fun sqrt(v: Double) = if (v < 0) Double.NaN else kotlin.math.sqrt(v)
        fun inverse(v: Double) = if (v == 0.0) Double.NaN else 1.0 / v
    }
}
