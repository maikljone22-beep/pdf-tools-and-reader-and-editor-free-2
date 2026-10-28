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

    /**
     * audit 5 C-12: diff of documents given as matched segments (e.g. the words of each old/new page pair): every
     * pair is diffed on its own, so a long book never hits the whole-document edit limit, and the result is one
     * change list over the concatenated token lists. [old] and [new] must have the same number of segments (use an
     * empty list for an unmatched page).
     */
    fun diffPaged(old: List<List<String>>, new: List<List<String>>, maxD: Int = 2000): List<WordChange> {
        require(old.size == new.size) { "segments differ" }
        val ops = ArrayList<Char>()
        for (i in old.indices) {
            checkInterrupted()
            ops += opsOf(old[i].map(::key), new[i].map(::key), maxD, 0)
        }
        return group(ops)
    }

    /** Myers diff over comparison keys; adjacent delete+insert runs are merged into REPLACE. */
    fun diffKeys(a: List<String>, b: List<String>, maxD: Int = 2000): List<WordChange> = group(opsOf(a, b, maxD, 0))

    /**
     * audit 5 C-12 / C-12 cancel: the thread running a compare is interrupted when the user cancels
     * (CompareEngine uses runInterruptible); the diff stops instead of burning CPU to the end.
     */
    private fun checkInterrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException("diff cancelled") }

    /** Edit script ('=', '-', '+') of [a] → [b]: prefix / suffix, Myers within [maxD], else split on unique anchors. */
    private fun opsOf(a: List<String>, b: List<String>, maxD: Int, depth: Int): List<Char> {
        // common prefix / suffix first: cheap and keeps the search space small
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val ops = ArrayList<Char>(a.size + b.size)    // '=', '-', '+'
        repeat(pre) { ops += '=' }
        val am = a.subList(pre, a.size - suf); val bm = b.subList(pre, b.size - suf)
        ops += myers(am, bm, maxD) ?: anchored(am, bm, maxD, depth)
        repeat(suf) { ops += '=' }
        return ops
    }

    /**
     * audit 5 C-12: fallback when the texts differ by more than [maxD] edits (it used to report "everything
     * changed", ~0 % similarity for two editions of a book): tokens that occur exactly once on each side and in the
     * same order (patience anchors, longest increasing run) split the problem; the gaps are diffed again.
     */
    private fun anchored(a: List<String>, b: List<String>, maxD: Int, depth: Int): List<Char> {
        val replaceAll = List(a.size) { '-' } + List(b.size) { '+' }
        if (depth > 8 || a.isEmpty() || b.isEmpty()) return replaceAll
        val ca = HashMap<String, Int>(); a.forEach { ca[it] = (ca[it] ?: 0) + 1 }
        val cb = HashMap<String, Int>(); b.forEach { cb[it] = (cb[it] ?: 0) + 1 }
        val posB = HashMap<String, Int>(); b.forEachIndexed { j, t -> if (cb[t] == 1 && ca[t] == 1) posB[t] = j }
        val cand = ArrayList<IntArray>()              // (index in a, index in b) of unique common tokens, in a order
        a.forEachIndexed { i, t -> posB[t]?.let { j -> cand += intArrayOf(i, j) } }
        if (cand.isEmpty()) return replaceAll
        val chain = longestIncreasing(cand)
        if (chain.isEmpty()) return replaceAll
        val ops = ArrayList<Char>(a.size + b.size)
        var ia = 0; var ib = 0
        for (p in chain) {
            checkInterrupted()
            ops += opsOf(a.subList(ia, p[0]), b.subList(ib, p[1]), maxD, depth + 1)
            ops += '='
            ia = p[0] + 1; ib = p[1] + 1
        }
        ops += opsOf(a.subList(ia, a.size), b.subList(ib, b.size), maxD, depth + 1)
        return ops
    }

    /** Longest run of [pairs] (sorted by first) whose second values increase (patience sorting, O(k log k)). */
    internal fun longestIncreasing(pairs: List<IntArray>): List<IntArray> {
        if (pairs.isEmpty()) return emptyList()
        val tails = IntArray(pairs.size); var len = 0          // index into pairs of the smallest tail of each length
        val prev = IntArray(pairs.size) { -1 }
        for (i in pairs.indices) {
            var lo = 0; var hi = len
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (pairs[tails[mid]][1] < pairs[i][1]) lo = mid + 1 else hi = mid }
            if (lo > 0) prev[i] = tails[lo - 1]
            tails[lo] = i
            if (lo == len) len++
        }
        val out = ArrayList<IntArray>(len)
        var k = tails[len - 1]
        while (k >= 0) { out += pairs[k]; k = prev[k] }
        out.reverse()
        return out
    }

    /** Myers O(ND) edit script, or null when more than [maxD] edits are needed. */
    private fun myers(a: List<String>, b: List<String>, maxD: Int): List<Char>? {
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
            if ((d and 63) == 0) checkInterrupted()
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
        if (!found) return null   // too different for Myers within maxD: the caller splits on anchors
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
