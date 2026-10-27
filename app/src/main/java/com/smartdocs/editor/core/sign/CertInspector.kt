package com.smartdocs.editor.core.sign

import android.content.Context
import com.smartdocs.editor.core.pdf.DigitalSignature
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSSignedData
import java.io.File
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Certificate details + trust of every signature of a PDF, in the same order as [DigitalSignature.verify]
 * (both iterate `doc.signatureDictionaries`). Used by the verify tab next to each VerifyResult.
 */
object CertInspector {

    data class Details(
        val subject: String,
        val issuer: String,
        val serial: String,
        val notBefore: Date,
        val notAfter: Date,
        val sha256: String,
        val sha1: String,
        val keyUsage: List<CertFormat.KeyUsageBit>,
        val extendedKeyUsage: List<String>,
        val signatureAlgorithm: String,
        val publicKey: String,
        val selfSigned: Boolean,
        val expiredNow: Boolean,
        val md5: String = "",
    )

    data class SigCert(
        val index: Int,
        val details: Details?,
        val trust: TrustPath.Status,
        /** Subjects from the signer up to the trust anchor (or as far as the embedded chain goes). */
        val chain: List<String>,
        val anchor: String?,
        val signer: X509Certificate?,
        /** The trust anchor comes from the Android system CA store, not from the user's trusted certificates. */
        val systemAnchor: Boolean = false,
    )

    val ops = TrustPath.Ops<X509Certificate>(
        subject = { it.subjectX500Principal.name },
        issuer = { it.issuerX500Principal.name },
        fingerprint = { CertFormat.sha256(it.encoded) },
        signedBy = { child, parent -> runCatching { child.verify(parent.publicKey) }.isSuccess },
    )

    fun details(c: X509Certificate, now: Long = System.currentTimeMillis()): Details {
        val pk = c.publicKey
        val keyDesc = when (pk) {
            is RSAPublicKey -> "RSA ${pk.modulus.bitLength()}"
            is ECPublicKey -> "EC ${pk.params.order.bitLength()}"
            else -> pk.algorithm
        }
        return Details(
            subject = c.subjectX500Principal.name, issuer = c.issuerX500Principal.name,
            serial = CertFormat.serial(c.serialNumber), notBefore = c.notBefore, notAfter = c.notAfter,
            sha256 = CertFormat.sha256(c.encoded), sha1 = CertFormat.sha1(c.encoded),
            keyUsage = CertFormat.keyUsage(c.keyUsage),
            extendedKeyUsage = CertFormat.eku(runCatching { c.extendedKeyUsage }.getOrNull()),
            signatureAlgorithm = c.sigAlgName, publicKey = keyDesc,
            selfSigned = c.subjectX500Principal == c.issuerX500Principal && runCatching { c.verify(c.publicKey) }.isSuccess,
            expiredNow = now > c.notAfter.time,
            md5 = CertFormat.hex(java.security.MessageDigest.getInstance("MD5").digest(c.encoded)),
        )
    }

    /** [system]: also accept the Android system CA store as trust anchors (Windows-store equivalent, see [SystemTrust]). */
    suspend fun inspect(context: Context, file: File, password: String? = null, system: Boolean = SignPrefs.trustSystemRoots(context)): List<SigCert> = withContext(Dispatchers.IO) {
        DigitalSignature.ensureProvider()
        val user = TrustedCerts.certificates(context)
        val systemRoots = if (system) SystemTrust.anchors() else emptyList()
        val userFps = user.map { CertFormat.sha256(it.encoded) }.toSet()
        // nitro-protect2: third anchor set, the EU trusted list (qualified CAs), when the user turned it on
        val eutl = if (TrustLists.useEutl(context)) TrustLists.anchors(context) else emptyList()
        val sysFps = systemRoots.map { CertFormat.sha256(it.encoded) }.toSet()
        val trusted = user + systemRoots.filter { CertFormat.sha256(it.encoded) !in userFps } +
            eutl.filter { CertFormat.sha256(it.encoded).let { fp -> fp !in userFps && fp !in sysFps } }
        val bytes = file.readBytes()
        val conv = JcaX509CertificateConverter()
        val out = ArrayList<SigCert>()
        val doc = if (password.isNullOrEmpty()) PDDocument.load(file) else PDDocument.load(file, password)
        doc.use { d ->
            d.signatureDictionaries.forEachIndexed { i, sig ->
                val r = runCatching {
                    val cms = CMSSignedData(sig.getContents(bytes))
                    @Suppress("UNCHECKED_CAST")
                    val all = cms.certificates.getMatches(null).filterIsInstance<X509CertificateHolder>().map { conv.getCertificate(it) }
                    val si = cms.signerInfos.signers.firstOrNull()
                    @Suppress("UNCHECKED_CAST")
                    val signer = si?.let { s ->
                        (cms.certificates.getMatches(s.sid as org.bouncycastle.util.Selector<X509CertificateHolder>).firstOrNull() as? X509CertificateHolder)?.let { conv.getCertificate(it) }
                    } ?: all.firstOrNull()
                    if (signer == null) SigCert(i, null, TrustPath.Status.UNTRUSTED, emptyList(), null, null)
                    else {
                        val t = TrustPath.evaluate(signer, all.filter { it != signer }, trusted, ops)
                        SigCert(i, details(signer), t.status, t.chain.map { DigitalSignature.friendlyName(it.subjectX500Principal.name) },
                            t.anchor?.let { DigitalSignature.friendlyName(it.subjectX500Principal.name) }, signer,
                            systemAnchor = t.anchor != null && CertFormat.sha256(t.anchor.encoded) !in userFps)
                    }
                }.getOrElse { SigCert(i, null, TrustPath.Status.UNTRUSTED, emptyList(), null, null) }
                out += r
            }
        }
        out
    }
}
