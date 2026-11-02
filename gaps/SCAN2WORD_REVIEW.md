# Photo → Word review (2026-11-02): text specialist + layout specialist

Test: one bold Yemeni school-book page (Samsung scan) → .docx → LibreOffice render.
Status marks: [x] fixed in this round, [ ] open.

## Text (OCR)
Baseline body CER 0.151; with ara+ArabicFT and the comma fix, CER 0.099 (measured by the reviewer).
- [x] Run Tesseract with ara+ArabicFT together for Word (app: ScanStudioScreen Word branch; `effectiveLangs` keeps both when asked). Brings back the section title, «س٣», table words and a third fewer wrong letters.
- [x] A lone «٠» between words is the bold Arabic comma.
- [x] Junk filtering:
  - drop tokens mixing Latin and Arabic-Indic digits, or with one letter four times running;
  - drop Latin numbers below conf 90 on pages numbered in Arabic-Indic digits;
  - drop a whole line when its median conf (junk included) is below 50, unless it has a surely-read word;
  - keep the «س١ / ج٢» labels.
- [x] Row merging by line centres (two printed lines were interleaved: «د - مطح…», «أقسامهضبة…»).
- [ ] Second pass on ink bands with no words (PSM 7, padded crop).
- [ ] Table cells: 20 px padding and ×1.5 scale (cell CER 0.177 → 0.124 measured).
- [ ] Underlined / italic words stay weak (model limit); a model trained on bold/italic print or cloud OCR is needed.

## Layout
- [x] Bold by absolute stroke width against the em: a page set in bold is bold throughout; headings need a thicker stroke than the page.
- [x] Printed line pitch (exact line spacing), wider printed gaps become space before.
- [x] Black 1 pt table rules and narrow cell margins on scanned pages.
- [ ] Header / footer boxes:
  - frame a band ruled above and below, even with no side rules;
  - keep its parts (right / centre / left) with tab stops;
  - keep the page number and ornaments.
- [ ] Gaps inside a line (answers «١- … ٢- … ٣-») as tabs.
- [ ] Underlines on words from the short rules under them.
- [ ] Bullets ➢ / •.
- [ ] Banner graphic as a picture, or as a bordered title.
- [ ] Justified paragraphs, by joining full-width lines.
- [ ] The page can still run a few lines over when the substitute font is wider than the book's.
