package com.smartdocs.editor.core.fillsign2

import com.smartdocs.editor.core.pdf.FillSignKeys
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import java.security.MessageDigest
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Reading saved image stamps back into the reader's editable model (nitro-fillsign2): a placed QuickSign signature
 * (/SDSig) or one of our image stamps (/Name /Image) whose appearance is a single image. The pure parts are here
 * (unit-tested); the bitmap itself is decoded in AnnotationWriter.toAnnot (Android only).
 */
object StampRead {
    /** /Stamp: which saved signature a placed stamp came from (a hash of the image's file name, never the path). */
    val SIG_REF: COSName = COSName.getPDFName("SDSigRef")

    /** Stable, non-identifying reference of the signature image at [path]. */
    fun refOf(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        return MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }

    /** The saved signature path among [paths] that [ref] points to, if any. */
    fun resolve(ref: String?, paths: Collection<String>): String? = ref?.let { r -> paths.firstOrNull { refOf(it) == r } }

    /** True for stamps this app can read back: a signature stamp or an image stamp of ours. */
    fun isOurImageStamp(an: PDAnnotation): Boolean =
        an.subtype == "Stamp" && (an.cosObject.getBoolean(FillSignKeys.SIG, false) || an.cosObject.getNameAsString(COSName.NAME) == "Image")

    /** The only image drawn by the stamp's normal appearance (null when there is none, or more than one). */
    fun imageOf(an: PDAnnotation): PDImageXObject? {
        if (!isOurImageStamp(an)) return null
        val res = an.normalAppearanceStream?.resources ?: return null
        val images = res.xObjectNames.mapNotNull { n -> runCatching { res.getXObject(n) as? PDImageXObject }.getOrNull() }
        return images.singleOrNull()
    }

    /**
     * Clockwise rotation in degrees (0, 90, 180, 270, or the nearest whole degree) of an appearance /Matrix
     * [a b c d]: the image is turned by it when it is read back, so the editable copy looks the same.
     */
    fun rotationOf(a: Float, b: Float): Int {
        val deg = Math.toDegrees(atan2(b.toDouble(), a.toDouble())).roundToInt()
        return ((-deg % 360) + 360) % 360
    }
}
