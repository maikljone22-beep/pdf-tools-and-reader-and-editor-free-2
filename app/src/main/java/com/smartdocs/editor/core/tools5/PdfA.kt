package com.smartdocs.editor.core.tools5

import android.content.Context
import com.smartdocs.editor.core.pdf.PdfSession
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Calendar

enum class Severity { ERROR, WARNING, INFO }

/** One preflight finding. [code] maps to a string resource "t5_pf_<code>" in the UI; [pages] are zero-based. */
data class Finding(val code: String, val severity: Severity, val count: Int = 0, val pages: List<Int> = emptyList(), val fixed: Boolean = false, val detail: String = "")

class PreflightReport(val facts: PdfFacts, val findings: List<Finding>) {
    val errors get() = findings.count { it.severity == Severity.ERROR && !it.fixed }
    val passes get() = errors == 0
}

data class PdfAOptions(
    /** Pages using fonts that are not embedded (or DeviceCMYK without a CMYK intent) are replaced by a 200 dpi picture. */
    val rasterizeProblemPages: Boolean = true,
    /** Also rasterise pages with transparency (not required by PDF/A-2b; for strict PDF/A-1-style consumers). */
    val flattenTransparency: Boolean = false,
    /** PDF/A-2b only allows PDF/A attachments; attachments cannot be verified here so they are removed. */
    val removeAttachments: Boolean = true,
    val dpi: Int = 200,
)

/** PDF/A-2b preflight + conversion (best effort, on device). */
object PdfA {

    /** Preflight against PDF/A-2b. */
    fun preflight(doc: PDDocument): PreflightReport = report(PdfInspect.inspect(doc))

    fun report(f: PdfFacts, fixedCodes: Set<String> = emptySet()): PreflightReport {
        val out = ArrayList<Finding>()
        fun add(code: String, sev: Severity, n: Int = 0, pages: List<Int> = emptyList(), detail: String = "") { out += Finding(code, sev, n, pages, code in fixedCodes, detail) }
        if (f.encrypted) add("encrypted", Severity.ERROR)
        if (f.fontsNotEmbedded.isNotEmpty()) add("fonts", Severity.ERROR, f.fontsNotEmbedded.size, detail = f.fontsNotEmbedded.joinToString(", ") { it.name }.take(300))
        if (f.javascript) add("js", Severity.ERROR)
        if (!f.hasXmp || f.pdfaClaim == null) add("xmp", Severity.ERROR)
        if (f.outputIntents == 0) add("intent", Severity.ERROR)
        if (f.cmykPages.isNotEmpty()) add("cmyk", Severity.ERROR, f.cmykPages.size, f.cmykPages.sorted())
        if (f.embeddedFiles > 0) add("attachments", Severity.ERROR, f.embeddedFiles)
        if (f.multimedia > 0) add("multimedia", Severity.ERROR, f.multimedia)
        if (f.lzw) add("lzw", Severity.ERROR)
        if (f.xfa) add("xfa", Severity.ERROR)
        if (f.annotationsWithoutAppearance > 0) add("annot_ap", Severity.WARNING, f.annotationsWithoutAppearance)
        if (f.version > 1.7f) add("version", Severity.WARNING, detail = f.version.toString())
        if (f.transparencyPages.isNotEmpty()) add("transparency", Severity.INFO, f.transparencyPages.size, f.transparencyPages.sorted())
        if (f.fontsWithoutUnicode.isNotEmpty()) add("unicode", Severity.INFO, f.fontsWithoutUnicode.size)
        out += Finding("linearize", Severity.INFO)            // "fast web view" is not produced (PdfBox 2.0 cannot linearise)
        return PreflightReport(f, out)
    }

    private fun stripJs(d: COSDictionary?) {
        d ?: return
        d.removeItem(N.AA)
        val a = d.getDictionaryObject(N.A) as? COSDictionary
        val s = (a?.getDictionaryObject(N.S) as? COSName)?.name
        if (s != null && s in setOf("JavaScript", "Launch", "Sound", "Movie", "ResetForm", "ImportData", "Hide", "SetOCGState", "Rendition", "Trans", "GoTo3DView")) d.removeItem(N.A)
    }

    private fun lzwToFlate(s: COSStream) {
        val f = s.getDictionaryObject(N.Filter)
        val has = f == N.LZW || (f is COSArray && (0 until f.size()).any { f.getObject(it) == N.LZW })
        if (!has) return
        val data = s.createInputStream().use { it.readBytes() }
        s.removeItem(N.n("DecodeParms"))
        s.createOutputStream(COSName.FLATE_DECODE).use { it.write(data) }
    }

    private fun cleanResources(res: PDResources?, seen: MutableSet<Any>, depth: Int = 0) {
        if (res == null || depth > 12 || !seen.add(res.cosObject)) return
        runCatching { res.extGStateNames.forEach { n -> res.getExtGState(n)?.cosObject?.let { it.removeItem(N.TR); it.removeItem(N.TR2) } } }
        runCatching {
            res.xObjectNames.forEach { n ->
                when (val x = runCatching { res.getXObject(n) }.getOrNull()) {
                    is PDImageXObject -> { x.cosObject.removeItem(N.n("Alternates")); x.cosObject.removeItem(N.n("OPI")); x.cosObject.setBoolean(N.Interpolate, false); lzwToFlate(x.cosObject) }
                    is PDFormXObject -> { x.cosObject.removeItem(N.n("OPI")); x.cosObject.removeItem(N.n("PS")); lzwToFlate(x.cosObject); cleanResources(x.resources, seen, depth + 1) }
                    else -> {}
                }
            }
        }
    }

    private fun outputIntent(doc: PDDocument): COSDictionary {
        val icc = doc.document.createCOSStream()
        icc.setInt(N.n("N"), 3)
        icc.createOutputStream(COSName.FLATE_DECODE).use { it.write(IccSrgb.bytes) }
        return COSDictionary().apply {
            setItem(N.Type, N.n("OutputIntent"))
            setItem(N.S, N.n("GTS_PDFA1"))
            setString(N.n("OutputConditionIdentifier"), "sRGB IEC61966-2.1")
            setString(N.n("Info"), "sRGB IEC61966-2.1")
            setString(N.n("RegistryName"), "http://www.color.org")
            setItem(N.n("DestOutputProfile"), icc)
        }
    }

    /** Replace the content of [pages] by a picture of themselves (fonts / CMYK / transparency problems). */
    private suspend fun rasterize(ctx: Context, src: File, password: String?, doc: PDDocument, pages: Set<Int>, dpi: Int) {
        if (pages.isEmpty()) return
        val s = PdfSession.open(ctx, src, password)
        try {
            pages.sorted().forEach { i ->
                val (w, h) = s.pageSize(i)
                val k = minOf(dpi / 72f, 3000f / maxOf(w, h))
                val bmp = s.render(i, (w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), annotations = false)
                val img = JPEGFactory.createFromImage(doc, bmp, 0.85f)
                s.invalidateCache(); bmp.recycle()
                val page = doc.getPage(i)
                page.cosObject.removeItem(N.Group)
                page.resources = PDResources()
                // the picture is in display orientation: draw it on an unrotated page of the displayed size
                val box = page.cropBox
                val rot = page.rotation.mod(360)
                page.rotation = 0
                val dispW = if (rot == 90 || rot == 270) box.height else box.width
                val dispH = if (rot == 90 || rot == 270) box.width else box.height
                val nb = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(box.lowerLeftX, box.lowerLeftY, dispW, dispH)
                page.mediaBox = nb; page.cropBox = nb
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs -> cs.drawImage(img, nb.lowerLeftX, nb.lowerLeftY, dispW, dispH) }
            }
        } finally { s.closeAsync() }
    }

    /**
     * Convert [src] to PDF/A-2b at [out]. Returns the preflight of the result (self-check) and the codes that were fixed.
     */
    suspend fun convert(ctx: Context, src: File, password: String?, out: File, opt: PdfAOptions = PdfAOptions(), title: String? = null,
                        onProgress: (Int, Int) -> Unit = { _, _ -> }): Pair<PreflightReport, Set<String>> = withContext(Dispatchers.IO) {
        val fixed = HashSet<String>()
        // temp-file memory, progress and Cancel between pages (convert audit C10 / C29)
        val doc = PDDocument.load(src, password.orEmpty(), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(48L shl 20))
        val total = doc.numberOfPages.coerceAtLeast(1) + 2
        onProgress(0, total)
        doc.use { d ->
            val before = PdfInspect.inspect(d)
            if (d.isEncrypted) { d.isAllSecurityToBeRemoved = true; fixed += "encrypted" }
            // pages to rasterise
            val bad = HashSet<Int>()
            if (opt.rasterizeProblemPages && before.fontsNotEmbedded.isNotEmpty()) bad += pagesWithFonts(d, before.fontsNotEmbedded.map { it.name }.toSet())
            if (opt.rasterizeProblemPages) bad += before.cmykPages
            if (opt.flattenTransparency) bad += before.transparencyPages
            if (bad.isNotEmpty()) {
                rasterize(ctx, src, password, d, bad, opt.dpi)
                if (before.fontsNotEmbedded.isNotEmpty()) fixed += "fonts"
                if (before.cmykPages.isNotEmpty()) fixed += "cmyk"
                if (opt.flattenTransparency && before.transparencyPages.isNotEmpty()) fixed += "transparency"
            }
            val cat = d.documentCatalog.cosObject
            // JavaScript and forbidden actions
            (cat.getDictionaryObject(N.Names) as? COSDictionary)?.removeItem(N.JavaScript)
            cat.removeItem(N.AA)
            (cat.getDictionaryObject(N.OpenAction) as? COSDictionary)?.let { oa -> val s = (oa.getDictionaryObject(N.S) as? COSName)?.name; if (s != null && s != "GoTo") cat.removeItem(N.OpenAction) }
            // forms
            (cat.getDictionaryObject(N.AcroForm) as? COSDictionary)?.let { af -> af.removeItem(N.XFA); af.setBoolean(N.NeedAppearances, false) }
            val seen = HashSet<Any>()
            onProgress(1, total)
            d.pages.forEachIndexed { pi, page ->
                kotlin.coroutines.coroutineContext.ensureActive()
                onProgress(1 + pi, total)
                page.cosObject.removeItem(N.AA)
                page.cosObject.removeItem(N.PieceInfo)
                cleanResources(page.resources, seen)
                runCatching { page.contentStreams.forEach { lzwToFlate(it.cosObject) } }
                val keep = page.annotations.filter { a ->
                    val sub = a.subtype ?: ""
                    when {
                        sub in setOf("Screen", "Movie", "Sound", "RichMedia", "3D", "FileAttachment") -> false
                        sub != "Popup" && sub != "Link" && !a.cosObject.containsKey(N.AP) -> false
                        else -> true
                    }
                }
                keep.forEach { a ->
                    stripJs(a.cosObject)
                    if (a.subtype != "Popup") {
                        // print on, hidden / invisible / noview / togglenoview off
                        val f = a.cosObject.getInt(N.F, 0)
                        a.cosObject.setInt(N.F, (f or 4) and (1 or 2 or 32 or 256).inv())
                    }
                }
                if (keep.size != page.annotations.size) page.annotations = keep
            }
            if (before.javascript) fixed += "js"
            if (before.multimedia > 0) fixed += "multimedia"
            if (before.annotationsWithoutAppearance > 0) fixed += "annot_ap"
            if (before.lzw) fixed += "lzw"
            if (before.xfa) fixed += "xfa"
            if (opt.removeAttachments && before.embeddedFiles > 0) { (cat.getDictionaryObject(N.Names) as? COSDictionary)?.removeItem(N.EmbeddedFiles); fixed += "attachments" }
            // output intent (sRGB)
            cat.setItem(N.OutputIntents, COSArray().apply { add(outputIntent(d)) }); fixed += "intent"
            // info + XMP kept in sync
            val info = d.documentInformation
            val now = Calendar.getInstance()
            val created = runCatching { info.creationDate }.getOrNull() ?: now
            info.creationDate = created; info.modificationDate = now
            if (!title.isNullOrBlank() && info.title.isNullOrBlank()) info.title = title
            info.producer = "SmartDocs"
            if (info.creator.isNullOrBlank()) info.creator = "SmartDocs"
            val xmp = XmpWriter.packet(info.title, info.author, info.subject, info.keywords, info.creator ?: "SmartDocs", "SmartDocs", XmpWriter.isoDate(created), XmpWriter.isoDate(now))
            val md = PDMetadata(d)
            md.importXMPMetadata(xmp.toByteArray(Charsets.UTF_8))
            d.documentCatalog.metadata = md
            fixed += "xmp"
            // trailer /ID and version
            val id = MessageDigest.getInstance("MD5").digest((src.absolutePath + now.timeInMillis).toByteArray())
            d.document.trailer.setItem(N.ID, COSArray().apply { add(COSString(id)); add(COSString(id)) })
            if (d.version > 1.7f || d.version < 1.4f) { d.version = 1.7f; fixed += "version" }
            kotlin.coroutines.coroutineContext.ensureActive()
            onProgress(total - 1, total)
            d.save(out)
        }
        onProgress(total, total)
        // self-check of the written file
        val after = PDDocument.load(out).use { PdfInspect.inspect(it) }
        report(after) to fixed
    }

    /** Pages whose resources (directly or in forms) use one of [fontNames]. */
    private fun pagesWithFonts(doc: PDDocument, fontNames: Set<String>): Set<Int> {
        val res = HashSet<Int>()
        fun uses(r: PDResources?, depth: Int): Boolean {
            if (r == null || depth > 8) return false
            if (runCatching { r.fontNames.any { fn -> runCatching { r.getFont(fn)?.name }.getOrNull() in fontNames } }.getOrDefault(false)) return true
            return runCatching { r.xObjectNames.any { xn -> (runCatching { r.getXObject(xn) }.getOrNull() as? PDFormXObject)?.let { uses(it.resources, depth + 1) } == true } }.getOrDefault(false)
        }
        doc.pages.forEachIndexed { i, p -> if (uses(p.resources, 0)) res += i }
        return res
    }

}
