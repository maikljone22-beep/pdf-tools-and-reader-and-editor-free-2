# Fixed: tabs audit 3, EDIT tab (21 rows)

Branch `fix/tab-view-core`. The coordinator added this audit to the branch's scope. This is the fix log for `3_edit.md`.
Built and tested on GitHub CI only. Nothing was run on a device. Code marked `tab-view-core <id>` belongs to this branch.
Paths are relative to `app/src/main/java/com/smartdocs/editor/`.

| id | status | fix | files |
|---|---|---|---|
| E-01 | FIXED | One signed/certified gate (`SignedGuard`, described in `FIXED_1_view_core.md`):<br>- `applyInPlace` covers Edit content text, images and shapes, the OCR page, links, OCR, watermark, page numbers, properties and every other in-place writer. A full rewrite of a signed file, or a change a certification forbids, is never written into the original. The result is kept as `signedCopy` and offered through "Save a copy".<br>- Incremental writers (NavIo links / bookmarks) still append.<br>- `EditIo.save` / `E5Io.save` refuse a full save over an existing signed file.<br>- `replaceOriginal`, which is the form designer's and the edit5 tools' "replace original", checks `checkWrite`. | core/pdf/SignedGuard.kt, ui/reader/ReaderState.kt, core/edit/ContentRewriter.kt, core/edit5/E5Io.kt, core/files/DocumentRepository.kt, ui/common/SignedMessages.kt, ui/forms/FormsScreen.kt |
| E-02 | FIXED | When `applyEdit` returns null, the controller keeps the selection and the typed text. It shows the real reason (signed, certified, the save error, or "not saved") instead of "Saved". | ui/edit/EditController.kt |
| E-03 | FIXED | The form designer gets the reader's password (`OpenPasswords`), or asks for it with PasswordDialog when it is missing or wrong. It loads and saves through `PdfOps.load`, so the result is re-protected. | core/pdf/OpenPasswords.kt, ui/formsx/DesignerState.kt, FormDesignerScreen.kt |
| E-04 | FIXED | The Edit-tab Watermark and Page numbers chips now open the edit5 screens: `e5_watermark`, and `e5_header_footer`, whose default spec is page numbers at bottom centre. These are rotation- and crop-box-aware, tagged, and can be updated or removed. | ui/reader/ReaderToolbars.kt |
| E-05 | FIXED | Edit-content undo and redo call `ReaderState.historyStepAwait`, which goes through `runHistory`: the AN-04 file-step block, the `saving` check, and events. | ReaderState.kt, ui/edit/EditController.kt |
| E-06 | FIXED | `hasPendingChange` covers typed text, a pending move or resize, and an image transform. A tap outside, Back, or X with something pending asks "Apply changes?" with Apply / Discard / Cancel. | ui/edit/EditController.kt, EditModeHost.kt |
| E-07 | FIXED | Linked text boxes are keyed by the document's permanent /ID, not its path, so they don't leak to another file saved at the same path. A box is matched on its top edge and its left or right edge, which handles RTL (`LinkBoxMatch`, tested). Links whose source box is gone are pruned on each page load, which covers undo and redo. | ui/edit/TextTools5.kt, EditController.kt |
| E-08 | FIXED | `ReaderIntents.decode` uses `PdfOps.decodeScaled`, which applies EXIF orientation. | ui/reader/ReaderIntents.kt |
| E-09 | FIXED (renamed + both offered) | The chips are now "Text box (comment)" and "Image (comment stamp)". Edit content, the first chip, adds real page text and images. | ReaderToolbars.kt, strings_tabview.xml |
| E-10 | FIXED | - The Watermark and Page numbers chips use the same edit5 implementation as Edit tools.<br>- One Document properties: the Edit chip opens `e5_doc_props`, and the ⋮ quick item was removed, leaving NVE "Document properties".<br>- The second "Organize pages" was removed from the reader's Edit tools sheet. | ReaderToolbars.kt, ReaderScreen.kt, nve/NveHost.kt |
| E-11 | FIXED | Page-assembly e5 tools require ASSEMBLE. `e5_xref`, `e5_destinations` and `e5_doc_props` are viewers and require nothing (tested). | core/security/ReaderRestrictions.kt |
| E-12 | FIXED | Before the first interactive write (applyInPlace or Save) on a protected file opened without its owner password, the reader asks once for the owner password (which is then kept), or lets the user continue knowingly or cancel. The password is checked before use. | ReaderState.kt (`confirmOwnerPassword`), ReaderScreen.kt |
| E-13 | FIXED | Cancelling the picker leaves the IMAGE tool. The image tool has its own hint ("place the image"). | ReaderScreen.kt, ui/common/Components.kt |
| E-14 | FIXED | PropertyBar shows only opacity for IMAGE and nothing extra for LINK (no palette, no font-size slider). | ReaderToolbars.kt |
| E-15 | FIXED | - ToolChip labels can wrap to two lines (merged with pdf-39's 72 dp / two-line change).<br>- The icon has no content description of its own, so TalkBack reads the label once.<br>- "Edit tools" has its own icon (Handyman).<br>- "Edit tools" and "Edit form fields" were moved to the front of the row, so they are no longer off-screen. | ReaderToolbars.kt |
| E-16 | FIXED | The single-image picker uses `PickVisualMedia`. The reader deletes the temp copy after decoding, and picked copies older than one day are cleaned up. | ui/common/Components.kt, ReaderScreen.kt |
| E-17 | FIXED | Quick `setInfo` updates an existing XMP packet in place with `XmpSync` (tested). Other XMP data is kept, for example PDF/A identification. The Edit chip opens the full editor. | core/pdf/XmpSync.kt, core/pdf/PdfOps.kt |
| E-18 | FIXED | A stroke snapshots only the active layer (partial undo steps). Steps dropped from the history recycle their bitmaps. Burn, dodge, heal and clone dabs run in order on one background thread, and undo, redo, Done and the next stroke wait for it. | ui/nve/imgedit/NveImageEditor.kt |
| E-19 | FIXED | The reader's key handler ignores keys while Edit content is open. | ReaderScreen.kt |
| E-20 | FIXED | Edit tools screens (`E5Doc`) start with the reader's password (`OpenPasswords`, held in memory only and removed on close). | ui/edit5/E5Ui.kt, ReaderState.kt |
| E-21 | FIXED | A quick chip tapped while another quick operation runs shows a "busy" message. | ui/quick/QuickController.kt |

21 FIXED. 0 OWNED. 0 NOT-FEASIBLE.

Known limits:
- E-12: when the owner password is not given, the saved file still gets a new random owner password. The user is now told this and chooses.
- E-07: links made before this change were keyed by path and are not migrated.

Tests: `core/security/EditToolPermissionsTest`, `core/pdf/XmpSyncTest`, `ui/edit/LinkBoxMatchTest` and `core/pdf/SignedGuardTest`. Not tested on a device.

CI: run 40634979004 (commit c66cc54) passed build and unit tests.
