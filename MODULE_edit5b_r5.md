# MODULE edit5b (round 5): live watermark + locked files in multi-file tools

Owner: edit5. This follows `MODULE_edit5_r5.md` and finishes two items from it:
- "Dynamic watermark tokens" was PARTIAL.
- "Locked files in multi-file tools" was listed as a limitation.

No Gradle dependency was added. No shared hot file was edited, and no file outside `core/edit5`, `ui/edit5`, the `strings_edit5.xml` files and the edit5 tests was touched.

## Files
**New**
| File | Content |
|---|---|
| `core/edit5/LiveWmScript.kt` | Pure logic for the live watermark: which tokens are live; template → script template; Java date pattern → `util.printd` pattern; JS string escaping (ASCII only); the document script; faded colour; 90° rotation snap; field and annotation flags. |
| `core/edit5/LiveWatermark.kt` | PdfBox side: `install` (one field per page, static appearance, scripts), `remove`, `present`, `mayContain` (byte pre-check), and the reader hook `refresh`. |
| `core/edit5/E5Locks.kt` | `OutProtection` (KEEP / REMOVE), `LockState`, `PasswordBook` (one password per file per operation, pure), and `E5Locks` (`probe`, `isPasswordError`, `plan`, `applyTo`, `protectLike`). |
| `ui/edit5/E5Unlock.kt` | `E5Unlocker` (suspend `unlock` / `unlockAll`, asks again after a wrong password, calls run one at a time behind a mutex), `E5UnlockDialog`, and `ProtectionChoice` (switch for "remove protection from the output"). |
| `test/.../core/edit5/LiveWatermarkTest.kt` | 3 JVM tests. Builds a PDF with a live watermark, checks the structure, applies it again, removes it, and checks an encrypted round trip. |
| `test/.../core/edit5/E5LocksTest.kt` | 11 tests for the pure parts and the password round trips. |

**Changed**
| File | Change |
|---|---|
| `core/edit5/WatermarkOps.kt` | `DecorSpec.live` and `isLive` (stored in the JSON). `apply(..., fileName = src.name)`: a live spec is written by `LiveWatermark.install`. Update and remove also remove the live fields and scripts. `settingKey` is now public. New `liveFace(...)`: shaped Arabic static text through `E5Line`, and the full (non-subset) Noto Naskh as the `/DR` font when the text or the user name is Arabic. |
| `core/edit5/HeaderFooterOps.kt` | `bates(..., passwords: (File) -> String? = { null }, protection = KEEP)`. Every file is loaded with its own password, and each output is re-protected like its source or left unprotected. |
| `core/edit5/ImposeOps.kt` | `nUp`, `booklet` and `poster` take `protection = KEEP`. The new document gets the source's protection through `E5Locks.protectLike`. |
| `ui/edit5/DecorScreen.kt` | Fixed / Live choice (watermark with text source only), with help text and a warning when the text has no live tokens. In live mode: rotation chips 0/90/180/270, no tiling, no "behind" option, and the preview uses the faded colour and the snapped rotation. Passes the real file name for `{file}`. Before this, the temp copy's name `e5_….pdf` was used. |
| `ui/edit5/BatesScreen.kt` | Asks for the password of each locked file once, when the file is added (or when the screen opens with a file). Cancelling drops that file from the list. Locked files show a lock icon. A "Protection" section offers "remove protection from the output". Before running, it checks that every file is ready. |
| `ui/edit5/ImposeScreen.kt` | Asks for the source's password when the file is picked, then passes the password to the page count and to the imposition. Shows the protection choice when the source is locked. |
| `ui/edit5/PageOpsScreen.kt` | The second file of replace / copy / interleave asks for its password when picked, and the password goes to `pageCount` and the operation. Run stays disabled until the second file is unlocked. |
| `res/values{,-en}/strings_edit5.xml` | 13 new keys (`e5_wm_fixed`, `e5_wm_live`, `e5_wm_live_help`, `e5_wm_fixed_help`, `e5_wm_live_no_tokens`, `e5_lock_*`). Both files now have the same 359 keys. |

`PdfOps.load` is `internal`, and it is reachable from `core/edit5` because it is the same module. It is already used through `E5Io.load`.

## Hooks for the lead (exact)
Nothing is required. The screens are already routed by the hooks in `MODULE_edit5_r5.md`.

**Optional: our reader refreshes a live watermark when it opens a file.**
- Where: `ui/reader/ReaderState.kt`, in `fun open(pw: String? = null)`.
- Find the line:
  ```kotlin
                  val s = PdfSession.open(context, f, pw)
  ```
- Insert directly before it:
  ```kotlin
                  runCatching { com.smartdocs.editor.core.edit5.LiveWatermark.refresh(context, f, pw, null) }
  ```

Signature: `suspend fun refresh(context: Context, file: File, password: String?, userName: String? = null): Boolean`. It needs a `Context` for the Arabic font assets, so it takes one more parameter than the requested `refresh(file, password, userName)`.

What it does:
- It first scans the bytes for the `/SDE5 ` key, which costs about one sequential read and no parsing. Files without a live watermark return `false` and are left untouched.
- When a live watermark is present, it rebuilds the static appearances in place with the current date and time, the page numbers, and `userName`. If `userName` is null, the stored user is used.
- After that, PDFium shows current values.

Side effect: the file is rewritten on every open that has a live watermark. Its modification time changes, and a digital signature on it would break. Only hook it in if that is acceptable.

## Verification (no gradle was run)
- **Type check:** `ui/edit5` and `core/edit5` were compiled with kotlinc 2.3.21 plus the Compose plugin, against the android-35 jar, the AAR jars and the prebuilt app classes (`scratchpad\edit5\kfast5.sh`, with a fresh R stub in `scratchpad\edit5\Rstub.kt`). Result: **0 errors**.
- **JVM tests:** `scratchpad\edit5\t5b.sh` ran them against the real `pdfbox-android-2.0.27.0` `classes.jar` plus the stub `android.jar`, whose methods throw. That means no `android.*` method is reached, which is what the Gradle unit tests need.
  - Result: **30 tests, all OK** (16 existing `Edit5LogicTest`, 3 `LiveWatermarkTest`, 11 `E5LocksTest`).
- **Independent checks of the PDF the test writes:**
  - `qpdf` is **not installed** on this machine, so `qpdf --check` was not run.
  - Instead, `pypdf` 6.14 in strict mode parsed the file. It found:
    - 3 pages, and fields `SDE5WM.p1..p3` with their values.
    - `/OpenAction` as `/GoTo` whose `/Next` is the JavaScript action.
    - `/AA /WP` as JavaScript.
    - `/Names /JavaScript` = `[Other, SDE5 live watermark]`.
    - On each widget: `/MK /R`, an AP stream with `/BBox` and `/Matrix`.
  - The generated script was run in Node with a mock of the Acrobat objects:
    - `identity` throws `NotAllowedError`, so the fallback user was used.
    - `util.printd`, `getField().getArray()`, `page` and `numPages` gave the right value on every page.
    - `doc.dirty` was restored.
- Nothing was run on a device, in Acrobat or in pdf.js.

## Status
| Item | Status |
|---|---|
| Live watermark (fields + JS on open/print) | **DONE**, with the limits below. |
| Live / fixed option on the watermark screen | **DONE** |
| Update / Remove finds the live parts | **DONE** |
| JVM structure test | **DONE** |
| `qpdf --check` | **NOT RUN** (qpdf not installed). pypdf strict parse and a Node run of the script were used instead. |
| Reader refresh hook | **DONE** as an optional hook (above). Not wired into the reader, because that file is shared. |
| Locked files: Bates (each file asked once), impose, second file of replace/copy/interleave | **DONE** |
| Output protection (keep like source / remove) | **DONE** for Bates and impose. Replace/copy/interleave write the main document's working copy, which keeps the main document's protection (see the limits below). |
| Tests for the pure parts | **DONE** |

### How the live watermark works
**Fields.** Parent field `SDE5WM` (`/FT /Tx`, ReadOnly, DoNotSpellCheck, DoNotScroll, plus Multiline when the text has several lines; `/Q 1`; `/DA /SDE5Wm <size> Tf r g b rg`).
- Each target page gets one merged field/widget `p<n>`:
  - `/F` is Print when the watermark prints, NoView when it is hidden on screen, and ReadOnly + Locked always.
  - `/MK /R` = the snapped rotation plus the page's `/Rotate`.
  - `/V` holds the static values.
  - `/AP /N` is a `/Tx BMC` stream with `/BBox`, a rotation `/Matrix`, and the text shaped and centred.
- `/DR /Font /SDE5Wm` is Helvetica, or the full Noto Naskh when the text or user is Arabic. `/NeedAppearances` is removed.

**Script.** One self-contained, ASCII-only script.
- It reads the user from `identity.name || identity.loginName`. If both are empty or not allowed, it uses the stored user.
- It formats date/time tokens with `util.printd`. The Java patterns are converted, e.g. `yyyy/MM/dd HH:mm` → `yyyy/mm/dd HH:MM`.
- It fills `{file}` from `this.documentFileName`, `{page}` from each widget's `page`, and `{pages}` from `numPages`.
- If Arabic-Indic digits are on, it converts the digits.
- It restores `dirty`, so opening does not ask to save.

**Where the script is installed** (it runs on open from the first two):
- `/Names /JavaScript` entry `SDE5 live watermark`.
- Chained onto `/OpenAction`. An existing destination is wrapped in a `/GoTo` with `/Next`, and an existing action gets the script appended to its `/Next`.
- Chained onto catalog `/AA /WP` (WillPrint).

**Fixed tokens.** `{title} {author} {hijri} {bates} {label}` are filled at apply time.

**Markers.** Every dictionary we add carries `/SDE5 (wmlive)`; the GoTo wrapper carries `/SDE5W true`. `remove` deletes:
- the widgets, the parent field and the `/DR` font;
- `/AcroForm`, if no other fields are left;
- our named script.

It also unlinks our actions and gives back the original destination. Applying again replaces everything without making duplicates, which the test checks.

### Limits
- **Live watermark:** these come from what a form field can do.
  - Rotation is 0/90/180/270 only. Viewers redraw a field's value using only `/MK /R`.
  - It is always drawn over the page content.
  - Opacity is simulated with a lighter colour. A redrawn field cannot be transparent.
  - One placement per page, no tiling.
  - Text source only, plain watermark type only. Background and security watermarks stay static.
  - `identity` is privileged in Acrobat, so a Reader usually shows the stored user name. The date, time, page numbers and file name do update.
  - Viewers without JavaScript (PDFium, most mobile viewers) show the values of the last apply or refresh.
  - A viewer that redraws Arabic values depends on its own field layout. The static appearance is always shaped correctly.
- **Re-protection:** `KEEP` re-protects with the typed password as the open password and the source's permission bits.
  - When the file was opened with its user password (or is owner-only), a new random owner password is set, so the restrictions really stay. Plain `PdfOps.load` sets owner = user, which lifts them.
  - The original owner password cannot be kept, because PdfBox cannot read it back.
  - When the file was opened with its owner password, the typed password becomes both passwords, like `PdfOps.load`.
- **Replace/copy/interleave:** the output is the main document's working copy, so it keeps the main document's protection (`PdfOps.load`). There is no "remove protection" switch in that screen, because the working copy is reused by later steps. The app's unlock tool removes it.
