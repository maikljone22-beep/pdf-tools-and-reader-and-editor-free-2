# Verify B: adversarial re-check of MISSING / CODE-ONLY rows (cmp_09-15.md, cmp_16-24.md)

Read-only. Paths are relative to `app/src/main/java/com/smartdocs/editor`. Each row was re-searched with new terms (English identifiers, PDF keys, PdfBox classes, Arabic strings), and callers and UI entry points were checked.
Rows checked: 124 (61 from cmp_09-15, including 3 N/A rows that claim a MISSING Android equivalent, and 63 from cmp_16-24).
Results: 119 confirmed (115 CONFIRMED-MISSING and 4 CONFIRMED-CODE-ONLY) and 5 corrected (1 ACTUALLY-HAVE, 4 ACTUALLY-PARTIAL).

## Table

| Source | Nitro feature | Original | Verified | Evidence / extra searches |
|---|---|---|---|---|
| 09-15 | Encryption 40-bit ARC4 | MISSING | CONFIRMED-MISSING | `EncAlgo` = AES_256/AES_128/RC4_128 (`core/redact/ProtectAdvanced.kt:11`). Nuance: when an existing 40-bit file is edited and re-saved, `PdfOps.kt:71` keeps its 40-bit key length; there is no way to create 40-bit |
| 09-15 | Security Profiles | MISSING | CONFIRMED-MISSING | Searched `ProtectPreset`, `savedProtect`. The recipe PROTECT step (`core/tools5/RecipeRunner.kt:84-86`) holds only one password (user = owner, no permissions), so it is not a profile |
| 09-15 | Trusted Contacts: request cert by e-mail | MISSING | CONFIRMED-MISSING | No Intent or e-mail code in `ui/signature/TrustedCertsScreen.kt`; Arabic certificate strings have no request string |
| 09-15 | Trusted Contacts: export | MISSING | CONFIRMED-MISSING | TrustedCertsScreen has only import (:58), view and delete (:87); no share/CreateDocument |
| 09-15 | Digital ID storage: Android KeyChain (N/A row) | MISSING equiv. | CONFIRMED-MISSING | `KeyChain`/`choosePrivateKeyAlias`: 0 hits. The only `AndroidKeyStore` use is `core/connected/ConnStore.kt:66` (AES key for connected docs) |
| 09-15 | Digital ID export | MISSING | CONFIRMED-MISSING | `ownKeystoreFile` = `filesDir/own_certificate.p12` (`core/pdf/DigitalSignature.kt:107`). Not included in the AppData bundle (`core/share5/AppDataBundle.kt:23-47`: signatures, stamps, profile, tags, favourites) |
| 09-15 | Manage Internet Access | MISSING | CONFIRMED-MISSING | `trustedSite`, `allowInternet`, `confirmUrl`, `linkConfirm`: 0 hits |
| 09-15 | Disable JavaScript preference | MISSING | CONFIRMED-MISSING | All DataStore keys are listed (ai_*, author, autosave, continuous, g6_*, hl_*, keep_zoom, lang, night, ocr_langs, pressure, restore, screen_on, theme, tts_*, volume_flip…); there is no JS key |
| 09-15 | Redaction overlay font | MISSING | CONFIRMED-MISSING | `RedactStyle` has fill, outline, overlay, textColor, fontSize, repeat, align (`core/redact/RedactMarks.kt:27-35`) |
| 09-15 | Redaction overlay case | MISSING | CONFIRMED-MISSING | Same data class; `uppercase` has 0 hits in core/redact and ui/security |
| 09-15 | Signature contact info | CODE-ONLY | CONFIRMED-CODE-ONLY | The only `SignOptions(` call is `ui/signature/DigitalSignScreen.kt:108` (name, location, reason); `SignAdvancedOptions.options()` :52 does not set contactInfo |
| 09-15 | Named signature profiles | MISSING | CONFIRMED-MISSING | One `psf_sign` prefs set (`SigAppearanceDesigner.kt:38`, `SignAdvancedOptions.kt:80`) |
| 09-15 | Appearance max font size | MISSING | CONFIRMED-MISSING | `SigAppearanceSpec` has no font field (`core/sign/SigAppearanceSpec.kt:7-16`) |
| 09-15 | QuickSign black & white | MISSING | CONFIRMED-MISSING | `removeWhiteBackground` (`ui/signature/SignatureBitmaps.kt:113`) only makes the background transparent and keeps the colours; no ColorMatrix in ui/signature |
| 09-15 | TSA login | MISSING | CONFIRMED-MISSING | `TsaClient.kt:76` sets only the `Accept` header; no auth |
| 09-15 | Attestable TSA | MISSING | CONFIRMED-MISSING | — |
| 09-15 | Revision X of Y / View Signed Version | MISSING | CONFIRMED-MISSING | `RevisionSnapshot.changes` diffs against the signed ByteRange but never writes it out as a file. Nuance: the revision count is shown in the pre-sign change list (`ui/signature/SignChanges.kt:66`) |
| 09-15 | Counter-signed state | MISSING | CONFIRMED-MISSING | `counter.?sign`: 0 hits in sign code |
| 09-15 | Hide valid-signature icons | MISSING | CONFIRMED-MISSING | No signature icons are drawn on pages |
| 09-15 | Signature field actions (sign into /Sig field…) | MISSING | CONFIRMED-MISSING | `DigitalSignature.sign` always calls `d.addSignature(sig, signer, sigOpts)` with no field (:300). The visible appearance is only a stamped image or text (:262-272). `WKind.SIGNATURE -> {}` (`ui/forms5/FormFillState.kt:299`). Only a signed/unsigned label appears in the Forms list (`ui/forms/FormsScreen.kt:291`) |
| 09-15 | Bundled CA roots (Notarius) | MISSING | CONFIRMED-MISSING | Trust comes only from `TrustedCerts.certificates` (`core/sign/CertInspector.kt:77`) |
| 09-15 | QuickSign from PDF page | MISSING | CONFIRMED-MISSING | ImageTab uses only `rememberImagePicker`/TakePicture (`ui/signature/SignatureImageTab.kt:46-49`) |
| 09-15 | Password-protect signature | MISSING | CONFIRMED-MISSING | `BiometricPrompt`/`biometric`: 0 hits |
| 09-15 | Recently used signatures | MISSING | CONFIRMED-MISSING | `lastUsed`/`usedAt`: 0 hits |
| 09-15 | System CA / AATL anchors (N/A row) | MISSING equiv. | CONFIRMED-MISSING | `TrustManagerFactory`/`AndroidCAStore`: 0 hits |
| 09-15 | Wizard: ID from system store (N/A row) | MISSING equiv. | CONFIRMED-MISSING | Same as KeyChain |
| 09-15 | Watermark from PDF page | MISSING | CONFIRMED-MISSING | `enum DecorSource { TEXT, IMAGE, COLOR }` (`core/edit5/WatermarkOps.kt:30`); LayerUtility is used only in Impose, PrintPrep and convert6 |
| 09-15 | Watermark profiles / .nxp import-export | MISSING | **ACTUALLY-PARTIAL** (weak) | A recipe with a WATERMARK step is named, saved, editable and exported/imported as `.sdrecipe` (`core/tools5/Recipes.kt:26`; `ui/tools5/RecipesScreen.kt:54,67-97`). Limits: text, size and opacity only, through the old `PdfOps.watermark` (`RecipeRunner.kt:56-58`), not the edit5 DecorSpec |
| 09-15 | Line spacing / word wrap | MISSING | CONFIRMED-MISSING | `lineSpacing`/`leading`/`lineHeight`: 0 hits in edit5 |
| 09-15 | Outline font | MISSING | CONFIRMED-MISSING | No RenderingMode/stroke text; the only `stroke()` in `E5Text.kt:128` draws the underline |
| 09-15 | Watermark border | MISSING | CONFIRMED-MISSING | DecorSpec has no border fields (:38-66) |
| 09-15 | Watermark click action | MISSING | CONFIRMED-MISSING | DecorSpec has no action field |
| 09-15 | Every-N-pages range | MISSING | CONFIRMED-MISSING | `PageSubset { ALL, ODD, EVEN, PORTRAIT, LANDSCAPE }` (`E5Format.kt:159`) |
| 09-15 | Header/footer image or PDF | MISSING | CONFIRMED-MISSING | HFSpec cells are strings only (`HeaderFooterOps.kt:19-30`) |
| 09-15 | Tokens: Batch page/total | MISSING | CONFIRMED-MISSING | The `Tokens.expand` switch (`E5Format.kt:131-147`) has: page/n, pages/total, date, time, datetime, hijri, file, title, author, user, bates, label |
| 09-15 | Token: Bates total | MISSING | CONFIRMED-MISSING | Same switch |
| 09-15 | Token: File path | MISSING | CONFIRMED-MISSING | Same switch |
| 09-15 | Tokens: Subject / Keywords | MISSING | CONFIRMED-MISSING | Same switch; TokenContext has no subject (:104-117) |
| 09-15 | Ignore page rotation option | MISSING | CONFIRMED-MISSING | No field in HFSpec or DecorSpec |
| 09-15 | Bates colour | MISSING | CONFIRMED-MISSING | `E5TextStyle(size = 9f, color = 0x212121)` with no colour control (`ui/edit5/BatesScreen.kt:41,77-84`) |
| 09-15 | Bates rotation / opacity / background | MISSING | CONFIRMED-MISSING | Same screen; the HFSpec built in `HeaderFooterOps.bates` (:176-179) has no such fields |
| 09-15 | Bates: increase page size | MISSING | CONFIRMED-MISSING | `bates()` does not set `shrink` (header/footer has shrink, Bates does not) |
| 09-15 | Bates only on numbered pages / per-file range | MISSING | CONFIRMED-MISSING | `HeaderFooterOps.kt:165-186`: every page of every file |
| 09-15 | Bates one number per doc / restart | MISSING | CONFIRMED-MISSING | `Bates.ranges(bates, counts)` is continuous |
| 09-15 | Remove Bates numbering | CODE-ONLY | CONFIRMED-CODE-ONLY | `grep "bates = true"`: 0 callers. The only `remove` call is `HeaderFooterScreen.kt:117` (`others = true`), which strips Acrobat `pdf:BatesN` but not our own `KIND_BATES` |
| 09-15 | Zone OCR | MISSING | CONFIRMED-MISSING | All `.recognize(` callers OCR whole pages or images (OcrInPlace:85, OcrScreen:108, OcrRunner:58/111, ScanBuilder:130, Cv6Engine2:211); no crop or region |
| 09-15 | Correct image skew | MISSING | CONFIRMED-MISSING | `deskew`/`skew`/`straighten`/`hough`: 0 hits |
| 09-15 | Detect text orientation | MISSING | CONFIRMED-MISSING | Only `PSM_AUTO` (`core/ocr/OcrEngine.kt:91`); no OSD |
| 09-15 | OCR output downsample | MISSING | CONFIRMED-MISSING | `downsample`/`targetDpi`: 0 hits in OCR code |
| 09-15 | OCR output compression | MISSING | CONFIRMED-MISSING | — |
| 09-15 | ~100 other OCR languages | MISSING | CONFIRMED-MISSING | `PINNED` lists 15 languages (`OcrEngine.kt:151-166`); the manual pickers offer 8 (`ui/ocr/OcrInPlace.kt:21-24`, `ui/tools5/OcrBatchScreen.kt:28`) or 3 (OcrScreen, Settings) |
| 09-15 | OCR-A/B, CMC7, numeric, handprint | MISSING | CONFIRMED-MISSING | `whitelist`/`tessedit_char`: 0 hits |
| 09-15 | CCITT G3/G4 (Optimize) | MISSING | CONFIRMED-MISSING | Not in Optimizer. Nuance: CCITTFactory embeds TIFF G3/G4 pages unchanged in TIFF→PDF (`core/convert6/ImageIo6.kt:113`) |
| 09-15 | Unembed fonts | MISSING | CONFIRMED-MISSING | `FontFile`/`unembed`: 0 hits |
| 09-15 | Remove named destinations | MISSING | CONFIRMED-MISSING | `COSName.DESTS` is read only in `PageOps5.kt:107,113` (repoint); Sanitizer and Optimizer leave it alone |
| 09-15 | Compare: image markers | MISSING | CONFIRMED-MISSING | CompareEngine works on words only (`core/tools5/CompareEngine.kt:95-112`) |
| 09-15 | Compare: page insert/delete markers | MISSING | CONFIRMED-MISSING | The page diff in `RevisionDiff` is used only by `RevisionSnapshot`/`SignChanges` |
| 09-15 | Compare: annotation markers | MISSING | CONFIRMED-MISSING | Same |
| 09-15 | Compare: rotation/crop/position | MISSING | CONFIRMED-MISSING | Same |
| 09-15 | Compare: mark colours | MISSING | CONFIRMED-MISSING | Fixed colours |
| 09-15 | Compare: page ranges | MISSING | CONFIRMED-MISSING | `Compare2Screen.kt:119-128` has two pickers only |
| 16-24 | Bookmarks: Delete All | MISSING | **ACTUALLY-PARTIAL** | Sanitize has a BOOKMARKS item that removes the whole outline (`core/redact/Sanitizer.kt:20`; `ui/security/SanitizeScreen.kt:137`, Tools → `sanitize`). There is no button inside the bookmark editors |
| 16-24 | Chained action list | MISSING | CONFIRMED-MISSING | `/Next` is only walked (PageOps5:88, Sanitizer:355) and written for the live watermark (LiveWatermark:58); no editor |
| 16-24 | Pref: wrap bookmarks / text size | MISSING | CONFIRMED-MISSING | No settings key |
| 16-24 | Auto bookmarks: leading numbers | MISSING | CONFIRMED-MISSING | The dialog has levels, ratio and replace only (`ui/edit5/BookmarksScreen.kt:176-184`) |
| 16-24 | Auto bookmarks: magnification | MISSING | CONFIRMED-MISSING | Same |
| 16-24 | Auto bookmarks: collapse / exact text | MISSING | CONFIRMED-MISSING | Same |
| 16-24 | Auto bookmarks: .inb settings | MISSING | CONFIRMED-MISSING | Same |
| 16-24 | Links: multi-select / align / distribute / resize | MISSING | CONFIRMED-MISSING | `LinkOps.update` keeps the old rect (`ui/edit5/ReaderLinks.kt:95`, `LinksScreen.kt:121`); there is no LINK in `AnnotKind` (`core/pdf/Annotations.kt:25-29`), so the annotation move/resize code does not apply |
| 16-24 | Link: custom action list | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Link: use current appearance as default | MISSING | CONFIRMED-MISSING | No link prefs (all SharedPreferences names checked: toolset, view5_tools, psf_sign… none for links) |
| 16-24 | Auto Page Links | MISSING | CONFIRMED-MISSING | The only callers of `LinkOps.add` and `linkifyText` are the manual and URL paths (LinksScreen:86,120; ReaderLinks:48,69) |
| 16-24 | Auto Keyword Links | MISSING | CONFIRMED-MISSING | Same |
| 16-24 | Named actions | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Document JS editor + console | MISSING | CONFIRMED-MISSING | JS is only listed by the Sanitize scan (`Sanitizer.kt:97`) |
| 16-24 | Document-level functions | MISSING | CONFIRMED-MISSING | Same |
| 16-24 | Document actions (WC/WS/WP/DP…) | MISSING | CONFIRMED-MISSING | Shown read-only in the pre-sign analyzer (`ui/signature/PreSignDialog.kt:43-46`); no editor. DocPropsOps writes only the OpenAction view |
| 16-24 | Delete attachment | MISSING | **ACTUALLY-PARTIAL** | Page-level FileAttachment annotations (which `AttachmentOps.list` reports, :33-38) can be deleted one by one in the reader comments panel (`ui/reader/annot/CommentsPanel.kt:171,220`). Document-level /EmbeddedFiles can only be removed all at once (Sanitize/Optimize/PDF-A) |
| 16-24 | Edit attachment description | MISSING | **ACTUALLY-PARTIAL** | `FILE_ATTACH` is in `hasEditableText` (`ui/reader/AnnotEdit.kt:258-260`), so its /Contents can be edited, and that is the text AttachmentOps shows as the description (:37). Document-level file descriptions cannot be edited |
| 16-24 | Print attachment | MISSING | CONFIRMED-MISSING | AttachmentsTool has extract and share only |
| 16-24 | Portfolio-opened notification | CODE-ONLY | CONFIRMED-CODE-ONLY | `Portfolio.isPortfolio` has 0 callers; `Portfolio.` is called only from `MergePortfolioScreens.kt`; no `/Collection` check anywhere in the reader |
| 16-24 | Attach source file on Create PDF | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Layers pane show/hide | MISSING | CONFIRMED-MISSING | `PDOptionalContent*` appears only in WatermarkOps:274-289 (its own OCG) and redact/PageInspector; no layer/OCG UI in ui/reader, ui/view5 or ui/edit5. `getOCGs` returns null in the JS shim (`core/forms5/AcroJsLib.kt:204`) |
| 16-24 | Layers: reset | MISSING | CONFIRMED-MISSING | Same |
| 16-24 | Layers: expand/collapse | MISSING | CONFIRMED-MISSING | Same |
| 16-24 | Layer properties | MISSING | CONFIRMED-MISSING | Same |
| 16-24 | Layer initial states | MISSING | CONFIRMED-MISSING | Only the watermark's /Usage |
| 16-24 | Set OCG state action | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Tags pane / create tags | MISSING | CONFIRMED-MISSING | `StructTreeRoot`: 0 hits in ui |
| 16-24 | Delete/reorder/edit tags, role map | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Tag properties beyond alt text | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Embed fonts | MISSING | CONFIRMED-MISSING | `isEmbedded` is only read (PdfInspect:89, GlyphDecoder, ToUnicodeRepair) |
| 16-24 | PDF/A-1b | MISSING | CONFIRMED-MISSING | The only `XmpWriter.packet` call (`core/tools5/PdfA.kt:209`) uses the defaults part=2, conformance=B; convert6 `pdf_pdfa` goes through the same `PdfA.convert` (`Cv6Engine.kt:194`) |
| 16-24 | PDF/A-1a / 2a | MISSING | CONFIRMED-MISSING | Same |
| 16-24 | PDF/A-2u | MISSING | CONFIRMED-MISSING | Same |
| 16-24 | PDF/A claim notification | MISSING | CONFIRMED-MISSING | `PdfInspect.` is called only from PdfA.kt and AccessibilityScreen:85 |
| 16-24 | PDF/UA marking | CODE-ONLY | CONFIRMED-CODE-ONLY | The `uaPart` parameter exists (`IccSrgb.kt:113-133`), but the only caller (PdfA.kt:209) does not pass it |
| 16-24 | PDF version selection | MISSING | CONFIRMED-MISSING | Only forced to 1.7 when the version is outside 1.4–1.7 (`PdfA.kt:217`) |
| 16-24 | Batch: run on folders | MISSING | CONFIRMED-MISSING | `OpenDocumentTree` is used only in SyncScreen, ExtractImagesScreen and FilesScreen |
| 16-24 | Batch: all open documents | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Batch: Execute JavaScript | MISSING | CONFIRMED-MISSING | StepType list (`Recipes.kt:22-34`) |
| 16-24 | Batch: Delete All Comments | MISSING | CONFIRMED-MISSING | No step. Nuance: batch redaction with "also sanitize" uses `SanitizeItem.DEFAULTS`, which includes COMMENTS (`ui/security/RedactScreen.kt:233`), but that is a side effect that also strips other content |
| 16-24 | Batch Print | MISSING | CONFIRMED-MISSING | `PrintScreen` takes one file (`ui/tools/MiscTools.kt:28-41`); PdfPrint is called with one file |
| 16-24 | Add Place (custom server) | MISSING | CONFIRMED-MISSING | `webdav`/`PROPFIND`/`smb`/`nextcloud`: 0 hits |
| 16-24 | WebDAV with locking | MISSING | CONFIRMED-MISSING | Same |
| 16-24 | Default save folder | MISSING | CONFIRMED-MISSING | `docsDir` is fixed to `getExternalFilesDir/Documents` (`core/files/DocumentRepository.kt:29`) |
| 16-24 | Bookmark text size / wrap (Interface) | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Max recent count | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Default zoom | MISSING | CONFIRMED-MISSING | `defaultZoom`/`initialZoom`: 0 hits; only the `keep_zoom` key |
| 16-24 | Ignore line weights / thin lines | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Ignore page rotation (pref) | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Guides | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Pop-up note font/size/opacity | MISSING | CONFIRMED-MISSING | The annotation toolbar opacity chips set markup opacity, not the pop-up window |
| 16-24 | Retain Markup Tools | MISSING | **ACTUALLY-HAVE** | "Continue creating" toggle chip `rd_continue_creating` (`ui/reader/ReaderToolbars.kt:324-325`). `ReaderState.continueCreating` defaults to true (:119); the tool is cleared only when it is off (:341, :738). It lives in the toolbar, not in Settings, and is not persisted |
| 16-24 | Editing preferences | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Remove bookmarks of deleted pages | MISSING | CONFIRMED-MISSING | See side-claim (c) |
| 16-24 | Compare settings / colours | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Hide valid-signature icons | MISSING | CONFIRMED-MISSING | — |
| 16-24 | QuickSign black & white | MISSING | CONFIRMED-MISSING | See §11 |
| 16-24 | Disable JavaScript | MISSING | CONFIRMED-MISSING | See §9 |
| 16-24 | Internet access trusted sites | MISSING | CONFIRMED-MISSING | See §9 |
| 16-24 | Batch print preferences | MISSING | CONFIRMED-MISSING | — |
| 16-24 | Help: Community forum | MISSING | CONFIRMED-MISSING | `forum`/`community`/`منتدى`: 0 hits in res/values* |
| 16-24 | Help: Labs | MISSING | CONFIRMED-MISSING | `labs`/`تجريبية`/`experimental`/`beta`: 0 hits |

## Corrections only

1. **Retain Markup Tools (§23)**: MISSING → ACTUALLY-HAVE. Reader toolbar chip "continue creating" (`ui/reader/ReaderToolbars.kt:324`), on by default (`ReaderState.kt:119,341,738`). It is not persisted and not in Settings.
2. **Bookmarks: Delete All (§16)**: MISSING → ACTUALLY-PARTIAL. Sanitize → Bookmarks removes the whole outline (`Sanitizer.kt:20`, `SanitizeScreen.kt:137`). There is no button in the bookmark editors.
3. **Delete attachment (§17)**: MISSING → ACTUALLY-PARTIAL. Page FileAttachment annotations can be deleted one at a time in the reader comments panel (`CommentsPanel.kt:171,220`). Document-level files can only be removed all at once.
4. **Edit attachment description (§17)**: MISSING → ACTUALLY-PARTIAL. The /Contents of a FILE_ATTACH annotation is editable (`AnnotEdit.kt:258-260`), and that is the description AttachmentOps lists (:37). Document-level descriptions are not editable.
5. **Watermark profiles / import-export (§12)**: MISSING → ACTUALLY-PARTIAL (weak). A recipe WATERMARK step is named, saved and exportable as `.sdrecipe`, but it covers text, size and opacity only, with the old watermark engine.

Notes on confirmed rows (status unchanged):
- 40-bit encryption is kept when an existing 40-bit file is re-saved (`PdfOps.kt:71`).
- CCITTFactory is used, but only for TIFF→PDF.
- The pre-sign change list shows the revision count.
- Document actions are shown read-only in PreSignDialog.
- The Forms list shows signature fields as signed or unsigned.
- Header/footer "remove others" strips Acrobat Bates (`pdf:BatesN`) but not SmartDocs' own Bates.
- Batch redaction with sanitize removes comments as a side effect.

## Spot-check of 15 HAVE rows (all confirmed reachable)

| # | HAVE row | Entry point / evidence | Result |
|---|---|---|---|
| 1 | Trusted Contacts import | DigitalSignScreen:221 → `G6Routes.TRUSTED_CERTS` → Gaps6Routes:17 → TrustedCertsScreen:48,58 | OK |
| 2 | Certify document P1/P2/P3 | SignAdvancedOptions:98-106 with a not-first check; DigitalSignature:276 | OK |
| 3 | Search & redact whole words / case | RedactPanes:57 `SearchOptionsPanel` → SearchUiCommon:91-92 | OK |
| 4 | Sanitize hidden layers | Tools `sanitize` → ToolScreens:83 → SanitizeScreen; HIDDEN_LAYERS item | OK |
| 5 | Watermark on-screen/on-print flags | DecorScreen:127-128 → DecorSpec.onScreen/onPrint → OCG /Usage | OK |
| 6 | OCR skip pages with text | OcrInPlace:66,87 → MixedPageOcr.plan/dropExisting | OK |
| 7 | Optimize remove JS / attachments | Tools `optimize` → ToolScreens:61 → OptimizeExportScreens:87-89 | OK |
| 8 | Compare report PDF | Tools `compare` → ToolScreens:55 → Compare2Screen:145 → CompareReport.write | OK |
| 9 | Bookmark bold/italic | Edit5 hub `e5_bookmarks` → BookmarksScreen:162-163 → OutlineStore:105 | OK |
| 10 | Create web links from URLs | LinksScreen:86 → LinkOps.linkifyText | OK |
| 11 | Extract from portfolio | Tools `portfolio` → MergePortfolioScreens:151-167 | OK |
| 12 | Recipes import/export .sdrecipe | Tools `recipes` → ToolScreens:66 → RecipesScreen:54,94 | OK |
| 13 | Recipe SET_INFO step | RecipeRunner:73 → PdfOps.setInfo | OK |
| 14 | Certificate encryption / decryption | Tools `cert_protect` → ToolScreens:88 → SecurityTools:252 encrypt, :387 decryptToFile | OK |
| 15 | Auto Build Bookmarks / Link highlight modes / TSA | BookmarksScreen:123-125 → OutlineTools.detectHeadings; LinkEditor:94 (I/O/P/N); SignAdvancedOptions:34-35 TsaClient | OK |

## Side-claims

**(a) Optimize and Compare pass no password, so encrypted files fail: CONFIRMED, with one limit.**
- `OptimizeScreen` calls `Optimizer.auditFile(f, null)` (OptimizeExportScreens.kt:76) and `Optimizer.optimize(f, null, out, opts)` (:99).
- `Compare2Screen` calls `CompareEngine.compare(a, null, b, null)` (:128), `CompareReport.write(ctx, a, null, b, null, …)` (:145) and `PdfSession.open(ctx, f, null)` for the page render (:46).
- `rememberPdfPicker` (ui/common/Components.kt:293-307) never asks for a password.
- The loaders call `PDDocument.load(f)` with no password (Optimizer.kt:101,170; CompareEngine.kt:96). Files with an open (user) password therefore throw.
- Limit: files encrypted with only an owner password still load, because the empty user password succeeds. The failure applies to open-password files only.

**(b) `{bates}` is empty in a plain header/footer: CONFIRMED.**
- `HeaderFooterOps.apply` calls `draw(…, KIND, src.name, null)` (HeaderFooterOps.kt:77). `draw` then builds `TokenContext(bates = bates?.invoke(i) ?: "")` (:110-112), and `Tokens.expand` maps "bates" to `ctx.bates` (E5Format.kt:143), which is the empty string.
- The Header & Footer screen still lists `{bates}` in its token help (`Tokens.ALL` at HeaderFooterScreen.kt:144).
- The watermark TokenContext (WatermarkOps.kt:173) has no bates either.
- Only `HeaderFooterOps.bates()` (:180) supplies a value.

**(c) Deleting pages leaves dead bookmarks: CONFIRMED.**
- `PdfOps.deletePages` (core/pdf/PdfOps.kt:146-150) only calls `doc.removePage(it)` and then `saveTo` (:95-101 is a plain save). It does not clean up the outline, links or named destinations.
- All delete paths go through it: OrganizeState.kt:161, QuickController.kt:177, ai/DocActions.kt:135.
- Outline items whose /Dest points at a removed page dictionary keep that reference, so the dead page object is written as an orphan and the bookmark goes nowhere.
- Destination repair exists only in `PageOps5.repoint` (PageOps5.kt:69,184), and only the replace-pages path uses it.
