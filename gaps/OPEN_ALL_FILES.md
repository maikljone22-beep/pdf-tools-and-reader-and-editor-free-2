# Open every common file type inside SmartDocs

Branch `feature/open-all-files` (worktree `D:\pdf\openall-wt`, from main `c310818`).
Goal (user): "I want my app to open all types of documents and files like SVG, Word, Excel, images … and others".
Viewing in-app is the goal; another app is only offered (Open with / Share / Convert) when a format is truly
impossible to show here.

## 1. Audit: how a file reaches a screen today (main c310818)

| Entry | What happens | Evidence |
|---|---|---|
| VIEW intent | Only the MIME types listed are accepted: PDF, DOCX/XLSX/PPTX (+macro-enabled), ODT, RTF, CSV, DOC/XLS/PPT, FDF/XFDF. No `image/*`, `text/*`, EPUB, HTML, ZIP, audio/video, no extension patterns for `application/octet-stream`. | `AndroidManifest.xml:55-77`, `:79-86` |
| VIEW handling | `content://` is copied with `DocumentRepository.localFile` (extension kept when 2-5 chars, **no extension → `.pdf`**), then `Routes.reader(...)`. | `MainActivity.kt:102-121`, `DocumentRepository.kt:172-175` |
| SEND (single, not PDF) / SEND_MULTIPLE | Always goes to **Share → PDF** (`share_to_pdf`): the file is converted, never viewed. | `ShareAndPrint.kt:42-48`, `MainActivity.kt:84-99` |
| Reader route dispatch | `OfficeRoutes.isOffice(name)` by **extension only** → Office screen; everything else → `ReaderScreen` (PDFium). A non-PDF there fails in `PdfSession.open` → "damaged" error screen. | `ui/Navigation.kt:153-163`, `OfficeRoutes.kt:63-72`, `ReaderState.kt:199-247`, `OpenError.kt:23-27` |
| Office screen kinds | WORD/SHEET/SLIDES/CSV editors, TEXT editor (txt/text/log/md), IMPORT read-only (odt/rtf), LEGACY doc/xls/ppt → info screen "old format", NONE → "cannot open". | `OfficeScreen.kt:60-70`, `OfficeRoutes.kt:41-55` |
| Home / Files "Open" pickers | `application/pdf` + `OfficeRoutes.MIMES` only (no images, EPUB, HTML, archives, media…). | `HomeScreen.kt:70`, `FilesScreen.kt:104`, `OfficeRoutes.kt:28-37` |
| Files tab folder listing | Only `pdf jpg jpeg png txt docx xlsx pptx csv odt rtf doc xls ppt md` are listed; anything else is invisible. | `DocumentRepository.kt:332` |
| Files tab device scan (SAF tree) | PDFs + Office names only. | `FilesScreen.kt:65` |
| Device scan (MediaStore) | PDFs only. | `DocumentRepository.kt:310-330` |
| Thumbnails / icons | Real thumbnail only for PDFs (PDFium) and jpg/jpeg/png/webp; one generic "doc" icon for every other type. | `Thumbnails.kt:57-58`, `:146-151` |
| Search index | PDFs only. | `IndexWorker.kt:32` |
| Detection | `FileKind6` (convert hub) sniffs PDF/ZIP-OOXML-ODF-EPUB/OLE2/RTF/JPG/PNG/WEBP/GIF/TIFF/HEIF magic bytes, but the open path never uses it. | `core/convert6/FileKind6.kt:70-90` |
| Existing readers reusable | `EpubReader`, `EmlReader`, `MarkdownHtml`, `TextHtml` (`tools5/SourceReaders.kt`), `DocReader`/`XlsReader` (`convert6/LegacyOffice.kt`), `OdfReader`, `RtfReader`, `TiffDecoder`, `CfbReader`, `ToPdf6.convert` (any → PDF). | files named |

## 2. Every format: today → target

Legend for "today": **viewer** = opens in an in-app viewer, **editor** = in-app editor, **PDF** = converted to PDF first,
**info** = info screen only, **error** = reaches the PDF reader and fails, **no** = not accepted / not listed.

| Group | Format | Today | Evidence | Target |
|---|---|---|---|---|
| Documents | PDF | editor (reader) | Navigation.kt:162 | unchanged |
| | DOCX/DOCM/DOTX/DOTM | editor | OfficeRoutes.kt:41, OfficeScreen.kt:62 | unchanged + magic-byte detection |
| | DOC/DOT (97-2003) | info ("old format") | OfficeScreen.kt:68 | read-only viewer (DocReader text) + Edit as DOCX + Convert to PDF |
| | RTF | viewer (read-only, ImportState) | OfficeRoutes.kt:46, OfficeScreen.kt:67 | unchanged; also by magic `{\rtf` |
| | ODT | viewer (read-only) | same | unchanged |
| | OTT | no | OfficeRoutes.kt:46 (no "ott") | viewer like ODT |
| | TXT | editor (whole file in memory) | OfficeRoutes.kt:45, OfficeText.kt:36 | editor up to 1 MB, streamed viewer above; encoding detection (BOM, UTF-16 without BOM, UTF-8, Windows-1256 vs 1252) |
| | other text/* (log, ini, cfg…) | log = editor; others no (VIEW not registered) | Manifest:55-77 | streamed text viewer |
| | Markdown | editor (raw text) | OfficeRoutes.kt:45 | rendered view + Edit |
| | HTML/HTM/XHTML | no (SEND → PDF) | ShareAndPrint.kt:63 | safe WebView (JS off, no file/content access, network blocked unless the user allows) |
| | MHT/MHTML | no | — | MIME parts → HTML with `cid:` resources inlined, same safe WebView |
| | XML/JSON/YAML/INI/LOG/source | no (SEND → PDF) | ToPdf.kt:28 | syntax-highlighted viewer, pretty-print JSON/XML |
| | XPS/OXPS | no | — | FixedPage XAML parsed and drawn (Glyphs, Path, ImageBrush, embedded/obfuscated fonts) |
| | DjVu | no | — | info screen (no permissive decoder exists; DjVuLibre is GPL) |
| | EPUB | no for VIEW; SEND → PDF | Manifest:93, ShareAndPrint.kt:64 | reader: spine chapters, TOC, RTL, font size, night mode |
| | FB2 / FB2.ZIP | no | — | parsed to HTML, reader view |
| | MOBI/AZW/AZW3/PRC | no | — | PalmDOC-compressed books: own decoder → reader; HUFF/CDIC or DRM → info |
| | CHM | no | — | info screen (LZX decoder not available under a permissive licence on Android) |
| Spreadsheets | XLSX/XLSM/XLTX | editor | OfficeRoutes.kt:42 | unchanged |
| | XLS/XLT | info ("old format") | OfficeScreen.kt:68 | read-only grid (XlsReader) + Convert to PDF / to XLSX |
| | ODS/OTS | no / error | OfficeRoutes.kt (no ods) | read-only grid (OdfReader) |
| | CSV/TSV | editor (UTF-8 or Windows-1256 only) | CsvIO.kt:8-19 | + UTF-16 and BOM detection via the shared decoder, delimiter sniff kept |
| | Numbers | no | — | info screen; shows the embedded QuickLook preview image when the package has one |
| Presentations | PPTX/PPTM/PPSX/POTX | editor | OfficeRoutes.kt:43 | unchanged (+PPSM/POTM by name) |
| | PPT/PPS/POT | info ("old format") | OfficeScreen.kt:68 | read-only slide texts (own PowerPoint record reader) |
| | ODP/OTP | no / error | — | read-only slide texts (OdfReader) |
| | Keynote / Pages | no | — | info screen + embedded preview image |
| Images | JPG/PNG/WEBP | SEND → PDF; Files list jpg/png only | Manifest:91, DocumentRepository.kt:332 | image viewer: zoom/pan, rotate, EXIF info, folder slideshow, Convert to PDF, OCR, share, edit |
| | GIF (animated) / WEBP (animated) | SEND → PDF | — | animated through `ImageDecoder` (API 28+), first frame below |
| | BMP / ICO / WBMP | SEND → PDF | — | viewer (platform decoders) |
| | HEIC/HEIF | SEND → PDF | — | viewer (API 28+ decoder; message below) |
| | AVIF | no | — | viewer on API 31+, message below |
| | TIFF incl. multi-page | no | — | viewer with page strip (own TiffDecoder) |
| | SVG | no | — | AndroidSVG renderer (no scripts, no external files), XXE/DOCTYPE refused |
| | DNG / RAW | no | — | preview through `ImageDecoder` where the device decodes it, else info |
| Comics | CBZ | no | — | page viewer, RTL reading option |
| | CB7 | no | — | page viewer through commons-compress 7z |
| | CBR | no | — | info screen (RAR decoders are not permissive: junrar carries the UnRAR licence restriction) |
| Archives | ZIP | Files tab "Unzip" extracts everything | FilesScreen.kt:168, ZipUtil.kt:30 | browse inside, open any entry in-app without extracting all; zip-slip / zip-bomb / nesting guards |
| | 7Z | no | — | browse (commons-compress) |
| | TAR / TGZ / GZ | no | — | browse (commons-compress tar, java.util.zip gzip) |
| | RAR | no | — | info screen (licence) |
| Email | EML | SEND → PDF | ShareAndPrint.kt:64, ToPdf.kt:78 | headers, safe HTML/text body, attachments openable in-app |
| | MSG (Outlook) | no | — | own CFB property reader (no Apache POI in the project): headers, body, attachments |
| Contacts / calendar | VCF | no | — | contact cards |
| | ICS | no | — | event list |
| Audio / video | MP3/M4A/AAC/OGG/OPUS/WAV/FLAC/AMR, MP4/MKV/WEBM/3GP/MOV/TS | no | — | media3 ExoPlayer (already in the project) with own controls; streamed from the source |
| Fonts | TTF/OTF | no | — | preview with Latin + Arabic sample, sizes |
| | WOFF | no | — | own WOFF1 → sfnt decoder, same preview |
| | WOFF2 | no | — | info (needs Brotli) |
| CAD | DXF | no | — | own ASCII DXF parser (LINE, CIRCLE, ARC, LWPOLYLINE, POLYLINE, POINT, TEXT/MTEXT) with zoom/pan |
| | DWG | no | — | info screen (no permissive DWG reader) |
| Other | FDF/XFDF | merge offer | MainActivity.kt:57 | unchanged |
| | anything else | error (PDF reader) | ReaderState.kt:244 | "File info": name, size, type by magic bytes, hex + text preview, Open with, Share, Convert to PDF when possible |

## 3. Implementation notes (filled in as the stages land)

See section 4 for the before → after table and section 5 for dependencies.
