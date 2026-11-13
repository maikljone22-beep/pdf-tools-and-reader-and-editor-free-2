package com.smartdocs.editor.core.office5

/** Parts for a new presentation: a minimal, valid .pptx (master, "Title and Content" layout, theme, one empty slide). */
object PptParts {
    private const val A = PptDoc.A_NS
    private const val P = PptDoc.P_NS
    private const val R = OPackage.R_NS
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val CT_BASE = "application/vnd.openxmlformats-officedocument.presentationml"

    /** A 16:9 deck with one slide (title + content placeholders). [rtl] makes the text styles right-to-left. */
    fun blankPptx(rtl: Boolean = true): OPackage {
        val ct = OPackage.DECL + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"$CT_BASE.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"$CT_BASE.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"$CT_BASE.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/slides/slide1.xml\" ContentType=\"${PptDoc.CT_SLIDE}\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/></Types>"
        val rels = rels("rId1" to ("officeDocument" to "ppt/presentation.xml"))
        val presRels = rels("rId1" to ("slideMaster" to "slideMasters/slideMaster1.xml"), "rId2" to ("slide" to "slides/slide1.xml"), "rId3" to ("theme" to "theme/theme1.xml"))
        val pres = OPackage.DECL + "<p:presentation xmlns:a=\"$A\" xmlns:r=\"$R\" xmlns:p=\"$P\"" + (if (rtl) " rtl=\"1\"" else "") + " saveSubsetFonts=\"1\">" +
            "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst><p:sldId id=\"256\" r:id=\"rId2\"/></p:sldIdLst>" +
            "<p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>"
        val masterRels = rels("rId1" to ("slideLayout" to "../slideLayouts/slideLayout1.xml"), "rId2" to ("theme" to "../theme/theme1.xml"))
        val layoutRels = rels("rId1" to ("slideMaster" to "../slideMasters/slideMaster1.xml"))
        val slideRels = rels("rId1" to ("slideLayout" to "../slideLayouts/slideLayout1.xml"))
        val slide = OPackage.DECL + "<p:sld xmlns:a=\"$A\" xmlns:r=\"$R\" xmlns:p=\"$P\"><p:cSld><p:spTree>$GROUP" +
            slidePh(2, "Title 1", "type=\"title\"") + slidePh(3, "Content Placeholder 2", "idx=\"1\"") +
            "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
        return OPackage.create(linkedMapOf(
            OPackage.CT to ct.toByteArray(), "_rels/.rels" to rels.toByteArray(),
            "ppt/presentation.xml" to pres.toByteArray(), "ppt/_rels/presentation.xml.rels" to presRels.toByteArray(),
            "ppt/slideMasters/slideMaster1.xml" to master(rtl).toByteArray(), "ppt/slideMasters/_rels/slideMaster1.xml.rels" to masterRels.toByteArray(),
            "ppt/slideLayouts/slideLayout1.xml" to layout().toByteArray(), "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to layoutRels.toByteArray(),
            "ppt/slides/slide1.xml" to slide.toByteArray(), "ppt/slides/_rels/slide1.xml.rels" to slideRels.toByteArray(),
            "ppt/theme/theme1.xml" to theme().toByteArray(),
        ))
    }

    private const val GROUP = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    private fun rels(vararg r: Pair<String, Pair<String, String>>): String =
        OPackage.DECL + "<Relationships xmlns=\"${OPackage.REL_NS}\">" + r.joinToString("") { (id, tt) -> "<Relationship Id=\"$id\" Type=\"$REL/${tt.first}\" Target=\"${tt.second}\"/>" } + "</Relationships>"

    private fun slidePh(id: Int, name: String, ph: String) =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph $ph/></p:nvPr></p:nvSpPr>" +
            "<p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></p:txBody></p:sp>"

    private fun masterPh(id: Int, name: String, ph: String, x: Int, y: Int, cx: Int, cy: Int, anchor: String, text: String) =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph $ph/></p:nvPr></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
            "<p:txBody><a:bodyPr vert=\"horz\" lIns=\"91440\" tIns=\"45720\" rIns=\"91440\" bIns=\"45720\" rtlCol=\"0\" anchor=\"$anchor\"><a:normAutofit/></a:bodyPr><a:lstStyle/>" +
            "<a:p><a:r><a:rPr lang=\"en-US\"/><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp>"

    private fun master(rtl: Boolean): String {
        val algn = if (rtl) "r" else "l"
        val rtlAttr = if (rtl) "1" else "0"
        val fill = "<a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill>"
        val title = "<p:titleStyle><a:lvl1pPr algn=\"$algn\" defTabSz=\"914400\" rtl=\"$rtlAttr\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\"><a:lnSpc><a:spcPct val=\"90000\"/></a:lnSpc>" +
            "<a:spcBef><a:spcPct val=\"0\"/></a:spcBef><a:buNone/><a:defRPr sz=\"4400\" kern=\"1200\">$fill<a:latin typeface=\"+mj-lt\"/><a:ea typeface=\"+mj-ea\"/><a:cs typeface=\"+mj-cs\"/></a:defRPr></a:lvl1pPr></p:titleStyle>"
        val margin = if (rtl) "marR=\"228600\"" else "marL=\"228600\""
        val body = "<p:bodyStyle><a:lvl1pPr $margin indent=\"-228600\" algn=\"$algn\" defTabSz=\"914400\" rtl=\"$rtlAttr\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\"><a:lnSpc><a:spcPct val=\"90000\"/></a:lnSpc>" +
            "<a:spcBef><a:spcPts val=\"1000\"/></a:spcBef><a:buFont typeface=\"Arial\"/><a:buChar char=\"&#8226;\"/><a:defRPr sz=\"2800\" kern=\"1200\">$fill<a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/><a:cs typeface=\"+mn-cs\"/></a:defRPr></a:lvl1pPr></p:bodyStyle>"
        return OPackage.DECL + "<p:sldMaster xmlns:a=\"$A\" xmlns:r=\"$R\" xmlns:p=\"$P\"><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>$GROUP" +
            masterPh(2, "Title Placeholder 1", "type=\"title\"", 838200, 365125, 10515600, 1325563, "ctr", "Click to edit Master title style") +
            masterPh(3, "Text Placeholder 2", "type=\"body\" idx=\"1\"", 838200, 1825625, 10515600, 4351338, "t", "Click to edit Master text styles") +
            "</p:spTree></p:cSld><p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst><p:txStyles>$title$body<p:otherStyle><a:defPPr><a:defRPr lang=\"en-US\"/></a:defPPr></p:otherStyle></p:txStyles></p:sldMaster>"
    }

    private fun layout(): String =
        OPackage.DECL + "<p:sldLayout xmlns:a=\"$A\" xmlns:r=\"$R\" xmlns:p=\"$P\" type=\"obj\" preserve=\"1\"><p:cSld name=\"Title and Content\"><p:spTree>$GROUP" +
            slidePh(2, "Title 1", "type=\"title\"") + slidePh(3, "Content Placeholder 2", "idx=\"1\"") +
            "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

    private fun theme(): String {
        fun clr(n: String, hex: String) = "<a:$n><a:srgbClr val=\"$hex\"/></a:$n>"
        fun font(n: String, latin: String) = "<a:$n><a:latin typeface=\"$latin\"/><a:ea typeface=\"\"/><a:cs typeface=\"Arial\"/></a:$n>"
        val fills = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>".repeat(3)
        val lines = listOf(6350, 12700, 19050).joinToString("") { "<a:ln w=\"$it\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln>" }
        return OPackage.DECL + "<a:theme xmlns:a=\"$A\" name=\"Office Theme\"><a:themeElements><a:clrScheme name=\"Office\">" +
            "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
            clr("dk2", "44546A") + clr("lt2", "E7E6E6") + clr("accent1", "4472C4") + clr("accent2", "ED7D31") + clr("accent3", "A5A5A5") +
            clr("accent4", "FFC000") + clr("accent5", "5B9BD5") + clr("accent6", "70AD47") + clr("hlink", "0563C1") + clr("folHlink", "954F72") +
            "</a:clrScheme><a:fontScheme name=\"Office\">" + font("majorFont", "Calibri Light") + font("minorFont", "Calibri") + "</a:fontScheme>" +
            "<a:fmtScheme name=\"Office\"><a:fillStyleLst>$fills</a:fillStyleLst><a:lnStyleLst>$lines</a:lnStyleLst>" +
            "<a:effectStyleLst>" + "<a:effectStyle><a:effectLst/></a:effectStyle>".repeat(3) + "</a:effectStyleLst><a:bgFillStyleLst>$fills</a:bgFillStyleLst></a:fmtScheme>" +
            "</a:themeElements></a:theme>"
    }
}
