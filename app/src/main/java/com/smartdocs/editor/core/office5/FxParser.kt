package com.smartdocs.editor.core.office5

/** Formula syntax tree. */
sealed class Fx {
    class Num(val v: Double, val text: String) : Fx()
    class Str(val v: String) : Fx()
    class Bool(val v: Boolean) : Fx()
    class Err(val code: String) : Fx()
    class Ref(val sheet: String?, val ref: CellRef) : Fx()
    /** A range; [kind] tells how it was written (A1:B2, A:C, 1:3). */
    class Area(val sheet: String?, val a: CellRef, val b: CellRef, val kind: Kind = Kind.CELLS) : Fx() {
        enum class Kind { CELLS, COLS, ROWS }
        val range: CRange get() = CRange.of(a.row, a.col, b.row, b.col)
    }
    class Unary(val op: Char, val e: Fx) : Fx()
    class Percent(val e: Fx) : Fx()
    class Bin(val op: String, val a: Fx, val b: Fx) : Fx()
    class Call(val name: String, val args: List<Fx>) : Fx()
    class Name(val id: String) : Fx()
    class Paren(val e: Fx) : Fx()
    object Missing : Fx()
}

class FxParseException(msg: String, val pos: Int) : Exception(msg)

/**
 * Excel formula parser (text without the leading '='). Precedence, low to high: comparison, &, + -,
 * * /, ^, %, unary minus (so -2^2 = 4 like Excel), reference ranges. `;` is accepted as argument separator.
 */
class FxParser private constructor(private val s: String) {
    private var i = 0

    private sealed class Tok {
        data class Num(val v: Double, val text: String) : Tok()
        data class Str(val v: String) : Tok()
        data class Err(val code: String) : Tok()
        data class Ref(val fx: Fx) : Tok()
        data class Func(val name: String) : Tok()
        data class Ident(val name: String) : Tok()
        data class Op(val op: String) : Tok()
        object End : Tok()
    }

    private val toks = ArrayList<Pair<Tok, Int>>()
    private var k = 0

    private fun lex() {
        while (true) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) { toks.add(Tok.End to i); return }
            val start = i
            val c = s[i]
            val t: Tok = when {
                c == '"' -> readString()
                c == '#' -> readError()
                c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> readRef() ?: readNumber()
                c == '\'' || c == '$' || c.isLetter() || c == '_' -> readRef() ?: readIdent()
                c == '<' && peek(1) == '=' -> op("<=", 2)
                c == '>' && peek(1) == '=' -> op(">=", 2)
                c == '<' && peek(1) == '>' -> op("<>", 2)
                c in "+-*/^&=<>%(),;:" -> op(c.toString(), 1)
                else -> throw FxParseException("unexpected '$c'", i)
            }
            toks.add(t to start)
        }
    }

    private fun peek(d: Int) = s.getOrNull(i + d)
    private fun op(o: String, n: Int): Tok { i += n; return Tok.Op(if (o == ";") "," else o) }

    private fun readString(): Tok {
        val sb = StringBuilder(); i++
        while (true) {
            if (i >= s.length) throw FxParseException("unterminated string", i)
            val c = s[i]
            if (c == '"') { if (peek(1) == '"') { sb.append('"'); i += 2; continue }; i++; break }
            sb.append(c); i++
        }
        return Tok.Str(sb.toString())
    }

    private fun readError(): Tok {
        for (e in ERRS) if (s.regionMatches(i, e, 0, e.length, ignoreCase = true)) { i += e.length; return Tok.Err(e) }
        throw FxParseException("bad error literal", i)
    }

    private fun readNumber(): Tok {
        val m = NUM_RX.find(s, i)?.takeIf { it.range.first == i } ?: throw FxParseException("bad number", i)
        i = m.range.last + 1
        return Tok.Num(m.value.toDouble(), m.value)
    }

    private fun readIdent(): Tok {
        val st = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
        val name = s.substring(st, i)
        var j = i; while (j < s.length && s[j].isWhitespace()) j++
        if (j < s.length && s[j] == '(') { i = j; return Tok.Func(name.uppercase()) }
        return when (name.uppercase()) { "TRUE" -> Tok.Ref(Fx.Bool(true)); "FALSE" -> Tok.Ref(Fx.Bool(false)); else -> Tok.Ident(name) }
    }

    /** A cell/range/column/row reference with an optional sheet prefix at [i], or null (position unchanged). */
    private fun readRef(): Tok? {
        val m = REF_RX.find(s, i)?.takeIf { it.range.first == i } ?: return null
        val end = m.range.last + 1
        val next = s.getOrNull(end)
        if (next != null && (next.isLetterOrDigit() || next == '_' || next == '(' || next == '.')) return null
        val sheet = m.groups[1]?.value?.let { unquote(it) }
        val body = m.groups[2]!!.value
        val fx: Fx = run {
            val parts = body.split(':')
            if (parts.size == 1) {
                val r = A1.parseCell(parts[0]) ?: return null
                Fx.Ref(sheet, r)
            } else {
                val a = parts[0]; val b = parts[1]
                when {
                    a.trimStart('$').all { it.isDigit() } -> {
                        val r1 = a.trimStart('$').toInt() - 1; val r2 = b.trimStart('$').toInt() - 1
                        if (r1 < 0 || r2 < 0) return null
                        Fx.Area(sheet, CellRef(r1, 0, a.startsWith("$"), true), CellRef(r2, MAX_COLS - 1, b.startsWith("$"), true), Fx.Area.Kind.ROWS)
                    }
                    a.trimStart('$').all { it.isLetter() } -> {
                        val c1 = A1.colIndex(a.trimStart('$')); val c2 = A1.colIndex(b.trimStart('$'))
                        if (c1 < 0 || c2 < 0) return null
                        Fx.Area(sheet, CellRef(0, c1, true, a.startsWith("$")), CellRef(MAX_ROWS - 1, c2, true, b.startsWith("$")), Fx.Area.Kind.COLS)
                    }
                    else -> {
                        val ra = A1.parseCell(a) ?: return null; val rb = A1.parseCell(b) ?: return null
                        Fx.Area(sheet, ra, rb)
                    }
                }
            }
        }
        i = end
        return Tok.Ref(fx)
    }

    private fun unquote(p: String): String {
        val n = p.removeSuffix("!")
        return if (n.startsWith("'")) n.substring(1, n.length - 1).replace("''", "'") else n
    }

    // ---------------- grammar ----------------

    private val cur: Tok get() = toks[k].first
    private fun isOp(o: String) = (cur as? Tok.Op)?.op == o
    private fun expectOp(o: String) { if (!isOp(o)) throw FxParseException("expected '$o'", toks[k].second); k++ }

    private fun parseAll(): Fx {
        lex()
        if (cur is Tok.End) throw FxParseException("empty formula", 0)
        val e = comparison()
        if (cur !is Tok.End) throw FxParseException("unexpected input", toks[k].second)
        return e
    }

    private fun comparison(): Fx {
        var a = concat()
        while (true) {
            val o = (cur as? Tok.Op)?.op ?: return a
            if (o !in CMP) return a
            k++; a = Fx.Bin(o, a, concat())
        }
    }

    private fun concat(): Fx {
        var a = additive()
        while (isOp("&")) { k++; a = Fx.Bin("&", a, additive()) }
        return a
    }

    private fun additive(): Fx {
        var a = term()
        while (isOp("+") || isOp("-")) { val o = (cur as Tok.Op).op; k++; a = Fx.Bin(o, a, term()) }
        return a
    }

    private fun term(): Fx {
        var a = power()
        while (isOp("*") || isOp("/")) { val o = (cur as Tok.Op).op; k++; a = Fx.Bin(o, a, power()) }
        return a
    }

    private fun power(): Fx {
        var a = percent()
        while (isOp("^")) { k++; a = Fx.Bin("^", a, percent()) }
        return a
    }

    private fun percent(): Fx {
        var a = unary()
        while (isOp("%")) { k++; a = Fx.Percent(a) }
        return a
    }

    private fun unary(): Fx {
        if (isOp("-")) { k++; return Fx.Unary('-', unary()) }
        if (isOp("+")) { k++; return Fx.Unary('+', unary()) }
        return primary()
    }

    private fun primary(): Fx {
        val (t, pos) = toks[k]
        return when (t) {
            is Tok.Num -> { k++; Fx.Num(t.v, t.text) }
            is Tok.Str -> { k++; Fx.Str(t.v) }
            is Tok.Err -> { k++; Fx.Err(t.code) }
            is Tok.Ref -> { k++; t.fx }
            is Tok.Ident -> { k++; Fx.Name(t.name) }
            is Tok.Func -> {
                k++; expectOp("(")
                val args = ArrayList<Fx>()
                if (isOp(")")) { k++; return Fx.Call(t.name, args) }
                while (true) {
                    args.add(if (isOp(",") || isOp(")")) Fx.Missing else comparison())
                    if (isOp(",")) { k++; continue }
                    expectOp(")"); break
                }
                Fx.Call(t.name, args)
            }
            is Tok.Op -> if (t.op == "(") { k++; val e = comparison(); expectOp(")"); Fx.Paren(e) } else throw FxParseException("unexpected '${t.op}'", pos)
            Tok.End -> throw FxParseException("unexpected end", pos)
        }
    }

    companion object {
        private val ERRS = listOf("#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#N/A", "#NUM!", "#NULL!", "#CIRC!", "#SPILL!", "#CALC!")
        private val CMP = setOf("=", "<>", "<", ">", "<=", ">=")
        private val NUM_RX = Regex("""\d*\.?\d+(?:[eE][+-]?\d+)?|\d+\.""")
        private const val SHEET = """('(?:[^']|'')+'!|[A-Za-z_\u0600-\u06FF][\w.\u0600-\u06FF]*!)"""
        private val REF_RX = Regex("""$SHEET?(\$?[A-Za-z]{1,3}\$?\d{1,7}(?::\$?[A-Za-z]{1,3}\$?\d{1,7})?|\$?[A-Za-z]{1,3}:\$?[A-Za-z]{1,3}|\$?\d{1,7}:\$?\d{1,7})""")

        fun parse(formula: String): Fx = FxParser(formula.trim().removePrefix("=")).parseAll()
        fun tryParse(formula: String): Fx? = runCatching { parse(formula) }.getOrNull()
    }
}

/** Formula text from a tree (no leading '='), plus reference rewriting used by copy/fill/insert/delete/rename. */
object FxPrinter {
    fun print(f: Fx): String = StringBuilder().also { p(f, it) }.toString()

    private fun p(f: Fx, sb: StringBuilder) {
        when (f) {
            is Fx.Num -> sb.append(f.text)
            is Fx.Str -> sb.append('"').append(f.v.replace("\"", "\"\"")).append('"')
            is Fx.Bool -> sb.append(if (f.v) "TRUE" else "FALSE")
            is Fx.Err -> sb.append(f.code)
            is Fx.Ref -> { f.sheet?.let { sb.append(A1.quoteSheet(it)).append('!') }; sb.append(f.ref.a1()) }
            is Fx.Area -> { f.sheet?.let { sb.append(A1.quoteSheet(it)).append('!') }; sb.append(area(f)) }
            is Fx.Unary -> { sb.append(f.op); p(f.e, sb) }
            is Fx.Percent -> { p(f.e, sb); sb.append('%') }
            is Fx.Bin -> { p(f.a, sb); sb.append(f.op); p(f.b, sb) }
            is Fx.Call -> { sb.append(f.name).append('('); f.args.forEachIndexed { i, a -> if (i > 0) sb.append(','); p(a, sb) }; sb.append(')') }
            is Fx.Name -> sb.append(f.id)
            is Fx.Paren -> { sb.append('('); p(f.e, sb); sb.append(')') }
            Fx.Missing -> {}
        }
    }

    private fun area(a: Fx.Area): String = when (a.kind) {
        Fx.Area.Kind.CELLS -> a.a.a1() + ":" + a.b.a1()
        Fx.Area.Kind.COLS -> (if (a.a.absCol) "$" else "") + A1.colName(a.a.col) + ":" + (if (a.b.absCol) "$" else "") + A1.colName(a.b.col)
        Fx.Area.Kind.ROWS -> (if (a.a.absRow) "$" else "") + (a.a.row + 1) + ":" + (if (a.b.absRow) "$" else "") + (a.b.row + 1)
    }

    /** Rebuilds the tree mapping every reference; [onRef]/[onArea] return the replacement node. */
    fun transform(f: Fx, onRef: (Fx.Ref) -> Fx, onArea: (Fx.Area) -> Fx): Fx = when (f) {
        is Fx.Ref -> onRef(f)
        is Fx.Area -> onArea(f)
        is Fx.Unary -> Fx.Unary(f.op, transform(f.e, onRef, onArea))
        is Fx.Percent -> Fx.Percent(transform(f.e, onRef, onArea))
        is Fx.Bin -> Fx.Bin(f.op, transform(f.a, onRef, onArea), transform(f.b, onRef, onArea))
        is Fx.Call -> Fx.Call(f.name, f.args.map { transform(it, onRef, onArea) })
        is Fx.Paren -> Fx.Paren(transform(f.e, onRef, onArea))
        else -> f
    }

    /** Copies a formula by (dr, dc): relative parts move, absolute parts stay, refs falling off the sheet become #REF!. */
    fun translate(f: Fx, dr: Int, dc: Int): Fx {
        fun mv(r: CellRef): CellRef? {
            val nr = if (r.absRow) r.row else r.row + dr
            val nc = if (r.absCol) r.col else r.col + dc
            return if (nr !in 0 until MAX_ROWS || nc !in 0 until MAX_COLS) null else r.copy(row = nr, col = nc)
        }
        return transform(f, { x -> mv(x.ref)?.let { Fx.Ref(x.sheet, it) } ?: Fx.Err("#REF!") }, { a ->
            when (a.kind) {
                Fx.Area.Kind.CELLS -> { val p = mv(a.a); val q = mv(a.b); if (p == null || q == null) Fx.Err("#REF!") else Fx.Area(a.sheet, p, q) }
                Fx.Area.Kind.COLS -> { val p = mv(a.a.copy(row = 0)); val q = mv(a.b.copy(row = 0)); if (p == null || q == null) Fx.Err("#REF!") else Fx.Area(a.sheet, p.copy(row = 0), q.copy(row = MAX_ROWS - 1), a.kind) }
                Fx.Area.Kind.ROWS -> { val p = mv(a.a.copy(col = 0)); val q = mv(a.b.copy(col = 0)); if (p == null || q == null) Fx.Err("#REF!") else Fx.Area(a.sheet, p.copy(col = 0), q.copy(col = MAX_COLS - 1), a.kind) }
            }
        })
    }

    /**
     * Adjusts references into the sheet matched by [targets] after inserting (count > 0) or deleting
     * (count < 0) rows ([rows] = true) or columns at [at]. Returns null when nothing changed.
     */
    fun shift(f: Fx, targets: (String?) -> Boolean, rows: Boolean, at: Int, count: Int): Fx? {
        var changed = false
        val out = transform(f, { x ->
            if (!targets(x.sheet)) x else {
                val idx = if (rows) x.ref.row else x.ref.col
                val n = A1.shiftIndex(idx, at, count)
                if (n == idx) x else { changed = true; if (n == null) Fx.Err("#REF!") else Fx.Ref(x.sheet, if (rows) x.ref.copy(row = n) else x.ref.copy(col = n)) }
            }
        }, { a ->
            if (!targets(a.sheet) || (rows && a.kind == Fx.Area.Kind.COLS) || (!rows && a.kind == Fx.Area.Kind.ROWS)) a else {
                val r = a.range
                val nr = A1.shiftRange(r, rows, at, count)
                if (nr == r) a else {
                    changed = true
                    if (nr == null) Fx.Err("#REF!")
                    else Fx.Area(a.sheet, a.a.copy(row = nr.r1, col = nr.c1), a.b.copy(row = nr.r2, col = nr.c2), a.kind)
                }
            }
        })
        return if (changed) out else null
    }

    /** Renames sheet references ([old] → [new]); null when the formula does not mention [old]. */
    fun renameSheet(f: Fx, old: String, new: String): Fx? {
        var changed = false
        val out = transform(f, { x -> if (x.sheet.equals(old, true)) { changed = true; Fx.Ref(new, x.ref) } else x },
            { a -> if (a.sheet.equals(old, true)) { changed = true; Fx.Area(new, a.a, a.b, a.kind) } else a })
        return if (changed) out else null
    }

    /** Replaces references to sheet [name] by #REF! (sheet deleted); null when not mentioned. */
    fun dropSheet(f: Fx, name: String): Fx? {
        var changed = false
        val out = transform(f, { x -> if (x.sheet.equals(name, true)) { changed = true; Fx.Err("#REF!") } else x },
            { a -> if (a.sheet.equals(name, true)) { changed = true; Fx.Err("#REF!") } else a })
        return if (changed) out else null
    }

    /** Every reference of a formula: (sheet, range). */
    fun refs(f: Fx, out: MutableList<Pair<String?, CRange>> = ArrayList()): MutableList<Pair<String?, CRange>> {
        when (f) {
            is Fx.Ref -> out.add(f.sheet to CRange.cell(f.ref.row, f.ref.col))
            is Fx.Area -> out.add(f.sheet to f.range)
            is Fx.Unary -> refs(f.e, out)
            is Fx.Percent -> refs(f.e, out)
            is Fx.Bin -> { refs(f.a, out); refs(f.b, out) }
            is Fx.Call -> f.args.forEach { refs(it, out) }
            is Fx.Paren -> refs(f.e, out)
            else -> {}
        }
        return out
    }

    /** Names of the functions called (for volatile detection). */
    fun calls(f: Fx, out: MutableSet<String> = HashSet()): MutableSet<String> {
        when (f) {
            is Fx.Call -> { out.add(f.name); f.args.forEach { calls(it, out) } }
            is Fx.Unary -> calls(f.e, out)
            is Fx.Percent -> calls(f.e, out)
            is Fx.Bin -> { calls(f.a, out); calls(f.b, out) }
            is Fx.Paren -> calls(f.e, out)
            else -> {}
        }
        return out
    }
}
