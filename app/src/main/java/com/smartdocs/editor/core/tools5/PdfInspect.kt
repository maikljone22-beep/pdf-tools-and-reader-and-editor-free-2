package com.smartdocs.editor.core.tools5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject

/** COS names used by the tools5 PDF code (created by name: safer than relying on every COSName constant). */
internal object N {
    fun n(s: String): COSName = COSName.getPDFName(s)
    val SMask = n("SMask"); val CA = n("CA"); val ca = n("ca"); val BM = n("BM"); val Group = n("Group"); val S = n("S")
    val Transparency = n("Transparency"); val JavaScript = n("JavaScript"); val JS = n("JS"); val OpenAction = n("OpenAction")
    val AA = n("AA"); val A = n("A"); val Names = n("Names"); val ToUnicode = n("ToUnicode"); val Encoding = n("Encoding")
    val Metadata = n("Metadata"); val OutputIntents = n("OutputIntents"); val EmbeddedFiles = n("EmbeddedFiles"); val AP = n("AP")
    val F = n("F"); val Subtype = n("Subtype"); val ColorSpace = n("ColorSpace"); val Filter = n("Filter"); val LZW = n("LZWDecode")
    val StructTreeRoot = n("StructTreeRoot"); val MarkInfo = n("MarkInfo"); val Marked = n("Marked"); val Lang = n("Lang")
    val ViewerPreferences = n("ViewerPreferences"); val DisplayDocTitle = n("DisplayDocTitle"); val Tabs = n("Tabs"); val K = n("K")
    val Alt = n("Alt"); val Pg = n("Pg"); val P = n("P"); val Type = n("Type"); val StructElem = n("StructElem"); val MCID = n("MCID")
    val ParentTree = n("ParentTree"); val ParentTreeNextKey = n("ParentTreeNextKey"); val StructParents = n("StructParents")
    val StructParent = n("StructParent"); val RoleMap = n("RoleMap"); val Obj = n("Obj"); val OBJR = n("OBJR"); val Nums = n("Nums")
    val TU = n("TU"); val Contents = n("Contents"); val Summary = n("Summary"); val Attr = n("A"); val Thumb = n("Thumb")
    val PieceInfo = n("PieceInfo"); val NeedAppearances = n("NeedAppearances"); val XFA = n("XFA"); val AcroForm = n("AcroForm")
    val Interpolate = n("Interpolate"); val TR = n("TR"); val TR2 = n("TR2"); val ID = n("ID")
}

internal fun COSBase?.deref(): COSBase? = if (this is COSObject) this.`object` else this

data class FontInfo(val name: String, val embedded: Boolean, val hasUnicode: Boolean, val subtype: String)

/** One pass over a PDF: what preflight, PDF/A, the optimizer and the accessibility checker need. */
data class PdfFacts(
    val version: Float,
    val encrypted: Boolean,
    val pages: Int,
    val fonts: List<FontInfo>,
    val transparencyPages: Set<Int>,
    val javascript: Boolean,
    val hasXmp: Boolean,
    val pdfaClaim: String?,
    val outputIntents: Int,
    val embeddedFiles: Int,
    val annotations: Int,
    val annotationsWithoutAppearance: Int,
    val multimedia: Int,
    val images: Int,
    val lzw: Boolean,
    val cmykPages: Set<Int>,
    val formFields: Int,
    val xfa: Boolean,
) {
    val fontsNotEmbedded get() = fonts.filter { !it.embedded }
    val fontsWithoutUnicode get() = fonts.filter { !it.hasUnicode }
}

object PdfInspect {

    private val mediaTypes = setOf("Screen", "Movie", "Sound", "RichMedia", "3D")

    fun inspect(doc: PDDocument): PdfFacts {
        val fonts = LinkedHashMap<String, FontInfo>()
        val transparency = HashSet<Int>(); val cmyk = HashSet<Int>()
        var images = 0; var lzw = false
        val seen = HashSet<COSBase>()

        fun streamLzw(s: COSStream) {
            val f = s.getDictionaryObject(N.Filter)
            if (f == N.LZW || (f is COSArray && (0 until f.size()).any { f.getObject(it) == N.LZW })) lzw = true
        }

        fun walk(res: PDResources?, page: Int, depth: Int) {
            if (res == null || depth > 12) return
            if (!seen.add(res.cosObject)) return
            runCatching {
                res.fontNames.forEach { fn ->
                    val font = runCatching { res.getFont(fn) }.getOrNull() ?: return@forEach
                    val key = font.name ?: fn.name
                    val d = font.cosObject
                    val sub = (d.getDictionaryObject(N.Subtype) as? COSName)?.name.orEmpty()
                    val enc = d.getDictionaryObject(N.Encoding)
                    val standardEnc = enc is COSName && enc.name in setOf("WinAnsiEncoding", "MacRomanEncoding", "StandardEncoding")
                    val unicode = d.containsKey(N.ToUnicode) || (sub != "Type0" && sub != "Type3" && (standardEnc || enc == null && font.isStandard14))
                    fonts.putIfAbsent(key, FontInfo(key, font.isEmbedded || sub == "Type3", unicode, sub))
                }
            }
            runCatching {
                res.extGStateNames.forEach { gn ->
                    val gs = res.getExtGState(gn)?.cosObject ?: return@forEach
                    val sm = gs.getDictionaryObject(N.SMask)
                    val ca = (gs.getDictionaryObject(N.ca) as? COSNumber)?.floatValue() ?: 1f
                    val CA = (gs.getDictionaryObject(N.CA) as? COSNumber)?.floatValue() ?: 1f
                    val bm = gs.getDictionaryObject(N.BM)
                    val blend = (bm is COSName && bm.name != "Normal" && bm.name != "Compatible")
                    if (sm is COSDictionary || ca < 1f || CA < 1f || blend) transparency += page
                }
            }
            runCatching {
                res.colorSpaceNames.forEach { cn -> if (runCatching { res.getColorSpace(cn).name }.getOrNull() == "DeviceCMYK") cmyk += page }
            }
            runCatching {
                res.xObjectNames.forEach { xn ->
                    when (val x = runCatching { res.getXObject(xn) }.getOrNull()) {
                        is PDImageXObject -> {
                            images++
                            if (x.cosObject.getDictionaryObject(N.SMask) is COSStream) transparency += page
                            if (runCatching { x.colorSpace.name }.getOrNull() == "DeviceCMYK") cmyk += page
                            streamLzw(x.cosObject)
                        }
                        is PDFormXObject -> {
                            val g = x.cosObject.getDictionaryObject(N.Group) as? COSDictionary
                            if (g?.getDictionaryObject(N.S) == N.Transparency) transparency += page
                            streamLzw(x.cosObject)
                            walk(x.resources, page, depth + 1)
                        }
                        else -> {}
                    }
                }
            }
        }

        var annots = 0; var noAp = 0; var media = 0; var js = false
        val catalog = doc.documentCatalog.cosObject
        doc.pages.forEachIndexed { i, page ->
            walk(page.resources, i, 0)
            val g = page.cosObject.getDictionaryObject(N.Group) as? COSDictionary
            if (g?.getDictionaryObject(N.S) == N.Transparency) transparency += i
            if (page.cosObject.containsKey(N.AA)) js = true
            runCatching { page.contentStreams.forEach { s -> streamLzw(s.cosObject) } }
            runCatching {
                page.annotations.forEach { a ->
                    annots++
                    val sub = a.subtype ?: ""
                    if (sub in mediaTypes) media++
                    if (sub != "Popup" && sub != "Link" && !a.cosObject.containsKey(N.AP)) noAp++
                    val act = a.cosObject.getDictionaryObject(N.A) as? COSDictionary
                    if (act?.getDictionaryObject(N.S) == N.JavaScript || a.cosObject.containsKey(N.AA)) js = true
                }
            }
        }
        val names = catalog.getDictionaryObject(N.Names) as? COSDictionary
        if (names?.containsKey(N.JavaScript) == true) js = true
        if (catalog.containsKey(N.AA)) js = true
        (catalog.getDictionaryObject(N.OpenAction) as? COSDictionary)?.let { if (it.getDictionaryObject(N.S) == N.JavaScript) js = true }
        val acro = catalog.getDictionaryObject(N.AcroForm) as? COSDictionary
        val fields = runCatching { doc.documentCatalog.acroForm?.fieldTree?.count() ?: 0 }.getOrDefault(0)
        val meta = runCatching { doc.documentCatalog.metadata?.toByteArray()?.let { String(it, Charsets.UTF_8) } }.getOrNull()
        val claim = meta?.let { m ->
            val part = Regex("""pdfaid:part(?:>|=")\s*(\d)""").find(m)?.groupValues?.get(1)
            val conf = Regex("""pdfaid:conformance(?:>|=")\s*([ABUabu])""").find(m)?.groupValues?.get(1)
            part?.let { "PDF/A-$it" + (conf?.lowercase() ?: "") }
        }
        val oi = (catalog.getDictionaryObject(N.OutputIntents) as? COSArray)?.size() ?: 0
        val ef = runCatching { Embed.all(doc).size }.getOrDefault(0)
        return PdfFacts(
            version = runCatching { doc.version }.getOrDefault(1.4f), encrypted = doc.isEncrypted, pages = doc.numberOfPages,
            fonts = fonts.values.toList(), transparencyPages = transparency, javascript = js, hasXmp = meta != null, pdfaClaim = claim,
            outputIntents = oi, embeddedFiles = ef, annotations = annots, annotationsWithoutAppearance = noAp, multimedia = media,
            images = images, lzw = lzw, cmykPages = cmyk, formFields = fields, xfa = acro?.containsKey(N.XFA) == true,
        )
    }
}
