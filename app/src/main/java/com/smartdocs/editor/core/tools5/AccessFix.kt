package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.convert.LayoutAnalyzer
import com.smartdocs.editor.core.convert.ParagraphBlock
import com.smartdocs.editor.core.convert.PdfContentExtractor
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField

/** Quick fixes of the accessibility checker and basic auto-tagging. All functions edit [doc] in memory. */
object AccessFix {

    fun setLanguage(doc: PDDocument, lang: String) { doc.documentCatalog.cosObject.setItem(N.Lang, COSString(lang)) }

    fun setTitle(doc: PDDocument, title: String) {
        doc.documentInformation.title = title
        val cat = doc.documentCatalog.cosObject
        val vp = cat.getDictionaryObject(N.ViewerPreferences) as? COSDictionary ?: COSDictionary().also { cat.setItem(N.ViewerPreferences, it) }
        vp.setBoolean(N.DisplayDocTitle, true)
    }

    /** Title suggestion: Info title, else the first heading-like line. */
    fun suggestTitle(doc: PDDocument, fallback: String): String =
        doc.documentInformation.title?.takeIf { it.isNotBlank() }
            ?: runCatching {
                val st = com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage = 1; endPage = 1 }
                SmartNaming.titleLine(st.getText(doc), 10)
            }.getOrNull() ?: fallback

    fun tabOrder(doc: PDDocument): Int {
        var n = 0
        doc.pages.forEach { p -> if (runCatching { p.annotations.isNotEmpty() }.getOrDefault(false)) { p.cosObject.setItem(N.Tabs, N.S); n++ } }
        return n
    }

    /** Field descriptions (TU) from the field's own name: "first_name" → "first name". */
    fun fieldDescriptions(doc: PDDocument): Int {
        var n = 0
        runCatching {
            doc.documentCatalog.acroForm?.fieldTree?.forEach { f ->
                if (f is PDTerminalField && f.fieldType != "Sig" && f.alternateFieldName.isNullOrBlank()) {
                    val label = (f.partialName ?: "").replace(Regex("""[_.\-]+"""), " ").replace(Regex("""([a-z])([A-Z])"""), "$1 $2").trim()
                    if (label.isNotEmpty()) { f.alternateFieldName = label; n++ }
                }
            }
        }
        return n
    }

    /** Bookmarks from detected headings (layout analysis), when the document has none. */
    fun bookmarksFromHeadings(doc: PDDocument, maxPages: Int = 300): Int {
        if (runCatching { doc.documentCatalog.documentOutline?.children()?.any() == true }.getOrDefault(false)) return 0
        val ex = PdfContentExtractor(doc)
        val outline = PDDocumentOutline()
        var n = 0
        for (i in 0 until minOf(doc.numberOfPages, maxPages)) {
            val layout = runCatching { LayoutAnalyzer.analyze(ex.extract(i, false), false) }.getOrNull() ?: continue
            layout.blocks.filterIsInstance<ParagraphBlock>().filter { it.headingLevel in 1..2 }.forEach { b ->
                val t = b.lines.joinToString(" ") { it.text }.trim().take(120)
                if (t.isNotEmpty()) { outline.addLast(PDOutlineItem().apply { title = t; setDestination(doc.getPage(i)) }); n++ }
            }
        }
        if (n > 0) doc.documentCatalog.documentOutline = outline
        return n
    }

    fun setAlt(fig: FigureRef, alt: String?) {
        if (alt.isNullOrBlank()) fig.elem.removeItem(N.Alt) else fig.elem.setItem(N.Alt, COSString(alt.trim()))
    }

    /** Decorative figure: retagged as an artifact-like element without alt (the checker no longer counts it as a figure). */
    fun markDecorative(fig: FigureRef) { fig.elem.setItem(N.S, N.n("Artifact")); fig.elem.removeItem(N.Alt) }

    private fun elem(type: String, parent: COSDictionary, page: COSDictionary?): COSDictionary = COSDictionary().apply {
        setItem(N.Type, N.StructElem); setItem(N.S, N.n(type)); setItem(N.P, parent)
        if (page != null) setItem(N.Pg, page)
        setItem(N.K, COSArray())
    }

    private fun addKid(parent: COSDictionary, kid: com.tom_roush.pdfbox.cos.COSBase) { (parent.getDictionaryObject(N.K) as COSArray).add(kid) }

    /**
     * Basic auto-tagging of an untagged document: Document → one P per run of page content, a Figure (MCID of its
     * own) around every top-level image, Link / Form elements for link and widget annotations (OBJR), ParentTree,
     * MarkInfo, /Lang, /Tabs /S. Text is not split into headings / tables (that needs layout-to-content mapping).
     * Returns the number of pages tagged; 0 when the document already has a structure tree.
     */
    fun autoTag(doc: PDDocument, lang: String): Int {
        val cat = doc.documentCatalog.cosObject
        if (cat.containsKey(N.StructTreeRoot)) return 0
        val root = COSDictionary().apply { setItem(N.Type, N.StructTreeRoot) }
        val document = COSDictionary().apply { setItem(N.Type, N.StructElem); setItem(N.S, N.n("Document")); setItem(N.P, root); setItem(N.K, COSArray()) }
        root.setItem(N.K, document)
        val nums = COSArray()
        var nextKey = 0
        var tagged = 0
        doc.pages.forEach { page ->
            val pd = page.cosObject
            val sect = elem("Sect", document, pd); addKid(document, sect)
            val mcidOwners = COSArray()           // index = MCID → owning element
            val rewritten = runCatching { wrapContent(doc, page, sect, mcidOwners) }.getOrDefault(false)
            if (rewritten) {
                pd.setInt(N.StructParents, nextKey)
                nums.add(COSInteger.get(nextKey.toLong())); nums.add(mcidOwners); nextKey++
                tagged++
            }
            runCatching {
                page.annotations.forEach { a ->
                    val sub = a.subtype ?: return@forEach
                    if (sub == "Popup") return@forEach
                    val type = when (sub) { "Link" -> "Link"; "Widget" -> "Form"; else -> "Annot" }
                    val e = elem(type, sect, pd)
                    val objr = COSDictionary().apply { setItem(N.Type, N.OBJR); setItem(N.Obj, a.cosObject); setItem(N.Pg, pd) }
                    addKid(e, objr); addKid(sect, e)
                    if (type == "Form") ((a.cosObject.getDictionaryObject(N.TU) as? COSString) ?: (a.cosObject.getDictionaryObject(N.n("Parent")) as? COSDictionary)?.getDictionaryObject(N.TU) as? COSString)?.let { e.setItem(N.Alt, it) }
                    if (type == "Link" && !a.cosObject.containsKey(N.Contents)) a.cosObject.setString(N.Contents, "Link")
                    a.cosObject.setInt(N.StructParent, nextKey)
                    nums.add(COSInteger.get(nextKey.toLong())); nums.add(e); nextKey++
                }
                if (page.annotations.isNotEmpty()) pd.setItem(N.Tabs, N.S)
            }
        }
        root.setItem(N.ParentTree, COSDictionary().apply { setItem(N.Nums, nums) })
        root.setInt(N.ParentTreeNextKey, nextKey)
        cat.setItem(N.StructTreeRoot, root)
        cat.setItem(N.MarkInfo, COSDictionary().apply { setItem(N.Marked, COSBoolean.TRUE) })
        if (!cat.containsKey(N.Lang)) setLanguage(doc, lang)
        return tagged
    }

    /**
     * Rewrite one page's content: existing marked content is kept as is; everything at marked-content depth 0 is
     * wrapped in /P (MCID n) runs, and each image `Do` outside text objects gets its own /Figure (MCID n).
     */
    private fun wrapContent(doc: PDDocument, page: PDPage, sect: COSDictionary, owners: COSArray): Boolean {
        val tokens: List<Any> = PDFStreamParser(page).let { it.parse(); it.tokens }
        if (tokens.isEmpty()) return false
        val res = page.resources
        val out = ArrayList<Any>(tokens.size + 32)
        var mcid = 0
        var openP = false
        var mcDepth = 0; var inText = false
        val pending = ArrayList<Any>()                       // operands of the current operator
        fun open(type: String) {
            val e = elem(type, sect, page.cosObject)
            addKid(e, COSInteger.get(mcid.toLong())); addKid(sect, e); owners.add(e)
            out.add(N.n(type)); out.add(COSDictionary().apply { setInt(N.MCID, mcid) }); out.add(Operator.getOperator("BDC"))
            mcid++
        }
        fun close() { out.add(Operator.getOperator("EMC")) }
        for (t in tokens) {
            if (t !is Operator) { pending += t; continue }
            val name = t.name
            val isImageDo = name == "Do" && !inText && mcDepth == 0 && (pending.lastOrNull() as? COSName)?.let { n -> runCatching { res?.getXObject(n) is PDImageXObject }.getOrDefault(false) } == true
            when {
                // existing marked content (spans, artifacts) simply nests inside the current P run
                name == "BMC" || name == "BDC" -> { mcDepth++; out.addAll(pending); out.add(t) }
                name == "EMC" -> { out.addAll(pending); out.add(t); mcDepth = (mcDepth - 1).coerceAtLeast(0) }
                isImageDo -> {
                    if (openP) { close(); openP = false }
                    open("Figure"); out.addAll(pending); out.add(t); close()
                }
                else -> {
                    // painting / text at depth 0 goes into a P run; BT opens one before the text object starts
                    if (!openP && mcDepth == 0 && name !in setOf("q", "Q", "cm", "gs", "w", "J", "j", "M", "d", "ri", "i")) { open("P"); openP = true }
                    if (name == "BT") inText = true
                    if (name == "ET") inText = false
                    out.addAll(pending); out.add(t)
                }
            }
            pending.clear()
            // a P run never ends inside a text object: it is closed only before an image Do (outside BT/ET) or at the end
        }
        if (openP) close()
        if (mcid == 0) return false
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { o -> ContentStreamWriter(o).writeTokens(out) }
        page.setContents(stream)
        return true
    }
}
