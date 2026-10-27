package com.smartdocs.editor.core.ai6

import android.content.Context
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.ai5.AiJson
import com.smartdocs.editor.core.ai5.AiLang
import com.smartdocs.editor.core.ai5.DocIndex
import com.smartdocs.editor.core.ai5.StudyGen
import com.smartdocs.editor.core.convert.BidiText
import com.smartdocs.editor.core.convert.OoxmlZip
import com.smartdocs.editor.core.convert.OoxmlZip.Companion.REL_DOC
import com.smartdocs.editor.core.convert.OoxmlZip.Companion.XML_HEAD
import com.smartdocs.editor.core.convert.PptxTemplates
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.OutputStream

/** One generated slide: title, bullet points, speaker notes and the source pages (1-based, as the model wrote them). */
data class SlideSpec(val title: String, val bullets: List<String>, val notes: String = "", val pages: List<Int> = emptyList())

data class SlideDeck(val title: String, val slides: List<SlideSpec>) {
    val rtl: Boolean get() = SlidesGen.isArabic(title + slides.joinToString(" ") { it.title })
}

/**
 * ai6 (B13): "generate presentation" — one request over a page-ordered spread of the document(s) (the same
 * material as the study tools), strict JSON {title, slides:[{title, bullets[], notes, page}]}, then PPTX / PDF /
 * Markdown writers. [SlideRender] draws the same deck for the PDF and the narrated video (B15).
 */
object SlidesGen {
    fun isArabic(s: String) = s.any { it.code in 0x0600..0x06FF || it.code in 0xFB50..0xFEFF }

    fun prompt(count: Int, language: String, focus: String, material: String): String {
        val lang = AiLang.name(language.ifBlank { AiLang.detect(material).ifBlank { "ar" } })
        return "اكتب بلغة: $lang. أنشئ عرضاً تقديمياً من $count شريحة تقريباً يلخص المستند بترتيب منطقي: شريحة عنوان، ثم المحاور الرئيسية، ثم خلاصة. " +
            (if (focus.isNotBlank()) "ركّز على: $focus. " else "") +
            "لكل شريحة عنوان قصير و3 إلى 6 نقاط مختصرة (لا تزيد النقطة على 15 كلمة) وملاحظات للمتحدث في جملتين إلى أربع جمل، ورقم الصفحة المصدر. " +
            "أعد JSON فقط: {\"title\":\"...\",\"slides\":[{\"title\":\"...\",\"bullets\":[\"...\"],\"notes\":\"...\",\"page\":3}]}\n\n" + material
    }

    /** Lenient parse; null when the answer holds no usable slide. */
    fun parse(answer: String, fallbackTitle: String = ""): SlideDeck? {
        val el = AiJson.extract(answer) ?: return null
        val root = el as? JsonObject
        val arr: JsonArray = (root?.let { AiJson.arr(it, "slides", "Slides", "شرائح") } ?: (el as? JsonArray)) ?: return null
        val slides = arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val title = AiJson.str(o, "title", "heading", "عنوان").orEmpty()
            val bullets = AiJson.strings(AiJson.arr(o, "bullets", "points", "items", "content"))
            if (title.isBlank() && bullets.isEmpty()) return@mapNotNull null
            SlideSpec(title, bullets, AiJson.str(o, "notes", "speaker_notes", "speakerNotes").orEmpty(), AiJson.pages(o["page"] ?: o["pages"]))
        }
        if (slides.isEmpty()) return null
        return SlideDeck(AiJson.str(root, "title").orEmpty().ifBlank { fallbackTitle.ifBlank { slides.first().title } }, slides)
    }

    suspend fun generate(ctx: Context, client: AiClient, indexes: List<DocIndex>, count: Int = 10, language: String = "", focus: String = ""): SlideDeck {
        val m = StudyGen.material(indexes, focus)
        val answer = AiCalls.complete(ctx, client, listOf(ChatMessage("system", AiCalls.SYSTEM), ChatMessage("user", prompt(count, language, focus, m))))
        return parse(answer, indexes.firstOrNull()?.name?.substringBeforeLast('.').orEmpty()) ?: throw IllegalStateException("parse")
    }

    fun markdown(deck: SlideDeck): String = buildString {
        append("# ").append(deck.title).append("\n\n")
        deck.slides.forEachIndexed { i, s ->
            append("## ").append(i + 1).append(". ").append(s.title).append('\n')
            if (s.pages.isNotEmpty()) append("_(").append(s.pages.joinToString(", ") { "p. $it" }).append(")_\n")
            append('\n')
            s.bullets.forEach { b -> append("- ").append(b).append('\n') }
            if (s.notes.isNotBlank()) append("\n> ").append(s.notes.replace("\n", "\n> ")).append('\n')
            append('\n')
        }
    }.trimEnd() + "\n"

    /** Text spoken for a slide in the narrated video: the notes, else the title and bullets. */
    fun narration(s: SlideSpec): String = s.notes.ifBlank { (listOf(s.title) + s.bullets).joinToString(". ") }
}

/**
 * Minimal PowerPoint writer for a [SlideDeck]: 16:9 slides with a coloured title band, a title text box and a
 * bulleted body (right-aligned + rtl for Arabic). Speaker notes are not written (they need a notes master);
 * they are kept in the Markdown and PDF outputs.
 */
object SlidesPptx {
    const val W = 12192000L
    const val H = 6858000L
    private const val ACCENT = "1F4E79"

    private fun sp(id: Int, name: String, x: Long, y: Long, cx: Long, cy: Long, fill: String?, body: String): String =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
            (if (fill != null) "<a:solidFill><a:srgbClr val=\"$fill\"/></a:solidFill>" else "<a:noFill/>") + "</p:spPr>" + body + "</p:sp>"

    private fun para(text: String, size: Int, rtl: Boolean, color: String, bold: Boolean, bullet: Boolean, center: Boolean = false): String {
        val algn = if (center) "ctr" else if (rtl) "r" else "l"
        val ppr = "<a:pPr algn=\"$algn\"" + (if (rtl) " rtl=\"1\"" else "") + (if (bullet) " marL=\"342900\" indent=\"-342900\"" else "") + ">" +
            (if (bullet) "<a:buFont typeface=\"Arial\"/><a:buChar char=\"&#8226;\"/>" else "<a:buNone/>") + "</a:pPr>"
        val lang = if (rtl) "ar-SA" else "en-US"
        return "<a:p>$ppr<a:r><a:rPr lang=\"$lang\" sz=\"$size\"" + (if (bold) " b=\"1\"" else "") + " dirty=\"0\"><a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill>" +
            "<a:latin typeface=\"Arial\"/><a:cs typeface=\"Arial\"/></a:rPr><a:t>" + BidiText.xml(text) + "</a:t></a:r></a:p>"
    }

    private fun txBody(paras: String, anchor: String = "t") =
        "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"91440\" tIns=\"45720\" rIns=\"91440\" bIns=\"45720\" anchor=\"$anchor\"><a:normAutofit/></a:bodyPr><a:lstStyle/>$paras</p:txBody>"

    /** Shapes of one slide ([index] 0 = cover when [cover]). */
    fun slideXml(s: SlideSpec, rtl: Boolean, cover: Boolean): String {
        val shapes = StringBuilder()
        if (cover) {
            shapes.append(sp(2, "Band", 0, 0, W, H, ACCENT, txBody("<a:p><a:endParaRPr lang=\"en-US\"/></a:p>")))
            val body = para(s.title, 4400, rtl, "FFFFFF", true, false, center = true) + s.bullets.take(2).joinToString("") { para(it, 2000, rtl, "DDEBF7", false, false, center = true) }
            shapes.append(sp(3, "Title", 609600, 1828800, W - 1219200, 3200400, null, txBody(body, "ctr")))
        } else {
            shapes.append(sp(2, "Band", 0, 0, W, 1188720, ACCENT, txBody(para(s.title, 3200, rtl, "FFFFFF", true, false), "ctr")))
            val body = s.bullets.joinToString("") { para(it, 2200, rtl, "222222", false, true) }.ifEmpty { "<a:p><a:endParaRPr lang=\"en-US\"/></a:p>" }
            shapes.append(sp(3, "Body", 457200, 1463040, W - 914400, H - 1920240, null, txBody(body)))
        }
        return XML_HEAD + "<p:sld " + PptxTemplates.NS + "><p:cSld><p:spTree>" + PptxTemplates.GROUP_HEAD + shapes +
            "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }

    /** Write the deck: a cover slide (deck title + first slide's subtitle) followed by one slide per [SlideSpec]. */
    fun write(deck: SlideDeck, out: OutputStream) {
        val zip = OoxmlZip(out)
        val rtl = deck.rtl
        val all = listOf(SlideSpec(deck.title, emptyList())) + deck.slides
        all.forEachIndexed { i, s ->
            zip.text("ppt/slides/slide${i + 1}.xml", slideXml(s, rtl, cover = i == 0))
            zip.text("ppt/slides/_rels/slide${i + 1}.xml.rels", OoxmlZip.rels(Triple("rId1", "$REL_DOC/slideLayout", "../slideLayouts/slideLayout1.xml")))
        }
        val n = all.size
        val ct = StringBuilder(XML_HEAD).append("<Types xmlns=\"").append(OoxmlZip.NS_CT).append("\">")
            .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            .append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
            .append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
            .append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
            .append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
            .append("<Override PartName=\"/ppt/presProps.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presProps+xml\"/>")
            .append("<Override PartName=\"/ppt/viewProps.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.viewProps+xml\"/>")
            .append("<Override PartName=\"/ppt/tableStyles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.tableStyles+xml\"/>")
            .append("<Override PartName=\"/docProps/core.xml\" ContentType=\"").append(OoxmlZip.CT_CORE).append("\"/>")
        for (i in 1..n) ct.append("<Override PartName=\"/ppt/slides/slide").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
        ct.append("</Types>")
        zip.text("[Content_Types].xml", ct)
        zip.text("_rels/.rels", OoxmlZip.rels(Triple("rId1", "$REL_DOC/officeDocument", "ppt/presentation.xml"), Triple("rId2", OoxmlZip.REL_CORE, "docProps/core.xml")))
        zip.text("docProps/core.xml", OoxmlZip.coreProps(deck.title))
        val p = StringBuilder(XML_HEAD).append("<p:presentation ").append(PptxTemplates.NS).append(" saveSubsetFonts=\"1\">")
            .append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>")
        for (i in 1..n) p.append("<p:sldId id=\"").append(255 + i).append("\" r:id=\"rId").append(i + 1).append("\"/>")
        p.append("</p:sldIdLst><p:sldSz cx=\"").append(W).append("\" cy=\"").append(H).append("\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
        zip.text("ppt/presentation.xml", p)
        val rels = ArrayList<Triple<String, String, String>>()
        rels += Triple("rId1", "$REL_DOC/slideMaster", "slideMasters/slideMaster1.xml")
        for (i in 1..n) rels += Triple("rId${i + 1}", "$REL_DOC/slide", "slides/slide$i.xml")
        rels += Triple("rId${n + 2}", "$REL_DOC/presProps", "presProps.xml")
        rels += Triple("rId${n + 3}", "$REL_DOC/viewProps", "viewProps.xml")
        rels += Triple("rId${n + 4}", "$REL_DOC/theme", "theme/theme1.xml")
        rels += Triple("rId${n + 5}", "$REL_DOC/tableStyles", "tableStyles.xml")
        zip.text("ppt/_rels/presentation.xml.rels", OoxmlZip.rels(*rels.toTypedArray()))
        zip.text("ppt/slideMasters/slideMaster1.xml", PptxTemplates.MASTER)
        zip.text("ppt/slideMasters/_rels/slideMaster1.xml.rels", OoxmlZip.rels(
            Triple("rId1", "$REL_DOC/slideLayout", "../slideLayouts/slideLayout1.xml"),
            Triple("rId2", "$REL_DOC/theme", "../theme/theme1.xml"),
        ))
        zip.text("ppt/slideLayouts/slideLayout1.xml", PptxTemplates.LAYOUT)
        zip.text("ppt/slideLayouts/_rels/slideLayout1.xml.rels", OoxmlZip.rels(Triple("rId1", "$REL_DOC/slideMaster", "../slideMasters/slideMaster1.xml")))
        zip.text("ppt/theme/theme1.xml", PptxTemplates.THEME)
        zip.text("ppt/presProps.xml", PptxTemplates.PRES_PROPS)
        zip.text("ppt/viewProps.xml", PptxTemplates.VIEW_PROPS)
        zip.text("ppt/tableStyles.xml", PptxTemplates.TABLE_STYLES)
        zip.close()
    }
}
