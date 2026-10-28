# fix/tab-comment — reader COMMENT + MY TOOLS tab audit (gaps/tabs-audit/2_comment_mytools.md)

Strings: `strings_tc.xml` (prefix `tc_`). Test: `TabCommentTest`.
One write path for comment changes: `SignedSave.editFile` (load without removing security → change → `saveEdited`:
incremental on signed files, refused on certified ones, re-protected otherwise). Refusals of quick changes
(`ReaderState.applyInPlace`, `Annot2Host.fileOp`) open the existing "save a copy" dialog (`signedBlock`).

| id | sev | Status | Fix |
|---|---|---|---|
| CM-01 | High | FIXED | `WorkbenchModel.writeAll` → `SignedSave.editFile` (pages of the items); `ScaleStore.write` was already signed-safe on main; `applyInPlace` turns `SignedDocumentException` into the save-a-copy dialog, the workbench keeps its items when nothing was written |
| CM-02 | High | FIXED | `CommentOps5.setChecked` delegates to `CommentStore.setChecked` (one implementation); restyle → `SignedSave.editFile` |
| CM-03 | High | FIXED | `WbView` maps through `PageGeometry` with `pageFrames` (/Rotate + crop origin); grid snapping in displayed space; box corners normalised |
| CM-04 | High | FIXED | `ImageAnnotProps5.applyFile` → `SignedSave.editFile` |
| CM-05 | Med | FIXED | one Measure (the saved workbench one; the canvas chip is gone, My tools "Measure" opens the workbench), workbench Tick / Line chips removed (Tickmarks / Line / Arrow stay), multi-select icon `SelectAll`; the two "checked" writers are one (CM-02) |
| CM-06 | Med | FIXED | one memory (`ToolMemory`): saved in the `ReaderState.tool` setter on every change (also when a tool clears itself), restore honours the switch; view5 `ToolMemoryEffect` removed; the switch is in the property bar |
| CM-07 | Low | FIXED | Comment property bar only with an active tool; undo / redo left to the top bar |
| CM-08 | Med | FIXED | eraser skips locked ink (pending and stored); one undo step per stroke (`eraseHits` / `endErase`) |
| CM-09 | Med | FIXED | Comments panel: locked comments are not deleted; stored deletes ask first |
| CM-10 | Med | FIXED | tab switch clears NVE drag mode / paste; the NVE overlay yields to an active tool |
| CM-11 | Med | FIXED | hiding comments ends the tool; picking a tool while hidden shows them again with a note |
| CM-12 | Med | FIXED | COPY checked for XFDF / FDF export, summary, export selected, Comment-manager exports; PRINT for snapshot → Print |
| CM-13 | Med | FIXED | `CommentOps7.keepOnly` keeps the encryption (loadRaw + reprotect); the filtered copy is deleted after the export |
| CM-14 | Med | FIXED | flatten on a signed file refused (`SignedSave.requireContentChangeAllowed`, reason CONTENT_CHANGE → save-a-copy dialog). To be switched to `SignedGuard.checkFullSave` when fix/tab-view-core is on main |
| CM-15 | Med | FIXED | `fileOp` calls `SaveHooks.onSaved` and `guard.afterSave`, sets `saving` during the write |
| CM-16 | Med | PARTIAL | `WorkbenchModel` kept in `V5State` (survives rotation); the page bitmap belongs to the session's render cache and is not recycled |
| CM-17 | Med | FIXED | pending export file in `Annot2Host`; "exported n" shown after the write |
| CM-18 | Med | FIXED | no outside-tap / back dismiss while recording; a recording interrupted by recreation is kept and added |
| CM-19 | Med | PARTIAL | chips 72 dp with two-line labels; grouping into dropdowns / compact-height collapse not done |
| CM-20 | Med | FIXED | `Customization.readerToolset` exported / imported (`ToolsetStore`) |
| CM-21 | Med | FIXED | user-tab tools filtered by `AiGate`; document-independent tools (`ReaderRestrictions.DOCUMENT_FREE`) open directly (no guard, save or undo step) |
| CM-22 | Low | FIXED | a missing user tab resets `userTab` |
| CM-23 | Low | PARTIAL | snapshot buttons wrap (FlowRow); bitmap not recycled (still shown while the sheet closes) |
| CM-24 | Low | FIXED | shortcuts go through `clickTool` and clear the user tab |
| CM-25 | Low | FIXED | tab switch clears the multi-selection |
| CM-26 | Low | FIXED | a picked, unplaced video is dropped on another tool |
| CM-27 | Low | FIXED | entering STAMP clears the previous tool's picture |
| CM-28 | Low | FIXED | one Lock / Unlock item by state; the properties dialog writes the lock only when changed |
| CM-30 | Low | FIXED | colour dots have spoken names (`colorName`) and RadioButton role; My-tools add / remove described, move up / down buttons |
| CM-31 | Low | FIXED | Comment paste is "Paste comment" / "Paste comment in place" |
| CM-32 | Med | FIXED | NVE area / page snapshot check COPY |
| CM-33 | Low | FIXED | previous / next comment walks stored and pending comments |
| CM-34 | Low | FIXED | one thickness slider for polygon / polyline / cloud (same 0.5–24 range) |
| CM-35 | Low | OPEN | placement copies are made only on slider release; recycling the replaced bitmap is not safe while a frame may still draw it |
| CM-36 | Low | FIXED | exports read a temp copy with the pending edits (`Annot2Host.exportSource`); the original is not saved |
| CM-37 | Low | FIXED | customisation import ≤ 256 KB, read on IO |
| CM-38 | Low | FIXED | the tool is cleared before Organize opens |
