package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import java.io.File

/**
 * A save that would invalidate a digital signature, or that cannot be done without doing so (audit AN-28 / DI-27).
 * The original is left untouched; the UI should say "this file is signed — save as a copy" (strings_fix56.xml:
 * `signed_doc_save_as_copy`, `signed_doc_certified_no_changes`) and offer an unsigned copy.
 */
class SignedDocumentException(val reason: Reason, message: String, cause: Throwable? = null) : java.io.IOException(message, cause) {
    enum class Reason {
        /** Certified (DocMDP P=1 or 2): the author allowed no annotation changes. */
        CERTIFIED_NO_CHANGES,
        /** The incremental update could not be written. */
        INCREMENTAL_FAILED,
    }
}

/**
 * Saving into a signed PDF without breaking its signatures (audit AN-28 / DI-27): changes are appended as an
 * incremental update, so the signed byte range of every signature stays byte-identical. PdfBox 2.0 writes only
 * the objects flagged with `setNeedToBeUpdated` (and new ones reachable from them), so every changed page is
 * flagged together with its path up to the catalog. An encrypted signed document is written with its original
 * security handler (incremental updates keep the encryption dictionary as it is), so it must be loaded with
 * [PdfOps.loadRaw], not re-protected.
 */
object SignedSave {

    /** True when [doc] has at least one signature field with a value (/FT /Sig with /V). */
    fun isSigned(doc: PDDocument): Boolean = runCatching { doc.signatureDictionaries.isNotEmpty() }.getOrDefault(false)

    /** For the reader: check a file on open (show "signed — annotations are appended" or offer a copy). */
    fun isSigned(file: File, password: String? = null): Boolean = runCatching { PdfOps.loadRaw(file, password).use { isSigned(it) } }.getOrDefault(false)

    /** DocMDP level of the certification signature (catalog /Perms /DocMDP), or null when not certified. */
    fun certifyLevel(doc: PDDocument): Int? = runCatching {
        val perms = doc.documentCatalog.cosObject.getDictionaryObject(COSName.PERMS) as? COSDictionary ?: return null
        val sig = perms.getDictionaryObject(COSName.DOCMDP) as? COSDictionary ?: return null
        val refs = sig.getDictionaryObject(COSName.REFERENCE) as? COSArray ?: return 2
        for (i in 0 until refs.size()) {
            val ref = refs.getObject(i) as? COSDictionary ?: continue
            if (ref.getCOSName(COSName.TRANSFORM_METHOD) == COSName.DOCMDP) {
                val tp = ref.getDictionaryObject(COSName.TRANSFORM_PARAMS) as? COSDictionary
                return tp?.getInt(COSName.P, 2) ?: 2
            }
        }
        2
    }.getOrNull()

    /** Throws [SignedDocumentException] when the certification of [doc] forbids adding or removing annotations. */
    fun requireAnnotationsAllowed(doc: PDDocument) {
        val p = certifyLevel(doc) ?: return
        if (p < 3) throw SignedDocumentException(SignedDocumentException.Reason.CERTIFIED_NO_CHANGES,
            "this file is certified (DocMDP P=$p) and does not allow annotation changes; save as a copy")
    }

    /** Flag [pages] (their /Annots array and their /Parent chain) and the catalog for the incremental update. */
    fun markPages(doc: PDDocument, pages: Collection<PDPage>) {
        doc.documentCatalog.cosObject.isNeedToBeUpdated = true
        val seen = HashSet<COSDictionary>()
        for (page in pages) {
            var node: COSDictionary? = page.cosObject
            (node?.getDictionaryObject(COSName.ANNOTS) as? COSArray)?.isNeedToBeUpdated = true
            while (node != null && seen.add(node)) {
                node.isNeedToBeUpdated = true
                (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.isNeedToBeUpdated = true
                node = node.getDictionaryObject(COSName.PARENT) as? COSDictionary
            }
        }
    }

    /**
     * Append the changes of [pages] to the original bytes of [doc] and write the result to [out] (never the
     * source). The original file is a byte-identical prefix of [out].
     */
    fun saveIncremental(doc: PDDocument, pages: Collection<PDPage>, out: File) {
        try {
            markPages(doc, pages)
            subsetFonts(doc)
            out.outputStream().use { doc.saveIncremental(it) }
        } catch (e: SignedDocumentException) {
            throw e
        } catch (e: Exception) {
            runCatching { out.delete() }
            throw SignedDocumentException(SignedDocumentException.Reason.INCREMENTAL_FAILED,
                "this file is signed and the change could not be appended without breaking the signature; save as a copy", e)
        }
    }

    /**
     * `PDDocument.save` subsets the fonts loaded with embedSubset (the FreeText font) but `saveIncremental` does
     * not, which would leave an empty font program. The set is package-private, so it is reached by reflection
     * (proguard keeps com.tom_roush.**).
     */
    private fun subsetFonts(doc: PDDocument) {
        val m = PDDocument::class.java.getDeclaredMethod("getFontsToSubset").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val fonts = m.invoke(doc) as MutableSet<PDFont>
        fonts.forEach { it.subset() }
        fonts.clear()
    }
}
