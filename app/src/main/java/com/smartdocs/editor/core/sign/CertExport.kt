package com.smartdocs.editor.core.sign

import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSAbsentContent
import org.bouncycastle.cms.CMSSignedDataGenerator
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Export of Trusted Contacts certificates: .cer (one certificate, DER), .pem (one or more, Base64 armour) and
 * .p7b / .p7c (a degenerate PKCS#7 / CMS SignedData with certificates and no signers, DER). .p7b and .p7c hold
 * the same bytes; only the extension and media type differ.
 */
object CertExport {

    enum class Kind(val ext: String, val mime: String, val multi: Boolean) {
        CER("cer", "application/pkix-cert", false),
        PEM("pem", "application/x-pem-file", true),
        P7B("p7b", "application/x-pkcs7-certificates", true),
        P7C("p7c", "application/pkcs7-mime", true),
    }

    fun encode(certs: List<X509Certificate>, kind: Kind): ByteArray {
        require(certs.isNotEmpty()) { "no certificate" }
        require(kind.multi || certs.size == 1) { "${kind.ext} holds one certificate" }
        return when (kind) {
            Kind.CER -> certs.single().encoded
            Kind.PEM -> pem(certs).toByteArray(Charsets.US_ASCII)
            Kind.P7B, Kind.P7C -> pkcs7(certs)
        }
    }

    fun pem(certs: List<X509Certificate>): String = buildString {
        val enc = Base64.getMimeEncoder(64, "\n".toByteArray())
        certs.forEach { c -> append("-----BEGIN CERTIFICATE-----\n").append(enc.encodeToString(c.encoded)).append("\n-----END CERTIFICATE-----\n") }
    }

    /** Certs-only SignedData (RFC 5652 "degenerate" case), as Windows / Acrobat write .p7b / .p7c. */
    fun pkcs7(certs: List<X509Certificate>): ByteArray {
        val gen = CMSSignedDataGenerator()
        gen.addCertificates(JcaCertStore(certs))
        return gen.generate(CMSAbsentContent(), false).toASN1Structure().getEncoded(ASN1Encoding.DER)
    }

    /** A file name from the certificate's common name (or "certificates" for several). */
    fun fileName(certs: List<X509Certificate>, kind: Kind): String {
        val base = if (certs.size == 1) cn(certs.single().subjectX500Principal.name) else "certificates"
        val safe = base.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").trim('_').take(60).ifEmpty { "certificate" }
        return "$safe.${kind.ext}"
    }

    internal fun cn(dn: String): String =
        Regex("""(?:^|,)\s*CN=((?:\\.|[^,])*)""", RegexOption.IGNORE_CASE).find(dn)?.groupValues?.get(1)?.replace("\\", "")?.trim().orEmpty()
}
