package com.smartdocs.editor.core.pdf

import android.content.Context
import android.graphics.Bitmap
import com.smartdocs.editor.core.sign.SigFields
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
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

/** Where a signature goes (an existing empty field, or a new field). */
typealias SigTarget = SigFields.Target

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
        /** FieldMDP lock of an approval signature (Nitro "lock document after signing"); null = the field's own /Lock, if any. */
        val lock: com.smartdocs.editor.core.sign.SigFields.Lock? = null,
        /** Keep a copy without the verification info when the DSS adds more than this many bytes (0 = never ask). */
        val ltvAskAboveBytes: Long = 0,
        /** Message digest of the CMS signature (ISO/TS 32001: SHA-2 and SHA-3 families); EdDSA keys use their own. */
        val digest: Digest = Digest.SHA256,
    )

    enum class Digest(val jca: String) { SHA256("SHA256"), SHA384("SHA384"), SHA512("SHA512"), SHA3_256("SHA3-256"), SHA3_512("SHA3-512") }

    /** JCA signature algorithm for [key] and [digest] (RSA, ECDSA, Ed25519/Ed448). */
    internal fun signatureAlgorithm(key: PrivateKey, digest: Digest): String {
        val a = key.algorithm.uppercase()
        return when {
            a == "ED25519" || a == "ED448" || a == "EDDSA" -> if (a == "ED448") "Ed448" else "Ed25519"
            a == "EC" || a == "ECDSA" -> "${digest.jca}withECDSA"
            else -> "${digest.jca}withRSA"
        }
    }

    /** Seal image split across the right edge of all pages before signing (see core/sign/PagingSeal). */
    data class PagingSealOptions(val image: Bitmap, val sizePt: Float = 110f, val yFraction: Float = 0.5f)

    /** Outcome of the optional post-signing steps. */
    data class SignReport(
        val timestamped: Boolean, val ltv: com.smartdocs.editor.core.sign.LtvSupport.Report?, val docTimestamped: Boolean, val warnings: List<String>,
        /** Bytes the embedded verification info (DSS) added; with [withoutLtv] set when that was over the "ask" limit. */
        val ltvBytes: Long = 0,
        /** The same signed document without the verification info (Foxit "ask if verification info is too big"). */
        val withoutLtv: File? = null,
    )

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
        /** Signature form field holding this signature (Sign / Clear / Validate on a field). */
        val fieldName: String? = null,
        val contactInfo: String = "",
        /** 1-based revision this signature covers, out of [revisionCount] saved revisions ("Revision 2 of 3"). */
        val revision: Int = 0,
        val revisionCount: Int = 0,
        /** Length of the signed revision: bytes [0, signedLength) of the file are the version that was signed. */
        val signedLength: Long = 0,
        /** FieldMDP lock written with this signature. */
        val lock: com.smartdocs.editor.core.sign.SigFields.Lock? = null,
        /** Signers of CMS counter-signatures on this signature. */
        val counterSigners: List<String> = emptyList(),
        /** Names of signatures added in later revisions (they counter-sign this one at document level). */
        val signedLaterBy: List<String> = emptyList(),
        /** Signer of the signature time-stamp (or of a document time-stamp), for "trust this time-stamp server". */
        val timestampCert: X509Certificate? = null,
        /** OCSP/CRL revocation check of the signer certificate (see core/sign/Revocation); null = not checked. */
        val revocation: com.smartdocs.editor.core.sign.Revocation.Result? = null,
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

    private class CmsSigner(private val id: Identity, private val tsaUrl: String?, private val pades: Boolean, private val digest: Digest = Digest.SHA256) : SignatureInterface {
        var timestamped = false
        override fun sign(content: InputStream): ByteArray {
            ensureProvider()
            val gen = CMSSignedDataGenerator()
            val algo = signatureAlgorithm(id.privateKey, digest)
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
        val arr = sig.cosObject.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.REFERENCE) as? com.tom_roush.pdfbox.cos.COSArray
            ?: com.tom_roush.pdfbox.cos.COSArray().also { sig.cosObject.setItem(com.tom_roush.pdfbox.cos.COSName.REFERENCE, it) }
        arr.add(ref); arr.setNeedToBeUpdated(true)
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
        target: SigTarget? = null,
    ): SignReport = withContext(Dispatchers.IO) {
        ensureProvider()
        require(out.canonicalPath != src.canonicalPath) { "sign: out must differ from src" }
        if (options.docTimestamp && options.tsaUrl.isNullOrBlank()) throw IllegalArgumentException("document time-stamp needs a TSA")
        val temps = ArrayList<File>()
        fun tmp(tag: String) = File(context.cacheDir, "sig_${tag}_${System.nanoTime()}.pdf").also { temps += it }
        val warnings = ArrayList<String>()
        try {
            var input = src
            val (wasEncrypted, priorSigs) = (if (password.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, password)).use { d ->
                d.isEncrypted to runCatching { d.signatureDictionaries.isNotEmpty() }.getOrDefault(false)
            }
            // audit D5: the pre-steps rewrite the whole file, which would break the earlier signatures
            if (priorSigs && (pagingSeal != null || (visible != null && visible.image == null)))
                throw SignedDocumentException(SignedDocumentException.Reason.INCREMENTAL_FAILED,
                    "this file is already signed: a paging seal or a text-only appearance would break the earlier signatures")
            if (pagingSeal != null) {
                val t = tmp("seal")
                com.smartdocs.editor.core.sign.PagingSeal.apply(input, t, pagingSeal.image, pagingSeal.sizePt, pagingSeal.yFraction, password = password)
                input = t
            }
            // an image appearance goes into the signature widget (see signInto); text only is drawn into the page
            if (visible != null && visible.image == null) {
                val t = tmp("visible")
                PdfOps.stampText(context, input, visible.page, visible.text ?: options.name, visible.x, visible.y, visible.fontSize, 0x333333, t, password = password)
                input = t
            }
            val needPost = options.ltv || options.docTimestamp
            val signedOut = if (needPost) tmp("signed") else out
            val widget = visible?.takeIf { it.image != null }
            val tgt = target ?: widget?.let { SigTarget(page = it.page, rect = floatArrayOf(it.x, it.y, it.w, it.h)) } ?: SigTarget(page = visible?.page ?: 0)
            val draw: ((PDDocument, PDPageContentStream, Float, Float) -> Unit)? = widget?.image?.let { img -> { t, cs, w, h -> drawFitted(t, cs, img, w, h) } }
            // audit D3: an encrypted file is signed in place with its own security handler (an incremental update keeps
            // the password, the permissions and earlier signatures); the result is checked. Only an unsigned file may
            // fall back to an unencrypted signed copy, and the report says so.
            val timestamped = if (!wasEncrypted) signInto(input, signedOut, identity, options, tgt, draw) else {
                val kept = runCatching {
                    val ts = signInto(input, signedOut, identity, options, tgt, draw, password)
                    val results = verifyBlocking(signedOut, password)
                    check(results.isNotEmpty() && results.all { it.signatureValid && it.error == null }) { "encrypted signature check failed" }
                    ts
                }
                kept.getOrElse { e ->
                    if (priorSigs) throw SignedDocumentException(SignedDocumentException.Reason.INCREMENTAL_FAILED,
                        "this protected file is already signed and could not be signed without breaking the earlier signatures", e)
                    val decrypted = tmp("plain")
                    (if (password.isNullOrEmpty()) PDDocument.load(input) else PDDocument.load(input, password)).use { it.isAllSecurityToBeRemoved = true; it.save(decrypted) }
                    warnings += "ENCRYPTION_REMOVED"
                    signInto(decrypted, signedOut, identity, options, tgt, draw)
                }
            }

            var current = signedOut
            var ltvReport: com.smartdocs.editor.core.sign.LtvSupport.Report? = null
            var ltvBytes = 0L
            var withoutLtv: File? = null
            if (options.ltv) {
                val t = if (options.docTimestamp) tmp("dss") else out
                val before = current.length()
                // signedOut is a temp file when post-steps run, so the version without DSS is still there to keep
                val beforeCopy = if (options.ltvAskAboveBytes > 0) tmp("noltv").also { current.copyTo(it, overwrite = true) } else null
                ltvReport = try {
                    com.smartdocs.editor.core.sign.LtvSupport.addDss(current, t, online = true).also { current = t }
                } catch (e: Exception) {
                    warnings += "LTV: ${e.message ?: e.javaClass.simpleName}"; null
                }
                if (ltvReport != null) {
                    ltvBytes = current.length() - before
                    if (beforeCopy != null && ltvBytes > options.ltvAskAboveBytes) {
                        withoutLtv = File(out.parentFile, out.nameWithoutExtension + "_noltv.pdf")
                        beforeCopy.copyTo(withoutLtv, overwrite = true)
                    }
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
            SignReport(timestamped, ltvReport, docTs, warnings + (ltvReport?.problems ?: emptyList()), ltvBytes, withoutLtv)
        } finally {
            temps.forEach { runCatching { it.delete() } }
        }
    }

    /**
     * The cryptographic step of [sign], without Android dependencies: attaches the signature to its field
     * ([SigFields.prepare]: an existing empty field, or a new one on [target]'s page), adds the optional FieldMDP
     * lock and DocMDP certification, and appends it incrementally to [signedOut]. A field with a rectangle gets a
     * widget appearance drawn by [draw] (blank when null). Returns whether a signature time-stamp was embedded.
     */
    internal fun signInto(
        input: File, signedOut: File, identity: Identity, options: SignOptions, target: SigTarget,
        draw: ((PDDocument, PDPageContentStream, Float, Float) -> Unit)? = null, password: String? = null,
    ): Boolean {
        ensureProvider()
        val signer = CmsSigner(identity, options.tsaUrl?.takeIf { it.isNotBlank() }, options.pades, options.digest)
        // loadRaw keeps an encrypted file's security handler, so the incremental update is encrypted like the file
        (if (password == null) PDDocument.load(input) else PdfOps.loadRaw(input, password)).use { d ->
            if (options.certifyLevel in 1..3 && d.signatureDictionaries.isNotEmpty())
                throw IllegalStateException("certification must be the first signature")
            if (SignedSave.certifyLevel(d) == 1 || d.signatureDictionaries.any { SigFields.lockOf(d, it)?.docPermission == 1 })
                throw SignedDocumentException(SignedDocumentException.Reason.CERTIFIED_NO_CHANGES, "a signature of this document does not allow changes")
            val sig = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(if (options.pades) PDSignature.SUBFILTER_ETSI_CADES_DETACHED else PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                name = options.name.ifBlank { friendlyName(identity.subject) }
                location = options.location
                reason = options.reason
                contactInfo = options.contactInfo
                signDate = Calendar.getInstance()
            }
            val prep = SigFields.prepare(d, sig, target, options.lock)
            // the same appearance on the other pages of a multi-page signature (extra widgets, drawn here)
            for (w in prep.extraWidgets) {
                val r = w.rectangle ?: continue
                w.appearance = SigFields.appearance(d, r, w.page?.rotation ?: 0, draw ?: { _, _, _, _ -> })
            }
            if (options.certifyLevel in 1..3) setMdp(d, sig, options.certifyLevel)
            val size = SignatureOptions.DEFAULT_SIGNATURE_SIZE * (if (options.tsaUrl.isNullOrBlank()) 2 else 4)
            val sigOpts = SignatureOptions().apply { preferredSignatureSize = size; page = prep.page }
            try {
                // a field with a rectangle keeps it: PdfBox only zeroes the widget when no visual template is given
                prep.rect?.let { r ->
                    val box = d.getPage(prep.page).mediaBox
                    sigOpts.setVisualSignature(java.io.ByteArrayInputStream(SigFields.template(box, r, prep.rotation) { t, cs, w, h -> draw?.invoke(t, cs, w, h) }))
                }
                d.addSignature(sig, signer, sigOpts)
                signedOut.outputStream().use { d.saveIncremental(it) }
            } finally { runCatching { sigOpts.close() } }
        }
        return signer.timestamped
    }

    /** Draws [img] centred in a w×h box, keeping its aspect ratio. */
    private fun drawFitted(t: PDDocument, cs: PDPageContentStream, img: Bitmap, w: Float, h: Float) {
        val x = com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(t, img)
        val k = minOf(w / img.width, h / img.height)
        val dw = img.width * k; val dh = img.height * k
        cs.drawImage(x, (w - dw) / 2f, (h - dh) / 2f, dw, dh)
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
    /** Whether the time-stamp authority certificate of [tokenCms] has expired now (Foxit "use expired timestamps"). */
    private fun tsaExpired(tokenCms: CMSSignedData): Boolean = runCatching {
        val token = org.bouncycastle.tsp.TimeStampToken(tokenCms)
        @Suppress("UNCHECKED_CAST")
        val h = token.certificates.getMatches(token.sid as org.bouncycastle.util.Selector<X509CertificateHolder>).firstOrNull() as? X509CertificateHolder
        h != null && Date().after(h.notAfter)
    }.getOrDefault(false)

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

    /**
     * Validates every signature of [file]. With [revocation], the signer chain is also checked for revocation
     * (embedded DSS responses first; [online] allows OCSP/CRL requests).
     */
    suspend fun verify(
        file: File, revocation: Boolean = false, online: Boolean = true, password: String? = null,
        acceptExpiredTimestamps: Boolean = true, useDocumentValidationInfo: Boolean = true,
    ): List<VerifyResult> = withContext(Dispatchers.IO) { verifyBlocking(file, password, revocation, online, acceptExpiredTimestamps, useDocumentValidationInfo) }

    /** [verify] on the calling thread ([password] opens an encrypted file; the signed byte ranges are read raw). */
    internal fun verifyBlocking(
        file: File, password: String?, revocation: Boolean = false, online: Boolean = true,
        acceptExpiredTimestamps: Boolean = true, useDocumentValidationInfo: Boolean = true,
        /**
         * Tabs audit D22: results of an earlier check of the same file before an append. A signature with the same
         * signed length and name is not checked again (its signed bytes are unchanged); only its coverage changes.
         */
        reuse: List<VerifyResult> = emptyList(),
    ): List<VerifyResult> {
        ensureProvider()
        // the file is never read into memory as a whole: revisions are found by streaming, and each signature reads
        // only its own contents / signed ranges
        val fileLength = file.length()
        val revEnds = com.smartdocs.editor.core.sign.RevisionDiff.revisionEnds(file)
        val results = ArrayList<VerifyResult>()
        (if (password.isNullOrEmpty()) PDDocument.load(file) else PDDocument.load(file, password)).use { doc ->
            val ltv = com.smartdocs.editor.core.sign.LtvSupport.hasDss(doc)
            // Foxit "ignore document validation info": the embedded DSS responses are not used for revocation
            val material = if (!revocation) null
                else if (useDocumentValidationInfo) com.smartdocs.editor.core.sign.Revocation.dssMaterial(doc)
                else com.smartdocs.editor.core.sign.Revocation.Material(emptyList(), emptyList())
            for (sig in doc.signatureDictionaries) {
                val name = sig.name.orEmpty()
                val reason = sig.reason.orEmpty()
                val location = sig.location.orEmpty()
                val date = sig.signDate?.time
                val subFilter = sig.subFilter.orEmpty()
                val range = sig.byteRange
                val covers = range != null && range.size == 4 && range[0] == 0 && (range[2].toLong() + range[3].toLong()) == fileLength
                val signedLen = if (range != null && range.size == 4) range[2].toLong() + range[3].toLong() else 0L
                val revision = revEnds.indexOfFirst { it >= signedLen }.let { if (it < 0) revEnds.size else it + 1 }
                val common = VerifyResult(
                    name = name, signDate = date, reason = reason, coversWholeDocument = covers, signatureValid = false,
                    certSubject = "", certIssuer = "", validFrom = null, validTo = null, location = location, subFilter = subFilter,
                    ltvData = ltv, fieldName = SigFields.fieldOf(doc, sig), contactInfo = sig.contactInfo.orEmpty(),
                    revision = revision, revisionCount = revEnds.size, signedLength = signedLen, lock = SigFields.lockOf(doc, sig),
                )
                val isDocTs = subFilter == "ETSI.RFC3161"
                reuse.firstOrNull { it.signedLength == signedLen && it.fieldName == common.fieldName && it.error == null && it.isDocTimestamp == isDocTs }?.let { old ->
                    results += old.copy(coversWholeDocument = covers, revision = revision, revisionCount = revEnds.size, ltvData = ltv)
                    continue
                }
                try {
                    val contents = file.inputStream().buffered().use { sig.getContents(it) }
                    val signedContent = file.inputStream().buffered().use { sig.getSignedContent(it) }
                    if (isDocTs) {
                        val (genTime, ok) = checkToken(CMSSignedData(contents), signedContent)
                        val tokenCms = CMSSignedData(contents)
                        val cert = tokenCms.signerInfos.signers.firstOrNull()?.let { signerCert(tokenCms, it) }
                        results += common.copy(
                            name = cert?.let { friendlyName(it.subjectX500Principal.name) } ?: "TSA", signDate = genTime,
                            signatureValid = ok, certSubject = cert?.subjectX500Principal?.name.orEmpty(),
                            certIssuer = cert?.issuerX500Principal?.name.orEmpty(), validFrom = cert?.notBefore, validTo = cert?.notAfter,
                            location = "", timestampDate = genTime, timestampValid = ok, isDocTimestamp = true, timestampCert = cert,
                        )
                        continue
                    }
                    val cms = CMSSignedData(CMSProcessableByteArray(signedContent), contents)
                    val signerInfo = cms.signerInfos.signers.firstOrNull() ?: throw IllegalStateException("no signer info")
                    val cert = signerCert(cms, signerInfo) ?: throw IllegalStateException("signer certificate not embedded")
                    val valid = runCatching { signerInfo.verify(JcaSimpleSignerInfoVerifierBuilder().build(cert)) }.getOrDefault(false)
                    var tsDate: Date? = null; var tsOk = false; var tsCert: X509Certificate? = null
                    signerInfo.unsignedAttributes?.get(org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_signatureTimeStampToken)?.let { attr ->
                        val v = attr.attrValues.getObjectAt(0)
                        val tokenCms = CMSSignedData(org.bouncycastle.asn1.cms.ContentInfo.getInstance(v))
                        val r = checkToken(tokenCms, signerInfo.signature)
                        tsDate = r.first; tsOk = r.second && (acceptExpiredTimestamps || !tsaExpired(tokenCms))
                        tsCert = runCatching { tokenCms.signerInfos.signers.firstOrNull()?.let { signerCert(tokenCms, it) } }.getOrNull()
                    }
                    val counter = signerInfo.counterSignatures.signers.map { cs ->
                        signerCert(cms, cs)?.let { friendlyName(it.subjectX500Principal.name) } ?: cs.sid.issuer?.toString().orEmpty()
                    }
                    val rev = material?.let { m ->
                        val pool = cms.certificates.getMatches(null).mapNotNull { h -> runCatching { JcaX509CertificateConverter().getCertificate(h as X509CertificateHolder) }.getOrNull() }
                        runCatching { com.smartdocs.editor.core.sign.Revocation.check(cert, pool, m, online) }.getOrNull()
                    }
                    results += common.copy(
                        name = name.ifBlank { cert.subjectX500Principal.name }, signatureValid = valid,
                        certSubject = cert.subjectX500Principal.name, certIssuer = cert.issuerX500Principal.name,
                        validFrom = cert.notBefore, validTo = cert.notAfter,
                        timestampDate = tsDate, timestampValid = tsOk, certifyLevel = certifyLevelOf(doc, sig),
                        counterSigners = counter, revocation = rev, timestampCert = tsCert,
                    )
                } catch (e: Exception) {
                    results += common.copy(error = e.message ?: e.toString(), isDocTimestamp = isDocTs)
                }
            }
        }
        // a signature added in a later revision covers (and so counter-signs) every earlier one
        return results.map { r ->
            r.copy(signedLaterBy = results.filter { o -> o !== r && !o.isDocTimestamp && o.signedLength > r.signedLength }.map { friendlyName(it.name) })
        }
    }

    enum class VerifyTime { SIGNING_TIME, SECURE_TIME, CURRENT_TIME }

    /**
     * The time a signature is validated at (Acrobat/Foxit "verification time"): the claimed signing time, the
     * secure (time-stamp) time when there is a valid one (falling back to the signing time), or now.
     */
    fun verificationTime(r: VerifyResult, mode: VerifyTime, now: Date = Date()): Date? = when (mode) {
        VerifyTime.CURRENT_TIME -> now
        VerifyTime.SECURE_TIME -> r.timestampDate?.takeIf { r.timestampValid } ?: r.signDate
        VerifyTime.SIGNING_TIME -> r.signDate
    }

    /** Whether the signer certificate was within its validity period at [at] (null = unknown). */
    fun certValidAt(r: VerifyResult, at: Date?): Boolean? {
        val from = r.validFrom ?: return null; val to = r.validTo ?: return null
        at ?: return null
        return !at.before(from) && !at.after(to)
    }

    /** Human readable "CN=…" → "…" extraction for display. */
    fun friendlyName(dn: String): String =
        Regex("""CN=([^,]+)""").find(dn)?.groupValues?.get(1)?.trim() ?: dn
}
