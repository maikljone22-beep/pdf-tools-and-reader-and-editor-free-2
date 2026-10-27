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
//   IMAGE_URL / IMAGE_MODEL   OpenAI-compatible /images/generations (e.g. https://api.openai.com/v1, gpt-image-1); secret IMAGE_KEY
//   STT_URL / STT_MODEL       OpenAI-compatible /audio/transcriptions (Groq whisper-large-v3-turbo); secret STT_KEY (or PROVIDER_KEY)
//   EMBED_URL / EMBED_MODEL   OpenAI-compatible /embeddings (OpenAI text-embedding-3-small); secret EMBED_KEY
//   SEARCH_KEY (secret)       Brave Search API key for /web/search
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
//   POST /audio/transcriptions  multipart (file, language) -> {text}
//   POST /embeddings         {input:[...]} -> {data:[{index,embedding}]}
//   POST /web/search         {query,count} -> {results:[{title,url,snippet}]}

const DAILY_LIMIT = 60;          // chat requests per device per day
const TTS_DAILY_LIMIT = 400;     // speech requests per device per day (one request = one ~1500-char chunk)
const MAX_BODY = 96 * 1024;      // bytes of JSON the app may send (tool schemas included)
const MAX_TTS_INPUT = 4096;      // characters per speech request (OpenAI's own limit)
const MAX_VISION_BODY = 6 * 1024 * 1024;   // chat requests that carry images (base64)
const MAX_AUDIO = 24 * 1024 * 1024;        // transcription upload (Whisper limit 25 MB)
const IMAGE_DAILY_LIMIT = 20;
const STT_DAILY_LIMIT = 30;
const EMBED_DAILY_LIMIT = 200;
const SEARCH_DAILY_LIMIT = 60;

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
    const isStt = req.method === "POST" && path.endsWith("/audio/transcriptions");
    const isEmbed = req.method === "POST" && path.endsWith("/embeddings");
    const isSearch = req.method === "POST" && path.endsWith("/web/search");
    if (!isChat && !isSpeech && !isVoices && !isCaps && !isUsage && !isImages && !isStt && !isEmbed && !isSearch) return json({ error: "not found" }, 404);
    if (req.headers.get("X-App-Token") !== env.APP_TOKEN) return json({ error: "unauthorized" }, 401);

    if (isCaps) return json(capabilities(env), 200);
    if (isUsage) return usage(req, env);
    if (isStt) return transcribe(req, env);

    if (isVoices) return json({
      voices: voices(env), model: env.TTS_MODEL || "gpt-4o-mini-tts",
      format: env.TTS_FORMAT || "mp3", max_chars: Math.min(parseInt(env.TTS_MAX_CHARS || "1500", 10) || 1500, MAX_TTS_INPUT),
    }, 200);

    const raw = await req.text();
    const hasImages = isChat && raw.includes('"image_url"');
    if (raw.length > (hasImages ? MAX_VISION_BODY : MAX_BODY)) return json({ error: "request too large" }, 413);
    let body;
    try { body = JSON.parse(raw); } catch { return json({ error: "bad json" }, 400); }
    if (hasImages && !env.VISION_MODEL) return json({ error: "not configured" }, 501);
    if (isImages && !(env.IMAGE_KEY && env.IMAGE_URL)) return json({ error: "not configured" }, 501);
    if (isEmbed && !(env.EMBED_KEY && env.EMBED_URL)) return json({ error: "not configured" }, 501);
    if (isSearch && !env.SEARCH_KEY) return json({ error: "not configured" }, 501);

    // rate limit per client IP (KV optional); every route kind has its own counter
    const kind = isSpeech ? "tts" : isImages ? "img" : isEmbed ? "emb" : isSearch ? "web" : "chat";
    const limit = { tts: TTS_DAILY_LIMIT, img: IMAGE_DAILY_LIMIT, emb: EMBED_DAILY_LIMIT, web: SEARCH_DAILY_LIMIT, chat: DAILY_LIMIT }[kind];
    const limited = await rateLimit(req, env, kind, limit);
    if (limited) return limited;

    if (isSpeech) return speech(body, env);
    if (isImages) return images(body, env);
    if (isEmbed) return embeddings(body, env);
    if (isSearch) return webSearch(body, env);

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
