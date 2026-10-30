# MODULE edit4 — Edit tab and Organize tab gaps (EDT-*, ORG-*, E*, F*)

Owner: edit4. Packages `core/edit4/*` and `ui/edit4/*`. Strings: `res/values/strings_edit4.xml` and `res/values-en/strings_edit4.xml` (537 keys, prefix `e4_`, the same keys in both files).
No Gradle dependency was added. I also edited `ui/pages/OrganizeScreen.kt` (2 hook lines).

## Verification
- **Core (`core/edit4`):** compiled with kotlinc 2.3.21 against android.jar, PdfBox-Android 2.0.27, PDFium and coroutines, together with `core/pdf` and `core/edit`. No errors.
- **Pure logic:** run on the JVM. Covered page labels (roman, letters, prefix, label→page), URL/e-mail/phone detection, whole-word Arabic regex, bookmark TXT import with Arabic digits and dot leaders, and N-up layouts.
- **UI (`ui/edit4` and the OrganizeScreen hooks):** compiled together with the whole app source tree (Compose compiler plugin, generated R/BuildConfig stubs). No errors in my files. The errors that remain belong to other modules and come from jars missing in my local classpath.
- Nothing was run on a device.

## Files
**Core (`core/edit4`)**
- `E4Common.kt`
  - I/O helpers.
  - Range and subset (all/odd/even/landscape/portrait).
  - Display⇄user geometry.
  - `Marked`: artifact BDC/EMC strip and detect, plus /PieceInfo JSON.
  - `E4Fonts`: bundled Naskh, Arabic/Latin system fonts, standard-14.
  - `E4Line`: shaped multi-font line with /ActualText.
- `LinkOps.kt`: real /Link annotations.
  - Kinds: URI, page (XYZ/Fit/FitH + zoom + "current view"), named destination, remote PDF, launch, e-mail, attachment. Other actions are kept read-only.
  - Appearance: border none/solid/dashed/underline, width, colour, /H mode.
  - Operations: list, add, update, delete, remove all / web links only.
  - Text-URL detection and link writing.
- `MediaLinks.kt`
  - Embed a file and build its link action (GoToE for PDF, Launch with embedded filespec for other files).
  - Insert a `/Screen` annotation with a Rendition action and MediaClip (poster appearance, optional autoplay on /PV).
  - List and extract Screen/RichMedia/Movie media; extract link targets.
- `HeaderFooterOps.kt`
  - 6 cells; tokens `{page} {pages} {date} {file} {title} {author} {bates} {label}`.
  - Font, size, colour, underline, margins, start number, Arabic digits, date format, mirrored even pages, shrink content, range+subset.
  - Content is tagged `/Artifact <</Type/Pagination/Subtype/Header|Footer/SDHF>>`.
  - Update/remove, including other producers' pagination artifacts.
  - Bates: prefix/suffix/digits/start, counter carried across files, `/SDBates`.
- `DecorOps.kt`: watermark and background.
  - Sources: text, image, PDF page, colour.
  - Opacity, rotation, relative/absolute scale, 3×3 alignment + offsets, tiling, behind/on top.
  - Screen/print visibility: OCG with /Usage and /AS.
  - Range+subset, update/remove, presets.
- `PageBoxOps.kt`
  - Crop/Trim/Bleed/Art boxes from display rects; auto white-margin detection (PDFium render); reset.
  - Resize to paper sizes with scaled or centred content (annotations transformed).
  - `PageWrap`.
- `PageLabelOps.kt`: read/write page labels, compute labels, parse a typed label.
- `PresentationOps.kt`
  - /Trans (12 styles, duration, direction, H/V, in/out) and /Dur.
  - Article threads: list, create, delete.
- `Imposition.kt`: N-up (2–16, RTL, column-first, borders), saddle-stitch booklet (right binding), poster tiles (overlap, cut marks).
- `PageOps2.kt`: page operations that keep the outline, links and forms:
  - in-place reorder with copies; reverse; duplicate; swap; interleave;
  - insert range at a position (+bookmark); N blank pages of a size;
  - replace pages, with destinations re-pointed;
  - split a page into parts (lossless CropBox); flatten selected pages; tab order;
  - blank-page / text-marker detection and splitting into parts.
- `ImageExtract.kt`: every image (pages and forms, deduplicated), original JPEG kept, or PNG/JPG.
- `BookmarkStore.kt`
  - Outline rows that keep colour, bold, italic, open state, XYZ/FitH destinations, URI and other actions.
  - Unchanged items are rebuilt from their original dictionaries.
  - Tree operations reuse `OutlineModel`; also sections and where-am-I.
- `BookmarkTools.kt`
  - Heading detection by font size, keyword bookmarks.
  - TXT/JSON export and import.
  - Clickable TOC page(s) inserted at the start (RTL, dot leaders, Arabic digits).
  - Extract or delete a section; rearrange pages by bookmark order.
- `DocPropsOps.kt`
  - /Info incl. custom keys, read-only facts, XMP export/import/regenerate.
  - Initial view (layout, page mode, open page, zoom, viewer prefs, title, full screen).
  - R2L, /Lang, print presets, fonts list.
- `TextReplace.kt`: find & replace and spelling corrections in page content, through `ContentEdit.replaceText`.
- `RichText.kt` + `RichEdit.kt`
  - Rich paragraphs: per-run font/size/colour/B/I/U/S/super/sub/char spacing/scale, alignment incl. justify, spacing, indent, bullets/numbering, direction.
  - Block operations: replace a block, add, rotate, join, split.
  - Linked boxes (text flow across boxes and pages; the chain is stored in /PieceInfo).
- `ObjectOps.kt`
  - Images: flip, any-angle rotate, clip crop, opacity, front/back, place on pages.
  - Copy an annotation to pages.
  - Shapes as page content (line, arrow, rect, rounded, ellipse, polygon; dash, opacity; linear/radial gradient).
  - Vector paths of the page content: scan, delete, recolour, width, move, lasso.
  - Text to outlines (glyph paths through `Path.approximate`).
- `DocStructOps.kt`
  - Layers: list, visibility copy, print state, rename, delete content, merge, flatten, import as a layer.
  - Named destinations.
  - Cross-reference link rings.
  - Embed/remove /Thumb.
- `WebAppend.kt`, `Combine.kt`, `AttachmentEdit.kt`.

**UI (`ui/edit4`)**
- `E4Ui.kt`: `E4Work` working copy with undo, `E4WorkScreen`, `E4PageView` page renderer with overlay and gestures, pickers, savers, small fields.
- Screens:
  - `LinksScreen` and `LinkEditor`
  - `HeaderFooterScreen` (presets, font picker), `BatesScreen`, `DecorScreen`
  - `PageLabelsScreen`, `PageBoxScreens` (crop, resize), `ImposeScreen`
  - `PageInsertScreens` (insert, replace, interleave, split page, ML Kit scan launcher)
  - `PageOpsScreens` (page ops, smart split, extract images, batch insert)
  - `BookmarksScreen` and `BookmarkSheets`, `DocPropertiesScreen`
  - `ContentToolsScreen`, `ContentPanels`, `RichTextEditor`, `ImageEditor`
  - `SpellScreens` (spell check, find & replace, `SpellCheckedField`)
  - `LayersArticles`, `CombineScreens` (combine, copy pages, reflow edit)
  - `OrganizeExtras`
- `ReaderHooks.kt`: reader-side composables (see the hooks below).
- `Edit4Hub.kt`: `EDIT4_TOOLS`, `EDIT4_TOOL_IDS`, the `Edit4Screens` dispatcher, `Edit4HubScreen`.

## Hooks for the lead (exact lines)
1. **`ui/tools/ToolScreens.kt`** — replace the last branch `else -> ComingSoonScreen(nav, id)` with:
   ```kotlin
   else -> if (id in com.smartdocs.editor.ui.edit4.EDIT4_TOOL_IDS) com.smartdocs.editor.ui.edit4.Edit4Screens(nav, id, uri) else ComingSoonScreen(nav, id)
   ```
   Route ids: `edit4_hub`, `content_tools`, `links`, `bookmarks`, `find_replace`, `spell_check`, `reflow_edit`, `layers`, `articles`, `header_footer`, `watermark_pro`, `background`, `bates`, `page_labels`, `crop_pages`, `resize_pages`, `insert_pages`, `replace_pages`, `split_page`, `split_smart`, `interleave`, `page_ops`, `combine`, `copy_pages`, `insert_batch`, `impose`, `extract_images`, `doc_properties`.
   - Optional: route `"outline"` to `com.smartdocs.editor.ui.edit4.BookmarksScreen(nav, uri)`. It keeps bookmark styles; the old editor drops them.
   - Optional: route `"watermark"` to `DecorScreen(nav, uri, DecorType.WATERMARK)`.
2. **`ui/tools/ToolsScreen.kt`** — in `allTools`, add one entry per `EDIT4_TOOLS` item. Each `E4Tool(id, label, icon, group)` maps to `ToolDef(id, label, icon, <your group>)`. At minimum add `ToolDef("edit4_hub", R.string.e4_hub_title, Icons.Default.EditNote, 2)`.
3. **Real link tool (EDT-14)**
   - Creating: in `ReaderScreen.kt`, where `TextPromptDialog(state, prompt) { … }` is shown, make LINK prompts use the new sheet:
     ```kotlin
     if (prompt.tool == ReaderTool.LINK && prompt.rect != null) com.smartdocs.editor.ui.edit4.ReaderLinkCreateSheet(state, prompt.page, prompt.rect!!) { /* same dismiss as TextPromptDialog */ } else TextPromptDialog(...)
     ```
     After that, the LINK branch in `ReaderDialogs.TextPromptDialog` (the note-based fake) is unused.
   - Editing: in `PageCanvas.kt`, the LINK tool's tap. Call `com.smartdocs.editor.ui.edit4.linkAt(state, page, x, y)` (PDF space). If it returns a link, show `ReaderPageLinksSheet(state, page, initial = it) { … }`.
   - Listing: an overflow item «روابط الصفحة» → `ReaderPageLinksSheet(state, state.currentPage) { … }`.
4. **Selection menu** (`ReaderOverlays` SelectionMenu, SHL-4 owner):
   - «رابط» → `com.smartdocs.editor.ui.edit4.ReaderLinkFromSelection(state) { … }`.
   - «إشارة مرجعية» → `com.smartdocs.editor.ui.edit4.addBookmarkFromReader(state, sel.text, sel.page, sel.rects.maxOf { maxOf(it.top, it.bottom) })`.
5. **Attachments panel (EDT-25)** — in `ReaderPanels.kt`, add an ATTACHMENTS tab:
   ```kotlin
   com.smartdocs.editor.ui.edit4.ReaderAttachmentsPanel(state) { f -> nav.navigate(Routes.reader(Uri.fromFile(f).toString())) }
   ```
6. **Playing media (EDT-26)** — in `PageCanvas` (tap with no tool):
   ```kotlin
   val media = com.smartdocs.editor.ui.edit4.rememberPageMedia(state, page)
   ```
   On a tap inside `media[i].rect` (PDF space), show `com.smartdocs.editor.ui.edit4.ReaderMediaPlayer(state, media[i]) { … }`.
7. **Page labels (ORG-12)**
   - Reader page indicator: `val labels = com.smartdocs.editor.ui.edit4.rememberPageLabels(state)`, then `pageIndicator(labels, page, count)`.
   - `GoToPageDialog`: resolve typed text with `PageLabelOps.find(labels ?: emptyList(), text)`.
8. **Transitions (ORG-19/F8)** — in full-screen/auto-flip:
   - Use `rememberTransitions(state)[page]` with `pageTransitionSpec(t)` as the `AnimatedContent` transitionSpec.
   - `t.autoAdvance > 0` sets the flip delay.
9. **Layers (EDT-33)** — reader overflow «الطبقات» → `ReaderLayersSheet(state, onOpen = { f -> nav.navigate(Routes.reader(Uri.fromFile(f).toString())) }) { … }`.
10. **Inline spell-check underline (EDT-9)** — in `ui/edit/EditPageView.kt`, the inline block editor can use `com.smartdocs.editor.ui.edit4.SpellCheckedField(text, onChange)`. That is an `EditText` with the platform spell-check underline; Compose `BasicTextField` has none.
11. **Edit mode → advanced tools** — `EditModeHost` top bar: add a button that opens `Routes.tool("content_tools", state.fileUri)` after saving. On text-less pages, the content tools screen shows the scan→editable-text prompt (EDT-4).
12. **Organize (already edited in `ui/pages/OrganizeScreen.kt`):**
    - top bar: `OrganizeMoreMenu(state) { snack = it }`
    - under the filter chips: `OrganizeSelectionHelpers(state)`

## Status per owned backlog row
| ID | Status |
|---|---|
| EDT-1 Rich text formatting | DONE. `RichTextSheet` + `RichEdit.replaceBlock`/`add` in content tools. Per-character font/size/colour/B/I/U/S/super/sub/char spacing/scale; paragraph alignment incl. justify, line/para spacing, indent, bullets/numbering, direction. Still to wire: the button from the base edit mode (hook 11). |
| EDT-2 | not owned (search4). The core `TextReplace` is available to them. |
| EDT-3 Font library | DONE. Bundled Noto Naskh plus Android's Arabic/Latin system fonts (Noto Naskh/Kufi/Sans Arabic, Roboto, Noto Serif …) and the standard-14 families. PARTIAL: no font download (new font files are not allowed here). |
| EDT-4 Editing a scanned page | DONE. Content tools show «هذه الصفحة صورة…» and run the editable OCR on this page or all pages, then reload. |
| EDT-5 Rotate text blocks | PARTIAL. A block can be re-drawn at any angle (rich edit + rotation, or Rotate). Editing text that is already rotated in the file still needs the base `PageScanner` (edit module), which skips non-upright glyphs. |
| EDT-6 Join / split blocks | DONE. Join 2+ selected blocks into one paragraph in their union box; split at the cursor. |
| EDT-7 Linked text boxes | DONE. Select blocks in order → «ربط المربعات». The text flows through the boxes; the chain is stored in /PieceInfo. Re-flow while typing is not live. |
| EDT-8 Reflow editing | DONE. Tool `reflow_edit`: PDF→DOCX (ConvertEngine) → office editor → «تطبيق» renders the DOCX back to PDF (OfficePdf). The layout may change, as the spec says. |
| EDT-9 Spell check | DONE. Document spell check through Android `SpellCheckerSession` (ignore / ignore all / change / add to dictionary; the dictionary is in SharedPreferences, not Room, because the DB is not mine). Corrections are written into the content. `SpellCheckedField` gives the inline underline (hook 10). When no checker exists for the language a message is shown; the AI fallback belongs to AI-2. |
| EDT-10 More image operations | DONE. Flip H/V, any-angle rotate, clip crop, opacity, front/back. |
| EDT-11 Image editor | DONE. Crop, rotate, flip, brightness/contrast, grayscale, white eraser, flood fill, white→transparent, undo; saves through `ContentEdit.replaceImage`. |
| EDT-12 Edit in another app | DONE. `ACTION_EDIT` through the FileProvider; on return «تحديث الصورة في المستند؟». |
| EDT-13 Place an object on multiple pages | DONE for page images (one shared XObject, scaled per page) and stored annotations/stamps/signatures (copy to range/odd/even). Placing pending reader annotations would need a hook in the reader's placement bar. |
| EDT-14 Real link annotations | DONE. Core, links screen and reader sheets. The reader hookup is hook 3; until it is wired, the reader's LINK tool still writes the old note. |
| EDT-15 Web links from text | DONE for writing: «تحويل الروابط النصية…» (PDFium text + char boxes) and remove all/web links. PARTIAL for viewing: live tap on plain-text URLs in the reader needs a PageCanvas hook. `LinkOps.findTextLinks` + `rectsFor` are ready for it. |
| EDT-16 Append linked web pages | DONE. WebView→PDF, appended with bookmarks, links become GoTo. |
| EDT-17 Cross references | DONE as link rings (each spot → next, last → first) with groups recorded in /PieceInfo. Uses links rather than push-buttons; same navigation. |
| EDT-18 Bookmarks keep style / add from selection | DONE. `BookmarkStore` keeps colour/bold/italic/open/XYZ zoom/actions; edit sheet; add here; `addBookmarkFromReader` (hook 4). |
| EDT-19 Bookmarks panel tools | DONE in the bookmarks tool: search, where am I, expand/collapse all, wrap. The reader panel itself is not mine; route the "outline" tool here (hook 1). |
| EDT-20 Auto bookmarks | DONE. Heading sizes → levels (selectable), strip symbols, keyword mode, range, replace or append. The AI mode belongs to AI-12. |
| EDT-21 Export/import bookmarks | DONE. TXT (indented, hand-written forms accepted) and JSON. |
| EDT-22 Page operations from bookmarks | DONE. Extract section, delete section. Print/share a section = extract, then use the result sheet. |
| EDT-23 TOC page | DONE. Title, levels, dot leaders, Arabic digits, font, RTL, clickable rows, numbers shifted by the inserted pages. |
| EDT-24 Named destinations | DONE. List, sort, add at the current page, rename, re-point, delete; links can target them. |
| EDT-25 Attachments panel | DONE. `ReaderAttachmentsPanel` (open PDF in reader, others via chooser with a warning for risky types, save, share, description, delete, add); needs hook 5. |
| EDT-26 Multimedia play & insert | DONE. Insert a playable `/Screen` (rendition, poster, autoplay); play Screen/RichMedia/Movie in `ReaderMediaPlayer` (hook 6). PARTIAL: playback is a full-screen dialog rather than exactly inside the page rectangle. |
| EDT-27 Shapes as page content | DONE |
| EDT-28 Edit existing vector objects | DONE for paths of the page's own content stream: select, lasso, delete, recolour fill/stroke, width, move. PARTIAL: paths inside form XObjects are not listed, and there is no rotate handle. |
| EDT-29 Arrange objects | PARTIAL. Bring to front / send to back for image XObjects; move for paths. No alignment/distribute; z-order of arbitrary paths would need their graphics state replayed. |
| EDT-30 Shading objects | DONE. Linear/radial two-colour gradients (Type 2/3 shading). |
| EDT-31 Text to outlines | DONE. Glyph paths approximated to polygons (`Path.approximate`, API 26); Type3 skipped. |
| EDT-32 Articles | DONE. List, create by drawing boxes in order, delete. PARTIAL: a reader "follow the thread" view is not built. |
| EDT-33 Layers show/hide | DONE. Tool + `ReaderLayersSheet` (opens a copy with the chosen visibility; PDFium honours /D). Needs hook 9. |
| EDT-34 Layer editing | DONE. Import a PDF page as a layer, rename, print state, delete (content stripped), merge, flatten. Layer reorder is not offered. |
| EDT-35 3D content | NOT FEASIBLE. No U3D/PRC renderer on Android in our stack (same as the spec). |
| EDT-36 Embedded thumbnails | DONE. Embed/remove /Thumb. |
| ORG-1 Insert range + position | DONE. Plus an optional bookmark for the inserted part. |
| ORG-2 Insert from camera | DONE. ML Kit scanner in the insert tool and in the Organize ⋮ menu. |
| ORG-3 Clipboard / web / N blank pages | DONE |
| ORG-4 Insert into many documents | DONE |
| ORG-5 Interleave | DONE |
| ORG-6 Reverse | DONE. Tool + Organize menu. |
| ORG-7 Replace pages | DONE. Outline/link destinations re-pointed. |
| ORG-8 Split by blank pages / many files | DONE. Blank pages or text marker, many files, naming pattern. Size/bookmark modes stay in the existing split tool. |
| ORG-9 Split one page | DONE. Halves (adjustable line), quarters, thirds; RTL order. |
| ORG-10 Real crop | DONE. Drag rect, numeric margins, auto white margins (per page), Crop/Trim/Bleed/Art, range+subset, reset. The crop view's «تطبيق على الملف» button in the reader is a hook for the view owner (route `crop_pages`). |
| ORG-11 Resize pages | DONE |
| ORG-12 Page labels | DONE. Editor + reader helpers (hook 7). |
| ORG-13 Header & footer | DONE |
| ORG-14 Watermark full | DONE |
| ORG-15 Background | DONE |
| ORG-16 Bates | DONE |
| ORG-17 Flatten selected pages | DONE |
| ORG-18 Rearrange by bookmarks | DONE |
| ORG-19 Page transitions | DONE. Writing; playback through `pageTransitionSpec` (hook 8). |
| ORG-20 Duplicate N / swap | DONE |
| ORG-21 Page properties | PARTIAL. Tab order (R/C/S/none) is done. Page open/close actions are not offered: editing JavaScript/arbitrary page actions is out of scope for safety. |
| ORG-22 Drag pages between two documents | PARTIAL. The `copy_pages` tool shows two documents side by side on tablets (stacked on phones), with tap-select and an insert position. Drag-and-drop across panes waits for SHL-5 parallel view (view4). |
| E1 Header and Footer | DONE (= ORG-13). Hijri date format is not included. |
| E2 Bates | DONE |
| E3 Background | DONE |
| E4 Watermark (image/PDF, update/remove, screen/print) | DONE |
| E5 Find & replace | DONE (tool `find_replace`) |
| E6 Rich paragraph formatting | DONE (= EDT-1) |
| E7 Font choice | DONE (= EDT-3) |
| E8 Image crop/flip/arrange | DONE (= EDT-10) |
| E9 Vector graphics / add shapes | DONE (= EDT-27/28) |
| E10 Rotated text / text in form XObjects | PARTIAL. Same limit as EDT-5; form-XObject text is not editable (base `PageScanner`). |
| F1 Crop pages | DONE |
| F2 Replace pages | DONE |
| F3 Resize pages | DONE |
| F4 Combine with page picking, mixed types | DONE. PDF, images, TXT and DOCX inputs; page checkboxes with thumbnails; reorder; bookmark per file (source bookmarks nested). XLSX/PPTX inputs are not converted. |
| F5 Insert from scanner / clipboard | DONE |
| F6 Selection helpers | DONE (Organize chips: odd/even/landscape/portrait/range) |
| F7 N-up / booklet / poster | DONE (+ print through `PrintHelper`) |
| F8 Page transitions | DONE (= ORG-19) |

Also done though not owned: document properties FILE-1..5 (`doc_properties` tool), as my brief asked.

## Limitations
- **Locked files:** multi-file tools (Bates, impose, combine, split smart, batch insert, interleave) open files without a password. Locked files must be unlocked first.
- **Header/footer and watermark text:**
  - It is not a form field; it is shaped page content.
  - Arabic-Indic digits are written as characters.
  - The OCG print/screen switch depends on the viewer honouring /AS (PDFium hides OFF groups on screen).
- **Background:** drawn behind the content (PREPEND). Pages whose content paints an opaque white rectangle hide it, as on desktop.
- **Content edits** (rich text, find & replace, spelling) re-lay a whole block. Mixed styles that already exist in the file are flattened to the block's style before the user restyles it (base edit limitation).
- **Vector scan:** top level only (no form XObjects); clip-only paths are skipped.
