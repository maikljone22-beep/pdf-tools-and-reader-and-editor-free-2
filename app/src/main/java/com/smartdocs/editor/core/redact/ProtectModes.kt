package com.smartdocs.editor.core.redact

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.DecryptionMaterial
import com.tom_roush.pdfbox.pdmodel.encryption.PDCryptFilterDictionary
import com.tom_roush.pdfbox.pdmodel.encryption.PDEncryption
import com.tom_roush.pdfbox.pdmodel.encryption.ProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.encryption.SecurityHandler
import com.tom_roush.pdfbox.pdmodel.encryption.SecurityHandlerFactory
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.encryption.StandardSecurityHandler
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.IdentityHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What an encryption covers. [ALL]: everything (PdfBox's standard handler). [ALL_BUT_METADATA]: everything except
 * the document-level XMP metadata stream (`/EncryptMetadata false`), so search engines and catalogues can still read it.
 * [ATTACHMENTS_ONLY]: only embedded files (`/StmF /Identity /StrF /Identity /EFF /StdCF`, `/AuthEvent /EFOpen`):
 * pages, strings and metadata stay in clear, the password protects the attachments.
 */
enum class EncScope { ALL, ALL_BUT_METADATA, ATTACHMENTS_ONLY }

/** Policy for [ScopedSecurityHandler]: the usual standard policy plus the [scope] (never [EncScope.ALL]). */
class ScopedProtectionPolicy(val standard: StandardProtectionPolicy, val scope: EncScope) : ProtectionPolicy() {
    init {
        encryptionKeyLength = standard.encryptionKeyLength
        setPreferAES(standard.isPreferAES)
    }
}

/**
 * PdfBox 2.0.27's [StandardSecurityHandler] is final and always encrypts every stream and string with the
 * metadata, so this handler wraps it: the standard handler builds the /Encrypt dictionary and the file key
 * (O, U, OE, UE, Perms, ID — all its public API), then the dictionary is adjusted to the [EncScope] and only the
 * streams/strings the scope covers are encrypted ([encryptStream] / [encryptString] are public and open in
 * [SecurityHandler]; the cipher itself is the base class's, so AESV2/AESV3 output is exactly PdfBox's).
 *
 * - AES-128 (V4/R4) with `/EncryptMetadata false`: the file key and /U depend on the flag (algorithm 2 step f), and
 *   PdfBox always computes them with `true`, so both are recomputed with the public
 *   [StandardSecurityHandler.computeEncryptedKey] / [StandardSecurityHandler.computeUserPassword].
 * - AES-256 (V5/R6): the file key is random and independent of the flag; only /Perms byte 8 must be 'F', so /Perms
 *   is recomputed (AES-256-ECB of P, FFFFFFFF, 'F', "adb", 4 random bytes).
 *
 * Decryption is not handled here: the output has `/Filter /Standard`, so readers (PdfBox included) open it with
 * their normal standard handler. PdfBox does not know `/EFF`; [EmbeddedFileCrypt] decrypts attachments for it.
 */
class ScopedSecurityHandler(policy: ScopedProtectionPolicy) : SecurityHandler() {
    private val scope = policy.scope
    private val embedded: MutableSet<COSStream> = java.util.Collections.newSetFromMap(IdentityHashMap())
    /** The document-level XMP stream (catalog /Metadata): the only one /EncryptMetadata false leaves in clear (ISO 32000, qpdf). */
    private var rootMetadata: COSStream? = null

    init {
        setProtectionPolicy(policy)
        setKeyLength(policy.encryptionKeyLength)
    }

    override fun prepareDocumentForEncryption(doc: PDDocument) {
        val policy = getProtectionPolicy() as ScopedProtectionPolicy
        val std = StandardSecurityHandler(policy.standard)
        // a fresh dictionary: an /Encrypt kept from the source file could carry stale /EFF, /Perms, /OE...
        val enc = PDEncryption()
        enc.securityHandler = this
        doc.setEncryptionDictionary(enc)
        if (scope == EncScope.ALL_BUT_METADATA) enc.cosObject.setBoolean(COSName.ENCRYPT_META_DATA, false)
        std.prepareDocumentForEncryption(doc)
        val e = doc.encryption ?: throw IllegalStateException("no encryption dictionary")
        val d = e.cosObject
        val v = e.version
        if (v < 4) throw IllegalStateException("scoped encryption needs AES (V4/V5), got V$v")
        setKeyLength(std.getKeyLength())
        setAES(std.isAES())
        var key: ByteArray = std.getEncryptionKey()
        when (scope) {
            EncScope.ALL_BUT_METADATA -> {
                d.setBoolean(COSName.ENCRYPT_META_DATA, false)
                rootMetadata = doc.documentCatalog.cosObject.getDictionaryObject(COSName.METADATA) as? COSStream
                e.stdCryptFilterDictionary?.let { it.cosObject.removeItem(COSName.ENCRYPT_META_DATA) }
                if (e.revision == 4) key = recomputeR4(std, policy.standard, e, doc)
                if (e.revision == 6) e.perms = perms(key, e.permissions, encryptMetadata = false)
            }
            EncScope.ATTACHMENTS_ONLY -> {
                d.removeItem(COSName.ENCRYPT_META_DATA)
                e.streamFilterName = COSName.IDENTITY
                e.stringFilterName = COSName.IDENTITY
                d.setItem(K_EFF, COSName.STD_CF)
                e.stdCryptFilterDictionary?.cosObject?.setItem(K_AUTH_EVENT, K_EF_OPEN)
                collectEmbeddedFiles(doc)
            }
            EncScope.ALL -> {}
        }
        setEncryptionKey(key)
        e.securityHandler = this
        doc.setEncryptionDictionary(e)
    }

    override fun prepareForDecryption(encryption: PDEncryption?, documentIDArray: COSArray?, decryptionMaterial: DecryptionMaterial?) {
        throw UnsupportedOperationException("ScopedSecurityHandler only encrypts; files open with /Standard")
    }

    override fun encryptStream(stream: COSStream, objNum: Long, genNum: Int) {
        val type = stream.getCOSName(COSName.TYPE)
        val doIt = when (scope) {
            EncScope.ALL -> true
            EncScope.ALL_BUT_METADATA -> stream !== rootMetadata
            EncScope.ATTACHMENTS_ONLY -> type == K_EMBEDDED_FILE || stream in embedded
        }
        if (doIt) super.encryptStream(stream, objNum, genNum)
    }

    override fun encryptString(string: COSString, objNum: Long, genNum: Int) {
        if (scope != EncScope.ATTACHMENTS_ONLY) super.encryptString(string, objNum, genNum)
    }

    /** Every embedded-file stream (/EF of any file specification reachable from the trailer); marks it /Type /EmbeddedFile. */
    private fun collectEmbeddedFiles(doc: PDDocument) {
        embedded.clear()
        val seen: MutableSet<COSBase> = java.util.Collections.newSetFromMap(IdentityHashMap())
        val stack = ArrayDeque<COSBase>()
        stack.addLast(doc.document.trailer)
        while (stack.isNotEmpty()) {
            var b = stack.removeLast()
            if (b is COSObject) b = b.getObject() ?: continue
            if (!seen.add(b)) continue
            when (b) {
                is COSDictionary -> {
                    (b.getDictionaryObject(COSName.EF) as? COSDictionary)?.let { ef ->
                        for (k in ef.keySet()) (ef.getDictionaryObject(k) as? COSStream)?.let { s ->
                            embedded += s
                            if (s.getCOSName(COSName.TYPE) == null) s.setItem(COSName.TYPE, K_EMBEDDED_FILE)
                        }
                    }
                    for (k in b.keySet()) if (k != COSName.PARENT && k != COSName.P) b.getItem(k)?.let { stack.addLast(it) }
                }
                is COSArray -> for (i in 0 until b.size()) b.get(i)?.let { stack.addLast(it) }
                else -> {}
            }
        }
    }

    companion object {
        val K_EFF: COSName = COSName.getPDFName("EFF")
        val K_AUTH_EVENT: COSName = COSName.getPDFName("AuthEvent")
        val K_EF_OPEN: COSName = COSName.getPDFName("EFOpen")
        val K_EMBEDDED_FILE: COSName = COSName.getPDFName("EmbeddedFile")
        private const val NAME = "SDScopedStandard"
        @Volatile private var registered = false

        /** Registers the handler for [ScopedProtectionPolicy] once (the factory refuses a name twice). */
        fun register() {
            if (registered) return
            synchronized(this) {
                if (registered) return
                runCatching { SecurityHandlerFactory.INSTANCE.registerHandler(NAME, ScopedSecurityHandler::class.java, ScopedProtectionPolicy::class.java) }
                registered = true
            }
        }

        /** R4 with /EncryptMetadata false: file key and /U from the public algorithms with encryptMetadata = false. */
        private fun recomputeR4(std: StandardSecurityHandler, p: StandardProtectionPolicy, e: PDEncryption, doc: PDDocument): ByteArray {
            val id = (doc.document.documentID?.getObject(0) as? COSString)?.bytes ?: ByteArray(0)
            val user = p.userPassword.orEmpty().toByteArray(Charsets.ISO_8859_1)
            val len = e.length / 8
            val o = e.ownerKey
            val u = std.computeUserPassword(user, o, e.permissions, id, 4, len, false)
            e.userKey = u
            return std.computeEncryptedKey(user, o, u, null, null, e.permissions, id, 4, len, false, false)
        }

        /** /Perms of R6 (ISO 32000-2 algorithm 10). */
        fun perms(fileKey: ByteArray, p: Int, encryptMetadata: Boolean, random: SecureRandom = SecureRandom()): ByteArray {
            val b = ByteArray(16)
            for (i in 0 until 4) b[i] = (p ushr (8 * i)).toByte()
            for (i in 4 until 8) b[i] = 0xFF.toByte()
            b[8] = (if (encryptMetadata) 'T' else 'F').code.toByte()
            b[9] = 'a'.code.toByte(); b[10] = 'd'.code.toByte(); b[11] = 'b'.code.toByte()
            val r = ByteArray(4).also { random.nextBytes(it) }
            System.arraycopy(r, 0, b, 12, 4)
            val c = Cipher.getInstance("AES/ECB/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"))
            return c.doFinal(b)
        }

        /** Decrypts /Perms: the 16 plain bytes (bytes 9..11 are "adb" when the key is right). */
        fun decryptPerms(fileKey: ByteArray, perms: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/ECB/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey, "AES"))
            return c.doFinal(perms)
        }
    }
}

/**
 * Attachments of a file encrypted with `/EFF` while streams are `/Identity` ([EncScope.ATTACHMENTS_ONLY], or the
 * same option of Acrobat): PdfBox 2.0 ignores /EFF and returns the encrypted bytes, so they are decrypted here
 * with the file key of the opened document (AESV2: per-object MD5 key; AESV3: the file key).
 */
object EmbeddedFileCrypt {

    /** True when embedded files use a crypt filter other than the stream filter (PdfBox left them encrypted). */
    fun needsManualDecrypt(doc: PDDocument): Boolean {
        val e = doc.encryption ?: return false
        if (e.version < 4) return false
        val eff = e.cosObject.getCOSName(ScopedSecurityHandler.K_EFF) ?: return false
        val stm = e.streamFilterName ?: COSName.IDENTITY
        return eff != stm && stm == COSName.IDENTITY && eff != COSName.IDENTITY
    }

    /** Decoded bytes of the embedded-file [stream] of [doc]. */
    fun bytes(doc: PDDocument, stream: COSStream): ByteArray {
        if (!needsManualDecrypt(doc)) return stream.createInputStream().use { it.readBytes() }
        val e = doc.encryption!!
        val key = e.securityHandler.getEncryptionKey() ?: throw IllegalStateException("document not decrypted")
        val eff = e.cosObject.getCOSName(ScopedSecurityHandler.K_EFF)!!
        val cf: PDCryptFilterDictionary? = e.getCryptFilterDictionary(eff)
        val method = cf?.cryptFilterMethod
        val raw = stream.createRawInputStream().use { it.readBytes() }
        val plain = when (method) {
            COSName.AESV3 -> aesCbc(key, raw)
            COSName.AESV2 -> {
                val ref = objectKey(doc, stream) ?: throw IllegalStateException("embedded file is not an indirect object")
                aesCbc(objectAesKey(key, ref.first, ref.second), raw)
            }
            COSName.NONE, null -> raw
            else -> throw UnsupportedOperationException("crypt filter method $method")
        }
        val tmp = COSStream()
        for ((k, v) in stream.entrySet()) if (k != COSName.LENGTH) tmp.setItem(k, v)
        tmp.createRawOutputStream().use { it.write(plain) }
        return tmp.createInputStream().use { it.readBytes() }
    }

    /** AESV2 object key: MD5(fileKey + objNum (3 bytes LE) + gen (2 bytes LE) + "sAlT"), at most 16 bytes. */
    fun objectAesKey(fileKey: ByteArray, objNum: Long, gen: Int): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(fileKey)
        md.update(byteArrayOf(objNum.toByte(), (objNum shr 8).toByte(), (objNum shr 16).toByte(), gen.toByte(), (gen shr 8).toByte()))
        md.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54))
        return md.digest().copyOf(minOf(fileKey.size + 5, 16))
    }

    fun aesCbc(key: ByteArray, data: ByteArray): ByteArray {
        if (data.size < 16) return ByteArray(0)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(data, 0, 16))
        return c.doFinal(data, 16, data.size - 16)
    }

    private fun objectKey(doc: PDDocument, s: COSStream): Pair<Long, Int>? =
        doc.document.objects.firstOrNull { it.getObject() === s }?.let { it.objectNumber to it.generationNumber }
}
