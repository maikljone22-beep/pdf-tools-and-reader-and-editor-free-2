# fix/pdf39-forms-3 — row log (pdf-39)

| Row | Status | Where | Note |
|---|---|---|---|
| XFA edit / validate pictures | FIXED | XfaPicture.parse / conforms, XfaTemplate (editPicture, validatePicture), XfaLayout.Widget, XfaRender keys /SDXfaEditPicture /SDXfaValidatePicture, FWidget, FormFillState.canonical / editText / validate (Issue.Picture), InPageFormLayer editor | The editor starts with the value in the edit picture; typed text is read back to the canonical value (saved /V) through the edit or display picture; a value that does not match the validate picture is flagged. Tests XfaPictureTest.parsingTypedText |
| XFA picture: Arabic month / day names, '%' | FIXED | XfaPicture (locale in the category or the field's / container's `locale`, Arabic names and ص / م; '%' scales ×100 both ways) | Tests XfaPictureTest.percentArabicNamesAndLocale |
| 8.28 rich text in the saved appearance | FIXED (Latin) | RichFields.daFor / ensureFont / underlined / underlineAppearance, FormOps.fillIn | Bold / italic switch the field's /DA to Helvetica Bold / Oblique / BoldOblique (added to /DR) before the appearance is generated; underline is drawn under single-line text. Arabic text keeps the embedded Arabic font (one face). Still whole-field style. Test RichFieldsTest.appearanceFaceAndUnderline |
