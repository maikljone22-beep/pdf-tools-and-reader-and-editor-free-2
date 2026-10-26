# 04 — Security, Privacy, Backend/API & Integrations (prefix SEC)

## Scope & method

**Read (snapshot `D:\pdf\audit-wt`, commit da74d31):**
- `app/src/main/AndroidManifest.xml`, `res/xml/file_paths.xml`, `res/xml/network_security_config.xml`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `.gitignore`, `*.properties.example`, git history of `*.properties`.
- All workers: `ai-proxy/worker.js`, `ai-proxy/{collab,share,esign,connected,license}/worker.js`, `share/pages.js`, `esign/sign-page.js`, all `wrangler.toml`, `ai-proxy/README.md`.
- `core/billing/*`, `core/cloud/{OAuthPkce,CloudHttp,GoogleDrive}.kt`, `core/ai/{AiClient,DocAgent,DocCommands}.kt`, `core/collab/{CollabAnnots,CollabClient}.kt`, `core/connected/{EnterpriseProtection,ConnStore}.kt`, `core/esign/EsignClient.kt`, `core/share5/{ShareClient,EmailReview,AppDataBundle}.kt`, `core/ads/AdsManager.kt`, `core/forms/{JsSandbox,XfaSupport,XfaTemplate}.kt`, `core/forms5/{AcroJs,AcroJsLib,FormSubmitter}.kt`, `ui/forms5/{FormActions,InPageFormBar}.kt`, `core/files/{WifiTransferServer,ZipUtil,DocumentRepository}.kt`, `ui/cloud/{WifiScreen,DeepLinks}.kt`, `ui/connected/ConnRoutes.kt`, `ui/reader/ReaderIntents.kt`, `ui/edit5/ReaderLinks.kt`, `MainActivity.kt`, `core/tools5/{ShareAndPrint,ToPdf,RecipeRunner,Optimizer}.kt`, `android/print/WebPdfWriter.kt`, `core/pdf/{PdfOps,LockedPdf,DigitalSignature}.kt` (parts), `core/redact/{TrueRedactor,RedactApplier}.kt` (parts), `core/office5/{OPackage,XDom}.kt` (parts), `core/util/CrashLog.kt`, `core/ocr/OcrEngine.kt` (in-flux), strings (values-en).
- Grep sweeps: `Log.*`, `WebView`, `ACTION_VIEW`, `ZipInputStream/ZipFile`, `DocumentBuilderFactory`, URLs, `Manifest.permission.*`, `consent|privacy`, `setAllSecurityToBeRemoved`, `password`.

**Secrets / properties:** `ai.properties`, `cloud.properties`, `pro.properties`, `ads.properties`, `license.properties`, `keystore.properties` do **not exist** in either project root (only `ads.properties.example`, `license.properties.example`, which contain no secrets). All are listed in `.gitignore`. `git log --all -- '*.properties'` shows only `gradle.properties` / wrapper changes — no secret was ever committed. Consequence: in the current build every BuildConfig token/URL is the empty string, so AI proxy, collab, share, e-sign, connected docs, licence server, Box/OneDrive/Dropbox OAuth and offline Pro codes are **disabled**; findings on those paths are about what happens when they are configured (marked "latent" where relevant). No secret values were printed.

**Could not verify:** merged manifest (needs gradle: AD_ID permission, library-exported components), the bundled PDFium version in `io.legere:pdfiumandroid:2.0.1`, PdfBox-Android's `XMLUtil` hardening (jar not in local Gradle cache), anything runtime (no emulator). Live workers were not called.

## Capability inventory

| Capability | Status | Evidence |
|---|---|---|
| OAuth (OneDrive/Dropbox/Box) | works (PKCE S256 + random state; tokens plain prefs) | `core/cloud/OAuthPkce.kt:86-123` |
| Google Drive | works (Identity AuthorizationClient, `drive.file`+`drive.readonly`) | `core/cloud/GoogleDrive.kt:45-46,66-95` |
| AI assistant / agent | works; destructive agent commands need a confirmation card | `core/ai/DocCommands.kt:25-47` |
| AI proxy auth | partial: shared static token in APK; rate limit optional and off by default | `ai-proxy/worker.js:33,89-97`, `wrangler.toml` (RATE commented) |
| AI data disclosure / consent | missing (only podcast note) | strings search; `strings_ai2.xml ai2_pc_note` only |
| Form JavaScript sandbox | works (interpreted, ClassShutter deny-all, safe std objects, time limit, stack limit; side-effect APIs stubbed) | `core/forms/JsSandbox.kt:15-33`, `core/forms5/AcroJsLib.kt:197-218` |
| Form submit / OpenUri | works with host confirmation dialog | `ui/forms5/InPageFormBar.kt:101-117` |
| Wi-Fi transfer | works (PIN, HttpOnly SameSite cookie, traversal checks, Wi-Fi-only bind default) ; cleartext | `core/files/WifiTransferServer.kt`, `ui/cloud/WifiScreen.kt:69-71`, `core/sync/SyncPrefs.kt:34` |
| Unzip (zip-slip) | safe (canonical prefix check) | `core/files/ZipUtil.kt:37-38`; office parts kept in memory `core/office5/OPackage.kt:177-182` |
| XML (XXE) | XDom custom parser: no entities beyond built-ins; DOM parsers: hardening attempted but silently skipped on Android | `core/office5/XDom.kt:206-209,277`, `core/forms/XfaSupport.kt:72` |
| Redaction | strong: glyph/path/image removal, raster fallback, full save; gaps in ActualText/Alt | `core/redact/TrueRedactor.kt:36-87`, `RedactApplier.kt:23-28` |
| Password-protected PDFs | password kept in memory only (ReaderState), not persisted/logged | `ui/reader/ReaderState.kt:80,188` |
| Logging | no `Log.*` calls in app code; crash file local, sent only by user | grep (0 hits), `core/util/CrashLog.kt` |
| Ads consent | works (UMP before init; OFF unless ads.properties) | `core/ads/AdsManager.kt:75-111` |
| Licence (Pro) | latent: `Pro.ENFORCE=false`; server mode Ed25519 tokens, client mode HMAC secret in APK | `core/billing/Pro.kt:27`, `ActivationCodes.kt:25-27` |
| Connected-doc offline keys | Keystore AES-GCM | `core/connected/ConnStore.kt:58-80` |
| Analytics opt-in | misleading: toggle stored, no analytics code exists | `core/review/AcctPrefs.kt:22-33` (no consumer) |
| Virtual printer (exported PrintService) | OK: guarded by `BIND_PRINT_SERVICE` | `AndroidManifest.xml:114-121` |
| Backups | broad: `allowBackup=true`, no rules | `AndroidManifest.xml:25` |

### External services

| Service | Endpoint | Auth | Data sent | Timeout / retry | Offline |
|---|---|---|---|---|---|
| AI chat (proxy) | `AI_PROXY_URL/chat/completions` → Groq (`wrangler.toml PROVIDER_URL`) | `X-App-Token` (static, in APK) | up to 12 000 chars of document text (`AiClient.MAX_DOC_CHARS`), file name, chat, tool schemas | connect 30 s / read 120 s / write 60 s; no retry (`AiClient.kt:51-56`) | AI features error out; app usable |
| AI chat (user key) | user endpoint (default api.openai.com) | `Authorization: Bearer <user key>` | same | same | same |
| AI speech (proxy) | `/audio/speech` → OpenAI TTS | `X-App-Token` | text chunks ≤ TTS_MAX_CHARS | same | device TTS still available |
| Speech recognition | Android `SpeechRecognizer` (OS provider) | n/a | microphone audio | OS | depends on OS provider |
| Collab review | `COLLAB_URL/reviews…` (CF Worker + KV/R2) | optional `X-App-Token`; review id+6-char code; owner key | whole PDF, author name, annotations JSON | CloudHttp: connect 20 s / read 120 s / write 300 s; no app retry (`CloudHttp.kt:30-36`) | polling stops; local edits kept |
| Share (web review, view links, mail) | `SHARE_URL` | optional `X-App-Token`; owner key; link password (PBKDF2 100k) | PDF, reviewer names/e-mails, message | CloudHttp | feature unavailable |
| E-sign | `ESIGN_URL` | optional `X-App-Token`; owner key; per-signer link token | PDF, signer names/e-mails, field values (signature images), device string | CloudHttp | unavailable |
| Connected docs | `CONNECTED_URL` | optional `X-App-Token`; owner key; reader id (self-chosen) | title, owner name, version hashes/PDFs, reader name | CloudHttp + "quick" client | cached Keystore key for `offlineDays` |
| Licence server | `LICENSE_URL` (/activate, /verify-purchase, /check) | none (device id + code/purchase token) | hashed device id, code, Play purchase token | connect 10 / read 20 / call 30 s (`LicenseClient.kt:33`); Worker every 3 days | grace 7 days, provisional Play 3 days (`LicenseToken.kt:65-82`) |
| Google Play Billing / Developer API | Play / androidpublisher (server) | service account | purchase token | — | client grace 30 days (client mode) |
| OneDrive / Dropbox / Box / Drive | provider APIs | OAuth bearer | files user opens/saves | CloudHttp | SAF / local copies |
| Google userinfo | `openidconnect.googleapis.com/v1/userinfo` | bearer | — | 15/20 s | — |
| OCR language data | `github.com/tesseract-ocr/tessdata_best/raw/main/<lang>` | none | — | 30/120 s; size ≥100 kB check only | OCR unavailable for missing langs |
| TSA / OCSP / CRL | `http://timestamp.digicert.com` etc. | none | document hash | `TsaClient` with https fallback | signing w/o timestamp |
| AdMob + UMP | Google | — | ad identifiers (after consent) | SDK | no ads |
| Form submit | URL from the PDF | none | field values or whole PDF | 20/40/60 s | error message |
| Wi-Fi transfer | local `http://<lan-ip>:8080+` | 6-digit PIN → session cookie | files in Documents | 30 s socket timeout | n/a |

Not applicable / not present: Firebase/Crashlytics, analytics SDKs, NanoHTTPD, `addJavascriptInterface`, Rhino Java access.

## Findings

### SEC-01 AI proxy: static app token + optional, IP-based rate limit = unmetered paid LLM/TTS for anyone who unpacks the APK
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence: `app/build.gradle.kts:23` compiles `AI_APP_TOKEN` into BuildConfig (and `proguard-rules.pro:24` keeps BuildConfig verbatim); `ai-proxy/worker.js:33` the only auth is `X-App-Token === env.APP_TOKEN`; `worker.js:89-90` `if (!env.RATE) return null` — no limit unless a KV is bound, and `ai-proxy/wrangler.toml` ships the `[[kv_namespaces]] RATE` block commented out; the limit key is `CF-Connecting-IP` (`worker.js:91`), not a device. The README itself states the token is extractable (`ai-proxy/README.md:27`). The proxy also forwards every other body field unchanged (`worker.js:52-58`: only `model`/`max_tokens` overridden, e.g. `n`, `tools` pass through).
- User impact / scenario: one person extracts the token (apktool, 2 min) and scripts the endpoint; Groq/OpenAI bills (chat + TTS at 4096 chars/request) are uncapped, or quota exhaustion makes AI fail for every real user.
- Proposed fix: make RATE mandatory (refuse requests when `env.RATE` is missing), key it on an install id signed by Play Integrity (or at minimum IP + token), drop unknown body keys (allow-list `messages, stream, temperature, tools, tool_choice`), force `n=1`, add a global daily budget counter.
- Effort: M     Regression risk: Low     Dependencies: KV namespace; optional Play Integrity
- Validation: worker unit test: request without RATE → 503; 61st request/day → 429; body with `n:50` forwarded as `n:1`.

### SEC-02 Shared-review comments are merged into the user's PDF with no annotation allow-list (actions, Launch, appearance streams, widgets)
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence: server stores app-reviewer `data` verbatim (`ai-proxy/collab/worker.js:128-134`, no validation — compare the web path which validates via `share/worker.js:218-228 validWebNote`); client decodes any JSON to COS (`core/collab/CollabAnnots.kt:85-108`) and inserts it as a page annotation (`CollabAnnots.kt:160-164`); only `/P /Parent /Popup /IRT` are skipped (`:34`); merged into the user's file on save (`core/collab/CollabLive.kt:112 beforeSave`, called from `ui/reader/ReaderState.kt:389`). `IGNORED_SUBTYPES` (Link/Widget) is applied only when *exporting* own annotations (`:116`).
- User impact / scenario: any holder of the review link+code posts a `/Subtype /Link` with a `/Launch` action (see SEC-03), a Widget, a FileAttachment with an embedded payload, or a `/Square` with an `/AP` stream that paints fake text over the page; every reviewer's next save writes it permanently into their document.
- Proposed fix: on decode, accept only markup subtypes (Text, Highlight, Underline, StrikeOut, Squiggly, FreeText, Ink, Square, Circle, Line, Polygon, PolyLine, Stamp-without-AP-from-remote, Caret); strip `/A /AA /AP /FS /Dest /Parent`; regenerate appearances locally; apply the same allow-list server-side in `collab/worker.js postComments`.
- Effort: M     Regression risk: Med (stamps/ink look regenerated)     Dependencies: none
- Validation: unit test: decoded Link/Launch/Widget/FileAttachment payload → rejected; Highlight with `/A` → `/A` removed.

### SEC-03 One tap on a PDF "Launch"/"GoToR" link shares any app-readable file (including app-private data) through the FileProvider
- Type: security
- Status: CONFIRMED (traced in code); exploitation needs user to pick a target app
- Severity: Medium      Priority: P1
- Evidence: `ui/reader/ReaderScreen.kt:286` link tap with no URI/dest → `followFileLinkAt`; `ui/edit5/ReaderLinks.kt:126` `File(l.spec.file)` accepts absolute paths and `../` relative paths from the PDF's `/F`; non-PDF targets go to `shareUri(target)` + `FLAG_GRANT_READ_URI_PERMISSION` chooser (`:130-133`); `res/xml/file_paths.xml:3,7` exposes `files-path "."` and `external-path "."`, so e.g. `/data/user/0/com.smartdocs.editor/files/datastore/settings.preferences_pb` (AI API key, `core/data/SettingsStore.kt:11`) or `files/signatures/*` is grantable. No confirmation dialog.
- User impact / scenario: a received PDF has a "Open attachment" link whose `/F` is `../../../../data/user/0/com.smartdocs.editor/files/datastore/settings.preferences_pb`; the user taps it, picks a messenger in the chooser, and the private file is sent.
- Proposed fix: resolve the target canonically and only allow files inside the document's own directory (or the user's Documents folder) and only known document extensions; show a confirmation with the file name; narrow `file_paths.xml` to the specific sub-folders actually shared (`Documents/`, `cache/tmp/`).
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test of the path check; device test with a crafted Launch link to `files/datastore/…` → refused.

### SEC-04 E-sign server: the requester can sign on behalf of every signer, and can register any PDF as the "verified final"
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P1
- Evidence: `ai-proxy/esign/worker.js:70` owner `GET /requests/:id/links` returns every signer's bearer token; signer routes authenticate only by that token (`:188-190`), and `submit` records `signed` with the caller's IP/UA (`:229-244`) — the owner's own request is indistinguishable from the signer's. `PUT /requests/:id/final` (`:82,123-138`) accepts any PDF once status is completed and indexes its SHA in `sha:` so `GET /verify/:sha` reports it as the completed, signed document (`:270-281`) — the server never checks the final PDF against the original + submitted values.
- User impact / scenario: a sender creates a request, fetches the links, submits "consent: true" with a drawn signature for the counter-party, then uploads an altered final PDF; the public verification page vouches for it.
- Proposed fix: never return signer tokens after the initial send (store only a hash; mail/links shown once); bind signing to an e-mail OTP or the signer's app account; on `final`, require the final to be a PAdES-signed incremental update of `originalSha` (verify byte-range prefix hash) or mark verify output as "uploaded by sender".
- Effort: L     Regression risk: Med     Dependencies: mail provider
- Validation: worker test: owner key cannot read tokens after send; final whose prefix hash ≠ original → 409.

### SEC-05 Public review / view-link / signing pages render untrusted PDFs with pdf.js 3.11.174 and eval enabled (CVE-2024-4367 class), no SRI, no CSP
- Type: security
- Status: PROBABLE (version and options confirmed; exploit not run)
- Severity: Medium      Priority: P1
- Evidence: `ai-proxy/share/pages.js:4` pdf.js 3.11.174 from cdnjs; `:44` `getDocument({url, cMapUrl, …})` without `isEvalSupported:false`; same version in `ai-proxy/esign/sign-page.js:4,47`; `<script src>` tags have no `integrity` and responses set no CSP (`share/worker.js:497-499`). PDFs are supplied by any review owner / link creator / e-sign requester. The share origin stores a global reviewer id `localStorage sd_pid` (`pages.js:79`) that authorises writes in all reviews (see SEC-06).
- User impact / scenario: a malicious PDF executes script on the share/e-sign origin: steals `sd_pid` to delete/impersonate the victim's notes in other reviews, or on the signing page auto-submits a decline/sign, or overlays a phishing form on a trusted domain.
- Proposed fix: upgrade to pdf.js ≥ 4.2.67 and pass `isEvalSupported:false`; add SRI hashes; send `Content-Security-Policy: default-src 'none'; script-src <cdn> 'nonce-…'; worker-src blob: <cdn>; connect-src 'self'`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: load the public CVE-2024-4367 PoC PDF in the patched page → no script execution.

### SEC-06 Review participant identity is a client claim: any code holder can overwrite or delete other reviewers' comments
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P1
- Evidence: `ai-proxy/collab/worker.js:121-124` key `comments:<id>:<participant>` with `participant` taken from the body; `allComments` returns every participant id to every reader (`:147,151`); web path accepts any `web-…` id (`share/worker.js:230-233`). The header comment "Each participant writes only its own KV key" (`collab/worker.js:21`) is not enforced. Client deletes by `/NM` regardless of author (`core/collab/CollabAnnots.kt:153-156`).
- User impact / scenario: a reviewer reads `participant` of a colleague from GET comments and posts `{nm, deleted:true, modified: 9e15}` items — the colleague's comments disappear for everyone and cannot be re-added (future `modified` wins, `:131-132`).
- Proposed fix: issue a per-participant secret on first post (`participantKey`, store its hash), require it for writes; clamp `modified` to `now + 5 min`; on the client only accept deletes for NMs that the deleting participant created.
- Effort: M     Regression risk: Med     Dependencies: app + worker change together
- Validation: worker test: write to another participant without its key → 403; `modified` far future clamped.

### SEC-07 `allowBackup="true"` with no backup rules: tokens, AI key, licence device id, signatures and fill-profile PII go to cloud/device-transfer backups
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P1
- Evidence: `AndroidManifest.xml:25` `android:allowBackup="true"`, no `android:dataExtractionRules`/`fullBackupContent`. Backed-up stores include: `shared_prefs/cloud_<provider>.xml` refresh tokens (`core/cloud/OAuthPkce.kt:50-55`), `datastore/settings` AI key (`SettingsStore.kt:11,33`), `license_state.xml` device id + licence (`core/billing/LicenseClient.kt:46-55,57`), `pro_entitlement.xml` (`Pro.kt:77`), `connected_docs` owner keys (`ConnStore.kt:31`), `files/signatures/*`, Room DB (fill profile, recents, text index).
- User impact / scenario: (a) restoring a backup onto a second phone restores the cached `device` id, so a licence bound to "device A" is valid on device B (`LicenseClient.deviceId` returns the stored value first, `:47-48`) — device limits are bypassable; (b) OAuth refresh tokens and the AI key leave the device in backups.
- Proposed fix: add `data_extraction_rules.xml` / `backup_rules.xml` excluding `license_state`, `pro_entitlement`, `cloud_*`, `connected_*`, `datastore/settings.preferences_pb`, `signatures/`; derive the licence device id on every start instead of caching it (or store it in `noBackupFilesDir`).
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: `adb shell bmgr backupnow` + restore on another emulator → no tokens, licence re-check required.

### SEC-08 A keyless custom AI endpoint (e.g. local Ollama) is silently replaced by the cloud proxy when a proxy is configured
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P1
- Evidence: `core/ai/AiClient.kt:37,40` `if (settings.aiApiKey.isBlank() && usesProxy()) AI_PROXY_URL else settings.aiEndpoint` — the user's endpoint is ignored whenever the key is blank; keyless local servers are an explicit preset (`ui/ai/AiSettingsScreen.kt:34` Ollama) and are considered "available" (`AiClient.kt:46`).
- User impact / scenario: a privacy-minded user sets `http://192.168.1.5:11434/v1` without key to keep documents local; in a release with `ai.properties` configured, every document is sent to Groq via the proxy instead.
- Proposed fix: use the proxy only when the endpoint is still the default (`https://api.openai.com/v1`) and the key is blank, or add an explicit "Use SmartDocs cloud AI" switch.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test of the constructor selection with (custom endpoint, blank key, proxy set) → custom endpoint.

### SEC-09 No disclosure/consent before document text is sent to third-party AI providers
- Type: security
- Status: CONFIRMED (traced in code; string search)
- Severity: Medium      Priority: P2
- Evidence: the only data-flow note is the podcast one (`res/values-en/strings_ai2.xml ai2_pc_note`); no consent flag in `AcctPrefs`/settings; summarise/translate/Q&A send up to 12 000 chars (`AiClient.kt:47,60-77`) to the proxy → Groq (`ai-proxy/wrangler.toml PROVIDER_URL`) or OpenAI TTS. Onboarding says documents are uploaded only "if you choose (… AI assistant)" (`strings_acct.xml ac_onb3_text`) but never names the processor. PII scan is local (`a5_pii_hint`) but is not offered before sending.
- User impact / scenario: users of a student/office app send IDs, contracts, medical PDFs to a US LLM provider without knowing which one; Play Data-safety and GDPR Art. 13 require naming it.
- Proposed fix: one-time sheet on first AI use: provider name, what is sent (page text, file name), retention link, "Don't send / Allow"; store flag; offer the local PII scan from that sheet.
- Effort: S     Regression risk: Low     Dependencies: privacy policy text
- Validation: UI test: first AI action shows the sheet; declining sends no request (OkHttp MockWebServer sees 0 calls).

### SEC-10 Activation codes are forgeable from the APK secret, and server mode inherits it via the migration path
- Type: security
- Status: CONFIRMED (traced in code); latent (`pro.properties` absent, `Pro.ENFORCE=false`)
- Severity: Medium      Priority: P2
- Evidence: `app/build.gradle.kts:34-35` `PRO_CODE_SECRET` in BuildConfig; `core/billing/ActivationCodes.kt:62-74` verifies HMAC with it; `generate()` is public (`:118-126`). Server mode migrates offline codes by sending them to `/activate` (`LicenseClient.kt:250-256`), which only works if the server `CODE_SECRET` equals the APK secret (`ai-proxy/license/worker.js:10` "same format as the offline codes"). Entitlement itself is a plain SharedPreferences record (`Pro.kt:94-97`) in client mode.
- User impact / scenario: once any release ships with `codeSecret`, anyone can mint unlimited lifetime codes; reusing the secret on the server makes those codes valid there too (device limit is per code, codes are free to mint).
- Proposed fix: never ship `codeSecret` in a public build; use a new server-only secret and migrate old codes by an allow-list (hash of issued codes) instead of HMAC; or switch codes to Ed25519-signed blobs verified with the public key.
- Effort: M     Regression risk: Low     Dependencies: licence server
- Validation: unit test: code made with the APK secret rejected by server after rotation.

### SEC-11 Documents opened from other apps are copied verbatim into external app storage and kept indefinitely
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `core/files/DocumentRepository.kt:27` `docsDir = getExternalFilesDir(null)/Documents`; every content URI is copied there (`:56-72`) and never cleaned; reviews and printed jobs also land there (`core/collab/CollabStore.kt:66`, `core/tools5/ShareAndPrint.kt:131`). minSdk 26: on API 26-28 (and 29 with legacy storage) apps holding READ_EXTERNAL_STORAGE can read `Android/data/<pkg>/`.
- User impact / scenario: a bank statement opened once from Gmail on an Android 9 phone stays readable by any storage-permission app; users don't know a second copy exists.
- Proposed fix: keep working copies in `filesDir` (internal) on API < 30, or at least for content-URI imports; add "clear cached copies" + automatic expiry of copies whose origin is a content URI.
- Effort: M     Regression risk: Med (paths stored in Room/recents)     Dependencies: migration of existing paths
- Validation: device test on API 28: second app cannot list the copy.

### SEC-12 CAMERA permission is declared but never requested, so "Take photo" (signature image) silently fails
- Type: bug
- Status: PROBABLE (code confirmed; needs device test)
- Severity: Medium      Priority: P1
- Evidence: `AndroidManifest.xml:7` declares CAMERA; no `Manifest.permission.CAMERA` request anywhere (grep); `ui/signature/SignatureImageTab.kt:49,56` launches `ActivityResultContracts.TakePicture()` inside `runCatching`. Android throws SecurityException for `ACTION_IMAGE_CAPTURE` when the caller declares CAMERA but has not been granted it — swallowed, so the button does nothing. The ML Kit document scanner does not need CAMERA.
- User impact / scenario: user taps "Take photo" to capture a signature; nothing happens.
- Proposed fix: remove the CAMERA permission from the manifest (simplest; then TakePicture needs no permission), or request it before launching.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: device test on API 34: Take photo opens the camera app.

### SEC-13 READ_MEDIA_IMAGES declared but unused (Play "Photo and video permissions" policy)
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `AndroidManifest.xml:16`; no runtime request or use (grep `READ_MEDIA`); `ui/files/FilesScreen.kt:54,120` comment states it is not needed. Images are picked via SAF/photo picker.
- User impact / scenario: Play Console rejects or requires a declaration form for apps holding READ_MEDIA_IMAGES without core need; also appears as an over-broad permission to users.
- Proposed fix: delete the permission line.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: build merged manifest; image pickers still work.

### SEC-14 Re-protection replaces the owner password with the user password (permissions become meaningless)
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `core/pdf/PdfOps.kt:42-48` `load()` re-protects an encrypted document with `StandardProtectionPolicy(pw, pw, ap)`; `compress()` re-protects with `protect(tmp, prot, password, password)` (`:358-361`). For a permission-only PDF (empty user password) owner becomes `""`.
- User impact / scenario: a company PDF that allows opening but forbids printing/editing, edited once (watermark, page numbers, compress) in SmartDocs, is saved with owner = user password: anyone who can open it now has full owner rights in Acrobat.
- Proposed fix: when the document was opened with the user password, keep the original encryption dictionary (PdfBox: `doc.setAllSecurityToBeRemoved(false)` and save incrementally) or ask for the owner password; at minimum generate a random owner password (`ProtectAdvanced.randomPassword`) instead of reusing the user one.
- Effort: M     Regression risk: Med     Dependencies: none
- Validation: unit test: permission-only PDF → after watermark, `AccessPermission.canPrint()` still false with empty password.

### SEC-15 Batch recipes on password-protected PDFs: some steps silently drop encryption, others then fail
- Type: data-integrity
- Status: CONFIRMED (traced in code); outcome needs device test
- Severity: Medium      Priority: P2
- Evidence: `core/tools5/RecipeRunner.kt:111` after any non-PROTECT step `pw = null`; `Optimizer.optimize` sets `isAllSecurityToBeRemoved = true` (`core/tools5/Optimizer.kt:172`) → output unencrypted; but PdfOps-based steps (watermark/page numbers/rotate use `PdfOps.load`, `PdfOps.kt:42-48`) keep encryption, so the *next* step opens an encrypted file with `null` password and throws.
- User impact / scenario: "Optimize → Watermark" on a protected file outputs an unprotected PDF without warning; "Watermark → Page numbers" fails with a password error.
- Proposed fix: track `pw` per step result (`pw` stays the same unless the step is known to decrypt), and re-protect the final output with the input password unless the recipe explicitly removes protection; show "protection removed" in the log.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit tests with an encrypted fixture for both recipes.

### SEC-16 Redaction leaves text in `/ActualText` / `/Alt` (marked content and structure tree); sanitising is off by default
- Type: security
- Status: PROBABLE (no handling found; needs a tagged-PDF test)
- Severity: Medium      Priority: P2
- Evidence: `core/redact/TrueRedactor.kt:69-87` removes glyphs/paths/images/annotations only; `PageInspector.kt:95-128` reads BDC properties only for optional content; no reference to `ActualText`, `Alt`, `StructTreeRoot` in `core/redact/` (grep). Metadata/bookmarks are cleaned only if `Options.sanitize` (default false, `RedactApplier.kt:23-24`).
- User impact / scenario: a Word-exported tagged PDF has `/Span <</ActualText (John Smith)>> BDC … EMC` or a struct element `/Alt`; after redaction the name is still extractable by screen readers / `pdftotext -layout`, and still in bookmarks/title if the user did not tick sanitize.
- Proposed fix: when a glyph inside a BDC with `/ActualText` is removed, drop that property (rewrite the BDC operand) and blank `/ActualText`/`/Alt` of struct elements whose `/K` MCIDs are on the redacted area; default `sanitize=true` for METADATA+BOOKMARKS when marks contain text.
- Effort: M     Regression risk: Med     Dependencies: none
- Validation: unit test with a tagged fixture: after redact, PDFBox text extraction + struct-tree walk do not contain the term.

### SEC-17 Form JavaScript has a time limit but no memory limit: a PDF can crash the app with OOM
- Type: security
- Status: PROBABLE (code path confirmed; needs device test)
- Severity: Medium      Priority: P2
- Evidence: `core/forms/JsSandbox.kt:17-23` (time + stack limits only); `JsSandbox.scriptError` treats every `VirtualMachineError` except StackOverflow as fatal and rethrows (`:46`), used in doc-level script init (`core/forms5/AcroJs.kt:101-104`) and event runs (`:133-139`).
- User impact / scenario: a document-level script `var s='x';for(;;)s+=s;` reaches ~1 G chars within the init deadline → OutOfMemoryError rethrown → app crash on opening the PDF (crash loop if "restore last session" reopens it).
- Proposed fix: catch `OutOfMemoryError` from script evaluation as a script error (disable JS for that document), and cap string/array growth by checking `Runtime.totalMemory()` in `observeInstructionCount`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: instrumented test with the script above → document opens, JS disabled message.

### SEC-18 Share worker can be used as an e-mail relay
- Type: security
- Status: CONFIRMED (traced in code); latent until MAIL_* configured
- Severity: Medium      Priority: P2
- Evidence: `ai-proxy/share/worker.js:91-105` any review owner sets up to 50 arbitrary reviewer e-mails and a 2000-char message; `:123-131` `remind`/`invite` mail them, or a single arbitrary `b.to`, with no rate limit or verification; owner = whoever created the review (collab create needs only the optional app token, `collab/worker.js:53-56`).
- User impact / scenario: spammer creates reviews and calls `/invite` repeatedly; mails go out from the project's MAIL_FROM domain → domain reputation/blocklisting, provider bill.
- Proposed fix: per-review and per-day mail caps in KV (e.g. 1 invite + 3 reminders per reviewer), restrict `to` to the stored reviewer list, require the owner e-mail to be verified before sending to others.
- Effort: S     Regression risk: Low     Dependencies: KV
- Validation: worker test: 4th reminder in a day → 429; `to` not in list → 400.

### SEC-19 Connected documents: "blocked" readers come back with a new reader id; without approval anyone with the doc id gets the protection key
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Medium      Priority: P2
- Evidence: `ai-proxy/connected/worker.js:117-133` reader id `rid` is client-chosen; a new `rid` is created as `allowed` unless `requireApproval` (`:123`); the response includes `key` for protected docs when status ok (`:131-133`); client id is a random UUID in prefs (`core/connected/ConnStore.kt:50`) — clearing app data yields a new one. The doc id travels inside the PDF (XMP marker, `core/connected/ConnMarker.kt`).
- User impact / scenario: owner blocks a former employee; they reinstall the app, open their copy, get a fresh `rid` → `allowed` → key → document opens.
- Proposed fix: default `requireApproval=true` for protected documents; document that blocking without approval is advisory; optionally bind readers to a signed-in account.
- Effort: S     Regression risk: Low     Dependencies: product decision
- Validation: worker test: protected doc → new rid gets `pending` and no key.

### SEC-20 Global cleartext permitted
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P2
- Evidence: `res/xml/network_security_config.xml` `<base-config cleartextTrafficPermitted="true">` (justified for OCSP/CRL/TSA). Effect: user AI endpoints over `http://` send the API key and document text in clear (`AiClient.kt:115-118`), form submits to `http://` URLs from PDFs (`FormSubmitter.kt:30-41`; the confirmation dialog shows only the host, `InPageFormBar.kt:109`).
- User impact / scenario: user types `http://my-server/v1` with an OpenAI-compatible key on café Wi-Fi → key and text sniffable.
- Proposed fix: keep cleartext (needed for revocation data, which is signed) but warn in AI settings when the endpoint is `http://` and not a private/loopback address; show "unencrypted" in the submit dialog for `http://`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: UI test: http public endpoint shows warning.

### SEC-21 Wi-Fi transfer: plain HTTP, PIN guessable over time, idle timer kept alive by the attacker
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P2
- Evidence: `core/files/WifiTransferServer.kt:94` `http://`; lockout resets the counter after 60 s (`:151-152` → 5 guesses/min/IP indefinitely); any request refreshes `lastActivity` (`:112`), so the 10-min auto-stop never fires during guessing; unbounded `newCachedThreadPool` (`:39`) + 30 s socket timeout allows thread exhaustion. Mitigations present: server lives only while the screen is open (`ui/cloud/WifiScreen.kt:51`), Wi-Fi-only bind by default (`SyncPrefs.kt:34`), traversal checks (`:232`, `:258,299`), HttpOnly SameSite cookie (`:148`), filenames escaped (`:203`).
- User impact / scenario: on a shared network, an attacker sniffs the PIN/session cookie and all transferred files, or keeps guessing (several LAN IPs) while the user leaves the screen open.
- Proposed fix: global (not per-IP) failure counter with exponential lockout and stop the server after e.g. 20 failures; refresh idle timer only for authenticated requests; bounded pool (8 threads); show "unencrypted, use trusted networks" on the screen.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test: 20 wrong PINs from different addresses → server stops.

### SEC-22 Deep link `smartdocs://esign/req/<id>` builds an unencoded route (crash on crafted links)
- Type: bug
- Status: PROBABLE (needs device test)
- Severity: Low      Priority: P2
- Evidence: `ui/cloud/DeepLinks.kt:54` → `ui/connected/ConnRoutes.kt:63-65` `nav.navigate(ConnRoutes.esignDetail(id))`, `esignDetail(id) = "esign/req/$id"` (`:27`, no `Uri.encode`, unlike `esignSign`); the branch is not wrapped in `runCatching` (unlike the oauth branch). A decoded segment containing `/` (from `%2F`) gives a route that matches no destination → `IllegalArgumentException` from navigation-compose.
- User impact / scenario: a web page or message with `smartdocs://esign/req/a%2Fb` crashes the app when tapped.
- Proposed fix: validate `id` with `^[a-z0-9]{12}$` before navigating and wrap the whole handler in `runCatching`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: instrumented test firing the crafted VIEW intent.

### SEC-23 Collab/share/e-sign/connected/licence workers are open when APP_TOKEN is unset; storage abuse
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P2
- Evidence: `collab/worker.js:53` `if (env.APP_TOKEN && …)`; same pattern `share/worker.js:54`, `esign/worker.js:57`, `connected/worker.js:33`; licence `/activate` etc. need no token (`license/worker.js:46-48`). Each create stores up to 20 MB for 90-365 days (`collab:24-26`, `share:298-317`).
- User impact / scenario: anonymous users use the review/link service as free 20 MB file hosting; R2/KV bills grow.
- Proposed fix: make APP_TOKEN required (fail closed), add per-IP daily create quota in KV, and an overall storage cap.
- Effort: S     Regression risk: Low     Dependencies: KV
- Validation: worker test: no token configured → 503 on create.

### SEC-24 Outdated BouncyCastle 1.72 (known DoS/timing CVEs) used for PDF signature validation
- Type: security
- Status: PROBABLE (version confirmed; reachability not proven)
- Severity: Low      Priority: P2
- Evidence: `app/build.gradle.kts` `bcpkix/bcprov-jdk15to18:1.72`. CVE-2024-29857 (ECCurve F2m parameters → CPU exhaustion when parsing a crafted certificate), CVE-2024-30172 (Ed25519 verification infinite loop with crafted signature/key) and CVE-2024-30171 are fixed in 1.78. Signatures embedded in untrusted PDFs are parsed by `core/pdf/DigitalSignature.kt`; licence tokens are verified with BC Ed25519 (`core/billing/LicenseToken.kt:38-42`, fixed public key).
- User impact / scenario: opening the signature panel of a crafted PDF hangs the validation thread.
- Proposed fix: bump to `1.78.1` or later (same artifact names).
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: existing signing/validation unit tests.

### SEC-25 PDF URI links: any scheme forwarded to ACTION_VIEW without confirmation
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `ui/reader/ReaderIntents.kt:30-40` http/https → Custom Tab; everything else (e.g. `smartdocs://esign/sign?...`, `intent:`, `market:`, `sms:`) goes straight to `startActivity(ACTION_VIEW)`; no "open link?" dialog. `file://` is blocked by StrictMode (caught). Form OpenUri has a confirmation (`InPageFormBar.kt:111`), reader links do not.
- User impact / scenario: a PDF link that looks like a page reference opens a phishing site or triggers the app's own deep link (e.g. joining an attacker's review/signing flow) on a single tap.
- Proposed fix: confirmation sheet showing the full URL for non-http(s) schemes and for http(s) whose visible text differs from the host; block `intent:` and own `smartdocs:` scheme from documents.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: UI test: tapping a `smartdocs://` link in a PDF shows the dialog.

### SEC-26 HTML/EML/EPUB → PDF WebView runs scripts and loads remote resources
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `android/print/WebPdfWriter.kt:37-41,58` `javaScriptEnabled = true`, `domStorageEnabled = true`, `loadDataWithBaseURL(null, html, …)`; no `shouldInterceptRequest`/`blockNetworkLoads`; scripts are removed with a regex only for some sources (`core/tools5/SourceReaders.kt:210,264`); plain HTML files are passed unchanged (`ToPdf.kt:72`). No JS bridge is exposed, file access default off (targetSdk 35).
- User impact / scenario: converting a received e-mail to PDF fires its tracking pixels and remote scripts (reveals IP/open time to the sender).
- Proposed fix: `settings.blockNetworkLoads = true` (images are already inlined for EPUB), `javaScriptEnabled = false` for file conversions (keep it only for the explicit URL→PDF tool).
- Effort: S     Regression risk: Low (verify layouts that need JS)     Dependencies: none
- Validation: MockWebServer image URL in an EML → no request.

### SEC-27 Exported VIEW/SEND filters accept `file://` and the app's own provider URIs (confused deputy)
- Type: security
- Status: CONFIRMED (traced in code); no direct exfiltration path found
- Severity: Low      Priority: P3
- Evidence: `AndroidManifest.xml:59,82-83` `scheme="file"`; `MainActivity.kt:111` `if (uri.scheme == "file") { incoming.value = uri }`; `DocumentRepository.kt:56` `File(uri.path!!)`; `ShareImport.read` copies any EXTRA_STREAM the app can open, including `content://com.smartdocs.editor.fileprovider/files/...` (`core/tools5/ShareAndPrint.kt:73-83`).
- User impact / scenario: another app can make SmartDocs open its own private files (e.g. as a "recent" entry); combined with share/convert tools a user could be tricked into exporting them.
- Proposed fix: drop `file` scheme from the filters (modern senders use content URIs) or reject paths under `dataDir`; reject EXTRA_STREAM URIs whose authority is our own provider.
- Effort: S     Regression risk: Low (old file managers)     Dependencies: none
- Validation: instrumented test sending `file:///data/data/<pkg>/…` → refused.

### SEC-28 Zip bombs: Office packages are fully inflated into memory; unzip has no size cap
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `core/office5/OPackage.kt:177-182` `m[e.name] = zin.readBytes()` for every entry; `core/files/ZipUtil.kt:30-48` no total-size or ratio limit. Zip-slip is handled correctly (`ZipUtil.kt:38`).
- User impact / scenario: a 100 kB .docx that inflates to several GB crashes the app (OOM) on open / share-to-PDF.
- Proposed fix: cap per-entry and total inflated size (e.g. 200 MB) and entry count; throw `OfficeFormatException`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test with a generated 1 GB-of-zeros entry → clean error.

### SEC-29 Licence server trusts the client-supplied productId and binds unlimited devices to one purchase
- Type: security
- Status: CONFIRMED (traced in code); latent (`ENFORCE=false`)
- Severity: Low      Priority: P3
- Evidence: `ai-proxy/license/worker.js:166-187` `plan: productId` from the body is signed into the token; Play's `st.productId` (`:163`) is never compared; `rec.devices.push(device)` without a limit (`:182`). Acknowledge uses the client productId (`:178`).
- User impact / scenario: a modified client sends a monthly purchase token with `productId: "pro_yearly"` (cosmetic today) or shares one purchase token across many devices.
- Proposed fix: use `st.productId` for `plan/product`, reject mismatch; cap devices per purchase (e.g. 5) with an admin unbind.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: worker test with mismatching productId → 400.

### SEC-30 Credentials stored in plain app-private preferences
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: OAuth access/refresh (`core/cloud/OAuthPkce.kt:50-55`), Drive access token (`GoogleDrive.kt:83-84`), AI key in DataStore (`SettingsStore.kt:109,147`), collab/share/e-sign/connected owner keys (`ConnStore.kt:20,44`). Contrast: connected-doc keys use Keystore AES-GCM (`ConnStore.kt:58-80`).
- User impact / scenario: on rooted devices / via backups (SEC-07) long-lived refresh tokens are readable.
- Proposed fix: wrap refresh tokens and the AI key with the same Keystore helper used for connected keys.
- Effort: S     Regression risk: Low     Dependencies: SEC-07
- Validation: unit test round-trip; prefs file contains ciphertext only.

### SEC-31 Workers return internal error details
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `license/worker.js:34` `detail: String(e.message)` including `"google token: " + JSON.stringify(j)` (`:144`); `collab/worker.js:34`, `share/worker.js:41`, `esign/worker.js:39`, `connected/worker.js:26` echo exception messages.
- User impact / scenario: configuration and Google OAuth error bodies are visible to any caller (recon aid).
- Proposed fix: log server-side (`console.error`) and return a generic `{error:"server"}`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: worker test: thrown error → body without message.

### SEC-32 Web review / view link "download disabled" only hides the `dl=1` variant
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `share/worker.js:195-205` (web review) and `:355-369` (view link) serve the full PDF without `dl=1`; only the attachment header is withheld. The app honestly says so for view links (`strings_share5.xml sh5_view_note`), not for web review "allowDownload".
- User impact / scenario: owner believes reviewers cannot keep a copy.
- Proposed fix: reuse the `sh5_view_note` wording on the review "allow download" switch.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: string review.

### SEC-33 Android DOM parsers: XXE hardening is silently ineffective; XFDF import path unverified
- Type: security
- Status: HYPOTHESIS (needs device test)
- Severity: Low      Priority: P3
- Evidence: `core/forms/XfaSupport.kt:72-73`, `XfaTemplate.kt:106-107` wrap `setFeature(disallow-doctype-decl)` in `runCatching` — Android's `DocumentBuilderFactory` throws for unsupported features, so the DTD ban is skipped. Android's KXml-based DOM does not fetch external entities, but internal entity expansion (billion laughs) behaviour is unverified. XFDF goes through PdfBox `FDFDocument.loadXFDF` (`core/annot/CommentExchange.kt:250`), whose XMLUtil hardening could not be inspected. OOXML uses the custom `XDom` (no entity expansion, `XDom.kt:277`) — safe.
- User impact / scenario: an XFA PDF with nested internal entities freezes the form engine.
- Proposed fix: reject XFA/XFDF bytes containing `<!DOCTYPE` before parsing (cheap and safe for these formats).
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: device test with a billion-laughs XFA packet.

### SEC-34 OCR language files downloaded from a mutable branch without integrity check (in-flux area)
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `core/ocr/OcrEngine.kt:47` `…/tessdata_best/raw/main/$lang.traineddata`, only `tmp.length() < 100_000` check (`:66`), then parsed by native Tesseract.
- User impact / scenario: a changed/compromised upstream file is loaded by native code on every OCR.
- Proposed fix: pin a commit hash in the URL and verify SHA-256 per language.
- Effort: S     Regression risk: Low     Dependencies: hash table
- Validation: unit test with a wrong hash → download rejected.

### SEC-35 Google Drive uses the restricted `drive.readonly` scope
- Type: arch-risk
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `core/cloud/GoogleDrive.kt:45-46` `drive.file` + `drive.readonly` (`FULL_ACCESS=false`, `:39`).
- User impact / scenario: Google OAuth verification for restricted scopes requires a yearly CASA security assessment; without it the consent screen shows "unverified app" and a 100-user cap.
- Proposed fix: use `drive.file` + the Drive Picker for opening arbitrary files, drop `drive.readonly`.
- Effort: M     Regression risk: Med     Dependencies: Picker integration
- Validation: OAuth consent screen review.

### SEC-36 Ads SDK always bundled even when ads are off
- Type: arch-risk
- Status: PROBABLE (merged manifest not built)
- Severity: Low      Priority: P3
- Evidence: `app/build.gradle.kts` `play-services-ads:24.4.0` unconditional; manifest always gets an `APPLICATION_ID` (sample id by default, `AndroidManifest.xml:131-133`, `build.gradle.kts:42`). play-services-ads normally merges `com.google.android.gms.permission.AD_ID` and an init provider.
- User impact / scenario: Play Data-safety must declare advertising ID collection even for builds that never show ads; extra startup work.
- Proposed fix: put ads in a product flavor, or remove AD_ID with `tools:node="remove"` when `ADS_ENABLED=false`.
- Effort: S     Regression risk: Low     Dependencies: build
- Validation: inspect merged manifest of release build.

### SEC-37 AI proxy model/param passthrough and naive comparisons (minor hardening)
- Type: security
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P3
- Evidence: `ai-proxy/worker.js:53` `Math.min(body.max_tokens || 1500, 2000)` — a negative/string value passes as is; token compare with `!==` (`:33`, also admin key `license/worker.js:50`); `OPTIONS` returns 204 for any path before auth (`:27`, harmless).
- User impact / scenario: malformed requests reach the provider; negligible timing side channel.
- Proposed fix: `max_tokens = clamp(int, 1, 2000)`; constant-time compare via `crypto.subtle.timingSafeEqual`.
- Effort: S     Regression risk: Low     Dependencies: SEC-01
- Validation: worker unit tests.

### SEC-38 Box client-secret fallback and unauthenticated token relay
- Type: security
- Status: CONFIRMED (traced in code); latent
- Severity: Low      Priority: P4
- Evidence: `core/cloud/OAuthPkce.kt:42-45` falls back to `CLOUD_BOX_CLIENT_SECRET` in the APK; `collab/worker.js:51,208-220` `/oauth-token/box` is reachable without app token (before the token check at `:53`).
- User impact / scenario: if `boxClientSecret` is ever put in cloud.properties, it ships in every APK.
- Proposed fix: remove the APK fallback; keep only the worker relay (acceptable for a public client).
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: build with secret set → compile error/lint.

### SEC-39 Analytics opt-in toggle does nothing
- Type: usability
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P4
- Evidence: `core/review/AcctPrefs.kt:22-33` stores `analytics_opt_in`; no reader of `analyticsOptIn` outside the settings/onboarding UI (`ui/help/HelpScreen.kt:52-87`, `ui/onboarding/OnboardingScreen.kt:53-67`); no analytics SDK in dependencies.
- User impact / scenario: users are asked to consent to statistics that are never collected (harmless, but the privacy policy/Data-safety must match).
- Proposed fix: hide the toggle until analytics exist, or wire it to the future SDK init.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: UI review.

### SEC-40 Dead Wi-Fi screen never shows the PIN
- Type: arch-risk
- Status: CONFIRMED (traced in code)
- Severity: Low      Priority: P4
- Evidence: `ui/tools/MiscTools.kt:66-112 WifiTransferScreen` creates a PIN-protected server but never displays `server.pin`; it is unreferenced (the "wifi" tool routes to `ui/cloud/WifiScreen.kt`, `ui/tools/ToolScreens.kt:69`).
- User impact / scenario: none today; if re-wired, users could never log in.
- Proposed fix: delete `WifiTransferScreen`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: build.

## Quick wins
SEC-03 (path check + narrow file_paths), SEC-05 (pdf.js upgrade + `isEvalSupported:false` + SRI), SEC-07 (backup rules), SEC-08 (proxy selection), SEC-12 (remove CAMERA), SEC-13 (remove READ_MEDIA_IMAGES), SEC-15 (recipe password tracking), SEC-17 (catch OOM), SEC-18 (mail caps), SEC-22 (validate id), SEC-23 (fail-closed APP_TOKEN), SEC-24 (BC bump), SEC-26 (block network loads), SEC-01 partial (make RATE mandatory — one line).

## Needs device testing
- SEC-12 Take-photo with declared-but-ungranted CAMERA (API 30/34).
- SEC-17 OOM via document-level JS; crash loop with "restore last session".
- SEC-22 crafted `smartdocs://esign/req/a%2Fb` link.
- SEC-03 crafted Launch link to `files/datastore/settings.preferences_pb` → chooser.
- SEC-11 readability of `Android/data/<pkg>/files/Documents` by another app on API 28.
- SEC-15 encrypted file through "Optimize → Watermark" and "Watermark → Page numbers" recipes.
- SEC-16 redaction of a tagged (ActualText) PDF, check with a screen reader / text extraction.
- SEC-33 XFA billion-laughs packet; XFDF import on Android (PdfBox XMLUtil feature support).
- SEC-07 backup/restore to a second emulator (licence device id carried over).
- SEC-21 Wi-Fi server under PIN brute force; IPv6 exposure when "Wi-Fi only" is off.
- SEC-36 merged release manifest (AD_ID, provider entries).
