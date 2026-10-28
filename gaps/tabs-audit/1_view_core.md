# Tabs audit 1: VIEW tab + reader core (read-only)

Scope: VIEW tab and the reader core every tab shares: top bar, document tabs, page modes/zoom/scroll, search, selection chips, side panels, banners, links, read aloud, reflow/text viewer, night/colour, split/compare, presentation/full screen, keys, command search, undo/redo, open/save/close.
Code base: main fa724fd (after the pull; reader/ files have not changed since my reads, and view5 changes were label-only).
Paths are relative to `app/src/main/java/com/smartdocs/editor/`.

---

## 1. Inventory

### 1a. VIEW tab bottom toolbar (ReaderToolbars.kt `ViewToolbar` 128-158, then View5ViewChips, then NveViewChips)

| Tab | Button | Handler file:line | Status |
|---|---|---|---|
| VIEW | View mode (sheet) | ReaderToolbars.kt:134 → ReaderDialogs.kt:152 → view/ViewSheet.kt:49 | DEFECT (V-17 persist) |
| VIEW | Fit page / Fit width | ReaderToolbars.kt:135 | OK |
| VIEW | Night mode | ReaderToolbars.kt:136 | DEFECT (V-17: not persisted) |
| VIEW | Page colour | ReaderToolbars.kt:137 → ReaderDialogs.kt:159 | DEFECT (V-17: night switch not persisted) |
| VIEW | Reflow | ReaderToolbars.kt:138 | DEFECT (V-10: duplicate) |
| VIEW | Auto flip | ReaderToolbars.kt:139 → ReaderDialogs.kt:173 | OK (Low V-30 icon) |
| VIEW | Rotate view | ReaderToolbars.kt:140 | OK |
| VIEW | Crop mode | ReaderToolbars.kt:142 | OK |
| VIEW | Magnifier (pan and zoom window) | ReaderToolbars.kt:145 | DEFECT (V-10: duplicate of Lens) |
| VIEW | Search | ReaderToolbars.kt:146 | OK (same as the top-bar search; intended) |
| VIEW | Panels (outline) | ReaderToolbars.kt:147 | OK |
| VIEW | Bookmarks | ReaderToolbars.kt:148 | OK |
| VIEW | Thumbnails | ReaderToolbars.kt:149 | OK |
| VIEW | Attachments | ReaderToolbars.kt:150 | OK |
| VIEW | Read aloud | ReaderToolbars.kt:151 | DEFECT (V-08 lifecycle; no "selected" state) |
| VIEW | AI | ReaderToolbars.kt:152 | OK (hidden when AI off) |
| VIEW | Share | ReaderToolbars.kt:153 → ReaderScreen.kt:196 | DEFECT (V-14 crash path) |
| VIEW | Print | ReaderToolbars.kt:154 → V5State.open(PRINT) (guarded) | OK |
| VIEW | Document info (INFO panel) | ReaderToolbars.kt:155 | DEFECT (V-21: same label, different target from the menu item) |
| VIEW | Tell me (command search) | view5/View5Host.kt:69 | OK |
| VIEW | Reflow (v5) | view5/View5Host.kt:70 | DEFECT (V-10 duplicate) |
| VIEW | Split view | View5Host.kt:71 → SplitCompare.kt:96 | DEFECT (V-24 blocking close) |
| VIEW | Compare | View5Host.kt:72 | DEFECT (V-24, V-35) |
| VIEW | Presentation | View5Host.kt:73 → Presentation5.kt:65 | OK (Low V-33) |
| VIEW | Auto scroll | View5Host.kt:74 | Low (V-34: forces continuous mode for good) |
| VIEW | Rulers / Grid / Reading ruler | View5Host.kt:77-79 | OK |
| VIEW | Lens | View5Host.kt:80 | DEFECT (V-10 duplicate) |
| VIEW | Overlay settings | View5Host.kt:81 | OK |
| VIEW | Read-aloud options | View5Host.kt:82 | DEFECT (V-19: holds the "tool memory" switch, which works only half) |
| VIEW | Zoom % (sheet) | nve/NveHost.kt:92 | OK |
| VIEW | Marquee zoom | NveHost.kt:93 | OK |
| VIEW | Fit height / Actual size | NveHost.kt:96-97 | OK |
| VIEW | Continuous facing | NveHost.kt:98 | OK |
| VIEW | Reverse view | NveHost.kt:102 | OK |
| VIEW | Word count | NveHost.kt:103 | OK |
| VIEW | Text viewer | NveHost.kt:104 → nve/NveTextViewer.kt:44 | DEFECT (V-04 copy bypass) |
| VIEW | Grid (NVE) | NveHost.kt:105 | DEFECT (V-10: second grid next to v5 Grid) |
| VIEW | Quad split | NveHost.kt:106 | OK |
| VIEW | Transitions | NveHost.kt:107 | OK |
| VIEW | Page snapshot / Area snapshot | NveHost.kt:108-109 | OK (COPY guarded in the result dialog) |
| VIEW | Paste | NveHost.kt:110 | OK |
| VIEW | Select all | NveHost.kt:111 | OK |
| VIEW | Signatures pane | NveHost.kt:112 | OK |
| VIEW | Spell check | NveHost.kt:113 | OK |
| VIEW | Customise toolbar | NveHost.kt:114 | OK |

The VIEW row holds about 45 chips (16 + 13 + 19) in one horizontally scrolling row. See V-26.

### 1b. Top bar (ReaderScreen.kt 237-281, Commands5.kt `V5TopActions` 160-172)

| Area | Button | Handler | Status |
|---|---|---|---|
| Top | Back arrow | ReaderScreen.kt:239 | DEFECT (V-02) |
| Top | Undo / Redo (long press: history) | HistoryUi.kt:37 | OK |
| Top | Save (only when dirty) | ReaderScreen.kt:242 | OK |
| Top | Doc tabs (phone) | view/ReaderTabs.kt:225 | OK |
| Top | Position back / forward | Commands5.kt:168-169 | OK |
| Top | Pinned commands (2 on phone, 6 on tablet; "measure,comments" by default) | Commands5.kt:165-170 | DEFECT (V-03 overflow) |
| Top | Tell me (bolt) | Commands5.kt:171 | OK |
| Top | Search | ReaderScreen.kt:245 | OK |
| Top | More menu: Save, History, Save as, Share, Print, Compress, OCR, Protect, Flatten, Redact page, OCR this page, Apply redactions, Go to page, Document info, Keep screen on, Settings, e-sign, Register connected, Tell me, Comment manager, Presentation, Shortcuts, Help, Edit tools, Save-as-more, Properties, Zoom, Word count, Paste, Snapshot, Spell, Signatures, Customise, Keys, Revert | ReaderScreen.kt:248-277, View5Host.kt:103-110, NveHost.kt:128-142 | DEFECT (V-03 unreachable on phones; V-21 three "properties" entries; V-28 Revert dialog inside the menu) |
| Top | Tablet doc tab strip (tap, ×, +, long press menu) | view/ReaderTabs.kt:176 | OK |
| Top | Tab row (VIEW…PAGES + user tabs) | ReaderToolbars.kt:60 | Low (V-36) |

### 1c. Selection chips (ReaderOverlays.kt `SelectionMenu` 83-117, plus NveSelectionChips nve/NveSelection.kt:150)

| Chip | Handler | Status |
|---|---|---|
| Copy | ReaderScreen.kt:298 (COPY guard) | OK |
| Highlight / Underline / Strikeout | ReaderState.markupFromSelection (ANNOTATE guard) | OK |
| Translate / Explain (AI) | ReaderScreen.kt:299 (COPY guard) | OK |
| Rewrite (ai6) | ReaderScreen.kt:303 → ReaderAi6.replaceSelection | DEFECT (V-11 duplicate) |
| Read from here | ReaderScreen.kt:300 | OK |
| Web search / Share text | ReaderScreen.kt:301 (COPY guard) | OK |
| Link | ReaderOverlays.kt:103 → edit5/ReaderLinks.kt:54 | OK (NavIo handles signed files) |
| PDF bookmark from selection | ReaderOverlays.kt:106 → edit5/ReaderNav.kt:36 | DEFECT (V-11: two "bookmark" chips) |
| Redact | ReaderScreen.kt:302 | OK |
| Squiggly / Note (NVE) | NveSelection.kt:163-170 | OK |
| Reading bookmark (NVE) | NveSelection.kt:171 | DEFECT (V-11) |
| Copy formatting / Copy table | NveSelection.kt:175-176 (guard inside) | OK |
| Select all / Column select / Export | NveSelection.kt:177-179 | OK |
| Edit text | NveSelection.kt:180 (MODIFY guard) | OK |
| Rewrite (NVE) | NveSelection.kt:181 → replaceInContent | DEFECT (V-11 duplicate; V-06 signed) |
| Word count | ReaderOverlays.kt:111 | OK |

About 22 chips. In full-screen mode (chrome hidden) the whole selection bar is hidden (V-29).

### 1d. Core surfaces

| Area | Item | Handler | Status |
|---|---|---|---|
| Search bar | Field, prev/next, case, whole words, replace toggle, result list | ReaderOverlays.kt:29; ReaderState.kt:758-787 | DEFECT (V-20) |
| Panels | Outline (search, find current, expand, wrap, size, print) | ReaderPanels.kt:90 | DEFECT (V-05 print bypass) |
| Panels | Bookmarks (add, search, delete) | ReaderPanels.kt:196 | OK |
| Panels | Comments list (filter, sort, expand, prev/next, more) | annot/CommentsPanel.kt | DEFECT (V-27 header clipped) |
| Panels | Thumbnails | ReaderPanels.kt:287 | OK |
| Panels | Attachments | attachments/ReaderAttachmentsPanel | not deep-audited |
| Panels | INFO + Layers / Security / Fields / Dests / Tags / Articles / Standards / Access / OCR / Connected | NpfPanels.kt:66-216 | OK (signed check in `change`); Low V-37 |
| Banners | Protected View / action inspector / restricted note | ReaderSecurityBanners.kt:34 | Low (V-38) |
| Banners | NVE doc banners, Redact marks, Portfolio prompt, XFA, OCR suggestion, recovery | ReaderScreen.kt:339-371 | DEFECT (V-18 duplicates) |
| Links | Tap link / file link / plain URL | PageCanvas.kt:409-411, ReaderScreen.kt:327, ReaderIntents.kt:23 | OK |
| Read aloud | TTS bar (prev, play, next, stop, rate) | ReaderOverlays.kt:121 | DEFECT (V-08) |
| Reflow | ReflowReader (v5) | view5/ReflowReader.kt:80 | DEFECT (V-13 perf); ReaderDialogs.kt:233 `ReflowView` is dead code |
| Keys | NveKeys → PageFlipKeys → V5Keys → G6Keys; Activity ReaderKeyBus | ReaderScreen.kt:311-318 | DEFECT (V-09) |
| Undo | Top bar, PropertyBar, History sheet, snackbar, Ctrl+Z | HistoryUi.kt, ReaderState.kt:419-459 | OK |
| Open | Password dialog, open error, repair | ReaderScreen.kt:321-325, 375 | OK |
| Save | save(), autosave on pause/interval, write-back, signed block dialog, Save as, revert | ReaderState.kt:486-563, ReaderScreen.kt:377-383 | DEFECT (V-01, V-02, V-07, V-12) |
| Close | ReaderHolder.onCleared → close() | ReaderState.kt:327-350, 869-878 | DEFECT (V-08, V-31) |

---

## 2. Defects

| id | tab | button/area | defect | severity | file:line | failure scenario | suggested fix |
|---|---|---|---|---|---|---|---|
| V-01 | core (save) | Save / autosave of filled form values | `ReaderState.save()` writes pending form values through `forms5.flushOnSave` → `FormValueWriter.write` → `FormOps.fillFields`, which does a full `doc.save(out)` (FormOps.kt:204). The same is true of `FieldVisibility.applyTo` and the image fields (FormValueWriter.kt:52). There is no `SignedSave` route, unlike annotations (AnnotationWriter / SignedSave.saveEdited). A `SignedDocumentException` would not reach `signedBlock` either: the generic catch at ReaderState.kt:498 turns it into a raw message. | Critical | ReaderState.kt:497-498; core/forms5/FormValueWriter.kt:45,52; core/pdf/FormOps.kt:204 | A form signed by the first signer is filled by the second person. Pressing back with autosave on, or Save, rewrites the whole file and silently invalidates the existing signature. A certified (DocMDP P=1) form gets rewritten with no warning. | Route form writes through `SignedSave` (incremental update; refuse when certified P<3 forbids form fill). In `save()` catch `SignedDocumentException` around `flushOnSave` and set `signedBlock`. |
| V-02 | core | Back button / top-bar back arrow / tab switch | With autosave on, back runs `saveThen { leave() }` and only leaves when `save()` returns true. When the save fails (signed or certified file sets `signedBlock`, annotations not saved, I/O error), every back press saves again and fails again. The discard dialog is never offered. "Save a copy" leaves `dirty` true, so the user is still stuck. `rememberTabSwitcher` is blocked the same way (`if (dirty && !save()) return`). | High | ReaderScreen.kt:129,142,239; view/ReaderTabs.kt:159 | Open a signed PDF, add a highlight, press back. The "signed — save a copy" dialog appears, and Cancel leaves you in the reader. Pressing back again does the same. The only ways out are killing the app or turning autosave off in Settings. | When `save()` returns false, show `DiscardDialog` (Save a copy / Discard / Cancel). After a successful "Save a copy", offer to leave. |
| V-03 | core (top bar) | Top-bar actions on phones | The actions row holds Undo + Redo (40dp each), Save (when dirty), the tabs icon, back/forward position (when there is history), 2 pinned commands (the default pins are "measure,comments"), Tell me, Search and More. That is 368dp in a clean state and up to 512dp, all in one unscrolled TopAppBar Row. On 360-411dp phones (and in tablet split-screen below 700dp) the last children, Search and the ⋮ More button, get no width and the title shrinks to nothing. | High | ReaderScreen.kt:240-279; view5/Commands5.kt:165-171; HistoryUi.kt:37 | Phone (360dp, Arabic): open a PDF and add a highlight. The ⋮ menu (Save as, Share, Print, Settings, Revert…) is cut off or invisible and cannot be tapped. | Default to 0 pins on phones, or fold the pins, position history and tabs into the ⋮ menu below 600dp. Always keep ⋮ visible, for example with a measured overflow. |
| V-04 | VIEW | Text viewer (NVE) | The text view is wrapped in an unconditional `SelectionContainer`, so on a document whose author forbids copying, the text can be selected and copied. Reflow uses `CopyableIf(guard.allows(COPY))`, and the viewer's own "save txt" button checks COPY, but selection does not. | High | nve/NveTextViewer.kt:91 | A no-copy PDF: VIEW → Text viewer → long-press → Copy puts the whole page text on the clipboard. | Use `CopyableIf(s.guard.allows(DocAction.COPY))` as ReflowReader does. |
| V-05 | VIEW (panel) | Outline → print icon → "Print page(s)/section" | `OutlinePrintMenu.print` calls `PrintPipeline.prepare` and `PdfPrint.print` directly, with no `guard.check(PRINT)`. The rest of the reader goes through `V5State.open(PRINT)`. | High | ReaderPanels.kt:166-175 | A PDF that forbids printing, or Protected View: Panels → Outline → printer icon on any bookmark → the system print dialog opens and prints. | `if (!state.guard.check(DocAction.PRINT)) return` at the start of `print()`. |
| V-06 | core | `applyInPlace` used by selection-bar Rewrite / Edit text, the AI agent, NVE panes and others (28 call sites) | `applyInPlace` checks ReaderGuard but never whether the file is digitally signed. Each op has to protect signatures itself. NavIo, CommentOps and AttachmentOps do; `ContentEdit.replaceText` (NVE Rewrite, `NveSelection.replaceInContent`) and `FindReplace.replaceHit` (ai6 Rewrite through QuickController) do a full rewrite. NpfPanels re-implements the check by hand (NpfPanels.kt:115). | High | ReaderState.kt:689-715; nve/NveSelection.kt (replaceInContent); ReaderAi6.kt:38-54 | On a signed contract, select text → Rewrite → Replace. The page content is rewritten and the signature becomes invalid. One undo step exists, but nothing warns the user. | In `applyInPlace` (or through a `signedPolicy` parameter), refuse with `SignedDocumentException` when `SignedSave.isSigned(f)` and the op is not incremental-aware, then show the same "save a copy" dialog. |
| V-07 | core (close) | Revert to saved (⋮) | `NveRecovery.revert` → `discardChanges()` → `popBackStack(); navigate(reader(sameUri))`. The new reader opens the same working file while the old `close()` is still running in `ioScope`: it closes the session, renames the stored-annotation backup back over the file, and disposes the history. The new session can load the file before the backup is put back. | Med | ReaderScreen.kt:273-277; ReaderState.kt:342-349; nve/shell/Recovery.kt:74 | You edit a saved annotation (it is taken out of the file), then choose Revert. The reopened document can show that annotation missing until the next reopen, and a save in that window writes the file without it. | Await the old reader's close/restore before navigating, for example with a `closeJob` that `open()` of the same file joins, or revert in place (restore backup, reopen session) without leaving the screen. |
| V-08 | core | Read aloud vs document switch/close | `ReadAloudController` is global and is not stopped in `ReaderState.close()`. Its provider lambda captures the old `ReaderState` and file. `ReaderScreen` also has `LaunchedEffect(tts.page, tts.state) { … state.jumpTo(tts.page) }`, which runs in the new reader too. | Med | ReaderState.kt:327-350; ReaderScreen.kt:125,163 | Read aloud document A, then switch tab to B (or open a linked PDF). A keeps being read, B jumps to A's current page each time A turns a page, and the TTS bar in B shows A's page count. | Stop TTS in `close()` (or when `tts docTitle != file.name`), and only follow `tts.page` when the session belongs to this reader. |
| V-09 | core (keys) | Space / Ctrl+PgDn / Ctrl+PgUp in facing and cover modes | NveKeys (first in the key chain) maps NEXT_PAGE/SPACE_DOWN to `jumpTo(currentPage + 1)`. In FACING mode, `currentPage` is the first page of the spread, so +1 maps back to the same spread and the page never advances. COVER gets stuck at page 2. Arrow keys use `vx.flip`, which is spread-aware, so they work. | Med | nve/NveKeys.kt:185-186 | Tablet + keyboard, facing mode: Space does nothing. Verified by running it (section 3). | Use `s.vx.flip(±1)` for these keys, which is what G6Keys/ViewExtras already do. |
| V-10 | VIEW | Duplicate tools in one row | (a) "Reflow" appears twice: ReaderToolbars.kt:138 (`R.string.reflow` "إعادة التدفق") and View5Host.kt:70 (`v5_reflow` "قراءة مرنة"). Same toggle, different names. (b) "Magnifier" (vx.magnifier, a pan-and-zoom window, ReaderToolbars.kt:145) and "Lens" (v5 lens, View5Host.kt:80) are two magnifier implementations with the same ZoomIn icon. They can be on together, and crop disables only one. (c) "Grid" (v5 overlay, View5Host.kt:78) and "Grid" (NVE grid sheet, NveHost.kt:105). (d) Zoom search icon vs Search icon (NveHost.kt:92 uses Icons.Search for zoom). | Med | as listed | Users see two reflow chips with different Arabic names, two zoom-glass chips that do different things, and two grid chips. TalkBack reads the zoom chip as a search icon. | Keep one chip per feature (hide the v5/NVE duplicates in VIEW or merge them), and give the zoom chip a ZoomIn/percent icon. |
| V-11 | VIEW (selection) | Duplicate selection chips | Two AI rewrite chips with different engines: `ai6r_rewrite_chip` → ReaderAi6.replaceSelection/FindReplace, and `nv_rewrite` → NveSelectionOps.replaceInContent/ContentEdit. Two bookmark chips: `nbm_bm_from_selection` writes a PDF outline item, while NVE `add_bookmark` writes an app DB reading bookmark. Their icons look alike (BookmarkAdd) and the labels are near-identical. | Med | ReaderOverlays.kt:98,106; nve/NveSelection.kt:171,181 | The user picks "add bookmark" and it never appears in the Outline panel (it went to the app DB), or the other way round. The two rewrites behave differently with fonts and multi-block selections. | Keep one rewrite (ai6 has the font/lossy warnings). Rename the bookmark chips to "Bookmark in PDF" and "Reading bookmark", or keep one. |
| V-12 | core | Two crash-recovery systems | NpfReaderEffects (filesDir/npf_recovery, every `NpfPrefs.recoveryMinutes`, default 5) and NveRecovery (filesDir/recovery, `NveShellPrefs.recoveryMinutes`) both run `exportWithAnnots` of the whole document periodically. After a crash both offer the copy: a modal AlertDialog with `onDismissRequest = {}`, plus the bottom NveRecoveryBanner. Neither export runs under `saveMutex`. | Med | NpfReaderHooks.kt:124-138,147; nve/shell/Recovery.kt:35-45,83-92; ReaderScreen.kt:101,348,435 | For a 100 MB PDF with unsaved edits, two full copies are written every few minutes (I/O, storage, battery). After a crash the user answers a modal dialog, then sees a second "recovered copy" banner for the same work. | Keep one implementation (NveRecovery has pruning) and one setting. Delete the other directory. |
| V-13 | VIEW | Reflow (v5 ReflowReader) build | Pages are ordered `(startPage until n) + (0 until startPage)` and chunked by 12, and `PageGraphics.images` is called with `chunk.min()..chunk.max()`. The chunk that wraps around the end covers 0..n-1, so every page is scanned. Each chunk also reloads the full PDDocument without MemoryUsageSetting. | Med | view5/ReflowReader.kt:98-101 | Open reflow at page 496 of a 500-page Arabic book: the first chunk scans all 500 pages' graphics before anything shows (seconds to minutes, risk of OOM). Verified (section 3). | Pass the chunk's page set (or contiguous sub-ranges), and load with `MemoryUsageSetting.setupMixed` or reuse one document. |
| V-14 | VIEW / ⋮ | Share | `if (showShare5) { … LaunchedEffect(Unit) { f = outputFile() } }`: `outputFile()` → `exportWithAnnots` can throw (I/O, AnnotationWriter, forms). An exception in LaunchedEffect crashes the app. If it returns null, `showShare5` stays true and later Share taps do nothing. The temp "share" file is never deleted. | Med | ReaderScreen.kt:431-432,183-189 | Share with pending annotations on a nearly full device crashes the app. | `runCatching { outputFile() }.onFailure { error = …; showShare5 = false }`, reset `showShare5` when null, and delete the temp file on dismiss. |
| V-15 | core | Page direction mismatch (docRtl) | PageView uses `rtl = docRtl ?: layoutDirection` for spread order and tap zones, but HorizontalPager follows `LocalLayoutDirection` (app language). An English (L2R) PDF in the Arabic UI: swipe-to-next goes right-to-left while tapping the right edge goes NEXT, and facing pages are placed L2R. An Arabic (R2L) document in the English UI is the reverse. | Med | PageView.kt:65,138,243,390 | In the Arabic UI, single-page mode on an English book: swiping left goes back while tapping the right edge goes forward. | Wrap the pager in `CompositionLocalProvider(LocalLayoutDirection provides if (rtl) Rtl else Ltr)`. |
| V-16 | core | Two "remember settings per tool" systems | `ReaderState.selectTool` uses `annot.ToolMemory` (prefs "tool_settings", saved on switch). `View5Host.ToolMemoryEffect` (prefs "view5_tools", saved on every change) also restores on each tool change, after ToolMemory, overriding colour/opacity/thickness/size. The "tool memory" switch (Read-aloud options sheet!) only disables the second, and only for readers opened afterwards (read once in `LaunchedEffect(s)`). | Med | ReaderState.kt:791-797; view5/View5Host.kt:127-146,168; annot/CommentPrefs.kt:14 | The user turns "remember tool settings" off and tools keep remembering their colours. The two stores can disagree, so the colour changes after the tool is picked. | Remove ToolMemoryEffect (ToolMemory covers more fields), and move the switch to Comment preferences, read live. |
| V-17 | VIEW | Night mode / page colour persistence | Night is persisted only when the View-mode sheet closes (ReaderScreen.kt:385) or a colour preset is picked. The VIEW chip (ReaderToolbars.kt:136), the PageColorSheet night switch (ReaderDialogs.kt:166), the reflow night button, Ctrl+N and the "night" command do not persist it. The same sheet dismissal also saves `continuousScroll = viewMode == CONTINUOUS`, so FACING/COVER are saved as "not continuous" (single) unless NpfPrefs.defaultLayout is set. | Low | ReaderToolbars.kt:136; ReaderDialogs.kt:166; ReaderScreen.kt:385; view5/Commands5.kt:75 | You turn night on with the chip; the next document opens in day mode. You choose Facing in the sheet; the next document opens in Single. | Persist nightMode in one place (a `snapshotFlow` on `state.nightMode`). Store the view mode itself, not a boolean. |
| V-18 | core (banners) | Duplicate / stacked banners | NveDocBanners shows JavaScript (also shown by ReaderSecurityBanners' action-inspector banner), pending redactions (also shown by RedactMarksBanner, BottomEnd), and portfolio (also shown by PortfolioPrompt), plus forms/PDF-A/UA/fonts/3D. These are full-width stacked cards fixed at top 96dp, over the ToolHint (top 8), the XFA banner (48) and the OCR suggestion. Dismissal is per reader instance only. | Med | ReaderScreen.kt:339-348,371; nve/panes/NveDocBanners.kt:41-53; ReaderSecurityBanners.kt:44 | A JS form with redaction marks on a phone in landscape: 3-5 banners cover most of the page, two of them say the same thing, and they come back every time the file is reopened. | Drop the JS/redaction/portfolio notices from NveDocBanners (or the older ones), collapse the rest into one expandable notice, and persist dismissals per file hash. |
| V-19 | VIEW | "Read-aloud options" chip hosts the tool-memory switch | The switch is in the wrong place and only half works (see V-16). | Low | view5/View5Host.kt:168-171 | Users cannot find the setting. | Move it (V-16). |
| V-20 | core (search) | Search close / clear | `closeSearch()` does not cancel `searchJob`. A search still running (250ms debounce or a long search) then sets `searchHits` and `jumpTo()` after the bar was closed. Clearing the field while a job is in flight cancels it before `searching = false`, so the spinner stays on forever. | Low | ReaderState.kt:758-787 | Type a word and close the search at once: the view jumps and hit highlights appear with no search bar. | `searchJob?.cancel(); searching = false` in `closeSearch()` and in the blank-query branch. |
| V-21 | VIEW / ⋮ | "Document info" naming | VIEW chip `tool_doc_info` opens the INFO side panel. ⋮ `tool_doc_info` opens QuickAction.PROPERTIES (an edit dialog). ⋮ `nv_properties` (the same Arabic text "خصائص المستند") opens the NVE DocInfo sheet. That makes three different screens under one name. | Low | ReaderToolbars.kt:155; ReaderScreen.kt:264; nve/NveHost.kt:133 | The same label opens different screens depending on where it is tapped. | Name them distinctly ("Info panel" / "Edit properties") or route all three to one. |
| V-22 | core | Shared `ttsHighlight` / global state | `ReaderAi6.ttsHighlight` is a process-global `mutableStateOf`. With V-08, sentence rects of document A are drawn on document B when page indices coincide (`PageCanvas.kt:272` checks only the page number). | Low | ReaderAi6.kt:127; PageCanvas.kt:272 | Visible after a tab switch while reading aloud. | Key it by file or clear it on reader close. |
| V-23 | core | Hard-coded Arabic prompt / dead state | `aiText` is never set to non-null, so the hard-coded Arabic `initialQuestion` is dead code; it would also show Arabic in the English UI. `ReflowView` (ReaderDialogs.kt:233) and `AnnotationsPanel` (ReaderPanels.kt:234) are unused. | Low | ReaderScreen.kt:83,221,416; ReaderDialogs.kt:233 | Not reachable today. | Delete, or use a string resource. |
| V-24 | VIEW | Split / Compare close | `DisposableEffect { onDispose { otherSession?.close() } }`: the blocking `close()` (`runBlocking` on the PDFium mutex) runs on the main thread while pane renders may hold the lock. | Low | view5/SplitCompare.kt:111 | Closing Compare while a large page renders freezes the UI, with a possible ANR on big scans. | `scope.launch(NonCancellable) { closeAsync() }` from an app scope. |
| V-25 | core | Keyboard focus | Key handling (`onPreviewKeyEvent`) is attached to the page Box and focus is requested only when `state.session` changes. After any dialog, sheet, search field or panel, focus is not returned, so Ctrl+Z/F/S, Space and arrows stop working until the user taps the page. (Volume keys still work through ReaderKeyBus.) | Low | ReaderScreen.kt:229-230,311 | Tablet with keyboard: open Go to page, close it, press Ctrl+F: nothing happens. | Request focus again when dialogs and search close, or handle keys at Activity level through ReaderKeyBus. |
| V-26 | VIEW | Chip overload | The VIEW row has about 45 chips in one horizontal scroll, with duplicates (V-10). The ToolsToolbar similarly appends all EDIT5 tools. Customise toolbar can hide only the 16 base VIEW chips (`nveHidden`); the v5/NVE chips cannot be hidden. | Low | ReaderToolbars.kt:128-157; View5Host.kt:66-83; NveHost.kt:89-115 | On a phone the useful chips sit 30+ swipes away, and customisation cannot remove the extra ones. | Group into sub-menus and let customisation cover every chip id. |
| V-27 | VIEW (panel) | Comments panel header | One unscrolled Row: Filter chip + Sort chip + 5 IconButtons (240dp) inside a 340dp tablet side panel (default `panelWidthDp`) or a 360dp phone sheet. The trailing ⋮ (export/import XFDF) gets clipped. | Med | annot/CommentsPanel.kt:111-135 | Tablet landscape, Comments panel: the ⋮ More button is cut off and export/import cannot be reached. | Make the row `horizontalScroll`, or move expand/collapse into the ⋮ menu. |
| V-28 | ⋮ | Revert confirm dialog | The AlertDialog is composed inside the DropdownMenu content and `menu` stays open. Dismissing the menu (tap outside, rotation, back) removes the dialog, and a remembered state inside a dropdown resets each time it opens. | Low | ReaderScreen.kt:273-277 | You tap Revert, then tap outside the dialog: the dialog and menu vanish together, which is fine. A back press, though, closes only the popup and leaves no dialog. | Hoist `nveRevert` to the screen and close the menu first. |
| V-29 | core | Full screen (chrome hidden) | The selection bar, TTS bar and auto-flip controls live in the bottomBar and disappear with `chrome = false`. Long-press selection then has no actions. `chrome` toggles only when `tool == null`, so a tool picked with a keyboard shortcut in full screen makes taps stop restoring the bars. | Low | ReaderScreen.kt:293-306,330-331 | Full screen → long-press a word: it is highlighted, but there is no copy/highlight menu. | Show SelectionMenu/TtsBar as overlays independent of `chrome`, or restore chrome on selection. |
| V-30 | VIEW | Auto-flip bar icons | "Faster" uses Icons.Add and shortens the interval (sec − 10); "Slower" uses Icons.Remove. The +/− symbols are the inverse of the seconds shown. | Low | view/ScreenControls.kt:161-170 | Users press "+" expecting more seconds. | Use FastForward/Slow icons, or put "+10s/−10s" on the seconds. |
| V-31 | core | Process death with autosave off | Pending annotations live only in the ViewModel. Autosave on ON_PAUSE runs only when `settings.autoSave` is on, and `NpfPrefs.autoSaveMinutes` defaults to 0. With autosave off, edits since the last recovery copy (up to 5 min) are lost on process death, and the recovery copy is a separate file, not the document. | Low | ReaderScreen.kt:103-105; NpfReaderHooks.kt:139-146 | Backgrounded tablet killed by the OS: the edits come back only as a "recovered copy" file, or not at all. | Write a recovery copy on ON_STOP when dirty, whatever the autosave setting. |
| V-32 | core | Protected View banner | It has no dismiss and takes about 120dp permanently above the pages (plus the security note), which is heavy on phones in landscape. | Low | ReaderSecurityBanners.kt:36-42 | A small phone in landscape loses about a third of the page area. | Make it collapsible to a chip. |
| V-33 | VIEW | Presentation | It is not immersive (system bars remain; the dialog uses decorFitsSystemWindows=false without WindowInsetsController hide), and night/page colour are ignored. | Low | view5/Presentation5.kt:90 | The "full screen" presentation still shows the status and navigation bars. | Hide system bars through the dialog window's insets controller. |
| V-34 | VIEW | Auto scroll | It forces `viewMode = CONTINUOUS` and never restores the user's facing/single mode afterwards. | Low | view5/View5Host.kt:59; Commands5.kt:61-64 | In facing mode, start then stop auto scroll: you are left in continuous mode. | Remember the previous mode and restore it on stop. |
| V-35 | VIEW | Compare with an encrypted PDF | `PdfSession.open(ctx, f, null)` gives no password prompt; it only shows the raw exception text. | Low | view5/SplitCompare.kt:108 | Comparing against a password PDF only shows an English error string. | Catch PasswordRequired and show PasswordDialog. |
| V-36 | core | Tab row when the current tab is hidden | If the user hides the tab they are in (customise), `idx` falls back to 0 (VIEW highlighted) while BottomToolbar still shows the hidden tab's tools. PAGES is never shown as selected (it navigates away). | Low | ReaderToolbars.kt:69-77 | Confusing highlight after customising. | Switch `state.tab` to the first visible tab when its own tab is hidden. |
| V-37 | VIEW (INFO) | INFO → OCR report | It scans up to 500 pages' text sequentially on every `renderVersion` change (each save), on the main dispatcher, using the reader's text cache. | Low | NpfPanels.kt:221-224 | Opening INFO→OCR on a large book after each save causes a long spinner and jank. | Run it on IO, cache it per file version, cancel it when the panel changes. |
| V-38 | core | Night mode vs a11y filter vs page tint | `NpfReader.pageFilter(night)` returns null in night mode, and the tint is skipped when the a11y filter is on. Thumbnails, Presentation, PagePane (split) and ManualCrop ignore the page colour and a11y colours, so the look differs between views. | Low | PageCanvas.kt:125,269; ReaderPanels.kt:295; Presentation5.kt:140 | Sepia page colour: the thumbnails and split view are white. | Apply the same tint helper in the secondary renderers. |

Items checked and found OK, so they are not listed above: `ReaderGuard.check` on tool selection (setter), `markupFromSelection`/`addAnnot` ANNOTATE guard, COPY guard on copy/share/web/translate/AI/copy-table/copy-formatting/export/snapshot, PRINT guard in `V5State.open`, encryption kept on full saves (`PdfOps.load` → `reprotect`), annotation save through `SignedSave` with `signedBlock`, `stillPendingAfterSave` (DI-05/DI-36), undo blocked while stored annots are taken out (AN-04), keep-screen-on timeout, orientation lock reset on exit, `configChanges` in the manifest (no recreate on rotation), PageSpread mapping, TapZones RTL, the ReaderTabs FIFO limit with pinned tabs, the restore-last-session flag, the link-trust gate (DocSecurityGate + NveTrust), and the en/ar string sets (values-en is missing only 4 keys: ac_policy_ar/en, npf_url_community/labs; none are in the reader).

---

## 3. Verified by running

Harness: `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\tabsaudit\h\`. It compiles with K2JVMCompiler, using the real `PageSpread` (copied verbatim from ui/reader/view/ViewExtras.kt) and the real `TapZones.kt`. Output:

```
FACING  Space(NveKeys)=[0, 0, 0, 0, 0, 0, 0]   flip(1)=[0, 2, 4, 6, 8, 8, 8]
COVER   Space(NveKeys)=[0, 1, 1, 1, 1, 1, 1]   flip(1)=[0, 1, 3, 5, 7, 9, 9]
SINGLE  Space(NveKeys)=[0, 1, 2, 3, 4, 5, 6]   flip(1)=[0, 1, 2, 3, 4, 5, 6]
reflow chunk 0 pages=12 PageGraphics range=0..499 (500 pages scanned)
reflow chunk 1 pages=12 PageGraphics range=7..18 (12 pages scanned)
TapZones rtl=false x=0.9 -> NEXT ; rtl=true -> PREV
```

- V-09 is confirmed: in facing mode, Space/Ctrl+PgDn never leave the first spread, and cover mode sticks at page 2. `vx.flip` works.
- V-13 is confirmed: with reflow started at page 495 of 500, the first chunk asks PageGraphics for the whole document.
- TapZones RTL mirroring is correct. V-15 is about the pager not using the same `rtl`.
- String-set diff (values vs values-en, script `scratchpad\strdiff.py`): 7970 keys, 4 missing, none used by the reader.
- V-01 was established by code reading, not by running. The chain is `ReaderState.save` → `FormFill.flushOnSave` → `FormValueWriter.write` → `FormOps.fillFields` (`load` + `doc.save(out)`, FormOps.kt:204), with no `isSigned` or `saveIncremental` anywhere in core/forms5 or FormOps. A real-PDF reproduction would need a signed AcroForm sample; there was none among the chemistry PDFs.

---

## 4. Counts per severity

| Severity | Count | IDs |
|---|---|---|
| Critical | 1 | V-01 |
| High | 5 | V-02, V-03, V-04, V-05, V-06 |
| Med | 12 | V-07, V-08, V-09, V-10, V-11, V-12, V-13, V-14, V-15, V-16, V-18, V-27 |
| Low | 20 | V-17, V-19, V-20, V-21, V-22, V-23, V-24, V-25, V-26, V-28, V-29, V-30, V-31, V-32, V-33, V-34, V-35, V-36, V-37, V-38 |
| Total | 38 | |
