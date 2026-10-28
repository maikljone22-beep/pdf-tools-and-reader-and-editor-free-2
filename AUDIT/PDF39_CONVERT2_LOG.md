# fix/pdf39-convert-2 — row log (pdf-39)

| Row | Status | Where | Note |
|---|---|---|---|
| 93 Web page: one single long page | FIXED | WebPdfWriter.Setup.singlePage (paper as tall as the content: WebCss.longPageMils), WebOpts.singlePage + switch (+ saved defaults) | Height from the laid-out content height at the print width, 3 % slack, at most 200 in. Test WebLongPageTest |
| 93 Web page: create PDF tags | FIXED | tagIfAsked (AccessAutoTag.tag on the result, language from the text), WebOpts.tags + switch, URL and pasted-HTML conversions | The WebView writes no tags; the app's auto-tagger adds headings, lists, tables, artifacts |
| 93 Web page: background graphics | DONE (stale) | WebOpts.backgrounds → WebCss.build(backgrounds) | already a switch in the web options card |
| 93 Web page: headers / footers on a new page, tag-tree optimisation | OPEN | — | no WebView control for running headers; tag tree is the auto-tagger's |
