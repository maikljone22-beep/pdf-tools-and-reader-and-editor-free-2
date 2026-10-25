# SmartDocs — remaining work (rebuilt plan, 2026-10-26)

Baseline: v0.4.0 (local git commit "Baseline"). Work done in the old chat after 0.4.0 was lost; its module notes are in
`D:\pdf\recovered-from-old-chat\` and are the spec for rebuilding. Status: [ ] todo, [~] in progress, [x] done (compiles).

## A. Rebuild lost post-0.4.0 work (spec = recovered MODULE_*.md)
- [~] search4: Arabic-aware search (reader) — ArabicNormalizer/TextMatcher/SearchPrefs + reader menu switches
- [ ] search4: search across all files (FTS index + worker), find & replace, search & highlight, sanitize, pattern redaction, mark→review→apply redaction, whole-page redaction, AES-256 + 8 permissions (protect_advanced)
- [ ] ai4: whole-document AI (chunked retrieval) with page citations, custom summary, suggested questions, study tools (flashcards, quiz, study guide, glossary, mind map), voice chat/dictation, spaces (multi-file), translate with layout, rewrite/grammar/explain, PII detection, form-fill suggestions
- [ ] edit4: real links (URI/page/file/email), headers & footers, Bates, watermark/background (incl. dynamic user/date + security watermark), page labels, real crop + auto margins, resize, booklet/n-up/poster, replace/split/interleave pages, outline editor keeping colour/bold/italic/zoom + auto bookmarks + TOC page, document properties
- [ ] view4: measure (distance/perimeter/area/circle/angle/count, scale calibration, CSV), callout/typewriter/tick/area highlight, comment manager + CSV/Word export, export highlighted text, dynamic stamps with Hijri date, full reflow (text+images), split view / side-by-side compare, presentation mode, rulers/grids/reading ruler/lens, auto-scroll, back/forward history, command search, shortcuts, trash bin (30 days), print options, help centre
- [ ] tools4: scan studio (ID card, business card, receipt, whiteboard, book, batch; brightness/contrast), any-format→PDF (connect core/office readers), share-to-PDF, virtual printer, PDF/A, optimizer + fast web view, OCR batch/auto, batch recipes, visual + word compare, accessibility check/tagging, RTF export
- [ ] forms2: fill forms on the page in the reader with live calculations, next/prev field, date picker, autocomplete, validation, image/date fields, submit to URL, XFA rendering
- [ ] office: open/edit Word, Excel, PowerPoint inside the app (Office-style tabs), save to same file
- [ ] sync2/share5/conn/monet/fid: sync all three clouds, comment-preserving shared review, share options, view-only links, signature requests, connected documents, licence server + AdMob, MS-RMS recognition

## B. Gaps found by the 0.4.0 audit (not covered above)
- [ ] Dual-screen / foldable layout
- [ ] Page templates stored in the document
- [ ] Line dash styles and line-end picker for annotations
- [ ] Remember last settings per annotation tool
- [ ] AI summary of comments (added as a comment)
- [ ] Insert pages from camera; graph/music blank page styles in Organize
- [ ] Modifications change log before signing
- [ ] Trusted-certificates list, certificate serial/key usage, signature appearance designer
- [ ] Full "sign here" stamp set (accepted, initial, rejected, sign here, witness)
- [ ] Read aloud: repeat, pause on phone call / audio focus
- [ ] AI: speech-to-text transcription, image generation (needs AI-proxy routes)
- [ ] Files: tag limit 5, copy, unzip in UI
- [ ] Cloud: Box, SharePoint (enterprise — likely not feasible), per-file sync queue
- [ ] Wi-Fi transfer: Wi-Fi-only vs mobile data option
- [ ] Email review flow
- [ ] Settings: full-screen top bar, volume keys zoom, RTL toggle, global auto-sync, transfer network, AI on/off; volumeKeysFlip default on
- [ ] Account: sign-in, activated devices, invite/referral
- [ ] Home: template entry, tool wizard; Home/Files two-pane on tablets
- [ ] System overlay magnifier (over other apps) — low priority

## C. Office feature rounds (specs: excel.md recovered; Word/PowerPoint specs lost — re-derive)
- [ ] Word: ~382 features    - [ ] Excel: ~260 features + ~220 functions    - [ ] PowerPoint: ~319 features

## D. Not feasible on Android (keep documented)
3D PDF content, smart cards, XPS export, print-production previews, DWG→PDF, MS RMS decryption, encrypt-all-except-metadata / attachments-only (PdfBox limit).

## Build notes
- Windows locale is Arabic: gradle.properties forces `-Duser.language=en -Duser.country=US -Duser.language.format=en -Duser.country.format=US`, otherwise Room's KSP output contains Arabic digits and fails to compile.
- Release signing key `release.jks` + `keystore.properties` is NOT in this folder (was on the old machine).
