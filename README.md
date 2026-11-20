# aria2ng-next (Slipstream)

A personal Android download manager — a Kotlin Multiplatform rewrite in the
spirit of Aria2App, driving a bundled [aria2-next](https://github.com/AnInsomniacy/aria2-next)
engine (`libaria2c.so` as a child process) over JSON-RPC, with a companion
MV3 browser extension that hands browser downloads to the app.

**License status: all rights reserved.** This is a personal project; no
license is granted to the application source code at this time. The bundled
engine is GPLv2+ — see [androidApp/src/main/assets/NOTICE.txt](androidApp/src/main/assets/NOTICE.txt)
for the corresponding-source offer and binary checksum.

## Layout

- `shared/` — platform-independent core: `EngineSpec` (settings → command line),
  `Aria2Client` (id-correlated JSON-RPC over WS/HTTP), `DownloadManager` (live view),
  `Download` model. Android target only today.
- `androidApp/` — Compose UI, `EngineService` (dataSync foreground service, sole
  engine lifecycle owner), `EngineProcess` (spawn/teardown), `ExtensionApiServer`
  (Rayburst protocol-3 compatible HTTP API), settings/history persistence.
- `extension/` — MV3 browser extension (see its own README).

## Build

```bash
./gradlew :androidApp:assembleDebug     # debug variant (package .debug, ports 6803/6903)
./gradlew :androidApp:assembleRelease   # release variant (ports 6802/6902)
./gradlew ktlintCheck testDebugUnitTest # lint + unit-test gate (also run in CI)
```

JDK 21, Android SDK 37. Release signing falls back to the debug key when
`keystore.properties` is absent (local iteration); CI restores the real key
from repository secrets for tag builds.

## Engine provenance

`androidApp/src/main/jniLibs/arm64-v8a/libaria2c.so` is a prebuilt binary of
aria2-next **v3.1.1** (upstream commit `1b8fb72fcf63dc9e78807f20039708184bce0ad2`).
Verify before replacing:

```bash
sha256sum androidApp/src/main/jniLibs/arm64-v8a/libaria2c.so
# 8f06939d8786c06204a099a3b9ff19d76ce063f62781eed155d9f3dbf04ea973
```

## TLS certificate verification

Android has no system CA store for aria2. The Mozilla CA bundle
([cacert.pem](https://curl.se/docs/caextract.html)) is bundled at
`androidApp/src/main/assets/cacert.pem` (Mozilla store as of 2026-11-05,
118 roots, SHA-256 `427c064216761a1e72a8b30f0d954d08cb6e4435a82091fb865afd20c8497285`).
It is extracted at first run, passed as `--ca-certificate`, and the
"Verify TLS certificates" default flips on automatically. To refresh it,
replace the asset with a newer cacert.pem — the extraction re-runs on size
change. Without the bundle the default stays off (turning it on manually
without a CA file breaks every HTTPS download).

## Platform limits worth knowing

- **dataSync foreground services are capped at ~6 h per 24 h** (Android 15+).
  The system stops the engine at the cap; a notification says so, and
  unfinished tasks restore from the 30 s session save when you reopen the app.
- The default download dir is app-private external storage — **not visible**
  via MTP/DocumentsUI on Android 11+. Pick a public directory in Settings
  (needs "All files access" on API 30+, runtime storage permission on 24–28).
- The Extension API listens on loopback by default; enabling "listen on all
  interfaces" exposes it to the LAN — the token then crosses the network in
  plain HTTP. The extension popup warns about non-loopback endpoints.
