# Convert audit — fix status (branch fix/convert-audit)

This file gives the status of every row of A_to_pdf.md (44), B_from_pdf.md (33) and C_other_hub.md (47), 124 ids in all.
Paths are relative to `app/src/main/java/com/smartdocs/editor/`. The WebView writer is `app/src/main/java/android/print/WebPdfWriter.kt`.

Verification:
- **Pure logic:** JVM unit tests in `app/src/test/java/com/smartdocs/editor/core/convert6/ConvertAuditFixTest.kt`. These are run by GitHub CI, not locally.
- **Device:** nothing was tested on a device. The WebView, PDFium, PdfBox-Android, the foreground service and MediaStore paths have been type-checked only.

## Shared pieces (cited below)

| piece | file |
|---|---|
| **J** job model: one job at a time, a cancelled run is awaited, localised errors | ui/tools5/T5Common.kt (T5Job), ui/convert6/Cv6ErrorText.kt, core/convert6/Cv6Errors.kt |
| **S** hub session: app-wide scope, state survives leaving the screen or a language switch, foreground service and notifications | ui/convert6/Cv6HubSession.kt, AndroidManifest.xml (Cv6JobService) |
| **B** batches: each input is tried and failures are listed. Passwords are kept per file, and only the locked files run again after a prompt | core/convert6/Cv6Batch.kt, core/convert6/Cv6Engine.kt (batch, pwOf, pdfOf), ui/convert6/ConvertHubScreen.kt (start, showResults) |
| **O** outputs: names are reserved and recorded in a journal. Failed or cancelled runs delete theirs, and the next app start purges what a killed run left. Multi-file outputs are written to a work folder and moved into Documents when complete | core/convert6/Cv6Engine.kt (out, workDir, publish, track), core/convert6/Cv6Batch.kt (OutputJournal), core/convert6/Cv6Platform.kt (cleanUp), SmartDocsApp.kt |
| **C** cancellation in blocking loops: the job is polled | Cv6Engine.cancelCheck → ConvertEngine/ConvertPipeline, TiffDecoder, flatten, PdfA, imagesToPdf, writers |
| **W** WebView: renderer death is handled, local content gets no soft timeout, printing has a progress watchdog, JS is off for local content, navigation is blocked, the network is blocked for e-mails and books | android/print/WebPdfWriter.kt, core/tools5/SourceReaders.kt (HtmlBits.sanitize) |
| **E** one to-PDF engine with content detection | core/convert6/ToPdf6.kt, core/tools5/ToPdf.kt (convert → ToPdf6, kindOf(File), merge, combine) |
| **N** names: cleaned, length-capped (150 UTF-8 bytes), unique, ASCII digits | core/files/FileNames.kt, DocumentRepository.uniqueFile |
| **R** ranges: Arabic-Indic / Persian digits, «،» «؛» and dashes are accepted, invalid text is flagged | core/pdf/PdfOps.kt (normalizeRange, rangeSyntaxOk, parseRange), ui/common/Components.kt (PageRangeField) |
| **P** restricted PDFs | core/convert6/Cv6Security.kt, Cv6Engine.checkSecurity / keepProtection, ToPdf.mergeablePdf |

## A — everything → PDF

| id | status | fix / file |
|---|---|---|
| A-C1 | FIXED | W: `onRenderProcessGone` returns true, destroys the WebView and fails the job with RendererGone, shown as "document too large". Big books, sheets and text are also printed in parts (ToPdf.htmlParts) |
| A-H1 | FIXED | core/convert6/TiffDecoder.kt rewrite: reads through RandomAccessFile, decodes one page at a time, streams rows and subsamples while decoding. Used by ImageIo6.forEachPage / tiffToPdf |
| A-H2 | FIXED | W: the print watchdog allows up to 6 min of layout, then fails only after 3 min with no growth of the output file. TimeoutException is localised (J) |
| A-H3 | FIXED | W: local HTML waits for onPageFinished (10 min limit only for a page that never finishes). The 25 s soft timeout applies to URLs only |
| A-H4 | FIXED | B + E: the password map reaches ToPdf6.combine → ToPdf.mergeablePdf (an unlocked temp copy), handouts and nUp. Cv6NeedPassword names the file |
| A-H5 | FIXED | B for the hub. ToPdfPlusScreen.convertEachToPdf for Tools → To PDF and Share "convert each": failures listed, partial output deleted |
| A-H6 | FIXED | ToPdf.merge / combine: PDFMergerUtility with setupMixed(48 MB) and temp dir; counts via PdfOps.loadRaw (mixed). PdfOps.merge also mixed |
| A-H7 | FIXED | ImageIo6.imagesToPdf, PdfOps.imagesToPdf, ScanPdfBuilder: `PDDocument(MemoryUsageSetting.setupTempFileOnly())`, bitmaps recycled per page |
| A-H8 | FIXED | J (Cv6ErrorText for every rememberT5Job screen and for the hub), CombinePlusUi.work, T5PrintService |
| A-H9 | FIXED | core/office/OfficeLayoutHtml.kt `printSheet`: no letters or row numbers, used range only, gridlines, repeated header row, zoom to page width. ToPdf6.xlsx picks landscape for wide sheets and prints in parts |
| A-H10 | FIXED | core/office/DocxReader.kt: OMML → linear text (m:f, sSup/sSub, rad, nary, d, func, acc, bar, lim, eqArr, m), footnotes and endnotes with markers, default header and footer, chart / SmartArt placeholders, AlternateContent Fallback. OfficeHtml.docx(forPrint) repeats the header and footer via thead/tfoot and appends the notes. The info line counts equations and objects that were not rendered |
| A-H11 | FIXED | FileKind6.legacyVariant + LegacyVariants (HTML tables, SpreadsheetML 2003, Word 2003 XML, TSV/CSV). ToPdf6.readSheetsInfo / readWordLike dispatch by content |
| A-M1 | FIXED | MAX_PRINT_ROWS raised to 20 000, printed in parts of 2 500 rows. The note "first N of M rows" is added on the page and to the result. Readers report cut sheets |
| A-M2 | FIXED | ImageIo6.imagesToPdf: a JPEG is embedded byte for byte only at quality ≥ 90 or when it is under 400 KB; otherwise it is decoded and re-encoded |
| A-M3 | FIXED | PPTX: the paper is the slide size, margins are 0 and slides are zoomed to the page width (OfficeSlideHtml.render). ODP: OdfReader.readOdpSlides + ToPdf6.odpFlow keep the pictures, printed on A4 landscape |
| A-M4 | FIXED | DocxReader.readSection (pgSz / orient / pgMar) → ToPdf6.docxSetup (custom MediaSize, landscape, per-side margins; WebPdfWriter.Setup.marginsMils) |
| A-M5 | FIXED | OfficeHtml.printable converts TIFF / BMP to PNG (PortableImage). EMF / WMF / charts get a localised placeholder that names the type (Cv6Platform.refreshLabels) |
| A-M6 | FIXED | W: JS off for local HTML / EML / EPUB, allowContentAccess=false, every navigation blocked, blockNetwork for EML / EPUB. HtmlBits.sanitize removes script, iframe, object, base, meta refresh, on* handlers and javascript: URLs |
| A-M7 | FIXED | EpubReader.readBook: chapter CSS (link + style) kept, title decoded once, sanitised, 4 MB per picture and 64 MB in total (overflow counted), TIFF converted. Printed in parts (ToPdf6.chapterParts) |
| A-M8 | FIXED | E: ToPdf.convert delegates to ToPdf6.convert (magic bytes). ToPdfPlus, Share and Combine use it, and the unsupported check uses `kindOf(File)`. MARKDOWN goes through MdFlow on every path (MarkdownHtml is no longer used for PDF) |
| A-M9 | FIXED | MdFlow.looksLikeMarkdown needs strong signals. Clipboard Markdown is parsed with hardBreaks=true (Cv6Engine clipboard_pdf) |
| A-M10 | FIXED | TextHtml.body gives each line `dir=auto`. ToPdf.textChunks streams the file (charset judged on the first MB), printed in parts |
| A-M11 | FIXED | FileKind6.isEncryptedOffice (EncryptedPackage + EncryptionInfo). The hub picker names the files; the engine throws EncryptedOffice → cv6_err_encrypted |
| A-M12 | FIXED | CombinePlus: CombineEntry.password, ToPdf.mergeablePdf, InvalidRange for bad or empty ranges. CombinePlusUi: password dialog per file, range field error, combine disabled |
| A-M13 | FIXED | ShareToPdfScreen: payload and files in rememberSaveable (savers). ShareImport.read uses one folder per share with real file names (no millis prefix) |
| A-M14 | FIXED | No `.cv6_` copy in Documents any more (ToPdf6 never copies). Scanner temps are deleted after saving. tmp/ and tmp/shared older than one day are purged at start-up (Cv6Platform.cleanUp) |
| A-M15 | FIXED | PdfOps.imagesToPdf and ScanPdfBuilder report skipped pictures and throw UnsupportedImage("empty") when none could be read |
| A-M16 | FIXED | TiffDecoder: compression 7 (strips / tiles + JPEGTables, Adobe marker for RGB) and old-style 6 (interchange stream) → JPEG pieces embedded raw. Dropped pages are listed in the info line |
| A-L1 | FIXED | ui/tools/ConvertTool.kt and ConvertFromPdf.kt deleted; route `convert_classic` opens the hub (ToolScreens.kt) |
| A-L2 | FIXED | T5PrintService: isCancelled is checked before and after the copy, FileNames.unique, localised labels and errors, and a toast with the location when notifications are off |
| A-L3 | FIXED | N |
| A-L4 | FIXED | `.mht/.mhtml` → ToPdf.readHtml extracts the HTML part with MhtReader and inlines its pictures (html_pdf, html_docx, legacy variants) |
| A-L5 | FIXED | CfbReader.streamNamesOf reads only the header, FAT and directory sectors. FileKind6.of caches results per (path, size, mtime) |
| A-L6 | FIXED | Cv6ErrorText.imageReason |
| A-L7 | FIXED | PdfOps.decodeScaled: rotate, then mirror for isFlipped. The raw-JPEG check includes flips |
| A-L8 | FIXED | OdfReader.parseXml fails closed: a part with DOCTYPE / ENTITY is refused before parsing, and the external-entity features are set where supported |
| A-L9 | FIXED | Cv6ResultsSheet ZIP all: runCatching, duplicate names numbered, error snackbar |
| A-L10 | FIXED | ScanPdfBuilder.build: ensureActive per page, temp-file memory. ML Kit's own PDF is no longer requested (the app builds the PDF with its options). Temps are deleted after saving |
| A-L11 | FIXED | Images6.pageDpi (declared JFIF / EXIF dpi, else the long side about A4) |
| A-L12 | FIXED | ToPdf.combine / ToPdf6.combine decide by FileKind6.of |
| A-L13 | FIXED | img_ocr_pdf temps are tracked by the engine (O) and the batch checks for cancel |
| A-L14 | FIXED | ToPdf.mergeablePdf reports signed inputs → cvf_info_signed (hub combine, CombinePlus) |
| A-L15 | FIXED | CombinePlusUi: web page through T5Job (progress + Cancel), localised errors |
| A-L16 | FIXED | S (the job is no longer in the composition scope, and the state is kept in Cv6HubSession) |

## B — PDF → other formats

| id | status | fix / file |
|---|---|---|
| B01 | FIXED | C: `ConvertEngine.convert(..., cancelCheck())` instead of `{ false }`; J (no second job while one is unwinding); O (outputs of a cancelled run deleted) |
| B02 | FIXED | B |
| B03 | FIXED | B (pwOf: own password, else the typed one, for every input) |
| B04 | FIXED | PdfXml.write takes (source index, w, h); Export7Run.xml extracts text page by page |
| B05 | FIXED | XpsWriter.viewbox = picture pixel size |
| B06 | FIXED | ImageIo6.longImage: width kept, split into parts (Images6.longParts: WebP 16383, JPEG 65500, memory budget), compress() result checked (ImageWriteFailed), info line |
| B07 | FIXED | TiffWriter.Stream (RandomAccessFile, page by page, IFD offsets patched) |
| B08 | FIXED | PsWriter.Stream (ascii85 into a Writer, page by page) |
| B09 | FIXED | XpsWriter.Stream, OdfWriter.OdpStream (pictures written as they arrive) |
| B10 | FIXED | PdfFlow6.eachPage / flow(spillDir): pictures in temp files (FBlock.Image(file)). EPUB writes pictures per chapter, ODT / DOCX / Markdown read them lazily, HTML set stores them as zip entries |
| B11 | FIXED | ImageIo6.pageBitmaps: DPI honoured (only the memory budget lowers it), raw pixels for TIFF / BMP (no JPEG round trip), effective dpi written to the tags |
| B12 | FIXED | padStart names (HtmlSet.pageName, Export7Run bmp / eps) |
| B13 | FIXED | Csv6.cellNumber in Xml2003 |
| B14 | FIXED | O (work folder + publish with unique names) |
| B15 | FIXED | O |
| B16 | FIXED | J. Extract Images: InvalidPasswordException → password field error + prompt |
| B17 | FIXED | R. Extract Images rejects a range that selects no page |
| B18 | FIXED | ImageExtract6.encode: CCITT passed through as TIFF (TiffWriter.ccitt), big pictures decoded subsampled, failures counted (onSkipped → cvf_ex_skipped) |
| B19 | FIXED | PdfFlow6.flow(detectTables = o.tables) for pdf_md |
| B20 | FIXED | InvalidRange per file (batch note), syntax checked first |
| B21 | FIXED | LayoutFlow.sheetsPerPage, Export7Run.xml2003 (no text-dump sheets unless the document has no table) |
| B22 | FIXED | Csv6.defuse in Csv6.write |
| B23 | FIXED | flow / pages(title = source name) |
| B24 | FIXED | PdfFlow6.ocrCopy and AutoOcr.ocrIfScanned delete the temp copy on failure, cancel or null |
| B25 | FIXED | ImageIo6.selectedCount gives real totals; XML page by page with ensureActive |
| B26 | FIXED | OdfWriter.letterbox per slide; PS %%PageBoundingBox per page and the largest box in the trailer |
| B27 | FIXED | Cv6Results ZIP all, Extract Images share / gallery in runCatching; Cv6Save.zip / named dedupe names |
| B28 | FIXED | Cv6Save.toGallery deletes the pending row on failure; storage permission asked on Android 9 and older; share copies in a new sub-folder each time |
| B29 | FIXED | = A-L1 |
| B30 | FIXED | HtmlSet.write: FlowHtml.CSS, lang/dir, pictures as files |
| B31 | FIXED | Cv6Catalog extract_images inputs + ODS |
| B32 | FIXED | N |
| B33 | FIXED | Cv6Batch.infoLines (file name prefix); showResults touches Recent for every output |

## C — other conversions, PDF → PDF, hub

| id | status | fix / file |
|---|---|---|
| C1 | FIXED | B |
| C2 | FIXED | O |
| C3 | FIXED | B |
| C4 | FIXED | Cv6Engine2.handouts: pwOf + withPdfPassword + security check |
| C5 | FIXED | ToPdf6.readSheetsInfo (200 000 × 1024 for conversions, XLS full BIFF8 grid), cut sheets reported (xlsx_ods, xlsx_csv) |
| C6 | FIXED | csv_xlsx runs in withContext(IO), text decoded once |
| C7 | FIXED | = A-H1; reusable sample buffer, cancel check per strip |
| C8 | FIXED | OcrRunner.isImage by content (TIFF excluded); hub converts TIFF / pictures to PDF first (pdf_searchable, img_ocr_pdf) |
| C9 | FIXED | PdfOps.flatten: temp-file PDF, cancel per page, progress |
| C10 | FIXED | PdfFlow6.load, PdfA.convert, Optimizer.optimize: MemoryUsageSetting.setupMixed |
| C11 | FIXED | S + O. Process death itself cannot be prevented: the foreground service makes it unlikely and the journal cleans up |
| C12 | FIXED | J |
| C13 | FIXED | J + C |
| C14 | FIXED | Hub onPicked / currentKind on IO; CFB header-only (A-L5); preClean on Dispatchers.Default |
| C15 | FIXED | Cv6Catalog.fmtWords (Arabic / English format names) used in search |
| C16 | FIXED | P: gray / compress / flatten / searchable need "modify" and keep the source protection (keepProtection); PDF/A needs owner access; exports need "copy" |
| C17 | FIXED | Csv6.cellNumber in OdfWriter.ods (leading zeros and more than 15 digits stay text) |
| C18 | FIXED | CsvIO.toXlsxFromText / keepAsText: number formats kept, formulas / leading zeros / long IDs / "3-4" stay text |
| C19 | FIXED | HtmlFlow.decode → U+FFFD for invalid code points |
| C20 | FIXED | = A-H11 |
| C21 | FIXED | epub_txt reads text only (no pictures); EPUB picture budget |
| C22 | FIXED | handouts: direction from the first 5 pages, nUp progress |
| C23 | FIXED | ImageIo6.forEachPage: JPEG-in-TIFF via BitmapFactory, CCITT pages via PdfBox CCITTFactory |
| C24 | FIXED | ImageIo6.convertImage: percentage of the real size (TIFF via TiffDecoder.pageSize) |
| C25 | FIXED | convertImage decodes subsampled to the target, one page at a time; "made smaller" note above 8000 px |
| C26 | FIXED | img_ocr_pdf temps tracked (O); renameTo instead of copy |
| C27 | FIXED | PdfTransforms6.grayscale: annotation appearances and /C, /IC rewritten. Pages with shadings / patterns / inline images / special colour spaces, or whose rewrite failed, are rasterised in grey (info count) |
| C28 | FIXED | Optimizer: grey / 1-bit pictures not re-encoded in the grayscale pass; colour pictures always made grey; progress forwarded |
| C29 | FIXED | PdfA.convert(onProgress) + ensureActive per page |
| C30 | FIXED | = A-L9 |
| C31 | FIXED | Cv6Engine2.run / flowConvert: explicit ids, `else` throws |
| C32 | FIXED | = A-L1 |
| C33 | FIXED | = A-M11 |
| C34 | FIXED | MdFlow.link (balanced parentheses) + Text6.safeLink in MdFlow, FlowHtml, FlowMarkdown, FlowDocx, OdfWriter, HtmlFlow |
| C35 | FIXED | MdFlow: picture lines (data: URIs, relative files next to the .md via ToPdf6.siblingResolver) → FBlock.Image |
| C36 | FIXED | ImageCodecs6 (PngEncoder, BmpDecoder, PortableImage + Android decoder) in EpubWriter, OdfWriter.odt, FlowDocx; dropped count in info |
| C37 | FIXED | cvf_info_doc_text for Word 97 inputs (flowConvert, to PDF) |
| C38 | FIXED | FlowDocx.directionSegments: `<w:rtl/>` only on the Arabic parts |
| C39 | FIXED | pptx_odp via Export7Run.odp (streamed, letterboxed) |
| C40 | FIXED | docx_images / pptx_images: unique work folder, progress, publish / zip |
| C41 | FIXED | cvf_info_first_sheet |
| C42 | FIXED | = A-L4 |
| C43 | FIXED | OdfReader.readOdt @Synchronized |
| C44 | FIXED | N |
| C45 | FIXED | Cv6Security.hasSignature → cvf_info_signed for gray / compress / flatten / PDF/A / searchable |
| C46 | FIXED | PdfTransforms6.nUp: empty PDF → clear error; orientation from most pages; temp-file memory |
| C47 | FIXED | Cv6Catalog.defaultsFor shared by the hub sheet and RecipeRunner |

## Remaining limits (not defects of these rows)

- **EMF / WMF pictures:** Android has no decoder for them, so they show a labelled placeholder (A-M5).
- **Equations:** they are linear text, not typeset MathML (A-H10).
- **Process death:** if the system kills the process anyway, the conversion stops. The partial outputs are removed at the next start.
