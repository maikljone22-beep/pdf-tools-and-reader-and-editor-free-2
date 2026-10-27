# OCR quality on a chemistry page with a watermark (r5)

**User report.** Samsung SM-T970, English UI. The user ran "Text recognition (OCR)" on «المرشد_2025م_في_الكيمياء_الجزء_الاول.pdf», page 25 of 55 on the tablet, and copied the text. The copy was garbage:
- Arabic-Indic numbers came out as Latin letters: «٨٩٠» → «Ade», «٢٨٣» → «YAY», «١٤» → «VE».
- The watermark, border and arrows came out as junk lines: «ات امت ست الست…», «وات وات…», «#رحتح», «OT», «EOE».
- Latin words appeared inside Arabic lines: «Cale», «lisa», «Leg».
- A formula was broken: «C2H2(g) + / ⁄ O2(g)».

**The page.** In the 183-page book on this PC it is **PDF page 78** (book page 65): unit 2, examples (٣) to (٥) for CH4, CO and C2H2. I found it by searching PDFium text for «1256 KJ/mole». All work used copies. The original's sha256 `ff04d824…5848f` is unchanged.

**How it was measured.**
- The app's real Kotlin code (repair, plan, `dropExisting`, `SearchablePdfWriter`, copy, and the new `OcrRefine` / `OcrCleanup`) was compiled into the PC harness and run with PdfBox-Android.
- Tesseract 5.4 used the app's pinned `tessdata_best` ara+eng on the app's render (2474×3500 px, PDFium, no annotations). The tablet build uses Tesseract4Android 4.8.0, which is Tesseract 5.4.1.
- Ground truth for page 78: 38 lines, 407 tokens, typed by hand from the page rendered at 2474 px, with every Arabic-Indic digit.
- Metrics:
  - token precision, recall and F1;
  - **bag char accuracy**: char accuracy that ignores word order (Tesseract orders mixed Arabic/Latin lines unpredictably);
  - digit-token recall.
- Regression pages:
  - all_5 pages 33, 41 and 45, where the whole text layer is real text; their repaired text layer is the ground truth;
  - a synthetic faded scan of page 41 (s41);
  - page 59 from the previous round, reviewed by hand because it has no ground truth.

Scratch folder: `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\ocrq\`. In it:
- `pipe.sh` replays `OcrEngine.recognize`;
- `e2e.sh` runs repair → plan → OCR layer → copy;
- `evalall.sh` scores every page;
- `metrics.py`, `gt_p78.txt`;
- `runtests.sh` runs JUnit in the harness.

## 0. What the page is (defect 5)

- **The page has a text layer, but not for its Arabic body.** iText 5.4.1 exported it from Word.
  - The text layer holds the header, the footer, «مثـــال (n)», and every Latin formula and unit. The fonts are Type0: PT Bold Heading, Times New Roman (+Bold), Cambria Math, Arial.
  - **All Arabic body text and all Arabic-Indic numbers are pictures.** Each word is a **2×2-px solid-colour image** (purple `7030A0` or black) drawn through a **1-bit soft mask** that holds the glyphs (for example a 1569×153 mask for a 94×18 pt word). This is Word's "text with effects". 246 of these are on the page.
  - The watermark logo is one 840×828 RGB image.
- **ToUnicodeRepair covers the text layer.** PT Bold Heading and the other Arabic fonts are fixed: 3 fonts, 221 glyphs, giving «المرشد في الكيمياء…» and «مثـــال». It did not cover the Cambria Math digits (see defect 4).
- **MixedPageOcr said "good text, no OCR"** (`plan pages=[]`). `imageBoxes` ignores pictures under 64×64 px, so the 2×2 word pictures were invisible to it. The page was recognised only because the user picked a single page. With "all pages" it would have been skipped and its Arabic would never become selectable.
  - **Fix:** `MixedPageOcr.drawnSize` uses the soft mask's size when it is larger.
  - Page 78 is now planned.
  - Planned pages: «…الجزء_الاول» 29 → 104 of 183; «…الوحدة_الأولى» 10 → 33 of 53; the other files are unchanged.
  - On the newly planned pages these mask pictures draw **10–65 % of all dark ink** (`inkcheck.py`). Clean pages 33 and 41 draw 0 %.
  - These pages genuinely have unselectable text. More pages to OCR is the price of an "OCR all pages" run.
- **Mode:** searchable (invisible layer). `dropExisting` drops the OCR words lying on the repaired text layer, so header and formulas are not duplicated.

**Old build or new build?** I replayed both on page 78 (old: `jvm/src/OldE2E.kt` + the pre-ea1cb42 copy path).

| build | what a copy gives | bag char acc. |
|---|---|---|
| pre-ea1cb42 | broken text layer «ادلششذ يف اٌىُُّبء» **plus** visually reversed OCR words «ةرارح قارتحا», «نم ناثيملا» | 0.198 |
| main 9d770b2 (ea1cb42) | repaired layer + OCR words in logical order, with the digit / junk / Latin-noise defects | 0.612 |
| this branch | see §2 | **0.814** |

**The user's copy is from a build that contains ea1cb42**, for three reasons:
- the Arabic is in logical order;
- no broken-font line appears;
- the OCR words of the header («المرشد في الكيمياء…») are missing while «/ الكيمياء الحرارية» is present. That is `dropExisting` working on a *repaired*, usable text layer. The old build kept every OCR word on a broken layer.

The user's copy begins at «- = = C2H2». That is the tail of the text layer, so the selection or the excerpt starts there. The OCR part matches this replay closely: «Cale», «lisa», «YAY», «VE», «Leg», «Wiss», «dite», «gly».

## 1. Root causes, fixes and numbers

### Defect 1: Arabic-Indic digits → Latin letters
- **Root cause.** The pinned `tessdata_best/ara` model's unicharset has 86 symbols. Of the Arabic-Indic digits it has **only ٠ and ١** (`combine_tessdata -u`, `ux/ara.lstm-unicharset`), so «٢…٩» can never be recognised.
  - With ara+eng, the eng model wins on these shapes: «٨٩٠» → «Ade/Me», «٢٨٣» → «YAY», «١٤» → «VE».
  - ara alone gives Western digits or nothing. A digit whitelist with ara gives an empty result.
- **Evidence.** The official `tessdata_best/script/Arabic` model has 307 symbols, including every ٠–٩ and ۰–۹. On a crop of «٨٩٠» it reads `۸۹۰` (conf 76); on «٤ =» it reads `٤ =`.
- **Fix (`core/ocr/OcrRefine.kt`, wired in `OcrEngine.recognize`).**
  - Words of an Arabic line are read again, one at a time, with the script model: `setRectangle` around the word box padded by ¼ of its height, `PSM_SINGLE_LINE`, `meanConfidence`. This applies to words with no Arabic letter, Arabic pieces mixed with digits or «؟», and lone letters.
  - The reading is taken only when **all** of these hold:
    - it is Arabic content (Arabic letters or Arabic-Indic digits) and confidence ≥ 40;
    - it has no Urdu letters;
    - it has no impossible word (`ArabicTextFix.implausibleWord`);
    - it does not mix Arabic letters with Latin;
    - it has no lone letter;
    - it is not longer than 2×+2 the original (the padded box caught neighbours).
  - Persian digit and letter forms are folded (۸ → ٨, ی → ي, ک/ڪ → ك).
  - The script model also reads Latin, so CO, CH4, KJ/mole and 890 come back as Latin and stay. Confident formulas and numbers are not re-read at all.
- **Model download.** The model is downloaded with ara, **optionally**: OCR works without it.
  - It is pinned to the same tessdata commit: `script/Arabic.traineddata`, 17,095,279 bytes, SHA-256 `a80454f7…3902e`.
  - Its git blob id `54a6fd7e…331a` was checked with `git ls-tree` at e12c65a.
- **Result (page 78, raw OCR words).**
  - Digit tokens: **8 → 25 of 83**.
  - Copy: 8 → 23.
  - Fixed examples: «Me» → ٨٩٠, «VE»/«Vt»/«٠4» → ١٤, «YE»/«3٠١4» → ٣١٤, «4» → ٤, «١١» → ١٦.

### Defect 2: watermark / border / arrow junk
- **Root cause.** These words came from three places:
  - the chain border at the top and bottom: glyph pieces 5–15 px high read as «حت ف حتت», «#رحتح»;
  - the vertical side border: one Tesseract "line" of stacked words 300+ px tall, «OEE», «COCEHOCAHEOCAHEO»;
  - the logo and arrows: «<-<----+», «—».
  
  Their confidence is 0–60, against about 90 for real words (`lines.py` per-line confidence dumps).
- **Fix (`core/ocr/OcrCleanup.kt`, after the second readings).** Real Arabic words, numbers and chemistry formulas (element symbols only) are never fragments.
  - **Word rules:**
    - «#»;
    - three equal letters in a row with confidence < 70;
    - impossible Arabic;
    - a box > 3× the page's median height with confidence < 70;
    - a long run of capitals with confidence < 50;
    - confidence < 10 for a short non-number.
  - **Line rules:**
    - lines of tiny "words" (median height < 0.4× the page median);
    - repeated short pieces;
    - one piece making up a quarter of the line;
    - lines made only (or 80 %) of fragments: lone letters, unknown 2-letter pieces, capitals that are no formula;
    - lines of mostly low-confidence words (their unsure words go, confident real words stay);
    - stray 1–2-word lines with confidence < 40 or < 60.
- **Pre-processing was measured and rejected.** I tried grey + "everything lighter than the page's Otsu threshold → white" (the watermark is luminance 170–245, the text ≤ 90).
  - Raw OCR on page 78: bag char accuracy 0.570 → 0.610.
  - Threshold sweep 110–190: F1 0.53–0.60, so it is noisy.
  - Combined with the other fixes it did not help on average:

    | variant | p78 | c33 | c41 | c45 | s41 | mean |
    |---|---|---|---|---|---|---|
    | without clamp | .774 | .889 | .869 | .716 | .851 | **.820** |
    | Otsu clamp | .749 | .886 | .884 | .748 | .821 | .818 |
    | clamp at ≥ 175 | .769 | .871 | .870 | .751 | .821 | .816 |

  - It also risks faint scans, so it is **not** in the app. The cleanup removes the watermark junk instead.
- **Result.** Page 78 hypothesis tokens 473 → 407 (truth: 407). Unmatched tokens 217 → 129. Every line from the user's copy listed in the report is recognised as junk (unit test).

### Defect 3: Latin noise words inside Arabic lines
- **Root cause.** On bold or coloured Arabic words, the eng model's score beat ara's. Tesseract picks per word, so «علمت» → «Cale», «كتلة» → «lisa», «وعند» → «Leg», «منه» → «die».
- **Fix.** The same second reading as defect 1: «Cale» → علمت, «Leg» → وعند, «lis» → كتل, «KP» → معاد, «)13» → إذا.
- **Remaining.** «Jol» (أول, the script model has 16 % confidence), «bisa», «haa», «Talis», «gly» (script readings rejected or unsure).

### Defect 4: formulas
- **The text layer's formulas are correct** («CH4(g) + 2O2(g) → CO2(g) + 2H2O(l) ∆Hc = - 890 KJ/mole»), except the fraction digits.
  - Root cause: Word's Cambria Math draws the «1/2» and «5/2» digits as **mathematical bold digits outside the BMP** (glyph 0xADE = U+1D7D3 𝟓, 0xADB = U+1D7D0 𝟐), and its ToUnicode maps them to **U+0020**. So «CO(g) + ½O2(g)» copied as «CO(g) + ⁄ O2(g)».
  - **Fix (`ToUnicodeRepair.correction`):** a glyph mapped to a blank whose cmap names a mathematical letter or digit (U+1D400–1D7FF) gets that character, NFKC-folded (𝟓 → 5). The page now copies «CO(g) + 1⁄2O2(g)».
  - On all_1/4/5/8 the extra fixes are only such digits and letters (2, 1, H, E, T, Σ), reviewed with a diff of PDFium text against the previous repair. all_4 goes from 29 fonts / 476 glyphs to 31 / 525.
- **Still broken:** PDFium puts the raised «5» and the lowered «2» of «5/2 O2» on their own lines («C2H2(g) + 5 / 2 / ⁄ O2(g)»). That is line building in PDFium for the superscript/subscript baselines.
- **OCR side:** the formulas are in the text layer, so `dropExisting` removes the OCR versions («CHy», «CoH,»). The cleanup never treats formulas as fragments.

## 2. Copying page 78: before and after (real replay, OCR part, first lines)

**User's tablet copy** (bag char accuracy 0.432; it is a partial selection):
```
ا حت ات ات امت تج الت ست الست ات ات ست الست ست الست ات ست ست الست ست امت امح تمصت ست رصت رست …
/ الكيمياء الحرارية ADE
من المعادلةّjG : ب ¢
أحسب حرارة احتراق & جو من الميثان ( إذا Cale أن :(1=cH12=C(A
المعطيات :lisa الميثان tT جم |( حرارة احتراق مول واحد من الميثان Ade كيلوجول/مول
```

**main 9d770b2** (`e2e_main/copy.txt`, OCR part; bag char accuracy 0.612, F1 0.650):
```
OEE
EOC =
مو
)باق
…
حم فحت #حتخ. #حتخ. #رحتخ. #رحتخ. #رحت. # د تح # حت #حت # حت …
م / الكيمياء الحرارية
أحسب حرارة احتراق 4 جم من الميثان ¢ 13( Cale أن :(1=H c12=C(
المعطيات :lisa الميثان ET aa «حرارة احتراق مول واحد من الميثان Me كيلوجول/مول
```

**This branch** (`e2e_new3/copy.txt`; bag char accuracy **0.814**, F1 **0.752**):
```
م / الكيمياء الحرارية
من المعادلتّ الآتيمٌ : — »
أحسب حرارة احتراق 4 جم من الميثان ¢ إذا علمت أن :(1=H c12=C(
المعطيات : كلي الميثان ٤= جم ٩ حرارة احتراق مول واحد من الميثان ٨٩٠ كيلوجول/مول
المطلوب : حساب حرارة احتراق ٤ جرام من غاز الميثان -
كتل مول واحد من غاز الميثان ١9 (١٢ +) ١ = ١ 5+ - ١٦٩ CA
١١٦ جرام من غازالميثان ] ٨٩٠+ كيلوجول .
وعند ضرب طرفين في وسطين
.* ٤ جرام من غازالميثان ----+>-> (س) كيلوجول/مول .
(س) حرارة احتراق 4 جرام من الميثان ——— - ٥.٣٣ كيلوجول .
يحترق Jol أكسيد الكرجؤْن ؛ كالآتي : ...
(١ أحسب حرارة احتراق ١٤ جم من Jol أكسيد الكربون» إذا علمت أن =16(:
المعطيات : كللي ١٤ جور < + حرارة احتراق مول واحد من YAY كيلوجول/مول -
```
The text layer before it now starts «المرشد في الكيمياء - للصف الثالث الثانوي 2024 – 2025 الوحدة الثانية» and contains «CO(g) + 1⁄2O2(g) → CO2(g) ∆Hc = - 283 KJ/mole».

## 3. Numbers

**Page 78, the whole page as copied** (ground truth: 407 tokens):

| | P | R | F1 | bag char acc. | digit tokens |
|---|---|---|---|---|---|
| pre-ea1cb42 build | .273 | .337 | .302 | .198 | 8/83 |
| main | .608 | .698 | .650 | .612 | 8/83 |
| **this branch** | **.751** | **.754** | **.752** | **.814** | **23/83** |

**Page 78, OCR words only** (`np/p78_*.tsv`):

| | F1 | bag char acc. | digit tokens |
|---|---|---|---|
| main (as on the device) | .573 | .570 | 8/83 |
| + second readings only | .624 | .655 | 25/83 |
| + cleanup only | .621 | .687 | 8/83 |
| **both** | **.676** | **.774** | **25/83** |

**Regression** (OCR words against the real text layer, digits folded to ASCII; bag char accuracy main → this branch):

| page | main | branch | F1 main → branch |
|---|---|---|---|
| all_5 p33 | .838 | .889 | .770 → .827 |
| all_5 p41 | .805 | .869 | .753 → .827 |
| all_5 p45 | .709 | .717 | .644 → .702 |
| p41 faded scan | .791 | .851 | .734 → .821 |

**Page 59** (previous round, no ground truth): 362 of 467 words kept.
- Dropped words reviewed one by one (`dropped.py`): border lines («ا تت ف لت رسا…», «كن حت ف حت…»), «EEOC», «EXCEEDS», and a few conf-0 misreads («كمي/0», «المعقودة/2»).
- 9 second readings were taken. Wrong multi-word takes («Gaya» → «التغير في درجة») are now refused.

**Not worse anywhere:** every measured page improved in F1 and bag char accuracy.

**Cost.**
- 27–137 second readings per page (p78: 137).
- On the PC, 150 crops took 31 s, against about 90 s for the page itself.
- Not timed on the tablet.

## 4. What remains impossible or not done (honest)
- «٢٨٣» in this bold font is read as «YAY» by **both** models, even when upscaled. ٢ and ٣ look alike as «Y», so no safe letter-to-digit table exists. Digit-token recall is still only 23–25 of 83: many numbers are split or merged differently («(١×4)»), and «٦.٥» is read as «٥» or «1.0».
- The special ة of this font (shaped like «ۃ») is often lost («كتلة» → «كتل/كلي»). Further misreads include «المعادلتّ», «الكرجؤْن» and «ضرب/صرب».
- Mixed Arabic/Latin lines are still ordered unpredictably by Tesseract.
- PDFium splits «5/2» over lines (defect 4).
- The tablet itself was not used. The device runs Tesseract4Android with `setImage(Bitmap)` and `setRectangle`; the PC harness uses CLI crops with the same models and PSM. Android-side timing and memory with the second model (about 17 MB more) were not measured.
- Pages OCRed with an older build keep their old layer until OCR is run again (the old layer is then removed, as before).

## 5. Files
- `core/ocr/OcrRefine.kt` (new): candidates, second-reading acceptance, script folding.
- `core/ocr/OcrCleanup.kt` (new): junk word and line rules, `isJunkTextLine`, `isFormula`.
- `core/ocr/OcrEngine.kt`:
  - `OcrWord.line` (from `isAtBeginningOf(RIL_TEXTLINE)`);
  - the helper `Arabic` model (optional download, pinned);
  - refine + cleanup inside `recognize`;
  - `text` rebuilt from the kept words.
- `core/ocr/MixedPageOcr.kt`: `drawnSize` (soft-mask size).
- `core/pdf/ToUnicodeRepair.kt`: mathematical digits and letters that were mapped to blanks.
- Tests:
  - `app/src/test/.../core/ocr/OcrQualityTest.kt` (9): the user's copied text as a fixture (junk lines dropped, real lines kept), real page-78 word boxes for the cleanup, real script-model readings for the acceptance rules, `drawnSize`;
  - `ToUnicodeRepairTest.mathDigitsMappedToSpaceAreRestored`.

## 6. Tests and CI
- **Harness JVM** (`runtests.sh`, final sources): 44/44 OK. That covers OcrQualityTest 9, ToUnicodeRepairTest 9, OcrTextLayerTest 5, ArabicCopyTest 6, ArabicTextFixTest 10 and ArabicTextQualityTest 5.
- **Local Gradle** in `D:\pdf\ocrq-wt` (`compileDebugKotlin testDebugUnitTest --max-workers=2`):
  - The first run was stopped by another agent's `--stop`.
  - The second run finished with 568 tests, 1 failed. The third, targeted run had 89 tests, 2 failed.
  - All three failures are from sources edited while the build ran: main classes were compiled before the edit, test classes after it. Each failing assertion is satisfied by the final sources (harness and CI).
- **GitHub CI** run 40531518250 (`Build APK`: assembleDebug + testDebugUnitTest) on commit 8d25647: **success**.
