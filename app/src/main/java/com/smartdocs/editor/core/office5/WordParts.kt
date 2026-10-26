package com.smartdocs.editor.core.office5

/** XML templates and part plumbing for the Word editor (styles, numbering, pictures, tables, blank document). */
object WordParts {
    const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    const val REL_STYLES = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
    const val REL_NUMBERING = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering"
    const val CT_STYLES = "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"
    const val CT_NUMBERING = "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"
    const val CT_DOCUMENT = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"

    /** Built-in style kinds the ribbon can apply: id used when the document lacks one, Word name, XML body. */
    enum class StyleKind(val defaultId: String, val wordName: String, val sizeHalfPt: Int, val outline: Int) {
        NORMAL("Normal", "Normal", 0, -1),
        TITLE("Title", "Title", 56, -1),
        HEADING1("Heading1", "heading 1", 32, 0),
        HEADING2("Heading2", "heading 2", 28, 1),
        HEADING3("Heading3", "heading 3", 24, 2),
        QUOTE("Quote", "Quote", 0, -1),
    }

    fun styleXml(w: String, k: StyleKind): String {
        val p = "$w:"
        val rpr = buildString {
            if (k != StyleKind.NORMAL && k != StyleKind.QUOTE) append("<${p}b/><${p}bCs/>")
            if (k == StyleKind.QUOTE) append("<${p}i/><${p}iCs/><${p}color ${p}val=\"404040\"/>")
            if (k.outline >= 0 || k == StyleKind.TITLE) append("<${p}color ${p}val=\"1F3864\"/>")
            if (k.sizeHalfPt > 0) append("<${p}sz ${p}val=\"${k.sizeHalfPt}\"/><${p}szCs ${p}val=\"${k.sizeHalfPt}\"/>")
        }
        val ppr = buildString {
            if (k.outline >= 0) append("<${p}keepNext/><${p}spacing ${p}before=\"240\" ${p}after=\"120\"/><${p}outlineLvl ${p}val=\"${k.outline}\"/>")
            if (k == StyleKind.TITLE) append("<${p}spacing ${p}after=\"200\"/>")
            if (k == StyleKind.QUOTE) append("<${p}ind ${p}left=\"720\" ${p}right=\"720\"/>")
        }
        val based = if (k == StyleKind.NORMAL) "" else "<${p}basedOn ${p}val=\"Normal\"/><${p}next ${p}val=\"Normal\"/>"
        val def = if (k == StyleKind.NORMAL) " ${p}default=\"1\"" else ""
        return "<${p}style ${p}type=\"paragraph\"$def ${p}styleId=\"${k.defaultId}\"><${p}name ${p}val=\"${k.wordName}\"/>$based<${p}qFormat/>" +
            (if (ppr.isEmpty()) "" else "<${p}pPr>$ppr</${p}pPr>") + (if (rpr.isEmpty()) "" else "<${p}rPr>$rpr</${p}rPr>") + "</${p}style>"
    }

    /** Ensures styles.xml exists and has style [k]; returns the style id to use. */
    fun ensureStyle(pkg: OPackage, docPath: String, k: StyleKind): String {
        val path = pkg.rels(docPath).firstOrNull { it.type == REL_STYLES }?.target ?: run {
            val sp = docPath.substringBeforeLast('/') + "/styles.xml"
            if (!pkg.has(sp)) pkg.put(sp, XDom.parse(OPackage.DECL + "<w:styles xmlns:w=\"$W_NS\"></w:styles>"))
            pkg.addRel(docPath, REL_STYLES, sp); pkg.addOverride(sp, CT_STYLES); sp
        }
        val d = pkg.dom(path) ?: return k.defaultId
        val styles = WordStyles(d)
        styles.idByName(k.wordName)?.let { return it }
        if (styles.defs.containsKey(k.defaultId)) return k.defaultId
        val ed = pkg.edit(path)!!
        val w = ed.prefixOf(W_NS) ?: "w"
        if (k != StyleKind.NORMAL && styles.idByName("Normal") == null && !styles.defs.containsKey("Normal")) ed.root.add(XDom.elem(styleXml(w, StyleKind.NORMAL)))
        ed.root.add(XDom.elem(styleXml(w, k)))
        return k.defaultId
    }

    // ---------------- numbering ----------------

    private fun abstractXml(w: String, id: Int, bullet: Boolean, name: String): String {
        val p = "$w:"
        val bullets = listOf("\u2022", "\u25CB", "\u25AA")
        val fmts = listOf("decimal", "lowerLetter", "lowerRoman")
        val lv = (0..8).joinToString("") { i ->
            val ind = 720 * (i + 1)
            val (fmt, text) = if (bullet) "bullet" to bullets[i % 3] else fmts[i % 3] to "%${i + 1}."
            "<${p}lvl ${p}ilvl=\"$i\"><${p}start ${p}val=\"1\"/><${p}numFmt ${p}val=\"$fmt\"/><${p}lvlText ${p}val=\"$text\"/><${p}lvlJc ${p}val=\"left\"/>" +
                "<${p}pPr><${p}ind ${p}left=\"$ind\" ${p}hanging=\"360\"/></${p}pPr></${p}lvl>"
        }
        return "<${p}abstractNum ${p}abstractNumId=\"$id\"><${p}multiLevelType ${p}val=\"hybridMultilevel\"/><${p}name ${p}val=\"$name\"/>$lv</${p}abstractNum>"
    }

    /** numId of our bullet / decimal list definition, creating numbering.xml and the definition when needed. */
    fun ensureList(pkg: OPackage, docPath: String, bullet: Boolean): String {
        val path = pkg.rels(docPath).firstOrNull { it.type == REL_NUMBERING }?.target ?: run {
            val np = docPath.substringBeforeLast('/') + "/numbering.xml"
            if (!pkg.has(np)) pkg.put(np, XDom.parse(OPackage.DECL + "<w:numbering xmlns:w=\"$W_NS\"></w:numbering>"))
            pkg.addRel(docPath, REL_NUMBERING, np); pkg.addOverride(np, CT_NUMBERING); np
        }
        val d = pkg.edit(path)!!
        val w = d.prefixOf(W_NS) ?: "w"
        val name = if (bullet) "SmartDocsBullet" else "SmartDocsDecimal"
        val root = d.root
        val abs = root.children("abstractNum").firstOrNull { it.child("name")?.attr("val") == name }
        val absId = abs?.attr("abstractNumId") ?: run {
            val id = (root.children("abstractNum").mapNotNull { it.attr("abstractNumId")?.toIntOrNull() }.maxOrNull() ?: -1) + 1
            val e = XDom.elem(abstractXml(w, id, bullet, name))
            val lastAbs = root.kids.indexOfLast { it is XElem && (it.local == "abstractNum" || it.local == "numPicBullet") }
            root.add(lastAbs + 1, e)
            id.toString()
        }
        root.children("num").firstOrNull { it.child("abstractNumId")?.attr("val") == absId }?.attr("numId")?.let { return it }
        val numId = (root.children("num").mapNotNull { it.attr("numId")?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val e = XDom.elem("<$w:num $w:numId=\"$numId\"><$w:abstractNumId $w:val=\"$absId\"/></$w:num>")
        val cleanup = root.kids.indexOfFirst { it is XElem && it.local == "numIdMacAtCleanup" }
        if (cleanup >= 0) root.add(cleanup, e) else root.add(e)
        return numId.toString()
    }

    // ---------------- tables ----------------

    /** A bordered table with [rows] x [cols] empty cells spanning [widthTwips]. */
    fun tableXml(w: String, rows: Int, cols: Int, widthTwips: Int, rtl: Boolean): String {
        val p = "$w:"
        val cw = widthTwips / cols.coerceAtLeast(1)
        val b = listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<$p$it ${p}val=\"single\" ${p}sz=\"4\" ${p}space=\"0\" ${p}color=\"808080\"/>" }
        val cellP = if (rtl) "<${p}p><${p}pPr><${p}bidi/></${p}pPr></${p}p>" else "<${p}p/>"
        return buildString {
            append("<${p}tbl><${p}tblPr><${p}tblW ${p}w=\"$widthTwips\" ${p}type=\"dxa\"/>")
            if (rtl) append("<${p}bidiVisual/>")
            append("<${p}tblBorders>$b</${p}tblBorders><${p}tblLook ${p}val=\"04A0\"/></${p}tblPr><${p}tblGrid>")
            repeat(cols) { append("<${p}gridCol ${p}w=\"$cw\"/>") }
            append("</${p}tblGrid>")
            repeat(rows) {
                append("<${p}tr>")
                repeat(cols) { append("<${p}tc><${p}tcPr><${p}tcW ${p}w=\"$cw\" ${p}type=\"dxa\"/></${p}tcPr>$cellP</${p}tc>") }
                append("</${p}tr>")
            }
            append("</${p}tbl>")
        }
    }

    // ---------------- pictures ----------------

    fun inlinePictureXml(w: String, rId: String, cx: Long, cy: Long, id: Int, name: String): String =
        "<$w:r><$w:drawing><wp:inline xmlns:wp=\"$WP_NS\" xmlns:r=\"${OPackage.R_NS}\" distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
            "<wp:docPr id=\"$id\" name=\"${XDom.escapeAttr(name)}\"/><wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"$A_NS\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic xmlns:a=\"$A_NS\"><a:graphicData uri=\"$PIC_NS\"><pic:pic xmlns:pic=\"$PIC_NS\">" +
            "<pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"${XDom.escapeAttr(name)}\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$rId\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></$w:drawing></$w:r>"

    /** Stores [bytes] as a media part related to [docPath]; returns the relationship id. */
    fun addImagePart(pkg: OPackage, docPath: String, bytes: ByteArray): String {
        val ext = OPackage.imageExt(bytes)
        val dir = docPath.substringBeforeLast('/') + "/media"
        val path = pkg.freeName(dir, "image", ext)
        pkg.put(path, bytes)
        pkg.ensureDefault(ext, OPackage.imageMime(ext))
        return pkg.addRel(docPath, OPackage.REL_IMAGE, path)
    }

    // ---------------- blank document ----------------

    /** A minimal valid .docx (used for "new document" and for converting text/ODT/RTF to an editable copy). */
    fun blankDocx(paragraphs: List<String> = listOf(""), rtl: Boolean = true): OPackage {
        val body = paragraphs.joinToString("") { t ->
            val ppr = if (rtl || com.smartdocs.editor.core.office.hasRtlChars(t)) "<w:pPr><w:bidi/></w:pPr>" else ""
            val rpr = if (rtl || com.smartdocs.editor.core.office.hasRtlChars(t)) "<w:rPr><w:rtl/></w:rPr>" else ""
            "<w:p>$ppr" + (if (t.isEmpty()) "" else "<w:r>$rpr<w:t xml:space=\"preserve\">${XDom.escapeText(t)}</w:t></w:r>") + "</w:p>"
        }
        val doc = OPackage.DECL + "<w:document xmlns:w=\"$W_NS\" xmlns:r=\"${OPackage.R_NS}\" xmlns:wp=\"$WP_NS\"><w:body>$body" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            (if (rtl) "<w:bidi/>" else "") + "</w:sectPr></w:body></w:document>"
        val styles = OPackage.DECL + "<w:styles xmlns:w=\"$W_NS\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>" +
            "<w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/><w:lang w:val=\"en-US\" w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            styleXml("w", StyleKind.NORMAL) + "</w:styles>"
        val ct = OPackage.DECL + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"$CT_DOCUMENT\"/><Override PartName=\"/word/styles.xml\" ContentType=\"$CT_STYLES\"/></Types>"
        val rels = OPackage.DECL + "<Relationships xmlns=\"${OPackage.REL_NS}\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"
        val docRels = OPackage.DECL + "<Relationships xmlns=\"${OPackage.REL_NS}\"><Relationship Id=\"rId1\" Type=\"$REL_STYLES\" Target=\"styles.xml\"/></Relationships>"
        return OPackage.create(linkedMapOf(
            OPackage.CT to ct.toByteArray(), "_rels/.rels" to rels.toByteArray(), "word/document.xml" to doc.toByteArray(),
            "word/_rels/document.xml.rels" to docRels.toByteArray(), "word/styles.xml" to styles.toByteArray(),
        ))
    }
}
