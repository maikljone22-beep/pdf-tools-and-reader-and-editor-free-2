# DONE: View / Shell, File / Properties, Edit text & images (feature/nitro-view-edit)

Branch `feature/nitro-view-edit` (from main b3521bd, main merged in at 8f842f1 for the new signature APIs). Not merged to main.
Work was split into parts that each own their files: parent (reader / view / shell hooks, `ui/nve/*.kt`), A edit-mode text,
B edit-mode images & page objects, C file / properties / print, D search / read aloud / articles, E image editor & vector shapes,
F reader panes & notification banners, G shell / settings / home.

**Nothing was tested on a device.** Status below = implemented and compiled (`:app:compileDebugKotlin` / CI "Build debug APK" pass);
behaviour on a phone is unverified. JVM round-trip tests cover the PDF-writing pieces (see "Tests").

Legend: DONE (where) · PARTIAL (what is missing) · ALREADY-PRESENT (evidence) · NOT-FEASIBLE (why) · OWNED ELSEWHERE (other session).
Paths are relative to `app/src/main/java/com/smartdocs/editor/`.

## Where things are reachable

- Reader **View** tab: new chips after view5's (zoom %, marquee, fit height, actual size, continuous facing, reverse view, word count,
  text viewer, grid/rulers/guides, spreadsheet split, page transitions, page / area snapshot, paste, select all, the six panes, check spelling,
  customise toolbars). **Edit** tab: "Edit tools" chip. **Tools** tab: every edit5 tool. ⋮ menu: Edit tools, Save as…, Document properties,
  Zoom, Word count, Paste, Page snapshot, Check spelling, Attachments, Signatures, Customise toolbars, Keyboard shortcuts, Revert to saved.
  Command search ("tell me", Ctrl+K): one command per feature and per edit5 tool, plus "Get help on …" / "Ask the help assistant".
- Selection bar: squiggly, note, bookmark, word count, copy formatted, copy as table, select all, column select, export, edit text, rewrite.
- Edit mode (content editing): text format bar + "more" sheets (fork A), image bar / more sheet / multi-select (fork B), Shapes button (fork E).
- Right click / long press on a document tab: tab menu. Right click on the page: context menu. Ctrl + wheel: zoom.

## Nitro cmp_01-06 — section 1 (Shell / Home / View)

| Row | Status | Where |
|---|---|---|
| Favorite Tools (pin any tool, reorder, Remove All, rename row, on Home) | DONE | G: ui/nve/shell/FavoritesUi.kt, ToolsScreen, HomeScreen; Custom reader tab rename + Remove all (Toolset.kt) |
| Home: Feature Guides (sample workflow PDFs) | DONE | G: core/nve/shell/FeatureGuides.kt (4 practice PDFs, Home + Help centre) |
| Tabs: Close Other / Close All (+ left / right, pin, copy path) | DONE | parent: ui/nve/NveTabs.kt, ReaderTabs.retain; Ctrl+W / Ctrl+Shift+W |
| Tabs: Copy as Path / Open Containing Folder | DONE (copy path) / N/A-ANDROID (folder) | NveTabs.kt |
| Tab groups, New Window, Cascade/Tile | PARTIAL | G: NveWindowActivity + "Open in new window" (Android multi-window); no movable tab groups (no Android equivalent) |
| Split pane / Next-Previous pane | DONE | parent: NveQuadSplit (4 panes, F6 / buttons cycle the active pane, row/column sync) |
| Zoom: Fit Height / Actual Size / Automatic | DONE | parent: ui/nve/NveZoom.kt presets, chips, zoom sheet, Ctrl+1/2/3 |
| Zoom range 25 %–6400 %, numeric zoom | DONE / range PARTIAL | PageView MIN_ZOOM 0.25 … MAX_ZOOM 32 (relative to fit width: up to ≈ 1000–3000 % of actual size, screen dependent; 6400 % not reached); numeric % field + presets in NveZoomSheet |
| Zoom marquee tool | DONE | parent: NveOverlays.kt (drag = zoom to rectangle, tap = 2×, zoom-out mode) |
| Copy With Formatting | DONE | parent: NveSelection.kt copyWithFormatting (HTML clip with size / bold / italic / colour / font of the text blocks) |
| Copy As Table | DONE | parent: core/nve/TextStats.kt TableCopy (HTML table + TSV) |
| Pane: Attachments | DONE | F: ui/nve/panes/NvePanes.kt (read-only, reusable `NveAttachmentsList`; open / save / jump) |
| Pane: Layers | OWNED ELSEWHERE | pdf-39 (layers) |
| Pane: Tags | DONE | F: structure-tree pane (view only), "not tagged" → accessibility tool |
| Pane: Signatures | DONE | F + parent: list + validate via main's `DigitalSignature.verify(file, revocation = false)` (matched by fieldName), "view signed version" via `SigRevisions.extract`; field tap sheet = main's SigFieldSheet |
| Document Notifications bar | DONE | F: ui/nve/panes/NveDocBanners.kt + core/nve/panes/DocNotices.kt (forms, JavaScript, font restrictions, PDF/A, PDF/UA, pending redactions, 3D, portfolio) |
| Grid colour, offsets, subdivisions, snap, guides, units | DONE | parent: NveGridRulers (page-anchored), NveGridSheet, guides, NveGridSnap (workbench tools); edit-mode objects snap via B's smart alignment |
| Spell Check | DONE | A: ui/nve/Spell.kt (SpellCheckerSession, custom dictionary, document dialog); parent: comment / note / reply / form fields + whole-document Check Spelling (NveDocSpell, core/nve/DocTexts.kt) |
| Auto Recovery (interval, "(Recovered)" copy) | DONE | G: ui/nve/shell/Recovery.kt (interval in Settings, banner on reopen, Revert to saved in ⋮) |
| Automatic repair of damaged files | DONE | G: PdfRepair + NveRepairButton in the open-error screen |
| Shell "Convert to Word / RTF / Excel / PowerPoint / Text" | DONE | G: 4 activity-aliases → Convert hub with the target preselected |

## Nitro cmp_01-06 — section 2 (File / Save / Properties)

| Row | Status | Where |
|---|---|---|
| Print: custom N×M | DONE | C: core/view5/PrintLayout.kt, PrintPrep.kt, ui/nve/file/NvePrint.kt |
| Print: Booklet both sides / front only / back only | DONE | C: PrintLayout.bookletSides |
| Print: auto-rotate and center, page scaling | DONE | C: fit / actual / shrink oversized / custom % |
| Print: only pages with markups | DONE | C: core/nve/PrintExtras.markupPages |
| Batch Print | DONE | C: ui/nve/file/BatchPrintScreen.kt, Tools tile "print_batch", print sheet "Print other files…" |
| Properties: Base URL | DONE | C: core/nve/DocInspect.kt, XmpOps base URL write; DocPropsScreen + NveDocInfoSheet |
| Properties: Page Size / Fast Web View / Locked | DONE | C: DocInspect |
| Properties Security tab (method + current permissions) | DONE | C: NveSecurityPanel (method, key length, revision, 8 permissions) |
| Properties Fonts tab | DONE | C: DocInspect.fonts + NveFontsPanel (pages, annotation appearances, fields; type, encoding, embedded / subset) |
| Mark as PDF/UA (was CODE-ONLY) | DONE | C: XmpOps.markPdfUa (pdfuaid, MarkInfo, DisplayDocTitle, Lang; warns when untagged) |

## Nitro cmp_01-06 — section 3 (Edit text and images)

| Row | Status | Where |
|---|---|---|
| Edit opt: paragraph vs line selection | DONE | A: PageTextModel line mode, edit preferences sheet |
| Smart Alignment | DONE | B: ui/edit/ImageTools5.kt + EditPageView guides (images / objects); A: guides when moving text boxes |
| Text font face | DONE | A: core/edit/EditFonts.kt + font picker (bundled Arabic, device fonts; CFF-outline OTF falls back) |
| Justify | DONE | A: RichText (RTL aware, last line not justified) |
| UPPER / lowercase | DONE | A: UPPER / lower / Title / Sentence |
| Character / word / line spacing | DONE | A (word spacing as TJ gaps for Type0 fonts; paragraph spacing + indent too) |
| Horizontal scaling, baseline offset, stroke + colour | DONE | A: Tz / Ts / render modes incl. invisible |
| Font embedding choice + convert to system font | DONE | A: subset / full / system; Arabic always embedded |
| Editing preferences | DONE | A: word distance, font warnings, device fonts, no-editable-text alert, live spell check, digit shapes |
| Add Image from Clipboard | DONE | B: InsertExtras5; reader paste: parent NveClipboard |
| Add Image from Scanner (camera) | DONE | B: TakePicture → insert at the tapped spot |
| Image properties | DONE | B: size, placed size, ppi, colour space, bpc, filter, soft mask, inline/XObject |
| Image Rotate 180° | DONE | B |
| Image Brightness / Contrast | DONE | B: AdjustDialog5 (live preview); E: full filter set in the image editor |
| Image colour-space convert | DONE | B: RGB / Gray / B&W / CMYK (uncalibrated, no ICC) |
| Per-image resample | DONE | B: 300/150/96/72/custom ppi; per-image JPEG quality too |
| Image Flip H/V | DONE | B |
| Crop / Crop W-H / Scale % / lock proportions | DONE | B: CropDialog5 (numeric, non-destructive clip), PositionDialog5 |
| Bring to front / send to back | DONE (images, shapes, shadings) / NOT-FEASIBLE (text blocks: moving a text operator breaks the surrounding text object state) | B |
| Copy / paste page objects | DONE | B: images, text blocks, vector shapes; across pages and documents (shadings cannot be copied) |
| Numeric X/Y, rotation, scale | DONE | B: PositionDialog5, RotateDialog5 (any angle, 15° snap, shear) |
| Align & distribute | DONE | B: multi-select (tap / marquee / all), align, distribute, match size, page centre, anchor |
| Prompt to remove layers before editing | DONE | B: LayersPrompt5 → "Merge layers" (undoable) |

## OLD_PLAN_AUDIT view / edit items

| Item | Status | Where |
|---|---|---|
| Edit5 tools reachable from the reader (Tools tab / More menu / command search) | DONE | parent: ReaderToolbars ToolsToolbar + Edit tab chip, NveEdit5Sheet, NveCommands |
| Old Tools tiles "watermark" (ToolsScreen.kt:55) and "outline" (ToolScreens.kt:44,90) open edit5 | DONE | ToolScreens: → e5_watermark / e5_bookmarks (old screens kept as *_classic ids) |
| Spell check everywhere (edit text, comment/note dialogs, form fields) | DONE | A + parent (see section 1) |
| Rich text in edit mode (underline, strike, super/sub, spacing, bullets, justify, per-run styles E6) | DONE | A: core/edit/RichText.kt, ui/edit/TextTools5.kt |
| E7 more fonts incl. Arabic | DONE | A |
| E8 / EDT-10/11/12 image ops (flip, any angle, crop, opacity, brightness/contrast, z-order, numeric, align/distribute, ACTION_EDIT round trip) | DONE | B (ACTION_EDIT re-imports, keeps placement); E: in-app image editor |
| E10 rotated / XObject text editing | PARTIAL | A: any-angle text replace / delete / move; first-level Form XObject text (copy-on-write). Missing: nested forms, mirrored / skewed text, kerning removal inside forms |
| EDT-5/6/7 join / split / linked text boxes | DONE (limits) | A: split puts the second part below; links saved on the device (prefs), not in the PDF; no flow with list styles |
| VIEW-3/4 zoom presets + marquee | DONE | parent |
| VIEW-14 word count | DONE | parent: NveWordCountDialog (document / page / selection) |
| VIEW-1/2/8 continuous facing (+cover) + reverse order | DONE | parent: PageView |
| VIEW-11 rulers, guides, snap | DONE | parent |
| SHL-3 customise toolbars | DONE | G: NveToolbarCustomizeSheet; applied in ReaderTabRow / ViewToolbar (parent) |
| SHL-4 full selection toolbar | DONE | parent: NveSelectionChips |
| SHL-7 tab menu | DONE | parent: NveTabs |
| SHL-10 crash recovery copy + Revert | DONE | G + parent hooks |
| SHL-12 mouse (Ctrl+wheel zoom, right-click menu) | DONE | parent: nveMouseModifier, NveContextMenu |
| SHL-13 accent colour | DONE | G: core/theme/Accent.kt (AccentColors), named colour themes, dynamic colour |
| FILE-1 XMP import / export | DONE | C: export, append/merge, replace, raw view |
| FILE-4 fonts screen (PdfInspect code-only) | DONE | C: DocInspect.fonts + Fonts tab |
| FILE-5 print presets read by the print sheet | DONE | C: built-in + user presets; /ViewerPreferences print settings as defaults |
| FILE-10 unified Save As | DONE | C: NveSaveAsSheet (⋮ "Save as…") |
| PRN-1 greyscale, scale, comment summary | DONE | C (greyscale = vector overlay + monochrome default) |
| PRN-3 print many files | DONE | C |
| HOME-1 paste / HOME-2 page snapshot | DONE | G (Home: clipboard → new PDF, page → PNG); parent (reader: paste into the PDF, page / area snapshot + paste back) |
| Security summary in properties | DONE | C |
| Document notification banners | DONE | F |
| Signatures pane (read-only list + validate) | DONE | F + parent, reusing main's verifier |
| Attachments pane (list / open / save, read-only, reusable) | DONE | F |

## Foxit rows (fcmp_01-08 §1-3, fcmp_09-13_22 §9/10/12/13 word count/22, fcmp_14-21_23 §14, fcmp_28-42 §39 + extras)

| Row | Status | Where |
|---|---|---|
| §1 Batch Print / Invoice Print | DONE / NOT-FEASIBLE (invoice print is a Foxit plug-in for invoices) | C |
| §1 "Get Help on keywords" from the command search | DONE | G: NveHelp.openWithQuery; parent: command sheet item |
| §1 Online tutorial videos | NOT-FEASIBLE | no curated videos to link; feature-guide PDFs instead |
| §1 Support chatbot ("how do I") | DONE | G: help assistant grounded on Help-centre articles (offline ranking fallback) |
| §1 Search box starts an AI conversation | DONE | G + parent: "Ask the help assistant" in command search |
| §1 Status bar zoom % / page display | DONE | parent: StatusPill, zoom chip shows % |
| §1 Status bar cursor coordinates | DONE | parent: mouse X/Y in the chosen unit |
| §1 Start page cloud panel | DONE | G: CloudPanel.kt |
| §1 QAT reset / position | PARTIAL | parent: "Reset pinned commands"; position fixed in the top bar |
| §1 Customize the ribbon (new tabs, rename, icon, tools, reorder, reset) | DONE | G: UserTabs.kt; parent: user tabs shown in the reader tab row / toolbar |
| §1 Ribbon customisation import / export | DONE | G: Customization JSON |
| §1 Customise keyboard shortcuts | DONE | parent: NveKeysSheet (assign / remove / reset all) |
| §1 Multi-tab close all / before / after / except current | DONE | parent |
| §1 Pin / unpin tab, Ctrl+Tab, wheel tab switching | DONE (pin, Ctrl+Tab, Ctrl+Shift+PgUp/PgDn) / NOT DONE (mouse-wheel over the tab strip) | parent |
| §1 Open in a new window / drag tab out | DONE / NOT-FEASIBLE (dragging a Compose tab out of the window) | G + parent |
| §1 Skins / colour themes | DONE | G |
| §1 Preferences search box | DONE | G: SettingsSearch.kt |
| §1 Document properties security / fonts / page size / fast web view | DONE | C |
| §1 Crash reporter attaches the crashing PDF | DONE | G + parent hook in Navigation CrashReportDialog |
| §1 NPS feedback survey | DONE | G |
| §1 Auto-recovery restart with tabs restored | DONE | G (recovery banner + existing RestoreLastSession) |
| §1 Safe reading / trust prompt for links + JavaScript | DONE | G: Trust.kt; parent: ReaderIntents.openUri and FormFillState.ensureLoaded guards |
| §2 Clipboard cut / paste into the page | DONE (paste text / image; cut of edit-mode objects by B) | parent, B |
| §2 Select All page text | DONE | parent |
| §2 Fit: Actual Size | DONE | parent |
| §2 Edit Object: Shape / Shading | DONE | B (existing path / sh objects), E (new shapes / gradients) |
| §2 Insert from web page / clipboard / folder / open files / into file | DONE | G: OrganizeInsert.kt, PageInsert.kt |
| §2 Scan presets | DONE | G: ScanPresetsUi.kt |
| §2 Suspect results | ALREADY-PRESENT | ui/tools5/OcrBatchScreen.kt:44-106 |
| §2 Translation history | DONE | G |
| §3 Drag-and-drop a PDF onto the window | DONE | G: DropTarget.kt |
| §3 Recent list copy path / list size | DONE | G: Folders.kt |
| §3 Save to Google Drive as Google Docs / Sheets / Slides | DONE in code, NEEDS-KEYS | G: GoogleDrive.uploadConverted (Drive OAuth setup required) |
| §3 Pinned folders + default save folder | DONE | G + parent (reader Save As starts there) |
| §3 Close all tabs | DONE | parent |
| §3 Permission enforcement | OWNED ELSEWHERE | pdf-3c |
| §9 Logical page numbers / Initial view / Full screen on open / Layers | OWNED ELSEWHERE | pdf-39 |
| §9 Page transitions (12 types, direction, speed, range, saved per document) | DONE | parent: core/nve/PageTransitions.kt, NveTransitionsSheet, Presentation5 honours /Trans and /Dur |
| §9 Reverse View | DONE | parent |
| §9 Text Viewer (font, colours, widths, save TXT) | DONE ("auto rotate" width NOT-FEASIBLE) | parent: NveTextViewer |
| §9 Continuous Facing | DONE | parent |
| §9 Spreadsheet split (4 panes) | DONE | parent: NveQuadSplit |
| §9 AutoScroll reverse / next-previous page / number keys | DONE | parent: PageOverlays5 AutoScrollBar, NveKeys |
| §9 Magnification presets / Zoom To dialog | DONE | parent |
| §9 Marquee zoom (+ zoom out) | DONE | parent |
| §9 Magnifier window size / fisheye | DONE / NOT-FEASIBLE (Android Magnifier has no fisheye) | parent |
| §9 Actual size | DONE | parent |
| §9 Tab close others / left / right / all | DONE | parent |
| §9 Tab switching keys | DONE | parent |
| §9 Parallel view / tab groups | PARTIAL | split, quad split, second window; no tab groups |
| §9 Scroll lock | DONE | parent |
| §9 Bookmarks panel search | OWNED ELSEWHERE | pdf-3c |
| §9 Articles panel / read article | DONE | D: ui/nve/articles/NveArticlesUi.kt (+ parent overlay hook) |
| §9 Portfolio component viewing | PARTIAL | F: detection banner, list, open (PDFs in the reader), save; no preview pane for non-PDF parts, no search across parts |
| §9 Navigation panels set (Attachments, Signatures, Fields, Tags, Articles, Destinations) | DONE (Layers / Model Tree: elsewhere / NOT-FEASIBLE) | F |
| §9 Ruler units, guides, grid appearance, snap to grid | DONE | parent |
| §9 Line weights view | NOT-FEASIBLE | PDFium-Android has no line-weight render option |
| §9 Ruler orientation / opacity / reset | DONE | parent |
| §9 Named destinations panel | DONE | F (create / rename / delete / sort / go to; undoable) |
| §9 Word count | DONE | parent |
| §10 TTS pitch (was CODE-ONLY) | DONE | D: Settings slider + reader read-aloud options |
| §10 Reading order with comments and alt text | DONE | D: core/nve/NveReadOrder.kt; parent: read-aloud provider hook |
| §10 Read form fields | PARTIAL | D: values read with the page; no Tab-through-fields mode |
| §10 Reading / speech preferences | DONE | D |
| §11 3D (view / navigate / measure / comment / views / cross section / camera / add 3D) | NOT-FEASIBLE | no 3D renderer in the app; F shows a "contains 3D, poster shown" notice (poster rendering unverified on a device) |
| §12 Column / rectangle selection | DONE | parent |
| §12 Select All | DONE | parent |
| §12 Selection menu export / word count | DONE | parent |
| §12 Copy with formatting | DONE | parent |
| §12 Paste into PDF | DONE | parent |
| §12 Snapshot whole page + paste back | DONE | parent |
| §12 Find options include bookmarks / comments (+ fields, attachments) | DONE | D: NveFindScopes / NveFindExtraResults (parent hooks in the find bar) |
| §12 Highlight-all colours / opacity | DONE | D: NveHitStyle; parent: PageCanvas hook |
| §12 Search scope folder / open PDFs / portfolio PDFs / selected index | DONE / DONE / DONE / PARTIAL (one app index) | D |
| §12 Include attachment contents | DONE | D: DocParts indexes attached PDF / text contents |
| §12 Additional criteria (author, title, subject, keywords, dates) | DONE | D (Info dictionary; XMP-only fields not used) |
| §12 Results sort / save CSV / PDF | DONE | D |
| §12 Auto-searched fields / layers / signatures | PARTIAL | fields indexed; layers = pdf-39; signature text not indexed |
| §12 Search & Remove Text (word list) | NOT DONE | workaround: Replace All with empty / redaction (D) |
| §12 Catalog index definition | PARTIAL | D: stop words, numbers, excluded folders; no titles / include dirs / custom fields |
| §12 Index revise (rebuild / purge / backup / restore) | DONE | D |
| §12 Embedded index | NOT-FEASIBLE | Acrobat's embedded index format is proprietary |
| §13 Word count (PDF + selection) | DONE | parent |
| §22 Image editor layers palette | DONE | E: per-layer opacity / blend / visibility in NveImageEditor; B: per-object opacity + 16 blend modes |
| §22 Articles: define boxes, properties, panel, read, modify, combine | DONE (modify PARTIAL: no drag-resize of a box) | D |
| §22 Automatic spell check while typing | DONE | A + parent (live underline in edit text, comment, note, form fields) |
| §22 Check Spelling dialog (Ignore, Ignore All, Change, Add to Dictionary) | DONE | A: DocumentSpellCheckDialog; parent: NveDocSpell |
| §22 Dictionary management | DONE | A: SpellDictionarySheet |
| §22 Layers rows | OWNED ELSEWHERE | pdf-39 |
| §22 XMP append / save .xmp | DONE | C |
| §22 Security / Fonts tabs | DONE | C |
| §22 Viewer print presets (PickTrayByPDFSize, PrintPageRange, NumCopies, PrintScaling, Duplex) | DONE | C (editable + read by the print sheet) |
| §14a Edit text from a selection | DONE | parent chip → NveEditRequest → A selects the block |
| §14a Rotate a paragraph / text box | DONE | A |
| §14a Font name / colour picker (RGB, HSV, CMYK→RGB) | DONE | A |
| §14a Underline / strike / super / subscript / bullets & numbering libraries / indent / spacing / scale / text mode | DONE | A |
| §14a Writing direction LTR / RTL toggle | DONE | A |
| §14a OCR button on scanned pages in Edit mode / "no editable text" alert preference | DONE | A |
| §14a Vertical text | DONE | A |
| §14a Move an existing text box + resize width + guides | DONE | A |
| §14a Link / join / split text boxes | DONE (limits above) | A |
| §14a Merge text objects / split text / remove kerning | DONE | A |
| §14a Convert text to outlines | DONE (Type3 fonts stay text) | A |
| §14b Edit object filter, multi-select, cut / copy / paste, scale X/Y, any-angle + 15° snap, flip, shear, clipping add / clear, opacity, stacking, page-centre, align / distribute / match size, properties | DONE | B (clear clipping only when the clip covers just that object) |
| §14c Draw new image / image editor / selections / painting tools / clone stamp / filters / flip / layers | DONE | E: ui/nve/imgedit/NveImageEditor.kt |
| §14c Spot healing brush | PARTIAL | E: ring-average fill, no texture synthesis |
| §14c Edit using another application | DONE | B: ACTION_EDIT round trip |
| §14c Image annotation advanced (scale when / proportional / position) + properties (hidden, border, locked) | DONE | B: ImageAnnotPropsDialog5 (button on stored image stamps, parent hook in AnnotEditUi) |
| §14c Place image on multiple pages | DONE | B |
| §14d Shapes as page content (line, rect, round rect, ellipse, polyline, polygon, Bezier, closed Bezier, free paint) + cap / join / miter, editable later | DONE | E: core/nve/shapes/NveShapes.kt, ui/nve/shapes/ShapeEditor.kt |
| §14d Shading objects (linear / radial gradients, stops, opacity) | DONE (free-drag reference line PARTIAL: presets + radius) | E |
| §14d Reflow Editing | PARTIAL | A: one page at a time as flowing text; per-block formatting / columns lost; no reflow across pages |
| §14d Add Article Box | DONE | D |
| Tapped link shows the URL first + Copy URL | DONE | parent: NveLinks / NveLinkPreview (switch to skip) |
| Plain-text URLs tappable | DONE | parent: core/nve/UrlFinder.kt + PageCanvas hook |
| Arabic-Indic digits when typing | DONE | A: NveDigits (edit mode); parent: annotation text dialog |
| Editable vector shapes (rounded rect, Bezier, shading) | DONE | E |
| §39 Keyboard shortcuts (Ctrl+O/W/Shift+W, Ctrl+D, F3/Shift+F3, Ctrl+Shift+F, Ctrl+1/2/3, Ctrl+Shift+±, Ctrl+A/C/V, Ctrl+B/T, Ctrl+Shift+D/T/I, comment tool keys, Ctrl+6, Ctrl+R, Alt+5, F4, F11, Ctrl+H, Ctrl+Shift+H, read-aloud keys, F1, Ctrl+Home/End, Ctrl+PgUp/PgDn, Space / Shift+Space in all modes, Ctrl+Tab) | DONE | parent: ui/nve/NveKeys.kt |
| §39 Keys5 conflicts (Ctrl+K/L/M/N, Ctrl+Shift+C/R) | DONE | parent: "Foxit / Acrobat layout" switch; default keeps the view5 bindings |
| §39 Single-key accelerators | DONE | parent (preference) |
| §39 Ctrl+8 hide comments, Ctrl+J JS console, e-mail all tabs | NOT DONE | comment hiding is section 7 (other session); no JS console; e-mail = share of the current tab only |

## Tests (JVM, PDFBox only)

New: core/nve/NveViewTest (word count, table copy, styled HTML, URL finder, /Trans round trip, spell-check texts),
core/edit/NveTextTest (A, with an asset-free test font `test/java/com/tom_roush/pdfbox/pdmodel/font/NveTestFont.java`),
core/edit/NveImageTest (B), core/nve/NveFileTest (C), core/nve/NveSearchTest (D), core/nve/NveShapesTest (E),
core/nve/NvePanesTest (F), core/nve/NveShellTest (G). JVM-test notes: pdfbox-android cannot initialise the standard-14
fonts, parse TrueType fonts or log (android.util.Log) on a plain JVM, so the tests use NveTestFont and a test-only
`android/util/Log.java` stand-in; the AcroForm is read with `getAcroForm(null)` (no standard-font fix-up).

## Commits and CI

Main commits (branch log `git log b3521bd..HEAD`): 7f95ed0, 096ea46, 9970e92, 7bb2b1b, 321d388 (A); b966433, 701e208, 9dca81c,
b753c6e (B); 69d6830 (C); fad15c3, 7a10a56 (D); 6d074b1, 42af97b, 440d86a (E); bf0ccf8, 7934e03, ed300e1 (F); e9bdb7a, fda8904,
52e1be2, 841a3af, 7c7121d (G); 7d51820, 73d1118, defc609, e2671fa, 3bb517e, 9d6eb14, e7a5b68, 9089ce5, d56db97 (parent: reader
hooks, compile / test fixes); merges of origin/main 8f842f1 and 95b1d2f (caaf3a9: AI/Office, pdf-3c signing, ReaderGuard —
NVE copy / export / snapshot / rewrite / edit-text / text-viewer save now ask `state.guard.check(...)`; document edits go
through `applyInPlace`, which ReaderGuard already guards).

CI (GitHub Actions "Build APK"): "Build debug APK" passes since defc609; unit tests: 40584635868 (9089ce5) 895/896 passed,
the last failure (CMYK colour-space name) is fixed in d56db97 — see the final report for that run's result.
