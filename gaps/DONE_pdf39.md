# pdf-39 final report — feature/nitro-pages-convert

Branch `feature/nitro-pages-convert`, stages A–G, each with a green CI. Merged into main: A 248b0fe, B 3d2603a, C 7bdd947, D ff49267, E 448c6e9, F fa724fd. Stage G: branch head 4bf2f02, CI 40617505628 green, waiting for the merge.
Detailed per-stage tables with file pointers: `AUDIT/PAGES_CONVERT_LOG.md`. Strings: `strings_pc.xml` (prefix `pc_`).

Status: DONE · PARTIAL · OPEN (not done) · NOT-FEASIBLE · OTHER-SCOPE (row of a mixed section that belongs to another branch). "(feature/nitro-tools)" = done earlier on my nitro-tools branch.

**Totals (315 rows):** DONE 171 · PARTIAL 71 · OPEN 48 · NOT-FEASIBLE 12 · OTHER-SCOPE 13


## 4. Pages: organize  (19)  (cmp_01-06.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 0 | Thumbnails: enlarge / reduce thumbnail size | MISSING | DONE | Organize zoom buttons (`OrganizeScreen` cellScale) |
| 1 | Embed / remove embedded page thumbnails | MISSING | DONE | `PageOrganize.embedThumbnails` / `removeThumbnails` (Organize › THUMBS) |
| 2 | Insert from File: multiple files at once | MISSING | DONE | Organize › INSERT: several files in order |
| 3 | Insert from Clipboard (image or text as new page) | MISSING | PARTIAL | Organize › INSERT: clipboard text → page; clipboard image via Combine |
| 4 | Extract opt: delete pages after extraction | MISSING | DONE | Organize › EXTRACT "delete pages after" |
| 5 | Delete Pages: by typed range / even / odd | MISSING | DONE | Organize › DELETE: range + odd/even/portrait/landscape |
| 6 | Replace opt: import bookmarks of the replacing pages | PARTIAL | DONE | Preferences "import bookmarks when replacing" (main, pdf-3c `NpfOutline.importFrom`) |
| 7 | Rotate 180° | PARTIAL | DONE | Organize › ROTATE 90/180/270 |
| 8 | Rotate opt: All / Even / Odd pages | PARTIAL | DONE | Organize › ROTATE with subset |
| 9 | Split: by bookmarks (level N) | PARTIAL | DONE | `PageOrganize.splitByBookmarks` (level N) |
| 10 | Split opt: update navigational elements / bookmarks handling / identical form fields | MISSING | PARTIAL | keep navigation (bookmarks/links) per part; identical form fields not handled |
| 11 | Split opt: base filename, suffix (page range / number / padded / letter) | PARTIAL | DONE | `PartName` NUMBER/PADDED/LETTER/RANGE |
| 12 | Split opt: destination folder | PARTIAL | OPEN | parts go to the app folder |
| 13 | Resize: A2 / A1 presets | PARTIAL | DONE | `PageBoxOps.PAPERS` A0–A2 |
| 14 | Resize: Reset to original size | PARTIAL | DONE | Resize undo (reset) |
| 15 | Redact pages from the page context menu | PARTIAL | DONE | per-thumbnail menu › Redact |
| 16 | OCR pages from the page context menu | PARTIAL | DONE | per-thumbnail menu › OCR (opens the page in the reader) |
| 17 | Print Pages from thumbnails | PARTIAL | DONE | per-thumbnail menu › Print page |
| 18 | Remove related bookmarks when deleting pages | MISSING | DONE | `PageOrganize.deleteIn` + pref `removeBookmarksOnDelete` |

## 5. Combine / Create PDF  (26)  (cmp_01-06.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 19 | Create PDF from PowerPoint (ppt/pptx) | PARTIAL | NOT-FEASIBLE | legacy .ppt: binary format, no reader |
| 20 | Create PDF from Publisher (pub) / Visio (vsd) / WordPerfect | MISSING | NOT-FEASIBLE | no pure-Java reader for pub/vsd/wpd |
| 21 | Dialog: add folder / output folder choice | PARTIAL | PARTIAL | Combine › add folder; output to the app folder |
| 22 | Create from Clipboard: image | MISSING | DONE | Combine › add from clipboard (image uri or text) |
| 23 | Combine opt: page handling (range per file) | MISSING | DONE | `CombineEntry.pages` |
| 24 | Conversion quality presets (Web / Office / Print / Custom) | PARTIAL | OPEN | no named creation presets |
| 25 | Custom: font embedding Subset/Full/none, action when embedding fails | MISSING | OPEN |  |
| 26 | Custom: per-image downsample DPI / type / compression (ZIP, JPEG, JPEG2000, LZW, CCITT…) | PARTIAL | OPEN | optimizer after creation covers dpi / codecs (feature/nitro-tools) |
| 27 | Page size and orientation for image sources (Letter, Legal, A3, A4, custom) | PARTIAL | PARTIAL | A3/A4/A5/Letter/Legal; no custom size for images |
| 28 | Page size for text sources | MISSING | DONE | web/text printer `WebPdfWriter.Setup` paper / orientation / margins |
| 29 | PDF/A-1b compliance at creation | PARTIAL | DONE | Combine / scan "save as PDF/A" |
| 30 | All files: attach the source file to the PDF | MISSING | DONE | nav-attach "attach source file" (main) |
| 31 | Word: convert links, convert headings to bookmarks | MISSING | PARTIAL | headings → bookmarks (`CombineOpts.headings`); links kept by the WebView printer |
| 32 | Excel: bookmark per sheet, fit to page, include all sheets | PARTIAL | PARTIAL | all sheets with headings; no bookmark per sheet / fit to page |
| 33 | PowerPoint: bookmark per slide, frame slides, layout (handouts 2/6, notes), fit | PARTIAL | PARTIAL | handouts exist; no bookmark per slide / notes layout |
| 34 | HTML: header/footer text, include background color | MISSING | PARTIAL | background graphics switch (`WebCss`); no header/footer text |
| 35 | Publisher / Outlook settings | PARTIAL | PARTIAL | EML→PDF exists; Publisher not supported |
| 36 | Printer: target folder/filename, if exists: overwrite / append to beginning or end / warn | PARTIAL | OPEN | print service owns the job |
| 37 | Printer: PDF version 1.4–1.7, PDF/A-1b | MISSING | OPEN | print service |
| 38 | Printer: quality presets, grayscale, custom paper forms | PARTIAL | OPEN | print service |
| 39 | Printer: PDF properties (initial view, metadata) | MISSING | OPEN | print service |
| 40 | Printer: PDF security (open/permissions password, 40/128/256-bit) | MISSING | OPEN | print service |
| 41 | Printer: open after creation | PARTIAL | OPEN | print service |
| 42 | Scan profiles (create/edit/copy/delete; Good/High/Print/Text-only presets) | PARTIAL | DONE | `ScanPreset` save / apply / delete |
| 43 | Scan opt: sheets fronts / fronts & backs (manual duplex) | PARTIAL | DONE | `ScanExtras.duplex` |
| 44 | Scan opt: PDF/A-1b | PARTIAL | DONE | Scan studio "save as PDF/A" (2b) |

## 6. Convert from PDF  (18)  (cmp_01-06.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 45 | PDF to Word: .doc (2003) format | MISSING | NOT-FEASIBLE | binary .doc; RTF export opens in Word |
| 46 | Word layout modes: highly editable with layout / single column / precisely laid out (text boxes) | PARTIAL | OPEN | converter internals (core/convert) |
| 47 | Word: custom character spacing to keep layout | MISSING | OPEN |  |
| 48 | Word images: automatic / anchor to paragraph / anchor to page / inline only | PARTIAL | OPEN |  |
| 49 | Word tables: lower threshold / font size adjust for complex cells | MISSING | OPEN |  |
| 50 | Headers/footers: convert to real headers / delete / don't detect | PARTIAL | OPEN |  |
| 51 | Advanced text recovery for non-standard font encoding (all / problem only / off, + language) | PARTIAL | PARTIAL | "Repair file text" quick action before converting |
| 52 | Excel: Excel 2003 XML format | MISSING | DONE | `pdf_xml2003` (`Xml2003`) |
| 53 | Excel opt: split multi-line text into rows | MISSING | OPEN |  |
| 54 | Excel opt: reduce merged cells | MISSING | OPEN |  |
| 55 | Text opt: preserve paragraphs vs break lines at N chars | MISSING | OPEN |  |
| 56 | Text opt: headers/footers detect & convert / discard | PARTIAL | OPEN |  |
| 57 | PDF to Images: TIFF / multi-page TIFF | MISSING | DONE | `pdf_tiff` multi-page (RGB / grey / 1-bit) |
| 58 | Extract images opt: color / mono output format (BMP/JPEG/PNG/TIFF) | PARTIAL | PARTIAL | BMP and TIFF page export; extracted images keep native format |
| 59 | Extract images opt: open folder after creation | PARTIAL | NOT-FEASIBLE | Android has no "open folder"; results sheet shares / opens |
| 60 | Batch: output folder (original's folder / specific folder) | PARTIAL | OPEN |  |
| 61 | Batch: page range per file | PARTIAL | OPEN |  |
| 62 | Explorer context-menu conversions | PARTIAL | PARTIAL | open-with / share to the app |

## 18. Layers (OCG)  (6)  (cmp_16-24.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 63 | Layers pane: show/hide layer | MISSING | DONE | `LayersScreen` (tool e5_layers) |
| 64 | Layers: reset to initial visibility | MISSING | DONE | `Layers.reset` |
| 65 | Layers: expand/collapse | MISSING | DONE | order groups collapsible |
| 66 | Layer properties: name, default state, locked | MISSING | DONE | layer properties dialog |
| 67 | Initial state Visibility / Print / Export (When On / Never / Always) | MISSING | DONE | `Layers.setUse` (+ /AS) |
| 68 | Set OCG state action | MISSING | DONE | `Layers.setStateAction`, bookmark "show only this layer" |

## 19. Accessibility / PDF/UA  (8)  (cmp_16-24.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 69 | Auto Tag | PARTIAL | DONE | `AccessAutoTag` (headings, lists, figures, artifacts, re-tag) |
| 70 | Tags pane: create root/sibling/parent/child; create tags from a selection; "Tag as…" | MISSING | PARTIAL | tags tree: root / child / sibling; no "create from selection" on the page |
| 71 | Mark as Artifact | PARTIAL | DONE | tag › change to Artifact; auto-tag artifacts |
| 72 | Delete/reorder/edit tags, change tag type, show content groups, show/remove artifacts, Manage Role Map | MISSING | DONE | `AccessTags` delete / move / indent / type / role map |
| 73 | Tag types (Document … Ruby/Warichu) | PARTIAL | DONE | `AccessTags.TYPES` (all standard types) |
| 74 | Tag properties: Title, ID, Actual Text, Language, Expansion, Scope, Row/Col span, header cells, Summary, List style, … | MISSING | DONE | `AccessTags.setProps` / `setAttrs` |
| 75 | Embed Fonts (WCAG / PDF/UA) | MISSING | NOT-FEASIBLE | original font programs are not available |
| 76 | Mark as PDF/UA (+ document language) | PARTIAL | DONE | `AccessUa.markUa` |

## 20. PDF/A  (7)  (cmp_16-24.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 77 | Convert to PDF/A-1b | MISSING | DONE | PDF/A-1b level |
| 78 | Convert to PDF/A-1a / 2a ("a" levels need tags) | MISSING | DONE | PDF/A-1a / 2a (auto-tag) |
| 79 | Convert to PDF/A-2u | MISSING | DONE | PDF/A-2u |
| 80 | Notification when a file claims PDF/A / editing breaks the claim | MISSING | DONE | reader banners PDF/A / PDF/UA → Verify (`NveDocBanners`) |
| 81 | PDF/A output from Create PDF / virtual printer / Scan | PARTIAL | DONE | scan / combine "save as PDF/A" |
| 82 | PDF/UA marking | CODE-ONLY | DONE | `PdfAOptions.uaPart`, `AccessUa.markUa` |
| 83 | PDF version selection 1.4–1.7 | MISSING | DONE | standards panel "save as PDF version" |

## 4. Convert Tab - Create PDF (all sources, Office add-ins, printer, Combine Files)  (43)  (fcmp_01-08.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 84 | From Multiple Files: keep original names and locations | PARTIAL | PARTIAL | names kept; output to the app folder |
| 85 | From Multiple Files: add folder, sort by name/time/size | PARTIAL | DONE | Combine › add folder, sort name / date / size |
| 86 | Convert to PDF Settings: presets (Standard, High Quality Print, Smallest File Size, PDF/A-1b, PDF/UA-1, USPTO) | PARTIAL | PARTIAL | PDF/A option at creation; no other presets |
| 87 | Convert to PDF Settings: add bookmarks (Word headings, Excel sheet names, PPT slide titles) | PARTIAL | PARTIAL | headings → bookmarks; no sheet / slide bookmarks |
| 88 | Convert to PDF Settings: Tagged PDF | PARTIAL | PARTIAL | auto-tag afterwards (`AccessAutoTag`) |
| 89 | Convert Model to 3D PDF (DWG/CAD) | MISSING | NOT-FEASIBLE | no CAD reader |
| 90 | From Clipboard: image | MISSING | DONE | Combine › clipboard image |
| 91 | Web Page: conversion levels (N levels / entire site / same path / same server) | MISSING | OPEN | one page only |
| 92 | Web Page: page-load timeout | PARTIAL | DONE | `Setup.timeoutMs` |
| 93 | Web Page: disable hyperlinks / one single long page / create bookmarks / create PDF tags + optimize tag tree / headers and footers on new page / background graphics | MISSING | PARTIAL | disable links (`WebCss.stripLinks`); no single long page / bookmarks / tags |
| 94 | Web Page: convert images / underline links | MISSING | DONE | `WebCss` images / underline links |
| 95 | Web Page: page layout (page size, margins, orientation) | MISSING | DONE | `WebPdfWriter.Setup` |
| 96 | Web Page: media style Screen / Print; scaling (fixed / fit width / enlarge) | PARTIAL | PARTIAL | print media only; no scaling choice |
| 97 | Open Weblink as New Document (convert a linked page) | MISSING | OPEN |  |
| 98 | Form from a file (auto-detect form fields) | MISSING | OPEN |  |
| 99 | Form: scan a file into a form | MISSING | OPEN |  |
| 100 | From HWP (Hangul) | MISSING | NOT-FEASIBLE | no HWP reader |
| 101 | From Template (designed templates; search; categories; preview; Edit Now) | PARTIAL | PARTIAL | offline templates with preview; no online library |
| 102 | Add-in settings: preserve document properties | MISSING | DONE | `OfficeProps` in `ToPdf.convert` |
| 103 | Add-in settings: Excel comments → note comments; PPT speaker notes → notes; slide transitions; hidden slides; page/slide range | MISSING | OPEN |  |
| 104 | Add-in settings: Excel fit options (actual size / fit sheet to page / fit to width) | MISSING | OPEN |  |
| 105 | Word: comments to notes; footnote/endnote links; headings/styles → bookmarks list | MISSING | PARTIAL | headings → bookmarks in Combine; no comments / footnote links |
| 106 | Word Mail Merge → PDF (record range, file-name field, e-mail each PDF) | PARTIAL | PARTIAL | mail merge from CSV/XLSX; no record range / e-mail |
| 107 | Printer properties (presets, append, PDF version, security, metadata) | PARTIAL | OPEN | print service |
| 108 | Browser toolbar: append webpage to an existing PDF / send as e-mail attachment | PARTIAL | DONE | web tab "append to a file"; share for e-mail |
| 109 | Combine: Add Files / Add Open Files / Add Folder / Add WebPage / Add from Scanner / Add from Clipboard | PARTIAL | DONE | `CombineTab` sources: files, tabs, folder, web page, camera, clipboard |
| 110 | Combine: PDF portfolio input (each part extracted) | MISSING | OPEN | extract the portfolio first |
| 111 | Combine: Thumbnail view / List view | PARTIAL | PARTIAL | list view with titles and ranges |
| 112 | Combine: sort by name / modified / size / Bates | MISSING | DONE | `CombinePlus.sorted` (no Bates sort) |
| 113 | Combine: expand a file into its pages; move/delete single pages across files; zoom preview | MISSING | PARTIAL | page range per file; no page-level moves before combining |
| 114 | Combine: choose pages / sheets per file | MISSING | DONE | page range per file |
| 115 | Combine: edit the top-level bookmark title per file | MISSING | DONE | title field per file |
| 116 | Combine setting: add a TOC page from bookmarks (numbered, up to 5 levels, page numbers) | PARTIAL | DONE | `CombineOpts.toc` |
| 117 | Combine setting: output folder / overwrite / don't open after merging / close opened files | PARTIAL | PARTIAL | file name; output to the app folder |
| 118 | Combine setting: save as PDF Portfolio / convert all files to PDF for portfolio | PARTIAL | PARTIAL | separate portfolio tool; binder can attach files |
| 119 | Combine: e-mail attachment handling (don't include / attach all / convert and append) | PARTIAL | PARTIAL | EML attachments embedded |
| 120 | Recognize Text: Downsample To (DPI) | MISSING | DONE | OCR output downsample (feature/nitro-tools) |
| 121 | Recognize Text: Find All Suspects | PARTIAL | DONE | OCR review of suspects (feature/nitro-tools) |
| 122 | Recognize Text: recognise line segments as path objects (tables) | MISSING | OPEN |  |
| 123 | OCR component download (incl. CJK engine) | PARTIAL | DONE | all tessdata languages pinned (feature/nitro-tools) |
| 124 | Recognize Text > Multiple Files (add files/folders/open files, output options, overwrite) | PARTIAL | PARTIAL | OCR+ batch; no folder / tabs input |
| 125 | Recognize Text > Selected Region (zone OCR) | MISSING | DONE | zone OCR (ocr_zone) (feature/nitro-tools) |
| 126 | Watched Folder (Convert > Batch): monitored folder processed to a destination | MISSING | OPEN |  |

## 5. Scan & OCR  (15)  (fcmp_01-08.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 127 | Sides: both sides / reverse sides prompt | PARTIAL | DONE | `ScanExtras.duplex` |
| 128 | Paper size (A0-A10, B, C, JIS, Letter, Legal, Ledger, Business card…) | PARTIAL | DONE | A3 / A5 / B5 / Legal / card added |
| 129 | Output: Save multiple files (N pages each, name prefix) | PARTIAL | DONE | `ScanExtras.chunks` |
| 130 | Output: create a portfolio for all results | MISSING | DONE | Scan › portfolio of all results |
| 131 | Optimize scanned PDF: JPEG2000 / JBIG2 / CCITT G4 / High Compression (MRC) | MISSING | DONE | CCITT / JBIG2 / MRC optimizer (feature/nitro-tools) |
| 132 | Optimize scanned PDF: Deskew | PARTIAL | DONE | deskew of existing PDFs (feature/nitro-tools) |
| 133 | Optimize scanned PDF: background removal / descreen / text sharpening / adaptive compression | PARTIAL | PARTIAL | scan filters and MRC; no descreen (feature/nitro-tools) |
| 134 | Remove blank pages after scanning | MISSING | DONE | Scan › remove blank pages |
| 135 | Save as Preset / Manage presets (edit, delete, move, create) / one-click preset scan | PARTIAL | DONE | `ScanPreset` |
| 136 | Post-Scan tab: Optimize Scanned PDF | PARTIAL | DONE | optimizer presets (feature/nitro-tools) |
| 137 | Post-Scan tab: Adjust colour mode Grayscale / Black & White (existing PDF) | PARTIAL | PARTIAL | grey exists; TIFF 1-bit export; no PDF→B&W PDF |
| 138 | Post-Scan tab: Edit Document Information (Author, Title, File name) | PARTIAL | DONE | Scan › title / author |
| 139 | OCR PDFs and PDF portfolios | PARTIAL | OPEN | portfolio parts are not OCRed |
| 140 | Find OCR Suspects: red boxes in the document, Search Document / Page, Not Text, Accept and Find, click text to correct | PARTIAL | PARTIAL | OCR review dialog (feature/nitro-tools) |
| 141 | All Suspects dialog (correct many at once) | MISSING | PARTIAL | one-at-a-time review (feature/nitro-tools) |

## 6. Export PDF (Convert > Export)  (18)  (fcmp_01-08.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 142 | Export to Word 97-2003 (.doc) | MISSING | NOT-FEASIBLE | binary .doc |
| 143 | DOCX settings: include comments | MISSING | OPEN |  |
| 144 | DOCX settings: Restore Defaults | MISSING | DONE | options sheet "restore defaults" |
| 145 | Explorer "Convert to Microsoft Office" | PARTIAL | PARTIAL | share / open with the app |
| 146 | Export to XML Spreadsheet 2003 | MISSING | DONE | `pdf_xml2003` |
| 147 | XLSX settings: decimal / thousands separators (auto or chosen) | PARTIAL | PARTIAL | automatic separators |
| 148 | XLSX settings: worksheet per table | MISSING | DONE | `Opt.SHEET_PER_TABLE` |
| 149 | Export to XPS / .oxps | MISSING | DONE | `pdf_xps` (page pictures) |
| 150 | HTML settings: single page with navigation pane / multiple pages split by headings or bookmarks | MISSING | DONE | `pdf_html_set` |
| 151 | Export to Accessible Text (includes alt text, for braille) | MISSING | DONE | `pdf_access_txt` |
| 152 | Export to XML 1.0 | MISSING | DONE | `pdf_xml` |
| 153 | Export to PostScript / Encapsulated PostScript | MISSING | DONE | `pdf_ps` / `pdf_eps` (page pictures) |
| 154 | Export to images: TIFF | MISSING | DONE | `pdf_tiff` |
| 155 | Export to images: JPEG2000 / BMP | MISSING | PARTIAL | BMP done; JPEG 2000 not feasible (no encoder) |
| 156 | Image settings: PNG interlace / filter, JPEG format, JPEG2000 tile size, monochrome, ICC embedding | MISSING | PARTIAL | monochrome (TIFF 1-bit); no PNG interlace / ICC |
| 157 | Image settings: open the destination folder | PARTIAL | NOT-FEASIBLE | no "open folder" on Android |
| 158 | Export All Images: output format / colour space / resolution | PARTIAL | PARTIAL | native format kept |
| 159 | Export Selection As Word / Excel / PowerPoint (selected area) | MISSING | OPEN |  |

## 7. PDF Portfolio & PDF Binder  (23)  (fcmp_01-08.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 160 | View a portfolio: list of component files | PARTIAL | DONE | `PortfolioEditor` |
| 161 | Portfolio opened in the reader → switch to portfolio view (notification) | CODE-ONLY | DONE | reader banner → portfolio editor |
| 162 | View modes: Layout / Details (Name, Description, Size, Modified; choose columns) / Cover Sheet | PARTIAL | DONE | details / tiles, columns |
| 163 | Preview pane (Right / Bottom / Off) | MISSING | DONE | preview pane |
| 164 | Default View (Retain Original / Layout / Details / Cover Sheet) | MISSING | DONE | `PortfolioEdit.setView` |
| 165 | Read Mode / print / search across portfolio files | MISSING | PARTIAL | search across parts; no read mode / print |
| 166 | Create portfolio: add from folder / web page / scanner / clipboard | MISSING | PARTIAL | add files / folder; no web / scanner |
| 167 | Create portfolio: sort by name / modified / size | MISSING | DONE | create sort |
| 168 | Create a blank portfolio | MISSING | DONE | blank portfolio |
| 169 | Add files / folder / open files to an existing portfolio; create folders inside | MISSING | DONE | add files / folder / folders inside |
| 170 | Portfolio Table of Contents on the cover sheet (clickable, folders up to 5 levels) / Regenerate TOC | PARTIAL | DONE | `PortfolioOps.regenerateCover` (GoToE links) |
| 171 | Delete a component file | MISSING | DONE | delete part |
| 172 | Edit Value: Name / Description / custom field | MISSING | DONE | rename / description / custom values |
| 173 | Convert a component (multimedia) to PDF inside the portfolio | MISSING | DONE | `PortfolioOps.convertPart` |
| 174 | Reduce file size of selected component PDFs | MISSING | DONE | `PortfolioOps.compressParts` |
| 175 | Portfolio Properties: show/hide fields, add/delete fields, field order, sort field and order, initial view | MISSING | DONE | `setFields` / `setSort` / `setInitial` |
| 176 | Drag-drop to reorganise files/folders | MISSING | PARTIAL | move to folder from the menu (no drag) |
| 177 | Edit a component in its native app / edit PDFs in the preview and save back into the portfolio | MISSING | OPEN | parts open as copies |
| 178 | PDF Binder (Pro): multi-format merge with ordered folders, custom TOC, save as project (.bdr) / template (.bdrt) | MISSING | PARTIAL | binder projects with order and TOC; no folders inside the binder |
| 179 | Binder: TOC template (Word template with field codes <<DocName>>, custom fields) | MISSING | OPEN |  |
| 180 | Binder: custom metadata fields mapped from Word/Excel/PowerPoint properties | MISSING | OPEN |  |
| 181 | Binder: Convert-to-PDF column (or include as attachment) | MISSING | DONE | `CombineEntry.attach` |
| 182 | Binder: output options (name, folder, create/edit TOC, open after, show in folder) | PARTIAL | PARTIAL | name, TOC; output to the app folder |

## 8. Compliance - Preflight, PDF/A, PDF/E, PDF/X, PDF/UA, PDF/VT, PDF Versions  (29)  (fcmp_01-08.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 183 | Preflight: analyse a document against a profile | PARTIAL | DONE | `Standards.preflight` profiles |
| 184 | Preflight: page range ("Preflight only page…") | MISSING | DONE | page range |
| 185 | Profiles tab: profile groups, libraries, favourites (heart), category menu, find / search all libraries, single checks / single fixups view, double-click to run | MISSING | PARTIAL | profiles with favourites; no libraries / single fixups view |
| 186 | Print-production checks (plates, CxF, hairlines, overprint, black overprint, image ppi range, invisible text, ICC/Lab objects, rich black, spot colours, Courier, transparent objects) | MISSING | PARTIAL | hairlines, overprint, spot colours, ppi, invisible text, Courier; no plates / CxF / rich black |
| 187 | Fixup: convert fonts to outlines | MISSING | NOT-FEASIBLE | problem pages are rasterised instead |
| 188 | Create Report (PDF / XML / TXT / HTML; overview, details, problems highlighted by masks or layers) | PARTIAL | PARTIAL | HTML / TXT / XML; no PDF report with highlights |
| 189 | Preflight as Action Wizard step (copy file on success/error, create report) | PARTIAL | PARTIAL | recipe PDF/A step; no check-only step |
| 190 | Convert to PDF/A-1a / 1b | MISSING | DONE | PDF/A-1a / 1b |
| 191 | Convert to PDF/A-2a / 2u | MISSING | DONE | PDF/A-2a / 2u |
| 192 | Convert to PDF/A-3a / 3b / 3u (attachments allowed) | MISSING | DONE | PDF/A-3a / 3b / 3u |
| 193 | Convert to PDF/A-4 / 4e / 4f | MISSING | OPEN | PDF 2.0 output not supported by PdfBox 2.0 |
| 194 | PDF/A: outline non-Unicode text (2u) | MISSING | PARTIAL | pages with non-Unicode fonts are rasterised |
| 195 | PDF/A: remove PDF/A information (pdfaid) | MISSING | DONE | `Standards.removeClaims` |
| 196 | PDF/A: verify (ZUGFeRD) | MISSING | DONE | `Standards.zugferd` |
| 197 | PDF/A: verify conformance of a claimed file | PARTIAL | PARTIAL | approximate preflight, not veraPDF |
| 198 | PDF/E-1: convert / verify / remove | MISSING | PARTIAL | verify (approximate) / remove; no convert |
| 199 | PDF/X: convert to X-1a / X-3 / X-4 / X-6 for GRACoL, ISO Coated v2, Japan Color, PSO Coated v3, SWOP; X-4 with layers; X-5n | MISSING | NOT-FEASIBLE | no CMYK colour management |
| 200 | PDF/X: verify X-1a/3/4/4p/5g/5n/5pg/6/6n/6p; remove PDF/X info | MISSING | PARTIAL | PDF/X-4 checks (approximate) / remove |
| 201 | PDF/UA: verify PDF/UA-1 (syntax) | PARTIAL | PARTIAL | checker with 32 rules (not Matterhorn) |
| 202 | PDF/UA: fix tagging structure | PARTIAL | DONE | `AccessAutoTag` + tags editor |
| 203 | PDF/UA: mark as PDF/UA / remove PDF/UA info | CODE-ONLY | DONE | mark / remove PDF/UA |
| 204 | PDF/VT: verify VT-1 / VT-2 | MISSING | PARTIAL | VT claim shown; no verification |
| 205 | PDF version compatibility checks (1.2-1.7) | PARTIAL | DONE | VERSION profile (`Standards.minVersion`) |
| 206 | Standards wizard (choose standard → version → options → Save as) | PARTIAL | DONE | level / UA / options → save |
| 207 | Save As PDF/A / PDF/E / PDF/X (file type + Settings…, non-compliance message suggests Preflight) | PARTIAL | PARTIAL | through the PDF/A screen; no PDF/E / PDF/X output |
| 208 | PDF/A, PDF/E, PDF/X output at creation (Create PDF / scan / printer) | PARTIAL | PARTIAL | PDF/A at scan / combine; no PDF/E / PDF/X |
| 209 | Standards panel in the navigation pane (auto-shown for PDF/A/E/X/UA files: conformance, ISO name, verification status, Verify Conformance, Open Preflight) | MISSING | DONE | standards card + reader banners |
| 210 | Standards panel: OutputIntent (colour settings) | PARTIAL | PARTIAL | intent count reported; profile not shown |
| 211 | PDF version choice 1.3-1.7 when creating / optimising | MISSING | DONE | save as PDF version 1.4–1.7 |

## 17. Page thumbnails panel  (3)  (fcmp_14-21_23.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 212 | Reorganize pages from the thumbnails panel | PARTIAL | PARTIAL | Organize screen (reader panel itself unchanged) |
| 213 | Enlarge / Reduce page thumbnails | MISSING | DONE | Organize zoom |
| 214 | Embed All Page Thumbnails | MISSING | DONE | embed / remove thumbnails |

## 23. Organize tab: page operations and page marks  (45)  (fcmp_14-21_23.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 215 | Insert from File: several files, move up/down, show preview | MISSING | DONE | Organize › INSERT list with up / down |
| 216 | Insert from non-PDF files (converted on the fly) | PARTIAL | DONE | Organize › INSERT other files (any_pdf) |
| 217 | Bookmarks and attachments of an inserted whole file are kept | MISSING | DONE | `PageOrganize.insertFile` bookmarks + attachments |
| 218 | Insert by dragging thumbnails from another document | MISSING | PARTIAL | drop files from other apps; not thumbnails from another document |
| 219 | Insert > From Folder | MISSING | OPEN | Organize insert: no folder source (Combine has one) |
| 220 | Insert > From Open Files | MISSING | DONE | from open tabs |
| 221 | Insert > From Clipboard | MISSING | PARTIAL | clipboard text |
| 222 | Insert > From Web Page | PARTIAL | DONE | from web page (url_pdf) |
| 223 | Insert > Into File (insert a range of this PDF into other target files, keep source, output options) | MISSING | DONE | `PageOrganize.insertInto` |
| 224 | Thumbnail zoom (Ctrl + wheel) | MISSING | DONE | zoom buttons |
| 225 | Multi-page rotate dialog (direction + page range) | PARTIAL | DONE | ROTATE dialog |
| 226 | Move Pages dialog (target location + page range) | PARTIAL | DONE | MOVE |
| 227 | Swap Two Pages | MISSING | DONE | SWAP |
| 228 | Delete: typed range + subset (even / odd / landscape / portrait) | MISSING | DONE | DELETE range + subset |
| 229 | Delete blank pages | MISSING | DONE | DELETE_BLANK |
| 230 | Extract: subset (even / odd / landscape / portrait) | MISSING | DONE | EXTRACT with subset |
| 231 | Extract modes: one PDF / several PDFs by "," / each page as a PDF | PARTIAL | DONE | ExtractMode ONE / GROUPS / EACH |
| 232 | Extract: delete the pages after extraction | MISSING | DONE | delete after extract |
| 233 | Alternate Mix (interleave more than two files, swap positions, reverse each) | PARTIAL | DONE | MIX (N files, reverse each) |
| 234 | Replace from a non-PDF file | MISSING | OPEN |  |
| 235 | Split Document: several files at once (Select More Files) | MISSING | OPEN | one file at a time |
| 236 | Split Document: output options (location, names) | PARTIAL | PARTIAL | naming; output to the app folder |
| 237 | Split Pages (tiles): dividing lines H/V, drag lines in preview, page range | PARTIAL | DONE | any columns × rows, slider per cut |
| 238 | Crop: Show All Boxes preview | MISSING | DONE | Crop "show all page boxes" |
| 239 | Crop: Constrain Proportions (equal margins) | MISSING | DONE | Crop "constrain proportions" |
| 240 | Crop: page size and centering inside the crop dialog; margin unit choice | PARTIAL | PARTIAL | units pt / mm / in; centring via Resize |
| 241 | Resize Pages: Constrain Proportions | MISSING | DONE | Resize "constrain proportions" |
| 242 | Resize Pages: multiple files (batch, output options) | MISSING | DONE | Resize batch files |
| 243 | Flatten (annotations and form fields into content) with a page range | PARTIAL | DONE | FLATTEN range |
| 244 | Reverse pages: range + subset | PARTIAL | DONE | REVERSE with subset |
| 245 | Rearrange pages to match the bookmark order | MISSING | DONE | BOOKMARK_ORDER |
| 246 | Header & Footer templates: save, delete, import/export, administrator templates | PARTIAL | DONE | header/footer profiles (feature/nitro-tools) |
| 247 | Header & Footer batch add (files, folders, open files, output options) | MISSING | DONE | header/footer batch (feature/nitro-tools) |
| 248 | Header & Footer / Watermark / Background: batch Remove All (with log file) | MISSING | DONE | batch remove with log (feature/nitro-tools) |
| 249 | Background / Watermark from a PDF page (choose the page of a multi-page file) | MISSING | DONE | PDF page as background / watermark (feature/nitro-tools) |
| 250 | Background / Watermark templates (save, import/export, admin) | PARTIAL | DONE | background / watermark profiles (feature/nitro-tools) |
| 251 | Background / Watermark batch add (several files) | PARTIAL | DONE | batch add (feature/nitro-tools) |
| 252 | Bates: Add Folders / Add Open Files / sort list by name, location, size, date | MISSING | DONE | Bates sources and sort (feature/nitro-tools) |
| 253 | Bates: output options (target folder, keep / add / replace name with the Bates range, overwrite, log file) | PARTIAL | DONE | Bates output options (feature/nitro-tools) |
| 254 | Bates: digits 3–15, start number, prefix, suffix | PARTIAL | DONE | Bates digits / start / prefix / suffix (feature/nitro-tools) |
| 255 | Bates: increment (negative reverses order) | MISSING | DONE | Bates increment (feature/nitro-tools) |
| 256 | Bates: rotate angle | MISSING | DONE | Bates rotation (feature/nitro-tools) |
| 257 | Bates Number macro inside Header & Footer | PARTIAL | DONE | Bates macro in header/footer (feature/nitro-tools) |
| 258 | Bates > Remove All | CODE-ONLY | DONE | Bates remove all (feature/nitro-tools) |
| 259 | Page labels shown in the reader (indicator, go to page by label) | CODE-ONLY | DONE | page labels in indicator and go-to dialog |

## 22. Articles, Layers, Spell Check, Document Properties & metadata  (23)  (fcmp_09-13_22.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 260 | Image Editor Layers palette (opacity, fill, blending mode) | MISSING | OTHER-SCOPE | image editor (nve) |
| 261 | Articles: define article boxes (Add Article Box, numbering, end with Enter) | MISSING | OTHER-SCOPE |  |
| 262 | Articles: Article Properties (Title, Subject, Author, Keywords) | MISSING | OTHER-SCOPE |  |
| 263 | Articles panel (list, rename, delete, Hide After Use) | MISSING | OTHER-SCOPE |  |
| 264 | Read Article (follow thread, auto zoom, next/previous box) | MISSING | OTHER-SCOPE |  |
| 265 | Modify articles: move/resize box, delete box/article, renumber, insert boxes, combine articles | MISSING | OTHER-SCOPE |  |
| 266 | Automatic spell check while typing (text edit, typewriter, textbox, callout, note pop-ups) | PARTIAL | OTHER-SCOPE |  |
| 267 | Check Spelling (whole document dialog: Ignore, Ignore All, Change, Add to Dictionary) | MISSING | OTHER-SCOPE |  |
| 268 | Dictionary management (custom dictionary, add/remove/reorder) | MISSING | OTHER-SCOPE |  |
| 269 | Layers panel: show/hide, Select All | MISSING | DONE | show / hide, show all |
| 270 | Layers: List for All Pages / Visible Pages, Expand/Collapse All | MISSING | PARTIAL | pages per layer, expand / collapse groups; no "visible pages only" filter |
| 271 | Layers: Reset to Initial Visibility | MISSING | DONE | reset |
| 272 | Layers: Apply Print / Export / Layer Overrides | MISSING | DONE | print / export overrides |
| 273 | Import as Layer (PDF/images; new layer / existing / copy source layers; position) | MISSING | DONE | import picture / PDF page as layer |
| 274 | Merge Layers / Flatten Layers | MISSING | DONE | merge / flatten |
| 275 | Delete Layer / Reorder Layers | MISSING | DONE | delete / reorder |
| 276 | Layer Properties: name, Intent (View/Reference), Default State, Visibility, Print, Export | MISSING | DONE | properties incl. intent and usage |
| 277 | Additional Metadata (XMP): Append from an .xmp file / Save to an .xmp file | MISSING | OTHER-SCOPE |  |
| 278 | Initial View: honoured when SmartDocs opens the file | MISSING | DONE | initial view on first open (`applyInitialView`, pref) |
| 279 | Define initial view as Full Screen (presentation setup) | PARTIAL | DONE | /PageMode FullScreen honoured on open |
| 280 | Security tab (permissions summary) | PARTIAL | OTHER-SCOPE |  |
| 281 | Fonts tab (fonts, types, encoding) | PARTIAL | OTHER-SCOPE |  |
| 282 | Advanced: Print presets: Paper Source by Page Size, Pages (print range), Number of Copies | MISSING | OTHER-SCOPE |  |

## 34. Accessibility  (32)  (fcmp_24-27_34.md)

| # | Row | Was | Now | Where / note |
|---|---|---|---|---|
| 283 | Checker options: choose rule categories or items; page range | MISSING | PARTIAL | rule categories; no page range |
| 284 | Attach the report to the document | MISSING | DONE | attach report |
| 285 | Result statuses: Passed, Failed, Needs manual check, Skipped, Passed/Failed manually | PARTIAL | DONE | pass / fail manually, skip |
| 286 | Context menu: Fix (per rule) | PARTIAL | PARTIAL | fix from the rule menu (fix dialog) |
| 287 | Context menu: Skip Rule / Explain / Pass / Fail / Show in Tags Panel / Options | MISSING | DONE | skip / explain / pass / fail |
| 288 | Check Again | PARTIAL | DONE | check again |
| 289 | Rules: Screen flicker, Timed responses | MISSING | DONE | flicker / timed rules |
| 290 | Fix: bookmarks from structure elements (choose elements) | PARTIAL | PARTIAL | bookmarks from headings (automatic) |
| 291 | Fix: character encoding (re-embed fonts) | MISSING | NOT-FEASIBLE | font programs not available |
| 292 | Fix: tagged content / annotations / multimedia (Create Artifact, Reading Order) | PARTIAL | DONE | auto-tag artifacts, tag annotations |
| 293 | Fix: nested alt text / alt text that hides annotations (remove alt) | MISSING | PARTIAL | alt text editable in the tags editor; no automatic fix |
| 294 | Fix: tables (TR/TH/TD structure, headers, regularity, summary), lists (Lbl/LBody), heading nesting | MISSING | PARTIAL | list / heading recognition; table attributes editable; no table recognition |
| 295 | Accessibility Setup Assistant (wizard for default accessibility preferences) | MISSING | OPEN |  |
| 296 | Reading Option (per-document reading order and mode for screen readers) | MISSING | OPEN |  |
| 297 | Screen reader support (TalkBack) | PARTIAL | DONE | 41 icon buttons labelled |
| 298 | Autotag Document (re-tag prompt if already tagged) | PARTIAL | DONE | re-tag with recognition |
| 299 | Autotag Form Field (separate command) | PARTIAL | DONE | "tag comments and links" |
| 300 | Recognition Report panel (after auto-tag) | MISSING | DONE | recognition report |
| 301 | Edit alt text of one figure from the page (Reading Order mode) | PARTIAL | PARTIAL | alt text in the figures list and tags editor; not on the page |
| 302 | Reading Order (Touch Up) tool: tag regions as Text, Figure, Form Field, Figure/Caption, Heading 1-6, Table, Cell, Formula, Reference, Note, Background/Artifact | MISSING | OPEN |  |
| 303 | Show page content groups (order / structure types), show table cells, show tables and figures, like elements in one box | MISSING | OPEN |  |
| 304 | Table Editor (cell properties, auto header IDs, highlight colour) | MISSING | OPEN |  |
| 305 | Edit Form Field Text / Edit Table Summary dialogs | MISSING | PARTIAL | table summary editable in the tags editor |
| 306 | Clear Page Structure (one page / all pages) | MISSING | DONE | `AccessTags.clearPage` / re-tag |
| 307 | Order panel: view/reorder the reading order by drag, cut/paste, renumber | MISSING | PARTIAL | move / indent in the tags tree; no drag |
| 308 | Set / Change / Apply Area Reading Order (draw areas, click to order) | MISSING | OPEN |  |
| 309 | Content panel: containers, cut/paste, New Container, Create Artifact, Save Selection As, Edit Container/Page/Catalog dictionary, Audit Space Usage, Show Metadata, Properties | MISSING | OPEN |  |
| 310 | Tags panel: tree view, expand/collapse, tag text size | MISSING | DONE | tags tree, expand / collapse |
| 311 | Tags panel ops: New Tag, cut/paste/paste child, Delete, Delete Empty Tags, Find Tag / Create Tag from Selection, Copy contents, Class Map, Role Map, Tag Annotations, "Document is Tagged PDF" flag, role mapping 1.7/2.0 | MISSING | DONE | new / delete / delete empty / role map / tagged flag / tag annotations |
| 312 | Find Element (artifacts, OCR suspects, unmarked comments/links/annotations/content; page/document; Tag Element) | MISSING | PARTIAL | unmarked annotations; no OCR suspects / content |
| 313 | Object Properties > Tag tab (alt text, language, title, Edit Attribute Objects) | PARTIAL | PARTIAL | tag properties in the tags editor |
| 314 | Table ColSpan / RowSpan attribute objects | MISSING | DONE | RowSpan / ColSpan attributes |

## Old plan and pdf-ad notes

| Item | Now | Where |
|---|---|---|
| K3 edit a saved scan later | DONE | `ScanProjects` ("Saved scans" in the scan studio) |
| K7 dewarp / page curvature | NOT-FEASIBLE | needs a learned dewarping model |
| K8 eraser for scanned pictures | DONE | `PageEdit.erase`, eraser in `ScanPageEditor` |
| K12 deskew + despeckle | DONE | deskew (feature/nitro-tools), `ScanClean.despeckle` |
| K13 wire `openOcrForPage` | DONE | reader menu "OCR this page" |
| A5 page labels in the reader | DONE | `PageIndicator` / `GoToPageDialog` |
| A6 / FILE-2 / FILE-3 /Direction, OpenAction, PageLayout, PageMode on open | DONE | `ReaderState.applyInitialView` (pref `doc_view_first_open`) |
| Dead bookmarks after delete | DONE | `PageOrganize.pruneOutline` (children move up) |
| Delete blank pages | DONE | Organize › DELETE_BLANK |
| Insert file keeps bookmarks and attachments | DONE | `PageOrganize.insertFile` |
| AI alt text must see the image (`T5Hooks.describeImage`) | DONE | `AltTextVision.install` (vision route, page-text fallback) |
| PDF/UA flag (`uaPart` code-only) | DONE | `AccessUa.markUa`, `PdfAOptions.uaPart` |
| ~35 null contentDescriptions | DONE | 41 icon-only buttons labelled (`pc_cd_*`) |
| Foxit 34 extras (rules + range, manual verdicts, attach report, flicker / timed, tags / order panels, clear structure, find element, recognition report, autotag headings / lists / tables) | PARTIAL | all except checker page range, drag order / content panels, table recognition (see section 34) |
| verify_A: thumbnail embedding | DONE | `embedThumbnails` |
| verify_A: delete by range outside the AI agent | DONE | Organize › DELETE |
| verify_A: Word → PDF headings → bookmarks | PARTIAL | Combine option "convert headings to bookmarks"; the hub's word_pdf card is unchanged (core/convert6 was kept out of scope during the convert audit) |
| Cancellable converters (pdf-ad request) | DONE | every new page loop checks `ensureActive` |

