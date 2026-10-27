package com.smartdocs.editor.core.sign

import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.PublicKeyProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.encryption.PublicKeyRecipient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Certificate (public-key, "Adobe.PubSec") encryption with PdfBox: each recipient's X.509 certificate gets its
 * own envelope carrying its permissions; the document is encrypted with AES-256 (adbe.pkcs7.s5 / AESV3).
 * Opening such a file needs the matching private key (PKCS#12).
 */
object CertEncryption {

    /** Permission set with the dependency rules of the reference app (see spec §4/§5). */
    data class Perms(
        val print: Boolean = true,
        val fillForms: Boolean = true,
        val annotate: Boolean = true,
        val assemble: Boolean = true,
        val modify: Boolean = true,
        val copyAccessibility: Boolean = true,
        val extract: Boolean = true,
    ) {
        fun withPrint(v: Boolean) = copy(print = v)
        /** Off → comment and modify off. */
        fun withFill(v: Boolean) = if (v) copy(fillForms = true) else copy(fillForms = false, annotate = false, modify = false)
        /** On → fill on. */
        fun withAnnotate(v: Boolean) = if (v) copy(annotate = true, fillForms = true) else copy(annotate = false)
        /** Off → modify off. */
        fun withAssemble(v: Boolean) = if (v) copy(assemble = true) else copy(assemble = false, modify = false)
        /** On → fill + assemble on. */
        fun withModify(v: Boolean) = if (v) copy(modify = true, fillForms = true, assemble = true) else copy(modify = false)
        /** Off → extract off. */
        fun withCopyAccessibility(v: Boolean) = if (v) copy(copyAccessibility = true) else copy(copyAccessibility = false, extract = false)
        /** On → accessibility copy on. */
        fun withExtract(v: Boolean) = if (v) copy(extract = true, copyAccessibility = true) else copy(extract = false)

        fun toAccessPermission(): AccessPermission = AccessPermission().apply {
            setCanPrint(print); setCanPrintFaithful(print)
            setCanFillInForm(fillForms)
            setCanModifyAnnotations(annotate)
            setCanAssembleDocument(assemble)
            setCanModify(modify)
            setCanExtractForAccessibility(copyAccessibility)
            setCanExtractContent(extract)
        }

        companion object { val ALL = Perms(); val READ_ONLY = Perms(false, false, false, false, false, true, false) }
    }

    data class Recipient(val certificate: X509Certificate, val perms: Perms = Perms.ALL, val label: String = "") {
        val name: String get() = label.ifBlank { DigitalSignature.friendlyName(certificate.subjectX500Principal.name) }
    }

    /** Problems that make a certificate unusable (or doubtful) as an encryption recipient. */
    enum class CertIssue { EXPIRED, NOT_YET_VALID, NO_ENCIPHERMENT, NOT_RSA }

    fun issues(c: X509Certificate, now: Date = Date()): List<CertIssue> {
        val out = ArrayList<CertIssue>()
        if (now.after(c.notAfter)) out += CertIssue.EXPIRED
        if (now.before(c.notBefore)) out += CertIssue.NOT_YET_VALID
        // keyUsage bits: 2 = keyEncipherment, 3 = dataEncipherment; null = no restriction
        c.keyUsage?.let { ku -> if (ku.size > 3 && !ku[2] && !ku[3]) out += CertIssue.NO_ENCIPHERMENT }
        if (!c.publicKey.algorithm.equals("RSA", true)) out += CertIssue.NOT_RSA
        return out
    }

    /** Hard blockers (PdfBox key transport only works with RSA keys; an expired certificate is refused like the reference app). */
    fun isBlocking(i: CertIssue) = i == CertIssue.EXPIRED || i == CertIssue.NOT_RSA

    /**
     * Read recipient certificates from .cer/.crt/.der/.pem (one or more) or a PKCS#7 bundle (.p7b/.p7c).
     * Throws when nothing could be read.
     */
    fun readCertificates(input: InputStream): List<X509Certificate> {
        val bytes = input.use { it.readBytes() }
        val cf = CertificateFactory.getInstance("X.509")
        val certs = runCatching { cf.generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>() }.getOrDefault(emptyList())
        if (certs.isNotEmpty()) return certs
        // some PEM files carry text before the BEGIN line
        val text = runCatching { String(bytes, Charsets.US_ASCII) }.getOrDefault("")
        val blocks = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----").findAll(text).map { it.value }.toList()
        val parsed = blocks.mapNotNull { b -> runCatching { cf.generateCertificate(ByteArrayInputStream(b.toByteArray())) as X509Certificate }.getOrNull() }
        if (parsed.isEmpty()) throw IllegalArgumentException("no certificate")
        return parsed
    }

    /** Certificate of a PKCS#12 keystore (e.g. the user's own on-device certificate). */
    suspend fun certificateFromPkcs12(input: InputStream, password: CharArray): X509Certificate =
        DigitalSignature.loadPkcs12(input, password).certificate

    /**
     * Encrypt [src] for [recipients] into [out]. [currentPassword] opens a password-protected source (its
     * password security is replaced). [keyLength] 256 = AES-256 (default), 128 = AES-128. [encryptMetadata] false
     * leaves the XMP metadata in clear ([PubSecMetaHandler]). [unlock]: the source is itself certificate encrypted
     * and is opened with this digital ID first ("change settings": new recipient list, written to a new file).
     */
    suspend fun encrypt(src: File, out: File, recipients: List<Recipient>, keyLength: Int = 256, currentPassword: String? = null,
                        encryptMetadata: Boolean = true, unlock: DigitalSignature.Identity? = null) = withContext(Dispatchers.IO) {
        require(recipients.isNotEmpty()) { "no recipients" }
        DigitalSignature.ensureProvider()
        val write: (PDDocument) -> Unit = { doc ->
            if (encryptMetadata) {
                val policy = PublicKeyProtectionPolicy()
                recipients.forEach { r ->
                    policy.addRecipient(PublicKeyRecipient().apply { x509 = r.certificate; permission = r.perms.toAccessPermission() })
                }
                policy.encryptionKeyLength = keyLength
                doc.protect(policy)
            } else {
                PubSecCrypto.register()
                doc.protect(PubSecMetaPolicy(recipients.map { it.certificate to it.perms.toAccessPermission() }, keyLength))
            }
            val tmp = File(out.parentFile, ".${out.name}.${System.nanoTime()}.tmp")
            try { doc.save(tmp); if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() } } finally { if (tmp.exists()) tmp.delete() }
        }
        if (unlock != null) PubSecCrypto.withDocument(src, unlock, write) else PdfOps.load(src, currentPassword).use(write)
    }

    /** Remove certificate protection with a digital ID (file or KeyChain): an unprotected copy in [out]. */
    suspend fun decryptToFile(src: File, out: File, identity: DigitalSignature.Identity) = withContext(Dispatchers.IO) {
        PubSecCrypto.withDocument(src, identity) { doc ->
            doc.isAllSecurityToBeRemoved = true
            val tmp = File(out.parentFile, ".${out.name}.${System.nanoTime()}.tmp")
            try { doc.save(tmp); if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() } } finally { if (tmp.exists()) tmp.delete() }
        }
    }

    /** Permissions [identity] has in a certificate-encrypted [file] (throws when it is not a recipient). */
    suspend fun permissionsFor(file: File, identity: DigitalSignature.Identity): AccessPermission = withContext(Dispatchers.IO) {
        PubSecCrypto.withDocument(file, identity) { it.currentAccessPermission }
    }

    /** First alias of [p12] that holds a private key (PdfBox needs the alias when the keystore has several entries). */
    private fun keyAlias(p12: ByteArray, password: String): String {
        val ks = DigitalSignature.pkcs12Store()
        ks.load(ByteArrayInputStream(p12), password.toCharArray())
        return ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) } ?: throw IllegalStateException("no private key in keystore")
    }

    /**
     * Open a certificate-encrypted PDF with the recipient's PKCS#12 key through
     * `PDDocument.load(file, keystorePassword, keyStoreStream, alias)`.
     */
    fun open(file: File, p12: ByteArray, password: String): PDDocument {
        DigitalSignature.ensureProvider()
        val alias = keyAlias(p12, password)
        return PDDocument.load(file, password, ByteArrayInputStream(p12), alias)
    }

    /** Write a decrypted copy of a certificate-encrypted [src] into [out] (for reading/editing elsewhere). */
    suspend fun decryptToFile(src: File, out: File, p12: ByteArray, password: String) = withContext(Dispatchers.IO) {
        open(src, p12, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(out)
        }
    }

    /** Permissions granted to the holder of [p12] in a certificate-encrypted [file]. */
    suspend fun permissionsFor(file: File, p12: ByteArray, password: String): AccessPermission = withContext(Dispatchers.IO) {
        open(file, p12, password).use { it.currentAccessPermission }
    }

    /**
     * True when [file] is protected with the public-key handler (/Filter /Adobe.PubSec). Detected from the
     * raw trailer bytes, because PdfBox cannot parse it without the key.
     */
    fun isCertificateEncrypted(file: File): Boolean = runCatching {
        val len = file.length()
        val tail = ByteArray(minOf(len, 256_000L).toInt())
        java.io.RandomAccessFile(file, "r").use { raf ->
            // /Encrypt dictionaries usually sit near the end (classic trailer) – check the tail and the head
            raf.seek(len - tail.size); raf.readFully(tail)
        }
        val head = ByteArray(minOf(len, 64_000L).toInt())
        file.inputStream().use { it.read(head) }
        val needle = "Adobe.PubSec".toByteArray()
        indexOf(tail, needle) >= 0 || indexOf(head, needle) >= 0
    }.getOrDefault(false)

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
