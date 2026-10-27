package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.redact.SanitizeItem
import com.smartdocs.editor.core.redact.Sanitizer
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode

/** Nitro optimizer extras: removing content classes, named destinations and embedded fonts, and the tips. */
object OptimizeExtras {

    /** Annotations, form fields, links and bookmarks go through [Sanitizer] (same rules as Sanitize); then named destinations. */
    fun removeContent(doc: PDDocument, o: OptimizeOptions) {
        val items = buildSet {
            if (o.removeAnnotations) add(SanitizeItem.COMMENTS)
            if (o.removeForms) add(SanitizeItem.FORMS)
            if (o.removeLinks) add(SanitizeItem.LINKS)
            if (o.removeBookmarks) add(SanitizeItem.BOOKMARKS)
        }
        if (items.isNotEmpty()) Sanitizer.clean(doc, items)
        if (o.removeNamedDests) removeNamedDestinations(doc)
    }

    /**
     * Removes the named-destination trees (/Dests in the catalog and in /Names). Links, actions and bookmarks that
     * point at a name get the explicit page destination first, so nothing stops working. Returns the names resolved.
     */
    fun removeNamedDestinations(doc: PDDocument): Int {
        val cat = doc.documentCatalog
        val c = cat.cosObject
        val names = c.getDictionaryObject(COSName.NAMES) as? COSDictionary
        if (!c.containsKey(COSName.DESTS) && names?.containsKey(COSName.DESTS) != true) return 0
        var n = 0
        fun resolve(d: COSBase?): COSBase? {
            val named = when (d) {
                is COSString -> PDNamedDestination(d)
                is COSName -> PDNamedDestination(d)
                else -> return null
            }
            return runCatching { cat.findNamedDestinationPage(named)?.cosObject }.getOrNull()?.also { n++ }
        }
        fun fixAction(a: COSDictionary?, depth: Int = 0) {
            if (a == null || depth > 20) return
            if (a.getCOSName(COSName.S) == COSName.getPDFName("GoTo")) resolve(a.getDictionaryObject(COSName.D))?.let { a.setItem(COSName.D, it) }
            when (val next = a.getDictionaryObject(COSName.NEXT)) {
                is COSDictionary -> fixAction(next, depth + 1)
                is COSArray -> for (i in 0 until next.size()) fixAction(next.getObject(i) as? COSDictionary, depth + 1)
                else -> {}
            }
        }
        doc.pages.forEach { p ->
            runCatching { p.annotations }.getOrNull()?.forEach { a ->
                val d = a.cosObject
                resolve(d.getDictionaryObject(COSName.DEST))?.let { d.setItem(COSName.DEST, it) }
                fixAction(d.getDictionaryObject(COSName.A) as? COSDictionary)
            }
        }
        fixAction(c.getDictionaryObject(COSName.OPEN_ACTION) as? COSDictionary)
        resolve(c.getDictionaryObject(COSName.OPEN_ACTION))?.let { c.setItem(COSName.OPEN_ACTION, it) }
        fun walk(node: PDOutlineNode, depth: Int) {
            if (depth > 40) return
            var it = node.firstChild; var guard = 0
            while (it != null && guard++ < 10_000) {
                val d = it.cosObject
                resolve(d.getDictionaryObject(COSName.DEST))?.let { x -> d.setItem(COSName.DEST, x) }
                fixAction(d.getDictionaryObject(COSName.A) as? COSDictionary)
                walk(it, depth + 1); it = it.nextSibling
            }
        }
        cat.documentOutline?.let { walk(it, 0) }
        c.removeItem(COSName.DESTS)
        names?.removeItem(COSName.DESTS)
        return n
    }

    private val STANDARD = setOf("Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique", "Times-Roman", "Times-Bold", "Times-Italic",
        "Times-BoldItalic", "Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique", "Symbol", "ZapfDingbats",
        "Arial", "Arial,Bold", "Arial,Italic", "Arial,BoldItalic", "ArialMT", "Arial-BoldMT", "Arial-ItalicMT", "Arial-BoldItalicMT",
        "TimesNewRoman", "TimesNewRoman,Bold", "TimesNewRomanPSMT", "TimesNewRomanPS-BoldMT", "TimesNewRomanPS-ItalicMT", "TimesNewRomanPS-BoldItalicMT",
        "CourierNew", "CourierNewPSMT", "CourierNewPS-BoldMT")
    private val FONT_FILES = listOf(COSName.FONT_FILE, COSName.FONT_FILE2, COSName.FONT_FILE3)

    /** "ABCDEF+Helvetica" → "Helvetica". */
    fun baseName(name: String): String = if (name.length > 7 && name[6] == '+' && name.take(6).all { it in 'A'..'Z' }) name.substring(7) else name

    fun isStandard(name: String) = baseName(name) in STANDARD

    /**
     * Nitro "Remove embedded fonts". STANDARD: simple (non-Type0) fonts whose name is one of the standard 14 or its
     * common Windows alias, which every viewer can draw. ALL: every embedded font of simple fonts; Type0 (CID) fonts
     * keep their programs, because without one the glyph ids (Arabic, CJK) cannot be shown by any viewer.
     * Returns the number of fonts unembedded.
     */
    fun unembedFonts(doc: PDDocument, mode: FontUnembed): Int {
        if (mode == FontUnembed.NONE) return 0
        val seen = HashSet<COSDictionary>()
        var n = 0
        fun fonts(res: PDResources?, depth: Int) {
            if (res == null || depth > 10) return
            val fd = res.cosObject.getDictionaryObject(COSName.FONT) as? COSDictionary
            fd?.keySet()?.forEach { k ->
                val f = fd.getDictionaryObject(k) as? COSDictionary ?: return@forEach
                if (!seen.add(f)) return@forEach
                if (f.getCOSName(COSName.SUBTYPE) == COSName.TYPE0 || f.getCOSName(COSName.SUBTYPE) == COSName.TYPE3) return@forEach
                val name = f.getCOSName(COSName.BASE_FONT)?.name ?: return@forEach
                if (mode == FontUnembed.STANDARD && !isStandard(name)) return@forEach
                val desc = f.getDictionaryObject(COSName.FONT_DESC) as? COSDictionary ?: return@forEach
                if (FONT_FILES.none { desc.containsKey(it) }) return@forEach
                FONT_FILES.forEach { desc.removeItem(it) }
                // the subset tag no longer names an embedded program
                val base = baseName(name)
                if (base != name) { f.setName(COSName.BASE_FONT, base); desc.setName(COSName.FONT_NAME, base) }
                n++
            }
            val xo = res.cosObject.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
            xo?.keySet()?.forEach { k ->
                val x = xo.getDictionaryObject(k) as? com.tom_roush.pdfbox.cos.COSStream ?: return@forEach
                if (x.getCOSName(COSName.SUBTYPE) == COSName.FORM) (x.getDictionaryObject(COSName.RESOURCES) as? COSDictionary)?.let { fonts(PDResources(it), depth + 1) }
            }
        }
        doc.pages.forEach { p ->
            fonts(p.resources, 0)
            runCatching { p.annotations }.getOrNull()?.forEach { a ->
                (a.cosObject.getDictionaryObject(COSName.AP) as? COSDictionary)?.values?.forEach { v ->
                    ((v as? com.tom_roush.pdfbox.cos.COSObject)?.`object` ?: v).let { s ->
                        (s as? com.tom_roush.pdfbox.cos.COSStream)?.let { st -> (st.getDictionaryObject(COSName.RESOURCES) as? COSDictionary)?.let { fonts(PDResources(it), 1) } }
                    }
                }
            }
        }
        (doc.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM) as? COSDictionary)?.let { af ->
            (af.getDictionaryObject(COSName.DR) as? COSDictionary)?.let { fonts(PDResources(it), 0) }
        }
        return n
    }

    /** Tips from the audit of the result: where the bytes still are and which option would help. */
    fun tips(after: SpaceAudit, o: OptimizeOptions, keptOriginal: Boolean): List<OptimizeTip> {
        val out = ArrayList<OptimizeTip>()
        if (after.fileSize < 300_000 && !keptOriginal) { out += OptimizeTip.ALREADY_SMALL; return out }
        if (after.share(SpaceCat.IMAGES) > 0.4f) {
            if (o.imageDpi > 120) out += OptimizeTip.LOWER_DPI
            if (o.jpegQuality > 60) out += OptimizeTip.LOWER_QUALITY
            if (!o.grayImages) out += OptimizeTip.GRAY_IMAGES
            if (o.monoMode == MonoCompression.KEEP) out += OptimizeTip.MONO_CCITT
        }
        if (after.share(SpaceCat.FONTS) > 0.25f && o.unembedFonts == FontUnembed.NONE) out += OptimizeTip.FONTS
        if (after.share(SpaceCat.ATTACHMENTS) > 0.1f && !o.stripAttachments) out += OptimizeTip.ATTACHMENTS
        if (after.share(SpaceCat.THUMBNAILS) > 0.05f && !o.stripThumbnails) out += OptimizeTip.THUMBNAILS
        if (after.share(SpaceCat.ANNOTATIONS) > 0.15f && !o.removeAnnotations) out += OptimizeTip.ANNOTATIONS
        if (after.share(SpaceCat.FORMS_XOBJECTS) > 0.3f && !o.removeForms) out += OptimizeTip.FORMS
        if (after.share(SpaceCat.CONTENT) > 0.5f) out += OptimizeTip.CONTENT
        return out
    }
}
