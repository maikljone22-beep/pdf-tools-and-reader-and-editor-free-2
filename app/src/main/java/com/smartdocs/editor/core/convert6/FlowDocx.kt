package com.smartdocs.editor.core.convert6

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [FlowDoc] → Word (.docx): real heading styles, RTL paragraphs (w:bidi + w:rtl runs), bullet / numbered lists
 * (numbering.xml), tables (bidiVisual for Arabic), inline pictures (PNG/JPEG/GIF/BMP), links and page breaks. Pure JVM.
 */
object FlowDocx {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val x = Text6::xml

    private class Ctx {
        val media = ArrayList<Pair<String, () -> ByteArray>>()
        val links = ArrayList<String>()
        var picId = 0
        var numInstances = 0
        var dropped = 0
    }

    /** Returns the number of pictures that could not be stored (EMF / WMF / SVG without a platform decoder). */
    fun write(doc: FlowDoc, out: OutputStream): Int {
        val c = Ctx()
        val body = StringBuilder()
        var lastList: Boolean? = null   // ordered?
        var numId = 0
        doc.blocks.forEach { b ->
            if (b is FBlock.Item) {
                val ordered = b.number > 0
                if (lastList != ordered) { c.numInstances++; numId = if (ordered) 1 + c.numInstances else 1; lastList = ordered }
            } else lastList = null
            block(b, body, c, numId)
        }
        val docXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:document xmlns:w=\"$W\" xmlns:r=\"$R\"" +
            " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"" +
            " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>" + body +
            "<w:sectPr>" + (if (doc.rtl) "<w:bidi/>" else "") + "<w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
        ZipOutputStream(out).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", contentTypes())
            put("_rels/.rels", ROOT_RELS)
            put("docProps/core.xml", core(doc))
            put("word/document.xml", docXml)
            put("word/styles.xml", styles(doc.rtl))
            put("word/numbering.xml", numbering(c.numInstances))
            put("word/_rels/document.xml.rels", rels(c))
            c.media.forEach { (n, bytes) -> z.putNextEntry(ZipEntry("word/media/$n")); z.write(bytes()); z.closeEntry() }
        }
        return c.dropped
    }

    private fun pPr(style: String?, rtl: Boolean, align: FAlign = FAlign.START, extra: String = ""): String {
        val sb = StringBuilder("<w:pPr>")
        if (style != null) sb.append("<w:pStyle w:val=\"").append(style).append("\"/>")
        sb.append(extra)
        if (rtl) sb.append("<w:bidi/>")
        if (align == FAlign.CENTER) sb.append("<w:jc w:val=\"center\"/>")
        sb.append("</w:pPr>")
        return sb.toString()
    }

    /**
     * A run that mixes Arabic and Latin words is split by direction, so only the Arabic part carries `<w:rtl/>`
     * (convert audit C38: "نص مع English 123" laid the Latin words out as complex script). Pure, used by the tests.
     */
    fun directionSegments(text: String): List<Pair<String, Boolean>> {
        if (text.none { Text6.isRtlChar(it) }) return listOf(text to false)
        if (text.none { it.isLetter() && !Text6.isRtlChar(it) }) return listOf(text to true)
        val out = ArrayList<Pair<String, Boolean>>()
        val latin = Regex("""[A-Za-zÀ-ɏ](?:[A-Za-z0-9À-ɏ'’.,:/@_\-]|\s+(?=[A-Za-z0-9À-ɏ]))*""")
        var pos = 0
        latin.findAll(text).forEach { m ->
            if (m.range.first > pos) out += text.substring(pos, m.range.first) to true
            out += m.value to false
            pos = m.range.last + 1
        }
        if (pos < text.length) out += text.substring(pos) to true
        return out.filter { it.first.isNotEmpty() }
    }

    private fun runXml(r: FRun, c: Ctx): String {
        if (r.text.isEmpty()) return ""
        val link = Text6.safeLink(r.link)
        val segs = directionSegments(r.text)
        if (segs.size > 1) {
            val inner = segs.joinToString("") { (t, rtl) -> runXml(r.copy(text = t, link = link), c, rtl) }
            if (link == null) return inner
            c.links += link
            return "<w:hyperlink r:id=\"rIdL${c.links.size}\">$inner</w:hyperlink>"
        }
        val run = runXml(r.copy(link = link), c, segs.firstOrNull()?.second ?: false)
        if (link == null) return run
        c.links += link
        return "<w:hyperlink r:id=\"rIdL${c.links.size}\">$run</w:hyperlink>"
    }

    private fun runXml(r: FRun, c: Ctx, rtl: Boolean): String {
        val props = StringBuilder()
        if (r.code) props.append("<w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\" w:cs=\"Courier New\"/>")
        if (r.bold) props.append("<w:b/><w:bCs/>")
        if (r.italic) props.append("<w:i/><w:iCs/>")
        if (r.link != null) props.append("<w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/>")
        if (rtl) props.append("<w:rtl/>")
        val rPr = if (props.isEmpty()) "" else "<w:rPr>$props</w:rPr>"
        val parts = r.text.split('\n')
        val inner = parts.mapIndexed { i, p ->
            (if (i > 0) "<w:br/>" else "") + p.split('\t').mapIndexed { j, s -> (if (j > 0) "<w:tab/>" else "") + (if (s.isEmpty()) "" else "<w:t xml:space=\"preserve\">${x(s)}</w:t>") }.joinToString("")
        }.joinToString("")
        return "<w:r>$rPr$inner</w:r>"
    }

    private fun runs(rs: List<FRun>, c: Ctx) = rs.joinToString("") { runXml(it, c) }

    private fun block(b: FBlock, sb: StringBuilder, c: Ctx, numId: Int) {
        when (b) {
            is FBlock.Heading -> sb.append("<w:p>").append(pPr("Heading${b.level.coerceIn(1, 6)}", b.rtl)).append(runs(b.runs, c)).append("</w:p>")
            is FBlock.Para -> sb.append("<w:p>").append(pPr(null, b.rtl, b.align)).append(runs(b.runs, c)).append("</w:p>")
            is FBlock.Item -> sb.append("<w:p>").append(pPr("ListParagraph", b.rtl, extra = "<w:numPr><w:ilvl w:val=\"${b.level.coerceIn(0, 8)}\"/><w:numId w:val=\"$numId\"/></w:numPr>")).append(runs(b.runs, c)).append("</w:p>")
            is FBlock.Quote -> sb.append("<w:p>").append(pPr("Quote", b.rtl)).append(runs(b.runs, c)).append("</w:p>")
            is FBlock.Code -> b.text.trimEnd().split('\n').forEach { l -> sb.append("<w:p>").append(pPr("Code", false)).append(runXml(FRun(l, code = true), c)).append("</w:p>") }
            is FBlock.Table -> table(b, sb, c)
            is FBlock.Image -> image(b, sb, c)
            FBlock.PageBreak -> sb.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            FBlock.Rule -> sb.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"999999\"/></w:pBdr></w:pPr></w:p>")
            is FBlock.Chapter -> if (b.title.isNotBlank()) sb.append("<w:p>").append(pPr("Heading1", Text6.rtlDominant(b.title))).append(runXml(FRun(b.title), c)).append("</w:p>")
        }
    }

    private fun table(t: FBlock.Table, sb: StringBuilder, c: Ctx) {
        if (t.rows.isEmpty()) return
        val cols = t.rows.maxOf { it.size }.coerceAtLeast(1)
        val colW = 9638 / cols
        sb.append("<w:tbl><w:tblPr>").append(if (t.rtl) "<w:bidiVisual/>" else "").append("<w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { sb.append("<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>") }
        sb.append("</w:tblBorders><w:tblCellMar><w:left w:w=\"80\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
        repeat(cols) { sb.append("<w:gridCol w:w=\"$colW\"/>") }
        sb.append("</w:tblGrid>")
        t.rows.forEachIndexed { i, row ->
            val head = i == 0 && t.header
            sb.append("<w:tr>").append(if (head) "<w:trPr><w:tblHeader/></w:trPr>" else "")
            for (k in 0 until cols) {
                val v = row.getOrElse(k) { "" }
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$colW\" w:type=\"dxa\"/>").append(if (head) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E8EEF6\"/>" else "").append("</w:tcPr>")
                sb.append("<w:p>").append(pPr(null, Text6.rtlDominant(v) || (t.rtl && v.none { it.isLetter() }))).append(runXml(FRun(v, bold = head), c)).append("</w:p></w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl><w:p/>")
    }

    private fun image(img0: FBlock.Image, sb: StringBuilder, c: Ctx) {
        // TIFF / WebP / odd BMP become PNG (convert audit C36); EMF / WMF / SVG are counted as dropped
        val norm = PortableImage.normalize(img0.bytes, img0.ext, setOf("jpg", "png", "gif", "bmp"))
        if (norm == null) { c.dropped++; return }
        val (bytes, ext) = norm
        val unchanged = img0.file != null && Images6.sniffExt(img0.head) == ext
        val dims = ImageDims.of(bytes)
        var wPt = if (img0.widthPt > 0) img0.widthPt else dims?.let { it.first * 0.75f } ?: 300f
        var hPt = if (img0.heightPt > 0) img0.heightPt else dims?.let { wPt * it.second / it.first.coerceAtLeast(1) } ?: (wPt * 0.75f)
        val maxW = 481f; val maxH = 680f
        val k = minOf(1f, maxW / wPt, maxH / hPt)
        wPt *= k; hPt *= k
        c.picId++
        val name = "image${c.picId}.$ext"
        c.media += name to (if (unchanged) ({ img0.bytes }) else ({ bytes }))
        val cx = (wPt * 12700).toLong(); val cy = (hPt * 12700).toLong()
        val id = c.picId
        sb.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>")
            .append("<wp:docPr id=\"$id\" name=\"Picture $id\" descr=\"").append(x(img0.alt)).append("\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
            .append("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rIdM$id\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
            .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
    }

    private fun contentTypes() = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpg\" ContentType=\"image/jpeg\"/><Default Extension=\"gif\" ContentType=\"image/gif\"/><Default Extension=\"bmp\" ContentType=\"image/bmp\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
        "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>"

    private const val ROOT_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>"

    private fun rels(c: Ctx) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        append("<Relationship Id=\"rIdS\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        append("<Relationship Id=\"rIdN\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>")
        c.media.forEachIndexed { i, (n, _) -> append("<Relationship Id=\"rIdM${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/$n\"/>") }
        c.links.forEachIndexed { i, l -> append("<Relationship Id=\"rIdL${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"").append(x(l)).append("\" TargetMode=\"External\"/>") }
        append("</Relationships>")
    }

    private fun core(doc: FlowDoc) = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\"" +
        " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>" + x(doc.title) + "</dc:title><dc:creator>" + x(doc.author.ifBlank { "SmartDocs" }) + "</dc:creator></cp:coreProperties>"

    private fun styles(rtl: Boolean): String {
        val sizes = listOf(40, 32, 28, 26, 24, 22)
        val heads = sizes.mapIndexed { i, sz ->
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading${i + 1}\"><w:name w:val=\"heading ${i + 1}\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"$i\"/></w:pPr><w:rPr><w:b/><w:bCs/><w:color w:val=\"1F3864\"/><w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/></w:rPr></w:style>"
        }.joinToString("")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:styles xmlns:w=\"$W\"><w:docDefaults><w:rPrDefault><w:rPr>" +
            "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Arial\" w:eastAsia=\"Calibri\"/><w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/>" + (if (rtl) "<w:lang w:val=\"en-US\" w:bidi=\"ar-SA\"/>" else "") +
            "</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"300\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" + heads +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"40\"/></w:pPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:ind w:left=\"567\" w:right=\"567\"/></w:pPr><w:rPr><w:i/><w:color w:val=\"555555\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Code\"><w:name w:val=\"Code\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/></w:pPr><w:rPr><w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\"/><w:sz w:val=\"20\"/></w:rPr></w:style>" +
            "</w:styles>"
    }

    /** numId 1 = bullets; numId 2.. = one decimal list instance each (numbering restarts per list). */
    private fun numbering(instances: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<w:numbering xmlns:w=\"$W\">")
        append("<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        for (l in 0..8) append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"").append(if (l % 2 == 0) "•" else "◦").append("\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${720 + l * 360}\" w:hanging=\"360\"/></w:pPr></w:lvl>")
        append("</w:abstractNum><w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        for (l in 0..8) append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"").append(if (l % 3 == 1) "lowerLetter" else "decimal").append("\"/><w:lvlText w:val=\"%${l + 1}.\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${720 + l * 360}\" w:hanging=\"360\"/></w:pPr></w:lvl>")
        append("</w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>")
        for (i in 1..instances) append("<w:num w:numId=\"${i + 1}\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>")
        append("</w:numbering>")
    }
}

/** Pixel size of PNG / JPEG / GIF / BMP bytes without decoding. Pure. */
object ImageDims {
    fun of(b: ByteArray): Pair<Int, Int>? {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
        return runCatching {
            when (Images6.sniffExt(b)) {
                "png" -> be32(16) to be32(20)
                "gif" -> (u8(6) or (u8(7) shl 8)) to (u8(8) or (u8(9) shl 8))
                "bmp" -> CfbReader.le32(b, 18) to kotlin.math.abs(CfbReader.le32(b, 22))
                "jpg" -> {
                    var i = 2
                    var r: Pair<Int, Int>? = null
                    while (i + 9 < b.size) {
                        if (u8(i) != 0xFF) { i++; continue }
                        val m = u8(i + 1)
                        if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
                        val len = be16(i + 2)
                        if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) { r = be16(i + 7) to be16(i + 5); break }
                        i += 2 + len
                    }
                    r
                }
                else -> null
            }
        }.getOrNull()?.takeIf { it.first > 0 && it.second > 0 }
    }
}
