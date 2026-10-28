package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What to do with the protection of a source file in a derived output. */
enum class OutProtection {
    /** Output protected like the source (same open password, same permissions) — what `PdfOps.load` does. */
    KEEP,
    /** Output written without encryption. */
    REMOVE,
}

/** Result of probing a file with a (possibly null) password. */
enum class LockState { OPEN, UNLOCKED, NEEDS_PASSWORD, WRONG_PASSWORD }

/**
 * Passwords typed for the files of one operation (Bates over several files, impose, the second file of
 * replace/copy/interleave). Keyed by absolute path, so every locked file is asked for once. Pure.
 */
class PasswordBook {
    private val known = LinkedHashMap<String, String?>()

    private fun key(f: File) = f.absolutePath

    /** True when [f] was already probed (it is open, or its password is known). */
    fun has(f: File) = known.containsKey(key(f))

    /** Known password of [f] (null for a file that is not encrypted or was never asked for). */
    fun get(f: File): String? = known[key(f)]

    /** Remember that [f] opens with [password] (null = no password needed). */
    fun put(f: File, password: String?) { known[key(f)] = password?.ifEmpty { null } }

    fun forget(f: File) { known.remove(key(f)) }

    /** Keep only the entries of [files] (files removed from a list forget their password). */
    fun retain(files: List<File>) { val keep = files.map { key(it) }.toSet(); known.keys.retainAll(keep) }

    /** Files of [files] not probed yet, in order, without duplicates. */
    fun pending(files: List<File>): List<File> = files.distinctBy { key(it) }.filter { !has(it) }

    /** Lookup function for the core operations. */
    fun lookup(): (File) -> String? = { f -> get(f) }

    fun snapshot(): Map<String, String?> = LinkedHashMap(known)
}

object E5Locks {
    /** True when [e] (or a cause) means "this file needs a (different) password". */
    fun isPasswordError(e: Throwable?): Boolean {
        var t = e
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is InvalidPasswordException) return true
            if (t.javaClass.simpleName == "PasswordRequiredException") return true
            val m = (t.message ?: "").lowercase()
            if ("password" in m) return true
            t = t.cause
        }
        return false
    }

    /** Try to open [file] with [password]. Never throws for password problems. */
    suspend fun probe(file: File, password: String?): LockState = withContext(Dispatchers.IO) { probeNow(file, password) }

    fun probeNow(file: File, password: String?): LockState = try {
        val doc = if (password.isNullOrEmpty()) PDDocument.load(file) else PDDocument.load(file, password)
        doc.use { if (it.isEncrypted) LockState.UNLOCKED else LockState.OPEN }
    } catch (e: Exception) {
        if (!isPasswordError(e)) throw e
        if (password.isNullOrEmpty()) LockState.NEEDS_PASSWORD else LockState.WRONG_PASSWORD
    }

    /**
     * The protection an output gets, given the source state. Pure decision: null = write unencrypted; otherwise the
     * (user, owner) passwords. A file opened with its user password (or an owner-only file opened without one) keeps
     * its permissions under a fresh owner password [ownerFallback]; a file opened with its owner password gets the
     * typed password as both (its user password is unknown) — like `PdfOps.load`.
     */
    fun plan(sourceEncrypted: Boolean, password: String?, protection: OutProtection, openedAsOwner: Boolean = false,
             ownerFallback: String = ""): Pair<String, String>? {
        if (!sourceEncrypted || protection == OutProtection.REMOVE) return null
        val pw = password.orEmpty()
        return if (openedAsOwner || ownerFallback.isEmpty()) pw to pw else pw to ownerFallback
    }

    /**
     * audit 5 E-3: the KEEP protection is the one shared helper [com.smartdocs.editor.core.pdf.PdfOps.keepProtection]
     * (same cipher, key length and permission bits as the source, never a downgrade to AES-128).
     */
    private fun policyLike(source: PDDocument, password: String?, protection: OutProtection): StandardProtectionPolicy? {
        if (!source.isEncrypted || protection == OutProtection.REMOVE) return null
        return com.smartdocs.editor.core.pdf.PdfOps.keepProtection(source, password)
    }

    /** audit 5 E-3: "remove protection" is offered only with owner access to the source (or an unencrypted one). Pure. */
    fun canRemove(sourceEncrypted: Boolean, ownerAccess: Boolean): Boolean = !sourceEncrypted || ownerAccess

    /**
     * Apply [protection] to [doc] loaded from a source by `PdfOps.load` (which re-protects an encrypted file with
     * the typed password as user and owner): KEEP re-protects with the source permissions ([plan]), REMOVE drops
     * the encryption from the output.
     */
    fun applyTo(doc: PDDocument, protection: OutProtection, password: String? = null) {
        if (!doc.isEncrypted) return
        // audit 5 E-3: REMOVE needs owner access; without it the output stays protected like the source
        val owner = runCatching { doc.currentAccessPermission.isOwnerPermission }.getOrDefault(false)
        if (protection == OutProtection.REMOVE && owner) { doc.isAllSecurityToBeRemoved = true; return }
        policyLike(doc, password, OutProtection.KEEP)?.let { runCatching { doc.protect(it) } }
    }

    /**
     * Protect a newly built [target] like [source] (same open password and permissions, AES 128) when [protection]
     * is KEEP and the source is encrypted. Used by outputs that are new documents (N-up, booklet, poster).
     */
    fun protectLike(target: PDDocument, source: PDDocument, password: String?, protection: OutProtection) {
        val owner = runCatching { source.currentAccessPermission.isOwnerPermission }.getOrDefault(false)
        policyLike(source, password, if (protection == OutProtection.REMOVE && !owner) OutProtection.KEEP else protection)?.let { target.protect(it) }
    }
}
