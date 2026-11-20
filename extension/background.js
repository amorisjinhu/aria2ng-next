// aria2ng-next Connect — intercept browser downloads and hand them to the
// app's Extension API (POST /add). The app always asks the user to confirm;
// ack:"final" + pausing the item holds the browser's copy until the verdict —
// "accepted" only fires after the user actually confirms.
//
// Fallback contract (same spirit as Rayburst Connect): if the API can't be
// reached, the user declines, or confirmation times out, the browser keeps
// the download — nothing is ever lost silently.

const DEFAULTS = {
  apiBase: "http://127.0.0.1:6902",
  token: "",
  enabled: true,
  intercept: true,
  minBytes: 0,
};

async function cfg() {
  // storage.local, not sync: the token must never leave this device via the
  // browser's account sync channel.
  return { ...DEFAULTS, ...(await chrome.storage.local.get(DEFAULTS)) };
}

function notify(title, message) {
  try {
    // Fixed id: each notification REPLACES the previous one instead of
    // stacking another entry in the OS tray.
    chrome.notifications.create("aria2ng-next", {
      type: "basic",
      iconUrl: "icon128.png",
      title,
      message: String(message).slice(0, 300),
    });
  } catch (_) {
    // notifications permission is nice-to-have
  }
}

async function cookiesFor(url) {
  try {
    const cs = await chrome.cookies.getAll({ url });
    return cs.map((c) => `${c.name}=${c.value}`).join("; ");
  } catch (_) {
    return "";
  }
}

/**
 * POST /add → resolves to the parsed body on HTTP 200
 * ({action:"accepted"|"added"|"cancelled"|"fallback"}), or
 * {action:"fallback", reason} on any transport/auth failure.
 */
async function submit(url, ctx = {}) {
  const c = await cfg();
  if (!c.enabled) return { action: "disabled" };
  // No token configured yet — fail fast with an actionable reason instead of
  // a 401 round-trip per download.
  if (!c.token || !c.token.trim()) return { action: "fallback", reason: "no-token" };

  const body = {
    url,
    ack: "final",
    verify: "true",
    userAgent: navigator.userAgent,
    referer: ctx.referer,
    finalUrl: ctx.finalUrl,
    filename: ctx.filename,
    filenameSource: ctx.filenameSource,
    mime: ctx.mime,
    size: ctx.size,
  };
  const cookie = await cookiesFor(ctx.finalUrl || url);
  if (cookie) body.cookie = cookie;
  for (const k of Object.keys(body)) if (body[k] === undefined) delete body[k];

  // Client-side deadline: the app holds ack:"final" up to 120 s, but a dozing
  // phone can drop TCP silently (no RST) — without this, fetch hangs forever
  // and the browser download stays paused indefinitely. 130 s > app budget,
  // then fall back so the browser keeps the download.
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), 130_000);
  try {
    const r = await fetch(`${c.apiBase}/add`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${c.token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
      signal: ac.signal,
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) return { action: "fallback", reason: j.error || `http-${r.status}` };
    return j;
  } catch (_) {
    return { action: "fallback", reason: "unreachable" };
  } finally {
    clearTimeout(timer);
  }
}

// ---- context menu: right-click any link/media → send to the app ----

const MENU_ID = "aria2ngnext-send";
chrome.runtime.onInstalled.addListener(() => {
  // Fires on updates too — wipe first or create() fails with duplicate id.
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: MENU_ID,
      title: chrome.i18n.getMessage("menuTitle") || "Download with aria2ng-next",
      contexts: ["link", "image", "video", "audio"],
    });
  });
});

chrome.contextMenus.onClicked.addListener(async (info) => {
  const url = info.linkUrl || info.srcUrl;
  if (!url) return;
  const res = await submit(url, { referer: info.pageUrl });
  if (res.action === "accepted" || res.action === "added") {
    notify("aria2ng-next", chrome.i18n.getMessage("sentConfirm") || "Sent — confirm in the app");
  } else if (res.reason === "no-token") {
    notify("aria2ng-next", chrome.i18n.getMessage("noToken") || "Set the app token in the extension options first");
  } else if (res.action !== "disabled") {
    notify("aria2ng-next", `${chrome.i18n.getMessage("sendFailed") || "Send failed"}: ${res.reason || res.action}`);
  }
});

// ---- download interception ----

// onDeterminingFilename fires after the browser resolved its suggested name —
// the right moment to intercept. Pausing the item holds the payload while the
// app waits for the user's verdict; every non-accepted outcome resumes it.
chrome.downloads.onDeterminingFilename.addListener(async (item) => {
  const c = await cfg();
  if (!c.enabled || !c.intercept) return;
  // Unconfigured extension must be invisible: intercepting now would pause
  // the download, 401, and spam a failure notification on EVERY download.
  if (!c.token || !c.token.trim()) return;
  if (item.byExtensionId) return; // our own fallback re-download
  if (!/^https?:/i.test(item.url)) return;
  if (c.minBytes > 0 && item.fileSize > 0 && item.fileSize < c.minBytes) return;

  try {
    await chrome.downloads.pause(item.id);
  } catch (_) {}

  const res = await submit(item.url, {
    finalUrl: item.finalUrl && item.finalUrl !== item.url ? item.finalUrl : undefined,
    referer: item.referrer || undefined,
    mime: item.mime || undefined,
    filename: item.filename ? item.filename.split(/[\\/]/).pop() : undefined,
    filenameSource: item.filename ? "browser" : undefined,
    size: item.fileSize > 0 ? item.fileSize : item.totalBytes > 0 ? item.totalBytes : undefined,
  });

  const resume = async () => {
    try {
      await chrome.downloads.resume(item.id);
    } catch (_) {}
  };

  if (res.action === "accepted" || res.action === "added") {
    // the app owns it now — drop the browser's copy
    try {
      await chrome.downloads.cancel(item.id);
      await chrome.downloads.erase({ id: item.id });
    } catch (_) {}
    notify("aria2ng-next", chrome.i18n.getMessage("sentConfirm") || "Sent — confirm in the app");
  } else if (res.action === "cancelled") {
    // Declined in the app = hand the download BACK to the browser, not delete
    // it from both worlds.
    await resume();
    notify("aria2ng-next", chrome.i18n.getMessage("declinedNote") || "Declined — browser kept the download");
  } else {
    // unreachable/rejected/timed out: browser keeps the download
    await resume();
    notify(
      "aria2ng-next",
      (chrome.i18n.getMessage("fallbackNote") || "App unreachable — browser kept the download") +
        (res.reason ? ` (${res.reason})` : ""),
    );
  }
});
