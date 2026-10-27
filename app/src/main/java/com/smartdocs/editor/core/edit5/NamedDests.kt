package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A named destination: [page] zero-based (-1 = its page is missing), how it shows the page. */
data class NamedDest(val name: String, val page: Int, val view: DestView = DestView(DestFit.FIT))

/**
 * The Destinations panel (Foxit): list, create, set, rename, delete named destinations. They live in the
 * catalog's /Names /Dests name tree (PDF 1.2+) or the older catalog /Dests dictionary; both are read, and
 * writing puts them all in one sorted name tree. Renaming also re-points links, bookmarks and go-to actions
 * that use the old name.
 */
object NamedDests {
    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    private fun destArray(b: COSBase?): COSArray? = when (val v = deref(b)) {
        is COSArray -> v
        is COSDictionary -> deref(v.getDictionaryObject(COSName.D)) as? COSArray
        else -> null
    }

    /** Raw name → destination array of [doc]. */
    fun raw(doc: PDDocument): LinkedHashMap<String, COSArray> {
        val out = LinkedHashMap<String, COSArray>()
        val cat = doc.documentCatalog.cosObject
        (deref(cat.getDictionaryObject(COSName.DESTS)) as? COSDictionary)?.let { d ->
            for (k in d.keySet()) destArray(d.getDictionaryObject(k))?.let { out[k.name] = it }
        }
        val names = deref(cat.getDictionaryObject(COSName.NAMES)) as? COSDictionary
        fun walk(node: COSDictionary?, depth: Int) {
            if (node == null || depth > 16 || out.size > 20000) return
            (deref(node.getDictionaryObject(COSName.NAMES)) as? COSArray)?.let { arr ->
                var i = 0
                while (i + 1 < arr.size()) {
                    val key = (deref(arr.getObject(i)) as? COSString)?.string
                    if (key != null) destArray(arr.getObject(i + 1))?.let { out[key] = it }
                    i += 2
                }
            }
            (deref(node.getDictionaryObject(COSName.KIDS)) as? COSArray)?.let { kids -> for (k in 0 until kids.size()) walk(deref(kids.getObject(k)) as? COSDictionary, depth + 1) }
        }
        walk(deref(names?.getDictionaryObject(COSName.DESTS)) as? COSDictionary, 0)
        return out
    }

    fun list(doc: PDDocument): List<NamedDest> {
        val idx = doc.pages.withIndex().associate { it.value.cosObject to it.index }
        return raw(doc).map { (n, a) -> NamedDest(n, (deref(a.getObject(0)) as? COSDictionary)?.let { idx[it] } ?: -1, Dests.view(a)) }
    }

    /** Page of destination [name] in [file] (no password), or null. */
    suspend fun pageOf(file: File, name: String): Int? = withContext(Dispatchers.IO) {
        runCatching { E5Io.load(file, null).use { d -> list(d).firstOrNull { it.name == name }?.page?.takeIf { it >= 0 } } }.getOrNull()
    }

    suspend fun load(src: File, password: String?): List<NamedDest> = withContext(Dispatchers.IO) { E5Io.load(src, password).use { list(it) } }

    /** Write [dests] as the document's named destinations (one flat, sorted name tree; the old /Dests is removed). */
    fun write(doc: PDDocument, dests: Map<String, COSArray>) {
        val cat = doc.documentCatalog.cosObject
        cat.removeItem(COSName.DESTS)
        // a fresh /Names dictionary (a copy keeps the other trees), so an incremental save writes it as a new object
        val names = (deref(cat.getDictionaryObject(COSName.NAMES)) as? COSDictionary)?.let { COSDictionary(it) } ?: COSDictionary()
        if (dests.isEmpty()) names.removeItem(COSName.DESTS)
        else names.setItem(COSName.DESTS, COSDictionary().apply {
            setItem(COSName.NAMES, COSArray().apply { dests.toSortedMap(compareBy<String> { it }).forEach { (k, v) -> add(COSString(k)); add(v) } })
        })
        if (names.size() == 0) cat.removeItem(COSName.NAMES) else cat.setItem(COSName.NAMES, names)
    }

    sealed class Op {
        data class Set(val name: String, val page: Int, val view: DestView) : Op()
        data class Delete(val name: String) : Op()
        data class Rename(val from: String, val to: String) : Op()
    }

    /** Apply [ops] to [doc]; returns the pages whose annotations changed (renamed link targets). */
    fun apply(doc: PDDocument, ops: List<Op>): Set<PDPage> {
        val m = raw(doc)
        val renames = HashMap<String, String>()
        for (op in ops) when (op) {
            is Op.Set -> if (doc.numberOfPages > 0 && op.name.isNotBlank()) m[op.name] = Dests.array(doc.getPage(op.page.coerceIn(0, doc.numberOfPages - 1)).cosObject, op.view)
            is Op.Delete -> m.remove(op.name)
            is Op.Rename -> if (op.to.isNotBlank() && op.to != op.from) m.remove(op.from)?.let { m[op.to] = it; renames[op.from] = op.to }
        }
        write(doc, m)
        return if (renames.isEmpty()) emptySet() else rename(doc, renames)
    }

    suspend fun edit(src: File, out: File, password: String?, ops: List<Op>) = withContext(Dispatchers.IO) {
        NavIo.edit(src, out, password) { doc, changed -> changed += apply(doc, ops) }
    }

    /** Re-point every use of a renamed destination: link /Dest, GoTo /D in links and bookmarks. */
    private fun rename(doc: PDDocument, map: Map<String, String>): Set<PDPage> {
        fun swap(holder: COSDictionary, key: COSName): Boolean {
            val v = deref(holder.getDictionaryObject(key))
            val old = (v as? COSString)?.string ?: (v as? COSName)?.name ?: return false
            val nw = map[old] ?: return false
            holder.setItem(key, COSString(nw)); return true
        }
        fun action(a: COSBase?): Boolean {
            var hit = false
            for (d in NavActions.flatten(a)) if (d.getNameAsString(COSName.S) == "GoTo" && swap(d, COSName.D)) { hit = true; d.isNeedToBeUpdated = true }
            return hit
        }
        val changed = HashSet<PDPage>()
        for (pg in doc.pages) {
            val annots = runCatching { pg.annotations }.getOrNull() ?: continue
            for (a in annots) {
                val d = a.cosObject
                if (d.getNameAsString(COSName.SUBTYPE) != "Link") continue
                if (swap(d, COSName.DEST) or action(d.getDictionaryObject(COSName.A))) changed += pg
            }
        }
        // outline items (the catalog is rewritten; touched items are flagged for an incremental save)
        // (with their ancestors and siblings before them, so the writer reaches them from the catalog)
        val seen = HashSet<COSDictionary>()
        fun walk(node: COSDictionary, path: List<COSDictionary>) {
            var it = deref(node.getDictionaryObject(COSName.FIRST)) as? COSDictionary
            val chain = ArrayList<COSDictionary>()
            while (it != null && seen.add(it) && path.size < 40) {
                chain += it
                if (swap(it, COSName.DEST) or action(it.getDictionaryObject(COSName.A))) (path + chain).forEach { d -> d.isNeedToBeUpdated = true }
                walk(it, path + chain)
                it = deref(it.getDictionaryObject(COSName.NEXT)) as? COSDictionary
            }
        }
        (deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.OUTLINES)) as? COSDictionary)?.let { walk(it, listOf(it)) }
        return changed
    }

    /** Sort for the panel: by name (natural, case-insensitive) or by page then name. */
    fun sorted(list: List<NamedDest>, byPage: Boolean): List<NamedDest> =
        if (byPage) list.sortedWith(compareBy<NamedDest> { if (it.page < 0) Int.MAX_VALUE else it.page }.thenBy { it.name.lowercase() })
        else list.sortedBy { it.name.lowercase() }

    /** A free name like "Dest 3" for a new destination. */
    fun freeName(existing: Collection<String>, base: String): String {
        var i = existing.size + 1
        while ("$base $i" in existing) i++
        return "$base $i"
    }
}
