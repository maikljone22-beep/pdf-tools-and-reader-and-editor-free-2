package com.smartdocs.editor.core.convert6

/** Hub sections, in display order. */
enum class Cv6Group { FROM_PDF, TO_PDF, IMAGES, OFFICE, TEXT_BOOKS, OCR, OTHER }

/** Formats shown on the cards (icon + short label). */
enum class Fmt { PDF, WORD, EXCEL, PPT, IMAGE, JPG, PNG, WEBP, HEIC, TIFF, ZIP, LONG_IMAGE, TXT, HTML, RTF, MD, EPUB, CSV, ODT, ODS, ODP, SVG,
    PDFA, GRAY, SMALL, SEARCHABLE, FLAT, WEB, EMAIL, CAMERA, CLIPBOARD, ANY, OCR, HANDOUT, RESIZE, IMAGES_OUT,
    BMP, PS, EPS, XPS, XML, ACCESS_TXT, HTML_SET, XML_2003 }

/** Options a conversion shows in its sheet. */
enum class Opt { RANGE, AUTO_OCR, TABLES, INCLUDE_IMAGES, SINGLE_SHEET, PPT_MODE, LAYOUT, IMG_FORMAT, DPI, QUALITY, IMG_OUTPUT, GRAY,
    PAGE_SETUP, MERGE, NUP, RESIZE, CSV_SHEETS, OCR_LANG, URL, TEXT, PDFA_STRICT, BW, SHEET_PER_TABLE }

/**
 * One hub conversion. [inputs] = accepted kinds (empty = no file: URL / clipboard / camera); [multi] = several inputs at once
 * (batch or merge). [route] = handled by another tool screen (tool id) instead of the hub runner. [groups] lists every
 * section the card appears in (first = home section).
 */
data class Conv(
    val id: String,
    val groups: List<Cv6Group>,
    val from: Fmt,
    val to: Fmt,
    val inputs: Set<Kind6>,
    val multi: Boolean = true,
    val opts: Set<Opt> = emptySet(),
    val outExt: String = "pdf",
    val route: String? = null,
    /** Search keywords besides the localised labels. */
    val keywords: String = "",
)

object Cv6Catalog {
    private val PDF = setOf(Kind6.PDF)
    private val IMG = Kind6.IMAGES
    private val WORDS = setOf(Kind6.DOCX, Kind6.DOC, Kind6.ODT, Kind6.RTF)
    private val SHEETS = setOf(Kind6.XLSX, Kind6.XLS, Kind6.CSV, Kind6.ODS)
    private val SLIDES = setOf(Kind6.PPTX, Kind6.ODP)

    val all: List<Conv> = listOf(
        // ---- from PDF ----
        Conv("pdf_docx", listOf(Cv6Group.FROM_PDF, Cv6Group.OFFICE), Fmt.PDF, Fmt.WORD, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR, Opt.LAYOUT, Opt.TABLES, Opt.INCLUDE_IMAGES), outExt = "docx", keywords = "word docx"),
        Conv("pdf_xlsx", listOf(Cv6Group.FROM_PDF, Cv6Group.OFFICE), Fmt.PDF, Fmt.EXCEL, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR, Opt.SINGLE_SHEET, Opt.TABLES), outExt = "xlsx", keywords = "excel xlsx"),
        Conv("pdf_pptx", listOf(Cv6Group.FROM_PDF, Cv6Group.OFFICE), Fmt.PDF, Fmt.PPT, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR, Opt.PPT_MODE, Opt.TABLES, Opt.INCLUDE_IMAGES), outExt = "pptx", keywords = "powerpoint pptx"),
        Conv("pdf_images", listOf(Cv6Group.FROM_PDF, Cv6Group.IMAGES), Fmt.PDF, Fmt.IMAGES_OUT, PDF, opts = setOf(Opt.RANGE, Opt.IMG_FORMAT, Opt.DPI, Opt.QUALITY, Opt.IMG_OUTPUT, Opt.GRAY), outExt = "jpg", keywords = "jpg png webp zip"),
        Conv("pdf_long_image", listOf(Cv6Group.FROM_PDF, Cv6Group.IMAGES), Fmt.PDF, Fmt.LONG_IMAGE, PDF, opts = setOf(Opt.RANGE, Opt.IMG_FORMAT, Opt.DPI, Opt.QUALITY), outExt = "jpg", keywords = "long stitch"),
        Conv("extract_images", listOf(Cv6Group.FROM_PDF, Cv6Group.IMAGES), Fmt.PDF, Fmt.IMAGE, PDF + setOf(Kind6.DOCX, Kind6.PPTX, Kind6.XLSX, Kind6.EPUB, Kind6.ODT, Kind6.ODP), multi = false, route = "extract_images", keywords = "extract images"),
        Conv("pdf_txt", listOf(Cv6Group.FROM_PDF, Cv6Group.TEXT_BOOKS), Fmt.PDF, Fmt.TXT, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR, Opt.TABLES), outExt = "txt", keywords = "text txt"),
        Conv("pdf_html", listOf(Cv6Group.FROM_PDF), Fmt.PDF, Fmt.HTML, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR, Opt.TABLES, Opt.INCLUDE_IMAGES), outExt = "html", keywords = "html web"),
        Conv("pdf_rtf", listOf(Cv6Group.FROM_PDF), Fmt.PDF, Fmt.RTF, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR), outExt = "rtf", keywords = "rtf"),
        Conv("pdf_md", listOf(Cv6Group.FROM_PDF, Cv6Group.TEXT_BOOKS), Fmt.PDF, Fmt.MD, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR, Opt.TABLES, Opt.INCLUDE_IMAGES), outExt = "md", keywords = "markdown md"),
        Conv("pdf_epub", listOf(Cv6Group.FROM_PDF, Cv6Group.TEXT_BOOKS), Fmt.PDF, Fmt.EPUB, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR, Opt.INCLUDE_IMAGES), outExt = "epub", keywords = "epub ebook book"),
        Conv("pdf_csv", listOf(Cv6Group.FROM_PDF, Cv6Group.OFFICE), Fmt.PDF, Fmt.CSV, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR), outExt = "csv", keywords = "csv tables"),
        Conv("pdf_odt", listOf(Cv6Group.FROM_PDF, Cv6Group.OFFICE), Fmt.PDF, Fmt.ODT, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR, Opt.INCLUDE_IMAGES), outExt = "odt", keywords = "odt libreoffice opendocument"),
        Conv("pdf_ods", listOf(Cv6Group.FROM_PDF, Cv6Group.OFFICE), Fmt.PDF, Fmt.ODS, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR), outExt = "ods", keywords = "ods libreoffice calc"),
        Conv("pdf_odp", listOf(Cv6Group.FROM_PDF, Cv6Group.OFFICE), Fmt.PDF, Fmt.ODP, PDF, opts = setOf(Opt.RANGE, Opt.DPI), outExt = "odp", keywords = "odp impress"),
        Conv("pdf_svg", listOf(Cv6Group.FROM_PDF, Cv6Group.IMAGES), Fmt.PDF, Fmt.SVG, PDF, opts = setOf(Opt.RANGE, Opt.DPI, Opt.IMG_OUTPUT), outExt = "svg", keywords = "svg vector"),
        // Nitro 6 / Foxit 6 export formats (core/convert6/Export7.kt)
        Conv("pdf_tiff", listOf(Cv6Group.FROM_PDF, Cv6Group.IMAGES), Fmt.PDF, Fmt.TIFF, PDF, opts = setOf(Opt.RANGE, Opt.DPI, Opt.GRAY, Opt.BW), outExt = "tif", keywords = "tiff tif multipage fax"),
        Conv("pdf_bmp", listOf(Cv6Group.FROM_PDF, Cv6Group.IMAGES), Fmt.PDF, Fmt.BMP, PDF, opts = setOf(Opt.RANGE, Opt.DPI, Opt.IMG_OUTPUT), outExt = "bmp", keywords = "bmp bitmap"),
        Conv("pdf_ps", listOf(Cv6Group.FROM_PDF, Cv6Group.OTHER), Fmt.PDF, Fmt.PS, PDF, opts = setOf(Opt.RANGE, Opt.DPI), outExt = "ps", keywords = "postscript ps print"),
        Conv("pdf_eps", listOf(Cv6Group.FROM_PDF, Cv6Group.IMAGES), Fmt.PDF, Fmt.EPS, PDF, opts = setOf(Opt.RANGE, Opt.DPI, Opt.IMG_OUTPUT), outExt = "eps", keywords = "eps encapsulated postscript"),
        Conv("pdf_xps", listOf(Cv6Group.FROM_PDF, Cv6Group.OTHER), Fmt.PDF, Fmt.XPS, PDF, opts = setOf(Opt.RANGE, Opt.DPI), outExt = "xps", keywords = "xps oxps"),
        Conv("pdf_xml", listOf(Cv6Group.FROM_PDF, Cv6Group.TEXT_BOOKS), Fmt.PDF, Fmt.XML, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR), outExt = "xml", keywords = "xml data"),
        Conv("pdf_access_txt", listOf(Cv6Group.FROM_PDF, Cv6Group.TEXT_BOOKS), Fmt.PDF, Fmt.ACCESS_TXT, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR), outExt = "txt", keywords = "accessible text braille alt"),
        Conv("pdf_html_set", listOf(Cv6Group.FROM_PDF), Fmt.PDF, Fmt.HTML_SET, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR, Opt.INCLUDE_IMAGES), outExt = "zip", keywords = "html multiple pages navigation"),
        Conv("pdf_xml2003", listOf(Cv6Group.FROM_PDF, Cv6Group.OFFICE), Fmt.PDF, Fmt.XML_2003, PDF, opts = setOf(Opt.RANGE, Opt.AUTO_OCR, Opt.SHEET_PER_TABLE), outExt = "xml", keywords = "excel 2003 xml spreadsheet"),
        Conv("pdf_pdfa", listOf(Cv6Group.FROM_PDF, Cv6Group.OTHER), Fmt.PDF, Fmt.PDFA, PDF, opts = setOf(Opt.PDFA_STRICT), keywords = "pdf/a archive"),
        Conv("pdf_gray", listOf(Cv6Group.FROM_PDF, Cv6Group.OTHER), Fmt.PDF, Fmt.GRAY, PDF, keywords = "grayscale black white"),
        Conv("pdf_compress", listOf(Cv6Group.FROM_PDF, Cv6Group.OTHER), Fmt.PDF, Fmt.SMALL, PDF, opts = setOf(Opt.QUALITY, Opt.DPI, Opt.GRAY), keywords = "compress reduce size"),
        Conv("pdf_searchable", listOf(Cv6Group.OCR, Cv6Group.FROM_PDF), Fmt.PDF, Fmt.SEARCHABLE, PDF + IMG, opts = setOf(Opt.OCR_LANG), keywords = "ocr searchable"),
        Conv("pdf_flatten", listOf(Cv6Group.FROM_PDF, Cv6Group.OTHER), Fmt.PDF, Fmt.FLAT, PDF, opts = setOf(Opt.DPI), keywords = "flatten image only"),
        // ---- to PDF ----
        Conv("word_pdf", listOf(Cv6Group.TO_PDF, Cv6Group.OFFICE), Fmt.WORD, Fmt.PDF, WORDS, opts = setOf(Opt.MERGE), keywords = "docx doc odt rtf"),
        Conv("excel_pdf", listOf(Cv6Group.TO_PDF, Cv6Group.OFFICE), Fmt.EXCEL, Fmt.PDF, SHEETS, opts = setOf(Opt.MERGE), keywords = "xlsx xls csv ods"),
        Conv("ppt_pdf", listOf(Cv6Group.TO_PDF, Cv6Group.OFFICE), Fmt.PPT, Fmt.PDF, SLIDES, opts = setOf(Opt.MERGE), keywords = "pptx odp slides"),
        Conv("images_pdf", listOf(Cv6Group.TO_PDF, Cv6Group.IMAGES), Fmt.IMAGE, Fmt.PDF, IMG, opts = setOf(Opt.PAGE_SETUP, Opt.MERGE, Opt.QUALITY), keywords = "jpg png heic tiff gif bmp webp"),
        Conv("text_pdf", listOf(Cv6Group.TO_PDF, Cv6Group.TEXT_BOOKS), Fmt.TXT, Fmt.PDF, setOf(Kind6.TEXT, Kind6.CSV), opts = setOf(Opt.MERGE), keywords = "txt"),
        Conv("md_pdf", listOf(Cv6Group.TO_PDF, Cv6Group.TEXT_BOOKS), Fmt.MD, Fmt.PDF, setOf(Kind6.MARKDOWN, Kind6.TEXT), opts = setOf(Opt.MERGE), keywords = "markdown"),
        Conv("html_pdf", listOf(Cv6Group.TO_PDF), Fmt.HTML, Fmt.PDF, setOf(Kind6.HTML), opts = setOf(Opt.MERGE), keywords = "html"),
        Conv("url_pdf", listOf(Cv6Group.TO_PDF, Cv6Group.OTHER), Fmt.WEB, Fmt.PDF, emptySet(), multi = false, opts = setOf(Opt.URL), keywords = "web url link"),
        Conv("epub_pdf", listOf(Cv6Group.TO_PDF, Cv6Group.TEXT_BOOKS), Fmt.EPUB, Fmt.PDF, setOf(Kind6.EPUB), opts = setOf(Opt.MERGE), keywords = "ebook"),
        Conv("eml_pdf", listOf(Cv6Group.TO_PDF, Cv6Group.OTHER), Fmt.EMAIL, Fmt.PDF, setOf(Kind6.EML), opts = setOf(Opt.MERGE), keywords = "email eml mail"),
        Conv("scan_pdf", listOf(Cv6Group.TO_PDF, Cv6Group.OCR), Fmt.CAMERA, Fmt.PDF, emptySet(), multi = false, route = "scan", keywords = "camera scan"),
        Conv("clipboard_pdf", listOf(Cv6Group.TO_PDF, Cv6Group.OTHER), Fmt.CLIPBOARD, Fmt.PDF, emptySet(), multi = false, opts = setOf(Opt.TEXT), keywords = "clipboard paste"),
        Conv("any_pdf", listOf(Cv6Group.TO_PDF, Cv6Group.OTHER), Fmt.ANY, Fmt.PDF, Kind6.entries.toSet() - setOf(Kind6.UNKNOWN, Kind6.ZIP, Kind6.PPT), opts = setOf(Opt.MERGE), keywords = "combine merge mixed"),
        // ---- images ----
        Conv("img_convert", listOf(Cv6Group.IMAGES), Fmt.IMAGE, Fmt.IMAGE, IMG, opts = setOf(Opt.IMG_FORMAT, Opt.QUALITY), outExt = "jpg", keywords = "jpg png webp convert"),
        Conv("heic_jpg", listOf(Cv6Group.IMAGES), Fmt.HEIC, Fmt.JPG, setOf(Kind6.HEIC), opts = setOf(Opt.QUALITY), outExt = "jpg", keywords = "heic heif iphone"),
        Conv("img_resize", listOf(Cv6Group.IMAGES), Fmt.IMAGE, Fmt.RESIZE, IMG, opts = setOf(Opt.RESIZE, Opt.IMG_FORMAT, Opt.QUALITY), outExt = "jpg", keywords = "resize compress smaller"),
        Conv("tiff_pdf", listOf(Cv6Group.IMAGES, Cv6Group.TO_PDF), Fmt.TIFF, Fmt.PDF, setOf(Kind6.TIFF), opts = setOf(Opt.PAGE_SETUP, Opt.MERGE), keywords = "tiff tif fax multipage"),
        Conv("img_txt", listOf(Cv6Group.OCR, Cv6Group.IMAGES), Fmt.IMAGE, Fmt.TXT, IMG, opts = setOf(Opt.OCR_LANG, Opt.MERGE), outExt = "txt", keywords = "ocr text"),
        Conv("img_docx", listOf(Cv6Group.OCR, Cv6Group.IMAGES), Fmt.IMAGE, Fmt.WORD, IMG, opts = setOf(Opt.OCR_LANG, Opt.MERGE), outExt = "docx", keywords = "ocr word"),
        Conv("img_ocr_pdf", listOf(Cv6Group.OCR, Cv6Group.IMAGES), Fmt.IMAGE, Fmt.SEARCHABLE, IMG, opts = setOf(Opt.OCR_LANG, Opt.MERGE), keywords = "ocr searchable"),
        // ---- Office <-> Office / text ----
        Conv("docx_txt", listOf(Cv6Group.OFFICE, Cv6Group.TEXT_BOOKS), Fmt.WORD, Fmt.TXT, WORDS, outExt = "txt", keywords = "word text"),
        Conv("docx_html", listOf(Cv6Group.OFFICE), Fmt.WORD, Fmt.HTML, WORDS, outExt = "html", keywords = "word html"),
        Conv("docx_md", listOf(Cv6Group.OFFICE, Cv6Group.TEXT_BOOKS), Fmt.WORD, Fmt.MD, WORDS, outExt = "md", keywords = "word markdown"),
        Conv("docx_odt", listOf(Cv6Group.OFFICE), Fmt.WORD, Fmt.ODT, setOf(Kind6.DOCX, Kind6.DOC, Kind6.RTF), outExt = "odt", keywords = "odt"),
        Conv("odt_docx", listOf(Cv6Group.OFFICE), Fmt.ODT, Fmt.WORD, setOf(Kind6.ODT, Kind6.RTF, Kind6.DOC), outExt = "docx", keywords = "odt rtf doc to docx"),
        Conv("docx_epub", listOf(Cv6Group.OFFICE, Cv6Group.TEXT_BOOKS), Fmt.WORD, Fmt.EPUB, WORDS, outExt = "epub", keywords = "ebook"),
        Conv("docx_images", listOf(Cv6Group.OFFICE, Cv6Group.IMAGES), Fmt.WORD, Fmt.IMAGES_OUT, WORDS, opts = setOf(Opt.IMG_FORMAT, Opt.DPI, Opt.IMG_OUTPUT), outExt = "jpg", keywords = "word pages images"),
        Conv("xlsx_csv", listOf(Cv6Group.OFFICE), Fmt.EXCEL, Fmt.CSV, setOf(Kind6.XLSX, Kind6.XLS, Kind6.ODS), opts = setOf(Opt.CSV_SHEETS), outExt = "csv", keywords = "excel csv"),
        Conv("csv_xlsx", listOf(Cv6Group.OFFICE), Fmt.CSV, Fmt.EXCEL, setOf(Kind6.CSV), outExt = "xlsx", keywords = "csv excel"),
        Conv("xlsx_ods", listOf(Cv6Group.OFFICE), Fmt.EXCEL, Fmt.ODS, setOf(Kind6.XLSX, Kind6.XLS, Kind6.CSV), outExt = "ods", keywords = "ods calc"),
        Conv("pptx_images", listOf(Cv6Group.OFFICE, Cv6Group.IMAGES), Fmt.PPT, Fmt.IMAGES_OUT, SLIDES, opts = setOf(Opt.IMG_FORMAT, Opt.DPI, Opt.IMG_OUTPUT), outExt = "jpg", keywords = "slides images"),
        Conv("pptx_handouts", listOf(Cv6Group.OFFICE), Fmt.PPT, Fmt.HANDOUT, SLIDES + PDF, opts = setOf(Opt.NUP), keywords = "handouts n-up slides per page"),
        Conv("pptx_odp", listOf(Cv6Group.OFFICE), Fmt.PPT, Fmt.ODP, setOf(Kind6.PPTX), outExt = "odp", keywords = "odp impress"),
        // ---- text & books ----
        Conv("txt_docx", listOf(Cv6Group.TEXT_BOOKS, Cv6Group.OFFICE), Fmt.TXT, Fmt.WORD, setOf(Kind6.TEXT), outExt = "docx", keywords = "text word"),
        Conv("md_docx", listOf(Cv6Group.TEXT_BOOKS, Cv6Group.OFFICE), Fmt.MD, Fmt.WORD, setOf(Kind6.MARKDOWN), outExt = "docx", keywords = "markdown word"),
        Conv("md_html", listOf(Cv6Group.TEXT_BOOKS), Fmt.MD, Fmt.HTML, setOf(Kind6.MARKDOWN), outExt = "html", keywords = "markdown html"),
        Conv("md_epub", listOf(Cv6Group.TEXT_BOOKS), Fmt.MD, Fmt.EPUB, setOf(Kind6.MARKDOWN, Kind6.TEXT), outExt = "epub", keywords = "markdown ebook"),
        Conv("html_docx", listOf(Cv6Group.TEXT_BOOKS, Cv6Group.OFFICE), Fmt.HTML, Fmt.WORD, setOf(Kind6.HTML), outExt = "docx", keywords = "html word"),
        Conv("epub_txt", listOf(Cv6Group.TEXT_BOOKS), Fmt.EPUB, Fmt.TXT, setOf(Kind6.EPUB), outExt = "txt", keywords = "ebook text"),
        Conv("epub_docx", listOf(Cv6Group.TEXT_BOOKS), Fmt.EPUB, Fmt.WORD, setOf(Kind6.EPUB), outExt = "docx", keywords = "ebook word"),
        // ---- OCR ----
        Conv("ocr_pdf_docx", listOf(Cv6Group.OCR), Fmt.OCR, Fmt.WORD, PDF, opts = setOf(Opt.RANGE, Opt.OCR_LANG, Opt.LAYOUT), outExt = "docx", keywords = "scanned editable word"),
        Conv("ocr_pdf_txt", listOf(Cv6Group.OCR), Fmt.OCR, Fmt.TXT, PDF, opts = setOf(Opt.RANGE, Opt.OCR_LANG), outExt = "txt", keywords = "scanned text"),
        Conv("ocr_pdf_xlsx", listOf(Cv6Group.OCR), Fmt.OCR, Fmt.EXCEL, PDF, opts = setOf(Opt.RANGE, Opt.OCR_LANG), outExt = "xlsx", keywords = "scanned table excel"),
    )

    init {
        check(all.map { it.id }.toSet().size == all.size) { "duplicate conversion ids" }
    }

    fun byId(id: String): Conv? = all.firstOrNull { it.id == id }

    fun inGroup(g: Cv6Group): List<Conv> = all.filter { g in it.groups }

    /** Conversions that accept [kind] (for the reader's current file: the hub puts them first). */
    fun forKind(kind: Kind6): List<Conv> = all.filter { kind in it.inputs }

    /**
     * Search over ids, keywords and localised labels ([label] gives "source → target" text). All words of the
     * query must match somewhere (case-insensitive; Arabic hamza/taa-marbuta variants normalised).
     */
    fun search(query: String, label: (Conv) -> String): List<Conv> {
        val words = norm(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return all
        return all.filter { c ->
            val hay = norm(c.id.replace('_', ' ') + " " + c.keywords + " " + label(c) + " " + c.from.name + " " + c.to.name)
            words.all { hay.contains(it) }
        }
    }

    fun norm(s: String): String = s.lowercase()
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ة', 'ه').replace('ى', 'ي')
        .replace(Regex("""[\u064B-\u0652ـ]"""), "")
        .replace(Regex("""[^\p{L}\p{N}/.+]+"""), " ").trim()

    /** Most-recent-first list after using [id] (dedup, capped). */
    fun pushRecent(recent: List<String>, id: String, max: Int = 6): List<String> = (listOf(id) + recent.filter { it != id }).take(max)
}
