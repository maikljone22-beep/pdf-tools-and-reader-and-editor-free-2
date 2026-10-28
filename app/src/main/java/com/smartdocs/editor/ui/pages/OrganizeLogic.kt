package com.smartdocs.editor.ui.pages

import com.smartdocs.editor.core.security.DocAction
import com.smartdocs.editor.core.security.ReaderRestrictions
import com.smartdocs.editor.core.security.SecMethod
import com.smartdocs.editor.core.security.SecurityInfo

/**
 * Pure rules of the page organizer (no Android; JVM-tested in OrganizeLogicTest).
 * Pages carry identities (one Long per page of the current version) so the clipboard and the selection survive
 * reorder / delete / insert (audit 5 G-11).
 */
object OrganizeLogic {

    /** Identities after deleting the pages at [removed]. */
    fun idsAfterDelete(ids: List<Long>, removed: Set<Int>): List<Long> = ids.filterIndexed { i, _ -> i !in removed }

    /** Identities after inserting [count] new pages at [at]. */
    fun idsAfterInsert(ids: List<Long>, at: Int, count: Int, fresh: () -> Long): List<Long> {
        val out = ids.toMutableList()
        out.addAll(at.coerceIn(0, ids.size), List(count.coerceAtLeast(0)) { fresh() })
        return out
    }

    /** Identities after a reorder: [order][i] is the old index shown at the new position i. A repeated page is a copy (new identity). */
    fun idsAfterReorder(ids: List<Long>, order: List<Int>, fresh: () -> Long): List<Long> {
        val seen = HashSet<Long>()
        return order.map { o -> ids.getOrNull(o)?.takeIf { seen.add(it) } ?: fresh() }
    }

    /** Identities after pasting [count] copies at [pos] (the copies are new pages). */
    fun idsAfterPaste(ids: List<Long>, pos: Int, count: Int, fresh: () -> Long): List<Long> = idsAfterInsert(ids, pos, count, fresh)

    /** Identities of the pages at [indexes] (sorted by index; out-of-range indexes are ignored). */
    fun idsOf(indexes: Collection<Int>, ids: List<Long>): List<Long> = indexes.sorted().mapNotNull { ids.getOrNull(it) }

    /** Current indexes of the pages [clip] names; pages that are gone are dropped (audit 5 G-11). */
    fun indexesOf(clip: List<Long>, ids: List<Long>): List<Int> {
        val pos = HashMap<Long, Int>()
        ids.forEachIndexed { i, id -> pos.putIfAbsent(id, i) }
        return clip.mapNotNull { pos[it] }
    }

    /** audit 5 G-12: "Select all" on the visible (filtered) pages; a second tap clears the selection. */
    fun selectAllToggle(selected: Set<Int>, visible: List<Int>): Set<Int> =
        if (visible.isNotEmpty() && selected.containsAll(visible)) emptySet() else visible.toSet()

    /** audit 5 G-03: the author's permissions allow [action] (Protected View is the reader's business). */
    fun allows(info: SecurityInfo?, action: DocAction): Boolean = ReaderRestrictions.check(info, false, action) == null

    /**
     * audit 5 G-02: a full save of a password-protected document opened without owner access re-protects it with a
     * random owner password (PdfOps.keepProtection), so the author's owner password is lost on Overwrite.
     */
    fun ownerPasswordWillChange(info: SecurityInfo?): Boolean =
        info != null && info.method == SecMethod.PASSWORD && !info.ownerAccess

    /** Zero-based pages → a print range "1-3,5" (sorted, duplicates dropped). */
    fun rangeSpec(pages: Collection<Int>): String {
        val s = pages.filter { it >= 0 }.toSortedSet().toList()
        if (s.isEmpty()) return ""
        val parts = ArrayList<String>()
        var a = s[0]; var b = s[0]
        for (k in 1 until s.size) {
            if (s[k] == b + 1) b = s[k] else { parts += if (a == b) "${a + 1}" else "${a + 1}-${b + 1}"; a = s[k]; b = s[k] }
        }
        parts += if (a == b) "${a + 1}" else "${a + 1}-${b + 1}"
        return parts.joinToString(",")
    }
}
