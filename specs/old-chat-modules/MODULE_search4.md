# MODULE_search4: search and redaction foundations

Owner id: `search4`. Strings are in `res/values/strings_search4.xml` and `res/values-en/strings_search4.xml` (160 strings, prefix `sr_`). No Gradle dependency was added, and Gradle was not run.

## How it was checked
- **Pure logic on the desktop JVM** (`/home/claude/convtest/search4/T1.kt`): all checks pass for `ArabicNormalizer`, `TextMatcher` and `SearchPatterns`.
  - مُحَمَّد and محمد match each other, and so do أحمد and احمد, الى and الي, presentation forms and lam-alef, Arabic-Indic digits, whole word, stemming, ة=ه, all-words with proximity, regex, and phrases across line breaks.
  - Patterns: Yemeni and Saudi phones, email, Luhn cards (an invalid card is rejected), IBAN mod-97, dates in Gregorian, Hijri and Arabic month names, URL, ID, passport and amounts.
- **PdfBox logic on the desktop with real PdfBox 2.0.30** (`/home/claude/convtest/search4/Harness.kt`, `build.sh`), covering `TrueRedactor`, `Sanitizer` and `RedactMarks`:
  - **Redaction:** glyphs are removed and the rest keep their positions. A path that lies entirely under a mark is deleted, and so is an image inside a mark; a path only partly under a mark stays (the box covers it).
  - **Sanitize:** these are removed:
    - invisible Tr 3 text
    - white-on-white text
    - text covered by a later opaque fill
    - an OFF optional-content layer, including its paths
    - glyphs and paths outside the crop box
    - metadata, JavaScript OpenAction, link, comment, bookmarks, PieceInfo and thumbnail
  - **What stays after sanitize:** white text on a dark box and all visible text (checked with `pdftotext`). A rescan finds nothing.
  - **/Redact marks:** a write → read round trip keeps the fill, overlay, repeat and whole-page flag. `qpdf --check` passes.
- **All new Android and Compose files** were type-checked with kotlinc 2.1 plus the compose compiler plugin. They were compiled against `android-36.jar`, the cached Compose 1.7.8 / Material3 1.3.1, PdfBox-Android, WorkManager 2.10 and coroutines jars. App classes that the files only call were replaced by stubs with the real signatures.
  - The frontend reports **no errors in any search4 file**.
  - A planted error was reported, which shows the check really runs.
- The fid desktop harness twin (`/home/claude/convtest/redact/DesktopRedactPlatform.kt`) got the new `rgb` parameter so it still builds.

## Files
### Core: `core/search/`
| File | What |
|---|---|
| `ArabicNormalizer.kt` | `NormOptions` and `fold(text) → Folded(text, map)` with an index map back to the source characters.<br>It ignores tashkeel and Latin accents, ignores tatweel, unifies أإآٱ→ا, ؤ→و, ئ→ي, ى/ی→ي, and optionally ة→ه, and maps Arabic-Indic digits to Latin.<br>It is case-insensitive, expands presentation forms and ligatures (NFKC), drops invisible marks (ZWJ, RLM, soft hyphen) and collapses white space.<br>Also `foldPattern` (for regex), `latinDigits`, the Arabic light stemmer `lightStem`, and `words`. |
| `TextMatcher.kt` | `SearchOptions` (norm, wholeWord, mode PHRASE/ANY_WORDS/ALL_WORDS/REGEX, stem, proximity) and `find()` → `TextMatch` ranges in the ORIGINAL text, plus `snippet`. |
| `SearchPatterns.kt` | Regex library with validators. Phones: Yemen, Saudi, Gulf, Egypt, international. Also email, national ID, passport, credit card (Luhn), IBAN (mod-97), dates (Gregorian, Hijri, Arabic month names), URL and amount. Plus CUSTOM regex and a NAMES list. |
| `TextSearch.kt` | `search(session, query, options, pages, text, boxes, patterns, …)` → `SearchHit` with PDFium char indexes and per-line rects (PDF space, top > bottom), the same shape as `PdfSession.search`. Also `rects`, `partialRange` (keep the last N characters) and `searchScopes` (comments, bookmarks, fields, attachments). |
| `DocParts.kt` | Form field values and attachment names/descriptions for scope search and the index. |
| `SearchPrefs.kt` | Stored Arabic options (SharedPreferences `search4_prefs`) and **`ReaderSearchHook.search(...)`**, the reader's entry point. |
| `FullTextIndex.kt` | Its own SQLite file `search_index.db` (FTS4, no Room change).<br>Tables: `docs`, `parts(doc, page, kind, raw)` and `parts_fts(body)`, where body = folded words plus light stems.<br>`search()` uses FTS stem-prefix candidates, then `TextMatcher` verification, and groups the results by file with snippets and highlight offsets.<br>Kinds: TEXT, COMMENTS, BOOKMARK, FIELD, ATTACHMENT. |
| `IndexWorker.kt` | `Indexer` collects the sources (docsDir walk, recents with file:// URIs, `scanDevicePdfs` paths that can be read). It indexes changed files (size/mtime), records locked files, prunes deleted ones and publishes live progress through `FullTextIndex.progressFlow`.<br>`IndexWorker` is a CoroutineWorker with `runNow`, `indexSoon(file)` and `schedule()` (every 12 h, battery and storage not low). |
| `FindReplace.kt` | `find` matches inside the `PageTextModel` blocks of MODULE_edit. `apply` chains `ContentEdit.replaceText` per block and finds the block again after each rewrite, because operator indexes move. |

### Core: `core/redact/` and `core/pdf/`
| File | What |
|---|---|
| `core/redact/RedactMarks.kt` | `RedactStyle` and `RedactMark` (user space).<br>Marks are read and written as standard `/Redact` annotations: `/QuadPoints /IC /C /OverlayText /Repeat /DA /Q /NM /Contents /T`, an outline appearance stream, and the private `/SDWholePage` and `/SDAutoSize`.<br>Functions: `read`, `writeAll`, `removeAll`, `save`. |
| `core/redact/RedactApplier.kt` | Applies marks:<ul><li>`TrueRedactor.redactPage` removes the content, and image pixels are painted in the fill colour.</li><li>The fill box is drawn per mark; white = whiteout.</li><li>Overlay text is shaped Arabic or Helvetica-Bold, auto-sized, repeated, aligned and clipped to the box, and upright on rotated pages.</li><li>A whole-page mark wipes the page: content, resources and annotations are dropped.</li><li>Every `/Redact` mark is removed.</li><li>`Options.sanitize` optionally removes hidden information in the same pass.</li><li>A page is rasterised only when the content cannot be rewritten, or when "maximum security" is on.</li><li>The save is always full.</li></ul> |
| `core/redact/Sanitizer.kt` | `scan()` → counts and details per `SanitizeItem`, and `clean(doc, items)`.<br>Items: metadata (Info, XMP, page and XObject XMP), attachments (EmbeddedFiles, AF, FileAttachment annotations), comments, forms (refresh appearances, flatten, drop AcroForm and XFA), JavaScript (Names, OpenAction, AA at every level, JS actions incl. `/Next` chains), links and actions, bookmarks, private data (PieceInfo, SpiderInfo, IDS/URLS, i.e. the embedded search index), thumbnails, hidden layers (OFF content removed and widgets on OFF layers removed), invisible text (Tr 3/7, or covered by a later image = OCR layer), hidden text (covered by a later opaque fill, or near-white with no dark backdrop), cropped content (glyphs and objects outside the crop box removed, then MediaBox = CropBox), and earlier revisions (the `%%EOF` count; a full save drops them). |
| `core/redact/RedactPrefs.kt` | Redaction code sets (Arabic defaults, editable), last style, redaction profiles (words, patterns, regex, style, whole word, keep-last) and the names list. |
| `core/redact/ProtectAdvanced.kt` | J6: AES-256 / AES-128 / RC4-128, print none / low / high, the five change levels, copy, accessibility. A random owner password is used when none is given. |
| `core/pdf/TrueRedactor.kt` (extended) | **What it does now:**<ul><li>`redactPage(..., fillRgb)`.</li><li>**Painted paths that lie entirely under a mark are deleted.** Clip paths are never deleted.</li><li>Images entirely under a mark are dropped instead of blanked.</li><li>`Result.objectsRemoved`.</li><li>`inspectPage()` returns `GlyphProbe` (box, render mode, fill colour, alpha, order, hidden layer) and `Paint` (opaque rectangle fills and images, in order).</li><li>`cleanPage(doc, page, Rules(glyph, dropObject, hiddenLayers), oc)` reuses the position-preserving rewrite for sanitize.</li></ul>**Marked content:** BDC/BMC/EMC are tracked in its own `processOperator`, because PdfBox's BDC processor does not resolve named `/Properties`. OCG and OCMD OFF are detected, and form XObjects and images with `/OC` OFF are skipped.<br>**Kept as before:** the original redaction behaviour and the existing API. |
| `core/pdf/RedactPlatform.kt` | `blankImage(..., rgb = 0)` paints in the mark's fill colour. |

### UI: `ui/search/`
| File | What |
|---|---|
| `SearchUiCommon.kt` | `DocHolder`/`rememberDocHolder(uri)` (file, PDFium session, password prompt), `SearchOptionsPanel` (modes, proximity, whole word, case, stem and the Arabic switches, which are stored), `PatternChips`, `patternLabel`, **`ReaderSearchMenuItems`** (reader hook) and `highlighted()`. |
| `HitList.kt` | Checkable result list (select all / none). |
| `GlobalSearchScreen.kt` | **Tool id `search_all`**: index search across files. Scope chips, options, live indexing progress, re-index, results grouped by file with highlighted snippets; a tap opens the reader at that page. |
| `SearchMarkScreen.kt` | **Tool id `search_highlight`**: query or patterns → check hits → highlight / underline / strikeout / squiggly with a colour → new file. Also `ColorRow`. |
| `FindReplaceScreen.kt` | **Tool id `find_replace`**: find, then replace with; a block list with per-block selection, font-substitution warning, confirm, and a new file. |

### UI: `ui/security/`
| File | What |
|---|---|
| `RedactScreen.kt` (rewritten) | Tabs: بحث / يدوي / صفحات / المراجعة. Existing `/Redact` marks in the file are loaded for review.<br>**Top bar:** style for all marks, «حفظ العلامات للمراجعة» (writes the `/Redact` annotations to `_marked.pdf`), clear.<br>**Applying:** «تطبيق الكل (n)» opens a confirmation with «إزالة المعلومات المخفية أيضاً» and «تحويل إلى صور (أقصى أمان)», and the result sheet shows a report.<br>Batch: several files are redacted with the current inputs. On tablets the review list sits beside the pane. |
| `RedactState.kt` | Marks in user space and their display↔user mapping (rotation and crop box). Search runs over the query, the word list and the patterns. Also `markChecked` (partial masking), `smartDetect` (regex plus AI via `core/ai/PiiDetector`), manual marks, page marks, repeat on pages, and editing or removing a mark or the style of all marks. |
| `RedactPanes.kt` | Search pane (word list, options, patterns, custom regex, names, keep-last, page range, regex/AI detection, profiles load/save/delete, batch), pages pane (range plus all / odd / even), review list (swatch, overlay, page; properties / repeat on pages / delete). |
| `RedactStyleSheet.kt` | Mark properties: fill (white = whiteout), outline, overlay text, code picker with an editor, text colour, size (auto), repeat, alignment. |
| `SanitizeScreen.kt` | **Tool id `sanitize`**: scan → checklist with counts and details → clean → `_clean.pdf` with a report. Everything found is checked except the OCR layer, which shows a warning. |
| `ProtectAdvancedScreen.kt` | **Tool id `protect_advanced`** (J6). |
| `RedactManualView.kt` | Unchanged. |

## Hooks for the lead (exact lines)
1. **Reader search (VIEW-12 / N2).** In `ui/reader/ReaderState.kt`, `runSearch`, replace
   ```kotlin
   val hits = runCatching { s.search(query, matchCase, wholeWords) }.getOrDefault(emptyList())
   ```
   with
   ```kotlin
   val hits = runCatching { com.smartdocs.editor.core.search.ReaderSearchHook.search(context, s, query, matchCase, wholeWords, { pageText(it) }, { charBoxes(it) }) }.getOrDefault(emptyList())
   ```
   The hits keep the `SearchHit` shape: PDFium char indexes and PDF-space rects. PageCanvas and the hit list work unchanged.
2. **Arabic switches in the reader search menu.** In `ui/reader/ReaderOverlays.kt`, inside the search bar `DropdownMenu`, insert after the `whole_words` `DropdownMenuItem(...)`:
   ```kotlin
   com.smartdocs.editor.ui.search.ReaderSearchMenuItems { state.runSearch(state.searchQuery) }
   ```
3. **Tool routes.** Add these cases to `ui/tools/ToolScreens.kt` (`"redact"` stays as it is):
   ```kotlin
   "search_all" -> com.smartdocs.editor.ui.search.GlobalSearchScreen(nav)
   "search_highlight" -> com.smartdocs.editor.ui.search.SearchMarkScreen(nav, uri)
   "find_replace" -> com.smartdocs.editor.ui.search.FindReplaceScreen(nav, uri)
   "sanitize" -> com.smartdocs.editor.ui.security.SanitizeScreen(nav, uri)
   "protect_advanced" -> com.smartdocs.editor.ui.security.ProtectAdvancedScreen(nav, uri)
   ```
   Tool cards (titles): `R.string.sr_global_title`, `sr_mark_title`, `sr_replace_title`, `sr_sanitize_title`, `sr_protect_title`. Suggested icons: Search, BorderColor, FindReplace, CleaningServices, EnhancedEncryption.
4. **Background index.** In `SmartDocsApp.onCreate()`, after `documents = DocumentRepository(this, db)`:
   ```kotlin
   com.smartdocs.editor.core.search.IndexWorker.schedule(this)
   ```
   Optional: in `DocumentRepository.touchRecent(...)` (end of the function) add `com.smartdocs.editor.core.search.IndexWorker.indexSoon(context, file)`, so saved or produced files are indexed at once.
5. **Entry points (optional but recommended):**
   - Home and Files search pill: «ابحث داخل الملفات» → `nav.navigate(Routes.tool("search_all"))`.
   - Reader overflow and search bar menu: «بحث واستبدال» / «بحث وتمييز» / «بحث وتنقيح» / «تنظيف قبل المشاركة» → `Routes.tool("find_replace" | "search_highlight" | "redact" | "sanitize", state.uriString)`, after saving pending annotations.
6. **DocAgent `redact_text` (optional).** It can use `TextSearch.search(...)` instead of `session.search`, so Arabic queries match regardless of tashkeel or alef form.

## Status of every backlog row owned by `search4`
| # | ID | Status |
|---|---|---|
| 57 | EDT-2 Find & Replace in page content | **PARTIAL.** It is the `find_replace` tool screen with whole word, case, Arabic folding, regex and page range, a match count with confirmation, a font-substitution warning, and a new file (undo = keep the original).<br>Not done: a «استبدال» toggle inside the reader search bar, which needs edits to the lead's reader files. The screen opens with the reader's file through `Routes.tool("find_replace", uri)`.<br>The limits of MODULE_edit apply: text in form XObjects and rotated text cannot be edited. |
| 117 | CMT-4 Search & Highlight | **DONE** (`search_highlight`: query, patterns, word options, 4 markup kinds, colour, real annotations). |
| 151 | VIEW-12 Arabic-aware and extended search | **DONE** with the reader hook (hook 1 + 2):<ul><li>Our own matcher over PDFium text and char boxes.</li><li>Options: tashkeel, alef/hamza/ya, ta marbuta, tatweel, digits, case, whole word.</li><li>Scopes text / comments / bookmarks / fields / attachments in `TextSearch.searchScopes` and in the index.</li></ul>**Not done:**<ul><li>A scope-grouped result list *inside the reader bar*: this is reader UI, and the lead can call `searchScopes`.</li><li>A highlight colour and opacity for search hits: that belongs to PageCanvas (lead).</li><li>Searching inside the PDF attachments themselves (attachment names and descriptions are searched).</li></ul> |
| 181 | PRT-3 True object-level redaction | **DONE.** Glyphs, image pixels, paths entirely under a mark, form XObjects and inline images are removed, together with the annotations and widgets under the mark. A raster fallback is used per page only when needed, and «تحويل إلى صورة (أقصى أمان)» is optional. Paths only partly under a mark are covered, not clipped out. |
| 182 | PRT-4 Redaction marks: review, then apply | **DONE in the redaction tool:**<ul><li>Marks are `/Redact` annotations, readable by other viewers.</li><li>Properties: fill, outline, overlay text, codes with an editor, colour, size / auto, repeat, alignment.</li><li>Page marks (range, odd, even) and repeat on pages.</li><li>Review list, apply all with confirmation, «حفظ العلامات للمراجعة».</li></ul>**PARTIAL:** marking from the reader's comment tab or selection menu needs a reader hook (lead). The reader can open `Routes.tool("redact", uri)`, which loads the marks already in the file. Import/export of code sets is not done; they are stored locally. |
| 183 | PRT-5 Search & Redact: lists, patterns, partial words | **DONE.** Phrase, word/phrase list, pattern library with Arabic-region formats, custom regex, names list, partial masking «أبقِ آخر N أحرف», whole word, case, Arabic folding. Import of a TXT list: paste into the list field. A file picker for .txt is not added. |
| 187 | PRT-9 Sanitize / remove hidden information | **DONE** (`sanitize`: scan with counts and details, checklist, every category in the spec, full save).<br>**PARTIAL:** "remove hidden information automatically on save/share" needs a setting and a hook in the share flow (lead/settings owner). Call `Sanitizer.clean(doc, items)` before sharing.<br>"Overlapping objects" is detected for text covered by later opaque rectangle fills or images. Overlap by arbitrary vector shapes is not render-tested. |
| 242 | TLS-3 Full-text search across files (index) | **DONE** (`search_all`, FTS4 index, WorkManager indexer, grouped snippets, open at page). |
| 336 | J1 Mark → review → apply with properties | **DONE.** Same as PRT-4, including «إزالة المعلومات المخفية أيضاً» offered at apply time. |
| 337 | J2 True content removal | **DONE.** Same as PRT-3. Rasterising happens only per page and only as a fallback. |
| 338 | J3 Pattern search & redact | **DONE.** Patterns, word list, multiple files («تنقيح عدة ملفات»). |
| 339 | J4 Sanitize / remove hidden information | **DONE.** Same as PRT-9. |
| 340 | J5 AI PII detection | **DONE.** «كشف البيانات الحساسة بالذكاء» in the redaction tool uses `core/ai/PiiDetector` with the user's AI settings. «كشف تلقائي (أنماط)» works without AI. The findings go to the checkable list, then become marks. |
| 341 | J6 Encryption options detail | **DONE** (`protect_advanced`: algorithm, print levels, change levels, copy, accessibility).<br>**NOT FEASIBLE:** "encrypt all except metadata" and "only attachments". PdfBox 2.0.27 has no `/EncryptMetadata` setter and no EFF crypt-filter support. This overlaps PRT-1 (owner protect4), which can reuse `ProtectAdvanced.permissions()`. |
| 362 | N1 Search inside all documents | **DONE.** Same as TLS-3. Scopes cover page text (including OCR text layers), comments, bookmarks, form fields and attachment names. |
| 363 | N2 Arabic-aware normalisation | **DONE** (`ArabicNormalizer`). It is used by reader search (hook 1), the index, search & highlight, find & replace, redaction and patterns. |

Also covered for the protect4 rows (they are not search4 rows): PRT-6 profiles and batch (in RedactScreen), PRT-7 smart redact (J5), PRT-8 whiteout (white fill), so protect4 can mark those as provided by search4.

## Limitations
- **Index:**
  - Files opened only through `content://` URIs (not copied locally) are not indexed.
  - Encrypted files are recorded as locked without content.
  - FTS candidates are found by word-stem prefix, so a query that is a fragment from the middle of a word (Latin "port" in "report") is not found by the index. The in-document search finds it.
- **Find & replace:** it rewrites whole blocks with the content-edit engine, so mixed styles inside a block are flattened to its dominant style (MODULE_edit limitation). The replacement text is literal; regex groups such as `$1` are not expanded.
- **Glyph removal:** vertical fonts or a zero font size make a page fall back to raster for redaction. For sanitize, such a page is reported in "not cleaned".
- **Hidden-layer clean-up:** remaining layers are made ON by default (empty `/OFF`). Clipping paths inside hidden content are kept.
- **Overlay text for Latin:** uses Helvetica-Bold, so non-WinAnsi Latin text falls back to the Arabic font (Noto Naskh). Glyphs that font lacks are skipped silently.
