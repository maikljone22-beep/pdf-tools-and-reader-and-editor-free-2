package com.smartdocs.editor.core.formsx

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString

/**
 * Signature field "Signed" tab (Nitro 8.72 / Foxit 26.67): the /Lock dictionary (SigFieldLock, PDF 32000 table
 * 233) that tells a signer which fields become read-only when this field is signed.
 *
 * This is only the authoring side. The signing code (core/sign/SigFields: prepare / readLock / applyLock) reads this
 * very dictionary when the field is signed, writes the FieldMDP reference and sets the locked fields read-only, so the
 * format is kept exactly as SigFields expects (/Type /SigFieldLock, /Action All|Include|Exclude, /Fields, optional /P)
 * and [lockedFields] delegates to SigFields.Lock.locks.
 *
 * The optional "run this script when signed" text has no standard place in PDF; it is kept as a JavaScript action
 * under the private key /SDOnSign of the field so the signing code can run it.
 */
object FxSigLocks {

    private fun n(s: String) = COSName.getPDFName(s)
    private val LOCK = n("Lock")
    private val ON_SIGN = n("SDOnSign")
    private val ACTION = n("Action")
    private val FIELDS = n("Fields")

    fun dict(lock: FxSigLock): COSDictionary? {
        if (lock.mode == FxSigLock.Mode.NONE) return null
        return COSDictionary().apply {
            setItem(COSName.TYPE, n("SigFieldLock"))
            setItem(ACTION, n(when (lock.mode) { FxSigLock.Mode.INCLUDE -> "Include"; FxSigLock.Mode.EXCLUDE -> "Exclude"; else -> "All" }))
            if (lock.mode != FxSigLock.Mode.ALL) setItem(FIELDS, COSArray().apply { lock.fields.forEach { add(COSString(it)) } })
            lock.docPermission?.let { setInt(COSName.P, it.coerceIn(1, 3)) }
        }
    }

    /** Write (or clear, for null / NONE without script) the lock and on-sign script of a signature field. */
    fun write(field: COSDictionary, lock: FxSigLock?) {
        val d = lock?.let { dict(it) }
        if (d != null) field.setItem(LOCK, d) else field.removeItem(LOCK)
        val js = lock?.script.orEmpty()
        if (js.isNotBlank()) field.setItem(ON_SIGN, COSDictionary().apply { setItem(COSName.TYPE, n("Action")); setItem(COSName.S, n("JavaScript")); setItem(COSName.JS, COSString(js)) })
        else field.removeItem(ON_SIGN)
    }

    fun read(lock: COSDictionary, field: COSDictionary? = null): FxSigLock {
        val mode = when (lock.getCOSName(ACTION)?.name) { "Include" -> FxSigLock.Mode.INCLUDE; "Exclude" -> FxSigLock.Mode.EXCLUDE; else -> FxSigLock.Mode.ALL }
        val fields = (lock.getDictionaryObject(FIELDS) as? COSArray)?.let { a -> (0 until a.size()).mapNotNull { (a.getObject(it) as? COSString)?.string } } ?: emptyList()
        val js = (field?.getDictionaryObject(ON_SIGN) as? COSDictionary)?.getDictionaryObject(COSName.JS).let { j -> when (j) { is COSString -> j.string; is COSStream -> runCatching { j.toTextString() }.getOrNull(); else -> null } }
        return FxSigLock(mode, fields, js.orEmpty(), lock.getInt(COSName.P, -1).takeIf { it in 1..3 })
    }

    /** Fully qualified names locked when the signature field [field] is signed: the signing code's own rule (SigFields.Lock.locks). */
    fun lockedFields(field: COSDictionary, allFields: List<String>, self: String): List<String> {
        val lock = com.smartdocs.editor.core.sign.SigFields.readLock(field.getDictionaryObject(LOCK) as? COSDictionary) ?: return emptyList()
        return allFields.filter { it != self && lock.locks(it) }
    }
}
