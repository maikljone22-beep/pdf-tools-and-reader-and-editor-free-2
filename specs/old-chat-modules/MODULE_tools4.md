# MODULE_tools4 — convert, OCR, scan, batch/recipes, compare, preflight, PDF/A, accessibility

Owner id: `tools4`. No new dependencies. Gradle was not run.

All new code was type-checked with the Kotlin 2.3.21 compiler and the Compose compiler plugin from the Gradle cache. It was compiled against android-36, every cached AAR and JAR, the app's last compiled classes, and a generated `R` stub. Result: **0 errors** in `core/tools4`, `ui/tools4`, `android/print/T4PrintBridge.kt` and the four existing files I edited.

These pure parts were also run on the desktop JVM:
- `ImageOps`: the skew estimate is exact to ±0.1° on synthetic pages.
- `CardParsers`: business card and receipt parsing.
- `IccSrgb`: Pillow/LittleCMS loads it and its transform is the identity.
- `TiffWriter` / `BmpWriter`: Pillow reads multi-page RGB, grey and 1-bit TIFFs and BMPs correctly.
- `SourceReaders`: Markdown, RTF (cp1256 and `\uN`), EML (MIME, quoted-printable, base64, encoded words, attachments) and EPUB (spine order, inlined images).

## Files

### `core/tools4/` (package `com.smartdocs.editor.core.tools4`)

| File | Role |
|---|---|
| ImageOps.kt | Pure pixel algorithms: Sauvola threshold, despeckle, median, background normalisation (shadow / whiteboard), auto levels, brightness/contrast, projection-profile skew, binding-column finder, dHash, pixel-diff boxes, grey / bilevel detection |
| ScanOps.kt | Scan filters (8), deskew, perspective quad warp, quarter rotation, cleanup eraser, book split, ID-card A4 composition, image→PDF builder (bilevel pages stored lossless) |
| ScanProject.kt | Saved scan projects (original images + per-page edits JSON), used to reopen and re-edit a scan |
| CardParsers.kt | Business card → contact + vCard; receipt → merchant / date / total / currency; CSV row; smart file-name suggestion |
| TextLayer.kt | `PageGeo` (display ↔ user space, crop box + /Rotate); `TextLayerWriter` (invisible OCR text, Arabic shaped) |
| OcrPlus.kt | OCR runner: deskew (boxes mapped back), despeckle, re-OCR, "cleaned image" mode with downsampling, editable mode, language auto-detect (script + stop words), per-page statistics, region OCR + region layer |
| InvisibleText.kt | Removes / counts rendering-mode-3/7 text (re-OCR) |
| OcrReview.kt | Sidecar word data per OCR output; applies suspect-review decisions by rebuilding the page's text layer |
| PdfInspect.kt | One-pass analysis: fonts (embedded / subset / ToUnicode / visible use), image placements with effective DPI, colour spaces, CMYK / RGB pages, transparency, actions / JS, annotations, forms, XMP/pdfaid, output intents, tags, language, LZW, layers |
| SpaceAudit.kt | Bytes per category (images, content, fonts, forms, comments, thumbnails, tags, bookmarks, metadata, attachments, colour, other, overhead) |
| Optimizer.kt | Advanced optimizer: downsampling above a placed ppi, JPEG quality, grey detection, bilevel → 1-bit Flate, "only if smaller", image dedup, discard JS / thumbnails / PieceInfo / metadata / tags / bookmarks / attachments / comments / alternates / layers, unused page resources, Flate for uncompressed streams, LZW → Flate; JSON (de)serialisable presets |
| IccSrgb.kt | sRGB ICC v2 profile generated in code (2.5 KB) |
| Xmp.kt | XMP packet writer (dc, xmp, pdf, pdfaid, pdfuaid) |
| RasterPage.kt | Replaces a page with a picture of itself while keeping its text searchable (PDF/A and preflight fallback) |
| PdfA.kt | PDF/A-1b / 2b / 3b conversion and a rule self-check (see TLS-6) |
| Grayscale.kt | Vector grey conversion (rg/RG/k/K, ICC/RGB/CMYK sc/scn, forms, annotation appearances, images); optional raster fallback for pages with shadings or separations |
| RasterWriters.kt | Multi-page TIFF (Deflate; RGB / grey / 1-bit) and BMP writers |
| ImageExport.kt | Page images: JPG/PNG/WebP/BMP/TIFF, any DPI, colour / grey / mono, one long image. Embedded images at native resolution (DCT passthrough, dedup, min size) |
| SourceReaders.kt | Markdown → HTML, RTF → HTML, EML (MIME) → HTML + attachments, EPUB → one HTML (inlined images) |
| WebCapture.kt | URL / HTML → PDF: paper, orientation, margins, images on/off, JS on/off, one long screen page, header/footer stamp, linked same-site pages (≤15) merged with bookmarks |
| Combine.kt | `ToPdf` (any supported file → PDF), `Tools4Hooks.officeToPdf`, `Combine` (mixed files → one PDF, bookmark per file, clickable TOC page, binders = saved lists) |
| ExportText.kt | RTF / XML 1.0 / accessible-text export from the converter's layout; `exportArea` (only a selected rectangle → DOCX/XLSX/PPTX/HTML/TXT) |
| OpLocator.kt | Bounding box of every top-level content operator (for autotagging) |
| AccessTree.kt | Structure-tree reader (role-mapped types, MCIDs, page, alt / lang / actual text), alt / type / language editing, move in reading order, "decorative" (marked content → Artifact), reading-order paragraphs (tags, or layout order) |
| AutoTag.kt | Autotag: layout → Document / H1–H3 / P / L-LI-LBody / Table-TR-TH-TD (spans) / Figure / Form + Link OBJR, MCIDs written into the content, ParentTree, MarkInfo, /Tabs /S, language, DisplayDocTitle; headers / footers / unmatched content become /Artifact |
| AccessCheck.kt | Accessibility checker (30 rules in 7 groups; WCAG contrast heuristic), HTML report; `AccessFix` quick fixes |
| DocCompare.kt | Word extraction with formatting, Arabic-normalised tokens, page alignment (insertions / removals), LCS word diff → inserted / deleted / replaced / formatting; per-page image hashes |
| CompareReport.kt | `CompareRunner`: text + image + optional pixel diff, overlay bitmap, PDF report (summary page + side-by-side annotated page pairs) |
| Preflight.kt | Profiles (screen / office / press / archive), findings, fixups: rasterise pages with non-embedded fonts, downsample, remove JS, greyscale, page boxes, hairlines, printer marks, PDF/A |
| Recipes.kt | Recipe / Step / OutputOptions model (JSON), 6 built-in recipes, store, `.sdrecipe` import / export |
| RecipeRunner.kt | Executes 17 step types file → file with a log; output naming tokens; `HeaderFooter` stamp (6 slots, tokens) |
| BatchWorker.kt | WorkManager `CoroutineWorker`: many files × recipe (or "convert each to PDF"); progress data, notification, JSON log |
| MailMerge.kt | CSV (`,` `;` tab) and XLSX (first sheet) reader, automatic field ↔ column mapping, one PDF per row or a merged PDF |
| Portfolio.kt | Portfolio detection, component list / extract, creation (cover page + embedded files + /Collection schema) |
| PrintAndShare.kt | `T4PrintService` ("SmartDocs PDF" virtual printer), `ShareImport` (SEND / SEND_MULTIPLE payloads) |

`app/src/main/java/android/print/T4PrintBridge.kt`: a new file in `android.print`, for the same package-private-callback reason as `WebPdfWriter`. It drives a custom-configured WebView print adapter into a file.

### `ui/tools4/` (package `com.smartdocs.editor.ui.tools4`)

T4Common, OcrPlusScreen (with the `OcrReviewDialog` suspect review), ScanStudioScreen, ScanParts (page editor with adjust / crop quad / eraser; contact and receipt sheets), ToPdfPlusScreen (+ `ShareToPdfScreen`, `WebOptionsPanel`), ExportPlusScreen, OptimizeScreen, PdfAScreen, RecipesScreen (+ `BatchMonitor`, `BatchScreen`), RecipeEditor, Compare2Screen, PreflightScreen, AccessibilityScreen, TagsPanel, MailMergeScreen, PortfolioSaveAs (`PortfolioScreen`, `SaveAsScreen`), AutoOcr (G2 helper), ReaderHooks4 (`AccessiblePageLayer`, `readingOrderTexts`, `RegionOcrSheet`, `AreaExportSheet`).

### Existing files edited (allowed by the brief; minimal)
- `ui/tools/CompareTool.kt`: `CompareScreen` now delegates to `Compare2Screen`. The old screen is kept as `LegacyCompareScreen` and is not routed.
- `ui/tools/ConvertFromPdf.kt`: automatic OCR (G2) before converting scanned pages (label `t4_g2_ocr`). The temporary OCR copy is deleted afterwards.
- `ui/tools/PageTools.kt`: the compress screen got a link "الضغط المتقدم وتحليل المساحة" → `optimize`.
- `ui/ocr/OcrScreen.kt`: a top-bar icon → `ocr_plus`.

### Strings
`res/values/strings_tools4.xml` and `res/values-en/strings_tools4.xml`: 563 strings, prefix `t4_`. Arabic and English have the same format placeholders (checked by script).

## Hooks for the lead (exact)

1. **`ui/tools/ToolScreens.kt`**: after the line `"compare" -> CompareScreen(nav, uri)` add:
```kotlin
        "ocr_plus" -> com.smartdocs.editor.ui.tools4.OcrPlusScreen(nav, uri)
        "scan_studio" -> com.smartdocs.editor.ui.tools4.ScanStudioScreen(nav, uri)
        "to_pdf_plus" -> com.smartdocs.editor.ui.tools4.ToPdfPlusScreen(nav, uri)
        "share_to_pdf" -> com.smartdocs.editor.ui.tools4.ShareToPdfScreen(nav)
        "export_plus" -> com.smartdocs.editor.ui.tools4.ExportPlusScreen(nav, uri)
        "optimize" -> com.smartdocs.editor.ui.tools4.OptimizeScreen(nav, uri)
        "pdfa" -> com.smartdocs.editor.ui.tools4.PdfAScreen(nav, uri)
        "recipes" -> com.smartdocs.editor.ui.tools4.RecipesScreen(nav, uri)
        "batch" -> com.smartdocs.editor.ui.tools4.BatchScreen(nav)
        "preflight" -> com.smartdocs.editor.ui.tools4.PreflightScreen(nav, uri)
        "accessibility" -> com.smartdocs.editor.ui.tools4.AccessibilityScreen(nav, uri)
        "mail_merge" -> com.smartdocs.editor.ui.tools4.MailMergeScreen(nav, uri)
        "portfolio" -> com.smartdocs.editor.ui.tools4.PortfolioScreen(nav, uri)
        "save_as" -> com.smartdocs.editor.ui.tools4.SaveAsScreen(nav, uri)
```
2. **`ui/tools/ToolsScreen.kt`**: after `ToolDef("compare", R.string.tool_compare, Icons.Default.Compare, 3),` add:
```kotlin
    ToolDef("scan_studio", R.string.t4_scan_title, Icons.Default.DocumentScanner, 0),
    ToolDef("to_pdf_plus", R.string.t4_topdf_title, Icons.Default.PictureAsPdf, 0),
    ToolDef("mail_merge", R.string.t4_mm_title, Icons.Default.MergeType, 0),
    ToolDef("portfolio", R.string.t4_portfolio_title, Icons.Default.FolderZip, 0),
    ToolDef("ocr_plus", R.string.t4_ocr_title, Icons.Default.TextFields, 3),
    ToolDef("export_plus", R.string.t4_export_title, Icons.Default.Output, 3),
    ToolDef("optimize", R.string.t4_opt_title, Icons.Default.Compress, 3),
    ToolDef("pdfa", R.string.t4_pdfa_title, Icons.Default.Inventory, 3),
    ToolDef("preflight", R.string.t4_pf_title, Icons.Default.FactCheck, 3),
    ToolDef("accessibility", R.string.t4_acc_title, Icons.Default.Accessibility, 3),
    ToolDef("recipes", R.string.t4_recipes_title, Icons.Default.AutoMode, 3),
    ToolDef("batch", R.string.t4_batch_jobs, Icons.Default.Schedule, 3),
```
   - The group numbers follow the existing scheme (0 = create, 3 = convert/tools). Adjust them if needed.
   - `save_as` is reached from the reader (hook 6), not the grid.
3. **`AndroidManifest.xml`**
   - Extend the existing `SEND` intent-filter with these lines, after `<data android:mimeType="image/*" />`:
```xml
                <data android:mimeType="text/html" />
                <data android:mimeType="text/markdown" />
                <data android:mimeType="text/rtf" />
                <data android:mimeType="application/rtf" />
                <data android:mimeType="application/epub+zip" />
                <data android:mimeType="message/rfc822" />
```
   - In the `SEND_MULTIPLE` filter, after `<data android:mimeType="image/*" />`, add `<data android:mimeType="*/*" />`.
   - Inside `<application>`, after the ReadAloudService `<service …/>`, add:
```xml
        <service android:name=".core.tools4.T4PrintService" android:exported="true" android:label="SmartDocs PDF"
            android:permission="android.permission.BIND_PRINT_SERVICE">
            <intent-filter><action android:name="android.printservice.PrintService" /></intent-filter>
        </service>
```
   - `POST_NOTIFICATIONS` is already declared. WorkManager needs nothing else: the worker is not a foreground service.
4. **`MainActivity.handleIntent`**: as the first statement of the function, before `val uri: Uri? = when …`, add:
```kotlin
        if (com.smartdocs.editor.core.tools4.ShareImport.handles(intent)) {
            val app = application as SmartDocsApp
            com.smartdocs.editor.core.tools4.ShareImport.pending = com.smartdocs.editor.core.tools4.ShareImport.read(this, intent!!, app.documents.tempDir)
            openedByIntent = true
            pendingRoute.value = com.smartdocs.editor.ui.Routes.tool("share_to_pdf")
            return
        }
```
   - `handles` is true for SEND_MULTIPLE, for text / URL shares without a file, and for HTML / Markdown / RTF / EPUB / e-mail. A single PDF, image, Office or TXT file keeps today's reader path.
   - `pendingRoute` is a new `mutableStateOf<String?>(null)` next to `incoming`. Navigate to it in the same `LaunchedEffect` that consumes `incoming`, then reset it to null.
5. **Office → PDF (G1)**: in `SmartDocsApp.onCreate` (or `MainActivity.onCreate`) add:
```kotlin
com.smartdocs.editor.core.tools4.Tools4Hooks.officeToPdf = { ctx, src, out ->
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (src.extension.lowercase()) {
            "docx" -> com.smartdocs.editor.ui.office.OfficePdf.docx(ctx, com.smartdocs.editor.core.office.OoxmlReader.readDocx(src), out)
            "pptx" -> com.smartdocs.editor.ui.office.OfficePdf.slides(ctx, com.smartdocs.editor.core.office.SlideDoc.open(src), out)
            "xlsx", "ods", "csv" -> com.smartdocs.editor.ui.office.OfficePdf.sheets(ctx, com.smartdocs.editor.core.office.SheetIo.load(src), out)
            else -> throw com.smartdocs.editor.core.tools4.UnsupportedSource(src.extension)
        }
    }
}
```
   - `SheetIo` is `internal`, so this must sit in the app module, which it does.
   - With this in place, Combine / Convert-each / Share-to-PDF / batch accept Office files.
6. **Reader** (owner: reader/view agents)
   - Overflow menu: add "حفظ باسم…" → `nav.navigate(Routes.tool("save_as", currentFileUri))` (FILE-10 router).
   - **K13 / CNV-13**: on a long press of a text-less page, or from the snapshot/rectangle tool, show:
```kotlin
RegionOcrSheet(file, state.password, page, rectDisplayPointsTopLeft, onLayer = { f -> state.openReplacement(f) /* or Routes.reader */ }, onDismiss = { … })
```
     `QuickController.openOcrForPage(page)` remains available for whole-page OCR.
   - **CNV-11**: in the selection/snapshot sheet add «تصدير التحديد» →
```kotlin
AreaExportSheet(nav, file, password, page, rectDisplayPointsTopLeft, onDismiss)
```
   - **ACC-7**: in `PageCanvas`, inside the Box that draws a page bitmap, add
```kotlin
AccessiblePageLayer(file, password, pageIndex, Modifier.matchParentSize())
```
     It is invisible and only adds TalkBack nodes. Gate it on `AccessibilityManager.isTouchExplorationEnabled` to save work.
   - **C1 / read aloud in tag order**: `ReadAloudController` can call `readingOrderTexts(file, password, page)` (suspend) instead of the raw page text.
   - **Portfolio (CNV-7 / A8)**: when opening a document where `Portfolio.isPortfolio(file, pw)` is true, offer «فتح كحافظة» → `Routes.tool("portfolio", uri)`.
7. **Files tab multi-select** (owner: files): add «تطبيق أداة…» → `Routes.tool("recipes")`.
   - Or call directly:
```kotlin
BatchJobs.enqueue(ctx, title, files.map { it to null }, Recipe(name = title, steps = listOf(Step(StepType.X, params))), false, emptyMap(), app.documents.docsDir)
```
   - «تحويل كل ملف إلى PDF»: `BatchJobs.enqueue(…, recipe = null, toPdf = true, …)`.
8. **Launcher shortcuts (K14)**: view4's `res/xml/v4_shortcuts.xml` already has a SCAN shortcut. Point its action to `Routes.tool("scan_studio")`, or keep the old scanner.
9. **Scanner screen** (owner: pages): optionally add a button «استوديو المسح» → `Routes.tool("scan_studio")`. Scan studio supersedes it (types, per-page edits, auto-OCR).
10. **CreateTools blank page (CNV-17)**: add a «مخصص» chip with width and height fields (mm/cm/in) that calls the existing `PdfOps.createBlank(out, count, PDRectangle(wMm * 72f / 25.4f, hMm * 72f / 25.4f))`. Remember the last choice in SharedPreferences. The engine already supports any size; only the chip is missing, and `CreateTools.kt` is not mine.
11. **TemplatesScreen (CNV-18)**: add an `OutlinedTextField` search filtering the list by `DocCompare.normalize(title).contains(DocCompare.normalize(query))`, and category chips if the template model has a category. The file is not mine (psf module).
12. **FILE-6/7/8/9 (owner view4)**: they can reuse these instead of re-implementing:
   - `SpaceAudit.audit`
   - `Optimizer.optimize(OptimizeOptions)`, whose presets are JSON
   - `BatchJobs.enqueue` with a COMPRESS step
   - the `optimize` route

## Status of every row owned by `tools4` (MASTER_BACKLOG)

| ID | Status |
|---|---|
| CNV-1 Combine any format | **DONE**. `to_pdf_plus` › Combine: PDF, images, TXT/CSV/code, Markdown, HTML, RTF, EPUB, EML and Office (via hook 5). Reorder, bookmark per file, clickable TOC page, binders. *Not done: dragging individual pages between files* (use Organize after combining). |
| CNV-2 Many files → separate PDFs | **DONE**. "Each file" tab, in-screen or in the background (WorkManager + notification + log). |
| CNV-3 Clipboard → PDF | **DONE**. Clipboard tab: text, HTML or image. |
| CNV-4 Web page advanced | **DONE**. Paper / orientation / margins, images, JS, one long screen page, header/footer, linked same-site pages (level 1, ≤15) with bookmarks. *Deeper levels are not offered.* |
| CNV-5 Convert from other apps | **DONE** with hooks 3 and 4: `ShareToPdfScreen` for files, URL, text and HTML. |
| CNV-6 Mail merge | **DONE**. Fillable PDF + CSV/XLSX, auto mapping, per-row files named from a column, or one merged PDF. *Not done: our template recipes as targets* (only AcroForm PDFs). |
| CNV-7 Portfolio view/create | **DONE** with hook 6 (auto-offer in the reader): list, open, share, extract, create with a /Collection schema. *Not done: folders inside portfolios.* |
| CNV-8 Export images more formats | **DONE**. JPG/PNG/WebP/BMP/multi-page TIFF; colour/grey/mono; any DPI; long image. JPEG2000/GIF **not feasible** (no encoder). |
| CNV-9 Export all embedded images | **DONE**. Native resolution, JPEG passthrough, dedup, minimum size, share all. |
| CNV-10 RTF / XML / accessible text | **DONE**. Export screen; accessible text uses figure alt texts from the tags. |
| CNV-11 Export selected area | **DONE** in core + `AreaExportSheet`; needs reader hook 6. |
| CNV-12 XPS/OXPS | **NOT FEASIBLE / not recommended** (spec): needs glyph indices and XPS font embedding; no Android viewers use it. |
| CNV-13 OCR of a region | **DONE** in core + `RegionOcrSheet`: copy, share, add a searchable layer. Needs reader hook 6. |
| CNV-14 OCR many files | **DONE**. `ocr_plus` multi-file, in-screen or in the background. |
| CNV-15 Review OCR suspects | **DONE**. Sidecar word data; pager with the word crop, edit / accept / not text, apply rebuilds the page layer. |
| CNV-16 OCR deskew/downsample/after-scan | **DONE**. Deskew, despeckle, cleaned image at 150/200/300 dpi, automatic OCR after scan in the scan studio. *Line-segments-as-vectors not done.* |
| CNV-17 Custom blank size | **PARTIAL**. Engine ready; the UI chip lives in `CreateTools.kt` (tools module), exact code in hook 10. |
| CNV-18 Template gallery search | **PARTIAL**. Belongs to the templates screen (psf module); exact search code in hook 11. *No online catalogue* (needs a proxy endpoint). |
| CNV-19 Email to PDF | **DONE**. `.eml` (MIME, inline images, QP/base64, encoded words) → PDF with a header table; attachments embedded. Also accepted via share (hook 3). |
| CNV-20 Binder projects | **DONE**. Save / reopen / delete combine lists (files, bookmarks, TOC). *No Word-template TOC.* |
| CNV-21 Print to SmartDocs | **DONE** with hook 3: a print service with one virtual printer saves to Documents and notifies → open. |
| ACC-1 Full check + report | **DONE**. 30 rules in 7 groups: pass/fail/manual, explanations as labels, fix links, HTML report. *"Skip rule" and attaching the report to the PDF are not done.* |
| ACC-2 Quick fixes | **DONE**. Title (suggested from the first heading) + show title, language, tab order, field descriptions from nearby labels, bookmarks from headings, comment descriptions. *The accessibility permission flag cannot be changed without the owner password* (not attempted). |
| ACC-3 Autotag | **DONE**. Headings, paragraphs, lists, tables with spans and TH, figures, artifacts, form fields and links (OBJR), ParentTree, MarkInfo, Tabs, language; recognition report. *Content inside form XObjects stays untagged* (reported per page). |
| ACC-4 Alt text (AI) | **DONE**. Tags panel: figure crop, alt text, AI description through the configured vision-capable endpoint/proxy, "decorative" turns it into an artifact. |
| ACC-5 Reading order editing | **PARTIAL**. Move elements earlier/later in the tree and change their type; *no on-page region drawing or numbered overlays* (needs reader canvas work). |
| ACC-6 Tags and content panels | **PARTIAL**. Tree list with type/page/alt/lang, filter to figures; edit type, alt, actual text, language. *No cut/paste of tags and no content-panel highlighting on the page.* |
| ACC-7 TalkBack page content | **DONE** in UI (`AccessiblePageLayer`: paragraphs in tag or layout order, headings, traversal order). Needs reader hook 6. |
| ACC-8 Autotag form fields | **DONE** (part of AutoTag: /Form elements with OBJR and TU as Alt). |
| TLS-1 Action wizard | **DONE**. Recipes with 17 step types, per-step options or "ask when run", output naming, 6 built-ins, edit / duplicate / delete / import / export `.sdrecipe`. |
| TLS-2 Batch runner | **DONE**. WorkManager worker, progress notification, per-file log, results list, output folder / pattern / same folder / overwrite. Files-tab entry via hook 7. |
| TLS-4 Visual compare + report | **DONE**. Word-level Arabic-normalised diff, page alignment (added/removed pages), formatting (font/size), images (hash), optional pixel diff, side-by-side pages with boxes, navigator, filters, change list, overlay, OCR of scanned inputs, PDF report, AI summary. *Moved-page detection is reported as remove + add.* |
| TLS-5 Preflight-lite | **DONE**. 4 profiles, 20 findings, 8 fixups, text report share. *No PDF/XML report formats* (text only). |
| TLS-6 PDF/A + conformance | **DONE** (best effort). PDF/A-1b/2b/3b: decrypt, strip JS / forbidden actions, annotation flags/AP, NeedAppearances/XFA off, layers removed, LZW→Flate, TR/TR2/Interpolate/Alternates/OPI/PostScript removed, attachments handled per part (3b: AFRelationship + /AF), sRGB output intent, XMP pdfaid + DC synced with Info, version, trailer ID. Pages with non-embedded fonts / CMYK / (A-1) transparency are rasterised with searchable text. The self-check validator shows the claim badge. *A full veraPDF-level validator is NOT FEASIBLE on device.* |
| TLS-7 PDF/X and PDF/E | **PARTIAL**. TrimBox/BleedBox fixup and printer marks only; PDF/X needs CMYK colour conversion (no colour engine) — **not feasible**, stated in the PDF/A screen. |
| TLS-8 Grayscale | **DONE**. Vector rewrite of colour operators, ICC RGB/CMYK, forms, annotation appearances, images → grey; raster fallback for shadings / separations. |
| TLS-9 Print production previews | **NOT FEASIBLE**. PDFium wrapper has no separation / overprint / ICC simulation. |
| TLS-10 Printer marks | **DONE**. Preflight fixup: media box +36 pt, crop marks, registration target, file/page/date info, TrimBox. |
| G1 Office → PDF | **DONE via hook 5**. The office module's `OfficePdf` export is plugged into all converters. RTF is converted natively here. *ODT/ODP are not supported* (no reader in core/office). |
| G2 Auto-OCR on export | **DONE**. ConvertFromPdf recognises scanned pages first with the saved languages. |
| G3 RTF, TIFF, embedded images | **DONE** (see CNV-8/9/10). |
| G4 Share-to-PDF text/URL/HTML | **DONE** with hooks 3 and 4. |
| G5 Optimizer with audit | **DONE**. See Optimizer / SpaceAudit; saved presets; many files. CCITT/JBIG2/JPEG2000 encoding, object streams and linearisation **not feasible** (PdfBox 2.0 / no encoders); bilevel images use 1-bit Flate instead of G4. |
| G6 PDF/A (+X/UA flags) | **DONE** as TLS-6. PDF/UA: Xmp supports `pdfuaid`; autotag + checker cover the UA basics. *No PDF/UA claim is written* (no full UA validation). |
| G7 Convert colours | **DONE** (greyscale). *Profile-to-profile conversion is not feasible* (no colour engine). |
| G8 Marks, boxes, hairlines | **DONE** (preflight fixups). |
| G9 Visual content (Express-like) | **NOT FEASIBLE / out of scope**: third-party design-service integration; templates + AI layout would be a separate project. |
| K1 ID card | **DONE**. ID-card type: 2-page capture limit, front + back on one A4 at ID-1 size. |
| K2 Auto-OCR on save + re-OCR | **DONE**. Scan studio auto-OCR with language chips (remembered); re-OCR in `ocr_plus` (old invisible layer removed). Runs in-screen with progress, not WorkManager. |
| K3 Edit existing scan | **DONE**. Saved scan projects reopen with original images: re-crop (quad), rotate, filter, brightness/contrast, eraser, reorder, delete, add pages, rebuild. *Retaking replaces through delete + add.* |
| K4 Filters | **DONE**. Original, auto colour, grey, B&W (Sauvola), light text, whiteboard, remove shadows, receipt; brightness/contrast; per page or all pages. |
| K5 Book mode | **DONE**. Binding found from the luminance profile; split into 2 pages; R→L order switch (default on). |
| K6 Business card → contact | **DONE**. OCR + parser (Arabic/Latin: name, title, company, phones, emails, web, address) → editable sheet → Contacts insert intent or `.vcf` share. *No LLM step* (regex/keywords only). |
| K7 Curvature correction | **PARTIAL**. Deskew + perspective crop; *true text-line dewarping is not implemented* (L effort, P3). |
| K8 Cleanup eraser | **DONE**. Rectangle eraser filled with the surrounding paper colour. |
| K9 Whiteboard/Receipt types | **DONE**. Presets; receipts append merchant/date/total/currency to `الإيصالات.csv` with share. *No "Form" document type* (use Document + B&W). |
| K10 Smart naming | **DONE**. First prominent line + detected date after OCR, when no name was typed. |
| K11 QR scanning | **NOT FEASIBLE** without a new dependency (`mlkit:barcode-scanning`); the document scanner exposes no QR and a hand-written decoder is out of scope. |
| K12 OCR pre-processing + languages | **DONE**. Deskew, despeckle, background normalisation in filters; 19 languages + auto-detect. |
| K13 Recognize one page by long-press | **PARTIAL (hook)**. `RegionOcrSheet` / `openOcrForPage` exist; the gesture lives in reader files (hook 6). |
| K14 Launcher shortcuts | **DONE by view4** (`v4_shortcuts.xml`); hook 8 can point SCAN at the scan studio. |
| L1 Visual + structured compare | **DONE** (see TLS-4). |
| M1 Accessibility checker | **DONE** (see ACC-1). |
| M2 Autotag + reading order | **DONE / PARTIAL** (see ACC-3 / ACC-5). |
| M3 Alt text AI | **DONE** (see ACC-4). |
| M4 Language + title display | **DONE** (quick fixes). Using `/Lang` for the TTS voice is a read-aloud hook (C1 is not mine). |
| M5 TalkBack quality of our UI | **PARTIAL**. All tools4 screens label icon buttons, merge report rows and give status icons spoken labels. *An app-wide audit of other modules' screens is outside my files.* |
| N3 Action wizard | **DONE** (see TLS-1). |
| N4 Batch apply a tool | **DONE**. Recipes › «تطبيق أداة على ملفات» (any step type on many files) + hook 7. |

## Notes and limitations
- **OCR text layers:**
  - They use the app's Arabic font asset when present; otherwise Arabic words are skipped in the layer, as in the existing writer.
  - Latin words use Helvetica, which is allowed for invisible text in PDF/A.
- **Background work:**
  - The batch worker is a normal (not foreground) worker, so very long OCR batches may be stopped by the system after about 10 minutes.
  - Making it foreground needs `setForeground` + the `dataSync` type, which is already merged in the manifest.
- **WebCapture:**
  - The single long page uses `WebView.enableSlowWholeDocumentDraw()` just before creating the WebView.
  - Print mode uses `T4PrintBridge`.
  - Both need the main thread; the worker reaches it through `Dispatchers.Main`.
- **Autotag:**
  - Re-tagging removes all existing marked-content operators.
  - Paths are never wrapped, so vector decoration stays untagged rather than artifact.
  - Figure crops use a private `/SDBox` key on Figure elements.
- **Compare:** word extraction uses PdfBox text positions (display frame). Very large pages are diffed in halves (approximate) above 9 M LCS cells.
- **Grayscale:** Indexed / Separation / DeviceN / pattern colours are not rewritten. Pages using them are rasterised when the fallback is on.
- **Running:** nothing was run on a device. Compile-level verification and the desktop unit runs listed at the top are all that was done.
