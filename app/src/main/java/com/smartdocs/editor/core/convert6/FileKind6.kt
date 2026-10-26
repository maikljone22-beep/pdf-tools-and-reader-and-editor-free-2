package com.smartdocs.editor.core.convert6

import java.io.File
import java.util.zip.ZipFile

/** Input kinds of the Convert hub. Finer than tools5 [com.smartdocs.editor.core.tools5.SourceKind]. */
enum class Kind6 {
    PDF, DOCX, DOC, ODT, RTF, XLSX, XLS, ODS, CSV, PPTX, PPT, ODP,
    JPG, PNG, WEBP, GIF, BMP, HEIC, TIFF,
    TEXT, MARKDOWN, HTML, EPUB, EML, ZIP, UNKNOWN;

    val isImage get() = this in IMAGES
    val isWordLike get() = this == DOCX || this == DOC || this == ODT || this == RTF
    val isSheetLike get() = this == XLSX || this == XLS || this == ODS || this == CSV
    val isSlideLike get() = this == PPTX || this == PPT || this == ODP

    companion object {
        val IMAGES = setOf(JPG, PNG, WEBP, GIF, BMP, HEIC, TIFF)
    }
}

/**
 * File-type detection from magic bytes first, then the name. Pure JVM: the head bytes and (for ZIP packages)
 * the entry names / `mimetype` entry are passed in, so the decision logic is unit-testable.
 */
object FileKind6 {

    fun byExtension(name: String): Kind6 = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> Kind6.PDF
        "docx", "docm", "dotx" -> Kind6.DOCX
        "doc", "dot" -> Kind6.DOC
        "odt", "ott" -> Kind6.ODT
        "rtf" -> Kind6.RTF
        "xlsx", "xlsm", "xltx" -> Kind6.XLSX
        "xls", "xlt" -> Kind6.XLS
        "ods", "ots" -> Kind6.ODS
        "csv", "tsv" -> Kind6.CSV
        "pptx", "ppsx", "potx" -> Kind6.PPTX
        "ppt", "pps" -> Kind6.PPT
        "odp", "otp" -> Kind6.ODP
        "jpg", "jpeg", "jfif", "jpe" -> Kind6.JPG
        "png" -> Kind6.PNG
        "webp" -> Kind6.WEBP
        "gif" -> Kind6.GIF
        "bmp", "dib" -> Kind6.BMP
        "heic", "heif", "hif", "avif" -> Kind6.HEIC
        "tif", "tiff" -> Kind6.TIFF
        "txt", "log", "json", "xml", "kt", "java", "py", "js", "c", "cpp", "h", "ini", "yaml", "yml", "sql", "sh" -> Kind6.TEXT
        "md", "markdown", "mdown" -> Kind6.MARKDOWN
        "html", "htm", "xhtml", "mht" -> Kind6.HTML
        "epub" -> Kind6.EPUB
        "eml" -> Kind6.EML
        "zip" -> Kind6.ZIP
        else -> Kind6.UNKNOWN
    }

    private fun startsWith(b: ByteArray, vararg sig: Int, at: Int = 0): Boolean =
        b.size >= at + sig.size && sig.indices.all { (b[at + it].toInt() and 0xFF) == sig[it] }

    private fun ascii(b: ByteArray, at: Int, s: String) = startsWith(b, *s.map { it.code }.toIntArray(), at = at)

    /**
     * [head] = the first bytes of the file (64+ are enough). For a ZIP, [zipEntries] are its entry names and
     * [zipMimetype] the content of its `mimetype` entry (ODF / EPUB), when known.
     */
    fun detect(name: String, head: ByteArray, zipEntries: Set<String>? = null, zipMimetype: String? = null): Kind6 {
        val ext = byExtension(name)
        return when {
            ascii(head, 0, "%PDF") -> Kind6.PDF
            // a few bytes of junk before %PDF are tolerated by readers
            String(head.copyOf(minOf(head.size, 1024)), Charsets.ISO_8859_1).contains("%PDF-") && ext == Kind6.PDF -> Kind6.PDF
            startsWith(head, 0x50, 0x4B, 0x03, 0x04) || startsWith(head, 0x50, 0x4B, 0x05, 0x06) -> zipKind(ext, zipEntries, zipMimetype)
            startsWith(head, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) -> when (ext) {
                Kind6.DOC, Kind6.XLS, Kind6.PPT -> ext
                else -> Kind6.UNKNOWN   // the caller may refine with the CFB stream names ([oleKind])
            }
            ascii(head, 0, "{\\rtf") -> Kind6.RTF
            startsWith(head, 0xFF, 0xD8, 0xFF) -> Kind6.JPG
            startsWith(head, 0x89, 0x50, 0x4E, 0x47) -> Kind6.PNG
            ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP") -> Kind6.WEBP
            ascii(head, 0, "GIF8") -> Kind6.GIF
            ascii(head, 0, "BM") && ext == Kind6.BMP -> Kind6.BMP
            startsWith(head, 0x49, 0x49, 0x2A, 0x00) || startsWith(head, 0x4D, 0x4D, 0x00, 0x2A) -> Kind6.TIFF
            ascii(head, 4, "ftyp") && heifBrand(head) -> Kind6.HEIC
            else -> textKind(ext, head)
        }
    }

    private fun heifBrand(head: ByteArray): Boolean {
        if (head.size < 12) return false
        val brand = String(head, 8, 4, Charsets.ISO_8859_1)
        return brand in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "avif", "avis")
    }

    private fun zipKind(ext: Kind6, entries: Set<String>?, mimetype: String?): Kind6 {
        val mt = mimetype?.trim()
        when (mt) {
            "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.text-template" -> return Kind6.ODT
            "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.spreadsheet-template" -> return Kind6.ODS
            "application/vnd.oasis.opendocument.presentation", "application/vnd.oasis.opendocument.presentation-template" -> return Kind6.ODP
            "application/epub+zip" -> return Kind6.EPUB
        }
        if (entries != null) {
            if ("word/document.xml" in entries) return Kind6.DOCX
            if ("xl/workbook.xml" in entries) return Kind6.XLSX
            if ("ppt/presentation.xml" in entries) return Kind6.PPTX
            if ("META-INF/container.xml" in entries) return Kind6.EPUB
            if ("content.xml" in entries && ext in setOf(Kind6.ODT, Kind6.ODS, Kind6.ODP)) return ext
        }
        return if (ext in setOf(Kind6.DOCX, Kind6.XLSX, Kind6.PPTX, Kind6.ODT, Kind6.ODS, Kind6.ODP, Kind6.EPUB)) ext else Kind6.ZIP
    }

    private fun textKind(ext: Kind6, head: ByteArray): Kind6 {
        val s = String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t').lowercase()
        if (ext == Kind6.UNKNOWN) {
            if (s.startsWith("<!doctype html") || s.startsWith("<html")) return Kind6.HTML
            if (Regex("""^(from|received|return-path|mime-version|message-id|date|subject):""").containsMatchIn(s)) return Kind6.EML
        }
        return ext
    }

    /** CFB (OLE2) refinement by stream names: Word / Excel / PowerPoint 97-2003. */
    fun oleKind(streams: Collection<String>): Kind6 = when {
        streams.any { it.equals("WordDocument", true) } -> Kind6.DOC
        streams.any { it.equals("Workbook", true) || it.equals("Book", true) } -> Kind6.XLS
        streams.any { it.equals("PowerPoint Document", true) } -> Kind6.PPT
        else -> Kind6.UNKNOWN
    }

    /** Detect a real file (reads the head, the ZIP directory or the CFB directory when needed). */
    fun of(f: File): Kind6 {
        val head = runCatching { f.inputStream().use { s -> ByteArray(1024).let { b -> val n = s.read(b); if (n <= 0) ByteArray(0) else b.copyOf(n) } } }.getOrDefault(ByteArray(0))
        val isZip = startsWith(head, 0x50, 0x4B, 0x03, 0x04)
        if (isZip) {
            val (entries, mt) = runCatching {
                ZipFile(f).use { z ->
                    val names = z.entries().asSequence().map { it.name }.toHashSet()
                    val m = z.getEntry("mimetype")?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.US_ASCII) } }
                    names to m
                }
            }.getOrDefault(null to null)
            return detect(f.name, head, entries, mt)
        }
        val k = detect(f.name, head)
        if (k == Kind6.UNKNOWN && startsWith(head, 0xD0, 0xCF, 0x11, 0xE0)) {
            return runCatching { oleKind(CfbReader(f.readBytes()).streamNames()) }.getOrDefault(Kind6.UNKNOWN)
        }
        return k
    }

    /** Mime types offered by the picker for a set of kinds. */
    fun mimes(kinds: Set<Kind6>): Array<String> {
        val out = LinkedHashSet<String>()
        kinds.forEach { k ->
            when (k) {
                Kind6.PDF -> out += "application/pdf"
                Kind6.DOCX -> out += "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                Kind6.DOC -> out += "application/msword"
                Kind6.ODT -> out += "application/vnd.oasis.opendocument.text"
                Kind6.RTF -> { out += "application/rtf"; out += "text/rtf" }
                Kind6.XLSX -> out += "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                Kind6.XLS -> out += "application/vnd.ms-excel"
                Kind6.ODS -> out += "application/vnd.oasis.opendocument.spreadsheet"
                Kind6.CSV -> { out += "text/csv"; out += "text/comma-separated-values"; out += "text/tab-separated-values" }
                Kind6.PPTX -> out += "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                Kind6.PPT -> out += "application/vnd.ms-powerpoint"
                Kind6.ODP -> out += "application/vnd.oasis.opendocument.presentation"
                Kind6.JPG, Kind6.PNG, Kind6.WEBP, Kind6.GIF, Kind6.BMP, Kind6.HEIC, Kind6.TIFF -> out += "image/*"
                Kind6.TEXT -> out += "text/plain"
                Kind6.MARKDOWN -> { out += "text/markdown"; out += "text/x-markdown"; out += "text/plain" }
                Kind6.HTML -> { out += "text/html"; out += "application/xhtml+xml" }
                Kind6.EPUB -> out += "application/epub+zip"
                Kind6.EML -> out += "message/rfc822"
                Kind6.ZIP -> out += "application/zip"
                Kind6.UNKNOWN -> {}
            }
        }
        // many file managers report odd types (octet-stream) for md/csv/odf/epub: keep them pickable
        out += "application/octet-stream"
        return out.toTypedArray()
    }
}
