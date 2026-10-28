# Fixes for the reader Tools + Pages audit (5_tools_pages.md, 98 rows)

Branch `fix/tab-tools-pages` (from main c71711b, main merged in several times). Paths are relative to
`app/src/main/java/com/smartdocs/editor/`. **Nothing was tested on a device.** Everything was checked only by the
GitHub CI build and the JVM unit tests listed at the end.

## Shared pieces (used by many rows)
- `core/pdf/PdfOps.protectLike` / `keepProtection` is the one helper that keeps protection on every derived output:
  same cipher, key length and permission bits, and never a downgrade. `E5Locks` (KEEP) uses it now. REMOVE is honoured only with owner access.
- `core/pdf/PageExtract.build` is the one implementation behind extract, share/print pages, split and the e5 part
  writers. It keeps the protection, the inherited attributes, its own annotation copies, the form fields of the kept
  pages, and the outline and internal links.
- `PdfOps.newProtection` / `protect` are used for every new user protection. They use AES-256, so Arabic and other
  Unicode passwords work (SASLprep / UTF-8). The owner password is never "owner" and never equal to the open
  password; when it is generated at random, it is shown with a copy button.
- `core/pdf/SessionPasswords` hands the reader's password to the tool screen it opens. It lives in memory only and
  expires after 30 minutes.
- `core/pdf/ToolOutputs` records which document a result was made from. ResultSheet's "Save in original" is offered
  only when a result was recorded as a transformation of exactly the route document, and it still needs the confirmation.

## Rows
| Row | Status | Where |
|---|---|---|
| T-01 | OWNED BY fix/tab-view-core (central gate) | ReaderState.applyInPlace (not touched here) |
| T-02 | FIXED | ui/reader/ReaderScreen.kt: quick SHARE uses the same restricted share as View/⋮ |
| T-03 | FIXED | core/pdf/PdfOps.compress: the optimizer keeps the protection; no `protect(pw, pw)` |
| T-04 | FIXED | ui/quick/QuickController.compressLock + QuickSheets: one compression at a time; Compress waits for the estimate |
| T-05 | FIXED | PdfOps.keepPages → PageExtract (protection, forms, outline); quick Extract also needs COPY; ShareBuilder subsets stay protected |
| T-06 | FIXED | ui/reader/ReaderToolbars.kt: chips for Accessibility, PDF/A, Optimize, OCR review, OCR batch, Attachments, Certificate protect; ui/reader/NpfPanels.kt: panel buttons go through actions.navigateTool |
| T-07 | FIXED | ReaderToolbars.kt (one chip per feature) + ui/edit5/Edit5Hub.kt (e5 organizer renamed "Advanced organize"; quick blank page renamed) |
| T-08 | FIXED | ReaderToolbars.kt: grouped grid with headers, two-line labels, taller grid |
| T-09 | FIXED | ReaderScreen navigateTool puts the password; tool screens start from SessionPasswords (ui/tools/*, tools5, edit5 E5Doc, organizer, OCR, redact/protect, compare, AI hub, accessibility, PDF/A, optimize) |
| T-10 | FIXED | core/pdf/DisplayPlace.kt + PdfOps.watermark / addPageNumbers: crop box with /Rotate applied |
| T-11 | FIXED | PdfOps.insertBlankPage: neighbour's crop size and /Rotate |
| T-12 | FIXED | PdfOps.newProtection (random owner); quick Protect shows the generated owner password |
| T-13 | FIXED | distinct icons (ReaderToolbars.kt, Edit5Hub.kt) |
| T-14 | FIXED | ReaderScreen: the share sheet gets the password for the temp copy |
| T-15 | FIXED | ReaderToolbars ToolsToolbar: AI filter recomputed (offline mode included) |
| T-16 | FIXED | core/pdf/XmpInfo.kt: setInfo syncs an existing XMP packet (pdfaid etc. kept) |
| G-01 | OWNED BY fix/tab-view-core (central gate) | OrganizeState.saveOverwrite (only the trash-backup change was made here, E-16) |
| G-02 | FIXED | ui/pages/OrganizeState.kt / OrganizeScreen.kt: reader password reused; warning before the owner password is replaced |
| G-03 | FIXED | OrganizeState.allows: PRINT / COPY checked; outputs keep their protection |
| G-05 | FIXED | ReaderScreen (main also clears the tool; the selection is cleared too); dead branch documented |
| G-06 | FIXED | per-thumbnail OCR runs in place on the work file (one undo step); Redact acts on the tapped page in place (main's redactPages, now also wiping fields and structure text) |
| G-07 | FIXED | LruCache for thumbnails sized to the cell (OrganizeState, PageGrid) |
| G-08 | FIXED | per-page actions keep the selection |
| G-09 | FIXED | core/util/PdfPrint.printDocument (V5 print pipeline) for all organizer prints; PrintHelper is an alias |
| G-11 | FIXED | clipboard by page identity (ui/pages/OrganizeLogic.kt) |
| G-12 | FIXED | select all toggles on visible pages |
| G-13 | FIXED | ui/nve/shell/OrganizeInsert.kt: awaited insert, busy reported, temps deleted |
| G-14 | FIXED | menu stays visible; "Open page" (ReaderScreen jumps to it) |
| G-15 | FIXED | ui/reader/ReaderPanels.kt thumbnails: opens at the current page, labels, cache |
| G-16 | FIXED | organizer open/dispose off the main thread |
| A-01 | FIXED | ui/tools5/AccessibilityScreen.kt / AccessTagsUi.kt: open with the password, prompt, mapped errors |
| A-02 | FIXED | ui/tools5/PdfAScreen.kt: rasterise off by default, pages listed, re-check on password change; core/tools5/PdfA refuses encrypted input without owner access |
| R-1 | FIXED | core/redact/PageScrub.replacePage: old page emptied, every reference re-pointed |
| R-3 | FIXED | PageScrub.wipePage: field values, ActualText/Alt removed |
| R-4 | FIXED | signed-input warning; signature values removed from outputs (Redact, Sanitize, Advanced protect) |
| R-6 | FIXED | RedactApplier: pending marks re-written after rasterising |
| R-8 | FIXED | Sanitizer: skipped content reported |
| R-9 | FIXED | Sanitizer: Redact / Watermark annotations removed with comments |
| R-10 | FIXED | core/redact/RedactIo.saveAtomic |
| R-L1 | FIXED | ui/security/RedactManualView.kt arrows |
| R-L7 | FIXED | core/redact/RedactDrafts.kt: draft persisted |
| P-2 | FIXED | PdfOps.newProtection (AES-256) + ProtectAdvanced / ProtectModes: Latin-1 check for RC4 / AES-128 modes |
| P-5 | FIXED | quick and advanced Protect: owner never equals the open password; random owner shown |
| P-11 | FIXED | ProtectAdvancedScreen: wrong current password asked again |
| P-L | FIXED | owner password dialog with copy; toggleable rows |
| C-1 | signature part OWNED BY fix/tab-view-core; rest FIXED | ui/search/FindReplaceScreen.kt: warning on signed input, result recorded so the central replaceOriginal gate decides |
| C-2 | FIXED | ToolOutputs: reports, comparisons, AI review, slides and translations are never recorded, so they offer no replace |
| C-3 | signature part OWNED BY fix/tab-view-core; rest FIXED | ui/forms/FormsScreen.kt: save in place via replaceOriginal (atomic, trash) |
| C-4 | FIXED | ui/tools/ToolPassword.kt, MergeSplitTools.kt, ExportTools.kt; PdfOps.split takes a password |
| C-4b | FIXED | ui/ai5/AiToolsPanel.kt, ui/ai6/TDocTools.kt, ui/ai5/DocSet.kt |
| C-5 | FIXED | ExportTools.kt: password errors reach the prompt |
| C-6 | FIXED | core/pdf/Annotations.kt MarkupFrame |
| C-7 | FIXED | core/edit/TextWriter.kt TextEncodeCheck + confirm dialog |
| C-8 | FIXED | core/search/FindReplace.kt + core/edit/ContentEdit.replaceTextIn: one load for find and one load/save for apply; skipped blocks reported |
| C-9 | FIXED | FindReplace / SearchMark error snackbars |
| C-10 | FIXED | relayout note in the confirm dialog |
| C-11 | OWNED BY fix/convert-audit | core/convert6 |
| C-12 | FIXED | core/tools5/WordDiff.kt (anchors, paged, interruptible), CompareEngine.kt, CompareCore.kt (per page pair) |
| C-13 | FIXED | ExportTools.kt: recycle, unique folder |
| C-14 | FIXED | ui/tools5/Compare2Screen.kt: LockedPdf probe |
| C-15 | OWNED BY fix/convert-audit | Cv6Engine |
| C-16 | OWNED BY fix/convert-audit | Cv6Engine |
| C-17 | ExtractImagesScreen part FIXED; Cv6Results part OWNED BY fix/convert-audit | ui/convert6/ExtractImagesScreen.kt |
| C-18 | FIXED | ExtractImagesScreen.kt |
| C-19 | FIXED | Compare2Screen.kt AutoMirrored arrows |
| C-21 | FIXED | ui/search/SearchMarkScreen.kt |
| C-22 | FIXED | SearchUiCommon, HitList, ExtractImagesScreen, ui/tools/ColorNames.kt |
| C-23 | FIXED (already sanitised in the code; comment added) | ImageExtract6 names |
| C-24 | OWNED BY fix/convert-audit | ui/convert6/Cv6Save.kt |
| C-25 | FIXED | PdfOps.split / uniqueIn, MergeSplitTools |
| C-26 | FIXED | FormsScreen export messages |
| E-1 | FIXED | ui/common/Components.kt ResultSheet + core/pdf/ToolOutputs; E5Doc.export records its own source |
| E-2 | OWNED BY fix/tab-view-core (central gate) | E5Io.save / replaceOriginal |
| E-3 | FIXED | core/edit5 PageOps5 / PageOrganize / PageImport / E5Owner, ImposeScreen, BatesScreen; e5 page tools need ASSEMBLE (core/security/ReaderRestrictions.kt) |
| E-4 | FIXED | PageOps5.arrange via arrangeInPlace / pushDownInherited |
| E-5 | FIXED | core/edit5/PageLabelRemap.kt in every page op and in PdfOps.arrangeInPlace / insertBlankPage / deletePages |
| E-6 | FIXED | core/edit5/PageImport.kt |
| E-7 | FIXED | FormPrune (PageImport.kt) |
| E-8 | FIXED | unique part names |
| E-9 | FIXED | ui/edit5/CropResizeScreens.kt |
| E-10 | FIXED | CropResizeScreens.kt batch unlock |
| E-11 | FIXED | PageOrganizeScreen.kt, LayersScreen.kt |
| E-12 | FIXED | LayersScreen.kt busy guard |
| E-13 | FIXED | ui/edit5/E5Ui.kt |
| E-14 | FIXED | CropResizeScreens.kt |
| E-15 | FIXED | PageOpsScreen.kt, PageOrganizeScreen.kt |
| E-16 | FIXED | quick watermark tagged like the e5 one (PdfOps.watermark); quick blank page like the e5 one (T-11); Organize overwrite keeps a trash copy |
| E-17 | FIXED | PageOpsScreen.kt |
| E-18 | FIXED (temp files) / NOT-FEASIBLE here (state after process death) | E5Ui.kt |
| E-19 | FIXED | PageLabelsScreen.kt, LinksScreen.kt |
| OCR-1 | OWNED BY fix/tab-view-core (central gate) | applyInPlace |
| OCR-2 | FIXED | core/ocr OcrPrep / OcrPipeline, core/tools5/OcrRunner, ui/ocr |
| OCR-3 | FIXED | OcrStore.remove / merge, RepairedInput, ToUnicodeRepair |
| OCR-4 | FIXED | OcrPipeline.MAX_RENDER_PX, OcrStore.rescale |
| OCR-5 | FIXED | OcrPipeline.cancelling; PdfSession.closeAsync is NonCancellable |
| OCR-6 | FIXED | ui/tools5/OcrBatchScreen.kt |
| OCR-7 | FIXED | NpfPanels (with T-06) |
| OCR-8 | FIXED | OcrEngine, ui/ocr/OcrErrors.kt, OcrSheet, OcrToolsScreens |

Counts: 98 rows. 90 have a fix on this branch: C-1 and C-3 have only their non-signature parts here, C-17 only its
ExtractImagesScreen part, and for E-18 the state after process death is NOT-FEASIBLE here. 4 are OWNED BY
fix/tab-view-core (T-01, G-01, E-2, OCR-1), as are the signature parts of C-1 and C-3. 4 are OWNED BY fix/convert-audit
(C-11, C-15, C-16, C-24), as is the Cv6Results part of C-17.

## Tests (JVM, app/src/test)
- core/pdf/TabToolsProtectionTest: extract and split keep the encryption, permissions and key length (AES-128 /
  256, user or owner password), split never overwrites, forms and outline are kept; Arabic AES-256 Protect;
  owner ≠ open; blank page follows its neighbour; XMP sync; displayed-page mapping; the result sheet's replace target; password hand-over.
- core/edit5/PartProtectionTest (split / smart split / mix protection), PageInheritTest (inherited attributes on
  reverse / move / interleave / copy, merged fields), PageLabelRemapTest, E5LocksTest (REMOVE only with owner access).
- core/redact/PageScrubTest (no reachable text after max-security redaction; whole-page wipe), ProtectUnicodeTest.
- core/ocr/OcrStoreMergeTest; ui/pages/OrganizeLogicTest; core/pdf/MarkupFrameTest; core/edit/TextEncodeCheckTest;
  core/tools5/WordDiffPagedTest; ui/tools/ColorNamesTest.

## Central signed gate (fix/tab-view-core, merged from main facfb11)
The writers this branch touches that write over an original go through the central gate, and none has its own check:
- ResultSheet "Save in original", Forms "save in place" and Organize Overwrite call
  `DocumentRepository.replaceOriginal(target, result, password)`, which runs `SignedGuard.checkWrite`. The
  password is passed so that an encrypted signed file is inspected too.
- The quick actions go through `ReaderState.applyInPlace`.
- The e5 tools go through `E5Io.save` (`checkFullSave`).
- The organizer reports a refusal with `signedOrMessage`.
- The signed-input notes in Redact, Sanitize, Advanced protect and Find & replace are warnings only; none of them gates a write.
