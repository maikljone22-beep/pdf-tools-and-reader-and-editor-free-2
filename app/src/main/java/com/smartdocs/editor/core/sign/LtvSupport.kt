package com.smartdocs.editor.core.sign

import com.smartdocs.editor.core.pdf.DigitalSignature
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AccessDescription
import org.bouncycastle.asn1.x509.AuthorityInformationAccess
import org.bouncycastle.asn1.x509.CRLDistPoint
import org.bouncycastle.asn1.x509.DistributionPointName
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.ocsp.BasicOCSPResp
import org.bouncycastle.cert.ocsp.CertificateID
import org.bouncycastle.cert.ocsp.OCSPReqBuilder
import org.bouncycastle.cert.ocsp.OCSPResp
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * PAdES long-term validation helpers:
 *  - [addDss]: append a Document Security Store (/DSS with /Certs, /OCSPs, /CRLs and per-signature /VRI)
 *    in an incremental update, fetching OCSP/CRL responses from each certificate's AIA / CRL DP (best effort).
 *  - [addDocTimestamp]: append an RFC 3161 document time-stamp (/Type /DocTimeStamp, ETSI.RFC3161) → PAdES-LTA.
 * Blocking network calls: run on Dispatchers.IO.
 */
object LtvSupport {

    data class Report(val certs: Int, val ocsps: Int, val crls: Int, val signatures: Int, val problems: List<String>)

    private val SUBFILTER_RFC3161: COSName = COSName.getPDFName("ETSI.RFC3161")

    // ---------------------------------------------------------------- material collection

    private class Material {
        val certs = LinkedHashMap<String, X509Certificate>()      // key = sha1 hex of encoding
        val ocsps = ArrayList<ByteArray>()
        val crls = ArrayList<ByteArray>()
        val problems = ArrayList<String>()
        // per signature (VRI)
        val certKeys = HashSet<String>(); val ocspIdx = HashSet<Int>(); val crlIdx = HashSet<Int>()
    }

    private fun sha1Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02X".format(it) }

    private val converter by lazy { JcaX509CertificateConverter() }

    private fun certsOfCms(cms: CMSSignedData): List<X509Certificate> =
        cms.certificates.getMatches(null).mapNotNull { h -> runCatching { converter.getCertificate(h as X509CertificateHolder) }.getOrNull() }

    /** Certificates of the signer CMS plus those inside an embedded signature time-stamp token. */
    private fun certsOfSignature(contents: ByteArray, isDocTimestamp: Boolean): List<X509Certificate> {
        val cms = CMSSignedData(contents)
        val out = ArrayList(certsOfCms(cms))
        if (!isDocTimestamp) cms.signerInfos.signers.forEach { si ->
            val attr = si.unsignedAttributes?.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken) ?: return@forEach
            attr.attrValues.forEach { v ->
                runCatching { out += certsOfCms(CMSSignedData(ContentInfo.getInstance(v))) }
            }
        }
        return out
    }

    private fun isSelfSigned(c: X509Certificate): Boolean =
        c.subjectX500Principal == c.issuerX500Principal && runCatching { c.verify(c.publicKey); true }.getOrDefault(false)

    internal fun extensionUrls(c: X509Certificate, method: org.bouncycastle.asn1.ASN1ObjectIdentifier): List<String> = runCatching {
        val raw = c.getExtensionValue(Extension.authorityInfoAccess.id) ?: return emptyList()
        val octets = (ASN1Primitive.fromByteArray(raw) as org.bouncycastle.asn1.ASN1OctetString).octets
        AuthorityInformationAccess.getInstance(ASN1Primitive.fromByteArray(octets)).accessDescriptions
            .filter { it.accessMethod == method && it.accessLocation.tagNo == GeneralName.uniformResourceIdentifier }
            .map { it.accessLocation.name.toString() }
    }.getOrDefault(emptyList())

    internal fun crlUrls(c: X509Certificate): List<String> = runCatching {
        val raw = c.getExtensionValue(Extension.cRLDistributionPoints.id) ?: return emptyList()
        val octets = (ASN1Primitive.fromByteArray(raw) as org.bouncycastle.asn1.ASN1OctetString).octets
        CRLDistPoint.getInstance(ASN1Primitive.fromByteArray(octets)).distributionPoints.flatMap { dp ->
            val name = dp.distributionPoint ?: return@flatMap emptyList<String>()
            if (name.type != DistributionPointName.FULL_NAME) return@flatMap emptyList<String>()
            GeneralNames.getInstance(name.name).names.filter { it.tagNo == GeneralName.uniformResourceIdentifier }.map { it.name.toString() }
        }.filter { it.startsWith("http", true) }
    }.getOrDefault(emptyList())

    private fun findIssuer(c: X509Certificate, pool: Collection<X509Certificate>): X509Certificate? =
        pool.firstOrNull { it != c && it.subjectX500Principal == c.issuerX500Principal && runCatching { c.verify(it.publicKey); true }.getOrDefault(false) }

    /** Download the issuer from AIA caIssuers (DER, PEM or PKCS#7 bundle). */
    private fun downloadIssuer(c: X509Certificate, m: Material): X509Certificate? {
        for (url in extensionUrls(c, AccessDescription.id_ad_caIssuers)) {
            val got = runCatching {
                val bytes = TsaClient.get(url)
                CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>()
            }.getOrElse { m.problems += "AIA $url: ${it.message}"; emptyList() }
            got.firstOrNull { it.subjectX500Principal == c.issuerX500Principal }?.let { return it }
        }
        return null
    }

    private fun fetchOcsp(c: X509Certificate, issuer: X509Certificate, m: Material): ByteArray? {
        val urls = extensionUrls(c, AccessDescription.id_ad_ocsp)
        if (urls.isEmpty()) return null
        val digest = JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1)
        val id = CertificateID(digest, JcaX509CertificateHolder(issuer), c.serialNumber)
        val req = OCSPReqBuilder().addRequest(id).build().encoded
        for (url in urls) {
            val r = runCatching {
                val resp = OCSPResp(TsaClient.post(url, req, "application/ocsp-request", "application/ocsp-response"))
                if (resp.status != OCSPResp.SUCCESSFUL) error("status ${resp.status}")
                val basic = resp.responseObject as? BasicOCSPResp ?: error("not a basic response")
                // responder certificates are validation material too
                basic.certs.forEach { h -> runCatching { addCert(m, converter.getCertificate(h)) } }
                resp.encoded
            }.getOrElse { m.problems += "OCSP $url: ${it.message}"; null }
            if (r != null) return r
        }
        return null
    }

    private fun fetchCrl(c: X509Certificate, m: Material): ByteArray? {
        for (url in crlUrls(c)) {
            val r = runCatching {
                val bytes = TsaClient.get(url)
                CertificateFactory.getInstance("X.509").generateCRL(ByteArrayInputStream(bytes)); bytes   // validates it parses
            }.getOrElse { m.problems += "CRL $url: ${it.message}"; null }
            if (r != null) return r
        }
        return null
    }

    private fun addCert(m: Material, c: X509Certificate): String {
        val key = sha1Hex(c.encoded)
        m.certs.putIfAbsent(key, c)
        m.certKeys += key
        return key
    }

    /** Build the chain of [start] certificates and gather revocation data for every non-root certificate. */
    private fun collect(start: List<X509Certificate>, m: Material, online: Boolean) {
        val queue = ArrayDeque(start)
        val done = HashSet<String>()
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            val key = addCert(m, c)
            if (!done.add(key)) continue
            if (isSelfSigned(c)) continue
            var issuer = findIssuer(c, m.certs.values + start)
            if (issuer == null && online) issuer = downloadIssuer(c, m)
            if (issuer != null) queue.addLast(issuer)
            if (!online) continue
            val ocsp = if (issuer != null) fetchOcsp(c, issuer, m) else null
            if (ocsp != null) { m.ocsps += ocsp; m.ocspIdx += m.ocsps.lastIndex }
            else {
                val crl = fetchCrl(c, m)
                if (crl != null) { m.crls += crl; m.crlIdx += m.crls.lastIndex }
                else if (extensionUrls(c, AccessDescription.id_ad_ocsp).isEmpty() && crlUrls(c).isEmpty())
                    m.problems += "no revocation source: ${DigitalSignature.friendlyName(c.subjectX500Principal.name)}"
            }
        }
    }

    // ---------------------------------------------------------------- DSS writing

    private fun stream(doc: PDDocument, data: ByteArray): COSStream {
        val s = doc.document.createCOSStream()
        s.createOutputStream(COSName.FLATE_DECODE).use { it.write(data) }
        s.setNeedToBeUpdated(true)
        return s
    }

    private fun arrayIn(dict: COSDictionary, name: COSName): COSArray {
        val a = dict.getDictionaryObject(name) as? COSArray ?: COSArray().also { dict.setItem(name, it) }
        a.setNeedToBeUpdated(true)
        return a
    }

    /**
     * Append a DSS for every signature of [src] to [out] (incremental update, so existing signatures stay valid).
     * [online] = false only embeds the certificates already present (no OCSP/CRL download).
     */
    fun addDss(src: File, out: File, online: Boolean = true): Report {
        DigitalSignature.ensureProvider()
        require(src.canonicalPath != out.canonicalPath) { "addDss: out must differ from src" }
        val fileBytes = src.readBytes()
        PDDocument.load(src).use { doc ->
            val sigs = doc.signatureDictionaries
            if (sigs.isEmpty()) throw IllegalStateException("no signatures")
            val catalog = doc.documentCatalog.cosObject
            val dss = catalog.getDictionaryObject(COSName.getPDFName("DSS")) as? COSDictionary ?: COSDictionary()
            catalog.setItem(COSName.getPDFName("DSS"), dss)
            dss.setNeedToBeUpdated(true)
            val certsArr = arrayIn(dss, COSName.getPDFName("Certs"))
            val ocspArr = arrayIn(dss, COSName.getPDFName("OCSPs"))
            val crlArr = arrayIn(dss, COSName.getPDFName("CRLs"))
            val vri = dss.getDictionaryObject(COSName.getPDFName("VRI")) as? COSDictionary ?: COSDictionary().also { dss.setItem(COSName.getPDFName("VRI"), it) }
            vri.setNeedToBeUpdated(true)

            val certStreams = HashMap<String, COSStream>()
            val allProblems = ArrayList<String>()
            var nOcsp = 0; var nCrl = 0
            for (sig in sigs) {
                val contents = runCatching { sig.getContents(fileBytes) }.getOrNull() ?: continue
                val isTs = sig.subFilter == SUBFILTER_RFC3161.name
                val m = Material()
                val start = runCatching { certsOfSignature(contents, isTs) }.getOrElse { allProblems += it.message ?: "cms"; emptyList() }
                if (start.isEmpty()) continue
                collect(start, m, online)
                allProblems += m.problems
                val vriCerts = COSArray(); val vriOcsp = COSArray(); val vriCrl = COSArray()
                m.certKeys.forEach { k ->
                    val s = certStreams.getOrPut(k) { stream(doc, m.certs.getValue(k).encoded).also { certsArr.add(it) } }
                    vriCerts.add(s)
                }
                m.ocspIdx.forEach { i -> val s = stream(doc, m.ocsps[i]); ocspArr.add(s); vriOcsp.add(s); nOcsp++ }
                m.crlIdx.forEach { i -> val s = stream(doc, m.crls[i]); crlArr.add(s); vriCrl.add(s); nCrl++ }
                val entry = COSDictionary().apply {
                    setItem(COSName.getPDFName("Cert"), vriCerts)
                    if (vriOcsp.size() > 0) setItem(COSName.getPDFName("OCSP"), vriOcsp)
                    if (vriCrl.size() > 0) setItem(COSName.getPDFName("CRL"), vriCrl)
                    setNeedToBeUpdated(true)
                }
                val sigKey = sha1Hex(sig.contents ?: contents)
                vri.setItem(COSName.getPDFName(sigKey), entry)
            }
            addPadesExtension(catalog)
            catalog.setNeedToBeUpdated(true)
            out.outputStream().use { doc.saveIncremental(it) }
            return Report(certStreams.size, nOcsp, nCrl, sigs.size, allProblems.distinct())
        }
    }

    /** /Extensions /ESIC (ETSI PAdES) in the catalog, as recommended for DSS-bearing documents. */
    private fun addPadesExtension(catalog: COSDictionary) {
        val ext = catalog.getDictionaryObject(COSName.getPDFName("Extensions")) as? COSDictionary ?: COSDictionary()
        if (ext.containsKey(COSName.getPDFName("ESIC"))) return
        val esic = COSDictionary().apply {
            setItem(COSName.getPDFName("BaseVersion"), COSName.getPDFName("1.7"))
            setInt(COSName.getPDFName("ExtensionLevel"), 5)
            setNeedToBeUpdated(true)
        }
        ext.setItem(COSName.getPDFName("ESIC"), esic)
        ext.setNeedToBeUpdated(true)
        catalog.setItem(COSName.getPDFName("Extensions"), ext)
    }

    // ---------------------------------------------------------------- document time-stamp

    /** Append an RFC 3161 document time-stamp signature (PAdES-LTA) to [src], written to [out]. */
    fun addDocTimestamp(src: File, out: File, tsaUrl: String = TsaClient.DEFAULT_URL) {
        DigitalSignature.ensureProvider()
        require(src.canonicalPath != out.canonicalPath) { "addDocTimestamp: out must differ from src" }
        PDDocument.load(src).use { doc ->
            val sig = PDSignature().apply {
                setType(COSName.DOC_TIME_STAMP)
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(SUBFILTER_RFC3161)
            }
            val opts = SignatureOptions().apply { preferredSignatureSize = 24000 }
            try {
                doc.addSignature(sig, SignatureInterface { content -> TsaClient.timestamp(content.readBytes(), tsaUrl) }, opts)
                out.outputStream().use { doc.saveIncremental(it) }
            } finally { runCatching { opts.close() } }
        }
    }

    /** True when the document carries a DSS dictionary. */
    fun hasDss(doc: PDDocument): Boolean = doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("DSS")) is COSDictionary
}
