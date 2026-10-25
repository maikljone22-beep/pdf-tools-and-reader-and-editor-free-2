package com.smartdocs.editor.core.util

/** Line-level diff of two texts based on a longest-common-subsequence alignment. */
object TextDiff {

    enum class Kind { SAME, ADDED, REMOVED, CHANGED }

    /** One diff row. [old] is the line from the first text, [new] from the second (either may be null). */
    data class Line(val kind: Kind, val old: String?, val new: String?)

    data class Result(val lines: List<Line>) {
        val added get() = lines.count { it.kind == Kind.ADDED }
        val removed get() = lines.count { it.kind == Kind.REMOVED }
        val changed get() = lines.count { it.kind == Kind.CHANGED }
        val identical get() = lines.all { it.kind == Kind.SAME }
    }

    /** Cap so the LCS table stays small (1500² ints ≈ 9 MB). */
    private const val MAX_LINES = 1500

    fun diff(oldText: String, newText: String): Result = diff(split(oldText), split(newText))

    private fun split(t: String): List<String> =
        t.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }.take(MAX_LINES)

    fun diff(a: List<String>, b: List<String>): Result {
        val n = a.size; val m = b.size
        // LCS table (n+1)*(m+1) stored as a flat IntArray
        val w = m + 1
        val dp = IntArray((n + 1) * w)
        for (i in n - 1 downTo 0) {
            val ai = a[i]
            for (j in m - 1 downTo 0) {
                dp[i * w + j] = if (ai == b[j]) dp[(i + 1) * w + j + 1] + 1 else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
            }
        }
        val raw = ArrayList<Line>()
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { raw += Line(Kind.SAME, a[i], b[j]); i++; j++ }
                dp[(i + 1) * w + j] >= dp[i * w + j + 1] -> { raw += Line(Kind.REMOVED, a[i], null); i++ }
                else -> { raw += Line(Kind.ADDED, null, b[j]); j++ }
            }
        }
        while (i < n) { raw += Line(Kind.REMOVED, a[i], null); i++ }
        while (j < m) { raw += Line(Kind.ADDED, null, b[j]); j++ }
        return Result(pairChanges(raw))
    }

    /** Collapse runs of REMOVED followed by ADDED into CHANGED pairs. */
    private fun pairChanges(raw: List<Line>): List<Line> {
        val out = ArrayList<Line>(raw.size)
        var k = 0
        while (k < raw.size) {
            if (raw[k].kind == Kind.REMOVED) {
                var r = k; while (r < raw.size && raw[r].kind == Kind.REMOVED) r++
                var e = r; while (e < raw.size && raw[e].kind == Kind.ADDED) e++
                val removed = raw.subList(k, r); val added = raw.subList(r, e)
                val pairs = minOf(removed.size, added.size)
                for (p in 0 until pairs) out += Line(Kind.CHANGED, removed[p].old, added[p].new)
                for (p in pairs until removed.size) out += removed[p]
                for (p in pairs until added.size) out += added[p]
                k = e
            } else { out += raw[k]; k++ }
        }
        return out
    }
}
