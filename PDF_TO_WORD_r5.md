# PDF → Word on the Arabic chemistry book (r5)

**User report.** "I tried to convert PDF to Word and it appears like this garbage." The input was `user_input.pdf`: 55 pages, the tablet copy of «المرشد_2025م_في_الكيمياء_الجزء_الاول» (book pages 54–108), which had already been through the reader OCR of an older build. The output was `user.docx`: 3.6 MB, 364 pictures and 6,603 paragraphs. The user saw:
- border and watermark junk lines («EEK», «KYE», «OOS», «عيحح», «جح جح»);
- one-word paragraphs;
- «ة» read as «ت» or «ي»;
- words in the wrong order;
- Latin garbage («Jl el—sasSJI», «@Jjlp»);
- duplicated text.

All work was done on copies, in the scratch folder `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\p2w\`. Paths below are relative to it unless stated.

**How it was measured.**
- The app's real converter code (`core/convert`, convert6 `FlowDocx`/`LayoutFlow`) was compiled into a JVM harness against real PdfBox-Android, with java.awt-backed `android.graphics` stubs (`jvm/build2.sh`, driver `jvm/src/Conv.kt`).
- The code from main 81ec0f4 was built the same way as the "before" baseline (`before/build0.sh`).
- The OCR preparation used the app's real `RepairedInput`, `ToUnicodeRepair`, `OcrLayerCleanup`, `MixedPageOcr.plan`/`dropExisting` and `SearchablePdfWriter` (`jvm/build3.sh`, `jvm/osrc/E2E2.kt`). PDFium text came from pypdfium2 dumps.
- Recognition replayed `OcrEngine.recognize` (OCR_QUALITY_r5 `ocrq/pipe.sh`): Tesseract 5.4 with the pinned tessdata_best ara+eng at psm 3 on the app's render (300 dpi, capped at 3500 px, no annotations), then `OcrRefine` second readings with the script model, then `OcrCleanup`.
- The "before" harness reproduces the user's docx text exactly: per-page paragraph counts identical on all 55 pages (2,084 body paragraphs, 1,123 of them single-word, 74 tables).
- Picture counts differ in the harness: 61 instead of 364, because the stub `Bitmap` decodes soft-masked images differently. Picture findings below therefore come from the user's own docx.

## 1. Which code path ran

Hub «تحويل» → `pdf_docx` → `Cv6Engine.fromPdf`.

| Question | Before (main 81ec0f4) | Evidence |
|---|---|---|
| Path | `keepLayout = true` (default) → `ConvertEngine.convert` → **PdfBox** `PdfContentExtractor` → `LayoutAnalyzer` → `DocxBuilder` (page breaks, header and footer). PDFium is not used. | The docx has `header1.xml`/`footer1.xml` and one page break per page. The harness reproduces every page's paragraph count. |
| (a) Our old OCR layer read? | **Yes.** `RawCollector` keeps rendering-mode-3 glyphs (colour 0), with no flag. | Page 25: 4,192 PDFium chars, of which 38,660 bytes are the old `3 Tr` stream. Docx page 25 has 442 paragraphs of OCR words. |
| (b) ToUnicodeRepair / RepairedInput? | **No.** `fromPdf` only calls `ocrCopy`. | Original book page 58 converts as «اٌغؤاي اٌضبٍٔ…». |
| (c) OcrCleanup / OcrRefine? | **No.** The auto-OCR (`AutoOcr.ocrIfScanned`) only runs when at least half of the pages have no text: 0 of 55 here, so no OCR ran during the conversion. | The junk words are the old layer's. |
| (d) Old OCR layer removed before re-OCR? | **No** (no re-OCR at all). | |
| (e) Mask-image text pages planned? | **No.** These are Word's 2×2 colour image + glyph soft mask per word; 28 of the 55 pages have them. Their text was not converted, and the pictures were exported one per word. | `MixedPageOcr.plan` on the repaired file plans 28 pages: 2, 3, 6–8, 10, 11, 13–16, 19, 20, 22–25, 28–31, 33, 34, 36–39, 55. |

**What the tablet file is.**
- **Page 1 (book page 41, PDF 54)** was rebuilt by the pre-847d5d8 "editable" OCR (ROOTCAUSE_arabic_ocr_r5). It is one 1699×2200 page picture with the recognised words painted out, and the old fast-model OCR words re-typed **visibly** in Noto Naskh / Helvetica, with Tz 20–500 %.
- **Pages 6 and 25** carry the old invisible layer: shaped, in visual order, per-word baselines, font sizes 4–9 pt.
- **The other pages** are the book's own text. Unlike the Downloads copy, their ToUnicode is already correct on the tablet copy.

## 2. Root cause per symptom (with evidence)

| Symptom | Root cause | Evidence |
|---|---|---|
| Junk lines «EEK», «KYE», «OOS», «عيحح», «جح جح», «Jl el—sasSJI», «@Jjlp» | **All are on page 1**: the old build's *visible* re-typed OCR words. Tesseract (fast) read the chain border and the banner, and the eng model read Arabic headings as Latin. The converter exported that text like typeset text. | A docx search for each string finds page 1 only. PDFium text of the PDF's page 1 has «KYE», «KEK EEK» and «Thermochemistry @Jjlp Jl el—sasSJI» in the text layer itself. |
| ة → ت / ي («المصاحبت», «الطاقن», «الكيميائيي») | **In the PDF already**: the old fast-model Tesseract read this font's special ة (shaped like «ۃ») as ت/ي/ن, and the rebuild wrote those words visibly. The converter did not add the error. Current OCR still often misreads this glyph (OCR_QUALITY_r5 §4). | PDFium text of page 1 contains «تبحاصملا» (= «المصاحبت» in visual order). The other pages' text layer has correct «ة». |
| Tiny paragraphs (1,123 single-word paragraphs) | 1. **OCR layer geometry.** Every OCR word has its own font size (box height, 4–18 pt) and its own baseline (box bottom). Baseline clustering (tolerance 0.35 × the smaller size) split every line, and the ±15 % size test kept words out of the paragraph above. Page 25 had 435 body paragraphs. 2. **`splitColumns` cut every gap wider than 2.5 em.** Lines with wide spacing («المعطيات : كتلة الميثان = ٤ جم ،      حرارة …») became pieces. 3. **Paragraph merge** required boxes less than 0.9 font size apart. With Arabic fonts and 1.15–1.5 line spacing the gap is larger, so wrapped lines never joined. Digit-only or Latin lines broke the `rtl == rtl` test. 4. **Subscripts** («CH» + lowered «4(g)») and tatweel gaps («مثـــال» → «مث ال») made extra lines and words. 5. **Pages whose body is pictures** (mask images) only had punctuation in the text layer («.», «:», «=», «م»), and each piece became a paragraph (page 7: 45 of 56). | `jvm/src/Diag.kt` line dumps for pages 5, 7 and 25. On page 25 the same visual line came out as 3–6 separate lines. |
| Words or lines in wrong order | Old layer reversed (pre-ea1cb42). Real formula glyphs and OCR words on one visual line ended up in different "lines". Brackets of OCR words came out mirrored («)س(») because the generic visual→logical reordering does not undo `SearchablePdfWriter.visualWord`'s mirroring. Lam-alef glyphs of shaped text came out reversed («ال يمكن»). | Page 25 before: «)س( كيلوجول/مول», «الإشعاعين( لا يمكن». |
| Duplicates | Invisible OCR words over real text were exported as well as the real text: the old layer was written over the whole page, header included. | Docx page 25 has «المرشد في الكيمياء - للصف الثالث الثانوي» twice, once from each source. |
| 364 pictures | Classified from the user's docx: **54 copies of the 840×828 watermark logo** (one per page, shown inline before the text); **109 pictures of a text line** (soft-mask word images, up to 60 per page); **78 blank or one-colour images** (empty masks, white tiles); **1 full-page raster** (the page-1 rebuild, words painted out); 122 other (figures, labels, small mask pieces). | `media.py` classification of `user.docx`. |
| Garbled Latin in Arabic runs | `DocxBuilder` put `<w:rtl/>` on every run of an RTL paragraph, Latin and formulas included. Word then orders neutrals and digits inside «(C = 12 ، H = 1)» and «CO2(g) + 2H2O(l)» right to left. | `runs()` before the fix. |
| (latent) Page break before page 1 | `else body.append(pageBreak)` belonged to the footer test: a leading break on documents without a footer, and a missing break before the first page that has one. | Code reading. Covered by a unit test now. |

## 3. Fixes

**convert6 (`Cv6Engine`, `PdfFlow6.wordInput`, `Cv6OptionsSheet`):**
- PDF → Word always converts a **repaired copy** (`RepairedInput`: ToUnicodeRepair). With the auto-OCR option (default on; always for «ممسوح ضوئياً → Word»), it also removes **our old OCR layer** on the converted pages.
- It runs `MixedPageOcr.plan` and recognises **only the planned pages** with the current pipeline (`OcrEngine.recognize`, which includes OcrRefine and OcrCleanup). Each such page gets a fresh invisible layer of the words not already on the page (`dropExisting`).
- OCR languages come from the document text (`LangDetect.forOcr`) unless chosen.
- Offline or no language data: the conversion goes on with the repaired copy, and the result says so.
- **Layout option:** «تلقائي / الحفاظ على التخطيط / نص متدفق». Automatic gives flowing text for Arabic documents whose pages needed OCR, and the page layout otherwise.
- The result line now also says how many pages were recognised and which pages an older OCR build rebuilt («حوِّل الملف الأصلي للحصول على أفضل نتيجة»).

**Extractor (`PdfContentExtractor`, `PdfPlatform`, new `OcrText`):**
- Rendering-mode 3/7 text is flagged `ocr`. So are the visible words of the old rebuild: Noto Naskh / Helvetica with Tz ≠ 100 on at least 60 % of their glyphs (`OcrText.ourVisibleLayer`).
- OCR words lying on real text are **dropped as duplicates** (`dropDuplicates`, same 35 % rule as `filterWords`).
- OCR runs standing on end (vertical border reads such as «COC») are dropped.
- 2×2 shading tiles become fills, so they show as highlights instead of pictures.
- **Soft-mask word pictures covered by text are not exported.**
- One-colour or empty images are skipped.
- Symbol / Wingdings private-use codes become «•», «⇐», «→», «∴», «✓», «✍». They used to be dropped, which lost list bullets and the arrows of worked examples.

**Layout (`LayoutAnalyzer`, `OcrText`, `PageFurniture`, `TableDetector`):**
- OCR lines get **one size** (1.25 × the median box height) **and one baseline**.
- Lines standing side by side **join**: overlapping bands, or a smaller-font sub/superscript, as long as no word stands over another. The words are cut again from the joined glyphs, so «CH» + «4(g)» → «CH4(g)».
- Tatweel glyphs bridge their word.
- OCR Arabic words are restored with the exact inverse of `visualWord`, on glyph units (brackets, lam-alef).
- OCR junk is removed:
  - `OcrCleanup.isJunkTextLine` / `isJunkWord`;
  - capitals runs (a doubled letter or no formula: «EEK», «OOS», «YEDOEDOE»);
  - Latin reads with «@», a capital after a small letter or a dash inside («el—sasSJI»);
  - Arabic runs of 15+ letters;
  - specks «| ! ' …»;
  - a lone Latin letter in Arabic;
  - «¢» → «،».
- Lines split only at real column gutters: rivers over at least 1/6 of the page's lines, or gaps wider than 6 em.
- **Paragraphs:**
  - lines join by **baseline pitch** (the first step at most 1.9 font sizes, later steps follow the paragraph's own pitch);
  - digit/symbol lines take the paragraph's direction;
  - **labels and list numbers start a new item** («المعطيات :», «مثال (٤) :», «١)», «.33», «∵»);
  - a line shorter than 55 % of the paragraph ends it;
  - lone pieces («:», «م», «١٢» from OCR) are no paragraphs;
  - OCR lines are headings only when clearly bigger, and a heading needs 3 letters.
- **Pictures under text are not exported:** at least 15 % of the page and covered by lines. That removes the watermark on every page, scanned or rebuilt page pictures, and backgrounds.
- Running headers and footers are keyed on their typeset words, so OCR additions don't hide them.
- A stream "table" with a column of stray marks is no table.

**Word (`DocxBuilder`, `BidiText.scriptRuns`, `TextRuns`):**
- Paragraphs keep `w:bidi`.
- **`w:rtl` goes only on Arabic runs.** Latin, formulas and European numbers are LTR runs. Paired brackets follow their content (a simplified BD16), and the Arabic comma counts as a separator.
- `w:cs` fonts and `w:bCs`/`w:szCs` are kept as before.
- OCR words get no document font: the default is cs Arial.
- **Flowing mode** (`ConvertOptions.docxFlowing`) has no page breaks and no header or footer.
- The page-break placement is fixed.

## 4. Before / after numbers

Ground truth (scratch folder):
- `gt_b54.txt`: book page 54 / tablet page 1, typed from the render;
- `gt_b59.txt`: book page 59 / tablet page 6, typed from the render;
- `gt_b78.txt`: book page 78 / tablet page 25, the ocrq ground truth with paragraph breaks.

Body only (running header and footer excluded), paragraphs separated by blank lines.

Metrics are `ocrq/metrics.py`:
- **bag** = bag-of-words character accuracy;
- **ordered** = character accuracy on the token sequence;
- **F1** = token F1.

The paragraph count is body paragraphs outside tables (GT in brackets). Script: `evalpages.sh`.

**(a) The user's 55-page already-OCRed file** (before = the user's own docx; after = repaired + old layers removed + 28 planned pages re-OCRed + new converter):

| tablet page | paragraphs before → after [GT] | bag | ordered | F1 |
|---|---|---|---|---|
| 1 (book 54, old rebuild) | 110 → **18** [19 + 1 table] | .557 → **.600** | .551 → **.630** | .537 → **.565** |
| 6 (book 59) | 163 → **25** [24 + 3 tables] | .658 → **.663** | .478 → **.601** | .527 → **.563** |
| 25 (book 78) | 435 → **34** [34] | .339 → **.792** | .263 → **.727** | .498 → **.718** |

Whole file:
- body paragraphs **2,084 → 843**;
- single-word paragraphs **1,123 → 70**;
- tables 74 → 74;
- pictures 61 → **14** (harness counts; on the device 364 before: the 54 watermark copies, the full-page raster, the covered word pictures and the blank images no longer go in).

**(b) The original 183-page book** (book pages 54–78 converted; before = main without OCR; after = repaired + 18 planned pages OCRed):

| book page | paragraphs before → after [GT] | bag | ordered | F1 |
|---|---|---|---|---|
| 54 | 11 → **18** [19 + 1 table] | .075 → **.732** | .101 → **.780** | .046 → **.715** |
| 59 | 21 → **26** [24 + 3 tables] | .075 → **.619** | .145 → **.632** | .024 → **.586** |
| 78 | 47 → **33** [34] | .120 → **.820** | .129 → **.753** | .234 → **.765** |

Pages 54–78:
- body paragraphs 657 → 369;
- single-word paragraphs 332 → 34;
- pictures 29 → 7.

Before, the book's Arabic came out as «اٌغؤاي اٌضبٍٔ», and its picture text was missing.

**Converted samples:** `…\scratchpad\docx_user\out\`:
- `user_input_flow.docx` (what "automatic" gives for this file) and `user_input_keep.docx`;
- `book_p54-78_flow.docx` and `book_p54-78_keep.docx`.

### First lines of the new Word text

**Tablet page 25** (book 78):
```
مثال )3( : من المعادلت الآتيّ : CO2(g) + 2H2O(l) ∆Hc = - 890 KJ/mole ب → CH4(g) + 2O2(g) ، أحسب حرارة احتراق & جو من الميثان ) إذا Cale أن C = 12 H = 1
المعطيات : كللي الميثان = جم ٩ حرارة احتراق مول واحد من الميثان = ٨٩٠ كيلوجول/مول
المطلوب : حساب حرارة احتراق ؛ جرام من غازالميثان CH4 -
∵ مول واحد منغاز الميثان ١٦٩ - 5+ ١٣ = (١ × (+ + (١٢ × ١( = CH4 جرام -
∵ ١٦ جرام منغاز الميثان ← كيلوجول .
وعتد صرب طرفين في وسطين
*. 4 جرام من غاز الميثان --+ ← (س) كيلوجول/مول .
Ade x ¢
(س) حرارة احتراق 4 جرام من الميثان = ———— = - ٣٣٥٢ كيلوجول .
مثال )4( : يحترق Joi أكسيد الكرَبونَْ ، كالآتي : CO(g) + 1⁄2O2(g) → CO2(g) ∆Hc = - 283 KJ/mole ...
(١ أحسب حرارة احتراق ١٤ جم من Joi أكسيد الكربون» إذا علمت C = 12 O = 16
(٢ أحسب lism آول أكسيد الكربون التي haa عند احتراقها ( - ٥11٦ كيلؤجول ) -
المعطيات : كللي ١٤ = CO جور + حرارة احتراق مول واحد من CO = كيلوجول/مول - المطلوب )٠:١ حساب حرارة احتزاق ١٤ جرام من CO - …
× + × = CO
∵ ٣٨ جرام من غاز +4 CO ← كيلوجول/مول ١٤ جرام من غاز CO + ← (س) كيلوجول .
وعند صورب طرفسين فطيين
x Vt
(س) حرارة احتراق ١٤ جرام من غاز CO = نشا = - ١٥ كيلوجول ٠
") حساب Plies غاز CO التي تعطي حرارة ( - 0717-3 كيلوجول )-
∵ كتلتٌ مول واحد منقازر ٣٨٢ - ١٦ + ١٣ - × ١9 + (١٢ × ١9 = CO
```
Before, the same page began with 30 one-word paragraphs: «حت», «ات», «ا», «ات», «امت», «تج», «الت», «ست», «الست»…

**Tablet page 1** (the old rebuild, so this is the old OCR's text, cleaned):
```
Thermochemistry Jl
الكيمياء الحراريسة : هي فرع من فروع علم الكيمياء يختص بدراسة التغيرات الحراريي المصاحبت للتغيرات ون الكيميائين والفيزيائيي » أو هي يهتم بدراسي بين الكيمياتين والحرارة المنطلقين أوالممتصت المصاحبت لتغيرات المادة .
9 صور الطاقة وتحولاتها : يمكن تحويل إلى أي صورة من صور الطاقنّ الأخرى » كما في الشكل الآتي : نلاحظ من الشكل الجانبي أن
0-5-2-2 أهم أنواع الطاقات ..لأنه يمكن تحويلها إلى حل ه»
أشكال الطاقن الأخرى » ويمكن تحويل جميع الطاقات ون إلى طاقن ماعدا (الطاقي النوويي والطاقي الإشعاعين) لا يمكن تحوياها إلى طاقن كيميائيي .
…
```
Before, it began «© / © / _—€ / pt / حح / 4 / Jl el—sasSJI / 2 / ~<a / ’ / Thermochemistry @Jjlp / جح / EEK / EEK / EEK / KEK / KYE / عيحح …» (one paragraph each).

**The same page from the original book**:
```
الوحدة الثانية
Thermochemistry
الدرس الأول مقدمة في الكيمياء الحرارية ( Thermo chemistry )
الكيمياء الحرارية : هي فرع من فروع ade الكيمياء يختص بدراسدٌّ التغيرات الحراريتٌ المصاحبتٌ للتغيرات والحرارة
…
```

## 5. Tests and CI

- **New `app/src/test/java/com/smartdocs/editor/core/convert/PdfToWordTest.kt` (14 tests):**
  - RTL paragraph merging with Word line spacing;
  - labels and list numbers as items;
  - wide spaces not cutting a line;
  - subscripts joining;
  - tatweel;
  - invisible-text duplicate suppression;
  - OCR un-mirroring (brackets, lam-alef glyph units);
  - OCR line normalisation;
  - **junk filter on the user's docx lines** (fixture: «EEK», «KEK EEK», «KYE», «جح جح», «OOS:», «عوجححوصحوعوصحمومصي--», «@Jjlp», «٠ YEDOEDOE», «KXEEK», «| ! '» dropped; the real lines kept);
  - lone pieces;
  - old-rebuild detection;
  - watermark dropping;
  - script runs;
  - `w:bidi` / `w:rtl` only on Arabic runs;
  - flowing vs layout page breaks.
- **Harness JUnit** (`jvm/runtests.sh`, app sources compiled with PdfBox-Android): FidLayoutTest 13 + PdfToWordTest 14, **27/27 OK**.
- Gradle was not run locally (as agreed).
- **GitHub CI:** run 40609568398 (Build APK: assembleDebug + testDebugUnitTest) on e316151 (code commit f66d705 merged with origin/main 0203a13): **success**.

## 6. What is still imperfect (honest)

- **OCR reading errors** remain, from Tesseract (OCR_QUALITY_r5 §4):
  - this font's ة as ت («المعادلت», «كتلت»), «كتلة» → «كللي»;
  - Latin noise inside Arabic lines («Cale», «Joi», «lism», «haa»);
  - ٢/٣ read as «Y»;
  - stacked fractions read as «Ade x ¢» / «x Vt»;
  - Tesseract psm 3 misses whole table cells on page 59 («سائل», «صلب», «الحديد»… are never recognised; the old fast run had some of them, which is why page 6's recall did not rise).
- **Tablet page 1 cannot be recovered.** The old build painted out the recognised words in its page picture, so re-OCR is impossible. The page's only text is the old fast-model OCR (ة→ت, «0-5-2-2» from the border). The converter cleans it and drops the picture, so the energy diagram and the flask banner of that page are lost. The result message names the page and recommends converting the original.
- **Logical order of numbers split by spaces in RTL lines** («( 2 - 1 )» for the book's «١ - ٢») is kept in visual order so Word shows it as in the PDF. Copying from Word gives the visual order.
- **Mixed Arabic/Latin lines**: formulas are placed by position. Lines like «CO2(g) + 2H2O(l) ∆Hc = - 890 KJ/mole ب → CH4(g) + 2O2(g)» read correctly in Word visually but are not logically ordered for screen readers.
- **Not done:**
  - grouping soft-mask word pictures into one picture per line when OCR is **off** (they are still exported one per word then; covered ones and blank ones are not);
  - numbers outside a one-column ruled table (page 50 «.1 … .24») remain separate paragraphs.
- **Other conversions** (Excel, PowerPoint, text, HTML…) still use the old auto-OCR rule. They do get the extractor and layout fixes. The classic `convert_classic` screen (`ui/tools/ConvertFromPdf.kt`, another agent's file) was not changed.
- **OCR cost:** the user's file plans 28 of 55 pages. On the PC each page takes about 2 min (best models + second readings). Not timed on the tablet; progress shows as the OCR stage.
- **Not tested on a device.** Tesseract4Android, Android `Bitmap` soft-mask decoding (`isUniform` / `solidColor`) and PdfBox-Android on ART were not exercised. The harness uses Tesseract 5.4 CLI and AWT stubs, so on-device picture counts will differ from the harness counts.
