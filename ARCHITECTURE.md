# Slipstream 架构文档

面向协作者的维护指南。描述当前代码的实际结构与关键契约，修改代码前请先阅读对应章节的"约束"部分。

- 应用名：aria2ng-next（仓库代号 Slipstream）
- 定位：类 Aria2App 的 Android 下载管理器，Kotlin 全量重写，内嵌 aria2-next 引擎（`libaria2c.so` 子进程），通过 JSON-RPC 驱动；附带浏览器扩展，可把浏览器下载接管到 App。
- 技术栈：Kotlin 2.4 / Kotlin Multiplatform（shared 模块当前仅 Android target）、Jetpack Compose（Material 3）、ktor client（WS/HTTP）、kotlinx.serialization / coroutines、DataStore Preferences、Gradle 9 + AGP 9.5、Java 21。

---

## 1. 模块结构与依赖方向

```
┌────────────────────────────────────────────────────────────┐
│ extension/            MV3 浏览器扩展（纯 JS，无构建步骤）      │
│   background.js / popup.js / options.js                    │
└──────────────────────────┬─────────────────────────────────┘
                           │ HTTP（Rayburst protocol-3 风格）
                           │ 127.0.0.1:6902（release）/6903（debug）
┌──────────────────────────▼─────────────────────────────────┐
│ androidApp/           Android 平台层（Compose UI + 平台服务） │
│   MainActivity / DownloadViewModel / DownloadScreen ...    │
│   EngineService（FGS）/ EngineProcess（进程单例）             │
│   ExtensionApi（内嵌 HTTP 服务）/ SettingsRepo / HistoryStore │
└──────────────────────────┬─────────────────────────────────┘
                           │ 单向依赖（Gradle 强制）
┌──────────────────────────▼─────────────────────────────────┐
│ shared/               平台无关核心（commonMain 仅 4 个文件）   │
│   rpc/Aria2Client.kt      JSON-RPC 客户端（WS + HTTP 双传输） │
│   rpc/DownloadManager.kt  下载列表 live view（轮询+事件刷新）  │
│   model/Download.kt       Download / FileEntry / GlobalStat  │
│   engine/EngineSpec.kt    设置 → aria2 命令行映射             │
└──────────────────────────┬─────────────────────────────────┘
                           │ JSON-RPC（ws:// 或 http://localhost:port/jsonrpc）
┌──────────────────────────▼─────────────────────────────────┐
│ libaria2c.so          aria2-next 子进程（jniLibs/arm64-v8a）  │
└────────────────────────────────────────────────────────────┘
```

约束：
- `androidApp → shared` 单向；shared 内禁止任何 Android framework 引用与反向依赖。
- shared/commonMain 依赖仅 ktor/serialization/coroutines。其中 `java.util.concurrent.ConcurrentHashMap` 与 `kotlin.concurrent.atomics.AtomicLong` 是刻意保留的 JVM 类型（见 §9 决策记录），新增非 Android target 时必须先走 expect/actual 替换。
- UI 与引擎的契约是单向数据流：状态下行只经 `collectAsState()` 读 StateFlow；操作上行只调 `DownloadManager` 的 suspend API（UI 侧经 `DownloadViewModel` 编排）。UI 从不直接触碰 `Aria2Client`（唯一例外：`ExtensionApi.verifyProbe` 调 `aria2.probe`，属协议层而非 UI）。

## 2. 目录地图

```
slipstream/
├── androidApp/src/main/kotlin/com/slipstream/
│   ├── MainActivity.kt        Activity 壳：抽屉导航/intent 路由/AboutDialog
│   ├── DownloadViewModel.kt   列表页 UI 状态 + RPC 编排 + Snackbar 事件流
│   ├── DownloadScreen.kt      列表页 UI（Scaffold/顶栏/FAB/列表）
│   ├── DownloadCard.kt        卡片 + StatusChip + statusLabel
│   ├── DownloadDialogs.kt     添加/详情/删除确认对话框
│   ├── ExtConfirmDialog.kt    ext 队首确认对话框
│   ├── CommonUi.kt            DetailRow + SpeedGraph
│   ├── LogScreen.kt           引擎 stdout 日志页
│   ├── FileOps.kt             openLocalFile / sha256Hex / deleteDownloadFiles
│   ├── SettingsScreen.kt      设置页（引擎 + 扩展 API + OEM 保活深链）
│   ├── OptionsEditor.kt       aria2 选项编辑器（含 OptionsEditorDialog）
│   ├── EngineProcess.kt       引擎进程所有权：启停/teardown/ext 队列门面
│   ├── EngineService.kt       dataSync 前台服务：生命周期所有者 + 通知
│   ├── ExtensionApi.kt        内嵌 HTTP 服务（ExtApiSpec/PendingAdd/路由）
│   ├── SettingsRepo.kt        DataStore 设置仓库 + 首启 token 播种
│   ├── HistoryStore.kt        已完成记录持久化（history.json）
│   ├── Format.kt / Theme.kt   字节/速度/ETA 格式化；主题
├── shared/src/commonMain/kotlin/com/slipstream/   （见上图 4 文件）
├── extension/                 MV3 扩展 + _locales（en/zh_CN）+ README
├── gradle/libs.versions.toml  版本目录（所有依赖版本唯一来源）
└── .github/workflows/android.yml  CI（build + tag 签名发布）
```

## 3. 引擎生命周期

所有权链：**EngineService（FGS）是引擎生命周期的唯一所有者**；EngineProcess 是持有 applicationContext 的进程级单例（`EngineProcess.get(context)`），执行实际的进程管理。UI 启停引擎必须走 `EngineService.start(context)` / `EngineService.stop(context)` 意图，禁止直调 `engine.start()/stop()`——否则会留下空转的 FGS、失真的通知状态，且 `wantRunning=true` 时被 START_STICKY 复活。

### 启动路径（EngineService.onStartCommand 默认分支）

```
startForeground(常驻通知) → wantRunning=true
→ scope.launch {
    repo.ensureDefaults()            // 首启播种随机 RPC/ext token（幂等）
    engine.updateSpec(repo.spec.first())  // 先应用持久化设置，避免 "gbc" 默认 token 首启
    if (wantRunning) engine.start()  // 门控：窗口期内的 stop 意图不被延迟启动撤销
  }
```

### EngineProcess 内部契约

- 状态机：`Stopped → Starting → Running / Failed`，经 `state: StateFlow<State>` 下行。
- `start()` 有双重守卫（`process.isAlive || startJob.isActive`），Starting 期间连点不会 spawn 孪生进程。
- `runStart()` 先 `teardownJob?.join()`——前任进程可能仍占着 RPC 端口（aria2 收 SIGTERM 时保存 session），不 join 会绑定失败。
- teardown 是串行 Job 链：`destroy()`（SIGTERM）→ `waitFor(5s)` → `destroyForcibly()`。SIGTERM 本身同步发出，即使 scope 已死（Service.onDestroy 场景）。
- `stop()` 先取消 `startJob` 再动进程状态，防止启动协程在背后复活引擎；同时 drain ext 队列（对每个未认领的 PendingAdd CAS 认领后 complete `Failed("engine stopped")`，让 ack:"final" 的浏览器调用方落回浏览器下载）。
- `updateSpec(newSpec)`：相等短路；需要重启时**先用旧 client 走 stop()，再换 client**——stop 会延迟 close client，先换会让它杀掉新 client。
- `watchProcess`：aria2 自行退出时置 Failed 并停 manager。
- 启动日志对 `--rpc-secret=` 脱敏为 `***`。

### 停止与超时

- `ACTION_STOP`：wantRunning=false → engine.stop() → stopForeground(REMOVE) → stopSelf()。
- Android 15 dataSync FGS 有 6h/24h 上限：`onTimeout` 两个重载（API 34 单参 / 35+ 双参）都必须 stop+stopSelf，否则 ForegroundServiceDidNotStopInTimeException 崩溃。
- `onDestroy`：engine.stop() + `engine.releaseExtApi()`（显式关 HTTP 监听）+ scope.cancel()。

### 设置应用（EngineService.onCreate 的收集器）

- `repo.spec.debounce(800).collect { engine.updateSpec(it) }`——吸收设置页突发写入，一次重启。收集前必须 `ensureDefaults()`。
- `repo.extSpec.debounce(800).collect { engine.updateExtApi(it) }`——**独立收集器，同样先 ensureDefaults()**（否则首启窗口把空 token 交给服务器，形成 fail-open 鉴权洞）。换 ext 监听绝不重启 aria2。

## 4. RPC 层（shared）

### Aria2Client

- 双传输同一端口：endpoint scheme 决定 WS 或 HTTP POST。HTTP 无服务端推送（`events` 永不发射），WS 有 aria2.on* 事件。
- 响应关联：请求 id 自增（AtomicLong），`pending: ConcurrentHashMap<Long, PendingCall>` **先注册后发送**——SharedFlow 广播会丢掉订阅前到达的响应。每个 waiter 是 CompletableDeferred，10s 超时，finally 清理。
- WS reader 协程：带 `method` 的帧是通知 → `events`（extraBufferCapacity=64）；否则按 id 完成 waiter。断线时 `failPending` 只失败属于旧 session 的 waiter（CAS remove，防止误杀新 session 的调用）。
- 重连不在 client 内：reader 失败由 CoroutineExceptionHandler 吞掉，DownloadManager 轮询发现 `!isConnected` 后 connect() 重试。
- tellWaiting/tellStopped 分页（PAGE_SIZE=100，上限 10000 条），深队列不丢条目。
- 错误统一抛 `Aria2RpcException(code, message)`；所有 suspend API 可抛异常/取消，调用方必须兜底（见 §6 rpcOp 模式）。

### DownloadManager

- live view = 1s 轮询 + 事件驱动刷新：`client.events.collectLatest { refresh() }`（事件风暴折叠为一次刷新）；另一路 `collect` 原样转发事件到 `manager.events` 供通知订阅（两路分离，刷新合并不丢通知事件）。
- `refresh()` 在 `refreshMutex` 内串行：tellActive + tellAllWaiting + tellAllStopped + getGlobalStat；写回前 `ensureActive()` 防 stop() 清空后写回过期快照；结果 `distinctBy { gid }`——aria2 过渡期同一 gid 可能同时出现在 live 与 stopped 列表，LazyColumn 按 gid key 遇重复是硬崩溃。
- WS 断线时轮询 loop 指数退避重连（1s → 15s 封顶）。
- `add()` 返回 gid（String）；所有变更类 API 成功后自动 refresh。

### 模型

- `Download`：gid/status/长度/速度/连接数/dir/name/错误/files；`persisted=true` 表示来自 HistoryStore 而非 live aria2——gid 引擎侧已不存在，只能做文件/记录操作，不能 pause/getOption。
- 名称解析优先级：BT info.name → 首个文件 path basename → 首个 URI basename → gid。
- `GlobalStat`：downloadSpeed/uploadSpeed/numActive/numWaiting/numStopped。

## 5. 设置与持久化

- **SettingsRepo**（DataStore "settings"）：`spec: Flow<EngineSpec>`、`extSpec: Flow<ExtApiSpec>`、`themeMode: Flow<Int>` 三路分离——主题与 ext 设置变更不重启引擎。`save()` 单次 edit 写全字段 → 单次发射 → 单次重启。
- **首启播种**：`ensureDefaults()` 对空/blank 的 RPC_TOKEN 与 EXT_TOKEN 各播种 16 字节 SecureRandom hex。幂等（事务 edit），多协程并发调用安全。已持久化的旧值不自动迁移，用户在设置页 token 输入框旁点骰子按钮自行轮换。
- **EngineSpec.args()**：自定义选项先加载，连接关键参数（daemon/enable-rpc/rpc-secret/rpc-listen-port/dir/session 等）**后置覆盖**，用户选项永远赢不了强制参数；client 与引擎从同一字段解析 endpoint，不会失同步。
- **HistoryStore**（filesDir/history.json）：aria2 重启即忘 stopped 结果，此 store 把 live 快照中的 COMPLETE/ERROR 实时折入历史（`merge`，异步 IO + Mutex，distinctBy gid，上限 300 条，newest first）。UI 列表 = live + history 按 gid 去重合并（DownloadViewModel.downloads）。`evict(gid)` 供删除/重试编排使用。
- **session 文件**（filesDir/session）：saveSession=true 时 `--input-file`/`--save-session` 指向它，30s 间隔保存；只救未完成任务，不救结果。

## 6. UI 层（架构级拆分后）

状态所有权规则：
- **DownloadViewModel**（AndroidViewModel，经手写 `Factory` 创建——项目未依赖 lifecycle-viewmodel-ktx，`viewModelFactory` DSL 不可用）：选中态、添加/详情/选项/删除对话框态、downloads 合并流（combine + stateIn WhileSubscribed(5000)）、速度采样（上限 60）、全部 RPC 编排。配置变更（旋转）后存活；进程被杀后的恢复（SavedStateHandle）尚未实现。
- **Composable 本地 remember**：抽屉/导航屏幕态（MainActivity）、添加对话框内的文本/选项草稿、详情对话框的 SHA 进度——生命周期与对话框一致，刻意不进 VM。
- **错误面**：VM 的 `rpcOp` = viewModelScope.launch + runCatching + CancellationException 重抛 + `events: SharedFlow<String>`（DROP_OLDEST）；DownloadScreen 收集后 showSnackbar。ext 确认对话框的失败提示仍是 Toast（原样保留）。

编排逻辑位置（改下载行为来这里，不要去 UI 文件）：
- `DownloadViewModel.retry(d)`：aria2 无 retry RPC——提取 files.uris → persisted 分支跳过引擎操作 → live 状态 remove / stopped 状态 clearResult → evictHistory → add(uris, opts)。
- `DownloadViewModel.deleteBatch(withFiles)`：调用时快照 targets；逐 gid remove→clearResult→evictHistory；**文件删除只在任务确认移除后执行**（从 live aria2 写盘底下删文件会丢数据）；单 gid 失败吞掉不中断批量，失败计数发 Snackbar。
- `FileOps.deleteDownloadFiles`：删 payload + `.aria2` 控制文件，沿空目录上溯（BT 布局），canonical path + 尾分隔符比较，绝不越出下载根目录。
- `FileOps.openLocalFile`：FileProvider content:// URI（API 24+ 禁 file://），getUriForFile 必须在 runCatching 内（SD 卡副卷无匹配 paths 根会抛 IllegalArgumentException）。
- ext 确认：`EngineProcess.confirmExtAdd/dismissExtAdd` 以 `PendingAdd.claimed: AtomicBoolean` CAS 一次定归属（App 对话框与通知按钮双入口并发安全），认领后才 addUri/complete verdict。**不要**把认领副作用写进 StateFlow.update 的 lambda——CAS 竞争时 lambda 会重试执行。

## 7. Extension API（App 内嵌 HTTP 服务）

`ExtensionApiServer`：裸 socket 手写 HTTP（表面只有几个 JSON 路由，不引服务框架），Connection: close，每连接一个协程，读超时 30s。默认绑定 **127.0.0.1**（显式 IPv4，避免 localhost→::1 失配）；`listenAll=true` 才绑 0.0.0.0（设置页有明文传输警告）。

鉴权与防护：
- Bearer token **常量时间比较**（MessageDigest.isEqual）；**blank token fail-closed**（空 token 时 `"Bearer "` 不得通过）。
- `/ping` `/version` 免鉴权（Rayburst Connect 发现协议对齐，商店版 Rayburst Connect 可直连本 App）。
- CORS `Access-Control-Allow-Origin: *`（同上，协议对齐的已知权衡）。
- 请求体上限 256KiB（鉴权后才分配）、单行 8KiB、头部 64 条上限。
- gid 路由正则限定 hex；`/add` 的 aria2 options 键固定白名单（header/user-agent/referer/out）；filename 经 `sanitizeFilename`（双分隔符取 basename + 控制字符过滤，无路径穿越）；仅 `filenameSource:"browser"` 可强制 `out`。

路由表：

| 路由 | 鉴权 | 说明 |
|---|---|---|
| GET /ping | 无 | `{"product":"rayburst","status":"ok","version":...}` |
| GET /version | 无 | App 版本 + 引擎阶段（running/starting/failed/stopped） |
| GET /downloads/capabilities | Bearer | protocolVersion:3 |
| GET /stat | Bearer | Rayburst 形状，计数全是十进制**字符串**；numStoppedTotal 只取 history.size（history 已折入本会话完成项，加 numStopped 会双重计数） |
| GET /tasks | Bearer | stat + 未完成任务（队列序）+ 最近 10 条完成 |
| POST /add | Bearer | 一律入 extPending 确认队列，见下 |
| POST /pause-all /resume-all | Bearer | 全暂停/全继续 |
| POST /tasks/{gid}/pause\|resume\|reveal | Bearer | reveal 经通知 contentIntent 打开文件（后台进程 startActivity 会被 BAL 静默拦截，通知点击天然豁免；无通知权限诚实返回 error） |

/add 确认流（confirm-first 契约，与 VIEW/SEND intent 一致，绝不静默添加）：
1. 解析 url（scheme 白名单：https?/ftp/magnet/ed2k/thunder）+ cookie/UA/referer/headers 合入 aria2 options。
2. `verify:"true"` 时调 `aria2.probe`（8s 预算）：引擎拿到 HTML 而浏览器拿到文件 → `fallback:web-page`（通常是过期会话）；方法不存在（上游 aria2）跳过校验；**刻意不比对 size**（Rayburst 的 size 是已收字节数，大文件必误判）。
3. `enqueueExtAdd(PendingAdd)` → App 对话框 + 高优先级通知（FSI best-effort）双入口等待用户。
4. `ack:"early"` 立即回 `accepted`；`ack:"final"` 挂起等 verdict（120s 超时 → dismiss + `fallback:unconfirmed`）。verdict：Added(gid)/Cancelled/Failed。
5. 引擎未 Running 时回 503：`engine_starting`（扩展重试）/ `engine_unavailable`（浏览器保留下载）。

生命周期：`updateExtApi(spec)` 相等短路、enabled && token 非空才 start；`releaseExtApi()` 停监听并置空 spec（防同 spec 再入被相等短路跳过）。**引擎停止后 API 保持监听并回 503 是有意契约**（浏览器据此保住下载），不做联动关闭；服务销毁才显式释放。

## 8. 浏览器扩展（extension/，MV3）

- `background.js`：`onDeterminingFilename` 拦截 → **先 pause 扣住浏览器副本** → submit（**ack:"final"**，携带 cookie/UA/referer/浏览器文件名）→ 按裁决分支：accepted/added → cancel+erase（App 接管）；**cancelled → resume 交还浏览器**（拒绝接管 ≠ 双删）；fallback/超时/不可达 → resume。`byExtensionId` 防回环。右键菜单发送走同一 submit。
- 契约："nothing is ever lost silently"——任何非接管结局浏览器都保住下载。改拦截逻辑时必须维持这条。
- token 存 `chrome.storage.local`（**禁止改回 sync**——token 不得经账号同步离开设备）；options 页对非回环 http:// endpoint 显示明文传输警告（token + 转发的站点 Cookie 会明文过局域网）。
- `<all_urls>` host 权限为 `cookies.getAll({url})` 所必需，已知权衡。
- popup.js 任务仪表盘（文件名走 textContent，无 innerHTML 注入）；options.js 连接配置。
- 协议细节见 extension/README.md；App 侧实现即 §7。

## 9. 安全模型与已知决策

| 决策 | 现状 | 理由与失效条件 |
|---|---|---|
| checkCertificate 默认 false | 设置项副标题如实说明需自行配置 ca-certificate | APK 未打包 CA bundle，Android 上 aria2 无系统证书库，翻 true 会破坏全部 HTTPS 下载。内置 Mozilla CA 并接入 caCertificatePath 后应翻 true |
| commonMain 用 ConcurrentHashMap | 字段声明处有注释 | call() 的 finally 清理需非挂起锁；Mutex 在调用方取消时泄漏 waiter。shared 保持 Android-only 期间成立；新增 target 必须 expect/actual |
| RPC/ext token 首启随机播种 | 16 字节 SecureRandom hex | 引擎永不监听公开已知默认值（源码里的 "gbc" 仅是未播种 fallback）；旧值不迁移，骰子按钮手动轮换 |
| ext API CORS * + /ping 免鉴权 | 协议对齐 | 商店版 Rayburst Connect 直连所需；风险由 Bearer + 回环绑定 + confirm-first 兜底 |
| listenAll 可选 | 设置页警告 | 局域网明文 HTTP 会暴露 token 与 Cookie；默认回环 |
| reveal 走通知点击 | 无通知权限返回 error | BAL 限制下后台 startActivity 静默失败，通知点击是唯一可靠豁免路径 |

## 10. 通知体系（EngineService）

| 通道 | 重要性 | 通知 ID | 用途 |
|---|---|---|---|
| engine | LOW | 1 | FGS 常驻通知（暂停全部/继续全部/停止按钮） |
| downloads | DEFAULT | 2 | aria2.on* 事件批处理通知（2.5s 窗口合并，固定 ID 替换不堆叠，onlyAlertOnce） |
| ext_requests | HIGH | 3 | ext 确认请求（CATEGORY_ALARM + FSI，确认/拒绝按钮；OEM 与 API 34+ 上 best-effort） |
| downloads | DEFAULT | 4 | reveal 文件打开（contentIntent，BAL 豁免） |

事件通知两阶段：收集器把 manager.events 灌入无界 Channel（永不阻塞发射方），flusher 等首个事件后吸收 2.5s 窗口再发一条——种子完成同秒触发三个事件不会刷屏。通道重要性创建时锁定，ext 请求必须独立通道。

## 11. 构建与 CI

变体（可共存安装，独立包名/数据目录/默认端口）：

| 变体 | applicationId | RPC 端口 | ext 端口 | 签名 |
|---|---|---|---|---|
| debug | com.aria2ngnext.debug | 6803 | 6903 | debug key |
| release | com.aria2ngnext.release | 6802 | 6902 | release.jks（缺失时回退 debug 签名） |

- minSdk 24 / targetSdk 37 / compileSdk 37，Java 21。release 开 minify + shrinkResources。
- `release.jks` / `keystore.properties` / `local.properties` 均 gitignore，从未入库；CI tag 发布从 repo secrets 恢复 keystore。
- 本地门禁（提交前必跑，取当次真实退出码）：
  ```bash
  ./gradlew :androidApp:compileDebugKotlin ktlintCheck --console=plain -q
  ./gradlew ktlintFormat   # 格式违规自动收敛
  ./gradlew :androidApp:assembleDebug
  ```
- CI（.github/workflows/android.yml）：push main / tag / PR 触发 `ktlintCheck assembleDebug assembleRelease`；`v*` tag 额外签名并发布 GitHub Release。
- 依赖版本统一走 gradle/libs.versions.toml。注意：lifecycle 只有 viewmodel-compose，**没有 viewmodel-ktx**——新建 ViewModel 工厂用手写 `ViewModelProvider.Factory`（参考 DownloadViewModel.Factory）。

## 12. 维护速查

| 要做的事 | 落点 |
|---|---|
| 新增 aria2 RPC 方法 | Aria2Client 加 suspend 封装 → DownloadManager 加业务 API（变更后 refresh）→ VM 编排 → UI 回调 |
| 新增引擎设置项 | EngineSpec 字段 + args() 映射 → SettingsRepo key/spec/save → SettingsScreen 控件；连接关键参数放强制区 |
| 新增 ext 设置项 | ExtApiSpec + SettingsRepo extSpec/saveExt + SettingsScreen；**不要**塞进 EngineSpec（会重启引擎） |
| 新增 ext API 路由 | ExtensionApiServer.handle 路由表 + 鉴权后分支；输入过白名单/正则/sanitize |
| 改下载行为（重试/删除/添加） | DownloadViewModel 编排 + FileOps；保持"文件删除在任务移除后"与 CAS 认领契约 |
| 改拦截/接管语义 | extension/background.js + §7 /add 流；维持"nothing is ever lost silently" |
| 新增 UI 屏幕 | 一屏一文件（参考 LogScreen.kt），导航挂 MainActivity 抽屉 |

高频陷阱（历史评审反复踩过的）：
1. StateFlow.update 的 lambda 会因 CAS 竞争重试——副作用（认领/置位）放 lambda 外，用 AtomicBoolean。
2. 收集 DataStore 设置前先 ensureDefaults()，否则首启窗口拿到空 token/默认值。
3. refresh 写回前 ensureActive；结果 distinctBy gid。
4. 后台 startActivity 会被 BAL 静默拦截且不抛异常——runCatching.isSuccess 不代表成功。
5. dataSync FGS 必须处理两个 onTimeout 重载。
6. 评审/修复结论跨轮次复用前重读当前代码并重跑门禁——并行提交会让行号与绿灯状态同时过期。

---

附：历史评审报告（CODE_REVIEW.md / CODE_REVIEW_R3.md / CODE_REVIEW_R3_FIXES.md）为本地工作文档，已 gitignore，不随仓库分发。