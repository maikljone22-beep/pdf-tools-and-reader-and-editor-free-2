# MODULE tools5 (round 5): scan studio, any-format → PDF, PDF/A, optimizer, export, mail merge, portfolios, OCR plus, recipes, compare, accessibility

Spec: `recovered-from-old-chat/MODULE_tools4.md`. I rebuilt it in the new packages `core/tools5` and `ui/tools5`, on top of:
- `core/ocr` (OcrEngine, SearchablePdfWriter)
- `core/office` (OoxmlReader + OfficeHtml). This is the first place they are connected to PDF output.
- `core/convert` (PdfContentExtractor, LayoutAnalyzer, TextRuns)
- `android.print.WebPdfWriter`, PdfOps, FormOps and the ML Kit document scanner

No new dependencies. Gradle was not run. Strings use the prefix `t5_`: 393 keys in `res/values/strings_tools5.xml` (Arabic) and `res/values-en/strings_tools5.xml`. Both files have the same keys and placeholders (checked by script) and both parse as XML.

## What was verified

- **Pure logic: compiled and tested.** I compiled it with kotlin-compiler-embeddable 2.2.20 from the gradle cache and ran it with JUnit 4.13.2. **All 30 tests pass.**
  - Files: TextNorm, CardParsers, SmartNaming, WordDiff, Recipes, RtfWriter, LangDetect (+ MergeMapping), IccSrgb (+ XmpWriter), AccessRules, PixelOps, SourceReaders.
- **Android-only files: type-checked, no errors.** `ScanImage.kt` and `ShareAndPrint.kt` (print service and share import) were checked against `android-36/android.jar` + exifinterface + core. `BatchWorker.kt` was checked against `work-runtime 2.9.1`, with a stub for RecipeRunner.
- **PdfBox and Compose code: not compiled.** Neither `pdfbox-android` nor Compose is in the local gradle cache. I checked the APIs by hand against the existing code (ContentRewriter, AttachmentOps, FormOps, PdfOps, ScannerScreen, ConvertTool…) and against PdfBox 2.0.
- Every `\u` escape that the editor turned into raw characters was converted back by script. The tools5 sources contain no raw invisible or bidi characters.

## Files

### `core/tools5/` (package `com.smartdocs.editor.core.tools5`)

| File | Role |
|---|---|
| TextNorm.kt | Arabic-Indic digits → ASCII, Arabic comparison key (harakat / tatweel / alef / taa-marbuta / yaa), invisible-character filter, safe file names |
| CardParsers.kt | `BusinessCardParser` → `Contact` (name, job title, company, typed phones, e-mails, web, address; Arabic and Latin), `VCard` 3.0 writer (escaping, 75-octet folding), `ReceiptParser` → merchant / ISO date / total / currency (15 currencies, inclusive-VAT totals), `Csv` reader/writer (quotes, `,` `;` tab detection, BOM) |
| SmartNaming.kt | `ScanKind` enum; smart name = prefix + first prominent line (or merchant / contact name) + detected date |
| WordDiff.kt | Myers diff on Arabic-normalised word keys (prefix/suffix trim, O(D²) trace memory), INSERT/DELETE/REPLACE runs, stats, text description |
| Recipes.kt | `StepType` (11 steps with default params), `Step`, `OutputOptions`, `Recipe`, JSON (de)serialisation (unknown steps skipped), `OutputNaming` tokens `{name} {recipe} {date} {time} {n}`, 6 built-ins |
| RtfWriter.kt | RTF writer: runs (bold/italic/size), RTL paragraphs (`\rtlpar`, Arabic charset font), headings (`\outlinelevel`), tables (`\rtlrow`), page breaks, `\uN?` escapes (7-bit output) |
| LangDetect.kt | OCR language auto-detect: script counts, Persian/Urdu letters, Latin stop words; `MergeMapping` (auto field ↔ column mapping, row values, unique file names) |
| IccSrgb.kt | sRGB ICC v2.1 profile generated in code (OutputIntent); `XmpWriter` (PDF/A `pdfaid` packet, optional `pdfuaid`, ISO dates) |
| AccessRules.kt | `AccessFacts`, **30 rules in 7 groups** (document 8, page 7, forms 2, alt text 5, tables 5, lists 2, headings 1), evaluation (PASS/FAIL/MANUAL/NA), HTML report |
| PixelOps.kt | Pure pixel algorithms: grey, auto levels, brightness/contrast, saturation, background estimate + normalisation (shadow / whiteboard), Sauvola B&W (reduced-grid integrals), book binding column, bilevel test |
| SourceReaders.kt | `EmlReader` (MIME multipart, base64, quoted-printable, RFC 2047 / 2231 names, cid: inline images, attachments), `EpubReader` (container → OPF → spine, images inlined, scripts dropped), `MarkdownHtml`, `TextHtml`, `HtmlBits` |
| ScanImage.kt | Bitmap side: EXIF-aware decode, `ScanFilter` (7) + `PageEdit` (filter, brightness, contrast, rotation), ID-card page (front + back at ID-1 size on A4), book split at the binding (right page first option) |
| ScanBuilder.kt | `TextLayer` (invisible OCR text over a placed picture, Arabic shaped + `ActualText`), page pictures per kind, image → PDF (JPEG, or lossless for pure B&W pages) with optional OCR layer |
| OcrRunner.kt | Pre-clean, language auto-detect pass, recognition of PDFs (text pages skipped) / images, low-confidence suspects with word crops, review application, writing (searchable layer / image PDF); `AutoOcr.ocrIfScanned` |
| ToPdf.kt | Any-format → PDF: PDF, images, text (charset guess incl. windows-1256), Markdown, HTML, **DOCX / XLSX / PPTX (core/office readers → OfficeHtml → WebView print)**, EPUB, EML (attachments embedded), URL; `combine` (one bookmark per file) |
| Portfolio.kt | `Embed` (name-tree attachments), `Portfolio` (detect `/Collection`, list / extract parts, create with a cover page + `/Collection` + PageMode UseAttachments) |
| ShareAndPrint.kt | `ShareImport` (SEND / SEND_MULTIPLE: streams copied locally, URL / text / HTML extras), `T5PrintService` (virtual printer "SmartDocs PDF": saves to `Documents/Printed`, notification opens it) |
| PdfInspect.kt | One pass over a PDF: fonts (embedded / ToUnicode), transparency pages, JS, XMP / pdfaid claim, output intents, attachments, annotations without AP, multimedia, images, LZW, CMYK pages, fields, XFA. Also holds the shared `N` COS-name table |
| PdfA.kt | Preflight (PDF/A-2b findings) and conversion. The conversion steps are listed under PDF/A below. Self-check of the output follows the conversion |
| Optimizer.kt | Space audit (bytes per category) and an optimizer: downsampling above a placed dpi, JPEG recompression (never larger), grey images, dedup, metadata / JS / thumbnails / PieceInfo / attachments removal, Flate for uncompressed streams, "only if smaller" |
| ExportPlus.kt | Page images (PNG / JPG / WebP, 72–600 dpi, colour/grey, annotations on/off, ZIP); PDF → RTF through the converter's layout analysis |
| MailMerge.kt | CSV / XLSX table reader; filled copy per row (name from a column) or one merged PDF; optional flatten; check-box value normalisation |
| RecipeRunner.kt | `RecipeStore` (SharedPreferences JSON), `RecipeRunner` (11 step types on a file, non-PDF inputs converted first, output naming, never throws except cancellation), `LogEntry` |
| BatchWorker.kt | `BatchJobs` (job spec + per-file JSON log in `filesDir/t5_batch`, enqueue / list / delete / cancel) and `BatchWorker` (CoroutineWorker, progress data + notification) |
| CompareEngine.kt | `WordExtractor` (PdfBox word boxes, logical order for Arabic lines), compare → word diff + per-page marks, visual overlay bitmap, AI summary (AiCalls) |
| CompareReport.kt | PDF report: summary page (files, counts, AI summary, coloured change list) and landscape side-by-side page pairs with the changes highlighted |
| AccessInspect.kt | Structure-tree walker (RoleMap-aware): every `AccessFacts` count, the figure list (alt, page, BBox) |
| AccessFix.kt | Quick fixes (language, title + DisplayDocTitle, tab order, field TU, bookmarks from headings, alt / decorative) and **basic auto-tagging**, described under Accessibility below |
| T5Hooks.kt | `T5Hooks.describeImage` (vision hook for alt text) and `AltTextAi` (vision hook, else a page-text prompt through AiCalls) |

### `ui/tools5/` (package `com.smartdocs.editor.ui.tools5`)

| File | Contents |
|---|---|
| T5Common.kt | `T5Job` (progress, cancel, result/results/info), `T5JobUi`, `rememberAnyFilePicker`, `T5Card`, `SwitchRow`, `FileListEditor`, `StatusPill`, helpers |
| ScanStudioScreen.kt | Scan studio |
| ScanParts.kt | `ScanPageEditor`, `ContactSheet` (insert intent / .vcf), `ReceiptSheet` (receipts.csv) |
| ToPdfPlusScreen.kt | `ToPdfPlusScreen` (combine / each / web+HTML) and `ShareToPdfScreen` |
| PdfAScreen.kt | `PdfAScreen` and `PreflightList` |
| OptimizeExportScreens.kt | `OptimizeScreen` + `SpaceReport`; `ExportPlusScreen` |
| MergePortfolioScreens.kt | `MailMergeScreen`, `PortfolioScreen` |
| OcrBatchScreen.kt | `OcrBatchScreen`, `OcrReviewDialog` |
| RecipesScreen.kt | `RecipesScreen`, `BatchScreen` (live WorkManager progress, per-file log) |
| RecipeEditor.kt | Full-screen recipe editor |
| Compare2Screen.kt | Side-by-side with boxes, overlay, change list, navigator, PDF report, AI summary; tablet layout side by side |
| AccessibilityScreen.kt | Results by group, HTML report, quick-fix dialog, figures alt-text panel |

### Tests (`app/src/test/java/com/smartdocs/editor/core/tools5/`)

| File | Covers |
|---|---|
| CardParsersTest.kt (7) | Latin / Arabic cards, vCard escaping and folding, Latin / Arabic receipts, amounts, CSV |
| WordDiffTest.kt (7) | Equal / insert / delete / replace, empty inputs, Arabic normalisation (harakat, alef, taa marbuta, Arabic digits), Arabic change, range coverage, 3000-word input |
| RecipeAndNamingTest.kt (8) | Recipe JSON round trip and tolerance, output naming, smart names, safe file names, language detection, merge mapping and file names |
| WritersTest.kt (8) | RTF escapes and document structure (braces balanced, 7-bit), ICC header and tag table, XMP packet, 30 accessibility rules + report, EML multipart, Markdown, binding column |

### Existing files edited (my area, small and additive)

- `ui/tools/ConvertFromPdf.kt`: **auto OCR before converting a scanned PDF.**
  - Before PDF → DOCX/XLSX/PPTX(editable)/HTML/TXT, `AutoOcr.ocrIfScanned` is called with the saved OCR languages. It acts when at least half of the pages have no text.
  - The temporary searchable copy is converted, then deleted.
  - New label `t5_cv_auto_ocr`. Two small inserts, near `val convertingFmt` and before `ConvertEngine.convert`.
- `ui/ocr/OcrScreen.kt`: a top-bar icon → `ocr_plus` (the current file is passed).
- `ui/scanner/ScannerScreen.kt`: a top-bar icon → `scan_studio`.

## Hooks for the lead (exact)

### H1. `ui/tools/ToolScreens.kt`: routes

Anchor: the line `"compare" -> CompareScreen(nav, uri)`. Insert after it:
```kotlin
        "scan_studio" -> com.smartdocs.editor.ui.tools5.ScanStudioScreen(nav, uri)
        "to_pdf_plus" -> com.smartdocs.editor.ui.tools5.ToPdfPlusScreen(nav, uri)
        "share_to_pdf" -> com.smartdocs.editor.ui.tools5.ShareToPdfScreen(nav)
        "pdfa" -> com.smartdocs.editor.ui.tools5.PdfAScreen(nav, uri)
        "optimize" -> com.smartdocs.editor.ui.tools5.OptimizeScreen(nav, uri)
        "export_plus" -> com.smartdocs.editor.ui.tools5.ExportPlusScreen(nav, uri)
        "mail_merge" -> com.smartdocs.editor.ui.tools5.MailMergeScreen(nav, uri)
        "portfolio" -> com.smartdocs.editor.ui.tools5.PortfolioScreen(nav, uri)
        "ocr_plus" -> com.smartdocs.editor.ui.tools5.OcrBatchScreen(nav, uri)
        "recipes" -> com.smartdocs.editor.ui.tools5.RecipesScreen(nav, uri)
        "batch" -> com.smartdocs.editor.ui.tools5.BatchScreen(nav)
        "compare_plus" -> com.smartdocs.editor.ui.tools5.Compare2Screen(nav, uri)
        "accessibility" -> com.smartdocs.editor.ui.tools5.AccessibilityScreen(nav, uri)
```
Optional: to make the better compare the default, change `"compare" -> CompareScreen(nav, uri)` to `"compare" -> com.smartdocs.editor.ui.tools5.Compare2Screen(nav, uri)`.

### H2. `ui/tools/ToolsScreen.kt`: tool cards

Anchor: the line `ToolDef("compare", R.string.tool_compare, Icons.Default.Compare, 3),`. Insert after it:
```kotlin
    ToolDef("scan_studio", R.string.t5_scan_title, Icons.Default.DocumentScanner, 0),
    ToolDef("to_pdf_plus", R.string.t5_topdf_title, Icons.Default.PictureAsPdf, 0),
    ToolDef("mail_merge", R.string.t5_mm_title, Icons.Default.MergeType, 0),
    ToolDef("portfolio", R.string.t5_portfolio_title, Icons.Default.FolderZip, 0),
    ToolDef("ocr_plus", R.string.t5_ocr_title, Icons.Default.DynamicFeed, 3),
    ToolDef("export_plus", R.string.t5_export_title, Icons.Default.Output, 3),
    ToolDef("optimize", R.string.t5_opt_title, Icons.Default.Compress, 3),
    ToolDef("pdfa", R.string.t5_pdfa_title, Icons.Default.Inventory, 3),
    ToolDef("accessibility", R.string.t5_acc_title, Icons.Default.Accessibility, 3),
    ToolDef("compare_plus", R.string.t5_cmp_title, Icons.Default.Difference, 3),
    ToolDef("recipes", R.string.t5_recipes_title, Icons.Default.AutoMode, 3),
    ToolDef("batch", R.string.t5_batch_jobs, Icons.Default.Schedule, 3),
```
- `Icons.Default.*` comes from material-icons-extended, which the file already imports with `filled.*`.
- If `Difference` is missing in the icons version, use `Icons.Default.Compare`.
- `share_to_pdf` is reached only through H4, not from the grid.

### H3. `AndroidManifest.xml`

1. **Share to PDF.** In the existing `<intent-filter>` with `android.intent.action.SEND`, after `<data android:mimeType="image/*" />`, add:
```xml
                <data android:mimeType="text/*" />
                <data android:mimeType="application/epub+zip" />
                <data android:mimeType="message/rfc822" />
                <data android:mimeType="application/vnd.openxmlformats-officedocument.wordprocessingml.document" />
                <data android:mimeType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" />
                <data android:mimeType="application/vnd.openxmlformats-officedocument.presentationml.presentation" />
```
2. In the `SEND_MULTIPLE` filter, after `<data android:mimeType="image/*" />`, add:
```xml
                <data android:mimeType="application/pdf" />
                <data android:mimeType="text/*" />
                <data android:mimeType="application/vnd.openxmlformats-officedocument.wordprocessingml.document" />
                <data android:mimeType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" />
                <data android:mimeType="application/vnd.openxmlformats-officedocument.presentationml.presentation" />
```
3. **Virtual printer.** Inside `<application>`, after the `ReadAloudService` `<service … />`, add:
```xml
        <service
            android:name=".core.tools5.T5PrintService"
            android:exported="true"
            android:label="SmartDocs PDF"
            android:permission="android.permission.BIND_PRINT_SERVICE">
            <intent-filter>
                <action android:name="android.printservice.PrintService" />
            </intent-filter>
        </service>
```
- WorkManager needs no manifest entry. `POST_NOTIFICATIONS` is already declared.
- The batch worker is a normal worker, not a foreground one. Very long OCR batches can therefore be stopped by the system after about 10 minutes. Making it a foreground worker would need `setForeground` plus a `dataSync` foreground-service type and permission.

### H4. `MainActivity.kt`: share intents → Share to PDF

1. In the class body, next to `private val incoming = …`, add:
```kotlin
    private val incomingRoute = MutableStateFlow<String?>(null)
```
2. In `setContent`, right after the existing `LaunchedEffect(uri) { … }` block, add:
```kotlin
                        val route by incomingRoute.collectAsStateWithLifecycle()
                        LaunchedEffect(route) { route?.let { nav.navigate(it); incomingRoute.value = null } }
```
3. In `handleIntent`, as the first statements of the body (before `val uri: Uri? = when …`), add:
```kotlin
        if (com.smartdocs.editor.core.tools5.ShareImport.handles(intent)) {
            openedByIntent = true
            val app = application as SmartDocsApp
            val i = intent!!
            lifecycleScope.launch {
                com.smartdocs.editor.core.tools5.ShareImport.pending = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.smartdocs.editor.core.tools5.ShareImport.read(this@MainActivity, i, java.io.File(app.documents.tempDir, "shared"))
                }
                incomingRoute.value = Routes.tool("share_to_pdf")
            }
            return
        }
```
- `handles()` takes every SEND / SEND_MULTIPLE except a single shared `application/pdf`, which keeps opening in the reader as today.
- Single shared images now go to the Share-to-PDF screen, where one tap converts them.

### H5. Optional integration points

- **Vision alt text.** Set `com.smartdocs.editor.core.tools5.T5Hooks.describeImage = { ctx, bitmap, arabic -> … }`, for example in `SmartDocsApp.onCreate` or from the AI module. Without it, alt-text suggestions use the page text through `AiCalls` (any configured endpoint or the proxy).
- **Files-tab multi-select (owner: files).** Add «تطبيق وصفة…» → `Routes.tool("recipes")`. To queue directly instead:
```kotlin
BatchJobs.enqueue(ctx, title, files.map { it to null }, recipe /* or null */, toPdfOnly = recipe == null, outDir = app.documents.docsDir)
```
- **Reader (owner: reader).**
  - When `Portfolio.isPortfolio(file, pw)` is true, offer «فتح كحافظة» → `Routes.tool("portfolio", uri)`.
  - The overflow menu can offer `accessibility`, `pdfa` and `optimize` with the current `uri`.
- **Compress screen (`ui/tools/PageTools.kt`).** A link to `optimize` can be added. I did not add it, to keep my edits to the three files listed above.

## Status of every feature in the spec

| Feature | Status |
|---|---|
| Scan studio: document | **DONE**. ML Kit scanner (full mode, gallery import) or picked images; per-page filter (7), brightness/contrast, rotation, reorder, delete, apply-to-all; paper FIT/A4/Letter; B&W pages stored lossless |
| ID card front + back on one page | **DONE**. Two shots composed on one A4 page at ID-1 size (portrait shots turned landscape); more shots become extra pages |
| Business card → vCard contact | **DONE**. OCR (forced for this kind) → parser (Arabic/Latin, phone types) → editable sheet → Contacts insert intent or `.vcf` share. *No LLM step* (rules only) |
| Receipt → CSV | **DONE**. Merchant / date / total (inclusive-VAT aware) / currency → editable sheet → appended to `Documents/receipts.csv` (UTF-8 BOM) + share |
| Whiteboard | **DONE**. Background normalisation + saturation + contrast preset |
| Book: split at binding, right page first | **DONE**. Binding = darkest smoothed column (35–65 % band), "right page first" switch (default on). *Curved-page dewarping NOT done* |
| Batch scanning | **DONE**. Batch kind: every scanner round is its own document; all are saved with smart names; results list |
| Per-page filters, brightness/contrast | **DONE** (see above; live preview in the page editor) |
| Auto OCR | **DONE**. Invisible text layer written while building the PDF; language chips or auto-detect; settings remembered |
| Smart file naming | **DONE**. Prefix by kind + first prominent line / merchant / contact name + detected date (else today); typed name wins |
| Any-format → PDF combiner | **DONE**. PDF, images, text/CSV/code, Markdown, HTML, **DOCX/XLSX/PPTX (core/office readers, now connected)**, EPUB, EML (attachments embedded), URL / pasted HTML; reorder, one bookmark per file; convert each file (in screen or WorkManager). *No legacy .doc/.xls/.ppt, ODF or RTF input* (no readers in core/office) |
| Share to PDF from other apps | **DONE with hooks H3 + H4**. Files (any supported type), URL, plain text, HTML |
| Virtual printer "SmartDocs PDF" | **DONE with hook H3**. PrintService with one printer (A4/Letter/A5/A3/Legal, colour/mono); saves to `Documents/Printed`, notification opens it. The user has to enable the service once in system print settings (note shown in the To-PDF screen) |
| PDF/A-2b conversion | **DONE (best effort)**. See the PDF/A section below |
| Preflight report | **DONE**. 15 finding codes with severity, counts, pages, font names; shown before and after conversion |
| Optimizer + space report | **DONE**. Space audit in 11 categories (before → after); downsample above a dpi, JPEG quality, grey, dedup, metadata / JS / thumbnails / PieceInfo / attachments removal, Flate for uncompressed streams, only-if-smaller |
| "Fast web view" (linearization) | **NOT FEASIBLE**: PdfBox(-Android) 2.0 cannot write linearised files or object streams. Shown as a disabled option with the reason, and as a preflight info item |
| Export to images | **DONE**. PNG / JPG / WebP, 72–600 dpi, colour/grey, annotations on/off, page range, ZIP. *TIFF/GIF/JPEG 2000 not offered* (no encoder in the platform) |
| Export to RTF | **DONE**. Through the converter's layout analysis: paragraphs with bold/italic/size runs, headings, RTL paragraphs and tables, page breaks |
| Mail merge (CSV → filled copies) | **DONE**. Fillable PDF (AcroForm) template + CSV or XLSX; auto mapping with editable dropdowns; name column; one file per row or one merged PDF; optional flatten. *Templates without form fields are not supported* (message shown) |
| PDF portfolios | **DONE**. Open (list, open PDF parts, share, extract all) and create (cover page, embedded files, `/Collection` details view). *No folders inside portfolios* |
| OCR batch | **DONE**. Many PDFs / images in the screen or in the background (recipe with one OCR step) |
| Image pre-clean | **DONE**. Grey + background normalisation + level stretch before recognition (switch) |
| Language auto-detect | **DONE**. Quick ara+eng pass on a downscaled first page → script / Persian / Urdu letters / Latin stop words → languages (downloaded on demand) |
| Review of low-confidence words | **DONE** (single file). Pager with the word picture, edit / accept / "not text"; decisions applied before the layer is written |
| Auto OCR before converting a scanned file | **DONE** in `ConvertFromPdf.kt` (see "Existing files edited") |
| Batch recipes (saved step sequences, WorkManager, per-file log) | **DONE**. 11 step types with options, 6 built-ins, editor (order, enable, params, naming pattern, same folder, overwrite), import / export `.sdrecipe`, background jobs screen with live progress, per-file log (open / share results), cancel |
| Better compare: word-level diff with Arabic | **DONE**. Myers diff on normalised words (harakat / alef / taa marbuta / digits); Arabic lines in logical order |
| Side-by-side + visual overlay | **DONE**. Side-by-side pages with coloured boxes (stacked on phones); pixel overlay (red / blue / grey); changed-page navigator; change list |
| Compare PDF report | **DONE**. Summary page (counts, AI summary when made, coloured change list) + side-by-side highlighted page pairs (up to 40) |
| AI summary hook | **DONE**. `CompareEngine.aiSummary` through `AiCalls` (configured endpoint / proxy); button shown only when AI is available |
| Accessibility checker (30 rules) + report | **DONE**. 30 rules / 7 groups, PASS/FAIL/MANUAL/NA with counts, fixable marker, HTML report (share). Contrast, reading order and scripts are MANUAL |
| Auto-tagging basics | **PARTIAL (basic by design)**. Document/Sect/P runs, a Figure per image, Link/Form/Annot OBJR, ParentTree, MarkInfo, Lang, Tabs. *No headings, lists or tables from layout* (that needs mapping layout blocks to content operators). Content inside form XObjects is not tagged separately |
| Alt text via AI hook | **DONE**. Figures panel: edit, AI suggestion (vision hook `T5Hooks.describeImage` with the page picture, else a page-text prompt), decorative → Artifact, save |
| Quick fixes | **DONE**. Title (suggested) + DisplayDocTitle, language, tab order, field descriptions from names, bookmarks from detected headings, auto-tag |

### PDF/A-2b conversion details

- **Fixed automatically:**
  - Encryption is removed.
  - JavaScript and forbidden actions are stripped (Names/JavaScript, catalog / page / annotation AA, non-GoTo OpenAction).
  - Multimedia, file-attachment annotations and annotations without an appearance are removed. The remaining annotations get the Print flag and lose Hidden/NoView.
  - XFA is removed and NeedAppearances is set to false.
  - LZW streams are re-encoded as Flate.
  - TR/TR2, Interpolate, Alternates, OPI and PS are removed.
  - Attachments are removed (option on).
  - An sRGB OutputIntent is added, using the ICC profile generated in code.
  - The XMP `pdfaid` packet is written and kept in sync with Info (dates, title, author, producer).
  - A trailer `/ID` is set, and the version is set to 1.7.
- **Option, rasterise problem pages:** pages that use non-embedded fonts, or DeviceCMYK, are rasterised at 200 dpi. Their text stops being selectable; run OCR afterwards if needed.
- **Option, flatten transparency:** rasterises pages with transparency. PDF/A-2b does not require it.
- **Not done:** a full veraPDF-level validator is **NOT FEASIBLE** on the device; the self-check re-runs the preflight on the result. CMYK → RGB colour conversion is **NOT FEASIBLE** (no colour engine), so those pages are rasterised instead.

### Accessibility auto-tagging details

- Untagged documents only.
- The page content is rewritten with PDFStreamParser + ContentStreamWriter:
  - painting and text at marked-content depth 0 go into `/P` MCID runs;
  - every image `Do` outside text objects gets its own `/Figure` MCID;
  - existing marked content is nested, not split.
- Link, Widget and other annotations get `Link` / `Form` / `Annot` elements with OBJR and `StructParent`. Form elements take TU as their Alt.
- Also written: ParentTree + ParentTreeNextKey, MarkInfo/Marked, /Lang, /Tabs /S.

## Notes and limitations

- **WebView converters** (HTML / Office / EPUB / EML / URL) run on the main thread through `WebPdfWriter`. Office HTML references the web font in `OfficeHtml`; offline it falls back to the system font.
- **XLSX → PDF** prints the HTML workbook view: all sheets, A4, the WebView's own pagination.
- **Recipe passwords:** a PROTECT step's password is stored in the recipe JSON (app-private SharedPreferences). Batch file passwords are stored in the job spec in app-private storage.
- **Compare** assumes both versions have a text layer. For scans, run `ocr_plus` first.
- **Not tested on a device.** Only the compile and unit checks listed at the top were done.
