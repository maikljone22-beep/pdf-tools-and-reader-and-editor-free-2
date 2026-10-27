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
