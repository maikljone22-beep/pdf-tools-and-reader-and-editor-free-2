package com.smartdocs.editor.core.tools5

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
import com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFMarkedContentExtractor
import com.tom_roush.pdfbox.text.TextPosition

/**
 * Foxit 34.34 / Nitro 19 auto-tag with structure recognition: one tag per text object, images as Figure, vector
 * painting as Artifact; text blocks become H1–H3 (larger than the body size), list items (L > LI > LBody, from bullet
 * or number prefixes) or P. A tagged file can be re-tagged (old tags and MCIDs are dropped first). Returns a
 * recognition report.
 */
object AccessAutoTag {
    enum class Kind { H1, H2, H3, P, LI }

    /** One text object: its MCID, text, size (pt) and whether its font looks bold. */
    data class Blk(val mcid: Int, val text: String, val size: Float, val bold: Boolean = false, val x: Float = 0f, val y: Float = 0f)

    data class Report(
        val pages: Int, val taggedPages: Int, val headings: Int, val paragraphs: Int, val lists: Int, val listItems: Int,
        val figures: Int, val artifacts: Int, val annotations: Int, val pagesWithoutText: Int, val retagged: Boolean,
        val tables: Int = 0,
    )

    /**
     * FINAL_CHECK "Auto Tag tables": runs of 2+ consecutive lines that each hold 2+ text blocks in the same columns
     * (x within 1.5 × the text size) are a table; rows top to bottom, cells left to right (mcids). Pure.
     */
    fun tables(blocks: List<Blk>): List<List<List<Int>>> {
        val b = blocks.filter { it.text.isNotBlank() }
        if (b.size < 4) return emptyList()
        val size = b.map { it.size }.sorted()[b.size / 2].coerceAtLeast(4f)
        // lines: blocks whose y is within half a text size
        val lines = ArrayList<MutableList<Blk>>()
        b.sortedBy { it.y }.forEach { k -> val last = lines.lastOrNull(); if (last != null && kotlin.math.abs(last.first().y - k.y) <= size * 0.5f) last += k else lines += mutableListOf(k) }
        lines.forEach { it.sortBy { c -> c.x } }
        fun sameColumns(a: List<Blk>, c: List<Blk>) = a.size == c.size && a.indices.all { kotlin.math.abs(a[it].x - c[it].x) <= size * 1.5f }
        val out = ArrayList<List<List<Int>>>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].size >= 2) {
                var j = i + 1
                while (j < lines.size && sameColumns(lines[i], lines[j])) j++
                if (j - i >= 2) { out += lines.subList(i, j).map { row -> row.map { it.mcid } }; i = j; continue }
            }
            i++
        }
        return out
    }

    private val bullet = Regex("""^\s*([•●▪◦‣⁃·*\-–]|\(?[0-9٠-٩]{1,3}[.)\-]|\(?[a-zA-Zأ-ي][.)])\s+""")

    /** Pure: heading levels from size ranks above the body size, list items from prefixes, else paragraphs. */
    fun classify(blocks: List<Blk>, minRatio: Float = 1.15f): List<Kind> {
        if (blocks.isEmpty()) return emptyList()
        val weights = HashMap<Float, Int>()
        blocks.forEach { b -> val k = Math.round(b.size * 2) / 2f; weights[k] = (weights[k] ?: 0) + b.text.length }
        val body = weights.maxByOrNull { it.value }!!.key
        fun isHeading(b: Blk) = (Math.round(b.size * 2) / 2f) >= body * minRatio && b.text.trim().length in 2..140 && b.text.any { it.isLetter() } && !bullet.containsMatchIn(b.text)
        val sizes = blocks.filter(::isHeading).map { Math.round(it.size * 2) / 2f }.distinct().sortedDescending().take(3)
        return blocks.map { b ->
            when {
                isHeading(b) -> when (sizes.indexOf(Math.round(b.size * 2) / 2f)) { 0 -> Kind.H1; 1 -> Kind.H2; else -> Kind.H3 }
                bullet.containsMatchIn(b.text) -> Kind.LI
                else -> Kind.P
            }
        }
    }

    private enum class Rec { TEXT, FIGURE }

    private val pathOps = setOf("m", "l", "c", "v", "y", "h", "re", "S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n", "sh", "W", "W*")

    /** Rewrites one page's content; returns the records (MCID order) and the number of artifact runs. */
    /**
     * FINAL_CHECK_2 #20 Content panel: give a page's unmarked text runs and pictures MCIDs (numbered after the ones it
     * already has) without touching its structure, so they can be tagged from the panel. False when nothing changed.
     */
    fun markContent(doc: PDDocument, page: PDPage): Boolean {
        val existing = runCatching { PDFStreamParser(page).let { it.parse(); it.tokens }.filterIsInstance<COSDictionary>().mapNotNull { (it.getDictionaryObject(COSName.getPDFName("MCID")) as? com.tom_roush.pdfbox.cos.COSNumber)?.intValue() }.maxOrNull() }.getOrNull() ?: -1
        val r = rewrite(doc, page, dropOldMcids = false, startMcid = existing + 1) ?: return false
        return r.first.isNotEmpty()
    }

    private fun rewrite(doc: PDDocument, page: PDPage, dropOldMcids: Boolean, startMcid: Int = 0): Pair<List<Rec>, Int>? {
        val tokens: List<Any> = PDFStreamParser(page).let { it.parse(); it.tokens }
        if (tokens.isEmpty()) return null
        val res = page.resources
        val out = ArrayList<Any>(tokens.size + 64)
        val recs = ArrayList<Rec>()
        var depth = 0; var inText = false; var artifact = false; var artifacts = 0
        val skip = ArrayDeque<Boolean>()
        val pending = ArrayList<Any>()
        fun bdc(tag: String) { out.add(COSName.getPDFName(tag)); out.add(COSDictionary().apply { setInt(COSName.getPDFName("MCID"), startMcid + recs.size) }); out.add(Operator.getOperator("BDC")) }
        fun emc() = out.add(Operator.getOperator("EMC"))
        fun endArtifact() { if (artifact) { emc(); artifact = false } }
        for (t in tokens) {
            if (t !is Operator) { pending += t; continue }
            val name = t.name
            when {
                name == "BDC" || name == "BMC" -> {
                    endArtifact()
                    val props = pending.lastOrNull() as? COSDictionary
                    val drop = dropOldMcids && name == "BDC" && props?.containsKey(COSName.getPDFName("MCID")) == true
                    skip.addLast(drop)
                    if (!drop) { depth++; out.addAll(pending); out.add(t) }
                }
                name == "EMC" -> {
                    endArtifact()
                    val drop = skip.removeLastOrNull() ?: false
                    if (!drop) { depth = (depth - 1).coerceAtLeast(0); out.addAll(pending); out.add(t) }
                }
                depth > 0 -> { out.addAll(pending); out.add(t); if (name == "BT") inText = true; if (name == "ET") inText = false }
                name == "BT" -> { endArtifact(); bdc("P"); recs += Rec.TEXT; inText = true; out.addAll(pending); out.add(t) }
                name == "ET" -> { out.addAll(pending); out.add(t); if (inText) emc(); inText = false }
                !inText && (name == "BI" || (name == "Do" && (pending.lastOrNull() as? COSName)?.let { n -> runCatching { res?.getXObject(n) is PDImageXObject }.getOrDefault(false) } == true)) -> {
                    endArtifact(); bdc("Figure"); recs += Rec.FIGURE; out.addAll(pending); out.add(t); emc()
                }
                !inText && (name in pathOps || name == "Do") -> {
                    // decoration (lines, boxes, forms of vector art) is an artifact for screen readers
                    if (!artifact) { out.add(COSName.getPDFName("Artifact")); out.add(Operator.getOperator("BMC")); artifact = true; artifacts++ }
                    out.addAll(pending); out.add(t)
                }
                else -> { out.addAll(pending); out.add(t) }
            }
            pending.clear()
        }
        endArtifact()
        if (inText) emc()
        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { o -> ContentStreamWriter(o).writeTokens(out) }
        page.setContents(stream)
        return recs to artifacts
    }

    /** Text of a marked content; [pos] gets the position of its first glyph (x, top) when still NaN. */
    private fun textOf(mc: PDMarkedContent, sizes: MutableList<Float>, bold: BooleanArray, pos: FloatArray = floatArrayOf(Float.NaN, Float.NaN)): String = buildString {
        mc.contents.forEach { c ->
            when (c) {
                is TextPosition -> {
                    append(c.unicode); sizes += c.fontSizeInPt; if (c.font?.name?.contains("Bold", true) == true) bold[0] = true
                    if (pos[0].isNaN()) { pos[0] = c.xDirAdj; pos[1] = c.yDirAdj }
                }
                is PDMarkedContent -> append(textOf(c, sizes, bold, pos))
            }
        }
    }

    private fun elem(type: String, parent: COSDictionary, page: COSDictionary?): COSDictionary = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.getPDFName("StructElem")); setItem(COSName.S, COSName.getPDFName(type)); setItem(COSName.P, parent)
        if (page != null) setItem(COSName.getPDFName("Pg"), page); setItem(COSName.K, COSArray())
    }

    private fun kid(parent: COSDictionary, k: com.tom_roush.pdfbox.cos.COSBase) {
        val a = parent.getDictionaryObject(COSName.K) as? COSArray ?: COSArray().also { parent.setItem(COSName.K, it) }
        a.add(k)
    }

    /** Drops the structure tree and the page / annotation links to it (Foxit "Clear page structure", all pages). */
    fun clearStructure(doc: PDDocument) {
        val cat = doc.documentCatalog.cosObject
        cat.removeItem(COSName.getPDFName("StructTreeRoot"))
        doc.pages.forEach { p ->
            p.cosObject.removeItem(COSName.getPDFName("StructParents"))
            runCatching { p.annotations }.getOrNull()?.forEach { it.cosObject.removeItem(COSName.getPDFName("StructParent")) }
        }
    }

    /**
     * Tag [f] in place (FINAL_CHECK_2 #3 / row 93 "create PDF tags" when creating a PDF): a file that is already
     * tagged is left as it is; the language comes from its text (Arabic → "ar", else the device language).
     */
    suspend fun tagFile(f: java.io.File): Report? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val tmp = java.io.File(f.parentFile, ".${f.name}.tag.pdf")
        try {
            val rep = com.smartdocs.editor.core.pdf.PdfOps.loadRaw(f, null).use { d ->
                if (d.documentCatalog.cosObject.containsKey(COSName.getPDFName("StructTreeRoot"))) return@use null
                val sample = runCatching { com.tom_roush.pdfbox.text.PDFTextStripper().apply { endPage = 2 }.getText(d) }.getOrDefault("")
                val lang = if (com.smartdocs.editor.core.convert6.Text6.rtlDominant(sample)) "ar" else java.util.Locale.getDefault().language.ifBlank { "en" }
                tag(d, lang, retag = false).also { d.save(tmp) }
            }
            if (rep != null && !tmp.renameTo(f)) tmp.copyTo(f, overwrite = true)
            rep
        } finally { tmp.delete() }
    }

    fun tag(doc: PDDocument, lang: String, retag: Boolean): Report {
        val cat = doc.documentCatalog.cosObject
        val wasTagged = cat.containsKey(COSName.getPDFName("StructTreeRoot"))
        if (wasTagged && !retag) return Report(doc.numberOfPages, 0, 0, 0, 0, 0, 0, 0, 0, 0, false)
        if (wasTagged) clearStructure(doc)
        // pass 1: rewrite every page, read the text of each MCID
        val perPage = ArrayList<Pair<List<Rec>, Map<Int, Blk>>>()
        var artifacts = 0; var pagesWithoutText = 0
        doc.pages.forEach { page ->
            val r = runCatching { rewrite(doc, page, dropOldMcids = wasTagged) }.getOrNull()
            if (r == null) { perPage += emptyList<Rec>() to emptyMap(); pagesWithoutText++; return@forEach }
            artifacts += r.second
            val blocks = HashMap<Int, Blk>()
            runCatching {
                val ex = PDFMarkedContentExtractor(); ex.processPage(page)
                ex.markedContents.forEach { mc ->
                    val id = mc.mcid; if (id < 0) return@forEach
                    val sizes = ArrayList<Float>(); val bold = booleanArrayOf(false); val pos = floatArrayOf(Float.NaN, Float.NaN)
                    val text = textOf(mc, sizes, bold, pos).replace(Regex("\\s+"), " ").trim()
                    blocks[id] = Blk(id, text, if (sizes.isEmpty()) 0f else sizes.sorted()[sizes.size / 2], bold[0],
                        if (pos[0].isNaN()) 0f else pos[0], if (pos[1].isNaN()) 0f else pos[1])
                }
            }
            if (r.first.none { it == Rec.TEXT } || blocks.values.none { it.text.isNotBlank() }) pagesWithoutText++
            perPage += r.first to blocks
        }
        // pass 2: classify every text block of the document together
        val all = perPage.flatMapIndexed { pi, (recs, blocks) -> recs.indices.filter { recs[it] == Rec.TEXT }.mapNotNull { m -> blocks[m]?.takeIf { it.text.isNotBlank() }?.let { (pi to m) to it } } }
        val kinds = classify(all.map { it.second }).let { ks -> all.map { it.first }.zip(ks).toMap() }
        // pass 3: structure tree
        val root = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("StructTreeRoot")) }
        val document = elem("Document", root, null); root.setItem(COSName.K, COSArray().also { it.add(document) })
        val nums = COSArray(); var nextKey = 0
        var headings = 0; var paras = 0; var lists = 0; var items = 0; var figures = 0; var annots = 0; var tagged = 0; var tableCount = 0
        perPage.forEachIndexed { pi, (recs, blocks) ->
            val page = doc.getPage(pi); val pd = page.cosObject
            val sect = elem("Sect", document, pd); kid(document, sect)
            val owners = COSArray()
            var list: COSDictionary? = null
            // tables of this page: Table > TR > TH (first row) / TD; each cell owns its text block
            val cellOf = HashMap<Int, COSDictionary>()
            val tableOfFirst = HashMap<Int, List<List<Int>>>()
            tables(blocks.values.toList()).forEach { t -> tableOfFirst[t.flatten().min()] = t; t.flatten().forEach { cellOf[it] = COSDictionary() } }
            recs.forEachIndexed { m, rec ->
                tableOfFirst[m]?.let { t ->
                    // the table starts at its first text block in content order
                    val table = elem("Table", sect, pd); kid(sect, table); tableCount++
                    t.forEachIndexed { ri, row ->
                        val tr = elem("TR", table, pd); kid(table, tr)
                        row.forEach { mc -> val cell = elem(if (ri == 0) "TH" else "TD", tr, pd); kid(tr, cell); cellOf[mc] = cell }
                    }
                }
                cellOf[m]?.takeIf { it.containsKey(COSName.S) }?.let { cell -> list = null; kid(cell, COSInteger.get(m.toLong())); owners.add(cell); return@forEachIndexed }
                val kind = if (rec == Rec.FIGURE) null else kinds[pi to m] ?: Kind.P
                if (kind != Kind.LI) list = null
                val owner = when (kind) {
                    null -> { figures++; elem("Figure", sect, pd).also { kid(sect, it) } }
                    Kind.LI -> {
                        val l = list ?: elem("L", sect, pd).also { kid(sect, it); list = it; lists++ }
                        val li = elem("LI", l, pd); kid(l, li)
                        items++; elem("LBody", li, pd).also { kid(li, it) }
                    }
                    else -> { if (kind == Kind.P) paras++ else headings++; elem(kind.name, sect, pd).also { kid(sect, it) } }
                }
                kid(owner, COSInteger.get(m.toLong())); owners.add(owner)
            }
            if (recs.isNotEmpty()) { pd.setInt(COSName.getPDFName("StructParents"), nextKey); nums.add(COSInteger.get(nextKey.toLong())); nums.add(owners); nextKey++; tagged++ }
            runCatching {
                page.annotations.forEach { a ->
                    val sub = a.subtype ?: return@forEach
                    if (sub == "Popup") return@forEach
                    val type = when (sub) { "Link" -> "Link"; "Widget" -> "Form"; else -> "Annot" }
                    val e = elem(type, sect, pd)
                    kid(e, COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("OBJR")); setItem(COSName.getPDFName("Obj"), a.cosObject); setItem(COSName.getPDFName("Pg"), pd) })
                    kid(sect, e)
                    if (type == "Link" && !a.cosObject.containsKey(COSName.CONTENTS)) a.cosObject.setString(COSName.CONTENTS, "Link")
                    a.cosObject.setInt(COSName.getPDFName("StructParent"), nextKey)
                    nums.add(COSInteger.get(nextKey.toLong())); nums.add(e); nextKey++; annots++
                }
                if (page.annotations.isNotEmpty()) pd.setItem(COSName.getPDFName("Tabs"), COSName.S)
            }
        }
        root.setItem(COSName.getPDFName("ParentTree"), COSDictionary().apply { setItem(COSName.getPDFName("Nums"), nums) })
        root.setInt(COSName.getPDFName("ParentTreeNextKey"), nextKey)
        cat.setItem(COSName.getPDFName("StructTreeRoot"), root)
        cat.setItem(COSName.getPDFName("MarkInfo"), COSDictionary().apply { setItem(COSName.getPDFName("Marked"), COSBoolean.TRUE) })
        if (!cat.containsKey(COSName.LANG) && lang.isNotBlank()) cat.setItem(COSName.LANG, COSString(lang))
        return Report(doc.numberOfPages, tagged, headings, paras, lists, items, figures, artifacts, annots, pagesWithoutText, wasTagged, tableCount)
    }
}
