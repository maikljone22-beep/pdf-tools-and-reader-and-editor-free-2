package com.smartdocs.editor.core.tools5

import android.content.Context
import com.smartdocs.editor.core.edit.OutlineModel
import com.smartdocs.editor.core.edit5.E5Bookmark
import com.smartdocs.editor.core.edit5.E5Io
import com.smartdocs.editor.core.edit5.OutlineTools
import com.smartdocs.editor.core.edit5.PageOrganize
import com.smartdocs.editor.core.edit5.PageSet
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Sort order of the combine list (Foxit Combine: name / modified / size). */
enum class FileSort { NAME, TIME, SIZE }

/** One file of a combine job: its bookmark title, the pages to take ("" = all, "1-3, 7") and the password of a protected PDF. */
data class CombineEntry(val file: File, val title: String, val pages: String = "", val attach: Boolean = false, val password: String? = null)

/** Options of [CombinePlus.combine] (Nitro 5 / Foxit 4 Combine settings). */
data class CombineOpts(
    /** One top-level bookmark per file. */
    val bookmarkPerFile: Boolean = true,
    /** Keep each PDF's own bookmarks (under its file bookmark) and its attachments. */
    val keepBookmarks: Boolean = true,
    /** Converted files (Word, HTML, text …): their headings become bookmarks (Nitro "convert headings to bookmarks"). */
    val headings: Boolean = false,
    /** Insert a table-of-contents page built from the bookmarks at the front. */
    val toc: Boolean = false,
    val tocTitle: String = "",
    val tocLevels: Int = 2,
)

object CombinePlus {

    /** [list] in [by] order ([desc] = largest / newest / Z first). Pure over the key functions. */
    fun <T> sorted(list: List<T>, by: FileSort, desc: Boolean, name: (T) -> String, time: (T) -> Long, size: (T) -> Long): List<T> {
        val c: Comparator<T> = when (by) {
            FileSort.NAME -> Comparator { a, b -> NaturalOrder.compare(name(a), name(b)) }
            FileSort.TIME -> compareBy(time)
            FileSort.SIZE -> compareBy(size)
        }
        return list.sortedWith(if (desc) c.reversed() else c)
    }

    fun sortFiles(files: List<File>, by: FileSort, desc: Boolean): List<File> = sorted(files, by, desc, { it.name }, { it.lastModified() }, { it.length() })

    /** Titles nested by level under [parent]; pages outside [map] are skipped. Returns the bookmarks written. */
    internal fun addHeadings(parent: PDOutlineNode, heads: List<E5Bookmark>, pageOf: (Int) -> PDPage?): Int {
        val stack = ArrayList<Pair<Int, PDOutlineNode>>()   // (level, node)
        var n = 0
        for (h in heads) {
            val page = pageOf(h.page) ?: continue
            while (stack.isNotEmpty() && stack.last().first >= h.level) stack.removeAt(stack.lastIndex)
            val item = PDOutlineItem().apply { title = h.title; destination = PDPageFitDestination().apply { setPage(page) } }
            (stack.lastOrNull()?.second ?: parent).addLast(item)
            stack += h.level to item; n++
        }
        return n
    }

    /**
     * Combine [entries] in order into [out]: non-PDF files are converted first; each file contributes the pages of its
     * range. Bookmarks follow [o]; the TOC page (when asked) is built from the final outline. Returns [out].
     */
    /**
     * Combine [entries] in order into [out]: non-PDF files are converted first (kinds by content); each file contributes
     * the pages of its range. A protected PDF uses its [CombineEntry.password] ([com.smartdocs.editor.core.convert6.Cv6NeedPassword]
     * names the file when it is missing); a range that is not valid or selects nothing is an error naming the file
     * instead of the file silently missing from the result (convert audit A-M12). Bookmarks follow [o]; the TOC page
     * (when asked) is built from the final outline. Returns [out].
     */
    suspend fun combine(ctx: Context, entries: List<CombineEntry>, out: File, tempDir: File, o: CombineOpts = CombineOpts(),
                        onProgress: (Int, Int) -> Unit = { _, _ -> }, signed: (File) -> Unit = {}): File {
        val temps = ArrayList<File>()
        try {
            // PDF Binder: files marked "attach" are embedded in the result instead of converted
            val attached = entries.filter { it.attach }
            entries.filterNot { it.attach }.forEach { e -> if (!com.smartdocs.editor.core.pdf.PdfOps.rangeSyntaxOk(e.pages)) throw com.smartdocs.editor.core.convert6.InvalidRange(e.file.name) }
            val parts = entries.filterNot { it.attach }.mapIndexed { i, e ->
                coroutineContext.ensureActive()
                onProgress(i, entries.size)
                val pdf = if (ToPdf.kindOf(e.file) == SourceKind.PDF) withContext(Dispatchers.IO) { ToPdf.mergeablePdf(e.file, e.password, tempDir, temps, signed) }
                    else File(tempDir, "cmb_${i}_${System.nanoTime()}.pdf").also { t -> temps += t; ToPdf.convert(ctx, e.file, t) }
                val heads = if (o.headings && pdf !== e.file) runCatching { OutlineTools.toBookmarks(OutlineTools.detectHeadings(pdf, null)) }.getOrDefault(emptyList()) else emptyList()
                Triple(e, pdf, heads)
            }
            onProgress(entries.size, entries.size)
            withContext(Dispatchers.IO) {
                val docs = ArrayList<PDDocument>()
                val marks = ArrayList<E5Bookmark>()     // for the TOC page
                val tmp = File(tempDir, "cmb_out_${System.nanoTime()}.pdf").also { temps += it }
                try {
                    PDDocument(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(48L shl 20)).use { nd ->
                        val outline = PDDocumentOutline()
                        for ((e, pdf, heads) in parts) {
                            coroutineContext.ensureActive()
                            val sd = E5Io.load(pdf).also { docs += it }
                            val pages = PageSet.parse(com.smartdocs.editor.core.pdf.PdfOps.normalizeRange(e.pages), sd.numberOfPages)
                            if (pages.isEmpty()) throw com.smartdocs.editor.core.convert6.InvalidRange(e.file.name)
                            val start = nd.numberOfPages
                            val map = HashMap<COSDictionary, PDPage>()
                            val byIndex = HashMap<Int, PDPage>()
                            pages.forEach { p -> val sp = sd.getPage(p); val np = nd.importPage(sp); map[sp.cosObject] = np; byIndex[p] = np }
                            val holder: PDOutlineNode = if (o.bookmarkPerFile) PDOutlineItem().apply {
                                title = e.title; destination = PDPageFitDestination().apply { setPage(nd.getPage(start)) }
                                outline.addLast(this)
                                marks += E5Bookmark(OutlineModel.newKey(), e.title, start, 0)
                            } else outline
                            val sub = if (o.bookmarkPerFile) 1 else 0
                            if (o.keepBookmarks && sd.documentCatalog.documentOutline != null) {
                                // the copy goes under the holder; for the top level copy straight into the outline being built
                                PageOrganize.copyOutline(sd, nd, map, holder.cosObject)
                            }
                            if (heads.isNotEmpty()) addHeadings(holder, heads) { byIndex[it] }
                            heads.filter { byIndex.containsKey(it.page) }.forEach { h -> marks += h.copy(page = start + pages.indexOf(h.page), level = h.level + sub) }
                            if (o.keepBookmarks) runCatching { PageOrganize.copyAttachments(sd, nd) }
                        }
                        if (outline.firstChild != null) nd.documentCatalog.documentOutline = outline
                        if (attached.isNotEmpty()) Embed.addFiles(nd, attached.map { Attachment(it.file.name, Embed.mimeOf(it.file.name), it.file.readBytes()) })
                        nd.documentInformation.creator = "SmartDocs"
                        if (nd.numberOfPages == 0) throw IllegalArgumentException("no pages")
                        E5Io.save(nd, tmp)
                    }
                } finally { docs.forEach { runCatching { it.close() } } }
                if (o.toc && marks.isNotEmpty()) OutlineTools.insertToc(ctx, tmp, out, null, marks, o.tocTitle.ifBlank { "Contents" }, 0, maxLevel = o.tocLevels - 1)
                else tmp.copyTo(out, overwrite = true)
            }
            return out
        } finally { temps.forEach { runCatching { it.delete() } } }
    }
}

/** Natural order for file names ("p2" before "p10"). Pure. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("""\d+|\D+""")
    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList(); val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) (p.trimStart('0').length - q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0')) else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}

/** Office core properties (docProps/core.xml): title, author, subject, keywords — kept on the converted PDF. */
object OfficeProps {
    private val tags = mapOf("title" to "dc:title", "author" to "dc:creator", "subject" to "dc:subject", "keywords" to "cp:keywords")

    /** Pure: the non-blank properties of a core.xml text. */
    fun parse(xml: String): Map<String, String> = tags.mapNotNull { (k, tag) ->
        Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let { k to unescape(it).trim() }
    }.filter { it.second.isNotEmpty() }.toMap()

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun read(office: File): Map<String, String> = runCatching {
        java.util.zip.ZipFile(office).use { z -> z.getEntry("docProps/core.xml")?.let { e -> z.getInputStream(e).use { parse(it.readBytes().toString(Charsets.UTF_8)) } } }
    }.getOrNull().orEmpty()

    /** Writes [props] into the information dictionary of [pdf] (in place). */
    fun applyTo(pdf: File, props: Map<String, String>) {
        if (props.isEmpty()) return
        E5Io.edit(pdf, pdf, null) { d ->
            val info = d.documentInformation
            props["title"]?.let { info.title = it }; props["author"]?.let { info.author = it }
            props["subject"]?.let { info.subject = it }; props["keywords"]?.let { info.keywords = it }
            d.documentInformation = info
        }
    }
}

/**
 * PDF Binder project (Foxit .bdr): the ordered entries and options of a combine job, kept to rebuild the binder later.
 * Text format (pure, JVM-tested): first line "opts=…", then one line per entry "file|title|pages|attach".
 */
data class BinderProject(val name: String, val opts: CombineOpts, val entries: List<CombineEntry>) {
    fun encode(): String = buildString {
        fun esc(v: String) = v.replace("%", "%25").replace("|", "%7C").replace("\n", " ")
        append("name=").append(esc(name)).append('\n')
        append("opts=").append(listOf(opts.bookmarkPerFile, opts.keepBookmarks, opts.headings, opts.toc).joinToString(",")).append('|').append(esc(opts.tocTitle)).append('|').append(opts.tocLevels).append('\n')
        entries.forEach { e -> append(esc(e.file.name)).append('|').append(esc(e.title)).append('|').append(esc(e.pages)).append('|').append(e.attach).append('\n') }
    }

    companion object {
        private fun unesc(v: String) = v.replace("%7C", "|").replace("%25", "%")

        /** [dir] holds the entries' files. */
        fun decode(text: String, dir: File): BinderProject {
            val lines = text.lines().filter { it.isNotBlank() }
            val name = lines.firstOrNull { it.startsWith("name=") }?.removePrefix("name=")?.let(::unesc).orEmpty()
            val o = lines.firstOrNull { it.startsWith("opts=") }?.removePrefix("opts=")?.split('|')
            val flags = o?.getOrNull(0)?.split(',')?.map { it == "true" }.orEmpty()
            val opts = CombineOpts(flags.getOrElse(0) { true }, flags.getOrElse(1) { true }, flags.getOrElse(2) { false }, flags.getOrElse(3) { false },
                o?.getOrNull(1)?.let(::unesc).orEmpty(), o?.getOrNull(2)?.toIntOrNull() ?: 2)
            val entries = lines.filterNot { it.startsWith("name=") || it.startsWith("opts=") }.mapNotNull { l ->
                val p = l.split('|'); if (p.size < 4) return@mapNotNull null
                CombineEntry(File(dir, unesc(p[0])), unesc(p[1]), unesc(p[2]), p[3] == "true")
            }
            return BinderProject(name, opts, entries)
        }
    }
}

/** Saved binder projects in files/binders/<id>/ (manifest + copies of the files). */
object BinderStore {
    fun root(ctx: Context) = File(ctx.filesDir, "binders").apply { mkdirs() }

    fun save(ctx: Context, p: BinderProject): File {
        val dir = File(root(ctx), System.currentTimeMillis().toString()).apply { mkdirs() }
        val used = HashSet<String>()
        val copied = p.entries.map { e ->
            var n = e.file.name; var k = 2
            while (!used.add(n)) n = e.file.nameWithoutExtension + "_" + (k++) + "." + e.file.extension
            e.file.copyTo(File(dir, n), true); e.copy(file = File(dir, n))
        }
        File(dir, "binder.txt").writeText(p.copy(entries = copied).encode())
        return dir
    }

    fun list(ctx: Context): List<Pair<File, BinderProject>> = (root(ctx).listFiles() ?: emptyArray())
        .filter { File(it, "binder.txt").isFile }.sortedByDescending { it.name }
        .mapNotNull { d -> runCatching { d to BinderProject.decode(File(d, "binder.txt").readText(), d) }.getOrNull() }

    fun delete(dir: File) { dir.deleteRecursively() }
}
