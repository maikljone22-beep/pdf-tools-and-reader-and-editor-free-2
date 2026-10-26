# Crash audit, round 5 (static analysis)

The audit was done by reading code. Nothing was run on an emulator. Paths are under `app/src/main/java/com/smartdocs/editor/` unless they start with `app/`. Line numbers are approximate, because other agents were editing some of these files at the same time.

**Type checks:** kotlinc 2.3.21 with the Compose plugin, run against the last `classes.jar` plus a generated R stub (`scratchpad/crash/fc.sh`).
- ui/view5, core/view5, core/tts, ui/edit5, core/edit5, ui/search, core/search, ui/security, core/redact, ui/common, ui/tools, ui/tools5, core/tools5, ui/ai5, core/ai5, ui/forms5, core/forms5, core/forms, ui/reader, MainActivity, core/pdf/PdfOps, android/print/WebPdfWriter and ui/forms/FormCalcUi produce no syntax errors and no type errors in the edited code.
- The errors that remain refer only to symbols the stale jar does not have yet (gaps6, tools5, Rhino and ML Kit classes).
- One fork's edit had broken a string literal (`ui/tools/ExportTools.kt:133`). The check caught it and it is fixed.

Many files are CRLF. All edits kept each file's existing line endings.

## 1. Bugs fixed

### Shared, high impact
| File | Class | Fix |
|---|---|---|
| ui/common/Components.kt `ToolRunner.run/runMany` | 5 (Errors were not caught) | Now catches `Throwable` and rethrows `CancellationException`. Before, an OutOfMemoryError, NoClassDefFoundError or StackOverflowError from PdfBox killed the app on every tool screen. |
| core/pdf/PdfOps.kt `parseRange` (~61) | 4 | `toInt()` became `toIntOrNull`, and the range is clamped to `1..count`. "99999999999" crashed and "1-2000000000" froze. This affects Split, Watermark, Convert and the recipe Rotate step. |
| android/print/WebPdfWriter.kt | 5/7 | `write()` runs in `postDelayed` on the main looper, and `adapter.onWrite` runs in a callback. Both are now in try/catch and report through `onDone`. Affects To PDF+ (web, HTML, text, Office, EPUB), Share to PDF and the Convert URL panel. |
| MainActivity.kt `handleIntent` (ShareImport) | 5 | `ShareImport.read` threw inside `lifecycleScope.launch` when shared content was unreadable. It is now wrapped and shows a toast. |
| ui/reader/ReaderScreen.kt (panels sheet) | 4/6 | `panel!!` was inside the `ModalBottomSheet` content, which recomposes in its own composition after `panel = null` (NPE when you jump from the outline, bookmarks or thumbnails sheet on phones). The value is now captured with `panel?.let`. |
| ui/reader/ReaderScreen.kt | 5/7 | The `followFileLinkAt` launch is now wrapped. The AI agent's `share` lambda is now wrapped. |
| ui/reader/ReaderIntents.kt `copy` / `shareText` | 7 | `TransactionTooLargeException` on huge text. The clip is capped at 200k characters and EXTRA_TEXT at 100k, and the clip call is guarded. |
| ui/tools/ToolCommon.kt `ResultsList` | 7 | The share buttons are now guarded (`shareIntent`/FileProvider can throw). |
| ui/forms/FormCalcUi.kt (~60) | 4 | `rememberDatePickerState` threw for an initial year outside 1900..2100 (for example, "1/1/24" parsed as year 24). The initial date is now kept only when its year is inside `DatePickerDefaults.YearRange`. |

### view5 (measure workbench, comments, split, presentation, reflow, stamps)
| File | Class | Fix |
|---|---|---|
| ui/view5/Workbench.kt (~64/68) | 2/4 | A page size of 0 gave Infinity or NaN, so `roundToInt()` threw (IllegalArgumentException) or the bitmap was enormous. `WbView` sizes now have a minimum, and the render height is clamped to 1..6000. |
| ui/view5/WorkbenchModel.kt `finish()` | 4 | Guarded `p[0]` when there are no points. |
| ui/view5/CommentManager.kt | 5 | Export CSV, Word, summary and highlights did file IO in `scope.launch` with nothing catching errors. They are now wrapped and show a message. |
| ui/view5/CommentManager.kt (LazyColumn) | 6 | Duplicate keys crashed the list: pending annotations all have index −1 and can share page, date and type. The key now includes the list position. |
| ui/view5/MeasureListSheet.kt | 5 | The CSV export write is now guarded. |
| ui/view5/TrashScreen.kt | 5 | list, purge, restore, empty and delete-forever are now in `runCatching`, including `Trash5.purge`, which runs at app start. |
| ui/view5/SplitCompare.kt `PagePane` | 2 | A zero-width or tiny pane gave a negative `requiredWidth`, and a page width of 0 gave an infinite height. Both throw. Sizes are now clamped. |
| ui/view5/Presentation5.kt | 2/4 | A zero page size or out-of-range page gave a huge or invalid bitmap request. Now guarded and clamped to 4096. |
| ui/view5/ReflowReader.kt | 5/4 | One bad page threw inside the build `LaunchedEffect`, which crashed the app. Each page is now guarded. The search `highlight()` could call `substring(from, a)` with `a < from`, which threw; the indexes are now clamped. `ReflowImage` sizes are clamped. |
| ui/view5/StampPicker5.kt | 6 | Dynamic-stamp templates are LazyVerticalGrid keys, so adding the same template twice crashed. Templates are now de-duplicated on load and on add. |
| ui/view5/PageOverlays5.kt (reading ruler) | 4 | With `hPx == 0`, NaN reached `roundToInt` and threw. Now guarded. |

### tools5 / tools
| File | Class | Fix |
|---|---|---|
| ui/tools5/T5Common.kt `T5Job.launch` | 5 | Catches `Throwable`. Covers every tools5 job: Scan, OCR+, combine, mail merge, compare, PDF/A, optimize, export. |
| ui/tools5/AccessibilityScreen.kt | 5/4 | `launchSafe` catches `Throwable`. A `file!!` is now null-safe. |
| ui/tools5/MergePortfolioScreens.kt (~150) | 5 | `Portfolio.extract` (PdfBox) is now in `runCatching`. |
| ui/tools5/RecipesScreen.kt (~93) | 5 | Recipe export/share IO is now guarded. |
| core/tools5/BatchWorker.kt | 5/7 | `enqueue` (spec write plus WorkManager) is guarded, and `all()` skips unreadable specs. |
| ui/tools5/ScanStudioScreen.kt (~178) | 6 | Duplicate LazyRow keys (the same file twice in a group). The key now includes the index. OCR language detection is guarded. |
| ui/tools5/ScanParts.kt | 5 | The vCard share and the receipt CSV append are guarded. |
| ui/tools5/Compare2Screen.kt | 4 | `old!!`/`new!!` are now null-safe. |
| ui/tools/ConvertFromPdf.kt (~184) | 5 | Catches `Throwable`. |
| ui/tools/ExportTools.kt (PDF→Text) | 7/OOM | Share text is capped at 100k characters and guarded. Clipboard is capped at 200k. The on-screen `Text` is capped at 300k (layout ran out of memory). "Save TXT" still writes everything. |
| ui/tools/AttachmentsTool.kt (~42) | 5/ANR | The attachment copy moved off the main thread into the runner. The file name is sanitized. |
| ui/tools/WatermarkTool.kt (~122) | 2 | `aspectRatio(0/NaN)` threw. It now falls back to 0.707. |

### edit5 / search / security / redact
| File | Class | Fix |
|---|---|---|
| core/redact/RedactApplier.kt `rasterize()` | OOM | Each page is rendered, saved and freed before the next, capped at about 24 MP. Pages of size 0 are skipped, and the renderer is closed in `finally`. |
| core/redact/RedactMath.kt `pages()` | 4 | A huge range built billions of items. Both ends are now clamped. |
| core/edit5/E5Format.kt `PageSet.parse` | 4 | Same huge-range fix. |
| core/redact/RedactMarks.kt `read()` | 6 | Redaction-review ids are now unique (copied annotations can share a name, and duplicate keys crashed the list). |
| ui/security/RedactScreen.kt (~143) | 4 | `coerceIn(0, pageCount-1)` threw on a 0-page document. Fixed. |
| ui/security/RedactManualView.kt (~54) | 2/5 | `pageSize` is guarded, and a 0×0 page no longer reaches `aspectRatio` (which threw). |
| ui/edit5/E5Ui.kt `E5PageView` / `E5Doc.undo()` | 2/5 | Infinite or NaN page ratio is fixed. The page-info reload on Undo is guarded. |
| ui/edit5/DecorScreen.kt, HeaderFooterScreen.kt | 5 | Loading saved settings is guarded (corrupt or encrypted file). |
| core/edit5/PageOps5.kt (~220) | 2 | Blank-page detection with a page width of 0 is fixed. |
| ui/edit5/PageLabelsScreen.kt (48, 56) | 4 | `coerceIn` with a negative bound is fixed. |
| ui/search/FindReplaceScreen.kt | 4/6 | Page-range overflow is guarded. Result keys include the position. |
| ui/search/HitList.kt, GlobalSearchScreen.kt | 6 | Keys include the position, so duplicates can no longer crash the list. |

### ai5 / forms5 / forms
| File | Class | Fix |
|---|---|---|
| core/forms/AfExpr.kt `AfNumbers.parse/raw`, AfFormat.kt `formatNumber` | 4 | `BigDecimal(Infinity)` crashed while drawing a field ("1e999", or a SUM that overflows). NaN and Infinity are now handled. |
| core/forms/AfFormat.kt `formatPickedDate`, core/forms5/FieldDates.kt `formatUtc` | 4 | An invalid date pattern from the PDF made `SimpleDateFormat` throw. Both fall back to `yyyy-MM-dd`. |
| ui/forms5/InPageFormBar.kt (date picker) | 4 | Same DatePicker year-range crash as FormCalcUi. Fixed the same way. |
| ui/forms5/InPageXfaBanner.kt, FormFillState.kt `addRow` | 5 | Catch `Throwable`, so StackOverflowError or OOM on huge XFA forms no longer crashes the app. |
| ui/ai5/AiHubScreen.kt `exportPdfAndShare` | 5 | A failed PDF write is now caught and shown as a toast. |
| core/ai5/MindMapLayout.kt `toPng` | OOM | The bitmap is capped at about 16 MP (it could be 8000×8000). |
| core/ai5/FormFillAi.kt `suggest` | 6 | Duplicate or empty field names caused duplicate LazyColumn keys in FormFillSheet. They are now removed. |
| core/ai5/Citations.kt, ui/ai5/AiToolsPanel.kt `parsePages` | 4 | Uses `toIntOrNull` and caps ranges. |
| core/ai5/Voice.kt `SpeechInput.start` | 7 | Checks that a recognizer exists and guards create/start. On failure it reports `onError` and `onEnd` instead of crashing. |
| core/forms/XfaSupport.kt, XfaJs.kt, core/forms5/XfaRepeat.kt | 4 | `toInt()` on digits taken from the file now uses `toIntOrNull`. |

### Checked and found OK
- **Nested scrolling:**
  - Every `horizontalScroll` row was traced through its helpers (Palette, DashPicker, LineEndPicker, ColorRow, ChipChoice, PresetRow, ToolRow contents, View5*Chips, Annot2*, PropertyBar). The only nested case left is Workbench, which was already fixed with `Palette(scroll = false)`.
  - Every LazyColumn or LazyVerticalGrid in a sheet or dialog has a bounded height, a weight, or `scrollable = false` in ToolScaffold.
- **Format strings:**
  - Every `%d` argument in the app is an Int.
  - No string is formatted with fewer arguments than it needs.
  - values and values-en have the same placeholders.
- **Desktop-only Java APIs:** no `java.awt`, `javax.imageio`, `javax.xml.bind` or `java.beans`. The only asset font (`fonts/NotoNaskhArabic-Regular.ttf`) exists.
- **FileProvider:** `res/xml/file_paths.xml` covers files, cache, external-files, external-cache and external.
- **Rhino:** interpreted mode (`optimizationLevel = -1`), and `Context.exit()` is in `finally`.
- **WorkManager:** inputs are only ids.
- **Routes:** every navigate target is registered. acct, share5 (including the "storage" alias), conn, gaps6 (through acctRoutes), office5 and view5 are all wired in `ui/Navigation.kt` now. Unknown tool ids fall back to ComingSoonScreen.

## 2. Risky spots not fixed
- **core/edit5/WatermarkOps.kt:184** `image!!`: a watermark image that fails to load shows "null" as the error. It does not crash.
- **ui/view5/ReflowReader.kt:** `SelectionContainer` wraps a `LazyColumn`. Older Compose versions crashed when the selected text scrolled out of view. Not reproduced here. If reflow crashes while selecting text, remove the SelectionContainer.
- **ui/view5/Keys5.kt `V5ShellHost`:** `Trash5.purge` runs on the main thread at start. It is now guarded, but on a huge trash it could cause a short freeze (ANR), not a crash.
- **Out of scope (not checked in depth):** core/office5, ui/office, cloud, sync, billing, settings, home, files, account, pages and gaps6 were being edited by other agents.

## 3. Screens to retest first (tablet, Android 13)
1. **Reader → Comment tab:**
   - Measure, Callout, Typewriter, Tick, Area highlight and Line in the workbench: tap points, use the style dialog and the scale dialog, then save.
   - Comments manager: filters, every export, and "check".
2. **Reader → View tab:** Split view and Compare (drag the divider to an extreme, zoom out), Presentation, Reflow (search, headings, images), Rulers, Grid, Reading ruler and Lens, Tell me, Print options.
3. **Reader panels on a phone-sized window:** outline, bookmarks and thumbnails sheet, then tap an entry (the NPE fix).
4. **Stamp picker → Dynamic tab:** add a template twice.
5. **Tools hub:**
   - Scan Studio, OCR+ and To PDF+ (web, HTML), Share-to-PDF from another app.
   - Mail merge, Portfolio, Compare (new), Accessibility, PDF/A, Optimize/Export+, Recipes and Batch.
   - PDF→Text on a long PDF (share and copy), Attachments with a big file, Watermark preview.
6. **Redact:** raster or max-security mode on a 10+ page PDF, a huge page range, the Manual tab, and the review list on a PDF that already has redaction marks.
7. **Edit5:** Watermark, Background, Header/Footer, Page labels, Smart split, and Undo in Crop, Links, Templates and Bookmarks.
8. **Search:** Find & replace (huge range, result list), Search & highlight, Global search.
9. **Forms:** number fields with huge values, the date picker on a field holding "1/1/24", XFA convert, write-back and add row.
10. **AI:** Export PDF in Chat, Summary and Study, mind map PNG, Translate with a page range, Form fill, and Voice/dictation with and without speech recognition.
11. **Trash and app-icon shortcuts.**
