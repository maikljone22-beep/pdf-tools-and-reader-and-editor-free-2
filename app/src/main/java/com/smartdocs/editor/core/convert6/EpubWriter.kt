package com.smartdocs.editor.core.convert6

import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Store `mimetype` uncompressed as the first entry (required by EPUB and ODF). */
internal fun ZipOutputStream.putStoredMimetype(mime: String) {
    val bytes = mime.toByteArray(Charsets.US_ASCII)
    val e = ZipEntry("mimetype")
    e.method = ZipEntry.STORED
    e.size = bytes.size.toLong(); e.compressedSize = bytes.size.toLong()
    e.crc = CRC32().apply { update(bytes) }.value
    putNextEntry(e); write(bytes); closeEntry()
}

/**
 * [FlowDoc] → EPUB 3 (reflowable, with an EPUB 2 NCX for old readers). One XHTML file per chapter
 * ([FlowDoc.chapters]); RTL books get `page-progression-direction="rtl"`. Images become separate items. Pure JVM.
 */
object EpubWriter {

    fun write(doc: FlowDoc, out: OutputStream, lang: String? = null, id: String = "urn:uuid:" + UUID.randomUUID()) {
        val rtl = doc.rtl
        val language = lang ?: if (rtl) "ar" else "en"
        val chapters = doc.chapters()
        val images = ArrayList<Pair<String, FBlock.Image>>()
        val x = Text6::xml
        ZipOutputStream(out).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            z.putStoredMimetype("application/epub+zip")
            put("META-INF/container.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
            put("OEBPS/style.css", "body{font-family:serif;line-height:1.6;margin:0 4%}" + (if (rtl) "html{direction:rtl}" else "") +
                "img{max-width:100%;height:auto}table{border-collapse:collapse}td,th{border:1px solid #999;padding:2px 6px}pre{white-space:pre-wrap;font-size:.9em}blockquote{margin:0 1em;font-style:italic}")
            chapters.forEachIndexed { i, (title, blocks) ->
                val mapped = blocks.map { b ->
                    if (b is FBlock.Image) {
                        val ext = (Images6.sniffExt(b.bytes) ?: b.ext).let { if (it == "jpeg") "jpg" else it }
                        if (ext in setOf("jpg", "png", "gif", "svg", "webp")) {
                            val name = "img${images.size + 1}.$ext"
                            images += name to b
                            "<div class=\"img\"><img src=\"images/$name\" alt=\"" + x(b.alt) + "\"/></div>\n"
                        } else ""
                    } else xhtml(FlowHtml.body(listOf(b)))
                }
                val hasHeading = blocks.firstOrNull() is FBlock.Heading
                val head = if (!hasHeading && title.isNotBlank() && chapters.size > 1) "<h1>" + x(title) + "</h1>\n" else ""
                put("OEBPS/ch${i + 1}.xhtml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"$language\" lang=\"$language\" dir=\"${if (rtl) "rtl" else "ltr"}\">" +
                    "<head><meta charset=\"utf-8\"/><title>" + x(title) + "</title><link rel=\"stylesheet\" href=\"style.css\" type=\"text/css\"/></head><body>\n" + head + mapped.joinToString("") + "</body></html>")
            }
            images.forEach { (n, b) -> z.putNextEntry(ZipEntry("OEBPS/images/$n")); z.write(b.bytes); z.closeEntry() }
            val navItems = chapters.mapIndexed { i, (t, _) -> "<li><a href=\"ch${i + 1}.xhtml\">" + x(t.ifBlank { "${i + 1}" }) + "</a></li>" }.joinToString("")
            put("OEBPS/nav.xhtml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"$language\" dir=\"${if (rtl) "rtl" else "ltr"}\"><head><meta charset=\"utf-8\"/><title>" +
                x(doc.title) + "</title></head><body><nav epub:type=\"toc\" id=\"toc\"><h1>" + x(doc.title) + "</h1><ol>" + navItems + "</ol></nav></body></html>")
            put("OEBPS/toc.ncx", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\"><head><meta name=\"dtb:uid\" content=\"" + x(id) + "\"/></head><docTitle><text>" + x(doc.title) + "</text></docTitle><navMap>" +
                chapters.mapIndexed { i, (t, _) -> "<navPoint id=\"np${i + 1}\" playOrder=\"${i + 1}\"><navLabel><text>" + x(t.ifBlank { "${i + 1}" }) + "</text></navLabel><content src=\"ch${i + 1}.xhtml\"/></navPoint>" }.joinToString("") + "</navMap></ncx>")
            val manifest = StringBuilder()
            manifest.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/><item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>")
            chapters.indices.forEach { manifest.append("<item id=\"ch${it + 1}\" href=\"ch${it + 1}.xhtml\" media-type=\"application/xhtml+xml\"/>") }
            images.forEachIndexed { i, (n, _) -> manifest.append("<item id=\"im${i + 1}\" href=\"images/$n\" media-type=\"${Images6.mimeOf(n.substringAfterLast('.'))}\"/>") }
            val spine = chapters.indices.joinToString("") { "<itemref idref=\"ch${it + 1}\"/>" }
            val modified = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            put("OEBPS/content.opf", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\" xml:lang=\"$language\"" + (if (rtl) " dir=\"rtl\"" else "") + ">" +
                "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"bookid\">" + x(id) + "</dc:identifier><dc:title>" + x(doc.title) + "</dc:title><dc:language>$language</dc:language>" +
                (if (doc.author.isNotBlank()) "<dc:creator>" + x(doc.author) + "</dc:creator>" else "") + "<meta property=\"dcterms:modified\">$modified</meta></metadata>" +
                "<manifest>$manifest</manifest><spine toc=\"ncx\"" + (if (rtl) " page-progression-direction=\"rtl\"" else "") + ">$spine</spine></package>")
        }
    }

    /** HTML fragments from [FlowHtml] made XML-clean (void elements closed). */
    fun xhtml(html: String): String = html.replace("<br>", "<br/>").replace("<hr>", "<hr/>")
        .replace(Regex("""<img([^>]*[^/])>"""), "<img$1/>")
}

/**
 * OpenDocument writers: [FlowDoc] → ODT, sheets → ODS, slides → ODP. Minimal but valid packages
 * (mimetype stored first, manifest, content.xml, styles.xml). Pure JVM.
 */
object OdfWriter {

    private const val NS = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\"" +
        " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\"" +
        " xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\" xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\"" +
        " xmlns:xlink=\"http://www.w3.org/1999/xlink\" xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\"" +
        " xmlns:presentation=\"urn:oasis:names:tc:opendocument:xmlns:presentation:1.0\" office:version=\"1.2\""

    private val x = Text6::xml

    private fun pack(out: OutputStream, mime: String, content: String, styles: String, media: List<Pair<String, ByteArray>> = emptyList()) {
        ZipOutputStream(out).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            z.putStoredMimetype(mime)
            put("content.xml", content)
            put("styles.xml", styles)
            put("meta.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<office:document-meta $NS xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\"><office:meta><meta:generator>SmartDocs</meta:generator></office:meta></office:document-meta>")
            media.forEach { (n, b) -> z.putNextEntry(ZipEntry("Pictures/$n")); z.write(b); z.closeEntry() }
            put("META-INF/manifest.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.2\">" +
                "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"$mime\"/><manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>" +
                "<manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/><manifest:file-entry manifest:full-path=\"meta.xml\" manifest:media-type=\"text/xml\"/>" +
                media.joinToString("") { (n, _) -> "<manifest:file-entry manifest:full-path=\"Pictures/$n\" manifest:media-type=\"${Images6.mimeOf(n.substringAfterLast('.'))}\"/>" } +
                "</manifest:manifest>")
        }
    }

    private fun stylesXml(rtl: Boolean) = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<office:document-styles $NS><office:styles>" +
        "<style:default-style style:family=\"paragraph\"><style:paragraph-properties style:writing-mode=\"${if (rtl) "rl-tb" else "lr-tb"}\"/>" +
        "<style:text-properties style:font-name=\"Arial\" fo:font-size=\"12pt\" style:font-size-complex=\"12pt\" fo:language=\"en\" style:language-complex=\"ar\"/></style:default-style>" +
        "<style:style style:name=\"Standard\" style:family=\"paragraph\"/>" +
        (1..6).joinToString("") { l -> "<style:style style:name=\"Heading_20_$l\" style:display-name=\"Heading $l\" style:family=\"paragraph\" style:parent-style-name=\"Standard\" style:default-outline-level=\"$l\"><style:text-properties fo:font-size=\"${22 - l * 2}pt\" style:font-size-complex=\"${22 - l * 2}pt\" fo:font-weight=\"bold\" style:font-weight-complex=\"bold\"/></style:style>" } +
        "</office:styles></office:document-styles>"

    private fun runsXml(rs: List<FRun>): String = rs.joinToString("") { r ->
        val t = x(r.text).replace("\t", "<text:tab/>").replace("\n", "<text:line-break/>")
        val styled = when { r.bold && r.italic -> "<text:span text:style-name=\"TBI\">$t</text:span>"; r.bold -> "<text:span text:style-name=\"TB\">$t</text:span>"; r.italic -> "<text:span text:style-name=\"TI\">$t</text:span>"; r.code -> "<text:span text:style-name=\"TC\">$t</text:span>"; else -> t }
        if (r.link != null) "<text:a xlink:type=\"simple\" xlink:href=\"" + x(r.link) + "\">$styled</text:a>" else styled
    }

    fun odt(doc: FlowDoc, out: OutputStream) {
        val media = ArrayList<Pair<String, ByteArray>>()
        val body = StringBuilder()
        var listOpen: Boolean? = null
        fun closeList() { if (listOpen != null) body.append("</text:list>"); listOpen = null }
        fun p(rtl: Boolean) = if (rtl) "PR" else "PL"
        doc.blocks.forEach { b ->
            if (b !is FBlock.Item) closeList()
            when (b) {
                is FBlock.Heading -> body.append("<text:h text:style-name=\"Heading_20_${b.level.coerceIn(1, 6)}\" text:outline-level=\"${b.level.coerceIn(1, 6)}\">").append(runsXml(b.runs)).append("</text:h>")
                is FBlock.Para -> body.append("<text:p text:style-name=\"${if (b.align == FAlign.CENTER) "PC" else p(b.rtl)}\">").append(runsXml(b.runs)).append("</text:p>")
                is FBlock.Item -> {
                    val ordered = b.number > 0
                    if (listOpen != ordered) { closeList(); body.append("<text:list text:style-name=\"${if (ordered) "LO" else "LB"}\">"); listOpen = ordered }
                    body.append("<text:list-item><text:p text:style-name=\"${p(b.rtl)}\">").append(runsXml(b.runs)).append("</text:p></text:list-item>")
                }
                is FBlock.Quote -> body.append("<text:p text:style-name=\"PQ\">").append(runsXml(b.runs)).append("</text:p>")
                is FBlock.Code -> b.text.trimEnd().split('\n').forEach { l -> body.append("<text:p text:style-name=\"PCode\">").append(x(l).replace(" ", "<text:s/>")).append("</text:p>") }
                is FBlock.Table -> if (b.rows.isNotEmpty()) {
                    val cols = b.rows.maxOf { it.size }.coerceAtLeast(1)
                    body.append("<table:table table:style-name=\"${if (b.rtl) "TabR" else "TabL"}\"><table:table-column table:number-columns-repeated=\"$cols\"/>")
                    b.rows.forEach { r ->
                        body.append("<table:table-row>")
                        for (k in 0 until cols) body.append("<table:table-cell table:style-name=\"Cell\" office:value-type=\"string\"><text:p text:style-name=\"${p(Text6.rtlDominant(r.getOrElse(k) { "" }))}\">").append(x(r.getOrElse(k) { "" })).append("</text:p></table:table-cell>")
                        body.append("</table:table-row>")
                    }
                    body.append("</table:table>")
                }
                is FBlock.Image -> {
                    val ext = Images6.sniffExt(b.bytes) ?: b.ext
                    if (ext in setOf("jpg", "png", "gif")) {
                        val n = "img${media.size + 1}.$ext"; media += n to b.bytes
                        val d = ImageDims.of(b.bytes)
                        var w = if (b.widthPt > 0) b.widthPt else d?.let { it.first * 0.75f } ?: 300f
                        var h = if (b.heightPt > 0) b.heightPt else d?.let { w * it.second / it.first } ?: (w * 0.75f)
                        val k = minOf(1f, 481f / w); w *= k; h *= k
                        body.append("<text:p text:style-name=\"PC\"><draw:frame draw:name=\"$n\" text:anchor-type=\"as-char\" svg:width=\"${w}pt\" svg:height=\"${h}pt\"><draw:image xlink:href=\"Pictures/$n\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/></draw:frame></text:p>")
                    }
                }
                FBlock.PageBreak -> body.append("<text:p text:style-name=\"PBreak\"/>")
                FBlock.Rule -> body.append("<text:p text:style-name=\"PL\">———</text:p>")
                is FBlock.Chapter -> if (b.title.isNotBlank()) body.append("<text:h text:style-name=\"Heading_20_1\" text:outline-level=\"1\">").append(x(b.title)).append("</text:h>")
            }
        }
        closeList()
        val auto = "<office:automatic-styles>" +
            "<style:style style:name=\"PL\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties style:writing-mode=\"lr-tb\"/></style:style>" +
            "<style:style style:name=\"PR\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties style:writing-mode=\"rl-tb\" fo:text-align=\"end\"/></style:style>" +
            "<style:style style:name=\"PC\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:text-align=\"center\"/></style:style>" +
            "<style:style style:name=\"PQ\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:margin-left=\"1cm\" fo:margin-right=\"1cm\"/><style:text-properties fo:font-style=\"italic\"/></style:style>" +
            "<style:style style:name=\"PCode\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:background-color=\"#f2f2f2\"/><style:text-properties style:font-name=\"Courier New\" fo:font-family=\"'Courier New'\" fo:font-size=\"10pt\"/></style:style>" +
            "<style:style style:name=\"PBreak\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:break-after=\"page\"/></style:style>" +
            "<style:style style:name=\"TB\" style:family=\"text\"><style:text-properties fo:font-weight=\"bold\" style:font-weight-complex=\"bold\"/></style:style>" +
            "<style:style style:name=\"TI\" style:family=\"text\"><style:text-properties fo:font-style=\"italic\" style:font-style-complex=\"italic\"/></style:style>" +
            "<style:style style:name=\"TBI\" style:family=\"text\"><style:text-properties fo:font-weight=\"bold\" fo:font-style=\"italic\" style:font-weight-complex=\"bold\" style:font-style-complex=\"italic\"/></style:style>" +
            "<style:style style:name=\"TC\" style:family=\"text\"><style:text-properties fo:font-family=\"'Courier New'\"/></style:style>" +
            "<style:style style:name=\"TabL\" style:family=\"table\"><style:table-properties style:width=\"17cm\" table:align=\"margins\" style:writing-mode=\"lr-tb\"/></style:style>" +
            "<style:style style:name=\"TabR\" style:family=\"table\"><style:table-properties style:width=\"17cm\" table:align=\"margins\" style:writing-mode=\"rl-tb\"/></style:style>" +
            "<style:style style:name=\"Cell\" style:family=\"table-cell\"><style:table-cell-properties fo:border=\"0.5pt solid #999999\" fo:padding=\"0.1cm\"/></style:style>" +
            "<text:list-style style:name=\"LB\"><text:list-level-style-bullet text:level=\"1\" text:bullet-char=\"•\"><style:list-level-properties text:space-before=\"0.4cm\" text:min-label-width=\"0.5cm\"/></text:list-level-style-bullet></text:list-style>" +
            "<text:list-style style:name=\"LO\"><text:list-level-style-number text:level=\"1\" style:num-format=\"1\" style:num-suffix=\".\"><style:list-level-properties text:space-before=\"0.4cm\" text:min-label-width=\"0.6cm\"/></text:list-level-style-number></text:list-style>" +
            "</office:automatic-styles>"
        val content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<office:document-content $NS>$auto<office:body><office:text>$body</office:text></office:body></office:document-content>"
        pack(out, "application/vnd.oasis.opendocument.text", content, stylesXml(doc.rtl), media)
    }

    /** Sheets of strings (numbers written as float cells). */
    fun ods(sheets: List<Pair<String, List<List<String>>>>, out: OutputStream, rtl: Boolean) {
        val body = StringBuilder()
        sheets.ifEmpty { listOf("Sheet1" to emptyList()) }.forEach { (name, rows) ->
            body.append("<table:table table:name=\"").append(x(name.take(31).ifBlank { "Sheet" })).append("\" table:style-name=\"${if (rtl) "TaR" else "TaL"}\">")
            val cols = rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
            body.append("<table:table-column table:number-columns-repeated=\"$cols\"/>")
            if (rows.isEmpty()) body.append("<table:table-row><table:table-cell/></table:table-row>")
            rows.forEach { r ->
                body.append("<table:table-row>")
                for (k in 0 until cols) {
                    val v = r.getOrElse(k) { "" }
                    val num = Csv6.number(v)
                    when {
                        v.isEmpty() -> body.append("<table:table-cell/>")
                        num != null -> body.append("<table:table-cell office:value-type=\"float\" office:value=\"").append(num).append("\"><text:p>").append(x(v)).append("</text:p></table:table-cell>")
                        else -> body.append("<table:table-cell office:value-type=\"string\"><text:p>").append(x(v)).append("</text:p></table:table-cell>")
                    }
                }
                body.append("</table:table-row>")
            }
            body.append("</table:table>")
        }
        val auto = "<office:automatic-styles><style:style style:name=\"TaL\" style:family=\"table\"><style:table-properties table:display=\"true\" style:writing-mode=\"lr-tb\"/></style:style>" +
            "<style:style style:name=\"TaR\" style:family=\"table\"><style:table-properties table:display=\"true\" style:writing-mode=\"rl-tb\"/></style:style></office:automatic-styles>"
        val content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<office:document-content $NS>$auto<office:body><office:spreadsheet>$body</office:spreadsheet></office:body></office:document-content>"
        pack(out, "application/vnd.oasis.opendocument.spreadsheet", content, stylesXml(rtl))
    }

    /** One slide per entry: a title, bullet lines and an optional picture (PNG/JPEG bytes) filling the slide. */
    class Slide(val title: String, val lines: List<String>, val picture: ByteArray? = null)

    fun odp(slides: List<Slide>, out: OutputStream, rtl: Boolean, widthCm: Float = 28f, heightCm: Float = 15.75f) {
        val media = ArrayList<Pair<String, ByteArray>>()
        val body = StringBuilder()
        slides.forEachIndexed { i, s ->
            body.append("<draw:page draw:name=\"page${i + 1}\" draw:master-page-name=\"Default\">")
            if (s.picture != null) {
                val ext = Images6.sniffExt(s.picture) ?: "jpg"
                val n = "slide${i + 1}.$ext"; media += n to s.picture
                body.append("<draw:frame svg:x=\"0cm\" svg:y=\"0cm\" svg:width=\"${widthCm}cm\" svg:height=\"${heightCm}cm\"><draw:image xlink:href=\"Pictures/$n\" xlink:type=\"simple\" xlink:show=\"embed\" xlink:actuate=\"onLoad\"/></draw:frame>")
            }
            val ps = if (rtl) "PR" else "PL"
            if (s.title.isNotBlank()) body.append("<draw:frame presentation:class=\"title\" svg:x=\"1cm\" svg:y=\"0.6cm\" svg:width=\"${widthCm - 2}cm\" svg:height=\"2.2cm\"><draw:text-box><text:p text:style-name=\"$ps\"><text:span text:style-name=\"TT\">")
                .append(x(s.title)).append("</text:span></text:p></draw:text-box></draw:frame>")
            if (s.lines.isNotEmpty()) {
                body.append("<draw:frame presentation:class=\"outline\" svg:x=\"1cm\" svg:y=\"3.2cm\" svg:width=\"${widthCm - 2}cm\" svg:height=\"${heightCm - 4}cm\"><draw:text-box>")
                s.lines.forEach { l -> body.append("<text:p text:style-name=\"${if (Text6.rtlDominant(l)) "PR" else "PL"}\">").append(x(l)).append("</text:p>") }
                body.append("</draw:text-box></draw:frame>")
            }
            body.append("</draw:page>")
        }
        val auto = "<office:automatic-styles><style:style style:name=\"PL\" style:family=\"paragraph\"><style:paragraph-properties style:writing-mode=\"lr-tb\"/><style:text-properties fo:font-size=\"18pt\"/></style:style>" +
            "<style:style style:name=\"PR\" style:family=\"paragraph\"><style:paragraph-properties style:writing-mode=\"rl-tb\" fo:text-align=\"end\"/><style:text-properties fo:font-size=\"18pt\"/></style:style>" +
            "<style:style style:name=\"TT\" style:family=\"text\"><style:text-properties fo:font-size=\"30pt\" fo:font-weight=\"bold\"/></style:style></office:automatic-styles>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<office:document-styles $NS><office:automatic-styles><style:page-layout style:name=\"PM1\"><style:page-layout-properties fo:page-width=\"${widthCm}cm\" fo:page-height=\"${heightCm}cm\" style:print-orientation=\"landscape\"/></style:page-layout></office:automatic-styles>" +
            "<office:master-styles><style:master-page style:name=\"Default\" style:page-layout-name=\"PM1\"/></office:master-styles></office:document-styles>"
        val content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<office:document-content $NS>$auto<office:body><office:presentation>$body</office:presentation></office:body></office:document-content>"
        pack(out, "application/vnd.oasis.opendocument.presentation", content, styles, media)
    }
}
