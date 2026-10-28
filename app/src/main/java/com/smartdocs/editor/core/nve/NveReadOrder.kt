package com.smartdocs.editor.core.nve

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton

/**
 * Read Out Loud reading order (Foxit "Reading Order" / "read comments, fields, alt text"): extra spoken items of a
 * page (comments, form fields, figure alternate text) merged into the page text. Pure PDFBox, no android classes.
 */
object NveReadOrder {

    enum class Order { CONTENT, STRUCTURE, TEXT_THEN_EXTRAS }

    enum class Kind { COMMENT, FIELD, ALT }

    /** One extra item: [yFrac] = 0 at the top of the page, 1 at the bottom; [seq] = structure / document order. */
    data class Extra(val kind: Kind, val yFrac: Float, val seq: Int, val text: String)

    data class Prefs(val comments: Boolean = false, val fields: Boolean = false, val alt: Boolean = true, val order: Order = Order.CONTENT) {
        val any: Boolean get() = comments || fields || alt
    }

    /** Localised words used in the spoken items (the caller passes the UI language). */
    data class Labels(
        val comment: String = "Comment", val by: String = "by", val field: String = "Field",
        val checked: String = "checked", val unchecked: String = "not checked", val empty: String = "empty", val figure: String = "Figure",
    )

    /**
     * Merge [extras] into [base] (the page text, lines separated by '\n').
     * CONTENT: each extra is spoken after the base line at its vertical position. STRUCTURE: base text, then extras in
     * structure / document order. TEXT_THEN_EXTRAS: base text, then extras top to bottom.
     */
    fun merge(base: String, extras: List<Extra>, order: Order): String {
        if (extras.isEmpty()) return base
        val lines = base.split('\n')
        return when (order) {
            Order.STRUCTURE -> (listOf(base) + extras.sortedBy { it.seq }.map { it.text }).filter { it.isNotBlank() }.joinToString("\n")
            Order.TEXT_THEN_EXTRAS -> (listOf(base) + extras.sortedWith(compareBy({ it.yFrac }, { it.seq })).map { it.text }).filter { it.isNotBlank() }.joinToString("\n")
            Order.CONTENT -> {
                val n = lines.size
                val slots = HashMap<Int, MutableList<Extra>>()
                for (e in extras) {
                    val at = if (base.isBlank()) 0 else (e.yFrac.coerceIn(0f, 1f) * n).toInt().coerceIn(0, n)
                    slots.getOrPut(at) { ArrayList() } += e
                }
                val out = ArrayList<String>()
                for (i in 0..n) {
                    slots[i]?.sortedWith(compareBy({ it.yFrac }, { it.seq }))?.forEach { out += it.text }
                    if (i < n && lines[i].isNotBlank()) out += lines[i]
                }
                out.joinToString("\n")
            }
        }
    }

    /** Extras of page [pageIndex] of [doc] selected by [prefs]. */
    fun extras(doc: PDDocument, pageIndex: Int, prefs: Prefs, labels: Labels = Labels()): List<Extra> {
        if (!prefs.any || pageIndex !in 0 until doc.numberOfPages) return emptyList()
        val page = doc.getPage(pageIndex)
        val box = page.cropBox
        val top = box.upperRightY; val h = box.height.coerceAtLeast(1f)
        fun frac(y: Float) = ((top - y) / h).coerceIn(0f, 1f)
        val out = ArrayList<Extra>()
        var seq = 0
        val annots = runCatching { page.annotations }.getOrNull().orEmpty()
        if (prefs.comments) for (a in annots) {
            if (a.subtype in setOf("Link", "Widget", "Popup")) continue
            val text = a.contents?.trim().orEmpty()
            if (text.isEmpty()) continue
            val author = a.cosObject.getString(COSName.T)?.trim().orEmpty()
            val head = if (author.isNotEmpty()) "${labels.comment} ${labels.by} $author" else labels.comment
            out += Extra(Kind.COMMENT, frac(a.rectangle?.upperRightY ?: top), seq++, "$head: $text")
        }
        if (prefs.fields) {
            // constructed directly: getAcroForm() may run the default fix-up (appearance generation)
            val form = (doc.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM) as? COSDictionary)?.let { runCatching { com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(doc, it) }.getOrNull() }
            form?.fieldTree?.forEach { f ->
                val w = f.widgets.firstOrNull { it.page?.cosObject == page.cosObject || (it.page == null && annots.any { a -> a.cosObject == it.cosObject }) } ?: return@forEach
                val name = (f.alternateFieldName ?: f.partialName ?: "").trim()
                val value = when (f) {
                    is PDCheckBox -> if (f.isChecked) labels.checked else labels.unchecked
                    is PDRadioButton -> f.value?.takeIf { it.isNotBlank() && it != "Off" } ?: labels.unchecked
                    is PDButton -> return@forEach      // push buttons have no value
                    else -> runCatching { f.valueAsString }.getOrDefault("").ifBlank { labels.empty }
                }
                out += Extra(Kind.FIELD, frac(w.rectangle?.upperRightY ?: top), seq++, "${labels.field} $name: $value".replace("  ", " "))
            }
        }
        if (prefs.alt) {
            val root = doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("StructTreeRoot")) as? COSDictionary
            if (root != null) walkAlt(root.getDictionaryObject(COSName.K), page.cosObject, null, out, labels, ::frac, intArrayOf(1000), 0)
        }
        return out
    }

    private fun walkAlt(k: COSBase?, page: COSDictionary, inheritedPg: COSDictionary?, out: MutableList<Extra>, labels: Labels,
                        frac: (Float) -> Float, seq: IntArray, depth: Int) {
        if (depth > 60 || out.size > 500) return
        val node = (k as? COSObject)?.`object` ?: k
        when (node) {
            is COSArray -> for (i in 0 until node.size()) walkAlt(node.get(i), page, inheritedPg, out, labels, frac, seq, depth + 1)
            is COSDictionary -> {
                val pg = (node.getDictionaryObject(COSName.getPDFName("Pg")) as? COSDictionary) ?: inheritedPg
                val alt = (node.getDictionaryObject(COSName.getPDFName("Alt")) as? COSString)?.string?.trim()
                if (!alt.isNullOrEmpty() && pg == page) {
                    val s = (node.getDictionaryObject(COSName.S) as? COSName)?.name ?: ""
                    val bbox = ((node.getDictionaryObject(COSName.A) as? COSDictionary)?.getDictionaryObject(COSName.getPDFName("BBox")) as? COSArray)
                    val yTop = (bbox?.getObject(3) as? COSNumber)?.floatValue()
                    val prefix = if (s == "Figure") labels.figure + ": " else ""
                    out += Extra(Kind.ALT, if (yTop != null) frac(yTop) else 1f, seq[0]++, prefix + alt)
                    return            // the alternate text replaces the subtree
                }
                walkAlt(node.getDictionaryObject(COSName.K), page, pg, out, labels, frac, seq, depth + 1)
            }
            else -> {}
        }
    }
}
