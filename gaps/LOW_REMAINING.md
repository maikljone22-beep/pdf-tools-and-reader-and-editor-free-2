# FINAL_CHECK_2 — Low rows still open (updated after Low batches 1–16, 2026-10-30)

Closed in batches 1–16: paper sizes, custom blank pages, crop page size, text/markdown page setup, HTML header/footer,
virtual printer (version / append / open), PDF/A per file, web capture (levels, site, screen look, info page),
Word comments and note links, mail merge range / e-mail, combine ↔ portfolio, XLSX separators, image export
monochrome, extract images gray / resolution, portfolio (read mode, manual order, print, scanner / clipboard),
binder (TOC line template, open after, show folder), preflight (recipe step, PDF report, grayscale fixup, PDF/A-4,
PDF/X / E / VT claims), page labels from bookmarks, insert preview, resize reset, templates search, e-mail
attachments convert-and-append, per-type video defaults, alt text from the page, text recovery for
xlsx / pptx / html, recipes dividers / log export / report, QR / barcodes, Find Element filters.
PowerPoint handouts with slide frames already existed (pptx_handouts, N-up with frames).

## Still open
- [ ] Create PDF: font embedding options; image codec / downsample options (PARTIAL: presets only)
- [ ] Binder: custom metadata / Office-property columns; folders / sections; binder templates
- [ ] Preflight: profile libraries, search, single checks view; print-production checks (plates, CxF, rich black, ICC / Lab, transparency)
- [ ] PDF/A: outline non-Unicode text instead of rasterising (PARTIAL: separate "fonts to outlines" fixup)
- [ ] Drag page thumbnails from another document (PARTIAL: drop target exists, no drag source)
- [ ] Two editable documents side by side (PARTIAL: tab groups)
- [ ] Video: floating playback window (PARTIAL: icon form opens a pop-up player)
- [ ] OCR: table rules / lines written as vector paths
- [ ] On-page Table Editor (accessibility)

## Needs a server
- [ ] Templates online library
- [ ] Volume licensing / seat management — LicenseClient (owner: LICENSE_URL)

## Not feasible on device
- PNG interlace / JPEG progressive / ICC embedding in image export (Android codecs)
- Formal PDF/A / Matterhorn validation (veraPDF); approximate checks only
- Saving next to the original file's location (scoped storage gives no parent folder); original names are kept
