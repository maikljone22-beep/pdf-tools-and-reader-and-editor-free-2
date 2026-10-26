# MODULE_history_r5 — unified multi-level undo / redo

User report: «لم تتم إضافة التراجع خطوة / الإعادة خطوة للتعديلات بشكل عام». Before this module, undo existed only in
fragments (pending annotations in `ReaderState`, a single «تراجع» in the quick-tool done bar, content edit's own
backup list, forms5's own stack, the AI chat's own backups). Now every change to the open document is one step of one
per-document history, with an always-visible Undo / Redo pair in the reader.

## Design

```
core/history/UndoStack.kt   pure ordering logic (unit-tested)
  HistoryStep.Memory(label, undo, redo, page, owner, tag)        in-memory change, callbacks run on Main
  HistoryStep.FileSnapshot(label, before, beforePassword, page)  byte copy of the previous file version;
                                                                 `after` is captured on first undo (for redo)
  UndoStack(maxSteps = 60, maxBytes = 300 MB, onDrop)
    push (clears redo) · peekUndo/peekRedo · commitUndo/commitRedo (only after the action succeeded)
    undoCountTo/redoCountTo (jump) · replaceMemory (save consolidation) · removeIf · prune (oldest first,
    newest step always kept) · onDrop deletes the snapshot files
core/history/DocHistory.kt  per-open-document manager (Compose-observable `version`, `busy`)
  folder: <app.documents.tempDir>/history/<d{hash(uri)}_{unique}>/
  copyIn(file) · push · pushMemory · replaceMemory · removeOwner · undo/redo/undoTo/redoTo/undoThrough(file)
  `current` = (working file, password), `restorer` = ReaderState.restoreFrom (safe swap path, not recorded)
  open(): deletes every folder not owned by a live DocHistory (stale folders from killed / crashed processes)
  dispose(): drops all steps and deletes the folder (ReaderState.close)
ui/reader/HistoryUi.kt      HistoryButtons (top bar), HistorySheet, HistorySnackbar
```

### Recording file changes
`ReaderState.swapFile(tmp, label, prevPassword, record = true, requireSnapshot = false)` is the single place where the
working file is replaced. Before the replace it copies the current file into the history folder (same encrypted bytes
for password-protected files; the password it opens with is kept in the step), and pushes a `FileSnapshot` after the
rename (before the reopen). So every path that goes through it is covered automatically:

| Path | Label |
|---|---|
| `applyInPlace` (quick tools, reader replace, redaction marks, edit5 links, view5 comment check / restyle, workbench, share6 collab merge, AI agent, content edit) | caller's label, evaluated after the op succeeded (quick tools use their «done» text) |
| `deleteExisting` (stored annotation) | «حذف تعليق من الملف» |
| `Annot2Host.fileOp` (group / ungroup / delete stored, handwriting → text) | «تعديل التعليقات في الملف» |
| forms5 `addRow` (XFA repeat) | «إضافة صف إلى النموذج» |
| Full-screen tools opened from the reader (`navigateTool`: Organize / PAGES tab, edit5 & tools screens, redact tool) that save into the same file | «تنظيم الصفحات» / «تعديل بأداة» |

Full-screen tools: before navigating, `ReaderState.beforeExternalTool(label)` copies the current version into the
history folder; when the reader resumes and `reloadIfChanged` sees the file changed, that copy becomes one undo step
(it is deleted when nothing changed). Organize keeps its own multi-step undo inside its screen.

Not recorded (`record = false`): the save's own swaps (see below), undo/redo/restore swaps (`restoreFrom`), and taking
stored annotations out of the file for editing (AnnotEdit convert / MultiSelect convertStored) — those continue as
pending annotations, and their pre-conversion file is used by the next save step.

`applyInPlace` uses `requireSnapshot = true` (as before, a failed backup copy aborts the change) and returns the
snapshot file, which is owned by the history (callers must not delete it). The quick-tool done bar, content edit,
and the AI chat undo through it with `undoThrough(file)` (jump back through that step).

### In-memory steps
- Pending annotations: `addAnnot / removeAnnot / replaceAnnot / batchAnnots` (covers annotation tools, move / resize /
  restyle / delete in AnnotEdit, multi-select ops, handwriting → text, copy) push a `Memory` step with before / after
  lists (owner `annots`). `adoptConverted` adds the converted annotation to every recorded state, as before.
- forms5 field values: `setValue / setValues / setImage` push a `Memory` step (owner `forms`); successive keystrokes in
  one field stay one step (the "after" state is captured when the step is undone). The field bar's undo / redo stay,
  enabled when the newest step is a form step, and call the unified undo / redo.

### Saving semantics
Saving is not a step. When `save()` actually writes (pending annotations, form values or annotation edits), it copies
the version before the save (or the pre-conversion backup when stored annotations were taken out) and
`replaceMemory`s: every in-memory step is replaced by one `FileSnapshot` labelled with the last change
(«… (+n تغييرات أخرى)»); redo is cleared. Undo after a save therefore restores the previous file content through the
normal swap path. File-level undo / redo write the restored version back to content:// / cloud originals like a normal
save (`restoreFrom` → `writeBack` + `SaveHooks.onSaved`), so the document is not left dirty: there is nothing pending
in memory, and the original already shows the undone state. In-memory undo marks the document dirty, as before.

### UI
- Reader top bar: Undo ↶ / Redo ↷ (`Icons.AutoMirrored.Filled.Undo/Redo`: mirrored in RTL, so the undo arrow points
  right), always visible, disabled (38 % alpha) when empty, busy or saving. Long-press either one → history sheet. The
  overflow menu also has «سجل التعديلات».
- History sheet: steps newest first — redo-able steps (faded), the current state (highlighted), earlier steps, then
  «بداية السجل». Each row = document right after that step (label, time, page, "file" tag); tapping it undoes / redoes
  back to that point (`undoTo` / `redoTo`). Header shows step count and disk size, plus undo / redo buttons.
- Snackbar after each undo / redo: «تم التراجع: <label>» with «إعادة» (and «تمت الإعادة: …» with «تراجع»); errors:
  «تعذّر التراجع: …».
- Keyboard (`V5Keys`): Ctrl+Z undo, Ctrl+Y and Ctrl+Shift+Z redo.
- Quick-tool done bar «تراجع» → unified undo back through that step (it stays undoable after the bar disappears);
  «حفظ كنسخة» copies the result then undoes through the unified history (the change remains available as redo).
- Property bar undo / redo, content-edit overlay undo (+ a new redo button) and the forms bar all use the unified stack.
- Office editors (`ui/office/*`) already have their own undo / redo (text, Word, sheets, slides): left as they are.
  Organize keeps its own temp-file stack inside its screen; what it saves back is one reader step (see above).

## Files
New: `core/history/UndoStack.kt`, `core/history/DocHistory.kt`, `ui/reader/HistoryUi.kt`,
`res/values/strings_history.xml`, `res/values-en/strings_history.xml`,
`app/src/test/java/com/smartdocs/editor/core/history/UndoStackTest.kt`.

Changed: `ui/reader/ReaderState.kt` (history, annotation steps, unified undo/redo/undoTo/redoTo/undoThrough, swapFile
recording, save consolidation, applyInPlace label + snapshot, close → dispose), `ui/reader/ReaderScreen.kt` (buttons,
sheet, snackbar, menu item, AI host / collab labels), `ui/reader/AnnotEdit.kt`, `ui/reader/annot/MultiSelect.kt`,
`ui/reader/annot/Annot2Host.kt`, `ui/forms5/FormFillState.kt`, `ui/quick/QuickController.kt`,
`ui/quick/QuickActions.kt` (comment), `ui/edit/EditController.kt`, `ui/edit/EditModeHost.kt`, `ui/view5/Keys5.kt`,
`ui/view5/CommentManager.kt`, `ui/view5/Workbench.kt`, `ui/edit5/ReaderLinks.kt`. (`core/ai/DocActions.kt` and `ui/ai/AgentChatState.kt` were briefly edited and reverted to
their original content.)

AI agent: the reader's `DocHost.apply` hands the chat a token path (`<snapshot>.ai-undo`, never created) so the chat's
own clean-up on close cannot delete the history's snapshot; `restore` maps it back and undoes through that step
(`ui/ai/*` was not touched).

## Covered / not covered
Covered: every reader change listed above, multi-step, in one order with pending annotation and form edits;
password-protected files (snapshots are the encrypted bytes + password, protect / unlock restore the old password);
write-back to content:// and cloud originals on undo / redo; limits 60 steps / 300 MB (oldest dropped); folder deleted
on reader close and stale folders deleted when the next document opens.

Not covered (by design or out of scope):
- Tools opened outside the reader's `navigateTool` (e.g. from the home screen, the standalone outline editor) on a file
  that is open in a reader: the reader reloads the change but has no "before" copy, so it is not a separate step;
  undoing an older step restores that older version (linear history).
- Viewing state (zoom, page colour, view mode, bookmarks of the viewer) is not undoable.
- Steps in progress inside a tool (polygon vertices, workbench points before «حفظ») keep their own local undo
  (`undoVertex`, `undoPoint`); once written they are one step.
- History is per open reader instance; it is not kept across process death or activity recreation.
- AI chat's per-message «تراجع» state is not refreshed when the same step is undone from the top bar.

## Tests / build
`UndoStackTest` (11 tests): order of undo / redo, redo cleared by a new change, failed action not committed,
count limit, size limit with file deletion, newest step kept when too large, jump counts, save consolidation
(`replaceMemory`), removeIf / clear, labels / folder names, and a `DocHistory` file undo → redo round trip with
passwords + stale-folder clean-up.

Build (`./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain`, project root):
**BUILD SUCCESSFUL in 7m 49s**; 53 test classes, 384 tests, 0 failures, 0 errors (UndoStackTest 11/11).
(A first run failed only on `core/convert6/FileKind6.kt: Unresolved reference 'CfbReader'` — files another process was
writing at that moment, not this module; the second run passed.)
