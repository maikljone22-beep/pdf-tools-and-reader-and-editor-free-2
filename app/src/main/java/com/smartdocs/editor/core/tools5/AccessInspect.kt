package com.smartdocs.editor.core.tools5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/** A tagged figure: its structure element, page (or −1), alternate text and optional BBox (PDF points). */
class FigureRef(val elem: COSDictionary, val page: Int, val alt: String?, val bbox: FloatArray?)

/** Structure-tree walker (COS level, RoleMap-aware) gathering [AccessFacts]. */
object AccessInspect {

    private fun roleMap(doc: PDDocument): Map<String, String> {
        val root = doc.documentCatalog.cosObject.getDictionaryObject(N.StructTreeRoot) as? COSDictionary ?: return emptyMap()
        val rm = root.getDictionaryObject(N.RoleMap) as? COSDictionary ?: return emptyMap()
        return rm.keySet().mapNotNull { k -> (rm.getDictionaryObject(k) as? COSName)?.let { k.name to it.name } }.toMap()
    }

    private fun typeOf(e: COSDictionary, roles: Map<String, String>): String {
        var t = (e.getDictionaryObject(N.S) as? COSName)?.name ?: return ""
        var guard = 0
        while (guard++ < 8) { t = roles[t] ?: break }
        return t
    }

    private fun kids(e: COSDictionary): List<COSBase> = when (val k = e.getDictionaryObject(N.K)) {
        null -> emptyList()
        is COSArray -> (0 until k.size()).mapNotNull { k.getObject(it) }
        else -> listOf(k)
    }

    private fun isElem(b: COSBase): Boolean = b is COSDictionary && b.containsKey(N.S) && (b.getDictionaryObject(N.Type).let { it == null || it == N.StructElem })

    private fun hasAlt(e: COSDictionary) = (e.getDictionaryObject(N.Alt) as? COSString)?.string?.isNotBlank() == true ||
        (e.getDictionaryObject(N.n("ActualText")) as? COSString)?.string?.isNotBlank() == true

    private fun pageIndex(doc: PDDocument, e: COSDictionary): Int {
        val pg = e.getDictionaryObject(N.Pg) as? COSDictionary ?: return -1
        return runCatching { doc.pages.indexOf(com.tom_roush.pdfbox.pdmodel.PDPage(pg)) }.getOrDefault(-1)
    }

    private fun attr(e: COSDictionary, key: String): COSBase? {
        fun look(d: COSDictionary?) = d?.getDictionaryObject(N.n(key))
        return when (val a = e.getDictionaryObject(N.Attr)) {
            is COSDictionary -> look(a)
            is COSArray -> (0 until a.size()).firstNotNullOfOrNull { look(a.getObject(it) as? COSDictionary) }
            else -> null
        }
    }

    fun figures(doc: PDDocument): List<FigureRef> {
        val roles = roleMap(doc)
        val root = doc.documentCatalog.cosObject.getDictionaryObject(N.StructTreeRoot) as? COSDictionary ?: return emptyList()
        val out = ArrayList<FigureRef>()
        val seen = HashSet<COSBase>()
        fun walk(e: COSDictionary, depth: Int) {
            if (depth > 60 || !seen.add(e)) return
            if (typeOf(e, roles) == "Figure") {
                val bb = (attr(e, "BBox") as? COSArray)?.toFloatArray()?.takeIf { it.size == 4 }
                out += FigureRef(e, pageIndex(doc, e), (e.getDictionaryObject(N.Alt) as? COSString)?.string, bb)
            }
            kids(e).filter(::isElem).forEach { walk(it as COSDictionary, depth + 1) }
        }
        kids(root).filter(::isElem).forEach { walk(it as COSDictionary, 0) }
        return out
    }

    /** [pages]: FINAL_CHECK_2 #20 checker page range (zero-based); null = the whole document. */
    fun facts(doc: PDDocument, pdf: PdfFacts, textCheckPages: Int = 200, pages: Set<Int>? = null): AccessFacts {
        val cat = doc.documentCatalog.cosObject
        val root = cat.getDictionaryObject(N.StructTreeRoot) as? COSDictionary
        val mark = (cat.getDictionaryObject(N.MarkInfo) as? COSDictionary)?.getDictionaryObject(N.Marked)
        val vp = cat.getDictionaryObject(N.ViewerPreferences) as? COSDictionary
        val roles = roleMap(doc)

        // pages without any text (image-only)
        var noText = 0
        val n = doc.numberOfPages
        runCatching {
            val st = PDFTextStripper()
            for (i in 1..minOf(n, textCheckPages)) { if (pages != null && (i - 1) !in pages) continue; st.startPage = i; st.endPage = i; if (st.getText(doc).isBlank()) noText++ }
            if (pages == null && n > textCheckPages && noText == textCheckPages) noText = n
        }

        // annotations
        var annots = 0; var annotsUntagged = 0; var links = 0; var linksUntagged = 0; var widgets = 0; var widgetsUntagged = 0
        var mediaUntagged = 0; var noTabs = 0; var pagesUntagged = 0
        doc.pages.forEachIndexed { pi, p ->
            if (pages != null && pi !in pages) return@forEachIndexed
            val list = runCatching { p.annotations }.getOrDefault(emptyList())
            var pageHasAnnots = false
            list.forEach { a ->
                val sub = a.subtype ?: ""
                if (sub == "Popup") return@forEach
                pageHasAnnots = true
                val tagged = a.cosObject.containsKey(N.StructParent)
                when (sub) {
                    "Link" -> { links++; if (!tagged) linksUntagged++ }
                    "Widget" -> { widgets++; if (!tagged) widgetsUntagged++ }
                    else -> { annots++; if (!tagged) annotsUntagged++; if (sub in setOf("Screen", "Movie", "Sound", "RichMedia", "3D") && !tagged) mediaUntagged++ }
                }
            }
            if (pageHasAnnots && (p.cosObject.getDictionaryObject(N.Tabs) as? COSName)?.name != "S") noTabs++
            if (root != null && !p.cosObject.containsKey(N.StructParents)) pagesUntagged++
        }
        var fieldsNoTu = 0
        runCatching {
            doc.documentCatalog.acroForm?.fieldTree?.forEach { f ->
                if (f is com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField && f.fieldType != "Sig" && f.alternateFieldName.isNullOrBlank()) fieldsNoTu++
            }
        }

        // structure tree
        var figures = 0; var figuresNoAlt = 0; var nestedAlt = 0; var altNoContent = 0; var altHidesAnnot = 0; var otherNoAlt = 0
        var tables = 0; var badRows = 0; var badCells = 0; var noHeaders = 0; var irregular = 0; var noSummary = 0
        var lists = 0; var badLi = 0; var badBody = 0; var headings = 0; var skips = 0; var lastLevel = 0
        val seen = HashSet<COSBase>()

        class Sub(val content: Boolean, val objr: Boolean, val alt: Boolean, val th: Boolean)

        fun walk(e: COSDictionary, parent: String, depth: Int): Sub {
            if (depth > 60 || !seen.add(e)) return Sub(false, false, false, false)
            // outside the checked page range: counted as plain content, not inspected
            if (pages != null) { val pi = pageIndex(doc, e); if (pi >= 0 && pi !in pages) return Sub(true, false, false, parent == "TR") }
            val t = typeOf(e, roles)
            var content = false; var objr = false; var altBelow = false; var th = t == "TH"
            val rowCells = ArrayList<Int>()
            var spans = false
            kids(e).forEach { k ->
                when {
                    k is COSInteger -> content = true
                    k is COSDictionary && k.getDictionaryObject(N.Type) == N.n("MCR") -> content = true
                    k is COSDictionary && k.getDictionaryObject(N.Type) == N.OBJR -> objr = true
                    isElem(k) -> {
                        val kd = k as COSDictionary
                        val s = walk(kd, t, depth + 1)
                        content = content || s.content; objr = objr || s.objr; altBelow = altBelow || s.alt; th = th || s.th
                        if (t == "TR") {
                            val kt = typeOf(kd, roles)
                            if (kt == "TH" || kt == "TD") { rowCells += 1; if (attr(kd, "ColSpan") != null || attr(kd, "RowSpan") != null) spans = true }
                        }
                    }
                }
            }
            val alt = hasAlt(e)
            if (alt && altBelow) nestedAlt++
            if (alt && !content && !objr) altNoContent++
            if (alt && objr && t !in setOf("Link", "Annot", "Form", "Figure")) altHidesAnnot++
            when (t) {
                "Figure" -> { figures++; if (!alt) figuresNoAlt++ }
                "Formula", "Ruby", "Warichu" -> if (!alt) otherNoAlt++
                "Table" -> {
                    tables++
                    if (!th) noHeaders++
                    if (attr(e, "Summary") == null) noSummary++
                    // regularity: every row has the same number of cells (rows are direct or under THead/TBody/TFoot)
                    val counts = ArrayList<Int>()
                    fun rows(x: COSDictionary, d: Int) {
                        kids(x).filter(::isElem).forEach { r ->
                            val rd = r as COSDictionary
                            when (typeOf(rd, roles)) {
                                "TR" -> counts += kids(rd).count { c -> isElem(c) && typeOf(c as COSDictionary, roles).let { it == "TH" || it == "TD" } }
                                "THead", "TBody", "TFoot" -> if (d < 3) rows(rd, d + 1)
                            }
                        }
                    }
                    rows(e, 0)
                    if (counts.distinct().size > 1 && !spans) irregular++
                }
                "TR" -> if (parent !in setOf("Table", "THead", "TBody", "TFoot")) badRows++
                "TH", "TD" -> if (parent != "TR") badCells++
                "L" -> lists++
                "LI" -> if (parent != "L") badLi++
                "Lbl", "LBody" -> if (parent != "LI") badBody++
            }
            if (t.length == 2 && t[0] == 'H' && t[1] in '1'..'6') {
                val lv = t[1] - '0'
                headings++
                if (lastLevel > 0 && lv > lastLevel + 1) skips++
                lastLevel = lv
            }
            return Sub(content, objr, alt || altBelow, th)
        }
        root?.let { r -> kids(r).filter(::isElem).forEach { walk(it as COSDictionary, "StructTreeRoot", 0) } }

        val outline = runCatching { doc.documentCatalog.documentOutline?.children()?.count() ?: 0 }.getOrDefault(0)
        return AccessFacts(
            pageCount = n,
            accessibilityAllowed = runCatching { doc.currentAccessPermission.canExtractForAccessibility() }.getOrDefault(true),
            pagesWithoutText = noText,
            tagged = root != null,
            marked = (mark as? COSBoolean)?.value == true,
            lang = (cat.getDictionaryObject(N.Lang) as? COSString)?.string,
            title = doc.documentInformation.title,
            displayDocTitle = (vp?.getDictionaryObject(N.DisplayDocTitle) as? COSBoolean)?.value == true,
            bookmarks = outline,
            pagesWithUntaggedContent = pagesUntagged,
            annotations = annots + links + widgets, annotationsUntagged = annotsUntagged,
            pagesWithoutTabsS = noTabs,
            fontsWithoutUnicode = pdf.fontsWithoutUnicode.size,
            multimedia = pdf.multimedia, multimediaUntagged = mediaUntagged,
            javascript = pdf.javascript,
            links = links, linksUntagged = linksUntagged,
            formFields = widgets, formFieldsUntagged = widgetsUntagged, formFieldsWithoutTU = fieldsNoTu,
            figures = figures, figuresWithoutAlt = figuresNoAlt, nestedAlt = nestedAlt, altWithoutContent = altNoContent,
            annotsHiddenByAlt = altHidesAnnot, otherWithoutAlt = otherNoAlt,
            tables = tables, tablesBadRows = badRows, tablesBadCells = badCells, tablesWithoutHeaders = noHeaders,
            tablesIrregular = irregular, tablesWithoutSummary = noSummary,
            lists = lists, listItemsBad = badLi, listBodiesBad = badBody,
            headings = headings, headingSkips = skips,
        )
    }
}
