package com.smartdocs.editor.core.tools5

/*
 * Accessibility checker: 30 rules in 7 groups (modelled on the PDF/UA + WCAG checks of desktop editors).
 * Facts are gathered from the PDF by AccessInspect (PdfBox); evaluation and the HTML report are plain Kotlin.
 */

enum class AccGroup { DOCUMENT, PAGE, FORMS, ALT, TABLES, LISTS, HEADINGS }

enum class AccStatus { PASS, FAIL, MANUAL, NA }

/** Everything the rules need, counted once over the document. */
data class AccessFacts(
    val pageCount: Int = 0,
    val accessibilityAllowed: Boolean = true,
    val pagesWithoutText: Int = 0,
    val tagged: Boolean = false,              // StructTreeRoot present
    val marked: Boolean = false,              // /MarkInfo /Marked true
    val lang: String? = null,
    val title: String? = null,
    val displayDocTitle: Boolean = false,
    val bookmarks: Int = 0,
    val pagesWithUntaggedContent: Int = 0,    // pages whose content has no marked-content (MCID) at all while tagged
    val annotations: Int = 0,
    val annotationsUntagged: Int = 0,
    val pagesWithoutTabsS: Int = 0,
    val fontsWithoutUnicode: Int = 0,
    val multimedia: Int = 0,
    val multimediaUntagged: Int = 0,
    val javascript: Boolean = false,
    val links: Int = 0,
    val linksUntagged: Int = 0,
    val formFields: Int = 0,
    val formFieldsUntagged: Int = 0,
    val formFieldsWithoutTU: Int = 0,
    val figures: Int = 0,
    val figuresWithoutAlt: Int = 0,
    val nestedAlt: Int = 0,                   // Alt on an element whose descendant also has Alt
    val altWithoutContent: Int = 0,           // element with Alt but no content (MCID/OBJR) under it
    val annotsHiddenByAlt: Int = 0,           // Alt on an element that contains an annotation
    val otherWithoutAlt: Int = 0,             // Formula / Ruby / Warichu / Annot elements without Alt
    val tables: Int = 0,
    val tablesBadRows: Int = 0,               // TR not directly under Table/THead/TBody/TFoot
    val tablesBadCells: Int = 0,              // TH/TD not under TR
    val tablesWithoutHeaders: Int = 0,
    val tablesIrregular: Int = 0,             // rows with different cell counts (no spans)
    val tablesWithoutSummary: Int = 0,
    val lists: Int = 0,
    val listItemsBad: Int = 0,                // LI not under L
    val listBodiesBad: Int = 0,               // Lbl/LBody not under LI
    val headings: Int = 0,
    val headingSkips: Int = 0,                // H1 → H3 jumps
)

data class AccRule(val id: String, val group: AccGroup)

data class AccResult(val rule: AccRule, val status: AccStatus, val count: Int = 0)

object AccessRules {

    val rules: List<AccRule> = listOf(
        // document (8)
        AccRule("perm", AccGroup.DOCUMENT), AccRule("image_only", AccGroup.DOCUMENT), AccRule("tagged", AccGroup.DOCUMENT),
        AccRule("reading_order", AccGroup.DOCUMENT), AccRule("lang", AccGroup.DOCUMENT), AccRule("title", AccGroup.DOCUMENT),
        AccRule("bookmarks", AccGroup.DOCUMENT), AccRule("contrast", AccGroup.DOCUMENT),
        // page content (7)
        AccRule("tagged_content", AccGroup.PAGE), AccRule("tagged_annots", AccGroup.PAGE), AccRule("tab_order", AccGroup.PAGE),
        AccRule("encoding", AccGroup.PAGE), AccRule("multimedia", AccGroup.PAGE), AccRule("scripts", AccGroup.PAGE),
        AccRule("nav_links", AccGroup.PAGE),
        // forms (2)
        AccRule("tagged_fields", AccGroup.FORMS), AccRule("field_desc", AccGroup.FORMS),
        // alternate text (5)
        AccRule("figure_alt", AccGroup.ALT), AccRule("nested_alt", AccGroup.ALT), AccRule("alt_content", AccGroup.ALT),
        AccRule("alt_hides_annot", AccGroup.ALT), AccRule("other_alt", AccGroup.ALT),
        // tables (5)
        AccRule("table_rows", AccGroup.TABLES), AccRule("table_cells", AccGroup.TABLES), AccRule("table_headers", AccGroup.TABLES),
        AccRule("table_regular", AccGroup.TABLES), AccRule("table_summary", AccGroup.TABLES),
        // lists (2)
        AccRule("list_items", AccGroup.LISTS), AccRule("list_body", AccGroup.LISTS),
        // headings (1)
        AccRule("heading_nesting", AccGroup.HEADINGS),
    )

    /** Rules that have a one-tap fix in AccessFix. */
    val fixable = setOf("lang", "title", "tab_order", "tagged", "tagged_content", "field_desc", "bookmarks", "figure_alt")

    private fun failIf(n: Int) = if (n > 0) AccStatus.FAIL else AccStatus.PASS

    fun evaluate(f: AccessFacts): List<AccResult> = rules.map { r ->
        val (st, n) = when (r.id) {
            "perm" -> (if (f.accessibilityAllowed) AccStatus.PASS else AccStatus.FAIL) to 0
            "image_only" -> (if (f.pageCount > 0 && f.pagesWithoutText == f.pageCount) AccStatus.FAIL else AccStatus.PASS) to f.pagesWithoutText
            "tagged" -> (if (f.tagged && f.marked) AccStatus.PASS else AccStatus.FAIL) to 0
            "reading_order" -> AccStatus.MANUAL to 0
            "lang" -> (if (!f.lang.isNullOrBlank()) AccStatus.PASS else AccStatus.FAIL) to 0
            "title" -> (if (!f.title.isNullOrBlank() && f.displayDocTitle) AccStatus.PASS else AccStatus.FAIL) to 0
            "bookmarks" -> (if (f.pageCount <= 20) AccStatus.NA else if (f.bookmarks > 0) AccStatus.PASS else AccStatus.FAIL) to 0
            "contrast" -> AccStatus.MANUAL to 0
            "tagged_content" -> (if (!f.tagged) AccStatus.FAIL else failIf(f.pagesWithUntaggedContent)) to f.pagesWithUntaggedContent
            "tagged_annots" -> (if (f.annotations == 0) AccStatus.NA else failIf(f.annotationsUntagged)) to f.annotationsUntagged
            "tab_order" -> (if (f.annotations == 0 && f.formFields == 0) AccStatus.NA else failIf(f.pagesWithoutTabsS)) to f.pagesWithoutTabsS
            "encoding" -> failIf(f.fontsWithoutUnicode) to f.fontsWithoutUnicode
            "multimedia" -> (if (f.multimedia == 0) AccStatus.NA else failIf(f.multimediaUntagged)) to f.multimediaUntagged
            "scripts" -> (if (f.javascript) AccStatus.MANUAL else AccStatus.NA) to 0
            "nav_links" -> (if (f.links == 0) AccStatus.NA else failIf(f.linksUntagged)) to f.linksUntagged
            "tagged_fields" -> (if (f.formFields == 0) AccStatus.NA else failIf(f.formFieldsUntagged)) to f.formFieldsUntagged
            "field_desc" -> (if (f.formFields == 0) AccStatus.NA else failIf(f.formFieldsWithoutTU)) to f.formFieldsWithoutTU
            "figure_alt" -> (if (f.figures == 0) AccStatus.NA else failIf(f.figuresWithoutAlt)) to f.figuresWithoutAlt
            "nested_alt" -> (if (!f.tagged) AccStatus.NA else failIf(f.nestedAlt)) to f.nestedAlt
            "alt_content" -> (if (!f.tagged) AccStatus.NA else failIf(f.altWithoutContent)) to f.altWithoutContent
            "alt_hides_annot" -> (if (!f.tagged) AccStatus.NA else failIf(f.annotsHiddenByAlt)) to f.annotsHiddenByAlt
            "other_alt" -> (if (!f.tagged) AccStatus.NA else failIf(f.otherWithoutAlt)) to f.otherWithoutAlt
            "table_rows" -> (if (f.tables == 0) AccStatus.NA else failIf(f.tablesBadRows)) to f.tablesBadRows
            "table_cells" -> (if (f.tables == 0) AccStatus.NA else failIf(f.tablesBadCells)) to f.tablesBadCells
            "table_headers" -> (if (f.tables == 0) AccStatus.NA else failIf(f.tablesWithoutHeaders)) to f.tablesWithoutHeaders
            "table_regular" -> (if (f.tables == 0) AccStatus.NA else failIf(f.tablesIrregular)) to f.tablesIrregular
            "table_summary" -> (if (f.tables == 0) AccStatus.NA else if (f.tablesWithoutSummary > 0) AccStatus.MANUAL else AccStatus.PASS) to f.tablesWithoutSummary
            "list_items" -> (if (f.lists == 0 && f.listItemsBad == 0) AccStatus.NA else failIf(f.listItemsBad)) to f.listItemsBad
            "list_body" -> (if (f.lists == 0 && f.listBodiesBad == 0) AccStatus.NA else failIf(f.listBodiesBad)) to f.listBodiesBad
            "heading_nesting" -> (if (f.headings == 0) AccStatus.NA else failIf(f.headingSkips)) to f.headingSkips
            else -> AccStatus.NA to 0
        }
        AccResult(r, st, n)
    }

    fun counts(results: List<AccResult>): Map<AccStatus, Int> = AccStatus.entries.associateWith { s -> results.count { it.status == s } }

    /** Self-contained HTML report. [label] gives the rule title, [statusLabel] / [groupLabel] the localised names. */
    fun htmlReport(
        fileName: String, results: List<AccResult>, rtl: Boolean, title: String,
        label: (String) -> String, statusLabel: (AccStatus) -> String, groupLabel: (AccGroup) -> String,
    ): String = buildString {
        val dir = if (rtl) "rtl" else "ltr"
        append("<!DOCTYPE html><html lang=\"").append(if (rtl) "ar" else "en").append("\" dir=\"").append(dir).append("\"><head><meta charset=\"utf-8\">")
        append("<title>").append(XmpWriter.esc(title)).append("</title><style>body{font-family:sans-serif;margin:24px}table{border-collapse:collapse;width:100%}")
        append("td,th{border:1px solid #ccc;padding:6px;text-align:start}.PASS{color:#1b5e20}.FAIL{color:#b71c1c;font-weight:bold}.MANUAL{color:#e65100}.NA{color:#757575}</style></head><body>")
        append("<h1>").append(XmpWriter.esc(title)).append("</h1><p>").append(XmpWriter.esc(fileName)).append("</p><p>")
        counts(results).forEach { (s, n) -> append(XmpWriter.esc(statusLabel(s))).append(": ").append(n).append(" &nbsp; ") }
        append("</p>")
        AccGroup.entries.forEach { g ->
            val rows = results.filter { it.rule.group == g }
            if (rows.isEmpty()) return@forEach
            append("<h2>").append(XmpWriter.esc(groupLabel(g))).append("</h2><table>")
            rows.forEach { r ->
                append("<tr><td>").append(XmpWriter.esc(label(r.rule.id))).append("</td><td class=\"").append(r.status.name).append("\">")
                append(XmpWriter.esc(statusLabel(r.status)))
                if (r.count > 0) append(" (").append(r.count).append(')')
                append("</td></tr>")
            }
            append("</table>")
        }
        append("</body></html>")
    }
}
