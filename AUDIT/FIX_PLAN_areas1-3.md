# Fix plan — audit areas 1–3 (Reader/rendering · Search/annotation/undo/redaction · Data integrity)

Author: audit session **pdf-3c**. Approved by the user to START FIXING areas 1–3 (2026-10-27).
Source findings (read these for full evidence, file:line, proposed fix, validation):
- `AUDIT\01_reader_rendering.md` (RD-xx), `AUDIT\02_navigation_annotation.md` (AN-xx), `AUDIT\03_data_integrity.md` (DI-xx)
- Line numbers refer to commit **da74d31** (snapshot `D:\pdf\audit-wt`). Re-locate before editing — main may have moved.
- Areas 4–7 (security, OCR/tools, UX/i18n, architecture) are NOT part of this plan unless listed below.

## Working rules (important)
1. **Own worktree + branch.** `git -C D:\pdf\SmartDocs-project-v0.4.0 worktree add -b fix/audit-1-3 D:\pdf\fix-wt main`,
   copy `local.properties`. Build/test ONLY there. Never run gradle in `D:\pdf\SmartDocs-project-v0.4.0`,
   `D:\pdf\sd-build` (session pdf-ad) or `D:\pdf\audit-wt` (pdf-3c).
2. **Coordinate with session pdf-ad before editing reader files.** pdf-ad's page-flip agent plans to integrate into
   `ui/reader/*` (ReaderScreen/PageView). pdf-ad also owns: core/convert6, ui/convert6, ui/tools/ToolsScreen.kt,
   ToolScreens.kt, ui/home/HomeScreen.kt, ui/pageflip, core/pageflip, core/ocr, ui/ocr, core/search/ArabicTextFix.kt.
   Do not edit those; if a fix needs them, ask pdf-ad.
3. **One stage = one or more small commits**, each with a regression test (JVM unit test where possible). Run
   `./gradlew testDebugUnitTest` (baseline: 384 tests pass at da74d31) + `:app:assembleDebug` after each stage.
4. **Never modify user documents**; test PDFs only as copies in your scratch dir.
5. No push to GitHub, no merge to main, no release build without asking the user. Commit messages end with the
   attribution line from your session reminder.
6. If a finding turns out wrong when you look at the code, don't fix it — record "not reproduced" in
   `AUDIT\FIX_LOG_areas1-3.md` (create it; log every change, test, and decision).
7. Questions/blockers → message session **pdf-3c**. Decisions that need the user are marked 🧑 below — ask the user, don't guess.
8. No device/emulator exists here: say "needs device test" for anything gesture/lifecycle-related; never claim it works on device.

## Stage 1 — Stop data loss on save/open (P0, mostly S effort) — do first
| ID | Problem | Fix (smallest) |
|---|---|---|
| DI-02 / AN-02 | `DocumentRepository.localFile` (≈:58-70) re-imports the source over an EDITED working copy whenever sizes differ (read-only Telegram/e-mail sources; or a truncated original after a crash) → all annotations lost | Store per working copy: last imported size+mtime+cheap hash and "last successful write-back" marker. If the working copy has edits not written back, do NOT overwrite: keep working copy, import the changed source as a new file (or ask). Unit-test the decision function (pure). |
| DI-01 | `writeBack` (≈:94-96) opens `"wt"` (truncate) then streams; fallback `"w"` may not truncate → corrupt original on crash/full storage | Pre-check free space ≥ file size; write, then verify length via query/`ParcelFileDescriptor.statSize`; for `"w"` fallback use `openFileDescriptor("rw")` + `truncate` via FileChannel; if the provider supports `DocumentsContract.createDocument`+rename, write a sibling temp and rename. Keep the working copy as the authority until verified (ties into DI-02). |
| DI-04 | Files → Rename (`ui/files/FilesScreen.kt:≈317`) replaces an existing file silently | exists() check (case-insensitive), reject `/` `..`, show error on false return. |
| DI-05 / AN-12 | `core/pdf/Annotations.kt:≈78-84` swallows per-annotation write failures; `ReaderState.save()` then clears all pending | Return written/failed; keep failed ones pending + dirty; snackbar "N annotations could not be saved". |
| DI-29 | `ui/common/WriteBackUi.kt:≈58-65` Save-a-copy crashes on IO error / reports success with no stream | throw on null stream, verify length, runCatching + error message, setOrigin only on success. |
| DI-13 | "Pick the original again" accepts any file and overwrites it | compare display name/size with recorded import; confirm dialog on mismatch. |
| DI-06 | Delete falls back to permanent delete if trash move fails (`FilesScreen.kt:≈326`) | put trash on same volume as docsDir; if move fails, ask "delete permanently?" instead of doing it. |
| DI-08 | Room v1, `exportSchema=false`, `fallbackToDestructiveMigration()` (`core/data/AppDatabase.kt:154-168`) | `exportSchema=true` + `room.schemaLocation`, commit `app/schemas/`, replace destructive fallback with `fallbackToDestructiveMigrationOnDowngrade()` only. Add a MigrationTestHelper note for next version. |
| DI-14 | `allowBackup=true`, no rules → tokens/API key/signatures/licence backed up & 25 MB quota blown | add `backup_rules.xml` + `data_extraction_rules.xml`: exclude token/key prefs, licence device id, caches, history, tessdata, docs copies (or user decides 🧑 whether documents should be backed up). |

## Stage 2 — Reader lifecycle: never drop edits / undo on navigation (P0/P1, M)
Coordinate with pdf-ad first (touches ReaderScreen/ReaderState).
| ID | Problem | Fix |
|---|---|---|
| AN-01 / DI-10 | Only Back checks `dirty`; navigating to Settings/AI/e-sign/links/view5 from the reader disposes `ReaderState` (`remember` + `DisposableEffect{close()}` ≈ReaderState.kt:654) without saving | one `guardedNavigate{}` used by every outgoing `nav.navigate` from the reader (autosave or discard dialog, same rule as Back); and `close()` saves in `ioScope`/NonCancellable when dirty and not discarded. |
| AN-03 / DI-11 / RD-25 | `DocHistory` deleted in `close()` → undo lost on any navigation, tab switch, keyboard attach, font-scale/density change | hoist `ReaderState` (or at least `DocHistory` + `externalBase`) into a NavBackStackEntry-scoped ViewModel; dispose in `onCleared`. Add `keyboard|navigation|density|fontScale|layoutDirection` to MainActivity configChanges. |
| DI-09 | pending annotations/form values only in memory → lost on process death | debounced (~1 s) JSON draft journal in `filesDir/drafts/<hash>/`; restore on open (mark dirty); delete after successful save. Unit-test serialisation round-trip. |
| DI-07 | editing a stored annotation removes it from the file before save | on open, detect leftover `.X.annotedit.pdf` and offer restore; or keep the file untouched and hide the annotation while editing. |
| DI-12 | write-back failures after leaving reader are never shown | persist failures (small Room table or prefs), banner on next open + badge in Recents. |
| RD-02 | after OS kill, reader reopens at route page not last page | Recents navigate with page = -1 (DB decides) or make route page one-shot via SavedStateHandle. |

## Stage 3 — Undo/redo correctness (newest commit da74d31) (P1)
| AN-04 | taking a stored annotation out for editing isn't a history step → undo duplicates it | record conversion as a FileSnapshot step (combined undo). |
| AN-05 | snapshot copy failed but memory steps kept → undo replays annotations already in file | on snapshot failure, clear/merge memory steps; test with a failing copy. |
| AN-08 | quick-tool / AI "Undo" silently undoes all newer changes | only offer it when it's the top step; otherwise disable or confirm with count. |
| AN-35 | no tests for ReaderState-history integration | add JVM tests around DocHistory + the save path (fake file ops). |

## Stage 4 — Redaction must really remove content (P0 security)
| AN-07 | removed/blanked images & form XObjects stay in `/Resources` (`core/redact/TrueRedactor.kt:62-68,173-176`) | after apply: drop every XObject (and nested form resources) no longer referenced by any `Do` in page content/kept forms/annotation APs. |
| AN-09 | `/ActualText` (written by the app's own ArabicShaper for every edited Arabic line) survives | strip ActualText/Alt/E on BDC spans and struct elements touching marks. |
| AN-10 | sanitize/maxSecurity default off; batch skips protected files silently | default sanitize on in redaction flow; report skipped files. |
| — | Validation | JVM test: build a PDF with text+image+ActualText under a mark, redact, re-parse → assert no text, no original image stream, no ActualText. |

## Stage 5 — Reader & annotation correctness (P1)
| RD-01 | corrupt file → endless spinner (snackbar clears `error`) | separate `openError` state + full-screen error with Retry/Open with/Back, localized. |
| RD-04 | `pointerInput(Unit)` keeps first view mode/list state (`ui/reader/view/PageViewParts.kt:40`, `PageCanvas.kt:131`) | key on mode/listState/pagerState or `rememberUpdatedState`. (needs device test) |
| RD-05 | edge tap zones measured per page (spine taps flip in facing mode) | compute from viewport; disable while zoomed & not at edge. |
| RD-03 | `ConnMarker.find` may read up to 64 MB before first render | run after first render / cache per (path,size,mtime). |
| AN-06 | annotations/selection/search highlights ignore /Rotate & CropBox origin (`PageGeometry.kt:30-32`) | use `PageFrame` (already used by redaction) in PageGeometry. Unit-test mapping for rotate 0/90/180/270 + offset cropbox. |
| AN-13 | eraser ignores saved ink | hit-test `existing` ink too; delete via one batched fileOp (one undo step). |
| AN-15 | global search misses visual-order Arabic | OR reversed terms in FTS query (`FullTextIndex.kt:106`, `IndexText.kt:301`). Note: ArabicTextFix is pdf-ad's — don't edit it. |
| AN-22 | reorder/move/paste rebuild the PDF (lose TOC, forms, labels, encryption) (`OrganizeState.kt:194-219`, `PdfOps.kt:93-101`) | reorder in place on the loaded document's page tree; keepPages only for extract/split. Test: outline + AcroForm survive reorder. |
| DI-20 | external change detected by size only | size + mtime + cheap hash (shared with DI-02). |
| DI-22 / DI-03 | rename/move/delete don't update recents/tags/bookmarks/origins; stale origin maps | `DocumentRepository.rename/move/delete` updating Room + origins in one place; used by Files. |

## Stage 6 — Signed & protected PDFs (P1) — overlaps area 5 (DOC-01/05/06); do only the annotation-save path here
| AN-28 / DI-27 | annotation save = full rewrite → invalidates signatures | detect `/Sig` on open; use `saveIncremental` for signed docs, or warn + save as copy. |
| DI-28 / AN-29 | `PdfOps.kt:42-50` re-protects with owner=user password, AES-128 | preserve original encryption/key length (open with owner pw where known); else warn. Test: permissions & key length preserved. |

## Stage 7 — Cloud/sync safety (P1)
DI-23 (download over unsynced edits), DI-24 (failed uploads not retried; clear-cache deletes unsynced), DI-25 (no mass-deletion guard). See 03_data_integrity.md for fixes.

## Later (P2/P3, not now unless user asks)
RD-14 incremental saves/perf, RD-30 TalkBack page semantics, AN-14 stylus-only mode, RD-11 night-mode inversion,
RD-22 per-document view state, AN-16/17/18/36 search UX, AN-21 tool memory, RD-20/21/34 page labels/thumbnails.
🧑 DI-16 (move documents out of app-specific storage so uninstall doesn't delete them) — product decision for the user.

## Definition of done per stage
- unit tests added for every confirmed defect fixed in that stage, all tests green (≥384 + new), debug APK builds;
- `AUDIT\FIX_LOG_areas1-3.md` updated (finding → commit → test → "needs device test" items);
- short report to pdf-3c and the user.
