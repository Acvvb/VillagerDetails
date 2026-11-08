# Threading Model

The four execution lines, pool anatomy and rejection policy, shutdown ordering, frame slicing, performance magnitudes, and the cross-thread data contract of VillagerDetails.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> **Covers**: `ModThreadPool` · `SnapshotThreadPool` · `QueryThreadPool` · `ServerThreadManager` ·
> `ServerThreadShutdownTask` · `ModContext`, plus the "frame slicing" pattern (`TradeRerollScheduler` is
> the sample; only the mechanism is described).
> **Related**: the persistence line is in [02](02-config-pipeline.md); tick driving is in [03](03-config-lifecycle.md).

---

### 1. The four execution lines

| Execution line | Created by | Thread name | Size | Priority / daemon | What it may touch |
|---|---|---|---|---|---|
| **Main thread** | Vanilla | Vanilla | 1 | Vanilla | everything (world, entities, packet sending, rules, in-memory config tree) |
| **Snapshot pool** | `SnapshotThreadPool.init()` | `VillagerDetails-SnapshotThread-Worker-N` | core 2 / max 4 / queue **1024** | `NORM-1` / daemon | **must not** touch the world or entities; handles pure-data snapshots only |
| **Query pool** | `QueryThreadPool.init()` | `VillagerDetails-QueryThread-Worker-N` | core 1 / max 2 / queue **16** | `NORM-1` / daemon | same as above; read-only index + produces pure data |
| **Config writer** | `AsyncConfigWriter.ensureWorker()` | `VillagerDetails-ConfigWriter` | single thread | `MIN` / daemon | may touch `String` + `Path` only, never a game object |

All three background lines are **daemon** threads: real data safety comes from "shutdown hook plus draining", not from "a thread keeping the JVM alive".

The pools are assembled in a single place: `ServerThreadManager.init()` calls `SnapshotThreadPool.init()` (which returns the pool instance) and right after it calls `ModContext.setServerPool(serverPool)`, so `ModContext.getServerPool()` is non-null for the whole server lifetime. Business code that submits snapshots still guards the "pool unavailable" case, but that branch now only fires when the server is absent or the pool is already shut down, and it warns once through an `AtomicBoolean` instead of on every submit.

---

### 2. Anatomy of a pool (`ModThreadPool`)

```
ctor: new ThreadPoolExecutor(core, max, 60s, LinkedBlockingQueue(queueCapacity),
                             threadFactory(daemon, NORM-1), handler)
                                                       │
                     handler = (WARN queue capacity / queued count first) → then delegate to the
                               policy it was given
                                                       │
        snapshot / query pass rejectWithWarn(): WARN + throw RejectedExecutionException
        the default (no-arg ctor) passes DISCARD_WITH_WARN: do nothing (the task is dropped)
```

The two entry points have **different failure semantics, and both are deliberate**:

| Entry point | Pool already shut down | Queue full | Task throws `Throwable` | How callers use it |
|---|---|---|---|---|
| `execute(Runnable)` | logs DEBUG, returns `false` | logs WARN, returns `false` | only the pool's own uncaught handling (never reaches the caller) | use it when you need the "was it accepted" answer |
| `submit(Callable<T>)` | `future.completeExceptionally(...)` | `future.completeExceptionally(...)` | `future.completeExceptionally(...)` | **the future always completes**, so `join()` never hangs |

The contract behind the `submit` row is "a future is never left incomplete": a shut-down pool, a full queue and a `Throwable` escaping the task all funnel into `completeExceptionally(...)`, which is what lets callers `join()` without a timeout.

#### Why capacity and rejection policy differ

| Pool | Queue | Cost of losing a rejected task | Policy |
|---|---|---|---|
| Snapshot pool | 1024 | **data loss** (only re-captured on the next chunk unload) | throw, so the caller falls back on `false` (business side logs + drops) |
| Query pool | 16 | **one lost query** (the player just types the command again) | throw, so the caller can tell the player "system busy" |
| Generic default | passed in by the caller | defined by the caller | `DISCARD_WITH_WARN` (only for tasks where losing one hurts no correctness) |

**Why two business pools** (instead of one big queue): the two task classes have completely different value. With a shared queue, a player hammering `/ec c TradeSearch` a few times would push out the "snapshot captured on chunk unload" tasks — and that is **real data loss** — whereas losing a query only means "this lookup did not happen". Hence the big queue for the snapshot pool and the small one for the query pool: the capacity gap (1024 vs 16) *is* that trade-off.

---

### 3. Shutdown ordering

`ServerThreadManager`'s `PRE_SHUTDOWN_HOOKS` is a `CopyOnWriteArrayList`, so **execution order = registration order**.

```
SERVER_STOPPING
  ① runPreShutdownHooks()            ← pools / writer thread are still alive here
       hooks registered by ServerThreadShutdownTask:
         AbstractWorldConfig.flushAll()            flush each config: main-thread serialize + enqueue (non-blocking)
         AsyncConfigManager.flushAndShutdown(3000) drain the write queue + stop the writer (two drain rounds inside)
  ② QueryThreadPool.shutdown()       queries are one-shot, losing one is cheap → shut down first
  ③ SnapshotThreadPool.shutdown()    waits up to 1 second for queued snapshots, then runs the rest on the
                                     calling thread → shut down last
  ↓
another SERVER_STOPPING listener (ServerStoppingListener)
  AbstractWorldConfig.resetAll() + RuleCache.clearServer()
the third SERVER_STOPPING listener (ServerSessionStates)
  SessionState.clearSession() on each state
```

**Why each step sits where it does**

| Step | What breaks if it moves |
|---|---|
| Hooks must run **before** the pools close | after shutdown, `execute` always returns `false`, so config can no longer be submitted (dirty data stays in memory) |
| `flushAndShutdown` must come after every `save` | stopping the writer first leaves later `submit` calls with only "lazily restart" or "drop" |
| Query pool first, snapshot pool second | the other way round, a queued query would wait for a snapshot that will never be enqueued again, and the slower snapshot tail would sit in the middle of shutdown |
| `resetAll` must come after the hooks | `reset` calls `flush()` first, and the writer is already stopped by then; and `reset` clears the dirty flag, so doing it early equals losing the change |

**Why query pool first and snapshot pool last**: a query task reads what snapshot tasks have already written into the index, so the query pool has to stop before the snapshot pool — otherwise a queued query waits for a snapshot that will never be enqueued again. The snapshot pool goes last for two reasons: its tasks may still submit config writes (so the writer must outlive them), and it is the slow tail — its wait can time out and move the leftovers onto the main thread, which is the safest thing to do once everything else has been flushed and closed.

**What `shutdown()` actually promises** (both pools use the same `ModThreadPool#shutdown`):

- If the queue is empty and no task is running, it calls `shutdownNow()` directly and returns — nothing is queued and nothing is running, so there is nothing to lose.
- Otherwise it first calls `expandPoolForShutdown()`: only when the backlog exceeds about `currentCore * THRESHOLD` pending tasks (`THRESHOLD = 100`) does it raise the core size to the current maximum. It only ever grows — the target never drops below the current maximum — so a bigger backlog never means fewer threads.
- Then it calls `shutdown()` and waits at most `AWAIT_TERMINATION_SECONDS = 1L` second.
- **If that wait times out, every remaining queued task runs on the calling thread** (the main thread during shutdown), one after another. A task that throws is logged and skipped, the drain loop continues, and there is no time limit on the drain: an upper bound would reintroduce partial task loss.
- **No queued task is dropped.** That is the contract of this method. A future pool that legitimately wants to drop work must call `shutdownNow()` at the call site instead of adding a switch here.

Queue-full rejection (section 5) is a different path: it never falls back to running the task on the caller's thread. The only drain onto the caller is the one above, and it applies to tasks that were already accepted.

**Why `flushAndShutdown` drains twice**: the first round is "drain → stop the thread", but if a `submit` lands exactly in the window between "queue looks empty" and "`stop` is set", `ensureWorker()` sees a live thread and returns, then the writer exits because of `stop` — and that content would never be written, **silently and without a log line**. So the second round closes the thread, re-checks `hasPending()`, and if anything is left it starts a thread again, re-queues every `PENDING` key on `NOTIFY`, drains again and stops again.

---

### 4. Frame slicing (the pattern belongs to the framework, the implementation to the business)

`TradeRerollScheduler` is the standard answer to "cannot finish inside one tick, and must run on the main thread":

```
submit (main thread)  TASKS: Map<villagerUUID, State> (LinkedHashMap, owned by the main thread)
                        ├─ same villager submitted again → old task cancelled at once (only one kept)
                        └─ cap MAX_ACTIVE_TASKS = 64
each tick (END_SERVER_TICK, main thread)
                        ├─ read the budget once per tick (attemptsPerTick(), reads rule state)
                        └─ for each task: run the attempts inside the budget
                               ├─ each attempt is seeded independently (randomSalt + attempt)
                               ├─ look the entity up live via level.getEntity(uuid) (no Villager reference held)
                               ├─ entity gone → cancel the task
                               └─ hit / attempts exhausted → finish (write back or notify)
```

Four **conventions worth copying**:

1. **A task holds pure data only** (UUID / dimension / predicate / salt / cap), never a strong entity reference — otherwise the entity leaks after a chunk unload.
2. **Read each rule once per tick** (all tasks in the same tick share the budget), instead of re-reading the cache inside the loop.
3. **Progress lives in the task** (`attempts` / `lastOffers`), never on the entity; the entity is not modified by a single byte while the simulation runs.
4. **No time valve**: work stops when the budget is exhausted, not when one attempt happens to be slow — the per-tick cost ceiling is `tasks × attempts per tick × cost per attempt`, and you tune it through the rule tier or the task cap.

---

### 5. Performance magnitudes

| Operation | Magnitude | Notes |
|---|---|---|
| One snapshot parse (`put`) | **microseconds** | a few dozen trades turned into pure data + 5 gates + one CHM put; only a few map operations inside the lock |
| One trade query | **milliseconds** | O(index entries × trades per villager). With tens of thousands of index entries it is "tens of milliseconds", which is why it must leave the main thread |
| One `flush` (main-thread part) | **milliseconds** | the whole tree serialized by Gson into a `String`; disk I/O is not part of it |
| One `writeString` | **sub-millisecond to milliseconds** | depends on file size and disk; the payload is a few KB to a few MB of JSON |
| `canSendTo` | **sub-microsecond to microseconds** | one mute-table lookup plus Fabric's `canSend` capability probe |

**Behaviour when the queue is full**: `execute` returns `false` (snapshot: the business side drops it and logs; query: the player is told "busy"); `submit` returns an already-exceptionally-completed future. **There is no "run it inline on the calling thread" fallback** — a rejection stays a rejection and the caller decides what to do with it.

---

### 6. Cross-thread data conventions

| Safe to pass | Never pass |
|---|---|
| `UUID` / primitives / `String` / `ImmutableList` snapshots / custom `record`s (scalars only) | `Level` / `Entity` / `ItemStack` / `MerchantOffer` / `Component` / `MinecraftServer` |
| `BlockPos` (already `immutable()`), `ResourceKey<Level>`, `Identifier` (vanilla immutable value types) | **live references** to mutable collections (`HashMap` / `ArrayList`) |
| `CompletableFuture` (callbacks switch back to the main thread via `server.execute(...)`) | any `Handler` / `Screen` / client-side object |

**Why these rules**: the main thread may mutate those objects while you read them (`ConcurrentModificationException` / half-updated state), and constructing or accessing a `Component` / `Screen` itself requires the main thread. `VillagerTradeSnapshot` and `VillagerTradeSearchHandler.SearchResult` on the business side are the reference implementations of this contract.

---

<a id="中文"></a>

## 中文

> 覆盖：`ModThreadPool` · `SnapshotThreadPool` · `QueryThreadPool` · `ServerThreadManager` ·
> `ServerThreadShutdownTask` · `ModContext`，以及"分帧"这一模式（以 `TradeRerollScheduler` 为样例，只讲机制）。
> 相关：写盘那条线在 [02](02-config-pipeline.md)，tick 驱动在 [03](03-config-lifecycle.md)。

---

### 1. 四条执行线

| 执行线 | 谁创建 | 线程名 | 规模 | 优先级 / daemon | 能碰什么 |
|---|---|---|---|---|---|
| **主线程** | 原版 | 原版 | 1 | 原版 | 一切（世界、实体、发包、规则、配置内存树） |
| **快照池** | `SnapshotThreadPool.init()` | `VillagerDetails-SnapshotThread-Worker-N` | core 2 / max 4 / 队列 **1024** | `NORM-1` / daemon | **不能**碰世界与实体；只处理纯数据快照 |
| **查询池** | `QueryThreadPool.init()` | `VillagerDetails-QueryThread-Worker-N` | core 1 / max 2 / 队列 **16** | `NORM-1` / daemon | 同上；只读索引 + 产出纯数据 |
| **配置写线程** | `AsyncConfigWriter.ensureWorker()` | `VillagerDetails-ConfigWriter` | 单线程 | `MIN` / daemon | 只能碰 `String` + `Path`，不碰任何游戏对象 |

三条后台线都是 **daemon**：真正的数据安全靠"停服钩子 + 关机钩子排干"，不靠"JVM 被线程吊住"。

池的装配只有一个地方：`ServerThreadManager.init()` 调 `SnapshotThreadPool.init()`（返回池实例），紧接着
`ModContext.setServerPool(serverPool)`，所以 `ModContext.getServerPool()` 在整个服务端生命周期内都非 null。
提交快照的业务代码仍然保留"池不可用"分支，但它如今只在"没有 server / 池已关闭"时命中，
并且用 `AtomicBoolean` 只报一次 WARN，不会每次提交都刷屏。

---

### 2. 一个池的解剖（`ModThreadPool`）

```
构造：new ThreadPoolExecutor(core, max, 60s, LinkedBlockingQueue(queueCapacity),
                             threadFactory(daemon, NORM-1), handler)
                                                     │
                         handler = (先 WARN 队列容量/排队数) → 再委托给传入的策略
                                                     │
        snapshot / query 传的是 rejectWithWarn()：WARN + throw RejectedExecutionException
        默认（无参构造）传的是 DISCARD_WITH_WARN：什么都不做（任务被丢弃）
```

两个入口的失败语义**不同，且都是刻意的**：

| 入口 | 池已关闭 | 队列满 | 任务内抛 `Throwable` | 调用方怎么用 |
|---|---|---|---|---|
| `execute(Runnable)` | 打 DEBUG，返回 `false` | 打 WARN，返回 `false` | 只有线程池自己的 uncaught 处理（不回到调用方） | 需要"能不能入队"这个答案时用 |
| `submit(Callable<T>)` | `future.completeExceptionally(...)` | `future.completeExceptionally(...)` | `future.completeExceptionally(...)` | **future 永远会完成**，`join()` 不会挂死 |

`submit` 那一行的契约是"绝不留下未完成的 future"：池已关闭、队列满、任务内抛出 `Throwable`
三种情况都收敛到 `completeExceptionally(...)`，所以调用方可以不带超时地 `join()`。

#### 容量与拒绝策略为什么不一样

| 池 | 队列 | 拒绝后丢失的代价 | 策略 |
|---|---|---|---|
| 快照池 | 1024 | **丢数据**（下次区块卸载才会重新抓到） | 抛异常，让调用方 `false` 兜底（业务侧打日志 + 丢弃） |
| 查询池 | 16 | **只丢一次查询**（玩家再敲一次命令就行） | 抛异常，让调用方给玩家"系统繁忙" |
| 通用默认 | 调用方传 | 由调用方定义 | `DISCARD_WITH_WARN`（只适合"丢一条不影响正确性"的任务） |

**为什么拆两个业务池**（而不是一个大队列）：两类任务的价值完全不同。合用一个队列时，
玩家连按几次 `/ec c TradeSearch` 就能把"区块卸载抓到的快照任务"挤掉 —— 那是**真丢数据**；
而查询任务挤掉只是"这次没查到"。所以快照池给大队列、查询池给小队列，
容量差异（1024 vs 16）本身就是这个取舍的体现。

---

### 3. 停服时序

`ServerThreadManager` 的 `PRE_SHUTDOWN_HOOKS` 是 `CopyOnWriteArrayList`，**执行顺序 = 登记顺序**。

```
SERVER_STOPPING
  ① runPreShutdownHooks()            ← 此时池子/写线程都还活着
       ServerThreadShutdownTask 登记的钩子：
         AbstractWorldConfig.flushAll()          逐个 flush：主线程序列化 + 入队（不阻塞）
         AsyncConfigManager.flushAndShutdown(3000) 排干写队列 + 停写线程（内部两轮 drain）
  ② QueryThreadPool.shutdown()       查询是一次性的，丢了不心疼 → 先关
  ③ SnapshotThreadPool.shutdown()    最多等队列里的快照 1 秒，超时把剩余的拉回调用线程 → 后关
  ↓
SERVER_STOPPING 的另一个监听者（ServerStoppingListener）
  AbstractWorldConfig.resetAll() + RuleCache.clearServer()
SERVER_STOPPING 的第三个监听者（ServerSessionStates）
  各 SessionState.clearSession()
```

**每一步为什么在这个位置**

| 步骤 | 不这么放会怎样 |
|---|---|
| 钩子必须在关池**之前** | 关池后 `execute` 一律返回 `false`，配置就再也提交不进去（脏数据留在内存里） |
| `flushAndShutdown` 必须排在所有 `save` 之后 | 先关写线程，后续 `submit` 只能懒重启或直接丢 |
| 查询池先关、快照池后关 | 反过来的话会留下"查询在等一份再也不会被排进来的快照"，而且快照池那段更慢的尾巴会卡在停服中间 |
| `resetAll` 必须在钩子之后 | `reset` 内部会先 `flush()`，此时写线程已经停了；且 `reset` 会清脏标记，提前做等于丢改动 |

**为什么查询池先关、快照池后关**：查询任务读的是快照写进索引之后的结果，所以查询池必须先于快照池停下 ——
否则一个还在排队的查询会等一份再也不会有人排进来的快照。快照池放最后有两个理由：它的任务可能还要
`submit` 配置写入（写线程必须活得更久），而它又是更慢的尾巴 —— 等 1 秒可能超时、超时会把剩余任务拉回主线程，
把这段最慢的动作放在所有收尾动作之后最安全。

**`shutdown()` 到底承诺了什么**（两个池共用 `ModThreadPool#shutdown`）：

- 队列空且没有活跃任务 → 直接调 `shutdownNow()` 返回（既没排队也没在跑，没有可丢的东西）。
- 否则先调 `expandPoolForShutdown()`：只有积压超过约 `currentCore * THRESHOLD` 条待执行任务（`THRESHOLD = 100`）
  时才把 core 线程数提到当前 maximum。它只扩容、不缩容 —— 目标恒为当前上限，积压再大也不会变成更少线程。
- 再调 `shutdown()`，并最多等 `AWAIT_TERMINATION_SECONDS = 1L` 秒。
- **超时后，队列里剩下的每个任务都在调用线程（停服时 = 主线程）上逐个执行**：单个任务抛异常只记日志、
  跳过、继续处理剩余任务；拉回路径**不设时间上限**（加上限就等于重新引入"部分任务丢失"）。
- **不丢任何已入队的任务**，这就是这个方法的契约。未来若有池想丢弃剩余任务，请在调用侧走 `shutdownNow()`，
  而不是在这里加开关。

第 5 节讲的"队列满拒绝"是另一条路径：拒绝不会退回调用线程同步执行。唯一的拉回就是上面这条，
作用于**已经被接受**的任务。

**为什么 `flushAndShutdown` 要两轮 drain**：第一轮"排干 → 停线程"，但如果某个 `submit`
恰好落在「判空之后、置 `stop` 之前」这个窗口里，`ensureWorker()` 看到线程还活着就直接返回，
随后写线程因 `stop` 退出 —— 那条内容再也没人写，**而且全程没有日志**。
所以第二轮会：关线程 → 复查 `hasPending()` → 有残留就再起线程、把 `PENDING` 的 key 重投 `NOTIFY` → 再排干 → 再关。

---

### 4. 分帧（这个模式属于框架，实现属于业务）

`TradeRerollScheduler` 是"不能在单 tick 里做完、又必须在主线程做"的标准解法：

```
提交（主线程）  TASKS: Map<villagerUUID, State>（LinkedHashMap，主线程独占）
                 ├─ 同一村民再提交 → 旧任务立刻取消（只留一个）
                 └─ 上限 MAX_ACTIVE_TASKS = 64
每 tick（END_SERVER_TICK, 主线程）
                 ├─ 每 tick 只读一次预算（attemptsPerTick()，读规则状态）
                 └─ 对每个任务：跑预算内的 attempt
                        ├─ 每次 attempt 独立播种（randomSalt + attempt）
                        ├─ 用 level.getEntity(uuid) 现查实体（不持有 Villager 引用）
                        ├─ 实体没了 → 取消任务
                        └─ 命中 / 次数用尽 → 收尾（写回或提示）
```

四条**可以照抄的约定**：

1. **任务只存纯数据**（UUID / 维度 / 谓词 / 盐 / 上限），绝不强引用实体 —— 否则区块卸载后实体泄漏。
2. **每 tick 只读一次规则**（同一 tick 里所有任务共用预算），避免在循环里反复读缓存。
3. **进度状态在任务里**（`attempts` / `lastOffers`），不在实体上；模拟期间实体一个字节不动。
4. **没有时间阀门**：跑满预算才停，不会因为某一次 attempt 慢就提前收工 —— 单 tick 成本上限是
   `任务数 × 每 tick 次数 × 单次成本`，要控就调规则档位或任务上限。

---

### 5. 性能量级

| 操作 | 量级 | 说明 |
|---|---|---|
| 一次快照解析（`put`） | **微秒级** | 几十条交易转纯数据 + 5 道闸 + 一次 CHM put；锁内只有几次 map 操作 |
| 一次交易查询 | **毫秒级** | O(索引条数 × 每村民交易数)。几万条索引时是"几十毫秒"，所以必须离开主线程 |
| 一次 `flush`（主线程部分） | **毫秒级** | 整份树 Gson 序列化成 String；磁盘 I/O 不在这里 |
| 一次 `writeString` | **亚毫秒~毫秒** | 取决于文件大小与磁盘；写的是几 KB~几 MB 的 JSON |
| `canSendTo` | **亚微秒~微秒** | 一次静音表查找 + Fabric 的 `canSend` 能力探测 |

**队列满时的行为**：`execute` 返回 `false`（快照：业务侧丢弃并打日志；查询：提示玩家"繁忙"）；
`submit` 返回已异常完成的 future。**队列满时没有"退回调用线程同步执行"的兜底** ——
拒绝就是拒绝，怎么处理由调用方决定。

---

### 6. 跨线程数据传递约定

| 可以传 | 不可以传 |
|---|---|
| `UUID` / 基本类型 / `String` / `ImmutableList` 快照 / 自定义 `record`（只含标量） | `Level` / `Entity` / `ItemStack` / `MerchantOffer` / `Component` / `MinecraftServer` |
| `BlockPos`（已 `immutable()`）、`ResourceKey<Level>`、`Identifier`（原版不可变值类型） | 可变集合（`HashMap` / `ArrayList`）的**活引用** |
| `CompletableFuture`（回调统一 `server.execute(...)` 切回主线程） | 任何 `Handler` / `Screen` / 客户端对象 |

**约定背后的原因**：主线程可能在你读的同时改这些对象（`ConcurrentModificationException` / 半更新状态），
而且 `Component` / `Screen` 的构造与访问本身要求主线程。业务侧的 `VillagerTradeSnapshot` 与
`VillagerTradeSearchHandler.SearchResult` 都是这条约定的落地样板。
