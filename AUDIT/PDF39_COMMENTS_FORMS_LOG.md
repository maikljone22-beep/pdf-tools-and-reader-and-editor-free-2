# fix/pdf39-comments-forms — row log (pdf-39)

| Row | Status | Where | Note |
|---|---|---|---|
| OLD I1 Fill & Sign on flat forms | FIXED | ReaderState.loadFsTargets/fsTargetAt, PageCanvas (dashed targets, tap → fillTarget), FieldRecognition.at | Uses core/formsx FieldRecognition.scanPage only when the document has no AcroForm fields; box → check mark, line → text prompt sized to the line. Test FormDataTest.fillSignTargetHit |
| 8.73 XDP export / import | FIXED | core/formsx/FormData (DataFormat.XDP, xdp, parseXdp, stripIndices), FormsScreen data menu, mail-data dialog | <xdp:xdp> package with <xfa:datasets><xfa:data> and <pdf href>; import reads xfa:data leaf paths (or an embedded XFDF) and matches index-free paths to "form1[0].Name[0]" fields. Tests exportAndParseEveryFormat, xdpPackageAndIndexedNames |
| 7.27 cloudy oval | FIXED | CloudPath.ellipse, AnnotStyle.shapeStream (oval cloud), Annotations.kt write/read Circle /BE, AnnotationOverlay, Cloud chips for the Oval tool | Test CloudShapesTest |
| 24.33 cloud scallops inward / outward | FIXED | CloudPath.build(inward), AnnotStyle.setCloudy/cloudInwardOf (/SDCloudIn), Annot.cloudInward, Annot2Writer polygon cloud, "Scallops inward" chip, ToolMemory field 14 | Other viewers draw /BE clouds outward; ours keeps the inward look. Test CloudShapesTest |
| 24.102 superscript / subscript | FIXED | Annot.script, AnnotStyle.Rich.script / scriptSize / scriptRise (vertical-align in /DS and /RC), Annotations.textAppearance, AnnotationOverlay, Text format dialog | Applies to the whole text box like the other text-box formats. Test TextScriptTest |
