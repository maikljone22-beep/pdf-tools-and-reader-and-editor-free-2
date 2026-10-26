package com.smartdocs.editor.core.tools5

/*
 * Word-level diff (Myers O(ND)) on Arabic-normalised tokens. Plain Kotlin, unit-tested in WordDiffTest.
 */

enum class ChangeType { EQUAL, INSERT, DELETE, REPLACE }

/** [a] = indexes in the old token list, [b] = in the new one (empty ranges for pure inserts / deletes). */
data class WordChange(val type: ChangeType, val a: IntRange, val b: IntRange)

data class DiffStats(val inserted: Int, val deleted: Int, val replaced: Int, val equal: Int) {
    val changes: Int get() = inserted + deleted + replaced
    /** 0..1 share of unchanged words. */
    fun similarity(): Float { val tot = equal + maxOf(inserted, 0) + deleted + replaced; return if (tot == 0) 1f else equal.toFloat() / tot }
}

object WordDiff {

    /** Split on white space; keeps punctuation attached so the visible text can be rebuilt. */
    fun tokenize(text: String): List<String> = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }

    /** Comparison key of a token: Arabic-normalised, punctuation trimmed. Empty keys still compare equal. */
    fun key(token: String): String = TextNorm.arabicKey(TextNorm.trimPunct(token)).ifEmpty { TextNorm.arabicKey(token) }

    fun diff(old: List<String>, new: List<String>, maxD: Int = 2000): List<WordChange> = diffKeys(old.map(::key), new.map(::key), maxD)

    fun diffText(old: String, new: String): List<WordChange> = diff(tokenize(old), tokenize(new))

    /** Myers diff over comparison keys; adjacent delete+insert runs are merged into REPLACE. */
    fun diffKeys(a: List<String>, b: List<String>, maxD: Int = 2000): List<WordChange> {
        // common prefix / suffix first: cheap and keeps the search space small
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val ops = ArrayList<Char>(a.size + b.size)    // '=', '-', '+'
        repeat(pre) { ops += '=' }
        val am = a.subList(pre, a.size - suf); val bm = b.subList(pre, b.size - suf)
        ops += myers(am, bm, maxD)
        repeat(suf) { ops += '=' }
        return group(ops)
    }

    private fun myers(a: List<String>, b: List<String>, maxD: Int): List<Char> {
        val n = a.size; val m = b.size
        if (n == 0) return List(m) { '+' }
        if (m == 0) return List(n) { '-' }
        val max = n + m
        val off = max + 1
        val v = IntArray(2 * max + 4)
        val trace = ArrayList<IntArray>()
        var found = false
        var dEnd = 0
        outer@ for (d in 0..max) {
            if (d > maxD) break
            trace += v.copyOfRange(off - d - 1, off + d + 2)   // slice k = -(d+1)..(d+1): memory O(D^2), not O(D*(N+M))
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) v[off + k + 1] else v[off + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[off + k] = x
                if (x >= n && y >= m) { found = true; dEnd = d; break@outer }
                k += 2
            }
        }
        if (!found) return List(n) { '-' } + List(m) { '+' }   // too different: everything replaced
        // backtrack
        val out = ArrayList<Char>(n + m)
        var x = n; var y = m
        for (d in dEnd downTo 1) {
            val vd = trace[d]
            val base = d + 1                       // index of k = 0 inside the slice
            val k = x - y
            val prevK = if (k == -d || (k != d && vd[base + k - 1] < vd[base + k + 1])) k + 1 else k - 1
            val prevX = vd[base + prevK]; val prevY = prevX - prevK
            while (x > prevX && y > prevY) { out += '='; x--; y-- }
            if (x == prevX) { out += '+'; y-- } else { out += '-'; x-- }
        }
        while (x > 0 && y > 0) { out += '='; x--; y-- }
        out.reverse()
        return out
    }

    private fun group(ops: List<Char>): List<WordChange> {
        val res = ArrayList<WordChange>()
        var ia = 0; var ib = 0; var i = 0
        while (i < ops.size) {
            if (ops[i] == '=') {
                val sa = ia; val sb = ib
                while (i < ops.size && ops[i] == '=') { ia++; ib++; i++ }
                res += WordChange(ChangeType.EQUAL, sa until ia, sb until ib)
            } else {
                val sa = ia; val sb = ib
                while (i < ops.size && ops[i] != '=') { if (ops[i] == '-') ia++ else ib++; i++ }
                val type = when { ia > sa && ib > sb -> ChangeType.REPLACE; ia > sa -> ChangeType.DELETE; else -> ChangeType.INSERT }
                res += WordChange(type, sa until ia, sb until ib)
            }
        }
        return res
    }

    fun stats(changes: List<WordChange>): DiffStats {
        var ins = 0; var del = 0; var rep = 0; var eq = 0
        changes.forEach { c ->
            when (c.type) {
                ChangeType.EQUAL -> eq += c.a.count()
                ChangeType.INSERT -> ins += c.b.count()
                ChangeType.DELETE -> del += c.a.count()
                ChangeType.REPLACE -> rep += maxOf(c.a.count(), c.b.count())
            }
        }
        return DiffStats(ins, del, rep, eq)
    }

    /** Short plain-text change list ("- old" / "+ new" / "~ old → new") for reports and the AI summary prompt. */
    fun describe(changes: List<WordChange>, old: List<String>, new: List<String>, max: Int = 200): String = buildString {
        var n = 0
        for (c in changes) {
            if (c.type == ChangeType.EQUAL) continue
            if (n++ >= max) { append("…\n"); break }
            val a = c.a.joinToString(" ") { old[it] }; val b = c.b.joinToString(" ") { new[it] }
            when (c.type) {
                ChangeType.INSERT -> append("+ ").append(b)
                ChangeType.DELETE -> append("- ").append(a)
                ChangeType.REPLACE -> append("~ ").append(a).append(" → ").append(b)
                ChangeType.EQUAL -> {}
            }
            append('\n')
        }
    }
}
