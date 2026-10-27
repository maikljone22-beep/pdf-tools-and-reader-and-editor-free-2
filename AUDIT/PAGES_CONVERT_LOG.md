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
| Organize thumbnail zoom, drag from other apps, per-thumbnail redact/OCR/print | OPEN | next push |
| Crop: all boxes, constrain proportions, units; resize A2/A1, batch | OPEN | next push |
| Split-page tiles with arbitrary counts and draggable lines | OPEN | next push |
