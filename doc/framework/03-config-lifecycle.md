# Config Lifecycle

When the four on-disk config files and the config sections are loaded, reloaded, and reset, and how the registry drives them.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> **Covers**: `AbstractWorldConfig` · `ReloadableConfig` · `ConfigRegistry` · `ConfigManager` ·
> `RuleConfig` · `RuleSwitchConfig` · `MigrationManager` (only *when* it is called).
> The mechanism of the persistence pipeline is in [02](02-config-pipeline.md); this page is about **when to load, when to reload, when to reset**.

---

### 1. Four "in-save JSON" files plus two kinds of config

| Config class | File (relative to the save root) | `configName()` | Persist interval | In `ConfigRegistry`? |
|---|---|---|---|---|
| `RuleConfig` | `villagerdetails/config.json` | `RuleConfig` | 20 ticks (default) | ✅ |
| `RuleSwitchConfig` | `villager_details_binding.json` | `RuleSwitchConfig` | 20 ticks | ✅ |
| `TradeIndexConfig` | `villagerdetails/trade_index.json` | `TradeIndexConfig` | 20 ticks | ✅ |
| `VillagerTradeCacheConfig` | `villagerdetails/villager_trade_cache.json` | `VillagerTradeCacheConfig` | **200 ticks** | ✅ |
| **Per-rule configs** (`NoSqueezeConfig`, `FurnaceDirtyHighlightConfig`, `PortalBindingConfig` …) | no file of their own — one **section** of `config.json` | the rule name | 20 ticks | ❌ **must not** be registered |
| `ServerLangConfig` / `PlayerHeadSittingConfig` and friends | same as above (section) | the rule name | 20 ticks | ❌ |

The line between the two kinds is **"does it have its own file"**:

- with its own file → it goes into `ConfigRegistry` and can be reloaded by name via `/ec c reload <name>`;
- section only → driven centrally by `RuleConfig.reloadChildren()` (they read `RuleConfig`'s tree, so they must load after the tree itself).

`ConfigRegistry.verifyReloadableConfigs()` nails this line down at startup: if a config bound to a rule is wrongly registered in `ConfigRegistry`, it throws `IllegalStateException` immediately.

---

### 2. `AbstractWorldConfig`: a subclass answers only 5 questions

| The subclass must implement | Semantics |
|---|---|
| `savePath()` | path relative to the save root; **asked again on every persist** (no cached path to invalidate when the world changes) |
| `load()` | disk → memory; uses `currentFile()` for the path, and returns `null` for "there is no readable file right now" |
| `clearData()` | clears memory (used by the shutdown reset); **never reads the disk and never writes it** |
| `getDataToSave()` | the object handed to Gson (a live reference, walked only at the instant of `flush`) |
| `configName()` | registry de-duplication / `/ec c reload` name lookup / log prefix |

Optional extension points: `onReload(server)`, `onDiskLoad(server)`, `flushIntervalTicks()` (returns 20 by default).

**Methods made `final`** (one fixed order, subclasses cannot change it): `reload` / `reloadFromDisk` / `getFilePath` / `flush`. If a subclass changed any of these four, the failure semantics of the four configs would drift apart.

---

### 3. Observer queue and throttling

```
static { ServerTickEvents.END_SERVER_TICK.register(_ -> AbstractWorldConfig.tickAll()); }   ← the only registration point in the class

markDirty()  →  super.markDirty() (dirty=true) + DIRTY_SET.add(this)
tickAll()    →  globalTicks++
                DIRTY_SET empty → return immediately (the vast majority of ticks)
                otherwise, for each instance:
                  globalTicks - lastFlushedTick >= flushIntervalTicks()
                     → flush()
                     → if (!isDirty()) { DIRTY_SET.remove(this); lastFlushedTick = globalTicks; }
```

Three design points:

1. **Why iterate `DIRTY_SET` instead of `ALL`**: `ALL` holds "every instance (4+)", while `DIRTY_SET` is empty most of the time. On the tick path "return immediately on an empty set" is cheaper than "walk 4 instances and test each of them", and **a new config needs no change to the tick logic**.
2. **Why `lastFlushedTick` starts at `-1`**: it guarantees that the first time an instance enters the dirty queue `globalTicks - (-1) >= interval` already holds, so "the first config change after startup" writes the file right away (instead of waiting a full 20 ticks).
3. **"Registering the tick twice halves the period"**: `globalTicks` is a static counter, so every extra `tickAll` registration adds +2 inside one real tick → a 200-tick throttle becomes 100 ticks. Hence the class comment forbids registering it anywhere else.

**The default period**: `AbstractWorldConfig#flushIntervalTicks()` returns 20 (about one second), and only `VillagerTradeCacheConfig` overrides it to 200 (about ten seconds).

**The rule behind the lock-free walk**: `tickAll` iterates `DIRTY_SET` without taking any lock. That is safe only because `markDirty` and `tickAll` both run on the main thread — that is the thread rule of this class (`markDirty` is documented as "main thread only"), and an instance added while the walk is running is simply first seen on the next tick.

**The two ways a dirty flag is cleared** (both keep the instance out of the queue):

- `flush()` succeeds → the parent class does `dirty.set(false)` (**it does not** go through `clearDirty`); the instance stays in `DIRTY_SET` for a moment and the next line of `tickAll` — `if (!isDirty())` — removes it;
- `clearDirty()` (used by `reset`) → removes it from `DIRTY_SET` directly.

---

### 4. The difference between `reload` and `reloadFromDisk` (state machine)

```
                ┌────────────────────────── reload(server) ─────────────────────────┐
new instance ──►│ ensureLoaded(server)   // loaded && same server → do nothing      │
                │        └─ forceLoad: server = …; load(); loaded = true            │
                │ onReload(server)   // apply to other systems (replay rules)       │
                │ markDirty()        // an all-default save still gets a file       │
                └───────────────────────────────────────────────────────────────────┘
                                             ↓  /ec c reload
                ┌──────────────────── reloadFromDisk(server) ───────────────────────┐
                │ flushAndAwait(server, RELOAD_AWAIT_MS)  // write pending, wait    │
                │ forceLoad(server)         // re-read the disk unconditionally     │
                │ onDiskLoad(server)        // apply + log the file name            │
                └───────────────────────────────────────────────────────────────────┘
```

**Why `reloadFromDisk` must `flushAndAwait` first**: without draining, the re-read returns the content from *before* the write, which silently rolls back what the player just changed. `ConfigRegistry.reloadAllFromDisk` fences `RuleConfig` separately again with `flushAndAwait(server, AbstractWorldConfig.RELOAD_AWAIT_MS)` — the outer fence waits for the unified config and the inner one (inside `reloadFromDisk`) waits for each config's own file, and both use the same `RELOAD_AWAIT_MS = 5000` constant, so there is only one timeout value to reason about.

**The two hooks split the work**:

| Hook | Timing | Typical use |
|---|---|---|
| `onReload` | after the replay, before `markDirty` | `RuleSwitchConfig` → replay the states into `RuleCache`; `RuleConfig` → drive all section configs |
| `onDiskLoad` | after the real disk read | apply + log ("re-read N sections from disk") |

---

### 5. Shutdown reset: the order inside `reset()`

```
reset()
  ① flush()          // submit the last state while the server is still here (non-blocking, enqueue only)
  ② server = null    // drop the server: getFilePath() now returns null → any late flush is a no-op
  ③ loaded = false
  ④ clearDirty()     // also removes it from DIRTY_SET, and keeps the old content from being written next start
  ⑤ clearData()      // clear the in-memory tree
```

**①→② cannot be swapped**: with `server` dropped first, `getFilePath()` becomes `null` and that `flush` is a no-op — the last change would be lost.

**④ cannot be skipped**: with the dirty flag left set, the next `tickAll` would serialize the **already-cleared memory** and overwrite the complete file that was just written.

---

### 6. `ConfigRegistry`: the three reload entry points

| Entry point | Caller | Behaviour |
|---|---|---|
| `reloadAll(server)` | `SERVER_STARTED` (wired by `registerStartedHook`) | `MigrationManager.migrateIfNeeded` → `reload(server)` one by one in **registration order**; a single failure logs ERROR without aborting |
| `reloadAllFromDisk(server)` | `/ec c reload` (no argument) | `RuleConfig.flushAndAwait(server, RELOAD_AWAIT_MS)` first → migrate → `reloadFromDisk` one by one; **any failure throws `RuntimeException`** (the command layer turns it into a message) |
| `reloadSpecificFromDisk(server, name)` | `/ec c reload <name>` | the same, but only the named config; an unknown name throws `IllegalArgumentException` |

Registration order = insertion order in the `ConfigRegistry` static block (`LinkedHashMap`):
`TradeIndexConfig → RuleSwitchConfig → RuleConfig → VillagerTradeCacheConfig`.
`RuleConfig` sits after `RuleSwitchConfig` and before `VillagerTradeCacheConfig` — because the section configs (including `ServerLangConfig`) are brought up together with it by `reloadChildren`.

**`RuleConfig.reloadChildren`** is the second-level driver:

```
for (ReloadableConfig child : RuleType.getReloadableConfigs())   // derived from the enum, never a hand-written list
    child.reload(server)                                         // a single failure only logs
```

So adding a rule with a config section **does not** require touching `ConfigRegistry` or `RuleConfig` — filling in `reloadableConfig` in `RuleType` is enough.

---

### 7. The full `/ec c reload` sequence

```
command (main thread)
  → ReloadMapping.reloadMappings
      ├─ validate the name (not in ConfigRegistry.getReloadableNames() → report and return 0)
      └─ ConfigRegistry.reloadAllFromDisk / reloadSpecificFromDisk
           ├─ RuleConfig.INSTANCE.flushAndAwait(server, RELOAD_AWAIT_MS)   ← exact fence
           ├─ MigrationManager.migrateIfNeeded(server)                     ← a migration may rewrite the file
           └─ each config.reloadFromDisk(server)
                ├─ flushAndAwait(server, RELOAD_AWAIT_MS)                  ← per-config fence
                ├─ forceLoad(server)
                └─ onDiskLoad(server)
```

---

<a id="中文"></a>

## 中文

> 覆盖：`AbstractWorldConfig` · `ReloadableConfig` · `ConfigRegistry` · `ConfigManager` ·
> `RuleConfig` · `RuleSwitchConfig` · `MigrationManager`（只讲"什么时候调"）。
> 写盘管线的机制在 [02](02-config-pipeline.md)；本文只讲**何时加载、何时重载、何时复位**。

---

### 1. 四个「存档内 JSON」+ 两类配置

| 配置类 | 文件（相对存档根） | `configName()` | 落盘周期 | 在 `ConfigRegistry` 里？ |
|---|---|---|---|---|
| `RuleConfig` | `villagerdetails/config.json` | `RuleConfig` | 20 tick（默认值） | ✅ |
| `RuleSwitchConfig` | `villager_details_binding.json` | `RuleSwitchConfig` | 20 tick | ✅ |
| `TradeIndexConfig` | `villagerdetails/trade_index.json` | `TradeIndexConfig` | 20 tick | ✅ |
| `VillagerTradeCacheConfig` | `villagerdetails/villager_trade_cache.json` | `VillagerTradeCacheConfig` | **200 tick** | ✅ |
| **规则细项配置**（`NoSqueezeConfig`、`FurnaceDirtyHighlightConfig`、`PortalBindingConfig` …） | 无独立文件 —— 是 `config.json` 的一个 **section** | 各自规则名 | 20 tick | ❌ **不许**注册 |
| `ServerLangConfig` / `PlayerHeadSittingConfig` 等 | 同上（section） | 各自规则名 | 20 tick | ❌ |

**两类配置的分界线**是"有没有自己的文件"：

- 有独立文件的 → 进 `ConfigRegistry`，能被 `/ec c reload <name>` 点名重载；
- 只是 section 的 → 由 `RuleConfig.reloadChildren()` 统一驱动（它们读的是 `RuleConfig` 的树，必须晚于树本身加载）。

`ConfigRegistry.verifyReloadableConfigs()` 会在启动期把这条分界线钉死：
一个规则绑定的配置如果被错注册进 `ConfigRegistry`，直接抛 `IllegalStateException`。

---

### 2. `AbstractWorldConfig`：子类只回答 5 个问题

| 子类必须实现 | 语义 |
|---|---|
| `savePath()` | 相对存档根的路径；**每次落盘都重新问**（换存档不用清缓存路径） |
| `load()` | 磁盘 → 内存；用 `currentFile()` 取路径，返回 `null` 表示"现在没有可读文件" |
| `clearData()` | 内存清空（停服复位用），**不读盘不写盘** |
| `getDataToSave()` | 交给 Gson 的对象（活引用，只在 `flush` 那一刻被遍历） |
| `configName()` | 注册表去重 / `/ec c reload` 点名 / 日志前缀 |

可覆盖的扩展点：`onReload(server)`、`onDiskLoad(server)`、`flushIntervalTicks()`（默认返回 20）。

**被 `final` 掉的方法**（顺序统一，子类不能改）：`reload` / `reloadFromDisk` / `getFilePath` / `flush`。
这四处一旦被子类改，四个配置的失败语义就会漂移。

---

### 3. 观察者队列与节流

```
static { ServerTickEvents.END_SERVER_TICK.register(_ -> AbstractWorldConfig.tickAll()); }   ← 全类唯一注册点

markDirty()  →  super.markDirty()（dirty=true） + DIRTY_SET.add(this)
tickAll()    →  globalTicks++
                DIRTY_SET 为空 → 立即 return（绝大多数 tick）
                否则对每个实例：
                  globalTicks - lastFlushedTick >= flushIntervalTicks()
                     → flush()
                     → if (!isDirty()) { DIRTY_SET.remove(this); lastFlushedTick = globalTicks; }
```

三个设计要点：

1. **为什么遍历 `DIRTY_SET` 而不是 `ALL`**：`ALL` 是"所有实例（4+ 个）"，`DIRTY_SET` 绝大多数时候是空的。
   tick 路径上"空集立即返回"比"遍历 4 个再逐个判脏"便宜，而且**新增配置不需要改 tick 逻辑**。
2. **为什么 `lastFlushedTick` 初值是 `-1`**：保证第一次进脏队列时 `globalTicks - (-1) >= interval` 立刻成立，
   于是"开服第一次改配置"会立刻写出文件（而不是等满 20 tick）。
3. **"重复注册 tick 会让周期减半"**：`globalTicks` 是静态计数，每多注册一次 `tickAll`，
   同一个真实 tick 里它就 +2 → 200 tick 的节流实际变成 100 tick。所以类注释明确禁止在别处再注册。

**默认周期**：`AbstractWorldConfig#flushIntervalTicks()` 默认返回 20（约 1 秒），只有
`VillagerTradeCacheConfig` 覆盖成 200（约 10 秒），其余三个配置用默认值。

**遍历不加锁的前提**：`tickAll` 遍历 `DIRTY_SET` 时不取任何锁。这只在 `markDirty` 与 `tickAll`
都在主线程上执行时才成立 —— 这就是本类的线程约定（`markDirty` 的注释写明"只允许主线程调用"），
遍历期间新加进来的实例本轮看不到，下一轮才会被处理。

**脏标记的两条清理路径**（都保证不留在队列里）：

- `flush()` 成功 → 父类里 `dirty.set(false)`（**不走** `clearDirty`）；实例短暂留在 `DIRTY_SET`，
  `tickAll` 下一句 `if (!isDirty())` 把它摘掉；
- `clearDirty()`（`reset` 用）→ 直接 `DIRTY_SET.remove(this)`。

---

### 4. `reload` 与 `reloadFromDisk` 的区别（状态机）

```
                ┌────────────────────────── reload(server) ─────────────────────────┐
新实例 ────────►│ ensureLoaded(server)   // loaded && server 相同 → 什么也不做      │
                │        └─ forceLoad: server = …; load(); loaded = true            │
                │ onReload(server)       // 应用到别的系统（回灌规则 / 带子配置）   │
                │ markDirty()            // 全默认值的存档也会拿到一份规范文件      │
                └───────────────────────────────────────────────────────────────────┘
                                             ↓  /ec c reload
                ┌──────────────────── reloadFromDisk(server) ───────────────────────┐
                │ flushAndAwait(server, RELOAD_AWAIT_MS)  // 先把内存改动写下去并等 │
                │ forceLoad(server)            // 无条件重读磁盘                    │
                │ onDiskLoad(server)           // 应用 + 打一条带文件名的日志       │
                └───────────────────────────────────────────────────────────────────┘

**为什么 `reloadFromDisk` 必须先 `flushAndAwait`**：不先排干的话，重新读盘读到的是"写之前"的旧内容，
等于把玩家刚才的修改**静默回滚**。外层栅栏（`ConfigRegistry.reloadAllFromDisk` 对 `RuleConfig` 的那次）
与内层栅栏（`reloadFromDisk` 里对各自文件的那次）用的是同一个常量 `RELOAD_AWAIT_MS = 5000`，
没有第二个超时值需要记。

**两类钩子的分工**：

| 钩子 | 时机 | 典型用途 |
|---|---|---|
| `onReload` | 回灌后、`markDirty` 前 | `RuleSwitchConfig` → 把状态回灌进 `RuleCache`；`RuleConfig` → 驱动所有 section 配置 |
| `onDiskLoad` | 真正读盘后 | 应用 + 日志（"已从磁盘重读 N 个 section"） |

---

### 5. 停服复位：`reset()` 的顺序

```
reset()
  ① flush()          // 趁 server 还在，把最后一份状态提交出去（不阻塞，只入队）
  ② server = null    // 摘掉 server：之后 getFilePath() 返回 null → 任何迟到 flush 都空转
  ③ loaded = false
  ④ clearDirty()     // 顺手从 DIRTY_SET 移除，且不让旧内容在下次开服被写出去
  ⑤ clearData()      // 清空内存树
```

**①→② 的顺序不能反**：先摘 `server` 的话，`getFilePath()` 变 `null`，那次 `flush` 直接空转 ——
最后一份改动就丢了。

**④ 不能省**：不清脏标记的话，下一次 `tickAll` 会把**已经清空的内存**序列化出去，覆盖掉刚写好的完整文件。

---

### 6. `ConfigRegistry`：重载的三个入口

| 入口 | 调用方 | 行为 |
|---|---|---|
| `reloadAll(server)` | `SERVER_STARTED`（由 `registerStartedHook` 挂上） | `MigrationManager.migrateIfNeeded` → 按**注册顺序**逐个 `reload(server)`；单个失败只记 ERROR 不中断 |
| `reloadAllFromDisk(server)` | `/ec c reload`（无参数） | 先 `RuleConfig.flushAndAwait(server, RELOAD_AWAIT_MS)` → 迁移 → 逐个 `reloadFromDisk`；**任一失败抛 `RuntimeException`**（命令层兜成提示） |
| `reloadSpecificFromDisk(server, name)` | `/ec c reload <name>` | 同上，但只重载点名的那个；名字不存在抛 `IllegalArgumentException` |

注册顺序 = `ConfigRegistry` 静态块里的插入顺序（`LinkedHashMap`）：
`TradeIndexConfig → RuleSwitchConfig → RuleConfig → VillagerTradeCacheConfig`。
`RuleConfig` 排在 `RuleSwitchConfig` 之后、`VillagerTradeCacheConfig` 之前 ——
因为 section 配置（含 `ServerLangConfig`）是在 `RuleConfig` 重载时被 `reloadChildren` 一起带起来的。

**`RuleConfig.reloadChildren`** 是第二级驱动：

```
for (ReloadableConfig child : RuleType.getReloadableConfigs())   // 由枚举推导，不手写清单
    child.reload(server)                                         // 单个失败只记日志
```

所以加一条带配置节的规则，**不需要**改 `ConfigRegistry` 或 `RuleConfig` —— 只要在 `RuleType` 里填上 `reloadableConfig`。

---

### 7. `/ec c reload` 的完整时序

```
命令（主线程）
  → ReloadMapping.reloadMappings
      ├─ 校验名字（不在 ConfigRegistry.getReloadableNames() 里 → 报错返回 0）
      └─ ConfigRegistry.reloadAllFromDisk / reloadSpecificFromDisk
           ├─ RuleConfig.INSTANCE.flushAndAwait(server, RELOAD_AWAIT_MS)   ← 精确栅栏
           ├─ MigrationManager.migrateIfNeeded(server)                     ← 迁移可能整份重写文件
           └─ 逐个 config.reloadFromDisk(server)
                ├─ flushAndAwait(server, RELOAD_AWAIT_MS)                  ← 每个配置自己的栅栏
                ├─ forceLoad(server)
                └─ onDiskLoad(server)
```
