# feature/nitro-pages-convert log

Scope (from pdf-ad): Nitro 4 Pages, 5 Create/Combine, 6 Convert, 18 Layers, 19 Accessibility, 20 PDF/A; Foxit 4, 5 (scanner rows),
6, 7, 8, 17, 22, 23a, 34; old plan K3/K7/K8/K12/K13, A5, A6/FILE-2/3. Stages: A pages/organize + reader, B create/combine + scanner,
C export, D portfolio/binder, E layers, F accessibility, G PDF/A + compliance. One push and a green CI per stage.

Strings: `strings_pc.xml` (prefix `pc_`). Tests: `PageOrganizeTest`.

## Stage A — pages / organize + reader

| Row | Status | Where |
|---|---|---|
| Delete pages: range + odd/even/portrait/landscape | DONE | `PageOrganizeScreen` DELETE → `PageOrganize.delete` |
| Dead bookmarks / links after delete (children kept) | DONE | `PageOrganize.pruneOutline` / `pruneLinks`; `PdfOps.deletePages` uses `deleteIn` |
| Delete blank pages | DONE | DELETE_BLANK → `PageOrganize.deleteBlank` |
| Rotate 90/180/270 with range + subset | DONE | ROTATE → `PdfOps.rotatePages` |
| Move a range before a page | DONE | MOVE → `moveOrder` |
| Swap two pages | DONE | SWAP → `swapOrder` |
| Reverse with range + subset | DONE | REVERSE → `PageOps5.reverse` |
| Extract: one file / per group / per page, delete after, keep navigation | DONE | EXTRACT → `writeParts`, `rangeGroups` |
| Insert several files in a chosen order, bookmarks (under a holder item) + attachments | DONE | INSERT → `insertFile`; `PdfOps.insertPagesFrom` delegates |
| Insert from open files (tabs), clipboard, web page, non-PDF files | DONE | INSERT sources → `Cv6Engine` `clipboard_pdf` / `url_pdf` / `any_pdf` |
| Insert pages into other files (start / end / after page) | DONE | INSERT_INTO → `insertInto` |
| Split at bookmarks of level N, file naming, keep navigation | DONE | SPLIT_BOOKMARKS → `splitByBookmarks`, `PartName` |
| Split by ranges with naming | DONE | SPLIT_RANGES → `writeParts` |
| Rearrange pages to the bookmark order | DONE | BOOKMARK_ORDER → `bookmarkOrder` |
| Alternate mix of more than two files, reverse each | DONE | MIX → `mixOrder` / `mix` |
| Flatten a page range | DONE | FLATTEN → `flattenPages` |
| Embed / remove page thumbnails | DONE | THUMBS → `embedThumbnails` / `removeThumbnails` |
| A5 page labels in the indicator and go-to dialog | DONE | `PageIndicator` / `GoToPageDialog` take `labels` |
| A6 / FILE-2 / FILE-3 /Direction, OpenAction, PageLayout, PageMode on open | DONE | `ReaderState.applyInitialView`, `InitialViewMap` |
| K13 OCR this page from the reader | DONE | reader menu → `QuickController.openOcrForPage` |
| Organize: thumbnail zoom | DONE | `OrganizeScreen` zoom buttons (`cellScale`) |
| Organize: drop PDFs / images dragged from another app | DONE | `OrganizeScreen` `dragAndDropTarget` → insert dialog |
| Organize: per-thumbnail menu (rotate, delete, print, redact, OCR) | DONE | `PageGrid` `pageActions` |
| Crop: show all page boxes, constrain proportions, pt / mm / in | DONE | `CropScreen`, `BoxTools.pageBoxes` / `constrain`, `BoxUnit` |
| Resize: A0/A1/A2, units, constrain proportions, batch files, reset (undo) | DONE | `ResizeScreen`, `PageBoxOps.PAPERS` |
| Split page: any columns × rows, one slider per cut line | DONE | `PageOpsScreen` SPLIT_PAGE, `BoxTools.evenCuts` / `moveCut` (also fixes the old preview drawing a horizontal cut mirrored) |

## Stage B — create / combine + scanner

| Row | Status | Where |
|---|---|---|
| Combine sources: files, folder, open files (tabs), web page, clipboard (text or image), camera scan | DONE | `ui/tools5/CombinePlusUi.kt` `CombineTab` |
| Combine: page range per file | DONE | `CombineEntry.pages` → `CombinePlus.combine` |
| Combine: edit each file's bookmark title | DONE | `CombineTab` title field |
| Combine: sort by name (natural) / date / size | DONE | `CombinePlus.sorted`, `NaturalOrder` |
| Combine: keep the files' own bookmarks and attachments | DONE | `CombineOpts.keepBookmarks` (`PageOrganize.copyOutline` / `copyAttachments`) |
| Word / HTML / text: convert headings to bookmarks | DONE | `CombineOpts.headings` (`OutlineTools.detectHeadings`, nested by `CombinePlus.addHeadings`) |
| Combine: TOC page from the bookmarks | DONE | `CombineOpts.toc` → `OutlineTools.insertToc` |
| Office: preserve document properties (title, author, subject, keywords) | DONE | `OfficeProps` in `ToPdf.convert` |
| Web page: paper, orientation, margins | DONE | `WebPdfWriter.Setup`, `WebOptionsCard` |
| Web page: page-load timeout (convert what loaded) | DONE | `Setup.timeoutMs` |
| Web page: images on/off, background graphics, underline links, disable links | DONE | `WebCss.build` (injected CSS), `WebCss.stripLinks` |
| Web page: append to an existing PDF | DONE | `appendIfAsked` |
| Scan paper sizes (A3, A5, B5, Legal, card) | DONE | `ScanPaper`, `ScanExtras.pageRect` |
| Scan: remove blank pages after scanning | DONE | `ScanStudioScreen` → `PageOrganize.deleteBlank` |
| Scan: save multiple files, N pages each | DONE | `ScanExtras.chunks` |
| Scan: document title / author at save | DONE | `ScanExtras.setInfo` |
| Scan: portfolio of all results | DONE | `Portfolio.create` after a multi-file scan |
| Scan: both sides (fronts, then backs, reversed or not) | DONE | `ScanExtras.duplex`, duplex card |
| Scan: save / apply / delete presets | DONE | `ScanPreset` (prefs `t5_scan` "presets") |
| K3 edit a saved scan later | DONE | `ScanProjects` (pictures + edits kept, "Saved scans" card) |
| K8 eraser for scanned pictures | DONE | `PageEdit.erase`, `ScanClean.erase`, eraser in `ScanPageEditor` |
| K12 despeckle | DONE | `PageEdit.despeckle`, `ScanClean.despeckle` (small connected components) |
| K7 page-curvature (dewarp) | NOT-FEASIBLE | needs a learned dewarping model; ML Kit's scanner only crops / corrects perspective |
| Publisher / Visio / WordPerfect / HWP / CAD / XPS input | NOT-FEASIBLE | no pure-Java reader for these formats |
| Web: crawl N levels / whole site | OPEN | |
| Printer-time PDF settings (version, security, metadata, append) | OPEN | Android print dialog owns the job |
| Auto-detect form fields | OPEN | |

## Stage C — export

| Row | Status | Where |
|---|---|---|
| Export to TIFF / multi-page TIFF (colour, grey, black & white) | DONE | `pdf_tiff` → `TiffWriter` (PackBits), `Export7Run.tiff` |
| Export to BMP | DONE | `pdf_bmp` → `BmpWriter` |
| Export to PostScript / EPS | DONE | `pdf_ps` / `pdf_eps` → `PsWriter` (page pictures, DCT + ASCII85) |
| Export to XPS | DONE | `pdf_xps` → `XpsWriter` (page pictures) |
| Export to XML 1.0 | DONE | `pdf_xml` → `PdfXml` (info, bookmarks, lines with position / size / font) |
| Export to accessible text (alt text) | DONE | `pdf_access_txt` → `Export7Run.accessibleText` (figure / formula alt text from the tags) |
| HTML: multiple pages split at chapters with a navigation page | DONE | `pdf_html_set` → `HtmlSet` (zip) |
| Excel XML Spreadsheet 2003; worksheet per table | DONE | `pdf_xml2003` → `Xml2003`, `Opt.SHEET_PER_TABLE` |
| Image settings: monochrome | DONE | `Opt.BW` (TIFF 1-bit, Otsu) |
| Options sheet: restore defaults | DONE | `Cv6OptionsSheet` |
| Word 97-2003 .doc | NOT-FEASIBLE | binary format, no writer; RTF export exists (Word opens it) |
| JPEG 2000 export | NOT-FEASIBLE | no JPEG 2000 encoder on Android / in PdfBox |
| DOCX include comments; Word text-box layout; anchoring; character spacing | OPEN | converter internals (core/convert) |
| Text: line width / discard headers & footers | OPEN | |
| Export a selected area as Word / Excel | OPEN | |
| Per-file page range / output folder in batch | OPEN | |

## Stage D — portfolio / binder

| Row | Status | Where |
|---|---|---|
| Portfolio opened in the reader → portfolio view | DONE | reader banner (`NveDocBanners` PORTFOLIO) → tool `portfolio` → `PortfolioEditor` |
| View modes (details / tiles), default view (details / layout / cover sheet) | DONE | `PortfolioEditor`, `PortfolioEdit.setView`, `Portfolio.create(view)` |
| Preview pane | DONE | `PortfolioEditor` preview card (PDF first page, images) |
| Search across portfolio files | DONE | `PortfolioOps.search` (hits open the part at that page) |
| Create: blank portfolio, sort, default view | DONE | `PortfolioScreen` create card |
| Add files / a folder (with sub-folders) to an existing portfolio; create folders | DONE | `PortfolioEdit.add` / `ensureFolder` (/Folders + "<id>" keys) |
| Delete a component; rename; edit description; custom field values | DONE | `PortfolioEdit.remove` / `rename` / `setDescription` / `setValue` (/CI) |
| Portfolio properties: fields (add / delete / order / show), sort field and order, initial file | DONE | `PortfolioEdit.setFields` (/Schema), `setSort` (/Sort), `setInitial` (/D) |
| Move files between folders | DONE | `PortfolioEdit.move` (menu "move to folder"; no drag) |
| Convert a component to PDF inside the portfolio | DONE | `PortfolioOps.convertPart` |
| Reduce file size of component PDFs | DONE | `PortfolioOps.compressParts` |
| Cover sheet table of contents (clickable, by folder) / regenerate | DONE | `PortfolioOps.regenerateCover` (GoToE links) |
| Binder: ordered files, TOC, save / reopen as a project | DONE | `CombineTab` binder card, `BinderProject` / `BinderStore` |
| Binder: include a file as an attachment instead of converting | DONE | `CombineEntry.attach` |
| Edit a part and save it back into the portfolio | OPEN | parts open as extracted copies |
| Binder: Word TOC template with field codes; metadata columns from Office properties | OPEN | |

## Stage E — layers

| Row | Status | Where |
|---|---|---|
| Layers pane: show / hide (with preview), show all / hide all | DONE | `LayersScreen` (tool `e5_layers`), `Layers.setOn` / `showAll`; reader INFO › Layers links to it |
| Reset to initial visibility | DONE | `Layers.reset` (states captured on open) |
| Expand / collapse (groups of the layer order) | DONE | `Layers.list` depth / group from /Order |
| Layers listed with the pages they appear on | DONE | `Layers.pagesOf` |
| Layer properties: name, intent (View / Design), default state, locked | DONE | `Layers.rename` / `setIntent` / `setOn` / `setLocked` |
| Visibility / Print / Export usage (when visible / never / always) | DONE | `Layers.setUse` (/Usage + /D /AS) |
| Apply print / export overrides | DONE | `Layers.applyOverrides` |
| Set OCG state action | DONE | `Layers.setStateAction`, "bookmark that shows only this layer" |
| Import as layer (picture / PDF page; page range) | DONE | `Layers.importImage` / `importPage` |
| Merge layers / flatten layers / delete layer / reorder | DONE | `Layers.merge` / `flatten` / `delete` (content via the sanitizer's hidden-layer pass) / `move` |
| New layer | DONE | `Layers.create` |
| Image-editor layers palette (opacity, blending) | OPEN | belongs to the image editor (nve) |

## Stage F — accessibility

| Row | Status | Where |
|---|---|---|
| Auto-tag with headings (H1–H3), lists (L/LI/LBody), figures, artifacts | DONE | `AccessAutoTag.tag` (one tag per text object, `classify`) |
| Re-tag an already tagged document | DONE | `AccessAutoTag.tag(retag = true)` (old MCIDs dropped) |
| Recognition report after auto-tag | DONE | `AccessAutoTag.Report`, report dialog |
| Tags panel: tree, expand / collapse, properties | DONE | `TagsEditor` / `AccessTags.tree` |
| New tag (root / child / sibling), delete, delete empty, move, indent / outdent, change type, mark as artifact | DONE | `AccessTags` |
| Tag properties: Title, Alt, ActualText, Lang, Expansion, ID; Scope / RowSpan / ColSpan / Headers / Summary; list numbering | DONE | `AccessTags.setProps` / `setAttrs` |
| Role map (view / add / remove) | DONE | `AccessTags.roleMap` / `setRole` |
| "Document is tagged PDF" flag | DONE | `AccessTags.setTaggedFlag` |
| Clear page structure (one page; all pages = re-tag) | DONE | `AccessTags.clearPage`, `AccessAutoTag.clearStructure` |
| Find unmarked annotations / links; tag annotations | DONE | `AccessTags.unmarked` / `tagAnnotations` |
| Mark as PDF/UA-1 (XMP pdfuaid, MarkInfo, DisplayDocTitle) | DONE | `AccessUa.markUa` (fix dialog), checker shows the flag |
| AI alt text sees the figure | DONE | `AltTextVision.install` → `T5Hooks.describeImage` (vision route; page-text prompt as fallback) |
| Checker: rule categories | DONE | group chips → `AccessRules.apply` |
| Checker: pass / fail manually, skip rule, explain, check again | DONE | rule menu, `pc_accx_*` explanations |
| Attach the report to the document | DONE | "attach report" (embedded HTML) |
| Rules: screen flicker, timed responses | DONE | `flicker` / `timed` (manual when media / scripts are present) |
| TalkBack: icon-only buttons without a description (41) | DONE | `pc_cd_*` labels across ui/ |
| Checker page range | OPEN | facts are counted over the whole document |
| Reading order panel by drag; area reading order; Touch-up tool on the page | OPEN | reorder works in the tags tree (move / indent) |
| Table editor on the page; content panel; recognition of tables | OPEN | |
| Embed fonts (WCAG) | NOT-FEASIBLE | the original font programs are not available |
| Accessibility setup assistant; per-document reading option | OPEN | |

## Stage G — PDF/A + compliance

| Row | Status | Where |
|---|---|---|
| Convert to PDF/A-1a / 1b / 2a / 2b / 2u / 3a / 3b / 3u | DONE | `PdfAOptions.level` → `PdfA.convert` (1: no transparency / layers, v1.4; u/a: Unicode, rasterise others; a: auto-tag; 3: attachments with /AFRelationship + /AF) |
| PDF/UA flag together with PDF/A | DONE | `PdfAOptions.uaPart` → `XmpWriter.packet(uaPart)` |
| Self-check per level | DONE | `Standards.levelReport` |
| Preflight profiles (PDF/A-1b, 2b, 2u, 3b, PDF/X-4, PDF/E-1, print, version, Factur-X) with favourites | DONE | `Standards.preflight`, profile chips (hearts in prefs `pc_preflight`) |
| Preflight page range | DONE | `Standards.preflight(pages)` |
| Print-production checks: hairlines, overprint, spot colours, image ppi, invisible text, Courier | DONE | `Standards.printFacts` |
| Create report: HTML / TXT / XML | DONE | `Standards.reportText` (share) |
| Standards panel: what the file claims (PDF/A, UA, X, E, VT, Factur-X) | DONE | `Standards.claims`, PDF/A screen card; reader banners PDF/A / PDF/UA → Verify (`NveDocBanners`) |
| Remove PDF/A / PDF/UA / PDF/X / PDF/E information | DONE | `Standards.removeClaims` |
| PDF version compatibility check and save as 1.4–1.7 | DONE | `Standards.minVersion` (VERSION profile), version chips |
| PDF/X verify (claim, GTS_PDFX intent, fonts, TrimBox, Trapped) | DONE | `PreflightProfile.PDFX_4` (approximate) |
| PDF/E verify | DONE | `PreflightProfile.PDFE_1` (approximate) |
| ZUGFeRD / Factur-X verify | DONE | `Standards.zugferd` |
| PDF/A output at creation (scan, combine) | DONE | Scan studio "save as PDF/A" (2b), combine "save as PDF/A" (3b) |
| Convert to PDF/X (CMYK, output conditions) | NOT-FEASIBLE | no colour management / CMYK conversion on device |
| PDF/A-4 / 4e / 4f, PDF/VT | OPEN | PDF 2.0 output not supported by PdfBox 2.0; VT claim is only shown |
| Formal validation (veraPDF / Matterhorn level) | NOT-FEASIBLE | checks are approximate, as stated in the screen |
| Fixup: fonts to outlines | NOT-FEASIBLE | problem pages are rasterised instead |
