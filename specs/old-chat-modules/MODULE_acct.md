# MODULE_acct — onboarding, help, account/Pro, cloud, sync, Wi‑Fi, shared review

Written without running Gradle (round-3 rules). Library APIs were checked with `javap` against
play-services-auth 22.0.0, billing 9.1.0 (+ktx), review 2.0.2, pdfbox-android 2.0.27.
Tested outside Gradle: the QR encoder (compiled with kotlinc and decoded with OpenCV, versions 1–10, all decoded correctly),
activation codes (a Kotlin verifier against the Python generator below), and the collab worker (node, mock KV: create/comment/meta/file/close→410/oauth bounce).

## Files
**Core**
- `core/review/AcctPrefs.kt`: onboarding/consent (`consent_version`, `analytics_opt_in`, default OFF), coach-mark flags, install time, profile photo. Also `BuildCfg` (reads optional BuildConfig fields by reflection).
- `core/review/ReviewPrompt.kt`: Play In-App Review gate (≥15 successful actions, ≥3 days since install, ≤ once / 90 days) and `ReviewPromptHost()`.
- `core/billing/Pro.kt`: `ProProducts` ("pro_monthly", "pro_yearly"), `Pro.ENFORCE = false`, `Pro.isActive` / `isActiveFlow` / `allows(ctx)`, cached entitlement.
- `core/billing/BillingManager.kt`: Play Billing v9 client (plans, purchase, restore, acknowledge, `refreshAsync()`).
- `core/billing/ActivationCodes.kt`: offline HMAC codes (format and limits are in the KDoc).
- `core/cloud/CloudHttp.kt`, `CloudModels.kt`, `GoogleDrive.kt` (AuthorizationClient + REST v3), `OAuthPkce.kt` (Custom Tabs + PKCE, `CloudConfig`), `OneDrive.kt` (Graph), `Dropbox.kt` (API v2), `CloudLinks.kt` (local file ↔ cloud item map and **`SaveHooks`**).
- `core/sync/SyncStore.kt`, `SyncEngine.kt` (two-way, conflict copies "(نسخة متعارضة)"), `SyncWorker.kt` (hourly WorkManager; Wi‑Fi-only pairs use UNMETERED).
- `core/collab/CollabAnnots.kt` (annotation ⇄ JSON of its PDF dictionary, merged by /NM), `CollabClient.kt`, `CollabStore.kt`, `CollabFlows.kt`.
- `core/files/WifiTransferServer.kt` (rewritten): 6-digit PIN login with an HttpOnly session cookie, lockout after 5 wrong tries, idle auto-stop (10 min, `onAutoStop`), uploads streamed to disk. **Constructor is now `WifiTransferServer(root, port = 8080, pin = newPin(), idleTimeoutMs)`; the PIN is ON by default.**

**UI**
- `ui/onboarding/OnboardingScreen.kt`: 4 pager pages (features, Arabic-first, privacy, consent and stats toggle). Back cannot skip it.
- `ui/onboarding/CoachMarks.kt`: `CoachMark(key, steps)`, `Modifier.coachTarget(id)`, `CoachMarkRegistry`, `CoachIds`, `HomeCoachMarks()`, `ReaderCoachMarks()`.
- `ui/help/HelpScreen.kt` (FAQ, feedback mail with device info + optional crash log, rate, reset tips, activation, analytics toggle, about), `ui/help/LegalScreens.kt` (privacy policy AR/EN, licenses).
- `ui/account/AccountScreen.kt` (rewritten: local profile + photo, Pro card, cloud/sharing links), `AcctRoutes.kt` (routes + `NavGraphBuilder.acctRoutes(nav)`), `PaywallScreen.kt` (+ `ActivationScreen`).
- `ui/cloud/CloudScreen.kt` (rewritten), `CloudBrowserScreen.kt`, `SyncScreen.kt` (+ `DriveFolderPicker`), `WifiScreen.kt` (PIN, QR, idle countdown, keeps the screen on), `ReviewScreen.kt`, `QrCode.kt` (encoder: byte mode, ECC M, v1–10, `QrImage`), `DeepLinks.kt`.
- Strings: `res/values/strings_acct.xml` and `res/values-en/strings_acct.xml` (prefix `ac_`).

**Server**: `ai-proxy/collab/worker.js`, `wrangler.toml`, `README.md` (Arabic deployment guide).

## Routes (registered by `acctRoutes(nav)`, already added to `ui/Navigation.kt`)
`help`, `help/privacy`, `help/licenses`, `paywall`, `activation`, `cloud_browser/{provider}` (drive|onedrive|dropbox), `sync`, `wifi_transfer`, `review?id={id}&c={c}`.

## Edits made outside the module (minimal)
- `MainActivity.kt`:
  - shows the onboarding gate (`AcctPrefs.onboardingDone`)
  - `DeepLinkHandler(nav)` and `ReviewPromptHost()` after AppNavHost
  - `BillingManager.refreshAsync()` in onCreate
  - `smartdocs://` URIs are routed to `DeepLinks.pending` in handleIntent.

  The coordinator's `onKeyDown`, `openedByIntent` and `RestoreLastSession` are kept.
- `ui/Navigation.kt`: imports `acctRoutes`, calls `acctRoutes(nav)` at the end of the NavHost, and adds `HomeCoachMarks()` after `HomeScreen(nav)`.

## For the lead to wire (files I don't own)
1. **Manifest** (`MainActivity`): add a deep-link filter:
   ```xml
   <intent-filter>
       <action android:name="android.intent.action.VIEW" />
       <category android:name="android.intent.category.DEFAULT" />
       <category android:name="android.intent.category.BROWSABLE" />
       <data android:scheme="smartdocs" android:host="oauth" />
       <data android:scheme="smartdocs" android:host="review" />
   </intent-filter>
   ```
   No new permissions are needed (INTERNET is already declared; WorkManager needs nothing).
2. **Old screens**:
   - `ToolScreens` "wifi" must route to `com.smartdocs.editor.ui.cloud.WifiScreen(nav)`. The old `MiscTools.WifiTransferScreen` does not display the PIN that the server now requires.
   - "help" should use `nav.navigate("help")` (`AcctRoutes.HELP`). `SettingsScreen` line ~133 still uses `Routes.tool("help")`.
3. **ReaderState hook**: after every successful save (where `app.documents.writeBack(f)` is called), add:
   ```kotlin
   com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f)
   ```
   This uploads cloud working copies back to their cloud item, publishes shared-review comments, and counts a "successful action" for the rating prompt. Tools/exports may call `com.smartdocs.editor.core.review.ReviewPrompt.recordSuccess(context)` after a successful result.
4. **Reader coach marks (optional)**:
   - Add `Modifier.coachTarget(CoachIds.READER_TABS)` on the tab row and `Modifier.coachTarget(CoachIds.READER_TOOLBAR)` on the tool bar.
   - Call `ReaderCoachMarks()` once in ReaderScreen.
5. **Home coach marks**: add `Modifier.coachTarget(CoachIds.HOME_OPEN)` and `Modifier.coachTarget(CoachIds.HOME_SCAN)` on the open/scan buttons in HomeScreen (`HomeCoachMarks()` is already called from Navigation). Without targets nothing shows and nothing is marked as seen.
6. **build.gradle.kts** `defaultConfig` (fields are read by reflection, so the code compiles without them):
   ```kotlin
   val cloudProps = Properties().apply { rootProject.file("cloud.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
   buildConfigField("String", "CLOUD_ONEDRIVE_CLIENT_ID", "\"${cloudProps.getProperty("onedriveClientId", "")}\"")
   buildConfigField("String", "CLOUD_DROPBOX_APP_KEY", "\"${cloudProps.getProperty("dropboxAppKey", "")}\"")
   buildConfigField("String", "CLOUD_ONEDRIVE_REDIRECT", "\"${cloudProps.getProperty("onedriveRedirect", "")}\"")
   buildConfigField("String", "CLOUD_DROPBOX_REDIRECT", "\"${cloudProps.getProperty("dropboxRedirect", "")}\"")
   buildConfigField("String", "COLLAB_URL", "\"${cloudProps.getProperty("collabUrl", "")}\"")
   buildConfigField("String", "COLLAB_TOKEN", "\"${cloudProps.getProperty("collabToken", "")}\"")
   buildConfigField("String", "SUPPORT_EMAIL", "\"${cloudProps.getProperty("supportEmail", "")}\"")
   val proProps = Properties().apply { rootProject.file("pro.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
   buildConfigField("String", "PRO_CODE_SECRET", "\"${proProps.getProperty("codeSecret", "")}\"")
   ```
7. **proguard-rules.pro**: `-keep class com.smartdocs.editor.BuildConfig { *; }` is required, otherwise R8 strips the fields read by reflection. Alternatively, replace `BuildCfg.string("X")` with `BuildConfig.X` once the fields exist.

## Configuration files (project root, keep out of git)
- `cloud.properties`:
  - `onedriveClientId=`: Azure app registration, "Mobile and desktop" platform, redirect `smartdocs://oauth/onedrive`, delegated scopes Files.ReadWrite, User.Read, offline_access.
  - `dropboxAppKey=`: Dropbox App Console, PKCE (no secret in the app), redirect `smartdocs://oauth/dropbox`. If a provider refuses custom schemes, register `https://<collab-worker>/oauth/<provider>` and set `onedriveRedirect=` / `dropboxRedirect=` to it; the worker forwards to the app.
  - `collabUrl=` / `collabToken=`: see `ai-proxy/collab/README.md`.
  - `supportEmail=`: feedback address. If empty, feedback opens a share sheet instead.
  - A provider with an empty key is shown as "not configured" and disabled. Shared review is disabled while `collabUrl` is empty.
- **Google Drive** needs no key in the app. In Google Cloud Console:
  - Enable the Drive API.
  - Create an **Android** OAuth client with the package name and the SHA-1 of the release keystore (and of the debug one, for testing).
  - Configure the consent screen.
  - `drive.readonly` is a restricted scope: publishing needs Google verification. `GoogleDrive.FULL_ACCESS = true` switches to the full `drive` scope, which is needed to overwrite files the app did not create.
- `pro.properties`: `codeSecret=<long random string>`. If empty, activation codes are disabled. Code generator (keep it private):
  ```python
  import hmac, hashlib, os, time, sys
  A = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
  def gen(secret, plan, days):  # plan 1=month 2=year 3=lifetime; days = validity from today (ignored for lifetime)
      day = 0 if plan == 3 else int(time.time() // 86400) + days
      b = bytes([plan, (day >> 8) & 255, day & 255]) + os.urandom(4)
      b += hmac.new(secret.encode(), b, hashlib.sha256).digest()[:8]
      n = int.from_bytes(b, "big"); s = "".join(A[(n >> (5 * (23 - i))) & 31] for i in range(24))
      return "-".join(s[i:i+4] for i in range(0, 24, 4))
  print(gen(sys.argv[1], int(sys.argv[2]), int(sys.argv[3])))
  ```
- **Play Console**: two auto-renewing subscriptions, `pro_monthly` and `pro_yearly` (one base plan each, optional free-trial offer). Set `Pro.ENFORCE = true` only after they are live. Feature code should gate with `Pro.allows(context)`.

## Limitations
- **Billing**: verification happens on the client only; there is no server check of purchase tokens.
- **Activation codes**:
  - They are device-agnostic and cannot be revoked offline, and the secret can be extracted from the APK.
  - Expiry is fixed when the code is generated, not when it is activated.
- **Analytics**: the opt-in is only stored. No collector ships.
- **Cloud**:
  - Write-back overwrites the cloud file; there is no conflict check against cloud changes made while the file was open.
  - Dropbox uploads are limited to 150 MB (no upload sessions).
  - Google Docs/Sheets/Slides files are exported as PDF on open and are read-only.
- **Sync**:
  - Google Drive only, polled hourly.
  - Subfolders are synced to a depth of 6.
  - Deletions are mirrored when the other side is unchanged (Drive deletions go to the trash).
  - The first sync treats same-size files as identical.
- **Wi‑Fi transfer**:
  - Plain HTTP on the LAN (the PIN protects access, not confidentiality).
  - The server runs only while the screen is open (no foreground service).
- **Shared review**:
  - Anyone with the link and code can read and comment; there are no accounts.
  - Comments are polled every 30 s while the review screen is open; the server's KV storage is eventually consistent (up to ~60 s).
  - Remote comments are merged into the local PDF file. If that file is open in the reader at the same moment, the reader will not show them until it reopens, and saving from the reader then may overwrite the merged comments. Open the document from the review screen after refreshing.
  - Only the author can edit or delete their own comments.
  - Encrypted PDFs are refused; files are limited to 20 MB.
- **Coach marks** use a full-window Popup; untested on devices with display cutouts.
