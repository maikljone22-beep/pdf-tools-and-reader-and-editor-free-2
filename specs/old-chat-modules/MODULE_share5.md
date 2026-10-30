# MODULE_share5 — Share tab: share options, managed shared review, review tracker, e-mail review, view-only links, storage settings, app-data sync

String prefix `s5_` (238 keys, identical sets): `res/values/strings_share5.xml` (Arabic) and `res/values-en/strings_share5.xml`.
No Gradle was run.

## How this was checked
- **Kotlin**: all 21 share5 files compiled with standalone `kotlinc` 2.1 + the Compose compiler plugin: 0 errors. The classpath was android-35 plus the cached jars (Compose 1.7.8, Material3 1.3.1, navigation 2.8.9, activity 1.10.1, okhttp 4.12, pdfbox-android 2.0.27, play-services-auth 22.0.0). The real `CloudHttp`, `CollabClient`, `CollabAnnots` and `AppDatabase` sources were compiled alongside. Other project classes were stubbed with their real signatures.
- **Worker** (`ai-proxy/share`), run in Node against a mock KV:
  - view links: create, password (wrong and right), view counting once per token, unique visitors, download refused, disable → 410, expiry → 410, delete with the wrong and right key.
  - review extensions: PATCH reviewers, activity, seen and consent, remind (mail mock), rotate code (old code → 403), deadline change.
  - web comments: posted, and a non-note annotation is rejected.
  - cron: deadline reminder, @mention mail, owner digest.
- **Web pages**, driven in headless Chromium with a real PDF and pdf.js 3.11.174:
  - review page: consent, add a note by tapping the page, marker and comment list, POST stored in the collab KV layout.
  - view page: wrong password message, then the right password renders 2 pages.
  - A canvas RTL text bug was found and fixed (`.page{direction:ltr}`).

## Status per owned ID
| ID | Status | What / where |
|---|---|---|
| SHR-1 Shared review lifecycle | **DONE** | <ul><li>`ReviewCreateScreen`: subject, name, deadline, reviewer names and e-mails, permissions, owner e-mail, message, invitation (mail, share, WhatsApp, copy, QR).</li><li>`ReviewManageScreen`: change or extend the deadline, End, Reopen, Add reviewers, Remind all or one (server e-mail, falling back to the share sheet).</li><li>The «انتهت المراجعة» banner. Comments after the end are refused (existing 410 + web 410).</li><li>Automatic reminder e-mail 24 h before the deadline (cron).</li><li>Not done: a poll-interval setting (polling stays at 30 s in acct/sync2 code), and "take ownership of others' unpublished comments" (no such state in our model).</li></ul> |
| SHR-2 Review tracker | **DONE** | <ul><li>`ReviewTrackerScreen`, with three tabs: «أرسلتُها / شاركتُ فيها / عبر البريد».</li><li>Meta and comments are fetched in parallel (`ReviewTracker.load`).</li><li>A new-comments badge (count seen per review), deadline/ended state and reviewers/comment counts.</li><li>A card opens the manage screen (actions).</li></ul> |
| SHR-3 E-mail-based review | **DONE** (needs hooks 6–7) | <ul><li>`EmailReview.prepare` shares a copy with a document id (trailer `/ID[0]` + `/Info SDReviewId`) and the note «أعد إرسال التعليقات…».</li><li>`EmailReview.match` recognises a returned annotated PDF, or an XFDF/FDF by ids, falling back to the `href` file name.</li><li>`ReturnedCommentsHost` asks «دمج تعليقات (فلان)؟» and merges through `CommentExchange.merge/importFile`. Duplicate merges are guarded by a file fingerprint.</li><li>Share option «التعليقات فقط (XFDF)» writes `<ids>` so the sender's app can match it.</li></ul> |
| SHR-4 Collaboration privacy consent | **DONE** | <ul><li>`ReviewConsentGate` appears the first time a joined review opens: «موافق / المشاركة باسم مجهول». It can be changed later via «خصوصيتي».</li><li>Anonymous reviewers publish as «مراجع NN», and `/T` is rewritten in outgoing comments (`ReviewConsent.anonymize`, hooked in `CollabStore.publish`).</li><li>The consent `{time, version, anonymous}` is stored on the server and shown in the activity feed.</li><li>The web page shows the same notice.</li></ul> |
| SHR-5 Share options | **DONE** (needs hook 2) | `Share5Sheet` / `ShareOptionsScreen`:<ul><li>Pages: all, current, or a range.</li><li>Format: as is, compressed, without comments, flattened (vector), as images (JPEG, ≤60 pages), or comments only (XFDF).</li><li>Share sheet or e-mail with the attachment.</li><li>Entry points to view link, shared review and e-mail review.</li></ul> |
| SHR-6 Enterprise CMS integrations | **SKIPPED** (P3) | <ul><li>SharePoint libraries need the `Sites.Read.All` scope (tenant admin consent), and the OneDrive client's token handling is private to the acct module.</li><li>On-premises DMS systems are out of scope per the spec.</li></ul> |
| SHR-7 Send to note-taking apps | **DONE** (N/A) | Android's share sheet reaches these apps; the «مشاركة» button in the share sheet does this. |
| O1 Share for review with tracking | **DONE** | <ul><li>Invite by e-mail or link, a browser review page (`/w/<id>`, pdf.js, add or delete notes, no app), and allow-web / allow-download toggles.</li><li>Deadline with reminders, per-reviewer status (invited / opened / commented, last activity, web), and an activity feed (comments, opens, consents).</li><li>@mentions → e-mail to the named invitee (cron), an owner digest e-mail, and revoke link (rotates the access code).</li><li>Live updates are polling (30 s), not SSE/Durable Objects.</li></ul> |
| O2 View-only link with analytics | **DONE** | <ul><li>`ViewLinkCreateScreen` options: expiry (presets or a date), password (PBKDF2 on the server, 10 tries / 15 min), allow download.</li><li>`ViewLinkDetailScreen` shows views, unique visitors and downloads, a per-day bar list, copy/share/mail/QR/open, change expiry, password or download, pause (disable) and delete.</li><li>Server: `/v/<id>` pdf.js viewer.</li></ul> |
| O3 Recently deleted / restore | **DONE by view4** | `core/view4/Trash.kt` (+ view4 UI) already implements it, so it was not duplicated here. `StorageScreen` shows the trash size and empties it through `view4.Trash.empty`. |
| O4 Cross-device sync of app data | **DONE** | <ul><li>`AppDataBundle`: a ZIP with signatures and initials, custom stamps, the fill profile, tags, favorites (name+size), identity, author and the view4 stamp library. Import merges and never deletes.</li><li>`AppDataSync`: the Drive **appDataFolder** (`drive.appdata` scope, its own authorization). It pulls newer bundles from other devices and uploads only when local content changed (no ping-pong). Automatic sync runs when the hub opens (≥10 min apart), plus «مزامنة الآن».</li><li>Export/import to a file.</li><li>`AppDataScreen` holds all of this.</li></ul> |
| O5 Storage settings | **DONE** | <ul><li>`StorageScreen` sizes: thumbnails, temp, reflow, AI index, AI voice, template previews, other cache, cloud copies, OCR languages, trash, documents and reviews.</li><li>Clear each, or clear all caches.</li><li>Unlink each cloud provider (optionally deleting its local copies).</li><li>«مسح كل بيانات التطبيق» (`clearApplicationUserData`).</li><li>Choosing the cache *location* is not offered (Android manages app cache dirs).</li></ul> |

## Files
**Server** (new): `ai-proxy/share/worker.js`, `ai-proxy/share/pages.js`, `ai-proxy/share/wrangler.toml`.
- It binds the **same KV namespace as `ai-proxy/collab`** (`COLLAB`) and, if collab uses R2, the same bucket (`FILES`), plus its own KV `SHARE`.
- The collab worker itself is unchanged.
- Mail uses the same `MAIL_*` variables as `ai-proxy/esign`.
- `PUBLIC_URL` is needed for links in cron mails.

**Core** `core/share5/`:
- `ShareClient.kt`: `S5Config`, `S5Client`.
- `ShareStores.kt`: `ViewLinkStore`, `ReviewExtras`.
- `ShareBuilder.kt`
- `EmailReview.kt`
- `ReviewTracking.kt`: `ReviewConsent`, `ReviewTracker`.
- `StorageManager.kt`
- `AppDataBundle.kt`
- `AppDataSync.kt`

**UI** `ui/share5/`:
- `Share5Routes.kt`: routes and `ShareHubScreen`.
- `Share5Common.kt`
- `ShareOptionsSheet.kt`
- `ReviewCreateScreen.kt`
- `ReviewManageScreen.kt`
- `ReviewManageParts.kt`: includes `ReviewConsentGate`.
- `ReviewTrackerScreen.kt`
- `ViewLinkScreens.kt`
- `ReturnedComments.kt`
- `StorageScreen.kt`
- `AppDataScreen.kt`

**Edited outside the module (trivial, re-read first)**: `core/collab/CollabStore.kt` `publish()`. The author and outgoing data now go through `ReviewConsent.author(...)` / `ReviewConsent.anonymize(...)` (SHR-4). This is a no-op unless the reviewer chose anonymity.

## Hooks for the lead
1. **`ui/Navigation.kt`**: `import com.smartdocs.editor.ui.share5.share5Routes`, then after `connRoutes(nav)` (line ~168) add `share5Routes(nav)`.

   | route | screen |
   |---|---|
   | `share5` | hub |
   | `share5/share?uri=&page=` | share options |
   | `share5/reviews` | tracker |
   | `share5/review_new?uri=` | new review |
   | `share5/review/{id}` | manage review |
   | `share5/views` | view links |
   | `share5/view_new?uri=` | new view link |
   | `share5/view/{id}` | view link detail |
   | `share5/returned?uri=` | returned comments |
   | `share5/storage` | storage |
   | `share5/appdata` | app data |

   Helpers: `Share5Routes.share(uri, page)`, `.reviewNew(uri)`, `.review(id)`, `.viewNew(uri)`, `.view(id)`, `.returned(uri)`.
2. **`ui/reader/ReaderScreen.kt`** (share sheet, SHR-5):
   - Add `var showShare5 by remember { mutableStateOf(false) }`.
   - In `ReaderActions(...)` replace `share = { scope.launch { outputFile()?.let { ReaderIntents.shareFile(ctx, app, it) } } },` with `share = { showShare5 = true },`.
   - Then near the other sheets add:
     ```kotlin
     if (showShare5) { var f by remember { mutableStateOf<java.io.File?>(null) }
         LaunchedEffect(Unit) { f = outputFile() }
         f?.let { com.smartdocs.editor.ui.share5.Share5Sheet(it, if (it == state.file) state.password else null, state.currentPage, nav) { showShare5 = false } } }
     ```
   - Simplest acceptable alternative: `share = { state.file?.let { nav.navigate(com.smartdocs.editor.ui.share5.Share5Routes.share(android.net.Uri.fromFile(it).toString(), state.currentPage)) } }`. This loses unsaved annotations unless the user saves first; the screen says so.
3. **`ui/reader/ReaderScreen.kt`** (SHR-3): after line 103 (`CollabLiveHost(...)`) add:
   ```kotlin
   state.file?.let { com.smartdocs.editor.ui.share5.ReturnedCommentsHost(it, nav) }   // share5: merge returned e-mail-review comments
   ```
4. **`ui/cloud/ReviewScreen.kt`** (acct):
   - In `ReviewDetail`, after `LaunchedEffect(ref.id) { while (true) { refresh(false); … } }` add `com.smartdocs.editor.ui.share5.ReviewConsentGate(ref)`.
   - In its top-bar `actions` add:
     ```kotlin
     IconButton(onClick = { nav.navigate(com.smartdocs.editor.ui.share5.Share5Routes.review(ref.id)) }) { Icon(Icons.Default.ManageAccounts, null) }
     ```
   - Optionally make the list FAB open `Share5Routes.reviewNew(null)`.
5. **`ui/cloud/CloudScreen.kt`**: after the shared-review `GroupRow` (line ~127) add:
   ```kotlin
   GroupDivider()
   GroupRow(stringResource(R.string.s5_hub), stringResource(R.string.s5_tracker_sub), icon = Icons.Default.Share, tile = tiles.amber, onClick = { nav.navigate(com.smartdocs.editor.ui.share5.Share5Routes.HUB) })
   ```
   Settings screen: rows to `Share5Routes.STORAGE` (`s5_storage` / `s5_storage_sub`) and `Share5Routes.APPDATA` (`s5_appdata` / `s5_appdata_sub`).
6. **`AndroidManifest.xml`** (MainActivity): returned comment files:
   ```xml
   <intent-filter>
       <action android:name="android.intent.action.VIEW" />
       <category android:name="android.intent.category.DEFAULT" />
       <data android:scheme="content" android:mimeType="application/vnd.adobe.xfdf" />
       <data android:scheme="content" android:mimeType="application/vnd.fdf" />
       <data android:scheme="file" android:mimeType="application/vnd.adobe.xfdf" />
       <data android:scheme="file" android:mimeType="application/vnd.fdf" />
   </intent-filter>
   ```
7. **`MainActivity.kt`** `setContent`: replace `uri?.let { nav.navigate(Routes.reader(it.toString())); incoming.value = null }` with:
   ```kotlin
   uri?.let { val ext = it.path?.substringAfterLast('.', "")?.lowercase()
       nav.navigate(if (ext == "xfdf" || ext == "fdf") com.smartdocs.editor.ui.share5.Share5Routes.returned(it.toString()) else Routes.reader(it.toString())); incoming.value = null }
   ```
   `localFile` keeps the extension.
8. **Files tab (optional)**: in `FilesScreen` single-file share use `nav.navigate(Share5Routes.share(Uri.fromFile(f).toString()))` for PDFs.
9. **`build.gradle.kts` `defaultConfig`** (read via `BuildCfg`; compiles without them):
   ```kotlin
   buildConfigField("String", "SHARE_URL", "\"${cloudProps.getProperty("shareUrl", "")}\"")
   buildConfigField("String", "SHARE_TOKEN", "\"${cloudProps.getProperty("shareToken", "")}\"")
   ```
   An empty `SHARE_TOKEN` falls back to `COLLAB_TOKEN`. While `shareUrl` is empty:
   - view links show "server not configured";
   - reviews still work through the collab worker, but without reminders e-mail, revoke, web review or activity.
10. **Google Cloud Console** (O4): the Android OAuth client used for Drive must also allow the `https://www.googleapis.com/auth/drive.appdata` scope (non-sensitive). No manifest change.
11. **Optional (O4)**: in `SmartDocsApp.onCreate()` add:
    ```kotlin
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { com.smartdocs.editor.core.share5.AppDataSync.autoSyncQuietly(this@SmartDocsApp) }
    ```
    Without it, auto-sync runs when the share hub opens.

## Limitations
- **View links**:
  - "Allow download" off hides the button and refuses `?dl=1`, but the browser still receives the PDF bytes to render, so it is not DRM.
  - Unique visitors = hash of IP + User-Agent per link (30 days).
  - KV counters are eventually consistent (concurrent views may undercount).
- **Revoke link** rotates the access code: every device, including joined ones, loses access until it gets the new invitation (joined devices see "review gone").
- **Reminders and mentions**:
  - They need the `MAIL_*` config.
  - Mention matching is `@name` against invited names (spaces ↔ `_`, case-insensitive), up to three words.
  - Cron granularity is 30 min.
- **Web reviewers**:
  - They can add and delete sticky notes only (the server rejects any other annotation type or appearance streams).
  - Their identity is a random per-browser id.
- **Anonymity**:
  - It applies to comments published after the choice. Comments already published under the real name are not rewritten.
  - The server still sees the device's participant id.
- **E-mail review matching**: other editors may rewrite `/ID[0]`. The `/Info SDReviewId` key usually survives. XFDF from other apps without `<ids>` matches by file name only.
- **App-data sync**:
  - It merges only (deletions do not propagate).
  - Favorites match by name + size.
  - Stamp images are copied into `filesDir/stamps`.
