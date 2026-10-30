package com.smartdocs.editor.core.tools5

import android.content.Context
import com.smartdocs.editor.core.edit5.E5Io
import com.smartdocs.editor.core.pdf.ArabicShaper
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.showArabic
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** One search hit inside a portfolio part. */
data class PfHit(val key: String, val name: String, val page: Int, val snippet: String)

/** Foxit 7 portfolio operations that need files, fonts or the converters (Android side of [PortfolioEdit]). */
object PortfolioOps {

    /** Load [src], change it with [block], save to [out] (may be [src]). */
    suspend fun edit(src: File, password: String?, out: File, block: (PDDocument) -> Unit): File = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { block(it) }; out
    }

    suspend fun info(src: File, password: String?): PfInfo = withContext(Dispatchers.IO) { E5Io.load(src, password).use { PortfolioEdit.read(it) } }

    private fun bytesOf(doc: PDDocument, key: String): ByteArray? = Embed.all(doc)[key]?.let { s -> (s.embeddedFileUnicode ?: s.embeddedFile)?.toByteArray() }

    /** Text search across every PDF part (Foxit "search across portfolio files"). */
    suspend fun search(src: File, password: String?, query: String, max: Int = 200): List<PfHit> = withContext(Dispatchers.IO) {
        val q = query.trim(); if (q.isEmpty()) return@withContext emptyList()
        val out = ArrayList<PfHit>()
        E5Io.load(src, password).use { doc ->
            for (it in PortfolioEdit.read(doc).items) {
                coroutineContext.ensureActive()
                if (!it.name.endsWith(".pdf", true) && it.mime != "application/pdf") continue
                val bytes = bytesOf(doc, it.key) ?: continue
                runCatching {
                    PDDocument.load(bytes).use { pd ->
                        val st = PDFTextStripper()
                        for (p in 0 until pd.numberOfPages) {
                            coroutineContext.ensureActive()
                            st.startPage = p + 1; st.endPage = p + 1
                            val text = st.getText(pd)
                            var i = text.indexOf(q, ignoreCase = true)
                            while (i >= 0 && out.size < max) {
                                val a = (i - 40).coerceAtLeast(0); val b = (i + q.length + 40).coerceAtMost(text.length)
                                out += PfHit(it.key, it.name, p, text.substring(a, b).replace('\n', ' ').trim())
                                i = text.indexOf(q, i + q.length, ignoreCase = true)
                            }
                            if (out.size >= max) break
                        }
                    }
                }
                if (out.size >= max) break
            }
        }
        out
    }

    /** "Reduce file size" of the PDF parts [keys] (optimizer with its defaults). Returns [out]. */
    suspend fun compressParts(src: File, password: String?, keys: List<String>, out: File, tempDir: File): File {
        val parts = HashMap<String, File>()
        try {
            withContext(Dispatchers.IO) {
                E5Io.load(src, password).use { doc -> keys.forEach { k -> bytesOf(doc, k)?.let { b -> parts[k] = File(tempDir, "pf_in_${System.nanoTime()}.pdf").also { it.writeBytes(b) } } } }
            }
            val small = parts.mapValues { (_, f) ->
                coroutineContext.ensureActive()
                val o = File(tempDir, "pf_out_${System.nanoTime()}.pdf")
                runCatching { Optimizer.optimize(f, null, o, OptimizeOptions()) }
                if (o.isFile && o.length() in 1 until f.length()) o else f
            }
            return Portfolio.replaceParts(src, password, out, small)
        } finally { parts.values.forEach { it.delete() }; tempDir.listFiles { f -> f.name.startsWith("pf_out_") }?.forEach { it.delete() } }
    }

    /** Converts a non-PDF part (image, Office, text, HTML …) to PDF inside the portfolio; the original is replaced. */
    suspend fun convertPart(ctx: Context, src: File, password: String?, key: String, out: File, tempDir: File): File {
        val (fid, name) = PortfolioEdit.splitKey(key)
        val inFile = File(tempDir, "pfc_${System.nanoTime()}_" + name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        val pdf = File(tempDir, "pfc_${System.nanoTime()}.pdf")
        try {
            withContext(Dispatchers.IO) { E5Io.load(src, password).use { doc -> inFile.writeBytes(bytesOf(doc, key) ?: error("no part")) } }
            ToPdf.convert(ctx, inFile, pdf)
            return edit(src, password, out) { doc ->
                val folder = PortfolioEdit.folderPaths(doc)[fid].orEmpty()
                val desc = Embed.all(doc)[key]?.fileDescription
                PortfolioEdit.remove(doc, listOf(key))
                val nk = PortfolioEdit.add(doc, listOf(Attachment(name.substringBeforeLast('.') + ".pdf", "application/pdf", pdf.readBytes())), folder)
                if (desc != null) nk.firstOrNull()?.let { PortfolioEdit.setDescription(doc, it, desc) }
            }
        } finally { inFile.delete(); pdf.delete() }
    }

    /**
     * FINAL_CHECK_2 #12: a part edited outside (opened in the reader, saved) goes back into the portfolio: same name,
     * folder and description, new bytes.
     */
    suspend fun replacePart(src: File, password: String?, key: String, edited: File, out: File): File = edit(src, password, out) { doc ->
        val (fid, name) = PortfolioEdit.splitKey(key)
        val folder = PortfolioEdit.folderPaths(doc)[fid].orEmpty()
        val desc = Embed.all(doc)[key]?.fileDescription
        PortfolioEdit.remove(doc, listOf(key))
        val nk = PortfolioEdit.add(doc, listOf(Attachment(name, Embed.mimeOf(name), edited.readBytes())), folder)
        if (desc != null) nk.firstOrNull()?.let { PortfolioEdit.setDescription(doc, it, desc) }
    }

    /**
     * Rebuilds the cover sheet (page 1): title, then every part grouped by folder; PDF parts get a link that opens
     * them (GoToE) — Foxit "portfolio table of contents" / "regenerate TOC".
     */
    suspend fun regenerateCover(ctx: Context, src: File, password: String?, out: File, title: String, folderLabel: String): File = edit(src, password, out) { doc ->
        val info = PortfolioEdit.read(doc)
        if (doc.numberOfPages == 0) doc.addPage(PDPage(PDRectangle.A4))
        val old = doc.getPage(0)
        val page = PDPage(old.mediaBox)
        doc.pages.insertBefore(page, old); doc.pages.remove(old)
        val arabic: PDFont = runCatching { PdfOps.arabicFont(ctx, doc) }.getOrDefault(PDType1Font.HELVETICA)
        fun fontFor(s: String) = if (ArabicShaper.isArabic(s) && arabic !is PDType1Font) arabic else PDType1Font.HELVETICA
        fun safe(s: String, f: PDFont) = if (f is PDType1Font) s.filter { it.code in 32..126 } else s
        val links = ArrayList<PDAnnotationLink>()
        PDPageContentStream(doc, page).use { cs ->
            val w = page.mediaBox.width; var y = page.mediaBox.height - 80f
            fun line(text: String, size: Float, indent: Float = 0f, key: String? = null) {
                if (y < 50f) return
                val f = fontFor(text)
                val shown = safe(if (ArabicShaper.isArabic(text)) ArabicShaper.shape(text) else text, f)
                if (shown.isNotBlank()) {
                    val tw = runCatching { f.getStringWidth(shown) / 1000f * size }.getOrDefault(0f)
                    val rtl = ArabicShaper.isArabic(text)
                    val x = if (rtl) w - 60f - indent - tw else 60f + indent
                    cs.beginText(); cs.setFont(f, size); cs.newLineAtOffset(x, y); cs.showArabic(text, shown); cs.endText()
                    if (key != null) links += PDAnnotationLink().also { l ->
                        l.rectangle = PDRectangle(x, y - 3f, tw, size + 4f)
                        l.borderStyle = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary().apply { width = 0f }
                        // GoToE: open the embedded file, first page
                        val target = COSDictionary().also { t -> t.setItem(COSName.getPDFName("R"), COSName.getPDFName("C")); t.setItem(COSName.N, COSString(key)) }
                        val d = COSArray().also { a -> a.add(COSInteger.ZERO); a.add(COSName.getPDFName("Fit")) }
                        val act = COSDictionary().also { a -> a.setItem(COSName.S, COSName.getPDFName("GoToE")); a.setItem(COSName.T, target); a.setItem(COSName.D, d) }
                        l.cosObject.setItem(COSName.A, act)
                    }
                }
                y -= size * 1.6f
            }
            line(title, 22f); y -= 8f
            val byFolder = info.items.groupBy { it.folder }
            (listOf("") + info.folders).forEach { folder ->
                val list = PortfolioEdit.sorted(byFolder[folder].orEmpty(), info.sortKey, info.ascending)
                if (list.isEmpty() && folder.isNotEmpty()) return@forEach
                val depth = if (folder.isEmpty()) 0 else folder.count { it == '/' } + 1
                if (folder.isNotEmpty()) line("$folderLabel: ${folder.substringAfterLast('/')}", 13f, 14f * (depth - 1))
                list.forEach { item ->
                    val pdf = item.name.endsWith(".pdf", true) || item.mime == "application/pdf"
                    line("• " + item.name + if (item.description.isNotBlank()) " — " + item.description else "", 11f, 14f * depth, if (pdf) item.key else null)
                }
            }
        }
        if (links.isNotEmpty()) page.annotations = links
    }
}
