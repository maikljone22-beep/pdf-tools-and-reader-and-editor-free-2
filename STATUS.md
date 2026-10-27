# SmartDocs — release status (single source of truth)

Updated by the integrator (pdf-ad) after every merge. Last update: 2026-10-27 07:10.

## Version 0.5.0 = "complete" when ALL boxes below are checked
Scope is FROZEN for 0.5.0: no new features are started until 0.5.0 is released. New requests go to "After 0.5.0".

### A. Features (code done, merged in main)
- [x] Arabic search, copy, editing fixes
- [x] Forms on the page, AI assistant, search & redaction, editing & pages, view & comments
- [x] Scanning & conversion, Convert hub (65 conversions) + extract images
- [x] Word / Excel / PowerPoint editors (basic)
- [x] Sharing & clouds, small gaps, unified undo/redo, page-turn effects
- [x] OCR fixes + root-cause fixes, compression fix, safe write-back

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

### D. Device test by the user (only the user can do this)
- [ ] Test APK installed on the tablet, the checklist in "Test checklist" passed, crashes reported and fixed

## APK schedule
- Every merge into main → GitHub builds an APK automatically (Actions tab → Artifacts).
- A local APK in D:\pdf is built only when no other session is building (the PC has 16 GB; parallel builds get killed).
- Next APKs: after B (pdf-39 Stage 5) and after B (pdf-3c 5-6); then the release candidate after C.

## After 0.5.0 (not started)
- Large Word / Excel / PowerPoint feature round (~960 features), remaining P2-P4 audit findings.
