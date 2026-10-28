package com.smartdocs.editor.core.annot

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * NVE-img: advanced properties of an image annotation (an annotation whose normal appearance paints an image, e.g. an
 * image stamp): hidden / printable / locked flags, border, and the icon fit — scale when (always / bigger / smaller /
 * never), proportional, position inside the rectangle — written as /MK /IF and applied by rebuilding the appearance.
 * Pure PDFBox (unit tested on the JVM).
 */
object ImageAnnotProps5 {
    enum class ScaleWhen(val code: String) { ALWAYS("A"), BIGGER("B"), SMALLER("S"), NEVER("N") }

    data class Props(
        val hidden: Boolean = false, val printable: Boolean = true, val locked: Boolean = false,
        val border: Float = 0f, val borderColor: Int = 0x000000,
        val scaleWhen: ScaleWhen = ScaleWhen.ALWAYS, val proportional: Boolean = true, val posX: Float = 0.5f, val posY: Float = 0.5f,
    )

    private val MK = COSName.getPDFName("MK"); private val IF = COSName.getPDFName("IF")
    private val SW = COSName.getPDFName("SW"); private val S = COSName.S; private val A = COSName.getPDFName("A")

    /** The first image painted by [form] or a form it uses (depth-limited). */
    fun imageIn(form: PDFormXObject, depth: Int = 0): PDImageXObject? {
        if (depth > 4) return null
        val res = form.resources ?: return null
        for (n in res.xObjectNames) {
            when (val x = runCatching { res.getXObject(n) }.getOrNull()) {
                is PDImageXObject -> return x
                is PDFormXObject -> imageIn(x, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun annot(doc: PDDocument, page: Int, index: Int): PDAnnotation? =
        runCatching { doc.getPage(page).annotations.getOrNull(index) }.getOrNull()

    fun image(doc: PDDocument, page: Int, index: Int): PDImageXObject? =
        annot(doc, page, index)?.appearance?.normalAppearance?.appearanceStream?.let { imageIn(it) }

    fun read(doc: PDDocument, page: Int, index: Int): Props? {
        val a = annot(doc, page, index) ?: return null
        val d = a.cosObject
        val b = (d.getDictionaryObject(COSName.BORDER) as? COSArray)?.let { (it.getObject(2) as? COSNumber)?.floatValue() } ?: 0f
        val fit = (d.getDictionaryObject(MK) as? COSDictionary)?.getDictionaryObject(IF) as? COSDictionary
        val sw = (fit?.getDictionaryObject(SW) as? COSName)?.name
        val pos = fit?.getDictionaryObject(A) as? COSArray
        return Props(
            a.isHidden, a.isPrinted, a.isLocked, b, 0x000000,
            ScaleWhen.entries.firstOrNull { it.code == sw } ?: ScaleWhen.ALWAYS,
            (fit?.getDictionaryObject(S) as? COSName)?.name != "A",
            (pos?.getObject(0) as? COSNumber)?.floatValue() ?: 0.5f, (pos?.getObject(1) as? COSNumber)?.floatValue() ?: 0.5f,
        )
    }

    /** Size of the image inside a [w]×[h] box for [p] (natural size: pixels at 96 dpi). Returns x, y, width, height. */
    fun fit(imgW: Float, imgH: Float, w: Float, h: Float, p: Props): FloatArray {
        val nw = imgW * 0.75f; val nh = imgH * 0.75f
        val fitsX = w / nw; val fitsY = h / nh
        val bigger = nw > w || nh > h
        val scale = when (p.scaleWhen) {
            ScaleWhen.ALWAYS -> true
            ScaleWhen.BIGGER -> bigger
            ScaleWhen.SMALLER -> !bigger
            ScaleWhen.NEVER -> false
        }
        var dw = nw; var dh = nh
        if (scale) {
            if (p.proportional) { val s = minOf(fitsX, fitsY); dw = nw * s; dh = nh * s } else { dw = w; dh = h }
        }
        return floatArrayOf((w - dw) * p.posX.coerceIn(0f, 1f), (h - dh) * p.posY.coerceIn(0f, 1f), dw, dh)
    }

    /** Write [p] into annotation [index] of [page]. False when it paints no image. */
    fun apply(doc: PDDocument, page: Int, index: Int, p: Props): Boolean {
        val a = annot(doc, page, index) ?: return false
        val img = image(doc, page, index) ?: return false
        a.isHidden = p.hidden; a.isPrinted = p.printable; a.isLocked = p.locked
        val d = a.cosObject
        d.setItem(COSName.BORDER, COSArray().apply { add(COSInteger.ZERO); add(COSInteger.ZERO); add(COSFloat(p.border)) })
        val mk = d.getDictionaryObject(MK) as? COSDictionary ?: COSDictionary().also { d.setItem(MK, it) }
        mk.setItem(IF, COSDictionary().apply {
            setItem(SW, COSName.getPDFName(p.scaleWhen.code))
            setItem(S, COSName.getPDFName(if (p.proportional) "P" else "A"))
            setItem(A, COSArray().apply { add(COSFloat(p.posX)); add(COSFloat(p.posY)) })
        })
        if (p.border > 0f) mk.setItem(COSName.getPDFName("BC"), COSArray().apply {
            add(COSFloat(((p.borderColor shr 16) and 0xFF) / 255f)); add(COSFloat(((p.borderColor shr 8) and 0xFF) / 255f)); add(COSFloat((p.borderColor and 0xFF) / 255f))
        }) else mk.removeItem(COSName.getPDFName("BC"))
        // rebuild the normal appearance: image fitted per /IF, clipped to the box, then the border
        val r = a.rectangle ?: return false
        val w = r.width; val h = r.height
        val form = PDFormXObject(doc)
        form.bBox = PDRectangle(0f, 0f, w, h)
        form.resources = PDResources()
        PDPageContentStream(doc, PDAppearanceStream(form.cosObject)).use { cs ->
            val f = fit(img.width.toFloat(), img.height.toFloat(), w, h, p)
            cs.saveGraphicsState()
            cs.addRect(0f, 0f, w, h); cs.clip()
            cs.drawImage(img, f[0], f[1], f[2], f[3])
            cs.restoreGraphicsState()
            if (p.border > 0f) {
                cs.setStrokingColor((p.borderColor shr 16) and 0xFF, (p.borderColor shr 8) and 0xFF, p.borderColor and 0xFF)
                cs.setLineWidth(p.border)
                cs.addRect(p.border / 2f, p.border / 2f, w - p.border, h - p.border); cs.stroke()
            }
        }
        val ap = a.appearance ?: PDAppearanceDictionary().also { a.appearance = it }
        ap.setNormalAppearance(PDAppearanceStream(form.cosObject))
        return true
    }

    suspend fun readFile(src: File, pw: String?, page: Int, index: Int): Props? = withContext(Dispatchers.IO) {
        com.smartdocs.editor.core.pdf.PdfOps.load(src, pw).use { if (image(it, page, index) == null) null else read(it, page, index) }
    }

    /** Signed-safe write (audit CM-04). */
    suspend fun applyFile(src: File, out: File, pw: String?, page: Int, index: Int, p: Props) = withContext(Dispatchers.IO) {
        com.smartdocs.editor.core.pdf.SignedSave.editFile(src, out, pw) { d ->
            if (!apply(d, page, index, p)) error("no image in this annotation")
            listOf(page)
        }
    }
}
