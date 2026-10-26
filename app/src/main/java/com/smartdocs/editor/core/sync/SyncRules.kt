package com.smartdocs.editor.core.sync

/**
 * Pure decision rules of the two-way folder sync (no Android, unit-tested in SyncRulesTest).
 *
 * Each file is compared with its state at the last successful sync ([Snap]):
 *  - changed only locally → [Action.UPLOAD]; changed only in the cloud → [Action.DOWNLOAD];
 *  - changed on both sides → [Action.CONFLICT] (the cloud version is kept as "<name> (نسخة متعارضة).<ext>", then the
 *    local one is uploaded): nothing is ever lost;
 *  - first sync (no snapshot) with both present: same size → [Action.RECORD], different → [Action.CONFLICT];
 *  - present on one side only: unchanged since the snapshot → the other side deleted it → delete here too;
 *    changed or never synced → copy it over (a deletion never wins over an edit).
 */
object SyncRules {
    /** One side of a file: modification time (ms) and size (bytes). */
    data class Side(val modified: Long, val size: Long)

    /** State recorded after the last successful sync. */
    data class Snap(val localMod: Long, val localSize: Long, val remoteMod: Long, val remoteSize: Long)

    enum class Action { NONE, RECORD, UPLOAD, DOWNLOAD, CONFLICT, UPLOAD_NEW, DOWNLOAD_NEW, DELETE_LOCAL, DELETE_REMOTE }

    /** Some providers round times to seconds; FAT/SD cards to 2 s. */
    const val CLOCK_TOLERANCE_MS = 2_000L

    fun localChanged(l: Side, s: Snap) = l.size != s.localSize || kotlin.math.abs(l.modified - s.localMod) > CLOCK_TOLERANCE_MS
    fun remoteChanged(r: Side, s: Snap) = r.size != s.remoteSize || kotlin.math.abs(r.modified - s.remoteMod) > CLOCK_TOLERANCE_MS

    fun decide(local: Side?, remote: Side?, snap: Snap?): Action = when {
        local == null && remote == null -> Action.NONE
        local != null && remote != null -> when {
            snap == null -> if (local.size == remote.size) Action.RECORD else Action.CONFLICT
            else -> {
                val lc = localChanged(local, snap); val rc = remoteChanged(remote, snap)
                when {
                    lc && rc -> Action.CONFLICT
                    lc -> Action.UPLOAD
                    rc -> Action.DOWNLOAD
                    else -> Action.NONE
                }
            }
        }
        local != null -> if (snap != null && !localChanged(local, snap)) Action.DELETE_LOCAL else Action.UPLOAD_NEW
        else -> if (snap != null && !remoteChanged(remote!!, snap)) Action.DELETE_REMOTE else Action.DOWNLOAD_NEW
    }

    const val CONFLICT_SUFFIX = " (نسخة متعارضة)" // " (نسخة متعارضة)"

    /** "report.pdf" → "report (نسخة متعارضة).pdf", then "report (نسخة متعارضة) 2.pdf", … skipping names in [taken]. */
    fun conflictName(name: String, taken: (String) -> Boolean): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = "$base$CONFLICT_SUFFIX$ext"
        var n = 2
        while (taken(candidate)) { candidate = "$base$CONFLICT_SUFFIX $n$ext"; n++ }
        return candidate
    }

    /** SAF document ids: "primary:Docs" contains "primary:Docs/a.pdf" and itself, not "primary:Docs2/a.pdf". */
    fun isBelow(treeId: String, docId: String): Boolean {
        if (docId == treeId) return true
        val base = if (treeId.endsWith("/") || treeId.endsWith(":")) treeId else "$treeId/"
        return docId.startsWith(base)
    }

    /** True for files sync never touches: hidden files, our temp/partial files, provider-native docs. */
    fun ignored(name: String): Boolean = name.startsWith(".") || name.endsWith(".part") || name.endsWith(".tmp") || name == "desktop.ini" || name == "Thumbs.db"
}
