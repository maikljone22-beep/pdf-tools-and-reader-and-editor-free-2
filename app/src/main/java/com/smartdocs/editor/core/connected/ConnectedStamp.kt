package com.smartdocs.editor.core.connected

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File
import java.security.SecureRandom

/** Writes the connected-document id ([ConnMarker]) into a copy of a PDF; optionally protects it with the server key. */
object ConnectedStamp {
    class EncryptedSource : IllegalStateException("source PDF is password protected")

    /**
     * [key] non-null = protected mode: AES-256 with [key] as the user password (readers get it from the server or the
     * offline cache), random owner password, printing/copying allowed.
     */
    fun stamp(src: File, out: File, docId: String, key: String?, srcPassword: String? = null) {
        require(ConnMarker.isId(docId)) { "bad id" }
        val doc = if (srcPassword.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, srcPassword)
        doc.use { d ->
            if (d.isEncrypted) { if (srcPassword.isNullOrEmpty()) throw EncryptedSource(); d.isAllSecurityToBeRemoved = true }
            val trailer = d.document.trailer
            trailer.setName(COSName.getPDFName(ConnMarker.TRAILER_KEY), docId)
            if (key != null) trailer.setName(COSName.getPDFName(ConnMarker.TRAILER_PROT), "1") else trailer.removeItem(COSName.getPDFName(ConnMarker.TRAILER_PROT))
            d.documentInformation.setCustomMetadataValue(ConnMarker.INFO_KEY, docId)
            writeXmp(d, docId)
            if (key != null) {
                val ownerPw = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val policy = StandardProtectionPolicy(ownerPw, key, AccessPermission())
                policy.encryptionKeyLength = 256
                d.protect(policy)
            }
            val tmp = File(out.parentFile, ".${out.name}.part")
            d.save(tmp)
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            ConnMarker.invalidate(out)
        }
    }

    private fun writeXmp(d: PDDocument, id: String) {
        val desc = """<rdf:Description rdf:about="" xmlns:sdc="${ConnMarker.XMP_NS}"><sdc:DocumentId>$id</sdc:DocumentId></rdf:Description>"""
        val existing = runCatching { d.documentCatalog.metadata?.exportXMPMetadata()?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
        val xml = if (existing != null && existing.contains("</rdf:RDF>")) {
            existing.replace(Regex("""<rdf:Description[^>]*xmlns:sdc="[^"]*"[^>]*>.*?</rdf:Description>""", RegexOption.DOT_MATCHES_ALL), "")
                .replace("</rdf:RDF>", "$desc</rdf:RDF>")
        } else {
            "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">$desc</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>"
        }
        val md = PDMetadata(d)
        md.importXMPMetadata(xml.toByteArray(Charsets.UTF_8))
        d.documentCatalog.metadata = md
    }

    fun newKey(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
