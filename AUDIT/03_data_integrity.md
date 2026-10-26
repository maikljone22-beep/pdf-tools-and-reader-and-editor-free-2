# 03 — Data integrity, persistence, file management (prefix DI)

## Scope & method

Read from `D:\pdf\audit-wt` (commit da74d31). All paths are repo-relative to `app/src/main/java/com/smartdocs/editor/` unless they start with `app/`.

What I read in full:
- `core/data/AppDatabase.kt` and `core/data/SettingsStore.kt` (the Room DB with its entities and DAOs, and the DataStore).
- `core/files/{DocumentRepository,FileCopy,ZipUtil,WifiTransferServer}.kt` and `ui/files/FilesScreen.kt`.
- `core/history/DocHistory.kt` and `ui/reader/ReaderState.kt` (open, save, swap, applyInPlace, restore, close).
- `ui/reader/AnnotEdit.kt` (convert, backup, restore), `ui/reader/ReaderScreen.kt` (lifecycle, back, save-as, write-back UI) and `ui/common/WriteBackUi.kt`.
- `core/sync/{SyncWorker,SyncEngine,SyncRules,SyncRuntime,SyncStore}.kt`.
- `core/cloud/{CloudLinks,CloudHttp}.kt` and the `GoogleDrive.kt` download/delete paths.
- `core/share5/{StorageManager,AppDataBundle,AppDataSync}.kt`, `core/view5/Trash.kt`, `ui/reader/view/ReaderTabs.kt`, `core/search/{FullTextIndex,IndexWorker}.kt`, `core/pdf/{PdfOps,LockedPdf,Annotations}.kt` (load/save paths) and `core/edit5/E5Io.kt`.
- `MainActivity.kt` (intent import), `SmartDocsApp.kt`, `app/src/main/AndroidManifest.xml` and `app/src/main/res/xml/`.

Writer census:
- A grep for `FileOutputStream|openOutputStream|.save(|renameTo(|.copyTo(|writeBytes(|writeText(|outputStream()` gave 321 hits outside convert6.
- The PdfBox operations use three helpers: `PdfOps.saveTo`, `E5Io.save` and `EditIo.save`. All three write a sibling temp file and then rename it, so they are atomic.
- The reader's swap path is `.saving.pdf`, then `renameTo`, then reopen. It is atomic on the local working copy.
- The non-atomic writers are the ones that matter: every write into a user-owned document through SAF/MediaStore (`writeBack`, `exportTo`, sync download), Files rename, delete-to-trash fallback, the Office plain-text save, and the `renameTo` → `copyTo` fallbacks.

What I could not verify:
- Real behaviour of `openOutputStream(uri,"w"/"wt")` on specific DocumentsProviders.
- Auto Backup quota and contents on a device.
- Navigation-Compose lifecycle timing (ON_PAUSE versus dispose order).
- `takePersistableUriPermission` eviction.

None of this could be run, because there is no emulator. Findings that depend on it are marked "needs device test".

## Capability inventory

| Feature | Status | Evidence |
|---|---|---|
| Room DB (recents, favourites, tags, signatures, bookmarks, fill profile, stamps) | partial / risky | `core/data/AppDatabase.kt:154-168` has version 1, `exportSchema=false` and `fallbackToDestructiveMigration()`. |
| Settings (DataStore) | works, fragile | `core/data/SettingsStore.kt:11`. There is no corruption handler and the AI key is stored in plaintext (`:75`). |
| ~45 SharedPreferences stores (tokens, licence, sync, tabs, origins…) | works, unencrypted | grep `getSharedPreferences`. OAuth access/refresh tokens are plaintext (`core/cloud/OAuthPkce.kt:52-55`). |
| Import of content:// docs into a working copy | works, data-loss edge cases | `core/files/DocumentRepository.kt:56-71` (DI-02, DI-03, DI-20) |
| Save into working copy (temp + rename) | works | `ui/reader/ReaderState.kt:364-404`, `swapFile` `:426-440` |
| Write-back to original document | works, non-atomic | `core/files/DocumentRepository.kt:90-107` (DI-01) |
| Save as / export to SAF | partial (errors swallowed) | `DocumentRepository.kt:178-180`, `ui/common/Components.kt:202`, `ui/common/WriteBackUi.kt:58-66` |
| Unified undo history (file snapshots) | partial | `core/history/DocHistory.kt`. History is lost when the reader leaves composition (DI-11). |
| Unsaved-changes on back | works | `ui/reader/ReaderScreen.kt:117-128` (autosave or discard dialog) |
| Unsaved-changes on navigate-away or process death | partial / broken | Nothing is persisted (DI-09, DI-10). |
| Files tab rename / move / copy / zip / unzip / delete-to-trash | partial | `ui/files/FilesScreen.kt:317, 326, 333-339`. Rename overwrites (DI-04); delete can be permanent (DI-07). |
| Trash (30 days) | works, minor gaps | `core/view5/Trash.kt:20-35, 64-68` |
| Recents / favourites / tags | works; keyed by working-copy `file://` path | `DocumentRepository.kt:127-137` |
| Reader tabs / restore session | works | `ui/reader/view/ReaderTabs.kt` |
| Cloud open / save-back | partial (no durable retry) | `core/cloud/CloudLinks.kt:44-84`, `SaveHooks :102-118` |
| Two-way folder sync (WorkManager) | works; per-pair mutex; snapshot written atomically | `core/sync/SyncRuntime.kt:130-166`, `SyncStore.kt:131-150` |
| Sync deletion safety | missing | `core/sync/SyncRules.kt:43-44` has no mass-delete guard. |
| App-data export/import (bundle + Drive appData) | partial | `core/share5/AppDataBundle.kt`. Merge-only; bookmarks and settings are not included. |
| Backup / data-extraction rules | missing | Manifest `allowBackup="true"` with no `fullBackupContent` or `dataExtractionRules`; `res/xml/` has only `file_paths.xml` and `network_security_config.xml`. |
| Cache hygiene | partial | `DocHistory.cleanupStale` only. `cacheDir/tmp` is never swept; clearing is manual through the Storage screen. |
| Full-text index (SQLite) | works; prune on run | `core/search/FullTextIndex.kt`, `IndexWorker.kt` |
| Wi-Fi transfer upload | works (temp + rename, unique names, size cap) | `core/files/WifiTransferServer.kt:248-264` |

## Failure-mode matrix and required recovery behaviour

| Failure | Current behaviour (evidence) | Required recovery behaviour |
|---|---|---|
| Crash or process death during the reader save | The working copy is safe: the temp file is written and then renamed (`ReaderState.kt:388-391`). A leftover `.X.saving.pdf` stays forever (DI-31). | Keep the current approach. At startup, sweep `.*.saving.pdf`, `.*.quick.pdf`, `.*.restore.pdf`, `.*.import` and `.*.upload` older than 1 h. |
| Crash during write-back to the original | The original is truncated by `"wt"` and only partly written (DI-01). | Write to a sibling temp document, then verify and rename. Where rename is impossible, keep a journal entry "write-back pending for U" and retry or repair on the next launch. Verify by comparing the length of the written stream with the source and re-reading the tail (`%%EOF`). |
| Low storage | The reader save fails cleanly with an error. Write-back can leave the original corrupted (DI-01). Delete becomes permanent (DI-07). Clearing the Storage "cloud cache" can delete unsynced edits (DI-24). | Check `StatFs` before large writes: you need about the file size free for a local swap. Never fall back to permanent delete. Mark cloud copies as dirty so they cannot be cleared. |
| SAF permission revoked | `writeBack` returns `Failed` and a dialog appears, but only while the reader is composed (DI-12). Sync stores `@permission`. | Persist a per-document flag "original not updated" and show it in recents/reader until it is resolved. |
| Source moved or deleted externally | `writeBack` throws FileNotFound and returns `Failed`. The re-import key `o:<uri>` stays stale (DI-03). HYPOTHESIS on provider behaviour. | Offer "save a copy". Clear the stale mapping. |
| Source changed externally | It is detected only when the size differs. When it is detected, the local working copy is overwritten, even when it holds unsaved-to-origin edits (DI-02, DI-20). | Compare size, mtime and a hash. When both sides changed, keep both and ask (conflict copy). |
| Same document opened twice (two URIs) | Two separate working copies are created, and the last write-back wins (DI-21). | Key identity on provider document id plus size/hash, and warn the user. |
| Process death with pending annotations | They are lost when autosave is off or when the kill happens during ON_PAUSE (DI-09). | Journal the pending annotations and form values to `filesDir/drafts/<docKey>.json` on every change. Offer restore on the next open. |
| DB schema change | All rows are destroyed (DI-14). | Write real migrations, set `exportSchema=true`, and add a MigrationTestHelper test. |
| DataStore corruption | The app crashes at launch on every start (DI-33). | Add a `ReplaceFileCorruptionHandler { emptyPreferences() }` and catch errors in the flow. |
| Sync source folder looks empty (unmounted or glitch) | Every remote file is deleted (DI-26). | Abort the run when more than N files or more than 30 % would be deleted, and ask the user. |

## Findings

### DI-01 Write-back to the user's original is non-atomic ("wt" truncate then stream), with a "w" fallback that may not truncate
- Type: data-integrity
- Status: CONFIRMED (traced in code). Provider-specific "w" behaviour is PROBABLE.
- Severity: Critical      Priority: P0
- Evidence:
  - `core/files/DocumentRepository.kt:94-96` opens `cr.openOutputStream(origin, "wt")`, falls back to `"w"`, then runs `out.use { o -> local.inputStream().use { it.copyTo(o) } }`. Nothing is written to a temp document, nothing is verified, and nothing is fsynced.
  - It is called after every save, autosave, undo restore and in-place tool run: `ui/reader/ReaderState.kt:383, 392, 425, 523, 541`.
  - It is also called from `replaceOriginal` (`DocumentRepository.kt:115`).
- User impact / scenario:
  - The user annotates a 40 MB PDF that lives on an SD card or in a cloud DocumentsProvider and switches apps. ON_PAUSE autosave (`ReaderScreen.kt:92`) starts the write-back. The process is killed, the provider throws, or the SD card fills up halfway through. The user's original is now truncated or corrupt.
  - On providers where `"w"` does not truncate (a known Android 10+ issue with some providers), a shorter new file leaves the old tail behind. The `startxref`/xref table no longer matches, and the PDF is broken.
- Proposed fix:
  1. If the provider supports `DocumentsContract.createDocument` and rename (tree or provider grants), write a sibling `name.pdf.smartdocs-tmp` document, verify its length, then `DocumentsContract.renameDocument` it or use delete+rename.
  2. Otherwise, open with `"rwt"` (never plain `"w"`). After writing, re-open for read and compare length and the last 1 KB with the local file. On mismatch, retry once and then mark the document "original not updated".
  3. Persist a write-back journal (`origin`, `local`, `startedAt`). On the next start, an unfinished entry means "the original may be damaged": rewrite it from the working copy.
- Effort: M     Regression risk: Med     Dependencies: DI-12
- Validation:
  - Instrumented test with a fake DocumentsProvider that throws after N bytes: the original must stay intact or be repaired on the next launch.
  - Test with a provider whose "w" mode does not truncate: the result length must equal the source.

### DI-02 Re-opening a read-only source (Telegram/WhatsApp/e-mail attachment) silently overwrites the working copy and destroys all saved annotations
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Critical      Priority: P0
- Evidence:
  - `core/files/DocumentRepository.kt:58-68`: `val known = origins.getString("o:$uri")`. If `known.exists() && srcSize == known.length()`, the known copy is reused. Otherwise `target = known` and the source is copied into `tmp`, then `tmp.renameTo(target)`.
  - After a save, the working copy differs in size from the source. When write-back failed (read-only grant gives `WriteBack.Failed`, `:103`), the next open of the same URI re-imports the source over the edited working copy.
- User impact / scenario:
  1. The user opens an attachment from Telegram, highlights 30 passages and saves.
  2. Write-back to Telegram's read-only URI fails. The dialog is dismissed or never shown (DI-12).
  3. The next day the user taps the same attachment again. All highlights are gone, including from Recents, because Recents points to the same working copy.
- Proposed fix: In `localFile`, when `known` exists and the source differs:
  1. If `known` has changes the source lacks (a stored hash of the imported bytes ≠ the current local hash), do not overwrite. Either open the local copy and offer "a newer version of the source exists — open it as a separate copy", or import to `uniqueFile` instead.
  2. Store `importedSize`, `importedHash` and `importedMtime` next to the origin mapping.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Unit/instrumented test. Import a URI, modify the local file, make the source read-only, then call `localFile(uri)` again: the local bytes must be unchanged.

### DI-03 Stale `o:<uri>` origin mapping after rename/move/delete makes a later re-open overwrite an unrelated file
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P0
- Evidence:
  - `DocumentRepository.kt:47-49` stores two mappings, `l:<path>` → uri and `o:<uri>` → path, and never removes them.
  - The Files rename/move (`ui/files/FilesScreen.kt:317, 337`) and delete (`:326`) do not touch `origins`.
  - `localFile` then reuses `known` when it exists, or writes into it (`:60, :64`).
- User impact / scenario:
  1. A.pdf is imported from URI U into `Documents/report.pdf`.
  2. The user renames it to `old.pdf`.
  3. The user imports another document whose name is `report.pdf`, so it gets `Documents/report.pdf`.
  4. The user opens U again: `known = Documents/report.pdf`, which is the other document. If the sizes match, the wrong document opens. If they differ, A's bytes overwrite B.
  5. A rename also breaks write-back for the renamed file, because the `l:` key is lost.
- Proposed fix:
  - Move origins into Room (`doc_origin(localPath PK, originUri, importedHash)`).
  - Update or delete it in a single repository API used by rename/move/delete/trash: `DocumentRepository.rename(f, newName)` and `.delete(f)`.
  - In `localFile`, verify `known`'s recorded `l:` key still maps back to the same `uri` before reusing it.
- Effort: M     Regression risk: Med     Dependencies: DI-22
- Validation: Instrumented test of the sequence above. B must remain byte-identical.

### DI-04 Files → Rename silently overwrites an existing file with the target name
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P0
- Evidence: `ui/files/FilesScreen.kt:317` runs `f.renameTo(File(f.parentFile, "${name.trim()}.${f.extension}"))` with no `exists()` check. On Android (POSIX `rename(2)`), `File.renameTo` replaces an existing destination file. The result is ignored, and there is no error message.
- User impact / scenario: In a folder with `contract.pdf` and `draft.pdf`, the user renames `draft` to `contract`. The original `contract.pdf` is destroyed without any warning and without going to the trash.
- Proposed fix: If the target exists (case-insensitive check as well), show "name already exists" or use `FileCopy.freeName`. Reject `/` and `..` in the name. Report the boolean result.
- Effort: S     Regression risk: Low     Dependencies: DI-22 (update DB/origins on rename)
- Validation: Robolectric/unit test on the rename helper: renaming onto an existing file returns an error and both files keep their content.

### DI-05 Annotations whose write fails are silently dropped and then cleared from pending
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence:
  - `core/pdf/Annotations.kt:78-84` wraps each annotation in `list.forEach { a -> runCatching { write(context, d, page, a); ... } }` and discards failures.
  - Then `d.save(out)`, and `ui/reader/ReaderState.kt:395` runs `annots.clear(); dirty = false`.
- User impact / scenario: A single annotation that throws (a bad image bitmap, a font problem with an Arabic free-text, a malformed path) vanishes on save. The save is reported as successful.
- Proposed fix: Collect the failures. Return `CommitResult(written, failed)`. Keep the failed ones in `annots` (still dirty) and show "N annotations could not be saved".
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Unit test with one annotation that forces an exception: commit reports 1 failed, and the reader keeps it pending.

### DI-06 Deleting in Files falls back to a permanent delete when the move to trash fails (low storage / cross-filesystem)
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence:
  - `ui/files/FilesScreen.kt:326` runs `if (!Trash5.moveToTrash(ctx, it)) it.deleteRecursively()`.
  - The trash is in `filesDir/trash` (`core/share5/StorageManager.kt:36`), while documents are in `getExternalFilesDir()/Documents` (`DocumentRepository.kt:27`). These are different filesystems, so `renameTo` fails and `copyRecursively` needs free space (`core/view5/Trash.kt:27`).
- User impact / scenario: On a nearly full phone, "delete" (which the UI implies is recoverable) permanently erases the documents or folder.
- Proposed fix:
  - Put the trash on the same volume as `docsDir`, for example `docsDir/../.trash`, so the move is a cheap rename.
  - If the move fails, show "cannot move to trash — delete permanently?" instead of deleting silently.
- Effort: S     Regression risk: Low     Dependencies: StorageManager trash path
- Validation: Unit test with a Trash whose dir is unwritable: the file must still exist after delete.

### DI-07 Editing a stored annotation removes it from the file immediately; a crash or kill before save loses it
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence:
  - `ui/reader/AnnotEdit.kt:135-143`: backup `.X.annotedit.pdf`, then `AnnotationWriter.delete(f, …, tmp)` and `s.swapFile(tmp, record=false)`. The annotation now exists only in memory (`adoptConverted`).
  - The backup is restored only on a user "discard" in `close()` (`ReaderState.kt:243-253`, `AnnotEdit.kt:206-209`). Nothing restores it on the next open after a crash.
- User impact / scenario:
  1. The user taps an existing comment to move it.
  2. The app crashes, or the OS kills it in the background with autosave off.
  3. For an app-created document (no origin), the comment is gone forever.
  4. For an imported one, DI-02's re-import may accidentally restore it while discarding other edits.
  5. The orphan `.X.annotedit.pdf` stays and shows in Files and search (DI-31).
- Proposed fix: On `ReaderState.open`, if `.X.annotedit.pdf` exists next to the file, offer "restore unsaved annotation edit" and restore or delete it. Alternatively, do not modify the file until save (render the stored annotation hidden instead).
- Effort: M     Regression risk: Med     Dependencies: DI-09
- Validation: Instrumented test: convert a stored annotation, kill the process, reopen. The annotation must still be present, or a restore must be offered.

### DI-08 Room schema v1 with `exportSchema=false` and `fallbackToDestructiveMigration()` — the first schema change wipes user data
- Type: arch-risk
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence: `core/data/AppDatabase.kt:154-155` (`version = 1, exportSchema = false`) and `:168` (`.fallbackToDestructiveMigration()`).
- User impact / scenario: The next release that adds a column (for example origin, hash or lastZoom) silently deletes recents, favourites, tags, reading bookmarks, the fill profile (personal data the user typed), and the custom stamp and signature lists. The signature PNGs stay on disk as orphans.
- Proposed fix:
  - Set `exportSchema = true` with the `room.schemaLocation` KSP arg and commit `schemas/`.
  - Remove the destructive fallback, or use `fallbackToDestructiveMigrationFrom()` only for explicitly abandoned versions.
  - Add `MigrationTestHelper` tests.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Migration test from v1 to v2 that keeps the rows.

### DI-09 Pending annotations and form values live only in memory; process death loses them
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P1
- Evidence:
  - `ui/reader/ReaderState.kt:122` (`val annots = mutableStateListOf()`).
  - Saving happens only on explicit save, back, or ON_PAUSE when `settings.autoSave` is on (`ReaderScreen.kt:92`).
  - `saveInBackground` runs in an unbound `ioScope` (`ReaderState.kt:412`), but nothing keeps the process alive. There is no foreground service and no WorkManager job, so a low-memory kill right after pause loses the save.
  - Nothing is written to disk until the save completes.
- User impact / scenario: The user fills a 3-page form, receives a call, and the OS kills the app. With autosave off, or with a kill during the background save, all input is lost. `RestoreLastSession` reopens the document empty.
- Proposed fix: Journal `annots` and `forms5` pending values (JSON, bitmaps as PNG files) to `filesDir/drafts/<hash(localPath)>/` debounced at about 1 s. On open, if a draft exists, apply it (and mark dirty). Delete the draft after a successful save.
- Effort: M     Regression risk: Med     Dependencies: none
- Validation: Instrumented test: add annotations, `am kill`, relaunch. The annotations are restored as pending.

### DI-10 Navigating away from the reader (Settings, AI, e-sign, file links, view5 commands) with autosave off discards pending edits without asking
- Type: data-integrity
- Status: PROBABLE (needs device test for lifecycle order)
- Severity: Medium      Priority: P1
- Evidence:
  - Only Back and the top-bar arrow check `dirty` (`ReaderScreen.kt:117-128, 214`) and `navigateTool` uses `saveThen` (`:193`).
  - These navigations go straight to `nav.navigate(...)` without a dirty check: `:240` Settings, `:242` e-sign, `:243` connected, `:286` file links, `:292` XFA, `:302/309` AI, `:336`, `:341`, `:344`, and `ui/view5/Commands5.kt:91-94`.
  - When the entry leaves composition, `DisposableEffect(uri) { onDispose { state.close() } }` (`ReaderState.kt:656`) drops the in-memory `annots`. With autosave on, the entry's ON_PAUSE saves first.
- User impact / scenario: With autosave turned off, the user annotates and opens Settings from the menu. On return, all annotations are gone.
- Proposed fix: Route every outgoing navigation through one `guardedNavigate { }` that applies the same rule as Back: autosave, or the discard dialog.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Compose UI test: autosave off, add an annotation, open Settings → a dialog is shown.

### DI-11 Undo history is destroyed whenever the reader leaves composition, which breaks the documented "full-screen tool = one undo step"
- Type: bug
- Status: PROBABLE (Navigation-Compose disposes non-visible destinations; needs device test)
- Severity: Medium      Priority: P2
- Evidence:
  - `ui/reader/ReaderScreen.kt:188`: `state.beforeExternalTool(label); nav.navigate(Routes.tool(...))`.
  - `ReaderState` is created with plain `remember(uri)` (`ReaderState.kt:654`), and `onDispose { state.close() }` runs `history.dispose()`, which deletes the history folder including the `externalBase` snapshot (`ReaderState.kt:252`, `DocHistory.kt:165-170`).
  - On return, a new `ReaderState` with an empty history is created, so `reloadIfChanged` has no `externalBase` to push (`:560-570`).
  - Consequence: every trip to Organize, AI or Settings erases all undo steps.
- User impact / scenario: The user deletes pages in Organize, returns, and taps Undo. Nothing happens, and earlier undo steps are gone too.
- Proposed fix: Hoist `ReaderState`/`DocHistory` to the reader `NavBackStackEntry`'s ViewModel (or a process-level registry keyed by file) so it survives forward navigation. Dispose it only when the entry is popped (`onCleared`).
- Effort: M     Regression risk: Med     Dependencies: none
- Validation: UI test: apply rotate, open Organize, back, then Undo restores the version before the tool.

### DI-12 Write-back failures after leaving the reader (or after a background autosave) are never shown; the user believes the original is updated
- Type: data-integrity
- Status: PROBABLE
- Severity: Medium      Priority: P1
- Evidence:
  - `writeBackResult` is a Compose state consumed only by `LaunchedEffect(state.writeBackResult)` inside `ReaderScreen` (`ReaderScreen.kt:98-102`).
  - `saveThen { leave() }` (`:116, :128`) pops the screen right after `save()` returns.
  - ON_PAUSE saves complete while the screen is not visible.
  - There is no persistent "original out of date" marker.
- User impact / scenario: The user presses back, write-back fails because of a read-only or revoked grant, and nothing is shown. The user then shares the original file from the file manager, without the edits.
- Proposed fix: Store failures in a small table (`pending_writeback(localPath, origin, reason, at)`). Show a badge in Recents and a banner when the reader opens, and offer "save copy" or "pick original". Clear the entry on success.
- Effort: S     Regression risk: Low     Dependencies: DI-01
- Validation: Instrumented test with a read-only source: after back, Home shows the "not saved to original" badge.

### DI-13 "Pick the original again" accepts any file and overwrites it
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P1
- Evidence: `ui/common/WriteBackUi.kt:50-56`. `OpenDocument` returns a uri, the app calls `setOrigin(f, uri)` and `writeBack(f)` with no check of name, size or content.
- User impact / scenario: In the picker, the user taps a similarly named file (`report (1).pdf`, or last year's version). That file is irreversibly overwritten.
- Proposed fix:
  - Compare the display name and, when available, the size or the hash of the original import with the picked document.
  - If they differ, confirm with "Replace '<name>' with your edited version?" and offer "save as copy" instead.
- Effort: S     Regression risk: Low     Dependencies: DI-02 (stored import hash)
- Validation: UI test: pick a different file and a confirmation is shown.

### DI-14 No backup or data-extraction rules: tokens, API key, licence state, signatures, personal profile and document text are all backed up and transferred
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence:
  - `app/src/main/AndroidManifest.xml:25` sets `android:allowBackup="true"` with no `android:fullBackupContent` or `android:dataExtractionRules`, and there is no such file in `app/src/main/res/xml/`. The default Auto Backup and Android 12+ device-to-device transfer therefore include every SharedPreferences file, the DataStore, the databases, `filesDir` and `getExternalFilesDir`.
  - What is backed up:
    - OAuth refresh tokens in plaintext: `core/cloud/OAuthPkce.kt:51-55` (`cloud_<provider>` access/refresh).
    - The AI API key in plaintext: `core/data/SettingsStore.kt:75`.
    - Licence and purchase tokens: `core/billing/LicenseClient.kt:109`, `Pro.kt:95-97`.
    - Signature images: `filesDir/signatures`.
    - The fill profile (name, ID numbers, address): the Room `fill_profile` table.
    - The full-text index holding the text of every document (the `FullTextIndex` DB).
    - The trash contents.
    - `connected_keys` ciphertext. Its Keystore key does not migrate, so those entries are useless after a restore.
- User impact / scenario:
  - Secrets and personal data leave the device in backups and via D2D.
  - Restored tokens can be reused on a new device.
  - Restored `pro_entitlement` or `license_state` may grant stale entitlements.
- Proposed fix:
  - Add `data_extraction_rules.xml` (API 31+) and `backup_rules.xml` (≤30).
  - Exclude `sharedpref/cloud_*.xml`, `oauth_pending`, `license_state`, `pro_entitlement`, `connected_keys`, `esign_store`, `collab*`, `datastore/settings.preferences_pb` (or move the AI key out of it), `database/<fulltext db>`, `file/trash/`, `external/Documents/Cloud/` and `file/tessdata/`.
  - Include `database/smartdocs.db` and the signatures and stamps.
- Effort: S     Regression risk: Low     Dependencies: DI-15, DI-16
- Validation: `adb shell bmgr backupnow` plus `adb backup` / a `bu` inspection showing the excluded files are absent (needs device test).

### DI-15 Secrets stored in plaintext (AI key, OAuth access/refresh tokens, purchase token)
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `core/data/SettingsStore.kt:75, 141` writes `p[K.AI_KEY] = n.aiApiKey`. `core/cloud/OAuthPkce.kt:52-55` stores `putString("access"/"refresh")` in plain prefs. The project already contains a Keystore AES-GCM helper pattern (`core/connected/ConnStore.kt:63-80`), but it is not used for these.
- User impact / scenario: Rooted devices, backups (DI-14) or debug builds expose the user's paid OpenAI key and long-lived cloud refresh tokens.
- Proposed fix: Reuse the `ConnKeyCache` Keystore pattern in a `SecretStore` for the AI key, the OAuth tokens and the purchase token. Migrate once on first read.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Unit test of `SecretStore` round-trip. Inspection shows no token strings in `shared_prefs/*.xml`.

### DI-16 All user documents live in app-specific storage and are deleted on uninstall or "Clear storage"
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence:
  - `core/files/DocumentRepository.kt:27`: `docsDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Documents")`. Scans (`:28`), tool results, imports, Wi-Fi uploads and cloud copies all go there.
  - `StorageManager.clearAllData` (`core/share5/StorageManager.kt:99`) wipes it too.
  - Auto Backup of that volume is subject to the 25 MB quota (see DI-17).
- User impact / scenario: A user who scanned 200 pages, or who created merged and signed documents in the app, reinstalls or switches phones. Every document that was never exported is gone.
- Proposed fix:
  - Offer a default "SmartDocs" folder in shared storage: `MediaStore.Downloads/Documents` with `RELATIVE_PATH=Documents/SmartDocs`, or a user-chosen SAF tree.
  - Save new results there, or at minimum warn on first scan or creation and offer "export all".
  - Show a warning dialog before uninstall-like actions (`clearAllData`).
- Effort: L     Regression risk: Med     Dependencies: DI-14
- Validation: Device test: create a scan, uninstall and reinstall, and the scan is still in Documents/SmartDocs.

### DI-17 Auto Backup will almost certainly exceed the 25 MB quota, so nothing is backed up (silent)
- Type: data-integrity
- Status: PROBABLE (needs device test)
- Severity: Medium      Priority: P2
- Evidence:
  - The default backup set includes `getExternalFilesDir()/Documents` (all working copies), `filesDir/tessdata` (OCR language packs, `StorageManager.kt:60`), `filesDir/trash` and the full-text DB.
  - When the total exceeds 25 MB, Android skips the cloud backup entirely. The `fallbackToDestructiveMigration` DB and the settings are then not backed up either.
- User impact / scenario: The user expects favourites, tags, signatures and settings to come back on a new phone. Nothing is restored.
- Proposed fix: Keep the backup set small with DI-14's rules: exclude Documents, tessdata, trash, caches and index. Recommend `AppDataSync` (Drive appData) for documents' metadata.
- Effort: S     Regression risk: Low     Dependencies: DI-14
- Validation: `adb shell dumpsys backup` shows a successful backup of less than 25 MB.

### DI-18 Persisted URI permissions are taken for every opened document and never released (128/512 cap)
- Type: data-integrity
- Status: CONFIRMED (code); eviction behaviour needs device test
- Severity: Medium      Priority: P2
- Evidence:
  - `takePersistableUriPermission` is called in `MainActivity.kt:114`, `ui/common/Components.kt:269-270` (every picker pick), `ui/files/FilesScreen.kt:128`, `ui/common/WriteBackUi.kt:53,62`, `ui/office/OfficeRoutes.kt:145`, `ui/cloud/SyncScreen.kt:74`.
  - There is no `releasePersistableUriPermission` anywhere (grep finds 0 hits).
  - The platform keeps at most 128 grants per app before API 30 and 512 from API 30, and drops the oldest.
- User impact / scenario: A heavy user opens more than 128 PDFs on Android 10. The write-back grant of an older document, or even the sync tree grant, is evicted. Saves start failing ("pick the original"), and sync reports a lost permission.
- Proposed fix:
  - Persist grants only for documents whose origin mapping is kept.
  - Release grants when a recent entry is removed, when the origin is replaced, or LRU beyond about 100.
  - Never let document grants evict the sync tree grants: count `contentResolver.persistedUriPermissions` and prune document grants first.
- Effort: S     Regression risk: Low     Dependencies: DI-03 (origins in Room)
- Validation: Instrumented test that opens 150 picked docs. The sync tree permission is still present, and the grant count stays at 110 or fewer.

### DI-19 ACTION_VIEW imports keep a read-only (usually non-persistable) grant, so every later save fails write-back
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence:
  - `MainActivity.kt:114` calls `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` inside `runCatching`. Most senders grant only a temporary read, and some file managers also grant write.
  - `localFile` records the origin anyway (`DocumentRepository.kt:69`), so each save calls `writeBack` against a URI that is unreachable after the activity instance dies. The result is `Failed`, and the dialog shows on every save.
- User impact / scenario: Documents opened from other apps nag the user with a "could not save to the original" dialog after each autosave.
- Proposed fix:
  - When the intent lacks `FLAG_GRANT_WRITE_URI_PERMISSION`, do not record an origin for write-back. Treat the copy as the user's own document, and offer "save a copy to…" once.
  - When write is granted, try `takePersistableUriPermission(READ|WRITE)`.
- Effort: S     Regression risk: Low     Dependencies: DI-02
- Validation: Device test: open from Telegram, annotate, save twice. At most one prompt appears, or none.

### DI-20 External changes are detected by size only: same-size edits keep a stale working copy, and the next save overwrites the external change
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P1
- Evidence: `DocumentRepository.kt:60`: `if (known != null && known.exists() && (srcSize < 0 || srcSize == known.length())) return known`. `querySize` returning -1 (a provider without SIZE) also counts as "unchanged".
- User impact / scenario: A colleague edits the shared PDF on a cloud drive (for example by toggling form fields, which often keeps the byte length). The user opens it in SmartDocs, sees the old version, saves, and write-back overwrites the colleague's change.
- Proposed fix: Store `importedSize`, `importedMtime` (`DocumentsContract.Document.COLUMN_LAST_MODIFIED`) and a cheap hash (first and last 64 KB plus size, as in `core/ai5/DocIndexer.kt:22`). Compare all three. On a both-sides change, create a conflict copy.
- Effort: S     Regression risk: Low     Dependencies: DI-02
- Validation: Unit test with the same size and a different mtime/hash: re-import (or conflict) happens.

### DI-21 Document identity is the URI string: the same file through two URIs gives two divergent working copies, and the last write-back wins
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence:
  - Origins are keyed `o:<uri>` (`DocumentRepository.kt:48, 58`), and Recents, tags and bookmarks are keyed by `Uri.fromFile(workingCopy)` (`:128`, `AppDatabase.kt:9, 22`).
  - Opening `/Download/x.pdf` through the Downloads provider, MediaStore (`scanDevicePdfs`, `:154`) and ExternalStorageProvider produces three URIs. That gives `x.pdf`, `x (2).pdf` and `x (3).pdf` (via `uniqueFile`, `:64`), each with its own annotations, all writing back to the same file.
- User impact / scenario: The user annotates via "Device" tab yesterday and via the system picker today. Each session overwrites the other's edits in the original. Recents shows confusing duplicates.
- Proposed fix: Normalise identity:
  - Resolve `DocumentsContract.getDocumentId` plus authority, or MediaStore `_ID`, into a canonical key.
  - Also match on (display name, size, hash). If a match exists, reuse that working copy.
- Effort: M     Regression risk: Med     Dependencies: DI-03
- Validation: Instrumented test: open the same file through MediaStore and through SAF, and one working copy results.

### DI-22 Rename, move and delete in Files do not update recents, tags, bookmarks, origins, cloud links, tabs or the search index
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P1
- Evidence:
  - `ui/files/FilesScreen.kt:317` (rename), `:333-339` (move) and `:326` (delete) only touch the filesystem.
  - These are all keyed by the absolute path or `file://` URI: Room `recent_docs.uri`, `doc_tags.uri`, `reading_bookmarks.uri` (`AppDatabase.kt:9, 22, 33-38`), `origins` (`DocumentRepository.kt:42`), `cloud_links` keyed by `local.absolutePath` (`core/cloud/CloudLinks.kt:26`), `ReaderTabs` (`ui/reader/view/ReaderTabs.kt:76-80`) and the FullTextIndex `path`.
- User impact / scenario:
  - After a rename, the document loses its tags, favourite, bookmarks and last page.
  - Saves no longer go back to the original or to the cloud, because the link is gone.
  - Recents keeps a dead entry.
  - The index returns hits for a missing path until the next prune.
- Proposed fix: Add `DocumentRepository.rename/move/delete(file)` that, in one Room transaction, rewrites `uri` in `recent_docs`, `doc_tags` and `reading_bookmarks`, and then migrates `origins`, `CloudLinks`, `ReaderTabs` and the index. Use it from Files, Trash restore and the tools.
- Effort: M     Regression risk: Med     Dependencies: DI-03
- Validation: Instrumented test: tag, favourite and bookmark a doc, rename it, and all metadata follows.

### DI-23 Re-opening a cloud document downloads over a local working copy whose upload failed
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence:
  - `core/cloud/CloudLinks.kt:44-56`: `val existing = … id == item.id`, `dest = existing ?: uniqueIn(dir, safe)`, `provider.download(item, dest, onProgress)`. The download is unconditional, with no check whether `dest` has local changes that were not uploaded.
  - Upload failures only emit an event (`SaveHooks`, `:107-110`).
- User impact / scenario: The user edits a Drive PDF offline. Save works locally, and the upload fails. Later the user taps the file in the Cloud browser, and the edits are replaced by the cloud version.
- Proposed fix:
  - Store `uploadedHash` and `dirty` in the link.
  - On open, if the local copy is dirty, either upload first or open the local copy and show a "local changes not uploaded" banner.
  - If the cloud also changed, create a conflict copy.
- Effort: S     Regression risk: Low     Dependencies: DI-24
- Validation: Unit test of the decision with a fake provider: dirty local plus re-open leaves the local bytes intact.

### DI-24 Failed cloud uploads are not retried or persisted, and "Clear cloud cache" deletes unsynced edits
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence:
  - `SaveHooks.onSaved` (`core/cloud/CloudLinks.kt:102-118`) does a one-shot upload in an in-process `CoroutineScope`. Failure goes to `_events.tryEmit` (a SharedFlow with no replay), whose only collector is `ui/cloud/CloudBrowserScreen.kt:82`, so a failure while in the reader is dropped.
  - There is no WorkManager retry.
  - `core/share5/StorageManager.kt:58, 77` marks `Documents/Cloud/<provider>` as `clearable=true` and deletes it.
- User impact / scenario: The user saves while offline and later frees storage through Storage → Cloud. The edits are permanently gone, and the user was never told the upload failed.
- Proposed fix:
  - Persist dirty cloud links and enqueue a unique `OneTimeWorkRequest("cloud_up_<id>")` with a network constraint and backoff (idempotent: compare hashes before upload).
  - Exclude dirty files from "clearable" and show their count.
- Effort: M     Regression risk: Med     Dependencies: DI-23
- Validation: Test: an offline save results in queued work, connectivity triggers an upload, and the Storage bucket excludes the dirty file.

### DI-25 Folder sync has no mass-deletion guard, and local deletes are permanent
- Type: data-integrity
- Status: CONFIRMED (no guard). Trigger scenario is HYPOTHESIS.
- Severity: Medium      Priority: P1
- Evidence:
  - `core/sync/SyncRules.kt:43-44`: present on one side only and unchanged since the snapshot means delete on the other side.
  - `SyncEngine.kt:264-265` performs `l.delete()` (a permanent SAF delete) or `provider.delete(r)` (Drive trashes, `GoogleDrive.kt:187`; other providers not checked).
  - There is no threshold or confirmation.
  - The local listing comes from `DocumentFile.listFiles()` (`:217`). If an SD card or USB tree is unmounted but still "canRead", or a provider returns an empty list, every snapshot entry becomes `DELETE_REMOTE`.
- User impact / scenario: An SD card is ejected while periodic sync runs, and the whole cloud folder is deleted (trashed on Drive; possibly permanent on others). In the other direction, a cloud glitch permanently deletes local files.
- Proposed fix:
  - Before executing deletes, count them. If more than 20 files or more than 30 % of the snapshot would be deleted, abort the pass with status "needs confirmation".
  - Move local deletes into the app trash, copying first, instead of `DocumentFile.delete()`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Unit test in `SyncRulesTest`/engine with an empty local list and a 50-entry snapshot: zero deletes are executed.

### DI-26 Sync download writes into the user's local file in place ("wt", falling back to "w")
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `core/sync/SyncEngine.kt:296`: `(runCatching { cr.openOutputStream(target.uri, "wt") }.getOrNull() ?: cr.openOutputStream(target.uri, "w"))?.use { … copyTo }`. The download into the temp file is safe; the final copy over the local document is not.
- User impact / scenario: A crash or full storage during the copy leaves the local file truncated. The next sync then sees the local side as changed (different size) and uploads the truncated file over the good cloud copy as an `UPLOAD`.
- Proposed fix: Create the new content as a sibling `.name.part` document in the tree (`createFile`), verify its length, then delete the original and `renameTo(name)` (`DocumentsContract.renameDocument`). Keep a queue state of "replacing" for recovery.
- Effort: M     Regression risk: Med     Dependencies: DI-01 helper
- Validation: Fake-provider test with an exception mid-copy: the original is intact, and the next run does not upload a truncated file.

### DI-27 Saving annotations rewrites the whole PDF and invalidates existing digital signatures without warning
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence:
  - `core/pdf/Annotations.kt:73-87` runs `PdfOps.load(src)` and then a full `d.save(out)`; `saveIncremental` is used only in `core/pdf/DigitalSignature.kt:305` and `core/sign/LtvSupport.kt`.
  - No reader path checks `signatureDictionaries` before saving (grep in `ui/reader`, `core/annot`: 0 hits).
- User impact / scenario: The user highlights a line in a signed contract and autosave writes it back to the original. The signature now shows as broken or invalid for every recipient, and the file has been overwritten.
- Proposed fix: On open, detect signatures (`PDDocument.signatureDictionaries`). If any exist:
  - Save annotations with `saveIncremental` (PdfBox supports adding annotations incrementally with `COSUpdateInfo.setNeedToBeUpdated`), or
  - Warn and force "save as copy". Never write back to the original automatically.
- Effort: M     Regression risk: Med     Dependencies: none
- Validation: Test with a signed sample: after adding a highlight, `CertInspector` still validates the original signature, or the reader blocks the overwrite.

### DI-28 Saving a protected PDF changes its security: the owner password is replaced by the user password or "", and encryption is set to AES-128
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P1
- Evidence:
  - `core/pdf/PdfOps.kt:42-50`: `if (doc.isEncrypted) { val pw = password.orEmpty(); doc.protect(StandardProtectionPolicy(pw, pw, ap).apply { encryptionKeyLength = 128 … }) }`.
  - It is used by every save (`Annotations.kt:73`, `E5Io.load`, `EditIo.load`).
- User impact / scenario:
  - A permissions-only PDF (no open password, owner password restricting printing or editing) is saved with an owner password of "". Any reader can now unlock full permissions.
  - An AES-256 file is downgraded to AES-128.
  - A file opened with the user password gets owner = user, so anyone who can open it can change its permissions.
  - Write-back pushes this weakened file over the original.
- Proposed fix:
  - Preserve the original encryption: keep the existing `SecurityHandler`/encryption dictionary and the key length (`doc.encryption.length`). PdfBox can re-save with the original handler when it is opened with the owner password.
  - Otherwise, for owner-restricted files without the owner password, refuse in-place save ("save as unprotected copy").
- Effort: M     Regression risk: Med     Dependencies: none
- Validation: Unit test: a permission-restricted AES-256 sample saved with an annotation still has a non-empty owner password, the same permissions and the same key length.

### DI-29 "Save a copy" in the write-back dialog crashes on IO error and reports success when no stream was opened
- Type: bug
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P1
- Evidence:
  - `ui/common/WriteBackUi.kt:58-65`: `scope.launch { app.documents.exportTo(f, uri); … Toast(wb_saved_original) }` has no try/catch, so an IOException or SecurityException is uncaught in `rememberCoroutineScope` and crashes the app.
  - `DocumentRepository.kt:179`: `openOutputStream(target,"wt")?.use{}` returns silently on null, and the code still shows the toast and re-points the origin (`setOrigin`) to a possibly empty document.
- User impact / scenario: The user chooses "save a copy" to a full SD card and the app crashes. With a provider that returns null, a 0-byte "copy" becomes the new "original".
- Proposed fix: Make `exportTo` throw when the stream is null and verify the written length. Wrap the call in `runCatching`, show the error, and only call `setOrigin` on success.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Unit test of `exportTo` with a null stream, which throws. UI test where an IO error shows an error and does not crash.

### DI-30 Tool result "Save as" is fire-and-forget: errors are swallowed and a 0-byte file is left
- Type: bug
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence:
  - `ui/common/Components.kt:201-203`: `CoroutineScope(Dispatchers.IO).launch { runCatching { app.documents.exportTo(file, uri) } }`. There is no feedback and the scope is unbounded.
  - `CreateDocument` has already created the empty target.
  - The same pattern appears in `ui/voice/PodcastScreen.kt:124`.
  - The reader save-as (`ReaderScreen.kt:136`) shows "success" when the stream is null.
- User impact / scenario: The user saves a merged result to Downloads. It fails because of quota or a provider error. No message appears, and an empty `merged.pdf` sits in Downloads.
- Proposed fix: Use one `exportToUser(file, uri)` helper that verifies the length, deletes the created document on failure (`DocumentsContract.deleteDocument`), and reports success or failure through a snackbar.
- Effort: S     Regression risk: Low     Dependencies: DI-29
- Validation: UI test with a failing provider: an error snackbar appears and the target is deleted.

### DI-31 Hidden temp and backup files are never cleaned up, and they show in Files, Device scan and search
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P2
- Evidence:
  - These temp/backup files are created in `docsDir`: `.X.saving.pdf` (`ReaderState.kt:388`), `.X.quick.pdf` (`:510`), `.X.restore.pdf` (`:536`), `.X.annotedit.pdf` (`AnnotEdit.kt:136`), `.X.import` (`DocumentRepository.kt:65`) and `.X.upload` (`WifiTransferServer.kt:260`).
  - The `save()` failure path does not delete `tmp` (`ReaderState.kt:398`).
  - `listFolder` (`DocumentRepository.kt:167`) and `scanDevicePdfs` (`:161`) filter by extension only, so hidden `*.pdf` leftovers are listed. The indexer walks `docsDir` too (`core/search/IndexWorker.kt:33`).
- User impact / scenario: After a crash, the user sees `.contract.saving.pdf` or `.contract.annotedit.pdf` in Files and search results. Opening and editing it creates further confusion. Space leaks.
- Proposed fix:
  - Filter `name.startsWith(".")` in `listFolder`, `scanDevicePdfs` and the indexer.
  - On app start, delete dot-temp files older than 1 h, except `.annotedit.pdf`, which is handled by the DI-07 recovery.
  - Delete `tmp` in the `save()` catch block.
- Effort: S     Regression risk: Low     Dependencies: DI-07
- Validation: Unit test of `listFolder` with dotfiles. Instrumented test of the startup sweep.

### DI-32 `cacheDir/tmp` is never swept, so decrypted copies of protected PDFs can persist after a crash
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence:
  - `LockedPdf.decryptToTemp` writes `.unlocked_<ts>_<name>` with security removed (`core/pdf/LockedPdf.kt:30-37`). It is deleted only in `finally` (`:52-54`), and `OrganizeState.kt:179` must delete it manually.
  - `DigitalSignature` writes `sig_plain_*.pdf` into `cacheDir` (`:253, 279-281`).
  - The share/print temp (`ReaderScreen.kt:166`, `newTemp("share")`) and the save-as temp are deleted only on success (`:136`).
  - There is no startup sweep (`SmartDocsApp.kt`). The only cleaning is manual in Storage (`StorageManager.kt:75`) or by OS eviction.
  - 82 `newTemp`/`tempDir` call sites exist.
- User impact / scenario: After a crash during a merge of password-protected files, plaintext copies remain in the cache indefinitely. Cache size also grows without bound on low-end devices.
- Proposed fix: In `SmartDocsApp.onCreate` (on a background thread), delete `tempDir` entries older than 24 h, and always delete `.unlocked_*` and `sig_plain_*`, skipping the live history folders already handled by `DocHistory.cleanupStale`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Instrumented test: create old temp files, start the app, and they are gone.

### DI-33 DataStore has no corruption handler and is collected without catch, so a corrupt file crash-loops the app at launch
- Type: bug
- Status: PROBABLE
- Severity: Medium      Priority: P2
- Evidence:
  - `core/data/SettingsStore.kt:11` declares `preferencesDataStore("settings")` without `corruptionHandler`.
  - `MainActivity.kt:44` runs `app.settings.settings.collectAsStateWithLifecycle(...)`, and the `settings` flow (`SettingsStore.kt:86`) has no `.catch`.
  - `settings.update` is called from UI `scope.launch` without try/catch in many places. On ENOSPC, the IOException goes uncaught.
- User impact / scenario: A power loss during a settings write corrupts `settings.preferences_pb`, and the app crashes on every start until the user clears data, which wipes the documents too (DI-16).
- Proposed fix: Add `corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }`, add `.catch { if (it is IOException) emit(emptyPreferences()) else throw it }` on `data`, and wrap `update` in `runCatching` with a user message.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Unit test that writes garbage into the preferences file: the flow emits defaults.

### DI-34 App-data bundle sync is merge-only: deletions come back, bookmarks and settings are missing, documents are matched by name and size
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence:
  - `core/share5/AppDataBundle.kt:25`: "Import only MERGES (nothing local is deleted)". Signatures are deduplicated by sha (`:89-95`). Delete a signature on phone A, and phone B's bundle re-imports it on the next `AppDataSync` (`AppDataSync.kt` imports newer bundles of other devices).
  - Not exported: `reading_bookmarks`, `AppSettings` other than author, recents and last page.
  - `docTags` and favourites are matched by `name == && size ==` against recents only (`:123-136`), so a file edited on the other device (new size) loses its tags.
  - Signature PNGs are uploaded to Drive appData unencrypted.
- User impact / scenario: A user deletes an old signature for security, and it reappears from another device's backup. Moving to a new device loses all reading bookmarks.
- Proposed fix: Add tombstones (`deleted: [sha…]` with a timestamp) and last-writer-wins per item. Include bookmarks (keyed by document hash) and settings. Match documents by content hash. Optionally encrypt the bundle with a user passphrase.
- Effort: M     Regression risk: Med     Dependencies: DI-21 (document hash)
- Validation: Two-device simulation unit test: a delete on A followed by sync on both leaves the signature absent on both.

### DI-35 Recents table grows without bound; tags and favourites are orphaned or lost by "remove record"; there are no foreign keys
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence:
  - `AppDatabase.kt:55`: `LIMIT 50` applies only in the query, and rows are never pruned.
  - `doc_tags` has no `@ForeignKey` (`:22-23`), and `TagDao.delete` (`:88`) leaves `doc_tags` rows behind.
  - "Remove record" (`ui/home/HomeComponents.kt:126`) runs `docs().remove(uri)`, which also drops the favourite flag (it lives in the same row) and leaves tag links.
  - `thumbPath` points into `cacheDir/thumbs` and can disappear.
- User impact / scenario: The user removes a document from Recents and silently loses its favourite status. The DB accumulates dead rows.
- Proposed fix: Split favourites into their own table, or make "remove from recents" set `hidden=1`. Add FKs with `onDelete=CASCADE` (in a migration, see DI-08). Prune non-favourite recents beyond 500 or whose file is missing.
- Effort: S     Regression risk: Low     Dependencies: DI-08
- Validation: DAO test: delete a tag and `doc_tags` becomes empty.

### DI-36 Pending-annotation save clears every annotation, including any added after the snapshot was taken
- Type: data-integrity
- Status: HYPOTHESIS (a modal progress dialog normally blocks input; programmatic adders such as collab merges or AI agents are not blocked)
- Severity: Medium      Priority: P2
- Evidence:
  - `ui/reader/ReaderState.kt:385`: `val pending = annots.toList()`. Then commit, swap, write-back, and `:395` runs `annots.clear()`.
  - `recordSaved` (`:405-408`) replaces every memory step.
  - `saveInBackground` runs off the main thread (`:412`) and mutates snapshot state concurrently with the UI.
- User impact / scenario: An annotation that arrives during a multi-second save (live collab, voice command, handwriting-to-text conversion finishing) is dropped and its undo step is lost.
- Proposed fix: Use `annots.removeAll(pending.toSet())`. Replace only the memory steps that existed at snapshot time. Run the state mutations on `Dispatchers.Main`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Unit test that adds an annotation in a hook between commit and clear: it remains pending.

### DI-37 The `renameTo` → `copyTo(overwrite)` fallback is non-atomic in every "atomic" path, and there is no fsync or output verification before replacing
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence:
  - `ReaderState.kt:436` (swapFile), `PdfOps.kt:55`, `E5Io.kt:24`, `DocumentRepository.kt:68, 113`, `SyncStore.kt:131-150` and `CloudHttp.kt:62` all use `if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }`.
  - `save()` replaces the document without first checking that the temp file opens. `applyInPlace` only checks `length > 0` (`:514`).
  - Nothing calls `FileOutputStream.fd.sync()`, so on power loss a renamed file can be zero-length on ext4/f2fs.
- User impact / scenario: A rare `renameTo` failure (a cross-volume fallback after `docsDir` switched to `filesDir`, or permission oddities), a power cut, or a PdfBox bug producing an unreadable file replaces the good document. The undo snapshot is the only recovery, and it lives in an evictable cache (DI-38).
- Proposed fix:
  - Make `atomicReplace(tmp, target)` the single helper: fsync the temp, then `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` (API 26+). On failure keep the original and throw; never copy over it.
  - Before replacing a PDF, open the temp quickly with PdfBox or PDFium for validation (page count > 0).
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Unit test: with a forced move failure the original is unchanged. A corrupt temp is rejected.

### DI-38 Undo snapshots live in `cacheDir` (up to 300 MB per document) and can be evicted by the OS mid-session
- Type: data-integrity
- Status: CONFIRMED (code); eviction is device-dependent
- Severity: Low      Priority: P3
- Evidence:
  - `ReaderState.kt:132` calls `DocHistory.open(app.documents.tempDir…)`, where `tempDir` is `cacheDir/tmp` (`DocumentRepository.kt:29`).
  - `DocHistory.kt:174` sets `MAX_BYTES = 300L * 1024 * 1024`.
  - Undo fails with "snapshot missing" (`:132`).
  - Storage → "Clear caches" also targets `tmp` (`StorageManager.kt:21, 75`).
- User impact / scenario: On a low-storage device, the user rotates and deletes pages and then tries to undo. The snapshot was evicted, and the destructive tool cannot be undone.
- Proposed fix:
  - Store snapshots in `noBackupFilesDir/history` (not evictable, not backed up), keeping the existing stale cleanup.
  - Scale `MAX_BYTES` to free space (for example min(300 MB, 10 % of free space)).
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Unit test of the budget. Device test with `pm trim-caches`.

### DI-39 The trash move and its metadata write are not atomic, and purge runs only when the Trash screen opens
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence:
  - `core/view5/Trash.kt:27-33` moves the file first and writes `.meta` second. `list()` only enumerates `.meta` files (`:37`), so a crash between the two leaves an invisible, never-purged file.
  - `purgeExpired` is called only from `ui/view5/TrashScreen.kt:44` (and `Trash5.purge`).
  - The trash sits in `filesDir` and is backed up (DI-14).
- User impact / scenario: Orphaned trash consumes space that the UI cannot show. Deleted documents survive in backups.
- Proposed fix: Write the `.meta` (state "moving") before the move and finalise it after. List files without metadata as "unknown" entries. Purge from a daily worker or at app start. Exclude the trash from backup.
- Effort: S     Regression risk: Low     Dependencies: DI-06
- Validation: Unit test that simulates a crash after the move: the entry is visible and restorable.

### DI-40 WorkManager: IndexWorker retries forever on any exception; sync's "any" and "wifi" periodic jobs can run back to back
- Type: performance
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence:
  - `core/search/IndexWorker.kt:86`: `runCatching { … Result.success() }.getOrElse { Result.retry() }` has no `runAttemptCount` cap. A systematic failure (for example a DB problem) retries with backoff indefinitely, waking the device.
  - Sync is otherwise well guarded: per-pair `Mutex.tryLock` (`SyncRuntime.kt:132`), unique work names (`SyncWorker.kt:122`), `retry` only on IOException and `runAttemptCount < 3` (`:66`), and a snapshot written atomically and only after a completed run (`SyncEngine.kt:209-210`). A partial run leaves uploaded files without a snapshot. The next pass then RECORDs them when sizes match and raises a conflict otherwise, so no duplication happens.
  - `IndexWorker` per-file work is idempotent (`needsIndex` by size and mtime).
- User impact / scenario: Battery drain in the edge case.
- Proposed fix: `if (runAttemptCount >= 3) Result.failure()`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Worker test with `TestListenableWorkerBuilder` and a forced exception: the fourth attempt returns failure.

### DI-41 Cloud write-back conflict copy always gets the same name
- Type: data-integrity
- Status: HYPOTHESIS (provider behaviour)
- Severity: Low      Priority: P3
- Evidence: `core/cloud/CloudLinks.kt:75`: `SyncRules.conflictName(link.item.name) { false }` always produces "X (نسخة متعارضة).pdf". On providers with unique names (OneDrive and Dropbox upload modes), a second conflict may overwrite the first conflict copy or fail.
- User impact / scenario: Two offline edit sessions in a row with remote changes overwrite the first conflict copy.
- Proposed fix: Check existing names in `parentId` (`provider.list`), or append a timestamp.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Fake-provider test with two conflicts: two distinct files result.

### DI-42 Unzip has no size or entry-count limit; import and Wi-Fi upload have no free-space check
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence:
  - `core/files/ZipUtil.kt:266-285` streams every entry, with no total-bytes or entry limit (the path traversal check is correct).
  - `DocumentRepository.localFile` (`:66`) copies without `StatFs`, and a failed copy leaves `.X.import` behind.
  - `WifiTransferServer.kt:249` caps per request but does not check free space.
- User impact / scenario: A zip bomb, or a 2 GB archive on a 1 GB-free phone, fills storage. Subsequent saves, and the DataStore, then fail (DI-33).
- Proposed fix: Cap the uncompressed total at min(free space − 200 MB, 2 GB) and entries at about 10 000. Delete partial output on failure. Check `StatFs.availableBytes` before import.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: Unit test with a crafted high-ratio zip: it aborts and cleans up.

### DI-43 The Office plain-text save writes the working copy in place
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `core/office/OoxmlReader.kt:59` runs `file.outputStream().use { it.write(bom); it.write(body) }`. It is called with `target == file` from `ui/office/OfficeText.kt:69-71` and `OfficeScreen.kt:124`. DOCX, XLSX and CSV use temp files (`DocxWriter2`, `CsvIO.kt:90`, `OPackage.kt:160`).
- User impact / scenario: A crash during the save of a TXT/MD file leaves it truncated, and write-back then propagates it.
- Proposed fix: Write to a sibling temp and then use the atomic replace from DI-37.
- Effort: S     Regression risk: Low     Dependencies: DI-37
- Validation: Unit test.

### DI-44 Autosave on every ON_PAUSE fully rewrites the original and triggers cloud upload and sync each time
- Type: performance
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence:
  - `ReaderScreen.kt:92`: ON_PAUSE with `autoSave && dirty` calls `saveInBackground()`.
  - Each save runs `writeBack` (full copy, DI-01), `SaveHooks.onSaved`, which uploads to the cloud and calls `SyncLive.onFileSaved` (`CloudLinks.kt:105-116`), and `history.copyIn` (a full copy).
  - ON_PAUSE fires for permission dialogs, the share sheet, the image picker launched from the reader, and multi-window focus changes.
- User impact / scenario: Inserting an image opens the picker. The resulting pause writes the 50 MB document twice and uploads it, which increases DI-01's exposure window and costs mobile data.
- Proposed fix: Save locally on pause (already atomic) but debounce write-back and upload until ON_STOP or reader close, plus a WorkManager safety net. Skip the save when the pause comes from the app's own activity-result launches.
- Effort: S     Regression risk: Low     Dependencies: DI-01, DI-24
- Validation: Instrumented test that counts write-backs while opening the image picker: zero until close.

### DI-45 The docs folder silently switches to internal storage when external is unavailable
- Type: arch-risk
- Status: HYPOTHESIS
- Severity: Low      Priority: P3
- Evidence: `DocumentRepository.kt:27` and `StorageManager.kt:35` use `getExternalFilesDir(null) ?: context.filesDir`. If external storage is unavailable at some launch (emulated storage not yet mounted after boot on some OEMs, or adoptable storage removed), new imports go to `filesDir/Documents`. All existing recents, `file://` keys, origins and tabs then point to the other volume, which looks like data loss.
- User impact / scenario: After a reboot, Recents shows "file not found" for every document until storage mounts. Documents created meanwhile are split across two folders.
- Proposed fix: Pick the volume once and persist the choice. If it is unavailable, show "storage not ready" instead of switching.
- Effort: S     Regression risk: Low     Dependencies: DI-16
- Validation: Needs device test.

## Quick wins (S effort, high impact)
- DI-04: refuse a rename onto an existing name.
- DI-02: do not overwrite a working copy that has local changes.
- DI-05: report and keep annotations that failed to write.
- DI-06: never fall back to a permanent delete.
- DI-08: export the schema, drop the destructive fallback, add a migration test.
- DI-14 + DI-15 + DI-17: backup and extraction rules, Keystore for secrets.
- DI-29 / DI-30: make SAF export errors visible and stop the crash.
- DI-10: route every reader navigation through the dirty guard.
- DI-25: mass-deletion guard in sync.
- DI-31 + DI-32: startup sweep of hidden temps and decrypted copies, and filter dotfiles from Files, Device scan and the indexer.
- DI-33: DataStore corruption handler.
- DI-36: `removeAll(pending)` instead of `clear()`.
- DI-12: persistent "original not updated" marker.

## Needs device testing
- DI-01: provider behaviour of `"w"` versus `"wt"`/`"rwt"` (Downloads, ExternalStorage, Google Drive, SD card), and an interrupted write-back.
- DI-10 / DI-11: Navigation-Compose order of ON_PAUSE and `onDispose` for the reader entry, and loss of history on forward navigation.
- DI-14 / DI-17: Auto Backup contents and the 25 MB quota (`bmgr`, `dumpsys backup`), and D2D transfer of tokens.
- DI-18: eviction of persisted URI grants beyond 128 (API ≤29) and 512 (API 30+), and its effect on sync trees.
- DI-19: which senders (Telegram, WhatsApp, Gmail, Files by Google) grant write or persistable permission on ACTION_VIEW.
- DI-25: listing behaviour of `DocumentFile.listFiles()` when an SD or USB tree is unmounted.
- DI-27: signature validity after an annotation save on a signed sample.
- DI-38: cache eviction of history snapshots under `pm trim-caches`.
- DI-45: `getExternalFilesDir` returning null at early boot.
