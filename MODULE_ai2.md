# MODULE ai2 — AI document agent, AI voices, audio summary & podcast

Not compiled this round (Round 3 rule). Every library API was checked against existing code or the PdfBox jar with `javap`.

## Files
| File | What |
|---|---|
| `core/ai/AiClient.kt` (edited) | + `baseUrl`, `modelName`, `viaProxy`, `postJson(path, body)`, `postForBytes(path, body)`, `getJson(path)` (same key / proxy token headers). |
| `core/ai/DocCommands.kt` | `DocCmd` (22 commands + aliases of the reference's names `delete-page`, `go-to`, …), `DocCommand` (forgiving args: Arabic-Indic digits, "٣ إلى ٥", "3-5, 8", "all/الكل", "current/الحالية", "last/الأخيرة"), `DocTools.all` (OpenAI `tools` JSON schema, Arabic descriptions) and `DocTools.fallbackInstructions`. |
| `core/ai/DocActions.kt` | `interface DocActions`, `PdfEdit`, `DocHost` (what the reader passes), `PdfDocActions` (implementation over PdfOps / PdfBox / AnnotationWriter / OCR / DocxWriter), `ActionResult`, `ActionError`. |
| `core/ai/DocAgent.kt` | `DocAgent.turn()` — tool calling (`tools` + `tool_calls`), JSON-in-text parsing for models that write the call as text, and a full fallback for providers that reject tools (```json {"function":…,"args":…}```). Arabic system prompt (page count, current page, selection). `DocCommandRunner` — Arabic confirmation text + dispatch. |
| `core/ai/FileDocHost.kt` | `FileDocHostState` — a `DocHost` over a plain file (used by the full-screen assistant; edits in place with backup/undo). |
| `ui/ai/AgentChatState.kt` | Agent chat state: user/assistant bubbles, confirmation cards (تنفيذ / إلغاء), results with تراجع (undo) / فتح / مشاركة, errors. Executed/cancelled commands are fed back to the model as short assistant notes. |
| `ui/ai/AgentSheet.kt` | `DocAgentSheet(host, onDismiss, initialPrompt?, onPodcast?)`, `DocAgentPanel(host, modifier, …)` (tablet side panel), `rememberAgentChatState(host)`. Suggestion chips. |
| `ui/ai/AiScreen.kt` (edited) | Chips "محادثة" / "أوامر المستند" (agent over the picked file via `FileDocHostState`) + "الملخص الصوتي والبودكاست" → route `podcast`. |
| `core/voice/AiSpeech.kt` | `/audio/speech` client, `VoicePrefs` (SharedPreferences `ai_voice`: readAloudAi, voice, hostVoice, guestVoice, model), `TtsProfile` (model / voices / format / maxChars — from the proxy's `GET /audio/voices`, Groq → `canopylabs/orpheus-arabic-saudi` WAV 190 chars, otherwise OpenAI `gpt-4o-mini-tts` MP3 1500 chars). Cache: `cacheDir/aivoice/<sha1(model,voice,speed,instr,text)>.mp3|wav`. `synthesizeLong()` splits and joins. |
| `core/voice/AudioConcat.kt` | MP3 byte concat (drops ID3v2 of later chunks and ID3v1 trailers); WAV concat (PCM data under one RIFF header); `durationMs()`. |
| `core/voice/AiVoiceEngine.kt` | Optional ReadAloud engine: `shouldUse(ctx)`, `speak(ctx, text, rate, utteranceId, next) { id, ok -> }`, `stop/pause/resume/setRate/release`, prefetch of the next chunk, `disableForSession()` fallback. |
| `core/voice/PodcastGenerator.kt` | Script by chat (summary 150–220 words / podcast 12–18 host↔guest lines as JSON, with "المضيف:/الضيف:" text fallback), per-line synthesis with two voices, joined file in `getExternalFilesDir/Audio/<doc>_summary|_podcast.mp3` (or `.wav`), `.txt` transcript and `.lines.json` timings (reopened automatically). |
| `ui/voice/AudioPlayer.kt` | `AudioPlayerState` (MediaPlayer), `PlayerControls` (play/pause, ±10 s, seek bar, speeds 0.5/0.75/1/1.25/1.5/2), `TranscriptView` (current line highlighted + auto-scroll, tap = seek). |
| `ui/voice/AiVoiceSettings.kt` | `AiVoicePicker`, `ReadAloudVoiceSection` ("صوت مُعدّ مسبقاً" / "صوت بشري (يحتاج إنترنت)" + voice + preview), `ReadAloudVoiceSheet`, `rememberTtsProfile()`. |
| `ui/voice/PodcastScreen.kt` | `PodcastScreen(nav, uri)` — route id **"podcast"**. Mode chips, voices, generate with progress, player, transcript (side by side on tablets), save (SAF) / share. |
| `ai-proxy/worker.js`, `wrangler.toml`, `README.md` | + `POST /audio/speech` (forced `TTS_MODEL`, allowed `TTS_VOICES`, `TTS_FORMAT`, ≤4096 chars, speed 0.25–4, separate daily counter 400/device) and `GET /audio/voices`. Chat body limit raised to 96 KB for tool schemas. |
| `res/values/strings_ai2.xml`, `res/values-en/strings_ai2.xml` | 129 strings, prefix `ai2_`. |

## Commands (tool names)
go_to_page, search_text, delete_pages, rotate_pages(degrees), move_page(from,to), duplicate_page, insert_blank_page(after), extract_pages(range) → new file, share_pages, flatten, redact_text(query), set_properties(title,author,subject,keywords), convert(format: docx/txt/jpeg/png), compress(quality), add_watermark(text), add_page_numbers(arabic_digits), protect(password), ocr(pages, mode searchable/editable), change_page_color (view only), summarize_to_note(page[, summary]), translate_selection(target_language), create_blank_pdf(pages).
No confirmation for go_to_page, search_text, translate_selection; every other command shows "هل تريد …؟" with تنفيذ / إلغاء and, after an in-place edit, a تراجع button.

## Hooks for the lead (I did not edit reader / TTS / navigation files)

### 1. Route
`ui/tools/ToolScreens.kt`: `"podcast" -> com.smartdocs.editor.ui.voice.PodcastScreen(nav, uri)`. Optional Tools-tab card: `ToolDef("podcast", R.string.ai2_podcast_title, Icons.Default.Headphones, 3)`.

### 2. Reader: replace `AiSheet` with the agent (ReaderScreen.kt, line ~279)
```kotlin
val agentHost = remember(state) {
    com.smartdocs.editor.core.ai.DocHost(
        file = { state.file }, password = { state.password }, pageCount = { state.pageCount }, currentPage = { state.currentPage },
        apply = { e -> state.applyInPlace(if (e.changesPassword) e.newPassword else state.password, e.op) },
        restore = { backup, pw -> state.restoreFrom(backup, pw) },
        goToPage = { state.jumpTo(it) },
        pageText = { state.pageText(it) },
        search = { q -> state.searchOpen = true; state.runSearch(q) },
        setPageColor = { state.pageColor = it },
        selectedText = { state.selection?.text },
        openFile = { f -> nav.navigate(Routes.reader(Uri.fromFile(f).toString())) },
        share = { files, mime -> ctx.startActivity(Intent.createChooser(app.documents.shareIntent(files, mime), null)) },
    )
}
if (showAi) DocAgentSheet(agentHost, onDismiss = { showAi = false },
    initialPrompt = aiText?.let { /* optional: e.g. "اشرح النص المحدد" */ null },
    onPodcast = { showAi = false; nav.navigate(Routes.tool("podcast", state.fileUri)) })
```
The brief's `apply: suspend (suspend (File, File)->Unit) -> Unit` became `apply: suspend (PdfEdit) -> File?` because PdfBox needs the current password inside the op (`applyInPlace`'s op already receives `pw`), protect must change the password, and the returned backup enables undo. `state.applyInPlace` saves pending annotations first and returns null if that fails (the agent then reports "لم يُطبَّق التعديل").
On tablets the same host can feed `DocAgentPanel(agentHost, Modifier.width(380.dp).fillMaxHeight())` as a side panel.

### 3. ReadAloud: AI voice engine (core/tts/ReadAloudController.kt, owned by the TTS module)
```kotlin
// start(): AiVoiceEngine.enableAgain()
// speakCurrent(), instead of engine.speak(...):
val ctx = appContext!!
if (AiVoiceEngine.shouldUse(ctx)) {
    AiVoiceEngine.speak(ctx, text, st.rate, id, next = chunks.getOrNull(chunkIdx + 1)) { uid, ok ->
        if (ok) listener.onDone(uid) else { AiVoiceEngine.disableForSession(); if (uid == "rd_$utteranceSeq") speakCurrent() }
    }
} else engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
// pause(): AiVoiceEngine.stop()   (resume re-speaks the chunk; the audio is cached, so it is instant)
// stop() / goToPage(): AiVoiceEngine.stop()     setRate(r): AiVoiceEngine.setRate(r)     release(): AiVoiceEngine.release()
```
`speakCurrent` needs the TTS engine only for the system path, so `ensureEngine` can stay as is. Voice choice UI: put `ReadAloudVoiceSection()` in the read-aloud "more settings" sheet, or open `ReadAloudVoiceSheet(onDismiss)`. The "قراءة الملخص" and "بودكاست ذكي" items of that sheet should navigate to `Routes.tool("podcast", uri)`.

## Limitations / TODO
- `duplicate_page` drops annotations from the copy (an annotation dictionary can belong to one page only); content and resources are shared.
- `convert`: docx (text-only via `DocxWriter`), txt, jpeg/png (max 60 pages, 150 dpi). Excel / PowerPoint / RTF are not supported ("الصيغة غير مدعومة"); "rtf"/"word-2003" map to docx.
- `ocr` uses Arabic + English and downloads missing traineddata silently (no progress shown in the chat card beyond the spinner).
- `redact_text` uses PDFium search rectangles; matches that PDFium cannot find (text drawn as images) are not redacted.
- `FileDocHostState` (full-screen assistant) edits the file in `docsDir` but does not call `documents.writeBack` for content:// originals.
- Podcast/summary audio: byte-concatenated MP3 relies on the provider returning one bitrate (true for OpenAI); seek positions come from per-line durations (MediaMetadataRetriever / WAV header). No background playback service — playback stops when the screen is left.
- Groq Orpheus Arabic voices accept 200 chars per request, so long texts make many requests (proxy daily speech limit: 400).
- Tool-support detection is per endpoint+model per process; a provider that silently ignores `tools` still works through JSON-in-text parsing.
