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
