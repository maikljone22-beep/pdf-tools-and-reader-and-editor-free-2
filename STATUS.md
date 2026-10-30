# SmartDocs — release status (single source of truth)

Updated by the integrator (pdf-ad) after every merge. Last update: 2026-10-28 14:15.

## Version 0.5.0 = "complete" when ALL boxes below are checked
Scope is FROZEN for 0.5.0: no new features are started until 0.5.0 is released. New requests go to "After 0.5.0".

### A. Features (code done, merged in main)
- [x] Arabic search, copy, editing fixes
- [x] Forms on the page, AI assistant, search & redaction, editing & pages, view & comments
- [x] Scanning & conversion, Convert hub (65 conversions) + extract images
- [x] Word / Excel / PowerPoint editors (basic)
- [x] Sharing & clouds, small gaps, unified undo/redo, page-turn effects
- [x] OCR fixes + root-cause fixes, compression fix, safe write-back
- [x] Arabic copy fix: wrong ToUnicode maps repaired, OCR layer copies in reading order, broken duplicate lines dropped — merged ea1cb42 (ARABIC_COPY_r5.md)

### A2. Feature gaps vs Nitro / Foxit / old plan + deep audits (user approved for 0.5.0) — see gaps/
- [x] Nitro (868 rows) + Foxit (1,639 rows) + old-plan comparisons; owners in gaps/ASSIGNMENTS.md
- [x] Convert section audit: 124 defects fixed (gaps/convert-audit/FIXED.md)
- [x] Reader tab audits: View+Edit 59, Comment 37, Fill&Sign+Sign 37, Tools+Pages 98, redact sub-audit 20 — fixed (gaps/tabs-audit/FIXED_*.md)
- [x] Central signed/certified gate (SignedGuard) used by every writer
- [x] Open every file type, selection menus per tab, in-page video, Pro OCR (PP-OCRv5 + fine-tuned Tesseract), PDF→Word fix
- [x] FINAL_CHECK_2.md: all 24 Medium rows done (cloud session 2026-10-30: #1 Combine page view, #5 DOCX comments, #12 portfolio part save-back, #14 rich text per character, #16 scan paper form → form, #19 order on the page + touch-up, #20 content panel / checker page range / tag from selection, #21 alt-text step, #22 watched folders; the rest by earlier merges)
- [ ] FINAL_CHECK_2.md: ~140 Low rows (web-capture / printer options, Combine / portfolio / export options, preflight, PDF/X-E-A-4, JS stubs, XFA events, tab groups, 6400 % zoom, nested XObject text, bookmark options, AI extras)
- [x] Scanner: SmartDocs camera (full resolution, live edges, auto-capture, corner editor), smart-enhance filter, curved-page flattening, high-quality output; video icon / player choice; error centre with auto-send to GitHub issues (ai-proxy/errors)
- [ ] Device test with TEST_CHECKLIST.md (user)
- [ ] Owner actions in SERVER_SETUP.md (user)

### B. Audit fixes — DONE
- [x] Areas 1-3 stages 1, 2, 3, 4 (pdf-39) — merged
- [x] Area 7 cloud/sync (pdf-3c) — merged
- [x] Area 5 reader parts: RD-01, RD-05, RD-04, AN-06, AN-13 + signed-file message + ConnMarker hook (pdf-39) — merged b440f95
- [x] Areas 5-6: signed save, keep encryption, in-place reorder, Arabic FTS, rename updates, conn cache (pdf-3c) — merged cf15d6d; signed-save gap for comment ops merged

### C. Release blockers
- [x] New release signing key created (backup in D:\pdf\signing-key-backup)
- [x] targetSdk raised to 36 — merged (note: Android 16 ignores the reader orientation lock on tablets ≥ 600 dp)
- [x] Missing English strings filled (307 added, 0 missing) — merged
- [x] Release build 0.5.0 signed with the new key (APK + AAB) — D:\pdf\SmartDocs-0.5.0-release\ (install on device still to verify)
- [ ] Rebuild release 0.5.0 with the Arabic copy fix (ea1cb42) — local build running

### D. Device test by the user (only the user can do this)
- [ ] Test APK installed on the tablet, the checklist in "Test checklist" passed, crashes reported and fixed

## APK schedule
- Every merge into main → GitHub builds an APK automatically (Actions tab → Artifacts).
- A local APK in D:\pdf is built only when no other session is building (the PC has 16 GB; parallel builds get killed).
- Next APKs: after B (pdf-39 Stage 5) and after B (pdf-3c 5-6); then the release candidate after C.

## After 0.5.0 (not started)
- Large Word / Excel / PowerPoint feature round (~960 features), remaining P2-P4 audit findings.
