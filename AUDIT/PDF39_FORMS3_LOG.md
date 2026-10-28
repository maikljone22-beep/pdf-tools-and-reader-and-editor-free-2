# fix/pdf39-forms-3 — row log (pdf-39)

| Row | Status | Where | Note |
|---|---|---|---|
| XFA edit / validate pictures | FIXED | XfaPicture.parse / conforms, XfaTemplate (editPicture, validatePicture), XfaLayout.Widget, XfaRender keys /SDXfaEditPicture /SDXfaValidatePicture, FWidget, FormFillState.canonical / editText / validate (Issue.Picture), InPageFormLayer editor | The editor starts with the value in the edit picture; typed text is read back to the canonical value (saved /V) through the edit or display picture; a value that does not match the validate picture is flagged. Tests XfaPictureTest.parsingTypedText |
| XFA picture: Arabic month / day names, '%' | FIXED | XfaPicture (locale in the category or the field's / container's `locale`, Arabic names and ص / م; '%' scales ×100 both ways) | Tests XfaPictureTest.percentArabicNamesAndLocale |
