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

## 3. What was built

### Routing (one dispatch for every "open")
* `ui/openall/OpenAll.kt` — `OpenDispatch` is now the body of the reader route (`ui/Navigation.kt`): the type is found by
  **magic bytes** (`core/openall/FileSniffer.kt`), then PDF → reader, editable DOCX/XLSX/PPTX/CSV/ODT/RTF/small TXT →
  Office editors, **everything else → `UniversalViewer`**. Content URIs are imported first (media are streamed instead).
  `openall/{uri}?depth=` is the direct viewer route (archive entries, e-mail attachments, previews).
* `OfficeScreen`: old DOC/XLS/PPT and unknown kinds no longer end on an "old format" screen — they open read-only in
  the viewer.
* Imports without a usable extension are named after their content (`DocumentRepository.localFile`), no more `*.pdf`.
* `MainActivity`: one shared file (SEND) opens in the viewer (Share → PDF stays for several files, text and links);
  audio/video from other apps are played from their `content://` URI, never copied.
* Manifest: VIEW for `text/* image/* audio/* video/* font/*`, e-books, archives, comics, e-mail, contacts, calendar,
  XPS, CHM, DjVu, iWork, `application/octet-stream`; a second VIEW filter with ~87 extension `pathPattern`s for generic
  MIME types; SEND accepts `*/*`.
* Home / Files "open" pickers accept `*/*`; the Files tab lists every file (not only 15 extensions); the SAF tree scan
  lists every known type; tiles have per-type icons/colours, and pictures (incl. GIF/HEIC/TIFF/SVG/RAW) and videos get
  real thumbnails (`ui/openall/OpenAllThumbs.kt`); Recents get the same tiles.
* Search: the indexer also indexes the text of TXT/code/MD/CSV/HTML/MHT/DOCX/DOC/RTF/ODT/EPUB/FB2/MOBI/EML/MSG/
  XLSX/XLS/ODS/PPT/ODP/VCF/ICS files (`core/openall/OpenAllText.kt`, `core/search/IndexWorker.kt`).

### Viewers (`ui/openall/`)
| Viewer | Formats | Notes |
|---|---|---|
| `TextViewer` | TXT, LOG, INI, JSON, XML, YAML, source code, big CSV | chunked reading (3 M chars, "load more" up to 48 M), encoding detection + manual choice, syntax colours, JSON/XML pretty print, search, wrap, font size, line numbers, Edit (txt/log/md ≤ 1 MB) |
| `CsvViewer` | delimited files the CSV editor does not take | delimiter + encoding detection |
| `WebDocViewer` (safe WebView) | Markdown (rendered), HTML/XHTML, MHT/MHTML, EML, MSG, FB2, MOBI/AZW/AZW3/PRC, DOC/OTT/RTF/ODT (read-only), XLS/ODS, PPT/ODP, and DOCX/XLSX/PPTX the editors cannot load | JS off (per-page "Allow scripts" for HTML), no file/content access, every request answered from the document or refused; "Load remote content" banner; links go to the browser only when tapped; night mode, text size, contents for FB2/MOBI, attachments open in-app, "Edit as Word" for DOC/ODT/RTF |
| `EpubViewer` | EPUB 2/3 | chapters served from the ZIP, TOC (nav/NCX), RTL page direction, text size, night mode, position remembered |
| `ImageViewer` | JPEG, PNG, GIF/WebP (animated, Android 9+), BMP, ICO, HEIF (9+), AVIF (12+), DNG/RAW (where the platform decodes it), TIFF multi-page, SVG | pinch/double-tap zoom, pan, rotate + save rotated copy, details/EXIF, folder swipe + slideshow, OCR, edit in another app, Convert to PDF |
| `ArchiveViewer` | ZIP/JAR/APK, 7z, TAR, TAR.GZ/TGZ, TAR.BZ2, TAR.XZ, GZ, BZ2, XZ | folders browsed inside the archive, one entry extracted on tap and opened in-app, "Extract all"; zip-slip, zip-bomb (size, ratio, entry count) and nesting (3) guards |
| `ComicViewer` | CBZ, CB7 | natural page order, lazy extraction + prefetch, zoom, slider, right-to-left (manga) mode, position remembered |
| `MediaViewer` | MP3, M4A/AAC, OGG/Opus, WAV, FLAC, AMR, MP4, MKV, WebM, 3GP, MOV, TS… | media3 ExoPlayer, own controls (seek, ±10 s, repeat, speed), cover art + tags, pauses on stop |
| `FontViewer` | TTF, OTF, TTC, WOFF | Latin + Arabic samples at five sizes, name table |
| `DxfViewer` | DXF (ASCII) | LINE, CIRCLE, ARC, ELLIPSE, (LW)POLYLINE, SPLINE, SOLID/3DFACE, POINT, TEXT/MTEXT, INSERT of blocks; zoom/pan; vector PDF |
| `XpsViewer` | XPS, OXPS | FixedDocumentSequence → pages; Canvas/Path/Glyphs, solid + image brushes, render transforms, clips, resources, obfuscated fonts; vector PDF |
| `CardsViewer` | VCF (2.1/3.0/4.0), ICS | cards with photo, "Add to contacts"; events/tasks with dates, "Add to calendar" |
| `FileInfoScreen` | unknown types + formats that cannot be shown | name, size, detected type, MIME, date, reason, hex + text preview, Open as text, Open with, Share; iWork shows its embedded preview image / QuickLook PDF |

Every viewer: errors stay in the app (`ErrorPane`), back returns, Arabic/RTL text direction by content, "Convert to
PDF" (`ViewerConvert`: existing tools5/convert6 converters, own vector renderers for SVG/DXF/XPS, HTML print for the
rest), Share, Open with, File info.

### Security
* XML: `core/openall/SafeXml.kt` refuses any `<!ENTITY` and external DTDs before parsing (only the W3C SVG/XHTML public
  DTDs are tolerated and stripped), SAX/DOM factories with external entities, DTD loading and doctype declarations off,
  entity resolver answers with nothing. Used for SVG (before AndroidSVG), FB2, XPS. EPUB/NCX/OPF are read with regexes
  (no XML parser at all).
* HTML: no JavaScript by default, no file/content access, no network unless the user taps "Load", scripts/iframes/event
  handlers stripped from e-mail, MHT, EPUB and MOBI markup, `javascript:` links neutralised.
* Archives: entry names normalised and never used as output paths (zip-slip), uncompressed size ≤ 512 MB per entry,
  ratio > 200 refused for entries above 1 MB, ≤ 20 000 entries, nesting ≤ 3 levels; Android 14+'s own ZIP path
  validation errors are reported.

## 4. Every format: before → after

| Format | Before (main c310818) | After (this branch) |
|---|---|---|
| PDF | reader | reader (also when misnamed: detected by `%PDF`) |
| DOCX / DOCM / DOTX / DOTM | Office editor | Office editor; read-only HTML view when the editor cannot load it |
| DOC / DOT | info screen "old format" | **in-app read-only viewer** (text via DocReader) + Edit as Word (docx) + Convert to PDF |
| RTF | read-only (ImportState) | unchanged; also detected by `{\rtf` |
| ODT | read-only (ImportState) | unchanged |
| OTT / FODT | not accepted | **in-app read-only viewer** + Edit as Word |
| TXT | editor (whole file) | editor ≤ 1 MB; **streamed viewer** above; UTF-16-without-BOM / 1252 / 1256 detection in the editor too |
| LOG / INI / CFG / other text | log = editor; others not registered | **text viewer** (chunked, encodings, search, Edit for log) |
| Markdown | raw text editor | **rendered** + Show source + Edit |
| HTML / HTM / XHTML | SEND → PDF only | **safe WebView** (no JS / file access / network unless allowed) |
| MHT / MHTML | not accepted | **safe WebView**, resources served from the archive |
| XML / JSON / YAML / source code | SEND → PDF only | **syntax-highlighted viewer**, pretty print JSON/XML |
| XPS / OXPS | not accepted | **in-app renderer** (own XAML parser + canvas), vector Convert to PDF |
| DjVu | not accepted | info screen — no permissive decoder (DjVuLibre GPL) |
| EPUB | VIEW not registered; SEND → PDF | **EPUB reader** (chapters, TOC, RTL, size, night) |
| FB2 / FB2.ZIP | not accepted | **reader view** with contents |
| MOBI / AZW / AZW3 / PRC | not accepted | **reader view** (own PalmDOC decoder); KF8 as reflowed text; HUFF/CDIC and DRM → info screen |
| CHM | not accepted | info screen — no permissive LZX decoder |
| XLSX / XLSM / XLTX | editor | unchanged (read-only HTML view as fallback) |
| XLS / XLT | info screen "old format" | **read-only sheet view** (XlsReader) + Convert to PDF |
| ODS / OTS | not accepted / reader error | **read-only sheet view** (OdfReader) |
| CSV / TSV | editor (UTF-8 / 1256) | editor with shared detection (BOMs, UTF-16, 1252/1256, quoted-delimiter sniff); big / odd files in a grid viewer |
| Numbers / Pages / Keynote | not accepted | info screen + **embedded preview image / QuickLook PDF** when present |
| PPTX / PPTM / PPSX / PPSM / POTX | editor | unchanged |
| PPT / PPS / POT | info screen "old format" | **read-only slide texts** (own record reader) + Convert to PDF |
| ODP / OTP | not accepted | **read-only slide texts** |
| JPG / PNG / WEBP | SEND → PDF; Files list jpg/png only | **image viewer** (zoom/pan, rotate, EXIF, slideshow, OCR, Convert, share, edit in other app) |
| GIF / animated WebP | SEND → PDF | **animated** (Android 9+) |
| BMP / ICO / WBMP | SEND → PDF | image viewer |
| HEIC / HEIF | SEND → PDF | image viewer (Android 9+, message below) |
| AVIF | not accepted | image viewer (Android 12+, message below) |
| TIFF (multi-page) | not accepted | image viewer with page bar (own TiffDecoder) |
| SVG / SVGZ | not accepted | **AndroidSVG** after XXE/DOCTYPE refusal, vector Convert to PDF |
| DNG / RAW | not accepted | preview where the platform decoder handles it, else in-app error message |
| CBZ | not accepted | **comic reader** (RTL option) |
| CB7 | not accepted | **comic reader** (commons-compress) |
| CBR | not accepted | info screen — UnRAR licence |
| ZIP / JAR / APK | Files tab "Unzip" all | **browse inside + open any entry in-app**, guards |
| 7Z | not accepted | browse + open entries |
| TAR / TGZ / TAR.BZ2 / TAR.XZ / GZ / BZ2 / XZ | not accepted | browse + open entries |
| RAR | not accepted | info screen — UnRAR licence |
| EML | SEND → PDF | **mail view** (headers, safe HTML/text body, inline images, attachments open in-app) |
| MSG (Outlook) | not accepted | **mail view** (own MAPI property reader over CfbReader) |
| VCF | not accepted | **contact cards** + Add to contacts |
| ICS | not accepted | **event list** + Add to calendar |
| MP3 / M4A / AAC / OGG / OPUS / WAV / FLAC / AMR | not accepted | **ExoPlayer audio player** (cover art, tags) |
| MP4 / MKV / WEBM / 3GP / MOV / TS | not accepted | **ExoPlayer video player** (streamed from the sender) |
| TTF / OTF / TTC | not accepted | **font preview** (Latin + Arabic) |
| WOFF | not accepted | font preview (own WOFF → sfnt) |
| WOFF2 | not accepted | info screen — needs Brotli |
| DXF | not accepted | **2-D drawing viewer**, vector Convert to PDF |
| DWG | not accepted | info screen — no permissive reader |
| FDF / XFDF | merge offer | unchanged |
| anything else | PDF reader error screen | **File info** (type by magic bytes, hex + text preview, Open as text, Open with, Share) |

## 5. Dependencies added (all permissive)

| Library | Version | Licence | Used for |
|---|---|---|---|
| `com.caverock:androidsvg-aar` | 1.4 | Apache 2.0 | SVG rendering (viewer, thumbnails, vector PDF) |
| `org.apache.commons:commons-compress` | 1.27.1 | Apache 2.0 | 7z, TAR, BZip2, XZ streams (brings commons-io 2.16.1, commons-lang3 3.16.0, commons-codec 1.17.1 — Apache 2.0) |
| `org.tukaani:xz` | 1.10 | 0BSD | LZMA/LZMA2 for 7z and .xz |

Already present and reused: media3-exoplayer (Apache 2.0), exifinterface, PdfBox-Android, the project's own
CfbReader / DocReader / XlsReader / OdfReader / RtfReader / TiffDecoder / EpubReader / EmlReader.
Not added on purpose: junrar (UnRAR licence restriction), DjVuLibre / djvu.js (GPL), any CHM/LZX library (GPL/LGPL
ports only), Apache POI (too large; the MSG and PPT readers are small own readers instead).

## 6. Not feasible / limits

* DjVu, CHM, RAR/CBR, DWG, WOFF2: info screen with the reason, Open with, Share (licences / missing permissive
  decoders, see §5).
* MOBI with HUFF/CDIC compression or DRM: info screen. AZW3/KF8 is shown as one reflowed flow; its images are not
  placed.
* XPS: gradients are drawn with their first stop colour, ArcSegment as a straight line, visual brushes are ignored.
* DXF: ASCII only (binary DXF refused), no hatches/dimensions/3-D; DWG never.
* Old PPT: slide texts only; old DOC: text only (no formatting/pictures); XLS: values without number formats.
* Numbers/Pages/Keynote: only the embedded preview.
* HEIF needs Android 9, AVIF Android 12, RAW depends on the device's decoder.
* Big files: text is read in chunks (up to 48 M characters), media are streamed, archive entries are extracted one at a
  time (≤ 512 MB); HTML-like formats (HTML ≤ 40 MB, MOBI/FB2/EML ≤ 200 MB, MSG ≤ 100 MB) are read whole.
* `pathPattern` matching for `content://` URIs depends on the sender (Android limitation); the MIME filters cover the
  usual cases.
* **Nothing was tested on a device** — only JVM unit tests and GitHub CI builds.
