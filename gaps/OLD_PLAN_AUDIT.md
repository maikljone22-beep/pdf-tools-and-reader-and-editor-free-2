# Gap audit results (2026-10-27)
## Office/sync/share/conn (done)
- NEEDS-SERVER/KEYS: no cloud.properties; workers (collab, share, esign, connected) not deployed; Drive OAuth + drive.appdata scope.
- Office: no Print, no Read aloud; search only Word/text (not Excel/PPT).
- Sync: setForeground never called; manifest lacks FOREGROUND_SERVICE_DATA_SYNC + SystemForegroundService dataSync (Android 12+ notification).
- Sync near-realtime partial (60s poll, no FileObserver/longpoll). Review poll-interval setting + take over comments missing. Office command registry missing. Live reviewers' comments not drawn (optional).
## Search/AI/forms/fid/monet (done)
- AI missing: slides gen B13, contract review/table->CSV B17, image gen AI-8, ask about image AI-10, web search AI-14, skills AI-6, AI edit via chat B10 (highlight/redact/replace), Home conversations list AI-5, DOCX export AI-15, Anki AI-19, read-aloud reading order C1, embeddings rerank, podcast edit.
- AI partial: rewrite selection can't replace (onReplace=null, ReaderScreen.kt:264,364); citations no passage highlight; translate no side-by-side; scanner no AI buttons; smart fill not in reader form bar; multi-file chat only via Spaces; usage local only; voice no barge-in; PII regex only.
- Search: Home "search all files" -> Files tab (HomeScreen.kt:118) PARTIAL; indexSoon no caller (IndexWorker.kt:114); searchScopes code-only (TextSearch.kt:74); hit colour missing; search in attachments missing.
- Redaction: code-set import/export + .txt picker missing; auto sanitize on share missing.
- Forms: Forms tool still shows "impossible" for dynamic XFA (FormsScreen.kt:133,211,241); XFA remove row missing; picture clauses/scripts missing.
- Ads: banner in ResultSheet missing; ads/licence off (no properties) = config.
## Edit/view/tools (done) — round 5 rebuilt only part of edit4/view4/tools4
1 Edit5 tools only reachable from Tools card e5_hub (ToolsScreen.kt:93 -> Edit5Hub.kt:55-75); reader never links; old outline/watermark routes open old screens (ToolScreens.kt:44,90).
2 Spell check missing everywhere. 3 Rich text in edit mode basic (no underline/strike/super/sub/bullets/justify; fonts SANS/SERIF/MONO only).
4 Image edit: no flip/crop/opacity/z-order/any-angle, no image editor, no ACTION_EDIT.
5 Organize: no odd/even/range select, no swap, flatten whole doc only, no clipboard insert.
6 Comments: no hide toggle, no copy/paste, no align/distribute, props partial, no ink->shapes.
7 View: no zoom presets/marquee zoom, no word count, no continuous facing.
8 Region OCR/export missing; openOcrForPage (QuickController.kt:79) no caller.
9 Bookmarks: no export/import/search/sections/add-from-selection.
10 Page labels not shown in reader (ReaderExtras.kt code-only); initial view/R2L/Lang not honoured on open.
11 Scan: no re-edit, eraser, deskew, curvature. 12 No custom blank size, no template search.
13 Shell/settings: no more-settings, offline mode, recent size, tab menu, crash recovery, accent colours.
14 Print: no greyscale/scale/comment summary, multi-file, poster. 15 Files: no paste/snapshot, save-as hub, optimizer presets/batch, fonts tab.
16 Convert: web capture options, combine TOC/binders, TIFF/BMP, XML export, batch from Files multi-select.
17 Attachments reader tab, layers, media /Screen (video agent covers), portfolio offer.
18 Accessibility: autotag basic, AI alt text never sees image (T5Hooks.describeImage never assigned T5Hooks.kt:18), no reading order/tags panel.
19 Compare formatting, preflight profiles, PDF/A 2b only, PDF/X. 20 niche: named dests, reflow edit, text block join/split, shapes/vector, transitions writer, etc.
## Acrobat/desktop gap lists + REMAINING B (done) ~170 rows: 79 DONE, 66 PARTIAL, 26 MISSING, 2 CODE-ONLY, 1 NF
Ranked non-DONE: A6/FILE-2/3 reader ignores /Direction R2L, OpenAction, PageLayout (DocPropsOps writes them); H1 no auto form-field detection + I1 smart fill&sign manual; C1 read-aloud raw order, no field reading, no sentence highlight; B7/PREF-8 AI-off leaks (Commands5.kt:83, ToolScreens unguarded); B11 rewrite onReplace=null; B1 citation highlight; K2 re-OCR skips text pages (OcrRunner.kt:99), K3 saved scan edit, K12 deskew; E6/E7/E8 edit quality; B10 AI chat edit; M5 TalkBack ~35 null contentDescriptions; B16 scan AI; I3 no system CA/revocation/signed-version view/banner; E3/E4 no PDF source, Tools watermark tile old screen (ToolsScreen.kt:55); H2 field props (comb, format/validate/calc authoring, align, tab order), H3 autocomplete settings, H4 only JSON (no FDF/XFDF/CSV); remember settings per tool (ReaderState.kt:115-118); D4 basic line tool no dash; A5 page labels; PREF-2 direction/digits default; SHL-4 selection bar (squiggly, note, bookmark, word count, rewrite); SHL-10 crash recovery/Revert; FILE-10 save-as; A1 reflow tables; F4 page pick combine; G5/FILE-7 CCITT/presets; FILE-9 MRC; B9/B14/B6; D2 snap to shape; PREF-7 offline; SHL-5; transcription; B13 slides; K13; FILE-4 fonts screen; PREF-1 settings search; PREF-3/4/6; polish: PREF-5, F5 clipboard insert, F6, F8 /Trans, G3 TIFF, G8, M2 autotag, H6 distribute, I4 FieldMDP, I5 KeyChain signing, K7, K8, K11 QR, D6 /RC, D7 spell, E9 vector, E10 rotated text, A4 layers, A7 media annots, A8 portfolio on open, B15, B17, G9, SHL-3/6/7/12/13, FILE-1 XMP, FILE-5 print presets not read, PREF-9, invite link, tabletop split, image gen, overlay magnifier.
