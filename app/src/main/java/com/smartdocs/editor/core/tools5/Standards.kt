package com.smartdocs.editor.core.tools5

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject

/** PDF/A levels (Foxit 8 / Nitro 20): part 1–3, conformance a (tagged), b (visual), u (Unicode text). */
enum class PdfALevel(val part: Int, val conformance: String) {
    A1A(1, "A"), A1B(1, "B"), A2A(2, "A"), A2B(2, "B"), A2U(2, "U"), A3A(3, "A"), A3B(3, "B"), A3U(3, "U");

    val label get() = "PDF/A-$part${conformance.lowercase()}"
    val needsTags get() = conformance == "A"
    val needsUnicode get() = conformance != "B"
    /** PDF/A-1 allows no transparency, no layers, PDF ≤ 1.4. */
    val strict1 get() = part == 1
    /** PDF/A-3 allows any attachment (with an /AFRelationship). */
    val anyAttachments get() = part == 3
}

/** Standards a file says it follows. */
data class Claims(val pdfa: String?, val pdfua: String?, val pdfx: String?, val pdfe: String?, val pdfvt: String?, val facturX: String?)

/** Preflight profiles (Foxit "Profiles" tab); [code] maps to "pc_prof_<code>". */
enum class PreflightProfile(val code: String) { PDFA_1B("a1b"), PDFA_2B("a2b"), PDFA_2U("a2u"), PDFA_3B("a3b"), PDFX_4("x4"), PDFE_1("e1"), PRINT("print"), VERSION("version"), ZUGFERD("zugferd") }

/** Print-production facts of a page range (Foxit preflight print checks). */
data class PrintFacts(
    val hairlines: Int = 0, val hairlinePages: Set<Int> = emptySet(), val overprint: Int = 0, val spotColours: Set<String> = emptySet(),
    val minPpi: Float = 0f, val maxPpi: Float = 0f, val lowPpiPages: Set<Int> = emptySet(), val invisibleText: Int = 0, val courier: Int = 0,
    val jpx: Boolean = false, val openType: Boolean = false, val layers: Boolean = false, val tagged: Boolean = false, val aesBits: Int = 0,
)

object Standards {
    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    fun xmp(doc: PDDocument): String? = runCatching { doc.documentCatalog.metadata?.exportXMPMetadata()?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()

    /** Value of an XMP property written as an element or an attribute ("pdfaid:part"). Pure. */
    fun xmpValue(xmp: String?, prop: String): String? {
        if (xmp == null) return null
        Regex("<$prop>\\s*([^<]*?)\\s*</$prop>").find(xmp)?.let { return it.groupValues[1] }
        return Regex("$prop=\"([^\"]*)\"").find(xmp)?.groupValues?.get(1)
    }

    fun claims(doc: PDDocument): Claims {
        val x = xmp(doc)
        val pdfa = xmpValue(x, "pdfaid:part")?.let { p -> "PDF/A-$p" + (xmpValue(x, "pdfaid:conformance")?.lowercase().orEmpty()) }
        val ua = xmpValue(x, "pdfuaid:part")?.let { "PDF/UA-$it" }
        val pdfx = (doc.documentInformation?.cosObject?.getString(COSName.getPDFName("GTS_PDFXVersion"))) ?: xmpValue(x, "pdfxid:GTS_PDFXVersion") ?: xmpValue(x, "pdfx:GTS_PDFXVersion")
        val pdfe = xmpValue(x, "pdfe:ISO_PDFEVersion")
        val vt = xmpValue(x, "pdfvtid:GTS_PDFVTVersion")
        val fx = xmpValue(x, "fx:ConformanceLevel")?.let { lvl -> (xmpValue(x, "fx:DocumentType") ?: "INVOICE") + " " + lvl }
        return Claims(pdfa, ua, pdfx, pdfe, vt, fx)
    }

    /** XMP without the given namespace prefixes' properties (pdfaid, pdfuaid, pdfxid, pdfe, pdfvtid). Pure. */
    fun stripXmp(xmp: String, prefixes: Set<String>): String {
        var s = xmp
        prefixes.forEach { p ->
            s = s.replace(Regex("<$p:[A-Za-z_]+>[^<]*</$p:[A-Za-z_]+>\\s*"), "")
                .replace(Regex("\\s$p:[A-Za-z_]+=\"[^\"]*\""), "")
                .replace(Regex("\\sxmlns:$p=\"[^\"]*\""), "")
        }
        return s
    }

    /** Foxit "Remove PDF/A / PDF/UA / PDF/X / PDF/E information". */
    fun removeClaims(doc: PDDocument, prefixes: Set<String>) {
        xmp(doc)?.let { x -> val md = PDMetadata(doc); md.importXMPMetadata(stripXmp(x, prefixes).toByteArray(Charsets.UTF_8)); doc.documentCatalog.metadata = md }
        if ("pdfxid" in prefixes) {
            doc.documentInformation?.cosObject?.removeItem(COSName.getPDFName("GTS_PDFXVersion"))
            doc.documentInformation?.cosObject?.removeItem(COSName.getPDFName("GTS_PDFXConformance"))
            (deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("OutputIntents"))) as? COSArray)?.let { a ->
                for (k in a.size() - 1 downTo 0) if ((deref(a.get(k)) as? COSDictionary)?.getNameAsString(COSName.S) == "GTS_PDFX") a.remove(k)
            }
        }
    }

    // ------------------------------------------------------------------ print checks

    private class Ctm(var a: Float = 1f, var b: Float = 0f, var c: Float = 0f, var d: Float = 1f) {
        fun copy() = Ctm(a, b, c, d)
        fun times(x: FloatArray) { val na = x[0] * a + x[1] * c; val nb = x[0] * b + x[1] * d; val nc = x[2] * a + x[3] * c; val nd = x[2] * b + x[3] * d; a = na; b = nb; c = nc; d = nd }
        val scale get() = Math.sqrt(Math.abs(a * d - b * c).toDouble()).toFloat()
    }

    /** Effective resolution of an image of [px] × [py] pixels drawn with a CTM (a, b, c, d). Pure. */
    fun ppi(px: Int, py: Int, a: Float, b: Float, c: Float, d: Float): Float {
        val w = Math.hypot(a.toDouble(), b.toDouble()) / 72.0; val h = Math.hypot(c.toDouble(), d.toDouble()) / 72.0
        if (w <= 0 || h <= 0) return 0f
        return minOf(px / w, py / h).toFloat()
    }

    /** Lowest PDF version that holds the features used. Pure. */
    fun minVersion(transparency: Boolean, layers: Boolean, jpx: Boolean, openType: Boolean, aesBits: Int, attachments: Boolean): Float = listOfNotNull(
        1.3f.takeIf { attachments }, 1.4f.takeIf { transparency }, 1.5f.takeIf { layers || jpx }, 1.6f.takeIf { openType || aesBits == 128 }, 1.7f.takeIf { aesBits >= 256 },
    ).maxOrNull() ?: 1.3f

    fun printFacts(doc: PDDocument, pages: List<Int>, lowPpi: Float = 150f): PrintFacts {
        var hair = 0; val hairPages = HashSet<Int>(); var op = 0; val spots = HashSet<String>()
        var minP = Float.MAX_VALUE; var maxP = 0f; val lowPages = HashSet<Int>(); var invisible = 0; var jpx = false
        val seenGs = HashSet<COSDictionary>()
        for (pi in pages) {
            if (pi !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(pi); val res = page.resources
            // spot colours named in the page colour spaces
            runCatching { res?.colorSpaceNames?.forEach { n ->
                (deref(res.cosObject.getDictionaryObject(COSName.COLORSPACE)) as? COSDictionary)?.let { cs -> (deref(cs.getDictionaryObject(n)) as? COSArray)?.let { a ->
                    when (a.getName(0)) { "Separation" -> a.getName(1)?.let { spots += it }; "DeviceN" -> (deref(a.get(1)) as? COSArray)?.forEach { x -> (deref(x) as? COSName)?.name?.let { if (it != "None") spots += it } } }
                } }
            } }
            val tokens = runCatching { PDFStreamParser(page).let { it.parse(); it.tokens } }.getOrNull() ?: continue
            val stack = ArrayDeque<Pair<Ctm, Float>>(); var ctm = Ctm(); var lw = 1f; var tr = 0
            val nums = ArrayList<Any>()
            for (t in tokens) {
                if (t !is Operator) { nums += t; continue }
                fun f(i: Int) = (nums.getOrNull(i) as? COSNumber)?.floatValue() ?: 0f
                when (t.name) {
                    "q" -> stack.addLast(ctm.copy() to lw)
                    "Q" -> stack.removeLastOrNull()?.let { ctm = it.first; lw = it.second }
                    "cm" -> if (nums.size >= 6) ctm.times(floatArrayOf(f(nums.size - 6), f(nums.size - 5), f(nums.size - 4), f(nums.size - 3)))
                    "w" -> lw = f(nums.size - 1)
                    "S", "s", "B", "B*", "b", "b*" -> if (lw * ctm.scale < 0.25f) { hair++; hairPages += pi }
                    "Tr" -> tr = f(nums.size - 1).toInt()
                    "Tj", "TJ", "'", "\"" -> if (tr == 3) invisible++
                    "gs" -> (nums.lastOrNull() as? COSName)?.let { n ->
                        runCatching { res?.getExtGState(n)?.cosObject }.getOrNull()?.let { g ->
                            if (seenGs.add(g) && (g.getBoolean(COSName.getPDFName("OP"), false) || g.getBoolean(COSName.getPDFName("op"), false))) op++
                        }
                    }
                    "Do" -> (nums.lastOrNull() as? COSName)?.let { n ->
                        val x = runCatching { res?.getXObject(n) }.getOrNull()
                        if (x is PDImageXObject) {
                            if (x.cosObject.getDictionaryObject(COSName.FILTER).let { fl -> fl == COSName.getPDFName("JPXDecode") || (fl as? COSArray)?.any { deref(it) == COSName.getPDFName("JPXDecode") } == true }) jpx = true
                            val p = ppi(x.width, x.height, ctm.a, ctm.b, ctm.c, ctm.d)
                            if (p > 0f) { minP = minOf(minP, p); maxP = maxOf(maxP, p); if (p < lowPpi) lowPages += pi }
                        }
                    }
                }
                nums.clear()
            }
        }
        val facts = PdfInspect.inspect(doc)
        val cat = doc.documentCatalog.cosObject
        val enc = doc.encryption
        return PrintFacts(hair, hairPages, op, spots, if (minP == Float.MAX_VALUE) 0f else minP, maxP, lowPages, invisible,
            facts.fonts.count { it.name.contains("Courier", true) }, jpx,
            openTypeFonts(doc, pages),
            cat.containsKey(COSName.getPDFName("OCProperties")), cat.containsKey(COSName.getPDFName("StructTreeRoot")),
            if (enc == null) 0 else runCatching { if (enc.version >= 5) 256 else if (enc.version == 4) 128 else 0 }.getOrDefault(0))
    }

    /** OpenType font programs (FontFile3 /OpenType) on [pages] — they need PDF 1.6. */
    private fun openTypeFonts(doc: PDDocument, pages: List<Int>): Boolean = pages.any { pi ->
        pi in 0 until doc.numberOfPages && runCatching {
            val res = doc.getPage(pi).resources ?: return@runCatching false
            res.fontNames.any { n ->
                val fd = res.getFont(n)?.fontDescriptor
                (fd?.fontFile3?.cosObject?.getNameAsString(COSName.SUBTYPE)) == "OpenType"
            }
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ profiles

    /**
     * Findings of [profile] for [pages] (page-based findings are limited to them). PDF/A profiles reuse [PdfA.report]
     * with the level's extra rules; PDF/X, PDF/E, ZUGFeRD and print checks are heuristics (not a certified validator).
     */
    fun preflight(doc: PDDocument, profile: PreflightProfile, pages: List<Int>): PreflightReport {
        val facts = PdfInspect.inspect(doc)
        val pf = printFacts(doc, pages)
        val inRange = pages.toSet()
        val claims = claims(doc)
        fun page(f: Finding) = f.copy(pages = f.pages.filter { it in inRange }, count = if (f.pages.isEmpty()) f.count else f.pages.count { it in inRange })
        val out = ArrayList<Finding>()
        fun add(code: String, sev: Severity, n: Int = 0, pg: Collection<Int> = emptyList(), detail: String = "") { out += Finding(code, sev, n, pg.sorted(), false, detail) }
        when (profile) {
            PreflightProfile.PDFA_1B, PreflightProfile.PDFA_2B, PreflightProfile.PDFA_2U, PreflightProfile.PDFA_3B -> {
                val level = when (profile) { PreflightProfile.PDFA_1B -> PdfALevel.A1B; PreflightProfile.PDFA_2U -> PdfALevel.A2U; PreflightProfile.PDFA_3B -> PdfALevel.A3B; else -> PdfALevel.A2B }
                out += levelReport(facts, pf, level).findings.map(::page).filter { it.pages.isNotEmpty() || it.count > 0 || it.code !in setOf("cmyk", "transparency") }
            }
            PreflightProfile.PDFX_4 -> {
                if (claims.pdfx == null) add("x_claim", Severity.ERROR)
                val intents = (deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("OutputIntents"))) as? COSArray)
                if (intents == null || (0 until intents.size()).none { (deref(intents.get(it)) as? COSDictionary)?.getNameAsString(COSName.S) == "GTS_PDFX" }) add("x_intent", Severity.ERROR)
                if (facts.fontsNotEmbedded.isNotEmpty()) add("fonts", Severity.ERROR, facts.fontsNotEmbedded.size, detail = facts.fontsNotEmbedded.joinToString { it.name }.take(300))
                if (facts.encrypted) add("encrypted", Severity.ERROR)
                val noTrim = pages.filter { it in 0 until doc.numberOfPages }.filter { p -> doc.getPage(p).cosObject.let { !it.containsKey(COSName.TRIM_BOX) && !it.containsKey(COSName.ART_BOX) } }
                if (noTrim.isNotEmpty()) add("x_trim", Severity.ERROR, noTrim.size, noTrim)
                if (doc.documentInformation?.cosObject?.containsKey(COSName.getPDFName("Trapped")) != true) add("x_trapped", Severity.ERROR)
                if (facts.javascript) add("js", Severity.ERROR)
                if (facts.multimedia > 0) add("multimedia", Severity.ERROR, facts.multimedia)
            }
            PreflightProfile.PDFE_1 -> {
                if (claims.pdfe == null) add("e_claim", Severity.ERROR)
                if (facts.fontsNotEmbedded.isNotEmpty()) add("fonts", Severity.ERROR, facts.fontsNotEmbedded.size)
                if (facts.encrypted) add("encrypted", Severity.ERROR)
                if (facts.javascript) add("js", Severity.ERROR)
                if (facts.lzw) add("lzw", Severity.ERROR)
                if (!facts.hasXmp) add("xmp", Severity.ERROR)
            }
            PreflightProfile.PRINT -> {
                if (pf.hairlines > 0) add("hairline", Severity.WARNING, pf.hairlines, pf.hairlinePages)
                if (pf.overprint > 0) add("overprint", Severity.INFO, pf.overprint)
                if (pf.spotColours.isNotEmpty()) add("spot", Severity.INFO, pf.spotColours.size, detail = pf.spotColours.joinToString())
                if (pf.lowPpiPages.isNotEmpty()) add("ppi_low", Severity.WARNING, pf.lowPpiPages.size, pf.lowPpiPages, detail = "%.0f–%.0f ppi".format(pf.minPpi, pf.maxPpi))
                if (pf.invisibleText > 0) add("invisible", Severity.INFO, pf.invisibleText)
                if (pf.courier > 0) add("courier", Severity.WARNING, pf.courier)
                if (facts.fontsNotEmbedded.isNotEmpty()) add("fonts", Severity.ERROR, facts.fontsNotEmbedded.size)
                facts.transparencyPages.filter { it in inRange }.takeIf { it.isNotEmpty() }?.let { add("transparency", Severity.INFO, it.size, it) }
                facts.cmykPages.filter { it in inRange }.takeIf { it.isNotEmpty() }?.let { add("cmyk", Severity.INFO, it.size, it) }
            }
            PreflightProfile.VERSION -> {
                val need = minVersion(facts.transparencyPages.isNotEmpty(), pf.layers, pf.jpx, pf.openType, pf.aesBits, facts.embeddedFiles > 0)
                add("v_needed", if (facts.version + 0.001f < need) Severity.ERROR else Severity.INFO, detail = "%.1f / %.1f".format(java.util.Locale.US, need, facts.version))
                if (facts.version > 1.7f) add("version", Severity.WARNING, detail = facts.version.toString())
            }
            PreflightProfile.ZUGFERD -> out += zugferd(doc, claims)
        }
        return PreflightReport(facts, out)
    }

    /** Extra PDF/A rules of a level over the PDF/A-2b preflight. */
    fun levelReport(facts: PdfFacts, pf: PrintFacts, level: PdfALevel, fixedCodes: Set<String> = emptySet()): PreflightReport {
        val base = PdfA.report(facts, fixedCodes).findings.toMutableList()
        fun put(code: String, sev: Severity, n: Int = 0, pages: List<Int> = emptyList()) { base.removeAll { it.code == code }; base += Finding(code, sev, n, pages, code in fixedCodes) }
        if (level.strict1) {
            if (facts.transparencyPages.isNotEmpty()) put("transparency", Severity.ERROR, facts.transparencyPages.size, facts.transparencyPages.sorted())
            if (pf.layers) put("a_layers", Severity.ERROR)
            if (facts.version > 1.4f) put("a_version14", Severity.ERROR)
            if (pf.jpx) put("a_jpx", Severity.ERROR)
        }
        if (level.needsUnicode && facts.fontsWithoutUnicode.isNotEmpty()) put("unicode", Severity.ERROR, facts.fontsWithoutUnicode.size)
        if (level.needsTags && !pf.tagged) put("a_tags", Severity.ERROR)
        if (level.anyAttachments) base.removeAll { it.code == "attachments" }
        facts.pdfaClaim?.let { c -> if (!c.startsWith("PDF/A-${level.part}")) put("a_claim_other", Severity.WARNING) }
        return PreflightReport(facts, base)
    }

    /** Factur-X / ZUGFeRD: the invoice XML attachment, its relationship and the XMP extension. */
    fun zugferd(doc: PDDocument, claims: Claims): List<Finding> {
        val out = ArrayList<Finding>()
        val names = setOf("factur-x.xml", "zugferd-invoice.xml", "zugferd_invoice.xml", "xrechnung.xml", "order-x.xml")
        val att = Embed.all(doc).entries.firstOrNull { (k, s) -> (s.fileUnicode ?: s.file ?: k).lowercase() in names }
        if (att == null) out += Finding("zf_xml", Severity.ERROR)
        else {
            val rel = (att.value.cosObject.getDictionaryObject(COSName.getPDFName("AFRelationship")) as? COSName)?.name
            if (rel !in setOf("Alternative", "Data", "Source")) out += Finding("zf_relationship", Severity.ERROR, detail = rel ?: "—")
            val af = deref(doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("AF"))) as? COSArray
            if (af == null || (0 until af.size()).none { deref(af.get(it)) === att.value.cosObject }) out += Finding("zf_af", Severity.ERROR)
        }
        if (claims.facturX == null) out += Finding("zf_xmp", Severity.ERROR)
        if (claims.pdfa?.startsWith("PDF/A-3") != true) out += Finding("zf_pdfa3", Severity.ERROR, detail = claims.pdfa ?: "—")
        if (out.isEmpty()) out += Finding("zf_ok", Severity.INFO, detail = claims.facturX.orEmpty())
        return out
    }

    /** PDF/A-3: every attachment gets /AFRelationship and is listed in the catalog /AF. */
    fun associateAttachments(doc: PDDocument) {
        val all = Embed.all(doc); if (all.isEmpty()) return
        val af = COSArray()
        all.values.forEach { s ->
            if (!s.cosObject.containsKey(COSName.getPDFName("AFRelationship"))) s.cosObject.setItem(COSName.getPDFName("AFRelationship"), COSName.getPDFName("Unspecified"))
            af.add(s.cosObject)
        }
        doc.documentCatalog.cosObject.setItem(COSName.getPDFName("AF"), af)
    }

    /** Report as plain text / XML / HTML ([label] = finding title). Pure. */
    fun reportText(name: String, profile: String, r: PreflightReport, label: (String) -> String, format: String): String {
        fun sev(f: Finding) = if (f.fixed) "OK" else f.severity.name
        return when (format) {
            "xml" -> buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<preflight file=\"").append(XmpWriter.esc(name)).append("\" profile=\"").append(XmpWriter.esc(profile)).append("\" errors=\"").append(r.errors).append("\">\n")
                r.findings.forEach { f -> append("  <finding code=\"").append(f.code).append("\" severity=\"").append(sev(f)).append("\" count=\"").append(f.count).append("\" pages=\"")
                    .append(f.pages.joinToString(",") { "${it + 1}" }).append("\">").append(XmpWriter.esc(label(f.code))).append("</finding>\n") }
                append("</preflight>\n")
            }
            "html" -> buildString {
                append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>").append(XmpWriter.esc(name)).append("</title><style>body{font-family:sans-serif}td{border-bottom:1px solid #ddd;padding:4px}.ERROR{color:#b71c1c}.WARNING{color:#e65100}.OK{color:#1b5e20}</style></head><body>")
                append("<h1>").append(XmpWriter.esc(profile)).append("</h1><p>").append(XmpWriter.esc(name)).append("</p><table>")
                r.findings.forEach { f -> append("<tr><td class=\"").append(sev(f)).append("\">").append(sev(f)).append("</td><td>").append(XmpWriter.esc(label(f.code)))
                    if (f.count > 0) append(" (").append(f.count).append(')'); append("</td><td>").append(f.pages.take(30).joinToString(", ") { "${it + 1}" }).append("</td></tr>") }
                append("</table></body></html>")
            }
            else -> buildString {
                append(profile).append(" — ").append(name).append('\n')
                r.findings.forEach { f -> append('[').append(sev(f)).append("] ").append(label(f.code)); if (f.count > 0) append(" (").append(f.count).append(')')
                    if (f.pages.isNotEmpty()) append(" p. ").append(f.pages.take(30).joinToString(",") { "${it + 1}" }); append('\n') }
            }
        }
    }
}
