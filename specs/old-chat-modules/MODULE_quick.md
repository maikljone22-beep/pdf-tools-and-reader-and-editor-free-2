# MODULE_quick — one-step document tools inside the reader

Goal: every document tool used from the reader is one step. Tap the tool, optionally confirm a compact sheet whose defaults are already set (one primary button), and the open document is replaced in place. The reader reloads at the same page and a "تم" bar offers "تراجع" (undo) and "حفظ كنسخة" (save as a copy). Inside the reader there are no file pickers, no full tool screens and no ResultSheet. The full tool screens are still reachable from the Tools tab.

## Files
New
- `ui/quick/QuickController.kt`: `QuickAction` enum, `QuickController` (sheet, confirm, running/progress/cancel, done bar, undo, save as copy, every action's operation), `QuickInfo`, `QuickDone`, `rememberQuickController`.
- `ui/quick/QuickActions.kt`: `QuickActionHost` (sheets, confirmations, progress dialog with cancel, messages to the snackbar), `QuickDoneBar` (sits in the Scaffold's snackbar slot, above the bottom toolbar), `OcrSuggestionBanner`.
- `ui/quick/QuickSheets.kt`: shared `QuickSheet` scaffold, Compress, Watermark (live mini preview) and Page-numbers (schematic preview) sheets.
- `ui/quick/QuickSheets2.kt`: Protect, Extract and Properties sheets.
- `ui/ocr/OcrSheet.kt`: the reference OCR dialog as one sheet.
- `ui/ocr/OcrInPlace.kt`: `OcrOptions`, `OcrPrefs` (SharedPreferences "ocr"), `runOcrToFile`.
- `core/ocr/EditableTextPdfWriter.kt`: the new "editable text" OCR mode.
- `res/values/strings_quick.xml` and `res/values-en/strings_quick.xml`: 129 `qk_` strings.

Changed
- `ui/reader/ReaderState.kt`
  - `password` now has an `internal set`.
  - New `applyInPlace(newPassword, op)`: saves pending annotations, then under `saveMutex` keeps a backup copy for undo, runs `op(src, out, pw)`, swaps the file atomically (`swapFile`: close the session, rename, reopen), sets `writeBackResult` and returns the backup.
  - New `restoreFrom(backup, pw)`, used by undo and save as copy.
  - `reopen()` now also re-measures page sizes when a probe page changed size. Rotating all pages keeps the page count, so the old check missed it.
- `ui/reader/ReaderToolbars.kt`
  - `ReaderActions.quick` was added.
  - The Tools tab is now a grid: 14 one-step actions first, then the screen tools (organize, redact, forms, ai, merge, split, compare, pdf_to_images, pdf_to_text), which open on the current file.
  - In the Edit toolbar, watermark, page numbers and properties now open quick sheets, and OCR was added.
- `ui/reader/ReaderScreen.kt`
  - Creates the controller and adds `QuickActionHost`.
  - `QuickDoneBar` goes in the snackbar slot.
  - The OCR banner is shown at the top of the content.
  - The More menu gained compress, OCR, protect and flatten; "Document properties" there now opens the quick sheet. The INFO panel is still available from the View toolbar.
- `core/pdf/PdfOps.kt` (additions only)
  - `isEncrypted(src, pw)`.
  - `flattenAnnotations(src, out, pw)`: flattens the AcroForm, draws each visible annotation's appearance stream into the page (`/BBox`×`/Matrix` mapped onto `/Rect`) and removes the annotation. Links and popups are kept and text stays vector.
- `core/ocr/OcrEngine.kt`: `label()` now covers fra, deu, spa, tur, fas and urd.

## One-step flows (from the reader)
| Tool | Taps | Behaviour |
|---|---|---|
| Compress | tool → button | Sheet with chips smallest / **medium** / high. The real result size is computed in the background for the selected chip ("from X to Y, saves Z%"). If it would not get smaller, the button is disabled. Apply reuses the precomputed file. |
| Watermark | tool → button | Text prefilled with the author name (or "مسودة" if none). Opacity, rotation and size sliders, 5 colours and a live preview on the current page. |
| Page numbers | tool → button | Position chips, Arabic digits switch (on when the UI locale is Arabic) and a start number. |
| Protect | tool → password ×2 → button | Permissions password and permissions are folded under "خيارات". The reader reopens with the new password. |
| Remove password | 1 tap | Uses the known password, or the empty one for permission-only files. Says "not protected" when there is nothing to remove. |
| Flatten | tool → confirm | Default keeps vector text (`flattenAnnotations`). A checkbox switches to the rasterising flatten. |
| Rotate all | 1 tap | Rotates 90° clockwise; undo is in the done bar. |
| Blank page after current | 1 tap | Jumps to the new page. |
| Delete current page | tool → confirm | Refused on a 1-page document. |
| Extract | tool → button | Current page (default) or a range, written to a new file in Documents. The bar offers Open and Share; the document itself is unchanged. |
| OCR | tool → "تنفيذ" | See below. Replaces the open document. |
| Properties | tool → save | Title, author, subject and keywords, plus read-only file information. |
| Print / Share | 1 tap | Existing helpers; pending annotations are included. |

Every in-place action:
- saves pending annotations first;
- writes back to the source (the reader's existing `writeBackResult` handling shows the permission or re-pick UI when needed);
- reloads at the same page;
- shows the done bar for 10 s with Undo and Save as copy. After that the backup is deleted.
- Cancelling or a failure leaves the document untouched, because the swap happens only after the operation succeeds.

## OCR
- One sheet:
  - Page range: all pages (default), or from–to with auto-correction; locked to one page when opened with `openOcrForPage`.
  - Languages: 8 chips. Arabic and English are preselected the first time; the last choice and mode are remembered in SharedPreferences "ocr". A warning shows when no language is selected, and a note when data will be downloaded.
  - Mode: two cards with their descriptions.
  - Button: "تنفيذ".
- Progress is shown while language data downloads (percentage), then per page ("page 3 of 20"), with a cancel button.
- Only pages without a text layer are recognised. If nothing needs OCR, the reference's "already has text" message is shown.
- Searchable mode uses the existing `SearchablePdfWriter` (invisible layer).
- Editable mode (`EditableTextPdfWriter`):
  - Renders the page at 300 dpi and runs Tesseract to get word boxes.
  - Paints each word box with the median colour of its 2 px border ring, so the scanned glyphs disappear.
  - The cleaned bitmap (about 200 dpi JPEG) becomes the page background.
  - Each word is written as visible text (FILL) in the ink colour sampled from the scan. Size and baseline come from glyph bounds measured with an Android `Paint`. Width is fitted with horizontal scaling.
  - Arabic is written with `ArabicShaper` + `showArabic` and Latin with Helvetica, so the text is searchable and copyable with logical text.
  - Words below 45 % confidence keep their scanned glyphs and only get invisible text.
- Suggestion: if the first three pages have no text and the first page has visible ink (so blank documents are excluded), a banner "يبدو أن هذا مستند ممسوح ضوئياً — التعرف على النص؟" appears with one button that opens the sheet. It can be dismissed for the session and hides once the document has text.

## Limitations / TODO
- Undo is available only while the done bar is visible (10 s), and only for the latest action.
- Editable OCR resets a recognised page to an unrotated box at its display size. Annotations already on that page keep their old coordinates, so they can be misplaced on rotated pages or pages whose crop box has an offset. The text font is the app's Arabic font or Helvetica, not the original typeface. Latin characters outside WinAnsi are skipped in the visible layer.
- Compress only re-encodes images (`PdfOps.compress`). There is no CCITT for monochrome images and no image de-duplication.
- `openOcrForPage(page)` exists but is not yet attached to a long press on a text-less page; that needs a hook in the page gesture code.
- Rotate all always rotates clockwise; to rotate the other way, use undo or Organize.
- Verified by compiling only (`assembleDebug`): no Kotlin errors in my files, and the APK was produced on the last build. Nothing was run on a device.
