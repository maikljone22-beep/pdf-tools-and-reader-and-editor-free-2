# MODULE_tools — tool screens (prefix `tl_`)

## Files created / replaced
UI (`app/src/main/java/com/smartdocs/editor/ui/tools/`)
- `ToolScreens.kt` — router `ToolScreens(nav, id, uri)`; dispatches to own screens and to other modules' screens (`OrganizeScreen`, `ScannerScreen`, `OcrScreen`, `RedactScreen`, `FormsScreen`, `SignaturesScreen`, `DigitalSignScreen`, `FillProfileScreen`, `StampsScreen`, `AiScreen`, `AiSettingsScreen`); unknown id → `ComingSoonScreen`.
- `ToolCommon.kt` — shared pieces: `ToolScaffold` (top bar + back, progress dialog, `ResultSheet`, error snackbar), `rememberInputFile(uri)`, `outName`, `PrimaryButton`, `ChoiceChips`, `ColorChips`, `NumberField`, `ResultsList` (multi-file results with share-all), `rememberTextFilePicker`, `ComingSoonScreen`.
- `CreateTools.kt` — `blank` (count 1-50, A4/A5/Letter/Legal/A3, orientation, style, page colour) and `images_to_pdf` (multi picker, thumbnail grid with move up/down/remove, fit-A4, quality).
- `MergeSplitTools.kt` — `merge` (ordered list, page counts, total) and `split` (every page / every N / custom ranges `1-3; 4-6; 7`, results list).
- `PageTools.kt` — `compress` (3 presets, size before→after), `page_numbers` (4 positions, start, size, Arabic digits), `doc_info` (meta + editable fields → `PdfOps.setInfo`).
- `WatermarkTool.kt` — `watermark` with live Canvas preview on the rendered first page (tablet: options beside preview).
- `SecurityTools.kt` — `protect`, `unlock` (wrong password → `password_wrong`), `flatten` (forms only / rasterize).
- `ExportTools.kt` — `pdf_to_images` (JPG/PNG, 72/150/300 DPI, range → `<docsDir>/<name>_images/`, share as `image/*`), `pdf_to_text` (selectable text, copy, share, save .txt); shared helpers `renderPages`, `extractAllText` (PDFium then PdfBox fallback).
- `ConvertTool.kt` — `convert`: tab "إلى PDF" (TXT file via `TextToPdf`, web page via `WebPdfWriter`) and tab "من PDF" (DOCX via `DocxWriter`, HTML, TXT, images → pdf_to_images; XLSX/PPTX show `convert_cloud_note`).
- `CompareTool.kt` — `compare`: two pickers, LCS line diff, side-by-side on tablet / stacked on phone, legend with counts, `compare_identical`.
- `MiscTools.kt` — `print` (`PrintHelper`), `read_aloud` (opens `Routes.reader`), `wifi` (server start/stop, IP shown, stops on dispose), `help` (about + feature list).

Core
- `core/pdf/TextToPdf.kt` — text → paginated A4 PDF, Arabic shaped via `ArabicShaper`, RTL lines right-aligned, word wrap.
- `core/pdf/DocxWriter.kt` — minimal valid .docx (content types, rels, styles, core props, document.xml; `<w:bidi/>` for Arabic paragraphs, page break per source page) + `DocxWriter.html(pages)` HTML export.
- `core/util/TextDiff.kt` — LCS line diff → SAME/ADDED/REMOVED/CHANGED (capped at 1500 lines per side).
- `core/util/PrintHelper.kt` — `PrintHelper.printPdf(context, file)` streaming `PrintDocumentAdapter` (named this way because the reader module already has `core/util/PdfPrint.kt`).
- `core/files/WifiTransferServer.kt` — ServerSocket HTTP server (port 8080+, tries 20 ports), RTL Arabic page listing `docsDir`, downloads (`/dl/<name>`, path-traversal checked) and multipart upload (`POST /upload`, ≤512 MB), `deviceIp()` via `NetworkInterface` (no ACCESS_WIFI_STATE needed).
- `app/src/main/java/android/print/WebPdfWriter.kt` — **in package `android.print` on purpose**: the constructors of `PrintDocumentAdapter.LayoutResultCallback/WriteResultCallback` are package-private in the SDK stubs, so a same-package class can subclass them without reflection. Drives `WebView.createPrintDocumentAdapter` → `onLayout` → `onWrite` into a `ParcelFileDescriptor`. Main-thread only; `convertUrl`/`convertHtml` are suspend wrappers.

Resources
- `res/values/strings_tools.xml` — all new strings, prefix `tl_`. Existing names reused (tool_*, compress_*, size_before_after, format_*, convert_cloud_note, compare_*, password_*, perm_*, info_*, wifi_*, …).

## Notes / limitations
- `assets/fonts/NotoNaskhArabic-Regular.ttf` is referenced by `PdfOps.arabicFont` but `app/src/main/assets` did not exist when this module was written; without it Arabic text in TextToPdf / watermark / page numbers falls back to Helvetica (Arabic glyphs replaced by `?` in TextToPdf). Someone owning assets should add the font.
- Web → PDF needs internet and relies on WebView's print adapter; some pages with heavy JS may render partially (1.2 s settle delay after `onPageFinished`).
- DOCX/HTML/TXT exports are text-only (no images/layout). XLSX/PPTX are deliberately unavailable offline.
- Compare diff is line-based on extracted text; scanned PDFs without text produce an "identical" (empty) result — OCR first.
- Wi-Fi server is unauthenticated by design (LAN only); the screen shows a warning and stops the server when leaving.
- `ResultSheet`'s "save as" uses the `application/pdf` mime even for .txt/.docx/.html results (component limitation in `ui/common`).
- No gradle run was performed; expected compile risks are limited to Compose API nuances (e.g. `FilterChip` leadingIcon null branch) which the lead can adjust quickly.
