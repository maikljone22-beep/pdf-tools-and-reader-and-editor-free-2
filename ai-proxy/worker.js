// SmartDocs AI proxy — Cloudflare Worker.
// Keeps the provider API key on the server; the app sends only a shared app token.
// Secrets (set with `wrangler secret put`): PROVIDER_KEY, APP_TOKEN, optional TTS_KEY
// Vars (wrangler.toml):
//   PROVIDER_URL   OpenAI-compatible base for chat, e.g. https://api.groq.com/openai/v1
//   DEFAULT_MODEL  chat model forced by the server
//   TTS_URL        OpenAI-compatible base for /audio/speech (defaults to PROVIDER_URL)
//   TTS_MODEL      speech model forced by the server (e.g. gpt-4o-mini-tts)
//   TTS_VOICES     comma separated list of allowed voices; the first one is the default
//   TTS_FORMAT     force an audio format (e.g. "wav" for Groq Orpheus); default: what the app asks (mp3)
//   TTS_MAX_CHARS  characters per request the app should send (Groq Orpheus: 200; OpenAI: up to 4096)
//   VISION_MODEL   model used when a chat request carries images (e.g. meta-llama/llama-4-scout-17b-16e-instruct on Groq)
//   IMAGE_URL / IMAGE_MODEL   OpenAI-compatible /images/generations and /images/edits (e.g. https://api.openai.com/v1, gpt-image-1); secret IMAGE_KEY
//   STT_URL / STT_MODEL       OpenAI-compatible /audio/transcriptions (Groq whisper-large-v3-turbo); secret STT_KEY (or PROVIDER_KEY)
//   EMBED_URL / EMBED_MODEL   OpenAI-compatible /embeddings (OpenAI text-embedding-3-small); secret EMBED_KEY
//   SEARCH_KEY (secret)       Brave Search API key for /web/search
//   OCR_PROVIDER              "google" (Cloud Vision DOCUMENT_TEXT_DETECTION, secret OCR_KEY = API key),
//                             "azure" (Document Intelligence prebuilt-read, OCR_URL = https://<res>.cognitiveservices.azure.com,
//                             secret OCR_KEY) or "vision" (VISION_MODEL through PROVIDER_URL: text only, no word boxes)
// A route whose key is missing answers 501 {"error":"not configured"}; the app shows a clear message.
// Optional KV binding RATE for per-device daily limits (also read back by GET /usage).
//
// Routes (all need the X-App-Token header):
//   POST /chat/completions   chat (SSE or JSON), tools / tool_calls pass through unchanged
//   POST /audio/speech       text → audio (mp3 by default), streamed back unchanged
//   GET  /audio/voices       {"voices": [...], "model": "...", "format": "mp3", "max_chars": 1500} for the app
//   GET  /capabilities       {"vision","images","transcription","embeddings","web_search","usage"} booleans
//   GET  /usage              {"chat_used","chat_limit","tts_used","tts_limit","images_used","images_limit"} for today
//   POST /images/generations {prompt,size} -> {data:[{b64_json}]}
//   POST /images/edits       multipart (image PNG, prompt, size) -> {data:[{b64_json}]}   (prompt-based image editing)
//   POST /audio/transcriptions  multipart (file, language) -> {text}
//   POST /embeddings         {input:[...]} -> {data:[{index,embedding}]}
//   POST /web/search         {query,count} -> {results:[{title,url,snippet}]}
//   POST /ocr                {image: base64 JPEG/PNG, languages:["ar","en"]} -> {provider,width,height,
//                            words:[{text,x0,y0,x1,y1,conf,line}] (pixels of the sent image), text}   ("Pro OCR")

const DAILY_LIMIT = 60;          // chat requests per device per day
const TTS_DAILY_LIMIT = 400;     // speech requests per device per day (one request = one ~1500-char chunk)
const MAX_BODY = 96 * 1024;      // bytes of JSON the app may send (tool schemas included)
const MAX_TTS_INPUT = 4096;      // characters per speech request (OpenAI's own limit)
const MAX_VISION_BODY = 6 * 1024 * 1024;   // chat requests that carry images (base64)
const MAX_AUDIO = 24 * 1024 * 1024;        // transcription upload (Whisper limit 25 MB)
const MAX_IMAGE_EDIT = 12 * 1024 * 1024;   // image edit upload (one PNG)
const IMAGE_DAILY_LIMIT = 20;
const STT_DAILY_LIMIT = 30;
const EMBED_DAILY_LIMIT = 200;
const SEARCH_DAILY_LIMIT = 60;
const OCR_DAILY_LIMIT = 100;     // OCR pages per device per day
const MAX_OCR_BODY = 8 * 1024 * 1024;      // one page image (base64)

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return new Response(null, { status: 204 });
    const path = url.pathname.replace(/\/+$/, "");
    const isChat = req.method === "POST" && path.endsWith("/chat/completions");
    const isSpeech = req.method === "POST" && path.endsWith("/audio/speech");
    const isVoices = req.method === "GET" && path.endsWith("/audio/voices");
    const isCaps = req.method === "GET" && path.endsWith("/capabilities");
    const isUsage = req.method === "GET" && path.endsWith("/usage");
    const isImages = req.method === "POST" && path.endsWith("/images/generations");
    const isImageEdit = req.method === "POST" && path.endsWith("/images/edits");
    const isStt = req.method === "POST" && path.endsWith("/audio/transcriptions");
    const isEmbed = req.method === "POST" && path.endsWith("/embeddings");
    const isSearch = req.method === "POST" && path.endsWith("/web/search");
    const isOcr = req.method === "POST" && path.endsWith("/ocr");
    if (!isChat && !isSpeech && !isVoices && !isCaps && !isUsage && !isImages && !isImageEdit && !isStt && !isEmbed && !isSearch && !isOcr) return json({ error: "not found" }, 404);
    if (req.headers.get("X-App-Token") !== env.APP_TOKEN) return json({ error: "unauthorized" }, 401);

    if (isCaps) return json(capabilities(env), 200);
    if (isUsage) return usage(req, env);
    if (isStt) return transcribe(req, env);
    if (isImageEdit) return imageEdit(req, env);

    if (isVoices) return json({
      voices: voices(env), model: env.TTS_MODEL || "gpt-4o-mini-tts",
      format: env.TTS_FORMAT || "mp3", max_chars: Math.min(parseInt(env.TTS_MAX_CHARS || "1500", 10) || 1500, MAX_TTS_INPUT),
    }, 200);

    const raw = await req.text();
    const hasImages = isChat && raw.includes('"image_url"');
    if (raw.length > (isOcr ? MAX_OCR_BODY : hasImages ? MAX_VISION_BODY : MAX_BODY)) return json({ error: "request too large" }, 413);
    let body;
    try { body = JSON.parse(raw); } catch { return json({ error: "bad json" }, 400); }
    if (hasImages && !env.VISION_MODEL) return json({ error: "not configured" }, 501);
    if (isImages && !(env.IMAGE_KEY && env.IMAGE_URL)) return json({ error: "not configured" }, 501);
    if (isEmbed && !(env.EMBED_KEY && env.EMBED_URL)) return json({ error: "not configured" }, 501);
    if (isSearch && !env.SEARCH_KEY) return json({ error: "not configured" }, 501);
    if (isOcr && !ocrConfigured(env)) return json({ error: "not configured" }, 501);

    // rate limit per client IP (KV optional); every route kind has its own counter
    const kind = isSpeech ? "tts" : isImages ? "img" : isEmbed ? "emb" : isSearch ? "web" : isOcr ? "ocr" : "chat";
    const limit = { tts: TTS_DAILY_LIMIT, img: IMAGE_DAILY_LIMIT, emb: EMBED_DAILY_LIMIT, web: SEARCH_DAILY_LIMIT, ocr: OCR_DAILY_LIMIT, chat: DAILY_LIMIT }[kind];
    const limited = await rateLimit(req, env, kind, limit);
    if (limited) return limited;

    if (isSpeech) return speech(body, env);
    if (isImages) return images(body, env);
    if (isEmbed) return embeddings(body, env);
    if (isSearch) return webSearch(body, env);
    if (isOcr) return ocr(body, env);

    // the server decides the model and caps the answer length
    body.model = (hasImages ? env.VISION_MODEL : env.DEFAULT_MODEL) || body.model;
    body.max_tokens = Math.min(body.max_tokens || 1500, 2000);

    const upstream = await fetch(`${env.PROVIDER_URL.replace(/\/$/, "")}/chat/completions`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${env.PROVIDER_KEY}` },
      body: JSON.stringify(body),
    });
    // stream through unchanged (SSE or JSON)
    return new Response(upstream.body, { status: upstream.status, headers: { "Content-Type": upstream.headers.get("Content-Type") || "application/json" } });
  },
};

async function speech(body, env) {
  const input = typeof body.input === "string" ? body.input : "";
  if (!input.trim()) return json({ error: "empty input" }, 400);
  if (input.length > MAX_TTS_INPUT) return json({ error: "input too long" }, 413);
  const allowed = voices(env);
  const voice = allowed.includes(body.voice) ? body.voice : allowed[0];
  const fmt = env.TTS_FORMAT || (["mp3", "wav", "opus", "aac", "flac"].includes(body.response_format) ? body.response_format : "mp3");
  const speed = Math.min(Math.max(Number(body.speed) || 1, 0.25), 4);
  const payload = { model: env.TTS_MODEL || "gpt-4o-mini-tts", input, voice, response_format: fmt, speed };
  if (typeof body.instructions === "string") payload.instructions = body.instructions.slice(0, 500);
  const base = (env.TTS_URL || env.PROVIDER_URL).replace(/\/$/, "");
  const upstream = await fetch(`${base}/audio/speech`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${env.TTS_KEY || env.PROVIDER_KEY}` },
    body: JSON.stringify(payload),
  });
  return new Response(upstream.body, { status: upstream.status, headers: { "Content-Type": upstream.headers.get("Content-Type") || "audio/mpeg" } });
}

function capabilities(env) {
  return {
    vision: !!env.VISION_MODEL,
    images: !!(env.IMAGE_KEY && env.IMAGE_URL),
    transcription: !!(env.STT_URL || env.PROVIDER_URL),
    embeddings: !!(env.EMBED_KEY && env.EMBED_URL),
    web_search: !!env.SEARCH_KEY,
    ocr: ocrConfigured(env),
    usage: !!env.RATE,
  };
}

async function usage(req, env) {
  if (!env.RATE) return json({ error: "not configured" }, 501);
  const ip = req.headers.get("CF-Connecting-IP") || "unknown";
  const day = new Date().toISOString().slice(0, 10);
  const get = async (k) => parseInt((await env.RATE.get(`${k}:${ip}:${day}`)) || "0", 10);
  return json({
    chat_used: await get("chat"), chat_limit: DAILY_LIMIT,
    tts_used: await get("tts"), tts_limit: TTS_DAILY_LIMIT,
    images_used: await get("img"), images_limit: IMAGE_DAILY_LIMIT,
    stt_used: await get("stt"), stt_limit: STT_DAILY_LIMIT,
  }, 200);
}

async function images(body, env) {
  const prompt = typeof body.prompt === "string" ? body.prompt.slice(0, 4000) : "";
  if (!prompt.trim()) return json({ error: "empty prompt" }, 400);
  const size = ["1024x1024", "1024x1536", "1536x1024", "512x512", "256x256"].includes(body.size) ? body.size : "1024x1024";
  const model = env.IMAGE_MODEL || "gpt-image-1";
  const payload = { model, prompt, size, n: 1 };
  if (model.startsWith("dall-e")) payload.response_format = "b64_json";
  const upstream = await fetch(`${env.IMAGE_URL.replace(/\/$/, "")}/images/generations`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${env.IMAGE_KEY}` },
    body: JSON.stringify(payload),
  });
  return new Response(upstream.body, { status: upstream.status, headers: { "Content-Type": "application/json" } });
}

async function imageEdit(req, env) {
  if (!(env.IMAGE_KEY && env.IMAGE_URL)) return json({ error: "not configured" }, 501);
  const len = parseInt(req.headers.get("Content-Length") || "0", 10);
  if (len > MAX_IMAGE_EDIT) return json({ error: "request too large" }, 413);
  const limited = await rateLimit(req, env, "img", IMAGE_DAILY_LIMIT);
  if (limited) return limited;
  let form;
  try { form = await req.formData(); } catch { return json({ error: "bad form" }, 400); }
  const image = form.get("image");
  const prompt = form.get("prompt");
  if (!image || typeof image === "string") return json({ error: "no image" }, 400);
  if (typeof prompt !== "string" || !prompt.trim()) return json({ error: "empty prompt" }, 400);
  const sizeIn = form.get("size");
  const size = ["1024x1024", "1024x1536", "1536x1024", "512x512", "256x256"].includes(sizeIn) ? sizeIn : "1024x1024";
  const model = env.IMAGE_MODEL || "gpt-image-1";
  const out = new FormData();
  out.append("image", image, image.name || "image.png");
  out.append("prompt", prompt.slice(0, 4000));
  out.append("model", model);
  out.append("size", size);
  out.append("n", "1");
  if (model.startsWith("dall-e")) out.append("response_format", "b64_json");
  const upstream = await fetch(`${env.IMAGE_URL.replace(/\/$/, "")}/images/edits`, {
    method: "POST", headers: { Authorization: `Bearer ${env.IMAGE_KEY}` }, body: out,
  });
  return new Response(upstream.body, { status: upstream.status, headers: { "Content-Type": "application/json" } });
}

async function transcribe(req, env) {
  const len = parseInt(req.headers.get("Content-Length") || "0", 10);
  if (len > MAX_AUDIO) return json({ error: "request too large" }, 413);
  const limited = await rateLimit(req, env, "stt", STT_DAILY_LIMIT);
  if (limited) return limited;
  let form;
  try { form = await req.formData(); } catch { return json({ error: "bad form" }, 400); }
  const file = form.get("file");
  if (!file || typeof file === "string") return json({ error: "no file" }, 400);
  const out = new FormData();
  out.append("file", file, file.name || "audio.m4a");
  out.append("model", env.STT_MODEL || "whisper-large-v3-turbo");
  out.append("response_format", "json");
  const lang = form.get("language");
  if (typeof lang === "string" && /^[a-z]{2}$/.test(lang)) out.append("language", lang);
  const base = (env.STT_URL || env.PROVIDER_URL).replace(/\/$/, "");
  const upstream = await fetch(`${base}/audio/transcriptions`, {
    method: "POST", headers: { Authorization: `Bearer ${env.STT_KEY || env.PROVIDER_KEY}` }, body: out,
  });
  return new Response(upstream.body, { status: upstream.status, headers: { "Content-Type": upstream.headers.get("Content-Type") || "application/json" } });
}

async function embeddings(body, env) {
  const input = Array.isArray(body.input) ? body.input.filter((x) => typeof x === "string").slice(0, 64).map((x) => x.slice(0, 8000)) : [];
  if (!input.length) return json({ error: "empty input" }, 400);
  const upstream = await fetch(`${env.EMBED_URL.replace(/\/$/, "")}/embeddings`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${env.EMBED_KEY}` },
    body: JSON.stringify({ model: env.EMBED_MODEL || "text-embedding-3-small", input }),
  });
  return new Response(upstream.body, { status: upstream.status, headers: { "Content-Type": "application/json" } });
}

async function webSearch(body, env) {
  const q = typeof body.query === "string" ? body.query.slice(0, 400) : "";
  if (!q.trim()) return json({ error: "empty query" }, 400);
  const count = Math.min(Math.max(parseInt(body.count || "5", 10) || 5, 1), 10);
  const r = await fetch(`https://api.search.brave.com/res/v1/web/search?count=${count}&q=${encodeURIComponent(q)}`, {
    headers: { Accept: "application/json", "X-Subscription-Token": env.SEARCH_KEY },
  });
  if (!r.ok) return json({ error: `search failed ${r.status}` }, 502);
  const data = await r.json();
  const results = ((data.web && data.web.results) || []).slice(0, count).map((x) => ({ title: x.title || "", url: x.url || "", snippet: (x.description || "").replace(/<[^>]+>/g, "") }));
  return json({ results }, 200);
}

function ocrConfigured(env) {
  const p = env.OCR_PROVIDER;
  if (p === "google") return !!env.OCR_KEY;
  if (p === "azure") return !!(env.OCR_KEY && env.OCR_URL);
  if (p === "vision") return !!(env.VISION_MODEL && env.PROVIDER_KEY);
  return false;
}

// Page OCR on the server ("Pro OCR", user opt-in). The key never leaves the worker; the answer is normalised to word
// boxes in the pixels of the image the app sent, so the app writes the same invisible text layer as offline.
async function ocr(body, env) {
  const image = typeof body.image === "string" ? body.image.replace(/^data:[^,]*,/, "") : "";
  if (!image) return json({ error: "image required" }, 400);
  const langs = Array.isArray(body.languages) ? body.languages.filter((l) => typeof l === "string" && /^[a-z-]{2,8}$/i.test(l)).slice(0, 4) : ["ar", "en"];
  const p = env.OCR_PROVIDER;
  if (p === "google") {
    const r = await fetch(`https://vision.googleapis.com/v1/images:annotate?key=${encodeURIComponent(env.OCR_KEY)}`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ requests: [{ image: { content: image }, features: [{ type: "DOCUMENT_TEXT_DETECTION" }], imageContext: { languageHints: langs } }] }),
    });
    if (!r.ok) return json({ error: `provider ${r.status}` }, 502);
    const res = ((await r.json()).responses || [])[0] || {};
    const fta = res.fullTextAnnotation || {};
    const page = (fta.pages || [])[0] || {};
    const words = [];
    let line = 0;
    for (const b of page.blocks || []) for (const par of b.paragraphs || []) {
      for (const w of par.words || []) {
        const text = (w.symbols || []).map((s) => s.text).join("");
        const v = (w.boundingBox && w.boundingBox.vertices) || [];
        const xs = v.map((q) => q.x || 0), ys = v.map((q) => q.y || 0);
        if (!text || !xs.length) continue;
        words.push({ text, x0: Math.min(...xs), y0: Math.min(...ys), x1: Math.max(...xs), y1: Math.max(...ys), conf: Math.round((w.confidence || 0) * 100), line });
        const last = (w.symbols || []).slice(-1)[0] || {};
        const t = last.property && last.property.detectedBreak && last.property.detectedBreak.type;
        if (t === "EOL_SURE_SPACE" || t === "LINE_BREAK") line++;
      }
      line++;
    }
    return json({ provider: "google", width: page.width || 0, height: page.height || 0, words, text: fta.text || "" }, 200);
  }
  if (p === "azure") {
    const base = env.OCR_URL.replace(/\/$/, "");
    const start = await fetch(`${base}/documentintelligence/documentModels/prebuilt-read:analyze?api-version=2024-11-30`, {
      method: "POST", headers: { "Content-Type": "application/json", "Ocp-Apim-Subscription-Key": env.OCR_KEY },
      body: JSON.stringify({ base64Source: image }),
    });
    if (start.status !== 202) return json({ error: `provider ${start.status}` }, 502);
    const op = start.headers.get("Operation-Location");
    for (let i = 0; i < 30; i++) {
      await new Promise((ok) => setTimeout(ok, 1000));
      const r = await fetch(op, { headers: { "Ocp-Apim-Subscription-Key": env.OCR_KEY } });
      const j = await r.json();
      if (j.status === "failed") return json({ error: "provider failed" }, 502);
      if (j.status !== "succeeded") continue;
      const page = ((j.analyzeResult || {}).pages || [])[0] || {};
      const lines = page.lines || [];
      const words = [];
      for (const w of page.words || []) {
        const poly = w.polygon || [];
        const xs = poly.filter((_, k) => k % 2 === 0), ys = poly.filter((_, k) => k % 2 === 1);
        if (!xs.length) continue;
        const off = (w.span || {}).offset || 0;
        const li = lines.findIndex((l) => (l.spans || []).some((s) => off >= s.offset && off < s.offset + s.length));
        words.push({ text: w.content, x0: Math.min(...xs), y0: Math.min(...ys), x1: Math.max(...xs), y1: Math.max(...ys), conf: Math.round((w.confidence || 0) * 100), line: li });
      }
      return json({ provider: "azure", width: page.width || 0, height: page.height || 0, words, text: (j.analyzeResult || {}).content || "" }, 200);
    }
    return json({ error: "provider timeout" }, 504);
  }
  // a vision chat model: plain text in reading order, no word boxes (the app keeps its own boxes)
  const r = await fetch(`${env.PROVIDER_URL.replace(/\/$/, "")}/chat/completions`, {
    method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${env.PROVIDER_KEY}` },
    body: JSON.stringify({
      model: env.VISION_MODEL, max_tokens: 4000, temperature: 0,
      messages: [{ role: "user", content: [
        { type: "text", text: "Transcribe ALL text on this page exactly, line by line, in reading order (Arabic right-to-left, columns right to left). Keep Arabic-Indic digits (٠١٢٣٤٥٦٧٨٩) as printed and keep chemical formulas and Latin text exactly. Output only the text." },
        { type: "image_url", image_url: { url: `data:image/jpeg;base64,${image}` } },
      ] }],
    }),
  });
  if (!r.ok) return json({ error: `provider ${r.status}` }, 502);
  const j = await r.json();
  const text = (((j.choices || [])[0] || {}).message || {}).content || "";
  return json({ provider: "vision", width: 0, height: 0, words: [], text }, 200);
}

function voices(env) {
  const list = (env.TTS_VOICES || "alloy,echo,fable,onyx,nova,shimmer").split(",").map((s) => s.trim()).filter(Boolean);
  return list.length ? list : ["alloy"];
}

async function rateLimit(req, env, kind, limit) {
  if (!env.RATE) return null;
  const ip = req.headers.get("CF-Connecting-IP") || "unknown";
  const key = `${kind}:${ip}:${new Date().toISOString().slice(0, 10)}`;
  const n = parseInt((await env.RATE.get(key)) || "0", 10);
  if (n >= limit) return json({ error: "daily limit reached" }, 429);
  await env.RATE.put(key, String(n + 1), { expirationTtl: 60 * 60 * 26 });
  return null;
}

function json(obj, status) { return new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json" } }); }
