# Audit agent brief (shared by all audit sub-agents)

Project: SmartDocs — Android PDF reader/editor. Kotlin 2.3 + Jetpack Compose, minSdk 26, targetSdk 35.
PDFium (io.legere:pdfiumandroid, wrapped in core/pdf/PdfSession.kt), PdfBox-Android 2.0.27, Tesseract4Android,
Room, DataStore, WorkManager, Rhino (form JS), OkHttp, ML Kit, Cloudflare Workers in ai-proxy/.
~100k lines Kotlin under app/src/main/java/com/smartdocs/editor/{core,ui}. Module notes: MODULE_*_r5.md,
REMAINING_PLAN.md, CRASH_AUDIT_r5.md, REVIEW_runtime.md in the project root.

## Where to read code
READ from the stable snapshot worktree: `D:\pdf\audit-wt` (commit da74d31). Cite paths repo-relative
(e.g. `app/src/main/java/com/smartdocs/editor/ui/reader/ReaderScreen.kt:123`).
`core/convert6`, `ui/convert6`, `core/pageflip`, `ui/pageflip` are only in `D:\pdf\SmartDocs-project-v0.4.0`
(uncommitted, being edited by another session). Those + `core/ocr`, `ui/ocr`, `core/search/ArabicTextFix.kt`,
`ui/tools/ToolsScreen.kt`, `ToolScreens.kt`, `ui/home/HomeScreen.kt` are IN FLUX — you may read them but
mark findings there "(in-flux area)".

## Hard rules
- READ-ONLY. Do not edit any source file, config, or document. The ONLY file you write is your own findings file
  named in your task (under `D:\pdf\SmartDocs-project-v0.4.0\AUDIT\`).
- Do NOT run gradle anywhere (the lead runs builds). Do not run the app. No network calls to project backends.
- Never print secrets/tokens you find — reference file:line and say "secret present", redacted.
- Test PDFs (read-only; copy to your own scratch dir before any processing):
  `C:\Users\User\Downloads\Telegram Desktop\chemistry\*.pdf` and other PDFs in `C:\Users\User\Downloads\Telegram Desktop\`.
- Do not assume a feature works because a button exists: trace from UI control → handler → core implementation → persistence.
- No generic advice. Every finding needs code evidence (file:line + short quote/trace). If you could not verify, label it.
- No on-device testing is possible (no emulator). Mark anything needing a device as "needs device test".

## Finding format (use exactly; one block per finding; IDs use your prefix)
```
### <PREFIX>-<nn> <short title>
- Type: bug | missing feature | usability | enhancement | security | data-integrity | performance | arch-risk | test-gap
- Status: CONFIRMED (traced in code) | PROBABLE | HYPOTHESIS
- Severity: Critical | High | Medium | Low      Priority: P0 | P1 | P2 | P3 | P4
- Evidence: file:line — what the code does (short quote or trace)
- User impact / scenario: concrete scenario
- Proposed fix: smallest concrete change
- Effort: S (<2h) | M (<1d) | L (>1d)     Regression risk: Low/Med/High     Dependencies: ...
- Validation: unit test / device test that proves the fix
```
Priority guide: P0 = data loss, corruption, security hole, crash in a core flow, accessibility blocker;
P1 = high-impact bug/friction in common workflows; P2 = valuable everyday improvement; P3 = power-user;
P4 = experimental/low value.

## Output file structure
1. `## Scope & method` (what you read; what you could not verify)
2. `## Capability inventory` (feature → status: works / partial / stub / broken / misleading, with evidence)
3. `## Findings` (blocks as above, most severe first)
4. `## Quick wins` (small effort, high impact; list IDs)
5. `## Needs device testing` (list)
Aim for depth over breadth: 25–60 well-evidenced findings. Finish with a 10-line summary in your final reply.
