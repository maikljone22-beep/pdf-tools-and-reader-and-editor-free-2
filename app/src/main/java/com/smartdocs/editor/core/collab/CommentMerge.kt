package com.smartdocs.editor.core.collab

/**
 * Pure merge plan for shared-review comments (no Android, unit-tested in CommentMergeTest).
 *
 * Rules:
 *  - my own entries (my participant id, or an /NM I authored and published) are never taken from the server;
 *  - an entry is fresh when it was never applied here, or the server copy is newer than the applied one;
 *  - several entries for the same /NM (should not happen; e.g. a reviewer reinstalled) → the newest wins;
 *  - a tombstone deletes the comment only if we have it; an unknown tombstone is just remembered;
 *  - "new comments" = fresh, non-deleted entries whose /NM was never applied before (edits of known comments
 *    are merged too but are not announced as new).
 */
object CommentMerge {
    data class Incoming(val nm: String, val participant: String, val modified: Long, val deleted: Boolean)

    data class Plan(
        /** /NMs to insert or replace. */
        val upserts: List<String>,
        /** /NMs to remove from the document. */
        val deletes: Set<String>,
        /** How many of [upserts] are comments this device never had. */
        val arrivals: Int,
        /** The applied map after the plan has been written into the document. */
        val applied: Map<String, Long>,
    ) {
        val isEmpty get() = upserts.isEmpty() && deletes.isEmpty()
    }

    fun plan(remote: List<Incoming>, me: String, applied: Map<String, Long>, mine: Set<String>, present: Set<String> = emptySet()): Plan {
        val newest = HashMap<String, Incoming>()
        for (c in remote) {
            if (c.participant == me || c.nm in mine) continue
            val prev = newest[c.nm]
            if (prev == null || c.modified > prev.modified) newest[c.nm] = c
        }
        val upserts = ArrayList<String>(); val deletes = LinkedHashSet<String>()
        val out = HashMap(applied)
        var arrivals = 0
        for (c in newest.values.sortedBy { it.modified }) {
            val last = applied[c.nm]
            if (last != null && last >= c.modified) continue
            if (c.deleted) {
                if (last != null || c.nm in present) deletes += c.nm
            } else {
                upserts += c.nm
                if (last == null && c.nm !in present) arrivals++
            }
            out[c.nm] = c.modified
        }
        return Plan(upserts, deletes, arrivals, out)
    }

    /** Remote comments that are waiting while a document is open (shown in the reader before they are merged). */
    fun pendingCount(remote: List<Incoming>, me: String, applied: Map<String, Long>, mine: Set<String>): Int =
        plan(remote, me, applied, mine).let { it.upserts.size + it.deletes.size }
}
