# MODULE_search5 (round 5): search, find & replace, redaction, sanitising, advanced protection

Owner id: `search5`. Spec: `recovered-from-old-chat/MODULE_search4.md`, rebuilt on the current code. The reader's Arabic search was already done and is not rebuilt; only additive changes were made to `ArabicNormalizer.kt` and `TextMatcher.kt`, and their API is unchanged.

- **Strings:** `res/values/strings_search5.xml` and `res/values-en/strings_search5.xml` hold the same 172 keys with the prefix `s5_`. The `sr_*` keys of `strings_search.xml` are reused, not duplicated.
- **Dependencies and shared files:** no new Gradle dependency, and no Room or manifest change. No shared hot file was edited.

## How it was checked (Gradle was not run)
- **Pure logic: JUnit, run with the embeddable Kotlin compiler 2.0.21 plus JUnit 4.13.2. 42 tests, all pass:**
  - `core/search/TextMatcherTest.kt` (the existing one, unchanged, still passes).
  - `TextMatcherModesTest.kt`, `SearchPatternsTest.kt` and `IndexTextTest.kt`.
  - `core/redact/RedactMathTest.kt` and `ProtectAdvancedTest.kt`.
- **Type-check and codegen of every search5 file.** The compiler was kotlinc 2.0.21 with the Compose compiler plugin. It ran against:
  - `android-35/android.jar`
  - PdfBox-Android 2.0.27 and WorkManager 2.9.1
  - Compose 1.7.8 / Material3 1.3.1 and Navigation 2.8.9 (BOM 2025.03.01)
  - the real `core/edit/*`, `PdfOps.kt`, `Annotations.kt` and `ArabicShaper.kt`
  - stubs with the real signatures for `PdfSession`, `ui/common` components, `LocalApp`/`SmartDocsApp`, `Routes` and `R`

  The result is **0 errors**. The harness found three real errors on the way (`@Composable` lambdas inside `if`), and they are fixed.
- **Not run on a device.** The content-removal engine (`PageInspector` / `TrueRedactor`) uses android.graphics, so it cannot run on the desktop JVM. It has not been run at runtime yet; see "Needs a device test" below.

## Files
### `core/search/` (search5 additions)
| File | What |
|---|---|
| `ArabicNormalizer.kt` (extended) | Adds `wordRanges`, `words`, `latinDigits` (keeps indexes), `foldPattern` (folds the Arabic letters of a regex and leaves `\W`, classes and case alone) and `lightStem` (light10-style Arabic stemmer plus simple English plurals, -ing and -ed). |
| `TextMatcher.kt` (extended) | Adds `SearchMode` (PHRASE / ANY_WORDS / ALL_WORDS / REGEX) and `SearchOptions(norm, wholeWord, mode, stem, proximity)`.<br>Adds `find(text, query, SearchOptions)`: stemmed phrases, any/all words, a proximity window in words, and regex on folded text. Ranges map back to the original text.<br>Adds `isValidRegex`. The old `find(text, q, norm, whole)` is unchanged. |
| `SearchPatterns.kt` | `PatternKind` and `find(text, kinds)` with validators:<ul><li>Phones: Yemen (mobile 70/71/73/77/78 and landline), Saudi (05…), Gulf (UAE, Kuwait, Qatar, Bahrain, Oman, with the country code), Egypt (010/011/012/015) and international (8–15 digits).</li><li>Email, URL, and cards (Luhn, 13–19 digits).</li><li>IBAN: ISO mod-97 plus per-country length, trimmed to the valid prefix.</li><li>National ID: Saudi/Iqama (Luhn), Egypt (14 digits with a birth-date and governorate check), Yemen (11 digits), UAE 784-… (Luhn).</li><li>Passport.</li><li>Dates: Gregorian d/m/y and y/m/d (validated), Hijri (هـ suffix, or a year 13xx/14xx هـ), and Arabic, Levantine, Hijri and English month names.</li><li>Amounts with a currency code, sign or Arabic currency word.</li></ul>Arabic-Indic digits are matched. On overlaps the longest match wins. Also `findCustom(regex)`. |
| `IndexText.kt` | Pure. `body(raw)` is the folded words plus light stems. `ftsQuery(query, mode)` builds stem-prefix FTS4 terms (AND, or OR for ANY_WORDS); it returns null for a regex or a query without words. |
| `TextSearch.kt` | `SearchRequest(query, options, words, patterns, regexes, keepLast)` and `ranges(text, req)` (sources, overlaps removed, keep-last-N).<br>`search(session, req, pages)` returns `Found(hit: SearchHit, source, kind)` over `PdfSession.searchWith`, so PDFium char indexes and per-line PDF-space rects are kept.<br>Also `search(session, query, options)` and `searchScopes(file, pw, req)`, which searches comments, bookmarks, fields and attachments. |
| `DocParts.kt` | `PartKind` (TEXT, COMMENTS, BOOKMARK, FIELD, ATTACHMENT) and `DocPart`. Uses PdfBox to collect annotation contents/subject/author, outline titles (with page), field name and value (on the page of the first widget), and attachment names and descriptions. |
| `FullTextIndex.kt` | Its own SQLite file `search_index.db`: tables `docs`, `parts(doc, page, kind, raw)` and FTS4 `parts_fts(body)`.<br>Functions: `needsIndex` (size/mtime), `put`, `remove`, `prune`, `clear`, `stats` and `lockedFiles`.<br>`search(query, options, kinds)`: FTS candidates are verified with `TextMatcher`, grouped by file with snippets and highlight ranges. A regex query scans the stored parts (up to 30 000).<br>`progressFlow` publishes live progress. |
| `IndexWorker.kt` | `Indexer.sources()` collects docsDir, recents with `file://` URIs and the readable paths from `scanDevicePdfs`.<br>`Indexer.run()` indexes changed files (PDFium text plus `DocParts`), records password-protected files as locked, prunes deleted files and reports progress.<br>`IndexWorker` is a CoroutineWorker with `schedule()` (every 12 h, battery and storage not low), `runNow(ctx, force)` and `indexSoon(ctx, file)`. |
| `FindReplace.kt` | `find`: `TextMatcher` matches inside `PageTextModel` blocks (`ReplaceMatch` with font and lossy warnings).<br>`apply`: for each selected block, re-analyses the page, finds the block again (same text, nearest rect), and calls `ContentEdit.replaceText(...)` with the block's size, colour, bold, italic, align and family. The edits chain through temp files into `out`.<br>`replaceIn` is pure. |

### `core/redact/` (new package)
| File | What |
|---|---|
| `RedactMath.kt` | Pure, tested. Contains:<ul><li>`Box`.</li><li>`glyphHit`: the centre lies in a mark, or at least 50 % of the glyph is covered.</li><li>`enclosed` / `touches`.</li><li>`keepLast` (partial masking).</li><li>`pages(spec, count, ALL/ODD/EVEN)`.</li><li>`map` (affine bounding box).</li><li>`autoFontSize` and `repeatCount`.</li></ul> |
| `RedactMarks.kt` | `RedactStyle` (fill: white = whiteout, outline, overlay, text colour, size where 0 = auto, repeat, align) and `RedactMark` (user space, `wholePage`).<br>Reads and writes standard `/Redact` annotations: `/QuadPoints /IC /C /OverlayText /Repeat /DA /Q /NM /Contents /T`, a tinted outline appearance stream, and the private `/SDWholePage` and `/SDAutoSize`.<br>Functions: `read`, `writeAll`, `removeAll`, `save(src, out, pw, marks)` and `load`. |
| `PageInspector.kt` | A `PDFGraphicsStreamEngine` that records every glyph (operator index, string index inside TJ, glyph index, user-space box, the TJ advance that replaces it, render mode, fill colour, alpha, order, hidden layer, nested) and every paint (fill or stroke with bbox and colour, opaque, rect-only; image XObject; inline image; form XObject; shading).<br>It tracks BDC/BMC/EMC itself and resolves `/OC` names through `/Properties` (OCG and OCMD with every visibility policy). Forms and images with an OFF `/OC` count as hidden. Operator numbering matches `core/edit/ContentRewriter`. |
| `TrueRedactor.kt` | `RewritePlan` holds glyph removals, removed operators, painting operators turned into `n`, and swapped images.<br>`apply` rewrites Tj, TJ, ' and " as TJ arrays with an exact advance for every removed glyph, so the following text keeps its position. If the operator count does not match, nothing is changed.<br>`redactPage` removes:<ul><li>glyphs under a mark;</li><li>paths entirely under a mark (clip paths are never touched);</li><li>images and form XObjects entirely under a mark.</li></ul>It also does the following:<ul><li>For an image partly under a mark, its pixels are painted in the fill colour (new XObject `Rd…`).</li><li>Annotations and widgets touching a mark are removed, and a widget takes its field `/V /DV` with it.</li><li>`needsRaster` is set for text in kept form XObjects, vertical fonts, inline images partly covered, and failed parses.</li></ul> |
| `RedactApplier.kt` | `apply(ctx, src, out, pw, marks, Options(sanitize, sanitizeItems, maxSecurity, rasterDpi))` → `Report`. It does this:<ul><li>True removal on each page.</li><li>A whole-page mark wipes the page: no content, no resources, no annotations, no thumbnail.</li><li>Every `/Redact` annotation is removed.</li><li>The fill box is drawn.</li><li>The overlay text is drawn upright in display space, also on rotated pages: Helvetica-Bold, or shaped Arabic with the bundled Noto Naskh. It is auto-sized, repeated or aligned, and clipped to the box.</li><li>The optional `Sanitizer.clean` runs.</li><li>The save is full.</li><li>A PDFium raster fallback applies only to the pages that need it, or to every marked page with maxSecurity.</li></ul> |
| `Sanitizer.kt` | `SanitizeItem` (14 items, `DEFAULTS` for redaction), `scan(src, pw)` → `ScanResult(findings with count + details, ocrLayer, revisions)`, and `clean(doc, items)` / `cleanFile`.<br>What each item covers:<ul><li>**Metadata:** Info, catalog/page/XObject XMP.</li><li>**Attachments:** EmbeddedFiles, AF, FileAttachment annotations.</li><li>**Comments.</li><li>**Forms:** remove XFA, refresh appearances, flatten, drop AcroForm and widgets.</li><li>**JavaScript:** the names tree, OpenAction, AA at document, page, annotation and field level, and `/A` JavaScript actions including `/Next` chains.</li><li>**Links and actions.</li><li>**Bookmarks.</li><li>**Private data:** PieceInfo, SpiderInfo, IDS/URLS.</li><li>**Thumbnails.</li><li>**Hidden layers:** the content is removed, widgets with an OFF `/OC` are removed, and `/D /OFF` is emptied.</li><li>**Invisible text:** Tr 3/7. An OCR layer over images is flagged.</li><li>**Hidden text:** covered by a later opaque rect fill or image with normal blending, fully transparent, or near-white with no darker backdrop.</li><li>**Cropped content:** glyphs and objects outside the CropBox are removed, then MediaBox = CropBox and the Bleed/Trim/Art boxes are dropped.</li><li>**Revisions:** the `%%EOF` count; a full save drops them.</li></ul> |
| `RedactPrefs.kt` | Arabic default code sets (editable), last style, profiles (words, patterns, regexes, style, whole word, keep-last) and the names list. Stored in SharedPreferences `search5_redact`. |
| `ProtectAdvanced.kt` | `EncAlgo` (AES-256 key 256, AES-128, RC4-128), `Permissions` (8 bits plus print and change presets, Acrobat style) and `accessPermission()`.<br>`protect(src, out, currentPw, ProtectOptions)` uses `StandardProtectionPolicy` with `encryptionKeyLength` = 256/128 and `setPreferAES`. A random owner password is used when none is given, and the function returns it. |

### `ui/search/`
| File | What |
|---|---|
| `SearchUiCommon.kt` | `DocHolder` and `rememberDocHolder(uri)` (file, PDFium session, password prompt, `generation`), `PasswordDialog`, `fileOf(uri)`.<br>`SearchOptionsPanel` (modes, proximity, whole word, case, stem, and the stored Arabic switches).<br>Also `PatternChips`, `patternLabel`, `modeLabel`, `highlighted()`, `ColorRow` and `MarkColors`. |
| `HitList.kt` | Checkable hit list with select all / none. |
| `GlobalSearchScreen.kt` | **`search_all`**:<ul><li>FTS search across files with scope chips (text, comments, bookmarks, fields, attachments) and options.</li><li>Live indexing progress, statistics and re-index. The first open with an empty index starts indexing.</li><li>Results are grouped by file with highlighted snippets; a tap opens `Routes.reader(file, page)`.</li></ul> |
| `SearchMarkScreen.kt` | **`search_highlight`**: query and/or patterns → checkable hits → highlight / underline / strikeout / squiggly with a colour. Writes real markup annotations through `AnnotationWriter.commit` into `_marked.pdf`. Tablet layout has two panes. |
| `FindReplaceScreen.kt` | **`find_replace`**: find, replace with, options (phrase or regex, whole word, case, Arabic folding), page range; a list of blocks with checkboxes, highlights and font/lossy warnings; a confirmation with the count → `_replaced.pdf`. |
| `ReaderSearchMenuItems.kt` | Unchanged (round 4). |

### `ui/security/` (the redaction UI was rewritten on top of the old `RedactScreen`)
| File | What |
|---|---|
| `RedactScreen.kt` | Tabs بحث / يدوي / صفحات / المراجعة. On a tablet the review list stays beside the active tab. `/Redact` marks already in the file are loaded.<br>**Top bar:** style for all marks, «حفظ العلامات للمراجعة» (`_marked.pdf`), clear.<br>**Bottom:** «تطبيق الكل (n)» opens a confirmation with «إزالة المعلومات المخفية أيضاً» and «تحويل إلى صور (أقصى أمان)». The result sheet shows the report: glyphs, objects, annotations and raster pages.<br>**Batch:** «تنقيح عدة ملفات» uses the current inputs. |
| `RedactState.kt` | Marks (user space), page geometry (`PageFrame` display ⇄ user), search inputs (query, options, word list, names list, patterns, regex, keep-last, page range), checked hits, `markChecked`, `addManual`, `addPageMarks`, `repeatOnPages`, update/remove/clear, `applyStyleToAll`, and loading and saving profiles. |
| `RedactPanes.kt` | Search pane (inputs, profiles menu, «كشف تلقائي (أنماط)», batch, hits, «تعليم المحدد»), pages pane (range plus all / odd / even), review list (swatch, label, page, properties, repeat on pages, delete). |
| `RedactStyleSheet.kt` | `RedactStyleDialog` (fill / whiteout, outline, overlay text with the code picker, text colour, size or auto, repeat, align), `CodesEditor`, `PagePickDialog`, `ParityRow`. |
| `RedactManualView.kt` | `RedactRegion` was replaced by `ManualBox` (display rect, fill, outline, whole page). It draws a translucent fill preview plus the outline. |
| `SanitizeScreen.kt` | **`sanitize`**: scan → checklist with counts and expandable details. Everything found is checked except an OCR layer, which shows a warning. Clean → `_clean.pdf` with a report. |
| `ProtectAdvancedScreen.kt` | **`protect_advanced`**: algorithm, open password with confirmation, «تقييد الطباعة والتعديل» (owner password or a random one, print level, change presets, the 8 permission checkboxes). It asks for the current password of an already-encrypted file and shows the generated owner password. |

### Tests (`app/src/test/java/com/smartdocs/editor/core/`)
- **`search/`:** `TextMatcherTest.kt` (unchanged, passes), `TextMatcherModesTest.kt`, `SearchPatternsTest.kt` and `IndexTextTest.kt`.
- **`redact/`:** `RedactMathTest.kt` and `ProtectAdvancedTest.kt`. `ProtectAdvancedTest` needs PdfBox-Android's `AccessPermission`, which is pure Java and already on the unit-test classpath through the app dependency.

## Hooks for the lead (exact)
1. **Tool routes.** In `ui/tools/ToolScreens.kt`, `when (id)`, add these after the line `"redact" -> RedactScreen(nav, uri)`:
   ```kotlin
   "search_all" -> com.smartdocs.editor.ui.search.GlobalSearchScreen(nav)
   "search_highlight" -> com.smartdocs.editor.ui.search.SearchMarkScreen(nav, uri)
   "find_replace" -> com.smartdocs.editor.ui.search.FindReplaceScreen(nav, uri)
   "sanitize" -> com.smartdocs.editor.ui.security.SanitizeScreen(nav, uri)
   "protect_advanced" -> com.smartdocs.editor.ui.security.ProtectAdvancedScreen(nav, uri)
   ```
   `"redact" -> RedactScreen(nav, uri)` stays as it is; the signature is unchanged.
2. **Tool cards.** In `ui/tools/ToolsScreen.kt`, in the tool list:
   - After `ToolDef("redact", R.string.tool_redact, Icons.Default.Block, 2),` add:
     ```kotlin
     ToolDef("sanitize", R.string.s5_sanitize_title, Icons.Default.CleaningServices, 2),
     ToolDef("protect_advanced", R.string.s5_protect_title, Icons.Default.EnhancedEncryption, 2),
     ```
   - Before `ToolDef("compare", R.string.tool_compare, Icons.Default.Compare, 3),` add:
     ```kotlin
     ToolDef("search_all", R.string.s5_global_title, Icons.Default.ManageSearch, 3),
     ToolDef("search_highlight", R.string.s5_mark_title, Icons.Default.BorderColor, 3),
     ToolDef("find_replace", R.string.s5_replace_title, Icons.Default.FindReplace, 3),
     ```
   `Icons.Default.ManageSearch` is deprecated in favour of `Icons.AutoMirrored.Filled.ManageSearch`; either works, or use `Icons.Default.Search`.
3. **Background index.** In `SmartDocsApp.onCreate()`, after `documents = DocumentRepository(this, db)`:
   ```kotlin
   com.smartdocs.editor.core.search.IndexWorker.schedule(this)
   ```
   Optional: index saved or produced files at once. At the end of `DocumentRepository.touchRecent(...)` (inside its `withContext`), add:
   ```kotlin
   com.smartdocs.editor.core.search.IndexWorker.indexSoon(context, file)
   ```
4. **Reader tool chips.** In `ui/reader/ReaderToolbars.kt`, `readerTools`, after `ReaderToolItem.Screen("redact", R.string.tool_redact, Icons.Default.Block),` add:
   ```kotlin
   ReaderToolItem.Screen("find_replace", R.string.s5_replace_title, Icons.Default.FindReplace),
   ReaderToolItem.Screen("search_highlight", R.string.s5_mark_title, Icons.Default.BorderColor),
   ReaderToolItem.Screen("sanitize", R.string.s5_sanitize_title, Icons.Default.CleaningServices),
   ReaderToolItem.Screen("protect_advanced", R.string.s5_protect_title, Icons.Default.EnhancedEncryption),
   ```
   `navigateTool(id)` already passes the open document. If there are pending annotations, it should save them first, as it does for `"redact"`.
5. **Reader search bar (optional).** In `ui/reader/ReaderOverlays.kt`, search `DropdownMenu`, after `com.smartdocs.editor.ui.search.ReaderSearchMenuItems { state.runSearch(state.searchQuery) }`, add:
   ```kotlin
   DropdownMenuItem(text = { Text(stringResource(R.string.s5_replace_title)) }, leadingIcon = { Icon(Icons.Default.FindReplace, null) },
       onClick = { menu = false; nav.navigate(com.smartdocs.editor.ui.Routes.tool("find_replace", state.uriString)) })
   ```
   Use whatever navigation handle and uri the overlay already has for tools. The reader's own search hook (round 4) stays as it is.
6. **Home / Files search pill (optional):** «ابحث داخل الملفات» → `nav.navigate(Routes.tool("search_all"))`.
7. **DocAgent `redact_text` (optional, true redaction instead of a raster).** In `core/ai/DocActions.kt` `redactText`:
   - Use `com.smartdocs.editor.core.search.TextSearch.search(session, query, com.smartdocs.editor.core.search.SearchOptions())` for an Arabic-aware search.
   - In place of `PdfOps.redact(ctx, src, out, regions, pw)`, call:
     ```kotlin
     com.smartdocs.editor.core.redact.RedactApplier.apply(ctx, src, out, pw, hits.mapIndexed { i, h -> com.smartdocs.editor.core.redact.RedactMark("ai$i", h.page, h.rects) })
     ```
8. **Share flow (optional, PRT-9 «تلقائي عند المشاركة»):** call `Sanitizer.cleanFile(src, out, pw, SanitizeItem.DEFAULTS)` before sharing.

## Status per feature of the spec
| Feature | Status |
|---|---|
| Reader Arabic search (normaliser, matcher, prefs, menu items, `searchWith`) | Already DONE (round 4). It was only extended: modes, stem, proximity, regex. |
| SearchPatterns with validators | **DONE.** Luhn, IBAN mod-97, Yemen/Saudi/Gulf/Egypt/international phones, email, Gregorian, Hijri and month-name dates, URL, amounts, IDs, passport. 12 tests. |
| TextSearch over PdfSession (+ scopes, keep-last) | **DONE.** |
| FullTextIndex (own FTS4 db) + IndexWorker | **DONE.** |
| GlobalSearchScreen `search_all` | **DONE.** |
| SearchMarkScreen `search_highlight` (real markup annotations) | **DONE.** |
| FindReplaceScreen `find_replace` (content-edit engine) | **PARTIAL.** The tool works.<br>A «استبدال» toggle inside the reader search bar needs the lead's reader files; hook 5 links to the tool.<br>MODULE_edit limits apply: text inside form XObjects and rotated text are not in `PageTextModel`; mixed styles in a block are flattened to the block style; the replacement is literal (no `$1`). |
| Sanitizer + SanitizeScreen `sanitize` | **DONE**, all 14 categories.<br>"Clean automatically on share" needs hook 8.<br>Overlap is detected only for later opaque rectangle fills or images, not for arbitrary vector shapes. |
| Redaction upgrade: mark → review → apply, `/Redact` annotations | **DONE.** |
| Patterns, word lists, names list, custom regex, keep-last-N, page range, profiles, batch | **DONE.** |
| Whole-page marks, repeat on pages, style for all / per mark, code sets | **DONE.** |
| True content removal | **DONE in code, needs a device test.** Covers glyphs, paths and images entirely under a mark, image pixels partly under a mark, form XObjects, annotations and widgets with their values. There is a raster fallback per page, and «أقصى أمان» is optional. Paths only partly under a mark are covered by the box, not clipped out. Text inside kept form XObjects falls back to raster for that page. |
| AI PII detection (J5) | **PARTIAL.** «كشف تلقائي (أنماط)» works without AI. The base tree has no `core/ai/PiiDetector`; another module (ai5) is adding `core/ai5/PiiDetector`, and its matches can feed `RedactState.found` later. |
| ProtectAdvanced + ProtectAdvancedScreen `protect_advanced` | **DONE.** AES-256 (keyLength 256) / AES-128 / RC4-128, 8 permissions, print and change presets, random owner password.<br>**NOT FEASIBLE:** "encrypt all except metadata" and "attachments only". PdfBox 2.0.27 has no `/EncryptMetadata` setter and no EFF crypt filters. |
| Marking from the reader's selection or comment tab | **NOT DONE.** It needs reader hooks (lead files). The reader can open `Routes.tool("redact", uri)`, which loads marks saved in the file. |

## Needs a device test (in this order)
1. Redact a word in a simple Latin PDF and in an Arabic PDF: the text after it keeps its position, and `pdftotext` or copying no longer gives the word.
2. Redact an area over a photo: the pixels are painted.
3. Redact over a whole image: the image is gone.
4. On a rotated page (90°): the box and the overlay text are upright.
5. «حفظ العلامات للمراجعة» then reopen: the marks are listed; Acrobat shows them as Redact marks.
6. Sanitize a file with an OFF layer and white-on-white text: both are gone and the visible text stays.
7. `protect_advanced` AES-256 opens in other viewers with the password, and the permissions show there.

## Limitations
- **Index:**
  - `content://` files that are not copied locally are not indexed. Encrypted files are recorded as locked.
  - FTS finds candidates by stem prefix, so a fragment from the middle of a word is not found by the global search (the in-document tools find it).
- **`TextSearch.search` labels:** the hit sources are aligned with the hits by order. If PDFium reports fewer characters than the page text length (rare), the labels shown on hits can shift. The rects stay correct.
- **Overlay text:** non-WinAnsi Latin falls back to the Arabic font, and glyphs that font lacks are dropped.
