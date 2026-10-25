package com.smartdocs.editor.core.pdf

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

/**
 * Certificate-based (PAdES-basic / adbe.pkcs7.detached) signing and verification built on PdfBox + BouncyCastle.
 * Keys come from a PKCS#12 file (user-provided or self-signed one generated on device).
 */
object DigitalSignature {

    /** A loaded signing identity. */
    class Identity(val privateKey: PrivateKey, val chain: List<X509Certificate>) {
        val certificate: X509Certificate get() = chain.first()
        val subject: String get() = certificate.subjectX500Principal.name
    }

    data class SignOptions(
        val name: String = "",
        val location: String = "",
        val reason: String = "",
        val contactInfo: String = "",
        /** RFC 3161 time-stamp authority URL; null/blank = no signature time-stamp. */
        val tsaUrl: String? = null,
        /** true = ETSI.CAdES.detached with signing-certificate-v2 (PAdES baseline), false = adbe.pkcs7.detached. */
        val pades: Boolean = false,
        /** 0 = approval signature; 1/2/3 = certification signature with DocMDP P=1 (no changes), 2 (fill+sign), 3 (+annotate). */
        val certifyLevel: Int = 0,
        /** After signing, embed a DSS with certificates + OCSP/CRL (PAdES-LT, best effort, needs internet). */
        val ltv: Boolean = false,
        /** After signing (and DSS), append an RFC 3161 document time-stamp (PAdES-LTA). Needs [tsaUrl]. */
        val docTimestamp: Boolean = false,
    )

    /** Seal image split across the right edge of all pages before signing (see core/sign/PagingSeal). */
    data class PagingSealOptions(val image: Bitmap, val sizePt: Float = 110f, val yFraction: Float = 0.5f)

    /** Outcome of the optional post-signing steps. */
    data class SignReport(val timestamped: Boolean, val ltv: com.smartdocs.editor.core.sign.LtvSupport.Report?, val docTimestamped: Boolean, val warnings: List<String>)

    /** Optional visible appearance drawn on [page] before the cryptographic signature is applied. */
    data class VisibleSignature(
        val page: Int,
        val x: Float, val y: Float, val w: Float, val h: Float,   // PDF points, origin bottom-left
        val image: Bitmap? = null,
        val text: String? = null,
        val fontSize: Float = 11f,
    )

    data class VerifyResult(
        val name: String,
        val signDate: Date?,
        val reason: String,
        val coversWholeDocument: Boolean,
        val signatureValid: Boolean,
        val certSubject: String,
        val certIssuer: String,
        val validFrom: Date?,
        val validTo: Date?,
        val location: String = "",
        val error: String? = null,
        val subFilter: String = "",
        /** Signature time-stamp (unsigned attribute) or, for a document time-stamp, the token time. */
        val timestampDate: Date? = null,
        val timestampValid: Boolean = false,
        val isDocTimestamp: Boolean = false,
        /** DocMDP level when this is the certification signature, else null. */
        val certifyLevel: Int? = null,
        /** The document carries a DSS (validation material for long-term validation). */
        val ltvData: Boolean = false,
    )

    /** File that holds the on-device self-signed certificate. */
    fun ownKeystoreFile(context: Context): File = File(context.filesDir, "own_certificate.p12")

    // ---------------------------------------------------------------- provider

    @Volatile private var providerReady = false

    /**
     * Android ships a stripped-down BouncyCastle registered under the name "BC" (no PKCS#12 with modern
     * ciphers, missing CMS/PKIX algorithms). Replace it once with the full bcprov that is bundled with the app
     * so that PKCS12 keystores, CMS signing and certificate conversion resolve consistently.
     */
    @Synchronized
    internal fun ensureProvider() {
        if (providerReady) return
        runCatching {
            val existing = Security.getProvider("BC")
            if (existing == null || existing !is BouncyCastleProvider) {
                Security.removeProvider("BC")
                Security.addProvider(BouncyCastleProvider())
            }
            // PdfBox's public-key security handler uses its own provider holder; give it the full BC as well
            com.tom_roush.pdfbox.pdmodel.encryption.SecurityProvider.setProvider(Security.getProvider("BC") ?: BouncyCastleProvider())
        }
        providerReady = true
    }

    internal fun pkcs12Store(): KeyStore {
        ensureProvider()
        return runCatching { KeyStore.getInstance("PKCS12", "BC") }.getOrElse { KeyStore.getInstance("PKCS12") }
    }

    // ---------------------------------------------------------------- keystore

    /** Load the first key entry of a PKCS#12 keystore. */
    suspend fun loadPkcs12(input: InputStream, password: CharArray): Identity = withContext(Dispatchers.IO) {
        val ks = pkcs12Store()
        input.use { ks.load(it, password) }
        val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) } ?: throw IllegalStateException("no private key in keystore")
        val key = ks.getKey(alias, password) as? PrivateKey ?: throw IllegalStateException("not a private key")
        val chain = ks.getCertificateChain(alias)?.mapNotNull { it as? X509Certificate }.orEmpty()
        if (chain.isEmpty()) throw IllegalStateException("certificate chain missing")
        Identity(key, chain)
    }

    suspend fun loadPkcs12(file: File, password: CharArray): Identity = loadPkcs12(file.inputStream(), password)

    /** Generate an RSA-2048 self-signed certificate and store it as PKCS#12 in [out]. Returns the identity. */
    suspend fun generateSelfSigned(out: File, commonName: String, organization: String, email: String, password: CharArray, validYears: Int = 5): Identity =
        withContext(Dispatchers.IO) {
            ensureProvider()
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val nb = X500NameBuilder(BCStyle.INSTANCE)
            nb.addRDN(BCStyle.CN, commonName.ifBlank { "SmartDocs User" })
            if (organization.isNotBlank()) nb.addRDN(BCStyle.O, organization)
            if (email.isNotBlank()) nb.addRDN(BCStyle.EmailAddress, email)
            val name: X500Name = nb.build()
            val now = Date()
            val until = Calendar.getInstance().apply { time = now; add(Calendar.YEAR, validYears) }.time
            val builder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(System.currentTimeMillis()), now, until, name, kp.public)
            // keyEncipherment/dataEncipherment let the same certificate be used for certificate (public-key) encryption
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation or KeyUsage.keyEncipherment or KeyUsage.dataEncipherment))
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val holder: X509CertificateHolder = builder.build(signer)
            val cert = JcaX509CertificateConverter().getCertificate(holder)
            val ks = pkcs12Store()
            ks.load(null, null)
            ks.setKeyEntry("smartdocs", kp.private, password, arrayOf(cert))
            out.outputStream().use { ks.store(it, password) }
            Identity(kp.private, listOf(cert))
        }

    // ---------------------------------------------------------------- signing

    private class CmsSigner(private val id: Identity, private val tsaUrl: String?, private val pades: Boolean) : SignatureInterface {
        var timestamped = false
        override fun sign(content: InputStream): ByteArray {
            ensureProvider()
            val gen = CMSSignedDataGenerator()
            val algo = if (id.privateKey.algorithm.equals("EC", true) || id.privateKey.algorithm.equals("ECDSA", true)) "SHA256withECDSA" else "SHA256withRSA"
            val contentSigner = JcaContentSignerBuilder(algo).build(id.privateKey)
            val sib = JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
            if (pades) {
                // ESS signing-certificate-v2 is mandatory for ETSI.CAdES.detached (PAdES baseline B)
                val certHash = java.security.MessageDigest.getInstance("SHA-256").digest(id.certificate.encoded)
                val issuerSerial = org.bouncycastle.asn1.x509.IssuerSerial(
                    org.bouncycastle.asn1.x509.GeneralNames(org.bouncycastle.asn1.x509.GeneralName(X500Name.getInstance(id.certificate.issuerX500Principal.encoded))),
                    id.certificate.serialNumber)
                val scv2 = org.bouncycastle.asn1.ess.SigningCertificateV2(org.bouncycastle.asn1.ess.ESSCertIDv2(certHash, issuerSerial))
                val attr = org.bouncycastle.asn1.cms.Attribute(org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_signingCertificateV2, org.bouncycastle.asn1.DERSet(scv2))
                sib.setSignedAttributeGenerator(org.bouncycastle.cms.DefaultSignedAttributeTableGenerator(org.bouncycastle.asn1.cms.AttributeTable(attr)))
            }
            gen.addSignerInfoGenerator(sib.build(contentSigner, id.certificate))
            gen.addCertificates(JcaCertStore(id.chain))
            val msg = CMSProcessableByteArray(content.readBytes())
            var signed = gen.generate(msg, false)
            if (!tsaUrl.isNullOrBlank()) {
                val si = signed.signerInfos.signers.first()
                val token = com.smartdocs.editor.core.sign.TsaClient.timestamp(si.signature, tsaUrl)
                val tsAttr = org.bouncycastle.asn1.ASN1Primitive.fromByteArray(token)
                val unsigned = (si.unsignedAttributes ?: org.bouncycastle.asn1.cms.AttributeTable(org.bouncycastle.asn1.ASN1EncodableVector()))
                    .add(org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_signatureTimeStampToken, tsAttr)
                val newSi = org.bouncycastle.cms.SignerInformation.replaceUnsignedAttributes(si, unsigned)
                signed = CMSSignedData.replaceSigners(signed, org.bouncycastle.cms.SignerInformationStore(newSi))
                timestamped = true
            }
            return signed.encoded
        }
    }

    /** DocMDP transform on the signature + catalog /Perms (PdfBox SigUtils.setMDPPermission). */
    private fun setMdp(doc: PDDocument, sig: PDSignature, p: Int) {
        val tp = com.tom_roush.pdfbox.cos.COSDictionary().apply {
            setItem(com.tom_roush.pdfbox.cos.COSName.TYPE, com.tom_roush.pdfbox.cos.COSName.TRANSFORM_PARAMS)
            setInt(com.tom_roush.pdfbox.cos.COSName.P, p.coerceIn(1, 3))
            setName(com.tom_roush.pdfbox.cos.COSName.V, "1.2")
            setNeedToBeUpdated(true)
        }
        val ref = com.tom_roush.pdfbox.cos.COSDictionary().apply {
            setItem(com.tom_roush.pdfbox.cos.COSName.TYPE, com.tom_roush.pdfbox.cos.COSName.SIG_REF)
            setItem(com.tom_roush.pdfbox.cos.COSName.TRANSFORM_METHOD, com.tom_roush.pdfbox.cos.COSName.DOCMDP)
            setItem(com.tom_roush.pdfbox.cos.COSName.DIGEST_METHOD, com.tom_roush.pdfbox.cos.COSName.getPDFName("SHA256"))
            setItem(com.tom_roush.pdfbox.cos.COSName.TRANSFORM_PARAMS, tp)
            setNeedToBeUpdated(true)
        }
        val arr = com.tom_roush.pdfbox.cos.COSArray().apply { add(ref); setNeedToBeUpdated(true) }
        sig.cosObject.setItem(com.tom_roush.pdfbox.cos.COSName.REFERENCE, arr)
        val catalog = doc.documentCatalog.cosObject
        val perms = com.tom_roush.pdfbox.cos.COSDictionary().apply { setItem(com.tom_roush.pdfbox.cos.COSName.DOCMDP, sig); setNeedToBeUpdated(true) }
        catalog.setItem(com.tom_roush.pdfbox.cos.COSName.PERMS, perms)
        catalog.setNeedToBeUpdated(true)
    }

    /**
     * Sign [src] into [out]. Optional pre-steps are drawn into the content first (so the signature covers
     * them): the paging seal strips and the visible appearance. Then the CMS signature (optionally with an
     * RFC 3161 time-stamp, PAdES attributes and a DocMDP certification) is appended incrementally, followed
     * by the optional DSS (LTV) and document time-stamp increments.
     */
    suspend fun sign(
        context: Context, src: File, out: File, identity: Identity, options: SignOptions,
        visible: VisibleSignature? = null, password: String? = null, pagingSeal: PagingSealOptions? = null,
    ): SignReport = withContext(Dispatchers.IO) {
        ensureProvider()
        require(out.canonicalPath != src.canonicalPath) { "sign: out must differ from src" }
        if (options.docTimestamp && options.tsaUrl.isNullOrBlank()) throw IllegalArgumentException("document time-stamp needs a TSA")
        val temps = ArrayList<File>()
        fun tmp(tag: String) = File(context.cacheDir, "sig_${tag}_${System.nanoTime()}.pdf").also { temps += it }
        val warnings = ArrayList<String>()
        try {
            var input = src
            if (pagingSeal != null) {
                val t = tmp("seal")
                com.smartdocs.editor.core.sign.PagingSeal.apply(input, t, pagingSeal.image, pagingSeal.sizePt, pagingSeal.yFraction, password = password)
                input = t
            }
            if (visible != null) {
                val t = tmp("visible")
                if (visible.image != null) {
                    PdfOps.stampImage(input, visible.page, visible.image, visible.x, visible.y, visible.w, visible.h, t, password = password)
                    if (!visible.text.isNullOrBlank()) {
                        val t2 = tmp("visible2")
                        PdfOps.stampText(context, t, visible.page, visible.text, visible.x, visible.y - visible.fontSize - 2f, visible.fontSize, 0x333333, t2, password = password)
                        input = t2
                    } else input = t
                } else {
                    PdfOps.stampText(context, input, visible.page, visible.text ?: options.name, visible.x, visible.y, visible.fontSize, 0x333333, t, password = password)
                    input = t
                }
            }
            // saveIncremental() cannot strip encryption (it appends to the original bytes); an encrypted
            // input is first fully re-saved without security, then signed incrementally.
            val probe = if (password.isNullOrEmpty()) PDDocument.load(input) else PDDocument.load(input, password)
            if (probe.isEncrypted) {
                val decrypted = tmp("plain")
                probe.use { it.isAllSecurityToBeRemoved = true; it.save(decrypted) }
                input = decrypted
            } else probe.close()

            val needPost = options.ltv || options.docTimestamp
            val signedOut = if (needPost) tmp("signed") else out
            val signer = CmsSigner(identity, options.tsaUrl?.takeIf { it.isNotBlank() }, options.pades)
            PDDocument.load(input).use { d ->
                if (options.certifyLevel in 1..3 && d.signatureDictionaries.isNotEmpty())
                    throw IllegalStateException("certification must be the first signature")
                val sig = PDSignature().apply {
                    setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                    setSubFilter(if (options.pades) PDSignature.SUBFILTER_ETSI_CADES_DETACHED else PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                    name = options.name.ifBlank { friendlyName(identity.subject) }
                    location = options.location
                    reason = options.reason
                    contactInfo = options.contactInfo
                    signDate = Calendar.getInstance()
                }
                if (options.certifyLevel in 1..3) setMdp(d, sig, options.certifyLevel)
                val size = SignatureOptions.DEFAULT_SIGNATURE_SIZE * (if (options.tsaUrl.isNullOrBlank()) 2 else 4)
                val sigOpts = SignatureOptions().apply { preferredSignatureSize = size }
                try {
                    d.addSignature(sig, signer, sigOpts)
                    signedOut.outputStream().use { d.saveIncremental(it) }
                } finally { runCatching { sigOpts.close() } }
            }

            var current = signedOut
            var ltvReport: com.smartdocs.editor.core.sign.LtvSupport.Report? = null
            if (options.ltv) {
                val t = if (options.docTimestamp) tmp("dss") else out
                ltvReport = try {
                    com.smartdocs.editor.core.sign.LtvSupport.addDss(current, t, online = true).also { current = t }
                } catch (e: Exception) {
                    warnings += "LTV: ${e.message ?: e.javaClass.simpleName}"; null
                }
            }
            var docTs = false
            if (options.docTimestamp) {
                try {
                    com.smartdocs.editor.core.sign.LtvSupport.addDocTimestamp(current, out, options.tsaUrl!!)
                    current = out; docTs = true
                } catch (e: Exception) { warnings += "DocTimeStamp: ${e.message ?: e.javaClass.simpleName}" }
            }
            if (current != out) current.copyTo(out, overwrite = true)
            SignReport(signer.timestamped, ltvReport, docTs, warnings + (ltvReport?.problems ?: emptyList()))
        } finally {
            temps.forEach { runCatching { it.delete() } }
        }
    }

    // ---------------------------------------------------------------- verification

    private fun certifyLevelOf(doc: PDDocument, sig: PDSignature): Int? = runCatching {
        val refs = sig.cosObject.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.REFERENCE) as? com.tom_roush.pdfbox.cos.COSArray ?: return null
        for (i in 0 until refs.size()) {
            val ref = refs.getObject(i) as? com.tom_roush.pdfbox.cos.COSDictionary ?: continue
            if (ref.getCOSName(com.tom_roush.pdfbox.cos.COSName.TRANSFORM_METHOD) == com.tom_roush.pdfbox.cos.COSName.DOCMDP) {
                val tp = ref.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.TRANSFORM_PARAMS) as? com.tom_roush.pdfbox.cos.COSDictionary
                return tp?.getInt(com.tom_roush.pdfbox.cos.COSName.P, 2) ?: 2
            }
        }
        null
    }.getOrNull()

    private fun signerCert(cms: CMSSignedData, signerInfo: org.bouncycastle.cms.SignerInformation): X509Certificate? {
        @Suppress("UNCHECKED_CAST")
        val holder = cms.certificates.getMatches(signerInfo.sid as org.bouncycastle.util.Selector<X509CertificateHolder>).firstOrNull() as? X509CertificateHolder ?: return null
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    /** Validate an RFC 3161 token over [data]; returns (genTime, valid). */
    private fun checkToken(tokenCms: CMSSignedData, data: ByteArray): Pair<Date?, Boolean> = runCatching {
        val token = org.bouncycastle.tsp.TimeStampToken(tokenCms)
        val info = token.timeStampInfo
        val algo = info.messageImprintAlgOID.id
        val md = java.security.MessageDigest.getInstance(when (algo) {
            "2.16.840.1.101.3.4.2.1" -> "SHA-256"; "2.16.840.1.101.3.4.2.2" -> "SHA-384"; "2.16.840.1.101.3.4.2.3" -> "SHA-512"; else -> "SHA-1"
        })
        val imprintOk = md.digest(data).contentEquals(info.messageImprintDigest)
        val tsaCert = token.certificates.getMatches(token.sid as org.bouncycastle.util.Selector<X509CertificateHolder>).firstOrNull() as? X509CertificateHolder
        val sigOk = tsaCert != null && runCatching { token.isSignatureValid(JcaSimpleSignerInfoVerifierBuilder().build(tsaCert)) }.getOrDefault(false)
        info.genTime to (imprintOk && sigOk)
    }.getOrDefault(null to false)

    suspend fun verify(file: File): List<VerifyResult> = withContext(Dispatchers.IO) {
        ensureProvider()
        val bytes = file.readBytes()
        val results = ArrayList<VerifyResult>()
        PDDocument.load(file).use { doc ->
            val ltv = com.smartdocs.editor.core.sign.LtvSupport.hasDss(doc)
            for (sig in doc.signatureDictionaries) {
                val name = sig.name.orEmpty()
                val reason = sig.reason.orEmpty()
                val location = sig.location.orEmpty()
                val date = sig.signDate?.time
                val subFilter = sig.subFilter.orEmpty()
                val range = sig.byteRange
                val covers = range != null && range.size == 4 && range[0] == 0 && (range[2].toLong() + range[3].toLong()) == bytes.size.toLong()
                val isDocTs = subFilter == "ETSI.RFC3161"
                try {
                    val contents = sig.getContents(bytes)
                    val signedContent = sig.getSignedContent(bytes)
                    if (isDocTs) {
                        val (genTime, ok) = checkToken(CMSSignedData(contents), signedContent)
                        val tokenCms = CMSSignedData(contents)
                        val cert = tokenCms.signerInfos.signers.firstOrNull()?.let { signerCert(tokenCms, it) }
                        results += VerifyResult(
                            name = cert?.let { friendlyName(it.subjectX500Principal.name) } ?: "TSA", signDate = genTime, reason = reason,
                            coversWholeDocument = covers, signatureValid = ok, certSubject = cert?.subjectX500Principal?.name.orEmpty(),
                            certIssuer = cert?.issuerX500Principal?.name.orEmpty(), validFrom = cert?.notBefore, validTo = cert?.notAfter,
                            subFilter = subFilter, timestampDate = genTime, timestampValid = ok, isDocTimestamp = true, ltvData = ltv,
                        )
                        continue
                    }
                    val cms = CMSSignedData(CMSProcessableByteArray(signedContent), contents)
                    val signerInfo = cms.signerInfos.signers.firstOrNull() ?: throw IllegalStateException("no signer info")
                    val cert = signerCert(cms, signerInfo) ?: throw IllegalStateException("signer certificate not embedded")
                    val valid = runCatching { signerInfo.verify(JcaSimpleSignerInfoVerifierBuilder().build(cert)) }.getOrDefault(false)
                    var tsDate: Date? = null; var tsOk = false
                    signerInfo.unsignedAttributes?.get(org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_signatureTimeStampToken)?.let { attr ->
                        val v = attr.attrValues.getObjectAt(0)
                        val r = checkToken(CMSSignedData(org.bouncycastle.asn1.cms.ContentInfo.getInstance(v)), signerInfo.signature)
                        tsDate = r.first; tsOk = r.second
                    }
                    results += VerifyResult(
                        name = name.ifBlank { cert.subjectX500Principal.name }, signDate = date, reason = reason,
                        coversWholeDocument = covers, signatureValid = valid,
                        certSubject = cert.subjectX500Principal.name, certIssuer = cert.issuerX500Principal.name,
                        validFrom = cert.notBefore, validTo = cert.notAfter, location = location,
                        subFilter = subFilter, timestampDate = tsDate, timestampValid = tsOk,
                        certifyLevel = certifyLevelOf(doc, sig), ltvData = ltv,
                    )
                } catch (e: Exception) {
                    results += VerifyResult(name, date, reason, covers, false, "", "", null, null, location, e.message ?: e.toString(), subFilter = subFilter, isDocTimestamp = isDocTs, ltvData = ltv)
                }
            }
        }
        results
    }

    /** Human readable "CN=…" → "…" extraction for display. */
    fun friendlyName(dn: String): String =
        Regex("""CN=([^,]+)""").find(dn)?.groupValues?.get(1)?.trim() ?: dn
}
