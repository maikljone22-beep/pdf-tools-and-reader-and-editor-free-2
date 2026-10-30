package com.smartdocs.editor.core.annot

import com.smartdocs.editor.core.pdf.Annot
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * §41 comments made by other editors (Bluebeam /BSI and /BSIColumnData, custom keys of other apps): editing a stored
 * comment rewrites it, so the keys our writer does not know are copied aside when it is taken out of the file and put
 * back on the rewritten annotation (matched by /NM) when it is saved. Standard annotation keys are never copied back:
 * the editor owns them (a removed fill or border must stay removed). Streams are not carried over.
 */
object ForeignKeys {
    /** Keys of ISO 32000 annotation dictionaries (and their markup / type specific entries) that the editor writes itself. */
    val STANDARD: Set<String> = setOf(
        "Type", "Subtype", "Rect", "Contents", "P", "NM", "M", "F", "AP", "AS", "Border", "C", "StructParent", "OC", "AF", "ca", "CA", "BM", "Lang",
        "T", "Popup", "RC", "CreationDate", "IRT", "Subj", "RT", "IT", "ExData", "State", "StateModel",
        "BS", "BE", "IC", "LE", "L", "LL", "LLE", "LLO", "Cap", "CP", "CO", "Measure", "DA", "DS", "Q", "CL", "RD",
        "QuadPoints", "InkList", "Vertices", "Path", "Name", "Open", "Rotate", "Parent", "FS", "Sound", "Movie", "A", "AA", "MK", "PA", "H", "Dest",
    )
    private const val MAX_DEPTH = 8
    private const val MAX_ITEMS = 2000
    private val store = ConcurrentHashMap<String, COSDictionary>()

    /** The non-standard entries of [cos], deep-copied so they outlive the document (null when there are none). */
    fun snapshot(cos: COSDictionary): COSDictionary? {
        val out = COSDictionary()
        var budget = MAX_ITEMS
        fun copy(b: COSBase?, depth: Int, seen: MutableSet<Any>): COSBase? {
            if (b == null || --budget < 0 || depth > MAX_DEPTH) return null
            val v = if (b is COSObject) b.getObject() ?: return null else b
            return when (v) {
                is COSStream -> null                                  // tied to the source file: not carried over
                is COSDictionary -> {
                    if (!seen.add(v)) return null
                    COSDictionary().also { d -> v.entrySet().forEach { (k, x) -> if (k.name != "P" && k.name != "Parent") copy(x, depth + 1, seen)?.let { d.setItem(k, it) } } }
                }
                is COSArray -> { if (!seen.add(v)) return null; COSArray().also { a -> for (i in 0 until v.size()) copy(v.get(i), depth + 1, seen)?.let { a.add(it) } } }
                else -> v                                             // names, strings, numbers, booleans: immutable values
            }
        }
        cos.entrySet().forEach { (k, v) -> if (k.name !in STANDARD) copy(v, 0, HashSet())?.let { out.setItem(k, it) } }
        return out.takeIf { it.size() > 0 }
    }

    /** Copy back the entries of [saved] that [target] does not have (never a standard key). */
    fun merge(target: COSDictionary, saved: COSDictionary) {
        saved.entrySet().forEach { (k, v) -> if (k.name !in STANDARD && !target.containsKey(k)) target.setItem(k, v) }
    }

    fun remember(nm: String, saved: COSDictionary) { store[nm] = saved }
    fun pending(nm: String?): COSDictionary? = nm?.let { store[it] }
    fun clear() = store.clear()

    /**
     * Before stored annotation [index] of [page] in [src] is rewritten as [a]: keep its foreign keys. Returns [a] with a
     * /NM to match it by later (its own, or a new one when the annotation had none).
     */
    fun capture(src: File, password: String?, page: Int, index: Int, a: Annot): Annot = runCatching {
        val saved = (if (password.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, password)).use { d ->
            d.getPage(page).annotations.getOrNull(index)?.cosObject?.let { snapshot(it) }
        } ?: return a
        val nm = a.nm ?: ("sd-fk-" + System.nanoTime().toString(36))
        remember(nm, saved)
        a.copy(nm = nm)
    }.getOrDefault(a)

    /** After [a] was written to [page] (new annotations from index [before]): put its foreign keys back. */
    fun restore(page: PDPage, before: Int, a: Annot) {
        val saved = pending(a.nm) ?: return
        runCatching { page.annotations.drop(before) }.getOrNull()?.forEach { an ->
            if (an.annotationName == a.nm) merge(an.cosObject, saved)
        }
    }
}
