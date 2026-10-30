# MODULE_convert — on-device PDF → Word / Excel / PowerPoint / HTML / TXT / Images

Everything runs on the phone. There are no new dependencies: the OOXML zips are written by hand with `java.util.zip`.

## Files
New in `core/convert/` (package `com.smartdocs.editor.core.convert`):

| File | Role | Android imports? |
|---|---|---|
| ConvModel.kt | Glyph, Segment, PageImage, PageContent, Word/Line, Paragraph/Table/Image blocks, PageLayout | no |
| BidiText.kt | presentation forms → base letters (NFKC), visual→logical bidi reorder, Arabic-Indic digits, number parsing, XML escape, stray-tanween fix | no |
| LayoutAnalyzer.kt | glyphs → words → lines (diacritics attached), column-gutter split, paragraphs, headings (by font size), alignment, reading order | no |
| TableDetector.kt | ruled tables from rule segments (union-find grid, colspan/rowspan) + borderless tables from column alignment (guard against 2-column text) | no |
| TextRuns.kt | bold/italic/size runs in logical order | no |
| OoxmlZip.kt, XlsxBuilder.kt, PptxTemplates.kt, PptxBuilder.kt, DocxBuilder.kt, HtmlTxtBuilder.kt | streaming writers | no |
| ConvertPipeline.kt | `PageSource` interface, `ConvertOptions`, `TargetFormat`, `ConvertPipeline.run(...)` with progress and cancellation | no |
| PdfContentExtractor.kt | PdfBox `PDFTextStripper` subclass (TextPosition → Glyph) + `PDFGraphicsStreamEngine` subclass (rules, thin filled rects, image placements) | PdfBox only; shared verbatim with the desktop test |
| PdfPlatform.kt | Android seam: `P2`=PointF, `Winding`=Path.FillType, `newPoint`, `encodePdfImage` (raw DCT passthrough or Bitmap→PNG/JPEG) | yes |
| ConvertEngine.kt | Android entry: `pageCount(src, pw)`, `convert(ctx, src, pw, opts, out, onProgress, isCancelled)`. Throws `ConvertEngine.PasswordNeeded`. Writes to a `.part` temp file, then renames it. PDFium (`PdfSession`) renders the picture slides | yes |

UI:
- `ui/tools/ConvertFromPdf.kt` (new): the "From PDF" tab. It has 6 format cards (Word, Excel, PowerPoint, HTML, TXT, Images) in a 2/3/6-column grid that adapts to tablets. Each card opens a ModalBottomSheet of options:
  - tables and images switches
  - Excel: one sheet per page, or all pages in one sheet
  - PowerPoint: "نص قابل للتعديل" or "صورة لكل صفحة"
  - Images: JPG/PNG, 72/150/300 DPI, ZIP or separate files
  - a page range for every format
  
  A page-by-page progress dialog has a cancel button. If the PDF is protected, a password field appears. The `ResultSheet` shows stats, and a warning to run OCR first when no text was found (a scanned PDF).
- `ui/tools/ConvertTool.kt`: the old `FromPdfPanel` and `FromPdfFormat` were removed. The tab now calls `ConvertFromPdfPanel(nav, uri, snackbar)`. The "To PDF" tab is unchanged.
- Strings: `res/values/strings_convert.xml` and `res/values-en/strings_convert.xml` (prefix `cv_`, 47 strings).

Routes and hooks: none needed. The existing `convert` route and `ConvertScreen(nav, uri)` signature are unchanged. `core/pdf/DocxWriter.kt` is no longer used by ConvertTool. It was left in place (not my file), so the lead may delete it if nothing else uses it.

## What each format produces
- **XLSX**: tables become bordered ranges.
  - Merged cells are kept, and the header row is bold and shaded.
  - Numbers become numeric cells. This includes Arabic-Indic digits, `٫`, thousands separators, `%` and `(neg)`. Each column gets one number format: General, `#,##0`, `#,##0.00`, `0%` or `0.00%`. Codes with leading zeros stay text.
  - Other text goes one line per row, with headings in bold.
  - Column widths come from the content. Sheets where Arabic dominates are `rightToLeft`, with the table columns mirrored so the visual order matches the PDF.
- **PPTX**: one slide per page at the page's aspect ratio, clamped to PowerPoint's 1–56 in limits.
  - *Editable*: one text box per paragraph, one `a:p` per line, wrap off. Font size, bold, line pitch and RTL/alignment are kept. Tables become real `a:tbl` tables with gridSpan/rowSpan, and images are placed at their PDF positions.
  - *Picture*: a 2× JPEG render of each page fills the slide.
- **DOCX**: reflowed paragraphs with runs; Heading1–3 styles (they show in the navigation pane); `w:bidi` + `w:rtl` for Arabic; real tables with gridSpan/vMerge, `bidiVisual` for RTL tables and `tblHeader`; inline images; a page break between pages. Page size and margins come from the first page.
- **HTML**: headings, `<p dir>`, tables with colspan/rowspan and `<th>`, base64 images.
- **TXT**: paragraphs, with table rows as tab-separated cells.
- **Images**: PDFium render with a ~100 MP cap per page; output is a ZIP or separate files (`ResultsList`).

## Validation (desktop harness, no gradle)
`/home/claude/convtest/`:
- `gen_pdfs.py` generates sample PDFs with reportlab: Arabic (reshaped) text, ruled and borderless tables, merged cells, two-column text and images. It also makes LibreOffice-exported PDFs of a docx and an RTL xlsx with 120 rows.
- `build.sh` copies the pure files and sed-swaps `com.tom_roush.pdfbox`→`org.apache.pdfbox` in PdfContentExtractor.kt. It fails if a shared file imports `android.*`, then compiles with kotlinc 2.1.0 against desktop PdfBox 2.0.30. `src/DesktopPlatform.kt` is the java.awt twin of PdfPlatform.kt.
- `java -cp … HarnessKt out pdfs/*.pdf` writes every format and a `*.layout.txt` dump.
- `validate.py` opens each file with python-docx, openpyxl and python-pptx and checks that every XML part is well-formed: **ALL OK** on 6 PDFs (including a 33-page real document). Rendering through LibreOffice showed the RTL tables, merges, headings and images correctly.

The Android PdfBox API names were checked with `javap` against the cached `pdfbox-android-2.0.27.0.aar`.

## Limitations / TODO
- Rotated pages (/Rotate ≠ 0) get text only. Rules and images are skipped there because they are in unrotated space.
- Reading order is top→bottom, splitting columns only at gutters wider than 2.5 em. Complex magazine layouts may interleave.
- Tables with only horizontal rules (booktabs style) depend on the borderless detector. A row holding a single chunk ends a borderless table.
- Text colour, underline and fonts are not carried over (Calibri/Arial are used). Vector drawings other than table rules are dropped.
- Scanned PDFs produce no text, and the result sheet tells the user to run OCR first. Wiring OCR before conversion automatically is a possible follow-up.
- Some generators map one glyph to "ا + ً" in ToUnicode. A non-final fathatan is stripped (`BidiText.fixTanween`). Other ToUnicode errors pass through.
- The PPTX `tblPr@rtl` attribute is not used, because LibreOffice ignores it. Tables keep their visual column order and each paragraph is set RTL instead.
