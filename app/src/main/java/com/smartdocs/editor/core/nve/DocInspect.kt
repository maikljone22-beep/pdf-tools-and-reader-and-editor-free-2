package com.smartdocs.editor.core.nve

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * NVE-file: read-only facts of a document for the properties sheet: page size, Fast Web View, base URL, locks,
 * security (method + the CURRENT permissions of the opened file), every font used, PDF/A + PDF/UA claims, XMP.
 * Pure PDFBox on COS level (JVM-testable).
 */
object DocInspect {

    data class PageSize(val widthPt: Float, val heightPt: Float, val name: String?) {
        val widthMm get() = widthPt * 25.4f / 72f
        val heightMm get() = heightPt * 25.4f / 72f
        val widthIn get() = widthPt / 72f
        val heightIn get() = heightPt / 72f
    }

    /** Current permissions of the opened file (all true when it is not encrypted or opened with the owner password). */
    data class Permissions(
        val print: Boolean = true, val printHighQuality: Boolean = true, val modify: Boolean = true, val copy: Boolean = true,
        val annotate: Boolean = true, val fillForms: Boolean = true, val accessibility: Boolean = true, val assemble: Boolean = true,
    ) {
        val restricted get() = !(print && printHighQuality && modify && copy && annotate && fillForms && accessibility && assemble)
    }

    data class Security(
        val encrypted: Boolean, val method: String, val keyBits: Int, val revision: Int, val filter: String,
        val owner: Boolean, val permissions: Permissions, val encryptMetadata: Boolean = true,
    )

    data class FontEntry(
        val name: String, val type: String, val encoding: String, val embedded: Boolean, val subset: Boolean,
        val pages: Set<Int>, val inAnnotations: Boolean = false, val inForms: Boolean = false,
    )

    data class Facts(
        val version: String, val pages: Int, val firstPage: PageSize?, val linearized: Boolean, val baseUrl: String,
        val signatures: Int, val certified: Boolean, val fieldLocks: Boolean, val security: Security,
        val tagged: Boolean, val hasStructTree: Boolean, val lang: String, val producer: String,
        val pdfaClaim: String?, val pdfuaClaim: Int?, val xmp: String?,
    ) {
        /** "Locked": changes are restricted (permissions, certification or locked fields). */
        val locked get() = security.permissions.restricted || certified || fieldLocks
    }

    private fun d(b: COSBase?): COSBase? = if (b is COSObject) b.`object` else b
    private fun n(s: String) = COSName.getPDFName(s)

    private val named = listOf(
        "A0" to (2384f to 3370f), "A1" to (1684f to 2384f), "A2" to (1191f to 1684f), "A3" to (842f to 1191f), "A4" to (595f to 842f),
        "A5" to (420f to 595f), "A6" to (298f to 420f), "B4" to (709f to 1001f), "B5" to (499f to 709f), "Letter" to (612f to 792f),
        "Legal" to (612f to 1008f), "Tabloid" to (792f to 1224f), "Executive" to (522f to 756f),
    )

    /** A known paper name for [w] x [h] points (either orientation, 3 pt tolerance), or null. */
    fun paperName(w: Float, h: Float): String? {
        val a = minOf(w, h); val b = maxOf(w, h)
        return named.firstOrNull { (_, s) -> abs(s.first - a) <= 3f && abs(s.second - b) <= 3f }?.first
    }

    fun pageSize(doc: PDDocument, index: Int): PageSize? {
        if (index !in 0 until doc.numberOfPages) return null
        val p = doc.getPage(index); val b = p.cropBox
        val rot = ((p.rotation % 360) + 360) % 360
        val w = if (rot % 180 == 0) b.width else b.height; val h = if (rot % 180 == 0) b.height else b.width
        return PageSize(w, h, paperName(w, h))
    }

    /** Linearized ("Fast Web View"): the first object of the file is a /Linearized dictionary whose /L is the file length. */
    fun isLinearized(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(minOf(2048L, raf.length()).toInt()); raf.readFully(buf)
            val head = String(buf, Charsets.ISO_8859_1)
            val m = Regex("""/Linearized\s+[\d.]+""").find(head) ?: return@use false
            val l = Regex("""/L\s+(\d+)""").find(head, m.range.first)?.groupValues?.get(1)?.toLongOrNull()
            l == null || l == raf.length()
        }
    }.getOrDefault(false)

    fun security(doc: PDDocument): Security {
        if (!doc.isEncrypted) return Security(false, "", 0, 0, "", true, Permissions())
        val enc = doc.encryption
        val ed = enc.cosObject
        val v = (d(ed.getDictionaryObject(n("V"))) as? COSNumber)?.intValue() ?: 0
        val r = (d(ed.getDictionaryObject(n("R"))) as? COSNumber)?.intValue() ?: 0
        val len = (d(ed.getDictionaryObject(n("Length"))) as? COSNumber)?.intValue() ?: if (v == 1) 40 else 0
        val cfm = ((d(ed.getDictionaryObject(n("CF"))) as? COSDictionary)?.let { cf ->
            val stdName = (d(ed.getDictionaryObject(n("StmF"))) as? COSName)?.name ?: "StdCF"
            (d(cf.getDictionaryObject(n(stdName))) as? COSDictionary)?.getNameAsString(n("CFM"))
        })
        val (method, bits) = when (cfm) {
            "AESV3" -> "AES" to 256
            "AESV2" -> "AES" to 128
            "V2" -> "RC4" to (if (len > 0) len else 128)
            else -> if (v >= 5) "AES" to 256 else "RC4" to (if (len > 0) len else 40)
        }
        val ap = doc.currentAccessPermission
        val owner = runCatching { ap.isOwnerPermission }.getOrDefault(false)
        val p = runCatching { ap.permissionBytes }.getOrDefault(-1)
        val perms = if (owner) Permissions() else decode(p, r)
        val encMeta = runCatching { ed.getBoolean(n("EncryptMetadata"), true) }.getOrDefault(true)
        return Security(true, method, bits, r, ed.getNameAsString(n("Filter")) ?: "Standard", owner, perms, encMeta)
    }

    /** /P bits (PDF 32000 table 22): 3 print, 4 modify, 5 copy, 6 annotate, 9 fill, 10 accessibility, 11 assemble, 12 faithful print. */
    fun decode(p: Int, revision: Int = 3): Permissions {
        fun bit(i: Int) = (p shr (i - 1)) and 1 == 1
        val print = bit(3)
        return Permissions(
            print = print, printHighQuality = print && (revision < 3 || bit(12)), modify = bit(4), copy = bit(5), annotate = bit(6),
            fillForms = if (revision >= 3) bit(9) || bit(6) else bit(6), accessibility = if (revision >= 3) bit(10) || bit(5) else bit(5),
            assemble = if (revision >= 3) bit(11) || bit(4) else bit(4),
        )
    }

    /** Every font: page resources (and the forms they draw), annotation appearances, the AcroForm default resources. */
    fun fonts(doc: PDDocument): List<FontEntry> {
        class Acc(val name: String, val type: String, val encoding: String, val embedded: Boolean, val subset: Boolean) {
            val pages = HashSet<Int>(); var annot = false; var form = false
        }
        val byObj = LinkedHashMap<Any, Acc>()
        val seenRes = HashSet<Any>()

        fun fontOf(fd: COSDictionary): Acc {
            val sub = fd.getNameAsString(COSName.SUBTYPE) ?: "?"
            val base = fd.getNameAsString(COSName.BASE_FONT) ?: (fd.getNameAsString(COSName.NAME) ?: "(Type3)")
            var type = sub
            var desc = d(fd.getDictionaryObject(COSName.FONT_DESC)) as? COSDictionary
            val encB = d(fd.getDictionaryObject(COSName.ENCODING))
            var encoding = when (encB) {
                is COSName -> encB.name
                is COSStream -> "CMap " + (encB.getNameAsString(n("CMapName")) ?: "")
                is COSDictionary -> (encB.getNameAsString(n("BaseEncoding")) ?: "Custom") + if (encB.containsKey(n("Differences"))) " + Differences" else ""
                else -> if (isSymbolic(base)) "Built-in" else "Standard"
            }
            if (sub == "Type0") {
                val desc0 = (d(fd.getDictionaryObject(n("DescendantFonts"))) as? COSArray)?.let { d(it.getObject(0)) as? COSDictionary }
                if (desc0 != null) { type = "Type0 (" + (desc0.getNameAsString(COSName.SUBTYPE) ?: "CID") + ")"; desc = d(desc0.getDictionaryObject(COSName.FONT_DESC)) as? COSDictionary ?: desc }
            }
            val embedded = sub == "Type3" || desc?.let { it.containsKey(n("FontFile")) || it.containsKey(n("FontFile2")) || it.containsKey(n("FontFile3")) } == true
            if (sub == "Type3") encoding = if (encB == null) "Custom" else encoding
            val subset = Regex("^[A-Z]{6}\\+").containsMatchIn(base)
            return Acc(base, type, encoding, embedded, subset)
        }

        fun walk(res: COSDictionary?, page: Int, annot: Boolean, form: Boolean, depth: Int) {
            if (res == null || depth > 10) return
            val fonts = d(res.getDictionaryObject(COSName.FONT)) as? COSDictionary
            fonts?.keySet()?.forEach { k ->
                val raw = fonts.getItem(k)
                val fd = d(raw) as? COSDictionary ?: return@forEach
                // indirect fonts by object number; direct ones (written inline per page) by what they are
                val probe = fontOf(fd)
                val key: Any = (raw as? COSObject)?.let { it.objectNumber to it.generationNumber } ?: listOf(probe.name, probe.type, probe.encoding, probe.embedded)
                val acc = byObj.getOrPut(key) { probe }
                if (page >= 0) acc.pages += page
                if (annot) acc.annot = true
                if (form) acc.form = true
            }
            if (!seenRes.add(res to page)) return
            val xo = d(res.getDictionaryObject(COSName.XOBJECT)) as? COSDictionary
            xo?.keySet()?.forEach { k ->
                val s = d(xo.getItem(k)) as? COSStream ?: return@forEach
                if (s.getNameAsString(COSName.SUBTYPE) == "Form") walk(d(s.getDictionaryObject(COSName.RESOURCES)) as? COSDictionary, page, annot, form, depth + 1)
            }
        }

        doc.pages.forEachIndexed { i, p ->
            walk(d(p.cosObject.getDictionaryObject(COSName.RESOURCES)) as? COSDictionary, i, false, false, 0)
            val annots = d(p.cosObject.getDictionaryObject(COSName.ANNOTS)) as? COSArray ?: return@forEachIndexed
            for (j in 0 until annots.size()) {
                val a = d(annots.getObject(j)) as? COSDictionary ?: continue
                val isWidget = a.getNameAsString(COSName.SUBTYPE) == "Widget"
                val ap = d(a.getDictionaryObject(n("AP"))) as? COSDictionary ?: continue
                listOf("N", "R", "D").forEach { st ->
                    when (val e = d(ap.getDictionaryObject(n(st)))) {
                        is COSStream -> walk(d(e.getDictionaryObject(COSName.RESOURCES)) as? COSDictionary, i, !isWidget, isWidget, 1)
                        is COSDictionary -> e.keySet().forEach { k -> (d(e.getItem(k)) as? COSStream)?.let { s -> walk(d(s.getDictionaryObject(COSName.RESOURCES)) as? COSDictionary, i, !isWidget, isWidget, 1) } }
                        else -> {}
                    }
                }
            }
        }
        val acro = d(doc.documentCatalog.cosObject.getDictionaryObject(n("AcroForm"))) as? COSDictionary
        walk(d(acro?.getDictionaryObject(n("DR"))) as? COSDictionary, -1, false, true, 0)
        return byObj.values.map { FontEntry(it.name, it.type, it.encoding, it.embedded, it.subset, it.pages.toSortedSet(), it.annot, it.form) }
            .sortedWith(compareBy({ it.pages.minOrNull() ?: Int.MAX_VALUE }, { it.name }))
    }

    private fun isSymbolic(base: String) = base.contains("Symbol") || base.contains("Dingbats")

    fun xmpText(doc: PDDocument): String? = runCatching { doc.documentCatalog.metadata?.toByteArray()?.let { String(it, Charsets.UTF_8) } }.getOrNull()

    fun pdfaClaim(xmp: String?): String? = xmp?.let { m ->
        val part = Regex("""pdfaid:part(?:>|=")\s*(\d)""").find(m)?.groupValues?.get(1)
        val conf = Regex("""pdfaid:conformance(?:>|=")\s*([ABUabu])""").find(m)?.groupValues?.get(1)
        part?.let { "PDF/A-$it" + (conf?.lowercase() ?: "") }
    }

    fun pdfuaClaim(xmp: String?): Int? = xmp?.let { Regex("""pdfuaid:part(?:>|=")\s*(\d)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    fun baseUrl(doc: PDDocument): String =
        (d(doc.documentCatalog.cosObject.getDictionaryObject(n("URI"))) as? COSDictionary)?.getString(n("Base")).orEmpty()

    /** Certified (/Perms /DocMDP) and field locks (a signature field /Lock or a FieldMDP reference). */
    fun locks(doc: PDDocument): Pair<Boolean, Boolean> {
        val cat = doc.documentCatalog.cosObject
        val certified = (d(cat.getDictionaryObject(n("Perms"))) as? COSDictionary)?.containsKey(n("DocMDP")) == true
        var field = false
        runCatching {
            doc.documentCatalog.acroForm?.fieldTree?.forEach { f ->
                val fd = f.cosObject
                if (fd.getNameAsString(n("FT")) == "Sig") {
                    if (fd.containsKey(n("Lock"))) field = true
                    val v = d(fd.getDictionaryObject(n("V"))) as? COSDictionary
                    val refs = d(v?.getDictionaryObject(n("Reference"))) as? COSArray
                    if (refs != null) for (i in 0 until refs.size()) if ((d(refs.getObject(i)) as? COSDictionary)?.getNameAsString(n("TransformMethod")) == "FieldMDP") field = true
                }
            }
        }
        return certified to field
    }

    fun facts(file: File, doc: PDDocument, page: Int = 0): Facts {
        val xmp = xmpText(doc)
        val (cert, fieldLock) = locks(doc)
        val cat = doc.documentCatalog
        return Facts(
            version = runCatching { doc.version.toString() }.getOrDefault(""), pages = doc.numberOfPages,
            firstPage = pageSize(doc, page.coerceIn(0, (doc.numberOfPages - 1).coerceAtLeast(0))), linearized = isLinearized(file),
            baseUrl = baseUrl(doc), signatures = runCatching { doc.signatureDictionaries.size }.getOrDefault(0), certified = cert, fieldLocks = fieldLock,
            security = security(doc), tagged = cat.markInfo?.isMarked == true, hasStructTree = cat.cosObject.containsKey(n("StructTreeRoot")),
            lang = cat.language.orEmpty(), producer = doc.documentInformation?.producer.orEmpty(), pdfaClaim = pdfaClaim(xmp), pdfuaClaim = pdfuaClaim(xmp), xmp = xmp,
        )
    }

    /** Open [file] without re-protecting (so the current permissions are the ones it was opened with) and read the facts. */
    fun read(file: File, password: String?, page: Int = 0): Facts =
        com.smartdocs.editor.core.pdf.PdfOps.loadRaw(file, password).use { facts(file, it, page) }

    fun readFonts(file: File, password: String?): List<FontEntry> =
        com.smartdocs.editor.core.pdf.PdfOps.loadRaw(file, password).use { fonts(it) }
}
