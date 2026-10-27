# Unreadable copied Arabic after OCR (r5)

**Page:** «المرشد_2025م_في_الكيمياء_الجزء_الاول.pdf», **PDF page 59** (book page 46, «الدرس الثاني السعة الحرارية والحرارة النوعية»). I found it by searching PDFium text for «777047544 … 46».

**How the work was done.** All work used copies, never the originals in Downloads. Tools:
- the JVM harness that compiles the app's **real Kotlin code** from this branch against real PdfBox-Android;
- Tesseract 5.4 (`tessdata_best`, ara+eng, on the app's 300-dpi / 3500-px render);
- PDFium through pypdfium2 (`get_text_range`), the same engine the app copies with.

Scratch folder (all paths below are relative to it): `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\ac\`.

## 1. Root causes (with evidence)

The user's copied text has two parts with two separate causes.

### RC-A: the PDF's own text layer is wrongly mapped (Part 1, «إػذاد األعزبر … اٌىُُّبء»)

The page draws Arabic with Type0 fonts (PT Bold Heading, Times New Roman, Times New Roman Bold), embedded TrueType subsets, Identity-H. The file was made by iText 5.4.1 from Word.

The fonts' **ToUnicode CMaps are generated wrongly.**
- Each font's positional-form glyphs (ﺑ ﺒ ﺐ ﺏ) are laid out in U+FE80.. order.
- The CMap writer describes runs of consecutive glyph ids with an **incrementing** `bfrange` whose destination is the *base letter of the first glyph*.
- Example from PT Bold Heading: `<00CC> <00E1> <0641>` gives the 22 forms of ف ق ك ل م ن the 22 code points ف ق ك ل م ن ه و ى ي ً ٌ ٍ َ ُ ِ ّ ْ ٓ ٔ ٕ ٖ.
- So ل-initial becomes «ٌ», م-initial «ِ», م-medial «ّ», ي-medial «ُ», ا-final «ب», س-isolated «ط», د-final «ذ»…
- Truth comes from the font program's own `cmap` (glyph → presentation form). Comparison scripts: `cmpmap.py`, `survey2.py`.

| glyph | font cmap (truth) | ToUnicode gave | |
|---|---|---|---|
| 0x86 | U+FE8E ALEF FINAL | ب | «الكيمياء» → «اٌىُُّبء» |
| 0xD7 | U+FEDF LAM INITIAL | ٌ (dammatan) | |
| 0xDB / 0xDC | MEEM INITIAL / MEDIAL | ِ / ّ | «مدرس مادة» → «ِذسط ِبدح» |
| 0xA2 / 0xA5 / 0xA9 | DAL FINAL / REH ISO / SEEN ISO | ذ / س / ط | |
| 0x8C / 0x9A | TEH MARBUTA FINAL / HAH FINAL | خ / خ | «الحديثة» → «احلذَضخ» |
| 0xC3 | AIN INITIAL | ػ (U+063B) | «إعداد» → «إػذاد», «صنعاء» → «صٕؼبء» |
| 0x11E | LAM-MEEM ligature U+FCCC | «لد» | «المرشد» → «ادلششذ» |

For this PT Bold Heading, 122 of the 192 CMap entries are wrong.

A second, separate fault: **PDFium reverses every multi-letter ToUnicode value in right-to-left text.** So even *correct* ligature entries come out wrong: «مح» → «حم» (محمد → «حممد»), «في» → «يف», «لأ» → «أل» (الأستاذ → «األستاذ»), «لا» → «ال». The same happens in clean Word exports: the «يف / امل» pattern from r5.

**Why it cannot be undone on the extracted text** (the `repairLegacyEncoding(text)` idea): the error depends on the glyph *form*, not the letter. The same wrong code point comes from several real letters:
- extracted «خ» is ة-final, ح-final or خ;
- «د» is ت-isolated, ح-initial or د;
- «ج» is ب-medial or ج;
- «غ» is ع-final, س-medial, ط-isolated or غ.

Text-level reversal would mean guessing with a language model. **At the font level, the identity of every glyph is exact.** So the repair is done there (section 2.3), not as a text substitution.

### RC-B: the app's invisible OCR layer extracts in visual order (Part 2, «ةدحاو … ةرارح»)

`SearchablePdfWriter` wrote each word **shaped** (presentation forms), in visual order, with `/ActualText`. PDFium ignores `/ActualText` when the glyphs have a ToUnicode, so it returns the visual string. Reproduced with the real `origin/main` writer and real Tesseract boxes on page 59 (`outpdf/p59_ocr_before.pdf`, PDFium text `before_ocr_text.txt`):
```
: Specific Heat dws ةرارحلا : Capacity ةيرارهلا ةعسلا
ةرارحلا تبستكملا وأ 33931( يمك ةرارحلا تبستكملاٌ وأ ةدوقملا
```
Both layers are extracted, so a copy pastes the broken original **and** the reversed OCR (full output: `e2e/copy_before.txt`).

What I measured about how PDFium reads an invisible layer (variants on page 59, `outpdf/p59_ocr_v*.pdf`):
- Writing words **unshaped in logical order** makes PDFium reverse every Arabic word («ةعسلا»). PDFium assumes the content stream is in visual order, as every producer writes it, and reverses right-to-left runs itself.
- What works is unshaped base letters with **each Arabic word's letters in visual order**, and **each line written left to right on one baseline**. PDFium then returns logical text with lines intact.
- Words in reading order (right to left) instead scrambled the word order inside lines («- للصف الثالث المرشد في الكيمياء»).
- A tiny box («-», 3 px high) on its own baseline split the header line, so words within the line band are snapped to the line's median baseline.

This deviates from the "logical order" in the brief on purpose: the logical order is what PDFium *returns*, not what must be *written*.

## 2. Fixes

### 2.1 Invisible OCR text (`core/ocr/SearchablePdfWriter.kt`, `EditableTextPdfWriter.kt`)
- Arabic words are written **unshaped**: base letters (`invisibleArabic`: presentation forms folded, tatweel and bidi controls dropped). Font: Noto Naskh (`PdfOps.arabicFont`, Type0 + ToUnicode). Characters the font lacks are dropped per character instead of losing the whole word (`encodable`).
- Each word's letters are written in visual order (`visualWord`: Latin/digit runs keep their order, brackets mirrored).
- Words are grouped into Tesseract lines and each line is written **left to right** (`visualLines` / `lineIndexes`), on a shared baseline when the word lies in the line band (`lineBaseline`).
- Horizontal scaling still stretches each run over its word box.
- `EditableTextPdfWriter`: invisible (low-confidence) words use the same path. Visible words stay shaped + `/ActualText` as before. Words are emitted in visual line order.

### 2.2 Copy (`core/search/ArabicTextFix.kt`, `ui/reader/TextSelectionLogic.kt` + call sites in `PageCanvas.kt`, `Annot2Host.kt`)
- `dropUnusableLines` (inside `fix`, so copy/share/translate and everything else that calls `fix`): a line that is `brokenLine` (at least a third of its Arabic words impossible) is dropped. Its Latin words and numbers are kept. This only happens when the selection also holds a clean Arabic line (at least 3 words, none impossible). With nothing readable to fall back on, the text is kept as it is.
- `isVisualOrder`: words starting with ة/ى and ending with «لا» are reversed words, so lines like «اهنأل جاتحت ىلإ ةرارح» are put back into order.
- «اهلل» → «الله»: an alef glyph + the font's ﷲ glyph (correctly «لله») comes out reversed from PDFium.
- `TextSelectionLogic.text(…, boxes)` + `reorderMixedLine`: PDFium returns about 20% of mixed Arabic/Latin lines in left-to-right visual order (47 of 232 such lines on the *clean* «الوحدة الأولى», e.g. «Zn بعنصر تنتهي 80Hg»). For copy, these lines are reordered by character x positions, but only when the line is mostly Arabic and its x positions run left to right.

### 2.3 Repair of the original text layer (new `core/pdf/ToUnicodeRepair.kt`)
Font-level repair rewrites only `/ToUnicode`, so rendering is untouched. Checked on all-4 pages 1, 30, 54, 59 and 120: pixel diff 0.000%, max 0, same character count.

1. **Font with a cmap** (the usual case): every glyph's ToUnicode value is compared with the embedded TrueType cmap.
   - A wrong Arabic letter is replaced by the cmap letter (presentation forms folded).
   - A ligature is written as **its single presentation-form code point** (U+FCCA…). PDFium decomposes such a code point in the right order, which fixes «حممد / يف / األستاذ».
   - The producer's choice is kept for: digits (Arabic-Indic glyphs mapped to the typed ASCII digits), mirrored brackets, Latin, the ﷲ glyph («لله» after an alef glyph), and mark glyphs whose cmap names fewer marks than the producer («ّ ِ»).
2. **Font without a cmap.** iLovePDF re-subset «…الفصل_الدراسي_الثاني_1.pdf» and stripped the cmap but kept the glyph ids and the faulty CMap.
   - The glyph layout is inferred: glyph code + K = presentation form, with K = 0xFE08 (PT Bold Heading) or 0xFB00 (Times New Roman, Arial).
   - K is inferred from the CMap's own trustworthy entries (bfchar entries, array ranges, the first code of each range).
   - It is accepted only if at least 12 anchors agree and at most 10% disagree, and only if it changes something. Faulty ligature ranges are fixed only when every possible form gives the same letters.
   - Validated on every sample font that does have a cmap: **29 accepted fonts, 2,887/2,887 glyphs agree.** Al Qabas, Simplified Arabic Bold and Arabic Typesetting don't fit the layout and are rejected (unchanged).

`OcrLayerCleanup` removes the app's **own** invisible OCR layer from repaired pages. It is recognised by shape: an appended content stream containing only text operators, rendering mode 3, our Noto Naskh font (plus Helvetica), no painting. The stream is emptied and a leading `Q` is kept.

Wiring:
- **OCR in the reader** (`ui/ocr/OcrInPlace.runOcrToFile`, also used by `DocActions`) and the **OCR screen** (`OcrScreen`) work on `RepairedInput`. This is a temporary repaired copy, with our old OCR layer removed from the pages being recognised again.
  - `MixedPageOcr.plan` therefore judges the repaired text with PDFium, so **repairable pages count as good text** and get no OCR layer.
  - `dropExisting` keeps only words not already selectable.
  - If nothing needs OCR, the repaired file is the result. The done message says how many pages were repaired (`qk_fix_done`).
- **New tool «إصلاح نص الملف»** (`QuickAction.FIX_TEXT`, reader tools tab + command palette): repairs in place with undo through `applyInPlace`. Repaired pages that show pictures *and* carry an old OCR layer keep that layer, because it may be their only text, and the message lists them for re-OCR (`qk_fix_reocr`).
- **Search index, convert, AI, TTS, reflow** read PDFium text, so they get correct text from any repaired file with no extra wiring. Copy/share/translate also get the line dropping (2.2).
- **Not wired:** `core/tools5/OcrRunner` (batch OCR). Its OCR document outlives the run (word review), so a temporary repaired copy would need lifetime handling.

## 3. Results on the real files

**Broken-text pages** (`ArabicTextFix.isBrokenArabic` on PDFium text, before → after the Kotlin repair; `repair_regress.sh`):

| file | pages | before | after | fonts / letters / ligatures fixed |
|---|---|---|---|---|
| الملخص_الشامل (all_1) | 186 | 181 | **0** | 9 / 177 / 83 |
| الملخص_الشامل_الوحدة_الأولى (all_2) | 17 | 14 | **0** | 8 / 177 / 66 |
| المرشد…الفصل_الثاني (all_3, iLovePDF, no cmap) | 132 | 120 | **2** | 21 / 494 / 157 |
| **المرشد…الجزء_الاول (all_4, the user's file)** | 183 | 129 | **0** | 29 / 476 / 174 |
| المرشد…الوحدة_الأولى (all_5, clean) | 53 | 0 | 0 | 14 / 8 / 67 |
| ملخص المساند (all_6, Ghostscript) | 248 | 238 | 238 | not repairable |
| ملخص_المساند_الوحدة_الأولى (all_7, Ghostscript) | 45 | 42 | 42 | not repairable |
| ملـخـص_الكيـمياء (all_8) | 121 | 90 | **0** | 33 / 969 / 242 |
| ملـخـص…الوحدة_الأولى (all_9) | 37 | 10 | **0** | 11 / 176 / 50 |

On the clean all_5, all 290 distinct word changes were reviewed (`d5.txt`); no Latin letter or digit was lost on any page:
- ligature order fixes, e.g. «الأستاذ», «في», «العلامة»;
- 8 real glyph-map errors, e.g. «واةحة → واضحة», «ابيماتيت → الهيماتيت»;
- doubtful cases checked against the rendered glyphs (`png/clean_checks.png`, `png/shadda.png`).

**OCR planning** (real `MixedPageOcr.plan`): all_4 goes from 140 planned pages to **29** (3 blank + 26 with real picture regions; page 59 is one of them, because pictures cover 56% of it). all_1 184 → 58, all_3 129 → 26, all_8 104 → 25.

**OCR layer geometry on page 59:** 96.2% (v5) and 95.4% (end-to-end) of OCR-layer characters lie inside their Tesseract word box (x centre, y overlap). The writer on `origin/main` scored 98.5%. The rest are punctuation and margin junk "words".

### Before / after: copying page 59 (real output, first lines)
Before (`origin/main`: broken original layer + its OCR layer; `e2e/copy_before.txt`, 127 lines):
```
ادلششذ يف اٌىُُّبء - ٌٍصف اٌضبٌش اٌضبٔىٌ 2024 – 2025 اٌىدذح اٌضبُٔخ
إػذاد األعزبر : حمّذ ادلغمف 777047544 - ِذسط ِبدح اٌىُُّبء مبذاسط اٌششُذ احلذَضخ - صٕؼبء 46
اٌذسط اٌضبٍٔ اٌغؼخ احلـــشاسَخ واحلشاسح إٌـــىػُخ
…
: المفقودة أو المكتسبة الطاقة وكمية الحسرارية السعة بين العلاقة
ٌبسرعة تسخن ٌضتْفْخَمت التوعي حرارتها التي المادة ") ¢ \u200F٠. التسخين عند أقل حرارة إلى تحتاج لأنها
```
After (this branch, real OCR flow replayed: repair → plan → OCR of the picked page → copy; `e2e_fresh/copy.txt`, 124 lines):
```
المرشد في الكيمياء - للصف الثالث الثانوي 2024 – 2025 الوحدة الثانية
إعداد الأستاذ : محمد المسقف 777047544 - مدرس مادة الكيمياء بمدارس الرشيد الحديثة - صنعاء 46
الدرس الثاني السعة الحـــرارية والحرارة النـــوعية
السعة الحرارية Capacity Heat :
…
كمي الحرارة الممتصن عند التسخين كبيرة صغيرة
(" المادة التي حرارتها التوعي متَخْفْضتٌ تسخن بسرعةٌ ¢ لأنها تحتاج إلى حرارة أقل عند التسخين
```
The user's *already OCRed* file, OCRed again with this branch (`e2e_oldocr/`): the old layer is removed (3,435 → 1,012 extracted characters) and the copy is **byte-identical** to the fresh-file result.

The user's own excerpt through `ArabicTextFix.fix` (JUnit fixture): the broken lines are dropped, leaving «777047544 46» and «Heat Capacity». The OCR lines come back in reading order, e.g. «التسخين عند أقل حرارة إلى تحتاج لأنها».

## 4. What is still not fixed
- **Tesseract recognition errors** in the OCR words for picture regions: «كمي» for «كمية», «درجت» for «درجة», «التوعيمٌ», two-column lines read across the columns («جرام حرارة لرقع … اللازمن هي»), and junk from the decorative border («#رحتج», «CACAO»).
- **all_6 / all_7** (re-distilled by GPL Ghostscript 9.53.3): the subsets have no cmap, and the glyphs were renumbered compactly, so no layout fits. For Yakout Linotype Light the best K disagrees with 76 of 104 anchors. Glyph identity is not in the file. These pages stay "unusable text": OCR adds a (now correctly ordered) layer, and copy drops the broken lines when the OCR text is present.
- **all_3**: 2 pages keep broken text from fonts that the strict test rejects (Simplified Arabic Bold, AAAGoldenLotus, Al Qabas).
- **The «إصلاح نص الملف» tool alone** cannot clean the old reversed OCR words on picture pages (they may be the only text there); it lists those pages for re-OCR. OCR itself handles them (see the end-to-end result above).
- **Mixed-line order** is fixed for copy in the reader (positions available). Other consumers (search snippets, AI) still get PDFium's order for the about 20% of mixed lines it emits left to right.
- Not run on a device: Android's `Paint`, PdfBox-Android on ART, and Tesseract4Android boxes (the PC has Tesseract 5.4).

## 5. Tests and their results
- **JUnit (new):**
  - `core/pdf/ToUnicodeRepairTest` (8): real glyph codes, cmap points and the real iLovePDF CMap; inference gives K = 0xFE08; a correct CMap and random ones are rejected.
  - `core/search/ArabicCopyTest` (6): the user's exact excerpt as a fixture, broken-line detection on real lines, visual-order detection, «الله», mixed-line reorder with real x positions.
  - `core/ocr/OcrTextLayerTest` (5): unshaped/visual words, line grouping with page 59's real Tesseract boxes, baseline snapping, recognising our layer.
  - Run in the harness JVM together with the existing `ArabicTextFixTest` and `ArabicTextQualityTest`: **34/34 OK**.
- **Real-file regressions** (scratch `ac\`):
  - `repair_regress.sh`: table in section 3;
  - `e2e.sh`: page-59 OCR flow, fresh and already-OCRed;
  - `PlanCheck` on the repaired PDFs (`plan_after_repair.txt`);
  - `boxcheck2.py`: char boxes against word boxes;
  - `pixdiff.py`: rendering unchanged.
- **Gradle** (`./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain --max-workers=2`): see 5a.

### 5a. Gradle
I ran `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain --max-workers=2` in `D:\pdf\sd-build`.
- Result: **BUILD SUCCESSFUL in 9m 4s**. 87 suites, **558 tests, 0 failures, 0 errors, 0 skipped** (JUnit XML reports).
- New: ToUnicodeRepairTest 8/8, ArabicCopyTest 6/6, OcrTextLayerTest 5/5.
- Existing: ArabicTextFixTest 10/10, ArabicTextQualityTest 5/5, OcrRootCauseTest 11/11.
- After that run, the only change was in two test files: the RLM in the fixtures became the `\u200F` escape, with the same string value. The harness JUnit run on the final files gives 34/34 OK.
