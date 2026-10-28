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
        /** A change of page content (flatten …) on a signed file: an incremental update would still invalidate it. */
        CONTENT_CHANGE,
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

    private val POPUP = COSName.getPDFName("Popup")

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
        // a FieldMDP lock with /P (PDF 2.0, "lock document after signing") restricts changes like a certification
        val locked = runCatching { doc.signatureDictionaries.mapNotNull { com.smartdocs.editor.core.sign.SigFields.lockOf(doc, it)?.docPermission }.minOrNull() }.getOrNull()
        val p = listOfNotNull(certifyLevel(doc), locked).minOrNull() ?: return
        if (p < 3) throw SignedDocumentException(SignedDocumentException.Reason.CERTIFIED_NO_CHANGES,
            "this file is certified (DocMDP P=$p) and does not allow annotation changes; save as a copy")
    }

    /** Throws [SignedDocumentException] (CONTENT_CHANGE) for a signed [doc]: page content must not change after signing. */
    fun requireContentChangeAllowed(doc: PDDocument) {
        if (isSigned(doc)) throw SignedDocumentException(SignedDocumentException.Reason.CONTENT_CHANGE,
            "this file is signed: flattening changes the page content and would invalidate the signature; save a copy")
    }

    /**
     * Tabs audit CM-01 / CM-02 / CM-04: the one write path of the comment tools. Loads [src] without removing its
     * security, applies [change] (it returns the zero-based pages it touched; empty = every page) and saves with
     * [saveEdited] — incremental and refused on certified files when signed, re-protected otherwise.
     */
    fun editFile(src: java.io.File, out: java.io.File, password: String?, change: (PDDocument) -> Collection<Int>) {
        PdfOps.loadRaw(src, password).use { doc ->
            val pages = change(doc)
            saveEdited(doc, out, password, pages.ifEmpty { (0 until doc.numberOfPages).toList() })
        }
    }

    /** Flag [pages] (their /Annots array and their /Parent chain) and the catalog for the incremental update. */
    fun markPages(doc: PDDocument, pages: Collection<PDPage>) {
        doc.documentCatalog.cosObject.isNeedToBeUpdated = true
        val seen = HashSet<COSDictionary>()
        for (page in pages) {
            var node: COSDictionary? = page.cosObject
            (node?.getDictionaryObject(COSName.ANNOTS) as? COSArray)?.let { annots ->
                annots.isNeedToBeUpdated = true
                // edited existing annotations (moved, regrouped, unlinked) and their popups must be rewritten too
                for (i in 0 until annots.size()) {
                    val an = annots.getObject(i) as? COSDictionary ?: continue
                    an.isNeedToBeUpdated = true
                    (an.getDictionaryObject(POPUP) as? COSDictionary)?.isNeedToBeUpdated = true
                }
            }
            while (node != null && seen.add(node)) {
                node.isNeedToBeUpdated = true
                (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.isNeedToBeUpdated = true
                node = node.getDictionaryObject(COSName.PARENT) as? COSDictionary
            }
        }
    }

    /**
     * Save [doc] (loaded with [PdfOps.loadRaw]) after an annotation edit of the zero-based [pages] to [out]: for a
     * signed file an incremental update ([saveIncremental]; certified files that forbid it throw), otherwise a full
     * save re-protected like the source ([PdfOps.reprotect]). Used by the comment, review and stored-annotation
     * writers so they keep signatures valid like [AnnotationWriter] (audit AN-28 / DI-27).
     */
    fun saveEdited(doc: PDDocument, out: File, password: String?, pages: Iterable<Int>) {
        if (isSigned(doc)) {
            requireAnnotationsAllowed(doc)
            saveIncremental(doc, pages.distinct().filter { it in 0 until doc.numberOfPages }.map { doc.getPage(it) }, out)
        } else {
            PdfOps.reprotect(doc, password)
            doc.save(out)
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
