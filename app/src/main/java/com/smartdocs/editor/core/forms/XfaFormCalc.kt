package com.smartdocs.editor.core.forms

import java.util.Locale

/**
 * FormCalc → JavaScript (FINAL_CHECK_2: the many XFA forms that calculate / validate in FormCalc). A FormCalc script
 * is translated into JavaScript that runs on the same sandboxed XFA object model as the form's JavaScript
 * ([XfaJs]); the FormCalc semantics live in the `__fc` helpers of its library (null counts as 0 in arithmetic,
 * comparisons give 1 / 0, the value of the script is the value of its last expression).
 *
 * Supported: numbers, strings, null; `+ - * /`, unary `- + not`, comparisons (`== <> < > <= >=`, `eq ne lt gt le
 * ge`), `& | and or`; `var`, assignment, `if … then … elseif … else … endif`, `while … do … endwhile`,
 * `for … upto | downto … [step …] do … endfor`, `foreach … in (…) do … endfor`, `break`, `continue`; references
 * `A.B`, `$`, `A[*]`, `A[n]`, properties (`.rawValue`, `.presence` …); the functions of [FUNCTIONS] (arithmetic,
 * aggregate, string and date ones). Anything else (user functions, `$data` / `$record`, SOM `..` and `#` steps)
 * makes the translation fail ([toJs] = null): that script is then left out, as before.
 */
object XfaFormCalc {

    /** FormCalc built-ins the library implements (lower case; FormCalc names are case-insensitive). */
    val FUNCTIONS = setOf(
        "sum", "avg", "min", "max", "count", "round", "abs", "floor", "ceil", "mod", "pow",
        "concat", "len", "left", "right", "substr", "upper", "lower", "ltrim", "rtrim", "str", "at", "replace", "space", "repeat",
        "date", "num2date", "date2num", "hasvalue", "oneof", "within", "choose", "exists",
    )

    /** Object properties assigned as properties (any other reference target gets its value). */
    private val PROPS = setOf("rawvalue", "presence", "access", "mandatory", "formattedvalue")

    class FcError(msg: String) : Exception(msg)

    /** JavaScript for the FormCalc [src], or null when it uses something not supported (or does not parse). */
    fun toJs(src: String): String? = runCatching { Parser(lex(src)).program() }.getOrNull()

    // ------------------------------------------------------------------ lexer

    private enum class T { NUM, STR, ID, OP, EOF }
    private data class Tok(val t: T, val s: String)

    private val KEYWORDS = setOf("if", "then", "elseif", "else", "endif", "var", "while", "do", "endwhile", "for", "upto", "downto",
        "step", "endfor", "foreach", "in", "break", "continue", "and", "or", "not", "eq", "ne", "lt", "gt", "le", "ge", "null",
        "func", "endfunc", "return", "throw")

    private fun lex(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c.isWhitespace() || c == ' ' -> i++
                c == ';' || c == '/' && i + 1 < n && src[i + 1] == '/' -> { while (i < n && src[i] != '\n' && src[i] != '\r') i++ }   // comments
                c.isDigit() || c == '.' && i + 1 < n && src[i + 1].isDigit() -> {
                    val st = i
                    while (i < n && src[i].isDigit()) i++
                    if (i < n && src[i] == '.') { i++; while (i < n && src[i].isDigit()) i++ }
                    if (i < n && (src[i] == 'e' || src[i] == 'E') && i + 1 < n && (src[i + 1].isDigit() || src[i + 1] in "+-")) {
                        i += 2; while (i < n && src[i].isDigit()) i++
                    }
                    out += Tok(T.NUM, src.substring(st, i))
                }
                c == '"' -> {
                    val sb = StringBuilder(); i++
                    while (true) {
                        if (i >= n) throw FcError("unterminated string")
                        val d = src[i]
                        if (d == '"') { if (i + 1 < n && src[i + 1] == '"') { sb.append('"'); i += 2; continue }; i++; break }
                        if (d == '\\' && i + 1 < n && src[i + 1] == 'u' && i + 5 < n) {
                            src.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()); i += 6; continue }
                        }
                        sb.append(d); i++
                    }
                    out += Tok(T.STR, sb.toString())
                }
                c.isLetter() || c == '_' || c == '$' || c == '!' -> {
                    val st = i; i++
                    while (i < n && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$')) i++
                    out += Tok(T.ID, src.substring(st, i))
                }
                else -> {
                    val two = if (i + 1 < n) src.substring(i, i + 2) else ""
                    when (two) {
                        "==", "<>", "<=", ">=", ".." -> { out += Tok(T.OP, two); i += 2 }
                        else -> {
                            if (c !in "+-*/<>=&|()[],.#") throw FcError("unexpected '$c'")
                            out += Tok(T.OP, c.toString()); i++
                        }
                    }
                }
            }
        }
        out += Tok(T.EOF, "")
        return out
    }

    // ------------------------------------------------------------------ parser → JS

    private class Parser(val toks: List<Tok>) {
        var p = 0
        val vars = HashSet<String>()

        fun peek(k: Int = 0) = toks[minOf(p + k, toks.lastIndex)]
        fun kw(s: String, k: Int = 0) = peek(k).t == T.ID && peek(k).s.equals(s, true)
        fun op(s: String, k: Int = 0) = peek(k).t == T.OP && peek(k).s == s
        fun next() = toks[p++]
        fun expectOp(s: String) { if (!op(s)) throw FcError("expected '$s'"); p++ }
        fun expectKw(s: String) { if (!kw(s)) throw FcError("expected $s"); p++ }

        fun program(): String {
            val body = block(emptySet())
            if (peek().t != T.EOF) throw FcError("unexpected ${peek().s}")
            return "var __r = null;\n$body__r;"
        }

        /** Statements until one of the keywords [ends] (or the end). */
        fun block(ends: Set<String>): String {
            val sb = StringBuilder()
            while (peek().t != T.EOF && !(peek().t == T.ID && peek().s.lowercase(Locale.ROOT) in ends)) sb.append(statement())
            return sb.toString()
        }

        fun statement(): String {
            val t = peek()
            if (t.t == T.ID) when (t.s.lowercase(Locale.ROOT)) {
                "var" -> {
                    next()
                    val name = ident()
                    vars += name.lowercase(Locale.ROOT)
                    val v = if (op("=")) { next(); expr() } else "\"\""
                    return "var ${jsVar(name)} = $v; __r = ${jsVar(name)};\n"
                }
                "if" -> {
                    next()
                    val sb = StringBuilder("if (__fc.t(").append(paren()).append(")) {\n")
                    expectKw("then"); sb.append(block(setOf("elseif", "else", "endif"))).append("}")
                    while (kw("elseif")) { next(); sb.append(" else if (__fc.t(").append(paren()).append(")) {\n"); expectKw("then"); sb.append(block(setOf("elseif", "else", "endif"))).append("}") }
                    if (kw("else")) { next(); sb.append(" else {\n").append(block(setOf("endif"))).append("}") }
                    expectKw("endif")
                    return sb.append("\n").toString()
                }
                "while" -> {
                    next(); val c = paren(); expectKw("do")
                    val b = block(setOf("endwhile")); expectKw("endwhile")
                    return "while (__fc.t($c)) {\n$b}\n"
                }
                "for" -> {
                    next()
                    val name = ident(); vars += name.lowercase(Locale.ROOT)
                    expectOp("="); val from = expr()
                    val down = when { kw("upto") -> false; kw("downto") -> true; else -> throw FcError("expected upto / downto") }
                    next(); val to = expr()
                    val step = if (kw("step")) { next(); expr() } else "1"
                    expectKw("do"); val b = block(setOf("endfor")); expectKw("endfor")
                    val v = jsVar(name)
                    return if (down) "for (var $v = __fc.num($from), __e$v = __fc.num($to), __s$v = __fc.num($step); $v >= __e$v; $v -= __s$v) {\n$b}\n"
                    else "for (var $v = __fc.num($from), __e$v = __fc.num($to), __s$v = __fc.num($step); $v <= __e$v; $v += __s$v) {\n$b}\n"
                }
                "foreach" -> {
                    next()
                    val name = ident(); vars += name.lowercase(Locale.ROOT)
                    expectKw("in"); expectOp("(")
                    val items = ArrayList<String>()
                    if (!op(")")) { items += expr(raw = true); while (op(",")) { next(); items += expr(raw = true) } }
                    expectOp(")"); expectKw("do"); val b = block(setOf("endfor")); expectKw("endfor")
                    val v = jsVar(name)
                    return "for (var __a$v = __fc.flat([${items.joinToString(",")}]), __k$v = 0; __k$v < __a$v.length; __k$v++) { var $v = __a$v[__k$v];\n$b}\n"
                }
                "break" -> { next(); return "break;\n" }
                "continue" -> { next(); return "continue;\n" }
                "func", "return", "throw" -> throw FcError("${t.s} not supported")
                else -> {}
            }
            // an assignment or an expression
            val start = p
            val target = tryRef()
            if (target != null && op("=")) {
                next()
                val v = expr()
                return "__r = ${assign(target, v)};\n"
            }
            p = start
            return "__r = ${expr()};\n"
        }

        fun paren(): String { expectOp("("); val e = expr(); expectOp(")"); return e }

        fun ident(): String {
            val t = next()
            if (t.t != T.ID || t.s.lowercase(Locale.ROOT) in KEYWORDS) throw FcError("expected a name")
            return t.s
        }

        // ---- references: name / $ followed by .name, [*], [n]

        /** A parsed reference: [js] the object (or local variable) expression, [lastName] the last name step, [isVar] a local variable. */
        inner class Ref(val js: String, val lastName: String?, val isVar: Boolean, val base: String?)

        fun tryRef(): Ref? {
            val t = peek()
            if (t.t != T.ID || t.s.lowercase(Locale.ROOT) in KEYWORDS) return null
            if (op("(", 1)) return null                        // a function call
            next()
            val first = t.s
            if (first.startsWith("!") || first.startsWith("$") && first.length > 1) throw FcError("$first not supported")
            val isVar = first.lowercase(Locale.ROOT) in vars
            var js = if (first == "$") "this" else if (isVar) jsVar(first) else jsName(first)
            var last: String? = if (first == "$") null else first
            var base: String? = null
            while (true) {
                when {
                    op("[") -> {
                        next()
                        js = if (op("*")) { next(); "__fc.sibs($js)" } else { val e = expr(); "__fc.nth($js, $e)" }
                        expectOp("]"); last = null
                    }
                    op(".") && peek(1).t == T.ID -> {
                        next(); val n = next().s
                        if (op("(")) throw FcError("method calls not supported")
                        base = js
                        js = if (n.lowercase(Locale.ROOT) in PROPS) "($js).${propName(n)}" else "__fc.kid($js, ${jsStr(n)})"
                        last = n
                    }
                    op("..") || op(".") && op("#", 1) -> throw FcError("SOM step not supported")
                    else -> break
                }
            }
            return Ref(js, last, isVar && js == jsVar(first), base)
        }

        fun assign(r: Ref, v: String): String = when {
            r.isVar -> "(${r.js} = $v)"
            r.lastName != null && r.lastName.lowercase(Locale.ROOT) in PROPS && r.base != null -> "(${r.base}.${propName(r.lastName)} = __fc.v($v))"
            else -> "__fc.set(${r.js}, $v)"
        }

        // ---- expressions (precedence: or, and, equality, relational, additive, multiplicative, unary)

        /** An expression's JS; [raw] keeps object references as objects (function arguments, foreach lists). */
        fun expr(raw: Boolean = false): String = or(raw)

        fun or(raw: Boolean): String {
            var a = and(raw)
            while (op("|") || kw("or")) { next(); val b = and(false); a = "__fc.or($a, $b)" }
            return a
        }
        fun and(raw: Boolean): String {
            var a = eq(raw)
            while (op("&") || kw("and")) { next(); val b = eq(false); a = "__fc.and($a, $b)" }
            return a
        }
        fun eq(raw: Boolean): String {
            var a = rel(raw)
            while (true) {
                val o = when { op("==") || kw("eq") -> "eq"; op("<>") || kw("ne") -> "ne"; else -> break }
                next(); a = "__fc.cmp(\"$o\", $a, ${rel(false)})"
            }
            return a
        }
        fun rel(raw: Boolean): String {
            var a = add(raw)
            while (true) {
                val o = when { op("<") || kw("lt") -> "lt"; op(">") || kw("gt") -> "gt"; op("<=") || kw("le") -> "le"; op(">=") || kw("ge") -> "ge"; else -> break }
                next(); a = "__fc.cmp(\"$o\", $a, ${add(false)})"
            }
            return a
        }
        fun add(raw: Boolean): String {
            var a = mul(raw)
            while (op("+") || op("-")) { val o = next().s; a = "(__fc.num($a) $o __fc.num(${mul(false)}))" }
            return a
        }
        fun mul(raw: Boolean): String {
            var a = unary(raw)
            while (op("*") || op("/")) { val o = next().s; a = "(__fc.num($a) $o __fc.num(${unary(false)}))" }
            return a
        }
        fun unary(raw: Boolean): String = when {
            op("-") -> { next(); "(-__fc.num(${unary(false)}))" }
            op("+") -> { next(); "__fc.num(${unary(false)})" }
            kw("not") -> { next(); "(__fc.t(${unary(false)}) ? 0 : 1)" }
            else -> primary(raw)
        }

        fun primary(raw: Boolean): String {
            val t = peek()
            return when {
                t.t == T.NUM -> { next(); t.s.toDouble().let { if (it == Math.floor(it) && Math.abs(it) < 1e15) it.toLong().toString() else it.toString() } }
                t.t == T.STR -> { next(); jsStr(t.s) }
                kw("null") -> { next(); "null" }
                op("(") -> { next(); val e = expr(raw); expectOp(")"); "($e)" }
                t.t == T.ID && op("(", 1) -> {
                    next(); next()
                    val f = t.s.lowercase(Locale.ROOT)
                    if (f !in FUNCTIONS) throw FcError("function ${t.s} not supported")
                    val args = ArrayList<String>()
                    if (!op(")")) { args += expr(raw = true); while (op(",")) { next(); args += expr(raw = true) } }
                    expectOp(")")
                    "__fc.f.$f(${args.joinToString(", ")})"
                }
                else -> {
                    val r = tryRef() ?: throw FcError("unexpected '${t.s}'")
                    if (raw || r.isVar) r.js else "__fc.v(${r.js})"
                }
            }
        }

        fun jsVar(n: String) = "fc_" + n.lowercase(Locale.ROOT).replace("$", "_")
        /** A form object by name: resolved like a JavaScript name (the script's containers), else null. */
        fun jsName(n: String) = "__fc.name(function(){ return $n; }, ${jsStr(n)})"
        fun propName(n: String) = when (n.lowercase(Locale.ROOT)) { "rawvalue" -> "rawValue"; "formattedvalue" -> "formattedValue"; else -> n.lowercase(Locale.ROOT) }
    }

    fun jsStr(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r")
            ' ', ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
            else -> append(c)
        }
        append('"')
    }
}
