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
// Optional KV binding RATE for per-device daily limits.
//
// Routes (all need the X-App-Token header):
//   POST /chat/completions   chat (SSE or JSON), tools / tool_calls pass through unchanged
//   POST /audio/speech       text → audio (mp3 by default), streamed back unchanged
//   GET  /audio/voices       {"voices": [...], "model": "...", "format": "mp3", "max_chars": 1500} for the app

const DAILY_LIMIT = 60;          // chat requests per device per day
const TTS_DAILY_LIMIT = 400;     // speech requests per device per day (one request = one ~1500-char chunk)
const MAX_BODY = 96 * 1024;      // bytes of JSON the app may send (tool schemas included)
const MAX_TTS_INPUT = 4096;      // characters per speech request (OpenAI's own limit)

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return new Response(null, { status: 204 });
    const path = url.pathname.replace(/\/+$/, "");
    const isChat = req.method === "POST" && path.endsWith("/chat/completions");
    const isSpeech = req.method === "POST" && path.endsWith("/audio/speech");
    const isVoices = req.method === "GET" && path.endsWith("/audio/voices");
    if (!isChat && !isSpeech && !isVoices) return json({ error: "not found" }, 404);
    if (req.headers.get("X-App-Token") !== env.APP_TOKEN) return json({ error: "unauthorized" }, 401);

    if (isVoices) return json({
      voices: voices(env), model: env.TTS_MODEL || "gpt-4o-mini-tts",
      format: env.TTS_FORMAT || "mp3", max_chars: Math.min(parseInt(env.TTS_MAX_CHARS || "1500", 10) || 1500, MAX_TTS_INPUT),
    }, 200);

    const raw = await req.text();
    if (raw.length > MAX_BODY) return json({ error: "request too large" }, 413);
    let body;
    try { body = JSON.parse(raw); } catch { return json({ error: "bad json" }, 400); }

    // rate limit per client IP (KV optional); chat and speech have separate counters
    const limited = await rateLimit(req, env, isSpeech ? "tts" : "chat", isSpeech ? TTS_DAILY_LIMIT : DAILY_LIMIT);
    if (limited) return limited;

    if (isSpeech) return speech(body, env);

    // the server decides the model and caps the answer length
    body.model = env.DEFAULT_MODEL || body.model;
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
