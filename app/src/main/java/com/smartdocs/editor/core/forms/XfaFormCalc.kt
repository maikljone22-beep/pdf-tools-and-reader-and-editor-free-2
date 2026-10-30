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
            return "var __r = null;\n${body}__r;"
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

    /**
     * The `__fc` runtime the translated scripts call, evaluated after the XFA object model ([XfaJs]). FormCalc
     * semantics: null / "" count as 0 in arithmetic, comparisons and logic give 1 / 0, aggregates skip nulls and
     * expand `A[*]`, dates are day numbers with 1 = 1 January 1900.
     */
    const val LIB = $$"""
(function (G) {
function isNode(x) { return x !== null && typeof x === "object" && typeof x.__i === "number"; }
function v(x) { if (isNode(x)) return x.rawValue; if (x === undefined) return null; return x; }
function num(x) {
  x = v(x); if (x === null || x === "") return 0; if (typeof x === "boolean") return x ? 1 : 0;
  if (typeof x === "number") return x; var n = parseFloat(String(x)); return isNaN(n) ? 0 : n;
}
function str(x) { x = v(x); if (x === null) return ""; if (typeof x === "number") return fmtNum(x); return String(x); }
function fmtNum(n) { if (!isFinite(n)) return ""; if (n === Math.floor(n) && Math.abs(n) < 1e15) return String(n); return String(parseFloat(n.toPrecision(12))); }
function numeric(x) { return typeof x === "number" || typeof x === "boolean" || (typeof x === "string" && x !== "" && !isNaN(Number(x))); }
function flat(a) {
  var out = [];
  function add(x) {
    if (x instanceof Array) { for (var k = 0; k < x.length; k++) add(x[k]); return; }
    out.push(x);
  }
  add(a); return out;
}
function vals(args) { var a = flat(Array.prototype.slice.call(args)), out = []; for (var k = 0; k < a.length; k++) { var x = v(a[k]); if (x !== null && x !== "") out.push(x); } return out; }
function sibs(o) {
  if (!isNode(o)) return [];
  var p = o.parent; if (!p) return [o];
  var all = p.nodes, out = []; for (var k = 0; k < all.length; k++) if (all[k].name === o.name) out.push(all[k]);
  return out;
}
var MON = ["January","February","March","April","May","June","July","August","September","October","November","December"];
var DAY = ["Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday"];
var EPOCH = Date.UTC(1900, 0, 1);
function dayNum(d) { return Math.floor((d - EPOCH) / 86400000) + 1; }
function pad(n, w) { var s = String(n); while (s.length < w) s = "0" + s; return s; }
function num2date(n, fmt) {
  n = num(n); if (n < 1) return "";
  var d = new Date(EPOCH + (n - 1) * 86400000), Y = d.getUTCFullYear(), M = d.getUTCMonth(), D = d.getUTCDate(), W = d.getUTCDay();
  fmt = fmt === null || fmt === undefined || fmt === "" ? "MMM D, YYYY" : String(fmt);
  return fmt.replace(/YYYY|YY|MMMM|MMM|MM|M|DD|D|EEEE|EEE/g, function (t) {
    switch (t) {
      case "YYYY": return String(Y); case "YY": return pad(Y % 100, 2);
      case "MMMM": return MON[M]; case "MMM": return MON[M].substring(0, 3); case "MM": return pad(M + 1, 2); case "M": return String(M + 1);
      case "DD": return pad(D, 2); case "D": return String(D); case "EEEE": return DAY[W]; case "EEE": return DAY[W].substring(0, 3);
    }
    return t;
  });
}
function date2num(s, fmt) {
  s = str(s); if (s === "") return 0;
  fmt = fmt === null || fmt === undefined || fmt === "" ? null : String(fmt);
  var Y = -1, M = -1, D = -1;
  if (fmt) {
    var toks = fmt.match(/YYYY|YY|MMMM|MMM|MM|M|DD|D|[^YMD]+/g) || [], pos = 0;
    for (var k = 0; k < toks.length; k++) {
      var t = toks[k], m;
      if (t === "YYYY") { m = /^\d{4}/.exec(s.substring(pos)); if (!m) return 0; Y = +m[0]; }
      else if (t === "YY") { m = /^\d{2}/.exec(s.substring(pos)); if (!m) return 0; Y = +m[0] + (+m[0] < 50 ? 2000 : 1900); }
      else if (t === "MMMM" || t === "MMM") { m = /^[A-Za-z]+/.exec(s.substring(pos)); if (!m) return 0; for (var q = 0; q < 12; q++) if (MON[q].toLowerCase().indexOf(m[0].toLowerCase()) === 0) M = q; }
      else if (t === "MM" || t === "M") { m = /^\d{1,2}/.exec(s.substring(pos)); if (!m) return 0; M = +m[0] - 1; }
      else if (t === "DD" || t === "D") { m = /^\d{1,2}/.exec(s.substring(pos)); if (!m) return 0; D = +m[0]; }
      else { m = [s.substring(pos, pos + t.length)]; }
      pos += m[0].length;
    }
  } else {
    var a = /^(\d{4})-(\d{1,2})-(\d{1,2})/.exec(s) || null;
    if (a) { Y = +a[1]; M = +a[2] - 1; D = +a[3]; }
    else { var b = /^(\d{1,2})\/(\d{1,2})\/(\d{4})/.exec(s); if (b) { M = +b[1] - 1; D = +b[2]; Y = +b[3]; } else { var t2 = Date.parse(s); if (isNaN(t2)) return 0; return dayNum(t2); } }
  }
  if (Y < 0 || M < 0 || D < 1) return 0;
  return dayNum(Date.UTC(Y, M, D));
}
G.__fc = {
  v: v, num: num, flat: flat, sibs: sibs,
  t: function (x) { return num(x) !== 0; },
  name: function (fn, n) { try { var r = fn(); return r === undefined ? null : r; } catch (e) { return G.xfa.resolveNode(n); } },
  kid: function (o, n) {
    if (o instanceof Array) { var out = []; for (var k = 0; k < o.length; k++) { var c = G.__fc.kid(o[k], n); if (c !== null) out.push(c); } return out; }   // A[*].B
    if (!isNode(o)) return null; if (o[n] !== undefined && isNode(o[n])) return o[n]; return o.resolveNode(n);
  },
  nth: function (o, i) { var a = sibs(o); i = num(i); return i >= 0 && i < a.length ? a[i] : null; },
  set: function (o, x) { x = v(x); if (isNode(o)) o.rawValue = x; return x; },
  and: function (a, b) { return num(a) !== 0 && num(b) !== 0 ? 1 : 0; },
  or: function (a, b) { return num(a) !== 0 || num(b) !== 0 ? 1 : 0; },
  cmp: function (op, a, b) {
    a = v(a); b = v(b);
    var eq = op === "==" || op === "eq", ne = op === "<>" || op === "ne";
    if (eq || ne) {
      var same;
      if (a === null || b === null) same = (a === null || a === "") && (b === null || b === "");
      else if (numeric(a) && numeric(b)) same = num(a) === num(b);
      else same = String(a) === String(b);
      return (eq ? same : !same) ? 1 : 0;
    }
    var r;
    if (typeof a === "string" && typeof b === "string" && !numeric(a) && !numeric(b)) r = a < b ? -1 : a > b ? 1 : 0;
    else { var x = num(a), y = num(b); r = x < y ? -1 : x > y ? 1 : 0; }
    switch (op) { case "<": case "lt": return r < 0 ? 1 : 0; case ">": case "gt": return r > 0 ? 1 : 0;
      case "<=": case "le": return r <= 0 ? 1 : 0; default: return r >= 0 ? 1 : 0; }
  },
  f: {
    sum: function () { var a = vals(arguments), s = 0; for (var k = 0; k < a.length; k++) s += num(a[k]); return s; },
    avg: function () { var a = vals(arguments); if (!a.length) return null; var s = 0; for (var k = 0; k < a.length; k++) s += num(a[k]); return s / a.length; },
    min: function () { var a = vals(arguments); if (!a.length) return null; var m = num(a[0]); for (var k = 1; k < a.length; k++) m = Math.min(m, num(a[k])); return m; },
    max: function () { var a = vals(arguments); if (!a.length) return null; var m = num(a[0]); for (var k = 1; k < a.length; k++) m = Math.max(m, num(a[k])); return m; },
    count: function () { return vals(arguments).length; },
    round: function (x, d) { if (v(x) === null) return null; var p = Math.pow(10, d === undefined ? 0 : Math.min(12, num(d))), n = num(x); return Math.round(Math.abs(n) * p + 1e-9) / p * (n < 0 ? -1 : 1); },
    abs: function (x) { return v(x) === null ? null : Math.abs(num(x)); },
    floor: function (x) { return v(x) === null ? null : Math.floor(num(x)); },
    ceil: function (x) { return v(x) === null ? null : Math.ceil(num(x)); },
    mod: function (a, b) { if (v(a) === null || v(b) === null) return null; var x = num(a), y = num(b); if (y === 0) throw new Error("division by zero"); return x % y; },
    pow: function (a, b) { return v(a) === null || v(b) === null ? null : Math.pow(num(a), num(b)); },
    concat: function () { var a = flat(Array.prototype.slice.call(arguments)), s = ""; for (var k = 0; k < a.length; k++) s += str(a[k]); return s; },
    len: function (s) { return str(s).length; },
    left: function (s, n) { return v(s) === null ? null : str(s).substring(0, Math.max(0, num(n))); },
    right: function (s, n) { if (v(s) === null) return null; s = str(s); return s.substring(Math.max(0, s.length - num(n))); },
    substr: function (s, st, n) { if (v(s) === null) return null; s = str(s); var b = Math.max(1, num(st)) - 1; return s.substring(b, b + Math.max(0, num(n))); },
    upper: function (s) { return v(s) === null ? null : str(s).toUpperCase(); },
    lower: function (s) { return v(s) === null ? null : str(s).toLowerCase(); },
    ltrim: function (s) { return v(s) === null ? null : str(s).replace(/^\s+/, ""); },
    rtrim: function (s) { return v(s) === null ? null : str(s).replace(/\s+$/, ""); },
    str: function (n, w, p) { if (v(n) === null) return null; w = w === undefined ? 10 : num(w); p = p === undefined ? 0 : num(p); var s = num(n).toFixed(Math.max(0, Math.min(15, p))); while (s.length < w) s = " " + s; return s.length > w ? new Array(w + 1).join("*") : s; },
    at: function (s1, s2) { return str(s1).indexOf(str(s2)) + 1; },
    replace: function (s1, s2, s3) { if (v(s1) === null) return null; var a = str(s1), b = str(s2), c = s3 === undefined ? "" : str(s3); return b === "" ? a : a.split(b).join(c); },
    space: function (n) { return new Array(Math.max(0, num(n)) + 1).join(" "); },
    repeat: function (s, n) { return new Array(Math.max(0, num(n)) + 1).join(str(s)); },
    date: function () { return dayNum(new Date().getTime()); },
    num2date: function (n, f) { return num2date(n, v(f)); },
    date2num: function (s, f) { return date2num(s, v(f)); },
    hasvalue: function (x) { var y = v(x); return y !== null && String(y).replace(/\s/g, "") !== "" ? 1 : 0; },
    oneof: function (s) { var x = str(s), a = vals(Array.prototype.slice.call(arguments, 1)); for (var k = 0; k < a.length; k++) if (str(a[k]) === x) return 1; return 0; },
    within: function (s, lo, hi) { var x = v(s); if (x === null) return null; if (numeric(x)) return num(x) >= num(lo) && num(x) <= num(hi) ? 1 : 0; x = str(x); return x >= str(lo) && x <= str(hi) ? 1 : 0; },
    choose: function (n) { var i = Math.floor(num(n)); return i >= 1 && i < arguments.length ? v(arguments[i]) : ""; },
    exists: function (x) { return x !== null && x !== undefined ? 1 : 0; }
  }
};
})(this);
"""

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
