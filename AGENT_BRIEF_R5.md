# SmartDocs — round 5 brief (rebuild on Windows)

Project root: `D:\pdf\SmartDocs-project-v0.4.0` (Android, Kotlin 2.3.21, Compose BOM 2025.03, minSdk 26, package `com.smartdocs.editor`).
Sources: `app/src/main/java/com/smartdocs/editor/`. Read `AGENT_BRIEF.md` (core APIs, conventions) first, then your spec.

Context: the work of an earlier round was lost; only its module notes survive in `D:\pdf\recovered-from-old-chat\MODULE_*.md`.
Your recovered note is your SPEC: rebuild what it describes on top of the current code, adapting to what actually exists here
(file names/APIs in the note may differ — check the real code). Do not assume any file mentioned in the note exists; grep first.

## Rules
- Do NOT run gradle (one build at a time; the lead compiles). Write code that compiles: correct imports, real APIs.
  Verify library APIs from jars in `C:\Users\User\.gradle\caches\modules-2\files-2.1\` (e.g. `javap` from the JDK, or unzip) or from existing code.
- Prefer NEW files in your own packages. Do NOT edit these shared files: `ui/reader/ReaderState.kt`, `ReaderScreen.kt`,
  `ReaderToolbars.kt`, `ReaderOverlays.kt`, `PageCanvas.kt`, `ui/tools/ToolScreens.kt`, `ui/tools/ToolsScreen.kt`, `ui/Navigation.kt`,
  `core/pdf/PdfOps.kt`, `core/pdf/Annotations.kt`, `core/pdf/PdfSession.kt`, `MainActivity.kt`, `SmartDocsApp.kt`, `build.gradle.kts`,
  `AndroidManifest.xml`, `res/values/strings.xml`. Instead, list exact hooks (file, anchor line to find, code to insert) in your MODULE file;
  the lead applies them. Small, additive edits to other existing files of your own area are OK.
- No new Gradle dependencies unless unavoidable (then list them in your MODULE file and explain).
- Strings: `res/values/strings_<module>.xml` + `res/values-en/strings_<module>.xml`, same keys, unique prefix. Arabic is the primary UI.
  In XML escape apostrophes (`\'`) and use `&amp;`. Never put raw invisible Unicode characters in Kotlin source — use `\uXXXX` escapes or `.code` comparisons.
- Pure logic (parsers, matchers, calculators): add JUnit 4 tests under `app/src/test/java/...` (junit 4.13.2 is available).
- Keep files < 600 lines. Tablet-aware layouts where useful.
- When done write `MODULE_<name>_r5.md` in the project root: files, hooks for the lead (exact), status of every feature in your spec
  (DONE / PARTIAL with what is missing / NOT FEASIBLE with reason).
