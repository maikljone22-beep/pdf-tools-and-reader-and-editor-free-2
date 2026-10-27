# Comments and Forms gaps: what was done (feature/nitro-comments-forms)

Work lists: `gaps/cmp_07-08.md` (Nitro sections 7 and 8, with the corrections from `gaps/verify_A.md`), the comment and form items of `gaps/OLD_PLAN_AUDIT.md`, the Foxit rows of sections 24–27 (`gaps/fcmp_24-27_34.md`) and the audio and image rows of section 19 (`gaps/fcmp_14-21_23.md`).

**Nothing was tested on a device.** The JVM unit tests (PdfBox write, then read back) and the compile were checked as described in "Verification" at the end.

**Status words:**
- **DONE:** the logic and a UI entry both exist.
- **PARTIAL:** part of the row is done; the note says what is missing.
- **NOT-FEASIBLE / NOT DONE:** not implemented; the note gives the reason.
- **OWNED BY pdf-3c:** the row belongs to the other session's Fill & Sign work.

**Paths:**
- `F/` = `app/src/main/java/com/smartdocs/editor/core/formsx/`
- `U/` = `app/src/main/java/com/smartdocs/editor/ui/formsx/`
- `A/` = `app/src/main/java/com/smartdocs/editor/core/annot/`
- `RA/` = `app/src/main/java/com/smartdocs/editor/ui/reader/annot/`
- `F5/` = `app/src/main/java/com/smartdocs/editor/core/forms5/`
- `UF5/` = `app/src/main/java/com/smartdocs/editor/ui/forms5/`

**String files** (the same keys in both languages):
- `strings_fx.xml`: the form designer.
- `strings_cmt7.xml`: comments.
- `strings_f8run.xml`: the fill-time form runtime.

Each file exists in `res/values/` (Arabic) and in `res/values-en/` (English).

**Undo:**
- Comment edits are steps in the reader's unified history: pending changes are in-memory steps, stored changes are file steps (`h.fileOp`, `applyInPlace`).
- Fill-time form changes are history steps.
- The form designer keeps its own multi-level undo and redo while it is open. Saving hands the result to the tool result sheet, where "replace the document" becomes one reader history step (`beforeExternalTool`).
- Signed files are saved incrementally (`SignedSave.saveEdited`).

---

## Section 8 (Nitro forms): designer and form data

| Row | Status | Where / note |
|---|---|---|
| 8.9 Barcode field (PDF417 / QR / Data Matrix, X dimension, Y/X ratio, ECC, compression, field list, tab or XML payload) | DONE | `F/FxBarcodes.kt`: zxing encoders, settings in /SDBarcode plus Acrobat's /PMD, a calculate script, and `refreshAll` after filling (also called by the fill runtime). The symbol is drawn as the field's appearance (`F/FxAppearance.barcodeAp`). Barcode kind and its Options tab are in `U/`. |
| 8.12 Visibility (visible / hidden / no print / hidden but printable) | DONE | `FxAppearance.annotFlags`; General tab. |
| 8.13 Read only | DONE | Ff bit 1 (`FxEditor.writeProps`); General tab. |
| 8.15 Rotation 0/90/180/270 | DONE | /MK /R plus a rotated appearance matrix. |
| 8.16 Locked | DONE | Widget /F Locked. The designer refuses move, resize, align and delete until the field is unlocked (lock button, `FxEditor.setLocked`). |
| 8.17 Edit / move / resize / delete existing fields | DONE | `F/FxEditor.kt` (`readAll`, `update`, `setRect`, `delete`, `deleteWidget`) and `U/FormDesignerScreen.kt` (select, drag, corner handle, delete). Reached from the "form_builder" tool and from the reader's Edit tab ("Edit form fields"). |
| 8.18 Font, size, colour | DONE | /DA plus /DR font resources (standard-14 fonts); Appearance tab. |
| 8.19 Border colour, fill, thickness, style (solid / dashed / beveled / inset / underline) | DONE | /MK /BC /BG and /BS /W /S /D, drawn by `FxAppearance.frame`. |
| 8.20 Appearance gallery | DONE | 6 named looks (`FxMerge.preset`) plus "save current look" (`U/FxPrefs.kt`). |
| 8.21 Text alignment | DONE | /Q. |
| 8.24 Scroll long text | DONE | DoNotScroll flag. |
| 8.27 Comb of N characters | DONE | Comb flag plus MaxLen (the AcroForm field option). |
| 8.28 Allow rich text | PARTIAL | The RichText flag can be set. Rich text is not edited at fill time; filled values stay plain text. |
| 8.29 Password / file selection | DONE | Both flags can be set. The fill-time file picker is in the runtime section. |
| 8.30 / 8.33 Check box and radio styles | DONE | /MK /CA with ZapfDingbats on and off appearances (`FxAppearance.checkAp`). |
| 8.31 Check box export value | DONE | The on-state name. |
| 8.32 Checked by default | DONE | /DV, /V and /AS. |
| 8.35 Radios in unison | DONE | Ff bit 26. |
| 8.36 Combo / list items with export values | DONE | /Opt pairs; the item editor has a separate export value. |
| 8.37 Up / down order, sort | DONE | Item up and down buttons; Sort flag. |
| 8.38 Editable combo | DONE | Edit flag. |
| 8.39 Multi-select list | DONE | MultiSelect flag. |
| 8.40 Commit on selection | DONE | CommitOnSelChange flag. |
| 8.41 Selection change script | DONE | /AA /K on choice fields. |
| 8.42 Push button layouts (7) | DONE | /MK /TP 0–6; label and icon are placed in the appearance. |
| 8.43 Behaviour (none / push / outline / invert) and up / down / rollover states | DONE | /H, /MK /CA /RC /AC and the /AP /N /R /D appearances. |
| 8.45 Button icon (choose / clear) and placement | DONE | `FxEditor.setIcon` and `iconFromImage`; /MK /I and /IF (`FxAppearance.iconMatrix`). |
| 8.47 Number format | DONE | AFNumber with decimals, separator, negative style and currency (`F/FxScripts.kt`). |
| 8.48 Percentage | DONE | AFPercent. |
| 8.49 Date pattern | DONE | AFDate_FormatEx, preset or custom pattern. |
| 8.50 Time | DONE | AFTime, 4 styles. |
| 8.51 Special formats and mask | DONE | AFSpecial (zip, zip+4, phone, SSN) and AFSpecial_KeystrokeEx (mask). |
| 8.52 Custom format / keystroke script | DONE | |
| 8.53 Validate range / custom script | DONE | AFRange_Validate or a custom script. |
| 8.54 Calculate SUM / PRD / AVG / MIN / MAX | DONE | AFSimple_Calculate, with a field picker. |
| 8.55 Simplified field notation | DONE | The BVCALC/EVCALC comment plus the equivalent JavaScript. |
| 8.56 Custom calculation script | DONE | |
| 8.57 Calculation order | DONE | /CO is kept in step with the calculated fields; Calculation order dialog. |
| 8.59 / 8.60 Triggers: Mouse Up / Down / Enter / Exit / Focus / Blur (authoring) | DONE | `F/FxActions.kt` `writeTriggers`; Actions tab (`U/ActionsEditor.kt`). Fill-time running is in the runtime section. |
| 8.61 Go to page, named destination or another document | DONE | GoTo (page and zoom), named GoTo, GoToR. |
| 8.62 Open web link | DONE | URI action. |
| 8.64 Run JavaScript | DONE | JavaScript action. |
| 8.65 Show / hide fields | DONE | Hide action. |
| 8.66 Reset form (all / except / just these) | DONE | ResetForm action. |
| 8.67 / 8.70 Submit (URL / mailto, FDF / XFDF / HTML / PDF, field list, exclude, empty fields, canonical dates, comments, GET) | DONE | SubmitForm flags. |
| 8.68 Import form data action | DONE | ImportData action. |
| 8.69 Named, SetOCGState and Read article actions; ordered action list | DONE | The list has up / down / edit / delete and is written as a /Next chain. |
| 8.72 Signature field: lock fields when signed, run a script | DONE (form side) | `F/FxSigLocks.kt` writes /Lock (SigFieldLock) and the on-sign script (/SDOnSign). `FxSigLocks.lockedFields` is the helper for the signing code. **The FieldMDP transform inside the signature belongs to feature/nitro-protect-sign (pdf-3c).** |
| 8.73 Form data: FDF / XFDF / XML / CSV / TXT | DONE | `F/FormData.kt`. Import / export menu in the designer, the Forms tool and the reader form menu. |
| 8.76 Collect returned forms into a responses file | DONE | `FormData.collect` writes one CSV row per form and can append to an existing CSV; designer menu "Combine forms to a sheet". |
| 8.77 Tab order: rows / columns / structure / unspecified / manual | DONE | Manual order by tapping fields in order (`FxEditor.setManualTabOrder`); tab numbers shown on the page. |
| 8.78 Make copies | DONE | `FxEditor.makeCopies` (rows × columns, spacing, same name); `duplicateToPages` (all / odd / even / range). |
| 8.79 Inherit properties / set as default | DONE | `FxEditor.inherit`; per-type defaults (`FxPrefs`). |
| 8.80 Align / distribute / size / centre on page | DONE | `F/FxLayout.kt`; selection bar. |
| 8.81 Properties on tap | DONE | Double-tap or the Properties button, for existing fields too, and for several fields at once (`FxMerge.apply`). |

## Section 8 (Nitro forms): fill-time runtime

| Row | Status | Where / note |
|---|---|---|
| 8.29 File-select fields at fill time | DONE | `F5/FormModel` (FF_FILE_SELECT); file picker in `UF5/FormRuntimeUi` and the field bar. |
| 8.58 Auto-calculate switch | DONE | `F5/FormPrefs`; Settings switch; "Calculate now" in the field bar. |
| 8.59 / 8.60 Run widget triggers | DONE | `F5/PdfActions.triggers`, run in `FormFillState`. A tap runs Enter, Down, Up, Exit; Focus and Blur follow the active field. |
| 8.61 GoTo / GoToR on widgets | DONE | Explicit and named destinations; GoToR opens the other PDF when it is found, otherwise says so. |
| 8.64 Arbitrary button JavaScript | DONE | Runs in the sandbox (`FormScripts.action`). |
| 8.65 Hide action | DONE | Undoable; written to the widget flags when saved (`FieldVisibility`). |
| 8.66 / 8.67 Reset / submit inside action chains | DONE | `FormActions.runChain` follows /Next. |
| 8.68 ImportData action | DONE | Uses `FormData`; one undo step. |
| 8.69 Named / SetOCGState / /Next chains | PARTIAL | Named page actions, Print and Save work; other named actions show "not supported". SetOCGState changes the default layer visibility and is undoable. There is no layers panel. |
| 8.70 Canonical dates, FDF with comments | DONE | `FormSubmit.canonicalDates`, `fdfWithAnnots`. |
| 8.71 Submit response viewer | DONE | HTML in a WebView with JavaScript off, or plain text. |
| 8.82 Highlight and required-field colours | DONE | Settings colour rows, used by `InPageFormLayer`. |
| 8.84 JavaScript doc API | PARTIAL | Real: submitForm, print, mailDoc, mailForm, exportAsFDF/XFDF, importAnFDF/XFDF, gotoNamedDest, pageNum, getURL/launchURL, getPageNthWord, getPageNumWords, flattenPages, setFocus, buttonImportIcon, browseForFileToSubmit, execMenuItem. removeField hides the field. saveAs, closeDoc, addScript, scroll, syncAnnotScan, exportDataObject, addField and setLock show "not supported". |

## Section 7 (Nitro comments)

| Row | Status | Where / note |
|---|---|---|
| 7.8 Pop-up annotations | DONE | A closed /Popup is written for markup comments (`A/AnnotStyle.addPopup`). |
| 7.10 Pop-up open / close, reset location | PARTIAL | Open all, close all and reset location are in the comments menu (`A/CommentOps7`). There is no floating pop-up window; notes open in a dialog. |
| 7.14 Snap toggle for all tools, snap to grid | DONE | `Workbench.kt`, `WorkbenchCanvas.resolve`. |
| 7.23 Fill for rectangle and oval | DONE | /IC: creation bar, writer, preview and read-back. |
| 7.26 Dash styles on the creation bars | DONE | /BS /D for rectangle, oval, line, arrow, polygon, polyline and ink. |
| 7.27 Cloudy 1–3 | PARTIAL | Cloud polygon at levels 1–3 and a cloudy rectangle (/BE). There is no cloudy oval. |
| 7.28 No border | DONE | /BS /W 0, for filled shapes. |
| 7.30 Leader lines | DONE | /LL /LLE /LLO, with a dialog. |
| 7.35 11 missing standard stamps | DONE | Plus Emergency and Verified (`StampPickerSheet`). |
| 7.38 Stamp from a PDF page | DONE | `StampSources.fromPdfPage`. |
| 7.40 Numeric stamp size and ratio lock | DONE | `PlacementBar`. |
| 7.41 Recent / favourite stamps, categories | DONE | |
| 7.42 / 7.67 Flatten one stamp / the selection | DONE | `AnnotStyle.flatten`. |
| 7.47 Per-page / per-document scale | DONE | Stored in /VP (`core/view5/ScaleStore`). |
| 7.48 Multiple-scale warning | DONE | |
| 7.51 Scale description | DONE | |
| 7.52 / 7.53 / 7.55 Author / subject / name per annotation | DONE | Properties dialog, for pending and stored annotations. |
| 7.58 Locked annotations | DONE | /F 128 and 512. Locked annotations refuse move, edit and delete. |
| 7.63 Expand / collapse all | DONE | `CommentsPanel`. |
| 7.64 Sort by type | DONE | Also by status, colour and check mark. |
| 7.65 Hide / show comments | DONE | Pages render without annotations. Limitation: PDFium also hides form-field appearances (the in-page form layer still shows the fields). |
| 7.70 Summary options | DONE | Sort, text size, paper, layout, empty pages, replies, status, page range. |
| 7.71 Print with a comments summary | DONE | `PrintSheet5`. |
| 7.72 Print sort orders | DONE | |
| 7.73 Identity: unit and e-mail | PARTIAL | Added to the stamp identity ({unit}, {email}). The comment author name in Settings is still a separate setting. |
| 7.74 Pop-up note font size and opacity | DONE | |
| 7.76 Align / distribute annotations | DONE | Also same size. |

## OLD_PLAN_AUDIT items

| Item | Status | Where / note |
|---|---|---|
| D2 Snap ink to shapes | DONE | `A/ShapeSnap`; toggle on the ink bar. |
| D4 Dash, line ends and lock on Line/Arrow | DONE | |
| D6 /RC rich text | DONE | /RC plus /DS, read back when editing. |
| Remember settings per tool | DONE | `ToolMemory` (SharedPreferences). |
| CMT-6 Hide comments | DONE | |
| CMT-8 Note icon / author / subject | DONE | 15 note icons. |
| CMT-10 Comment copy / paste | DONE | Cut, copy, paste and paste in place, to other pages and other documents. |
| CMT-11 Align / distribute | DONE | |
| CMT-18 Stamp categories, stamps from PDF pages | DONE | |
| CMT-19 Numbered tickmarks | DONE | Numbers, letters and symbols (TICKMARK tool). |
| H1 Auto field detection on flat forms | DONE | `F/FieldRecognition.kt` finds underscores, check-box glyphs, empty boxes and fill-in lines, and names each field from its label. Designer: "Run form field recognition" (this page / all pages) with a review list. |
| H2 Field properties (comb, appearance, format / validate / calculate authoring, alignment, tab order) | DONE | See section 8. |
| H3 Autocomplete settings and clearing | DONE | On/off switch; list, edit, delete and clear all. |
| H4 FDF / XFDF / CSV / XML form data | DONE | `F/FormData.kt`. |
| H6 Distribute / collect | DONE | "Distribute form" shares the PDF by e-mail or any app; "Combine forms to a sheet" collects returned forms. |
| XFA: remove repeating row | DONE | `XfaSupport.removeInstance`, `XfaGenerated.removeRow`. |
| Forms tool "impossible" dialog for dynamic XFA | DONE | Now offers "Open in reader" (the in-page XFA layer) and "Flatten to PDF". |

## Foxit section 24 (comments and stamps)

| Row | Status | Note |
|---|---|---|
| 24.9 | DONE | Markup start / end adjusted by a word. |
| 24.12 | DONE | Pop-up note font preference. |
| 24.13 | DONE | Sticky-note indicator on markups that have text. |
| 24.18 / 24.66 | PARTIAL | No floating pop-up (see 7.10). |
| 24.19 | DONE | 15 note icons. |
| 24.21 | DONE | 4 attachment icons. |
| 24.24 | DONE | Copy comment. |
| 24.25 | DONE | System SpellCheckerSession; says so when the device has no spell checker. |
| 24.27 | NOT DONE | Dragging callout parts separately needs handles for each part (/CL). |
| 24.31 | DONE | Square / circle constraint. |
| 24.33 | NOT DONE | Inward / outward scallops: PDF has no flag for it, and the renderer always draws outward. |
| 24.40 | DONE | Same width / height / size. |
| 24.41 | DONE | Text inside a new shape is copied into its note. |
| 24.42 | DONE | Caption on plain lines. |
| 24.45 | DONE | Emergency, Expired, Verified. |
| 24.47 | DONE | Stamp shape templates. |
| 24.51 | DONE | Stamp from a PDF page. |
| 24.52 | DONE | Custom stamp name, category, opacity, white made transparent. |
| 24.53 | DONE | Stamp categories. |
| 24.54 | DONE | A comment stamp on a page range. |
| 24.55 | DONE | Clipboard image as a stamp. |
| 24.56 | DONE | Dynamic stamp font, size and colour. |
| 24.57 | DONE | Prompted values (`{ask:…}`). |
| 24.58 | PARTIAL | Edit and delete stamps. No image replace and no shared "admin" stamps. |
| 24.60 / 24.71 | DONE | Cut / copy / paste, paste in place. |
| 24.63 / 24.79 | DONE | Rotate after placement, in 15° steps. |
| 24.65 | DONE | Favourite stamps. |
| 24.67 | DONE | Locked comments. |
| 24.68 | DONE | Author and subject. |
| 24.69 | DONE | Review history list. |
| 24.73 | DONE | Expand / collapse all. |
| 24.74 | DONE | Previous / next comment. |
| 24.75 | DONE | More sort orders. |
| 24.76 | DONE | Colour filter. |
| 24.77 | DONE | Show / hide all comments. |
| 24.83 | DONE | Reply colour and author. |
| 24.85 | DONE | Align / distribute. |
| 24.87 | PARTIAL | Summary options are done. Connector-line layouts are not. |
| 24.90 | DONE | An opened FDF finds its PDF through /F or href. |
| 24.92 | DONE | Export selected comments. |
| 24.93 | DONE | FDF by e-mail. |
| 24.95 | NOT-FEASIBLE | Putting comments into the source Word file would need DOCX comment anchoring to the PDF text. |
| 24.99 | DONE | Font family. |
| 24.101 | DONE | Bold / italic / underline / strike. |
| 24.102 | PARTIAL | Character and word spacing and character scale are done. Superscript and subscript are not. |
| 24.105 | PARTIAL | Bullets are done. Numbering and indent are not. |
| 24.106 | DONE | Justify. |
| 24.107 | PARTIAL | Line spacing is done. Paragraph spacing is not. |
| 24.108 | DONE | Fill, dash and line ends on the creation bar. |
| 24.96 / 24.97 | N/A-ANDROID | As in the source list. |

## Foxit section 25 (measure, calculator, tickmarks)

| Row | Status | Note |
|---|---|---|
| 25.4 | DONE | Leader lines with a remembered default. |
| 25.10 | DONE | Scale read from /VP and from /Measure. |
| 25.12 | DONE | Per-page or all-pages scale. |
| 25.13 | DONE | ΔX/ΔY and an X/Y readout. |
| 25.14 | DONE | Custom label. |
| 25.18 | NOT DONE | Dragging the label separately needs a label-offset model. |
| 25.19 | PARTIAL | End points can be typed in; there are no drag handles. |
| 25.20 | DONE | Flip. |
| 25.23 | DONE | Align / distribute. |
| 25.24–25.26 | DONE | Accounting calculator with tape, which can be placed on the page (`A/CalcTape`). |
| 25.27 | PARTIAL | 10 symbols, not 31. |
| 25.28 | DONE | Number and letter tickmarks. |
| 25.30 | DONE | Select all marks to recolour them; previous / next. |
| 25.1 | N/A-ANDROID | |

## Foxit section 26 (forms)

| Row | Status | Note |
|---|---|---|
| 26.3 | DONE | "This form is fillable" strip (close, don't show again). |
| 26.11 | DONE | Auto-shrink on screen. The saved appearance relies on PdfBox's auto size (not checked against the PdfBox source). |
| 26.14 | OWNED BY pdf-3c | A chip was added before ownership was clarified; it only selects the existing rectangle tool. |
| 26.15 | OWNED BY pdf-3c | A Fill & Sign comb was added (`FormScriptTools`) before ownership was clarified; pdf-3c owns the row. The AcroForm comb flag is done (8.27). |
| 26.18 | DONE | Field recognition (H1). |
| 26.19 | DONE | Designer assistant: in Create mode the detected boxes are outlined, and one tap creates the field. |
| 26.22 / 26.83 | DONE | Barcode field (8.9). |
| 26.24 | DONE | Properties dialog with every tab. |
| 26.25 | DONE | 8.17. |
| 26.26 / 26.29 | DONE | Marquee and long-press multi-select; properties of several fields at once. |
| 26.27 | DONE | Multiple copies (no live preview). |
| 26.28 | DONE | Duplicate across pages (all / odd / even / range, shared value). |
| 26.30 | DONE | Current properties as new defaults. |
| 26.32 / 26.33 / 26.34 / 26.36 | DONE | Visibility, orientation, read only, locked. |
| 26.37 | DONE | Line style, thickness, border and fill colour. |
| 26.38 | PARTIAL | Font, size and colour are done. AcroForm has no LTR/RTL key; right alignment (/Q 2) is the substitute. |
| 26.39 | DONE | Position tab (left, bottom, width, height in points). |
| 26.40–26.43 | DONE | Layouts, icon placement, behaviour, label and icon. |
| 26.44–26.52, 26.55 | DONE | Triggers, GoTo, URL, show/hide, menu item, submit / reset, import, JavaScript, action list. |
| 26.46 | N/A-ANDROID | |
| 26.53 | DONE | Read-article action: authored in the designer and run at fill time. |
| 26.54 | PARTIAL | SetOCGState is authored and run. There is no layers panel. |
| 26.56–26.59 | DONE | Check and radio styles, export value, default, unison. |
| 26.60 | DONE | Radio buttons placed one by one join the selected or last group (`FxEditor.addRadioWidget`). |
| 26.61 | DONE | Choice options. |
| 26.62–26.65 | DONE | Format, validate, calculate, text options. |
| 26.67 | DONE (form side) | 8.72; the FieldMDP write belongs to pdf-3c. |
| 26.68 | PARTIAL | Tapping an XFA signature widget offers the digital-signing tool. The signing screen (pdf-3c's area) cannot target a specific field. |
| 26.71 | DONE | Align, centre, distribute, size to the anchor. |
| 26.72 / 26.73 | DONE | Tab order modes, tab numbers, tap-to-set; Fields list sorted alphabetically or in tab order. |
| 26.75 | DONE | Page templates: go to, rename, replace. |
| 26.76 | DONE | Calculation order dialog. |
| 26.77 | DONE | Static XFA converted to an AcroForm that stays fillable. XFA-only scripts and layout are lost. |
| 26.79 / 26.80 / 26.82 | DONE | FDF / XFDF / XML / CSV / TXT import and export, sending by e-mail, importing one CSV row. |
| 26.81 | DONE | Combine forms into a sheet, or append to an existing CSV. |
| 26.84 | DONE | Auto-complete list management. |
| 26.1 / 26.12 | N/A-ANDROID | |

## Foxit section 27 (JavaScript)

| Row | Status | Note |
|---|---|---|
| 27.3 | DONE | JavaScript on page open / close, bookmarks and links. |
| 27.4 | DONE | Document JavaScript dialog: add, edit, delete. |
| 27.5 | DONE | Document actions WC / WS / DS / WP / DP: edited, and run at the right moments. |
| 27.6 | DONE | JavaScript console. |
| 27.7 | DONE | "Edit all JavaScripts". |
| 27.10 | PARTIAL | Same as 8.84. |
| 27.12 | SKIPPED | Owned by pdf-3c. |
| 27.8 / 27.9 | N/A-ANDROID | |

## Section 19 (audio and image annotations)

| Row | Status | Note |
|---|---|---|
| Add a sound file (.wav / .mp3) | DONE | "Choose a sound file" in the Sound tool. |
| Play audio from other PDFs | DONE | Native /Sound decoded to WAV (raw, signed, μ-law, A-law), and Screen/Rendition audio, played in the app's player (`A/SoundPdf`). |
| Image annotation: opacity, rotate, placement | ALREADY-PRESENT | `AnnotEditUi.kt:76`; placement handles. Rotation after placement was added (24.63). |
| Video: align / distribute / centre | DONE | Through 7.76. |
| Video properties: locked / title / alternate text | PARTIAL | Through the comment properties dialog. |
| Video renditions, media settings, playback windows, defaults, media actions tab | NOT DONE | They belong to the in-page video module (VIDEO_INLINE_r5.md); that module was not changed. |

## Verification

All of the following were done without a device.

- **Plain-JVM check (outside gradle).**
  - Compile: kotlinc with the Compose plugin type-checked all 203 main Kotlin files changed on the branch. It ran against the app's dependency jars, an R class regenerated from the current resources, and the last compiled app classes. There were 0 errors.
  - Tests: the 13 new or changed test classes ran on a plain JVM with android.jar stubs (88 tests).
- **Local gradle build.** The daemon disappeared during the run, so the build did not finish. It was not retried.
- **GitHub CI.**
  - Run 40519463462 (03e8a12): failed.
  - Run 40533043022 (2cc7caa): the APK built, and the unit tests failed. After that run, fixes went into tests that hit PdfBox's AcroForm fix-up and android.util.Log, and into the FDF writer (it now has a cross-reference table).
- The CI result of the final push is given in the session report.
