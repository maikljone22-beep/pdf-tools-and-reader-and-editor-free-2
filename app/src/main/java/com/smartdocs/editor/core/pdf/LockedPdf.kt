package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Password-protected inputs for merge / insert-pages (MODULE_fid): detection, password check, and decryption into
 * temporary copies that the existing [PdfOps] operations can read. The result of a merge is unencrypted.
 */
object LockedPdf {

    /** True when [f] cannot be opened without a user password. Permission-only files (owner password) open fine. */
    fun needsPassword(f: File): Boolean = try { PDDocument.load(f).use { false } } catch (e: InvalidPasswordException) { true } catch (e: Exception) { false }

    /** True when the file carries any encryption (also permission-only files, which PdfBox merges refuse to edit). */
    fun isEncrypted(f: File, password: String? = null): Boolean =
        runCatching { (if (password.isNullOrEmpty()) PDDocument.load(f) else PDDocument.load(f, password)).use { it.isEncrypted } }.getOrDefault(false)

    /** True when [pw] opens [f]. */
    fun checkPassword(f: File, pw: String): Boolean = try { PDDocument.load(f, pw).use { true } } catch (e: InvalidPasswordException) { false } catch (e: Exception) { false }

    /** Page count of a (possibly locked) file, or null when the password is wrong / the file is unreadable. */
    fun pageCount(f: File, pw: String? = null): Int? =
        runCatching { (if (pw.isNullOrEmpty()) PDDocument.load(f) else PDDocument.load(f, pw)).use { it.numberOfPages } }.getOrNull()

    /** Writes an unencrypted copy of [f] into [dir] and returns it. Throws [InvalidPasswordException] on a wrong password. */
    fun decryptToTemp(f: File, pw: String?, dir: File): File {
        dir.mkdirs()
        val out = File(dir, ".unlocked_${System.nanoTime()}_${f.name}")
        (if (pw.isNullOrEmpty()) PDDocument.load(f) else PDDocument.load(f, pw)).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
        return out
    }

    /**
     * Merges [sources] in order into [out]. Encrypted inputs (including permission-only ones) are first decrypted into
     * temporary copies with [passwords] (file → password); the temporaries are deleted afterwards.
     */
    suspend fun mergeLocked(sources: List<File>, passwords: Map<File, String>, out: File, tempDir: File) = withContext(Dispatchers.IO) {
        val temps = ArrayList<File>()
        try {
            val inputs = sources.map { f ->
                val pw = passwords[f]
                if (pw != null || isEncrypted(f)) decryptToTemp(f, pw, tempDir).also { temps += it } else f
            }
            PdfOps.merge(inputs, out)
        } finally {
            temps.forEach { it.delete() }
        }
    }

    /** Unlocked copy of [other] for `PdfOps.insertPagesFrom` (null when it needs no unlocking). Delete it after use. */
    fun unlockedForInsert(other: File, pw: String?, tempDir: File): File? =
        if (pw != null || isEncrypted(other)) decryptToTemp(other, pw, tempDir) else null
}
