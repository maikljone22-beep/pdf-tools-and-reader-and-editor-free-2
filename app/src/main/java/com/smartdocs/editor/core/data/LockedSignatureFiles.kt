package com.smartdocs.editor.core.data

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted form of a password-locked QuickSign signature (nitro-fillsign2, pdf-ad request): the app-data bundle
 * never carries a locked signature's image in the clear. The PNG is encrypted with AES-256-GCM under a key derived
 * from the signature's PIN / password with PBKDF2-HMAC-SHA256 and its OWN random salt (not the salt of the lock
 * hash, so the hash in the sidecar is not the key). Pure JVM code: unit-tested.
 *
 * Blob layout: "SDSL" | version 1 | iterations (int) | salt (16) | iv (12) | ciphertext + GCM tag.
 */
object LockedSignatureCrypto {
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte(), 'L'.code.toByte())
    private const val VERSION = 1

    private fun key(secret: CharArray, salt: ByteArray, iterations: Int) = SecretKeySpec(SignatureLock.hash(secret, salt, iterations), "AES")

    fun encrypt(plain: ByteArray, secret: CharArray, iterations: Int = SignatureLock.ITERATIONS, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(secret, salt, iterations), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC)
        val ct = c.doFinal(plain)
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).use { o -> o.write(MAGIC); o.writeByte(VERSION); o.writeInt(iterations); o.write(salt); o.write(iv); o.write(ct) }
        return bo.toByteArray()
    }

    /** The PNG bytes, or null when [secret] is wrong, the blob was changed or it is not a blob. */
    fun decrypt(blob: ByteArray, secret: CharArray): ByteArray? = runCatching {
        DataInputStream(blob.inputStream()).use { i ->
            val magic = ByteArray(4); i.readFully(magic)
            if (!magic.contentEquals(MAGIC) || i.readUnsignedByte() != VERSION) return null
            val iterations = i.readInt(); if (iterations !in 1..10_000_000) return null
            val salt = ByteArray(16); i.readFully(salt)
            val iv = ByteArray(12); i.readFully(iv)
            val ct = i.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(secret, salt, iterations), GCMParameterSpec(128, iv))
            c.updateAAD(MAGIC)
            try { c.doFinal(ct) } catch (e: AEADBadTagException) { null }
        }
    }.getOrNull()

    fun isBlob(bytes: ByteArray): Boolean = bytes.size > 37 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)
}

/** The encrypted copy `<image>.sdlock` kept next to a locked signature's PNG. */
object LockedSignatureFiles {
    fun blobOf(image: File) = File(image.parentFile, image.name + ".sdlock")

    /** Up to date: written after the last change of the image (or the image is not on this device yet). */
    fun fresh(image: File): Boolean { val b = blobOf(image); return b.exists() && (!image.exists() || b.lastModified() >= image.lastModified()) }

    /** (Re)writes the encrypted copy of [image] under [secret]. */
    fun write(image: File, secret: CharArray) {
        if (!image.exists()) return
        val b = blobOf(image); val tmp = File(b.parentFile, b.name + ".tmp")
        tmp.writeBytes(LockedSignatureCrypto.encrypt(image.readBytes(), secret))
        if (!tmp.renameTo(b)) { b.delete(); tmp.renameTo(b) }
    }

    /**
     * After a successful unlock: an imported locked signature (only the blob on this device) gets its PNG back;
     * a stale or missing blob is rewritten.
     */
    fun sync(image: File, secret: CharArray) {
        val b = blobOf(image)
        if (!image.exists() && b.exists()) {
            LockedSignatureCrypto.decrypt(b.readBytes(), secret)?.let { png ->
                image.writeBytes(png); b.setLastModified(image.lastModified())
            }
        } else if (!fresh(image)) write(image, secret)
    }
}
