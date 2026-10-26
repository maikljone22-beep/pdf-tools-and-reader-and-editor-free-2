# MODULE_share6 (round 5) — multi-cloud sync, sharing & review, signature requests, connected documents, licences + ads, conversion fidelity

Rebuilt from the recovered notes MODULE_sync2, MODULE_share5, MODULE_conn, MODULE_monet, MODULE_fid on top of the current code,
plus Box, Wi‑Fi transfer network option and cloud copy-link. Gradle was NOT run.

**Verification (outside Gradle)**
- Kotlin type-check with kotlinc 2.3.21 + Compose plugin against the app's last `classes.jar` + all dependency jars (+ play-services-ads 24.4.0 / UMP 3.2.0 for `core/ads`), script `scratchpad/share6/kc6.sh`: see "Compile status" at the end.
- JUnit (plain JVM, `scratchpad/share6/t6.sh`): sync rules + queue + comment merge 25/25, share rules 11/11, esign/connected 30/30, licence 7/7, conversion fidelity 19/19 — **92 tests, all pass**.
- Workers run in Node against mock KV/mail/Google APIs: collab (Box token route), share, esign, connected, license — all checks pass.
- Desktop PDFBox harnesses: connected-document stamping (AES-256), converter on generated multi-column / rotated PDFs.

String files (Arabic in `values/`, English in `values-en/`, identical key sets):
`strings_sync6.xml` (`s6_`), `strings_share5.xml` (`sh5_` — NOT `s5_`, which search5 already uses), `strings_conn.xml` (`cn_`), `strings_monet.xml` (`mn_`), `strings_fid.xml` (`fd_`).

---------------------------------------------------------------------------------------------------------------------
## 1. Files

### Cloud / sync / live review (sync2 + extras)
- `core/cloud/CloudModels.kt` — `ProviderId.BOX`; `CloudProvider` gains default members `delete`, `metadataOf`, `supportsLinks`, `shareLink(item, anyone)`, `changeCursor`, `changesSince`; `Clouds.accountOf(p)`.
- `core/cloud/Box.kt` (new) — Box Content API: list/search/download/upload (chunked sessions > 50 MB)/update/create folder/delete (trash)/shared link/events feed.
- `core/cloud/GoogleDrive.kt`, `OneDrive.kt`, `Dropbox.kt` — delete, metadata, share links (anyone-with-link / private web link), change feeds (Drive changes, Graph delta, Dropbox cursor).
- `core/cloud/OAuthPkce.kt` — Box endpoint (secret kept by the collab worker `/oauth-token/box`, https bounce `/oauth/box`); token refresh serialised (Box refresh tokens are single-use).
- `core/cloud/CloudLinks.kt` — `SaveHooks.onSaved` now: cloud write-back → `CollabLive.confirm` → publish my comments → `SyncLive.onFileSaved`. Write-back of a cloud working copy detects a newer cloud version and uploads ours as "(نسخة متعارضة)" instead of overwriting.
- `core/sync/SyncRules.kt` (new, pure) — conflict/deletion rules, conflict names, SAF tree containment.
- `core/sync/SyncQueue.kt` (new, pure) — per-file queue state machine (pending/running/synced/failed, exponential back-off 30 s…6 h, give up after 6, manual retry).
- `core/sync/SyncStore.kt` — `SyncPair.provider`, `failed`; queue persistence + live `queues` flow; `retry()`; change cursors.
- `core/sync/SyncEngine.kt` — works with any provider, through `SyncRules` + `SyncQueue`, progress callback.
- `core/sync/SyncRuntime.kt` (new) — per-pair lock, rerun flag, quiet period after own writes, live progress, network/battery checks.
- `core/sync/SyncWorker.kt` — periodic every 15 min (Wi‑Fi group / any-network group), `syncNow` (expedited, retries failed files), `request` (expedited, coalesced), battery check for expedited work, `getForegroundInfo` for API ≤ 30.
- `core/sync/SyncLive.kt` (new) — near-real-time: ContentObserver on paired SAF trees (5 s debounce), cloud change feeds every 60 s while the app is visible, stale pairs on app open, sync-after-save.
- `core/sync/SyncPrefs.kt` (new) — auto sync master switch, live sync, sync-on-save, transfer "Wi‑Fi only", Wi‑Fi-transfer-server "Wi‑Fi only".
- `core/collab/CommentMerge.kt` (new, pure) — merge plan for others' comments (never mine, newest wins, tombstones, "new" count).
- `core/collab/CollabStore.kt` — `fetch` never rewrites a file open in the reader (hands comments to CollabLive); `planFor`/`applyPlan`/`markApplied`/`mergeInto`; `publish` goes through `ReviewConsent` (anonymous reviewers).
- `core/collab/CollabLive.kt` (new) — live comments of the open review document: poll 30 s, `remote`, `arrivals`, `beforeSave`, `mergeOp`, `confirm`, merge-on-close.
- `core/collab/ReviewCheckWorker.kt` (new) — every 30 min: "N new comments" notification (opens `smartdocs://review/<id>?c=`).
- `core/collab/CollabFlows.kt` — schedules ReviewCheckWorker on create/join.
- `core/files/WifiTransferServer.kt` — additive `bindAddress`, `wifiIp()`.
- `ui/cloud/SyncScreen.kt` (rewritten) — any connected cloud, provider badge, live progress, waiting-for-Wi‑Fi/network/battery notes, per-file queue with retry / retry all, sync all, options sheet.
- `ui/cloud/SyncPickers.kt` (new) — `SyncProviderChooser`, generic `CloudFolderPicker`, `DriveFolderPicker` wrapper, `ProviderBadge`.
- `ui/cloud/SyncSettings.kt` (new) — `SyncSettingsSection()` for the settings screen.
- `ui/cloud/CollabLiveHost.kt` (new) — reader glue (snackbar "N تعليقات جديدة" + "عرض").
- `ui/cloud/CloudBrowserScreen.kt` — per-file link menu: copy share link / share link / copy private link.
- `ui/cloud/CloudScreen.kt` — Box row (automatic), sync subtitle names all clouds, share-hub row.
- `ui/cloud/ReviewScreen.kt` — `ReviewConsentGate(ref)` + "manage review" action.
- `ui/cloud/DeepLinks.kt` — Box account load; hosts `esign`, `doc` → `ConnDeepLinks.handle`.
- `ui/cloud/WifiScreen.kt` — "Wi‑Fi only" switch (Wi‑Fi/hotspot address only vs every network).
- `ui/onboarding/CoachMarks.kt` — reader first-run tips fixed: screen coordinates, zero-size targets ignored, overlay measures its own origin, tour fixed at start, seen stored only after a step was shown, `clippingEnabled = false`, optional `READER_DOC_TABS` step.
- `ai-proxy/collab/worker.js` + `README.md` — `POST /oauth-token/box` (adds BOX_CLIENT_SECRET), Arabic Box setup section.
- Tests: `test/.../core/sync/SyncRulesTest.kt`, `SyncQueueTest.kt`, `test/.../core/collab/CommentMergeTest.kt`.

### Share & review (share5)
- `core/share5/`: `ShareRules.kt` (pure: link expiry, password lockout, e-mail-review matching, page ranges), `ShareClient.kt`, `ShareStores.kt`, `ShareBuilder.kt`, `EmailReview.kt`, `ReviewTracking.kt`, `ReviewConsent.kt`, `StorageManager.kt`, `AppDataBundle.kt`, `AppDataSync.kt`.
- `ui/share5/`: `Share5Routes.kt` (+ hub), `Share5Common.kt`, `ShareOptionsSheet.kt` (`Share5Sheet`), `ReviewCreateScreen.kt`, `ReviewManageScreen.kt`, `ReviewManageParts.kt` (`ReviewConsentGate`), `ReviewTrackerScreen.kt`, `ViewLinkScreens.kt`, `ReturnedComments.kt` (`ReturnedCommentsHost`, `ReturnedScreen`, `EmailReviewScreen`), `StorageScreen.kt`, `AppDataScreen.kt`.
- `ai-proxy/share/`: `worker.js`, `pages.js` (browser review page `/w/<id>`, view page `/v/<id>`), `wrangler.toml`, `README.md` (Arabic).
- Test: `test/.../core/share5/ShareRulesTest.kt`.

### Signature requests, connected documents, protected files (conn)
- `core/esign/`: `EsignModels.kt`, `EsignGeometry.kt`, `EsignClient.kt`, `EsignStore.kt`, `EsignComposer.kt`.
- `core/connected/`: `ConnMarker.kt`, `EnterpriseProtection.kt`, `ConnectedClient.kt`, `ConnStore.kt` (Keystore AES-GCM key cache), `ConnectedStamp.kt`, `ConnectedDocs.kt`.
- `ui/esign/`: `EsignCommon.kt`, `EsignDashboardScreen.kt`, `EsignCreateScreen.kt`, `EsignFieldPlacer.kt`, `EsignDetailScreen.kt`, `EsignSignScreen.kt`.
- `ui/connected/`: `ConnRoutes.kt` (+ `ConnDeepLinks`), `ConnectedHomeScreen.kt`, `ConnectedDocScreen.kt`, `ConnectedOpenScreen.kt` (+ `ConnectedBanner`), `EnterpriseProtectedScreen.kt`.
- `ai-proxy/esign/` (`worker.js`, `sign-page.js`, `wrangler.toml`, `README.md`), `ai-proxy/connected/` (`worker.js`, `wrangler.toml`, `README.md`).
- Tests: `test/.../core/esign/SignerFlowTest.kt`, `AuditFormatTest.kt`, `test/.../core/connected/ConnMarkerTest.kt`, `EnterpriseProtectionTest.kt`.

### Licences + ads (monet)
- `core/billing/`: `LicenseToken.kt` (pure: Ed25519 verify, grace rules, code format), `LicenseClient.kt`, `LicenseCheckWorker.kt` (new); `Pro.kt`, `ActivationCodes.kt` (`suspend redeem()`), `BillingManager.kt` (server verify) changed.
- `core/ads/`: `AdsManager.kt`, `AdBanner.kt` (`AdBanner()`, `AdsConsentHost()`, `rememberNaturalBreak()`, `AdsPrivacyOptionsItem()`). **Only these two files use the ads/UMP libraries.**
- `ui/help/LegalScreens.kt` — appends `mn_policy_ads` / `mn_policy_license`.
- `ai-proxy/license/`: `worker.js`, `wrangler.toml`, `keygen.mjs`, `README.md` (Arabic).
- Root templates: `ads.properties.example`, `license.properties.example`.
- Test: `test/.../core/billing/LicenseTokenTest.kt`.

### Conversion fidelity, seal, locked merge (fid)
- `core/convert/` new: `FontMap.kt`, `Decorations.kt`, `ReadingOrder.kt`, `PageFurniture.kt`, `PageFrame.kt`; changed: `ConvModel.kt`, `TextRuns.kt`, `PdfContentExtractor.kt`, `PdfPlatform.kt`, `LayoutAnalyzer.kt`, `TableDetector.kt`, `ConvertPipeline.kt`, `DocxBuilder.kt`, `PptxBuilder.kt`, `HtmlTxtBuilder.kt`, `XlsxBuilder.kt` (all new params defaulted, callers unchanged).
- `core/sign/PagingSeal.kt` (changed), `core/sign/SealGeometry.kt` (new).
- `core/pdf/LockedPdf.kt` (new: `needsPassword`, `checkPassword`, `pageCount`, `decryptToTemp`, `unlockedForInsert`, `mergeLocked`), `ui/tools/FilePasswordDialog.kt` (new).
- Tests: `test/.../core/convert/FidLayoutTest.kt`, `PageGeometryTest.kt`.

`.gitignore`: added `ads.properties`, `license.properties`, `.dev.vars`, `ai-proxy/**/.wrangler/`.

---------------------------------------------------------------------------------------------------------------------
## 2. Hooks for the lead (exact)

### 2.1 `build.gradle.kts`
Dependencies (needed only by `core/ads/*`; if you do not want ads, delete `core/ads/` and skip hooks 2.6 ads items):
```kotlin
implementation("com.google.android.gms:play-services-ads:24.4.0")
implementation("com.google.android.ump:user-messaging-platform:3.2.0")
```
In `defaultConfig`, extend the existing `cloudProps` loop list with:
```kotlin
"CLOUD_BOX_CLIENT_ID" to "boxClientId", "CLOUD_BOX_REDIRECT" to "boxRedirect", "CLOUD_BOX_CLIENT_SECRET" to "boxClientSecret", "CLOUD_BOX_TOKEN_URL" to "boxTokenUrl",
"SHARE_URL" to "shareUrl", "SHARE_TOKEN" to "shareToken",
"ESIGN_URL" to "esignUrl", "ESIGN_TOKEN" to "esignToken", "CONNECTED_URL" to "connectedUrl", "CONNECTED_TOKEN" to "connectedToken"
```
and add after the `proProps` lines:
```kotlin
val adsProps = Properties().apply { rootProject.file("ads.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
buildConfigField("String", "ADS_ENABLED", "\"${adsProps.getProperty("enabled", "false")}\"")
buildConfigField("String", "ADS_BANNER_ID", "\"${adsProps.getProperty("bannerId", "")}\"")
buildConfigField("String", "ADS_INTERSTITIAL_ID", "\"${adsProps.getProperty("interstitialId", "")}\"")
manifestPlaceholders["admobAppId"] = adsProps.getProperty("appId", "ca-app-pub-3940256099942544~3347511713")
val licProps = Properties().apply { rootProject.file("license.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
buildConfigField("String", "LICENSE_URL", "\"${licProps.getProperty("licenseUrl", "")}\"")
buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"${licProps.getProperty("licensePublicKey", "")}\"")
```
All fields are read through `BuildCfg` (reflection), so code compiles without them; the existing `-keep class …BuildConfig` rule covers them.

### 2.2 `AndroidManifest.xml`
- In the `smartdocs` intent-filter, after `<data android:scheme="smartdocs" android:host="review" />`:
  ```xml
  <data android:scheme="smartdocs" android:host="esign" />
  <data android:scheme="smartdocs" android:host="doc" />
  ```
- New `MainActivity` intent-filter (returned e-mail-review comment files):
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
- Inside `<application>` (required as soon as the ads library is a dependency, or the SDK crashes at start):
  ```xml
  <meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="${admobAppId}" />
  ```
- No new permission is needed (sync uses a plain ForegroundInfo without a service type on API ≤ 30; `POST_NOTIFICATIONS` exists).

### 2.3 `ui/Navigation.kt`
Imports: `com.smartdocs.editor.ui.share5.share5Routes`, `com.smartdocs.editor.ui.connected.connRoutes`. After `acctRoutes(nav)` (line ~156):
```kotlin
share5Routes(nav)
connRoutes(nav)
```
Routes: `share5`, `share5/share?uri=&page=`, `share5/reviews`, `share5/review_new?uri=`, `share5/review/{id}`, `share5/views`, `share5/view_new?uri=`,
`share5/view/{id}`, `share5/email_new?uri=`, `share5/returned?uri=`, **`share5/storage` (storage screen, `Share5Routes.STORAGE`)**, `share5/appdata`;
`esign`, `esign/create?uri=`, `esign/req/{id}`, `esign/sign?id=&t=`, `connected?uri=`, `connected/doc/{id}`, `connected/open?id=&uri=`, `enterprise_protected?uri=`.

### 2.4 `ui/reader/ReaderState.kt`
1. `save()`, between the commit and the swap (current lines 315–316):
   ```kotlin
                       AnnotationWriter.commit(context, f, pending, tmp, password)
                       com.smartdocs.editor.core.collab.CollabLive.beforeSave(context, f, tmp, password)   // share6: merge other reviewers' comments
                       swapFile(tmp)
   ```
   (no-op unless `f` is an attached shared-review file; never throws; ≤ 4 s network wait).
2. Connected / protected files — new state next to `var error by mutableStateOf` (line ~75):
   ```kotlin
   var connected by mutableStateOf<com.smartdocs.editor.core.connected.ConnectedStatus?>(null)
   var connectedLocked by mutableStateOf(false)
   var enterpriseInfo by mutableStateOf<com.smartdocs.editor.core.connected.EnterpriseProtection.Info?>(null)
   ```
   In `open(pw)`, between `val f = file ?: app.documents.localFile(...)` and `val s = PdfSession.open(context, f, pw)`:
   ```kotlin
   com.smartdocs.editor.core.connected.EnterpriseProtection.detect(f)?.takeIf { it.blocking }?.let { enterpriseInfo = it; return@launch }
   var openPw = pw
   val mark = com.smartdocs.editor.core.connected.ConnectedDocs.markOf(f)
   if (mark != null) {
       if (mark.protected && pw == null) {
           val st = com.smartdocs.editor.core.connected.ConnectedDocs.checkOnOpen(context, f)
           connected = st
           if (st == null || st.locked) { connectedLocked = true; return@launch }
           openPw = st.password
       } else scope.launch { connected = com.smartdocs.editor.core.connected.ConnectedDocs.checkOnOpen(context, f) }
   }
   ```
   then `val s = PdfSession.open(context, f, openPw)` and `password = openPw`.

### 2.5 `ui/reader/ReaderScreen.kt`
- After line 101 (`LaunchedEffect(state.error) { … }`):
  ```kotlin
  com.smartdocs.editor.ui.cloud.CollabLiveHost(state.file, snack) { op -> state.applyInPlace(state.password, op) }   // share6: live review comments
  state.file?.let { com.smartdocs.editor.ui.share5.ReturnedCommentsHost(it, nav) }                               // share6: returned e-mail-review comments
  val connUri = state.file?.let { android.net.Uri.fromFile(it).toString() }
  LaunchedEffect(state.enterpriseInfo) { if (state.enterpriseInfo != null && connUri != null) { nav.popBackStack(); nav.navigate(com.smartdocs.editor.ui.connected.ConnRoutes.enterprise(connUri)) } }
  LaunchedEffect(state.connectedLocked) { if (state.connectedLocked) { val id = state.connected?.docId ?: state.file?.let { com.smartdocs.editor.core.connected.ConnectedDocs.markOf(it)?.docId }; if (id != null) { nav.popBackStack(); nav.navigate(com.smartdocs.editor.ui.connected.ConnRoutes.connectedOpen(id, connUri)) } } }
  ```
  Above the pages: `state.connected?.let { com.smartdocs.editor.ui.connected.ConnectedBanner(it, nav, connUri) }`.
- Share sheet: add `var showShare5 by remember { mutableStateOf(false) }`; in `ReaderActions(...)` replace
  `share = { scope.launch { outputFile()?.let { ReaderIntents.shareFile(ctx, app, it) } } },` (line ~164, and the same for `onShare` at ~184) with `share = { showShare5 = true },`; near the other dialogs:
  ```kotlin
  if (showShare5) { var f by remember { mutableStateOf<java.io.File?>(null) }; LaunchedEffect(Unit) { f = outputFile() }
      f?.let { com.smartdocs.editor.ui.share5.Share5Sheet(it, if (it == state.file) state.password else null, state.currentPage, nav) { showShare5 = false } } }
  ```
- Reader first-run tips, after `com.smartdocs.editor.ui.reader.annot.Annot2Dialogs(state, nav, snack)` (line ~329):
  ```kotlin
  if (state.session != null && !state.needPassword) com.smartdocs.editor.ui.onboarding.ReaderCoachMarks()   // share6
  ```
- Optional reader menu items: `nav.navigate(ConnRoutes.esignCreate(uri))` ("طلب توقيعات"), `nav.navigate(ConnRoutes.connected(uri))` ("تسجيل كمستند متصل").

### 2.6 Coach-mark targets (`import com.smartdocs.editor.ui.onboarding.CoachIds` + `import com.smartdocs.editor.ui.onboarding.coachTarget`)
- `ui/reader/ReaderToolbars.kt` line 64: `ScrollableTabRow(selectedTabIndex = idx, modifier = Modifier.coachTarget(CoachIds.READER_TABS), edgePadding = 8.dp, divider = {}, containerColor = MaterialTheme.colorScheme.surface) {`
- line 93: `Surface(Modifier.coachTarget(CoachIds.READER_TOOLBAR), tonalElevation = 3.dp, shadowElevation = 8.dp) {`
- optional `ui/reader/view/ReaderTabs.kt` line 174: `Surface(Modifier.coachTarget(CoachIds.READER_DOC_TABS), tonalElevation = 1.dp) {` and line 222: `Box(Modifier.coachTarget(CoachIds.READER_DOC_TABS)) {`.
Steps whose target is missing are skipped; nothing is marked seen until a step was shown.

### 2.7 `SmartDocsApp.onCreate()` (end)
```kotlin
com.smartdocs.editor.core.sync.SyncLive.install(this)                       // near-real-time sync, 15-min jobs, review notifications
com.smartdocs.editor.core.billing.LicenseCheckWorker.schedule(this)         // licence re-check (no-op without LICENSE_URL)
kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { com.smartdocs.editor.core.share5.AppDataSync.autoSyncQuietly(this@SmartDocsApp) }
```
(add `import kotlinx.coroutines.launch`). Without `SyncLive.install`, watching starts the first time the Sync screen opens or a document is saved.

### 2.8 `MainActivity.kt`
- Post-onboarding branch, right after `ReviewPromptHost()`: `com.smartdocs.editor.core.ads.AdsConsentHost()` (no ads ever show without it).
- Banner: `bottomBar = { Column { if (route == Routes.HOME || route == Routes.TOOLS) com.smartdocs.editor.core.ads.AdBanner(); NavigationBar { … } } }`. Never in reader/editors/signing/scanner.
- Line ~55, incoming files:
  ```kotlin
  uri?.let { val ext = (it.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
      nav.navigate(if (ext == "xfdf" || ext == "fdf") com.smartdocs.editor.ui.share5.Share5Routes.returned(it.toString()) else Routes.reader(it.toString())); incoming.value = null }
  ```

### 2.9 `ui/common/Components.kt` ResultSheet (interstitial at a natural break)
```kotlin
val naturalBreak = com.smartdocs.editor.core.ads.rememberNaturalBreak()
ModalBottomSheet(onDismissRequest = { onDismiss(); naturalBreak() }, …)
```
(not on "Open"/"Share"; no-op for Pro, first session, < 4 min since the last one, or not loaded).

### 2.10 Tools tab
- `ToolsScreen.kt` `allTools`: `ToolDef("esign", R.string.cn_tool_esign, Icons.Default.HistoryEdu, 2),` `ToolDef("connected", R.string.cn_tool_connected, Icons.Default.Link, 2),`
- `ToolScreens.kt` `when (id)`: `"esign" -> com.smartdocs.editor.ui.esign.EsignDashboardScreen(nav)`, `"esign_create" -> com.smartdocs.editor.ui.esign.EsignCreateScreen(nav, uri)`, `"connected" -> com.smartdocs.editor.ui.connected.ConnectedHomeScreen(nav, uri)`.
- `ui/tools/MergeSplitTools.kt` `MergeScreen` (locked inputs):
  - state: `val passwords = remember { mutableStateMapOf<String, String>() }`, `val locked = remember { mutableStateMapOf<String, Boolean>() }`, `var asking by remember { mutableStateOf<File?>(null) }`;
  - in `LaunchedEffect(files)` before the page count: `if (!locked.containsKey(f.absolutePath)) locked[f.absolutePath] = withContext(Dispatchers.IO) { LockedPdf.needsPassword(f) }`, count with `LockedPdf.pageCount(f, passwords[f.absolutePath])`;
  - `LaunchedEffect(files, passwords.size, locked.size) { asking = files.firstOrNull { locked[it.absolutePath] == true && passwords[it.absolutePath] == null } }`;
  - `asking?.let { f -> FilePasswordDialog(f, onUnlocked = { pw -> passwords[f.absolutePath] = pw; counts.remove(f.absolutePath); asking = null }, onRemove = { files = files - f; asking = null }) }`;
  - merge button `enabled = files.size >= 2 && files.none { locked[it.absolutePath] == true && passwords[it.absolutePath] == null }` (else show `fd_merge_locked_left`);
  - replace `PdfOps.merge(list, out)` with `com.smartdocs.editor.core.pdf.LockedPdf.mergeLocked(list, list.mapNotNull { f -> passwords[f.absolutePath]?.let { f to it } }.toMap(), out, app.documents.tempDir)`;
  - optional protect switch (`fd_merge_protect`/`fd_merge_protect_pw`): merge to `app.documents.newTemp("merge")`, then `PdfOps.protect(tmp, out, pw, pw)`.
- `ui/pages/OrganizeState.kt`: `fun insertFrom(other: File, at: Int, otherPassword: String?) { apply { src, out -> val u = LockedPdf.unlockedForInsert(other, otherPassword, documents.tempDir); try { PdfOps.insertPagesFrom(src, u ?: other, at, out, password) } finally { u?.delete() } } }`, old 2-arg version calls it with `null`; UI: `if (LockedPdf.needsPassword(other)) FilePasswordDialog(other, onUnlocked = { pw -> state.insertFrom(other, at, pw) }, onRemove = {})`.
- Optional `ui/tools/ConvertFromPdf.kt`: switch for `ConvertOptions(detectHeaders = …)` with `fd_detect_headers`/`fd_detect_headers_sub` (on by default).

### 2.11 Settings agent (ui/settings) — rows/sections
- Section: `com.smartdocs.editor.ui.cloud.SyncSettingsSection()` (auto sync, near-real-time, sync after save, transfer over Wi‑Fi only).
- Row → `nav.navigate(com.smartdocs.editor.ui.share5.Share5Routes.STORAGE)` labels `sh5_storage` / `sh5_storage_sub`.
- Row → `Share5Routes.APPDATA` labels `sh5_appdata` / `sh5_appdata_sub`.
- Privacy/about: `com.smartdocs.editor.core.ads.AdsPrivacyOptionsItem()` (renders only where UMP requires it).

### 2.12 Account agent (ui/account)
- `PaywallScreen.kt` ActivationForm: replace `onResult(ActivationCodes.activate(ctx, code))` with `scope.launch { busy = true; onResult(ActivationCodes.redeem(ctx, code)); busy = false }` + progress (`mn_redeeming`); map `Revoked` → `mn_redeem_revoked`, `DeviceLimit` → `mn_redeem_device_limit`, `Network` → `mn_redeem_network`; under the field `mn_redeem_online_note` if `ActivationCodes.online` else `mn_redeem_offline_note`. **Required once LICENSE_URL is set** (offline `activate` is refused in server mode).
- Optional: `mn_no_ads_benefit` on the paywall when `AdsManager.configured`; `mn_provisional` for a provisional entitlement.
- acct policy text section 5: when the licence server is on, point to `mn_policy_license` instead of "codes are verified on the device".

### 2.14 Settings integration (gaps6 AppSettings) — already done in my code, no hook needed
- **S1 auto sync:** `SyncWorker.doWork` reads `(applicationContext as SmartDocsApp).settings.settings.first().autoSync` at the start of every
  automatic/periodic run and returns success without syncing when it is false; manual "sync now" (`KEY_FORCE`) ignores it. `SyncLive.install`
  also collects the settings flow into `SyncPrefs.appAutoSync`, so near-real-time triggers, sync-after-save and `SyncWorker.request` stop too.
- **S2 transfer network:** `TransferPolicy.allowed(ctx, settings)` is checked before starting the Wi‑Fi transfer server (`WifiScreen`), before
  cloud uploads from the browser (`CloudBrowserScreen`) and at the start of automatic sync runs (retried later when refused). WorkManager
  sync jobs use `NetworkType.UNMETERED` when `settings.transferNetwork == "wifi"` (or the pair is Wi‑Fi-only), otherwise `CONNECTED`;
  `SyncRuntime.networkOk` follows the same rule. (Re-scheduled automatically when the setting changes, via `SyncLive.install`.)
- **Route `storage`:** registered as an alias of `share5/storage` in `share5Routes(nav)` — Settings' `nav.navigate("storage")` works once
  hook 2.3 (`share5Routes(nav)`) is applied.
- The sync screen's own options sheet (`SyncSettingsSection`) remains as finer per-feature switches (live sync, sync after save); they are
  ANDed with the app settings.

### 2.13 Files tab (optional)
Single-PDF share → `nav.navigate(Share5Routes.share(Uri.fromFile(f).toString()))`.

---------------------------------------------------------------------------------------------------------------------
## 3. Feature status

### sync2
| Feature | Status |
|---|---|
| Sync with Google Drive, OneDrive, Dropbox **and Box** | DONE (generic engine over `CloudProvider`) |
| Near-real-time while open | DONE — SAF ContentObserver (5 s debounce) + cloud change feeds polled every 60 s (Drive changes, Graph delta, Dropbox cursor, Box events). PARTIAL for trees whose provider sends no change notifications (they rely on save / 15 min / sync now). Dropbox longpoll not used (60 s polling instead). |
| Every 15 min in background | DONE (two periodic jobs, Wi‑Fi-only group on unmetered) |
| Sync now / sync all now | DONE (expedited, retries failed files) |
| Sync on save | DONE (document source inside a paired tree; needs no reader hook — via SaveHooks) |
| Per-file status queue with retry | DONE (pending/running/synced/failed, back-off, manual retry, retry all) |
| Conflict rules | DONE (both changed → conflict copy; deletions never beat edits) + cloud working-copy write-back conflict copy |
| Shared review keeps others' comments | DONE — needs hook 2.4.1 + 2.5 (CollabLive) |
| "N new comments" | DONE — reader snackbar with "عرض" (merge now) + background notification every 30 min |
| First-run tips in the reader | DONE — needs hooks 2.5 (ReaderCoachMarks) + 2.6 targets |
| Long-sync progress notification | PARTIAL — live progress on the Sync screen; notification only as the expedited foreground info on API ≤ 30 |

### share5
| Feature | Status |
|---|---|
| Share sheet (whole/current page/range; as-is, compressed, without comments, flattened, images ≤ 60 pages, comments-only XFDF; share menu or e-mail) | DONE (hook 2.5) |
| Shared review creation (deadline, reviewers, permissions, anonymous, invitations incl. QR) | DONE |
| Browser reviewers (`/w/<id>`, sticky notes into the collab KV) | DONE (worker) |
| Reviewer status, reminders (auto 24 h before deadline + manual), extend/end/reopen/revoke | DONE (revoke/reopen/reminders need `shareUrl`) |
| Anonymous option / consent | DONE (`ReviewConsentGate`, pseudonym in published comments) |
| E-mail review round-trip (commented PDF or FDF/XFDF → offer to merge) | DONE (hooks 2.2, 2.5, 2.8) |
| View-only links (expiry, password + lockout, download toggle, view/visitor/download counts) | DONE — download "off" is not DRM |
| App data sync via Drive appDataFolder (signatures, stamps, fill profile, tags, favourites) | DONE — merge only, deletions do not propagate |
| Storage screen (cache sizes, clear, unlink accounts incl. Box) as route `share5/storage` | DONE |
| Poll-interval setting, take over others' comments | NOT DONE (no such state in our model) |

### conn
| Feature | Status |
|---|---|
| Signature requests: signers in order or parallel, field placement, per-signer links | DONE |
| Sign in app / on a web page | DONE |
| Status tracking, reminders (cron), decline, cancel, delete | DONE |
| Final audit page (who/when/IP/device/UA, SHA-256 original + final, verification page `/v`) | DONE (composed on the sender's device) |
| Connected documents: id stamp, versions, newer-version alert, open counts, approve/block readers, revoke, protected mode with offline key cache | DONE (hooks 2.4.2, 2.5) |
| Microsoft-protected (RMS/MIP/IRM, .pfile/.ppdf, labels) recognition + explanation screen | DONE (heuristic scan) |
| Decrypting rights-managed files | NOT FEASIBLE (needs the organisation's keys) |

### monet
| Feature | Status |
|---|---|
| Licence server: Play purchase verification (+ack, RTDN void/expire), activation codes (device limit, revocation), admin | DONE (`ai-proxy/license`) |
| Ed25519-signed licences verified in the app, grace rules, 3-day re-check worker | DONE |
| Code redemption through the server in the UI | PARTIAL — account agent hook 2.12 |
| AdMob banner + interstitial with UMP consent, OFF until `ads.properties` has ids, never in Pro, test ids in debug | DONE in code — needs deps + manifest meta-data + hooks 2.8/2.9 |
| Privacy policy text | DONE (appended in LegalScreens) |

### fid
| Item | Status |
|---|---|
| PDF→Word/Excel/PowerPoint/HTML: colour/font/size/bold/italic, underline/strike, fills, XY-cut reading order (RTL columns), headers/footers (DOCX header/footer + PAGE field), rotated/cropped pages | DONE (rebuilt) |
| Paging seal on rotated pages | DONE |
| True redaction | DONE earlier by search5 (`core/redact/TrueRedactor.kt`); not duplicated |
| Merging password-protected PDFs | DONE in core (`LockedPdf`, `FilePasswordDialog`) — screen wiring = hook 2.10 |

### Extras
| Feature | Status |
|---|---|
| Box cloud provider (OAuth + browse/upload/download/folders, sync, links) | DONE — needs Box app + collab worker secrets |
| Wi‑Fi transfer: "Wi‑Fi only" vs every network | DONE (`WifiScreen` switch; Wi‑Fi-only binds the Wi‑Fi/hotspot address). Cloud transfers: global "Wi‑Fi only" in `SyncSettingsSection` |
| Copy link for cloud files | DONE — Drive (anyone-with-link permission when allowed + webViewLink), OneDrive (anonymous view link / web URL), Dropbox (public shared link / preview URL), Box (open shared link / app URL) |
| OneDrive for Business | DONE with the same Graph code (the `common` authority already accepts work/school accounts; same `/me/drive` API) |
| SharePoint document libraries | NOT FEASIBLE — needs `Sites.Read.All`/`Sites.ReadWrite.All`, which most tenants restrict to admin consent; only the user's own OneDrive for Business is reachable |

---------------------------------------------------------------------------------------------------------------------
## 4. What the user must configure / deploy
All `*.properties` files live in the project root and are git-ignored; secrets go in with `wrangler secret put` (never in `wrangler.toml`).
1. **Box**: Box Developer Console → Custom App → OAuth 2.0; redirect `https://<collab-worker>/oauth/box`; scopes read+write. In `ai-proxy/collab`: `wrangler secret put BOX_CLIENT_ID`, `wrangler secret put BOX_CLIENT_SECRET`, `wrangler deploy`. `cloud.properties`: `boxClientId=`.
2. **Dropbox links**: enable `sharing.write` + `sharing.read` scopes in the Dropbox App Console (users reconnect once).
3. **Share worker** (`ai-proxy/share/README.md`): `wrangler kv namespace create SHARE`, bind the same `COLLAB` KV id (and R2 bucket) as the collab worker, vars `PUBLIC_URL`, `COLLAB_URL`, `wrangler secret put APP_TOKEN`, optional `MAIL_API_KEY` + `MAIL_FROM`, `wrangler deploy`. `cloud.properties`: `shareUrl=`, `shareToken=`.
4. **Google Cloud Console**: add scope `https://www.googleapis.com/auth/drive.appdata` to the consent screen of the Drive Android OAuth client (app-data sync).
5. **esign worker** (`ai-proxy/esign/README.md`): `wrangler kv namespace create ESIGN`, `wrangler secret put APP_TOKEN`, optional mail vars (`PUBLIC_URL`, `MAIL_API_URL`, `MAIL_FROM`, secret `MAIL_API_KEY`), `wrangler deploy`. **connected worker**: `wrangler kv namespace create DOCS`, `wrangler secret put APP_TOKEN`, `wrangler deploy`. `cloud.properties`: `esignUrl=`, `esignToken=`, `connectedUrl=`, `connectedToken=`.
6. **License worker** (`ai-proxy/license/README.md`): `node keygen.mjs`; `wrangler kv namespace create LICENSES`; secrets `SIGNING_KEY`, `CODE_SECRET` (the old `pro.properties` codeSecret so sold codes keep working), `ADMIN_KEY`, `PLAY_SERVICE_ACCOUNT` (JSON), `RTDN_SECRET`; `PUBLIC_KEY` in `[vars]`; `wrangler deploy`. Play service account with "View financial data" + "Manage orders and subscriptions"; Pub/Sub topic + push subscription `https://<worker>/rtdn?secret=<RTDN_SECRET>`. App: `license.properties` (`licenseUrl=`, `licensePublicKey=`), then empty `codeSecret` in `pro.properties`.
7. **AdMob**: create app + banner + interstitial units; copy `ads.properties.example` → `ads.properties` (`enabled=true`, `appId`, `bannerId`, `interstitialId`); consent messages in AdMob → Privacy & messaging; publish `app-ads.txt`; Play Console: Contains ads = Yes, Data safety (Advertising ID), target audience 13+.

---------------------------------------------------------------------------------------------------------------------
## 5. Limitations (main)
- Change feeds are drive-wide; our own uploads cause at most one extra no-op pass (pairs synced in the last 20 s are skipped).
- Dropbox uploads > 150 MB still unsupported (no upload sessions); Box uses chunked sessions above 50 MB.
- Shared review: if I edited someone else's comment locally and they edit it remotely, their version wins at merge; `beforeSave` merges only for documents attached through `CollabLiveHost`.
- Signature requests: link holders can sign (no OTP); the server stores the final hash but does not rebuild the PDF.
- Connected documents: revocation only affects protected copies after their offline window; a key once obtained opens that copy forever.
- Licences: KV is eventually consistent (~1 min for revocations); a modified APK can bypass any client gate.
- Header/footer detection needs ≥ 2 pages; minority-direction text becomes one run.

---------------------------------------------------------------------------------------------------------------------
## 6. Compile status
- One combined type-check (`kc6.sh all --ads`) of core/cloud, core/sync, core/collab, core/share5, ui/share5, core/esign, core/connected,
  ui/esign, ui/connected, core/billing, core/ads, ui/help/LegalScreens.kt, ui/cloud, ui/onboarding/CoachMarks.kt, core/files/WifiTransferServer.kt:
  **0 errors**. Re-checked after the settings integration (2.14) together with the new `core/data/SettingsStore.kt` + `core/gaps6/G6Prefs.kt`:
  0 errors in share6 files (the only error, `G6Prefs.kt: ToolWizard`, is because gaps6's ToolWizard.kt was not in that file list).
- fid: `kc6.sh fid` over all of core/convert, PagingSeal/SealGeometry, LockedPdf, FilePasswordDialog: 0 errors (ConvertFromPdf.kt, AccessFix.kt,
  ExportPlus.kt callers were not in the batch because the cached classes.jar lacks newer tools5 classes; they only use defaulted parameters).
- Scripts: `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\share6\kc6.sh` (type-check),
  `...\share6\t6.sh` (JUnit), worker tests under `...\share6\{share5,conn,monet}\*.mjs`, `...\share6\boxtok.mjs`.
