# Scanner deep audit (2026-11-01): engineering + UX specialists

Read-only reviews of main df9d4a8, from two directions: an Android engineering bug hunt (E) and a UX / accessibility / Arabic copy review with personas and a competitor view (U).
They come on top of `SCANNER_UX_AUDIT.md`. Status: [x] fixed (batch), [ ] open.

## Engineering (E)
- [x] E1 High — camera decode of >16 MP photos at sample 1 → OOM → page silently lost (ScanCameraScreen decode / capture resolution)
- [x] E2 High — ENHANCE filter at 4096 px (6144 for books) needs ~20 B/px heap → save fails with OOM
- [x] E3 High — auto-capture re-captures the same page after "Use" (lastAuto set at capture time)
- [x] E4 High — Done/close while straightening drops pages, leaks files, may reorder pages
- [x] E5 Med — scanner temp pictures never deleted (S1: deleted after save / clear / discard; 24 h purge covers cam_ / t5scan_)
- [x] E6 Med — corner editor accepts crossed / collapsed corners
- [x] E7 Med — EXIF transpose / transverse (mirrored) ignored
- [x] E8 Med — changing the kind after capture keeps the old kind's filter / flatten
- [x] E9 Med — page editor preview recomputes on every slider tick (no debounce, no cache)
- [x] E10 Med — save runs PDF / file work on Main (deleteBlank, copies, project delete)
- [x] E11 Med — book halves leak bitmaps; eraser applied before per-half dewarp misses
- [ ] E12 Med — live edge detection allocates ~10 MB per frame; sharpness on Main
- [ ] E13 Med — shutter has no accessible label; corner editor drag-only
- [x] E14 Low — projects deleted in one tap (S1: confirmation)
- [x] E15 Low — re-saving a reopened project duplicates it
- [x] E16 Low — pages-per-file ignores ID card / book
- [x] E17 Low — ID card page with non-A4 paper is shrunk / mis-scaled
- [ ] E18 Low — gray flag unused (gray pages stored as colour JPEG); biased bilevel sample
- [x] E19 Low — recent page count stale after blank-page removal
- [ ] E20 Low — receipt / contact parsed from the last document only
- [x] E21 Low — three prefs files; some options saved only on Save; presets miss camera / quality
- [ ] E22 Low — exposure slider not reset after rebind
- [x] E23 Low — QR card: Back closes camera; WIFI: treated as a link; flat page index in results
- [x] E24 Low — preset delete icon 18 dp
- [x] E25 Low — capture / decode failures silent
- [ ] E26 Low — live outline may be offset when analysis and preview aspect differ (unverified)
- [ ] E27 Low — executor shut down while takePicture pending (unverified)
- [x] E28 (found while fixing) — newTemp() names collide within one millisecond (counter added)

## UX (U)
- [x] U1 High — last page lost while straightening (= E4) + no progress
- [x] U2 High — duplicate auto-capture (= E3); needs "turn the page"
- [x] U3 High — «مسح الكل» ambiguous, no confirmation (S1: «حذف كل الصفحات» + confirm); eraser undo label still open
- [x] U4 High — back discards unsaved pages silently (S1: confirm; pages also survive leaving / process death)
- [x] U5 High — scans saved under Android/data (invisible to Files / WhatsApp); multi-result list has no "save to device"
- [x] U6 High — scan projects pile up full-size copies; only 6 visible; no size / cleanup
- [x] U7 High — TalkBack: no shutter label, hints not announced, no capture sound / haptic
- [x] U8 High — camera toggles show state by colour only; flash label never says the mode
- [x] U9 High — page editor loses edits (move closes, rotate wipes eraser, outside tap discards)
- [x] U10 High — saved scan deleted without confirmation (S1: the studio list confirms; old screen retired from navigation)
- [x] U11 Med — ↑/↓ arrows for an RTL horizontal row
- [x] U12 Med — "apply to all" copies only filter / brightness / contrast
- [x] U13 Med — high quality default, no size choice / estimate
- [x] U14 Med — OCR off still runs OCR (smart name forces it)
- [x] U15 Med — receipt 5 / card 2 page limit silent; kind remembered across sessions
- [ ] U16 Med — one progress message for all save phases
- [x] U17 Med — Arabic counts not pluralised («3 صفحة»)
- [x] U18 Med — jargon / ambiguous labels (دفعة، إعادة، استوديو، Tabloid…)
- [x] U19 Med — corner handles tiny, no magnifier, no "detected edges" reset
- [x] U20 Med — presets: no selected state / feedback, tiny delete
- [x] U21 Med — no torch (only capture flash)
- [x] U22 Med — "ready, tap shutter" while auto-capture waits on sharpness
- [x] U23 Med — PDF only: no JPG export, no "copy text"
- [ ] U24 Low — no tablet / landscape layout
- [x] U25 Low — QR result: no copy toast, no typed actions, link opened unchecked
- [x] U26 Low — TalkBack reads thumbnail number twice
- [x] U27 Low — pages-per-file field opens the text keyboard
- [x] U28 Low — exposure slider unlabeled, no reset
- [x] U29 Low — old scanner shows raw English exception text
- [x] U30 Low — failed multi-file save then retry duplicates files

## Batch S2 (continuous capture)
- Pages keep the raw photo + corners (PageEdit.crop); corners editable later; gallery photos edge-detected; no in-camera straightening (fixes E1/E4); re-arm by page change (E3); corners validated (E6); EXIF mirror (E7); kind change remaps defaults (E8); preview debounce (E9); capture errors shown (E25); torch, labelled toggles, shutter label, live hints, haptic + shutter sound (U7/U8/U21/U22); editor keeps edits on move / rotate / outside tap, mirrored before/after arrows, prev/next page (U9/U11); dp handles + detected-edges reset (U19). E2 mitigated: high quality 3508 px, book cap 4200 px, decode ≤ 1.5× target.

## Batch S4–S6
- Kinds + per-kind guidance inside the camera, gallery button in the camera, page limit shown (U15).
- Save: size choice small / medium / best (U13), PDF or JPG (U23), copy recognised text (U23), OCR off really off (U14), ID card always A4 (E17), pages-per-file by output pages (E16), a failing document does not stop the rest and stays for retry (U30), blank-page count (E19), file work off Main (E10), basic options first and the rest folded (audit #10), name preview (audit #14), numeric keyboard (U27).
- Projects: all listed with total size, reopening replaces instead of copying (U6 / E15). Presets: selected state, feedback, real touch target (U20 / E24).
- Results: "save to folder" for every multi-result list in the app (U5). Second document / duplex discoverable (audit #9).
- Arabic: plurals for page counts (U17), labels: عدة مستندات، إعادة الالتقاط، تراجع عن آخر محو، كاميرا التطبيق، المسح الضوئي (U3 / U18). Book half leak (E11).
- Still open: E12 analyzer allocations (perf), E18 gray JPEG, E20 receipts from every document, E22 exposure after rebind, E26 / E27 (unverified), U16 phase progress, U24 tablet layout.
