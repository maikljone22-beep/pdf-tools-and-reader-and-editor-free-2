# MODULE edit5 (round 5): rebuild of the edit4 spec

Owner: edit5. My packages are `core/edit5/*` and `ui/edit5/*`. String files: `res/values/strings_edit5.xml` and `res/values-en/strings_edit5.xml` (346 keys, prefix `e5_`, the same keys in both files). Tests: `app/src/test/java/com/smartdocs/editor/core/edit5/Edit5LogicTest.kt`.

What I did not touch:
- No Gradle dependency was added.
- No shared hot file was edited.
- `PdfOps.kt` is unchanged. The new PdfBox operations are all in `core/edit5`.

## Verification (no gradle was run)
- **Pure logic:** compiled with kotlinc 2.3.21 from the gradle cache and run on the JVM with JUnit 4.13.2.
  - Result: **16 tests, all OK**.
  - Covered: Bates format and numbering across files, roman/letter page labels, label ranges and lookup (Arabic digits too), booklet order (LTR, and RTL with blanks), N-up order (RTL, column-first), poster tiles, split parts, interleave, white-margin detection, Hijri (tabular epoch and round trip, Umm al-Qura 1446-01-01 = 2024-07-07), tokens, page ranges, link detection and mailto.
- **Core and UI type check:** compiled with kotlinc 2.3.21 plus the Compose compiler plugin.
  - Classpath: android-35 `android.jar`, the PdfBox-Android / PDFium / Compose / material3 / navigation jars from `D:\gradle-home`, the rest of the app compiled from source, and a generated R stub that includes my strings.
  - Result: **0 errors** in `core/edit5` and `ui/edit5`.
  - The whole app tree also compiled with 0 errors on my machine. Other agents edit files at the same time, so this may change.
- Nothing was run on a device.

## Files
**core/edit5** (PdfBox, all new)
| File | Content |
|---|---|
| `E5Format.kt` | Pure logic: `Digits`, `Bates`/`BatesSpec`, `PageLabelFormat`/`LabelRange`/`LabelStyle`, `Tokens`/`TokenContext`, `PageSet`/`PageSubset`. |
| `HijriDate.kt` | Pure logic. Umm al-Qura through `java.time.chrono.HijrahDate` (API 26), with a tabular fallback; Arabic and English month names; pattern formatting. |
| `ImposeOrder.kt` | Pure logic: booklet, N-up, poster tiles, split parts, interleave order; `MarginDetect` (content bounds, ink ratio). |
| `LinkDetect.kt` | Pure logic: URL / e-mail / phone detection, URL normalisation, building and parsing mailto. |
| `E5Io.kt` | Load/save. `Marked`: tagged artifact blocks (strip, detect) and settings stored in catalog `/PieceInfo /SmartDocsE5`. |
| `E5Text.kt` | `E5Font` (bundled Naskh, system Naskh/Kufi/Sans Arabic, Times/Courier/Roboto for Latin). `E5Line`: shaped mixed Arabic/Latin line (ArabicShaper + arabicFont) with /ActualText. |
| `E5Geom.kt` | `PageInfo`: display⇄user mapping (rotation and crop offset), rect and quad helpers. |
| `LinkOps.kt` | Real /Link annotations. |
| `HeaderFooterOps.kt` | Header & footer, plus Bates across files. |
| `WatermarkOps.kt` | Watermark, background and security watermark. |
| `PageBoxOps.kt` | Crop, auto crop, reset, resize, page labels read/write. |
| `ImposeOps.kt` | N-up, booklet, poster as vector form XObjects. |
| `PageOps5.kt` | Clone, arrange, repoint destinations, reverse, duplicate, copy, interleave, replace, split page, blank/marker detection, split at separators. |
| `PageTemplates.kt` | /Names /Pages and /Templates. |
| `BlankPages.kt` | Nine paper styles. |
| `OutlineStore.kt` | Outline model that keeps style and zoom. |
| `OutlineTools.kt` | Heading detection, clickable TOC page. |
| `DocPropsOps.kt` | Info, custom keys, initial view, viewer preferences, XMP sync. |

**ui/edit5** (Compose, all new)
| File | Content |
|---|---|
| `E5Ui.kt` | `E5Doc` working copy with undo, `E5PageView` (render, draw rect, tap), shared widgets. |
| `Edit5Hub.kt` | `EDIT5_TOOLS`, `EDIT5_TOOL_IDS`, `Edit5Screens` dispatcher, `Edit5HubScreen`. |
| `LinkEditor.kt` | `LinkEditorSheet`, shared by the tool and the reader. |
| `LinksScreen.kt` | The links tool screen. |
| `ReaderLinks.kt`, `ReaderExtras.kt` | Reader hooks. |
| Other screens | `HeaderFooterScreen.kt`, `BatesScreen.kt`, `DecorScreen.kt`, `PageLabelsScreen.kt`, `CropResizeScreens.kt`, `ImposeScreen.kt`, `PageOpsScreen.kt`, `TemplatesBlankScreens.kt`, `BookmarksScreen.kt`, `DocPropsScreen.kt`. |

## Hooks for the lead (exact)

### 1. Tool routes: `ui/tools/ToolScreens.kt`
Replace the last branch `else -> ComingSoonScreen(nav, id)` with:
```kotlin
else -> if (id in com.smartdocs.editor.ui.edit5.EDIT5_TOOL_IDS) com.smartdocs.editor.ui.edit5.Edit5Screens(nav, id, uri) else ComingSoonScreen(nav, id)
```
Route ids: `e5_hub`, `e5_links`, `e5_bookmarks`, `e5_header_footer`, `e5_bates`, `e5_watermark`, `e5_background`, `e5_security_wm`, `e5_page_labels`, `e5_crop`, `e5_resize`, `e5_impose`, `e5_page_ops`, `e5_replace_pages`, `e5_split_page`, `e5_smart_split`, `e5_interleave`, `e5_copy_pages`, `e5_templates`, `e5_insert_blank`, `e5_doc_props`.

Optional replacements. These are better than the old screens, because the old ones drop bookmark styles and cannot update or remove a watermark:
```kotlin
"outline" -> com.smartdocs.editor.ui.edit5.BookmarksScreen(nav, uri)
"watermark" -> com.smartdocs.editor.ui.edit5.DecorScreen(nav, uri, com.smartdocs.editor.core.edit5.DecorType.WATERMARK)
```

### 2. Tool cards: `ui/tools/ToolsScreen.kt`
In `allTools`, after the line `ToolDef("print", R.string.tool_print, Icons.Default.Print, 3),`, add the hub card:
```kotlin
ToolDef("e5_hub", R.string.e5_hub_title, Icons.Default.EditNote, 1),
```
Optionally, add one card per tool:
```kotlin
*com.smartdocs.editor.ui.edit5.EDIT5_TOOLS.map { ToolDef(it.id, it.label, it.icon, it.group) }.toTypedArray(),
```
`E5Tool.group` uses the same groups (0 create, 1 organize, 2 sign/protect, 3 read).

### 3. Reader link tool: replaces the note-based fake
- **`ReaderScreen.kt`.** Find:
  ```kotlin
  prompt?.let { p -> TextPromptDialog(state, p) { prompt = null } }
  ```
  Replace it with:
  ```kotlin
  prompt?.let { p -> if (!com.smartdocs.editor.ui.edit5.ReaderLinkPrompt(state, p.tool == ReaderTool.LINK, p.page, p.x, p.y, p.rect) { prompt = null }) TextPromptDialog(state, p) { prompt = null } }
  ```
  - A drawn rectangle opens the real-link editor: URI, page with Fit / FitH / XYZ zoom, other PDF, other file, e-mail, named destination; border style, colour and highlight mode.
  - The editor writes a real `/Link` through `state.applyInPlace`, so it is undoable like the other one-step tools.
  - After this hook, the `ReaderTool.LINK` branch in `ReaderDialogs.TextPromptDialog` is dead code. It can be deleted.
- **`PageCanvas.kt`, `handleTap`** (tap with the LINK tool edits or deletes the link under the finger). In `when (val t = state.tool) {`, add a branch before `ReaderTool.MEASURE -> {`:
  ```kotlin
  ReaderTool.LINK -> onPrompt(TextPrompt(t, page, x, y))
  ```
  A prompt with `rect == null` opens `ReaderLinkAtSheet`, which shows the editor for the link at (x, y), or this page's link list when nothing was hit.
- **Optional overflow item** «روابط الصفحة» (links on this page). Hold a flag `showLinks` and render:
  ```kotlin
  if (showLinks) com.smartdocs.editor.ui.edit5.ReaderPageLinksSheet(state, state.currentPage) { showLinks = false }
  ```

### 4. Link from selected text: `ReaderOverlays.kt` `SelectionMenu`
After the `ToolChip(Icons.Default.Share, …) { onShare(sel.text) }` line, add:
```kotlin
var e5Link by remember { mutableStateOf(false) }
ToolChip(Icons.Default.Link, stringResource(R.string.annot_link)) { e5Link = true }
if (e5Link) com.smartdocs.editor.ui.edit5.ReaderLinkFromSelection(state) { e5Link = false }
```
The link gets one QuadPoints entry per text line. URLs and e-mails in the selection are prefilled.

### 5. Optional: follow GoToR / Launch links in the reader
PDFium reports these links with neither a URI nor a page. In `ReaderScreen.kt`, the `onOpenLink = { l -> ReaderIntents.openLink(ctx, l) { p -> state.jumpTo(p) } }` argument becomes:
```kotlin
onOpenLink = { l -> if (l.destPage == null && l.uri.isNullOrBlank()) scope.launch { com.smartdocs.editor.ui.edit5.followFileLinkAt(state, state.currentPage, l.bounds.centerX(), l.bounds.centerY()) { f, p -> nav.navigate(Routes.reader(android.net.Uri.fromFile(f).toString(), p)) } } else ReaderIntents.openLink(ctx, l) { p -> state.jumpTo(p) } }
```

### 6. Optional: page labels in the reader
- Page indicator:
  ```kotlin
  val labels = com.smartdocs.editor.ui.edit5.rememberPageLabels(state)
  ```
  Then show `com.smartdocs.editor.ui.edit5.pageIndicator(labels, page, count)`.
- `GoToPageDialog`: resolve the typed text with `com.smartdocs.editor.ui.edit5.resolvePageInput(labels, text, count)`.

## Status per feature
| Feature | Status |
|---|---|
| Real link annotations | **DONE.** URI, go-to page (XYZ + zoom / Fit / FitH), named destination, other PDF (GoToR, relative name when it sits in the same folder, new window), other file (Launch), mailto with subject/body. Drawn from a rectangle or from selected text (QuadPoints). Edit, delete, delete all / web only, convert text URLs. Border none/solid/dashed/underline, colour, width, /H mode, tooltip; appearance stream built when a border is shown. Standard `/Link` dictionaries, so they work in other PDF apps. Reader replacement = hooks 3–4. |
| Headers & footers | **DONE.** Six cells and tokens (`{page} {pages} {date[:fmt]} {time} {datetime} {hijri[:fmt]} {file} {title} {author} {user} {bates} {label}`). Hijri date (Umm al-Qura, tabular fallback, Arabic/English month names), Arabic-Indic digits, Arabic fonts through ArabicShaper/arabicFont plus system Naskh/Kufi/Sans. Margins, start number, mirrored even pages, page range plus odd/even/portrait/landscape. Tagged `/Artifact <</Type/Pagination/Subtype/Header\|Footer/SDE5 (hf)>>`, settings stored in /PieceInfo. Update and remove, including Acrobat's pagination artifacts. PARTIAL: "shrink content" is not tagged. Update does not shrink the page again, but after a full remove the scale stays in the content. |
| Bates numbering across files | **DONE.** Prefix, suffix, digits, start number; numbering continues across the ordered files; six positions; tagged `bates`. The range of each file is stored in /PieceInfo. |
| Watermark & background | **DONE.** Text, image or colour; over or behind; opacity, rotation, scale, 3×3 position plus offsets, tiling, page range/subset. Screen/print visibility through an OCG with /Usage and /D /AS. Update and remove (ours and other producers' /Watermark and /Background artifacts). |
| Dynamic watermark tokens | **PARTIAL.** `{user} {date} {time} {datetime} {page} {file}` are resolved per page when the watermark is applied; Update refreshes them. Values computed at print or open time would need document JavaScript, which is not written (security). |
| Security watermark | **DONE.** Tiled faint diagonal text with user, date-time, file and a trace ID, plus a microtext line on every edge. The trace ID is also written to Info `/SmartDocsTrace`. |
| Page labels | **DONE.** Editor (D/R/r/A/a/prefix only, prefix, start number) with preview; written as PDF `/PageLabels`. Reader helpers in hook 6. |
| Real crop (CropBox) | **DONE.** Draw a rect or type margins; Crop/Trim/Bleed/Art box; page range/subset; reset. Auto white-margin removal renders each page with PDFium, with padding and an option for one crop on all pages. Undo inside the tool. |
| Resize pages | **DONE.** Paper presets or custom mm; fit/fill/centre/stretch; margins; forced orientation. Annotation rects and QuadPoints are transformed too. |
| Booklet / 2-up / 4-up / poster | **DONE.** N-up 2/4/6/8/9/16 (RTL, column-first, borders, gap, margins, paper). Saddle-stitch booklet with right or left binding and creep. Poster tiles with overlap, cut marks and tile labels. All are vector form XObjects with rotation and crop handled. |
| Replace pages | **DONE.** Bookmarks, links, named destinations and the open action are re-pointed to the replacement page. |
| Split one page into several | **DONE.** Halves left/right or top/bottom (adjustable cut), quarters, thirds; RTL order. Lossless: copies with a CropBox each. |
| Smart split at blank pages | **DONE.** Blank = no text and ink below 0.2%, rendered with PDFium. A text marker can split instead. Separators can be dropped; one file per part. |
| Interleave, reverse, duplicate, copy between files | **DONE.** Done inside the page tree, so outline and link targets of kept pages stay valid. Reverse can be limited to a range. Duplicate N times after each page or at the end. Copy pages from another file at a position. Interleave can reverse the second file. |
| Page templates in the document | **DONE.** Save a page as a hidden template (`/Names /Templates`, /Type /Template, the page leaves the tree) or as a named page (`/Names /Pages`). Insert N copies at a position, show/hide, delete. |
| Outline editor (colour, bold/italic, zoom) | **DONE.** Keeps `/C`, `/F` bold/italic, open/closed `/Count`, and XYZ left/top/zoom or Fit/FitH destinations. Named destinations are resolved. Other actions (URI, JS, GoToR …) are kept untouched. Move up/down, indent/outdent, delete, add, edit sheet. Reuses `OutlineModel` tree operations. |
| Auto bookmarks from headings | **DONE.** Lines in a larger font than the body (weighted mode) become headings. The largest N sizes become levels. Wrapped titles are merged; repeated running headers are skipped. Replace or append. |
| Clickable TOC page | **DONE.** Inserted at the start from the bookmarks: title, levels, dot leaders, page numbers shifted by the TOC pages, Arabic digits, RTL layout, font choice. Each row is a real link; a "Contents" bookmark is added. |
| Document properties | **DONE.** Title, author, subject, keywords, creator, producer, Trapped, creation date (can reset), modified date updated, custom keys. Open action (page plus Fit/FitH/XYZ zoom), PageLayout, PageMode. Viewer prefs: `/Direction` R2L/L2R, DisplayDocTitle, Fit/CenterWindow, Hide toolbar/menubar/UI, PrintScaling, Duplex. `/Lang`; facts; XMP packet regenerated from the fields (optional). |
| Insert blank pages (graph, music …) | **DONE.** Blank, lined, narrow ruled with margin (RTL side), 5 mm grid, millimetre graph paper (1/5/10 mm weights), dots, isometric, music staves, Cornell notes; line and paper colour; same size as the document or a paper preset; count and position. |
| JUnit tests for pure logic | **DONE.** 16 tests (see Verification). |

## Limitations
- **Locked files in multi-file tools:** Bates, impose and the second file of replace/copy/interleave open files without a password. Unlock those files first. The main document of each tool asks for its password.
- **Link targets for "other file" links:**
  - The target is stored as a relative name when the file sits next to the document. Otherwise the absolute path is stored, which is app-private.
  - Files picked from outside are copied into the documents folder, so a link to them works as long as both files stay together.
- **Reader rect space:** the reader's drawn rect and the selection rects are used as PDF user space, which is what the reader already does for annotations. Pages with a /Rotate or a crop box that does not start at 0,0 have the same offset as the reader's other tools. The edit5 screens themselves map rotation and crop correctly (`PageInfo`).
- **Heading detection** needs real text. Scanned PDFs need OCR first.
