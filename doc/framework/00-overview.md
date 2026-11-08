# Framework Overview

Layering, startup order, cross-cutting concerns and the entry points of the three main flows of the VillagerDetails framework.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> **Audience**: senior developers who modify the framework, add rules, or debug threading and deadlock issues.
> **Boundary with the business docs**: "why does the player see A instead of B" → `doc/business/`; "why is the code written this way" → this directory.
>
> This chapter gives the layer diagram, the fixed startup order, the five cross-cutting concerns, the entry
> points of the three main flows, and the behaviour fixed at compile time. Mechanism details live in the
> dedicated chapters.

---

### 1. Layers

```
┌──────────────────────────────────────────────────────────────────────────┐
│ Business layer (doc/business/)                                           │
│   RuleType enum · handler/* · mixin/* · per-rule config sections         │
└───────────┬──────────────────────────────────────────────────────────────┘
            │ uses only the framework APIs below
┌───────────▼────────────┬────────────────────────┬────────────────────────┐
│ Rule framework (05)    │ Command framework (06) │ Network framework (07) │
│ RuleCache / RuleType   │ RuleCommand / CCommand │ PayloadTypes           │
│ RuleChangeService      │ base classes / perms   │ SyncChannel            │
│ RuleSwitches           │ permission filters     │ ServerReceiver         │
│ state validators       │ startup self-check     │ SyncSubscriptions      │
└────────────────────────┴────────────────────────┴────────────────────────┘
                         │                        │
┌────────────────────────▼────────────────────────▼────────────────────────┐
│ Config pipeline (02)   │ Config lifecycle (03)  │ Session / scanning (04)│
│ AbstractAsyncConfig    │ AbstractWorldConfig    │ SessionState           │
│ AsyncConfigManager     │ ConfigRegistry         │ ServerSessionStates    │
│ AsyncConfigWriter      │ RuleConfig / Switch    │ ChannelScanner         │
│ JsonIO                 │ MigrationManager       │ ScanService            │
└────────────────────────┴────────────────────────┴────────────────────────┘
                         │                        │
┌────────────────────────▼────────────────────────▼────────────────────────┐
│ Threading (01)                                                           │
│   ModThreadPool · SnapshotThreadPool · QueryThreadPool                   │
│   ServerThreadManager (lifecycle + pre-shutdown hooks)                   │
└──────────────────────────────────────────────────────────────────────────┘
```

Dependencies are **one-way**: business → the three framework facades → config/session → threading.
Callbacks that travel the other way (`RuleCache` listeners, `SessionState.clearSession`,
scan-driven `register()` calls) go through interfaces, so the framework never depends on business classes.

---

### 2. Startup order

#### 2.1 Shared entry point — `VillagerDetails.onInitialize` (main source set, runs on both sides)

| # | Call | Purpose | Why it comes first |
|---|---|---|---|
| 0a | `PayloadTypes.registerAll()` | Registers custom payload **types** into `PayloadTypeRegistry` | Fabric requires the registration on both physical sides, otherwise the peer cannot decode custom packets |
| 0b | `ModEntities.register()` | Registers mod entity types (currently only the block seat) | Also required on both sides |

#### 2.2 Server entry point — `VillagerDetailsServer.init()`

| # | Call | Purpose | Why it must come after the previous step |
|---|---|---|---|
| 1 | `ServerThreadShutdownTask.register()` → `ServerThreadManager.init()` | Creates both worker pools and registers the pre-shutdown hook | Everything asynchronous depends on it |
| 2 | `ConfigManager.registry()` | Registers the `SERVER_STARTED` reload hook; runs `RuleDataRegistry.verifyConsistency()` and `ConfigRegistry.verifyReloadableConfigs()` | These checks **touch `RuleType` for the first time**, which initialises the `RuleCache` static block (all rules enter memory with their default state) |
| 3 | `ServerSessionStates.register()` | Scans `cache.impl` and hooks `DISCONNECT` / `SERVER_STOPPING` | Must precede business registration so every state has an owner |
| 4 | `ServerEventManager.registerAll()` | Scans `event.impl` (including sub-packages) and registers all Fabric events | Business events must be active before commands and networking |
| 5 | `RuleModuleRegistry.initAll()` | Registers rule callback modules (`LanguageModule`, `ExchangeModule`) | Must happen before any rule can be changed |
| 6 | `RuleCommand.registerHook()` | Hooks `CommandRegistrationCallback` | Rules must already be registered |
| 7 | `ServerChannels.registerAll()` | Payload types + channel self-checks + receivers + sync events | Last, because it validates that every payload is registered |

#### 2.3 After `SERVER_STARTED`

```
SERVER_STARTED
 ├─ ConfigRegistry.reloadAll(server)   → ensureLoaded + onReload + markDirty for every config file
 ├─ ServerThreadManager.init()         → idempotent; recreates the pools on a world switch
 └─ VillagerTradeSearchEvents          → VillagerTradeCacheConfig.ensureLoaded + first completion-cache build
```

Two ordering facts worth remembering:

1. **Rule state already exists in memory before the server starts** (the `RuleCache` static block writes the
   defaults in step 2). The values stored on disk are replayed by `RuleSwitchConfig.syncToSwitch()` on
   `SERVER_STARTED`. Reading a rule during step 2 yields the default; reading it afterwards yields the saved value.
2. During that static block `RuleCache.server` is still `null`, so the `persist` call is deliberately a no-op.

---

### 3. Five cross-cutting concerns

| Concern | Single source of truth | Chapter |
|---|---|---|
| **Main-thread rule** | `ServerThreadManager` (pools) + `ServerReceiver.registerServerReceiver` (packet callbacks hop back) + `AbstractWorldConfig.tickAll` (tick driver) | 01 / 07 / 03 |
| **Async boundary** | `ModThreadPool` / `SnapshotThreadPool` / `QueryThreadPool` / `AsyncConfigWriter` (the fourth execution line) | 01 / 02 |
| **Persistence** | `AbstractAsyncConfig` (dirty flag) → `AsyncConfigManager` (facade) → `AsyncConfigWriter` (single writer, retries failed writes up to 3 times) → `JsonIO` (atomic replace) | 02 |
| **Lifecycle** | `AbstractWorldConfig` (observer set + throttling) + `ConfigRegistry` (reload entry points) + `ServerStoppingListener` (reset) | 03 |
| **Registration** | `ScanService` (package → class index, built once per process) + `ChannelScanner` (role interface + instantiation + failure report), reused by five registries | 04 |

#### 3.1 The five scan-based registries

| Registry | Scanned package | Role interface |
|---|---|---|
| `ServerEventManager` | `com.villagerdetails.event.impl` | `ServerEventRegisterServer` |
| `ServerChannels` | `network.sync.{clientbound,receiver,event}` | `ClientboundChannel` / `ServerReceiver` / `SyncEvents` |
| `ServerSessionStates` | `com.villagerdetails.cache.impl` | `SessionState` |
| `PayloadTypes` | `network.sync.payload` | `ClientboundPayload` / `ServerboundPayload` |
| `RuleDataRegistry` | *(no scanning — derived from the `RuleType` enum)* | — |

---

### 4. Entry points of the three main flows

#### ① A `/ec ...` command

```
CommandRegistrationCallback
  → RuleCommand.register(dispatcher)             [06]
      ├─ /ec, /ec list ...          requires hasBasePermission   [05 permissions]
      └─ /ec c <rule> ...           CCommand.buildCSubcommand    [06 tree + startup validation]
           ↓ execute
        RuleChangeService.change                 [05 validation + write]
           ↓
        RuleCache.setState → persist(RuleSwitchConfig) + fireCallbacks/listeners   [05]
           ↓
        business callbacks (RuleCallbacks / RuleCache.addListener) → immediate side effects
```

#### ② A chunk unload (async index write)

```
ENTITY_UNLOAD (Fabric event, main thread)
  → VillagerTradePipeline.onEntityUnload         [business]
      → deep-copy a plain-data snapshot on the main thread
      → ModContext.getServerPool() → pool.execute(...)          [01]
          → worker: VillagerTradeCache.put(...)               [business]
              → VillagerTradeCacheConfig.markDirty()          [03]
                  → tickAll (200-tick throttle) → flush → save → submit   [02]
                      → writer thread: JsonIO.writeString (atomic replace) [02]
```

#### ③ Server shutdown

```
SERVER_STOPPING
  → ServerThreadManager.shutdown()               [01]
      ① runPreShutdownHooks()
          AbstractWorldConfig.flushAll()                   submit every dirty config (non-blocking)
          AsyncConfigManager.flushAndShutdown(3000)        drain + stop the writer thread
      ② QueryThreadPool.shutdown()      wait up to 1s, then run leftovers on the caller thread
      ③ SnapshotThreadPool.shutdown()   same contract, but ordering matters (see 01 §3)
  → ServerStoppingListener.onServerStopping()    [03]
      AbstractWorldConfig.resetAll()   detach server + clearDirty + clearData
      RuleCache.clearServer()
  → ServerSessionStates.clearAll() → each SessionState.clearSession()   [04]
```

Neither pool ever silently drops queued work: `ModThreadPool.shutdown()` waits up to one second and then
executes whatever is still queued **on the calling thread** (the main thread during shutdown).

---

### 5. Behaviour fixed at compile time

| Constant                                      | Location                                     | Why it is not configurable                                                                                         |
|-----------------------------------------------|----------------------------------------------|--------------------------------------------------------------------------------------------------------------------|
| `MIN_COMPATIBLE_VERSION` / `BLOCKED_VERSIONS` | `NetworkConstants`                           | Protocol compatibility is a release decision; a typo either locks everyone out or lets incompatible clients in     |
| `SyncProtocol.VERSION`                        | `SyncProtocol`                               | Any packet-shape change bumps the version and adds a new payload id; no compatibility fields inside an existing id |
| Pool capacities and rejection policies        | `SnapshotThreadPool` / `QueryThreadPool`     | Capacity is tied to "is losing a task a data loss?"                                                                |
| Pool / thread names, daemon flag, priority    | `ModThreadPool` constructor                  | Thread dumps are how we identify these pools                                                                       |
| Mixin list                                    | `v26.2\|v26.3/src/*/resources/*.mixins.json` | An unlisted mixin silently never applies                                                                           |

---

### 6. Reading order

```
00-overview (this chapter)
   → 01-threading → 02-config-pipeline → 03-config-lifecycle → 04-session-state
   → 05-rule-framework → 06-command-framework → 07-network-framework
   → 08-mixin-inventory → 09-version-adapter
10-glossary — consult any time
```

---

<a id="中文"></a>

## 中文

> **目标读者**：需要改框架、加新规则、排查并发 / 死锁问题的高级开发者。
> **与业务文档的边界**：「为什么玩家看到 A 而不是 B」→ `doc/business/`；「为什么代码这么写而不是那么写」→ 这里。
>
> 本文给出分层图、固定启动顺序、五个横切关注点、三条主线的入口，以及编译期固化的行为。
> 机制细节在各分篇。

---

### 1. 分层图

```
┌──────────────────────────────────────────────────────────────────────────┐
│ 业务层（doc/business/）                                                  │
│   RuleType 枚举 · handler/* · mixin/* · 各规则的配置节                   │
└───────────┬──────────────────────────────────────────────────────────────┘
            │ 只调下面这些「框架 API」
┌───────────▼────────────┬────────────────────────┬────────────────────────┐
│ 规则框架 (05)          │ 命令框架 (06)          │ 网络框架 (07)          │
│ RuleCache / RuleType   │ RuleCommand / CCommand │ PayloadTypes           │
│ RuleChangeService      │ 抽象基类 / 权限过滤    │ SyncChannel            │
│ RuleSwitches / 校验器  │ 启动自检               │ ServerReceiver         │
│ 状态校验器             │                        │ 静音订阅 / 描述符      │
└────────────────────────┴────────────────────────┴────────────────────────┘
                         │                        │
┌────────────────────────▼────────────────────────▼────────────────────────┐
│ 配置管线 (02)          │ 配置生命周期 (03)      │ 会话 / 扫包 (04)       │
│ AbstractAsyncConfig    │ AbstractWorldConfig    │ SessionState           │
│ AsyncConfigManager     │ ConfigRegistry         │ ServerSessionStates    │
│ AsyncConfigWriter      │ RuleConfig / Switch    │ ChannelScanner         │
│ JsonIO                 │ MigrationManager       │ ScanService            │
└────────────────────────┴────────────────────────┴────────────────────────┘
                         │                        │
┌────────────────────────▼────────────────────────▼────────────────────────┐
│ 线程层 (01)                                                              │
│   ModThreadPool · SnapshotThreadPool · QueryThreadPool                   │
│   ServerThreadManager（生命周期 + pre-shutdown 钩子）                    │
└──────────────────────────────────────────────────────────────────────────┘
```

**依赖是单向的**：业务 → 三个框架门面 → 配置 / 会话 → 线程。
反方向的回调（`RuleCache` 监听器、`SessionState.clearSession`、扫包驱动的 `register()`）
都通过**接口**回到业务，所以框架永远不依赖业务类。

---

### 2. 启动顺序

#### 2.1 两端共用入口 —— `VillagerDetails.onInitialize`（`main` 源集，两端都会跑）

| 序 | 调用 | 作用 | 为什么最前面 |
|---|---|---|---|
| 0a | `PayloadTypes.registerAll()` | 把自定义包**类型**登记进 `PayloadTypeRegistry` | Fabric 要求在物理两端各登记一次，否则对端无法解码自定义包 |
| 0b | `ModEntities.register()` | 注册模组实体类型（目前只有方块座位） | 同样两端都要有 |

#### 2.2 服务端入口 —— `VillagerDetailsServer.init()`

| 序 | 调用 | 作用 | 为什么必须在上一步之后 |
|---|---|---|---|
| 1 | `ServerThreadShutdownTask.register()` → `ServerThreadManager.init()` | 建两个业务池 + 登记 pre-shutdown 钩子 | 后面所有异步都依赖它 |
| 2 | `ConfigManager.registry()` | 注册 `SERVER_STARTED` 回灌钩子；跑 `RuleDataRegistry.verifyConsistency()` 与 `ConfigRegistry.verifyReloadableConfigs()` | 这两个自检会**首次触碰 `RuleType`**，从而初始化 `RuleCache` 静态块（所有规则以默认值进入内存） |
| 3 | `ServerSessionStates.register()` | 扫 `cache.impl` 并挂 `DISCONNECT` / `SERVER_STOPPING` | 早于业务注册，保证任何状态都有清理者 |
| 4 | `ServerEventManager.registerAll()` | 扫 `event.impl`（含子包）注册所有 Fabric 事件 | 业务事件必须早于命令与网络可用 |
| 5 | `RuleModuleRegistry.initAll()` | 注册规则回调模块（`LanguageModule`、`ExchangeModule`） | 必须早于任何规则被修改 |
| 6 | `RuleCommand.registerHook()` | 挂 `CommandRegistrationCallback` | 规则必须先注册完成 |
| 7 | `ServerChannels.registerAll()` | 包类型 + 通道自检 + 收包 + 同步事件 | 最后，因为它要校验每个 payload 都已登记 |

#### 2.3 `SERVER_STARTED` 之后

```
SERVER_STARTED
 ├─ ConfigRegistry.reloadAll(server)   → 每个配置文件 ensureLoaded + onReload + markDirty
 ├─ ServerThreadManager.init()         → 幂等；换存档时重建池
 └─ VillagerTradeSearchEvents          → VillagerTradeCacheConfig.ensureLoaded + 补全缓存首次构建
```

两个值得记住的时序事实：

1. **规则状态在开服前就已在内存里**（第 2 步的 `RuleCache` 静态块写入默认值）。
   磁盘上的值由 `RuleSwitchConfig.syncToSwitch()` 在 `SERVER_STARTED` 回灌。
   所以第 2 步期间读到的是默认值，之后读到的才是存档值。
2. 那个静态块执行时 `RuleCache.server` 仍是 `null`，因此那一次 `persist` 是刻意的空操作。

---

### 3. 五个横切关注点

| 关注点 | 唯一来源 | 分篇 |
|---|---|---|
| **主线程约束** | `ServerThreadManager`（池）+ `ServerReceiver.registerServerReceiver`（收包切回主线程）+ `AbstractWorldConfig.tickAll`（tick 驱动） | 01 / 07 / 03 |
| **异步边界** | `ModThreadPool` / `SnapshotThreadPool` / `QueryThreadPool` / `AsyncConfigWriter`（第四条执行线） | 01 / 02 |
| **落盘** | `AbstractAsyncConfig`（脏标记）→ `AsyncConfigManager`（门面）→ `AsyncConfigWriter`（单写者，写失败最多重试 3 次）→ `JsonIO`（原子替换） | 02 |
| **生命周期** | `AbstractWorldConfig`（观察者集合 + 节流）+ `ConfigRegistry`（重载入口）+ `ServerStoppingListener`（复位） | 03 |
| **注册** | `ScanService`（包名 → 类名索引，全进程构建一次）+ `ChannelScanner`（角色接口 + 实例化 + 失败汇总），被五个注册中心复用 | 04 |

#### 3.1 五个扫包注册中心

| 注册中心 | 扫的包 | 角色接口 |
|---|---|---|
| `ServerEventManager` | `com.villagerdetails.event.impl` | `ServerEventRegisterServer` |
| `ServerChannels` | `network.sync.{clientbound,receiver,event}` | `ClientboundChannel` / `ServerReceiver` / `SyncEvents` |
| `ServerSessionStates` | `com.villagerdetails.cache.impl` | `SessionState` |
| `PayloadTypes` | `network.sync.payload` | `ClientboundPayload` / `ServerboundPayload` |
| `RuleDataRegistry` | **不扫包**（由 `RuleType` 枚举推导） | — |

---

### 4. 三条主线的入口

#### ① 一次 `/ec ...` 命令

```
CommandRegistrationCallback
  → RuleCommand.register(dispatcher)             [06]
      ├─ /ec, /ec list ...          requires hasBasePermission   [05 权限]
      └─ /ec c <规则> ...           CCommand.buildCSubcommand    [06 命令树 + 启动自检]
           ↓ 执行
        RuleChangeService.change                 [05 校验 + 写入]
           ↓
        RuleCache.setState → persist(RuleSwitchConfig) + fireCallbacks/listeners   [05]
           ↓
        业务回调（RuleCallbacks / RuleCache.addListener）→ 立即副作用
```

#### ② 一次区块卸载（异步写索引）

```
ENTITY_UNLOAD (Fabric 事件, 主线程)
  → VillagerTradePipeline.onEntityUnload         [业务]
      → 主线程深拷贝纯数据快照
      → ModContext.getServerPool() → pool.execute(...)          [01]
          → worker：VillagerTradeCache.put(...)               [业务]
              → VillagerTradeCacheConfig.markDirty()          [03]
                  → tickAll（200 tick 节流）→ flush → save → submit   [02]
                      → 写线程：JsonIO.writeString（原子替换）        [02]
```

#### ③ 一次停服

```
SERVER_STOPPING
  → ServerThreadManager.shutdown()               [01]
      ① runPreShutdownHooks()
          AbstractWorldConfig.flushAll()                   提交所有脏配置（不阻塞）
          AsyncConfigManager.flushAndShutdown(3000)        排干 + 停写线程
      ② QueryThreadPool.shutdown()      最多等 1 秒，剩余任务拉回调用线程执行
      ③ SnapshotThreadPool.shutdown()   同样的契约，只是顺序有讲究（见 01 §3）
  → ServerStoppingListener.onServerStopping()    [03]
      AbstractWorldConfig.resetAll()   摘 server + clearDirty + clearData
      RuleCache.clearServer()
  → ServerSessionStates.clearAll() → 各 SessionState.clearSession()   [04]
```

两个池都不会静默丢弃已入队的任务：`ModThreadPool.shutdown()` 最多等一秒，
之后把队列里剩下的任务**在调用线程（停服时即主线程）**上执行完。

---

### 5. 编译期固化的行为

| 常量                                          | 位置                                     | 为什么不做成配置                                           |
|-----------------------------------------------|------------------------------------------|------------------------------------------------------------|
| `MIN_COMPATIBLE_VERSION` / `BLOCKED_VERSIONS` | `NetworkConstants`                       | 协议兼容是发布决定；写错要么全员被拒、要么放行不兼容客户端 |
| `SyncProtocol.VERSION`                        | `SyncProtocol`                           | 包形状一变就抬版本并新增包 id，不在既有 id 里塞兼容字段    |
| 池容量与拒绝策略                              | `SnapshotThreadPool` / `QueryThreadPool` | 容量与「丢了算不算数据丢失」强相关                         |
| 池名 / 线程名 / daemon / 优先级               | `ModThreadPool` 构造                     | 线程 dump 靠它定位                                         |
| mixin 清单                                    | `v26.2                                   | v26.3/src/*/resources/*.mixins.json`                       | 未列出的 mixin 会静默不生效 |

---

### 6. 阅读顺序

```
00-overview（本篇）
   → 01-threading → 02-config-pipeline → 03-config-lifecycle → 04-session-state
   → 05-rule-framework → 06-command-framework → 07-network-framework
   → 08-mixin-inventory → 09-version-adapter
10-glossary 随时查
```
