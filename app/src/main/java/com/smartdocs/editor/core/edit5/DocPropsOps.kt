package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/** Initial view: what a viewer shows when the file opens. Names are the PDF values. */
data class InitialView(
    val openPage: Int = 0,
    val openFit: DestFit = DestFit.FIT,
    val openZoom: Float = 0f,
    val pageLayout: String = "",          // "" = viewer default, SinglePage, OneColumn, TwoColumnLeft/Right, TwoPageLeft/Right
    val pageMode: String = "",            // "" / UseNone, UseOutlines, UseThumbs, FullScreen, UseOC, UseAttachments
    val direction: String = "",          // "" / L2R / R2L  (/ViewerPreferences /Direction)
    val hideToolbar: Boolean = false, val hideMenubar: Boolean = false, val hideWindowUI: Boolean = false,
    val fitWindow: Boolean = false, val centerWindow: Boolean = false, val displayDocTitle: Boolean = false,
    val printScaling: String = "",       // "" / None / AppDefault
    val duplex: String = "",             // "" / Simplex / DuplexFlipShortEdge / DuplexFlipLongEdge
    val language: String = "",          // /Lang, e.g. ar-SA
)

/** Every /Info field plus custom keys, the initial view and read-only facts. */
data class DocProps(
    val title: String = "", val author: String = "", val subject: String = "", val keywords: String = "",
    val creator: String = "", val producer: String = "",
    val created: Calendar? = null, val modified: Calendar? = null,
    val trapped: String = "",            // "" / True / False / Unknown
    val custom: List<Pair<String, String>> = emptyList(),
    val view: InitialView = InitialView(),
    // read-only
    val version: String = "", val pages: Int = 0, val encrypted: Boolean = false, val tagged: Boolean = false, val hasXmp: Boolean = false,
)

object DocPropsOps {
    private val STANDARD = setOf("Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate", "Trapped")
    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    suspend fun read(src: File, password: String?): DocProps = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            val info = doc.documentInformation
            val cat = doc.documentCatalog
            val cd = cat.cosObject
            val vp = deref(cd.getDictionaryObject(COSName.VIEWER_PREFERENCES)) as? COSDictionary
            val pageIdx = doc.pages.withIndex().associate { it.value.cosObject to it.index }
            var openPage = 0; var fit = DestFit.FIT; var zoom = 0f
            val oa = deref(cd.getDictionaryObject(COSName.OPEN_ACTION))
            val destArr = when (oa) { is COSArray -> oa; is COSDictionary -> if (oa.getNameAsString(COSName.S) == "GoTo") deref(oa.getDictionaryObject(COSName.D)) as? COSArray else null; else -> null }
            destArr?.let { a ->
                openPage = (deref(a.getObject(0)) as? COSDictionary)?.let { pageIdx[it] } ?: (a.getObject(0) as? COSInteger)?.intValue() ?: 0
                fit = when (a.getName(1)) { "XYZ" -> DestFit.XYZ; "FitH", "FitBH" -> DestFit.FITH; else -> DestFit.FIT }
                if (fit == DestFit.XYZ) zoom = (deref(a.getObject(4)) as? COSNumber)?.floatValue() ?: 0f
            }
            DocProps(
                info.title.orEmpty(), info.author.orEmpty(), info.subject.orEmpty(), info.keywords.orEmpty(), info.creator.orEmpty(), info.producer.orEmpty(),
                runCatching { info.creationDate }.getOrNull(), runCatching { info.modificationDate }.getOrNull(), info.trapped.orEmpty(),
                info.metadataKeys.filter { it !in STANDARD }.sorted().map { it to (info.getCustomMetadataValue(it) ?: "") },
                InitialView(openPage, fit, zoom, cd.getNameAsString(COSName.PAGE_LAYOUT) ?: "", cd.getNameAsString(COSName.PAGE_MODE) ?: "",
                    vp?.getNameAsString(COSName.getPDFName("Direction")) ?: "",
                    vp?.getBoolean(COSName.getPDFName("HideToolbar"), false) ?: false, vp?.getBoolean(COSName.getPDFName("HideMenubar"), false) ?: false,
                    vp?.getBoolean(COSName.getPDFName("HideWindowUI"), false) ?: false, vp?.getBoolean(COSName.getPDFName("FitWindow"), false) ?: false,
                    vp?.getBoolean(COSName.getPDFName("CenterWindow"), false) ?: false, vp?.getBoolean(COSName.getPDFName("DisplayDocTitle"), false) ?: false,
                    vp?.getNameAsString(COSName.getPDFName("PrintScaling")) ?: "", vp?.getNameAsString(COSName.getPDFName("Duplex")) ?: "", cat.language.orEmpty()),
                version = runCatching { doc.version.toString() }.getOrDefault(""), pages = doc.numberOfPages, encrypted = doc.isEncrypted,
                tagged = cat.markInfo?.isMarked == true, hasXmp = cat.metadata != null,
            )
        }
    }

    /** Write [p]. [syncXmp] rewrites the XMP packet from the /Info values (so apps that read XMP agree). */
    suspend fun write(src: File, out: File, password: String?, p: DocProps, syncXmp: Boolean = true, touchModified: Boolean = true) = withContext(Dispatchers.IO) {
        E5Io.edit(src, out, password) { doc ->
            val info = doc.documentInformation
            val d = info.cosObject
            fun put(k: String, v: String) { if (v.isBlank()) d.removeItem(COSName.getPDFName(k)) else d.setString(COSName.getPDFName(k), v) }
            put("Title", p.title); put("Author", p.author); put("Subject", p.subject); put("Keywords", p.keywords); put("Creator", p.creator); put("Producer", p.producer)
            if (p.created != null) info.creationDate = p.created
            info.modificationDate = if (touchModified) Calendar.getInstance() else p.modified
            if (p.trapped.isBlank()) d.removeItem(COSName.TRAPPED) else info.trapped = p.trapped
            // custom keys: remove the ones that disappeared, then write the list
            info.metadataKeys.filter { it !in STANDARD && p.custom.none { c -> c.first == it } }.forEach { d.removeItem(COSName.getPDFName(it)) }
            p.custom.filter { it.first.isNotBlank() && it.first !in STANDARD }.forEach { (k, v) -> d.setString(COSName.getPDFName(k.trim().replace(' ', '_')), v) }
            doc.documentInformation = info
            applyView(doc, p.view)
            if (syncXmp) doc.documentCatalog.metadata = PDMetadata(doc).also { m -> m.importXMPMetadata(xmp(p, info.creationDate, info.modificationDate).toByteArray(Charsets.UTF_8)) }
        }
    }

    private fun applyView(doc: PDDocument, v: InitialView) {
        val cd = doc.documentCatalog.cosObject
        if (doc.numberOfPages > 0) {
            val arr = COSArray().apply {
                add(doc.getPage(v.openPage.coerceIn(0, doc.numberOfPages - 1)).cosObject)
                when (v.openFit) {
                    DestFit.FIT -> add(COSName.getPDFName("Fit"))
                    DestFit.FITH -> { add(COSName.getPDFName("FitH")); add(COSNull.NULL) }
                    DestFit.XYZ -> { add(COSName.getPDFName("XYZ")); add(COSNull.NULL); add(COSNull.NULL); add(if (v.openZoom > 0f) COSFloat(v.openZoom) else COSNull.NULL) }
                }
            }
            if (v.openPage == 0 && v.openFit == DestFit.FIT && cd.getDictionaryObject(COSName.OPEN_ACTION) == null) Unit else cd.setItem(COSName.OPEN_ACTION, arr)
        }
        fun name(k: COSName, s: String) { if (s.isBlank()) cd.removeItem(k) else cd.setName(k, s) }
        name(COSName.PAGE_LAYOUT, v.pageLayout); name(COSName.PAGE_MODE, v.pageMode)
        val vp = deref(cd.getDictionaryObject(COSName.VIEWER_PREFERENCES)) as? COSDictionary ?: COSDictionary()
        fun flag(k: String, b: Boolean) { if (b) vp.setBoolean(COSName.getPDFName(k), true) else vp.removeItem(COSName.getPDFName(k)) }
        fun vname(k: String, s: String) { if (s.isBlank()) vp.removeItem(COSName.getPDFName(k)) else vp.setName(COSName.getPDFName(k), s) }
        flag("HideToolbar", v.hideToolbar); flag("HideMenubar", v.hideMenubar); flag("HideWindowUI", v.hideWindowUI)
        flag("FitWindow", v.fitWindow); flag("CenterWindow", v.centerWindow); flag("DisplayDocTitle", v.displayDocTitle)
        vname("Direction", v.direction); vname("PrintScaling", v.printScaling); vname("Duplex", v.duplex)
        if (vp.size() == 0) cd.removeItem(COSName.VIEWER_PREFERENCES) else cd.setItem(COSName.VIEWER_PREFERENCES, vp)
        if (v.language.isBlank()) cd.removeItem(COSName.LANG) else doc.documentCatalog.language = v.language.trim()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun iso(c: Calendar?): String? = c?.let {
        val tz = it.timeZone.getOffset(it.timeInMillis) / 60000
        String.format(java.util.Locale.US, "%04d-%02d-%02dT%02d:%02d:%02d%s%02d:%02d", it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH),
            it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE), it.get(Calendar.SECOND), if (tz >= 0) "+" else "-", kotlin.math.abs(tz) / 60, kotlin.math.abs(tz) % 60)
    }

    /** Minimal XMP packet (Dublin Core + PDF + XMP basic) matching the /Info dictionary. */
    fun xmp(p: DocProps, created: Calendar?, modified: Calendar?): String = buildString {
        append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append("<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">\n")
        append("<dc:format>application/pdf</dc:format>\n")
        if (p.title.isNotBlank()) append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(p.title)}</rdf:li></rdf:Alt></dc:title>\n")
        if (p.author.isNotBlank()) append("<dc:creator><rdf:Seq><rdf:li>${esc(p.author)}</rdf:li></rdf:Seq></dc:creator>\n")
        if (p.subject.isNotBlank()) append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(p.subject)}</rdf:li></rdf:Alt></dc:description>\n")
        if (p.view.language.isNotBlank()) append("<dc:language><rdf:Bag><rdf:li>${esc(p.view.language)}</rdf:li></rdf:Bag></dc:language>\n")
        if (p.keywords.isNotBlank()) append("<pdf:Keywords>${esc(p.keywords)}</pdf:Keywords>\n")
        if (p.producer.isNotBlank()) append("<pdf:Producer>${esc(p.producer)}</pdf:Producer>\n")
        if (p.creator.isNotBlank()) append("<xmp:CreatorTool>${esc(p.creator)}</xmp:CreatorTool>\n")
        iso(created)?.let { append("<xmp:CreateDate>$it</xmp:CreateDate>\n") }
        iso(modified)?.let { append("<xmp:ModifyDate>$it</xmp:ModifyDate>\n<xmp:MetadataDate>$it</xmp:MetadataDate>\n") }
        append("</rdf:Description>\n</rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>")
    }
}
