# FINAL CHECK 2 (main b719484)

Second completeness check, read-only on code, against main **b719484** (the first check, `gaps/FINAL_CHECK.md`, was against fa724fd).
Re-checked: every row still STILL MISSING / PARTIAL in FINAL_CHECK.md, every row of its "Status updates after this check" table, and every PARTIAL / OPEN item in `gaps/DONE_*.md`, `gaps/tabs-audit/FIXED_*.md`, `AUDIT/*_LOG.md`, `gaps/OPEN_ALL_FILES.md` and `gaps/SELECTION_MENUS.md`.
Nothing was run on a device; every DONE row still needs a device test (see `TEST_CHECKLIST.md`).

> **Status 2026-10-30 (cloud session):** every Medium row below is DONE in `main` — #2/3/4/6/7/8/9/10/11/13/15/17/18/23/24 by earlier merges, #1/5/12/14/16/19/20/21/22 by the `feature/*` merges of 2026-10-30 (see `git log --merges`). Only the Low rows remain.

## STILL MISSING: short list (deduplicated; there are no High items)

Med, by owner area:

| # | Area | Missing | Parts |
|---|---|---|---|
| 1 | pages/convert | Combine: page-level view, page thumbnails, expand files into pages and move pages between them | B, C, D |
| 2 | pages/convert | Output folder choice for Split, Combine, "each file" To-PDF (also folder input + sort), batch | A, B, C |
| 3 | pages/convert | Creation presets and a Tagged-PDF option when creating a PDF | B, C |
| 4 | pages/convert | Export a selection / region as Word, Excel, PowerPoint (today .txt only) | B, C, D |
| 5 | pages/convert | DOCX export with comments | B, C |
| 6 | pages/convert | PDF→Word: "precisely laid out" text-box mode, image anchoring, on/off switch and language for text recovery | A, C |
| 7 | pages/convert | Replace pages from a non-PDF file: all three entry points use the PDF-only picker (`PageOpsScreen:72`, `ReaderThumbs:149`, `OrganizeScreen:154`), **claim failed spot-check** | C |
| 8 | pages/convert | Split several files at once | C |
| 9 | pages/convert | Office → PDF conversion options, Excel fit-to-page | C |
| 10 | pages/convert | Legacy .ppt → PDF in the convert hub (only the viewer's text-only route) | A |
| 11 | pages/convert | Image → PDF custom page size | A |
| 12 | pages/convert | Portfolio: edit a part and save it back | C, D |
| 13 | comments/forms | Automatic field recognition after Create PDF | C, D |
| 14 | comments/forms | Fill-time rich text per character (8.28: whole-field style only) | A, C |
| 15 | comments/forms | Video annotation properties dialog and playback settings | C |
| 16 | comments/forms | Scan a paper form into a form (pdf-39 in progress) | B, C |
| 17 | edit | Search & Remove Text (word list) | A, C |
| 18 | edit | GoToE link destination; set a bookmark's destination to the current view | C |
| 19 | tools/OCR | Accessibility: area reading order and on-page touch-up tool | C, D |
| 20 | tools/OCR | Accessibility: Content panel; checker page range; create tag from selection | C, D |
| 21 | tools/OCR | Make Accessible has no alt-text step | C |
| 22 | tools/OCR | Watched folder (batch) | B, D |
| 23 | reader/view | Per-document reading options | C, D |
| 24 | AI/office | PPTX export without speaker notes; no Spaces entry (old B13) | D |

Low (details in the parts): about 140 unique rows. The large groups:
- **pages/convert**: web-capture options, printer service options, Combine / portfolio / binder options, export options, preflight libraries, print-production checks, PDF/E and PDF/X output, PDF/A-4.
- **comments/forms**: JS stubs (`scroll`, `setAction`, `buttonSetIcon`, `signatureSign`), the GoBack / GoForward / Close / Quit named actions, XFA validate and pre-save / pre-print events, measurement and stamp extras.
- **reader/view**: tab groups, 6400 % zoom, mouse-wheel tab switching, full-screen and page-display preferences.
- **edit**: nested / skewed XObject text, article box move / resize, bookmark generation options.
- **files**: search-index scope, AZW3 images, XPS / DXF details.
- **AI/office**: image editing, infographics, Danish translation, video-summary voices.

OWNER-ACTION (unchanged code-side; all in `SERVER_SETUP.md`):
- redeploy the collab worker (Leave a review), the e-sign worker and the connected worker (open message);
- Graph SharePoint scopes;
- publish `assetlinks.json`;
- forum and Labs URLs;
- Drive OAuth client (Save as Docs / Sheets / Slides);
- AI proxy routes.

## Totals (536 rows over four parts; a row that is open in two sources is counted in each)

| Part | Slice | Rows | DONE | STILL MISSING | NOT-FEASIBLE | OWNER-ACTION | N/A |
|---|---|---|---|---|---|---|---|
| A | FINAL_CHECK §1–8, DONE_view_edit, FIXED_1/3, OPEN_ALL_FILES, SELECTION_MENUS | 101 | 33 | 51 | 15 | 1 | 1 |
| B | FINAL_CHECK §9–24 + fcmp_01-08, DONE_pdf3c_*, FIXED_4, NITRO_TOOLS_LOG | 89 | 27 | 53 | 3 | 5 | 1 |
| C | fcmp_09-23, Foxit §24–27, DONE_comments_forms, DONE_pdf39, PDF39_* / TAB_COMMENT logs | 198 | 40 | 149 | 7 | 0 | 2 |
| D | Foxit §28–42, old plan, DONE_ai_office, FIXED_5, PAGES_*_LOG, status-update table | 148 | 67 | 70 | 6 | 5 | 0 |
| **Sum** | | **536** | **167** | **323** | **31** | **11** | **4** |

## Corrections to earlier documents

- **FINAL_CHECK (first check) said pages-convert stage G was not merged.** It is merged (6476d36), so the following are DONE:
  - PDF/A 1a / 1b / 2a / 2u / 3a / 3b / 3u;
  - preflight profiles, favourites and page range;
  - claim removal;
  - the ZUGFeRD and version checks;
  - the standards wizard;
  - "save as PDF version".
- **FINAL_CHECK §23 "split output options" was DONE**, but only the naming is done; there is no per-run output folder. Now STILL MISSING (item 2).
- **FINAL_CHECK §21 Make Accessible** has no alt-text step (item 21).
- **DONE_pdf39 "replace pages from a non-PDF file"** failed its spot-check (item 7).
- **DONE_pdf39 "convert fonts to outlines" was marked not feasible.** Edit mode already outlines a text block, so it can be built (Low, pages/convert).
- **Judgement calls:**
  - Egnyte is marked DONE through the WebDAV preset, though there is no native Egnyte provider.
  - §42 Callas preflight is DONE for the verify profiles; PDF/X conversion and PDF/VT are NOT-FEASIBLE.
- **Dead code:** `ToolMemoryEffect` (`ui/view5/View5Host.kt:131`) is no longer called.

## Spot-checks

- **34 call chains** were followed from button to handler to core, on merges after fa724fd: A 8, B 9, C 9 (plus the DONE_pdf39 sub-checks), D 8.
- **All 59 rows** of the FINAL_CHECK "Status updates" table were confirmed in code.
- **One failure:** replace pages from a non-PDF file (item 7). The chains are listed in each part.

## Fixed since the first check (beyond the status-update table)

- **pages-convert stage G** (PDF/A and compliance).
- **fix/pages-a11y:**
  - table auto-tagging;
  - PDF/UA-1 machine check;
  - Fix for every rule;
  - Order panel;
  - nested alt.
- **Organize:**
  - per-page Redact and OCR;
  - PDF/A at Combine (3b) and Scan (2b).
- **Comments:**
  - floating note window;
  - cloudy oval;
  - one shared identity;
  - inward / outward scallops;
  - superscript / subscript.
- **Forms:**
  - XDP;
  - OLD I1 Fill & Sign targets on flat forms;
  - XFA picture clauses and events;
  - form overflow indicator.
- **OCR:**
  - K2 redo OCR;
  - auto OCR while editing.
- **Reader:**
  - honours /Direction, OpenAction, PageLayout and PageMode;
  - signatures panel;
  - page labels.
- **Reflow:** multi-page editing.
- **Thumbnails panel:** reorganise, menu, size.
- **Read aloud:** block navigation.
- **Links:**
  - "use current view";
  - named destinations from a list;
  - convert a web link to PDF.
- **FIXED_4:** D21, D22 and D25.
- **FIXED_5:** the rows owned by other branches (central signed gate T-01 / G-01 / E-2 / OCR-1; convert-audit C-11 / C-15 / C-16 / C-17 / C-24).

---


## FINAL CHECK 2 — Part A: Nitro §1–8 (shell/view, file, edit, pages, combine, convert, comments, forms) + view/edit logs

Checked on `D:\pdf\final-check2-wt` = origin/main b719484 (read-only). Paths relative to `app/src/main/java/com/smartdocs/editor/`.
Nothing run on a device; status = code exists + UI caller found.

### 1. FINAL_CHECK.md §1–8 rows that were STILL MISSING (44)

#### §1 Shell / View
| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Tab groups, New Window, Cascade/Tile | STILL MISSING | STILL MISSING | New window done; no tab groups anywhere (no TabGroup code in ui/) |
| Zoom range 25%–6400% | STILL MISSING | STILL MISSING | PageView.kt:45-46 MIN_ZOOM 0.25 / MAX_ZOOM 32 (× fit width) unchanged; NveZoom.percentRange still capped by it |

#### §4 Pages: organize
| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Split opt: destination folder | STILL MISSING | STILL MISSING | PageOrganizeScreen has no folder picker; output still docsDir (+ global NpfSaveFolder copy) |
| Resize: Reset to original size | STILL MISSING | STILL MISSING | CropResizeScreens.kt:173-174 reset is still `doc.undo()`; no stored original size. (Crop has `PageBoxOps.resetBoxes`, resize does not) |
| Redact pages from page context menu | STILL MISSING | DONE | OrganizeScreen.kt:303 pc_page_redact → confirm :347 → OrganizeState.redactPage :226 → PageOrganize.redactPages (core/edit5/PageOrganize.kt:432) |
| OCR pages from page context menu | STILL MISSING | DONE | OrganizeScreen.kt:305 pc_page_ocr → OrganizeState.ocrPage :355 → runOcrToFile(OcrOptions(listOf(page))) |

#### §5 Combine / Create PDF
| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Create PDF from legacy .ppt | STILL MISSING | STILL MISSING | Cv6Catalog `any_pdf` still excludes Kind6.PPT, `ppt_pdf` = PPTX/ODP. Only route: open-all viewer → Convert to PDF (ViewerConvert.kt:41, FType.PPT → slide **text only** printed as HTML). No real slide rendering |
| Dialog: output folder | STILL MISSING | STILL MISSING | CombinePlusUi has add-folder (:71) only; no output folder choice |
| Conversion quality presets (Web/Office/Print) | STILL MISSING | STILL MISSING | Opt enum (Cv6Catalog.kt:12) unchanged; no named presets |
| Custom: font embedding | STILL MISSING | STILL MISSING | no option |
| Custom: image downsample / codec | STILL MISSING | STILL MISSING | only QUALITY |
| Image sources: custom page size | STILL MISSING | STILL MISSING | Images6.kt:6 `PageSize6` = A4/LETTER/LEGAL/A3/A5/IMAGE; CreateTools.kt:29 same presets |
| Page size for text sources | STILL MISSING | STILL MISSING | text_pdf / md_pdf opts = MERGE only |
| PDF/A-1b at creation | STILL MISSING | DONE | "Save as PDF/A" switch at creation: Combine (CombinePlusUi.kt:187,204 → PdfA.convert A3B) and Scan (ScanStudioScreen.kt:272,176 → A2B). Level fixed (2b/3b); 1b only in the separate PDF/A tool (stage G) |
| Excel: bookmark per sheet, fit to page | STILL MISSING | STILL MISSING | no per-sheet outline in ToPdf6/ToPdf (only one bookmark per input file, ToPdf6.kt:293). Wide-sheet landscape/zoom-to-width now automatic (convert-audit A-H9), no option |
| PowerPoint: bookmark per slide, notes, frames | STILL MISSING | STILL MISSING | handouts only |
| HTML: header/footer text | STILL MISSING | STILL MISSING | none in ToPdf/WebCss/ToPdfPlus |
| Printer: target / overwrite / append | STILL MISSING | STILL MISSING | core/tools5/ShareAndPrint.kt `T5PrintService` has no options |
| Printer: PDF version / PDF/A-1b | STILL MISSING | STILL MISSING | same |
| Printer: quality presets, grayscale | STILL MISSING | STILL MISSING | same |
| Printer: PDF properties | STILL MISSING | STILL MISSING | same |
| Printer: PDF security | STILL MISSING | STILL MISSING | same |
| Printer: open after creation | STILL MISSING | STILL MISSING | notification only (ShareAndPrint.kt:162) |
| Scan opt: PDF/A-1b | STILL MISSING | DONE | ScanStudioScreen.kt:92/272 pdfa switch → :176 `PdfA.convert(…A2B)` (2b, not 1b) |

#### §6 Convert from PDF (Cv6Catalog opts unchanged since fa724fd; convert-audit fixed defects, not options)
| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Word: "precisely laid out" text boxes | STILL MISSING | STILL MISSING | pdf_docx LAYOUT keep/flowing only |
| Word: custom character spacing | STILL MISSING | STILL MISSING | no option |
| Word images: anchoring | STILL MISSING | STILL MISSING | INCLUDE_IMAGES only |
| Word tables: threshold / font adjust | STILL MISSING | STILL MISSING | TABLES on/off only |
| Headers/footers convert / delete / don't detect | STILL MISSING | STILL MISSING | no toggle |
| Advanced text recovery option (+ language) | STILL MISSING | STILL MISSING | PdfFlow6.kt:136 always `RepairedInput.of`; no switch, no language |
| Excel: split multi-line text into rows | STILL MISSING | STILL MISSING | no option |
| Excel: reduce merged cells | STILL MISSING | STILL MISSING | no option |
| Text: paragraphs vs N chars/line | STILL MISSING | STILL MISSING | pdf_txt opts RANGE/AUTO_OCR/TABLES |
| Text: discard headers/footers | STILL MISSING | STILL MISSING | no option |
| Extract images: output format | STILL MISSING | STILL MISSING | ImageExtract6 native JPEG/PNG only (an output *folder* picker exists: ExtractImagesScreen.kt:100) |
| Batch: output folder | STILL MISSING | STILL MISSING | ui/convert6 has no tree picker for batch results |
| Batch: page range per file | STILL MISSING | STILL MISSING | no per-file range |

#### §7 Comments
| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 7.10 Floating pop-up note window | STILL MISSING | DONE | annotation bar SA.A_FLOAT_NOTE (AnnotEditUi.kt:93) → `cx.floating` → PageView.kt:433 `FloatingNoteWindow` → Save `CommentExtras.saveFloating` |
| 7.27 Cloudy oval | STILL MISSING | DONE | CommentExtrasUi.kt:91-93 cloud chips for OVAL → `h.rectCloud` → CommentPrefs.kt:68 → AnnotStyle.kt:427 `CloudPath.ellipse`; drawn AnnotationOverlay.kt:105 |
| 7.73 One shared identity | STILL MISSING | DONE | StampPicker5.kt:67 `StampTokens.shared` (name = comment author); identity Save also sets `authorName` (:258-260) |

#### §8 Forms
| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 8.28 Allow rich text (fill time) | STILL MISSING | DONE | InPageFormLayer.kt:364 B/I/U bar → `FormFillState.toggleRich` → `RichFields` /RV /DS + appearance (forms-2/3). Limit: whole-field style, no per-character runs |
| 8.69 Named actions at fill time | STILL MISSING | STILL MISSING | Now Find/Zoom/Fit/FullScreen/layouts (core/forms5/NamedActions.kt, FormActions.kt:168-178). Still "not supported": GoBack / GoForward / Close / Quit / SaveAs etc. (authoring list NavActions.kt:83 offers GoBack/GoForward) |
| 8.73 Form data XDP | STILL MISSING | DONE | FormData.kt:27 `DataFormat.XDP`, :164 `xdp`, :301 `parseXdp`; menus FormsScreen.kt:273, FormScriptTools.kt:418, designer :301 |
| 8.84 JS doc API | STILL MISSING | STILL MISSING | saveAs/closeDoc/addScript/addField/setLock/exportDataObject now real. Still: `scroll` stub (AcroJsLib.kt:239), `removeField` only hides (FormActions.kt:136), doc changes not applied to signed docs |

### 2. "Status updates after this check" rows in my areas (quick code confirmation)
| Source / row | Old status | Now | Evidence |
|---|---|---|---|
| §15 Link destination "Set this position" | DONE (claimed) | DONE | ui/edit5/LinkEditor.kt `currentView`, passed by ReaderLinks.kt |
| §16 Bookmark named destination from a list | DONE | DONE | `LocalDestNames` in NavUi.kt / BookmarksScreen.kt / LinkEditor.kt |
| §15 Link "convert linked page to PDF" | DONE | DONE | ui/nve/NveLinks.kt:91 `ToPdf.url` |
| §19 Read aloud next / prev / repeat block | DONE | DONE | ReaderOverlays.kt:134-136 → ReadAloudController.prevBlock/repeatBlock/nextBlock |
| OLD A1 Reflow tables | DONE | DONE | core/view5/ReflowTables.kt; ReflowReader.kt:191 `RBlock.Table` → `ReflowTable` |
| OLD A1 Reflow column order | DONE | DONE | core/view5/ReflowColumns.kt |
| §14d Reflow editing across pages | DONE | DONE | TextTools5.kt:516 `TSheet.REFLOW` → `ReflowSheet` → TextEdit5.loadFlow |
| §17 Reader thumbnails panel reorganise | DONE | DONE | ReaderPanels.kt:72 → ReaderThumbs.kt `inPlace` → `applyInPlace` → PdfOps.rotatePages/deletePages… |
| SELECTION_MENUS thumbnail menu | DONE | DONE | `SelCtx.THUMB` in SelMenu.kt / SelThumbs.kt, used by OrganizeScreen.kt + ReaderThumbs |
| §17 Thumbnail size | DONE | DONE | ReaderThumbs.kt `ThumbMoves.SIZES`, ThumbPanelPrefs |
| B §23 Conversion defaults per format | DONE | DONE | core/convert6/Cv6Defaults.kt; ConvertHubScreen `Cv6Defaults.load` |
| C §1 QAT position | DONE | DONE | Commands5.kt:113-114; ReaderScreen `V5PinnedBottomRow`; FcsSettings.kt:70 |
| 16b Batch convert from Files | DONE | DONE | FilesComponents.kt:62 `onConvert` → Cv6Batch |

### 3. gaps/DONE_view_edit.md open items
| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| §1/§9 Tab groups / parallel view | PARTIAL | STILL MISSING | = §1 row above |
| Zoom 6400% | range PARTIAL | STILL MISSING | = §1 row above |
| Pane: Layers / §22 Layers / §9 logical page numbers, initial view, full screen on open | OWNED ELSEWHERE | DONE | Layers e5_layers (Edit5Hub); `e5_page_labels` (Edit5Hub.kt:38,75); DocPropsScreen.kt:132 /PageMode incl. FullScreen; ReaderState.initialPageMode |
| §3 Permission enforcement | OWNED (pdf-3c) | DONE | core/security/ReaderRestrictions.kt + ReaderGuard checks |
| §9 Bookmarks panel search | OWNED (pdf-3c) | DONE | ReaderPanels.kt:95,213 search box |
| Bring to front: text blocks | NOT-FEASIBLE | NOT-FEASIBLE | moving a text operator breaks text-object state (DONE file) |
| E10 rotated / XObject text editing | PARTIAL | STILL MISSING | PageScanner.kt:155 still rejects mirrored/skewed; nested Form XObjects not editable |
| §1 Invoice print | NOT-FEASIBLE | NOT-FEASIBLE | Foxit plug-in |
| §1 Tutorial videos | NOT-FEASIBLE | NOT-FEASIBLE | no curated videos; guide PDFs instead |
| §1 QAT position | PARTIAL | DONE | see status table (fc-settings) |
| §1 Mouse-wheel tab switching | NOT DONE | STILL MISSING | no Scroll pointer handling in NveTabs.kt / ReaderTabs.kt |
| §1 Drag tab out of window | NOT-FEASIBLE | NOT-FEASIBLE | Compose tab cannot be dragged out |
| §3 Save to Drive as Docs/Sheets/Slides | NEEDS-KEYS | OWNER-ACTION | GoogleDrive.uploadConverted needs Drive OAuth client |
| §9 Text viewer "auto rotate" width | NOT-FEASIBLE | NOT-FEASIBLE | DONE file |
| §9 Magnifier fisheye | NOT-FEASIBLE | NOT-FEASIBLE | Android Magnifier has none |
| §9 Portfolio component viewing | PARTIAL | DONE | portfolio editor (MergePortfolioScreens.kt:142 → PortfolioEditorUi.kt): search across parts :171, preview pane PDF + images :239; any attachment opens in the in-app viewers (AttachmentsBrowser, open-all 2b97f51) |
| §9 Model tree / §11 3D | NOT-FEASIBLE | NOT-FEASIBLE | no 3D renderer |
| §9 Line weights view | NOT-FEASIBLE | NOT-FEASIBLE | PDFium-Android has no flag |
| §10 Read form fields: tab-through mode | PARTIAL | STILL MISSING | read aloud reads field values with the page (NveReadAloud fields switch); no field-by-field mode |
| §12 Search scope: selected index | PARTIAL | STILL MISSING | one app index only (no named / multiple indexes) |
| §12 Auto-searched layers / signatures | PARTIAL | STILL MISSING | PartKind (DocParts.kt:16) has no signature or layer-name part |
| §12 Search & Remove Text (word list) | NOT DONE | STILL MISSING | no code; workaround Replace-All / redaction |
| §12 Catalog index definition | PARTIAL | STILL MISSING | titles, XMP, custom props, tags now (FcsIndexPrefs). Missing: user-chosen include folders (IndexWorker.sources = docs dir + recents + device scan) |
| §12 Embedded index | NOT-FEASIBLE | NOT-FEASIBLE | proprietary format |
| §22 Articles: drag-resize a box | PARTIAL | STILL MISSING | NveArticlesUi.kt: drag creates new boxes only; no resize/move of a bead |
| §14c Spot healing texture synthesis | PARTIAL | STILL MISSING | ImageOps.kt:218 ring-average only |
| §14d Shading free-drag reference line | PARTIAL | STILL MISSING | shapes code unchanged since fa724fd (presets + radius) |
| §14d Reflow editing | PARTIAL | DONE | reflow-plus (see status table) |
| §39 Ctrl+8 / Ctrl+J / e-mail all tabs | NOT DONE | DONE | NveKeys.kt:115 HIDE_COMMENTS, :130 JS_CONSOLE → e5_doc_js, :129 EMAIL_TABS |

### 4. gaps/tabs-audit/FIXED_1_view_core.md and FIXED_3_edit.md
| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| V-01 signed form-fill save | OWNED (pdf-3c) | DONE | merged with fix/tab-fill-sign (facfb11); core/pdf/SignedSave.kt:64 `requireFormFillAllowed` |
| V-16 / V-19 one tool memory | OWNED (pdf-39) | DONE | merged fix/tab-comment (29bb393). Note: `ToolMemoryEffect` (View5Host.kt:131) is still defined but has no caller (dead code, not a gap) |
| E-12 limit: new random owner password when owner password not given | known limit | NOT-FEASIBLE | the original owner password cannot be re-used without knowing it; user is told |
| E-07 limit: old path-keyed linked text boxes not migrated | known limit | N/A | one-time legacy data |
All other V-/E- rows: FIXED per log; no open items.

### 5. gaps/OPEN_ALL_FILES.md (§6 limits; feature merged e0bcef4)
| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| DjVu, CHM, RAR/CBR, DWG, WOFF2 → info screen | not feasible | NOT-FEASIBLE | GPL/UnRAR licences or no permissive decoder (§5) |
| MOBI HUFF/CDIC or DRM | limit | NOT-FEASIBLE | DRM / no decoder |
| AZW3/KF8 images not placed | limit | STILL MISSING | MobiReader: reflowed text only |
| XPS gradients (first stop), ArcSegment as line, visual brushes ignored | limit | STILL MISSING | core/openall/XpsReader.kt:169, :205 |
| DXF ASCII only, no hatches/dimensions/3-D | limit | STILL MISSING | core/openall/DxfReader.kt |
| Old PPT text only, old DOC text only, XLS without number formats | limit | STILL MISSING | readers unchanged |
| Numbers/Pages/Keynote embedded preview only | limit | NOT-FEASIBLE | proprietary IWA format, no reader |
| HEIF < Android 9, AVIF < 12, RAW device-dependent | limit | NOT-FEASIBLE | platform decoders |
| pathPattern for content:// URIs | limit | NOT-FEASIBLE | Android intent-filter limitation |

### 6. gaps/SELECTION_MENUS.md
| Source / row | Old status | Now | Evidence |
|---|---|---|---|
| §6 thumbnail menu (last open item) | DONE (thumbs-panel) | DONE | see status table |
| §7.1–7.5 field / annotation / page-image menus, place at point, no gesture lock | done | DONE | field long-press InPageFormLayer.kt:181 → SelHooks.longPressField → SelMenuLayer.kt:101 → `SelFieldMenu.run` |
No open items left; only "needs device test".

### Spot-checks (branches merged after fa724fd)
1. **Organize per-page Redact** (fix/pages-a11y) — OK: OrganizeScreen.kt:303 → `redactAsk` → confirm :347 → OrganizeState.redactPage :226 → PageOrganize.redactPages :432.
2. **Organize per-page OCR** (fix/pages-a11y) — OK: OrganizeScreen.kt:305 → OrganizeState.ocrPage :355 → `runOcrToFile(…OcrOptions(listOf(page)…))` inside `apply`.
3. **Floating note 7.10** (fix/pdf39-forms-2) — OK: AnnotEditUi.kt:93 A_FLOAT_NOTE → `cx.floating` → PageView.kt:433 FloatingNoteWindow → FloatingNote.kt:78 `cx.saveFloating`.
4. **Cloudy oval 7.27** (fix/pdf39-comments-forms) — OK: CommentExtrasUi.kt:91-93 → `h.rectCloud` → CommentPrefs.kt:68 → AnnotStyle.kt:427 `CloudPath.ellipse`.
5. **XDP export/import 8.73** (fix/pdf39-comments-forms) — OK: FormsScreen.kt:273 data menu → `DataFormat.XDP` → FormData.kt:71 `xdp` / :216 `parseXdp`.
6. **Fill-time named actions 8.69** (fix/pdf39-forms-2) — OK (partial scope): FormActions.kt:168-178 → `NamedActions` (Find, zoom, fit…); GoBack/GoForward not mapped.
7. **JS saveAs / closeDoc 8.84** (fix/pdf39-forms-3) — OK: FormActions.kt:139/145 → ReaderState.scriptSaveAs / scriptClose (:189-190) → ReaderScreen.kt:201-202 (SAF save-as launcher / requestLeave).
8. **Reader thumbnails reorganise** (fix/thumbs-panel) — OK: ReaderPanels.kt:72 → ReaderThumbs `ReaderThumbnailsPanel` → `inPlace` :118 `state.applyInPlace` → PdfOps.rotatePages :126 / deletePages :129.
No FAIL. (Also confirmed: 7.73 shared identity StampPicker5.kt:258-260; read-aloud blocks ReaderOverlays.kt:134-136.)

### STILL MISSING

#### High
- (none in this slice)

#### Med
- §5 Create PDF from legacy .ppt: only a text-only path through the open-all viewer; hub excludes PPT. (pages/convert)
- §5 Image→PDF custom page size: presets only. (pages/convert)
- §6 PDF→Word "precisely laid out" text-box mode. (pages/convert)
- §6 Advanced text recovery switch + recovery language (repair is automatic only). (pages/convert)

#### Low
- §1 Tab groups. (reader/view)
- §1 Zoom to 6400% (MAX_ZOOM 32× fit width). (reader/view)
- DONE_view_edit Mouse-wheel tab switching over the tab strip. (reader/view)
- DONE_view_edit Read form fields field-by-field (tab-through) mode. (reader/view)
- §4 Split: per-operation destination folder. (pages/convert)
- §4 Resize: reset to original size after save (undo only). (pages/convert)
- §5 Output folder choice in Combine/Create. (pages/convert)
- §5 Named quality presets. (pages/convert)
- §5 Font embedding options. (pages/convert)
- §5 Image codec / downsample options. (pages/convert)
- §5 Page size for text sources. (pages/convert)
- §5 Excel: bookmark per sheet / fit option. (pages/convert)
- §5 PowerPoint: bookmark per slide, notes, frames. (pages/convert)
- §5 HTML: header/footer text. (pages/convert)
- §5 Printer (T5PrintService): target/append, PDF version/PDF-A, quality/grayscale, properties, security, auto-open — 6 rows. (pages/convert)
- §6 Word: character spacing; image anchoring; table threshold; header/footer toggle — 4 rows. (pages/convert)
- §6 Excel: split multi-line rows; reduce merged cells. (pages/convert)
- §6 Text: line width/paragraph mode; discard headers/footers. (pages/convert)
- §6 Extract images: output format choice. (pages/convert)
- §6 Batch: output folder; per-file page range. (pages/convert)
- 8.69 Fill-time named actions GoBack / GoForward / Close / Quit / SaveAs. (comments/forms)
- 8.84 JS `scroll` stub; `removeField` only hides; script doc changes skipped on signed docs. (comments/forms)
- DONE_view_edit E10 nested Form XObject / mirrored / skewed text editing. (edit)
- DONE_view_edit Articles: resize/move an existing box. (edit)
- DONE_view_edit Spot-healing texture synthesis. (edit)
- DONE_view_edit Shading free-drag reference line. (edit)
- DONE_view_edit Search & Remove Text (word list). (files)
- DONE_view_edit Multiple / selected indexes (one app index). (files)
- DONE_view_edit Index: signature text and layer names not indexed. (files)
- DONE_view_edit Catalog: user-chosen include folders. (files)
- OPEN_ALL AZW3/KF8 images not placed. (files)
- OPEN_ALL XPS gradients / arcs / visual brushes. (files)
- OPEN_ALL DXF hatches / dimensions / binary DXF. (files)
- OPEN_ALL old PPT/DOC text only, XLS no number formats. (files)

### Counts
| Source | Rows | DONE | STILL MISSING | NOT-FEASIBLE | OWNER-ACTION | N/A |
|---|---|---|---|---|---|---|
| FINAL_CHECK §1–8 (STILL MISSING rows) | 44 | 9 | 35 | 0 | 0 | 0 |
| Status updates (my areas) | 13 | 13 | 0 | 0 | 0 | 0 |
| DONE_view_edit open items | 29 | 7 | 12 | 9 | 1 | 0 |
| FIXED_1 / FIXED_3 | 4 | 2 | 0 | 1 | 0 | 1 |
| OPEN_ALL_FILES limits | 9 | 0 | 4 | 5 | 0 | 0 |
| SELECTION_MENUS | 2 | 2 | 0 | 0 | 0 | 0 |
| **Total** | **101** | **33** | **51** | **15** | **1** | **1** |

(DONE_view_edit STILL MISSING 12 includes the 2 duplicates of §1 tab groups / zoom; unique STILL MISSING rows = 49: 35 FINAL_CHECK + 10 DONE_view_edit + 4 OPEN_ALL.)

---

## Final check 2, part B: FINAL_CHECK.md lines 487-1034 (§9-§24 + fcmp_01-08) and DONE files (protect/sign, nav, FIXED_4, NITRO_TOOLS_LOG)

Checked on `D:\pdf\final-check2-wt` (origin/main b719484). Read-only. Paths are under `app/src/main/java/com/smartdocs/editor/`.
Rows in the range whose first-check "Now" was DONE / NOT-FEASIBLE are not repeated. Rows listed: every STILL MISSING / partial row, the OWNER-ACTION rows of the range, and every open item of the assigned DONE / FIXED / LOG files.

Important change since the first check: **pages-convert stage G is merged** (6476d36, after fa724fd; the first check said it was not). It added PDF/A levels, preflight profiles, standards card, claim removal and save-as-version (`core/tools5/Standards.kt`, `ui/tools5/PdfAScreen.kt`). fix/pages-a11y (846774f) added table auto-tagging and the PDF/UA-1 machine-check profile.

### Part B: §9-§24 (first-check STILL MISSING / OWNER rows)

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| §19 Auto Tag (tables) | STILL MISSING (part) | DONE | `AccessAutoTag.tables()` :42, Table/TR/TH/TD written :211-220; Tools → `accessibility` → `AccessibilityScreen.kt:310` |
| §19 Read aloud next / previous / repeat block | STILL MISSING | DONE | TTS bar `ReaderOverlays.kt:134-142` → `ReadAloudController.prevBlock/repeatBlock/nextBlock/…Of` (`core/tts/ReadAloudController.kt:109-128`) |
| §20 Convert to PDF/A-1b | STILL MISSING | DONE | Tools → `pdfa` → `PdfAScreen.kt:223` level chips (`PdfALevel` A1A…A3U, `Standards.kt:16`) → `PdfA.convert` writes `part/conformance` (`PdfA.kt:231-232`), 1.4 for level 1 (:240) |
| §20 PDF/A-1a / 2a | STILL MISSING | DONE | same chips; `needsTags` runs `AccessAutoTag` (`PdfA.kt:219`) |
| §20 PDF/A-2u | STILL MISSING | DONE | same chips (`A2U`, `needsUnicode`) |
| §20 PDF/A from scan / print / create | STILL MISSING (part) | STILL MISSING (part) | Scan (`ScanStudioScreen.kt:176`) and Combine tab (`CombinePlusUi.kt:204`, PDF/A-3b) now have it. Still no direct PDF/A switch in the To-PDF "Each file" tab (`ToPdfPlusScreen.kt:97-125`) or the print service (only through a printer recipe with a PDFA step) |
| §20 PDF version selection | STILL MISSING | DONE | `PdfAScreen.kt:198-214` standards card "save as PDF version" 1.4-1.7; PDF/A-1 forces 1.4 |
| §23 Conversion defaults in Settings | STILL MISSING | DONE | `Cv6OptionsSheet.kt:166` → `Cv6Defaults.save`; `ConvertHubScreen.kt:123` loads; Settings rows `FcsConvDefaultsRows` (`NpfSettingsScreen.kt:329`) |
| §24 Multi-user packs / volume licensing | STILL MISSING | STILL MISSING | no seat management anywhere (`grep seat` only hits portfolio/doc props); `LicenseClient` also needs owner `LICENSE_URL` |
| §24 Help: Community forum | OWNER-ACTION | OWNER-ACTION | `npf_url_community` still empty (`res/values/strings_nav_prefs.xml:188`) |
| §24 Help: Labs | OWNER-ACTION | OWNER-ACTION | `npf_url_labs` still empty (:189) |

### fcmp_01-08

#### §1 Application shell

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| QAT reset / position | STILL MISSING | DONE | More preferences → General chips (`ui/settings/FcsSettings.kt:70`) → `V5Commands.qatPosition` (`Commands5.kt:113`) → `ReaderScreen.kt:411` `V5PinnedBottomRow` |
| Pin tab / Ctrl+Tab / mouse-wheel tab switching | STILL MISSING (part) | STILL MISSING (part) | No scroll-wheel handler on the tab strip (only `PointerEventType.Scroll` uses: Ctrl+wheel zoom in `NveOverlays.kt:407`, page flip) |

#### §3 Open, Save & Close

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Save to Google Drive as Docs / Sheets / Slides | OWNER-ACTION | OWNER-ACTION | `GoogleDrive.uploadConverted` needs the owner's Drive OAuth client |
| Recent list size (duplicate setting) | DONE (duplicate) | DONE | merged into `NpfPrefs.recentLimit` (fix/fc-settings); no separate status |

#### §4 Create PDF, Combine, Recognize Text

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Multiple files: keep original names and locations | STILL MISSING | STILL MISSING | outputs still go to `app.documents.docsDir` (`ToPdfPlusScreen.kt:111`), not next to sources |
| Multiple files (separate PDFs): add folder, sort | STILL MISSING | STILL MISSING | "Each file" tab still only `FileListEditor` (`ToPdfPlusScreen.kt:100`; `T5Common.kt:178` has no folder / sort) |
| Creation presets (Standard / Print / Smallest / PDF/A / UA / USPTO) | STILL MISSING | STILL MISSING | no named creation presets (only PDF/A switch in Combine / Scan) |
| Bookmarks at conversion (Excel sheets, PPT titles) | STILL MISSING (part) | STILL MISSING (part) | `CombinePlus` headings come from `OutlineTools.detectHeadings` on the output (:94); no sheet-name / slide-title bookmarks; `ToPdf.combine` gives one bookmark per file |
| Tagged PDF at creation | STILL MISSING | STILL MISSING | `AccessAutoTag.tag` is only called by `PdfA.kt:219` and `AccessibilityScreen`; no tag option on To-PDF / WebView output |
| Web: conversion levels / whole site | STILL MISSING | STILL MISSING | `WebPdfWriter.Setup` (android/print/WebPdfWriter.kt:66-75) has no depth / crawl |
| Web: single long page / bookmarks / tags / header-footer | STILL MISSING (part) | STILL MISSING (part) | `WebCss` only images / backgrounds / underline / strip links |
| Web: media Screen/Print; scaling | STILL MISSING | STILL MISSING | no media or scale field in `Setup` |
| Open Weblink as New Document | STILL MISSING | DONE | link preview "Open as PDF" `ui/nve/NveLinks.kt:84-93` → `ToPdf.url` → reader route |
| Form: scan a file into a form | STILL MISSING | STILL MISSING | `core/formsx/FieldRecognition.kt` has no OCR / raster path |
| From Template (library, search, categories) | STILL MISSING | STILL MISSING | `ui/templates/TemplatesScreen.kt` has no search / category / online library |
| Excel comments, PPT notes, transitions, hidden slides, ranges | STILL MISSING | STILL MISSING | nothing in the conversion path (`PptDoc` notes handling is the office editor only) |
| Excel fit options | STILL MISSING | STILL MISSING | no fit-to-page / width option |
| Word comments, footnote links | STILL MISSING (part) | STILL MISSING (part) | `DocxReader` numbers footnotes as text; no links, no comment → note |
| Mail merge: record range, e-mail each PDF | STILL MISSING | STILL MISSING | `MailMergeScreen` (`MergePortfolioScreens.kt:49-111`) runs all rows; no range / e-mail |
| Printer properties: PDF version | STILL MISSING (part) | STILL MISSING (part) | printer recipe (`ShareAndPrint.kt:141-146`) has no version step (`StepType`, `Recipes.kt:21-44`) |
| Combine: portfolio input unpacked | STILL MISSING | STILL MISSING | no portfolio / Collection handling in `CombinePlus` |
| Combine: thumbnail view | STILL MISSING | STILL MISSING | list only |
| Combine: expand files into pages, move pages across files | STILL MISSING | STILL MISSING | page range per file only |
| Combine: output folder / overwrite / don't open | STILL MISSING | STILL MISSING | output to docsDir (`CombinePlusUi.kt:195`); no overwrite / no-open choice |
| Combine: save as portfolio | STILL MISSING | STILL MISSING | separate Portfolio tool only |
| Combine: e-mail attachment handling | STILL MISSING | STILL MISSING | `SourceReaders.kt:207-247` always lists / embeds attachments; no choice |
| Recognize line segments as path objects | STILL MISSING | STILL MISSING | nothing in `core/ocr` |
| Watched Folder | STILL MISSING | STILL MISSING | only FileObservers: `NpfSaveFolder`, `core/sync/AppFolderWatch.kt`, `SyncLive` (sync, not tools) |

#### §5 Scan & OCR

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Paper sizes (A0-A10, B, C, JIS…) | STILL MISSING (part) | STILL MISSING (part) | `ScanPaper { FIT, A4, LETTER, LEGAL, A3, A5, B5, CARD }` (`ScanBuilder.kt:56`) |

#### §6 Export PDF

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| DOCX: include comments | STILL MISSING | STILL MISSING | no option in `Opt` (`Cv6Catalog.kt:12`) |
| XLSX: decimal / thousands separators | STILL MISSING | STILL MISSING | automatic only |
| PNG interlace / filter, ICC | STILL MISSING (part) | STILL MISSING (part) | only `Opt.BW` monochrome |
| Image settings: open the destination folder | STILL MISSING | NOT-FEASIBLE | DONE_pdf39 #157: outputs are app-private; Android has no generic "open folder" intent for them (results sheet opens / shares files) |
| Export All Images: format / colour / resolution | STILL MISSING | STILL MISSING | `ui/convert6/ExtractImagesScreen.kt` keeps the native format |
| Export Selection As Word / Excel / PowerPoint | STILL MISSING | STILL MISSING | selection export is `.txt` only (`ui/selmenu/SelMenuLayer.kt:207-217`) |

#### §7 Portfolio & Binder

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Read / print across files | STILL MISSING (part) | STILL MISSING (part) | search done; no print / read mode in `PortfolioEditorUi.kt` |
| Create: add from web / scanner / clipboard | STILL MISSING (part) | STILL MISSING (part) | folder only |
| Edit a component and save it back | STILL MISSING | STILL MISSING | parts open as extracted copies |
| Binder: TOC template with field codes | STILL MISSING | STILL MISSING | `BinderProject` (`CombinePlus.kt:188`) has only name / opts / entries |
| Binder: custom metadata fields | STILL MISSING | STILL MISSING | same |
| Binder: output options (open after, show in folder, folder) | STILL MISSING | STILL MISSING | output to docsDir |

#### §8 Compliance

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Preflight against a profile | STILL MISSING | DONE | `PreflightProfile` 10 profiles (`Standards.kt:32`) chips `PdfAScreen.kt:145-153` → `Standards.preflight` :178 |
| Preflight page range | STILL MISSING | DONE | range field `PdfAScreen.kt:155` → `PageSet.parse` |
| Profiles tab (libraries, favourites, search) | STILL MISSING | STILL MISSING (part) | favourites done (heart, prefs "pc_preflight"); no libraries / search / single-fixups view |
| Print-production checks | STILL MISSING | STILL MISSING (part) | `printFacts` covers hairlines, overprint, spot colours, ppi, invisible text, Courier; no plates / CxF / rich black / ICC-Lab |
| Fixup: convert fonts to outlines | STILL MISSING | STILL MISSING | DONE_pdf39 says NF (pages rasterised instead), but edit mode already outlines a text block, so it is buildable |
| Create Report (PDF / XML / TXT / HTML, highlights) | STILL MISSING (part) | STILL MISSING (part) | HTML / TXT / XML share (`PdfAScreen.kt:160-174` → `Standards.reportText`); no PDF report with highlights |
| Preflight as Action Wizard step | STILL MISSING | STILL MISSING | `StepType` has PDFA (convert) and ACCESS_CHECK; no PDF/A check-only step, no success / error branch |
| Convert to PDF/A-1a / 1b | STILL MISSING | DONE | level chips (see §20) |
| PDF/A-2a / 2u | STILL MISSING | DONE | same |
| PDF/A-3 | STILL MISSING | DONE | `A3A/A3B/A3U`, `Standards.associateAttachments` (`PdfA.kt:216`) |
| PDF/A-4 | STILL MISSING | STILL MISSING | not in `PdfALevel`; DONE_pdf39: PdfBox 2.0 has no PDF 2.0 output (could be NF) |
| Outline non-Unicode text (2u) | STILL MISSING | STILL MISSING (part) | such pages are rasterised, not outlined |
| Remove PDF/A information | STILL MISSING | DONE | standards card "remove" `PdfAScreen.kt:181-192` → `Standards.removeClaims` :76 |
| ZUGFeRD verification | STILL MISSING | DONE | profile `ZUGFERD` → `Standards.zugferd` :306 |
| Verify conformance of a claimed file | STILL MISSING | STILL MISSING | still approximate preflight (no veraPDF-grade validator) |
| PDF/E convert / verify / remove | STILL MISSING | STILL MISSING (part) | verify (`PDFE_1`, approximate) and remove done; no convert |
| PDF/X convert | STILL MISSING | NOT-FEASIBLE | no CMYK colour management / output profiles (DONE_pdf39 #199) |
| PDF/X verify / remove | STILL MISSING | STILL MISSING (part) | `PDFX_4` approximate check + remove only; other X variants missing |
| PDF/UA verify (PDF/UA-1) | STILL MISSING | DONE | profile `PDFUA_1` → `Standards.pdfUa` :238 (automatable Matterhorn checkpoints, numbered; human checkpoints stay manual) |
| PDF/UA fix tagging | STILL MISSING (tables) | DONE | table auto-tag (see §19) + `TagsEditor` |
| Mark as PDF/UA / remove PDF/UA info | STILL MISSING (remove) | DONE | remove `pdfuaid` in the standards card (`PdfAScreen.kt:178`) |
| PDF/VT verify | STILL MISSING | STILL MISSING | claim shown only (`Claims.pdfvt`), no VT checks, no remove button for VT (`PdfAScreen.kt:186`) |
| PDF version compatibility checks | STILL MISSING | DONE | profile `VERSION` → `Standards.minVersion` :103 |
| Standards wizard | STILL MISSING | DONE | `PdfAScreen` level → UA → options → convert (:215-245) |
| Save As PDF/A / E / X | STILL MISSING | STILL MISSING (part) | PDF/A via Save As (`SaveAsSheet.kt:60`, default level) and the tool; no PDF/E / PDF/X output |
| Standard output at creation | STILL MISSING | STILL MISSING (part) | PDF/A at Scan / Combine; no PDF/E / X, none in Each-file / print service |
| Standards panel: OutputIntent | STILL MISSING | STILL MISSING | `NpfDocPanels` / standards card show no OutputIntent (only used inside the X-4 check, `Standards.kt:193`) |

### DONE / FIXED / LOG files: open items

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| DONE_pdf3c_nav: Leave a review | PARTIAL (local only) | OWNER-ACTION (code DONE) | `ui/cloud/ReviewScreen.kt:283` → `CollabClient.leave` (:82) → worker `POST /reviews/:id/leave` (`ai-proxy/collab/worker.js:88,149`); owner must redeploy the collab worker |
| DONE_pdf3c_nav: Print the current view | PARTIAL | STILL MISSING | no view-area option in `core/view5/PrintSettings.kt` / `PrintSheet5`; still Snapshot → Print |
| DONE_pdf3c_nav: A6 reader honours /Direction, OpenAction, PageLayout (owner pdf-39) | OWNER (other session) | DONE | `ReaderState.kt:411-418` (`InitialViewMap.rtl / viewMode`, OpenAction page / zoom), `/PageMode` → `ReaderScreen.kt:72-78`; `PageView.kt:64-65` uses `docRtl` |
| DONE_pdf3c_nav / protect_sign: forum / Labs URLs | OWNER | OWNER-ACTION | same rows as §24 (not counted twice) |
| DONE_pdf3c_protect_sign: Signatures panel in the reader (owner pdf-ad) | OWNER (other session) | DONE | View chip / menu / command `nv_signatures` (`ui/nve/NveHost.kt:115,143,198`) → `NvePanes.kt:119` `SignaturesPane` |
| DONE_pdf3c_protect_sign: EU trusted list XAdES signature not verified | caveat | NOT-FEASIBLE | `core/sign/TrustLists.kt:106` (no XML-DSig on Android; same reason as fc-sign 26.68 XAdES) |
| DONE_pdf3c_protect_sign: redeploy `ai-proxy/esign/worker.js` (forms, branding) | OWNER | OWNER-ACTION | unchanged |
| NITRO_TOOLS_LOG: page labels shown in the reader (moved to pages-convert) | moved | DONE | `ReaderScreen.kt:70,449,544` (`rememberPageLabels` → `PageIndicator`, `GoToPageDialog`), also `ReaderThumbs.kt:266` |
| FIXED_4 D21 (stacked banners) | partial → follow-up | DONE | `ReaderScreen.kt:455` `SignedBarSlot` in the banner column (`SigFieldActions.kt:272`) |
| FIXED_4 D22 (streamed validation) | partial → follow-up | DONE | `RevisionDiff.revisionEnds(File)` (`core/sign/RevisionDiff.kt:68`), reuse in `DigitalSignature` |
| FIXED_4 D25 (add-field chip, Digital IDs tab) | partial → follow-up | DONE | Sign toolbar chip `ReaderToolbars.kt:325` → `SigFieldRequest(addField = true)` → `DigitalSignScreen.kt:223` opens placement; `ReaderScreen.kt:276` `openTab = 2` |
| All four files: "needs device test" lists | open | N/A | not checkable in code; still untested on a device |

All other rows of these four files are done / present / NF with reasons (AES-GCM, RMS/AIP, Notarius, JPEG 2000, lossy JBIG2, Fast Web View, drag-drop, overprint simulation, printer append, pre-review password).

### Spot-checks

1. **OK** stage G PDF/A levels: Tools `pdfa` (`ToolsScreen.kt:87`, `ToolScreens.kt:64`) → `PdfAScreen.kt:223` `ChoiceChips(PdfALevel.entries)` → convert button :233 `PdfAOptions(level = level, uaPart)` → `PdfA.convert` → `XmpWriter.packet(part = opt.level.part, conformance = opt.level.conformance)` (`PdfA.kt:231-232`).
2. **OK** stage G claim removal: standards card `PdfAScreen.kt:181-192` "remove" → `Standards.removeClaims(d, setOf(prefix))` (`Standards.kt:76`) → new file recorded with `ToolOutputs.record`.
3. **OK** fc-settings conversion defaults: options sheet "Save as default" `Cv6OptionsSheet.kt:166` → `Cv6Defaults.save`; hub `ConvertHubScreen.kt:123` → `Cv6Defaults.load(…, defaultsFor(c))`; Settings `NpfSettingsScreen.kt:329` → `FcsConvDefaultsRows` (`FcsSettings.kt:45-49`, `savedIds/savedScreens`).
4. **OK** fc-settings QAT position: `FcsSettings.kt:70` chips → `V5Commands.qatPosition/setQatPosition` (`Commands5.kt:113`) → `ReaderScreen.kt:411` `V5PinnedBottomRow(state)`.
5. **OK** tts-blocks: TTS bar `ReaderOverlays.kt:134-136` buttons + jump menu :141-142 → `ReadAloudController.prevBlock/repeatBlock/nextBlock/nextBlockOf` (`core/tts/ReadAloudController.kt:109-128`) → `core/tts/ReadBlocks`.
6. **OK** fc-sign link "Open as PDF": tap link → `NveLinkPreview` (`NveHost.kt:70`) → button `NveLinks.kt:84` → `ToPdf.url(ctx, url, f)` :91 → `nav.navigate(Routes.reader(...))` :93.
7. **OK** sign-partials D25: Sign toolbar chip `ReaderToolbars.kt:325` sets `SigFieldRequest.pending(addField = true)` → `navigateTool("digital_sign")` → `DigitalSignScreen.kt:97` `SignTab(addField)` → :223 opens placement and adds the field after placing.
8. **OK** pages-a11y table auto-tag: `AccessibilityScreen.kt:310` → `AccessAutoTag.tag(d, lang, retag)` → `tables(blocks)` :42 → `elem("Table")`, `TR`, `TH`/`TD` cells own their text blocks (:211-220).
9. **OK** (extra) fc-sync Remove me: `ReviewScreen.kt:283` → `CollabClient.leave` → `POST /reviews/:id/leave` (`CollabClient.kt:84`; worker `leaveReview` :149). Needs the worker redeployed.

No spot-check failed. One correction to the first check: it said stage G was not merged; it is (6476d36), so most §8 / §20 rows moved.

### STILL MISSING

**High:** none.

**Med**
1. fcmp §4 Tagged PDF at creation: no tag option when creating PDFs (only afterwards). Area: pages/convert.
2. fcmp §4 Multiple files (Each file tab): no folder input / sort. Area: pages/convert.
3. fcmp §4 Combine: output folder / overwrite / don't open after merging. Area: pages/convert.
4. fcmp §6 Export Selection As Word / Excel / PowerPoint (selection export is .txt only). Area: pages/convert.
5. fcmp §6 DOCX export: include comments. Area: pages/convert.
6. fcmp §4 Watched Folder (no monitored folder for tools). Area: tools/OCR (batch).

**Low**
7. §20 PDF/A switch missing in the To-PDF "Each file" tab and the print service (recipe only). Area: pages/convert.
8. §24 Volume licensing / seat management (also owner `LICENSE_URL`). Area: AI/office (billing).
9. fcmp §1 Mouse-wheel tab switching over the tab strip. Area: reader/view.
10. fcmp §4 Keep original names and locations for multi-file output. Area: pages/convert.
11. fcmp §4 Creation presets (Standard / Print / Smallest / PDF/A / UA / USPTO). Area: pages/convert.
12. fcmp §4 Bookmarks from Excel sheet names / PPT slide titles. Area: pages/convert.
13. fcmp §4 Web: conversion levels / whole site. Area: pages/convert.
14. fcmp §4 Web: single long page, bookmarks, tags, header-footer. Area: pages/convert.
15. fcmp §4 Web: media Screen / Print, scaling. Area: pages/convert.
16. fcmp §4 Form: scan a file into a form (no OCR path in FieldRecognition). Area: comments/forms.
17. fcmp §4 From Template: search, categories, online library. Area: pages/convert.
18. fcmp §4 Excel comments, PPT notes / transitions / hidden slides / ranges. Area: pages/convert.
19. fcmp §4 Excel fit options. Area: pages/convert.
20. fcmp §4 Word comments → notes, footnote / endnote links. Area: pages/convert.
21. fcmp §4 Mail merge record range / e-mail each PDF. Area: pages/convert.
22. fcmp §4 Printer: PDF version choice. Area: pages/convert.
23. fcmp §4 Combine: portfolio input unpacked. Area: pages/convert.
24. fcmp §4 Combine: thumbnail view. Area: pages/convert.
25. fcmp §4 Combine: expand into pages, move pages across files. Area: pages/convert.
26. fcmp §4 Combine: save as portfolio. Area: pages/convert.
27. fcmp §4 Combine: e-mail attachment handling choice. Area: pages/convert.
28. fcmp §4 OCR: line segments as path objects. Area: tools/OCR.
29. fcmp §5 Scan paper sizes A0-A10, B, C, JIS. Area: tools/OCR (scan).
30. fcmp §6 XLSX decimal / thousands separators. Area: pages/convert.
31. fcmp §6 PNG interlace / filter, ICC embedding. Area: pages/convert.
32. fcmp §6 Export All Images: format / colour / resolution. Area: pages/convert.
33. fcmp §7 Portfolio: print / read mode across parts. Area: pages/convert.
34. fcmp §7 Portfolio create: add from web / scanner / clipboard. Area: pages/convert.
35. fcmp §7 Portfolio: edit a part and save it back. Area: pages/convert.
36. fcmp §7 Binder: TOC template with field codes. Area: pages/convert.
37. fcmp §7 Binder: custom metadata fields. Area: pages/convert.
38. fcmp §7 Binder: output options (open after, show in folder). Area: pages/convert.
39. fcmp §8 Profiles: libraries, search, single-fixups view. Area: pages/convert.
40. fcmp §8 Print-production checks: plates, CxF, rich black, ICC/Lab. Area: pages/convert.
41. fcmp §8 Fixup: convert fonts to outlines (DONE file says NF; edit mode proves it buildable). Area: pages/convert.
42. fcmp §8 Preflight report as PDF with highlights. Area: pages/convert.
43. fcmp §8 Preflight as a check-only recipe step with success / error branch. Area: tools/OCR (batch).
44. fcmp §8 PDF/A-4 (PdfBox 2.0 has no PDF 2.0 output; could become NF). Area: pages/convert.
45. fcmp §8 Outline non-Unicode text (rasterised instead). Area: pages/convert.
46. fcmp §8 Formal conformance validation of claimed files (approximate preflight). Area: pages/convert.
47. fcmp §8 PDF/E convert. Area: pages/convert.
48. fcmp §8 PDF/X verify for variants other than X-4. Area: pages/convert.
49. fcmp §8 PDF/VT verify (+ remove VT claim). Area: pages/convert.
50. fcmp §8 Save As PDF/E / PDF/X. Area: pages/convert.
51. fcmp §8 PDF/E / PDF/X output at creation. Area: pages/convert.
52. fcmp §8 Standards panel: OutputIntent profile. Area: pages/convert.
53. DONE_pdf3c_nav Print the current view (Snapshot → Print only). Area: reader/view.

### Counts (89 rows)

| Status | Count |
|---|---|
| DONE | 27 |
| STILL MISSING | 53 |
| NOT-FEASIBLE | 3 (image-export open folder; PDF/X convert; EU trust list XAdES check) |
| OWNER-ACTION | 5 (forum URL, Labs URL, Google Drive Docs export keys, collab worker redeploy for Leave review, e-sign worker redeploy) |
| N/A | 1 (device-test lists) |

---

## Part C: FINAL_CHECK lines 1035-2010 (fcmp_09-13_22, fcmp_14-21_23, Foxit §24-27) and the comments/forms DONE and LOG files

Checked against: `D:\pdf\final-check2-wt`, origin/main b719484. The work was read-only.
Code paths are under `app/src/main/java/com/smartdocs/editor/`.
Scope: only rows whose "Now" was STILL MISSING or PARTIAL (mixed cells included), plus the matching "Status updates after this check" rows.

### A. FINAL_CHECK fcmp_09-13_22

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| §9 Parallel view (tab groups) | STILL MISSING | STILL MISSING | No tab-group code (`tabGroup` has no hits). Split view, quad split and a second window remain the only options |
| §9 Toggle reading ruler: vertical, opacity, reset | STILL MISSING | STILL MISSING | `ui/view5/View5Host.kt:211-215` still offers only on/off, dim outside, band height and colour. `PageOverlays5.ReadingRuler` draws a horizontal band only |
| §10 Read form fields (Tab-through mode) | STILL MISSING | STILL MISSING | Field values are read along with the page. There is no field-by-field Tab mode in TTS |
| §12 Auto-searched layers / signatures | STILL MISSING | STILL MISSING | `core/search/DocParts.kt:16` `PartKind` = TEXT, COMMENTS, BOOKMARK, FIELD, ATTACHMENT, METADATA. Neither layer (OCG) content nor signature text is indexed |
| §12 Search & Remove Text (word list) | STILL MISSING | STILL MISSING | No tool for it. Workarounds: Replace All with an empty value, or redaction |
| §12 Catalog index definition | STILL MISSING | STILL MISSING | fc-settings added custom properties, XMP and tags (`FcsIndexPrefsUi`), and NveIndexSheet has stop words, numbers and excluded folders. Still missing: an index title and include folders |
| §22 Modify articles: move / resize a box | STILL MISSING | STILL MISSING | `ui/nve/articles/NveArticlesUi.kt` has delete, combine, renumber and bead reorder. A box cannot be dragged or resized (`NveArticles` has no rect-update call) |

### B. FINAL_CHECK fcmp_14-21_23

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| §14d Reflow Editing | STILL MISSING | DONE | Status update (fix/reflow-plus) confirmed. Edit mode text toolbar button `nt_reflow` (`ui/edit/TextTools5.kt:467`) → `ReflowSheet` (:761, page range from / to) → `TextEdit5.loadFlow` / `reflowRange` → `core/edit/ReflowFlow.kt` |
| §15 Linked text menu: convert the linked page to PDF | STILL MISSING | DONE | Status update (fix/fc-sign) confirmed. Link sheet button `fsg_link_as_pdf` (`ui/nve/NveLinks.kt:83-96`) → `ToPdf.url` → opens the result in the reader |
| §15 Destination: "Set this position" | STILL MISSING | DONE | `LinkEditor.kt:86-87` "use current view" button; `ReaderLinks.kt:47,69,95` passes `currentView = { currentPage to zoom }` |
| §15 Destination inside an attachment (GoToE) | STILL MISSING | STILL MISSING | Only `PortfolioOps.kt:140` (portfolio cover) writes GoToE. The link and bookmark action editors have no GoToE kind |
| §15 Named destination chosen from a list | STILL MISSING | DONE | `NavUi.kt:199` `LocalDestNames` chips (:224), provided by `LinkEditor.kt:109` and `BookmarksScreen.kt:222` |
| §15 Recently used URLs drop-down | STILL MISSING | STILL MISSING | Not found (no URL history) |
| §15 Append All Web Links on the page | STILL MISSING | STILL MISSING | Not found |
| §15 View Web Links: download / convert | STILL MISSING | STILL MISSING | The list is display-only. "Open as PDF" exists only in the single-link sheet |
| §15 Actions: play media / sound | STILL MISSING | STILL MISSING | The action editors have no Rendition or Sound action kind |
| §16 Default zoom for new bookmarks | STILL MISSING | STILL MISSING | Only `AutoBmSettings.view` exists, which applies to auto-created bookmarks. There is no default for manual bookmarks |
| §16 New bookmarks from structure (tags) | STILL MISSING | STILL MISSING | `AccessFix.bookmarksFromHeadings` (`core/tools5/AccessFix.kt:64`) uses layout headings, not the structure tree |
| §16 Set a bookmark's destination to the current view | STILL MISSING | STILL MISSING | `currentView` is wired only into the link editor. `BookmarksScreen` (Edit5) has page and zoom fields only |
| §16 Named destination (Browse) | STILL MISSING | DONE | Same `LocalDestNames` chips in `BookmarksScreen.kt:222` → `NavUi.ActionDialog` |
| §16 Use current appearance as the new default | STILL MISSING | STILL MISSING | Exists only for links (`nbm_link_use_default`, `LinkEditor.kt:123`) |
| §16 Auto: text case, text before / after | STILL MISSING | STILL MISSING | `AutoBmLevel` (`core/edit5/AutoBookmarks.kt:24-31`) has no case, prefix or suffix fields |
| §16 Auto: remove non-alphanumeric, stop words | STILL MISSING | STILL MISSING | Not in `AutoBmLevel` / `AutoBmSettings` |
| §17 Reorganize from the reader thumbnails panel | STILL MISSING | DONE | `ReaderPanels.kt:72` → `ui/reader/ReaderThumbs.kt:92` `ReaderThumbnailsPanel` (long-press drag → `ThumbMoves.moveOrder`, menu with insert / replace / extract / delete) |
| §17 Enlarge / reduce thumbnails (reader panel) | STILL MISSING | DONE | `ReaderThumbs.kt:209-212` − / + buttons → `ThumbMoves.step` (64-240 dp), saved in `ThumbPanelPrefs` |
| §19 Video poster (none / from file) | STILL MISSING | STILL MISSING | The poster is always taken automatically (`VideoPoster`). There is no choice |
| §19 Video properties: appearance, locked, title, alt text | STILL MISSING | STILL MISSING | Only the generic comment properties dialog |
| §19 Renditions | STILL MISSING | STILL MISSING | `VideoPdf` writes a single MR rendition. There is no rendition editor |
| §19 Media Settings | STILL MISSING | STILL MISSING | Not built |
| §19 Playback settings (volume, repeat N, keep open) | STILL MISSING | STILL MISSING | `VideoPdf.Options` (`core/annot/VideoPdf.kt:35`) has only autoplay, loop, muted and controls |
| §19 Playback location (floating, hidden) | STILL MISSING | STILL MISSING | In-page and full screen only |
| §19 Use current settings as default per media type | STILL MISSING | STILL MISSING | Not built |
| §19 Media Actions tab | STILL MISSING | STILL MISSING | Only `/AA /PV` autoplay (`VideoPdf.kt:163`) |
| §21 Built-in Make Accessible: alt-text step | STILL MISSING | STILL MISSING | `builtin_access` (`Recipes.kt:173`) = OCR, SET_INFO, AUTO_TAG, ACCESS_CHECK. `StepType` has no alt-text step |
| §21 Panels, Add Divider | STILL MISSING | STILL MISSING | `StepType` has NOTE (instruction) only. There are no panels or dividers |
| §23a Insert: preview of the file being inserted | STILL MISSING | STILL MISSING | No preview in `PageOrganizeScreen` or the thumbnail-panel insert |
| §23a Drag page thumbnails between documents | STILL MISSING | STILL MISSING | No drag-and-drop source anywhere (`dragAndDropSource` / `startDragAndDrop` have no hits). Only whole files can be dropped into Organize |
| §23a Replace from a non-PDF file | STILL MISSING | STILL MISSING | `PageOpsScreen.kt:72` and `ReaderThumbs.kt:149` both use `rememberPdfPicker` (PDF only) |
| §23a Split several files at once | STILL MISSING | STILL MISSING | `SplitScreen` (`ui/tools/MergeSplitTools.kt`) takes a single file (`rememberPdfPicker { file = it.firstOrNull() }`) |
| §23a Crop: page size / centering | STILL MISSING | STILL MISSING | Paper size and centering are still only in the Resize screen (`CropResizeScreens.kt:147+`), not in Crop |

### C. FINAL_CHECK Foxit §24-27 (comments, measure, forms, JavaScript)

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 24.18 Note pop-up resize / move / reset | STILL MISSING | STILL MISSING | Move and reset are done: the floating note (`ui/reader/annot/FloatingNote.kt`) drags by its title bar, and `resetPopups` resets locations. Resize is missing: the window has a fixed 260 dp width (:61) |
| 24.27 Callout parts dragged separately | STILL MISSING | STILL MISSING | No /CL per-part handles. The callout knee and tip are set only at creation (`CommentWriter5.kt:86-88`) |
| 24.33 Cloud scallops inward / outward | STILL MISSING | DONE | Creation row chip `cf_cloud_inward` (`CommentExtrasUi.kt:97-98`) → `Annot2Host.cloudInward` (:235) → `Annot2Writer` → `CloudPath.build(inward)`; `/SDCloudIn` key |
| 24.58 Manage stamps: replace image, shared / admin stamps | STILL MISSING | STILL MISSING | `StampMeta` has no image replace. There are no shared or admin stamps |
| 24.66 Pop-ups: open / close all, move, reset, hover tooltip | STILL MISSING | STILL MISSING | Open and close all, reset, and move (floating note) are done. There is no hover tooltip over a comment for mouse or stylus |
| 24.87 Summary options: connectors, sequence numbers | STILL MISSING | STILL MISSING | Connector lines are done: switch `cf_sum_connectors` (`CommentExtrasUi.kt:492`) → `SummaryOptions.connectors` → `CommentSummary.kt:230`. Sequence numbers already existed. Still missing: the connector colour and opacity are fixed (`setStrokingColor(0.9,0.22,0.21)`, :232) |
| 24.102 Superscript / subscript | STILL MISSING | DONE | Text format dialog chips (`CommentExtrasUi.kt:539-542`) → `Annot.script` → `AnnotStyle` vertical-align in /DS and /RC (:341, :357) → `Annotations.kt:256` appearance. The style applies to the whole box |
| 24.105 Numbering / indent | STILL MISSING | STILL MISSING | Bullets only |
| 24.107 Paragraph spacing | STILL MISSING | STILL MISSING | Line spacing only |
| 25.18 Drag a measurement label separately | STILL MISSING | STILL MISSING | No label-offset model |
| 25.19 Endpoint drag handles | STILL MISSING | STILL MISSING | No point handles on stored lines or measurements. Points can only be typed in, for the last pending measurement |
| 25.27 Tickmark palette (31 symbols) | STILL MISSING | STILL MISSING | `TickSymbols.all` still has 10 symbols |
| 26.68 Sign / verify dynamic-XFA XML signatures | STILL MISSING | NOT-FEASIBLE | Status update (fix/fc-sign): Android has no `javax.xml.crypto` and no XAdES library. PDF signatures over XFA forms work |
| 27.10 Doc API stubs | STILL MISSING | STILL MISSING | Done: saveAs and closeDoc (`AcroJsLib.kt:226-227` → `FormActions.kt:139-145` → `ReaderScreen.kt:201-202`), and addScript, addField and setLock (forms-3). Still stubs: `doc.scroll` (`AcroJsLib.kt:239`) and `Field.setAction` / `buttonSetIcon` / `signatureSign` (:171). addScript, addField and setLock are not applied on signed files |

### D. DONE_comments_forms.md open items

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 8.28 Allow rich text (fill time) | PARTIAL | STILL MISSING | forms-2 and forms-3 added B / I / U while filling (`core/forms5/RichFields`, `InPageFormLayer`) and a styled saved appearance (Arabic too). The style still applies to the whole field; there are no per-character runs |
| 8.69 Named / SetOCGState / /Next | PARTIAL | STILL MISSING | Done: `core/forms5/NamedActions.kt` (page navigation, Print, Save, Find, zoom, fit, full screen, layouts), SetOCGState, and the layers panel (`NpfPanels` LAYERS). Still "not supported": GoBack / GoForward, Close, Quit and other names |
| 8.84 / 27.10 JS doc API | PARTIAL | STILL MISSING | Same as 27.10 above. `scroll` stays OPEN (PDF39_FORMS3_LOG) |
| 7.10 / 24.18 Floating pop-up window | PARTIAL | STILL MISSING | Selection menu `A_FLOAT_NOTE` (`AnnotEditUi.kt:93`) → `cx.floating` → `PageView.kt:433` `FloatingNoteWindow` (drag, edit, Save → `CommentExtras.saveFloating`). Resize is missing |
| 7.27 Cloudy oval | PARTIAL | DONE | `CloudPath.ellipse` (:58); Cloud chips on the Oval tool (`h.rectCloud`, `CommentExtrasUi.kt:97`) |
| 7.73 Identity: one shared identity | PARTIAL | DONE | `StampIdentity.load` uses the comment author (PDF39_COMMENTS_FORMS_LOG, `StampTokens.shared`) |
| 24.27 / 24.58 / 24.105 / 24.107 / 25.18 / 25.19 / 25.27 | NOT DONE / PARTIAL | STILL MISSING | See section C |
| 24.33 / 24.87 / 24.102 | NOT DONE / PARTIAL | see C | 24.33 and 24.102 are DONE. 24.87 still lacks connector colour and opacity |
| 26.38 Field text direction LTR / RTL | PARTIAL | NOT-FEASIBLE | AcroForm has no direction key; right alignment (/Q 2) is the substitute |
| 26.54 Set layer visibility: no layers panel | PARTIAL | DONE | Layers panel: reader INFO › Layers (`NpfPanels`) and Edit5 `e5_layers` |
| 26.68 XFA signature widget | PARTIAL | NOT-FEASIBLE | See section C (status update) |
| §19 Video properties: locked / title / alt text | PARTIAL | STILL MISSING | Same as the §19 row in B |
| §19 Video renditions, media settings, playback windows, defaults, media actions | NOT DONE | STILL MISSING | Same as the §19 rows in B |

### E. AUDIT PDF39_* logs and TAB_COMMENT_LOG

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| CF-log: Form-field recognition after Create PDF (pref) | DEFERRED | STILL MISSING | `FieldRecognition` is called only from the designer (`DesignerState.kt:242`, `FormDesignerScreen.kt:218,672`) and from the Fill & Sign targets (`ReaderState.kt:396`). Nothing runs it after Create PDF or convert6, and there is no preference |
| CF-log: OLD I1 Fill & Sign on flat forms | FIXED | DONE | `PageCanvas.kt:95` `loadFsTargets` (FILL_SIGN tab) → `ReaderState.kt:383-405` → `FieldRecognition.scanPage` / `at` → `PageCanvas.kt:424` `fillTarget` |
| FORMS2: XFA picture clauses | PARTIAL | DONE | FORMS3 added edit and validate pictures, Arabic month and day names, and the % scaling (`XfaPicture`, `XfaTemplate.kt:335 validatePicture`) |
| FORMS2: XFA scripts beyond initialize / calculate | PARTIAL | STILL MISSING | FORMS3 added interactive field events: `XfaTemplate.kt:254` exit / enter / change / click → `XfaEventRuntime`. Still not run: `<validate><script>` and the preSave / prePrint / preSubmit events |
| FORMS2: 8.84 JS stubs | PARTIAL | STILL MISSING | See 27.10 |
| FORMS2: 8.28 rich text | PARTIAL | STILL MISSING | See 8.28 (whole field only) |
| FORMS3: 8.84 JS scroll | OPEN | STILL MISSING | `AcroJsLib.kt:239` `["scroll"]` → "unsupported" |
| FORMS3: addScript / setLock / addField on signed files | (note) | STILL MISSING | Logged to the console and not applied on signed documents (a full rewrite would be needed). Low |
| TAB_COMMENT CM-14 "switch to SignedGuard.checkFullSave later" | FIXED (later note) | DONE | The flatten refusal works through `SignedSave.requireContentChangeAllowed` (`CommentOps7.kt:198`). It was not switched to `SignedGuard.checkFullSave`; that is cleanup only, and behaviour is the same |
| TAB_COMMENT CM-01…CM-38 (all others) | FIXED | DONE | Every row is FIXED, with no open notes. CM-29 is not in the log |

---

### Part C: gaps/DONE_pdf39.md lines 11-124 (PARTIAL / OPEN rows), checked on main b719484

Scope: every row in DONE_pdf39 §4 Pages organize, §5 Combine / Create, §6 Convert from PDF, §18 Layers, §19 Accessibility and §20 PDF/A whose "Now" was PARTIAL or OPEN. That is 34 rows. §18 and §20 have none.
Paths are relative to `app/src/main/java/com/smartdocs/editor/`.

FINAL_CHECK.md "Status updates" (line 2802+) has no row for any of these 34 items. The fix/convert-audit FIXED.md list fixes defects; it does not add any of these options. Each result below was checked in the code.

#### pdf39 §4 Pages: organize

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| pdf39 §4 #3 Insert from Clipboard (image or text as new page) | PARTIAL | DONE | Organize toolbar → "Insert more" (`ui/nve/shell/OrganizeInsert.kt` `NveOrganizeInsertMore`, called at `ui/pages/OrganizeScreen.kt:462`) → "Insert from clipboard" → `NvePaste.fromClipboard` (`ui/nve/shell/HomeActions.kt:50`). An image goes through `PdfOps.imagesToPdf`, a PDF is used as is, and text is converted with `Cv6Engine`. The result is inserted with `insertAwait` |
| pdf39 §4 #10 Split opt: navigational elements / bookmarks / identical form fields | PARTIAL | STILL MISSING | Keeping navigation works: the `pc_keep_nav` switch (`ui/edit5/PageOrganizeScreen.kt:137`) → `PageOrganize.writeParts(keepNavigation)` → `PageExtract.build`. Missing: there is no option for identical form fields. Each part always copies the fields of its kept widgets, with no rename or flatten choice |
| pdf39 §4 #12 Split opt: destination folder | OPEN | STILL MISSING | `PageOrganizeScreen.kt:261/263` always writes the parts to `app.documents.docsDir`. `ResultsList` (`ui/tools/ToolCommon.kt`) has only Open and Share. There is no folder picker. The global "save folder" (Settings, `core/files/NpfSaveFolder.kt`) copies new outputs to one fixed folder, but you cannot choose a folder for each split |

#### pdf39 §5 Combine / Create PDF

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| pdf39 §5 #21 Dialog: add folder / output folder choice | PARTIAL | STILL MISSING | "Add folder" works (`ui/tools5/CombinePlusUi.kt:71` `pickFolder`). The output is always `uniqueFile(app.documents.docsDir, …)` (`:195`). There is no output-folder choice in the dialog |
| pdf39 §5 #24 Conversion quality presets (Web / Office / Print / Custom) | OPEN | STILL MISSING | `Cv6Opts` (`core/convert6/Cv6Engine.kt:29`) and `ToPdfPlusScreen` have no named creation presets. Saved per-conversion defaults (`Cv6Defaults`) are not quality presets |
| pdf39 §5 #25 Custom: font embedding Subset/Full/none, action on failure | OPEN | STILL MISSING | No option anywhere. Office/HTML → PDF goes through `android.print.WebPdfWriter` (Chromium), which always subsets fonts. `Setup` has no font field |
| pdf39 §5 #26 Custom: per-image downsample / type / compression at creation | OPEN | STILL MISSING | Not available at creation. Only the separate Optimize tool (and the recipe OPTIMIZE step) can recompress afterwards |
| pdf39 §5 #27 Page size / orientation for images incl. custom | PARTIAL | STILL MISSING | `PageSize6` (`core/convert6/Images6.kt:6`) offers A4, Letter, Legal, A3, A5 and IMAGE. There is no custom width × height |
| pdf39 §5 #31 Word: convert links, headings → bookmarks | PARTIAL | DONE | Headings: Combine "headings → bookmarks" (`CombineOpts.headings` → `OutlineTools.detectHeadings`, `core/tools5/CombinePlus.kt:94`). Links: `DocxReader` turns `w:hyperlink` into `<a href>` (`core/office/DocxReader.kt:238`). Chromium print output keeps link annotations; `ToPdf.url(disableLinks)` → `WebCss.stripLinks` exists only to remove them. Not tested on a device |
| pdf39 §5 #32 Excel: bookmark per sheet, fit to page, all sheets | PARTIAL | STILL MISSING | All sheets: done. Fit: the sheet is zoomed automatically to the page width (`OfficeLayoutHtml.kt:96`), with no fit-to-page option. Missing: a bookmark for each sheet. `ToPdf.htmlParts` adds no outline |
| pdf39 §5 #33 PowerPoint: bookmark per slide, frame slides, handouts/notes layout, fit | PARTIAL | STILL MISSING | Handouts work (`pptx_handouts`, `Cv6Catalog.kt:108`). Missing: a bookmark for each slide, a notes-page layout (speaker notes are read only for search, `OfficeFind.kt:148`) and a frame-slides option. Print has "no frame" (`OfficeLayoutHtml.kt:229`) |
| pdf39 §5 #34 HTML: header/footer text, background color | PARTIAL | STILL MISSING | The background switch works (`WebCss`). `WebPdfWriter.Setup` (`android/print/WebPdfWriter.kt:66`) has no header/footer text field |
| pdf39 §5 #35 Publisher / Outlook settings | PARTIAL | NOT-FEASIBLE (Publisher) | Outlook works: EML and now also .msg → PDF. `MsgReader` (`core/openall/MailReaders.kt`) → viewer "Convert to PDF" (`ui/openall/ViewerConvert.kt:41`, FType.MSG). Publisher: there is no pure-Java .pub reader (same reason as #20) |
| pdf39 §5 #36 Printer: target folder/filename, if exists overwrite/append/warn | OPEN | STILL MISSING | `T5PrintService.onPrintJobQueued` (`core/tools5/ShareAndPrint.kt:134-139`) always writes to Documents/Printed with a unique name. The printer recipe has its `folderUri` ignored (only `BatchWorker` uses it). There is no overwrite or append choice |
| pdf39 §5 #37 Printer: PDF version 1.4–1.7, PDF/A-1b | OPEN | STILL MISSING | Partly done: the printer recipe (Tools › Recipes › `PrinterRecipeCard`, `ui/tools5/RecipesScreen.kt:231`) can include a PDFA step. That step uses the default `PdfAOptions` level, so no 1b and no level choice (`RecipeRunner.kt:131`). There is no PDF-version step |
| pdf39 §5 #38 Printer: quality presets, grayscale, custom paper forms | OPEN | STILL MISSING | Partly done: an OPTIMIZE step (dpi / quality) in the printer recipe. Missing: grayscale (no GRAY step; the printer's monochrome mode is not applied) and custom paper (`printer()` lists 5 fixed sizes, `ShareAndPrint.kt:115-119`) |
| pdf39 §5 #39 Printer: PDF properties (initial view, metadata) | OPEN | STILL MISSING | Metadata works through a SET_INFO step in the printer recipe. Missing: the initial view (no step type) |
| pdf39 §5 #40 Printer: PDF security (open/permissions password, 40/128/256-bit) | OPEN | STILL MISSING | Partly done: a PROTECT step with one password (`PdfOps.protect(src,out,pw,pw)`, `RecipeRunner.kt:192`). Missing: a separate permissions password, the permission flags and the key-length choice |
| pdf39 §5 #41 Printer: open after creation | OPEN | NOT-FEASIBLE | A print service cannot start an activity from the background (Android 10+ background-activity-start limits). The Android equivalent exists: the notification opens the PDF in the app (`ShareAndPrint.kt:173-176`), or a toast when notifications are off |

#### pdf39 §6 Convert from PDF

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| pdf39 §6 #46 Word layout modes (editable with layout / single column / precise text boxes) | OPEN | STILL MISSING | The `cv6_layout` chips offer Auto / Keep layout / Flowing (`ui/convert6/Cv6OptionsSheet.kt:110`, `Cv6Opts.keepLayout`). Missing: a precise text-box mode. `DocxBuilder` writes no `txbx` or frames |
| pdf39 §6 #47 Word: custom character spacing | OPEN | STILL MISSING | No option, and no `w:spacing w:val` on runs |
| pdf39 §6 #48 Word images: automatic / anchor paragraph / anchor page / inline | OPEN | STILL MISSING | Images are always inline (`DocxBuilder.kt:200` `wp:inline`). There is no anchoring option |
| pdf39 §6 #49 Word tables: lower threshold / font size adjust | OPEN | STILL MISSING | Only an on/off "tables" option (`Opt.TABLES`) |
| pdf39 §6 #50 Headers/footers: real headers / delete / don't detect | OPEN | STILL MISSING | Real headers come only with Keep layout (`DocxBuilder` header1/footer1). Flowing mode drops them. There is no separate header/footer choice and no "don't detect" |
| pdf39 §6 #51 Advanced text recovery (all / problem only / off + language) | PARTIAL | STILL MISSING | Word: always converts a repaired copy (`PdfFlow6.wordInput` → `RepairedInput`), OCRs problem pages when Auto OCR is on (`MixedPageOcr.plan`), and `ocr_pdf_docx` gives "all + language". Missing: Excel, PPT, TXT and HTML (`office(...)`) never repair broken font encoding, only scanned pages through `ocrCopy`. There is no language choice on `pdf_docx` |
| pdf39 §6 #53 Excel: split multi-line text into rows | OPEN | STILL MISSING | `XlsxBuilder` keeps multi-line text in one cell (wrapText). No option |
| pdf39 §6 #54 Excel: reduce merged cells | OPEN | STILL MISSING | Merges are always written (`XlsxBuilder.kt:103`). No option |
| pdf39 §6 #55 Text: preserve paragraphs vs break at N chars | OPEN | STILL MISSING | `pdf_txt` opts are RANGE / AUTO_OCR / TABLES only. `ConvertPipeline.kt:113` writes the layout text as is |
| pdf39 §6 #56 Text: headers/footers detect & convert / discard | OPEN | STILL MISSING | No option for TXT |
| pdf39 §6 #58 Extract images: color / mono output format | PARTIAL | STILL MISSING | `ExtractImagesScreen` (tool `extract_images`) has no format choice; extracted images keep their native format. Page export (`pdf_images`, `pdf_tiff`, `pdf_bmp`) is a different feature |
| pdf39 §6 #60 Batch: output folder (original's / specific) | OPEN | DONE | Tools › Recipes / Batch (`ToolScreens.kt:72-73`): a recipe with the "Convert to Office" step (`StepType.CONVERT_OFFICE` → `Cv6Engine`, `RecipeRunner.kt:269`), with the output set in `RecipeEditor` to the same folder or a picked folder. `BatchWorker.kt:160-176` exports to `item.folder` or `folderUri`. The convert-hub batch (Files › Convert) still writes to Documents |
| pdf39 §6 #61 Batch: page range per file | OPEN | STILL MISSING | `Cv6Opts.range` is one range for the whole run. The CONVERT_OFFICE step uses the defaults with no range |
| pdf39 §6 #62 Explorer context-menu conversions | PARTIAL | DONE | The Android equivalent: share-sheet aliases "Convert to Word / Excel / PowerPoint" (`AndroidManifest.xml` activity-alias `nl_alias_word/excel/ppt`), open-with, and Files multi-select → Convert (`ui/convert6/Cv6Batch.kt`, fix/fc-sync) |

#### pdf39 §19 Accessibility / PDF/UA

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| pdf39 §19 #70 Tags pane: create root/sibling/child; create from selection; "Tag as…" | PARTIAL | STILL MISSING | The tags tree (root, child, sibling, change type, and the reading-order view from fix/pages-a11y) works. Missing: creating a tag from a selection or area on the page, and "Tag as…" from the page (no such handler in `selmenu` or the reader) |

#### STILL MISSING

High: none.

Med
- pdf39 §4 #12 Split destination folder: no folder picker; the parts always go to Documents. (pages/convert)
- pdf39 §5 #21 Combine output folder: no output-folder choice. (pages/convert)
- pdf39 §6 #46 Word layout modes: no precise text-box mode. (pages/convert)
- pdf39 §6 #48 Word image anchoring: images are inline only. (pages/convert)
- pdf39 §19 #70 Tags: no "create tag from selection" or "Tag as…" on the page. (tools/OCR, accessibility)

Low
- pdf39 §4 #10 Split: no handling option for identical form fields. (pages/convert)
- pdf39 §5 #24 No creation quality presets (Web / Office / Print / Custom). (pages/convert)
- pdf39 §5 #25 No font-embedding choice (Chromium always subsets). (pages/convert)
- pdf39 §5 #26 No image downsample or compression at creation (only Optimize afterwards). (pages/convert)
- pdf39 §5 #27 No custom page size for image sources. (pages/convert)
- pdf39 §5 #32 Excel → PDF: no bookmark per sheet and no fit-to-page option. (pages/convert)
- pdf39 §5 #33 PPT → PDF: no bookmark per slide, notes layout or frame option. (pages/convert)
- pdf39 §5 #34 HTML → PDF: no header/footer text. (pages/convert)
- pdf39 §5 #36 Printer: fixed Documents/Printed folder, unique name, no overwrite/append. (pages/convert)
- pdf39 §5 #37 Printer: no PDF version choice; PDF/A only at the default level through a recipe. (pages/convert)
- pdf39 §5 #38 Printer: no grayscale or custom paper. (pages/convert)
- pdf39 §5 #39 Printer: no initial-view setting. (pages/convert)
- pdf39 §5 #40 Printer: no permissions password, permission flags or key length. (pages/convert, sign/protect)
- pdf39 §6 #47 Word: no character-spacing option. (pages/convert)
- pdf39 §6 #49 Word: no table-threshold or font-adjust options. (pages/convert)
- pdf39 §6 #50 Word: header/footer handling is tied to the layout mode; no "don't detect". (pages/convert)
- pdf39 §6 #51 Text recovery covers Word only; no language choice on pdf_docx. (pages/convert, tools/OCR)
- pdf39 §6 #53 Excel: no option to split multi-line text into rows. (pages/convert)
- pdf39 §6 #54 Excel: no option to reduce merged cells. (pages/convert)
- pdf39 §6 #55 Text: no paragraph or line-length option. (pages/convert)
- pdf39 §6 #56 Text: no header/footer option. (pages/convert)
- pdf39 §6 #58 Extract images: no output format choice. (pages/convert)
- pdf39 §6 #61 Batch convert: no page range per file. (pages/convert)

#### Counts (34 rows)

DONE 4 (#3, #31, #60, #62) · STILL MISSING 28 · NOT-FEASIBLE 2 (#35 Publisher part, #41) · OWNER-ACTION 0 · N/A 0

---

### Part C: DONE_pdf39 §4 (Convert: Create PDF) and §5 (Scan & OCR), PARTIAL / OPEN rows re-checked on main b719484

Scope: every row in `gaps/DONE_pdf39.md` lines 125-192 whose "Now" was PARTIAL or OPEN (30 rows). This was a read-only check of the code. FINAL_CHECK.md "Status updates" has no rows for these items, and `gaps/convert-audit/FIXED.md` lists defects, not these features. Every status below was confirmed in the code.
Paths are relative to `app/src/main/java/com/smartdocs/editor/`.

#### pdf39 §4 Convert tab: Create PDF

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| pdf39 §4 #84 From Multiple Files: keep original names and locations | PARTIAL | STILL MISSING | To PDF+ (`ui/tools5/ToPdfPlusScreen.kt` convertEachToPdf → `docs.uniqueFile(docs.docsDir…)`) and the hub (`Cv6Engine.outDir = docs.docsDir`) always write to the app's Documents. "Same folder as the source" exists only in Action Wizard recipes (`Recipes.kt OutputOptions.sameFolder`, `BatchWorker` writes back to the source's SAF folder), and a recipe needs at least one step (`RecipeEditor` Save is `enabled = steps.isNotEmpty()`). Create PDF has no output-folder or same-folder choice. |
| pdf39 §4 #86 Convert to PDF Settings: presets (Standard / HQ Print / Smallest / PDF/A-1b / PDF/UA-1 / USPTO) | PARTIAL | STILL MISSING | `Cv6Catalog` word_pdf / excel_pdf / ppt_pdf take only `Opt.MERGE`. fc-settings `Cv6Defaults` saves the options a conversion was last run with, but these are not named presets. There are no Standard / HQ print / Smallest / USPTO profiles at creation. |
| pdf39 §4 #87 Bookmarks from Word headings, Excel sheet names, PPT slide titles | PARTIAL | STILL MISSING | Only heading detection on the converted PDF (`CombinePlus` `o.headings` → `OutlineTools.detectHeadings`). `ToPdf6` / `XlsxReader` / `PptxReader` write no outline per sheet or slide. |
| pdf39 §4 #88 Convert to PDF Settings: Tagged PDF | PARTIAL | STILL MISSING | The WebView print output is untagged. No ToPdf6 / hub / Combine code calls `AccessAutoTag`. Workaround: tag afterwards (Accessibility auto-tag) or use a recipe with an AUTO_TAG step. |
| pdf39 §4 #91 Web Page: conversion levels (N levels / site / path / server) | OPEN | STILL MISSING | `ToPdf.url(ctx, url, out, setup, disableLinks)` captures one page only. There is no crawl or depth code. |
| pdf39 §4 #93 Web Page: disable links / single long page / bookmarks / tags / header-footer / backgrounds | PARTIAL | STILL MISSING | Done: disable links (`WebCss.stripLinks`, `WebOpts.noLinks`) and background graphics (`WebCss.build(backgrounds)`, `pc_web_backgrounds` switch in `WebOptionsCard`). Missing: one single long page, bookmarks from the page headings, PDF tags, and headers/footers on new pages. |
| pdf39 §4 #96 Web Page: media Screen / Print; scaling | PARTIAL | STILL MISSING | `android/print/WebPdfWriter.Setup` has no media type and no scale. `WebOpts` offers only paper, orientation, margin, timeout, images, backgrounds, underline and links. |
| pdf39 §4 #97 Open Weblink as New Document | OPEN | DONE | `ui/nve/NveLinks.kt NveLinkPreview` has an "Open as PDF" button (`fsg_link_as_pdf`) → `ToPdf.url` → opens the result in the reader. To reach it, tap a web link in the reader; the link preview appears when "ask before opening links" is on, which is the default (`NveLinks.openWeb`, hooked in ReaderScreen:438, `NveHost` composes the preview with nav). |
| pdf39 §4 #98 Form from a file (auto-detect form fields) | OPEN | DONE | `core/formsx/FieldRecognition` (underscores, boxes, lines, check glyphs → fields) runs in Tools → `form_builder` (`FormDesignerScreen`, Create mode calls `st.recognize`, then review sheet → `FieldRecognition.apply`). Caveat: the input must be a PDF, so a Word or image file is converted first (two steps). |
| pdf39 §4 #99 Form: scan a file into a form | OPEN | STILL MISSING | Recognition uses only text glyphs and vector segments (`FieldRecognition.scanPage` → `PageGraphics.scan`), so lines and boxes on a scanned (raster) page are not found. The scanner has no "to form" step. |
| pdf39 §4 #101 From Template (designed templates, search, categories, preview, Edit Now) | PARTIAL | STILL MISSING | `ui/templates/TemplatesScreen` is an offline gallery with live thumbnails, an editor and a preview. It has no search and no online library (`core/templates` makes no network calls). |
| pdf39 §4 #103 Add-in settings: Excel comments, PPT speaker notes, transitions, hidden slides, page/slide range | OPEN | STILL MISSING | The Office → PDF conversions take only `Opt.MERGE` (`Cv6Catalog` lines 75-77). `PptxReader` does not read notes, hidden slides or transitions, and `XlsxReader` does not read comments. |
| pdf39 §4 #104 Add-in settings: Excel fit options (actual / fit sheet / fit width) | OPEN | STILL MISSING | No fit-to-page or fit-to-width option exists in `core/office`, `convert6` or `ToPdf`. |
| pdf39 §4 #105 Word: comments to notes; footnote/endnote links; headings → bookmarks | PARTIAL | STILL MISSING | Footnotes and endnotes are now rendered with markers (`DocxReader.readNotes`, `OfficeHtml` `.notes`), but the markers are not links. Word comments (`comments.xml`) are not read. Headings → bookmarks works only in Combine. |
| pdf39 §4 #106 Mail Merge → PDF (record range, file-name field, e-mail each) | PARTIAL | STILL MISSING | The file name comes from a data column (`MailMergeScreen` `nameColumn` → `MergeMapping.fileName`). Missing: a record range (all rows always run) and e-mailing each PDF. |
| pdf39 §4 #107 Printer properties (presets, append, PDF version, security, metadata) | OPEN | STILL MISSING | Partly built: `T5PrintService` runs the chosen "printer recipe" (Tools → recipes → `PrinterRecipeCard`), which gives presets, security (PROTECT), metadata (SET_INFO) and PDF/A. Missing: appending to an existing PDF and choosing the PDF version; neither exists as a recipe step (`StepType`). |
| pdf39 §4 #110 Combine: PDF portfolio input (each part extracted) | OPEN | STILL MISSING | `CombinePlus.combine` imports a portfolio as its cover pages. Its parts are not expanded (no portfolio handling in `CombinePlus` / `CombinePlusUi`). |
| pdf39 §4 #111 Combine: Thumbnail view / List view | PARTIAL | STILL MISSING | `CombineTab` and `MergeScreen` (`FileOrderRow`) are list-only. There is no thumbnail or grid view. |
| pdf39 §4 #113 Combine: expand a file into pages; move/delete pages across files; zoom preview | PARTIAL | STILL MISSING | Only a page range per file. There is no page-level view or move before combining and no preview (no thumb or preview code in `CombinePlusUi`). |
| pdf39 §4 #117 Combine setting: output folder / overwrite / don't open / close opened files | PARTIAL | STILL MISSING | The output is always `docs.uniqueFile(docs.docsDir, base)` (`CombinePlusUi` Combine button). The only setting is the file name; there is no folder, overwrite or close-files option. |
| pdf39 §4 #118 Combine setting: save as PDF Portfolio / convert all files to PDF for portfolio | PARTIAL | STILL MISSING | Combine has no portfolio output. The separate Tools → `portfolio` tool creates one (`Portfolio.create`) and converts one part at a time from its menu (`PortfolioEditorUi` → `PortfolioOps.convertPart`). There is no "convert all" action and no option in Combine. |
| pdf39 §4 #119 Combine: e-mail attachment handling (don't include / attach all / convert and append) | PARTIAL | STILL MISSING | `ToPdf6` EML always embeds the attachments (`Embed.addFiles`). There is no choice and no convert-and-append. |
| pdf39 §4 #122 Recognize Text: line segments as path objects (tables) | OPEN | STILL MISSING | The OCR writers (`SearchablePdfWriter`, `EditableTextPdfWriter`) write no vector rules. Table rules are only dropped as noise (`ProOcrCore`, `OcrEnsemble`). |
| pdf39 §4 #124 Recognize Text > Multiple Files (files / folders / open files, output, overwrite) | PARTIAL | DONE | Tools → `recipes` (`RecipesScreen`): built-in "ocr" recipe (`Recipes.kt builtin_ocr`). Inputs: Add files, `BatchSourceButtons` (folder with sub-folders, open tabs, RecipesScreen:156). Output: recipe pattern, same folder or picked folder, conflict ask / overwrite / rename (`checkAndRun`, `BatchWorker`). The OCR+ screen (`ocr_plus`) itself still takes files only. |
| pdf39 §4 #126 Watched Folder (monitored folder → destination) | OPEN | STILL MISSING | No watched-folder code. `AppFolderWatch` only watches cloud working copies for sync. |

#### pdf39 §5 Scan & OCR

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| pdf39 §5 #133 Optimize scanned PDF: background removal / descreen / text sharpening / adaptive compression | PARTIAL | DONE | Tools → `optimize` → Advanced (`FoxitOptimizeOptions`) has scan switches for background (`nt_opt_scan_bg`), descreen (`nt_opt_descreen`), sharpen and MRC (`nt_opt_mrc`). Core: `Optimizer` runs `ScanFilters.descreen` / `sharpen` and `PixelOps.normalizeBackground`, and MRC is in `OptimizeScan`. |
| pdf39 §5 #137 Post-Scan: colour mode Grayscale / Black & White on an existing PDF | PARTIAL | DONE | B&W: Tools → `optimize` → Advanced → "B&W" switch (`nt_opt_bw`). `Optimizer` turns the images 1-bit with `ScanFilters.toBilevel` and writes them as CCITT G4. Grey: hub conversion `pdf_gray` (`PdfTransforms6.grayscale`). Only images are changed, which covers scanned pages. |
| pdf39 §5 #139 OCR PDFs and PDF portfolios | OPEN | DONE | Tools → `ocr_plus` (`OcrBatchScreen`:152): when `Portfolio.isPortfolio` is true, it runs `OcrRunner.ocrPortfolio`, which OCRs every PDF part plus the cover and then calls `Portfolio.replaceParts`. Not in background mode: the queued recipe path OCRs only the cover. |
| pdf39 §5 #140 Find OCR Suspects: red boxes, search doc/page, Not Text, Accept and Find, click to correct | PARTIAL | DONE | Tools → `ocr_review` (`ui/ocr/OcrToolsScreens.kt OcrReviewScreen`) has red boxes on the page view, a Document / Page scope chip, a confidence slider, per-suspect Accept and Not text, and a row tap that jumps to the page. Save rewrites the layer (`OcrReview.rewrite`). Caveat: corrections are typed in the list. Tapping a box on the page does not open an editor. |
| pdf39 §5 #141 All Suspects dialog (correct many at once) | PARTIAL | DONE | The same screen lists every suspect of the page or the whole document as editable fields (up to 400 rows, with the cut shown on screen) and has "Accept all" (`nt_ocr_review_accept_all`). |

#### STILL MISSING

#### High
- (none)

#### Med
- pdf39 §4 #86: no Convert-to-PDF presets (Standard / HQ print / Smallest / PDF/A-1b / PDF/UA-1 / USPTO); Office conversions have only Merge. (pages/convert)
- pdf39 §4 #88: no "Tagged PDF" option at creation; WebView output is untagged, so tagging is a separate step. (pages/convert)
- pdf39 §4 #103: no Office conversion options (speaker notes, Excel comments, hidden slides, transitions, slide/page range). (pages/convert)
- pdf39 §4 #104: no Excel fit options (actual size / fit sheet / fit width). (pages/convert)
- pdf39 §4 #113: Combine cannot expand files into pages, move or delete single pages across files, or preview. (pages/convert)

#### Low
- pdf39 §4 #84: Create PDF from multiple files cannot save next to the originals (only Documents); same-folder output only via an Action Wizard recipe. (pages/convert)
- pdf39 §4 #87: no bookmarks from Excel sheet names or PPT slide titles. (pages/convert)
- pdf39 §4 #91: web page conversion is one page only (no levels / site / path / server). (pages/convert)
- pdf39 §4 #93: web page has no single long page, bookmarks, tags or header/footer on new page (links and backgrounds are done). (pages/convert)
- pdf39 §4 #96: web page has no Screen / Print media choice and no scaling. (pages/convert)
- pdf39 §4 #99: no "scan into a form": field recognition ignores raster lines and boxes. (comments/forms)
- pdf39 §4 #101: templates have no search and no online library. (pages/convert)
- pdf39 §4 #105: Word comments are not converted, and footnote / endnote markers are not links. (pages/convert)
- pdf39 §4 #106: mail merge has no record range and cannot e-mail each PDF. (pages/convert)
- pdf39 §4 #107: the virtual printer cannot append to an existing PDF or choose the PDF version (other settings work via the printer recipe). (pages/convert)
- pdf39 §4 #110: Combine does not expand a portfolio's parts. (pages/convert)
- pdf39 §4 #111: Combine has no thumbnail view. (pages/convert)
- pdf39 §4 #117: Combine has no output folder, overwrite or close-files settings. (pages/convert)
- pdf39 §4 #118: Combine cannot save as a portfolio, and the portfolio tool has no "convert all parts to PDF". (pages/convert)
- pdf39 §4 #119: e-mail conversion always embeds attachments; there is no choice and no convert-and-append. (pages/convert)
- pdf39 §4 #122: OCR does not recreate table rules as vector paths. (tools/OCR)
- pdf39 §4 #126: no Watched Folder. (tools/OCR, batch)

#### Counts (30 rows)
- DONE: 8 (#97, #98, #124, #133, #137, #139, #140, #141)
- STILL MISSING: 22 (Med 5, Low 17, High 0)
- NOT-FEASIBLE: 0
- OWNER-ACTION: 0
- N/A: 0

---

### Part C: DONE_pdf39 §6 Export / §7 Portfolio & Binder / §8 Compliance: PARTIAL + OPEN rows on main b719484

Scope: DONE_pdf39.md lines 193-277, every row whose "Now" was PARTIAL or OPEN (29 rows). FINAL_CHECK.md "Status updates after this check" (line 2802+) has no entries for these areas. AUDIT/PAGES_CONVERT_LOG.md stages C/D/G match DONE_pdf39 (it lists the same rows as OPEN). Later merges (convert-audit, tab-tools-pages, fc-settings, thumbs-panel ...) did not change these features. Each row below was checked in code.

#### §6 Export PDF

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| pdf39 §6 #143 DOCX settings: include comments | OPEN | STILL MISSING | `Cv6Catalog` `pdf_docx` opts = RANGE/AUTO_OCR/LAYOUT/TABLES/INCLUDE_IMAGES only. No `w:comment` / comments.xml writer anywhere in the code, so PDF annotations never become Word comments. |
| pdf39 §6 #145 Explorer "Convert to Microsoft Office" | PARTIAL | DONE | AndroidManifest activity-aliases `.ConvertToWord/.ConvertToExcel/.ConvertToPowerPoint/.ConvertToText` (SEND + VIEW of application/pdf, label `nl_alias_word` "SmartDocs: to Word"). `MainActivity`:148 `NveConvertTargets.forComponent` → `ShellPrefs.ALIASES` opens the Convert hub with pdf_docx/xlsx/pptx/txt preselected. Reached from a file manager's Share / Open with. This is the Android version of the Explorer context menu. |
| pdf39 §6 #147 XLSX settings: decimal / thousands separators | PARTIAL | STILL MISSING | Separators are still detected automatically only (`BidiText` / `LayoutFlow` number parsing). `pdf_xlsx` has no separator option in `Cv6OptionsSheet`, and there is no separator string in values-en. |
| pdf39 §6 #155 Export to JPEG2000 / BMP | PARTIAL | NOT-FEASIBLE | BMP is DONE (`pdf_bmp` in the Convert hub). JPEG 2000 has no encoder on Android or in PdfBox (decode only, `ImageExtract6` JPX passthrough). PAGES_CONVERT_LOG:87 says the same. |
| pdf39 §6 #156 Image settings: PNG interlace/filter, JPEG format, JP2 tile, monochrome, ICC | PARTIAL | STILL MISSING | `ImgFmt` = JPG/PNG/WEBP with quality; GRAY; 1-bit only for TIFF (`Opt.BW`). Missing: PNG interlace/filter, JPEG baseline/progressive, ICC profile embedding, and monochrome for PNG/JPG. A custom encoder would be needed because `Bitmap.compress` has no interlace or ICC; that is feasible. The JP2 tile size is not feasible (see #155). |
| pdf39 §6 #158 Export All Images: output format / colour space / resolution | PARTIAL | STILL MISSING | `ui/convert6/ExtractImagesScreen` (tool `extract_images`) offers min size, naming, range, ZIP/folder/gallery/share. There is no output-format, colour-space or resolution choice; images keep their native format or become PNG (`ImageExtract6`). |
| pdf39 §6 #159 Export Selection As Word / Excel / PowerPoint | OPEN | STILL MISSING | The only selection export is `NveSelection.kt`:179 `nv_export_selection`, which writes `.txt`. There is no area/selection → DOCX/XLSX/PPTX path. |

#### §7 PDF Portfolio & PDF Binder

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| pdf39 §7 #165 Read Mode / print / search across portfolio files | PARTIAL | STILL MISSING | Search works (`PortfolioOps.search`, in `PortfolioEditor`). `ui/tools5/PortfolioEditorUi.kt` has no read mode and no print of all parts or of selected parts; each part opens alone as an extracted copy. |
| pdf39 §7 #166 Create portfolio from folder / web page / scanner / clipboard | PARTIAL | STILL MISSING | `PortfolioScreen` create card (MergePortfolioScreens.kt) has "add files" only, and the editor adds files/folder. A scanner source exists separately (`ScanStudioScreen`:192 "portfolio of the results" switch). Missing: web page and clipboard sources for a portfolio. The Combine tab has these sources, but the portfolio screen does not. |
| pdf39 §7 #176 Drag-drop to reorganise files/folders | PARTIAL | STILL MISSING | Moving is only "move to folder" from the item menu (`PfAsk.Move` → `PortfolioEdit.move`). There is no drag gesture in PortfolioEditorUi. |
| pdf39 §7 #177 Edit a component and save it back into the portfolio | OPEN | STILL MISSING | Parts open as extracted copies (`Portfolio.extract` → `openPdf`). `PortfolioEdit` / `PortfolioOps` have no replace-part or save-back function (only add/remove/rename/convert/compress). |
| pdf39 §7 #178 PDF Binder: folders, project (.bdr) / template (.bdrt) | PARTIAL | STILL MISSING | `BinderProject(name, opts, entries)` + `BinderStore` (CombinePlusUi "pc_binders" card) save a flat ordered list. Missing: folders/sections inside the binder, and binder templates. |
| pdf39 §7 #179 Binder TOC template (Word template with field codes) | OPEN | STILL MISSING | The TOC is generated only from bookmarks (`CombineOpts.toc/tocTitle/tocLevels`). No template input and no `<<DocName>>` field codes. |
| pdf39 §7 #180 Binder custom metadata fields from Office properties | OPEN | STILL MISSING | `CombineEntry` = file/title/pages/attach/password only. No metadata columns and no reading of Office core properties. |
| pdf39 §7 #182 Binder output options (name, folder, TOC, open after, show in folder) | PARTIAL | DONE | CombinePlusUi: name field (`t5_file_name_optional`), TOC switch plus TOC title, PDF/A switch. Result `ResultSheet` (ui/common/Components.kt:202) gives Open / Share / save elsewhere (`CreateDocument`, user picks a folder) / replace. "Show in folder" has no Android equivalent. |

#### §8 Compliance

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| pdf39 §8 #185 Profiles: libraries, find, single checks / fixups view | PARTIAL | STILL MISSING | `PreflightProfile` is a fixed list of 10 (a1b/a2b/a2u/a3b/x4/e1/print/version/zugferd/ua1) with favourites. There are no libraries or groups, no search across profiles, and no single-check or single-fixup view. |
| pdf39 §8 #186 Print-production checks | PARTIAL | STILL MISSING | `Standards.PrintFacts` covers hairlines, overprint, spot colours, ppi, invisible text, Courier, JPX, layers. Missing: plates/separations, CxF, rich black, ICC/Lab object checks, transparent-object listing. |
| pdf39 §8 #188 Create Report: PDF with problems highlighted | PARTIAL | STILL MISSING | `Standards.reportText` writes xml / html / txt only (PdfAScreen:168). There is no PDF report with masks or layers marking the problem areas. |
| pdf39 §8 #189 Preflight as an Action Wizard step | PARTIAL | STILL MISSING | `Recipes.StepType` has PDFA (convert) and ACCESS_CHECK, but no preflight check step and no copy-on-success/error or report output. |
| pdf39 §8 #193 Convert to PDF/A-4 / 4e / 4f | OPEN | STILL MISSING | `PdfALevel` = A1A..A3U only. The DONE file blames PdfBox 2.0. That reason is weak: `PDDocument.setVersion(2.0f)` writes a 2.0 header. The real work is the PDF 2.0 / A-4 rules (no Info dict, pdfaid:rev). This is not a hard blocker. |
| pdf39 §8 #194 PDF/A 2u: outline non-Unicode text | PARTIAL | NOT-FEASIBLE | No glyph-to-outline converter exists on PdfBox-Android (same reason as #187 "fonts to outlines"). The workaround is to rasterise the affected pages (PdfA "rasterizeMissingFonts"). |
| pdf39 §8 #197 PDF/A verify conformance | PARTIAL | STILL MISSING | Verification is only the approximate `Standards.preflight` / `levelReport`. There is no full ISO 19005 validator (veraPDF-class). |
| pdf39 §8 #198 PDF/E-1 convert | PARTIAL | STILL MISSING | Verify (`PreflightProfile.PDFE_1`, approximate) and remove (`removeClaims`) exist. There is no PDF/E-1 conversion; PDF/E-1 is PDF 1.6 with restrictions, so it is feasible like PDF/A. |
| pdf39 §8 #200 PDF/X verify X-1a/3/4p/5*/6*; remove | PARTIAL | STILL MISSING | Only `PDFX_4` checks (approximate) plus remove. Missing: other PDF/X variants. PDF/X conversion is NOT-FEASIBLE (#199) but verification is not. |
| pdf39 §8 #201 PDF/UA-1 verify | PARTIAL | STILL MISSING | `Standards.pdfUa` plus the accessibility checker use a rule subset. It is not the full Matterhorn protocol. |
| pdf39 §8 #204 PDF/VT verify VT-1 / VT-2 | PARTIAL | STILL MISSING | `Claims.pdfvt` is only read and shown (PdfAScreen:178). There is no `PreflightProfile` for VT, so no verification. |
| pdf39 §8 #207 Save As PDF/E / PDF/X | PARTIAL | STILL MISSING | Save-as goes through the PDF/A screen only. PDF/E output is missing; PDF/X output is NOT-FEASIBLE (no CMYK, #199). |
| pdf39 §8 #208 PDF/E / PDF/X output at creation | PARTIAL | STILL MISSING | PDF/A works at scan (`ScanStudioScreen` pdfa) and in Combine (`pc_save_pdfa`). PDF/E at creation is missing; PDF/X is not feasible. |
| pdf39 §8 #210 Standards panel: OutputIntent | PARTIAL | STILL MISSING | `NpfPanels.kt` STANDARDS shows PDF version, A/UA/X/E claims and Tagged. It does not show the OutputIntent condition (`OutputConditionIdentifier`) or the ICC profile name; the preflight only counts intents. |

#### STILL MISSING

High: none.

Med
- pdf39 §6 #143: DOCX export "include comments" (annotations → Word comments) is missing. Area: pages/convert.
- pdf39 §6 #159: Export Selection / area as Word / Excel / PowerPoint is missing (only .txt). Area: pages/convert (+ reader selection menu).
- pdf39 §7 #177: Edit a portfolio part and save it back into the portfolio (parts are copies only). Area: pages/convert.

Low
- pdf39 §6 #147: XLSX decimal / thousands separator choice is missing (auto only). Area: pages/convert.
- pdf39 §6 #156: Image export lacks PNG interlace/filter, JPEG baseline/progressive, ICC embed, and PNG/JPG monochrome. Area: pages/convert.
- pdf39 §6 #158: Extract images has no output format / colour space / resolution options. Area: pages/convert.
- pdf39 §7 #165: Portfolio has no read mode and no print of all/selected parts. Area: pages/convert.
- pdf39 §7 #166: Portfolio cannot be created from a web page or the clipboard. Area: pages/convert.
- pdf39 §7 #176: Portfolio has no drag-drop reorganising (menu move only). Area: pages/convert.
- pdf39 §7 #178: Binder has no folders/sections and no binder templates. Area: pages/convert.
- pdf39 §7 #179: Binder TOC template with field codes is missing. Area: pages/convert.
- pdf39 §7 #180: Binder metadata columns from Office properties are missing. Area: pages/convert.
- pdf39 §8 #185: Preflight profile libraries / search / single checks-fixups view are missing. Area: tools/OCR (compliance).
- pdf39 §8 #186: Print checks lack plates, CxF, rich black, ICC/Lab, transparency listing. Area: tools/OCR (compliance).
- pdf39 §8 #188: There is no PDF preflight report with highlighted problems. Area: tools/OCR (compliance).
- pdf39 §8 #189: There is no preflight check step in recipes / Action Wizard. Area: tools/OCR (compliance).
- pdf39 §8 #193: PDF/A-4 / 4e / 4f conversion is missing (blocker claim weak). Area: tools/OCR (compliance).
- pdf39 §8 #197: There is no full PDF/A conformance validator (approximate only). Area: tools/OCR (compliance).
- pdf39 §8 #198: PDF/E-1 conversion is missing. Area: tools/OCR (compliance).
- pdf39 §8 #200: PDF/X verification covers X-4 only. Area: tools/OCR (compliance).
- pdf39 §8 #201: PDF/UA-1 verification is not full Matterhorn. Area: tools/OCR (compliance).
- pdf39 §8 #204: There is no PDF/VT verification. Area: tools/OCR (compliance).
- pdf39 §8 #207: Save As PDF/E is missing (PDF/X is not feasible). Area: tools/OCR (compliance).
- pdf39 §8 #208: PDF/E output at creation is missing. Area: tools/OCR (compliance).
- pdf39 §8 #210: The Standards panel does not show the OutputIntent condition / profile. Area: reader/view (nav panel).

#### Counts (29 rows)

- DONE: 2 (#145, #182)
- STILL MISSING: 25 (Med 3, Low 22)
- NOT-FEASIBLE: 2 (#155 JPEG 2000 part, BMP done; #194)
- OWNER-ACTION: 0
- N/A: 0

---

### Part C: DONE_pdf39 lines 278-423 (§17, §23, §22, §34, old-plan notes): PARTIAL / OPEN rows on main b719484

Scope: every row in `gaps/DONE_pdf39.md` lines 278-423 whose "Now" is PARTIAL or OPEN (OTHER-SCOPE, DONE and NOT-FEASIBLE rows are not included). Paths are relative to `app/src/main/java/com/smartdocs/editor/`. Every row was checked in the code. FINAL_CHECK.md and the AUDIT logs were used only as leads.

#### §17 Page thumbnails panel

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| pdf39 §17 #212 Reorganize pages from the thumbnails panel | PARTIAL | DONE | `ui/reader/ReaderThumbs.kt` `ReaderThumbnailsPanel` (hooked at `ReaderPanels.kt:72`, THUMBNAILS). Long-press drag (`detectDragGesturesAfterLongPress` → `ThumbMoves.moveOrder` → `PdfOps.reorder`), plus rotate / delete / duplicate / insert / replace / extract. Each change goes through `ReaderState.applyInPlace`. Path: reader → Thumbnails panel (fix/thumbs-panel) |

#### §23 Organize

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| pdf39 §23 #218 Insert by dragging thumbnails from another document | PARTIAL | STILL MISSING | Organize accepts whole files dropped from other apps (`dragAndDropTarget`). The app has no drag source at all (no `dragAndDropSource` / `startDragAndDrop`), so you cannot drag a page thumbnail from one document into another |
| pdf39 §23 #219 Insert > From Folder | OPEN | DONE | `ui/nve/shell/OrganizeInsert.kt` `NveOrganizeInsertMore`, item `nl_insert_folder`: an `OpenDocumentTree` picker → every PDF in the folder, sorted by name → `PageInsert.merge` → `insertFromAwait`. Path: Tools `organize` → `OrganizeScreen` toolbar (`OrganizeScreen.kt:462`) → "More insert" → Folder |
| pdf39 §23 #221 Insert > From Clipboard | PARTIAL | DONE | Same menu, `nl_insert_clipboard` → `NvePaste.fromClipboard`. It handles an image URI (→ `imagesToPdf`), a PDF URI and text (→ `clipboard_pdf`), so it is no longer text only |
| pdf39 §23 #234 Replace from a non-PDF file | OPEN | STILL MISSING | Every replace path uses `rememberPdfPicker` with PDF only: `ui/edit5/PageOpsScreen.kt:72` (`e5_replace_pages`), `ReaderThumbs.kt:149` (THUMB_REPLACE), `OrganizeScreen.kt:154` (`pickThumbFile`). Missing: a `*/*` picker plus the `any_pdf` conversion, which INSERT already has in `PageOrganizeScreen.kt:95` |
| pdf39 §23 #235 Split Document: several files at once | OPEN | STILL MISSING | `ui/tools/MergeSplitTools.kt` `SplitScreen` takes one file (`rememberPdfPicker` single). Edit5 SPLIT_RANGES / SPLIT_BOOKMARKS also work on the open document only. No batch split anywhere |
| pdf39 §23 #236 Split Document: output options (location, names) | PARTIAL | STILL MISSING | Names: done (`PartName` chips, `PageOrganizeScreen.kt:135`). Location: no per-run target folder. Parts go to `docsDir`, and the copy to the global default save folder (`core/files/NpfSaveFolder`) only happens through Settings. The Tools `split` output goes into a `<name>_split` sub-folder, which NpfSaveFolder skips. (FINAL_CHECK called this DONE; that is too generous) |
| pdf39 §23 #240 Crop: page size and centering inside the crop dialog | PARTIAL | STILL MISSING | `ui/edit5/CropResizeScreens.kt` `CropScreen` (from :49) has units (`BoxUnit`), show-boxes and constrain proportions. It has no paper-size or centre controls; those exist only in `ResizeScreen` (:149) |

#### §22 Layers

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| pdf39 §22 #270 Layers: list for All Pages / Visible Pages, Expand/Collapse All | PARTIAL | STILL MISSING | `ui/edit5/LayersScreen.kt` shows pages per layer and collapsible groups. The reader panel (`NpfPanels.kt:131`) is a switch list only. Neither has a "layers of visible / current pages only" filter |

#### §34 Accessibility

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| pdf39 §34 #283 Checker options: page range | PARTIAL | STILL MISSING | Rule categories: done (`AccessRules.apply(results, groups, …)`). `AccessibilityScreen.kt` has no page-range input, and the rules run on the whole document |
| pdf39 §34 #286 Context menu: Fix (per rule) | PARTIAL | DONE | `ui/tools5/AccessibilityScreen.kt:257`: every FAIL / MANUAL rule has a Fix item → `fixRule(id)` (:145). Depending on the rule, Fix opens the fix dialog (`AccessRules.fixable`), runs a structure fix (`AccessFix2.STRUCTURE_FIXES`, 16 ids), opens the OCR / Unlock tool or the tags editor, or (for human checks such as contrast or flicker) shows the explanation. Path: Tools `accessibility` (`ToolsScreen.kt:88`) → result row menu (fix/pages-a11y) |
| pdf39 §34 #290 Fix: bookmarks from structure elements (choose elements) | PARTIAL | STILL MISSING | `core/tools5/AccessFix.kt:64` `bookmarksFromHeadings` uses layout headings (levels 1-2), only when the file has no outline. It does not read the structure tree, and you cannot choose the element types |
| pdf39 §34 #293 Fix: nested alt text / alt text that hides annotations | PARTIAL | DONE | `core/tools5/AccessFix2.removeNestedAlt / removeAltWithoutContent / removeAltHidingAnnots`, called from `fixRule` ids `nested_alt`, `alt_content`, `alt_hides_annot` (`AccessibilityScreen.kt:157-159`) |
| pdf39 §34 #294 Fix: tables, lists, heading nesting | PARTIAL | DONE | Tables are recognised in auto-tag: `AccessAutoTag.tables` (:42, used at :214). The rule fixes are in `AccessFix2`: `wrapOrphans` (TR / TH-TD / LI / Lbl-LBody), `firstRowHeaders`, `summaries`, `fixHeadingSkips` (`AccessibilityScreen.kt:161-167`) |
| pdf39 §34 #295 Accessibility Setup Assistant | OPEN | DONE | `ui/settings/FcsSettings.kt` `FcsA11yAssistant` (4 steps), row `FcsA11yAssistantRow` at `NpfSettingsScreen.kt:259`. Path: tool `npf_settings` (More preferences, `ToolScreens.kt:104`) → Accessibility (fix/fc-settings) |
| pdf39 §34 #296 Reading Option (per-document reading order and mode) | OPEN | STILL MISSING | Only the global `NpfPrefs.readingOrder` exists (infer / raw, read by `ReaderAi6.kt:86`; set in `NpfSettingsScreen.kt:258` and the assistant). No per-document value, and no reading mode choice (visible pages / whole document) |
| pdf39 §34 #301 Edit alt text of one figure from the page | PARTIAL | STILL MISSING | Alt text can be edited only in the tags editor / figures list. The image selection menu's `I_PROPS` → `ImageTools5.PropsDialog5` has no alt-text field, and there is no tap-figure-to-edit-alt action |
| pdf39 §34 #302 Reading Order (Touch Up) tool: tag regions on the page | OPEN | STILL MISSING | No on-page region tagging tool. The code has no touch-up or region-to-tag code |
| pdf39 §34 #303 Show page content groups / table cells / tables and figures | OPEN | STILL MISSING | No on-page overlay of structure or content groups |
| pdf39 §34 #304 Table Editor | OPEN | STILL MISSING | No on-page table editor. Cell attributes (Scope / Headers / RowSpan / ColSpan / Summary) can be set only through tag properties in `AccessTagsUi.kt` |
| pdf39 §34 #305 Edit Form Field Text / Edit Table Summary dialogs | PARTIAL | DONE | Form field text (/TU): the tooltip field in `ui/formsx/FieldPropsDialog.kt:164` (form designer `FormDesignerScreen.kt:242/255`), plus automatic `AccessFix.fieldDescriptions`. Table summary: `pc_tags_summary` via `AccessTags.setAttrs` in the tags editor |
| pdf39 §34 #307 Order panel: reorder the reading order by drag, renumber | PARTIAL | DONE | `ui/tools5/AccessTagsUi.kt:211` `OrderPanel`: per-page numbered list, long-press drag or up / down → `AccessTags.moveTo`. Opened from the tags editor chip `pa_tags_order` (:92-94). Limits: moves stay among siblings, and there is no cut / paste |
| pdf39 §34 #308 Set / Change / Apply Area Reading Order (draw areas) | OPEN | STILL MISSING | Nothing draws areas on the page to set the order |
| pdf39 §34 #309 Content panel (containers, cut/paste, New Container, Create Artifact, dictionaries, …) | OPEN | STILL MISSING | No Content panel. Only the Tags tree and the Order view exist |
| pdf39 §34 #312 Find Element (artifacts, OCR suspects, unmarked content) | PARTIAL | STILL MISSING | `AccessTags.unmarked` (:293) finds only annotations without /StructParent. It does not find artifacts, OCR suspects or unmarked page content |
| pdf39 §34 #313 Object Properties > Tag tab (alt, language, title, attribute objects) | PARTIAL | DONE | Tags editor `TagProps` → `AccessTags.setProps` (Title, Alt, ActualText, Lang, Expansion, ID) and `setAttrs` (`TagAttrs`: Scope / RowSpan / ColSpan / Headers / Summary / ListNumbering). These cover the Tag tab, reached from the tree rather than from a page object. Layout attributes are not editable |

#### Old plan and pdf-ad notes

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| pdf39 Old plan: Foxit 34 extras (rules + range, …, autotag tables) | PARTIAL | STILL MISSING | Since pdf39: drag order is done (#307) and table recognition is done (#294). Still missing: the checker page range (#283) and the Content panel (#309) |
| pdf39 Old plan: verify_A Word → PDF headings → bookmarks | PARTIAL | STILL MISSING | Only Combine has this option (`pc_headings_bookmarks` → `CombinePlus` → `OutlineTools`). The Convert hub `word_pdf` card (`Cv6Catalog.kt:75`, opts = MERGE only) converts through `ToPdf6.convert` → DOCX → HTML → WebView print (`ToPdf6.kt:190-194`), which writes no outline |

#### Spot-checks (code chains followed)

1. OK: reader Thumbnails panel → `ReaderPanels.kt:72` → `ReaderThumbnailsPanel` → drag → `ThumbMoves.moveOrder` → `move()` → `state.applyInPlace` → `PdfOps.reorder`.
2. OK: Organize "More insert" → Folder → `OpenDocumentTree` → `PageInsert.merge` → `OrganizeState.insertFromAwait`.
3. OK: Organize "More insert" → Clipboard → `NvePaste.fromClipboard`, with image, PDF and text branches.
4. OK: Accessibility result row menu → Fix → `fixRule` → `AccessFix2.removeNestedAlt` / `wrapOrphans` / `firstRowHeaders`, saved to `*_accessible`.
5. OK: Tags editor → `pa_tags_order` chip → `OrderPanel` → drag → `AccessTags.moveTo`.
6. OK: More preferences → `FcsA11yAssistantRow` → `FcsA11yAssistant`.
7. OK: auto-tag → `AccessAutoTag.tables` is used inside tagging (:214).
8. FAIL (replace from non-PDF): every replace entry point is a PDF-only picker (PageOpsScreen:72, ReaderThumbs:149, OrganizeScreen:154).

#### STILL MISSING

#### High
None.

#### Med
- pdf39 §23 #234 Replace pages from a non-PDF file: all replace pickers are PDF only, and the `any_pdf` conversion is missing on replace. (pages/convert)
- pdf39 §23 #235 Split several files at once: Split takes one file only. (pages/convert)
- pdf39 §34 #302 Reading Order touch-up tool: no tagging of page regions. (tools/OCR accessibility)
- pdf39 §34 #296 Per-document reading option: only the global reading-order preference exists. (reader/view)
- pdf39 Old plan Foxit 34 extras: checker page range and Content panel are still missing. (tools/OCR accessibility)

#### Low
- pdf39 §23 #218 Drag page thumbnails from another document: the app has no drag source. (pages/convert)
- pdf39 §23 #236 Split output location per run: names are done; location is only the global save folder, and Tools Split's sub-folder is not copied. (pages/convert)
- pdf39 §23 #240 Crop dialog: no page size or centering (only in Resize). (pages/convert)
- pdf39 §22 #270 Layers: no "visible / current pages only" filter. (edit)
- pdf39 §34 #283 Accessibility checker: no page range. (tools/OCR accessibility)
- pdf39 §34 #290 Bookmarks from chosen structure elements: only layout headings 1-2 are used. (tools/OCR accessibility)
- pdf39 §34 #301 Edit a figure's alt text from the page: the image properties dialog has no alt field. (tools/OCR accessibility)
- pdf39 §34 #303 On-page display of content groups, tables and figures. (tools/OCR accessibility)
- pdf39 §34 #304 On-page Table Editor. (tools/OCR accessibility)
- pdf39 §34 #308 Area reading order. (tools/OCR accessibility)
- pdf39 §34 #309 Content panel. (tools/OCR accessibility)
- pdf39 §34 #312 Find Element: no artifacts, OCR suspects or unmarked content. (tools/OCR accessibility)
- pdf39 Old plan verify_A: Convert hub `word_pdf` does not turn headings into bookmarks (only Combine does). (pages/convert)

#### Counts (28 rows)

- DONE: 10 (#212, #219, #221, #286, #293, #294, #295, #305, #307, #313)
- STILL MISSING: 18
- NOT-FEASIBLE: 0
- OWNER-ACTION: 0
- N/A: 0

---

### Spot-checks (button → handler → core), merges after fa724fd

1. **JS saveAs / closeDoc (fix/pdf39-forms-3):** `AcroJsLib.kt:226-227` → `FormActions.kt:139-145` ("saveas" → `r.save()` then `scriptSaveAs`; "close" → `scriptClose`) → `ReaderScreen.kt:201-202` (SAF "Save a copy" / `requestLeave`). **OK**
2. **Thumbnail panel size and reorder (fix/thumbs-panel):** `ReaderPanels.kt:72` → `ReaderThumbs.kt:92` `ReaderThumbnailsPanel` → − / + at :209-212 → `ThumbMoves.step` / `ThumbPanelPrefs`; long-press drag → `ThumbMoves.moveOrder` → `inPlace` (:135). **OK**
3. **Reflow editing across pages (fix/reflow-plus):** edit-mode button `nt_reflow` (`TextTools5.kt:467`) → `ReflowSheet` (:761) → `TextEdit5.loadFlow` (:363) / `reflowRange` (:378) → `core/edit/ReflowFlow.kt`. **OK**
4. **Fill & Sign on flat forms (fix/pdf39-comments-forms):** `PageCanvas.kt:95` (FILL_SIGN tab) `loadFsTargets` → `ReaderState.kt:383-405` `FieldRecognition.scanPage` → tap `PageCanvas.kt:424` `fsTargetAt` → `fillTarget` (:529). **OK**
5. **Inward cloud scallops (fix/pdf39-comments-forms):** creation chip `cf_cloud_inward` (`CommentExtrasUi.kt:97-98`) → `Annot2Host.cloudInward` (:235) → `Annot2Writer.kt:112,122` → `CloudPath.build(…, inward)`. **OK**
6. **Comment superscript / subscript:** Text format dialog chips (`CommentExtrasUi.kt:539-542`) → `Annot.script` → `AnnotStyle` vertical-align (:341, :357) → `Annotations.kt:256` text appearance. **OK**
7. **Floating note (fix/pdf39-forms-2 / selmenu-rest):** selection menu `A_FLOAT_NOTE` (`SelObjects.kt:70`, `AnnotEditUi.kt:93`) → `cx.floating` → `PageView.kt:433` `FloatingNoteWindow` → Save → `CommentExtras.saveFloating` (:97). **OK.** Move works; resize is not built, so 24.18 stays STILL MISSING.
8. **Summary connector lines:** switch `cf_sum_connectors` (`CommentExtrasUi.kt:492`) → `SummaryOptions.connectors` → `CommentSummary.kt:230` draws the connector. **OK.** The colour is hard-coded, so 24.87 stays partly open.
9. **Link "Set this position" and named-destination list (fix/fc-sign):** `ReaderLinks.kt:47/69/95` `currentView` → `LinkEditor.kt:86-87`; `LocalDestNames` → `NavUi.kt:224`. **OK**

**Claim that FAILED:**
- **"Replace pages from a non-PDF file":** all replace entry points (`PageOpsScreen.kt:72`, `ReaderThumbs.kt:149`, `OrganizeScreen.kt:154`) use the PDF-only `rememberPdfPicker`.

**Disagreements with FINAL_CHECK:**
- **§23 "split output options":** only the naming is done. There is no per-run output folder (pdf39 part d).
- **§21 Make Accessible:** still has no alt-text step.

Sub-reports partC_pdf39_d (7 OK, 1 FAIL as above) and partC_pdf39_a/b/c contain more chains.

### STILL MISSING (combined, by importance)

#### High
- none

#### Med
- **8.28 / FORMS2:** rich text at fill time is still whole-field style only, with no per-character runs. (comments/forms)
- **CF-log:** field recognition does not run automatically after Create PDF, and there is no preference for it. (comments/forms)
- **§12 Search & Remove Text tool:** not built. (edit)
- **§15 GoToE:** no link or bookmark destination inside an attachment. (edit)
- **§16:** a bookmark's destination cannot be set to the current view. (edit)
- **§19 Video:** no properties dialog (appearance, title, alt text) and no playback settings (volume, repeat, keep open). (comments/forms)
- **§23a / pdf39 #234:** pages cannot be replaced from a non-PDF file. (pages/convert)
- **§23a / pdf39 #235:** Split cannot take several files at once. (pages/convert)
- **pdf39 §4 #12 / #236:** Split has no output folder choice. (pages/convert)
- **pdf39 §5 #21 / #117:** Combine has no output folder, overwrite or close-files choice. (pages/convert)
- **pdf39 §5 #113 / #111:** Combine cannot expand files into pages, move pages across files, preview or show thumbnails. (pages/convert)
- **pdf39 §4 #86 / #24:** no creation presets (quality, fonts, downsampling). (pages/convert)
- **pdf39 §4 #88:** no "Tagged PDF" option at creation. (pages/convert)
- **pdf39 §4 #103 / #104:** Office conversion has no notes, Excel comments, hidden-slide, transition or range options, and no Excel fit options. (pages/convert)
- **pdf39 §6 #46 / #48:** Word export has no precise text-box mode and no image anchoring. (pages/convert)
- **pdf39 §6 #143:** DOCX export cannot include comments. (pages/convert)
- **pdf39 §6 #159:** a selection cannot be exported to Word, Excel or PowerPoint. (pages/convert)
- **pdf39 §7 #177:** a portfolio part cannot be edited and saved back. (pages/convert)
- **pdf39 §19 #70:** no "create tag from selection" on the page. (tools/OCR accessibility)
- **pdf39 §34 #302 / #308:** no reading-order touch-up tool and no area reading order. (tools/OCR accessibility)
- **pdf39 §34 #296:** no per-document reading option. (reader/view)
- **pdf39 old plan:** the accessibility checker has no page range and there is no Content panel. (tools/OCR accessibility)

#### Low

comments/forms
- 24.18 / 7.10: the floating note cannot be resized.
- 24.66: no hover tooltip over comments.
- 24.27: callout parts cannot be dragged separately (/CL handles).
- 24.58: custom stamps have no image replace and there are no shared or admin stamps.
- 24.87: the summary connector colour and opacity are fixed.
- 24.105: no numbering or indent in text comments.
- 24.107: no paragraph spacing in text comments.
- 25.18: a measurement label cannot be dragged on its own.
- 25.19: no drag handles on the end points of stored measurements or lines.
- 25.27: the tickmark palette has 10 symbols, not 31.
- 27.10 / 8.84: doc.scroll, Field.setAction, buttonSetIcon and signatureSign are stubs. addScript, addField and setLock are not applied on signed files.
- 8.69: the GoBack / GoForward / Close / Quit named actions are unsupported.
- FORMS2: XFA `<validate>` scripts and the preSave / prePrint / preSubmit events do not run.
- §19 video: no poster choice, renditions, media settings, floating or hidden playback, per-type defaults or Media Actions tab.
- pdf39 #99: a scanned paper form cannot be turned into a fillable form.

reader/view
- §9: no tab groups.
- §9: the reading ruler has no vertical orientation, opacity or reset.
- §10: form fields cannot be read aloud in a Tab-through mode.
- §12: layer content and signature text are not searched.
- §12: the catalog index has no title and no include folders.
- §22: an article box cannot be moved or resized.
- pdf39 §8 #210: the Standards panel does not show the OutputIntent.

edit
- §15: no recently used URLs list.
- §15: no "append all web links on the page".
- §15: View Web Links has no download or convert.
- §15: no play-media / sound action.
- §16: no default zoom for new bookmarks.
- §16: no bookmarks from structure tags.
- §16: the current bookmark appearance cannot be made the default.
- §16: auto bookmarks have no text case or text before / after.
- §16: auto bookmarks cannot remove non-alphanumeric characters or stop words.
- pdf39 §22 #270: the Layers list has no "visible pages only" filter.

pages/convert
- §23a: no preview of the file being inserted.
- §23a: page thumbnails cannot be dragged between documents.
- §23a: the Crop dialog has no page size or centering.
- pdf39 §4–§8: the remaining Low rows in partC_pdf39_a/b/c: web conversion levels, media and headers; templates search; mail merge range and e-mail; virtual printer options (#36-#40, #107); Combine portfolio and EML handling; Word / Excel / Text export options; image export and extract options; portfolio print, web and clipboard sources, drag-drop; binder folders, templates and metadata; bookmarks from Excel sheets and PPT slides; footnote links; one page range per file in batch convert.

tools/OCR / compliance / accessibility
- §21: the Make Accessible recipe has no alt-text step.
- §21: recipes have no panels or dividers.
- pdf39 #122: OCR does not write table lines as paths.
- pdf39 #126: no watched folder.
- pdf39 §8: no preflight profile library, print-production checks, PDF preflight report or preflight recipe step; no PDF/A-4, PDF/E conversion or Save As, or PDF/VT verify; validation is approximate (PDF/A, X-4 only, UA rule subset).
- pdf39 §34: bookmarks are not built from structure elements; alt text cannot be edited on the page; content groups are not shown on the page; no on-page table editor; Find Element is limited.
- pdf39 #51: text recovery covers Word only.

### Counts

| Source | DONE | STILL MISSING | NOT-FEASIBLE | OWNER-ACTION | N/A |
|---|---|---|---|---|---|
| FINAL_CHECK fcmp_09-13_22 (A) | 0 | 7 | 0 | 0 | 0 |
| FINAL_CHECK fcmp_14-21_23 (B) | 7 | 26 | 0 | 0 | 0 |
| FINAL_CHECK Foxit §24-27 (C) | 2 | 11 | 1 | 0 | 0 |
| DONE_comments_forms (D) | 3 | 6 | 2 | 0 | 2 (duplicate rows) |
| PDF39 logs + TAB_COMMENT_LOG (E) | 4 | 6 | 0 | 0 | 0 |
| DONE_pdf39 lines 11-124 (part a) | 4 | 28 | 2 | 0 | 0 |
| DONE_pdf39 lines 125-192 (part b) | 8 | 22 | 0 | 0 | 0 |
| DONE_pdf39 lines 193-277 (part c) | 2 | 25 | 2 | 0 | 0 |
| DONE_pdf39 lines 278-423 (part d) | 10 | 18 | 0 | 0 | 0 |
| **Total** | **40** | **149** | **7** | **0** | **2** |

Some rows appear in more than one source because the DONE files overlap with FINAL_CHECK, for example §23a replace / split / crop and §17. Each source is counted on its own.

---

## FINAL CHECK 2, part D: FINAL_CHECK.md lines 2011 to the end, plus the DONE_ai_office, FIXED_5_tools_pages, PAGES_CONVERT_LOG and PAGES_A11Y_LOG open items

Checked on origin/main b719484, read-only. Paths are under `app/src/main/java/com/smartdocs/editor/`. Nothing was run on a device.

### 0. "Status updates after this check" table (FINAL_CHECK.md 2813-2875): code confirmation

I checked all 59 rows. All 59 are confirmed in code and reachable. None failed.

| Rows (branch) | Confirmed by |
|---|---|
| §28 cert policy (fc-sign) | `ui/security/CertPolicyUi.kt` `CertPolicyRow`, hooked in at `ui/tools/SecurityTools.kt:297` → `core/sign/CertPolicies` load / save / delete |
| §37 prompt trust, expired TS, ignore DSS, LTV ask (fc-sign) | `SignPrefs.promptTrust/acceptExpiredTimestamps/useDocumentValidationInfo`; `DigitalSignature.verify(...)`, `ltvAskAboveBytes`, `SignReport.withoutLtv`; UI in `DigitalSignScreen.kt`, `SigFieldActions.kt` |
| 26.68 XAdES NF; §15 set position, §16 named dest list, §15 open as PDF (fc-sign) | `LinkEditor.kt` `currentView` (passed by `ReaderLinks`); `LocalDestNames` (BookmarksScreen / NavUi / LinkEditor); `ui/nve/NveLinks.kt` → `ToPdf.url` |
| 34.27 pref correction | `ReaderAi6.orderedPage` reads `NpfPrefs.readingOrder` |
| B §23 conv defaults; language system; QAT position; connected off; a11y assistant; printer marks / hairline; notif gear; debug log; index prefs; Asian width; doc title; cache cap (fc-settings) | `Cv6Defaults` + `FcsConvDefaultsRows`; `SettingsScreen.kt:72-77` `AppLang.SYSTEM/AR/EN` → `AppLang.apply`; `V5PinnedBottomRow` in ReaderScreen, `qatPosition`; `FcsPrefs.connectedOff` (ConnectedClient / ConnectedDocs / ReaderState); `FcsA11yAssistant` (NpfSettingsScreen); `PrintForm.kt:113-115` → `PrintPrep` `PrinterMarks.layout/fixLineWidths`; `FcsNotificationChannels`; `core/util/DebugLog` (SmartDocsApp, CrashLog, FcsSettings); `FcsIndexPrefsRows` (NpfSettingsScreen:238); `FullTextIndex.evictToLimit` from IndexWorker |
| §39 keys (fc-settings) | `NveKeys.kt`: EXPORT_COMMENTS, CUT:119, DESELECT, CROSS_REF, ADD_BOOKMARK:79, DUPLICATE_PAGE:123 (→ `applyInPlace` `PageOps5.duplicate`, :285-288), READ_TO_END:124, READ_PAUSE:125, MENU_BAR, TOOLBARS, EMAIL_TABS (→ `emailOpenTabs`), JS_CONSOLE:130 |
| M5 labels; ads in ResultSheet; invite link; recent-size merge (fc-settings) | `AdBanner` in `ui/common/Components.kt`; manifest intent filter lines 54-59 for `smartdocs.app/invite` |
| sync near-realtime; 16b; take over; Remove Me; reminder; To/Cc; FDF rule; Dropbox team; SharePoint; Egnyte WebDAV; DMS NF; connected message / clone (fc-sync) | `core/sync/ChangeWatch` (+ `SyncSkip`, `AppFolderWatch`, `DavWatch`, `Dropbox/Box.waitForChange`); `FilesScreen.kt:195` → `Cv6Batch`; `ReviewScreen.kt:259` `CollabStore.takeOver`, :283 `CollabClient.leave`; `ReviewReminder`; `RecipientField`; `SendBackScreen` + `FdfRule`; `CloudScopeRow` (`CloudBrowserScreen.kt:155`, `teamRoot`, `SharePoint`); `DavPresets` in WebDavScreens; `ConnectedDocScreen.kt` openMessage + `ConnectedDocs.clone`:183 |
| §19 read-aloud blocks (tts-blocks) | `core/tts/ReadBlocks`, `ReadAloudController.nextBlock`, buttons in `ReaderOverlays.kt` |
| A1 reflow tables / columns; 20b reflow editing (reflow-plus) | `ReflowTables`, `ReflowColumns`, `ReflowReader.kt`; `core/edit/ReflowFlow` → `TextEdit5`, `ReflowSheet` in `ui/edit/TextTools5.kt` |
| §17 thumbs panel, thumb menu, size (thumbs-panel) | `ReaderThumbnailsPanel` (ReaderPanels), `ThumbMoves`, `ThumbPanelPrefs`; `ThumbSelMenu` used at `OrganizeScreen.kt:313` and `ReaderThumbs.kt:240` |

### 1. FINAL_CHECK §28-§34: the old STILL MISSING rows

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| §28 Cert protect: save as policy | STILL MISSING | DONE | See section 0: `CertPolicyRow` in the certificate tab of the Protect tool |
| §31 Take ownership of others' comments | STILL MISSING | DONE | `ReviewScreen.kt:259` → `CollabStore.takeOver` → `CommentMerge.takeOver` |
| §31 Remove Me (server) | STILL MISSING | OWNER-ACTION | `CollabClient.leave` is called at `ReviewScreen.kt:283`. The owner must redeploy the collab worker (`/reviews/:id/leave`) |
| §32 DMS non-PDF preview | STILL MISSING | NOT-FEASIBLE | There are no DMS/ECM connectors on Android (ECM is N/A) |
| §32 Dropbox Team Folders | STILL MISSING | DONE | `Dropbox.teamRoot`, chips in `CloudScopeRow` |
| §32 OneDrive Business / SharePoint | STILL MISSING | OWNER-ACTION | Client done (`core/cloud/SharePoint.kt`, CloudScopeRow). The Azure app needs the Sites.Read.All and Files.ReadWrite.All permissions |
| §32 Egnyte | STILL MISSING | DONE (WebDAV) | `core/webdav/DavPresets.kt` Egnyte preset in Add place. No native Egnyte API provider |
| §33 Connected open message | STILL MISSING | OWNER-ACTION | `ConnectedDocScreen.kt` openMessage, `ConnectedClient.settings`. The connected worker must be redeployed |
| §33 Clone document | STILL MISSING | DONE | `ConnectedDocScreen.kt:183` → `ConnectedDocs.clone` |
| §33 Disable ConnectedPDF | STILL MISSING | DONE | `FcsPrefs.connectedOff`, switch in `FcsSettings.kt` |
| §34.2 Checker page range | STILL MISSING | STILL MISSING | `AccessInspect.facts(doc, pdf, textCheckPages)` and `AccessRules.evaluate` have no page-range input |
| §34.7 Fix per rule | STILL MISSING | DONE | `AccessibilityScreen.kt:150-167`: `AccessFix2.STRUCTURE_FIXES`, tags editor, OCR, unlock; human-check rules open the explanation |
| §34.18 Bookmarks from chosen elements | STILL MISSING | STILL MISSING | Only `AccessFix.bookmarksFromHeadings` (`AccessFix.kt:64`) |
| §34.24 Nested alt / alt hiding annots fix | STILL MISSING | DONE | `AccessFix2.removeNestedAlt` / `removeAltHidingAnnots`, Fix menu `AccessibilityScreen.kt:157-159` |
| §34.26 Setup Assistant | STILL MISSING | DONE | `FcsA11yAssistant` (More preferences → Accessibility) |
| §34.27 Per-document reading option | STILL MISSING | STILL MISSING | Only the global `NpfPrefs.readingOrder`. No per-document setting |
| §34.38 Edit alt text on the page | STILL MISSING | STILL MISSING | Still list-only (`FiguresPanel`). No on-page editor |
| §34.39 Touch-up reading order tool | STILL MISSING | STILL MISSING | 0 hits for touchUp or area order |
| §34.40 Show content groups / tables / figures on the page | STILL MISSING | STILL MISSING | No on-page overlay |
| §34.41 Table editor | STILL MISSING | STILL MISSING | Only tag attributes (`AccessTags.setAttrs`) |
| §34.44 Order panel (drag) | STILL MISSING | DONE | Tags editor "Reading order" view, `AccessTagsUi.kt:214-249`: `AccessTags.readingOrder`, drag or up / down → `AccessTags.moveTo` |
| §34.45 Area reading order | STILL MISSING | STILL MISSING | Not built |
| §34.46 Content panel | STILL MISSING | STILL MISSING | Not built |
| §34.49 Find Element (beyond unmarked annots / links) | STILL MISSING | STILL MISSING | Only `AccessTags.unmarked` (`AccessTags.kt:293`) |

### 2. FINAL_CHECK §35-§37: the old STILL MISSING rows

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| §35 AI image editing (`/images/edits`), undoable insert | STILL MISSING (part) | STILL MISSING | 0 hits for `images/edits`. `ImageGenScreen` inserts into a PDF copy, not as a reader undo step. Generation needs the proxy IMAGE_* config (owner) |
| §35 Workspace crafts: infographics, web pages, Spaces slides entry | STILL MISSING (part) | STILL MISSING | 0 hits for infographic. `SlidesGen` is reached only from `TDocTools.kt`, not from SpacesScreen |
| §36 Bleed / crop marks | STILL MISSING (part) | DONE | `PrintForm.kt:113-115` regMarks / bleedMarks → `PrintPrep` `PrinterMarks.layout` |
| §36 Print range "current view" | STILL MISSING | STILL MISSING | `PrintSettings` / print sheet have no visible-area range. You can only snapshot and then print |
| §37 Structure tab order as a preference | STILL MISSING | STILL MISSING | Only the one-off `AccessFix.tabOrder` (`AccessibilityScreen.kt:313`) |
| §37 Action Wizard log file; PDF/UA report during the run | STILL MISSING | STILL MISSING | The job log is only viewed in a dialog (`RecipesScreen.kt:330-348`). There is no export, and the pdfua step (`RecipeRunner.kt:179`) writes no report |
| §37 Auto-open pop-ups | STILL MISSING (part) | DONE | `NpfPrefs.autoOpenPopup` (Settings › Comments), read in `NpfReaderHooks` |
| §37 Pencil stroke-merge timeout | STILL MISSING | DONE | Comment menu `CommentExtrasUi.kt:201` → `CommentExtras.cycleInkMerge` → `InkMerge.canMerge/merge` |
| §37 Custom blank page size | STILL MISSING | STILL MISSING | `TemplatesBlankScreens.kt:148-156` offers only `PageBoxOps.PAPERS` presets |
| §37 Alert on a full-screen request | STILL MISSING | STILL MISSING | No pref or alert (0 hits) |
| §37 PDF/A view mode | STILL MISSING (part) | STILL MISSING | Only the `npf_pdfa_claim` notice |
| §37 Show changed text in colour | STILL MISSING | STILL MISSING | 0 hits |
| §37 Auto OCR on scanned pages while editing | STILL MISSING (part) | DONE | Edit prefs switch `TextTools5.kt:742` (auto_ocr) → `:505` `t.ocrPage()` (pro-ocr 5d4b961) |
| §37 Form text-field overflow indicator | STILL MISSING | DONE | `FormPrefs.overflowIndicator` (switch `FormRunSettings.kt`) → `InPageFormLayer` |
| §37 Form recognition after Create PDF | STILL MISSING | STILL MISSING | DEFERRED in PDF39_COMMENTS_FORMS_LOG. No `FieldRecognition` call in convert6 |
| §37 Default e-mail subject / body | STILL MISSING | DONE | `core/share5/MailDefaults`, `MailDefaultRows` in NpfSettingsScreen, used in `ShareOptionsSheet` |
| §37 Full-screen per-item choices | STILL MISSING | STILL MISSING | Not built |
| §37 Per-message notification gear | STILL MISSING (part) | DONE | `FcsNotificationChannels` |
| §37 Debug mode | STILL MISSING | DONE | `DebugLog` + switch in FcsSettings |
| §37 Index preferences | STILL MISSING (part) | DONE | `FcsIndexPrefsRows` |
| §37 Languages: follow the system / more languages | STILL MISSING | DONE | `AppLang.SYSTEM` (SettingsScreen:72). More languages are NOT-FEASIBLE (they need human translations) |
| §37 Facing page count / gap between pages | STILL MISSING | STILL MISSING | 0 hits in the reader prefs or strings |
| §37 Always show the page size | STILL MISSING | STILL MISSING | 0 hits |
| §37 Keep zoom on Go-to-Destination | STILL MISSING | STILL MISSING | `keepZoom` (`ScreenControls.kt:121`) is per-tab zoom, not link / destination jumps |
| §37 Local reminder before review expiry | STILL MISSING | DONE | `core/collab/ReviewReminder`, switch in ReviewScreen |
| §37 Take ownership | STILL MISSING | DONE | as in §31 |
| §37 To/Cc auto-complete | STILL MISSING | DONE | `RecipientBook` / `RecipientField` |
| §37 Send as FDF above N MB | STILL MISSING | DONE | `FdfRule` in `SendBackScreen` (route share5/send_back) |
| §37 Ignore Asian character width | STILL MISSING | DONE | `ArabicNormalizer` / `SearchPrefs` (fc-settings) |
| §37 Document title in search results | STILL MISSING (part) | DONE | search DB v2 title (fc-settings) |
| §37 Fast Find cache limit | STILL MISSING | DONE | `FullTextIndex.evictToLimit` |
| §37 Prompt to trust signers on open | STILL MISSING | DONE | SignedBar "Trust" + `SignPrefs.promptTrust` |
| §37 Expired timestamps / ignore DSS | STILL MISSING (part) | DONE | `DigitalSignature.verify(acceptExpiredTimestamps, useDocumentValidationInfo)` |
| §37 Ask if verification info is too big | STILL MISSING | DONE | `SignOptions.ltvAskAboveBytes`, `SignReport.withoutLtv` (DigitalSignScreen) |

### 3. FINAL_CHECK §39-§42: the old STILL MISSING rows

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| §39 Ctrl+X / Ctrl+Shift+A / annotation copy-paste | STILL MISSING | DONE | `NveKeys` CUT:119, DESELECT, annotation clipboard |
| §39 Ctrl+B / T / Shift+C | STILL MISSING | DONE | ADD_BOOKMARK:79 (handler :233); CROSS_REF in the Foxit layout |
| §39 Ctrl+Alt+D | STILL MISSING | DONE | DUPLICATE_PAGE:123 → :285 `applyInPlace` |
| §39 Ctrl+L / I / 8 / Shift+8 / Enter / Alt+V | STILL MISSING | DONE | EXPORT_COMMENTS (on Ctrl+Alt+L), EXPORT_HIGHLIGHTS, HIDE / SHOW, REPLY, PASTE_IN_PLACE |
| §39 Read aloud B / P / Y | STILL MISSING | DONE | READ_TO_END:124, READ_PAUSE:125, toggle |
| §39 F10 / Shift+F10 / Ctrl+F11 | STILL MISSING | DONE | MENU_BAR, `SelMenuState`, TOOLBARS |
| §39 Form Tab / Shift+Tab; Ctrl+J | STILL MISSING | STILL MISSING | Ctrl+J is done (JS_CONSOLE:130). There is no Tab / Shift+Tab field navigation: the only KEYCODE_TAB use is Ctrl+Tab between documents (`NveKeys.kt:55-56`) |
| §39 Ctrl+Shift+K | STILL MISSING | DONE | EMAIL_TABS → `emailOpenTabs` |
| §40 Tags / Content / Order panels | STILL MISSING | STILL MISSING (part) | The Order panel is done (`AccessTagsUi.kt:214`). There is still no Content panel |
| §41 Combine thumbnail preview (delete / reorder pages) | STILL MISSING | STILL MISSING | `CombinePlusUi.kt` has no thumbnails, only a page range per file |
| §41 AI image editing | STILL MISSING | STILL MISSING | as in §35 |
| §41 Bluebeam / foreign comments editable | STILL MISSING | STILL MISSING | No work done. The only hit is a comment in `AnnotStyle.kt:521` |
| §41 CSV column choice | STILL MISSING | STILL MISSING | `CommentFilter.csv` (`core/view5/CommentFilter.kt:98-102`) has fixed columns |
| §41 Sequential stamp | STILL MISSING | STILL MISSING | `StampTokens.fill` map (`HijriDate.kt:89-92`) has no counter token |
| §41 Dropbox Team Folders | STILL MISSING | DONE | as in §32 |
| §41 Pencil: hide cursor / highlight over text | STILL MISSING | STILL MISSING | 0 hits for PointerIcon / stylus hover |
| §41 Preflight grayscale fixup | STILL MISSING | STILL MISSING | `Standards.kt` has no grayscale fixup; only the `pdf_gray` convert tool |
| §41 Workspace crafts | STILL MISSING | STILL MISSING | as in §35 |
| §41 Translation: Danish | STILL MISSING | STILL MISSING | `AiLang.NAMES` (`core/ai5/AiText.kt:97-100`) has no "da" |
| §41 Volume / polylength / diameter / radius | STILL MISSING | STILL MISSING | `MeasureType` (`MeasureModel.kt:34`) is unchanged |
| §41 Page labels from bookmarks / regions | STILL MISSING | STILL MISSING | 0 hits |
| §41 Enterprise shared stamps | STILL MISSING | STILL MISSING | 0 hits |
| §42 Egnyte | STILL MISSING | DONE (WebDAV) | `DavPresets` |
| §42 Callas-style preflight, PDF/X / E / VT | STILL MISSING | DONE (verify / profiles) | Stage G merged: `PdfAOptions.level` 1a-3u, `PreflightProfile` (PDF/A, PDF/X-4, PDF/E-1, print, version, Factur-X) in `PdfAScreen.kt`. PDF/X conversion and PDF/VT are NOT-FEASIBLE (no CMYK management; PdfBox 2.0 cannot write PDF 2.0) |
| §42 Watched folder | STILL MISSING | STILL MISSING | 0 hits in core/tools5. Also OPEN in DONE_pdf39 row 126 |

### 4. FINAL_CHECK, old plan: the old STILL MISSING / OWNER rows

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| A Sync near-realtime | STILL MISSING | DONE (+OWNER-ACTION for Drive / OneDrive push) | `core/sync/ChangeWatch`, Dropbox / Box long-poll, `SyncSkip` |
| A Take over comments | STILL MISSING | DONE | as in §31 |
| B B13 slides | STILL MISSING | STILL MISSING | `SlideRender.pdf` has notes, but there is no PPTX notes writer (0 notesSlide hits in ai6) and no Spaces entry |
| B AI-8 image generation | STILL MISSING | STILL MISSING | as in §35 |
| B XFA picture clauses / scripts | STILL MISSING | DONE | `core/forms/XfaPicture` (display / edit / validate), `XfaEventRuntime` (exit / enter / change / click), PDF39_FORMS2/3 logs. Interactive validate *scripts* are not confirmed |
| B Ads banner in ResultSheet | STILL MISSING | DONE | `AdBanner` in `Components.kt` ResultSheet |
| C 8b Region export to Word / Excel | STILL MISSING | STILL MISSING | 0 hits |
| C 12 Custom blank size; template search | STILL MISSING | STILL MISSING | presets only; `TemplatesScreen` has no search |
| C 16b Batch convert from Files | STILL MISSING | DONE | `FilesComponents.kt:62` → `Cv6Batch` |
| C 18b Reading-order panel | STILL MISSING | STILL MISSING (part) | The Order panel is done. Area / touch-up reading order is still missing (= 34.39 / 34.45) |
| C 19b Preflight profiles, PDF/A levels, PDF/X | STILL MISSING | DONE | Stage G (see §42). PDF/X output is NOT-FEASIBLE |
| C 20b Reflow editing | STILL MISSING | DONE | `ReflowFlow` / `ReflowSheet` |
| D I1 Smart Fill & Sign on flat forms | STILL MISSING | DONE | `ReaderState.loadFsTargets` → `PageCanvas` targets (`FieldRecognition` when there is no AcroForm) |
| D K2 Redo OCR | STILL MISSING | DONE | `OcrSheet.kt:129-154` redo checkbox → `OcrInPlace.kt:85` `OcrRedo.strippedCopy`; also `OcrRunner.plan.redo` |
| D M5 TalkBack labels | STILL MISSING | DONE | pages-a11y (16 left labelled) |
| D A1 Reflow tables | STILL MISSING | DONE | `ReflowTables` |
| D F4 Combine page picking | STILL MISSING | STILL MISSING | as in §41 |
| D 19b (repeat) | STILL MISSING | DONE | as above (counted once, in C) |
| D PREF-5 PDF/A view mode | STILL MISSING | STILL MISSING | as in §37 |
| D G8 Printer marks / hairline | STILL MISSING | DONE | `PrinterMarks` |
| D SHL-5 Tab groups / two editable documents | STILL MISSING | STILL MISSING | 0 hits for tab groups |
| D K11 QR scanning | STILL MISSING | STILL MISSING | Nothing in `ui/scanner` |
| D E10 Nested / mirrored / skewed XObject text | STILL MISSING | STILL MISSING | No change |
| D B15 Video summary | STILL MISSING | STILL MISSING | `VideoSummary.kt` still uses only the system TextToSpeech; untested |
| D Invite deep link | STILL MISSING | OWNER-ACTION | Manifest intent filter added; the owner must publish assetlinks.json |
| D Tabletop split | STILL MISSING | STILL MISSING | `TabletopSplit` (`ui/gaps6/ReaderHooks.kt:100`) has no caller |
| D Image generation (repeat) | STILL MISSING | STILL MISSING | = AI-8 |

The "D 19b (repeat)" row is not counted. The 27 counted rows are the old list (B13 and Image gen are each counted once in B and once in D, as in the first check).

### 5. gaps/DONE_ai_office.md: open items

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| B13 slides | PARTIAL | STILL MISSING | no PPTX notes, no Spaces entry |
| B15 video summary | PARTIAL | STILL MISSING | system TTS only; untested |
| AI-8 / §35 image gen / edit | PARTIAL + NEEDS-SERVER | STILL MISSING | no prompt edit; insert not undoable; IMAGE_* proxy config (owner) |
| §35 Workspace crafts | PARTIAL | STILL MISSING | no infographics / web pages |
| §35 AI panel width | PARTIAL | STILL MISSING | width can only be cycled, not dragged (`DocAssistantSheet`) |
| Office find: PowerPoint match over two runs | limitation | STILL MISSING | still noted in OfficeFind; no fix |
| MCP | NOT-FEASIBLE | NOT-FEASIBLE | no stdio MCP on Android; no OAuth hosting |
| Local AI | NOT-FEASIBLE | NOT-FEASIBLE | AICore / MediaPipe |
| AI-off gate for the new route ids (owned by pdf-3c) | OWNED | DONE | `core/gaps6/G6Prefs.kt:13` TOOL_IDS include all six `ai_*` ids; `ToolScreens` `AiGate.allowsTool` |
| Proxy routes (vision, images, STT, embeddings, web search, usage) | NEEDS-SERVER | OWNER-ACTION | `ai-proxy/worker.js` routes are written but not deployed. The app works with the user's own key |

### 6. gaps/tabs-audit/FIXED_5_tools_pages.md: open items

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| T-01 quick actions on signed files | OWNED tab-view-core | DONE | `ReaderState.kt:913-915` `SignedGuard.isIncrementalOf/decide` in applyInPlace |
| G-01 Organize overwrite | OWNED | DONE | `OrganizeState.kt:136,445` `SignedGuard.checkWrite` |
| E-2 E5Io tools | OWNED | DONE | `core/edit5/E5Io.kt:22` `SignedGuard.checkFullSave` |
| OCR-1 quick OCR | OWNED | DONE | same gate as T-01 |
| C-1 signature part (Find & replace) | OWNED | DONE | `DocumentRepository.replaceOriginal` → `SignedGuard.checkWrite` (:275), ResultSheet `Components.kt:237` |
| C-3 signature part (Forms save in place) | OWNED | DONE | `FormsScreen.kt:126` replaceOriginal |
| C-11 convert ZIP on Main | OWNED convert-audit | DONE | `Cv6Engine` work in `withContext(Dispatchers.IO)` (:181,193,207) |
| C-15 convert cancel | OWNED | DONE | `Cv6Engine.cancelCheck` (:213) → `ConvertEngine.convert(..., cancelled)` |
| C-16 batch passwords | OWNED | DONE | `Cv6Engine.pwOf` per-file password map (:87-91) |
| C-17 Cv6Results share / ZIP crash | OWNED | DONE | runCatching around share / open (`Cv6Results.kt:37-45,82`) |
| C-24 Save to gallery failures / permission | OWNED | DONE | `Cv6Save.needsStoragePermission`, failed-copy cleanup (B28) |
| E-18 state after process death | NOT-FEASIBLE here | NOT-FEASIBLE | as stated; temp files fixed |

### 7. AUDIT/PAGES_CONVERT_LOG.md: OPEN rows

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Web: crawl N levels | OPEN | STILL MISSING | 0 hits |
| Printer-time PDF settings | OPEN | DONE | printer recipe `ShareAndPrint.kt:136-141` / `RecipesScreen.kt:240`. Append is NOT-FEASIBLE |
| Auto-detect form fields | OPEN | DONE | designer `FieldRecognition` (fx_detect) + reader flat-form targets. It does not run automatically after Create PDF (see §37) |
| DOCX: comments, text boxes, anchoring, char spacing | OPEN | STILL MISSING | `pdf_docx` options are unchanged (`Cv6Catalog.kt:43`) |
| Text: line width / discard headers & footers | OPEN | STILL MISSING | `pdf_txt` opts = RANGE, AUTO_OCR, TABLES only |
| Export a selected area as Word / Excel | OPEN | STILL MISSING | = 8b |
| Batch: per-file page range / output folder | OPEN | STILL MISSING | `Cv6Engine.outDir` = docsDir only |
| Portfolio: edit a part and save it back | OPEN | STILL MISSING | `PortfolioOps` has only compress / convert part. Parts open as copies |
| Binder: Word TOC template; Office metadata columns | OPEN | STILL MISSING | 0 hits |
| Image-editor layers palette | OPEN | DONE | `NveImageEditor.kt:120-121,196` layers with opacity, blend, visibility, merge |
| Checker page range | OPEN | STILL MISSING | = 34.2 |
| Reading order by drag; area order; touch-up | OPEN | STILL MISSING (part) | drag is DONE (pages-a11y); area / touch-up is missing |
| Table editor on page; content panel; table recognition | OPEN | STILL MISSING (part) | `AccessAutoTag.tables` is DONE; the editor and the content panel are missing |
| Setup assistant; per-document reading option | OPEN | STILL MISSING (part) | the assistant is DONE; the per-document option is missing |
| PDF/A-4 / 4e / 4f, PDF/VT | OPEN | NOT-FEASIBLE | PdfBox 2.0 cannot write PDF 2.0 (reason given in the log) |

### 8. AUDIT/PAGES_A11Y_LOG.md

| Source / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Formal PDF/UA validation | PARTIAL | NOT-FEASIBLE (the machine-check part is DONE) | `Standards.pdfUa` preflight profile in PdfAScreen. A certified (veraPDF-level) validator is NOT-FEASIBLE, as in Stage G |

### Spot-checks

1. **OK.** fc-sign cert policy: certificate tab of the Protect tool (`SecurityTools.kt` CertEncryptTab) → `CertPolicyRow` (:297) → save / apply / delete (`CertPolicyUi.kt` 19, 34, 35) → `CertPolicies.load/save/delete`.
2. **OK.** fc-sync take over: ReviewScreen "Take over" (`ReviewScreen.kt:259`) → `CollabStore.takeOver` (`CollabStore.kt:155`; refuses while `CollabLive.isAttached`) → `CommentMerge.takeOver` → publish.
3. **OK.** fc-sync 16b: Files selection bar Convert icon (`FilesComponents.kt:62`) → `FilesScreen.kt:195-196` `Cv6Batch.hand` + navigate `convert_hub` → `ConvertHubScreen.kt:91` `Cv6Batch.take`.
4. **OK.** fc-settings Ctrl+Alt+D: `NveKey.DUPLICATE_PAGE` chord (`NveKeys.kt:123`) → handler (:285-288) → `ReaderState.applyInPlace` (signed gate, undo) → `PageOps5.duplicate`.
5. **OK.** fc-settings language: Settings chips (`SettingsScreen.kt:72`, `AppLang.SYSTEM/AR/EN`) → `AppLang.apply` (:77); `MainActivity` uses `AppLang` (attachBaseContext / sync).
6. **OK.** pages-a11y Fix per rule: rule menu Fix → `AccessibilityScreen.kt:152` `id in AccessFix2.STRUCTURE_FIXES` → :157 `AccessFix2.removeNestedAlt(d)` (`AccessFix2.kt:59`) → saved as `_accessible`.
7. **OK.** pro-ocr redo: reader OCR sheet checkbox (`OcrSheet.kt:129-132`) → `OcrOptions(..., redo)` (:154) → `OcrInPlace.kt:85` `OcrRedo.strippedCopy` and :93 (all chosen pages re-OCRed).
8. **OK.** pdf39-comments-forms ink merge: comment menu item (`CommentExtrasUi.kt:201`) → `CommentExtras.cycleInkMerge` (:123, prefs ink_merge_ms) → on a new ink `InkMerge.canMerge` → `s.replaceAnnot(InkMerge.merge(...))` (:132-133).

No claim failed.

### STILL MISSING

#### High
- (none)

#### Med
- §34.39 / §34.45 / OLD 18b: area reading order and the on-page touch-up reading-order tool (tools/OCR).
- §34.46 / §40: accessibility Content panel (tools/OCR).
- §41 / OLD F4: Combine has no page thumbnails to pick, delete or reorder pages across files (pages/convert).
- §37: form-field recognition does not run after Create PDF (deferred in pdf39) (comments/forms).
- OLD B13: slides have no speaker notes in the PPTX and no Spaces entry (AI/office).

#### Low
- §34.2: accessibility checker page range (tools/OCR).
- §34.18: bookmarks from chosen structure elements (tools/OCR).
- §34.27: per-document reading option (reader/view).
- §34.38: alt text editing on the page (tools/OCR).
- §34.40: on-page display of content groups, tables and figures (tools/OCR).
- §34.41: on-page table editor (tools/OCR).
- §34.49: Find Element for artifacts, OCR suspects and unmarked content (tools/OCR).
- §35 / §41 / AI-8: prompt-based image editing (`/images/edits`) and an undoable image insert (AI/office).
- §35 / §41: Workspace infographics and web-page crafts (AI/office).
- §36: print range "current view" (reader/view).
- §37: structure tab order as a preference (comments/forms).
- §37: Action Wizard log export, and a PDF/UA report during the run (tools/OCR).
- §37 / OLD 12: custom blank page size and units; template search (pages/convert).
- §37: alert and preference for a full-screen request (reader/view).
- §37 / PREF-5: PDF/A view mode (reader/view).
- §37: changed text shown in colour while editing (edit).
- §37: per-item full-screen choices (reader/view).
- §37: facing page count and gap between pages (reader/view).
- §37: always show the page size (reader/view).
- §37: keep zoom on Go-to-Destination (reader/view).
- §39: Tab / Shift+Tab between form fields (comments/forms).
- §41: editing comments from other editors (Bluebeam) (comments/forms).
- §41: CSV comment summary column picker (comments/forms).
- §41: sequential (auto-increment) stamp (comments/forms).
- §41: pencil hides the cursor on a stylus; pencil over text highlights (comments/forms).
- §41: preflight grayscale fixup (pages/convert).
- §41: Danish in AI translation (AI/office).
- §41: volume, polylength, diameter and radius measuring (comments/forms).
- §41: page labels from bookmarks or page regions (pages/convert).
- §41: enterprise / shared stamps with an edit lock (comments/forms).
- §42: watched folder for the Action Wizard (tools/OCR).
- OLD 8b / convert log: export a selected area to Word / Excel (pages/convert).
- OLD SHL-5: tab groups; two editable documents side by side (reader/view).
- OLD K11: QR scanning in the scanner (tools/OCR).
- OLD E10: nested, mirrored or skewed XObject text editing (edit).
- OLD B15: video summary with AI voices; device test (AI/office).
- OLD gaps6: `TabletopSplit` is not wired (reader/view).
- Convert log: web capture crawl N levels (pages/convert).
- Convert log: DOCX comments, text boxes, anchoring, character spacing (pages/convert).
- Convert log: text export line width; discard headers and footers (pages/convert).
- Convert log: per-file page range and output folder in batch conversion (pages/convert).
- Convert log: edit a portfolio part and save it back (pages/convert).
- Convert log: binder Word TOC template; Office metadata columns (pages/convert).
- DONE_ai_office: drag handle for the AI panel width (AI/office).
- DONE_ai_office: PowerPoint find across two text runs (AI/office).

### Counts

The 59 rows of the "Status updates" table are not included: all were confirmed and none failed.

| Section | DONE | STILL MISSING | NOT-FEASIBLE | OWNER-ACTION | N/A |
|---|---|---|---|---|---|
| 1. §28-34 (24) | 10 | 10 | 1 | 3 | 0 |
| 2. §35-37 (34) | 20 | 14 | 0 | 0 | 0 |
| 3. §39-42 (25) | 10 | 15 | 0 | 0 | 0 |
| 4. Old plan (27) | 12 | 14 | 0 | 1 | 0 |
| 5. DONE_ai_office (10) | 1 | 6 | 2 | 1 | 0 |
| 6. FIXED_5 (12) | 11 | 0 | 1 | 0 | 0 |
| 7. PAGES_CONVERT_LOG (15) | 3 | 11 | 1 | 0 | 0 |
| 8. PAGES_A11Y_LOG (1) | 0 | 0 | 1 | 0 | 0 |
| **Total (148)** | **67** | **70** | **6** | **5** | **0** |

Some rows appear in more than one source (for example 34.2 and the checker page range in the log, AI-8 in §35 and §41, 8b, workspace crafts). They are counted per source but listed once in STILL MISSING above, which has 50 unique items (0 High, 5 Med, 45 Low).

---
