# Verify A: adversarial check of the MISSING and CODE-ONLY rows (cmp_01-06, cmp_07-08)

This was a read-only check of `D:\pdf\SmartDocs-project-v0.4.0`. Paths are relative to `app/src/main/java/com/smartdocs/editor/` unless they start with `res/`.

**Scope:** 89 rows (54 in cmp_01-06 and 35 in cmp_07-08). I also spot-checked 15 HAVE rows.

**Result:** 84 statuses confirmed (82 CONFIRMED-MISSING, 2 CONFIRMED-CODE-ONLY) and 5 corrected (all to ACTUALLY-PARTIAL).

## Verification table

| Source file | Nitro feature | Original | Verified | Evidence / terms searched |
|---|---|---|---|---|
| cmp_01-06 | Tabs: Close Other / Close All | MISSING | CONFIRMED-MISSING | ReaderTabs.kt:117,196,233-241 offer single close only. Searched `close.?all`, `close.?other`, `إغلاق الكل`, `إغلاق البقية`: 0 hits |
| cmp_01-06 | Zoom Fit Height / Actual Size / Automatic | MISSING | CONFIRMED-MISSING | Only fitPage/fitWidth (ViewSheet.kt:104-106, ReaderToolbars.kt:115). `FitHeight` appears only in the AcroJS stub (AcroJsLib.kt:263). Searched `actual.?size`, `zoom100`, `الحجم الفعلي`, `ملاءمة الارتفاع` |
| cmp_01-06 | Zoom marquee tool | MISSING | CONFIRMED-MISSING | Only a lens/magnifier (PageOverlays5.kt:125, strings v5_lens) and the pan & zoom window. Searched `marquee`, `zoomRect`, `zoom.?to.?rect` |
| cmp_01-06 | Copy with formatting | MISSING | CONFIRMED-MISSING | Every `setPrimaryClip` uses `newPlainText` (ReaderIntents.kt:46 etc.). Searched `newHtml`, `htmlText` |
| cmp_01-06 | Copy As Table | MISSING | CONFIRMED-MISSING | The selection bar (ReaderOverlays.kt:90-99) has no table copy. Searched `copy.?table`, `نسخ كجدول`, and "table" in ui/reader, ui/view5, ui/ai5 |
| cmp_01-06 | Layers pane (OCG show/hide) | MISSING | CONFIRMED-MISSING | OCG is used only in WatermarkOps.kt:274-293, PageInspector, Sanitizer.kt:136-239 and PreSignAnalyzer.kt:116. `e5_pm_layers` is only the PageMode "UseOC" label (DocPropsScreen.kt:124) |
| cmp_01-06 | Print custom N×M | MISSING | CONFIRMED-MISSING | PrintSheet5.kt:75 offers fixed 1/2/4/6/9/16 |
| cmp_01-06 | Booklet front only / back only | MISSING | CONFIRMED-MISSING | PrintSheet5.kt:68-69 has booklet and binding only. PrintLayout.kt:37 |
| cmp_01-06 | Print only pages with markups | MISSING | CONFIRMED-MISSING | PrintLayout.Subset is ALL/ODD/EVEN (PrintLayout.kt:6). There is only a comments on/off switch (PrintSheet5.kt:65) |
| cmp_01-06 | Batch print | MISSING | CONFIRMED-MISSING | PrintScreen (MiscTools.kt:29-39) and PdfPrint print one file. Recipes StepType (Recipes.kt:22-34) has no PRINT step |
| cmp_01-06 | Properties: Base URL | MISSING | CONFIRMED-MISSING | DocPropsScreen.kt and QuickSheets2.kt:96-130 have no URI/Base. Searched `"URI"`, `baseUri` |
| cmp_01-06 | Properties: page size / Fast Web View / Locked | MISSING | CONFIRMED-MISSING | Facts line DocPropsScreen.kt:93 (version, pages, encrypted, tagged, XMP). Quick PropertiesSheet (QuickSheets2.kt:118-129) shows file, size, pages and dates. FileInfoDialog FilesComponents.kt:228. Linearize is listed as unsupported (t5_opt_fastweb_na) |
| cmp_01-06 | Mark as PDF/UA | CODE-ONLY | CONFIRMED-CODE-ONLY | `uaPart` param IccSrgb.kt:113-133. Its only callers are PdfA.kt:209 (no uaPart) and a test. No UI hit for `pdfuaid` or `PDF/UA` |
| cmp_01-06 | Edit opt: Smart Alignment | MISSING | CONFIRMED-MISSING | `snap` in ui/edit and core/edit: only EditGeometry.kt:45 (rotation rounding) |
| cmp_01-06 | Text justify | MISSING | CONFIRMED-MISSING | `enum TextAlign { LEFT, CENTER, RIGHT }` PageTextModel.kt:14. Justify exists only in Office and reflow |
| cmp_01-06 | UPPER/lowercase | MISSING | CONFIRMED-MISSING | EditPanels.kt strings: no case control |
| cmp_01-06 | Horizontal scaling / baseline / stroke text | MISSING | CONFIRMED-MISSING | EditTextStyle has only size, color, bold, italic, align, family and lineHeight (TextWriter.kt:18-26). Searched `Tz`, `setTextRise`, `RenderingMode`, `setCharacterSpacing` |
| cmp_01-06 | Add image from clipboard | MISSING | CONFIRMED-MISSING | No `getPrimaryClip` anywhere. Clipboard reads are text only (Cv6OptionsSheet.kt:102-105) |
| cmp_01-06 | Image brightness/contrast | MISSING | CONFIRMED-MISSING | EditPanels.kt actions: replace, export, share, rotate, delete. Brightness exists only in scan |
| cmp_01-06 | Image colorspace convert | MISSING | CONFIRMED-MISSING | Only whole-document pdf_gray (Cv6Catalog.kt:59) |
| cmp_01-06 | Image flip H/V | MISSING | CONFIRMED-MISSING | EditController.kt:301-320 has move, resize, rotate and reset only |
| cmp_01-06 | Image crop / scale % / lock | MISSING | CONFIRMED-MISSING | Resize is uniform `Affine.scale(s,s)` (EditController.kt:310), so proportions are always locked, but there is no crop and no numeric value |
| cmp_01-06 | Bring to front / send to back | MISSING | CONFIRMED-MISSING | `o5_bring_forward`/`o5_send_backward` are Office only. Searched `bringToFront`, `zIndex.*annot` |
| cmp_01-06 | Copy/paste page objects | MISSING | CONFIRMED-MISSING | ae_copy duplicates annotations only (AnnotEditUi.kt). The content editor has no copy |
| cmp_01-06 | Numeric X/Y, rotation, scale | MISSING | CONFIRMED-MISSING | EditPanels.kt strings: no numeric fields |
| cmp_01-06 | Align & distribute | MISSING | CONFIRMED-MISSING | `distribute`, `alignTop`, `matchSize`, `توزيع`: only Office/DOCX parsers and licence text |
| cmp_01-06 | Prompt to remove layers before edit | MISSING | CONFIRMED-MISSING | No OCG reference in ui/edit or core/edit |
| cmp_01-06 | Thumbnail size control | MISSING | CONFIRMED-MISSING | Fixed `minCell` (OrganizeScreen.kt:155). ReaderPanels.kt:203 is fixed at 96 dp |
| cmp_01-06 | Embed / remove embedded page thumbnails | MISSING | **ACTUALLY-PARTIAL** | Removal is in the UI in two places: Optimize switch `t5_opt_thumbs` (OptimizeExportScreens.kt:88 → Optimizer.kt:37,181) and Sanitize item THUMBNAILS (SanitizeScreen.kt:139 → Sanitizer.kt:300-303). Embedding is missing |
| cmp_01-06 | Insert multiple files at once | MISSING | CONFIRMED-MISSING | `pickInsert … files.firstOrNull()` OrganizeScreen.kt:57 |
| cmp_01-06 | Insert from clipboard | MISSING | CONFIRMED-MISSING | The Organize copy/paste uses an internal page clipboard (OrganizeState.kt:209-212), not the system clipboard |
| cmp_01-06 | Delete pages after extraction | MISSING | CONFIRMED-MISSING | OrganizeState.extractSelected (:223) has no option |
| cmp_01-06 | Delete pages by typed range / odd / even | MISSING | **ACTUALLY-PARTIAL** | Typed-range delete works through the AI document agent: `delete_pages` accepts "3-5, 8" (core/ai/DocCommands.kt:28,163,169 → DocAgent.kt:197 → DocActions.kt:132-135 PdfOps.deletePages). It needs AI to be enabled. Odd/even and a non-AI range are missing |
| cmp_01-06 | Split: keep bookmarks/links/fields | MISSING | CONFIRMED-MISSING | Every split path uses `importPage` into a new PDDocument: PdfOps.split :230, keepPages :136, SplitOps.byBookmarks MergeSplitTools.kt:166, bySize :215, PageOps5.splitAt :239 |
| cmp_01-06 | Remove related bookmarks on delete | MISSING | CONFIRMED-MISSING | PdfOps.kt:146-151 calls `doc.removePage` only. Searched `prune`, `dangling`, `deadDest`, `broken` |
| cmp_01-06 | Publisher / Visio / WordPerfect source | MISSING | CONFIRMED-MISSING | Searched `"pub"`, `"vsd"`, `"wpd"`, `visio`, `wordperfect`: 0 hits |
| cmp_01-06 | Create from clipboard image | MISSING | CONFIRMED-MISSING | clipboard_pdf is Opt.TEXT only (Cv6Catalog.kt:75) |
| cmp_01-06 | Combine: range per file | MISSING | CONFIRMED-MISSING | MergeScreen FileOrderRow has no range. Workaround: PageOps5.copyPages (insert a page range from another file, PageOpsScreen.kt:85-91) |
| cmp_01-06 | Font embedding options | MISSING | CONFIRMED-MISSING | No `subset` or `embed font` option strings |
| cmp_01-06 | Page size for text sources | MISSING | CONFIRMED-MISSING | text_pdf and md_pdf have opts=MERGE only (Cv6Catalog.kt:66-67). ToPdfPlusScreen strings: no size |
| cmp_01-06 | Attach source file | MISSING | CONFIRMED-MISSING | Searched `attach.?source`, `attach.?original`, `إرفاق الأصل`: 0 hits |
| cmp_01-06 | Word→PDF: convert links, headings → bookmarks | MISSING | **ACTUALLY-PARTIAL** | Links are probably kept: OfficeHtml.kt:123 writes `<a href>` and ToPdf.kt:49,74 prints that HTML through a WebView. Chromium print output normally keeps link annotations, but I did not test this at runtime. Headings→bookmarks is missing at conversion time. A later workaround exists: BookmarksScreen.kt:123 → OutlineTools.detectHeadings |
| cmp_01-06 | HTML: header/footer, background | MISSING | CONFIRMED-MISSING | No such option in convert6 or ToPdfPlus |
| cmp_01-06 | Printer: PDF version / PDF/A | MISSING | CONFIRMED-MISSING | T5PrintService (core/tools5/ShareAndPrint.kt, Manifest :117) has no options |
| cmp_01-06 | Printer: properties | MISSING | CONFIRMED-MISSING | Same service, no options |
| cmp_01-06 | Printer: security | MISSING | CONFIRMED-MISSING | Same service, no options |
| cmp_01-06 | PDF→Word .doc | MISSING | CONFIRMED-MISSING | outExt "docx" only. `.doc` is accepted as input only (Kind6.DOC) |
| cmp_01-06 | Word character spacing | MISSING | CONFIRMED-MISSING | Opt enum (Cv6Catalog.kt:11-12) has no such option |
| cmp_01-06 | Complex-table threshold | MISSING | CONFIRMED-MISSING | Opt enum: TABLES on/off only |
| cmp_01-06 | Excel 2003 XML | MISSING | CONFIRMED-MISSING | XlsxBuilder writes OOXML only |
| cmp_01-06 | Excel split multi-line rows | MISSING | CONFIRMED-MISSING | Opt enum: none |
| cmp_01-06 | Excel reduce merged cells | MISSING | CONFIRMED-MISSING | XlsxBuilder.kt:153 always writes merges. No option |
| cmp_01-06 | Text: paragraphs vs N chars | MISSING | CONFIRMED-MISSING | pdf_txt opts are RANGE, AUTO_OCR and TABLES. ExportTools.kt strings: none |
| cmp_01-06 | PDF→TIFF | MISSING | CONFIRMED-MISSING | ImgFmt JPG/PNG/WEBP (Images6.kt:4). `tif` appears only in readers and MIME sniffing |
| cmp_07-08 | 7.14 Typewriter smart alignment / snap to grid | MISSING | **ACTUALLY-PARTIAL** | Every workbench tap goes through `resolve()` (WorkbenchCanvas.kt:74-77,105). That includes the typewriter, text box and callout. It snaps to page vector segments and text glyph corners (Workbench.kt:71-85) when `v.snap` is on, which is the default (V5State.kt:58). The toggle chip is shown only for measure tools (Workbench.kt:122). Snap to grid is missing |
| cmp_07-08 | 7.28 Shape No Border | MISSING | CONFIRMED-MISSING | Thickness slider 1..12 (Annot2Toolbar.kt:92) and 0.5..24 (ReaderToolbars.kt:337). Only fill has a "none" option (an_no_fill) |
| cmp_07-08 | 7.30 Leader lines | MISSING | CONFIRMED-MISSING | Searched `"LL"`, `"LLE"`, `"LLO"`, `leaderLine`: 0 hits. MeasureWriter.kt:89 sets only the caption |
| cmp_07-08 | 7.38 Stamp from PDF page | MISSING | CONFIRMED-MISSING | StampsScreen.kt:72 uses an image picker only. Two-step workaround: snapshot → save PNG (Annot2Dialogs.kt:255-262) → image stamp |
| cmp_07-08 | 7.48 Multiple-scale warning | MISSING | CONFIRMED-MISSING | There is one global scale (V5State). Not applicable |
| cmp_07-08 | 7.58 Locked annotations | MISSING | CONFIRMED-MISSING | Searched `isLocked`, `setLocked`, `LockedContents`: no annotation hits |
| cmp_07-08 | 7.65 Hide comments on screen | MISSING | CONFIRMED-MISSING | The reader always renders with `annotations=true` (PageCanvas.kt:97). ui/reader/annot has no `hide` or `visib` hits |
| cmp_07-08 | 7.67 Flatten selection | MISSING | CONFIRMED-MISSING | `flattenAnnotations(src,out,pw)` PdfOps.kt:593 works on the whole document only |
| cmp_07-08 | 7.74 Pop-up note font/size/opacity prefs | MISSING | CONFIRMED-MISSING | Searched `noteFont`, `noteOpacity`, `popupFont`: 0 hits |
| cmp_07-08 | 7.76 Align / distribute annotations | MISSING | CONFIRMED-MISSING | Same search as the align & distribute row above |
| cmp_07-08 | 8.9 Barcode field | MISSING | CONFIRMED-MISSING | Barcodes appear only in XFA parsing. A QR encoder exists (ui/cloud/QrCode.kt) but it is used only for share links |
| cmp_07-08 | 8.16 Locked field | MISSING | CONFIRMED-MISSING | FormBuilder.kt has no `/Lock` and no lock flag |
| cmp_07-08 | 8.17 Edit/delete existing field | CODE-ONLY | CONFIRMED-CODE-ONLY | `FormBuilder.removeField` (:154) has no callers. No `renameField`, `moveField` or `editField`. The annotation tools skip Widgets (Annotations.kt:395) |
| cmp_07-08 | 8.18 Field font/size/colour | MISSING | CONFIRMED-MISSING | Always `/Helv … 0 g` (FormBuilder.kt:47,84,121) |
| cmp_07-08 | 8.19 Field border/fill/style | MISSING | CONFIRMED-MISSING | Fixed grey border and pale fill (FormBuilder.kt:63-68) |
| cmp_07-08 | 8.20 Appearance gallery | MISSING | CONFIRMED-MISSING | none |
| cmp_07-08 | 8.24 Scroll long text | MISSING | CONFIRMED-MISSING | `DoNotScroll` appears only in the LiveWmScript comment (:139) and the JS stub list |
| cmp_07-08 | 8.28 Rich text | MISSING | CONFIRMED-MISSING | `richText` appears only in the JS stub and xlsx parsing |
| cmp_07-08 | 8.30 Checkbox style | MISSING | CONFIRMED-MISSING | No `/MK /CA` setter |
| cmp_07-08 | 8.31 Checkbox export value | MISSING | CONFIRMED-MISSING | FormBuilder.kt:92-101 |
| cmp_07-08 | 8.32 Checked by default | CODE-ONLY | CONFIRMED-CODE-ONLY | FormBuilder.kt:100 has the logic. The Default field is shown only for TEXT, BUTTON and option types (FormBuilderScreen.kt:160-161) |
| cmp_07-08 | 8.33 Radio style | MISSING | CONFIRMED-MISSING | Same as 8.30 |
| cmp_07-08 | 8.40 Commit on selection | MISSING | CONFIRMED-MISSING | `commitOnSelChange` appears only in the JS stub |
| cmp_07-08 | 8.41 Selection-change script | MISSING | CONFIRMED-MISSING | The builder writes no `/AA /K` for choice fields |
| cmp_07-08 | 8.42 Push button layout | MISSING | **ACTUALLY-PARTIAL** | The "Image" field kind in the form builder sets `/MK /TP 1` (icon only) and removes `/CA` (FormBuilderExtras.kt:49-55, called from FormBuilderScreen.kt:82). The default button is label-only. That gives 2 of the 7 layouts; the other 5 are missing |
| cmp_07-08 | 8.43 Button behaviour / states | MISSING | CONFIRMED-MISSING | No `/H` and no rollover or down appearance |
| cmp_07-08 | 8.60 Mouse Down/Enter/Exit/Focus/Blur | MISSING | CONFIRMED-MISSING | These keys are read only by PreSignAnalyzer.kt:48 (a pre-sign warning, ps_ev_focus). They are never executed or authored |
| cmp_07-08 | 8.61 Go-to action on fields | MISSING | CONFIRMED-MISSING | ButtonAction has Submit, Reset, OpenUri, Print and ImportImage (FormSubmit.kt:6-13). LinkOps handles PDAnnotationLink only |
| cmp_07-08 | 8.68 Import form data action | MISSING | CONFIRMED-MISSING | FDF/XFDF import exists for comments only (an_import, CommentExchange.kt:247) |
| cmp_07-08 | 8.71 HTML response viewer | MISSING | CONFIRMED-MISSING | FormSubmitter.kt:39 returns only the code and message |
| cmp_07-08 | 8.72 Signature-field lock on sign | MISSING | CONFIRMED-MISSING | Searched `FieldMDP`, `SigFieldLock`, `"Lock"`: 0 hits |
| cmp_07-08 | 8.76 Form responses file | MISSING | CONFIRMED-MISSING | Searched `responses` (only LTV hits) and `exportFormData`. Mail merge is the opposite direction |
| cmp_07-08 | 8.78 Make copies | MISSING | CONFIRMED-MISSING | FormBuilderScreen.kt has no copy or duplicate |
| cmp_07-08 | 8.79 Inherit / set default properties | MISSING | CONFIRMED-MISSING | none |
| cmp_07-08 | 8.80 Align / distribute fields | MISSING | CONFIRMED-MISSING | none |

## Corrections only

1. **cmp_01-06: Embed / remove embedded page thumbnails.** MISSING → **ACTUALLY-PARTIAL**. Removal is in the UI: the Optimize switch `t5_opt_thumbs` (ui/tools5/OptimizeExportScreens.kt:88, core/tools5/Optimizer.kt:37,181) and the Sanitize item THUMBNAILS (ui/security/SanitizeScreen.kt:139, core/redact/Sanitizer.kt:300-303). Embedding is still missing.
2. **cmp_01-06: Delete pages by typed range / odd / even.** MISSING → **ACTUALLY-PARTIAL**. The AI document agent's `delete_pages` takes ranges like "3-5, 8" (core/ai/DocCommands.kt:28,163,169; DocAgent.kt:197; DocActions.kt:132-135). It needs AI. There is still no odd/even option and no non-AI range.
3. **cmp_01-06: Word→PDF convert links / headings to bookmarks.** MISSING → **ACTUALLY-PARTIAL** (links). core/office/OfficeHtml.kt:123 emits `<a href>`, and core/tools5/ToPdf.kt:49,74 prints the HTML through a WebView, which normally keeps the links. This is not tested at runtime. Headings→bookmarks is missing at conversion; it can be done afterwards with ui/edit5/BookmarksScreen.kt:123 (OutlineTools.detectHeadings).
4. **cmp_07-08 7.14: Typewriter smart alignment / snap.** MISSING → **ACTUALLY-PARTIAL**. Workbench taps for the typewriter, text box and callout snap to page lines and glyph corners (ui/view5/WorkbenchCanvas.kt:74-77; Workbench.kt:71-85). This is on by default (V5State.kt:58), but the toggle is visible only with measure tools (Workbench.kt:122). Snap to grid is missing.
5. **cmp_07-08 8.42: Push button layout.** MISSING → **ACTUALLY-PARTIAL**. The form builder's "Image" field kind writes `/MK /TP 1` (icon only) (core/forms5/FormBuilderExtras.kt:49-55). The default button is label only. The other 5 layouts are missing.

Notes on confirmed rows (the status stays, but the evidence needed fixing or a workaround exists):
- 8.60: the original evidence said 0 hits for the Fo/Bl/D/E/X keys. They are read in core/sign/PreSignAnalyzer.kt:48, but only to warn before signing. The status stays MISSING.
- 7.38: a two-step workaround exists: snapshot → save PNG → image stamp.
- Combine range per file: a partial workaround is PageOps5.copyPages (insert a page range from a second file).
- 8.9: a QR encoder already exists in the app (ui/cloud/QrCode.kt) and could be reused for barcode fields.

## Spot-check of 15 HAVE rows

All 15 are reachable from the UI.

| Row | Feature | Reachable entry point confirmed |
|---|---|---|
| A128 | Bold / italic | EditPanels.kt:90-91, via the content_edit chip (ReaderToolbars.kt:177 → ReaderScreen.kt:181) |
| A41 | Rotate view | ReaderToolbars.kt:120 |
| A136 | Add image | ReaderToolbars.kt:179 (rd_add_image), EditPanels.kt:232-234 |
| A43 | Copy text | ReaderOverlays.kt:90 |
| A47 | Copy / extract image | EditPanels.kt:181,188 |
| A49 | Find bar | ReaderOverlays.kt:41-46 |
| A153 | Flatten | ToolScreens.kt:48 → FlattenScreen SecurityTools.kt:130 |
| A51 | Search comments / bookmarks | GlobalSearchScreen.kt:129-133 (PartKind) |
| A163 | Drag reorder | PageGrid.kt:88 (detectDragGesturesAfterLongPress) |
| A53 | Find & replace | ToolsScreen.kt:75, ToolScreens.kt:82, ReaderToolbars.kt:273 |
| A55 | Pages pane | ReaderToolbars.kt:129 |
| B178 | 8.74 Reset form | FormsScreen.kt:188 |
| B109 | 8.5 List box | FormBuilderScreen.kt:45-47 (form_builder tool) |
| B55 | 7.32 Eraser | ReaderToolbars.kt:149; PageCanvas.kt:229 removes ink hits |
| B42 | 7.19 Polygon | Toolset.kt:78 commentExtras → Annot2CommentChips → ReaderToolbars.kt:167 |
