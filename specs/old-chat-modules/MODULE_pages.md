# MODULE pages — Organize / Scanner / OCR / Forms / Redact

## Files created
- `app/src/main/res/values/strings_pages.xml` — all new strings, prefix `pg_` (existing `page_*`, `ocr_*`, `form_*`, `redact_*`, `scan_*` reused).
- `ui/pages/OrganizeScreen.kt` — `OrganizeScreen(nav, uri)`: file pick / password prompt, filter chips (all / annotated / bookmarked), thumbnail grid, bottom toolbar (insert blank with style picker before/after/end, insert from file, insert image page via temp `imagesToPdf`, rotate L/R, delete, move back/forward, copy/paste, extract, share), undo, save (overwrite) / save as new → `ResultSheet`, unsaved-changes guard.
- `ui/pages/OrganizeState.kt` — state holder: every op writes a new temp version via `PdfOps`, undo stack of previous temp files, `PdfSession` reopen per version, thumbnail cache keyed `version:page` (300 px wide), annotated pages from `AnnotationWriter.list`, bookmarked pages from `BookmarkDao.forDoc`.
- `ui/pages/PageGrid.kt` — `LazyVerticalGrid` + `detectDragGesturesAfterLongPress` drag-and-drop reorder (auto-scroll at edges, drop-target highlight); long-press without movement = select; tap = toggle/select. Tablet (> 700 dp) uses larger cells.
- `ui/scanner/ScannerScreen.kt` — `ScannerScreen(nav)`: ML Kit `GmsDocumentScanning` (FULL mode, 50 pages, JPEG+PDF, gallery import) via `StartIntentSenderForResult`; page list (preview with colour mode applied, up/down/remove, add more = relaunch), paper size A4/Letter/fit, colour/gray/B&W (ColorMatrix), JPEG quality slider, "save as PDF" into `scansDir` → `ResultSheet`, "OCR now" → `Routes.tool("ocr", uri)`; previous scans list (open / OCR / share / delete); ML Kit failure → error card + gallery picker fallback.
- `ui/scanner/ScanPdfBuilder.kt` — image → PDF builder with paper/colour/quality (PdfBox), preview helper.
- `core/ocr/OcrEngine.kt` — Tesseract wrapper: downloads `tessdata_fast` `ara`/`eng` into `filesDir/tessdata` with OkHttp + progress, `init(filesDir, "ara+eng", OEM_LSTM_ONLY)`, PSM_AUTO, `recognize(bitmap)` → text + word boxes (`ResultIterator`, `RIL_WORD`, confidence), `cancel()` (`stop()`).
- `core/ocr/SearchablePdfWriter.kt` — invisible text layer (`RenderingMode.NEITHER`), pixel→point mapping incl. page `/Rotate`, Arabic words shaped with `ArabicShaper` + `PdfOps.arabicFont`, Latin words with Helvetica, horizontal scaling to fit the word box.
- `ui/ocr/OcrScreen.kt` — `OcrScreen(nav, uri)`: language chips (ara+eng / ara / eng, default from `settings.ocrLanguages`), mode searchable PDF / text only, page range, cancellable progress dialog (download + per page), text results per page (selectable; copy / share / save .txt), searchable PDF → `ResultSheet`.
- `core/pdf/FormOps.kt` — `listFields`, `fillFields` (sets values, tries PdfBox appearance generation, falls back to and always ends with `NeedAppearances = true`), `resetAll`, `fieldsOnPage(file, page)` → `FieldRect(name, type, rect)` in PDF points for reader highlighting.
- `ui/forms/FormsScreen.kt` — `FormsScreen(nav, uri)`: fields grouped by page, editors per type (text / multiline, switch, radio, dropdown), modified counter, save (overwrite) / save as, reset, flatten (`PdfOps.flattenForms`, with pending edits applied first), export/import JSON (SAF).
- `ui/security/RedactScreen.kt` — `RedactScreen(nav, uri)`: search & redact (query, match case, hit list with checkboxes + select all) and manual mode; region list with delete; apply → `PdfOps.redact` → `ResultSheet`; `redact_warning` shown + confirm dialog. Tablet shows region list beside content.
- `ui/security/RedactManualView.kt` — single-page viewer with page selector, draw/pan toggle, drag-to-draw rectangles (screen→PDF points), black-box preview of existing regions.

## Notes / limitations
- No gradle run; please compile. API names assumed: Tesseract4Android `TessBaseAPI.stop()/clear()/PageSegMode.PSM_AUTO`, ML Kit `GmsDocumentScanningResult.fromActivityResultIntent`, PdfBox `PDButton.getOnValues()`, `PDPageContentStream.setHorizontalScaling`, M3 `MenuAnchorType`, `SegmentedButton`, `VerticalDivider`.
- Searchable PDF: Arabic words are written as presentation forms (per brief), so some viewers may not match a plain Arabic search against them; Latin words use Helvetica (non-WinAnsi chars are skipped silently).
- `PdfOps.redact` ignores page `/Rotate`; manual regions are mapped assuming rotation 0 (search hits come from PDFium in page space and are passed through).
- Organizer "annotated / bookmarked" chips refer to the original numbering and are cleared after any op that changes the page count.
- Scanner also requests `RESULT_FORMAT_PDF` but only the JPEG pages are used (we build our own PDF to honour paper/colour options).
- OCR renders pages at 300 dpi capped to 3500 px on the long side.
