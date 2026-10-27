package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode

/**
 * nav-prefs (Nitro Preferences → Bookmarks):
 *  - [removeFor]: "remove related bookmarks when deleting pages" — bookmarks whose destination is a deleted page are
 *    unlinked; one that still has children stays as a folder without a destination;
 *  - [importFrom]: "import bookmarks when replacing pages" — the bookmarks of the replacement file that point at the
 *    pages brought in are copied, pointing at their new copies.
 * Works on the COS level (PdfBox 2.0 has no outline-item removal).
 */
object NpfOutline {

    /** Unlink every bookmark of [doc] whose destination page is in [deleted]; returns how many were removed. */
    fun removeFor(doc: PDDocument, deleted: Set<COSDictionary>): Int {
        if (deleted.isEmpty()) return 0
        val outline = doc.documentCatalog.documentOutline ?: return 0
        var removed = 0
        fun visit(node: PDOutlineNode, depth: Int) {
            if (depth > 40) return
            var item: PDOutlineItem? = node.firstChild
            var guard = 0
            while (item != null && guard++ < 50_000) {
                val next = item.nextSibling
                visit(item, depth + 1)
                val page = runCatching { item.findDestinationPage(doc) }.getOrNull()
                if (page != null && page.cosObject in deleted) {
                    if (item.hasChildren()) { item.cosObject.removeItem(COSName.DEST); item.cosObject.removeItem(COSName.A) }
                    else { unlink(node.cosObject, item.cosObject); removed++ }
                }
                item = next
            }
        }
        visit(outline, 0)
        return removed
    }

    /** Remove [item] from [parent]'s child list (Prev / Next / First / Last / Count). */
    internal fun unlink(parent: COSDictionary, item: COSDictionary) {
        val prev = deref(item.getItem(COSName.PREV)) as? COSDictionary
        val next = deref(item.getItem(COSName.NEXT)) as? COSDictionary
        if (prev != null) { if (next != null) prev.setItem(COSName.NEXT, next) else prev.removeItem(COSName.NEXT) }
        else { if (next != null) parent.setItem(COSName.FIRST, next) else parent.removeItem(COSName.FIRST) }
        if (next != null) { if (prev != null) next.setItem(COSName.PREV, prev) else next.removeItem(COSName.PREV) }
        else { if (prev != null) parent.setItem(COSName.LAST, prev) else parent.removeItem(COSName.LAST) }
        val count = parent.getInt(COSName.COUNT, 0)
        if (count > 0) parent.setInt(COSName.COUNT, count - 1) else if (count < 0) parent.setInt(COSName.COUNT, count + 1)
        if (parent.getDictionaryObject(COSName.FIRST) == null) parent.removeItem(COSName.COUNT)
        item.removeItem(COSName.PARENT)
    }

    /**
     * Copy the bookmarks of [other] that point at pages of [map] (other's page → its copy in [doc]) to the end of
     * [doc]'s outline, under one folder titled [folderTitle] (or at the top level when it is null). Returns the count.
     */
    fun importFrom(doc: PDDocument, other: PDDocument, map: Map<COSDictionary, PDPage>, folderTitle: String?): Int {
        val src = other.documentCatalog.documentOutline ?: return 0
        val outline = doc.documentCatalog.documentOutline ?: PDDocumentOutline().also { doc.documentCatalog.documentOutline = it }
        val parent: PDOutlineNode = folderTitle?.let { t -> PDOutlineItem().also { it.title = t } } ?: outline
        var n = 0
        fun copy(from: PDOutlineNode, to: PDOutlineNode, depth: Int) {
            if (depth > 40) return
            var item: PDOutlineItem? = from.firstChild
            var guard = 0
            while (item != null && guard++ < 50_000) {
                val page = runCatching { item.findDestinationPage(other) }.getOrNull()?.let { map[it.cosObject] }
                val c = PDOutlineItem().also { it.title = item.title ?: "" }
                if (page != null) c.destination = PDPageFitDestination().also { it.page = page }
                val before = n
                copy(item, c, depth + 1)
                if (page != null || n > before) { to.addLast(c); if (page != null) n++ }
                item = item.nextSibling
            }
        }
        copy(src, parent, 0)
        if (parent !== outline && n > 0) outline.addLast(parent as PDOutlineItem)
        return n
    }

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b
}
