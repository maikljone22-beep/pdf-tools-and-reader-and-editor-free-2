package com.smartdocs.editor.core.redact

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument

/**
 * Embedded search indexes (Nitro "Sanitize: embedded search indexes"). Acrobat's catalog keeps them in a
 * /PieceInfo entry (Acrobat search / catalog data) or as an embedded index file (`*.pdx`, `*.idx`, the `.ix?`
 * parts). Only those entries are removed: other private data and attachments stay unless their own item is chosen.
 */
internal object SearchIndex {
    private val N_PIECE = COSName.getPDFName("PieceInfo")
    private val PIECE_KEY = Regex("(?i)search|index|catalog|idx")
    private val FILE_NAME = Regex("(?i).*(\\.pdx|\\.idx|\\.ix[a-z]|\\.lck|index\\.did)$")

    fun isIndexFile(name: String) = FILE_NAME.matches(name.trim())

    /** What [remove] would take out, for the scan details. */
    fun find(doc: PDDocument): List<String> {
        val out = ArrayList<String>()
        pieceKeys(doc).forEach { out += "PieceInfo /${it.name}" }
        embedded(doc)?.let { tree -> Sanitizer.collectNames(tree).filter(::isIndexFile).forEach { out += it } }
        return out
    }

    fun remove(doc: PDDocument): Int {
        var n = 0
        val piece = doc.documentCatalog.cosObject.getDictionaryObject(N_PIECE) as? COSDictionary
        pieceKeys(doc).forEach { piece?.removeItem(it); n++ }
        if (piece != null && piece.size() == 0) doc.documentCatalog.cosObject.removeItem(N_PIECE)
        embedded(doc)?.let { n += dropNames(it, 0) }
        return n
    }

    private fun pieceKeys(doc: PDDocument): List<COSName> {
        val piece = doc.documentCatalog.cosObject.getDictionaryObject(N_PIECE) as? COSDictionary ?: return emptyList()
        return piece.keySet().filter { PIECE_KEY.containsMatchIn(it.name) }
    }

    private fun embedded(doc: PDDocument): COSDictionary? =
        (doc.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary)?.getDictionaryObject(COSName.EMBEDDED_FILES) as? COSDictionary

    /** Removes the index files from a name tree node (and its kids). Limits are left as they are (still valid bounds). */
    private fun dropNames(node: COSDictionary, depth: Int): Int {
        if (depth > 20) return 0
        var n = 0
        (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { arr ->
            var i = 0
            while (i + 1 < arr.size()) {
                val name = (arr.getObject(i) as? COSString)?.string
                if (name != null && isIndexFile(name)) { arr.remove(i + 1); arr.remove(i); n++ } else i += 2
            }
        }
        (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids ->
            for (i in 0 until kids.size()) (kids.getObject(i) as? COSDictionary)?.let { n += dropNames(it, depth + 1) }
        }
        return n
    }
}

/**
 * The tag structure (Nitro "Sanitize: document structure"): the structure tree, /MarkInfo and every
 * /StructParent(s) link of pages, annotations and XObjects. The marked-content operators in the page content
 * stay; without the tree they mean nothing.
 */
internal object Structure {
    private val N_TREE = COSName.getPDFName("StructTreeRoot")
    private val N_MARK = COSName.getPDFName("MarkInfo")
    private val N_SP = COSName.getPDFName("StructParent")
    private val N_SPS = COSName.getPDFName("StructParents")

    /** Number of structure elements (at least 1 when a tree exists) and the first element types, for the scan. */
    fun count(doc: PDDocument): Pair<Int, List<String>> {
        val root = doc.documentCatalog.cosObject.getDictionaryObject(N_TREE) as? COSDictionary ?: return 0 to emptyList()
        val types = LinkedHashMap<String, Int>()
        var n = 0
        val seen = HashSet<COSDictionary>()
        fun walk(d: COSDictionary, depth: Int) {
            if (depth > 60 || n > 50_000 || !seen.add(d)) return
            d.getCOSName(COSName.S)?.name?.let { types[it] = (types[it] ?: 0) + 1; n++ }
            when (val k = d.getDictionaryObject(COSName.K)) {
                is COSDictionary -> walk(k, depth + 1)
                is COSArray -> for (i in 0 until k.size()) (k.getObject(i) as? COSDictionary)?.let { walk(it, depth + 1) }
                else -> {}
            }
        }
        walk(root, 0)
        return n.coerceAtLeast(1) to types.entries.sortedByDescending { it.value }.take(30).map { "${it.key}: ${it.value}" }
    }

    fun remove(doc: PDDocument): Int {
        val cat = doc.documentCatalog.cosObject
        if (!cat.containsKey(N_TREE) && !cat.containsKey(N_MARK)) return 0
        val n = count(doc).first.coerceAtLeast(1)
        cat.removeItem(N_TREE); cat.removeItem(N_MARK)
        doc.pages.forEach { p ->
            p.cosObject.removeItem(N_SPS)
            runCatching { p.annotations }.getOrNull()?.forEach { it.cosObject.removeItem(N_SP) }
            val xo = p.resources?.cosObject?.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
            xo?.keySet()?.forEach { k -> (xo.getDictionaryObject(k) as? COSDictionary)?.let { it.removeItem(N_SPS); it.removeItem(N_SP) } }
        }
        return n
    }
}
