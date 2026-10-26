// SmartDocs licence server — Cloudflare Worker.
//
// Verifies Google Play subscriptions (Play Developer API, service account) and activation codes (HMAC, device limit,
// revocation list), and returns Ed25519-signed licence tokens that the app verifies offline (core/billing/LicenseToken.kt).
//
// Bindings / settings (see README.md):
//   KV        LICENSES                     codes, devices, purchases, revocations, cached Google token
//   secret    SIGNING_KEY                  Ed25519 private key, PKCS#8 base64 (keygen.mjs)
//   var       PUBLIC_KEY                   Ed25519 public key, raw 32 bytes base64 (same as licensePublicKey in the app)
//   secret    CODE_SECRET                  HMAC secret of activation codes (same format as the offline codes)
//   secret    ADMIN_KEY                    header X-Admin-Key for /admin/*
//   secret    PLAY_SERVICE_ACCOUNT         the service-account JSON key (whole file content)
//   secret    RTDN_SECRET                  ?secret= of the Pub/Sub push URL
//   var       PACKAGE_NAME                 com.smartdocs.editor
//   var       MAX_DEVICES (default 2)      devices per activation code
//   var       TOKEN_DAYS (default 10)      validity of a signed token (the app re-checks every 3 days)
//
// Routes
//   POST /activate         {code, device}                              → {token} | 400 malformed/invalid | 403 revoked | 409 device_limit | 410 expired
//   POST /verify-purchase  {packageName, productId, purchaseToken, device} → {active, token?, reason?}
//   POST /check            {device, tokens:[…]}                         → {licenses:[{lid, active, token?, reason?}]}
//   POST /admin/generate   {plan:1|2|3, days, count}                    → {codes:[…]}
//   POST /admin/revoke     {code}            POST /admin/unrevoke {code}
//   POST /admin/devices    {code}                                       → {devices:[…]}     POST /admin/unbind {code, device}
//   POST /rtdn?secret=…    Google Play real-time developer notifications (Pub/Sub push)
//   GET  /health

const DAY = 86400000;
const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const PLAN_NAMES = { 1: "month", 2: "year", 3: "lifetime" };

export default {
  async fetch(req, env) {
    try { return await route(req, env); } catch (e) { return json({ error: "server", detail: String(e && e.message || e) }, 500); }
  },
};

async function route(req, env) {
  const url = new URL(req.url);
  const path = url.pathname.replace(/\/+$/, "");
  if (req.method === "GET" && path === "/health") return json({ ok: true, time: Date.now() });
  if (req.method !== "POST") return json({ error: "not_found" }, 404);
  if (path === "/rtdn") return rtdn(req, env, url);
  let body;
  try { body = await req.json(); } catch { return json({ error: "bad_json" }, 400); }
  if (path === "/activate") return activate(body, env);
  if (path === "/verify-purchase") return verifyPurchase(body, env);
  if (path === "/check") return check(body, env);
  if (path.startsWith("/admin/")) {
    if (!env.ADMIN_KEY || req.headers.get("X-Admin-Key") !== env.ADMIN_KEY) return json({ error: "forbidden" }, 403);
    return admin(path.slice(7), body, env);
  }
  return json({ error: "not_found" }, 404);
}

// ------------------------------------------------------------------ activation codes

export function normalize(code) {
  return String(code || "").toUpperCase().replace(/[\s-]/g, "").replace(/O/g, "0").replace(/[IL]/g, "1").replace(/U/g, "V");
}

function decodeCode(code) {
  const s = normalize(code);
  if (s.length !== 24) return null;
  const out = []; let buffer = 0, bits = 0;
  for (const c of s) {
    const v = ALPHABET.indexOf(c); if (v < 0) return null;
    buffer = ((buffer << 5) | v) & 0xffffff; bits += 5;
    if (bits >= 8) { out.push((buffer >> (bits - 8)) & 0xff); bits -= 8; }
  }
  return out.length === 15 ? new Uint8Array(out) : null;
}

function encodeCode(bytes) {
  let s = "", buffer = 0, bits = 0;
  for (const b of bytes) {
    buffer = ((buffer << 8) | b) & 0xffffff; bits += 8;
    while (bits >= 5) { s += ALPHABET[(buffer >> (bits - 5)) & 31]; bits -= 5; }
  }
  if (bits > 0) s += ALPHABET[(buffer << (5 - bits)) & 31];
  return s.match(/.{1,4}/g).join("-");
}

async function hmac8(secret, data) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, data)).slice(0, 8);
}

/** {plan, until} for a valid code, or {error}. until = 0 for lifetime. */
async function parseCode(code, env) {
  const b = decodeCode(code);
  if (!b) return { error: "malformed" };
  if (!env.CODE_SECRET) return { error: "disabled" };
  const mac = await hmac8(env.CODE_SECRET, b.slice(0, 7));
  let diff = 0; for (let i = 0; i < 8; i++) diff |= mac[i] ^ b[7 + i];
  if (diff !== 0 || !PLAN_NAMES[b[0]]) return { error: "invalid" };
  const days = (b[1] << 8) | b[2];
  const until = days === 0 ? 0 : days * DAY + DAY - 1;
  return { plan: b[0], until };
}

async function codeKey(code) { return "code:" + (await sha256hex(normalize(code))).slice(0, 32); }

async function activate(body, env) {
  const device = String(body.device || "");
  if (!/^[0-9a-f]{16,64}$/.test(device)) return json({ error: "bad_device" }, 400);
  const p = await parseCode(body.code, env);
  if (p.error) return json({ error: p.error }, p.error === "disabled" ? 503 : 400);
  const key = await codeKey(body.code);
  if (await env.LICENSES.get("revoked:" + key)) return json({ error: "revoked" }, 403);
  if (p.until !== 0 && Date.now() > p.until) return json({ error: "expired" }, 410);
  const rec = (await env.LICENSES.get(key, "json")) || { devices: [], first: Date.now(), plan: p.plan, until: p.until };
  const max = parseInt(env.MAX_DEVICES || "2", 10);
  if (!rec.devices.some((d) => d.id === device)) {
    if (rec.devices.length >= max) return json({ error: "device_limit", max }, 409);
    rec.devices.push({ id: device, at: Date.now() });
    await env.LICENSES.put(key, JSON.stringify(rec));
  }
  const token = await sign(env, { lid: "c" + key.slice(5, 29), kind: "code", plan: PLAN_NAMES[p.plan], product: "code:" + PLAN_NAMES[p.plan], dev: device, until: p.until, ck: key });
  return json({ token, plan: PLAN_NAMES[p.plan], until: p.until });
}

// ------------------------------------------------------------------ Google Play

async function googleToken(env) {
  const cached = await env.LICENSES.get("gtoken");
  if (cached) return cached;
  const sa = JSON.parse(env.PLAY_SERVICE_ACCOUNT || "{}");
  if (!sa.client_email || !sa.private_key) throw new Error("PLAY_SERVICE_ACCOUNT not set");
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(new TextEncoder().encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = b64url(new TextEncoder().encode(JSON.stringify({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600,
  })));
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const key = await crypto.subtle.importKey("pkcs8", unb64(pem), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`)));
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=${encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=${header}.${claims}.${b64url(sig)}`,
  });
  const j = await r.json();
  if (!j.access_token) throw new Error("google token: " + JSON.stringify(j).slice(0, 200));
  await env.LICENSES.put("gtoken", j.access_token, { expirationTtl: 3000 });
  return j.access_token;
}

const PLAY = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";

/** {active, expiry, productId, ackPending} from purchases.subscriptionsv2.get */
async function playState(env, pkg, purchaseToken) {
  const t = await googleToken(env);
  const r = await fetch(`${PLAY}/${encodeURIComponent(pkg)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`, { headers: { Authorization: `Bearer ${t}` } });
  if (r.status === 404 || r.status === 410 || r.status === 400) return { active: false, reason: "unknown_purchase" };
  if (!r.ok) throw new Error("play api " + r.status);
  const s = await r.json();
  const item = (s.lineItems || [])[0] || {};
  const expiry = item.expiryTime ? Date.parse(item.expiryTime) : 0;
  const state = s.subscriptionState || "";
  const active = (state === "SUBSCRIPTION_STATE_ACTIVE" || state === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" ||
    (state === "SUBSCRIPTION_STATE_CANCELED" && expiry > Date.now())) && expiry > Date.now();
  return { active, expiry, state, productId: item.productId, ackPending: s.acknowledgementState === "ACKNOWLEDGEMENT_STATE_PENDING" };
}

async function verifyPurchase(body, env) {
  const device = String(body.device || "");
  const pkg = String(body.packageName || "");
  const purchaseToken = String(body.purchaseToken || "");
  const productId = String(body.productId || "");
  if (!/^[0-9a-f]{16,64}$/.test(device) || !purchaseToken || !productId) return json({ error: "bad_request" }, 400);
  if (env.PACKAGE_NAME && pkg !== env.PACKAGE_NAME) return json({ error: "bad_package" }, 400);
  const pkey = "play:" + (await sha256hex(purchaseToken)).slice(0, 40);
  if (await env.LICENSES.get("voided:" + pkey)) return json({ active: false, reason: "revoked" });
  const st = await playState(env, pkg || env.PACKAGE_NAME, purchaseToken);
  if (st.ackPending && st.active) {
    const t = await googleToken(env);
    await fetch(`${PLAY}/${encodeURIComponent(pkg)}/purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`,
      { method: "POST", headers: { Authorization: `Bearer ${t}`, "Content-Type": "application/json" }, body: "{}" }).catch(() => {});
  }
  const rec = (await env.LICENSES.get(pkey, "json")) || { purchaseToken, productId, pkg, devices: [] };
  if (!rec.devices.includes(device)) rec.devices.push(device);
  rec.state = st.state; rec.expiry = st.expiry; rec.checked = Date.now();
  await env.LICENSES.put(pkey, JSON.stringify(rec));
  if (!st.active) return json({ active: false, reason: st.reason || "inactive" });
  const token = await sign(env, { lid: "p" + pkey.slice(5, 29), kind: "play", plan: productId, product: productId, dev: device, until: st.expiry, pk: pkey });
  return json({ active: true, token, until: st.expiry });
}

// ------------------------------------------------------------------ re-check

async function check(body, env) {
  const device = String(body.device || "");
  const out = [];
  for (const tok of (Array.isArray(body.tokens) ? body.tokens : []).slice(0, 5)) {
    const c = await verifyToken(env, String(tok));
    if (!c || c.dev !== device) { out.push({ lid: c && c.lid || "", active: false, reason: "invalid_token" }); continue; }
    if (c.kind === "code" && c.ck) {
      const rec = await env.LICENSES.get(c.ck, "json");
      if (await env.LICENSES.get("revoked:" + c.ck)) { out.push({ lid: c.lid, active: false, reason: "revoked" }); continue; }
      if (!rec || !rec.devices.some((d) => d.id === device)) { out.push({ lid: c.lid, active: false, reason: "unbound" }); continue; }
      if (c.until !== 0 && Date.now() > c.until) { out.push({ lid: c.lid, active: false, reason: "expired" }); continue; }
      const token = await sign(env, { lid: c.lid, kind: "code", plan: c.plan, product: c.product, dev: device, until: c.until, ck: c.ck });
      out.push({ lid: c.lid, active: true, token });
    } else if (c.kind === "play" && c.pk) {
      const rec = await env.LICENSES.get(c.pk, "json");
      if (!rec || await env.LICENSES.get("voided:" + c.pk)) { out.push({ lid: c.lid, active: false, reason: "revoked" }); continue; }
      const st = await playState(env, rec.pkg || env.PACKAGE_NAME, rec.purchaseToken);
      rec.state = st.state; rec.expiry = st.expiry; rec.checked = Date.now();
      await env.LICENSES.put(c.pk, JSON.stringify(rec));
      if (!st.active) { out.push({ lid: c.lid, active: false, reason: st.reason || "inactive" }); continue; }
      const token = await sign(env, { lid: c.lid, kind: "play", plan: c.plan, product: c.product, dev: device, until: st.expiry, pk: c.pk });
      out.push({ lid: c.lid, active: true, token });
    } else out.push({ lid: c.lid, active: false, reason: "invalid_token" });
  }
  return json({ licenses: out });
}

// ------------------------------------------------------------------ admin

async function admin(cmd, body, env) {
  if (cmd === "generate") {
    if (!env.CODE_SECRET) return json({ error: "CODE_SECRET not set" }, 503);
    const plan = parseInt(body.plan, 10);
    if (!PLAN_NAMES[plan]) return json({ error: "plan must be 1 (month), 2 (year) or 3 (lifetime)" }, 400);
    const count = Math.min(Math.max(parseInt(body.count || "1", 10), 1), 500);
    const validity = parseInt(body.days || (plan === 1 ? "31" : "366"), 10);
    const day = plan === 3 ? 0 : Math.floor(Date.now() / DAY) + validity;
    const codes = [];
    for (let i = 0; i < count; i++) {
      const b = new Uint8Array(15);
      b[0] = plan; b[1] = (day >> 8) & 255; b[2] = day & 255;
      b.set(crypto.getRandomValues(new Uint8Array(4)), 3);
      b.set(await hmac8(env.CODE_SECRET, b.slice(0, 7)), 7);
      codes.push(encodeCode(b));
    }
    return json({ codes, plan: PLAN_NAMES[plan], expiresDay: day });
  }
  const key = await codeKey(body.code);
  if (cmd === "revoke") { await env.LICENSES.put("revoked:" + key, String(Date.now())); return json({ ok: true }); }
  if (cmd === "unrevoke") { await env.LICENSES.delete("revoked:" + key); return json({ ok: true }); }
  if (cmd === "devices") {
    const rec = await env.LICENSES.get(key, "json");
    return json({ devices: rec ? rec.devices : [], revoked: !!(await env.LICENSES.get("revoked:" + key)) });
  }
  if (cmd === "unbind") {
    const rec = await env.LICENSES.get(key, "json");
    if (rec) { rec.devices = rec.devices.filter((d) => d.id !== body.device); await env.LICENSES.put(key, JSON.stringify(rec)); }
    return json({ ok: true });
  }
  return json({ error: "not_found" }, 404);
}

// ------------------------------------------------------------------ RTDN (Pub/Sub push)

async function rtdn(req, env, url) {
  if (!env.RTDN_SECRET || url.searchParams.get("secret") !== env.RTDN_SECRET) return json({ error: "forbidden" }, 403);
  const body = await req.json().catch(() => ({}));
  let msg = {};
  try { msg = JSON.parse(new TextDecoder().decode(unb64(body.message && body.message.data || ""))); } catch { return json({ ok: true }); }
  const sub = msg.subscriptionNotification;
  const voided = msg.voidedPurchaseNotification;
  const token = (sub && sub.purchaseToken) || (voided && voided.purchaseToken);
  if (!token) return json({ ok: true });
  const pkey = "play:" + (await sha256hex(token)).slice(0, 40);
  // 12 = REVOKED, 13 = EXPIRED; voided = refund / chargeback
  if (voided || (sub && (sub.notificationType === 12 || sub.notificationType === 13))) {
    await env.LICENSES.put("voided:" + pkey, JSON.stringify({ at: Date.now(), type: voided ? "voided" : sub.notificationType }));
  }
  return json({ ok: true });
}

// ------------------------------------------------------------------ tokens

async function signingKey(env) {
  if (!env.SIGNING_KEY) throw new Error("SIGNING_KEY not set");
  return crypto.subtle.importKey("pkcs8", unb64(env.SIGNING_KEY), { name: "Ed25519" }, false, ["sign"]);
}

export async function sign(env, claims) {
  const now = Date.now();
  const days = parseInt(env.TOKEN_DAYS || "10", 10);
  const payload = { v: 1, active: true, iat: now, exp: now + days * DAY, ...claims };
  const p = b64url(new TextEncoder().encode(JSON.stringify(payload)));
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, await signingKey(env), new TextEncoder().encode(p)));
  return `${p}.${b64url(sig)}`;
}

export async function verifyToken(env, token) {
  const parts = token.split(".");
  if (parts.length !== 2 || !env.PUBLIC_KEY) return null;
  const key = await crypto.subtle.importKey("raw", unb64(env.PUBLIC_KEY), { name: "Ed25519" }, false, ["verify"]);
  const ok = await crypto.subtle.verify({ name: "Ed25519" }, key, unb64(parts[1]), new TextEncoder().encode(parts[0])).catch(() => false);
  if (!ok) return null;
  try { return JSON.parse(new TextDecoder().decode(unb64(parts[0]))); } catch { return null; }
}

// ------------------------------------------------------------------ helpers

async function sha256hex(s) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)));
  return Array.from(d, (x) => x.toString(16).padStart(2, "0")).join("");
}
function b64url(bytes) {
  let s = ""; for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function unb64(s) {
  s = String(s).replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4) s += "=";
  const bin = atob(s); const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
function json(obj, status = 200) { return new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json" } }); }
