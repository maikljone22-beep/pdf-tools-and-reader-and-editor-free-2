# AI + Office gaps: done list (branch feature/old-plan-ai-office)

Session: ai-office (2026-10-27). Sources: `gaps/OLD_PLAN_AUDIT.md` (AI section, Office line) and `gaps/fcmp_28-42.md` §35.
Nothing here was tested on a device. Every commit was built and unit-tested by GitHub Actions only (no local gradle).

Paths below are under `app/src/main/java/com/smartdocs/editor/` unless they start with `ai-proxy/` or `app/`.

## Status legend
- **DONE**: implemented in the app (file named).
- **DONE + NEEDS-SERVER**: the app side is done. It works with the user's own OpenAI-compatible key; through the
  app proxy it needs the new route in `ai-proxy/worker.js`, which is written but **not deployed**. Without it the app
  shows `ai6_not_configured` ("this feature is not configured …") instead of an HTTP error.
- **PARTIAL**: works, with the named part missing.
- **ALREADY-PRESENT**: was in the code before this branch (evidence given).
- **NOT-FEASIBLE**: not done, reason given.
- **OWNED BY pdf-3c**: the coordinator moved it to branch `feature/nav-prefs`.

## Proxy routes added (`ai-proxy/worker.js`, `wrangler.toml`, `README.md`), not deployed
The proxy stays OpenAI-compatible (Groq chat by default); no Anthropic calls are involved.

| Route | Purpose | Server config |
|---|---|---|
| `GET /capabilities` | which media routes have keys; the app asks once | — |
| `GET /usage` | today's per-device counters from KV `RATE` | KV binding `RATE` |
| `POST /chat/completions` with `image_url` parts | vision (larger body allowed, model switched) | `VISION_MODEL` |
| `POST /images/generations` | image generation | `IMAGE_URL`, `IMAGE_MODEL`, secret `IMAGE_KEY` |
| `POST /audio/transcriptions` | audio file to text (multipart passthrough) | `STT_URL`, `STT_MODEL`, `STT_KEY` or `PROVIDER_KEY` |
| `POST /embeddings` | passage re-ranking | `EMBED_URL`, `EMBED_MODEL`, secret `EMBED_KEY` |
| `POST /web/search` | live web search in chat (Brave API) | secret `SEARCH_KEY` |

The client side is `core/ai6/AiMedia.kt`. It covers capabilities, the not-configured mapping, vision, images, transcription, embeddings, web search with a keyless DuckDuckGo fallback, and usage. It is tested by `app/src/test/.../core/ai6/AiMediaTest.kt`.

## OLD_PLAN_AUDIT.md: AI rows

| Row | Status | Where / note |
|---|---|---|
| B1 citations highlight the passage | DONE | `core/ai6/QuoteLocator.kt` (Arabic-normalised quote match), `ui/reader/ReaderAi6.kt` (`highlightQuote`, 8 s highlight), `ReaderScreen.kt` onCitation. A citation into another file opens that file at the page without the highlight. |
| B6 voice barge-in | DONE | `core/voice/BargeIn.kt` (VadGate + AudioRecord with AEC/NS), `ui/ai5/VoiceUi.kt` (interrupt, mic mute, barge-in toggle). The threshold may need tuning on real devices. |
| B9 Spaces URL / pasted-text sources + sharing | DONE | `core/ai6/HubWebText.kt` (HTML to text), `core/ai6/SpaceSources.kt` (`.sdspace` bundle export/import), `ui/ai5/SpacesScreen.kt`, `DocSet.kt`, `DocIndexer.getText` |
| B10 / AI-7 AI edit via chat | DONE | `core/ai/DocCommands.kt`, `DocActions.kt`, `DocAgent.kt`, `core/ai/AiEditPatterns.kt`. Tools `highlight_text`, `redact_pattern`, `replace_text`, `rewrite_text`, with the existing confirm cards and undo. |
| B11 rewrite selection replaces the text | DONE | `ReaderScreen.kt` onReplace → `ReaderAi6.replaceSelection` (content-edit replace, one undo step); rewrite chip in `ReaderOverlays.kt`. A selection that spans several text blocks is not replaced (message shown; copy / note still work). |
| B12 side-by-side translation | DONE | `ui/ai6/SideBySideScreen.kt` (route `ai_side_by_side`), `core/ai5/DocTranslator.kt`, `ui/ai5/AiToolsPanel.kt` |
| B13 generate slides PPTX / PDF / MD | PARTIAL | `core/ai6/SlidesGen.kt`, `core/ai6/SlideRender.kt`, `ui/ai6/TDocTools.kt`; the PPTX opens in the Office slide editor. Speaker notes are in the PDF and MD only (no notes master in the PPTX), and there is no Spaces entry yet. |
| B14 podcast editing | DONE | `core/voice/PodcastScript.kt`, `ui/voice/PodcastEditor.kt`, `ui/voice/PodcastScreen.kt`, `core/voice/PodcastGenerator.kt`: focus / length, editable transcript (text, host/guest swap, add/delete/reorder), re-synthesis from the per-line cache, chapters, duplicate, share. |
| B15 video summary (narrated slides) | PARTIAL | `core/ai6/VideoSummary.kt`: MediaCodec H.264 + AAC through MediaMuxer, with system-TTS narration. AI voices are not used (MP3 decode not added). Encoders vary by device and this is untested. |
| B16 AI buttons after scanning | DONE | `ui/scanner/ScanAi6.kt` (Summarize / Ask bar), `ScannerScreen.kt`. Image-only scans are OCRed by the hub indexer. |
| B17 contract review / table to CSV / analysis / risk | DONE | `core/ai6/DocReview.kt`, `ui/ai6/TDocTools.kt`: key terms with page citations, analysis, risk, compare up to 10 contracts, tables to CSV; export CSV / PDF / share. |
| C1 read aloud: reading order, sentence highlight, fields | DONE | `core/ai6/ReadingOrder.kt` (column clustering, RTL column order, sentence pieces), `core/tts/ReadAloudController.kt` (`onRangeStart`, `spoken`), `ui/reader/PageCanvas.kt` overlay, `ReaderAi6.kt`. With an AI voice the whole spoken piece is highlighted, not the word. |
| Smart fill in the reader form bar | DONE | `ui/forms5/InPageFormBar.kt` AI button → `ui/ai5/FormFillSheet`, one undo step. It fills all suggested fields (no single-field mode). |
| AI-4 files + images in hub chat | DONE + NEEDS-SERVER (vision) | `ui/ai5/AiHubScreen.kt`, `ui/ai5/CitedChatState.kt`, `ui/ai6/HubChatInput.kt`: more PDFs, gallery, camera, page snapshot |
| AI-5 Home "Conversations" | DONE | `ui/ai6/HubConversations.kt` (Home card + route `ai_chats`), `ui/home/HomeScreen.kt`, `core/ai5/AiStores.kt` (`allChats`, chat file list) |
| AI-6 skills | DONE | `core/ai6/HubSkills.kt`, `ui/ai6/SkillsScreen.kt` (route `ai_skills`): presets + custom, enable, JSON export/import, chips and `/id` in the chat |
| AI-8 image generation | PARTIAL + NEEDS-SERVER | `ui/ai6/ImageGenScreen.kt` (route `ai_image`): generate, save, share, insert into a PDF copy. The insert is not a reader undo step, and prompt edits of an existing image (`/images/edits`) are not done. |
| AI-10 ask about an image / chart | DONE + NEEDS-SERVER (vision) | hub attach (image, camera, page snapshot) + "chart" skill; `ReaderScreen.kt` passes the current page for the snapshot |
| AI-11 LaTeX / step-by-step presets | DONE | `core/ai6/HubSkills.kt` presets steps / math / latex, `ui/ai6/ChatRich.kt` (code and LaTeX blocks with copy) |
| AI-14 web search toggle | DONE + NEEDS-SERVER | chip in the chat; `[W1]…` sources and link chips. Proxy `/web/search` (Brave); keyless DuckDuckGo instant answers otherwise. |
| AI-15 DOCX export of answers | DONE | per-answer Word button + whole chat to Word (`core/pdf/DocxWriter` via `core/ai6/ChatExport.kt`, `ui/ai5/CitedChatUi.kt`) |
| AI-18 usage | DONE + NEEDS-SERVER | `ui/ai6/HubUsage.kt` in the hub and `ui/ai/AiSettingsScreen.kt`: server counters (`/usage`), else the local counter, else "own key: no limit" |
| AI-19 Anki export | DONE | `core/ai6/ChatExport.kt`, `ui/ai6/HubAnki.kt`, `ui/ai5/StudyViewers.kt`: Anki TSV with `#separator`/`#deck`/tags headers, or CSV |
| Embeddings re-rank | DONE + NEEDS-SERVER | `core/ai6/ChatRerank.kt`: BM25 candidates re-scored by cosine with disk-cached chunk vectors; silent BM25 fallback |
| Audio file transcription | DONE + NEEDS-SERVER | `ui/ai6/TranscribeScreen.kt` (route `ai_transcribe`): pick audio (≤24 MB) → editable text → TXT / PDF, ask, summarise |
| PII regex only | DONE | `core/ai6/AiPii.kt`, `ui/ai5/PiiScanSheet.kt` (opt-in AI pass, merged with the regex hits; new kinds NAME / ADDRESS) |
| Multi-file chat only via Spaces | DONE | = AI-4 (hub adds files) |
| AI-off master switch (Commands5.kt:83, ToolScreens routes, deep links) | OWNED BY pdf-3c | Route gating left to `feature/nav-prefs`. **pdf-3c must also gate the new route ids:** `ai_side_by_side`, `ai_image`, `ai_transcribe`, `ai_chats`, `ai_skills`, `ai_help` (all start with `ai_`). This branch does guard its own new entry points with `settings.aiEnabled`: selection chips, form-bar AI, scan AI bar, Office AI command, help chat, image / transcribe screens, AI voices (`AiSpeech.available`, `AiVoiceEngine.aiSwitchOn`), AI alt text (`core/tools5/T5Hooks.kt`). |

## OLD_PLAN_AUDIT.md: Office rows

| Row | Status | Where / note |
|---|---|---|
| Print for Word / Excel / PowerPoint | DONE | `ui/office/Office6.kt` `OfficePrint` (state → `ToPdf.convert` → `core/util/PdfPrint`), ⋮ menu in `OfficeScreen.kt` + ribbon buttons |
| Read aloud in Office | DONE | `ui/office/Office6.kt` `OfficeSpeaker` (system TTS; Word blocks, Excel selection or used range row by row, PowerPoint slide text + notes; follows the current item) |
| Find (and replace) in Excel and PowerPoint | DONE | `core/office5/OfficeFind.kt` (`SheetFind`, `SlideFind`, `TextMatch`), `FindBar` options in `OfficeCommon.kt`, `SheetEditor.kt`, `SlideEditor.kt`. Word find/replace was ALREADY-PRESENT (`WordState`). A PowerPoint match that spans two text runs is not found. |
| Office command registry used by AI commands | DONE | `core/office5/OfficeCommands.kt` (about 70 ids, AI prompt + parser), `OfficeCommandRegistry.run("word.bold")` in `ui/office/Office6.kt`; AI command dialog (commands with confirm, Word paragraph rewrite, Excel formula). The older ribbon buttons were not rewired to the registry. |

## fcmp_28-42.md §35: every PARTIAL / MISSING row

| §35 row | Status | Where / note |
|---|---|---|
| Chat about attachments / OCR before analysis | DONE | `core/ai5/DocIndexer.kt` `attachments`: embedded PDF / txt / md / csv files are indexed with the document. Other attachment types are skipped. |
| Chat about images / charts | DONE + NEEDS-SERVER (vision) | = AI-10 |
| Enhance writing: replace selection | DONE | = B11 |
| Translation: side-by-side; translate images; per-language guidance | DONE (images: + NEEDS-SERVER) | `SideBySideScreen.kt`; `DocTranslator.translate(..., guidance, translateImages)` with vision on pages that have no text layer |
| Agentic Edit PDF mode (highlight / replace) | DONE | = B10 |
| Intelligent Read: Summary Reading | DONE | `ui/ai5/SummaryPanel.kt` read-aloud / stop (`SummaryGen.spokenText`) + audio file of the summary (document or Space) |
| Read Aloud: highlight current text | DONE | = C1 |
| MCP integration / connectors | NOT-FEASIBLE | No stdio MCP on Android. Remote MCP connectors (Jira, Gmail…) need per-service OAuth and server hosting, which this app has no backend for. |
| AI credits / usage display | DONE + NEEDS-SERVER | = AI-18 |
| Web Search in chat | DONE + NEEDS-SERVER | = AI-14 |
| Agentic chat: skills | DONE | = AI-6 |
| Local AI (on-device model) | NOT-FEASIBLE | Needs a new on-device model dependency (AICore / Gemini Nano is limited to a few devices; MediaPipe LLM is a large download). A user-run local endpoint (Ollama / LM Studio) already works through AI settings. |
| AI image generation / editing | PARTIAL + NEEDS-SERVER | = AI-8 (generation done; prompt editing of an existing image not done) |
| Export AI responses to Word | DONE | = AI-15 |
| Chats tab on Start page | DONE | = AI-5 |
| Formula recognition to MathML / LaTeX | DONE + NEEDS-SERVER (vision) | "formula" skill: page or image → LaTeX block + MathML block (`HubSkills.kt`, `ChatRich.kt`) |
| Workspace crafts: slides, infographics, web pages | PARTIAL | Slides are done (B13). Infographics and web pages are not done. |
| AI feedback buttons / adjustable panel width | DONE / PARTIAL | Thumbs up/down are saved with the chat (`CitedChatUi.kt`, `AiStores.kt`). The reader assistant is a bottom sheet; on screens over 700dp a button cycles its max width (480 / 640 / 960dp). There is no drag handle. |
| §41 13.1 support chatbot (in §41, done alongside) | DONE | `ui/ai6/HelpChatScreen.kt` (route `ai_help`, answers from the help articles + FAQ), chip in `ui/view5/HelpCenter5.kt` |

## Tests added (JVM)
`core/ai6/AiMediaTest.kt`, `core/ai6/HubChatTest.kt`, `core/ai6/AiDocToolsTest.kt`, `core/ai6/ReaderAi6LogicTest.kt`,
`core/voice/PodcastScriptTest.kt` (with `VadGateTest`), `core/office5/OfficeFindTest.kt`.

## CI
`.github/workflows/build.yml` now re-emits Kotlin compile errors and failed tests as check-run annotations, because job
logs need admin rights to download and annotations are public. This is a small, branch-local improvement; keep or drop it at merge.
