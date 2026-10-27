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
