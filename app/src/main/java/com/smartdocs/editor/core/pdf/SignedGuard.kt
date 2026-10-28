package com.smartdocs.editor.core.pdf

import java.io.File

/**
 * tab-view-core V-06 / E-01 / D2 / T-01 / G-01: ONE gate for every write into a digitally signed or certified PDF.
 *
 * Detection reuses [SignedSave] ([SignedSave.isSigned], [SignedSave.certifyLevel], the FieldMDP lock of
 * [SignedSave.requireAnnotationsAllowed]); this object only decides what a change may do:
 *  - [Decision.PROCEED]          unsigned, uncertified: write as usual
 *  - [Decision.SAVE_INCREMENTAL] signed, the change is allowed when appended ([SignedSave.saveIncremental])
 *  - [Decision.OFFER_COPY]       signed, the change would break a signature: keep the original, offer "Save a copy"
 *  - [Decision.REFUSE]           certified (DocMDP / FieldMDP lock) forbids this change: refuse, offer a copy
 *
 * Callers: `ReaderState.applyInPlace` / `ReaderState.guardSigned`, `EditIo.save` / `E5Io.save` ([checkFullSave]),
 * `DocumentRepository.replaceOriginal`, `OrganizeState.saveOverwrite`, the form designer save.
 */
object SignedGuard {

    enum class Decision { PROCEED, SAVE_INCREMENTAL, OFFER_COPY, REFUSE }

    /** What the change does to the document (the DocMDP levels: 2 = form fill + sign, 3 = + annotations). */
    enum class Change { FORM_FILL, ANNOTATE, MODIFY }

    /** [signed]: at least one signature has a value. [lockP]: lowest DocMDP / FieldMDP permission, null = none. */
    data class Info(val signed: Boolean, val lockP: Int?) {
        val protectedBySignature: Boolean get() = signed || lockP != null
        companion object { val NONE = Info(false, null) }
    }

    /**
     * Pure decision (unit-tested). [incremental]: the change is (or can be) written as an incremental update that
     * keeps every signed byte range; null = not known yet (decide before writing).
     */
    fun decide(info: Info, change: Change, incremental: Boolean?): Decision {
        if (!info.protectedBySignature) return Decision.PROCEED
        val p = info.lockP
        if (p != null) {
            val allowed = when (change) { Change.FORM_FILL -> p >= 2; Change.ANNOTATE -> p >= 3; Change.MODIFY -> false }
            if (!allowed) return Decision.REFUSE
        }
        if (!info.signed) return Decision.PROCEED
        // page content, page assembly, security: never valid after signing, even appended
        if (change == Change.MODIFY) return Decision.OFFER_COPY
        return if (incremental == false) Decision.OFFER_COPY else Decision.SAVE_INCREMENTAL
    }

    /** The reason the reader's "signed — save a copy" dialog shows for a blocking [d]; null when not blocking. */
    fun reasonOf(d: Decision): SignedDocumentException.Reason? = when (d) {
        Decision.REFUSE -> SignedDocumentException.Reason.CERTIFIED_NO_CHANGES
        Decision.OFFER_COPY -> SignedDocumentException.Reason.INCREMENTAL_FAILED
        else -> null
    }

    /** Signature / certification state of [doc]. */
    fun inspect(doc: com.tom_roush.pdfbox.pdmodel.PDDocument): Info {
        val signed = SignedSave.isSigned(doc)
        val lock = runCatching { doc.signatureDictionaries.mapNotNull { com.smartdocs.editor.core.sign.SigFields.lockOf(doc, it)?.docPermission }.minOrNull() }.getOrNull()
        val p = listOfNotNull(SignedSave.certifyLevel(doc), lock).minOrNull()
        return Info(signed, p)
    }

    /** Signature / certification state of [file]; [Info.NONE] when it cannot be read. */
    fun inspect(file: File, password: String?): Info =
        runCatching { PdfOps.loadRaw(file, password).use { inspect(it) } }.getOrDefault(Info.NONE)

    /** True when [out] starts with the exact bytes of [src] and is longer: an incremental update of it. */
    fun isIncrementalOf(src: File, out: File): Boolean {
        val n = src.length()
        if (n <= 0L || out.length() <= n) return false
        src.inputStream().buffered(1 shl 16).use { a ->
            out.inputStream().buffered(1 shl 16).use { b ->
                val ba = ByteArray(1 shl 16); val bb = ByteArray(1 shl 16)
                var left = n
                while (left > 0) {
                    val want = minOf(left, ba.size.toLong()).toInt()
                    val ra = readFully(a, ba, want); val rb = readFully(b, bb, want)
                    if (ra != want || rb != want) return false
                    for (i in 0 until want) if (ba[i] != bb[i]) return false
                    left -= want
                }
            }
        }
        return true
    }

    private fun readFully(s: java.io.InputStream, buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) { val r = s.read(buf, off, len - off); if (r < 0) break; off += r }
        return off
    }

    /** Decision for replacing [src] by the already written [out]. */
    fun decideWrite(src: File, out: File, password: String?, change: Change, info: Info = inspect(src, password)): Decision =
        if (!info.protectedBySignature) Decision.PROCEED else decide(info, change, isIncrementalOf(src, out))

    /**
     * Throws [SignedDocumentException] when [out] may not replace [src] (signed and not an incremental update, or
     * certified against [change]). For writers outside the reader (replace original, organize, form designer).
     */
    fun checkWrite(src: File, out: File, password: String?, change: Change = Change.MODIFY) {
        val d = decideWrite(src, out, password, change)
        reasonOf(d)?.let { r -> throw SignedDocumentException(r, if (r == SignedDocumentException.Reason.CERTIFIED_NO_CHANGES)
            "this file is certified and does not allow this change; save as a copy" else "this file is signed and the change would break the signature; save as a copy") }
    }

    /**
     * For in-memory writers (`EditIo.save`, `E5Io.save`): [doc] is about to be saved in full over [out]. A full save
     * over an existing signed / certified file breaks its signatures, so it is refused (the original stays as it
     * is). New files (temp results, copies) are always allowed: they are checked again by [checkWrite] /
     * `ReaderState.applyInPlace` before they can replace a signed original.
     */
    fun checkFullSave(doc: com.tom_roush.pdfbox.pdmodel.PDDocument, out: File) {
        if (!out.exists() || out.length() == 0L) return
        val d = decide(inspect(doc), Change.MODIFY, false)
        reasonOf(d)?.let { r -> throw SignedDocumentException(r, "this file is signed or certified; save as a copy") }
    }
}
