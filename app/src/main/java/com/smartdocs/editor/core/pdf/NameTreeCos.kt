package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString

/**
 * Edits of a PDF name tree (EmbeddedFiles…) at the COS level, in place, so a signed file can save only the
 * touched nodes as an incremental update (PDNameTreeNode.setNames rebuilds the root and drops /Kids).
 * Every edit returns the node dictionaries it changed (root first), to be flagged for the incremental save.
 */
object NameTreeCos {

    private val NAMES = COSName.NAMES
    private val KIDS = COSName.KIDS
    private val LIMITS = COSName.LIMITS

    /** One leaf entry: key, value and the node path from the root to the leaf holding it. */
    class Entry(val key: String, val value: COSBase?, val path: List<COSDictionary>)

    fun entries(root: COSDictionary): List<Entry> = ArrayList<Entry>().also { walk(root, listOf(root), it, 0) }

    private fun walk(node: COSDictionary, path: List<COSDictionary>, out: MutableList<Entry>, depth: Int) {
        if (depth > 32) return
        (node.getDictionaryObject(NAMES) as? COSArray)?.let { a ->
            var i = 0
            while (i + 1 < a.size()) {
                (a.getObject(i) as? COSString)?.string?.let { out += Entry(it, a.getObject(i + 1), path) }
                i += 2
            }
        }
        (node.getDictionaryObject(KIDS) as? COSArray)?.let { kids ->
            for (k in 0 until kids.size()) (kids.getObject(k) as? COSDictionary)?.let { walk(it, path + it, out, depth + 1) }
        }
    }

    /** Value stored under [key], or null. */
    fun get(root: COSDictionary, key: String): COSBase? = entries(root).firstOrNull { it.key == key }?.value

    /** Removes [key]. Returns the changed nodes (empty leaves are unlinked from their parent), or null when absent. */
    fun remove(root: COSDictionary, key: String): List<COSDictionary>? {
        val e = entries(root).firstOrNull { it.key == key } ?: return null
        val leaf = e.path.last()
        val a = leaf.getDictionaryObject(NAMES) as? COSArray ?: return null
        var i = 0
        while (i + 1 < a.size()) {
            if ((a.getObject(i) as? COSString)?.string == key) { a.remove(i + 1); a.remove(i); break }
            i += 2
        }
        val touched = ArrayList(e.path)
        // an empty leaf (not the root) is removed from its parent's /Kids
        var p = e.path.size - 1
        while (p > 0 && isEmpty(e.path[p])) {
            (e.path[p - 1].getDictionaryObject(KIDS) as? COSArray)?.let { kids -> removeKid(kids, e.path[p]) }
            p--
        }
        e.path.drop(1).forEach { updateLimits(it) }
        return touched
    }

    /** Stores [value] under [key] (replacing an existing value), keeping the keys sorted. Returns the changed nodes. */
    fun put(root: COSDictionary, key: String, value: COSBase): List<COSDictionary> {
        val existing = entries(root).firstOrNull { it.key == key }
        val path: List<COSDictionary> = existing?.path ?: run {
            // descend to the leaf whose range holds the key (or the nearest one)
            val p = arrayListOf(root)
            var node = root
            var depth = 0
            while (depth++ < 32) {
                val kids = node.getDictionaryObject(KIDS) as? COSArray ?: break
                val list = (0 until kids.size()).mapNotNull { kids.getObject(it) as? COSDictionary }
                if (list.isEmpty()) break
                node = list.firstOrNull { k -> limits(k)?.let { (lo, hi) -> key >= lo && key <= hi } == true }
                    ?: list.lastOrNull { k -> limits(k)?.let { (lo, _) -> lo <= key } == true } ?: list.first()
                p += node
            }
            p
        }
        val leaf = path.last()
        val a = leaf.getDictionaryObject(NAMES) as? COSArray ?: COSArray().also { leaf.setItem(NAMES, it) }
        var i = 0
        var placed = false
        while (i + 1 < a.size()) {
            val k = (a.getObject(i) as? COSString)?.string
            if (k == key) { a.set(i + 1, value); placed = true; break }
            if (k != null && k > key) { a.add(i, COSString(key)); a.add(i + 1, value); placed = true; break }
            i += 2
        }
        if (!placed) { a.add(COSString(key)); a.add(value) }
        path.drop(1).forEach { updateLimits(it) }
        return path
    }

    /** [name] made unique among [taken] ("a.pdf" → "a_2.pdf"). */
    fun uniqueKey(taken: Set<String>, name: String): String {
        val base = name.ifBlank { "file" }
        if (base !in taken) return base
        val stem = base.substringBeforeLast('.'); val ext = base.substringAfterLast('.', "")
        var n = 2
        while (true) {
            val k = stem + "_" + n++ + if (ext.isNotEmpty() && base.contains('.')) ".$ext" else ""
            if (k !in taken) return k
        }
    }

    private fun isEmpty(node: COSDictionary): Boolean =
        ((node.getDictionaryObject(NAMES) as? COSArray)?.size() ?: 0) == 0 && ((node.getDictionaryObject(KIDS) as? COSArray)?.size() ?: 0) == 0

    private fun removeKid(kids: COSArray, node: COSDictionary) {
        for (k in kids.size() - 1 downTo 0) if (kids.getObject(k) === node) kids.remove(k)
    }

    private fun limits(node: COSDictionary): Pair<String, String>? {
        val l = node.getDictionaryObject(LIMITS) as? COSArray ?: return null
        val lo = (l.getObject(0) as? COSString)?.string ?: return null
        val hi = (l.getObject(1) as? COSString)?.string ?: return null
        return lo to hi
    }

    /** Recomputes /Limits of a non-root node from its own keys and its kids' limits. */
    private fun updateLimits(node: COSDictionary) {
        val keys = ArrayList<String>()
        (node.getDictionaryObject(NAMES) as? COSArray)?.let { a -> var i = 0; while (i < a.size()) { (a.getObject(i) as? COSString)?.string?.let(keys::add); i += 2 } }
        (node.getDictionaryObject(KIDS) as? COSArray)?.let { kids -> for (k in 0 until kids.size()) (kids.getObject(k) as? COSDictionary)?.let { limits(it) }?.let { (lo, hi) -> keys += lo; keys += hi } }
        if (keys.isEmpty()) { node.removeItem(LIMITS); return }
        node.setItem(LIMITS, COSArray().apply { add(COSString(keys.min())); add(COSString(keys.max())) })
    }
}
