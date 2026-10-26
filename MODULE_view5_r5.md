# MODULE view5 — round 5 (rebuild of view4: measure, comments, view, shell, trash, print, help)

Gradle was not run. The new code was type-checked with kotlinc 2.3.21 and the Compose compiler plugin. The classpath was the app's last compiled classes (`app/build/.../classes.jar`) plus the dependency jars, with a generated R stub that includes the new `v5_` strings. All files of `core/view5`, `ui/view5`, `core/tts/ReadAloudFocus.kt`, the edited `ReadAloudController.kt` and `PageView.kt` compile with **0 errors**.

The pure logic was compiled and run on the JVM: **20 JUnit tests, all pass** (`app/src/test/java/com/smartdocs/editor/core/view5/View5LogicTest.kt`). They cover:
- measurement geometry and snapping
- scale parsing and calibration
- the Hijri date (Umm al-Qura and the tabular fallback) and stamp tokens
- the back / forward history stack
- trash expiry, move, restore and purge
- print ranges, booklet order and N-up layout
- command ranking, comment filters and the reflow block builder

No new Gradle dependency was added.

Strings: `res/values/strings_view5.xml` and `res/values-en/strings_view5.xml`. They use the prefix `v5_`, contain 238 keys each (the same keys in both), and both parse as valid XML. Every `R.string.*` used by view5 exists.

## Files
**core/view5** (no UI)
- `MeasureModel.kt`: units, `MeasureScale` (parse "1 in = 10 ft" / "1:100" / Arabic digits, calibration), `Geom` (distance, path, shoelace area, circle, angle, 45° constraint, snap to points / segments / intersections), CSV with BOM and totals, `Digits`.
- `MeasureWriter.kt`: writes real measure annotations:
  - Line with `/IT /LineDimension`, PolyLine with `/PolyLineDimension`, Polygon with `/PolygonDimension`, Circle with `CircleDimension`.
  - Angle (PolyLine, `/Subj Angle`) and Count (Circle markers, `/Subj Count`).
  - Each has a `/Measure /RL` dictionary (`/R` scale, `/X /D /A /T` number formats) and our own appearance with labels.
  - `list()` reads them back (ours and other viewers' `/Measure` annotations); `lastScale()`.
- `CommentWriter5.kt`:
  - callout (FreeText `/IT /FreeTextCallout`, `/CL`, `/LE`, `/RD`), typewriter (`/FreeTextTypeWriter`) and text box (`/DA /DS /Q`, border / dash / fill)
  - tick / cross / dot / ? marks (stamps)
  - area highlight (Highlight with a rectangle quad, multiply)
  - line / arrow with dash and both line endings
  - `restyle()` for stored Line / PolyLine / Polygon / Square / Circle / Ink (`/BS`, `/LE`, colour, then PdfBox `constructAppearances`)
- `V5Pdf.kt`: shared PdfBox helpers: `DashStyle` (6), `LineEnd` (10 PDF ending names), appearance forms, ending shapes, glyph-safe Arabic/Latin labels.
- `CommentOps5.kt`: markup quads and private checkmarks (`/StateModel /Marked` replies, read and write), and a Word (.docx) table export.
- `CommentFilter.kt`: pure. `CRow`, filters (text with Arabic folding, author, type, status, page, checked), 6 sort orders, CSV, text summary, highlighted-text export.
- `CommandRank.kt`: pure. Ranking for "tell me", pins and recents.
- `HijriDate.kt`: pure. Umm al-Qura via `java.time.chrono.HijrahDate` (AH 1300–1600) with a tabular (Kuwaiti) fallback and a ± day adjustment. `StampTokens`: `{name} {title} {org} {date} {time} {datetime} {hijri} {hijri_num}`.
- `PositionHistory.kt`: pure. Browser-like back / forward stack.
- `Trash.kt`: java.io only. 30-day trash with a `.meta` sidecar: move, list, restore (free name), delete, empty, purge expired.
- `PrintLayout.kt`: pure. Range parsing (Arabic digits, open ends, reverse ranges), odd / even, booklet imposition (left or right binding), N-up grid and placement (RTL order).
- `PrintPrep.kt`: builds the PDF to print. Range / subset / reverse, then N-up (1/2/4/6/9/16) or a folded booklet. Pages are vector Form XObjects via `LayerUtility`, annotation appearances are flattened first, and comments can be left out.
- `PageGraphics.kt`: PdfBox `PDFGraphicsStreamEngine` that collects path segments (for snapping) and image placements (for reflow).
- `ReflowBlocks.kt`: pure. Lines and images become headings (by glyph size), paragraphs and images in reading order. Page numbers in the top or bottom margin are dropped.

**ui/view5**
- `V5State.kt`: per-reader state (weak map, no ReaderState field). It holds the open surface, overlays, auto-scroll, history, measure settings, the command env and pins.
- `View5Host.kt`: `View5ReaderHost` (single host), `View5ViewChips`, `View5CommentChips`, `V5MoreMenuItems`, `ToolMemoryEffect` (remembers the last settings per tool), the read-aloud options sheet and the overlay settings sheet.
- Measuring and comment tools: `Workbench.kt`, `WorkbenchModel.kt`, `WorkbenchCanvas.kt` (page workbench with loupe, snapping, pinch zoom), `WorkbenchDialogs.kt` (scale / calibration, text box, palette, dash picker, line-end picker, line style), `MeasureListSheet.kt`.
- Comments and stamps: `CommentManager.kt` (manager, filters, exports, checkmarks, restyle dialog), `StampPicker5.kt` (ready, sign-here set, dynamic templates, custom).
- Reading and viewing: `ReflowReader.kt` (full reflow), `SplitCompare.kt` (split view and compare with synced scroll), `Presentation5.kt`, `PageOverlays5.kt` (rulers, grid, reading ruler, lens, auto-scroll bar, history pill, `V5AutoScrollEffect`).
- Shell, help and print: `Commands5.kt` (command search, pinned top-bar actions), `Keys5.kt` (keyboard shortcuts, Android shortcut helper, app-icon shortcuts, `V5ShellHost`), `TrashScreen.kt` (+ `Trash5`), `HelpCenter5.kt` (help centre, what's new, `WhatsNewOnce`, routes), `PrintSheet5.kt`.

**core/tts**
- `ReadAloudFocus.kt` (new): audio focus (pause on call or other audio, resume after a transient loss), `ACTION_AUDIO_BECOMING_NOISY` (headphones unplugged), and repeat OFF / PAGE / DOCUMENT.

**Edited (own area, small and additive)**
- `core/tts/ReadAloudController.kt`, three hooks:
  - `advancePage()` uses `ReadAloudFocus.nextAfter`
  - `speakCurrent()` calls `ReadAloudFocus.acquire`
  - `stop()` calls `ReadAloudFocus.release`

  The existing raw U+0600 in `applyLanguage` became the escape `'؀'`; this does not change behaviour.
- `ui/reader/PageView.kt`, one line after `AutoFlipEffect(state)`:
  `if (mode == ViewMode.CONTINUOUS) com.smartdocs.editor.ui.view5.V5AutoScrollEffect(state, listState)`

## Hooks for the lead (exact; anchors are the current text)

### `ui/reader/ReaderScreen.kt`
1. After `com.smartdocs.editor.ui.reader.annot.Annot2Dialogs(state, nav, snack)      // MODULE_annot2 pickers & dialogs` add:
   ```kotlin
   com.smartdocs.editor.ui.view5.View5ReaderHost(state, nav, snack, actions) { outputFile() }
   ```
2. Replace `if (state.reflow) ReflowView(state)` with:
   ```kotlin
   com.smartdocs.editor.ui.view5.View5PageOverlays(state)
   if (state.reflow) com.smartdocs.editor.ui.view5.ReflowReader(state)
   ```
3. In `onPreviewKeyEvent`, replace the line `state.vx.handleKeyCode(e.nativeKeyEvent.keyCode, settings.volumeKeysFlip, rtl)` with:
   ```kotlin
   com.smartdocs.editor.ui.view5.V5Keys.onKey(state, e.nativeKeyEvent) || state.vx.handleKeyCode(e.nativeKeyEvent.keyCode, settings.volumeKeysFlip, rtl)
   ```
4. In `ReaderActions(...)`, replace `print = { scope.launch { outputFile()?.let { ReaderIntents.print(ctx, it, state.pageCount) } } },` with:
   ```kotlin
   print = { com.smartdocs.editor.ui.view5.V5State.of(state).open(com.smartdocs.editor.ui.view5.V5Surface.PRINT) },
   ```
   Inside `quick = { … }`, do the same for `onPrint = …`:
   ```kotlin
   onPrint = { com.smartdocs.editor.ui.view5.V5State.of(state).open(com.smartdocs.editor.ui.view5.V5Surface.PRINT) })
   ```
5. In the top bar `actions = {`, just before `IconButton(onClick = actions.search) { Icon(Icons.Default.Search, …) }` add:
   ```kotlin
   com.smartdocs.editor.ui.view5.V5TopActions(state)
   ```
   This adds back / forward when there is history, the pinned commands (2 on phones, 6 on tablets) and the ⚡ "tell me" button.
6. In the ⋮ `DropdownMenu`, after the Settings `DropdownMenuItem` add:
   ```kotlin
   com.smartdocs.editor.ui.view5.V5MoreMenuItems(state, nav) { menu = false }
   ```
7. In `BackHandler { when {`, add as the **first** branch:
   ```kotlin
   com.smartdocs.editor.ui.view5.V5State.of(state).onBack() -> {}
   ```
8. Replace `if (showStamps) StampPickerSheet(` with `if (showStamps) com.smartdocs.editor.ui.view5.V5StampPicker(`. The parameters are the same.

### `ui/reader/ReaderToolbars.kt`
- In `ViewToolbar`, after the last chip `ToolChip(Icons.Default.Info, stringResource(R.string.tool_doc_info), onClick = a.docInfo)` add:
  ```kotlin
  com.smartdocs.editor.ui.view5.View5ViewChips(state)
  ```
- In `CommentToolbar`, after `com.smartdocs.editor.ui.reader.annot.Annot2CommentChips(state, a)      // MODULE_annot2 tools + comments menu` add:
  ```kotlin
  com.smartdocs.editor.ui.view5.View5CommentChips(state)
  ```

### `ui/Navigation.kt`
- Add the import `import com.smartdocs.editor.ui.view5.view5Routes`. It is an extension on `NavGraphBuilder`, so it cannot be called with a fully-qualified name.
- After `acctRoutes(nav)` add:
  ```kotlin
  view5Routes(nav)
  ```
- Routes added: `v5_trash`, `v5_help`, `v5_whats_new` (`V5Routes.TRASH / HELP / WHATS_NEW`).

### `MainActivity.kt`
1. First line of `handleIntent(intent: Intent?)` (before `val uri`):
   ```kotlin
   if (com.smartdocs.editor.ui.view5.V5Shortcuts.handleIntent(this, intent)) return
   ```
2. In `onCreate`, after `setContent { … }`:
   ```kotlin
   com.smartdocs.editor.ui.view5.V5Shortcuts.publish(this)
   ```
   These are dynamic app-icon shortcuts: last document, scan, files, trash. No manifest change is needed.
3. After `AppNavHost(nav)`:
   ```kotlin
   com.smartdocs.editor.ui.view5.V5ShellHost(nav)
   ```
   It navigates for a shortcut and purges trash entries older than 30 days.
4. Add this override, so Meta+/ lists the reader shortcuts on Android 7+:
   ```kotlin
   override fun onProvideKeyboardShortcuts(data: MutableList<android.view.KeyboardShortcutGroup>?, menu: android.view.Menu?, deviceId: Int) {
       super.onProvideKeyboardShortcuts(data, menu, deviceId); data?.addAll(com.smartdocs.editor.ui.view5.V5Keys.groups(this))
   }
   ```

### `ui/files/FilesScreen.kt` (trash)
- In the delete confirm, replace `list.forEach { it.deleteRecursively() }` with:
  ```kotlin
  list.forEach { if (!com.smartdocs.editor.ui.view5.Trash5.moveToTrash(ctx, it)) it.deleteRecursively() }
  ```
  `ctx` already exists at line 78. Optionally change the dialog text to `stringResource(R.string.v5_trash_note, 30)`.
- Optional: a toolbar / ⋮ entry `nav.navigate(com.smartdocs.editor.ui.view5.V5Routes.TRASH)`.

### `ui/settings/SettingsScreen.kt`
- In the "About" `GroupCard`, before the help row, add:
  ```kotlin
  GroupRow(stringResource(R.string.v5_help_center), icon = Icons.Default.HelpOutline, tile = tiles.green, onClick = { nav.navigate(com.smartdocs.editor.ui.view5.V5Routes.HELP) })
  GroupRow(stringResource(R.string.v5_trash), icon = Icons.Default.DeleteSweep, tile = tiles.purple, onClick = { nav.navigate(com.smartdocs.editor.ui.view5.V5Routes.TRASH) })
  GroupRow(stringResource(R.string.v5_whats_new), icon = Icons.Default.NewReleases, tile = tiles.teal, onClick = { nav.navigate(com.smartdocs.editor.ui.view5.V5Routes.WHATS_NEW) })
  ```
  Use whatever tile colours exist in `tiles`.

### `ui/home/HomeScreen.kt`
- At the start of `HomeScreen(nav)` add `com.smartdocs.editor.ui.view5.WhatsNewOnce(nav)`. It is shown once after each update and skipped on first install.

### Tools tab (optional, lead wires)
- Cards: "المحذوفات" → `nav.navigate("v5_trash")`, "مركز المساعدة" → `nav.navigate("v5_help")`.

## Status per feature (the task's key list)
| Feature | Status |
|---|---|
| Measure: distance, perimeter, area, circle, angle, count | DONE. Page workbench (Comment tab › قياس). Live readout with ΔX, ΔY and angle. |
| Scale calibration | DONE. You can type a scale ("1 cm = 2 m", "1:100", Arabic digits) or draw over a known length and enter its real length. 9 units, precision 0–4, remembered. The last scale is also stored in every annotation's `/Measure /R`. |
| Snapping | DONE. Snaps to path end points, midpoints, intersections and the nearest point on a path (PdfBox path extraction), to glyph-box corners and to your own points. 45° constraint toggle. |
| Magnifier for placement | DONE. A one-finger drag shows a 2.5× loupe with a crosshair; the point is placed on release. Pinch to zoom up to 10×. |
| Saved as real /Measure annotations | DONE. Line / PolyLine / Polygon / Circle with `/IT …Dimension`, `/Measure /RL` and number formats, our appearance, and the value in `/Contents`. Undo is through `applyInPlace`. |
| CSV export | DONE. The Measurements sheet (Comment tab or ⌘ search) lists our and other viewers' measurements. CSV with UTF-8 BOM and totals per type. |
| Callout with arrow | DONE. Tap the tip, then two box corners, then the text dialog (size, colours, fill, dash, alignment, RTL, 10 line endings). Dragging the knee afterwards is not supported. |
| Typewriter | DONE. One tap; borderless FreeText `/IT FreeTextTypeWriter`; the box grows to fit the text (RTL anchors on the right). |
| Tick / cross marks | DONE. Three sizes, any colour. Numbered or abjad tickmarks are not done. |
| Area highlight | DONE. Two corners; Highlight with a rectangle quad and a multiply appearance. |
| Comment manager: search + filters (author/type/status/page) | DONE. Arabic-folded search; multi-select author, type, status and page filters; checked / unchecked; 6 sort orders. Pending (unsaved) annotations are listed too. Private checkmarks are written as `/StateModel Marked` replies. |
| Export to CSV / Word / summary | DONE. CSV (BOM), Word (.docx table, RTL), text summary grouped by page, and annot2's PDF summary. All respect the current filter. |
| Export highlighted text only | DONE. Text under every markup (PDFium glyphs inside the quads), exported as TXT or copied. |
| Dynamic stamps (name + date incl. Hijri) | DONE. Templates with tokens; the identity (name, title, organisation) is editable. Add a template with a live preview; long-press deletes. The Hijri date is Umm al-Qura, with an adjust pref `hijri_adj` (no UI yet). |
| Sign-here set (accepted, initial here, rejected, sign here, witness) | DONE. Flag-shaped stamps, each plain or dated with name and date. Hook 8 replaces the reader's picker. |
| Line dash styles + line-end shapes picker | DONE. Workbench line / arrow tool, callouts and measurement lines. The comment manager can also restyle stored lines and shapes (dash, both endings, width, colour). The reader's own LINE / ARROW tools (AnnotationWriter, a shared file) still write solid lines. |
| Remember last settings per annotation tool | DONE. `ToolMemoryEffect`: colour, opacity, thickness and font size per tool, restored when the tool is picked; can be switched off in read-aloud options › "تذكّر آخر إعدادات لكل أداة". Workbench tools remember their own settings. |
| Full reflow mode (text + images, headings) | DONE (hook 2). Whole document, built in the background starting at the current page. Headings by glyph size, paragraphs, image crops, page marks, page numbers dropped. Font size, line spacing, serif, justify, images toggle, headings list, Arabic-folded search with highlight, night mode; the reader's page stays in sync. Scanned pages show the page image. Tables are not structured. |
| Split view | DONE. Two panes of the same document, side by side or stacked, draggable divider, zoom per pane, optional sync. |
| Side-by-side compare with synced scroll | DONE. Pick another PDF; sync toggle (page and relative offset, both directions). The second pane is view-only. |
| Presentation mode with transitions | DONE. Black full screen with 7 transitions (none, fade, push, cover, zoom, wipe, slide up). Tap, swipe, arrows, space, volume and Home / End; auto-advance 5–60 s; loop; long-press laser pointer. External display is not supported. |
| Rulers, grids | PARTIAL. cm / mm rulers and a grid (2–50 mm) at the page scale of the current zoom. They are aligned to the page area, not to the page edge, because PageView's scroll geometry is private. |
| Reading ruler | DONE. Band with a drag handle, height, colour and dim outside. |
| Lens | DONE (API 28+). `android.widget.Magnifier` follows the finger, zoom 1.5–5×. On API 26–27 the chip shows a "needs Android 9" hint, and the workbench loupe still works. |
| Auto-scroll | DONE (PageView edit). Smooth continuous scroll at speed 1–10, pause, stop; switches to continuous mode. The paged modes keep the existing auto-flip. |
| Back / forward position history | DONE. Every jump through `pendingJump` (links, outline, bookmarks, search, go-to; page flips excluded) is recorded. A "رجوع إلى الصفحة N" pill shows for 6 s, the top bar has back / forward, and Alt+← / Alt+→ also work. Back from our surfaces is hook 7. |
| Command search ("tell me") with pinned commands | DONE. About 45 commands with AR/EN keywords and Arabic folding, ranked with pins and recents, plus a "search in document" row. Pin with long-press or the pin icon; pinned commands show in the top bar (hook 5). |
| Keyboard shortcuts | DONE (hook 3 + MainActivity 4). Ctrl+F/P/S/Shift+S/G/Z/Y/=/−/0/K/L/M/4/N, Ctrl+/, Ctrl+Shift+C/R, Alt+←/→, F5, Home / End, Esc. A help sheet and Android's shortcut helper. |
| App-icon shortcuts | DONE (MainActivity hooks 1–3). Dynamic shortcuts: last document, scan, files, trash. |
| 30-day trash bin | DONE (FilesScreen hook). Files moved from Files go to `filesDir/trash` with metadata. The trash screen can restore (to the original folder or Documents, picking a free name), delete forever and empty. Expired entries are purged on app start and when the screen opens. |
| Print options (range, booklet, pages per sheet) | DONE (hook 4). All / current / range, odd / even, reverse, with or without comments, 1/2/4/6/9/16 per sheet (RTL order, borders), booklet with right or left binding, A4 / Letter / A3 / A5, landscape. Vector pages via Form XObjects. Then the system print dialog. |
| Help centre + What's new | DONE. 13 offline AR/EN articles with search; the What's new screen; `WhatsNewOnce`. |
| Read aloud repeat + pause on phone call / audio focus loss | DONE. Repeat off / page / document. Pauses on audio-focus loss (calls, other players) and on headphones unplugged; resumes after a transient loss. Options sheet in View › "خيارات القراءة بصوت". |
| Dual-screen / foldable layout | **NOT DONE.** `androidx.window` is not on the compile classpath: `androidx.window:window:1.2.0` is only a *runtime* dependency of `material3-window-size-class` 1.3.1 (its `releaseApiElements` has no window entry). To do it, add `implementation("androidx.window:window:1.2.0")`, which is already in the Gradle cache, so there is nothing to download. Width-based split view and compare work on unfolded devices today. |

### Spec rows of MODULE_view4 not rebuilt this round
These were not in this round's key list. They stay **NOT DONE** and could be a later round:
- SHL-3 customise tabs / JSON; SHL-4 selection chips; SHL-6 second window (needs a manifest activity); SHL-7 tab menu; SHL-9 version history; SHL-10 crash recovery; SHL-12 Ctrl+wheel / right-click; SHL-13 accent colours.
- FILE-1…10 (properties, XMP, fonts, optimizer, MRC, save-as hub) and PREF-1…9 (More settings screen). The only exception is the stamp identity (PREF-3, PARTIAL: stored in view5 prefs).
- HOME-1/2 paste and snapshot.
- CMT-6 hide comments (needs a PageCanvas hook); CMT-10 comment clipboard; CMT-11 align / distribute; CMT-20 calculator tape; CMT-23 rich text; CMT-24 bubbles; CMT-26 smart pen.
- CMT-18 categories and PDF-page stamps; CMT-19 numbered / abjad tickmarks.
- VIEW-1/2 continuous facing; VIEW-4 marquee zoom; VIEW-8; VIEW-14 word count; VIEW-15 custom tints; VIEW-16 reading-order source for TTS. VIEW-13 advanced search belongs to the search agent. VIEW-17 is not feasible.
- HLP-2 AI help retrieval; HLP-3 video link.
- PRN-1 greyscale, scale and comment summary; PRN-2 poster / tile; PRN-3 multi-file print.
- A3 links, A4 layers, A5 labels, A6 initial view, A7 media, A8 portfolio; D2 shape recognition.

## Limitations
- The workbench works on one page at its own zoom, ignoring the reader's view rotation. Its items are written in one `applyInPlace` (pending reader annotations are saved first; undo through the existing done bar). Callouts and text boxes are not re-editable with our dialogs afterwards; AnnotEdit can move FreeText.
- Labels use Helvetica for Latin text and the bundled Naskh font otherwise. Glyphs the font lacks (for example ² in Naskh) are replaced (²→2) instead of failing.
- The reflow builder relies on PDFium text order. Images under 0.4 % or over 92 % of the page are skipped. Two-column layouts are read line by line from the top.
- The overlays (rulers, grid, reading ruler) assume the fit-width layout, as in view4.
- The trash keeps app-local files only. Documents opened from other apps are copies in our storage, so deleting them in Files never touches the user's original.
