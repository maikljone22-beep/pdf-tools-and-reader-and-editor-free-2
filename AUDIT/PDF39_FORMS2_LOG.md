# fix/pdf39-forms-2 — row log (pdf-39)

| Row | Status | Where | Note |
|---|---|---|---|
| §37 text field overflow indicator | FIXED | FormPrefs.overflowIndicator / overflowBox, InPageFormLayer.drawPending ("+" box when the text layout overflows), Settings › Forms switch | Fixed-size text fields only (auto-size fields shrink); shown on values typed in this session — saved values are drawn by the page renderer. Test OverflowIndicatorTest |
