# MODULE ai4 — retrieval, citations, summary, study tools, voice, spaces, AI tools

I did not run Gradle (Round 4 rule). I checked library APIs with `javap`: PdfBox `LayerUtility`, `PDPageTree.insertAfter`, `PDFormXObject.getBBox`, `AppendMode`, `Matrix`, and Compose ui-text 1.7.8 `LinkAnnotation.Clickable` / `TextLinkStyles`. I also checked existing code: `PptxBuilder`, `SlideDoc`, `FormOps`, `PdfOps.redact/setOutline/arabicFont`, `AnnotationWriter.commit` and `ArabicNormalizer`.
I compiled the pure logic on the desktop with kotlinc 2.1 and ran it: normaliser (delegates to `core/search/ArabicNormalizer`), tokeniser/stemmer, chunking, BM25, retrieval, citation parsing, and the quiz / mind-map / card parsers.
Strings: `res/values/strings_ai4.xml` + `res/values-en/strings_ai4.xml` (210 strings, prefix `a4_`). The UI and docs name no vendor products.

## Files
### core/ai (new)
| File | Content |
|---|---|
| `AiText.kt` | `AiNormalizer` (delegates to `core.search.ArabicNormalizer` with ta-marbuta folding; it keeps a local fallback), `AiTokens` (stop words + light Arabic stemmer), `AiLang` (detect, Arabic names, rule "answer in the document language unless asked"), `AiJson` (lenient JSON from model answers, Arabic-Indic digits). |
| `DocIndex.kt` | `DocChunk` (page/paragraph, label `p12¶3`), `DocIndex` (BM25, `spread()`, `material()` with `[صفحة N]`), `DocIndexer.get(ctx, file, pw, pageCount?, pageText?, progress)`: memory LRU + `cacheDir/ai4_index/<contentHash>.json`. The hash is size + first/last 64 KB. |
| `Embeddings.kt` | Optional `POST /embeddings` re-rank. It is remembered as unavailable after the first failure. Vectors are cached in `<key>.emb`. |
| `Retrieval.kt` | `Source`, `Retriever.retrieve(ctx, client, indexes, query, maxChars=7500, k=10)`: BM25 per doc → optional embedding re-rank → page-diverse selection; generic questions get a spread; multi-doc keeps every relevant doc. `sourcesBlock()`. |
| `Citations.kt` | Citation format (below), `CitationFormat.visible/parse/plain`, JSON (de)serialisation. |
| `PassageLocator.kt` | `locate/locateNear(session, page, quote) → SearchHit` (normalised fuzzy match → char boxes → line rects, PDF space). |
| `DocQa.kt` | Grounded Q&A prompt with citations + persona/skills; `starterQuestions()`. |
| `SummaryGen.kt` | `SummaryOptions` (length, bullets/paragraphs, language, audience, focus), `StructuredSummary` (overview, key points, sections with pages, suggested questions). One request for short docs; map-reduce for long ones (≤ 6 segments + 1). Cached in `filesDir/ai4/summary/`. |
| `StudyGen.kt` | Flashcards, quiz (MCQ + true/false with explanations and pages), study guide, glossary, mind map. Strict JSON schemas plus parsers that also accept malformed model output. |
| `AiStores.kt` | `CreationStore` (saved study items + SM-2 style `CardSrs`), `ChatStore` (conversations per document key / space), `SpaceStore`. JSON files under `filesDir/ai4/`, so no Room change. |
| `TextTools.kt` | Selection actions: rewrite, simplify, formal, friendly, shorter, longer, proofread, explain, summarise, define, translate. |
| `PdfTextLayout.kt` | Arabic-shaped wrap, shrink-to-fit boxes, flowing multi-page writer, Markdown→PDF. |
| `DocTranslator.kt` | Whole-document translation that keeps the layout: SIDE_BY_SIDE (original page as a form + a panel with each translated block at its position), OVERLAY (blocks replaced in a copy), TEXT_ONLY. It translates in batches of `{id:text}` JSON and caches results. |
| `SlidesGen.kt` | Slide outline → `.pptx` (blank `PptxBuilder` package + public `SlideDoc.newSlide/addTextBox/save`), 16:9 PDF with notes, Markdown. |
| `PiiDetector.kt` | Regex library (email, phone incl. Gulf/Yemen formats, IBAN, cards with Luhn, IDs, dates, URLs) + optional AI names/orgs/addresses → `PiiFinding(hit: SearchHit)`. |
| `FormFillAi.kt` | Fields + printed label near each widget → fill-profile match + AI suggestions → `FormOps.fillFields`. |
| `MindMapLayout.kt` | Tree layout (RTL root on the right), PNG and vector PDF export (Android `PdfDocument`). |
| `AiExtras.kt` | `AiBookmarks` (layout heading candidates → AI levels → `setOutline`), `DocReview` (table → CSV, contract/risk/analysis report), `SkillStore`/`AiSkill`, `VisionAsk` (image_url chat), `ImageGen` (`/images/generations`), `AiUsage` (`GET /usage`), `WebSearch` (`GET /search`), `ReadingOrderText` (C1). |

### core/ai (edited, now mine)
- `DocCommands.kt` / `DocAgent.kt` / `DocActions.kt`: new agent tools `highlight_text(query, color)` and `redact_pattern(kind)`. Both have confirmation cards and undo.

### ui/ai
| File | Content |
|---|---|
| `AiScreen.kt` (rewritten) | Hub (route `ai`): one file, several files, or `uri="space:<id>"`. Optional `#tab=summary/study/tools/agent`. Tabs: Chat · Summary · Study · Tools · Agent. Shows usage and a no-AI card. |
| `DocSetState.kt` | Document set + index loading; `CitationBus` (citation hand-over to the reader). |
| `CitedChatState.kt`, `CitedChatUi.kt` | Grounded chat: retrieval, streaming, `[n]` superscript links, source chips, follow-ups, starters, copy/share/PDF, saved/temporary chats, history sheet, skill chips, web toggle, mic. |
| `VoiceInput.kt` | `SpeechInput` (SpeechRecognizer, partial results, Arabic default), `MicButton` (permission + system-activity fallback). |
| `HandsFreeDialog.kt` | Voice loop: listen → answer → speak (AI voice via `AiVoiceEngine` when enabled, else system TTS) → listen. Tap the orb to interrupt; mute/stop/close. |
| `SummaryPanel.kt` | Options editor, presets (5 points, executive, study), section cards with page chips, suggested questions, `LanguagePicker`. |
| `StudyPanel.kt`, `StudyViewer.kt`, `FlashcardsView.kt`, `QuizView.kt`, `MindMapView.kt` | Generators + saved creations; flip cards with SRS; quiz with score and mistake review; zoom/pan/collapse mind map; guide, glossary and slides views; exports (PDF/MD/PNG/PPTX). |
| `AiToolsPanel.kt` | Translate document, smart PII redaction, AI form fill, AI bookmarks, extract table (CSV), contract review, analysis, AI image, skills, podcast. |
| `PiiScanSheet.kt`, `FormFillAiSheet.kt`, `TextToolsSheet.kt`, `ImageAskSheet.kt`, `SkillsUi.kt`, `SpacesScreen.kt`, `DocAssistantSheet.kt` | Reusable sheets for the hooks below. |
| `AiSheet.kt`, `AgentChatState.kt` (edited) | Whole-document retrieval in place of the 12k truncation. |

## Citation format (B1)
Prompt passages are numbered `[1]…[k]` (`Retriever.sourcesBlock`; file names are shown when several documents are involved). The model writes `[n]` / `[n، m]` after claims, then a line `@@@` followed by `{"quotes":{"1":"exact 5–15 words"},"followups":[…]}`. `CitationFormat.parse` → `CitedAnswer(text, citations[n, docKey, filePath, fileName, page(0-based), quote, passage], followUps)`. If a quote is missing or wrong, the parser uses the passage sentence that overlaps the cited sentence most.

## Hooks for the lead (I did not edit these files)
1. **Routes** (`ui/tools/ToolScreens.kt`, after `"ai_settings" -> AiSettingsScreen(nav)`):
   `"ai_spaces" -> com.smartdocs.editor.ui.ai.SpacesScreen(nav)`. The `"ai"` route already exists; it now accepts `space:<id>` and `#tab=`. Optional Tools card: `ToolDef("ai_spaces", R.string.a4_spaces, Icons.Default.Workspaces, …)`.
2. **Reader AI sheet**: in `ReaderScreen.kt` replace the `if (showAi) com.smartdocs.editor.ui.ai.DocAgentSheet(...)` block with:
```kotlin
if (showAi) state.file?.let { f -> com.smartdocs.editor.ui.ai.DocAssistantSheet(agentHost, f, state.password, state.pageCount, { state.pageText(it) },
    onCitation = { c -> state.session?.let { s -> scope.launch { com.smartdocs.editor.core.ai.PassageLocator.locateNear(s, c.page, c.quote)
        ?.let { hit -> state.searchHits = listOf(hit); state.searchIndex = 0; state.jumpTo(hit.page) } ?: state.jumpTo(c.page) } } },
    onPage = { state.jumpTo(it) }, onDismiss = { showAi = false },
    initialQuestion = aiText?.let { "اشرح هذا النص:\n$it" },
    onPodcast = { showAi = false; nav.navigate(Routes.tool("podcast", state.fileUri)) }) }
```
   The highlight reuses the search-hit overlay (`SearchHit` rects are in PDF space).
3. **Reader citation from the hub**: after the session opens (where `startPage` is applied), add:
   `com.smartdocs.editor.ui.ai.CitationBus.take(state.file!!.absolutePath)?.let { c -> PassageLocator.locateNear(state.session!!, c.page, c.quote)?.let { state.searchHits = listOf(it); state.searchIndex = 0 } }`
4. **Selection menu** (`ReaderOverlays` SelectionMenu, SHL-4 owner): AI chip → `TextToolsSheet(text = sel.text, initial = null, docContext = null, onReplace = /* only when in content-edit: EditModeHost replaceText */ null, onAddNote = { t -> /* add NOTE annot at sel.rects.first() */ }, onDismiss = …)`.
5. **Snapshot / image long-press**: `ImageAskSheet(bitmap, onInsertText = null) { … }` (AI-10/11).
6. **Redaction screen** (`ui/security/RedactScreen.kt`): add a button «كشف ذكي» → `PiiScanSheet(file, password, onApply = { hits -> hits.forEach { if (!isSelected(it)) toggleHit(it) } ; show = false }, onDismiss = …)`. The hits use the same convention as its search hits.
7. **Reader forms bar**: «تعبئة ذكية» → `FormFillAiSheet(file, password, onValues = { m -> state.forms.setValues(m) /* merge into in-memory values */ }, onFile = {}, onDismiss = …)`.
8. **After scanning** (B16, `ScannerScreen` result sheet): buttons `nav.navigate(Routes.tool("ai", Uri.fromFile(pdf).toString() + "#tab=summary"))` and `… + "#tab=chat"`.
9. **Read aloud order** (C1, `ReaderScreen.kt` line ~136): change the provider to `{ p -> com.smartdocs.editor.core.ai.ReadingOrderText.page(state.file!!, state.password, p) ?: state.pageText(p) }`. Sentence highlighting stays with the TTS owner.
10. **Podcast** (`ui/voice/PodcastScreen.kt:95`, `core/voice` owner): replace `AiClient.trimDocument(s.allText())` with `DocIndexer.get(ctx, f).let { it.material(it.spread(12000)) }`, so the script covers the whole book.
11. **Manifest** (SpeechRecognizer on API 30+ needs package visibility):
    `<queries><intent><action android:name="android.speech.RecognitionService" /></intent></queries>` (RECORD_AUDIO is already declared).
12. **Proxy** (`ai-proxy/worker.js`, optional; the client degrades gracefully without it): add routes `POST /embeddings` (forward to `EMBED_URL`, forced `EMBED_MODEL`), `GET /usage` → `{"chat":{"used","limit"},"tts":{"used","limit"}}` from the KV counters, `GET /search?q=` → `{"results":[{title,url,snippet}]}` (configurable provider key), and `POST /images/generations` (forced model, small daily quota). Chat requests from map-reduce summaries and translations count against `DAILY_LIMIT` (60). Raise it or give documents with heavy use more quota.

## Status per backlog row (owner ai4)
| ID | Status |
|---|---|
| B1 citations | DONE: numbered citations, superscript links and chips, jump plus highlight (hook 2/3), copy/share/PDF with a sources list. |
| B2 retrieval | DONE: page/paragraph chunks, Arabic BM25, optional embeddings, per-hash cache. The 12k truncation is gone from AiSheet and the agent. |
| B3 summary | DONE: options (length/format/language/audience/focus), presets, sections with page chips, map-reduce, cache, share/PDF. |
| B4 suggestions | DONE: starters from the summary cache or a dedicated request; follow-ups come from the answer tail. |
| B5 mic | DONE: SpeechRecognizer (Arabic default, partial results) plus system-activity fallback. A Whisper fallback is not done because the proxy has no `/audio/transcriptions` route. |
| B6 hands-free | PARTIAL: full voice loop with AI or system voice. Barge-in is by tapping the orb; it does not detect speech while audio plays (echo makes recognition unreliable without AEC). |
| B7 chat history | DONE: saved per document/space, open/delete/delete all, temporary chat. PARTIAL for the global "AI off" switch, because it needs a `SettingsStore.aiEnabled` field that another module owns. Hook: hide the AI entry points when it is false. |
| B8 study tools | DONE: flashcards with SRS, quiz (MCQ/TF, score, explanations, review, page jump), study guide, mind map (zoom/pan/collapse, PNG/PDF), glossary, saved creations. |
| B9 spaces | DONE (local): spaces with files, persona and their own chats/summaries/creations; multi-doc citations name the file. Not done: URL/pasted-text sources and link sharing (needs a proxy `/fetch` and the collab worker). |
| B10 AI edit via chat | PARTIAL: new `highlight_text` and `redact_pattern` agent tools. `replace_text`/`rewrite_block` are not done because they need the content-edit module's `ContentEdit.replaceText` API on a block (edit module). |
| B11 rewrite selection | DONE (`TextToolsSheet`). Replace-in-document goes through the `onReplace` hook (content-edit owner). |
| B12 translate document | DONE: side-by-side, overlay and text-only, with page range. Text inside images is not translated (that would need OCR). |
| B13 presentation | DONE: outline → PPTX (SlideDoc) / PDF / Markdown. PPTX has no speaker notes (SlideDoc has no notes API); notes are in the PDF/MD. |
| B14 podcast editing | NOT DONE (owner of `core/voice` / `ui/voice` is ai2): `PodcastGenerator` would need focus/length parameters and an editable transcript. The only change in my reach is hook 10. |
| B15 video summary | NOT FEASIBLE this round: P3; it needs a MediaCodec/MediaMuxer encoder pipeline that cannot be checked without a device. The slide JSON from B13 is ready as its input. |
| B16 AI on scans | PARTIAL: hub deep link `#tab=summary/chat` is ready; the button on the scan result sheet is hook 8 (scanner owner). |
| B17 contract intelligence | DONE (single document + multi-document comparison through spaces): `DocReview.review(CONTRACT)` report with page refs → PDF. |
| C1 read-aloud order | PARTIAL: `ReadingOrderText.page()` (column/RTL-aware blocks + form fields), wired by hook 9. Sentence highlighting belongs to the TTS owner. |
| AI-1 | DONE (= B12). |
| AI-2 | PARTIAL: rewrite/proofread/shorter/longer/formal tools are done; the diff preview and apply-in-block need the content-edit hook. |
| AI-3 | DONE (= B1). |
| AI-4 | DONE: retrieval + multi-file chat (hub «+» adds files). Image attachments in the chat go through `ImageAskSheet`. |
| AI-5 | DONE (= B7; temporary chat included). The Home «المحادثات» list is not done; `ChatStore.keys()` is ready for the home owner. |
| AI-6 | DONE: preset + custom skills, enable/disable, export JSON / import from the clipboard, `/skill` chips in the chat. |
| AI-7 | PARTIAL (= B10). |
| AI-8 | PARTIAL: image generation (`/images/generations`) to PNG. Not done: placing it with `ContentEdit.addImage` (edit module) and prompt edits of an existing image (`/images/edits` multipart). |
| AI-9 | DONE: mind map as a native Compose tree with PNG/PDF export (no Mermaid). Not done: flowchart and timeline types. |
| AI-10 | DONE: `ImageAskSheet` (needs a vision model; it shows a clear error otherwise). |
| AI-11 | DONE: LaTeX and step-by-step solving presets. Not done: MathML tagging. |
| AI-12 | DONE in the hub tools (`AiBookmarks`). For the reader bookmarks panel, call `AiBookmarks.generate` and `apply` (bookmarks owner). |
| AI-13 | DONE: extract to table (CSV), contract review, risk analysis, document analysis (PDF reports with page refs). |
| AI-14 | PARTIAL: chat toggle + client `WebSearch`. It needs the proxy `/search` route (hook 12); without it, answers silently stay document-only. |
| AI-15 | DONE: each answer has a Word button (`DocxWriter`, text with its sources list). |
| AI-16 | NOT FEASIBLE: needs a new dependency (on-device model / ML Kit GenAI); the brief does not allow new dependencies. A local server endpoint already works through AI settings. |
| AI-17 | NOT FEASIBLE: there is no stdio MCP on Android, and remote MCP would need proxy work for little user value. |
| AI-18 | PARTIAL: the hub shows usage from proxy `GET /usage` (hook 12). Nothing is shown with an own key. |
| AI-19 | DONE through spaces + study creations (flashcards, quiz, guide, mind map, glossary, slides, summary). Anki CSV export is not done. |

## Limitations
- Proxy quota: a long-document summary costs ≤ 7 requests; translation costs about 1 request per 4,500 characters. Requests per day are limited to 60 on the proxy.
- Overlay translation covers source blocks with white boxes, which hides background art under the text. Rotated pages get a reflowed translation page instead.
- Side-by-side translation of rotated pages relies on `LayerUtility.importPageAsForm`'s rotation handling.
- Chat/creation stores are JSON files (not Room), and nothing synchronises them.
