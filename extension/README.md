# aria2ng-next Connect

Rayburst-Connect-style browser extension for the aria2ng-next Android app:
intercepts browser downloads and POSTs them to the app's embedded Extension
API. Cookies, referer, UA, filename hint and size travel with the request —
that's the whole point of an extension over an `ACTION_VIEW` intent.

## Architecture

```
browser ──POST /add──▶ app ExtensionApiServer (127.0.0.1:6902)
                        │ Bearer-token auth · CORS * · 256 KiB body cap
                        ▼
                   extPending queue → in-app confirm dialog
                        │ confirmed            │ declined / engine down
                        ▼                      ▼
                aria2.addUri + options    nothing added
```

The server speaks **Rayburst Connect protocol 3** — the store-published
Rayburst Connect extension (Chrome Web Store / Edge Add-ons / Firefox AMO)
works with this app unmodified, which is the easiest install path on Android
where sideloading extensions is restricted. Sniffer/media endpoints
(`media/v2/*`) are not implemented; downloads, popup dashboard, pause/resume
and reveal (opens the file with a viewer) all work.

Endpoints — `GET /ping` and `GET /version` are unauthenticated (same as the
real desktop); everything else requires `Authorization: Bearer <token>`:

- `GET  /ping` → `{"product":"rayburst","status":"ok","version":…}`
- `GET  /version` → `{"app":…,"engine":"running|starting|stopped|failed"}`
- `GET  /downloads/capabilities` → `{"product":"rayburst","protocolVersion":3}`
- `GET  /stat` → counters as decimal strings
- `GET  /tasks` → `{stat, tasks:[…]}` — strings for numbers, queue order + 10 finished
- `POST /add` → `{url, finalUrl?, referer?, cookie?, userAgent?, filename?, filenameSource?, mime?, size?, requestHeaders?, verify?, ack?}`
  → `{action:"accepted"}` (default `ack:"early"`) or `{action:"added"|"cancelled"|"fallback"}` for `ack:"final"`
- `POST /tasks/{gid}/pause|resume|reveal`, `POST /pause-all`, `POST /resume-all` → `{"status":"ok"}`

503 responses carry `{"error":"engine_starting"|"engine_unavailable"}` so the
extension distinguishes retry-from-startup from a stopped engine.

`ack:"early"` = extension drops the browser download once the app has queued
the request; the confirm dialog owns it from there. `ack:"final"` = response
waits up to 120 s for the user's answer; timeout → `{action:"fallback",
reason:"unconfirmed"}` and the browser should keep its download.

`verify:true` is honored: before queueing, `aria2.probe` (aria2-next RPC)
fetches the first byte with the request's own headers/cookie/referer. HTML
where the browser saw a file → `fallback:web-page`; probe transport failure
or >8 s → `fallback:unreachable`. An engine lacking the method just skips
the check. The request's `size` is deliberately *not* compared against
`totalLength` — extensions report bytes-received-so-far, not the payload
total, so that comparison false-positives on every large download.

## Install

### Option A — official Rayburst Connect from a store (recommended on Android)

The API is wire-compatible, so the published extension just works:

1. App → Settings → Browser extension → note port and token.
2. Install **Rayburst Connect** from Edge Add-ons / Chrome Web Store / AMO —
   on Android this means Edge (store-only installs) or Firefox (AMO).
3. Extension → Settings → Connection → host `127.0.0.1`, port `6902`
   (or `6903` for debug builds), secret = the app token.
4. Its popup should show the task dashboard; interception, context menu and
   pause/resume work. Sniffer/media pages report unavailable — that API
   isn't implemented.

### Option B — this folder, unpacked (Kiwi / Lemur / desktop Chromium)

1. In the app: Settings → Browser extension → note the port and token.
2. Copy this `extension/` folder to the device (or clone the repo on it).
3. Browser → `chrome://extensions` → Developer mode → **+ (from .zip/…)** or
   "Load unpacked" → pick `extension/`.
4. Toolbar icon → settings → paste endpoint `http://127.0.0.1:6902` + token →
   **Test connection** should say `OK — rayburst protocol 3`.

Desktop Chromium pointing at the phone over LAN works too — enable
"Listen on all interfaces" in the app's extension settings and set the
endpoint to `http://<phone-ip>:<port>`.

## Differences vs the real Rayburst desktop

- No media sniffer (`media/v2/*` → 404 → extension reports the integration
  unavailable); no native-messaging auto-launch (Android), no task remove,
  no rename.
- `reveal` opens the payload file with a viewer intent — Android has no
  "show in folder".
- Every submission is confirmed in-app; Rayburst's silent-accept mode is
  not offered.
- `aria2.probe` requires the bundled aria2-next engine; verify degrades to
  "no check" if the method is missing.
