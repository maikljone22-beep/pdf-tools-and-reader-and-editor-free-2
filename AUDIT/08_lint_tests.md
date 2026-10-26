# Lint & unit-test results (executed by the lead)

Where: isolated worktree `D:\pdf\audit-wt` (detached at da74d31), command
`./gradlew --no-daemon testDebugUnitTest lintDebug --continue`. Log: `AUDIT\build_test_lint.log`.
Lint report: `D:\pdf\audit-wt\app\build\reports\lint-results-debug.{xml,html}`.

## Unit tests — EXECUTED, PASSED
384 tests in 54 suites, 0 failures, 0 errors (`app/build/test-results/testDebugUnitTest/*.xml`).
Instrumented tests (androidTest) were NOT run: no emulator/device available (hypervisor off).

## Lint — EXECUTED; task "failed" because lint has abortOnError and found 320 errors
Totals: 320 Error, 480 Warning, 20 Hint. All errors are non-code-crash classes:

| Lint id | Count | Meaning / evidence | Audit ID |
|---|---|---|---|
| MissingTranslation | 309 | Default `res/values` is Arabic; 309 keys missing from `values-en` (e.g. `ac_policy_ar`, `tl_help_privacy`) → English-locale users see Arabic text in those places | LINT-01 (P2) |
| ByteOrderMark | 7 | Stray U+FEFF in the middle of source: `core/pdf/ArabicShaper.kt:88`, `core/convert/BidiText.kt:75`, `core/connected/ConnectedStamp.kt:48`, `core/office/DocxWriter2.kt:203`, `core/office/OoxmlPackage.kt:316`, `core/ocr/SearchablePdfWriter.kt:40` (in-flux), `core/office/XlsxWriter.kt:20`. If inside string/char literals they are invisible characters emitted into output (Arabic shaping/DOCX/XLSX) | LINT-02 (P2, check each) |
| AppLinkUrlError | 3 | `AndroidManifest.xml:65-67` mime types with upper-case letters (`macroEnabled`) — Android mime matching is case-sensitive → those intent filters never match | LINT-03 (P3) |
| PropertyEscape | 1 | local machine `local.properties` only — not a project defect | — |

Notable warnings:
- `InsecureBaseConfiguration`: `res/xml/network_security_config.xml:4` `base-config cleartextTrafficPermitted="true"` for ALL hosts (justified in comment for OCSP/CRL only). Should be scoped to a domain-config or rely on per-request checks; currently any http:// URL (AI custom endpoint, cloud, links) is allowed in cleartext. → LINT-04 (P2, security)
- `StaticFieldLeak` (9): `BillingManager.kt:37`, `cloud/Box.kt:35`, `Dropbox.kt:27`, `GoogleDrive.kt:48`, `OneDrive.kt:28`, `tts/ReadAloudFocus.kt:12,28`, `sync/SyncLive.kt:24,39` — objects holding a Context (verify whether applicationContext; if Activity → leak). → ARCH cross-ref
- `SetJavaScriptEnabled`: `app/src/main/java/android/print/WebPdfWriter.kt:38` (HTML→PDF WebView). → SEC cross-ref
- `TrustAllX509TrustManager` (3): inside BouncyCastle bcpkix 1.72 jar (library code, not app code); informational.
- `ScopedStorage`/`OldTargetApi`/`SelectedPhotoAccess`: WRITE_EXTERNAL_STORAGE maxSdk 29 (fine), targetSdk 35 not latest, READ_MEDIA_IMAGES declared without Selected Photos handling (see SEC-13).
- `CheckResult`: `core/ocr/EditableTextPdfWriter.kt:78` result of `Rect.intersect` ignored → if rects don't intersect, the erase box is the unclipped one (in-flux area; pdf-ad session). → LINT-05 (P2)
- `GradleDependency`/`NewerVersionAvailable` (25): androidx (activity 1.10.1→1.13, navigation 2.8.9→2.10, lifecycle 2.8.7→2.11, core 1.15→1.19, room 2.7.1→2.8.5, work 2.10→2.12, datastore 1.1.4→1.3, window 1.2→1.5), play-services-ads 24.4→25.5, UMP 3.2→4.0. Upgrade only after compatibility testing.
- `UseKtx` 199, `PluralsCandidate` 119 (Arabic needs plural forms: zero/one/two/few/many/other), `UnusedResources` 86.
