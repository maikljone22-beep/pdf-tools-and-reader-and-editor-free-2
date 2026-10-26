package com.smartdocs.editor.core.convert6

import android.content.Context
import com.smartdocs.editor.core.office.OfficeHtml
import com.smartdocs.editor.core.office.OoxmlReader
import com.smartdocs.editor.core.office5.CsvIO
import com.smartdocs.editor.core.tools5.ToPdf
import com.smartdocs.editor.core.tools5.UnsupportedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Any supported input → PDF. Reuses tools5 [ToPdf] (DOCX/XLSX/PPTX/EPUB/EML/HTML/Markdown/text through the WebView)
 * and adds the hub's readers: Word 97 (.doc, text), ODT/ODS/ODP, RTF, XLS, CSV as a table, TIFF / HEIC pictures.
 */
object ToPdf6 {

    /** Rows printed per sheet (the WebView print of huge sheets runs out of memory). */
    const val MAX_PRINT_ROWS = 5000

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

    fun slidesFlow(title: String, slides: List<Pair<String, List<String>>>): FlowDoc {
        val blocks = ArrayList<FBlock>()
        slides.forEachIndexed { i, (t, lines) ->
            if (i > 0) blocks += FBlock.PageBreak
            if (t.isNotBlank()) blocks += FBlock.Heading(1, listOf(FRun(t)), Text6.rtlDominant(t))
            lines.forEach { l -> blocks += FBlock.Item(listOf(FRun(l)), Text6.rtlDominant(l)) }
        }
        return FlowDoc(title, blocks)
    }

    /** Reads a word-processing input of any supported kind into a [FlowDoc]. */
    fun readWordLike(f: File, kind: Kind6 = FileKind6.of(f)): FlowDoc = when (kind) {
        Kind6.DOCX -> OfficeFlow.docx(OoxmlReader.readDocx(f), f.nameWithoutExtension)
        Kind6.DOC -> FlowDoc.fromText(DocReader.text(f.readBytes()), f.nameWithoutExtension)
        Kind6.ODT -> OdfReader.readOdt(OdfReader.zipEntry(f), f.nameWithoutExtension)
        Kind6.RTF -> RtfReader.parse(f.readBytes(), f.nameWithoutExtension)
        Kind6.MARKDOWN -> MdFlow.parse(ToPdf.readText(f), f.nameWithoutExtension)
        Kind6.HTML -> HtmlFlow.parse(ToPdf.readText(f), f.nameWithoutExtension)
        Kind6.EPUB -> HtmlFlow.parse(com.smartdocs.editor.core.tools5.EpubReader.read(f).html, f.nameWithoutExtension)
        Kind6.TEXT, Kind6.CSV -> FlowDoc.fromText(ToPdf.readText(f), f.nameWithoutExtension)
        else -> throw UnsupportedSource(f.extension)
    }

    /** Sheets of any supported spreadsheet input. */
    fun readSheets(f: File, kind: Kind6 = FileKind6.of(f)): List<Pair<String, List<List<String>>>> = when (kind) {
        Kind6.XLSX -> OoxmlReader.readXlsx(f).sheets.map { s ->
            val maxC = s.maxCol
            val byRow = s.rows.associateBy { it.index }
            s.name to (0..s.maxRow).map { r ->
                val cells = byRow[r]?.cells?.associateBy { it.col }
                (0..maxC).map { c -> cells?.get(c)?.display.orEmpty() }
            }
        }
        Kind6.XLS -> XlsReader.read(f.readBytes())
        Kind6.ODS -> OdfReader.readOds(OdfReader.zipEntry(f))
        Kind6.CSV, Kind6.TEXT -> { val t = ToPdf.readText(f); listOf(f.nameWithoutExtension to CsvIO.parse(t, if (f.extension.equals("tsv", true)) '\t' else CsvIO.sniff(t))) }
        else -> throw UnsupportedSource(f.extension)
    }

    suspend fun flowToPdf(ctx: Context, doc: FlowDoc, out: File): File = ToPdf.html(ctx, FlowHtml.write(doc), out)

    /** Convert one file to [out]. */
    suspend fun convert(ctx: Context, f: File, out: File, setup: PageSetup = PageSetup(), quality: Int = 88): File {
        coroutineContext.ensureActive()
        val kind = FileKind6.of(f)
        return when {
            kind == Kind6.PDF -> withContext(Dispatchers.IO) { f.copyTo(out, overwrite = true) }
            kind.isImage -> ImageIo6.imagesToPdf(listOf(f), out, setup, quality)
            kind == Kind6.DOCX && f.extension.equals("docx", true) -> ToPdf.convert(ctx, f, out)
            kind == Kind6.XLSX -> ToPdf.html(ctx, withContext(Dispatchers.IO) { OfficeHtml.xlsx(OoxmlReader.readXlsx(f)) }, out)
            kind == Kind6.PPTX -> ToPdf.html(ctx, withContext(Dispatchers.IO) { OfficeHtml.pptx(OoxmlReader.readPptx(f), forPrint = true) }, out)
            kind == Kind6.DOCX -> ToPdf.html(ctx, withContext(Dispatchers.IO) { OfficeHtml.docx(OoxmlReader.readDocx(f), f.nameWithoutExtension) }, out)
            kind == Kind6.EPUB || kind == Kind6.EML || kind == Kind6.MARKDOWN || kind == Kind6.HTML -> {
                // tools5 decides by extension: give it a copy with the right one when the name is odd
                val ext = when (kind) { Kind6.EPUB -> "epub"; Kind6.EML -> "eml"; Kind6.MARKDOWN -> "md"; else -> "html" }
                if (f.extension.equals(ext, true) || ToPdf.kindOf(f.name).name == kind.name) ToPdf.convert(ctx, f, out)
                else {
                    val tmp = File(out.parentFile, ".cv6_${System.nanoTime()}.$ext")
                    try { withContext(Dispatchers.IO) { f.copyTo(tmp, overwrite = true) }; ToPdf.convert(ctx, tmp, out) } finally { tmp.delete() }
                }
            }
            kind == Kind6.TEXT -> ToPdf.html(ctx, withContext(Dispatchers.IO) { com.smartdocs.editor.core.tools5.TextHtml.fromText(ToPdf.readText(f), f.nameWithoutExtension).html }, out)
            kind.isSheetLike -> flowToPdf(ctx, withContext(Dispatchers.IO) { sheetsFlow(f.nameWithoutExtension, readSheets(f, kind)) }, out)
            kind == Kind6.ODP -> flowToPdf(ctx, withContext(Dispatchers.IO) { slidesFlow(f.nameWithoutExtension, OdfReader.readOdp(OdfReader.zipEntry(f))) }, out)
            kind.isWordLike -> flowToPdf(ctx, withContext(Dispatchers.IO) { readWordLike(f, kind) }, out)
            else -> throw UnsupportedSource(f.extension.ifEmpty { kind.name.lowercase() })
        }
    }

    /** Several inputs → one PDF with a bookmark per input (tools5 combiner over our per-file PDFs). */
    suspend fun combine(ctx: Context, files: List<File>, out: File, tempDir: File, setup: PageSetup, quality: Int, onProgress: (Int, Int) -> Unit): File {
        val temps = ArrayList<File>()
        try {
            val items = files.mapIndexed { i, f ->
                onProgress(i, files.size + 1)
                val pdf = if (FileKind6.of(f) == Kind6.PDF) f else File(tempDir, "cv6c_${i}_${System.nanoTime()}.pdf").also { temps += it; convert(ctx, f, it, setup, quality) }
                ToPdf.Item(pdf, f.nameWithoutExtension)
            }
            return ToPdf.combine(ctx, items, out, tempDir, bookmarks = files.size > 1) { _, _ -> onProgress(files.size, files.size + 1) }
        } finally { temps.forEach { it.delete() } }
    }
}
