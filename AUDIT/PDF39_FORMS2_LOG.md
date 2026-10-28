# fix/pdf39-forms-2 — row log (pdf-39)

| Row | Status | Where | Note |
|---|---|---|---|
| §37 text field overflow indicator | FIXED | FormPrefs.overflowIndicator / overflowBox, InPageFormLayer.drawPending ("+" box when the text layout overflows), Settings › Forms switch | Fixed-size text fields only (auto-size fields shrink); shown on values typed in this session — saved values are drawn by the page renderer. Test OverflowIndicatorTest |
| XFA picture clauses | PARTIAL | core/forms/XfaPicture (num / date / time / text / zero / null, alternatives), XfaTemplate `<format><picture>`, XfaLayout.Widget.picture, XfaRender key /SDXfaPicture, FWidget.picture, FormFillState.display, InPageFormLayer | Display pictures are applied in the in-page layer; edit (`<ui><picture>`) and validate pictures are not; month / day names are English; '%' is not scaled. Tests XfaPictureTest, XfaLayoutTest.pictureClauseReachesTheWidget |
