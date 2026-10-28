package com.smartdocs.editor.core.tools5

import android.content.Context
import android.graphics.Bitmap
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai6.AiImage
import com.smartdocs.editor.core.ai6.AiMedia
import com.smartdocs.editor.core.ai6.MediaMessage
import com.smartdocs.editor.core.data.AppSettings
import com.smartdocs.editor.core.data.SettingsStore
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Foxit 34 / Nitro 19: "Mark as PDF/UA" — the pdfuaid:part in the XMP (added to the existing packet, or a small packet
 * of its own), /MarkInfo /Marked, /ViewerPreferences /DisplayDocTitle. The XMP string edits are pure (JVM-tested).
 */
object AccessUa {
    private const val NS = "xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\""

    /** [xmp] with pdfuaid:part = [part]; null when there is no rdf:Description to extend. Pure. */
    fun insertUa(xmp: String, part: Int = 1): String? {
        if (xmp.contains("pdfuaid:part")) return xmp.replace(Regex("<pdfuaid:part>\\s*\\d+\\s*</pdfuaid:part>"), "<pdfuaid:part>$part</pdfuaid:part>")
            .replace(Regex("pdfuaid:part=\"\\d+\""), "pdfuaid:part=\"$part\"")
        val i = xmp.indexOf("<rdf:Description"); if (i < 0) return null
        val close = xmp.indexOf('>', i); if (close < 0) return null
        val selfClosing = xmp[close - 1] == '/'
        val head = xmp.substring(0, if (selfClosing) close - 1 else close).let { if (it.contains("xmlns:pdfuaid")) it else "$it $NS" }
        return if (selfClosing) "$head><pdfuaid:part>$part</pdfuaid:part></rdf:Description>" + xmp.substring(close + 1)
        else "$head><pdfuaid:part>$part</pdfuaid:part>" + xmp.substring(close + 1)
    }

    /** A minimal packet: title and the PDF/UA part. Pure. */
    fun packet(title: String?, part: Int = 1): String = buildString {
        append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
        append("<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ").append(NS).append('>')
        if (!title.isNullOrBlank()) append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(XmpWriter.esc(title)).append("</rdf:li></rdf:Alt></dc:title>")
        append("<pdfuaid:part>").append(part).append("</pdfuaid:part></rdf:Description></rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>")
    }

    /** Reads the document's XMP as text (null when none). */
    fun xmpOf(doc: PDDocument): String? = runCatching { doc.documentCatalog.metadata?.exportXMPMetadata()?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()

    fun isUa(doc: PDDocument): Boolean = xmpOf(doc)?.contains("pdfuaid:part") == true

    fun markUa(doc: PDDocument, part: Int = 1) {
        val cat = doc.documentCatalog
        val title = doc.documentInformation?.title
        val xmp = xmpOf(doc)?.let { insertUa(it, part) } ?: packet(title, part)
        val md = PDMetadata(doc); md.importXMPMetadata(xmp.toByteArray(Charsets.UTF_8)); cat.metadata = md
        cat.cosObject.setItem(COSName.getPDFName("MarkInfo"), COSDictionary().apply { setItem(COSName.getPDFName("Marked"), COSBoolean.TRUE) })
        val vp = (cat.cosObject.getDictionaryObject(COSName.VIEWER_PREFERENCES) as? COSDictionary) ?: COSDictionary().also { cat.cosObject.setItem(COSName.VIEWER_PREFERENCES, it) }
        vp.setBoolean(COSName.getPDFName("DisplayDocTitle"), true)
    }
}

/**
 * Pdf-ad note (Foxit 34): the AI alt text must see the figure. Installs [T5Hooks.describeImage] with the vision route
 * of the user's AI endpoint (or the proxy); [AltTextAi] keeps the page-text prompt as the fallback.
 */
object AltTextVision {
    @Volatile private var settings: AppSettings? = null

    fun install(ctx: Context, store: SettingsStore) {
        CoroutineScope(Dispatchers.Default).launch { runCatching { store.settings.collect { settings = it } } }
        T5Hooks.describeImage = hook@{ _, image, arabic ->
            val s = settings ?: throw IllegalStateException("settings")
            val prompt = if (arabic) "اكتب نصًا بديلًا (Alt text) قصيرًا ودقيقًا لهذا الشكل في المستند، جملة واحدة لا تتجاوز 120 حرفًا. لا تبدأ بعبارة «صورة لـ»."
                else "Write one short, precise alt text (max 120 characters) for this figure in a document. Do not start with \"Image of\"."
            val jpeg = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            AiMedia.visionChat(AiClient(s), listOf(MediaMessage("user", prompt, listOf(AiImage(jpeg)))), maxTokens = 200).trim().trim('"')
        }
    }
}
