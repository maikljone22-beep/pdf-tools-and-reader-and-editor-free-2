package com.smartdocs.editor.core.convert

import com.smartdocs.editor.core.convert.OoxmlZip.Companion.REL_DOC
import com.smartdocs.editor.core.convert.OoxmlZip.Companion.XML_HEAD
import com.smartdocs.editor.core.convert.OoxmlZip.Companion.emu
import com.smartdocs.editor.core.convert.OoxmlZip.Companion.twips
import java.io.Closeable
import java.io.OutputStream

/**
 * Streaming DOCX writer for analysed pages: reflowed paragraphs with bold/italic/size runs, headings
 * (Heading1-3 styles, so they show in Word's navigation pane), RTL paragraphs (`w:bidi` + `w:rtl`) for
 * Arabic, real Word tables (merged cells, header row, RTL tables via `w:bidiVisual`) and inline images.
 * A page break separates source pages. Runs keep colour, font family, underline, strike-through and highlight; PDF
 * fills become paragraph / cell shading; running headers and footers ([PageFurniture]) become a real Word header and
 * footer with the page number as a PAGE field.
 */
class DocxBuilder(
    out: OutputStream, private val title: String, private val furniture: PageFurniture = PageFurniture.NONE,
    /** Flowing text: no page breaks and no Word header / footer (running headers and footers are left out). */
    private val flowing0: Boolean = false,
    /**
     * Row 46 "precisely laid out": every paragraph and picture in a Word frame (framePr) and every table floating
     * (tblpPr), placed on the page where the PDF has it; page breaks between pages. Wins over [flowing0].
     */
    private val exact: Boolean = false,
    /** Detected running headers / footers are deleted instead of becoming the Word header / footer. */
    private val dropFurniture: Boolean = false,
    /** s6 "custom character spacing": points added between characters of every run (0 = as is). */
    private val charSpacing: Float = 0f,
    /** s6 "image anchoring": pictures float at their PDF position (anchored to the page) instead of sitting in line. */
    private val anchorImages: Boolean = false,
) : Closeable {
    private val flowing = flowing0 && !exact

    /** framePr of a block placed at [r] (PDF points, y down) on its page; a little wider so its lines do not wrap. */
    private fun frame(r: Rect): String {
        val w = ((r.x1 - r.x0) * 1.06f + 4f).coerceAtMost(pageW)
        return "<w:framePr w:w=\"" + OoxmlZip.twips(w) + "\" w:hRule=\"auto\" w:wrap=\"notBeside\" w:vAnchor=\"page\" w:hAnchor=\"page\" w:x=\"" +
            OoxmlZip.twips(r.x0.coerceAtLeast(0f)) + "\" w:y=\"" + OoxmlZip.twips(r.y0.coerceAtLeast(0f)) + "\"/>"
    }

    private val zip = OoxmlZip(out)
    private val body = StringBuilder()
    private val imageRels = ArrayList<Pair<String, String>>() // rId → media name
    private var pages = 0
    private var pageW = 595f; private var pageH = 842f
    private var marginL = 72f; private var marginR = 72f; private var marginT = 72f; private var marginB = 72f
    private var bodySize = 11f
    private var rtlVotes = 0
    private var picId = 0
    private var pageHasFills = false
    private var headerXml: String? = null
    private var footerXml: String? = null

    fun addPage(page: PageLayout) {
        if (pages == 0) setupFirstPage(page)
        pageHasFills = page.hasFills
        // a page break before every page but the first (the old else-branch of the footer test put one before the first
        // page and skipped the one before the page that first had a footer)
        if (pages > 0 && !flowing) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        if (!flowing && !dropFurniture) {
            if (headerXml == null && page.header.isNotEmpty()) headerXml = furniturePart("hdr", page.header, page)
            if (footerXml == null && page.footer.isNotEmpty()) footerXml = furniturePart("ftr", page.footer, page)
        }
        pages++
        if (page.rtl) rtlVotes++ else rtlVotes--
        for (b in page.blocks) when (b) {
            is ParagraphBlock -> paragraph(b)
            is TableBlock -> table(b)
            is ImageBlock -> image(b, page)
        }
    }

    private fun setupFirstPage(p: PageLayout) {
        pageW = p.width; pageH = p.height
        bodySize = Math.round(p.bodyFontSize * 2f) / 2f
        if (p.blocks.isNotEmpty()) {
            val x0 = p.blocks.minOf { it.rect.x0 }; val x1 = p.blocks.maxOf { it.rect.x1 }
            val y0 = p.blocks.minOf { it.rect.y0 }; val y1 = p.blocks.maxOf { it.rect.y1 }
            marginL = x0.coerceIn(18f, 72f); marginR = (pageW - x1).coerceIn(18f, 72f)
            marginT = y0.coerceIn(18f, 72f); marginB = (pageH - y1).coerceIn(18f, 72f)
        }
    }

    private val contentW get() = (pageW - marginL - marginR).coerceAtLeast(72f)

    private fun halfPts(fs: Float) = Math.round(fs * 2f).coerceIn(2, 3276)

    private fun runs(sb: StringBuilder, runs0: List<TextRun>, rtl: Boolean, forceBold: Boolean = false) {
        // a run is right-to-left only where it holds Arabic: Latin words, formulas and European numbers inside an Arabic
        // paragraph are left-to-right runs (with w:rtl on them Word ordered «CO2(g) + 2H2O(l)» and «(C = 12 ، H = 1)» wrongly)
        val runs = runs0.flatMap { r -> BidiText.scriptRuns(r.text, rtl).map { (t, isRtl) -> r.copy(text = t) to isRtl } }
        for ((r, runRtl) in runs) {
            val rp = StringBuilder()
            r.font?.let { f ->
                val cs = FontMap.complexScript(f)
                rp.append("<w:rFonts w:ascii=\"").append(BidiText.xml(f)).append("\" w:hAnsi=\"").append(BidiText.xml(f)).append("\" w:cs=\"").append(BidiText.xml(cs)).append("\"/>")
            }
            if (r.bold || forceBold) rp.append("<w:b/><w:bCs/>")
            if (r.italic) rp.append("<w:i/><w:iCs/>")
            if (r.strike) rp.append("<w:strike/>")
            if (r.color != 0) rp.append("<w:color w:val=\"").append(Decorations.hex(r.color)).append("\"/>")
            if (charSpacing != 0f) rp.append("<w:spacing w:val=\"").append(Math.round(charSpacing * 20f).coerceIn(-1580, 1580)).append("\"/>")   // twentieths of a point
            if (kotlin.math.abs(r.size - bodySize) >= 0.5f) { val hp = halfPts(r.size); rp.append("<w:sz w:val=\"").append(hp).append("\"/><w:szCs w:val=\"").append(hp).append("\"/>") }
            if (r.underline) rp.append("<w:u w:val=\"single\"/>")
            r.bg?.let { rp.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(Decorations.hex(it)).append("\"/>") }
            if (runRtl) rp.append("<w:rtl/>")
            sb.append("<w:r>")
            if (rp.isNotEmpty()) sb.append("<w:rPr>").append(rp).append("</w:rPr>")
            sb.append("<w:t xml:space=\"preserve\">").append(BidiText.xml(r.text)).append("</w:t></w:r>")
        }
    }

    private fun jc(a: Align): String? = when (a) {
        Align.START -> null
        Align.CENTER -> "center"
        // in a bidi paragraph Word's "right" is the trailing (left) edge, so END is "right" in both directions
        Align.END -> "right"
    }

    private fun paragraph(b: ParagraphBlock) {
        body.append("<w:p><w:pPr>")
        if (b.headingLevel in 1..3) body.append("<w:pStyle w:val=\"Heading").append(b.headingLevel).append("\"/>")
        if (exact) body.append(frame(b.rect))                // schema order: pStyle, framePr, shd, bidi, spacing, jc
        b.shade?.let { body.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(Decorations.hex(it)).append("\"/>") }
        if (b.rtl) body.append("<w:bidi/>")
        if (exact) body.append("<w:spacing w:before=\"0\" w:after=\"0\"/>")
        jc(b.align)?.let { body.append("<w:jc w:val=\"").append(it).append("\"/>") }
        body.append("</w:pPr>")
        runs(body, TextRuns.ofParagraph(b.lines), b.rtl)
        body.append("</w:p>")
    }

    private fun table(t: TableBlock) {
        val rtl = t.rtl
        val nc = t.cols
        val header = LayoutAnalyzer.headerLikely(t)
        fun lc(visualCol: Int, span: Int) = if (rtl) nc - visualCol - span else visualCol
        // logical column widths (twips), scaled into the text width
        val rawW = (0 until nc).map { k -> val v = if (rtl) nc - 1 - k else k; (t.colX[v + 1] - t.colX[v]).coerceAtLeast(12f) }
        val scale = minOf(1f, contentW / rawW.sum())
        val w = rawW.map { twips(it * scale).coerceAtLeast(200) }
        // owner of each grid position
        class Own(val cell: TableCell, val l: Int)
        val owner = HashMap<Long, Own>()
        fun key(r: Int, c: Int) = (r.toLong() shl 20) or c.toLong()
        for (c in t.cells) {
            val l = lc(c.col, c.colSpan)
            for (dr in 0 until c.rowSpan) for (dc in 0 until c.colSpan) owner[key(c.row + dr, l + dc)] = Own(c, l)
        }
        val border = if (t.ruled) "w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"" else "w:val=\"none\" w:sz=\"0\" w:space=\"0\" w:color=\"auto\""
        body.append("<w:tbl><w:tblPr>")
        // precisely laid out: a floating table at its place on the page
        if (exact) body.append("<w:tblpPr w:leftFromText=\"0\" w:rightFromText=\"0\" w:vertAnchor=\"page\" w:horzAnchor=\"page\" w:tblpX=\"")
            .append(OoxmlZip.twips(t.rect.x0.coerceAtLeast(0f))).append("\" w:tblpY=\"").append(OoxmlZip.twips(t.rect.y0.coerceAtLeast(0f))).append("\"/><w:tblOverlap w:val=\"overlap\"/>")
        if (rtl) body.append("<w:bidiVisual/>")
        body.append("<w:tblW w:w=\"").append(w.sum()).append("\" w:type=\"dxa\"/><w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) body.append("<w:").append(side).append(' ').append(border).append("/>")
        body.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:left w:w=\"80\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/></w:tblCellMar>")
            .append("<w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"0\" w:lastColumn=\"0\" w:noHBand=\"1\" w:noVBand=\"1\"/></w:tblPr><w:tblGrid>")
        w.forEach { body.append("<w:gridCol w:w=\"").append(it).append("\"/>") }
        body.append("</w:tblGrid>")
        for (r in 0 until t.rows) {
            body.append("<w:tr>")
            if (header && r == 0) body.append("<w:trPr><w:tblHeader/></w:trPr>")
            var c = 0
            while (c < nc) {
                val own = owner[key(r, c)]
                if (own == null) { tc(w[c], 1, null, null, false); c++; continue }
                if (own.l != c) { c++; continue } // covered by a horizontal span
                val cell = own.cell
                val span = cell.colSpan
                val cw = (c until c + span).sumOf { w[it] }
                when {
                    cell.row == r -> tc(cw, span, if (cell.rowSpan > 1) "restart" else null, cell, header && r == 0)
                    else -> tc(cw, span, "continue", null, false)
                }
                c += span
            }
            body.append("</w:tr>")
        }
        body.append("</w:tbl><w:p/>")
    }

    private fun tc(width: Int, span: Int, vMerge: String?, cell: TableCell?, headerCell: Boolean) {
        body.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(width).append("\" w:type=\"dxa\"/>")
        if (span > 1) body.append("<w:gridSpan w:val=\"").append(span).append("\"/>")
        when (vMerge) { "restart" -> body.append("<w:vMerge w:val=\"restart\"/>"); "continue" -> body.append("<w:vMerge/>") }
        val fill = cell?.fill?.let { Decorations.hex(it) } ?: if (headerCell && !pageHasFills) "DCE6F1" else null
        if (fill != null) body.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(fill).append("\"/>")
        body.append("</w:tcPr>")
        val lines = cell?.lines.orEmpty()
        if (lines.isEmpty()) body.append("<w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr></w:p>")
        for (line in lines) {
            body.append("<w:p><w:pPr>")
            if (line.rtl) body.append("<w:bidi/>")
            body.append("<w:spacing w:after=\"0\"/>")
            if (headerCell) body.append("<w:jc w:val=\"center\"/>")
            body.append("</w:pPr>")
            runs(body, TextRuns.of(line), line.rtl, forceBold = headerCell)
            body.append("</w:p>")
        }
        body.append("</w:tc>")
    }

    private fun image(b: ImageBlock, page: PageLayout) {
        val img = b.image
        if (img.w < 8f || img.h < 8f) return
        picId++
        val ext = if (img.ext == "png") "png" else "jpeg"
        val name = "image$picId.$ext"
        zip.bytes("word/media/$name", img.bytes)
        val rid = "rIdImg$picId"
        imageRels += rid to name
        // keep the PDF size, shrunk to the text width/height when needed
        val s = if (exact) minOf(1f, pageW / img.w, pageH / img.h) else minOf(1f, contentW / img.w, (pageH - marginT - marginB).coerceAtLeast(72f) / img.h)
        val cx = emu(img.w * s); val cy = emu(img.h * s)
        val center = (b.rect.x0 + b.rect.x1) / 2f
        val align = when {
            kotlin.math.abs(center - page.width / 2f) < page.width * 0.08f -> "center"
            center > page.width / 2f -> "right"
            else -> "left"
        }
        body.append("<w:p><w:pPr>").append(if (exact) frame(b.rect) + "<w:spacing w:before=\"0\" w:after=\"0\"/>" else "")
            .append("<w:jc w:val=\"").append(if (exact) "left" else align).append("\"/></w:pPr><w:r><w:drawing>")
        val anchored = anchorImages && !exact
        if (anchored) body.append("<wp:anchor distT=\"0\" distB=\"0\" distL=\"114300\" distR=\"114300\" simplePos=\"0\" relativeHeight=\"").append(picId)
            .append("\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/>")
            .append("<wp:positionH relativeFrom=\"page\"><wp:posOffset>").append(emu(b.rect.x0.coerceAtLeast(0f))).append("</wp:posOffset></wp:positionH>")
            .append("<wp:positionV relativeFrom=\"page\"><wp:posOffset>").append(emu(b.rect.y0.coerceAtLeast(0f))).append("</wp:posOffset></wp:positionV>")
            .append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapSquare wrapText=\"bothSides\"/>")
        else body.append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
        body.append("<wp:docPr id=\"").append(picId).append("\" name=\"Picture ").append(picId).append("\"/>")
            .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
            .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>")
            .append("<pic:nvPicPr><pic:cNvPr id=\"").append(picId).append("\" name=\"").append(name).append("\"/><pic:cNvPicPr/></pic:nvPicPr>")
            .append("<pic:blipFill><a:blip r:embed=\"").append(rid).append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
            .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
            .append("</pic:pic></a:graphicData></a:graphic>").append(if (anchored) "</wp:anchor>" else "</wp:inline>").append("</w:drawing></w:r></w:p>")
    }

    fun finish() {
        val rtlDoc = rtlVotes > 0
        val doc = StringBuilder(XML_HEAD)
            .append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"").append(REL_DOC)
            .append("\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"").append(OoxmlZip.NS_A)
            .append("\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>")
            .append(body)
        if (pages == 0) doc.append("<w:p/>")
        doc.append("<w:sectPr>")
        if (headerXml != null) doc.append("<w:headerReference w:type=\"default\" r:id=\"rIdHdr1\"/>")
        if (footerXml != null) doc.append("<w:footerReference w:type=\"default\" r:id=\"rIdFtr1\"/>")
        doc.append("<w:pgSz w:w=\"").append(twips(pageW)).append("\" w:h=\"").append(twips(pageH)).append('"')
        if (pageW > pageH) doc.append(" w:orient=\"landscape\"")
        doc.append("/><w:pgMar w:top=\"").append(twips(marginT)).append("\" w:right=\"").append(twips(marginR)).append("\" w:bottom=\"").append(twips(marginB))
            .append("\" w:left=\"").append(twips(marginL)).append("\" w:header=\"360\" w:footer=\"360\" w:gutter=\"0\"/>")
        if (rtlDoc) doc.append("<w:bidi/>")
        doc.append("</w:sectPr></w:body></w:document>")
        zip.text("word/document.xml", doc)
        zip.text("word/styles.xml", styles(rtlDoc))
        val rels = ArrayList<Triple<String, String, String>>()
        rels += Triple("rId1", "$REL_DOC/styles", "styles.xml")
        imageRels.forEach { (id, name) -> rels += Triple(id, "$REL_DOC/image", "media/$name") }
        headerXml?.let { zip.text("word/header1.xml", it); rels += Triple("rIdHdr1", "$REL_DOC/header", "header1.xml") }
        footerXml?.let { zip.text("word/footer1.xml", it); rels += Triple("rIdFtr1", "$REL_DOC/footer", "footer1.xml") }
        zip.text("word/_rels/document.xml.rels", OoxmlZip.rels(*rels.toTypedArray()))
        zip.text("_rels/.rels", OoxmlZip.rels(
            Triple("rId1", "$REL_DOC/officeDocument", "word/document.xml"),
            Triple("rId2", OoxmlZip.REL_CORE, "docProps/core.xml"),
        ))
        zip.text("docProps/core.xml", OoxmlZip.coreProps(title))
        zip.text("[Content_Types].xml", XML_HEAD + "<Types xmlns=\"" + OoxmlZip.NS_CT + "\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            (if (headerXml != null) "<Override PartName=\"/word/header1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>" else "") +
            (if (footerXml != null) "<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>" else "") +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"" + OoxmlZip.CT_CORE + "\"/></Types>")
        zip.close()
    }

    /** A header (`hdr`) or footer (`ftr`) part: one paragraph per line, parts on centre / end tab stops. */
    private fun furniturePart(tag: String, lines: List<Line>, page: PageLayout): String {
        val sb = StringBuilder(XML_HEAD).append("<w:").append(tag).append(" xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"").append(REL_DOC).append("\">")
        val cw = twips(contentW)
        for (line in lines.sortedBy { it.top }) {
            val parts = furniture.parts(line, page.width, page.height, page.bodyFontSize)
            val rtl = line.rtl
            sb.append("<w:p><w:pPr><w:tabs><w:tab w:val=\"center\" w:pos=\"").append(cw / 2).append("\"/><w:tab w:val=\"")
                .append(if (rtl) "left" else "right").append("\" w:pos=\"").append(cw).append("\"/></w:tabs>")
            if (rtl) sb.append("<w:bidi/>")
            sb.append("<w:spacing w:after=\"0\"/></w:pPr>")
            val hp = halfPts(line.fontSize)
            val font = line.words.firstOrNull()?.font
            val fonts = if (font == null) "" else "<w:rFonts w:ascii=\"" + BidiText.xml(font) + "\" w:hAnsi=\"" + BidiText.xml(font) + "\" w:cs=\"" + BidiText.xml(FontMap.complexScript(font)) + "\"/>"
            val rpr = "<w:rPr>" + fonts + "<w:sz w:val=\"" + hp + "\"/><w:szCs w:val=\"" + hp + "\"/>" + (if (rtl) "<w:rtl/>" else "") + "</w:rPr>"
            var slot = 0 // 0 start, 1 centre, 2 end
            var first = true
            for (p in parts.sortedBy { it.pos.ordinal }) {
                while (slot < p.pos.ordinal) { sb.append("<w:r><w:tab/></w:r>"); slot++ }
                if (!first && slot == p.pos.ordinal) sb.append("<w:r>").append(rpr).append("<w:t xml:space=\"preserve\"> </w:t></w:r>")
                first = false
                for (piece in p.pieces) when (piece) {
                    is PageFurniture.Piece.Text -> sb.append("<w:r>").append(rpr).append("<w:t xml:space=\"preserve\">").append(BidiText.xml(piece.text)).append("</w:t></w:r>")
                    is PageFurniture.Piece.PageNumber -> sb.append("<w:r>").append(rpr).append("<w:fldChar w:fldCharType=\"begin\"/></w:r><w:r>").append(rpr)
                        .append("<w:instrText xml:space=\"preserve\"> PAGE </w:instrText></w:r><w:r>").append(rpr).append("<w:fldChar w:fldCharType=\"separate\"/></w:r><w:r>").append(rpr)
                        .append("<w:t>").append(BidiText.xml(piece.sample)).append("</w:t></w:r><w:r>").append(rpr).append("<w:fldChar w:fldCharType=\"end\"/></w:r>")
                }
            }
            sb.append("</w:p>")
        }
        return sb.append("</w:").append(tag).append(">").toString()
    }

    private fun styles(rtl: Boolean): String {
        val hp = halfPts(bodySize)
        fun heading(n: Int, size: Int) = "<w:style w:type=\"paragraph\" w:styleId=\"Heading$n\"><w:name w:val=\"heading $n\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:uiPriority w:val=\"9\"/><w:qFormat/>" +
            "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"${n - 1}\"/></w:pPr>" +
            "<w:rPr><w:b/><w:bCs/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr></w:style>"
        return XML_HEAD + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Arial\"/>" +
            "<w:sz w:val=\"$hp\"/><w:szCs w:val=\"$hp\"/><w:lang w:val=\"${if (rtl) "ar-SA" else "en-US"}\" w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            heading(1, maxOf(hp + 12, 32)) + heading(2, maxOf(hp + 6, 26)) + heading(3, maxOf(hp + 2, 24)) +
            "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/>" +
            "<w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
            "</w:styles>"
    }

    override fun close() { runCatching { zip.close() } }
}
