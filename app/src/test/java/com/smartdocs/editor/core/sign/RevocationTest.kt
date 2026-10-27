package com.smartdocs.editor.core.sign

import com.smartdocs.editor.core.pdf.DigitalSignature
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.CRLReason
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v2CRLBuilder
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder
import org.bouncycastle.cert.ocsp.CertificateID
import org.bouncycastle.cert.ocsp.CertificateStatus
import org.bouncycastle.cert.ocsp.OCSPResp
import org.bouncycastle.cert.ocsp.OCSPRespBuilder
import org.bouncycastle.cert.ocsp.RespID
import org.bouncycastle.cert.ocsp.RevokedStatus
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date

/** Nitro §11 "LTV verification": OCSP / CRL revocation status of a signer chain. */
class RevocationTest {

    private fun keys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val now = Date()
    private val caKeys = keys()
    private val leafKeys = keys()

    private fun cert(subject: String, issuer: String, pub: java.security.PublicKey, signKey: java.security.PrivateKey, ca: Boolean, serial: Long): X509Certificate {
        DigitalSignature.ensureProvider()
        val b = JcaX509v3CertificateBuilder(X500Name(issuer), BigInteger.valueOf(serial), Date(now.time - 86_400_000), Date(now.time + 86_400_000L * 365), X500Name(subject), pub)
        if (ca) {
            b.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            b.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        }
        return JcaX509CertificateConverter().getCertificate(b.build(JcaContentSignerBuilder("SHA256withRSA").build(signKey)))
    }

    private val ca = cert("CN=Test CA", "CN=Test CA", caKeys.public, caKeys.private, true, 1)
    private val leaf = cert("CN=Signer", "CN=Test CA", leafKeys.public, caKeys.private, false, 42)

    private fun crl(revoked: Boolean, signer: java.security.PrivateKey = caKeys.private): ByteArray {
        val b = X509v2CRLBuilder(X500Name("CN=Test CA"), now)
        if (revoked) b.addCRLEntry(BigInteger.valueOf(42), Date(now.time - 3_600_000), CRLReason.keyCompromise)
        return JcaX509CRLConverter().getCRL(b.build(JcaContentSignerBuilder("SHA256withRSA").build(signer))).encoded
    }

    private fun ocsp(status: CertificateStatus?, signer: java.security.PrivateKey = caKeys.private): ByteArray {
        val digest = JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1)
        val id = CertificateID(digest, JcaX509CertificateHolder(ca), leaf.serialNumber)
        val basic = BasicOCSPRespBuilder(RespID(X500Name("CN=Test CA"))).addResponse(id, status)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(signer), null, now)
        return OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, basic).encoded
    }

    @Test fun crlStatus() {
        assertEquals(Revocation.Status.GOOD, Revocation.crlStatus(crl(false), leaf, ca)?.first)
        val r = Revocation.crlStatus(crl(true), leaf, ca)
        assertEquals(Revocation.Status.REVOKED, r?.first)
        assertTrue(r!!.second!!.before(now))
        assertNull("a CRL signed by another key is ignored", Revocation.crlStatus(crl(true, keys().private), leaf, ca))
    }

    @Test fun ocspStatus() {
        assertEquals(Revocation.Status.GOOD, Revocation.ocspStatus(ocsp(CertificateStatus.GOOD), leaf, ca)?.first)
        val rev = Revocation.ocspStatus(ocsp(RevokedStatus(Date(now.time - 60_000), CRLReason.keyCompromise)), leaf, ca)
        assertEquals(Revocation.Status.REVOKED, rev?.first)
        assertNull("a response signed by an unrelated key is ignored", Revocation.ocspStatus(ocsp(CertificateStatus.GOOD, keys().private), leaf, ca))
        assertTrue(Revocation.ocspRequest(leaf, ca).isNotEmpty())
        assertEquals(OCSPResp.SUCCESSFUL, OCSPResp(ocsp(CertificateStatus.GOOD)).status)
    }

    @Test fun chainResultUsesEmbeddedMaterialOffline() {
        val good = Revocation.check(leaf, listOf(ca), Revocation.Material(listOf(ocsp(CertificateStatus.GOOD)), emptyList()), online = false)
        assertEquals(Revocation.Status.GOOD, good.status)
        assertEquals(Revocation.Source.EMBEDDED_OCSP, good.certs.single().source)
        val revoked = Revocation.check(leaf, listOf(ca), Revocation.Material(emptyList(), listOf(crl(true))), online = false)
        assertEquals(Revocation.Status.REVOKED, revoked.status)
        assertTrue("revoked an hour ago, signed two hours ago", revoked.revokedAfter(Date(now.time - 7_200_000)))
        assertFalse(revoked.revokedAfter(now))
        val unknown = Revocation.check(leaf, listOf(ca), Revocation.Material(emptyList(), emptyList()), online = false)
        assertEquals(Revocation.Status.UNKNOWN, unknown.status)
    }
}
