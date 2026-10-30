# MODULE_office — Word / Excel / PowerPoint viewer and editor

Users can open, view and edit `.docx`, `.xlsx` and `.pptx` files, and plain text (`.txt/.csv/.md/.log`). Edits are saved **into the same file**: the working copy is rewritten, then `app.documents.writeBack(file)` + `rememberWriteBack()` (same pattern as ReaderScreen) and `SaveHooks.onSaved(app, file)`. Old binary files (`.doc/.xls/.ppt/.rtf`) show an Arabic explanation with "open with another app" and "share".

No Gradle run (Round 3). Everything was compiled with a standalone `kotlinc` 2.1 + the Compose compiler plugin against the real dependency jars from the Gradle cache (Compose 1.7.8, Material3 1.3.1, navigation 2.8.9, android-36). The few project symbols outside this module were stubbed with their real signatures. Result: **0 errors**. The pure-Kotlin core was also run on sample files: python-docx, openpyxl and python-pptx open the saved files, and LibreOffice renders them correctly. This holds for files made by python-docx/openpyxl/python-pptx and for files re-saved by LibreOffice.

## Files

### New: `core/office` (pure Kotlin, testable off-device)
| File | What |
|---|---|
| `WordDoc.kt` | Editable .docx. The body is a list of XML items (exact source text), with undo/redo snapshots. Text edits keep the formatting of unchanged runs (prefix/suffix diff). Also: bold/italic/underline/strike/size/colour/highlight, alignment, RTL, heading styles, bullet/number lists (+ levels), insert/split/merge/move/delete paragraphs, page break, picture, table, table cells, add/delete row. `save` adds the media, numbering.xml and heading styles only when they are used. `saveInPlace` makes the saved state the new baseline. |
| `WordParts.kt` | Styles/numbering/rels info, XML templates (heading styles, list definitions, inline picture, table), `RunSeg`/`ParaInfo`/`CellInfo`, `spliceSegments`. |
| `SheetDoc.kt` | Editable workbook: cells (input, style, shared-string index, cached values), formula evaluation with memo and cycle detection, set/clear/paste, insert/delete rows/cols (fixes formulas in every sheet, merges, widths, row attributes), merge/unmerge, column width, add/rename/delete sheet. Undo uses cell diffs and structural snapshots. |
| `SheetIo.kt` | xlsx load (shared formulas expanded) and save. Only touched sheets get a regenerated `sheetData`; the rest of each worksheet is kept as is. Also writes the sheet list/rels/content types, drops calcChain and sets `fullCalcOnLoad`. |
| `FormulaEngine.kt`, `FormulaFunctions.kt` | Tokenizer/parser/evaluator. Supports A1, `$A$1`, `Sheet!A1`, `'Sheet name'!A1:B2`, `A:A`, the operators `+ - * / ^ & % = <> < > <= >=`, and about 45 functions (SUM, AVERAGE, MIN, MAX, COUNT, COUNTA, IF, IFERROR, AND/OR/NOT, ROUND*, SUMIF/COUNTIF/AVERAGEIF, VLOOKUP/HLOOKUP, text functions…). `rewriteRefs`/`translate` update references when rows/cols move and when pasting. Arabic-Indic digits count as numbers. For unknown functions the cached value from Excel is kept. |
| `SlideDoc.kt` | Editable .pptx: reorder/delete/duplicate slides, new blank slide (same layout), edit shape text (keeps run formatting), add/delete text box, render model per slide. Save rewrites `sldIdLst`, rels and content types, and removes the parts and notes of deleted slides. |
| `XmlEdit.kt` | Schema-ordered child replacement, attribute edits, splicing, and rels / [Content_Types] helpers. |

### Changed existing engine files (bug fixes / hooks)
- `OoxmlPackage.rewrite`: new parts in `replace` (media, new sheets/slides, numbering.xml) used to be **silently dropped**. They are now appended. Duplicate zip entries are skipped instead of crashing.
- `XlsxReader`: added the `skipCells` option (metadata-only load). `XlsxWriter.requestRecalc` is now `internal` so it can be reused.
- `PptxReader`: added `readSlideXml(relsPart, xml, cx, cy)` (renders edited slides) and `slideSize()`. `SlideShape.TextBox.spIndex` (new last param with a default) maps rendered boxes to their `p:sp`.

### New: `ui/office`
| File | What |
|---|---|
| `OfficeScreen.kt` | **`@Composable fun OfficeScreen(nav: NavHostController, uri: String?)`**: load and kind detection (content first, the name only as a fallback), top bar (undo/redo/search/save/menu), save + write-back + SaveHooks, an unsaved-changes dialog on back, Convert to PDF (→ `ResultSheet`), Print (→ `PdfPrint`), Share/Open with (temp copy with the right extension), read aloud (`ReadAloudController` + a mini bar), and the legacy/failed screen. |
| `OfficeCommon.kt` | `OfficeEditorState` (base class every editor implements), search bar, Save/Discard dialog, text/table dialogs, `OfficeFiles` helpers. |
| `OfficeRibbon.kt` | **Ribbon framework**: `RibbonTab`, `OfficeRibbonRegistry`, `OfficeCommandRegistry`, `RibbonButton/RibbonChip/RibbonDivider`. |
| `WordState.kt`, `WordEditor.kt`, `WordRibbon.kt` | Word. Reading mode = WebView of `OfficeHtml.docx` (faithful layout, `findAllAsync` search). Edit mode = a page-like list. Tap a paragraph and type; Enter splits it. Formatting is shown live. Tables: tap a cell to edit, add/delete row. Pictures are shown inline. Tabs: Home, Insert, Layout, View. |
| `SheetState.kt`, `SheetEditor.kt`, `SheetGrid.kt`, `SheetRibbon.kt` | Excel. Canvas grid that draws only visible cells, with fixed letter/number headers and optional frozen row/col. RTL sheets are mirrored. Gestures: pinch zoom, fling, tap select, long-press-drag range, tap the selected cell for a context menu. Formula bar: tapping cells inserts references while typing `=`, and closing brackets are added. Sheet tabs (+, rename, delete). Copy/cut/paste (TSV; formulas re-based internally). AutoSum/AVERAGE/COUNT/MIN/MAX, function list. Tabs: Home, Insert, Formulas, View. |
| `SlideState.kt`, `SlideEditor.kt`, `SlideRender.kt` | PowerPoint. Native Compose slide rendering, used for thumbnails (bottom strip on phones, side column on tablets), the main canvas and the slide show (full screen `HorizontalPager`, system bars hidden, tap to advance). Edit mode: tap a text box to edit it; a "Text boxes" sheet lists empty placeholders too. Tabs: Home, Insert, Slide Show, View. |
| `TextState.kt` | Plain-text editor. Keeps the original encoding and BOM (Windows-1256 Arabic files too). |
| `OfficePdf.kt`, `OfficePdfGrid.kt` | PDF export with Android `PdfDocument` + `StaticLayout`: vector text, Arabic shaping and bidi. Word reflows on A4 (headings, lists, alignment, pictures, tables, page breaks). Sheets are paginated down then across. Slides are one page each at slide size. |
| `OfficeDispatch.kt` | `OfficeRoutes` (route, `isOffice`, `routeFor`, `open`, picker MIME types) + `rememberDocumentOpener(nav)`. |
| `res/values/strings_office.xml`, `res/values-en/strings_office.xml` | 128 `of_` strings (Arabic + English). |

## Hooks for the lead (I did not edit these files)

### 1. Route — `ui/Navigation.kt` (inside `NavHost { … }`)
```kotlin
composable(
    com.smartdocs.editor.ui.office.OfficeRoutes.OFFICE,                    // "office/{uri}"
    arguments = listOf(navArgument("uri") { type = NavType.StringType }),
) { e -> com.smartdocs.editor.ui.office.OfficeScreen(nav, Uri.decode(e.arguments?.getString("uri") ?: "")) }
```

### 2. One central dispatch (covers Home recents, Files tab, MainActivity intents, pickers, ResultSheet, reader tabs, session restore)
In the existing `Routes.READER` composable of `Navigation.kt`, replace the body with:
```kotlin
val u = Uri.decode(e.arguments?.getString("uri") ?: "")
val office = androidx.compose.runtime.remember(u) { com.smartdocs.editor.ui.office.OfficeRoutes.isOffice(u) }
if (office) com.smartdocs.editor.ui.office.OfficeScreen(nav, u)
else ReaderScreen(nav, uri = u, startPage = e.arguments?.getInt("page") ?: -1)
```
`isOffice` checks the extension. When the extension says nothing (for example `x.docx.pdf`, see bug 4), it reads the 4-byte header and the zip entries. With this, `MainActivity.handleIntent` (`incoming → Routes.reader(...)`), `HomeScreen.openDoc`, `FilesScreen.openFile`, etc. need **no change**. If you prefer explicit routing at call sites instead, use `OfficeRoutes.open(nav, uriString, page)` or `nav.navigate(OfficeRoutes.routeFor(uriString, page))` in place of `nav.navigate(Routes.reader(...))`.

### 3. File pickers
- To open documents from Home / Files / Cloud: replace `rememberPdfPicker { … Routes.reader … }` with
  `val pick = com.smartdocs.editor.ui.office.rememberDocumentOpener(nav)`. It launches `OpenDocument` with `OfficeRoutes.ALL_DOCUMENT_MIMES`, keeps read+write permission, copies with `localFile` and routes to the right screen.
- MIME types to add to any "open document" picker (`OfficeRoutes.PICKER_MIMES`):
  `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `application/vnd.openxmlformats-officedocument.presentationml.presentation`, `application/vnd.ms-word.document.macroEnabled.12`, `application/vnd.ms-excel.sheet.macroEnabled.12`, `application/vnd.ms-powerpoint.presentation.macroEnabled.12`, `application/vnd.openxmlformats-officedocument.presentationml.slideshow`, `application/msword`, `application/vnd.ms-excel`, `application/vnd.ms-powerpoint`, `text/plain`, `text/csv`, `text/markdown`.
- Keep `rememberPdfPicker` PDF-only for the PDF tools.
- Optional Tools-tab entry: id `office_open` → `val open = rememberDocumentOpener(nav); open()`.

### 4. Bugs outside this module that affect Office files (please fix)
1. **`DocumentRepository.localFile` names every imported copy `*.pdf`**. It calls `uniqueFile(docsDir, name.removeSuffix(".pdf"))`, whose `ext` defaults to `"pdf"`, so `report.docx` becomes `report.docx.pdf`. OfficeScreen copes (content sniffing; share/open-with use a temp copy with the right name), but recents and the Files list show the wrong name. Fix:
   ```kotlin
   val name = displayName(uri) ?: "document.pdf"
   val ext = name.substringAfterLast('.', "pdf").lowercase().takeIf { it.length in 2..5 } ?: "pdf"
   val target = known?.takeIf { it.parentFile?.exists() == true } ?: uniqueFile(docsDir, name.substringBeforeLast('.'), ext)
   ```
2. **`WriteBackUi.rememberWriteBack`**: the re-pick fallback uses `regrant.launch(arrayOf("application/pdf"))` and `CreateDocument("application/pdf")`, so an Office file whose write permission was lost cannot be re-picked. Use `arrayOf("*/*")` (or `mimeFor(f.extension)`) for both.
3. **`AndroidManifest.xml`**: the VIEW filter accepts only `application/pdf`. To receive Office files from other apps, add `<data android:mimeType="…"/>` lines for the MIME types above to the VIEW filter (and to SEND if wanted). `handleIntent` already works through the reader-route dispatch.
4. **Files tab**: `DocumentRepository.listFolder` keeps only `pdf/jpg/jpeg/png/txt`; add `"docx","xlsx","pptx","doc","xls","ppt","csv","md"`. `FilesScreen.scanTree` keeps only PDFs; add `|| OfficeRoutes.isOfficeMime(c.type) || isOfficeFile(c.name.orEmpty())`. `scanDevicePdfs` (MediaStore) could add the Office MIME types the same way.
5. Home thumbnails are rendered through PDFium, which cannot read Office files. Show a type icon for `isOfficeFile(name)` (or use `SlideView` / first-page HTML later).

## Extension points (later Office-parity rounds: Draw, Design, Review, Transitions, Animations, Data…)
- **New ribbon tab without touching the editors**:
  ```kotlin
  OfficeRibbonRegistry.register(OfficeKind.DOCX, RibbonTab("review", R.string.xx_tab_review, order = 60) { s ->
      val w = s as WordState
      RibbonButton(Icons.Default.Comment, R.string.xx_comment) { w.onSelected { i -> w.doc.mutate { items -> /* … */ } } }
  })
  ```
  Built-in orders: Home 10, Insert 20, Formulas 30 (xlsx), Layout 50 (docx), Slide Show 70 (pptx), View 90. Use values in between (Draw 25, Design 40, Transitions 45, Animations 55, Review 60, Data 35…).
- **Commands by id** (AI agent, shortcuts): `OfficeCommandRegistry.run("word.bold", state)`.
  - Built-in Word ids: `word.bold/italic/underline/strike/bullets/numbering/align.start|center|end|justify/paragraph.insert|delete/pagebreak/heading.-1|0|1|2|3`.
  - Built-in Excel ids: `sheet.autosum/average/count/min/max/row.insert|delete/col.insert|delete/clear/merge/unmerge`.
  - Built-in PowerPoint ids: `slide.new/duplicate/delete/moveUp/moveDown/present/presentCurrent`.
  - Register new ones with `OfficeCommandRegistry.register(id) { state -> … }`.
- **Document models are open for growth**:
  - `WordDoc.mutate { items -> }` gives one undoable step on raw body XML items. Sections are the `sectPr` items. Comments and track changes are `w:ins/w:del/w:commentRangeStart` inside items. `WordXml` holds the templates, and `XmlEdit.setChild(..., WordXml.PPR_ORDER/RPR_ORDER)` keeps schema order.
  - `SheetDoc.apply(sheet, diffs)` for cell batches and `setStyle(...)` for style indexes. Number formats/fonts need a styles.xml writer, which does not exist yet. `FormulaFunctions.call` is where new functions go.
  - `SlideDoc.mutateSlide(i) { xml -> }` for transitions (`p:transition`), animations (`p:timing`), shapes and charts. `PptxReader.readSlideXml` re-renders.
- `OfficeEditorState` is the contract shared with chrome and tabs: `tick`/`bump`/`edit{}`, `editMode`, search, `speechPages`, `exportPdfBlocking`, `saveBlocking`, `commitPending`.

## Limitations / TODO
- **Word**:
  - Formatting applies to the whole selected paragraph; there is no character-range selection yet.
  - Editing the text of a paragraph with hyperlinks, fields or content controls flattens them to one run. A hint is shown, and images in it are kept.
  - Headers, footers, footnotes, comments and text boxes are kept but not editable.
  - Nested tables are read-only, and tables have no column insert.
  - Reading mode is HTML reflow, not exact Word pagination.
- **Excel**:
  - There is no cell formatting UI (bold/fill/number format), because writing styles.xml is not implemented yet. Existing formats are kept and shown.
  - Inserting or deleting rows/columns does not shift conditional formats, data validations, hyperlinks, defined names or charts.
  - Column styles in `<cols>` are dropped when columns are changed.
  - About 45 functions are implemented; other functions keep Excel's cached value until their inputs change.
  - Edited strings are written as inline strings; Excel converts them on save.
  - PDF export is capped at 20 000 rows per sheet.
- **PowerPoint**:
  - Only text edits; there is no move/resize of shapes and no picture insert yet.
  - Theme colours, fonts, gradients, layout/master artwork and charts/SmartArt are not rendered: slides show explicit colours and solid fills only, and text defaults to black, or white on dark backgrounds.
  - Line breaks inside a paragraph (`a:br`) become separate paragraphs after an edit.
  - Speaker notes are not shown.
- **General**:
  - Password-protected (encrypted OLE) OOXML files show the "could not open" screen.
  - Very large files are fully loaded into memory.
  - There is no autosave on pause; the app asks on leave.
