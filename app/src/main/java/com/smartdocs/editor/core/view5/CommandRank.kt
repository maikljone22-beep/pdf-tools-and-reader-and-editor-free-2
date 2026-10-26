package com.smartdocs.editor.core.view5

/** A command for "tell me" search: stable id, visible label and extra keywords (Arabic and English). */
data class CommandDef(val id: String, val label: String, val keywords: List<String> = emptyList(), val group: String = "")

/** Ranking of commands against a typed query (pure, tested). */
object CommandRank {

    private fun words(s: String) = CommentFilter.fold(s).split(Regex("[\\s\\-_/,.]+")).filter { it.isNotEmpty() }

    /** Score of [c] for [query]; 0 = no match. Prefix of the label wins, then word prefixes, then keyword / substring hits. */
    fun score(c: CommandDef, query: String): Int {
        val q = CommentFilter.fold(query.trim())
        if (q.isEmpty()) return 1
        val label = CommentFilter.fold(c.label)
        val kw = c.keywords.map { CommentFilter.fold(it) }
        var s = 0
        if (label == q) s += 1000
        if (label.startsWith(q)) s += 500
        val qw = words(query)
        val lw = words(c.label)
        val kwWords = kw.flatMap { words(it) }
        var all = true
        for (w in qw) {
            val hit = when {
                lw.any { it.startsWith(w) } -> 120
                kwWords.any { it.startsWith(w) } -> 80
                label.contains(w) -> 40
                kw.any { it.contains(w) } -> 30
                else -> 0
            }
            if (hit == 0) all = false
            s += hit
        }
        if (!all) return 0
        return s
    }

    /** Matching commands, best first; pinned ones get a small boost. */
    fun rank(all: List<CommandDef>, query: String, pinned: Set<String> = emptySet(), recent: List<String> = emptyList()): List<CommandDef> =
        all.map { it to score(it, query) + (if (it.id in pinned) 15 else 0) + (recent.indexOf(it.id).let { i -> if (i < 0) 0 else 10 - i.coerceAtMost(9) }) }
            .filter { it.second > (if (query.isBlank()) 0 else 15) }
            .sortedByDescending { it.second }
            .map { it.first }

    /** Most-recent-first list of ids, without duplicates, capped at [max]. */
    fun pushRecent(list: List<String>, id: String, max: Int = 8): List<String> = (listOf(id) + list.filter { it != id }).take(max)
}
