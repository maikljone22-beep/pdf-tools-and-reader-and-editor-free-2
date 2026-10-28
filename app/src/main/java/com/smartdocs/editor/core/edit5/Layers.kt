package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem

/** Usage state of a layer for an event (Foxit / Nitro "Visibility / Print / Export: when visible, never, always"). */
enum class LayerUse { WHEN_ON, NEVER, ALWAYS }

/** One optional content group as the layers panel shows it. [depth] / [group] come from the /Order tree. */
data class LayerRow(
    val index: Int, val name: String, val on: Boolean, val initialOn: Boolean, val locked: Boolean,
    val intent: String, val view: LayerUse, val print: LayerUse, val export: LayerUse,
    val depth: Int, val group: String?, val pages: List<Int>,
)

/**
 * Nitro 18 / Foxit 22 layers on the COS level: list with order tree, pages, initial state, lock, intent and usage
 * (view / print / export with /AS auto-states); show / hide, reset, rename, reorder, merge, delete, flatten, new
 * layer, import as layer, and a bookmark that sets layer states. Pure over [PDDocument] — JVM-tested.
 */
object Layers {
    private val OCP = COSName.getPDFName("OCProperties")
    private val OCGS = COSName.getPDFName("OCGs")
    private val ORDER = COSName.getPDFName("Order")
    private val ON = COSName.getPDFName("ON"); private val OFF = COSName.getPDFName("OFF")
    private val LOCKED = COSName.getPDFName("Locked"); private val AS = COSName.getPDFName("AS")
    private val USAGE = COSName.getPDFName("Usage"); private val INTENT = COSName.getPDFName("Intent")
    private val OC = COSName.getPDFName("OC")

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b
    private fun props(doc: PDDocument): COSDictionary? = deref(doc.documentCatalog.cosObject.getDictionaryObject(OCP)) as? COSDictionary
    private fun ocgs(doc: PDDocument): List<COSDictionary> = (deref(props(doc)?.getDictionaryObject(OCGS)) as? COSArray)?.mapNotNull { deref(it) as? COSDictionary }.orEmpty()
    private fun d(doc: PDDocument): COSDictionary? = deref(props(doc)?.getDictionaryObject(COSName.D)) as? COSDictionary
    private fun arr(d: COSDictionary, k: COSName): COSArray = (deref(d.getDictionaryObject(k)) as? COSArray) ?: COSArray().also { d.setItem(k, it) }
    private fun has(a: COSArray?, g: COSDictionary) = a != null && (0 until a.size()).any { deref(a.get(it)) === g }
    private fun drop(a: COSArray?, g: COSDictionary) { if (a != null) for (k in a.size() - 1 downTo 0) if (deref(a.get(k)) === g) a.remove(k) }

    private fun state(doc: PDDocument, g: COSDictionary): Boolean {
        val dd = d(doc) ?: return true
        val baseOff = dd.getNameAsString(COSName.getPDFName("BaseState")) == "OFF"
        return if (baseOff) has(deref(dd.getDictionaryObject(ON)) as? COSArray, g) else !has(deref(dd.getDictionaryObject(OFF)) as? COSArray, g)
    }

    private fun use(g: COSDictionary, event: String, key: String): LayerUse {
        val u = deref(deref(g.getDictionaryObject(USAGE)).let { it as? COSDictionary }?.getDictionaryObject(COSName.getPDFName(event))) as? COSDictionary ?: return LayerUse.WHEN_ON
        return when (u.getNameAsString(COSName.getPDFName(key))) { "ON" -> LayerUse.ALWAYS; "OFF" -> LayerUse.NEVER; else -> LayerUse.WHEN_ON }
    }

    /** Pages (zero-based) whose resources, annotations or XObjects use each OCG. */
    fun pagesOf(doc: PDDocument): Map<COSDictionary, List<Int>> {
        val out = HashMap<COSDictionary, MutableList<Int>>()
        fun mark(b: COSBase?, p: Int, depth: Int = 0) {
            val g = deref(b) as? COSDictionary ?: return
            when (g.getNameAsString(COSName.TYPE)) {
                "OCG" -> out.getOrPut(g) { ArrayList() }.let { if (it.lastOrNull() != p) it += p }
                "OCMD" -> if (depth < 4) { (deref(g.getDictionaryObject(COSName.getPDFName("OCGs")))).let { x -> if (x is COSArray) x.forEach { mark(it, p, depth + 1) } else mark(x, p, depth + 1) } }
            }
        }
        doc.pages.forEachIndexed { i, page ->
            val res = page.cosObject.getDictionaryObject(COSName.RESOURCES) as? COSDictionary
            (res?.getDictionaryObject(COSName.PROPERTIES) as? COSDictionary)?.let { pr -> pr.keySet().forEach { k -> mark(pr.getDictionaryObject(k), i) } }
            (res?.getDictionaryObject(COSName.XOBJECT) as? COSDictionary)?.let { xo -> xo.keySet().forEach { k -> (xo.getDictionaryObject(k) as? COSDictionary)?.let { mark(it.getDictionaryObject(OC), i) } } }
            runCatching { page.annotations }.getOrNull()?.forEach { a -> mark(a.cosObject.getDictionaryObject(OC), i) }
        }
        return out
    }

    /** Layers in /Order order (groups give depth); layers missing from /Order follow. [initial] = states at open. */
    fun list(doc: PDDocument, initial: Map<Int, Boolean> = emptyMap()): List<LayerRow> {
        val all = ocgs(doc); if (all.isEmpty()) return emptyList()
        val dd = d(doc)
        val locked = (deref(dd?.getDictionaryObject(LOCKED)) as? COSArray)
        val pages = pagesOf(doc)
        val seen = LinkedHashMap<COSDictionary, Pair<Int, String?>>()
        fun walk(a: COSArray, depth: Int, group: String?) {
            if (depth > 12) return
            var label = group
            for (k in 0 until a.size()) when (val x = deref(a.get(k))) {
                is COSString -> label = x.string
                is COSDictionary -> if (x in all && x !in seen) seen[x] = depth to label
                is COSArray -> walk(x, depth + 1, (deref(x.get(0)) as? COSString)?.string ?: label)
                else -> {}
            }
        }
        (deref(dd?.getDictionaryObject(ORDER)) as? COSArray)?.let { walk(it, 0, null) }
        all.forEach { if (it !in seen) seen[it] = 0 to null }
        return seen.map { (g, pos) ->
            val i = all.indexOf(g)
            val on = state(doc, g)
            LayerRow(i, g.getString(COSName.NAME) ?: "#${i + 1}", on, initial[i] ?: on, has(locked, g),
                (deref(g.getDictionaryObject(INTENT)) as? COSName)?.name ?: "View",
                use(g, "View", "ViewState"), use(g, "Print", "PrintState"), use(g, "Export", "ExportState"),
                pos.first, pos.second, pages[g].orEmpty())
        }
    }

    /** Visibility of every layer (index → on), e.g. to remember the initial state. */
    fun states(doc: PDDocument): Map<Int, Boolean> = ocgs(doc).withIndex().associate { (i, g) -> i to state(doc, g) }

    fun setOn(doc: PDDocument, index: Int, on: Boolean) {
        val g = ocgs(doc).getOrNull(index) ?: return
        val dd = d(doc) ?: COSDictionary().also { props(doc)!!.setItem(COSName.D, it) }
        val onA = arr(dd, ON); val offA = arr(dd, OFF)
        drop(onA, g); drop(offA, g)
        (if (on) onA else offA).add(g)
    }

    /** "Reset to initial visibility". */
    fun reset(doc: PDDocument, initial: Map<Int, Boolean>) = initial.forEach { (i, on) -> setOn(doc, i, on) }

    fun showAll(doc: PDDocument, on: Boolean) = ocgs(doc).indices.forEach { setOn(doc, it, on) }

    fun rename(doc: PDDocument, index: Int, name: String) { ocgs(doc).getOrNull(index)?.setString(COSName.NAME, name) }

    fun setLocked(doc: PDDocument, index: Int, locked: Boolean) {
        val g = ocgs(doc).getOrNull(index) ?: return
        val dd = d(doc) ?: return
        val a = arr(dd, LOCKED); drop(a, g); if (locked) a.add(g)
    }

    fun setIntent(doc: PDDocument, index: Int, intent: String) { ocgs(doc).getOrNull(index)?.setItem(INTENT, COSName.getPDFName(intent)) }

    /** Usage for View / Print / Export, and the matching /AS auto-state entry so viewers apply it. */
    fun setUse(doc: PDDocument, index: Int, event: String, use: LayerUse) {
        val g = ocgs(doc).getOrNull(index) ?: return
        val key = event + "State"
        val usage = (deref(g.getDictionaryObject(USAGE)) as? COSDictionary) ?: COSDictionary().also { g.setItem(USAGE, it) }
        if (use == LayerUse.WHEN_ON) usage.removeItem(COSName.getPDFName(event))
        else usage.setItem(COSName.getPDFName(event), COSDictionary().also { it.setItem(COSName.getPDFName(key), COSName.getPDFName(if (use == LayerUse.ALWAYS) "ON" else "OFF")) })
        if (usage.size() == 0) g.removeItem(USAGE)
        // /D /AS: one entry per event listing the OCGs whose usage matters for it
        val dd = d(doc) ?: return
        val asArr = arr(dd, AS)
        var entry: COSDictionary? = null
        for (k in 0 until asArr.size()) (deref(asArr.get(k)) as? COSDictionary)?.let { if (it.getNameAsString(COSName.getPDFName("Event")) == event) entry = it }
        val e = entry ?: COSDictionary().also { n ->
            n.setItem(COSName.getPDFName("Event"), COSName.getPDFName(event))
            n.setItem(COSName.getPDFName("Category"), COSArray().also { it.add(COSName.getPDFName(event)) })
            n.setItem(OCGS, COSArray()); asArr.add(n)
        }
        val list = arr(e, OCGS); drop(list, g); if (use != LayerUse.WHEN_ON) list.add(g)
        if (list.size() == 0) { for (k in asArr.size() - 1 downTo 0) if (deref(asArr.get(k)) === e) asArr.remove(k) }
        if (asArr.size() == 0) dd.removeItem(AS)
    }

    /** "Apply print / export overrides": visibility becomes what printing / exporting would show. */
    fun applyOverrides(doc: PDDocument, event: String) {
        list(doc).forEach { r ->
            val u = when (event) { "Print" -> r.print; "Export" -> r.export; else -> r.view }
            if (u != LayerUse.WHEN_ON) setOn(doc, r.index, u == LayerUse.ALWAYS)
        }
    }

    /** Moves layer [index] one place up / down in the top level of /Order. */
    fun move(doc: PDDocument, index: Int, delta: Int) {
        val g = ocgs(doc).getOrNull(index) ?: return
        val dd = d(doc) ?: return
        val order = arr(dd, ORDER)
        if (!has(order, g)) ocgs(doc).forEach { x -> if (!has(order, x)) order.add(x) }
        val pos = (0 until order.size()).firstOrNull { deref(order.get(it)) === g } ?: return
        val to = (pos + delta).coerceIn(0, order.size() - 1); if (to == pos) return
        val item = order.get(pos); order.remove(pos); order.add(to, item)
    }

    /** A new, empty layer (shown); returns its group. */
    fun create(doc: PDDocument, name: String): PDOptionalContentGroup {
        val p = doc.documentCatalog.ocProperties ?: PDOptionalContentProperties().also { doc.documentCatalog.ocProperties = it }
        val g = PDOptionalContentGroup(name)
        p.addGroup(g)
        d(doc)?.let { dd -> (deref(dd.getDictionaryObject(ORDER)) as? COSArray)?.let { o -> if (!has(o, g.cosObject)) o.add(g.cosObject) } }
        return g
    }

    /** Every place that points at [from] (page properties, XObjects, annotations, OCMDs) points at [to] instead. */
    private fun repoint(doc: PDDocument, from: COSDictionary, to: COSDictionary?) {
        fun fix(d: COSDictionary, key: COSName) {
            when (val v = deref(d.getDictionaryObject(key))) {
                from -> if (to == null) d.removeItem(key) else d.setItem(key, to)
                is COSDictionary -> if (v.getNameAsString(COSName.TYPE) == "OCMD") (deref(v.getDictionaryObject(OCGS)) as? COSArray)?.let { a ->
                    for (k in a.size() - 1 downTo 0) if (deref(a.get(k)) === from) { if (to == null) a.remove(k) else a.set(k, to) }
                } else if (v === from && to != null) d.setItem(key, to)
                else -> {}
            }
        }
        doc.pages.forEach { page ->
            val res = page.cosObject.getDictionaryObject(COSName.RESOURCES) as? COSDictionary
            (res?.getDictionaryObject(COSName.PROPERTIES) as? COSDictionary)?.let { pr -> pr.keySet().toList().forEach { k -> if (deref(pr.getDictionaryObject(k)) === from && to != null) pr.setItem(k, to) else fix(pr, k) } }
            (res?.getDictionaryObject(COSName.XOBJECT) as? COSDictionary)?.let { xo -> xo.keySet().forEach { k -> (xo.getDictionaryObject(k) as? COSDictionary)?.let { fix(it, OC) } } }
            runCatching { page.annotations }.getOrNull()?.forEach { a -> fix(a.cosObject, OC) }
        }
    }

    /** Removes [g] from /OCGs, /Order, ON / OFF / Locked / AS lists. */
    private fun unlist(doc: PDDocument, g: COSDictionary) {
        val p = props(doc) ?: return
        drop(deref(p.getDictionaryObject(OCGS)) as? COSArray, g)
        val dd = d(doc) ?: return
        listOf(ON, OFF, LOCKED).forEach { drop(deref(dd.getDictionaryObject(it)) as? COSArray, g) }
        fun clean(a: COSArray, depth: Int) { if (depth > 12) return; drop(a, g); for (k in 0 until a.size()) (deref(a.get(k)) as? COSArray)?.let { clean(it, depth + 1) } }
        (deref(dd.getDictionaryObject(ORDER)) as? COSArray)?.let { clean(it, 0) }
        (deref(dd.getDictionaryObject(AS)) as? COSArray)?.forEach { e -> (deref(e) as? COSDictionary)?.let { drop(deref(it.getDictionaryObject(OCGS)) as? COSArray, g) } }
    }

    /** Merge layers [from] into [into]: their content belongs to [into] afterwards. */
    fun merge(doc: PDDocument, from: List<Int>, into: Int) {
        val all = ocgs(doc); val target = all.getOrNull(into) ?: return
        from.filter { it != into }.mapNotNull { all.getOrNull(it) }.forEach { g -> repoint(doc, g, target); unlist(doc, g) }
    }

    /**
     * Delete layer [index] with its content. [removeHiddenContent] removes the content of switched-off layers (the
     * sanitizer does it); every other layer is switched on meanwhile and gets its state back afterwards.
     */
    fun delete(doc: PDDocument, index: Int, removeHiddenContent: (PDDocument) -> Unit) {
        val all = ocgs(doc); val g = all.getOrNull(index) ?: return
        val before = states(doc)
        all.indices.forEach { setOn(doc, it, it != index) }
        removeHiddenContent(doc)
        before.forEach { (i, on) -> if (i != index) setOn(doc, i, on) }
        unlist(doc, g); repoint(doc, g, null)
    }

    /** "Flatten layers": hidden content goes, what is visible stays as plain content and the layers disappear. */
    fun flatten(doc: PDDocument, removeHiddenContent: (PDDocument) -> Unit) {
        removeHiddenContent(doc)
        ocgs(doc).forEach { g -> repoint(doc, g, null) }
        doc.documentCatalog.cosObject.removeItem(OCP)
    }

    /**
     * Foxit "Import as layer" (picture): [image] drawn on [pages] inside a new layer [name] at ([x], [y], [w], [h])
     * in points of each page.
     */
    fun importImage(doc: PDDocument, image: PDImageXObject, name: String, pages: List<Int>, place: (Float, Float) -> FloatArray) {
        val g = create(doc, name)
        pages.filter { it in 0 until doc.numberOfPages }.forEach { i ->
            val page = doc.getPage(i)
            val box = page.cropBox
            val r = place(box.width, box.height)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.beginMarkedContent(OC, g)
                cs.drawImage(image, box.lowerLeftX + r[0], box.lowerLeftY + r[1], r[2], r[3])
                cs.endMarkedContent()
            }
        }
    }

    /** Foxit "Import as layer" (PDF page): page [srcPage] of [src] drawn on [pages] in a new layer, scaled to fit. */
    fun importPage(doc: PDDocument, src: PDDocument, srcPage: Int, name: String, pages: List<Int>) {
        val util = com.tom_roush.pdfbox.multipdf.LayerUtility(doc)
        val form = util.importPageAsForm(src, srcPage)
        val g = create(doc, name)
        pages.filter { it in 0 until doc.numberOfPages }.forEach { i ->
            val page = doc.getPage(i)
            val box = page.cropBox; val fb = form.bBox
            val k = minOf(box.width / fb.width, box.height / fb.height)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.beginMarkedContent(OC, g)
                cs.saveGraphicsState()
                cs.transform(com.tom_roush.pdfbox.util.Matrix(k, 0f, 0f, k, box.lowerLeftX + (box.width - fb.width * k) / 2 - fb.lowerLeftX * k, box.lowerLeftY + (box.height - fb.height * k) / 2 - fb.lowerLeftY * k))
                cs.drawForm(form)
                cs.restoreGraphicsState()
                cs.endMarkedContent()
            }
        }
    }

    /** A SetOCGState action that shows [show] and hides [hide] (indexes). */
    fun setStateAction(doc: PDDocument, show: List<Int>, hide: List<Int>): COSDictionary {
        val all = ocgs(doc)
        val state = COSArray()
        if (hide.isNotEmpty()) { state.add(COSName.getPDFName("OFF")); hide.mapNotNull { all.getOrNull(it) }.forEach { state.add(it) } }
        if (show.isNotEmpty()) { state.add(COSName.getPDFName("ON")); show.mapNotNull { all.getOrNull(it) }.forEach { state.add(it) } }
        return COSDictionary().also { it.setItem(COSName.S, COSName.getPDFName("SetOCGState")); it.setItem(COSName.getPDFName("State"), state); it.setBoolean(COSName.getPDFName("PreserveRB"), true) }
    }

    /** A bookmark titled [title] that shows only layer [index] (Nitro / Foxit "set layer visibility" action). */
    fun addStateBookmark(doc: PDDocument, title: String, show: List<Int>, hide: List<Int>) {
        val outline = doc.documentCatalog.documentOutline ?: PDDocumentOutline().also { doc.documentCatalog.documentOutline = it }
        val item = PDOutlineItem().apply { this.title = title }
        item.cosObject.setItem(COSName.A, setStateAction(doc, show, hide))
        outline.addLast(item)
    }
}

/** pdf39 §22 #270: the layers list filtered to the layers used on some pages (visible / current page). Pure. */
object LayerPageFilter {
    /** Keep a layer used on [pages] when [only] is null (all pages) or it is used on one of [only]. */
    fun keep(pages: List<Int>, only: Set<Int>?): Boolean = only == null || pages.any { it in only }
}
