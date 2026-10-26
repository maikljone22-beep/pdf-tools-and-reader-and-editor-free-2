// SmartDocs sharing worker (share5): managed shared review extensions, browser reviewers, view-only links.
//
// Bindings (see wrangler.toml):
//   COLLAB  KV  — the SAME namespace as ai-proxy/collab (review meta + comments live there; this worker extends them)
//   SHARE   KV  — this worker's own data (review extras, activity, view links, stats)
//   FILES   R2  — optional; the same bucket as ai-proxy/collab if collab uses one (review PDFs `pdf/<id>`, link PDFs `view/<id>`)
// Secrets / vars:
//   APP_TOKEN (secret, optional) — when set, every /reviews and /links request must carry X-App-Token
//   MAIL_API_KEY (secret, optional), MAIL_API_URL (default Resend), MAIL_FROM — e-mail invitations, reminders, digests
//   PUBLIC_URL — this worker's public origin (used in cron e-mails)
//
// App API (X-App-Token)
//   PUT  /reviews/:id/extra      X-Owner-Key  {subject,message,ownerName,ownerEmail,reviewers:[{name,email}],allowWeb,allowDownload,reminders}
//   GET  /reviews/:id/extra      X-Owner-Key  → {extra, meta, reviewers:[…status], activity:[…]}
//   POST /reviews/:id/deadline   X-Owner-Key  {deadline}           (0 = none; also reopens a review whose deadline passed)
//   POST /reviews/:id/state      X-Owner-Key  {closed}             end / reopen
//   POST /reviews/:id/rotate     X-Owner-Key  → {code}             revoke the old link
//   POST /reviews/:id/remind     X-Owner-Key  {to?}                → {sent} | 501 when mail is not configured
//   POST /reviews/:id/invite     X-Owner-Key  {to?}                first invitation mail
//   POST /reviews/:id/seen       X-Review-Code {participant,name,consent?}
//   POST   /links                body = PDF; X-Title, X-File-Name (URL-encoded), X-Expires (ms, 0 = never), X-Password (URL-encoded), X-Allow-Download (1/0)
//   GET    /links/:id            X-Owner-Key → meta + stats
//   PATCH  /links/:id            X-Owner-Key {title?,expires?,password?,allowDownload?,disabled?}
//   DELETE /links/:id            X-Owner-Key
// Public
//   GET  /w/:id?c=CODE           browser review page          GET /w/:id/file|comments?c=   POST /w/:id/comments|seen?c=
//   GET  /v/:id                  view-only page                POST /v/:id/open {password}   GET /v/:id/file?t=TOKEN[&dl=1]

import { reviewPage, viewPage, messagePage } from "./pages.js";

const MAX_PDF = 20 * 1024 * 1024;
const MAX_TRIES = 10;                 // mirrors LinkRules.MAX_TRIES in the app
const LOCK_WINDOW_MS = 15 * 60 * 1000; // mirrors LinkRules.LOCK_WINDOW_MS
const AFTER_DEADLINE_TTL = 30 * 24 * 3600;
const DEFAULT_TTL = 90 * 24 * 3600;
const LINK_DEFAULT_TTL = 365 * 24 * 3600;

export default {
  async fetch(req, env, ctx) {
    try { return await route(req, env, ctx); }
    catch (e) { return json({ error: String((e && e.message) || e) }, 500); }
  },
  async scheduled(event, env, ctx) { ctx.waitUntil(cron(env, Date.now())); },
};

export async function route(req, env, ctx) {
  const url = new URL(req.url);
  const p = url.pathname.split("/").filter(Boolean);
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors() });

  if (p[0] === "w" && p[1]) return webReview(req, env, url, p);
  if (p[0] === "v" && p[1]) return viewLink(req, env, url, p);
  if (p[0] === "health") return json({ ok: true, mail: mailConfigured(env) });

  if (env.APP_TOKEN && req.headers.get("X-App-Token") !== env.APP_TOKEN) return json({ error: "unauthorized" }, 401);
  if (p[0] === "reviews" && p[1]) return reviewApi(req, env, url, p);
  if (p[0] === "links") return linkApi(req, env, url, p);
  return json({ error: "not found" }, 404);
}

// ------------------------------------------------------------------ shared review extensions

async function loadMeta(env, id) {
  if (!/^[a-z0-9]{10}$/.test(id || "")) return null;
  return await env.COLLAB.get(`meta:${id}`, "json");
}
function ttlFor(meta) {
  if (meta.deadline > 0) return Math.max(Math.ceil((meta.deadline - Date.now()) / 1000) + AFTER_DEADLINE_TTL, 3600);
  return DEFAULT_TTL;
}
async function saveMeta(env, meta) { await env.COLLAB.put(`meta:${meta.id}`, JSON.stringify(meta), { expirationTtl: ttlFor(meta) }); }
async function loadExtra(env, id) { return (await env.SHARE.get(`extra:${id}`, "json")) || null; }
async function saveExtra(env, id, extra, meta) { await env.SHARE.put(`extra:${id}`, JSON.stringify(extra), { expirationTtl: ttlFor(meta) }); }
function ended(meta, now = Date.now()) { return !!meta.closed || (meta.deadline > 0 && now > meta.deadline); }

async function activity(env, id, entry, meta) {
  const key = `act:${id}`;
  const list = (await env.SHARE.get(key, "json")) || [];
  list.unshift({ ...entry, time: Date.now() });
  await env.SHARE.put(key, JSON.stringify(list.slice(0, 200)), { expirationTtl: ttlFor(meta) });
}

async function reviewApi(req, env, url, p) {
  const id = p[1], sub = p[2] || "";
  const meta = await loadMeta(env, id);
  if (!meta) return json({ error: "not found" }, 404);
  if (sub === "seen" && req.method === "POST") {
    if (req.headers.get("X-Review-Code") !== meta.code) return json({ error: "forbidden" }, 403);
    return recordSeen(env, meta, await req.json().catch(() => ({})), false);
  }
  if (req.headers.get("X-Owner-Key") !== meta.ownerKey) return json({ error: "forbidden" }, 403);
  const extra = (await loadExtra(env, id)) || { reviewers: [], allowWeb: false, allowDownload: true, reminders: true };

  if (sub === "extra" && req.method === "PUT") {
    const b = await req.json().catch(() => ({}));
    const reviewers = (Array.isArray(b.reviewers) ? b.reviewers : []).slice(0, 50).map((r) => ({
      name: String((r && r.name) || "").slice(0, 80), email: String((r && r.email) || "").slice(0, 120),
    })).filter((r) => r.name || r.email);
    Object.assign(extra, {
      subject: String(b.subject || extra.subject || meta.title || "").slice(0, 200), message: String(b.message || "").slice(0, 2000),
      ownerName: String(b.ownerName || meta.author || "").slice(0, 80), ownerEmail: String(b.ownerEmail || "").slice(0, 120),
      reviewers, allowWeb: !!b.allowWeb, allowDownload: b.allowDownload !== false, reminders: b.reminders !== false,
    });
    await saveExtra(env, id, extra, meta);
    return json({ ok: true, webUrl: extra.allowWeb ? `${url.origin}/w/${id}?c=${meta.code}` : null });
  }
  if (sub === "extra" && req.method === "GET") return json(await status(env, url, meta, extra));
  if (sub === "deadline" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    meta.deadline = Math.max(0, Number(b.deadline) || 0);
    if (meta.deadline > Date.now()) extra.reminded = false;
    await saveMeta(env, meta); await saveExtra(env, id, extra, meta);
    await activity(env, id, { kind: "deadline", deadline: meta.deadline }, meta);
    return json({ ok: true, deadline: meta.deadline });
  }
  if (sub === "state" && req.method === "POST") {
    const b = await req.json().catch(() => ({}));
    meta.closed = !!b.closed;
    await saveMeta(env, meta);
    await activity(env, id, { kind: meta.closed ? "ended" : "reopened" }, meta);
    return json({ ok: true, closed: meta.closed });
  }
  if (sub === "rotate" && req.method === "POST") {
    meta.code = randomCode(6);
    await saveMeta(env, meta);
    await activity(env, id, { kind: "revoked" }, meta);
    return json({ code: meta.code, shareUrl: `${url.origin}/w/${id}?c=${meta.code}` });
  }
  if ((sub === "remind" || sub === "invite") && req.method === "POST") {
    if (!mailConfigured(env)) return json({ error: "mail not configured" }, 501);
    const b = await req.json().catch(() => ({}));
    const to = (b.to ? [String(b.to)] : extra.reviewers.map((r) => r.email)).filter(validEmail);
    if (!to.length) return json({ sent: 0 });
    const mailBody = inviteHtml(url.origin, meta, extra, sub === "remind", env);
    let sent = 0;
    for (const t of to) if (await sendMail(env, t, (sub === "remind" ? "تذكير: " : "") + (extra.subject || meta.title), mailBody)) sent++;
    await activity(env, id, { kind: sub, count: sent }, meta);
    return json({ sent });
  }
  return json({ error: "not found" }, 404);
}

async function recordSeen(env, meta, b, web) {
  const participant = String(b.participant || "").slice(0, 64);
  if (!/^[A-Za-z0-9-]{8,64}$/.test(participant)) return json({ error: "bad participant" }, 400);
  const key = `seen:${meta.id}:${participant}`;
  const prev = (await env.SHARE.get(key, "json")) || {};
  const rec = { name: String(b.name || prev.name || "").slice(0, 80), time: Date.now(), first: prev.first || Date.now(), web: web || !!prev.web,
    consent: b.consent ? { time: Number(b.consent.time) || Date.now(), version: Number(b.consent.version) || 1, anonymous: !!b.consent.anonymous } : prev.consent || null };
  await env.SHARE.put(key, JSON.stringify(rec), { expirationTtl: ttlFor(meta) });
  if (!prev.first) await activity(env, meta.id, { kind: "opened", name: rec.name, web }, meta);
  if (b.consent && !prev.consent) await activity(env, meta.id, { kind: "consent", name: rec.name, anonymous: rec.consent.anonymous }, meta);
  return json({ ok: true });
}

async function listKeys(kv, prefix) {
  const keys = []; let cursor;
  do { const page = await kv.list({ prefix, cursor }); keys.push(...page.keys); cursor = page.list_complete ? undefined : page.cursor; } while (cursor);
  return keys;
}

async function status(env, url, meta, extra) {
  const participants = (await listKeys(env.COLLAB, `comments:${meta.id}:`)).map((k) => ({
    participant: k.name.split(":")[2], name: (k.metadata && k.metadata.name) || "", lastSeen: (k.metadata && k.metadata.lastSeen) || 0, count: (k.metadata && k.metadata.count) || 0,
  }));
  const seen = {};
  for (const k of await listKeys(env.SHARE, `seen:${meta.id}:`)) seen[k.name.split(":")[2]] = await env.SHARE.get(k.name, "json");
  const norm = (s) => String(s || "").trim().toLowerCase();
  const people = new Map();
  for (const r of extra.reviewers) people.set(norm(r.name) || norm(r.email), { name: r.name, email: r.email, invited: true, status: "invited", count: 0, last: 0, web: false });
  const touch = (name, upd) => {
    const k = norm(name) || "?";
    const cur = people.get(k) || { name, email: "", invited: false, status: "opened", count: 0, last: 0, web: false };
    Object.assign(cur, upd(cur)); people.set(k, cur);
  };
  for (const [pid, s] of Object.entries(seen)) if (s) touch(s.name, (c) => ({ status: c.status === "commented" ? c.status : "opened", last: Math.max(c.last, s.time || 0), web: c.web || !!s.web, consent: s.consent || c.consent || null, participant: pid }));
  for (const x of participants) touch(x.name, (c) => ({ status: x.count > 0 ? "commented" : (c.status === "invited" ? "opened" : c.status), count: c.count + x.count, last: Math.max(c.last, x.lastSeen), participant: x.participant, web: c.web || x.participant.startsWith("web-") }));
  const { ownerKey, ...pub } = meta;
  return {
    extra, meta: pub, reviewers: [...people.values()], activity: (await env.SHARE.get(`act:${meta.id}`, "json")) || [],
    webUrl: extra.allowWeb ? `${url.origin}/w/${meta.id}?c=${meta.code}` : null, mail: mailConfigured(env),
  };
}

// ------------------------------------------------------------------ browser reviewers

async function webReview(req, env, url, p) {
  const id = p[1], sub = p[2] || "";
  const meta = await loadMeta(env, id);
  const code = url.searchParams.get("c") || "";
  if (!meta || code !== meta.code) return sub ? json({ error: "forbidden" }, 403) : html(messagePage("الرابط غير صالح", "انتهت صلاحية رابط المراجعة أو أُلغي. اطلب رابطاً جديداً من صاحب المستند."), 403);
  const extra = (await loadExtra(env, id)) || {};
  if (!extra.allowWeb) return sub ? json({ error: "web review disabled" }, 403) : html(messagePage("المراجعة من المتصفح غير مفعّلة", "افتح الرابط في تطبيق محرر الملفات الذكي على أندرويد."), 403);
  if (!sub && req.method === "GET") return html(reviewPage(meta, extra, ended(meta)));
  if (sub === "file" && req.method === "GET") {
    const body = env.FILES ? (await env.FILES.get(`pdf/${id}`))?.body : await env.COLLAB.get(`pdf:${id}`, "arrayBuffer");
    if (!body) return json({ error: "file missing" }, 404);
    const headers = { "Content-Type": "application/pdf", "Cache-Control": "private, no-store" };
    if (url.searchParams.get("dl") === "1") {
      if (extra.allowDownload === false) return json({ error: "download disabled" }, 403);
      headers["Content-Disposition"] = `attachment; filename*=UTF-8''${encodeURIComponent(meta.fileName || "review.pdf")}`;
    }
    return new Response(body, { headers });
  }
  if (sub === "comments" && req.method === "GET") return json({ now: Date.now(), comments: await allComments(env, id) });
  if (sub === "seen" && req.method === "POST") return recordSeen(env, meta, await req.json().catch(() => ({})), true);
  if (sub === "comments" && req.method === "POST") {
    if (ended(meta)) return json({ error: "review ended" }, 410);
    const raw = await req.text();
    if (raw.length > 256 * 1024) return json({ error: "too large" }, 413);
    let b; try { b = JSON.parse(raw); } catch { return json({ error: "bad json" }, 400); }
    return postWebComments(env, meta, b);
  }
  return json({ error: "not found" }, 404);
}

/** Web reviewers may only add / delete their own sticky notes: /Subtype /Text, no appearance stream. */
export function validWebNote(c) {
  if (!c || typeof c.nm !== "string" || !/^[A-Za-z0-9._-]{4,128}$/.test(c.nm)) return false;
  if (c.deleted) return true;
  const d = c.data && c.data.d;
  if (!d || !d.Subtype || d.Subtype.n !== "Text") return false;
  if (d.AP || d.Popup || d.IRT || d.P) return false;
  if (!Array.isArray(d.Rect) || d.Rect.length !== 4 || !d.Rect.every((v) => typeof v === "number" && isFinite(v))) return false;
  if (!d.Contents || typeof d.Contents.s !== "string" || d.Contents.s.length > 40000) return false;
  const allowed = new Set(["Type", "Subtype", "Rect", "Contents", "T", "NM", "M", "CreationDate", "C", "Name", "F", "Open", "CA"]);
  return Object.keys(d).every((k) => allowed.has(k));
}

async function postWebComments(env, meta, b) {
  const participant = String(b.participant || "");
  if (!/^web-[A-Za-z0-9]{8,60}$/.test(participant)) return json({ error: "bad participant" }, 400);
  const author = String(b.author || "").slice(0, 80);
  const key = `comments:${meta.id}:${participant}`;
  const cur = (await env.COLLAB.get(key, "json")) || { author, items: {} };
  cur.author = author || cur.author;
  let accepted = 0, rejected = 0;
  for (const c of Array.isArray(b.comments) ? b.comments.slice(0, 50) : []) {
    if (!validWebNote(c)) { rejected++; continue; }
    const modified = Number(c.modified) || Date.now();
    const prev = cur.items[c.nm];
    if (prev && prev.modified > modified) continue;
    cur.items[c.nm] = c.deleted ? { deleted: true, modified } : { page: c.page | 0, modified, data: c.data };
    accepted++;
  }
  const count = Object.values(cur.items).filter((x) => !x.deleted).length;
  await env.COLLAB.put(key, JSON.stringify(cur), { expirationTtl: ttlFor(meta), metadata: { name: cur.author, lastSeen: Date.now(), count } });
  if (accepted) await activity(env, meta.id, { kind: "comment", name: cur.author, count: accepted, web: true }, meta);
  return json({ now: Date.now(), accepted, rejected }, rejected && !accepted ? 400 : 200);
}

async function allComments(env, id) {
  const out = [];
  for (const k of await listKeys(env.COLLAB, `comments:${id}:`)) {
    const participant = k.name.split(":")[2];
    const v = await env.COLLAB.get(k.name, "json");
    if (!v) continue;
    for (const [nm, c] of Object.entries(v.items || {})) out.push({ nm, participant, author: v.author || "", modified: c.modified, deleted: !!c.deleted, page: c.page || 0, data: c.deleted ? null : c.data });
  }
  return out;
}

// ------------------------------------------------------------------ view-only links

async function linkApi(req, env, url, p) {
  if (p.length === 1 && req.method === "POST") return createLink(req, env, url);
  const id = p[1];
  if (!/^[a-z0-9]{12}$/.test(id || "")) return json({ error: "not found" }, 404);
  const meta = await env.SHARE.get(`link:${id}`, "json");
  if (!meta) return json({ error: "not found" }, 404);
  if (req.headers.get("X-Owner-Key") !== meta.ownerKey) return json({ error: "forbidden" }, 403);
  if (req.method === "GET") {
    const stats = (await env.SHARE.get(`stats:${id}`, "json")) || { views: 0, unique: 0, downloads: 0, days: {} };
    return json({ ...publicLink(meta), url: `${url.origin}/v/${id}`, stats });
  }
  if (req.method === "PATCH") {
    const b = await req.json().catch(() => ({}));
    if (typeof b.title === "string") meta.title = b.title.slice(0, 200);
    if (b.expires !== undefined) meta.expires = Math.max(0, Number(b.expires) || 0);
    if (typeof b.allowDownload === "boolean") meta.allowDownload = b.allowDownload;
    if (typeof b.disabled === "boolean") meta.disabled = b.disabled;
    if (typeof b.password === "string") {
      if (b.password && (b.password.length < 4 || b.password.length > 64)) return json({ error: "bad password" }, 400);
      Object.assign(meta, b.password ? await hashPassword(b.password) : { pwHash: null, pwSalt: null });
    }
    await env.SHARE.put(`link:${id}`, JSON.stringify(meta), { expirationTtl: linkTtl(meta) });
    return json(publicLink(meta));
  }
  if (req.method === "DELETE") {
    await env.SHARE.delete(`link:${id}`); await env.SHARE.delete(`stats:${id}`);
    if (env.FILES) await env.FILES.delete(`view/${id}`); else await env.SHARE.delete(`vpdf:${id}`);
    return json({ ok: true });
  }
  return json({ error: "not found" }, 404);
}

function linkTtl(meta) { return meta.expires > 0 ? Math.max(Math.ceil((meta.expires - Date.now()) / 1000) + AFTER_DEADLINE_TTL, 3600) : LINK_DEFAULT_TTL; }
function publicLink(m) { return { id: m.id, title: m.title, fileName: m.fileName, created: m.created, expires: m.expires, allowDownload: m.allowDownload, disabled: !!m.disabled, hasPassword: !!m.pwHash, size: m.size }; }

async function createLink(req, env, url) {
  const len = parseInt(req.headers.get("Content-Length") || "0", 10);
  if (len > MAX_PDF) return json({ error: "file too large" }, 413);
  const pdf = await req.arrayBuffer();
  if (!pdf.byteLength || pdf.byteLength > MAX_PDF) return json({ error: "bad file size" }, 413);
  if (String.fromCharCode(...new Uint8Array(pdf, 0, 5)) !== "%PDF-") return json({ error: "not a pdf" }, 400);
  const pw = dec(req.headers.get("X-Password"));
  if (pw && (pw.length < 4 || pw.length > 64)) return json({ error: "bad password" }, 400);
  const id = randomId(12), ownerKey = randomId(32);
  const meta = {
    id, ownerKey, created: Date.now(), size: pdf.byteLength, title: dec(req.headers.get("X-Title")) || "مستند",
    fileName: dec(req.headers.get("X-File-Name")) || "document.pdf", expires: Math.max(0, parseInt(req.headers.get("X-Expires") || "0", 10) || 0),
    allowDownload: req.headers.get("X-Allow-Download") !== "0", disabled: false, pwHash: null, pwSalt: null,
    ...(pw ? await hashPassword(pw) : {}),
  };
  const ttl = linkTtl(meta);
  if (env.FILES) await env.FILES.put(`view/${id}`, pdf, { httpMetadata: { contentType: "application/pdf" } });
  else await env.SHARE.put(`vpdf:${id}`, pdf, { expirationTtl: ttl });
  await env.SHARE.put(`link:${id}`, JSON.stringify(meta), { expirationTtl: ttl });
  return json({ id, ownerKey, url: `${url.origin}/v/${id}` });
}

export function linkState(meta, now) {
  if (meta.disabled) return "disabled";
  if (meta.expires > 0 && now >= meta.expires) return "expired";
  return "active";
}
/** Sliding-window lockout identical to LinkRules.Tries in the app. */
export function triesLocked(times, now) { return times.filter((t) => now - t < LOCK_WINDOW_MS).length >= MAX_TRIES; }

async function viewLink(req, env, url, p) {
  const id = p[1], sub = p[2] || "";
  const meta = /^[a-z0-9]{12}$/.test(id) ? await env.SHARE.get(`link:${id}`, "json") : null;
  const now = Date.now();
  if (!meta) return sub ? json({ error: "not found" }, 404) : html(messagePage("الرابط غير موجود", "ربما حذفه صاحبه."), 404);
  const state = linkState(meta, now);
  if (state !== "active") {
    const msg = state === "expired" ? "انتهت صلاحية هذا الرابط." : "أوقف صاحب المستند هذا الرابط مؤقتاً.";
    return sub ? json({ error: state }, 410) : html(messagePage("الرابط غير متاح", msg), 410);
  }
  if (!sub && req.method === "GET") return html(viewPage(meta, !!meta.pwHash));
  if (sub === "open" && req.method === "POST") {
    const ipKey = await sha256Hex((req.headers.get("CF-Connecting-IP") || "") + "|" + id);
    const tryKey = `try:${id}:${ipKey.slice(0, 24)}`;
    const times = ((await env.SHARE.get(tryKey, "json")) || []).filter((t) => now - t < LOCK_WINDOW_MS);
    if (times.length >= MAX_TRIES) return json({ error: "locked", retryInMs: times.sort()[times.length - MAX_TRIES] + LOCK_WINDOW_MS - now }, 429);
    const b = await req.json().catch(() => ({}));
    if (meta.pwHash) {
      const ok = await checkPassword(String(b.password || ""), meta);
      if (!ok) { times.push(now); await env.SHARE.put(tryKey, JSON.stringify(times), { expirationTtl: 900 }); return json({ error: "wrong password", left: MAX_TRIES - times.length }, 401); }
    }
    const token = randomId(32);
    await env.SHARE.put(`vt:${token}`, JSON.stringify({ id, counted: false }), { expirationTtl: 3600 });
    return json({ token, title: meta.title, fileName: meta.fileName, allowDownload: meta.allowDownload });
  }
  if (sub === "file" && req.method === "GET") {
    const token = url.searchParams.get("t") || "";
    const tk = /^[a-z0-9]{32}$/.test(token) ? await env.SHARE.get(`vt:${token}`, "json") : null;
    if (!tk || tk.id !== id) return json({ error: "forbidden" }, 403);
    const dl = url.searchParams.get("dl") === "1";
    if (dl && !meta.allowDownload) return json({ error: "download disabled" }, 403);
    const body = env.FILES ? (await env.FILES.get(`view/${id}`))?.body : await env.SHARE.get(`vpdf:${id}`, "arrayBuffer");
    if (!body) return json({ error: "file missing" }, 404);
    await countView(env, meta, req, tk, token, dl);
    const headers = { "Content-Type": "application/pdf", "Cache-Control": "private, no-store" };
    if (dl) headers["Content-Disposition"] = `attachment; filename*=UTF-8''${encodeURIComponent(meta.fileName)}`;
    return new Response(body, { headers });
  }
  return json({ error: "not found" }, 404);
}

async function countView(env, meta, req, tk, token, dl) {
  const key = `stats:${meta.id}`;
  const s = (await env.SHARE.get(key, "json")) || { views: 0, unique: 0, downloads: 0, days: {} };
  if (dl) s.downloads++;
  if (!tk.counted) {
    s.views++;
    const day = new Date().toISOString().slice(0, 10);
    s.days[day] = (s.days[day] || 0) + 1;
    const days = Object.keys(s.days).sort(); while (days.length > 90) delete s.days[days.shift()];
    const vis = (await sha256Hex((req.headers.get("CF-Connecting-IP") || "") + "|" + (req.headers.get("User-Agent") || ""))).slice(0, 32);
    const uv = `uv:${meta.id}:${vis}`;
    if (!(await env.SHARE.get(uv))) { s.unique++; await env.SHARE.put(uv, "1", { expirationTtl: 30 * 24 * 3600 }); }
    await env.SHARE.put(`vt:${token}`, JSON.stringify({ ...tk, counted: true }), { expirationTtl: 3600 });
  }
  s.last = Date.now();
  await env.SHARE.put(key, JSON.stringify(s), { expirationTtl: linkTtl(meta) });
}

// ------------------------------------------------------------------ cron: deadline reminders, @mentions, owner digest

export async function cron(env, now) {
  if (!mailConfigured(env)) return { skipped: true };
  const origin = (env.PUBLIC_URL || "").replace(/\/$/, "");
  let reminders = 0, mentions = 0, digests = 0;
  for (const k of await listKeys(env.SHARE, "extra:")) {
    const id = k.name.slice(6);
    const meta = await loadMeta(env, id);
    const extra = await env.SHARE.get(k.name, "json");
    if (!meta || !extra || ended(meta, now)) continue;
    if (extra.reminders !== false && meta.deadline > now && meta.deadline - now < 24 * 3600 * 1000 && !extra.reminded) {
      for (const r of extra.reviewers) if (validEmail(r.email) && await sendMail(env, r.email, "تذكير: " + (extra.subject || meta.title), inviteHtml(origin, meta, extra, true, env))) reminders++;
      extra.reminded = true;
    }
    const comments = (await allComments(env, id)).filter((c) => !c.deleted && c.data);
    const done = new Set(extra.mentioned || []);
    for (const c of comments) {
      if (done.has(c.nm)) continue;
      done.add(c.nm);
      const text = pdfText(c.data.d && c.data.d.Contents);
      for (const r of extra.reviewers) {
        if (!validEmail(r.email) || !r.name) continue;
        const tag = "@" + r.name.trim().replace(/\s+/g, "_");
        if (text.toLowerCase().includes(tag.toLowerCase()) || text.includes("@" + r.name.trim())) {
          if (await sendMail(env, r.email, `ذكرك ${c.author || "مراجع"} في «${extra.subject || meta.title}»`, mentionHtml(origin, meta, extra, c.author, text))) mentions++;
        }
      }
    }
    extra.mentioned = [...done].slice(-2000);
    const count = comments.length;
    const lastDigest = extra.lastDigest || 0;
    if (validEmail(extra.ownerEmail) && count > (extra.digestCount || 0) && now - lastDigest > 20 * 3600 * 1000) {
      if (await sendMail(env, extra.ownerEmail, `تعليقات جديدة على «${extra.subject || meta.title}»`, digestHtml(meta, extra, count - (extra.digestCount || 0), count))) digests++;
      extra.lastDigest = now; extra.digestCount = count;
    }
    await saveExtra(env, id, extra, meta);
  }
  return { reminders, mentions, digests };
}

// ------------------------------------------------------------------ mail

function mailConfigured(env) { return !!(env.MAIL_API_KEY && env.MAIL_FROM); }
function validEmail(s) { return typeof s === "string" && /^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/.test(s); }
async function sendMail(env, to, subject, htmlBody) {
  if (!mailConfigured(env)) return false;
  const r = await fetch(env.MAIL_API_URL || "https://api.resend.com/emails", {
    method: "POST", headers: { Authorization: `Bearer ${env.MAIL_API_KEY}`, "Content-Type": "application/json" },
    body: JSON.stringify({ from: env.MAIL_FROM, to: [to], subject, html: htmlBody }),
  });
  return r.ok;
}
function esc(s) { return String(s || "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
function inviteHtml(origin, meta, extra, reminder, env) {
  const app = `${((env && env.COLLAB_URL) || origin).replace(/\/$/, "")}/r/${meta.id}?c=${meta.code}`;
  const web = extra.allowWeb ? `${origin}/w/${meta.id}?c=${meta.code}` : null;
  const due = meta.deadline > 0 ? `<p>الموعد النهائي: <b>${new Date(meta.deadline).toISOString().slice(0, 16).replace("T", " ")}</b> (UTC)</p>` : "";
  return `<div dir="rtl" style="font-family:Tahoma,Arial,sans-serif">
<p>${reminder ? "تذكير بمراجعة" : "دعاك"} ${esc(extra.ownerName || meta.author || "")} ${reminder ? "" : "لمراجعة"} المستند «${esc(extra.subject || meta.title)}».</p>
${extra.message ? `<p>${esc(extra.message).replace(/\n/g, "<br>")}</p>` : ""}${due}
<p><a href="${app}">فتح في تطبيق محرر الملفات الذكي</a>${web ? ` — أو <a href="${web}">التعليق من المتصفح</a>` : ""}</p>
<p style="color:#666;font-size:12px">رمز المراجعة: ${meta.id} — رمز الدخول: ${esc(meta.code)}</p></div>`;
}
function mentionHtml(origin, meta, extra, author, text) {
  return `<div dir="rtl" style="font-family:Tahoma,Arial,sans-serif"><p>ذكرك ${esc(author || "مراجع")} في تعليق على «${esc(extra.subject || meta.title)}»:</p>
<blockquote>${esc(text).slice(0, 1000)}</blockquote>${extra.allowWeb ? `<p><a href="${origin}/w/${meta.id}?c=${meta.code}">عرض المراجعة</a></p>` : ""}</div>`;
}
function digestHtml(meta, extra, fresh, total) {
  return `<div dir="rtl" style="font-family:Tahoma,Arial,sans-serif"><p>وصلت ${fresh} تعليقات جديدة على «${esc(extra.subject || meta.title)}» (المجموع ${total}).</p><p>افتح المراجعة في التطبيق لدمجها.</p></div>`;
}

/** Decodes a PDF text string from the comment JSON form {"s": base64}. */
export function pdfText(v) {
  if (!v || typeof v.s !== "string") return "";
  let bin; try { bin = atob(v.s); } catch { return ""; }
  if (bin.length >= 2 && bin.charCodeAt(0) === 0xfe && bin.charCodeAt(1) === 0xff) {
    let out = ""; for (let i = 2; i + 1 < bin.length; i += 2) out += String.fromCharCode((bin.charCodeAt(i) << 8) | bin.charCodeAt(i + 1));
    return out;
  }
  try { return decodeURIComponent(escape(bin)); } catch { return bin; }
}

// ------------------------------------------------------------------ helpers

async function sha256Hex(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
async function pbkdf2(pw, saltHex) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(pw), "PBKDF2", false, ["deriveBits"]);
  const salt = new Uint8Array(saltHex.match(/../g).map((h) => parseInt(h, 16)));
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations: 100000 }, key, 256);
  return [...new Uint8Array(bits)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
async function hashPassword(pw) {
  const salt = [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, "0")).join("");
  return { pwSalt: salt, pwHash: await pbkdf2(pw, salt) };
}
async function checkPassword(pw, meta) {
  const h = await pbkdf2(pw, meta.pwSalt);
  let diff = h.length ^ meta.pwHash.length;
  for (let i = 0; i < Math.min(h.length, meta.pwHash.length); i++) diff |= h.charCodeAt(i) ^ meta.pwHash.charCodeAt(i);
  return diff === 0;
}
function randomId(n) { const a = "abcdefghijklmnopqrstuvwxyz0123456789"; return Array.from(crypto.getRandomValues(new Uint8Array(n)), (x) => a[x % a.length]).join(""); }
function randomCode(n) { const a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; return Array.from(crypto.getRandomValues(new Uint8Array(n)), (x) => a[x % a.length]).join(""); }
function dec(s) { try { return s ? decodeURIComponent(s.replace(/\+/g, " ")) : ""; } catch { return s || ""; } }
function cors() { return { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "GET,POST,PUT,PATCH,DELETE" }; }
function json(obj, status = 200) { return new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json", ...cors() } }); }
function html(body, status = 200) {
  return new Response(body, { status, headers: { "Content-Type": "text/html; charset=utf-8", "Referrer-Policy": "no-referrer", "X-Frame-Options": "DENY" } });
}
