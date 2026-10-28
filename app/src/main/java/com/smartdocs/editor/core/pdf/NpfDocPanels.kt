package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination

/**
 * nav-prefs (Foxit §40 navigation panels): what the extra reader panels show, read with PdfBox — layers (optional
 * content), security settings, form fields, the structure tree (tags / order), article threads, named destinations
 * and the standards the file claims (XMP). Pure over a [PDDocument], so it is unit-tested.
 */
object NpfDocPanels {
    data class Layer(val name: String, val on: Boolean, val index: Int)
    data class Security(val encrypted: Boolean, val filter: String, val version: Int, val revision: Int, val keyBits: Int,
                        val print: Boolean, val modify: Boolean, val copy: Boolean, val annotate: Boolean, val fill: Boolean,
                        val assemble: Boolean, val printHigh: Boolean, val extractForA11y: Boolean)
    data class FieldRow(val name: String, val type: String, val value: String, val page: Int, val required: Boolean, val readOnly: Boolean)
    data class StructRow(val depth: Int, val type: String, val text: String, val page: Int)
    data class Article(val title: String, val beads: Int, val firstPage: Int)
    data class Dest(val name: String, val page: Int)
    data class Standards(val pdfVersion: String, val pdfa: String?, val pdfua: String?, val pdfx: String?, val pdfe: String?, val tagged: Boolean)

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b
    private fun pageOf(doc: PDDocument, d: COSDictionary?): Int = d?.let { pd -> doc.pages.indexOf(com.tom_roush.pdfbox.pdmodel.PDPage(pd)) } ?: -1

    // ---- layers ----
    private fun ocProps(doc: PDDocument): COSDictionary? = deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("OCProperties"))) as? COSDictionary

    fun layers(doc: PDDocument): List<Layer> {
        val p = ocProps(doc) ?: return emptyList()
        val ocgs = deref(p.getDictionaryObject(COSName.getPDFName("OCGs"))) as? COSArray ?: return emptyList()
        val d = deref(p.getDictionaryObject(COSName.D)) as? COSDictionary
        val off = (deref(d?.getDictionaryObject(COSName.getPDFName("OFF"))) as? COSArray)?.mapNotNull { deref(it) }?.toSet().orEmpty()
        val baseOff = d?.getNameAsString(COSName.getPDFName("BaseState")) == "OFF"
        val on = (deref(d?.getDictionaryObject(COSName.getPDFName("ON"))) as? COSArray)?.mapNotNull { deref(it) }?.toSet().orEmpty()
        return (0 until ocgs.size()).mapNotNull { i ->
            val g = deref(ocgs.getObject(i)) as? COSDictionary ?: return@mapNotNull null
            val visible = if (baseOff) g in on else g !in off
            Layer(g.getString(COSName.NAME) ?: "#${i + 1}", visible, i)
        }
    }

    /** Turn layer [index] on / off in the default configuration (what every viewer shows when it opens the file). */
    fun setLayer(doc: PDDocument, index: Int, visible: Boolean): Boolean {
        val p = ocProps(doc) ?: return false
        val ocgs = deref(p.getDictionaryObject(COSName.getPDFName("OCGs"))) as? COSArray ?: return false
        val g = deref(ocgs.getObject(index)) as? COSDictionary ?: return false
        val d = deref(p.getDictionaryObject(COSName.D)) as? COSDictionary ?: COSDictionary().also { p.setItem(COSName.D, it) }
        fun arr(key: String): COSArray = deref(d.getDictionaryObject(COSName.getPDFName(key))) as? COSArray ?: COSArray().also { d.setItem(COSName.getPDFName(key), it) }
        val offArr = arr("OFF"); val onArr = arr("ON")
        fun remove(a: COSArray) { for (k in a.size() - 1 downTo 0) if (deref(a.get(k)) === g) a.remove(k) }
        remove(offArr); remove(onArr)
        (if (visible) onArr else offArr).add(ocgs.get(index))
        return true
    }

    // ---- security ----
    fun security(doc: PDDocument): Security {
        val enc = doc.encryption
        val ap = doc.currentAccessPermission
        return Security(doc.isEncrypted, enc?.filter ?: "", enc?.version ?: 0, enc?.revision ?: 0, enc?.length ?: 0,
            ap.canPrint(), ap.canModify(), ap.canExtractContent(), ap.canModifyAnnotations(), ap.canFillInForm(),
            ap.canAssembleDocument(), ap.canPrintFaithful(), ap.canExtractForAccessibility())
    }

    // ---- fields ----
    fun fields(doc: PDDocument): List<FieldRow> {
        val form = doc.documentCatalog.getAcroForm(null) ?: return emptyList()
        val pageOfWidget = HashMap<COSDictionary, Int>()
        doc.pages.forEachIndexed { i, pg -> runCatching { pg.annotations }.getOrNull()?.forEach { pageOfWidget[it.cosObject] = i } }
        return form.fieldTree.mapNotNull { f ->
            if (f is com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField) return@mapNotNull null
            val w = (f as? com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField)?.widgets?.firstOrNull()
            val page = w?.let { pageOfWidget[it.cosObject] ?: pageOf(doc, deref(w.cosObject.getDictionaryObject(COSName.P)) as? COSDictionary) } ?: -1
            FieldRow(f.fullyQualifiedName ?: f.partialName ?: "", f.fieldType ?: "", runCatching { f.valueAsString }.getOrDefault(""), page, f.isRequired, f.isReadOnly)
        }
    }

    // ---- structure tree (tags / order) ----
    fun structure(doc: PDDocument, max: Int = 3000): List<StructRow> {
        val root = deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.STRUCT_TREE_ROOT)) as? COSDictionary ?: return emptyList()
        val out = ArrayList<StructRow>()
        fun visit(b: COSBase?, depth: Int, inheritedPage: Int) {
            if (out.size >= max || depth > 60) return
            when (val e = deref(b)) {
                is COSArray -> for (i in 0 until e.size()) visit(e.get(i), depth, inheritedPage)
                is COSDictionary -> {
                    if (!e.containsKey(COSName.S)) return            // marked-content / object references
                    val page = (deref(e.getDictionaryObject(COSName.PG)) as? COSDictionary)?.let { pageOf(doc, it) } ?: inheritedPage
                    val text = listOf("Alt", "T", "ActualText", "E").firstNotNullOfOrNull { k -> (deref(e.getDictionaryObject(COSName.getPDFName(k))) as? COSString)?.string?.takeIf { it.isNotBlank() } } ?: ""
                    out += StructRow(depth, e.getNameAsString(COSName.S) ?: "?", text, page)
                    visit(e.getDictionaryObject(COSName.K), depth + 1, page)
                }
                else -> Unit
            }
        }
        visit(root.getDictionaryObject(COSName.K), 0, -1)
        return out
    }

    // ---- articles ----
    fun articles(doc: PDDocument): List<Article> {
        val threads = deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("Threads"))) as? COSArray ?: return emptyList()
        return (0 until threads.size()).mapNotNull { i ->
            val t = deref(threads.getObject(i)) as? COSDictionary ?: return@mapNotNull null
            val info = deref(t.getDictionaryObject(COSName.I)) as? COSDictionary
            val first = deref(t.getDictionaryObject(COSName.F)) as? COSDictionary
            var n = 0; var b = first; val seen = HashSet<COSDictionary>()
            while (b != null && seen.add(b) && n < 10_000) { n++; b = deref(b.getDictionaryObject(COSName.N)) as? COSDictionary }
            Article(info?.getString(COSName.TITLE) ?: "#${i + 1}", n, pageOf(doc, deref(first?.getDictionaryObject(COSName.P)) as? COSDictionary))
        }
    }

    // ---- named destinations ----
    fun destinations(doc: PDDocument): List<Dest> {
        val out = ArrayList<Dest>()
        fun pageIndexOf(b: COSBase?): Int {
            val arr = when (val v = deref(b)) { is COSArray -> v; is COSDictionary -> deref(v.getDictionaryObject(COSName.D)) as? COSArray; else -> null } ?: return -1
            return when (val first = deref(arr.getObject(0))) {
                is COSDictionary -> pageOf(doc, first)
                is com.tom_roush.pdfbox.cos.COSInteger -> first.intValue()
                else -> -1
            }
        }
        (deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.DESTS)) as? COSDictionary)?.let { d ->
            d.keySet().forEach { k -> out += Dest(k.name, pageIndexOf(d.getDictionaryObject(k))) }
        }
        fun tree(n: COSDictionary?, depth: Int) {
            if (n == null || depth > 30) return
            (deref(n.getDictionaryObject(COSName.NAMES)) as? COSArray)?.let { a ->
                var i = 0
                while (i + 1 < a.size()) { (deref(a.getObject(i)) as? COSString)?.string?.let { out += Dest(it, pageIndexOf(a.getObject(i + 1))) }; i += 2 }
            }
            (deref(n.getDictionaryObject(COSName.KIDS)) as? COSArray)?.forEach { tree(deref(it) as? COSDictionary, depth + 1) }
        }
        tree(deref((deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES)) as? COSDictionary)?.getDictionaryObject(COSName.DESTS)) as? COSDictionary, 0)
        return out.distinctBy { it.name }
    }

    /** Add (or replace) the named destination [name] → top of page [page]; written as a flat /Names /Dests tree. */
    fun putDestination(doc: PDDocument, name: String, page: Int): Boolean {
        if (name.isBlank() || page !in 0 until doc.numberOfPages) return false
        val all = destinations(doc).filter { it.name != name }.associate { it.name to it.page }.toMutableMap()
        all[name] = page
        writeDestinations(doc, all)
        return true
    }

    fun removeDestination(doc: PDDocument, name: String): Boolean {
        val all = destinations(doc).associate { it.name to it.page }.toMutableMap()
        if (all.remove(name) == null) return false
        writeDestinations(doc, all)
        return true
    }

    private fun writeDestinations(doc: PDDocument, all: Map<String, Int>) {
        val cat = doc.documentCatalog
        cat.cosObject.removeItem(COSName.DESTS)      // the old-style dictionary is folded into the name tree
        val names = cat.names ?: PDDocumentNameDictionary(cat).also { cat.names = it }
        val tree = com.tom_roush.pdfbox.pdmodel.PDDestinationNameTreeNode()
        val map = LinkedHashMap<String, PDPageDestination>()
        all.toSortedMap().forEach { (n, p) ->
            if (p !in 0 until doc.numberOfPages) return@forEach
            map[n] = PDPageXYZDestination().also { it.page = doc.getPage(p); it.top = doc.getPage(p).cropBox.upperRightY.toInt() }
        }
        tree.names = map
        names.dests = tree
    }

    // ---- standards ----
    fun standards(doc: PDDocument): Standards {
        val xmp = runCatching { doc.documentCatalog.metadata?.exportXMPMetadata()?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull().orEmpty()
        fun tag(ns: String, key: String): String? =
            Regex("""$ns:$key\s*=\s*"([^"]+)"""").find(xmp)?.groupValues?.get(1) ?: Regex("""<$ns:$key>([^<]+)</$ns:$key>""").find(xmp)?.groupValues?.get(1)
        val a = tag("pdfaid", "part")?.let { it + (tag("pdfaid", "conformance")?.lowercase() ?: "") }
        val ua = tag("pdfuaid", "part")
        val x = doc.documentInformation?.cosObject?.getString(COSName.getPDFName("GTS_PDFXVersion")) ?: tag("pdfxid", "GTS_PDFXVersion")
        val e = tag("pdfe", "ISO_PDFEVersion")
        val tagged = (deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.MARK_INFO)) as? COSDictionary)?.getBoolean(COSName.getPDFName("Marked"), false) == true
        return Standards(doc.version.toString(), a, ua, x, e, tagged)
    }
}
