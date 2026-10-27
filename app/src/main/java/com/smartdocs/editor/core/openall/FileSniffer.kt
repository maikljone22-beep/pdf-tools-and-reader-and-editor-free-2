package com.smartdocs.editor.core.openall

import java.io.File
import java.util.zip.ZipFile

/** Every file type the app knows how to show (open-all). */
enum class FType {
    PDF, DOCX, DOC, RTF, ODT, TEXT, MARKDOWN, HTML, MHT, CODE, XPS, DJVU, EPUB, FB2, MOBI, CHM,
    XLSX, XLS, ODS, CSV, IWORK, PPTX, PPT, ODP,
    IMAGE, SVG, TIFF, HEIF, AVIF, RAW,
    CBZ, CB7, CBR, ZIP, SEVENZ, TAR, GZIP, BZIP2, XZ, RAR,
    EML, MSG, VCF, ICS, AUDIO, VIDEO, FONT, WOFF, WOFF2, DXF, DWG, FDF, UNKNOWN;

    val isArchive get() = this in setOf(ZIP, SEVENZ, TAR, GZIP, BZIP2, XZ)
    val isComic get() = this == CBZ || this == CB7
    val isMedia get() = this == AUDIO || this == VIDEO
    val isImage get() = this in setOf(IMAGE, SVG, TIFF, HEIF, AVIF, RAW)
}

/**
 * File-type detection by magic bytes first, then by name / MIME type (open-all). Pure JVM: the head bytes and, for
 * containers, the ZIP entry names / `mimetype` entry or the OLE2 stream names are passed in.
 */
object FileSniffer {

    const val HEAD = 4096

    private val EXT: Map<String, FType> = buildMap {
        fun put(t: FType, vararg e: String) = e.forEach { put(it, t) }
        put(FType.PDF, "pdf")
        put(FType.DOCX, "docx", "docm", "dotx", "dotm")
        put(FType.DOC, "doc", "dot", "wps")
        put(FType.RTF, "rtf")
        put(FType.ODT, "odt", "ott", "fodt")
        put(FType.TEXT, "txt", "text", "log", "nfo", "me", "readme", "srt", "vtt", "sub", "lrc", "diz", "asc")
        put(FType.MARKDOWN, "md", "markdown", "mdown", "mkd", "mkdn")
        put(FType.HTML, "html", "htm", "xhtml", "xht", "shtml")
        put(FType.MHT, "mht", "mhtml")
        put(FType.CODE, "xml", "json", "jsonl", "geojson", "yaml", "yml", "ini", "cfg", "conf", "toml", "properties", "env",
            "kt", "kts", "java", "gradle", "py", "js", "mjs", "ts", "tsx", "jsx", "c", "h", "cpp", "cc", "hpp", "cs", "go", "rs",
            "swift", "m", "php", "rb", "pl", "lua", "sh", "bash", "zsh", "bat", "cmd", "ps1", "sql", "css", "scss", "less",
            "dart", "r", "scala", "groovy", "vue", "svelte", "tex", "bib", "plist", "xsd", "xsl", "xslt", "wsdl", "rss", "atom",
            "gpx", "kml", "opml", "csproj", "sln", "pom", "iml", "manifest", "gitignore", "dockerfile", "makefile", "cmake", "diff", "patch")
        put(FType.XPS, "xps", "oxps")
        put(FType.DJVU, "djvu", "djv")
        put(FType.EPUB, "epub")
        put(FType.FB2, "fb2")
        put(FType.MOBI, "mobi", "azw", "azw3", "kf8", "prc", "pdb")
        put(FType.CHM, "chm")
        put(FType.XLSX, "xlsx", "xlsm", "xltx", "xltm")
        put(FType.XLS, "xls", "xlt")
        put(FType.ODS, "ods", "ots", "fods")
        put(FType.CSV, "csv", "tsv", "tab", "psv")
        put(FType.IWORK, "numbers", "pages", "key")
        put(FType.PPTX, "pptx", "pptm", "ppsx", "ppsm", "potx", "potm")
        put(FType.PPT, "ppt", "pps", "pot")
        put(FType.ODP, "odp", "otp", "fodp")
        put(FType.IMAGE, "jpg", "jpeg", "jpe", "jfif", "png", "apng", "gif", "webp", "bmp", "dib", "ico", "cur", "wbmp")
        put(FType.SVG, "svg", "svgz")
        put(FType.TIFF, "tif", "tiff")
        put(FType.HEIF, "heic", "heif", "hif")
        put(FType.AVIF, "avif")
        put(FType.RAW, "dng", "cr2", "cr3", "nef", "nrw", "arw", "orf", "rw2", "raf", "pef", "srw")
        put(FType.CBZ, "cbz")
        put(FType.CB7, "cb7")
        put(FType.CBR, "cbr")
        put(FType.ZIP, "zip", "jar", "apk", "aar", "xpi", "crx")
        put(FType.SEVENZ, "7z")
        put(FType.TAR, "tar")
        put(FType.GZIP, "gz", "tgz", "gzip")
        put(FType.BZIP2, "bz2", "tbz", "tbz2")
        put(FType.XZ, "xz", "txz")
        put(FType.RAR, "rar")
        put(FType.EML, "eml", "emlx")
        put(FType.MSG, "msg")
        put(FType.VCF, "vcf", "vcard")
        put(FType.ICS, "ics", "ical", "ifb", "vcs")
        put(FType.AUDIO, "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "awb", "mka", "weba", "wma", "aif", "aiff", "mid", "midi")
        put(FType.VIDEO, "mp4", "m4v", "mkv", "webm", "3gp", "3g2", "mov", "ts", "m2ts", "mts", "avi", "ogv", "flv", "wmv", "mpg", "mpeg")
        put(FType.FONT, "ttf", "otf", "ttc", "otc")
        put(FType.WOFF, "woff")
        put(FType.WOFF2, "woff2")
        put(FType.DXF, "dxf")
        put(FType.DWG, "dwg")
        put(FType.FDF, "fdf", "xfdf")
    }

    private val AUDIO_EXT = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "mka", "weba")

    fun extOf(name: String): String {
        val n = name.substringAfterLast('/').lowercase()
        if (n.endsWith(".tar.gz") || n.endsWith(".tar.bz2") || n.endsWith(".tar.xz")) return n.substringAfterLast('.')
        if (n.endsWith(".fb2.zip")) return "fb2.zip"
        return n.substringAfterLast('.', "")
    }

    fun byExtension(name: String): FType {
        val e = extOf(name)
        if (e == "fb2.zip") return FType.FB2
        return EXT[e] ?: when (name.substringAfterLast('/').lowercase()) {
            "readme", "license", "licence", "changelog", "copying", "authors" -> FType.TEXT
            "makefile", "dockerfile", "gradlew" -> FType.CODE
            else -> FType.UNKNOWN
        }
    }

    fun byMime(mime: String?): FType {
        val m = mime?.lowercase()?.substringBefore(';')?.trim() ?: return FType.UNKNOWN
        return when {
            m == "application/pdf" -> FType.PDF
            m == "image/svg+xml" -> FType.SVG
            m == "image/tiff" -> FType.TIFF
            m == "image/heic" || m == "image/heif" -> FType.HEIF
            m == "image/avif" -> FType.AVIF
            m == "image/x-adobe-dng" || m.startsWith("image/x-") && m.contains("raw") -> FType.RAW
            m == "image/vnd.djvu" || m == "image/x-djvu" -> FType.DJVU
            m == "image/vnd.dxf" || m == "application/dxf" -> FType.DXF
            m == "image/vnd.dwg" || m == "application/acad" -> FType.DWG
            m.startsWith("image/") -> FType.IMAGE
            m.startsWith("audio/") -> FType.AUDIO
            m.startsWith("video/") -> FType.VIDEO
            m.startsWith("font/") || m.contains("font-ttf") || m.contains("font-otf") -> if (m.contains("woff2")) FType.WOFF2 else if (m.contains("woff")) FType.WOFF else FType.FONT
            m == "text/html" || m == "application/xhtml+xml" -> FType.HTML
            m == "multipart/related" || m == "application/x-mimearchive" -> FType.MHT
            m == "message/rfc822" -> FType.EML
            m == "application/vnd.ms-outlook" -> FType.MSG
            m == "text/vcard" || m == "text/x-vcard" || m == "text/directory" -> FType.VCF
            m == "text/calendar" -> FType.ICS
            m == "text/markdown" || m == "text/x-markdown" -> FType.MARKDOWN
            m == "text/csv" || m == "text/comma-separated-values" || m == "text/tab-separated-values" -> FType.CSV
            m == "text/rtf" || m == "application/rtf" -> FType.RTF
            m == "application/json" || m == "application/xml" || m == "text/xml" || m.endsWith("+xml") || m.endsWith("+json") || m == "application/javascript" || m == "application/x-yaml" -> FType.CODE
            m.startsWith("text/") -> FType.TEXT
            m == "application/epub+zip" -> FType.EPUB
            m == "application/x-fictionbook+xml" || m == "application/x-fictionbook" -> FType.FB2
            m == "application/x-mobipocket-ebook" || m == "application/vnd.amazon.ebook" || m == "application/vnd.amazon.mobi8-ebook" -> FType.MOBI
            m == "application/vnd.ms-htmlhelp" || m == "application/x-chm" -> FType.CHM
            m == "application/vnd.ms-xpsdocument" || m == "application/oxps" -> FType.XPS
            m.contains("wordprocessingml") || m.startsWith("application/vnd.ms-word") -> FType.DOCX
            m == "application/msword" -> FType.DOC
            m.contains("spreadsheetml") || m.startsWith("application/vnd.ms-excel.") -> FType.XLSX
            m == "application/vnd.ms-excel" -> FType.XLS
            m.contains("presentationml") || m.startsWith("application/vnd.ms-powerpoint.") -> FType.PPTX
            m == "application/vnd.ms-powerpoint" -> FType.PPT
            m.startsWith("application/vnd.oasis.opendocument.text") -> FType.ODT
            m.startsWith("application/vnd.oasis.opendocument.spreadsheet") -> FType.ODS
            m.startsWith("application/vnd.oasis.opendocument.presentation") -> FType.ODP
            m == "application/vnd.comicbook+zip" || m == "application/x-cbz" -> FType.CBZ
            m == "application/x-cb7" -> FType.CB7
            m == "application/vnd.comicbook-rar" || m == "application/x-cbr" -> FType.CBR
            m == "application/zip" || m == "application/x-zip-compressed" || m == "application/java-archive" || m == "application/vnd.android.package-archive" -> FType.ZIP
            m == "application/x-7z-compressed" -> FType.SEVENZ
            m == "application/x-tar" || m == "application/x-gtar" -> FType.TAR
            m == "application/gzip" || m == "application/x-gzip" || m == "application/x-compressed-tar" -> FType.GZIP
            m == "application/x-bzip2" || m == "application/x-bzip" -> FType.BZIP2
            m == "application/x-xz" -> FType.XZ
            m == "application/vnd.rar" || m == "application/x-rar-compressed" || m == "application/x-rar" -> FType.RAR
            m == "application/vnd.fdf" || m == "application/vnd.adobe.xfdf" -> FType.FDF
            else -> FType.UNKNOWN
        }
    }

    private fun u8(b: ByteArray, i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
    private fun sig(b: ByteArray, at: Int, vararg v: Int) = b.size >= at + v.size && v.indices.all { u8(b, at + it) == v[it] }
    private fun ascii(b: ByteArray, at: Int, s: String) = b.size >= at + s.length && s.indices.all { u8(b, at + it) == s[it].code }

    /**
     * Detects the type. [head] = first bytes (up to [HEAD]). For a ZIP, [zipEntries] / [zipMimetype] refine it;
     * for an OLE2 compound file, [oleStreams] (top-level entry names). [mime] is the sender's type, used only when the
     * content and the name say nothing.
     */
    fun detect(name: String, head: ByteArray, mime: String? = null, zipEntries: Set<String>? = null, zipMimetype: String? = null, oleStreams: Collection<String>? = null): FType {
        val ext = extOf(name)
        val byExt = byExtension(name)
        val hint = if (byExt != FType.UNKNOWN) byExt else byMime(mime)
        // a few bytes of junk before %PDF are tolerated by readers
        if (ascii(head, 0, "%PDF") || (hint == FType.PDF && String(head.copyOf(minOf(head.size, 1024)), Charsets.ISO_8859_1).contains("%PDF-"))) return FType.PDF
        if (ascii(head, 0, "%FDF")) return FType.FDF
        if (sig(head, 0, 0x50, 0x4B, 0x03, 0x04) || sig(head, 0, 0x50, 0x4B, 0x05, 0x06) || sig(head, 0, 0x50, 0x4B, 0x07, 0x08)) return zipKind(hint, zipEntries, zipMimetype)
        if (sig(head, 0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) return oleKind(hint, oleStreams)
        if (ascii(head, 0, "{\\rtf")) return FType.RTF
        if (sig(head, 0, 0xFF, 0xD8, 0xFF)) return FType.IMAGE
        if (sig(head, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return FType.IMAGE
        if (ascii(head, 0, "GIF87a") || ascii(head, 0, "GIF89a")) return FType.IMAGE
        if (ascii(head, 0, "RIFF") && head.size >= 12) {
            return when {
                ascii(head, 8, "WEBP") -> FType.IMAGE
                ascii(head, 8, "WAVE") -> FType.AUDIO
                ascii(head, 8, "AVI ") -> FType.VIDEO
                else -> hint
            }
        }
        if (ascii(head, 0, "BM") && head.size >= 14 && sig(head, 6, 0, 0, 0, 0)) return FType.IMAGE
        if (sig(head, 0, 0, 0, 1, 0) && (ext == "ico" || ext == "cur" || hint == FType.IMAGE) || sig(head, 0, 0, 0, 2, 0) && ext == "cur") return FType.IMAGE
        if (sig(head, 0, 0x49, 0x49, 0x2A, 0x00) || sig(head, 0, 0x4D, 0x4D, 0x00, 0x2A)) return if (hint == FType.RAW) FType.RAW else FType.TIFF
        if (ascii(head, 4, "ftyp")) return isoKind(head, hint, ext)
        if (ascii(head, 0, "AT&TFORM") || ascii(head, 0, "FORM") && ascii(head, 8, "DJV")) return FType.DJVU
        if (ascii(head, 0, "ITSF")) return FType.CHM
        if (head.size >= 68 && (ascii(head, 60, "BOOKMOBI") || ascii(head, 60, "TEXtREAd"))) return FType.MOBI
        if (sig(head, 0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return if (hint == FType.CB7) FType.CB7 else FType.SEVENZ
        if (ascii(head, 0, "Rar!\u001A\u0007")) return if (hint == FType.CBR) FType.CBR else FType.RAR
        if (sig(head, 0, 0x1F, 0x8B)) return if (hint == FType.SVG) FType.SVG else FType.GZIP
        if (ascii(head, 0, "BZh") && u8(head, 3) in '1'.code..'9'.code) return FType.BZIP2
        if (sig(head, 0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)) return FType.XZ
        if (ascii(head, 257, "ustar")) return FType.TAR
        if (ascii(head, 0, "ID3") || ascii(head, 0, "fLaC") || ascii(head, 0, "#!AMR") || ascii(head, 0, "MThd") || ascii(head, 0, "FORM") && ascii(head, 8, "AIF")) return FType.AUDIO
        if (ascii(head, 0, "OggS")) return if (ext == "ogv") FType.VIDEO else FType.AUDIO
        if (sig(head, 0, 0x1A, 0x45, 0xDF, 0xA3)) return if (ext == "mka" || ext == "weba" || hint == FType.AUDIO) FType.AUDIO else FType.VIDEO
        if (ascii(head, 0, "FLV")) return FType.VIDEO
        if (sig(head, 0, 0x00, 0x00, 0x01, 0xBA) || sig(head, 0, 0x00, 0x00, 0x01, 0xB3)) return FType.VIDEO
        if (sig(head, 0, 0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11)) return if (ext == "wma") FType.AUDIO else FType.VIDEO
        if (u8(head, 0) == 0xFF && (u8(head, 1) and 0xE0) == 0xE0 && (hint == FType.AUDIO || hint == FType.UNKNOWN && u8(head, 1) in setOf(0xFB, 0xFA, 0xF3, 0xF2, 0xF1, 0xF9, 0xE3))) return FType.AUDIO
        if (ascii(head, 0, "wOFF")) return FType.WOFF
        if (ascii(head, 0, "wOF2")) return FType.WOFF2
        if (ascii(head, 0, "OTTO") || ascii(head, 0, "ttcf") || ascii(head, 0, "true") && hint == FType.FONT || sig(head, 0, 0, 1, 0, 0) && (hint == FType.FONT || plausibleSfnt(head))) return FType.FONT
        if (ascii(head, 0, "AC10") || ascii(head, 0, "AC1.") || ascii(head, 0, "AC2.")) return FType.DWG
        if (ascii(head, 0, "AutoCAD Binary DXF")) return FType.DXF
        if (u8(head, 0) == 0x47 && head.size > 188 && u8(head, 188) == 0x47 && (hint == FType.VIDEO || hint == FType.UNKNOWN)) return FType.VIDEO
        return textKind(hint, head, ext)
    }

    /** An sfnt directory whose table count and first tag look real (00 01 00 00 alone is too common). */
    private fun plausibleSfnt(b: ByteArray): Boolean {
        if (b.size < 16) return false
        val n = (u8(b, 4) shl 8) or u8(b, 5)
        if (n !in 4..64) return false
        return (12 until 16).all { u8(b, it) in 0x20..0x7E }
    }

    private fun isoKind(b: ByteArray, hint: FType, ext: String): FType {
        val boxSize = ((u8(b, 0) shl 24) or (u8(b, 1) shl 16) or (u8(b, 2) shl 8) or u8(b, 3)).coerceIn(16, minOf(b.size, 256))
        val brands = ArrayList<String>()
        brands += String(b, 8, 4, Charsets.ISO_8859_1)
        var i = 16
        while (i + 4 <= boxSize) { brands += String(b, i, 4, Charsets.ISO_8859_1); i += 4 }
        val major = brands.first()
        return when {
            major == "avif" || major == "avis" || brands.contains("avif") && major in setOf("mif1", "msf1") -> FType.AVIF
            major in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1") -> FType.HEIF
            major == "crx " -> FType.RAW
            major.startsWith("M4A") || major.startsWith("M4B") || major.startsWith("M4P") || major == "F4A " -> FType.AUDIO
            ext in AUDIO_EXT || hint == FType.AUDIO -> FType.AUDIO
            else -> FType.VIDEO
        }
    }

    private fun zipKind(hint: FType, entries: Set<String>?, mimetype: String?): FType {
        when (mimetype?.trim()) {
            "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.text-template" -> return FType.ODT
            "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.spreadsheet-template" -> return FType.ODS
            "application/vnd.oasis.opendocument.presentation", "application/vnd.oasis.opendocument.presentation-template" -> return FType.ODP
            "application/epub+zip" -> return FType.EPUB
            "application/vnd.ms-xpsdocument", "application/oxps" -> return FType.XPS
        }
        if (entries != null) {
            if ("word/document.xml" in entries) return FType.DOCX
            if ("xl/workbook.xml" in entries) return FType.XLSX
            if ("ppt/presentation.xml" in entries) return FType.PPTX
            if ("META-INF/container.xml" in entries) return FType.EPUB
            if (entries.any { it.equals("FixedDocumentSequence.fdseq", true) || it.endsWith(".fpage", true) }) return FType.XPS
            if (entries.any { it.startsWith("Index/") && it.endsWith(".iwa") } || "Index.zip" in entries || "QuickLook/Preview.pdf" in entries && hint == FType.IWORK) return FType.IWORK
            if ("content.xml" in entries && hint in setOf(FType.ODT, FType.ODS, FType.ODP)) return hint
            val files = entries.filter { !it.endsWith("/") }
            if (files.size == 1 && files[0].endsWith(".fb2", true)) return FType.FB2
            if (hint == FType.CBZ) return FType.CBZ
            if (files.isNotEmpty() && files.size >= 2 && files.all { isImageName(it) || it.endsWith(".xml", true) || it.endsWith(".txt", true) } && files.count { isImageName(it) } >= 2) return FType.CBZ
        }
        return if (hint in setOf(FType.DOCX, FType.XLSX, FType.PPTX, FType.ODT, FType.ODS, FType.ODP, FType.EPUB, FType.XPS, FType.IWORK, FType.CBZ, FType.FB2)) hint else FType.ZIP
    }

    fun isImageName(n: String) = n.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "heic", "jxl")

    private fun oleKind(hint: FType, streams: Collection<String>?): FType {
        if (streams != null) {
            if (streams.any { it.equals("WordDocument", true) }) return FType.DOC
            if (streams.any { it.equals("Workbook", true) || it.equals("Book", true) }) return FType.XLS
            if (streams.any { it.equals("PowerPoint Document", true) }) return FType.PPT
            if (streams.any { it.startsWith("__substg1.0_", true) || it.startsWith("__properties_version1.0", true) }) return FType.MSG
        }
        return if (hint in setOf(FType.DOC, FType.XLS, FType.PPT, FType.MSG)) hint else FType.UNKNOWN
    }

    /** Decides between the text kinds: by content markers, then by name. */
    private fun textKind(hint: FType, head: ByteArray, ext: String): FType {
        if (head.isEmpty()) return if (hint == FType.UNKNOWN) FType.TEXT else hint
        // binary content behind a text name (".txt" that is not text) goes to the file-info screen
        if (!TextDecode.looksText(head)) return if (hint.isText()) FType.UNKNOWN else hint
        val s = TextDecode.decode(head).text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val low = s.take(2048).lowercase()
        if (low.startsWith("begin:vcard")) return FType.VCF
        if (low.startsWith("begin:vcalendar")) return FType.ICS
        if (low.startsWith("<?xml") || low.startsWith("<")) {
            val root = Regex("""<([a-zA-Z][\w:.-]*)""").findAll(low).map { it.groupValues[1] }.firstOrNull { !it.startsWith("!") && it != "?xml" }
            when {
                root == "svg" || root?.endsWith(":svg") == true -> return FType.SVG
                root == "fictionbook" -> return FType.FB2
                low.startsWith("<!doctype html") || root == "html" -> return if (hint == FType.MHT) FType.MHT else FType.HTML
                root == "xfdf" -> return FType.FDF
            }
            if (hint == FType.UNKNOWN || hint == FType.TEXT) return FType.CODE
        }
        if (Regex("""^\s*0\s*\r?\n\s*SECTION\s*\r?\n""").containsMatchIn(s.take(64)) || hint == FType.DXF) return FType.DXF
        if (hint == FType.MHT || hint == FType.UNKNOWN && low.contains("multipart/related") && low.contains("mime-version")) return FType.MHT
        if ((hint == FType.UNKNOWN || hint == FType.TEXT && ext == "") && Regex("""(?m)^(from|received|return-path|mime-version|message-id|subject|delivered-to):""").containsMatchIn(low.take(1024))) return FType.EML
        if (hint == FType.UNKNOWN && (low.startsWith("{") || low.startsWith("["))) return FType.CODE
        return if (hint == FType.UNKNOWN) FType.TEXT else hint
    }

    private fun FType.isText() = this in setOf(FType.TEXT, FType.MARKDOWN, FType.HTML, FType.CODE, FType.CSV, FType.VCF, FType.ICS, FType.EML, FType.MHT, FType.DXF, FType.SVG, FType.FB2)

    /** Reads the head of [f] (and the ZIP / OLE2 directory when needed) and detects its type. */
    fun of(f: File, name: String = f.name, mime: String? = null): FType {
        val head = readHead(f)
        if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
            val (entries, mt) = runCatching {
                ZipFile(f).use { z ->
                    val names = HashSet<String>()
                    val it = z.entries()
                    while (it.hasMoreElements() && names.size < 5000) names += it.nextElement().name
                    val m = z.getEntry("mimetype")?.takeIf { it.size in 1..200 }?.let { e -> z.getInputStream(e).use { s -> String(s.readBytes(), Charsets.US_ASCII) } }
                    names to m
                }
            }.getOrDefault(null to null)
            return detect(name, head, mime, entries, mt)
        }
        if (head.size >= 8 && (head[0].toInt() and 0xFF) == 0xD0 && (head[1].toInt() and 0xFF) == 0xCF) {
            val streams = if (f.length() <= 64L * 1024 * 1024) runCatching { com.smartdocs.editor.core.convert6.CfbReader(f.readBytes()).streamNames() }.getOrNull() else null
            return detect(name, head, mime, oleStreams = streams)
        }
        return detect(name, head, mime)
    }

    fun readHead(f: File, n: Int = HEAD): ByteArray = runCatching {
        f.inputStream().use { s ->
            val b = ByteArray(n); var off = 0
            while (off < n) { val r = s.read(b, off, n - off); if (r <= 0) break; off += r }
            b.copyOf(off)
        }
    }.getOrDefault(ByteArray(0))

    /** A file extension for content of type [t] (used to name imports that came without one). */
    fun extFor(t: FType): String = when (t) {
        FType.PDF -> "pdf"; FType.DOCX -> "docx"; FType.DOC -> "doc"; FType.RTF -> "rtf"; FType.ODT -> "odt"; FType.TEXT -> "txt"
        FType.MARKDOWN -> "md"; FType.HTML -> "html"; FType.MHT -> "mht"; FType.CODE -> "txt"; FType.XPS -> "xps"; FType.DJVU -> "djvu"
        FType.EPUB -> "epub"; FType.FB2 -> "fb2"; FType.MOBI -> "mobi"; FType.CHM -> "chm"; FType.XLSX -> "xlsx"; FType.XLS -> "xls"
        FType.ODS -> "ods"; FType.CSV -> "csv"; FType.IWORK -> "numbers"; FType.PPTX -> "pptx"; FType.PPT -> "ppt"; FType.ODP -> "odp"
        FType.IMAGE -> "jpg"; FType.SVG -> "svg"; FType.TIFF -> "tif"; FType.HEIF -> "heic"; FType.AVIF -> "avif"; FType.RAW -> "dng"
        FType.CBZ -> "cbz"; FType.CB7 -> "cb7"; FType.CBR -> "cbr"; FType.ZIP -> "zip"; FType.SEVENZ -> "7z"; FType.TAR -> "tar"
        FType.GZIP -> "gz"; FType.BZIP2 -> "bz2"; FType.XZ -> "xz"; FType.RAR -> "rar"; FType.EML -> "eml"; FType.MSG -> "msg"
        FType.VCF -> "vcf"; FType.ICS -> "ics"; FType.AUDIO -> "mp3"; FType.VIDEO -> "mp4"; FType.FONT -> "ttf"; FType.WOFF -> "woff"
        FType.WOFF2 -> "woff2"; FType.DXF -> "dxf"; FType.DWG -> "dwg"; FType.FDF -> "fdf"; FType.UNKNOWN -> "bin"
    }

    /** Image extension by its magic bytes (jpg/png/gif/webp/bmp/ico) for naming; null when not one of them. */
    fun imageExt(head: ByteArray): String? = when {
        sig(head, 0, 0xFF, 0xD8, 0xFF) -> "jpg"
        sig(head, 0, 0x89, 0x50, 0x4E, 0x47) -> "png"
        ascii(head, 0, "GIF8") -> "gif"
        ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP") -> "webp"
        ascii(head, 0, "BM") -> "bmp"
        sig(head, 0, 0, 0, 1, 0) -> "ico"
        else -> null
    }

    /** MIME type for sharing / "open with". */
    fun mimeOf(t: FType, ext: String): String = when (t) {
        FType.PDF -> "application/pdf"; FType.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        FType.DOC -> "application/msword"; FType.RTF -> "application/rtf"; FType.ODT -> "application/vnd.oasis.opendocument.text"
        FType.TEXT, FType.CODE -> if (ext == "json") "application/json" else if (ext == "xml") "text/xml" else "text/plain"
        FType.MARKDOWN -> "text/markdown"; FType.HTML -> "text/html"; FType.MHT -> "multipart/related"; FType.XPS -> "application/vnd.ms-xpsdocument"
        FType.DJVU -> "image/vnd.djvu"; FType.EPUB -> "application/epub+zip"; FType.FB2 -> "application/x-fictionbook+xml"
        FType.MOBI -> "application/x-mobipocket-ebook"; FType.CHM -> "application/vnd.ms-htmlhelp"
        FType.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; FType.XLS -> "application/vnd.ms-excel"
        FType.ODS -> "application/vnd.oasis.opendocument.spreadsheet"; FType.CSV -> "text/csv"
        FType.PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"; FType.PPT -> "application/vnd.ms-powerpoint"
        FType.ODP -> "application/vnd.oasis.opendocument.presentation"
        FType.IMAGE -> when (ext) { "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"; "ico" -> "image/x-icon"; else -> "image/jpeg" }
        FType.SVG -> "image/svg+xml"; FType.TIFF -> "image/tiff"; FType.HEIF -> "image/heif"; FType.AVIF -> "image/avif"; FType.RAW -> "image/x-adobe-dng"
        FType.CBZ -> "application/vnd.comicbook+zip"; FType.CB7 -> "application/x-cb7"; FType.CBR -> "application/vnd.comicbook-rar"
        FType.ZIP -> "application/zip"; FType.SEVENZ -> "application/x-7z-compressed"; FType.TAR -> "application/x-tar"
        FType.GZIP -> "application/gzip"; FType.BZIP2 -> "application/x-bzip2"; FType.XZ -> "application/x-xz"; FType.RAR -> "application/vnd.rar"
        FType.EML -> "message/rfc822"; FType.MSG -> "application/vnd.ms-outlook"; FType.VCF -> "text/vcard"; FType.ICS -> "text/calendar"
        FType.AUDIO -> "audio/*"; FType.VIDEO -> "video/*"; FType.FONT -> if (ext == "otf") "font/otf" else "font/ttf"
        FType.WOFF -> "font/woff"; FType.WOFF2 -> "font/woff2"; FType.DXF -> "image/vnd.dxf"; FType.DWG -> "image/vnd.dwg"
        FType.FDF -> if (ext == "xfdf") "application/vnd.adobe.xfdf" else "application/vnd.fdf"
        FType.IWORK -> "application/octet-stream"; FType.UNKNOWN -> "application/octet-stream"
    }
}
