# 01 — Reader experience & rendering pipeline (prefix RD)

## Scope & method

Read from the stable snapshot `D:\pdf\audit-wt` (da74d31), paths cited repo-relative under
`app/src/main/java/com/smartdocs/editor/` (abbreviated below as `…/`).

Read in full or traced:
- `ui/reader/ReaderScreen.kt`, `ReaderState.kt`, `PageView.kt`, `PageCanvas.kt` (render + gesture parts), `PageGestures.kt`,
  `ReaderDialogs.kt`, `ReaderPanels.kt`, `ReaderOverlays.kt` (indicator, TTS bar)
- `ui/reader/view/ViewExtras.kt`, `ScreenControls.kt`, `ReaderTabs.kt`, `ViewSheet.kt`, `PageViewParts.kt`, `PageTint.kt`, `CropController.kt` (detect/ensure)
- `core/pdf/PdfSession.kt` (whole), `core/pdf/Annotations.kt` (`list`, `commit`), `core/pdf/PdfOps.kt` (flatten/redact cache usage)
- `core/tts/ReadAloudController.kt`, `ReadAloudService.kt`, `ReadAloudFocus.kt`
- `MainActivity.kt`, `AndroidManifest.xml` (activity + service), `core/data/SettingsStore.kt` (defaults), `core/files/DocumentRepository.kt` (`localFile`, `writeBack`)
- `ui/gaps6/ReaderHooks.kt` (fold / fullscreen / G6Keys), `core/gaps6/G6Prefs.kt`, `ui/view5/Keys5.kt`, `ui/view5/V5State.kt`, `ui/view5/ReflowReader.kt`, `ui/view5/SplitCompare.kt` (session lifecycle), `ui/edit5/ReaderExtras.kt` (page labels)
- `core/connected/ConnMarker.kt`, `core/connected/EnterpriseProtection.kt` (open path), `core/history/DocHistory.kt` (`open`, `copyIn`, `dispose`)
- PDFium binding: unpacked `io.legere:pdfiumandroid:2.0.1` classes from the local Gradle cache (copied to scratch) to check thread-safety (`*$$inlined$wrapLock$*` wrappers + `PdfiumCore` Mutex).
- Page-flip (in-flux, light): `core/pageflip/ScriptDirection.kt`, `BoundedLru.kt`, `ui/pageflip/PageSnapshotCache.kt`, `PageFlipController.kt` (head), `PageFlipHost.kt` (grep) in `D:\pdf\SmartDocs-project-v0.4.0`.

Not verified / limits:
- No device/emulator: all frame-rate, memory and gesture-feel statements are code-derived; marked "needs device test" where it matters.
- Compose `pointerInput` stale-capture findings (RD-04, RD-19) rely on the documented Compose contract that the block only restarts when its keys change.
- `core/view5` writers, workbench, presentation, print were only skimmed (out of reading-path scope).
- Did not run gradle or process the test PDFs (listing the Telegram folder timed out).

## Capability inventory

| Feature | Status | Evidence |
|---|---|---|
| Continuous vertical scroll | works | `ui/reader/PageView.kt:313-337` LazyColumn, keyed items |
| Single page pager / facing / cover-facing | works | `PageView.kt:338-362`, `ui/reader/view/ViewExtras.kt:126-157` PageSpread |
| Fit width / fit page | works (no fit-height, no custom %) | `PageView.kt:91-95`, `ViewSheet.kt:94-99` |
| Pinch zoom with re-render at zoom | works | `PageCanvas.kt:81-95` (re-render at quantised width ≤3200 px) + high-zoom tiles `PageCanvas.kt:96-108` |
| Zoom range | partial | `PageView.kt:42-43` MIN 1× (= fit), MAX 6×; cannot zoom out below fit |
| Double-tap zoom | partial | `PageView.kt:183-186` zooms around screen centre, ignores tap point |
| Remember last page per doc | works, but broken after process death when route carries a page | `ReaderState.kt:190-192, 230-234`; see RD-02 |
| Remember zoom / mode / scroll offset per doc | partial | zoom only via 5 tabs (`ScreenControls.kt:270-277`); mode global continuous/single only (`ReaderScreen.kt:318`) |
| Restore last session on launch | works | `ReaderTabs.kt` `RestoreLastSession` + `restoreTarget` |
| Full-screen / distraction-free | partial | centre tap hides bars (`ReaderScreen.kt:211, 259, 285`); status bar only via setting (`ReaderHooks.kt:60-68`); nav bar never hidden |
| Rotation without recreate | works for orientation/screenSize/uiMode | manifest `configChanges` line 37; recreate still happens for keyboard/density/fontScale (RD-25) |
| Orientation lock | works | `ScreenControls.kt:252-258` |
| Tap edges to flip | works but zones are per-page, not per-screen | `PageView.kt:213-224` (RD-05) |
| Volume keys flip / zoom | works, over-aggressive | `ViewExtras.kt:90-98`, `ReaderHooks.kt:83-92` (RD-06) |
| Keyboard: arrows, PgUp/PgDn, Home/End, Ctrl+F/G/S/Z/+/-/0 | works | `ViewExtras.kt:90-98`, `ui/view5/Keys5.kt:33-95` |
| Mouse: Ctrl+wheel zoom | missing | no `PointerEventType.Scroll` handling anywhere in `ui/` |
| Loading indicator | partial | spinner only (`ReaderScreen.kt:283`); none during content:// import (RD-27) |
| Corrupted file error state | broken | spinner forever after snackbar (RD-01) |
| Password open flow | works | `ReaderState.kt:203-204`, `ReaderDialogs.kt:32-50` |
| PDFium thread safety | works | per-session `Mutex` (`PdfSession.kt:42`) + library-global `wrapLock` around every native call (pdfiumandroid 2.0.1 classes `PdfPage$renderPageBitmap$$inlined$wrapLock$1`, `PdfiumCore$newDocument$$inlined$wrapLock$1`) |
| Close / no use-after-free | works | `PdfSession.kt:256-277` close waits for the mutex; `ReaderState.kt:243-254` closes off the UI scope |
| Render cancellation on fast scroll | works | per-item `LaunchedEffect` cancelled on dispose; `Mutex.withLock` is cancellable while waiting |
| Bitmap cache | partial | 64 MB LruCache per session (`PdfSession.kt:55-57`), no trim-memory hook (RD-12) |
| Native page cache | works | LRU of 12 open `PdfPage`s (`PdfSession.kt:48-54, 280`) |
| Night mode | partial (bitmap inversion on CPU, photos inverted) | `PdfSession.kt:118, 138-150` (RD-11) |
| Sepia / page colour presets | works (GPU blend) | `ui/reader/view/PageTint.kt:41-60` |
| RTL page order | partial — follows UI language, not the document | `PageView.kt:116-125`, pager follows LayoutDirection (RD-28) |
| Page labels | stub (implemented, not wired) | `ui/edit5/ReaderExtras.kt:15-34`, no callers (RD-20) |
| Jump to page | works (indices only) | `ReaderDialogs.kt:67-80` |
| Thumbnails | partial | `ReaderPanels.kt:200-224` does not open at current page (RD-21) |
| Outline / TOC | works | `ReaderPanels.kt:79-106` |
| Bookmarks in reader | works (panel only) | `ReaderPanels.kt:115-143` |
| Back to previous position after jump | works | `ui/view5/V5State.kt:74-90`, Alt+←/→ |
| Fast scroller | partial (thumb grab hit-test stale) | `ui/reader/view/PageViewParts.kt:74-131` (RD-19) |
| Reflow | works, heavy on long docs | `ui/view5/ReflowReader.kt:80-114` (RD-13) |
| Read aloud | works with gaps | foreground service `mediaPlayback`, audio focus + noisy receiver (`ReadAloudFocus.kt`), Arabic detection (RD-07..RD-10) |
| Two-page spread on tablets | manual only; automatic on book-posture foldables | `ReaderHooks.kt:46-55` |
| Accessibility of page content | missing | no semantics on page canvas (RD-31) |
| Page flip transitions | in-flux | `ui/pageflip/*` (RD-41) |

## Findings

### RD-01 Corrupted / unreadable file ends in an endless spinner
- Type: bug
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence: `ui/reader/ReaderState.kt:205-206` sets `error = e.message`; `ui/reader/ReaderScreen.kt:104` `LaunchedEffect(state.error) { state.error?.let { snack.showSnackbar(it); state.error = null } }`; the body branch `ReaderScreen.kt:282` shows the error text only while `state.session == null && state.error != null`, then `:283` falls back to `CircularProgressIndicator()`. After the snackbar times out, the error is null and the spinner stays forever. The snackbar text is the raw exception message (e.g. PDFium "File not in PDF format or corrupted").
- User impact / scenario: user opens a truncated Telegram download. They see an English technical message for 4 s, then a spinner that never ends, with no retry and no explanation.
- Proposed fix: keep a separate `openError` state that the snackbar does not clear. Show a full-screen error with a localized message (corrupt / not a PDF / permission lost / file missing), plus "Retry", "Open with…" and "Back" buttons. Map exception types to string resources.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Robolectric/Compose test that opens a 10-byte file and asserts the error view is still shown after 6 s; device test with a truncated PDF.

### RD-02 OS kill + resume reopens at the page the reader was entered with, not where the user stopped
- Type: bug
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence: the route carries the page: `ui/Navigation.kt:51-54` `reader/{uri}?page={page}`. Recents navigate with an explicit page (`Routes.reader(doc.uri, doc.lastPage)` / `(d.uri, d.lastPage)` found by grep). `ReaderState.kt:191` `initialPage = (if (startPage >= 0) startPage else recent?.lastPage ?: 0)`. The route argument always wins over the DB page. After process death, NavHost restores the back stack with the original `page` argument.
- User impact / scenario: student opens the 600-page textbook from Recents at page 120, reads to page 180, switches to the camera, and Android kills the app. On return they are back at page 120. The same happens for search-result and AI-citation opens (fixed page) and `InPageXfaBanner` (`page 0`).
- Proposed fix: treat the route page as a one-shot request. Store a `consumedStartPage` flag in `SavedStateHandle`/`rememberSaveable`, or prefer `recent.lastPage` whenever `recent.updatedAt` is newer than the navigation. Simplest: Recents should navigate with `-1` and let the DB decide.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: instrumented test that navigates `reader(uri, 5)`, calls `rememberPage(40)`, recreates the activity with "Don't keep activities", and asserts `currentPage == 40`.

### RD-03 Time-to-first-page: whole-file scan before PDFium opens the document
- Type: performance
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence: `ReaderState.kt:177` awaits `ConnectedDocs.markOf(f)` before `PdfSession.open` (`:186`). `core/connected/ConnMarker.kt:49-67`: if the 256 KB tail has no marker and the file is ≤64 MB, it reads the whole file in 1 MB chunks. Each chunk becomes a `String` (`String(buf,0,n,ISO_8859_1)`) plus a 200-char carry and is regex-searched. `EnterpriseProtection.detect(f)` (`:175`) reads head and tail windows first. Then `pageSizes(near)` opens up to 31 pages serially (`:194-195`), still before `session = s`.
- User impact / scenario: every open of a 40–60 MB scanned textbook reads 40–60 MB and allocates about 2× that in transient Strings before anything renders. That is roughly 0.5–2 s extra on mid-range phones (needs device test), on every open, including from Recents.
- Proposed fix: run the marker scan in parallel or after first render, since only "protected" marks need to block. Limit the full scan to files whose trailer contains the marker's object key, or cache the result per (path, size, mtime). Shrink the pre-render size window to ±3 pages.
- Effort: S     Regression risk: Low     Dependencies: share6 owner (ConnectedDocs)
- Validation: microbenchmark of `ConnMarker.find` on a 50 MB file; device trace (Perfetto) of open → first `renderPageBitmap`.

### RD-04 Gesture handler keeps the first view mode and list state (pinch/pan broken after switching mode)
- Type: bug
- Status: PROBABLE
- Severity: High      Priority: P1
- Evidence: `ui/reader/view/PageViewParts.kt:40` `pointerInput(Unit) { … canTake(…) … onPinch(…) … onPan(…) }`. The block is never restarted, so it keeps the lambdas from the first composition of `PageView`. Those lambdas capture `val mode = state.viewMode` (`PageView.kt:71`), the `listState`/`pagerState` created under `key(mode)` (`:76-77`), and the local funs `applyZoom`/`clampPan` that branch on `mode` (`:146-181`). After switching CONTINUOUS→SINGLE (View sheet, `ViewSheet.kt:57`, or fold auto-switch `ReaderHooks.kt:52`), `onPan` calls `listState.dispatchRawDelta` on the disposed list and the stale `clampPan()` forces `panY = 0f` (`PageView.kt:149`). `canTake` also uses the wrong rule (`zoom > 1.01` instead of the overflow tests).
- User impact / scenario: user switches to single-page mode and zooms into a page. Vertical panning does nothing and pinch anchoring jumps. The reverse switch (start in SINGLE, switch to CONTINUOUS) breaks zoomed panning in the list. This persists until the reader is reopened.
- Proposed fix: key the modifier, `pointerInput(mode, listState, pagerState)`, or read every callback through `rememberUpdatedState` inside `readerGestures`. Apply the same to `PageCanvas.kt:131` `pointerInput(page, g)`, which captures `onTap`/`settings`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Compose UI test that switches mode, injects a pinch plus a vertical drag, and asserts `panY` changes; needs device test.

### RD-05 Edge tap zones are measured on the page, not the screen
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P1
- Evidence: `PageCanvas.kt:379` `onTap(p, layout)` passes the tap position relative to the page canvas and the page's layout size. `PageView.kt:215` `val frac = p.x / size.width` uses 18 %/82 % thresholds on that page.
- User impact / scenario: (a) in Facing/Cover mode, the inner 18 % of each page sits at the spine in the middle of the screen, so tapping the centre to show the toolbar flips pages. (b) When zoomed in, the page is wider than the screen, so the visible screen edges are not the zones. Tapping near a page edge that has been panned to the screen centre flips unexpectedly. (c) In fit-page mode the margins outside the page get no taps at all.
- Proposed fix: compute zones from viewport coordinates. Pass `positionInRoot` or convert in `PageView` and use `viewport.width`. Disable edge flips while `zoom > 1` horizontally overflows, or only when the pan is at that edge. Make the zone width a setting.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test of a pure `tapZone(xInViewport, viewportW, rtl)`; device test in Facing mode tapping the spine.

### RD-06 Volume keys are always swallowed in the reader (even during read-aloud and at the last page)
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `ViewExtras.kt:91-92` `KEYCODE_VOLUME_DOWN -> volumeKeys && state.session != null && (flip(1) || true)` returns true even when no flip is possible. `volumeKeysFlip` defaults to true (`core/data/SettingsStore.kt:19`). The key is consumed both in Compose (`ReaderScreen.kt:278`) and in `MainActivity.kt:33-34`. There is no check of `ReadAloudController.isActive` and no `repeatCount` filter.
- User impact / scenario: a user listening to read-aloud presses volume up and the book jumps back a page while the TTS volume does not change. Holding the key machine-guns through pages. At the last page, volume becomes unchangeable.
- Proposed fix: return false (let the system change volume) when TTS is playing, when no flip happened, or when `event.repeatCount > 0`. Consider default off, or a first-use snackbar ("Volume keys turn pages — change in Settings").
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test of `handleKeyCode` with TTS active and at the last page; device test.

### RD-07 Read-aloud drives whichever document is open, not the one being read
- Type: bug
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `ReaderScreen.kt:112` `LaunchedEffect(tts.page, tts.state) { if (tts.state != IDLE && tts.page != state.currentPage …) state.jumpTo(tts.page) }`. `ReadAloudController` is a process-wide singleton (`core/tts/ReadAloudController.kt:24`) whose `Status` only carries `docTitle`, with no document identity.
- User impact / scenario: user starts read-aloud on book A, then opens book B from a link or a tab. Every TTS page change in A yanks B to the same page number, and the user cannot read B.
- Proposed fix: add `docUri` to `Status` and only sync when it equals `state.fileUri`. Show "Reading: A — return" in B's TTS bar.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test on a pure `shouldFollow(status, fileUri)`; device test with two docs.

### RD-08 Read-aloud silently skips to the end when the reader is closed or while saving
- Type: bug
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: the provider is `{ state.pageText(it) }` (`ReaderScreen.kt:145`), which returns `runCatching { session?.text(i) ?: "" }` (`ReaderState.kt:220-223`). After `ReaderState.close()` sets `session = null` (`:245`), or while `swapFile` has the session closed (`:456`), every uncached page yields "". `ReadAloudController.speakCurrent()` then calls `advancePage()` immediately for empty chunks (`ReadAloudController.kt:144`). It loops through all remaining pages and stops, with no message. Image-only (scanned) pages are also skipped silently.
- User impact / scenario: user starts read-aloud, presses Back to browse files, and expects the foreground service to keep reading. It reads to the end of the current page, then the notification disappears. An annotation autosave mid-reading can drop a page.
- Proposed fix: give the controller its own lightweight `PdfSession` (or a text provider on the file) that is independent of the screen. Retry once on a closed session. On an empty page, say "page N has no text" or offer OCR instead of skipping.
- Effort: M     Regression risk: Med     Dependencies: none
- Validation: unit test of the controller with a provider that returns "" (assert it does not race through pages); device test pressing Back while reading.

### RD-09 Arabic voice selection misses Arabic presentation-form text
- Type: bug
- Status: PROBABLE
- Severity: Medium      Priority: P2
- Evidence: `ReadAloudController.kt:191` `val arabic = text.any { it in '\u0600'..'ۿ' }` covers U+0600–U+06FF only. Many Arabic PDFs extract to Presentation Forms (U+FB50–FDFF, U+FE70–FEFF). The in-flux `core/pageflip/ScriptDirection.kt:7-9` already handles those ranges. `pageText` is raw PDFium text with no normalisation (`ReaderState.kt:220`), and `ArabicTextFix` (core/search, in-flux) is not applied to TTS.
- User impact / scenario: in an Arabic textbook whose text layer uses presentation forms, TTS picks the English voice and reads nothing useful, or spells characters.
- Proposed fix: share one `isArabic` helper covering 0600–06FF, 0750–077F, 08A0–08FF, FB50–FDFF and FE70–FEFF. NFKC-normalise TTS text (folds presentation forms to base letters) and fix visual-order reversal before chunking.
- Effort: S     Regression risk: Low     Dependencies: core/search ArabicTextFix (in-flux)
- Validation: unit test with a FE8D/FEDF string; device test with an Arabic PDF from the test folder.

### RD-10 Read-aloud has no MediaSession (no headset, lock-screen or Bluetooth controls)
- Type: missing feature
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `core/tts/ReadAloudService.kt:317-338` builds a plain `NotificationCompat` with BigTextStyle actions. It has no `MediaSessionCompat` and no `MediaStyle`, so headset play/pause buttons do nothing. `resume()` re-speaks the whole ≤400-char chunk (`ReadAloudController.kt:79-83, 207`). `noArabic` stays set until `release()` even after the voice is installed (`:62, 117`). Non-Arabic text is forced to `Locale.ENGLISH` (`:201`).
- User impact / scenario: a commuter cannot pause from earbuds. After a call, the sentence restarts several lines back.
- Proposed fix: add a MediaSession with MediaStyle. Track sentence offsets with `onRangeStart` and resume from the current sentence. Use `TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA` for the "no Arabic" message. Detect other languages with ML Kit language-id, which is already a dependency.
- Effort: M     Regression risk: Low     Dependencies: none
- Validation: device test with Bluetooth headset.

### RD-11 Night mode inverts bitmaps on the CPU (photos become negatives, 3× memory spike per render)
- Type: performance
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `PdfSession.kt:118` `val out = if (nightMode) invert(bmp) else bmp`. `invert` (`:138-150`) allocates an `IntArray(w*h)` plus a second ARGB bitmap per render and loops in Kotlin. At the 3200 px cap (≈2263×3200) that is about 29 MB + 29 MB + 29 MB transient. It runs for every zoom step (48 px quantisation) and every tile (`:134`). All colours are inverted, including images and highlight colours.
- User impact / scenario: night reading of a colour chemistry textbook shows negative photos and diagrams. Zooming in night mode is visibly slower, with GC pauses (needs device test).
- Proposed fix: render normally and apply a `ColorFilter` (ColorMatrix "invert lightness") when drawing in `PageCanvas` (GPU, zero extra memory). Optionally preserve images by drawing image rects un-filtered (PDFium image object bounds) for a "smart" dark mode. Drop `nightMode` from the bitmap cache key.
- Effort: S     Regression risk: Low     Dependencies: thumbnails/snapshot code that passes `night` to `render`
- Validation: screenshot test with ColorMatrix; memory profiler before and after.

### RD-12 Bitmap cache sizing: 64 MB per session, no trim-memory, polluted by zoom steps
- Type: performance
- Status: PROBABLE
- Severity: Medium      Priority: P2
- Evidence: `PdfSession.kt:55-57` `LruCache<String,Bitmap>(64 MB)` per session, keyed `index:width:height:night:annots` (`:112`). `PageCanvas.kt:85` quantises to 48 px, so a pinch from 1× to 3× creates a new entry about every 48 px (each up to ~29 MB, `MAX_RENDER_PX = 3200`, `:47`), and the cache keeps only about 2 of them. Displayed `base` bitmaps stay referenced outside the cache. Several sessions can be alive at once (reader, `ui/home/Thumbnails.kt:65`, `SplitCompare.kt:108`, `core/search/IndexWorker.kt:65`), each with its own 64 MB budget. There is no `onTrimMemory`/`ComponentCallbacks2` anywhere (grep). `android:largeHeap="true"` (manifest :30) hides the problem on test devices.
- User impact / scenario: on a 3 GB phone, a student zooms around a dense 1000-page PDF with split view open. Likely OOM or a low-memory kill in the background, which loses the RD-02 state.
- Proposed fix: one app-wide bitmap pool sized from `ActivityManager.memoryClass/8`. Cache only "settled" renders (after the 140 ms debounce) and not intermediate zoom steps. Use RGB_565 for opaque pages without alpha. Clear caches on `onTrimMemory(TRIM_MEMORY_RUNNING_LOW/UI_HIDDEN)`.
- Effort: M     Regression risk: Med     Dependencies: page-flip snapshot cache (shares bitmaps)
- Validation: memory profiler scenario (pinch 1→6× on 5 pages); `adb shell am send-trim-memory`.

### RD-13 Unbounded per-document text and char-box caches (reflow of a long book)
- Type: performance
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `ReaderState.kt:163-164` `HashMap<Int,String>` and `HashMap<Int,List<RectF>>` with no size bound. They are cleared only on reopen (`:490`). `ui/view5/ReflowReader.kt:96-113` walks every page calling `s.pageText(p)` and `s.charBoxes(p)`, so for 1000 pages × ~3000 chars it keeps about 3 M `RectF` objects (≥100 MB). Each 12-page chunk also re-parses the PDF with PdfBox (`PageGraphics.images(f, …)`) and rebuilds the whole `ordered` list (O(n²)).
- User impact / scenario: turning on reflow in a 600–1000-page textbook grows the heap until the app slows or dies (needs device test).
- Proposed fix: make `boxCache` an LRU of about 20 pages. Reflow should compute lines and discard boxes. Open the PdfBox document once for the image scan, and append blocks incrementally.
- Effort: S     Regression risk: Low     Dependencies: view5 reflow
- Validation: unit test asserting cache size ≤ N after 100 `charBoxes` calls; heap dump on a 1000-page file.

### RD-14 Every save (including autosave on pause) copies the whole file 2–3× and rewrites it with PdfBox
- Type: performance
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence: `ReaderState.save()`, `ReaderState.kt:371` `history.copyIn(f)` (full byte copy, `core/history/DocHistory.kt:58-63`). `:388` `AnnotationWriter.commit` loads the full document and calls `d.save(out)` (full, not incremental; `core/pdf/Annotations.kt:73, ~87`). `:390` `swapFile` closes and reopens the session. `:393` `writeBack` copies the file again to the source URI. Autosave triggers on every `ON_PAUSE` when dirty (`ReaderScreen.kt:92`, `autoSave` default true). `ProgressDialog` shows while `saving` (`ReaderScreen.kt:351`).
- User impact / scenario: a student highlights two lines in a 120 MB textbook and switches to WhatsApp. The phone copies 120 MB, rewrites 120 MB and copies 120 MB to Drive. On return there is a "Processing" modal, and on a slow device the save may still run when the OS kills the app. History caps at 300 MB, so only about 2 undo steps fit.
- Proposed fix: use PdfBox `saveIncremental` (appends only the new annots, also fixing the 3× cost). Store history as XFDF/annotation deltas for annotation-only steps instead of full-file copies. Write back incrementally or debounce writeBack.
- Effort: L     Regression risk: Med     Dependencies: history module, signatures (incremental save keeps signatures valid)
- Validation: benchmark save on a 100 MB file before and after; verify signed PDFs remain valid after annotation.

### RD-15 Full PdfBox parse on every open and every reopen, competing with first render
- Type: performance
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `ReaderState.kt:202, 495` `loadExisting()` → `AnnotationWriter.list` → `PDDocument.load(src)` iterating all pages (`core/pdf/Annotations.kt:349-361`). It runs right after the session appears and after every save or reopen. `forms5.ensureLoaded` also loads a full form model (`ui/forms5/FormFillState.kt:203`, call timing PROBABLE).
- User impact / scenario: opening a 1000-page book parses it twice (PDFium + PdfBox) while the first pages render, which slows scrolling in the first seconds and spikes memory.
- Proposed fix: list stored annotations lazily per visible page via PDFium (`FPDFPage_GetAnnot*`), or defer `loadExisting` until the Comments panel opens or editing starts. Use `MemoryUsageSetting.setupTempFileOnly()` when PdfBox is needed.
- Effort: M     Regression risk: Med     Dependencies: annotation editing (AnnotEdit uses `existing`)
- Validation: Perfetto trace of open; heap during open of a large file.

### RD-16 Stale page sizes after a tool changes pages other than the probed ones
- Type: bug
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `ReaderState.reopen()` `:474-481` re-measures only when the page count changed or pages {0, current, current+1} changed size, otherwise keeping `pageSizes`. `PageCanvas.kt:76` and `PageView.kt:80` read sizes from that map, and `pageSize()` returns the cached value first (`:212`).
- User impact / scenario: in Organize, the user rotates pages 10–20 or sets a crop box, then returns to the reader on page 1. Pages 10–20 are drawn into the old aspect box (stretched) until the document is reopened.
- Proposed fix: after an external or tool change (`reloadIfChanged`, `applyInPlace`), always clear and re-measure sizes. The ±15 pre-measure is cheap, and the rest happens in the background. Keep the probe shortcut only for annotation-only saves.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: instrumented test that rotates page 15 via `PdfOps`, calls `reloadIfChanged()`, and asserts `pageSizes[15]` is swapped.

### RD-17 Showing/hiding the toolbar re-lays out the page (content jumps)
- Type: usability
- Status: PROBABLE
- Severity: Medium      Priority: P2
- Evidence: `ReaderScreen.kt:211` `topBar = { if (chrome) Column { … } }` and `:259` `bottomBar = { if (chrome) … }`. The content is `padding(pad)` (`:273`), so the viewport height changes by the bar heights. In fit-page mode `baseWidth` depends on viewport height (`PageView.kt:94`), so pages resize and re-render. `LaunchedEffect(… viewport …) clampPan()` (`:209`).
- User impact / scenario: a one-handed tablet reader taps the centre to hide the bars, and the text they were reading jumps about 120 dp up. In fit-page mode the page also changes scale.
- Proposed fix: draw the bars as overlays (`Box` with `AnimatedVisibility`) over a page area that always fills the screen, and use insets padding only on the bars.
- Effort: M     Regression risk: Med     Dependencies: banners anchored to TopCenter
- Validation: device test; screenshot comparison of page offset before and after the toggle.

### RD-18 No real distraction-free mode
- Type: missing feature
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `ui/gaps6/ReaderHooks.kt:60-68` hides only `statusBars()` and only when `fullScreenReader` is set (default false, `SettingsStore.kt:39`). Hiding chrome does not hide the system bars. `PageIndicator` is always drawn (`ReaderScreen.kt:289`), as are the OCR/XFA/redact banners.
- User impact / scenario: the reader never gets an edge-to-edge page. The page pill covers the last text line.
- Proposed fix: when `chrome` is false, call `hide(systemBars())` with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, and fade the page indicator after 2 s.
- Effort: S     Regression risk: Low     Dependencies: RD-17
- Validation: device test.

### RD-19 Fast-scroller thumb can only be grabbed where it was first drawn
- Type: bug
- Status: PROBABLE
- Severity: Medium      Priority: P2
- Evidence: `ui/reader/view/PageViewParts.kt:92` `val frac = …currentPage…` (plain val). `:98` `.pointerInput(pageCount) { awaitEachGesture { … val y0 = frac * (trackH - thumbH); if (abs(down.y - (y0+thumbH/2)) > thumbH*1.2f) return… } }`. The block is keyed only on `pageCount`, so `frac` stays at its first-composition value. The scroller also only exists while scrolling or within 1.5 s after (`:85-89`).
- User impact / scenario: student on page 300 of 600 tries to drag the visible thumb in the middle of the track and nothing happens. Only touching where the thumb was at open time (top) works. This is the main fast navigation for long books in continuous mode.
- Proposed fix: `val fracState = rememberUpdatedState(frac)` and read it inside the gesture. Allow tap-on-track to jump. Keep the scroller hit area alive (invisible) instead of `return` when faded.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Compose UI test that scrolls to page 300, then drags at the thumb's current y; needs device test.

### RD-20 Page labels are implemented but never shown or accepted
- Type: missing feature
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P2
- Evidence: `ui/edit5/ReaderExtras.kt:15-34` defines `rememberPageLabels`, `pageIndicator` and `resolvePageInput`, with no callers (grep). The indicator shows `page_of(current+1,count)` (`ReaderOverlays.kt:139-142`). `GoToPageDialog` accepts digits only (`ReaderDialogs.kt:74`, `filter { isDigit() }`). Outline, bookmarks and thumbnails show indices only.
- User impact / scenario: the textbook's printed page "245" is PDF page 263. The student types 245 and lands on the wrong page. Roman-numbered front matter is confusing.
- Proposed fix: wire `rememberPageLabels` into `PageIndicator`, and use `resolvePageInput` in `GoToPageDialog` with a text keyboard.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test of `resolvePageInput` (exists); UI test with a labelled PDF.

### RD-21 Thumbnails panel always opens at page 1; outline does not show the current chapter
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `ReaderPanels.kt:203` `LazyVerticalGrid(…)` has no `state`/initial index. The current page is only bordered (`:211-215`). `OutlinePanel` (`:79-106`) does not highlight or scroll to the entry containing `currentPage`. Thumbnail renders share the 64 MB page cache and can evict the visible page's bitmaps (`:209`).
- User impact / scenario: on page 450 of 600, the user opens thumbnails to skim nearby pages and must scroll 450 pages of thumbnails.
- Proposed fix: `rememberLazyGridState(initialFirstVisibleItemIndex = currentPage - 3)`. Highlight and scroll the outline entry with the greatest `page ≤ currentPage`. Use a separate small thumbnail cache.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Compose test asserting the first visible thumbnail index after opening.

### RD-22 Reading state is not remembered per document (mode, fit, zoom, offset)
- Type: missing feature
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `ReaderState.kt:96-101` initial `viewMode` is CONTINUOUS or SINGLE from the global `continuousScroll`. `fitPage=false`, `zoom=1`, `rotation=0`. Persisting only writes `continuousScroll = viewMode == CONTINUOUS` (`ReaderScreen.kt:318`), so FACING/COVER are lost. Zoom is restored only from one of the last 5 tabs with no pan (`ScreenControls.kt:270-277`, `ReaderTabs.MAX = 5`). The DB stores only `lastPage` (`ReaderState.kt:233`), not the offset within the page. `ReaderState` is created with `remember` (`:654`), not saveable.
- User impact / scenario: the tablet reader sets cover-facing for a comic-style book and it resets to single every time. A zoomed reader of a two-column paper returns to the page top at 1×.
- Proposed fix: add `viewMode`, `fitPage`, `zoom`, `scrollFrac` (fraction within page) and `panX` to the docs table, or a per-doc prefs JSON keyed by file uri, and restore them in `open()`.
- Effort: M     Regression risk: Low     Dependencies: Room migration
- Validation: instrumented test: set FACING and zoom 2, close, reopen, assert both restored.

### RD-23 Zoom controls are limited (no zoom-out below fit, double-tap ignores the finger, no % or fit-height)
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `PageView.kt:42` `MIN_ZOOM = 1f` (fit), so the user cannot see two or three pages across on a tablet in continuous mode. `:183-186` double tap calls `applyZoom(2.5f, Offset(viewport.width/2, viewport.height/2), …)` with the tap point `at` unused. `ViewSheet.kt:94-99` offers only fit page / fit width. No zoom-level readout anywhere.
- User impact / scenario: the student double-taps a formula at the bottom of the page and the zoom centres on the middle of the page, so they have to pan to find it.
- Proposed fix: anchor double-tap at `at`. Allow MIN 0.25 in continuous mode with a multi-column grid when below 1. Add fit-height and a transient "150 %" toast with presets.
- Effort: S (anchor) / M (zoom-out grid)     Regression risk: Low     Dependencies: none
- Validation: Compose test: double tap at (x,y) and assert the point under the finger stays fixed.

### RD-24 No mouse/trackpad support: Ctrl+wheel zoom missing, wheel scroll disabled when zoomed
- Type: missing feature
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: no `PointerEventType.Scroll` handling in `ui/` (grep). In continuous mode `userScrollEnabled = state.zoom <= 1.01f || toolBusy()` (`PageView.kt:321`), so mouse wheel scrolling stops once zoomed. `readerGestures` handles touch only (`PageViewParts.kt:34-71`).
- User impact / scenario: a Chromebook or DeX user zooms with Ctrl+=, then the wheel stops scrolling the document.
- Proposed fix: in `readerGestures` handle `PointerEventType.Scroll`: Ctrl → `applyZoom(exp(-dy*k), position)`, otherwise pan and scroll.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: device test with mouse (DeX / ChromeOS / emulator).

### RD-25 Activity recreation on keyboard/density/font-scale changes discards the reader (undo history deleted)
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence: manifest `configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize|uiMode"` (line 37) does not include `keyboard`, `navigation`, `density`, `fontScale` or `locale|layoutDirection`. On recreate, `rememberReaderState` (`ReaderState.kt:651-658`) creates a new state, and `onDispose { state.close() }` runs `history.dispose()`, which deletes the undo folder (`:252`, `DocHistory.kt:165-170`). Pending annotations survive only if `autoSave` (`ReaderScreen.kt:92`); with autosave off they are lost. Zoom, mode, selection and open panels are lost either way.
- User impact / scenario: tablet user attaches a keyboard cover, or changes display size or font size from quick settings. The document reloads, undo history vanishes, and with autosave off the last highlights disappear.
- Proposed fix: add `keyboard|navigation|density|fontScale|layoutDirection` to configChanges, and hold `ReaderState` in a NavBackStackEntry-scoped ViewModel so it survives recreation. Do not dispose history on configuration-change destroy (`isChangingConfigurations`).
- Effort: M     Regression risk: Med     Dependencies: Compose resources must recompose on density/fontScale (they do)
- Validation: instrumented test: add an annotation (autosave off), trigger `ActivityScenario.recreate()`, assert the annotation is still pending and `canUndo`.

### RD-26 Incoming VIEW intent is handled again on activity recreation (duplicate reader)
- Type: bug
- Status: PROBABLE
- Severity: Medium      Priority: P2
- Evidence: `MainActivity.kt:40` `handleIntent(intent)` runs unconditionally in `onCreate`, ignoring `savedInstanceState`. After process death the NavHost restores its back stack (already containing the reader) and then `LaunchedEffect(uri)` navigates to `Routes.reader(...)` again (`:54-58`), after re-copying the content URI (`:116-118`). The `RestoreLastSession` guard (`ReaderTabs.kt` `currentDestination == READER`) does not apply to this path.
- User impact / scenario: user opens a PDF from Gmail, is killed in the background, and returns. A second reader instance sits on the stack (Back shows the same document), and the file is copied again.
- Proposed fix: `if (savedInstanceState == null) handleIntent(intent)`. Mark consumed intents, e.g. `intent.action = null` after handling.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: instrumented test with "Don't keep activities".

### RD-27 Opening from another app shows no progress during import; repeated taps race on the same temp file
- Type: usability
- Status: CONFIRMED (traced in code) / race PROBABLE
- Severity: Medium      Priority: P2
- Evidence: `MainActivity.kt:116-120` copies the whole content URI (`DocumentRepository.localFile`, `core/files/DocumentRepository.kt:56-72`) in `lifecycleScope` before navigating, with no UI. The temp name `.${target.name}.import` comes from `uniqueFile`, which returns the same name until the rename (`:34-39, 66-69`). Two concurrent intents for the same URI write the same tmp file. Reuse is keyed only on size (`srcSize == known.length()`, `:60`), so a re-downloaded file with identical size shows the stale copy.
- User impact / scenario: user taps a 150 MB PDF in Drive. Home stays still for 10 s, they tap again, and there is a chance of a corrupted working copy. An edited-and-reuploaded file of equal size opens the old version.
- Proposed fix: navigate to the reader immediately with an "Importing… 45 %" state (copy inside `ReaderState.open`). Serialise imports per URI (Mutex map). Compare mtime (`DocumentsContract.COLUMN_LAST_MODIFIED`) or a head/tail hash as well as size.
- Effort: M     Regression risk: Low     Dependencies: none
- Validation: unit test of the per-URI import mutex; device test with a large Drive file.

### RD-28 RTL page order follows the app language, not the book; /Direction R2L is ignored
- Type: missing feature
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `PageView.kt:61` `rtl = LocalLayoutDirection == Rtl`, which drives spread order (`:122`), tap zones (`:216-217`) and keys (`ViewExtras.kt:95-96`). `HorizontalPager` follows LayoutDirection. The direction comes from the UI language/override (`SmartDocsTheme(rtl = LayoutDir.isRtl(settings))`, `MainActivity.kt:46`). The PDF `/ViewerPreferences /Direction` is editable (`core/edit5/DocPropsOps.kt:26`) but never read by the reader. The in-flux `PageFlipController.rtl()` adds auto-detection (`ui/pageflip/PageFlipController.kt:48-53`), but the stable pager does not have it.
- User impact / scenario: an English-UI user reads an Arabic book in facing mode and gets pages in the wrong order with swipes reversed. An Arabic-UI user reading an English paper gets RTL spreads.
- Proposed fix: resolve `docRtl` once per document (ViewerPreferences Direction → script detection → UI), and pass it to PageView, keys and tap zones independently of LayoutDirection (wrap the pager in `CompositionLocalProvider(LocalLayoutDirection provides …)`). Persist the choice per document (RD-22).
- Effort: M     Regression risk: Med     Dependencies: pageflip module (in-flux) has the detector
- Validation: unit test of the resolver; device test with an Arabic book in English UI.

### RD-29 Search and outline jumps land at the page top, not at the target
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P2
- Evidence: `ReaderState.kt:236` `jumpTo(page)` only carries a page. `PageView.kt:189-193` uses `scrollToItem(p)` (offset 0) or `animateScrollToPage`. Search hits have rects (`SearchHit.rects`) that are never used for positioning (`ReaderState.kt:590, 598`).
- User impact / scenario: while zoomed at 2×, the student steps through 30 hits. Each hit at the bottom of a page is off-screen, so they must scroll to find the orange box.
- Proposed fix: add `pendingJumpRect`, scroll so the rect sits about 1/3 down the viewport, and adjust `panX` when zoomed.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Compose test: after `searchStep`, the hit rect lies inside the viewport.

### RD-30 Accessibility: page content is invisible to TalkBack, page changes are not announced
- Type: missing feature
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence: no `semantics`/`contentDescription` in `PageCanvas.kt`, `PageView.kt`, `PageViewParts.kt` or `ReaderOverlays.kt` (grep). The page is a raw `Canvas`. `PageIndicator` is a clickable Surface without a role label. Fast scroller and tap zones are gesture-only.
- User impact / scenario: a blind user opens a PDF and TalkBack says nothing about the page, cannot read its text (except via reflow or read-aloud), and does not know when the page changed.
- Proposed fix: give each page node `semantics { contentDescription = "Page N of M"; text = pageText (lazy) }` plus `liveRegion` announcements on page change, and custom accessibility actions "Next page" and "Previous page". Point users to reflow as the accessible mode.
- Effort: M     Regression risk: Low     Dependencies: none
- Validation: Accessibility Scanner plus a TalkBack device test; Compose semantics test.

### RD-31 Accidental page turns: edge tap fires on the tap that stops a fling; every tap waits for the double-tap timeout
- Type: usability
- Status: HYPOTHESIS
- Severity: Medium      Priority: P2
- Evidence: `readerGestures` `onDown = { flingJob?.cancel() }` does not consume the down (`PageViewParts.kt:43-44`). The page's `detectTapGestures` (`PageCanvas.kt:132-151`) will still see a tap after the LazyColumn fling stops. `onDoubleTap` is installed, so every single tap (including edge flips) is delayed by `doubleTapTimeout` (~300 ms).
- User impact / scenario: user flicks through a long document and taps to stop near the right edge, and the reader jumps a page. Flipping by edge taps feels laggy.
- Proposed fix: ignore taps whose down arrived while a scroll or fling was in progress (`listState.isScrollInProgress` at down). For edge zones, act on the first tap and handle double-tap only in the centre zone.
- Effort: S     Regression risk: Low     Dependencies: RD-05
- Validation: needs device test.

### RD-32 Keep-screen-on defaults to off
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `SettingsStore.kt:22` `keepScreenOn = false`, and `ScreenControls.kt:238` turns the flag off when the setting is off. The touch-restarting timeout logic only runs when the setting is on.
- User impact / scenario: the student studies a dense page for 60 s and the screen dims and locks (system timeout 30 s).
- Proposed fix: default to on with the existing 10-minute idle timeout (`ViewExtras.kt:33`), which already restarts on touch.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: device test.

### RD-33 Reflow overlay leaves the page view rendering underneath
- Type: performance
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `ReaderScreen.kt:285-288` composes `PageView` and then `if (state.reflow) ReflowReader(state)` on top. PageView keeps rendering and tiling visible pages and competes for the PDFium lock with reflow's text extraction.
- User impact / scenario: reflow builds slower and uses more memory than needed.
- Proposed fix: `if (state.reflow) ReflowReader(state) else PageView(...)`, keeping `listState` hoisted so the position survives.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: trace that no `render` calls happen while reflow is on.

### RD-34 Rotation in continuous mode loses the position within the page; landscape has no automatic spread
- Type: usability
- Status: PROBABLE
- Severity: Low      Priority: P3
- Evidence: with configChanges the LazyColumn keeps `firstVisibleItemScrollOffset` in pixels. Item heights change by about 2× on rotation (`PageView.kt:325`, width-based), so the relative position drifts. Nothing switches to FACING in landscape on tablets: only the fold BOOK posture does (`ReaderHooks.kt:47-55`, `tablet = screenWidthDp > 700` only affects panels, `ReaderScreen.kt:80`).
- User impact / scenario: a tablet reader rotates to landscape and loses their line. They then see one huge page needing scrolling instead of two pages.
- Proposed fix: on viewport change, convert the offset to a fraction of item height and restore it. Add an option "Two pages in landscape" for width ≥ 840 dp.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: device test.

### RD-35 Main-thread file IO and blocking close in reader-adjacent paths
- Type: performance
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `ReaderState` constructor (inside `remember`, main thread) calls `DocHistory.open` → `cleanupStale(root)`, which deletes stale history folders of up to 300 MB (`ReaderState.kt:130`, `DocHistory.kt:183-193`). `ui/view5/SplitCompare.kt:111` calls blocking `ses.close()` (`runBlocking { mutex.withLock }`, `PdfSession.kt:266-270`) in `onDispose` on the main thread. If the dialog is disposed while `PdfSession.open` (`:108`) is still running, the new session is never closed (leak).
- User impact / scenario: jank or an ANR risk when opening the reader after a crash left large history folders, or when closing split view mid-render.
- Proposed fix: run `cleanupStale` on IO in the background. Use `closeAsync` from a non-composition scope, and check a disposed flag after open, as `ReaderState` does at `:187`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: StrictMode `detectDiskWrites` in debug builds.

### RD-36 PDFium global lock gives background work equal priority with on-screen renders
- Type: arch-risk
- Status: PROBABLE
- Severity: Low      Priority: P3
- Evidence: every pdfiumandroid call takes the library's process-wide lock (classes `PdfPage$renderPageBitmap$$inlined$wrapLock$1`, `PdfDocument$openPage$$inlined$wrapLock$1`, `PdfiumCore$newDocument…wrapLock`). This is correct (thread safety is guaranteed), but `IndexWorker` (`core/search/IndexWorker.kt:65`), home thumbnails, the reader's background `pageSizes()` over all pages (`ReaderState.kt:198`) and `OcrSuggestionBanner` (`ui/quick/QuickActions.kt:148-160`) all queue FIFO against visible-page renders. Visibility does not set any priority.
- User impact / scenario: the first seconds after opening a 1000-page book, or while the search index worker runs, show white pages longer (needs device test).
- Proposed fix: pause or yield the IndexWorker while the reader is foregrounded. Measure page sizes in batches with `yield()`, or use `FPDF_GetPageSizeByIndex` (no page load). Prioritise renders for visible pages (a small priority queue in front of the session mutex).
- Effort: M     Regression risk: Med     Dependencies: search indexing owner
- Validation: Perfetto trace of open plus concurrent indexing.

### RD-37 Password flow: OK, with small gaps
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `ReaderDialogs.kt:32-50` has no show-password toggle and no "remember for this session" option. Cancel leaves the reader (`ReaderScreen.kt:315`). Wrong-password detection partly relies on message matching (`PdfSession.kt:296-297` `"password" in msg || "pdfium" in msg && "security" in msg`), and a non-password PDFium error containing "password" text would loop the dialog. The password is not persisted (good for security) but must be retyped after every recreate or kill (RD-25).
- User impact / scenario: users with long passwords can't verify their typing, and must re-enter after a background kill.
- Proposed fix: add a visibility toggle, rely only on `PdfPasswordException`, and optionally hold the password in an encrypted in-memory session store until the app process dies.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test of the exception mapping.

### RD-38 View settings, stored and current, drift between stores
- Type: arch-risk
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: view state is spread across DataStore (`AppSettings.continuousScroll/nightMode/pageColor`), SharedPreferences "view" (`ViewExtras.kt:21`, also written by `ReaderViewPrefs`, `core/gaps6/G6Prefs.kt:57-61`), "view5" (`V5State.kt:28`), "tabs" (`ReaderTabs.kt:52`), "pageflip" (in-flux) and Room (`lastPage`). `ViewExtras` reads its prefs once at construction, so a change from Settings while the reader is open is ignored until reopen.
- User impact / scenario: changing orientation lock or timeout in Settings with a document open has no effect until the reader is reopened.
- Proposed fix: one `ReaderPrefs` DataStore flow (global) plus a per-document record (RD-22). Observe it instead of snapshot reads.
- Effort: M     Regression risk: Med     Dependencies: RD-22
- Validation: unit test of the prefs repository.

### RD-39 Bookmarks only via panel; no one-tap bookmark or indicator on bookmarked pages
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: adding a bookmark requires opening the panel and pressing "Add" (`ReaderPanels.kt:123-129`). The top bar has no bookmark toggle and the page area shows no indicator (`ReaderScreen.kt:215-246`). Bookmarks are keyed by `state.fileUri` (working copy), so the same book imported twice under different names has separate bookmarks.
- User impact / scenario: the student wants to mark the page with tomorrow's homework in one tap.
- Proposed fix: add a bookmark toggle icon in the top bar (filled when the current page is bookmarked), plus a ribbon on bookmarked pages.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: UI test.

### RD-40 Search hit and other overlays stay after reopen
- Type: bug
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `reopen()` (`ReaderState.kt:467-497`) clears text and box caches but not `searchHits`. After a tool replaces the file (deleting pages, OCR adding text), old hit rects are drawn on the wrong pages (`PageCanvas.kt:249`).
- User impact / scenario: after "delete page 3" from the reader, the orange search boxes appear shifted onto other text.
- Proposed fix: on reopen with a changed page count or size, rerun `runSearch(searchQuery)` or clear hits.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: instrumented test.

### RD-41 Page-flip module (in-flux, light review)
- Type: arch-risk
- Status: PROBABLE
- Severity: Low      Priority: P3
- Evidence (in-flux area): `ui/pageflip/PageFlipController.kt` per-document key `"doc_" + Integer.toHexString(s.uriString.hashCode())`. `uriString` is the route URI (content:// for intent opens vs file:// otherwise; compare `ReaderState.fileUri`, `ReaderState.kt:93`), so the same book gets two sets of per-doc prefs, and 32-bit hash collisions are possible. `PageFlipHost.kt:94` `remember(session, renderVersion, nightMode) { PageSnapshotCache(…) }` drops the old cache without `clear()`, so its in-flight render jobs continue against the closed session (they fail fast; wasted work only). On the positive side, `PageSnapshotCache.renderSize` intentionally mirrors `PageCanvas` quantisation (shared cache hits), never recycles shared bitmaps, and bounds memory with `retainAround`. `ScriptDirection` covers presentation forms (useful for RD-09/RD-28).
- User impact / scenario: flip settings chosen "for this document" are lost when the same file is next opened from Recents instead of the share sheet.
- Proposed fix: key by `fileUri` (or a content hash). `DisposableEffect(cache) { onDispose { cache.clear() } }`.
- Effort: S     Regression risk: Low     Dependencies: pageflip author (in-flux)
- Validation: unit test of key derivation.

### RD-42 Test gap: no automated coverage of the reader's state machine
- Type: test-gap
- Status: PROBABLE
- Severity: Medium      Priority: P2
- Evidence: the reader's lifecycle logic (`open` / `reopen` / `swapFile` / `close`, error state, route page vs DB page, mode switching) lives in `ReaderState` and composables with direct `SmartDocsApp`/Room/PDFium dependencies (`ReaderState.kt:69-76`). RD-01, RD-02, RD-04, RD-16 and RD-25 are all regressions that a unit or instrumented suite would have caught. Pure helpers such as `PageSpread` and `PositionHistory` are testable, but none of the reader flows are.
- User impact / scenario: regressions in core reading flows reach users.
- Proposed fix: extract a `ReaderController` (ViewModel) with injectable `DocumentSource`/`SessionFactory`. Add Robolectric tests for error, password, resume page and reload, and instrumented recreate tests.
- Effort: L     Regression risk: Med     Dependencies: RD-25
- Validation: CI.

## Quick wins
Small effort, high impact:
- RD-01 (persistent error view with retry)
- RD-02 (route page as one-shot; Recents pass -1)
- RD-03 (move/skip the full-file marker scan before first render)
- RD-04 (key `pointerInput` / `rememberUpdatedState`)
- RD-05 (viewport-based tap zones)
- RD-06 (don't swallow volume during TTS, at ends, or on repeat)
- RD-07 (TTS follows only its own document)
- RD-11 (ColorMatrix night mode instead of CPU inversion)
- RD-16 (always re-measure sizes after an external change)
- RD-19 (fast-scroller stale `frac`)
- RD-20 (wire page labels — code already exists)
- RD-21 (thumbnails open at current page)
- RD-23 (double-tap anchors at finger)
- RD-26 (`savedInstanceState == null` guard)
- RD-33 (don't compose PageView under reflow)

## Needs device testing
- RD-03 TTFP gain on a 40–60 MB file (Perfetto)
- RD-04 pan/pinch after switching CONTINUOUS ↔ SINGLE, and after fold posture changes
- RD-05 / RD-31 tap zones in Facing mode, while zoomed, and tap-to-stop-fling
- RD-11 / RD-12 / RD-13 memory: pinch 1→6× in night mode; reflow on a 1000-page PDF; split view plus thumbnails on a 3 GB device
- RD-14 autosave cost on a 100 MB textbook (and whether the OS kills it mid-save)
- RD-17 content jump on chrome toggle
- RD-19 fast-scroller grab after scrolling
- RD-25 / RD-26 recreate: attach a keyboard, change display size/font, "Don't keep activities"
- RD-08 / RD-09 / RD-10 read-aloud: leave the reader while reading; Arabic presentation-form PDF; Bluetooth headset buttons
- RD-28 Arabic book in English UI (facing order, swipe direction)
- RD-30 TalkBack walkthrough
- RD-34 rotation position drift in continuous mode
