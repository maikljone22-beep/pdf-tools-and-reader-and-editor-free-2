package com.smartdocs.editor.core.sign

/**
 * "Changes made to this document" — compares a snapshot of an earlier revision (the last signed one, or the
 * previous incremental update) with the current document. Pure; snapshots come from [RevisionSnapshot].
 */
object RevisionDiff {

    data class AnnotSnap(val subtype: String, val key: String)
    data class PageSnap(val rotation: Int, val sizeKey: String, val annots: List<AnnotSnap>)
    data class FieldSnap(val type: String, val value: String)
    data class DocSnap(val pages: List<PageSnap>, val fields: Map<String, FieldSnap>)

    enum class Kind { PAGE_ADDED, PAGE_REMOVED, PAGE_ROTATED, PAGE_RESIZED, FIELD_ADDED, FIELD_REMOVED, FIELD_CHANGED, ANNOT_ADDED, ANNOT_REMOVED, LINK_ADDED, LINK_REMOVED }

    /** [page] is 0-based (null for document-level changes); [detail] is a field name / annotation subtype / rotation. */
    data class Change(val kind: Kind, val page: Int?, val detail: String)

    private val SKIP = setOf("Widget", "Popup")

    fun diff(old: DocSnap, new: DocSnap): List<Change> {
        val out = ArrayList<Change>()
        val n = minOf(old.pages.size, new.pages.size)
        for (i in 0 until n) {
            val a = old.pages[i]; val b = new.pages[i]
            if (((a.rotation % 360) + 360) % 360 != ((b.rotation % 360) + 360) % 360) out += Change(Kind.PAGE_ROTATED, i, "${a.rotation}→${b.rotation}")
            if (a.sizeKey != b.sizeKey) out += Change(Kind.PAGE_RESIZED, i, "${a.sizeKey}→${b.sizeKey}")
            val oldAnn = a.annots.filter { it.subtype !in SKIP }.groupingBy { it }.eachCount()
            val newAnn = b.annots.filter { it.subtype !in SKIP }.groupingBy { it }.eachCount()
            for ((k, c) in newAnn) repeat(c - (oldAnn[k] ?: 0)) { out += Change(if (k.subtype == "Link") Kind.LINK_ADDED else Kind.ANNOT_ADDED, i, k.subtype) }
            for ((k, c) in oldAnn) repeat(c - (newAnn[k] ?: 0)) { out += Change(if (k.subtype == "Link") Kind.LINK_REMOVED else Kind.ANNOT_REMOVED, i, k.subtype) }
        }
        for (i in n until new.pages.size) out += Change(Kind.PAGE_ADDED, i, "")
        for (i in n until old.pages.size) out += Change(Kind.PAGE_REMOVED, i, "")
        for ((name, f) in new.fields) {
            val o = old.fields[name]
            when {
                o == null -> out += Change(Kind.FIELD_ADDED, null, name)
                o.value != f.value -> out += Change(Kind.FIELD_CHANGED, null, name)
            }
        }
        for (name in old.fields.keys) if (name !in new.fields) out += Change(Kind.FIELD_REMOVED, null, name)
        return out
    }

    /**
     * End offsets (exclusive, after the EOL that follows) of every `%%EOF` marker — one per saved revision.
     * The bytes `[0, end)` of an entry are that complete revision.
     */
    fun revisionEnds(bytes: ByteArray): List<Int> {
        val marker = "%%EOF".toByteArray(Charsets.US_ASCII)
        val out = ArrayList<Int>()
        var i = 0
        while (i <= bytes.size - marker.size) {
            var hit = true
            for (j in marker.indices) if (bytes[i + j] != marker[j]) { hit = false; break }
            if (hit) {
                var end = i + marker.size
                if (end < bytes.size && bytes[end] == '\r'.code.toByte()) end++
                if (end < bytes.size && bytes[end] == '\n'.code.toByte()) end++
                out += end; i = end
            } else i++
        }
        return out
    }

    /** Short count summary by kind (for a subtitle such as "3 annotations added · 1 page rotated"). */
    fun counts(changes: List<Change>): Map<Kind, Int> = changes.groupingBy { it.kind }.eachCount()
}
