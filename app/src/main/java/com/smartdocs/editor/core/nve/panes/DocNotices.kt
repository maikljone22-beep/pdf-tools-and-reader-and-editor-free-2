package com.smartdocs.editor.core.nve.panes

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDTrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2

/** Kinds of document notification banners shown above the pages. */
enum class DocNotice { FORMS, JAVASCRIPT, FONT_RESTRICTED, PDFA, PDFUA, PENDING_REDACTIONS, THREE_D, PORTFOLIO }

/** One detected notice: [count] items (fields, scripts, fonts, marks, 3D annots, files); [detail] e.g. "PDF/A-2b". */
data class NoticeHit(val kind: DocNotice, val count: Int, val detail: String = "")

/**
 * Detects what the document notifications bar reports (Nitro / Foxit "this document contains …"): form fields,
 * JavaScript, fonts whose licence restricts embedding / editing, a PDF/A or PDF/UA claim in the XMP, unapplied
 * redaction marks, 3D / RichMedia annotations and PDF portfolios. Pure PDFBox, scans at most [maxPages] pages.
 */
object DocNotices {
    private val S = COSName.getPDFName("S")
    private val JS_ACTION = COSName.getPDFName("JavaScript")

    fun detect(doc: PDDocument, maxPages: Int = 60): List<NoticeHit> {
        val out = ArrayList<NoticeHit>()
        val cat = doc.documentCatalog
        // ---- forms
        val fieldCount = runCatching { cat.acroForm?.fieldTree?.count { it !is com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField } ?: 0 }.getOrDefault(0)
        if (fieldCount > 0) out += NoticeHit(DocNotice.FORMS, fieldCount)
        // ---- JavaScript
        var js = 0
        val names = cat.cosObject.getCOSDictionary(COSName.NAMES)
        names?.getCOSDictionary(COSName.JAVA_SCRIPT)?.let { js += countNameTree(it, 0) }
        if (isJs(cat.cosObject.getDictionaryObject(COSName.getPDFName("OpenAction")))) js++
        js += countAa(cat.cosObject.getCOSDictionary(COSName.AA))
        runCatching { cat.acroForm?.fieldTree?.forEach { f -> js += countAa(f.cosObject.getCOSDictionary(COSName.AA)) } }
        // ---- per page: annotations, fonts
        var redact = 0; var threeD = 0
        val restricted = HashSet<String>()
        doc.pages.forEachIndexed { i, p ->
            if (i >= maxPages) return@forEachIndexed
            js += countAa(p.cosObject.getCOSDictionary(COSName.AA))
            val annots = p.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray
            if (annots != null) for (k in 0 until annots.size()) {
                val a = deref(annots.get(k)) as? COSDictionary ?: continue
                when (a.getNameAsString(COSName.SUBTYPE)) {
                    "Redact" -> redact++
                    "3D", "RichMedia" -> threeD++
                }
                if (isJs(a.getDictionaryObject(COSName.A))) js++
            }
            runCatching {
                val res = p.resources ?: return@runCatching
                for (n in res.fontNames) {
                    val f = runCatching { res.getFont(n) }.getOrNull() ?: continue
                    if (isRestricted(f)) restricted += f.name ?: n.name
                }
            }
        }
        if (js > 0) out += NoticeHit(DocNotice.JAVASCRIPT, js)
        if (restricted.isNotEmpty()) out += NoticeHit(DocNotice.FONT_RESTRICTED, restricted.size, restricted.take(3).joinToString(", "))
        // ---- XMP claims
        val xmp = runCatching { cat.metadata?.exportXMPMetadata()?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: ""
        pdfaClaim(xmp)?.let { out += NoticeHit(DocNotice.PDFA, 1, it) }
        pdfuaClaim(xmp)?.let { out += NoticeHit(DocNotice.PDFUA, 1, it) }
        if (redact > 0) out += NoticeHit(DocNotice.PENDING_REDACTIONS, redact)
        if (threeD > 0) out += NoticeHit(DocNotice.THREE_D, threeD)
        if (cat.cosObject.getDictionaryObject(COSName.getPDFName("Collection")) != null) {
            val files = names?.getCOSDictionary(COSName.getPDFName("EmbeddedFiles"))?.let { countNameTree(it, 0) } ?: 0
            out += NoticeHit(DocNotice.PORTFOLIO, files)
        }
        return out
    }

    /** "PDF/A-2b" from pdfaid:part / pdfaid:conformance (element or attribute form), or null. */
    fun pdfaClaim(xmp: String): String? {
        val part = xmpValue(xmp, "pdfaid:part") ?: return null
        val conf = xmpValue(xmp, "pdfaid:conformance") ?: ""
        return "PDF/A-$part${conf.lowercase()}"
    }

    fun pdfuaClaim(xmp: String): String? = xmpValue(xmp, "pdfuaid:part")?.let { "PDF/UA-$it" }

    private fun xmpValue(xmp: String, key: String): String? {
        Regex("$key\\s*=\\s*[\"']([^\"']+)[\"']").find(xmp)?.let { return it.groupValues[1].trim() }
        Regex("<$key>\\s*([^<]+?)\\s*</$key>").find(xmp)?.let { return it.groupValues[1].trim() }
        return null
    }

    /** Embedding licence of a TrueType-based font forbids editing: restricted (bit 1) or preview & print only. */
    fun isRestricted(f: PDFont): Boolean {
        if (!f.isEmbedded) return false
        val ttf = when (f) {
            is PDTrueTypeFont -> runCatching { f.trueTypeFont }.getOrNull()
            is PDType0Font -> (f.descendantFont as? PDCIDFontType2)?.let { runCatching { it.trueTypeFont }.getOrNull() }
            else -> null
        } ?: return false
        val fs = runCatching { ttf.oS2Windows?.fsType?.toInt() }.getOrNull() ?: return false
        return fsTypeRestricts(fs)
    }

    /** OS/2 fsType: 0x0002 restricted licence; 0x0004 preview & print without 0x0008 editable. */
    fun fsTypeRestricts(fs: Int): Boolean = (fs and 0x0002) != 0 || ((fs and 0x0004) != 0 && (fs and 0x0008) == 0)

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    private fun isJs(b: COSBase?): Boolean {
        val d = deref(b) as? COSDictionary ?: return false
        return d.getCOSName(S) == JS_ACTION || d.containsKey(COSName.JS)
    }

    private fun countAa(aa: COSDictionary?): Int {
        aa ?: return 0
        var n = 0
        for (k in aa.keySet()) if (isJs(aa.getDictionaryObject(k))) n++
        return n
    }

    /** Number of leaf entries of a name tree given as a COS dictionary. */
    private fun countNameTree(node: COSDictionary, depth: Int): Int {
        if (depth > 32) return 0
        var n = 0
        (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { n += it.size() / 2 }
        (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids ->
            for (i in 0 until kids.size()) (deref(kids.get(i)) as? COSDictionary)?.let { n += countNameTree(it, depth + 1) }
        }
        return n
    }
}
