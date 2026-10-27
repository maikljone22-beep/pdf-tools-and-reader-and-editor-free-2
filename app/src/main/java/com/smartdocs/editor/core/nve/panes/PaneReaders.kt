package com.smartdocs.editor.core.nve.panes

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDObjectReference
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode
import java.util.Calendar

/** One attached file: document level ([page] null, key = name-tree name) or a file-attachment annotation. */
data class AttachmentInfo(
    val key: String,
    val name: String,
    val size: Long?,
    val description: String,
    val modified: Long?,
    val subtype: String,
    /** Page index of a file-attachment annotation, null for a document-level attachment. */
    val page: Int?,
    /** Index of the annotation in the page's /Annots (annotation attachments only). */
    val annotIndex: Int = -1,
)

/** One node of the logical structure tree. */
data class StructNode(val type: String, val title: String, val alt: String, val actual: String, val page: Int?, val children: List<StructNode>)

/** One AcroForm terminal field. */
data class FieldInfo(val name: String, val type: String, val value: String, val page: Int?, val readOnly: Boolean, val required: Boolean)

/** One named destination. [source] "names" (catalog /Names /Dests tree) or "dests" (catalog /Dests dictionary). */
data class DestInfo(val name: String, val page: Int?, val kind: String, val source: String)

/** One signature field (signed or not); validity comes from the existing verifier (DigitalSignature.verify). */
data class SigFieldInfo(
    val name: String, val signed: Boolean, val signer: String, val date: Long?, val reason: String, val location: String,
    val page: Int?, val subFilter: String,
)

/**
 * Read-only readers behind the reader panes (attachments, tags, fields, destinations, signatures) plus the few
 * named-destination edits. Pure PDFBox: no Android classes, so the JVM tests can run them.
 */
object PaneReaders {
    private const val MAX_NODES = 4000

    private fun pageIndex(doc: PDDocument, page: PDPage?): Int? {
        page ?: return null
        val i = doc.pages.indexOf(page)
        return if (i >= 0) i else null
    }

    private fun pageIndex(doc: PDDocument, dict: COSDictionary?): Int? {
        dict ?: return null
        var i = 0
        for (p in doc.pages) { if (p.cosObject === dict) return i; i++ }
        return null
    }

    // ---------------------------------------------------------------- attachments

    private fun <T : com.tom_roush.pdfbox.pdmodel.common.COSObjectable> walk(node: PDNameTreeNode<T>, out: MutableMap<String, T>, depth: Int = 0) {
        if (depth > 32) return
        runCatching { node.names }.getOrNull()?.let { out.putAll(it) }
        runCatching { node.kids }.getOrNull()?.forEach { walk(it, out, depth + 1) }
    }

    private fun embedded(spec: PDComplexFileSpecification): PDEmbeddedFile? =
        spec.embeddedFileUnicode ?: spec.embeddedFile ?: spec.embeddedFileDos ?: spec.embeddedFileMac ?: spec.embeddedFileUnix

    private fun fileName(spec: PDComplexFileSpecification, fallback: String): String =
        (spec.fileUnicode ?: spec.filename ?: spec.file)?.takeIf { it.isNotBlank() }?.substringAfterLast('/')?.substringAfterLast('\\') ?: fallback

    private fun info(key: String, spec: PDComplexFileSpecification, page: Int?, annotIndex: Int): AttachmentInfo {
        val ef = embedded(spec)
        val size = ef?.let { e -> e.size.takeIf { it > 0 }?.toLong() ?: runCatching { e.length.toLong() }.getOrNull() }
        return AttachmentInfo(
            key = key, name = fileName(spec, key), size = size, description = spec.fileDescription ?: "",
            modified = runCatching { (ef?.modDate as Calendar?)?.timeInMillis }.getOrNull(), subtype = ef?.subtype ?: "",
            page = page, annotIndex = annotIndex,
        )
    }

    /** Document-level embedded files (name tree) and file-attachment annotations of every page. */
    fun attachments(doc: PDDocument): List<AttachmentInfo> {
        val out = ArrayList<AttachmentInfo>()
        val tree = doc.documentCatalog.names?.embeddedFiles
        if (tree != null) {
            val map = LinkedHashMap<String, PDComplexFileSpecification>()
            walk(tree, map)
            map.forEach { (k, spec) -> out += info(k, spec, null, -1) }
        }
        doc.pages.forEachIndexed { pi, p ->
            runCatching { p.annotations }.getOrNull()?.forEachIndexed { ai, a ->
                if (a is PDAnnotationFileAttachment) {
                    val spec = runCatching { a.file }.getOrNull() as? PDComplexFileSpecification ?: return@forEachIndexed
                    out += info("p$pi#$ai", spec, pi, ai)
                }
            }
        }
        return out
    }

    /** Bytes of the attachment [a] (as listed by [attachments]), or null. */
    fun attachmentBytes(doc: PDDocument, a: AttachmentInfo): ByteArray? {
        val spec: PDComplexFileSpecification? = if (a.page == null) {
            val map = LinkedHashMap<String, PDComplexFileSpecification>()
            doc.documentCatalog.names?.embeddedFiles?.let { walk(it, map) }
            map[a.key]
        } else {
            val annot = runCatching { doc.getPage(a.page).annotations.getOrNull(a.annotIndex) }.getOrNull() as? PDAnnotationFileAttachment
            runCatching { annot?.file }.getOrNull() as? PDComplexFileSpecification
        }
        return spec?.let { embedded(it) }?.let { runCatching { it.toByteArray() }.getOrNull() }
    }

    /** Catalog /Collection (a PDF portfolio). */
    fun isPortfolio(doc: PDDocument): Boolean = doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("Collection")) != null

    // ---------------------------------------------------------------- structure tree

    /** The structure tree, or null when the document is not tagged. */
    fun structTree(doc: PDDocument): List<StructNode>? {
        val root = doc.documentCatalog.structureTreeRoot ?: return null
        val count = intArrayOf(0)
        val kids = runCatching { root.kids }.getOrNull() ?: return emptyList()
        return kids.mapNotNull { (it as? PDStructureElement)?.let { e -> node(doc, e, count, 0) } }
    }

    private fun node(doc: PDDocument, e: PDStructureElement, count: IntArray, depth: Int): StructNode? {
        if (count[0]++ > MAX_NODES || depth > 64) return null
        var page = pageIndex(doc, runCatching { e.page }.getOrNull())
        val children = ArrayList<StructNode>()
        runCatching { e.kids }.getOrNull()?.forEach { k ->
            when (k) {
                is PDStructureElement -> node(doc, k, count, depth + 1)?.let { children += it; if (page == null) page = it.page }
                is PDMarkedContentReference -> if (page == null) page = pageIndex(doc, runCatching { k.page }.getOrNull())
                is PDObjectReference -> if (page == null) {
                    val ref = runCatching { k.referencedObject }.getOrNull()
                    val pd = (ref as? com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation)?.page
                    page = pageIndex(doc, pd)
                }
            }
        }
        return StructNode(e.structureType ?: "", e.title ?: "", e.alternateDescription ?: "", e.actualText ?: "", page, children)
    }

    // ---------------------------------------------------------------- fields

    private fun widgetPage(doc: PDDocument, w: PDAnnotationWidget, cache: Map<COSDictionary, Int>): Int? =
        pageIndex(doc, runCatching { w.page }.getOrNull()) ?: cache[w.cosObject]

    private fun widgetPages(doc: PDDocument): Map<COSDictionary, Int> {
        val m = HashMap<COSDictionary, Int>()
        doc.pages.forEachIndexed { i, p -> runCatching { p.annotations }.getOrNull()?.forEach { a -> m[a.cosObject] = i } }
        return m
    }

    /**
     * The AcroForm WITHOUT PDFBox's default fixup (that fixup builds default resources with standard-14 fonts and
     * regenerates appearances: a read-only pane must never do that).
     */
    fun acroForm(doc: PDDocument): com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm? = doc.documentCatalog.getAcroForm(null)

    /** Field value from the COS /V (inherited through /Parent), never through the value / appearance API. */
    private fun inherited(d: COSDictionary, key: COSName): COSBase? {
        var cur: COSDictionary? = d; var depth = 0
        while (cur != null && depth++ < 32) {
            cur.getDictionaryObject(key)?.let { return it }
            cur = cur.getDictionaryObject(COSName.PARENT) as? COSDictionary
        }
        return null
    }

    private fun cosText(b: COSBase?): String = when (b) {
        is com.tom_roush.pdfbox.cos.COSString -> b.string
        is COSName -> b.name
        is COSArray -> (0 until b.size()).mapNotNull { (b.getObject(it) as? com.tom_roush.pdfbox.cos.COSString)?.string }.joinToString(", ")
        else -> ""
    }

    /** Terminal AcroForm fields in field-tree order. */
    fun fields(doc: PDDocument): List<FieldInfo> {
        val form = acroForm(doc) ?: return emptyList()
        val cache = widgetPages(doc)
        val out = ArrayList<FieldInfo>()
        for (f in form.fieldTree) {
            if (f is PDNonTerminalField) continue
            val type = when (f) {
                is PDCheckBox -> "checkbox"; is PDRadioButton -> "radio"; is PDSignatureField -> "signature"
                else -> when (f.fieldType) { "Tx" -> "text"; "Ch" -> "choice"; "Btn" -> "button"; "Sig" -> "signature"; else -> f.fieldType ?: "" }
            }
            val value = when (f) {
                is PDCheckBox, is PDRadioButton -> {
                    val v = inherited(f.cosObject, COSName.V) as? COSName
                    if (f is PDCheckBox) (if (v != null && v.name != "Off") "☑" else "☐") else v?.name?.takeIf { it != "Off" } ?: ""
                }
                else -> cosText(inherited(f.cosObject, COSName.V))
            }
            val page = f.widgets.firstNotNullOfOrNull { widgetPage(doc, it, cache) }
            out += FieldInfo(f.fullyQualifiedName ?: "", type, value, page, f.isReadOnly, f.isRequired)
        }
        return out
    }

    /** Signature fields with their signature dictionary values (no cryptographic check here). */
    fun signatureFields(doc: PDDocument): List<SigFieldInfo> {
        val form = acroForm(doc) ?: return emptyList()
        val cache = widgetPages(doc)
        return form.fieldTree.filterIsInstance<PDSignatureField>().map { f ->
            val s = f.signature
            SigFieldInfo(
                name = f.fullyQualifiedName ?: "", signed = s != null, signer = s?.name ?: "", date = runCatching { s?.signDate?.timeInMillis }.getOrNull(),
                reason = s?.reason ?: "", location = s?.location ?: "", page = f.widgets.firstNotNullOfOrNull { widgetPage(doc, it, cache) },
                subFilter = s?.subFilter ?: "",
            )
        }
    }

    /** DocMDP level of the certification signature (1-3), or null. */
    fun certificationLevel(doc: PDDocument): Int? {
        val perms = doc.documentCatalog.cosObject.getCOSDictionary(COSName.getPDFName("Perms")) ?: return null
        val sig = perms.getCOSDictionary(COSName.getPDFName("DocMDP")) ?: return null
        val refs = sig.getDictionaryObject(COSName.getPDFName("Reference")) as? COSArray ?: return 2
        for (i in 0 until refs.size()) {
            val r = refs.getObject(i) as? COSDictionary ?: continue
            val tp = r.getCOSDictionary(COSName.getPDFName("TransformParams")) ?: continue
            return tp.getInt(COSName.P, 2)
        }
        return 2
    }

    // ---------------------------------------------------------------- named destinations

    private fun kindOf(d: PDDestination?): String = (d as? PDPageDestination)?.cosObject?.let { a -> (a.getObject(1) as? COSName)?.name } ?: ""

    private fun destPage(doc: PDDocument, d: PDDestination?): Int? {
        val pd = d as? PDPageDestination ?: return null
        pageIndex(doc, runCatching { pd.page }.getOrNull())?.let { return it }
        val n = runCatching { pd.pageNumber }.getOrDefault(-1)
        return if (n >= 0 && n < doc.numberOfPages) n else null
    }

    private fun namesTreeMap(doc: PDDocument): LinkedHashMap<String, PDPageDestination> {
        val map = LinkedHashMap<String, PDPageDestination>()
        doc.documentCatalog.names?.dests?.let { walk(it, map) }
        return map
    }

    private fun destsDict(doc: PDDocument): COSDictionary? = doc.documentCatalog.cosObject.getCOSDictionary(COSName.DESTS)

    private fun destFromBase(b: COSBase?): PDDestination? {
        var x = b
        if (x is COSObject) x = x.getObject()
        if (x is COSDictionary) x = x.getDictionaryObject(COSName.D)
        return runCatching { PDDestination.create(x) }.getOrNull()
    }

    /** Every named destination (name tree first, then the PDF 1.1 /Dests dictionary). */
    fun namedDests(doc: PDDocument): List<DestInfo> {
        val out = ArrayList<DestInfo>()
        namesTreeMap(doc).forEach { (k, d) -> out += DestInfo(k, destPage(doc, d), kindOf(d), "names") }
        destsDict(doc)?.let { dict ->
            for (k in dict.keySet()) { val d = destFromBase(dict.getDictionaryObject(k)); out += DestInfo(k.name, destPage(doc, d), kindOf(d), "dests") }
        }
        return out
    }

    private fun writeNamesTree(doc: PDDocument, map: Map<String, PDPageDestination>) {
        val cat = doc.documentCatalog
        val names = cat.names ?: PDDocumentNameDictionary(cat).also { cat.names = it }
        // PDDocumentNameDictionary.setDests clears the catalog /Dests dictionary (PDF 1.1 store): keep it
        val legacy = cat.cosObject.getItem(COSName.DESTS)
        if (map.isEmpty()) names.dests = null
        else {
            val node = com.tom_roush.pdfbox.pdmodel.PDDestinationNameTreeNode()
            node.names = java.util.TreeMap(map)
            names.dests = node
        }
        if (legacy != null) cat.cosObject.setItem(COSName.DESTS, legacy)
    }

    /** Rename destination [old] to [new] in whichever store holds it. False when [old] is missing or [new] exists. */
    fun renameDest(doc: PDDocument, old: String, new: String): Boolean {
        if (new.isBlank() || old == new) return false
        if (namedDests(doc).any { it.name == new }) return false
        val map = namesTreeMap(doc)
        map.remove(old)?.let { d -> map[new] = d; writeNamesTree(doc, map); return true }
        val dict = destsDict(doc) ?: return false
        val v = dict.getItem(COSName.getPDFName(old)) ?: return false
        dict.removeItem(COSName.getPDFName(old)); dict.setItem(COSName.getPDFName(new), v)
        return true
    }

    /** Delete destination [name]. */
    fun deleteDest(doc: PDDocument, name: String): Boolean {
        val map = namesTreeMap(doc)
        if (map.remove(name) != null) { writeNamesTree(doc, map); return true }
        val dict = destsDict(doc) ?: return false
        if (dict.getItem(COSName.getPDFName(name)) == null) return false
        dict.removeItem(COSName.getPDFName(name)); return true
    }

    /** Add an /XYZ destination [name] to page [page] (top in PDF user space, zoom 0 = keep). */
    fun addDest(doc: PDDocument, name: String, page: Int, top: Int? = null, zoom: Float = 0f): Boolean {
        if (name.isBlank() || page !in 0 until doc.numberOfPages) return false
        if (namedDests(doc).any { it.name == name }) return false
        val d = PDPageXYZDestination()
        val p = doc.getPage(page)
        d.page = p
        d.top = top ?: p.mediaBox.upperRightY.toInt()
        d.left = p.mediaBox.lowerLeftX.toInt()
        d.zoom = zoom
        val map = namesTreeMap(doc); map[name] = d
        writeNamesTree(doc, map)
        return true
    }
}
