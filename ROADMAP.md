# aria2ng-next — Roadmap

Kotlin + KMP rewrite of the Aria2App fork. Embedded aria2-next engine,
in-app downloader only (no remote profiles). Old Java app stays as the
working reference until this reaches parity.

## Architecture

```
shared/       KMP library — RPC client, models, EngineSpec (settings→cmdline)
androidApp/   Jetpack Compose app — EngineProcess, Service (later), UI
```

Design contract carried over from the legacy fixes:
- Single source of truth: `EngineSpec` fields → forced cmdline params;
  the client resolves its endpoint from the same fields. No baked copies.
- Custom options merge into the cmdline but lose to connectivity params.
- Deterministic defaults: port 6802, token `gbc` (configurable).
  6802 avoids colliding with the legacy app's 6801 when both are installed.

## Milestones

### M1 — Skeleton (engine + RPC handshake)
- [x] KMP project scaffold (shared + androidApp, version catalog)
- [x] `Aria2Client`: WebSocket JSON-RPC, id-correlated calls, event flow
- [x] `EngineSpec`: settings → cmdline args, forced-params contract
- [x] `EngineProcess`: exec `nativeLibraryDir/libaria2c.so`, stdout log stream
- [x] `getVersion` verified on real device over `ws://localhost:6801/jsonrpc` (aria2-next 3.1.1)

### M2 — Core download UX
- [x] Foreground `EngineService` + notification (process survives backgrounding)
- [x] Download list: `tellActive`/`tellWaiting`/`tellStopped` polling + WS events
- [x] Add download: URI list + magnet via dialog/VIEW/SEND intents (.torrent file picker pending)
- [x] Actions: pause/resume/remove/clear-result (per-download detail page pending)
- [x] DataStore settings repo (write UI lands in M3 settings screen)
- [x] Verified on device: VIEW intent → addUri → download → file in `Android/data/.../downloads`
- [x] Cold-start race fix: `Aria2Client.call` waits for WS connection (10s)

### M3 — Options & config parity
- [x] Settings screen: port/token/dir/listenAll/allowOriginAll/checkCert/session/custom options (DataStore → debounce → engine restart)
- [x] .torrent file picker (OpenDocument → base64 → aria2.addTorrent)
- [x] Speed graph (rolling 60-sample sparkline under top bar)
- [x] Per-download detail dialog (gid/dir/files with per-file progress)
- [x] JDK 21 toolchain (temurin-21 via mise, jvmToolchain(21))
- [x] Per-download & global option editor (full aria2 key catalog + searchable picker)
- [ ] Session resume verified end-to-end (input-file restore — works implicitly)

### M4 — Polish / distribution
- [x] v2rayNG-style drawer nav (half-width) + About dialog + Engine log screen
- [x] i18n: en + zh-rCN string resources (all UI chrome; option labels stay English)
- [x] App icon + splash (adaptive icon + core-splashscreen backport)
- [x] Notification actions (Pause all / Resume all / Stop on the engine notification)
- [x] Global speed limits (dl/ul KB/s → --max-overall-*-limit) + SAF directory picker
- [x] Speed regression — skipped: saturates the link on device, comparison is moot
- [x] Release story: **replace the old Aria2App repo**; release.jks + GitHub
  Actions CI (debug-signing fallback when keystore.properties is absent)

### Later / optional
- [ ] Desktop target via Compose Desktop reusing `shared`
- [ ] Torrent search / peers view / bitfield visualizer (legacy features, only if wanted)

## Status log

- 2026-11-19 — Scaffold created; engine swap knowledge and the settings-desync
  contract migrated from `D:\github\fu2\Aria2App` (commits ee89ac38, 71938c8e).
- 2026-11-19 — **M1 done**: engine spawns, binds 6801, `getVersion` returns
  aria2-next 3.1.1 over KMP WebSocket client. Verified on arm64 device.
- 2026-11-20 — Async-boundary hardening pass (full review in CODE_REVIEW.md):
  RPC pending-waiter map, engine start/stop/restart serialization + teardown
  wait, reconnect backoff, UI coroutine error sink, random first-run RPC token,
  dataSync FGS timeout handling, external-intent confirmation dialog.
- 2026-11-20 — Toolchain: AGP 9.5.1 / Gradle 9.9.1 / Kotlin 2.4.21 / Compose
  BOM 2026.09 / compileSdk+targetSdk 37.
- 2026-11-20 — Engine-event notifications (aria2.on\* → 2.5s batch window →
  single notification id + onlyAlertOnce, no drawer spam); selectable RPC
  transport WS/HTTP — HTTP is stateless, polls only, no server push.
- 2026-11-20 — Crash fix: removing a download briefly lists its gid in both a
  live tell\* list and tellStopped → dedupe by gid in refresh().
- 2026-11-20 — **Renamed to aria2ng-next.** Variant packages
  `com.aria2ngnext.debug` / `.release` co-installable (isolated data, default
  RPC ports 6803/6802); release signs with debug key for local iteration.
- 2026-11-20 — **M4 done.** Icon+splash, notification actions, speed limits,
  SAF dir picker. Release story decided: replace old repo; real `release.jks`
  (gitignored, `keystore.properties`) + `.github/workflows/android.yml`.
