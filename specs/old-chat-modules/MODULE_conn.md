# MODULE_conn: signature requests, connected documents, enterprise-protected files

All three features run on our own servers (Cloudflare Workers in `ai-proxy/`). No third-party product is named in the UI or the docs.

## How this was checked (Gradle was not run)
- **App compile:** every Kotlin file of this module compiled cleanly with standalone `kotlinc` 2.1 and the Compose compiler plugin. The classpath was android-35 plus the exact cached jars (Compose 1.7.8, Material3 1.3.1, navigation 2.8.9, coil 2.7, okhttp 4.12, pdfbox-android 2.0.27). Project classes I don't own were replaced by stubs that copy their real signatures. Result: 0 errors and 0 warnings.
- **Workers:** both were run in Node against a mock KV:
  - Sign-request flows: sequential order, parallel order, declining, finalizing, verifying, deleting, and the cron job.
  - Connected-document flows: approval, access request, allow, key, revoke-all, a hash-only version, deleting.
- **Web signing page:** tested end to end in headless Chromium with pdf.js 3.11.174: render the page, draw a signature, give consent, submit. The server state was then checked.
- **Desktop harness (Apache PDFBox):**
  - `ConnectedStamp`: marking, reading the id from the AES-256 file with no password (qpdf reports R=6), and falling back to XMP when the trailer key is missing.
  - `EnterpriseProtection.detect` on synthetic samples.
  - `EsignComposer.fieldMatrix` on pages with /Rotate 0, 90, 180 and 270: the image lands upright at the right place on all four.

## Files
**Servers**
- `ai-proxy/esign/`:
  - `worker.js`: the API.
  - `sign-page.js`: the mobile web signing page (pdf.js from cdnjs, font and cmap data from the same pdfjs-dist version on jsDelivr) and the `/v` hash-verification page.
  - `wrangler.toml`: KV `ESIGN`, optional R2 `FILES`, cron every 6 hours, mail variables.
  - `README.md` (Arabic).
- `ai-proxy/connected/`: `worker.js`, `wrangler.toml` (KV `DOCS`, optional R2 `FILES`), `README.md` (Arabic).

**Core**
- `core/esign/EsignModels.kt`: fields use page fractions with a top-left origin, on the displayed (rotated) page. Also holds the draft, signer, view and value types.
- `core/esign/EsignClient.kt`: `EsignConfig` (`ESIGN_URL`, `ESIGN_TOKEN`) and the HTTP client for both sender and signer.
- `core/esign/EsignStore.kt`: local list of sent and received requests.
- `core/esign/EsignComposer.kt`:
  - `compose(...)` draws every signer's values into the pages (rotation-aware matrix) and appends the Arabic "certificate of completion" pages with the audit trail.
  - Also provides `sha256(file)`.
- `core/connected/ConnectedClient.kt`: `ConnectedConfig` (`CONNECTED_URL`, `CONNECTED_TOKEN`), the client, and `ConnAccess`.
- `core/connected/ConnectedStamp.kt`: writes and reads the id (trailer names, /Info, XMP). Protected mode means AES-256 with the server-held key as the user password.
- `core/connected/ConnStore.kt`:
  - Owned documents and the per-install reader id.
  - `ConnKeyCache`: offline keys wrapped with an AES-GCM key from the Android Keystore, each with an expiry.
- `core/connected/ConnectedDocs.kt`: `markOf(file)` (fast, local), `checkOnOpen(context, file): ConnectedStatus?` (suspend), `statusById`, `register`, `publishVersion`, `downloadLatest`, `requestAccess`.
- `core/connected/EnterpriseProtection.kt`: `detect(file): Info?` (`kind`, `labelName`, `blocking`) and `viewerPackages`.

**UI**
- `ui/esign/EsignCommon.kt`: status chips, share helpers (share sheet, WhatsApp, mail, copy), `rememberPdfSession`, `PageBitmap`, `ConnCard`.
- `ui/esign/EsignDashboardScreen.kt`: sent and received tabs, refreshing statuses, opening a pasted link.
- `ui/esign/EsignCreateScreen.kt`: four steps (document and options, signers, fields, send), then the per-signer links dialog.
- `ui/esign/EsignFieldPlacer.kt`: tap a page to add a field, drag to move it, use the corner handle to resize it, set required, delete.
- `ui/esign/EsignDetailScreen.kt`: signers, reminders, links, the audit trail, cancel and delete.
  - "Create the signed copy" composes the file, uploads it, then shows it in `ResultSheet`.
  - "Sign digitally with my certificate" opens `Routes.tool("digital_sign", uri)`, the existing PAdES screen.
- `ui/esign/EsignSignScreen.kt`: in-app signing. The signer picks one of their saved signatures or creates one with `SignatureCreateSheet`, fills text and date fields, consents, then finishes or declines.
- `ui/connected/ConnRoutes.kt`: `ConnRoutes`, `NavGraphBuilder.connRoutes(nav)`, `ConnDeepLinks`.
- `ui/connected/ConnectedHomeScreen.kt`: owned documents and the register sheet.
- `ui/connected/ConnectedDocScreen.kt`: open counts, readers, approving or blocking readers, revoke-all, approval and offline-days settings, versions, publishing a new version, deleting.
- `ui/connected/ConnectedOpenScreen.kt`: the reader-side screen (newer version, access request, revoked or offline notice) and `ConnectedBanner(status, nav, fileUri)`.
- `ui/connected/EnterpriseProtectedScreen.kt`: the Arabic explanation screen. Its buttons:
  - Open with the official viewer (explicit package intents).
  - Open with another app.
  - Share.
  - Get the viewer.
- Strings: `res/values/strings_conn.xml` and `res/values-en/strings_conn.xml`. Both have 249 keys with the `cn_` prefix and identical key sets.

## For the lead to wire

### 1. NavHost (`ui/Navigation.kt`)
```kotlin
import com.smartdocs.editor.ui.connected.connRoutes
// inside NavHost { … }, next to acctRoutes(nav):
connRoutes(nav)
```

| route | composable |
|---|---|
| `esign` | `EsignDashboardScreen(nav)` |
| `esign/create?uri={uri}` | `EsignCreateScreen(nav, uri)` |
| `esign/req/{id}` | `EsignDetailScreen(nav, id)` |
| `esign/sign?id={id}&t={t}` | `EsignSignScreen(nav, id, token)` |
| `connected?uri={uri}` | `ConnectedHomeScreen(nav, uri)` (opens the register sheet when `uri` is set) |
| `connected/doc/{id}` | `ConnectedDocScreen(nav, id)` |
| `connected/open?id={id}&uri={uri}` | `ConnectedOpenScreen(nav, id, uri)` |
| `enterprise_protected?uri={uri}` | `EnterpriseProtectedScreen(nav, uri)` |

Use the helpers `ConnRoutes.esignCreate(uri)`, `.esignDetail(id)`, `.esignSign(id, t)`, `.connected(uri)`, `.connectedDoc(id)`, `.connectedOpen(id, uri)` and `.enterprise(uri)`.

### 2. Tools tab
Add to `allTools` in `ToolsScreen.kt` and map the ids in `ToolScreens.kt`:
```kotlin
ToolDef("esign", R.string.cn_tool_esign, Icons.Default.HistoryEdu, 2),
ToolDef("connected", R.string.cn_tool_connected, Icons.Default.Link, 2),
// ToolScreens:
"esign" -> com.smartdocs.editor.ui.esign.EsignDashboardScreen(nav)
"esign_create" -> com.smartdocs.editor.ui.esign.EsignCreateScreen(nav, uri)
"connected" -> com.smartdocs.editor.ui.connected.ConnectedHomeScreen(nav, uri)
```
Subtitles are available in `cn_tool_esign_desc` and `cn_tool_connected_desc`. Optional reader menu entries:
- "Request signatures": `nav.navigate(ConnRoutes.esignCreate(uriString))`.
- "Register as connected": `nav.navigate(ConnRoutes.connected(uriString))`.

### 3. Deep links
**Manifest:** add these to the existing `smartdocs` intent filter, next to `oauth` and `review`:
```xml
<data android:scheme="smartdocs" android:host="esign" />
<data android:scheme="smartdocs" android:host="doc" />
```
`MainActivity` already forwards every `smartdocs://` URI to `DeepLinks.pending`.

**`ui/cloud/DeepLinkHandler`** (acct agent's file): add a branch to `when (u.host)`:
```kotlin
"esign", "doc" -> com.smartdocs.editor.ui.connected.ConnDeepLinks.handle(nav, u)
```

### 4. `build.gradle.kts` `defaultConfig`
Reuse the `cloudProps` block from MODULE_acct.md. The fields are read through `BuildCfg.string`, so the code compiles without them; the `-keep class …BuildConfig` rule from acct still applies.
```kotlin
buildConfigField("String", "ESIGN_URL", "\"${cloudProps.getProperty("esignUrl", "")}\"")
buildConfigField("String", "ESIGN_TOKEN", "\"${cloudProps.getProperty("esignToken", "")}\"")
buildConfigField("String", "CONNECTED_URL", "\"${cloudProps.getProperty("connectedUrl", "")}\"")
buildConfigField("String", "CONNECTED_TOKEN", "\"${cloudProps.getProperty("connectedToken", "")}\"")
```
While a URL is empty, its screens show "server not configured". `checkOnOpen` still works offline with cached keys.

### 5. Reader hooks (`ui/reader/ReaderState.kt` `open(pw)`, just before `PdfSession.open(context, f, pw)`)
```kotlin
// enterprise rights-managed files: never prompt for a password, explain instead
com.smartdocs.editor.core.connected.EnterpriseProtection.detect(f)?.takeIf { it.blocking }?.let { enterpriseInfo = it; return@launch }
// connected documents
var openPw = pw
val mark = com.smartdocs.editor.core.connected.ConnectedDocs.markOf(f)          // fast, local
if (mark != null) {
    if (mark.protected && pw == null) {                                              // need the key before opening
        val st = com.smartdocs.editor.core.connected.ConnectedDocs.checkOnOpen(context, f)
        connected = st
        if (st == null || st.locked) { connectedLocked = true; return@launch }
        openPw = st.password
    } else scope.launch { connected = com.smartdocs.editor.core.connected.ConnectedDocs.checkOnOpen(context, f) }  // banner only
}
val s = PdfSession.open(context, f, openPw)   // and keep `password = openPw`
```
New state in ReaderState:
```kotlin
var connected by mutableStateOf<ConnectedStatus?>(null)
var connectedLocked by mutableStateOf(false)
var enterpriseInfo by mutableStateOf<EnterpriseProtection.Info?>(null)
```
In ReaderScreen:
```kotlin
LaunchedEffect(state.enterpriseInfo) { if (state.enterpriseInfo != null) { nav.popBackStack(); nav.navigate(ConnRoutes.enterprise(uriOfFile)) } }
LaunchedEffect(state.connectedLocked) { if (state.connectedLocked) { nav.popBackStack(); state.connected?.let { nav.navigate(ConnRoutes.connectedOpen(it.docId, uriOfFile)) } } }
state.connected?.let { ConnectedBanner(it, nav, uriOfFile) }   // above the pages; hides itself when there is nothing to say
```
Here `uriOfFile = Uri.fromFile(f).toString()`. `checkOnOpen` uses connect, read and call timeouts of 6 s, 10 s and 10 s.

## Limitations
- **Signature requests: identity.** Anyone who holds a signer's link can sign. There is no SMS or OTP and no account. The evidence recorded is the delivery address, the IP, the user agent and timestamps.
- **Signature requests: the completed PDF.**
  - It is built on the sender's device, not on the server. The server holds the original's hash, the submitted values and the final file's hash (`/v` page and `GET /verify/<sha>`). It does not check that the uploaded final was built from those values.
  - Signatures are drawn as images into the page content, and date and text values are drawn as images too. There are no AcroForm fields.
  - The certificate page cannot contain its own hash. It points to the verification page instead.
  - Applying PAdES afterwards changes the file, so its hash no longer matches the hash registered on the server. The audit trail still covers the pre-PAdES copy.
- **Signature requests: delivery and timing.**
  - Email needs a mail-API config on the worker. Without it, links are shared through the share sheet, WhatsApp (`wa.me`) or the mail app, and there are no automatic reminders.
  - KV is eventually consistent (about 60 s). In sequential order the next signer may briefly see "not your turn".
  - There are no push notifications. The dashboard refreshes when opened and on demand.
- **Signature requests: limits.** 20 MB per PDF, 20 signers and 500 fields. Encrypted PDFs cannot be sent, because PdfSession asks for a password and the sender is told to remove it first.
- **Signature requests: field size.** Default field sizes assume A4 proportions and are corrected for the page shape.
- **Connected documents: revocation.**
  - It only prevents reading for *protected* files, and only after the reader's offline period ends or the next time they open the file online.
  - A key that was already obtained can open that copy forever in any PDF viewer.
  - Unprotected files stay readable; revocation only stops downloads of newer versions.
  - All versions of one document share one key.
- **Connected documents: reader identity.** It is a random per-install id plus the display name the reader set in settings. Clearing app data creates a new "reader". A device's clock can be moved to stretch the offline window.
- **Connected documents: finding the id.**
  - The id is found from the trailer keys (last 256 KB), else by a streaming scan (files up to 64 MB) for the XMP or /Info markers.
  - An id that survives only inside compressed object streams is not found.
  - If the reader saves a protected connected file, `PdfOps.load` re-protects it with the same key, but at AES-128.
- **Connected documents: owner key.** The owner's copy of the document key is kept in `ConnKeyCache` (Keystore-wrapped) with no expiry. After reinstalling, the app cannot publish a new protected version, because `ConnStore` is lost.
- **Enterprise protection: detection.** It is a heuristic byte scan of the first and last 2 MB. It recognises:
  - .ppdf and .pfile wrappers.
  - The enterprise IRM security handler and wrapper PDFs that embed it.
  - Two other DRM handlers.
  - OLE DataSpaces.
  - Sensitivity labels with no encryption (`LABEL_ONLY`, non-blocking).
- **Enterprise protection: no decryption.** Nothing is decrypted. "Open with the official viewer" tries the known packages in order, then falls back to a chooser.
