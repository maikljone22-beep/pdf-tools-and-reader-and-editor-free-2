package com.smartdocs.editor.core.convert

import com.smartdocs.editor.core.convert.OoxmlZip.Companion.REL_DOC
import com.smartdocs.editor.core.convert.OoxmlZip.Companion.XML_HEAD
import com.smartdocs.editor.core.convert.OoxmlZip.Companion.emu
import java.io.Closeable
import java.io.OutputStream

/**
 * Streaming PPTX writer: one slide per PDF page at the page's aspect ratio.
 * Slides are either a full-bleed page picture ([addImageSlide]) or editable content ([addEditableSlide]):
 * text boxes positioned over the original lines (font size, bold, RTL alignment kept), real PowerPoint
 * tables for detected tables, and the page's images at their positions.
 */
class PptxBuilder(out: OutputStream, private val title: String, firstPageW: Float, firstPageH: Float) : Closeable {

    private val zip = OoxmlZip(out)
    private var slides = 0
    private var media = 0
    private val slideW: Long
    private val slideH: Long

    init {
        // PowerPoint accepts 1in..56in per side: scale the page uniformly into that range
        var w = emu(firstPageW.coerceAtLeast(10f)); var h = emu(firstPageH.coerceAtLeast(10f))
        val max = 51206400L; val min = 914400L
        val down = minOf(1.0, max.toDouble() / maxOf(w, h))
        w = (w * down).toLong(); h = (h * down).toLong()
        val up = maxOf(1.0, min.toDouble() / minOf(w, h))
        slideW = minOf(max, (w * up).toLong()); slideH = minOf(max, (h * up).toLong())
    }

    private class Frame(val s: Double, val ox: Double, val oy: Double)

    private fun frame(pw: Float, ph: Float): Frame {
        val sx = slideW / (pw * 12700.0); val sy = slideH / (ph * 12700.0)
        val s = minOf(sx, sy)
        return Frame(s, (slideW - pw * 12700.0 * s) / 2, (slideH - ph * 12700.0 * s) / 2)
    }

    private fun Frame.x(pt: Float) = Math.round(ox + pt * 12700.0 * s)
    private fun Frame.y(pt: Float) = Math.round(oy + pt * 12700.0 * s)
    private fun Frame.d(pt: Float) = Math.round(pt * 12700.0 * s).coerceAtLeast(12700L)

    private fun addMedia(bytes: ByteArray, ext: String): String {
        media++
        val e = if (ext == "png") "png" else "jpeg"
        val name = "image$media.$e"
        zip.bytes("ppt/media/$name", bytes)
        return name
    }

    /** A slide that is just the page picture (exact look, not editable). */
    fun addImageSlide(bytes: ByteArray, ext: String, pageW: Float, pageH: Float) {
        val f = frame(pageW, pageH)
        val img = addMedia(bytes, ext)
        val sb = StringBuilder()
        pic(sb, 2, "rId2", f.x(0f), f.y(0f), f.d(pageW), f.d(pageH))
        writeSlide(sb, listOf(img))
    }

    /** Editable slide built from the analysed page. */
    private var pageHasFills = false

    fun addEditableSlide(page: PageLayout) {
        pageHasFills = page.hasFills
        val f = frame(page.width, page.height)
        val sb = StringBuilder()
        val imgs = ArrayList<String>()
        var id = 2
        // images first so text stays on top
        page.blocks.filterIsInstance<ImageBlock>().forEach { b ->
            imgs += addMedia(b.image.bytes, b.image.ext)
            pic(sb, id++, "rId${imgs.size + 1}", f.x(b.rect.x0), f.y(b.rect.y0), f.d(b.rect.w), f.d(b.rect.h))
        }
        for (b in page.blocks) when (b) {
            is ParagraphBlock -> textBox(sb, id++, b, f)
            is TableBlock -> table(sb, id++, b, f)
            else -> Unit
        }
        writeSlide(sb, imgs)
    }

    private fun pic(sb: StringBuilder, id: Int, rel: String, x: Long, y: Long, cx: Long, cy: Long) {
        sb.append("<p:pic><p:nvPicPr><p:cNvPr id=\"").append(id).append("\" name=\"Picture ").append(id).append("\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>")
            .append("<p:blipFill><a:blip r:embed=\"").append(rel).append("\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>")
            .append("<p:spPr><a:xfrm><a:off x=\"").append(x).append("\" y=\"").append(y).append("\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm>")
            .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>")
    }

    private fun sz(fs: Float, f: Frame): Int = Math.round(fs * f.s * 100).toInt().coerceIn(100, 400000)

    private fun runs(sb: StringBuilder, runs: List<TextRun>, rtl: Boolean, f: Frame) {
        for (r in runs) {
            sb.append("<a:r><a:rPr lang=\"").append(if (rtl) "ar-SA" else "en-US").append("\" sz=\"").append(sz(r.size, f)).append('"')
            if (r.bold) sb.append(" b=\"1\"")
            if (r.italic) sb.append(" i=\"1\"")
            if (r.underline) sb.append(" u=\"sng\"")
            if (r.strike) sb.append(" strike=\"sngStrike\"")
            val latin = BidiText.xml(r.font ?: FontMap.ARIAL)
            val cs = BidiText.xml(FontMap.complexScript(r.font))
            sb.append(" dirty=\"0\"><a:solidFill><a:srgbClr val=\"").append(Decorations.hex(r.color)).append("\"/></a:solidFill>")
            r.bg?.let { sb.append("<a:highlight><a:srgbClr val=\"").append(Decorations.hex(it)).append("\"/></a:highlight>") }
            sb.append("<a:latin typeface=\"").append(latin).append("\"/><a:cs typeface=\"").append(cs).append("\"/></a:rPr><a:t>")
                .append(BidiText.xml(r.text)).append("</a:t></a:r>")
        }
    }

    private fun algn(a: Align, rtl: Boolean) = when (a) {
        Align.CENTER -> "ctr"
        Align.START -> if (rtl) "r" else "l"
        Align.END -> if (rtl) "l" else "r"
    }

    private fun textBox(sb: StringBuilder, id: Int, b: ParagraphBlock, f: Frame) {
        val r = b.rect
        val pad = b.fontSize * 0.15f
        val pitch = if (b.lines.size > 1) (b.lines.last().baseline - b.lines.first().baseline) / (b.lines.size - 1) else 0f
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(id).append("\" name=\"TextBox ").append(id).append("\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>")
            .append("<p:spPr><a:xfrm><a:off x=\"").append(f.x(r.x0 - pad)).append("\" y=\"").append(f.y(r.y0 - pad)).append("\"/><a:ext cx=\"").append(f.d(r.w + 2 * pad))
            .append("\" cy=\"").append(f.d(r.h + 2 * pad)).append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>")
            .append(b.shade?.let { "<a:solidFill><a:srgbClr val=\"" + Decorations.hex(it) + "\"/></a:solidFill>" } ?: "<a:noFill/>").append("</p:spPr>")
            .append("<p:txBody><a:bodyPr wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" rtlCol=\"0\" anchor=\"t\"><a:noAutofit/></a:bodyPr><a:lstStyle/>")
        for (line in b.lines) {
            sb.append("<a:p><a:pPr algn=\"").append(algn(b.align, b.rtl)).append('"')
            if (b.rtl) sb.append(" rtl=\"1\"")
            sb.append('>')
            if (pitch > 0f && pitch < 4f * b.fontSize) sb.append("<a:lnSpc><a:spcPts val=\"").append(Math.round(pitch * f.s * 100).toInt().coerceIn(0, 158400)).append("\"/></a:lnSpc>")
            sb.append("</a:pPr>")
            runs(sb, TextRuns.of(line), b.rtl, f)
            sb.append("</a:p>")
        }
        sb.append("</p:txBody></p:sp>")
    }

    private fun table(sb: StringBuilder, id: Int, t: TableBlock, f: Frame) {
        val nc = t.cols; val nr = t.rows
        val header = LayoutAnalyzer.headerLikely(t)
        // columns stay in visual order (no tblPr@rtl: LibreOffice and some viewers ignore it); RTL is per paragraph
        val primary = HashMap<Long, TableCell>()
        val coverH = HashSet<Long>(); val coverV = HashSet<Long>()
        fun key(r: Int, c: Int) = (r.toLong() shl 20) or c.toLong()
        for (c in t.cells) {
            val l = c.col
            primary[key(c.row, l)] = c
            for (dr in 0 until c.rowSpan) for (dc in 0 until c.colSpan) {
                if (dr == 0 && dc == 0) continue
                if (dc > 0) coverH += key(c.row + dr, l + dc)
                if (dr > 0) coverV += key(c.row + dr, l + dc)
            }
        }
        val widths = (0 until nc).map { k -> f.d(t.colX[k + 1] - t.colX[k]) }
        val bodySize = t.cells.flatMap { it.lines }.map { it.fontSize }.sorted().let { if (it.isEmpty()) 11f else it[it.size / 2] }
        sb.append("<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"").append(id).append("\" name=\"Table ").append(id).append("\"/>")
            .append("<p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>")
            .append("<p:xfrm><a:off x=\"").append(f.x(t.colX.first())).append("\" y=\"").append(f.y(t.rowY.first())).append("\"/><a:ext cx=\"").append(widths.sum())
            .append("\" cy=\"").append(f.d(t.rowY.last() - t.rowY.first())).append("\"/></p:xfrm>")
            .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl><a:tblPr")
        if (header) sb.append(" firstRow=\"1\"")
        sb.append("/><a:tblGrid>")
        widths.forEach { sb.append("<a:gridCol w=\"").append(it).append("\"/>") }
        sb.append("</a:tblGrid>")
        for (r in 0 until nr) {
            sb.append("<a:tr h=\"").append(f.d(t.rowY[r + 1] - t.rowY[r])).append("\">")
            for (c in 0 until nc) {
                val k = key(r, c)
                val cell = primary[k]
                sb.append("<a:tc")
                if (cell != null) {
                    if (cell.colSpan > 1) sb.append(" gridSpan=\"").append(cell.colSpan).append('"')
                    if (cell.rowSpan > 1) sb.append(" rowSpan=\"").append(cell.rowSpan).append('"')
                }
                if (k in coverH) sb.append(" hMerge=\"1\"")
                if (k in coverV) sb.append(" vMerge=\"1\"")
                sb.append("><a:txBody><a:bodyPr/><a:lstStyle/>")
                val lines = cell?.lines.orEmpty()
                if (lines.isEmpty()) sb.append("<a:p><a:endParaRPr lang=\"en-US\" sz=\"").append(sz(bodySize, f)).append("\" dirty=\"0\"/></a:p>")
                for (line in lines) {
                    sb.append("<a:p><a:pPr algn=\"").append(if (line.rtl) "r" else "l").append('"')
                    if (line.rtl) sb.append(" rtl=\"1\"")
                    sb.append("/>")
                    val rr = TextRuns.of(line).map { if (header && r == 0) it.copy(bold = true) else it }
                    runs(sb, rr, line.rtl, f)
                    sb.append("</a:p>")
                }
                sb.append("</a:txBody><a:tcPr marL=\"45720\" marR=\"45720\" marT=\"22860\" marB=\"22860\">")
                for (side in listOf("lnL", "lnR", "lnT", "lnB")) {
                    if (t.ruled) sb.append("<a:").append(side).append(" w=\"9525\"><a:solidFill><a:srgbClr val=\"7F7F7F\"/></a:solidFill></a:").append(side).append('>')
                    else sb.append("<a:").append(side).append(" w=\"0\"><a:noFill/></a:").append(side).append('>')
                }
                val fill = cell?.fill?.let { Decorations.hex(it) } ?: if (header && r == 0 && !pageHasFills) "DCE6F1" else null
                if (fill != null) sb.append("<a:solidFill><a:srgbClr val=\"").append(fill).append("\"/></a:solidFill>") else sb.append("<a:noFill/>")
                sb.append("</a:tcPr></a:tc>")
            }
            sb.append("</a:tr>")
        }
        sb.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>")
    }

    private fun writeSlide(shapes: StringBuilder, images: List<String>) {
        slides++
        val sb = StringBuilder(XML_HEAD).append("<p:sld ").append(PptxTemplates.NS).append("><p:cSld><p:spTree>").append(PptxTemplates.GROUP_HEAD)
            .append(shapes).append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
        zip.text("ppt/slides/slide$slides.xml", sb)
        val rels = ArrayList<Triple<String, String, String>>()
        rels += Triple("rId1", "$REL_DOC/slideLayout", "../slideLayouts/slideLayout1.xml")
        images.forEachIndexed { i, img -> rels += Triple("rId${i + 2}", "$REL_DOC/image", "../media/$img") }
        zip.text("ppt/slides/_rels/slide$slides.xml.rels", OoxmlZip.rels(*rels.toTypedArray()))
    }

    fun finish() {
        if (slides == 0) writeSlide(StringBuilder(), emptyList())
        val ct = StringBuilder(XML_HEAD).append("<Types xmlns=\"").append(OoxmlZip.NS_CT).append("\">")
            .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            .append("<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>")
            .append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
            .append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
            .append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
            .append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
            .append("<Override PartName=\"/ppt/presProps.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presProps+xml\"/>")
            .append("<Override PartName=\"/ppt/viewProps.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.viewProps+xml\"/>")
            .append("<Override PartName=\"/ppt/tableStyles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.tableStyles+xml\"/>")
            .append("<Override PartName=\"/docProps/core.xml\" ContentType=\"").append(OoxmlZip.CT_CORE).append("\"/>")
        for (i in 1..slides) ct.append("<Override PartName=\"/ppt/slides/slide").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
        ct.append("</Types>")
        zip.text("[Content_Types].xml", ct)
        zip.text("_rels/.rels", OoxmlZip.rels(
            Triple("rId1", "$REL_DOC/officeDocument", "ppt/presentation.xml"),
            Triple("rId2", OoxmlZip.REL_CORE, "docProps/core.xml"),
        ))
        zip.text("docProps/core.xml", OoxmlZip.coreProps(title))
        val p = StringBuilder(XML_HEAD).append("<p:presentation ").append(PptxTemplates.NS).append(" saveSubsetFonts=\"1\">")
            .append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>")
        for (i in 1..slides) p.append("<p:sldId id=\"").append(255 + i).append("\" r:id=\"rId").append(i + 1).append("\"/>")
        p.append("</p:sldIdLst><p:sldSz cx=\"").append(slideW).append("\" cy=\"").append(slideH).append("\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
        zip.text("ppt/presentation.xml", p)
        val rels = ArrayList<Triple<String, String, String>>()
        rels += Triple("rId1", "$REL_DOC/slideMaster", "slideMasters/slideMaster1.xml")
        for (i in 1..slides) rels += Triple("rId${i + 1}", "$REL_DOC/slide", "slides/slide$i.xml")
        rels += Triple("rId${slides + 2}", "$REL_DOC/presProps", "presProps.xml")
        rels += Triple("rId${slides + 3}", "$REL_DOC/viewProps", "viewProps.xml")
        rels += Triple("rId${slides + 4}", "$REL_DOC/theme", "theme/theme1.xml")
        rels += Triple("rId${slides + 5}", "$REL_DOC/tableStyles", "tableStyles.xml")
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

    override fun close() { runCatching { zip.close() } }
}
