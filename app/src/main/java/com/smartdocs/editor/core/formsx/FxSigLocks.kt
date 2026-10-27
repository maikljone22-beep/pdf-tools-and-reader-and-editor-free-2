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
 * This is only the authoring side. Writing the matching FieldMDP transform into the signature itself (and setting
 * the locked fields read-only at signing time) belongs to the signing code (feature/nitro-protect-sign); that code
 * reads the same dictionary through [read] / [lockedFields].
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
        return FxSigLock(mode, fields, js.orEmpty())
    }

    /** Fully qualified names locked when the signature field [field] is signed (all others of [allFields] for Exclude). */
    fun lockedFields(field: COSDictionary, allFields: List<String>, self: String): List<String> {
        val lock = field.getDictionaryObject(LOCK) as? COSDictionary ?: return emptyList()
        val l = read(lock)
        return when (l.mode) {
            FxSigLock.Mode.ALL -> allFields.filter { it != self }
            FxSigLock.Mode.INCLUDE -> l.fields
            FxSigLock.Mode.EXCLUDE -> allFields.filter { it != self && it !in l.fields }
            FxSigLock.Mode.NONE -> emptyList()
        }
    }
}
