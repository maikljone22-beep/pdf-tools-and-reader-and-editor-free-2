package com.smartdocs.editor.core.convert

import com.smartdocs.editor.core.convert.OoxmlZip.Companion.NS_A
import com.smartdocs.editor.core.convert.OoxmlZip.Companion.NS_P
import com.smartdocs.editor.core.convert.OoxmlZip.Companion.NS_R
import com.smartdocs.editor.core.convert.OoxmlZip.Companion.XML_HEAD

/** Fixed parts of a minimal PowerPoint package (one blank master + layout + Office theme). */
internal object PptxTemplates {

    const val NS = "xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\""

    const val GROUP_HEAD = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    const val MASTER = XML_HEAD + "<p:sldMaster $NS><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>" + GROUP_HEAD +
        "</p:spTree></p:cSld><p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
        "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
        "<p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>"

    const val LAYOUT = XML_HEAD + "<p:sldLayout $NS type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>" + GROUP_HEAD +
        "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

    const val PRES_PROPS = XML_HEAD + "<p:presentationPr $NS/>"

    const val VIEW_PROPS = XML_HEAD + "<p:viewPr $NS><p:normalViewPr><p:restoredLeft sz=\"15620\"/><p:restoredTop sz=\"94660\"/></p:normalViewPr>" +
        "<p:slideViewPr><p:cSldViewPr><p:cViewPr varScale=\"1\"><p:scale><a:sx n=\"100\" d=\"100\"/><a:sy n=\"100\" d=\"100\"/></p:scale><p:origin x=\"0\" y=\"0\"/></p:cViewPr><p:guideLst/></p:cSldViewPr></p:slideViewPr>" +
        "<p:gridSpacing cx=\"76200\" cy=\"76200\"/></p:viewPr>"

    const val TABLE_STYLES = XML_HEAD + "<a:tblStyleLst xmlns:a=\"$NS_A\" def=\"{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}\"/>"

    private fun sys(name: String, v: String) = "<a:$name><a:srgbClr val=\"$v\"/></a:$name>"

    private const val FILL_STYLES = "<a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"><a:tint val=\"50000\"/></a:schemeClr></a:solidFill>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"><a:shade val=\"80000\"/></a:schemeClr></a:solidFill></a:fillStyleLst>"
    private const val LINE_STYLES = "<a:lnStyleLst>" +
        "<a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln>" +
        "<a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln>" +
        "<a:ln w=\"19050\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln></a:lnStyleLst>"
    private const val EFFECT_STYLES = "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>"
    private const val BG_STYLES = "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"><a:tint val=\"95000\"/></a:schemeClr></a:solidFill>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"><a:shade val=\"90000\"/></a:schemeClr></a:solidFill></a:bgFillStyleLst>"

    val THEME: String = XML_HEAD + "<a:theme xmlns:a=\"$NS_A\" name=\"Office Theme\"><a:themeElements>" +
        "<a:clrScheme name=\"Office\"><a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
        sys("dk2", "44546A") + sys("lt2", "E7E6E6") + sys("accent1", "4472C4") + sys("accent2", "ED7D31") + sys("accent3", "A5A5A5") +
        sys("accent4", "FFC000") + sys("accent5", "5B9BD5") + sys("accent6", "70AD47") + sys("hlink", "0563C1") + sys("folHlink", "954F72") +
        "</a:clrScheme><a:fontScheme name=\"Office\">" +
        "<a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"Arial\"/></a:majorFont>" +
        "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"Arial\"/></a:minorFont></a:fontScheme>" +
        "<a:fmtScheme name=\"Office\">$FILL_STYLES$LINE_STYLES$EFFECT_STYLES$BG_STYLES</a:fmtScheme>" +
        "</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"
}
