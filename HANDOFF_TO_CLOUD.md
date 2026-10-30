# Handoff to the cloud session

Repo: `maikljone22-beep/pdf-tools-and-reader-and-editor-free-2`, branch `main`. App: SmartDocs (Android, Kotlin/Compose, `com.smartdocs.editor`), version 0.5.0 (versionCode 5), targetSdk 36. Arabic-first UI (Arabic in `res/values`, English in `res/values-en`).

## 1. Done and merged (main = `c4ac7b3` before this file)
- Feature gap work vs Nitro PDF Pro (868 rows), Foxit PDF Editor (1,639 rows) and the old-chat plan — tables in `gaps/` (`cmp_*`, `fcmp_*`, `OLD_PLAN_AUDIT.md`, `FINAL_CHECK.md`, `FINAL_CHECK_2.md`, `DONE_*`).
- Deep audits, all fixed: Convert section (124, `gaps/convert-audit/FIXED.md`), reader tabs View+Edit 59 / Comment 37 / Fill&Sign+Sign 37 / Tools+Pages 98 / redact sub-audit 20 (`gaps/tabs-audit/FIXED_*`).
- Central signed/certified-file gate `core/pdf/SignedGuard.kt` (+ `ReaderState.guardSigned`, `applyInPlace(change=…)`); every writer must use it — never add a second gate.
- Open-all-file-types viewers, per-tab selection menus (`core/selmenu`, `ui/selmenu`), in-page video (+ properties, renditions), Pro OCR (PP-OCRv5 ONNX + fine-tuned Tesseract `models/ocr/ArabicFT.traineddata`), PDF→Word fix, Arabic copy repair (`ToUnicodeRepair`), PDF/A levels + preflight, accessibility, forms designer/XFA events, JS actions, reader prefs, sync, links/bookmarks, AI/Office.
- Per-area logs: `AUDIT/*_LOG.md`, `*_r5.md` in the root.

## 2. Open branches
| Branch | Status | Action |
|---|---|---|
| `fix/audit-7` | superseded by `fix/audit-7-r2` (merged long ago) | abandon |
| `fix/web-print` (pdf-3c, local session) | in progress, not pushed when this was written: web-capture + virtual printer options | continue only if it appears on origin; else redo from `gaps/FINAL_CHECK_2.md` (web/printer rows) |
| pdf-39 local work (no branch pushed yet): FormCalc interpreter for XFA, Combine page-level view, DOCX export with comments, Portfolio part edit + save back, a11y 19–21, watched folder | in progress | continue only if branches appear on origin; else take these rows from `gaps/FINAL_CHECK_2.md` |

## 3. Remaining work, priority order
1. **Specs**: unzip `SmartDocs-handoff-from-old-chat.zip` into `specs/` (Word/Excel/PowerPoint feature specs). The zip was NOT found on the local machine (searched C:, D:, E:) — get it from the user.
2. Remaining rows of `gaps/FINAL_CHECK_2.md` (no High left; ~24 Medium, ~140 Low), minus what is already done by later merges (re-verify each row against code first).
3. Large Word / Excel / PowerPoint feature round (~960 features) from `specs/` — the post-0.5.0 scope.
4. Signed release build 0.5.0 (APK + AAB) after the device test — needs the local signing key (see build notes); cannot be done in the cloud.
5. Fix bugs from the user's tablet test (`TEST_CHECKLIST.md`).

## 4. Do not touch
- Signing key and secrets: `release.jks`, `keystore.properties`, `*.properties` with keys, `ads.properties`, `license.properties`, `.dev.vars` — git-ignored; never commit, never print.
- `models/ocr/ArabicFT.traineddata` (pinned by commit + SHA-256 in `core/ocr/OcrEngine.kt FT_URL`).
- If the local sessions pdf-3c / pdf-39 are still active: their areas above (web/printer options; XFA FormCalc, combine page view, DOCX comments, portfolio, a11y, watched folder). Check origin for new branches before starting those.

## 5. Build notes and rules
- CI: `.github/workflows/build.yml` builds every branch (assembleDebug + testDebugUnitTest), uploads `SmartDocs-<branch>-<run>`; compile errors / failed tests are emitted as check-run annotations. Concurrency cancels older runs on the same branch.
- Workflow: one branch per task; merge `origin/main` into the branch before the final push so its green run covers main; merge into main with `--no-ff` only after a green run.
- Commit identity: `git -c user.name="SmartDocs" -c user.email="claude@abcexam.com" commit`; message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- LF line endings (`.gitattributes`); Python edits with `newline=''`; no raw invisible Unicode in Kotlin source.
- JVM unit tests: PdfBox-Android can't load Standard-14 fonts on the JVM — use signature fields / `getAcroForm(null)` / bundled test fonts; `unitTests.isReturnDefaultValues = true` is set.
- Gradle locally: `--max-workers=2`, `-q`; Windows Arabic locale needs the `-Duser.language=en …` flags already in `gradle.properties`.
- Owner actions (servers, keys, assetlinks): `SERVER_SETUP.md`. Release checklist: `STATUS.md`.
- Debug builds install as `com.smartdocs.editor.test` («SmartDocs تجريبي»).
