# 02 — Navigation / discovery and annotation / editing / study workflows (prefix AN)

## Scope & method

Read-only audit of the stable snapshot `D:\pdf\audit-wt` (commit da74d31). All paths below are relative to
`app/src/main/java/com/smartdocs/editor/` unless they start with `app/` or a project file name.

Read in full or in the relevant parts:
- History (newest commit da74d31): `core/history/DocHistory.kt`, `core/history/UndoStack.kt`, `MODULE_history_r5.md`,
  every history call site in `ui/reader/ReaderState.kt`, `ui/reader/AnnotEdit.kt`, `ui/reader/annot/MultiSelect.kt`,
  `ui/edit/EditController.kt`, `ui/reader/ReaderScreen.kt`.
- Reader and annotations: `ui/reader/ReaderState.kt`, `ReaderScreen.kt`, `PageCanvas.kt`, `PageGeometry.kt`,
  `TextSelectionLogic.kt`, `ReaderOverlays.kt`, `ReaderPanels.kt`, `ReaderDialogs.kt`, `PageView.kt` (jump handling),
  `ui/reader/view/ReaderTabs.kt`, `ui/reader/annot/Toolset.kt`, `Annot2Dialogs.kt` (export), `core/pdf/Annotations.kt`,
  `core/pdf/PdfSession.kt`, `core/files/DocumentRepository.kt` (localFile / writeBack).
- Search: `core/search/ArabicNormalizer.kt`, `TextMatcher.kt`, `SearchPrefs.kt`, `FullTextIndex.kt`, `IndexWorker.kt`,
  `IndexText.kt`, `ui/search/GlobalSearchScreen.kt` (navigation part).
- Redaction: `core/redact/TrueRedactor.kt`, `RedactApplier.kt`, `RedactMath.kt`, `RedactMarks.kt` (appearance),
  `PageInspector.kt` / `Sanitizer.kt` (grep level), `ui/security/RedactScreen.kt`, `RedactState.kt`,
  `ui/search/ReaderSearchExtras.kt` (reader marks), `core/pdf/PdfOps.redact`.
- Content editing: `core/pdf/ArabicShaper.kt`, `core/edit/EditBidi.kt`, `core/edit/ContentEdit.kt`,
  `core/edit/ContentRewriter.kt`, `ui/edit/EditController.kt`.
- Page organize: `ui/pages/OrganizeState.kt`, `OrganizeScreen.kt` (grep), `core/pdf/PdfOps.kt` page operations.
- Bookmarks / outline: `core/data/AppDatabase.kt` (ReadingBookmark), `ReaderPanels.BookmarksPanel`,
  `ui/edit5/BookmarksScreen.kt`, `core/edit5/OutlineStore.kt` (head), `ui/edit/OutlineEditor.kt` (grep).

Not verified / limits:
- No device or emulator: everything about rendering position, gesture feel and Navigation-Compose disposal timing is
  traced in code only and listed under "Needs device testing".
- PDFium behaviour (`FPDF_GetPageWidthF` returns the rotated crop-box size; `FPDFText_GetCharBox` returns unrotated
  user-space coordinates) is taken from PDFium's documented semantics and from the project's own comment in
  `core/pdf/PdfOps.kt:402` ("PDFium size: already rotated by /Rotate"); not executed.
- `ui/stamps/**`, `ui/signature/**`, `core/edit5/**` screens other than bookmarks, `core/review/**` were only skimmed;
  no findings are claimed for them beyond what is listed.
- `core/search/ArabicTextFix.kt` is in flux; it is only referenced, findings touching it are marked "(in-flux area)".

## Capability inventory

| Feature | Status | Evidence |
|---|---|---|
| In-document search, Arabic folding (tashkeel, hamza/alef forms, ى/ي, digits, tatweel, presentation forms, visual-order reversal) | works (strong) | `core/search/ArabicNormalizer.kt:27-93`, `TextMatcher.kt:184-218` |
| Search options (case, whole word, ة=ه, diacritics switches) | works | `ReaderOverlays.kt:53-58`, `SearchPrefs.kt:333-342` |
| Search result list + prev/next + highlight of current hit | partial — jumps to page only, not to the hit position; no progressive results | `ReaderState.kt:595-599`, `PageView.kt:189-193` (AN-17, AN-18) |
| Search history / recent queries | missing | no store found (grep `searchHistory|recentQueries`: none) |
| Cross-file full-text search (FTS4 index, WorkManager) | partial — misses visual-order Arabic files, no in-reader highlight on open | `FullTextIndex.kt:104-133`, `IndexText.kt:301-310`, `GlobalSearchScreen.kt:114` (AN-15, AN-16) |
| Index staleness | works (size+mtime check; re-index 5 s after save via touchRecent; periodic 12 h) | `FullTextIndex.kt:44-46`, `IndexWorker.kt:264-271` |
| Reading bookmarks (viewer) | partial — DB only, keyed by working-copy URI, not remapped on page delete/reorder, no undo on delete | `AppDatabase.kt:33-40`, `ReaderPanels.kt:114-140` (AN-25) |
| Outline (TOC) view | works (PDFium) | `PdfSession.kt` `outline()` |
| Outline editing | partial — reader editor saves in place; edit5 bookmarks screen saves only a copy and has no unsaved-changes guard | `ui/edit/OutlineEditor.kt:232,288`, `ui/edit5/BookmarksScreen.kt:67-109` (AN-26) |
| Highlight / underline / strike / squiggly | works on text pages; silently does nothing on scanned pages; wrong position on rotated / offset-crop pages | `PageCanvas.kt:429-436` (AN-06, AN-20) |
| Ink / shapes / notes / free text / stamps / image placement | works (pending in memory, written as real PDF annotations with /AP) | `ReaderState.kt:279-308`, `core/pdf/Annotations.kt:70-89` |
| Eraser | partial — erases only unsaved (pending) ink | `PageCanvas.kt:214` (AN-13) |
| Storage model | in-PDF annotations (PdfBox, full rewrite), not a DB overlay | `Annotations.kt:70-89` |
| When written | on explicit Save, on Back (autosave on), on ON_PAUSE (autosave on), before tools; NOT on in-app navigation | `ReaderScreen.kt:90-93,118-130,180-195` (AN-01) |
| Atomic write of working copy | yes (sibling temp + rename) | `ReaderState.kt:387-390,458` |
| Atomic write-back to the user's original (content://) | NO — in-place truncate + copy | `DocumentRepository.kt:94-96` (AN-02) |
| Edit / move / resize / restyle / delete existing annotations | works with caveats (stored ones are taken out of the file and re-written; lossy) | `AnnotEdit.kt:107-150` (AN-11, AN-12) |
| Colour / tool memory | missing — one shared colour/opacity/width, reset every open | `ReaderState.kt:112-115,605-619` (AN-21) |
| Unified undo / redo (da74d31) | partial — works within one reader session; lost on any navigation away / tab switch; ordering bugs with converted annotations | AN-03, AN-04, AN-05, AN-08, AN-27 |
| Undo across restart | not supported (by design; stale folders deleted) | `DocHistory.kt:183-195` |
| Accidental annotation while scrolling (stylus vs finger) | missing — any one-finger drag draws while a drag tool is active; no palm rejection / stylus-only | `PageCanvas.kt:172,315` (AN-14) |
| Annotation list / filter / sort | works (type + author filter, page/date/author sort) | `ui/reader/annot/CommentsPanel.kt:81-130` |
| Export XFDF / FDF / CSV / summary PDF | works (saves pending first) | `Annot2Dialogs.kt:100-122`, `ui/view5/CommentManager.kt:112` |
| Content edit vs annotations | distinct modes, but content edits are written back immediately and cannot be discarded with "Discard changes" | `ReaderState.kt:508-530`, `ReaderScreen.kt:315` (AN-19) |
| Editing existing text, Arabic shaping & numbers | partial — real bidi for digits/Latin; Persian letters not shaped; marks reordered before base | `EditBidi.kt:128-145`, `ArabicShaper.kt:12-26` (AN-23, AN-24) |
| Redaction | TRUE content removal for text glyphs / paths / images in the page stream — but removed or blanked images and forms stay in the file's resources, /ActualText is kept, sanitising is off by default | AN-07, AN-09, AN-10 |
| Organize pages delete/rotate/reorder | works with in-screen undo + exit guard; reorder/paste rebuild the document (drops outline, forms, encryption) | `OrganizeState.kt:194-219`, `PdfOps.kt:93-101` (AN-22) |

## Findings

### AN-01 Unsaved annotations are silently lost when leaving the reader through in-app navigation
- Type: data-integrity
- Status: CONFIRMED (traced in code); disposal timing needs device test
- Severity: Critical      Priority: P0
- Evidence: `ui/reader/ReaderState.kt:654-656` — `val state = remember(uri) { ReaderState(...) }` and `DisposableEffect(uri) { onDispose { state.close() } }` inside the nav destination. `close()` (`ReaderState.kt:243-254`) closes the session and calls `history.dispose()`; it never calls `save()`. Autosave only runs on `ON_PAUSE` (`ReaderScreen.kt:92`), on Back (`ReaderScreen.kt:128,214`), and in `navigateTool` (`ReaderScreen.kt:192` `if (state.dirty) saveThen(go)`). Several navigations skip all of these: `nav.navigate(Routes.SETTINGS)` (`ReaderScreen.kt:240`), e-sign create / connected register (`:242-243`), following a link to another file (`:286`), the XFA banner (`:292`), the AI outline (`:302,309`), AI citations / podcast / `openFile` (`:336,341,344`). In-app navigation does not fire ON_PAUSE. Navigation-Compose removes the previous destination from composition, so `remember` state goes away and `onDispose` runs.
- User impact / scenario: a student highlights 40 passages, then opens Settings from the ⋮ menu to change the theme and comes back: every highlight is gone, with no prompt. Worse, if they had started moving a stored annotation (it is taken out of the file, AnnotEdit convert), that annotation is now missing from the working file. For `file://` documents the working file is the user's own file (`DocumentRepository.kt:57`), so it is lost for good.
- Proposed fix: in `close()`, if `dirty` and the user did not choose discard, call `save()` in `ioScope` (NonCancellable) before closing the session. Better: hoist `ReaderState` into a `ViewModel` scoped to the back-stack entry so navigation does not destroy it, and route every `nav.navigate` from the reader through `saveThen`.
- Effort: M     Regression risk: Med     Dependencies: AN-03 (same root cause)
- Validation: instrumented test — add an annotation, navigate to Settings, go back, and assert the annotation exists (in `annots` or in the file).

### AN-02 Write-back to the original is a non-atomic in-place overwrite; a kill mid-write then propagates the truncated file over the good working copy
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Critical      Priority: P0
- Evidence: `core/files/DocumentRepository.kt:94-96` — `cr.openOutputStream(origin, "wt")` and then `local.inputStream().copyTo(o)`. The original is truncated first and rewritten in place. On the next open, `localFile` (`:58-70`) re-imports when `srcSize != known.length()`: it copies the (now truncated) original into a temp file and `renameTo(target)` over the intact working copy `known`. Write-back runs after every save, every undo/redo (`ReaderState.kt:393,433,523,541`) and on ON_PAUSE autosave, which is exactly when Android may kill the process.
- User impact / scenario: the user highlights and switches apps. The autosave's write-back to Drive/Downloads is cut off by a low-memory kill. Next launch the document shows as damaged or empty, and the good local copy has been overwritten by it.
- Proposed fix: (1) In `localFile`, never overwrite an existing working copy that is newer than the last successful write-back. Keep the "last written-back size/hash" in `origins` and ask the user when the original changed. (2) For write-back, write to a temp document in the same folder through DocumentsContract and rename it where the provider supports that; otherwise at least validate the result (read the length back, check the `%%EOF` tail) and retry. Keep the working copy as the source of truth until write-back is verified.
- Effort: M     Regression risk: Med     Dependencies: file-management audit
- Validation: unit test with a fake ContentResolver that throws mid-stream. Assert the next `localFile` keeps the working copy and reports a conflict.

### AN-03 Unified undo history (da74d31) is wiped on every navigation away and every tab switch; the "full-screen tool = one undo step" feature cannot work
- Type: bug
- Status: CONFIRMED (traced in code); needs device test
- Severity: High      Priority: P1
- Evidence: `ReaderState.kt:130` creates `DocHistory` per `ReaderState`. `close()` → `history.dispose()` (`:252`) → `dir.deleteRecursively()` (`core/history/DocHistory.kt:165-170`). `navigateTool` stores `externalBase` in the same `ReaderState` (`ReaderState.kt:553-557`) and then navigates (`ReaderScreen.kt:188`). The reader leaves composition, `onDispose` → `close()`, and the snapshot folder including `externalBase` is deleted. On return a NEW `ReaderState` is built: `openedStamp` is taken from the already-changed file, so `reloadIfChanged` (`:560-569`) sees no change and `externalBase` is null anyway. Tab switching pops the reader inclusively (`ui/reader/view/ReaderTabs.kt:155-157`). MODULE_history_r5.md says Organize "becomes one undo step when the reader resumes"; that is not reachable.
- User impact / scenario: the user deletes 3 pages in Organize, returns, presses Undo: nothing to undo. Every earlier step (annotations saved, quick tools) is also gone after visiting Organize, Settings or another tab.
- Proposed fix: move `ReaderState` (or at least `DocHistory` + `externalBase`) into a back-stack-entry ViewModel or an app-level registry keyed by document. Dispose it only when the entry is popped for good (`onCleared`), not on `onDispose`.
- Effort: M     Regression risk: Med     Dependencies: AN-01
- Validation: instrumented test — make an annotation, save, open Organize, delete a page, save, go back; assert `history.undoList()` holds both steps and undo restores the page.

### AN-04 Undoing a file step after a stored annotation was taken out for editing duplicates the annotation, and the following save snapshot reapplies an undone change
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence: convert removes the stored annotation from the file without a history step: `AnnotEdit.kt:139-143` (`s.swapFile(tmp, record = false)` then `s.adoptConverted(a)`). The same happens in `MultiSelect.kt:209-213`. `adoptConverted` adds the copy to every memory snapshot (`ReaderState.kt:344-348`). An older `FileSnapshot.before` still contains the original annotation; undoing it (`DocHistory.kt:130-138` → `restoreFrom`) puts the original back in the file while the adopted copy stays in `annots`. On the next save both are written. `save()` also takes `before` from `edit.backupFile` (`ReaderState.kt:371`), the pre-conversion copy. That copy is stale after the undo: undoing that save restores a version that still contains the change the user had undone.
- User impact / scenario: compress (file step) → drag an existing highlight a little → Undo, Undo. The highlight now appears twice after save. Undoing the save brings back the compression the user removed.
- Proposed fix: record the conversion as a `FileSnapshot` step (record = true, label "edit stored annotation") whose undo also removes the adopted annotation (a combined step), or forbid undoing past a conversion until the next save. Drop `edit.backupFile` as the save's `before` once any file step was undone or redone after the conversion.
- Effort: M     Regression risk: Med     Dependencies: —
- Validation: unit test on ReaderState with fake writer: file step → convert → undo×2 → save; assert one annotation in the output.

### AN-05 A save whose snapshot copy failed keeps the in-memory steps; undo then re-adds annotations that are already in the file (duplicates)
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `ReaderState.kt:371` — `before = runCatching { history.copyIn(...) }.getOrNull()` (null on disk-full or I/O error). Then `:400` `if (wrote && before != null) recordSaved(...) else before?.delete()`. The memory steps (`AnnotSnap` lists holding the saved annotations) stay on the stack while `annots.clear()` ran (`:395`). Undo → `setAnnots(snap.before)` brings saved annotations back as pending → the next save writes them again. A related path: forms flushed but the annotation commit failed (`:375-398`) → `wrote = true` → `replaceMemory` removes the memory steps of annotations that are still pending.
- User impact / scenario: low storage (the history folder alone may hold 300 MB, `DocHistory.kt:174`). The user saves, presses Undo, saves again: every highlight is doubled.
- Proposed fix: when the save wrote but no snapshot exists, call `history.removeOwner(OWNER_ANNOTS)` (and the forms owner) so stale memory steps cannot replay. In the partial-failure path, only replace the steps of owners that were actually written.
- Effort: S     Regression risk: Low     Dependencies: —
- Validation: unit test with `copyIn` throwing; assert `undo()` after save does not change `annots`.

### AN-06 Annotations, text selection and search highlights use the wrong coordinate space on rotated pages and pages whose crop box does not start at (0,0)
- Type: bug
- Status: CONFIRMED (traced in code); visual result needs device test
- Severity: High      Priority: P1
- Evidence: `PageGeometry.kt:30-32` maps `pdf ↔ screen` as `x*scale, (pageH - y)*scale`: origin 0,0, no /Rotate. `pageW/pageH` come from `PdfSession.pageSize` = `getPageWidthPoint()` (`PdfSession.kt:67-76`), which PDFium returns with /Rotate applied (the project relies on this in `PdfOps.kt:402`). Character boxes (`PdfSession.kt:186-193`, `textPageGetCharBox`) and search rects are in unrotated user space including the MediaBox/CropBox offset. Grep finds no /Rotate or crop-box compensation anywhere in `ui/reader`, `core/pdf/Annotations.kt` or `core/annot` (`grep -i "getRotation|cropbox|/Rotate"` — only the view-rotation `state.rotation`). The drag-to-highlight rect is converted with the same mapping (`PageCanvas.kt:430`), and ink/shape points become PDF coordinates through `g.screenToPdf`, so wrong coordinates are written into the file.
- User impact / scenario: a sideways scanned page fixed with Organize → Rotate (sets `/Rotate`, `PdfOps.kt:112`), or any landscape page stored as portrait + /Rotate 90 (common in textbooks). Highlights and search hits draw in the wrong place, drag-highlight picks the wrong words, and ink lands rotated or offset when viewed in another app. PDFs with CropBox [36 36 …] show every highlight shifted by 36 pt.
- Proposed fix: give `PageGeometry` the page's rotation and crop-box origin (read once per page with PdfBox or PDFium `FPDFPage_GetRotation` / crop box). Apply `userToDisplay` the way `core/edit/PageFrame` does for redaction (`RedactState.kt:70-71` already uses `PageFrame.of(p)`).
- Effort: M     Regression risk: Med     Dependencies: —
- Validation: unit test of the new mapping for rot 0/90/180/270 + offset crop box; device test with a rotated page: highlight a word, save, verify in another viewer.

### AN-07 Redaction leaves the removed or blanked images and form XObjects in the page resources: the original pixels and text stay recoverable
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Critical      Priority: P0
- Evidence: `core/redact/TrueRedactor.kt:62-68` — images fully inside a mark: `plan.removeOps += p.op` (only the `Do` operator is removed). Partly covered: `blankImage` adds a NEW image to the resources (`:173-176` `res.add(x, "Rd")`) and swaps the `Do`, while the original image stays registered under its name in `/Resources /XObject`. Form XObjects inside a mark are removed the same way, by operator only (`:63`). `ContentRewriter.commit` (`core/edit/ContentRewriter.kt:100-115`) only replaces the content stream. No resource pruning exists in `core/redact` (grep "unused|prune|XObject" finds nothing relevant), and `RedactApplier.apply` saves with `doc.save(tmp)` (`RedactApplier.kt:278`). PdfBox writes every object reachable from the page's /Resources, so the unredacted image and the form (with its text) are in the output file. Only the raster fallback (`needsRaster` / `maxSecurity`, default off — `RedactScreen.kt:55`) replaces the page and drops them.
- User impact / scenario: the user redacts a photographed ID card, signature image or a logo/stamp on a scanned contract and shares `_redacted.pdf`. `pdfimages` or any PDF object viewer extracts the full original image. This is the classic "black box only" leak, in a feature whose UI promises true redaction.
- Proposed fix: after `apply()`, walk the page's `/Resources /XObject` (and nested form resources) and remove every entry whose name is no longer used by any `Do` in the rewritten content (or in remaining forms/annotation appearances). Same for `/Font` and `/ExtGState` optionally. Add a post-redaction verifier that extracts text and images from the marked areas of the output and fails if anything remains.
- Effort: M     Regression risk: Med     Dependencies: AN-09, AN-10
- Validation: unit/instrumented test — a PDF with one image fully under a mark and one partly under it: after apply, assert the original image stream object is absent (search COS objects for its length / MD5) and only "Rd" remains.

### AN-08 "Undo" on the quick-tool done bar and in the AI chat silently undoes every newer change too
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `DocHistory.kt:116-119` `undoThrough(before)` → `undoTo(id)`, and `undoTo` (`:100-105`) undoes that step "and every newer step". It is used by the done bar (MODULE_history_r5 "QuickDoneBar «تراجع» → unified undo back through that step") and by the AI chat restore (`ReaderScreen.kt:330`). No confirmation lists the extra steps.
- User impact / scenario: compress → add 5 highlights → tap the still-visible "Undo" of the compress bar: the 5 highlights are undone as well (they move to redo and are lost with the next change). Same for an AI chat message's undo after manual edits.
- Proposed fix: when the target step is not the top one, show "This also undoes N later changes: …" with confirm; or hide the done-bar undo once a newer step exists.
- Effort: S     Regression risk: Low     Dependencies: —
- Validation: UI test — quick tool, then an annotation, then done-bar undo → expect a confirmation dialog.

### AN-09 Redaction keeps /ActualText of marked content, including the text the app itself writes for every edited Arabic line
- Type: security
- Status: CONFIRMED for in-app edited/added text; PROBABLE for third-party tagged PDFs
- Severity: High      Priority: P1
- Evidence: the app wraps shaped Arabic in `BDC /Span <</ActualText (logical text)>>` (`core/pdf/ArabicShaper.kt:103-110`, `core/edit/TextWriter.kt:150`). `TrueRedactor` removes glyph bytes from `Tj/TJ` only (`TrueRedactor.kt:103-156`). `PageInspector` tracks BDC only for hidden-OC detection (`PageInspector.kt:83`), and `grep -i actualtext core/redact` finds nothing, so the property dictionary with the logical text stays in the content stream. The structure tree (`/StructTreeRoot` /ActualText, /Alt) is also untouched.
- User impact / scenario: a user edits a line with the content editor (or adds text with the add-text tool), then redacts a name in that line. The visible glyphs go, but copy/paste and text extraction still return the full original line through /ActualText.
- Proposed fix: in the rewrite, when a BDC with /ActualText (inline or from /Properties) spans a removed glyph, drop the /ActualText key (or the whole BDC/EMC pair). In `RedactApplier`, also strip /ActualText /Alt /E from structure elements whose /K MCIDs are on the page and touch marks, or always run the structure-tree removal when redacting.
- Effort: M     Regression risk: Med     Dependencies: AN-07
- Validation: unit test — add Arabic text with `showArabic`, redact a word, then assert PdfBox text extraction / raw stream contains no `ActualText` with the word.

### AN-10 Redaction defaults leave metadata, bookmarks, attachments and partly covered vector text in place; batch mode skips protected files silently
- Type: security
- Status: CONFIRMED (traced in code); vector-path leak is PROBABLE
- Severity: Medium      Priority: P1
- Evidence: `ui/security/RedactScreen.kt:54-55` — `sanitize` and `maxSecurity` default to `false`. `RedactApplier.apply` runs the Sanitizer only `if (options.sanitize)` (`RedactApplier.kt:277`), so outline titles, Info/XMP, attachments and form values elsewhere that contain the redacted term stay. Paths are only removed when fully enclosed (`TrueRedactor.kt:62` `if (inside)`), so text drawn as vector outlines (common in designed Arabic textbooks and exported posters) stays whole when a line-wide path only partly touches the mark. Batch: `RedactScreen.kt:222` `PdfSession.open(ctx, f, null)` → `?: continue` for encrypted files, with no report.
- User impact / scenario: a user redacts "Ahmed Ali" everywhere but the bookmark "Ahmed Ali — CV" and the document title keep it. Batch redaction of 10 files where 2 are protected: they are silently not redacted.
- Proposed fix: when search-based marks exist, scan with `Sanitizer` for remaining occurrences of the searched terms (outline, Info, XMP, annotations, attachment names) and pre-check those items. Default `sanitize = true` for the redaction flow. Report skipped files in the batch summary. For partly covered paths, clip them (or rasterise the region) instead of keeping them.
- Effort: M     Regression risk: Low     Dependencies: AN-07
- Validation: test PDF with the term in the outline + Info: after default redaction, full-text extraction of all COS strings finds no term.

### AN-11 Editing a stored annotation rewrites it lossily (replies, popup, rich text, dates and custom appearance are lost) and leaves an orphan popup
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `AnnotEdit.kt:123-145` convert = `AnnotationWriter.extract` → `toAnnot` (`core/pdf/Annotations.kt` `toAnnot`: keeps kind, rects/paths, colour, CA, /T, /Contents, width, NM only) + `AnnotationWriter.delete`, which does `list.removeAt(index)` (`Annotations.kt:463-471`) without removing the /Popup child or reply annotations (/IRT) that point to it. The re-written annotation gets a new /CreationDate (`Annotations.kt` `setCreationDate(now)`), loses /RC, /Subj, custom /AP (e.g. Acrobat stamps, cloud borders), /StateModel review states, and its replies become orphans pointing to a deleted object.
- User impact / scenario: a teacher's Acrobat comment with three replies; the student only nudges it 2 mm. Replies are now detached, the date shows today, the rich-text formatting is gone.
- Proposed fix: for move/resize/colour of a stored annotation, edit the existing COS dictionary in place (translate /Rect, /QuadPoints, /InkList, /Vertices; regenerate /AP) instead of delete + recreate. When deleting, also remove its /Popup and re-parent or delete /IRT replies after confirming.
- Effort: L     Regression risk: Med     Dependencies: —
- Validation: unit test — annotation with popup and reply: move it, then assert the same object number, the reply's /IRT still resolves, and no orphan popup remains.

### AN-12 Annotations that fail to write are dropped silently and still cleared from memory
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P1
- Evidence: `core/pdf/Annotations.kt:78-84` — each `write(context, d, page, a)` is wrapped in `runCatching { … }` with the result ignored; `d.save(out)` follows. `ReaderState.save()` then `annots.clear()` (`ReaderState.kt:395`). Also `AnnotKind.REDACT -> { /* … never stored */ }` (`Annotations.kt:207`) drops silently.
- User impact / scenario: an image stamp whose bitmap was recycled, an attachment whose temp file was cleaned from cache, or a malformed ink path fails. The user sees "saved" and the annotation is gone, with no undo.
- Proposed fix: collect failures. If any, keep those annotations pending (do not clear them) and show "N annotations could not be saved".
- Effort: S     Regression risk: Low     Dependencies: —
- Validation: unit test with an annotation whose `write` throws; assert `save()` reports it and `annots` still contains it.

### AN-13 Eraser only erases unsaved ink; after any autosave, earlier strokes cannot be erased
- Type: bug
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P1
- Evidence: `ui/reader/PageCanvas.kt:214` — `ReaderTool.ERASER -> … inkHits(annots, page, x, y, …).forEach { state.removeAnnot(it) }`. `annots` is only the pending list, while saved ink lives in `state.existing` and is never consulted. Saves happen on every ON_PAUSE (`ReaderScreen.kt:92`), so "pending" is short-lived.
- User impact / scenario: a student scribbles, checks a message (ON_PAUSE → autosave), comes back and tries to erase: nothing happens. The only route is tap-select → delete, one stroke at a time.
- Proposed fix: let the eraser hit-test `existing` Ink entries too (rect pre-filter, then extract the path) and delete them through one batched `fileOp` per gesture (a single undo step).
- Effort: M     Regression risk: Low     Dependencies: —
- Validation: device test — draw, background the app, return, erase; stroke is removed and undo restores it.

### AN-14 With a drawing/markup tool active, every one-finger drag draws: no stylus-only mode, no palm rejection, no two-finger scroll
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P1
- Evidence: `PageCanvas.kt:172` `else -> state.tool in dragTools` claims the gesture at touch-down for INK, ERASER, shapes and HIGHLIGHT/UNDERLINE/STRIKEOUT/SQUIGGLY (`:315-316`). `pageDrag` consumes every move (`:298-312`). `grep -i "PointerType|stylus|palm"` over `ui/` finds nothing, so `PointerType.Stylus` is never checked.
- User impact / scenario: the rapid highlighter must toggle the tool off to scroll to the next paragraph, and back on, every time. Forgetting creates stray highlights/ink (undoable, but they break flow). Stylus users get palm marks.
- Proposed fix: add the setting "Draw with stylus only; finger scrolls" (accept only `PointerType.Stylus`/`Eraser` when a stylus was seen). Let two-finger drags always pan/zoom while a tool is active. Optionally use long-press-to-start for finger highlighting.
- Effort: M     Regression risk: Med     Dependencies: —
- Validation: device test with a stylus and a finger; instrumented test that injects `PointerType.Touch` with the stylus-only option → no annotation.

### AN-15 Cross-file search never finds Arabic documents stored in visual (reversed) order
- Type: bug
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence: in-document search tries the reversed query (`TextMatcher.kt:192-197`). The global index pre-filters with FTS: `FullTextIndex.kt:106-111` `IndexText.ftsQuery(query, mode)` builds prefix terms from the forward query only (`IndexText.kt:301-310`). The body is indexed from the raw (reversed) text (`IndexText.kt:285-293`), so reversed words never match `stem*`, and candidates never reach `TextMatcher`. The index also does not apply `ArabicTextFix.demojibake` (used by `TextMatcher.find`, `:186`), so cp1256-mojibake files are not candidates either (in-flux area).
- User impact / scenario: an Arabic textbook reader searches "الكيمياء" across the library. Older textbooks (visual-order text, the reason the reverse path exists) are missing from the results, although the same search inside the open file finds them.
- Proposed fix: OR the reversed-word terms into the MATCH (`(stem* OR reversedStem*)`), or index both orders (append `fold(raw).reversed()` words when the page looks visual-order, i.e. presentation forms present). Apply the same demojibake before `IndexText.body`.
- Effort: S     Regression risk: Low     Dependencies: ArabicTextFix (in-flux area)
- Validation: unit test — index a part with reversed Arabic; `search("محمد")` returns it.

### AN-16 Opening a global-search result does not carry the query: no highlight, no hit navigation in the reader
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `ui/search/GlobalSearchScreen.kt:114` `nav.navigate(Routes.reader(Uri.fromFile(File(fr.path)).toString(), h.page...))`. `Routes.reader(uri, page)` (`ui/Navigation.kt:54`) has no query parameter, so the reader opens on the page with the search bar closed.
- User impact / scenario: the student finds "معادلة نيرنست" in 6 files, taps one: lands on the page and must re-type the query to see where it is.
- Proposed fix: add `?q=` to the reader route; on open set `searchOpen = true; runSearch(q)` and select the hit on that page.
- Effort: S     Regression risk: Low     Dependencies: —
- Validation: UI test — global search → tap result → search bar shows the query and the current hit is on the start page.

### AN-17 Next/previous hit only jumps to the page; at zoom the hit can stay off-screen
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `ReaderState.kt:595-599` `searchStep` → `jumpTo(searchHits[i].page)`. `PageView.kt:189-193` handles `pendingJump` with `listState.scrollToItem(p)` / `animateScrollToPage`, i.e. page top only, with no pan to `hit.rects`. Tapping a result row does the same (`ReaderOverlays.kt:71`).
- User impact / scenario: reading a dense page at 200 % (common on phones), "next" goes to the right page but the orange current hit is below the fold or off to the side. Several hits on the same page do not move the view at all.
- Proposed fix: add `jumpTo(page, focus: RectF?)`; after scrolling, set `panX/panY` (or the list offset) so the hit rect is centred.
- Effort: M     Regression risk: Low     Dependencies: AN-06 (correct rects)
- Validation: device test — zoom 250 %, step through 5 hits on one page; each is visible.

### AN-18 Reader search is not cancellable, shows no progressive results, and stale jobs reset the spinner
- Type: performance
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `ReaderState.kt:578-592` — after a 250 ms debounce, the whole document is scanned before anything is shown. `PdfSession.searchWith` (`PdfSession.kt:229-251`) loops all pages, checking only `closed` and never `isActive`. When the job is cancelled, `runCatching { s.searchWith… }` catches the `CancellationException` (`ReaderState.kt:582-584`) and falls back to `s.search(...)`. The cancelled job then still sets `searchHits`, `searchIndex`, `searching = false`. Every surviving keystroke job holds the PDFium mutex page by page and competes with page rendering.
- User impact / scenario: in a 600-page textbook, typing an Arabic word slowly (>250 ms per key) starts several full scans. Rendering stutters, the spinner disappears while results are still coming, and the first results appear only after the whole book is scanned.
- Proposed fix: call `ensureActive()` per page in `searchWith`/`search`, rethrow `CancellationException` (no `runCatching` around suspend calls), publish hits incrementally (current page outward first), and guard state writes with `if (searchJob === this)`.
- Effort: S     Regression risk: Low     Dependencies: —
- Validation: unit test with a fake session: cancel after 2 pages; assert the scan stops and state is untouched.

### AN-19 "Discard changes" does not discard content edits, quick tools, form flushes or stored-annotation deletions: those are already written back to the original
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: content edits go through `applyEdit` → `applyInPlace` (`ui/edit/EditController.kt:32-33`), which swaps the file and calls `app.documents.writeBack(f)` immediately (`ReaderState.kt:523`). The same is true for `deleteExisting` (`:433`) and undo/redo (`:541`). The discard dialog only drops pending annotations / forms and restores the annotation-edit backup on the working copy (`ReaderScreen.kt:315`, `ReaderState.kt:246-251`).
- User impact / scenario: the user edits a paragraph, adds a highlight, then leaves with "Discard". The highlight goes, the paragraph edit stays in their original file. The two kinds of change look the same in the UI but persist differently.
- Proposed fix: either word the dialog accurately ("Discard N unsaved annotations — content edits are already saved; use Undo") or keep file-level changes in the working copy and write back only on save/leave, with discard restoring the opened version.
- Effort: S (wording) / L (deferred write-back)     Regression risk: Low / High     Dependencies: AN-02
- Validation: UI test — content edit + annotation → Discard → reopen; behaviour matches the dialog text.

### AN-20 Markup by drag uses a rectangle on character index range: over-selects in multi-column/RTL layouts and silently does nothing on scanned pages
- Type: usability
- Status: CONFIRMED (traced in code); over-selection is PROBABLE (depends on text order)
- Severity: Medium      Priority: P2
- Evidence: `PageCanvas.kt:430-436` → `TextSelectionLogic.charsInRect` (`TextSelectionLogic.kt:76-86`) returns `first..last` index of any box intersecting the rect, i.e. every character between them in content-stream order. `rects()` (`:47-66`) then merges a line's boxes across columns into one wide rect. If no char boxes are found (image-only page) → `?: return@launch` with no feedback and no freehand-highlight fallback.
- User impact / scenario: in a two-column Arabic textbook, dragging over one line in the right column highlights text in the left column or the column gutter too. On a scanned worksheet the highlighter does nothing and no message explains why (OCR needed).
- Proposed fix: highlight the reading-order range between the char under the drag start and the char under the drag end, split rects per text line and per column gap. On pages without text, fall back to a rectangle highlight (Square with Multiply /AP) and show "No text on this page — run OCR for text highlighting".
- Effort: M     Regression risk: Med     Dependencies: AN-06
- Validation: device test on a two-column Arabic PDF and on a scanned page.

### AN-21 No per-tool colour/width memory; settings reset on every open
- Type: missing feature
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P2
- Evidence: `ReaderState.kt:112-115` — `color = 0xFFEB3B`, `opacity = 1f`, `thickness = 3f` are plain state initialised on every open. `selectTool` (`:605-619`) only swaps between a yellow / red / dark default based on the current colour. No persistence (grep `toolColor|lastColor|penColor`: none).
- User impact / scenario: a student who uses green highlights and blue ink must reselect colours after every reopen and every tool switch. Opacity 1.0 for highlights relies on Multiply.
- Proposed fix: store `{tool → color, opacity, width, font size}` in DataStore; add 3–5 favourite presets per tool in the property bar.
- Effort: S     Regression risk: Low     Dependencies: —
- Validation: unit test of the preset store; UI test — choose green highlighter, reopen, still green.

### AN-22 Reorder / move / paste pages rebuild the PDF: outline (TOC), form fields, page labels, named destinations and password protection are dropped
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence: `ui/pages/OrganizeState.kt:194-219` — `reorder`, `moveSelected` and `paste` call `PdfOps.reorder` / `PdfOps.keepPages`. `PdfOps.kt:93-101` creates `PDDocument()` and `importPage` for each page, copying only `documentInformation`. The catalog (/Outlines, /AcroForm, /PageLabels, /Names, /StructTreeRoot, /OCProperties, XMP) is not copied, and `saveTo(nd, out)` writes `nd` without `protect` (unlike `PdfOps.load`, `:39-49`). Delete and rotate use `doc.removePage` / `/Rotate` on the original document and are fine.
- User impact / scenario: an Arabic textbook reader moves one misplaced page with the arrow buttons and saves: the whole bookmark/TOC panel is empty from then on. A password-protected exam file reordered and saved is now unprotected, with no warning. Fillable forms lose their fields.
- Proposed fix: implement reorder in place on the loaded document (reorder `/Kids` of the page tree, or `doc.pages.remove` + `insertBefore/After`), keeping the catalog and encryption. Use `keepPages` only for extract/split.
- Effort: M     Regression risk: Med     Dependencies: —
- Validation: unit test — PDF with outline + AcroForm + encryption: reorder [1,0,2] → output still has the outline (destinations follow pages), fields and encryption.

### AN-23 Arabic shaper misses Persian/extended letters and alef wasla: wrong joining in edited or added text
- Type: bug
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `core/pdf/ArabicShaper.kt:12-26` `forms` lacks پ چ ژ گ ٹ ڈ ڑ ں ھ ۃ ٱ ۀ and others. `Bidi.isShapable` includes U+0671–U+06D3 (`core/edit/EditBidi.kt:142-143`), so those letters enter `shapeRaw`, where `isArabicLetter` is false (`ArabicShaper.kt:30,56`). They pass through unshaped and break the joining of their neighbours (`prevJoins` / `nextJoins` false). `ٱ` (Quranic alef wasla) is also missing from `lamAlef`.
- User impact / scenario: editing a line containing "گزارش" or a Quranic quotation with "ٱللَّه" renders disconnected letters in the saved PDF.
- Proposed fix: extend `forms` with the full Arabic Presentation Forms-A table (or generate it from Unicode ArabicShaping.txt joining types). Better: shape with the platform (`android.graphics.text.TextRunShaper` / HarfBuzz via `Paint.getTextRunAdvances`) and embed glyph IDs.
- Effort: M     Regression risk: Med     Dependencies: —
- Validation: unit test — `Bidi.shapeVisual("گزارش", true)` returns only presentation-form code points for every letter.

### AN-24 Tashkeel is reordered before its base letter in visual output (marks drawn on the neighbouring letter)
- Type: bug
- Status: PROBABLE (traced in code; font-dependent rendering needs device test)
- Severity: Medium      Priority: P2
- Evidence: `EditBidi.kt` `levels()` W1 gives NSM the level of the previous char (`:61`), and `reorder()` (`:107-125`) reverses the whole RTL run including marks. A logical "بَ" becomes visual "َ ﺑ" (mark first). `ArabicShaper.shapeRaw` keeps marks in sequence (`:66`) and `visualOrder` reverses them the same way (`:93-95`). PdfBox `showText` does no GPOS mark positioning, so a zero-advance mark placed before its base overlays the glyph to its left, which is the next letter in reading order. Unicode bidi rule L3 (keep combining marks after their base) is not applied.
- User impact / scenario: an Arabic textbook / Quran teacher fixes a vowelled word with the content editor: the harakat shift one letter to the left in the output PDF.
- Proposed fix: after `reorder`, move each run of NSM chars to after their base (L3). Ideally use real shaping (see AN-23) with mark anchoring.
- Effort: S     Regression risk: Low     Dependencies: AN-23
- Validation: unit test — `logicalToVisual("بَت", true)` places U+064E right after the ب glyph; device test viewing output in Acrobat/Chrome.

### AN-25 Reading bookmarks are page-index rows in the DB: not remapped after page delete/reorder, lost if the working copy path changes, deleted without undo
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P2
- Evidence: `core/data/AppDatabase.kt:33-40` `ReadingBookmark(uri, page, title)`; `ReaderPanels.kt:119-137` keys on `state.fileUri` (the working copy `file://`). `OrganizeState.apply` only clears its own filter sets when the page count changes (`OrganizeState.kt:135`), with no DAO update. Delete is immediate (`ReaderPanels.kt:137`). Bookmarks are not written into the PDF, so they do not travel with the file.
- User impact / scenario: the page jumper bookmarks chapters, then deletes the 4 blank pages at the front: every bookmark now points 4 pages too far.
- Proposed fix: after Organize save, remap bookmark pages with the old→new index map produced by the operation (delete/reorder are known). Offer "save bookmarks into the PDF outline". Snackbar undo on delete.
- Effort: M     Regression risk: Low     Dependencies: AN-22
- Validation: unit test — remap function for delete {0,1} and reorder; UI test of delete + undo.

### AN-26 Edit5 bookmarks (outline) screen only saves a copy and loses edits on Back without asking
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `ui/edit5/BookmarksScreen.kt:107-109` — the only save button is `e5_save_copy` → `doc.export(docsDir, …, "bookmarks")` (a new file). There is no `BackHandler` in `ui/edit5/*.kt` (grep), while `dirty` exists (`:55`). By contrast the reader's own outline editor saves in place and guards Back (`ui/edit/OutlineEditor.kt:232,288`).
- User impact / scenario: the user builds a TOC for a scanned textbook with "auto from headings", presses Back: all gone. If they press save, the TOC is in `X_bookmarks.pdf`, not in the book they are reading.
- Proposed fix: add a dirty-guard `BackHandler`; offer "Save to this file" (through `applyInPlace` when opened from the reader) next to "Save as copy".
- Effort: S     Regression risk: Low     Dependencies: —
- Validation: UI test — edit, Back → discard/save dialog appears.

### AN-27 History disk usage and cost: up to 300 MB per document in cacheDir and a full-file copy per change; snapshots can vanish under storage pressure
- Type: performance
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `DocHistory.kt:173-174` (60 steps / 300 MB), folder under `app.documents.tempDir` = `cacheDir/tmp` (`DocumentRepository.kt:29`). Every save copies the whole file (`ReaderState.kt:371`), every swap copies again (`:452`), the first undo copies the current file as `after` (`DocHistory.kt:133-136`), and each step is followed by a full PdfBox rewrite and a full write-back. Cache files may be purged by the OS while the app runs, giving "snapshot missing" errors (`DocHistory.kt:132`).
- User impact / scenario: a 120 MB textbook: each autosave (every app switch) costs ≈360 MB of I/O; only 1–2 undo steps fit. On low-end phones saving takes seconds behind a blocking progress dialog (`ReaderScreen.kt:350`).
- Proposed fix: store history in `filesDir/history` (not purgeable); for annotation-only saves store an XFDF/annotation-diff step instead of a full copy; use PdfBox incremental save (`saveIncremental`) for annotation commits (also fixes AN-28).
- Effort: L     Regression risk: Med     Dependencies: AN-28
- Validation: benchmark — save timing and bytes written on a 100 MB file before/after.

### AN-28 Annotating a digitally signed PDF rewrites the file fully and invalidates the signatures without warning
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence: `core/pdf/Annotations.kt:87` `d.save(out)` (full save) for every annotation commit and delete (`:466-469`). The project knows incremental saves preserve signatures (`core/pdf/DigitalSignature.kt:305`, `core/sign/LtvSupport.kt:195` "incremental update, so existing signatures stay valid") but `ReaderState.save()` never checks for signatures.
- User impact / scenario: a student receives a signed certificate or a signed contract, adds one highlight (autosaved on app switch): the signature panel in Acrobat now shows "document has been altered / signature invalid", and the original is overwritten by write-back.
- Proposed fix: detect /Sig fields on open. For signed documents, commit annotations with `saveIncremental` (PdfBox 2.0 supports it when objects are marked `setNeedToBeUpdated`), or warn and save as a copy.
- Effort: M     Regression risk: Med     Dependencies: signing audit
- Validation: signed sample PDF → add highlight → verify signature with `DigitalSignature` verifier still "valid, modified by annotation only".

### AN-29 Saving annotations on a protected PDF replaces the owner password and permissions with the user password
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `core/pdf/PdfOps.kt:41-46` — every encrypted document loaded for writing is re-protected with `StandardProtectionPolicy(pw, pw, ap)`: owner password = user password. `AnnotationWriter.commit` uses `PdfOps.load` (`Annotations.kt:73`).
- User impact / scenario: a publisher-protected textbook (open password known, printing/copying restricted by a secret owner password). After one highlight, anyone with the open password now also holds the owner password and can lift all restrictions, and the original is overwritten by write-back.
- Proposed fix: keep the original encryption dictionary (PdfBox: do not call `protect`, save with `doc.setAllSecurityToBeRemoved(false)` and the existing security handler) or ask for the owner password. Never silently downgrade.
- Effort: M     Regression risk: Med     Dependencies: security audit
- Validation: unit test — PDF with owner ≠ user password: after commit, opening with the user password reports `isOwnerPassword == false`.

### AN-30 Content-edit "delete image" removes only the draw operator; the image stays in the file
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `core/edit/ContentEdit.kt:100-104` `deleteImage` → `rw.remove(op)` only. `ContentRewriter.commit` (`ContentRewriter.kt:100-115`) replaces the content stream and leaves `/Resources /XObject` untouched (same root cause as AN-07). `replaceImage` likewise adds a new XObject while the old one stays.
- User impact / scenario: the user deletes a photo or signature image from a page before sharing; the image is still extractable from the PDF.
- Proposed fix: share the resource-pruning helper proposed in AN-07 and run it after every ContentRewriter commit.
- Effort: S (after AN-07)     Regression risk: Low     Dependencies: AN-07
- Validation: same object-absence test as AN-07.

### AN-31 Annotation-edit backup and save temp files are left as hidden files next to the user's document
- Type: bug
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `AnnotEdit.kt:136-137,156-157` writes `.<name>.annotedit.pdf` in `f.parentFile`. It is deleted only on a successful save (`committed()`, `:198-199`) or restored on discard. On close without save, or on a crash, it stays (`restoreOnClose` returns null when `!discard`, `:206-209`). For `file://` documents that folder is the user's (e.g. Download). `.saving.pdf` / `.restore.pdf` / `.quick.pdf` temps live in the same place (`ReaderState.kt:387,513,537`) and are never cleaned on a crash.
- User impact / scenario: hidden duplicate copies of private documents pile up in Downloads (some apps and backups show hidden files).
- Proposed fix: keep backups/temps in app-private storage (rename is not needed for backups). Add a startup sweep of `.*.annotedit.pdf` / `.*.saving.pdf` older than 1 day in known doc folders.
- Effort: S     Regression risk: Low     Dependencies: —
- Validation: unit test of the sweep; manual check after a forced crash.

### AN-32 Rotated image placement (signature/stamp) is distorted on commit
- Type: bug
- Status: PROBABLE (traced in code; needs device test)
- Severity: Low      Priority: P3
- Evidence: `ReaderState.kt:622-628` — `rotateBitmap(p.image, p.rotation)` produces the larger axis-aligned bounding bitmap (`Bitmap.createBitmap(..., m, true)`, `:632-635`), which is then stored with `rects = listOf(normPdf(p.rect))`, the unrotated frame. The rotated image is squeezed into the original frame (smaller, and aspect-distorted for non-square images at 30–60°).
- User impact / scenario: the user tilts a signature 20° to fit a slanted line; the saved signature is smaller than the preview and slightly squashed.
- Proposed fix: compute the rotated frame's bounding box (`Matrix.mapRect`) around the frame centre and store that rect, or write the stamp with an /AP matrix rotation instead of rotating pixels.
- Effort: S     Regression risk: Low     Dependencies: AN-06
- Validation: unit test of the bounding-rect math; device test comparing the preview and the saved PDF.

### AN-33 Highlight /Contents is auto-filled with the highlighted text (≤200 chars)
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `ReaderState.kt:642` `text = sel.text.take(200)` and `PageCanvas.kt:435` `.take(200)`; the writer puts it in `/Contents` (`Annotations.kt` markup branch `m.contents = a.text`). The text goes through `ArabicTextFix.fix` (`TextSelectionLogic.kt:72`, in-flux area).
- User impact / scenario: in Acrobat / Xodo every highlight opens a "comment" that is just the quoted text; comment exports and summaries show long passages cut at 200 characters as if they were user notes.
- Proposed fix: store the quoted text in a separate key (e.g. `/RC`-less custom key, or XFDF export only) and keep `/Contents` for user notes; remove the 200-char cap for exports.
- Effort: S     Regression risk: Low     Dependencies: —
- Validation: unit test — highlight a 300-char sentence; summary export contains the full quote, `/Contents` is empty.

### AN-34 Go-to-page knows only physical page numbers (no page labels / printed numbers)
- Type: enhancement
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P2
- Evidence: `ReaderDialogs.kt:67-80` — digits only, `n in 1..count`, no /PageLabels lookup, and no IME "Go" action (`keyboardOptions` without `imeAction`/`keyboardActions`). The app can edit page labels (`ui/edit5/PageLabelsScreen.kt`) but the reader never reads them (grep `PageLabel` in `ui/reader`: none).
- User impact / scenario: a textbook's printed page 45 is PDF page 53. The teacher says "open page 45" and the student lands 8 pages early. Each jump also needs a tap on OK.
- Proposed fix: read /PageLabels once (PdfBox `getPageLabels`); accept labels ("iv", "45") in the dialog, show "45 (53)" in the page indicator; add `ImeAction.Go`.
- Effort: S     Regression risk: Low     Dependencies: —
- Validation: unit test — label resolver for a roman + arabic labelled sample; UI test of Enter = Go.

### AN-35 Undo/redo tests cover the pure stack only; no tests for ReaderState history integration
- Type: test-gap
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `app/src/test/java/com/smartdocs/editor/core/history/UndoStackTest.kt` (11 tests: ordering, limits, replaceMemory, one DocHistory round trip). Nothing exercises `ReaderState.save()` consolidation, `adoptConverted`, `swapFile(record=false)` paths, navigation disposal, or `undoThrough` side effects. These are exactly where AN-03/04/05/08 sit.
- User impact / scenario: regressions in the most data-sensitive code ship unnoticed.
- Proposed fix: extract the save/convert/undo orchestration from `ReaderState` into a testable class with injected writer/session/file-swap fakes; add scenario tests for AN-04 and AN-05 and the save-failure paths.
- Effort: M     Regression risk: Low     Dependencies: —
- Validation: the new tests themselves.

### AN-36 No recent-search history in the reader or global search
- Type: missing feature
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `ReaderState.kt:153-160` keeps only the current query; `GlobalSearchScreen.kt:44` `rememberSaveable` query only; grep for any stored query list finds none.
- User impact / scenario: a student revising re-types the same Arabic terms (with diacritics-insensitive matching they would benefit from one-tap reuse) in every session and every file.
- Proposed fix: keep the last 20 queries (DataStore, per user) and show them as chips under an empty search field, with "clear history".
- Effort: S     Regression risk: Low     Dependencies: —
- Validation: UI test — search, close, reopen the search bar → chip present.

## Quick wins
- AN-05 (drop stale memory steps when the snapshot failed) — S
- AN-12 (keep annotations that failed to write, report them) — S
- AN-15 (OR reversed-word FTS terms) — S
- AN-16 (pass the query to the reader from global search) — S
- AN-18 (`ensureActive` + do not catch CancellationException in search) — S
- AN-08 (confirm when "Undo" from the done bar / AI also undoes later steps) — S
- AN-19 (accurate Discard dialog wording) — S
- AN-24 (L3 mark reordering) — S
- AN-26 (Back guard + save-in-place in edit5 bookmarks) — S
- AN-21 (persist per-tool colour/width) — S
- AN-34 (page labels + IME Go in go-to-page) — S
- AN-01 minimal fix (save in `close()` when dirty and not discarded) — S/M, P0

## Needs device testing
- AN-01 / AN-03: confirm the reader composable (and `ReaderState`) is disposed on in-app navigation and tab switch; verify annotation loss and history loss.
- AN-02: kill the process during write-back to a SAF/Drive document; reopen.
- AN-06: highlight, ink and search on a page with /Rotate 90 and on a page with an offset CropBox; inspect the saved file in another viewer.
- AN-07 / AN-09 / AN-10: redact an image-bearing page and an in-app-edited Arabic line; run `pdfimages` / text extraction on the output.
- AN-13: erase after autosave (background the app first).
- AN-14: stylus vs finger scrolling with the highlighter active; palm contact.
- AN-17: hit visibility at 200–300 % zoom.
- AN-18: typing speed vs search on a 500+ page Arabic textbook (render stutter).
- AN-20: drag-highlight on a two-column Arabic PDF and on a scanned page.
- AN-23 / AN-24: render edited Persian / vowelled Arabic in Chrome/Acrobat.
- AN-28: annotate a signed PDF, verify the signature in Acrobat.
- AN-32: rotated signature placement, preview vs saved.
