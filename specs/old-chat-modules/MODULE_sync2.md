# MODULE_sync2 — multi-cloud folder sync, near-real-time sync, live shared-review comments, reader coach marks

Written without running Gradle (round-3 rules). I checked these APIs with `javap`:
- WorkManager 2.10.0:
  - `ForegroundInfo(int, Notification, int)`
  - `OutOfQuotaPolicy`
  - `setExpedited`. Expedited work only allows network/storage constraints, so the battery is checked by hand.
  - `CoroutineWorker.setForeground` / `getForegroundInfo`
- compose-ui 1.7.8: `LayoutCoordinates.positionOnScreen()` and `PopupProperties(clippingEnabled = …)`.
- pdfbox-android: `PDDocument.load(File, String)`.

String prefix `s2_`. Strings are in `res/values/strings_sync2.xml` (Arabic, including Arabic plurals) and `res/values-en/strings_sync2.xml`.

## Files
**New**
- `core/sync/SyncRuntime.kt`:
  - one lock per pair, a "run again" flag, and self-write quiet time
  - live `progress: StateFlow<Map<pairId, SyncProgress>>`
  - battery and network checks
  - notification channel `sync` and the progress notification
- `core/sync/RemoteWatcher.kt`: remote change detection while the app is in the foreground.
  - Dropbox: `list_folder/longpoll`.
  - Drive and OneDrive: the change feed is polled every 60 s and filtered by the pair's known folder and file ids.
- `core/sync/LocalWatcher.kt`:
  - `FileObserver` (inotify) when the SAF tree maps to a readable path.
  - Otherwise, and always on API 30+: the documents provider's change notifications, through child-document cursors kept open with a `ContentObserver`.
- `core/sync/SyncLive.kt`:
  - `install(app)`: activity start/stop tracking, local watchers for every auto pair, and the remote watcher while in the foreground.
  - 5 s debounce.
  - `onFileSaved()` (sync-after-save).
  - `contains(pair, file, originUri)`.
- `core/collab/CollabLive.kt`: live review comments for the document open in the reader.
  - `remote: StateFlow<List<RemoteAnnot>>`, `arrivals: SharedFlow<Int>`, `active`
  - `attach` / `detach` / `refresh()`
  - `beforeSave()`, `mergeFile()`, `confirm()`
- `ui/cloud/SyncPickers.kt`:
  - `SyncProviderChooser`
  - `CloudFolderPicker(provider)` (generic)
  - `DriveFolderPicker`, kept as a wrapper
- `ui/cloud/CollabLiveHost.kt`: one-line reader glue that attaches/detaches and shows the "N تعليقات جديدة" snackbar with a "عرض" action that merges the comments now.

**Changed (module files I now own)**
- `core/cloud/CloudModels.kt`:
  - `CloudProvider.delete(item)` (new abstract member; all three clients implement it)
  - `DeltaChange` and `DeltaPage`
- `core/cloud/GoogleDrive.kt`:
  - `delete` (trash)
  - `startPageToken()`
  - `changes(pageToken)` (changes.list). An expired token gives `reset=true`.
- `core/cloud/OneDrive.kt`:
  - `delete` (recycle bin)
  - `deltaLatest()` (`/root/delta?token=latest`)
  - `delta(link)`. A 410 response gives `reset=true`.
- `core/cloud/Dropbox.kt`:
  - `delete` (`delete_v2`)
  - `latestCursor(path)` (recursive)
  - `longpoll(cursor)`: on notify.dropboxapi.com; it is cancellable and honours backoff.
  - `continueCursor(cursor, path)`. A 409 response gives `reset=true`.
- `core/cloud/CloudLinks.kt`, `SaveHooks.onSaved`: after the upload, it now calls `CollabLive.confirm`, then publishes (`CollabStore.onDocumentSaved`), then calls `SyncLive.onFileSaved`.
- `core/sync/SyncStore.kt`:
  - `SyncPair.provider` (old pairs are read as Drive)
  - remote folder ids per pair
  - change-feed cursor per pair
  - `knownRemoteIds()`
- `core/sync/SyncEngine.kt`:
  - works with any `CloudProvider`
  - records folder ids
  - progress callback
  - conflict-copy behaviour unchanged: "(نسخة متعارضة)"
- `core/sync/SyncWorker.kt`:
  - periodic runs every 15 min (any network and Wi‑Fi groups, battery not low)
  - `syncNow` (expedited, REPLACE)
  - `request` (expedited, KEEP, or "rerun" when the pair is already running)
  - manual battery check for expedited work (it is deferred to a battery-constrained job)
  - takes a fresh change cursor before each run
  - foreground or plain progress notification after 8 s
- `core/collab/CollabStore.kt`:
  - `fetch()` no longer writes a file that is open in the reader. It hands the comments to `CollabLive.offer`.
  - new `fetchFresh`, `mergeInto` and `markApplied`.
  - `publish()` does not rewrite a file that is open in the reader.
- `ui/cloud/SyncScreen.kt` (rewritten):
  - add flow: provider, then local folder, then cloud folder
  - per-pair provider badge
  - status, relative last-sync time, live progress with the current file name
  - waiting-for-Wi‑Fi / network / battery notes
- `ui/cloud/CloudScreen.kt`: the sync row subtitle now names all three clouds.
- `ui/onboarding/CoachMarks.kt`:
  - Targets are registered in **screen** coordinates. Zero-size or detached targets are not registered.
  - The overlay measures its own screen origin, so popup and window offsets, system bars and cutouts do not shift the hole.
  - The tour list is fixed when it starts, and steps whose target disappeared are skipped.
  - "Seen" is stored only after at least one step was actually shown.
  - Popup `clippingEnabled = false`.
  - The hole is drawn in unmirrored canvas space, so RTL is correct.
  - New optional `CoachIds.READER_DOC_TABS` step.

## Hooks for the lead (files I do not own)
### 1. `ui/reader/ReaderState.kt`: merge-before-save (required for task 3)
In `save()`, between the commit and the swap (currently lines 310–311):
```kotlin
                    AnnotationWriter.commit(context, f, pending, tmp, password)
                    com.smartdocs.editor.core.collab.CollabLive.beforeSave(context, f, tmp, password)   // MODULE_sync2: merge remote review comments
                    swapFile(tmp)
```
- `beforeSave` never throws and waits at most 4 s for the network.
- It is a no-op unless `f` is a shared-review file currently attached.
- The existing `SaveHooks.onSaved(app, f)` calls (already in every save path) do the rest: `confirm`, then publish my comments, then sync-after-save.

### 2. `ui/reader/ReaderScreen.kt`
- After line 100 (`LaunchedEffect(state.error) { … }`):
  ```kotlin
      com.smartdocs.editor.ui.cloud.CollabLiveHost(state.file, snack) { op -> state.applyInPlace(state.password, op) }   // MODULE_sync2: live review comments
  ```
  - It attaches to the open file and polls every 30 s.
  - It shows "N تعليقات جديدة" with the action "عرض", which merges the comments into the document and reopens it at the same page.
  - To draw remote comments before they are merged, collect `com.smartdocs.editor.core.collab.CollabLive.remote` (`collectAsState()`). Each `RemoteAnnot` has `page`, `rect` (PDF points, origin bottom-left), `color`, `author`, `contents` and `subtype`. `CollabLive.refresh(ctx)` fetches immediately (for example, from a "refresh" button).
- After line 308 (`Annot2Dialogs(...)`):
  ```kotlin
      if (state.session != null && chrome && !state.needPassword) com.smartdocs.editor.ui.onboarding.ReaderCoachMarks()   // MODULE_sync2
  ```

### 3. `ui/reader/ReaderToolbars.kt`: coach-mark targets
Add the imports:
```kotlin
import com.smartdocs.editor.ui.onboarding.CoachIds
import com.smartdocs.editor.ui.onboarding.coachTarget
```
- Line 64 (`ReaderTabRow`):
  ```kotlin
      ScrollableTabRow(selectedTabIndex = idx, modifier = Modifier.coachTarget(CoachIds.READER_TABS), edgePadding = 8.dp, divider = {}, containerColor = MaterialTheme.colorScheme.surface) {
  ```
- Line 93 (`BottomToolbar`):
  ```kotlin
      Surface(Modifier.coachTarget(CoachIds.READER_TOOLBAR), tonalElevation = 3.dp, shadowElevation = 8.dp) {
  ```

### 4. `ui/reader/view/ReaderTabs.kt`: optional third step (open documents)
Add the same two imports. Then:
- Line 174 (`ReaderTabStrip`): `Surface(Modifier.coachTarget(CoachIds.READER_DOC_TABS), tonalElevation = 1.dp) {`
- Line 222 (`ReaderTabsAction`): `Box(Modifier.coachTarget(CoachIds.READER_DOC_TABS)) {`

If these are left out, the step is simply skipped.

### 5. `SmartDocsApp.onCreate()`: start watching at app start
Add this at the end:
```kotlin
        com.smartdocs.editor.core.sync.SyncLive.install(this)
```
- It is idempotent.
- Without it, watching starts the first time the Sync screen opens or a document is saved in this process. After that the periodic job still runs every 15 min.

### 6. `AndroidManifest.xml`
- Add the permission (`FOREGROUND_SERVICE` and `POST_NOTIFICATIONS` are already declared):
  ```xml
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
  ```
- Inside `<application>`, give WorkManager's foreground service the dataSync type. This is needed on Android 14+ for the long-sync foreground notification (`xmlns:tools` is already declared):
  ```xml
  <service
      android:name="androidx.work.impl.foreground.SystemForegroundService"
      android:foregroundServiceType="dataSync"
      tools:node="merge" />
  ```
- Without these, the worker catches the failure and posts a plain progress notification instead, but expedited work on API < 31 runs as a foreground service and needs the declaration.
- `POST_NOTIFICATIONS`: on API 33+ the progress notification only shows if the permission was granted (the reader already asks for it for read-aloud). No other permission is needed. SAF tree permissions are persisted when a folder is picked.

## Behaviour summary
**Triggers**
- The periodic job runs every 15 min (Wi‑Fi-only pairs on UNMETERED, others on CONNECTED; battery not low).
- The local watcher fires 5 s after the last local change while the process is alive.
- Remote changes are detected in the foreground: Dropbox longpoll (about instant), and the Drive/OneDrive feed every 60 s.
- "Sync now" button.
- Sync-after-save, when the saved file or its source document (`documents.originOf`) is inside a paired tree.
- When the app comes to the foreground, pairs not synced in the last 2 min are requested.
- All automatic triggers are expedited one-time works. They respect Wi‑Fi-only (network constraint) and battery (manual check, then fall back to a battery-constrained job).

**Running a pair**
- Runs never overlap per pair. A change during a run causes one more pass (at most 3).
- Our own writes are ignored by the local watcher during a run and for 4 s after it.

**Shared review**
- While a review file is open, no background code rewrites it.
- Remote comments are kept in memory, shown live (StateFlow plus snackbar), and merged by /NM into the save output before the file is replaced.
- They are marked "applied" only after the save really replaced the file (`SaveHooks`, then `confirm`). After that, my own comments are published, and merged comments are not republished as mine.
- Comments not merged when the reader closes are written 1.5 s after `detach`. If that fails (for example, offline), they are fetched again next time, because nothing was marked as applied.

## Limitations / TODO
**Change feeds**
- Drive and OneDrive feeds are drive-wide and filtered by the ids the pair knows. A new file in a brand-new remote subfolder created between syncs is caught by the parent-id check only for the first level. Deeper levels are caught by the next periodic run.
- The change feed also reports our own uploads, so one extra (no-op) sync pass may follow a sync that uploaded.
- Dropbox longpoll uses one connection per Dropbox pair while in the foreground.

**Local watcher**
- On Android 11+ shared storage, inotify through FUSE may miss writes made by other apps through MediaProvider. The provider-cursor observer covers ExternalStorageProvider trees.
- Trees from other providers (SD card via a vendor provider, cloud document providers) may send no notifications. They then rely on the 15-min job, sync-after-save and "Sync now".
- FileObserver watches up to 64 folders (depth 3). Cursor observers watch up to 32 folders (depth 2).

**Notifications and Dropbox uploads**
- The long-sync notification appears after 8 s. It is indeterminate (the total count is unknown up front) and shows the files transferred so far and the current name.
- Dropbox uploads are still limited to 150 MB (no upload sessions).

**Shared review**
- If I locally edited someone else's comment and they edit it remotely, their version wins at merge (server rule: only authors edit their own comments).
- `beforeSave` merges only for documents opened through `CollabLiveHost` (that is, attached).
