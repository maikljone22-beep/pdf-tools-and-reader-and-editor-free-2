# Late sub-audit (redact / sanitize / protect advanced / quick protect-unlock) — written against main before fix/tab-tools-pages merged
D1 Critical RedactApplier.rasterize: old page only unlinked; still reachable via StructTree /Pg, widget /P, link/outline /Dest, named Dests, with its resources holding unredacted text. Fix: clear old page (empty contents, new resources, no annots) or repoint every reference.
D2 High PdfOps.protect quick sheet AES-128 (ISO-8859-1 passwords → Arabic becomes ????); ProtectAdvanced AES_128/RC4 accept non-Latin-1. Fix: AES-256 in quick; warn/reject non-Latin-1 for R2–R4.
D3 High RedactApplier.wipe: whole-page wipe keeps AcroForm /V and /ActualText /Alt /E of the page's structure elements.
D4 Med Redact/Sanitize/ProtectAdvanced: no isSigned warning; signatures left invalid in output → warn and remove/flatten signatures in output.
D5 High quick Protect: empty owner field → owner = open password. Fix random owner; refuse owner==open.
D6 Med applyAll + maxSecurity: keepPending written before rasterize → pending marks on that page lost and burned into image while text stays visible. Fix: write keepPending after rasterize or don't rasterize pages with pending marks.
D7 Low RedactScreen.kt:175 apply one mark: no confirmation, silently reuses last sanitize/maxSecurity.
D8 Med Sanitizer.kt:222,227,244: failed/nested/vertical glyph pages and failed TrueRedactor.apply skipped silently → report or rasterize.
D9 Med Sanitizer MARKUP set misses Redact (and Watermark/Screen) annotations (Redact /Contents holds the matched sensitive text).
D10 Low Sanitizer.kt:202, ProtectAdvanced doc.save(out), RedactMarks.kt:281 not atomic (tmp+rename).
D11 Med ProtectAdvancedScreen.kt:69-75 wrong current password swallowed by runCatching → no re-prompt, needsOwner false, raw English error later.
D12 High QuickController.kt:192-194,227-229 quick Protect/Unlock in place without signed check.
L1 RedactManualView.kt:63/65 prev/next arrows swapped.
L2 ProtectAdvanced: restrict on, permissions unchanged, no open password → enabled button fails with English "nothing to protect".
L3 ProtectAdvancedScreen.kt:95 generated owner password only shown in ResultSheet, no copy action.
L4 RedactState.kt:65-75,203-210 failed load silently drops /Redact marks + geometry; markImageAt failures show "no image here".
L5 RedactScreen.kt:240-264 batch: page range ignored; no-hit files not reported; Compose state written from IO thread.
L6 SanitizeScreen.kt:105, ProtectAdvancedScreen.kt:229-233, RedactScreen.kt:194-195 checkbox+label not one toggleable row (TalkBack).
L7 RedactScreen.kt:45-63 marks lost on process death (rememberSaveable / persisted marks).
Minor: Sanitizer.countEof reads whole file into memory; huge files fall back to 1.

## Verification and fixes (branch fix/redact-subaudit, from main 3561994)
Paths are relative to `app/src/main/java/com/smartdocs/editor/`. Most rows were already fixed on main by
fix/tab-tools-pages (see FIXED_5_tools_pages.md: R-1, R-3, R-4, R-6, R-8, R-9, R-10, R-L1, R-L7, P-2, P-5, P-11, P-L, T-12)
and fix/tab-view-core (the central signed gate). **Nothing was tested on a device.** The only checks were the GitHub CI build and the JVM tests.

| Row | Status | Evidence / change |
|---|---|---|
| D1 | ALREADY-FIXED on main | core/redact/RedactApplier.kt:211 `PageScrub.replacePage`. PageScrub.kt:57-68 re-points every reference (StructTree /Pg, annotation /P, outline, link, named and catalog Dests, OpenAction), drops the old page's widgets and fields, and empties the old page dictionary. Test PageScrubTest.maxSecurityReplacementLeavesNoReachableCopyOfThePage checks that no reachable object holds the text. |
| D2 | ALREADY-FIXED on main | core/pdf/PdfOps.kt:100-102 `newProtection` uses AES-256 (quick Protect, PdfOps.kt:520). core/redact/ProtectAdvanced.kt:81,106 `passwordsFit` and NonLatinPasswordException for RC4 and AES-128. ProtectAdvancedScreen shows the Latin-1 warning and disables the button. Tests: ProtectUnicodeTest, TabToolsProtectionTest.arabicPasswordAes256AndRealOwner. |
| D3 | ALREADY-FIXED on main | RedactApplier.kt:67 calls `PageScrub.wipePage` (PageScrub.kt:75-87), which removes field /V /DV /AP and the structure /ActualText /Alt /E. Test: PageScrubTest.wholePageWipeRemovesFieldValuesAndStructureText. |
| D4 | ALREADY-FIXED on main | Signed-input warning in RedactScreen.kt:85/223, SanitizeScreen.kt:107 and ProtectAdvancedScreen.kt:74/158. `PageScrub.stripSignatures` in RedactApplier.kt:88, Sanitizer.kt:232 and ProtectAdvanced.kt:112. |
| D5 | ALREADY-FIXED on main | PdfOps.kt:101 uses a random owner when the field is empty or equal to the open password. QuickController.kt:240 refuses owner == open. ProtectAdvanced.kt:87 `ownerFor`. The generated owner password is shown with a copy action. Tests: ProtectUnicodeTest.ownerNeverEqualsOpen, TabToolsProtectionTest. |
| D6 | ALREADY-FIXED on main; refactored + tested here | Pending marks are written only after rasterising. The logic is now in `RedactApplier.pendingBeforeRaster` (RedactApplier.kt:87,114) and `writePending` (:104, called at :216). The stored /Redact marks are removed before rendering (:78), so they are never burned into the image. New test: RedactSubauditTest.pendingMarkOfARasterisedPageLandsOnTheReplacementPage plus pendingMarksAreNotWrittenBeforeTheRasterStep. |
| D7 | FIXED | ui/security/RedactScreen.kt:219: "apply this mark" now opens the confirm dialog, with its own title and sanitize / max-security toggles and a note that the other marks stay pending. Before, it applied at once. |
| D8 | ALREADY-FIXED on main | Sanitizer.kt:257 (UNREADABLE), the NESTED / VERTICAL / ODD_SIZE counts, and :297 (REWRITE_FAILED) go into the report. RedactApplier rasterises the page when `needsRaster` is set. |
| D9 | ALREADY-FIXED on main | Sanitizer.kt:68-69: MARKUP includes Redact and Watermark. Screen is removed by MULTIMEDIA (SanitizeExtra.kt:114). Both are in the defaults. Tests: PageScrubTest.sanitizeCommentsRemovesRedactMarksAndTheirReplies and the new RedactSubauditTest.sanitizeDefaultsRemoveRedactWatermarkAndScreenAnnotations. |
| D10 | ALREADY-FIXED on main; remainder FIXED | `RedactIo.saveAtomic` is used in Sanitizer.kt:233, ProtectAdvanced.kt:115, RedactMarks.kt:281 and RedactApplier.kt:217. The remaining plain `renameTo`/`copyTo` fallback (RedactApplier.kt:94) now uses the new `RedactIo.moveInto` (copy to a temp file next to the output, then rename). Test: RedactSubauditTest.moveIntoReplacesAnExistingOutput. |
| D11 | ALREADY-FIXED on main | ProtectAdvancedScreen.kt:86-96: a wrong current password shows a snackbar and asks again. |
| D12 | ALREADY-FIXED on main (central gate, no new gate added) | Quick Protect and Unlock go through QuickController.kt:118 to `ReaderState.applyInPlace`, which runs `SignedGuard.decide` (ReaderState.kt:911). |
| L1 | ALREADY-FIXED on main | RedactManualView.kt:64/66: AutoMirrored Left = previous, Right = next. |
| L2 | FIXED | ProtectAdvancedScreen.kt:108: with restrict on, every permission allowed and no open password, the Protect button is disabled and a localized hint (`ttrd_nothing_to_protect`) is shown. The core `require` stays (test RedactSubauditTest.nothingToProtectIsRefused). |
| L3 | ALREADY-FIXED on main | ProtectAdvancedScreen.kt:237 OwnerPasswordDialog with copy. |
| L4 | FIXED | RedactState.load sets `loadFailed` and RedactScreen.kt:88 shows `ttrd_marks_load_failed`. `markImageAt` returns ImageMark.ADDED/NONE/FAILED, and a failed inspection shows `ttrd_image_failed` instead of "no image here". |
| L5 | FIXED | RedactScreen.runBatch uses the page range (`RedactState.searchPages`, shared with the single-file search), reports files without matches (`ttrd_batch_no_hits`) and counts a failed search as skipped. Progress text and the report are set on Dispatchers.Main. |
| L6 | ALREADY-FIXED on main | RedactScreen.kt:243-244 ToggleRow, SanitizeScreen.kt:119 toggleable ListItem, ProtectAdvancedScreen PermRow is ToggleRow (:269). |
| L7 | ALREADY-FIXED on main | core/redact/RedactDrafts.kt holds a persisted draft, restored in RedactScreen (draft snackbar). |
| minor countEof | FIXED | Sanitizer.kt:194-215: the file is streamed in 64 KiB chunks with KMP matching that carries across chunk borders. Before, it was read whole, and an OOM fell back to 1. Test: RedactSubauditTest.countEofStreamsAcrossChunkBorders. |

Strings: ar (values/strings_tt_rd.xml) and en (values-en/strings_tt_rd.xml). Counts: 21 rows. 13 ALREADY-FIXED on main, 2
ALREADY-FIXED with a remainder or refactor done here (D6, D10), 6 FIXED (D7, L2, L4, L5, countEof; D10 remainder). 0 NOT-FEASIBLE.
