# Fixed: tabs audit 1, VIEW tab and reader core (38 rows)

Branch `fix/tab-view-core`, from main 21c8c49, with origin/main merged before each push. This is the fix log for `1_view_core.md`. The EDIT tab rows are in `FIXED_3_edit.md`.
Built and tested on GitHub CI only. Nothing was run on a device. Code marked `tab-view-core <id>` belongs to this branch.
Paths are relative to `app/src/main/java/com/smartdocs/editor/`.

## The central signed/certified gate (V-06, also E-01 / D2 / T-01 / G-01)

`core/pdf/SignedGuard.kt` is the one decision point. It reuses SignedSave's isSigned and certifyLevel checks, the FieldMDP lock and `requireFormFillAllowed`, which is copied verbatim from fix/tab-fill-sign e5a0c61.
- `SignedGuard.decide(info, change, incremental)` returns PROCEED, SAVE_INCREMENTAL, OFFER_COPY or REFUSE. The change kinds are FORM_FILL, ANNOTATE and MODIFY. The function is pure and covered by unit tests.
- `ReaderState.guardSigned(change, incremental)` is the call for reader-side writers. `ReaderState.signedInfo()` is cached per file stamp.
- `ReaderState.applyInPlace` has a new optional `change` parameter. Its default comes from `action`. Every in-place change is judged after the op writes `out`:
  - If the source is signed and `out` is not a byte-prefix (incremental) update, or a certification forbids the change, the original is not touched.
  - The result is kept as `signedCopy` and `signedBlock` is set.
  - The dialog's "Save a copy" then exports that result, so the change is kept in a copy.
  - An op that appended incrementally (NavIo, CommentOps, AttachmentOps) is judged as an annotation-level change.
  - This covers all callers: quick actions (compress, OCR, watermark, page numbers, flatten, rotate, delete page, protect), content edit, AI edits, form reset and import, document JS, shapes, and the rest.
- `EditIo.save` and `E5Io.save` call `checkFullSave`, which never rewrites an existing signed file in full.
- `DocumentRepository.replaceOriginal`, `OrganizeState.saveOverwrite` and the Forms screen overwrite call `checkWrite(src, out, pw, change)`.

## Rows

| id | status | fix | files |
|---|---|---|---|
| V-01 | OWNED BY pdf-3c | Form-fill save of signed forms (FormOps/FormValueWriter) is fixed on fix/tab-fill-sign. Only its `SignedSave.requireFormFillAllowed` hunk is copied here, verbatim. | core/pdf/SignedSave.kt |
| V-02 | FIXED | When a save fails while leaving (back arrow, Back, tab switch), the user is no longer trapped. A DiscardDialog in failure mode (`failed=`) offers Save a copy / Discard / Cancel, and the signed reason is shown inside it. After a successful "Save a copy", a second dialog offers to close the document and discard its changes. `rememberTabSwitcher` reports the failure through `onSaveFailed`. | ui/reader/ReaderScreen.kt, ReaderDialogs.kt, view/ReaderTabs.kt |
| V-03 | FIXED | `TopBarOverflow.partition` (pure, tested) decides by screen width which top-bar actions stay in the bar: undo/redo, save, search, tell me, tabs, pins, position back/forward, in that priority. The rest are added at the top of the ⋮ menu, which is always visible. `V5TopActions(show)`, `v5TopEntries`, `V5TopOverflowItems`, and `ReaderTabsAction(showIcon, externalOpen)` support this. | ui/reader/TopBarOverflow.kt, ReaderScreen.kt, view5/Commands5.kt, view/ReaderTabs.kt |
| V-04 | FIXED | The text viewer uses `CopyableIf(guard.allows(COPY))`. | ui/nve/NveTextViewer.kt |
| V-05 | FIXED | Printing from the outline checks `guard.check(PRINT)` first. | ui/reader/ReaderPanels.kt |
| V-06 | FIXED | Central gate (see above). NVE Rewrite, ai6 Rewrite and Edit text all go through `applyInPlace`, so a signed file is never rewritten. "Save a copy" exports the result instead. | core/pdf/SignedGuard.kt, ui/reader/ReaderState.kt |
| V-07 | FIXED | `ReaderState.revertToSaved()` reverts in place, under `saveMutex`: it drops pending annotations and form values, puts the stored-annotation backup back, and reopens the session. No second reader races the closing one. | ReaderState.kt, ReaderScreen.kt |
| V-08 | FIXED | `ReaderAi6.ttsOwner` records which document read aloud belongs to. Read aloud stops in `ReaderState.close()` and when another document comes to the front. Page following, the TTS bar and the read-aloud chip's selected state apply only to the owning document. | ReaderAi6.kt, ReaderState.kt, ReaderScreen.kt, ReaderToolbars.kt |
| V-09 | FIXED | NveKeys PREV/NEXT/SPACE use `vx.flip(±1)`, which is spread-aware. A new pure `PageSpread.step` is tested for facing and cover modes. | ui/nve/NveKeys.kt, view/ViewExtras.kt |
| V-10 | FIXED | The v5 Reflow and Lens chips were removed from the VIEW row (both stay in Tell me). Only one magnifier can be on at a time. The NVE Grid chip moved to ⋮ "Grid, rulers and guides". The zoom chip and the ⋮ zoom item use a Percent icon. | view5/View5Host.kt, Commands5.kt, ReaderToolbars.kt, nve/NveHost.kt |
| V-11 | FIXED | The duplicate NVE Rewrite chip was removed (the ai6 Rewrite stays). The bookmark chips are now "Bookmark in PDF" and "Reading bookmark", with different icons, in the NVE chips and in the new selection-menu bindings (one-line change). | nve/NveSelection.kt, selmenu/SelBindings.kt, strings_nav_bm.xml |
| V-12 | FIXED | One recovery system remains (NveRecovery, with one interval: both settings screens edit `NpfPrefs.recoveryMinutes`). The old npf_recovery loop and its modal dialog were removed. A leftover old copy is handed over to the banner (`adoptLegacy`). Snapshots run under `saveMutex`. | NpfReaderHooks.kt, nve/shell/Recovery.kt, ShellPrefs.kt |
| V-13 | FIXED | `ReflowChunks.order` (pure, tested) builds runs of consecutive pages, so no chunk wraps from the last page to the first. `PageGraphics.images` loads with MemoryUsageSetting. | core/view5/ReflowChunks.kt, view5/ReflowReader.kt, core/view5/PageGraphics.kt |
| V-14 | FIXED | Share: a failed export shows an error instead of crashing, and Share works again after a null or failed export. Old `share_*` temp copies (more than 1 h old) are deleted. The newest one is kept because the receiving app may still be reading it. | ReaderScreen.kt |
| V-15 | FIXED | The pager is given the document's direction (`docRtl`), so swipe direction and spread order match the tap zones. Pages keep the app's direction. Continuous facing rows follow the same order. | ui/reader/PageView.kt |
| V-16 | OWNED BY pdf-39 (CM-06) | One tool memory. Merged from main: ToolMemoryEffect is gone and the switch is in the PropertyBar. | (fix/tab-comment) |
| V-17 | FIXED | Night mode is saved whenever it changes, by any route (`snapshotFlow`). The layout itself is remembered in `NpfPrefs.lastLayout`, so facing and cover are no longer saved as single. | ReaderScreen.kt, NpfReaderHooks.kt, core/data/NpfPrefs.kt |
| V-18 | FIXED | NveDocBanners no longer repeats JS, redactions and portfolio (other banners already show them). The rest is one notice with "N more". Dismissals are saved per file. | nve/panes/NveDocBanners.kt |
| V-19 | OWNED BY pdf-39 (CM-06) | Part of the tool-memory unification. The switch moved out of Read-aloud options into the PropertyBar on fix/tab-comment (merged). | (fix/tab-comment) |
| V-20 | FIXED | `closeSearch()` and the blank query cancel the search job and reset `searching`. | ReaderState.kt |
| V-21 | FIXED | The VIEW chip is now "Info panel". The ⋮ quick "Document info" item was removed, leaving one "Document properties" in ⋮. | ReaderToolbars.kt, ReaderScreen.kt, ToolbarCustomize.kt |
| V-22 | FIXED | The read-aloud highlight is drawn only for the document that owns read aloud, and is cleared on close. | ReaderAi6.kt |
| V-23 | FIXED | Removed dead code: `aiText` with its hard-coded Arabic question, `ReflowView`, `AnnotationsPanel` and `subtypeLabel`. | ReaderScreen.kt, ReaderDialogs.kt, ReaderPanels.kt |
| V-24 | FIXED | Split and Compare close the other session with `closeAsync` off the main thread. | view5/SplitCompare.kt |
| V-25 | FIXED | Focus returns to the pages whenever a dialog, sheet, menu, search bar, panel or view5/NVE surface closes. | ReaderScreen.kt |
| V-26 | FIXED | Every VIEW chip can now be hidden (VIEW_CHIPS covers the v5 and NVE chip ids, with labels). Duplicates were removed (V-10). | nve/shell/ShellPrefs.kt, ToolbarCustomize.kt, View5Host.kt, NveHost.kt |
| V-27 | FIXED | In the Comments panel header the chips and step buttons scroll, and the ⋮ (export/import) is always visible. | annot/CommentsPanel.kt |
| V-28 | FIXED | The Revert dialog was moved out of the ⋮ DropdownMenu, which now closes first. | ReaderScreen.kt |
| V-29 | FIXED | Selection actions in full screen come from the merged selection-menu layer, which is not tied to chrome. The TTS bar stays reachable in full screen. A tap always restores the bars, and picking a tool brings them back. | ReaderScreen.kt |
| V-30 | FIXED | The auto-flip buttons use FastForward / SlowMotion icons instead of +/−. | view/ScreenControls.kt |
| V-31 | FIXED | A recovery copy is written on ON_STOP when there are changes, whatever the autosave setting (while recovery is on). | nve/shell/Recovery.kt |
| V-32 | FIXED | The Protected View banner can collapse to a one-line chip, which still has "Enable editing". | ReaderSecurityBanners.kt |
| V-33 | FIXED | Presentation is immersive: system bars are hidden through the dialog window's insets controller. Night mode and page colour now apply. | view5/Presentation5.kt |
| V-34 | FIXED | `V5State.startAutoScroll` remembers the previous layout, which is restored when auto scroll stops (chip, Esc or command). | view5/V5State.kt, View5Host.kt, Commands5.kt |
| V-35 | FIXED | Compare with an encrypted PDF shows the password dialog. | view5/SplitCompare.kt |
| V-36 | FIXED | If the current tab is hidden, the reader switches to the first visible tab and its tools. | ReaderToolbars.kt |
| V-37 | FIXED | The INFO → OCR report runs on IO, is cached per file version, and is cancelled when the panel goes away. | NpfPanels.kt |
| V-38 | FIXED | A `Modifier.pageTint` helper applies the page colour in thumbnails, split/compare/quad panes, presentation and manual crop. | view/PageTint.kt, ReaderPanels.kt, SplitCompare.kt, NveHost.kt, Presentation5.kt, view/ViewSheet.kt |

36 FIXED. 3 OWNED (V-01 by pdf-3c; V-16 and V-19 by pdf-39). 0 NOT-FEASIBLE.

## Tests (JVM)

- `core/pdf/SignedGuardTest`: gate decisions and the incremental byte-prefix check.
- `ui/reader/view/PageSpreadStepTest`: key navigation in facing and cover modes.
- `core/view5/ReflowChunksTest`: reflow chunks are bounded.
- `ui/reader/TopBarOverflowTest`: top-bar overflow partition.
- `core/security/EditToolPermissionsTest` and `core/pdf/XmpSyncTest`, which belong to the EDIT rows.

Not tested on a device. Gesture direction, immersive mode, focus restore, dialogs and TTS ownership were checked only by code reading and CI compilation.

## CI

Run 40634979004 (commit c66cc54, origin/main facfb11 merged) passed: assembleDebug and testDebugUnitTest.
Earlier failures, all fixed: 40625600456 (deprecated createTempDir in a test), 40629118671 (Surface onClick overload), 40631908355 (XmpSyncTest: stray control characters in a regex).
