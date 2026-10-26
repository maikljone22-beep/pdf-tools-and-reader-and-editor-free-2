# MODULE forms5b (round 5): form JavaScript, XFA in its own layout, rotated fields

This round finishes three items of `forms5`. It builds on `MODULE_forms5_r5.md`, and every hook from that file is already applied in the code.

- No gradle was run.
- All pure logic compiles with `kotlin-compiler-embeddable 2.3.21`, and **53/53 JUnit tests pass**. That is the 25 old tests plus 28 new ones.
- I also compiled every file in `core/forms`, `core/forms5` and `ui/forms5`, with the Compose compiler plugin. They were compiled against the app's last `classes.jar`, the dependency jars and Rhino.
  - The only errors are 3 new `R.string.f5_*` keys: `f5_alert_title`, `f5_add_row` and `f5_row_added`.
  - The local `R.jar` is out of date. The keys are in both `strings_forms5.xml` files.

## New dependency (the lead adds it)

`app/build.gradle.kts`, in `dependencies { … }`, next to the PdfBox line:
```kotlin
    // Form JavaScript (Acrobat AcroForm + XFA scripts), sandboxed, interpreted mode (MODULE_forms5b)
    implementation("org.mozilla:rhino:1.7.15.1")
```
Why this version:
- It is the 1.7.15 line, which still targets Java 8: its bytecode is major version 52.
- It has no transitive dependencies. The jar is 1.4 MB.
- It runs on Android minSdk 26 in interpreted mode. The code sets `optimizationLevel = -1`, so no bytecode is generated at run time.
- 1.8.x and later need Java 11, so I did not use them.

`app/proguard-rules.pro`, appended. Rhino loads some built-ins by class name, and its shell/debugger tools refer to Swing and AWT:
```
# Rhino (MODULE_forms5b)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
```
Packaging needs nothing more. Rhino's `META-INF/LICENSE.txt` and `NOTICE*.txt` are already covered by the existing `META-INF/LICENSE*` and `META-INF/NOTICE*` excludes.

No other hooks are needed. The public APIs are unchanged: `InPageFormLayer(state, page, g)`, `InPageFormBar`, `InPageXfaBanner`, `FormFill`, `flushOnSave`, `exportSource`, `discard`, `XfaGenerated.generate` and `XfaGenerated.writeBack`.

## Files

### New — `core/forms5/`

**`AcroJs.kt`** — the Rhino runtime.
- Sandbox:
  - `initSafeStandardObjects`, so there is no `Packages`, `java` or `JavaImporter`.
  - `JsSandbox`: a ClassShutter that denies every class, no E4X, a bounded interpreter stack, and a per-event deadline enforced by the instruction observer (`JsTimeout`).
- The global object is the Doc.
- Document-level scripts (`/Names /JavaScript`) run once at start-up.
- Field scripts are compiled once per text and cached.
- `run(kind, target, script, event, values)` returns `JsResult`: rc, value, change, selection, alerts, the values other fields got, and changed properties.

**`AcroJsLib.kt`, `AcroJsAf.kt`** — the runtime's JavaScript, plain ES5 in raw strings.
- **Field:**
  - Properties:
    - value and type: `value`, `valueAsString`, `rawValue`, `name`, `type`, `defaultValue`
    - visibility and state: `display`, `hidden`, `readonly`, `required`
    - colours: `textColor`, `fillColor`, `strokeColor` (with their aliases)
    - text: `textSize`, `textFont`, `userName`, `alignment`, `charLimit`, `multiline`, `password`, `comb`
    - choices and layout: `exportValues`, `numItems`, `currentValueIndices`, `page`, `rect`, `calcOrderIndex`
  - Methods: `isBoxChecked`, `checkThisBox`, `getItemAt`, `setItems`, `insertItemAt`, `deleteItemAt`, `clearItems`, `getArray` (parent names), `buttonGetCaption`, `buttonSetCaption`, and no-op `setFocus`, `setAction` and `buttonImportIcon`.
  - Values follow Acrobat:
    - Numeric text comes back as a number.
    - A check box gives its on-value or `"Off"`.
    - A multi-select list gives an array.
- **Doc (global):** `getField`, `getNthFieldName`, `numFields`, `numPages`, `calculateNow`, `resetForm`, and no-op submit, print and mail calls.
- **`event`:** `value`, `change`, `changeEx`, `willCommit`, `rc`, `target`, `targetName`, `selStart`, `selEnd`, `keyDown`, `commitKey`, `source`.
- **`app`:**
  - `alert` (string or object form). The texts are collected for our dialog.
  - `beep`, `response` (returns null), `viewerType`, `viewerVersion`, `platform`, timers.
- **Other globals:** `console.println`, `global`.
- **Constants:** `color` (with `equal` and `convert`), `display`, `border`, `style`, `font`, `highlight`, `position`, `scale*`.
- **`util`:** `printd` (formats 0/1/2 and patterns), `printf` (`%d %f %s %x`, the `,n` separator styles, flags, width and precision), `printx`, `scand`.
- **AF helpers:**
  - `AFNumber_Format` and `AFNumber_Keystroke`: all separator, negative (red / parentheses) and currency styles.
  - `AFPercent_Format`, `AFPercent_Keystroke`.
  - `AFDate_FormatEx`, `AFDate_KeystrokeEx`, `AFDate_Format`, `AFDate_Keystroke`. The date parser follows AFParseDateEx: lenient, accepts month names, and accepts ISO input in d/m/y fields.
  - `AFTime_*`.
  - `AFSpecial_Format`, `AFSpecial_Keystroke`, `AFSpecial_KeystrokeEx` (masks).
  - `AFSimple_Calculate`, including parent field names.
  - `AFRange_Validate`.
  - Utilities: `AFMakeNumber` (comma or dot decimals, grouping, Arabic-Indic digits), `AFMergeChange`, `AFExtractNums`, `AFExactMatch`, `AFParseDateEx`.
  - AF rejections are reported as typed issues, so the existing Arabic and English messages are used (`Issue.NotNumber`, `NotDate`, `BadFormat`, `OutOfRange`). Plain `app.alert` texts are shown as written.

**`FormScripts.kt`** — the document's field scripts on the runtime. It is thread-safe, and only value differences are sent to the runtime.
- `keystroke`: the change and selection while typing. It may reject the change or rewrite it.
- `commit`: K with willCommit, then V. A rejection carries the issue and the alert text.
- `calculate`: every C script in /CO order (the rest of the C fields are appended).
- `format`: F, cached.
- Pattern-based fallback:
  - `ran = false` or null means that no script ran: no engine, a syntax error, a run-time error or a time-out. The caller then uses the old pattern-based logic, which also accepts unknown scripts.
  - If Rhino cannot start (for example the class is missing), every call reports "no engine". FormCalc, AfFormat and FieldValidation then work exactly as before.

**`FieldRotation.kt`** — pure maths for rotated fields:
- the text angle on screen: page `/Rotate` − `/MK /R` + view rotation
- the upright frame (sides swapped for 90 and 270) and the frame → screen mapping
- the `/MK /R` matrix, the quarter-turn angle of a matrix, and the appearance mismatch test

**`XfaRender.kt`** — PdfBox renderer of the XFA layout.
- Draws boxes (fill, 4 edges, dashed), lines, arcs and ellipses, text, and images (JPEG or lossless).
  - Text is Helvetica, bold or italic, and the Noto Naskh font with `ArabicShaper` and `showArabic`.
  - Text is aligned horizontally and vertically.
- Creates real AcroForm fields at the laid-out positions:
  - text: Q, maxLen, comb, multiline, password, read-only, required
  - check box, with its own on and off appearances
  - one radio group per exclGroup, whose state names are the XFA item values
  - combo or list, with export and display values
  - signature

**`XfaRepeat.kt`** — the "add row" places, stored in the generated PDF's info dictionary (`SmartDocsXfaRepeat`) as JSON.

### New — `core/forms/`

**`XfaTemplate.kt`** — the XFA template model and parser.
- Units: mm, cm, in, pt, px; a bare number is inches.
- It reads:
  - subforms, subformSets, areas, exclGroups, fields and draws
  - x, y, w, h, the min and max sizes, `anchorType` and `colSpan`
  - `layout`, `presence`, `occur` (min, max, initial), `bind` (once, none, global, dataRef), `columnWidths`, `keep`
  - breakBefore and breakAfter, and the old `<break>`
  - margins, borders (edges, corner radius, fill) and fonts (size, weight, posture, colour)
  - para: hAlign, vAlign, spaceAbove and spaceBelow
  - captions: placement, reserve, font, para
  - the ui type: textEdit (with `multiLine` and comb), numericEdit, dateTimeEdit, passwordEdit, checkButton (size and shape), choiceList (`open`, `textEntry`), signature, button, imageEdit, barcode
  - items (display and save), default values, rich text (`exData` paragraphs become lines), embedded base64 images, access, `nullTest`, tooltips
  - pageSets, pageAreas, contentAreas, the medium (with landscape) and master-page content
  - `initialize` and `calculate` scripts, JavaScript only

**`XfaMerge.kt`** — template × data.
- Builds the instances of repeating subforms: the data instance count, otherwise `initial` or `min`, capped by `max`.
- Computes the data paths in the `XfaSupport` convention, so `saveValues` writes them back unchanged.
- Takes field and exclGroup values, and image data from the data or the template.
- Records `XRepeatInfo` (count, max, can add).

**`XfaLayout.kt`** — measuring and pagination.
- Positioned subforms: anchors, growable sizes.
- `tb`.
- `lr-tb` and `rl-tb`: rows wrap, and right-to-left rows run from the right.
- `table` and `row`, using `columnWidths`.
- Field captions use left, right, top or bottom placement, with reserve or the measured size.
- Covers ui borders and margins, field and subform borders, and check boxes inside or outside exclGroups.
- Draws: text with wrapping, lines (slope), rectangles, arcs, images.
- Presence: hidden and inactive take no space; invisible keeps its space but nothing is drawn.
- Pages:
  - Uses content areas in order, page areas by name or id, and page-area `occur max`.
  - Breaks before or after, to a page area or the next content area.
  - Flowed content that does not fit continues in the next content area or page. A container's border is drawn per fragment, under its children.
  - Master-page content is drawn on every page that uses the page area.
  - "Add row" places are emitted after the last instance of each group that can grow.

**`XfaJs.kt`** — XFA JavaScript (`initialize`, then `calculate` run twice) on the same sandbox.
- Object model:
  - Node properties: `rawValue`, `formattedValue`, `isNull`, `presence`, `name`, `index`, `parent`, `className`, `somExpression`, `nodes`, `instanceManager.count`, `resolveNode` and `resolveNodes`, and its named children.
  - Globals: `xfa.resolveNode`, `xfa.form.<root>`, `xfa.host.*`, `xfa.layout.pageCount()`, `app.alert`.
- Names resolve like XFA: the container, then its ancestors.
- A calculate script's last expression becomes the value.
- FormCalc is skipped and the stored value is kept.

**`JsSandbox.kt`** — the shared Rhino `ContextFactory` described under `AcroJs.kt`.

### Changed (own area)

**`core/forms5/FormModel.kt`**
- Collects the raw K, F, V and C JavaScript of every field and the document-level scripts.
- Builds the JS field list: type, value, on-value, exports, items, flags, colours, page, rect, tooltip, default value.
- Builds the full calculation order and stores all of this as `FormJs`.
- `FWidget` gets `mkRotation` (`/MK /R`, or the quarter turn of the appearance `/Matrix` when `/MK /R` is missing) and `pageRotation`.
- `FormModel` gets `js` and `xfaRepeats`.

**`core/forms5/FormValueWriter.kt`**
- New `AppearanceRotation`, which runs before `FormOps.fillFields` on the fields being written:
  - A widget whose stream is turned by its matrix but has no `/MK /R` gets that `/MK /R`.
  - A stream whose matrix or BBox does not match `/MK /R` is dropped. PdfBox then makes a fresh one with the right BBox and matrix (it reuses an existing stream's BBox and Matrix otherwise).
- Picked pictures for image buttons are drawn in the turned BBox with the rotation matrix.

**`core/forms5/XfaGenerated.kt`**
- `generate` now builds the form in its own layout: parse → merge → XFA JS → layout → render. The text and choice values get their appearances through `FormOps.fillFields`, which handles Arabic.
- The old one-column generator remains as the fallback (`generateList`).
- New `addRow(context, generated, repeat, out)`: writes the values back, adds a data instance, and generates the form again.
- In `writeBack`, an unset radio group now gives "" instead of "Off".

**`core/forms/XfaSupport.kt`**
- `saveValues` now shares `rewriteData` with the new `addInstances(src, parentPath, name, count, out)`. A new instance is a copy of the last one with its values cleared, or an empty element.
- New `dataRoot(bytes)`.

**`ui/forms5/FormFillState.kt`**
- `scripts` (`FormScripts`): the engine starts off the main thread on load.
- Calculation: `computed` comes from the JS calculation. Fields whose script failed fall back to `FormCalcModel`, and with no engine the old path runs unchanged.
- Script properties: `props()`, `isHidden()` and `isReadOnly()` give the properties changed by scripts.
- Typing and display:
  - `keystroke()` handles K while typing.
  - `display()` uses F, with `AfFormat` as the fallback.
  - `pending()` uses F for the saved appearance text.
- Commit:
  - `commitActive()` runs K with willCommit, then V.
  - A rejected value is replaced by the previous accepted value, as in Acrobat. The field keeps the focus, the field is marked, and the alert dialog shows the script's text or the localised AF message.
  - Without scripts, `FieldValidation` runs as before.
  - `focus`, `step`, `done` and `tap` respect the rejection.
- XFA rows: `addRow(repeat)` saves, adds the row, regenerates, then `swapFile`s and writes the source back.
- `FormMessage.RowAdded`.

**`ui/forms5/InPageFormLayer.kt`**
- Pending values (text, comb, password, check, radio, choice, picture) are drawn in the widget's upright frame, rotated by `FieldRotation.textAngle(pageRotation, mkRotation, view rotation)` about the widget centre.
- The in-place `BasicTextField` is laid out in that frame and turned with `graphicsLayer { rotationZ }`.
- Each text change goes through the K script.
- Script properties are honoured: hidden, read-only, text colour, fill colour.
- Generated XFA forms get an «إضافة صف» / "Add row" button at the bottom end of the last instance of each repeating subform that can still grow.

**`ui/forms5/InPageFormBar.kt`**
- An `AlertDialog` for `app.alert` and rejections ("Message from the form").
- The `RowAdded` message.

**`res/values*/strings_forms5.xml`**
- New keys: `f5_alert_title`, `f5_add_row`, `f5_row_added`.
- `f5_xfa_dynamic_body` is reworded: the form now opens in its original layout.
- Both files have 44 keys.

### Tests (`app/src/test/java/com/smartdocs/editor/core/forms5/`)

**`FormScriptsTest`** — 12 tests, run on real Rhino:
- AFRange_Validate
- regex validation of a ZIP code with `app.alert`; the leading zero is kept
- a custom cross-field date validation with an object-form `app.alert`
- AFNumber_Format with `$` and `€` currencies and the red / parentheses negative styles
- AFNumber_Keystroke: typing letters, Arabic-Indic digits, and a commit that is not a number
- date keystroke and format: 31/02 is rejected, 29/02/2024 is accepted, ISO input is accepted
- a keystroke script that rewrites the change
- a calc chain of 5 fields in /CO order: lines → `AFSimple_Calculate` SUM → tax → total with a VIP discount and `util.printf`; the total script hides the tax field
- a document-level function
- the sandbox: `java.lang.System.exit` is denied, `while(true)` stops at the limit, `Packages` is undefined
- a syntax error accepts the value
- the Field API: choice items, `currentValueIndices`, `checkThisBox`, `getField` of an unknown name
- `util.printd`, `printf`, `printx` and `scand`

**`XfaLayoutTest`** — 10 tests on hand-written templates, checking field rects and page counts:
- units
- a positioned 2-page form: caption reserve, check box with a right caption, title draw, line, ui border, a hidden field, a multiline field on page 2
- a flowed form with 40 repeating `lr-tb` rows that overflows to page 2: exact rows per page, the next page starts at the content-area top, an "add row" place
- `rl-tb` with an Arabic caption: right-to-left placement, wrapping, the caption is RTL and right-aligned
- presence: hidden versus invisible
- `breakBefore` to a landscape page area
- `occur` initial and max without data
- an exclGroup becomes a radio group
- JavaScript `calculate` and `initialize` (`resolveNode`, presence); FormCalc is skipped and the stored value stays
- `XfaRepeat` JSON round trip

**`FieldRotationTest`** — 6 tests: the angle combinations, frame swapping, the rotated frame covers the rect, the text start corner per angle, the appearance matrix and angle, mismatch detection.

The 25 earlier tests still pass. Total: **53 tests, OK**.

How I ran them (scratch harness, no gradle):
- Script: `C:\Users\User\AppData\Local\Temp\claude\D--pdf\fdc86c7c-3184-4367-a941-8255d2dde9f3\scratchpad\forms5\t.sh`. It uses Kotlin 2.3.21, kotlinx-serialization 1.8.0, junit 4.13.2 and `rhino-1.7.15.1.jar`.
- Two stubs stand in for the PdfBox-dependent `FieldScripts` and `XfaSupport.XType`.
- The Compose and PdfBox type check is `scratchpad\forms5\kf5.sh`.

## Status

### 1. Form JavaScript — **DONE**

- K while typing, F for display and for the saved appearance, V and K-commit on leaving a field, C in /CO order. A rejection restores the previous value and shows the alert, as in Acrobat.
- Document-level scripts run once at start-up.
- Sandbox: no Java, a time and instruction limit, and errors count as accept.
- Fallbacks:
  - Pattern-based code is kept as the fallback: FormCalcModel, AfFormat and FieldValidation.
  - It is used when the engine is missing or a script fails.

Limits:
- `app.alert` in calculate scripts is not shown, because they run on every keystroke.
- `app.response` returns null.
- Field property changes from scripts (hidden, read-only, colours, items) apply in the reader only and are not written into the PDF.
- `setAction`, `setFocus` and `submitForm` inside scripts are no-ops. Submit buttons still work through the existing `FormSubmit` path.
- Calculations run on the main thread, with a 250 ms limit per script.

### 2. Dynamic XFA in its original layout — **DONE**, with limits

Done:
- positioned and flowed (tb, lr-tb, rl-tb, table and row) subforms
- units, captions with reserve, borders, fills, fonts, margins
- draws: text, lines, rectangles, arcs, images
- page areas, content areas and master-page content
- breaks, and overflow to the next page
- repeating subforms with an «إضافة صف» button, which writes the data, adds an instance and regenerates in place
- presence
- JavaScript `initialize` and `calculate`
- image fields and draws render their embedded image
- values are written back into the original datasets (and with the button, more rows)

Not done:
- `proto` and `use` / `usehref` references are not resolved.
- Picture clauses (`<format><picture>`) are not applied, so values show raw.
- FormCalc is skipped, by design.
- XFA calculations are not live in the generated form. They run when it is generated or regenerated.
- Overflow leaders and trailers, keep-with-next and widow rules are not applied.
- Rounded corners are drawn square.
- Barcodes are drawn as text.
- Image fields are not editable.
- Page-number fields on master pages show their default text.
- Rows can be added but not removed.

### 3. Rotated pages — **DONE**

- Pending values and the in-place editor are rotated by page `/Rotate` − `/MK /R` + the view rotation, so they match the final appearance.
- The saved appearance is regenerated when its BBox or Matrix does not match `/MK /R`, and `/MK /R` is derived from a rotated matrix when it is missing.
- Image buttons get the rotation matrix.

Not done: widgets created by the form builder on rotated pages still get no `/MK /R`. FormBuilder is outside this module.
