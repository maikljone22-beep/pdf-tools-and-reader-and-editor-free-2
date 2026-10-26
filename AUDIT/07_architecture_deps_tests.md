# 07 — Architecture, code quality, concurrency/lifecycle, dependencies, tests, release readiness (prefix ARCH)

## Scope & method

**What I read.** The snapshot at `D:\pdf\audit-wt` (commit `da74d31`; 24 commits on top of the `16e5783 Baseline: SmartDocs v0.4.0 as received`). I read:
- the build and config files: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`, `app/proguard-rules.pro`, `AndroidManifest.xml`, `res/xml/*` and `.github/workflows/build.yml`;
- the core runtime files: `SmartDocsApp.kt`, `MainActivity.kt`, `core/pdf/PdfSession.kt`, `ui/reader/ReaderState.kt`, `core/data/AppDatabase.kt`, `core/ocr/OcrEngine.kt`, `core/ads/AdsManager.kt`, `core/billing/*`, `core/cloud/OAuthPkce.kt`, `core/search/IndexWorker.kt`, `core/redact/Sanitizer.kt`, `ui/forms5/FormFillState.kt`, `ui/view5/V5State.kt` and `ui/help/LegalScreens.kt`, plus many call sites found with grep;
- the 47 unit-test files under `app/src/test`;
- `REMAINING_PLAN.md`.

**In-flux areas.** I read `core/convert6`, `ui/convert6`, `core/pageflip`, `ui/tools/*` and `ui/home/*` in `D:\pdf\SmartDocs-project-v0.4.0`. Findings there are marked "(in-flux area)".

**Static metrics.** I gathered these with grep and awk over `app/src/main/java`:
- 629 Kotlin files and about 93.4k lines;
- 1,173 `runCatching`, 111 `catch (e: Exception|Throwable)` and 0 `Log.*` calls;
- 178 `!!` and about 93 unsafe `as` casts;
- 63 `getSharedPreferences(` calls across 26 distinct preference files;
- 0 ViewModels, 54 `rememberSaveable` and 1,306 `mutableStateOf`;
- 55 `PDDocument.load(...)` calls spread over 38 files (117 files import PdfBox).

**Dead-code check.** I ran a script that lists every public top-level class, object or function whose name never appears in another file. That gave 156 candidates, and I checked the important ones by hand.

**Native libraries.** I copied the `pdfiumandroid-core-2.0.1.aar` and `tesseract4android-openmp-4.8.0.aar` files from `D:\gradle-home` into my scratch folder and parsed the ELF LOAD-segment alignment of every arm64-v8a and x86_64 `.so` with a small Python script.

**What I could not verify.** I ran no Gradle build or tests (the lead runs `testDebugUnitTest lintDebug`). There is no device, so I could not check runtime behaviour. Play-policy dates and CVE applicability come from my own knowledge and need checking in Play Console or an advisory database (marked PROBABLE). I did not check the native-lib alignment of ML Kit / Play-services AARs or the release R8 output. I did not look at `app/build` (as instructed).

## Capability inventory (engineering capabilities)

| Capability | Status | Evidence |
|---|---|---|
| Module structure | partial | One `:app` module (`settings.gradle.kts:8`). 35 `core/*` and 33 `ui/*` packages, layered by **delivery round** (`edit`→`edit5`, `forms`→`forms5`, `office`→`office5`, `ai`→`ai5`, `share5`, `tools5`, `view5`, `gaps6`, `convert6`) rather than by domain. |
| DI | stub | A service locator: `SmartDocsApp.instance` plus `lateinit db/settings/documents` (`SmartDocsApp.kt:11-31`) and 367 top-level `object`s. No Hilt or Koin. |
| Screen state | partial | No ViewModels. State-holder classes are created with `remember` and bound to `rememberCoroutineScope()` (`ReaderState.kt:651-657`). Add-ons attach through `WeakHashMap<ReaderState, …>` side-cars (`FormFillState.kt:39`, `V5State.kt:105`), which leak (ARCH-03). |
| PDFium encapsulation | works | `io.legere` is imported only in `core/pdf/PdfSession.kt` (plus a licence string). |
| PdfBox encapsulation | broken | 117 files import `com.tom_roush`, 15 of them in `ui/`. There are 55 direct `PDDocument.load` calls and none uses `MemoryUsageSetting`. |
| DB migrations | broken | Room `version = 1, exportSchema = false, fallbackToDestructiveMigration()` (`AppDatabase.kt:154-168`). |
| Error observability | broken | 0 `Log` calls. CrashLog only writes a local file (`core/util/CrashLog.kt`). No mapping-file retention for R8 builds. |
| Unit tests | partial | 47 files and 384 `@Test` methods, all pure-logic JVM tests. No PDF fixture files, no `src/test/resources` folder and no `androidTest` folder. |
| Instrumentation / UI tests | missing | No `app/src/androidTest`, and no `testInstrumentationRunner` in `defaultConfig`. |
| CI | partial | `.github/workflows/build.yml`: assembleDebug plus testDebugUnitTest on push to main/master only. No PR trigger, no lint, no release/R8 build, no wrapper validation. |
| Release signing | broken | `signingConfigs` is created only when `keystore.properties` exists (`app/build.gradle.kts:52-66`). `REMAINING_PLAN.md:46` says `release.jks` is lost. Neither tree contains it. |
| R8 keep rules | works | PdfBox, BouncyCastle, Tesseract, PDFium, Rhino, `core.data` and `BuildConfig` are kept (`proguard-rules.pro`). I found no Rhino/Java reflection bridge that needs more rules (ARCH-20). |
| 16 KB page size | works (pdfium, tesseract) / unverified (ML Kit, ads) | Every arm64/x86_64 `.so` in pdfium-core 2.0.1 and tesseract4android 4.8.0 has LOAD `p_align = 0x4000`. |
| Dependency reproducibility | missing | No `gradle.lockfile`, no `verification-metadata.xml`, no `distributionSha256Sum`. JitPack is unrestricted. |
| Feature configuration | misleading | 20 `BuildConfig` strings come from git-ignored `*.properties` files. None exist in either tree, so a build made today ships about 12 features as "not configured" (ARCH-12). |

## Findings

### ARCH-01 Release signing key lost; release build silently produces an unsigned artifact
- Type: arch-risk (release blocker)
- Status: CONFIRMED
- Severity: Critical      Priority: P0
- Evidence:
  - `app/build.gradle.kts:52-58`: the release config is created only when the properties file exists: `if (keyProps.getProperty("storeFile") != null) create("release") {…}`.
  - `:65`: `signingConfigs.findByName("release")?.let { signingConfig = it }`. With no keystore this does nothing and no error is raised.
  - `REMAINING_PLAN.md:46`: "Release signing key `release.jks` + `keystore.properties` is NOT in this folder (was on the old machine)."
  - `ls D:\pdf\SmartDocs-project-v0.4.0\*.jks` finds nothing.
- User impact / scenario: `assembleRelease`/`bundleRelease` succeed but produce an unsigned APK/AAB, and nobody notices until upload. If v0.4.0 was already published and signed with that key without Play App Signing, existing users **cannot receive updates**. With Play App Signing, an upload-key reset is needed, which takes days.
- Proposed fix:
  1. Find out whether Play App Signing is on. If it is, request an upload-key reset in Play Console. If it is not, try to recover the key from the old machine or backups.
  2. Make a release build without a signing config fail. In `android {}` add `gradle.taskGraph.whenReady { if (allTasks.any { it.name.contains("Release") } && signingConfigs.findByName("release") == null) throw GradleException("release keystore missing") }`.
- Effort: S (gradle) / L (key recovery)     Regression risk: Low     Dependencies: owner access to Play Console
- Validation: `./gradlew bundleRelease` without `keystore.properties` must fail. `apksigner verify --print-certs` on the artifact must match the Play upload certificate.

### ARCH-02 targetSdk 35 is below Google Play's current target-API requirement
- Type: arch-risk (release blocker)
- Status: PROBABLE (policy date from my knowledge; check in Play Console)
- Severity: Critical      Priority: P0
- Evidence:
  - `app/build.gradle.kts:16-17`: `minSdk = 26`, `targetSdk = 35`, while `compileSdk = 36` (`:12`).
  - Google Play's yearly policy requires new apps **and updates** to target the API level released the year before by 31 August (API 36 / Android 16 for 2026), with an extension window to 1 November. Today is 2026-10-27.
- User impact / scenario: Play Console rejects the next update, or will reject it after the extension expires.
- Proposed fix: set `targetSdk = 36`. Then review the Android 16 behaviour changes: edge-to-edge opt-out is removed (the app already calls `enableEdgeToEdge()`, `MainActivity.kt:39`), predictive back (`enableOnBackInvokedCallback="true"` is already set), and `android:screenOrientation`/resizability being ignored on large screens. The manifest has `configChanges` that include `smallestScreenSize|screenLayout`, which is fine.
- Effort: M     Regression risk: Med     Dependencies: ARCH-01
- Validation: device test on an API-36 emulator and a foldable: open, annotate, rotate, back gesture and IME in the reader.

### ARCH-03 ReaderState leaks for the life of the process through WeakHashMap side-cars whose values hold their keys
- Type: performance (memory leak)
- Status: CONFIRMED
- Severity: High      Priority: P1
- Evidence:
  - `ui/forms5/FormFillState.kt:39-40`: `private val states = WeakHashMap<ReaderState, FormFillState>()` with `getOrPut(reader) { FormFillState(reader) }`.
  - `:48`: `class FormFillState internal constructor(val reader: ReaderState)`. The value strongly references the key, so the weak key is never cleared.
  - The same pattern is in `ui/view5/V5State.kt:105-106` with `class V5State private constructor(val s: ReaderState)` (`:26`).
  - `ReaderState.kt:146` calls `FormFill.of(this)` for every reader.
  - No `remove` exists for either map (grep for `states.remove`/`map.remove` finds nothing).
- User impact / scenario: every document opened stays in memory with its ReaderState. That includes `textCache`/`boxCache` with the text and char boxes of every page visited (`ReaderState.kt:163-164`), pending annotations, `Placement.image` bitmaps, the form model and V5 state. A student who opens 30 or more chemistry PDFs in one session grows the heap without limit and eventually gets an OOM. `android:largeHeap="true"` only delays this.
- Proposed fix: key by identity and remove explicitly. Call `FormFill.release(this)` and `V5State.release(this)` from `ReaderState.close()`. Alternatively, store the side-cars as fields of ReaderState, or make the value hold a `WeakReference<ReaderState>`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: JVM unit test that creates a FormFillState through `of()`, drops the reader, calls `System.gc()` and asserts `states.size == 0`. Device test: open and close 20 PDFs and compare the LeakCanary or `dumpsys meminfo` heap before and after.

### ARCH-04 Room DB: version 1, no exported schema, destructive fallback — the first schema change wipes user data
- Type: data-integrity
- Status: CONFIRMED (code). The crash scenario for devices that had a lost post-0.4.0 build is a HYPOTHESIS.
- Severity: High      Priority: P1
- Evidence:
  - `core/data/AppDatabase.kt:152-169`: `version = 1, exportSchema = false` with `Room.databaseBuilder(...).fallbackToDestructiveMigration()`.
  - The tables hold user-created data: `signatures` (paths to saved handwritten signatures), `reading_bookmarks`, `tags`/`doc_tags`, `fill_profile` (the auto-fill personal profile) and `custom_stamps`.
- User impact / scenario: the next developer who adds a column and bumps the version silently deletes every user's saved signatures, bookmarks, tags and fill profile. If the version is not bumped, Room throws "cannot verify the data integrity" at the first DAO call. This can already happen on test devices that ran the lost post-0.4.0 builds (`REMAINING_PLAN.md:3`) if those builds changed the entities at version 1.
- Proposed fix:
  1. Set `exportSchema = true` and add `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`.
  2. Commit `schemas/1.json`.
  3. Replace `fallbackToDestructiveMigration()` with explicit `Migration`s, keeping `fallbackToDestructiveMigrationOnDowngrade()` only.
  4. Add a Room `MigrationTestHelper` test.
- Effort: S     Regression risk: Low     Dependencies: androidTest setup (ARCH-24)
- Validation: instrumentation migration test 1→2 that keeps all rows of `signatures` and `fill_profile`.

### ARCH-05 Blocking `PdfSession.close()` (runBlocking + mutex) is called on the main thread from onDispose
- Type: performance (ANR / jank)
- Status: CONFIRMED
- Severity: Medium      Priority: P1
- Evidence:
  - `core/pdf/PdfSession.kt:266-270`: `override fun close() { … kotlinx.coroutines.runBlocking { mutex.withLock { closeLocked() } } }`. It waits for any in-flight `render`, `text` or `search`, each of which holds the mutex during a native PDFium call (`:114-121`).
  - Main-thread callers:
    - `ui/esign/EsignCommon.kt:40`: `DisposableEffect(session) { … onDispose { s?.close() } }`, inside `rememberPdfSession`, which has 3 users.
    - `ui/forms/FormBuilderScreen.kt:70` (inside `LaunchedEffect`, which runs on Main) and `:73` (onDispose).
    - `ui/pages/OrganizeState.kt:289` (`dispose()`): it cancels the thumbnail jobs, but a native render already in progress cannot be interrupted.
    - `ui/search/SearchUiCommon.kt:47`.
- User impact / scenario: the user leaves Organize Pages or the e-sign preview while a large scanned page is rendering at high DPI. The UI thread blocks until PDFium finishes, which can take seconds on low-end devices and risks an ANR.
- Proposed fix: replace these calls with `appScope.launch { s.closeAsync() }`, where appScope is an application-level `SupervisorJob + IO`. Mark `close()` `@Deprecated("blocks")` and lint for it.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: StrictMode `detectAll()` in debug. Device test: leave Organize while thumbnails of a 300-page scan render and check that no frame is longer than 700 ms.

### ARCH-06 PdfBox is used directly from 117 files (15 in UI); 55 ad-hoc `PDDocument.load` calls with no memory policy and inconsistent password handling
- Type: arch-risk
- Status: CONFIRMED
- Severity: High      Priority: P1
- Evidence:
  - `grep -rl com.tom_roush` gives 117 files, 15 of them under `ui/` (for example `ui/tools5/AccessibilityScreen.kt:84,138,159,227`, `ui/signature/DigitalSignScreen.kt:114`, `ui/view5/MeasureListSheet.kt:42`).
  - `PDDocument.load(file)` without a password appears 55 times. Examples: `DigitalSignScreen.kt:114` (`PDDocument.load(f).use { … mediaBox … }`, which fails on an encrypted document when a visible signature is used) and `AccessibilityScreen.kt:84,138,227`.
  - `grep MemoryUsageSetting|setupTempFileOnly` returns nothing, so every load is fully on-heap.
  - `PdfOps.kt:375-385` rasterises a whole document into one in-memory `PDDocument`.
- User impact / scenario: password-protected PDFs work in the reader but fail with "password required"/`InvalidPasswordException` in about 30 secondary tools. Large scans (100 MB or more) hit an OOM in tools such as accessibility, PDF/A, compare and optimise. Every PdfBox upgrade or replacement (ARCH-15) touches more than 100 files.
- Proposed fix:
  1. Add `core/pdf/PdfDocs.kt` with `suspend fun <T> withPdf(file, password, block: (PDDocument) -> T)`. It loads with `MemoryUsageSetting.setupMixed(32 MB)` on `Dispatchers.IO` and maps `InvalidPasswordException` to `PasswordRequiredException`.
  2. Migrate the `ui/` callers first, then the core callers.
  3. Add a lint/grep CI check that forbids `PDDocument.load` outside `core/pdf`.
- Effort: M (wrapper plus UI callers) / L (all)     Regression risk: Med     Dependencies: none
- Validation: JVM test that encrypts a generated PDF with PdfBox and calls each migrated tool entry point with the password. Device test on a 150 MB scan through Accessibility check.

### ARCH-07 Error swallowing is systemic: 1,173 `runCatching`, 0 log statements; Save-as can report nothing or success on failure
- Type: data-integrity
- Status: CONFIRMED
- Severity: High      Priority: P1
- Evidence:
  - `ui/common/Components.kt:202`, the "Save as" in the shared `ResultSheet` that every tool result uses: `if (uri != null) CoroutineScope(Dispatchers.IO).launch { runCatching { app.documents.exportTo(file, uri) } }`. There is no success or failure feedback, and the scope is unstructured.
  - `core/files/DocumentRepository.kt:178-179`: `contentResolver.openOutputStream(target, "wt")?.use { … }`. A `null` stream writes nothing and returns normally, so `ui/reader/ReaderScreen.kt:135-137` shows `R.string.success` for a file that was never written.
  - Hotspots: `ReaderState.kt` (31), `core/edit/GlyphDecoder.kt` (19), `core/forms5/FormModel.kt` (18), `core/redact/Sanitizer.kt` (15).
  - `grep "Log\.(e|w|d)("` = 0 matches.
- User impact / scenario: a user saves a tool result to a cloud SAF provider that refuses writes, or whose stream is null. They see no error, or "Success", and delete the original believing the export exists.
- Proposed fix:
  1. `exportTo` throws `IOException("cannot open target")` when the stream is null.
  2. `ResultSheet` launches in `rememberCoroutineScope()` and shows a snackbar on success or failure.
  3. Add an `AppLog.w(tag, t)` helper (Logcat in debug, CrashLog ring buffer in release) and use it in `runCatching{}.onFailure` at file, crypto and save boundaries.
- Effort: S (save-as) / M (logging)     Regression risk: Low     Dependencies: none
- Validation: unit test with a fake ContentResolver that returns a null stream, asserting an exception. Device test: save as to a read-only provider and check that an error is shown.

### ARCH-08 Sanitizer swallows per-item failures yet still reports items as removed
- Type: security
- Status: CONFIRMED (code path); real-file impact PROBABLE
- Severity: Medium      Priority: P1
- Evidence:
  - `core/redact/Sanitizer.kt:252`: `runCatching { cat.names?.let { names -> names.embeddedFiles?.let { n += collectNames(it.cosObject).size…; names.setEmbeddedFiles(null) } } }`. If `setEmbeddedFiles` throws after `n` was increased, the count is still reported.
  - `:283-284`: `runCatching { form.refreshAppearances() }; runCatching { form.flatten() }`. If flatten fails, the field values are dropped together with `ACRO_FORM` without being burned in, so visible form data can vanish. The failure is silent.
  - `:260` and `:270` swallow JavaScript removal the same way.
- User impact / scenario: "Remove hidden data" reports "Attachments: 2 removed" while the embedded files remain on a malformed name tree. A user then shares a document that still contains confidential attachments.
- Proposed fix: after `doc.save`, re-open the output and verify that each requested item is now absent (no `EmbeddedFiles`, no JS name tree, no `/AA`). Report "could not remove X" instead of a count. Never count before the mutation succeeds.
- Effort: M     Regression risk: Low     Dependencies: none
- Validation: JVM test (PdfBox already works in JVM tests, see `ProtectModesTest.kt`). Build a PDF with an attachment, JS and a form, sanitise it, re-load it and assert that all three are absent.

### ARCH-09 CancellationException swallowed in suspend loops (`runCatching { suspendCall() }`)
- Type: bug
- Status: CONFIRMED
- Severity: Low      Priority: P2
- Evidence:
  - `core/search/IndexWorker.kt:54`: `for (f in todo) { …; runCatching { indexFile(index, f) }; done++ }`. `indexFile` calls `PdfSession.open`/`text` with `withContext(IO)`, so when WorkManager stops the worker each remaining file throws `CancellationException`, the exception is swallowed, `done` is still increased, and `prune()` still runs (`:57`).
  - Only 19 places anywhere rethrow `CancellationException`. `ui/common/Components.kt:172,180` does it correctly.
- User impact / scenario: the progress state reports "indexed N/N" after a cancelled run. Indexing also keeps doing work after cancellation, which wastes battery.
- Proposed fix: add a `suspend inline fun <T> runCatchingCancellable(block)` that rethrows `CancellationException`, use it in suspend code (start with IndexWorker, OcrRunner and DocIndexer), and add a detekt rule `SuspendFunSwallowedCancellation`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: coroutine unit test: cancel the job after the first file and assert that `done == 1` and `prune` is not called.

### ARCH-10 Unstructured `CoroutineScope(Dispatchers.IO)` launches outlive their owner and cannot report results
- Type: arch-risk
- Status: CONFIRMED
- Severity: Low      Priority: P2
- Evidence: 6 places create a fresh scope:
  - `SmartDocsApp.kt:27` (AppDataSync at start-up);
  - `ui/common/Components.kt:202` (Save-as);
  - `ui/ai/AgentChatState.kt:188`;
  - `ui/ocr/OcrScreen.kt:57`;
  - plus the `ReaderState` `ioScope` (`ReaderState.kt:239`, which is intentional);
  - plus `PdfSession`'s blocking close.
- User impact / scenario: there is no central place to wait for pending writes before process death or to surface errors (see ARCH-07).
- Proposed fix: add one `val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` in `SmartDocsApp` with a `CoroutineExceptionHandler` that logs through CrashLog, and replace the ad-hoc scopes with it.
- Effort: S     Regression risk: Low     Dependencies: ARCH-07
- Validation: code review / grep in CI for `CoroutineScope(Dispatchers`.

### ARCH-11 Debug and release builds share the `smartdocs://oauth` redirect scheme → OAuth callback ambiguity
- Type: bug
- Status: PROBABLE (needs device test)
- Severity: Medium      Priority: P2
- Evidence:
  - `app/build.gradle.kts:68-71`: the debug build has `applicationIdSuffix = ".test"` so both builds install side by side.
  - `AndroidManifest.xml:39-47`: both declare `<data android:scheme="smartdocs" android:host="oauth"/>`.
  - `core/cloud/OAuthPkce.kt:60`: "the redirect comes back to MainActivity as `smartdocs://oauth/<provider>`". The PKCE verifier lives in the prefs of the app that started the flow.
  - Google Drive authorisation (Identity API) also needs a separate OAuth client registered for `com.smartdocs.editor.test` plus the debug SHA-1.
- User impact / scenario: a tester with both builds installed connects Dropbox or OneDrive. Android shows a chooser, or sends the redirect to the other app, which has no verifier, so sign-in fails and looks broken.
- Proposed fix: use `manifestPlaceholders["oauthScheme"] = "smartdocs"` for release and `"smartdocs-test"` for debug, plus a `BuildConfig.OAUTH_SCHEME` used by `OAuthPkce`. Register both redirects with the providers.
- Effort: S     Regression risk: Low     Dependencies: provider consoles
- Validation: device test with both builds installed: connect Dropbox from each.

### ARCH-12 Twenty BuildConfig features depend on git-ignored properties files that do not exist; nothing fails the release build
- Type: missing feature / release readiness
- Status: CONFIRMED
- Severity: High      Priority: P1
- Evidence:
  - `app/build.gradle.kts:22-45` reads `ai.properties`, `cloud.properties`, `pro.properties`, `ads.properties` and `license.properties` with `takeIf { it.exists() }` and `""` defaults.
  - `.gitignore` excludes all of them, and none exists in `D:\pdf\audit-wt` or `D:\pdf\SmartDocs-project-v0.4.0` (only `gradle.properties` and `local.properties`).
  - The UI then shows "not configured" for these features: `ui/cloud/CloudScreen.kt:112,126`, `ui/connected/ConnectedHomeScreen.kt:59`, `ui/esign/EsignCreateScreen.kt:124`, `ui/account/AccountScreen.kt:173`.
  - AI falls back to a user API key (`core/ai/AiClient.kt:37-44`).
  - `BuildCfg` reads the fields by reflection (`core/review/AcctPrefs.kt:63-66`), so a renamed field also silently turns its feature off.
- User impact / scenario: a release built on this machine ships with OneDrive, Dropbox, Box, shared review, share links, e-sign, connected docs, the licence server and the AI proxy all disabled. Users see about 12 "Not configured" rows in a paid app.
- Proposed fix: add a `verifyReleaseConfig` task that release tasks depend on. It fails when the required keys (a list kept per flavour) are blank. Replace reflection in `BuildCfg` with direct `BuildConfig.X` references so a rename is a compile error.
- Effort: S     Regression risk: Low     Dependencies: owner has the secrets
- Validation: `./gradlew bundleRelease` without the properties files must fail and name the missing keys.

### ARCH-13 Secrets compiled into the APK (offline Pro HMAC secret, Box client secret, worker tokens)
- Type: security
- Status: CONFIRMED (mechanism; values not present in this tree)
- Severity: High      Priority: P1
- Evidence:
  - `app/build.gradle.kts:30` sets `CLOUD_BOX_CLIENT_SECRET`, `:31-32` sets `SHARE_TOKEN`/`ESIGN_TOKEN`/`CONNECTED_TOKEN`/`COLLAB_TOKEN` and `:35` sets `PRO_CODE_SECRET`, all as `buildConfigField` strings.
  - `core/billing/ActivationCodes.kt:48,55-66`: offline Pro codes are verified with `HmacSHA256` using the **embedded** secret. Anyone who decompiles the APK can mint unlimited lifetime Pro codes.
  - `proguard-rules.pro` keeps `BuildConfig { *; }`, so the strings are not even obfuscated.
- User impact / scenario: revenue loss through keygens, and abuse of the backend workers using the extracted tokens.
- Proposed fix:
  - Ship release builds with `codeSecret` empty and `LICENSE_URL` set (server mode, already supported at `ActivationCodes.kt:23,64`).
  - Move the Box client secret to the worker's token exchange (`OAuthPkce.kt:45` already supports a `COLLAB_URL/oauth-token/box` proxy).
  - Treat app tokens as public identifiers and enforce limits on the server side.
- Effort: S (config) / M (server side)     Regression risk: Low     Dependencies: ARCH-12
- Validation: `apkanalyzer dex packages` / `strings` on the release AAB shows no secret values.

### ARCH-14 BouncyCastle 1.72 (2022) has known CVEs reachable when verifying untrusted signed PDFs
- Type: security
- Status: PROBABLE (version CONFIRMED at `app/build.gradle.kts:152-153`; CVE applicability from my knowledge)
- Severity: Medium      Priority: P1
- Evidence: `org.bouncycastle:bcpkix-jdk15to18:1.72` and `bcprov-jdk15to18:1.72`. Advisories fixed in later releases include:
  - CVE-2024-29857 (ECCurve F2m parameter CPU exhaustion, fixed 1.78);
  - CVE-2024-30172 (Ed25519 infinite loop, fixed 1.78);
  - CVE-2024-30171 (RSA timing side channel, fixed 1.78);
  - CVE-2023-33201 (fixed 1.74).

  `core/pdf/DigitalSignature.kt` and `core/sign/LtvSupport.kt` parse certificates, CMS and OCSP from documents and servers.
- User impact / scenario: opening and validating a hostile signed PDF can hang the validation thread (DoS).
- Proposed fix: bump both artifacts to the current 1.8x release (same artifact IDs, API compatible for CMS/PKIX).
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: the existing esign tests plus a device test signing, validating and LTV-enabling a document.

### ARCH-15 PdfBox-Android 2.0.27.0 is a stale fork; upstream 2.0.x parser hardening is missing
- Type: arch-risk
- Status: PROBABLE
- Severity: Medium      Priority: P2
- Evidence:
  - `app/build.gradle.kts:129` uses `com.tom-roush:pdfbox-android:2.0.27.0`, which is the last fork release. Upstream Apache PDFBox has shipped further 2.0.x maintenance releases (about 2.0.28–2.0.3x) that fix infinite loops and OOMs on malformed files, plus 3.0.x.
  - The fork is used for all editing, forms, security and saving (ARCH-06), and every file the user opens is untrusted input.
- User impact / scenario: crafted or corrupt PDFs from messaging apps can hang tools or exhaust memory.
- Proposed fix: short term, guard each PdfBox operation with a timeout and memory policy through the ARCH-06 wrapper. Medium term, check whether the pure-Java parts of upstream 2.0.x can be vendored, or follow a maintained fork. Keep the dependency behind `core/pdf` so it can be swapped.
- Effort: M     Regression risk: Med     Dependencies: ARCH-06
- Validation: fuzz corpus (for example the PDF.js or pdfium test corpora) run through `withPdf {}` in a JVM test with a timeout.

### ARCH-16 JitPack repository is global and unpinned; no dependency verification, no lockfile, no wrapper checksum
- Type: security (supply chain) / reproducibility
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence:
  - `settings.gradle.kts:6`: `repositories { google(); mavenCentral(); maven("https://jitpack.io") }`. There is no `content { includeGroup("com.github.adaptech-cz") }`, so any coordinate can resolve from JitPack, which builds from mutable git tags.
  - `gradle/verification-metadata.xml` does not exist, and there are no `*.lockfile` files.
  - `gradle-wrapper.properties` has no `distributionSha256Sum`.
  - CI (`build.yml`) has no `gradle/actions/wrapper-validation`.
- User impact / scenario: a compromised or re-tagged JitPack artifact ends up in the signed release without anyone noticing, and builds are not byte-reproducible.
- Proposed fix:
  - Restrict JitPack with `exclusiveContent { forRepository { maven("https://jitpack.io") }; filter { includeGroup("com.github.adaptech-cz.Tesseract4Android") } }`.
  - Run `./gradlew --write-verification-metadata sha256 help`.
  - Add `distributionSha256Sum` and the wrapper-validation action.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: CI is green with verification on. Tampering with one cached jar fails the build.

### ARCH-17 OCR models downloaded from a mutable `main` branch with no checksum
- Type: security / reproducibility (in-flux area)
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence:
  - `core/ocr/OcrEngine.kt:47`: `"https://github.com/tesseract-ocr/tessdata_best/raw/main/$lang.traineddata"`.
  - The only check is `tmp.length() < 100_000` (`:66`). The file is then fed to native Tesseract (`:78-80`).
- User impact / scenario: OCR results change when upstream changes the files. A tampered or truncated model larger than 100 KB is parsed by native code.
- Proposed fix: pin to a commit SHA URL, and keep a `Map<lang, sha256>` that is verified before the rename.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test on the hash check with a corrupted file. Device test downloading `ara`.

### ARCH-18 Unused `READ_MEDIA_IMAGES` permission triggers Play's Photo/Video permission policy
- Type: arch-risk (release)
- Status: CONFIRMED (unused) / PROBABLE (policy outcome)
- Severity: Medium      Priority: P1
- Evidence:
  - `AndroidManifest.xml:16` declares `<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />`.
  - No code requests it. `grep READ_MEDIA_IMAGES` in Kotlin only hits a comment at `ui/files/FilesScreen.kt:54`. Image picking uses `PickVisualMedia` (`ui/common/Components.kt:296`, `ui/account/AccountScreen.kt:74`).
- User impact / scenario: Play Console asks for a Photo & Video permissions declaration and rejects the update, because a PDF editor's core use does not need broad photo access.
- Proposed fix: delete the line, or add `android:maxSdkVersion="32"` if legacy behaviour needs it.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: `aapt dump permissions` on the release APK, then the Play pre-launch report.

### ARCH-19 Refresh tokens in plaintext SharedPreferences with `allowBackup="true"` and no backup rules
- Type: security
- Status: CONFIRMED
- Severity: Medium      Priority: P1
- Evidence:
  - `core/cloud/OAuthPkce.kt:50-55`: `getSharedPreferences("cloud_$provider")` with `putString("refresh", v)`.
  - `core/cloud/GoogleDrive.kt:83`: `putString("token", token)`.
  - `AndroidManifest.xml:25` has `android:allowBackup="true"`, with no `dataExtractionRules`/`fullBackupContent` (`res/xml` holds only `file_paths.xml` and `network_security_config.xml`).
- User impact / scenario: cloud refresh tokens, the fill profile (Room DB) and the licence state are copied into Google backups and device-to-device transfers, then restored on another device.
- Proposed fix: add `data_extraction_rules.xml` and `backup_rules.xml` that exclude `sharedpref/cloud_*.xml`, the licence prefs and the `ocr_best` folder. Optionally wrap tokens with an AndroidKeyStore AES key (the 7 existing KeyStore usages show the pattern).
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: `adb shell bmgr backupnow` followed by restore on a second emulator; tokens must not be present.

### ARCH-20 R8: keep rules are broad but complete; the risk is that no release build is ever tested
- Type: test-gap
- Status: CONFIRMED (rules) / HYPOTHESIS (runtime)
- Severity: Medium      Priority: P2
- Evidence:
  - `proguard-rules.pro` keeps `com.tom_roush.**`, `org.bouncycastle.**`, `com.googlecode.tesseract/leptonica.**`, `io.legere.pdfiumandroid.**`, `org.mozilla.javascript.**`/`classfile.**`, `com.smartdocs.editor.core.data.**` and `BuildConfig`.
  - Rhino gets only plain data (`ScriptableObject.putProperty`, `core/forms5/AcroJs.kt:95-100`) with `setClassShutter { false }` (`core/forms/JsSandbox.kt:20`). Nothing relies on Java reflection.
  - kotlinx-serialization is used only through `JsonObject` builders (no `@Serializable`), so no plugin or rules are needed.
  - But CI never runs `assembleRelease` or `minifyReleaseWithR8` (`build.yml:22-24`), and `-keep class com.tom_roush.** { *; }` disables shrinking of the largest library.
- User impact / scenario: R8-only crashes (missing class from a `-dontwarn`-suppressed reference such as `com.gemalto.jp2`, `javax.naming` or `java.awt`) are first found by users.
- Proposed fix: add a CI job `./gradlew :app:assembleRelease` with a throwaway keystore that uploads `mapping.txt`, plus a smoke instrumentation test on the minified build (`testBuildType`).
- Effort: S     Regression risk: Low     Dependencies: ARCH-24
- Validation: CI release job is green, and the mapping is archived per versionCode.

### ARCH-21 No R8 mapping retention and no remote crash reporting; obfuscated crash files cannot be decoded
- Type: arch-risk
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence:
  - `core/util/CrashLog.kt:22-38` writes only the last crash to `filesDir/last_crash.txt`. The stack trace is obfuscated in release (`isMinifyEnabled = true`, `app/build.gradle.kts:62`).
  - No mapping upload or archive step exists in `build.yml`.
- User impact / scenario: support receives unreadable stack traces and crash fixes slow down.
- Proposed fix: archive `app/build/outputs/mapping/release/mapping.txt` for each versionCode, add it to the Play Console upload, and keep 5 crashes instead of only the last.
- Effort: S     Regression risk: Low     Dependencies: ARCH-01
- Validation: decode a deliberate crash with `retrace`.

### ARCH-22 Delivery-round packages (`*5`, `*6`) layer on top of the originals; three OOXML stacks and four DOCX writers
- Type: arch-risk
- Status: CONFIRMED
- Severity: Medium      Priority: P3
- Evidence:
  - The "duplicates" are **all live and layered**, not alternatives. `core.edit` is used by `core/edit5` (8 files), `core/redact` (3) and `ui/edit` (4). `core.forms` is used by `core/forms5` (7) and `ui/forms` (3). `core.office` is used by `core/office5` (5), `core/tools5` (2) and `ui/office` (2). `core.ai` is used by `core/ai5` (9), `ui/ai` (5), `core/voice` and `core/tools5`.
  - The results are parallel implementations:
    - OOXML zip/package: `core/office/OoxmlPackage.kt`, `core/office5/OPackage.kt`, `core/convert/OoxmlZip.kt`.
    - DOCX writers: `core/pdf/DocxWriter.kt` (used by `core/ai/DocActions.kt`), `core/office/DocxWriter2.kt` (dead), `core/convert/DocxBuilder.kt`, `core/office5/WordDoc.kt`.
    - XLSX writers: `core/office/XlsxWriter.kt` (dead), `core/convert/XlsxBuilder.kt`, `core/office5/XlsxIO5.kt`.
    - Number formats: `core/office/XlsxReader.kt NumFormat`, `core/office5/NumFmt5.kt`.
  - (in-flux area) `core/convert6` builds on `core/convert` (`Cv6Engine.kt`, `PdfFlow6.kt` import `core.convert`).
- User impact / scenario: the same document type exports differently depending on the entry point (AI action, office editor or convert), and a bug fixed in one writer stays in the other three.
- Proposed fix: no big rewrite. Write an ADR naming one owner per format (OOXML I/O lives in `office5`), delete the dead writers (ARCH-23), and route `DocActions` to the `office5` writer.
- Effort: M     Regression risk: Med     Dependencies: ARCH-23
- Validation: golden-file test that writes the same model through each remaining path and diffs `document.xml`.

### ARCH-23 Confirmed dead code (zero references anywhere outside the defining file)
- Type: enhancement (code quality)
- Status: CONFIRMED
- Severity: Low      Priority: P3
- Evidence: each name appears only at its own declaration (grep across `app/src`):
  - `core/office/DocxWriter2.kt` (213 lines, `object DocxWriter2` and `DocxEditor`);
  - `core/office/XlsxWriter.kt` (131 lines);
  - `core/pdf/AnnotReplies.kt` (92 lines);
  - `ui/tools/MiscTools.kt:68 WifiTransferScreen`, superseded by `ui/cloud/WifiScreen` (route `"wifi"`, `ToolScreens.kt:69`);
  - `ui/cloud/SyncPickers.kt:104 DriveFolderPicker`;
  - `ui/voice/AiVoiceSettings.kt:109 ReadAloudVoiceSheet`;
  - `ui/ai/AgentSheet.kt:54 DocAgentSheet`, which is referenced only in a KDoc comment.

  Unused dependency: `androidx.compose.material3:material3-window-size-class` (`app/build.gradle.kts:107`), with 0 imports of `windowsizeclass`.

  (in-flux area) In `D:\pdf\SmartDocs-project-v0.4.0`, the classic `ConvertScreen`/`ConvertFromPdfPanel` (about 600 lines across `ui/tools/ConvertTool.kt` and `ConvertFromPdf.kt`) is reachable only through route `"convert_classic"` (`ToolScreens.kt:53`), and nothing navigates to that route.
- User impact / scenario: about 1k lines of maintenance and review burden, and misleading grep hits. The dead `AnnotReplies` suggests reply threading exists when the live code is elsewhere.
- Proposed fix: delete them, and remove the dependency.
- Effort: S     Regression risk: Low     Dependencies: compile
- Validation: `assembleDebug` and the unit tests stay green.

### ARCH-24 No instrumentation tests, no PDF fixtures: core workflows are untested end-to-end
- Type: test-gap
- Status: CONFIRMED
- Severity: High      Priority: P1
- Evidence:
  - `app/src` has only `debug`, `main` and `test`. There is no `androidTest` and no `testInstrumentationRunner` (`grep` in `app/build.gradle.kts` = 0).
  - The 47 JVM test files (384 tests) cover pure logic: `core/history/UndoStackTest`, `core/redact/{RedactMath,ProtectModes,ProtectAdvanced}Test`, `core/search/*` (Arabic normalisation in `ArabicTextFixTest`, `TextMatcherTest`), `core/forms5/*` (8), `core/office5/*` (4), `core/edit5/*` (3), `core/edit/EditBidiTest`, `core/convert/{FidLayout,PageGeometry}Test`, `core/esign/*`, `core/sync/*`, `core/tools5/*` (2), `core/view5/View5LogicTest`, and the ai5, billing, collab, connected, gaps6 and share5 packages.
  - **No test exists for** these packages: `core/pdf` (PdfSession, `AnnotationWriter.commit`, `PdfOps`, `FormOps`, `LockedPdf`, `DigitalSignature`), `core/ocr`, `core/annot`, `core/forms` (XfaLayout, JsSandbox), `core/office`, `core/sign`, `core/data`, `core/files`, `core/cloud`, `core/templates` and every `ui/*` state holder (ReaderState save, swap and reopen; `DocHistory` file snapshots).
  - There are no PDF fixture files in the repo (no `src/test/resources`).
- User impact / scenario: the P0 flows (save annotations and reopen, undo/redo across a save, redaction really removing text, encrypted save keeping the password, the OCR text layer, Arabic extraction from real fixtures) can regress silently. CI only proves that the app compiles.
- Proposed fix (smallest set):
  1. Add `src/test/resources/pdf/` with 4 small generated fixtures (plain, encrypted AES-256, AcroForm, Arabic text with the embedded Noto Naskh).
  2. Add JVM round-trip tests with PdfBox (proven to work by `ProtectModesTest.kt:43-91`): annotations committed and re-listed (`AnnotationWriter.list`), `TrueRedactor` output no longer contains the text, and an encrypted save stays encrypted.
  3. Add an `androidTest` module with `AndroidJUnitRunner`, plus one PdfSession render/text test and one Room migration test.
- Effort: M     Regression risk: Low     Dependencies: none
- Validation: CI shows these tests and they fail when `AnnotationWriter.commit` is broken.

### ARCH-25 CI covers neither PRs, lint, nor release
- Type: test-gap
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence: `.github/workflows/build.yml:3-6` triggers on `push: branches: [main, master]` and `workflow_dispatch` only. The steps are `assembleDebug` and `testDebugUnitTest` (`:22-24`). There is no `pull_request` trigger, no `lintDebug`, no release job, no Gradle cache (`setup-java` without `cache: gradle`) and no wrapper validation. The repo is local-only, with no remote (`REMAINING_PLAN.md:3` "local git commit"), so CI may never run at all.
- User impact / scenario: broken builds and lint errors reach main.
- Proposed fix: add a `pull_request:` trigger, `cache: gradle`, `./gradlew lintDebug testDebugUnitTest assembleRelease` (release with a throwaway key) and `gradle/actions/wrapper-validation@v4`.
- Effort: S     Regression risk: Low     Dependencies: ARCH-20
- Validation: open a PR with a deliberate lint error and check that CI fails.

### ARCH-26 State-management model: no ViewModel or saved state; reader in-memory edits depend on ON_PAUSE autosave
- Type: arch-risk
- Status: CONFIRMED (design) / PROBABLE (loss scenario, needs device test)
- Severity: Medium      Priority: P2
- Evidence:
  - `ReaderState.kt:66-67`: "Created with `remember` and bound to the composable's coroutine scope". `rememberReaderState` uses `remember(uri)` and `rememberCoroutineScope()` (`:651-657`).
  - There are 0 ViewModel classes and 54 `rememberSaveable` uses against 1,306 `mutableStateOf`.
  - `AndroidManifest.xml:36` `configChanges` covers orientation, size and uiMode but **not** `locale|density|fontScale|keyboard|layoutDirection`. An in-app language or RTL switch (`SmartDocsTheme(rtl = LayoutDir.isRtl(settings))`, `MainActivity.kt:45`), or a system font-size change, recreates the Activity.
  - Pending annotations live only in `annots` (memory). Recovery relies on `saveInBackground()` (`ReaderState.kt:415`), which only runs when `settings.autoSave` and dirty conditions hold.
- User impact / scenario: with autosave off, a user annotates, changes the system font size or language from quick settings, comes back, and the unsaved annotations are gone without a discard prompt.
- Proposed fix: add `locale|layoutDirection|fontScale|density|keyboard` to `configChanges` (cheap), and persist pending annotations to a small journal file on ON_STOP regardless of autosave. Longer term, move ReaderState into a ViewModel with `SavedStateHandle` for uri, page and tool.
- Effort: S (configChanges) / L (ViewModel)     Regression risk: Med     Dependencies: none
- Validation: device test with autosave off: annotate, change the system font size, return, and the annotations are still there.

### ARCH-27 Settings and state fragmented across 26 SharedPreferences files, DataStore and Room
- Type: arch-risk
- Status: CONFIRMED
- Severity: Low      Priority: P3
- Evidence:
  - 63 `getSharedPreferences(` calls with 26 distinct names, for example `cloud_$provider` (`OAuthPkce.kt:51`) and `view5` (`V5State.kt:104`).
  - There is 1 DataStore (`core/data/SettingsStore`) and Room. `BuildCfg` lives in `core/review/AcctPrefs.kt:63`, which is unrelated to review.
- User impact / scenario: "Clear data" and "export settings" features and backup rules (ARCH-19) have to know every file, so a missed prefs file leaks or survives a reset.
- Proposed fix: add a `PrefsRegistry` object listing every prefs name, used by backup rules, StorageManager clear and AppDataSync, with a unit test that greps the sources for unregistered names.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: the unit test fails on a new unregistered prefs name.

### ARCH-28 `PdfSession.render()` returns a cache-owned bitmap; callers must remember `invalidateCache()` before `recycle()`
- Type: arch-risk
- Status: CONFIRMED
- Severity: Low      Priority: P3
- Evidence:
  - `core/pdf/PdfSession.kt:110-121` puts the result into `bitmapCache` and returns the same instance to every caller.
  - Some callers handle it: `RedactApplier.kt:163`, `OcrRunner.kt:91`, `ui/ocr/OcrInPlace.kt:82` and `ConvertEngine.kt:58` ("bitmap belongs to the session cache: never recycle").
  - `core/share5/ShareBuilder.kt:80-89` recycles without `invalidateCache()`. It is safe today only because the session is private and each page is rendered once.
- User impact / scenario: the first refactor that shares a session gives "Canvas: trying to use a recycled bitmap" crashes.
- Proposed fix: add `renderOwned(...)`, which bypasses the cache and returns a bitmap the caller may recycle, and use it in every export, OCR or redact path.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit-level review plus a device test of share-as-images followed by reader scrolling.

### ARCH-29 `WebPdfWriter` lives in the `android.print` package and uses a non-cancellable `suspendCoroutine` with no timeout
- Type: arch-risk
- Status: PROBABLE (needs device test)
- Severity: Medium      Priority: P2
- Evidence:
  - `app/src/main/java/android/print/WebPdfWriter.kt:19-24` subclasses package-private SDK callbacks by declaring a class in the `android.print` package. This depends on how ART checks package-private access across class loaders, and it can break on a future Android version.
  - `:29-34` uses `suspendCoroutine { … }`, which cannot be cancelled and has no timeout. A WebView that never finishes layout leaves the caller suspended forever.
  - `:39` sets `javaScriptEnabled = true` for arbitrary URLs.
- User impact / scenario: "URL/HTML to PDF" hangs with a spinner on some pages or OEM WebViews, and there is no way to cancel.
- Proposed fix: use `suspendCancellableCoroutine` with `withTimeout(60s)`, destroy the WebView on cancel, and add an instrumentation test on API 26, 30 and 36.
- Effort: S     Regression risk: Low     Dependencies: ARCH-24
- Validation: device test converting a page that never fires `onPageFinished`; cancel must work.

### ARCH-30 Dependency currency summary (versions from `app/build.gradle.kts` and root `build.gradle.kts`)
- Type: arch-risk
- Status: CONFIRMED (versions) / PROBABLE (age judgements)
- Severity: Low      Priority: P3
- Evidence:
  - **Toolchain:**
    - AGP 8.13.2, Kotlin 2.3.21, Compose compiler plugin 2.3.21, KSP 2.3.12 and Gradle 8.14.3 are current.
    - JDK 17 and desugar_jdk_libs 2.1.5 are fine.
  - **AndroidX:**
    - Compose BOM `2025.03.01` (`:101`) is about 19 months old (Compose 1.7.x).
    - activity-compose 1.10.1, navigation-compose 2.8.9, lifecycle 2.8.7 and core-ktx 1.15.0 are early-2025.
    - window 1.2.0 is from 2023.
    - work 2.10.0, room 2.7.1, datastore 1.1.4, browser 1.8.0, exifinterface 1.4.0, documentfile 1.0.1 and splashscreen 1.0.1 are fine.
  - **Coroutines and serialization:** kotlinx-coroutines 1.10.1 and serialization-json 1.8.0.
  - **PDF:** pdfiumandroid 2.0.1 (16 KB aligned, verified); pdfbox-android 2.0.27.0 (stale, ARCH-15); rhino 1.7.15.1, which I believe is the patched 1.7.x line (Rhino shipped 1.7.15.1 as a security release; verify against GHSA). Rhino runs with `initSafeStandardObjects` and a deny-all ClassShutter.
  - **OCR and ML:** Tesseract4Android 4.8.0 from JitPack (16 KB aligned, verified; newer releases may exist, so verify); mlkit document-scanner 16.0.0; digital-ink 19.0.0.
  - **Google Play:** billing-ktx 9.1.0, review-ktx 2.0.2, play-services-ads 24.4.0 and UMP 3.2.0.
  - **Network and images:** okhttp 4.12.0 (last 4.x; 5.x is stable), coil-compose 2.7.0 (Coil 3 exists).
  - **Crypto:** bouncycastle 1.72 (outdated, ARCH-14).
  - **Sign-in:** play-services-auth 22.0.0. The code already uses the Identity `AuthorizationClient`, but it calls the deprecated `toGoogleSignInAccount()` (`core/cloud/GoogleDrive.kt:82`, `core/gaps6/GoogleAccount.kt:76`) for the e-mail. Use the OpenID userinfo fallback that is already present (`GoogleAccount.kt:32`), and move sign-in to Credential Manager later.
  - **Tests:** junit 4.13.2 and org.json 20240303.
- User impact / scenario: you inherit old Compose bugs (text field / IME and lazy list fixes after 1.7) and stay on deprecated Google auth APIs.
- Proposed fix: one upgrade pass. Raise the BOM to the latest stable plus activity, lifecycle and navigation, then core, then window, run lint and the tests, and update BouncyCastle (ARCH-14).
- Effort: M     Regression risk: Med     Dependencies: ARCH-24 (safety net)
- Validation: unit tests plus a device smoke test of the reader, forms and IME.

### ARCH-31 Licences: no GPL/AGPL found; Rhino (MPL-2.0) missing from the licences screen; NOTICE files stripped
- Type: arch-risk (compliance)
- Status: CONFIRMED
- Severity: Low      Priority: P2
- Evidence:
  - `ui/help/LegalScreens.kt:62-74` lists AndroidX, Kotlin, PdfBox-Android, PDFium, PdfiumAndroid, Tesseract, Tesseract4Android, Leptonica, Bouncy Castle, OkHttp, Coil, Noto and Material icons.
  - It does **not** list Rhino (MPL-2.0, which requires notice and source-availability information), the tessdata models (Apache-2.0), libjpeg/libpng (bundled in tesseract4android: `libjpeg.so`, `libpngx.so`), or ML Kit / Play services (Google ToS).
  - `app/build.gradle.kts:90` excludes `META-INF/LICENSE*` and `META-INF/NOTICE*`, so the Apache-2.0 NOTICE text is not shipped in the APK either.
  - None of the dependencies are GPL or AGPL.
- User impact / scenario: a compliance gap in store review or an audit.
- Proposed fix: add the Rhino, tessdata, libjpeg/libpng and ML Kit entries, and generate the list with the `com.google.android.gms:oss-licenses` plugin or AboutLibraries so it stays in sync.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: the licences screen lists every runtime artifact in `releaseRuntimeClasspath`.

### ARCH-32 AdMob SDK always linked (sample app id by default) even when ads are off
- Type: arch-risk (release / privacy)
- Status: CONFIRMED (linking) / PROBABLE (AD_ID permission merge, needs a manifest-merger check)
- Severity: Low      Priority: P2
- Evidence:
  - `app/build.gradle.kts:144-145` links `play-services-ads:24.4.0` and UMP unconditionally.
  - `:41`: `manifestPlaceholders["admobAppId"] = adsProps.getProperty("appId", "ca-app-pub-3940256099942544~3347511713")` (Google's sample id).
  - Runtime is gated correctly: `AdsManager.gatherConsent` returns early when `!configured` (`core/ads/AdsManager.kt:75-77`).
  - play-services-ads normally merges `com.google.android.gms.permission.AD_ID` into the manifest.
- User impact / scenario: the Play Data-safety form must declare an advertising ID even though ads are off, and the APK is larger.
- Proposed fix: add a `noAds` product flavour without the ads deps, or use `<uses-permission android:name="com.google.android.gms.permission.AD_ID" tools:node="remove"/>` when ads are disabled. Fail the release build if the sample app id is used while `enabled=true`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: inspect `app/build/intermediates/merged_manifest/release/AndroidManifest.xml`.

### ARCH-33 Global cleartext traffic allowed
- Type: security
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence: `res/xml/network_security_config.xml` has `<base-config cleartextTrafficPermitted="true">`. Its comment justifies this with OCSP/CRL, but the setting applies to **all** traffic: the AI proxy, cloud APIs, collab, e-sign, Wi-Fi transfer and the WebView-to-PDF conversion.
- User impact / scenario: a misconfigured `http://` worker URL in a properties file sends documents and tokens unencrypted, with no warning.
- Proposed fix: set `cleartextTrafficPermitted="false"` in the base config. If OCSP/CRL hosts are known, add `domain-config` entries for them; otherwise allow cleartext only for OCSP/CRL fetches made through a dedicated OkHttp client that rejects non-OCSP content types. Wi-Fi transfer runs as a local server, which is unaffected.
- Effort: S     Regression risk: Med (LTV fetches)     Dependencies: none
- Validation: device test of LTV validation against a known-good signed PDF, and AI and cloud calls still working.

### ARCH-34 `rememberPdfSession` can leak a native document when the composable leaves during open
- Type: performance (native leak)
- Status: PROBABLE
- Severity: Low      Priority: P3
- Evidence:
  - `ui/esign/EsignCommon.kt:39`: `LaunchedEffect(file, password) { session = file?.let { runCatching { PdfSession.open(ctx, it, password) }.getOrNull() } }`.
  - `PdfSession.open` (`core/pdf/PdfSession.kt:288-299`) runs inside `withContext(IO)`. If the effect is cancelled after `core.newDocument` returns but before the result is delivered, the `PdfSession` is never assigned and never closed. `runCatching` swallows the `CancellationException`.
  - `ReaderState.open` handles this case with `if (disposed) s.closeAsync()` (`ReaderState.kt:187`), but this helper does not.
- User impact / scenario: quickly opening and backing out of e-sign previews leaks a PDFium document and its fd each time.
- Proposed fix: open into a local variable, and if `!isActive` after open, call `closeAsync()` in `NonCancellable`.
- Effort: S     Regression risk: Low     Dependencies: ARCH-05
- Validation: device test that opens and leaves 50 times, then checks `ls /proc/<pid>/fd | wc -l` is stable.

### ARCH-35 Smallest steps to reduce coupling and future-proof the app (no feature flags exist today)
- Type: enhancement
- Status: CONFIRMED (absence)
- Severity: Low      Priority: P3
- Evidence:
  - There is no feature-flag mechanism. Features are toggled by blank `BuildConfig` strings (ARCH-12) or are always on.
  - PDFium is well isolated (only `PdfSession.kt`), but PdfBox is not (ARCH-06).
  - `ReaderState` imports 10 packages (`core.cloud`, `core.collab`, `core.connected`, `core.data`, `core.files`, `core.history`, `core.pdf`, `core.search`, `ui.forms5`, `ui.reader`). Add-on modules hook into it through static side-cars (ARCH-03), and 51 files reference `ReaderState`.
- User impact / scenario: you cannot dark-launch risky features such as convert6, pageflip (in-flux areas) or live collab, and an engine change ripples through the whole code base.
- Proposed fix:
  1. Add a `Features` object, backed by `BuildConfig` booleans plus a debug-settings override, and gate the convert6 hub and pageflip behind it until device-verified.
  2. Use the ARCH-06 `withPdf` wrapper as the only PdfBox entry point.
  3. Define a `ReaderExtension` interface (`onOpen`, `onSave`, `onClose`) registered in ReaderState instead of WeakHashMaps.
- Effort: M     Regression risk: Low     Dependencies: ARCH-03, ARCH-06
- Validation: a lint/grep CI rule plus unit tests of the `Features` defaults.

## Quick wins
- **ARCH-03**: release the WeakHashMap side-cars on close (S, P1).
- **ARCH-05**: stop calling the blocking `close()` on the main thread (S).
- **ARCH-07**: make `exportTo` throw on a null stream and show Save-as feedback (S).
- **ARCH-01** and **ARCH-12**: fail release builds without a keystore or required config (S).
- **ARCH-18**: drop the unused `READ_MEDIA_IMAGES` permission (S).
- **ARCH-19**: add backup exclusion rules (S).
- **ARCH-14**: update BouncyCastle (S).
- **ARCH-16**: restrict JitPack, add the wrapper checksum and verification metadata (S).
- **ARCH-04**: export the Room schema and replace the destructive fallback (S).
- **ARCH-23**: delete about 1k lines of dead code and the unused window-size-class dependency (S).
- **ARCH-25**: add a PR trigger, lint and a release build to CI (S).
- **ARCH-17**: pin tessdata to a commit and verify sha256 (S).

## Needs device testing
- ARCH-02: targetSdk 36 behaviour (edge-to-edge, back, large screens).
- ARCH-05: jank or ANR when leaving Organize or e-sign during a render.
- ARCH-11: OAuth with both debug and release builds installed.
- ARCH-26: unsaved annotations after a font-scale or locale change with autosave off.
- ARCH-29: WebPdfWriter on API 26, 30 and 36 and its cancellation.
- ARCH-33: LTV validation after cleartext is restricted.
- ARCH-34: fd and native leak from rapid preview open and back.
- ARCH-03: heap growth across 20 or more opened documents (LeakCanary).
- ARCH-20: smoke test of the minified release build (R8-only crashes).
- 16 KB pages: install on a 16 KB-page emulator (Android 15+ "16k" image). PDFium and Tesseract `.so` files are aligned (verified); ML Kit and Play-services natives are unverified.
