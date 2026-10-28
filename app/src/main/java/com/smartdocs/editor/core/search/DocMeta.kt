package com.smartdocs.editor.core.search

import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * fc-sync (Foxit §37 index preferences): document metadata as searchable [PartKind.METADATA] parts.
 *  - always: the title (its own part, page [TITLE_PAGE], so results can show it) and author / subject / keywords;
 *  - [Options.custom]: custom /Info properties ("Project: Apollo");
 *  - [Options.xmp]: the text of the XMP packet (tags removed);
 *  - [Options.tags]: structure tags' alternate text, actual text, titles and expansions.
 * The string helpers are pure and unit-tested (DocMetaTest).
 */
object DocMeta {
    /** Page number stored for the title part (other metadata parts use -1, like attachments). */
    const val TITLE_PAGE = -2
    private const val MAX_XMP = 20_000
    private const val MAX_TAGS = 5_000

    data class Options(val xmp: Boolean = false, val custom: Boolean = false, val tags: Boolean = false)

    private val STANDARD = setOf("Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate", "Trapped")

    suspend fun load(file: File, o: Options): List<DocPart> = withContext(Dispatchers.IO) {
        if (!file.extension.equals("pdf", true)) return@withContext emptyList()
        runCatching { PdfOps.load(file, null).use { collect(it, o) } }.getOrDefault(emptyList())
    }

    fun collect(doc: PDDocument, o: Options): List<DocPart> {
        val out = ArrayList<DocPart>()
        val info = runCatching { doc.documentInformation }.getOrNull()
        val xml = runCatching { doc.documentCatalog.metadata?.exportXMPMetadata()?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
        val title = info?.title?.takeIf { it.isNotBlank() } ?: xml?.let(::xmpTitle)
        title?.let { out += DocPart(PartKind.METADATA, TITLE_PAGE, it.trim()) }
        val basic = listOfNotNull(info?.author, info?.subject, info?.keywords).filter { it.isNotBlank() }.joinToString(" — ")
        if (basic.isNotBlank()) out += DocPart(PartKind.METADATA, -1, basic)
        if (o.custom && info != null) {
            val props = runCatching { info.metadataKeys.associateWith { info.getCustomMetadataValue(it).orEmpty() } }.getOrDefault(emptyMap())
            customText(props)?.let { out += DocPart(PartKind.METADATA, -1, it) }
        }
        if (o.xmp && xml != null) xmpText(xml).takeIf { it.isNotBlank() }?.let { out += DocPart(PartKind.METADATA, -1, it.take(MAX_XMP)) }
        if (o.tags) runCatching {
            val root = doc.documentCatalog.structureTreeRoot?.cosObject
            if (root != null) {
                val texts = ArrayList<String>()
                walkTags(root.getDictionaryObject(COSName.K), texts, 0)
                if (texts.isNotEmpty()) out += DocPart(PartKind.METADATA, -1, texts.joinToString(" — "))
            }
        }
        return out
    }

    private fun walkTags(node: COSBase?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 60 || out.size >= MAX_TAGS) return
        val n = if (node is COSObject) node.getObject() else node
        when (n) {
            is COSArray -> for (i in 0 until n.size()) walkTags(n.get(i), out, depth + 1)
            is COSDictionary -> {
                for (k in listOf("Alt", "ActualText", "T", "E")) (n.getDictionaryObject(COSName.getPDFName(k)) as? COSString)?.string?.takeIf { it.isNotBlank() }?.let { out += it.trim() }
                walkTags(n.getDictionaryObject(COSName.K), out, depth + 1)
            }
            else -> Unit
        }
    }

    // ---- pure ----

    /** "Key: value" lines of the non-standard /Info entries, or null when there are none. */
    fun customText(props: Map<String, String>): String? =
        props.filterKeys { it !in STANDARD }.filterValues { it.isNotBlank() }.entries.sortedBy { it.key }
            .joinToString(" — ") { "${it.key}: ${it.value.trim()}" }.ifBlank { null }

    /** Text content of an XMP packet: elements and processing instructions removed, entities decoded, spaces collapsed. */
    fun xmpText(xml: String): String = xml
        .replace(Regex("""<\?[^>]*\?>"""), " ")
        .replace(Regex("""<[^>]+>"""), " ")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("""\s+"""), " ").trim()

    /** dc:title of an XMP packet (the first rdf:li), when present. */
    fun xmpTitle(xml: String): String? {
        val block = Regex("""<dc:title>(.*?)</dc:title>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1) ?: return null
        val li = Regex("""<rdf:li[^>]*>(.*?)</rdf:li>""", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1) ?: block
        return xmpText(li).ifBlank { null }
    }
}
