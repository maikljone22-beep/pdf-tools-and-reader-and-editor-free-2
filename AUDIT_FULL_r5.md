# SmartDocs — Unified Product, UX & Technical Risk Audit (r5)

- **Date:** 2026-10-27
- **Auditor:** session pdf-3c, with 7 read-only specialist sub-audits.
- **Code audited:** commit **da74d31**, the snapshot in `D:\pdf\audit-wt`. The in-flux areas (convert6 and pageflip) were also read from the working tree.
- **Scope:** the whole application. That is the Android app (≈100k lines of Kotlin, 70 packages) plus the Cloudflare Workers in `ai-proxy/`.
- **Status:** **audit complete; no source code or user document was modified by the audit.** Fixes for areas 1–3 were started after the user approved them: session pdf-39 is working on branch `fix/audit-1-3` (see §15).

**Detailed evidence.** Every finding below has file:line evidence, a proposed fix, effort, regression risk and a validation step in the area files:

| File | Area | Findings |
|---|---|---|
| `AUDIT/01_reader_rendering.md` | Reading experience, rendering, performance, read-aloud | RD-01…42 |
| `AUDIT/02_navigation_annotation.md` | Search, bookmarks, annotation, undo/redo, redaction, page organize | AN-01…36 |
| `AUDIT/03_data_integrity.md` | Persistence, file identity, write-back, DB, backup, cloud/sync | DI-01…45 |
| `AUDIT/04_security_privacy_backend.md` | Security, privacy, backend workers, integrations | SEC-01…40 |
| `AUDIT/05_document_intelligence_tools.md` | OCR, Arabic, tools, Office, forms, signatures, malformed input | DOC-01…39 |
| `AUDIT/06_ux_a11y_i18n.md` | Screen inventory, UX, accessibility, i18n/RTL, devices | UX-01…39 |
| `AUDIT/07_architecture_deps_tests.md` | Architecture, concurrency, dependencies, tests, release | ARCH-01…35 |
| `AUDIT/08_lint_tests.md` | Executed lint and unit tests | LINT-01…05 |

That is **281 findings** in total. Each one is labelled CONFIRMED (traced in code), PROBABLE or HYPOTHESIS.

---

## 0. What was executed vs. what was not (validation honesty)

| Item | Result |
|---|---|
| Unit tests `testDebugUnitTest` (isolated worktree) | **Ran: 384 tests, 0 failures, 0 errors** |
| Android lint `lintDebug` | **Ran: 320 errors, 480 warnings, 20 hints.** None of the errors is a crash class: 309 are MissingTranslation, plus 7 ByteOrderMark, 3 AppLinkUrlError and 1 PropertyEscape (local machine only). Details in `AUDIT/08_lint_tests.md` |
| Real PDFs | **Ran:** copies of 3 Arabic chemistry PDFs analysed with PyMuPDF/fontTools. Finds broken text layers that the app's own detector misses (DOC-04) |
| Native libraries | **Checked:** pdfium-core 2.0.1 and tesseract4android 4.8.0 `.so` files are 16 KB-page aligned. ML Kit/Play natives were not verified |
| The app on a device or emulator | **NOT RUN.** There is no emulator because the hypervisor is off, and no device is attached. Gestures, frame rate, memory, lifecycle and TalkBack are judged from code only, and each item that needs a device is listed in the area files under "Needs device testing" |
| Instrumented tests (androidTest) | **None exist** (ARCH-24) |
| Backend workers | **Not called.** None of the `*.properties` files exist, so every server feature is disabled in the current build (ARCH-12) |
| Regression tests for confirmed defects | Not added by the audit, which is read-only. Each fix stage adds them (see §12) |

---

## 1. Executive summary

SmartDocs is a very broad product: a reader, annotation, editing, OCR, Office, forms, signatures, redaction, cloud sync, shared review, e-sign and AI. Rendering and the core reading loop are well engineered:
- PDFium calls are serialized.
- Renders are cancelled on fast scroll.
- Pages are re-rendered sharply at zoom, using tiles.
- Documents are closed safely.
- The last page and session are restored.

The weak point is **everything that writes**: saving, write-back, page operations, signing and redaction. That is where the audit found almost all of its serious defects, and they share a few root causes:

1. **Saving is not atomic and the source of truth is ambiguous** (DI-01/02/03, AN-02).
   - Each opened document is a private working copy, linked to the user's file only by URI strings in SharedPreferences.
   - The copy back to the original truncates it first ("wt") and then streams, with no temp file or verification.
   - The next open can re-import a truncated or unchanged read-only source *over* the edited copy.
   - **Result: silent loss of annotations or a corrupted original.**
2. **The reader's state lives in composition** (AN-01, AN-03, DI-09/10/11, RD-25, ARCH-26).
   - `ReaderState` is a plain `remember`. No ViewModel or saved state is used anywhere.
   - Navigating anywhere except Back drops unsaved annotations and deletes the new unified undo history.
   - Process death, attaching a keyboard or changing the font scale does the same.
3. **Every write is a full PdfBox rewrite or a rebuild into a new document** (RD-14, AN-22, AN-28, DI-27/28, DOC-01/02/03/05/06). As a result:
   - **digital signatures are invalidated**
   - **owner passwords are replaced by the user password**, re-encrypted at AES-128
   - Optimize/PDF-A **silently drop encryption**
   - reorder/paste/split **drop the TOC, form fields, page labels and encryption**
4. **Errors are swallowed systemically** (ARCH-07):
   - The code has 1,173 `runCatching` calls and **0 log statements**.
   - Failed annotation writes are cleared from memory (AN-12/DI-05).
   - "Save as" and "Save a copy" can report success when nothing was written (DI-29/30).
   - 84 places show raw exception text to users (UX-17).
5. **Redaction is real for page content but leaves recoverable data** (AN-07/09/10, SEC-16):
   - removed images and form XObjects stay in `/Resources`
   - `/ActualText` survives, and the app writes it itself for every edited Arabic line
   - sanitize is off by default
6. **Arabic is a first-class audience but not first-class in text handling** (DOC-04, AN-15, AN-23/24, DOC-11/18/21, UX-01/03):
   - Broken Arabic text layers are treated as good: in the chemistry PDFs, 14/17 and 42/45 pages are broken, yet the detector reports 0. Search, copy, AI and OCR then skip or mis-handle them.
   - Visual-order Arabic is never found by global search.
   - Persian letters and alef wasla are not shaped.
   - The **language setting doesn't change the language**.
   - **307 strings are untranslated** in English, and Arabic is the default for every other locale.
7. **Release is blocked** (ARCH-01/02/12):
   - The release signing key is lost, so a Play update is impossible without Play App Signing key-reset support.
   - `targetSdk 35` is probably below Play's current requirement (PROBABLE; check Play Console).
   - All server features are unconfigured.
   - There is no instrumentation, no PDF fixture tests, and CI never builds a release.
8. **Backend trust model** (SEC-01/02/04/06):
   - The AI proxy is protected only by a token extractable from the APK, with no default rate limit, so LLM spend is uncapped.
   - Shared-review comments are merged into the user's PDF without an allow-list. Launch links planted there can share private app files with one tap (SEC-03).
   - E-sign senders can sign on behalf of signers.
   - These are latent today because the servers are not configured, but they block enabling those features.

**Bottom line:**
- **Not release-ready.** The blockers are in §14.
- The single most valuable investment is **one safe-save pipeline**: atomic write-back, a working-copy-is-authority rule, incremental saves for signed documents, and preserved encryption. Add a reader ViewModel and a draft journal next to it.
- Together these close about 25 P0/P1 findings across four areas. Most of the rest are small, isolated fixes.

**Counts by priority** (after merging duplicates, about 250 unique issues): roughly **P0 ≈ 14, P1 ≈ 75, P2 ≈ 110, P3 ≈ 70, P4 ≈ 10**.

---

## 2. Architecture & dependency overview (from evidence)

- **Shape:**
  - Single `:app` module.
  - `core/*` holds 37 packages of logic and `ui/*` holds 33 packages of Compose screens. `MainActivity` hosts the NavHost: 5 tabs plus about 50 routes, and a `tool/{id}` dispatcher with 68 + 21 ids.
  - There is no DI framework. Objects and singletons are reached through `SmartDocsApp`.
  - No ViewModels or `SavedStateHandle` are used; screen state is `remember`/`mutableStateOf` (ARCH-26).
  - Settings are fragmented across **26 SharedPreferences files**, DataStore and Room (ARCH-27).
- **Rendering:**
  - `core/pdf/PdfSession` wraps PDFium (io.legere:pdfiumandroid 2.0.1).
  - Each session has its own Mutex, inside a library-global lock. There is a 64 MB bitmap LruCache per session and an LRU of 12 native pages.
  - `close()` uses `runBlocking`, called from `onDispose` on the main thread, which is an ANR risk (ARCH-05).
- **Editing:**
  - PdfBox-Android 2.0.27 (a stale fork; ARCH-15) is used **directly from 117 files, 15 of them UI**, with 55 ad-hoc `PDDocument.load` calls. None sets a memory policy, and many ignore the password (ARCH-06).
  - There is no single document-write service, which is why the save rules above are inconsistent.
- **Layered delivery rounds:**
  - The `*5`/`*6` packages (edit5, forms5, office5, ai5, view5, tools5, share5, convert6, gaps6) are **live layers on top of the originals, not dead copies** (ARCH-22).
  - There are 3 OOXML stacks and 4 DOCX writers.
  - Confirmed dead code includes DocxWriter2, XlsxWriter, AnnotReplies, WifiTransferScreen, DriveFolderPicker, ReadAloudVoiceSheet, DocAgentSheet, and the `material3-window-size-class` dependency (ARCH-23, UX-10).
- **Persistence:**
  - Room v1 with `exportSchema=false` and `fallbackToDestructiveMigration()` (DI-08/ARCH-04).
  - Documents are copied into `getExternalFilesDir/Documents` and deleted on uninstall (DI-16).
  - The link from working copy to source is held in SharedPreferences (DI-03).
- **Background work:** WorkManager runs the index, sync and batch jobs. The batch worker has no foreground service (DOC-13).
- **Network:**
  - OkHttp is used for the AI proxy, OneDrive/Dropbox/Box/Drive, and the collab/share/esign/connected/licence workers in `ai-proxy/`.
  - Cleartext is allowed for the whole app (SEC-20/ARCH-33/LINT-04).
- **Other engines:**
  - Tesseract4Android, with models downloaded from a mutable GitHub branch with no checksum (ARCH-17).
  - ML Kit document scanner and digital ink.
  - Rhino for form JS, in a well-designed sandbox but with no memory limit (SEC-17/DOC-32).
  - BouncyCastle 1.72 for signatures, with known CVEs (ARCH-14).
  - Billing 9.1, AdMob 24.4 with UMP (off by default).
- **Build:**
  - AGP with Kotlin 2.3 and Compose BOM 2025.03.01; minSdk 26 / target 35 / compile 36.
  - R8 is on with broad keep rules (ARCH-20), but no mapping retention and no crash reporting (ARCH-21).
  - JitPack is a global, unpinned repository, with no dependency verification or lockfile (ARCH-16).
  - 25 libraries have newer versions (LINT; upgrade only with testing).
- **Licences:** no GPL or AGPL dependencies. Rhino (MPL-2.0) is missing from the licences screen, and NOTICE files are stripped (ARCH-31).

---

## 3. Categorized capability inventory (summary)

The full tables are in each area file under "Capability inventory", and the screen inventory is the table in `06_ux_a11y_i18n.md`. Legend: **W** works, **P** partial, **B** broken, **S** stub or not wired, **M** missing.

| Domain | Status highlights |
|---|---|
| Reading | **W:** continuous / single / facing, fit width/page, pinch re-render + tiles, last page, session restore, back-to-position, keyboard keys, sepia/page colour, outline, bookmarks panel, password open. **P:** zoom range (no below-fit, double-tap ignores the finger), loading UI, fullscreen, thumbnails (open at page 1), night mode (CPU inversion), read-aloud. **B:** corrupted-file state (RD-01), resume after process death (RD-02). **S:** page labels (RD-20). **M:** Ctrl+wheel zoom, TalkBack page semantics, per-document view state |
| Search / navigation | **W:** in-document Arabic-aware search with normalization. **P:** hit navigation is per page only, cannot be cancelled, global FTS misses visual-order Arabic. **M:** search history, query carried into the reader from global results |
| Annotation / study | **W:** real in-PDF annotations, atomic local swap. **P:** eraser (unsaved ink only), edit of stored annotations (lossy, not crash-safe), undo/redo (wiped on navigation, duplicates). **B:** coordinates on rotated or cropped pages (AN-06). **M:** stylus-only/palm rejection, per-tool colour memory |
| Page organize | **W:** delete/rotate/insert. **B:** reorder/move/paste lose TOC, forms, labels, encryption (AN-22) |
| Redaction | **W:** glyph/vector/image removal, raster fallback. **P:** leftover XObjects, ActualText, sanitize off (AN-07/09/10) |
| OCR / Arabic | **W:** mixed-page de-duplication (OCR twice doesn't duplicate). **P:** language auto-detect (drops English <10%), mixed-page plan over-OCRs, layer misaligned on cropped pages. **B:** broken-text-layer detection (DOC-04) *(in-flux; pdf-ad is investigating)* |
| Tools (compress, PDF/A, merge, split, batch) | **P:** three different compress engines (UX-08), PDF/A rasterizes non-embedded-font pages and deletes annotations without appearances, Optimize drops encryption, batch jobs are not foreground, delete each other's temp files and store passwords in plaintext |
| Office | **W:** DOCX/XLSX/PPTX open, edit and save; 54 Excel functions. **P:** XLSX array and shared formulas degrade (DOC-27). Packages are fully inflated in memory (DOC-28) |
| Forms | **W:** AcroForm fill, sandboxed JS. **P:** always a full save (breaks signatures), multi-line Arabic wraps in reverse order |
| Signatures | **W:** CMS/PAdES, TSA, DocMDP, incremental signature append. **B:** visible signature/seal does a full rewrite first and destroys existing signatures (DOC-01). **P:** the verifier's "valid" means only CMS integrity (DOC-29). Camera photo of a signature probably fails (SEC-12) |
| Files | **W:** browse, trash, copy, unzip (zip-slip safe). **B:** rename overwrites (DI-04). **P:** delete falls back to permanent delete, identity is by URI string, recents/tags not updated on rename or move |
| Cloud / sync | **W:** PKCE OAuth, sync snapshot/queue with locks and unique work. **P:** download over unsynced edits, no upload retry, no mass-delete guard. All are disabled in this build (no config) |
| Backend (review / share / e-sign / connected / licence / AI) | Implemented but **unconfigured**. There are trust-model gaps (SEC-01/02/04/06/10) |
| i18n / a11y | **B:** language setting (UX-01), 307 missing English strings (UX-03). **P:** RTL chevrons/arrows, plurals (2 of 254), TalkBack labels and switch semantics, touch targets |

---

## 4. Prioritized risk register (top items; full register = the area files)

Severity / likelihood / impact / status are summarized here. The ID links to its evidence. "=" marks duplicates found by more than one sub-audit, merged into one item.

| # | ID(s) | Category | Sev | Pri | Status | One-line |
|---|---|---|---|---|---|---|
| 1 | DI-01 = AN-02 | Data integrity | Critical | P0 | CONFIRMED (lead re-verified `DocumentRepository.kt:94-96`) | Write-back truncates the original before streaming; a crash or full storage corrupts it |
| 2 | DI-02 (+DI-20) | Data integrity | Critical | P0 | CONFIRMED | Reopening a read-only or changed source silently overwrites the edited working copy (all annotations lost) |
| 3 | AN-01 = DI-10 | Data integrity | Critical | P0 | CONFIRMED | Navigating from the reader to Settings/AI/e-sign/links discards unsaved annotations |
| 4 | UX-02 | Data integrity | High | P0* | CONFIRMED | Default "Save in original" on tool results overwrites the source (for example a low-quality compress) with no backup or undo; failure is swallowed |
| 5 | AN-07, AN-09, AN-10, SEC-16 | Security | Critical | P0 | CONFIRMED (lead re-verified no resource pruning) | Redacted images and XObjects plus ActualText remain extractable |
| 6 | DOC-01, AN-28 = DI-27, DOC-02 | Integrity / security | High | P0/P1 | CONFIRMED | Annotating, form filling or adding a visible signature invalidates existing digital signatures |
| 7 | DI-03, DI-04 | Data integrity | High | P0 | CONFIRMED (DI-04 lead re-verified) | Stale origin maps can overwrite an unrelated file; Rename overwrites an existing file |
| 8 | ARCH-01, ARCH-02 | Release | Critical | P0 | CONFIRMED / PROBABLE | Signing key lost; targetSdk below Play's requirement |
| 9 | DI-08 = ARCH-04 | Data integrity | High | P1 (P0 before the next schema change) | CONFIRMED (lead re-verified) | Destructive Room migration wipes signatures, stamps, bookmarks, tags and recents |
| 10 | DI-28 = AN-29 = DOC-06 = SEC-14, DOC-05, SEC-15 | Security | Medium | P1 | CONFIRMED | Save re-protects with owner = user password at AES-128; Optimize/PDF-A drop encryption silently |
| 11 | AN-22 = DOC-03 | Data integrity | High | P1 | CONFIRMED | Reorder/move/paste/split drop the outline, AcroForm, labels and encryption |
| 12 | AN-03, DI-11, RD-25 | Data integrity | High | P1 | CONFIRMED | Undo history deleted on navigation, tab switch, keyboard, font scale |
| 13 | DI-09, DI-07 | Data integrity | Med-High | P1 | CONFIRMED | Pending edits live in memory only; an annotation being edited is removed from the file until save |
| 14 | DI-14 = SEC-07 = ARCH-19, DI-15, DOC-15/16 | Privacy | High | P1 | CONFIRMED | Backup with no rules: plaintext OAuth refresh tokens, AI key, licence id, signatures, batch passwords; 25 MB quota probably exceeded, so nothing is backed up |
| 15 | DI-05 = AN-12, DI-29, DI-30, ARCH-07, ARCH-08 | Reliability | High | P1 | CONFIRMED | Silent write failures; success reported for nothing written |
| 16 | SEC-02 + SEC-03 | Security | High | P1 | CONFIRMED | Review participants can plant Launch/GoToR links; one tap shares any app-readable file through the FileProvider (covers filesDir) |
| 17 | SEC-01, SEC-04, SEC-06, SEC-05, SEC-10 | Backend security | High | P1 (blocks enabling the servers) | CONFIRMED / PROBABLE | AI proxy cost exposure; e-sign forgery by the sender; review identity is a client claim; pdf.js 3.11 with eval; forgeable Pro codes |
| 18 | DOC-04 (+AN-15, DOC-21) | Correctness | High | P1 | CONFIRMED with real PDFs | Broken Arabic text layers are treated as good: wrong search, copy, AI, and OCR skipped *(in-flux)* |
| 19 | AN-06, DOC-09, DOC-30/31 | Correctness | High | P1 | CONFIRMED | Coordinates ignore /Rotate and the CropBox origin (annotations, highlights, OCR layer, watermark, page numbers, signature placement) |
| 20 | UX-01, UX-03 | i18n | High | P1 | CONFIRMED | The language setting doesn't change the language; 307 strings untranslated; Arabic is shown to every non-Arabic locale |
| 21 | UX-04, DOC-13/22 | Reliability / UX | High | P1 | CONFIRMED | Long operations can't be cancelled; batch jobs aren't foreground (killed) |
| 22 | RD-01, RD-02, RD-03, RD-04 | Reader | High | P1 | CONFIRMED (RD-04 PROBABLE) | Endless spinner on a corrupt file; wrong resume page; whole-file scan before the first page; stale gesture handler |
| 23 | DI-23, DI-24, DI-25 | Data integrity | High | P1 | CONFIRMED | Cloud re-download over unsynced edits, no upload retry, sync with no mass-delete guard |
| 24 | ARCH-03, ARCH-05 | Performance | High / Med | P1 | CONFIRMED | ReaderState leaks for the process lifetime (WeakHashMap values hold their key); `runBlocking` close on the main thread |
| 25 | RD-30, UX-14, UX-28/29 | Accessibility | High | P1/P2 | CONFIRMED | Page content invisible to TalkBack; settings switches have no toggle semantics; no headings or live regions; unlabeled icons |
| 26 | DI-06, DI-16, DI-22 | Data integrity | High | P1 | CONFIRMED | Delete falls back to permanent; documents deleted on uninstall; rename/move doesn't update recents/tags/bookmarks |
| 27 | ARCH-12, ARCH-24, ARCH-25 | Release / test | High | P1 | CONFIRMED | Features silently disabled by missing config; no instrumentation or fixture tests; CI doesn't cover PRs, lint or release |
| 28 | SEC-12, SEC-13 = ARCH-18, UX-24 | Play / permissions | Medium | P1 | PROBABLE / CONFIRMED | CAMERA is never requested (photo signature fails); unused READ_MEDIA_IMAGES (policy review); required mic/autofocus hides the app on some devices |
| 29 | ARCH-14, ARCH-15, ARCH-16, ARCH-17 = SEC-34 | Supply chain | Medium | P1/P2 | PROBABLE / CONFIRMED | BouncyCastle CVEs, stale PdfBox fork, unpinned JitPack, unverified model downloads |
| 30 | SEC-08, SEC-09 | Privacy | Medium | P1/P2 | CONFIRMED | A local AI endpoint is silently replaced by the cloud proxy; no consent before sending document text |

\*UX-02 is labelled P1 in its area file. It is raised here to P0 because it is the default button and destroys originals irreversibly.

**Classification requested by Phase 8:**
- **Confirmed defects:** items 1–7, 9–16, 18–24, 26–27 above. Each is CONFIRMED in its area file, and items 1, 5, 7 and 9 were re-verified by the lead.
- **Probable defects:** RD-04 (gesture handler), ARCH-02 (targetSdk policy date), SEC-05 (pdf.js eval), SEC-12 (camera), DOC-13 (batch killed), DI-17 (backup quota), RD-12 (cache pressure), DI-33 (DataStore corruption crash-loop), ARCH-11 (OAuth scheme clash between debug and release).
- **Architectural risks:**
  - No ViewModel or saved-state model (ARCH-26)
  - PdfBox spread across 117 files with no write service (ARCH-06)
  - 26 settings stores (ARCH-27)
  - Layered `*5`/`*6` packages (ARCH-22)
  - Cache-owned bitmaps returned to callers (ARCH-28)
- **Missing safeguards:**
  - Atomic write-back
  - Free-space checks (DI-42)
  - Resource limits on zips, Office files and form JS (SEC-17/28, DOC-28)
  - Integrity checks on downloaded models
  - Mass-delete guard
  - Backup rules
  - Crash reporting and mapping retention (ARCH-21)
  - A rate limit on the proxy
- **Future scalability concerns:**
  - Unbounded text and char-box caches (RD-13)
  - Recents table growth (DI-35)
  - Up to 300 MB of history per document (AN-27/DI-38)
  - A full-file copy on every autosave (RD-14/DI-44)
  - FTS index staleness and retry-forever (DI-40)
- **Optional hardening:**
  - Scope cleartext to OCSP/CRL hosts
  - Encrypt tokens with Keystore
  - SRI/CSP on the worker pages
  - Narrower Drive scope (SEC-35)
  - Strip AdMob when ads are off (SEC-36/ARCH-32)

---

## 5. Top 10 critical defects & reliability risks

| # | What breaks | Evidence | Why it matters | Fix (smallest) | Success measure |
|---|---|---|---|---|---|
| 1 | **Original file corrupted / edits lost on write-back** (DI-01, DI-02, AN-02) | `core/files/DocumentRepository.kt:58-70, 94-96` | Runs on every autosave at ON_PAUSE; every user is exposed | Working copy is the authority until a verified write-back; store size, mtime and hash plus a "written-back" marker; never re-import over unsynced edits; verify length; temp-and-rename where the provider allows | Kill-during-write test: original never 0-byte/partial *or* the working copy survives; reopen never loses annotations (JVM test of the decision function + device test) |
| 2 | **Unsaved annotations and undo dropped on navigation** (AN-01, AN-03, DI-10/11, RD-25) | `ui/reader/ReaderState.kt:243-254, 654-656` | Annotating is the main study workflow | `guardedNavigate{}` for all outgoing navigation; save in `close()` if dirty; ReaderState/DocHistory in a back-stack ViewModel; extend configChanges | Navigate to Settings and back: annotations and undo stack intact; keyboard attach keeps state |
| 3 | **"Save in original" overwrites sources irreversibly** (UX-02) | `ui/common/Components.kt:226` | Default button; a compress can wipe the original quality | Make "Save as new file" the default; keep a `.bak`/history step for overwrite; surface write-back failure | Result sheet default = copy; overwrite is undoable once |
| 4 | **Redaction leaves extractable data** (AN-07/09/10) | `core/redact/TrueRedactor.kt:62-68,173-176`; `ArabicShaper.kt:103-110` | Legal and privacy exposure; users trust "redacted" | Prune unreferenced XObjects; strip ActualText/Alt on touched spans; sanitize on by default; post-redaction verifier | Fixture test: no text, original image stream or ActualText recoverable from the marked area |
| 5 | **Signatures invalidated by ordinary saves and signing** (DOC-01/02, AN-28) | `core/pdf/Annotations.kt:73-87`; signing path in `core/sign` | Signed contracts silently become invalid | Detect `/Sig`; use `saveIncremental` for signed documents (annotations, forms, visible signature); otherwise warn and save a copy | Fixture: sign → annotate → verifier still valid |
| 6 | **Encryption weakened or removed** (DI-28, DOC-05/06, SEC-14/15) | `core/pdf/PdfOps.kt:42-50`; optimizer | Owner restrictions removed; "protected" files leave unprotected | Preserve the original security handler and key length; tools carry encryption forward or warn explicitly | Fixture: protected PDF → save/optimize → same permissions, key length and owner password |
| 7 | **Page reorder/split destroys structure** (AN-22, DOC-03) | `ui/pages/OrganizeState.kt:194-219`, `PdfOps.kt:93-101` | TOC and forms lost in textbooks and forms | Reorder in place on the page tree; `importPage` only for extract | Fixture: outline + AcroForm + labels + encryption survive reorder |
| 8 | **Release impossible** (ARCH-01/02/12) | `app/build.gradle.kts:52-65`, `targetSdk = 35` | No update can ship | Recover the key or use Play App Signing upload-key reset; target 36 after testing; fail the release build when the signing config or required properties are missing | `bundleRelease` fails loudly without a key; a signed AAB builds with a key |
| 9 | **DB wipe on first schema change + backup leaks** (DI-08, DI-14) | `core/data/AppDatabase.kt:154-168`; manifest `allowBackup` | Signatures, stamps, bookmarks, tags gone; tokens in backups | `exportSchema` + committed schemas + real migrations; backup and data-extraction rules that exclude secrets and caches | Migration test v1→v2 keeps rows; backup rules lint-clean |
| 10 | **Hostile shared content can exfiltrate files** (SEC-02/03) | review merge in `core/collab`; Launch handling + FileProvider paths | One tap shares private files including the settings that hold the AI key | Annotation allow-list on merge; block Launch/GoToR to absolute or `../` paths; restrict FileProvider to an export dir | Fixture PDF with a Launch link to `../shared_prefs/...` → blocked |

---

## 6. Top 20 small details with the greatest impact (each S effort, low regression risk)

1. **DI-04:** Rename checks whether the name exists, case-insensitively, and rejects `/` and `..`.
2. **DI-05/AN-12:** Failed annotation writes stay pending, with the message "N annotations could not be saved".
3. **DI-29/DI-30:** "Save a copy" / "Save as" throw on a null stream, verify the written length, and show the real error.
4. **RD-01:** A corrupt or missing file gets a full-screen error with Retry, Open with… and Back, instead of an endless spinner.
5. **RD-02:** Recents open with page = −1 so the DB's last page wins, which fixes resume after process death.
6. **RD-04:** Key `pointerInput` on mode/listState, or use `rememberUpdatedState`. This fixes pinch and pan after switching mode.
7. **RD-05:** Measure tap zones on the viewport, not the page. This stops spine taps flipping pages in facing mode.
8. **RD-03:** Run the connected-doc marker scan after the first render, or cache it, for a faster time-to-first-page.
9. **DI-06:** Never fall back to permanent delete silently; ask instead.
10. **DI-25:** Abort a sync pass that would delete more than 20 files or more than 30% of the snapshot.
11. **AN-15:** OR the reversed Arabic terms into the FTS query, so global search finds visual-order PDFs.
12. **AN-16 / AN-17:** Carry the query into the reader from global results, and scroll to the hit rectangle, not just the page.
13. **RD-21:** Thumbnails open at the current page, and the outline highlights the current chapter.
14. **RD-06:** Volume keys don't flip pages during read-aloud or calls, and pass through at the last page.
15. **UX-05/UX-06:** AutoMirrored chevrons and correct prev/next arrows in e-sign, form builder and redaction.
16. **UX-14:** Settings switch rows use `Modifier.toggleable(role = Switch)`, one TalkBack stop that announces state (147 rows).
17. **UX-20:** A view change in one document stops silently rewriting the global default (add a per-document override).
18. **LINT-03 / UX-24 / SEC-12 / SEC-13:** Small manifest fixes:
    - lower-case the macroEnabled mime types
    - add `uses-feature required=false` for the microphone and autofocus
    - request CAMERA at runtime, or drop it
    - remove READ_MEDIA_IMAGES
19. **ARCH-03 / ARCH-05:** Fix the WeakHashMap value→key leak, and move `PdfSession.close()` off the main thread.
20. **DOC-11:** Lower the OCR English threshold, or always include `eng` with `ara`, since 4.8% Latin in a chemistry book is still important text.

Honourable mentions:
- AN-08: undo buttons that silently undo newer steps.
- AN-21: per-tool colour and width memory.
- RD-32: keep-screen-on default for reading.
- UX-34: the greeting says "Good evening" all afternoon.
- LINT-02: stray BOM characters in Arabic, DOCX and XLSX writer sources.

---

## 7. Reading & annotation workflow improvements (personas)

| Persona | Friction found (IDs) | Highest-value changes |
|---|---|---|
| **Student, 600-page textbook** | Resume page wrong after the OS kills the app (RD-02); per-document zoom/mode not kept (RD-22); thumbnails start at page 1 (RD-21); reorder loses the TOC (AN-22); reflow caches unbounded (RD-13); no page labels, so the printed page number can't be typed (RD-20/AN-34) | RD-02, RD-22, RD-20 + AN-34 (go to the printed page), RD-21, AN-22 |
| **Arabic scanned-PDF reader** | Broken text layer treated as good, so no OCR and garbage search/copy (DOC-04); English dropped from OCR (DOC-11); layer misaligned on cropped pages (DOC-09); RTL page order follows the app, not the book (RD-28); voice detection misses presentation forms (RD-09); language setting doesn't work (UX-01) | DOC-04 *(pdf-ad)*, DOC-11, DOC-09, RD-28 (honour `/Direction R2L`), UX-01 |
| **Rapid highlighter / annotator** | Every drag draws, no stylus-only mode (AN-14); eraser can't erase saved ink (AN-13); colours reset each open (AN-21); drag-highlight over-selects across columns and does nothing on scans (AN-20); failed writes vanish (AN-12); navigation drops work (AN-01) | AN-01, AN-12, AN-13, AN-14, AN-21 |
| **Frequent page jumper** | Jumps land at the page top (RD-29); hit navigation per page only (AN-17); no search history (AN-36); back-to-position works (keep it) | RD-29/AN-17, AN-36 |
| **One-handed tablet reader** | Tap zones per page, so the spine flips (RD-05); touch targets of 16–40dp in the reader toolbars (UX-16); toolbar show/hide shifts content (RD-17); no real distraction-free mode (RD-18) | RD-05, UX-16, RD-17, configurable tap-zone width |
| **Very large PDF** | Whole-file marker scan before the first page (RD-03); full PdfBox parse on every open (RD-15); 2–3 full-file copies per autosave (RD-14/DI-44); 64 MB cache per session with no trim (RD-12); no PdfBox memory policy (ARCH-06) | RD-03, RD-15 (lazy PdfBox), RD-12 (onTrimMemory), incremental save (RD-14) |
| **Poor connectivity / low-end device** | No proactive offline state (UX-18); cloud uploads never retried (DI-24); batch jobs killed with no foreground service (DOC-13); night-mode CPU inversion spikes memory (RD-11) | DI-24 (WorkManager upload), DOC-13, RD-11 (colour-matrix tint) |
| **Closes the app unexpectedly and returns** | Pending edits in memory only (DI-09); annotation being edited removed from the file (DI-07); write-back failures never shown again (DI-12); undo gone (AN-03) | DI-09 draft journal, DI-07 recovery, DI-12 banner + badge |

**Essential vs. power-user:**
- **Essential:** everything in rows 1–3 and 8.
- **Power-user:** stylus-only mode, per-tool memory, command search, Ctrl+wheel, reading ruler.

---

## 8. Mobile / tablet / desktop compatibility gaps

- **Configuration changes:** a keyboard attach/detach, density or font-scale change, or desktop windowing recreates the activity. That loses the reader state, undo and pending edits (RD-25).
- **Keyboard / mouse:**
  - Reader shortcuts exist (arrows, PgUp/PgDn, Ctrl+F/G/S/Z/±/0).
  - There is no Ctrl+wheel zoom, and wheel scroll is disabled when zoomed (RD-24).
  - Outside the reader there are no shortcuts, right-click, hover or tooltips (UX-19).
- **Tablets and foldables:**
  - Breakpoints are ad hoc (500/600/700/720/840dp), and the WindowSizeClass dependency is unused (UX-25).
  - A two-page spread is manual except on book-posture foldables.
  - Home and Files have no two-pane layout.
- **Touch:** tap zones per page (RD-05); sub-48dp targets (UX-16); no palm rejection (AN-14).
- **Font scale:** fixed sizes clip at large scale (UX-26, PROBABLE).
- **RTL/LTR:** chevrons and arrows (UX-05/06); four different "is the UI Arabic" checks (UX-07); the layout-direction override doesn't reach non-Compose surfaces (UX-39).
- **Play device filtering:** the microphone and autofocus are implicitly required (UX-24).
- **API 26–29:** documents copied to external app storage are readable by other apps on older APIs (SEC-11). HEIC can't be decoded on API 26–27 (DOC-39).
- **Two installs:** debug and release share the `smartdocs://oauth` scheme (ARCH-11).

## 9. Redundant / confusing / unnecessary UI

- **Compress ×3 engines** (UX-08): only the reader's Quick Compress takes a password, can be cancelled, and refuses to produce a larger file. Unify on that engine.
- **Other duplicated entry points:** OCR has 5, and watermark, protect and compare have 2–4 each. Route them all to one implementation.
- **Help:** three help screens; Settings shows "Help centre" and "Help" side by side (UX-12).
- **AI configuration** appears on two screens, and unsaved edits are lost (UX-22).
- **Misleading or dead controls:**
  - "Rotate" and "Extract pages" cards open the generic organizer with nothing preselected (UX-09).
  - Feature-flagged rows look enabled but do nothing (UX-27).
  - The analytics toggle does nothing (SEC-39).
  - `onboardingDone` is written but never read, and TTS pitch is read but never settable (UX-21).
- **Dead code and routes** to remove: `compare_basic`, `compare_plus`, `outline` and `help` tool ids; WifiTransferScreen; legacy AiSheet/DocAgentSheet; ReadAloudVoiceSheet; DocxWriter2; XlsxWriter; AnnotReplies; DriveFolderPicker; the "storage" route alias; and `convert_classic` (in-flux). See UX-10, UX-37 and ARCH-23.
- **"Discard changes"** doesn't discard content edits, quick tools or form flushes (AN-19), which misleads users.

---

## 10. Data-loss & recovery assessment

| Failure | Current behaviour | Required recovery behaviour | IDs |
|---|---|---|---|
| Crash or kill during write-back | Original truncated or partial; next open may import the partial file over the good copy | Working copy stays authoritative; write-back verified; on mismatch, retry or offer "save copy" | DI-01/02, AN-02 |
| Read-only source (Telegram, e-mail) | Write-back fails, then a later reopen overwrites the edited copy | Keep the edited copy; show a persistent "not saved to original" badge with Save copy / Pick original | DI-02, DI-12, DI-19 |
| Process death with pending edits | Lost | Draft journal restored on open | DI-09 |
| Editing a stored annotation, then a crash | Annotation gone from the file | Restore it from `.annotedit` backup on open | DI-07 |
| Navigation away (autosave off) | Edits silently discarded | Same rule as Back: save or ask | AN-01, DI-10 |
| Low storage | Truncate-then-fail; trash move fails, so permanent delete; no free-space check on import or unzip | Pre-check space; never fall back to destructive actions | DI-01, DI-06, DI-42 |
| File renamed, moved or deleted externally | Stale origin maps; recents, tags and bookmarks orphaned | Identity record (Room) with URI, size, mtime and hash; reconcile on open | DI-03, DI-20/21/22 |
| SAF permission revoked or grant limit hit | Grants never released (128/512 cap) | Release on recent removal; handle SecurityException with re-pick | DI-18 |
| Schema change | All DB data wiped | Real migrations plus a schema test | DI-08 |
| DataStore corruption | Probable crash loop at launch | `ReplaceFileCorruptionHandler` | DI-33 |
| Uninstall or "Clear storage" | All in-app documents deleted | Default save location in shared storage, or an export prompt 🧑 (user decision) | DI-16 |
| Cloud upload fails | Not retried; "clear cache" deletes unsynced edits; reopen downloads over the edits | Persisted dirty flag + WorkManager retry; conflict copy | DI-23/24 |
| Sync mass deletion | Propagated, and local deletes are permanent | Threshold guard + trash | DI-25 |
| Saving a signed or protected PDF | Signatures invalid; security weakened | Incremental save; keep the encryption handler | DI-27/28 |
| Batch or long operation killed | Partial output, temp files deleted by a concurrent job | Foreground work, per-job temp dirs, resumable status | DOC-13/14 |
| Undo snapshots | Up to 300 MB per document in cacheDir, evictable by the OS, deleted on navigation | Bounded, app-scoped, and survive navigation | AN-27, DI-38, AN-03 |

**Rule for all fixes:** never assume a save succeeded. Every write path returns a verified result, and the UI must show failures persistently, not only in a snackbar.

## 11. Security & privacy assessment (defensive)

- **Secrets:**
  - None of the key files are present or ever committed (git history checked).
  - However, the design compiles the AI app token, the Pro HMAC secret, the Box client secret and the worker tokens into BuildConfig, so they are extractable from any APK built with them (ARCH-13, SEC-01/10/38).
  - Anything secret must live server-side.
- **Local storage:**
  - OAuth refresh tokens, the AI key, purchase tokens and batch passwords are stored in plain preferences (DI-15, SEC-30, DOC-15).
  - Backup has no rules (DI-14/SEC-07).
  - Decrypted temp copies are never swept (DI-32).
- **Untrusted documents:**
  - The Rhino sandbox is good, but has no memory cap (SEC-17).
  - Launch/GoToR links can share arbitrary files (SEC-03), and any URI scheme is opened without confirmation (SEC-25).
  - The WebView HTML/EML/EPUB→PDF conversion runs scripts and loads remote resources (SEC-26).
  - Zip bombs: unzip and Office packages have no size cap (SEC-28, DOC-28).
  - XXE hardening is unverified on Android DOM parsers (SEC-33, HYPOTHESIS).
  - OK: zip-slip is blocked, and OOXML expands no entities.
- **Network:** cleartext is allowed for the whole app (SEC-20). Wi-Fi transfer is plain HTTP with a PIN that is guessable over time (SEC-21).
- **Backend (when configured):**
  - Proxy cost abuse (SEC-01).
  - Review identity is a client claim, and the review merge has no allow-list (SEC-02/06).
  - E-sign forgery by the sender (SEC-04).
  - pdf.js 3.11.174 with eval, and no CSP or SRI (SEC-05).
  - E-mail relay (SEC-18).
  - Workers are open when APP_TOKEN is unset (SEC-23).
  - The licence server trusts the client-supplied productId (SEC-29).
- **Privacy:**
  - No consent screen before document text goes to an AI provider (SEC-09).
  - A local AI endpoint is silently overridden by the cloud proxy (SEC-08).
  - Unused permissions: READ_MEDIA_IMAGES, and CAMERA is never requested.
- **Good:**
  - PKCE S256 with state; Keystore-encrypted connected-doc keys.
  - UMP consent before ads.
  - Passwords kept in memory only; no logging.
  - Wi-Fi server hardened cookie and path checks.
  - The signing cryptography itself.

## 12. Performance & scalability assessment

**Measured:** nothing on a device; only unit tests ran. All of the statements below are theoretical, from code, and must be measured on a device before any optimisation larger than an S change.

- **Time to first page:** a whole-file marker scan of up to 64 MB, with 1 MB→String chunks, runs before PDFium opens the file (RD-03). A full PdfBox parse also runs on every open and competes with the first render (RD-15).
- **Saves:** every autosave copies the file 2–3 times and does a full rewrite, followed by a full write-back, cloud upload and sync (RD-14, DI-44). That is O(file size) I/O on every ON_PAUSE.
- **Memory:**
  - A 64 MB bitmap cache per session with no `onTrimMemory` (RD-12).
  - Unbounded text and char-box caches (RD-13).
  - CPU night-mode inversion triples memory per render (RD-11).
  - ReaderState leaks for the process lifetime (ARCH-03).
  - `largeHeap` hides the pressure.
- **Main thread:** `runBlocking` close (ARCH-05), plus main-thread file I/O in reader-adjacent paths (RD-35).
- **Background:**
  - The PDFium global lock gives background work equal priority with on-screen renders (RD-36).
  - The index worker retries forever (DI-40).
  - The mixed-page OCR plan OCRs nearly every page that has any image, at 300 dpi (DOC-12).
- **Growth:**
  - The recents table is unbounded (DI-35).
  - History of up to 300 MB per document (AN-27).
  - Office and zip files are fully in memory (DOC-28).
  - The proxy has no rate limit (SEC-01).
- **Proposed instrumentation, the cheapest first step:**
  - Trace sections or timing logs for open, first render and save.
  - A low-memory device test with a 1,000-page PDF.

## 13. Compatibility & upgrade risks

- **Play target API:** `targetSdk 35` (ARCH-02). Target 36 needs predictive-back, edge-to-edge and foreground-service-type checks.
- **Stale or vulnerable libraries:**
  - pdfbox-android 2.0.27 is a stale fork without upstream 2.0.x hardening (ARCH-15). Migration is costly; isolate it behind a write service first (ARCH-06/35).
  - BouncyCastle 1.72 has known CVEs on the untrusted-signature path (ARCH-14). Upgrade to 1.78+ with signature fixture tests.
  - play-services-auth 22 GoogleSignIn APIs are deprecated. Credential Manager plus the Authorization API is the migration path (ARCH-30).
  - androidx libraries are 1–3 minors behind (lint list in 08). Upgrade in one batch with full tests and a release build, not piecemeal.
- **Native libraries:** 16 KB pages are OK for PDFium and Tesseract; ML Kit and Play natives are unverified.
- **Reproducible builds:** there is no lockfile, dependency verification or wrapper checksum, and JitPack is global (ARCH-16).
- **Vendor coupling:** PDFium is wrapped well (PdfSession), but PdfBox is not (117 files).
- **OCR models:** they come from a mutable branch (ARCH-17). Pin to a commit plus a SHA-256.

---

## 14. Release-readiness recommendation

**Verdict: NOT READY for a Play release.** A test APK for internal device testing is fine.

**Blockers:**
1. The release signing key is lost (ARCH-01). Recover it, or request an upload-key reset through Play App Signing. Make `bundleRelease` fail without a signing config.
2. Target API (ARCH-02): verify Play Console's requirement and move to 36 with testing.
3. The P0 data-loss set: DI-01/02/03/04, AN-01, UX-02 (and DI-09 strongly recommended).
4. The P0 redaction leftovers: AN-07/09/10.
5. Signed and protected PDF integrity: DOC-01, AN-28/DI-27, DI-28. Otherwise disable annotation saves on signed documents and warn.
6. Room schema export and the migration path, before any schema change (DI-08).
7. Backup and data-extraction rules (DI-14).
8. Permissions cleanup for Play policy (ARCH-18/SEC-13, SEC-12, UX-24).
9. At least one release-build smoke test, because R8 has never been exercised (ARCH-20), plus mapping retention (ARCH-21).

**Before enabling any server feature:** SEC-01, 02/03, 04, 05, 06, 10, 23, and the consent screen (SEC-09).

**Uncertainties:**
- No device runs, so gesture, lifecycle, TalkBack, memory and OEM provider "w"/"wt" behaviour are untested.
- The Play policy date.
- Backup quota behaviour.
- The bundled PDFium version.
- PdfBox-Android XML hardening.

---

## 15. Remediation roadmap (safe, testable phases)

Principles:
- Small, reversible changes, one finding or root cause per commit, each with a regression test.
- No large rewrites, and never modify user documents.
- Each phase ends with all tests green (≥384 + new), a debug APK, a device-test checklist, and an entry in `AUDIT/FIX_LOG_*.md`.

### Phase A — Immediate (critical defects, data loss, security, release blockers)
| Step | Findings | Owner / status |
|---|---|---|
| A1 Safe save & open: working copy is the authority, verified write-back, identity record, rename/delete safety, failure surfacing | DI-01/02/03/04/05/06/12/13/20/29/30, AN-02/12, UX-02 | **In progress: session pdf-39** (Stage 1 of `AUDIT/FIX_PLAN_areas1-3.md`). pdf-ad reports DI-01 is already changed on main at 7792688 (not yet verified by the audit; pdf-39 is checking it against DI-01/DI-02) |
| A2 Reader lifecycle: guardedNavigate, save on close, ViewModel-scoped ReaderState/DocHistory, configChanges, draft journal, annotedit recovery, WeakHashMap leak | AN-01/03, DI-07/09/10/11, RD-25, RD-02, ARCH-03/05 | pdf-39 Stages 2–3; **waiting for pdf-ad to release the `ui/reader` files** (page-flip integration) |
| A3 Redaction completeness + verifier | AN-07/09/10, SEC-16, ARCH-08 | pdf-39 Stage 4 |
| A4 Signed and protected integrity: incremental saves for signed docs (annotations, forms, visible signature); preserve encryption; tools carry encryption forward or warn | DOC-01/02/05/06, AN-28/29, DI-27/28, SEC-14/15 | pdf-39 Stage 6 covers the annotation-save path; the **DOC tools part is not yet assigned** |
| A5 DB + backup: exportSchema, migrations, backup and data-extraction rules, token encryption | DI-08/14/15, ARCH-04/19, DOC-15/16 | pdf-39 Stage 1 (DI-08/14); token encryption is not yet assigned |
| A6 Release blockers: signing, target 36, fail-fast missing config, release smoke build in CI, mapping retention, permission cleanup | ARCH-01/02/12/20/21/25, ARCH-18, SEC-12/13, UX-24, LINT-03 | **Not assigned. Needs the user** (signing key / Play Console) 🧑 |
| A7 Untrusted-document hardening: Launch/GoToR block, FileProvider scope, review-merge allow-list, URI scheme confirm, JS memory cap, zip/Office size caps | SEC-02/03/17/25/28, DOC-28/32 | Not assigned |

### Phase B — Near term (reliability, correctness, coverage, high-impact UX)
- **B1 Reader correctness:** RD-01, 03, 04, 05, 06, 07/08, 16, 19, 21; AN-06 (rotation/CropBox in PageGeometry + DOC-09/30/31); AN-13, AN-04/05/08, AN-22/DOC-03 (in-place reorder). Assigned to pdf-39 Stage 5 except the DOC items.
- **B2 Arabic correctness:**
  - DOC-04 (broken-layer detection) is pdf-ad's, in-flux.
  - The rest are unassigned: AN-15, AN-23/24, DOC-11, DOC-18/19/21, RD-09, RD-28.
- **B3 i18n:** UX-01 (apply locale via `AppCompatDelegate.setApplicationLocales` + `localeConfig`), UX-03/LINT-01 (307 English strings), UX-05/06/07/11/13 (plurals for Arabic: zero/one/two/few/many/other), UX-23.
- **B4 Accessibility:** RD-30, UX-14, UX-16, UX-28, UX-29, UX-26.
- **B5 Long operations:** UX-04 (cancellable progress), DOC-13 (foreground WorkManager), DOC-14 (per-job temp), DOC-22/24.
- **B6 Cloud and sync safety:** DI-23/24/25/26 (pdf-39 Stage 7), DI-18/19.
- **B7 Errors and observability:**
  - replace silent `runCatching` on write paths with typed results
  - user-facing localized errors (UX-17)
  - a minimal local crash log and mapping retention (ARCH-07/21)
- **B8 Tests:**
  - PDF fixture round-trip tests on the JVM (PdfBox already works there; ARCH-24): annotate, save, reload; redact, extract; reorder keeps the outline; protected save keeps permissions; signed annotate keeps validity.
  - Room migration tests.
  - Arabic fixtures from the chemistry PDFs (small extracts only, with the user's OK 🧑).
  - A first instrumented smoke test (open, render, annotate, save) for when a device is available.
- **B9 Supply chain:** BouncyCastle upgrade (ARCH-14), pinned and verified OCR models (ARCH-17), dependency verification and lockfile (ARCH-16), scoped cleartext (SEC-20/LINT-04).
- **B10 Privacy:** AI consent and provider disclosure (SEC-09), respect the local endpoint (SEC-08).

### Phase C — Long term (maintainability, scalability, future capabilities)
- **C1 Document write service:**
  - one module owns `PDDocument` load and save: memory policy, password, incremental-vs-full, encryption carry-forward, verify.
  - Migrate the 117 PdfBox call sites gradually (ARCH-06/35).
- **C2 Incremental annotation saves and delta history** instead of full-file copies (RD-14, AN-27, DI-38, DI-44).
- **C3 Per-document reading state:** mode, fit, zoom, offset, direction (RD-22, UX-20, RD-38).
- **C4 Consolidate duplicated tools:** one compress, one OCR entry, one help (UX-08/12); remove dead code (ARCH-23, UX-10/37).
- **C5 Settings consolidation:** one typed settings store with a corruption handler (ARCH-27, DI-33).
- **C6 Document storage location** outside app-specific storage (DI-16) 🧑 product decision.
- **C7 Power-user features (P3):**
  - stylus-only mode and palm rejection (AN-14)
  - per-tool memory (AN-21)
  - page labels in go-to (RD-20/AN-34)
  - search history (AN-36)
  - Ctrl+wheel and desktop input (RD-24, UX-19)
  - MediaSession for read-aloud (RD-10)
  - real distraction-free mode (RD-18)
  - WindowSizeClass layouts (UX-25)
- **C8 Backend hardening** before launching the server features (SEC-01/04/05/06/10/18/19/23/29/31).
- **C9 PdfBox-Android fork risk:** evaluate upstream PDFBox 3.x on Android behind the write service once C1 exists.

---

## 16. Acceptance criteria & regression tests for high-priority changes

| Change | Acceptance criteria (measurable) | Regression tests | Regressions to watch |
|---|---|---|---|
| Safe write-back (DI-01/02/03) | Simulated failure at any byte offset leaves either the original intact or the working copy intact and flagged; reopening never replaces a working copy that has unwritten edits; a changed source is imported as a new copy or a conflict prompt | JVM: pure "import decision" function table (sizes, mtime, hash, write-back marker); fake ContentResolver throwing mid-stream | Providers without SIZE or mtime; MediaStore RecoverableSecurityException flow |
| Reader lifecycle (AN-01/03, DI-09/10) | Navigate reader → Settings → back: pending annotations and the undo stack are identical; process death with pending annotations restores the draft; keyboard attach keeps state | JVM: draft journal round-trip; guardedNavigate rule table; device: back-stack scenarios | Double saves; ViewModel holding a closed session; memory of the retained history |
| Redaction (AN-07/09/10) | After redaction, no text is extractable in marked rectangles; no `/XObject` entry unreferenced by content; no ActualText/Alt containing removed text; batch summary lists skipped files | JVM fixture: text + image + form XObject + ActualText (Arabic) → redact → parse/extract asserts | Shared XObjects used on other pages must stay; file size |
| Signed docs (DOC-01/02, AN-28) | Sign → annotate / form-fill / visible signature → existing signatures still verify | JVM fixture with a self-signed test cert (no real keys) | Incremental save + encryption combos; DocMDP level 1 must refuse |
| Encryption preserve (DI-28, DOC-05/06) | Save/optimize/PDF-A keep the key length, permissions and owner password, or show an explicit warning before removing them | JVM: protected fixture → each tool → check the encryption dictionary | Tools that legitimately need decrypting (PDF/A forbids encryption: warn) |
| Page reorder in place (AN-22/DOC-03) | Outline destinations, AcroForm fields, page labels and encryption survive reorder/move/paste | JVM fixture | Named destinations pointing at moved pages |
| Room migrations (DI-08) | v1→v2 keeps all rows; the schema JSON is committed | Room MigrationTestHelper (instrumented) or a JVM schema-diff check | Downgrade path |
| Backup rules (DI-14) | Tokens, keys, licence id, caches and history are excluded; size under 25 MB | Lint + inspection of `bmgr` on a device | Users expecting settings restore |
| Corrupt-file state (RD-01) | A corrupt, non-PDF or missing file shows a persistent localized error with Retry/Back within 2 s | Compose UI test or ReaderState unit test with a failing session | — |
| Resume page (RD-02) | After process death, reopening resumes at the last page read | ReaderState unit test with route page vs DB page | Deep links that deliberately target a page |
| Language (UX-01/03) | Selecting English or Arabic changes all text and direction; there are no Arabic strings in an English UI (lint MissingTranslation = 0) | Lint gate in CI; screenshot test per locale when a device is available | Locale-dependent formatting in Gradle (already forced) |
| Long ops cancel (UX-04, DOC-13) | Every long tool shows progress plus Cancel; Back cancels; batch survives backgrounding for more than 10 min | JVM: cancellation propagates through ToolRunner; device: background test | Partial outputs must be deleted on cancel |
| A11y (RD-30, UX-14) | TalkBack announces "Page N of M", page changes, and switch state in one stop | Compose semantics tests | — |
| Release (ARCH-01/02/20) | `bundleRelease` fails without signing; a signed AAB builds; release APK smoke: open, annotate, save, OCR without R8 crashes | CI release job | Keep rules for reflection users |

**Success metrics after the fixes:**
- Zero known P0 issues.
- Every P0/P1 fix has a regression test.
- Lint has no Errors, with MissingTranslation = 0 and the manifest clean.
- Unit tests ≥ 384 plus the new ones, all green.
- A device checklist from the "Needs device testing" sections, executed and recorded.
- Median time-to-first-page and save duration measured on a reference device, before and after A/C2.

---

## 17. Records & coordination

- **Audit artefacts:**
  - `AUDIT/_AGENT_BRIEF.md` (method)
  - `AUDIT/01…08_*.md` (evidence)
  - `AUDIT/build_test_lint.log`
  - The lint report at `D:\pdf\audit-wt\app\build\reports\lint-results-debug.html`
- **Fix work:**
  - `AUDIT/FIX_PLAN_areas1-3.md` is the plan handed to session **pdf-39**, which works in `D:\pdf\fix-wt` on branch `fix/audit-1-3`.
  - The fix log is `AUDIT/FIX_LOG_areas1-3.md`, maintained by pdf-39.
- **Coordination:**
  - Session **pdf-ad** owns the in-flux areas (convert6, pageflip, ocr, ArabicTextFix, Tools/Home screens).
  - pdf-39 holds reader edits until pdf-ad gives the all-clear.
- **Unassigned (needs the user's go-ahead):**
  - Areas 4–7 fixes: security (A7, C8), DOC tool integrity (A4 remainder), UX/i18n (B3/B4), release (A6).
  - The 🧑 decisions: signing key recovery and Play Console, targetSdk, DI-16 storage location, using test-PDF extracts as fixtures.
