# Root cause: Arabic page corrupted after in-reader OCR (r5)

**Document:** «المرشد_2025م_في_الكيمياء_الجزء_الاول.pdf»: 183 pages, iText 5.4.1, MediaBox = CropBox = 595.32 x 841.92, /Rotate 0 on every page.

I worked only on a copy at `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\rootcause\in\orig.pdf`. Its sha256 is `ff04d8244d66b9b334775694aaf8ef4bc7a572442c5ce5dbe1f0cb7865d5848f`, the same as the original in Downloads, which I did not modify.

**Which page:** the screenshots show the opening page of unit 2 «الكيمياء الحرارية / Thermochemistry». In the file this is **PDF page 54** (book page 41). PDF page 1 is the cover. Everything below refers to page 54 unless stated otherwise.

All folder names below are relative to the scratch folder `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\rootcause\`.

**Summary.** The corruption is not a viewer bug. Before commit 847d5d8, the "editable text" OCR mode rebuilt every page that contained any image. In this file that means all 183 pages, because every page carries tiny 2x2-px highlight images. A rebuild works like this:
1. It rasterises the page.
2. It paints over the recognised word boxes.
3. It re-types the OCR words visibly, in Noto Naskh, with horizontal scaling from 20 to 500 % and font sizes up to 200 pt.

I reproduced this with the app's own Kotlin code (old and new `EditableTextPdfWriter`), real PdfBox-Android and real Tesseract 5.4 word boxes. The result shows the symptoms from the tablet:
- duplicated and overlapping Arabic;
- junk words in the right and left margins (the decorative chain border read as `0`, `2`, `3`, `1`, `EEX`, `HEN`);
- words in the wrong font and position;
- a corrupted footer (the phone number came out as `77704175424` instead of `777047544`).

The fixes the lead had already committed (847d5d8, df980cf) are correct for this page: it now stays pixel-identical. They are not sufficient, for three reasons:
- The original text layer of page 54 is itself broken: the fonts' ToUnicode maps give wrong letters. The fix only keeps the page because `isGarbage` fails to notice that. Once garbage detection is improved (as the audit asks), the old branch would rebuild every such page again.
- Light text on dark boxes is erased: it is redrawn black on black.
- The OCR planner still selects almost every page.

All of these are now fixed and covered by tests.

---

## 1. Rendering architecture and source files

| Layer | Files | Role |
|---|---|---|
| PDF engine | `core/pdf/PdfSession.kt` (pdfiumandroid) | `render(page,w,h,night,annots)` gives an exact-size ARGB bitmap of the **CropBox** (64 MB LRU keyed `page:w:h:night:annots`). `renderRegion` gives zoom tiles. `text()`, `charBoxes()` (user space, y up), `links()`, `pageSize()` (CropBox, rotated). |
| Page view | `ui/reader/PageView.kt`, `PageCanvas.kt`, `PageGeometry.kt` | One `PageCanvas` per page: base bitmap drawn at page size, high-zoom tile on top. Then the overlays: page tint, link highlight, search hits, pending annotations, annot2, selection, measure, placement. After that `InPageFormLayer` (forms5) and the action bars. One transform, `PageGeometry.pdfToScreen` = `(x*scale, (pageH-y)*scale)`, then a rotation `withTransform`. |
| Overlays | `ui/forms5/InPageFormLayer.kt`, `ui/view5/PageOverlays5.kt`, `ui/reader/annot/*` | Only draw stored or pending annotations and form fields. |
| Reflow | `ui/view5/ReflowReader.kt` | Re-lays out `ReaderState.pageText(p)` + `charBoxes(p)`, i.e. the PDFium text layer. |
| Editing | `core/edit/*` (`PageScanner` skips render modes 3/7, the invisible OCR text) | |
| OCR | `ui/ocr/OcrInPlace.kt` (`runOcrToFile`), `core/ocr/MixedPageOcr.kt` (which pages), `core/ocr/OcrEngine.kt` (Tesseract4Android), `core/ocr/SearchablePdfWriter.kt` (invisible layer), `core/ocr/EditableTextPdfWriter.kt` (page rebuild), `core/tools5/OcrRunner.kt` (batch, AutoOcr) | Reader OCR: `QuickController.ocr` → `state.applyInPlace` (backup + undo) → `runOcrToFile` → the file is replaced → `ReaderState` reload (new session, `textCache`/`boxCache` cleared, `renderVersion++`). |
| Arabic text | `core/pdf/ArabicShaper.kt` → `core/edit/EditBidi.kt`, `core/search/ArabicTextFix.kt`, `ArabicNormalizer`, `TextMatcher` | |

## 2. What the original page is made of

Inspected with PyMuPDF and pypdfium2 (the same PDFium engine as the app). Outputs: `out_struct_p54.txt` and `png\orig_p54_textlayer_boxes.png`.

- Content stream: 233 KB. It contains 128 BT, 615 `re`, 389 `S`, 101 `Do`, 289 BDC/EMC (Artifact), `Tr 2` 4 times, no OCG, no annotations, no widgets.
- **Text layer: 540 characters for a page that shows about 2,000.**
  - Most of the visible body text is vector paths, not text.
  - What is text uses Type0 fonts (PT Bold Heading, Simplified Arabic, Times New Roman). Their **ToUnicode maps glyphs to the wrong Arabic code points**: «اٌذسط األوي ِمذِخ يف اٌىُُّبء احلشاسَخ» for «الدرس الأول مقدمة في الكيمياء الحرارية». PyMuPDF extracts the same wrong letters, so the fault is in the PDF, not in PDFium.
  - The running header text («المرشد في الكيمياء …») lies under the banner picture, invisible.
- **Images:** 98 placements. Most are **2x2-px Indexed images** (Word highlight shading behind text lines). There is one 448x96 RGB label image of 161x35 pt.
- The watermark emblem, the table and the coloured boxes are vector graphics.

**Survey of the 9 PDFs in the folder** (real Kotlin `ArabicTextFix` on PDFium text of all 1,022 pages, `out_quality_kotlin.txt`):
- 8 of the 9 documents have pages with wrongly mapped fonts.
- «…الجزء_الاول»: pages 1–53 are clean Word-export text; pages 54–182 are broken (129 pages).
- «…الوحدة_الأولى» (b.pdf) has none: it only has the reversed Word ligatures («يف», «امل»), which are readable.

## 3. Lifecycle of the affected page (pre-fix build)

1. The user opens the OCR sheet in the reader and picks «نص قابل للتعديل».
2. `QuickController.ocr` → `applyInPlace`, which backs up the file and keeps undo.
3. `runOcrToFile` → `MixedPageOcr.plan`. Page 54 has text and `hasImages` = true (the 2x2 tiles count as images). The page is planned, and so are **all 183 of 183 pages** (`scripts/plan.py`).
4. The page is rendered at 300 dpi, capped at 3,500 px (2474x3500), and recognised by Tesseract (ara+eng, tessdata_fast in that build).
5. `EditableTextPdfWriter.replacePage` (old):
   - every word with confidence ≥ 45 has its box painted with the median border colour;
   - the page content is replaced (OVERWRITE, resources cleared) by a JPEG at 200 dpi;
   - every word is drawn **visibly**: font size = box height / glyph ratio (up to 200 pt), Tz 20–500 %.
6. The file is replaced in place and the reader reloads: new session, caches cleared, `renderVersion++`. From then on the reader faithfully shows the damaged PDF.

## 4. Hypotheses

| # | Hypothesis | Evidence for | Evidence against | Test | Actual result | Conclusion | Confidence |
|---|---|---|---|---|---|---|---|
| H1 | Visible OCR text drawn over the page | Old `replacePage` writes OCR words with `RenderingMode.FILL`, Tz 20–500 %, ≤ 200 pt, and paints over glyphs only where conf ≥ 45. | — | Ran the app's pre-fix writer (git 28ffd43) on page 54 with real Tesseract boxes (`jvm/`, `MainKt old`), rendered with PDFium. | **9.4 % of pixels changed.** 276 visible OCR words, 48 of them in the side margins or > 20 pt. Junk border "words" (`0`, `2`, `1`, `EEX`, `HEN`) became visible. Footer phone number became `77704175424`. Mixed fonts and overlaps throughout (`png\p54_old_fast_p54.png`, `png\compare_footer.png`). | **Confirmed root cause.** It is not a viewer overlay: the visible text is written into the PDF. | High |
| H2 | Same content rendered twice | In the rebuilt page, glyphs that were not erased (conf < 45, or Tesseract boxes that miss the dots) stay in the JPEG, and the OCR word is drawn next to or over them. | No double rendering in the viewer: one bitmap per page, one tile. | Same as H1; viewer code reading. | Doubles are visible in the rebuilt PDF itself (`png\crop_body_p54_old_fast.png`). | Confirmed, as a consequence of H1. | High |
| H3 | Wrong PDF→view transform | `PageGeometry.pdfToScreen` ignores a non-zero CropBox origin (latent bug for cropped PDFs). `SearchablePdfWriter` used the MediaBox (DOC-09). | This file's boxes are all at origin 0,0 with no rotation, so neither applies here. | `CropCheck` (DOC-09): page with MediaBox 0 0 700 900 and CropBox 50 30 645 872; ran the HEAD writer and the fixed writer, measured PDFium char boxes. | HEAD layer at x 120.5–233.7, bottom 664.1; fixed layer at **x 152.4–248.6, bottom 671.5** (expected 150–250 and about 672). | Not a cause here. DOC-09 confirmed and fixed. The viewer CropBox-origin issue is reported, not fixed (another agent's file, not reproduced). | High / Medium |
| H4 | No clipping to page bounds | In `CropController.FULL` mode, `PageCanvas` draws overlays without `clipToBounds`, so annotation or measure overlays can spill outside the page. | The base bitmap is drawn at exactly page size and cannot overflow. The viewport is clipped (`PageView` L309). Page 54 has no annotations, links or forms. | Code reading. | Nothing in the viewer can paint page text outside the page. The "numbers outside the frame" are OCR words written into the right margin (H1). | Not a cause. | High |
| H5 | Stale overlays after navigation or re-render | The high-zoom `tile` is not cleared when `renderVersion`/`session` changes, so an old tile can show for about 220 ms plus render time after OCR replaces the file. A tiny race: `textCache` is cleared before `session = fresh`. | The base bitmap is keyed by page and re-rendered on `renderVersion`. `reload()` clears text and box caches. The bitmap cache is per session. | Code reading only. | Transient at high zoom only; cannot explain a lasting corruption. | Not a cause. A minor transient is noted as a recommendation (not fixed: `ui/reader` belongs to another agent, not reproduced). | Medium |
| H6 | Arabic shaping, bidi or font mapping in the app | The OCR rewrite uses Noto Naskh plus our shaper, so style changes are visible. | Visible glyphs of the original come from PDFium with embedded fonts; the app does no shaping for display. | Rendered the original with PDFium: correct (`png\orig_p54.png`). | The original renders perfectly; the only app shaping involved is in the OCR rewrite. | Not a cause of the corruption. Font and style loss is inherent in rewriting words. | High |
| H7 | The original PDF has a malformed text layer | ToUnicode maps glyphs to wrong letters. 75 % of the visible text has no text layer (vector paths). Hidden header text sits under the banner. | Visually the original is perfect. | PyMuPDF and PDFium extraction; `ArabicTextFix` measurement over 1,022 pages. | Confirmed: 129/183 pages of this file and pages in 7 other PDFs. It makes copy, search and reflow show wrong letters (the "differs between view modes" symptom) and fooled `isGarbage`. | **Confirmed contributing cause** (garbage detection, reflow/search output). It cannot be repaired from the PDF. | High |
| H8 | Reflow, accessibility, annotation, selection or search overlays interfere | Reflow shows the PDFium text layer, so wrong letters on page 54 (H7), and after OCR it shows OCR text. Search hits use the same broken layer. | These are separate views, not drawn over the page. Page 54 has no annotations or forms. `core/edit` ignores invisible OCR text. | Code reading. | They explain why views differ, not the page corruption. | Not a cause of the page corruption. | Medium |

## 5. Confirmed root causes

1. **RC1 (main):** before 847d5d8, the editable OCR rebuilt pages that have real text, and wrote OCR words visibly with no limits. Reproduced (H1/H2).
2. **RC2:** `MixedPageOcr.plan` treated "has any image XObject" as "has a scanned part". 2x2-px highlight tiles, logos and watermarks put 183/183 pages (and 53/53 of b.pdf) into OCR. This is DOC-12.
3. **RC3:** text-layer quality was judged only by PUA and U+FFFD characters (`isGarbage`). Wrongly mapped Arabic fonts passed as good text in 8 of the 9 PDFs (DOC-04).
   - Effect today: OCR words that overlap the broken layer are dropped by `dropExisting`, so search stays broken.
   - With the 847d5d8 branch (`isNotBlank && !isGarbage`), fixing detection naively would make those pages rebuild again.
4. **RC4 (found while verifying 847d5d8):** the current `EditableTextPdfWriter` still damages genuine image pages.
   - `inkColor` only looked for pixels darker than the background.
   - White words in a black box were erased and redrawn **black on black**.
   - Measured on the cover, page 2: `png\compare_p2_orig_vs_current.png` and `png\compare_p2_orig_before_after_ink.png`.

## 6. Fixes (files, before → after)

| File | Before | After |
|---|---|---|
| `ui/ocr/OcrInPlace.kt` | Rebuild when `text.isBlank() \|\| isGarbage(text)`. | Rebuild **only when the page has no text at all**. Any page with text objects, even garbage or wrongly mapped ones, gets invisible OCR words only (`addInvisible`). |
| `core/search/ArabicTextFix.kt` (owned) | `isGarbage` only. | New `isUnusableText` = `isGarbage \|\| isBrokenArabic`. `implausibleWord` flags a word containing any of: tanween followed by a letter (except a final «ًا/ًى»), a word-initial vowel, letters not used in Arabic/Persian/Urdu text (U+063B–063F, U+066E/F, Sindhi/Pashto letters such as ڀ ڄ ٴ, marks U+0656–065F), or an Arabic-Indic digit between letters. A page is broken when ≥ 15 % of at least 10 Arabic words (≥ 4) are implausible. `isGarbage` is unchanged, because `core/edit` uses it too. |
| `core/ocr/MixedPageOcr.kt` (owned) | Plan = blank \|\| any image \|\| garbage. `dropExisting` skipped only for garbage. | Plan = blank \|\| `isUnusableText` \|\| `scanShare ≥ 10 %`. `scanShare` covers pictures of at least 64x64 px with fewer than 10 text characters on them, clipped to the page; placements come from the content stream via `PDFStreamEngine` (`Do`/`BI` + CTM). `dropExisting` keeps every OCR word on an unusable layer. Pure `Box`/`scanShare` are unit-tested. |
| `core/ocr/EditableTextPdfWriter.kt` (owned) | `inkColor` dark-only, black when no ink. `Rect.intersect` result ignored (LINT-05: an off-bitmap box stays unclipped, then `getPixels` throws). | `inkOf`: dark or light ink, whichever dominates, pushed away from the background. `null` means the word keeps its scanned glyphs and gets invisible text only. `if (!r.intersect(...) \|\| r.isEmpty) skip`. |
| `core/ocr/SearchablePdfWriter.kt` (owned) | Maps word boxes with the MediaBox. | Uses the CropBox (PdfBox clips it to the MediaBox, as PDFium does), the same box as the render. This is DOC-09. |
| `core/ocr/OcrEngine.kt` (owned) | Downloads from `tessdata_best/raw/main` with no check (ARCH-17/SEC-34). | Pinned to commit `e12c65a915945e4c28e237a9b52bc4a8f39a0cec` (current `main`, 2024-03-09). Checks size and **SHA-256** for all 15 languages the sheet offers or auto-detect can return. I downloaded the files here, computed the hashes, and confirmed their git blob ids match the GitHub API. `isLanguageInstalled` also checks the exact size. An unknown language is refused. |
| `core/tools5/LangDetect.kt` + `OcrRunner.kt` | `detect` keeps a script only at ≥ 10 %. a.pdf, with 4.8 % Latin, gets `ara` only (DOC-11). | New `forOcr`: Arabic-script result plus ≥ 8 Latin letters gives `ara+eng`. `OcrRunner.detectLanguages` uses it. `detect` is unchanged (existing tests). |
| `core/convert6/PdfFlow6.kt`, `ui/tools/ConvertFromPdf.kt` (**other agents' files**, minimal edit with Edit) | `runCatching { AutoOcr.ocrIfScanned(...) }.getOrNull()` swallowed `CancellationException` (DOC-24). | `try … catch (CancellationException) { throw e } catch (Exception) { null }`. |

Not changed:
- The viewer files (H3 CropBox origin in `PageGeometry`, H4 overlay clipping, H5 stale tile). They are not causes here, were not reproduced, and belong to other agents. They are listed as recommendations.
- No repair pass for reversed Word ligatures (b.pdf). Its text is readable and the audit marks that as "consider". `ArabicTextFix.fix` already handles visual order.

## 7. Tests and their real results

**JVM harness running the app's real Kotlin code.** It builds `EditableTextPdfWriter`, `SearchablePdfWriter`, `MixedPageOcr`, `ArabicShaper`, `EditBidi` and `ArabicTextFix`, plus the pre-fix writers from git. It runs on real PdfBox-Android classes with java.awt-backed `android.graphics` stubs, Tesseract 5.4.0 on the PC (tessdata_fast to match the pre-fix build, tessdata_best for the current one), and PDFium (pypdfium2) for rendering and text. Script: `regress.sh`.

1. **Page 54 with the current decision rule:** `isUnusableText=true` → addInvisible. **Pixel diff vs original: 0.000 %** (max diff 0). Chars 540 → 2514 (invisible OCR layer added).
2. **Page 54 with the pre-fix writer:** 9.407 % of pixels changed (fast models) and 8.779 % (best models). The current `replacePage` forced on page 54 would still change 7.4 % (fonts), which is why the decision rule, not the writer, is what protects digital pages.
3. **Real `MixedPageOcr.plan` on 9 PDFs** (`out_plan_after.txt`):
   - «…الجزء_الاول»: planned **183 → 140**. The 129 broken-text pages get an invisible layer. Rebuild in editable mode: **183 → 3** (pages 2, 3, 13; 2 is an image cover, 3 and 13 are blank).
   - b.pdf: planned **53 → 10**.
   - Rebuilt pages in all 9 PDFs are only pages with no text at all.
4. **Quality rule on 1,022 pages:**
   - all broken documents are flagged;
   - b.pdf: 0 pages flagged;
   - clean pages of «…الجزء_الاول» 1–53: 0 flagged;
   - samples were reviewed by eye (`out_quality_samples.txt`).
5. **Cover page 2** (a genuine image page that is still rebuilt): with the ink fix, the white words in the black oval are redrawn white instead of disappearing (`png\compare_p2_orig_before_after_ink.png`).
6. **DOC-09 crop test:** see H3. HEAD is off by about 30 pt horizontally and 8 pt vertically; the fix lands within 2.5 pt of the expected position.

**JUnit (new):**
- `app/src/test/java/com/smartdocs/editor/core/search/ArabicTextQualityTest.kt`: real extracted strings from 5 broken and 4 clean pages, vocalised Arabic, word rules.
- `app/src/test/java/com/smartdocs/editor/core/ocr/OcrRootCauseTest.kt`: `scanShare`, `inkOf` (dark, light-on-dark, none), `LangDetect.forOcr`, pinned languages and URL, `sha256`.
- Gradle result: see §7a.

### 7a. Gradle
`./gradlew :app:testDebugUnitTest --console=plain` (one run, 21 min): **BUILD SUCCESSFUL**. 68 suites, 461 tests, 0 failures, 0 errors. New tests: OcrRootCauseTest 11/11, ArabicTextQualityTest 5/5. Existing ArabicTextFixTest 10/10 and RecipeAndNamingTest 8/8 (LangDetect.detect unchanged) still pass. The tree compiled includes other agents' concurrent edits as of 00:30–00:55.

## 8. What could NOT be tested

- **On-device rendering and the tablet's own damaged file.** I do not have the user's modified PDF or the tablet.
  - The exact «0-2-5» glyph string was not reproduced literally. The mechanism was reproduced: margin junk words made visible, words > 20 pt, Tz up to 500 %.
  - Tesseract4Android on device (a different Tesseract build, tessdata_fast at the time) gives different boxes from Tesseract 5.4 on the PC.
- **Android `Paint.getTextBounds`, `Bitmap`, JPEG encoding.** They are approximated in the harness with AWT and the Noto Naskh / Arial metrics. Visual details of the rewritten words can differ slightly on device.
- **Zoom, orientation, navigation and reflow on a real tablet.** Analysed by code reading only (H3/H4/H5/H8).
- **The tablet's file is not repaired by these fixes.** The user must use the OCR result's undo (`QuickController.undo` → `undoThrough` backup) or re-open the original from Telegram.
- **Download verification end to end.** Not run against OkHttp on device. The hashes were computed from files downloaded here, and the git blob ids match GitHub.

## 9. PNGs (full paths)

- `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\rootcause\png\orig_p54.png`: original page 54, PDFium, no overlays, no annotations or forms.
- `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\rootcause\png\orig_p54_textlayer_boxes.png`: text layer as red boxes (540 chars; body text has none; hidden header under the banner).
- `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\rootcause\png\p54_old_fast_p54.png`: reproduction of the pre-fix rebuild (the tablet symptoms).
- `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\rootcause\png\p54_old_best_p54.png`, `...\png\p54_new_best_p54.png`: pre-fix writer with best models; current writer forced onto the page.
- `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\rootcause\png\p54_decide_best_p54.png`: current pipeline (identical to the original).
- `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\rootcause\png\compare_footer.png`: footer, original / pre-fix / forced current.
- `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\rootcause\png\crop_body_orig.png`, `...\png\crop_body_p54_old_fast.png`: body crops.
- `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\rootcause\png\compare_p2_orig_vs_current.png`, `...\png\compare_p2_orig_before_after_ink.png`: cover page, before and after the ink fix.
- `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\rootcause\png\b_scanned_region_pages.png`: the 6 b.pdf pages still planned as "scanned region" (calligraphy and watermark pictures).

**Scripts:**
- `...\rootcause\regress.sh` runs everything.
- `...\rootcause\scripts\` (inspect, structure, render, pixdiff, plan, quality, textboxes, teststrings).
- `...\rootcause\jvm\` (`build.sh`, stubs, `src\Main.kt`, `PlanCheck.kt`, `Quality.kt`, `CropCheck.kt`).
