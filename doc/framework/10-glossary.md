# Framework Glossary

The definition of record for framework terms, one authoritative entry per term with a pointer to the chapter that owns the mechanism.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> **This chapter is the defining source for framework terms.** The business docs (`doc/business/04-glossary.md`) only link here and never repeat a definition.
> Every entry gives "a one-sentence definition + the authoritative location (which chapter, which section)". **A term is explained only once, here.**

---

### 1. Threads and execution lines

| Term | Definition | Where |
|---|---|---|
| **Main thread / server thread** | The only thread that may safely read and write the world and entities and send packets; the vanilla tick thread | [01](01-threading.md) §1 |
| **Snapshot pool** | The async pool dedicated to "writing the trade index" (queue 1024 / core2-max4) | [01](01-threading.md) §1 |
| **Query pool** | The async pool dedicated to "reading the trade index" (queue 16 / core1-max2); kept apart from the snapshot pool so queries cannot squeeze out writes | [01](01-threading.md) §1 |
| **Config write thread** | A single-writer thread that does nothing but `String → file` I/O | [02](02-config-pipeline.md) §1 |
| **daemon threads** | All three background threads are daemons: data safety comes from draining on shutdown / halt hooks, not from keeping the JVM alive | [01](01-threading.md) §1 |
| **Rejection policy (RejectedExecutionHandler)** | The fallback when a queue is full or a pool is closed; the snapshot and query pools "WARN + throw", handing the caller `false` | [01](01-threading.md) §2 |
| **Frame slicing** | The pattern that slices work "too big for one tick and yet mandatory on the main thread" into per-tick budgets | [01](01-threading.md) §4 |
| **pre-shutdown hook** | Wrap-up work that must run **before** the pools close (config draining), executed in registration order | [01](01-threading.md) §3 |

---

### 2. The write pipeline

| Term | Definition | Where |
|---|---|---|
| **Dirty flag (dirty)** | An `AtomicBoolean`: there are uncommitted changes; `markDirty()` only sets the flag and never touches the disk | [02](02-config-pipeline.md) §1 |
| **Commit (flush)** | The main-thread side action: **serialize on the calling thread + enqueue**. It does not mean "already on disk" | [02](02-config-pipeline.md) §1 |
| **Persist (writeOne)** | The write-thread side action: a temporary file + `ATOMIC_MOVE` actually writes the bytes | [02](02-config-pipeline.md) §1 |
| **Coalesce / debounce** | Consecutive commits to the same path keep only the newest content; a single drain writes once | [02](02-config-pipeline.md) §3 |
| **PENDING** | `ConcurrentHashMap<Path, String>`: content waiting to be written (a later write replaces an earlier one = coalescing) | [02](02-config-pipeline.md) §3 |
| **NOTIFY** | `LinkedBlockingQueue<Path>`: only wakes the write thread; duplicates and stale entries are allowed | [02](02-config-pipeline.md) §3 |
| **inFlight** | The file the write thread is currently writing; `awaitFile` uses it to tell "is there still an in-flight write" | [02](02-config-pipeline.md) §2 |
| **drain** | Spins until "everything pending is cleared"; a timeout only logs ERROR, it never waits forever | [02](02-config-pipeline.md) §5 |
| **awaitFile** | The precise fence before reading from disk: waits until the given file has neither pending nor in-flight writes | [02](02-config-pipeline.md) §5 |
| **batch** | Coalesces a group of changes into one commit; `flush` is suppressed inside the block, and an abnormal exit still commits | [02](02-config-pipeline.md) §5 |
| **Atomic write** | Write a temporary file in the same directory → `ATOMIC_MOVE` replaces it; on failure it falls back to a plain move | [02](02-config-pipeline.md) §6 |

---

### 3. Config lifecycle

| Term | Definition | Where |
|---|---|---|
| **Observer queue (DIRTY_SET)** | The set that only `markDirty`-ed config instances enter; `tickAll` iterates it alone | [03](03-config-lifecycle.md) §3 |
| **globalTicks / lastFlushedTick** | The global tick counter and each instance's last persist tick; the difference between them implements throttling | [03](03-config-lifecycle.md) §3 |
| **flushIntervalTicks** | The persist period each instance declares for itself (20 by default, 200 for the villager index) | [03](03-config-lifecycle.md) §3 |
| **Replay on startup (reload)** | `ensureLoaded → onReload → markDirty`: applies the disk values to the system and guarantees the file exists | [03](03-config-lifecycle.md) §4 |
| **Reread from disk (reloadFromDisk)** | `flushAndAwait → forceLoad → onDiskLoad`: drain first, then reread the whole file | [03](03-config-lifecycle.md) §4 |
| **Reset (reset)** | Shutdown wrap-up: flush → detach the server → clear dirty → clear memory | [03](03-config-lifecycle.md) §5 |
| **section config** | A rule's detail config with no file of its own, just one node of `config.json`; driven by `RuleConfig.reloadChildren` | [03](03-config-lifecycle.md) §1 |

---

### 4. Session and package scanning

| Term | Definition | Where |
|---|---|---|
| **Session state (SessionState)** | The cache contract for "lives only inside one connected session": `clearSession()` / `clearPlayer(uuid)` | [04](04-session-state.md) §1 |
| **Package scanning** | Discovering implementation classes by package name through reflection; `ScanService` builds the index once, `ChannelScanner` instantiates and aggregates failures | [04](04-session-state.md) §2 |
| **Role interface** | One interface per directory (`ClientboundChannel` / `ServerReceiver` / `SyncEvents` / `SessionState` …); only its concrete implementations are processed | [04](04-session-state.md) §2 |
| **The `INSTANCE` convention** | `public static final Xxx INSTANCE` + a private constructor; scanning prefers that field and falls back to a no-arg constructor | [04](04-session-state.md) §2 |
| **Half registration** | A class that was scanned but threw during initialization; it counts as a `Failure` and never appears in the "registered" list | [04](04-session-state.md) §6 |
| **The `SCAN_PACKAGES` allowlist** | `ScanService` indexes only packages on the allowlist; asking for a package outside it throws `IllegalStateException`, so startup fails loudly | [04](04-session-state.md#2-扫包机制三层) §2 |

---

### 5. Rule framework

| Term | Definition | Where |
|---|---|---|
| **Tier (permission tier)** | The level taken when the state value is `true/ops/master/admin/owner`; it is both the switch and "who may trigger it" | [05](05-rule-framework.md) §1 |
| **Permission node** | `villagerdetails:c.<lowercase register name>` (the effective check for players) / `villagerdetails:c.base` (the `/ec` root permission) | [05](05-rule-framework.md) §2 |
| **`hasLevel`** | Looks only at the source's own permission level and **ignores permission nodes** — command admission uses this path, so a permissions plugin cannot widen it | [05](05-rule-framework.md) §2 |
| **`requirement`** | The admission predicate of a rule command: rule off → false; list-config rule → OWNERS; otherwise → compare by tier | [05](05-rule-framework.md) §2 |
| **canonicalize** | The first gate on a state value: empty values are rejected / hits in the predefined list pass / custom lists pass verbatim | [05](05-rule-framework.md) §3 |
| **State validator (RuleStateValidator)** | The second gate: format and range; a pure function with no side effects, applied before the write | [05](05-rule-framework.md) §3 |
| **Short-circuit** | `setState` returns immediately when the value did not change: no write, no persist, **no callback** | [05](05-rule-framework.md#2-rulecache五个方法--两条红线) §2 |
| **Quick switches (quickSwitches)** | A rule's legal value list; if it contains a non-switch word, the whole rule is treated as a "custom list" (any text passes) | [05](05-rule-framework.md) §1 |
| **List-config rule (RuleConfigKind.LIST)** | A rule that maintains a name / value table in addition to the switch; its command is hard-wired to OWNERS and its content has to be sent to clients | [05](05-rule-framework.md) §6 |
| **Rule descriptor (RuleDescriptor)** | The rule view sent to the client: translated plain text + state + allowed values + list templates | [05](05-rule-framework.md) §5 |
| **Template primitives** | `ListTemplate(plain, valueKind, idSource)`: the client only knows these shapes, never rule semantics | [05](05-rule-framework.md) §5 |
| **Value control (ValueKind)** | Which control the "value" column of a list page uses (plain name list / number …) | [05](05-rule-framework.md) §5 |

---

### 6. Network

| Term | Definition | Where |
|---|---|---|
| **Payload type (PayloadType)** | An entry in `PayloadTypeRegistry` (id + codec); **must be registered once on each physical side** | [07](07-network-framework.md) §2 |
| **payload** | The definition of one packet: `PayloadId` + `CODEC`; whether it implements `ClientboundPayload` / `ServerboundPayload` decides the direction | [07](07-network-framework.md) §3 |
| **Channel (SyncChannel)** | The **send** API of one S→C sync; the project's only `ServerPlayNetworking.send` lives in `sendTo` | [07](07-network-framework.md) §4 |
| **Protocol channel (ProtocolChannel)** | A channel exempt from muting but not from "is it installed" (used by the handshake); the marker interface and `isProtocolChannel()` must agree | [07](07-network-framework.md) §4 |
| **Mute** | The client unilaterally turns a channel's display off; the server skips scanning and payload building in `canSendTo` | [07](07-network-framework.md) §7 |
| **Handshake** | The client reports the mod version → the server judges it against constants (blacklist / lower bound) → ack; no handshake means no kick | [07](07-network-framework.md) §6 |
| **Protocol version (SyncProtocol.VERSION)** | The shape version of the descriptor / sync protocol; a shape change means a version bump + a new payload id | [07](07-network-framework.md) §6 |
| **Capability probe (canSend)** | `ServerPlayNetworking.canSend`: whether the peer has this mod; every send goes through it first | [07](07-network-framework.md) §4 |

---

### 7. Concurrency semantics

| Term | Definition | Where |
|---|---|---|
| **Epoch** | The number for "the whole index was replaced"; +1 on a clear, recorded when a snapshot is taken and compared when writing, blocking late writes from before the swap | [01](01-threading.md) §6 / business [`trade-search.md`](../business/03-deep-dives/trade-search.md) |
| **Tombstone** | The record of the moment a villager died; it blocks a snapshot write that lands after death | same as above |
| **`LAST_SNAPSHOT`** | Each villager's "latest snapshot moment already written"; only a strictly newer one is let through, preventing out-of-order overwrites | same as above |
| **`clearedAt`** | The moment of the most recent full clear; a snapshot older than it is dropped | same as above |
| **Second check inside the lock** | Gates 3/4/5 of `put` must complete inside `SESSION_LOCK`, otherwise every check is merely a check-then-act window | same as above |

> The **implementation** of epoch / tombstone / `LAST_SNAPSHOT` / `clearedAt` is business code
> (`VillagerTradeCache`), so the authoritative document is the business-side deep dive; this section only gives
> the one-sentence framework view.

---

### 8. Mixin

| Term | Definition | Where |
|---|---|---|
| **Injection point** | `@Inject`'s `HEAD` / `TAIL` / `RETURN`, or the call site of a `@Redirect` | [08](08-mixin-inventory.md) §4 |
| **cancellable** | The injection point can cancel vanilla execution (= it really changes control flow) | [08](08-mixin-inventory.md) §4 |
| **`@Accessor` / `@Invoker`** | Exposes a private field / method; changes no behaviour, but breaks as soon as the field is renamed | [08](08-mixin-inventory.md) §3 |
| **refmap** | The "name mapping" generated at compile time that keeps injection points matched across obfuscation | [08](08-mixin-inventory.md) §4 |
| **`defaultRequire`** | An injection point must match ≥1 target by default; no match means a startup failure | [08](08-mixin-inventory.md) §1 |

---

### 9. Version adaptation

| Term | Definition | Where |
|---|---|---|
| **Version layer** | The two same-package, same-name implementations under `v26.2/` / `v26.3/`, one of which the build picks | [09](09-version-adapter.md) §1 |
| **Move down** | Moving "code that does not compile" from `common` into the version layer (rule: does not compile → move down) | [09](09-version-adapter.md) §1 |
| **Flatten** | Hiding a version difference behind an `@Accessor` / adapter class so call sites stay version-agnostic | [09](09-version-adapter.md) §3 |
| **Source-set splicing** | The build script merges `common/src/*` and `vXX/src/*` into one artifact according to `mcVersion` | [09](09-version-adapter.md) intro |

---

### 10. Easily confused terms (sort these out before reading code)

| Word | Two meanings | How to tell them apart |
|---|---|---|
| **flush** | ① the main thread "commits / enqueues" (`AbstractAsyncConfig#flush`) ② drains and stops the write thread (`flushAndShutdown`) | ① non-blocking, does not wait for the disk; ② spins and waits |
| **clear** | ① clearing session memory (`SessionState.clearSession`) ② clearing config memory data (`clearData`) | ① triggered by events; ② only inside `reset` |
| **dirty** | ① the config has uncommitted changes (dirty flag) ② the furnace "cannot burn its input" (business concept) | completely different contexts; do not mix them up |
| **sync** | ① network S→C push ② persist (writing memory to disk) | ① `SyncChannel`; ② `flush` |
| **tier** | ① a rule's permission level ② a numeric step such as the simulation count per tick | ① permission semantics; ② a pure number |
| **snapshot** | ① the trade snapshot (business data structure) ② `LAST_SNAPSHOT` (the moment table) | ① data; ② metadata |

---

<a id="中文"></a>

## 中文

> **本文是框架术语的定义方**。业务文档（`doc/business/04-glossary.md`）只做链接，不重复定义。
> 每条给「一句话定义 + 权威位置（第几篇第几节）」。**同一术语只在这里解释一次。**

---

### 1. 线程与执行线

| 术语 | 定义 | 位置 |
|---|---|---|
| **主线程 / 服务端线程** | 唯一能安全读写世界、实体、发包的线程；原版 tick 线程 | [01](01-threading.md) §1 |
| **快照池** | 专收“写交易索引”的异步池（队列 1024 / core2-max4） | [01](01-threading.md) §1 |
| **查询池** | 专收“查交易索引”的异步池（队列 16 / core1-max2）；与快照池分开，防止查询挤掉写任务 | [01](01-threading.md) §1 |
| **配置写线程** | 单写者线程，只做 `String → 文件` 的 I/O | [02](02-config-pipeline.md) §1 |
| **daemon 线** | 后台三条线程都是 daemon：数据安全靠停服/关机钩子排干，不靠 JVM 被线程吊住 | [01](01-threading.md) §1 |
| **拒绝策略（RejectedExecutionHandler）** | 队列满/池关闭时的兜底；快照池与查询池是“WARN + 抛异常”，让调用方拿 `false` | [01](01-threading.md) §2 |
| **分帧** | 把“单 tick 做不完、又必须在主线程做”的工作按每 tick 预算切片的模式 | [01](01-threading.md) §4 |
| **pre-shutdown 钩子** | 关池**之前**要跑的收尾动作（配置排干），按登记顺序执行 | [01](01-threading.md) §3 |

---

### 2. 写盘管线

| 术语 | 定义 | 位置 |
|---|---|---|
| **脏标记（dirty）** | `AtomicBoolean`：有未提交改动；`markDirty()` 只置标志，不碰磁盘 | [02](02-config-pipeline.md) §1 |
| **提交（flush）** | 主线程侧动作：**线程序列化 + 入队**。注意它不等同于“已落盘” | [02](02-config-pipeline.md) §1 |
| **落盘（writeOne）** | 写线程侧动作：临时文件 + `ATOMIC_MOVE` 真正写字节 | [02](02-config-pipeline.md) §1 |
| **合并 / 去抖** | 同一路径连续提交只保留最新内容，一个 drain 只写一次 | [02](02-config-pipeline.md) §3 |
| **PENDING** | `ConcurrentHashMap<Path, String>`：待写内容（后写覆盖先写 = 合并） | [02](02-config-pipeline.md) §3 |
| **NOTIFY** | `LinkedBlockingQueue<Path>`：只负责叫醒写线程，允许重复/过期 | [02](02-config-pipeline.md) §3 |
| **inFlight** | 写线程正在写的那个文件；`awaitFile` 用它判“是否还有在途写” | [02](02-config-pipeline.md) §2 |
| **drain** | 自旋等待“待写全部清空”；超时只记 ERROR，不死等 | [02](02-config-pipeline.md) §5 |
| **awaitFile** | 读盘前的精确栅栏：等指定文件既无待写也无在途写 | [02](02-config-pipeline.md) §5 |
| **batch** | 把一批修改合并成一次提交；块内 `flush` 被压住，异常退出也会提交 | [02](02-config-pipeline.md) §5 |
| **原子写** | 写同目录临时文件 → `ATOMIC_MOVE` 替换；失败退回普通 move | [02](02-config-pipeline.md) §6 |

---

### 3. 配置生命周期

| 术语 | 定义 | 位置 |
|---|---|---|
| **观察者队列（DIRTY_SET）** | 只有被 `markDirty` 的配置实例才进入的集合；`tickAll` 只遍历它 | [03](03-config-lifecycle.md) §3 |
| **globalTicks / lastFlushedTick** | 全局 tick 计数与每个实例的上次落盘 tick；两者之差实现节流 | [03](03-config-lifecycle.md) §3 |
| **flushIntervalTicks** | 每个实例自声明的落盘周期（默认 20，村民索引 200） | [03](03-config-lifecycle.md) §3 |
| **开服回灌（reload）** | `ensureLoaded → onReload → markDirty`：把磁盘值应用到系统，并保证文件存在 | [03](03-config-lifecycle.md) §4 |
| **从磁盘重读（reloadFromDisk）** | `flushAndAwait → forceLoad → onDiskLoad`：先排干再整份重读 | [03](03-config-lifecycle.md) §4 |
| **复位（reset）** | 停服收尾：flush → 摘 server → 清脏 → 清内存 | [03](03-config-lifecycle.md) §5 |
| **section 配置** | 没有独立文件、只是 `config.json` 一个节点的规则细项配置；由 `RuleConfig.reloadChildren` 驱动 | [03](03-config-lifecycle.md) §1 |

---

### 4. 会话与扫包

| 术语 | 定义 | 位置 |
|---|---|---|
| **会话状态（SessionState）** | “只活在一次联机会话里”的缓存契约：`clearSession()` / `clearPlayer(uuid)` | [04](04-session-state.md) §1 |
| **扫包** | 用包名反射发现实现类；`ScanService` 建一次索引，`ChannelScanner` 负责实例化与失败汇总 | [04](04-session-state.md) §2 |
| **角色接口** | 每个目录对应一个接口（`ClientboundChannel` / `ServerReceiver` / `SyncEvents` / `SessionState` …），只有它的具体实现会被处理 | [04](04-session-state.md) §2 |
| **`INSTANCE` 约定** | `public static final Xxx INSTANCE` + 私有构造；扫包优先读它，没有就退回无参构造 | [04](04-session-state.md) §2 |
| **半注册** | 类被扫到但初始化抛异常；计入 `Failure`，不出现在“已注册”清单里 | [04](04-session-state.md) §6 |
| **`SCAN_PACKAGES` 白名单** | `ScanService` 只索引白名单内的包；白名单外的包名会抛 `IllegalStateException`，启动直接失败 | [04](04-session-state.md#2-扫包机制三层) §2 |

---

### 5. 规则框架

| 术语 | 定义 | 位置 |
|---|---|---|
| **档位（permission tier）** | 状态值取 `true/ops/master/admin/owner` 时的等级；既是开关也是“谁能触发” | [05](05-rule-framework.md) §1 |
| **权限节点** | `villagerdetails:c.<注册名小写>`（对玩家的生效判定）/ `villagerdetails:c.base`（`/ec` 总权限） | [05](05-rule-framework.md) §2 |
| **`hasLevel`** | 只看来源自身的权限等级，**不看权限节点**——命令准入用这条路，权限插件放宽不了 | [05](05-rule-framework.md) §2 |
| **`requirement`** | 规则命令的准入谓词：规则关→false；逐条配置类→OWNERS；其余→按档位判等级 | [05](05-rule-framework.md) §2 |
| **canonicalize** | 状态值的第一道关：空值拒 / 预定义列表命中 / 自定义列表原样过 | [05](05-rule-framework.md) §3 |
| **状态校验器（RuleStateValidator）** | 第二道关：格式与范围；纯函数、无副作用，写入前拦 | [05](05-rule-framework.md) §3 |
| **短路** | `setState` 发现值没变就直接返回：不写、不落盘、**不触发回调** | [05](05-rule-framework.md#2-rulecache五个方法--两条红线) §2 |
| **快捷开关（quickSwitches）** | 规则的合法取值列表；若含非开关词则整条被当“自定义列表”（任意文本放行） | [05](05-rule-framework.md) §1 |
| **逐条配置类（RuleConfigKind.LIST）** | 除开关外还维护一份名单/数值表的规则；命令写死 OWNERS，内容需下发 | [05](05-rule-framework.md) §6 |
| **规则描述符（RuleDescriptor）** | 下发给客户端的规则视图：翻译好的纯文本 + 状态 + 可选值 + 列表模板 | [05](05-rule-framework.md) §5 |
| **模板原语** | `ListTemplate(plain, valueKind, idSource)`：客户端只认这几种形状，不认识规则语义 | [05](05-rule-framework.md) §5 |
| **值控件（ValueKind）** | 列表页里“值”那一列用什么控件（纯名单 / 数字 …） | [05](05-rule-framework.md) §5 |

---

### 6. 网络

| 术语 | 定义 | 位置 |
|---|---|---|
| **包类型（PayloadType）** | `PayloadTypeRegistry` 里的登记项（id + codec）；**必须物理两端各登记一次** | [07](07-network-framework.md) §2 |
| **payload** | 一条包的定义：`PayloadId` + `CODEC`；实现 `ClientboundPayload` / `ServerboundPayload` 决定方向 | [07](07-network-framework.md) §3 |
| **通道（SyncChannel）** | 一条 S→C 同步的**发送** API；全项目唯一的 `ServerPlayNetworking.send` 在 `sendTo` 里 | [07](07-network-framework.md) §4 |
| **协议通道（ProtocolChannel）** | 免静音、不免“对端装没装”的通道（握手用）；标记接口与 `isProtocolChannel()` 必须一致 | [07](07-network-framework.md) §4 |
| **静音（mute）** | 客户端单方面关掉某通道的显示；服务端在 `canSendTo` 里跳过扫描与组包 | [07](07-network-framework.md) §7 |
| **握手（handshake）** | 客户端上报模组版本 → 服务端按常量判定（拉黑 / 下限）→ 回执；没收到不踢人 | [07](07-network-framework.md) §6 |
| **协议版本（SyncProtocol.VERSION）** | 描述符/同步协议的形状版本；形状变了就升版本 + 加新包 id | [07](07-network-framework.md) §6 |
| **能力探测（canSend）** | `ServerPlayNetworking.canSend`：对端是否装了本模组；所有发送前都要过 | [07](07-network-framework.md) §4 |

---

### 7. 并发语义

| 术语 | 定义 | 位置 |
|---|---|---|
| **纪元（epoch）** | 索引“整体换代”的编号；清空时 +1，抓快照时记录、写入时比对，挡住换代前的迟到写入 | [01](01-threading.md) §6 / 业务 [`trade-search.md`](../business/03-deep-dives/trade-search.md) |
| **墓碑（tombstone）** | 村民死亡时刻的记录；挡住“死后才落地”的快照写入 | 同上 |
| **`LAST_SNAPSHOT`** | 每个村民“已写入的最新快照时刻”；只放行严格更新的那一份，防乱序覆盖 | 同上 |
| **`clearedAt`** | 最近一次整份清空的时刻；快照比它还早 → 丢弃 | 同上 |
| **锁内二次检查** | `put` 的闸 3/4/5 必须在 `SESSION_LOCK` 内完成，否则每个检查都只是 check-then-act 窗口 | 同上 |

> 纪元 / 墓碑 / `LAST_SNAPSHOT` / `clearedAt` 的**实现**在业务代码（`VillagerTradeCache`），
> 所以权威文档是业务侧的深度剖析；本节只给框架视角的一句话定义。

---

### 8. Mixin

| 术语 | 定义 | 位置 |
|---|---|---|
| **注入点** | `@Inject` 的 `HEAD` / `TAIL` / `RETURN`，或 `@Redirect` 的调用点 | [08](08-mixin-inventory.md) §4 |
| **cancellable** | 注入点可取消原版执行（= 真的改控制流） | [08](08-mixin-inventory.md) §4 |
| **`@Accessor` / `@Invoker`** | 暴露私有字段 / 方法；不改变行为，但字段改名即失效 | [08](08-mixin-inventory.md) §3 |
| **refmap** | 编译期生成的“命名映射”，保证混淆前后注入点对得上 | [08](08-mixin-inventory.md) §4 |
| **`defaultRequire`** | 注入点默认要求匹配到 ≥1 个目标；匹配不到即启动失败 | [08](08-mixin-inventory.md) §1 |

---

### 9. 版本适配

| 术语 | 定义 | 位置 |
|---|---|---|
| **版本层** | `v26.2/` / `v26.3/` 两份同包同名的实现，构建时挑一份 | [09](09-version-adapter.md) §1 |
| **下沉** | 把“编不过的代码”从 `common` 移到版本层的动作（判定口诀：编不过→下沉） | [09](09-version-adapter.md) §1 |
| **抹平** | 用 `@Accessor` / 适配类把版本差异挡住，让调用点保持版本无关 | [09](09-version-adapter.md) §3 |
| **源集拼接** | 构建脚本按 `mcVersion` 把 `common/src/*` 与 `vXX/src/*` 合成一个产物 | [09](09-version-adapter.md) 开头 |

---

### 10. 易混词表（读代码前先分清）

| 词 | 两种含义 | 怎么区分 |
|---|---|---|
| **flush** | ① 主线程“提交/入队”（`AbstractAsyncConfig#flush`）② 排干并停写线程（`flushAndShutdown`） | ① 不阻塞、不等落盘；② 会自旋等待 |
| **清空** | ① 清会话内存（`SessionState.clearSession`）② 清配置内存数据（`clearData`） | ① 由事件触发；② 只在 `reset` 里 |
| **脏** | ① 配置有未提交改动（dirty 标记）② 熔炉“输入槽烧不了”（业务概念） | 上下文完全不同，别混 |
| **同步** | ① 网络 S→C 推送 ② 落盘（把内存写到磁盘） | ① `SyncChannel`；② `flush` |
| **档位** | ① 规则的权限等级 ② 每 tick 模拟次数这类“数值档” | ① 权限语义；② 纯数值 |
| **快照** | ① 交易快照（业务数据结构）② `LAST_SNAPSHOT`（时刻表） | ① 数据；② 元数据 |
