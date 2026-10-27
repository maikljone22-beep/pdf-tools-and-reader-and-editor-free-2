package com.smartdocs.editor.core.redact

import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Categories of hidden information that [Sanitizer] finds and removes. */
enum class SanitizeItem {
    METADATA, ATTACHMENTS, COMMENTS, FORMS, JAVASCRIPT, LINKS, BOOKMARKS, PRIVATE_DATA, THUMBNAILS,
    HIDDEN_LAYERS, INVISIBLE_TEXT, HIDDEN_TEXT, CROPPED, REVISIONS, SEARCH_INDEX, STRUCTURE, MULTIMEDIA,
    /** Foxit RHI "overlapping objects": pages where objects lie over each other are turned into images (the screen does it). */
    OVERLAPPING;

    companion object {
        /**
         * Offered by default with redaction: everything except forms, links, the (searchable) OCR layer and the
         * tag structure (it makes the document accessible; the tags of redacted content are removed anyway).
         */
        val DEFAULTS: Set<SanitizeItem> = values().toSet() - setOf(FORMS, LINKS, INVISIBLE_TEXT, STRUCTURE, OVERLAPPING)

        /** Found items that the sanitize screen does not check by itself. */
        val OPT_IN: Set<SanitizeItem> = setOf(STRUCTURE, OVERLAPPING)
    }
}

data class Finding(val count: Int, val details: List<String>)

/** [ocrLayer]: invisible text lies over images, i.e. an OCR layer that keeps a scan searchable. */
data class ScanResult(val findings: Map<SanitizeItem, Finding>, val ocrLayer: Boolean, val revisions: Int)

/**
 * Finds and removes hidden information: metadata (Info, XMP of document, pages and XObjects), attachments,
 * comments, forms (refreshed, flattened, AcroForm/XFA dropped), JavaScript at every level, links and actions,
 * bookmarks, private application data (PieceInfo, SpiderInfo, IDS/URLS), thumbnails, content of hidden
 * layers, invisible text (Tr 3/7), hidden text (covered by a later opaque fill/image, near-white without a dark
 * backdrop, or fully transparent), content outside the crop box, earlier revisions (a full save drops them),
 * embedded search indexes ([SearchIndex]) and the tag structure (StructTreeRoot, MarkInfo, StructParent(s)).
 */
object Sanitizer {
    private val N_AF = COSName.getPDFName("AF")
    private val N_PIECE = COSName.getPDFName("PieceInfo")
    private val N_SPIDER = COSName.getPDFName("SpiderInfo")
    private val N_IDS = COSName.getPDFName("IDS")
    private val N_URLS = COSName.getPDFName("URLS")
    private val N_THUMB = COSName.getPDFName("Thumb")
    private val N_XFA = COSName.getPDFName("XFA")
    private val N_OC = COSName.getPDFName("OC")
    private val N_JS = COSName.getPDFName("JavaScript")
    private val MARKUP = setOf("Text", "FreeText", "Line", "Square", "Circle", "Polygon", "PolyLine", "Highlight", "Underline", "Squiggly",
        "StrikeOut", "Stamp", "Caret", "Ink", "Popup", "Sound")

    // ---------------- scan ----------------

    suspend fun scan(src: File, password: String?): ScanResult = withContext(Dispatchers.IO) {
        val revisions = countEof(src)
        PdfOps.load(src, password).use { doc ->
            val f = LinkedHashMap<SanitizeItem, Finding>()
            fun put(k: SanitizeItem, n: Int, d: List<String>) { if (n > 0) f[k] = Finding(n, d.take(30)) }
            val cat = doc.documentCatalog
            // metadata
            run {
                val info = doc.documentInformation?.cosObject
                val d = ArrayList<String>()
                info?.keySet()?.forEach { k -> d += "${k.name}: ${info.getString(k) ?: ""}".take(80) }
                var n = d.size
                if (cat.cosObject.containsKey(COSName.METADATA)) { n++; d += "XMP" }
                doc.pages.forEachIndexed { pi, p -> if (p.cosObject.containsKey(COSName.METADATA)) { n++; d += "XMP p.${pi + 1}" }; n += xobjectMetadata(p, false) }
                put(SanitizeItem.METADATA, n, d)
            }
            // attachments
            run {
                val names = runCatching { cat.names?.embeddedFiles?.let { collectNames(it.cosObject) } }.getOrNull().orEmpty()
                val d = ArrayList<String>(names)
                doc.pages.forEachIndexed { pi, p -> annots(p).filter { it.subtype == "FileAttachment" }.forEach { d += "p.${pi + 1}: ${it.contents ?: ""}" } }
                val af = if (cat.cosObject.containsKey(N_AF)) 1 else 0
                put(SanitizeItem.ATTACHMENTS, d.size + af, d)
            }
            // comments
            run {
                val d = ArrayList<String>()
                doc.pages.forEachIndexed { pi, p -> annots(p).filter { it.subtype in MARKUP && it.subtype != "Popup" }.forEach { d += "p.${pi + 1} ${it.subtype}: ${(it.contents ?: "").take(60)}" } }
                put(SanitizeItem.COMMENTS, d.size, d)
            }
            // forms
            run {
                val form = cat.acroForm
                val fields = runCatching { form?.fieldTree?.toList() }.getOrNull().orEmpty()
                val d = fields.map { "${it.fullyQualifiedName}: ${runCatching { it.valueAsString }.getOrDefault("")}".take(80) }.toMutableList()
                if (form?.cosObject?.containsKey(N_XFA) == true) d += "XFA"
                put(SanitizeItem.FORMS, d.size, d)
            }
            // javascript
            run {
                val d = ArrayList<String>()
                runCatching { cat.names?.javaScript?.cosObject?.let { d += collectNames(it).map { n -> "JS: $n" } } }
                if (isJs(cat.cosObject.getDictionaryObject(COSName.OPEN_ACTION))) d += "OpenAction"
                if (cat.cosObject.containsKey(COSName.AA)) d += "Document AA"
                doc.pages.forEachIndexed { pi, p ->
                    if (p.cosObject.containsKey(COSName.AA)) d += "p.${pi + 1} AA"
                    annots(p).forEach { a -> if (a.cosObject.containsKey(COSName.AA) || isJs(a.cosObject.getDictionaryObject(COSName.A))) d += "p.${pi + 1} ${a.subtype}" }
                }
                runCatching { cat.acroForm?.fieldTree?.forEach { fl -> if (fl.cosObject.containsKey(COSName.AA)) d += "Field ${fl.fullyQualifiedName}" } }
                put(SanitizeItem.JAVASCRIPT, d.size, d)
            }
            // links and actions
            run {
                val d = ArrayList<String>()
                doc.pages.forEachIndexed { pi, p -> annots(p).forEach { a ->
                    if (a.subtype == "Link") d += "p.${pi + 1} " + linkTarget(a)
                    else if (a.subtype == "Widget" && a.cosObject.containsKey(COSName.A) && !isJs(a.cosObject.getDictionaryObject(COSName.A))) d += "p.${pi + 1} action"
                } }
                val oa = cat.cosObject.getDictionaryObject(COSName.OPEN_ACTION)
                if (oa is COSDictionary && !isJs(oa)) d += "OpenAction"
                put(SanitizeItem.LINKS, d.size, d)
            }
            // bookmarks
            run {
                val d = ArrayList<String>()
                cat.documentOutline?.let { walkOutline(it, d, 0) }
                put(SanitizeItem.BOOKMARKS, d.size, d)
            }
            // private data
            run {
                val d = ArrayList<String>()
                if (cat.cosObject.containsKey(N_PIECE)) d += "PieceInfo"
                if (cat.cosObject.containsKey(N_SPIDER)) d += "SpiderInfo"
                (cat.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary)?.let { n -> if (n.containsKey(N_IDS)) d += "IDS"; if (n.containsKey(N_URLS)) d += "URLS" }
                doc.pages.forEachIndexed { pi, p -> if (p.cosObject.containsKey(N_PIECE)) d += "PieceInfo p.${pi + 1}" }
                put(SanitizeItem.PRIVATE_DATA, d.size, d)
            }
            put(SanitizeItem.THUMBNAILS, doc.pages.count { it.cosObject.containsKey(N_THUMB) }, emptyList())
            SearchIndex.find(doc).let { put(SanitizeItem.SEARCH_INDEX, it.size, it) }
            Multimedia.find(doc).let { put(SanitizeItem.MULTIMEDIA, it.size, it) }
            Structure.count(doc).let { (n, d) -> put(SanitizeItem.STRUCTURE, n, d) }
            // content-level
            var ocr = false
            val oc = cat.ocProperties
            val layers = ArrayList<String>()
            runCatching { oc?.optionalContentGroups?.forEach { g -> if (!oc.isGroupEnabled(g)) layers += g.name ?: "?" } }
            var hiddenLayer = 0; var invisible = 0; var hiddenText = 0; var cropped = 0
            val overlapPages = ArrayList<Int>()
            val hd = ArrayList<String>(); val cd = ArrayList<String>(); val id = ArrayList<String>()
            doc.pages.forEachIndexed { pi, page ->
                val insp = PageInspector(page, oc).run(page)
                val crop = cropBox(page)
                insp.glyphs.forEach { g ->
                    if (g.hidden) hiddenLayer++
                    if (isInvisible(g)) { invisible++; if (insp.paints.any { it.kind == PaintKind.IMAGE && it.box.contains(g.box.cx, g.box.cy) }) ocr = true }
                    else if (!g.hidden && isHiddenText(g, insp)) { hiddenText++; if (hd.size < 30) hd += "p.${pi + 1}: ${g.unicode}" }
                    if (!crop.intersects(g.box)) cropped++
                }
                hiddenLayer += insp.paints.count { it.hidden && !it.nested }
                cropped += insp.paints.count { !it.nested && !crop.intersects(it.box) }
                if (invisible > 0 && id.size < 30 && id.lastOrNull()?.startsWith("p.${pi + 1}") != true) id += "p.${pi + 1}"
                val mb = page.mediaBox
                if (mb.width > crop.w + 1f || mb.height > crop.h + 1f) cd += "p.${pi + 1}"
                if (Overlap.has(insp)) overlapPages += pi
            }
            put(SanitizeItem.HIDDEN_LAYERS, layers.size + hiddenLayer, layers)
            put(SanitizeItem.INVISIBLE_TEXT, invisible, id)
            put(SanitizeItem.HIDDEN_TEXT, hiddenText, mergeChars(hd))
            put(SanitizeItem.CROPPED, cropped + cd.size, cd)
            put(SanitizeItem.OVERLAPPING, overlapPages.size, overlapPages.map { "p.${it + 1}" })
            if (revisions > 1) f[SanitizeItem.REVISIONS] = Finding(revisions - 1, emptyList())
            ScanResult(f, ocr, revisions)
        }
    }

    private fun mergeChars(d: List<String>): List<String> = d.groupBy { it.substringBefore(':') }.map { (k, v) -> k + ": " + v.joinToString("") { it.substringAfter(": ") } }

    private fun countEof(f: File): Int = runCatching {
        val bytes = f.readBytes()
        val pat = "%%EOF".toByteArray()
        var n = 0; var i = 0
        while (i <= bytes.size - pat.size) {
            var ok = true
            for (j in pat.indices) if (bytes[i + j] != pat[j]) { ok = false; break }
            if (ok) { n++; i += pat.size } else i++
        }
        n
    }.getOrDefault(1)

    // ---------------- clean ----------------

    suspend fun cleanFile(src: File, out: File, password: String?, items: Set<SanitizeItem>, context: android.content.Context? = null): Map<SanitizeItem, Int> = withContext(Dispatchers.IO) {
        require(out.canonicalPath != src.canonicalPath) { "out must differ from src" }
        var pages: List<Int> = emptyList()
        val r = PdfOps.load(src, password).use { doc ->
            if (SanitizeItem.OVERLAPPING in items && context != null)
                pages = doc.pages.mapIndexedNotNull { i, p -> i.takeIf { runCatching { Overlap.has(PageInspector(p, doc.documentCatalog.ocProperties).run(p)) }.getOrDefault(false) } }
            val r = clean(doc, items); doc.save(out); r
        }
        if (pages.isEmpty() || context == null) return@withContext r
        val tmp = File(out.absoluteFile.parentFile, ".${out.nameWithoutExtension}.${System.nanoTime()}.ovl.pdf")
        try { out.copyTo(tmp, overwrite = true); RedactApplier.rasterize(context, tmp, out, password, pages, 200) } finally { tmp.delete() }
        r + (SanitizeItem.OVERLAPPING to pages.size)
    }

    /** Cleans [doc] in memory. Returns the number of things removed per item. */
    fun clean(doc: PDDocument, items: Set<SanitizeItem>): Map<SanitizeItem, Int> {
        val r = LinkedHashMap<SanitizeItem, Int>()
        fun add(k: SanitizeItem, n: Int) { if (n > 0) r[k] = (r[k] ?: 0) + n }
        val cat = doc.documentCatalog
        val c = cat.cosObject
        // content first, while layers and the crop box are still known
        val contentItems = items.intersect(setOf(SanitizeItem.HIDDEN_LAYERS, SanitizeItem.INVISIBLE_TEXT, SanitizeItem.HIDDEN_TEXT, SanitizeItem.CROPPED))
        if (contentItems.isNotEmpty()) {
            val oc = cat.ocProperties
            doc.pages.forEach { page ->
                val insp = PageInspector(page, oc).run(page)
                if (insp.failed) return@forEach
                val crop = cropBox(page)
                val plan = RewritePlan()
                var hl = 0; var inv = 0; var ht = 0; var cr = 0
                insp.glyphs.forEach { g ->
                    if (g.nested || g.vertical || g.badSize) return@forEach
                    when {
                        SanitizeItem.HIDDEN_LAYERS in items && g.hidden -> { plan.removeGlyph(g); hl++ }
                        SanitizeItem.INVISIBLE_TEXT in items && isInvisible(g) -> { plan.removeGlyph(g); inv++ }
                        SanitizeItem.HIDDEN_TEXT in items && !isInvisible(g) && isHiddenText(g, insp) -> { plan.removeGlyph(g); ht++ }
                        SanitizeItem.CROPPED in items && !crop.intersects(g.box) -> { plan.removeGlyph(g); cr++ }
                    }
                }
                insp.paints.forEach { p ->
                    if (p.nested) return@forEach
                    val drop = (SanitizeItem.HIDDEN_LAYERS in items && p.hidden).also { if (it) hl++ } ||
                        (SanitizeItem.CROPPED in items && !crop.intersects(p.box) && p.kind != PaintKind.SHADING).also { if (it) cr++ }
                    if (drop) when (p.kind) {
                        PaintKind.FILL, PaintKind.STROKE -> plan.noPaint += p.op
                        else -> plan.removeOps += p.op
                    }
                }
                if (!plan.isEmpty && TrueRedactor.apply(doc, page, insp, plan)) {
                    add(SanitizeItem.HIDDEN_LAYERS, hl); add(SanitizeItem.INVISIBLE_TEXT, inv); add(SanitizeItem.HIDDEN_TEXT, ht); add(SanitizeItem.CROPPED, cr)
                }
                if (SanitizeItem.CROPPED in items) {
                    val cb = page.cropBox
                    if (page.mediaBox.width > cb.width + 1f || page.mediaBox.height > cb.height + 1f) { page.mediaBox = cb; add(SanitizeItem.CROPPED, 1) }
                    listOf("BleedBox", "TrimBox", "ArtBox").forEach { page.cosObject.removeItem(COSName.getPDFName(it)) }
                }
                if (SanitizeItem.HIDDEN_LAYERS in items && oc != null) {
                    val list = annots(page)
                    val keep = list.filterNot { a -> (a.cosObject.getDictionaryObject(N_OC) as? COSDictionary)?.let { d ->
                        val g = com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(d)
                        g is com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup && !oc.isGroupEnabled(g)
                    } == true }
                    if (keep.size != list.size) { add(SanitizeItem.HIDDEN_LAYERS, list.size - keep.size); page.annotations = keep }
                }
            }
            if (SanitizeItem.HIDDEN_LAYERS in items) {
                // what is left is visible: no layer stays switched off
                (cat.ocProperties?.cosObject?.getDictionaryObject(COSName.D) as? COSDictionary)?.setItem(COSName.getPDFName("OFF"), COSArray())
            }
        }
        if (SanitizeItem.METADATA in items) {
            val info = doc.documentInformation?.cosObject?.keySet()?.size ?: 0
            doc.documentInformation = PDDocumentInformation()
            var n = info
            if (c.containsKey(COSName.METADATA)) { c.removeItem(COSName.METADATA); n++ }
            doc.pages.forEach { p -> if (p.cosObject.containsKey(COSName.METADATA)) { p.cosObject.removeItem(COSName.METADATA); n++ }; n += xobjectMetadata(p, true) }
            add(SanitizeItem.METADATA, n)
        }
        if (SanitizeItem.ATTACHMENTS in items) {
            var n = 0
            runCatching { cat.names?.let { names -> names.embeddedFiles?.let { n += collectNames(it.cosObject).size.coerceAtLeast(1); names.setEmbeddedFiles(null) } } }
            if (c.containsKey(N_AF)) { c.removeItem(N_AF); n++ }
            n += removeAnnots(doc) { it.subtype == "FileAttachment" }
            add(SanitizeItem.ATTACHMENTS, n)
        }
        if (SanitizeItem.COMMENTS in items) add(SanitizeItem.COMMENTS, removeAnnots(doc) { it.subtype in MARKUP })
        if (SanitizeItem.JAVASCRIPT in items) {
            var n = 0
            runCatching { cat.names?.let { names -> if (names.javaScript != null) { n++; names.setJavascript(null) } } }
            if (isJs(c.getDictionaryObject(COSName.OPEN_ACTION))) { c.removeItem(COSName.OPEN_ACTION); n++ }
            if (c.containsKey(COSName.AA)) { c.removeItem(COSName.AA); n++ }
            doc.pages.forEach { p ->
                if (p.cosObject.containsKey(COSName.AA)) { p.cosObject.removeItem(COSName.AA); n++ }
                annots(p).forEach { a ->
                    if (a.cosObject.containsKey(COSName.AA)) { a.cosObject.removeItem(COSName.AA); n++ }
                    if (isJs(a.cosObject.getDictionaryObject(COSName.A))) { a.cosObject.removeItem(COSName.A); n++ }
                }
            }
            runCatching { cat.acroForm?.fieldTree?.forEach { fl -> if (fl.cosObject.containsKey(COSName.AA)) { fl.cosObject.removeItem(COSName.AA); n++ } } }
            add(SanitizeItem.JAVASCRIPT, n)
        }
        if (SanitizeItem.LINKS in items) {
            var n = removeAnnots(doc) { it.subtype == "Link" }
            doc.pages.forEach { p -> annots(p).forEach { a -> if (a.subtype == "Widget" && a.cosObject.containsKey(COSName.A)) { a.cosObject.removeItem(COSName.A); n++ } } }
            if (c.containsKey(COSName.OPEN_ACTION)) { c.removeItem(COSName.OPEN_ACTION); n++ }
            add(SanitizeItem.LINKS, n)
        }
        if (SanitizeItem.FORMS in items) {
            cat.acroForm?.let { form ->
                val n = runCatching { form.fieldTree.count() }.getOrDefault(1).coerceAtLeast(1)
                form.cosObject.removeItem(N_XFA)
                runCatching { form.refreshAppearances() }
                runCatching { form.flatten() }
                c.removeItem(COSName.ACRO_FORM)
                removeAnnots(doc) { it.subtype == "Widget" }
                add(SanitizeItem.FORMS, n)
            }
        }
        if (SanitizeItem.BOOKMARKS in items) {
            cat.documentOutline?.let { o -> val d = ArrayList<String>(); walkOutline(o, d, 0); cat.documentOutline = null; add(SanitizeItem.BOOKMARKS, d.size.coerceAtLeast(1)) }
        }
        if (SanitizeItem.SEARCH_INDEX in items) add(SanitizeItem.SEARCH_INDEX, SearchIndex.remove(doc))
        if (SanitizeItem.MULTIMEDIA in items) add(SanitizeItem.MULTIMEDIA, Multimedia.remove(doc))
        if (SanitizeItem.STRUCTURE in items) add(SanitizeItem.STRUCTURE, Structure.remove(doc))
        if (SanitizeItem.PRIVATE_DATA in items) {
            var n = 0
            listOf(N_PIECE, N_SPIDER).forEach { k -> if (c.containsKey(k)) { c.removeItem(k); n++ } }
            (c.getDictionaryObject(COSName.NAMES) as? COSDictionary)?.let { nd -> listOf(N_IDS, N_URLS).forEach { k -> if (nd.containsKey(k)) { nd.removeItem(k); n++ } } }
            doc.pages.forEach { p -> if (p.cosObject.containsKey(N_PIECE)) { p.cosObject.removeItem(N_PIECE); n++ } }
            add(SanitizeItem.PRIVATE_DATA, n)
        }
        if (SanitizeItem.THUMBNAILS in items) {
            var n = 0
            doc.pages.forEach { p -> if (p.cosObject.containsKey(N_THUMB)) { p.cosObject.removeItem(N_THUMB); n++ } }
            add(SanitizeItem.THUMBNAILS, n)
        }
        if (SanitizeItem.REVISIONS in items) r[SanitizeItem.REVISIONS] = r[SanitizeItem.REVISIONS] ?: 0   // a full save drops them
        return r
    }

    // ---------------- helpers ----------------

    internal fun isInvisible(g: GlyphProbe) = g.mode == RenderingMode.NEITHER || g.mode == RenderingMode.NEITHER_CLIP

    private fun luminance(c: Int): Float = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f

    /** Covered by a later opaque fill/image, transparent, or near-white with no darker paint beneath it. */
    internal fun isHiddenText(g: GlyphProbe, insp: PageInspector): Boolean {
        if (g.alpha <= 0.01f) return true
        val cx = g.box.cx; val cy = g.box.cy
        val coveredLater = insp.paints.any { p ->
            p.order > g.order && p.opaque && !p.hidden && (p.kind == PaintKind.IMAGE || (p.kind == PaintKind.FILL && p.rectOnly)) &&
                p.box.encloses(g.box, 0.5f)
        }
        if (coveredLater) return true
        if (luminance(g.fill) >= 0.94f) {
            val backdrop = insp.paints.any { p -> p.order < g.order && !p.hidden && p.box.contains(cx, cy) &&
                (p.kind == PaintKind.IMAGE || p.kind == PaintKind.INLINE_IMAGE || p.kind == PaintKind.SHADING || p.kind == PaintKind.FORM ||
                    (p.kind == PaintKind.FILL && luminance(p.color) < 0.8f)) }
            return !backdrop
        }
        return false
    }

    private fun cropBox(p: PDPage) = p.cropBox.let { Box(it.lowerLeftX, it.lowerLeftY, it.upperRightX, it.upperRightY) }

    private fun annots(p: PDPage): List<PDAnnotation> = runCatching { p.annotations }.getOrNull().orEmpty()

    private fun removeAnnots(doc: PDDocument, pred: (PDAnnotation) -> Boolean): Int {
        var n = 0
        doc.pages.forEach { p ->
            val list = annots(p)
            val gone = list.filter(pred).map { it.cosObject }.toSet()
            if (gone.isEmpty()) return@forEach
            val keep = list.filter { a -> a.cosObject !in gone && !(a.subtype == "Popup" && (a.cosObject.getDictionaryObject(COSName.PARENT) as? COSDictionary)?.let { it in gone } == true) }
            n += gone.size
            p.annotations = keep
        }
        return n
    }

    /** True when the action (or one in its /Next chain) is JavaScript. */
    private fun isJs(a: COSBase?, depth: Int = 0): Boolean {
        if (depth > 20) return false
        val d = (a as? COSDictionary) ?: return false
        if (d.getCOSName(COSName.S) == N_JS) return true
        return when (val next = d.getDictionaryObject(COSName.NEXT)) {
            is COSDictionary -> isJs(next, depth + 1)
            is COSArray -> (0 until next.size()).any { isJs(next.getObject(it), depth + 1) }
            else -> false
        }
    }

    private fun linkTarget(a: PDAnnotation): String {
        val act = a.cosObject.getDictionaryObject(COSName.A) as? COSDictionary ?: return "link"
        return act.getString(COSName.URI) ?: act.getCOSName(COSName.S)?.name ?: "link"
    }

    private fun walkOutline(node: PDOutlineNode, out: MutableList<String>, depth: Int) {
        if (depth > 30 || out.size > 2000) return
        var it = node.firstChild
        var guard = 0
        while (it != null && guard++ < 5000) { out += "  ".repeat(depth) + (it.title ?: ""); walkOutline(it, out, depth + 1); it = it.nextSibling }
    }

    internal fun collectNames(node: COSDictionary, depth: Int = 0): List<String> {
        if (depth > 20) return emptyList()
        val out = ArrayList<String>()
        (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { arr ->
            var i = 0
            while (i < arr.size()) { (arr.getObject(i) as? com.tom_roush.pdfbox.cos.COSString)?.let { out += it.string }; i += 2 }
        }
        (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids -> for (i in 0 until kids.size()) (kids.getObject(i) as? COSDictionary)?.let { out += collectNames(it, depth + 1) } }
        return out
    }

    /** Counts (and with [remove], deletes) /Metadata of the page's image and form XObjects. */
    private fun xobjectMetadata(p: PDPage, remove: Boolean): Int {
        val res = p.resources ?: return 0
        var n = 0
        runCatching {
            res.xObjectNames.forEach { name ->
                val x = (res.cosObject.getDictionaryObject(COSName.XOBJECT) as? COSDictionary)?.getDictionaryObject(name) as? COSDictionary ?: return@forEach
                if (x.containsKey(COSName.METADATA)) { n++; if (remove) x.removeItem(COSName.METADATA) }
            }
        }
        return n
    }
}
