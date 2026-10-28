// SmartDocs connected documents — Cloudflare Worker.
//
// KV namespace DOCS: doc metadata, readers, versions. Optional R2 bucket FILES for version PDFs (else KV, <= 20 MB).
// Optional secret APP_TOKEN (checked on every call: X-App-Token).
//
// Owner (X-Owner-Key):
//   POST   /docs                      {title, owner, protected, requireApproval, offlineDays} -> {id, ownerKey, key?}
//   PUT    /docs/:id/versions?sha=&size=&note=   body = PDF or empty (hash-only version) -> {n}
//   GET    /docs/:id                  -> meta + versions + readers + opens
//   POST   /docs/:id/readers/:rid     {status: allowed|blocked}
//   POST   /docs/:id/settings         {requireApproval, offlineDays, revoked, openMessage?}  (openMessage: shown to readers on open)
//   POST   /docs/:id/revoke-all       every reader blocked, document revoked (protected copies stop opening after their offline period)
//   DELETE /docs/:id
// Reader:
//   POST   /open/:id                  {rid, name, sha} -> {status ok|pending|blocked|revoked, latest, isLatest, key?, offlineDays, title, owner, message}
//   POST   /open/:id/request          {rid, name, message}  (access request when approval is required / after a block)
//   GET    /open/:id/latest?rid=      the newest version PDF (allowed readers only)

const MAX_PDF = 20 * 1024 * 1024;
const TTL = 3650 * 24 * 3600;
const MAX_READERS = 2000;

export default {
  async fetch(req, env) {
    try { return await route(req, env); } catch (e) { return json({ error: String((e && e.message) || e) }, (e && e.status) || 500); }
  },
};

class HttpError extends Error { constructor(s, m) { super(m); this.status = s; } }

async function route(req, env) {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors() });
  if (env.APP_TOKEN && req.headers.get("X-App-Token") !== env.APP_TOKEN) return json({ error: "unauthorized" }, 401);
  const url = new URL(req.url);
  const p = url.pathname.split("/").filter(Boolean);
  if (p[0] === "docs" && p.length === 1 && req.method === "POST") return create(req, env);
  if (p[0] === "health") return json({ ok: true });
  const id = p[1];
  const meta = await load(env, id);
  if (p[0] === "open") return reader(req, env, meta, p[2] || "", url);
  if (p[0] !== "docs") return json({ error: "not found" }, 404);
  if (req.headers.get("X-Owner-Key") !== meta.ownerKey) return json({ error: "forbidden" }, 403);
  const sub = p[2] || "";
  if (req.method === "PUT" && sub === "versions") return addVersion(req, env, meta, url);
  if (req.method === "GET" && sub === "") return json(ownerView(meta));
  if (req.method === "POST" && sub === "readers" && p[3]) {
    const b = await req.json().catch(() => ({}));
    const r = meta.readers[decodeURIComponent(p[3])];
    if (!r) throw new HttpError(404, "no such reader");
    r.status = b.status === "blocked" ? "blocked" : "allowed";
    await save(env, meta); return json({ ok: true });
  }
  if (req.method === "POST" && sub === "settings") {
    const b = await req.json().catch(() => ({}));
    meta.requireApproval = !!b.requireApproval;
    meta.offlineDays = clampInt(b.offlineDays, 0, 365, meta.offlineDays);
    if (b.revoked === false && meta.revoked) meta.revoked = false;
    if (typeof b.openMessage === "string") meta.openMessage = str(b.openMessage, 500);
    await save(env, meta); return json({ ok: true });
  }
  if (req.method === "POST" && sub === "revoke-all") {
    meta.revoked = true;
    for (const r of Object.values(meta.readers)) r.status = "blocked";
    await save(env, meta); return json({ ok: true });
  }
  if (req.method === "DELETE" && sub === "") {
    for (const v of meta.versions) if (v.hasFile) await delFile(env, `${meta.id}/${v.n}`);
    await env.DOCS.delete(`doc:${meta.id}`);
    return json({ ok: true });
  }
  return json({ error: "not found" }, 404);
}

async function create(req, env) {
  const b = await req.json().catch(() => ({}));
  const id = rnd(12);
  const meta = {
    id, ownerKey: rnd(32), title: str(b.title, 120), owner: str(b.owner, 80), protected: !!b.protected,
    key: b.protected ? hex(24) : "", requireApproval: !!b.requireApproval, offlineDays: clampInt(b.offlineDays, 0, 365, 7),
    revoked: false, created: Date.now(), opens: 0, versions: [], readers: {}, openMessage: str(b.openMessage || "", 500),
  };
  await save(env, meta);
  return json({ id, ownerKey: meta.ownerKey, key: meta.key || undefined });
}

async function addVersion(req, env, meta, url) {
  const sha = (url.searchParams.get("sha") || "").toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(sha)) throw new HttpError(400, "bad sha");
  const body = await req.arrayBuffer();
  if (body.byteLength > MAX_PDF) throw new HttpError(413, "file too large");
  const n = (meta.versions.length ? meta.versions[meta.versions.length - 1].n : 0) + 1;
  const hasFile = body.byteLength > 0;
  if (hasFile) await putFile(env, `${meta.id}/${n}`, body);
  meta.versions.push({ n, sha, size: Number(url.searchParams.get("size")) || body.byteLength, note: str(url.searchParams.get("note") || "", 300), time: Date.now(), hasFile });
  // keep files of the last 5 versions only
  for (const v of meta.versions.slice(0, -5)) if (v.hasFile) { await delFile(env, `${meta.id}/${v.n}`); v.hasFile = false; }
  await save(env, meta);
  return json({ n });
}

function ownerView(meta) {
  const { ownerKey, key, readers, ...rest } = meta;
  return { ...rest, readers: Object.entries(readers).map(([rid, r]) => ({ rid, ...r })).sort((a, b) => b.last - a.last) };
}

async function reader(req, env, meta, sub, url) {
  if (req.method === "POST" && sub === "") {
    const b = await req.json().catch(() => ({}));
    const rid = str(b.rid, 64);
    if (!/^[A-Za-z0-9-]{8,64}$/.test(rid)) throw new HttpError(400, "bad reader id");
    let r = meta.readers[rid];
    const now = Date.now();
    if (!r) {
      if (Object.keys(meta.readers).length >= MAX_READERS) throw new HttpError(429, "too many readers");
      r = meta.readers[rid] = { name: str(b.name, 80), opens: 0, first: now, last: now, status: meta.requireApproval ? "pending" : "allowed", message: "" };
    }
    if (b.name) r.name = str(b.name, 80);
    r.opens++; r.last = now; meta.opens++;
    await save(env, meta);
    const latest = meta.versions[meta.versions.length - 1] || null;
    const sha = str(b.sha, 64).toLowerCase();
    const isLatest = !latest || !sha || latest.sha === sha;
    const status = meta.revoked ? "revoked" : r.status === "allowed" ? "ok" : r.status;
    return json({
      status, latest, isLatest, offlineDays: meta.offlineDays, title: meta.title, owner: meta.owner, message: meta.openMessage || "",
      key: status === "ok" && meta.protected ? meta.key : undefined,
    });
  }
  if (req.method === "POST" && sub === "request") {
    const b = await req.json().catch(() => ({}));
    const r = meta.readers[str(b.rid, 64)];
    if (!r) throw new HttpError(404, "open the document first");
    if (r.status !== "allowed") r.status = "pending";
    r.message = str(b.message, 300);
    await save(env, meta); return json({ ok: true });
  }
  if (req.method === "GET" && sub === "latest") {
    const r = meta.readers[url.searchParams.get("rid") || ""];
    if (meta.revoked || !r || r.status !== "allowed") throw new HttpError(403, "not allowed");
    const v = [...meta.versions].reverse().find((x) => x.hasFile);
    if (!v) throw new HttpError(404, "no downloadable version");
    const body = await getFile(env, `${meta.id}/${v.n}`);
    if (!body) throw new HttpError(404, "file missing");
    return new Response(body, { headers: { "Content-Type": "application/pdf", ...cors() } });
  }
  return json({ error: "not found" }, 404);
}

async function load(env, id) {
  if (!/^[a-z0-9]{12}$/.test(id || "")) throw new HttpError(404, "not found");
  const m = await env.DOCS.get(`doc:${id}`, "json");
  if (!m) throw new HttpError(404, "not found");
  return m;
}
async function save(env, meta) { await env.DOCS.put(`doc:${meta.id}`, JSON.stringify(meta), { expirationTtl: TTL }); }
async function putFile(env, k, b) { if (env.FILES) await env.FILES.put(`connected/${k}`, b); else await env.DOCS.put(`file:${k}`, b, { expirationTtl: TTL }); }
async function getFile(env, k) { return env.FILES ? (await env.FILES.get(`connected/${k}`))?.body : env.DOCS.get(`file:${k}`, "arrayBuffer"); }
async function delFile(env, k) { if (env.FILES) await env.FILES.delete(`connected/${k}`); else await env.DOCS.delete(`file:${k}`); }
function rnd(n) { const a = "abcdefghijklmnopqrstuvwxyz0123456789"; return Array.from(crypto.getRandomValues(new Uint8Array(n)), (x) => a[x % a.length]).join(""); }
function hex(n) { return [...crypto.getRandomValues(new Uint8Array(n))].map((b) => b.toString(16).padStart(2, "0")).join(""); }
function str(v, m) { return typeof v === "string" ? v.slice(0, m) : ""; }
function clampInt(v, lo, hi, d) { const n = parseInt(v, 10); return Number.isFinite(n) ? Math.min(Math.max(n, lo), hi) : d; }
function cors() { return { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "*", "Access-Control-Allow-Methods": "GET,POST,PUT,DELETE" }; }
function json(o, s = 200) { return new Response(JSON.stringify(o), { status: s, headers: { "Content-Type": "application/json; charset=utf-8", ...cors() } }); }
