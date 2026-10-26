package com.smartdocs.editor.core.redact

import com.smartdocs.editor.core.edit.ContentRewriter
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.util.IdentityHashMap

/**
 * What a content-only rewrite leaves behind and true redaction must also remove:
 *  - images / form XObjects no longer painted by any `Do` (audit AN-07): PdfBox saves everything reachable from
 *    /Resources, so a removed or blanked image would otherwise still be in the file, extractable with `pdfimages`;
 *  - /ActualText, /Alt and /E of marked content around removed glyphs, and of the structure elements that own that
 *    marked content (audit AN-09): the app itself tags every shaped Arabic line with its logical text.
 */
internal object RedactCleanup {
    private val ACTUAL_TEXT = COSName.getPDFName("ActualText")
    private val ALT = COSName.getPDFName("Alt")
    private val E = COSName.getPDFName("E")
    private val MCID = COSName.getPDFName("MCID")
    private val PROPERTIES = COSName.getPDFName("Properties")
    private val TEXT_KEYS = listOf(ACTUAL_TEXT, ALT, E)

    // ---------------- AN-07: unused XObjects ----------------

    /** Names painted with `Do` in [page]'s content, or null when the content cannot be parsed (then nothing is pruned). */
    fun usedXObjects(page: PDPage): Set<COSName>? = runCatching {
        val tokens = PDFStreamParser(page).let { it.parse(); it.tokens }
        val used = HashSet<COSName>()
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t is Operator && t.name == "Do") (tokens.getOrNull(i - 1) as? COSName)?.let(used::add)
        }
        used
    }.getOrNull()

    /**
     * Removes every /XObject resource entry that no page using that resource dictionary paints. A dictionary shared
     * by several pages (or inherited from /Pages) keeps the union of their names. Returns how many entries went.
     */
    fun pruneUnusedXObjects(doc: PDDocument): Int {
        val usedBy = IdentityHashMap<COSDictionary, HashSet<COSName>>()
        val unknown = java.util.Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        for (page in doc.pages) {
            val xo = runCatching { page.resources?.cosObject?.getDictionaryObject(COSName.XOBJECT) as? COSDictionary }.getOrNull() ?: continue
            val used = usedXObjects(page)
            if (used == null) { unknown += xo; continue }
            usedBy.getOrPut(xo) { HashSet() }.addAll(used)
        }
        // /Resources on intermediate /Pages nodes that no page resolves to any more (every page got its own, e.g. after
        // rasterising) still reach the file: none of their XObjects is painted
        fun nodes(node: COSDictionary, depth: Int) {
            if (depth > 64) return
            val xo = (node.getDictionaryObject(COSName.RESOURCES) as? COSDictionary)?.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
            if (xo != null && xo !in unknown && !usedBy.containsKey(xo) && node.getCOSName(COSName.TYPE) == COSName.PAGES) usedBy[xo] = HashSet()
            val kids = node.getDictionaryObject(COSName.KIDS) as? COSArray ?: return
            for (i in 0 until kids.size()) (kids.getObject(i) as? COSDictionary)?.let { if (it.getCOSName(COSName.TYPE) == COSName.PAGES) nodes(it, depth + 1) }
        }
        runCatching { nodes(doc.pages.cosObject, 0) }
        var removed = 0
        for ((xo, used) in usedBy) {
            if (xo in unknown) continue
            for (name in xo.keySet().toList()) if (name !in used) { xo.removeItem(name); removed++ }
        }
        return removed
    }

    // ---------------- AN-09: /ActualText around removed content ----------------

    /**
     * Operator indexes of the BDC operators whose marked-content span (up to the matching EMC) contains one of
     * [touched]. [names] are the operator names in content order.
     */
    fun spansTouching(names: List<String?>, touched: Set<Int>): Set<Int> {
        val out = HashSet<Int>()
        val stack = ArrayList<Int>()   // operator index of each open BDC / BMC (-1 for BMC: no properties)
        for ((i, n) in names.withIndex()) {
            when (n) {
                "BDC" -> stack += i
                "BMC" -> stack += -1
                "EMC" -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> if (i in touched) stack.forEach { if (it >= 0) out += it }
            }
        }
        return out
    }

    /**
     * In [rw], removes /ActualText /Alt /E from the property lists of BDC spans containing a [touched] operator
     * (inline dictionaries are replaced; named ones from the page's /Properties are edited). Returns the MCIDs of
     * those spans so [stripStructure] can clean the structure elements that own them.
     */
    fun stripActualText(rw: ContentRewriter, page: PDPage, touched: Set<Int>): Set<Int> {
        if (touched.isEmpty()) return emptySet()
        val spans = spansTouching((0 until rw.operatorCount).map { rw.operatorName(it) }, touched)
        val mcids = HashSet<Int>()
        for (op in spans) {
            val orig = rw.original(op)
            val operands = orig.dropLast(1)
            val tag = operands.getOrNull(operands.size - 2) as? COSName ?: continue
            when (val props = operands.lastOrNull()) {
                is COSDictionary -> {
                    (props.getDictionaryObject(MCID) as? COSInteger)?.let { mcids += it.intValue() }
                    if (TEXT_KEYS.any(props::containsKey)) {
                        val copy = COSDictionary(props); TEXT_KEYS.forEach(copy::removeItem)
                        rw.replace(op, listOf(tag, copy, Operator.getOperator("BDC")))
                    }
                }
                is COSName -> {
                    val dict = runCatching {
                        (page.resources?.cosObject?.getDictionaryObject(PROPERTIES) as? COSDictionary)?.getDictionaryObject(props) as? COSDictionary
                    }.getOrNull() ?: continue
                    (dict.getDictionaryObject(MCID) as? COSInteger)?.let { mcids += it.intValue() }
                    TEXT_KEYS.forEach(dict::removeItem)
                }
            }
        }
        return mcids
    }

    /**
     * Removes /ActualText /Alt /E from structure elements on [page] whose content includes one of [mcids].
     * Returns how many elements were cleaned.
     */
    fun stripStructure(doc: PDDocument, page: PDPage, mcids: Set<Int>): Int {
        if (mcids.isEmpty()) return 0
        val root = doc.documentCatalog.cosObject.getDictionaryObject(COSName.STRUCT_TREE_ROOT) as? COSDictionary ?: return 0
        val pageDict = page.cosObject
        var n = 0
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())

        fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

        // true when the subtree of [el] holds one of the MCIDs on this page; cleans every element on the way up,
        // so an ancestor summarising the whole line (e.g. a /P with /ActualText) loses its text too
        fun walk(el: COSDictionary, inheritedPg: COSDictionary?): Boolean {
            if (!seen.add(el)) return false
            val pg = (el.getDictionaryObject(COSName.PG) as? COSDictionary) ?: inheritedPg
            var hit = false
            fun visit(kid: COSBase?) {
                when (val v = deref(kid)) {
                    is COSInteger -> if (pg === pageDict && v.intValue() in mcids) hit = true
                    is COSDictionary -> if (v.getCOSName(COSName.TYPE) == COSName.getPDFName("MCR")) {
                        val mpg = (v.getDictionaryObject(COSName.PG) as? COSDictionary) ?: pg
                        if (mpg === pageDict && (v.getDictionaryObject(MCID) as? COSInteger)?.intValue() in mcids) hit = true
                    } else if (v.containsKey(COSName.S) || v.containsKey(COSName.K)) { if (walk(v, pg)) hit = true }
                    else -> {}
                }
            }
            when (val k = deref(el.getDictionaryObject(COSName.K))) {
                is COSArray -> for (i in 0 until k.size()) visit(k.get(i))
                null -> {}
                else -> visit(k)
            }
            if (hit && TEXT_KEYS.any(el::containsKey)) { TEXT_KEYS.forEach(el::removeItem); n++ }
            return hit
        }
        walk(root, null)
        return n
    }
}
