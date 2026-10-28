# fix/pdf39-convert — row log (pdf-39), convert6 rows after fix/convert-audit

| Row (DONE_pdf39 / FINAL_CHECK) | Status | Where | Note |
|---|---|---|---|
| 31 / 87 / 105 Word headings → bookmarks (hub word_pdf) · 32 Excel bookmark per sheet · 33 PowerPoint bookmark per slide | FIXED | core/convert6/OfficeOutline (docxTitles, slideTitles, locate, bookmarks, applyByText, applyPerPage), ToPdf6.convert / xlsx / combine (bookmarks), Opt.BOOKMARKS on word_pdf / excel_pdf / ppt_pdf, Cv6Opts.bookmarks (+ defaults), options sheet switch | Word Title + Heading 1–6 and sheet names are found by their text in the printed pages (in order; spaces, harakat, tatweel and Arabic presentation forms ignored); slides are one per page. Test OfficeOutlineTest |
| 27 custom page size for image sources | FIXED | PageSize6.CUSTOM, PageSetup.customW / customH (+ hub defaults), Images6.place (custom, limits 1–200 in), options sheet width / height in mm | Orientation Auto keeps the size as typed. Test CustomPageSizeTest |
