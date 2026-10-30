# MODULE_fid — conversion fidelity, true redaction, paging seal on rotated pages, merging locked PDFs

Gradle was not run. The core code was compiled with kotlinc against `android-36/android.jar` and the cached `pdfbox-android-2.0.27.0` classes.jar:
- every file in `core/convert` except `ConvertEngine.kt` (unchanged)
- `core/pdf/TrueRedactor.kt` and `core/pdf/RedactPlatform.kt`

It was also tested on the desktop JVM with real PdfBox 2.0.30. The UI files were reviewed by hand only, because they need the full app to compile.

## 1. PDF → Word / Excel / PowerPoint / HTML (`core/convert/`)

### Text formatting
- **Colour, font, size, bold and italic carry over.**
  - `PdfContentExtractor` registers the PdfBox colour operators on its text stripper, so each glyph has its fill or stroke colour.
  - Each glyph is also tagged with a common font family. New `FontMap.kt` maps names such as `ABCDEF+Arial-BoldMT` → Arial and `TimesNewRomanPSMT` → Times New Roman. Arabic families (Traditional/Simplified Arabic, Sakkal Majalla, Noto Naskh, Amiri…) keep their names. An unknown font falls back by its descriptor flag: serif → Times New Roman, monospace → Courier New, otherwise Arial.
  - Complex-script runs (`w:cs`, `a:cs`) always get a font that has Arabic glyphs.
- **Underline and strike-through** are detected from rules drawn under or through a word. Table grid lines and full-width separator lines are ignored (new `Decorations.kt`).
- **Fills are detected.**
  - Highlight boxes behind words become run shading.
  - Shaded boxes behind a paragraph become paragraph shading.
  - Table cell backgrounds become real cell fills: `w:shd` in Word, `a:solidFill` in PowerPoint, styled fills in Excel and `background` in HTML.
  - The synthetic blue header shading is now used only when the PDF has no fills of its own.
- **Model changes:**
  - `Glyph` gained `color` and `font`.
  - `Word` gained `color`, `font`, `underline`, `strike` and `bg`.
  - `TableCell.fill`, `ParagraphBlock.shade` and `PageContent.fills` were added.
  - `TextRun` now carries every attribute.
  - XLSX styles are now generated: fonts, fills and xfs are created as needed.

### Reading order (new `ReadingOrder.kt`)
- **Recursive XY-cut.** Each region is cut into columns at vertical whitespace gutters. Columns are read left → right, or right → left on Arabic pages. Otherwise the region is cut into horizontal bands, and neighbouring bands that share a gutter are joined again. Two-column text therefore reads column by column, while full-width titles and footers still split the page.
- **Side columns:** a narrow side column next to a wide main column is read after it.
- **Images under text:** pictures lying under text (backgrounds, watermarks) are taken out of the cut, so they don't glue the columns together.
- **Narrow gutters:** `ReadingOrder.rivers` finds gutters narrower than 2.5 em, i.e. whitespace that lines up over 3–4 or more consecutive lines. `LayoutAnalyzer.splitColumns` cuts lines there.
- **Borderless tables:** they are no longer built from running text in columns (every column holding about 4+ words per row).

### Headers and footers (new `PageFurniture.kt`)
- Up to 8 pages are sampled (`ConvertPipeline.detectFurniture`). A line counts as a running header or footer when:
  - it sits in the top or bottom 10 % of the page,
  - it is not larger than the body text,
  - its text, with digits ignored, repeats on at least half of the sampled pages at the same height.
- **DOCX:** these lines become a real Word header and footer (`header1.xml` / `footer1.xml`).
  - Separate pieces of a line (title … page number) are placed on start / centre / end tab stops.
  - The digit run that changes from page to page becomes a `PAGE` field.
- **HTML, TXT and XLSX:** these lines are dropped.
- **PPTX:** these lines are kept, so each slide still looks like its page.
- New option `ConvertOptions.detectHeaders` (default true).

### Rotated pages
- Everything goes through one affine transform. It maps PDF user space to the displayed page (crop box, /Rotate applied), then turns that so the dominant text direction reads left → right.
- This covers `/Rotate 90/180/270`, landscape content printed sideways on a portrait page, and crop boxes with a non-zero origin.
- Tables, rules, fills and images now come out correctly on these pages; before, they produced text only.
  - Images are turned or mirrored to match the page (`encodePdfImage(img, orient)` on both platforms).
  - Text outside the crop box is dropped.
  - Text running in a minority direction (rotated labels) is kept as one text box.

### Tests
- New `/home/claude/convtest/gen_fid.py` makes three files:
  - `multicol.pdf`: 2 columns with a narrow 1.4 em gutter, header/footer with "Page N of 3", coloured / bold / italic / mono / underlined / struck / highlighted words, a table with coloured header and body cells, and a shaded note box.
  - `arabic_cols.pdf`: RTL two columns with a header/footer.
  - `rotated.pdf`: pages with /Rotate 90 and 270, a sideways table, a crop-box page, and a sideways portrait page.
- `run_fid.sh` runs them through the harness (`build.sh` now includes the new files). `validate.py` reports **ALL OK** on these files and on the old set.
- LibreOffice renders of the DOCX and PPTX were checked: PAGE fields, colours, fills, decorations, column order (Arabic: right column first), and tables and images on rotated pages, with images upright.

## 2. Paging seal (`core/sign/PagingSeal.kt`)
- The strip is now placed on the right edge of the page as displayed, honouring the crop box and /Rotate. It is drawn upright via `TrueRedactor.PageGeom.uprightImageMatrix`.
- Before, it went to the unrotated right edge of the crop box.
- Verified on pages with /Rotate 0, 90, 180-equivalent and 270, and on a crop-box page.

## 3. Redaction
- **New `core/pdf/TrueRedactor.kt`** (pure PdfBox), with the Android image seam in `core/pdf/RedactPlatform.kt`. It actually removes content:
  - **Text:** every glyph under an area is cut out of `Tj`, `TJ`, `'` and `"`, and replaced by a TJ kerning number of the same advance. The text around it does not move.
  - **Form XObjects:** the ones that hold redacted content are rewritten as page-private copies, recursively.
  - **Images:** pixels under an area are painted black in a new XObject. An inline image is turned into an XObject.
  - **Resources:** the page's resources are pruned, so the original images and forms are not written out.
  - **Annotations:** overlapping annotations are removed; for form widgets, `V` and `AP` are cleared up the field chain.
- **Raster fallback:** only pages with vertical fonts, stencil masks, images over 30 MP or a zero font size are rendered to a picture (the old behaviour, which rendered every page with a region). The raster code now uses the crop box.
- **`PdfOps.redact(..., displaySpace: Boolean = false)`.**
  - `false`: rectangles are in PDF user space, i.e. PDFium search/text rects. This is what `DocActions.redactText` passes, unchanged.
  - `true`: rectangles are on the displayed page (crop box origin, rotation applied, as PDFium page sizes and `RedactManualView` use). They are mapped through `TrueRedactor.PageGeom`.
- **New `PdfOps.pageGeometry(src, pw)`.**
- **`ui/security/RedactScreen.kt` (minimal edits):**
  - It loads the page geometry and converts search-hit rectangles to display space, so the manual view previews them in the right place on rotated pages.
  - It calls `redact(..., displaySpace = true)`.
- **Tests (`/home/claude/convtest/redact/`):** `gen.py`, `build.sh`, `RedactHarness.kt` and `SealHarness.kt` cover plain, /Rotate 90, crop-box, form-XObject, Arabic and inline-image pages, plus a TeX `TJ`-kerned document.
  - `pdftotext` shows only the redacted words gone; the neighbouring words keep their exact x positions.
  - `qpdf --qdf | grep SECRET` finds nothing.
  - `pdfimages` lists only the blanked copies.
- **Reader hook:** the reader's "redact" item opens `RedactScreen`, which is already fixed, so no reader change is needed.
  - If the reader ever draws regions itself, it should pass them with `displaySpace = true` when they come from page-size coordinates, and `false` when they are PDFium text rects.

## 4. Merging password-protected PDFs
- **`PdfOps.merge(sources, out, passwords: Map<File, String> = emptyMap())`.** Encrypted inputs, including permission-only ones, are decrypted into temporary copies before merging. The result is unencrypted.
- **New helpers:** `PdfOps.needsPassword(f)` and `PdfOps.checkPassword(f, pw)`.
- **`PdfOps.insertPagesFrom(..., otherPassword: String? = null)`.**
- **`ui/tools/MergeSplitTools.kt` MergeScreen:**
  - It detects locked inputs and opens one `FilePasswordDialog` per locked file, in list order. The new dialog is in `ui/tools/FilePasswordDialog.kt` and can be reused.
  - The password is checked before it is accepted. A wrong password keeps the dialog open with an error. "Remove file" drops the file.
  - Each row shows a locked / unlocked badge.
  - The page count is read with the password.
  - The merge is blocked while any file is still locked.
  - Optional switch "protect the result with a password": merges to a temp file, then runs `PdfOps.protect`.
- **Hook for the pages agent:** `ui/pages/OrganizeState.insertFrom(other, at)` still calls `insertPagesFrom` without `otherPassword`. To support locked files:
  1. Check `PdfOps.needsPassword(other)`.
  2. If it is locked, show `FilePasswordDialog(other, onUnlocked = { pw -> state.insertFrom(other, at, pw) }, onRemove = {})`.
  3. Pass `otherPassword = pw`.

## Strings
`res/values/strings_fid.xml` and `res/values-en/strings_fid.xml` hold 15 strings with the prefix `fd_`.

## Limitations / TODO
- Vector graphics under a redaction box are covered but not deleted. Marked-content `/ActualText` and the structure tree are not scrubbed. Text in annotation appearance streams is removed together with the annotation.
- Text in a minority direction becomes one text box at its position; it does not reflow.
- The header/footer detector needs at least 2 pages. A large repeated title is not treated as a header.
- RedactScreen loads the page geometry asynchronously. A search hit selected within the first instant after opening a file could still be stored in user space (harmless on unrotated pages).
