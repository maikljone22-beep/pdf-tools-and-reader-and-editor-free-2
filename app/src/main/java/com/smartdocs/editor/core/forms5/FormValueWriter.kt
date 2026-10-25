package com.smartdocs.editor.core.forms5

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.smartdocs.editor.core.pdf.FormOps
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes the in-page edits into a PDF: field values through [FormOps.fillFields] (Arabic font + shaped
 * appearances, formatted appearances with raw /V) and picked pictures into image buttons
 * (new /AP /N plus the /MK /I icon, as Acrobat's buttonImportIcon does).
 */
object FormValueWriter {

    suspend fun write(
        context: Context, src: File, values: Map<String, String>, display: Map<String, String>, images: Map<String, File>,
        out: File, password: String?, dropXfa: Boolean,
    ) = withContext(Dispatchers.IO) {
        val mid = if (images.isEmpty()) out else File(out.parentFile, ".${out.name}.v.pdf")
        if (values.isNotEmpty() || display.isNotEmpty() || dropXfa) FormOps.fillFields(src, values, mid, password, context, display, dropXfa)
        else src.copyTo(mid, overwrite = true)
        if (images.isEmpty()) return@withContext
        try {
            PdfOps.load(mid, password).use { doc ->
                images.forEach { (name, file) -> runCatching { setImage(doc, name, file) } }
                doc.save(out)
            }
        } finally { mid.delete() }
    }

    private fun decode(file: File, maxSide: Int = 1600): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var s = 1; while (o.outWidth / s > maxSide || o.outHeight / s > maxSide) s *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun setImage(doc: PDDocument, name: String, file: File) {
        val f = doc.documentCatalog.acroForm?.getField(name) as? PDPushButton ?: return
        val bmp = decode(file) ?: return
        val img = if (bmp.hasAlpha()) LosslessFactory.createFromImage(doc, bmp) else JPEGFactory.createFromImage(doc, bmp, 0.88f)
        for (w in f.widgets) {
            val r = w.rectangle ?: continue
            val ap = PDAppearanceStream(doc)
            ap.bBox = PDRectangle(0f, 0f, r.width, r.height)
            ap.resources = PDResources()
            PDPageContentStream(doc, ap).use { cs ->
                // fit inside the box keeping the aspect ratio, centred
                val k = minOf(r.width / img.width, r.height / img.height)
                val w2 = img.width * k; val h2 = img.height * k
                cs.drawImage(img, (r.width - w2) / 2f, (r.height - h2) / 2f, w2, h2)
            }
            w.appearance = PDAppearanceDictionary().apply { setNormalAppearance(ap) }
            val mk = (w.cosObject.getDictionaryObject(COSName.getPDFName("MK")) as? COSDictionary) ?: COSDictionary().also { w.cosObject.setItem(COSName.getPDFName("MK"), it) }
            mk.setItem(COSName.getPDFName("I"), ap.cosObject)
            // TP 1 = icon only
            mk.setInt(COSName.getPDFName("TP"), 1)
        }
    }
}
