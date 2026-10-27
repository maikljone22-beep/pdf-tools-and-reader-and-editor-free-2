package com.smartdocs.editor.ui.office

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.office5.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// =====================================================================================================
// office6: print, read aloud, find & replace bars for Excel / PowerPoint, the command registry and the
// AI command box of the Office editors.
// =====================================================================================================

// ---------------- find & replace bars ----------------

@Composable
fun SheetFindBar(s: SheetState) {
    val ctx = LocalContext.current
    val ms = s.matches
    FindBar(s.query, { s.query = it; s.matchIndex = 0; s.bump() }, s.replacement, { s.replacement = it; s.bump() }, ms.size, s.matchIndex.coerceAtMost((ms.size - 1).coerceAtLeast(0)),
        onPrev = { s.gotoMatch(s.matchIndex - 1) }, onNext = { s.gotoMatch(s.matchIndex + 1) },
        onReplaceOne = { s.replaceCurrent() },
        onReplaceAll = { val n = s.replaceAll(); Toast.makeText(ctx, ctx.getString(R.string.of6_replaced, n), Toast.LENGTH_SHORT).show() },
        onClose = { s.findOpen = false; s.bump() },
        matchCase = s.findOpts.matchCase, onMatchCase = { s.findOpts = s.findOpts.copy(matchCase = it); s.matchIndex = 0; s.bump() },
        whole = s.findOpts.whole, onWhole = { s.findOpts = s.findOpts.copy(whole = it); s.matchIndex = 0; s.bump() }, wholeLabel = R.string.of6_whole_cell)
}

@Composable
fun SlideFindBar(s: SlideState) {
    val ctx = LocalContext.current
    val ms = s.matches
    FindBar(s.query, { s.query = it; s.matchIndex = 0; s.bump() }, s.replacement, { s.replacement = it; s.bump() }, ms.size, s.matchIndex.coerceAtMost((ms.size - 1).coerceAtLeast(0)),
        onPrev = { s.gotoMatch(s.matchIndex - 1) }, onNext = { s.gotoMatch(s.matchIndex + 1) },
        onReplaceOne = { s.replaceCurrent() },
        onReplaceAll = { val n = s.replaceAll(); Toast.makeText(ctx, ctx.getString(R.string.of6_replaced, n), Toast.LENGTH_SHORT).show() },
        onClose = { s.findOpen = false; s.bump() },
        matchCase = s.findOpts.matchCase, onMatchCase = { s.findOpts = s.findOpts.copy(matchCase = it); s.matchIndex = 0; s.bump() },
        whole = s.findOpts.whole, onWhole = { s.findOpts = s.findOpts.copy(whole = it); s.matchIndex = 0; s.bump() })
}

// ---------------- print ----------------

object OfficePrint {
    /** Converts the current state to a temporary PDF (same pipeline as Export to PDF) and opens the system print dialog. */
    suspend fun print(ctx: Context, app: com.smartdocs.editor.SmartDocsApp, host: OfficeHost) {
        val src = withContext(Dispatchers.IO) {
            val dir = File(app.documents.tempDir, "o6print_${System.nanoTime()}").apply { mkdirs() }
            val ext = if (host is ImportState) "docx" else host.ext
            File(dir, host.file.nameWithoutExtension + "." + ext).also { host.saveTo(it) }
        }
        val out = File(src.parentFile, host.file.nameWithoutExtension + ".pdf")
        com.smartdocs.editor.core.tools5.ToPdf.convert(ctx, src, out)
        withContext(Dispatchers.Main) { com.smartdocs.editor.core.util.PdfPrint.print(ctx, out, host.file.nameWithoutExtension) }
    }
}

// ---------------- read aloud ----------------

/** One spoken piece; [onStart] moves the editor to it (scroll to the paragraph, select the row, show the slide). */
class SpeakItem(val text: String, val onStart: () -> Unit = {})

/** System TextToSpeech queue for the Office editors (Arabic / other language picked per piece). */
class OfficeSpeaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: Int? = null
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext, this)
    var active by mutableStateOf(false); private set
    var index by mutableIntStateOf(-1); private set
    var items: List<SpeakItem> = emptyList(); private set

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) { main.post { active = false }; return }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { next(utteranceId) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { next(utteranceId) } }
        })
        main.post { pending?.let { pending = null; speak(it) } }
    }

    fun start(list: List<SpeakItem>) {
        stop()
        items = list.flatMap { it2 -> OfficeSpeech.chunks(it2.text).mapIndexed { k, t -> SpeakItem(t, if (k == 0) it2.onStart else ({})) } }
        if (items.isEmpty()) return
        active = true
        if (!ready) pending = 0 else speak(0)
    }

    private fun speak(i: Int) {
        if (!active || i !in items.indices) { stop(); return }
        index = i
        runCatching { items[i].onStart() }
        val t = items[i].text
        runCatching { tts.setLanguage(if (OfficeSpeech.hasArabic(t)) Locale("ar") else Locale.getDefault()) }
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "o6_$i")
    }

    private fun next(id: String?) {
        if (!active) return
        val i = id?.removePrefix("o6_")?.toIntOrNull() ?: return
        if (i == index) speak(i + 1)
    }

    fun stop() { active = false; index = -1; pending = null; runCatching { tts.stop() } }

    fun shutdown() { stop(); runCatching { tts.shutdown() } }
}

/** What to read for each editor, starting at the user's position. */
fun officeSpeechItems(host: OfficeHost, scope: CoroutineScope): List<SpeakItem> = when (host) {
    is WordState -> {
        val from = maxOf(host.focus, host.cell?.block ?: -1, 0)
        host.blocks.withIndex().drop(from).mapNotNull { (i, b) ->
            val t = when (b) {
                is WBlock.Para -> b.p.text
                is WBlock.Table -> WordTables.rows(b.elem).joinToString("\n") { r -> WordTables.cells(r).joinToString("، ") { WordTables.cellText(it) } }
                is WBlock.Other -> b.text
                else -> ""
            }.replace(WordRuns.OBJ.toString(), "").trim()
            t.takeIf { it.isNotEmpty() }?.let { SpeakItem(it) { scope.launch { runCatching { host.list.animateScrollToItem(i) } } } }
        }
    }
    is SheetState -> {
        val sh = host.sheet
        val used = sh.usedRange()
        val range = if (host.sel.size > 1 && !host.wholeColumns && !host.wholeRows) host.sel else used?.let { CRange(0, 0, it.r2, it.c2) }
        if (range == null) emptyList() else (range.r1..minOf(range.r2, used?.r2 ?: range.r2)).mapNotNull { r ->
            OfficeSpeech.sheetRows(host.wb.book, sh, CRange(r, range.c1, r, range.c2)).firstOrNull()?.let { t ->
                SpeakItem(t) { host.sel = CRange(r, range.c1, r, minOf(range.c2, used?.c2 ?: range.c2)); host.reveal = r to range.c1; host.bump() }
            }
        }
    }
    is SlideState -> (host.current until host.doc.slideCount).mapNotNull { i ->
        SlideFind.slideText(host.doc, i).takeIf { it.isNotBlank() }?.let { SpeakItem(it) { host.go(i) } }
    }
    is TextState -> OfficeSpeech.chunks(host.value.text).map { SpeakItem(it) }
    else -> emptyList()
}

@Composable
fun OfficeReadAloudBar(speaker: OfficeSpeaker, modifier: Modifier = Modifier) {
    if (!speaker.active) return
    Surface(modifier.fillMaxWidth(), tonalElevation = 4.dp, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.RecordVoiceOver, null)
            Spacer(Modifier.width(8.dp))
            Text(speaker.items.getOrNull(speaker.index)?.text.orEmpty(), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.of6_reading_item, speaker.index + 1, speaker.items.size), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp))
            IconButton(onClick = { speaker.stop() }) { Icon(Icons.Default.Stop, stringResource(R.string.of6_stop_reading)) }
        }
    }
}

// ---------------- command registry ----------------

/** Chrome-level actions the registry can trigger (print, read aloud, export). Set by OfficeChrome. */
interface OfficeUi {
    fun print()
    fun readAloud()
    fun exportPdf()
}

/**
 * Every Office command by id ("word.bold", "sheet.sum", "slide.new", "find", "print"…, see [OfficeCommandCatalog]).
 * `OfficeCommandRegistry.run("word.bold")` acts on the open editor; the AI command box runs the AI's calls here.
 * Each command goes through the editor's own undo (one step per command).
 */
object OfficeCommandRegistry {
    var host: OfficeHost? = null
    var ui: OfficeUi? = null

    fun kindOf(h: OfficeHost?): OfficeKind = when (h) { is WordState -> OfficeKind.WORD; is SheetState -> OfficeKind.SHEET; is SlideState -> OfficeKind.SLIDE; else -> OfficeKind.ANY }

    fun run(id: String, args: Map<String, String> = emptyMap()): Boolean { val h = host ?: return false; return run(h, OfficeCall(id, args)) }

    fun run(h: OfficeHost, c: OfficeCall): Boolean = try { dispatch(h, c) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }

    private fun dispatch(h: OfficeHost, c: OfficeCall): Boolean {
        when (c.id) {
            "undo" -> { if (!h.canUndo) return false; h.undo(); return true }
            "redo" -> { if (!h.canRedo) return false; h.redo(); return true }
            "print" -> return ui?.print() != null
            "read_aloud" -> return ui?.readAloud() != null
            "export_pdf" -> return ui?.exportPdf() != null
            "find" -> return openFind(h, c.arg("query").orEmpty())
            "replace_all" -> {
                val f = c.arg("find") ?: return false; val r = c.args["replace"].orEmpty()
                return when (h) {
                    is WordState -> { h.query = f; h.replacement = r; h.replaceAll() > 0 }
                    is SheetState -> { h.query = f; h.replacement = r; h.replaceAll() > 0 }
                    is SlideState -> { h.query = f; h.replacement = r; h.replaceAll() > 0 }
                    else -> false
                }
            }
        }
        return when (h) {
            is WordState -> word(h, c)
            is SheetState -> sheet(h, c)
            is SlideState -> slide(h, c)
            else -> false
        }
    }

    private fun openFind(h: OfficeHost, q: String): Boolean {
        when (h) {
            is WordState -> { h.findOpen = true; h.query = q; h.matchIndex = 0; h.bump(); if (q.isNotEmpty()) h.gotoMatch(0) }
            is SheetState -> { h.findOpen = true; h.query = q; h.matchIndex = 0; h.bump(); if (q.isNotEmpty()) h.gotoMatch(0) }
            is SlideState -> { h.findOpen = true; h.query = q; h.matchIndex = 0; h.bump(); if (q.isNotEmpty()) h.gotoMatch(0) }
            else -> return false
        }
        return true
    }

    private fun word(s: WordState, c: OfficeCall): Boolean {
        fun fmt(block: () -> Unit): Boolean { if (!s.hasTarget) return false; block(); return true }
        when (c.id) {
            "word.select" -> {
                val t = c.arg("text") ?: return false
                val m = s.doc.find(t).firstOrNull() ?: return false
                val top = s.blocks.indexOfFirst { it.elem === m.paragraph }
                if (top < 0) { s.query = t; s.gotoMatch(0); return true }
                s.focusParagraph(top); s.field = s.field.copy(selection = TextRange(m.start, m.end)); s.bump(); return true
            }
            "word.select_paragraph" -> {
                val n = c.int("n") ?: return false
                val paras = s.blocks.withIndex().filter { it.value is WBlock.Para }.map { it.index }
                val i = paras.getOrNull(n - 1) ?: return false
                s.focusParagraph(i); s.field = s.field.copy(selection = TextRange(0, s.field.text.length)); s.bump(); return true
            }
            "word.bold" -> return fmt { s.toggle({ it.bold }) { r, on -> WordFormat.setBold(r, on) } }
            "word.italic" -> return fmt { s.toggle({ it.italic }) { r, on -> WordFormat.setItalic(r, on) } }
            "word.underline" -> return fmt { s.toggle({ it.underline }) { r, on -> WordFormat.setUnderline(r, on) } }
            "word.strike" -> return fmt { s.toggle({ it.strike }) { r, on -> WordFormat.setStrike(r, on) } }
            "word.clear_format" -> return fmt { s.clearFormatting() }
            "word.font_size" -> { val pt = c.arg("size")?.toFloatOrNull()?.coerceIn(1f, 400f) ?: return false; return fmt { s.format { WordFormat.setSize(it, pt) } } }
            "word.color" -> { val hx = OfficeCommandCatalog.hex(c.arg("hex")) ?: return false; return fmt { s.format { WordFormat.setColor(it, hx) } } }
            "word.align.start" -> return fmt { s.paragraphs { WordFormat.setAlign(it, PAlign.START) } }
            "word.align.center" -> return fmt { s.paragraphs { WordFormat.setAlign(it, PAlign.CENTER) } }
            "word.align.end" -> return fmt { s.paragraphs { WordFormat.setAlign(it, PAlign.END) } }
            "word.align.justify" -> return fmt { s.paragraphs { WordFormat.setAlign(it, PAlign.JUSTIFY) } }
            "word.rtl" -> return fmt { s.paragraphs { WordFormat.setBidi(it, true) } }
            "word.ltr" -> return fmt { s.paragraphs { WordFormat.setBidi(it, false) } }
            "word.title" -> return fmt { s.applyStyle(WordParts.StyleKind.TITLE) }
            "word.heading1" -> return fmt { s.applyStyle(WordParts.StyleKind.HEADING1) }
            "word.heading2" -> return fmt { s.applyStyle(WordParts.StyleKind.HEADING2) }
            "word.heading3" -> return fmt { s.applyStyle(WordParts.StyleKind.HEADING3) }
            "word.normal" -> return fmt { s.applyStyle(WordParts.StyleKind.NORMAL) }
            "word.quote" -> return fmt { s.applyStyle(WordParts.StyleKind.QUOTE) }
            "word.bullets" -> return fmt { s.toggleList(true) }
            "word.numbering" -> return fmt { s.toggleList(false) }
            "word.insert_paragraph" -> {
                s.insertParagraph()
                val t = c.args["text"].orEmpty()
                if (t.isNotEmpty() && s.focus >= 0) { s.onField(TextFieldValue(t, TextRange(t.length))); s.commitPending() }
                return true
            }
            "word.insert_text" -> { val t = c.arg("text") ?: return false; if (s.focus < 0 && s.cell == null) return false; s.insertAtCursor(t); s.commitPending(); return true }
            "word.insert_table" -> { s.insertTable((c.int("rows") ?: 2).coerceIn(1, 200), (c.int("cols") ?: 2).coerceIn(1, 60)); return true }
            "word.page_break" -> { s.insertPageBreak(); return true }
            "word.delete_block" -> { if (s.anchor == null) return false; s.deleteBlock(); return true }
        }
        return false
    }

    private fun sheet(s: SheetState, c: OfficeCall): Boolean {
        when (c.id) {
            "sheet.select" -> {
                val r = A1.parseRange(c.arg("range")?.uppercase() ?: return false) ?: return false
                s.select(r.r1, r.c1); if (r.size > 1) { s.sel = r; s.bump() }; s.reveal = r.r1 to r.c1; return true
            }
            "sheet.set" -> {
                val ref = A1.parseCell(c.arg("cell")?.uppercase() ?: return false) ?: return false
                val v = c.args["value"].orEmpty()
                val err = s.change { s.wb.setInput(s.sheet, ref.row, ref.col, v) }
                if (err == null) s.select(ref.row, ref.col)
                return err == null
            }
            "sheet.bold" -> { val on = !s.fmt.bold; s.format { it.copy(bold = on) }; return true }
            "sheet.italic" -> { val on = !s.fmt.italic; s.format { it.copy(italic = on) }; return true }
            "sheet.fill" -> { val hx = OfficeCommandCatalog.hex(c.arg("hex")) ?: return false; s.format { it.copy(fill = hx) }; return true }
            "sheet.number_format" -> { val code = c.arg("code") ?: return false; s.format { it.copy(numFmt = code) }; return true }
            "sheet.align" -> {
                val a = when (c.arg("align")?.lowercase()) { "left", "start", "يمين" -> "left"; "center", "centre", "وسط" -> "center"; "right", "end", "يسار" -> "right"; else -> return false }
                s.format { it.copy(hAlign = a) }; return true
            }
            "sheet.sum" -> { s.autoFunction("SUM"); s.commitPending(); return true }
            "sheet.average" -> { s.autoFunction("AVERAGE"); s.commitPending(); return true }
            "sheet.count" -> { s.autoFunction("COUNT"); s.commitPending(); return true }
            "sheet.max" -> { s.autoFunction("MAX"); s.commitPending(); return true }
            "sheet.min" -> { s.autoFunction("MIN"); s.commitPending(); return true }
            "sheet.insert_row" -> { s.change { s.wb.shiftRows(s.sheet, s.sel.r1, s.sel.rows.coerceAtMost(1000)) }; return true }
            "sheet.insert_col" -> { s.change { s.wb.shiftCols(s.sheet, s.sel.c1, s.sel.cols.coerceAtMost(1000)) }; return true }
            "sheet.delete_row" -> { s.change { s.wb.shiftRows(s.sheet, s.sel.r1, -s.sel.rows) }; return true }
            "sheet.delete_col" -> { s.change { s.wb.shiftCols(s.sheet, s.sel.c1, -s.sel.cols) }; return true }
            "sheet.sort_asc" -> { s.sort(true); return true }
            "sheet.sort_desc" -> { s.sort(false); return true }
            "sheet.clear" -> { s.clear(); return true }
            "sheet.new_sheet" -> {
                val name = s.wb.uniqueName(c.arg("name") ?: "Sheet")
                s.change { s.wb.addSheet(name) }
                s.switchSheet(s.wb.book.sheets.size - 1); return true
            }
            "sheet.goto_sheet" -> { val n = c.int("n") ?: return false; if (n - 1 !in s.wb.book.sheets.indices) return false; s.switchSheet(n - 1); return true }
        }
        return false
    }

    private fun slide(s: SlideState, c: OfficeCall): Boolean {
        when (c.id) {
            "slide.new" -> { s.newSlide(); return true }
            "slide.duplicate" -> { s.duplicate(); return true }
            "slide.delete" -> { if (s.doc.slideCount <= 1) return false; s.deleteSlide(); return true }
            "slide.go" -> { val n = c.int("n") ?: return false; if (n - 1 !in 0 until s.doc.slideCount) return false; s.go(n - 1); return true }
            "slide.add_text" -> { val t = c.arg("text") ?: return false; s.addTextBox(t); s.commitPending(); return true }
            "slide.set_text" -> {
                val e = s.selected ?: return false; val t = c.args["text"].orEmpty()
                if (s.editingText) s.commitText()
                s.doc.changeSlide(s.current) { s.doc.setShapeText(e, t) }; s.bump(); return true
            }
            "slide.background" -> { val hx = OfficeCommandCatalog.hex(c.arg("hex")) ?: return false; s.background(hx); return true }
            "slide.transition" -> {
                val k = c.arg("kind")?.lowercase() ?: return false
                if (k !in setOf("fade", "push", "wipe", "cover", "cut", "none")) return false
                s.transition(if (k == "none") null else k, false); return true
            }
            "slide.bold" -> { if (s.selected == null) return false; s.formatText { it.set("b", "1") }; return true }
            "slide.align.center" -> { if (s.selected == null) return false; s.align("ctr"); return true }
            "slide.move" -> { val d = c.int("delta") ?: return false; val to = s.current + d; if (to !in 0 until s.doc.slideCount) return false; s.moveSlide(d); return true }
            "slide.show" -> { s.commitPending(); s.showFrom = s.current; s.bump(); return true }
        }
        return false
    }
}

// ---------------- AI command box ----------------

private enum class AiMode { COMMANDS, REWRITE, FORMULA }

/** Short description of where the user is, so the AI can address it. */
private fun aiContext(h: OfficeHost): String = when (h) {
    is WordState -> {
        val paras = h.blocks.count { it is WBlock.Para }
        val sel = h.focusedPara?.let { WordRuns.text(it) }?.take(600)
        "The document has $paras paragraphs." + (sel?.let { "\nSelected paragraph: \"$it\"" } ?: "\nNothing is selected.") +
            "\nFirst paragraphs: " + h.blocks.filterIsInstance<WBlock.Para>().map { it.p.text }.filter { it.isNotBlank() }.take(8).joinToString(" | ") { it.take(80) }
    }
    is SheetState -> {
        val used = h.sheet.usedRange()
        val header = used?.let { u -> (u.c1..minOf(u.c2, u.c1 + 25)).joinToString(", ") { c -> A1.colName(c) + "=" + runCatching { h.wb.book.display(h.sheet, u.r1, c).text }.getOrDefault("") } }.orEmpty()
        "Sheet \"${h.sheet.name}\". Active cell ${A1.name(h.row, h.col)}, selection ${h.sel.a1()}. Used range ${used?.a1() ?: "empty"}. First row: $header"
    }
    is SlideState -> "Slide ${h.current + 1} of ${h.doc.slideCount}. Text: " + SlideFind.slideText(h.doc, h.current, withNotes = false).take(600) + (if (h.selected != null) "\nA shape is selected." else "")
    else -> ""
}

@Composable
fun OfficeAiDialog(host: OfficeHost, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    var mode by remember { mutableStateOf(AiMode.COMMANDS) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var calls by remember { mutableStateOf<List<OfficeCall>?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val kind = OfficeCommandRegistry.kindOf(host)

    fun ask() {
        val q = input.trim(); if (q.isEmpty() || busy) return
        if (!AiClient.available(settings)) { error = ctx.getString(R.string.ai6_not_configured); return }
        busy = true; error = null; calls = null; result = null
        scope.launch {
            try {
                when (mode) {
                    AiMode.COMMANDS -> {
                        val a = AiCalls.ask(ctx, client, OfficeCommandCatalog.prompt(kind), aiContext(host) + "\n\nRequest: " + q)
                        val list = OfficeCommandCatalog.parse(a, kind)
                        if (list.isEmpty()) error = ctx.getString(R.string.of6_ai_none) else calls = list
                    }
                    AiMode.REWRITE -> {
                        val para = (host as? WordState)?.focusedPara?.let { WordRuns.text(it) }
                        if (para.isNullOrBlank()) { error = ctx.getString(R.string.of6_ai_rewrite_need_para); return@launch }
                        result = AiCalls.ask(ctx, client, "Rewrite the paragraph as the user asks. Keep its language unless asked otherwise. Answer ONLY with the new paragraph text, no quotes, no comments.",
                            "Instruction: $q\n\nParagraph:\n$para").trim().removeSurrounding("\"")
                    }
                    AiMode.FORMULA -> {
                        val f = AiCalls.ask(ctx, client, "You write one spreadsheet formula (Excel syntax, English function names, comma separators) for the active cell. Answer ONLY with the formula starting with =.",
                            aiContext(host) + "\n\nWhat the formula must do: " + q).trim().lines().firstOrNull { it.trim().startsWith("=") }?.trim()
                        if (f == null) error = ctx.getString(R.string.of6_ai_none) else result = f.removeSurrounding("`")
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { error = com.smartdocs.editor.core.ai6.AiMedia.userMessage(ctx, e)
            } finally { busy = false }
        }
    }

    fun apply() {
        when (mode) {
            AiMode.COMMANDS -> {
                val list = calls ?: return
                val ok = list.count { OfficeCommandRegistry.run(host, it) }
                Toast.makeText(ctx, ctx.getString(R.string.of6_ai_applied, ok, list.size), Toast.LENGTH_SHORT).show()
                onDismiss()
            }
            AiMode.REWRITE -> {
                val s = host as? WordState ?: return; val t = result ?: return
                if (s.focus < 0) { error = ctx.getString(R.string.of6_ai_rewrite_need_para); return }
                s.commitPending(); s.onField(TextFieldValue(t, TextRange(t.length))); s.commitPending()
                onDismiss()
            }
            AiMode.FORMULA -> {
                val s = host as? SheetState ?: return; val f = result ?: return
                val err = s.change { s.wb.setInput(s.sheet, s.row, s.col, f) }
                if (err != null) error = err else { s.select(s.row, s.col); onDismiss() }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.AutoAwesome, null) },
        title = { Text(stringResource(R.string.of6_ai_command)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(mode == AiMode.COMMANDS, { mode = AiMode.COMMANDS; calls = null; result = null }, label = { Text(stringResource(R.string.of6_ai_mode_commands)) })
                    if (host is WordState) FilterChip(mode == AiMode.REWRITE, { mode = AiMode.REWRITE; calls = null; result = null }, label = { Text(stringResource(R.string.of6_ai_mode_rewrite)) })
                    if (host is SheetState) FilterChip(mode == AiMode.FORMULA, { mode = AiMode.FORMULA; calls = null; result = null }, label = { Text(stringResource(R.string.of6_ai_mode_formula)) })
                }
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), minLines = 2,
                    placeholder = { Text(stringResource(when (mode) { AiMode.COMMANDS -> R.string.of6_ai_hint; AiMode.REWRITE -> R.string.of6_ai_rewrite_hint; AiMode.FORMULA -> R.string.of6_ai_formula_hint })) })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                calls?.let { list ->
                    Text(stringResource(R.string.of6_ai_will_run), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    list.forEach { c -> Text("• " + c.id + (if (c.args.isEmpty()) "" else " " + c.args.entries.joinToString(", ") { it.key + "=" + it.value.take(40) }), style = MaterialTheme.typography.bodySmall) }
                }
                result?.let { Text(stringResource(R.string.of6_ai_result), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)); Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            if (calls != null || result != null) TextButton(onClick = { apply() }) { Text(stringResource(R.string.of6_ai_apply)) }
            else TextButton(onClick = { ask() }, enabled = !busy && input.isNotBlank()) { Text(stringResource(R.string.of6_ai_run)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.o5_cancel)) } },
    )
}
