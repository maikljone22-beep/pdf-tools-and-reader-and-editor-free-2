// SmartDocs shared review ("comment together") — Cloudflare Worker.
//
// Storage: KV namespace bound as COLLAB (metadata + comments). PDFs go to an R2 bucket bound as FILES when present,
// otherwise into KV (≤ 20 MB). Optional secret APP_TOKEN: when set, every request must carry X-App-Token.
//
// Routes
//   POST   /reviews                     body = PDF; headers X-Review-Title, X-Review-Author, X-File-Name (URL-encoded),
//                                        X-Review-Deadline (epoch ms, 0 = none)  → {id, code, ownerKey, shareUrl}
//   GET    /reviews/:id                 X-Review-Code → meta + reviewers
//   GET    /reviews/:id/file            X-Review-Code → the PDF
//   GET    /reviews/:id/comments        X-Review-Code → {now, comments:[{nm, participant, author, modified, deleted, page, data}]}
//   POST   /reviews/:id/comments        X-Review-Code, JSON {participant, author, comments:[{nm, page, modified, data} | {nm, deleted:true}]}
//                                        → 410 after the deadline or when closed
//   POST   /reviews/:id/close           X-Owner-Key
//   DELETE /reviews/:id                 X-Owner-Key
//   GET    /r/:id?c=CODE                share page with an "open in the app" button (smartdocs://review/:id?c=CODE)
//   GET    /oauth/:provider?…           bounce for OAuth providers that refuse custom schemes → smartdocs://oauth/:provider?…
//   POST   /oauth-token/box             Box token exchange/refresh: adds the client secret kept here (secrets BOX_CLIENT_ID,
//                                        BOX_CLIENT_SECRET), so the app ships no secret (share6)
//
// Each participant writes only its own KV key (comments:<id>:<participant>), so concurrent reviewers never overwrite
// each other. KV is eventually consistent (a new comment can take up to ~60 s to appear in other regions).

const MAX_PDF = 20 * 1024 * 1024;
const MAX_COMMENTS_BODY = 4 * 1024 * 1024;
const DEFAULT_TTL = 90 * 24 * 3600;           // seconds a review lives without a deadline
const AFTER_DEADLINE_TTL = 30 * 24 * 3600;    // kept this long after the deadline (read-only)

export default {
  async fetch(req, env) {
    try {
      return await route(req, env);
    } catch (e) {
      return json({ error: String(e && e.message || e) }, 500);
    }
  },
};

async function route(req, env) {
  const url = new URL(req.url);
  const parts = url.pathname.split("/").filter(Boolean);
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors() });

  // public pages (no app token: opened from messengers / browsers)
  if (req.method === "GET" && parts[0] === "r" && parts[1]) return sharePage(parts[1], url.searchParams.get("c") || "");
  if (req.method === "GET" && parts[0] === "oauth" && parts[1]) {
    const target = `smartdocs://oauth/${encodeURIComponent(parts[1])}${url.search}`;
    return new Response(null, { status: 302, headers: { Location: target } });
  }

  if (req.method === "POST" && parts[0] === "oauth-token" && parts[1] === "box") return boxToken(req, env);

  if (env.APP_TOKEN && req.headers.get("X-App-Token") !== env.APP_TOKEN) return json({ error: "unauthorized" }, 401);
  if (parts[0] !== "reviews") return json({ error: "not found" }, 404);

  if (parts.length === 1 && req.method === "POST") return createReview(req, env, url);
  const id = parts[1];
  if (!/^[a-z0-9]{10}$/.test(id)) return json({ error: "not found" }, 404);
  const meta = await env.COLLAB.get(`meta:${id}`, "json");
  if (!meta) return json({ error: "not found" }, 404);

  const sub = parts[2] || "";
  if (req.method === "POST" && sub === "close") {
    if (req.headers.get("X-Owner-Key") !== meta.ownerKey) return json({ error: "forbidden" }, 403);
    meta.closed = true;
    await env.COLLAB.put(`meta:${id}`, JSON.stringify(meta), { expirationTtl: AFTER_DEADLINE_TTL });
    return json({ ok: true });
  }
  if (req.method === "DELETE" && sub === "") {
    if (req.headers.get("X-Owner-Key") !== meta.ownerKey) return json({ error: "forbidden" }, 403);
    await deleteReview(env, id);
    return json({ ok: true });
  }

  // everything else needs the access code
  if (req.headers.get("X-Review-Code") !== meta.code) return json({ error: "forbidden" }, 403);

  if (req.method === "GET" && sub === "") return json(await publicMeta(env, meta));
  if (req.method === "GET" && sub === "file") {
    const body = env.FILES ? (await env.FILES.get(`pdf/${id}`))?.body : await env.COLLAB.get(`pdf:${id}`, "arrayBuffer");
    if (!body) return json({ error: "file missing" }, 404);
    return new Response(body, { headers: { "Content-Type": "application/pdf" } });
  }
  if (req.method === "GET" && sub === "comments") return json({ now: Date.now(), comments: await allComments(env, id) });
  if (req.method === "POST" && sub === "comments") return postComments(req, env, id, meta);
  return json({ error: "not found" }, 404);
}

async function createReview(req, env, url) {
  const len = parseInt(req.headers.get("Content-Length") || "0", 10);
  if (len > MAX_PDF) return json({ error: "file too large" }, 413);
  const pdf = await req.arrayBuffer();
  if (pdf.byteLength === 0 || pdf.byteLength > MAX_PDF) return json({ error: "bad file size" }, 413);
  const head = new Uint8Array(pdf, 0, Math.min(5, pdf.byteLength));
  if (String.fromCharCode(...head) !== "%PDF-") return json({ error: "not a pdf" }, 400);

  const id = randomId(10);
  const code = randomCode(6);
  const ownerKey = randomId(32);
  const deadline = Math.max(0, parseInt(req.headers.get("X-Review-Deadline") || "0", 10) || 0);
  const meta = {
    id, code, ownerKey, deadline, closed: false, created: Date.now(), size: pdf.byteLength,
    title: dec(req.headers.get("X-Review-Title")) || "مراجعة",
    author: dec(req.headers.get("X-Review-Author")),
    fileName: dec(req.headers.get("X-File-Name")) || "review.pdf",
  };
  const ttl = ttlFor(meta);
  if (env.FILES) await env.FILES.put(`pdf/${id}`, pdf, { httpMetadata: { contentType: "application/pdf" } });
  else await env.COLLAB.put(`pdf:${id}`, pdf, { expirationTtl: ttl });
  await env.COLLAB.put(`meta:${id}`, JSON.stringify(meta), { expirationTtl: ttl });
  const shareUrl = `${url.origin}/r/${id}?c=${code}`;
  return json({ id, code, ownerKey, shareUrl });
}

async function postComments(req, env, id, meta) {
  if (meta.closed || (meta.deadline > 0 && Date.now() > meta.deadline)) return json({ error: "review ended" }, 410);
  const raw = await req.text();
  if (raw.length > MAX_COMMENTS_BODY) return json({ error: "too large" }, 413);
  let body;
  try { body = JSON.parse(raw); } catch { return json({ error: "bad json" }, 400); }
  const participant = String(body.participant || "").slice(0, 64);
  if (!/^[A-Za-z0-9-]{8,64}$/.test(participant)) return json({ error: "bad participant" }, 400);
  const author = String(body.author || "").slice(0, 80);
  const key = `comments:${id}:${participant}`;
  const current = (await env.COLLAB.get(key, "json")) || { author, items: {} };
  current.author = author || current.author;
  let accepted = 0;
  for (const c of Array.isArray(body.comments) ? body.comments : []) {
    if (!c || typeof c.nm !== "string" || c.nm.length > 128) continue;
    const prev = current.items[c.nm];
    const modified = Number(c.modified) || Date.now();
    if (prev && prev.modified > modified) continue;
    current.items[c.nm] = c.deleted ? { deleted: true, modified } : { page: c.page | 0, modified, data: c.data };
    accepted++;
  }
  const count = Object.values(current.items).filter((x) => !x.deleted).length;
  await env.COLLAB.put(key, JSON.stringify(current), {
    expirationTtl: ttlFor(meta),
    metadata: { name: current.author, lastSeen: Date.now(), count },
  });
  return json({ now: Date.now(), accepted });
}

async function allComments(env, id) {
  const out = [];
  for (const k of await listKeys(env, `comments:${id}:`)) {
    const participant = k.name.split(":")[2];
    const v = await env.COLLAB.get(k.name, "json");
    if (!v) continue;
    for (const [nm, c] of Object.entries(v.items || {})) {
      out.push({ nm, participant, author: v.author || "", modified: c.modified, deleted: !!c.deleted, page: c.page || 0, data: c.deleted ? null : c.data });
    }
  }
  return out;
}

async function publicMeta(env, meta) {
  const reviewers = (await listKeys(env, `comments:${meta.id}:`)).map((k) => ({
    name: (k.metadata && k.metadata.name) || "", lastSeen: (k.metadata && k.metadata.lastSeen) || 0, count: (k.metadata && k.metadata.count) || 0,
  }));
  const { ownerKey, code, ...pub } = meta;
  return { ...pub, reviewers };
}

async function listKeys(env, prefix) {
  const keys = [];
  let cursor;
  do {
    const page = await env.COLLAB.list({ prefix, cursor });
    keys.push(...page.keys);
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  return keys;
}

async function deleteReview(env, id) {
  for (const k of await listKeys(env, `comments:${id}:`)) await env.COLLAB.delete(k.name);
  if (env.FILES) await env.FILES.delete(`pdf/${id}`); else await env.COLLAB.delete(`pdf:${id}`);
  await env.COLLAB.delete(`meta:${id}`);
}

function ttlFor(meta) {
  if (meta.deadline > 0) {
    const secs = Math.ceil((meta.deadline - Date.now()) / 1000) + AFTER_DEADLINE_TTL;
    return Math.max(secs, 3600);
  }
  return DEFAULT_TTL;
}

function sharePage(id, code) {
  const safeId = /^[a-z0-9]{10}$/.test(id) ? id : "";
  const safeCode = /^[A-Z0-9]{6}$/.test(code) ? code : "";
  const deep = `smartdocs://review/${safeId}?c=${safeCode}`;
  const intent = `intent://review/${safeId}?c=${safeCode}#Intent;scheme=smartdocs;package=com.smartdocs.editor;end`;
  const html = `<!DOCTYPE html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>مراجعة مشتركة</title><style>body{font-family:Tahoma,Arial,sans-serif;background:#f4f6f8;margin:0;padding:24px;color:#1f2937}
.card{background:#fff;border-radius:16px;max-width:520px;margin:40px auto;padding:28px;text-align:center;box-shadow:0 2px 12px rgba(0,0,0,.07)}
a.btn{display:inline-block;background:#2563eb;color:#fff;text-decoration:none;padding:12px 26px;border-radius:12px;font-size:17px;margin:12px 0}
code{font-size:22px;letter-spacing:4px;direction:ltr;display:inline-block}</style></head><body><div class="card">
<h2>دُعيت إلى مراجعة مستند</h2><p>افتح الرابط على هاتف أندرويد مثبَّت عليه تطبيق "محرر الملفات الذكي" لإضافة تعليقاتك.</p>
<a class="btn" href="${intent}">فتح في التطبيق</a><p><a href="${deep}">رابط بديل</a></p>
<p>أو أدخل في التطبيق رمز المراجعة:</p><p><code>${safeId}</code></p><p>ورمز الدخول:</p><p><code>${safeCode}</code></p>
</div></body></html>`;
  return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8" } });
}

// Box needs client_secret for code exchange and refresh; only these two grants for our own client id are forwarded.
async function boxToken(req, env) {
  if (!env.BOX_CLIENT_ID || !env.BOX_CLIENT_SECRET) return json({ error: "box not configured" }, 501);
  const form = new URLSearchParams(await req.text());
  const grant = form.get("grant_type");
  if (grant !== "authorization_code" && grant !== "refresh_token") return json({ error: "unsupported_grant_type" }, 400);
  if (form.get("client_id") !== env.BOX_CLIENT_ID) return json({ error: "invalid_client" }, 400);
  const out = new URLSearchParams();
  for (const k of ["grant_type", "code", "refresh_token", "redirect_uri", "code_verifier"]) if (form.get(k)) out.set(k, form.get(k));
  out.set("client_id", env.BOX_CLIENT_ID);
  out.set("client_secret", env.BOX_CLIENT_SECRET);
  const r = await fetch("https://api.box.com/oauth2/token", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: out.toString() });
  return new Response(await r.text(), { status: r.status, headers: { "Content-Type": "application/json", ...cors() } });
}

function randomId(n) {
  const a = "abcdefghijklmnopqrstuvwxyz0123456789";
  const b = crypto.getRandomValues(new Uint8Array(n));
  return Array.from(b, (x) => a[x % a.length]).join("");
}
function randomCode(n) {
  const a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const b = crypto.getRandomValues(new Uint8Array(n));
  return Array.from(b, (x) => a[x % a.length]).join("");
}
function dec(s) { try { return s ? decodeURIComponent(s.replace(/\+/g, " ")) : ""; } catch { return s || ""; } }
function cors() { return { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "GET,POST,DELETE" }; }
function json(obj, status = 200) { return new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json", ...cors() } }); }
