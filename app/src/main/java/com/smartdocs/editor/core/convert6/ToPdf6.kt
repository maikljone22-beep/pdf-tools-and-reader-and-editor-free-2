package com.smartdocs.editor.core.convert6

import android.content.Context
import android.print.PrintAttributes
import android.print.WebPdfWriter
import com.smartdocs.editor.R
import com.smartdocs.editor.core.office.OfficeHtml
import com.smartdocs.editor.core.office.OoxmlReader
import com.smartdocs.editor.core.office5.CsvIO
import com.smartdocs.editor.core.tools5.EmlReader
import com.smartdocs.editor.core.tools5.Embed
import com.smartdocs.editor.core.tools5.EpubReader
import com.smartdocs.editor.core.tools5.HtmlBits
import com.smartdocs.editor.core.tools5.OfficeProps
import com.smartdocs.editor.core.tools5.TextHtml
import com.smartdocs.editor.core.tools5.ToPdf
import com.smartdocs.editor.core.tools5.UnsupportedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Sheets of a spreadsheet input, and the names of sheets that were cut to the read limits. */
class Sheets(val list: List<Pair<String, List<List<String>>>>, val cut: List<String> = emptyList())

/**
 * THE any-format → PDF engine (convert audit A-M8): the hub, Tools → To PDF, Share-to-PDF, Combine, the reader and the
 * recipes all come here, with detection by content ([FileKind6]). Word / Excel / PowerPoint / books / e-mails / web
 * pages / Markdown / text are printed by the WebView ([ToPdf.html], scripts off for local content); big inputs are
 * printed in parts and joined ([ToPdf.htmlParts]); pictures go through [ImageIo6.imagesToPdf].
 */
object ToPdf6 {

    /** Rows printed per sheet (the rest is reported, convert audit A-M1); a part of the print holds [PART_ROWS]. */
    const val MAX_PRINT_ROWS = 20_000
    const val PART_ROWS = 2_500
    /** Characters of HTML per printed part (books, text). */
    const val PART_CHARS = 3_000_000

    fun sheetsFlow(title: String, sheets: List<Pair<String, List<List<String>>>>): FlowDoc {
        val blocks = ArrayList<FBlock>()
        sheets.forEachIndexed { i, (name, rows) ->
            if (i > 0) blocks += FBlock.PageBreak
            if (sheets.size > 1) blocks += FBlock.Heading(2, listOf(FRun(name)), Text6.rtlDominant(name))
            val cut = rows.take(MAX_PRINT_ROWS)
            if (cut.isNotEmpty()) blocks += FBlock.Table(cut, false, Text6.rtlDominant(cut.take(50).flatten().joinToString(" ")))
        }
        return FlowDoc(title, blocks)
    }

    /** A sheet table in parts of [PART_ROWS] rows, each an HTML page (the first with the sheet name). Pure. */
    fun sheetParts(title: String, sheets: List<Pair<String, List<List<String>>>>, note: (String, Int, Int) -> String?): Sequence<String> = sequence {
        sheets.forEach { (name, rows0) ->
            val rows = trimSheet(rows0)
            val rtl = Text6.rtlDominant(rows.take(50).flatten().joinToString(" "))
            val shown = rows.take(MAX_PRINT_ROWS)
            val chunks = shown.chunked(PART_ROWS).ifEmpty { listOf(emptyList()) }
            chunks.forEachIndexed { k, part ->
                val blocks = ArrayList<FBlock>()
                if (k == 0 && sheets.size > 1) blocks += FBlock.Heading(2, listOf(FRun(name)), Text6.rtlDominant(name))
                if (part.isNotEmpty()) blocks += FBlock.Table(part, false, rtl)
                if (k == chunks.lastIndex && rows.size > shown.size) note(name, shown.size, rows.size)?.let { blocks += FBlock.Para(listOf(FRun(it, italic = true)), Text6.rtlDominant(it)) }
                yield(FlowHtml.write(FlowDoc(title, blocks)))
            }
        }
    }

    /** Rows without trailing empty rows and columns (sheets often end with thousands of formatted empty cells). Pure. */
    fun trimSheet(rows: List<List<String>>): List<List<String>> {
        val last = rows.indexOfLast { r -> r.any { it.isNotBlank() } }
        if (last < 0) return emptyList()
        val kept = rows.subList(0, last + 1)
        val width = kept.maxOf { r -> r.indexOfLast { it.isNotBlank() } + 1 }
        return kept.map { r -> if (r.size > width) r.subList(0, width) else r }
    }

    fun slidesFlow(title: String, slides: List<Pair<String, List<String>>>): FlowDoc {
        val blocks = ArrayList<FBlock>()
        slides.forEachIndexed { i, (t, lines) ->
            if (i > 0) blocks += FBlock.PageBreak
            if (t.isNotBlank()) blocks += FBlock.Heading(1, listOf(FRun(t)), Text6.rtlDominant(t))
            lines.forEach { l -> blocks += FBlock.Item(listOf(FRun(l)), Text6.rtlDominant(l)) }
        }
        return FlowDoc(title, blocks)
    }

    /** ODP slides with their pictures (convert audit A-M3: the pictures were dropped). */
    fun odpFlow(title: String, slides: List<OdfReader.OdpSlide>): FlowDoc {
        val blocks = ArrayList<FBlock>()
        slides.forEachIndexed { i, s ->
            if (i > 0) blocks += FBlock.PageBreak
            if (s.title.isNotBlank()) blocks += FBlock.Heading(1, listOf(FRun(s.title)), Text6.rtlDominant(s.title))
            s.lines.forEach { l -> blocks += FBlock.Item(listOf(FRun(l)), Text6.rtlDominant(l)) }
            s.pictures.forEach { b -> blocks += FBlock.Image(b, Images6.sniffExt(b) ?: "png") }
        }
        return FlowDoc(title, blocks)
    }

    /** The first bytes of [f]. */
    private fun head(f: File, n: Int = 4096): ByteArray = f.inputStream().use { s -> ByteArray(n).let { b -> b.copyOf(maxOf(0, s.read(b))) } }

    /** Reads a word-processing input of any supported kind into a [FlowDoc]. A ".doc" that is HTML / XML / text is read as such (A-H11 / C20). */
    fun readWordLike(f: File, kind: Kind6 = FileKind6.of(f)): FlowDoc = when (kind) {
        Kind6.DOCX -> OfficeFlow.docx(OoxmlReader.readDocx(f), f.nameWithoutExtension)
        Kind6.DOC -> when (val v = FileKind6.legacyVariant(head(f))) {
            FileKind6.Legacy.CFB -> FlowDoc.fromText(DocReader.text(f.readBytes()), f.nameWithoutExtension)
            FileKind6.Legacy.RTF -> RtfReader.parse(f.readBytes(), f.nameWithoutExtension)
            FileKind6.Legacy.HTML -> LegacyVariants.wordDoc(v, ToPdf.readHtml(f), f.nameWithoutExtension)
            else -> LegacyVariants.wordDoc(v, ToPdf.readText(f), f.nameWithoutExtension)
        }
        Kind6.ODT -> OdfReader.readOdt(OdfReader.zipEntry(f), f.nameWithoutExtension)
        Kind6.RTF -> RtfReader.parse(f.readBytes(), f.nameWithoutExtension)
        Kind6.MARKDOWN -> MdFlow.parse(ToPdf.readText(f), f.nameWithoutExtension, resolve = siblingResolver(f))
        Kind6.HTML -> HtmlFlow.parse(ToPdf.readHtml(f), f.nameWithoutExtension)
        Kind6.EPUB -> HtmlFlow.parse(EpubReader.read(f).html, f.nameWithoutExtension)
        Kind6.TEXT, Kind6.CSV -> FlowDoc.fromText(ToPdf.readText(f), f.nameWithoutExtension)
        else -> throw unsupported(f, kind)
    }

    /** Pictures next to a Markdown file (relative paths inside its folder only, at most 20 MB each). */
    fun siblingResolver(f: File): (String) -> ByteArray? = { rel ->
        val dir = f.absoluteFile.parentFile
        val t = File(dir, rel).canonicalFile
        if (dir != null && t.path.startsWith(dir.canonicalPath + File.separator) && t.isFile && t.length() < (20L shl 20)) t.readBytes() else null
    }

    private fun unsupported(f: File, kind: Kind6): Exception =
        if (FileKind6.isEncryptedOffice(f)) EncryptedOffice(f.name) else UnsupportedSource(f.extension.ifEmpty { kind.name.lowercase() })

    /** Sheets of any supported spreadsheet input (see [readSheetsInfo] for the cut sheets). */
    fun readSheets(f: File, kind: Kind6 = FileKind6.of(f)): List<Pair<String, List<List<String>>>> = readSheetsInfo(f, kind).list

    /**
     * Sheets of any supported spreadsheet input, read with the format-conversion limits (convert audit C5: XLSX was cut
     * at 5000 rows, ODS at 20000 × 500, without a word); sheets that still hit a limit are named in [Sheets.cut].
     */
    fun readSheetsInfo(f: File, kind: Kind6 = FileKind6.of(f), maxRows: Int = OdfReader.MAX_ROWS, maxCols: Int = OdfReader.MAX_COLS): Sheets = when (kind) {
        Kind6.XLSX -> {
            val book = OoxmlReader.readXlsx(f, maxRows, maxCols)
            Sheets(book.sheets.map { s ->
                val maxC = s.maxCol
                val byRow = s.rows.associateBy { it.index }
                s.name to (0..s.maxRow).map { r ->
                    val cells = byRow[r]?.cells?.associateBy { it.col }
                    (0..maxC).map { c -> cells?.get(c)?.display.orEmpty() }
                }
            }, book.sheets.filter { it.truncated }.map { it.name })
        }
        Kind6.XLS -> when (val v = FileKind6.legacyVariant(head(f))) {
            FileKind6.Legacy.CFB -> Sheets(XlsReader.read(f.readBytes()))
            FileKind6.Legacy.HTML -> Sheets(LegacyVariants.sheets(v, ToPdf.readHtml(f), f.nameWithoutExtension))
            else -> Sheets(LegacyVariants.sheets(v, ToPdf.readText(f), f.nameWithoutExtension))
        }
        Kind6.ODS -> OdfReader.readOds(OdfReader.zipEntry(f), maxRows, maxCols).let { l -> Sheets(l, l.filter { it.second.size >= maxRows }.map { it.first }) }
        Kind6.CSV, Kind6.TEXT -> { val t = ToPdf.readText(f); Sheets(listOf(f.nameWithoutExtension to CsvIO.parse(t, if (f.extension.equals("tsv", true)) '\t' else CsvIO.sniff(t)))) }
        else -> throw unsupported(f, kind)
    }

    suspend fun flowToPdf(ctx: Context, doc: FlowDoc, out: File): File = ToPdf.html(ctx, FlowHtml.write(doc), out)

    private fun mils(twips: Int) = twips * 1000 / 1440

    /** Print setup of a Word document from its section (convert audit A-M4: always A4 portrait, 0.5" margins). */
    fun docxSetup(page: com.smartdocs.editor.core.office.DocPage?): WebPdfWriter.Setup {
        page ?: return WebPdfWriter.Setup(javaScript = false)
        val w = mils(minOf(page.widthTwips, page.heightTwips)); val h = mils(maxOf(page.widthTwips, page.heightTwips))
        val media = runCatching { PrintAttributes.MediaSize("docx_page", "Document", w.coerceIn(1000, 50_000), h.coerceIn(1000, 50_000)) }.getOrDefault(PrintAttributes.MediaSize.ISO_A4)
        return WebPdfWriter.Setup(media = media, landscape = page.landscape, javaScript = false,
            marginsMils = intArrayOf(mils(page.left), mils(page.top), mils(page.right), mils(page.bottom)))
    }

    /**
     * Convert one file to [out]. [info] receives notes for the result sheet (rows not printed, equations as text,
     * pictures left out, pages that could not be read …).
     */
    suspend fun convert(ctx: Context, f: File, out: File, setup: PageSetup = PageSetup(), quality: Int = 88, bookmarks: Boolean = false,
                        speakerNotes: Boolean = false, info: (String) -> Unit = {}): File {
        coroutineContext.ensureActive()
        val kind = FileKind6.of(f)
        val title = f.nameWithoutExtension
        val tmp = File(ctx.cacheDir, "tmp").apply { mkdirs() }
        val res = when {
            kind == Kind6.PDF -> withContext(Dispatchers.IO) { f.copyTo(out, overwrite = true) }
            kind.isImage -> {
                val skipped = ArrayList<String>()
                ImageIo6.imagesToPdf(listOf(f), out, setup, quality, skipped = { skipped += it })
                if (skipped.isNotEmpty()) info(ctx.getString(R.string.cvf_info_skipped, skipped.joinToString(", ")))
                out
            }
            kind == Kind6.DOCX -> {
                val model = withContext(Dispatchers.IO) { OoxmlReader.readDocx(f) }
                if (model.equations > 0) info(ctx.getString(R.string.cvf_info_equations, model.equations))
                if (model.unrendered > 0) info(ctx.getString(R.string.cvf_info_unrendered, model.unrendered))
                ToPdf.html(ctx, OfficeHtml.docx(model, title, forPrint = true), out, docxSetup(model.page))
                // Word headings → bookmarks (found by their text in the printed pages)
                if (bookmarks) runCatching { OfficeOutline.applyByText(out, OfficeOutline.docxTitles(model)) }
                out
            }
            kind == Kind6.XLSX -> xlsx(ctx, f, out, tmp, info, bookmarks)
            kind == Kind6.PPTX -> {
                val model = withContext(Dispatchers.IO) { OoxmlReader.readPptx(f) }
                val s = model.slides.firstOrNull()
                // the paper is the slide (convert audit A-M3: 16:9 slides sat in the top third of A4 portrait pages)
                val wMils = ((s?.width ?: 12192000L) * 1000 / 914400).toInt().coerceIn(1000, 50_000)
                val hMils = ((s?.height ?: 6858000L) * 1000 / 914400).toInt().coerceIn(1000, 50_000)
                val media = runCatching { PrintAttributes.MediaSize("slide", "Slide", minOf(wMils, hMils), maxOf(wMils, hMils)) }.getOrDefault(PrintAttributes.MediaSize.ISO_A4)
                val setupW = WebPdfWriter.Setup(media = media, landscape = wMils > hMils, marginMils = 0, javaScript = false)
                ToPdf.html(ctx, OfficeHtml.pptx(model, forPrint = true, pageWidthPx = wMils * 96 / 1000), out, setupW)
                // one bookmark per slide (its title, else "Slide n")
                if (bookmarks) runCatching { OfficeOutline.applyPerPage(out, OfficeOutline.slideTitles(model) { n -> ctx.getString(R.string.cv9_slide_n, n) }) }
                // row 103: speaker notes → note comments on the slide's page
                if (speakerNotes) runCatching { SlideNotes.apply(out, model.slides.map { it.notes }, ctx.getString(R.string.cv9_notes_author)) }
                    .getOrNull()?.takeIf { it > 0 }?.let { info(ctx.getString(R.string.cv9_info_notes, it)) }
                out
            }
            kind == Kind6.EPUB -> {
                val book = withContext(Dispatchers.IO) { EpubReader.readBook(f) }
                if (book.droppedImages > 0) info(ctx.getString(R.string.cvf_info_images_dropped, book.droppedImages))
                val css = book.css
                ToPdf.htmlParts(ctx, chapterParts(book.title, book.chapters, book.rtl, css), out, tmp, WebPdfWriter.Setup(javaScript = false, blockNetwork = true))
            }
            kind == Kind6.EML -> {
                val doc = withContext(Dispatchers.IO) { EmlReader.read(f.readBytes(), title, ToPdf.emlLabels) }
                // e-mail: no scripts, no remote pictures (read receipts / tracking, convert audit A-M6)
                ToPdf.html(ctx, doc.html, out, WebPdfWriter.Setup(javaScript = false, blockNetwork = true))
                if (doc.attachments.isNotEmpty()) withContext(Dispatchers.IO) {
                    val att = File(tmp, ".${out.name}.${System.nanoTime()}.att")
                    try {
                        com.smartdocs.editor.core.pdf.PdfOps.loadRaw(out, null).use { pd -> Embed.addFiles(pd, doc.attachments); pd.save(att) }
                        if (!att.renameTo(out)) att.copyTo(out, overwrite = true)
                    } finally { att.delete() }
                }
                out
            }
            kind == Kind6.MARKDOWN -> ToPdf.html(ctx, FlowHtml.write(withContext(Dispatchers.IO) { MdFlow.parse(ToPdf.readText(f), title, resolve = siblingResolver(f)) }), out)
            kind == Kind6.HTML -> ToPdf.html(ctx, withContext(Dispatchers.IO) { HtmlBits.sanitize(ToPdf.readHtml(f)) }, out).also {
                if (bookmarks) runCatching { OfficeOutline.applyDetected(out) }      // row 93: headings → bookmarks
            }
            kind == Kind6.TEXT -> ToPdf.htmlParts(ctx, ToPdf.textChunks(f).map { HtmlBits.page(title, TextHtml.body(it), HtmlBits.rtlDominant(it.take(20000)), TextHtml.CSS) }, out, tmp)
            kind.isSheetLike -> {
                val sheets = withContext(Dispatchers.IO) { readSheetsInfo(f, kind, maxRows = 65_536, maxCols = 512) }
                sheets.cut.forEach { info(ctx.getString(R.string.cvf_info_sheet_cut, it)) }
                ToPdf.htmlParts(ctx, sheetParts(title, sheets.list) { name, shown, all -> ctx.getString(R.string.cvf_info_rows_cut, name, shown, all).also { info(it) } }, out, tmp)
            }
            // row 19: PowerPoint 97-2003 — the slide texts (one page per slide, title as heading); pictures and layout are not read
            kind == Kind6.PPT -> {
                val slides = withContext(Dispatchers.IO) { com.smartdocs.editor.core.openall.PptText.slides(f.readBytes()) }
                info(ctx.getString(R.string.cv9_info_ppt_text))
                flowToPdf(ctx, slidesFlow(title, slides), out)
                if (bookmarks) runCatching { OfficeOutline.applyByText(out, slides.mapNotNull { (t, _) -> t.takeIf { it.isNotBlank() }?.let { OfficeOutline.Title(1, it) } }) }
                out
            }
            kind == Kind6.ODP -> ToPdf.html(ctx, FlowHtml.write(withContext(Dispatchers.IO) { odpFlow(title, OdfReader.readOdpSlides(OdfReader.zipEntry(f))) }), out,
                WebPdfWriter.Setup(media = PrintAttributes.MediaSize.ISO_A4, landscape = true, javaScript = false))
            kind.isWordLike -> {
                if (kind == Kind6.DOC && FileKind6.legacyVariant(head(f)) == FileKind6.Legacy.CFB) info(ctx.getString(R.string.cvf_info_doc_text))
                flowToPdf(ctx, withContext(Dispatchers.IO) { readWordLike(f, kind) }, out)
            }
            else -> throw unsupported(f, kind)
        }
        // Foxit add-in setting "preserve document properties": title / author / subject / keywords of the Office file
        if (kind == Kind6.DOCX || kind == Kind6.XLSX || kind == Kind6.PPTX)
            withContext(Dispatchers.IO) { runCatching { OfficeProps.applyTo(out, OfficeProps.read(f)) } }
        return res
    }

    /** Book chapters grouped into print parts of about [PART_CHARS] characters. */
    private fun chapterParts(title: String, chapters: List<String>, rtl: Boolean, css: String): Sequence<String> = sequence {
        val sb = StringBuilder()
        var first = true
        chapters.forEach { c ->
            if (sb.isNotEmpty() && sb.length + c.length > PART_CHARS) { yield(HtmlBits.page(title, sb.toString(), rtl, css)); sb.setLength(0) }
            sb.append(if (first) "<div>" else "<div class=\"chapter\">").append(c).append("</div>\n")
            first = false
        }
        if (sb.isNotEmpty() || chapters.isEmpty()) yield(HtmlBits.page(title, sb.toString(), rtl, css))
    }

    /** Excel → PDF: clean printable sheets (no grid chrome), landscape for wide sheets, printed in parts (A-H9 / A-M1). */
    private suspend fun xlsx(ctx: Context, f: File, out: File, tmp: File, info: (String) -> Unit, bookmarks: Boolean = false): File {
        val book = withContext(Dispatchers.IO) { OoxmlReader.readXlsx(f, MAX_PRINT_ROWS, 500) }
        val landscape = OfficeHtml.xlsxLandscape(book)
        val pageW = if (landscape) 1020 else 700
        book.sheets.filter { it.truncated }.forEach { info(ctx.getString(R.string.cvf_info_sheet_cut, it.name)) }
        val parts = sequence {
            book.sheets.forEachIndexed { i, s ->
                val used = OfficeHtml.xlsxUsedRows(s)
                if (used.isEmpty()) return@forEachIndexed
                val chunks = (used.first..used.last).chunked(PART_ROWS)
                chunks.forEachIndexed { k, part ->
                    val note = if (k == chunks.lastIndex && s.truncated) ctx.getString(R.string.cvf_info_sheet_cut, s.name) else null
                    yield(OfficeHtml.xlsxPrint(book, i, part.first()..part.last(), pageW, if (k == 0 && book.sheets.size > 1) s.name else null, note))
                }
            }
        }.ifEmpty { sequenceOf(FlowHtml.write(FlowDoc(f.nameWithoutExtension, emptyList()))) }
        val res = ToPdf.htmlParts(ctx, parts, out, tmp, WebPdfWriter.Setup(landscape = landscape, marginMils = 400, javaScript = false))
        // a bookmark per sheet: each printed sheet starts with its name when the workbook has several
        if (bookmarks && book.sheets.size > 1) runCatching {
            OfficeOutline.applyByText(out, book.sheets.filter { !OfficeHtml.xlsxUsedRows(it).isEmpty() }.map { OfficeOutline.Title(1, it.name) })
        }
        return res
    }

    /**
     * Several inputs → one PDF with a bookmark per input. [passwords] gives each protected PDF its password (convert
     * audit A-H4); [info] gets notes (signed inputs, rows not printed …).
     */
    suspend fun combine(ctx: Context, files: List<File>, out: File, tempDir: File, setup: PageSetup, quality: Int, onProgress: (Int, Int) -> Unit,
                        passwords: (File) -> String? = { null }, info: (String) -> Unit = {}, bookmarks: Boolean = false): File {
        val temps = ArrayList<File>()
        try {
            val items = files.mapIndexed { i, f ->
                onProgress(i, files.size + 1)
                if (FileKind6.of(f) == Kind6.PDF) ToPdf.Item(f, f.nameWithoutExtension, passwords(f))
                else ToPdf.Item(File(tempDir, "cv6c_${i}_${System.nanoTime()}.pdf").also { temps += it; convert(ctx, f, it, setup, quality, bookmarks = bookmarks, info = info) }, f.nameWithoutExtension)
            }
            return ToPdf.combine(ctx, items, out, tempDir, bookmarks = files.size > 1, onProgress = { _, _ -> onProgress(files.size, files.size + 1) },
                signed = { s -> info(ctx.getString(R.string.cvf_info_signed, s.name)) })
        } finally { temps.forEach { it.delete() } }
    }
}
