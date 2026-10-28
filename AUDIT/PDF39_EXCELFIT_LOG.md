# pdf-39 — Excel → PDF fit to page (FINAL_CHECK_2 #9)

| Row | Status | Where |
|---|---|---|
| #9 Excel fit-to-page | DONE | `core/office/ExcelFit.kt` (`SheetPrint`, `ExcelFit.plan`/`bands`/`landscape`), `OfficeSheetHtml.fitPlan` / `printSheet(band, fixedZoom)`, `ToPdf6.xlsx(fit)` |

- **Sheet's own setup (default):** `XlsxReader` reads `<pageSetup orientation fitToWidth fitToHeight scale>` and `<sheetPr><pageSetUpPr fitToPage>` into `SheetModel.print`. Fit to N pages wide × M tall scales to the smaller of the two (0 = automatic); a scale without fit-to-page prints at that %; the sheet's orientation sets the page. A sheet with no setup keeps the old behaviour (all columns on one page width).
- **Options (Excel → PDF):** Sheet's own setup / Actual size / All columns on one page / Whole sheet on one page (`Cv6Opts.excelFit`, `Opt.EXCEL_FIT`, saved in defaults; also used when combining).
- **Columns that do not fit** after scaling are no longer cut off: they print in further column bands, Excel's "down, then over" order; merges crossing a band edge are cut to the band.
- Scale is limited to Excel's 10–400 %, never enlarged by fitting.
- Test: `ExcelFitTest`.
