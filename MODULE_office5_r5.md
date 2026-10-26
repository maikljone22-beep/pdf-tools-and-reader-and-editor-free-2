# MODULE office5 (round 5): Office editors (Word / Excel / PowerPoint)

This round adds the editor foundation and the essential features for opening, viewing and editing `.docx`, `.xlsx`/`.csv` and `.pptx` inside the app, and saving into the same file. The engine is written by hand on the existing OOXML approach: no Apache POI and **no new Gradle dependency**.

How it was checked:
- No Gradle run.
- `core/office` + `core/office5` + `ui/office` type-check with **0 errors**. They were compiled with kotlinc 2.3.21 and the Compose plugin against `app/build/.../classes.jar` and the dependency jars, with an R stub for the `o5_` strings and a stub for `core/tools5/ToPdf` (newer than classes.jar). The script is `scratchpad/office5/kui.sh`.
- 40 JUnit tests pass (`scratchpad/office5/t.sh`).
- Round trip on real files made by python-docx, openpyxl and python-pptx (scratchpad `office5/rt/`). Edits were made with this engine and saved. The same libraries re-open the saved files with the expected text, styles, numbering, tables, pictures, formulas, cached values, merges, freeze panes, column widths, and slide order and notes.

## Files

### New: `core/office5` (pure Kotlin, JVM-testable, no `org.xmlpull`)
| File | What |
|---|---|
| `XDom.kt` | XML DOM built for round trips. Text and attribute values stay escaped as in the source, and comments/PI/CDATA stay verbatim, so `parse(x).toXml() == x` for normal OOXML. It also has schema-ordered child insertion (`setOrdered`) and namespace helpers. |
| `OPackage.kt` | The ZIP package in memory. Parts that were not opened for editing are written back **byte-for-byte** in their original order. New parts are appended and removed parts are dropped. It also has rels / content-type helpers, image size/type sniffing, an atomic `saveTo`, and cheap `snapshot`/`restore`. |
| `SnapshotUndo.kt` | Multi-level undo/redo over package snapshots (40 levels, memory cap). Used by Word and PowerPoint. |
| `WordRuns.kt` | Character-level paragraph editing: text over runs (including runs inside hyperlinks, ins, smartTag, fldSimple and sdt), **run splitting**, **merging of equal runs**, diff-based `setText` (only the changed middle is touched, so formatting survives), insert/delete (field chars are kept), `formatRange`, and paragraph split/join. rPr/pPr children are written in schema order, so unknown run properties survive. |
| `WordFormat.kt` | Run properties (b/i/u/strike/size/colour/highlight/font/vertAlign/rtl, with the cs variants), paragraph properties (jc, bidi, pStyle, numPr, indent), the `WordStyles` model (docDefaults + style chains) and `WordNumbering` (list markers). |
| `WordParts.kt` | Templates: heading/title/quote styles (created when missing; Arabic-localised style names are found by `w:name`), bullet/decimal list definitions in numbering.xml (part, rel and content type created when missing), tables, inline pictures, and a blank docx. |
| `WordDoc.kt` | The editable .docx: blocks, `change{}`/undo/redo, new paragraphs, delete/move, page break, styles, lists, pictures (insert/resize/read), page orientation and margins, find/replace, statistics. |
| `WordTables.kt` | Table operations: set cell text (lines become paragraphs), insert/delete row, insert/delete column (gridSpan aware), cell shading. |
| `WordView.kt` | Render model for the UI: blocks with character spans carrying the effective formatting, markers, pictures and tables. |
| `A1.kt` | `CellRef`/`CRange`, A1 parsing and formatting, column letters, and insert/delete shift math. |
| `FxValue.kt` | Values (`FV`), Excel coercions/comparison/rounding, Arabic-Indic digit input, and 1900 serial dates (with the 1900-02-29 quirk). |
| `FxParser.kt` | Formula lexer/parser: precedence like Excel (so -2^2 = 4), `%`, `&`, comparisons, `$A$1`, `Sheet!A1`, `'My sheet'!A1:B2`, `A:C`, `1:3`, `;` as separator, error literals. `FxPrinter` prints formulas and rewrites references for copy/fill (`translate`), insert/delete (`shift`), sheet rename, and sheet delete (→ #REF!). |
| `FxEval.kt` | Evaluator with implicit intersection, and `FxArgs`: lazy, Excel-coerced arguments with aggregate rules. Unknown functions raise `FxUnsupported`, and the cell then keeps Excel's cached value. |
| `FxFunctions.kt` | **Registry**: one `reg(name, category, min, max, volatile) { args -> }` call adds a function. 54 functions: SUM, PRODUCT, ABS, ROUND/ROUNDUP/ROUNDDOWN, INT, MOD, POWER, SQRT, PI, SIGN, SUMIF, AVERAGE, MIN, MAX, COUNT, COUNTA, COUNTBLANK, COUNTIF, AVERAGEIF, IF, IFERROR, IFNA, AND, OR, NOT, TRUE, FALSE, CONCAT, CONCATENATE, LEN, LEFT, RIGHT, MID, UPPER, LOWER, TRIM, TEXT, VALUE, TODAY, NOW, DATE, YEAR, MONTH, DAY, VLOOKUP, HLOOKUP, CHOOSE, ISBLANK, ISNUMBER, ISTEXT, ISERROR, NA. It also has COUNTIF-style criteria with wildcards. |
| `NumFmt5.kt` | Number-format engine: General; up to 4 sections; `[Red]`; `[>=n]` conditions; literals and `\`/`_`/`*`; `0 # ?`; thousands separators and trailing-comma scaling; %; E+; `@`; `[$SAR]` currency tags; date/time tokens including `[h]` and AM/PM; built-in format ids. |
| `SheetBook.kt` | Workbook model (`Cell5` is immutable; `Sheet5` holds widths, heights, merges, freeze, rtl). It keeps the **dependency graph**: single-cell and range precedents with reverse indexes. **Incremental recalculation** covers the transitive dependents in topological order (Kahn), so long chains do not recurse. Cycles give `#CIRC!`, volatile functions recalculate, and input is parsed (12% → 0% format, dates, `'text`). Whole-book undo snapshots (`BookUndo`). |
| `Workbook5.kt` | Edit operations: input, clear, format (by cell and by position, for borders), insert/delete rows and columns (formulas in **all** sheets rewritten, merges/heights/widths shifted), merge/unmerge, widths/heights/freeze, sort, autofill, copy/paste (formulas re-based), TSV paste, sheet add/rename/delete/move with name checks, status-bar aggregates, autofit width. |
| `FillSeries.kt` | AutoFill series: linear numbers, dates, "Item 1" style text, English and Arabic day/month names, Q1..Q4; otherwise the seeds repeat. |
| `XStyles.kt` | styles.xml as a DOM: reads `CellFormat` (font, fill, borders, alignment, numFmt) and appends new fonts/fills/borders/numFmts (ids from 164)/xfs while keeping everything else. |
| `XlsxIO5.kt` | xlsx load: shared strings, rich text, shared formulas expanded, cached values, cols, row heights, merges, frozen pane, RTL. Save: regenerates sheetData/cols/mergeCells/pane/dimension **of changed sheets only**, appends to sharedStrings (unchanged rich-text strings keep their index), rewrites the sheet list, moves `localSheetId` of defined names, clamps activeTab, drops calcChain, sets `fullCalcOnLoad`, and writes formula + cached value. Also a blank workbook. |
| `CsvIO.kt` | CSV/TSV: delimiter sniffing, RFC 4180 quotes, BOM and Windows-1256 fallback, save (formulas saved as results), convert to xlsx. |
| `PptDoc.kt` | Editable .pptx. Slides: reorder (sldIdLst), delete (rels, part, override and **notes** removed), duplicate (notes link dropped), new slide from the current layout's title/body placeholders. Shapes: add text box / preset shape / line / picture, move/resize (xfrm), delete, z-order, text replace (pPr and first-run rPr kept per paragraph), run formatting, alignment/RTL, fill/outline, text colour. Also slide background and transitions (fade/push/wipe). |
| `PptView.kt` | Slide render model: layout/master placeholder inheritance, master and layout artwork (`showMasterSp`), group transforms, preset geometry, theme colours with lumMod/lumOff/tint/shade, style refs, solid/gradient (first stop)/picture backgrounds, text runs, normAutofit, insets/anchor, pictures, basic tables. |
| `PlainImport.kt` | Read-only .odt (content.xml) and .rtf (control words, `\'hh`, `\uN`, ansicpg) → paragraphs. |

### Existing `core/office`: no change
This round did not change `core/office`. Its public API is untouched (`OoxmlReader`, `OfficeHtml`, `DocxEditor`, `XlsxWriter`…). Two internal helpers are reused: `NumberText` and `hasRtlChars`.

### New: `ui/office`
| File | What |
|---|---|
| `OfficeScreen.kt` | **`@Composable fun OfficeScreen(nav, uri: String)`**. It imports `content://` URIs (keeping the real extension) and repairs `*.docx.pdf` names. It detects the kind by name and then by content, and loads off the main thread. Shared top bar: undo, redo, **save into the same file** (+ `rememberWriteBack` + `SaveHooks.onSaved` + recents). The menu has **Save as** (SAF CreateDocument), **Export PDF** (through `core/tools5/ToPdf.convert`, then `ResultSheet`), **Share**, **Open with**, *Save as xlsx* for CSV, and *Edit as Word* for ODT/RTF. There is an unsaved-changes dialog on back, and a legacy/failed screen with Open-with/Share. `OfficeActions` holds these shared actions. |
| `OfficeRoutes.kt` | `OfficeRoutes.ROUTE = "office5/{uri}"`, `route()`, `open()`, `isOffice(uri)`, `kindOf(file)`, `MIMES` (for pickers and intent filters), `mimeOf`, `importUri`, `repairName`, `createBlank(app, "docx"/"xlsx", name)`, and **`rememberOfficeOpener(nav)`** (picker for every Office/text type, which opens the editor). |
| `OfficeCommon.kt` | `OfficeHost` contract; ribbon (`RibbonTabs`, `RibbonRow`, `RibbonButton`, `RibbonChip`, `RibbonColor`, `RibbonDivider`); colour/size/text/font-size dialogs; unsaved dialog; find bar. |
| `OfficeStart.kt` | `OfficeStartScreen(nav)`: Open Office document / New Word document / New Excel workbook (for a Tools entry). |
| `WordState.kt`, `WordEditor.kt`, `WordBlocks.kt`, `WordRibbon.kt` | **Word**. Page-like document column, editable in place: tap a paragraph to type. Typing is diff-applied to the DOM with live formatting; Enter splits the paragraph; Backspace at the start joins. Pictures are inline, tables have editable cells, and there are page-break markers. Ribbon tabs: الصفحة الرئيسية، إدراج، تخطيط، مراجعة، عرض, plus contextual جدول and صورة tabs. The status bar shows the word count. On tablets there is an outline (headings) side panel. Reading mode and zoom. |
| `SheetState.kt`, `SheetGrid.kt`, `SheetEditor.kt`, `SheetRibbon.kt` | **Excel**. Canvas grid that draws only the visible cells, with pinned letter/number headers and frozen rows/cols. RTL sheets are mirrored. Gestures: pan with fling, pinch zoom, tap to select, tap a header for a row/column, long-press and drag for a range, a **fill handle** that you drag to AutoFill, double-tap to edit, and tapping the selected cell opens the context menu. Formula bar: name box with go-to, fx function list, tapping cells inserts references while typing `=`, function-name suggestions, and missing brackets/quotes are closed. Sheet tabs (+, rename, move, delete). Selection SUM/AVG/COUNT. On tablets there is a cell side panel. Ribbon tabs: Home, Insert, Formulas, Data, View. |
| `SlideState.kt`, `SlideCanvas.kt`, `SlideEditor.kt`, `SlideRibbon.kt` | **PowerPoint**. Compose rendering of slides. Thumbnails sit in a bottom strip on phones and a side column on tablets; long-press a thumbnail to duplicate, move or delete. On the canvas: tap selects, double-tap edits **text in place**, drag moves, and the corner handles resize. Ribbon tabs: Home, Insert, Design, Transitions, Slide Show, View, plus a contextual Shape tab. **Slide show**: a full-screen dialog with system bars hidden; tap or swipe left goes to the next slide and swipe right to the previous one. The transition is a fade (push/wipe slide in). |
| `OfficeText.kt` | Plain-text editor (keeps encoding and BOM; undo/redo; find) and the read-only ODT/RTF view. |

### Resources
- `res/values/strings_office5.xml` and `res/values-en/strings_office5.xml`: 229 `o5_` strings, Arabic and English. Backslashes and apostrophes are escaped.

### Tests (`app/src/test/java/com/smartdocs/editor/core/office5/`)
| File | Covers |
|---|---|
| `WordRunsTest` | Exact DOM round trip; text over hyperlinks and tabs; run splitting keeps unknown rPr; format split then merge back; diff `setText`; deleting across a field; empty-paragraph mark props; breaks; paragraph split and join; pPr schema order; **untouched package is byte-for-byte**; edits add numbering/styles parts plus rels and content types; undo/redo; find/replace/stats; table rows and columns; picture insert. |
| `FormulaTest` | Precedence, references/ranges/whole columns, dependency recalculation, cross-sheet refs plus rename/delete, all required functions, error values, circular references, a 3000-cell chain, printer round trip, unsupported functions keeping the cached value. |
| `SheetModelTest` | Column names, shift math, translate/shift formulas, insert/delete rows and columns, sort, fill series, undo/redo, **number formatting**, input parsing, xlsx save → reload (styles, widths, freeze, merges, RTL, cached values, sheet delete/rename), shared formulas, CSV. |
| `PptDocTest` | **Slide reorder updates sldIdLst**; delete removes part, rels, notes and override; duplicate and new slide (unique ids, layout positions); text edits keep run formatting; theme colours; text box/shape/fill/transition. |

## Hooks for the lead (I did not edit these files)

### 1. Route: `ui/Navigation.kt`, inside `NavHost { … }` (for example after `composable(Routes.TOOL, …) { … }`)
```kotlin
composable(
    com.smartdocs.editor.ui.office.OfficeRoutes.ROUTE,          // "office5/{uri}"
    arguments = listOf(navArgument("uri") { type = NavType.StringType }),
) { e -> com.smartdocs.editor.ui.office.OfficeScreen(nav, Uri.decode(e.arguments?.getString("uri") ?: "")) }
```

### 2. One central dispatch: `ui/Navigation.kt`, the `Routes.READER` composable
Replace its body `ReaderScreen(nav, uri = Uri.decode(e.arguments?.getString("uri") ?: ""), startPage = e.arguments?.getInt("page") ?: -1)` with:
```kotlin
val u = Uri.decode(e.arguments?.getString("uri") ?: "")
val office = androidx.compose.runtime.remember(u) { com.smartdocs.editor.ui.office.OfficeRoutes.isOffice(u) }
if (office) com.smartdocs.editor.ui.office.OfficeScreen(nav, u)
else ReaderScreen(nav, uri = u, startPage = e.arguments?.getInt("page") ?: -1)
```
With this, every existing `nav.navigate(Routes.reader(...))` opens Office files in the editor with no other change: MainActivity intents (`incoming → Routes.reader`), Home recents (`openDoc`), the Files tab (`openFile`, `FilesComponents` device list), pickers, `ResultSheet`, and session restore.
- `isOffice` checks the extension. For `*.pdf` names it sniffs the content, which covers copies named `x.docx.pdf` by the current `localFile`.
- `OfficeScreen` accepts `file://` and `content://` URIs.

### 3. `AndroidManifest.xml`: receive Office files from other apps
In the VIEW `<intent-filter>` that has `<data android:mimeType="application/pdf" />`, add:
```xml
<data android:mimeType="application/vnd.openxmlformats-officedocument.wordprocessingml.document" />
<data android:mimeType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" />
<data android:mimeType="application/vnd.openxmlformats-officedocument.presentationml.presentation" />
<data android:mimeType="application/vnd.ms-word.document.macroEnabled.12" />
<data android:mimeType="application/vnd.ms-excel.sheet.macroEnabled.12" />
<data android:mimeType="application/vnd.ms-powerpoint.presentation.macroEnabled.12" />
<data android:mimeType="application/vnd.oasis.opendocument.text" />
<data android:mimeType="application/rtf" />
<data android:mimeType="text/csv" />
<data android:mimeType="text/comma-separated-values" />
<data android:mimeType="application/msword" />
<data android:mimeType="application/vnd.ms-excel" />
<data android:mimeType="application/vnd.ms-powerpoint" />
```
- `text/plain` is optional (the app would then show up for every .txt).
- `MainActivity.handleIntent` needs no change.
- If the lead wants a direct route, `incoming` could navigate with `OfficeRoutes.route(...)` when `OfficeRoutes.isOffice(...)`, but hook 2 already covers it.

### 4. `core/files/DocumentRepository.kt`, `localFile`: keep the real extension (bug: every import becomes `*.pdf`)
Replace
```kotlin
val name = displayName(uri) ?: "document.pdf"
val target = known?.takeIf { it.parentFile?.exists() == true } ?: uniqueFile(docsDir, name.removeSuffix(".pdf").removeSuffix(".PDF"))
```
with
```kotlin
val name = displayName(uri) ?: "document.pdf"
val ext = name.substringAfterLast('.', "pdf").lowercase().takeIf { it.length in 2..5 } ?: "pdf"
val target = known?.takeIf { it.parentFile?.exists() == true } ?: uniqueFile(docsDir, name.substringBeforeLast('.'), ext)
```
The Office screen works without this fix: it re-imports content URIs itself and renames `*.docx.pdf` working copies while keeping the origin link. But recents and the Files list show wrong names until the fix is in.

### 5. Files tab and folder listing
- `DocumentRepository.listFolder`: add `"docx","xlsx","pptx","csv","odt","rtf","doc","xls","ppt","md"` to `setOf("pdf", "jpg", "jpeg", "png", "txt")`.
- `ui/files/FilesScreen.kt` `scanTree`: change
  `else if (c.type == "application/pdf" || c.name.orEmpty().endsWith(".pdf", true)) {`
  to
  `else if (c.type == "application/pdf" || c.name.orEmpty().endsWith(".pdf", true) || com.smartdocs.editor.ui.office.OfficeRoutes.kindOfName(c.name.orEmpty()) != com.smartdocs.editor.ui.office.OfficeDocKind.NONE) {`
  (and use `c.name ?: "document"` for the name).
- Optional: `scanDevicePdfs` could also query the Office MIME types (`OfficeRoutes.MIMES`).

### 6. "Open" pickers (Home / Files)
To open any document type from the Home/Files "open" buttons, use
`val pickOffice = com.smartdocs.editor.ui.office.rememberOfficeOpener(nav)` and call `pickOffice()`. Alternatively, add `*OfficeRoutes.MIMES` to the array that `rememberPdfPicker` launches, but only for the general "open" buttons; the PDF tools stay PDF-only.

### 7. `ui/common/WriteBackUi.kt`: write-back re-pick for non-PDF files
In `rememberWriteBack`, `regrant.launch(arrayOf("application/pdf"))` and `CreateDocument("application/pdf")` mean an Office file whose write permission was lost cannot be re-picked. Use `arrayOf("*/*")` and `ActivityResultContracts.CreateDocument("*/*")`, or the file's MIME type.

### 8. Optional Tools-tab entry
- In `ToolScreens.kt` `when (id)`: `"office" -> com.smartdocs.editor.ui.office.OfficeStartScreen(nav)`.
- In `ToolsScreen.kt` add a card with `R.string.o5_tool_open` / `R.string.o5_tool_open_sub` that navigates to `Routes.tool("office")`.
- Strings for New document / New workbook are included (`o5_tool_new_doc`, `o5_tool_new_sheet`).

### 9. Home thumbnails
`Thumbnails.render` returns null for non-PDF files, which is fine (placeholder). An optional improvement is a type icon for `OfficeRoutes.kindOfName(name)`.

### 10. Dependency on `core/tools5`
`OfficeActions.exportPdf` calls `com.smartdocs.editor.core.tools5.ToPdf.convert(ctx, src, out): File` (suspend), which exists in the current source. If tools5 renames it, update that one line in `ui/office/OfficeScreen.kt`.

## Feature status

### Word
| Feature | Status |
|---|---|
| Open .docx, edit in place, save into the same file / save as | DONE |
| Ribbon tabs (الصفحة الرئيسية، إدراج، تخطيط، مراجعة، عرض) + contextual Table/Picture | DONE |
| Bold/italic/underline/strike, font size (steps and list), font colour, highlight, clear formatting | DONE. Applies to the selection; a collapsed cursor applies to the word under it; in an empty paragraph it applies to the paragraph mark. |
| Alignment (start/center/end/justify), RTL/LTR paragraphs, whole-document direction | DONE |
| Bullets / numbering (numbering.xml created), list level / indent | DONE |
| Headings / styles (Normal, Title, H1–H3, Quote; existing localised styles reused) | DONE |
| Tables: insert, edit cells, add/remove rows and columns, shading, delete | DONE. Nested tables are shown read-only and vertically merged cells are not editable as a merge. |
| Pictures: insert (scaled to the page), resize ±, align, delete | DONE. Inline pictures only; there is no free drag-resize handle. |
| Page breaks | DONE |
| Find & replace (keeps the formatting of the first matched character) | DONE |
| Undo/redo (one typing session = one step) | DONE |
| Word count (status bar + dialog) | DONE |
| Round-trip safety: untouched parts byte-for-byte; edited paragraphs keep unknown run properties | DONE (tested) |
| Page orientation, margins presets | DONE |
| .doc (binary) → read-only message + open with / share | DONE |
| .odt / .rtf read | DONE. Read-only view plus "Edit as Word" (converts to a docx copy). |
| .txt / .md / .log edit | DONE |
| True pagination (page boxes as in Word), headers/footers, footnotes, comments, track changes, text boxes | NOT DONE. These are kept untouched in the file. |
| Font family picker | NOT DONE. Fonts are kept and shown as serif/sans/mono mapping. |

### Excel
| Feature | Status |
|---|---|
| Grid editor, pinned headers, frozen rows/cols (freeze top row / first column / at selection / unfreeze) | DONE |
| Multiple sheets: tabs, add/rename/delete/move, name validation | DONE |
| Cell editing with formula bar, reference insertion by tapping, function suggestions | DONE |
| Formula engine: arithmetic, references, ranges, the required functions + extras (54), error values, circular refs, dependency graph + incremental recalculation | DONE. Unknown functions keep the cached value and are flagged in the side panel. |
| Number formats (general, number, percent, currency SAR/YER/$, date, long date, time, scientific, text, ± decimals) | DONE |
| Bold/italic/underline/strike/size/colour/fill/borders (all, outside, thick, bottom, none)/alignment/wrap/merge | DONE |
| Column width / row height / autofit width | DONE |
| Sort a range (header detection) | DONE |
| AutoFill series (fill handle drag; Fill down/right) | DONE |
| Insert/delete rows/cols (formulas in all sheets rewritten) | DONE. Conditional formats, data validations, hyperlinks, defined names and charts are not shifted. |
| Undo/redo | DONE |
| Save values + formulas + cached results; untouched sheets byte-for-byte | DONE |
| CSV open/edit/save (+ save as xlsx) | DONE |
| Copy/cut/paste (internal with formula re-basing; system clipboard TSV) | DONE |
| RTL sheets, zoom, gridlines toggle, show formulas | DONE |
| Rich text inside cells, conditional formatting, charts, filters, pivot tables, data validation, comments | NOT DONE (later Excel rounds). Existing ones are kept in the file. |

### PowerPoint
| Feature | Status |
|---|---|
| Thumbnail strip + slide canvas rendering (text, preset shapes, lines, pictures, basic tables, backgrounds, theme colours, master/layout artwork) | DONE. Gradients use their first stop, and charts, SmartArt and custom geometry draw as rectangles or nothing. |
| Edit text in place | DONE. Run formatting (B/I/U/size/colour) applies to the whole shape; paragraph pPr and first-run rPr are kept per line. |
| Move/resize shapes (drag, corner handles) | DONE |
| Add/delete/duplicate/reorder slides | DONE |
| Add text box / shapes (rect, rounded, ellipse, triangle, arrow, line) / picture; fill/outline colours; z-order; delete | DONE |
| Slide show (full screen, tap/swipe, fade; push/wipe slide) | DONE |
| Transitions per slide / all slides (fade, push, wipe) | DONE |
| Slide background colour | DONE |
| Export to PDF | DONE, through the existing `ToPdf` (HTML pipeline from `core/office`). |
| Speaker notes view/edit, animations, character-range formatting inside a text box, rotation handle, new blank .pptx | NOT DONE |

### Common
| Feature | Status |
|---|---|
| Open from Files/Home/other apps/picker | DONE through hooks 1–6 (the lead applies them). |
| Save into the same file (write-back to the origin) + Save as | DONE |
| Export to PDF, Share, Open with | DONE |
| Recent files entry (`touchRecent` on open and save) | DONE |
| Tablet side panels (Word outline, Excel cell panel, PowerPoint thumbnail column) | DONE |
| New blank Word document / Excel workbook (`OfficeStartScreen`, `OfficeRoutes.createBlank`) | DONE |

## Known limitations
- The whole package is held in memory, which suits normal documents. Very large workbooks (100k+ cells) work, but every structural edit runs a full recalculation and every undo step copies the cell maps.
- Word typing applies each keystroke to the DOM. Only the edited paragraph's view is rebuilt, but structural edits (split, join, style) rebuild the whole document view.
- Word: there is no character-level selection across paragraphs (formatting works within one paragraph or one cell). Enter in an empty list item does not end the list. Hyperlink and field text can be edited, but new text typed at their edge goes into the neighbouring run.
- Excel:
  - Edited strings go into sharedStrings (appended). Old unused strings are not pruned.
  - Formula text is re-printed when a structural change touches it, so spacing in such formulas is normalised.
  - Array formulas are treated as normal formulas.
  - Whole-column formats in `<cols style>` are kept, but they are not applied to new cells.
- PowerPoint:
  - Placeholder text style inheritance (master text styles, bullets from lstStyle) is approximated: sizes come from the layout/master `defRPr` when present, otherwise 40/24/18 pt.
  - Text is drawn with the system font.
- Password-protected (encrypted) Office files show the "could not open" screen.
- PDF export of edited documents goes through the existing HTML renderers in `core/office`, so Word pagination in the PDF is reflowed HTML.
