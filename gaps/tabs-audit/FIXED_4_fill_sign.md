# Tabs audit 4 (Fill & Sign + Sign): fixes, one row per defect

**Branches:**
- **`fix/tab-fill-sign`** (pdf-3c, sign side). It merges `fix/tab-fill-sign-reader` (the helper, reader side), which brings in `fix/tab-view-core` (23ba22a), and main.
- **D2 is not fixed here.** It is solved centrally on `fix/tab-view-core` (`SignedGuard`, `ReaderState.applyInPlace` / `guardSigned`); this branch uses it and adds no second guard.

Paths are relative to `app/src/main/java/com/smartdocs/editor/`.

**Nothing here was tested on a device.** Rows marked "device" need a check on the SM-T970.

| Dn | Severity | Status | How (files) |
|---|---|---|---|
| D1 | Critical | fixed | Signed forms are filled in one raw-loaded document and appended incrementally: `FormValueWriter.writeSigned` with `FormOps.fillIn(fixup = false)`, `AppearanceRotation.fixIn`, image buttons, Hide flags and barcodes. `SignedFormMarks` flags the touched objects. `SignedSave.requireFormFillAllowed` refuses DocMDP/FieldMDP P=1, which shows the save-a-copy dialog (`ReaderState.save`). Fields locked by a signature are skipped. Tests: `SignedFormSaveTest` (value saved, signature valid, prefix identical, P=2 allowed, P=1 refused, locked field kept). |
| D2 | Critical | central (pdf-ad) | `SignedGuard` on fix/tab-view-core. The writers here append incrementally, so the gate lets them through. |
| D3 | High | fixed | `DigitalSignature.sign` handles encrypted files as follows. It first signs in place with the original security handler (`signInto(password)` via `PdfOps.loadRaw`), then checks the result with `verifyBlocking`. If that fails on a file with no earlier signatures, it signs an unencrypted copy and the report says so (`ns3_encryption_removed`). If the file was already signed, it refuses. Test: `EncryptedSignTest`, where the second signer keeps the first signature and the file stays encrypted. |
| D4 | High | fixed | `core/pdf/OpenPasswords` keeps the reader's open password in memory only (set by `ReaderState`). DigitalSignScreen passes it to analyze, changes, loadRaw, sign (including batch), verify, inspect, addEmpty and clear. |
| D5 | High | fixed | `sign()` refuses the paging seal and a text-only appearance (full-save pre-steps) when the file already has signatures. |
| D6 | High | fixed | The reader's field sheet uses `reader.applyInPlace(label, FILL_FORMS)` for Move / Delete / Clear, which gives the guard, the save lock, an atomic replace, an undo step and write-back. The tool screen writes new copies for "add empty field" (the screen then works on the copy) and for Verify > Clear (`produce("_cleared")`). |
| D7 | High | fixed | Discard and "save a copy, then leave" reuse V-02's `DiscardDialog(failed)`. After a refused signed save, autosave stops retrying until an explicit save or Discard. A failed `close()` keeps the edits as the recovery copy. |
| D8 | Med | fixed | `core/security/CertifiedLock` plus `Block.CERTIFIED`: ANNOTATE is blocked when P<3 and FILL_FORMS when P=1, read on open and after a reopen, with a banner. Test: `CertifiedLockTest`. |
| D9 | High | fixed (device) | `core/fillsign2/FsGeom`. FreeText, comb and STAMP_IMAGE appearances use an upright BBox with the `SigFields.matrixFor` matrix (FreeText also gets /Rotate). Clamping is done in the crop box. Stamps read back take the page /Rotate into account. Test: `FsGeomTest`. |
| D10 | Med | fixed | `MultiPage.relocate`, `pasteBox` and `boxAt` take the crop box in user space (`FsGeom.PageBox`, from `ReaderState.pageBoxOf`). The audit §3 numbers are unit tests. |
| D11 | High | fixed (device) | `absoluteOffset` for the form tap targets, the editor, the add-row bar, the choice menu and "Paste here". |
| D12 | Med | fixed | `DigitalSignature.verify(password)` / `verifyBlocking`. The reader bar, the field sheet and the Verify tab pass the password. |
| D13 | Med | fixed | Apply All and Remove seals report every exception. "None" appears only after a successful run. |
| D14 | Med | fixed | The gate keeps the result as `signedCopy`, so "Save a copy" contains the seal or the flattening. |
| D15 | Med | fixed | PIN gate on paste, "Paste here" and multi-page (reader). On the signing screen: batch signing (`signBatch`), the seal choice (`SignAdvancedOptions` with `sealGate`), and blurred, labelled thumbnails (`SavedSignatureThumb`, toolbar, seal dialog). |
| D16 | Med | fixed | `DigitalSignVm` holds the file, the unlocked ID (never saved state), the request and a `ToolRunner(viewModelScope)`, so a rotation neither cancels signing nor loses the ID. |
| D17 | Med | fixed | The certificate, import and new-ID passwords use `remember` (not saveable) and are cleared after use. |
| D18 | Med | fixed | Smart fill seeds from `currentValues()`, skips pending fields and checks COPY. New Fill & Sign chip (`ui/forms5/FillSignSmartFill.kt`). |
| D19 | Med | central (pdf-ad) | The E-sign route goes through `navigateTool` (save first + guard) on fix/tab-view-core. |
| D20 | Med | fixed (device) | The bottom bar height is measured, and `InPageFormBar(stripBottom)` places the strip above it. |
| D21 | Med | fixed | Closing the signed bar is `rememberSaveable`, and the bar shows only while no tool is active, so it never covers the placement bar. It is not stacked with the other banners, because the slot is shared with V-02 banners on another branch. |
| D22 | Med | fixed | `VerifyCache` per (path, length, mtime): an unchanged file is not validated again. The whole file is still read when it changes. |
| D23 | Low | fixed | The form menu has Convert to comb, JS console and Document actions. The duplicate comb dialog is removed. |
| D24 | Low | fixed (reader part) | One Done (in the placement bar). One "place on pages" (`MultiPageDialog`). Validation UIs: the NVE SignaturesPane is pdf-ad's. |
| D25 | Low | fixed | New chips: Signatures panel, Validate all, Digital IDs, E-sign request, My details. "Add signature field" stays in Digital signature (Sign tab, "Add an empty signature field here"). |
| D26 | Low | fixed | Date formats with a menu (`FsDate`), plus the Arabic-digit option. |
| D27 | Low | fixed | The profile chips and the signature thumbnails go through `selectTool`; a second tap deselects. |
| D28 | Low | fixed | TalkBack labels for the signature, initials and locked thumbnails. |
| D29 | Low | fixed | `CombLayout.cellsFor` / `overflow`: the box grows, with a warning. |
| D30 | Low | fixed | The Flatten-on-save preference is stored only on Apply. |
| D31 | Low | fixed | `StampClipboard.release` runs in `ReaderState.close`. The clipboard keeps the signature path only. |
| D32 | Low | fixed | Signatures, My details and Digital IDs open without the guard or a save first (`DOC_FREE_TOOLS`, plus main's `DOCUMENT_FREE`). |
| D33 | Low | fixed | The drawing state is kept with a `rememberSaveable` Saver and rescaled to the new canvas. |
| D34 | Low | fixed | `SignedSave.markPages` no longer rewrites untouched, signed signature widgets (`isSignedSigWidget`). Writers that edit one flag it themselves. |
| D35 | Low | fixed | The saved-signature chips on the signing screen show thumbnails (locked ones blurred). |
| D36 | Med | fixed | `reader.guard.check(FILL_FORMS)` runs before Sign field, Move, Delete and Clear (also inside `applyInPlace`). |
| D37 | Low | fixed | `flushOnSave` clears the typed values only after a successful swap. |

**Tests added:**
- `SignedFormSaveTest`
- `EncryptedSignTest`
- `CertifiedLockTest`
- `FsGeomTest`
- MultiPage / comb tests in the reader branch

**Needs device test:**
- D9: marks, text, comb and a signature on /Rotate 90/270 pages, checked in Acrobat.
- D11: Arabic UI tap targets.
- D20: strip position.
- D8 / D7: banner and dialogs on certified files.
- D14: the copy with a seal.
- D15: blur on Android 12+.
- D33: rotate while drawing.
- D1: a signed form filled by party B, then checked in Acrobat.
- D3: a second signature on a password-protected signed PDF, then checked in Acrobat.

## Follow-up (fix/sign-partials): the three partials closed
- **D21:** the reader's top banners are one column (`ReaderScreen`): tool hint / placement bar / OCR suggestion, the signed bar (`SignedBarSlot`), the XFA banner and the NVE document banners stack instead of overlapping; the signed bar's results and its closed state live in `FormFillState` (kept across rotation).
- **D22:** validation never reads the whole file into memory (`RevisionDiff.revisionEnds(File)` streams; each signature reads only its contents / signed range); after an append-only save the earlier signatures are reused (`verifyBlocking(reuse)`, `VerifyCache` keeps the file tail to recognise the append) — only new signatures are checked. Test: `RevisionStreamTest`.
- **D25:** Sign tab chip "Add an empty signature field" (opens the placement and adds the field); the Digital IDs chip opens straight on the Digital IDs tab (`SigFieldRequest.openTab`).
