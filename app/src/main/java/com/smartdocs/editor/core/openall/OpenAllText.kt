package com.smartdocs.editor.core.openall

import com.smartdocs.editor.core.convert6.FBlock
import com.smartdocs.editor.core.convert6.FileKind6
import com.smartdocs.editor.core.convert6.HtmlFlow
import com.smartdocs.editor.core.convert6.Text6
import com.smartdocs.editor.core.convert6.ToPdf6
import java.io.File

/** Searchable text of non-PDF documents for the full-text index (open-all). */
object OpenAllText {
    const val MAX_CHARS = 1_000_000
    private const val MAX_FILE = 60L * 1024 * 1024

    private val INDEXED = setOf(FType.TEXT, FType.CODE, FType.MARKDOWN, FType.CSV, FType.HTML, FType.MHT, FType.DOCX, FType.DOC, FType.RTF, FType.ODT,
        FType.EPUB, FType.FB2, FType.MOBI, FType.EML, FType.MSG, FType.XLSX, FType.XLS, FType.ODS, FType.PPT, FType.ODP, FType.VCF, FType.ICS)

    fun isIndexable(f: File): Boolean = f.isFile && f.length() in 1..MAX_FILE && FileSniffer.byExtension(f.name) in INDEXED

    /** fix/files-index: the same test by name and size (files of a SAF include folder). */
    fun isIndexableName(name: String, size: Long): Boolean = size in 1..MAX_FILE && FileSniffer.byExtension(name) in INDEXED

    private fun plain(html: String) = HtmlFlow.plain(html)

    private fun flowText(blocks: List<FBlock>): String = blocks.joinToString("\n") { b ->
        when (b) {
            is FBlock.Heading -> Text6.plain(b.runs); is FBlock.Para -> Text6.plain(b.runs); is FBlock.Item -> Text6.plain(b.runs)
            is FBlock.Quote -> Text6.plain(b.runs); is FBlock.Code -> b.text; is FBlock.Table -> b.rows.joinToString("\n") { it.joinToString(" ") }
            else -> ""
        }
    }

    /** Text of [f], or null when its type has none (blocking; call off the main thread). */
    fun extract(f: File): String? {
        if (!isIndexable(f)) return null
        val t = FileSniffer.of(f)
        val text = runCatching {
            when (t) {
                FType.TEXT, FType.CODE, FType.MARKDOWN, FType.CSV -> TextDecode.decode(FileSniffer.readHead(f, 4 * MAX_CHARS)).text
                FType.HTML -> plain(TextDecode.decode(FileSniffer.readHead(f, 8 * MAX_CHARS)).text)
                FType.MHT -> plain(MhtReader.read(f.readBytes()).html)
                FType.DOCX, FType.DOC, FType.RTF, FType.ODT -> flowText(ToPdf6.readWordLike(f, FileKind6.of(f)).blocks)
                FType.EPUB -> plain(com.smartdocs.editor.core.tools5.EpubReader.read(f, maxImageBytes = 0).html)
                FType.FB2 -> plain(Fb2Reader.read(f.readBytes()).html.replace(Regex("""src="data:[^"]*""""), ""))
                FType.MOBI -> plain(MobiReader.read(f.readBytes()).html.replace(Regex("""src="data:[^"]*""""), ""))
                FType.EML -> plain(com.smartdocs.editor.core.tools5.EmlReader.read(f.readBytes(), f.nameWithoutExtension).html.replace(Regex("""src="data:[^"]*""""), ""))
                FType.MSG -> MsgReader.read(f.readBytes()).let { m -> m.subject + "\n" + m.headers.joinToString("\n") { it.second } + "\n" + (m.bodyText ?: m.bodyHtml?.let(::plain) ?: "") }
                FType.XLSX, FType.XLS, FType.ODS -> ToPdf6.readSheets(f, FileKind6.of(f)).joinToString("\n") { (n, rows) -> n + "\n" + rows.take(20000).joinToString("\n") { it.joinToString(" ") } }
                FType.PPT -> PptText.slides(f.readBytes()).joinToString("\n") { (a, b) -> a + "\n" + b.joinToString("\n") }
                FType.ODP -> com.smartdocs.editor.core.convert6.OdfReader.readOdp(com.smartdocs.editor.core.convert6.OdfReader.zipEntry(f)).joinToString("\n") { (a, b) -> a + "\n" + b.joinToString("\n") }
                FType.VCF -> CardReaders.contacts(TextDecode.readFile(f).text).joinToString("\n") { c -> c.name + "\n" + c.fields.joinToString("\n") { it.second } }
                FType.ICS -> CardReaders.events(TextDecode.readFile(f).text).joinToString("\n") { e -> listOf(e.summary, e.start, e.location, e.description).joinToString("\n") }
                else -> null
            }
        }.getOrNull() ?: return null
        return text.take(MAX_CHARS).takeIf { it.isNotBlank() }
    }
}
