package com.smartdocs.editor.core.sign

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The user's trusted certificates (Settings → signatures → trusted certificates). Each certificate is stored
 * DER-encoded as `filesDir/trusted_certs/<sha256-hex>.cer`. Import accepts .cer / .crt (DER or PEM) and .pem
 * bundles (several certificates). Verification marks a signature as trusted when its chain ends at one of them
 * (see [CertInspector]).
 */
object TrustedCerts {
    private fun dir(c: Context) = File(c.filesDir, "trusted_certs").apply { mkdirs() }

    data class Entry(val file: File, val cert: X509Certificate, val sha256: String)

    /** Parses DER or PEM (one or many certificates). */
    fun parse(bytes: ByteArray): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        return runCatching { cf.generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>() }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: pemBlocks(String(bytes, Charsets.US_ASCII)).mapNotNull { der ->
                runCatching { cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate }.getOrNull()
            }
    }

    private fun pemBlocks(text: String): List<ByteArray> =
        Regex("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE-----").findAll(text).mapNotNull { m ->
            runCatching { java.util.Base64.getMimeDecoder().decode(m.groupValues[1]) }.getOrNull()
        }.toList()

    /** Imports every certificate in [input]; returns how many were new. */
    suspend fun import(context: Context, input: InputStream): Int = withContext(Dispatchers.IO) {
        val certs = parse(input.use { it.readBytes() })
        if (certs.isEmpty()) throw IllegalArgumentException("no certificate")
        var added = 0
        for (c in certs) {
            val fp = CertFormat.hex(java.security.MessageDigest.getInstance("SHA-256").digest(c.encoded), "")
            val f = File(dir(context), "$fp.cer")
            if (!f.exists()) { f.writeBytes(c.encoded); added++ }
        }
        added
    }

    fun list(context: Context): List<Entry> = (dir(context).listFiles { f -> f.extension == "cer" } ?: emptyArray()).mapNotNull { f ->
        val c = runCatching { parse(f.readBytes()).firstOrNull() }.getOrNull() ?: return@mapNotNull null
        Entry(f, c, CertFormat.sha256(c.encoded))
    }.sortedBy { it.cert.subjectX500Principal.name }

    fun certificates(context: Context): List<X509Certificate> = list(context).map { it.cert }

    fun delete(entry: Entry) { entry.file.delete() }

    /** Adds an already-loaded certificate (e.g. the signer of a verified document: "trust this certificate"). */
    fun add(context: Context, cert: X509Certificate): Boolean {
        val fp = CertFormat.hex(java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded), "")
        val f = File(dir(context), "$fp.cer")
        if (f.exists()) return false
        f.writeBytes(cert.encoded); return true
    }
}
