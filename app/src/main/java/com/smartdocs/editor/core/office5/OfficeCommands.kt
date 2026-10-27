package com.smartdocs.editor.core.office5

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * office6: the Office command catalogue. Every command has a stable id ("word.bold", "sheet.sum", "slide.new",
 * "find", "print"…) that the ribbons, the command registry (ui/office/OfficeCommandRegistry) and the AI
 * command box share. The AI gets [prompt] and answers a JSON list of [OfficeCall]s that [parse] reads.
 */
enum class OfficeKind { WORD, SHEET, SLIDE, ANY }

data class OfficeCommandSpec(val id: String, val kind: OfficeKind, val args: List<String> = emptyList(), val help: String)

data class OfficeCall(val id: String, val args: Map<String, String> = emptyMap()) {
    fun arg(k: String): String? = args[k]?.takeIf { it.isNotBlank() }
    fun int(k: String): Int? = arg(k)?.trim()?.toIntOrNull()
}

object OfficeCommandCatalog {
    private fun w(id: String, help: String, vararg args: String) = OfficeCommandSpec(id, OfficeKind.WORD, args.toList(), help)
    private fun s(id: String, help: String, vararg args: String) = OfficeCommandSpec(id, OfficeKind.SHEET, args.toList(), help)
    private fun p(id: String, help: String, vararg args: String) = OfficeCommandSpec(id, OfficeKind.SLIDE, args.toList(), help)
    private fun a(id: String, help: String, vararg args: String) = OfficeCommandSpec(id, OfficeKind.ANY, args.toList(), help)

    val all: List<OfficeCommandSpec> = listOf(
        // shared
        a("find", "open find / replace with a query", "query"),
        a("replace_all", "replace every occurrence in the whole document", "find", "replace"),
        a("print", "print the document"),
        a("read_aloud", "read the document aloud"),
        a("undo", "undo the last change"), a("redo", "redo"),
        a("export_pdf", "export the document to PDF"),
        // word
        w("word.select", "select the first paragraph that contains the text (do this before formatting a part)", "text"),
        w("word.select_paragraph", "select paragraph number n (1-based)", "n"),
        w("word.bold", "toggle bold of the selection"), w("word.italic", "toggle italic"), w("word.underline", "toggle underline"),
        w("word.strike", "toggle strikethrough"), w("word.clear_format", "clear character formatting"),
        w("word.font_size", "set font size in points", "size"), w("word.color", "set text colour (hex RRGGBB)", "hex"),
        w("word.align.start", "align start"), w("word.align.center", "center"), w("word.align.end", "align end"), w("word.align.justify", "justify"),
        w("word.rtl", "right-to-left paragraph"), w("word.ltr", "left-to-right paragraph"),
        w("word.title", "Title style"), w("word.heading1", "Heading 1 style"), w("word.heading2", "Heading 2 style"), w("word.heading3", "Heading 3 style"),
        w("word.normal", "Normal style"), w("word.quote", "Quote style"),
        w("word.bullets", "toggle bulleted list"), w("word.numbering", "toggle numbered list"),
        w("word.insert_paragraph", "insert a new paragraph after the selection with the text", "text"),
        w("word.insert_text", "insert text at the cursor", "text"),
        w("word.insert_table", "insert a table", "rows", "cols"),
        w("word.page_break", "insert a page break"),
        w("word.delete_block", "delete the selected paragraph / table"),
        // sheet
        s("sheet.select", "select a cell or range in A1 notation", "range"),
        s("sheet.set", "type a value or =formula into a cell", "cell", "value"),
        s("sheet.bold", "toggle bold of the selection"), s("sheet.italic", "toggle italic"),
        s("sheet.fill", "fill colour of the selection (hex RRGGBB)", "hex"),
        s("sheet.number_format", "number format code of the selection (e.g. #,##0.00 or 0%)", "code"),
        s("sheet.align", "horizontal alignment: left, center, right", "align"),
        s("sheet.sum", "AutoSum the selection"), s("sheet.average", "AVERAGE of the selection"), s("sheet.count", "COUNT of the selection"),
        s("sheet.max", "MAX of the selection"), s("sheet.min", "MIN of the selection"),
        s("sheet.insert_row", "insert rows above the selection"), s("sheet.insert_col", "insert columns before the selection"),
        s("sheet.delete_row", "delete the selected rows"), s("sheet.delete_col", "delete the selected columns"),
        s("sheet.sort_asc", "sort ascending by the active column"), s("sheet.sort_desc", "sort descending by the active column"),
        s("sheet.clear", "clear the selection contents"),
        s("sheet.new_sheet", "add a worksheet", "name"),
        s("sheet.goto_sheet", "show worksheet number n (1-based)", "n"),
        // slides
        p("slide.new", "add a slide after the current one"), p("slide.duplicate", "duplicate the current slide"), p("slide.delete", "delete the current slide"),
        p("slide.go", "go to slide n (1-based)", "n"),
        p("slide.add_text", "add a text box with the text", "text"),
        p("slide.set_text", "replace the text of the selected shape", "text"),
        p("slide.background", "slide background colour (hex RRGGBB)", "hex"),
        p("slide.transition", "transition of the current slide: fade, push, wipe, cover, cut or none", "kind"),
        p("slide.bold", "bold text of the selected shape"),
        p("slide.align.center", "center text of the selected shape"),
        p("slide.move", "move the current slide by delta (-1 = earlier)", "delta"),
        p("slide.show", "start the slide show from the current slide"),
    )

    private val byId = all.associateBy { it.id }
    fun spec(id: String): OfficeCommandSpec? = byId[id]

    fun forKind(k: OfficeKind): List<OfficeCommandSpec> = all.filter { it.kind == OfficeKind.ANY || it.kind == k }

    /** System prompt for the AI command box. */
    fun prompt(k: OfficeKind): String = buildString {
        append("You turn a user's request about the open ")
        append(when (k) { OfficeKind.WORD -> "Word document"; OfficeKind.SHEET -> "spreadsheet"; OfficeKind.SLIDE -> "presentation"; else -> "document" })
        append(" into app commands. Answer ONLY with a JSON array like [{\"id\":\"word.bold\",\"args\":{}}]. ")
        append("Use only these ids (args in parentheses):\n")
        forKind(k).forEach { c -> append("- ").append(c.id); if (c.args.isNotEmpty()) append(" (").append(c.args.joinToString(", ")).append(")"); append(": ").append(c.help).append('\n') }
        append("Run them in order. Select before formatting a part. Answer [] when no command fits. The user may write in Arabic.")
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses the model answer: a JSON array (possibly in prose or code fences) or {"commands":[…]}. Unknown ids are dropped. */
    fun parse(answer: String, k: OfficeKind = OfficeKind.ANY): List<OfficeCall> {
        val t = answer.trim()
        val arr: JsonArray = run {
            val s = t.indexOf('['); val e = t.lastIndexOf(']')
            if (s >= 0 && e > s) runCatching { json.parseToJsonElement(t.substring(s, e + 1)) as? JsonArray }.getOrNull() else null
        } ?: run {
            val s = t.indexOf('{'); val e = t.lastIndexOf('}')
            if (s >= 0 && e > s) runCatching { (json.parseToJsonElement(t.substring(s, e + 1)) as? JsonObject)?.get("commands") as? JsonArray }.getOrNull() else null
        } ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: (o["command"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val spec = spec(id) ?: return@mapNotNull null
            if (k != OfficeKind.ANY && spec.kind != OfficeKind.ANY && spec.kind != k) return@mapNotNull null
            val args = (o["args"] as? JsonObject)?.mapNotNull { (key, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { key to it } }?.toMap() ?: emptyMap()
            OfficeCall(id, args)
        }
    }

    /** Hex colour from an AI argument ("#ff0000", "FF0000", "red"). */
    fun hex(v: String?): String? {
        val x = v?.trim()?.removePrefix("#")?.uppercase() ?: return null
        if (x.length == 6 && x.all { it.isDigit() || it in 'A'..'F' }) return x
        return mapOf("RED" to "FF0000", "GREEN" to "00B050", "BLUE" to "0070C0", "YELLOW" to "FFFF00", "BLACK" to "000000", "WHITE" to "FFFFFF",
            "ORANGE" to "FFC000", "PURPLE" to "7030A0", "GRAY" to "808080", "GREY" to "808080",
            "أحمر" to "FF0000", "أخضر" to "00B050", "أزرق" to "0070C0", "أصفر" to "FFFF00", "أسود" to "000000", "أبيض" to "FFFFFF")[x]
    }
}

/** Read-aloud chunking shared by the Office editors: splits long text into sentences that fit TTS limits. */
object OfficeSpeech {
    const val MAX = 3500

    fun chunks(text: String, max: Int = MAX): List<String> {
        val t = text.trim(); if (t.isEmpty()) return emptyList()
        if (t.length <= max) return listOf(t)
        val out = ArrayList<String>(); val sb = StringBuilder()
        for (sentence in t.split(Regex("(?<=[.!?؟۔])\\s+|\\n+"))) {
            if (sb.length + sentence.length + 1 > max && sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
            if (sentence.length > max) sentence.chunked(max).forEach { out.add(it) } else { if (sb.isNotEmpty()) sb.append(' '); sb.append(sentence) }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** One spoken line per spreadsheet row: "A1 value, B1 value" style without empty cells. */
    fun sheetRows(book: SheetBook, s: Sheet5, range: CRange): List<String> {
        val out = ArrayList<String>()
        val used = s.usedRange() ?: return out
        val r2 = minOf(range.r2, used.r2); val c2 = minOf(range.c2, used.c2)
        for (r in range.r1..r2) {
            val vals = (range.c1..c2).mapNotNull { c -> runCatching { book.display(s, r, c).text }.getOrNull()?.takeIf { it.isNotBlank() } }
            if (vals.isNotEmpty()) out.add(vals.joinToString("، "))
        }
        return out
    }

    fun hasArabic(t: String) = t.any { it in '؀'..'ۿ' }
}
