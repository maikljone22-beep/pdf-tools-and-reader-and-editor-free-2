# MODULE ai5 (round 5): whole-document AI, citations, summary, study tools, voice, Spaces and AI tools

Spec: `recovered-from-old-chat/MODULE_ai4.md`, rebuilt on the current `core/ai`, `ui/ai` and `core/voice` code.
I did not run Gradle. Verification done:
- **Pure logic plus JUnit tests**: I compiled them with the embeddable Kotlin 2.2.20 compiler from the Gradle cache and ran them with JUnit 4.13.2. 25 tests, all passing.
- **Type-check of every new and edited file**: I ran Kotlin 2.3.21 with the Compose compiler plugin. The classpath was `android-35/android.jar`, the app's last `bundleDebugClassesToCompileJar/classes.jar` and `R.jar`, and the dependency jars from `D:\gradle-home`.
- **Remaining errors in that check**: only two kinds, both expected:
  - `R.string.a5_*` is unresolved, because that old `R.jar` does not yet contain the new strings.
  - `PdfOps.load` is reported as `internal`. My sources and the app jar were in different modules for the check; in the real build they are one module, so this is fine.

Nothing in the hot-file list was edited. No new dependencies.

## Files
### core/ai5 (new, package `com.smartdocs.editor.core.ai5`)
| File | Content |
|---|---|
| `AiText.kt` | `AiNorm` (delegates to `core.search.ArabicNormalizer`, ta-marbuta folded, digit unification), `AiTokens` (AR/EN stop words, light Arabic stemmer), `AiLang` (detect, language names), `AiJson` (lenient JSON from model answers: fences, prose, trailing commas, Arabic digits, page lists). |
| `DocIndex.kt` | `DocChunk(id, page 0-based, para, text)`, `Chunker` (paragraph-aware, ≈900 chars, never spans pages), `DocIndex` (BM25 + phrase bonus, `spread(maxChars)`, `material()` with `[صفحة N]`). |
| `DocIndexer.kt` | `DocIndexer.get(ctx, file, password?, pageText?, pageCount?, progress)`. It reads the WHOLE document (no page limit), keeps a memory LRU, and caches chunks in `cacheDir/ai5_index/<contentHash>.json` (hash = size + first/last 64 KB). |
| `Retrieval.kt` | `Source(n, docKey, fileName, filePath, page, text)`, `Retriever.retrieve(indexes, query, maxChars=9000, k=10)`. Generic questions get a spread; specific ones get BM25 with at most 2 passages per page. For multi-document questions every matching document is kept. `sourcesBlock()` numbers passages [1]…[k]. |
| `Citations.kt` | `CitationFormat` (prompt contract, `visible` while streaming, `parse`, `parts`, `plain`, `numbers`, `bestQuote`, JSON), `Citation`, `CitedAnswer(text, citations, followUps)`, `AnswerPart`. |
| `DocQa.kt` | Grounded Q&A prompt (history, multi-document, Space persona), follow-up-aware retrieval query, `starters()` (one request) and `parseQuestions`. |
| `AiCalls.kt` | All ai5 requests go through here. It keeps a local per-day counter for the proxy's `DAILY_LIMIT=60`, offers `fits/remaining`, and maps HTTP 429 to `AiQuotaException`. |
| `SummaryGen.kt` | `SummaryOptions(length, format, language, audience, focus)` → `StructuredSummary(overview, points, sections[title, pages, text], questions)`. Short documents take 1 request. Long ones use map-reduce over at most 6 segments + 1, i.e. **at most 7 requests even for a 500-page book**. Results are cached in `filesDir/ai5/summary/`. |
| `StudyModels.kt` | `Flashcard`/`CardSrs` + `Srs` (SM-2, Again/Hard/Good/Easy), `QuizQuestion` (MCQ/TF), `GlossaryTerm`, `MindNode`. `StudyParse` holds lenient parsers (letters, booleans, 0/1-based indexes, JSON or Markdown outline for the mind map) and the persistence helpers. |
| `StudyGen.kt` | Flashcards / quiz / study guide / glossary / mind map. **One request each**, over a page-ordered spread of the whole document (or Space); an optional focus topic puts the best passages first. |
| `AiStores.kt` | `AiStore`: Spaces, saved chats (with citations and follow-ups) and creations, as JSON under `filesDir/ai5/`. Scope = document content key or `space:<id>`. |
| `TextTools.kt` | `TextAction` (rewrite, grammar, explain, simplify, formal, shorter, longer, summarize, define, translate) + `TextTools.run(...)`: streaming. |
| `DocTranslator.kt` | Whole-document translation. Blocks come from PDFium char boxes. Batches of about 5,000 chars of `{"id":"text"}` JSON, about 1 request per batch. OVERLAY (layout kept: white box + fitted translated text in the same rect, RTL-aware) or TEXT_ONLY PDF. Page range supported. |
| `PiiDetector.kt` | **`PiiDetector.detect(text, kinds = all): List<PiiMatch(kind, start, end, value)>`**. Kinds: EMAIL, PHONE (intl, Gulf/Yemen), CARD (Luhn), IBAN (mod-97), NATIONAL_ID (labels or Saudi 10-digit), PASSPORT (label), DATE, URL, IP. Arabic-Indic digits work; matches never overlap. `mask()` for previews. Pure Kotlin, on device. |
| `PiiScanner.kt` | `PiiScanner.scan(session, kinds) → List<PiiHit(match, hit: SearchHit)>` and `hits(session)`. Rects come from `PdfSession.searchWith`, so they use exactly the redaction screen's search-hit convention. |
| `FormFillAi.kt` | Fields → local fill-profile match (names, labels, AR/EN synonyms) → one AI request for the rest (no invented personal data, options respected) → `FillSuggestion`s. `apply()` = `FormOps.fillFields`. |
| `CommentAi.kt` | Comments (`AnnotationWriter.list`) → one summary request → `addAsNote()` writes it as a NOTE annotation on page 1 (`AnnotationWriter.commit`). |
| `WholeBook.kt` | Whole-book material for the podcast / audio summary: direct text up to 12k chars, otherwise ≤ 6 cached segment-note requests. |
| `MindMapLayout.kt` | Tree layout (root on the right in RTL, collapsible), branch colours, Android-canvas drawing, `toPng()`, and `toPdf()` (vector, `android.graphics.pdf.PdfDocument`). |
| `Voice.kt` | `SpeechInput` (Android `SpeechRecognizer`, `ar-SA` default, partial results, RMS level, system-intent fallback) and `AnswerSpeaker` (AI voice via `AiVoiceEngine` when enabled, else system TTS; callbacks on the main thread). |

### ui/ai5 (new, package `com.smartdocs.editor.ui.ai5`)
| File | Content |
|---|---|
| `AiHubScreen.kt` | Hub for route `tool/ai`. Tabs: Chat · Summary · Study · Tools · Agent. `uri` = `file://…` or `space:<id>`, optional `#tab=chat\|summary\|study\|tools\|agent`. Shows the remaining proxy quota and a no-AI card. Also holds `citationRoute(c)` / `pageRoute(path, page)` = **`Routes.reader(uri, page)`**, plus `shareText` and `exportPdfAndShare`. |
| `DocSet.kt` | Documents of a session + background whole-document indexing with progress. |
| `CitedChatState.kt` / `CitedChatUi.kt` | Cited chat: retrieval → streaming (tail hidden) → parsed citations. Answers show superscript `[n]` links (`LinkAnnotation.Clickable`) and source chips "[2] ص 14"; both call `onCitation`. Also: follow-up chips, starter questions, copy/share/PDF with a sources list, saved chats (history sheet, temporary chat), mic and voice-chat buttons. |
| `VoiceUi.kt` | `MicButton` (RECORD_AUDIO permission, live partial bubble, system dictation fallback) and `VoiceChatDialog` (listen → ask → speak → listen; tap the orb to interrupt). |
| `SummaryPanel.kt` | Presets, length, bullets/paragraphs, audience, language, focus, request estimate, sections with page chips (open the reader), suggested questions (sent to chat), share/PDF. `LanguagePicker`. |
| `StudyPanel.kt`, `StudyViewers.kt`, `MindMapView.kt` | Generators + saved creations; flip cards with SRS; quiz with score, colour-coded answers, explanations and "go to page"; glossary; study guide; zoom/pan/collapse mind map with PNG/PDF export (share sheet). |
| `TextToolsSheet.kt` | Selection sheet: all `TextAction`s, target-language picker, Replace (when `onReplace` is given), Copy, Add as note, Share. |
| `PiiScanSheet.kt` | On-device PII scan with kind filters, masked list, checkboxes; `onApply(List<SearchHit>)`. |
| `FormFillSheet.kt` | Editable suggestions, each toggleable. `onValues(map)` fills in memory (reader), else writes a `_filled` copy. |
| `AiToolsPanel.kt` | Translate document, PII scan, smart form fill, smart bookmarks (outline over the whole book), comment summary → note, book podcast, read aloud. |
| `SpacesScreen.kt` | Route `tool/ai_spaces`: create/edit/delete Spaces (name, instructions, up to 20 PDFs) → hub `space:<id>`. |
| `DocAssistantSheet.kt` | Reader sheet: "اسأل المستند" (cited chat over the whole open document, reusing the session's page text) + the existing agent tab. |

### Edited (my area, small and additive)
- `ui/ai/AiScreen.kt`: now delegates to `AiHubScreen` (route `"ai"` unchanged; the old outline action moved to Tools → "فهرس ذكي").
- `ui/ai/AgentChatState.kt`: **the 12,000-char `trimDocument` is replaced** by `DocIndexer` + `Retriever` per turn, over the whole document.
- `ui/ai/AiSheet.kt`: the same. Questions get retrieved passages; quick actions get an even spread over every part of the document.
- `ui/voice/PodcastScreen.kt` (line ~93): `AiClient.trimDocument(s.allText())` → `WholeBook.material(ctx, client, DocIndexer.get(ctx, f))`, so the podcast / audio summary covers the whole book.

### Strings and tests
- **Strings**: `res/values/strings_ai5.xml` + `res/values-en/strings_ai5.xml`, 149 strings each, prefix `a5_`. Arabic is primary.
- **Tests** (`app/src/test/java/com/smartdocs/editor/core/ai5/`):
  - `RetrievalTest`: chunking, paragraphs, stemmer, BM25, specific vs generic retrieval, multi-document, spread.
  - `CitationsTest`: markers, Arabic digits, ranges, unknown numbers dropped, quote fallback, streaming `visible`, plain/sources text, JSON round-trip.
  - `PiiAndStudyTest`: PII kinds, Luhn/IBAN, labels, Arabic digits; SM-2; quiz, flashcard, glossary and mind-map parsers; lenient JSON.

## Hooks for the lead (exact)
1. **Spaces route**: `ui/tools/ToolScreens.kt`, after the line `"ai_settings" -> AiSettingsScreen(nav)` insert:
   `"ai_spaces" -> com.smartdocs.editor.ui.ai5.SpacesScreen(nav)`
2. **Tool card**: `ui/tools/ToolsScreen.kt`, after the line `ToolDef("podcast", R.string.ai2_podcast_title, Icons.Default.Podcasts, 3),` insert
   `ToolDef("ai_spaces", R.string.a5_spaces, Icons.Default.Workspaces, 3),` (`Icons.Default.*` is already imported via `filled.*`).
3. **Reader AI sheet with citations**: `ui/reader/ReaderScreen.kt`. Find the block that starts with `if (showAi) com.smartdocs.editor.ui.ai.DocAgentSheet(agentHost, onDismiss = { showAi = false },` (3 lines, ending with `onPodcast = { … }) `) and replace it with:
   ```kotlin
   if (showAi) state.file?.let { f -> com.smartdocs.editor.ui.ai5.DocAssistantSheet(agentHost, f, state.password, state.pageCount, { state.pageText(it) },
       onCitation = { c -> showAi = false; if (c.filePath == f.absolutePath) state.jumpTo(c.page) else nav.navigate(com.smartdocs.editor.ui.ai5.citationRoute(c)) },
       onDismiss = { showAi = false },
       initialQuestion = aiText?.let { "اشرح هذا النص:\n$it" },
       onPodcast = { showAi = false; nav.navigate(Routes.tool("podcast", state.fileUri)) }) }
   ```
   Chats are saved, so the history button brings the conversation back after a citation closes the sheet.
4. **Selection menu → text tools** (rewrite / grammar / explain / translate…): `ui/reader/ReaderScreen.kt`.
   - (a) Next to `var showAi by remember { mutableStateOf(false) }` add:
     `var textTools by remember { mutableStateOf<Pair<TextSelection, com.smartdocs.editor.core.ai5.TextAction?>?>(null) }`
   - (b) In the `SelectionMenu(sel, state, …)` call, replace `onTranslate = { aiText = it; showAi = true }, onAskAi = { aiText = it; showAi = true },` with:
     `onTranslate = { textTools = sel to com.smartdocs.editor.core.ai5.TextAction.TRANSLATE }, onAskAi = { textTools = sel to com.smartdocs.editor.core.ai5.TextAction.EXPLAIN },`
   - (c) After the `if (showAi) …` block from hook 3 add:
   ```kotlin
   textTools?.let { (sel, act) -> com.smartdocs.editor.ui.ai5.TextToolsSheet(sel.text, act, docContext = null, onReplace = null,
       onAddNote = { t -> sel.rects.firstOrNull()?.let { r -> val top = maxOf(r.top, r.bottom)
           state.addAnnot(com.smartdocs.editor.core.pdf.Annot(kind = com.smartdocs.editor.core.pdf.AnnotKind.NOTE, page = sel.page,
               rects = listOf(android.graphics.RectF(r.left, top, r.left + 20f, top - 20f)), text = t, author = state.authorName)) } },
       onShare = { ReaderIntents.shareText(ctx, it) }, onDismiss = { textTools = null; state.selection = null }) }
   ```
   Replace-in-document: once the content-edit module offers a replace-selection API, pass it as `onReplace`. The sheet then shows «استبدال» for rewrite/grammar/translate.
5. **Redaction, smart detection**: `ui/security/RedactScreen.kt`.
   - Add `var showPii by remember { mutableStateOf(false) }` next to `var confirm …`.
   - Inside `if (session != null) { … }` after the `SingleChoiceSegmentedButtonRow { … }` add:
     `TextButton(onClick = { showPii = true }) { Text(stringResource(R.string.a5_pii_title)) }`
   - At the end of the composable add:
     `if (showPii) com.smartdocs.editor.ui.ai5.PiiScanSheet(file, password, session, onApply = { hs -> hs.forEach { if (!isSelected(it)) toggleHit(it) }; hits = hs; mode = RedactMode.SEARCH; showPii = false }, onDismiss = { showPii = false })`
   - The hits are normal `SearchHit`s from `PdfSession.searchWith`. Other modules can call `PiiDetector.detect(text)` directly.
6. **Reader forms bar, smart fill**: wherever the forms5 bar has room (e.g. `ui/forms5/InPageFormBar.kt`), add an action that opens
   `com.smartdocs.editor.ui.ai5.FormFillSheet(state.file!!, state.password, onValues = { m -> state.forms5.setValues(m) }, onDismiss = { … })`. It is also available in Hub → Tools, where it writes a `_filled` copy.
7. **Manifest** (SpeechRecognizer on API 30+ needs package visibility; RECORD_AUDIO is already declared): inside `<manifest>` add
   `<queries><intent><action android:name="android.speech.RecognitionService" /></intent></queries>`
   Without it, `MicButton` falls back to the system dictation activity and voice chat shows "not available".
8. **Optional deep links** (scanner result sheet, home): `nav.navigate(Routes.tool("ai", Uri.fromFile(pdf).toString() + "#tab=summary"))` (or `#tab=chat|study|tools`).
9. **Proxy**: no change required. All batching respects `DAILY_LIMIT = 60` (`ai-proxy/worker.js`):
   - summary ≤ 7 requests;
   - study item 1;
   - question 1 (+1 for starter questions when no summary is cached);
   - whole-book podcast ≤ 6 + script;
   - translation ≈ 1 per 5,000 chars (estimate shown, refused up front when it does not fit).
   Raise `DAILY_LIMIT` if heavy translation use is expected.

Citation → reader navigation: the hub uses `nav.navigate(Routes.reader(Uri.fromFile(File(c.filePath)).toString(), c.page))`, page 0-based, the same as the `page` argument of `Routes.READER`. The reader sheet jumps in place (hook 3).

## Status per feature
| Feature | Status |
|---|---|
| Whole-document answers (replacing 12k truncation) | DONE: chunked BM25 retrieval over every page, cached index. Used by the hub chat, the reader sheet, `AgentChatState` and `AiSheet`. |
| Numbered citations opening the reader at the page | DONE: `[n]` superscript links + source chips → `Routes.reader(uri, page)` (hub) or `jumpTo` (reader, hook 3). Quotes are verified against the passage (fallback: best sentence). The passage highlight on the page is NOT done; it needs a `searchHits` hook in hot files (`PassageLocator` from ai4 not rebuilt). |
| Customisable summary | DONE: length, bullets/paragraphs, language, audience, focus, presets, sections with page chips, cache, share/PDF, map-reduce for long books. |
| Suggested questions + follow-ups | DONE: starters (free from the summary cache, else 1 request) and follow-ups parsed from each answer's tail. |
| Flashcards with spaced repetition | DONE: SM-2 (Again/Hard/Good/Easy), due queue, saved state. |
| Quiz with score + explanations | DONE: MCQ + true/false, score, colour-coded review, explanation, go-to-page. |
| Study guide, glossary | DONE. |
| Mind map exportable as image/PDF | DONE: zoom/pan/collapse; PNG and vector PDF via the share sheet. |
| Arabic voice dictation | DONE: SpeechRecognizer `ar-SA`, partial results, permission, system fallback (needs hook 7 on API 30+). |
| Voice chat | DONE: listen → cited answer → AI voice / system TTS → listen. PARTIAL for barge-in: you interrupt by tapping the orb; there is no speech detection during playback. |
| Spaces | DONE (local): several files questioned together, instructions, per-Space saved chats and creations, multi-document citations name the file. Not done: URL/pasted-text sources and sharing. |
| Saved chats | DONE: per document / Space, open, delete, temporary chat. |
| Translation that keeps layout | DONE (overlay per text block, RTL-aware, shrink-to-fit) + text-only. Limits: text inside images is not translated (needs OCR); rotated pages are not specially handled; the white box covers background art behind text. |
| Rewrite / grammar / explain for selected text | DONE (`TextToolsSheet`, hook 4). Replace-in-document waits for a content-edit API (`onReplace`). |
| PII detector | DONE: `PiiDetector.detect(text): List<PiiMatch>` + `PiiScanner` (SearchHits) + `PiiScanSheet` (hook 5). AI-based name/address detection is not done (on-device regex only, by design for privacy). |
| Form-fill suggestions | DONE: profile match + one AI request, editable, in-memory (hook 6) or `_filled` copy. |
| AI summary of comments as a note annotation | DONE: Hub → Tools → "ملخص التعليقات" writes a `_comments` copy with a NOTE on page 1. Not wired into the reader's comments panel (hot files); call `CommentAi.summarize` + `addAsNote` there if wanted. |
| Podcast / read-aloud covering the whole book | DONE: the podcast uses `WholeBook` (PodcastScreen edited). Read aloud already walks every page (`ReadAloudController` with `pageText`); the hub links to it. |
| Proxy daily limit / batching | DONE: `AiCalls` counter, estimates in the UI, up-front refusal, 429 → clear Arabic message. |
| Existing AI settings / proxy | DONE: everything uses `AiClient(settings)` (aiEndpoint/aiModel/aiApiKey, or the ai-proxy when no key). |

## Limitations
- Retrieval is lexical (BM25 + Arabic stemming). There is no embedding re-rank because the proxy has no `/embeddings` route.
- Stores are JSON files, not Room, and are not synced.
- The local quota counter only estimates the proxy's per-IP counter; a 429 from the proxy is still handled.
