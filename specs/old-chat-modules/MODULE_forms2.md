# MODULE forms2: form filling in the reader + dynamic XFA forms

Strings: `res/values/strings_forms2.xml` + `res/values-en/strings_forms2.xml` (prefix `f2_`, identical key sets).

No gradle run. I compiled with a standalone `kotlinc` 2.3.21 (and the Compose compiler plugin), against android.jar, PdfBox-Android, the Compose 1.7.8 / material3 1.3.1 jars and small stubs for ReaderState, SmartDocsApp and R. All new core and UI files compile without errors. The two `ui/forms` edits are small and were not compiled.

I ran the XFA binding and layout on a sample XDP on the JVM. It contained:
- a positioned header
- a table with a repeating row (3 data records)
- lr-tb check, radio and choice fields

Both binding and add/remove of rows gave the expected data paths.

## Files
Core (no Compose)
- `core/forms/ReaderFormData.kt`:
  - `FormWidget` covers every widget of every AcroForm field: page, rect, on-value of a check box or radio, options and export values, readOnly / required / multiline / password / comb / maxLen, quadding, DA font size and colour.
  - Rects are in the reader's displayed page space: the crop-box origin is moved to 0,0 and `/Rotate` is applied, so `PageGeometry.pdfRectToLayout` maps them directly.
  - `ReaderFormLoader.load(file, pw)` returns the widgets, current values (FormOps conventions, with radio `/Opt` export values resolved), the `FormCalcModel` and the XFA kind.
- `core/forms/XfaModel.kt`: `XfaTemplate`, the XFA template parser.
  - Subforms with layout `position | tb | lr-tb | rl-tb | row | table` and `columnWidths`.
  - `occur` min/max/initial, `bind none | dataRef`, borders.
  - Fields with ui `text | numeric | date | check | choice | password | signature | image`, captions (placement and reserve), items and save items, font size, `para hAlign`, `maxChars`, tooltips.
  - `exclGroup` (radio buttons) and `draw` (text / rich exData, line, rectangle).
  - `breakBefore` / `break`.
  - Page set: medium stock, short/long, orientation, contentArea.
  - Units: in, mm, cm, pt, em, mp, pc. A bare number is in inches.
- `core/forms/XfaForm.kt`: the template bound to `xfa:datasets` as an instance tree.
  - A repeating subform gets one instance per data record, at least `min`, and `initial` when there is no data.
  - `addInstance` / `removeInstance` respect the occur limits, and data paths are recomputed after each change.
  - `values()` and `fields()` list the values and the field items.
  - `save(src, out, pw)` changes only the datasets packet. It deletes the data records of removed rows, creates the records of new rows, writes every value and drops `/Perms /UR3`.
  - `fromXml(bytes)` is for tests.
- `core/forms/XfaLayout.kt`: lays the form out into pages of positioned boxes (points, origin at the top-left).
  - Handles positioned and flowed layouts, wrapping in lr-tb / rl-tb, table rows with column widths and cell grid, captions, repeating rows and explicit page breaks.
  - Flowed (tb/table) content taller than the remaining space is split across pages.
  - Optional add-row / remove-row control boxes. The remove control sits just outside the row's end.
  - Text width is pluggable: an estimate on screen, real font metrics for PDF.
- `core/forms/XfaExport.kt`: converts the filled form into a normal PDF from the same layout, drawn with `TplCanvas` (Noto Naskh, `ArabicShaper` + `showArabic`).
  - `Mode.FLAT` draws captions and values as page content.
  - `Mode.ACROFORM` draws the same pages with real AcroForm fields, created by `FormBuilder.addFields` and pre-filled through `FormOps.fillFields`, so Arabic values get the embedded font and shaped appearances.

UI
- `ui/reader/forms/ReaderForms.kt`: per-reader form state, `ReaderForms.of(state)`, with a registry so it works before the hook is in place.
  - Loads in `ensureLoaded()`, keyed on file + `renderVersion`, so it reloads after every save or file swap.
  - Keeps pending `edits`. `computed` holds the calculated fields, recalculated in `/CO` order after every change with the existing `FormCalcModel`.
  - Tracks the active widget and the Next/Previous order: by page, then rows top to bottom, then right to left when the page text is Arabic.
  - Its own undo/redo stack. Typing in one field is one step. `undoStamp` / `redoStamp` let it interleave with the annotation stack.
  - `pending()` returns values plus the formatted `display` text.
  - `writeTo`, `flush(file, pw, swap)`, `exportSource(file, pw)`, `committed()`, `discard()`.
- `ui/reader/forms/ReaderFormsOverlay.kt`: `ReaderFormsPageOverlay(state, page, g)`, the form layer of one page.
  - Draws Acrobat-style field highlights when the `highlightFields` setting is on, with a red frame on required fields.
  - Draws pending values over the rendered bitmap: AFNumber/AFDate/… formatted text, check marks, radio dots, choice labels.
  - Puts tap targets on field boxes only, so the rest of the page keeps all its gestures. Check boxes and radios get at least 36 dp.
  - Check boxes and radios toggle on tap.
  - Combo and list boxes open an anchored `DropdownMenu`. List boxes are multi-select when the field allows it.
  - Text fields get a `BasicTextField` placed exactly on the field rect:
    - font size from the DA (auto = fits the height) × zoom
    - right-aligned for Arabic text or right quadding, `TextDirection.Content`
    - `maxLen` limit and password masking
    - Decimal or Phone keyboard from the keystroke action
    - IME Next / Done, and the field is brought into view above the keyboard
  - Inactive only while an annotation tool, a placement or a text selection is active.
- `ui/reader/forms/ReaderFormsBar.kt`:
  - `ReaderFormsBar(state, modifier)` is the field bar above the keyboard (`imePadding`). It has previous / next, field name with "n of N", a date picker for AFDate fields, clear, undo, redo and done. Its own `BackHandler` closes the field first. Choosing a tool ends field editing.
  - `ReaderXfaBanner(state, modifier)` appears for dynamic XFA documents and opens `XfaFormDialog`. Saving goes through `state.applyInPlace`, so the data lands in the same original file and is written back.
- `ui/forms/xfa/XfaFormRenderer.kt`: `XfaPages(form, zoom, tick, …)` renders the laid-out pages scaled to the width × zoom.
  - Static text, captions, lines and borders are drawn on a Canvas.
  - Real inputs sit on the field boxes; choice lists open a dropdown; check boxes and radios toggle.
  - Repeating rows show "add row" chips and "−" buttons.
  - Signature and image fields are shown but not editable.
- `ui/forms/xfa/XfaFormDialog.kt`: the full-screen form view.
  - Zoom 1–3×.
  - Save into the same file through the caller's `saveInPlace`.
  - Overflow menu: "save filled copy", "convert to fillable PDF", "convert to static PDF".
  - Asks to save or discard on close.
  - Results go to `onResult`, or to a built-in share dialog when there is none.

Edited (forms tool, re-read before each edit)
- `ui/forms/XfaFormPanel.kt`: `XfaBanner(info, onOpenForm = null, onFlattenInfo)`. For dynamic XFA it now shows a sentence and the buttons "open form to fill" and "convert to PDF". Without `onOpenForm` it behaves as before.
- `ui/forms/FormsScreen.kt`:
  - New `xfaView` state. The banner and the flatten action for dynamic XFA open `XfaFormDialog`; the old "impossible" dialog is no longer reached from flatten.
  - `saveInPlace` writes to a temp file, copies it over the file and runs write-back.
  - Results go to the existing `ResultSheet`.
  - Closing the dialog reloads the list.

## Reader hooks (lead): exact minimal lines
1. **ReaderState: create the instance and dirty flag.** Add after `var saving by mutableStateOf(false); private set`:
   ```kotlin
   /** In-reader AcroForm filling (MODULE_forms2): pending field values, live calculations, own undo. */
   val forms = com.smartdocs.editor.ui.reader.forms.ReaderForms(this).also { it.markDirty = { dirty = true } }
   ```
2. **ReaderState.save(): flush form values into the same file first.** Inside `saveMutex.withLock {`, right after `val f = file ?: return false`:
   ```kotlin
   // MODULE_forms2: pending form-field values are written first (FormOps.fillFields → swapFile)
   var formsSaved = false
   if (forms.hasPending) {
       saving = true
       try { formsSaved = withContext(NonCancellable) { forms.flush(f, password) { tmp -> swapFile(tmp) } } }
       catch (e: Exception) { error = e.message ?: e.toString(); saving = false; return false }
       saving = false
   }
   ```
   Then change the existing write-back line in the `annots.isEmpty()` branch:
   `if (edit.hasBackup) { writeBackResult = …; edit.committed() }` →
   `if (edit.hasBackup || formsSaved) { writeBackResult = runCatching { app.documents.writeBack(f) }.getOrNull().also { com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f) }; if (edit.hasBackup) edit.committed() }`
   (When annotations are pending too, the normal path that follows already writes back.)
3. **ReaderState.exportWithAnnots: save as / share / print include the pending values.**
   ```kotlin
   suspend fun exportWithAnnots(out: File) {
       val f0 = file ?: return
       val f = forms.exportSource(f0, password)          // MODULE_forms2: temp copy with pending field values
       try {
           if (annots.isEmpty()) withContext(Dispatchers.IO) { f.copyTo(out, overwrite = true) }
           else AnnotationWriter.commit(context, f, annots.toList(), out, password)
       } finally { if (f != f0) f.delete() }
   }
   ```
4. **Undo/redo across annotations and field edits.** Optional: the field bar has its own undo/redo.
   - Add the fields:
     ```kotlin
     private val undoTimes = ArrayDeque<Long>(); private val redoTimes = ArrayDeque<Long>()
     ```
   - In `snapshot()`:
     ```kotlin
     undoTimes.addLast(android.os.SystemClock.uptimeMillis()); if (undoTimes.size > 60) undoTimes.removeFirst(); redoTimes.clear()
     ```
   - First line of `undo()`:
     ```kotlin
     if (forms.canUndo && forms.undoStamp >= (undoTimes.lastOrNull() ?: Long.MIN_VALUE)) { forms.undo(); return }
     ```
     After `val prev = …`, add `undoTimes.removeLastOrNull(); redoTimes.addLast(android.os.SystemClock.uptimeMillis())`.
   - First line of `redo()`:
     ```kotlin
     if (forms.canRedo && forms.redoStamp >= (redoTimes.lastOrNull() ?: Long.MIN_VALUE)) { forms.redo(); return }
     ```
     After `val next = …`, add `redoTimes.removeLastOrNull(); undoTimes.addLast(android.os.SystemClock.uptimeMillis())`.
   - Where the toolbar enables undo/redo, use `state.canUndo || state.forms.canUndo` and `state.canRedo || state.forms.canRedo`.
   - In `save()`, after the stacks are cleared, also add `undoTimes.clear(); redoTimes.clear()`.
5. **PageCanvas: the per-page form layer.** In `Box(modifier.size(wDp, hDp)) {`, after the closing brace of the big `Canvas(...) { … }` and before `if (editTarget != null && editBounds != null …`:
   ```kotlin
   com.smartdocs.editor.ui.reader.forms.ReaderFormsPageOverlay(state, page, g)      // MODULE_forms2
   ```
   The layer is the page's layout size, aligned top-left, and handles taps only on field boxes, so the existing tap and drag logic is unchanged everywhere else.
6. **ReaderScreen: field bar, XFA banner, discard.**
   - Make this the last child of the root `Box(Modifier.fillMaxSize().readerTouchWatcher(state)) {`, after the phone `ModalBottomSheet`:
     ```kotlin
     com.smartdocs.editor.ui.reader.forms.ReaderFormsBar(state, Modifier.align(Alignment.BottomCenter))   // MODULE_forms2
     ```
   - In the page area, after the `OcrSuggestionBanner(...)` line:
     ```kotlin
     com.smartdocs.editor.ui.reader.forms.ReaderXfaBanner(state, Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
     ```
   - In `DiscardDialog(... onDiscard = { … })`, add `state.forms.discard();` before `leave()`.

Without hooks 1–3 the reader still lets you fill and see values, but nothing marks the document dirty or saves them. Hook 5 is what shows the layer.

## Behaviour notes
- **Values:** they stay in memory until the reader's normal save (manual, autosave on pause, leaving with autosave, tab switch, tools that save first). They are then written into the same working file with `FormOps.fillFields(values, display = formatted text, context)`, so you get Arabic font and shaped appearances, formatted appearances with raw `/V`, and `dropXfa` for static XFA. After that, `swapFile` reopens the file and writes it back to the source URI.
- **Calculated fields:** they are not tab stops and cannot be tapped. Their live results are drawn over the page and saved with their formatted appearance. If stored totals are stale when the document opens, they count as pending changes, and the next save fixes them.
- **Highlighting:** follows `settings.highlightFields`. In night mode, pending values are drawn light on dark.
- **XFA from the reader:** a dynamic XFA document shows the banner. "Fill form" opens the form view, and saving there goes through `applyInPlace`, which saves pending annotations first, swaps the file, writes back and keeps an undo backup that is deleted again.

## Limitations
- **Typing in the reader:**
  - No JavaScript. Keystroke/validate scripts are not run, except the AF keyboard choice and the date picker.
  - Comb fields are typed as plain text without per-cell spacing.
  - Editable combo boxes only offer their list items.
  - Rich-text fields are edited as plain text.
- **On-page drawing:** pending values are drawn horizontally, even on pages shown with view rotation 90/270. The editor is not rotated either.
- **Tap targets:** a tap on a field box no longer reaches the page canvas, so you cannot start a text selection by long-pressing inside a fillable field. Choosing an annotation tool turns the targets off.
- **XFA binding:**
  - Normal, global and simple `dataRef` binding (relative `$.` / `$record.`) only. A `[*]` inside a field `dataRef` maps to index 0.
  - Several repeatable subforms with the same name in one data group share the records.
- **XFA scripts:** calculate/validate scripts (FormCalc/JS), presence toggling and choice-list scripts are not run.
- **XFA layout:** it is an approximation of XFA layout.
  - `anchorType`, margins/insets, keep/overflow leaders and subformSet choice logic are ignored. All members of a subformSet are shown.
  - Rich-text styling is reduced to paragraphs, and text widths on screen are estimated.
  - Positioned subforms taller than a page are not split.
- **Fillable conversion:** exclusive groups (radio buttons) stay drawn with their current choice and are not converted to fields. Image fields are left out. Signature fields become empty AcroForm signature fields.
- **Rights:** saving XFA data removes Reader-extension rights (`/Perms /UR3`), as the existing XfaSupport does.

## Routes
None needed. The XFA form view is a full-screen dialog opened from FormsScreen and from the reader banner. To give it its own Tools entry, a thin screen can wrap `XfaFormDialog(file, password, saveInPlace, onDismiss, onResult)`.
