package com.smartdocs.editor.core.sign

import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import java.io.File

/**
 * Static pre-sign analysis (no script is executed): lists everything in the document that can still change
 * what the signer sees after signing — document/page/field JavaScript and their trigger events, named page
 * templates (spawnable pages), XFA, optional content layers, unsigned signature fields and empty fillable
 * fields — plus the existing signature / certification (DocMDP) state that limits which signature types
 * are still possible.
 */
object PreSignAnalyzer {

    enum class Kind { DOC_SCRIPT, PAGE_SCRIPT, FIELD_SCRIPT, LINK_SCRIPT, PAGE_TEMPLATE, XFA, LAYERS, UNSIGNED_FIELD, EMPTY_FIELDS, EXISTING_SIGNATURE, CERTIFIED }

    /** One finding. [event] is the PDF trigger key (OpenAction, WC, WS, DS, WP, DP, O, C, K, F, V, E, X, D, U, Fo, Bl, PO, PC, PV, PI, Names). */
    data class Item(val kind: Kind, val target: String, val event: String = "", val page: Int = -1, val snippet: String = "", val count: Int = 0)

    data class Report(
        val items: List<Item>,
        val signatureCount: Int,
        /** DocMDP permission level of an existing certification signature (1, 2, 3) or null. */
        val certifiedP: Int?,
        val unsignedFields: List<String>,
        val emptyFillable: Int,
    ) {
        /** A certification signature must be the first signature of the document. */
        val canCertify: Boolean get() = signatureCount == 0
        /** Adding any signature is forbidden by an existing P=1 certification. */
        val signingLocked: Boolean get() = certifiedP == 1
        val hasActiveContent: Boolean get() = items.any { it.kind in setOf(Kind.DOC_SCRIPT, Kind.PAGE_SCRIPT, Kind.FIELD_SCRIPT, Kind.LINK_SCRIPT, Kind.PAGE_TEMPLATE, Kind.XFA) }
    }

    private val docEvents = listOf("WC", "WS", "DS", "WP", "DP")
    private val pageEvents = listOf("O", "C")
    private val fieldEvents = listOf("K", "F", "V", "C")
    private val widgetEvents = listOf("E", "X", "D", "U", "Fo", "Bl", "PO", "PC", "PV", "PI")

    private fun jsOf(action: COSBase?): String? {
        val d = (action as? COSDictionary) ?: return null
        val s = d.getCOSName(COSName.S)?.name
        if (s != "JavaScript") return null
        return when (val js = d.getDictionaryObject(COSName.JS)) {
            is COSString -> js.string
            is COSStream -> runCatching { js.toTextString() }.getOrNull() ?: ""
            else -> ""
        }
    }

    private fun snippet(js: String) = js.replace(Regex("\\s+"), " ").trim().take(140)

    /** Walk a name tree (Names/Kids) and return its keys. */
    private fun nameTreeKeys(node: COSDictionary?, depth: Int = 0): List<String> {
        if (node == null || depth > 20) return emptyList()
        val out = ArrayList<String>()
        (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { arr ->
            var i = 0
            while (i < arr.size()) { (arr.getObject(i) as? COSString)?.let { out += it.string }; i += 2 }
        }
        (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids ->
            for (j in 0 until kids.size()) out += nameTreeKeys(kids.getObject(j) as? COSDictionary, depth + 1)
        }
        return out
    }

    /** Values (JavaScript actions) of the document-level /Names /JavaScript tree. */
    private fun nameTreeJs(node: COSDictionary?, out: MutableList<Pair<String, String>>, depth: Int = 0) {
        if (node == null || depth > 20) return
        (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { arr ->
            var i = 0
            while (i + 1 < arr.size()) {
                val key = (arr.getObject(i) as? COSString)?.string ?: ""
                jsOf(arr.getObject(i + 1))?.let { out += key to it }
                i += 2
            }
        }
        (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids ->
            for (j in 0 until kids.size()) nameTreeJs(kids.getObject(j) as? COSDictionary, out, depth + 1)
        }
    }

    suspend fun analyze(file: File, password: String? = null): Report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        PdfOps.load(file, password).use { doc -> analyze(doc) }
    }

    fun analyze(doc: PDDocument): Report {
        val items = ArrayList<Item>()
        val catalog = doc.documentCatalog.cosObject

        // --- document level
        jsOf(catalog.getDictionaryObject(COSName.OPEN_ACTION))?.let { items += Item(Kind.DOC_SCRIPT, "", "OpenAction", snippet = snippet(it)) }
        (catalog.getDictionaryObject(COSName.AA) as? COSDictionary)?.let { aa ->
            docEvents.forEach { ev -> jsOf(aa.getDictionaryObject(COSName.getPDFName(ev)))?.let { items += Item(Kind.DOC_SCRIPT, "", ev, snippet = snippet(it)) } }
        }
        val names = catalog.getDictionaryObject(COSName.NAMES) as? COSDictionary
        if (names != null) {
            val js = ArrayList<Pair<String, String>>()
            nameTreeJs(names.getDictionaryObject(COSName.JAVA_SCRIPT) as? COSDictionary, js)
            js.forEach { (k, v) -> items += Item(Kind.DOC_SCRIPT, k, "Names", snippet = snippet(v)) }
            val templates = nameTreeKeys(names.getDictionaryObject(COSName.TEMPLATES) as? COSDictionary)
            templates.forEach { items += Item(Kind.PAGE_TEMPLATE, it, "Templates") }
            val namedPages = nameTreeKeys(names.getDictionaryObject(COSName.PAGES) as? COSDictionary)
            namedPages.forEach { items += Item(Kind.PAGE_TEMPLATE, it, "Pages") }
        }
        if (catalog.getDictionaryObject(COSName.OCPROPERTIES) is COSDictionary) items += Item(Kind.LAYERS, "")

        // --- pages and link annotations
        doc.pages.forEachIndexed { pi, page ->
            (page.cosObject.getDictionaryObject(COSName.AA) as? COSDictionary)?.let { aa ->
                pageEvents.forEach { ev -> jsOf(aa.getDictionaryObject(COSName.getPDFName(ev)))?.let { items += Item(Kind.PAGE_SCRIPT, "", ev, pi, snippet(it)) } }
            }
            runCatching { page.annotations }.getOrNull()?.forEach { a ->
                if (a.subtype == "Link") jsOf(a.cosObject.getDictionaryObject(COSName.A))?.let { items += Item(Kind.LINK_SCRIPT, "", "U", pi, snippet(it)) }
            }
        }

        // --- form
        val acro = doc.documentCatalog.acroForm
        val unsigned = ArrayList<String>()
        var empty = 0
        var signatures = 0
        if (acro != null) {
            if (acro.hasXFA()) items += Item(Kind.XFA, if (acro.xfaIsDynamic()) "dynamic" else "static")
            for (f in acro.fieldTree) {
                val name = f.fullyQualifiedName ?: f.partialName ?: ""
                (f.cosObject.getDictionaryObject(COSName.AA) as? COSDictionary)?.let { aa ->
                    fieldEvents.forEach { ev -> jsOf(aa.getDictionaryObject(COSName.getPDFName(ev)))?.let { items += Item(Kind.FIELD_SCRIPT, name, ev, -1, snippet(it)) } }
                }
                if (f !is PDTerminalField) continue
                f.widgets.forEach { w ->
                    val pageIdx = w.page?.let { doc.pages.indexOf(it) } ?: -1
                    (w.cosObject.getDictionaryObject(COSName.AA) as? COSDictionary)?.let { aa ->
                        widgetEvents.forEach { ev -> jsOf(aa.getDictionaryObject(COSName.getPDFName(ev)))?.let { items += Item(Kind.FIELD_SCRIPT, name, ev, pageIdx, snippet(it)) } }
                    }
                    if (w.cosObject !== f.cosObject) jsOf(w.cosObject.getDictionaryObject(COSName.A))?.let { items += Item(Kind.FIELD_SCRIPT, name, "U", pageIdx, snippet(it)) }
                }
                when {
                    f is PDSignatureField -> if (f.signature == null) unsigned += name else signatures++
                    f.isReadOnly -> Unit
                    f is PDPushButton -> Unit
                    (runCatching { f.valueAsString }.getOrNull() ?: "").let { it.isBlank() || it == "Off" } -> empty++
                }
            }
        }
        signatures = maxOf(signatures, runCatching { doc.signatureDictionaries.size }.getOrDefault(0))
        unsigned.forEach { items += Item(Kind.UNSIGNED_FIELD, it) }
        if (empty > 0) items += Item(Kind.EMPTY_FIELDS, "", count = empty)
        if (signatures > 0) items += Item(Kind.EXISTING_SIGNATURE, "", count = signatures)

        // --- existing certification (catalog /Perms /DocMDP → sig /Reference[0] /TransformParams /P)
        val certP = runCatching {
            val perms = catalog.getDictionaryObject(COSName.PERMS) as? COSDictionary ?: return@runCatching null
            val sig = perms.getDictionaryObject(COSName.DOCMDP) as? COSDictionary ?: return@runCatching null
            val ref = (sig.getDictionaryObject(COSName.REFERENCE) as? COSArray)?.getObject(0) as? COSDictionary ?: return@runCatching 2
            val tp = ref.getDictionaryObject(COSName.TRANSFORM_PARAMS) as? COSDictionary ?: return@runCatching 2
            tp.getInt(COSName.P, 2)
        }.getOrNull()
        if (certP != null) items += Item(Kind.CERTIFIED, "", count = certP)

        return Report(items, signatures, certP, unsigned, empty)
    }
}
