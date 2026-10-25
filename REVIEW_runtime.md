# Runtime review — crashes & functional bugs

Paths are relative to `app/src/main/java/com/smartdocs/editor/`. Line numbers refer to the file **before** the fix.

## 1. Reader + TTS

| Issue | Change |
|---|---|
| `core/pdf/PdfSession.kt:178` `close()` freed the PDFium document without taking the mutex, so a render/text call in flight on another thread touched freed native memory (SIGSEGV on save/swap, on fast back-navigation and when the organizer re-opened). | `close()` now sets a `closed` flag and takes the mutex (`runBlocking`); added `suspend closeAsync()` for coroutine callers; every native access goes through `page()` which throws `IllegalStateException` after close and `IllegalArgumentException` for an out-of-range index instead of crashing natively. `isClosed` exposed. |
| `PdfSession.pageSize()` did native work on the caller's (main) thread and could return 0/NaN (broken MediaBox) → division by zero / NaN layout sizes in `PageView.baseWidth`, `PageCanvas` (`scale = widthPx / pw`), `ThumbnailsPanel` (`160*h/w`), `OrganizeState.requestThumb`. | Runs on `Dispatchers.IO`; width/height are sanitised (`> 1` and finite) to A4 defaults. `ReaderState.pageSize` also rejects a closed session / bad index. |
| `PdfSession.open` leaked the `ParcelFileDescriptor` when `newDocument` threw (wrong password loop). | fd closed on failure. |
| `ui/reader/ReaderState.kt:272` `swapFile` renamed the temp over the file while the old session still held it, and the `copyTo(overwrite=true)` fallback truncated the file **in place** under the open session; the old session was closed 2 s later without a lock. | Order is now: write temp (`AnnotationWriter.commit`) → `closeAsync()` old session (waits for in-flight renders) → rename/copy → reopen. The closed session object stays referenced so the page view keeps its scroll position; renders issued during the swap fail fast and `PageCanvas` keeps its last bitmap. No reopen after the screen was disposed (would leak a native doc). |
| `ReaderState.save()` could be cancelled mid-swap (ON_PAUSE autosave used the composable scope, which is cancelled when the NavBackStackEntry is disposed) and two saves could run concurrently (back button + autosave). | Critical section wrapped in `withContext(NonCancellable)`, serialised with a `Mutex`; `saveInBackground()` runs in a scope that outlives the composable and `ReaderScreen` ON_PAUSE uses it. `deleteExisting` got the same treatment. |
| `ReaderState.open()` could open a second session (double call) or finish after dispose → leaked native document. | Guarded (`session != null` → return; closes the new session if `disposed`). |
| `ui/reader/ReaderScreen.kt:107-112` POST_NOTIFICATIONS was *requested* and the foreground service started in the same tap; on API 33+ the first read-aloud ran without a notification. | Permission is checked first; if missing it is requested and reading starts in the result callback (granted or denied — the FGS is allowed either way). |
| `core/tts/ReadAloudService.kt:469` when the controller was already IDLE the service called `stopSelf()` **without** `startForeground()` after `startForegroundService()` → `RemoteServiceException: did not then call Service.startForeground()` (process kill on API 26+). Reproducible with an empty first page (chunks empty → `advancePage()` → `stop()` before `startService()`). | `startForeground(id, notif, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)` (API 29+; plain overload below) is now always called first in `onStartCommand`; `ReadAloudController.startService()` no longer starts the service for an IDLE controller. Manifest already declares `mediaPlayback` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`. |
| `ReadAloudController` `onDone/onError` acted on stale utterances (after `setRate`, `pause`, `goToPage` stop the engine) → skipped chunks / double advance. | Callbacks are ignored unless `utteranceId == "rd_$utteranceSeq"`. |
| `ui/reader/ReaderPanels.kt:196` `ThumbnailsPanel` captured the session once; after a save the closed session was used for thumbnails. | `produceState` keyed on the session, skips closed/out-of-range. |
| `ui/reader/PageCanvas.kt:83-88` a failed render replaced the bitmap with `null` (white flash during save). | Keeps the previous bitmap; skips closed sessions / bad page index. |

## 2. `core/pdf` write operations

| Issue | Change |
|---|---|
| **Every** PdfBox save of an encrypted document failed: PdfBox 2.0 throws `IllegalStateException("PDF contains an encryption dictionary, please remove it … or set a protection policy")` when a document opened with a password is saved without `protect()`. Affected: `PdfOps.*` (delete/rotate/stamp/watermark/page numbers/compress/setInfo/setOutline/flattenForms/insertBlank/insertPagesFrom), `AnnotationWriter.commit/delete` (reader save on protected PDFs), `FormOps.fillFields/resetAll`, `SearchablePdfWriter.save`. | `PdfOps.load` (now `internal`) re-protects an encrypted doc with the same password (AES-128); `removeSecurity` (`setAllSecurityToBeRemoved`) and `protect` (new policy) still override it. `AnnotationWriter`, `FormOps`, `SearchablePdfWriter` now load through it. |
| Reading and writing the same `File` (`out == src`) corrupts the document (`PDDocument` reads lazily from the source). No current caller in `ui/` does this (all use `uniqueFile`/`newTemp`/`.saving.pdf`), but nothing prevented it. | `PdfOps.saveTo()` writes to a sibling temp and renames; all `doc.save(out)` in `PdfOps` go through it. `merge` writes to a temp destination too. `AnnotationWriter.commit/delete` and `DigitalSignature.sign` `require(out != src)`. |
| `PdfOps.insertPagesFrom:154` always detached and re-inserted the imported pages, also for an append; now only moves them when `atIndex` is inside the document. Added optional `password` (OrganizeState passes it; inserting into a protected file used to fail). |
| `PdfOps.redact:352` (a) rects with `top < bottom` produced an empty `drawRect` (nothing redacted but the page was still rasterised → the operation silently did nothing); (b) `/Rotate` pages: the PDFium bitmap is rotated but coordinates were mapped as unrotated and the replacement page used the unrotated MediaBox → distorted/wrong boxes. | Rect corners are normalised after mapping; mapping handles 0/90/180/270 and MediaBox offsets; the replacement page is sized from the rendered raster (already rotated, `/Rotate` 0). Bitmaps are recycled and the session cache invalidated. |
| `PdfOps.flatten:327` held every rendered page bitmap in memory before writing → OOM on long documents. | Streams one page at a time. |
| `PdfOps.compress` — verified `res.put(name, newImg)` is the correct `PDResources.put(COSName, PDXObject)` overload and `return@forEach` targets the inner lambda. No change. |
| `AnnotationWriter.list` threw (and hid the whole panel) on a single annotation without `/Rect` or with a broken dictionary. | Per-page and per-annotation `runCatching`, `rectangle ?: PDRectangle()`. |

## 3. `MainActivity.kt`

Content URIs from ACTION_VIEW/ACTION_SEND were handed to the reader as-is; the copy (`documents.localFile`) only happened when the reader composable started. The temporary grant is per activity instance and non-persistable (`takePersistableUriPermission` throws and is swallowed), so a slow start or a second intent could lose access. Now `handleIntent` copies the content URI immediately in `lifecycleScope` and navigates with the resulting `file://` URI; `getParcelableExtra` uses the typed overload on API 33+.

## 4. `ui/files/FilesScreen.kt`

On API 33+ `MediaStore.Files` returns only the app's own PDFs and `READ_MEDIA_IMAGES` does not help (documents are not media). Added an "اختر مجلداً" (`OpenDocumentTree`) button on the "all PDFs" tab: the tree URI gets `takePersistableUriPermission`, is stored in `SharedPreferences("files")/tree_uri`, and PDFs are listed recursively via `DocumentFile` (bounded to 2000 files / depth 8, on IO) merged with the MediaStore results. A "Remove" action forgets the folder. `READ_EXTERNAL_STORAGE` is only requested below API 33. Strings in `res/values/strings_files.xml` (+ `values-en`).

## 5. Scanner / OCR

- `core/ocr/OcrEngine.kt:77` Tesseract init path: verified — `init(filesDir, …)` with data in `filesDir/tessdata/<lang>.traineddata` is correct; made explicit (`tessdataDir.parentFile`). Download already goes to `.part` then renames.
- `OcrEngine.close()` and `init()` were not synchronised with `recognize()` (which holds the monitor) → `TessBaseAPI.recycle()` while a recognition ran → native crash on leaving the OCR screen mid-run. Both are now synchronised; `recognize` reads the handle under the lock. `OcrScreen` cancels first and closes off the main thread.
- ML Kit result parsing (`GmsDocumentScanningResult.fromActivityResultIntent`) is correct; OCR progress callbacks are posted to the main scope via `scope.launch`. No change.

## 6. Digital signature

- `core/pdf/DigitalSignature.kt` never registered the full BouncyCastle; Android's stripped "BC" provider was used for PKCS#12 / CMS. Added `ensureProvider()` (remove Android's "BC", add bundled `BouncyCastleProvider`; appended rather than inserted at 1 so AES/RSA keep using AndroidOpenSSL) and `KeyStore.getInstance("PKCS12", "BC")` with fallback; called from load/generate/sign/verify.
- `sign()` set `isAllSecurityToBeRemoved` and then `saveIncremental()` on an encrypted input — incremental saves append to the original (encrypted) bytes, producing an unreadable file. An encrypted input is now fully re-saved unencrypted to a temp first, then signed incrementally.

## 7. Compose misuse sweep

Checked all `rememberLauncherForActivityResult` (all at composable top level), `stringResource` (none inside non-composable lambdas — screens pre-resolve strings into vals), `LaunchedEffect` keys (`ThumbnailsPanel` fixed above; others correct). `LocalContext` is used for `PrintManager` from the Activity context (correct). `ReaderScreen` `LocalLifecycleOwner` is the NavBackStackEntry — ON_PAUSE fires when navigating to a tool, hence the background-scope autosave above.

## 8. `ui/common/Components.kt`

`ResultSheet` used `CreateDocument("application/pdf")` for every result (txt/json/docx/png/zip results exist). Added `mimeFor(ext)`; the contract and the share mime are derived from the file extension.

## Other touched call sites

`OrganizeState.saveOverwrite` closed its session first and writes via sibling temp + rename (was `copyTo(overwrite=true)` on the original → truncated file on crash); `OrganizeState.reopen`, `RedactScreen`, `OcrScreen`, `AiScreen`, `PageTools`, `WatermarkTool`, `ExportTools` use `closeAsync()` in suspend context.

## Not fixed (needs a design decision)

- **Redaction on `/Rotate` pages, manual mode**: `RedactManualView` produces rects in *display* space (rotated PDFium size) while search hits are in PDF *user* space. `PdfOps.redact` now treats all regions as user space (correct for search hits and for all unrotated pages); manual regions on rotated pages land in the wrong place. Fix requires exposing page rotation from `PdfSession` (pdfiumandroid API) and converting in `RedactManualView`.
- `PdfOps.merge` cannot merge password-protected sources (`PDFMergerUtility` has no password hook); the error surfaces in the snackbar.
- `DocumentRepository.localFile` copies the same content URI again on every open (the "existing" check compares against `thumbPath`, which is never a PDF). Harmless but wasteful; left as is.
- `PdfSession.close()` is blocking by design (waits for in-flight native calls). `OrganizeState.dispose()` and `RedactScreen` `onDispose` call it on the main thread; waits are bounded by one thumbnail/page render.
