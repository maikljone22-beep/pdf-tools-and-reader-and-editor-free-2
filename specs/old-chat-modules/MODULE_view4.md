# MODULE view4 — comment, view, shell, file, preferences, help and print gaps (round 4)

No Gradle was run. The code was compiled with kotlinc 2.1 and the Compose compiler plugin. The classpath was:
- the cached AndroidX / Compose / PdfBox jars;
- the app's last `kotlin-classes/release` output;
- a regenerated R stub that includes the new `v4_` strings.

The build ran to the end of the backend for all 45 files in `core/view4` and `ui/view4`, and only three things were changed for the test:
1. A stale `PdfOps.merge` signature in the old class output (the current source already has the `passwords` parameter).
2. `V4WindowActivity` was taken out, because the test classpath lacks `androidx.core`'s `MenuHost`.
3. `-Xfriend-paths` was added so `internal PdfOps.load` resolves.

`MeasureModel`, `ShapeFit` and `Reflow.pageBlocks` were also run on the desktop JVM against real PDFs from `/home/claude/convtest/pdfs`.

Strings: `res/values/strings_view4.xml` and `res/values-en/strings_view4.xml`, prefix `v4_`, 597 strings. Both pass xmllint. Every `R.string.v4_*` used in the code is defined.
New XML resource: `res/xml/v4_shortcuts.xml` (static launcher shortcuts).

## Files
**core/view4** (no UI):
- `MeasureModel` (units, scale, calibration, geometry, CSV)
- `MeasureWriter` (Line/PolyLine/Polygon/Circle with `/Measure` RL, `/IT …Dimension`, page `/VP`; reads them back)
- `VectorSnap` (path segments → endpoint / midpoint / intersection / on-path snap)
- `CalloutWriter` (FreeText callout `/CL` `/LE`, typewriter or text box with border / dash / cloud / fill / align / direction, tickmarks, rich `/RC`)
- `LinkWriter` (real `/Link` URI and GoTo, list, auto-link; area highlight)
- `CommentTools` (filters, checkmarks `/StateModel Marked`, CSV, highlight export, summary layouts)
- `CommentEdit` (full properties with PdfBox `constructAppearances`, comment clipboard, align / distribute, filtered copy, DOCX export)
- `StampLibrary` (dynamic templates, tokens incl. Hijri, prompt fields, categories, favourites, image or PDF-page stamps)
- `Reflow` (structured blocks + JSON cache + Arabic folding search)
- `PrintPrep` (what / pages / reverse / scale / paper / N-up / booklet / greyscale)
- `FileProps` (info, custom properties, XMP, initial view, R2L, language, print presets, fonts, layers, labels, transitions, tags, space audit)
- `Optimizer` (effective-dpi downsampling, JPEG / lossless / CCITT, MRC, discard and clean-up)
- `MediaScan` (Screen / RichMedia / Movie / Sound → files, WAV wrap)
- `ShapeFit`, `Trash`, `V4Prefs`, `V4Pdf` (helpers)

**ui/view4**:
- `V4State` (per-reader state, weak map, no ReaderState field) and `View4ReaderHost` (single host + View / Comment chips + ⋮ items)
- Measuring and placing on the page: `Workbench` + `WorkbenchParts` (page workbench), `MeasureListSheet`
- Reading and viewing: `LiquidReflow`, `SplitCompare`, `Presentation`, `ViewSheets` (text viewer, zoom, overlays, word count, shortcuts), `PageOverlays`, `PointerAids`, `NavPanesSheet`
- Comments, stamps, print and search: `CommentManagerSheet`, `StampPicker4`, `PrintSheet`, `AdvancedSearch`, `CommandSearch` (command search, pins, customise)
- History, keys and other reader surfaces: `V4History`, `V4Keys`, `MoreSurfaces` (facing, calculator, paste, snapshot, save-as hub, favourite tools), `InkTools`
- Shell and app screens: `V4Shell`, `FileScreens` (routes, trash, properties, folders), `OptimizerScreen`, `MoreSettingsScreen`, `HelpCenter`
- Shared helpers: `V4Extras` (second window, tab menu, accent, tint, read-aloud source, selection chips, spell check, pref helpers), `V4Common`

## Hooks for the lead (exact lines; anchors are the current text)
**`ui/reader/ReaderScreen.kt`**
1. After `com.smartdocs.editor.ui.reader.annot.Annot2Dialogs(state, nav, snack)` add:
   `com.smartdocs.editor.ui.view4.View4ReaderHost(state, nav, snack, actions) { outputFile() }`
2. Replace `if (state.reflow) ReflowView(state)` with:
   ```
   com.smartdocs.editor.ui.view4.V4PageOverlays(state)
   if (state.reflow) com.smartdocs.editor.ui.view4.LiquidReflowView(state)
   ```
   This both replaces ReflowView (A1) and adds the overlays (rulers, grid, reading ruler, lens, marquee, auto-scroll bar, context menu, history pill, recovery banner).
3. In the page `Box` modifier chain, after `.focusable()` add:
   `.then(com.smartdocs.editor.ui.view4.v4Pointer(state))`
   (Ctrl+wheel zoom and right-click menu.)
4. In `onPreviewKeyEvent`, replace the body line with:
   `com.smartdocs.editor.ui.view4.V4Keys.onKey(com.smartdocs.editor.ui.view4.V4.of(state), e.nativeKeyEvent, ctx) || state.vx.handleKeyCode(e.nativeKeyEvent.keyCode, settings.volumeKeysFlip, rtl)`
5. In `ReaderActions(...)` set:
   `print = { com.smartdocs.editor.ui.view4.V4.of(state).open(com.smartdocs.editor.ui.view4.V4Surface.PRINT) }`
   Do the same for `onPrint =` inside `quick = { … }`.
6. Replace `StampPickerSheet(` with `com.smartdocs.editor.ui.view4.V4StampPicker(`. The parameters are the same.
7. In the top bar `actions = {`, before the Search `IconButton`, add:
   `com.smartdocs.editor.ui.view4.V4UndoButtons(state); com.smartdocs.editor.ui.view4.V4PinnedActions(state)`
8. In the ⋮ `DropdownMenu`, after the Settings item, add:
   `com.smartdocs.editor.ui.view4.V4MoreMenuItems(state, nav) { menu = false }`
9. In `BackHandler { when {`, add as the first branch:
   `com.smartdocs.editor.ui.view4.V4.of(state).let { it.historyPill && it.back() } -> {}`
10. In `doStartReadAloud`, replace `{ state.pageText(it) }` with:
    `{ com.smartdocs.editor.ui.view4.V4ReadAloud.pageText(state, it) }`

**`ui/reader/ReaderToolbars.kt`**
- At the end of the `ViewToolbar` `ToolRow`, after the doc-info chip:
  `com.smartdocs.editor.ui.view4.View4ViewChips(state)`
- In `CommentToolbar`, after `Annot2CommentChips(state, a)`:
  `com.smartdocs.editor.ui.view4.View4CommentChips(state)`
- In `ReaderTabRow`, wrap the list:
  `val tabs = com.smartdocs.editor.ui.view4.V4Pins.applyTabs(androidx.compose.ui.platform.LocalContext.current, listOf(...existing...), state.tab)`

**`ui/reader/ReaderOverlays.kt` (`SelectionMenu`)**
- After the strikeout `ToolChip`:
  `com.smartdocs.editor.ui.view4.V4SelectionChips(state, sel)`

**`ui/reader/PageCanvas.kt`**
1. Before the first `LaunchedEffect`:
   `val showC = com.smartdocs.editor.ui.view4.v4ShowComments(state); val ctx4 = androidx.compose.ui.platform.LocalContext.current`
2. Add `showC` to that effect's keys, and render with `session.render(page, w, h, night, showC)`.
3. Change `annots.forEach { a -> if (a.page == page && …` to `if (showC) annots.forEach { … }`.
4. Change `if (!night) with(com.smartdocs.editor.ui.reader.view.PageTintDraw) { drawPageTint(pageColor, bw, bh) }` to:
   `if (!night && !with(com.smartdocs.editor.ui.view4.V4Tint) { drawV4Tint(ctx4, bw, bh) }) with(com.smartdocs.editor.ui.reader.view.PageTintDraw) { drawPageTint(pageColor, bw, bh) }`

**`ui/reader/PageView.kt`**
- Inside the continuous branch, next to the `listState` usage:
  `com.smartdocs.editor.ui.view4.V4AutoScrollEffect(state, listState)`

**`ui/Navigation.kt`**
- After `acctRoutes(nav)`:
  `com.smartdocs.editor.ui.view4.view4Routes(nav)`
- Routes it adds: `v4_trash`, `v4_props?uri=`, `v4_optimizer?uri=`, `v4_more_settings`, `v4_help`, `v4_whats_new`.

**`MainActivity.kt`**
1. First line of `handleIntent` (before `val uri`):
   `if (com.smartdocs.editor.ui.view4.V4Shell.handleIntent(this, intent)) { openedByIntent = true; return }`
2. After `AppNavHost(nav)`:
   `com.smartdocs.editor.ui.view4.V4ShellHost(nav)`
3. Add this override:
   `override fun onProvideKeyboardShortcuts(data: MutableList<android.view.KeyboardShortcutGroup>?, menu: android.view.Menu?, deviceId: Int) { super.onProvideKeyboardShortcuts(data, menu, deviceId); data?.addAll(com.smartdocs.editor.ui.view4.V4Keys.groups(this)) }`

**`AndroidManifest.xml`**
1. Inside `<activity android:name=".MainActivity">`:
   `<meta-data android:name="android.app.shortcuts" android:resource="@xml/v4_shortcuts" />`
2. Inside the same activity, add these filters:
   ```xml
   <intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="content"/><data android:scheme="file"/><data android:mimeType="application/vnd.openxmlformats-officedocument.wordprocessingml.document"/><data android:mimeType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"/><data android:mimeType="application/vnd.openxmlformats-officedocument.presentationml.presentation"/><data android:mimeType="application/msword"/><data android:mimeType="application/vnd.ms-excel"/><data android:mimeType="application/vnd.ms-powerpoint"/><data android:mimeType="text/plain"/><data android:mimeType="image/*"/></intent-filter>
   <intent-filter><action android:name="android.intent.action.SEND"/><category android:name="android.intent.category.DEFAULT"/><data android:mimeType="application/*"/><data android:mimeType="text/*"/></intent-filter>
   <intent-filter><action android:name="android.intent.action.SEND_MULTIPLE"/><category android:name="android.intent.category.DEFAULT"/><data android:mimeType="application/pdf"/><data android:mimeType="*/*"/></intent-filter>
   ```
3. After the MainActivity element:
   `<activity android:name=".ui.view4.V4WindowActivity" android:exported="false" android:documentLaunchMode="always" android:resizeableActivity="true" android:theme="@style/Theme.SmartDocs" android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize|uiMode"/>`

**`ui/settings/SettingsScreen.kt`**
- In the "About" `GroupCard`, before help, add three `GroupRow`s:
  - `GroupRow(stringResource(R.string.v4_more_settings), icon = Icons.Default.Tune, tile = tiles.purple, onClick = { nav.navigate("v4_more_settings") })`
  - the same for `R.string.v4_trash` → `"v4_trash"`
  - the same for `R.string.v4_help_center` → `"v4_help"`
- Optional, for PREF-1: a search field that navigates to More settings. That screen already filters every v4 row.

**`ui/files/FilesScreen.kt`**
1. The delete confirm:
   - Replace `list.forEach { it.deleteRecursively() }` with `list.forEach { if (!com.smartdocs.editor.core.view4.Trash.moveToTrash(ctx, it)) it.deleteRecursively() }`.
   - Change the dialog text to "نقل إلى المحذوفات".
2. Multi-select bar actions:
   - Compress: `{ com.smartdocs.editor.ui.view4.V4Batch.pending.addAll(selectedFiles); nav.navigate("v4_optimizer?uri=") }`
   - Print: `{ scope.launch { com.smartdocs.editor.ui.view4.V4Print.printFiles(ctx, app, selectedFiles.filter { it.extension.equals("pdf", true) }) } }`
3. At the top of the device tab:
   `com.smartdocs.editor.ui.view4.V4FolderChips { dir -> /* open dir */ }`
4. Call `RecentFolders.visit(ctx, dir)` when a folder is opened. Pin and unpin through `RecentFolders.togglePin(ctx, dir)` in the folder ⋮ menu.
5. The file ⋮ "info" item can open `nav.navigate(com.smartdocs.editor.ui.view4.V4Routes.props(Uri.fromFile(f).toString()))`.
6. A toolbar entry should open `"v4_trash"`.

**Other hooks**
- **`core/theme/Theme.kt`**: wrap the chosen scheme with `com.smartdocs.editor.ui.view4.V4Accent.apply(LocalContext.current, scheme, dark)` (SHL-13).
- **`ui/home/HomeScreen.kt`**: call `com.smartdocs.editor.ui.view4.WhatsNewOnce(nav)` once.
- **`core/files/DocumentRepository.touchRecent`**: after the upsert, add:
  ```
  com.smartdocs.editor.ui.view4.V4Shell.pushRecent(context, file)
  com.smartdocs.editor.ui.view4.V4PrefHooks.trimRecents(context.applicationContext as SmartDocsApp)
  ```
- **PREF consumers** (other modules' files), all through `V4PrefHooks`:
  - `offline(ctx)` → return an error early in `AiClient`, `CloudHttp`, `CollabClient`, `TsaClient` and the OCR model download.
  - `hideAi(ctx)` → hide the AI chips and tools.
  - `digits()` / `forcedRtl()` → `TextWriter`, the stamp and FreeText writers.
  - `author()` → `ReaderState.authorName`.
  - `defaultViewMode()` → the `ReaderState.viewMode` initial value.
- **Tools tab** (`ToolsScreen` cards; navigate with `nav.navigate(...)`, not `Routes.tool`):
  - "الضغط المتقدم" → `v4_optimizer?uri=`
  - "المحذوفات" → `v4_trash`
  - "مركز المساعدة" → `v4_help`

## Status per owned backlog row (98)
| ID | Status |
|---|---|
| SHL-1 | DONE. `CommandSearchSheet` ranks ~70 commands (tools, quick actions, panels, view surfaces, settings) with AR/EN keywords and Arabic folding. It ends with "search in document" and "ask the assistant" rows. Opened from the ⚡ top-bar icon, the View chip and the context menu. |
| SHL-2 | DONE. `V4PinnedActions` shows pinned commands in the top bar (4 on phones, 6 on tablets); long-press in command search or Customise pins a command. |
| SHL-3 | DONE. `CustomizeSheet`: tab order and visibility (`V4Pins.applyTabs` hook), pinned commands, JSON export, import and reset. The JSON also carries annot2's "my tools" set and the favourite styled tools. |
| SHL-4 | DONE (hook). `V4SelectionChips` adds squiggly, note-on-selection, bookmark, link, word count, redact, edit text, insert / replace (annot2 caret and replace) and export selection, plus spell check in the note dialog. The AI items are already in the menu. |
| SHL-5 | PARTIAL. `CompareScrollDialog` shows the open document beside any other PDF with its own scroll and zoom and a sync toggle. The second pane is view-only: editing two documents at once needs two `ReaderState`s in the reader (L). |
| SHL-6 | DONE (manifest hook). `V4WindowActivity` + `V4Window.open` use LAUNCH_ADJACENT / MULTIPLE_TASK / NEW_DOCUMENT; available from command search and the ⋮ / tab menu. |
| SHL-7 | DONE (hook). `V4TabMenuItems`: close others, close all, print all, share all, new window, show in Files. Placed in the ⋮ menu; can also be added to the `ReaderTabs` menus. A setting for the tab limit is not added, because `ReaderTabs.MAX` is a constant in the view module. |
| SHL-8 | DONE. `V4PageOverlays` records the position before every jump (links, bookmarks, search, go-to, panels). It shows the «رجوع إلى الموضع السابق» pill for 6 s; Back (hook) and Alt+← return. |
| SHL-9 | DONE (hook). `V4Versions` snapshots the file after every rewrite. Each save, quick tool, page tool, AI edit or form flush bumps `renderVersion`, which triggers the snapshot. `V4UndoButtons` first undo pending annotations, then walk the versions. Long-press shows the history list; at most 25 versions / 150 MB, deleted on close. |
| SHL-10 | DONE. Every N minutes (1/5/10, setting) pending annotations are committed to `filesDir/recovery`. After an abnormal exit a banner offers restore / ignore. "العودة إلى نسخة الفتح" (revert) is in ⋮ and History. |
| SHL-11 | DONE (hooks). `V4Keys` implements Ctrl+F/S/Shift+S/P/D/Z/Y/G/0/1/2/3/±/4/6/B/L/M/H/Shift+H/Shift+Y/'/', F3/Shift+F3, F4, F11, Home/End, Esc and Alt+←. Optional single-key tools H U S N T P M V. Also a help sheet and Android's shortcut helper (`onProvideKeyboardShortcuts`). |
| SHL-12 | DONE (hook). `v4Pointer`: Ctrl+wheel zooms; a secondary click opens a context menu (copy page text, comments, paste, snapshot, commands, print). Hover tooltips on the reader's own chips are not added (ReaderToolbars is not mine). |
| SHL-13 | DONE (theme hook). 7 accent colours in More settings › المظهر; `V4Accent.apply` changes primary and primaryContainer for light and dark. |
| FILE-1 | DONE. `FilePropsScreen` › الوصف: title, author, subject and keywords. Custom properties can be added, edited or deleted. Read-only rows: version, pages, size, file size, dates, application, producer, tagged, fast web view, encrypted, PDF/A, XMP, portfolio. XMP export and import are in the ⋮ menu. |
| FILE-2 | DONE (reader honouring = hook). The «العرض الأولي» tab sets page mode, layout, magnification, open page, document title and hide toolbars. `FileProps.initialView()` is there for the reader to honour on open. |
| FILE-3 | DONE (writing) / PARTIAL (honouring). `/ViewerPreferences /Direction R2L` and `/Lang` are written. The facing order follows `/Direction` only in view4's `FacingScrollDialog` (RTL); `PageSpread` in the view module still uses the UI direction. Hook: read `FileProps.initialView().rtl`. |
| FILE-4 | DONE. Fonts tab: page resources, form XObjects, annotation APs and AcroForm DR, with type, encoding, embedded / subset and ToUnicode. Warns when fonts are both not embedded and lack ToUnicode. |
| FILE-5 | DONE. The «الطباعة» tab sets PrintScaling, Duplex, NumCopies and PrintPageRange. The print sheet keeps its own last choices; reading the document presets into it is left to the user's choice. |
| FILE-6 | DONE. «المساحة» tab: bytes per category (images, content, fonts, forms, comments, other, overhead) with bars and a link to the optimizer. |
| FILE-7 | DONE (with the noted limits). `Optimizer` + `OptimizerScreen`. Images: effective-dpi downsample, threshold, JPEG quality, greyscale, bi-level → CCITT G4, only-if-smaller. Discard: JS and actions, thumbnails, metadata, attachments, bookmarks, tags, comments, private data, alternates. Clean-up: Flate for unencoded streams. A custom preset can be saved. NOT FEASIBLE: JBIG2 / JPEG2000 encoding, linearisation and object streams (PdfBox 2.0), re-subsetting fonts. |
| FILE-8 | DONE (Files hook). The optimizer takes many files (picker or `V4Batch` from the Files multi-select) and lists "from X to Y" per file. It runs in the screen's coroutine, not as a WorkManager job. |
| FILE-9 | DONE. MRC option: the full-page scan becomes a 100 dpi JPEG background plus a sharp ink layer (Otsu threshold) inside a Form XObject under the same resource name. Placement and the invisible OCR text are kept. Deskew and despeckle are not done. |
| FILE-10 | DONE. `SaveAsHubSheet`: PDF copy, optimized, PDF/A (routes to `pdfa`, owned elsewhere), Office / HTML, images, text, print-ready, all routed to the existing engines. |
| PREF-1 | DONE. `MoreSettingsScreen` has a search box that filters every row (title, category, AR/EN keywords, Arabic folding). |
| PREF-2 | PARTIAL. Settings for default direction, digits and reading direction exist. Applied in view4's writers (callout and typewriter direction toggle, tickmark digits, stamp digits). Other modules' writers need the `V4PrefHooks.digits/forcedRtl` hook. |
| PREF-3 | DONE. «هويتي» (name, title, organization, department, e-mail) is used by the dynamic stamps. `V4PrefHooks.author` is available for ReaderState. |
| PREF-4 | PARTIAL. Copying the selected text into markup already happens in `ReaderState.markupFromSelection`. Settings exist for opening the note after creating, merging ink, note font size and keep-tool; they need consumers in annot / reader code (hooks listed). |
| PREF-5 | PARTIAL. Settings exist for document title, PDF/A read-only, redaction suffix and recovery interval. The interval is used by SHL-10. The others need their consumers in reader and redact code. |
| PREF-6 | PARTIAL. Settings exist for layout, zoom, panel on open, logical numbers and gap. The reader's initial state must read them (`V4PrefHooks.defaultViewMode`). |
| PREF-7 | PARTIAL. The offline-mode switch and `V4PrefHooks.offline` exist; the AI, cloud, collab, TSA and OCR clients must check it (other modules). |
| PREF-8 | PARTIAL. The hide-AI switch and `V4PrefHooks.hideAi` exist; the AI chips must check it. |
| PREF-9 | DONE (hook). The recent-list size (10/25/50/unlimited) is applied by `V4PrefHooks.trimRecents` from `touchRecent`. Pinned and recent folders are in `RecentFolders` + `V4FolderChips`. |
| HOME-1 | DONE. `PasteSheet`: a clipboard image becomes a movable stamp placement; clipboard text becomes a typewriter text box. Pasting *into the page content* (content edit) is not offered. |
| HOME-2 | DONE. `PageSnapshotSheet`: whole page at 72 / 150 / 300 dpi → copy, share, save PNG, print, place as stamp on any page. |
| CMT-1 | DONE. Callout in the workbench: tap the tip, then the box; FreeText `/IT FreeTextCallout`, `/CL`, `/LE OpenArrow`, `/RD`, full style. Dragging the knee afterwards is not supported (AnnotEdit owns editing). |
| CMT-2 | DONE. The typewriter / text box dialog sets size, text colour, border colour and width, solid / dashed / cloudy border, fill, alignment and RTL/LTR. It writes `/DA`, `/DS`, `/Q`, `/BS`, `/BE` and `/IC` with our appearance. The font family is the bundled Naskh or Helvetica. |
| CMT-3 | DONE. Area highlight mode: two corners → Highlight with rectangle QuadPoints and a multiply appearance. |
| CMT-5 | DONE. Comment manager ⋮: highlighted text (PDFium text under each markup) as TXT, CSV or copy, grouped by page. A DOCX version is part of the Word export. |
| CMT-6 | DONE (PageCanvas hook). The eye chip in View, Comment and the comment manager renders without annotations, skips the pending overlay and shows a «التعليقات مخفية» badge. |
| CMT-7 | DONE. Comment manager: search; filters for author, type, status, colour bucket, date (today / 7 / 30 days) and checked / unchecked; 6 sort orders; private checkmarks written as `/StateModel Marked` replies. Previous / next stepping uses tap-to-jump instead of ‹ ›. |
| CMT-8 | DONE. Properties dialog in the comment manager: author, subject, contents, colour, opacity, width, dashed, both line endings (10 styles), note icon (11) and lock. The appearance is rebuilt by PdfBox `constructAppearances`. "Set current style as default" is covered by CMT-9 favourite tools. |
| CMT-9 | DONE. Favourite styled tools (tool, colour, opacity, width, font size) appear as chips in the Comment row. Shared through the Customise JSON. |
| CMT-10 | DONE. Comment manager selection: copy, cut, paste onto the current page of any open document (in-memory clipboard, PdfBox clone, geometry scaled to the target page). |
| CMT-11 | PARTIAL. Align (6) and distribute (2) on the selection, plus centring on the page, via `StoredOps.translate`. Rotating comments is not done, because it needs AP-matrix editing in AnnotEdit. |
| CMT-12 | DONE. Summary layouts: beside the page (annot2's), comments only, and page-then-comments (vector page + numbered markers); A4 or Letter, font size, current filter and sort. |
| CMT-13 | DONE. Distance, perimeter, polygon area and circle area are saved as Line / PolyLine / Polygon / Circle with `/IT …Dimension`, `/Measure` and labels. They are listed with comments and in the Measurements sheet. |
| CMT-14 | DONE. Calibrate a known length, or type a ratio; 9 units; precision 0–4; stored per page or document in `/VP`. A live chip shows distance, angle, ΔX, ΔY and Σ. |
| CMT-15 | DONE. Snap to endpoints, midpoints, intersections and paths (PdfBox path extraction). The loupe (long-press and drag) also helps placement. Snapping is off on rotated pages. |
| CMT-16 | DONE. CSV export (UTF-8 BOM, opens in Excel), totals per type, angle and count tools. |
| CMT-17 | DONE. Dynamic templates with token chips (name, title, organization, date, time, Hijri) and prompt fields; the user is asked for the fields at placement. |
| CMT-18 | PARTIAL. Categories, favourites (long-press), edit and delete, and stamps from a PDF page (rendered at 150 dpi with white made transparent, not a vector form). "Place on several pages" and the rotation handle stay in the placement UI owned by the reader. |
| CMT-19 | DONE. Tickmarks ✓ ✗ ? ★, auto-incrementing 1,2,3 (Arabic-Indic digits per the setting), أ ب ج (abjad) and A B C; `/Subj Tickmark:<style>`; 3 sizes and 4 colours. Jumping to next or previous of the same style is not added. |
| CMT-20 | DONE. Calculator tape: + − × ÷, a note per row, decimals, Arabic digits; inserted as a typewriter text box. Re-editing the inserted tape is not supported. |
| CMT-21 | DONE. Export selected comments (XFDF via a filtered copy), import several FDF/XFDF/PDF files at once, share comments as XFDF. |
| CMT-22 | PARTIAL. Comments, the text under markups and replies export to a DOCX table. Applying the edits back into the source Word file with tracked changes is not done (L). |
| CMT-23 | PARTIAL. `**bold**` / `_italic_` in the callout and typewriter text are stored as an XHTML `/RC` (other viewers show the formatting). Our own appearance stream draws regular weight, and `/RC` from other files is not parsed for editing. |
| CMT-24 | PARTIAL. The «الملاحظات على الصفحة» mode of the workbench shows every note on the page as a bubble. Draggable in-reader bubbles need PageCanvas geometry (not mine). |
| CMT-25 | PARTIAL. The tool stays selected already (`continueCreating`) and has a setting. A 45° constraint exists for distance measuring in the workbench. Snapping in the reader's drawing gestures needs a hook in PageGestures. |
| CMT-26 | DONE (one tap). «قلم ذكي»: mostly-horizontal pending ink strokes over text become highlights of the characters they cross. This is a conversion after drawing, not live. |
| VIEW-1 | DONE. `FacingScrollDialog`: continuous two-up (or 3/4-up) spreads, cover option, RTL order. It is a separate view surface without annotation tools; PageView's facing mode stays paged. |
| VIEW-2 | DONE. 1–4 pages per row and gap 0 / 8 / 24 in the same surface. |
| VIEW-3 | DONE. Zoom sheet and Ctrl+0/1/2/3: fit page, fit width, fit visible (content box from CropController detection), actual size (xdpi), presets 100–600 % and a typed value. The reader's minimum zoom is fit width. |
| VIEW-4 | PARTIAL. The marquee chip: drag a rectangle and the zoom fits its width. The view is not panned to the rectangle, because PageView's pan is private. |
| VIEW-5 | DONE. The lens chip uses Android's `Magnifier` following the finger (API 28+). On older devices the workbench loupe is offered. |
| VIEW-6 | DONE. Split view: two independent panes of the same session with a draggable divider, side by side or stacked. |
| VIEW-7 | DONE. Presentation: black, fitted, tap / swipe / keys / volume, loop, auto-advance 5–30 s. Transitions are the document's `/Trans` or a chosen one (fade, push, cover, uncover, wipe, split, box, fly…). An external display is mirrored via `android.app.Presentation`. |
| VIEW-8 | PARTIAL. Reverse order is available in the facing surface; the main reader keeps natural order (PageSpread is not mine). |
| VIEW-9 | DONE (PageView hook). Smooth auto-scroll at speed 1–10 with pause, reverse and stop (`V4AutoScrollEffect`). |
| VIEW-10 | DONE. Reading ruler: draggable band, colour, opacity, height, dim outside. |
| VIEW-11 | PARTIAL. Rulers (cm / mm / in / pt at the current zoom, fit-width basis), grid with spacing and subdivisions, and draggable guides are drawn over the page area. "Snap to grid" for edit and form-builder drags needs hooks in those modules. |
| VIEW-13 | DONE. Advanced search: phrase / any / all-within-N-words, Arabic light stemming, 9 patterns by region (phone, e-mail, IBAN, date, amount, Yemen / Saudi / Egypt / UAE IDs). Results show context and appear on the page (sets `searchHits`); save as CSV. Searching all open tabs is not done (only one session is open). |
| VIEW-14 | DONE. Word count of the selection or the document: words, characters, characters without spaces, lines, paragraphs, pages; Arabic punctuation aware. |
| VIEW-15 | PARTIAL (PageCanvas hook). Custom paper / ink pairs (5 presets, via blend modes). "Change only black text" is not feasible with blend overlays (it needs per-pixel masking of the render). |
| VIEW-16 | DONE (hook). `V4ReadAloud.pageText`: logical reading order from the reflow analysis, plus comments, form fields and figure alt text, each switchable in settings. |
| VIEW-17 | NOT FEASIBLE. The PDFium wrapper has no hairline render flag, and rewriting line widths per frame is too slow. |
| HLP-1 | DONE. 10 offline AR/EN articles (`HelpArticleSheet` by context key, `HelpCenterScreen` with search). The «؟» entry in each tool sheet is not added to sheets owned by other modules. |
| HLP-2 | PARTIAL. «اسأل عن التطبيق» opens the existing AI assistant; retrieval over the help articles is not wired into the agent (AI module). |
| HLP-3 | DONE. «دروس فيديو» opens a YouTube search link (`VIDEO_URL`); replace it with the channel playlist when one exists. |
| HLP-4 | DONE (no new dependency). «البحث عن تحديث» opens the Play Store page. The What's-new screen is shown once after each update. |
| HLP-5 | NOT APPLICABLE. Desktop plug-ins, admin console and command lines. |
| PRN-1 | DONE (print hook). Print sheet: with / without comments or forms only; all / current / odd / even / range; reverse; fit / actual / %; paper; landscape; greyscale (150 dpi raster); comment summary. Then the system dialog. |
| PRN-2 | PARTIAL. 1/2/4/6/9/16 pages per sheet (order, borders) and booklet with right or left binding are done. The poster / tile mode is not done. |
| PRN-3 | DONE (Files hook). `V4Print.printFiles` prints a multi-selection as one job; «طباعة الكل» works for tabs. Including PDF attachments is not done. |
| A1 | DONE (hook). `LiquidReflowView`: whole-document blocks with running headers and footers removed (headings, paragraphs, lists, scrollable tables, image crops, page marks). Collapsible sections, outline jump, search with Arabic folding, font / line / word / letter spacing (none for Arabic), themes, serif, justify, images toggle, view original, RTL per block. JSON cache; built progressively; up to 600 pages. |
| A3 | DONE. The links mode creates real `/Link` annotations (URL / e-mail or go-to-page, visible or invisible border), lists and deletes existing links, and auto-links URLs and e-mails found in the page text. |
| A4 | DONE. Layers pane: the switches set the `/OCProperties /D` visibility in the document (undoable through History), plus "show all". A view-only temporary toggle is not available (the PDFium wrapper has no OCG state API). |
| A5 | PARTIAL. Labels are listed in the panes with jump. Showing them in the page indicator and go-to needs the reader hook (`FileProps.pageLabels`). Editing labels is not added. |
| A6 | DONE. Covered by FILE-1 … 5: viewer preferences, page mode / layout, open action, `/Lang`, R2L, print presets, fonts, version, tagged and fast web view. Honouring on open needs the reader hook. |
| A7 | PARTIAL. The Media pane extracts Screen (rendition), RichMedia assets, Movie and Sound (WAV-wrapped) and plays them with the system player. There is no inline floating player at the annotation's rect. |
| A8 | PARTIAL. Portfolios are detected (Properties shows it). The Attachments pane lists and opens the component files; the `/Collection` schema columns are not shown. |
| D1 | DONE. Same as CMT-13 … 16. |
| D2 | DONE (one tap). «تحويل الرسم لأشكال»: pending ink → line / rectangle / ellipse / triangle or polygon with the same style (`ShapeFit`, tested on the desktop). |
| D3 | DONE. Same as CMT-1. |
| D4 | DONE. Properties dialog: dash, both line endings, colour, width, opacity, lock. AnnotEdit's move refusal for locked annotations is up to AnnotEdit; we set the `/F` Locked bit. |
| D5 | DONE. CSV (comment manager), DOCX table and highlighted-text export. |
| D6 | PARTIAL. Same as CMT-23. |
| D7 | PARTIAL. The system `SpellCheckerSession` gives suggestions in view4's text dialogs (callout, typewriter, note on selection). Not in other modules' editors. |
| D8 | DONE. Private checkmarks (`/StateModel Marked`) with a filter. |

## Limitations
- All writes go through `ReaderState.applyInPlace` (undo through the done bar and the new History) and are written back to the source. Pending annotations are saved first, so they also enter History.
- The workbench works on one page at its own zoom. Callouts and text boxes cannot be edited later with our dialogs; AnnotEdit can move FreeText kinds.
- The Liquid view skips images under 0.4 % or over 92 % of the page. Scanned pages without text show the OCR hint.
- The page tint, rulers and marquee assume the fit-width layout (PageView's geometry is private).
- `V4WindowActivity` duplicates MainActivity's composition: theme, locals and `AppNavHost`, but not the onboarding gate.
- The optimizer opens files without a password; locked files fail and are listed with the error.
