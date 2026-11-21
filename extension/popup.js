const $ = (id) => document.getElementById(id);

function fmt(n) {
  n = Number(n) || 0;
  for (const u of ["B", "KB", "MB", "GB", "TB"]) {
    if (n < 1024 || u === "TB") return `${n < 10 && u !== "B" ? n.toFixed(1) : Math.round(n)} ${u}`;
    n /= 1024;
  }
}

async function api(path, method = "GET") {
  const c = await chrome.storage.local.get({ apiBase: "http://127.0.0.1:6902", token: "" });
  const r = await fetch(`${c.apiBase}${path}`, {
    method,
    headers: { Authorization: `Bearer ${c.token}` },
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
  return j;
}

async function load() {
  // setInterval can fire while a slow fetch is still in flight — overlapping
  // loads would interleave DOM writes and duplicate work.
  if (load.inFlight) return;
  load.inFlight = true;
  try {
    await loadInner();
  } finally {
    load.inFlight = false;
  }
}

async function loadInner() {
  $("err").textContent = "";
  let j;
  try {
    j = await api("/tasks");
  } catch (e) {
    $("stat").textContent = "";
    $("tasks").replaceChildren();
    $("err").textContent =
      e.message === "unauthorized"
        ? "Wrong token — check extension options"
        : e.message === "engine_unavailable"
          ? "Engine is stopped — start it in the app"
          : e.message === "engine_starting"
            ? "Engine is starting…"
            : `Unreachable: ${e.message}`;
    return;
  }

  const s = j.stat || {};
  $("stat").textContent =
    `↓ ${fmt(s.downloadSpeed)}/s  ↑ ${fmt(s.uploadSpeed)}/s  ·  ${s.numActive ?? 0} active / ${s.numWaiting ?? 0} waiting`;

  const tasks = j.tasks || [];
  const list = $("tasks");
  list.replaceChildren();
  if (!tasks.length) {
    const empty = document.createElement("div");
    empty.className = "empty";
    empty.textContent = "No tasks";
    list.appendChild(empty);
  }
  // Everything server-supplied goes through textContent, never innerHTML:
  // /tasks crosses a trust boundary (plain HTTP + listenAll → LAN MITM), and
  // this page holds cookies/downloads/storage privileges — an injected
  // <script> in a status/name field would be extension-context XSS.
  for (const t of tasks) {
    const pct = t.totalLength > 0 ? Math.min(100, (100 * t.completedLength) / t.totalLength) : 0;
    const div = document.createElement("div");
    div.className = "task";
    const name = document.createElement("div");
    name.className = "name";
    name.textContent = t.name || t.gid;
    const meta = document.createElement("div");
    meta.className = "meta";
    const info = document.createElement("span");
    info.textContent =
      `${t.status} · ${fmt(t.completedLength)}/${fmt(t.totalLength)}` +
      (t.downloadSpeed ? ` · ↓${fmt(t.downloadSpeed)}/s` : "");
    const ops = document.createElement("span");
    ops.className = "ops";
    meta.append(info, ops);
    const bar = document.createElement("div");
    bar.className = "bar";
    const fill = document.createElement("i");
    fill.style.width = `${pct}%`; // pct is a locally computed number — safe
    bar.appendChild(fill);
    div.append(name, meta, bar);
    const live = ["active", "waiting", "paused"].includes(t.status);
    if (live) {
      const b = document.createElement("button");
      b.textContent = t.status === "paused" ? "▶" : "⏸";
      b.onclick = async () => {
        try {
          await api(`/tasks/${t.gid}/${t.status === "paused" ? "resume" : "pause"}`, "POST");
        } catch (_) { }
        load();
      };
      ops.appendChild(b);
    }
    list.appendChild(div);
  }
}

$("opts").addEventListener("click", (e) => {
  e.preventDefault();
  chrome.runtime.openOptionsPage();
});

load();
setInterval(load, 2000);
