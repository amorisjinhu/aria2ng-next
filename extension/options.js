const DEFAULTS = {
  apiBase: "http://127.0.0.1:6902",
  token: "",
  enabled: true,
  intercept: true,
  minBytes: 0,
};

const $ = (id) => document.getElementById(id);
const status = (t) => ($("status").textContent = t);

// Plain-HTTP to a non-loopback host leaks the token (and the site cookies we
// forward) onto the LAN — surface it instead of staying silent.
function refreshWarn() {
  const v = $("apiBase").value.trim();
  const insecure =
    /^http:\/\//i.test(v) && !/^http:\/\/(127\.0\.0\.1|localhost|\[::1\])(:|\/|$)/i.test(v);
  $("warn").textContent = insecure
    ? "Warning: non-local HTTP endpoint — token and forwarded cookies cross the network unencrypted."
    : "";
}

async function load() {
  const c = { ...DEFAULTS, ...(await chrome.storage.local.get(DEFAULTS)) };
  $("apiBase").value = c.apiBase;
  $("token").value = c.token;
  $("minBytes").value = c.minBytes;
  $("enabled").checked = c.enabled;
  $("intercept").checked = c.intercept;
  $("hint").textContent =
    "Point this at the app's Extension API (Settings → Browser extension in aria2ng-next). " +
    "Port defaults: release 6902, debug 6903.";
  refreshWarn();
}

$("apiBase").addEventListener("input", refreshWarn);

$("save").addEventListener("click", async () => {
  await chrome.storage.local.set({
    apiBase: $("apiBase").value.trim().replace(/\/+$/, "") || DEFAULTS.apiBase,
    token: $("token").value.trim(),
    minBytes: Math.max(0, parseInt($("minBytes").value, 10) || 0),
    enabled: $("enabled").checked,
    intercept: $("intercept").checked,
  });
  status("Saved.");
});

$("test").addEventListener("click", async () => {
  status("Testing…");
  try {
    const base = $("apiBase").value.trim().replace(/\/+$/, "") || DEFAULTS.apiBase;
    const r = await fetch(`${base}/downloads/capabilities`, {
      headers: { Authorization: `Bearer ${$("token").value.trim()}` },
    });
    const j = await r.json().catch(() => ({}));
    status(
      r.ok
        ? `OK — ${j.product || "?"} protocol ${j.protocolVersion ?? "?"}`
        : `HTTP ${r.status} ${j.error || ""} — check token / engine running`,
    );
  } catch (e) {
    status(`Unreachable: ${e.message}\nIs the app running and the port correct?`);
  }
});

load();
