package com.smartdocs.editor.core.sign

import com.smartdocs.editor.core.pdf.DigitalSignature
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.bouncycastle.asn1.x509.AccessDescription
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.ocsp.BasicOCSPResp
import org.bouncycastle.cert.ocsp.CertificateID
import org.bouncycastle.cert.ocsp.OCSPReqBuilder
import org.bouncycastle.cert.ocsp.OCSPResp
import org.bouncycastle.cert.ocsp.RevokedStatus
import org.bouncycastle.cert.ocsp.UnknownStatus
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Revocation check of a signer's certificate chain during validation (Nitro/Acrobat "LTV verification"):
 * OCSP first, then CRL, using the responses embedded in the document's DSS and, when [online], the
 * certificate's AIA OCSP / CRL distribution points. Network calls block: run on Dispatchers.IO.
 */
object Revocation {

    enum class Status { GOOD, REVOKED, UNKNOWN }

    enum class Source { OCSP, CRL, EMBEDDED_OCSP, EMBEDDED_CRL, NONE }

    /** One certificate of the chain. [revokedAt] is set for REVOKED. */
    data class CertResult(val subject: String, val status: Status, val source: Source, val revokedAt: Date? = null, val problem: String? = null)

    /** Whole chain: REVOKED if any certificate is, GOOD only if every non-root certificate was checked GOOD. */
    data class Result(val status: Status, val certs: List<CertResult>) {
        /** Revoked, but only after [signedAt] (a time-stamped signature made before the revocation stays valid). */
        fun revokedAfter(signedAt: Date?): Boolean =
            status == Status.REVOKED && signedAt != null && certs.filter { it.status == Status.REVOKED }.all { it.revokedAt?.after(signedAt) == true }
    }

    data class Material(val ocsps: List<ByteArray>, val crls: List<ByteArray>)

    private val converter by lazy { JcaX509CertificateConverter() }

    /** OCSP responses and CRLs of the document security store (/DSS /OCSPs, /CRLs). */
    fun dssMaterial(doc: PDDocument): Material {
        val dss = doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("DSS")) as? COSDictionary ?: return Material(emptyList(), emptyList())
        fun streams(key: String): List<ByteArray> = (dss.getDictionaryObject(COSName.getPDFName(key)) as? COSArray)?.let { a ->
            (0 until a.size()).mapNotNull { i -> (a.getObject(i) as? COSStream)?.let { s -> runCatching { s.createInputStream().use { it.readBytes() } }.getOrNull() } }
        }.orEmpty()
        return Material(streams("OCSPs"), streams("CRLs"))
    }

    /**
     * Checks [leaf] and every issuing certificate found in [pool] up to (not including) a self-signed root.
     * Revocation dates are reported as they are; [Result.revokedAfter] compares them with the signing time.
     */
    fun check(leaf: X509Certificate, pool: List<X509Certificate>, material: Material, online: Boolean): Result {
        val chain = TrustPath.build(leaf, pool, CertInspector.ops)
        val results = ArrayList<CertResult>()
        for ((i, c) in chain.withIndex()) {
            if (isSelfSigned(c)) break
            val issuer = chain.getOrNull(i + 1) ?: pool.firstOrNull { it.subjectX500Principal == c.issuerX500Principal && signedBy(c, it) }
            results += checkOne(c, issuer, material, online)
        }
        val status = when {
            results.any { it.status == Status.REVOKED } -> Status.REVOKED
            results.isNotEmpty() && results.all { it.status == Status.GOOD } -> Status.GOOD
            else -> Status.UNKNOWN
        }
        return Result(status, results)
    }

    private fun checkOne(c: X509Certificate, issuer: X509Certificate?, m: Material, online: Boolean): CertResult {
        val name = DigitalSignature.friendlyName(c.subjectX500Principal.name)
        if (issuer != null) {
            for (o in m.ocsps) ocspStatus(o, c, issuer)?.let { (st, at) -> if (st != Status.UNKNOWN) return CertResult(name, st, Source.EMBEDDED_OCSP, at) }
        }
        for (crl in m.crls) crlStatus(crl, c, issuer)?.let { (st, at) -> return CertResult(name, st, Source.EMBEDDED_CRL, at) }
        if (!online) return CertResult(name, Status.UNKNOWN, Source.NONE)
        val problems = ArrayList<String>()
        if (issuer != null) {
            for (url in LtvSupport.extensionUrls(c, AccessDescription.id_ad_ocsp)) {
                val r = runCatching { ocspStatus(TsaClient.post(url, ocspRequest(c, issuer), "application/ocsp-request", "application/ocsp-response"), c, issuer) }
                    .getOrElse { problems += "OCSP: ${it.message}"; null }
                if (r != null && r.first != Status.UNKNOWN) return CertResult(name, r.first, Source.OCSP, r.second)
            }
        }
        for (url in LtvSupport.crlUrls(c)) {
            val r = runCatching { crlStatus(TsaClient.get(url), c, issuer) }.getOrElse { problems += "CRL: ${it.message}"; null }
            if (r != null) return CertResult(name, r.first, Source.CRL, r.second)
        }
        return CertResult(name, Status.UNKNOWN, Source.NONE, problem = problems.firstOrNull() ?: "no revocation source")
    }

    internal fun ocspRequest(c: X509Certificate, issuer: X509Certificate): ByteArray {
        DigitalSignature.ensureProvider()
        val digest = JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1)
        return OCSPReqBuilder().addRequest(CertificateID(digest, JcaX509CertificateHolder(issuer), c.serialNumber)).build().encoded
    }

    /**
     * Status of [c] in an OCSP response (full OCSPResp or bare BasicOCSPResponse), or null when the response does
     * not cover it or its signature does not verify (signed by the issuer or by a responder it certified).
     */
    internal fun ocspStatus(bytes: ByteArray, c: X509Certificate, issuer: X509Certificate): Pair<Status, Date?>? = runCatching {
        DigitalSignature.ensureProvider()
        val basic = runCatching { OCSPResp(bytes).takeIf { it.status == OCSPResp.SUCCESSFUL }?.responseObject as? BasicOCSPResp }.getOrNull()
            ?: BasicOCSPResp(org.bouncycastle.asn1.ocsp.BasicOCSPResponse.getInstance(bytes))
        val digest = JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1)
        val id = CertificateID(digest, JcaX509CertificateHolder(issuer), c.serialNumber)
        val single = basic.responses.firstOrNull { it.certID == id } ?: return null
        val responders = listOf(issuer) + basic.certs.mapNotNull { h -> runCatching { converter.getCertificate(h) }.getOrNull() }
            .filter { r -> r.issuerX500Principal == issuer.subjectX500Principal && signedBy(r, issuer) }
        val ok = responders.any { r -> runCatching { basic.isSignatureValid(JcaContentVerifierProviderBuilder().build(r.publicKey)) }.getOrDefault(false) }
        if (!ok) return null
        when (val st = single.certStatus) {
            null -> Status.GOOD to null
            is RevokedStatus -> Status.REVOKED to st.revocationTime
            is UnknownStatus -> Status.UNKNOWN to null
            else -> Status.UNKNOWN to null
        }
    }.getOrNull()

    /** Status of [c] in a CRL from its issuer (signature checked when [issuer] is known), or null if not applicable. */
    internal fun crlStatus(bytes: ByteArray, c: X509Certificate, issuer: X509Certificate?): Pair<Status, Date?>? = runCatching {
        val crl = CertificateFactory.getInstance("X.509").generateCRL(ByteArrayInputStream(bytes)) as X509CRL
        if (crl.issuerX500Principal != c.issuerX500Principal) return null
        if (issuer != null) crl.verify(issuer.publicKey)
        val entry = crl.getRevokedCertificate(c.serialNumber)
        if (entry != null) Status.REVOKED to entry.revocationDate else Status.GOOD to null
    }.getOrNull()

    private fun isSelfSigned(c: X509Certificate) = c.subjectX500Principal == c.issuerX500Principal && signedBy(c, c)
    private fun signedBy(child: X509Certificate, parent: X509Certificate) = runCatching { child.verify(parent.publicKey) }.isSuccess
}
