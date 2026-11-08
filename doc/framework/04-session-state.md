# Session state framework (`SessionState` + package scanning)

The `SessionState` lifecycle contract, the three scanning layers behind it, the three clearing moments, and the data-pollution cases the framework prevents.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> Covers: `SessionState` · `ServerSessionStates` · `ChannelScanner` · `ScanService` · `SyncSubscriptions`.
> The client-side counterpart (`ClientSessionStates` / `ClientChannels`) is in [07](07-network-framework.md#8-客户端对端).
> Which caches should implement it on the business side: [04-glossary](../business/04-glossary.md) and [tab-completion](../business/03-deep-dives/tab-completion.md).

---

### 1. Contract

```java
public interface SessionState {
    void clearSession();                    // whole thing: disconnect / world switch / shutdown
    default void clearPlayer(UUID uuid) {}   // server only: drop that player's slice on quit
}
```

**Semantic boundary** (much stricter than the method signatures):

1. `clearSession()` must make "enter the world again" equal "just started" — **including "already notified / already requested" memory flags**; otherwise the second session silently skips the first sync.
2. `clearPlayer(uuid)` drops only the part that belongs to that player; `SyncSubscriptions` is the canonical example (a mute set keyed by UUID).
3. Implementations **must not** read or write the disk while clearing (`VillagerTradeCache.clearSession()` is the one special case: it calls `flush()` first, because the business semantics is "turning the rule off must not lose data", and it keeps the memory state when the flush fails — see the business docs).
4. **Callers always go through the instance** (`Xxx.INSTANCE`); there are no static methods with the same name — with both variants alive, "clear this one, read that one" is the classic mistake.

---

### 2. Scanning mechanism (three layers)

```
① ScanService            package name → class name index, built once per process
   ├─ build(): scans the mod root + its own code source, groups by package name (TreeMap, ordered)
   ├─ whitelist SCAN_PACKAGES: a package outside the whitelist → IllegalStateException, startup fails
   └─ loadClasses(pkg, anchor, onLoadFailure): Class.forName(name, false, loader) loads without initialising;
      a single failure only WARNs + invokes the callback, it never aborts the round
                ↓
② ChannelScanner         role interface → instance → callback
   ├─ forEach(pkg, caller, role, action, failures): classes → filter interface/abstract → instanceOf → action
   ├─ instanceOf: prefers public static final INSTANCE, otherwise falls back to the no-arg constructor (setAccessible even when private)
   ├─ order: sorted by class name (stable, independent of the file system / jar entry order)
   ├─ checkNotEmpty: scanning zero classes is fatal ("one block never registered" fails silently)
   └─ reportFailures: aggregates the whole round's Failures into one ERROR
                ↓
③ ServerSessionStates    assembly + event wiring
   └─ registerAll(): scans com.villagerdetails.cache.impl → fills STATES → appends SyncSubscriptions → sets registered
```

**The whitelist is a startup self-check.** `ScanService.classNames(packageName)` first checks `isCovered` against `SCAN_PACKAGES`; when the requested package is not listed it logs an ERROR and throws `IllegalStateException`, and the message carries the requested package name plus the allowed prefixes. Registering a new registry therefore fails loudly at startup instead of producing an empty table — a mistyped or missing package can never degrade into "nothing was scanned, the feature silently never works".

**The volatile publication of `registered`**: `STATES` is filled first and `registered = true` is written last. The other order (publish, then fill) lets another thread walk a half-built `ArrayList`. Every clearing entry point (`clearPlayer` / `clearAll`) calls `registerAll()` as a fallback — that early return is **deliberately silent**: the duplicate-registration WARN hangs off `init()`, the one place that should run exactly once, so normal play is not spammed with warnings.

**Main thread only**: `registerAll()` takes no lock because it can only be entered from the server main thread — `init` (the entry point), `DISCONNECT` and `SERVER_STOPPING` are all main-thread events and therefore serialised; `volatile registered` publishes, it does not provide mutual exclusion. `SessionState.clearSession` / `clearPlayer` inherit the same contract: the framework calls them on the main thread only, so implementations need no locks against concurrent clearing — and must not hand the clearing work to another thread. A future non-main-thread caller has to re-introduce locking or CAS-based claiming, otherwise `STATES` is filled twice.

**Manual registrations are part of the design**: `SyncSubscriptions` belongs to the network framework (`network.channel`), not to `cache.impl`, so `registerAll()` adds it with an explicit `STATES.add(SyncSubscriptions.INSTANCE)`. That line carries a comment stating that it sits outside the scanned packages and that any new state of the same kind has to be added right there; every manual registration point carries such a comment.

---

### 3. What the three moments call

| Moment | Trigger | Call | Scope |
|---|---|---|---|
| Player quits | `ServerPlayConnectionEvents.DISCONNECT` | `state.clearPlayer(uuid)` one by one | per player (mute records, selection, allow-list, mod version) |
| Player rejoins | the client reports again (business side) | — | the server keeps nothing from the previous session and waits for the client |
| Shutdown / world switch | `ServerLifecycleEvents.SERVER_STOPPING` | `state.clearSession()` one by one | everything (index, tombstones, epoch — see the business docs) |
| Disconnect (client) | `ClientSessionStates` | `clearSession()` | everything on the client |

**Two clearers in one shutdown, and their order**: `SERVER_STOPPING` has three registrants, executed in registration order — `ServerThreadManager.shutdown` (first runs the pre-shutdown hooks that drain the pending writes) → `ServerStoppingListener` (`resetAll` + `clearServer`) → `ServerSessionStates.clearAll`. So the `flush()` inside `VillagerTradeCache.clearSession()` runs after the write thread is already gone: it goes through `AbstractWorldConfig.flush()` (swallows the exception and keeps the dirty flag), so it cannot throw, and the real persist already happened in the pre-shutdown hook. The pre-shutdown hook running ahead of `clearAll` is an ordering dependency of that design, not a coincidence.

---

### 4. Which `SessionState`s exist on the server

| Implementation | What it clears | What an uncleaned state looks like (examples) |
|---|---|---|
| `VillagerTradeCache` | index + tombstones + `LAST_SNAPSHOT` + advances the epoch + `clearedAt` | after a world switch you search villagers from the previous world |
| `SyncSubscriptions` | player UUID → muted channel set | after a server switch a channel stays muted |
| `PlayerHeadSittingAllow` | the "others may sit on my head" list | after a switch a stranger can sit on your head |
| `PlayerBlockSittingPreference` | the "I do not want to sit" list | the previous session's opt-out carries over |
| `EBSelectionStateCache` | the entity + block dual selection | the old server's selection boxes are drawn; binding targets an entity that does not exist |
| `BBSectionStateCache` | pos1 / pos2 | same as above (region) |
| `PortalSelectionCache` | the portal binding "pending in this dimension" cache | after a switch `bind` uses the previous session's selection |
| `PlayerModVersion` | player → reported mod version | the handshake reads the previous session's version (affects the version gate after a reconnect) |
| **`TradeItemCompletionCache`** | **not implemented** | — |

#### Why `TradeItemCompletionCache` does not implement it

It is a **view derived from a registry** (`VILLAGER_TRADE` → completion candidates), not "this session's state of a player":

- the registry changes with data packs / mods and has nothing to do with a session;
- clearing it only creates a **completion gap** (the next Tab completion list is empty until someone refreshes it manually or reloads the data pack);
- it disappears with the JVM on shutdown, and `SERVER_STARTED` recomputes it after a restart.

**This is the decision rule**: anything that can be recomputed from facts outside the save file (registries, constants, data packs) should not be a `SessionState`.

---

### 5. Data pollution: what an uncleaned state looks like

| Scenario | Symptom |
|---|---|
| Selection (EB/BB) left over across sessions | selection boxes are drawn at the previous session's positions; the binding flow treats a non-existent entity UUID as the target, `getEntity` returns null → silent failure |
| Allow-list (`PlayerHeadSittingAllow`) left over | in single player you allowed a friend in save A; in save B that permission still holds (same UUID) |
| Mute set left over | after a server switch a channel never receives packets again, which on the client looks like "the highlight / list stops refreshing" |
| Trade index left over | query results show villagers from another save (clicking them lands on nothing) |
| Epoch / tombstones left over | a late `put` from the new session is killed by the previous session's tombstone (one entry lost), or the tombstone is not cleared and the state ends up as "in the index and tombstoned at once" |

---

### 6. Failure isolation

`ChannelScanner` wraps **every class in its own try** and records failures as `Failure(stage, target, what, cause)`:

- a single class failing to instantiate → skip it and continue with the other classes of the same package;
- at the end of the round `reportFailures` aggregates them into one ERROR (no per-class spam);
- `checkNotEmpty` detects "the whole package produced 0 implementations" → that is a configuration error (wrong package name / sub-package not shipped), and it must be reported, otherwise "one block never registered" presents itself as "some feature never works".

**Why "0 classes" is worse than "1 failure"**: one failure is usually a problem with an individual class; zero means **the whole registration chain is disconnected**.

---

### 7. Class-name verification (this page)

| Claim | Verified |
|---|---|
| `SessionState`: `clearSession` / default `clearPlayer` | ✅ |
| `ServerSessionStates`: `STATE_PACKAGE` / `INSTANCE` / `STATES` / `registered` / `initialized` / `register` / `init` / `registerAll` / `clearPlayer` / `clearAll` / `guard` | ✅ |
| `ChannelScanner`: `Failure` (`stage` / `target` / `what` / `cause` / `describe`) / `forEach` / `instanceOf` / `checkNotEmpty` / `reportFailures` | ✅ |
| `ScanService`: `loadClasses(String, Class, BiConsumer)` / `classNames` / `index` / `isCovered` / `SCAN_PACKAGES` | ✅ |
| Server `SessionState` implementations: 7 in `cache.impl` + `SyncSubscriptions` (manual) | ✅ confirmed by a repo-wide grep |
| `PlayerModVersion implements SessionState` | ✅ cleared on disconnect |
| `TradeItemCompletionCache` does not implement `SessionState` | ✅ |

---

<a id="中文"></a>

## 中文

> 覆盖：`SessionState` · `ServerSessionStates` · `ChannelScanner` · `ScanService` · `SyncSubscriptions`。
> 客户端的对应物（`ClientSessionStates` / `ClientChannels`）在 [07](07-network-framework.md#8-客户端对端)。
> 业务侧"哪些缓存该实现它"见 [04-glossary](../business/04-glossary.md) 与 [tab-completion](../business/03-deep-dives/tab-completion.md)。

---

### 1. 契约

```java
public interface SessionState {
    void clearSession();                    // 整份清空：断线 / 切世界 / 停服
    default void clearPlayer(UUID uuid) {}   // 服务端专用：玩家退出时摘掉他那份
}
```

**语义边界**（比方法签名严格得多）：

1. `clearSession()` 必须做到"清完再进服 == 刚启动"——**包括"已通知过 / 已请求过"这类记忆标记**，
   否则第二局会出现"第一次同步被跳过"的静默 bug。
2. `clearPlayer(uuid)` 只清"属于这个玩家"的那部分；`SyncSubscriptions` 是标准例子（按 UUID 存静音集合）。
3. 实现类**不允许**在清理里读盘 / 写盘（`VillagerTradeCache.clearSession()` 是个特例：它会先 `flush()`，
   但那是业务语义"关规则不丢数据"，且失败时保留内存 —— 见业务文档）。
4. **调用方一律走实例**（`Xxx.INSTANCE`），不提供静态同名方法 —— 两套并存时最容易"清的是这份、读的是那份"。

---

### 2. 扫包机制（三层）

```
① ScanService            包名 → 类名索引，全进程建一次
   ├─ build()：扫 mod 根目录 + 自身 code source，按包名分组（TreeMap，天然有序）
   ├─ 白名单 SCAN_PACKAGES：包名不在白名单 → 抛 IllegalStateException，启动直接失败
   └─ loadClasses(pkg, anchor, onLoadFailure)：Class.forName(name, false, loader) 只加载不初始化；
      单个失败只 WARN + 回调，不中断
                ↓
② ChannelScanner         角色接口 → 实例 → 回调
   ├─ forEach(pkg, caller, role, action, failures)：取类 → 过滤 interface/abstract → instanceOf → action
   ├─ instanceOf：优先 public static final INSTANCE，没有就退回无参构造（私有也 setAccessible）
   ├─ 顺序：按类名排序（稳定，不依赖文件系统 / jar 条目顺序）
   ├─ checkNotEmpty：一个都没扫到 = 致命错（"少注册一块"会静默失效）
   └─ reportFailures：把整轮 Failure 汇总成一条 ERROR
                ↓
③ ServerSessionStates    组装 + 挂事件
   └─ registerAll()：扫 com.villagerdetails.cache.impl → 填 STATES → 追加 SyncSubscriptions → 置 registered
```

**白名单是一次启动自检**：`ScanService.classNames(packageName)` 先 `isCovered` 查 `SCAN_PACKAGES`，
不在白名单就记一条 ERROR 并抛 `IllegalStateException`，报错信息里带请求的包名与允许的前缀列表。
于是"新加了注册中心却忘了登记包"这类配置错误在启动期就炸掉，而不是产出一张空表 ——
包名写错或漏登记，永远不会表现成"什么都没扫到、功能静默不生效"。

**`registered` 的 volatile 发布语义**：先填 `STATES`，最后才置 `registered = true`。
反过来（先置位再填）会让别的线程遍历到一张半成品 `ArrayList`。
清理入口（`clearPlayer` / `clearAll`）每次都先 `registerAll()` 兜底 —— 它的 early-return **刻意沉默**，
防重复的 WARN 只挂在"全局只该被调一次"的 `init()` 上，否则正常流程会被刷成告警。

**只在主线程**：`registerAll()` 不加锁的前提是它只可能从服务器主线程进来 ——
`init`（入口点）/ `DISCONNECT` / `SERVER_STOPPING` 都是主线程事件，三者天然串行；
`volatile registered` 只负责发布可见性，不负责互斥。`SessionState.clearSession` / `clearPlayer`
沿用同一条约定：框架只在主线程调用它们，所以实现里不需要为并发清理加锁，
也不该把清理动作丢到别的线程上；将来若真的出现非主线程的调用方，必须重新加锁或改成 CAS 抢占，
否则 `STATES` 会被重复填充。

**手工登记也是设计的一部分**：`SyncSubscriptions` 属于网络框架（`network.channel`），
不在 `cache.impl` 包内，所以 `registerAll()` 里**手工** `STATES.add(SyncSubscriptions.INSTANCE)`；
那一行带着注释，写明它不在扫包范围里、将来新增同类"外挂状态"要在这里补一行。
凡是手工登记点，一律带这样的注释。

---

### 3. 三个时机分别调什么

| 时机 | 触发 | 调用 | 覆盖范围 |
|---|---|---|---|
| 玩家退出 | `ServerPlayConnectionEvents.DISCONNECT` | 逐个 `state.clearPlayer(uuid)` | 按玩家（静音记录、选区、同意名单、模组版本） |
| 玩家重进 | 客户端重新上报（业务自己发） | — | 服务端不保留上一局状态，等客户端重发 |
| 停服 / 换存档 | `ServerLifecycleEvents.SERVER_STOPPING` | 逐个 `state.clearSession()` | 整份（含索引、墓碑、纪元 —— 见业务文档） |
| 断线（客户端） | `ClientSessionStates` | `clearSession()` | 客户端整份 |

**一个"服务端清两次"的顺序问题**：`SERVER_STOPPING` 有三个注册者，执行顺序是注册顺序，
实际是 `ServerThreadManager.shutdown`（先跑 pre-shutdown 钩子排干写盘）→ `ServerStoppingListener`（`resetAll` + `clearServer`）
→ `ServerSessionStates.clearAll`。所以 `VillagerTradeCache.clearSession()` 里那次 `flush()`
发生在"写线程已经关掉之后"—— 它内部的 `flush` 会走 `AbstractWorldConfig.flush()`（吞异常 + 保留脏标记），
不会抛；而真正的落盘已经在 pre-shutdown 钩子里完成过。
pre-shutdown 钩子排在 `clearAll` 之前是这条设计的顺序依赖，不是巧合。

---

### 4. 服务端现有哪些 `SessionState`

| 实现 | 清理什么 | 不清的后果（举例） |
|---|---|---|
| `VillagerTradeCache` | 索引 + 墓碑 + `LAST_SNAPSHOT` + 推进纪元 + `clearedAt` | 换存档后查到上一个世界的村民 |
| `SyncSubscriptions` | 玩家 UUID → 静音通道集合 | 换服后仍然收不到某个通道的包 |
| `PlayerHeadSittingAllow` | "允许别人坐我头上"名单 | 换服后陌生人能直接坐你头上 |
| `PlayerBlockSittingPreference` | "我不想坐"名单 | 上一局的关闭状态跨局生效 |
| `EBSelectionStateCache` | 实体 + 方块双选区 | 换服后画着旧服的选区框；绑定时指向不存在的实体 |
| `BBSectionStateCache` | pos1 / pos2 | 同上（区域） |
| `PortalSelectionCache` | 传送门绑定的"本维度待绑定"缓存 | 换服后 `bind` 用了上一局的选区 |
| `PlayerModVersion` | 玩家 → 上报的模组版本 | 握手判定读到上一局的版本（影响重连后的版本拦截） |
| **`TradeItemCompletionCache`** | **不实现** | —— |

#### 为什么 `TradeItemCompletionCache` 不实现

它是**注册表派生的视图**（`VILLAGER_TRADE` → 补全候选），不是"玩家这一局的状态"：

- 注册表随数据包 / 模组变化，与"会话"无关；
- 清空它只会制造**补全空窗期**（下一次 Tab 补全列表是空的，直到有人手动刷新或重载数据包）；
- 停服时随 JVM 一起消失，重启后 `SERVER_STARTED` 会重算。

**这是一条判断标准**：能由"存档外的事实"（注册表、常量、数据包）重新算出来的，就不该是 `SessionState`。

---

### 5. 数据污染：不清会怎样

| 场景 | 表现 |
|---|---|
| 选区（EB/BB）跨局残留 | 选区框画在上一局的位置；绑定流程把"不存在的实体 UUID"当目标，`getEntity` 返回 null → 静默失败 |
| 同意名单（`PlayerHeadSittingAllow`）跨局生效 | 单人 A 存档里你允许了朋友，进 B 存档后这个"允许"还生效（UUID 相同） |
| 静音集合跨局 | 换服后某个通道一直收不到包，客户端表现为"高亮/列表不刷新" |
| 交易索引跨局 | 查询结果里出现另一个存档的村民坐标（点过去是空的） |
| 纪元 / 墓碑跨局 | 新会话的迟到 `put` 被上一局的墓碑误杀（数据少一条），或反过来墓碑没清导致"索引有、墓碑也有" |

---

### 6. 失败隔离

`ChannelScanner` 对每个类做**独立 try**，失败记成 `Failure(stage, target, what, cause)`：

- 单个类实例化失败 → 跳过它，继续处理同包其它类；
- 整轮末尾 `reportFailures` 汇总成一条 ERROR（不逐条刷屏）；
- `checkNotEmpty` 检测"整包 0 个实现" → 这是配置错误（包名写错 / 子包没打进产物），必须报出来，
  否则"少注册一块"会表现为"某功能永远不生效"。

**为什么"0 个"比"1 个失败"更严重**：1 个失败通常是个别类的问题；0 个说明**整条注册链都没接上**。

---

### 7. 类名核实结果（本篇）

| 断言 | 核实 |
|---|---|
| `SessionState`：`clearSession` / 默认 `clearPlayer` | ✅ |
| `ServerSessionStates`：`STATE_PACKAGE` / `INSTANCE` / `STATES` / `registered` / `initialized` / `register` / `init` / `registerAll` / `clearPlayer` / `clearAll` / `guard` | ✅ |
| `ChannelScanner`：`Failure`（`stage` / `target` / `what` / `cause` / `describe`）/ `forEach` / `instanceOf` / `checkNotEmpty` / `reportFailures` | ✅ |
| `ScanService`：`loadClasses(String, Class, BiConsumer)` / `classNames` / `index` / `isCovered` / `SCAN_PACKAGES` | ✅ |
| 服务端 `SessionState` 实现共 7 个（`cache.impl`）+ `SyncSubscriptions`（手工） | ✅ 全仓 grep 确认 |
| `PlayerModVersion implements SessionState` | ✅ 断线时清理 |
| `TradeItemCompletionCache` 未实现 `SessionState` | ✅ |
