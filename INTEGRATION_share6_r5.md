# INTEGRATION share6 (round 5) + office5 hooks

Applied by the integration agent from `MODULE_share6_r5.md` section 2 and (on the lead's request) `MODULE_office5_r5.md` hooks 1-8.
All edits are additive and marked with `// share6` / `// office5` comments. Line endings stay LF and no invisible Unicode was added.

## share6 hooks

| Hook | Status | Where / notes |
|---|---|---|
| 2.1 build.gradle.kts | APPLIED | `play-services-ads:24.4.0` and `user-messaging-platform:3.2.0`. The cloudProps list has Box/SHARE/ESIGN/CONNECTED. Added `adsProps` with ADS_ENABLED (default `"false"`), ADS_BANNER_ID and ADS_INTERSTITIAL_ID. `manifestPlaceholders["admobAppId"]` defaults to the test id `ca-app-pub-3940256099942544~3347511713`. Added `licProps` with LICENSE_URL and LICENSE_PUBLIC_KEY. Verified: the generated BuildConfig has `ADS_ENABLED = "false"`, so ads stay off with no `ads.properties`. |
| 2.2 AndroidManifest.xml | APPLIED | `smartdocs://esign` and `smartdocs://doc` are in the smartdocs filter. New VIEW filter for xfdf/fdf (content+file). The `com.google.android.gms.ads.APPLICATION_ID` meta-data (`${admobAppId}`) is inside `<application>`; the merged debug manifest has the test app id. |
| 2.3 ui/Navigation.kt | APPLIED | Imports added. `share5Routes(nav)` and `connRoutes(nav)` come after `acctRoutes(nav)`. |
| 2.4 ReaderState.kt | APPLIED | `CollabLive.beforeSave` sits between commit and `swapFile`. The `connected` / `connectedLocked` / `enterpriseInfo` state is added, and the open() logic opens with `openPw`. Small change: `EnterpriseProtection.detect(f)` and `ConnectedDocs.markOf(f)` run in `withContext(Dispatchers.IO)` because they read files; same behaviour. |
| 2.5 ReaderScreen.kt | APPLIED | CollabLiveHost, ReturnedCommentsHost, the enterprise/connected redirects, and ConnectedBanner (last row of the top bar column, above the pages). `showShare5` + Share5Sheet replace both share lambdas (`actions.share` and quick `onShare`). ReaderCoachMarks comes after Annot2Dialogs. Optional menu items are added: "طلب توقيعات" (`cn_tool_esign`, opens `ConnRoutes.esignCreate`) and "تسجيل مستند متصل" (`cn_register`, opens `ConnRoutes.connected`). |
| 2.6 Coach-mark targets | APPLIED | `ReaderToolbars.kt`: READER_TABS on the ScrollableTabRow and READER_TOOLBAR on the BottomToolbar Surface. Optional `ReaderTabs.kt`: READER_DOC_TABS on the tablet strip Surface and the phone action Box. Imports added. |
| 2.7 SmartDocsApp.onCreate | APPLIED | `SyncLive.install`, `LicenseCheckWorker.schedule`, and `AppDataSync.autoSyncQuietly` on IO, each wrapped in `runCatching` so they cannot crash startup. Added `import kotlinx.coroutines.launch`. |
| 2.8 MainActivity / bottom bar | APPLIED | `AdsConsentHost()` comes after `ReviewPromptHost()`. Incoming `.xfdf`/`.fdf` go to `Share5Routes.returned`, everything else to `Routes.reader`. The bottom bar is built in `ui/Navigation.kt` `AppNavHost`, so the banner is there: `Column { if (route == HOME or TOOLS) AdBanner(); NavigationBar {...} }`. It shows only on phone layouts; the tablet navigation rail has no bottom bar, so no banner there. |
| 2.9 ResultSheet | APPLIED | `rememberNaturalBreak()`; the interstitial triggers only on sheet dismiss, not on Open/Share. |
| 2.10 Tools tab | APPLIED | Added ToolDef `esign` and `connected` (group 2). ToolScreens has `esign`, `esign_create` and `connected`. MergeScreen: passwords/locked/asking state, LockedPdf.needsPassword/pageCount on IO, FilePasswordDialog, the merge button is disabled while locked files remain (shows `fd_merge_locked_left`), and it merges with `LockedPdf.mergeLocked`. The optional protect switch is added (`fd_merge_protect`/`_pw`: merge to temp, then `PdfOps.protect`), plus the `fd_merge_unencrypted` note. OrganizeState: new 3-arg `insertFrom(other, at, otherPassword)` using `unlockedForInsert` (on IO, temp deleted); the 2-arg version delegates with `null`. OrganizeScreen InsertDialog detects a locked file (produceState on IO) and shows `FilePasswordDialog`, then `insertFrom(other, at, pw)`. |
| 2.10 optional ConvertFromPdf `detectHeaders` switch | SKIPPED | Optional. `ConvertOptions.detectHeaders` defaults to true, so behaviour is already right. |
| 2.11 Settings | APPLIED | `SyncSettingsSection()` comes right after `G6SyncCard`. One storage row is kept: the existing Gaps6 row now goes to `Share5Routes.STORAGE` (no duplicate). New row `sh5_appdata`/`sh5_appdata_sub` opens `Share5Routes.APPDATA`. `AdsPrivacyOptionsItem()` is in the About card. |
| 2.12 Account (PaywallScreen) | APPLIED | ActivationForm calls `scope.launch { busy; ActivationCodes.redeem(ctx, code) }`, with a progress indicator + `mn_redeeming` and the button disabled while busy. Revoked, DeviceLimit and Network map to `mn_redeem_*`. The online/offline note is shown under the field. The gaps6 `ActivatedDevices.recordActivation` call is kept. Optional `mn_no_ads_benefit` row is shown when `AdsManager.configured`. |
| 2.12 optional `mn_provisional` + acct policy section 5 | SKIPPED | Optional. `LegalScreens` already appends `mn_policy_license` (share6 code). |
| 2.13 Files tab | APPLIED | A single-file share of a PDF opens `Share5Routes.share(...)`. Other files and multi-select still use the system share. |
| 2.14 Settings integration | NOTHING NEEDED | Confirmed. The `"storage"` alias is registered in `share5Routes` (Share5Routes.kt line 71), which is now in the NavHost, and the settings row uses `Share5Routes.STORAGE` directly. SyncWorker/SyncLive/TransferPolicy read AppSettings in share6 code. |

## office5 hooks

| Hook | Status | Where / notes |
|---|---|---|
| 1 Office route | APPLIED | `composable(OfficeRoutes.ROUTE)` comes after the `Routes.TOOL` composable in Navigation.kt. |
| 2 READER dispatch | APPLIED | `remember(u) { OfficeRoutes.isOffice(u) }` chooses OfficeScreen or ReaderScreen. |
| 3 Manifest VIEW MIME types | APPLIED | 13 Office MIME types are in the PDF VIEW filter. `text/plain` is not included (optional). |
| 4 DocumentRepository.localFile | APPLIED | Keeps the real extension (2-5 chars, else pdf). |
| 5 Files listing | APPLIED | `listFolder` has the extensions. `FilesScreen.scanTree` accepts Office kinds. The name fallback stays `"document.pdf"` because a null name only matches through the PDF MIME type. The optional `scanDevicePdfs` MIME query is SKIPPED. |
| 6 General open pickers | APPLIED | Used the "add MIMES" variant, not `rememberOfficeOpener`, so PDFs still open. `rememberPdfPicker` has a new defaulted `mimes` parameter; PDF tools are unchanged. Home and Files "open" pass `application/pdf` + `OfficeRoutes.MIMES`, and the reader dispatch sends Office files to the editor. |
| 7 WriteBackUi | APPLIED | For non-PDF files, re-pick uses `*/*` and save-copy uses a second `CreateDocument("*/*")` launcher. PDFs are unchanged. |
| 8 Tools entry | APPLIED | `ToolDef("office", R.string.o5_tool_open, Icons.Default.Description, 0)`, and ToolScreens has `"office" -> OfficeStartScreen(nav)`. |

## Compile
`./gradlew :app:compileDebugKotlin --console=plain` ran once after all hooks: **BUILD SUCCESSFUL in 11m 28s, 0 errors** (only pre-existing deprecation warnings).
Ran: generateDebugBuildConfig, processDebugMainManifest (merged manifest has the AdMob APPLICATION_ID with the test id), processDebugResources, compileDebugKotlin.
Log: `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\integ_share6_compile.log`.

## Files touched
app/build.gradle.kts, app/src/main/AndroidManifest.xml, SmartDocsApp.kt, MainActivity.kt, ui/Navigation.kt, ui/reader/ReaderState.kt,
ui/reader/ReaderScreen.kt, ui/reader/ReaderToolbars.kt, ui/reader/view/ReaderTabs.kt, ui/common/Components.kt, ui/common/WriteBackUi.kt,
ui/tools/ToolsScreen.kt, ui/tools/ToolScreens.kt, ui/tools/MergeSplitTools.kt, ui/pages/OrganizeState.kt, ui/pages/OrganizeScreen.kt,
ui/settings/SettingsScreen.kt, ui/settings/Gaps6SettingsSection.kt, ui/account/PaywallScreen.kt, core/files/DocumentRepository.kt,
ui/files/FilesScreen.kt, ui/home/HomeScreen.kt.
