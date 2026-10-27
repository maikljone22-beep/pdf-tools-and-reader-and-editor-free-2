package com.smartdocs.editor.core.sign

import com.smartdocs.editor.core.pdf.DigitalSignature
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.DecryptionMaterial
import com.tom_roush.pdfbox.pdmodel.encryption.PDCryptFilterDictionary
import com.tom_roush.pdfbox.pdmodel.encryption.PDEncryption
import com.tom_roush.pdfbox.pdmodel.encryption.ProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.encryption.SecurityHandler
import com.tom_roush.pdfbox.pdmodel.encryption.SecurityHandlerFactory
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSAlgorithm
import org.bouncycastle.cms.CMSEnvelopedData
import org.bouncycastle.cms.CMSEnvelopedDataGenerator
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder
import org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate

/**
 * Certificate security beyond PdfBox's own public-key handler (nitro-protect2, row 4):
 * - encryption with `/EncryptMetadata false` ([PubSecMetaHandler]): PdfBox 2.0.27 can read such files (its
 *   decryption appends 0xFFFFFFFF to the key digest and skips /Metadata streams) but cannot write them, so the
 *   recipients, file key and dictionary are built here the same way (ISO 32000-1 7.6.5.3);
 * - decryption with any digital ID ([open]), including Android KeyChain keys that cannot be exported to a PKCS#12
 *   file: PdfBox only accepts a PKCS#12 stream, so the document is opened through [IdentityPubSecHandler].
 */
object PubSecCrypto {
    const val FILTER = "Adobe.PubSec"
    /** Same length as [FILTER]: the temporary copy opened with [IdentityPubSecHandler] names it instead. */
    internal const val ALIAS_FILTER = "SDIdentityPS"
    private val NEEDLE = "/$FILTER".toByteArray(Charsets.ISO_8859_1)
    private val ALIAS = "/$ALIAS_FILTER".toByteArray(Charsets.ISO_8859_1)

    internal val current = ThreadLocal<DigitalSignature.Identity?>()
    @Volatile private var registered = false

    fun register() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            runCatching { SecurityHandlerFactory.INSTANCE.registerHandler(ALIAS_FILTER, IdentityPubSecHandler::class.java, IdentityPolicy::class.java) }
            runCatching { SecurityHandlerFactory.INSTANCE.registerHandler(MetaHandlerName, PubSecMetaHandler::class.java, PubSecMetaPolicy::class.java) }
            registered = true
        }
    }
    private const val MetaHandlerName = "SDPubSecNoMeta"

    /**
     * Runs [block] on [file] (certificate encrypted) decrypted with [identity]'s private key. The document reads from
     * a temporary copy whose /Filter names [IdentityPubSecHandler]; the copy is deleted afterwards.
     */
    fun <T> withDocument(file: File, identity: DigitalSignature.Identity, block: (PDDocument) -> T): T {
        DigitalSignature.ensureProvider()
        register()
        val tmp = File.createTempFile("pubsec", ".pdf")
        try {
            file.copyTo(tmp, overwrite = true)
            if (patch(tmp) == 0) throw IOException("not a certificate-encrypted PDF")
            val doc = synchronized(current) {
                current.set(identity)
                try { PDDocument.load(tmp, "", MemoryUsageSetting.setupMixed(48L shl 20)) } finally { current.remove() }
            }
            return doc.use(block)
        } finally { tmp.delete() }
    }

    /** Replaces every "/Adobe.PubSec" by the alias name (same length, so offsets stay valid). Returns the count. */
    internal fun patch(f: File): Int {
        var n = 0
        RandomAccessFile(f, "rw").use { raf ->
            val len = raf.length(); val buf = ByteArray(1 shl 16); var pos = 0L
            while (pos < len) {
                raf.seek(pos)
                val read = raf.read(buf).takeIf { it > 0 } ?: break
                var i = 0
                while (i <= read - NEEDLE.size) {
                    if (matches(buf, i)) { raf.seek(pos + i); raf.write(ALIAS); n++; i += NEEDLE.size } else i++
                }
                if (pos + read >= len) break
                pos += (read - NEEDLE.size + 1).coerceAtLeast(1)
            }
        }
        return n
    }

    private fun matches(b: ByteArray, at: Int): Boolean { for (j in NEEDLE.indices) if (b[at + j] != NEEDLE[j]) return false; return true }

    /** One recipient's PKCS#7 envelope of the 20-byte seed + 4 permission bytes (big endian), AES-256-CBC content. */
    fun envelope(seed: ByteArray, permissions: Int, cert: X509Certificate): ByteArray {
        val content = ByteArray(24)
        System.arraycopy(seed, 0, content, 0, 20)
        content[20] = (permissions ushr 24).toByte(); content[21] = (permissions ushr 16).toByte()
        content[22] = (permissions ushr 8).toByte(); content[23] = permissions.toByte()
        val bc = Security.getProvider("BC")
        val gen = CMSEnvelopedDataGenerator()
        gen.addRecipientInfoGenerator(JceKeyTransRecipientInfoGenerator(cert).let { if (bc != null) it.setProvider(bc) else it })
        val enc = JceCMSContentEncryptorBuilder(CMSAlgorithm.AES256_CBC).let { if (bc != null) it.setProvider(bc) else it }.build()
        return gen.generate(CMSProcessableByteArray(content), enc).toASN1Structure().getEncoded(ASN1Encoding.DER)
    }

    /** File key of the public-key handler: SHA-1 (V4) or SHA-256 (V5) of seed + recipients [+ FFFFFFFF], truncated. */
    fun fileKey(seed: ByteArray, recipients: List<ByteArray>, version: Int, keyBits: Int, encryptMetadata: Boolean): ByteArray {
        val md = MessageDigest.getInstance(if (version == 5) "SHA-256" else "SHA-1")
        md.update(seed); recipients.forEach { md.update(it) }
        if (version >= 4 && !encryptMetadata) md.update(byteArrayOf(-1, -1, -1, -1))
        return md.digest().copyOf(keyBits / 8)
    }
}

/** Marker policy of [IdentityPubSecHandler] (the factory needs one per handler; never used to encrypt). */
class IdentityPolicy : ProtectionPolicy()

/** Recipients + permissions of a certificate encryption that leaves the metadata in clear. */
class PubSecMetaPolicy(val recipients: List<Pair<X509Certificate, AccessPermission>>, keyBits: Int) : ProtectionPolicy() {
    init { encryptionKeyLength = keyBits; setPreferAES(true) }
}

/** Writes Adobe.PubSec (adbe.pkcs7.s5, AESV2 / AESV3) with `/EncryptMetadata false`; /Metadata streams stay in clear. */
class PubSecMetaHandler(policy: PubSecMetaPolicy) : SecurityHandler() {
    init { setProtectionPolicy(policy); setKeyLength(policy.encryptionKeyLength) }

    override fun prepareDocumentForEncryption(doc: PDDocument) {
        val policy = getProtectionPolicy() as PubSecMetaPolicy
        val bits = if (getKeyLength() >= 256) 256 else 128
        val v = if (bits == 256) 5 else 4
        val seed = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val recipients = policy.recipients.map { (cert, perm) -> PubSecCrypto.envelope(seed, perm.permissionBytesForPublicKey, cert) }
        val enc = PDEncryption()
        enc.filter = PubSecCrypto.FILTER
        enc.subFilter = "adbe.pkcs7.s5"
        enc.length = bits
        enc.version = v
        val cf = PDCryptFilterDictionary()
        cf.cryptFilterMethod = if (v == 5) COSName.AESV3 else COSName.AESV2
        cf.length = bits
        val arr = COSArray(); recipients.forEach { arr.add(COSString(it)) }; arr.isDirect = true
        cf.cosObject.setItem(COSName.RECIPIENTS, arr)
        cf.cosObject.setBoolean(COSName.ENCRYPT_META_DATA, false)
        cf.cosObject.isDirect = true
        enc.defaultCryptFilterDictionary = cf
        enc.streamFilterName = COSName.DEFAULT_CRYPT_FILTER
        enc.stringFilterName = COSName.DEFAULT_CRYPT_FILTER
        enc.cosObject.setBoolean(COSName.ENCRYPT_META_DATA, false)
        setKeyLength(bits); setAES(true)
        setEncryptionKey(PubSecCrypto.fileKey(seed, recipients, v, bits, encryptMetadata = false))
        enc.securityHandler = this
        doc.setEncryptionDictionary(enc)
        doc.document.setEncryptionDictionary(enc.cosObject)
    }

    override fun prepareForDecryption(encryption: PDEncryption?, documentIDArray: COSArray?, decryptionMaterial: DecryptionMaterial?) {
        throw UnsupportedOperationException("PubSecMetaHandler only encrypts; files open with Adobe.PubSec")
    }

    /** /EncryptMetadata false: metadata streams are written in clear (PdfBox's decryption skips every /Metadata stream). */
    override fun encryptStream(stream: COSStream, objNum: Long, genNum: Int) {
        if (stream.getCOSName(COSName.TYPE) == COSName.METADATA) return
        super.encryptStream(stream, objNum, genNum)
    }
}

/**
 * Public-key decryption with a [DigitalSignature.Identity] held in [PubSecCrypto.current] (file ID or KeyChain).
 * Mirrors PdfBox's PublicKeySecurityHandler, except that the private key comes straight from the identity and the
 * crypt filter's /Length is accepted in bytes too.
 */
class IdentityPubSecHandler : SecurityHandler() {
    override fun prepareDocumentForEncryption(doc: PDDocument) { throw UnsupportedOperationException("decryption only") }

    override fun prepareForDecryption(encryption: PDEncryption, documentIDArray: COSArray?, decryptionMaterial: DecryptionMaterial?) {
        val id = PubSecCrypto.current.get() ?: throw IOException("no digital ID for a certificate-encrypted document")
        val cf = encryption.defaultCryptFilterDictionary
        val v = encryption.version
        var bits = when {
            cf != null && cf.length != 0 -> { setDecryptMetadata(cf.isEncryptMetaData && encryption.isEncryptMetaData); cf.length }
            else -> { setDecryptMetadata(encryption.isEncryptMetaData); encryption.length }
        }
        if (bits in 1..32) bits *= 8
        if (v == 5) bits = 256 else if (v == 4) bits = 128 else if (bits == 0) bits = 40
        setKeyLength(bits)
        val array = encryption.cosObject.getCOSArray(COSName.RECIPIENTS) ?: cf?.cosObject?.getCOSArray(COSName.RECIPIENTS)
            ?: throw IOException("/Recipients entry is missing in encryption dictionary")
        val holder = X509CertificateHolder(id.certificate.encoded)
        val fields = ArrayList<ByteArray>()
        var content: ByteArray? = null
        for (i in 0 until array.size()) {
            val bytes = (array.getObject(i) as COSString).bytes
            fields += bytes
            if (content != null) continue
            val ri = CMSEnvelopedData(bytes).recipientInfos.recipients.firstOrNull { it.rid.match(holder) } ?: continue
            content = ri.getContent(JceKeyTransEnvelopedRecipient(id.privateKey))
        }
        val env = content ?: throw IOException("the digital ID matches none of ${array.size()} recipients")
        if (env.size != 24) throw IOException("the enveloped data does not contain 24 bytes")
        setCurrentAccessPermission(AccessPermission(env.copyOfRange(20, 24)).apply { setReadOnly() })
        if (v == 4 || v == 5) setAES(cf?.cryptFilterMethod.let { it == COSName.AESV2 || it == COSName.AESV3 })
        setEncryptionKey(PubSecCrypto.fileKey(env.copyOf(20), fields, v, bits, isDecryptMetadata || v < 4))
    }
}
