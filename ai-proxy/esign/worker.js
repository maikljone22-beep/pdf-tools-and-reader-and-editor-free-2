// SmartDocs signature requests — Cloudflare Worker.
//
// Storage: KV namespace ESIGN (request metadata, signer values, hash index). PDFs go to the R2 bucket FILES when bound,
// otherwise into KV (<= 20 MB). Secrets: APP_TOKEN (optional, checked on app calls), MAIL_API_KEY (optional).
// Vars: PUBLIC_URL (links in mails/cron), MAIL_API_URL + MAIL_FROM (generic JSON mail API: POST {from,to,subject,html}
// with "Authorization: Bearer MAIL_API_KEY"). Without mail settings links are shared from the app.
//
// Sender (X-App-Token + X-Owner-Key):
//   POST   /requests                   JSON {title,sender,senderEmail,message,order,signers[{name,email}],fields[],pageCount,expiresDays,sendMail}
//                                      -> {id, ownerKey}
//   PUT    /requests/:id/file          body = PDF -> {sha, links[], appLinks[], mailed}
//   GET    /requests/:id               -> request (signers, fields, audit, status)
//   GET    /requests/:id/links         -> {links[]}
//   GET    /requests/:id/values        -> {values:[{signer, values{fieldId:value}, event}]}
//   POST   /requests/:id/remind        {signer:-1|i} -> {mailed}
//   POST   /requests/:id/cancel
//   PUT    /requests/:id/final         body = completed PDF -> {sha}
//   DELETE /requests/:id
// Signer (token from the link, X-App-Token only for app calls; the web page is public):
//   GET  /sign/:id?t=    -> {request (only this signer's fields), me, canSign, reason}     (marks "viewed")
//   GET  /sign/:id/file?t=
//   POST /sign/:id/submit?t=  {values, consent:true, device, channel}
//   POST /sign/:id/decline?t= {reason, device, channel}
//   GET  /s/:id?t=       mobile web signing page
//   GET  /w/sign/:id?t=, /w/sign/:id/file?t=, POST /w/sign/:id/submit|decline?t=   (same as /sign/* without app token, for the page)
// Public: GET /v (verification page), GET /verify/:sha -> {id,title,completed,signers[]} or 404.
// Cron (every 6 h): reminder mails to pending signers idle for 48 h, expiry of old requests.

import { signPage, verifyPage } from "./sign-page.js";

const MAX_PDF = 20 * 1024 * 1024;
const MAX_SIGNERS = 20;
const MAX_FIELDS = 500;
const TTL = 120 * 24 * 3600;
const DAY = 24 * 3600 * 1000;

export default {
  async fetch(req, env) {
    try { return await route(req, env); } catch (e) { return json({ error: String((e && e.message) || e) }, e && e.status || 500); }
  },
  async scheduled(event, env, ctx) { ctx.waitUntil(cron(env)); },
};

class HttpError extends Error { constructor(status, msg) { super(msg); this.status = status; } }

async function route(req, env) {
  const url = new URL(req.url);
  const p = url.pathname.split("/").filter(Boolean);
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors() });
  const origin = env.PUBLIC_URL ? env.PUBLIC_URL.replace(/\/$/, "") : url.origin;

  // ---- public pages
  if (req.method === "GET" && p[0] === "s" && p[1]) return html(signPage(p[1], url.searchParams.get("t") || "", origin));
  if (req.method === "GET" && p[0] === "v" && !p[1]) return html(verifyPage(origin, ""));
  if (req.method === "GET" && p[0] === "v" && p[1]) return html(verifyPage(origin, p[1]));
  if (req.method === "GET" && p[0] === "verify" && p[1]) return verify(env, p[1]);
  if (p[0] === "w" && p[1] === "sign") return signerRoutes(req, env, p.slice(1), url, origin, "web");

  if (env.APP_TOKEN && req.headers.get("X-App-Token") !== env.APP_TOKEN) return json({ error: "unauthorized" }, 401);
  if (p[0] === "sign") return signerRoutes(req, env, p, url, origin, "app");
  if (p[0] !== "requests") return json({ error: "not found" }, 404);
  if (p.length === 1 && req.method === "POST") return create(req, env);

  const id = p[1];
  const meta = await load(env, id);
  if (req.headers.get("X-Owner-Key") !== meta.ownerKey) return json({ error: "forbidden" }, 403);
  const sub = p[2] || "";
  if (req.method === "PUT" && sub === "file") return uploadFile(req, env, meta, origin);
  if (req.method === "GET" && sub === "") return json(ownerView(meta));
  if (req.method === "GET" && sub === "links") return json({ links: meta.signers.map((s) => link(origin, meta.id, s.token)) });
  if (req.method === "GET" && sub === "values") return json({ values: await allValues(env, meta) });
  if (req.method === "POST" && sub === "remind") {
    const body = await req.json().catch(() => ({}));
    const mailed = await remind(env, meta, origin, typeof body.signer === "number" && body.signer >= 0 ? body.signer : -1);
    await save(env, meta);
    return json({ mailed });
  }
  if (req.method === "POST" && sub === "cancel") {
    if (["completed", "finalized"].includes(meta.status)) throw new HttpError(409, "already completed");
    meta.status = "cancelled"; audit(meta, "cancelled", meta.sender); await save(env, meta); return json({ ok: true });
  }
  if (req.method === "PUT" && sub === "final") return uploadFinal(req, env, meta);
  if (req.method === "DELETE" && sub === "") { await remove(env, meta); return json({ ok: true }); }
  return json({ error: "not found" }, 404);
}

// ---------------------------------------------------------------- sender

async function create(req, env) {
  const b = await req.json();
  const signers = (Array.isArray(b.signers) ? b.signers : []).slice(0, MAX_SIGNERS + 1);
  if (signers.length === 0 || signers.length > MAX_SIGNERS) throw new HttpError(400, "1-20 signers");
  const fields = (Array.isArray(b.fields) ? b.fields : []);
  if (fields.length > MAX_FIELDS) throw new HttpError(400, "too many fields");
  const id = rnd(12), ownerKey = rnd(32);
  const meta = {
    id, ownerKey, title: str(b.title, 120) || "طلب توقيع", sender: str(b.sender, 80), senderEmail: str(b.senderEmail, 120),
    message: str(b.message, 1000), order: b.order === "parallel" ? "parallel" : "sequential", pageCount: b.pageCount | 0,
    sendMail: !!b.sendMail, created: Date.now(), expires: Date.now() + Math.min(Math.max(b.expiresDays | 0 || 30, 1), 90) * DAY,
    status: "draft", originalSha: "", finalSha: "", audit: [],
    signers: signers.map((s) => ({ name: str(s.name, 80) || "?", email: str(s.email, 120), token: rnd(24), status: "waiting", viewedAt: 0, signedAt: 0, declineReason: "", lastMail: 0 })),
    fields: fields.map((f) => ({
      id: str(f.id, 40), signer: f.signer | 0, page: f.page | 0, x: num(f.x), y: num(f.y), w: num(f.w), h: num(f.h),
      type: ["signature", "initials", "date", "text"].includes(f.type) ? f.type : "signature", required: f.required !== false, label: str(f.label, 60),
    })).filter((f) => f.signer >= 0 && f.signer < signers.length),
  };
  audit(meta, "created", meta.sender);
  await save(env, meta);
  return json({ id, ownerKey });
}

async function uploadFile(req, env, meta, origin) {
  if (meta.status !== "draft") throw new HttpError(409, "already sent");
  const pdf = await req.arrayBuffer();
  if (pdf.byteLength === 0 || pdf.byteLength > MAX_PDF) throw new HttpError(413, "bad file size");
  if (String.fromCharCode(...new Uint8Array(pdf, 0, 5)) !== "%PDF-") throw new HttpError(400, "not a pdf");
  await putFile(env, `orig:${meta.id}`, pdf);
  meta.originalSha = await sha256(pdf);
  meta.status = "out";
  flowNormalize(meta);
  audit(meta, "sent", meta.sender);
  let mailed = false;
  if (meta.sendMail && mailConfigured(env)) {
    for (const i of flowActive(meta)) mailed = (await mailSigner(env, meta, i, origin, false)) || mailed;
  }
  await save(env, meta);
  return json({
    sha: meta.originalSha, mailed,
    links: meta.signers.map((s) => link(origin, meta.id, s.token)),
    appLinks: meta.signers.map((s) => `smartdocs://esign/sign?id=${meta.id}&t=${s.token}`),
  });
}

async function uploadFinal(req, env, meta) {
  if (meta.status !== "completed" && meta.status !== "finalized") throw new HttpError(409, "not completed");
  const pdf = await req.arrayBuffer();
  if (pdf.byteLength === 0 || pdf.byteLength > MAX_PDF * 2) throw new HttpError(413, "bad file size");
  const sha = await sha256(pdf);
  await putFile(env, `final:${meta.id}`, pdf);
  if (meta.finalSha && meta.finalSha !== sha) await env.ESIGN.delete(`sha:${meta.finalSha}`);
  meta.finalSha = sha; meta.status = "finalized";
  audit(meta, "finalized", meta.sender, "", "", sha);
  await env.ESIGN.put(`sha:${sha}`, meta.id, { expirationTtl: 3650 * 24 * 3600 });
  await env.ESIGN.put(`sha:${meta.originalSha}`, meta.id, { expirationTtl: 3650 * 24 * 3600 });
  await save(env, meta, 3650 * 24 * 3600);
  return json({ sha });
}

function ownerView(meta) {
  const { ownerKey, ...rest } = meta;
  return { ...rest, signers: meta.signers.map(({ token, lastMail, ...s }) => s) };
}

async function allValues(env, meta) {
  const out = [];
  for (let i = 0; i < meta.signers.length; i++) {
    const v = await env.ESIGN.get(`val:${meta.id}:${i}`, "json");
    if (v) out.push({ signer: i, values: v.values || {}, event: v.event || null });
  }
  return out;
}

async function remind(env, meta, origin, which) {
  if (meta.status !== "out") return false;
  const targets = which >= 0 ? [which] : flowActive(meta);
  let mailed = false;
  for (const i of targets) {
    if (!flowActive(meta).includes(i)) continue;
    mailed = (await mailSigner(env, meta, i, origin, true)) || mailed;
    audit(meta, "reminded", meta.signers[i].name);
  }
  return mailed;
}

async function remove(env, meta) {
  for (let i = 0; i < meta.signers.length; i++) await env.ESIGN.delete(`val:${meta.id}:${i}`);
  await delFile(env, `orig:${meta.id}`); await delFile(env, `final:${meta.id}`);
  if (meta.finalSha) await env.ESIGN.delete(`sha:${meta.finalSha}`);
  if (meta.originalSha) await env.ESIGN.delete(`sha:${meta.originalSha}`);
  await env.ESIGN.delete(`req:${meta.id}`);
}

// ---------------------------------------------------------------- signer

async function signerRoutes(req, env, p, url, origin, channelDefault) {
  const id = p[1];
  const token = url.searchParams.get("t") || "";
  const meta = await load(env, id);
  const me = meta.signers.findIndex((s) => s.token === token);
  if (me < 0 || token.length < 20) throw new HttpError(403, "bad link");
  const sub = p[2] || "";
  const ip = req.headers.get("CF-Connecting-IP") || "";
  const ua = (req.headers.get("User-Agent") || "").slice(0, 300);
  if (req.method === "GET" && sub === "") {
    const st = state(meta, me);
    if (st.canSign && meta.signers[me].status === "pending") {
      meta.signers[me].status = "viewed"; meta.signers[me].viewedAt = Date.now();
      audit(meta, "viewed", meta.signers[me].name, ip, channelDefault);
      await save(env, meta);
    }
    const view = ownerView(meta);
    view.fields = meta.fields.filter((f) => f.signer === me);
    view.audit = [];
    view.signers = view.signers.map((s, i) => (i === me ? s : { name: s.name, email: "", status: s.status, signedAt: s.signedAt }));
    return json({ request: view, me, canSign: st.canSign, reason: st.reason });
  }
  if (req.method === "GET" && sub === "file") {
    const body = await getFile(env, `orig:${meta.id}`);
    if (!body) throw new HttpError(404, "file missing");
    return new Response(body, { headers: { "Content-Type": "application/pdf", ...cors() } });
  }
  if (req.method === "GET" && sub === "final") {
    const body = await getFile(env, `final:${meta.id}`);
    if (!body) throw new HttpError(404, "not finalized");
    return new Response(body, { headers: { "Content-Type": "application/pdf", ...cors() } });
  }
  const st = state(meta, me);
  if (req.method === "POST" && (sub === "submit" || sub === "decline")) {
    if (!st.canSign) throw new HttpError(st.reason === "turn" ? 409 : 410, st.reason);
    const b = await req.json().catch(() => ({}));
    const event = { time: Date.now(), ip, ua, device: str(b.device, 120), channel: b.channel === "app" ? "app" : "web" };
    if (sub === "decline") {
      flowDecline(meta, me, str(b.reason, 500));
      meta.signers[me].event = event;
      audit(meta, "declined", meta.signers[me].name, ip, event.device || event.channel, meta.signers[me].declineReason);
      await save(env, meta);
      await mailOwner(env, meta, `رفض ${meta.signers[me].name} التوقيع: ${meta.title}`);
      return json({ ok: true, status: meta.status });
    }
    if (b.consent !== true) throw new HttpError(400, "consent required");
    const values = {};
    const mine = meta.fields.filter((f) => f.signer === me);
    for (const f of mine) {
      const v = b.values && typeof b.values[f.id] === "string" ? b.values[f.id] : "";
      if (f.required && !v) throw new HttpError(400, `missing field ${f.id}`);
      if (v.length > 400 * 1024) throw new HttpError(413, "value too large");
      if (v) values[f.id] = v;
    }
    await env.ESIGN.put(`val:${meta.id}:${me}`, JSON.stringify({ values, event }), { expirationTtl: TTL });
    flowSign(meta, me);
    meta.signers[me].event = event;
    audit(meta, "signed", meta.signers[me].name, ip, event.device || event.channel);
    if (meta.status === "completed") await mailOwner(env, meta, `اكتمل التوقيع: ${meta.title}`);
    else if (mailConfigured(env)) for (const i of flowActive(meta)) if (meta.signers[i].lastMail === 0) await mailSigner(env, meta, i, origin, false);
    await save(env, meta);
    return json({ ok: true, status: meta.status });
  }
  return json({ error: "not found" }, 404);
}

function state(meta, me) {
  if (meta.status === "cancelled") return { canSign: false, reason: "cancelled" };
  if (meta.status === "declined") return { canSign: false, reason: "declined" };
  if (meta.status === "draft") return { canSign: false, reason: "draft" };
  if (Date.now() > meta.expires && meta.status === "out") return { canSign: false, reason: "expired" };
  const s = meta.signers[me];
  if (s.status === "signed") return { canSign: false, reason: "signed" };
  if (s.status === "declined") return { canSign: false, reason: "declined" };
  if (!flowActive(meta).includes(me)) return { canSign: false, reason: "turn" };
  return { canSign: true, reason: "" };
}

// ---- order rules (same as SignerFlow in the app)
function done(s) { return s.status === "signed" || s.status === "declined"; }
export function flowActive(meta) {
  if (meta.signers.some((s) => s.status === "declined")) return [];
  const open = meta.signers.map((s, i) => (done(s) ? -1 : i)).filter((i) => i >= 0);
  return meta.order === "parallel" ? open : open.slice(0, 1);
}
export function flowNormalize(meta) {
  const act = flowActive(meta);
  meta.signers.forEach((s, i) => { if (!done(s)) s.status = act.includes(i) ? (s.status === "viewed" ? "viewed" : "pending") : "waiting"; });
  if (meta.signers.some((s) => s.status === "declined")) meta.status = "declined";
  else if (meta.signers.every((s) => s.status === "signed")) meta.status = "completed";
}
export function flowSign(meta, i) { meta.signers[i].status = "signed"; meta.signers[i].signedAt = Date.now(); flowNormalize(meta); }
export function flowDecline(meta, i, reason) { meta.signers[i].status = "declined"; meta.signers[i].signedAt = Date.now(); meta.signers[i].declineReason = reason; flowNormalize(meta); }

// ---------------------------------------------------------------- verify

async function verify(env, sha) {
  if (!/^[0-9a-f]{64}$/.test(sha)) return json({ error: "bad hash" }, 400);
  const id = await env.ESIGN.get(`sha:${sha}`);
  if (!id) return json({ error: "unknown" }, 404);
  const meta = await env.ESIGN.get(`req:${id}`, "json");
  if (!meta) return json({ error: "unknown" }, 404);
  return json({
    id: meta.id, title: meta.title, sender: meta.sender, created: meta.created, status: meta.status,
    kind: sha === meta.finalSha ? "final" : "original", originalSha: meta.originalSha, finalSha: meta.finalSha,
    signers: meta.signers.map((s) => ({ name: s.name, status: s.status, signedAt: s.signedAt })),
  });
}

// ---------------------------------------------------------------- cron

async function cron(env) {
  if (!mailConfigured(env) || !env.PUBLIC_URL) return;
  let cursor;
  do {
    const page = await env.ESIGN.list({ prefix: "req:", cursor });
    for (const k of page.keys) {
      const meta = await env.ESIGN.get(k.name, "json");
      if (!meta || meta.status !== "out" || !meta.sendMail) continue;
      if (Date.now() > meta.expires) continue;
      let changed = false;
      for (const i of flowActive(meta)) {
        const s = meta.signers[i];
        if (s.email && Date.now() - Math.max(s.lastMail, meta.created) > 2 * DAY) { await mailSigner(env, meta, i, env.PUBLIC_URL, true); audit(meta, "reminded", s.name); changed = true; }
      }
      if (changed) await save(env, meta);
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
}

// ---------------------------------------------------------------- mail

function mailConfigured(env) { return !!(env.MAIL_API_URL && env.MAIL_API_KEY && env.MAIL_FROM); }

async function sendMail(env, to, subject, htmlBody) {
  if (!mailConfigured(env) || !to) return false;
  const r = await fetch(env.MAIL_API_URL, {
    method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${env.MAIL_API_KEY}` },
    body: JSON.stringify({ from: env.MAIL_FROM, to: [to], subject, html: htmlBody }),
  });
  return r.ok;
}

async function mailSigner(env, meta, i, origin, reminder) {
  const s = meta.signers[i];
  if (!s.email) return false;
  const l = link(origin, meta.id, s.token);
  const body = `<div dir="rtl" style="font-family:Tahoma,Arial">
<p>${esc(reminder ? "تذكير: " : "")}${esc(meta.sender || "مرسل")} يطلب توقيعك على «${esc(meta.title)}».</p>
${meta.message ? `<p>${esc(meta.message)}</p>` : ""}
<p><a href="${l}" style="background:#2563eb;color:#fff;padding:10px 20px;border-radius:10px;text-decoration:none">مراجعة المستند وتوقيعه</a></p>
<p style="color:#666;font-size:12px">هذا الرابط خاص بك، لا تعِد توجيهه.</p></div>`;
  const ok = await sendMail(env, s.email, `${reminder ? "تذكير: " : ""}طلب توقيع — ${meta.title}`, body);
  if (ok) s.lastMail = Date.now();
  return ok;
}

async function mailOwner(env, meta, subject) {
  if (!meta.senderEmail) return false;
  return sendMail(env, meta.senderEmail, subject, `<div dir="rtl" style="font-family:Tahoma,Arial"><p>${esc(subject)}</p><p>افتح التطبيق لإنشاء النسخة الموقّعة النهائية.</p></div>`);
}

// ---------------------------------------------------------------- storage helpers

async function load(env, id) {
  if (!/^[a-z0-9]{12}$/.test(id || "")) throw new HttpError(404, "not found");
  const meta = await env.ESIGN.get(`req:${id}`, "json");
  if (!meta) throw new HttpError(404, "not found");
  return meta;
}
async function save(env, meta, ttl = TTL) { await env.ESIGN.put(`req:${meta.id}`, JSON.stringify(meta), { expirationTtl: ttl }); }
async function putFile(env, key, buf) {
  if (env.FILES) await env.FILES.put(`esign/${key}`, buf, { httpMetadata: { contentType: "application/pdf" } });
  else await env.ESIGN.put(`file:${key}`, buf, { expirationTtl: TTL });
}
async function getFile(env, key) { return env.FILES ? (await env.FILES.get(`esign/${key}`))?.body : env.ESIGN.get(`file:${key}`, "arrayBuffer"); }
async function delFile(env, key) { if (env.FILES) await env.FILES.delete(`esign/${key}`); else await env.ESIGN.delete(`file:${key}`); }

function audit(meta, kind, who, ip = "", device = "", detail = "") { meta.audit.push({ time: Date.now(), kind, who: who || "", ip, device, detail }); if (meta.audit.length > 300) meta.audit.splice(0, meta.audit.length - 300); }
function link(origin, id, token) { return `${origin}/s/${id}?t=${token}`; }
async function sha256(buf) { const d = await crypto.subtle.digest("SHA-256", buf); return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join(""); }
function rnd(n) { const a = "abcdefghijklmnopqrstuvwxyz0123456789"; return Array.from(crypto.getRandomValues(new Uint8Array(n)), (x) => a[x % a.length]).join(""); }
function str(v, max) { return typeof v === "string" ? v.slice(0, max) : ""; }
function num(v) { const n = Number(v); return Number.isFinite(n) ? Math.min(Math.max(n, 0), 1) : 0; }
function esc(s) { return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
function cors() { return { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "GET,POST,PUT,DELETE" }; }
function json(o, status = 200) { return new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json; charset=utf-8", ...cors() } }); }
function html(s) { return new Response(s, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } }); }
