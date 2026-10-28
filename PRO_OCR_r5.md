# Pro OCR for Arabic + Latin chemistry books (r5)

**Goal.** ≥ 97 % character accuracy on the user's chemistry books. Book page 78 was at 0.814 after fix/ocr-quality (whole page as copied; 0.776 for the OCR words alone).

**Result.** The 97 % target is **not reached**.

The best measured on-device result:
- **0.895** mean order-insensitive character accuracy over 10 ground-truth items from 5 books.
- **0.841** on page 78.
- Word accuracy 0.835.
- Arabic-Indic numbers 0.70, against 0.23 before.

That result needs two parts:
- the app's new **Auto** engine: Tesseract plus PP-OCRv5 AI, word by word;
- a **Tesseract model fine-tuned here on the books' fonts**. It is pinned in the code, but it is not hosted yet: one upload is needed (§6).

Without that upload, Auto measures **0.866** (page 78: 0.795). The app before this branch measured 0.812 (page 78: 0.776).

A cloud "Pro OCR" route on the app's server is in place but unmeasured, because I had no keys.

Nothing was tested on a device.

| configuration | bag | line-order | word | Arabic-Indic numbers | Latin/formulas | page 78 |
|---|---|---|---|---|---|---|
| app before (ara+eng + second readings + cleanup) | 0.812 | 0.770 | 0.718 | 0.232 | 0.276 | 0.776 |
| **Auto as shipped** (that + PP-OCRv5, word by word) | **0.866** | 0.792 | 0.798 | 0.373 | 0.399 | 0.795 |
| **Auto with the fine-tuned model** (after the upload in §6) | **0.895** | 0.789 | 0.835 | 0.704 | 0.455 | 0.841 |

All numbers below were measured on the PC with the app's real Kotlin code where it exists (OcrRefine, OcrCleanup, OcrEnsemble, OcrLexicon, ProOcrCore in a JVM harness). The engines ran on the CPU with 2–4 threads.

Scratch folder (all scripts, renders, outputs): `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\pro\`
- `scripts/evalocr.py`, `scripts/table.py`: the metrics.
- `scripts/ppocr.py`: the PP-OCRv5 pipeline in Python, mirrored by `ProOcrCore.kt`.
- `scripts/base.sh`: the app's current pipeline.
- `scripts/tessrun.sh`, `scripts/kens.sh`: run the Kotlin `OcrEnsemble` + `OcrCleanup`.
- `jvm/`: the harness and its JUnit runner.
- `gt/`: images and ground truth.
- `train/`: Tesseract fine-tuning.

## 1. Ground truth (10 items, 5 books, 2,446 tokens)

The books were copied first; the originals in Downloads were not modified.

| id | book (copy name) | PDF page | kind | tokens | ground truth |
|---|---|---|---|---|---|
| c78 | المرشد الجزء الأول (C) | 78 | **mask-image glyphs** (Word "text effects"), bold, digit-heavy, watermark, chain border | 407 | by hand (previous round, 2474 px render) |
| c41 | المرشد الوحدة الأولى (F = C p. 41) | 41 | multiple-choice table, Times New Roman, formulas FeCl3 / Fe2(SO4)3 / NH4OH | 452 | text layer **corrected by hand**: ASCII digits → the printed ٠–٩, reversed formulas fixed, question numbers moved to the front, header completed |
| s41 | same page as a faded grey scan | – | scan-like (previous round's synthetic) | 452 | = c41 |
| d41 | ملخص المساند (D, Ghostscript) | 41, region | YakoutLinotype (a font not used in training), years and model codes «٢٠١٣نموذج», FeCL3 / CO2 | 116 | by hand from 1800 px crops; numbers checked by zoom |
| e81 | ملـخص الكيمياء (E) | 81, top half | **three columns**, bold Arial, decimals «٠,٩٦» «٦,٧١٥», ions AL+3 / Cu+2 | 246 | by hand, columns right to left; decimal order checked by glyph positions and the PDF's (mis-mapped) digits |
| a46 | الملخص الشامل (A) | 46, region | Yaser/Bader fonts, equations Zn → Zn+2 + 2e- | 117 | by hand |
| b56 | المرشد الفصل الثاني (B) | 56, region | coloured (blue) questions, exponents «١٠-١٣», isotopes 26Fe56 | 138 | by hand; values checked against physics (1 MeV = 1.6×10⁻¹³ J) |
| c100 | C | 100, region | **formula page**: ΔH, (g)/(s)/(l) subscripts, ½, coloured lines, watermark | 195 | by hand; values checked (Fe2O3 −822.2, Al2O3 −1669.8, C6H6 +49) |
| s100 | c100 as a phone/scan picture | – | 0.8° skew, blur, 200-dpi resample, noise, JPEG q55 (`scripts/mkscan.py`) | 195 | = c100 |
| c120 | C | 120, lower half | coloured underlined notes, table of 8 statements | 182 | by hand |

**Metrics** (`scripts/evalocr.py`). Tokens are normalised first:
- NFKC;
- harakat and tatweel removed;
- Persian digits and letters folded (۸ → ٨, ی → ي, ڪ → ك);
- ى → ي;
- hyphens split;
- edge punctuation stripped;
- pure-symbol tokens dropped.

| metric | what it measures |
|---|---|
| **bag** | order-insensitive character accuracy: exact tokens first, then greedy closest pairs by edit distance. This is the primary metric. |
| **seq** | line-order character accuracy: 1 − Levenshtein over the whole token sequence / ground-truth length |
| **word** | word accuracy: recall of tokens (multiset) |
| **dig** | exact recall of tokens containing Arabic-Indic digits |
| **lat** | bag character accuracy over tokens with a Latin letter (formulas, units, ions) |

## 2. Engines: survey (web, Oct 2026) and measurement

| engine | licence (code / weights) | size | on Android | measured here (10-item mean bag / word / dig) | PC CPU time for page 78 |
|---|---|---|---|---|---|
| Tesseract 5.4 `ara+eng` best (the app before this branch, raw) | Apache-2.0 / Apache-2.0 | 12.6 + 15.4 MB | yes (Tesseract4Android) | 0.748 / 0.692 / 0.101 | 67 s* |
| **app pipeline** (ara+eng + OcrRefine second readings with script/Arabic + OcrCleanup) | same + script/Arabic 17.1 MB | | yes | **0.812 / 0.718 / 0.232** (baseline) | 88 s* |
| Tesseract `script/Arabic` alone | Apache-2.0 | 17.1 MB | yes | 0.818 / 0.727 / 0.420 | 40 s* |
| … + OcrCleanup | | | | 0.824 / 0.725 / 0.416 | |
| `script/Arabic+eng` | | | | 0.742 / 0.695 / 0.335 | |
| **`script/Arabic` fine-tuned here on the books' fonts** (§6, integer model) | Apache-2.0 base; weights trained here | 10.0 MB | yes (same Tesseract) | **0.854 / 0.770 / 0.679** | **23 s** |
| **PaddleOCR PP-OCRv5 mobile det + `arabic_PP-OCRv5_mobile_rec`** (ONNX, my pipeline) | Apache-2.0 / Apache-2.0 | 4.8 + 8.0 MB | **yes** (ONNX Runtime, MIT) | 0.755 / 0.657 / 0.286 | det 5.6–13.5 s + rec 53 s* |
| PP-OCRv5, detection at 1600 px instead of 2400 | | | | 0.703 / 0.611 / 0.226 | |
| PP-OCRv3/"v4" Arabic rec (RapidOCR / monkt ONNX) | Apache-2.0 | 8.9 MB | yes | not benchmarked: its 161-symbol dictionary has **no ٠ and no `)` `=` `×`** (checked in the file) | – |
| EasyOCR `arabic.pth` | Apache-2.0 / not stated | – | PyTorch only | not run (no mobile runtime; weights licence unclear) | – |
| docTR / OnnxTR, MMOCR, Kraken | Apache-2.0 | – | no official Arabic model / desktop only | not run | – |
| Surya | GPL / OpenRAIL-M with a $5M revenue cap | ~650M params | no | excluded (licence, size) | – |
| GOT-OCR2.0 (0.7B), Florence-2 Arabic fine-tunes (0.23B), Qari-OCR (Qwen2-VL-2B), Arabic-Nougat (GPL-3.0), Nuqta-Arabic-OCR-0.8B (Apache-2.0, KITAB CER 0.12–0.13, GGUF ≈ 765 MB, ≈ 1.6 GB RAM) | various | 0.2–2 B params | no (too large for 3–4 GB phones; Nuqta "possible but heavy" on high-end tablets) | not run: candidates for the **server** side of Cloud Pro only | – |
| Google Cloud Vision DOCUMENT_TEXT_DETECTION / Azure Document Intelligence Read / Mistral OCR | cloud | – | via the app's server | **NEEDS-SERVER, unverified**: no keys here. KITAB-Bench lists Azure at CER 0.52 (unclear scale) and Gemini-2.0-Flash at 0.13; Google Vision is not in KITAB. Price about $1.5 per 1,000 pages (Google: first 1,000 per month free). | – |

\* Times marked * were measured while other jobs shared the CPU, so they are upper bounds. See §7.

Sources (survey sub-report):
- KITAB-Bench: arXiv 2502.14949.
- PaddleX model tables: paddlepaddle.github.io/PaddleX/3.5.
- HF model cards of PaddlePaddle/*_onnx, NAMAA-Space/Qari-OCR, SalahALHaismawi/Nuqta-Arabic-OCR-0.8B.
- datalab-to/surya licence.
- Google Vision pricing.

**The best on-device AI engine by measurement is PP-OCRv5 Arabic mobile.**
- It is the only permissive, phone-sized, Arabic model with all of ٠–٩.
- Alone it is *worse* than Tesseract on average: 0.755 against 0.812. It drops digits and brackets inside long mixed lines («( ٣.٢٤ × ١٠-٢٨» → «٢٣٢»), glues bold words («مثالمنالمعادلة»), reads O as 0 in formulas, and collapses on the blurred scan (s100 0.361).
- It is *better* on clean lines: d41 0.893 against 0.737, c120 0.969 against 0.865, a46 0.875 against 0.781.

So the two engines make different errors, and **combining them is the measured winner**.

## 3. Input quality (Tesseract script/Arabic, same 10 items)

| variant | bag | seq | word | dig |
|---|---|---|---|---|
| colour render 300 dpi (as the app) | **0.818** | 0.776 | 0.727 | 0.420 |
| darkest channel (coloured text → dark) | 0.819 | 0.788 | 0.730 | 0.432 |
| darkest channel ÷ blurred background (flatten tints/watermark) | 0.823 | 0.773 | 0.743 | 0.493 |
| Sauvola (w 51, k 0.25) on that | 0.809 | 0.661 | 0.731 | 0.390 |
| 400 dpi (8 items; 300 dpi on the same 8: 0.824) | 0.736 | 0.626 | 0.633 | 0.266 |
| 600 dpi rendered and area-downsampled to 300 ("native mask resolution", 4 items; 300 dpi: 0.792) | 0.742 | 0.637 | 0.713 | 0.307 |
| Persian/Urdu characters black-listed (c78) | 0.721 (0.724 without) | | | |

**Rejected.**
- Higher resolution hurts Tesseract's best models clearly: the text becomes larger than the line height they were trained on. Supersampling the soft masks does not help either.
- Background flattening gains +0.005 bag and +0.07 on digits, but it was not tried inside the full pipeline, so it is not shipped. It is a cheap candidate for the next round.
- Sauvola loses.

The app keeps 300 dpi, capped at 3,500 px.

## 4. Post-correction (lexicon / confusions / digit rules)

**Lexicons.**
- For measurement only (not shipped): words of «السلسلة المختارة كيمياء.docx», a different book in the user's folder, 5,610 words.
- Shipped: the app's own glossary `assets/ocr/glossary_ar.txt`, about 430 school / chemistry / function words that I wrote for SmartDocs.
- Coverage: the docx covers 68–94 % of the ground-truth Arabic words.

**Rules** (`scripts/postfix.py`): for words not in the lexicon only,
- one confusion edit (ة/ت/ه, ض/ص, dot groups, hamza forms);
- a missing final ة («كتل» → «كتلة»);
- a glued pair of known words;
- one general edit for words of ≥ 5 letters.

| on | lexicon | bag | word |
|---|---|---|---|
| baseline | docx + glossary | 0.812 → 0.812 | 0.718 → 0.725 |
| ensemble output | glossary | 0.852 → 0.848 | 0.800 → **0.777** |
| ensemble, only confusion + final ة, exact glossary words | glossary | 0.8525 → 0.8520 | 0.800 → 0.798 |
| split glued Arabic letter ↔ digit / Latin («٢٨٣كيلوجول») | – | 0.866 → 0.865 | 0.798 → 0.808 |

**Rejected: none of these helps the primary metric.**
- A small lexicon "corrects" real words into lexicon words.
- The book really prints glued tokens such as «٢٠١٣نموذج».

The lexicon is used only where it measurably helps: as evidence when choosing between two engines' readings (§5). The fixtures «YAY» → «٢٨٣», «كتلة», «صرب» → «ضرب» are fixed by that choice, not by rewriting (unit tests in `ProOcrTest`).

## 5. The ensemble (shipped as "Auto")

`OcrEnsemble.combine` (Kotlin, harness-measured through `scripts/kens.sh`) works like this:
- Tesseract words (after OcrRefine + OcrCleanup) and PP-OCR words that overlap on the same row form clusters (union-find).
- Each cluster takes the reading with the higher **mean confidence + 0.6 × share of known Arabic words** (glossary, with «ال» and one clitic).
- PP-OCR words no Tesseract word covers are inserted into their row at the reading position when PP-OCR confidence is ≥ 90.
- Boxes taller than 2.5 × the median text height (borders, table rules) never join clusters.
- OcrCleanup runs again.

Grid of the scoring weights (the evaluation set is also the tuning set, so ±0.01 is noise):

| lexicon weight | add threshold 0.90 | 0.95 | never add |
|---|---|---|---|
| 0.2 | .835 | .832 | .827 |
| 0.35 | .845 | .842 | .837 |
| 0.5 | .849 | .847 | .842 |
| 0.8 | .855 | .853 | .848 |
| no lexicon | .809 (worse than Tesseract alone) | | |

| Tesseract leg in the ensemble | bag | seq | word | dig | lat |
|---|---|---|---|---|---|
| app pipeline (ara+eng + refine + cleanup) — **shipped** | **0.866** | **0.792** | 0.798 | 0.373 | 0.399 |
| script/Arabic + cleanup | 0.863 | 0.758 | 0.797 | 0.482 | 0.290 |
| three engines with agreement voting (app pipeline + script/Arabic + PP-OCR; Python, not shipped) | 0.876 | 0.766 | 0.811 | 0.444 | 0.384 |

The three-engine vote gains only +0.01 bag, and it costs a second full-page Tesseract pass (about +45 % time), so it is not shipped.

### Per item, before → after (bag char accuracy; word accuracy in brackets)

| item | app before | PP-OCRv5 alone | **Auto (shipped)** | Auto + fine-tuned model (§6) |
|---|---|---|---|---|
| c78 mask glyphs, digits | 0.776 (.676) | 0.650 (.511) | **0.795** (.676) | 0.841 (.776) |
| c41 table | 0.866 (.779) | 0.842 (.790) | **0.918** (.883) | 0.946 (.920) |
| s41 faded scan | 0.848 (.774) | 0.835 (.812) | **0.915** (.900) | 0.962 (.936) |
| d41 unseen font | 0.737 (.629) | 0.893 (.836) | **0.854** (.784) | 0.871 (.802) |
| e81 three columns | 0.834 (.772) | 0.808 (.707) | **0.881** (.801) | 0.901 (.846) |
| a46 equations | 0.781 (.641) | 0.875 (.786) | **0.868** (.838) | 0.922 (.872) |
| b56 exponents, colour | 0.866 (.783) | 0.609 (.442) | **0.849** (.739) | 0.871 (.812) |
| c100 formulas | 0.755 (.662) | 0.707 (.564) | **0.801** (.713) | 0.818 (.713) |
| s100 phone scan | 0.790 (.692) | 0.361 (.200) | **0.801** (.703) | 0.835 (.703) |
| c120 coloured notes | 0.865 (.775) | 0.969 (.918) | **0.977** (.940) | 0.984 (.967) |
| **mean** | **0.812** (.718) | 0.755 (.657) | **0.866** (.798) | **0.895** (.835) |
| line-order acc. | 0.770 | 0.647 | 0.792 | 0.789 |
| Arabic-Indic numbers | 0.232 | 0.286 | 0.373 | 0.704 |
| Latin / formulas | 0.276 | 0.155 | 0.399 | 0.455 |

Worse than before:
- b56: −0.017. PP-OCR wins some clusters with confident but wrong exponent readings.
- d41 and a46 lose to "PP-OCR alone" but still gain over the old app.

## 6. Fine-tuned Tesseract

**Feasible on this PC, and it is the biggest single gain.** It took about 3 hours, including two failed attempts. Files: scratchpad `train/` (`render1.sh`, `../scripts/make_wordstr.py`, `train_ws.sh`).

**Base.** tessdata_best `script/Arabic` at commit e12c65a (Apache-2.0).
- It already holds every ٠–٩, Latin, `( ) = + × /`.
- The fine-tune keeps its character set, so Δ and → are still unknown.

**Fonts.**
- The fonts embedded in the books are unusable: they are subset CID fonts with no cmap (checked with fontTools on all 5 books).
- So the lines were rendered with the same families installed on this PC:
  - Arial and Arial Bold (the bold body of C/E/B);
  - Times New Roman and its Bold (C unit 1);
  - Simplified Arabic and its Bold;
  - Traditional Arabic and its Bold;
  - **PT Bold Heading**: 62 lines only, because the font has no Latin;
  - Tahoma and Tahoma Bold;
  - Sakkal Majalla Bold.
- Size 12–15 pt at 300 dpi, exposure −1..+1.
- YakoutLinotype (d41) and the Yaser/Bader fonts (a46) are **not** in training.

**Text** (`scripts/gen_text.py`): 7,762 lines.
- Half are prose lines from «السلسلة المختارة كيمياء.docx», a book that is not in the benchmark. 80 % of their digits were turned into ٠–٩.
- Half are generated lines:
  - numbers, decimals «٠,٩٦» «٥٦٦.٦», exponents «١٠-١٣»;
  - units (جم، كيلوجول/مول، كولوم…);
  - formulas with (g)/(l)/(s)/(aq), ions Fe+3 / SO4-2, Roman numerals;
  - «س١:», «١١)», option rows «١ … ٢ … ٣ … ٤ …», years «٢٠١٣/أ».
- No line of any benchmark page is in the training text.

**Pipeline.**
1. `text2image`: one line per page.
2. Each page is cropped to its ink.
3. **tesstrain-style WordStr boxes with the text in the model's visual order.** Logical order makes the model collapse to empty output (BCER 97 %). Bracket mirroring must *not* be applied.
4. `tesseract … --psm 13 lstm.train`.
5. `lstmtraining --continue_from Arabic.lstm --learning_rate 0.0002 --max_iterations 3000`, 4 threads, about 55 min.

The first attempt used text2image's character boxes. It produced spaces after every non-joining letter («هيد روك سيدا»), because the model learned the box gaps.

**Training error.** Training BCER 13.5 % at iteration 100 → 1.9 % at 2700.

Held-out real pages (all 10 items):

| model | bag | seq | word | dig | lat | page 78 bag | time page 78 (PC) |
|---|---|---|---|---|---|---|---|
| stock script/Arabic | 0.818 | 0.776 | 0.727 | 0.420 | 0.192 | 0.724 | 40 s |
| fine-tuned, 1700 iterations | 0.848 | 0.768 | 0.759 | 0.672 | 0.481 | 0.746 | |
| fine-tuned, 3000 it., float (17.1 MB) | 0.858 | 0.772 | 0.775 | **0.679** | 0.434 | 0.797 | 40 s |
| fine-tuned, 3000 it., **integer** (10.0 MB) | 0.854 | 0.767 | 0.770 | 0.679 | 0.426 | 0.776 | **23 s** |
| … + OcrCleanup | 0.862 | 0.770 | 0.766 | 0.679 | 0.445 | | |
| **Auto with it: fine-tuned (int) + PP-OCRv5** | **0.895** | 0.789 | **0.835** | **0.704** | **0.455** | **0.841** | 23 s + PP |
| Auto with the float model | 0.899 | 0.791 | 0.840 | 0.716 | 0.451 | 0.846 | |
| three-engine vote (app pipeline + fine-tuned + PP; Python) | 0.894 | 0.823 | 0.842 | 0.641 | 0.428 | | +88 s |

Per item, Auto with the integer model (bag char accuracy):

| item | c78 | c41 | s41 | d41 | e81 | a46 | b56 | c100 | s100 | c120 |
|---|---|---|---|---|---|---|---|---|---|---|
| bag | .841 | .946 | .962 | .871 | .901 | .922 | .871 | .818 | .835 | .984 |

- **Digits**: Arabic-Indic number tokens went from 23 % (app before) to 70 %.
- **Speed**: one Tesseract pass with an integer model replaces ara+eng plus 27–137 second readings. Page 78 takes 23 s instead of 88 s.

**Hosting (not done).**
- The repository has no releases. I did not create one or upload anywhere, and nothing went to third-party services.
- The model file is `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\pro\ArabicFT.traineddata` (a copy of `train\tdi\ArabicFT.traineddata`): 10,021,428 bytes, SHA-256 `3620be6eba8cbee6049aa19125ae3e863a7c8639b0627820c05d32ccdc1ff72e`.
- **One step enables it:** create the release `ocr-models-1` on this repository and attach the file as `ArabicFT.traineddata`. The app has it pinned: `OcrEngine.FT_URL` + `PINNED["ArabicFT"]`, size + SHA-256.
- What the app does once it can fetch the file:
  - In Auto and AI it downloads the file optionally with the Arabic pack.
  - It starts Tesseract with `ArabicFT` instead of `ara+eng` (`OcrEngine.effectiveLangs`, unit-tested).
  - It skips the second readings.
- Until the upload, the download fails quietly and Auto runs with ara+eng: **0.866**.
- The float model is 0.004 better but 1.7× larger and 1.7× slower, so the integer model is the one pinned.

**Fonts and licences.**
- The training images came from fonts licensed on this PC. Only the trained weights are shipped, not the fonts.
- The base model is Apache-2.0.
- The training prose is from the user's own document. The model does not contain the text in a recoverable form, but it learned from it. This is noted for the owner's decision before publishing the model.


## 7. What went into the app

| file | what |
|---|---|
| `core/ocr/ProOcrCore.kt` (new, pure Kotlin) | DB post-processing (4-neighbour components, mean probability ≥ 0.5, DB unclip 1.6, scaling), detection size (long side 2400 px, multiples of 32), recognition width, greedy CTC with the model's 747 characters + space, visual → logical Arabic order (Latin/digit runs kept, brackets mirrored), line chunks, XY-cut reading order (right-to-left columns), words with boxes |
| `core/ocr/ProOcr.kt` (new) | ONNX Runtime sessions (≤ 4 threads, ALL_OPT), BGR / ImageNet normalisation for detection, (x/255 − 0.5)/0.5 at height 48 for recognition. Pinned model download: Hugging Face `PaddlePaddle/PP-OCRv5_mobile_det_onnx@e6f4fa85`, 4,826,518 B, SHA-256 `a4319856…6e61d`; `PaddlePaddle/arabic_PP-OCRv5_mobile_rec_onnx@14aaedcd`, 7,998,947 B, SHA-256 `799113eb…59110`; size + SHA-256 checked, through the offline-mode interceptor. Detection at 1600 px on low-RAM devices (measured cost −0.017 bag in Auto). |
| `assets/ocr/ppocrv5_arabic_dict.txt` | the recogniser's character list, taken from the model's `inference.yml` (Apache-2.0) |
| `core/ocr/OcrEnsemble.kt`, `core/ocr/OcrLexicon.kt` (new, pure) | the word-by-word choice (§5); the glossary lexicon (+ `OcrEngine.addKnownText` for a document's own text) |
| `assets/ocr/glossary_ar.txt` | the SmartDocs glossary (written for the app) |
| `core/ocr/CloudOcr.kt` (new) | Cloud Pro: JPEG (long side ≤ 2600) → the app's proxy `POST /ocr` → words in page pixels. Returns null (offline fallback) when the proxy is not configured, offline, on error, or when the provider returns no word boxes. |
| `core/ocr/OcrEngine.kt` | `mode` (from the OCR settings). Optional fine-tuned model `ArabicFT` (pinned: 10,021,428 B, SHA-256 `3620be6e…f72e`, URL = a release asset of this repo, §6): when installed and the mode is not Tesseract-only, it replaces `ara+eng` (`effectiveLangs`) and the second readings are skipped. In `recognize`: CLOUD first, if configured; then Tesseract + refine + cleanup as before; AUTO / AI add `ProOcr`; AI-only uses its words. `ensureLanguages("…ara…")` also fetches the AI models (optional; failures ignored). **The public API is unchanged**, so every caller, including the PDF→Word path of fix/pdf-to-word, keeps working. Note: callers using `wantWords = false` get Tesseract only, which keeps language detection fast; the PDF→Word path gets Auto quality if it passes `wantWords = true`. |
| `core/ocr/OcrPrep.kt` | `OcrEngineMode { AUTO, AI, TESSERACT, CLOUD }`, `OcrTuning.engine` (JSON key `eng`, default AUTO) |
| `core/ocr/OcrPipeline.kt` | `engineFor` passes the setting to the engine |
| `ui/ocr/OcrTuningUi.kt` + strings (ar + en) | "Recognition engine" chips: Auto (Tesseract + AI) / AI only / Tesseract only / Cloud Pro OCR, with a note about the download and the server |
| `app/build.gradle.kts`, `proguard-rules.pro` | `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` (MIT; AAR 27.9 MB); x86/x86_64 ONNX libraries excluded; keep rule for `ai.onnxruntime.**` |
| `ai-proxy/worker.js` | `POST /ocr` (`OCR_PROVIDER` = google / azure / vision, secret `OCR_KEY`, `OCR_URL` for Azure; 100 pages per device per day; 8 MB body), `capabilities.ocr` |
| `app/src/test/.../ProOcrTest.kt` | 19 JVM tests (listed in §8) |

**Sizes.**
- ONNX Runtime native code adds about 18.5 MB to the arm64 APK (`libonnxruntime.so` 17.6 MB + JNI 0.9 MB, stored uncompressed), about 13 MB to the armeabi-v7a APK, and about 31 MB to the universal APK.
- The AI models are not in the APK. They are a 12.8 MB optional download, made when the Arabic pack is installed.

**Speed** (PC: Intel Core i7-4810MQ, 4 cores / 8 threads, 2014, shared with other agents' jobs; page 78, 2474×3500 px):

| step | time |
|---|---|
| Tesseract ara+eng | 67 s |
| + second readings | 21 s |
| PP-OCR detection at 2400 px | 5.6–13.5 s |
| PP-OCR recognition of 82 boxes | 53 s with 4 contended threads (whole PP-OCR page in a later run: 45 s) |
| fine-tuned integer model, whole page, instead of ara+eng + second readings | 23 s (float model: 40 s) |

- Auto for one dense page is therefore roughly 1.5–2× the old time on the PC. With the fine-tuned model it is about the old time: 23 s + PP-OCR, against 88 s.
- **Tablet estimate (SM-T970, Snapdragon 865, 4 threads): about the same as this 2014 laptop CPU, so 1.5–3 minutes per dense page in Auto.** This is unverified; it is an extrapolation, not a measurement.
- Memory: detection input 1696×2400×3 floats = 49 MB + probability map 16 MB; low-RAM devices use 1600 px.

## 8. Tests and CI

- **JVM harness** (`jvm/runtests.sh`, the app's real `ProOcrCore`, `OcrEnsemble`, `OcrLexicon`, `OcrRefine`, `OcrCleanup`, `ArabicTextFix`): ProOcrTest 16/16. The three tests that need kotlinx-serialization or Android types (cloud answer parsing, fine-tuned language mapping, settings JSON) run only in Gradle.
- `ProOcrTest`, 19 tests:
  - CTC collapse and the space class;
  - dictionary parsing;
  - visual → logical (numbers, brackets, Latin, decimals);
  - DB boxes (unclip, scaling, threshold);
  - detection size;
  - two-column order;
  - a page frame detected as one tall box (s41) not joining rows;
  - one-row lines;
  - ensemble fixtures from page 78's real boxes: «YAY» → «٢٨٣», «: كلي» → «كتلة», «صرب» → «ضرب» (the box of «ضرب» with the reading the device build produced), a confident known word kept, one AI word over split pieces, AI-only words placed in their row or on a new line, no AI words = unchanged;
  - lexicon clitics;
  - cloud answer parsing;
  - fine-tuned model language mapping and pin;
  - engine setting JSON.
- **GitHub CI** (Build APK = assembleDebug + testDebugUnitTest): CI_SECTION
- **Not tested on a device**: ONNX Runtime on Android, the model download, real speed and memory, the settings UI, and the cloud route (no server keys).

## 9. Honest remaining errors (Auto)

- **Page 78 is 0.795 as shipped (0.841 with the fine-tuned model).** The bold mask glyphs still defeat the engines:
  - glued bold words: «مالمالمعادلةالأتية», «٢٨٣كيلوجول»;
  - ٢/٣ read as Y: «YA», «YAY» remain where PP-OCR also failed;
  - «كتلة» → «كتلم»;
  - formula subscripts: «CoH» for C2H2, «AH» for ΔH, because Δ is not in Tesseract's character set.
  - On the real page the formulas come from the repaired text layer, so this matters mainly for scans.
- **Arabic-Indic numbers** are still only 37 % exact tokens as shipped (70 % with the fine-tuned model): split («١ ٦» / «١٦»), decimals reordered, «٥» ↔ «٠».
- **Formulas and ions** in scans: 0.40.
- **Order inside mixed Arabic/Latin lines** is still that of the engines; line-order accuracy is 0.79.
- The ensemble's weights were tuned on the same 10 items it is scored on; expect a little less on new books.
- The 97 % target needs a recogniser trained on these fonts.
  - The fine-tuning in §6 gives +0.03 after only 3,000 iterations on 7,762 synthetic lines. More fonts (Yakout, Yaser), more degraded renders and real labelled lines from the books' repaired text layers are the next step.
  - A server VLM (Cloud Pro with a vision model or Google Vision) is the realistic path to ≥ 95 % today, but it is unmeasured here.
