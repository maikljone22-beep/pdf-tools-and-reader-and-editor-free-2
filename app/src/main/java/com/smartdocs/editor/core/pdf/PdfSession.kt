package com.smartdocs.editor.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.util.LruCache
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.FindFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Outline entry of the document (table of contents). */
data class OutlineItem(val title: String, val page: Int, val level: Int, val children: List<OutlineItem>)

/** A hit of a text search: page index + bounding rects in page points (PDF coordinate space, origin bottom-left). */
data class SearchHit(val page: Int, val charIndex: Int, val length: Int, val rects: List<RectF>, val snippet: String)

data class PageLink(val bounds: RectF, val destPage: Int?, val uri: String?)

data class DocMeta(
    val title: String, val author: String, val subject: String, val keywords: String,
    val creator: String, val producer: String, val creationDate: String, val modDate: String,
)

/**
 * A rendering + text session over one PDF file, backed by PDFium.
 * Thread-safe: all native access is serialised with a mutex and executed on the IO dispatcher.
 */
class PdfSession private constructor(
    private val core: PdfiumCore,
    private val doc: PdfDocument,
    val file: File,
) : AutoCloseable {

    private val mutex = Mutex()
    /**
     * Small LRU of open native pages. Only the pages around the viewport stay loaded; older ones are closed,
     * otherwise scrolling through a long document keeps every page in native memory until the app is killed.
     * Accessed only with [mutex] held.
     */
    private val pageCache = object : LinkedHashMap<Int, PdfPage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPage>): Boolean {
            if (size <= MAX_OPEN_PAGES) return false
            runCatching { eldest.value.close() }
            return true
        }
    }
    private val bitmapCache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Volatile private var closed = false
    val isClosed: Boolean get() = closed

    val pageCount: Int = doc.getPageCount()

    /** Page size in PDF points (1/72 inch). */
    private val sizes = HashMap<Int, Pair<Float, Float>>()

    suspend fun pageSize(index: Int): Pair<Float, Float> = withContext(Dispatchers.IO) {
        mutex.withLock {
            sizes.getOrPut(index) {
                val p = page(index)
                val w = p.getPageWidthPoint().toFloat(); val h = p.getPageHeightPoint().toFloat()
                // never hand out a zero/NaN size: callers divide by it
                (if (w.isFinite() && w > 1f) w else 595f) to (if (h.isFinite() && h > 1f) h else 842f)
            }
        }
    }

    /**
     * Sizes (points) of the pages in [range]. Pages not already open are opened, measured and closed again,
     * so measuring a long document does not keep hundreds of native pages alive.
     */
    suspend fun pageSizes(range: IntRange = 0 until pageCount): Map<Int, Pair<Float, Float>> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<Int, Pair<Float, Float>>()
        for (i in range) {
            if (i !in 0 until pageCount) continue
            mutex.withLock {
                if (closed) return@withContext out
                val known = sizes[i]
                if (known != null) { out[i] = known; return@withLock }
                val cached = pageCache[i]
                val p = cached ?: runCatching { doc.openPage(i) }.getOrNull() ?: return@withLock
                try {
                    val w = p.getPageWidthPoint().toFloat(); val h = p.getPageHeightPoint().toFloat()
                    val sz = (if (w.isFinite() && w > 1f) w else 595f) to (if (h.isFinite() && h > 1f) h else 842f)
                    sizes[i] = sz; out[i] = sz
                } finally { if (cached == null) runCatching { p.close() } }
            }
        }
        out
    }

    /** Must be called with [mutex] held. Throws once the session is closed so native code is never touched after [close]. */
    private fun page(index: Int): PdfPage {
        check(!closed) { "PdfSession closed" }
        require(index in 0 until pageCount) { "page $index out of range 0..${pageCount - 1}" }
        return pageCache.getOrPut(index) { doc.openPage(index) ?: error("cannot open page $index") }
    }

    /** Render a page to a bitmap of exactly [width]x[height] pixels. */
    suspend fun render(index: Int, width: Int, height: Int, nightMode: Boolean = false, annotations: Boolean = true): Bitmap =
        withContext(Dispatchers.IO) {
            val key = "$index:$width:$height:$nightMode:$annotations"
            bitmapCache.get(key)?.let { return@withContext it }
            mutex.withLock {
                bitmapCache.get(key)?.let { return@withLock it }
                val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                page(index).renderPageBitmap(bmp, 0, 0, width, height, annotations, false, Color.WHITE, Color.WHITE)
                val out = if (nightMode) invert(bmp) else bmp
                bitmapCache.put(key, out)
                out
            }
        }

    /**
     * Render a page into a new bitmap the caller owns (not put in the shared cache, so the caller may recycle it):
     * large one-off renders like the workbench page (tabs audit CM-16).
     */
    suspend fun renderOwned(index: Int, width: Int, height: Int, nightMode: Boolean = false, annotations: Boolean = true): Bitmap =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                page(index).renderPageBitmap(bmp, 0, 0, width, height, annotations, false, Color.WHITE, Color.WHITE)
                if (nightMode) invert(bmp) else bmp
            }
        }

    /** Render a region of a page (used for high-zoom tiles). [clip] is in fractions 0..1 of the page. */
    suspend fun renderRegion(index: Int, fullWidth: Int, fullHeight: Int, clip: RectF, nightMode: Boolean, annotations: Boolean = true): Bitmap =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val w = ((clip.width()) * fullWidth).toInt().coerceAtLeast(1)
                val h = ((clip.height()) * fullHeight).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val startX = -(clip.left * fullWidth).toInt()
                val startY = -(clip.top * fullHeight).toInt()
                page(index).renderPageBitmap(bmp, startX, startY, fullWidth, fullHeight, annotations, false, Color.WHITE, Color.WHITE)
                if (nightMode) invert(bmp) else bmp
            }
        }

    private fun invert(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (c and 0xFF000000.toInt()) or (0xFFFFFF - (c and 0xFFFFFF))
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        src.recycle()
        return out
    }

    fun invalidateCache() = bitmapCache.evictAll()

    suspend fun text(index: Int): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            page(index).openTextPage().use { tp ->
                val n = tp.textPageCountChars()
                if (n <= 0) "" else (tp.textPageGetText(0, n) ?: "")
            }
        }
    }

    /** All text of the document, page by page. */
    suspend fun allText(): List<String> = (0 until pageCount).map { text(it) }

    suspend fun outline(): List<OutlineItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            fun conv(b: io.legere.pdfiumandroid.api.Bookmark, level: Int): OutlineItem =
                OutlineItem(b.title ?: "", b.pageIdx.toInt(), level, b.children.map { conv(it, level + 1) })
            doc.getTableOfContents().map { conv(it, 0) }
        }
    }

    suspend fun links(index: Int): List<PageLink> = withContext(Dispatchers.IO) {
        mutex.withLock { page(index).getPageLinks().map { PageLink(it.bounds, it.destPageIdx, it.uri) } }
    }

    suspend fun meta(): DocMeta = withContext(Dispatchers.IO) {
        mutex.withLock {
            val m = doc.getDocumentMeta()
            DocMeta(m.title ?: "", m.author ?: "", m.subject ?: "", m.keywords ?: "", m.creator ?: "", m.producer ?: "", m.creationDate ?: "", m.modDate ?: "")
        }
    }

    /** Character boxes for a page in page points, origin bottom-left (PDF space). */
    suspend fun charBoxes(index: Int): List<RectF> = withContext(Dispatchers.IO) {
        mutex.withLock {
            page(index).openTextPage().use { tp ->
                val n = tp.textPageCountChars()
                (0 until n).map { tp.textPageGetCharBox(it) ?: RectF() }
            }
        }
    }

    /** Text inside a rectangle given in page points (PDF space). */
    suspend fun textInRect(index: Int, rect: RectF): String = withContext(Dispatchers.IO) {
        mutex.withLock { page(index).openTextPage().use { it.textPageGetBoundedText(rect, 4096) ?: "" } }
    }

    suspend fun search(query: String, matchCase: Boolean = false, wholeWord: Boolean = false, pages: IntRange = 0 until pageCount): List<SearchHit> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val flags = buildSet { if (matchCase) add(FindFlags.MatchCase); if (wholeWord) add(FindFlags.MatchWholeWord) }
            val hits = ArrayList<SearchHit>()
            for (pi in pages) {
                mutex.withLock {
                    page(pi).openTextPage().use { tp ->
                        val total = tp.textPageCountChars()
                        tp.findStart(query, flags, 0)?.use { fr ->
                            while (fr.findNext()) {
                                val idx = fr.getSchResultIndex(); val len = fr.getSchCount()
                                val rc = tp.textPageCountRects(idx, len)
                                val rects = (0 until rc).mapNotNull { tp.textPageGetRect(it) }
                                val s = (idx - 30).coerceAtLeast(0); val e = (idx + len + 30).coerceAtMost(total)
                                val snippet = if (e > s) (tp.textPageGetText(s, e - s) ?: query).replace('\n', ' ') else query
                                hits += SearchHit(pi, idx, len, rects, snippet)
                            }
                        }
                    }
                }
            }
            hits
        }

    /**
     * Search with a custom matcher over each page's text (e.g. Arabic-aware folding). [matcher] returns
     * char ranges of the page text; rects come from PDFium so hits look exactly like [search] hits.
     */
    suspend fun searchWith(pages: IntRange = 0 until pageCount, matcher: (String) -> List<IntRange>): List<SearchHit> =
        withContext(Dispatchers.IO) {
            val hits = ArrayList<SearchHit>()
            for (pi in pages) {
                if (closed) break
                mutex.withLock {
                    page(pi).openTextPage().use { tp ->
                        val total = tp.textPageCountChars()
                        if (total <= 0) return@use
                        val text = tp.textPageGetText(0, total) ?: return@use
                        for (r in matcher(text)) {
                            val idx = r.first; val len = (r.last + 1 - r.first).coerceAtMost(total - idx)
                            if (idx < 0 || len <= 0) continue
                            val rc = tp.textPageCountRects(idx, len)
                            val rects = (0 until rc).mapNotNull { tp.textPageGetRect(it) }
                            hits += SearchHit(pi, idx, len, rects, com.smartdocs.editor.core.search.TextMatcher.snippet(text, r))
                        }
                    }
                }
            }
            hits
        }

    /**
     * Close without blocking the caller's thread: waits for an in-flight render/text call to release the
     * native lock before freeing the document. Prefer this from coroutines.
     */
    suspend fun closeAsync() {
        if (closed) return
        closed = true
        // a cancelled caller must still free the native document (audit 5 OCR-5 follow-up)
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { mutex.withLock { closeLocked() } }
    }

    /**
     * Blocking close. Waits for any in-flight native call (render, text, search) so PDFium is never
     * freed while another thread is inside it. Safe to call more than once.
     */
    override fun close() {
        if (closed) return
        closed = true
        kotlinx.coroutines.runBlocking { mutex.withLock { closeLocked() } }
    }

    private fun closeLocked() {
        runCatching { pageCache.values.forEach { runCatching { it.close() } } }
        pageCache.clear()
        bitmapCache.evictAll()
        runCatching { doc.close() }
    }

    companion object {
        private const val MAX_OPEN_PAGES = 12
        @Volatile private var coreInstance: PdfiumCore? = null
        fun core(context: Context): PdfiumCore = coreInstance ?: synchronized(this) {
            coreInstance ?: PdfiumCore(context.applicationContext).also { coreInstance = it }
        }

        class PasswordRequiredException : Exception("password required")

        /** Open a local file. Throws [PasswordRequiredException] when the file is encrypted and no/wrong password given. */
        suspend fun open(context: Context, file: File, password: String? = null): PdfSession = withContext(Dispatchers.IO) {
            val core = core(context)
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val doc = try {
                if (password.isNullOrEmpty()) core.newDocument(pfd) else core.newDocument(pfd, password)
            } catch (e: Exception) {
                runCatching { pfd.close() }
                val msg = e.message.orEmpty().lowercase()
                if ("password" in msg || "pdfium" in msg && "security" in msg || e is io.legere.pdfiumandroid.api.PdfPasswordException) throw PasswordRequiredException()
                throw e
            }
            PdfSession(core, doc, file)
        }
    }
}
