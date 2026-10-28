package com.smartdocs.editor.core.ocr

import com.smartdocs.editor.core.pdf.OcrLayerCleanup
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.ToUnicodeRepair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The document an OCR run works on: [src] with its broken Arabic text layer repaired first ([ToUnicodeRepair]) and the
 * app's own earlier invisible OCR layer removed from the pages about to be recognised again ([OcrLayerCleanup]; older
 * builds wrote it reversed, and a second run would stack a duplicate). Planning ([MixedPageOcr.plan]) then judges the
 * repaired text with PDFium, so a page whose text only needed the repair is not OCRed (no duplicate OCR layer on copy),
 * and [MixedPageOcr.dropExisting] keeps only words that are not already selectable.
 * [file] is [src] when nothing changed. Close it to delete the temporary copy.
 */
class RepairedInput private constructor(val file: File, val result: ToUnicodeRepair.Result?, val oldOcrRemoved: Int, private val temp: File?) : AutoCloseable {

    val repaired: Boolean get() = temp != null && (result?.fonts ?: 0) > 0

    override fun close() { temp?.delete() }

    companion object {
        /** [rerun]: zero-based pages that will be recognised again (null = all) — their old OCR layer is removed. */
        suspend fun of(cacheDir: File, src: File, password: String?, rerun: Collection<Int>? = null): RepairedInput = withContext(Dispatchers.IO) {
            val tmp = File(cacheDir, "textfix_${System.nanoTime()}.pdf")
            try {
                PdfOps.load(src, password).use { doc ->
                    val r = ToUnicodeRepair.repair(doc, removeOldOcr = true)
                    var cleared = r.ocrLayersRemoved
                    for (pi in rerun ?: (0 until doc.numberOfPages).toList()) {
                        if (pi !in 0 until doc.numberOfPages) continue
                        val page = doc.getPage(pi)
                        // audit 5 OCR-3: a page with stored OCR words carries our layer, also a Latin-only one (Helvetica);
                        // the stored words go with the layer, so the new run does not append to them
                        val n = runCatching { OcrLayerCleanup.removeOurs(page, latinOnly = OcrStore.has(page)) }.getOrDefault(0)
                        if (n > 0) { runCatching { OcrStore.remove(page) }; cleared += n }
                    }
                    if (r.fonts > 0 || cleared > 0) { doc.save(tmp); RepairedInput(tmp, r, cleared, tmp) }
                    else { tmp.delete(); RepairedInput(src, r, 0, null) }
                }
            } catch (e: CancellationException) {
                tmp.delete(); throw e
            } catch (e: Exception) {
                tmp.delete(); RepairedInput(src, null, 0, null)    // the repair is a bonus: OCR goes on with the original file
            }
        }
    }
}
