package com.smartdocs.editor.core.security

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.RandomAccessFile

/** Reads [SecurityInfo] from a PdfBox document (document properties, reader restrictions, "Change Settings"). */
object SecurityInfoReader {

    fun of(doc: PDDocument): SecurityInfo {
        if (!doc.isEncrypted) return SecurityInfo.NONE
        val enc = doc.encryption ?: return SecurityInfo.NONE
        val filter = enc.filter.orEmpty()
        val method = when (filter) {
            "Standard" -> SecMethod.PASSWORD
            "Adobe.PubSec" -> SecMethod.CERTIFICATE
            else -> SecMethod.OTHER
        }
        val v = enc.version
        val cf = runCatching { enc.stdCryptFilterDictionary ?: enc.defaultCryptFilterDictionary }.getOrNull()
        val cfm = runCatching { cf?.cryptFilterMethod?.name }.getOrNull()
        val aes = v >= 5 || (v == 4 && cfm != null && cfm.startsWith("AESV"))
        val bits = when {
            v >= 5 -> 256
            v == 4 -> 128
            v <= 1 -> 40
            else -> enc.length.takeIf { it > 0 } ?: 40
        }
        // /EncryptMetadata sits in the dictionary (standard) or in the default crypt filter (certificate security)
        val meta = runCatching { enc.isEncryptMetaData && (cf?.isEncryptMetaData ?: true) }.getOrDefault(true)
        val ap = runCatching { doc.currentAccessPermission }.getOrNull()
        val perms = if (method == SecMethod.PASSWORD) enc.permissions else ap?.permissionBytes ?: enc.permissions
        return SecurityInfo(
            method = method, filter = filter, subFilter = runCatching { enc.subFilter }.getOrNull().orEmpty(),
            algorithm = if (aes) "AES" else "RC4", keyLength = bits, version = v, revision = enc.revision,
            encryptMetadata = meta, permissions = perms, ownerAccess = ap?.isOwnerPermission ?: false,
        )
    }

    /**
     * Cheap check before loading the document with PdfBox: the trailer (or a cross-reference stream) names /Encrypt.
     * The head and tail of the file are searched; false negatives fall back to the full check of [of].
     */
    fun mentionsEncrypt(file: File): Boolean = runCatching {
        val len = file.length()
        val needle = "/Encrypt".toByteArray()
        RandomAccessFile(file, "r").use { raf ->
            val tail = ByteArray(minOf(len, 256_000L).toInt())
            raf.seek(len - tail.size); raf.readFully(tail)
            val head = ByteArray(minOf(len, 64_000L).toInt())
            raf.seek(0); raf.readFully(head)
            contains(tail, needle) || contains(head, needle)
        }
    }.getOrDefault(true)

    internal fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
