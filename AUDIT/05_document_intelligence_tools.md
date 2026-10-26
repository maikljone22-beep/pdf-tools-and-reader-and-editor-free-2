# 05 — Document intelligence, processing tools, Office, forms, signatures, malformed input (prefix DOC)

## Scope & method

**Code read** (stable snapshot `D:\pdf\audit-wt`, commit da74d31, unless marked in-flux):
- OCR (IN-FLUX, read from `D:\pdf\SmartDocs-project-v0.4.0`; `core/ocr` and `ui/ocr` are identical in both trees as of this audit): `core/ocr/OcrEngine.kt`, `MixedPageOcr.kt`, `SearchablePdfWriter.kt`, `EditableTextPdfWriter.kt`, `ui/ocr/OcrInPlace.kt`, `OcrScreen.kt`, and `core/search/ArabicTextFix.kt` (in-flux).
- `core/tools5`: `OcrRunner.kt`, `LangDetect.kt`, `Optimizer.kt`, `PdfA.kt`, `PdfInspect.kt` (font/CMYK facts), `ShareAndPrint.kt`, `BatchWorker.kt`, `RecipeRunner.kt`, `ScanImage.kt`, `ScanBuilder.kt` (TextLayer), `PixelOps.kt`, `SourceReaders.kt` (EPUB); `ui/tools5/OcrBatchScreen.kt`, `T5Common.kt`.
- `core/pdf`: `PdfOps.kt` (load/saveTo/merge/keepPages/split/watermark/page numbers/stamp/compress/protect), `PdfSession.kt`, `ArabicShaper.kt`, `FormOps.kt`, `DigitalSignature.kt`; `core/edit/EditBidi.kt` (shapeVisual).
- `core/convert` (`PdfContentExtractor.kt`, `BidiText.kt`, `DocxBuilder.kt` grep), `core/convert6/PdfFlow6.kt`, `Cv6Engine2.kt` (IN-FLUX).
- Office: `core/office5/OPackage.kt`, `XDom.kt`, `XlsxIO5.kt`, `SheetBook.kt` (grep), `SnapshotUndo.kt`, `WordDoc.kt` (header), `core/office/OoxmlPackage.kt`; `ui/office/OfficeScreen.kt`, `SheetState.kt`.
- Forms: `core/forms5/FormValueWriter.kt`, `FormScripts.kt`, `core/forms/JsSandbox.kt`, `XfaSupport.kt`/`XfaTemplate.kt` (parser setup).
- Signatures: `core/pdf/DigitalSignature.kt`, `core/sign/PreSignAnalyzer.kt`, `CertEncryption.kt` (grep), `ui/signature/DigitalSignScreen.kt`, `CertificateTab.kt`; `core/esign/EsignClient.kt`, `EsignComposer.kt` (grep).
- Templates / voice: grep-level only (`TplCanvas.kt`, `AiSpeech.kt`).
- Callers traced: `ui/quick/QuickController.kt` (in-place apply + undo), `ui/pages/OrganizeState.kt`, `ui/tools/ExportTools.kt`, `core/ai/FileDocHost.kt`, `AndroidManifest.xml`.

**Validation run (exactly what was executed)** — scratch dir `C:\Users\User\AppData\Local\Temp\claude\D--pdf\bb42be6a-90e1-44e5-ae04-802722930807\scratchpad\docaudit\`:
1. `python -c "import pypdf"` → 6.14.2 present; `import pdfminer` → missing (skipped); `import fitz` → PyMuPDF 1.27.2.3 present; fontTools 4.63.0 present.
2. Copied (read-only originals untouched) from `C:\Users\User\Downloads\Telegram Desktop\chemistry\`:
   `a.pdf` = `_الملخص_الشامل_في_الكيمياء_للصف_ثالث_ثانوي_الوحدة_الأولى.pdf` (2.7 MB), `b.pdf` = `المرشد_2025م_في_الكيمياء_الجزء_الاول_الوحدة_الأولى.pdf` (9.7 MB), `c.pdf` = `ملخص_المساند_في_الكيمياء_3ث_الوحدة_الأولى.pdf` (1.9 MB); and the app font `app/src/main/assets/fonts/NotoNaskhArabic-Regular.ttf` → `noto.ttf`.
3. Scripts (all run with `python -I`): `scripts/char.py` (page classes, fonts, char categories), `scripts/garb.py` (Python port of `ArabicTextFix.isGarbage` + count of pages with image XObjects = pages `MixedPageOcr.plan` would OCR + broken-ligature signatures), `scripts/emb.py` (non-embedded fonts per page = pages `PdfA` would rasterise), `scripts/sig.py` (U+063B–U+063F "unused letter" signature of font-specific encodings), `scripts/cmap.py` (NotoNaskh cmap coverage). Output in `char_out.txt` and console.

**Results**

| File | Pages | Text / mixed / image-only* | Pages with image XObject (→ OCR planned) | `isGarbage` pages | Non-embedded fonts (pages affected) | PF-A / PF-B chars | Broken-text signatures |
|---|---|---|---|---|---|---|---|
| a.pdf | 17 | 13 / 2 / 2 | 16 | 0 | Times New Roman, Arial (14 of 17) | 0 / 0 | 14/17 pages contain U+063B–063F from font "Yaser- meem" (font-specific encoding); `يف` 5 vs `في` 0 |
| b.pdf | 53 | 28 / 24 / 1 | 51 | 0 | 8 Times/Arial variants (50 of 53) | 174 / 749 | Word-export ligature reversal: `يف` 64 vs `في` 103, 115 words starting `امل` (= `الم`); sample `املرشد يف ال كيمياء … حممد املسقف` |
| c.pdf | 45 | 44 / 1 / 0 | 2 | 0 | none | 45 / 480 | 42/45 pages U+063B–063F from "YakoutLinotypeLight"; sample `اًؽمد هلل اًري كٌم اإلنظبن` (= `الحمد لله الذي علم الإنسان`); `يف` 170 vs `في` 46; Quran glyphs as PF-A (`ﭧ ﭨ ﭷ`) |

\* heuristic: <20 chars + image coverage >50 % = image-only; ≥20 chars + coverage >50 % = mixed. No page had /Rotate ≠ 0 or CropBox ≠ MediaBox, so the rotation/cropbox findings below are code-traced only.

NotoNaskhArabic-Regular.ttf (191 KB, the only bundled font): covers Basic Latin, digits, Arabic block 253/256, PF-A 611/688, PF-B 141/144, Persian/Urdu letters; **missing** U+2082 (₂), U+2192 (→).

**Could not verify**: anything needing a device (Tesseract runtime behaviour, PDFium memory on huge images, WorkManager stop behaviour, other viewers' handling of /ActualText). tessdata_best file sizes were not downloaded (no network calls). Office (Word/PPTX) round-trip fidelity and template/voice modules were only sampled.

## Capability inventory

| Feature | Status | Evidence |
|---|---|---|
| OCR language data | partial — downloaded on first use from GitHub `tessdata_best`, nothing bundled; no checksum; corrupt file never re-fetched | `core/ocr/OcrEngine.kt:33-71` |
| Scanned / digital / mixed page detection | partial / misleading — "has text" = PDFium text non-blank; "mixed" = any image XObject in resources; broken-ToUnicode Arabic counted as good text | `MixedPageOcr.kt:47-58`, `ArabicTextFix.kt:60-65` |
| Searchable OCR layer (reader quick action) | works for MediaBox==CropBox pages; misaligned when CropBox differs | `SearchablePdfWriter.kt:48-53` vs `OcrInPlace.kt:69-72` |
| Editable-text OCR | partial — rebuilds page as JPEG + visible text; resets rotation/boxes, keeps annotations | `EditableTextPdfWriter.kt:146-203` |
| Duplicate layer when OCR runs twice | mostly prevented (words covered >35 % by existing char boxes dropped) but page is re-OCR'd in full | `MixedPageOcr.kt:69-83, 108-126` |
| OCR auto language | partial — first OCR page only, 10 % threshold | `OcrRunner.kt:106`, `LangDetect.kt:44-46` |
| OCR cancel / progress | partial — progress per page; Tesseract call itself likely not interruptible | `OcrEngine.kt:120`, `OcrRunner.kt:85-94` |
| Original file on OCR | in place with undo backup (reader), new `_ocr` file (OCR screen/batch) | `QuickController.kt:85-98`, `OcrBatchScreen.kt:87-95` |
| Arabic writing (stamps, watermark, page numbers, OCR layer, forms) | works for Arabic; broken joining for Persian/Urdu letters; Latin words with non-WinAnsi chars dropped | `ArabicShaper.kt:12-26`, `SearchablePdfWriter.kt:61,75` |
| Copy / extract Arabic | copy-selection fixed (`ArabicTextFix.fix`); PDF→TXT, AI context, DOCX-from-text not fixed | `TextSelectionLogic.kt:72`, `ExportTools.kt:77-84`, `FileDocHost.kt:30-37` |
| Compress / optimize | works; strips encryption silently; owner restrictions lost on re-protect | `Optimizer.kt:172`, `PdfOps.kt:349-366` |
| PDF/A-2b | partial / misleading — rasterises pages with non-embedded fonts (most Arabic Word exports), deletes AP-less annotations; self-check only of own checks | `PdfA.kt:144-160, 168-179` |
| Merge / split / extract / reorder | works for simple PDFs; drops AcroForm, outlines, struct tree, OCGs, encryption on page subsets/reorder | `PdfOps.kt:80-133`, `OrganizeState.kt:194-218` |
| Virtual printer | works (saves to Documents/Printed, notification) | `ShareAndPrint.kt:124-158` |
| Batch (WorkManager) | partial — no foreground service, shared temp dir, plaintext passwords | `BatchWorker.kt:104-126`, `BatchJobs.enqueue:74-81` |
| AcroForm fill | works, Arabic DA font embedded (full font); full rewrite (not incremental) | `FormOps.kt:186-206` |
| Form JS (Rhino) | works with sandbox (ClassShutter, deadline); OOM rethrown | `JsSandbox.kt` |
| XFA | static XFA dropped on request; parser DOCTYPE hardening attempted | `FormOps.kt:203`, `XfaTemplate.kt:104-108` |
| Digital signature (sign) | crypto correct (CMS SHA-256, PAdES-B attrs, TSA, DocMDP, incremental append); visible/seal pre-steps rewrite the file | `DigitalSignature.kt:182-213, 245-331` |
| Signature verify | partial — CMS integrity only; green "valid" independent of trust; no ByteRange/DocMDP change checks | `DigitalSignature.kt:367-421`, `DigitalSignScreen.kt:236-249` |
| E-sign (server) | partial — final PDF images + audit pages, no cryptographic seal | `EsignComposer.kt:24-64` |
| XLSX edit/save | works with DOM-in-place; array formulas & unparseable shared formulas degraded | `XlsxIO5.kt:110-127, 303-306` |
| Office open of huge/zip-bomb files | partial — whole package in memory, OOM caught → "open failed" | `OPackage.kt:174-185`, `OfficeScreen.kt:60-75` |
| Zip extraction (zip-slip) | works (canonical-path guard) | `core/files/ZipUtil.kt:29-48` |
| Malformed PDFs | errors surfaced as raw (English) messages, no crash in tools5 (`Throwable` caught) | `T5Common.kt:57-60`, `QuickController.kt:99-105` |

## Findings

### DOC-01 Adding a visible signature or paging seal destroys existing signatures (full rewrite before signing)
- Type: data-integrity
- Status: CONFIRMED (traced in code)
- Severity: High      Priority: P0
- Evidence: `core/pdf/DigitalSignature.kt:257-271` — before the incremental signature, `PagingSeal.apply(...)` and `PdfOps.stampImage(...)` / `PdfOps.stampText(...)` are run on the input; these do `load(src)` + `saveTo(doc, out)` (`PdfOps.kt:236-254`, `262-276`, `PagingSeal.kt:24-48` → `doc.save(out)`), i.e. a FULL save that rewrites every object and xref. Only after that is `d.saveIncremental` used (`:305`). `PreSignAnalyzer.kt:156-159` counts existing signatures and warns, but the code still rewrites. Additionally `:276-283` re-saves encrypted input fully "without security" → prior signatures broken and the signed output is unencrypted.
- User impact / scenario: second signer of a contract opens a PDF already signed by party A, chooses "visible signature" (default UI flow) → party A's signature becomes invalid in Acrobat ("document has been altered or corrupted"). For a password-protected original, the signed copy is silently unprotected.
- Proposed fix: when `doc.signatureDictionaries` is non-empty (or the file is encrypted), do not pre-stamp; instead create the visible appearance as the signature widget's /AP (PdfBox `SignatureOptions.setVisualSignature` / a PDVisibleSigProperties template) inside the same incremental save; for encrypted inputs, sign incrementally with the existing encryption (PdfBox supports `saveIncremental` on decrypted-loaded encrypted docs) or refuse with a clear message.
- Effort: M     Regression risk: Med     Dependencies: none
- Validation: unit test: sign `fixture_signed_once.pdf` with visible=true → `verify()` returns 2 results, first `signatureValid=true`; device test in Acrobat Reader.

### DOC-02 Form filling always does a full save — breaks signatures and certification that allow form fill
- Type: data-integrity
- Status: CONFIRMED
- Severity: High      Priority: P1
- Evidence: `core/pdf/FormOps.kt:186-206` `fillFields` → `doc.save(out)`; `core/forms5/FormValueWriter.kt:42-53` (`AppearanceRotation.prepare` → `doc.save(out)` `:161`, image buttons `doc.save(out)` `:52`). `saveIncremental` is used nowhere outside signing (`grep saveIncremental` → only `DigitalSignature.kt:305`, `LtvSupport.kt:244,278`).
- User impact / scenario: government/university forms certified with DocMDP P=2 ("form fill + sign allowed") or forms already signed by an issuer: after filling in SmartDocs the issuer's signature shows invalid → form rejected.
- Proposed fix: if the document has any signature dictionary, load and fill then `saveIncremental` (mark changed field/widget/AcroForm dicts `setNeedToBeUpdated(true)`); keep full save only for unsigned documents.
- Effort: M     Regression risk: Med     Dependencies: DOC-01 (shared helper)
- Validation: unit test: fill field in certified P=2 fixture → `DigitalSignature.verify` still valid and `coversWholeDocument=false` only for the cert signature; Acrobat device check.

### DOC-03 Reorder / paste / extract / split rebuild a new PDDocument — AcroForm, outlines, tags, layers, page labels and encryption are dropped
- Type: data-integrity
- Status: CONFIRMED (copy-back of the unencrypted result into the original: PROBABLE, save path not traced)
- Severity: High      Priority: P1
- Evidence: `core/pdf/PdfOps.kt:93-101` `keepPages`: `PDDocument().use { nd -> pages.forEach { nd.importPage(doc.getPage(it)) }; nd.documentInformation = ...; saveTo(nd, out) }` — only Info is copied; no `/AcroForm`, `/Outlines`, `/StructTreeRoot`, `/OCProperties`, `/PageLabels`, `/Names`, `/Metadata`, and `nd` is never protected although `load()` re-protects `doc`. `PdfOps.reorder = keepPages` (`:117`), used in place by `ui/pages/OrganizeState.kt:194-206` (reorder/move) and `:218` (paste). `split` (`:120-133`) same pattern with `load(src)` (no password).
- User impact / scenario: user drags page 3 before page 1 in a fillable form → widgets still draw but fields are no longer a form (no fill, no JS); bookmarks vanish; a password-protected file becomes unprotected after reorder.
- Proposed fix: implement reorder/delete/duplicate in place on the loaded document (`doc.pages.remove/insertBefore`, keep catalog) instead of `importPage` into a new doc; for extract/split, copy AcroForm fields whose widgets are on kept pages and re-apply the source protection policy.
- Effort: M     Regression risk: Med     Dependencies: none
- Validation: unit test: reorder a fixture with 2 text fields + outline + user password → result has `acroForm.fields.size == 2`, outline non-empty, `isEncrypted`.

### DOC-04 Broken Arabic text layers (font-specific encodings, reversed Word ligatures) are classified as good text — no OCR, wrong search/copy/convert/AI (in-flux area)
- Type: bug
- Status: CONFIRMED (code + measured on test PDFs)
- Severity: High      Priority: P1
- Evidence: `core/search/ArabicTextFix.kt:60-65` `isGarbage` counts only PUA/U+FFFD/control chars (≥30 %). `MixedPageOcr.kt:57`, `OcrInPlace.kt:80` use it to decide OCR/rebuild. Measured: 0 garbage pages in a/b/c.pdf while a.pdf 14/17 and c.pdf 42/45 pages contain U+063B–U+063F (letters unused in Arabic, produced by fonts "Yaser- meem"/"YakoutLinotypeLight"), c.pdf sample `اًؽمد هلل اًري كٌم` for `الحمد لله الذي علم`; b.pdf has Word-export ligature reversal (`يف` 64×, 115 words starting `امل`).
- User impact / scenario: the user's own chemistry summaries: searching "الحمد" or "في" finds nothing/wrong hits; "Convert to Word" and AI chat get gibberish; OCR is skipped ("nothing to OCR") because the page "has text".
- Proposed fix: extend the quality check: (a) fraction of chars in U+063B–U+063F/U+066E–066F or a per-font "unknown letter" ratio > 2 % → garbage; (b) bigram heuristic for reversed ligatures (`يف` vs `في`, initial `امل`/`حمم`) and offer a "repair text" pass (swap known reversed ligature pairs) before falling back to OCR; (c) report the reason in the OCR sheet ("text layer is corrupt — OCR recommended").
- Effort: M     Regression risk: Low     Dependencies: coordinate with the session working on Arabic/OCR root causes
- Validation: unit tests with extracted strings from a/b/c.pdf (`isGarbage` → true for c.pdf page 4 text; false for clean Arabic paragraph).

### DOC-05 Optimize / compress / PDF-A silently remove the PDF's security; re-protected output loses permissions
- Type: security
- Status: CONFIRMED
- Severity: Medium      Priority: P1
- Evidence: `core/tools5/Optimizer.kt:172` `if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true`; `OptimizeExportScreens.kt:99` calls with `password = null`, so owner-password-only files (open without password) are saved unrestricted; `RecipeRunner.kt:54` passes the password → output decrypted. `PdfOps.compress` (`:349-366`) re-protects with `protect(tmp, prot, password, password)` whose defaults allow print/copy/modify/annotate (`:322-330`) → original restrictions lost. `PdfA.kt:150` removes encryption (required by PDF/A, but not surfaced as a warning before running).
- User impact / scenario: a teacher distributes a no-copy/no-print PDF; a student runs "Optimize" → output has no restrictions; a user compresses their password-protected payslip in a batch → output has no password.
- Proposed fix: keep the original policy: read `doc.encryption` (key length, permissions) and, after optimizing, call `doc.protect(StandardProtectionPolicy(ownerPw?, userPw, originalPerms))`; if the owner password is unknown, refuse ("restricted document — owner password needed") rather than strip. Show an explicit confirmation in PDF/A.
- Effort: S     Regression risk: Low     Dependencies: DOC-06
- Validation: unit test: optimize an AES-128 file with user pw + no-print → result `isEncrypted`, `currentAccessPermission.canPrint()==false`.

### DOC-06 `PdfOps.load` re-protects with user password as owner password and AES-128 — owner-only restrictions become removable
- Type: security
- Status: CONFIRMED
- Severity: Medium      Priority: P1
- Evidence: `core/pdf/PdfOps.kt:39-49`: `doc.protect(StandardProtectionPolicy(pw, pw, ap).apply { encryptionKeyLength = 128 ... })` with `pw = password.orEmpty()`. Every tool using `load()` (watermark, page numbers, stamps, rotate, delete pages, set info, OCR writers, forms) writes this policy.
- User impact / scenario: a restricted PDF (owner password, no user password) is watermarked → saved with owner password "" → any tool can remove restrictions; AES-256 inputs downgraded to AES-128.
- Proposed fix: keep `doc.encryption` as is when the owner password is unknown (PdfBox 2.0 re-encrypts with the original handler when `isAllSecurityToBeRemoved` is false) — or generate a random owner password instead of reusing `pw`, and use the original key length.
- Effort: S     Regression risk: Med     Dependencies: none
- Validation: unit test: load owner-only fixture, watermark, reopen → owner password ≠ "" (`PDDocument.load(out, "")` → `isOwnerPassword == false`).

### DOC-07 PDF/A conversion turns most Arabic Word-exported pages into 200-dpi pictures (text lost)
- Type: data-integrity
- Status: CONFIRMED (code + measured)
- Severity: High      Priority: P1
- Evidence: `core/tools5/PdfA.kt:34-36` default `rasterizeProblemPages = true`; `:153` pages using any non-embedded font are passed to `rasterize` (`:106-133`), which replaces content with a JPEG (`page.resources = PDResources()`, OVERWRITE). No OCR layer is added. Measured: a.pdf 14/17 pages and b.pdf 50/53 pages use non-embedded Times New Roman/Arial.
- User impact / scenario: "Archive as PDF/A" on a typical Arabic school PDF → file no longer searchable/selectable, larger, blurry when zoomed; report says "passes".
- Proposed fix: embed a substitute font instead (NotoNaskh for Arabic glyph coverage + a bundled Latin serif/sans, rewrite /FontFile2 + widths) and rasterise only if substitution fails; when rasterising, add an invisible OCR layer (reuse `SearchablePdfWriter`) and show a page count warning before running.
- Effort: L     Regression risk: Med     Dependencies: OCR data availability
- Validation: run on a.pdf fixture → `PdfInspect.fontsNotEmbedded` empty and page text length unchanged.

### DOC-08 PDF/A deletes annotations without /AP (incl. form widgets) and sets NeedAppearances=false
- Type: data-integrity
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence: `PdfA.kt:168-176` keeps an annotation only if subtype is Popup/Link or it has `/AP`; `:162` `af.setBoolean(N.NeedAppearances, false)`. Fields that relied on NeedAppearances lose their widgets while the field objects stay in `/AcroForm /Fields` (orphans).
- User impact / scenario: a filled form generated by another app (values without appearance streams) is converted → values disappear.
- Proposed fix: before filtering, call `acroForm.refreshAppearances()` (PdfBox 2.0.27 has it) for widgets lacking /AP; also remove deleted widgets from the field tree.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test: form with NeedAppearances + no AP → after convert, widget count unchanged and `/AP` present.

### DOC-09 Searchable OCR layer is mapped with MediaBox while the page image is rendered with the CropBox (in-flux area)
- Type: bug
- Status: CONFIRMED (code); not observed in the test PDFs (all CropBox==MediaBox)
- Severity: Medium      Priority: P1
- Evidence: render size comes from PDFium `getPageWidthPoint/HeightPoint` (crop box, rotated) — `PdfSession.kt:67-75`, used in `OcrInPlace.kt:69-72`, `OcrRunner.kt:87-90`; but `SearchablePdfWriter.kt:48-53` uses `pdPage.mediaBox` for scale and origin. `MixedPageOcr.kt:177` and `EditableTextPdfWriter.kt:155` correctly use the CropBox — inconsistent.
- User impact / scenario: scanned books cropped by scanner software (CropBox inset) → search highlights and text selection land beside/below the words.
- Proposed fix: in `addLayer`, use `pdPage.cropBox ?: mediaBox` exactly like `EditableTextPdfWriter`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test: fixture with CropBox inset 50 pt + known word box → written text matrix origin within 1 pt of expected.

### DOC-10 Editable-text OCR rebuild resets rotation/boxes but keeps annotations; vector pages and 1-bit scans become JPEG (in-flux area)
- Type: data-integrity
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence: `EditableTextPdfWriter.kt:191-197`: `pdPage.rotation = 0; mediaBox = cropBox = PDRectangle(dispW, dispH)` (origin moved to 0,0), `resources = PDResources()`, OVERWRITE content; `/Annots` untouched. Background is `JPEGFactory ... 0.82f` at ≤200 dpi (`:184-189`). Pages whose text is "garbage" are rebuilt too (`OcrInPlace.kt:79-82`).
- User impact / scenario: links/comments/form widgets on a /Rotate 90 or offset-MediaBox page end up in the wrong place; a 30 KB/page CCITT scan becomes ~0.5 MB/page; a crisp vector page with a broken text layer becomes a blurry picture.
- Proposed fix: transform annotation /Rect (and /QuadPoints) with the same display transform, or keep /Rotate and draw the image with the inverse matrix; keep the original image XObject when it is 1-bit (draw the "cleaning" as white rectangles over it instead of re-encoding); for garbage-text vector pages, prefer hiding the bad text (render mode 3 via content rewrite) over rasterising.
- Effort: M     Regression risk: Med     Dependencies: none
- Validation: unit test with rotated page + link annotation → link rect maps onto the same displayed word after rebuild.

### DOC-11 OCR auto language detection uses only the first OCR page and a 10 % threshold — English is dropped for Arabic science documents
- Type: bug
- Status: CONFIRMED (code + measured share)
- Severity: Medium      Priority: P1
- Evidence: `OcrRunner.kt:106` `if (langs == null) langs = detectLanguages(engine, bmp)` — once, on the first planned page; `LangDetect.kt:44-46` keeps a script only if `value*10 >= total`. Measured Latin share of a.pdf text: 739 / (14498+739) = 4.8 % → result would be `"ara"` only. Same path for AutoOcr before conversion (`OcrRunner.kt:190`) and recipes (`RecipeRunner.kt:51` "auto").
- User impact / scenario: OCR of a chemistry handout: NaCl, H2SO4, pH, units come out as Arabic letters for the whole document; cover pages make it worse.
- Proposed fix: when Arabic is detected, always keep `eng` as secondary (`ara+eng`), sample 2–3 pages (first, middle) and lower the Latin threshold to ~2 %.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test `LangDetect.detect(textOf(a.pdf))` contains "eng".

### DOC-12 "Mixed page" plan OCRs almost every page that has any image (logos, backgrounds) at 300 dpi (in-flux area)
- Type: performance
- Status: CONFIRMED (code + measured)
- Severity: Medium      Priority: P2
- Evidence: `MixedPageOcr.kt:47-58` page needs OCR if `pi in withImages` (`hasImages` = any image XObject in resources, regardless of size or whether it is drawn). Measured: a.pdf 16/17, b.pdf 51/53 pages planned although they are digital text; `dropExisting` then discards nearly all words. Each page: 300 dpi render ≤3500 px + Tesseract tessdata_best.
- User impact / scenario: "OCR" on b.pdf runs ~51 full-page recognitions (minutes, battery, heat) to add almost nothing; the progress suggests it is doing useful work.
- Proposed fix: only treat a page as mixed when drawn image area (via PDFium image objects/bounds or PdfBox `PDFGraphicsStreamEngine`) covers >25 % of the page AND the text char count is low relative to the area; skip images < 5 % of the page.
- Effort: M     Regression risk: Low     Dependencies: none
- Validation: unit test on b.pdf fixture: planned pages ≤ 5.

### DOC-13 Batch worker runs long OCR/convert jobs without a foreground service
- Type: bug
- Status: PROBABLE (WorkManager 10-minute execution limit; needs device test)
- Severity: Medium      Priority: P1
- Evidence: `core/tools5/BatchWorker.kt:104-126` `CoroutineWorker.doWork` processes all files in one run; no `setForeground`/`getForegroundInfo` anywhere in `core/tools5` (grep). When the system stops the worker it is rescheduled and restarts from file 0; `RecipeRunner.run` then writes `name_2.pdf` (`RecipeRunner.kt:117-121`).
- User impact / scenario: batch OCR of 20 scanned PDFs in background stops after ~10 min and restarts, producing duplicates and never finishing on slow phones.
- Proposed fix: call `setForeground(ForegroundInfo(...))` (type dataSync) at start; persist per-file completion in the log and skip done files on restart.
- Effort: S     Regression risk: Low     Dependencies: notification permission
- Validation: device test: 15-minute batch completes; restart skips completed files.

### DOC-14 Concurrent batch jobs delete each other's temp files
- Type: bug
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence: `BatchWorker.kt:109` all jobs use `cacheDir/t5_batch_tmp`; `:122` `finally { ... temp.listFiles()?.forEach { it.delete() } }`. WorkManager runs enqueued workers in parallel by default; requests are not unique/chained (`BatchJobs.enqueue:76-79`).
- User impact / scenario: user queues two batches; the first one finishing wipes the second's intermediate files → second job's files fail with FileNotFoundException.
- Proposed fix: per-job temp dir `t5_batch_tmp/<jobId>`; or `enqueueUniqueWork("t5_batch", APPEND_OR_REPLACE, ...)`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit/instrumented test running two workers concurrently.

### DOC-15 Batch passwords persisted in plaintext and never deleted
- Type: security
- Status: CONFIRMED
- Severity: Medium      Priority: P1
- Evidence: `BatchWorker.kt:45` writes `"pw"` into `filesDir/t5_batch/<id>.json`; comment `:73` says "stored only ... for the run" but the spec is deleted only by user action (`BatchJobs.delete:68-71`), not after `doWork`. `AndroidManifest.xml:25` `android:allowBackup="true"` with no backup rules (no `res/xml/*backup*`).
- User impact / scenario: PDF passwords stay on disk indefinitely and are included in Google auto-backup.
- Proposed fix: strip `pw` from the spec after the run (or keep them only in memory / EncryptedFile); add backup exclusion for `t5_batch`.
- Effort: S     Regression risk: Low     Dependencies: DOC-16
- Validation: unit test: after `doWork`, spec JSON has no "pw".

### DOC-16 Auto-backup includes OCR language data and the private signing keystore
- Type: security
- Status: PROBABLE (quota behaviour per Android docs; sizes of tessdata_best not measured)
- Severity: Medium      Priority: P2
- Evidence: `AndroidManifest.xml:25` `allowBackup="true"`, no `fullBackupContent`/`dataExtractionRules`; OCR data in `filesDir/ocr_best/tessdata` (`OcrEngine.kt:33`); private key in `filesDir/own_certificate.p12` (`DigitalSignature.kt:107`).
- User impact / scenario: several tessdata_best packs can exceed the 25 MB auto-backup quota so app backup stops entirely (notes, settings lost on phone change); the PKCS#12 (protected only by a user-chosen password) leaves the device.
- Proposed fix: add `data_extraction_rules.xml`/`backup_rules.xml` excluding `ocr_best/`, `t5_batch/`, `own_certificate.p12`, caches.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: `adb shell bmgr backupnow` dry run shows excluded paths (device test).

### DOC-17 Multi-step recipes fail on encrypted inputs after the first step
- Type: bug
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence: `RecipeRunner.kt` `run`: `if (next !== cur ...) { ...; if (s.type != StepType.PROTECT) pw = null else ... }`. But WATERMARK/PAGE_NUMBERS/ROTATE/SET_INFO/OCR/REMOVE_METADATA/AUTO_TAG use `PdfOps.load(src, password)` which re-encrypts with the same password (`PdfOps.kt:41-46`) → the next step opens an encrypted file with `pw = null` → InvalidPasswordException.
- User impact / scenario: recipe "OCR → watermark" on a password-protected scan logs "failed" for every file.
- Proposed fix: set `pw = null` only after steps that decrypt (OPTIMIZE, PDFA); otherwise keep `pw`.
- Effort: S     Regression risk: Low     Dependencies: DOC-05/06 if those change decryption behaviour
- Validation: unit test: recipe [WATERMARK, PAGE_NUMBERS] on encrypted fixture → ok=true.

### DOC-18 Persian/Urdu letters are not shaped — broken joining in everything the app writes, although fas/urd OCR is offered
- Type: bug
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence: `core/pdf/ArabicShaper.kt:12-26` `forms` has no entries for پ چ ژ گ ٹ ڈ ڑ ں ے ۀ ه‍ (U+067E, 0686, 0698, 06AF, 0679, 0688, 0691, 06BA, 06D2, 06C0, 06C1); `EditBidi.kt:148-149` treats 0x0671..0x06D3 as shapable but `shapeRaw` appends them unshaped and neighbours lose their joining (`isArabicLetter` false). Font has the presentation forms (cmap check: U+FB56, FB7A, FB8A, FB92 present). OCR languages include fas/urd (`OcrInPlace.kt:21-24`).
- User impact / scenario: Persian OCR in "editable text" mode, Persian watermark/stamp/form text renders as disconnected letters.
- Proposed fix: add the PF-A forms for these letters (e.g. peh FB56–FB59, tcheh FB7A–FB7D, jeh FB8A–FB8B, gaf FB92–FB95, keheh FB8E–FB91, farsi yeh FBFC–FBFF, yeh barree FBAE–FBAF, noon ghunna FB9E–FB9F, tteh FB66–FB69, ddal FB88–FB89, rreh FB8C–FB8D, heh goal FBA6–FBA9); note 'ک'/'ی' are currently mapped to Arabic kaf/yeh forms (FED9/FEEF) which changes the extracted letter.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test `ArabicShaper.shapeRaw("پدر")` starts with U+FB58 (initial peh) in logical form.

### DOC-19 Multi-line Arabic form fields wrap in reverse line order; field colour forced to black
- Type: bug
- Status: PROBABLE (PdfBox appearance generator wraps the visual string left-to-right; needs device check)
- Severity: Medium      Priority: P2
- Evidence: `FormOps.kt:161-171` `applyTextAppearance`: `visual = shown.split('\n').joinToString { ArabicShaper.shape(it) }` (whole paragraph reversed into one visual line) then `f.setValue(visual)`; PdfBox's `AppearanceGeneratorHelper` breaks long lines from the start of the string, i.e. from the visual left = logical END. `f.defaultAppearance = "/SDNaskh $size Tf 0 g"` drops the original colour.
- User impact / scenario: a "notes" field with two lines of Arabic shows the second half of the sentence on the first line.
- Proposed fix: for multiline fields, wrap in logical order first (measure with the font and field width), then shape each wrapped line and join with '\n'; keep the colour operator from the original DA.
- Effort: M     Regression risk: Low     Dependencies: none
- Validation: render test: 2-line Arabic value → first rendered line equals shape(first logical words).

### DOC-20 Words with characters outside WinAnsi are silently dropped from OCR layers and stamps; three diverging text-layer writers
- Type: bug
- Status: CONFIRMED
- Severity: Low      Priority: P3
- Evidence: Latin words use `PDType1Font.HELVETICA` (`SearchablePdfWriter.kt:31,61`, `EditableTextPdfWriter.kt:131`), `showText` of e.g. "H₂O"/"→" throws and `runCatching` (`SearchablePdfWriter.kt:75`, `EditableTextPdfWriter.kt:200`) drops the word; `TextLayer.write` (`ScanBuilder.kt:28-55`) filters to 32..255 instead; NotoNaskh lacks ₂ and → (cmap check). Three implementations of the same mapping (SearchablePdfWriter, EditableTextPdfWriter.writeWord, TextLayer).
- User impact / scenario: chemistry formulas or arrows recognised by OCR are not searchable; stamps containing such characters fail entirely (`stampText` throws).
- Proposed fix: use the embedded Type0 font for Latin too (it covers Latin), with a per-char fallback that replaces unknown glyphs by `?`-width spaces while keeping /ActualText; consolidate into one writer.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test: addLayer with word "H₂O" → page text contains "H₂O".

### DOC-21 PDF→Text, AI context and text exports do not repair Arabic presentation forms / visual order
- Type: bug
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence: `ArabicTextFix.fix` is called only in `ui/reader/TextSelectionLogic.kt:72` and `ui/edit/EditController.kt:192`. `ui/tools/ExportTools.kt:77-84` (`extractAllText` → PDF→Text tool), `core/ai/FileDocHost.kt:30-37` (AI document text), `core/ai/DocActions.kt:238-242` (DOCX/TXT from `allText()`) use raw PDFium text. b.pdf contains 749 PF-B + 174 PF-A characters.
- User impact / scenario: "Save as TXT" or "Ask AI about this PDF" on b.pdf gives presentation-form/reversed Arabic; AI answers degrade.
- Proposed fix: apply `ArabicTextFix.fix` per page in `extractAllText`, `FileDocHost.text`, `DocActions` text exports (not in search-index code that needs char indexes).
- Effort: S     Regression risk: Low     Dependencies: in-flux ArabicTextFix
- Validation: unit test: extractAllText on fixture with PF text returns base letters.

### DOC-22 OCR cancellation does not interrupt Tesseract; batch screens never call engine.cancel
- Type: usability
- Status: PROBABLE (Tesseract4Android `stop()` is documented to work with the progress-monitor API, while the code uses `getUTF8Text`; needs device test)
- Severity: Low      Priority: P2
- Evidence: `OcrEngine.kt:90-117` uses `t.setImage` + `t.utF8Text`, `cancel()` calls `api?.stop()` (`:120`); `OcrBatchScreen.kt:79-97` and `BatchWorker` only cancel the coroutine (engine is local), checks happen between pages (`OcrRunner.kt:86`).
- User impact / scenario: pressing Cancel during a dense 300-dpi Arabic page waits tens of seconds; the native call keeps the CPU busy after leaving the screen.
- Proposed fix: use `getHOCRText(0)` with a `ProgressNotifier` (supports cancel) or run recognition in a cancellable child and recycle the API on cancel; pass the engine's cancel to `job.cancel`.
- Effort: M     Regression risk: Med     Dependencies: none
- Validation: device test: cancel completes < 1 s.

### DOC-23 Tesseract data: no bundled fallback, no integrity check, no recovery from a corrupt pack
- Type: usability
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence: `OcrEngine.kt:35` installed = file exists and > 100 KB; `:44-71` downloads from `github.com/tesseract-ocr/tessdata_best/raw/main/...` without hash; `init` failure (`:81-82`) returns false → "init failed" message, file is not deleted, so the same bad file is used forever; `assets/` contains only `fonts/NotoNaskhArabic-Regular.ttf`.
- User impact / scenario: first OCR needs internet (offline users in Yemen-type networks fail); a corrupt/intercepted download bricks OCR until app data is cleared.
- Proposed fix: verify SHA-256 against a pinned list; on `init` failure delete the language files and offer re-download; consider bundling `ara`+`eng` `tessdata_fast` (smaller) as offline fallback; show download size before downloading.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test: truncated file → `init` false → file removed → `isLanguageInstalled` false.

### DOC-24 Convert-with-auto-OCR swallows cancellation and OCR errors (in-flux area)
- Type: bug
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence: `core/convert6/PdfFlow6.kt:84` `if (!force) return runCatching { AutoOcr.ocrIfScanned(...) }.getOrNull()` — `runCatching` also catches `CancellationException`, download failures and init failures; null means "not scanned, convert original".
- User impact / scenario: converting a scanned PDF offline silently yields an empty Word file; pressing Cancel during OCR continues with conversion.
- Proposed fix: rethrow `CancellationException`; distinguish "not scanned" (null) from failure (surface "OCR unavailable: …" and ask whether to continue).
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test: cancelled coroutine → ocrCopy throws CancellationException.

### DOC-25 Optimizer image dedup points duplicates at the original, not the recompressed image
- Type: bug
- Status: CONFIRMED
- Severity: Low      Priority: P3
- Evidence: `Optimizer.kt:122-127` stores `c.byHash[key] = img` (original) before processing; a later duplicate returns `prev` = original (`:125`) while the first occurrence is replaced by `made` (`:150`). Result keeps both the original and the new stream.
- User impact / scenario: a logo repeated on 300 pages: first page gets the small JPEG, the other 299 keep the large original → compression gain mostly lost.
- Proposed fix: after processing, `c.byHash[key] = made ?: img`, and on a hit return `c.done[prev.cosObject] ?: prev`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test: two identical images → one image stream in output.

### DOC-26 Optimizer re-encodes lossless/grey/indexed images as RGB JPEG
- Type: enhancement
- Status: CONFIRMED (colour-fidelity for CMYK/ICC on PdfBox-Android: HYPOTHESIS)
- Severity: Low      Priority: P3
- Evidence: `Optimizer.kt:134-145` only masks/1-bit are skipped; any image above the dpi target (`needScale`) is decoded via `img.image` and written with `JPEGFactory` — Flate screenshots, line art, DeviceGray and Indexed images become RGB JPEG.
- User impact / scenario: text-in-image diagrams get JPEG ringing; grey scans grow 3 channels.
- Proposed fix: keep Flate for images with few colours (Indexed/≤256 colours) via `LosslessFactory` after scaling; write DeviceGray JPEG for grey sources.
- Effort: M     Regression risk: Low     Dependencies: none
- Validation: unit test: Indexed PNG-like image stays Flate.

### DOC-27 XLSX save degrades array formulas and unparseable shared formulas
- Type: data-integrity
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence: `core/office5/XlsxIO5.kt:110-116` shared-formula children get `formula = fx?.let { ... }` — null when the master did not parse (`FxParser.tryParse`) → stored as a constant (`:127`); `cellXml` (`:303-306`) writes `<f>text</f>` without `t="array"`/`ref`/`dataTable` attributes. Only sheets that were changed are regenerated (doc `:8-10`), but one edit regenerates the whole sheet.
- User impact / scenario: editing one cell in a sheet with `=XLOOKUP(...)` filled down (shared) or a CSE array formula → after save, filled-down cells are plain numbers / the array formula computes per cell in Excel.
- Proposed fix: keep the original `<f>` element (attributes + text) on cells whose formula was not edited; for shared children whose master failed to parse, preserve `t="shared" si=` references.
- Effort: M     Regression risk: Med     Dependencies: none
- Validation: round-trip unit test: workbook with array formula + unsupported shared formula, edit another cell, compare `<f>` elements.

### DOC-28 Office packages are fully exploded into memory with no size limit
- Type: performance
- Status: CONFIRMED
- Severity: Low      Priority: P3
- Evidence: `OPackage.open` = `read(file.readBytes())` and every entry `zin.readBytes()` (`OPackage.kt:172-183`); undo `snapshot()` re-serialises every dirty DOM on each checkpoint (`:133-137`, `SnapshotUndo.kt:15-20`, `WordState.kt:115`). OOM is caught (`OfficeScreen.kt:73`) → "open failed".
- User impact / scenario: a 40 MB xlsx export or a crafted zip bomb cannot be opened (or freezes on low-RAM phones); typing in a large DOCX re-serialises document.xml on each new typing burst.
- Proposed fix: reject entries whose uncompressed size/ratio exceeds limits (e.g. >200 MB total, ratio >100) with a clear message; lazy-load media parts from `ZipFile`; snapshot the DOM only of the part being edited.
- Effort: M     Regression risk: Med     Dependencies: none
- Validation: unit test: zip bomb fixture → `OfficeFormatException("too large")` within 1 s.

### DOC-29 Signature verification: green "valid" means only CMS integrity; no ByteRange sanity or post-signing change analysis
- Type: security
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence: `DigitalSignature.kt:380-401` validity = `signerInfo.verify(...)` over `sig.getSignedContent(bytes)`; `covers` = range ends at file end (`:381`); no check that `range[1]..range[2]` is exactly the `/Contents` hex string, no DocMDP/allowed-change check for later revisions, no chain building here. UI `DigitalSignScreen.kt:236-240` shows Verified icon + "valid" from `signatureValid` alone; trust is a separate line (`CertTrust.kt:24`).
- User impact / scenario: a self-signed or tampered-after-signing document (incremental update that changes page content, "shadow attack") shows a green "Signature valid" headline; only a small "partial coverage" line hints otherwise.
- Proposed fix: compute overall status = integrity ∧ trusted chain ∧ (covers whole doc ∨ later revisions only contain allowed changes per DocMDP / `RevisionDiff`); validate ByteRange layout; show "valid but signer unknown" in amber.
- Effort: M     Regression risk: Low     Dependencies: `core/sign/RevisionDiff.kt`, `TrustPath.kt`
- Validation: unit tests with fixtures: self-signed → amber; content changed after signing → red.

### DOC-30 Visible signature placement ignores CropBox/rotation and is a page stamp, not the field appearance
- Type: usability
- Status: CONFIRMED
- Severity: Low      Priority: P2
- Evidence: `DigitalSignScreen.kt:113-127` corner coordinates from `mediaBox` of the page; drawing via `PdfOps.stampImage/stampText` in user space (`DigitalSignature.kt:262-270`); the PDSignature field gets no visual widget; existing empty signature fields (`PreSignAnalyzer.kt:149`) are never used.
- User impact / scenario: on a /Rotate 90 or cropped page the stamp lands sideways or off-page; Acrobat shows no clickable signature box; pre-placed "Sign here" fields stay empty.
- Proposed fix: same as DOC-01 (widget appearance); compute position in CropBox display space; offer "sign in field X" when unsigned fields exist.
- Effort: M     Regression risk: Med     Dependencies: DOC-01
- Validation: unit test with rotated fixture: widget /Rect inside CropBox at the chosen display corner.

### DOC-31 Watermark and page numbers ignore MediaBox origin, CropBox and /Rotate
- Type: bug
- Status: CONFIRMED
- Severity: Medium      Priority: P2
- Evidence: `PdfOps.kt:304-313` page numbers: `x = (box.width - tw)/2`, `y = 24f` in raw user space of `mediaBox` (origin ignored, rotation ignored); `:285-291` watermark centre at `box.width/2, box.height/2` without `lowerLeftX/Y`.
- User impact / scenario: scanned pages with /Rotate 90 get numbers on the left edge printed sideways; PDFs with MediaBox [0 0 …] offsets or CropBox insets get numbers outside the visible area.
- Proposed fix: compute in CropBox display space and pre-multiply by the page rotation matrix (as `SearchablePdfWriter` does for words).
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test: rotated + offset fixture → number text position inside CropBox at the displayed bottom centre.

### DOC-32 Malicious form JavaScript can crash the app with a single builtin allocation
- Type: security
- Status: PROBABLE
- Severity: Medium      Priority: P2
- Evidence: `core/forms/JsSandbox.kt:46` `scriptError` returns false for `VirtualMachineError` other than StackOverflow, and callers rethrow (`AcroJs.kt:104,133,139`, `FormScripts.kt:102-103`, `XfaJs.kt:49`). The deadline is checked only by the instruction observer every 5000 interpreted instructions (`:21-27`), which does not run inside Java builtins (`String.prototype.repeat`, `Array.prototype.join`).
- User impact / scenario: a PDF with document-level JS `'x'.repeat(1<<30)` crashes SmartDocs when the form engine starts; `new Array(1e9).join()` freezes it.
- Proposed fix: catch `OutOfMemoryError` inside the script runner and mark the engine unavailable (the allocation failed, heap is still usable); wrap builtins `repeat`/`padStart`/`join` with length caps; run scripts on a dedicated thread with a watchdog.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test: script `'x'.repeat(2**30)` → `unavailableReason` set, no crash.

### DOC-33 Merge uses unlimited main memory and cannot open protected inputs; owner-restricted inputs merge into an unrestricted file
- Type: security
- Status: CONFIRMED
- Severity: Low      Priority: P2
- Evidence: `PdfOps.kt:80-90` `PDFMergerUtility().mergeDocuments(null)` (null = default main-memory only) and `addSource(file)` with no password; restricted (owner-only) sources open with empty user password and the merged output carries no encryption.
- User impact / scenario: merging 10 large scans OOMs on 3 GB phones; merging a no-copy PDF removes the restriction.
- Proposed fix: `MemoryUsageSetting.setupMixed(64 MB)` with a temp dir; ask for passwords per source; keep the most restrictive permissions or refuse restricted sources.
- Effort: S     Regression risk: Low     Dependencies: DOC-05
- Validation: unit test: merge restricted fixture → output encrypted or refused.

### DOC-34 OCR screen OCRs every page in range, catches only Exception (in-flux area)
- Type: performance
- Status: CONFIRMED
- Severity: Low      Priority: P3
- Evidence: `ui/ocr/OcrScreen.kt:96-109` loops over all `pages` (plan used only for dedupe), `catch (e: Exception)` (`:121`) — `OutOfMemoryError` from a 3500-px render/Tesseract propagates and crashes; rendered bitmaps not recycled after use.
- User impact / scenario: OCR of a 300-page digital PDF from this screen recognises all 300 pages; OOM on low-RAM devices kills the app.
- Proposed fix: iterate `plan.pages` (as `runOcrToFile` does); catch `Throwable` as `T5Common` does; recycle `bmp`.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test of the page selection; device test for OOM path.

### DOC-35 Encrypted inputs unsupported in several tools; raw English exception text shown to Arabic users
- Type: usability
- Status: CONFIRMED
- Severity: Low      Priority: P3
- Evidence: password `null` passed in `OcrBatchScreen.kt:84,95`, `OptimizeExportScreens.kt:99`, `MergeSplitTools.kt:299`, `ExportTools.kt:79` (`PdfSession.open(ctx, src)`), `PdfOps.split` (`:123` `load(src)`); errors shown as `e.message` (`T5Common.kt:58-60` "OutOfMemory", PdfBox "Cannot decrypt PDF, the password is incorrect", PDFium "password required").
- User impact / scenario: user batch-OCRs a protected PDF and sees an English stack-message; no password prompt.
- Proposed fix: map `PasswordRequiredException`/`InvalidPasswordException`/OOM/IOException to localized strings and offer a password prompt (the batch screen already has `RecipeRunner.opens` pre-check).
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: UI test with protected fixture → Arabic prompt.

### DOC-36 OCR text stored as visual-order presentation forms with /ActualText — copy quality depends on the viewer
- Type: arch-risk
- Status: HYPOTHESIS (needs device test in Acrobat, Chrome, pdf.js, Google Drive)
- Severity: Low      Priority: P3
- Evidence: `ArabicShaper.kt:103-111` `showArabic` writes shaped visual glyph string with BDC/ActualText per word; `SearchablePdfWriter.kt:60,82`, `EditableTextPdfWriter.kt:209,227`. Viewers that ignore /ActualText extract reversed presentation forms; the app's own converter (`PdfContentExtractor` via PDFTextStripper) does not read ActualText and relies on NFKC + geometric reordering (`BidiText.kt:69-120`).
- User impact / scenario: a searchable PDF made in SmartDocs is shared; the recipient copies text in a pdf.js-based viewer and gets reversed letters.
- Proposed fix: for the INVISIBLE layer (render mode 3, glyph shapes irrelevant) consider writing logical base letters per word with a ToUnicode-correct font and per-word positioning; test the major viewers and pick the best combination; document the result.
- Effort: M     Regression risk: Med     Dependencies: device/viewer matrix
- Validation: viewer matrix test: copy a known Arabic sentence from OCR output in 4 viewers.

### DOC-37 E-sign output has no cryptographic seal; signer values are images
- Type: enhancement
- Status: CONFIRMED
- Severity: Low      Priority: P3
- Evidence: `core/esign/EsignComposer.kt:24-64`: values rendered as images, certificate/audit pages appended, `doc.save(tmp)`; integrity only via server SHA (`EsignClient.uploadFinal:78-79`). No `DigitalSignature.sign` call.
- User impact / scenario: the completed agreement can be edited without visible evidence in a PDF reader; signer values are not searchable/accessible.
- Proposed fix: after composing, apply a PAdES document signature/timestamp with the app's seal certificate; add invisible text (ActualText) for rendered values.
- Effort: M     Regression risk: Low     Dependencies: certificate management
- Validation: verify() on composed output returns a valid covering signature.

### DOC-38 Shared files are copied into app storage without size limit
- Type: performance
- Status: CONFIRMED (cleanup of the share dir not traced)
- Severity: Low      Priority: P3
- Evidence: `core/tools5/ShareAndPrint.kt:72-83` copies every `EXTRA_STREAM` fully (`s.copyTo(it)`) with no size or free-space check.
- User impact / scenario: sharing a large video "to PDF" fills storage before the conversion screen even rejects the type.
- Proposed fix: check `OpenableColumns.SIZE` and free space; skip unsupported MIME types before copying.
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: unit test with fake resolver reporting 2 GB → skipped with message.

### DOC-39 HEIC/HEIF images listed as OCR-able but cannot be decoded on API 26–27
- Type: bug
- Status: PROBABLE (BitmapFactory HEIF support starts at API 28)
- Severity: Low      Priority: P4
- Evidence: `OcrRunner.kt:40` includes "heic","heif"; decode via `BitmapFactory` (`ScanImage.kt:32-38`) → null → `IllegalArgumentException("image")` (`OcrRunner.kt:76`).
- User impact / scenario: Android 8 user picks an iPhone photo → error "image".
- Proposed fix: on API < 28 exclude HEIC from pickers or show "format not supported on this Android version".
- Effort: S     Regression risk: Low     Dependencies: none
- Validation: device test on API 27 emulator.

## Quick wins
- DOC-09 (CropBox in SearchablePdfWriter — one line)
- DOC-11 (always keep `eng` with Arabic, lower threshold)
- DOC-14 (per-job temp dir)
- DOC-15 (drop stored passwords after run) + DOC-16 (backup rules)
- DOC-17 (keep `pw` after non-decrypting steps)
- DOC-18 (add Persian/Urdu presentation forms)
- DOC-21 (apply `ArabicTextFix.fix` in PDF→Text / AI context)
- DOC-24 (rethrow CancellationException, surface OCR failure)
- DOC-25 (dedup map fix)
- DOC-31 (watermark/page-number placement)
- DOC-32 (catch OOM in script runner)
- DOC-05/DOC-06 (preserve original protection policy — S each, high security value)

## Needs device testing
- DOC-01/02/29/30: signature validity after visible sign / form fill, in Adobe Acrobat Reader and the app's own verifier.
- DOC-13: WorkManager stop/restart behaviour of long batches.
- DOC-19: multi-line Arabic form field rendering.
- DOC-22: Tesseract cancel latency; memory peak per page (3500 px render + preClean ≈ 4 × 35 MB + Tesseract) on a 2–3 GB RAM phone.
- DOC-36: copy/search of OCR'd Arabic in Acrobat, Chrome, pdf.js/Firefox, Google Drive viewer.
- DOC-12/DOC-04: end-to-end OCR runtime on b.pdf and OCR quality on c.pdf pages after a "garbage" fix.
- PDFium rendering of pages containing huge images (decompression-bomb JPEG/Flate) — native OOM is not catchable (HYPOTHESIS, not filed as a finding).
- DOC-39: HEIC on API 26–27.
