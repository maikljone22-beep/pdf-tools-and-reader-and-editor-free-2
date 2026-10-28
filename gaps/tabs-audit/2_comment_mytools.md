# Tabs audit 2: COMMENT tab and CUSTOM ("My tools") tab

Read-only audit of main `D:\pdf\SmartDocs-project-v0.4.0` (no gradle, no edits). Paths below are relative to `app/src/main/java/com/smartdocs/editor/`.
Method: code reading of every handler, plus one PyMuPDF experiment (section 3).

---

## 1. Inventory: every button in these tabs

Status values: OK, DEFECT (with defect ids, see section 2), DUP (the same feature also exists as another button).

### 1a. COMMENT tab: tool row (`ui/reader/ReaderToolbars.kt:162-192`)

| Tab | Button | Handler file:line | Status |
|---|---|---|---|
| COMMENT | Highlight | ReaderToolbars.kt:163,183 → selectTool | OK (CM-06) |
| COMMENT | Underline | ReaderToolbars.kt:164 | OK |
| COMMENT | Strikeout | ReaderToolbars.kt:165 | OK |
| COMMENT | Squiggly | ReaderToolbars.kt:166 | OK |
| COMMENT | Note | ReaderToolbars.kt:167 | OK |
| COMMENT | Free text | ReaderToolbars.kt:168 | DUP (CM-05: Typewriter / Callout / Edit "Add text") |
| COMMENT | Ink (pen) | ReaderToolbars.kt:169 | OK |
| COMMENT | Eraser | ReaderToolbars.kt:170; PageCanvas.kt:236,435-437,477 | DEFECT CM-08, CM-11 |
| COMMENT | Line | ReaderToolbars.kt:171 | DUP CM-05 ("Line & arrow" workbench chip) |
| COMMENT | Arrow | ReaderToolbars.kt:172 | OK |
| COMMENT | Rectangle | ReaderToolbars.kt:173 | OK |
| COMMENT | Oval | ReaderToolbars.kt:174 | OK |
| COMMENT | Stamp | ReaderToolbars.kt:184 → ReaderScreen.kt:390 V5StampPicker | DEFECT CM-27 (Low) |
| COMMENT | Measure (canvas, not saved) | ReaderToolbars.kt:176; PageCanvas.kt:222,431 | DUP CM-05 (2nd "قياس" chip) |
| COMMENT | Replace text | Annot2Toolbar.kt:34 → Annot2Host.pick :126 | OK |
| COMMENT | Insert text (caret) | Annot2Host.kt:129-135,176 | OK (CM-24 keyboard path) |
| COMMENT | Polyline | Annot2Host.kt:172 | OK (CM-34) |
| COMMENT | Polygon | Annot2Host.kt:172 | OK |
| COMMENT | Cloud | Annot2Host.kt:172 | OK |
| COMMENT | Attachment | Annot2Host.kt:194 → Annot2Dialogs.kt:58 | OK |
| COMMENT | Sound | Annot2Host.kt:196 → RecordDialog Annot2Dialogs.kt:181 | DEFECT CM-18 |
| COMMENT | Video | Annot2Host.kt:150,195; VideoHost.kt:303-336 | DEFECT CM-26 (Low) |
| COMMENT | Snapshot | Annot2Host.kt:287,372 → SnapshotSheet Annot2Dialogs.kt:252 | DEFECT CM-12 (print), CM-23, CM-32 (dup) |
| COMMENT | Multi-select | Annot2Host.kt:198,249 | DEFECT CM-25; icon same as "Area highlight" (CM-05) |
| COMMENT | Tickmarks | CommentExtras.kt:434 | DUP CM-05 ("Tick" workbench chip) |
| COMMENT | More (⋯) | Annot2Toolbar.kt:41-60 | see 1b |
| COMMENT | Measure (workbench) | View5Host.kt:91 → Workbench.kt:54 | DEFECT CM-01, CM-03, CM-16; DUP CM-05 |
| COMMENT | Callout | View5Host.kt:92 | DEFECT CM-01, CM-03 |
| COMMENT | Typewriter | View5Host.kt:93 | DEFECT CM-01, CM-03 |
| COMMENT | Tick | View5Host.kt:94 | DEFECT CM-01, CM-03, DUP CM-05 |
| COMMENT | Area highlight | View5Host.kt:95 | DEFECT CM-01, CM-03 |
| COMMENT | Line & arrow | View5Host.kt:96 | DEFECT CM-01, CM-03, DUP CM-05 |
| COMMENT | Comment manager | View5Host.kt:97 → CommentManager.kt:85 | DEFECT CM-02, CM-12; DUP CM-05 |
| COMMENT | Measurements list | View5Host.kt:98 → MeasureListSheet | OK |
| COMMENT | Comments (panel) | ReaderToolbars.kt:191 → ReaderPanels.kt:70 CommentsPanel | DEFECT CM-09; DUP CM-05 |

### 1b. COMMENT tab: "More" menu (`Annot2Toolbar.kt:52-59`, `CommentExtrasUi.kt:183-204`)

| Tab | Item | Handler | Status |
|---|---|---|---|
| COMMENT | Summary (PDF) | Annot2Dialogs.kt:111-123 | DEFECT CM-12, CM-36 |
| COMMENT | Export XFDF / Export FDF | Annot2Dialogs.kt:98-109 | DEFECT CM-12, CM-17, CM-36 |
| COMMENT | Import | Annot2Dialogs.kt:68-76 → fileOp | DEFECT CM-15 |
| COMMENT | Merge comments from PDF | Annot2Dialogs.kt:77-84 | DEFECT CM-15 |
| COMMENT | Handwriting → text | Annot2Host.kt:395 | OK |
| COMMENT | Hide / show comments | CommentExtras.kt:174 | DEFECT CM-11 |
| COMMENT | Paste / Paste in place | CommentExtras.kt:200 | OK (CM-31 naming) |
| COMMENT | Note indicators | CommentExtras.kt:131 | OK |
| COMMENT | Copy encircled text | CommentExtras.kt:85 | OK |
| COMMENT | Pop-up note preferences | CommentExtrasUi.kt:228 | OK |
| COMMENT | Previous / Next comment | CommentExtras.kt:462 | DEFECT CM-33 (Low) |
| COMMENT | Open / close all pop-ups, reset pop-ups | CommentExtras.kt:414-415 | DEFECT CM-15 |
| COMMENT | E-mail FDF (all) | CommentExtrasUi.kt:202 → exportSelected | DEFECT CM-12, CM-13 |
| COMMENT | Calculator tape | CommentExtrasUi.kt:244 | OK |

### 1c. COMMENT tab: property bar and creation extras (`ReaderToolbars.kt:347-390`, `Annot2Toolbar.kt:67-116`, `CommentExtrasUi.kt:64-141`)

| Tab | Button | Handler | Status |
|---|---|---|---|
| COMMENT | Undo / Redo | ReaderToolbars.kt:353-354 | DUP (top bar HistoryButtons), CM-07 |
| COMMENT | 12 colour dots, custom colour, hex field | ReaderToolbars.kt:356-371 | DEFECT CM-07, CM-30 |
| COMMENT | Opacity 25/50/75/100 | ReaderToolbars.kt:361 | OK |
| COMMENT | Keep creating | ReaderToolbars.kt:365 | OK (persisted NpfPrefs) |
| COMMENT | Thickness / font size sliders | ReaderToolbars.kt:376-383 | DUP CM-34 |
| COMMENT | Poly Done / undo vertex / cancel, fill, cloud 1/2 | Annot2Toolbar.kt:84-108 | OK |
| COMMENT | Multi-select count + cancel | Annot2Toolbar.kt:110 | OK |
| COMMENT | Fill, dash, no border, rect cloud 0-3, square, ink→shapes | CommentExtrasUi.kt:73-100 | DEFECT CM-06 (values not remembered when tool auto-clears) |
| COMMENT | B / I / U / S, text format dialog | CommentExtrasUi.kt:101-107 | OK |
| COMMENT | Note / attachment icon chips | CommentExtrasUi.kt:108-112 | OK |
| COMMENT | Tick row: numbers/letters/symbols, size, select all, prev/next | CommentExtrasUi.kt:126-141 | OK |
| COMMENT | Line caption, line start / end | CommentExtrasUi.kt:116-121 | OK |

### 1d. COMMENT tab: selection bar, placement bar, panels, dialogs

| Tab | Button | Handler | Status |
|---|---|---|---|
| COMMENT | Sel ⋮: Properties | CommentExtras.kt:305-355 | OK (CM-28) |
| COMMENT | Sel ⋮: Lock / Unlock | CommentExtras.kt:357 | DEFECT CM-28 (Low) |
| COMMENT | Sel ⋮: Cut / Copy / Paste in place | CommentExtras.kt:178-222 | OK |
| COMMENT | Sel ⋮: Align / distribute / same size (11) | CommentExtras.kt:247 | OK |
| COMMENT | Sel ⋮: Rotate left / right / custom | CommentExtras.kt:400 | OK |
| COMMENT | Sel ⋮: Place on pages | CommentExtras.kt:226 | OK |
| COMMENT | Sel ⋮: Flatten | CommentExtras.kt:388 | DEFECT CM-14 |
| COMMENT | Sel ⋮: Export selected XFDF/FDF, e-mail FDF | CommentExtras.kt:476 | DEFECT CM-12, CM-13, CM-17 |
| COMMENT | Placement bar: size, opacity, place on pages, done | CommentExtrasUi.kt:376-430 | OK (CM-35 bitmap copies) |
| COMMENT | Comments panel: sort, expand/collapse, prev/next, ⋮ | CommentsPanel.kt:123-135 | OK |
| COMMENT | Comments panel: delete pending / delete stored | CommentsPanel.kt:194, 251 | DEFECT CM-09 |
| COMMENT | Comments panel: author+colour, check, status, replies | CommentsPanel.kt:208-286 | DEFECT CM-15 |
| COMMENT | Comment manager: CSV / Word / text summary / PDF summary / highlights / copy highlights | CommentManager.kt:105-149 | DEFECT CM-12 |
| COMMENT | Comment manager: search, filters, colour, checked, sort | CommentManager.kt:151-168 | OK |
| COMMENT | Comment manager: row check / restyle | CommentManager.kt:174, 191-201 | DEFECT CM-02 |
| COMMENT | Workbench: close, page ◀▶, undo, Save (n) | Workbench.kt:99-115 | DEFECT CM-01, CM-03, CM-16 |
| COMMENT | Workbench: tools, snap, grid snap, scale, leader, edit last, 45°, ١٢٣, fill, line style, mark size, palette, Done | Workbench.kt:117-170 | OK (scale save: CM-01) |
| COMMENT | Snapshot sheet: copy, share, save PNG, print | Annot2Dialogs.kt:268-285 | DEFECT CM-12, CM-23 |
| COMMENT | Record dialog: record / stop, pick audio, cancel | Annot2Dialogs.kt:212-227 | DEFECT CM-18 |
| COMMENT | Audio player | Annot2Dialogs.kt:232 | OK (CM-35) |

### 1e. CUSTOM ("My tools") tab

| Tab | Button | Handler | Status |
|---|---|---|---|
| CUSTOM | Tab label (custom name) | ReaderToolbars.kt:77 | OK |
| CUSTOM | Favourite tool chips (any catalog tool) | Toolset.kt:120-122 → clickTool :101 | OK |
| CUSTOM | Property bar (when a tool is active) | ReaderToolbars.kt:121 | OK |
| CUSTOM | Customise | Toolset.kt:124 → ToolsetEditSheet :132 | OK |
| CUSTOM | Sheet: rename tab | ShellSettings.kt:122 | OK |
| CUSTOM | Sheet: remove all | ShellSettings.kt:123 | OK (no confirm, Low) |
| CUSTOM | Sheet: long-press drag reorder, remove | Toolset.kt:155-174 | DEFECT CM-30 (a11y) |
| CUSTOM | Sheet: add / remove from catalog (35 tools, 4 groups) | Toolset.kt:178-195 | OK |
| CUSTOM | User tabs (ribbon) in tab row | ReaderToolbars.kt:80-83 | DEFECT CM-22, CM-24 |
| CUSTOM | User tab toolbar chips | UserTabs.kt:64-71 | DEFECT CM-21 |
| CUSTOM | Customise toolbar sheet: show/hide, up/down, reset, View chips | ToolbarCustomize.kt:44-84 | OK |
| CUSTOM | My tabs: add / edit / delete | UserTabs.kt:91-104 | DEFECT CM-21, CM-22 |
| CUSTOM | Customisation export | UserTabs.kt:80-83,100 | DEFECT CM-20 |
| CUSTOM | Customisation import | UserTabs.kt:84-88,101 | DEFECT CM-20, CM-37 |

---

## 2. Defects

| id | tab | button / area | defect | sev | file:line | failure scenario | suggested fix |
|---|---|---|---|---|---|---|---|
| CM-01 | COMMENT | Workbench chips: Measure, Callout, Typewriter, Tick, Area highlight, Line & arrow; scale save | `WorkbenchModel.writeAll` does `PdfOps.load` + `doc.save(out)` (full rewrite) through `applyInPlace`; `ScaleStore.write` goes the same way. No SignedSave, no certified-file refusal. | High | ui/view5/WorkbenchModel.kt:157-174; ui/view5/WorkbenchDialogs.kt:98; ReaderState.kt:692-720 | A user opens a signed contract, adds a callout or a measurement in the workbench, and taps Save. The file is fully rewritten and every signature becomes invalid. A certified (DocMDP P=1) file is changed without any warning. | Write through `PdfOps.loadRaw` + `SignedSave.requireAnnotationsAllowed` + `SignedSave.saveEdited(doc, out, pw, pages)` (as CommentOps7.edit does). Same for ScaleStore.write. |
| CM-02 | COMMENT | Comment manager: tick a row ("checked"), restyle | `CommentOps5.setChecked` and the restyle block both do a full `doc.save(out)`. The Comments panel does the same "checked" through `CommentStore.setChecked` (incremental, signed-safe), so there are two implementations of one feature. | High | core/view5/CommentOps5.kt:72-87; ui/view5/CommentManager.kt:174, 195-201 | Ticking "checked" in the Comment manager on a signed PDF breaks the signatures. The same tick in the Comments panel does not. | Drop CommentOps5.setChecked and call CommentStore.setChecked. Route restyle through a SignedSave-aware edit helper. |
| CM-03 | COMMENT | Workbench (all 6 chips plus the snapping) | The workbench maps the screen to the PDF as `P(x/scale, pageH - y/scale)`, using `pageSizes` (PDFium size with /Rotate applied), and writes the result as user space. `pageFrames` (/Rotate and crop-box origin, audit AN-06) is ignored. Glyph snapping uses `charBoxes` (user space), which does not match. | High | ui/view5/WorkbenchCanvas.kt:48-49; ui/view5/Workbench.kt:63-69,78 | On a scanned page with /Rotate 90, or a cropped page, a callout, typewriter, measure or area highlight lands far from where it was placed and turned 90°. **Verified (section 3).** | Use `PageGeometry` with `state.pageFrames[page]` (as PageCanvas does) for toScreen/toPdf. |
| CM-04 | COMMENT | Image-annotation properties (Comment properties of image stamps) | `ImageAnnotProps5.applyFile` does `PdfOps.load` + `d.save(out)` (full save). | High | core/annot/ImageAnnotProps5.kt:134-139 | Changing the opacity or border of a placed image stamp on a signed file invalidates its signatures. | Use SignedSave.saveEdited. |
| CM-05 | COMMENT | Tool row as a whole | Duplicate tools from three merged modules: two "قياس / Measure" chips with the same Straighten icon (canvas measure, which is never saved, and the workbench); Line vs "Line & arrow"; Tickmarks vs Tick; Free text vs Typewriter vs Callout (plus Edit tab "Add text"); Multi-select and Area highlight share the HighlightAlt icon; Comments panel vs Comment manager (different features, e.g. different "checked" writers). | Med | ReaderToolbars.kt:162-191; View5Host.kt:88-99; Toolset.kt:81 | The user taps the first "قياس". The measurement disappears on the next tap or page and is never saved. They cannot tell the two chips apart, and two "checked" paths give different results on signed files. | Merge: keep one Measure (the workbench), one tickmark tool, one comment list. Give distinct icons and labels. |
| CM-06 | COMMENT / CUSTOM | Per-tool settings memory | There are two memories. `ReaderState.selectTool` uses `annot.ToolMemory` (prefs "tool_settings": colour, opacity, width, font, fill, dash, line ends, cloud, B/I/U, icon). The view5 `ToolMemoryEffect` (prefs "view5_tools": colour, opacity, width, font) restores again after it. The "Remember tool settings" switch (placed in the Read-aloud options sheet) disables only the view5 one, and only after reopening. `addAnnot` sets `tool = null` directly, so ToolMemory never captures the extras of tools that auto-deselect. | Med | ReaderState.kt:388,791-796; ui/reader/annot/CommentPrefs.kt:14-58; ui/view5/View5Host.kt:127-146,184 | (a) The user turns "Remember tool settings" off, but highlight colours are still restored. (b) The user sets Rect to dashed with fill, draws once (the tool auto-clears), then picks Rect again: dash and fill have reverted to older values while the colour is new. | Keep one store (ToolMemory). Save it from a tool-change observer, not only in selectTool. Honour the switch in selectTool. Move the switch to the Comment property bar or Settings. |
| CM-07 | COMMENT | Property bar with no tool | The property bar is always shown in COMMENT (`PropertyBar(state)` unconditionally). A colour picked with no tool active is overwritten by ToolMemory when the tool is then picked. Undo/redo here duplicates the top-bar HistoryButtons. | Low | ReaderToolbars.kt:115, 353-356; ReaderState.kt:795 | The user taps red, then Highlight, and gets yellow. | Show the bar only when `tool != null` (as in EDIT, FILL_SIGN and CUSTOM), or apply a "pre-pick" colour after the restore. |
| CM-08 | COMMENT | Eraser | It ignores locked annotations: pending ink goes through `removeAnnot` (no lock check) on every drag move, and stored ink goes through `eraseStoredInk` (no `locked` filter). Each stroke hit is a separate undo step. | Med | PageCanvas.kt:236, 435-437, 477; ReaderState.kt:384-416 (removeAnnot), 580-605 (eraseStoredInk) | A reviewer locks an ink signature-like scribble. The eraser removes it anyway, from the pending list and from the saved file. Erasing 10 strokes needs 10 undos. | Skip `locked` in `inkHits` and in eraseStoredInk's candidate filter. Batch the erase per drag with `batchAnnots`. |
| CM-09 | COMMENT | Comments panel delete (pending / stored) | Delete ignores the lock (the panel shows the lock icon at :220 but delete still runs) and asks for no confirmation. | Med | ui/reader/annot/CommentsPanel.kt:194, 251 | A locked stored comment is deleted from the panel in one tap, which bypasses section 7.58 locking. | Call `cx.refuseLocked(CTarget(...))` before both deletes. |
| CM-10 | COMMENT (seam with VIEW) | Any drawing tool after a View-tab drag mode | `NveState.dragMode` (marquee zoom, area snapshot, paste-place) is not cleared on a tab switch. `NvePageOverlays` covers the whole page and consumes every tap and drag. | Med | ReaderScreen.kt:284-287 (onTab); ui/nve/NveOverlays.kt:74-104 | View tab → "Area snapshot" → switch to Comment → Ink: drawing does nothing and the drag makes a snapshot instead. | In onTab, set `NveState.of(state).dragMode = null; pasteText = null`. Also hide the overlay when `state.tool != null`. |
| CM-11 | COMMENT | Hide comments | While hidden, every creation tool stays active. New pending annotations are not drawn (`if (showAnnots)`), and the eraser erases invisible ink. Nothing tells the user why. | Med | CommentExtras.kt:174; PageCanvas.kt:93, 274 | The user hides comments, forgets, and draws a highlight: nothing appears. They report "highlight broken" and draw several invisible copies, which all get saved. | On toggleHidden, clear the tool. When a tool is picked while hidden, auto-show (or toast). |
| CM-12 | COMMENT | Text-export paths: Comment manager CSV / Word / summary / highlights / "copy highlights"; XFDF/FDF export; Summary PDF; export selected; snapshot "Print" | None of these call `guard.check(DocAction.COPY)` (snapshot print does not check PRINT), whereas the Snapshot tool itself requires COPY (ReaderGuard.kt:169) and text selection copy is guarded. | Med | CommentManager.kt:105-149; Annot2Dialogs.kt:98-123; CommentExtras.kt:476; Annot2Dialogs.kt:278-285 | On a PDF whose author forbids copying, the user creates highlights, then uses "Copy highlights" or "Export highlights". The protected text goes to the clipboard or a .txt file. On a print-forbidden PDF, snapshot → Print prints the page. | Gate the highlight and text exports with COPY. Gate snapshot print with PRINT (ReaderRestrictions). |
| CM-13 | COMMENT | Export selected / e-mail FDF | `CommentOps7.keepOnly` loads the document and sets `isAllSecurityToBeRemoved = true`, writing a **decrypted full copy** to `tempDir/cx_export_<nanos>/<name>.pdf`. It is never deleted. | Med | CommentExtras.kt:482-488; core/annot/CommentOps7.kt:245-251 | Each export of a password-protected file leaves an unencrypted copy of the whole document in app storage. | Export XFDF directly from the filtered in-memory doc, or delete `dir` in a finally block. Never remove security on a temp copy. |
| CM-14 | COMMENT | Selection ⋮ → Flatten (signed file) | Flatten changes page content but is written as an incremental "annotation" update. The DocMDP check `requireAnnotationsAllowed` allows P=3, but content changes are not allowed after signing. | Med | CommentExtras.kt:388-398; core/annot/CommentOps7.kt:178-183,197 | Flattening a comment on a signed PDF: Acrobat then reports the signature as invalidated by changes. | Refuse flatten on signed files with "save a copy" (signedBlock UI). |
| CM-15 | COMMENT | All stored-comment edits through `Annot2Host.fileOp` (reply, status, checked, delete, import, merge, align, lock, props, paste, flatten, pop-ups) | `fileOp` swaps the file and writes it back, but never calls `SaveHooks.onSaved` (cloud auto-upload and listeners) or `guard.afterSave`. Save, erase, deleteExisting and applyInPlace all call SaveHooks. `fileOp` also never sets `saving`, so the save button and undo stay enabled during the write. | Med | ui/reader/annot/Annot2Host.kt:448-462 vs ReaderState.kt:597,612,713 | On a cloud-linked document, replying to a comment or importing an XFDF is never uploaded. The trusted-document hash is lost after the change, so the JS banner returns. | Add `.also { SaveHooks.onSaved(s.app, f) }` and `s.guard.afterSave(f)`. Set `saving` while the op runs. |
| CM-16 | COMMENT | Workbench lifecycle | `remember { WorkbenchModel(v) }` lives inside a Dialog. `V5State.surface` survives (it is held by ReaderState), so after a rotation or split-screen resize the workbench reopens **empty**. Unsaved measurements and callouts are lost without warning. A 2000×(≤6000) bitmap per page is also never recycled. | Med | ui/view5/Workbench.kt:58,65-69 | On the Tab S7+ the user measures 6 items, rotates the tablet, and they are gone. | Keep `WorkbenchModel` in V5State (per ReaderState). Recycle the old bitmap in produceState's awaitDispose. |
| CM-17 | COMMENT | XFDF/FDF export, export selected | The temp file waiting for the SAF result is held in `remember` (`exportTmp`, `pendingOut`). After activity recreation while the picker is open, the result arrives with null and nothing is written (the temp file leaks). The "exported n" snackbar shows before the user has even picked a destination. | Med | Annot2Dialogs.kt:85-90,105-107; CommentExtrasUi.kt:212-216 | Tablet rotated while the Save dialog is open: the export silently produces nothing. A user who cancels still sees "Exported 12 comments". | Keep the pending file in Annot2Host (ViewModel-scoped) and show the snackbar after `writeTo` succeeds. |
| CM-18 | COMMENT | Sound recording | Tapping outside the dialog (`onDismissRequest`) or rotating (DisposableEffect → `stop(discard = true)`) throws the recording away with no confirmation. `recordTap` survives, so the dialog reappears empty. | Med | Annot2Dialogs.kt:186,197 | The user records a 2-minute voice note, brushes outside the dialog, and the audio is gone. | Use `DialogProperties(dismissOnClickOutside = false)` while recording. On dispose during recording, keep the file and add it. |
| CM-19 | COMMENT (and CUSTOM) | Toolbar layout | `ToolChip` has a fixed width of 64dp with one-line ellipsis, so Arabic labels are cut ("علامات مرقمة", "مدير التعليقات", "وسيلة شرح", "تحديد متعدد", "خط وسهم"). The Comment row has about 36 chips in one horizontal scroll with no overflow hint, and on a phone in landscape the property bar, Annot2PropertyRow, CreationExtrasRow (up to 3 rows for lines) and tool row stack to about 5 rows. | Med | ReaderToolbars.kt:89-105,115; CommentExtrasUi.kt:116-121 | On a 360dp phone in landscape, with the Arrow tool active, the page area is about 1/3 of the screen. Many chip labels are unreadable in Arabic. | Allow 2-line labels or a wider chip. Group the tools (shapes / text / media) into dropdown chips. Collapse the extras behind a toggle on compact height. |
| CM-20 | CUSTOM | Customisation export / import | `Customization` holds tab order, hidden tabs and chips, user tabs, home favourites and the custom-tab name, but **not the "My tools" toolset** (prefs "toolset", ToolsetStore). Importing on a new device restores the tab name but an empty or default tool list. | Med | ui/nve/shell/UserTabs.kt:44-56; ui/reader/annot/Toolset.kt:86-98 | The user exports their setup and imports it on a phone: "My tools" shows the 5 defaults. | Add a `readerToolset` field (ReaderTool names) to Customization's JSON and apply it with ToolsetStore.save. |
| CM-21 | CUSTOM | User tabs (ribbon) | The tool list is the home `allTools` without `AiGate` (ToolsToolbar filters it). Every chip goes through `navigateTool`, which saves pending edits, snapshots for undo and applies `actionForTool` → default MODIFY, even for tools that do not touch the open file (scan, images_to_pdf, blank…). | Med | UserTabs.kt:64-71,122; ReaderScreen.kt:202-219; core/security/ReaderRestrictions.kt:96-105 | (a) With AI disabled in Settings, AI tools can still be added to a user tab and opened. (b) On a restricted PDF, the "Scan" chip in a user tab says "restricted by the author". (c) Tapping "Blank PDF" saves the user's pending annotations into the original first. | Filter with `AiGate.allowsTool`. Open document-independent tools with `nav.navigate(Routes.tool(id))` without the guard, save or snapshot. |
| CM-22 | CUSTOM | Delete or import away the selected user tab | `NveState.userTab` keeps the deleted id. `NveUserTabToolbar` returns early, so the bottom bar is empty, and the tab row highlights the built-in tab even though its toolbar is not shown. | Low | UserTabs.kt:66,94; ReaderToolbars.kt:73-74,111-112 | Customise toolbar → delete the tab being used: no toolbar until another tab is tapped. | In NveUserTabToolbar, if the tab is missing, reset `userTab = null`. |
| CM-23 | COMMENT | Snapshot sheet | Three icon+text buttons in a fixed Row with no wrap or scroll: clipped on small phones, worse in Arabic. The bitmap is never recycled on dismiss. | Low | Annot2Dialogs.kt:268-276 | On a 360dp phone, "حفظ PNG" is cut off or squeezed. | Use FlowRow or icon buttons. Recycle in onDismiss. |
| CM-24 | COMMENT | Keyboard shortcuts for comment tools | `NveKeys.tool()` calls `s.selectTool(t)`, not `annot2.pick(t)`, for CARET and REPLACE: the selection-based caret/replace and their default colours are skipped. It also leaves `NveState.userTab` set, so the user-tab toolbar stays visible while a Comment tool is active, with no property bar. | Low | ui/nve/NveKeys.kt:171,215-216 | With a text selection, Ctrl/shortcut "Insert text" waits for a tap instead of acting on the selection. With a user tab open, the shortcut activates Ink with no visible property bar. | Route through `clickTool(state, actions, t)` and clear `userTab`. |
| CM-25 | COMMENT | Multi-select across tab switch | `onToolChanged(null)` keeps `multi` (it only clears for a non-null tool), so the selection and its action bar survive a switch to another tab. | Low | Annot2Host.kt:155-158; ReaderScreen.kt:286 | Lasso 3 comments, go to the Edit tab: the selection frame stays and a drag moves the comments there. | Clear `annot2.multi` and `edit` on a tab change. |
| CM-26 | COMMENT | Video tool | A picked video waiting for its box (`video.picked`) is never cleared on a tool change. Re-picking the Video tool later skips the picker and places the old file. | Low | Annot2Host.kt:150; VideoHost.kt:320,336 | The user picks a video, cancels by choosing another tool, and next time gets the old video with no picker. | Clear `picked` in onToolChanged when the new tool != VIDEO. |
| CM-27 | COMMENT | Stamp chip, then cancel the picker | `selectTool(STAMP)` keeps `pendingImage` from the IMAGE or SIGNATURE tool. When the picker is cancelled, `if (pendingImage == null) selectTool(null)` does not fire, so STAMP stays active with the old picture. | Low | ReaderState.kt:800; ReaderScreen.kt:390 | Edit → Add image (photo) → Comment → Stamp → cancel → tap the page: the photo is placed as a "stamp". | Clear `pendingImage` when entering STAMP, or track "picked in this session". |
| CM-28 | COMMENT | Selection ⋮ Lock/Unlock; Properties on a multi-selection | Both Lock and Unlock are always listed, whatever the current state. On a multi-selection with mixed lock states, Properties writes the first item's lock state to all items. | Low | CommentExtrasUi.kt:150-151; CommentExtras.kt:310-347 | The user opens Properties for a mixed set and taps OK without touching anything: every item gets locked. | Show one toggle. Use a tri-state lock in PropsDialog and only apply it when changed. |
| CM-30 | COMMENT / CUSTOM | Accessibility | `ColorDot` has no contentDescription (TalkBack announces 12 unlabeled buttons). The ToolsetEditSheet add/remove icons have `null` descriptions (only the row is clickable). Reorder works only by long-press drag, with no move up/down alternative. | Low | ReaderToolbars.kt:393-400; Toolset.kt:171-189 | A TalkBack user cannot pick a colour or reorder "My tools". | Add colour names, describe the add/remove state, and add move up/down actions (as ToolbarCustomize already has). |
| CM-31 | COMMENT (seam VIEW) | "Paste" | There are two different pastes with the same label: the Comment tab pastes the in-app CommentClipboard, while the View tab's NVE "Paste" reads the system clipboard (image → IMAGE tool, text → FreeText). | Low | CommentExtrasUi.kt:188-190; ui/nve/NveSelection.kt:189-212 | The user copies a comment, then uses View → Paste: "clipboard empty" or the last system text is pasted. | Rename one ("Paste comment" / "Paste from clipboard"), or let the NVE paste fall back to CommentClipboard. |
| CM-32 | COMMENT (seam VIEW) | Snapshot (two implementations) | The Comment SNAPSHOT tool requires COPY (ReaderGuard.actionFor). The View tab's NVE "Area snapshot" and "Page snapshot" make the same PNG with no guard. | Med | ReaderGuard.kt:169; ui/nve/NveOverlays.kt:98,172; ui/nve/NveSheets.kt:255 | On a copy-restricted PDF, the Comment snapshot is refused but View → Area snapshot copies the region. | Make both call `guard.check(COPY)`, or keep one implementation. |
| CM-33 | COMMENT | Previous / next comment | It only walks the stored annotations (`s.existing`) and skips pending ones. | Low | CommentExtras.kt:462-471 | After adding 5 comments (not saved yet), "Next comment" says "no comments". | Merge pending annots (sorted the same way) into the list. |
| CM-34 | COMMENT | Polygon / polyline / cloud thickness | Two thickness sliders show at once with different ranges (PropertyBar "more" 0.5–24, Annot2PropertyRow 1–12). | Low | ReaderToolbars.kt:372-378; Annot2Toolbar.kt:94-95 | A width set to 18 in one slider shows as clamped 12 in the other. | Show a single slider. |
| CM-35 | COMMENT | Bitmaps | The placement-bar opacity slider makes a new full bitmap copy on each change, never recycled. The Workbench page bitmap and the snapshot bitmap are also never recycled. | Low | CommentExtrasUi.kt:408-412; Workbench.kt:65; Annot2Dialogs.kt:252 | Repeated opacity tweaks on a large stamp add memory pressure on phones. | Recycle the previous bitmap. |
| CM-36 | COMMENT | Export XFDF/FDF, summary, export selected | Each first runs `if (dirty && !save())`, writing pending edits into the original file even when autosave is off and the user has not decided to save. | Low | Annot2Dialogs.kt:100,114; CommentExtras.kt:479 | With autosave off, "Export FDF" silently commits the edits the user meant to discard. | Export from `exportWithAnnots` to a temp copy instead of saving the original. |
| CM-37 | CUSTOM | Customisation import | It accepts `*/*`, reads the whole file into memory on the main thread (`readBytes()` in the result callback), and reports nothing specific on wrong files. | Low | UserTabs.kt:84-88 | Picking a large file by mistake janks or OOMs. | Limit the size (for example 256 KB) and read on Dispatchers.IO. |
| CM-38 | COMMENT | Tab change to PAGES | Opens organize via `navigateTool` without `selectTool(null)`, so the active comment tool is still active on return. | Low | ReaderScreen.kt:285 | The user returns from Organize with the eraser still active. | Clear the tool before navigating. |

(The CM-29 id is not used: it was merged into CM-06.)

---

## 3. Verified by running

* **CM-03 (Workbench coordinate mapping on rotated pages).** I copied `_الملخص_الشامل_في_الكيمياء_للصف_ثالث_ثانوي_الوحدة_الأولى.pdf` to the scratchpad (`tabsaudit/c2/in.pdf`), set page 1 to /Rotate 90 with PyMuPDF and used the Workbench formula: displayed size W×H = 841.9×595.3 (PDFium-style, rotation applied), tap at displayed (100, 100), `P = (x, H − y)`, written as a user-space /Rect `[100 465 160 495]` (a 60×30 box). I rendered with and without annotations and diffed the pixels:
  `annotation drawn at displayed x 464..494 y 100..158 pt (intended x 100..160, y 70..100)`.
  The box is ~360 pt off horizontally and turned 90° (30×58 instead of 60×30). Scripts: `scratchpad/tabsaudit/c2/rot.py`, `diff.py`.
* **Strings.** I extracted all 624 `R.string` ids used by ReaderToolbars, ui/reader/annot/*, View5Host, Workbench*, CommentManager, MeasureListSheet, StampPicker5, StampExtras, UserTabs, ToolbarCustomize, FavoritesUi, AnnotEditUi and ReaderPanels, and checked them against `res/values` (Arabic default) and `res/values-en`: **none are missing** in either. The remaining text problems are truncation (CM-19) and hard-coded literals ("A4", "Letter", "S/M/L", "45°", calculator keys), which are acceptable.
* Everything else was found by code reading (no gradle, per the brief).

---

## 4. Counts per severity

| Severity | Count | ids |
|---|---|---|
| Critical | 0 | |
| High | 4 | CM-01, CM-02, CM-03, CM-04 |
| Med | 17 | CM-05, CM-06, CM-08, CM-09, CM-10, CM-11, CM-12, CM-13, CM-14, CM-15, CM-16, CM-17, CM-18, CM-19, CM-20, CM-21, CM-32 |
| Low | 16 | CM-07, CM-22, CM-23, CM-24, CM-25, CM-26, CM-27, CM-28, CM-30, CM-31, CM-33, CM-34, CM-35, CM-36, CM-37, CM-38 |
| Total | 37 | |

The most important seams: the three merged comment stacks (base reader tools, MODULE_annot2 / section 7, view5 workbench) each have their own save path. Only the annot2 path (CommentOps7 / CommentStore) is signed-file safe, while the view5 workbench and comment manager do full rewrites. The workbench also ignores page rotation and crop. Each stack also has its own memory and clipboard: two tool memories, two clipboards, two snapshots and two comment lists.
