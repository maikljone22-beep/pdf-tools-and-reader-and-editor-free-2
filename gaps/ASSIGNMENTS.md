# Feature gaps — who does what (user approved: build ALL of them for 0.5.0)

Sources (in this folder):
- `NITRO_FEATURES.md` — Nitro PDF Pro 14.18 inventory (433 features, 24 sections) from the installed program.
- `cmp_01-06.md`, `cmp_07-08.md`, `cmp_09-15.md`, `cmp_16-24.md` — row-by-row comparison with SmartDocs main.
- `OLD_PLAN_AUDIT.md` — old-chat specs (recovered MODULE_*.md) and Acrobat/desktop gap lists vs code.
- `FOXIT_*` — Foxit PDF Editor 14 inventory + comparison (coming; will be assigned when ready).
- Verifier corrections: `verify_A.md` (sections 1-8), `verify_B.md` (9-24) when ready.

Rule: every row with status PARTIAL, MISSING or CODE-ONLY is done, unless it is really present (say so) or not feasible on Android (say why).
Each session ends with a row-by-row table: done / already present / not feasible.

| Session | Branch | Scope |
|---|---|---|
| pdf-3c | feature/nitro-protect-sign | Nitro 9 Protect, 11 Sign |
| pdf-3c (next) | feature/nitro-nav-settings | Nitro 16 Bookmarks/links/actions, 17 Attachments/Portfolio, 21 Batch, 22 Cloud/sharing, 23 Preferences, 24 Other; old-plan: AI-off leaks (Commands5 / ToolScreens), settings rows (PREF-*), sync notification on Android 12+ (setForeground + dataSync), Home "search all files", indexSoon caller |
| pdf-39 | feature/nitro-tools | Nitro 10 Redaction, 12 Headers/Footers/Watermark/Bates, 14 Optimize, 15 Compare, 13 OCR (after fix/ocr-quality merges) |
| pdf-39 (next) | feature/nitro-pages-convert | Nitro 4 Pages, 5 Create/Combine, 6 Convert, 18 Layers, 19 Accessibility, 20 PDF/A; old-plan: scan K3/K7/K8/K12/K13, A5 page labels in reader, A6/FILE-2/3 honour /Direction + OpenAction + PageLayout |
| pdf-ad (agents) | feature/nitro-view-edit | Nitro 1 Shell/View, 2 File/Properties, 3 Edit text & images, edit5 tools reachable from the reader, spell check |
| pdf-ad (agents) | feature/nitro-comments-forms | Nitro 7 Comments, 8 Forms (form field editing/appearance/format/validate/calc/actions, FDF/XFDF, barcode field, stamps, hide comments) |
| pdf-ad (agents) | feature/old-plan-ai-office | old-plan AI gaps (rewrite replace, citations highlight, AI edit via chat, slides, contract review, ask image, …), Office print / read aloud / search in Excel & PowerPoint |
| pdf-ad | feature/inline-video, fix/ocr-quality | in progress |

Memory: 16 GB PC. Gradle only with `--max-workers=2`; if a build is killed, do not loop — push and use GitHub CI. One local build per session at a time.

## Foxit comparison (fcmp_*.md, 1,639 rows) — assigned 2026-10-27 16:20
| Foxit sections | Owner |
|---|---|
| 1 Shell, 2 Home, 3 Open/Save, 9 View, 10 Read aloud, 12 Search, 14 Edit, 22 Spell check/Articles/Properties, 39 Keyboard, reader URL tap/preview, 3D notice | pdf-ad view-edit agent |
| 24 Comments, 25 Measure/Calculator/TickMarks, 26 Forms, 27 JavaScript editor, 19 Multimedia (audio/image) | pdf-ad comments-forms agent |
| 35 AI & Translation + old-plan AI + Office print/read aloud/search | pdf-ad ai-office agent (feature/old-plan-ai-office) |
| 4 Create, 5 Scan/OCR, 6 Export, 7 Portfolio/Binder, 8 Compliance, 13 Compare, 17 Thumbnails, 20 Optimizer, 22 Layers, 23 Organize/HF/Bates, 29 Redaction (Smart Redact AI), 34 Accessibility | pdf-39 |
| 15 Links/Destinations/Cross-ref, 16 Bookmarks, 18 Attachments, 21 Action Wizard, 28 Protect (reader honours permissions), 30 Sign (multi-page, batch, banner, doc timestamp), 31 Share (strip metadata), 36 Batch print, 37 Preferences (offline, JS off, Action Inspector), 40 Panels | pdf-3c |
| 11 3D, 32 desktop ECM, 38 plug-ins, most of 39 command line | N/A on Android (documented in fcmp files) |
