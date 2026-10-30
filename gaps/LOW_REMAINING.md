# FINAL_CHECK_2 — Low rows still open (verified against main b725b67, 2026-10-30)

Of 169 Low rows (duplicates across parts counted per part): 105 DONE, 22 PARTIAL, 42 MISSING. Everything in edit / files / AI-office is done. Open rows, by area (where it plugs in):

## pages/convert
- [ ] Resize: reset to the original size after save — OrganizeState / PdfOps
- [ ] Create PDF: font embedding options; image codec / downsample options — CreatePreset, Cv6OptionsSheet (PARTIAL: presets only)
- [ ] Page size for text / markdown sources — Cv6Catalog text_pdf / md_pdf (no PAGE_SETUP), ToPdf6
- [ ] PowerPoint → PDF: slide frames / handouts — ToPdf6 ppt path (PARTIAL: bookmarks, notes, hidden slides, range done)
- [ ] HTML → PDF: header / footer text — Cv6Catalog html_pdf, WebPdfWriter
- [ ] Print service: target / append, PDF version, auto-open — ShareAndPrint (PARTIAL: PDF/A, security, watermark via the printer recipe)
- [ ] PDF/A switch in To-PDF "each file" tab and print service — ToPdfPlusScreen, ShareAndPrint
- [ ] Keep original names / locations for multi-file output — ToPdfPlusScreen, OutputFolder (PARTIAL: via recipe / watched folder)
- [ ] Web capture: conversion levels, whole site, crawl N levels — CombinePlusUi WebOpts, WebPdfWriter
- [ ] Web capture: header / footer on a new page — same
- [ ] Web capture: screen / print media, scaling — CombinePlusUi WebOpts, WebCss
- [ ] Templates: search, categories, online library — ui/templates, core/templates
- [ ] Word → PDF: comments → notes; footnote / endnote markers as links — DocxReader, OfficeHtml, ToPdf6
- [ ] Mail merge: record range; e-mail each PDF — MailMerge, MergePortfolioScreens
- [ ] Virtual printer: PDF version, append to an existing PDF — ShareAndPrint (no version recipe step)
- [ ] Combine: unpack portfolio parts as input — CombinePlus, CombinePlusUi
- [ ] Combine: save as portfolio; portfolio "convert all parts to PDF" — CombinePlus, Portfolio
- [ ] E-mail conversion: attachments embed / convert-and-append — SourceReaders, ToPdf
- [ ] XLSX export: decimal / thousands separator — XlsxBuilder, Cv6 opts
- [ ] Image export: PNG interlace / filter, JPEG progressive, ICC, monochrome — ImageCodecs6
- [ ] Extract images: colour space / resolution — ImageExtract6, ExtractImagesScreen (PARTIAL: format choice done)
- [ ] Portfolio: read mode; print all / selected parts — PortfolioEditorUi
- [ ] Portfolio: add from web / scanner / clipboard — PortfolioEditorUi, PortfolioEdit
- [ ] Portfolio: drag-drop reordering — PortfolioEditorUi
- [ ] Binder: TOC template with field codes — CombinePlus (CombineOpts.toc, OutlineTools.insertToc)
- [ ] Binder: custom metadata / Office-property columns — CombinePlus BinderProject
- [ ] Binder: folders / sections, binder templates — BinderStore
- [ ] Binder output options: open after, show in folder — CombinePlusUi (PARTIAL: output folder row)
- [ ] Preflight: profile libraries, search, single checks / fixups view — Standards (fixed enum), PdfAScreen
- [ ] Print-production checks: plates, CxF, rich black, ICC / Lab, transparency listing — Standards PRINT, PdfInspect
- [ ] Preflight report as a PDF with highlighted problems — PdfAScreen
- [ ] PDF/A-4 / 4e / 4f conversion — PdfA, Standards PdfALevel
- [ ] PDF/A: outline non-Unicode text instead of rasterising — PdfA (PARTIAL: edit has a fonts-to-outlines fixup)
- [ ] Formal PDF/A / Matterhorn validation — NOT-FEASIBLE on device (veraPDF); approximate only
- [ ] PDF/E-1 conversion; PDF/X variants other than X-4 verify; PDF/VT verify; Save as PDF/E; PDF/E / PDF/X at creation — Standards, PdfA, CreatePreset
- [ ] Insert pages: preview of the file being inserted — AnyDocPicker, OrganizeScreen
- [ ] Drag page thumbnails from another document — Organize / ReaderThumbs (PARTIAL: drop target exists, no source)
- [ ] Crop dialog: page size and centering — CropController
- [ ] Preflight grayscale fixup — Standards / PdfAScreen (PARTIAL: Optimizer has grayscale)
- [ ] Page labels from bookmarks or page regions — page-labels tool
- [ ] Custom blank page size and units — OrganizeScreen insert dialog, PdfOps.insertBlankPage

## reader/view
- [ ] Two editable documents side by side — ReaderScreen, RpfTabs (PARTIAL: tab groups done)

## comments/forms
- [ ] Video: per-type defaults; floating / hidden playback — VideoPdf, video properties (PARTIAL)

## tools/OCR, compliance, accessibility, batch
- [ ] OCR: table rules / lines written as vector paths — SearchablePdfWriter, EditableTextPdfWriter
- [ ] Scan paper sizes A0–A10, B, C, JIS — ScanBuilder ScanPaper
- [ ] Preflight as a recipe step with success / error branch — Recipes StepType, RecipeRunner
- [ ] Recipes: panels / dividers — Recipes, RecipeEditor (PARTIAL: NOTE step)
- [ ] Text recovery for xlsx / pptx / html conversions (Word only today) — Cv6Catalog TEXT_RECOVERY
- [ ] Edit a figure's alt text from the page (image properties) — SelBindings, SelObjects
- [ ] On-page Table Editor — AccessTagsUi, AccessContent
- [ ] Find Element: artifacts, OCR suspects, unmarked content — AccessFind (PARTIAL: text / type)
- [ ] Action Wizard log export; PDF/UA report during a run — RecipeRunner LogEntry, RecipesScreen
- [ ] QR / barcode scanning in the scanner — ScanCameraScreen / ScanStudioScreen (zxing core is a dependency)

## AI/office
- [ ] Volume licensing / seat management — LicenseClient, Pro (owner: LICENSE_URL)
