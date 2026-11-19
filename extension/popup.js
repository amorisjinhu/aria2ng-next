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
  $("err").textContent = "";
  let j;
  try {
    j = await api("/tasks");
  } catch (e) {
    $("stat").textContent = "";
    $("tasks").innerHTML = "";
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
  $("tasks").innerHTML = tasks.length
    ? ""
    : '<div class="empty">No tasks</div>';
  for (const t of tasks) {
    const pct = t.totalLength > 0 ? Math.min(100, (100 * t.completedLength) / t.totalLength) : 0;
    const div = document.createElement("div");
    div.className = "task";
    const live = ["active", "waiting", "paused"].includes(t.status);
    div.innerHTML =
      `<div class="name"></div>` +
      `<div class="meta"><span>${t.status} · ${fmt(t.completedLength)}/${fmt(t.totalLength)}` +
      (t.downloadSpeed ? ` · ↓${fmt(t.downloadSpeed)}/s` : "") +
      `</span><span class="ops"></span></div>` +
      `<div class="bar"><i style="width:${pct}%"></i></div>`;
    div.querySelector(".name").textContent = t.name || t.gid;
    const ops = div.querySelector(".ops");
    if (live) {
      const b = document.createElement("button");
      b.textContent = t.status === "paused" ? "▶" : "⏸";
      b.onclick = async () => {
        try {
          await api(`/tasks/${t.gid}/${t.status === "paused" ? "resume" : "pause"}`, "POST");
        } catch (_) {}
        load();
      };
      ops.appendChild(b);
    }
    $("tasks").appendChild(div);
  }
}

$("opts").addEventListener("click", (e) => {
  e.preventDefault();
  chrome.runtime.openOptionsPage();
});

load();
setInterval(load, 2000);
