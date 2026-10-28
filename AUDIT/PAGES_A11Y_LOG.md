# fix/pages-a11y — FINAL_CHECK rows: organize per-page actions, accessibility Fix / Order / tables / PDF/UA, button labels

Strings: `strings_pa.xml` (prefix `pa_`). Test: `A11yFixTest`.

| FINAL_CHECK row | Status | Where |
|---|---|---|
| Organize per-page Redact (acted on the whole document) | DONE | thumbnail menu → confirm → `OrganizeState.redactPage` → `PageOrganize.redactPages` (content, resources, annotations removed; page filled) |
| Organize per-page OCR (only opened the reader) | DONE | `OrganizeState.ocrPage`: the page is OCRed alone and replaced (`PageOps5.replace`, bookmarks / links kept) |
| Accessibility: only 8 rules have a Fix | DONE | every failed / manual rule has "Fix": `AccessFix2` (nested alt, alt without content, alt hiding annotations, formulas, orphan TR / TD / LI / Lbl-LBody, header row, summary, heading levels), `AccessTags.tagAnnotations` (annotations, links, fields, media), `ToUnicodeRepair` (encoding), OCR tool (image only), Unlock (permissions), tags editor (reading order); contrast / scripts / flicker / timed / regular tables open the explanation (human check) |
| Order panel (drag the reading order) | DONE | tags editor "Reading order" view: per page, numbered, long-press drag or up / down (`AccessTags.moveTo`, `readingOrder`); moves stay among siblings |
| Auto Tag tables | DONE | `AccessAutoTag.tables` (aligned rows / columns → Table > TR > TH / TD), table count in the recognition report |
| Formal PDF/UA validation | PARTIAL | preflight profile "PDF/UA-1 (machine checks)" = `Standards.pdfUa` (Matterhorn automatable checkpoints); not a certified validator; human checkpoints stay manual |
| ~51 icon-only buttons without contentDescription | DONE | 16 left after the earlier 41 (pages-convert stage F): labelled (cloud, sync, Bates, bookmarks, e-sign, shapes, crop dialog, optimize, panels) |
