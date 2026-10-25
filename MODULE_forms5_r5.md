# MODULE forms5 (round 5): fill forms on the page in the reader

Spec: `recovered-from-old-chat/MODULE_forms2.md`, plus the gaps listed in the brief. I rebuilt it in new packages
`core/forms5` and `ui/forms5` on top of the existing `FormCalc`, `AfFormat`, `XfaSupport`, `FormOps` and `FormBuilder`.

Strings: `res/values/strings_forms5.xml` and `res/values-en/strings_forms5.xml`. They use the prefix `f5_` and have the same keys.

No gradle was run.
- The pure-logic files and their tests compiled with `kotlin-compiler-embeddable 2.2.20` from the gradle cache, using a 3-line stub for `FieldScripts`, which lives in the PdfBox-dependent `FormCalc.kt`. **All 25 JUnit tests pass.**
- The Compose and PdfBox files were **not compiled**: Compose and pdfbox-android are not in the local gradle cache. I checked their APIs by hand against the existing code (Annot2Writer, ContentEdit, FormOps, FormsScreen).

## Files

### Core (`app/src/main/java/com/smartdocs/editor/core/forms5/`)

**`FieldNavigation.kt`**
- `Box5`: a plain-float rect.
- `PageSpace.rectToDisplay`: converts a widget `/Rect` to the reader's displayed page space. It moves the crop-box origin to 0,0 and applies `/Rotate`.
- `FieldNavigation.order`: the Next/Previous order.
  - Pages are taken in order.
  - Inside a page the order goes by rows. Rows run right-to-left on Arabic pages. A page with `/Tabs /C` goes by columns.
- `step` (wraps around at both ends) and `position` ("n of N").

**`AutocompleteStore.kt`**
- Remembers the values the user typed, per normalised field key, plus a global pool.
- Ranking: values that start with the typed text first, then values with a word that starts with it. Inside each group, by use count, then by recency.
- Matching ignores Arabic alef/yaa/taa-marbuta variants, harakat and tatweel, and treats Arabic-Indic digits like Latin digits.
- Values are capped per key. The store converts to and from JSON.
- `AutocompleteFile` keeps it in `filesDir/forms5_autocomplete.json`.

**`FieldDates.kt`**
- Formatting and parsing for the date picker in **UTC**. Material's DatePicker uses UTC midnight; the old `AfFormat.formatPickedDate` used the device zone and shifts the day west of UTC.
- Decides whether a field is a date field: an AFDate keystroke, a date format, or a date-like name (including "تاريخ").
- Provides the AFDate JS for the form builder.

**`FieldValidation.kt`** runs validation scripts (`/AA /V`) without a JS engine. It recognises:
- `AFRange_Validate`
- `if (event.value < a || event.value > b) {app.alert(..); event.rc=false}`, including the reversed `5 > event.value`
- `/re/.test(event.value)`, either as `if (!…)` or as `event.rc = …`

It also has built-in checks:
- required
- maxLen
- AFNumber / AFPercent: the value must be numeric
- AFDate: the value must parse with the field's pattern
- AFSpecial: the digit count for zip, zip+4, phone and SSN

A script it cannot read accepts the value.

**`FormSubmit.kt`**
- `ButtonAction`: Submit, Reset, OpenUri, Print, ImportImage.
- SubmitForm flags: Include/Exclude, IncludeNoValueFields, ExportFormat (HTML), GetMethod, XFDF, SubmitPDF.
- The field subset to send.
- Request bodies: HTML url-encoded, XFDF and FDF. FDF uses UTF-16BE hex strings for non-ASCII values.
- A JavaScript fallback reads `this.submitForm({cURL, cSubmitAs})`, `resetForm()`, `app.launchURL()`, `print()` and `buttonImportIcon()`.

**`FormSubmitter.kt`**
- Sends the request with OkHttp: POST of FDF, XFDF, HTML or PDF, or GET for HTML with the GetMethod flag.

**`FormModel.kt`**
- `FormModelLoader.load(file, pw)` returns `FormModel`:
  - every widget (`FWidget`): kind, page, annotation index, display-space box
  - the on-value of check boxes and radios (radio `/Opt` export values are resolved)
  - choice display texts and export values
  - flags: read-only, required, multiline, password, comb, multi-select, editable combo, maxLen, quadding
  - `/DA` font size and colour, `/MK /BG` colour
  - the button action and the validate spec
  - current values in FormOps conventions
  - the `FormCalcModel`, the XFA kind, page `/Tabs`, and whether the file was generated from XFA
- Image fields are push buttons whose action is `buttonImportIcon`.

**`FormValueWriter.kt`**
- Writes values through `FormOps.fillFields`, which gives Arabic font and shaped appearances, formatted appearances with the raw `/V`, and `dropXfa`.
- Writes picked pictures into image buttons: a new `/AP /N` and `/MK /I`, with `/TP 1`.

**`XfaGenerated.kt`**: dynamic XFA turned into a generated AcroForm PDF.
- One row per data-bound field: caption plus a real field. A heading is drawn for each subform.
- The layout is right-to-left when the captions are Arabic. Captions use Noto Naskh with ArabicShaper and `showArabic`.
- Field mapping:
  - text, number, date, password → text fields
  - check → check box
  - exclGroup → radio, with the option captions drawn
  - choice → combo
  - signature → empty signature field
  - image → caption only
- The fields are pre-filled from the current data.
- The mapping is stored in the document info dictionary (`SmartDocsXfaSource`, `SmartDocsXfaMap`).
- `writeBack()` writes the values into the original document's datasets through `XfaSupport.saveValues`. Check-box values and choice export values are converted back.

**`FormBuilderExtras.kt`**
- `ExtraKind.Date(pattern)` adds AFDate format and keystroke actions to a text field.
- `ExtraKind.Image` turns a push button into an image field: `buttonImportIcon` action, no caption, icon only.

### UI (`app/src/main/java/com/smartdocs/editor/ui/forms5/`)

**`FormFillState.kt`**
- `FormFill.of(readerState)` keeps one state per reader in a WeakHashMap, so nothing needs to be added to ReaderState for it to work.
- `FormFillState` holds:
  - the model, loaded again whenever the session, file or `renderVersion` changes
  - `edits`, picked `images`, and `computed` (live recalculation in `/CO` order with the existing `FormCalcModel`, re-run on every keystroke)
  - the active field, the Next/Previous `order`, own undo/redo (typing in one field is one step), validation `errors`, autocomplete, and button actions
- Save, export and discard API: `flushOnSave`, `exportSource`, `discard`, plus `markDirty`.

**`InPageFormLayer.kt`**: `InPageFormLayer(reader, pageIndex, g: PageGeometry)` is the per-page layer.
- Highlights fields when `settings.highlightFields` is on. Required fields get a red frame; fields that failed validation get a thick red frame.
- Draws pending values over the bitmap:
  - text: formatted (AF*) and aligned (quadding or Arabic), comb cells, password dots
  - check marks and radio dots
  - choice labels
  - picked pictures
  - night-mode colours
- Has tap targets on field boxes only. Check boxes and radios get at least 36 dp.
- Text fields and editable combos get an in-place `BasicTextField` on the widget rect:
  - font size from `/DA` × zoom (auto fits the height; never below 12 sp)
  - `TextDirection.Content`, `maxLen`, password masking
  - Decimal or Phone keyboard from the keystroke action
  - IME Next / Done
- Combo and list boxes open an anchored `DropdownMenu`. List boxes are multi-select when the field allows it.
- Image fields open the picture picker.
- The layer is inactive while an annotation tool, a placement or a text selection is active.

**`InPageFormBar.kt`**: `InPageFormBar(reader, modifier)` is the field bar above the keyboard (`imePadding`).
- Previous / next, field name with "n of N".
- Date picker (UTC-correct) for date fields.
- Toggle for check boxes and radios, dropdown for choices, clear, undo / redo, done.
- Autocomplete chips. For editable combos the chips are the combo's options.
- The validation message. `BackHandler` closes the field first.
- Also hosts the confirmation for button actions (submit, reset, open link), the date picker dialog and short feedback messages.

**`FormActions.kt`** runs button actions.
- Submit:
  - required fields are checked first; the first empty one gets focus
  - check boxes are sent as their on-state or `Off`
  - the PDF format sends the filled document
  - `mailto:` opens the share sheet with the data file attached, plus recipients and subject
- Reset (all fields or only the listed ones) is a single undo step.
- Open URI is limited to http(s) and mailto.
- Print shows a hint.

**`InPageXfaBanner.kt`**: `InPageXfaBanner(reader, onOpen, modifier)`.
- On a dynamic XFA document: "open as fillable form" and "convert to normal PDF" (the generated form, flattened).
- On a form generated from XFA: "save into the original XFA". It saves the reader first, then writes back.

### Edited (own area)

**`ui/forms/FormBuilderScreen.kt`**
- Two new chips, **Date** and **Image**, next to the field types.
- The fields are created as text / button fields, then converted with `FormBuilderExtras.apply` on save.
- A drawn field is identified by page + rect (`extraKey`).

### Tests (`app/src/test/java/com/smartdocs/editor/core/forms5/`)

| Test file | Covers |
|---|---|
| `FieldNavigationTest` | rows LTR and RTL, `/Tabs /C`, pages, non-stops, wrapping, page rotation 0/90/180/270 and crop offset |
| `AutocompleteStoreTest` | key normalisation, ranking, global fallback, Arabic folding, cap, JSON round trip |
| `FieldDatesTest` | UTC formatting under a negative-offset zone, parsing including Arabic digits, invalid dates, detection, builder JS |
| `FieldValidationTest` | AFRange, hand-written comparisons, regex, unsupported scripts, built-in checks |
| `FormSubmitTest` | flags, field selection, HTML / XFDF / FDF bodies, JavaScript actions |

## Hooks for the lead (exact)

1. **`ui/reader/PageCanvas.kt`: the page layer.**
   - Anchor: inside `Box(modifier.size(wDp, hDp)) {`, right after the closing `}` of the big `Canvas(...) { … }`, before
     `    if (editTarget != null && editBounds != null && !(draft.active && draft.annotFrame != null)) {`
   - Insert:
   ```kotlin
   com.smartdocs.editor.ui.forms5.InPageFormLayer(state, page, g)      // MODULE_forms5: fill fields on the page
   ```
   - The layer fills the page Box. Only field boxes take taps, so every other page gesture is unchanged.

2. **`ui/reader/ReaderState.kt`: dirty flag.**
   - Anchor: after `var saving by mutableStateOf(false); private set`.
   - Insert:
   ```kotlin
   /** In-page form filling (MODULE_forms5): pending field values, live calculations, field bar. */
   val forms5 = com.smartdocs.editor.ui.forms5.FormFill.of(this).also { it.markDirty = { dirty = true } }
   ```

3. **`ReaderState.save()`: write field values into the same file.**
   - Anchor: inside `saveMutex.withLock {`, right after `val f = file ?: return false`.
   - Insert:
   ```kotlin
   // MODULE_forms5: pending form values are written first (FormOps.fillFields -> swapFile)
   val formsSaved = try { withContext(NonCancellable) { forms5.flushOnSave(f, password) { tmp -> swapFile(tmp) } } }
       catch (e: Exception) { error = e.message ?: e.toString(); return false }
   ```
   - Then, in the `if (annots.isEmpty()) {` branch, replace
   `if (edit.hasBackup) { writeBackResult = runCatching { app.documents.writeBack(f) }.getOrNull().also { com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f) }; edit.committed() }`
   with
   `if (edit.hasBackup || formsSaved) { writeBackResult = runCatching { app.documents.writeBack(f) }.getOrNull().also { com.smartdocs.editor.core.cloud.SaveHooks.onSaved(app, f) }; if (edit.hasBackup) edit.committed() }`
   - When annotations are pending too, the normal path that follows already writes back.

4. **`ReaderState.exportWithAnnots`: save as / share / print include the values.** Replace the body with:
   ```kotlin
   suspend fun exportWithAnnots(out: File) {
       val f0 = file ?: return
       val f = forms5.exportSource(f0, password)          // MODULE_forms5: temp copy with pending field values
       try {
           if (annots.isEmpty()) withContext(Dispatchers.IO) { f.copyTo(out, overwrite = true) }
           else AnnotationWriter.commit(context, f, annots.toList(), out, password)
       } finally { if (f != f0) f.delete() }
   }
   ```

5. **`ui/reader/ReaderScreen.kt`: field bar, XFA banner, discard.**
   - Field bar: make this the last child of the root `Box(Modifier.fillMaxSize().readerTouchWatcher(state)) {`, after the phone `if (!tablet && panel != null) ModalBottomSheet(...) { … }` block:
     ```kotlin
     com.smartdocs.editor.ui.forms5.InPageFormBar(state, Modifier.align(Alignment.BottomCenter))   // MODULE_forms5
     ```
   - XFA banner: in the page area `else -> { … }`, after the `else OcrSuggestionBanner(...)` line:
     ```kotlin
     com.smartdocs.editor.ui.forms5.InPageXfaBanner(state, onOpen = { f -> nav.navigate(Routes.reader(android.net.Uri.fromFile(f).toString(), 0)) },
         modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp))   // MODULE_forms5
     ```
   - Discard: in `DiscardDialog(... onDiscard = { showDiscard = false; state.edit.discardOnClose(); leave() } ...)`, add `state.forms5.discard();` before `leave()`.

6. **Optional: toolbar undo/redo.** The field bar has its own undo/redo. To route the reader's undo buttons to field edits while the bar is open, use:
   ```kotlin
   if (state.forms5.activeId != null && state.forms5.canUndo) state.forms5.undo() else state.undo()
   ```
   Do the same for redo.

Without hook 1 nothing is shown. Without hooks 2–4 values are shown and edited but not autosaved or written. Hook 5 adds the bar (Next/Previous, date, undo), the XFA banner and the discard behaviour.

No new dependencies. No routes are needed: XFA forms open as normal reader documents through the `onOpen` callback.

## Feature status

| Feature | Status |
|---|---|
| Fill on the page: text box over the field | **DONE** — font from /DA × zoom, alignment, RTL, maxLen, password, numeric/phone keyboard, IME next/done. |
| Check boxes / radios toggle on tap | **DONE** — 36 dp minimum target; radio `/Opt` export values resolved. |
| Combo / list open a dropdown | **DONE** — multi-select list; editable combo = in-place text plus option chips. |
| Field bar above the keyboard | **DONE** — prev/next, n of N, date picker, toggle, dropdown, clear, undo/redo, done, Back closes. |
| Live calculated fields | **DONE** — existing `FormCalcModel` (AFSimple, SFN, simple scripts) re-run on every change, formatted, saved with formatted appearance. |
| Values saved into the same file on reader save | **DONE** (hooks 2–3). |
| Values included in share / print / save as | **DONE** (hook 4). |
| Next / previous navigation order | **DONE** — rows (RTL on Arabic pages), `/Tabs /C` columns, radio group = one stop. |
| Autocomplete of previously typed values | **DONE** — on-device store, Arabic-aware; password fields excluded. |
| Field validation scripts | **PARTIAL** — see "Validation scripts" below. |
| Highlight fields | **DONE** — follows `settings.highlightFields`; required fields red; invalid fields thick red; active field accent frame. |
| Form submit to URL (SubmitForm) | **DONE** — see "Submit" below. |
| Image fields in the form builder | **DONE** — see "Image fields" below. |
| Date fields in the form builder | **DONE** — text field + `AFDate_FormatEx`/`AFDate_KeystrokeEx("dd/mm/yyyy")`; the reader offers the date picker. The builder dialog has no pattern chooser (default dd/mm/yyyy). |
| Static XFA → AcroForm view | **DONE** — its AcroForm widgets are filled in place; the XFA packet is dropped on save so the AcroForm values win (as the forms tool does). |
| Dynamic XFA rendered as a generated form, convertible to a normal PDF | **PARTIAL** — see "Dynamic XFA" below. |
| Undo across annotations and field edits in one stack | **PARTIAL** — the field bar has its own stack; hook 6 is the optional bridge. |

Details for the PARTIAL rows and the longer DONE rows:
- **Validation scripts** — no JS engine.
  - Recognised: `AFRange_Validate`, comparison-and-reject scripts, regex `.test` scripts, plus the AF keystroke rules (number, date, special), maxLen and required.
  - Other scripts accept the value.
  - Validation runs when the user leaves the field. Required fields are checked on submit.
  - A rejected value is kept but marked, with the message in the bar (Acrobat restores the old value).
- **Submit**
  - The SubmitForm action and `this.submitForm` JS. Formats: FDF, HTML (POST or GET), XFDF, PDF.
  - The field subset and the include-empty flag are honoured.
  - The user confirms the host first.
  - `mailto:` goes through the share sheet.
  - Not supported: relative URLs, `IncludeAnnotations`, `CanonicalFormat`, `Embed` flags, and server responses (FDF replies are not merged back).
- **Image fields**
  - Builder: a push button with `buttonImportIcon` and icon-only display.
  - Reader: tapping it picks a gallery image, shown on the page and written as the button appearance on save.
- **Dynamic XFA**
  - Shown as a generated one-column form and filled on the page; values are written back into the original's datasets.
  - "Convert to normal PDF" = the generated form flattened.
  - Missing:
    - The original XFA layout is not reproduced.
    - Repeating subforms show the existing instances only; rows cannot be added.
    - XFA scripts and presence rules are not run.
    - Image fields appear as a caption only.

## Limitations
- No JavaScript engine. Keystroke scripts other than AF* and custom format scripts are not run.
- Pending values are drawn horizontally. On pages with `/Rotate` or a view rotation of 90/270 the positions are right, but the text is not rotated.
- Rich-text fields are edited as plain text.
- A tap on a fillable field box is taken by the field, so a long-press there does not start a text selection. Choosing any annotation tool turns the field targets off.
- Calculated fields whose stored value is stale when the document opens are drawn with the live value. They are written on the next save that has user edits; a computation alone does not mark the document dirty.
- Signature fields are not handled by the layer. Use the reader's signature tool.
- I did not compile the Compose / PdfBox files (no local jars). The lead's first build may need small fixes. The likely spots are icon names (`History`, `CheckBox`, `EditNote`) and `TextMeasurer.measure` named arguments.
