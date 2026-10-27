package com.smartdocs.editor.core.tools5

import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Optimizer settings as JSON (Foxit "save / delete user presets": stored through the shared profile list, kind
 * "opt"), and the size estimate before running.
 */
object OptimizePresets {

    fun toJson(o: OptimizeOptions): String = JSONObject().apply {
        put("dpi", o.imageDpi); put("q", o.jpegQuality); put("recJpeg", o.recompressJpeg); put("grayImages", o.grayImages); put("dedup", o.dedupImages)
        put("meta", o.stripMetadata); put("js", o.stripJavaScript); put("thumbs", o.stripThumbnails); put("attach", o.stripAttachments)
        put("piece", o.stripPieceInfo); put("flate", o.flateUncompressed); put("smaller", o.onlyIfSmaller); put("colorMode", o.colorMode.name)
        put("separate", o.separateClasses); put("gDpi", o.grayDpi); put("gQ", o.grayQuality); put("grayMode", o.grayMode.name)
        put("mDpi", o.monoDpi); put("monoMode", o.monoMode.name); put("unembed", o.unembedFonts.name)
        put("rmAnnots", o.removeAnnotations); put("rmForms", o.removeForms); put("rmLinks", o.removeLinks); put("rmBookmarks", o.removeBookmarks)
        put("rmDests", o.removeNamedDests); put("keepProt", o.keepProtection); put("version", o.pdfVersion); put("downsample", o.downsample.name)
        put("above", o.aboveDpi); put("subset", o.subsetFonts); put("merge", o.mergeFragments); put("mrc", o.mrc); put("flatT", o.flattenTransparencyDpi)
        put("scanBg", o.scanBackground); put("descreen", o.scanDescreen); put("sharpen", o.scanSharpen); put("bw", o.bwImages)
        val c = o.cleanup
        put("cleanup", JSONObject().apply {
            put("formActions", c.formActions); put("alternates", c.alternateImages); put("tags", c.tags); put("print", c.printSettings)
            put("search", c.searchIndex); put("media", c.multimedia); put("external", c.externalLinks); put("objData", c.objectData)
            put("layers", c.flattenLayers); put("lzw", c.lzwToFlate); put("invalid", c.invalidLinks); put("unusedDests", c.unusedDests)
            put("content", c.optimizeContent); put("curves", c.linesToCurves)
        })
    }.toString()

    private inline fun <reified E : Enum<E>> JSONObject.enum(k: String, d: E): E = runCatching { enumValueOf<E>(getString(k)) }.getOrDefault(d)

    fun fromJson(s: String): OptimizeOptions? = runCatching {
        val o = JSONObject(s); val d = OptimizeOptions()
        val c = o.optJSONObject("cleanup") ?: JSONObject(); val dc = CleanupOptions()
        OptimizeOptions(
            imageDpi = o.optInt("dpi", d.imageDpi), jpegQuality = o.optInt("q", d.jpegQuality), recompressJpeg = o.optBoolean("recJpeg", d.recompressJpeg),
            grayImages = o.optBoolean("grayImages", d.grayImages), dedupImages = o.optBoolean("dedup", d.dedupImages), stripMetadata = o.optBoolean("meta", d.stripMetadata),
            stripJavaScript = o.optBoolean("js", d.stripJavaScript), stripThumbnails = o.optBoolean("thumbs", d.stripThumbnails), stripAttachments = o.optBoolean("attach", d.stripAttachments),
            stripPieceInfo = o.optBoolean("piece", d.stripPieceInfo), flateUncompressed = o.optBoolean("flate", d.flateUncompressed), onlyIfSmaller = o.optBoolean("smaller", d.onlyIfSmaller),
            colorMode = o.enum("colorMode", d.colorMode), separateClasses = o.optBoolean("separate", d.separateClasses), grayDpi = o.optInt("gDpi", d.grayDpi),
            grayQuality = o.optInt("gQ", d.grayQuality), grayMode = o.enum("grayMode", d.grayMode), monoDpi = o.optInt("mDpi", d.monoDpi), monoMode = o.enum("monoMode", d.monoMode),
            unembedFonts = o.enum("unembed", d.unembedFonts), removeAnnotations = o.optBoolean("rmAnnots"), removeForms = o.optBoolean("rmForms"), removeLinks = o.optBoolean("rmLinks"),
            removeBookmarks = o.optBoolean("rmBookmarks"), removeNamedDests = o.optBoolean("rmDests"), keepProtection = o.optBoolean("keepProt", true),
            pdfVersion = o.optString("version"), downsample = o.enum("downsample", d.downsample), aboveDpi = o.optInt("above"), subsetFonts = o.optBoolean("subset"),
            mergeFragments = o.optBoolean("merge"), mrc = o.optBoolean("mrc"), flattenTransparencyDpi = o.optInt("flatT"),
            scanBackground = o.optBoolean("scanBg"), scanDescreen = o.optBoolean("descreen"), scanSharpen = o.optBoolean("sharpen"), bwImages = o.optBoolean("bw"),
            cleanup = CleanupOptions(c.optBoolean("formActions", dc.formActions), c.optBoolean("alternates"), c.optBoolean("tags"), c.optBoolean("print"), c.optBoolean("search"),
                c.optBoolean("media"), c.optBoolean("external"), c.optBoolean("objData"), c.optBoolean("layers"), c.optBoolean("lzw"), c.optBoolean("invalid"),
                c.optBoolean("unusedDests"), c.optBoolean("content"), c.optBoolean("curves")),
        )
    }.getOrNull()

    /**
     * Foxit "Estimate compression size": optimizes a sample of up to [samplePages] evenly spaced pages and scales the
     * saving to the whole file. Returns the estimated size in bytes.
     */
    suspend fun estimate(src: File, password: String?, o: OptimizeOptions, tempDir: File, samplePages: Int = 6): Long = withContext(Dispatchers.IO) {
        val sample = File(tempDir, "opt_est_${System.nanoTime()}.pdf"); val out = File(tempDir, "opt_est_out_${System.nanoTime()}.pdf")
        try {
            var total: Int
            (if (password.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, password)).use { doc ->
                total = doc.numberOfPages
                if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                if (total > samplePages) {
                    val keep = (0 until samplePages).map { it * total / samplePages }.toSet()
                    for (i in total - 1 downTo 0) if (i !in keep) doc.removePage(i)
                }
                doc.save(sample)
            }
            Optimizer.optimize(sample, null, out, o.copy(onlyIfSmaller = false, keepProtection = false))
            val ratio = out.length().toDouble() / sample.length().coerceAtLeast(1)
            (src.length() * ratio.coerceIn(0.01, 1.5)).toLong()
        } finally { sample.delete(); out.delete() }
    }
}
