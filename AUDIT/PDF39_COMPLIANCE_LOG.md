# fix/pdf39-compliance — row log (pdf-39), FINAL_CHECK compliance / accessibility rows outside core/convert6

| FINAL_CHECK row | Status | Where | Note |
|---|---|---|---|
| Standards panel: OutputIntent | FIXED | NpfDocPanels.outputIntents / outputIntentLine, Standards(outputIntents), Reader INFO › Standards row | One line per /OutputIntents entry: subtype, output condition, embedded ICC profile and its channels. Test OutputIntentPanelTest |
| Remove PDF/A information | DONE (stale row) | ui/tools5/PdfAScreen.kt (claims card, per-claim remove) → Standards.removeClaims | FINAL_CHECK was written before stage G (6476d36) was merged |
| Mark as PDF/UA / remove PDF/UA info | DONE (stale row) | same claims card, prefix pdfuaid | idem |
| PDF/X verify / remove | PARTIAL | PreflightProfile.PDFX_4 (approximate preflight); remove via the claims card (also drops GTS_PDFX output intents and the Info keys) | No formal ISO 15930 validation |
| PDF/E convert / verify / remove | PARTIAL | PreflightProfile.PDFE_1 (approximate); remove via the claims card | No PDF/E conversion |
| Verify conformance of a claimed file (formal) | NOT-FEASIBLE | — | A formal validator (veraPDF: validation model + profiles, JAXB / Xerces, about 40 MB of desktop Java) cannot run inside the app; the in-app preflight checks the machine-checkable rules and says so in its report |
| PDF/UA-1 formal (Matterhorn) validation | NOT-FEASIBLE (formal) / PARTIAL | AccessibilityScreen checker + PreflightProfile.PDFUA_1 machine checks | Matterhorn's human-judgement checkpoints cannot be decided by software; the machine checkpoints are covered by the checker |
| 34.49 Find Element | FIXED | core/tools5/AccessFind.find, Tags screen find field + type chips (the tree shows the matches) | By text (title, alt, actual text, id, language) and / or type (also role-mapped). Test AccessFindTest |
| 34.18 New bookmarks from chosen structure elements | FIXED | AccessFind.bookmarks / addBookmarks / elementText (MCID text through PDFMarkedContentExtractor), Tags screen "Bookmarks from structure" dialog | Appended under one new top bookmark; headings keep their level; title = element text, else title / actual / alt. Test AccessFindTest |
