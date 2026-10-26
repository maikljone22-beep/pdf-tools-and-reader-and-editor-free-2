# MODULE convert6 (round 5) — Convert hub «تحويل» + «استخراج كل الصور»

Tool ids: `convert_hub` (hub), `extract_images` (extract all images). The old route id `convert` now opens the hub too
(the old two-tab screen stays reachable as `convert_classic`).

## Files

core/convert6 (pure JVM unless marked *Android*):
- `FileKind6.kt` — file-type detection (magic bytes → ZIP entries / `mimetype` → CFB streams → extension), picker mimes.
- `Cv6Catalog.kt` — every hub conversion (`Conv`: id, groups, from/to, accepted kinds, batch, options, route), search (Arabic-normalised), recents.
- `FlowDoc.kt` — reflowable document model + Markdown / plain-text / HTML writers.
- `FlowDocx.kt` — FlowDoc → DOCX (heading styles, `w:bidi` + `w:rtl`, numbering.xml lists, `bidiVisual` tables, pictures, links); `ImageDims`.
- `EpubWriter.kt` — FlowDoc → EPUB 3 (+NCX, rtl page progression, chapters); `OdfWriter` → ODT / ODS / ODP.
- `FlowReaders.kt` — Markdown → FlowDoc (GFM tables), HTML → FlowDoc, Office models (core/office) → FlowDoc / slide texts.
- `RtfReader.kt` — RTF → FlowDoc (\uN, \'hh with \ansicpg / \fcharset 178, \rtlpar, tables).
- `OdfReader.kt` — ODT / ODS / ODP readers (javax.xml DOM).
- `CfbReader.kt`, `LegacyOffice.kt` — OLE2 container; Word 97 text (piece table), Excel 97 BIFF8 values.
- `TiffDecoder.kt` — baseline multi-page TIFF (none/LZW/Deflate/PackBits, strips/tiles, 1–16 bit, palette/RGB/CMYK).
- `Images6.kt` — formats, resize math, page placement (size/orientation/fit/margins), N-up grids, long-image stitch.
- `ImageNaming.kt` — extract-images names: sanitising (Arabic kept, bidi/format chars removed), caption lookup, de-dup.
- `LayoutFlow.kt` — core/convert `PageLayout` → FlowDoc (headings, lists, tables, images, outline chapters); `Csv6`.
- *Android*: `PdfFlow6.kt` (PDF → layouts / FlowDoc, outline chapters, OCR copy), `ImageIo6.kt` (decode any image incl. TIFF/HEIC,
  images → PDF with page setup, format/resize, long image, SVG pages, page pictures), `PdfTransforms6.kt` (grayscale, N-up),
  `ImageExtract6.kt` (PDF + package image extraction), `ToPdf6.kt` (any input → PDF, combiner), `Cv6Engine.kt` / `Cv6Engine2.kt` (runner).

ui/convert6:
- `ConvertHubScreen.kt` — hub (search, current-file suggestions, recents, grouped cards), one-step flow, `ConvertHubBanner`.
- `Cv6OptionsSheet.kt` — inputs editor (batch, add more), password, all options with defaults (`defaultsFor`).
- `Cv6Results.kt` — multi-output sheet (open / share each, share all, ZIP all), `openResultFile`.
- `ExtractImagesScreen.kt`, `Cv6Save.kt` — thumbnails grid, select / rename, ZIP / SAF folder / share / gallery.
- `Cv6Labels.kt` — labels, icons, tiles.

Strings: `res/values/strings_convert6.xml`, `res/values-en/strings_convert6.xml` (prefix `cv6_`, same keys).
Tests: `app/src/test/java/com/smartdocs/editor/core/convert6/Convert6PureTest.kt`, `Convert6WritersTest.kt`.

## Edits to existing files (minimal, additive)
- `ui/tools/ToolScreens.kt`: `"convert", "convert_hub" -> ConvertHubScreen`, `"convert_classic" -> ConvertScreen`, `"extract_images" -> ExtractImagesScreen`.
- `ui/tools/ToolsScreen.kt`: ToolDef `convert` → `convert_hub` (same label/icon), new ToolDef `extract_images` (group 3),
  `defaultTop` uses `convert_hub`, full-width `ConvertHubBanner` as the first item of the Tools grid.
- `ui/home/HomeScreen.kt`: quick action «تحويل» (`convert_hub`).

## Hooks for the lead (reader files are owned by another agent — not edited)
1. `ui/reader/ReaderToolbars.kt`, list `readerTools`, after the line
   `ReaderToolItem.Screen("pdf_to_text", R.string.tool_pdf_to_text, Icons.Default.Subject),` insert:
   ```kotlin
   ReaderToolItem.Screen("convert_hub", R.string.cv6_title, Icons.Default.SwapHoriz),
   ReaderToolItem.Screen("extract_images", R.string.cv6_t_extract_images, Icons.Default.Collections),
   ```
   `navigateTool(id)` already saves pending edits and calls `Routes.tool(id, state.fileUri)`, so the hub / extractor open with the
   current file (the hub puts matching conversions on top in «الملف الحالي»). Any existing reader/overflow item that navigates to
   `"convert"` already lands in the hub with the file (route alias).
2. Optional: overflow menu item «استخراج الصور» → `nav.navigate(Routes.tool("extract_images", state.fileUri))` (same as hook 1).

No new Gradle dependencies.

## Conversion table

Legend: DONE / PARTIAL (what is missing) / NOT FEASIBLE (why). Arabic: DOCX uses `w:bidi`/`w:rtl`/`bidiVisual`; ODT `rl-tb`
paragraph styles; EPUB/HTML `dir`; PDFs rendered through the WebView (Noto Naskh Arabic, correct shaping) or the existing
PdfBox writers that use `PdfOps.arabicFont` + `ArabicShaper.shape`.

### From PDF (batch: several PDFs at once; auto-OCR of scanned PDFs where text matters)
| Conversion | Status | Notes |
|---|---|---|
| PDF → Word (.docx) | DONE | core/convert engine (keep layout) or FlowDocx (text flow); auto-OCR. |
| PDF → Excel (.xlsx) | DONE | core/convert; sheet per page or one sheet. |
| PDF → PowerPoint (.pptx) | DONE | editable or picture slides. |
| PDF → images JPG/PNG/WebP per page, ZIP | DONE | tools5 ExportPlus; DPI, quality, grey, ZIP/separate. |
| PDF → one long image | DONE | stitched, total capped at 60 MP (width shrinks for long documents). |
| PDF → text (.txt) | DONE | |
| PDF → HTML | DONE | |
| PDF → RTF | DONE | tools5 ExportPlus.rtf. |
| PDF → Markdown (.md) | DONE | headings, lists, tables, images (→ ZIP with images/ when pictures are included). |
| PDF → EPUB (reflowable) | DONE | chapters from the top-level outline, else level-1 headings; rtl page progression. |
| PDF → CSV (tables) | DONE | detected tables (UTF-8 BOM); text lines as one column when no table. |
| PDF → ODT | DONE | own OpenDocument writer (page breaks kept). |
| PDF → ODS | DONE | one sheet per page with tables. |
| PDF → ODP | PARTIAL | one picture slide per page (not editable text). |
| PDF → SVG per page | PARTIAL | raster page picture + invisible selectable text layer; true vector output not feasible (PDFium renders to pixels, PdfBox-Android has no vector renderer). |
| PDF → PDF/A | DONE | tools5 PdfA (PDF/A-2b best effort); option to flatten transparency. |
| PDF → grayscale PDF | PARTIAL | vector colour operators (rg/RG/k/K/sc/scn in DeviceRGB/CMYK/ICC/CalRGB) rewritten in pages and forms, images greyed by the optimizer; shadings, Separation/DeviceN/Indexed colours, masked images and annotation appearances keep their colour. |
| PDF → compressed PDF | DONE | tools5 Optimizer (DPI, quality, grey). |
| PDF → searchable PDF (OCR) | DONE | tools5 OcrRunner; also accepts images. |
| PDF → image-only (flattened) PDF | DONE | PdfOps.flatten (DPI option). |
| Extract all images | DONE | see below. |

### To PDF (batch: merge into one PDF with a bookmark per input, or one PDF per input)
| Conversion | Status | Notes |
|---|---|---|
| Word .docx → PDF | DONE | core/office reader → HTML → WebView print. |
| .doc → PDF | PARTIAL | Word 97 text only (piece table): no formatting, pictures or table layout; encrypted .doc refused. |
| .odt → PDF | DONE | own ODT reader (headings, lists, tables, pictures, bold/italic, rtl). |
| .rtf → PDF | DONE | own RTF reader (no pictures). |
| Excel .xlsx → PDF | DONE | |
| .xls → PDF | PARTIAL | BIFF8 cell values (numbers not date-formatted, no styles); first 5000 rows per sheet printed. |
| .csv → PDF | DONE | printed as a table (delimiter sniffed). |
| .ods → PDF | DONE | values as tables. |
| PowerPoint .pptx → PDF | DONE | |
| .odp → PDF | PARTIAL | slide titles + text as one page per slide (no positioned layout / pictures). |
| .ppt → PDF | NOT FEASIBLE | PowerPoint 97 binary record format needs a full parser (no on-device library without a heavy dependency). |
| Images JPG/PNG/WebP/GIF/BMP → PDF | DONE | page size A4/Letter/Legal/A3/A5/image, orientation, fit/fill/stretch/original, margins; JPEGs embedded byte-for-byte. |
| HEIC/HEIF → PDF | PARTIAL | Android 9+ only (platform decoder). |
| TIFF incl. multi-page → PDF | DONE | own decoder; CCITT G3/G4 pages via PdfBox CCITTFactory; JPEG-in-TIFF pages skipped. |
| Text / Markdown / HTML file → PDF | DONE | |
| Web page URL → PDF | DONE | WebView print (needs internet). |
| EPUB → PDF | DONE | tools5 EpubReader. |
| Email .eml → PDF | DONE | tools5 EmlReader (attachments embedded). |
| Camera scan → PDF | DONE | routes to the existing scanner (`scan`). |
| Clipboard text → PDF | DONE | Markdown detected automatically. |
| Mixed files → one PDF | DONE | any supported kind, bookmarks per file. |

### Images
| Conversion | Status | Notes |
|---|---|---|
| Format converter JPG↔PNG↔WebP (+ GIF/BMP/TIFF/HEIC input) | DONE | alpha flattened on white for JPG. Output to GIF/BMP/TIFF/HEIC not offered (no platform encoder). |
| HEIC → JPG | PARTIAL | Android 9+ only. |
| Resize / compress | DONE | by percent or longest side, keep or change format, quality. |
| Images → one PDF | DONE | see To PDF. |
| PDF pages → images | DONE | |
| Image → text (OCR) | DONE | Tesseract; language auto-detected or chosen; merge into one file or one per image. |
| Image → Word (OCR) | DONE | text paragraphs in DOCX (RTL), one page per image. |
| Images → searchable PDF (OCR) | DONE | |

### Office ↔ Office / text
| Conversion | Status | Notes |
|---|---|---|
| Word → TXT / HTML / Markdown | DONE | DOCX/DOC/ODT/RTF inputs; DOCX → HTML uses the core/office renderer. |
| Word → ODT, ODT/RTF/DOC → DOCX | DONE | through FlowDoc (structure, not exact styling). |
| Word → EPUB | DONE | |
| Excel → CSV | DONE | XLSX via office5 (formula results), XLS/ODS via own readers; first sheet or all sheets (ZIP). |
| CSV → Excel | DONE | office5 CsvIO + XlsxIO5 (numbers/formulas parsed). |
| Excel/CSV → ODS | DONE | values only. |
| PowerPoint → images per slide | DONE | via PDF. |
| PowerPoint → PDF handouts (N per page) | DONE | 2/3/4/6/9 per A4, frames, RTL order for Arabic decks; also from PDF. |
| PowerPoint → ODP | PARTIAL | picture slides (not editable). |
| Word → images per page | DONE | via PDF. |

### Text & books
| Conversion | Status | Notes |
|---|---|---|
| TXT → DOCX | DONE | form feed = page break. |
| DOCX → TXT | DONE | |
| Markdown → DOCX / PDF / HTML / EPUB | DONE | |
| HTML → DOCX | DONE | lenient HTML parser (inline data: images kept). |
| EPUB → PDF / TXT / DOCX | DONE | |

### OCR
| Conversion | Status | Notes |
|---|---|---|
| Scanned PDF → editable Word | DONE | forced OCR copy → core/convert DOCX (keep layout) or text flow. |
| Scanned PDF → text | DONE | |
| Scanned PDF → Excel | DONE | OCR copy → table detection. |
| Scanned PDF → searchable PDF | DONE | |
| Image(s) → text / Word / PDF | DONE | |

### Extract all images (`extract_images`)
| Feature | Status | Notes |
|---|---|---|
| PDF: page XObjects, nested forms, inline images, tiling patterns, annotation appearances, unused resources | DONE | original resolution. |
| Keep encoding: DCT → .jpg byte-for-byte, JPX → .jp2, else lossless PNG with SMask applied | DONE | CMYK JPEGs are re-encoded to PNG (viewers show raw CMYK JPEGs inverted). Stencil masks skipped. |
| Duplicates (same object or same SHA-1) skipped; min-size filter; page range; password | DONE | |
| Naming: `<doc>_p<page>_<n>`, XObject resource name, nearby caption («الشكل 3: …»), sanitised, Arabic kept, de-dup `(2)`, `(3)` | DONE | caption lookup on unrotated pages (rotated pages fall back to page/index names). |
| Size W×H and file size per image, thumbnails, select all/none, inline rename | DONE | JP2/EMF thumbnails show an icon (no platform decoder). |
| Output: ZIP, SAF folder, share selected, gallery (MediaStore Pictures/SmartDocs) | DONE / PARTIAL | gallery without permission needs Android 10+; on 8/9 the insert may fail without storage permission (reported count). |
| DOCX / PPTX / XLSX media (alt text `descr`, slide number) | DONE | |
| ODT / ODP / ODS pictures (frame title/name), EPUB images (img alt) | DONE | |

## Tests / compile
- `./gradlew :app:compileDebugKotlin --console=plain`: the first run found 3 errors in convert6 files, which were fixed. The second run
  reported **BUILD SUCCESSFUL**, with only deprecation and style warnings in convert6 files.
- `./gradlew :app:testDebugUnitTest --tests "com.smartdocs.editor.core.convert6.*"`: **35 tests, 0 failures** across FileKind6Test (5),
  ImageNamingTest (5), Images6Test (5), Cv6CatalogTest (3), FlowWritersTest (11: Markdown round trip, DOCX RTL/lists/links, EPUB
  structure + well-formed XHTML, ODT/ODS/ODP round trips, CSV, text/page breaks), ReadersTest (4: RTF code page / Unicode / tables,
  HTML, Word control characters, ODF lengths) and TiffDecoderTest (2: multi-page + PackBits).
- Not tested on a device: the WebView print paths, PDFium rendering, OCR, the MediaStore/SAF saves and the PdfBox stream
  rewrites (grayscale, N-up, image extraction). These need a manual check on a phone.
