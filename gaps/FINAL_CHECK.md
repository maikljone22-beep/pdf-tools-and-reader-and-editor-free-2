# FINAL CHECK — every PARTIAL / MISSING / CODE-ONLY comparison row against main

**Checked against:** main fa724fd. The 8555d74 barcode merge is included, but it changes no row.

**Sources:**
- Nitro: `NITRO_FEATURES.md` and `cmp_*.md`, with the corrections in `verify_A.md` and `verify_B.md`.
- Foxit: `FOXIT_FEATURES.md` and `fcmp_*.md`.
- `OLD_PLAN_AUDIT.md`.

**Evidence used:**
- every `gaps/DONE_*.md` file;
- `AUDIT/NITRO_TOOLS_LOG.md` and `AUDIT/PAGES_CONVERT_LOG.md`;
- `gaps/OPEN_ALL_FILES.md`;
- the root `*_r5.md` logs;
- **the code itself**.

**How a row was judged:**
- **DONE:** the code exists **and** a UI entry reaches it (a tool id, button, menu, panel, settings row or key). Code that nothing in the UI calls counts as STILL MISSING.
- **Spot-checks:** 61 DONE claims were followed in code from button to handler to core function, and are marked "(spot-checked)" in the parts below:
  - Nitro 1–8: 12
  - Nitro 9–24: 11
  - Foxit 1–23: 13
  - Foxit 24–42 + old plan: 25

**Nothing here was tested on a device.**

## Totals

| Slice | Rows | DONE | STILL MISSING | NOT-FEASIBLE | OWNER-ACTION | N/A-ANDROID |
|---|---|---|---|---|---|---|
| Nitro §1–8 | 211 | 160 | 44 | 3 | 0 | 4 |
| Nitro §9–24 | 204 | 188 | 9 | 5 | 2 | 3 |
| Foxit §1–23 | 481 | 353 | 106 | 21 | 1 | 0 |
| Foxit §24–42 + OLD_PLAN | 630 | 472 | 125 | 23 | 9 | 1 |
| **All** | **1526** | **1173 (77%)** | **284** | **52** | **12** | **8** |

## Where the check disagrees with a DONE file
- **PAGES_CONVERT_LOG stage A, Organize per-page Redact and OCR:** these do not act on the tapped page. Redact opens the whole-document tool, and OCR only opens the reader (`OrganizeScreen.kt:203-204`). → STILL MISSING.
- **DONE_view_edit §9, reading ruler vertical / opacity / reset:** the `rulerVertical` / `rulerOpacity` settings belong to the measurement rulers. The reading band still has only on/off, dim, height and colour. → STILL MISSING.
- ~~DONE_pdf3c_nav, reading order preference: never read~~ **Correction (pdf-3c):** it is read in `ReaderAi6.orderedPage` (ReaderAi6.kt:86), which read-aloud calls; the preference is DONE. Only a *per-document* reading option (34.27) is open.
- **DONE_comments_forms 24.33, cloud scallops:** marked not feasible there, but the app draws its own cloud path (`CloudPath`). → STILL MISSING.
- **DONE_view_edit.md:** its header still says "Not merged", but it was merged in 315a67b.

Duplicates and unused code, which are not gaps:
- The recent-list size is set in two places (`NpfPrefs.RECENT_LIMIT` and `NveFolders.recentSize`); merge them.
- `NveFindScopes`, `NveFindExtraResults` and `NveHitStyleSettings` have no callers (NpfScopeHits is the one used).

## STILL MISSING — High and Med (the full Low lists are in each part)

**High**
- OLD I1: Fill & Sign on flat forms has no detected tap targets; field recognition exists only in the form designer. [comments/forms]
- OLD K2: no "redo OCR / replace the text layer" for pages that already have text. [tools/OCR]

**Med**
- **pages/convert:**
  - legacy .ppt input;
  - custom image page size;
  - PDF→Word "precise layout" mode;
  - text-recovery switch and language;
  - Excel sheet and PPT slide bookmarks;
  - Combine thumbnail page picking.
- **pages/convert compliance:**
  - preflight profiles (PDF/A-2b only);
  - PDF/A-1a / 1b;
  - verifying claimed PDF/A;
  - formal PDF/UA validation;
  - Auto Tag for tables;
  - PDF/X / E / VT.
- **accessibility:**
  - only 8 checker rules have a Fix;
  - no Order / Content panel (drag reading order).
- **comments/forms:**
  - XDP form data;
  - comment summary with connector lines or sequence numbers;
  - form-field recognition after Create PDF;
  - XFA picture clauses and scripts.
- **edit:**
  - Reflow editing across pages;
  - link destination "set this position";
  - bookmark named destination as a list;
  - automatic OCR when editing a scanned page.
- **reader/view:**
  - link "convert the linked page to PDF";
  - reorganise in the reader thumbnails panel;
  - reflow does not rebuild tables;
  - read aloud next / previous / repeat block.
- **nav/settings:**
  - conversion defaults stored per format (Settings only links to the converters);
  - "follow system" language (only Arabic / English).
- **sign/protect:**
  - certificate protection cannot be saved as a policy.
- **all areas:**
  - about 51 icon-only buttons still have `contentDescription = null` (OLD M5).
- **files:**
  - sync still polls every 60 s (no long-poll / push).

## OWNER-ACTION (12)
- **Servers and keys:** deploy the workers (collab / share / esign / connected / license), with `cloud.properties`, the Drive OAuth client and the real `ads.properties` / `license.properties`; see `SERVER_SETUP.md`.
- **E-sign:** online forms and branding need the redeployed esign worker.
- **AI:** vision chat, Create Image and formula-to-LaTeX need the proxy routes or the user's own key.
- **Google Drive:** saving to Drive as Docs / Sheets / Slides needs the Drive OAuth client.
- **Help links:** set the forum / Labs URLs (`npf_url_community`, `npf_url_labs`).
- **Licensing:** volume licensing needs the licence server.

## NOT-FEASIBLE (52, details in the parts)
Typical reasons:
- **Licensing:** AATL / Notarius roots, Microsoft MIP / AIP.
- **PdfBox 2.0 limits:** AES-GCM, linearize.
- **PDFium wrapper:** thin lines, render flags.
- **Formats with no Android library:** 3D, CAD, HWP, .doc output, JPEG 2000.
- **Tesseract has no models:** OCR-A/B / CMC7 / handprint.
- **Desktop-only:** tutorial videos, overprint simulation, drag-and-drop.

---

The four detailed parts follow. Each has its own tables, spot-checks, STILL MISSING list and counts.


# Part A — Nitro §1–8
# Final check: Nitro sections 1–8 (cmp_01-06 + cmp_07-08, with verify_A corrections)

Checkout: `D:\pdf\final-check-wt` at fa724fd (read-only). Paths below are relative to `app/src/main/java/com/smartdocs/editor/`.
Note: `origin/main` has one newer merge (8555d74, shared zxing barcode encoder). It does not change any row here; 8.9 was already DONE at fa724fd through `core/formsx/FxBarcodes.kt`.

Every row was checked in code: the symbol exists AND a UI caller reaches it. "Nothing tested on a device" applies to all DONE rows (the DONE files say so too).
The verify_A corrections (thumbnails, delete by range, Word links, 7.14 snap, 8.42 button layout) were applied first; all five rows are now DONE.

---

## 1. Application shell: Home, View and navigation

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Favorite Tools (pin any tool, reorder, Remove All, rename tab) | PARTIAL | DONE | ui/nve/shell/FavoritesUi.kt `NveFavoritesSection` (ToolsScreen.kt:183, star mode :205) + `NveHomeFavorites` (HomeScreen.kt:143); Custom tab rename `NveShellPrefs.customTabName` (ReaderToolbars.kt:70) |
| Home: Feature Guides | PARTIAL | DONE | core/nve/shell/FeatureGuides.kt (4 practice PDFs) → ui/nve/shell/HomeActions.kt `GuidesDialog`, Home `rememberNveHomeActions` (HomeScreen.kt:79) |
| Tabs: Close Other / Close All | MISSING | DONE (spot-checked) | Tab long-press/right-click → ReaderTabs.kt → `NveTabMenu` / `NveTabsMenuItems` → NveTabs.kt:52 `ReaderTabs.retain` |
| Tab groups, New Window, Cascade/Tile | PARTIAL | STILL MISSING | New window done (NveWindowActivity, Manifest :444). Missing: tab groups (DONE file: "no movable tab groups") |
| Split pane / Next-Previous pane | PARTIAL | DONE | View chip `nv_quad_split` → NveHost.kt `NveQuadSplit` (4 panes, prev/next buttons, F6) |
| Zoom: Fit Height / Actual Size / Automatic | MISSING | DONE (spot-checked) | View tab → View5Host.kt:83 `NveViewChips` → chips nv_fit_height / nv_actual_size → NveZoom.kt:67 `preset(FIT_HEIGHT/ACTUAL)`; AUTO via command search `nv_auto_zoom` |
| Zoom range 25%–6400% | PARTIAL | STILL MISSING | Numeric % (NveZoomSheet) and min 25% done (PageView.kt:45 MIN_ZOOM 0.25). Max is 32× fit-width (PageView.kt:46), ≈1000–3000% of actual size; 6400% not reached |
| Zoom marquee tool | MISSING | DONE | View chip `nv_marquee` → `NveDrag.ZOOM_IN` → NveOverlays.kt `NvePageOverlays` (ReaderScreen.kt:333) |
| Copy With Formatting | MISSING | DONE | Selection bar ReaderOverlays.kt:109 `NveSelectionChips` → NveSelection.kt:175 → `copyWithFormatting` (HTML clip) |
| Copy As Table | MISSING | DONE (spot-checked) | ReaderOverlays.kt:109 → NveSelection.kt:176 chip nv_copy_table → `NveSelectionOps.copyAsTable` → core/nve/TextStats.kt `TableCopy` (HTML + TSV) |
| Pane: Attachments | PARTIAL | DONE | View chip ReaderToolbars.kt:150 → `ReaderPanel.ATTACHMENTS` (ui/attachments/ReaderAttachments.kt) |
| Pane: Layers (OCG) | MISSING | DONE (spot-checked) | Reader INFO › Layers NpfPanels.kt:135 → tool `e5_layers` (Edit5Hub.kt:44,80) → LayersScreen.kt:133 switch → `Layers.setOn` |
| Pane: Tags | PARTIAL | DONE | INFO panel → `NveMoreFor` → NvePanes.kt:120 `TagsPane`; full editor ui/tools5/AccessTagsUi.kt `TagsEditor` (AccessibilityScreen.kt:123) |
| Pane: Signatures | PARTIAL | DONE | View chip / ⋮ `nv_pane_signatures` → NvePanes.kt:119 `SignaturesPane` → `DigitalSignature.verify` (:215) |
| Document Notifications bar | PARTIAL | DONE | ReaderScreen.kt:347 `NveDocBanners` → core/nve/panes/DocNotices.kt (FORMS, JAVASCRIPT, FONT_RESTRICTED, PDFA, PDFUA, PENDING_REDACTIONS, 3D, PORTFOLIO) |
| Grid opts (colour, offsets, subdivisions, snap, guides, units) | PARTIAL | DONE | View chip `nv_grid_title` → NveSheets.kt:140 `NveGridSheet` (nv_grid_color/offset/subdiv/snap_grid/units/guides) |
| Spell Check | PARTIAL | DONE | ui/nve/Spell.kt; whole-document check chip `nv_spell_doc` → `NveDocSpell`; SpellCheckRow in annotation dialogs |
| Auto Recovery (interval, "(Recovered)") | PARTIAL | DONE | ui/nve/shell/Recovery.kt `NveRecoveryBanner`/`NveRecoveryEffect` (ReaderScreen.kt:348,435); interval in Settings `NveShellSettingsRows` (SettingsScreen.kt:72) |
| Automatic repair of damaged files | PARTIAL | DONE | Open-error screen ReaderScreen.kt:324 `NveRepairButton` → core/nve/shell/ShellCore.kt `PdfRepair` |
| Shell "Convert to Word/Excel/PowerPoint/Text" | PARTIAL | DONE | AndroidManifest.xml:371-425 activity-aliases ConvertToWord/Excel/PowerPoint/Text (SEND/VIEW pdf) → Convert hub preselected (RTF target not aliased) |

## 2. File, Save and Document Properties

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Print: custom N×M | MISSING | DONE | ui/view5/PrintForm.kt:117-120 cols/rows fields → core/view5/PrintLayout.kt:73 |
| Print: Booklet both / front / back | MISSING | DONE | PrintForm.kt:136 `PrintLayout.Sides.BOTH/FRONT/BACK` |
| Print: auto-rotate/center, scaling | PARTIAL | DONE | PrintForm.kt:102 Scale AUTO/FIT/SHRINK/ACTUAL/CUSTOM; PrintLayout.kt:99 `fitScale(autoRotate)` |
| Print: only pages with markups | MISSING | DONE (spot-checked) | PrintSheet5.kt:75 `PrintOptionsForm` → PrintForm.kt:79 chip `markedOnly` → PrintPrep.kt:71 `PrintExtras.markupPages` |
| Batch Print | MISSING | DONE | Tools tile `batch_print` (ToolsScreen.kt:96) → ToolScreens.kt:76 `BatchPrintScreen` |
| Properties: Base URL | MISSING | DONE | Edit5 DocPropsScreen.kt:91 base URL field; core/nve/DocInspect.kt:213 |
| Properties: Page Size / Fast Web View / Locked | MISSING | DONE | DocInspect.kt:48,54 (firstPage, linearized, locked) shown in DocPropsScreen + ui/nve/file/DocInfoSheet.kt (⋮ → Properties) |
| Properties Security tab | PARTIAL | DONE | DocPropsScreen.kt:101 `NveSecurityPanel` (method, key length, permissions) |
| Properties Fonts tab | PARTIAL | DONE | DocPropsScreen.kt:102 `NveFontsPanel` ← `DocInspect.readFonts` |
| Mark the document as PDF/UA | CODE-ONLY | DONE (spot-checked) | DocPropsScreen.kt:165 → core/nve/XmpOps.kt:183 `markPdfUaFile` → :159 `markPdfUa`; also AccessibilityScreen → core/tools5/AccessUa.kt `markUa` |

## 3. Edit text and images

All rows are reached from the reader Edit tab → content edit mode (ui/edit/EditModeHost.kt:68-80 hosts `TextTools5Host`, `ImageTools5Host`, `MultiAlignBar5`, `ImageIdleBar5`; EditPanels.kt:201 `ImageTools5Chips`, :234 `InsertExtras5`).

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Edit opt: paragraph vs line selection | PARTIAL | DONE | ui/edit/TextTools5.kt nt_select_line / nt_select_paragraph |
| Edit opt: Smart Alignment | MISSING | DONE | ImageTools5.kt ni_smart_align (ImageIdleBar5), EditPageView guides |
| Text formatting: font face | PARTIAL | DONE | core/edit/EditFonts.kt + TextTools5 font picker (nt_font_bundled / system / arabic / search) |
| Text formatting: justify | MISSING | DONE | PageTextModel.kt:14 `TextAlign.JUSTIFY`; TextTools5 nt_justify |
| UPPER / lowercase | MISSING | DONE | TextTools5 nt_case_upper/lower/title/sentence |
| Character / word / line spacing | PARTIAL | DONE | TextTools5 nt_char_spacing / nt_word_spacing / nt_line_spacing |
| Horizontal scaling, baseline, stroke | MISSING | DONE | TextTools5 nt_hscale / nt_rise / nt_mode_stroke / nt_stroke_color |
| Font embedding choice + convert to system font | PARTIAL | DONE | TextTools5 nt_embed_subset/full/system, nt_to_system_font |
| Editing preferences | PARTIAL | DONE | TextTools5 nt_prefs (nt_word_distance, nt_pref_warn_fonts, nt_pref_system_fonts) |
| Add Image: from Clipboard | MISSING | DONE | ImageTools5.kt:586 `InsertExtras5` ni_paste_clip (+ reader paste NveClipboard) |
| Add Image: from camera | PARTIAL | DONE | `InsertExtras5` TakePicture (ni_camera) |
| Image properties | PARTIAL | DONE | ImageTools5.kt:1155 `PropsDialog5` |
| Image: Rotate 180° | PARTIAL | DONE | MoreSheet5 ni_rotate_180 |
| Image: Brightness/Contrast | MISSING | DONE | ImageTools5.kt:1034 `AdjustDialog5` (ni_adjust) |
| Image: colour-space convert | MISSING | DONE | ImageTools5.kt:1055 `ColorDialog5` (RGB/Gray/B&W/CMYK, no ICC) |
| Image: Resample | PARTIAL | DONE | ImageTools5.kt:1088 `ResampleDialog5` |
| Image: Flip H/V | MISSING | DONE | MoreSheet5 ni_flip_h / ni_flip_v |
| Image: Crop / W-H / Scale % / lock | MISSING | DONE (spot-checked) | EditPanels.kt:201 → MoreSheet5 ni_crop → ImageTools5.kt:912 `CropDialog5` → `t.change(ImageEdit5.Change(crop=…))`; `PositionDialog5` :861 |
| Arrange: Bring To Front / Send To Back | MISSING | DONE | MoreSheet5 ni_front/ni_back → core/edit/ImageEdit5.kt:365 `arrange` (images, shapes, shadings; text blocks NOT-FEASIBLE per DONE file) |
| Arrange: Copy/Paste objects | MISSING | DONE | ImageTools5.kt:81 `ObjectClipboard5`, ni_copy/ni_cut, `CopyTextObject5` |
| Numeric X/Y, rotation, scale | MISSING | DONE | `PositionDialog5` :861, `RotateDialog5` :839 |
| Align & distribute | MISSING | DONE | EditModeHost.kt:68 → ImageTools5.kt:546 `MultiAlignBar5` (ni_distribute_h/v) |
| Prompt to remove Layers before editing | MISSING | DONE | ImageTools5.kt:1192 `LayersPrompt5` (from `ImageTools5Host`) |

## 4. Pages: organize

Tool ids: Organize (ui/pages/OrganizeScreen.kt) and Edit5 `e5_organize` / `e5_split_bookmarks` / `e5_mix` (Edit5Hub.kt:79-82 → ui/edit5/PageOrganizeScreen.kt, ops `OrgOp`).

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Thumbnails: enlarge / reduce | MISSING | DONE | OrganizeScreen.kt:184-195 zoom buttons `cellScale` |
| Embed / remove page thumbnails | MISSING → PARTIAL (verify_A) | DONE | PageOrganizeScreen `OrgOp.THUMBS` → `embedThumbnails` / `removeThumbnails` |
| Insert from File: multiple files | MISSING | DONE | OrganizeScreen.kt:322 `NveOrganizeInsertMore` (nl_insert_files, nl_insert_folder); PageOrganizeScreen INSERT (several files, ordered) |
| Insert from Clipboard | MISSING | DONE | `NveOrganizeInsertMore` nl_insert_clipboard → `NvePaste.fromClipboard` (image or text); PageOrganizeScreen.kt:149 clipboard_pdf |
| Extract opt: delete pages after | MISSING | DONE | PageOrganizeScreen.kt:124 `deleteAfter` switch |
| Delete Pages: typed range / even / odd | MISSING → PARTIAL (verify_A) | DONE (spot-checked) | Tools → e5_hub → Edit5Hub.kt:79 `e5_organize` → PageOrganizeScreen.kt:111 `RangeSubset` → :193 `PageOrganize.delete` |
| Replace opt: import bookmarks | PARTIAL | DONE | core/edit5/PageOps5.kt:185 `NpfPrefs.importBookmarksOnReplace` → `NpfOutline.importFrom`; switch NpfSettingsScreen.kt:208 (off by default) |
| Rotate 180° | PARTIAL | DONE | PageOrganizeScreen.kt:116 ROTATE 90/180/270 |
| Rotate opt: All / Even / Odd | PARTIAL | DONE | ROTATE with `RangeSubset` (PageOrganizeScreen.kt:111) |
| Split: by bookmarks level N | PARTIAL | DONE | PageOrganizeScreen.kt:128 `level` → `PageOrganize.splitByBookmarks` (:238) |
| Split opt: keep navigation / fields | MISSING | DONE | PageOrganizeScreen.kt:125,132 `keepNav` → PageOrganize.kt:289-298 `keepNavigation` |
| Split opt: filename suffix pattern | PARTIAL | DONE | PageOrganizeScreen.kt:130-131 `PartName` NUMBER/PADDED/LETTER/RANGE |
| Split opt: destination folder | PARTIAL | STILL MISSING | Output is always `app.documents.docsDir`. Only a global "default save folder" copy exists (core/files/NpfSaveFolder.kt); no per-operation folder choice |
| Resize: A2 / A1 presets | PARTIAL | DONE | core/edit5/PageBoxOps.kt:109-113 `PAPERS` A0-A2 |
| Resize: Reset to original size | PARTIAL | STILL MISSING | Reset is only `doc.undo()` inside the open screen (CropResizeScreens.kt:169-170); no reset after the file is saved |
| Redact pages from page context menu | PARTIAL | STILL MISSING | OrganizeScreen.kt:203 pc_page_redact opens the `redact` tool for the whole document; the tapped page is not passed and not redacted |
| OCR pages from page context menu | PARTIAL | STILL MISSING | OrganizeScreen.kt:204 pc_page_ocr only opens the reader at that page; OCR is not started (user must then use ⋮ "OCR this page") |
| Print Pages from thumbnails | PARTIAL | DONE (spot-checked) | OrganizeScreen.kt:196 `pageActions` → pc_page_print → `selectedToTemp` → `PrintHelper.printPdf` |
| Remove related bookmarks when deleting | MISSING | DONE | core/pdf/PdfOps.kt:148-151 → `PageOrganize.deleteIn(removeBookmarks = NpfPrefs.removeBookmarksOnDelete)`; switch NpfSettingsScreen.kt:207 |

## 5. Combine / Create PDF

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Create PDF from PowerPoint (legacy .ppt) | PARTIAL | STILL MISSING | core/convert6/Cv6Catalog.kt:87 `any_pdf` still excludes `Kind6.PPT`; ppt_pdf accepts PPTX/ODP only (:39,77). A PPT text reader exists for viewing only |
| Publisher / Visio / WordPerfect | MISSING | NOT-FEASIBLE | AUDIT/PAGES_CONVERT_LOG.md stage B: no pure-Java reader for these formats |
| Dialog: add folder / output folder | PARTIAL | STILL MISSING | Add folder DONE (CombinePlusUi pc_add_folder). Missing: output folder choice (only global NpfSaveFolder copy) |
| Create from Clipboard: image | MISSING | DONE | Home paste → ui/nve/shell/HomeActions.kt:50 `fromClipboard` (image/*); To-PDF+ Combine pc_add_clipboard |
| Combine opt: page range per file | MISSING | DONE | Tools `to_pdf_plus` (ToolScreens.kt:61) → ToPdfPlusScreen.kt:69 `CombineTab` page_range → `CombineEntry.pages` |
| Conversion quality presets (Web/Office/Print) | PARTIAL | STILL MISSING | No named presets; only Opt.QUALITY slider and compress levels |
| Custom: font embedding Subset/Full/none | MISSING | STILL MISSING | No option in Cv6 Opt enum (Cv6Catalog.kt:12-13) or ToPdfPlus |
| Custom: image downsample / codec | PARTIAL | STILL MISSING | Only JPEG quality; no codec or downsample-type choice |
| Page size for image sources: custom size | PARTIAL | STILL MISSING | Presets only; no custom size in Images6 / Cv6OptionsSheet |
| Page size for text sources | MISSING | STILL MISSING | text_pdf / md_pdf opts = MERGE only (Cv6Catalog.kt:79-80) |
| PDF/A-1b at creation | PARTIAL | STILL MISSING | core/tools5/PdfA.kt converts to PDF/A-2b only, as a separate step |
| Attach the source file | MISSING | DONE | ToPdfPlusScreen nat_attach_source (DONE_pdf3c_nav: To-PDF+, convert hub, text→PDF) |
| Word: links, headings → bookmarks | MISSING → PARTIAL (verify_A) | DONE | To-PDF+ Combine `pc_headings_bookmarks` → CombinePlus.kt `CombineOpts.headings` → `OutlineTools.detectHeadings`; links kept by WebView print (not runtime-tested). Not offered on hub `word_pdf` |
| Excel: bookmark per sheet, fit to page | PARTIAL | STILL MISSING | No per-sheet bookmark or fit option (ToPdf6/ToPdf) |
| PowerPoint: bookmark per slide, notes, frames | PARTIAL | STILL MISSING | Only handouts (pptx_handouts); no slide bookmarks, notes layout, frames |
| HTML: header/footer, background colour | MISSING | STILL MISSING | Web-page path has background graphics on/off (WebCss, pc_web_backgrounds); no header/footer text for HTML/web |
| Publisher / Outlook settings | PARTIAL | NOT-FEASIBLE | EML→PDF exists; Publisher has no reader (stage B log) |
| Printer: target / overwrite / append | PARTIAL | STILL MISSING | PAGES_CONVERT_LOG stage B "Printer-time PDF settings: OPEN"; core/tools5/ShareAndPrint.kt has no options |
| Printer: PDF version / PDF/A-1b | MISSING | STILL MISSING | Same (OPEN) |
| Printer: quality presets, grayscale | PARTIAL | STILL MISSING | Same (OPEN) |
| Printer: PDF properties | MISSING | STILL MISSING | Same (OPEN) |
| Printer: PDF security | MISSING | STILL MISSING | Same (OPEN) |
| Printer: open after creation | PARTIAL | STILL MISSING | ShareAndPrint.kt:162-165 notification opens the file on tap; no auto-open option |
| Scan profiles | PARTIAL | DONE | ui/tools5/ScanStudioScreen.kt:93 `ScanPreset` save/apply/delete; ui/nve/shell/ScanPresetsUi.kt |
| Scan opt: manual duplex | PARTIAL | DONE | ScanStudioScreen.kt:218 duplex card → `ScanExtras.duplex` |
| Scan opt: PDF/A-1b | PARTIAL | STILL MISSING | No PDF/A in the scanner; only the separate PDF/A-2b tool |

## 6. Convert from PDF

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| PDF to Word: .doc (2003) | MISSING | NOT-FEASIBLE | PAGES_CONVERT_LOG stage C: binary format, no writer (RTF export exists) |
| Word layout: "precisely laid out" text boxes | PARTIAL | STILL MISSING | Stage C "OPEN"; pdf_docx has LAYOUT keep/flowing only |
| Word: custom character spacing | MISSING | STILL MISSING | Stage C OPEN |
| Word images: anchoring | PARTIAL | STILL MISSING | Stage C OPEN; only INCLUDE_IMAGES |
| Word tables: threshold / font adjust | MISSING | STILL MISSING | Opt has TABLES on/off only |
| Headers/footers: convert / delete / don't detect | PARTIAL | STILL MISSING | Always detected; no toggle |
| Advanced text recovery option (+ language) | PARTIAL | STILL MISSING | Core now automatic: PdfFlow6.kt:91-102 always converts a `RepairedInput` copy (PDF_TO_WORD_r5). Missing: all / problem-only / off switch and a recovery-language setting (pdf_docx opts have no OCR_LANG) |
| Excel: 2003 XML | MISSING | DONE | Cv6Catalog.kt:68 `pdf_xml2003` (Xml2003, SHEET_PER_TABLE) in Convert hub |
| Excel opt: split multi-line text into rows | MISSING | STILL MISSING | No option |
| Excel opt: reduce merged cells | MISSING | STILL MISSING | No option |
| Text opt: paragraphs vs N chars per line | MISSING | STILL MISSING | Stage C OPEN; pdf_txt opts RANGE/AUTO_OCR/TABLES (Cv6Catalog.kt:49) |
| Text opt: discard headers/footers | PARTIAL | STILL MISSING | Stage C OPEN |
| PDF to Images: TIFF / multi-page TIFF | MISSING | DONE | Cv6Catalog.kt:60 `pdf_tiff` → TiffWriter (colour/grey/1-bit) |
| Extract images: output format choice | PARTIAL | STILL MISSING | core/convert6/ImageExtract6.kt:55-65 native JPEG or PNG; no format choice |
| Extract images: open folder after | PARTIAL | N/A-ANDROID | Comparison: opening a system folder does not apply; results sheet opens/shares files |
| Batch: output folder | PARTIAL | STILL MISSING | Stage C OPEN ("per-file page range / output folder in batch") |
| Batch: page range per file | PARTIAL | STILL MISSING | Stage C OPEN |
| Explorer context-menu conversions | PARTIAL | DONE | Manifest activity-aliases ConvertToWord/Excel/PowerPoint/Text (see section 1) |

## 7. Comments / Annotations / Review

Entry points: Comment tab creation bar `CreationExtrasRow` (ReaderToolbars.kt), comment menu `CommentExtrasMenuItems` (Annot2Toolbar.kt), selection `SelectionMoreButton` (AnnotEditUi.kt, MultiActionBar.kt), all in ui/reader/annot/CommentExtrasUi.kt → `Annot2Host.cx` (CommentExtras.kt) → core/annot/CommentOps7.kt / AnnotStyle.kt.

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 7.8 Markups connect to pop-up notes | PARTIAL | DONE | core/annot/AnnotStyle.kt `addPopup` (called from Annot2Writer) |
| 7.10 Pop-up open/close, reset location | PARTIAL | STILL MISSING | Open all / close all / reset in comment menu (CommentExtrasUi.kt:199-201 → CommentOps7.setPopupsOpen/resetPopups). Missing: a floating pop-up window (notes open in a dialog) |
| 7.14 Typewriter smart alignment / snap to grid | MISSING → PARTIAL (verify_A) | DONE | ui/view5/Workbench.kt:127 snap toggle for every tool + grid; `WorkbenchCanvas.resolve` |
| 7.23 Fill colour (rectangle, oval) | PARTIAL | DONE | CommentExtrasUi.kt CreationExtrasRow `shapes = RECT \|\| OVAL` an_fill → /IC |
| 7.26 Dash styles on creation bar | PARTIAL | DONE | CreationExtrasRow cx_dash (`h.dash`) |
| 7.27 Cloudy 1–3 on any shape | PARTIAL | STILL MISSING | Cloud polygon 1-3 and cloudy rectangle done (cx_cloud for RECT). Missing: cloudy oval |
| 7.28 No Border | MISSING | DONE | CreationExtrasRow cx_no_border (`h.noBorder`, /BS /W 0, filled shapes) |
| 7.30 Leader length / offset / extension | MISSING | DONE (spot-checked) | Workbench.kt:135 DISTANCE chip → WorkbenchDialogs.kt:241 `LeaderDialog` → core/view5/MeasureWriter.kt:97 `AnnotStyle.setLeader` (/LL /LLE /LLO) |
| 7.35 Standard stamps (11 missing) | PARTIAL | DONE | ui/stamps/StampPickerSheet.kt:54-64 (EXPERIMENTAL, FOR COMMENT, TOP SECRET, INFORMATION ONLY …) |
| 7.38 Custom stamp from PDF page | MISSING | DONE | ui/view5/StampPicker5.kt / StampExtras.kt `StampSources.fromPdfPage` |
| 7.40 Stamp width/height, ratio lock | PARTIAL | DONE | ReaderScreen → CommentExtrasUi.kt:405 `PlacementBar` (fields :440-442, cx_keep_ratio :444) |
| 7.41 Recent / collections / profiles | PARTIAL | DONE | StampPicker5.kt:126-127 favourites + recents (`StampMeta`), categories |
| 7.42 Stamp into page (one stamp) | PARTIAL | DONE | Selection menu cx_flatten (CommentExtrasUi.kt:161) → CommentExtras.flatten → CommentOps7.flatten |
| 7.47 Page scale per page / per document | PARTIAL | DONE | core/view5/ScaleStore.kt (/VP); cx_doc_scales in Workbench dialogs |
| 7.48 Multiple-scale warning | MISSING | DONE | R.string.cx_multi_scale_warn (WorkbenchDialogs) |
| 7.51 Scale description | PARTIAL | DONE | V5State.kt:62 `scaleDescription`; Workbench.kt:131 |
| 7.52 Author per annotation | PARTIAL | DONE | cx_props → CommentExtras.openProps/applyProps → `CommentOps7.setProps(Props(author…))` |
| 7.53 Subject | PARTIAL | DONE | same Props dialog |
| 7.55 Description / Name | PARTIAL | DONE | same Props dialog (setName, setContents) |
| 7.58 Locked | MISSING | DONE | CommentExtrasUi.kt:151-152 cx_lock/cx_unlock → `cx.setLocked`; `refuseLocked` |
| 7.63 Expand / Collapse All | PARTIAL | DONE | ui/reader/annot/CommentsPanel.kt expand/collapse all |
| 7.64 Sort by Type | PARTIAL | DONE | CommentsPanel.kt:61 `SortBy.TYPE` (+ STATUS, COLOR, CHECKED) |
| 7.65 Hide Comments / show all | MISSING | DONE (spot-checked) | Annot2Toolbar → `CommentExtrasMenuItems` (CommentExtrasUi.kt:186) → `cx.toggleHidden` → PageCanvas.kt:93 `showAnnots = !cx.hidden`; AnnotEdit.kt:89. Limitation: PDFium also hides form appearances |
| 7.67 Flatten selection | MISSING | DONE | `SelectionMoreButton` cx_flatten → CommentOps7.flatten(page, indices) |
| 7.70 Summary options | PARTIAL | DONE | CommentExtrasUi.kt:459 `SummaryOptionsFields` |
| 7.71 Print with comments summary | PARTIAL | DONE | ui/view5/PrintSheet5.kt:60,76 summary + `SummaryOptionsFields` |
| 7.72 Print sort orders | PARTIAL | DONE | SummaryOptions sort (PrintSheet5) |
| 7.73 My Identity (unit, e-mail) | PARTIAL | STILL MISSING | {unit}/{email} added to stamp identity (StampPicker5.kt:266). Missing: one shared identity; comment author is still a separate Settings name |
| 7.74 Pop-up note font, size, opacity prefs | MISSING | DONE | CommentExtrasUi.kt:228-238 note prefs dialog → `cx.saveNotePrefs` (note_font, note_opacity) |
| 7.76 Align / distribute annotations | MISSING | DONE | `SelectionMoreButton` align menu (CommentExtrasUi.kt:171) → `cx.align(mode)` |

## 8. Forms

Entry: Tools `form_builder` (ToolsScreen.kt:63) and reader Edit tab chip `fx_edit_fields` (ReaderToolbars.kt:209) → ToolScreens.kt:98 → ui/forms/FormBuilderScreen.kt:11 → ui/formsx/FormDesignerScreen.kt; properties ui/formsx/FieldPropsDialog.kt (tabs general / appearance / options / format / validate / calculate / actions / position / signed), actions ui/formsx/ActionsEditor.kt; logic core/formsx/*. Fill-time runtime: ui/forms5/*, core/forms5/*.

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 8.9 Barcode field | MISSING | DONE | core/formsx/FxBarcodes.kt; FieldPropsDialog fx_symbology / fx_xdim / fx_yx / fx_ecc / fx_bc_* |
| 8.12 Visibility | PARTIAL | DONE | fx_vis_visible/hidden/noprint/hiddenprint → `FxAppearance.annotFlags` |
| 8.13 Read Only | PARTIAL | DONE | fx_readonly → `FxEditor.writeProps` |
| 8.15 Rotation | PARTIAL | DONE | fx_rotation (/MK /R) |
| 8.16 Locked | MISSING | DONE | fx_locked → `FxEditor.setLocked` |
| 8.17 Edit / move / delete existing field | CODE-ONLY | DONE (spot-checked) | form_builder → FormBuilderScreen.kt:11 → FormDesignerScreen → DesignerState.kt:150 `FxEditor.readAll`; drag/resize FormDesignerScreen.kt:416-425,492 `FxEditor.setRect`; delete :502 `FxEditor.deleteWidget` |
| 8.18 Appearance font, size, colour | MISSING | DONE | fx_font / fx_font_size / fx_text_color |
| 8.19 Border/fill colour, thickness, style | MISSING | DONE | fx_border_color / fx_fill_color / fx_thin..thick / fx_style_* |
| 8.20 Appearance Gallery | MISSING | DONE | fx_gallery_* + fx_gallery_save (FxMerge.preset, FxPrefs) |
| 8.21 Text alignment | PARTIAL | DONE | fx_text_align (/Q) |
| 8.24 Scroll long text | MISSING | DONE | fx_scroll (DoNotScroll) |
| 8.25 Check spelling | N/A-ANDROID | N/A-ANDROID | (fx_spell flag exists too) |
| 8.27 Comb | PARTIAL | DONE | fx_comb + fx_maxlen |
| 8.28 Allow rich text | MISSING | STILL MISSING | Flag can be set (fx_richtext). Missing: rich-text editing at fill time; values stay plain text (DONE_comments_forms PARTIAL) |
| 8.29 Password / File select | PARTIAL | DONE | fx_password / fx_fileselect; fill-time file picker in FormRuntimeUi |
| 8.30 Checkbox style | MISSING | DONE | fx_cs_check/circle/cross/diamond/square/star |
| 8.31 Checkbox export value | MISSING | DONE | fx_export_value |
| 8.32 Checked by default | CODE-ONLY | DONE | fx_checked_default (/DV /V /AS) |
| 8.33 Radio style | MISSING | DONE | same check-style chips |
| 8.35 Radios in unison | PARTIAL | DONE | fx_unison |
| 8.36 Combo/List export values | PARTIAL | DONE | fx_item_display / fx_item_export |
| 8.37 Up/Down, Sort | PARTIAL | DONE | fx_up / fx_down / fx_sort |
| 8.38 Allow custom text | PARTIAL | DONE | fx_editable |
| 8.39 Multiple selection | PARTIAL | DONE | fx_multiselect |
| 8.40 Commit immediately | MISSING | DONE | fx_commit |
| 8.41 Selection-change script | MISSING | DONE | fx_sel_change_js (/AA /K) |
| 8.42 Push button layouts | MISSING → PARTIAL (verify_A) | DONE | fx_layout_0..6 (/MK /TP) |
| 8.43 Behaviour / states | MISSING | DONE | fx_hl_none/push/outline/invert, fx_rollover, fx_down_caption |
| 8.45 Button icon + placement | PARTIAL | DONE | fx_icon_choose / fx_icon_clear / fx_icon_scale / fx_scale_* / fx_proportional |
| 8.47 Number format | PARTIAL | DONE | fx_fmt_number, fx_decimals, fx_sep_style, fx_currency, fx_neg_style |
| 8.48 Percentage | PARTIAL | DONE | fx_fmt_percent |
| 8.49 Date format | PARTIAL | DONE | fx_fmt_date / fx_date_pattern |
| 8.50 Time | PARTIAL | DONE | fx_fmt_time / fx_time_style |
| 8.51 Special | PARTIAL | DONE | fx_special_zip/zip4/phone/ssn, fx_fmt_mask |
| 8.52 Custom format / keystroke | PARTIAL | DONE | fx_format_js / fx_keystroke_js |
| 8.53 Validate | PARTIAL | DONE | fx_val_range (min/max) / fx_val_custom |
| 8.54 Calculate SUM…MAX | PARTIAL | DONE | fx_calc_simple + fx_op_* + fx_pick_fields |
| 8.55 SFN | PARTIAL | DONE | fx_calc_sfn |
| 8.56 Custom calc script | PARTIAL | DONE | fx_calc_custom |
| 8.57 Calculation order | PARTIAL | DONE | Designer calculation-order dialog (/CO) |
| 8.58 Auto-calc pref | PARTIAL | DONE | ui/settings/FormRunSettings.kt:60 f8_set_auto_calc (FormPrefs) |
| 8.59 Mouse Up | PARTIAL | DONE | ActionsEditor fx_tr_up; runtime PdfActions.triggers |
| 8.60 Down / Enter / Exit / Focus / Blur | MISSING | DONE | fx_tr_down/enter/exit/focus/blur (`FxActions.writeTriggers`) + runtime |
| 8.61 Go to page / named dest / other doc | MISSING | DONE | fx_act_goto / fx_dest_name / fx_act_remote; runtime GoTo/GoToR |
| 8.62 Open web link | PARTIAL | DONE | fx_act_url |
| 8.63 Open file (Launch) | N/A-ANDROID | N/A-ANDROID | security; stripped in PDF/A |
| 8.64 Run JavaScript | PARTIAL | DONE | fx_act_js; runtime `FormScripts.action` |
| 8.65 Show/Hide | PARTIAL | DONE | fx_act_showhide; runtime Hide action |
| 8.66 Reset form | PARTIAL | DONE | fx_act_reset |
| 8.67 Submit form | PARTIAL | DONE | fx_act_submit / fx_submit_fmt |
| 8.68 Import form data action | MISSING | DONE | fx_act_import; runtime via FormData |
| 8.69 Named / OCG / chained actions | PARTIAL | STILL MISSING | Authoring done (fx_act_named / fx_act_layers / ordered list → /Next); runtime chain `FormActions.runChain`. Missing: named actions other than page nav / Print / Save show "not supported" at fill time |
| 8.70 Submit HTTP/e-mail, formats, flags | PARTIAL | DONE | fx_canonical / fx_include_annots / fx_include_empty / fx_get; core/forms5/FormSubmit.kt:118 `canonicalDates`, :131 `fdfWithAnnots` |
| 8.71 HTML response viewer | MISSING | DONE | ui/forms5/FormRuntimeUi.kt WebView (JS off) |
| 8.72 Signature field lock on sign / script | MISSING | DONE | fx_sig_all/include/exclude/script → core/formsx/FxSigLocks.kt; FieldMDP written at signing by `SigFields.applyLock` (DONE_pdf3c_protect_sign) |
| 8.73 Form data FDF/XFDF/XML/XDP | PARTIAL | STILL MISSING | FDF / XFDF / XML / CSV / TXT done (core/formsx/FormData.kt:25 `DataFormat`; designer, FormsScreen.kt:210, FormScriptTools). Missing: XDP |
| 8.75 FormFlow99 (.xfd) | N/A-ANDROID | N/A-ANDROID | legacy Windows format |
| 8.76 Collect returned forms | MISSING | DONE | FormDesignerScreen.kt:144 `FormData.collect` ("Combine forms to a sheet") |
| 8.77 Tab order manual | PARTIAL | DONE | FormDesignerScreen.kt:232 fx_tabs_menu → `FxEditor.setManualTabOrder` |
| 8.78 Make copies | MISSING | DONE | FormDesignerScreen.kt:274 `FxEditor.makeCopies` |
| 8.79 Inherit / set default | MISSING | DONE | FormDesignerScreen.kt:289 `FxEditor.inherit`; FxPrefs defaults |
| 8.80 Align / distribute / size | MISSING | DONE | FormDesignerScreen.kt:521-531 `FxLayout.Align/Size`, fx_distribute_h/v |
| 8.81 Properties on tap | PARTIAL | DONE | double-tap / Properties for existing fields and multi-select (fx_props_multi, FxMerge.apply) |
| 8.82 Highlight / required colours | PARTIAL | DONE | Settings colour rows (FormRunSettings) used by InPageFormLayer |
| 8.84 JS doc API | PARTIAL | STILL MISSING | Many calls are real now (submitForm, mailDoc, exportAsFDF, importAnFDF, flattenPages, getPageNthWord …). Still stubs / "not supported": saveAs, closeDoc, addField, addScript, scroll, syncAnnotScan, exportDataObject, setLock; removeField only hides |

---

## Spot-checks (call chain followed button → handler → core)

1. Fit height (s1): View5Host.kt:83 `NveViewChips` → chip nv_fit_height → NveZoom.kt:67 `preset(FIT_HEIGHT)`.
2. Copy as table (s1): ReaderOverlays.kt:109 `NveSelectionChips` → NveSelection.kt:176 → `copyAsTable` → core/nve/TextStats.kt `TableCopy`.
3. Close others (s1): ReaderTabs.kt → `NveTabMenu` → NveTabs.kt:52 `ReaderTabs.retain`.
4. Layers pane (s1): NpfPanels.kt:135 → `Routes.tool("e5_layers")` → Edit5Hub.kt:80 `LayersScreen` → :133 `Layers.setOn`.
5. Print only pages with markups (s2): PrintSheet5.kt:75 → PrintForm.kt:79 `markedOnly` → PrintPrep.kt:71 `PrintExtras.markupPages`.
6. Mark PDF/UA (s2): DocPropsScreen.kt:165 → XmpOps.kt:183 `markPdfUaFile` → :159 `markPdfUa`.
7. Crop image (s3): EditPanels.kt:201 `ImageTools5Chips` / MoreSheet5 ni_crop → ImageTools5.kt:912 `CropDialog5` → `ImageEdit5.Change(crop)`.
8. Delete by range/odd/even (s4): Edit5Hub.kt:79 → PageOrganizeScreen.kt:111 `RangeSubset` → :193 `PageOrganize.delete`.
9. Print from thumbnail (s4): OrganizeScreen.kt:196 `pageActions` → pc_page_print → `PrintHelper.printPdf`.
10. Hide comments (s7): `CommentExtrasMenuItems` (CommentExtrasUi.kt:186) → `cx.toggleHidden` → PageCanvas.kt:93.
11. Leader lines (s7): Workbench.kt:135 → WorkbenchDialogs.kt:241 `LeaderDialog` → MeasureWriter.kt:97 `AnnotStyle.setLeader`.
12. Edit existing form fields (s8): ToolsScreen.kt:63 / ReaderToolbars.kt:209 → FormBuilderScreen.kt:11 → FormDesignerScreen → `FxEditor.readAll` / `setRect` / `deleteWidget`.

Spot-check findings that lowered a DONE claim: the Organize per-page "Redact" and "OCR" items (PAGES_CONVERT_LOG stage A claims DONE) do not act on the tapped page. Redact opens the redact tool for the whole document. OCR only opens the reader at that page.

---

## STILL MISSING (by importance)

### Med
- s5 Create PDF from legacy .ppt: `any_pdf`/`ppt_pdf` exclude Kind6.PPT. (pages/convert)
- s5 Image→PDF custom page size: presets only. (pages/convert)
- s6 PDF→Word "precisely laid out" text-box mode: only keep-layout / flowing. (pages/convert)
- s6 Advanced text recovery option: the repair is automatic now, but there is no all / problem-only / off switch and no recovery language. (pages/convert)
- 8.73 Form data XDP: FDF/XFDF/XML/CSV/TXT done, XDP missing. (comments/forms)

### Low
- s1 Tab groups (new window done). (reader/view)
- s1 Zoom to 6400%: max is about 1000–3000% of actual size. (reader/view)
- s4 Split destination folder: no per-operation folder choice. (pages/convert)
- s4 Resize "reset to original size": undo only, in the open screen. (pages/convert)
- s4 Redact from a thumbnail: opens the redact tool without the page. (pages/convert)
- s4 OCR from a thumbnail: opens the reader only, no OCR. (pages/convert)
- s5 Choose the output folder when creating/combining (add folder done). (pages/convert)
- s5 Named quality presets. (pages/convert)
- s5 Font embedding options. (pages/convert)
- s5 Image codec / downsample type options. (pages/convert)
- s5 Page size for text sources. (pages/convert)
- s5 PDF/A-1b at creation (only a separate PDF/A-2b step). (pages/convert)
- s5 Excel: bookmark per sheet, fit to page. (pages/convert)
- s5 PowerPoint: bookmark per slide, notes layout, frames. (pages/convert)
- s5 HTML: header/footer text (background exists for web pages). (pages/convert)
- s5 Printer: append / overwrite target. (pages/convert)
- s5 Printer: PDF version / PDF/A. (pages/convert)
- s5 Printer: quality presets. (pages/convert)
- s5 Printer: PDF properties. (pages/convert)
- s5 Printer: PDF security. (pages/convert)
- s5 Printer: auto-open after creation (notification only). (pages/convert)
- s5 Scanner: PDF/A output. (tools/OCR)
- s6 Word: custom character spacing. (pages/convert)
- s6 Word: image anchoring. (pages/convert)
- s6 Word: complex-table threshold. (pages/convert)
- s6 Header/footer delete / skip-detection toggle (Word). (pages/convert)
- s6 Excel: split multi-line text into rows. (pages/convert)
- s6 Excel: reduce merged cells. (pages/convert)
- s6 Text: line width / paragraph mode. (pages/convert)
- s6 Text: discard headers/footers. (pages/convert)
- s6 Extract images: output format choice. (pages/convert)
- s6 Batch: output folder. (pages/convert)
- s6 Batch: page range per file. (pages/convert)
- 7.10 Floating pop-up note window (open/close/reset done). (comments/forms)
- 7.27 Cloudy oval. (comments/forms)
- 7.73 One shared identity: the comment author is separate from the stamp identity. (comments/forms)
- 8.28 Rich text editing at fill time (flag only). (comments/forms)
- 8.69 Fill-time named actions other than page / Print / Save. (comments/forms)
- 8.84 JS doc API: saveAs, closeDoc, addField, addScript, scroll, syncAnnotScan, exportDataObject and setLock are still stubs. (comments/forms)

---

## Counts (211 rows that were PARTIAL / MISSING / CODE-ONLY / N/A in the comparisons)

| Section | Rows | DONE | STILL MISSING | NOT-FEASIBLE | OWNER-ACTION | N/A-ANDROID |
|---|---|---|---|---|---|---|
| 1 Shell / View | 20 | 18 | 2 | 0 | 0 | 0 |
| 2 File / Properties | 10 | 10 | 0 | 0 | 0 | 0 |
| 3 Edit text & images | 23 | 23 | 0 | 0 | 0 | 0 |
| 4 Pages | 19 | 15 | 4 | 0 | 0 | 0 |
| 5 Combine / Create | 26 | 6 | 18 | 2 | 0 | 0 |
| 6 Convert from PDF | 18 | 3 | 13 | 1 | 0 | 1 |
| 7 Comments | 30 | 27 | 3 | 0 | 0 | 0 |
| 8 Forms | 65 | 58 | 4 | 0 | 0 | 3 |
| **Total** | **211** | **160** | **44** | **3** | **0** | **4** |

# Part B — Nitro §9–24
# Final check: Nitro sections 9–24 (cmp_09-15.md, cmp_16-24.md, with verify_B corrections)

Checked against the worktree `D:\pdf\final-check-wt` at fa724fd (main). Paths are relative to `app/src/main/java/com/smartdocs/editor/`. Only rows that were PARTIAL, MISSING or CODE-ONLY in the comparisons are listed. Three N/A-ANDROID rows that claimed a missing Android equivalent (verify_B) are also included.

Sources of DONE claims: `AUDIT/NITRO_TOOLS_LOG.md` (§10, 12, 13, 14, 15), `gaps/DONE_pdf3c_protect_sign.md` (§9, 11), `gaps/DONE_pdf3c_nav.md` (§16, 17, 21, 22, 23, 24), `AUDIT/PAGES_CONVERT_LOG.md` (§18, 19; stage G PDF/A is **not** merged), and `gaps/DONE_view_edit.md` (PDF/UA, banners, editing preferences).

Spot-checks (button → handler → core) are marked **(spot-checked)**. There are 11. Nothing was run on a device.

---

## §9 Protect

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 40-bit ARC4 | MISSING | DONE | `EncAlgo.RC4_40` + `Rc40Warning`, `ui/security/ProtectAdvancedScreen.kt:130,237` (Tools → `protect_advanced`) |
| Cert security: recipients from Trusted Contacts | PARTIAL | DONE | `ui/tools/SecurityTools.kt:312-314` (picker on `TrustedCerts.list`) (Tools → `cert_protect`) |
| Security Profiles | MISSING | DONE | `core/redact/SecurityProfiles.kt`; `ui/security/SecurityProfilesUi.kt` `SecurityProfilesDialog`, opened from `ProtectAdvancedScreen.kt:185` |
| Trusted Contacts: request cert by e-mail | MISSING | DONE | `TrustedCertsScreen.kt:59` → `CertExportUi.requestCertificateByMail` (+ `OwnPublicCert`) |
| Trusted Contacts: export | MISSING | DONE | `TrustedCertsScreen.kt:60,91` → `CertExportDialog` → `core/sign/CertExport` |
| My Digital IDs: create self-signed (OU, country, usage, key, validity, several IDs) | PARTIAL | DONE **(spot-checked)** | Digital sign → Certificate tab: `ui/signature/CertificateTab.kt:221-244` (OU, country, usage chips, RSA/EC/Ed25519, 1–30 years) → `generate()` :125 → `DigitalIds.create`; ID list :160-175 |
| Digital ID export (.p12 / .cer, share) | MISSING | DONE | `CertificateTab.kt:182-195` (export .p12/.cer, share) |
| Digital ID: view certificate details | PARTIAL | DONE | `CertificateTab.kt:148-151` → `CertDetailsBlock` (`TrustedCertsScreen.kt:102-116`, includes MD5; `CertInspector.kt:75`) |
| Manage Internet Access | MISSING | DONE | `core/security/DocSecurity.kt` `NetMode` / `InternetAccess`; Settings → `NpSecurityCard` (`ui/settings/NpSecuritySettings.kt:46`), host list in `Np2TrustSettings.kt:135-139`; enforced in `ui/security/DocSecurityGate.kt:75` |
| File-launch warnings / block executables | PARTIAL | DONE | `ui/edit5/ReaderLinks.kt:121-128` → `DocSecurityGate.confirmLaunch` |
| Disable JavaScript preference | MISSING | DONE | Settings `NpSecuritySettings.kt:36-42` (`TrustPrefs.setJsLevel`); used by `FormFillState.kt:316`, `ReaderGuard.kt:71` |
| (N/A row) Windows store: Android KeyChain equivalent | N/A (equiv. MISSING) | N/A-ANDROID (Android equivalent DONE) | `CertificateTab.kt:200-211` → `DigitalIds.chooseKeyChain` / `loadKeyChain` |

## §10 Redaction, whiteout, sanitize

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Redaction code sets + Codes Editor (new set / rename / remove) | PARTIAL | DONE | `ui/security/RedactStyleSheet.kt:118-160` `CodesEditor` (set menu, new, rename, remove, import/export) → `RedactPrefs.codeSets` |
| Overlay text: font | MISSING | DONE | `RedactStyle.font` (`core/redact/RedactMarks.kt:37`); font chips `RedactStyleSheet.kt:83` |
| Overlay text: case | MISSING | DONE | `RedactStyle.textCase` (`RedactMarks.kt:38-41`); chips `RedactStyleSheet.kt:89` |
| Sanitize: embedded search indexes | PARTIAL | DONE | `SanitizeItem.SEARCH_INDEX` (`core/redact/Sanitizer.kt:21,143,317`); label `SanitizeScreen.kt:148` |
| Sanitize: document structure (tags) | PARTIAL | DONE | `SanitizeItem.STRUCTURE` (`Sanitizer.kt:145,319`); opt-in with a note, `SanitizeScreen.kt:112,149` |
| Notification of pending redaction overlays | PARTIAL | DONE | Reader banner `ui/search/ReaderSearchExtras.kt:120` `RedactMarksBanner`, shown at `ReaderScreen.kt:344` (Apply → `redact` tool) |

## §11 Sign

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Reason preset list | PARTIAL | DONE | `ui/signature/DigitalSignScreen.kt:76` `REASONS`, used at :297 |
| Contact info | CODE-ONLY | DONE | Field `DigitalSignScreen.kt:302`, passed to `SignOptions(…, contact)` :207,237; shown in verify :604 |
| Visible signature placement | PARTIAL | DONE | `DigitalSignScreen.kt:408` → `SigPlacementDialog` (`ui/signature/SigPlacement.kt:60`: drag / resize / draw) |
| Lock document after signing | PARTIAL | DONE | `DigitalSignScreen.kt:171` `lock()` → `SignOptions.lock` → `core/sign/SigFields.kt:198,265` `applyLock` (FieldMDP) |
| Named signature profiles | MISSING | DONE | `DigitalSignScreen.kt:152-167` → `core/sign/SignProfiles` |
| Certify: ask for file name | PARTIAL | DONE | `DigitalSignScreen.kt:141` `SignPrefs.askFileName` |
| Appearance: max font size | MISSING | DONE | `ui/signature/SigAppearanceDesigner.kt:168-176` → `SigAppearanceSpec.maxFontSize` |
| Appearance: named profiles | PARTIAL | DONE | `SigAppearanceDesigner.kt:45-50` `SigAppearanceProfiles` |
| QuickSign black & white | MISSING | DONE | `SignatureCreateSheet.kt:92`, `SignatureImageTab.kt:107`, `SignaturesScreen.kt:95` → `SignatureImage.toBlackAndWhite` → `SignatureMono` |
| Timestamp servers: list / default | PARTIAL | DONE | `ui/signature/SignAdvancedOptions.kt:40-59` → `core/sign/TsaServers` |
| Timestamp server login | MISSING | DONE | `TsaServer.username`, password in SecretBox (`core/sign/TsaServers.kt:8-23`) |
| Attestable timestamp server | MISSING | DONE | `TsaServer.requireTrusted` (`TsaServers.kt:14`) |
| LTV verification (OCSP/CRL) | PARTIAL | DONE | `core/sign/Revocation`; `DigitalSignScreen.kt:642` `RevocationLine` in the Verify tab |
| Signer details: location / contact | PARTIAL | DONE | `DigitalSignScreen.kt:603-604` (VerifyCard) |
| Revision X of Y / View Signed Version | MISSING | DONE | `ui/signature/SigFieldActions.kt:130-133,197-200` → `SigRevisions.extract`, opens in the reader |
| Counter-signed state | MISSING | DONE | `DigitalSignScreen.kt:619` `r.counterSigners` |
| Hide valid-signature icons | MISSING | DONE | Badges `ui/forms5/InPageFormLayer.kt:143,364`; toggle `DigitalSignScreen.kt:508` → `SignPrefs.setShowSignatureIcons` |
| Signature field actions (Sign / Clear / Validate / View signed / Properties) | MISSING | DONE **(spot-checked)** | Reader tap on a /Sig widget → `SigFieldSheet` (`ReaderScreen.kt:370`; `SigFieldActions.kt:48`) → Sign :116-119 → `digital_sign` with `SigFieldRequest` (`DigitalSignScreen.kt:54`) → `DigitalSignature.kt:356-375` → `SigFields.prepare(target.fieldName)` (`SigFields.kt:173-177`); Clear :184 `SigFields.clear`; Properties :143 |
| Notarius / bundled CA roots | MISSING | NOT-FEASIBLE | Licensing (DONE_pdf3c_protect_sign: "AATL / Notarius NF"). The system CA store is used instead: `core/sign/SystemTrust.kt`, toggle `DigitalSignScreen.kt:505` |
| QuickSign from a PDF page | MISSING | DONE | `ui/signature/SignatureImageTab.kt:132` → `PdfPageCropDialog` |
| Password-protect a signature | MISSING | DONE | `core/data/SignatureLock`; `ui/signature/SignatureLockUi.kt:33`; checked in `DigitalSignScreen.kt:225` |
| Recently used signatures | MISSING | DONE | `SignatureMetaStore.recent` in `ReaderToolbars.kt:253`, `SignaturesScreen.kt:87`; `markUsed` at `ReaderState.kt:816` |
| (N/A row) Wizard: Windows store, Android KeyChain equivalent | N/A (equiv. MISSING) | N/A-ANDROID (Android equivalent DONE) | as in §9 (`DigitalIds.chooseKeyChain`) |
| (N/A row) Windows trust anchor, Android CA store equivalent | N/A (equiv. MISSING) | N/A-ANDROID (Android equivalent DONE) | `SystemTrust.anchors()` (AndroidCAStore), used by `CertInspector.inspect(system=…)` :80-90; checkbox `DigitalSignScreen.kt:505` |

## §12 Headers & footers, watermarks / backgrounds, Bates

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Watermark type: PDF page | MISSING | DONE | `ui/edit5/DecorScreen.kt:65,119` `DecorSource.PDF` (Tools → `e5_hub` → `e5_watermark`) |
| Watermark profiles / import-export | MISSING (verify: PARTIAL) | DONE | `E5ProfileBar` `DecorScreen.kt:78,83` (`core/edit5/DecorExtras.kt` `E5Profiles`); JSON, not `.nxp` |
| Text: font style (italic) | PARTIAL | DONE | `DecorScreen.kt:105`; `HeaderFooterScreen.kt:121` |
| Text: underline in watermark UI | PARTIAL | DONE | `DecorScreen.kt:106` |
| Line spacing / word wrap | MISSING | DONE | `DecorScreen.kt:113-115` |
| Outline font | MISSING | DONE | `DecorScreen.kt:107-110` (outline colour, hollow) |
| Border colour / weight / dashed | MISSING | DONE | `DecorScreen.kt:164-166` |
| Action on click | MISSING | DONE | `DecorScreen.kt:179-181` `ClickAction` (single placements only, not tiled) |
| Every N pages | MISSING | DONE | `EveryNPages` `DecorScreen.kt:177,317`; `HeaderFooterScreen.kt:141` |
| Header/footer: image or PDF | MISSING | DONE | `HeaderFooterScreen.kt:62` image picker → `HFSpec.imagePath` / `HFPicture` |
| Header/footer: user profiles | PARTIAL | DONE | `E5ProfileBar("hf")` `HeaderFooterScreen.kt:73` |
| Tokens: Batch page / total | MISSING | DONE | `{batchpage}` `{batchpages}` (`core/edit5/E5Format.kt:189-194`); `HeaderFooterOps.applyBatch` (`HeaderFooterScreen.kt:207`) |
| Token `{bates}` in a plain header/footer | PARTIAL | DONE | `HeaderFooterOps.kt:175-181` (numbers with `HFSpec.bates`); format fields `HeaderFooterScreen.kt:88-95` |
| Token: Bates total | MISSING | DONE | `{batestotal}` `E5Format.kt:213` |
| Token: File path | MISSING | DONE | `{path}` `E5Format.kt:214` |
| Tokens: Subject / Keywords | MISSING | DONE | `E5Format.kt:215-216` |
| Ignore page rotation | MISSING | DONE | `DecorScreen.kt:172`; `HeaderFooterScreen.kt:142` |
| Bates: colour | MISSING | DONE | `ui/edit5/BatesScreen.kt:120` |
| Bates: rotation / opacity / background | MISSING | DONE | `BatesScreen.kt:122-126` |
| Bates: advanced margins + units | PARTIAL | DONE | `MarginsEditor` `BatesScreen.kt:111` |
| Bates: increase page size | MISSING | DONE | `BatesScreen.kt:114` `growPage` |
| Bates: numbered pages only / range per file | MISSING | DONE | `BatesScreen.kt:105-107` (`RangeSubset`, `countSkipped`) |
| Bates: one number per document / restart | MISSING | DONE | `BatesScreen.kt:99-100` `BatesMode` |
| Bates: output folder | PARTIAL | DONE | `BatesScreen.kt:59-63,175` (SAF tree → `saveToTree`) |
| Remove Bates numbering | CODE-ONLY | DONE **(spot-checked)** | Tools → `e5_hub` → `e5_bates` → button `BatesScreen.kt:166` → `HeaderFooterOps.removeBates` (`remove(bates = true)`, `HeaderFooterOps.kt:146-152`, which also restores enlarged pages) |

## §13 OCR

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Zone Tool | MISSING | DONE | Tool `ocr_zone` `ui/tools/ToolsScreen.kt:83` → `ToolScreens.kt:69` → `ui/ocr/OcrToolsScreens.kt` `OcrZoneScreen` |
| Correct image skew | MISSING | DONE **(spot-checked)** | Reader OCR sheet → `OcrTuningSection` (`ui/ocr/OcrSheet.kt:118`; `OcrTuningUi.kt:43` "deskew") → `OcrInPlace.kt:63` → `OcrPipeline.correctedCopy` → `OcrPrep.correctPages` |
| Fixed threshold | PARTIAL | DONE | `OcrTuningUi.kt:48-54` |
| Detect text orientation | MISSING | DONE | `OcrTuningUi.kt:44` (four quick recognitions; Tesseract OSD itself cannot run on an LSTM-only build) |
| Smooth colour image | PARTIAL | DONE | `OcrTuningUi.kt:45` |
| Recognition quality | PARTIAL | DONE | `OcrTuningUi.kt:39-40` (best / fast) |
| Output: downsample | MISSING | DONE | `OcrTuningUi.kt:60-63` `outputDpi` |
| Output: compression | MISSING | DONE | same section, `outputQuality` |
| Auto de-skew for existing PDFs | PARTIAL | DONE | same path as skew |
| Languages ita/por/rus/ell/heb/hin/chi_sim selectable | PARTIAL | DONE | `core/ocr/OcrModels.kt`; `OcrLanguagePicker` in `OcrSheet.kt`, `OcrToolsScreens.kt`, `OcrBatchScreen.kt` |
| Other ~100 languages | MISSING | DONE | `OcrModels.kt` (all tessdata models pinned) + "More languages…" picker |
| OCR-A/B, CMC7, numeric only, handprint | MISSING | NOT-FEASIBLE (numeric part DONE) | Numbers only: `OcrTuningUi.kt:56`. The others are not possible because Tesseract has no models for them (string `nt_ocr_special_note`) |

## §14 Optimize / compress

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Separate colour / grey / mono settings | PARTIAL | DONE | `ui/tools5/OptimizeExportScreens.kt:103-120` (Tools → `optimize`) |
| Compression Automatic / JPEG / ZIP | PARTIAL | DONE | `CompressionChips` `OptimizeExportScreens.kt:182` |
| CCITT G3 / G4 | MISSING | DONE | `MonoCompression` chips `:118-120` → `core/tools5/Ccitt.kt` |
| Unembed fonts | MISSING | DONE | `:130` `FontUnembed` (standard-14 or simple fonts; CID fonts kept) |
| Remove annotations / forms / links / bookmarks in Optimize (4 rows) | PARTIAL | DONE | `:134-137` |
| Remove named destinations | MISSING | DONE | `:138` |
| Troubleshooting tips | PARTIAL | DONE | `OptimizeTip` `:78,185` |
| Encrypted input in Optimize | PARTIAL | DONE **(spot-checked)** | `OptimizeExportScreens.kt:87-95`: `auditFile(f, pw)` throws `InvalidPasswordException` → `PasswordDialog` → `password`; `Optimizer.optimize(f, pw, …)` :161; keep-protection switch :139 |

## §15 Compare

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Image markers | MISSING | DONE **(spot-checked)** | Tools → `compare` → `Compare2Screen.kt:192` `CompareEngine.compare(a, pa, b, pb, o)` → `core/tools5/CompareCore.kt:234,279` `images()` |
| Page insert / delete markers | MISSING | DONE | `PageMatch.align` (`CompareCore.kt:184`) |
| Annotation markers | MISSING | DONE | `CompareCore.kt:235,302` |
| Rotation / crop / position markers | MISSING | DONE | `PageChange` ROTATED / CROPPED / MOVED (NITRO_TOOLS_LOG §15) |
| Include text / annotations toggle | PARTIAL | DONE | `CompareOptions` (`Compare2Screen.kt:133`) |
| Mark colours | MISSING | DONE | `StyleDialog` `Compare2Screen.kt:271,328`; default in Settings (`NpfSettingsScreen.kt:316-320`) |
| Auto match pages | PARTIAL | DONE | `Compare2Screen.kt:181` |
| Visual compare (output document) | PARTIAL | DONE | `CompareOutput.visualDocument` `Compare2Screen.kt:226` |
| Page ranges per document | MISSING | DONE | `Compare2Screen.kt:176-178` |
| Encrypted inputs | PARTIAL | DONE | `PasswordDialog` `Compare2Screen.kt:146`; passwords passed at :192,226,234 |

## §16 Bookmarks, links, actions, JavaScript

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Delete All bookmarks | MISSING (verify: PARTIAL) | DONE | `ui/edit5/BookmarksScreen.kt:128,182-184` (Tools → `e5_hub` → `e5_bookmarks`) |
| Bookmark colour (custom) | PARTIAL | DONE | `ui/edit5/NavUi.kt:51` `NbmColorDialog` |
| Expand / collapse all | PARTIAL | DONE | `BookmarksScreen.kt:123-124` `OutlineNav.setOpenAll`; reader outline panel (`ui/edit5/ReaderNav.kt:30`) |
| Bookmark actions (web, file, named, JS, print, extract) | PARTIAL | DONE | `ActionListEditor` (`NavUi.kt:161,183-185`), used in `BookmarksScreen.kt:257` |
| Chained action list (/Next) | MISSING | DONE | same `ActionListEditor` (also for links: `LinkEditor.kt:89`) |
| Wrap bookmarks / text size | MISSING | DONE | `ReaderNav.kt:60` (outline panel options) |
| Auto bookmarks: 5 levels | PARTIAL | DONE | `core/edit5/AutoBookmarks.kt`; dialog `BookmarksScreen.kt:284-309` |
| Auto bookmarks: per-level font / colour / indent / mask / keywords | PARTIAL | DONE | same |
| Auto bookmarks: leading numbers | MISSING | DONE | same |
| Auto bookmarks: magnification | MISSING | DONE | same |
| Auto bookmarks: insertion Before | PARTIAL | DONE | same |
| Auto bookmarks: collapse / exact text | MISSING | DONE | same |
| Auto bookmarks: settings import / export | MISSING | DONE | `BookmarksScreen.kt:294-296` (JSON; Nitro's `.inb` format is undocumented) |
| Links: multi-select / align / distribute / resize | MISSING | DONE | `ui/edit5/LinksScreen.kt:50,65-87` selection mode → `core/edit5/LinkGeom` |
| Link zoom FitV / FitB / FitBH / FitR | PARTIAL | DONE | `DestViewFields` `NavUi.kt:100-104` |
| Link to another document (named destination, password) | PARTIAL | DONE (password NOT-FEASIBLE) | named destination `LinkEditor.kt:80`. A PDF action cannot carry a password, so the reader asks for it instead (DONE_pdf3c_nav) |
| Link: custom action list | MISSING | DONE | `LinkEditor.kt:89` `ActionListEditor` |
| Use current appearance as default | MISSING | DONE | `LinkEditor.kt:42,102` `LinkDefaults` |
| Auto Page Links | MISSING | DONE | `LinksScreen.kt:168,216` → `AutoLinks.pageLinks` |
| Auto Keyword Links | MISSING | DONE | `LinksScreen.kt:170,218` |
| Named actions | MISSING | DONE | `NavAction.Named` (`NavUi.kt:150,183`) in the bookmark and link action lists |
| Document JS editor + console | MISSING | DONE **(spot-checked)** | Tools → `e5_hub` → `e5_doc_js` (`Edit5Hub.kt:32,68`) → `ui/edit5/DocScriptsScreen.kt:73` (scripts / actions / console tabs, console :169) → `core/edit5/DocJs` (follows JsPolicy) |
| Document-level functions | MISSING | DONE | same screen, scripts tab |
| Document actions (WC / WS / DS / WP / DP) | MISSING | DONE | same screen, actions tab |

## §17 Attachments and portfolio

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Attachments panel in the reader | PARTIAL | DONE **(spot-checked)** | `ReaderPanel.ATTACHMENTS` (`ReaderToolbars.kt:57,150`) → `ReaderPanels.kt:72` → `ReaderAttachmentsPanel` → `AttachmentsBrowser`; the delete action `AttachmentsBrowser.kt:227,241` → `AttachmentOps.delete` (`core/pdf/AttachmentOps.kt:210`) |
| Open attachment in the native app | PARTIAL | DONE | `AttachmentsBrowser.kt:108` ACTION_VIEW (PDFs open in the reader) |
| Delete one attachment | MISSING (verify: PARTIAL) | DONE | as above |
| Edit description | MISSING (verify: PARTIAL) | DONE | `AttachmentsBrowser.kt:225,236` → `AttachmentOps.setDescription` :233 |
| Print attachment | MISSING | DONE | `AttachmentsBrowser.kt:148-149` → `PdfPrint.print` |
| Properties: created / modified | PARTIAL | DONE | PROPERTIES action `AttachmentsBrowser.kt:247` (doc comment :53-54) |
| Detail / tile view | PARTIAL | DONE | `AttView { LIST, DETAIL, TILES }` `AttachmentsBrowser.kt:44,194-202` |
| Portfolio-opened notification | CODE-ONLY | DONE | `PortfolioPrompt` `ui/attachments/ReaderAttachments.kt:49`, shown at `ReaderScreen.kt:371`; also the `NveDocBanners` PORTFOLIO notice → `portfolio` tool |
| Attach source file on Create PDF | MISSING | DONE | `ToPdfPlusScreen.kt:86,105` `AttachmentOps.attachSources`; switch `Cv6OptionsSheet.kt:169` |

## §18 Layers (OCG)

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Layers pane: show / hide | MISSING | DONE **(spot-checked)** | Tools → `e5_hub` → `e5_layers` (`Edit5Hub.kt:80`) → `ui/edit5/LayersScreen.kt` → `Layers.setOn` / `showAll` / `list` |
| Reset to initial visibility | MISSING | DONE | `Layers.reset` (same screen) |
| Expand / collapse | MISSING | DONE | `Layers.list` groups (same screen) |
| Layer properties (name, default state, locked) | MISSING | DONE | `Layers.rename` / `setIntent` / `setLocked` |
| Initial state Visibility / Print / Export | MISSING | DONE | `Layers.setUse`, `applyOverrides` |
| Set OCG state action | MISSING | DONE | `Layers.addStateBookmark` |

## §19 Accessibility / PDF/UA

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Auto Tag | PARTIAL | STILL MISSING (part) | Headings, lists, artifacts and re-tagging are DONE: `AccessibilityScreen.kt:226` → `AccessAutoTag.tag(d, lang, retag)`. **Tables are still not recognised** (PAGES_CONVERT_LOG stage F: "recognition of tables OPEN") |
| Tags pane: create / "Tag as…" | MISSING | DONE | `AccessibilityScreen.kt:123` → `TagsEditor` (`ui/tools5/AccessTagsUi.kt:37`) → `AccessTags` |
| Mark as Artifact | PARTIAL | DONE | `AccessTags` (mark as artifact) in the TagsEditor |
| Delete / reorder / edit tags, change type, role map | MISSING | DONE | `AccessTagsUi.kt:59` (tree, roleMap) and AccessTags move / indent / change type |
| Tag types | PARTIAL | DONE | auto-tag writes H1–H3, L / LI / LBody; "change type" in the editor |
| Tag properties beyond alt text | MISSING | DONE | `AccessTagsUi.kt:178-179` `setProps` / `setAttrs` (Title, ActualText, Lang, Expansion, ID, Scope, spans, Headers, Summary, list numbering) |
| Embed fonts | MISSING | NOT-FEASIBLE | the original font programs are not available (PAGES_CONVERT_LOG stage F) |
| Mark as PDF/UA | PARTIAL / CODE-ONLY | DONE | `AccessibilityScreen.kt:232` `AccessUa.markUa`; also `DocPropsScreen.kt:165` and `DocInfoSheet.kt:129` → `XmpOps.markPdfUaFile` |
| Read aloud: next / previous / repeat block | PARTIAL | STILL MISSING | `TtsBar` (`ReaderOverlays.kt:121-132`) has play/pause, stop and previous/next **page** only. `ReadAloudController` has no block or sentence navigation |

## §20 PDF/A

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Convert to PDF/A-1b | MISSING | STILL MISSING | `PdfA.kt:209` still calls `XmpWriter.packet` with the defaults (part 2, B); `PdfAOptions` has no part or level. Stage G of feature/nitro-pages-convert is not merged |
| PDF/A-1a / 2a | MISSING | STILL MISSING | same |
| PDF/A-2u | MISSING | STILL MISSING | same |
| PDF/A claim notification / editing breaks it | MISSING | DONE **(spot-checked)** | `ReaderScreen.kt:347` → `NveDocBanners` → `DocNotices.pdfaClaim` (`core/nve/panes/DocNotices.kt:69-83`); text `np_bn_pdfa`: "claims %s conformance. Changes may break the conformance." |
| PDF/A from scan / print / create | PARTIAL | STILL MISSING (part) | Now also reachable through the reader Save As → PDF/A (`ui/nve/file/SaveAsSheet.kt:60`), recipes and convert6. Scan, To-PDF and the print service still have no direct PDF/A option |
| PDF/UA marking | CODE-ONLY | DONE | see §19 |
| PDF version selection | MISSING | STILL MISSING | `PdfA.kt:217` still forces 1.7 when the version is outside 1.4–1.7. Optimize has `pdfVersion`, but PDF/A does not |

## §21 Batch / automation

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Clone user sequences | PARTIAL | DONE | `RecipesScreen.kt:183` `RecipeOps.clone` |
| Run on folders | MISSING | DONE **(spot-checked)** | Tools → `recipes` → `RecipesScreen.kt:97` `rememberFolderSource` (sub-folders) → `BatchJobs.enqueueItems` :113 |
| Run on all open documents | MISSING | DONE | `RecipesScreen.kt:76,101` (open tabs source) |
| Destination folder | PARTIAL | DONE | `r.output.folderUri` / `SafFolders` `RecipesScreen.kt:129` |
| Ask before overwriting | PARTIAL | DONE | `Conflict.ASK` → dialog `RecipesScreen.kt:123,219-221` |
| Execute JavaScript step | MISSING | DONE | `StepType.JAVASCRIPT` (`core/tools5/Recipes.kt:35`) |
| Metadata step: Base URL / custom | PARTIAL | DONE | `SET_INFO` with `baseUrl` and `custom` (`Recipes.kt:30`) |
| Delete All Comments step | MISSING | DONE | `DELETE_COMMENTS` (`Recipes.kt:36`) |
| Global commands (steps) | PARTIAL | DONE | 23 StepTypes, including HEADER_FOOTER, BATES, REDACT, SANITIZE, CONVERT_OFFICE and PRINT (`Recipes.kt:22-45`) |
| Batch Print | MISSING | DONE | Tool `batch_print` `ToolsScreen.kt:96` → `ToolScreens.kt:76` → `BatchPrintScreen` (print presets `PrintPresets`) |
| Watermark / header-footer profiles for many files | PARTIAL | DONE | `"profile"` key in the WATERMARK and HEADER_FOOTER steps (`Recipes.kt:26,37`) |

## §22 Cloud / DMS

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Add Place (custom server) | MISSING | DONE | `core/webdav/DavPlaces.kt`; `ui/webdav/WebDavScreens.kt` (from `ui/cloud/CloudScreen.kt`) |
| WebDAV open / save with locking | MISSING | DONE | `core/webdav/DavClient.kt:15-23` (LOCK / UNLOCK, 423 handling). Needs a test against a real server |
| Microsoft account (Graph) / AIP | PARTIAL | NOT-FEASIBLE | AIP / SharePoint need the MIP SDK and a tenant (DONE_pdf3c_nav). OneDrive login exists, but it needs the owner's client ID |

## §23 Preferences

All of these rows are in Settings → `NpfSettingsCard` (`SettingsScreen.kt:111`) → `NpfSettingsScreen` (`ui/settings/NpfSettingsScreen.kt`), with search, unless the row says otherwise.

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Default save folder | MISSING | DONE | `npf_save_folder` :135 → `core/files/NpfSaveFolder.kt`. Note: outputs are still written to the app folder and then **copied** to the chosen folder |
| Bookmark text size / wrap | MISSING | DONE | `ReaderNav.kt:60` |
| Max recent count | MISSING | DONE | `npf_recent_limit` :138 |
| Auto-recovery interval | PARTIAL | DONE | `npf_autosave_every`, `npf_recovery` |
| Default page layout | PARTIAL | DONE | `npf_layout` :149-151 → `NpfReader.initialMode` (`NpfReaderHooks.kt:53`) |
| Default zoom | MISSING | DONE | `npf_zoom` :152-153 → `ReaderState.kt:106-107` `NpfReader.initialZoom` |
| Ignore line weights / enhance thin lines | MISSING | NOT-FEASIBLE | the PDFium wrapper has no such flag (`npf_thin_lines_na`; DONE_pdf3c_nav) |
| Ignore page rotation (pref) | MISSING | DONE | `npf_hf_rotation`; per-run switches in DecorScreen / HeaderFooterScreen |
| Default units | PARTIAL | DONE | `npf_units` :170 |
| Grid snap / colour / offsets / subdivisions | PARTIAL | DONE | `NpfGridSettings` (`ui/view5/NpfGuides.kt:92`), used at `NpfSettingsScreen.kt:172` and `View5Host.kt:205` |
| Guides | MISSING | DONE | `NpfGuidesOverlay` (`PageOverlays5.kt:53`) |
| Pop-up note font / size / opacity | MISSING | DONE | `npf_note_font` :181 (`npf_note_size`, `npf_note_opacity`); `NpfNotePopup` (`NpfReaderHooks.kt:170`) |
| Retain markup tools | MISSING (verify: HAVE) | DONE | toolbar chip `rd_continue_creating` + Settings `npf_retain_tools` :47,179 |
| Comment summary preferences | PARTIAL | DONE | comment summary SummaryOptions (DONE_comments_forms), Settings row under `npf_s_comments` |
| Identity (organisation / unit / e-mail) | PARTIAL | DONE | `IdentityRows` :280-292 |
| Field highlight / required colours | PARTIAL | DONE | Settings colour rows, used by `InPageFormLayer` (DONE_comments_forms 8.82) |
| XFA preferences | PARTIAL | DONE | `npf_xfa_auto` :195, `npf_xfa_banner` |
| Editing preferences | MISSING | DONE | Edit mode → text tools → prefs (`ui/edit/TextTools5.kt:416,459,659-690`): word distance, embedded-font warning, system fonts, no-text alert, live spell check, digits |
| Remove bookmarks of deleted pages | MISSING | DONE | `npf_bm_delete` :207 → `PageOrganize.deleteIn` (`PdfOps.kt:151`) |
| Import bookmarks when replacing pages | PARTIAL | DONE | `npf_bm_import` :208 → `PageOps5.kt:186` |
| Conversion defaults in Settings | PARTIAL | STILL MISSING | `ToolDefaults` (:302-305) only **links** to the convert hub, To-PDF+ and Export screens. Per-format options are not saved as defaults: `Cv6OptionsSheet.defaultsFor` is fixed, and the "cv6" prefs keep only recent conversions |
| OCR defaults | PARTIAL | DONE | `npf_ocr_output`, `npf_ocr_clean`, `npf_ocr_auto` :308-315, plus the OCR language row |
| Compare settings / colours | MISSING | DONE | :316-320 → `CompareStyle.saveDefault` |
| Measure snapping | PARTIAL | DONE | `npf_measure_snap` :175 |
| Hide valid-signature icons | MISSING | DONE | `DigitalSignScreen.kt:508` (signing options, not global Settings) |
| QuickSign black & white | MISSING | DONE | see §11 |
| Disable JavaScript | MISSING | DONE | see §9 |
| Internet access: trusted sites | MISSING | DONE | see §9 |
| Notification toggles | PARTIAL | DONE | `npf_n_review` / `npf_n_sync` / `npf_n_tasks` :216 |
| Search preferences | PARTIAL | DONE | `npf_s_search` :224 (scopes, hit colours) |
| Accessibility preferences | PARTIAL | DONE | `npf_a11y_override` :238, reading order `npf_read_order` |
| Saving preferences | PARTIAL | DONE | autosave interval / on leave (`npf_autosave_every`, `npf_on_leave`) |
| Batch print preferences | MISSING | DONE | `BatchPrintScreen` with `PrintPresets.last/remember` (`BatchPrintScreen.kt:42,70`) |

## §24 Other

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Font mapping (user-editable) | PARTIAL | DONE | editor `NpfSettingsScreen.kt:327-334` → `NpfText.userFontMap` → `core/convert/FontMap.kt:75`. Note: it applies to conversion only; PDFium's on-screen font substitution is not affected |
| Barcode form fields | PARTIAL | DONE | XFA barcode drawing `core/forms/XfaLayout.kt:284` → `NpfBarcode.boxes`; form-designer barcode field `core/formsx/FxBarcodes.kt` (DONE_comments_forms 8.9). The shared zxing encoder (8555d74) is on origin/main, after fa724fd |
| Help: Community forum | MISSING | OWNER-ACTION | row `NpfSettingsScreen.kt:270-276`. `npf_url_community` is empty, so it falls back to the Play Store page. The owner must set the URL |
| Help: Labs | MISSING | OWNER-ACTION | same; `npf_url_labs` is empty and falls back to the Play testing page |
| Multi-user packs / volume licensing | PARTIAL | STILL MISSING | activation codes and a license client exist (`PaywallScreen.kt:130`, `core/billing/ActivationCodes`), but there is no seat management. It also needs the owner's `LICENSE_URL` |

---

## STILL MISSING (sorted by importance)

**Med**
1. §19 Auto Tag: no table recognition (headings, lists, artifacts and re-tag are done). Area: pages/convert (accessibility).
2. §20 PDF/A-1b output: only PDF/A-2b is written (`PdfA.kt:209` uses the default part and level; stage G is not merged). Area: pages/convert.
3. §23 Conversion defaults in Settings: Settings only links to the converter screens, and per-format options are not saved as defaults. Area: nav/settings (convert).

**Low**
4. §20 PDF/A-1a / 2a (the "a" levels). Area: pages/convert.
5. §20 PDF/A-2u. Area: pages/convert.
6. §20 PDF version selection for PDF/A output (fixed at 1.7). Area: pages/convert.
7. §20 Direct PDF/A option in Scan, To-PDF and the print service (only Save As, recipes and convert6 today). Area: pages/convert.
8. §19 Read aloud: next / previous / repeat block (page navigation only). Area: reader/view.
9. §24 Volume / enterprise licensing: no seat management (also needs the owner's license server). Area: AI/office (account/billing).

## Counts

204 rows were PARTIAL, MISSING or CODE-ONLY (97 in §9–15, 107 in §16–24), plus the 3 N/A rows above.

| Status | §9–15 | §16–24 | Total |
|---|---|---|---|
| DONE | 95 | 93 | **188** |
| STILL MISSING | 0 | 9 | **9** |
| NOT-FEASIBLE | 2 | 3 | **5** |
| OWNER-ACTION | 0 | 2 | **2** |
| N/A-ANDROID (the Android equivalent is now DONE) | 3 | 0 | **3** |

NOT-FEASIBLE: Notarius / bundled CA roots (licensing); OCR-A/B, CMC7, handprint (no Tesseract models; numbers-only is done); embed fonts (no font programs); thin-line rendering (PDFium flag); Microsoft Graph / AIP (MIP SDK and tenant).
OWNER-ACTION: forum URL, Labs URL. Other owner items in this slice that are not counted as separate rows: e-sign worker redeploy, the cloud OAuth client IDs, WebDAV and KeyChain device tests.

# Part C — Foxit §1–23
# Final check: Foxit sections 1-23 (fcmp_01-08, fcmp_09-13_22, fcmp_14-21_23)

Checked against `D:\pdf\final-check-wt` (docs/final-check, main fa724fd). Read-only. Paths are relative to `app/src/main/java/com/smartdocs/editor/`.
Sources read: DONE_view_edit, DONE_pdf3c_nav, DONE_pdf3c_protect_sign, DONE_comments_forms, DONE_ai_office, NITRO_TOOLS_LOG, PAGES_CONVERT_LOG, VIDEO_INLINE_r5, verify_A/B, OPEN_ALL_FILES, convert-audit. Each DONE row below was grepped on main for the symbol and for a UI caller (route, tool id, chip, menu or panel).
Only rows that were PARTIAL / MISSING / CODE-ONLY (plus the video "IN PROGRESS" rows of §19) are listed.

How I decided a status: **DONE** means everything the comparison called missing is now built and reachable, apart from parts the DONE file marks not feasible (those are named in the note). If any named part is still missing, the row is **STILL MISSING** and the note says which part.

Nothing was run on a device. "Reachable" means a UI caller exists in the code.

---

## fcmp_01-08.md

### §1 Application shell, workspace, backstage, help

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Batch Print / Invoice Print | MISSING | DONE | Tools tile `batch_print` → ToolScreens → `ui/tools5/BatchPrintScreen.kt`. Invoice Print is NOT-FEASIBLE: it is a Foxit invoice plug-in (DONE_view_edit) |
| Optimizer / Optimize Scanned / Index / Action Wizard | PARTIAL | DONE | Index: GlobalSearch → `NveGlobalSearchBar` → `NveIndexSheet` (options, rebuild, purge, backup; NveSearchUi.kt:289). Scanned: `OptimizeScan.mrc` and scan filters in Optimize. Recipes |
| "Get Help on <keywords>" | PARTIAL | DONE (spot-checked) | Commands5.kt:146 "Get help on q" → `NveHelp.openWithQuery` (HelpUi.kt:42) → HelpCenter5.kt:60 `takeQuery()` fills the help search |
| Online tutorial videos | MISSING | NOT-FEASIBLE | DONE_view_edit: there are no curated videos to link; 4 practice PDFs (`core/nve/shell/FeatureGuides.kt`) are offered instead. This is a content decision, not a technical limit |
| Support Center AI chatbot | MISSING | DONE | Route `ai_help` (`ui/ai6/HelpChatScreen.kt`, chip in HelpCenter5) + `NveHelpBotDialog` (HelpUi.kt:78). Needs AI to be enabled |
| Search box starts an AI conversation | PARTIAL | DONE | Commands5.kt:151 "Ask the help assistant" → `NveHelp.askAi` → HelpUi.kt:61-67 bot dialog. It starts the help assistant, not the document AI |
| Status bar zoom % / page display | PARTIAL | DONE (spot-checked) | ReaderScreen.kt:333 `NvePageOverlays` → NveOverlays.kt:73 `StatusPill` (zoom %, :456-471). The View chip `nv_zoom_short` shows the % (NveHost.kt:92) |
| Status bar cursor coordinates | MISSING | DONE (spot-checked) | ReaderScreen.kt:311 `nveMouseModifier` sets `cursor` (NveOverlays.kt:411) → `StatusPill` shows "p x y unit". Mouse pointer only (no touch read-out) |
| Start page tutorial videos / cloud-share panel | PARTIAL | DONE | `CloudPanel` on HomeScreen. Videos: NOT-FEASIBLE (as above) |
| QAT reset / position | PARTIAL | STILL MISSING | Reset is done (command `nv_reset_pins`, NveHost.kt:197). Still missing: a position choice (pins stay in the top bar) |
| Customize the Ribbon (tabs, groups, rename, icon, reset) | PARTIAL | DONE | `ui/nve/shell/UserTabs.kt` (editor reached from the View chip "customise toolbar"); ReaderToolbars.kt:112 `NveUserTabToolbar` |
| Ribbon customization import / export | MISSING | DONE | UserTabs.kt:80-85 CreateDocument / OpenDocument JSON (`core/nve/shell/Customization.kt`) |
| Customize keyboard shortcuts | MISSING | DONE | ⋮ "Keyboard shortcuts" → `NveKeysSheet` (NveKeys.kt:249-279: assign, remove, reset all); command `nv_keys` |
| Close all / before / after / except current | MISSING | DONE | Tab menu (ReaderTabs.kt:202, :255) → `NveTabs` close others/left/right/all (NveTabs.kt:40-48) |
| Pin / unpin tab, Ctrl+Tab, mouse-wheel tab switching | MISSING | STILL MISSING | Pin is done (NveTabs.kt:27-37, :83). Ctrl+Tab and Ctrl+Shift+PgUp/PgDn are done (NveKeys). Still missing: switching tabs with the mouse wheel over the tab strip |
| New window / drag tab out | MISSING | DONE | Tab menu "New window" (NveTabs.kt:92-94) → `NveWindowActivity`. Dragging a tab out of the window is NOT-FEASIBLE (DONE file) |
| Colour themes | MISSING | DONE | Settings → `NveShellSettingsRows` (SettingsScreen.kt:72) → `core/theme/Accent.kt` AccentColors, dynamic colour |
| Preferences search box | MISSING | DONE | `ui/settings/NpfSettingsScreen.kt:79` search field (Arabic-insensitive folding, :89) |
| Properties: Security / Fonts / page size / Fast Web View | PARTIAL | DONE | ⋮ "Properties" → `NveExternal.docInfo` → `ui/nve/file/DocInfoSheet.kt` (`NveSecurityPanel`, `NveFontsPanel`; `core/nve/DocInspect`) |
| Crash reporter attaches the crashing PDF | MISSING | DONE | `ui/nve/shell/CrashAttach.kt`, used in Navigation.kt (crash dialog) and HelpScreen.kt |
| Feedback survey (NPS) | PARTIAL | DONE | HomeScreen.kt:57 `NveNpsOnce` (once, after a week of use); chip in HelpCenter (HelpUi.kt:65) |
| Auto-recovery (interval, copy, prompt, all tabs) | PARTIAL | DONE | `ui/nve/shell/Recovery.kt` (`NveRecoveryBanner`, ReaderScreen.kt:348; `NveRecoveryEffect` :435); interval `NpfPrefs.RECOVERY_MIN`; tabs restored (ReaderTabs / MainActivity) |
| Safe reading / trust prompts / JavaScript off | PARTIAL | DONE | `core/security/TrustManager.kt` + `TrustPrefs`, `JsPolicy`; prompts in ReaderIntents / FormFillState (`NveTrust`); settings `Np2TrustSettings`, `NpSecuritySettings` (DONE_pdf3c_protect_sign §28) |

### §2 Home tab

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Clipboard: Cut / Paste into the page | MISSING | DONE | View chip / ⋮ "Paste" → `NveSurface.PASTE` → `NveClipboard.paste` (NveHost.kt:56). Cut and copy of edit-mode objects: ImageTools5 |
| Clipboard: Select All page text | MISSING | DONE | View chip `nv_select_all` → `NveSelectionOps.selectAll` (NveHost.kt:111, :117) |
| Fit: Actual Size | MISSING | DONE | View chip → `NveZoom.preset(ACTUAL)` (NveHost.kt:97); command `nv_actual` |
| Edit Object: Shape | MISSING | DONE | Edit mode filter `ObjFilter5.SHAPE` (ImageTools5.kt:71, :490); new shapes: `ShapesFab` (EditModeHost.kt:60) → `ShapeEditorDialog` |
| Edit Object: Shading | MISSING | DONE | `ObjFilter5.SHADING` (ImageTools5.kt:111, :518); gradients in ShapeEditor |
| Insert: From Web Page | MISSING | DONE (spot-checked) | OrganizeScreen.kt:322 → `NveOrganizeInsertMore` (OrganizeInsert.kt:85 "web") → `ToPdf.url` → `state.insertFrom` (OrganizeState.kt:174). Also e5_organize INSERT `url_pdf` |
| Insert: From Clipboard | MISSING | DONE | OrganizeInsert.kt:86; PageOrganizeScreen INSERT `clipboard_pdf` |
| Insert: From Folder / Open Files | MISSING | DONE | OrganizeInsert.kt:62-70, :95 (SAF tree, all PDFs in it); PageOrganizeScreen INSERT "from tabs" (`ReaderTabs.tabs`) |
| Insert: Into File | PARTIAL | DONE | e5_organize `OrgOp.INSERT_INTO` → `PageOrganize.insertInto`; NVE `InsertIntoDialog` |
| Scan / Manage scan presets | PARTIAL | DONE | `ScanPresetsUi` in ScannerScreen; Scan studio save / apply / delete presets (`ScanPreset`, ScanStudioScreen.kt:240-249) |
| Suspect Results: First / All Suspects | PARTIAL | DONE | Tools `ocr_review` → `OcrReviewScreen` (red boxes, page / document list, accept all). Files OCRed before this build have no stored words |
| Translation History | MISSING | DONE | TextToolsSheet.kt:68 records translations, :89 `NveTranslationHistoryChip` → history dialog. Covers selection translation only |

### §3 Open, Save & Close

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Drag-and-drop a PDF onto the window | MISSING | DONE (spot-checked) | MainActivity.kt:62 `NveDropRoot(onFile = { incoming.value = … })` wraps the NavHost → DropTarget.kt:33-44 `onDrop` |
| Recent item: Copy path | MISSING | DONE (spot-checked) | HomeComponents.kt:128 `NveDocMenuExtras` → Folders.kt:131-135 `setPrimaryClip(path)` |
| Recent list size | MISSING | DONE | `NpfPrefs.RECENT_LIMIT` (NpfSettingsScreen.kt:139 → HomeScreen.kt:63) and `NveFolders.recentSize` (ShellSettings.kt:89-92 → HomeScreen.kt:183). There are two settings for this, so a duplicate to merge |
| Enforce print / copy / edit permissions | MISSING | DONE | `core/security/ReaderRestrictions.kt` + `ui/reader/ReaderGuard.kt` (`state.guard.check(DocAction…)`, e.g. NveSelection.kt:179) |
| Auto-save every N minutes + recovery file | PARTIAL | DONE | `NpfPrefs.AUTOSAVE_MIN` / `RECOVERY_MIN` (NpfReaderHooks), Recovery.kt |
| Save to Google Drive as Docs / Sheets / Slides | MISSING | OWNER-ACTION | `GoogleDrive.uploadConverted` is called from `CloudPanel`. It needs the Google Drive OAuth client to be set up (DONE_view_edit: "NEEDS-KEYS") |
| Pinned recent folders / default save folder | PARTIAL | DONE | `NveHomePinnedFolders` (Folders.kt:85-115); default save folder in ShellSettings.kt:94-98 and `core/files/NpfSaveFolder.kt` |
| Close all tabs | MISSING | DONE | NveTabs close "all"; Ctrl+Shift+W |

### §4 Create PDF, Combine, Recognize Text

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Multiple files: keep original names and locations | PARTIAL | STILL MISSING | Outputs still go to the app folder (plus an optional copy to the default save folder). They are not written next to the source files |
| Multiple files (separate PDFs): add folder, sort | PARTIAL | STILL MISSING | The To-PDF "Each file" tab still uses `FileListEditor` (ToPdfPlusScreen tab 1). Folder input and sort exist only in the Combine tab |
| Creation presets (Standard / Print / Smallest / PDF/A / PDF/UA / USPTO) | PARTIAL | STILL MISSING | No named presets at creation. A recipe can run PDF/A or auto-tag afterwards |
| Bookmarks at conversion (Word headings, Excel sheets, PPT titles) | PARTIAL | STILL MISSING | Combine "headings → bookmarks" (`CombineOpts.headings`, CombinePlusUi.kt:178) covers Word, HTML and text. Excel sheet names and PPT slide titles are not made into bookmarks |
| Tagged PDF at creation | PARTIAL | STILL MISSING | The WebView printer still writes no tags. Tags can be added afterwards with `AccessAutoTag` (better now) or the recipe `AUTO_TAG` step |
| Convert Model to 3D PDF (DWG / CAD) | MISSING | NOT-FEASIBLE | PAGES_CONVERT_LOG stage B: no pure-Java reader for CAD |
| From Clipboard: image | MISSING | DONE | Combine "clipboard (text or image)" (CombinePlusUi.kt:114); Home paste → new PDF (NVE HOME-1) |
| Web: conversion levels / whole site | MISSING | STILL MISSING | Still OPEN in PAGES_CONVERT_LOG; one page only |
| Web: page-load timeout | PARTIAL | DONE | `WebPdfWriter.Setup.timeoutMs` (android/print/WebPdfWriter.kt:47-53), `pc_web_timeout` |
| Web: disable links / single long page / bookmarks / tags / header-footer / backgrounds | MISSING | STILL MISSING | Disabling links and background graphics are done (`WebCss`, CombinePlusUi.kt:253-255). Still missing: one single long page, bookmarks, tags, headers and footers |
| Web: convert images / underline links | MISSING | DONE | `WebCss.build(images, …, underlineLinks)`, CombinePlusUi.kt:252-254 |
| Web: page size, margins, orientation | MISSING | DONE | `Setup.media / landscape / marginMils`, `pc_web_layout` |
| Web: media style Screen/Print; scaling | PARTIAL | STILL MISSING | No screen-media or scaling option |
| Open Weblink as New Document | MISSING | STILL MISSING | The link preview (`NveLinkPreview`) offers open and copy URL only. Nothing converts the linked page |
| Form from a file (auto-detect fields) | MISSING | DONE | Form designer "Run form field recognition" → `core/formsx/FieldRecognition.kt` (DesignerState.kt:227, FormDesignerScreen.kt:216) |
| Form: scan a file into a form | MISSING | STILL MISSING | Recognition reads glyphs and vector lines only. A scanned (image) page finds nothing unless it is OCRed first, and that path is untested |
| From HWP | MISSING | NOT-FEASIBLE | PAGES_CONVERT_LOG: no pure-Java HWP reader |
| From Template (library, search, categories) | PARTIAL | STILL MISSING | Unchanged: 11 offline templates, no search, categories or online library |
| Add-in setting: preserve document properties | MISSING | DONE | `OfficeProps` in `ToPdf.convert` and CombinePlus |
| Excel comments, PPT notes, transitions, hidden slides, ranges | MISSING | STILL MISSING | Not converted |
| Excel fit options | MISSING | STILL MISSING | No fit-to-page / fit-to-width option |
| Word comments, footnote links, styles → bookmarks | MISSING | STILL MISSING | Headings → bookmarks now works in Combine. Comments → notes and footnote/endnote links are still missing |
| Mail merge: record range, e-mail each PDF | PARTIAL | STILL MISSING | `core/tools5/MailMerge.kt` has neither a record range nor e-mail sending |
| Printer properties (presets, append, version, security, metadata) | PARTIAL | STILL MISSING | The virtual printer now runs a recipe (ShareAndPrint.kt:136-141): security, metadata, PDF/A and watermark. Still missing: a PDF version choice. Append is NOT-FEASIBLE (one PDF per print job, DONE_pdf3c_nav) |
| Browser: append webpage to existing PDF / e-mail | PARTIAL | DONE | `appendIfAsked` (CombinePlusUi / ToPdfPlusScreen, `pc_append_to`); e-mail by sharing the result |
| Combine sources: folder, open files, web, scanner, clipboard | PARTIAL | DONE | `CombineTab` (CombinePlusUi.kt:98 folder, :114 clipboard, :115 scan, :119 tabs, :125 web page) |
| Combine: PDF portfolio input unpacked | MISSING | STILL MISSING | CombinePlus has no portfolio handling. Workaround: extract the portfolio parts first |
| Combine: thumbnail view | PARTIAL | STILL MISSING | List view only |
| Combine: sort by name / modified / size | MISSING | DONE | `CombinePlus.sorted` (natural order), `pc_sort` chip (CombinePlusUi.kt:141). There is no Bates sort |
| Combine: expand files into pages, move pages across files | MISSING | STILL MISSING | Files are still whole units |
| Combine: choose pages per file | MISSING | DONE | `CombineEntry.pages` field (CombinePlusUi.kt:163) |
| Combine: edit the bookmark title per file | MISSING | DONE | `pc_bookmark_title` field (CombinePlusUi.kt:162) |
| Combine: TOC page from bookmarks | PARTIAL | DONE | `CombineOpts.toc` → `OutlineTools.insertToc` (`pc_toc_page`, :179) |
| Combine: output folder / overwrite / don't open | PARTIAL | STILL MISSING | Only the global default save folder copy exists. Combine has no overwrite or "don't open after merging" choice |
| Combine: save as portfolio | PARTIAL | STILL MISSING | Still a separate Portfolio tool |
| Combine: e-mail attachment handling | PARTIAL | STILL MISSING | EML→PDF still always embeds the attachments; no "convert and append" or "don't include" |
| Recognize Text: Downsample To (DPI) | MISSING | DONE | `OcrTuning.outputDpi` / `outputQuality` → `OcrPipeline.shrinkOutput` (`ui/ocr/OcrTuningUi.kt`) |
| Recognize Text: Find All Suspects | PARTIAL | DONE | Tool `ocr_review` (`OcrReviewScreen`) |
| Recognize line segments as path objects | MISSING | STILL MISSING | No ruling or line output in `core/ocr` |
| OCR component download (CJK) | PARTIAL | DONE | `core/ocr/OcrModels.kt` pins every tessdata model; the "More languages…" picker (`OcrLanguagePicker`) is in the OCR sheet, OCR+ and the area tool. Needs a network download |
| Recognize Text > Multiple Files (folders, open files, output) | PARTIAL | DONE | Through Tools `recipes` → built-in "ocr" recipe: sources can be a folder or the open documents (`BatchSources`), with an output folder and overwrite rule. The OCR+ screen itself still takes files only |
| Recognize Text > Selected Region (zone OCR) | MISSING | DONE | Tool `ocr_zone` (`ui/ocr/OcrToolsScreens.kt` `OcrZoneScreen`); button in the reader OCR sheet / QuickActions |
| Watched Folder | MISSING | STILL MISSING | No folder watcher for tools. The only `FileObserver` is in `NpfSaveFolder`, and it copies outputs |

### §5 Scan & OCR

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Sides: both sides / reverse prompt | PARTIAL | DONE | Scan studio duplex card → `ScanExtras.duplex` (ScanStudioScreen.kt:218-222) |
| Paper sizes (A0-A10, B, C, JIS, Letter, Legal…) | PARTIAL | STILL MISSING | `ScanPaper` adds A3, A5, B5, Legal and card. The wider A, B, C and JIS sets are still missing |
| Output: multiple files (N pages each) | PARTIAL | DONE | `ScanExtras.chunks(…, perFile)` (ScanStudioScreen.kt:151) |
| Output: portfolio of all results | MISSING | DONE | `Portfolio.create` after a multi-file scan (ScanStudioScreen) |
| Optimize scanned: JPEG2000 / JBIG2 / CCITT G4 / MRC | MISSING | DONE | Optimize: `Jbig2` (lossless), `Ccitt`, `OptimizeScan.mrc` (OptimizeFoxitUi.kt:49). JPEG 2000 is NOT-FEASIBLE (no encoder, NITRO_TOOLS_LOG) |
| Optimize scanned: Deskew existing PDFs | PARTIAL | DONE | OCR "Correct page skew" → `OcrPrep.straighten` |
| Background removal / descreen / sharpening / adaptive | PARTIAL | DONE | Optimize `scanBackground` / `scanDescreen` / `scanSharpen` (OptimizeFoxitUi.kt:51-53); adaptive = MRC |
| Remove blank pages after scanning | MISSING | DONE | ScanStudioScreen → `PageOrganize.deleteBlank` (`removeBlank` pref) |
| Save as Preset / Manage presets | PARTIAL | DONE | `ScanPreset` save / apply / delete (ScanStudioScreen.kt:240-249); `ScanPresetsUi` |
| Post-Scan: Optimize Scanned PDF | PARTIAL | DONE | Optimizer codec gaps closed (row above) |
| Post-Scan: grayscale / black & white of an existing PDF | PARTIAL | DONE | Optimize `bwImages` (Sauvola → CCITT G4, OptimizeFoxitUi.kt:54) |
| Post-Scan: Edit document info (Author, Title) | PARTIAL | DONE | `ScanExtras.setInfo`, author field in Scan studio |
| OCR PDF portfolios | PARTIAL | DONE | `OcrRunner.ocrPortfolio` (OcrBatchScreen) |
| Find OCR Suspects (red boxes, page / document) | PARTIAL | DONE | `ocr_review` tool |
| All Suspects dialog | MISSING | DONE | `ocr_review` document list, editable all at once |

### §6 Export PDF

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Word 97-2003 (.doc) | MISSING | NOT-FEASIBLE | PAGES_CONVERT_LOG C: binary format, no writer (RTF export opens in Word) |
| DOCX: include comments | MISSING | STILL MISSING | Still OPEN in PAGES_CONVERT_LOG C |
| DOCX: Restore Defaults | MISSING | DONE | Cv6OptionsSheet.kt:164-166 `pc_restore_defaults` |
| Explorer "Convert to Microsoft Office" | PARTIAL | DONE | Manifest activity-aliases ConvertToWord / Excel / PowerPoint / Text → Convert hub with the target preselected |
| XML Spreadsheet 2003 | MISSING | DONE | `pdf_xml2003` (Cv6Catalog.kt:68) → `Xml2003` |
| XLSX: decimal / thousands separators | PARTIAL | STILL MISSING | No user choice; still automatic only |
| XLSX: worksheet per table | MISSING | DONE | `Opt.SHEET_PER_TABLE` (Cv6OptionsSheet) |
| XPS / OXPS | MISSING | DONE | `pdf_xps` → `XpsWriter`. Pages are written as pictures |
| HTML: multiple pages with a navigation page | MISSING | DONE | `pdf_html_set` → `HtmlSet` (zip) |
| Accessible text (alt text) | MISSING | DONE | `pdf_access_txt` → `Export7Run.accessibleText` |
| XML 1.0 | MISSING | DONE | `pdf_xml` → `PdfXml` |
| PostScript / EPS | MISSING | DONE | `pdf_ps` / `pdf_eps` → `PsWriter` (page pictures) |
| Images: TIFF | MISSING | DONE | `pdf_tiff` → `TiffWriter` (colour / grey / 1-bit) |
| Images: JPEG2000 / BMP | MISSING | DONE | BMP: `pdf_bmp` → `BmpWriter`. JPEG 2000 is NOT-FEASIBLE (no encoder) |
| PNG interlace / filter, JPEG format, tile size, monochrome, ICC | MISSING | STILL MISSING | Only monochrome is done (`Opt.BW`). Interlace, filter and ICC embedding are missing |
| Image settings: open the destination folder | PARTIAL | STILL MISSING | Unchanged; the results sheet opens or shares the files |
| Export All Images: format / colour space / resolution | PARTIAL | STILL MISSING | ExtractImagesScreen still has no format, colour or resolution choice |
| Export Selection As Word / Excel / PowerPoint | MISSING | STILL MISSING | Still OPEN. The selection can be exported as TXT (NveSelection.kt:179) or copied as an HTML/TSV table, not to Office files |

### §7 PDF Portfolio & Binder

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| View a portfolio (component list) | PARTIAL | DONE | Reader banner `DocNotice.PORTFOLIO` (NveDocBanners.kt:47-53) → tool `portfolio` → `PortfolioEditor` (MergePortfolioScreens.kt:142) |
| Portfolio opened in the reader → portfolio view | CODE-ONLY | DONE | Same banner, plus `PortfolioPrompt` (nav-attach) |
| View modes: Layout / Details / Cover; columns | PARTIAL | DONE | PortfolioEditorUi.kt:142-159 (details / tiles, columns) |
| Preview pane | MISSING | DONE | PortfolioEditorUi.kt:150, :252 (PDF first page, images) |
| Default View | MISSING | DONE | `pc_pf_default_view` → `PortfolioEdit.setView` |
| Read / print / search across files | MISSING | STILL MISSING | Search is done (`PortfolioOps.search`, PortfolioEditorUi.kt:173-183). There is no print across the parts |
| Create: add from folder / web / scanner / clipboard | MISSING | STILL MISSING | Folder is done (editor `addFolder`, PortfolioEditorUi.kt:111). Web page, scanner and clipboard are missing |
| Create: sort | MISSING | DONE | MergePortfolioScreens.kt:182-183 `FileSort` |
| Create a blank portfolio | MISSING | DONE | MergePortfolioScreens.kt:188-189 `pc_pf_make_blank` |
| Add files / folder to an existing portfolio; folders | MISSING | DONE | `PortfolioEdit.add` / `ensureFolder`; `pc_pf_new_folder` |
| Cover TOC clickable / regenerate | PARTIAL | DONE | `PortfolioOps.regenerateCover` (GoToE links), `pc_pf_regen_cover` |
| Delete a component | MISSING | DONE | `PortfolioEdit.remove`, `pc_pf_delete` |
| Edit name / description / custom field | MISSING | DONE | `rename` / `setDescription` / `setValue`; editor menu :207-212 |
| Convert a component to PDF | MISSING | DONE | `PortfolioOps.convertPart`, `pc_pf_convert` |
| Reduce size of component PDFs | MISSING | DONE | `PortfolioOps.compressParts`, `pc_pf_compress` |
| Portfolio properties (fields, sort, initial view) | MISSING | DONE | `setFields` (/Schema), `setSort`, `setInitial` |
| Drag-drop reorganise | MISSING | DONE | `PortfolioEdit.move` through the "move to folder" menu. There is no drag; the function is the same |
| Edit a component and save it back | MISSING | STILL MISSING | OPEN in PAGES_CONVERT_LOG D: parts open as extracted copies |
| PDF Binder (ordered files, TOC, project) | MISSING | DONE | Combine tab binder card → `BinderProject` / `BinderStore` (CombinePlusUi.kt:198-210) |
| Binder: TOC template with field codes | MISSING | STILL MISSING | OPEN |
| Binder: custom metadata fields | MISSING | STILL MISSING | OPEN |
| Binder: attach instead of convert | MISSING | DONE | `CombineEntry.attach`, `pc_attach_instead` (CombinePlusUi.kt:165-168) |
| Binder: output options | PARTIAL | STILL MISSING | Name and TOC exist; output goes to the app folder (plus the default save folder copy). There is no "open after" or "show in folder" choice |

### §8 Compliance (preflight, PDF/A, E, X, UA, VT)

PAGES_CONVERT_LOG lists a stage G (PDF/A and compliance), but no stage G log or merge exists on main. Most of §8 is therefore unchanged.

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Preflight against a profile | PARTIAL | STILL MISSING | Still one built-in PDF/A-2b check set |
| Preflight page range | MISSING | STILL MISSING | Unchanged |
| Profiles tab (libraries, favourites, search) | MISSING | STILL MISSING | Unchanged |
| Print-production checks | MISSING | STILL MISSING | Unchanged |
| Fixup: convert fonts to outlines | MISSING | STILL MISSING | Edit mode can turn one text block into outlines (NVE A), but there is no preflight fixup for non-embedded fonts |
| Create Report (PDF / XML / TXT / HTML, highlights) | PARTIAL | STILL MISSING | The accessibility checker can now attach its HTML report (stage F). The PDF/A preflight report is still on screen only |
| Preflight as an Action Wizard step | PARTIAL | STILL MISSING | Recipes have `ACCESS_CHECK` and `PDFA` (convert) steps, but no PDF/A check-only step and no success/error branch |
| Convert to PDF/A-1a / 1b | MISSING | STILL MISSING | `XmpWriter.packet` call still uses part 2 (PdfA.kt:209) |
| PDF/A-2a / 2u | MISSING | STILL MISSING | Same |
| PDF/A-3 | MISSING | STILL MISSING | Same |
| PDF/A-4 | MISSING | STILL MISSING | Same |
| Outline non-Unicode text (2u) | MISSING | STILL MISSING | Unchanged |
| Remove PDF/A information | MISSING | STILL MISSING | No remove command. The raw XMP replace in DocInfoSheet is the only workaround |
| ZUGFeRD verification | MISSING | STILL MISSING | Unchanged |
| Verify conformance of a claimed file | PARTIAL | STILL MISSING | The claim is now shown (Standards panel, PDF/A banner), but verification is still the approximate preflight |
| PDF/E convert / verify / remove | MISSING | STILL MISSING | The Standards panel only shows a pdfe claim (`NpfDocPanels.Standards`) |
| PDF/X convert | MISSING | STILL MISSING | Unchanged |
| PDF/X verify / remove | MISSING | STILL MISSING | The claim is shown only |
| PDF/UA verify (PDF/UA-1) | PARTIAL | STILL MISSING | The checker gained categories, manual verdicts, explanations and flicker/timed rules (stage F). It is still not a formal Matterhorn validation |
| PDF/UA fix tagging | PARTIAL | STILL MISSING | Headings, lists, artifacts, re-tagging and the tags editor are done (`AccessAutoTag`, `TagsEditor`). Still missing: table recognition (OPEN in stage F) |
| Mark as PDF/UA / remove PDF/UA info | CODE-ONLY | STILL MISSING | Marking is done: `XmpOps.markPdfUa` (DocPropsScreen.kt:94-165, DocInfoSheet.kt:129) and `AccessUa.markUa` (AccessibilityScreen.kt:232). Removing the pdfuaid claim is still missing |
| PDF/VT verify | MISSING | STILL MISSING | Unchanged |
| PDF version compatibility checks | PARTIAL | STILL MISSING | Unchanged |
| Standards wizard | PARTIAL | STILL MISSING | Unchanged (PDF/A-2b only) |
| Save As PDF/A / E / X | PARTIAL | STILL MISSING | ⋮ "Save as…" now offers a PDF/A-2b copy (`SaveAsSheet` `SaveAsKind.PDFA`). PDF/E and PDF/X are missing |
| Standard output at creation | PARTIAL | STILL MISSING | Only through a recipe step or the printer recipe |
| Standards panel in the navigation pane | MISSING | DONE | Reader INFO › Standards (`NpfPanels.kt:54, :102, :188-194`: claims, version, tagged, "Open preflight" → tool `pdfa`); PDF/A / UA banners (`DocNotices`) |
| Standards panel: OutputIntent | PARTIAL | STILL MISSING | `NpfDocPanels.Standards` has no output-intent field |
| PDF version choice when creating / optimising | MISSING | DONE | Optimize `OptimizeOptions.pdfVersion` (retain, 1.4 to 2.0), saved in optimizer presets. Not offered at creation |

---

## fcmp_09-13_22.md

### §9 View tab

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Logical page numbers (page labels) | CODE-ONLY | DONE (spot-checked) | ReaderScreen.kt:67 `rememberPageLabels` → :338 `PageIndicator(labels = pageLabels)` and :384 `GoToPageDialog(labels = …)` |
| Page Transitions (12 types, direction, speed, range, /Trans) | PARTIAL | DONE | View chip `nv_transitions` → `NveTransitionsSheet`; `core/nve/PageTransitions.kt`; Presentation5.kt:83 honours /Trans and /Dur |
| Reverse View | MISSING | DONE | View chip `nv_reverse_view` (NveHost.kt:102) |
| Text Viewer | PARTIAL | DONE | View chip → `NveTextViewer` (font, colours, widths, save TXT at :60/:87). "Auto rotate" width is NOT-FEASIBLE (DONE file) |
| Continuous Facing | MISSING | DONE | View chip (NveHost.kt:98) → PageView.kt:80-81 continuous spread |
| Spreadsheet split (4 panes) | MISSING | DONE | View chip → `NveQuadSplit` (NveHost.kt:211, F6 cycles the panes) |
| AutoScroll reverse / page jump / number keys | MISSING | DONE | `autoReverse` (NveState, PageOverlays5, NveKeys) |
| Zoom % entry / presets / below fit-width | PARTIAL | DONE | `NveZoomSheet` (numeric % and presets); MIN_ZOOM 0.25. The top end is about 1000-3000 %, not 6400 % (DONE file) |
| Marquee zoom | MISSING | DONE | View chip `nv_marquee` → `NveDrag.ZOOM_IN` (NveOverlays) |
| Magnifier window size / fisheye | PARTIAL | DONE | Lens size in NveSheets.kt:171. Fisheye is NOT-FEASIBLE (Android Magnifier has none) |
| Actual Size | MISSING | DONE | `ZoomPreset.ACTUAL` |
| Tab close Others / Left / Right / All | MISSING | DONE | NveTabs.kt:40-48 |
| Tab switching keys | MISSING | DONE | NveKeys.kt (`KEYCODE_TAB`, Ctrl+Shift+PgUp/PgDn) |
| Parallel view (tab groups) | PARTIAL | STILL MISSING | Split view, quad split and a second window exist. There are no tab groups (DONE file: PARTIAL) |
| Scroll lock | MISSING | DONE | Command `nv_scroll_lock` (NveHost.kt:186) |
| Bookmarks panel: search / find current / wrap | MISSING | DONE | ReaderPanels.kt:112 `OutlineToolbar` (ReaderNav.kt:74-90: search, find current, wrap, text size) |
| Articles panel / read article | MISSING | DONE | Command `nv_articles` → `NvePane.ARTICLES` (`NveArticlesUi`); INFO › Articles → "More" (NveHost.kt:263) |
| Portfolio component viewing | PARTIAL | DONE | Banner → `PortfolioEditor` with preview pane and cross-part search (stage D) |
| Navigation panels set | PARTIAL | DONE | INFO panels: layers (→ `e5_layers`), security, fields, destinations, tags, articles, standards (NpfPanels.kt); ATTACHMENTS panel; Signatures pane (`nv_pane_signatures`). Model Tree is NOT-FEASIBLE (no 3D) |
| Ruler units | MISSING | DONE | Unit in NpfPrefs / NveState (`n.unit`), grid sheet |
| Guides | MISSING | DONE | `ui/view5/NpfGuides.kt`; NveOverlays guides (tap on the ruler band) |
| Grid appearance (colour, offsets, subdivisions) | PARTIAL | DONE | View5Host.kt:205 `NpfGridSettings` |
| Snap to grid | MISSING | DONE | NpfGridSettings snap; `snap_grid` (NveState.kt:114) |
| Line Weights view | MISSING | NOT-FEASIBLE | DONE_view_edit / DONE_pdf3c_nav: PDFium-Android has no line-weight flag |
| Toggle (reading) ruler: vertical, opacity, reset | MISSING | STILL MISSING (spot-checked) | DONE_view_edit claims DONE, but its code (`rulerVertical`/`rulerOpacity`, NveOverlays.kt:312-352) is for the page *measurement* rulers. The reading band settings (View5Host.kt:207-211) still have only on/off, dim outside, height and colour. Vertical orientation, opacity and reset are missing |
| Named destinations panel | MISSING | DONE | INFO › Destinations + NVE `NvePane.DESTINATIONS` (rename / sort); `ui/edit5/DestinationsScreen.kt` |
| Word Count | MISSING | DONE | View chip / ⋮ `nv_word_count` → `NveWordCountDialog` |

### §10 Read Out Loud

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Pitch | CODE-ONLY | DONE | Read-aloud options sheet → `NveReadAloudPrefs` (View5Host.kt:187; pitch slider NveReadAloud.kt:81-86). Settings shows the same block with `showPitch = false` |
| Reading order: comments and alt text | MISSING | DONE | ReaderScreen.kt:163 → `NveReadText.pageText` (`core/nve/NveReadOrder.kt`) |
| Read form fields (Tab through, check state) | MISSING | STILL MISSING | Field values are read with the page. There is no Tab-through-fields mode (DONE file: PARTIAL) |
| Reading / speech preferences | PARTIAL | DONE | `NveReadAloudPrefs` (comments, fields, alt text, order) |

### §11 3D content

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 3D formats: view / navigate / measure / comment | MISSING | NOT-FEASIBLE | DONE_view_edit §11: there is no 3D renderer in the app |
| 2D preview when 3D is not active | PARTIAL | DONE | `DocNotice.THREE_D` banner ("contains 3D, poster shown"). That the poster renders is not verified on a device |
| 3D trust prompt | MISSING | NOT-FEASIBLE | No 3D renderer |
| 3D toolbar, axes, context menu | MISSING | NOT-FEASIBLE | Same |
| Model Tree panel | MISSING | NOT-FEASIBLE | Same (DONE_pdf3c_nav §40) |
| Navigation tools (rotate, spin, walk, fly) | MISSING | NOT-FEASIBLE | Same |
| Display (projection, render mode, lighting) | MISSING | NOT-FEASIBLE | Same |
| Cross Section | MISSING | NOT-FEASIBLE | Same |
| Camera properties | MISSING | NOT-FEASIBLE | Same |
| 3D views list | MISSING | NOT-FEASIBLE | Same |
| Add 3D | MISSING | NOT-FEASIBLE | Same |
| 3D measurement | MISSING | NOT-FEASIBLE | Same |
| 3D comments | MISSING | NOT-FEASIBLE | Same |

### §12 Select, copy, find, advanced search, index

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Column / rectangle selection | MISSING | DONE | Command `nv_column` → `NveDrag.COLUMN_SELECT` |
| Select All page text | MISSING | DONE | View chip `nv_select_all` |
| Selection menu: export, Word Count | MISSING | DONE | ReaderOverlays.kt:109 `NveSelectionChips` (export TXT :179); word count chip (ReaderOverlays.kt:113 `WordCount.count`) |
| Copy with Formatting | MISSING | DONE | `NveSelectionChips` copy formatted (HTML clip) |
| Paste into PDF | MISSING | DONE | `NveClipboard.paste` |
| Snapshot: whole page + paste back | PARTIAL | DONE | View chips `nv_page_snapshot` / `nv_area_snapshot` (NveHost.kt:108-109) |
| Find bar: include bookmarks / comments | PARTIAL | DONE | ReaderOverlays → `NpfScopeHits` (main's scopes; NVE's `NveFindScopes` is unused) |
| Highlight-all colour / opacity | PARTIAL | DONE | NpfPrefs hit colours (NpfSettingsScreen search section, :224) |
| Advanced Search: choose a folder | PARTIAL | DONE | GlobalSearchScreen.kt:55, :95 `NveGlobalSearchState` / `NveGlobalSearchBar` scopes |
| Scope: open PDFs / portfolio / selected index | MISSING | DONE | NveScope (open tabs, portfolio parts, index). There is only one app index, so "selected index" means that one |
| Include attachment contents | PARTIAL | DONE | `DocParts.attachmentText` |
| Additional criteria (author, title, dates) | MISSING | DONE | `NveCriteriaSheet` (NveSearchUi.kt:236, :250). /Info fields only, not XMP-only fields |
| Results: Sort By | MISSING | DONE | `NveSearchCore.Sort` (GlobalSearchScreen.kt:74) |
| Save results to PDF / CSV | MISSING | DONE | NveSearchUi export (`toCsv`) |
| Auto-searched layers / signatures | PARTIAL | STILL MISSING | Fields are indexed. Layer content and signature text are not |
| Search & Remove Text (word list) | PARTIAL | STILL MISSING | DONE_view_edit: NOT DONE. Workaround: Replace All with an empty value, or redaction |
| Catalog index definition | MISSING | STILL MISSING | `NveIndexSheet` adds stop words, numbers and excluded folders. Titles, include folders and custom fields are missing (DONE file: PARTIAL) |
| Index revise (rebuild, purge, backup) | PARTIAL | DONE | `NveIndexSheet` (purge confirm, `NveIndexBackup.export`) |
| Embedded index | MISSING | NOT-FEASIBLE | DONE_view_edit: Acrobat's embedded index format is proprietary |

### §13 Compare & Word Count

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Open from ECM | PARTIAL | NOT-FEASIBLE | NITRO_TOOLS_LOG §15: no ECM connectors in the app; SAF already reaches cloud providers |
| Page range to compare | MISSING | DONE | Compare2Screen.kt:176 `opts.oldRange` / `newRange` |
| Compare Text Only | PARTIAL | DONE | Kind switches (text only = the others off) |
| Compare Table | MISSING | DONE | `TableDetect`; "Tables" kind (Compare2Screen.kt:112, :153) |
| Detect page deletions / inserts | MISSING | DONE | `PageMatch.align` → `PageChange` (NITRO_TOOLS_LOG §15) |
| Markup colour / opacity / thickness / default | MISSING | DONE | `CompareStyle` + `StyleDialog` (Compare2Screen) |
| Filter by Text / Images / Annotations / Formatting | MISSING | DONE (spot-checked) | Tools `compare` → Compare2Screen.kt:135 `kinds`, :152-153 filter chips → `MarkedPage(… kinds …)` (:70) |
| Changes as comments / save result | PARTIAL | DONE | `CompareOutput.annotatedCopy` |
| Word Count (PDF document / selection) | MISSING | DONE | `core/tools5/WordCount.kt`: Compare2Screen.kt:354 `WordCount.ofPdf`; selection chip ReaderOverlays.kt:113; View chip `nv_word_count` |

### §22 Articles, layers, spell check, properties

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Image Editor Layers palette | MISSING | DONE | `ui/nve/imgedit/NveImageEditor.kt` (`EPanel.LAYERS`, `Blend` modes), reached from the edit-mode image sheet (ImageTools5.kt:676-692) |
| Articles: define article boxes | MISSING | DONE | `NveArticlesUi` + `NveArticleDrawOverlay` (ReaderScreen.kt:334) |
| Articles: properties | MISSING | DONE | `NveArticlesUi` (`core/nve/NveArticles.setInfo`) |
| Articles panel | MISSING | DONE | `NvePane.ARTICLES`; INFO › Articles |
| Read Article | MISSING | DONE | `NveArticleDrawOverlay` read mode |
| Modify articles (move / resize box, delete, renumber, combine) | MISSING | STILL MISSING | DONE_view_edit: modify is PARTIAL; a box cannot be resized by dragging |
| Automatic spell check while typing | PARTIAL | DONE | `ui/nve/Spell.kt` live check; main's `SpellCheckRow` in comment dialogs |
| Check Spelling dialog | MISSING | DONE | View chip / ⋮ `nv_spell_doc` → `NveDocSpell` |
| Dictionary management | MISSING | DONE | `SpellDictionarySheet` (Spell.kt) |
| Layers panel show / hide | MISSING | DONE | Edit5 `e5_layers` → `ui/edit5/LayersScreen.kt` (`Layers.setOn`); reader INFO › Layers links to it |
| Layers: list for pages, expand / collapse | MISSING | DONE | `Layers.list` depth / groups, `Layers.pagesOf` |
| Layers: reset to initial visibility | MISSING | DONE | `Layers.reset` |
| Layers: print / export / layer overrides | MISSING | DONE | `Layers.applyOverrides` |
| Import as Layer | MISSING | DONE | `Layers.importImage` / `importPage` |
| Merge / Flatten layers | MISSING | DONE | `Layers.merge` / `flatten` |
| Delete / reorder layers | MISSING | DONE | `Layers.delete` / `move` |
| Layer properties (name, intent, default, usage) | MISSING | DONE | `Layers.rename` / `setIntent` / `setOn` / `setLocked` / `setUse` |
| XMP append / save .xmp | MISSING | DONE | DocInfoSheet → `core/nve/XmpOps` (export, merge, replace) |
| Initial View honoured on open | MISSING | DONE (spot-checked) | ReaderState.kt:254 `applyInitialView(v, firstOpen)` → :303-309 (direction, layout, page mode) → ReaderScreen.kt:69-75 opens the panel. First open only, behind a preference |
| Initial view as Full Screen | PARTIAL | DONE | PageMode FullScreen → `"full"` → `chrome = false` (ReaderScreen.kt:72). It hides the reader chrome; it does not start Presentation mode |
| Security tab (permissions) | PARTIAL | DONE | `NveSecurityPanel` (DocInfoSheet, DocPropsScreen) |
| Fonts tab | PARTIAL | DONE | `NveFontsPanel` (`DocInspect.fonts`) |
| Print presets: paper source, pages, copies | MISSING | DONE | DocPropsScreen edits PickTrayByPDFSize / PrintPageRange / NumCopies; the print sheet reads them (`core/view5/PrintSettings`) |

---

## fcmp_14-21_23.md

### §14a Text and paragraph editing

All rows are reached through Reader Edit tab → content edit → `EditModeHost` (`TextTools5Host`, EditModeHost.kt:79; `core/edit/RichText.kt`, `ui/edit/TextTools5.kt`, NVE A).

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Edit Text from a text selection | PARTIAL | DONE | Selection chip → `NveEditRequest` → TextTools5Host selects the block |
| Rotate a paragraph / text box | MISSING | DONE | NVE A any-angle text (EditController TextTools5 hooks) |
| Font name | PARTIAL | DONE | `core/edit/EditFonts.kt` font picker (bundled Arabic, device fonts) |
| Underline / Strikethrough | MISSING | DONE | RichText run styles |
| Superscript / Subscript | MISSING | DONE | RichText (Ts baseline) |
| Font colour picker (RGB / CMYK) | PARTIAL | DONE | TextTools5 colour picker (RGB, HSV, CMYK → RGB); EditPanels.kt:106 |
| Character spacing | MISSING | DONE | RichText Tc |
| Writing direction LTR / RTL toggle | PARTIAL | DONE | TextTools5 direction toggle |
| Bullets / Numbering | MISSING | DONE | RichText list styles |
| Justify | MISSING | DONE | RichText (RTL aware) |
| Indent | MISSING | DONE | RichText indent |
| Line / paragraph / word spacing | MISSING | DONE | RichText (word spacing as TJ gaps for Type0) |
| Character scale | MISSING | DONE | Tz |
| Scanned page: Recognize Text button | PARTIAL | DONE | OCR button in edit mode on scanned pages (NVE A) |
| "No editable text" alert preference | MISSING | DONE | Edit preferences sheet |
| Vertical text | MISSING | DONE | NVE A |
| Move an existing text box | MISSING | DONE | NVE A move / resize width |
| Alignment guides | MISSING | DONE | Guides when moving text (A) and objects (B, `ImageTools5`) |
| Check Spelling | PARTIAL | DONE | `ui/nve/Spell.kt` in edit text + `NveDocSpell` |
| Link & Join / Link text boxes / Unlink | MISSING | DONE | NVE A. Limit: the links are saved on the device (prefs), not in the PDF |
| Join / Split text boxes | MISSING | DONE | NVE A (the split puts the second part below) |
| Merge text objects / split / remove kerning | MISSING | DONE | NVE A. Kerning removal inside nested form XObjects is not done (E10 PARTIAL) |
| Convert text to shape (outlines) | MISSING | DONE | NVE A (Type3 fonts stay text) |
| Text Mode (fill / stroke / invisible) | MISSING | DONE | Render modes in TextTools5 |

### §14b Objects, arrange, properties

All rows go through edit mode → `ImageTools5Host` / `ImageTools5Chips` (EditModeHost.kt:80, EditPanels.kt:201).

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Edit Object filter All / Text / Image / Shape / Shading | PARTIAL | DONE | `FilterRow5` (ImageTools5.kt:487-490) |
| Multi-select objects | MISSING | DONE | `MultiAlignBar5` (tap / marquee / all) |
| Copy / Cut / Paste objects | MISSING | DONE | `CopyTextObject5`, B copy/paste (across pages and documents; shadings cannot be copied) |
| Resize aspect lock / Scale X-Y % | MISSING | DONE | `PositionDialog5` (:861) |
| Rotate any angle, 15° snap | MISSING | DONE | `RotateDialog5` (:839) |
| Flip H / V | MISSING | DONE | MoreSheet5 |
| Shear | MISSING | DONE | `RotateDialog5` shear |
| Clipping add / clear | MISSING | DONE | `CropDialog5` (:912). Clear works only when the clip covers just that object |
| Object opacity | MISSING | DONE | `LookDialog5` (:979, opacity + 16 blend modes) |
| Stacking order | MISSING | DONE | Images, shapes and shadings. Moving text blocks is NOT-FEASIBLE (it breaks the text object state, DONE file) |
| Page-center objects | MISSING | DONE | `MultiAlignBar5` (`ImageTools5.Ref.PAGE`) |
| Align objects | MISSING | DONE | `MultiAlignBar5` (ImageTools5.kt:546-570) |
| Distribute objects | MISSING | DONE | Same (`DIST_H` / `DIST_V`) |
| Match size | MISSING | DONE | Same |
| Properties: Position X/Y, W/H, Scale | MISSING | DONE | `PositionDialog5` |
| Image object properties (opacity, compress, colour space) | PARTIAL | DONE | `PropsDialog5` (:1155), `CompressDialog5` (:1119), `ColorDialog5` (:1055) |

### §14c Images and image editor

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Draw New Image | MISSING | DONE | `ImgSheet5.DRAW_NEW` → `ImageEditorHost5(blank = true)` (ImageTools5.kt:676) |
| Image Editor window | MISSING | DONE | `ImgSheet5.EDITOR` → `NveImageEditor` (ImageTools5.kt:682-692) |
| Selection tools (rectangle, wand, lasso, polygon lasso) | MISSING | DONE | NveImageEditor.kt:59-61 tools |
| Painting tools (brush, eraser, bucket, eyedropper, burn / dodge, blend) | MISSING | DONE | NveImageEditor.kt:56-62; `ImageOps.Blend` |
| Spot Healing / Clone Stamp | MISSING | DONE | NVE E. The healing brush is a simple ring-average fill, with no texture synthesis |
| Filters (live preview, stackable) | MISSING | DONE | `ImageOps.FilterType` (9 filters), `EPanel.FILTERS` |
| Flip whole image / selection, Replace image | PARTIAL | DONE | NVE E flip; B flip; `ed_img_replace` |
| Edit using another application | MISSING | DONE | ImageTools5.kt:791 `ACTION_EDIT` round trip |
| Image annotation advanced (scale when, proportional, position) | PARTIAL | DONE | AnnotEditUi.kt:90 → `ImageAnnotPropsDialog5` |
| Place image on multiple pages | PARTIAL | DONE | `PagesDialog5` (:1140) |
| Image annotation hidden / border / locked | MISSING | DONE | `ImageAnnotPropsDialog5` |
| Align / distribute images | MISSING | DONE | `MultiAlignBar5` (page objects); 7.76 for annotations |

### §14d Shapes, shading, reflow

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Add Shapes as page content | PARTIAL | DONE | `ShapesFab` (EditModeHost.kt:60) → `ShapeEditorDialog` (`ShapeKind` line … closed Bezier, free) |
| Line cap / join / miter | MISSING | DONE | ShapeEditor `StylePanel` / `StrokeDialog5` |
| Shading objects (gradients) | MISSING | DONE | NVE E linear / radial gradients. Dragging the reference line freely is partial (presets and radius) |
| Reflow Editing | MISSING | STILL MISSING | DONE_view_edit: PARTIAL. It edits one page at a time as flowing text; per-block formatting and columns are lost, and text does not reflow across pages |
| Add Article Box | MISSING | DONE | `NveArticlesUi` |
| Add 3D | MISSING | NOT-FEASIBLE | No 3D renderer (DONE_view_edit §11) |

### §15 Links, destinations, actions

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Show the link URL before following | MISSING | DONE | `NveLinkPreview` (NveHost.kt:70; NveLinks.kt:71-83, with a switch to skip it) |
| Linked text menu: copy URL / convert linked page to PDF | PARTIAL | STILL MISSING | Copy URL is done (`nv_copy_url`). Converting the linked page to PDF is missing |
| Link thickness 0-12 | PARTIAL | DONE | LinkEditor.kt:97-98 slider 1-12 (0 = the invisible border style) |
| Link custom colour | PARTIAL | DONE | `NbmColorRow` custom colour dialog (NavUi.kt:77-88, LinkEditor.kt:96) |
| Destination: page view, "Set this position", FitV / FitB / FitR | PARTIAL | STILL MISSING | FitV, FitB, FitBH and FitR are done (`DestViewFields`). Still missing: navigating to a view and capturing it ("Set this position") |
| Destination in another PDF (named, password) | PARTIAL | DONE | Named destination of the other file (LinkEditor.kt:79-80). Password is NOT-FEASIBLE (an action cannot carry one, DONE_pdf3c_nav) |
| Destination inside an attachment (GoToE) | MISSING | STILL MISSING | The `NavAction` kinds are GoTo, Uri, GoToR, Launch, Named, Js. GoToE is written only by the portfolio cover |
| Named destination chosen from a list | PARTIAL | STILL MISSING | Still a typed field (LinkEditor.kt:88, NavUi.kt:218) |
| Recently used URLs drop-down | MISSING | STILL MISSING | Not found |
| Destination: Others (actions) | MISSING | DONE | `ActionListEditor` (NavUi.kt:161: page, web, file, named, JS, print / extract pages); other actions are kept read-only |
| Move / resize a link | MISSING | DONE | `core/edit5/LinkGeom.kt`, LinksScreen multi-select / align / resize |
| Create links from URLs: page scope (+ phones) | PARTIAL | DONE | LinksScreen.kt:209, :252 (`LinkDetect.find(…, phones)`, page scope) |
| Append All Web Links on the page | MISSING | STILL MISSING | Not found |
| View Web Links dialog: download / convert | PARTIAL | STILL MISSING | Listing only |
| Cross Reference groups | MISSING | DONE | Edit5 → `ui/edit5/CrossRefScreen.kt` (groups jump in a cycle, :30-59) |
| Cross References panel | MISSING | DONE | Same screen |
| Auto cross-reference links ("see page …") | MISSING | DONE | `core/edit5/AutoLinks.kt` (Auto Page Links, cross-reference links) from LinksScreen |
| Destinations panel | MISSING | DONE | `ui/edit5/DestinationsScreen.kt`; INFO › Destinations |
| Actions: submit / reset / import authoring | PARTIAL | DONE | Form designer Actions tab (`ui/formsx/ActionsEditor.kt`, DONE_comments_forms 8.66-8.68) |
| Actions: show/hide, JS, named, media, layer, article | MISSING | STILL MISSING | Show/hide, JS, named, SetOCGState and read article are authorable on fields (designer); JS and named on links and bookmarks; layer state via `Layers.setStateAction`. Still missing: a "play media / sound" action |
| Action triggers (mouse, focus, page, document) | MISSING | DONE | Field triggers (`FxActions.writeTriggers`); page open/close and document WC/WS/DS/WP/DP (`DocScriptsScreen`) |

### §16 Bookmarks

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| TOC page options (styles, orientation) | PARTIAL | DONE | BookmarksScreen.kt:378-394 (TocStyle) |
| TOC when combining files | MISSING | DONE | `CombineOpts.toc` |
| Set default bookmark zoom level | MISSING | STILL MISSING | Only auto-created bookmarks take a zoom (`AutoBmSettings.view`). There is no default for new manual bookmarks |
| Add bookmark from selected text | MISSING | DONE (spot-checked) | ReaderOverlays.kt:107 `ReaderBookmarkFromSelection` → ReaderNav.kt:36-50 `applyInPlace` |
| New bookmarks from structure (tags) | MISSING | STILL MISSING | Not found |
| Extract / delete pages via bookmarks | MISSING | DONE | BookmarksScreen.kt:160-189 `nbm_section_extract` / `nbm_section_delete` |
| Set Destination to the current view | PARTIAL | STILL MISSING | Page and zoom fields only |
| Named destination (Browse) | PARTIAL | STILL MISSING | Typed only |
| Add any action to a bookmark | MISSING | DONE | `NavActions` / `ActionListEditor` |
| Appearance: font style and colour | PARTIAL | DONE | Custom colour (`NbmColorRow`) |
| Use current appearance as new default | MISSING | STILL MISSING | This exists for links (`nbm_link_use_default`), not for bookmarks |
| Bookmark text size | MISSING | DONE | `OutlineToolbar` bigger / smaller (ReaderNav.kt:88-89) |
| Multi-select bookmarks | MISSING | DONE | BookmarksScreen.kt:125-133 (select, select all, export) |
| Expand / Collapse All | MISSING | DONE | BookmarksScreen.kt:123-124; reader outline menu |
| Auto from search strings | MISSING | DONE | Per-level keywords in / out (`AutoBmLevel.include` / `exclude`, BookmarksScreen.kt:354-355) |
| Auto level settings | PARTIAL | DONE | 5 levels; font, size, colour, indent, mask (`AutoBookmarks.kt`) |
| Auto level properties: text case, text before / after | MISSING | STILL MISSING | Not in `AutoBmLevel` |
| Auto Create page range | CODE-ONLY | DONE | `AutoBmSettings.pages`, `nbm_page_range` (BookmarksScreen.kt:323) |
| Auto: insert location | PARTIAL | DONE | `AutoInsert` (`nbm_insert_where`) |
| Auto: remove non-alphanumeric, stop words | MISSING | STILL MISSING | Only leading numbers can be stripped and keywords excluded. There is no non-alphanumeric or stop-word option |
| Auto: save / load settings | MISSING | DONE | `nbm_import_settings` / `nbm_export_settings` (JSON) |
| Export / import bookmarks XML | MISSING | DONE | BookmarksScreen.kt:126-127 |

### §17 Page thumbnails

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Reorganize from the reader thumbnails panel | PARTIAL | STILL MISSING | `ThumbnailsPanel` (ReaderPanels.kt:287-310) still only navigates. Page operations are in Organize (now with a per-thumbnail menu) |
| Enlarge / reduce thumbnails | MISSING | STILL MISSING | Organize has zoom now (`cellScale`). The reader panel is still fixed at 96 dp |
| Embed all page thumbnails | MISSING | DONE | e5_organize `OrgOp.THUMBS` → `PageOrganize.embedThumbnails` |

### §18 File attachments

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Add several files / a folder | MISSING | DONE | AttachmentsBrowser.kt:164 `OpenMultipleDocuments`, :167 `OpenDocumentTree` |
| Attachments panel in the viewer | PARTIAL | DONE | ReaderPanels.kt:72 `ReaderAttachmentsPanel` |
| Open an attachment | PARTIAL | DONE | AttachmentsBrowser.kt:108-112 ACTION_VIEW; PDFs open in the reader |
| Per-type open rules / trust prompt | MISSING | DONE | `AttachmentPolicy` (TrustManager.kt:176), `TrustPrefs.attachRules`, `DocSecurityGate` |
| Delete an attachment | PARTIAL | DONE | AttachmentsBrowser.kt:240-241 `AttachmentOps.delete` |
| Edit Description | MISSING | DONE | AttachmentsBrowser.kt:233-236 `AttachmentOps.setDescription` |
| Attachment security settings | MISSING | DONE | Same policy (settings in `Np2TrustSettings`) |

### §19 Multimedia

The video rows were "IN PROGRESS" in the comparison. The in-page player is on main now (`ui/reader/annot/VideoHost.kt`, `InPageVideoLayer.kt`, `VideoDialogs.kt`; VIDEO_INLINE_r5).

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Play video in the PDF | IN PROGRESS | DONE | `InPageVideoLayer` (PageCanvas hook), Comment tab Video tool |
| Add video (play area, embed) | IN PROGRESS | DONE | Drag a rectangle → `VideoPdf.writeScreen`. Only embedded clips are supported; URL or linked clips show "not embedded" |
| Video poster (none / file / movie) | IN PROGRESS | STILL MISSING | The poster is always taken automatically from the video (`VideoPoster`). There is no "none" or "from file" choice |
| Video properties: appearance, locked, title, alt text | IN PROGRESS | STILL MISSING | DONE_comments_forms: PARTIAL, through the generic comment properties dialog only |
| Renditions (add / edit / delete / reorder) | IN PROGRESS | STILL MISSING | DONE_comments_forms: NOT DONE |
| Media Settings | IN PROGRESS | STILL MISSING | NOT DONE |
| Playback settings (keep open, volume, controls, repeat N) | IN PROGRESS | STILL MISSING | Only autoplay, loop, muted and controls (`VideoPdf.Options`). No volume, repeat count or keep-open |
| Playback location (floating window, hidden, window options) | IN PROGRESS | STILL MISSING | In-page and full screen only |
| Use current settings as default for the media type | IN PROGRESS | STILL MISSING | NOT DONE |
| Media Actions tab (triggers) | IN PROGRESS | STILL MISSING | Only /AA /PV autoplay is written |
| Edit the play area: move / resize / align / distribute | IN PROGRESS | DONE | Long-press → frame handles (VIDEO_INLINE_r5); align / distribute through 7.76 |
| Audio: add a sound file | PARTIAL | DONE | Sound tool "Choose a sound file" (DONE_comments_forms §19) |
| Play native /Sound annotations | PARTIAL | DONE | `core/annot/SoundPdf` (decoded to WAV, in-app player) |

### §20 PDF Optimizer

All rows: Tools `optimize` → `OptimizeExportScreens.kt` → `FoxitOptimizeOptions` (:142, `ui/tools5/OptimizeFoxitUi.kt:29-76`) → `core/tools5/Optimizer` / `OptimizeCleanup` / `OptimizeScan`. (spot-checked: the :142 options and the :147 `OptimizePresets.estimate` call)

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| PDF version compatibility | MISSING | DONE | `OptimizeOptions.pdfVersion` |
| Estimate compression size | MISSING | DONE (spot-checked) | OptimizeExportScreens.kt:147-149 `OptimizePresets.estimate` |
| Reduce size of multiple files | PARTIAL | DONE | `OptimizeBatchSection` (:143; files, folder, reorder) |
| Output options (folder, naming, overwrite) | PARTIAL | DONE | `BatchOutputOptions` (OptimizeFoxitUi.kt:158-175) |
| User presets (save / delete) | MISSING | DONE | `E5ProfileBar("opt")` (OptimizeExportScreens.kt:100) |
| Separate colour / grey / mono settings | PARTIAL | DONE | `separateClasses` |
| Downsample method + threshold | PARTIAL | DONE | `core/tools5/Resample.kt`, `aboveDpi` |
| Compression ZIP / JPEG | PARTIAL | DONE | `ImgCompression` |
| JPEG 2000 | MISSING | NOT-FEASIBLE | NITRO_TOOLS_LOG: no JPX encoder for Android / PdfBox |
| JBIG2 / CCITT / Run Length | MISSING | DONE | `Jbig2` (lossless only; lossy JBIG2 is NOT-FEASIBLE), `Ccitt`, `RunLength` |
| High Compression (MRC) | MISSING | DONE | `OptimizeScan.mrc` (`nt_opt_mrc`) |
| Unembed fonts | MISSING | DONE | `OptimizeExtras.unembedFonts` (standard-14 or simple fonts; CID fonts keep their programs) |
| Subset embedded fonts | MISSING | DONE | `FontSubset` (`nt_opt_subset`) |
| Transparency flattening presets | MISSING | DONE | `flattenTransparencyDpi` |
| Discard form actions | MISSING | DONE | `nt_cl_form_actions` |
| Flatten form fields in Optimize | PARTIAL | DONE | `removeForms` (flattens) |
| Discard alternate images | MISSING | DONE | `nt_cl_alternates` |
| Discard document tags | MISSING | DONE | `nt_cl_tags` |
| Smooth lines → curves / merge image fragments | MISSING | DONE | `nt_cl_curves` (`LineFit`), `nt_opt_merge_fragments` |
| Discard embedded print settings | MISSING | DONE | `nt_cl_print` |
| Discard embedded search index | MISSING | DONE | `nt_cl_search` |
| Discard bookmarks in Optimize | PARTIAL | DONE | `removeBookmarks` |
| Discard comments / forms / multimedia in Optimize | PARTIAL | DONE | `removeAnnotations` / `removeForms`, `nt_cl_multimedia` |
| Discard external cross references only | PARTIAL | DONE | `nt_cl_external` |
| Discard object data | MISSING | DONE | `nt_cl_object_data` |
| Hidden layers + flatten visible layers | PARTIAL | DONE | `nt_cl_layers` |
| Replace LZW with Flate | MISSING | DONE | `nt_cl_lzw` |
| Remove invalid bookmarks / links | MISSING | DONE | `nt_cl_invalid` |
| Discard unreferenced named destinations | MISSING | DONE | `nt_cl_unused_dests` |
| Optimize page content | MISSING | DONE | `nt_cl_content` (`ContentTidy`) |
| Optimize Scanned PDF | PARTIAL | DONE | MRC, background, descreen, sharpen, B&W (OptimizeFoxitUi.kt:49-54) |

### §21 Action Wizard

Tools `recipes` → `RecipesScreen` / `RecipeEditor`; `core/tools5/Recipes.kt` (22 step types, :22-46), `core/batch/*`.

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Built-in: Prepare for Distribution | PARTIAL | DONE | `builtin_distribute` (sanitize, watermark, header/footer, optimize; Recipes.kt:176) |
| Built-in: Make Accessible | PARTIAL | STILL MISSING | `builtin_access`: OCR, SET_INFO (title), AUTO_TAG (language, tab order), ACCESS_CHECK. There is no alt-text step |
| Built-in: Make Accessible PDF/UA-1 | MISSING | DONE | `builtin_pdfua` (AUTO_TAG with pdfua = true) |
| Built-in: Publish Sensitive Information | MISSING | DONE | `builtin_sensitive` (redact, sanitize, optimize, protect) |
| Files to process: folder, open files | PARTIAL | DONE | `BatchSources` / `SafFolders` (folder with sub-folders, open tabs) |
| Command coverage | PARTIAL | DONE | 22 step types: JS, delete comments, header/footer, Bates, redact, sanitize, convert to Office, print, note, nested recipe… |
| Prompt User at run time | MISSING | DONE | `Step(ask = true)` |
| Panels, Add Instruction, Add Divider | MISSING | STILL MISSING | Instructions are done (`NOTE` step). There are no panels or dividers inside a recipe |
| Save with name and description | PARTIAL | DONE | `Recipe.description` (Recipes.kt:85) |
| Run: Stop / Resume | PARTIAL | DONE | RecipesScreen.kt:331-333 (`nbt_stop`, `BatchJobs.resume`) |
| Copy user recipes | PARTIAL | DONE | RecipesScreen.kt:183 `RecipeOps.clone` |
| Reorder the recipe list | MISSING | DONE | RecipesScreen.kt:185-186 `RecipeStore.move` |
| Custom Commands (label, tooltip, show options) | PARTIAL | DONE | A one-step recipe with a name, a description and `ask` (options shown at run) |
| Manage custom commands | PARTIAL | DONE | Same as recipes (edit, copy, move, export, import) |
| Custom commands inside actions | MISSING | DONE | `RUN_RECIPE` step |

### §23a Organize: page operations

Most rows: Edit5 tool `e5_organize` → `ui/edit5/PageOrganizeScreen.kt` (op chips :105, `OrgOp` :42) → `core/edit5/PageOrganize.kt`.

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Insert several files, order, preview | MISSING | STILL MISSING | Several files with up/down order are done (PageOrganizeScreen.kt:90, :261-271). Still missing: a preview of the file being inserted |
| Insert non-PDF files | PARTIAL | DONE | `pickAny` → `Cv6Engine` `any_pdf` (PageOrganizeScreen.kt:93-99) |
| Keep bookmarks / attachments of an inserted file | MISSING | DONE | `pc_keep_bookmarks` / `pc_keep_attachments` (:159-160) |
| Insert by dragging thumbnails from another document | MISSING | STILL MISSING | Organize accepts PDFs and images dropped from another app (`dragAndDropTarget`, OrganizeScreen.kt:72, :191). That drops whole files; dragging single page thumbnails between documents is still missing |
| Insert from Folder | MISSING | DONE | `NveOrganizeInsertMore` folder (OrganizeInsert.kt:62-95) |
| Insert from Open Files | MISSING | DONE | PageOrganizeScreen.kt:141-145 (`ReaderTabs.tabs`) |
| Insert from Clipboard | MISSING | DONE | :146-151 `clipboard_pdf`; OrganizeInsert.kt:86 |
| Insert from Web Page | PARTIAL | DONE | :152-158 `url_pdf`; OrganizeInsert.kt:100-108 |
| Insert Into other files | MISSING | DONE | `OrgOp.INSERT_INTO` → `PageOrganize.insertInto` (:369) |
| Thumbnail zoom | MISSING | DONE | OrganizeScreen `cellScale` zoom buttons |
| Rotate dialog with range | PARTIAL | DONE | `OrgOp.ROTATE` + `RangeSubset` (:111) |
| Move dialog (to page N, range) | PARTIAL | DONE | `OrgOp.MOVE` → `moveOrder` |
| Swap two pages | MISSING | DONE | `OrgOp.SWAP` (:118, :205) |
| Delete by range + subset | MISSING | DONE | `OrgOp.DELETE` + `RangeSubset` (:192) |
| Delete blank pages | MISSING | DONE (spot-checked) | Edit5Hub.kt:79 `e5_organize` → PageOrganizeScreen.kt:105 op chips → :194-200 `OrgOp.DELETE_BLANK` → `PageOrganize.deleteBlank` (PageOrganize.kt:174) |
| Extract subset | MISSING | DONE | `OrgOp.EXTRACT` with range + subset |
| Extract modes (one / several / each page) | PARTIAL | DONE | `ExtractMode` ONE / GROUPS / EACH (:123, :226-228) |
| Delete pages after extraction | MISSING | DONE | `pc_delete_after` (:124) |
| Alternate Mix (> 2 files, reverse each) | PARTIAL | DONE | `OrgOp.MIX` → `mixOrder` / `mix` (`e5_mix`, Edit5Hub.kt:82) |
| Replace from a non-PDF file | MISSING | STILL MISSING | PageOpsScreen.kt:72 still uses `rememberPdfPicker` (PDF only) |
| Split several files at once | MISSING | STILL MISSING | The Split tool and SPLIT_RANGES / SPLIT_BOOKMARKS work on one document |
| Split output options (location, names) | PARTIAL | DONE | `PartName` naming (:131); output copy to the default save folder |
| Split page into tiles (free lines) | PARTIAL | DONE | PageOpsScreen SPLIT_PAGE (any columns × rows, a slider per cut; `BoxTools.evenCuts` / `moveCut`) |
| Crop: show all boxes | MISSING | DONE | `CropScreen`, `BoxTools.pageBoxes` |
| Crop: constrain proportions | MISSING | DONE | `BoxTools.constrain` |
| Crop: page size / centering; units | PARTIAL | STILL MISSING | Units are done (pt / mm / in, `BoxUnit`). Page size and centering are still only in the separate Resize tool |
| Resize: constrain proportions | MISSING | DONE | `ResizeScreen` |
| Resize multiple files | MISSING | DONE | `ResizeScreen` batch files |
| Flatten with a page range | PARTIAL | DONE | `OrgOp.FLATTEN` → `PageOrganize.flattenPages` (:207) |
| Reverse: range + subset | PARTIAL | DONE | `OrgOp.REVERSE` |
| Rearrange pages to the bookmark order | MISSING | DONE | `OrgOp.BOOKMARK_ORDER` → `bookmarkOrder` (:238) |

### §23b Page marks and page numbers

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Header & Footer templates (save, delete, import / export) | PARTIAL | DONE | `E5ProfileBar("hf")`. Administrator templates are NOT-FEASIBLE (no managed-configuration channel, NITRO_TOOLS_LOG) |
| Header & Footer batch add | MISSING | DONE | HeaderFooterScreen.kt:144-153 (`BatchSources`, `BatchOutputOptions`) |
| Batch Remove All page marks with a log file | MISSING | DONE | HeaderFooterScreen.kt:183 `runBatchWithLog` |
| Background / Watermark from a PDF page | MISSING | DONE | DecorScreen.kt:65, :87, :119 `DecorSource.PDF` |
| Background / Watermark templates | PARTIAL | DONE | `E5ProfileBar` (watermark, background) |
| Background / Watermark batch add | PARTIAL | DONE | Decor batch section (`OptimizeBatchSection` in DecorScreen) |
| Bates: add folders / open files / sort | MISSING | DONE (spot-checked) | BatesScreen.kt:85 `BatchSources(files)` |
| Bates: output options (folder, naming, log) | PARTIAL | DONE | BatesScreen.kt:129-132 `BatesNaming`; `Bates.log` |
| Bates: digits up to 15 | PARTIAL | DONE | BatesScreen.kt:93 `coerceIn(1, 15)` |
| Bates: increment (negative) | MISSING | DONE (spot-checked) | BatesScreen.kt:95-97 `spec.increment` |
| Bates: rotate | MISSING | DONE | `BatesLayout.rotation` |
| Bates macro in Header & Footer | PARTIAL | DONE | `HeaderFooterOps.draw` numbers `{bates}` (NITRO_TOOLS_LOG §12 fix) |
| Bates > Remove All | CODE-ONLY | DONE (spot-checked) | BatesScreen.kt:166 `HeaderFooterOps.removeBates` |
| Page labels shown in the reader | CODE-ONLY | DONE | Same as §9 logical page numbers |

---

## Spot-checks (button → handler → core)

1. **Get Help on keywords:** Commands5.kt:146 → `NveHelp.openWithQuery` (HelpUi.kt:42) → HelpCenter5.kt:60 `takeQuery()`. OK.
2. **Status bar zoom and coordinates:** ReaderScreen.kt:311 `nveMouseModifier` (cursor) and :333 `NvePageOverlays` → NveOverlays.kt:73 → `StatusPill` :456-471. OK.
3. **Drag and drop open:** MainActivity.kt:62 `NveDropRoot` → DropTarget.kt:44 `onDrop` → `incoming` URI. OK.
4. **Copy path from Recent:** HomeComponents.kt:128 → Folders.kt:127-135 `setPrimaryClip`. OK.
5. **Insert from web / clipboard / folder:** OrganizeScreen.kt:322 → OrganizeInsert.kt:85-108 → `ToPdf.url` / SAF tree → `OrganizeState.insertFrom` :174. OK.
6. **Delete blank pages:** Edit5Hub.kt:79 → PageOrganizeScreen.kt:105 / :194 → `PageOrganize.deleteBlank` :174. OK.
7. **Optimizer Foxit options:** OptimizeExportScreens.kt:142 `FoxitOptimizeOptions` → OptimizeFoxitUi.kt:47-76; :147 `OptimizePresets.estimate`. OK.
8. **Bates:** BatesScreen.kt:85 `BatchSources`, :95 increment, :166 `HeaderFooterOps.removeBates`. OK.
9. **Page labels in the reader:** ReaderScreen.kt:67 → :338 / :384. OK.
10. **Initial view on open:** ReaderState.kt:254 → :303-309 → ReaderScreen.kt:69-75. OK (first open only, behind a preference).
11. **Bookmark from a selection:** ReaderOverlays.kt:107 → ReaderNav.kt:36-50. OK.
12. **Compare filters and Word count:** Compare2Screen.kt:135 / :152-153 / :176; ReaderOverlays.kt:113 `WordCount.count`. OK.
13. **Reading ruler orientation / opacity / reset: claim FAILED.** DONE_view_edit says DONE, but View5Host.kt:207-211 (the reading band) has no orientation, opacity or reset. The NVE `rulerVertical` / `rulerOpacity` flags belong to the measurement rulers (NveOverlays.kt:312-352). The row is marked STILL MISSING.

Other notes found during the check:
- `NveFindScopes`, `NveFindExtraResults` and `NveHitStyleSettings` (ui/nve/search/NveSearchUi.kt) have no callers. Main's `NpfScopeHits` and NpfPrefs hit colours are used instead. This is code-only duplication, not a missing feature.
- Recent list size is set in two places: `NpfPrefs.RECENT_LIMIT` (NpfSettingsScreen) and `NveFolders.recentSize` (ShellSettings). HomeScreen applies both. These should be merged.

---

## STILL MISSING (sorted by importance)

### Med
- §4 Convert-time bookmarks: Excel sheet names and PPT slide titles are not made into bookmarks; Word, HTML and text headings are (pages/convert).
- §8 Preflight profiles: still one fixed PDF/A-2b check set (pages/convert, compliance).
- §8 PDF/A-1a / 1b conversion: only PDF/A-2b (pages/convert, compliance).
- §8 Verifying a claimed file: approximate preflight only, not veraPDF level (pages/convert, compliance).
- §8 PDF/UA-1 validation: checker only, not a formal Matterhorn validation (pages/convert, accessibility).
- §8 PDF/UA tagging fix: tables are not recognised; headings, lists and re-tagging are done (pages/convert, accessibility).
- §14d Reflow Editing: one page at a time, formatting and columns lost, no reflow across pages (edit).
- §15 Link menu: no "convert the linked page to PDF"; copy URL is done (reader/view).
- §15 Link destination: no "Set this position" capture of the current view (edit, links).
- §16 Bookmark action: named destination is typed, not picked from a list (edit, bookmarks).
- §17 Reader thumbnails panel only navigates; reorganising happens in Organize (reader/view).

### Low
- §1 QAT: no choice of position for pinned commands (nav/settings).
- §1 Mouse-wheel tab switching over the tab strip (reader/view).
- §4 Multiple files: outputs are not written next to the source files (pages/convert).
- §4 "Each file" To-PDF tab: no folder input and no sort (pages/convert).
- §4 Named creation presets (Standard / Print / Smallest / PDF/A / PDF/UA) (pages/convert).
- §4 Tagged PDF at creation (pages/convert).
- §4 Web to PDF: conversion levels / whole site (pages/convert).
- §4 Web to PDF: single long page, bookmarks, tags, headers and footers (pages/convert).
- §4 Web to PDF: screen media and scaling (pages/convert).
- §4 Open a web link as a new PDF (reader/view).
- §4 Scan a paper form into a fillable form: no raster detection (comments/forms).
- §4 Template library: search, categories, more templates (pages/convert).
- §4 Office: Excel comments, PPT notes, transitions, hidden slides, ranges (pages/convert).
- §4 Office: Excel fit options (pages/convert).
- §4 Word: comments to notes, footnote and endnote links (pages/convert).
- §4 Mail merge: record range and e-mailing each PDF (pages/convert).
- §4 Virtual printer: no PDF version choice; append is not feasible (pages/convert).
- §4 Combine: a portfolio input is not unpacked into its parts (pages/convert).
- §4 Combine: no thumbnail view (pages/convert).
- §4 Combine: cannot expand files into pages and move pages across files (pages/convert).
- §4 Combine: no output folder / overwrite / "don't open" choice (pages/convert).
- §4 Combine: no "save as portfolio" option (pages/convert).
- §4 Combine: no choice of how EML attachments are handled (pages/convert).
- §4 OCR: table line segments are not written as path objects (tools/OCR).
- §4 Watched folder (tools/OCR).
- §5 Scan paper sizes: no full A0-A10, B, C or JIS sets (tools/OCR).
- §6 DOCX export: include comments (pages/convert).
- §6 XLSX export: choice of decimal and thousands separators (pages/convert).
- §6 Image export: PNG interlace / filter and ICC embedding (pages/convert).
- §6 Open the destination folder after export (pages/convert).
- §6 Extract images: format, colour space and resolution choices (pages/convert).
- §6 Export a selected area as Word / Excel / PowerPoint (pages/convert).
- §7 Portfolio: print across all parts (pages/convert).
- §7 Portfolio: add parts from a web page, the scanner or the clipboard (pages/convert).
- §7 Portfolio: edit a part and save it back into the portfolio (pages/convert).
- §7 Binder: Word TOC template with field codes (pages/convert).
- §7 Binder: custom metadata fields (pages/convert).
- §7 Binder: "open after" and "show in folder" output options (pages/convert).
- §8 Preflight page range (compliance).
- §8 Preflight profile library (compliance).
- §8 Print-production checks (compliance).
- §8 Fixup: convert fonts to outlines (compliance).
- §8 Preflight report export as PDF / XML / TXT (compliance).
- §8 Preflight check-only recipe step with success / error branch (compliance).
- §8 PDF/A-2a / 2u (compliance).
- §8 PDF/A-3 (compliance).
- §8 PDF/A-4 (compliance).
- §8 Outline non-Unicode text (compliance).
- §8 Remove the PDF/A claim (compliance).
- §8 ZUGFeRD verification (compliance).
- §8 PDF/E (compliance).
- §8 PDF/X convert (compliance).
- §8 PDF/X verify / remove (compliance).
- §8 Remove the PDF/UA claim; marking is done (compliance).
- §8 PDF/VT (compliance).
- §8 PDF version compatibility checks (compliance).
- §8 Standards wizard (compliance).
- §8 Save As PDF/E / PDF/X; Save As PDF/A-2b is done (compliance).
- §8 Standard output at creation (compliance).
- §8 Standards panel: show the OutputIntent (compliance).
- §9 Tab groups (reader/view).
- §9 Reading ruler: vertical orientation, opacity, reset (reader/view).
- §10 Read form fields aloud in a Tab-through mode (reader/view).
- §12 Search inside layer content and signature text (reader/view).
- §12 Search & Remove Text tool (edit).
- §12 Catalog index: titles, include folders, custom fields (reader/view).
- §15 Link to a destination inside an attachment (GoToE) (edit).
- §15 Named destination picked from a list (edit).
- §15 Recently used URLs drop-down (edit).
- §15 Append all web links on a page (edit).
- §15 View Web Links: download and convert (edit).
- §15 "Play media / sound" action (edit).
- §16 Default zoom for new bookmarks (edit).
- §16 Bookmarks from structure tags (edit).
- §16 Set a bookmark's destination to the current view (edit).
- §16 Use the current bookmark appearance as the default (edit).
- §16 Auto bookmarks: text case, text before / after the title (edit).
- §16 Auto bookmarks: remove non-alphanumeric characters, stop words (edit).
- §17 Thumbnail size in the reader panel (reader/view).
- §19 Video poster choice: none / from a file (comments/forms).
- §19 Video properties dialog: appearance, title, alt text (comments/forms).
- §19 Video renditions (comments/forms).
- §19 Video media settings (comments/forms).
- §19 Video playback settings: volume, repeat count, keep open (comments/forms).
- §19 Video playback location: floating window, hidden (comments/forms).
- §19 Video defaults per media type (comments/forms).
- §19 Video Media Actions tab (comments/forms).
- §21 "Make Accessible" recipe: alt-text step (tools/OCR, batch).
- §21 Panels and dividers inside a recipe (tools/OCR, batch).
- §22 Articles: drag to resize a box (reader/view).
- §23a Insert: preview of the file being inserted (pages/convert).
- §23a Drag page thumbnails between documents (pages/convert).
- §23a Replace pages from a non-PDF file (pages/convert).
- §23a Split several files at once (pages/convert).
- §23a Crop dialog: page size and centering (pages/convert).

## Counts

Counted by script from the "Now" column.

| Status | Rows |
|---|---|
| DONE | 353 |
| STILL MISSING | 106 |
| NOT-FEASIBLE | 21 |
| OWNER-ACTION | 1 |
| N/A-ANDROID | 0 |
| **Total rows checked** | **481** |

By file:
- fcmp_01-08: DONE 100 · STILL MISSING 66 · NOT-FEASIBLE 4 · OWNER-ACTION 1 (171 rows).
- fcmp_09-13_22: DONE 73 · STILL MISSING 7 · NOT-FEASIBLE 15 (95 rows).
- fcmp_14-21_23 (including the 11 §19 video "IN PROGRESS" rows): DONE 180 · STILL MISSING 33 · NOT-FEASIBLE 2 (215 rows).

How mixed rows were counted:
- Rows whose remaining part is not feasible count as DONE: Invoice Print, tutorial videos on the start page, Magnifier fisheye, the Text Viewer's "auto rotate" width, BMP / JPEG 2000 export, JBIG2 lossy, and the password in a GoToR link.
- Rows that are entirely not feasible count as NOT-FEASIBLE: tutorial videos, CAD, HWP, .doc, Line Weights, ECM in Compare, the embedded index, Add 3D, JPEG 2000 in Optimize, and 12 of the 13 §11 3D rows.

# Part D — Foxit §24–42 + OLD_PLAN_AUDIT
# Final completeness check: Foxit 24–27 / 34 / 28–42 and OLD_PLAN_AUDIT, against main

- **Checkout:** `D:\pdf\final-check-wt`, main fa724fd / 8555d74. It was read-only: nothing was built and no gradle was run.
- **Method:** each non-HAVE row was compared with the DONE files and then confirmed in the code. A row counts as DONE only when its code is reachable from the UI: a tool id, button, menu, panel, settings row or key binding.
- **Spot-checks:** 25 DONE rows were followed in the code from button to handler to core function. They are marked "(spot-checked)" in each part.

The detailed tables are in five parts, appended below in this order:
1. Foxit 24–27 (comments, measure, forms, JavaScript)
2. Foxit 34 + 28–33 (accessibility, protect, redaction, sign, share, ECM, ConnectedPDF)
3. Foxit 35–37 (AI, print, preferences)
4. Foxit 38–42 (plug-ins, shortcuts, panels, version history, Foxit-only)
5. OLD_PLAN_AUDIT (Office/sync, AI/search/forms, items 1–20, the ranked list)

## Totals

| Part | Rows | DONE | STILL MISSING | NOT-FEASIBLE | OWNER-ACTION | N/A-ANDROID |
|---|---|---|---|---|---|---|
| Foxit 24–27 | 134 | 119 | 14 | 1 | 0 | 0 |
| Foxit 34 + 28–33 | 127 | 97 | 24 | 3 | 3 | 0 |
| Foxit 35–37 | 128 | 85 | 34 | 8 | 0 | 1 |
| Foxit 38–42 | 98 | 60 | 26 | 8 | 4 | 0 |
| **Foxit subtotal** | **487** | **361** | **98** | **20** | **7** | **1** |
| OLD_PLAN_AUDIT | 143 | 111 | 27 | 3 | 2 | 0 |
| **All** | **630** | **472** | **125** | **23** | **9** | **1** |

Notes on the counts:
- **AI rows that need the proxy.** Parts 35–37 and OLD_PLAN mark them DONE, because they work with the user's own key; the proxy deploy is noted inside each row. Part 38–42 marks four of them OWNER-ACTION (vision, image mode, formula, eSign online form / branding).
- **Overlap.** Some old-plan ranked rows repeat an earlier item: B13, AI-8, C1, B11, B1.

## Where I disagree with the DONE files
- **24.33 (cloud scallop direction):** DONE_comments_forms says NOT DONE because "the renderer always draws outward". The app draws its own cloud appearance (`CloudPath`), so it is feasible. Marked STILL MISSING.
- **34.27 (reading option) and the NpfPrefs "reading order" preference:** DONE_pdf3c_nav lists the reading-order preference as done, but `NpfPrefs.readingOrder` is never read, so it is code-only. Marked STILL MISSING.
- **Header of DONE_view_edit.md:** its first line still says "Not merged to main", but git log shows the branch was merged (315a67b).

## STILL MISSING: combined list, by importance
The owner area for each row is in brackets.

**High**
- OLD I1: Fill & Sign on flat forms has no detected tap targets. Field recognition exists only in the form designer. [comments/forms]
- OLD K2: no "redo OCR / replace text layer" for pages that already have text (OcrRunner / MixedPageOcr skip them). [tools/OCR]

**Med**
- 24.87: comment summary has no connector-line or sequence-number layouts and no connector colour or opacity. [comments/forms]
- §28: certificate protection cannot be saved as a policy; SecurityProfiles covers passwords only. [sign/protect]
- 34.7: only 8 checker rules have a Fix. [tools/OCR, accessibility]
- §40 / 34.44 / 34.46 / OLD 18b: no Order panel (drag reading order) and no Content panel. Only move / indent in the tags tree. [tools/OCR, accessibility]
- §37 Languages: only Arabic and English, with no "follow system" option. [nav/settings]
- §37 Editing: no automatic OCR when editing a scanned page. [edit]
- §37 Forms: field recognition does not run automatically after Create PDF. [comments/forms]
- OLD M5: about 51 icon-only buttons still have `contentDescription = null`, for example BookmarksScreen:150-151 and BatesScreen:81-82. [all areas]
- OLD A1: reflow does not rebuild tables. [reader/view]
- OLD F4 / §41: Combine has no thumbnail page picking or reordering. [pages/convert]
- OLD 19b / §42: preflight is PDF/A-2b only, with no profiles, PDF/A-1b/3b, PDF/X, PDF/E or PDF/VT. [pages/convert]
- OLD: XFA picture clauses, and XFA scripts other than initialize/calculate. [comments/forms]
- OLD: sync near-realtime still uses a 60 s cloud poll, with no long-poll or push. [files]

**Low**
Comments / measure / forms (24–27, §37, §41):
- 24.18 / 24.66: no floating pop-up note window.
- 24.27: callout parts cannot be dragged separately.
- 24.33: no inward or outward cloud scallops.
- 24.58: custom stamps have no "replace image" and there are no shared/admin stamps; §41 has no enterprise stamps with an edit lock either.
- 24.102: no superscript / subscript in text comments. 24.105: no numbering or indent. 24.107: no paragraph spacing.
- 25.18: a measurement label cannot be dragged on its own. 25.19: no endpoint drag handles.
- 25.27: 10 tickmark symbols, not 31.
- §41: no volume, polylength, diameter or radius measures.
- 27.10: several JavaScript doc API calls are still stubs.
- §37: no tab-order-from-structure preference, no auto-open pop-ups, no pencil stroke-merge timeout, no field overflow indicator, no default e-mail subject or body.
- §41: no sequential stamp; editing comments made in other editors (Bluebeam) is not built or tested; the CSV summary has fixed columns; pencil has no hide-cursor and no pencil-over-text highlight.

Sign / protect:
- 26.68: dynamic-XFA and XML signatures cannot be signed or verified.
- §37: no prompt to trust an unknown signer on open, no "use expired timestamps" or "ignore validation info" options, no "ask if verification info is too big" option.

Accessibility (34):
- 34.2: the checker has no page range.
- 34.18: bookmarks cannot be built from chosen structure elements.
- 34.24: no fix for nested alt text.
- 34.26: no setup assistant.
- 34.27: no per-document reading option (the preference is code-only).
- 34.38: alt text cannot be edited on the page.
- 34.39 / 34.45: no touch-up tool and no area reading order.
- 34.40: content groups are not shown on the page.
- 34.41: no on-page table editor.
- 34.49: Find Element finds only unmarked annotations and links.
- §37: the Action Wizard log cannot be saved, and Make Accessible writes no PDF/UA report.

Share / review / cloud (§31–33, §37, OLD):
- No "take ownership" of other reviewers' comments (§31, §37, OLD).
- "Remove Me" from a review is local only; the server has no endpoint.
- No local reminder before a review expires, no To/Cc auto-complete, no send-as-FDF size rule.
- §32: no Dropbox Team Folders, no OneDrive Business / SharePoint library, no Egnyte, no DMS preview.
- §33: no Connected open message, no clone, no switch to turn ConnectedPDF off.

AI / office (§35, §41, OLD):
- No prompt editing of an existing image; inserting a generated image is not an undo step (AI-8).
- Workspace has no infographic or web-page outputs; Spaces has no slides entry; the PPTX has no speaker notes (B13).
- Translation has no Danish.
- The video summary uses system TTS only and is untested on devices (B15).

Print (§36, OLD):
- No bleed, crop or registration marks except poster cut marks; no hairline fix (G8).
- No "current view" print range; the only route is Snapshot → Print.

Reader / view (§37, OLD):
- No full-screen request alert and no PDF/A view mode (PREF-5).
- Full screen has no per-item choices.
- No facing page count or page gap setting.
- The page size is not shown on the page.
- No "keep zoom on Go-to-Destination" preference.
- No tab groups and no two editable documents side by side (SHL-5).
- The tabletop split exists in code but is not wired.

Keyboard (§39):
- Unbound: Ctrl+L / I / 8 / Shift+8 / Enter / Alt+V, Ctrl+X / Shift+A, annotation copy/paste keys, Ctrl+Shift+C, Ctrl+B to add a bookmark, Ctrl+Alt+D, the read-aloud keys B / P / Y, F10 / Shift+F10 / Ctrl+F11, Tab between form fields, Ctrl+J, Ctrl+Shift+K.

Pages / convert (§41, §42, OLD):
- No page labels from bookmarks or page regions.
- Grayscale is not a preflight fix.
- No custom blank page size and no template search (item 12).
- No export of a selected area to Word / Excel (8b).
- No batch convert from the Files multi-select (16b).
- No watched folder for the Action Wizard.

Edit (§37, OLD):
- Changed text is not shown in colour.
- Reflow editing works on one page only; nested, mirrored or skewed XObject text cannot be edited (20b / E10).

Nav / settings / files (§37, OLD):
- No per-message notification settings gear and no debug-logging switch.
- Index preferences lack XMP, custom properties and structure tags; search cannot ignore Asian character width, does not show the document title in results, and has no cache size limit.
- No ads banner in ResultSheet.
- The invite deep link has no intent-filter (domain verification by the owner is also needed).

Tools / OCR (OLD):
- No QR scanning in the scanner (K11).

## OWNER-ACTION (9)
- §30: eSign bulk send, online form and branding. The esign worker must be redeployed.
- §41: chat about images, the agent's Create Image mode, and formula to LaTeX. These need the vision / image proxy routes (or the user's own vision key).
- §41 (2023.3): eSign online form / branding. The esign worker must be redeployed.
- OLD: cloud.properties and the workers (collab, share, esign, connected) plus the Drive OAuth client; real ads / licence properties.

---
# Final check: Foxit sections 24–27 (comments, measure, forms, JavaScript), non-HAVE rows

Source: `gaps/fcmp_24-27_34.md`. DONE claims come from `gaps/DONE_comments_forms.md`, plus `gaps/DONE_pdf3c_protect_sign.md` and `gaps/DONE_pdf3c_nav.md` for 26.14, 26.15, 26.68 and 27.12. Every claim was checked against the code on main (`D:\pdf\final-check-wt`, fa724fd).

Paths are relative to `app/src/main/java/com/smartdocs/editor/`.

**Shared UI entries** (each one was checked for a caller):
- **Comments "more" menu:** `ui/reader/annot/CommentExtrasUi.kt:CommentExtrasMenuItems`, called from `Annot2Toolbar.kt:59`.
- **Selection overflow:** `SelectionMoreButton`, called from `AnnotEditUi.kt:93` and `MultiActionBar.kt:61`.
- **Creation options row:** `CreationExtrasRow`, called from `ReaderToolbars.kt:388`.
- **Dialogs:** `CommentExtrasDialogs`, called from `Annot2Dialogs.kt:130`.
- **Stamp placement bar:** `PlacementBar`, called from `ReaderScreen.kt:340`.
- **Stamp picker:** `ui/view5/StampPicker5.kt:V5StampPicker`, called from `ReaderScreen.kt:390`.
- **Form designer:** `ui/formsx/FormDesignerScreen.kt`, reached through the tool id `form_builder` (`ToolScreens.kt:98` → `ui/forms/FormBuilderScreen.kt`) and through the reader Fill & Sign chip "Edit form fields" (`ReaderToolbars.kt:209`).

## 24a. Text markup, pin, typewriter

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 24.9 Adjust markup range | MISSING | DONE | `AnnotEditUi.kt:97-100` has the ←\| \|→ buttons, which call `CommentExtras.adjustMarkup`. It moves the start or end by one word, so there are no drag handles. |
| 24.12 Pop-up note font prefs | MISSING | DONE | "more" menu → `cx_note_prefs` dialog (font size and opacity) → `CommentExtras.saveNotePrefs`. |
| 24.13 Sticky-note indicator on markups | MISSING | DONE | "more" menu toggle `cx_note_indicators`; drawn in `PageCanvas.kt:276`. |
| 24.18 Note pop-up resize / move / reset | PARTIAL | STILL MISSING | Reset location exists (`cx_popups_reset_action` → `CommentOps7.resetPopups`). There is still no floating, resizable pop-up window; notes open in a dialog. |
| 24.19 Note icon types | PARTIAL | DONE | `AnnotStyle.NOTE_ICONS` (15 icons): chips in `CreationExtrasRow` and a picker in `PropsDialog`. |
| 24.21 Attachment icons | PARTIAL | DONE | `AnnotStyle.ATTACH_ICONS` (Graph, Paperclip, PushPin, Tag) in `CreationExtrasRow`. |
| 24.24 Copy comment | MISSING | DONE | Selection overflow → `cx_copy` → `CommentExtras.copy`. |
| 24.25 Spell check in comments | PARTIAL | DONE | `ui/reader/annot/SpellCheck.kt:SpellCheckRow` (system SpellCheckerSession), used in the edit-text dialog (`AnnotEditUi.kt:154`) and the note dialog (`ReaderDialogs.kt:119`). |
| 24.27 Callout parts dragged separately | MISSING | STILL MISSING | Not built (DONE file: NOT DONE). There are no per-part /CL handles. |

## 24b. Drawing tools

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 24.31 Square / circle constraint | MISSING | DONE | `cx_square_constraint` chip (`CreationExtrasRow`) → `annot2.constrain`, used in `PageGestures.kt:118`. |
| 24.33 Cloud scallops inward / outward | PARTIAL | STILL MISSING | Not built. The DONE file calls it NOT DONE because "the renderer always draws outward". That reason is weak: the app writes its own appearance (`core/annot/CloudPath.kt`), so the scallop direction could be controlled there. |
| 24.40 Resize to anchor | MISSING | DONE | Multi-select overflow → Align → `SAME_W` / `SAME_H` / `SAME_BOTH` → `CommentExtras.align`. |
| 24.41 Copy encircled text into drawing pop-ups | MISSING | DONE | "more" menu toggle `cx_copy_encircled`; `CommentExtras.addNew` fills the text for RECT, OVAL, POLYGON and CLOUD. |
| 24.42 Show text in line (plain lines) | PARTIAL | DONE | `cx_line_caption` checkbox for LINE and ARROW (`CreationExtrasRow`) → `/Cap` is written in `core/pdf/Annotations.kt:274`. |

## 24c. Stamps

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 24.45 Emergency / Expired / Verified stamps | PARTIAL | DONE | `ui/stamps/StampPickerSheet.kt:58,65,66` (`cx_stamp_expired`, `cx_stamp_emergency`, `cx_stamp_verified`). |
| 24.47 Stamp shape templates | MISSING | DONE | `ui/view5/StampExtras.kt:StampShape` (arrow, circle, diamond, ellipse, octagon, rectangle, square, triangle); picker tab `cx_stamp_tab_shapes`. |
| 24.51 Stamp from a PDF page | MISSING | DONE | `StampSources.fromPdfPage`, through the picker dialog `cx_stamp_from_pdf` (`StampPicker5.kt:337`). |
| 24.52 Custom stamp name / category / opacity / transparent white | MISSING | DONE | Long-press a custom stamp → `cx_stamp_edit` dialog → `StampMeta.saveCustom`. |
| 24.53 Stamp categories | PARTIAL | DONE | Category chips in the Custom tab (`StampPicker5.kt:226-232`). |
| 24.54 Place a stamp on several pages (spot-checked) | MISSING | DONE | `PlacementBar` button `cx_place_pages` → `PagesReq` → `PagesDialog` → `CommentExtras.placeOnPages` → copies of the pending stamp, or `CommentOps7.copyStored` for stored ones. Also in the selection overflow. |
| 24.55 Clipboard image as a stamp | MISSING | DONE | Picker chip → `StampSources.fromClipboard` (`StampPicker5.kt:223`). |
| 24.56 Dynamic stamp font / size / colour | PARTIAL | DONE | `cx_stamp_style` dialog: colour, border and font (`StampMeta.FONTS`). Size is set numerically when placing (`PlacementBar` `cx_size`). |
| 24.57 Prompted custom values | MISSING | DONE | `{ask:…}` token → `askTpl` dialog → `StampMeta.fillAsk`. |
| 24.58 Manage stamps: edit | PARTIAL | STILL MISSING | Rename, category, opacity and delete work. There is no "replace image" and no shared or admin stamps. |
| 24.60 Cut / copy / paste a stamp, paste in place | MISSING | DONE | Same clipboard as 24.71. |
| 24.63 Rotate a placed stamp, 15° steps | PARTIAL | DONE | Selection overflow → rotate left / right / custom (`RotateDialog`, 15° steps) → `CommentOps7.rotate`. |
| 24.65 Favourite stamps | MISSING | DONE | `StampMeta.toggleFavourite`; Favourites tab (`StampPicker5.kt:169,185`). |

## 24d. Pop-ups, properties, management

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 24.66 Pop-ups open / close all, move, reset | PARTIAL | STILL MISSING | Open all, close all and reset exist ("more" menu → `CommentOps7.setPopupsOpen` / `resetPopups`). There is still no floating pop-up to move, and no hover tooltip. |
| 24.67 Locked comments | MISSING | DONE | Overflow `cx_lock` / `cx_unlock` and a Locked switch in `PropsDialog` → `CommentExtras.setLocked`; `refuseLocked` blocks edits. |
| 24.68 Author / subject per comment | PARTIAL | DONE | `PropsDialog` (author, subject, name, contents) → `CommentExtras.applyProps` → `CommentOps7.setProps`. |
| 24.69 Review history list | PARTIAL | DONE | `CommentsPanel.kt:255-260` (`cx_review_history`, shown when the card is expanded). |
| 24.71 Cut / copy / paste comments, paste in place (spot-checked) | MISSING | DONE | `SelectionMoreButton` `cx_copy` / `cx_cut` → `CommentExtras.copy` → `CommentClipboard.set` (with a temp snapshot for stored comments). Then "more" menu `cx_paste` / `cx_paste_in_place` → `CommentExtras.paste` → `CommentOps7.copyStored`. This works across documents. |
| 24.73 Expand / collapse all | PARTIAL | DONE | `CommentsPanel.kt:130-131`. |
| 24.74 Previous / next comment | MISSING | DONE | `CommentsPanel.kt:132-133` and the "more" menu → `CommentExtras.stepComment`. |
| 24.75 Sort by type / status / colour / check | PARTIAL | DONE | `CommentsPanel.kt:301` (`SortBy.TYPE`, `STATUS`, `COLOR`, `CHECKED`). |
| 24.76 Filter by colour | PARTIAL | DONE | `CommentsPanel.kt:152` and `CommentManager.kt:162-165`. |
| 24.77 Show / hide all comments | MISSING | DONE | "more" menu `cx_hide_comments` / `cx_show_comments` → `CommentExtras.toggleHidden`. Limitation: PDFium also hides form-field appearances. |
| 24.79 Rotate comments | MISSING | DONE | Same as 24.63. |
| 24.83 Reply colour / author | MISSING | DONE | Reply edit button `cx_reply_style` (`CommentsPanel.kt:274,304-311`). |
| 24.85 Align / center / distribute | MISSING | DONE | Multi-select overflow `cx_align` → `AlignMode.*` → `CommentExtras.align`. |
| 24.87 Summary options | PARTIAL | STILL MISSING | Sort, text size, paper, thumbnails, empty pages, replies, status and page range are done (`SummaryOptionsFields`). The connector-line and sequence-number layouts are not built, and the connector colour and opacity can't be set. |
| 24.90 FDF opens its PDF | PARTIAL | DONE | `ui/share5/ReturnedComments.kt:82-83` → `CommentOps7.fdfTargetName` / `findPdf`. |
| 24.92 Export selected comments | MISSING | DONE | Overflow `cx_export_sel_xfdf` / `cx_export_sel_fdf` → `CommentExtras.exportSelected` (`CommentOps7.keepOnly`). |
| 24.93 FDF via e-mail | PARTIAL | DONE | Overflow and "more" menu `cx_email_fdf` (share = true). |
| 24.95 Comments into the source Word file | PARTIAL | NOT-FEASIBLE | DONE file: this needs DOCX comment anchoring to the PDF text. Comments can still be exported as a new DOCX table. |

## 24e. Comment Format tab

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 24.99 Font family | MISSING | DONE | `TextFormatDialog` chips `cx_font_sans` / `serif` / `mono`, opened from the FREETEXT creation row ("Text format") and from the edit bar (`AnnotEditUi.kt:54`). There are only 3 generic families, not installed fonts. |
| 24.101 Bold / italic / underline / strike | MISSING | DONE | `CreationExtrasRow` B / I / U / S chips, plus `TextFormatDialog showBiu` for stored comments; written as /RC. |
| 24.102 Superscript / subscript / spacing / scale | MISSING | STILL MISSING | Character spacing, word spacing and character scale are done (`TextFormatDialog` sliders). Superscript and subscript are not built. |
| 24.105 Bullets / numbering / indent | MISSING | STILL MISSING | Bullets are done (`cx_bullets`). Numbering and increase / decrease indent are not built. |
| 24.106 Justify | PARTIAL | DONE | `TextFormatDialog` align chip `FormatAlignJustify`. |
| 24.107 Line / paragraph spacing | MISSING | STILL MISSING | Line spacing is done (slider). Paragraph spacing is not built. |
| 24.108 Fill / dash / ends on the creation bar | PARTIAL | DONE | `CreationExtrasRow`: fill palette for RECT and OVAL, `cx_dash` → `DashPicker`, and line start / end `LineEndPicker`. |

## 25. Measure, calculator, tickmarks

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 25.4 Leader lines | MISSING | DONE | Workbench chip `cx_leader_lines` → `WorkbenchDialogs.kt:LeaderDialog`; written as /LL /LLE /LLO. |
| 25.10 Scale from the document | MISSING | DONE | `core/view5/ScaleStore.read/readAll` reads /VP and /Measure. It is used in `Workbench.kt:90` (`v.docScales`). |
| 25.12 Per-page scale | PARTIAL | DONE | `ScaleStore.write(doc, pages, …)` writes /VP per page. |
| 25.13 Result info ΔX / ΔY | PARTIAL | DONE | `Workbench.kt:168` shows ΔX, ΔY and the angle. |
| 25.14 Custom measurement label | MISSING | DONE | `WorkbenchDialogs.kt:257-289` (`customLabel`) → `MeasureWriter.kt:79`. It edits the last pending measurement only. |
| 25.18 Drag a measurement label separately | PARTIAL | STILL MISSING | Not built (DONE file: NOT DONE, because there is no label-offset model). |
| 25.19 Endpoint drag handles | MISSING | STILL MISSING | End points can only be typed in, and only for the last pending measurement (`WorkbenchDialogs.kt:257`). There are no drag handles on stored lines. |
| 25.20 Flip | MISSING | DONE | `cx_flip` in the same dialog (`WorkbenchDialogs.kt:275-278`). |
| 25.23 Arrange measurements | MISSING | DONE | Measurements are ordinary annotations, so reader multi-select → Align / distribute (24.85) covers them. |
| 25.24 Accounting calculator keypad (spot-checked) | MISSING | DONE | `Annot2Toolbar` more menu → `CommentExtrasMenuItems` `cx_calculator` → `cx.calculator = true` → `CommentExtrasDialogs` → `CalculatorDialog` → `core/annot/CalcTape` (+ − × ÷ % √ 1/x ± CE C M+ M− MR MC, subtotal, total, GT). |
| 25.25 Calculator tape editing | MISSING | DONE | `CalculatorDialog`: tap a line to edit or delete it; undo / redo; decimals 0/2/4; date header. |
| 25.26 Tape on the page | MISSING | DONE | `cx_calc_place` → `state.addAnnot(FREETEXT, subject "Calculator Tape")`. |
| 25.27 TickMark symbols palette | PARTIAL | STILL MISSING | `TickSymbols.all` has 10 symbols, not Foxit's 31. |
| 25.28 Number / letter tickmarks | MISSING | DONE | `ReaderTool.TICKMARK` (`Toolset.kt:56`) → `TickRow` numbers / letters → `CommentExtras.placeTick` / `nextLabel`. |
| 25.30 Recolour all / navigate tickmarks | MISSING | DONE | `TickRow` `cx_tick_select` (`selectTickmarks`, then recolour the selection) and prev / next (`stepTickmark`). |

## 26a. Filling

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 26.3 AcroForm "fillable" notification | PARTIAL | DONE | `ui/forms5/FormRuntimeUi.kt:168-186` (`f8_fillable_banner`, close); Settings switch `f8_set_banner` (`ui/settings/FormRunSettings.kt:65`). |
| 26.11 Auto-shrink text | PARTIAL | DONE | Shrinks on screen. The saved appearance relies on PdfBox auto size, which was not verified (DONE file note). |
| 26.14 Fill & Sign rectangle | PARTIAL | DONE | `ReaderTool.FS_RRECT` in the Fill group (`Toolset.kt:74`, `nf2_rounded_rect`); pdf-3c work. |
| 26.15 Fill & Sign comb field | MISSING | DONE | `ReaderTool.FS_COMB` (`Toolset.kt:75`, `nf2_comb`, with cells and cell width); pdf-3c work. "Convert Comb ↔ Text" is not built (the comb-convert item was removed in the merge). |

## 26b. Form recognition and field creation

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 26.18 Run Form Field Recognition (spot-checked) | MISSING | DONE | `FormDesignerScreen` more menu `fx_detect` → `dialog = "detect"` (this page / all pages, review list) → `DesignerState` scan → `core/formsx/FieldRecognition.scanPage`; then `fx_create_selected` → `FieldRecognition.apply`. |
| 26.19 Designer Assistant | MISSING | DONE | In Create mode the suggestions are drawn and a tap creates the field (`FormDesignerScreen.kt:216`, `onSuggestion`). |
| 26.22 Barcode field | MISSING | DONE | `fx_type_barcode`; Options in `FieldPropsDialog` (`fx_symbology`, `fx_xdim`, `fx_yx`, `fx_ecc`, `fx_bc_compress`, `fx_bc_xml`) → `core/formsx/FxBarcodes`. |
| 26.24 All Properties for existing fields | PARTIAL | DONE | `FieldPropsDialog` with every tab, opened by double-tap or `fx_props`, also for several fields at once (`fx_props_multi`). |
| 26.25 Move / resize / delete existing fields (spot-checked) | CODE-ONLY | DONE | Select-mode drag → `FxEditor.setRect` (`FormDesignerScreen.kt:416`); position fields (`:425`); delete on the selection bar → `FxEditor.delete` / `deleteWidget` (`:502`). |
| 26.26 Multi-select fields | MISSING | DONE | Marquee or long-press; `fx_n_selected` bar (`FormDesignerScreen.kt:496`). |
| 26.27 Multiple copies | MISSING | DONE | `fx_copies` dialog (rows, cols, dx, dy, same name) → `FxEditor.makeCopies`. There is no live preview. |
| 26.28 Duplicate across pages | MISSING | DONE | `fx_dup_pages` (all / odd / even / range) → `FxEditor.duplicateToPages`. |
| 26.29 Properties of several fields | MISSING | DONE | `fx_props_multi` → `FxMerge.apply`. |
| 26.30 Current properties as new defaults | MISSING | DONE | `fx_set_default` (`FxPrefs`) and `fx_inherit`. |

## 26c. Field properties

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 26.32 Visibility (authoring) | PARTIAL | DONE | General tab: `fx_visibility` (visible / hidden / no print / hidden but printable). |
| 26.33 Orientation | PARTIAL | DONE | `fx_rotation`. |
| 26.34 Read only | PARTIAL | DONE | `fx_readonly`. |
| 26.36 Locked | MISSING | DONE | `fx_locked`, and the lock / unlock bar `fx_lock` (`FormDesignerScreen.kt:545`). |
| 26.37 Border style / thickness / colours | MISSING | DONE | Appearance tab: `fx_style_solid` / `dashed` / `beveled` / `inset` / `underline`, `fx_thickness`, `fx_border_color`, `fx_fill_color`. |
| 26.38 Font / size / colour / direction | MISSING | DONE | `fx_font`, `fx_font_size`, `fx_text_color`. LTR / RTL is not possible: AcroForm has no direction key, so right alignment is the substitute (DONE file). |
| 26.39 Position tab | MISSING | DONE | `fx_tab_position` (`fx_pos_left`, `bottom`, `width`, `height`). |
| 26.40 Button layouts | MISSING | DONE | `fx_layout`. |
| 26.41 Icon placement | PARTIAL | DONE | `fx_icon_scale`, `fx_scale_always` / `never` / `bigger` / `smaller`, `fx_proportional`. |
| 26.42 Button behaviour | MISSING | DONE | `fx_behavior` (`fx_hl_none` / `push` / `outline` / `invert`), `fx_rollover`, `fx_down_caption`. |
| 26.43 Button label / icon | PARTIAL | DONE | `fx_caption`, `fx_icon_choose`, `fx_icon_clear`. |
| 26.44 Triggers | PARTIAL | DONE | `ui/formsx/ActionsEditor.kt` (`fx_tr_up`, `down`, `enter`, `exit`, `focus`, `blur`) → `FxActions.writeTriggers`; run at fill time by `PdfActions.triggers`. |
| 26.45 GoTo action | MISSING | DONE | `fx_act_goto` / `fx_act_remote` / `fx_dest_name`. |
| 26.47 Web link action | PARTIAL | DONE | `fx_act_url`. |
| 26.48 Show / hide action | PARTIAL | DONE | `fx_act_showhide`. |
| 26.49 Menu item action | PARTIAL | DONE | `fx_act_menu` / `fx_act_named` are authored. At fill time, named actions other than page, Print and Save show "not supported". |
| 26.50 Submit / reset authoring | PARTIAL | DONE | `fx_act_submit` (format, fields, include empty, canonical dates, GET), `fx_act_reset`. |
| 26.51 Import-data action | MISSING | DONE | `fx_act_import`. |
| 26.52 Run JavaScript action | PARTIAL | DONE | `fx_act_js`; any script runs in the sandbox (`FormScripts.action`). |
| 26.53 Read-article action | MISSING | DONE | `fx_act_article` / `fx_article_n`. |
| 26.54 Set layer visibility | PARTIAL | DONE | `fx_act_layers` is authored. At runtime `core/forms5/OcgState.kt` and `PdfActions` handle SetOCGState. The layers panel that was missing now exists (`ui/reader/NpfPanels.kt` LAYERS, from nav-prefs). |
| 26.55 Actions list up / down / edit / delete | MISSING | DONE | ActionsEditor `fx_up` / `fx_down` / `fx_edit`, written as a /Next chain. |
| 26.56 Check box style | MISSING | DONE | `fx_check_style` (`fx_cs_check` / `circle` / `cross` / `diamond` / `square` / `star`). |
| 26.57 Check box export value | MISSING | DONE | `fx_export_value`. |
| 26.58 Checked by default | CODE-ONLY | DONE | `fx_checked_default`. |
| 26.59 Radio style / unison | PARTIAL | DONE | `fx_check_style`, `fx_unison`, `fx_radio_values`. |
| 26.60 Radio buttons placed one by one | PARTIAL | DONE | `FxEditor.addRadioWidget` (`FormDesignerScreen.kt:245`). |
| 26.61 Choice items / export / sort / flags | PARTIAL | DONE | `fx_item_display`, `fx_item_export`, `fx_up` / `fx_down`, `fx_sort`, `fx_editable`, `fx_commit`, `fx_multiselect`. |
| 26.62 Format authoring | PARTIAL | DONE | `fx_tab_format`: number, percent, date, time, special, mask, custom (`FxScripts`). |
| 26.63 Validate authoring | PARTIAL | DONE | `fx_tab_validate`: range or custom script. |
| 26.64 Calculate authoring | PARTIAL | DONE | `fx_tab_calculate`: simple operations with a field picker, SFN, custom script. |
| 26.65 Text options | PARTIAL | DONE | `fx_text_align`, `fx_default_value`, `fx_scroll`, `fx_richtext` (flag only), `fx_maxlen`, `fx_password`, `fx_fileselect`, `fx_spell`, `fx_comb`, `fx_multiline`. |
| 26.67 Signature-field Signed tab | MISSING | DONE | `fx_tab_signed` (`fx_sig_all` / `exclude` / `include`, `fx_sig_script`) → `FxSigLocks`; the FieldMDP is applied when signing (`SigFields.applyLock`, pdf-3c). |
| 26.68 Sign and verify XFA signatures | MISSING | STILL MISSING | AcroForm /Sig widgets, including those of static XFA, can now be signed and verified in place (`ui/signature/SigFieldActions.kt`, through `FormFillState.sigTapped`). Dynamic-XFA signature widgets and XFA (XML) signatures can't be signed or checked. |

## 26d. Arrange, tab order, templates, XFA, data

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 26.71 Align / distribute / size fields | MISSING | DONE | Selection bar `fx_align*`, `fx_distribute_h` / `v`, `fx_size_*`, `fx_center_page_*` → `core/formsx/FxLayout`. |
| 26.72 Tab order modes, numbers, click-to-set | PARTIAL | DONE | `fx_tabs_menu` (row / col / struct / unspec / manual), `fx_tabs_numbers`, Tabs mode → `FxEditor.setManualTabOrder` (`FormDesignerScreen.kt:232`). |
| 26.73 Alphabetic field list | PARTIAL | DONE | Fields dialog `fx_sort_alpha` / `fx_sort_tab` (`FormDesignerScreen.kt:685-686`). |
| 26.75 Template Goto / Rename / Replace | MISSING | DONE | `ui/edit5/TemplatesBlankScreens.kt:78-80` (`f8_tpl_goto`, `f8_tpl_rename`, `f8_tpl_replace` → `PageTemplates.replaceWithPage`). |
| 26.76 Calculation-order dialog | PARTIAL | DONE | `fx_calc_order` dialog with up / down (`FormDesignerScreen.kt:601-609`). |
| 26.77 Edit static XFA | PARTIAL | DONE | Static XFA becomes an AcroForm that stays fillable and editable in the designer. XFA-only scripts and layout are lost. |
| 26.79 Form data FDF / XFDF / TXT / XML / CSV | PARTIAL | DONE | `core/formsx/FormData` (export / importFile); designer `fx_data_import` / `fx_data_export`, Forms tool (`FormsScreen.kt:210`), reader form chips (`FormScriptTools.kt:434`). |
| 26.80 Export form data via e-mail | PARTIAL | DONE | Same formats, shared from the export flow. |
| 26.81 Combine forms to a sheet / append CSV | MISSING | DONE | `fx_combine` / `fx_combine_append` → `FormData.collect` (`FormDesignerScreen.kt:144`). |
| 26.82 Import one CSV row into the open form | PARTIAL | DONE | `FormData` CSV import → `parseTable(...).firstRow()` (`FormData.kt:173`). |
| 26.83 2D barcode of form data | MISSING | DONE | Barcode field with field list and Tab / XML payload (`FxBarcodes`, `refreshAll` after filling). |
| 26.84 Auto-complete list management | MISSING | DONE | Settings → Forms: `f8_set_autocomplete` switch and `f8_set_ac_manage` (`AutocompleteManager`: list, edit, delete, clear) in `ui/settings/FormRunSettings.kt:63-121`, included in `SettingsScreen.kt:108`. |

## 27. JavaScript

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 27.3 Attach JS to the document, bookmarks, links and pages | MISSING | DONE | `core/forms5/DocScripts` locators (`page:i:O/C`, `outline:i`, `link:…`, `open`) with `setCode`, edited in the Document JavaScript screen / "Edit all". |
| 27.4 Document JavaScript dialog | MISSING | DONE | `ui/edit5/DocScriptsScreen.kt` (tool `e5_doc_js`, `Edit5Hub.kt:32,68`), opened from the reader form chips (`ReaderToolbars.kt:231` `openDocJs`). |
| 27.5 Document actions WC / WS / DS / WP / DP | MISSING | DONE | `DocScripts.DOC_ACTIONS` with the `nbm_js_tab_actions` tab; run by the form runtime when JsPolicy allows. |
| 27.6 JavaScript console | MISSING | DONE | `DocScriptsScreen` console tab → `core/edit5/DocJsConsole.run`. It refuses to run when JavaScript is off. |
| 27.7 Edit all JavaScripts | MISSING | DONE | `DocScripts.listAll`; `nbm_js_goto_line`. |
| 27.10 Doc API | PARTIAL | STILL MISSING | Many calls are real now (submitForm, mailDoc, exportAsFDF, gotoNamedDest and others). saveAs, closeDoc, addScript, scroll, syncAnnotScan, exportDataObject, addField and setLock still show "not supported". |
| 27.12 Enable / disable JavaScript preference (spot-checked) | MISSING | DONE | Settings → `ui/settings/NpSecuritySettings.kt:36-42` JS level → `settings.runJavaScript` → `FormFillState.kt:316` sets `core/security/DocSecurity.kt:JsPolicy.enabled` → checked by FormScripts, FormCalc and XfaJs. |

## Spot-checks (call chain followed in the code)

1. 24.71 copy / paste: `SelectionMoreButton` → `cx.copy()` → `CommentClipboard.set`, then `CommentExtrasMenuItems` → `cx.paste()` → `CommentOps7.copyStored`.
2. 24.54 place on pages: `PlacementBar` → `PagesReq` → `PagesDialog` → `cx.placeOnPages` → `CommentOps7.copyStored`.
3. 25.24 calculator: `Annot2Toolbar` more menu → `cx.calculator` → `CalculatorDialog` → `CalcTape` → `state.addAnnot(FREETEXT)`.
4. 26.18 field recognition: designer menu `fx_detect` → detect dialog → `DesignerState` → `FieldRecognition.scanPage` / `apply`.
5. 26.25 existing fields: designer select mode → `FxEditor.setRect` / `delete` / `deleteWidget`.
6. 27.12 JavaScript switch: `NpSecuritySettings` → `runJavaScript` → `JsPolicy.enabled` in `FormFillState`.

## STILL MISSING (by importance)

**Med**
- 24.87: summary connector-line and sequence-number layouts, connector colour and opacity (comments/forms).

**Low**
- 24.18 / 24.66: a floating, movable and resizable pop-up note window, and a hover tooltip (comments/forms, reader/view).
- 24.27: dragging callout parts separately after placement (/CL handles) (comments/forms).
- 24.33: inward / outward cloud scallops. Feasible, because the app writes its own appearance in CloudPath (comments/forms).
- 24.58: replacing a custom stamp's image, and shared or admin stamps (comments/forms).
- 24.102: superscript and subscript in text comments (comments/forms).
- 24.105: numbering and indent in text comments (comments/forms).
- 24.107: paragraph spacing in text comments (comments/forms).
- 25.18: dragging a measurement label on its own (comments/forms).
- 25.19: drag handles on measurement end points. Today the points can only be typed in, for the last pending measurement (comments/forms).
- 25.27: tickmark palette has 10 symbols, not 31 (comments/forms).
- 26.68: signing and verifying dynamic-XFA / XML signatures (sign/protect).
- 27.10: remaining doc-API stubs: saveAs, closeDoc, addScript, addField, setLock, exportDataObject, scroll, syncAnnotScan (comments/forms).

These parts are also missing but don't change their row's status: the 26.15 Comb ↔ Text conversion, and only 3 generic font families in 24.99.

## Counts (134 non-HAVE rows: §24 = 52, §25 = 15, §26 = 60, §27 = 7)

| Status | 24 | 25 | 26 | 27 | Total |
|---|---|---|---|---|---|
| DONE | 42 | 12 | 59 | 6 | **119** |
| STILL MISSING | 9 | 3 | 1 | 1 | **14** |
| NOT-FEASIBLE | 1 (24.95) | 0 | 0 | 0 | **1** |
| OWNER-ACTION | 0 | 0 | 0 | 0 | 0 |
| N/A-ANDROID | 0 | 0 | 0 | 0 | 0 |

---

# Final check: Foxit §34 (fcmp_24-27_34) and §28–33 (fcmp_28-42), main fa724fd/8555d74

Paths are under `app/src/main/java/com/smartdocs/editor/`. Only rows that were PARTIAL / MISSING / CODE-ONLY are listed. Rows marked "HAVE (needs server)" are out of scope. Nothing was tested on a device.

## §28 Protect

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Security Properties / reader honours /P | PARTIAL | DONE (spot-checked) | Reader copy chip → `ReaderScreen.kt:298` `state.guard.check(DocAction.COPY)` → `ui/reader/ReaderGuard.kt:138` → `core/security/ReaderRestrictions.check`; tools gated via `ReaderRestrictions.actionForTool` (`ReaderScreen.kt:204`); permissions list in the INFO → Security panel (`ui/reader/NpfPanels.kt` SECURITY → `NpfDocPanels.security`) |
| AES-GCM (ISO/TS 32003) | MISSING | NOT-FEASIBLE | DONE_pdf3c_protect_sign: PdfBox 2.0 cannot write AESV4 |
| Security policies (New/Copy/Edit/Delete/Apply) | MISSING | DONE | `core/redact/SecurityProfiles`, `ui/security/SecurityProfilesUi.kt` `SecurityProfilesDialog`, opened from `ProtectAdvancedScreen.kt:185` (password mode) |
| Change Settings pre-filled | PARTIAL | DONE | `core/redact/ProtectPrefill.kt` `ProtectPrefills.from`, used `ProtectAdvancedScreen.kt:80` |
| Certificate Protect: new self-signed ID | PARTIAL | DONE | `core/sign/DigitalIds.create` from `ui/signature/CertificateTab.kt:131` (RSA/EC/Ed25519, validity, usage, several IDs) |
| Certificate Protect: algorithm / don't encrypt metadata / save as policy | PARTIAL | STILL MISSING | Metadata switch done (`ui/tools/SecurityTools.kt:263` np2_cert_no_meta). Policies exist only for password protection; `SecurityProfile` has no certificate fields |
| Change / remove certificate protection in place | PARTIAL | DONE | `ui/security/CertChangeUi.kt` `CertChangeSection`, `SecurityTools.kt:234` |
| Security watermark profiles (several watermarks, import/export) | PARTIAL | DONE | `core/edit5/SecurityWatermarkSet` in `ui/edit5/DecorScreen.kt:78`; JSON import/export in `ui/edit5/E5ProfilesUi.kt:37` |
| Trust Manager / Safe Reading Mode | PARTIAL | DONE | `core/security/TrustManager.kt` `SafeReading`; Settings → `Np2TrustCard()` (`ui/settings/SettingsScreen.kt:91`); enforced in `ui/security/DocSecurityGate.kt` |
| Privileged locations | MISSING | DONE | `TrustPrefs.privileged`, `Np2TrustSettings.kt` |
| Protected View | MISSING | DONE | `core/security/TrustManager.kt` `ProtectedView`, `ReaderRestrictions.check(..., protectedView, ...)` |
| Non-PDF attachment security (per-extension, restore defaults) | PARTIAL | DONE | `DocSecurityGate.confirmAttachment` (`ui/attachments/AttachmentsBrowser.kt:122`); rules + "restore" in `Np2TrustSettings.kt:180,201` |
| Manage Sites (internet access from PDFs) | MISSING | DONE | `ui/settings/NpSecuritySettings.kt:30-55` (netMode, trusted sites dialog); `DocSecurityGate.web` |
| Run Script levels | MISSING | DONE | `NpSecuritySettings.kt:35-42` four `JsLevel`s (`TrustPrefs.setJsLevel`), `JsPolicy` |
| FATL / EUTL trust list updates | MISSING | DONE | `core/sign/TrustLists` + `TrustListWorker`; Settings `Np2TrustSettings.kt:42-69`. Note: list XAdES signature not verified; AATL/FATL roots NF (licensing) |
| Launch File action warning | PARTIAL | DONE | `ui/edit5/ReaderLinks.kt:128` → `DocSecurityGate.confirmLaunch` (blocks executables, asks) |

## §29 Redaction

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| RHI: overlapping objects → image | PARTIAL | DONE | `core/redact/Sanitizer.kt:23` OVERLAPPING (opt-in), `ui/security/SanitizeScreen.kt:113,151` |
| RHI: multimedia | PARTIAL | DONE | `Sanitizer.kt:144,318` MULTIMEDIA, `SanitizeScreen.kt:150` |
| Mark a whole image by tap | MISSING | DONE | `ui/security/RedactScreen.kt:155` onImageTap → `RedactState.markImageAt` |
| Move / resize marks | PARTIAL | DONE | `ui/security/RedactManualView.kt:81-108` (drag inside = move, corner = resize) |
| Overlay text font | PARTIAL | DONE | `ui/security/RedactStyleSheet.kt:82-85` nt_redact_font |
| Redaction code sets + import/export | PARTIAL | DONE | `core/redact/RedactPrefs.kt:54-98` CodeSets; `RedactStyleSheet.kt:133-163` import/export buttons |
| Properties: author, subject | MISSING | DONE | `ui/security/RedactProps.kt` `MarkPropertiesDialog`, opened `RedactScreen.kt:173` |
| Properties: Review History | MISSING | DONE | `RedactProps.kt:54-57` (m.reviews, set status) |
| Properties: Locked; current as default | PARTIAL | DONE | `RedactProps.kt:42`; locked marks skipped in `RedactManualView.kt:89` |
| Apply a single mark | MISSING | DONE | `RedactScreen.kt:175` onApplyOne → `applyAll(m)` |
| Output filename prefix / suffix | PARTIAL | DONE | `RedactScreen.kt:51-52,89-90,198-199`, `RedactPrefs.saveOutName` |
| Search & Redact: import / export word list | MISSING | DONE (spot-checked) | Redact tool → `RedactSearchPane` buttons nt_rd_import_words / nt_rd_export_words (`ui/security/RedactPanes.kt:90-91`) → `rememberTextImport` / `rememberTextExport("text/plain")` (`RedactPanes.kt:49-50`) → `st.wordsText` used by the search |
| Search & Redact: country / region patterns | PARTIAL | DONE | `core/search/SearchPatterns.kt:9` (US SSN, UK NINO/driving, CA SIN, IN, ES, BR, CN …); chips `ui/search/SearchUiCommon.kt:136-161` |
| Profiles: overlay text per phrase | PARTIAL | DONE | `RedactPrefs.kt:17-33` ("phrase \| overlay" lines) |
| Profiles: import / export | PARTIAL | DONE | `RedactPrefs.exportProfiles/importProfiles`, menu `RedactPanes.kt:80-82` |
| Smart Redact (names, organisations, roles) | PARTIAL | DONE | Offline `core/ai5/PiiEntities` + AI pass `core/ai6/AiPii.scan` from `ui/ai5/PiiScanSheet.kt:60`; reached from Redact (`RedactScreen.kt:183`) and AI tools. AI pass needs the user's AI key |

## §30 Fill & Sign, digital signatures

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Fill: rounded rectangle | MISSING | DONE | FS_RRECT in `ui/reader/annot/Toolset.kt`, `core/fillsign2/RoundRect.kt` |
| Fill: comb field | MISSING | DONE | FS_COMB in `Toolset.kt` → `core/fillsign2/CombLayout.kt` (used in `ui/reader/AnnotationOverlay.kt`) |
| Create signature: paste from clipboard | MISSING | DONE | nf2_paste_clip in `ui/signature/` |
| Convert signature to black & white | MISSING | DONE | `ui/signature/SignatureMono.kt` (nq_bw_*) |
| Keep original size | MISSING | DONE | nf2_keep_size |
| Temporary (session-only) signature | MISSING | DONE | nf2_temporary, nf2_temporary_badge |
| Password to use a signature | MISSING | DONE | `SignatureLock` / `SignatureLockUi.kt` (nq_lock_*) |
| Set current size as default | MISSING | DONE | nf2_set_default_size in `StampActionButtons` (`ui/reader/AnnotEditUi.kt:75`) |
| Copy / cut / paste placed signature | MISSING | DONE | `StampActionButtons`, `PasteHereBar` (`ui/reader/PageCanvas.kt:316`) |
| Place on multiple pages | MISSING | DONE | `ui/reader/FillSign2Reader.kt:191` MultiPageDialog → `core/fillsign2/MultiPage` |
| Apply all signatures + flatten | PARTIAL | DONE | `FillSign2Reader.kt:257` ApplyAllDialog, nf2_flatten_on_save, `core/fillsign2/SignatureFlatten` |
| Manage signatures: edit / move up / down | MISSING | DONE | nf2_edit_signature, nf2_move_up/down (`ui/signature/SignaturesScreen.kt`, `SignatureEditDialog.kt`) |
| Paging seal standalone add/remove | PARTIAL | DONE | `FillSign2Reader.kt:263` PagingSealDialog (nf2_seal_add / nf2_seal_remove) |
| eSign: Send in Bulk | MISSING | OWNER-ACTION | `ui/esign/EsignBulkUi.kt` built; needs the e-sign server |
| eSign: online form | MISSING | OWNER-ACTION | `core/esign/EsignForms.kt`; needs `ai-proxy/esign/worker.js` redeployed |
| eSign: branding | MISSING | OWNER-ACTION | `core/esign/EsignBrand.kt`, `ui/esign/EsignBrandUi.kt`; needs worker redeploy |
| Digital IDs: details / remove | PARTIAL | DONE | `CertDetailsBlock`, ns_id_delete_confirm (`ui/signature/CertificateTab.kt`) |
| Digital IDs: export | MISSING | DONE | ns_id_export_p12 / ns_id_export_cer |
| Digital IDs: tokens / smart cards (KeyChain) | MISSING | DONE | `DigitalIds.chooseKeyChain/loadKeyChain` (ns_id_keychain_*). Direct USB PKCS#11 is not built; KeyChain providers only |
| Place signature anywhere | PARTIAL | DONE | `SigPlacementDialog` (ns_place_*) |
| Reason presets | PARTIAL | DONE | ns_reason_presets in `DigitalSignScreen` |
| Sign page range | MISSING | DONE | ns2_also_pages (`SigFields.splitWidgets`) |
| Lock document after signing (FieldMDP) | PARTIAL | DONE | ns_lock_* (`SigFields.applyLock`) |
| Sign multiple documents | MISSING | DONE (spot-checked) | Tools `digital_sign` (`ui/tools/ToolsScreen.kt:61`, `ToolScreens.kt:100`) → button ns2_batch_sign (`ui/signature/DigitalSignScreen.kt:378`) → `signBatch()` (`:233`) → `DigitalSignature.sign` per file |
| Move / resize / delete unsigned field | MISSING | DONE | ns2_add_empty_field, ns2_field_move, ns2_field_delete |
| Appearance: font, size, direction, named styles | PARTIAL | DONE | ns2_ap_font*, ns2_ap_dir*, nq_ap_profiles (`SigAppearanceDesigner.kt`) |
| Clear signature | MISSING | DONE | ns_field_clear (`ui/signature/SigFieldActions.kt`) |
| Verify signatures on open | MISSING | DONE (spot-checked) | `ReaderScreen.kt:370` `SigFieldSheet` → `LaunchedEffect(file)` → `SigFields.list` + `DigitalSignature.verify(f)` (`SigFieldActions.kt:53-58`) → badges + `SignedBar` |
| Signature status icons on page + panel | PARTIAL | DONE | badges via `FormFillState.sigStatus`; ns_show_sig_icons; reader pane `ui/nve/panes/NvePanes.kt` SIGNATURES |
| Certified-document bar on open | MISSING | DONE | `SignedBar` (ns2_bar_certified) |
| Revocation checking during validation | MISSING | DONE | `core/sign/Revocation`, ns_check_revocation |
| Add LTV to an existing signed file | PARTIAL | DONE | `DigitalSignScreen.kt:520` → `LtvSupport.addDss` |
| Verification time choice | PARTIAL | DONE | ns2_time_now/secure/signing, `DigitalSignature.verificationTime` |
| View Signed Version | MISSING | DONE | ns_view_signed_version, `SigRevisions` |
| Compare signed vs current | PARTIAL | DONE | ns2_compare_signed, `SignedCompare` |
| Time Stamp Document standalone | CODE-ONLY | DONE | `DigitalSignScreen.kt:521` → `LtvSupport.addDocTimestamp` |
| TSA servers manage / login | PARTIAL | DONE | `ui/signature/TsaServersUi.kt` (nq_tsa_*) |
| Trusted certificates: export | MISSING | DONE | `ui/signature/CertExportUi.kt` (np_export_certs) |
| Trust levels | MISSING | DONE | `TrustedCerts.Level` in `TrustedCertsScreen.kt` (ns2_trust_signatures / ns2_trust_certified) |
| Trust TSA certificate | PARTIAL | DONE | ns2_trust_tsa |
| Signature panel in reader | MISSING | DONE | `ReaderScreen.kt:359` `NveSidePane` → `NvePanes.kt:119` `SignaturesPane` (verify, jump, signed version) |
| Signature properties (location, contact) | PARTIAL | DONE | ns_field_properties, ns_contact, `VerifyCard` |
| Hash algorithms SHA-3 / EdDSA | PARTIAL | DONE | ns2_digest, ns2_digest_sha3_note, ns2_id_ed_note (`DigitalSignature.Digest`) |

## §31 Share / review

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Email all open tabs | MISSING | DONE | nat_email_tabs in share sheet |
| Auto-save before e-mail | PARTIAL | DONE | `ReaderScreen.kt:431` `outputFile()` writes current edits before `Share5Sheet` |
| Shared Review: send the PDF as attachment | PARTIAL | DONE | nat_rv_send_pdf |
| Pre-review security | MISSING | NOT-FEASIBLE | DONE_pdf3c_nav: reviewers and the server must read the copy |
| Auto-check interval | PARTIAL | DONE | `core/share5/ReviewLocal.interval`, nat_rv_interval |
| Work offline / online | PARTIAL | DONE | `ReviewLocal.offline`, nat_rv_go_offline / go_online |
| Save archive copy | PARTIAL | DONE | `ReviewManageScreen.kt:118` → `saveArchiveCopy` (`TrackerExtras.kt:201`) |
| Take ownership of others' unpublished comments | MISSING | STILL MISSING | no code (`takeOver`/`ownership` 0 hits in share/collab) |
| Tracker: create PDF from details | MISSING | DONE | nat_rv_report, `ReviewLocal.reportText` |
| Tracker: folders | MISSING | DONE | `TrackerFolderChips` (`ReviewTrackerScreen.kt:65`), `ReviewLocal.folders` |
| Reviewer: Remove Me | MISSING | STILL MISSING | `leaveReview` (`TrackerExtras.kt:216`) only hides it on this device; server has no leave endpoint |
| Shared files: search / leave | PARTIAL | DONE | nat_rv_search; leave as above (local) |
| Notifications inbox | PARTIAL | DONE | `ReviewLocal.inbox`, nat_rv_inbox / mark_read |

## §32 ECM / cloud

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Preview non-PDF files from DMS | PARTIAL | STILL MISSING | no DMS context (system picker only); effectively N/A on Android |
| Dropbox Team Folders | PARTIAL | STILL MISSING | no `team` / `root_namespace` in `core/cloud/Dropbox.kt` |
| OneDrive Business / SharePoint library | PARTIAL | STILL MISSING | no drives/sites code in `core/cloud/OneDrive.kt`; also needs OAuth keys (owner) |
| Egnyte | MISSING | STILL MISSING | 0 hits |

## §33 ConnectedPDF

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Configure Open Message | MISSING | STILL MISSING | 0 hits in core/ui connected |
| Clone Document | MISSING | STILL MISSING | 0 hits |
| Disable ConnectedPDF (pref) | PARTIAL | STILL MISSING | no user toggle (only "not configured" state; global offline mode `NpfNet` blocks network but is not a Connected switch) |

## §34 Accessibility

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 34.2 Checker options: categories, page range | MISSING | STILL MISSING | categories DONE (group chips → `AccessRules.apply`); page range OPEN in PAGES_CONVERT_LOG |
| 34.4 Attach report | MISSING | DONE | `ui/tools5/AccessibilityScreen.kt:158-167` pc_acc_attach_report |
| 34.6 Manual / skipped statuses | PARTIAL | DONE | `AccStatus.SKIPPED`, verdicts (`AccessibilityScreen.kt:78,185`) |
| 34.7 Fix per rule | PARTIAL | STILL MISSING | rule menu has Fix, but only for 8 rules (`AccessRules.fixable`, `AccessRules.kt:86`); other failed rules have no fix |
| 34.8 Skip / Explain / Pass / Fail | MISSING | DONE | rule menu `AccessibilityScreen.kt:184-189` (pc_acc_explain/pass/fail/skip) |
| 34.9 Check Again | PARTIAL | DONE | pc_acc_check_again |
| 34.12 Flicker / timed rules | MISSING | DONE | `AccessRules.kt:70` flicker / timed |
| 34.18 Bookmarks from chosen elements | PARTIAL | STILL MISSING | `AccessFix.bookmarksFromHeadings` only; elements cannot be chosen |
| 34.21 Character encoding (re-embed fonts) | MISSING | NOT-FEASIBLE | PAGES_CONVERT_LOG: original font programs not available |
| 34.22 Tagged content / annots / multimedia, artifacts | PARTIAL | DONE | `AccessAutoTag` artifacts; tags editor artifact + `AccessTags.tagAnnotations` (`AccessTagsUi.kt:120`) |
| 34.24 Nested alt / alt hiding annotations fix | MISSING | STILL MISSING | only explanations (pc_accx_nested_alt, pc_accx_alt_hides); no fix |
| 34.25 Tables / lists / headings fixes | MISSING | DONE | auto-tag headings/lists (`AccessAutoTag.classify`); manual fix in tags editor (type, Scope/Headers/RowSpan/ColSpan/Summary). No automatic table recognition |
| 34.26 Setup Assistant | MISSING | STILL MISSING | OPEN in PAGES_CONVERT_LOG |
| 34.27 Reading Option (per document) | MISSING | STILL MISSING | the preference is DONE (`ReaderAi6.orderedPage` reads `NpfPrefs.readingOrder`); only the per-document option is OPEN |
| 34.29 TalkBack labels | PARTIAL | DONE | 41 `pc_cd_*` descriptions (PAGES_CONVERT_LOG stage F); not device-tested |
| 34.34 Autotag headings/lists, re-tag | PARTIAL | DONE | `AccessibilityScreen.kt:226` → `AccessAutoTag.tag(d, lang, retag)` |
| 34.35 Autotag form fields separately | PARTIAL | DONE | `AccessTags.tagAnnotations` (Widget → Form OBJR, `AccessTags.kt:285-305`) |
| 34.36 Recognition report | MISSING | DONE | `AccessAutoTag.Report` dialog (pc_acc_report_*) |
| 34.38 Edit alt text on the page | PARTIAL | STILL MISSING | still list-only (`FiguresPanel`) |
| 34.39 Reading Order / Touch-up tool | MISSING | STILL MISSING | OPEN |
| 34.40 Show content groups / tables / figures on page | MISSING | STILL MISSING | no on-page overlay |
| 34.41 Table Editor | MISSING | STILL MISSING | no on-page table editor (cell attrs only through tag properties) |
| 34.42 Edit form field text / table summary | MISSING | DONE | Summary and Alt/ActualText in tag properties (pc_tags_summary, `AccessTags.setAttrs`) |
| 34.43 Clear page structure | MISSING | DONE | `AccessTagsUi.kt:123` → `AccessTags.clearPage` |
| 34.44 Order panel (drag reorder) | MISSING | STILL MISSING | only move/indent in the tags tree |
| 34.45 Area reading order | MISSING | STILL MISSING | OPEN |
| 34.46 Content panel | MISSING | STILL MISSING | OPEN |
| 34.47 Tags panel tree | MISSING | DONE (spot-checked) | Tools `accessibility` (`ToolsScreen.kt:88`) → button pc_tags_title (`AccessibilityScreen.kt:157`, tags = true) → `TagsEditor` (`:123`, `AccessTagsUi.kt:37`) → `AccessTags.tree(d)` (`AccessTagsUi.kt:59`) |
| 34.48 Tags panel operations | MISSING | DONE | new root/child/sibling, delete, delete empty, find, role map, tagged flag, tag annotations (pc_tags_*). No Class Map / cut-paste |
| 34.49 Find Element | MISSING | STILL MISSING | only unmarked annotations/links (`AccessTags.unmarked`); no artifacts / OCR suspects / unmarked content |
| 34.50 Tag properties | PARTIAL | DONE | `AccessTags.setProps` (Title, Alt, ActualText, Lang, Expansion, ID) |
| 34.51 ColSpan / RowSpan | MISSING | DONE | `AccessTags.setAttrs` |

## STILL MISSING (by importance)

Med
- §28 Certificate protection saved as a policy: SecurityProfiles cover password protection only — sign/protect.
- §34.7 Per-rule Fix for the non-fixable rules (only 8 rules have a fix) — tools/OCR (accessibility).

Low
- §31 Take ownership of other reviewers' unpublished comments — AI/office (share/review).
- §31 Remove Me from a review on the server (only local hide) — AI/office (share/review); server endpoint needed.
- §32 Dropbox Team Folders — files (cloud).
- §32 OneDrive Business / SharePoint document library — files (cloud).
- §32 Egnyte — files (cloud).
- §32 DMS non-PDF preview with DMS context — files (effectively N/A).
- §33 Connected open message — files (connected).
- §33 Clone connected document — files (connected).
- §33 Disable-ConnectedPDF preference — nav/settings.
- §34.2 Checker page range — tools/OCR (accessibility).
- §34.18 Bookmarks from chosen structure elements — tools/OCR (accessibility).
- §34.24 Nested-alt / alt-hides-annotation fix — tools/OCR (accessibility).
- §34.26 Accessibility Setup Assistant — nav/settings.
- §34.27 Per-document reading option; `NpfPrefs.readingOrder` is code-only — reader/view.
- §34.38 On-page alt text editing — tools/OCR (accessibility).
- §34.39 / 34.45 Reading-order touch-up tool and area reading order — tools/OCR (accessibility).
- §34.40 On-page content groups / tables / figures display — tools/OCR (accessibility).
- §34.41 On-page table editor — tools/OCR (accessibility).
- §34.44 Order panel (drag reorder) — tools/OCR (accessibility).
- §34.46 Content panel — tools/OCR (accessibility).
- §34.49 Find Element beyond unmarked annotations/links — tools/OCR (accessibility).

## Counts (127 rows)

| | §28 | §29 | §30 | §31 | §32 | §33 | §34 | Total |
|---|---|---|---|---|---|---|---|---|
| DONE | 14 | 16 | 40 | 10 | 0 | 0 | 17 | 97 |
| STILL MISSING | 1 | 0 | 0 | 2 | 4 | 3 | 14 | 24 |
| NOT-FEASIBLE | 1 | 0 | 0 | 1 | 0 | 0 | 1 | 3 |
| OWNER-ACTION | 0 | 0 | 3 | 0 | 0 | 0 | 0 | 3 |
| N/A-ANDROID | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

Spot-checked (5): §28 Security Properties / /P enforcement; §29 word-list import/export; §30 batch signing; §30 verify on open; §34.47 Tags panel.

---

# Foxit fcmp_28-42 sections 35, 36, 37: non-HAVE rows checked against main (fa724fd / 8555d74)

Paths are under `app/src/main/java/com/smartdocs/editor/`. "Proxy" = `ai-proxy/worker.js` route, written but not deployed (owner). With the user's own OpenAI-compatible key these features work without the proxy.

## 35. AI Assistant & Translation

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Chat about attachments / OCR before analysis | PARTIAL | DONE | `core/ai5/DocIndexer.attachments`, called from `ui/ai5/DocSet.kt:46`. Only PDF and text attachments are indexed. |
| Chat about images / charts | MISSING | DONE | Hub attach (gallery, camera, page snapshot) in `ui/ai6/HubChatInput.kt`, `core/ai6/AiMedia` vision. Through the proxy it needs the vision route deployed (owner). |
| Enhance writing: replace the selection | PARTIAL | DONE (spot-checked) | `ReaderScreen.kt:419` onReplace → `ReaderAi6.replaceSelection` → `FindReplace.replaceHit` through `quick.apply` (one undo step); the button is in `TextToolsSheet.kt:99`. A selection that spans several text blocks is refused. |
| Translation: side-by-side, images, guidance | PARTIAL | DONE | `ui/ai6/SideBySideScreen.kt` (tool `ai_side_by_side`); `AiToolsPanel.kt:143,158` passes guidance and trImages to `DocTranslator.translate`. Image translation needs vision. |
| Agentic Edit PDF mode | PARTIAL | DONE | `core/ai/DocCommands.kt:49-52` (highlight_text, replace_text, rewrite_text). |
| Intelligent Read: read the summary aloud | PARTIAL | DONE | `ui/ai5/SummaryPanel.kt:74-83` (AnswerSpeaker + `SummaryGen.spokenText`). |
| Read Aloud: highlight the current text | PARTIAL | DONE | `core/tts/ReadAloudController.kt:200` onRangeStart; `core/ai6/ReadingOrder.kt`. With an AI voice the whole piece is highlighted, not the word. |
| MCP integration / connectors | MISSING | NOT-FEASIBLE | DONE_ai_office: Android has no stdio MCP, and remote connectors need per-service OAuth plus hosting. |
| AI credits / usage display | PARTIAL | DONE | `ui/ai6/HubUsage.kt`; `ui/ai/AiSettingsScreen.kt:97-99` AiUsageLine. The local counter works now; the server counter needs the proxy `/usage` route (owner). |
| Web search in chat | MISSING | DONE | `ui/ai6/HubChatInput.kt:111` chip → `CitedChatState.kt:120` `AiMedia.webSearch`. It falls back to DuckDuckGo with no key; Brave needs the proxy SEARCH_KEY (owner). |
| Agentic chat: skills | PARTIAL | DONE | `core/ai6/HubSkills.kt`, `ui/ai6/SkillsScreen.kt` (tool `ai_skills`, `ToolScreens.kt:111`). |
| Local AI (on-device model) | PARTIAL | NOT-FEASIBLE | DONE_ai_office: it needs AICore or MediaPipe models (few devices support them, and the download is large). A user-run Ollama endpoint already works. |
| AI image generation / editing | MISSING | STILL MISSING (part) | Generation: `ui/ai6/ImageGenScreen.kt` (tool `ai_image`); it needs the proxy IMAGE_* config (owner). Missing: editing an existing image with a prompt (`/images/edits`), and the insert is not a reader undo step. |
| Export AI responses to Word | PARTIAL | DONE | `ui/ai5/CitedChatUi.kt:136,188` (Word button per answer and for the whole chat) → `core/ai6/ChatExport.kt`. |
| Chats tab on the Start page | PARTIAL | DONE | `ui/home/HomeScreen.kt:153` HomeConversationsCard → `ConversationsScreen` (tool `ai_chats`). |
| Formula to MathML / LaTeX | MISSING | DONE | The "formula" preset (`core/ai6/HubSkills.kt:34`, wantsImage) and `ui/ai6/ChatRich.kt` blocks. It needs vision. |
| Workspace crafts (slides, infographics, web pages) | PARTIAL | STILL MISSING (part) | Slides are done (`core/ai6/SlidesGen.kt`, `ui/ai6/TDocTools.kt`). Infographics and web-page crafts are missing, and Spaces has no slides entry. |
| AI feedback buttons / panel width | MISSING | DONE | `ui/ai5/CitedChatUi.kt:135` FeedbackButtons → `chat.setFeedback`. The width is a button that cycles three sizes; there is no drag handle. |

## 36. Print, batch print, invoice print, printer

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Batch Print | MISSING | DONE (spot-checked) | Tools tile `batch_print` (`ToolsScreen.kt:96`) → `ui/tools5/BatchPrintScreen.kt` → `PrintPipeline.prepare` → `PdfPrint.print` (`BatchPrintScreen.kt:84-91`). Sources: files, a folder or the open tabs. |
| Print part of a page (Snapshot → Print) | MISSING | DONE | `ui/reader/annot/Annot2Dialogs.kt:283-288` SnapshotSheet "Print" → `ToPdf.convert` → `PdfPrint.print`. |
| Print pages from thumbnails | PARTIAL | DONE | `ui/pages/OrganizeScreen.kt:155` prints the selected pages. |
| Print from bookmarks (pages / sections) | MISSING | DONE (spot-checked) | `ReaderPanels.kt:145` OutlinePrintMenu → `print(range)` → `PrintPipeline.prepare` → `PdfPrint.print` (lines 162-172). |
| Print all tabs | PARTIAL | DONE | Batch Print "open tabs" source (`BatchPrintScreen.kt:53-55`, openTabFiles). |
| Print as image | MISSING | DONE | `PrintSettings.asImage` and dpi (`core/view5/PrintSettings.kt:50-51`). |
| Bleed marks / cut marks | MISSING | STILL MISSING (part) | Cut marks exist for poster tiles only (`PrintSettings.cutMarks`). There are no bleed or crop marks for normal printing. |
| Print preview with page slider | PARTIAL | DONE | `ui/view5/PrintSheet5.kt:44,80,98` (nbt_preview, PrintPreviewDialog). |
| Print range: current view | PARTIAL | STILL MISSING | DONE_pdf3c_nav says PARTIAL: you can only take a snapshot and then print it. There is no "visible area" range. |
| Print range: logical page numbers | MISSING | DONE | `PrintSheet5.kt:46,58` `PrintLayout.labelRange(range, labels)`. |
| Scale none / fit / reduce / custom % | PARTIAL | DONE | `ui/view5/PrintForm.kt:102-107` (Auto, Fit, Shrink, Actual, Custom %). |
| Tile large pages (poster) | MISSING | DONE | `PrintSettings.poster`, posterZoom, overlap, posterLabels, cutMarks. |
| Multiple pages per sheet N×M, order, margins | PARTIAL | DONE | `PrintSettings` cols/rows/vertical/rtlOrder/margin; `PrintForm.kt:156`. |
| Booklet front / back, sheet range | PARTIAL | DONE | `PrintSettings.sides`, `sheets`. |
| Auto-rotate / centre / orientation | PARTIAL | DONE | `PrintSettings.autoRotate`, landscape. |
| Print What (document / markups / stamps / form fields / annotations) | PARTIAL | DONE | `core/view5/PrintPrep.kt:19` enum PrintContent (5 values). |
| Print notes and pop-ups | MISSING | DONE | `PrintSettings.notes` (nbt_print_notes). |
| Summarize comments when printing | PARTIAL | DONE | `PrintSettings.summary`; `printSummaryLabels` in `PrintForm.kt:34`. |
| Simulate overprinting | MISSING | NOT-FEASIBLE | DONE_pdf3c_nav: neither PDFium nor Android supports it. |
| Page setting (margins) | PARTIAL | DONE | `PrintSettings.margin`, papers A4/Letter/A3/A5/Legal. |
| Virtual printer: presets, folder, security | PARTIAL | DONE | `core/tools5/ShareAndPrint.kt:136-141` applies the printer recipe (`RecipeStore.printerRecipe`), chosen in `ui/tools5/RecipesScreen.kt:240-241`. The output folder is fixed (Documents/Printed). Append is NOT-FEASIBLE (one PDF per job). |
| Printer conversion settings (version, fonts, standards, watermark, security…) | MISSING | DONE | Same mechanism: a recipe with security, watermark, header/footer and PDF/A steps. |

## 37. Preferences

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 3D: playing / trust prompt | MISSING | NOT-FEASIBLE | No 3D renderer (DONE_view_edit §11; DONE_pdf3c_nav: "3D model tree NF"). |
| A11y: high-contrast presets | PARTIAL | DONE | `ui/settings/NpfSettingsScreen.kt:239` npf_a11y_presets. |
| A11y: custom page background + text colour | PARTIAL | DONE (spot-checked) | Settings → NpfSettingsCard (`SettingsScreen.kt:111`) → tool `npf_settings` → `NpfColorRow` K.A11Y_FG/A11Y_BG (`NpfSettingsScreen.kt:249-252`) → `NpfReader.pageFilter` (`NpfReaderHooks.kt:72`) → `PageCanvas.kt:125`. |
| A11y: only change black text / line art | MISSING | NOT-FEASIBLE | DONE_pdf3c_nav: the page is a raster, so only one tint filter can be applied. |
| A11y: always use page layout / zoom | PARTIAL | DONE | `NpfPrefs.defaultLayout` and `defaultZoom` → `ReaderState.kt:105-107`; Settings rows npf_layout and npf_zoom. |
| A11y: document structure for tab order (pref) | MISSING | STILL MISSING | There is no preference. Structure tab order exists only as an AccessFix fix and as a form-designer tab-order mode. |
| A11y: show portfolios in Files mode | MISSING | DONE | Portfolio prompt on open (`ui/attachments/ReaderAttachments.kt` PortfolioPrompt, nat_portfolio_open) → portfolio view. There is no separate preference. |
| A11y: show checker options dialog | PARTIAL | DONE | The checker has rule-category chips (`AccessRules.apply`; PAGES_CONVERT_LOG:147). |
| Action Wizard: log file; PDF/UA report while making accessible | MISSING | STILL MISSING | The recipe per-file log stays inside the app (`core/tools5/BatchWorker.kt:56` JSON, shown in RecipesScreen) and cannot be saved as a log file. The PDF/UA built-in recipe exists (nbt_rc_pdfua), but no report is written while it runs. |
| Commenting: pop-up font / size / opacity | MISSING | DONE | npf_note_font, npf_note_size, npf_note_opacity (`NpfSettingsScreen.kt:181-189`); used in `AnnotEditUi.kt` and `CommentExtras.kt`. |
| Commenting: print notes and pop-ups | MISSING | DONE | `PrintSettings.notes`. |
| Commenting: use the login name as author | PARTIAL | DONE | npf_id_as_author uses the shared identity (`NpfSettingsScreen.kt:292-298`). It is not tied to the account login. |
| Commenting: copy selected text into markup pop-ups | PARTIAL | DONE | `NpfPrefs.copySelection` → `ReaderState`. |
| Commenting: auto-open pop-ups; copy encircled text | MISSING | STILL MISSING (part) | Encircled text is copied (24.41, DONE_comments_forms). There is no auto-open pop-up preference (`autoOpen` appears only in Presentation5). |
| Commenting: pencil stroke timeout | MISSING | STILL MISSING | No stroke-merge timeout found (inkTimeout, strokeMerge and similar: 0 hits). |
| Create PDF: custom blank page size | PARTIAL | STILL MISSING | The insert-blank screen only offers preset papers (`ui/edit5/TemplatesBlankScreens.kt` PageBoxOps.PAPERS). There are no custom width/height or units. |
| Documents: alert on a full-screen request | MISSING | STILL MISSING | /PageMode FullScreen is now honoured (`ReaderScreen.kt:69-73`, chrome = false), but the reader shows no alert and has no preference for it. |
| Documents: filename vs title | PARTIAL | DONE | `NpfPrefs.showDocTitle` → `NpfReader.title` (`ReaderScreen.kt:238`). |
| Documents: auto-save before e-mail; page range | PARTIAL | DONE | The share sheet sends the current file with its pending annotations (`ShareOptionsSheet.kt:197-214`); the page range was already HAVE. |
| Documents: PDF/A view mode | MISSING | STILL MISSING (part) | A PDF/A claim notice exists (npf_pdfa_claim, `ui/reader/NpfPanels.kt`). There is no read-only PDF/A view mode and no "never / only PDF/A" choice. |
| Documents: auto-save every N minutes | PARTIAL | DONE | `NpfPrefs.autoSaveMinutes` → `NpfReaderEffects` (npf_autosave_every). |
| Documents: linearize on Save As | MISSING | NOT-FEASIBLE | DONE_pdf3c_nav: PdfBox 2.0 cannot write linearized files. |
| Documents: auto-embed fonts | PARTIAL | DONE | The edit preferences have a font embedding choice: subset, full or system (DONE_view_edit §3, fork A). |
| Documents: preferred Save As location | MISSING | DONE | `NpfPrefs.saveTree` (npf_save_folder_app; `core/files/NpfSaveFolder.kt`). |
| Documents: redaction filename prefix / suffix | PARTIAL | DONE | `core/redact/RedactPrefs.kt:84-86`; `RedactScreen.kt:199` nt_rd_suffix field. |
| Editing: show changed text in colour | MISSING | STILL MISSING | 0 hits for changedTextColor or similar. |
| Editing: auto-recognise text on scanned pages | PARTIAL | STILL MISSING (part) | Edit mode has an OCR button and a "no editable text" alert (DONE_view_edit §14a), but it does not run OCR automatically. |
| Forms: manage barcode parameters | MISSING | DONE | `core/formsx/FxBarcodes.kt` with the Options tab in the form designer (DONE_comments_forms 8.9). |
| Forms: text-field overflow indicator | MISSING | STILL MISSING | 0 hits in forms5 / formsx. |
| Forms: highlight colours | PARTIAL | DONE | `ui/settings/FormRunSettings.kt` (highlight colours; `SettingsScreen.kt:108`). |
| Forms: run form recognition when creating a PDF | MISSING | STILL MISSING | Recognition is manual only (designer fx_detect "Run form field recognition"). It does not run after Create PDF. |
| Forms: Auto-Complete off / basic / advanced, edit the list | MISSING | DONE | `FormRunSettings.kt:63-72` (switch plus AutocompleteManager for list, edit, delete and clear). There are no basic/advanced levels. |
| Forms: e-mail default subject / contents | PARTIAL | STILL MISSING | No default-subject setting was found. |
| Full Screen: per-item choices | PARTIAL | STILL MISSING | Still a single full-screen switch. |
| Full Screen: background colour | MISSING | DONE | `NpfPrefs.presentBackground` → `Presentation5.kt` (npf_present_bg). |
| Full Screen: transitions | PARTIAL | DONE | `core/nve/PageTransitions.kt`, NveTransitionsSheet (12 types; DONE_view_edit §9). |
| General: single-key accelerators | MISSING | DONE | `ui/nve/NveKeys.kt` with a preference (DONE_view_edit §39). |
| General: create links from URLs | MISSING | DONE | `core/nve/UrlFinder.kt` + PageCanvas hook; e5_links_linkify. |
| General: hand tool reads articles | PARTIAL | DONE | `ui/nve/articles/NveArticlesUi.kt`. |
| General status bar: auto-hide / scroll lock | PARTIAL | DONE | Scroll lock in `ui/nve/NveHost.kt`; the full-screen reader hides the bars. |
| Notification messages: per-message gear / PDF/A message | PARTIAL | STILL MISSING (part) | Category toggles (npf_s_notifications; sync, review, tasks) and the PDF/A notice are done. There is no per-message settings gear. |
| Disable all Internet features | MISSING | DONE | `core/data/NpfNet.kt` (`NpfPrefs.offline`), npf_offline_hint. |
| Startup: show advertisement | PARTIAL | NOT-FEASIBLE | DONE_pdf3c_nav: the ads switch is a paid benefit (a product decision). |
| Startup: debug mode | PARTIAL | STILL MISSING | There is a crash log (ac_feedback_crash) but no debug-log switch. |
| Startup: crash recovery of unsaved edits | PARTIAL | DONE | `ui/nve/shell/Recovery.kt`; NpfPrefs.recoveryMinutes (npf_recovery). |
| History: max recent documents | MISSING | DONE | `NpfPrefs.recentLimit` → `HomeScreen.kt`. |
| Identity: one identity with unit / e-mail | PARTIAL | DONE | npf_id_unit, npf_id_email, npf_id_as_author. |
| Index preferences | PARTIAL | STILL MISSING (part) | Stop words, numbers and excluded folders exist (`ui/nve/search/NveSearch.kt:38`). There are no XMP, custom-property, structure-tag or logging options. |
| JavaScript: enable JS actions | MISSING | DONE | JsLevel in `ui/settings/NpSecuritySettings.kt` (`SettingsScreen.kt:90`), `core/security/TrustPrefs.jsLevel`. |
| Languages: follow the system / more languages | PARTIAL | STILL MISSING | `SettingsScreen.kt:70` still offers only "ar" and "en", with no "system" option. |
| Languages: reading direction inferred from the document | PARTIAL | DONE | `ReaderState.kt:304` `docRtl = InitialViewMap.rtl(v.direction)`. |
| Languages: text-direction switch for text comments / objects | PARTIAL | DONE | The textbox dialog has an RTL flag (WorkbenchDialogs) and edit text has nt_direction (`ui/edit/TextTools5.kt`). |
| Languages: Arabic-Indic digits when typing | PARTIAL | DONE | `NpfPrefs.digits` → `NpfReader.typed`, `ui/nve/NveDigits.kt`. |
| Layout grid | PARTIAL | DONE | NpfPrefs grid colour, offsets and subdivisions (`NpfSettingsScreen` npf_s_units). |
| Measuring: label / leader / scale from the document | PARTIAL | DONE | `core/view5/ScaleStore.kt` (/VP), leader and label (DONE_comments_forms 25.4, 25.10, 25.14). |
| Page Display: panel shown on open | MISSING | DONE | `NpfPrefs.panelOnOpen` (`NpfReaderHooks.kt:113`) plus /PageMode (`ReaderScreen.kt:69-73`). |
| Page Display: default layout (incl. continuous facing) | PARTIAL | DONE | `NpfPrefs.defaultLayout`; continuous facing nv_continuous_facing. |
| Page Display: default zoom | MISSING | DONE | `NpfPrefs.defaultZoom` → `NpfReader.initialZoom`. |
| Page Display: facing page count / margin between pages | PARTIAL | STILL MISSING | Not found in the reader prefs (e5_gap belongs to imposition). |
| Page Display: smooth text / line art / thin lines | MISSING | NOT-FEASIBLE | DONE_pdf3c_nav: the PDFium wrapper has no flag for it. |
| Page Display: logical page numbers | CODE-ONLY | DONE | `ReaderScreen.kt:67,338,384` rememberPageLabels → PageIndicator and GoToPageDialog. |
| Page Display: always show the page size | PARTIAL | STILL MISSING | It is shown only in document properties and info. |
| Page Display: forbid zoom change on Go-to-Destination | MISSING | STILL MISSING | No preference. A link can be authored to "keep zoom" (e5_link_zoom_keep), but that is not a reader preference. |
| PDF Sign: flatten when applying all signatures | PARTIAL | DONE | `core/fillsign2/SignatureFlatten.kt`, FillSignPrefs (DONE_pdf3c_protect_sign: apply all + flatten). |
| Print: batch print settings / confirm | MISSING | DONE | `BatchPrintScreen.kt`. |
| Reading: assistive technology support | PARTIAL | DONE | 41 `pc_cd_*` TalkBack labels (PAGES_CONVERT_LOG:151). Only 5 `contentDescription = null` remain in the app. |
| Reading: reading order preference | PARTIAL | DONE | `NpfPrefs.readingOrder` (npf_read_order) → `ReaderAi6`. |
| Reviewing: auto-check interval | PARTIAL | DONE | nat_rv_minutes / nat_rv_hours (DONE_pdf3c_nav §31). |
| Reviewing: local reminder before expiry | PARTIAL | STILL MISSING | Only server reminders to reviewers exist (sh5_auto_remind). |
| Reviewing: take ownership of others' comments | PARTIAL | STILL MISSING | 0 hits for ownership or takeOver. |
| Reviewing: auto-complete To/Cc names | MISSING | STILL MISSING | Not found. |
| Reviewing: send as FDF above N MB | PARTIAL | STILL MISSING | Only the "comments only" XFDF format; there is no size rule. |
| Search: ignore Asian character width | MISSING | STILL MISSING | Not built (low relevance). |
| Search: document title in results / max results / proximity | PARTIAL | STILL MISSING (part) | Max results (npf_s_max) and proximity (s5_proximity) are done. Showing the document title in results was not found. |
| Search: Fast Find cache size limit | PARTIAL | STILL MISSING | The cache can be cleared, but there is no size limit setting. |
| Security: Protected View | MISSING | DONE | `TrustPrefs.pvMode` → `ui/settings/Np2TrustSettings.kt` (`SettingsScreen.kt:91`). |
| Security: Privileged Locations | MISSING | DONE | `TrustPrefs.addFolder` / `addHost` → `Np2TrustSettings.kt`. |
| Signature: manage appearance styles | PARTIAL | DONE | `core/sign/SigAppearanceSpec.kt:106` SigAppearanceProfiles; `SigAppearanceDesigner.kt`. |
| Signature: verify signatures on open | MISSING | DONE (spot-checked) | `ReaderScreen.kt:370` SigFieldSheet → LaunchedEffect(file) → `SigFields.list` + `DigitalSignature.verify` → badges (`fs.sigStatus`) + SignedBar (`ui/signature/SigFieldActions.kt:48-62`). |
| Signature: prompt to review / trust untrusted signers | PARTIAL | STILL MISSING | You can add a signer to trusted from the verify result (`CertTrust.kt`), but the app never prompts when a document opens. |
| Signature: require revocation checking / expired timestamps / ignore validation info | MISSING | STILL MISSING (part) | The revocation toggle is done (`SignPrefs.checkRevocation`, DigitalSignScreen). The "use expired timestamps" and "ignore DSS" options are missing. |
| Signature: verify using creation time / timestamp / now | MISSING | DONE | `DigitalSignature.verificationTime`, chosen in `DigitalSignScreen.kt`. |
| Signature: verification info (ask if too big) | PARTIAL | STILL MISSING | There is only the LTV switch when signing (`SignAdvancedOptions.kt:45`), with no "ask" mode. |
| Signature: PKCS#11 module settings | MISSING | N/A-ANDROID | Android cannot load PKCS#11 modules. The equivalent, Android KeyChain IDs, is done (`core/sign/DigitalIds.kt` chooseKeyChain, CertificateTab). |
| Speech: read comments / form fields | MISSING | DONE | `core/nve/NveReadOrder.kt:28` Prefs(comments, fields, alt) → `ui/nve/tts/NveReadAloud.kt`. DONE_view_edit says there is no Tab-through-fields mode. |
| Spelling: spell check + dictionaries | PARTIAL | DONE | `ui/nve/Spell.kt`, SpellDictionarySheet (DONE_view_edit §22). |
| Trust Manager | MISSING | DONE | Np2TrustCard (safe reading, privileged locations, Protected View, attachments, EU list), `core/security/TrustManager.kt`. |
| OCR preferences (language) | PARTIAL | DONE | All pinned languages in the OCR screens (nt_ocr_all_langs, nt_ocr_more_langs), npf_ocr_auto. The basic Settings row still offers 3 choices (`SettingsScreen.kt:144`). |

## STILL MISSING

Med
- 37 Languages: no "follow system" option and no languages beyond Arabic and English (nav/settings).
- 37 Editing: no automatic OCR when editing a scanned page; only a button and an alert (edit).
- 37 Forms: form recognition does not run automatically after Create PDF (comments/forms).

Low
- 35 AI image editing with a prompt (`/images/edits`); the image insert is not undoable (AI/office). Generation itself needs the proxy deploy (owner).
- 35 Workspace crafts: no infographics or web pages, and no slides entry in Spaces (AI/office).
- 36 Bleed / crop marks for normal printing (only poster cut marks) (nav/settings: print).
- 36 Print range "current view" (only via Snapshot → Print) (reader/view).
- 37 Tab order from document structure as a preference (comments/forms).
- 37 Action Wizard log file export, and a PDF/UA report during Make Accessible (tools/OCR).
- 37 Auto-open pop-ups for non-note comments (comments/forms).
- 37 Pencil stroke-merge timeout (comments/forms).
- 37 Custom blank page size and units (pages/convert).
- 37 Alert / preference when a document requests full screen (reader/view).
- 37 PDF/A view mode (read-only for PDF/A files) (reader/view).
- 37 Show changed text in colour while editing (edit).
- 37 Form text-field overflow indicator (comments/forms).
- 37 Default e-mail subject / body for form submission (comments/forms).
- 37 Full-screen per-item choices: scroll bar, status bar, tabs, panels, exit button (reader/view).
- 37 Per-message notification settings gear (nav/settings).
- 37 Debug-mode logging switch (nav/settings).
- 37 Index preferences: XMP, custom properties, structure tags, logging (files/search).
- 37 Facing page count and page-gap setting (reader/view).
- 37 Always show the page size on the page (reader/view).
- 37 Keep zoom on Go-to-Destination (reader/view).
- 37 Local reminder before a review expires (files: share/review).
- 37 Take ownership of other reviewers' comments (files: share/review).
- 37 To/Cc e-mail auto-complete (files: share/review).
- 37 Send as FDF above N MB (files: share/review).
- 37 Ignore Asian character width in search (files/search).
- 37 Document title in search results (files/search).
- 37 Fast Find cache size limit (files/search).
- 37 Prompt to trust untrusted signers on open (sign/protect).
- 37 "Use expired timestamps" / "ignore document validation info" options (sign/protect).
- 37 "Ask if verification info is too big" when signing (sign/protect).

## Counts (128 rows: §35 18, §36 22, §37 88)

| Status | §35 | §36 | §37 | Total |
|---|---|---|---|---|
| DONE | 14 | 19 | 52 | 85 |
| STILL MISSING (incl. part) | 2 | 2 | 30 | 34 |
| NOT-FEASIBLE | 2 | 1 | 5 | 8 |
| OWNER-ACTION | 0 | 0 | 0 | 0 (proxy deploys are noted inside DONE rows of §35) |
| N/A-ANDROID | 0 | 0 | 1 | 1 |

Spot-checked (5): rewrite replace (§35), Batch Print (§36), print from bookmarks (§36), a11y colour override (§37), verify on open (§37).

---

# Foxit §38–42 (fcmp_28-42.md): status on main today (fa724fd / 8555d74)

Paths are under `app/src/main/java/com/smartdocs/editor/`. Read-only check; nothing was run on a device.

## §38 Plug-ins
No PARTIAL / MISSING / CODE-ONLY rows: every row is N/A-ANDROID or HAVE.

## §39 Shortcuts
Two key tables are in use: `ui/nve/NveKeys.kt` (enum `NveKey`, hooked in at `ui/reader/ReaderScreen.kt:315` before `V5Keys`) and `ui/view5/Keys5.kt`.

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Customize keyboard shortcuts | MISSING | DONE (spot-checked) | ⋮ menu item `nv_keys_title` (`ui/nve/NveHost.kt:141`), or command `nv_keys` (`NveHost.kt:200`) → `NveSurface.KEYS` → `NveKeysSheet` → `NveKeys.bind/reset/resetAll` (prefs "nve_keys") → `NveKeys.chord()` is read in `onKey`, which ReaderScreen calls |
| Ctrl+O / Ctrl+W / Ctrl+Shift+W / Ctrl+Q | MISSING | DONE | `NveKey.OPEN/CLOSE_TAB/CLOSE_ALL`. Ctrl+Q (exit) is not bound; Android apps do not quit themselves (N/A) |
| Ctrl+D properties / Ctrl+K preferences | PARTIAL | DONE | `NveKey.PROPERTIES` opens DOC_INFO; `PREFERENCES` takes Ctrl+K only with the "Foxit layout" switch on (otherwise Ctrl+K is Tell me) |
| F3 / Shift+F3 / Ctrl+Shift+F | PARTIAL | DONE | `FIND_NEXT`, `FIND_PREV`, `ADV_SEARCH` (opens the `search_all` tool) |
| Ctrl+1/2/3, Ctrl+M zoom to | PARTIAL | DONE | `ACTUAL`, `FIT_WIDTH`, `FIT_VISIBLE` → `NveZoom.preset`. Ctrl+M = ZOOM_TO only in Foxit layout |
| Ctrl+Shift+Plus / Minus rotate view | MISSING | DONE | `ROTATE_CW` / `ROTATE_CCW` |
| Ctrl+C / X / V / A, Ctrl+Shift+A | PARTIAL | STILL MISSING | Ctrl+A, Ctrl+C (selected text) and Ctrl+V (`NveClipboard.paste`) are done. Missing: Ctrl+X, Ctrl+Shift+A (deselect), and copy / paste keys for annotations |
| Ctrl+B / Ctrl+T / Ctrl+Shift+C | MISSING | STILL MISSING | Ctrl+T opens find_replace. Ctrl+B only opens the BOOKMARKS panel; it does not add a bookmark. Ctrl+Shift+C (cross-reference) is still the Comment manager (`Keys5.kt:50`) |
| Ctrl+Shift+D / T / I, Ctrl+Alt+D | MISSING | STILL MISSING | D, T and I are done (`DELETE_PAGE`, `INSERT_BLANK`, `INSERT_FILE`). Ctrl+Alt+D (duplicate page) is not bound |
| Comment tool keys (Shift+H/U/S, Ctrl+U, Shift+P/C/E/T, Ctrl+Shift+N, Shift+I, Ctrl+Shift+R) | MISSING | DONE | `NveKey.HIGHLIGHT` … `REPLACE_TEXT`. Ctrl+Shift+R needs the Foxit layout |
| Ctrl+L export comments / Ctrl+I export highlighted / Ctrl+8, Ctrl+Shift+8 hide / show / Ctrl+Enter reply / Ctrl+Alt+V paste in place | MISSING | STILL MISSING | None of these is in `NveKey` or `V5Keys`. Ctrl+L opens the comment manager (Foxit layout) or presentation. DONE_view_edit also lists Ctrl+8 as NOT DONE. The features themselves exist (hide comments, paste in place, export) |
| F11 / Ctrl+H / Ctrl+6 / Ctrl+R / Ctrl+5 / Alt+5 / F4 | MISSING | DONE | `FULL_SCREEN`, `READ_MODE`, `TEXT_VIEWER`, `RULERS`, `MARQUEE`, `NAV_PANE`. Ctrl+5 (line weights) is NOT-FEASIBLE: PDFium has no line-weight option |
| Ctrl+Shift+H auto-scroll; Esc stop | MISSING | DONE | `AUTO_SCROLL`; Esc and 0–9 / minus while scrolling (`NveKeys.onKey`) |
| Read aloud Ctrl+Shift+V / B / E / P / Y | MISSING | STILL MISSING | Only V (`READ_ALOUD`) and E (`READ_STOP`) are bound. B, P (pause) and Y are missing |
| Space / Shift+Space / Ctrl+Home / End / Ctrl+PgUp / PgDn | PARTIAL | DONE | `SPACE_DOWN/UP`, `FIRST_PAGE/LAST_PAGE`, `PREV_PAGE/NEXT_PAGE` |
| Ctrl+Tab / Ctrl+Shift+Tab | MISSING | DONE | `NEXT_TAB/PREV_TAB` → `tabStep` over `ReaderTabs.tabs` |
| Ctrl+F11 / F1 / F10, F6, Shift+F10 | MISSING | STILL MISSING | F1 (`HELP`) and F6 (pane cycle, `NveHost.kt:225`) are done. F10, Shift+F10 and Ctrl+F11 are missing (Low; these are desktop focus keys) |
| Form: Tab / Shift+Tab; Ctrl+J JS console | PARTIAL | STILL MISSING | No `KEYCODE_TAB` handler in `ui/forms5` (the form bar still uses previous / next buttons). The JS console exists (`ui/edit5/DocScriptsScreen.kt`, DocJsConsole), but no Ctrl+J key opens it |
| Ctrl+Shift+L e-mail current / Ctrl+Shift+K e-mail all tabs | MISSING | STILL MISSING | Ctrl+Shift+L is done (`NveKey.EMAIL` → share). Ctrl+Shift+K is not bound; "e-mail all open tabs" exists in `ShareOptionsSheet.kt:165` |
| Single-key accelerators + preference | MISSING | DONE | `NveKeys.singleKey` switch in `NveKeysSheet`; map `single` (H, U, K, D, X, S, Z, F, E) |

## §40 Navigation panels
The reader INFO panel (`ReaderPanels.kt:73` → `NpfInfoPanels`, enum `NpfPanel` in `ui/reader/NpfPanels.kt:53`) and the NVE panes (`ui/nve/panes/NvePanes.kt`, enum `NvePane`).

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Layers panel | MISSING | DONE | `NpfPanel.LAYERS` (show / hide) plus the full tool `e5_layers` (`LayersScreen`, PAGES_CONVERT_LOG stage E) |
| Attachments panel | PARTIAL | DONE | `ReaderPanel.ATTACHMENTS` (`ui/attachments/ReaderAttachments.kt`) |
| Security Settings panel | MISSING | DONE | `NpfPanel.SECURITY` → `NpfDocPanels.security`; also `NveSecurityPanel` in the document info sheet |
| Digital Signatures panel | MISSING | DONE (spot-checked) | View chip / ⋮ "Signatures" (`NveHost.kt:112,139`) → `NvePane.SIGNATURES` → `SignaturesPane` (`NvePanes.kt:199`) → `PaneReaders.signatureFields`, then the Validate button → `DigitalSignature.verify(f, revocation = false)` |
| Fields panel | PARTIAL | DONE | `NpfPanel.FIELDS`, `NvePane.FIELDS` (`FieldsPane`) |
| Accessibility Check / Report panel | PARTIAL | DONE | `NpfPanel.ACCESS` (`AccessRules.counts` summary) |
| Recognition Report (OCR) | PARTIAL | DONE | `NpfPanel.OCR` → `OcrReport` (pages without text; Run OCR) |
| Tags / Content / Order panels | MISSING | STILL MISSING | Tags: done (`NpfPanel.TAGS`, `NvePane.TAGS`, `TagsEditor` in `ui/tools5/AccessTagsUi.kt`). Missing: a Content panel and an Order panel. PAGES_CONVERT_LOG stage F marks "reading order panel by drag / content panel" OPEN; reorder works only by moving nodes in the tags tree |
| Articles panel | MISSING | DONE | `NpfPanel.ARTICLES`, `NvePane.ARTICLES`, `ui/nve/articles/NveArticlesUi.kt` |
| Model Tree (3D) | MISSING | NOT-FEASIBLE | The app has no 3D renderer (DONE_pdf3c_nav NF; DONE_view_edit §11) |
| Destinations panel | MISSING | DONE | `NpfPanel.DESTS`, `NvePane.DESTINATIONS` (`DestinationsPane`), `ui/edit5/DestinationsScreen.kt` |
| Cross References panel | MISSING | DONE | Tool `e5_xref` → `CrossRefScreen` (`ui/edit5/Edit5Hub.kt:67`), reached from the reader's Tools tab (edit5 tools). It is a screen, not a side panel |
| ConnectedPDF panel | PARTIAL | DONE | `NpfPanel.CONNECTED` |
| Standards panel | MISSING | DONE | `NpfPanel.STANDARDS` (PDF/A, UA, X, E, tagged; button to the `pdfa` tool) |
| Portfolio panes | MISSING | DONE | `PortfolioPrompt` (`ReaderScreen.kt:371`) → tool `portfolio` → `PortfolioEditor` (stage D) |
| Panel mechanics (width, dock / float) | PARTIAL | DONE | Panel-width slider (`NpfSettingsScreen.kt:157`, used at `ReaderScreen.kt:354`). Dock / float is N/A on a phone |

## §41 Version-history additions

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 14.0 PDF Binder | MISSING | DONE | `CombinePlusUi.kt` binder card, `BinderProject` / `BinderStore` (`core/tools5/CombinePlus.kt`). The Word TOC template is still OPEN |
| 14.0 / 2024.2 Tickmarks | MISSING | DONE (spot-checked) | `Toolset.kt:56` ToolEntry TICKMARK → `Annot2Host.kt:199` `cx.placeTick` → `CommentExtras.kt:437` (subject `AnnotStyle.TICKMARK`, numbered / lettered) |
| 14.0 3D glTF | MISSING | NOT-FEASIBLE | The app has no 3D renderer |
| 14.0 ISO/TS 32001/32002 hashes | PARTIAL | DONE | `DigitalSignature.Digest` SHA-384/512, SHA3-256/512 (`core/pdf/DigitalSignature.kt:77`); Ed25519 IDs (`core/sign/DigitalIds.kt:42`) |
| 14.0 AES-GCM (ISO/TS 32003) | MISSING | NOT-FEASIBLE | PdfBox 2.0 cannot write AESV4 (DONE_pdf3c_protect_sign) |
| 14.0 Accessibility: keyboard, button identifiers | PARTIAL | DONE | 41 `pc_cd_*` TalkBack labels (stage F); the keyboard map above. Not checked with TalkBack on a device |
| 14.0 Batch page resize | PARTIAL | DONE | `ui/edit5/CropResizeScreens.kt:155-213`: batch file list, the same size applied to each file |
| 14.0 OCR Vietnamese / Indonesian | MISSING | DONE | `core/ocr/OcrModels.kt` pins `vie` / `ind` (all tessdata models); "More languages…" picker |
| 14.0 XMP save / import | PARTIAL | DONE (spot-checked) | Ctrl+D or ⋮ → DOC_INFO → `ui/nve/file/DocInfoSheet.kt:75` export launcher → `XmpOps.exportFile`; import with replace / merge; also in `DocPropsScreen.kt:49,58` |
| 14.0 Combine thumbnail preview (delete / reorder pages before combining) | PARTIAL | STILL MISSING | Only a page-range text field per file (`CombinePlusUi.kt:163`). There is no thumbnail preview for deleting or reordering pages |
| 13.0 Auto-bookmarks from text styles / search strings | PARTIAL | DONE | `core/edit5/AutoBookmarks.kt` (5 levels; font / size per level; include / exclude keywords, masks) |
| 13.0 Skin colour | PARTIAL | DONE | Accent colour (`NpfSettingsScreen.kt:257-261`, `core/theme/Accent.kt`) |
| 13.0 Partial-page OCR | MISSING | DONE | Tool `ocr_zone` (`OcrZoneScreen`), plus a button in the reader OCR sheet |
| 13.1 Support chatbot | PARTIAL | DONE | Route `ai_help` → `ui/ai6/HelpChatScreen.kt`, chip in `HelpCenter5.kt` |
| 13.1.4 / 2024.3 Non-PDF attachment security | PARTIAL | DONE | `AttachmentPolicy` (`core/security/TrustManager.kt`), `ui/security/DocSecurityGate.kt`, `Np2TrustSettings.kt` |
| 2024.1 AI analysis with OCR first / attachments | PARTIAL | DONE | `DocIndexer` indexes attachments (PDF / txt / md / csv) and OCRs scanned pages |
| 2024.3 AI quota purchase, auto analysis, feedback buttons, panel width | PARTIAL | DONE | Feedback thumbs (`CitedChatUi.kt`, `AiStores`); width cycling (`DocAssistantSheet.kt:64`); paywall. Width can only be cycled, not dragged |
| 2025.1 AI tab, voices, AI bookmarks, chat about images, multi-document | PARTIAL | OWNER-ACTION | Images: hub attach / camera / page snapshot (`AiHubScreen`, `HubChatInput`). This works with the user's own key; through the app proxy it needs the vision route deployed (`ai-proxy/worker.js` VISION_MODEL) |
| 2025.2 MCP host | MISSING | NOT-FEASIBLE | No stdio MCP on Android, and remote connectors need OAuth hosting (DONE_ai_office) |
| 2025.2 Intelligent Read + Smart Podcast | HAVE / PARTIAL | DONE | Read-aloud of the summary in `SummaryPanel` (`SummaryGen.spokenText`) |
| 2025.2 AI live web search; agentic chat | MISSING / PARTIAL | DONE | Web-search chip with a keyless DuckDuckGo fallback (`core/ai6/AiMedia.kt`); Brave needs the proxy SEARCH_KEY. Skills: `ai_skills` |
| 2025.3 AI image generation / editing | MISSING | STILL MISSING | Generation is in code (`ui/ai6/ImageGenScreen.kt`, route `ai_image`) and needs the IMAGE_* proxy config (owner). Prompt-based editing of an existing image (`/images/edits`) is not built |
| 2025.3 Export AI responses to Word / PDF | PARTIAL | DONE | `core/ai6/ChatExport.kt` → DocxWriter; Word button in `CitedChatUi` |
| 2025.3 Comments from other editors (Bluebeam) shown / editable | PARTIAL | STILL MISSING | No work or test for editing foreign subtypes. The only "Bluebeam" hit is a tickmark comment (`AnnotStyle.kt:502`) |
| 2025.3 CSV comment summary (columns, types, page range, sort) | PARTIAL | STILL MISSING | The CSV follows the Manager filters (type, page, author), but its columns are fixed (`CommentFilter.csv`, `CommentFilter.kt:97`). There is no column picker |
| 2025.3 Favorite Toolbox / 2026.2 toolbox management | MISSING | DONE | `Toolset.kt:85` favourite tools; the Custom reader tab with rename / remove all; `ui/nve/shell/FavoritesUi.kt` |
| 2025.3 Sequential Stamp (auto-increment) | MISSING | STILL MISSING | `StampTokens.fill` (`core/view5/HijriDate.kt:74-90`) has no counter token. Numbered tickmarks exist, but there is no sequential stamp |
| 2025.3 Remove hidden information on close / e-mail | PARTIAL | DONE | `core/share5/ShareStrip.kt`, toggles in `ShareOptionsSheet.kt:91` (on share only; nothing runs on close) |
| 2025.3 AIP SSO; Dropbox Team Folders | N/A / PARTIAL | STILL MISSING | AIP is N/A. `core/cloud/Dropbox.kt` has no team-folder or path_root handling |
| 2026.1 PDF Action Inspector on open | PARTIAL | DONE (spot-checked) | The document opens → `ReaderGuard` (`ui/reader/ReaderGuard.kt:76-91`) `scan()` → `ActionInspector.scan` → `JsRun.showBanner` warning, with trust / block answers. It is a static analysis, not AI |
| 2026.1 Chats tab on Start page | MISSING | DONE | `ui/ai6/HubConversations.kt` (Home card, route `ai_chats`) |
| 2026.1 Pencil: hide cursor on stylus; pencil over text highlights | MISSING | STILL MISSING | 0 hits for `pencilHighlight`, `PointerIcon`, `hideCursor` |
| 2026.1 Overprint Preview | MISSING | NOT-FEASIBLE | No support in PDFium or Android (DONE_pdf3c_nav NF) |
| 2026.1 Preflight whole-PDF grayscale | PARTIAL | STILL MISSING | Grayscale is still a separate convert tool (`pdf_gray`, which has known defects in convert-audit C #27/#28). It is not a preflight fix |
| 2026.2 Foxit Workspace | PARTIAL | STILL MISSING | Slides are done (B13, partial). Infographics and web-page crafts are missing (0 hits for `infographic`) |
| 2026.2 Agent chat: Edit PDF mode, Create Image, models, MCP, skills | PARTIAL | OWNER-ACTION | Edit PDF (B10) and skills are done. Create Image needs the image proxy. MCP is NOT-FEASIBLE |
| 2026.2 Local AI (BYOM / SLM) | PARTIAL | NOT-FEASIBLE | DONE_ai_office: AICore is on few devices, and the model would be a large download. A local endpoint (Ollama) already works |
| 2026.2 Formula to MathML / LaTeX | MISSING | OWNER-ACTION | The "formula" skill in `HubSkills.kt` / `ChatRich.kt` needs vision (the proxy route, or the user's own key) |
| 2026.2 Translation: choose pages, translate images, Danish | PARTIAL | STILL MISSING | Pages are done. Images: `DocTranslator.translateImage` (needs vision). Danish is not in `AiLang.NAMES` (`core/ai5/AiText.kt:97-100`) |
| 2026.2 Read Aloud with highlighted text | PARTIAL | DONE | `core/ai6/ReadingOrder.kt`, `ReadAloudController.onRangeStart`, `PageCanvas` overlay |
| 2026.2 Volume, polylength, diameter and radius tools | PARTIAL | STILL MISSING | `MeasureType` still has only DISTANCE, PERIMETER, AREA, CIRCLE, ANGLE and COUNT (`core/view5/MeasureModel.kt:34`) |
| 2026.2 Page labels from bookmarks / page regions | MISSING | STILL MISSING | 0 hits. The page-labels tool uses ranges only |
| 2026.2 Compare: cross-page text compare | PARTIAL | DONE | `PageMatch.align` (`core/tools5/CompareCore.kt:45,184`), auto match with moved and inserted / deleted pages (NITRO_TOOLS_LOG §15) |
| 2026.2 Enterprise stamps from a shared drive with edit lock | MISSING | STILL MISSING | Stamps are local only (synced through the app-data bundle). There are no shared or admin stamps (DONE_comments_forms 24.58 PARTIAL) |
| 2023.3 eSign Pro: bulk send, online form, branding | MISSING | OWNER-ACTION | `core/esign/*` (bulk, online form, branding). The online form and branding need `ai-proxy/esign/worker.js` redeployed (DONE_pdf3c_protect_sign) |
| Online manual utilities: word count, loupe | PARTIAL | DONE | `NveWordCountDialog`; lens / magnifier (`Commands5.kt:60` "lens", `MagnifierWindow`) |

## §42 Foxit-only features

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| Egnyte integration | MISSING | STILL MISSING | 0 hits. Only the system picker reaches Egnyte, if the Egnyte app is installed |
| AI-based redaction (Smart Redact) | PARTIAL | DONE | `PiiScanSheet.kt:60` → `core/ai6/AiPii.scan` (opt-in AI pass; NAME / ADDRESS kinds) merged with the regex hits. Needs an AI key or the proxy |
| Accounting Calculator | MISSING | DONE (spot-checked) | Comments menu `cx_calculator` (`ui/reader/annot/CommentExtrasUi.kt:203`) → `cx.calculator` → `CalculatorDialog` (`:304`) → `CalcTape` (tape edit, decimals, date, place on page) |
| Dynamic XFA support | PARTIAL | DONE | Forms tool: flatten generates the XFA layout as an AcroForm (`ui/forms/FormsScreen.kt:131-141`); "Open in reader" opens the in-page XFA layer (DONE_comments_forms) |
| Callas Preflight, 100+ profiles, PDF/X, PDF/E, PDF/VT | PARTIAL | STILL MISSING | `core/tools5/PdfA.kt` is still PDF/A-2b only. PDF/X is only shown in the Standards panel; there is no PDF/X conversion or preflight profiles (stage G is not merged) |
| 3D PDF | MISSING | NOT-FEASIBLE | No 3D renderer; a "contains 3D" notice is shown |
| Action Wizard + Watched Folder | PARTIAL | STILL MISSING | Folder and sub-folder sources are done (`SafFolders`, `BatchSources`). There is no watched folder that runs a recipe automatically (0 hits) |
| Full-text index catalog / Fast Find | PARTIAL | NOT-FEASIBLE | The .pdx catalog / embedded index format is proprietary (DONE_view_edit §12). The app index has stop words and excluded folders |
| TickMarks, cross-reference auto-links, Invoice Print | MISSING | DONE | TICKMARK tool; `core/edit5/AutoLinks.kt` + `CrossRefScreen`. Invoice Print is NOT-FEASIBLE (a Foxit plug-in) |
| Image Editor | MISSING | DONE | `ui/nve/imgedit/NveImageEditor.kt` (layers, filters, clone, healing) |
| Measure: calibration, snapping, export | PARTIAL | DONE | Snapping: `WorkbenchCanvas.resolve`, grid snap (DONE_comments_forms 7.14); CSV in `MeasureListSheet` |
| JS editor / console, page templates, barcode fields, Designer Assistant, static XFA editing | MISSING / PARTIAL | DONE | `ui/edit5/DocScriptsScreen.kt`; `PageTemplates` (go to / rename / replace); `core/formsx/FxBarcodes.kt`; `FieldRecognition` designer assistant; static XFA → AcroForm (DONE_comments_forms 26.77) |
| AIP / RMS / WIP / FileOpen / FIPS / FATL-EUTL | N/A / MISSING | DONE | EU trusted list (`TrustLists`; the XAdES signature is not verified). AATL is NOT-FEASIBLE (licensing); enterprise items are N/A |
| ECM connectors; ConnectedPDF; Portfolio; Binder | N/A / HAVE / MISSING | DONE | Portfolio editor (stage D) and binder; ECM is N/A |
| Reading utilities: loupe, reverse view, etc. | PARTIAL | DONE | Lens / MagnifierWindow; reverse view in PageView (DONE_view_edit VIEW-1/2/8) |
| AI Assistant / Smart Redact (mostly HAVE) | HAVE / PARTIAL | DONE | See §35 (DONE_ai_office) and the Smart Redact row above |

## STILL MISSING (by importance)

**Med**
- §40 Tags / Content / Order panels: the Content panel and the drag reading-order (Order) panel are missing; tags are done. Area: tools/OCR (accessibility).

**Low**
- §39 Ctrl+L / Ctrl+I / Ctrl+8 / Ctrl+Enter / Ctrl+Alt+V: no keys bound, although the features exist. Area: nav/settings.
- §39 Ctrl+X, Ctrl+Shift+A, and annotation copy / paste keys. Area: nav/settings.
- §39 Ctrl+Shift+C (cross-reference); Ctrl+B adds no bookmark. Area: nav/settings.
- §39 Ctrl+Alt+D (duplicate page). Area: nav/settings.
- §39 read-aloud keys B / P / Y. Area: nav/settings.
- §39 F10, Shift+F10, Ctrl+F11. Area: nav/settings.
- §39 Tab / Shift+Tab between form fields; Ctrl+J for the JS console. Area: comments/forms.
- §39 Ctrl+Shift+K (e-mail all tabs). Area: nav/settings.
- §41 Combine: thumbnail preview to delete / reorder pages before combining. Area: pages/convert.
- §41 AI image editing (`/images/edits`); generation needs the owner's proxy config. Area: AI/office.
- §41 Editing comments from other editors (Bluebeam etc.): not built or verified. Area: comments/forms.
- §41 CSV comment summary column choice. Area: comments/forms.
- §41 Sequential (auto-increment) stamp. Area: comments/forms.
- §41 Dropbox team folders. Area: files.
- §41 Pencil: hide cursor on stylus; pencil over text highlights. Area: comments/forms.
- §41 Preflight grayscale fix (only the separate `pdf_gray` tool exists). Area: pages/convert.
- §41 Workspace crafts: infographics and web pages. Area: AI/office.
- §41 Translation: Danish missing (images need vision). Area: AI/office.
- §41 Volume, polylength, diameter and radius measuring tools. Area: comments/forms (measure).
- §41 Page labels from bookmarks or page regions. Area: pages/convert.
- §41 Enterprise / shared stamps with edit lock. Area: comments/forms.
- §42 Egnyte. Area: files.
- §42 Callas-style preflight profiles, PDF/X / PDF/E / PDF/VT. Area: pages/convert.
- §42 Watched folder for the Action Wizard. Area: tools/OCR (batch).

## Counts (non-HAVE rows only; §38 has none)

| | §39 | §40 | §41 | §42 | Total |
|---|---|---|---|---|---|
| DONE | 11 | 14 | 24 | 11 | **60** |
| STILL MISSING | 9 | 1 | 13 | 3 | **26** |
| NOT-FEASIBLE | 0 | 1 | 5 | 2 | **8** |
| OWNER-ACTION | 0 | 0 | 4 | 0 | **4** |
| N/A-ANDROID | 0 | 0 | 0 | 0 | **0** |

Spot-checked (6): customise shortcuts, Signatures panel, tickmarks, XMP export, Action Inspector on open, accounting calculator.

---

# OLD_PLAN_AUDIT.md: every non-DONE item, checked against main (fa724fd / 8555d74)

Sources: DONE_ai_office, DONE_pdf3c_nav, DONE_pdf3c_protect_sign, DONE_view_edit, DONE_comments_forms, AUDIT/NITRO_TOOLS_LOG, AUDIT/PAGES_CONVERT_LOG, VIDEO_INLINE_r5. The item codes (A1, K2, PREF-5 and so on) were resolved with `D:\pdf\recovered-from-old-chat\acrobat_gap.md`, `desktop_gap.md` and `MODULE_view4.md`. Paths are under `app/src/main/java/com/smartdocs/editor/`.

**AI rows that need the proxy.** These rows work with the user's own OpenAI-compatible key, so they are marked DONE. The matching `ai-proxy` routes are written but not deployed; deploying them is owner work, noted in each row. The same rule is used in part 35-37. Part 38-42 instead marks four vision/image/eSign-form rows OWNER-ACTION.

## A. Office / sync / share / conn

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| NEEDS-SERVER/KEYS: no cloud.properties; collab/share/esign/connected workers not deployed; Drive OAuth + drive.appdata | NEEDS-SERVER | OWNER-ACTION | There is no `cloud.properties` in the repo; only `ads.properties.example` and `license.properties.example`. `core/cloud/OAuthPkce.kt:18`, `CollabClient.kt:14` and `ConnectedClient.kt:16` read BuildConfig. Needs SERVER_SETUP.md steps, the workers deployed and OAuth clients. |
| Office: no Print | MISSING | DONE (spot-checked) | ⋮ "Print" `ui/office/OfficeScreen.kt:207` → `printDoc()` :126 → `OfficePrint.print` (`Office6.kt:76`) → `ToPdf.convert` → `PdfPrint.print` |
| Office: no Read aloud | MISSING | DONE | `OfficeSpeaker` (`Office6.kt:94`), `OfficeReadAloudBar` shown at `OfficeScreen.kt:248` |
| Office: search only Word/text | PARTIAL | DONE | `SheetFindBar` (`SheetEditor.kt:52`) and `SlideFindBar` (`SlideEditor.kt:50`) → `core/office5/OfficeFind.kt`. A PowerPoint match that spans two runs is not found. |
| Sync: setForeground never called; manifest lacks the dataSync type | MISSING | DONE | `core/sync/SyncWorker.kt:80` and `CloudUploadWorker.kt:39` call `setForeground`. `AndroidManifest.xml:13` has FOREGROUND_SERVICE_DATA_SYNC; lines 457-458 have SystemForegroundService with `dataSync`. |
| Sync near-realtime (60 s poll, no FileObserver / long-poll) | PARTIAL | STILL MISSING | Cloud changes are still polled every 60 s (`SyncLive.REMOTE_POLL_MS = 60_000`). Local changes use a ContentObserver on the SAF trees. There is no long-poll or push. |
| Review poll-interval setting | MISSING | DONE | `ReviewLocal.interval/setInterval`; dialog in `ui/share5/TrackerExtras.kt:121` reschedules `ReviewCheckWorker` |
| Take over other reviewers' comments | MISSING | STILL MISSING | No code: grep for `takeOver`, `ownership`, `unpublished` returns 0. Part 34_28-33 (§31) agrees. |
| Office command registry | MISSING | DONE | `OfficeCommandRegistry` (`Office6.kt:202`, about 70 ids in `core/office5/OfficeCommands.kt`). It is used by the AI command dialog `OfficeAiDialog`, opened at `OfficeScreen.kt:251`. The ribbon buttons were not moved onto it. |
| Live reviewers' comments not drawn (optional) | MISSING | DONE | `CollabLiveHost` is called at `ui/reader/ReaderScreen.kt:118`. It polls every 30 s and shows "N new comments" with a "show" action, which merges the comments into the page (`CollabLive`). |

## B. Search / AI / forms / ads

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| AI B13 slides generation | MISSING | STILL MISSING | Built in part: `core/ai6/SlidesGen.kt` from `ui/ai6/TDocTools.kt:139` makes PPTX / PDF / MD. Missing: speaker notes in the PPTX, and an entry from Spaces. |
| AI B17 contract review / table to CSV | MISSING | DONE | `core/ai6/DocReview.kt` via `ui/ai6/TDocTools.kt` |
| AI-8 image generation | MISSING | STILL MISSING | Generation is built: route `ai_image` (`ui/tools/ToolScreens.kt:108`) → `ImageGenScreen`. Missing: prompt-editing an existing image (`/images/edits`), and the insert into a PDF is not a reader undo step. The proxy needs `IMAGE_*` set (owner). |
| AI-10 ask about an image / chart | MISSING | DONE | Hub attach (gallery, camera, page snapshot) in `ui/ai6/HubChatInput.kt` / `AiHubScreen.kt`. Needs a vision model; the proxy `VISION_MODEL` route is not deployed (owner). |
| AI-14 web search in chat | MISSING | DONE | A chat chip, then `core/ai6/AiMedia.kt` web search with a keyless DuckDuckGo fallback. The Brave route is not deployed (owner). |
| AI-6 skills | MISSING | DONE | Route `ai_skills` from `ui/ai/AiSettingsScreen.kt:101` and `ui/ai5/AiHubScreen.kt:166` → `ui/ai6/SkillsScreen.kt` |
| B10 AI edit via chat (highlight / redact / replace) | MISSING | DONE | `core/ai/DocCommands.kt:49,51` (`highlight_text`, `replace_text`, plus `redact_pattern`, `rewrite_text`) in the agent sheet, with confirm cards |
| AI-5 Home conversations list | MISSING | DONE | `ui/home/HomeScreen.kt:153` `HomeConversationsCard`; route `ai_chats` |
| AI-15 DOCX export of answers | MISSING | DONE | `ui/ai5/AiHubScreen.kt:68` → `ChatExport.writeDocx` |
| AI-19 Anki export | MISSING | DONE | `ui/ai6/HubAnki.kt:40` (`ChatExport.anki` / `csv`) |
| C1 read-aloud reading order, sentence highlight | MISSING | DONE | `core/tts/ReadAloudController.kt:153` uses `core/ai6/ReadingOrder.pieces`; `onRangeStart` at :200 drives the highlight. (The separate `NpfPrefs.readingOrder` preference is never read; see 34.27 in part 34_28-33.) |
| Embeddings re-rank | MISSING | DONE | `ui/ai5/CitedChatState.kt:117` → `core/ai6/ChatRerank.kt` `Reranker.rerank`. If no embeddings are available it falls back to BM25 without telling the user. The `EMBED` route is not deployed (owner). |
| Podcast editing (B14) | MISSING | DONE | `ui/voice/PodcastScreen.kt:264` → `PodcastScriptEditor` (`ui/voice/PodcastEditor.kt`), `core/voice/PodcastScript.kt` |
| B11 rewrite can't replace (onReplace = null) | PARTIAL | DONE | `ReaderScreen.kt:419` onReplace → `ReaderAi6.replaceSelection` (spot-checked in part 35-37). A selection spanning several text blocks is refused with a message. |
| B1 citations: no passage highlight | PARTIAL | DONE | `core/ai6/QuoteLocator.kt` → `ui/reader/ReaderAi6.kt` `highlightQuote` |
| Translate: no side-by-side | PARTIAL | DONE | `ui/ai5/AiToolsPanel.kt:91` → route `ai_side_by_side` → `ui/ai6/SideBySideScreen.kt` |
| Scanner: no AI buttons (B16) | PARTIAL | DONE | `ui/scanner/ScannerScreen.kt:139` bottom bar → `ScanAiBar` (`ui/scanner/ScanAi6.kt:41`) |
| Smart fill not in the reader form bar | PARTIAL | DONE | `ui/forms5/InPageFormBar.kt:209` → `ui/ai5/FormFillSheet` |
| Multi-file chat only via Spaces | PARTIAL | DONE | Hub "add files" (`AiHubScreen.kt:166` onAddFiles) |
| AI usage local only (AI-18) | PARTIAL | DONE | `ui/ai6/HubUsage.kt`: server counters, else the local count. The `/usage` route is not deployed (owner). |
| Voice: no barge-in (B6) | PARTIAL | DONE | `ui/ai5/VoiceUi.kt:105` `BargeInDetector` (`core/voice/BargeIn.kt`). The threshold has not been tuned on a device. |
| PII: regex only | PARTIAL | DONE | `ui/ai5/PiiScanSheet.kt:60` → `core/ai6/AiPii.scan`; offline names / organisations / roles in `core/ai5/PiiEntities.kt` |
| Search: Home "search all files" opened the Files tab | PARTIAL | DONE | `ui/home/HomeScreen.kt:126` → tool `search_all` |
| Search: indexSoon has no caller | CODE-ONLY | DONE | `core/cloud/CloudLinks.kt:167` and `core/files/DocumentRepository.kt:287` call `IndexWorker.indexSoon` |
| Search: searchScopes code-only | CODE-ONLY | DONE | `ui/reader/ReaderOverlays.kt:63` → `ui/search/NpfScopeHits.kt` → `TextSearch.searchScopes` |
| Search: hit colour missing | MISSING | DONE | `ui/nve/search/NveSearch.kt:62` `NveHitStyle`, settings `NveHitStyleSettings`; NpfPrefs hit colours |
| Search: search in attachments | MISSING | DONE | `core/search/DocParts.kt:39,71` (attachment contents), shown through NpfScopeHits |
| Redaction: code-set import/export + .txt word-list picker | MISSING | DONE | `ui/security/RedactStyleSheet.kt:134-137` (`importCodeSets` / `exportCodeSets`); word list import/export at `ui/security/RedactPanes.kt:49-50` (spot-checked in part 34_28-33) |
| Redaction: auto sanitize on share | MISSING | DONE | `ui/share5/ShareOptionsSheet.kt:91-105` → `core/share5/ShareStrip` items passed to `ShareBuilder.build` |
| Forms tool says "impossible" for dynamic XFA | PARTIAL | DONE | `ui/forms/FormsScreen.kt:133-144`: flatten generates an AcroForm (`XfaGenerated.generate`) and flattens it. Filling is in the reader's in-page XFA layer. |
| XFA: remove repeating row | MISSING | DONE | `ui/forms5/InPageFormLayer.kt:187` → `FormFillState.removeRow` → `XfaGenerated.removeRow` |
| XFA: picture clauses / scripts | MISSING | STILL MISSING | `core/forms/XfaTemplate.kt:291` skips `<picture>` (values show raw). Only `initialize` and `calculate` scripts are read (:239-240); no other XFA events run. |
| Ads: banner in ResultSheet | MISSING | STILL MISSING | `AdBanner` is shown only on HOME / TOOLS (`ui/Navigation.kt:91`); `ResultSheet` (`ui/common/Components.kt:192`) has none |
| Ads / licence off (no properties) | CONFIG | OWNER-ACTION | Only `ads.properties.example` and `license.properties.example` exist; the owner must supply the real files |

## C. Edit / view / tools, items 1-20

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| 1 Edit5 tools reachable only from the Tools card; old outline/watermark routes open old screens | PARTIAL | DONE | Reader `NveEdit5Sheet` (`ui/nve/NveHost.kt:60,147`), Edit-tab chip, command search; `ToolScreens.kt:46` watermark → `e5_watermark`, :96 outline → `e5_bookmarks` |
| 2 Spell check missing everywhere | MISSING | DONE | `ui/nve/Spell.kt` (SpellCheckerSession); comment / note / form dialogs and document Check Spelling (DONE_view_edit) |
| 3 Rich text in edit mode basic | PARTIAL | DONE | `core/edit/RichText.kt`, `ui/edit/TextTools5.kt`; font picker `core/edit/EditFonts.kt` |
| 4 Image edit: flip / crop / opacity / z-order / any angle / editor / ACTION_EDIT | MISSING | DONE | DONE_view_edit fork B (CropDialog5, RotateDialog5, ACTION_EDIT) and fork E `ui/nve/imgedit/NveImageEditor.kt` |
| 5 Organize: odd/even/range, swap, flatten range, clipboard insert | MISSING | DONE | `ui/edit5/PageOrganizeScreen.kt:42` OrgOp SWAP / FLATTEN (`PageOrganize.flattenPages` :207) / INSERT sources (clipboard), reached from `e5_organize` (`Edit5Hub.kt:79`) |
| 6 Comments: hide, copy/paste, align/distribute, properties, ink → shapes | MISSING | DONE | Verified in part 24-27 (24.71, 24.77, 24.85, 24.68); ink → shape `ui/reader/PageGestures.kt:95` `ShapeSnap.classify` |
| 7 View: zoom presets / marquee, word count, continuous facing | MISSING | DONE | `ui/nve/NveHost.kt:93` marquee chip, :98 continuous facing, `NveWordCountDialog` (:49) |
| 8a Region OCR; openOcrForPage has no caller | MISSING / CODE-ONLY | DONE | Tool `ocr_zone` (`ui/ocr/OcrToolsScreens.kt:67`, button in `OcrSheet.kt:120`); reader menu `ReaderScreen.kt:261` "OCR this page" → `QuickController.openOcrForPage` (:83) |
| 8b Region export (selected area to Word / Excel) | MISSING | STILL MISSING | PAGES_CONVERT_LOG stage C lists "Export a selected area as Word / Excel" as OPEN. Only an area snapshot (image) and OCR-zone copy exist. |
| 9 Bookmarks: export/import, search, sections, add from selection | MISSING | DONE | `core/edit5/OutlineNav.kt` (`write` / `read` XML :109/:147, `search` :37, `sectionPages`); `ui/edit5/ReaderNav.kt:51` `insertForPage` from the selection; `BookmarksScreen.kt:123-159` |
| 10 Page labels not shown; initial view / R2L / Lang not honoured | CODE-ONLY / MISSING | DONE (spot-checked) | `ReaderScreen.kt:67` `rememberPageLabels` → `PageBoxOps.readLabels` → `PageIndicator(labels)` :338 and `GoToPageDialog` :384. `ReaderState.kt:254` → `applyInitialView` :303: R2L always applied; layout, open page and zoom only on first open and only when `NpfPrefs.docViewFirstOpen` is on |
| 11a Scan: re-edit, eraser, deskew | MISSING | DONE | `ScanProjects` "Saved scans" (`ui/tools5/ScanStudioScreen.kt:97,193`); eraser / despeckle `ui/tools5/ScanParts.kt:89`. Deskew is in OCR pre-processing (`core/ocr/OcrPrep.skewAngle/straighten`, the "Correct page skew" option), not in the scan editor. |
| 11b Scan: curvature (dewarp) | MISSING | NOT-FEASIBLE | PAGES_CONVERT_LOG K7: needs a learned dewarping model; ML Kit only crops and corrects perspective |
| 12 No custom blank size, no template search | MISSING | STILL MISSING | `ui/tools/CreateTools.kt:49` has preset page sizes only. Insert-blank uses `PageBoxOps.PAPERS` (`TemplatesBlankScreens.kt:156`). `ui/templates/TemplatesScreen.kt` has no search field. |
| 13 Shell/settings: more settings, offline, recent size, tab menu, crash recovery, accent colours | MISSING | DONE | `npf_settings` (`ToolScreens.kt:103`) → `NpfSettingsScreen`; accent `NpfSettingsScreen.kt:261`; offline `core/data/NpfNet` (CloudHttp interceptor); recovery `ui/nve/shell/Recovery.kt`; tab menu `NveTabs` |
| 14 Print: greyscale / scale / comment summary, multi-file, poster | MISSING | DONE | `ui/view5/PrintForm.kt` (poster :47, modes); `batch_print` (`ui/tools/MiscTools.kt:59`) → BatchPrintScreen (spot-checked in part 35-37) |
| 15 Files: paste / snapshot, save-as hub, optimizer presets/batch, fonts tab | MISSING | DONE | `NveSaveAsSheet` (`ui/nve/NveExternal.kt:24`); optimizer profile bar `OptimizeExportScreens.kt:100`; `NveFontsPanel` (`DocPropsScreen.kt:102`); Home paste / page snapshot (DONE_view_edit G) |
| 16a Convert: web capture options, combine TOC/binders, TIFF/BMP, XML export | MISSING | DONE | `WebPdfWriter.Setup` / `WebOptionsCard`, `CombinePlusUi.kt` (TOC, binder), `pdf_tiff`, `pdf_bmp`, `pdf_xml` (PAGES_CONVERT_LOG B / C) |
| 16b Convert: batch from Files multi-select | MISSING | STILL MISSING | The Files selection bar (`ui/files/FilesScreen.kt:185-194`) has share, zip, move, copy, delete and merge only. There is no convert action for the selected files. |
| 17 Attachments reader tab, layers, media /Screen, portfolio offer | MISSING / CODE-ONLY | DONE | `ui/attachments/ReaderAttachments.kt`; Layers `e5_layers` (reader INFO link `NpfPanels.kt:135`); in-page video for our own and other producers' Screen / RichMedia (VIDEO_INLINE_r5); portfolio banner → `portfolio` tool |
| 18a Accessibility: auto-tag basic; AI alt text never sees the image; no tags panel | PARTIAL / CODE-ONLY | DONE | `AccessAutoTag.tag` (headings, lists, re-tag), `AltTextVision.install` → `T5Hooks.describeImage`, `TagsEditor` (spot-checked in part 34_28-33) |
| 18b Accessibility: reading-order panel | MISSING | STILL MISSING | No Order panel and no area / touch-up reading order; only move / indent in the tags tree (PAGES_CONVERT_LOG F "OPEN"; part 34_28-33 34.44) |
| 19a Compare formatting | MISSING | DONE | `ui/tools5/Compare2Screen.kt:203` FormatChange filter (NITRO_TOOLS_LOG §15) |
| 19b Preflight profiles; PDF/A 2b only; PDF/X | MISSING | STILL MISSING | `ui/tools5/PdfAScreen.kt:62` is PDF/A-2b only (`IccSrgb.kt:113` part = 2, conformance = B). There are no preflight profiles and no PDF/A-1b/3b or PDF/X output (stage G was not merged). |
| 20a Niche: named destinations, text-block join/split, shapes/vector, transitions writer | MISSING | DONE | `DestinationsScreen`, edit-mode join/split (DONE_view_edit A), `NveShapes`, `NvePresentation` /Trans writer |
| 20b Niche: reflow editing | MISSING | STILL MISSING | Built in part (DONE_view_edit): one page at a time as flowing text. Per-block formatting and columns are lost, and there is no reflow across pages. |

## D. Ranked non-DONE list (Acrobat / desktop gap codes)

| Section / row | Old status | Now | Evidence or what is missing |
|---|---|---|---|
| A6 / FILE-2 / FILE-3 reader ignores /Direction, OpenAction, PageLayout | MISSING | DONE | `ReaderState.applyInitialView` (see item 10) |
| H1 auto form-field detection | MISSING | DONE | `core/formsx/FieldRecognition.kt` in the form designer (spot-checked in part 24-27, 26.18) |
| I1 smart Fill & Sign on flat forms (detected tap targets, suggestions) | MISSING | STILL MISSING | Field recognition runs only in the designer (`ui/formsx/DesignerState.kt:227`). The Fill & Sign tools (`core/fillsign2`, `ui/reader/annot`) do not detect boxes or lines and offer no tap targets. |
| C1 read-aloud order, field reading, sentence highlight | PARTIAL | DONE | See C1 above. Field reading: values are read with the page; there is no Tab-through-fields mode. |
| B7 / PREF-8 AI-off leaks | PARTIAL | DONE (spot-checked) | `ToolScreens.kt:31` `AiGate.allowsTool` → `NpfAiOffScreen`; `ToolsScreen.kt:134` `AiGate.filter`; `G6Prefs.kt:13` covers all ai6 route ids |
| B11 rewrite onReplace = null | PARTIAL | DONE | See B above |
| B1 citation highlight | PARTIAL | DONE | See B above |
| K2 re-OCR skips text pages (OcrRunner) | PARTIAL | STILL MISSING | `core/tools5/OcrRunner.kt:101` / `MixedPageOcr.plan`: pages that already have a text layer over the scan are skipped, and on mixed pages only new words are kept. There is no "redo OCR / replace the existing text layer" option (for example, to re-run with another language). |
| K3 edit a saved scan | MISSING | DONE | `ScanProjects` (item 11a) |
| K12 OCR pre-processing (deskew, despeckle, background, languages) | PARTIAL | DONE | `OcrPrep`, `OcrTuning`, `core/ocr/OcrModels.kt` (NITRO_TOOLS_LOG §13); scan despeckle `ScanClean.despeckle` |
| E6 / E7 / E8 edit quality (rich text, fonts, image ops) | PARTIAL | DONE | See items 3 and 4 |
| B10 AI chat edit | MISSING | DONE | See B10 above |
| M5 TalkBack: about 35 null contentDescriptions | PARTIAL | STILL MISSING | Stage F added 41 `pc_cd_*` labels. A grep still finds about 51 places where an `IconButton` holds `Icon(…, null)` within 3 lines, for example `ui/edit5/BookmarksScreen.kt:150-151`, `ui/edit5/BatesScreen.kt:81-82` and `ui/cloud/CloudBrowserScreen.kt:131`. |
| B16 scan AI | PARTIAL | DONE | `ScanAiBar` |
| I3 no system CA / revocation / signed-version view / banner | MISSING | DONE | `SystemTrust` through `CertInspector.inspect(system = …)` (`DigitalSignScreen.kt:494`); `core/sign/Revocation`; `SigRevisions.extract` (`SigFieldActions.kt:200`, `NvePanes.kt:229`); `SigFieldSheet` SignedBar (`ReaderScreen.kt:370`) |
| E3 / E4 no PDF source for watermark; Tools watermark tile opens the old screen | PARTIAL | DONE | `DecorSource.PDF` (`ui/edit5/DecorScreen.kt:65`); `ToolScreens.kt:46` |
| H2 field properties (comb, format/validate/calc authoring, align, tab order) | PARTIAL | DONE | Form designer (part 24-27, 26.37-26.72) |
| H3 autocomplete settings | MISSING | DONE | Part 24-27, 26.84 |
| H4 form data only JSON | PARTIAL | DONE | `core/formsx/FormData.kt` (part 24-27, 26.79) |
| Remember settings per tool | MISSING | DONE | `ui/reader/annot/CommentPrefs.kt:14` `ToolMemory`, used by `Annot2Host.kt:56` |
| D4 basic line tool, no dash | PARTIAL | DONE | Part 24-27, 24.108 |
| A5 page labels | CODE-ONLY | DONE | Item 10 |
| PREF-2 direction / digits default | PARTIAL | DONE | Layout direction `ui/settings/Gaps6SettingsSection.kt:68`; Arabic-Indic digits when typing (`NpfText`, NVE `NveDigits`) |
| SHL-4 full selection bar | PARTIAL | DONE | `NveSelectionChips` (DONE_view_edit) |
| SHL-10 crash recovery / Revert | MISSING | DONE | `ui/nve/shell/Recovery.kt` `NveRecovery`; ⋮ Revert to saved |
| FILE-10 save-as hub | MISSING | DONE | `NveSaveAsSheet` |
| A1 reflow with tables | PARTIAL | STILL MISSING | `core/view5/ReflowBlocks.kt:13-15` has only Heading, Para and Image blocks; tables are not rebuilt |
| F4 combine with page picking | PARTIAL | STILL MISSING | Combine takes a page range per file (`CombineEntry.pages`). There are no page thumbnails to pick or reorder pages across files (part 38-42 §41 agrees). |
| G5 / FILE-7 CCITT / presets | PARTIAL | DONE | `core/tools5/Ccitt.kt`, `Jbig2.kt`, optimizer presets (NITRO_TOOLS_LOG §14 / §20) |
| FILE-9 MRC | MISSING | DONE | `OptimizeScan.mrc` |
| B9 Spaces URL / text sources, sharing | PARTIAL | DONE | `ui/ai5/SpacesScreen.kt:159` `SpaceSources.write`; `.sdspace` export |
| B14 podcast edit | MISSING | DONE | See above |
| B6 barge-in | PARTIAL | DONE | See above |
| D2 snap ink to shape | MISSING | DONE | `PageGestures.kt:95` |
| PREF-7 offline mode | PARTIAL | DONE | `core/data/NpfNet` interceptor (`core/cloud/CloudHttp.kt:31`), `CloudUploadWorker.kt:30` |
| SHL-5 parallel view of two documents | PARTIAL | STILL MISSING | There are quad split (`NveQuadSplit`), split compare and a second window (`NveWindowActivity`). Tab groups are missing, and so is editing both documents side by side in one window (DONE_view_edit "Parallel view / tab groups PARTIAL"). |
| Audio transcription | MISSING | DONE | Route `ai_transcribe` (`ToolScreens.kt:109`) → `TranscribeScreen`. Needs an STT-capable key or the proxy `STT_*` route (owner). |
| B13 slides | MISSING | STILL MISSING | Same as B13 above |
| K13 OCR one page from the reader | PARTIAL | DONE | `ReaderScreen.kt:261` → `openOcrForPage` |
| FILE-4 fonts screen | CODE-ONLY | DONE | `NveFontsPanel` |
| PREF-1 settings search | MISSING | DONE | `NpfSettingsScreen` search |
| PREF-3 / PREF-4 / PREF-6 identity; markup-text copy, retain tools, note font; default layout / zoom / panel | PARTIAL | DONE | `NpfPrefs` keys `COPY_SEL`, `RETAIN_TOOLS`, `NOTE_FONT` (`core/data/NpfPrefs.kt:46-47`); identity org / unit / e-mail; `NpfReader` default layout / zoom / panel (DONE_pdf3c_nav) |
| PREF-5 doc title, PDF/A read-only, redaction suffix, recovery interval | PARTIAL | STILL MISSING | Built: document title, redaction prefix and suffix, recovery interval. Missing: PDF/A view mode or read-only preference (part 35-37 §37 agrees). |
| F5 clipboard insert | PARTIAL | DONE | Organize INSERT from clipboard (`Cv6Engine clipboard_pdf`) |
| F6 selection helpers | PARTIAL | DONE | Organize odd / even / range (PAGES_CONVERT_LOG A) |
| F8 /Trans page transitions | MISSING | DONE | `NvePresentation`, `core/nve/PageTransitions.kt` |
| G3 TIFF export | MISSING | DONE | `pdf_tiff` → `TiffWriter` |
| G8 printer marks, page boxes, hairline fix | MISSING | STILL MISSING | Page boxes are shown and edited in Crop (`BoxTools.pageBoxes`). There are no trim / bleed / registration marks (cut marks exist only for poster tiles) and no hairline fix. |
| M2 autotag + reading order | PARTIAL | DONE | `AccessAutoTag`; the reading-order panel is 18b |
| H6 distribute / collect forms | MISSING | DONE | Part 24-27, 26.81 |
| I4 FieldMDP lock after signing | MISSING | DONE | `DigitalSignScreen.kt:345` lock after signing → `SigFields.applyLock`; form side `FxSigLocks` |
| I5 KeyChain signing | MISSING | DONE | `ui/signature/CertificateTab.kt:203` `DigitalIds.chooseKeyChain`. Cloud (CSC) signing is not built. |
| K7 curvature | MISSING | NOT-FEASIBLE | Item 11b |
| K8 eraser | PARTIAL | DONE | `ScanClean.erase` |
| K11 QR scanning | MISSING | STILL MISSING | Nothing in `ui/scanner`. zxing is now a dependency (BarcodeEncoder) but is used only for encoding. |
| D6 /RC rich text | MISSING | DONE | Part 24-27, 24.101 |
| D7 spell check | PARTIAL | DONE | Item 2 |
| E9 vector shapes | MISSING | DONE | `core/nve/shapes/NveShapes.kt`, `ui/nve/shapes/ShapeEditor.kt` |
| E10 rotated / XObject text editing | PARTIAL | STILL MISSING | DONE_view_edit: any-angle and first-level Form XObject text work. Nested forms, mirrored or skewed text, and kerning inside forms do not. |
| A4 layers | MISSING | DONE | `LayersScreen` (`e5_layers`) |
| A7 media annotations from other producers | PARTIAL | DONE | Inline playback of foreign Screen / RichMedia (VIDEO_INLINE_r5 "Foreign Screen + Rendition", "Foreign RichMedia"); RichMedia is read-only |
| A8 portfolio on open | CODE-ONLY | DONE | `NveDocBanners` PORTFOLIO → `portfolio` → `PortfolioEditor` |
| B15 video summary | MISSING | STILL MISSING | `core/ai6/VideoSummary.kt` from `TDocTools.kt:114` is built in part: it narrates with system TTS only (no AI voices), and the device encoders are untested (DONE_ai_office PARTIAL) |
| B17 contract review | MISSING | DONE | See above |
| G9 visual content (Adobe Express-like) | MISSING | NOT-FEASIBLE | MODULE_tools4 G9: a third-party design-service integration, out of scope |
| SHL-3 / SHL-6 / SHL-7 / SHL-12 / SHL-13 | MISSING | DONE | Customise toolbars (`NveToolbarCustomizeSheet`), second window (`NveWindowActivity`, manifest :444), tab menu, Ctrl+wheel / right-click, accent |
| FILE-1 XMP import / export | PARTIAL | DONE | `XmpOps.exportFile` (spot-checked in part 38-42) |
| FILE-5 print presets not read | PARTIAL | DONE | `core/view5/PrintSettings.kt` presets + /ViewerPreferences |
| PREF-9 recent list size | PARTIAL | DONE | `NpfReaderHooks` recent size (DONE_pdf3c_nav) |
| Invite deep link (gaps6 L1) | OPTIONAL | STILL MISSING | `core/gaps6/Referral.kt:14` builds `https://smartdocs.app/invite`, but the manifest has no intent-filter for it. Domain verification is also needed (owner). |
| Tabletop split (gaps6 R4) | OPTIONAL | STILL MISSING | `TabletopSplit` (`ui/gaps6/ReaderHooks.kt:100`) is never called from `ReaderScreen` (code-only) |
| Image generation | MISSING | STILL MISSING | Same as AI-8 |
| Overlay magnifier | MISSING | DONE | `ui/reader/PageView.kt:416` `MagnifierWindow`; "magnifier" view chip |

### Spot-checks in this part (button → handler → core)
1. Office Print: `OfficeScreen.kt:207` menu item → `printDoc()` (:126) → `OfficePrint.print` (`Office6.kt:76`) → `ToPdf.convert` → `PdfPrint.print`.
2. Page labels: `ReaderScreen.kt:67` `rememberPageLabels` (`ui/edit5/ReaderExtras.kt:15`) → `PageBoxOps.readLabels` → `PageLabelFormat.labels` → `PageIndicator(labels)` (:338) and `GoToPageDialog` (:384).
3. AI-off gate: every tool route goes through `ToolScreens.kt:31` `AiGate.allowsTool` → `NpfAiOffScreen`. `G6Prefs.kt:13` lists `ai_side_by_side`, `ai_image`, `ai_transcribe`, `ai_chats`, `ai_skills` and `ai_help`.

### STILL MISSING (old plan), by importance
High
- I1 smart Fill & Sign on flat forms: no detected tap targets in Fill & Sign (comments/forms).
- K2 re-OCR: no "redo OCR / replace text layer" for pages that already have text (tools/OCR).

Med
- M5 TalkBack: about 51 icon-only buttons still have `contentDescription = null` (all areas; nav/settings).
- A1 reflow does not rebuild tables (reader/view).
- F4 combine: no thumbnail page picking or reordering across files (pages/convert).
- 19b preflight profiles, PDF/A-1b/3b and PDF/X (pages/convert).
- 18b reading-order (Order) panel and area reading order (tools/OCR, accessibility).
- XFA picture clauses, and scripts other than initialize/calculate (comments/forms).
- Sync near-realtime: 60 s cloud poll, no long-poll or push (files).

Low
- B13 slides: no speaker notes in the PPTX, no Spaces entry (AI/office).
- AI-8 image generation: no prompt edit of an existing image; the insert is not an undo step (AI/office).
- B15 video summary: system TTS only, untested on devices (AI/office).
- 8b export a selected area to Word / Excel (pages/convert).
- 12 custom blank page size; template search (pages/convert).
- 16b batch convert from the Files multi-select (files).
- 20b / E10 reflow editing across pages; nested, mirrored or skewed XObject text (edit).
- SHL-5 tab groups, two editable documents side by side (reader/view).
- PREF-5 PDF/A view mode (reader/view).
- G8 printer marks, hairline fix (pages/convert).
- K11 QR scanning in the scanner (tools/OCR).
- Take over other reviewers' comments (files, share/review).
- Ads banner in ResultSheet (nav/settings).
- Invite deep link intent-filter (nav/settings); tabletop split not wired (reader/view).

### Counts (old plan, 143 rows; some ranked-list rows repeat an item from A–C, e.g. B13, AI-8, C1)
| Status | A | B | C | D | Total |
|---|---|---|---|---|---|
| DONE | 7 | 29 | 19 | 56 | 111 |
| STILL MISSING | 2 | 4 | 6 | 15 | 27 |
| NOT-FEASIBLE | 0 | 0 | 1 | 2 | 3 |
| OWNER-ACTION | 1 | 1 | 0 | 0 | 2 |
| N/A | 0 | 0 | 0 | 0 | 0 |


## Status update — fix/fc-sign (pdf-3c)

| Row (part / section) | Now | How / files |
|---|---|---|
| §28 Certificate protection saved as a policy | DONE | `core/sign/CertPolicies.kt` (recipients' certificates + permissions + cipher + metadata, JSON); `ui/security/CertPolicyUi.kt` in the certificate-protect tool (save / apply / delete); test `CertPoliciesTest` |
| §37 Prompt to trust untrusted signers on open | DONE | the signed bar offers "Trust <signer>" for the first valid, untrusted signature (`SigFieldActions.kt` SignedBar, CertInspector); switch in Verify options (`SignPrefs.promptTrust`) |
| §37 "Use expired timestamps" | DONE | `DigitalSignature.verify(acceptExpiredTimestamps)`: a time-stamp whose TSA certificate expired counts only when on (`SignPrefs.acceptExpiredTimestamps`, default on) |
| §37 "Ignore document validation info" | DONE | `verify(useDocumentValidationInfo = false)` ignores the embedded DSS for revocation (`SignPrefs.useDocumentValidationInfo`) |
| §37 "Ask if verification info is too big" | DONE | `SignOptions.ltvAskAboveBytes`: when the DSS adds more than the limit (chips 256 KB–4 MB / never), a copy without it is kept and the result asks Keep / Remove (`SignReport.ltvBytes / withoutLtv`) |
| 26.68 Signing / verifying dynamic-XFA XML signatures (XAdES) | NOT-FEASIBLE | Android has no XML Digital Signature API (`javax.xml.crypto` is absent) and no bundled XAdES library; PDF signatures over XFA forms are done with the normal PDF signature |
| §15 Link destination "Set this position" | DONE | link editor "Use current view" (page + zoom from the reader) — `ui/edit5/LinkEditor.kt` `currentView`, passed by `ReaderLinks` |
| §16 Bookmark action: named destination picked from a list | DONE | `LocalDestNames` chips in `NavUi.ActionDialog` (GoTo), provided by `BookmarksScreen` and the link editor's action lists; link "named" kind also picks from the list |
| §15 Link menu "convert the linked page to PDF" | DONE | link preview "Open as PDF" (`ui/nve/NveLinks.kt`, `ToPdf.url`, opens the result in the reader) |
| 34.27 reading-order preference | DONE (correction) | `NpfPrefs.readingOrder` is read by `ReaderAi6.orderedPage`; the per-document option stays STILL MISSING |

## Status update — fix/fc-settings
Every STILL MISSING row of area nav/settings (parts A–D), plus the merge of the duplicate recent-list size. Each row was re-checked on main 7c30b70 before it was built. Paths are under `app/src/main/java/com/smartdocs/editor/`. There was no device test (see "needs device test" in the branch report).

| row (part/section) | now | how / files |
|---|---|---|
| B §23 Conversion defaults in Settings (Med) | DONE | `core/convert6/Cv6Defaults.kt` keeps one JSON object per conversion id (prefs "fcs_conv_defaults"). The page range, URL and text are never saved. The hub starts from it (`ConvertHubScreen.open` → `Cv6Defaults.load(…, defaultsFor(c))`). The options sheet has "Save as default" / "Forget saved default" (`Cv6OptionsSheet.kt`). To PDF+ saves its bookmarks, attach-source, background and web-layout options (`ToPdfPlusScreen.kt`, key `screen:to_pdf_plus`). Settings → More preferences → Tools lists the saved defaults, with forget one / forget all (`ui/settings/FcsSettings.kt` `FcsConvDefaultsRows`) |
| D §37 Languages: follow the system (Med) | DONE | `core/data/AppLang.kt`: the Settings row offers System / العربية / English (`SettingsScreen.kt`). Android 13+ uses the per-app language (`LocaleManager`, `res/xml/locales_config.xml`, `android:localeConfig`), so the system "App languages" screen works too and `MainActivity.syncLanguage` reads it back. Android 12 and older use `MainActivity.attachBaseContext` → `AppLang.wrap` (prefs mirror "fcs_lang") plus a recreate. The old "lang" key always held "ar" (its default was written with every change), so `AppLang.migrate` keeps only an explicit "en". The code that asked `settings.language == "ar"` now calls `AppLang.isArabic` (LayoutDir, Compare, Accessibility report, Side-by-side, handwriting). Before this, the setting changed only the layout direction, never the strings |
| D §37 Languages: more than Arabic / English | NOT-FEASIBLE | It needs human translations of every string file (about 10 000 strings). The plumbing (`AppLang`, `locales_config.xml`) takes a new `values-xx` folder with no code change |
| C §1 QAT: position of the pinned commands | DONE | Top bar or bottom row: `V5Commands.qatPosition` / `setQatPosition` (prefs "view5"), chips in the Tell-me sheet and in More preferences → General. `V5PinnedBottomRow` is hooked into the reader's bottom bar (`ReaderScreen.kt`, one line). `ui/view5/Commands5.kt` |
| D §33 Disable-ConnectedPDF preference | DONE | `core/data/FcsPrefs.connectedOff`; switch in More preferences → General. When it is off, `ConnectedConfig.enabled` is false and `ConnectedDocs.checkOnOpen` returns null. The reader makes no status call, and a protected connected file falls back to the normal password prompt (`ReaderState.kt`) |
| D §34.26 Accessibility Setup Assistant | DONE | 4-step wizard (contrast colours, reading order, page layout / zoom, read-aloud speed / keep screen on). It writes the existing `NpfPrefs` / `AppSettings` values only on Finish. Entry: More preferences → Accessibility (`FcsSettings.kt` `FcsA11yAssistant`) |
| D §36 / OLD G8 Bleed / crop / registration marks; hairline fix | DONE | Crop marks were already there for single-page printing. Added registration and bleed marks with a bleed size (`core/view5/PrinterMarks.kt`, `PrintSettings`, `PrintPrep`, print sheet `PrintForm.kt`). Hairline fix: line widths in the page content and its form XObjects are raised to 0.25 / 0.5 / 1 pt. ExtGState /LW and nested forms are not changed. Test `PrinterMarksTest` |
| D §37 Per-message notification settings gear | DONE | More preferences → Notifications lists each notification channel of the app (review, sync, batch, print, read aloud), with a gear that opens the system settings for that channel (`FcsNotificationChannels`) |
| D §37 Startup: debug mode | DONE | `core/util/DebugLog.kt` + switch in More preferences → General. While it is on, log lines and uncaught crashes go to `files/debug_log.txt` (capped at 512 KB). "Share log" exports the header, that log and the app's own logcat; "Clear log" deletes it. Hooks: `SmartDocsApp`, `CrashLog` |
| D §37 Index preferences: XMP, custom properties, structure tags, logging | DONE | `core/search/FcsIndexPrefs.kt` (prefs "fcs_index"), `PartKind.METADATA`, `IndexWorker`, index log `files/index_log.txt` (view / clear). UI `ui/nve/search/FcsIndexPrefsUi.kt` in the search section |
| D §37 Search: ignore Asian character width | DONE | Full-width / half-width folding in `ArabicNormalizer` / `IndexText` (the index always folds width). The user switch is in `SearchPrefs`, in Settings and in the search options panel (`SearchUiCommon.kt`) |
| D §37 Search: document title in results | DONE | `search_index.db` v2 adds `docs.title` (the upgrade keeps the indexed content). `GlobalSearchScreen` shows the title under the file name |
| D §37 Search: Fast Find cache size limit | DONE | Limit choice none / 50 / 100 / 250 / 500 MB. `FullTextIndex.evictToLimit` drops the text of the oldest indexed documents after each index run. Test `FcsIndexPrefsTest` |
| D §39 Ctrl+L / Ctrl+I / Ctrl+8 / Ctrl+Shift+8 / Ctrl+Enter / Ctrl+Alt+V | DONE | `ui/nve/NveKeys.kt`: EXPORT_COMMENTS is on **Ctrl+Alt+L**, because Ctrl+L stays presentation / Foxit-layout comment manager (it can be rebound in the keys sheet). Also EXPORT_HIGHLIGHTS, HIDE_COMMENTS / SHOW_COMMENTS, REPLY (expands the selected comment's card, `CommentsPanel.kt` hook) and PASTE_IN_PLACE |
| D §39 Ctrl+X, Ctrl+Shift+A, annotation copy / paste | DONE | CUT (annotations), DESELECT. Ctrl+C with no text copies the selected annotation, and Ctrl+V pastes it after an annotation copy (`NveKeys.annotClipNewer`) |
| D §39 Ctrl+Shift+C cross-reference; Ctrl+B adds a bookmark | DONE | CROSS_REF in the Foxit layout only (in the default layout Ctrl+Shift+C stays the comment manager, `Keys5.kt:50`). Ctrl+B now adds a bookmark for the current page |
| D §39 Ctrl+Alt+D duplicate page | DONE | DUPLICATE_PAGE → `PageOps5.duplicate` through `applyInPlace` (one undo step, restrictions checked) |
| D §39 read-aloud keys B / P / Y | DONE | Ctrl+Shift+B reads to the end, Ctrl+Shift+P pauses or resumes, Ctrl+Shift+Y turns read aloud on or off (Acrobat meanings) |
| D §39 F10 / Shift+F10 / Ctrl+F11 | DONE | F10 opens the command search (MENU_BAR). Shift+F10 was already on main (`ui/selmenu/SelMenuState.kt:125`). Ctrl+F11 hides or shows the toolbars (TOOLBARS, `ReaderScreen.kt` hook). Test `NveKeyDefaultsTest` |
| D §39 Ctrl+Shift+K e-mail all tabs | DONE | EMAIL_TABS → `emailOpenTabs` (`ShareOptionsSheet.kt`, now internal). With one tab it falls back to Share. (Also done here, from a comments/forms row: Ctrl+J opens the document JavaScript console) |
| D OLD M5 icon-only buttons without `contentDescription` | DONE (on main) | Main fixed it first (fix/pages-a11y 846774f: "last 16 unlabeled icon buttons", which also covers the cloud and signature buttons this branch could not touch). In the merge with main, main's version of the shared files was kept |
| D OLD Ads banner in ResultSheet | DONE | `ui/common/Components.kt` `ResultSheet` shows the existing `AdBanner` (no ads without consent, for Pro users or with ads off) |
| D OLD Invite deep link intent-filter | DONE (+ OWNER-ACTION) | One `<intent-filter android:autoVerify="true">` for `https://smartdocs.app/invite…` on MainActivity (the only manifest change besides `android:localeConfig`). `MainActivity.handleIntent` → `Referral.isInviteLink` → `ReferralStore.redeem` → invite screen. The owner must publish `/.well-known/assetlinks.json` on smartdocs.app for verification |
| Duplicate recent-list size (`NpfPrefs.RECENT_LIMIT` / `NveFolders.recentSize`) | MERGED | One setting, `NpfPrefs.recentLimit` (0 = all). Both rows (Settings → General shell rows and More preferences → Files) edit it. `NveFolders.recentSize` was removed, and an old "recent_size" value is migrated once in `NpfPrefs.init`. HomeScreen applies it once |

Tests: `app/src/test/java/com/smartdocs/editor/core/data/FcSettingsTest.kt` (language normalise / migrate / resolve, conversion-default JSON round trip, recent-size merge, invite links, debug-log trimming), plus `PrinterMarksTest`, `FcsIndexPrefsTest` and `NveKeyDefaultsTest`.
## Status update — fix/fc-sync

Scope: every STILL MISSING row of the areas **files** (including files/search and files: share/review) and **share / cloud**, re-checked on main 7c30b70. Nothing here was tested on a device.

What changed in sync: only how changes are **detected**. `SyncEngine`, `DeleteGuard`, `SafeReplace`, the dirty-link rules (`CloudLinks`, `CloudUploadWorker`) and the conflict copies are unchanged; a detected change still calls `SyncWorker.request` for the pair.

| row | now | how / files |
|---|---|---|
| OLD: sync near-realtime uses a 60 s cloud poll, no long-poll or push | DONE (push for Drive / OneDrive: OWNER-ACTION) | The fixed poll is gone. `core/sync/ChangeWatch.kt` runs one watcher per provider that has auto pairs, only while the app is open. **Dropbox:** `files/list_folder/longpoll` with the cursor (`Dropbox.waitForChange`, honours `backoff`). **Box:** `OPTIONS /events` gives the realtime server, then a long-poll (`new_change` / `reconnect`, `Box.waitForChange`). **Drive** `changes.list` and **Graph** `/root/delta` deltaLink are polled with a back-off (30 s → 5 min; errors 30 s → 15 min; no network = no request). Long-polls are cancellable (`CloudHttp.stringCancellable`). Fallback: the 15-min WorkManager job (network + battery-not-low constraints) now asks the delta feed from the cursor taken at the start of the pair's last clean run and compares the local listing with the snapshot; if neither changed the pair is skipped, with a full run at least every 6 h (`core/sync/SyncSkip.kt`, hooks in `SyncWorker` and `SyncRuntime`). Nothing is scheduled or held open when nothing is linked. **Local:** `core/sync/AppFolderWatch.kt` puts a FileObserver on the folders of cloud and WebDAV working copies, so a copy changed by another app is queued for upload (hash-checked, own saves skipped). **WebDAV:** `core/webdav/DavWatch.kt` does a Depth-0 `getctag` / ETag PROPFIND per folder (Depth 1 only when the tag moved) and marks linked files "changed on the server" in the WebDAV browser. Delta / cursor parsing is pure in `core/cloud/ChangeFeeds.kt`, with JSON fixtures in `test/resources/fc_sync`. The Sync options show the mode per provider. **OWNER-ACTION:** real push for Drive / OneDrive needs an HTTPS webhook receiver (for example worker routes `POST /push/drive` for `changes.watch` channel notifications and `POST /push/graph` for Graph subscription validation and notifications, relaying to FCM) plus FCM in the app. |
| 16b: no batch convert from the Files multi-select | DONE | A Convert button in the Files selection bar (`ui/files/FilesComponents.kt`, `FilesScreen.kt`) hands the files to the Convert hub (`ui/convert6/Cv6Batch.kt`). A batch conversion takes every accepted file; a single-file one takes the first. |
| §31 / §37 / OLD: no "take ownership" of other reviewers' comments | DONE | A "Take over" action per reviewer in the shared-review screen (`ui/cloud/ReviewScreen.kt`). `CollabStore.takeOver` sets /T to my name, removes the comments from the others' set and publishes them as mine. The choice is pure in `CommentMerge.takeOver`. It refuses while the document is open in the reader. |
| §31: "Remove Me" is local only | DONE (code) + OWNER-ACTION | New worker route `POST /reviews/:id/leave` (`ai-proxy/collab/worker.js`): the participant is hidden from the reviewer list and its comments stay. The app calls it (`CollabClient.leave`) before removing the review locally. An old server answers 404, and leaving then stays local. Owner: redeploy the collab worker. |
| 37: no local reminder before a review expires | DONE | `core/collab/ReviewReminder.kt`: one WorkManager job per review, due 24 h before the deadline, posted locally (no network). It is rescheduled on every `CollabStore.upsert` and has a switch in the review screen. |
| 37: no To / Cc auto-complete | DONE | `core/share5/RecipientBook.kt` stores used addresses on the device. `ui/share5/RecipientField.kt` shows suggestion chips. Used in the e-mail review screen (with a new Cc field: `S5Share.email(cc=)`) and in Send back. |
| 37: no send-as-FDF size rule | DONE | `FdfRule` (default: above 5 MB; choices 1 / 2 / 5 / 10 / 20 MB) and the new "Send my comments back" screen (`ui/share5/SendBackScreen.kt`, route `share5/send_back`, row in the Share hub). Above the limit, only the comments go as FDF (`CommentExchange.exportFdf`). |
| §32 / §41: no Dropbox Team Folders | DONE | Team-space root: `DropboxSpace.teamRoot` reads `root_info`, and the `Dropbox-API-Path-Root` header switches the root. Switch chips "My folder / Team space" are in the cloud browser (`ui/cloud/CloudScopes.kt`). The switch is refused while Dropbox sync pairs exist. After a switch, saves resolve the file path by id. |
| §32: no OneDrive Business / SharePoint document library | DONE (client) + OWNER-ACTION | **Business:** already present (the `common` authority with `/me/drive`). **SharePoint:** a "SharePoint libraries" entry lists the libraries of followed sites (`/me/followedSites` → `/sites/{id}/drives`). Items are addressed as `sp:<driveId>|<itemId>` (`core/cloud/SharePoint.kt`, `OneDrive.kt`). The wider scope is asked for only on that tap and is used for refreshes only after the sign-in succeeded. Library folders are never skipped by the periodic job (`feedCovers`). **Owner:** add the delegated Graph permissions Sites.Read.All and Files.ReadWrite.All to the Azure app registration. |
| §32 / §42: no Egnyte | DONE (WebDAV) + OWNER-ACTION for the native API | WebDAV presets in Add place (`core/webdav/DavPresets.kt`): Egnyte `https://<domain>.egnyte.com/webdav/` (where the Egnyte admin allows WebDAV), Nextcloud, ownCloud, pCloud, Koofr and Yandex. A native Egnyte provider needs an Egnyte API key and OAuth app registered by the owner. |
| §32: DMS non-PDF preview with DMS context | NOT-FEASIBLE | There are no DMS / ECM connectors on Android (ECM is N/A in §22). |
| §33: no Connected open message | DONE (code) + OWNER-ACTION | The owner edits it in the document screen (`ConnectedClient.settings(openMessage=)`). Readers see it in the reader banner and on the open screen (`ConnectedStatus.message`). Worker: `settings` stores `openMessage` and `/open` returns `message` (`ai-proxy/connected/worker.js`). Owner: redeploy the connected worker. |
| §33: no Clone of a connected document | DONE | `ConnectedDocs.clone` gives the copy a new id and key, with no readers or history; settings and the open message are copied. A Clone action is in the owner screen. Works with the current worker. |
| §33: no switch to turn ConnectedPDF off | DONE via fix/fc-settings | `FcsPrefs.connectedOff` (Settings switch; guards in `ConnectedClient`, `ConnectedDocs`, `ReaderState`). The fc-sync duplicate (`ConnPrefs`) was removed in the merge. |
| 37: index preferences lack XMP, custom properties, structure tags and logging | DONE via fix/fc-settings | `core/search/FcsIndexPrefs.kt`, `IndexWorker`, `ui/nve/search/FcsIndexPrefsUi.kt`. The fc-sync duplicate (`DocMeta` / `IndexExtras`) was removed. |
| 37: search cannot ignore Asian character width | DONE via fix/fc-settings | `ArabicNormalizer` / `IndexText`, `SearchPrefs`. The fc-sync duplicate was removed. |
| 37: no document title in search results | DONE via fix/fc-settings | Search DB v2 with a title column. The fc-sync duplicate (`FileResult.title`) was removed. |
| 37: no Fast Find cache size limit | DONE via fix/fc-settings | `FullTextIndex.evictToLimit`. The fc-sync duplicate (`enforceLimit`) was removed. |

Needs a device test:
- the Dropbox and Box long-polls (held open and cancelled when the app goes to the background), and the Drive / OneDrive back-off;
- the periodic skip (a pair that did not change makes no listing requests);
- the FileObserver on working-copy folders;
- WebDAV ctag on Nextcloud;
- the Dropbox team space;
- SharePoint libraries after the owner grants the scopes;
- the review reminder notification;
- the To / Cc chips, Send back with FDF, and the Connected open message / clone;
- batch convert from Files.
