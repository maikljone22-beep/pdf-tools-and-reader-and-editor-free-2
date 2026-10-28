# fix/pdf39-comments-forms — row log (pdf-39)

| Row | Status | Where | Note |
|---|---|---|---|
| OLD I1 Fill & Sign on flat forms | FIXED | ReaderState.loadFsTargets/fsTargetAt, PageCanvas (dashed targets, tap → fillTarget), FieldRecognition.at | Uses core/formsx FieldRecognition.scanPage only when the document has no AcroForm fields; box → check mark, line → text prompt sized to the line. Test FormDataTest.fillSignTargetHit |
| 8.73 XDP export / import | FIXED | core/formsx/FormData (DataFormat.XDP, xdp, parseXdp, stripIndices), FormsScreen data menu, mail-data dialog | <xdp:xdp> package with <xfa:datasets><xfa:data> and <pdf href>; import reads xfa:data leaf paths (or an embedded XFDF) and matches index-free paths to "form1[0].Name[0]" fields. Tests exportAndParseEveryFormat, xdpPackageAndIndexedNames |
