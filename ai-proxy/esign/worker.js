// SmartDocs signature requests — Cloudflare Worker.
//
// Storage: KV namespace ESIGN (request metadata, signer values, hash index). PDFs go to the R2 bucket FILES when bound,
// otherwise into KV (<= 20 MB). Secrets: APP_TOKEN (optional, checked on app calls), MAIL_API_KEY (optional).
// Vars: PUBLIC_URL (links in mails/cron), MAIL_API_URL + MAIL_FROM (generic JSON mail API: POST {from,to,subject,html}
// with "Authorization: Bearer MAIL_API_KEY"). Without mail settings links are shared from the app.
//
// Sender (X-App-Token + X-Owner-Key):
//   POST   /requests                   JSON {title,sender,senderEmail,message,order,signers[{name,email}],fields[],pageCount,expiresDays,sendMail,
//                                            brand{name,color:"#rrggbb",logo:base64 PNG/JPEG/WebP <= 100 KB}}
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
// Online forms (one reusable public link; anyone fills and signs their own copy). Owner calls as above:
//   POST   /forms                      JSON {title,sender,senderEmail,message,fields[] (signer 0),pageCount,maxResponses,requireEmail,notify,brand}
//                                      -> {id, ownerKey}
//   PUT    /forms/:id/file             body = PDF -> {sha, link}  (form becomes "active")
//   GET    /forms/:id                  -> form (status active|disabled|full, count)
//   GET    /forms/:id/submissions      -> {submissions:[{sid,name,email,time,finalSha}]} (newest last)
//   GET    /forms/:id/submissions/:sid -> {sid,name,email,time,values{fieldId:value},event,finalSha}
//   PUT    /forms/:id/submissions/:sid/final  body = that person's completed PDF -> {sha}  (registered for /verify)
//   POST   /forms/:id/disable | /forms/:id/enable
//   DELETE /forms/:id                  (form, file, logo and every submission)
//   Public: GET /f/:id (web page), GET /w/form/:id -> {form, open, reason}, GET /w/form/:id/file,
//           POST /w/form/:id/submit {name,email,values,consent:true,device} -> {ok,sid}
// Branding: GET /logo/:id (public) -> the request's / form's logo image.
// Public: GET /v (verification page), GET /verify/:sha -> {id,title,completed,signers[]} or 404.
// Cron (every 6 h): reminder mails to pending signers idle for 48 h, expiry of old requests.

import { signPage, verifyPage, formPage } from "./sign-page.js";

const MAX_PDF = 20 * 1024 * 1024;
const MAX_SIGNERS = 20;
const MAX_FIELDS = 500;
const TTL = 120 * 24 * 3600;
const FORM_TTL = 365 * 24 * 3600;
const LONG_TTL = 3650 * 24 * 3600;
const MAX_LOGO = 100 * 1024;
const MAX_RESPONSES = 5000;
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
  if (req.method === "GET" && p[0] === "f" && p[1]) return html(formPage(p[1], origin));
  if (p[0] === "w" && p[1] === "form" && p[2]) return formPublicRoutes(req, env, p[2], p[3] || "", origin);
  if (req.method === "GET" && p[0] === "logo" && p[1]) return logo(env, p[1]);

  if (env.APP_TOKEN && req.headers.get("X-App-Token") !== env.APP_TOKEN) return json({ error: "unauthorized" }, 401);
  if (p[0] === "sign") return signerRoutes(req, env, p, url, origin, "app");
  if (p[0] === "forms") return formRoutes(req, env, p, origin);
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
  const br = brandIn(b.brand);
  const meta = {
    id, ownerKey, brand: br.brand, title: str(b.title, 120) || "طلب توقيع", sender: str(b.sender, 80), senderEmail: str(b.senderEmail, 120),
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
  if (br.logo) await putLogo(env, id, br, TTL);
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
  await env.ESIGN.delete(`logo:${meta.id}`);
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

// ---------------------------------------------------------------- online forms

async function formRoutes(req, env, p, origin) {
  if (p.length === 1 && req.method === "POST") return formCreate(req, env);
  const meta = await loadForm(env, p[1]);
  if (req.headers.get("X-Owner-Key") !== meta.ownerKey) return json({ error: "forbidden" }, 403);
  const sub = p[2] || "";
  if (req.method === "PUT" && sub === "file") {
    if (meta.status !== "draft") throw new HttpError(409, "already published");
    const pdf = await req.arrayBuffer();
    if (pdf.byteLength === 0 || pdf.byteLength > MAX_PDF) throw new HttpError(413, "bad file size");
    if (String.fromCharCode(...new Uint8Array(pdf, 0, 5)) !== "%PDF-") throw new HttpError(400, "not a pdf");
    await putFile(env, `form:${meta.id}`, pdf, FORM_TTL);
    meta.originalSha = await sha256(pdf);
    meta.status = "active";
    await env.ESIGN.put(`sha:${meta.originalSha}`, `form:${meta.id}`, { expirationTtl: FORM_TTL });
    await saveForm(env, meta);
    return json({ sha: meta.originalSha, link: formLink(origin, meta.id) });
  }
  if (req.method === "GET" && sub === "") return json(formOwnerView(meta, origin));
  if (req.method === "GET" && sub === "submissions" && !p[3]) return json({ submissions: await formList(env, meta.id) });
  if (sub === "submissions" && p[3]) {
    const sid = p[3];
    if (!/^[0-9a-z]{20}$/.test(sid)) throw new HttpError(404, "not found");
    const key = `fsub:${meta.id}:${sid}`;
    const s = await env.ESIGN.get(key, "json");
    if (!s) throw new HttpError(404, "not found");
    if (req.method === "GET" && !p[4]) return json({ sid, name: s.name, email: s.email, time: s.time, values: s.values || {}, event: s.event || null, finalSha: s.finalSha || "" });
    if (req.method === "PUT" && p[4] === "final") {
      const pdf = await req.arrayBuffer();
      if (pdf.byteLength === 0 || pdf.byteLength > MAX_PDF * 2) throw new HttpError(413, "bad file size");
      const sha = await sha256(pdf);
      if (s.finalSha && s.finalSha !== sha) await env.ESIGN.delete(`sha:${s.finalSha}`);
      s.finalSha = sha;
      await env.ESIGN.put(`sha:${sha}`, `form:${meta.id}:${sid}`, { expirationTtl: LONG_TTL });
      await env.ESIGN.put(key, JSON.stringify(s), { expirationTtl: LONG_TTL, metadata: subMeta(s) });
      return json({ sha });
    }
  }
  if (req.method === "POST" && (sub === "disable" || sub === "enable")) {
    if (meta.status === "draft") throw new HttpError(409, "not published");
    meta.status = sub === "disable" ? "disabled" : "active";
    await saveForm(env, meta);
    return json(formOwnerView(meta, origin));
  }
  if (req.method === "DELETE" && sub === "") {
    let cursor;
    do {
      const page = await env.ESIGN.list({ prefix: `fsub:${meta.id}:`, cursor });
      for (const k of page.keys) {
        const f = k.metadata && k.metadata.finalSha;
        if (f) await env.ESIGN.delete(`sha:${f}`);
        await env.ESIGN.delete(k.name);
      }
      cursor = page.list_complete ? undefined : page.cursor;
    } while (cursor);
    await delFile(env, `form:${meta.id}`);
    if (meta.originalSha) await env.ESIGN.delete(`sha:${meta.originalSha}`);
    await env.ESIGN.delete(`logo:${meta.id}`);
    await env.ESIGN.delete(`form:${meta.id}`);
    return json({ ok: true });
  }
  return json({ error: "not found" }, 404);
}

async function formCreate(req, env) {
  const b = await req.json();
  const fields = (Array.isArray(b.fields) ? b.fields : []);
  if (fields.length === 0 || fields.length > MAX_FIELDS) throw new HttpError(400, "1-500 fields");
  const id = rnd(12), ownerKey = rnd(32);
  const br = brandIn(b.brand);
  const meta = {
    id, ownerKey, kind: "form", brand: br.brand, title: str(b.title, 120) || "نموذج توقيع", sender: str(b.sender, 80), senderEmail: str(b.senderEmail, 120),
    message: str(b.message, 1000), pageCount: b.pageCount | 0, created: Date.now(), status: "draft", originalSha: "", count: 0,
    maxResponses: Math.min(Math.max(b.maxResponses | 0 || 1000, 1), MAX_RESPONSES), requireEmail: !!b.requireEmail, notify: !!b.notify,
    fields: fields.map((f) => ({
      id: str(f.id, 40), signer: 0, page: f.page | 0, x: num(f.x), y: num(f.y), w: num(f.w), h: num(f.h),
      type: ["signature", "initials", "date", "text"].includes(f.type) ? f.type : "signature", required: f.required !== false, label: str(f.label, 60),
    })),
  };
  if (br.logo) await putLogo(env, id, br, FORM_TTL);
  await saveForm(env, meta);
  return json({ id, ownerKey });
}

function formState(meta) {
  if (meta.status === "draft") return { open: false, reason: "draft" };
  if (meta.status === "disabled") return { open: false, reason: "disabled" };
  if (meta.count >= meta.maxResponses) return { open: false, reason: "full" };
  return { open: true, reason: "" };
}

function formOwnerView(meta, origin) {
  const { ownerKey, ...rest } = meta;
  return { ...rest, link: meta.status === "draft" ? "" : formLink(origin, meta.id), state: formState(meta).reason || "active" };
}

async function formList(env, id) {
  const out = [];
  let cursor;
  do {
    const page = await env.ESIGN.list({ prefix: `fsub:${id}:`, cursor });
    for (const k of page.keys) {
      const m = k.metadata || {};
      out.push({ sid: k.name.split(":")[2], name: m.name || "", email: m.email || "", time: m.time || 0, finalSha: m.finalSha || "" });
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  return out.sort((a, b) => a.time - b.time);
}

function subMeta(s) { return { name: s.name, email: s.email, time: s.time, finalSha: s.finalSha || "" }; }

async function formPublicRoutes(req, env, id, sub, origin) {
  const meta = await loadForm(env, id);
  const st = formState(meta);
  if (req.method === "GET" && sub === "") {
    const { ownerKey, senderEmail, count, notify, ...view } = meta;
    return json({ form: view, open: st.open, reason: st.reason });
  }
  if (req.method === "GET" && sub === "file") {
    if (meta.status === "draft") throw new HttpError(404, "file missing");
    const body = await getFile(env, `form:${meta.id}`);
    if (!body) throw new HttpError(404, "file missing");
    return new Response(body, { headers: { "Content-Type": "application/pdf", ...cors() } });
  }
  if (req.method === "POST" && sub === "submit") {
    if (!st.open) throw new HttpError(410, st.reason);
    const b = await req.json().catch(() => ({}));
    if (b.consent !== true) throw new HttpError(400, "consent required");
    const name = str(b.name, 80).trim();
    const email = str(b.email, 120).trim();
    if (!name) throw new HttpError(400, "name required");
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new HttpError(400, "bad email");
    if (meta.requireEmail && !email) throw new HttpError(400, "email required");
    const values = {};
    let total = 0;
    for (const f of meta.fields) {
      const v = b.values && typeof b.values[f.id] === "string" ? b.values[f.id] : "";
      if (f.required && !v) throw new HttpError(400, `missing field ${f.id}`);
      if (v.length > 400 * 1024) throw new HttpError(413, "value too large");
      total += v.length;
      if (v) values[f.id] = v;
    }
    if (total > 2 * 1024 * 1024) throw new HttpError(413, "values too large");
    const ip = req.headers.get("CF-Connecting-IP") || "";
    const ua = (req.headers.get("User-Agent") || "").slice(0, 300);
    const time = Date.now();
    const sid = time.toString(36).padStart(10, "0") + rnd(10);
    const s = { name, email, time, values, title: meta.title, sender: meta.sender, created: meta.created, originalSha: meta.originalSha,
      event: { time, ip, ua, device: str(b.device, 120), channel: "web" }, finalSha: "" };
    await env.ESIGN.put(`fsub:${meta.id}:${sid}`, JSON.stringify(s), { expirationTtl: FORM_TTL, metadata: subMeta(s) });
    meta.count = (meta.count | 0) + 1;
    await saveForm(env, meta);
    if (meta.notify) await mailOwner(env, meta, `ردّ جديد على «${meta.title}»: ${name}`);
    return json({ ok: true, sid });
  }
  return json({ error: "not found" }, 404);
}

async function verifyForm(env, sha, parts) {
  const [, id, sid] = parts;
  if (!sid) {
    const meta = await env.ESIGN.get(`form:${id}`, "json");
    if (!meta) return json({ error: "unknown" }, 404);
    return json({ id: meta.id, title: meta.title, sender: meta.sender, created: meta.created, status: meta.status, kind: "original",
      originalSha: meta.originalSha, finalSha: "", signers: [] });
  }
  const s = await env.ESIGN.get(`fsub:${id}:${sid}`, "json");
  if (!s) return json({ error: "unknown" }, 404);
  return json({ id: `${id}-${sid}`, title: s.title, sender: s.sender, created: s.created, status: "finalized", kind: sha === s.finalSha ? "final" : "original",
    originalSha: s.originalSha, finalSha: s.finalSha, signers: [{ name: s.name, status: "signed", signedAt: s.time }] });
}

async function loadForm(env, id) {
  if (!/^[a-z0-9]{12}$/.test(id || "")) throw new HttpError(404, "not found");
  const meta = await env.ESIGN.get(`form:${id}`, "json");
  if (!meta) throw new HttpError(404, "not found");
  return meta;
}
async function saveForm(env, meta) { await env.ESIGN.put(`form:${meta.id}`, JSON.stringify(meta), { expirationTtl: FORM_TTL }); }
function formLink(origin, id) { return `${origin}/f/${id}`; }

// ---------------------------------------------------------------- branding

// {brand:{name,color,logo:bool,logoType}, logo: Uint8Array|null}. A bad colour is dropped; a bad logo is a 400.
export function brandIn(b) {
  const o = b && typeof b === "object" ? b : {};
  const color = typeof o.color === "string" && /^#[0-9a-fA-F]{6}$/.test(o.color) ? o.color.toLowerCase() : "";
  let logo = null, logoType = "";
  if (typeof o.logo === "string" && o.logo) {
    let bytes;
    try { bytes = Uint8Array.from(atob(o.logo.replace(/^data:[^,]*,/, "")), (c) => c.charCodeAt(0)); } catch (e) { throw new HttpError(400, "bad logo"); }
    if (bytes.length > MAX_LOGO) throw new HttpError(413, "logo too large");
    logoType = imageType(bytes);
    if (!logoType) throw new HttpError(400, "logo must be PNG, JPEG or WebP");
    logo = bytes;
  }
  return { brand: { name: str(o.name, 80), color, logo: !!logo, logoType }, logo };
}

export function imageType(u) {
  if (u.length > 8 && u[0] === 0x89 && u[1] === 0x50 && u[2] === 0x4e && u[3] === 0x47) return "image/png";
  if (u.length > 3 && u[0] === 0xff && u[1] === 0xd8 && u[2] === 0xff) return "image/jpeg";
  if (u.length > 12 && String.fromCharCode(...u.slice(0, 4)) === "RIFF" && String.fromCharCode(...u.slice(8, 12)) === "WEBP") return "image/webp";
  return "";
}

// Text colour readable on [hex] (same rule as EsignBrand.onColor in the app).
export function ink(hex) {
  const m = /^#([0-9a-f]{6})$/i.exec(hex || "");
  if (!m) return "#ffffff";
  const n = parseInt(m[1], 16);
  const lin = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
  const L = 0.2126 * lin((n >> 16) & 255) + 0.7152 * lin((n >> 8) & 255) + 0.0722 * lin(n & 255);
  return L > 0.179 ? "#111111" : "#ffffff";
}

async function putLogo(env, id, br, ttl) { await env.ESIGN.put(`logo:${id}`, br.logo, { expirationTtl: ttl, metadata: { type: br.brand.logoType } }); }

async function logo(env, id) {
  if (!/^[a-z0-9]{12}$/.test(id)) return json({ error: "not found" }, 404);
  const r = await env.ESIGN.getWithMetadata(`logo:${id}`, "arrayBuffer");
  if (!r || !r.value) return json({ error: "not found" }, 404);
  return new Response(r.value, { headers: { "Content-Type": (r.metadata && r.metadata.type) || "image/png", "Cache-Control": "public, max-age=86400", ...cors() } });
}

// ---------------------------------------------------------------- verify

async function verify(env, sha) {
  if (!/^[0-9a-f]{64}$/.test(sha)) return json({ error: "bad hash" }, 400);
  const id = await env.ESIGN.get(`sha:${sha}`);
  if (!id) return json({ error: "unknown" }, 404);
  if (id.startsWith("form:")) return verifyForm(env, sha, id.split(":"));
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
  const b = meta.brand || {};
  const color = b.color || "#2563eb";
  const head = b.logo || b.name ? `<p>${b.logo ? `<img src="${origin}/logo/${meta.id}" alt="" style="max-height:48px;max-width:200px;vertical-align:middle">` : ""}
${b.name ? `<b style="color:${color};margin:0 8px">${esc(b.name)}</b>` : ""}</p>` : "";
  const body = `<div dir="rtl" style="font-family:Tahoma,Arial">${head}
<p>${esc(reminder ? "تذكير: " : "")}${esc(b.name || meta.sender || "مرسل")} يطلب توقيعك على «${esc(meta.title)}».</p>
${meta.message ? `<p>${esc(meta.message)}</p>` : ""}
<p><a href="${l}" style="background:${color};color:${ink(color)};padding:10px 20px;border-radius:10px;text-decoration:none">مراجعة المستند وتوقيعه</a></p>
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
async function putFile(env, key, buf, ttl = TTL) {
  if (env.FILES) await env.FILES.put(`esign/${key}`, buf, { httpMetadata: { contentType: "application/pdf" } });
  else await env.ESIGN.put(`file:${key}`, buf, { expirationTtl: ttl });
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
