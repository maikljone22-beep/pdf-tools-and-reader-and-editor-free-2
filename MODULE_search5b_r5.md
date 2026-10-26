# MODULE_search5b (round 5): reader find & replace, reader redaction, PII → redaction, scoped encryption

Owner id: `search5` (continuation of `MODULE_search5_r5.md`). Gradle was not run.

## Files
### New
| File | What |
|---|---|
| `core/redact/ProtectModes.kt` | `EncScope` (ALL / ALL_BUT_METADATA / ATTACHMENTS_ONLY), `ScopedProtectionPolicy`, `ScopedSecurityHandler`, `EmbeddedFileCrypt`. |
| `ui/search/ReaderSearchExtras.kt` | `ReaderReplaceToggle`, `ReaderReplaceRow`, `ReaderReplace.run`, `ReaderRedact` (selection / page → `/Redact`), `RedactMarksBanner`. |
| `test/.../core/redact/ProtectModesTest.kt` | 6 end-to-end encryption tests (details below). |

### Changed (small, additive)
- **`core/redact/ProtectAdvanced.kt`:** `ProtectOptions.scope` (default `ALL`, so existing callers are unchanged).
  - Any scope other than ALL goes through `ScopedSecurityHandler`.
  - Two `require`s: the scope needs AES, and "attachments only" needs an open password.
- **`core/search/FindReplace.kt`:** adds `ReplaceOutcome`, `replaceAllIn(...)`, `replaceHit(...)` and `pickBlock(...)`. The existing API is unchanged.
- **`ui/security/ProtectAdvancedScreen.kt`:** a new section «ما الذي يُشفَّر» with three radio buttons.
  - The two new scopes are disabled for RC4.
  - "Attachments only" is blocked until an open password is entered.
- **`ui/security/RedactState.kt`:**
  - `excluded`, `activeMarks()` and `toggleIncluded()`.
  - `addHits(List<SearchHit>)`, which removes duplicates.
- **`ui/security/RedactPanes.kt`:**
  - The search pane takes an optional `onPii` button «كشف البيانات الحساسة (ذكي)».
  - The review list has a checkbox per mark and an "n غير محددة" counter.
- **`ui/security/RedactScreen.kt`:**
  - The `PiiScanSheet` is wired in.
  - Apply, «حفظ العلامات» and the counters use `activeMarks()`.
- **`ui/reader/ReaderOverlays.kt`:**
  - `SearchBar(state, quick: QuickController? = null, onClose)`: the replace toggle and replace row appear only when `quick` is passed.
  - `SelectionMenu(..., onRedact: ((TextSelection) -> Unit)? = null)`: adds the «تنقيح» chip.
  - Both new parameters have defaults, so other callers still compile.
- **`ui/reader/ReaderScreen.kt`:**
  - `SearchBar(state, quick)` and `onRedact = { ReaderRedact.markSelection(...) }`.
  - Two overflow items: «تنقيح الصفحة» and «تطبيق التنقيح» (`actions.navigateTool("redact")`).
  - `RedactMarksBanner` (BottomEnd of the page box).
  - Each edit is tagged `// MODULE_search5b`.
- **`ReaderState.kt`:** not changed (not needed).
- **Strings:** 25 new `s5_` keys, in `values/` and `values-en/strings_search5.xml` (197 keys each).
- **Line endings:** every edited file is LF.

## Per-item status
### 1. Find & replace in the reader search bar: **DONE** (needs a device test)
- **Controls:** the toggle icon (FindReplace) opens a second row «استبدال بـ» with «استبدال» (current hit) and «استبدال الكل».
- **How it runs:** it goes through `QuickController.apply` → `ReaderState.applyInPlace`. Pending annotations are saved first, the engine writes to a temp file, then `swapFile` keeps the page. The usual «تم» bar offers undo and "save as copy". Then `state.runSearch(query)` runs again.
- **Snackbar:** "تم استبدال n". It adds the substitute-font warning when the engine reports `fontWarning`, and a lossy-text note.
- **Current hit:** the hit's centre is mapped user → display space (`PageFrame`) and matched to the `PageTextModel` block. The k-th occurrence in a block is chosen from the earlier hits that fall in the same block. Only that range is rewritten.
- **Options:** the reader's options are used (case and whole word through `SearchPrefs.load(ctx, matchCase)`).
- **Limits (MODULE_edit):** text in form XObjects and rotated text cannot be replaced. If nothing editable matches, the snackbar says «لم يُعثر على النص…».

### 2. PII detection → redaction marks: **DONE**
- **Sheet:** `PiiScanSheet(doc.file, doc.password, doc.session, onApply, onDismiss)`, the real signature in `ui/ai5`.
- **Opening it:** from the button in the search pane of `RedactScreen`.
- **Result:** the returned `SearchHit`s become marks, ticked. On a phone the screen switches to the review tab; on a tablet the review list is already beside it. A snackbar gives the count.
- **Review:** every mark in the review list now has a checkbox. Unticked marks are kept but neither applied nor saved.

### 3. Redaction from the reader: **DONE** (area drawing: NOT DONE, by choice)
- **Marking:** the selection chip «تنقيح» and the overflow item «تنقيح الصفحة» write a standard `/Redact` annotation into the file at once. They use `RedactMarks.load` + the new mark → `RedactMarks.save`, through the quick path, so there is undo and pending annotations are saved first.
- **Display:** the reader renders the mark's appearance stream; PDFium renders annotations.
- **Applying:** «تطبيق التنقيح» is in the overflow, and in a banner "علامات تنقيح: n" whenever `state.existing` has a `Redact` subtype. It opens `Routes.tool("redact", fileUri)`, which loads the marks for review and true removal.
- **Area drawing:** a "redact area" draw mode would need `PageCanvas` / tool-enum changes, so it was skipped. The redaction tool's «يدوي» tab already draws areas.
- **Mark style:** the reader marks use the redaction tool's last style (`RedactPrefs.lastStyle`).

### 4a. Encrypt everything except metadata (`/EncryptMetadata false`): **DONE, validated**
- **Why a wrapper:** PdfBox-Android 2.0.27 `StandardSecurityHandler` is **final** (javap), so it cannot be subclassed. `ScopedSecurityHandler extends SecurityHandler` (abstract, public constructor) is registered with `SecurityHandlerFactory.INSTANCE.registerHandler("SDScopedStandard", …)` for `ScopedProtectionPolicy`.
- **No reflection needed.** In `prepareDocumentForEncryption`:
  - A fresh `PDEncryption` is created, with `EncryptMetadata false` set.
  - `StandardSecurityHandler(policy).prepareDocumentForEncryption(doc)` runs, and its public `getEncryptionKey()` / `getKeyLength()` / `isAES()` are copied over.
- **R4 fix:** PdfBox's R4 code hard-codes `encryptMetadata=true` in `computeUserPassword` / `computeEncryptedKey`. So **/U and the file key are recomputed** with the public methods and `encryptMetadata=false`.
- **R6 fix:** PdfBox writes 'T'. **/Perms is recomputed** as AES-256-ECB(fileKey, P LE + FFFFFFFF + 'F' + "adb" + 4 random bytes).
- **What stays in clear:** `encryptStream` (public, open) skips only the **document-level** catalog /Metadata stream, which matches ISO 32000 and qpdf. Page-level XMP stays encrypted. PdfBox's reader skips any `/Type /Metadata` stream that starts with `<?xpacket`, so it reads both cases.

### 4b. Encrypt only attachments: **DONE, validated** (one inherent app-side limit)
- **Encryption dictionary:** V4/R4 AESV2 or V5/R6 AESV3, with `/StmF /Identity /StrF /Identity /EFF /StdCF` and `/StdCF /AuthEvent /EFOpen`, which is Acrobat's way.
- **What is encrypted:** only embedded-file streams. They are found by walking the whole object graph for file-spec `/EF` dictionaries, so the name tree, `/AF` and annotations are covered. Missing `/Type /EmbeddedFile` entries are added. Strings are not encrypted.
- **Reading attachments back:** PdfBox 2.0 ignores `/EFF`; with `StmF=Identity` it returns ciphertext. `EmbeddedFileCrypt.bytes(doc, stream)` decrypts with the opened document's file key (AESV2: MD5 object key; AESV3: the key), then decodes the filters.
- **Limit:** PDFium (this app's reader), PdfBox and qpdf all ignore `/AuthEvent /EFOpen`. They ask for the password to open the file itself; Acrobat and Reader do not. Without the password, the pages are still stored in clear, which the test proves (below). The screen's hint says this.

## Tests
- **Harness:** kotlinc 2.0.21 + JUnit 4.13.2, with `android-35/android.jar` on the classpath. Its `Log` stubs throw, like Gradle's unit-test android jar, so any `android.util.Log` call would fail the test.
- **Result: 48 tests, all pass.** That is the 42 earlier search5 tests plus 6 new ones in `ProtectModesTest`.
- **`ProtectModesTest`:** the test PDF has page text, an Info title, catalog XMP, page XMP, and two attachments (one plain with `/Type`, one Flate without `/Type`).
  - **(a) at 128 and 256:** in the raw bytes the catalog XMP is in clear, while the page XMP, the content, the strings and the attachment are not.
  - **(a) reopened with the user password and with the owner password:** `/EncryptMetadata` is false, V/R are 4/4 or 5/6, and the content, title, XMP and both attachments are byte-exact. With R6, decrypted `/Perms[8]=='F'`, the bytes are "adb" and P matches. A wrong password is refused.
  - **(b) at 128 and 256:** content, title and XMP are in clear and the attachment is not. With `/Encrypt` renamed in the trailer (same length), the file opens **without a password**: the page content and title are readable and the attachment is still ciphertext.
  - **(b) reopened with the password:** Identity filters, `/EFF /StdCF`, `/AuthEvent /EFOpen`, AESV2/AESV3, and `EmbeddedFileCrypt` gives both attachments byte-exact.
  - **Also tested:** the /Perms layout, and normal ALL encryption unchanged.
- **qpdf:** the qpdf CLI is not installed. Instead I used pikepdf 10.17 (bundles **libqpdf 12.4.2**) in a scratch venv, on the files the test writes (`-Dsd.outDir`).
  - **ALL and ALL_BUT_METADATA, 128/256:** `check_pdf_syntax` (≈ `qpdf --check`) reports **no problems**. qpdf shows R4/V4 AES or R6/V5 AESv3 and `/EncryptMetadata False`. Page text, title, root and page XMP, and both attachments are all correct through qpdf's own decryption. Without a password qpdf raises `PasswordError`.
  - **ATTACHMENTS_ONLY:** qpdf reports stream=none, string=none, file=aes/aesv3, and page content, title and XMP are correct.
    - qpdf's `--check` **warns about the Flate attachment**, because qpdf has no `/EFF` read support (its `decryptStream` uses only `cf_stream`; I checked the qpdf source).
    - Taking qpdf's own file key and decrypting each attachment independently in Python (pycryptodome, standard AESV2/AESV3) gives **both payloads byte-exact**.
- **Type-check:** kotlinc **2.3.21** with the Compose plugin, against the app's last `bundleDebugClassesToCompileJar/classes.jar`, the dependency jars and an R stub generated from the current `res/values/*.xml`.
  - `core/search/*`, `core/redact/*`, `ui/search/*`, `ui/security/*`, `core/ai5/*` and `ui/ai5/*` compile with **0 errors**.
  - Adding `ReaderOverlays.kt` and `ReaderScreen.kt` gives only "unresolved `view5` / `edit5` / `forms5`" errors. Those packages are newer than that jar or being written now; none of the errors is on a search5b line.
- **Harness location:** `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\search5\` (`ptest.sh`, `fullcheck.sh`, `pyval\val.py`).

## Hooks for the lead
1. **Optional, recommended:** let the attachments tool read files encrypted with "attachments only". In `core/pdf/AttachmentOps.kt` `extract(...)`, replace
   `target.outputStream().use { o -> o.write(ef.toByteArray()) }`
   with
   `target.outputStream().use { o -> o.write(com.smartdocs.editor.core.redact.EmbeddedFileCrypt.bytes(doc, ef.cosObject)) }`
   For ordinary files it falls back to the normal decoded bytes.
2. Nothing else. The reader hooks are already applied in `ReaderOverlays.kt` / `ReaderScreen.kt`; please keep them if those files are merged.

## Needs a device test
1. **Reader replace:** a word in a simple Latin PDF and in an Arabic PDF, current hit and all. Check undo, that the page is kept, the new search, and the font-warning snackbar on a subset-font PDF.
2. **«تنقيح» on a selection:** the outlined, tinted mark shows on the page. Then the banner → the redaction tool lists it → apply.
3. **PII sheet in the redaction tool:** the hits appear ticked in the review list, unticking excludes them, and apply uses only ticked marks.
4. **Scoped encryption:** open the outputs in Acrobat Reader. "Attachments only" should open without a password and ask for it on the attachment. "All but metadata" should ask for the password, and its XMP should be visible to indexers.
