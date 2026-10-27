# Nitro gaps — sections 10, 12, 13, 14, 15 (branch feature/nitro-tools, session pdf-39)

Source: `cmp_09-15.md` (Nitro PDF Pro vs SmartDocs). Every PARTIAL / MISSING / CODE-ONLY row of the five sections,
checked again in the code before building. Strings: `res/values{,-en}/strings_nitro.xml` (prefix `nt_`).

## 10 Redaction, whiteout, sanitize

| Row | Result | Where |
|---|---|---|
| Redaction code sets and Codes Editor (new set / rename / remove) | done | `core/redact/RedactPrefs.kt` (`CodeSet`, `CodeSets`, `codeSets/activeSet`; the old single list migrates into the default set); `ui/security/RedactStyleSheet.kt` `CodesEditor` |
| Overlay text: font | done | `RedactStyle.font` (an `E5Font`, "" = automatic); `RedactApplier.fontAndText`; stored as `/SDFont` and in profiles |
| Overlay text: case | done | `RedactStyle.textCase` + `applyCase` (as typed / UPPER / lower / Title; Latin only); `/OverlayText` is written with the case applied so other viewers show it, `/SDCase` keeps the setting |
| Sanitize: embedded search indexes | done | new item `SanitizeItem.SEARCH_INDEX` → `core/redact/SanitizeExtra.kt` `SearchIndex` (PieceInfo search/index entries, embedded `*.pdx`/`*.idx`/`*.ix?` files; other data stays) |
| Sanitize: document structure (tags) | done | new item `SanitizeItem.STRUCTURE` → `Structure` (StructTreeRoot, MarkInfo, StructParent(s)); opt-in (not pre-checked, not in DEFAULTS) with an accessibility note |
| Notification of pending redaction overlays | already present | the reader shows `RedactMarksBanner` (`ui/search/ReaderSearchExtras.kt:120`, `ReaderScreen.kt:300`) whenever the file holds /Redact marks, with "apply" |

Test: `RedactNitroTest`.

## 12 Headers & footers, watermarks / backgrounds, Bates

| Row | Result | Where |
|---|---|---|
| Watermark type: PDF page | done | `DecorSource.PDF` (`pdfPath`, `pdfPage`) → `DecorExtras.importPage` (LayerUtility form XObject) in `WatermarkOps.draw`; picker + page field in `DecorScreen` |
| Watermark profiles (new, rename, copy, delete, import/export) | done | `core/edit5/DecorExtras.kt` `E5ProfileList` / `E5Profiles`; `ui/edit5/E5ProfilesUi.kt` `E5ProfileBar` in the watermark/background/security and header/footer screens. Export/import is a SmartDocs JSON file (`.nxp` is Nitro's private format) |
| Text: font style (italic) | done | `E5TextStyle.italic` (12° slant in `E5Line.draw`); switch in watermark and header/footer |
| Text: underline in the watermark UI | done | switch in `DecorScreen` (the model already had it; now stored in the watermark JSON too) |
| Text: line spacing, word wrap | done | `DecorSpec.lineSpacing` (single / 1.5 / double), `wrapWidth` (`DecorExtras.wrap`) |
| Text: outline font (border / fill colour) | done | `E5TextStyle.outline` + `hollow` (text render mode fill+stroke / stroke) |
| Border: colour, weight, dashed | done | `DecorSpec.border`, `borderWidth`, `borderDashed` (`WatermarkOps.border`) |
| Action on click: go to page / remote document / web link | done | `DecorSpec.click` → `DecorExtras.addLink` (link annotation over the turned box, tagged /SDE5 so update/remove take it away). Single placements only (not tiled) |
| Page range: from page X every N pages | done | `PageSet.every`, `DecorSpec/HFSpec.everyStart/everyN`, `EveryNPages` UI |
| Header & Footer: image or PDF content | done | `HFSpec.imagePath/imagePage/imageSlot/imageHeight` → `HFPicture` |
| Header & Footer: user profiles | done | `E5ProfileBar("hf")` |
| Token: Batch Page No. / Batch Total pages | done | `{batchpage}` `{batchpages}`; header/footer screen takes more files (`HeaderFooterOps.applyBatch`) |
| Token: Bates No. in a plain header/footer (bug: expanded to empty) | fixed | `HeaderFooterOps.draw` numbers the target pages with `HFSpec.bates` when no Bates tool feeds it; format fields appear when a cell uses `{bates}` |
| Token: Bates Total pages | done | `{batestotal}` (`Bates.total`) |
| Token: File Path | done | `{path}` |
| Token: Subject / Keywords | done | `{subject}` `{keywords}` |
| Option: ignore page rotation | done | `DecorExtras.frameOf(page, ignoreRotation)` for watermark and header/footer |
| Bates: colour | done | `BatesLayout.style.color` + colour chips |
| Bates: rotation / opacity / place in background | done | `BatesLayout.rotation/opacity/behind` (shared `HFSpec` drawing; rotation per cell around its centre) |
| Bates: advanced margins and units | done | top/bottom/left/right (`HFSpec.marginRight`), `E5Unit` pt/mm/cm/in (`MarginsEditor`, also in header/footer) |
| Bates: increase page size to fit | done | `growPage` → `Grown.grow` (original boxes kept in /SDE5Grown; re-runs do not grow twice; removal restores) |
| Bates: increment on every page vs numbered pages only / page range per file | done | `BatesLayout.range/subset` + `BatesSpec.countSkipped` (`Bates.plan`) |
| Bates: one number per document / restart per document | done | `BatesSpec.mode` (`BatesMode.PER_DOCUMENT`, `RESTART`) |
| Bates: output folder | done | "Save results to a folder" (SAF tree, `Cv6Save.toTree`) |
| Remove Bates Numbering (CODE-ONLY) | done | `HeaderFooterOps.removeBates` (ours and Acrobat /BatesN, restores grown pages) + button in the Bates screen. The header/footer "remove" no longer strips Acrobat Bates numbers (verifier note: it stripped Acrobat's but not ours) |

Test: `NitroDecorTest`.

## 14 Optimize / compress

| Row | Result | Where |
|---|---|---|
| Separate settings for colour / grayscale / monochrome images | done | `OptimizeOptions.separateClasses` + `grayDpi/grayQuality/grayMode`, `monoDpi/monoMode` (`Optimizer.processImage`, `mono`) |
| Compression: Automatic / JPEG / ZIP | done | `ImgCompression` per class (AUTO = the earlier behaviour; ZIP = lossless Flate re-encode); a result is only kept when smaller |
| Compression: CCITT G3 / G4 | done | new `core/tools5/Ccitt.kt` encoder (T.6 G4 `/K -1`, T.4 1-D G3 with EOLs `/K 0`), 1-bit downsampling; `MonoCompression` |
| Remove: embedded fonts (unembed) | done | `OptimizeExtras.unembedFonts` — standard-14 only, or all simple fonts (CID/Type0 fonts keep their programs: Arabic/CJK cannot be shown without them) |
| Remove: annotations / form widgets / links / bookmarks in Optimize | done | `removeAnnotations/removeForms/removeLinks/removeBookmarks` → `Sanitizer.clean` (same rules as Sanitize; forms are flattened) |
| Remove: named destinations | done | `OptimizeExtras.removeNamedDestinations` — links, actions, OpenAction and bookmarks that use a name get the explicit destination first |
| Troubleshooting tips | done | `OptimizeTip` / `OptimizeExtras.tips` from the audit of the result, shown in a card |
| Encrypted input in Optimize (bug: null password) | fixed | password prompt in `OptimizeScreen`; the result stays protected with the same password (`keepProtection`, switchable). `PdfOps.compress` keeps its own re-protect (`keepProtection = false` there) |

Test: `NitroOptimizeTest`.

## 15 Compare (+ Foxit §13 Compare PDFs & Word Count)

| Row | Result | Where |
|---|---|---|
| Markers: inserted / deleted / replaced image | done | `CompareCore.images` (images per page with position + content hash via `PageInspector`; same place + other picture = replaced, same picture elsewhere = moved) |
| Markers: inserted / deleted page | done | `PageMatch.align` → `PageChange` INSERTED / DELETED, full-page marks |
| Markers: inserted / deleted annotation | done | `CompareCore.annotations` (subtype + contents + place; changed contents = replaced) |
| Markers: page rotation, crop box, page position | done | `PageChange` ROTATED / CROPPED (size and offset) / MOVED (pages out of order: `PageMatch.movedPairs`) |
| Option: include text and annotations (toggle) | done | `CompareOptions.text/images/annotations/formatting/pages/tables` |
| Option: mark colours (Foxit: colour, opacity, line thickness, set as default) | done | `CompareStyle` + `StyleDialog` in `Compare2Screen`; used by the views, the report, the overlay and the annotated copy |
| Option: auto match pages (reordered pages) | done | `CompareOptions.autoMatch` → `PageMatch.align` (alignment on word-set similarity, then moved pages); the word diff runs over pages in matched order |
| Visual Compare: output document, auto match | done | `CompareOutput.visualDocument` (pixel overlay per matched page pair, inserted / deleted pages shown alone) |
| Page ranges for each document | done | `CompareOptions.oldRange/newRange` |
| Encrypted inputs (bug: null passwords) | fixed | password prompts per file; passwords passed to compare, rendering, report, overlay and outputs |
| Foxit: Compare Table | done | `TableDetect` (rows from word positions, cells at wide gaps) + `TableDetect.diff` (rows aligned, changed cells); "Tables" tab and report section |
| Foxit: filter results by Text / Images / Annotations / Formatting / Everything | done | `ChangeKind` filter chips (also Pages, Tables); formatting = font or size change of the same word (`FormatChange`) |
| Foxit: changes in the Comments panel / save result | done | `CompareOutput.annotatedCopy`: a copy of the new document with every change as a highlight annotation with a note |
| Foxit: Option "Compare Text Only" | done | the kind switches (text only = all others off) |
| Foxit: Open from ECM | not feasible | no ECM connectors in the app; SAF already reaches cloud providers |
| Foxit: Word Count (words, characters with/without spaces, pages, lines, Asian characters; document or selection) | done | `core/tools5/WordCount.kt`; compare screen action (each document, page range) and a "Word count" chip in the reader selection menu |

Test: `NitroCompareTest`.

## Foxit §20 PDF Optimizer / Reduce File Size

| Row | Result | Where |
|---|---|---|
| PDF version compatibility | done | `OptimizeOptions.pdfVersion` (retain, 1.4 … 2.0) |
| Estimate compression size | done | `OptimizePresets.estimate` (optimizes up to 6 sample pages, scales the saving) |
| Reduce file size, multiple files (files, folder, reorder, remove) | done | `OptimizeBatchSection` (files, a whole folder via SAF, move up, remove) |
| Output options (folder, naming, overwrite) | done | name suffix, output folder (SAF tree), "replace files with the same name" (`saveToTree`) |
| Predefined settings: save / delete user presets | done | `OptimizePresets.toJson/fromJson` + the shared profile bar (kind "opt": save, rename, copy, delete, import, export) |
| Separate settings per image class | done (Nitro §14) | |
| Downsample: Off / Average / Subsampling / Bicubic + "for images above" | done | `core/tools5/Resample.kt` (box, nearest, separable bicubic with widened kernel), `downsample`, `aboveDpi` |
| Compression ZIP / JPEG | done (Nitro §14) | |
| Compression: JPEG 2000 | not feasible | no JPEG 2000 (JPX) encoder exists for Android / PdfBox; writing one is out of scope |
| Monochrome: JBIG2 lossless, CCITT G3/G4, Run Length | done | `core/tools5/Jbig2.kt` (generic region, MQ coder; decodes with jbig2-imageio in the test), `RunLength`, `Ccitt` |
| Monochrome: JBIG2 lossy | not feasible | lossy JBIG2 needs symbol (text-region) coding with pattern matching; only the lossless generic region is written |
| High compression (MRC) / Optimize scanned PDF | done | `OptimizeScan.mrc`: paper whitened, ink as a CCITT G4 stencil in the ink colour over a 1/3-resolution JPEG background, placed as a form XObject so OCR text and other objects stay |
| Unembed fonts | done (Nitro §14) | |
| Subset all embedded fonts | done | `FontSubset` / `TtfStrip`: unused TrueType glyph outlines emptied, glyph ids kept (safe for Arabic / CID); fonts of annotations, fields, patterns and Type 3 glyphs left whole |
| Transparency flattening presets | done | `flattenTransparencyDpi` (low 150 / medium 300 / high 600): pages with transparency are rendered at that resolution (`RedactApplier.rasterize`) |
| Discard form submission / import / reset actions | done | `OptimizeCleanup.removeActions` (incl. /Next chains and /AA) |
| Flatten form fields | done (Nitro §14 "remove forms" flattens) | |
| Discard alternate images | done | `OptimizeCleanup.alternates` |
| Discard document tags | done | via `Structure` (Sanitize item) |
| Convert smooth lines to curves | done | `LineFit` / `ContentTidy` (smooth runs of ≥ 6 line segments → Catmull-Rom Béziers within 0.5 units) |
| Detect and merge image fragments | done | `OptimizeScan.mergeFragments` (strips stacked edge to edge become one image) |
| Discard embedded print settings | done | `OptimizeCleanup.printSettings` |
| Discard embedded search index | done | via `SearchIndex` |
| Discard bookmarks / comments / forms / multimedia | done | Nitro §14 switches + `CleanupOptions.multimedia` (Screen, Movie, Sound, RichMedia, 3D and their actions) |
| Discard external cross references (keep internal links) | done | GoToR / GoToE / Launch / URI actions removed; links left empty are removed |
| Discard all object data (tag attributes) | done | `OptimizeCleanup.objectData` (/A, /C, /ClassMap) |
| Discard hidden layer content and flatten visible layers | done | `OptimizeCleanup.flattenLayers` |
| Replace LZW with Flate | done | `OptimizeCleanup.lzwToFlate` |
| Remove invalid bookmarks / links | done | `OptimizeCleanup.invalidLinks` (targets on missing pages or unknown names) |
| Discard unreferenced named destinations | done | `OptimizeCleanup.unusedDests` + `NameTrees.retain` |
| Optimize page content | done | `ContentTidy.rewrite` (one compact Flate stream) |
| Fast Web View | not feasible | PdfBox 2 cannot linearize (unchanged) |

Test: `FoxitOptimizeTest` (adds `org.apache.pdfbox:jbig2-imageio` as a test-only dependency to decode the JBIG2 output).

## Foxit §29 Redaction, Smart Redact, hidden data

| Row | Result | Where |
|---|---|---|
| RHI: overlapping objects (page converted to image) | done | Sanitize item `OVERLAPPING` (opt-in): pages where text or images are covered by a later opaque image / area (`Overlap.has`) are rendered as images (`Sanitizer.cleanFile` + `RedactApplier.rasterize`) |
| RHI / Sanitize: multimedia | done | Sanitize item `MULTIMEDIA` (`Multimedia`: Screen, Movie, Sound, RichMedia, 3D annotations and media actions) |
| Mark: double-click an image to mark the whole image | done | double tap in the manual view → `RedactState.markImageAt` (image boxes from `PageInspector`) |
| Marks: move and resize before applying | done | `RedactManualView`: drag inside a mark moves it, drag a corner handle resizes it (one-rectangle marks); locked marks do not move |
| Properties: overlay font | done (Nitro §10) | |
| Properties: code sets, edit codes, import / export | done | named sets (Nitro §10) + import / export of code sets as JSON (`RedactPrefs.exportCodeSets/importCodeSets`) |
| Properties: General tab (author, subject) | done | `RedactMark.author/subject` → /T and /Subj; `MarkPropertiesDialog` |
| Properties: Review History | done | `RedactMark.reviews` written as hidden review-state replies (/IRT, /StateModel Review, /State) and read back; "set status" in the properties |
| Properties: Locked; set current properties as default | done | `RedactMark.locked` (/F Locked; cannot be moved, resized, removed or cleared) + "Set as default" in the appearance dialog |
| Apply a single selected mark | done | "Apply this mark only": `RedactApplier.Options.keepPending` writes the other active marks back as /Redact |
| Redaction file name: prefix, suffix | done | prefix / suffix fields in the apply dialog (`RedactPrefs.outPrefix/outSuffix`) |
| Search & Redact: import / export word list (.txt) | done | import (appends, de-duplicated) and export of the word list |
| Search & Redact: country / region | done | `core/search/RegionPatterns.kt`: US SSN and phone, UK NI number, phone, postcode, driving licence, Canada SIN (Luhn), India Aadhaar (Verhoeff) and PAN, Spain DNI/NIE (check letter), Brazil CPF, China resident ID (MOD 11-2), France and Germany phones — in their own chip group. US driving licences: not added (every state has its own format; no reliable pattern) |
| Profiles: overlay text per word / phrase group | done | word list lines "phrase \| overlay" (`PhraseList`), stored in the profile (`RedactProfile.overlays`) and used when marking hits |
| Profiles: import / export | done | `RedactPrefs.exportProfiles/importProfiles` in the profile menu |
| Protect Redactions (AIP) | not feasible | needs Microsoft AIP / RMS (unchanged N/A-ANDROID) |
| Smart Redact: AI detection of person names, organisations, roles | done | `core/ai5/PiiEntities.kt`: AI path through the app's AI client (page text → JSON entities → located in the text, only real text is marked), offline fallback patterns (Arabic / English titles before names, organisation words, job-title list); new `PiiKind` PERSON / ORG / ROLE in the PII scan with a "use AI" switch |

Test: `FoxitRedactTest`.

## Foxit §23b Page marks and page numbers (§23a page operations go to feature/nitro-pages-convert)

| Row | Result | Where |
|---|---|---|
| Header & Footer templates: save, delete, import / export | done | profile bar (Nitro §12). "Administrator templates" — not feasible: no managed-configuration channel in the app; built-in presets stay |
| Header & Footer batch add (files, folders, open files, output options) | done | header/footer screen: `BatchSources` (files, folder, reader tabs, sort) + `BatchOutputOptions` (suffix, output folder, replace same name) |
| Header & Footer / Watermark / Background: batch Remove All with a log file | done | "Remove from all files" → `runBatchWithLog` (one OK / FAILED line per file, log saved and copied with the outputs) |
| Background / Watermark from a PDF page | done (Nitro §12) | |
| Background / Watermark templates | done (Nitro §12 profiles) | |
| Background / Watermark batch add | done | decor screen batch section + "Apply to all files" (new engine, all options, with a log) |
| Bates: add folders / open files / sort by name, location, size, date | done | `BatchSources` in the Bates screen |
| Bates: output options (folder, keep / add / replace name with the Bates range, overwrite, log file) | done | `BatesNaming` + `Bates.outputName`, output folder with "replace same name", `Bates.log` |
| Bates: digits 3–15 | done | digits up to 15 |
| Bates: increment (negative counts down) | done | `BatesSpec.increment` in `Bates.plan` |
| Bates: rotate angle, {bates} in a header/footer, Remove All | done (Nitro §12) | |
| Page labels shown in the reader | moved | part of feature/nitro-pages-convert (old plan A5), as agreed |

Test: `FoxitBatesTest`.
