package com.smartdocs.editor.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.AnnotationWriter
import com.smartdocs.editor.core.pdf.DocxWriter
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One in-place edit of the open document. [op] reads `src` (with password `pw`) and writes the result to `out`;
 * the reader swaps the file. When [changesPassword] the reopened document uses [newPassword].
 */
class PdfEdit(
    val op: suspend (src: File, out: File, pw: String?) -> Unit,
    val changesPassword: Boolean = false,
    val newPassword: String? = null,
)

/**
 * What the reader gives the agent. Everything is a lambda so the reader keeps ownership of its state.
 *
 * Reader hook (ReaderScreen, see MODULE_ai2.md):
 * ```
 * val host = remember(state) { DocHost(
 *     file = { state.file }, password = { state.password }, pageCount = { state.pageCount }, currentPage = { state.currentPage },
 *     apply = { e -> state.applyInPlace(if (e.changesPassword) e.newPassword else state.password, e.op) },
 *     restore = { backup, pw -> state.restoreFrom(backup, pw) },
 *     goToPage = { state.jumpTo(it) }, pageText = { state.pageText(it) },
 *     search = { q -> state.searchOpen = true; state.searchQuery = q; state.runSearch(q) },
 *     setPageColor = { state.pageColor = it }, selectedText = { state.selection?.text },
 *     openFile = { f -> nav.navigate(Routes.reader(Uri.fromFile(f).toString())) },
 *     share = { files, mime -> ctx.startActivity(Intent.createChooser(app.documents.shareIntent(files, mime), null)) },
 * ) }
 * ```
 */
class DocHost(
    val file: () -> File?,
    val password: () -> String?,
    val pageCount: () -> Int,
    val currentPage: () -> Int,
    /** Run an edit in place; returns the backup of the previous version (for undo) or null when nothing changed. */
    val apply: suspend (PdfEdit) -> File?,
    /** Put a backup returned by [apply] back (undo). Null = no undo offered. */
    val restore: (suspend (backup: File, password: String?) -> Unit)? = null,
    val goToPage: (Int) -> Unit = {},
    val pageText: suspend (Int) -> String = { "" },
    val search: (String) -> Unit = {},
    /** View-only page colour (ARGB long like `AppSettings.pageColor`). */
    val setPageColor: (Long) -> Unit = {},
    val selectedText: () -> String? = { null },
    val openFile: (File) -> Unit = {},
    val share: (List<File>, String) -> Unit = { _, _ -> },
)

/** Result of a command: message for the chat, optional undo backup and files created. */
data class ActionResult(val message: String, val backup: File? = null, val prevPassword: String? = null, val files: List<File> = emptyList())

/** The executor interface: one suspend function per document command (pages are 0-based). */
interface DocActions {
    val pageCount: Int
    val currentPage: Int
    fun goToPage(page: Int): ActionResult
    suspend fun searchText(query: String): ActionResult
    suspend fun deletePages(pages: List<Int>): ActionResult
    suspend fun rotatePages(pages: List<Int>, degrees: Int): ActionResult
    suspend fun movePage(from: Int, to: Int): ActionResult
    suspend fun duplicatePage(page: Int): ActionResult
    suspend fun insertBlankPage(after: Int): ActionResult
    suspend fun extractPages(pages: List<Int>): ActionResult
    suspend fun sharePages(pages: List<Int>): ActionResult
    suspend fun flatten(): ActionResult
    suspend fun redactText(query: String): ActionResult
    suspend fun setProperties(title: String?, author: String?, subject: String?, keywords: String?): ActionResult
    suspend fun convert(format: String): ActionResult
    suspend fun compress(quality: String): ActionResult
    suspend fun addWatermark(text: String): ActionResult
    suspend fun addPageNumbers(arabicDigits: Boolean): ActionResult
    suspend fun protect(password: String): ActionResult
    suspend fun ocr(pages: List<Int>?, editable: Boolean): ActionResult
    fun changePageColor(color: Long): ActionResult
    suspend fun summarizeToNote(page: Int, summary: String): ActionResult
    suspend fun createBlankPdf(pages: Int): ActionResult
    suspend fun pageText(page: Int): String
    fun selectedText(): String?
    // ai6 (B10 / AI-7): edit through chat. [pages] null = every page.
    suspend fun highlightText(query: String, regex: Boolean, color: Int, pages: List<Int>?): ActionResult
    suspend fun redactPattern(kind: String, regex: String?, pages: List<Int>?): ActionResult
    suspend fun replaceText(find: String, replace: String, matchCase: Boolean): ActionResult
}

/** Failure meant for the user as is (e.g. "cannot delete every page"). */
class ActionError(message: String) : Exception(message)

/** [DocActions] against PdfOps / PdfBox, editing in place through [DocHost.apply]. */
class PdfDocActions(private val ctx: Context, private val host: DocHost, private val docs: DocumentRepository) : DocActions {

    private fun s(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    override val pageCount: Int get() = host.pageCount()
    override val currentPage: Int get() = host.currentPage()

    private suspend fun edit(done: String, e: PdfEdit): ActionResult {
        val prev = host.password()
        val backup = host.apply(e) ?: throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_not_applied))
        return ActionResult(done, backup, prev)
    }

    private suspend fun edit(done: String, op: suspend (File, File, String?) -> Unit): ActionResult = edit(done, PdfEdit(op))

    private fun base(): String = host.file()?.nameWithoutExtension ?: "document"

    override fun goToPage(page: Int): ActionResult {
        if (page !in 0 until pageCount) throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_page))
        host.goToPage(page)
        return ActionResult(s(com.smartdocs.editor.R.string.ai2_done_goto, page + 1))
    }

    override suspend fun searchText(query: String): ActionResult {
        val f = host.file() ?: throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_no_doc))
        val n = withSession(f) { it.search(query).size }
        host.search(query)
        return ActionResult(s(com.smartdocs.editor.R.string.ai2_done_search, n, query))
    }

    override suspend fun deletePages(pages: List<Int>): ActionResult {
        if (pages.isEmpty()) throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_page))
        if (pages.size >= pageCount) throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_delete_all))
        val r = edit(s(com.smartdocs.editor.R.string.ai2_done_delete, DocCommand.formatPages(pages))) { src, out, pw -> PdfOps.deletePages(src, pages.toSet(), out, pw) }
        host.goToPage((pages.minOrNull() ?: 0).coerceAtMost(pageCount - 1))
        return r
    }

    override suspend fun rotatePages(pages: List<Int>, degrees: Int): ActionResult {
        val d = (Math.round(degrees / 90f) * 90).mod(360)
        if (d == 0 || pages.isEmpty()) throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_angle))
        return edit(s(com.smartdocs.editor.R.string.ai2_done_rotate, DocCommand.formatPages(pages), degrees)) { src, out, pw -> PdfOps.rotatePages(src, pages, d, out, pw) }
    }

    override suspend fun movePage(from: Int, to: Int): ActionResult {
        val n = pageCount
        if (from !in 0 until n || to !in 0 until n) throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_page))
        if (from == to) return ActionResult(s(com.smartdocs.editor.R.string.ai2_done_move, from + 1, to + 1))
        val r = edit(s(com.smartdocs.editor.R.string.ai2_done_move, from + 1, to + 1)) { src, out, pw ->
            withContext(Dispatchers.IO) {
                PdfOps.load(src, pw).use { doc ->
                    val page = doc.getPage(from)
                    doc.pages.remove(page)
                    if (to >= doc.numberOfPages) doc.addPage(page) else doc.pages.insertBefore(page, doc.getPage(to))
                    doc.save(out)
                }
            }
        }
        host.goToPage(to)
        return r
    }

    override suspend fun duplicatePage(page: Int): ActionResult {
        if (page !in 0 until pageCount) throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_page))
        val r = edit(s(com.smartdocs.editor.R.string.ai2_done_duplicate, page + 1)) { src, out, pw ->
            withContext(Dispatchers.IO) {
                PdfOps.load(src, pw).use { doc ->
                    val original = doc.getPage(page)
                    // shallow copy of the page dictionary: content streams and resources are shared (valid PDF);
                    // annotations are dropped from the copy because an annotation belongs to exactly one page
                    val dict = COSDictionary(original.cosObject)
                    dict.removeItem(COSName.ANNOTS)
                    doc.pages.insertAfter(PDPage(dict), original)
                    doc.save(out)
                }
            }
        }
        host.goToPage(page + 1)
        return r
    }

    override suspend fun insertBlankPage(after: Int): ActionResult {
        val at = (after + 1).coerceIn(0, pageCount)
        val r = edit(s(com.smartdocs.editor.R.string.ai2_done_blank, at + 1)) { src, out, pw -> PdfOps.insertBlankPage(src, at, out, password = pw) }
        host.goToPage(at)
        return r
    }

    override suspend fun extractPages(pages: List<Int>): ActionResult {
        val f = host.file() ?: throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_no_doc))
        if (pages.isEmpty()) throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_page))
        val out = docs.uniqueFile(docs.docsDir, base() + "_" + s(com.smartdocs.editor.R.string.ai2_suffix_extract))
        PdfOps.keepPages(f, pages, out, host.password())
        runCatching { docs.touchRecent(out, pages.size) }
        return ActionResult(s(com.smartdocs.editor.R.string.ai2_done_extract, pages.size, out.name), files = listOf(out))
    }

    override suspend fun sharePages(pages: List<Int>): ActionResult {
        val f = host.file() ?: throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_no_doc))
        val target = if (pages.isEmpty() || pages.size == pageCount) f else {
            val out = File(docs.tempDir, base() + "_" + DocCommand.formatPages(pages).replace("، ", "_") + ".pdf")
            PdfOps.keepPages(f, pages, out, host.password()); out
        }
        host.share(listOf(target), "application/pdf")
        return ActionResult(s(com.smartdocs.editor.R.string.ai2_done_share))
    }

    override suspend fun flatten(): ActionResult =
        edit(s(com.smartdocs.editor.R.string.ai2_done_flatten)) { src, out, pw -> PdfOps.flattenAnnotations(src, out, pw) }

    override suspend fun redactText(query: String): ActionResult {
        val f = host.file() ?: throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_no_doc))
        val hits = withSession(f) { com.smartdocs.editor.core.search.TextSearch.search(it, query, com.smartdocs.editor.core.search.SearchOptions()) }
        if (hits.isEmpty()) throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_not_found, query))
        // true redaction (search5): glyphs under the marks are removed instead of rasterising the page
        val marks = hits.mapIndexed { i, h -> com.smartdocs.editor.core.redact.RedactMark("ai$i", h.page, h.rects) }
        return edit(s(com.smartdocs.editor.R.string.ai2_done_redact, hits.size, query)) { src, out, pw -> com.smartdocs.editor.core.redact.RedactApplier.apply(ctx, src, out, pw, marks) }
    }

    override suspend fun setProperties(title: String?, author: String?, subject: String?, keywords: String?): ActionResult {
        val f = host.file() ?: throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_no_doc))
        val cur = withSession(f) { it.meta() }
        val info = PdfOps.Info(
            title = title ?: cur.title.orEmpty(), author = author ?: cur.author.orEmpty(),
            subject = subject ?: cur.subject.orEmpty(), keywords = keywords ?: cur.keywords.orEmpty(),
            creator = cur.creator.orEmpty(), producer = cur.producer.orEmpty(),
        )
        return edit(s(com.smartdocs.editor.R.string.ai2_done_props)) { src, out, pw -> PdfOps.setInfo(src, info, out, pw) }
    }

    override suspend fun convert(format: String): ActionResult {
        val f = host.file() ?: throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_no_doc))
        val fmt = format.lowercase().trim().removePrefix(".")
        val files: List<File> = when (fmt) {
            "docx", "word", "doc", "word-2003", "rtf" -> {
                val out = docs.uniqueFile(docs.docsDir, base(), "docx")
                DocxWriter.write(withSession(f) { it.allText() }, out, base()); listOf(out)
            }
            "txt", "text" -> {
                val out = docs.uniqueFile(docs.docsDir, base(), "txt")
                val text = withSession(f) { it.allText() }.joinToString("\n\n\u000C")
                withContext(Dispatchers.IO) { out.writeText(text) }; listOf(out)
            }
            "jpeg", "jpg", "png", "image", "images" -> renderImages(f, if (fmt == "png") "png" else "jpg")
            else -> throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_format, format))
        }
        return ActionResult(s(com.smartdocs.editor.R.string.ai2_done_convert, files.size, files.firstOrNull()?.name ?: ""), files = files)
    }

    private suspend fun renderImages(f: File, ext: String): List<File> = withSession(f) { session ->
        val n = session.pageCount.coerceAtMost(MAX_IMAGE_PAGES)
        (0 until n).map { i ->
            val (w, h) = session.pageSize(i)
            val scale = 150f / 72f
            val bmp = session.render(i, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            val out = docs.uniqueFile(docs.docsDir, "${base()}_${i + 1}", ext)
            withContext(Dispatchers.IO) {
                out.outputStream().use { o -> bmp.compress(if (ext == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, o) }
            }
            session.invalidateCache()
            out
        }
    }

    override suspend fun compress(quality: String): ActionResult {
        val (q, dim) = when (quality.lowercase()) { "high", "عالي" -> 40 to 1000; "low", "منخفض" -> 80 to 2000; else -> 60 to 1400 }
        var before = 0L; var after = 0L
        val r = edit(s(com.smartdocs.editor.R.string.ai2_done_compress)) { src, out, pw ->
            before = src.length()
            PdfOps.compress(src, out, q, dim, pw)
            after = out.length()
            if (after >= before) { out.delete(); throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_no_gain)) }
        }
        return r.copy(message = s(com.smartdocs.editor.R.string.ai2_done_compress_sizes, DocumentRepository.formatSize(before), DocumentRepository.formatSize(after)))
    }

    override suspend fun addWatermark(text: String): ActionResult =
        edit(s(com.smartdocs.editor.R.string.ai2_done_watermark, text)) { src, out, pw -> PdfOps.watermark(ctx, src, text, out, password = pw) }

    override suspend fun addPageNumbers(arabicDigits: Boolean): ActionResult =
        edit(s(com.smartdocs.editor.R.string.ai2_done_numbers)) { src, out, pw -> PdfOps.addPageNumbers(ctx, src, out, arabicDigits = arabicDigits, password = pw) }

    override suspend fun protect(password: String): ActionResult {
        if (password.isBlank()) throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_password))
        val f = host.file() ?: throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_no_doc))
        if (PdfOps.isEncrypted(f, host.password())) throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_already_protected))
        return edit(s(com.smartdocs.editor.R.string.ai2_done_protect), PdfEdit({ src, out, pw ->
            PdfOps.protect(src, out, password, password, currentPassword = pw)
        }, changesPassword = true, newPassword = password))
    }

    override suspend fun ocr(pages: List<Int>?, editable: Boolean): ActionResult {
        val engine = com.smartdocs.editor.core.ocr.OcrEngine(ctx.applicationContext)
        var count = 0
        try {
            val langs = listOf("ara", "eng")
            return edit(s(com.smartdocs.editor.R.string.ai2_done_ocr_pending)) { src, out, pw ->
                count = com.smartdocs.editor.ui.ocr.runOcrToFile(ctx, engine, src, out, pw, com.smartdocs.editor.ui.ocr.OcrOptions(pages, langs, editable)) { _, _ -> }
            }.let { it.copy(message = s(com.smartdocs.editor.R.string.ai2_done_ocr, count)) }
        } finally {
            withContext(Dispatchers.IO) { runCatching { engine.close() } }
        }
    }

    override fun changePageColor(color: Long): ActionResult {
        host.setPageColor(color)
        return ActionResult(s(com.smartdocs.editor.R.string.ai2_done_color))
    }

    override suspend fun summarizeToNote(page: Int, summary: String): ActionResult {
        if (page !in 0 until pageCount) throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_page))
        val r = edit(s(com.smartdocs.editor.R.string.ai2_done_note, page + 1)) { src, out, pw ->
            val box = withContext(Dispatchers.IO) { PdfOps.load(src, pw).use { d -> d.getPage(page).cropBox.let { RectF(it.lowerLeftX, it.lowerLeftY, it.upperRightX, it.upperRightY) } } }
            // top-right corner (Arabic documents start on the right); Annot rects are PDF space: top > bottom
            val x = box.right - 36f; val top = box.top - 16f
            val note = Annot(kind = AnnotKind.NOTE, page = page, color = 0xFFEB3B, rects = listOf(RectF(x, top, x + 20f, top - 20f)), text = summary, author = ctx.getString(com.smartdocs.editor.R.string.ai_title))
            AnnotationWriter.commit(ctx, src, listOf(note), out, pw).orThrow()
        }
        host.goToPage(page)
        return r
    }

    override suspend fun createBlankPdf(pages: Int): ActionResult {
        val out = docs.uniqueFile(docs.docsDir, s(com.smartdocs.editor.R.string.ai2_blank_name))
        PdfOps.createBlank(out, pages.coerceIn(1, 200))
        runCatching { docs.touchRecent(out, pages) }
        return ActionResult(s(com.smartdocs.editor.R.string.ai2_done_create, out.name), files = listOf(out))
    }

    override suspend fun pageText(page: Int): String = host.pageText(page)
    override fun selectedText(): String? = host.selectedText()

    // ---------------- ai6 (B10 / AI-7): edit through chat ----------------

    /** Hits of [matcher] on [pages] (null = all), searched with PDFium so rects match the page text. */
    private suspend fun findAll(pages: List<Int>?, matcher: (String) -> List<IntRange>): List<com.smartdocs.editor.core.pdf.SearchHit> {
        val f = host.file() ?: throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_no_doc))
        val wanted = pages?.takeIf { it.isNotEmpty() }?.toSet()
        val hits = try {
            withSession(f) { session ->
                val range = if (wanted == null) 0 until session.pageCount else (wanted.minOrNull() ?: 0)..(wanted.maxOrNull() ?: 0)
                session.searchWith(range, matcher)
            }
        } catch (e: IllegalArgumentException) { throw ActionError(s(com.smartdocs.editor.R.string.ai6r_err_pattern)) }
        return hits.filter { (wanted == null || it.page in wanted) && it.rects.isNotEmpty() }
    }

    override suspend fun highlightText(query: String, regex: Boolean, color: Int, pages: List<Int>?): ActionResult {
        val hits = findAll(pages) { t -> AiEditPatterns.highlightRanges(t, query, regex) }
        if (hits.isEmpty()) throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_not_found, query))
        val author = s(com.smartdocs.editor.R.string.ai_title)
        val annots = hits.map { h ->
            Annot(kind = AnnotKind.HIGHLIGHT, page = h.page, color = color,
                rects = h.rects.map { r -> RectF(minOf(r.left, r.right), maxOf(r.top, r.bottom), maxOf(r.left, r.right), minOf(r.top, r.bottom)) }, author = author)
        }
        val r = edit(s(com.smartdocs.editor.R.string.ai6r_done_highlight, hits.size, query)) { src, out, pw -> AnnotationWriter.commit(ctx, src, annots, out, pw).orThrow() }
        host.goToPage(hits.first().page)
        return r
    }

    override suspend fun redactPattern(kind: String, regex: String?, pages: List<Int>?): ActionResult {
        val hits = findAll(pages) { t -> AiEditPatterns.redactRanges(t, kind, regex) }
        if (hits.isEmpty()) throw ActionError(s(com.smartdocs.editor.R.string.ai6r_err_none_found, kind))
        val marks = hits.mapIndexed { i, h -> com.smartdocs.editor.core.redact.RedactMark("ai6_$i", h.page, h.rects) }
        return edit(s(com.smartdocs.editor.R.string.ai6r_done_redact_pattern, hits.size, kind)) { src, out, pw -> com.smartdocs.editor.core.redact.RedactApplier.apply(ctx, src, out, pw, marks) }
    }

    override suspend fun replaceText(find: String, replace: String, matchCase: Boolean): ActionResult {
        if (find.isBlank()) throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_args))
        var outcome: com.smartdocs.editor.core.search.ReplaceOutcome? = null
        val options = com.smartdocs.editor.core.search.SearchOptions(norm = com.smartdocs.editor.core.search.NormOptions(matchCase = matchCase))
        val r = edit(s(com.smartdocs.editor.R.string.ai6r_done_replace, 0, find)) { src, out, pw ->
            val o = com.smartdocs.editor.core.search.FindReplace.replaceAllIn(ctx, src, out, pw, find, options, replace, ctx.cacheDir)
            if (o.count == 0) { out.delete(); throw ActionError(s(com.smartdocs.editor.R.string.ai2_err_not_found, find)) }
            outcome = o
        }
        val o = outcome
        val warn = (if (o?.fontWarning == true) "\n" + s(com.smartdocs.editor.R.string.s5_rr_font_warn) else "") + (if (o?.lossy == true) "\n" + s(com.smartdocs.editor.R.string.s5_rr_lossy) else "")
        return r.copy(message = s(com.smartdocs.editor.R.string.ai6r_done_replace, o?.count ?: 0, find) + warn)
    }

    private suspend fun <T> withSession(f: File, block: suspend (PdfSession) -> T): T {
        val session = PdfSession.open(ctx, f, host.password())
        try { return block(session) } finally { session.closeAsync() }
    }

    companion object {
        const val MAX_IMAGE_PAGES = 60

        /** Named view colours for change_page_color (ARGB). */
        fun parseColor(name: String): Long? {
            val n = name.trim().lowercase()
            return when {
                n.startsWith("#") && n.length == 7 -> n.substring(1).toLongOrNull(16)?.let { 0xFF000000L or it }
                n in setOf("white", "أبيض", "ابيض", "default") -> 0xFFFFFFFFL
                n in setOf("sepia", "بني فاتح", "سيبيا", "كريمي") -> 0xFFF4ECD8L
                n in setOf("green", "أخضر", "اخضر") -> 0xFFCCE8CFL
                n in setOf("gray", "grey", "رمادي") -> 0xFFE0E0E0L
                n in setOf("night", "black", "dark", "ليلي", "أسود", "اسود") -> 0xFF1E1E1EL
                n in setOf("yellow", "أصفر", "اصفر") -> 0xFFFFF8C4L
                n in setOf("blue", "أزرق", "ازرق") -> 0xFFDCEBFAL
                else -> null
            }
        }
    }
}
