// SmartDocs error server: the app's error centre POSTs errors here; each distinct error (by id) becomes one GitHub
// issue labelled "app-error"; repeats and new versions add a comment. The GitHub token never leaves this worker.
//
// Secrets: GITHUB_TOKEN (fine-grained, this repo only, Issues: read & write), APP_TOKEN (same value as errorsToken in
// the app's cloud.properties). Vars: GITHUB_REPO = "owner/repo". KV binding ERRORS (optional): issue numbers by
// error id and a per-IP daily limit.

const MAX_PER_REQUEST = 20;
const DAILY_PER_IP = 100;

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "GET" && url.pathname === "/") return json({ ok: true, service: "smartdocs-errors" });
    if (req.method !== "POST" || url.pathname !== "/report") return json({ error: "not found" }, 404);
    if (!env.APP_TOKEN || req.headers.get("X-App-Token") !== env.APP_TOKEN) return json({ error: "unauthorized" }, 401);

    const ip = req.headers.get("CF-Connecting-IP") || "?";
    if (env.ERRORS) {
      const key = `rate:${ip}:${new Date().toISOString().slice(0, 10)}`;
      const n = parseInt((await env.ERRORS.get(key)) || "0", 10);
      if (n >= DAILY_PER_IP) return json({ error: "rate limited" }, 429);
      await env.ERRORS.put(key, String(n + 1), { expirationTtl: 172800 });
    }

    let body;
    try { body = await req.json(); } catch { return json({ error: "bad json" }, 400); }
    const errors = Array.isArray(body.errors) ? body.errors.slice(0, MAX_PER_REQUEST) : [];
    const results = [];
    for (const e of errors) {
      try { results.push(await fileError(env, body, e)); }
      catch (x) { results.push({ id: e.id, error: String(x.message || x) }); }
    }
    return json({ ok: true, results });
  },
};

async function fileError(env, meta, e) {
  const id = clean(e.id, 40);
  if (!/^[0-9a-f]{6,40}$/.test(id)) throw new Error("bad id");
  const title = `[${clean(e.kind, 10)}] ${clean(e.title, 140)} (err:${id})`;
  const info = [
    `**Area:** ${clean(e.area, 60)}  ·  **Count:** ${Number(e.count) || 1}  ·  **Error version:** ${clean(e.version, 40)}`,
    `**App:** ${clean(meta.app, 80)} ${clean(meta.version, 40)}  ·  **Device:** ${clean(meta.device, 120)}`,
    `**First / last:** ${iso(e.firstAt)} → ${iso(e.lastAt)}`,
  ].join("\n");
  const trace = "```\n" + clean(e.detail, 6000).replace(/```/g, "ˋˋˋ") + "\n```";

  let issue = env.ERRORS ? parseInt((await env.ERRORS.get(`issue:${id}`)) || "0", 10) : 0;
  if (!issue) issue = await findIssue(env, id);
  if (issue) {
    await gh(env, `/issues/${issue}/comments`, { body: `Seen again.\n\n${info}` });
    // a closed (fixed) error that comes back is reopened
    await gh(env, `/issues/${issue}`, { state: "open" }, "PATCH").catch(() => {});
    return { id, issue, action: "commented" };
  }
  const created = await gh(env, "/issues", { title, body: `${info}\n\n${trace}\n\n_Sent automatically by the app's error centre._`, labels: ["app-error"] });
  if (env.ERRORS) await env.ERRORS.put(`issue:${id}`, String(created.number));
  return { id, issue: created.number, action: "created" };
}

async function findIssue(env, id) {
  const q = encodeURIComponent(`repo:${env.GITHUB_REPO} is:issue "err:${id}" in:title`);
  const r = await fetch(`https://api.github.com/search/issues?q=${q}`, { headers: headers(env) });
  if (!r.ok) return 0;
  const j = await r.json();
  return j.items && j.items.length ? j.items[0].number : 0;
}

async function gh(env, path, payload, method = "POST") {
  const r = await fetch(`https://api.github.com/repos/${env.GITHUB_REPO}${path}`, { method, headers: headers(env), body: JSON.stringify(payload) });
  if (!r.ok) throw new Error(`GitHub ${r.status}`);
  return r.json();
}

function headers(env) {
  return { Authorization: `Bearer ${env.GITHUB_TOKEN}`, Accept: "application/vnd.github+json", "User-Agent": "smartdocs-errors", "Content-Type": "application/json" };
}
function clean(s, max) { return String(s ?? "").replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, "").slice(0, max); }
function iso(t) { const n = Number(t); return n > 0 ? new Date(n).toISOString().replace("T", " ").slice(0, 16) : "?"; }
function json(o, status = 200) { return new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json" } }); }
