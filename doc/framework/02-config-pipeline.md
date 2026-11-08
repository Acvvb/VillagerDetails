# Config Persistence Pipeline

The seven-stage data flow from a dirty flag to an atomic file write, with coalescing, debounce, failure semantics and batching.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> **Covers**: `AbstractAsyncConfig` · `AsyncConfigManager` · `AsyncConfigWriter` · `JsonIO`.
> **Related**: the config **lifecycle** (when to load / reload / reset) is in [03](03-config-lifecycle.md);
> the writer thread is the fourth execution line, see [01](01-threading.md#1-the-four-execution-lines).

---

### 1. The seven-stage data flow

```
① markDirty()                 caller thread (server side = main thread)
   └─ dirty.set(true) + DIRTY_SET.add(this)          ← sets flags only, never touches the disk
                ↓  END_SERVER_TICK
② tickAll()                   main thread
   └─ walk DIRTY_SET, throttle by flushIntervalTicks → call flush()
                ↓
③ flush()                     AbstractWorldConfig (final)
   ├─ batchDepth > 0 → return immediately (suppressed inside a block)
   ├─ !dirty → return
   ├─ getFilePath() == null → return (no server; the dirty flag is kept)
   └─ AsyncConfigManager.save(path, getDataToSave())
                ↓
④ save()                      main thread ← the one and only serialization point
   ├─ JsonIO.serialize(data) → String     (Gson walks the whole tree, pure CPU)
   └─ AsyncConfigWriter.submit(path, json)
                ↓
⑤ submit()                    main thread
   ├─ PENDING.put(path, json)              (a later value for the same path overwrites = coalescing)
   ├─ ensureWorker()                       (start a writer thread if there is none)
   └─ NOTIFY.offer(path)                   (wake-up only)
                ↓  writer thread
⑥ runLoop() → writeOne(path)  config writer thread
   ├─ inFlight = path
   ├─ PENDING.remove(path) → content       (take the current newest value; skip if there is none)
   └─ JsonIO.writeString(path, content)
                ↓
⑦ writeString()               config writer thread
   ├─ Files.createDirectories(parent)
   ├─ Files.writeString(<file>.tmp.<UUID>, content)
   ├─ Files.move(tmp, file, REPLACE_EXISTING, ATOMIC_MOVE)
   └─ on failure → Files.move(tmp, file, REPLACE_EXISTING) (fall back to a non-atomic move)
   finally → deleteIfExists(tmp)
```

**Stage ④ is the only serialization point**: at that instant `data` becomes an immutable `String`, so later mutations of that tree or map cannot pollute this write.

The tail of `JsonIO.serialize` also runs two regex passes over the whole JSON string (`inlineNumberArrays`) that collapse "one number per line" arrays onto a single line, e.g. the coordinates `[-2278, 77, -2098]`. It only handles **pure-number** arrays — string arrays still get one element per line — and its correctness rests on one property: a newline inside a JSON string literal is always escaped by Gson, so a whole-string scan cannot damage string content. The cost is one pure-CPU scan-and-rebuild pass over large files (a multi-MB villager index).

---

### 2. Thread and concurrency primitive of each stage

| Stage | Thread | Concurrency control | Why |
|---|---|---|---|
| ① `markDirty` | any (in practice always the main thread) | `AtomicBoolean dirty` + `ConcurrentHashMap.newKeySet()` | call sites are spread across event callbacks / commands / receivers; no caller should have to take a lock first |
| ② `tickAll` | main thread | `DIRTY_SET` is a concurrent set, but **only the main thread reads and writes it** | the concurrent set is defensive only; semantically the main thread owns it |
| ③ `flush` | main thread | `batchDepth` (`AtomicInteger`) | the atomic type is defensive too: `flush()` is documented as a main-thread-only operation |
| ④ `save` | main thread | lock-free (guaranteed by the caller) | serialization must happen while no thread is mutating the data |
| ⑤ `submit` | main thread | `ConcurrentHashMap PENDING` + `LinkedBlockingQueue NOTIFY` | the reader side is the writer thread, so it has to be thread-safe |
| ⑥ `writeOne` | writer thread | `volatile inFlight` + the `LIFECYCLE` lock (start/stop of the thread) | `inFlight` lets `awaitFile` tell "this file still has a write in flight" |
| ⑦ `writeString` | writer thread | the temporary file name carries a UUID | even if concurrent writes ever appear, nobody deletes somebody else's temp file |

---

### 3. How coalescing (and debounce) works

```
a file changed 10 times inside one tick:
  submit(path, "v1") → PENDING[path]="v1", NOTIFY=[path]
  submit(path, "v2") → PENDING[path]="v2", NOTIFY=[path, path]
  …
  submit(path, "v10")→ PENDING[path]="v10", NOTIFY=[path ×10]

the writer thread wakes up 10 times, but each time takes the "current newest value" from PENDING:
  1st time: remove → "v10" → write to disk
  2nd~10th: remove returns null → return immediately (duplicate notifications are eaten by "skip when absent")
→ the disk is written exactly once
```

**Why `PENDING` is a map instead of a queue**: queue semantics are "every item must be written"; config semantics are "what this file should finally contain". A map expresses "later write wins" natively, and the writer thread does not need to know which entry is stale.

**Why `NOTIFY` may have weak semantics** (duplicates allowed, entries may point at items that are already gone): it only wakes the writer up, the real data lives in `PENDING`; a redundant or expired wake-up is harmless.

---

### 4. Failure semantics (one conclusion per step)

| Step | Failure | Dirty flag | Return value / exception | Consequence |
|---|---|---|---|---|
| `JsonIO.serialize` | cyclic reference / unsupported field | **kept** | `save` logs ERROR and returns `false` | the next `tickAll` retries |
| `save` with empty arguments | — | kept | WARN + `false` | same as above |
| `submit` with empty arguments | — | enqueue failed but **the flag is already cleared** | WARN | content lost |
| `writeString` inside `writeOne` throws | disk full / file in use / no permission | **already cleared** (cleared when `flush` succeeded) | logs ERROR + `FAILED++`, puts the content back into `PENDING` and re-notifies the writer; at most `MAX_WRITE_RETRIES = 3` retries; a WARN when it finally gives up | nothing is lost while retries remain; only a write that exhausts the retry budget is lost, and that always leaves a WARN |
| `awaitFile` timeout | writer thread slow / stuck | not affected | logs ERROR and returns, **never throws** | the caller continues with in-memory content (it reads the stale file content) |
| `flushAndShutdown` timeout | same as above | not affected | logs ERROR and returns | the remaining files are left to the JVM shutdown hook / the next start |
| `ATOMIC_MOVE` fails inside `writeString` | network drive / FAT / Windows file in use | not affected | falls back to a plain `move`, content still complete | the "never leave a half-written file" guarantee is given up for that write |

**The failure strategy in one sentence**: a failed **submit** keeps the dirty flag and retries on the next tick; a failed **write** is retried by the writer thread itself, up to three times, before it gives up with a WARN. Neither path loses content silently.

---

### 5. The semantics of `batch`

```
batch(action)                          // nestable
  depth++
  try   { action.run() }
  finally {
    if (--depth == 0) flush();         // only the outermost exit submits once
  }
```

- **Every `flush()` inside the block is suppressed**: otherwise one `RuleSwitchConfig` replay of 40 rules would rewrite the whole file 40 times.
- **An exception inside the block still submits**: the `finally` guarantees "changed means not lost".
- Only the outermost level submits — an inner `flush` is stopped by `batchDepth > 0`.
- `batchDepth` is only ever read and written on the config-owner (main) thread. The `AtomicInteger` type is defensive, not a promise of thread safety: `flush()` is a main-thread-only operation, and the `batchDepth.get() > 0` check in it is a plain check-then-act.

The business-side use case: `RuleSwitchConfig.syncToSwitch()` replays all rule states inside `batch(() -> {...})`, and `RuleCache.setState` calls `persist → setBindingState` every time, yet the disk is written once.

---

### 6. Why the atomic write is designed this way

| Design point | Reason |
|---|---|
| The temporary file sits in the **same directory** | atomic replacement only exists within one file system (a cross-device move is not atomic) |
| The temporary file name carries a **UUID** | a fixed `filename.tmp` lets two writers (the async writer thread and a synchronous fallback somewhere) delete each other's temp file; a UUID keeps even concurrent writes from colliding |
| **Write the temp file first, then move** | on a crash or power loss the target file is either the old complete content or the new complete content, never half of it |
| Fall back to a plain move when `ATOMIC_MOVE` fails | network drives / FAT do not support it, and Windows file-in-use is common; the fallback only loses atomicity, the content stays complete |
| `finally deleteIfExists(tmp)` | after a successful move the temp file is already gone (the call is idempotent); on failure it cleans up the debris |

---

<a id="中文"></a>

## 中文

> 覆盖：`AbstractAsyncConfig` · `AsyncConfigManager` · `AsyncConfigWriter` · `JsonIO`。
> 相关：配置的**生命周期**（何时 load / reload / reset）在 [03](03-config-lifecycle.md)；
> 写线程属于第四条执行线，见 [01](01-threading.md#1-四条执行线)。

---

### 1. 七段数据流

```
① markDirty()                 调用方线程（服务端 = 主线程）
   └─ dirty.set(true) + DIRTY_SET.add(this)          ← 只置标志，不碰磁盘
                ↓  END_SERVER_TICK
② tickAll()                   主线程
   └─ 遍历 DIRTY_SET，按 flushIntervalTicks 节流 → 调 flush()
                ↓
③ flush()                     AbstractWorldConfig（final）
   ├─ batchDepth > 0 → 直接返回（块内压住）
   ├─ !dirty → 返回
   ├─ getFilePath() == null → 返回（没有 server，脏标记保留）
   └─ AsyncConfigManager.save(path, getDataToSave())
                ↓
④ save()                      主线程 ← 唯一的串行化点
   ├─ JsonIO.serialize(data) → String     （Gson 遍历整棵树，纯 CPU）
   └─ AsyncConfigWriter.submit(path, json)
                ↓
⑤ submit()                    主线程
   ├─ PENDING.put(path, json)              （同路径后来者覆盖前者 = 合并）
   ├─ ensureWorker()                       （没有写线程就起一条）
   └─ NOTIFY.offer(path)                   （只负责叫醒）
                ↓  写线程
⑥ runLoop() → writeOne(path)  配置写线程
   ├─ inFlight = path
   ├─ PENDING.remove(path) → content       （取当前最新值；取不到就跳过）
   └─ JsonIO.writeString(path, content)
                ↓
⑦ writeString()               配置写线程
   ├─ Files.createDirectories(parent)
   ├─ Files.writeString(<file>.tmp.<UUID>, content)
   ├─ Files.move(tmp, file, REPLACE_EXISTING, ATOMIC_MOVE)
   └─ 失败 → Files.move(tmp, file, REPLACE_EXISTING)（退回非原子）
   finally → deleteIfExists(tmp)
```

**只有第 ④ 段是串行化点**：`data` 在这一刻被转成不可变 `String`，之后那棵树 / 那个 Map 再被改也不会污染这次写入。

`JsonIO.serialize` 末尾还会对整份 JSON 做两次正则替换（`inlineNumberArrays`），把"每个数字独占一行"的
数组压成一行，例如坐标 `[-2278, 77, -2098]`。它只处理**纯数字**数组，字符串数组仍是一个元素一行；
正确性依赖一条性质：JSON 字符串字面量里的换行一定被 Gson 转义，所以整串扫描不会误伤字符串内容。
代价是对大文件（几 MB 的村民索引）做一次纯 CPU 的整串扫描与重建。

---

### 2. 每段的线程与并发原语

| 段 | 线程 | 并发控制 | 为什么这样 |
|---|---|---|---|
| ① `markDirty` | 任意（实际都是主线程） | `AtomicBoolean dirty` + `ConcurrentHashMap.newKeySet()` | 调用点分散在事件回调 / 命令 / 收包里，不想让调用方先抢锁 |
| ② `tickAll` | 主线程 | `DIRTY_SET` 是并发集合，但**只有主线程读写** | 并发集合只是防御性的，语义上是主线程独占 |
| ③ `flush` | 主线程 | `batchDepth`（`AtomicInteger`） | 原子类型同样只是防御：`flush()` 是主线程专用操作 |
| ④ `save` | 主线程 | 无锁（调用方保证） | 必须在没有任何线程改数据时序列化 |
| ⑤ `submit` | 主线程 | `ConcurrentHashMap PENDING` + `LinkedBlockingQueue NOTIFY` | 读方是写线程，必须线程安全 |
| ⑥ `writeOne` | 写线程 | `volatile inFlight` + `LIFECYCLE` 锁（起/停线程） | `inFlight` 给 `awaitFile` 判"这个文件还有在途写" |
| ⑦ `writeString` | 写线程 | 临时文件名带 UUID | 即使将来出现并发写，也不会"删掉别人正在写的临时文件" |

---

### 3. 合并（去抖）是怎么工作的

```
tick 内被改 10 次：
  submit(path, "v1") → PENDING[path]="v1", NOTIFY=[path]
  submit(path, "v2") → PENDING[path]="v2", NOTIFY=[path, path]
  …
  submit(path, "v10")→ PENDING[path]="v10", NOTIFY=[path ×10]

写线程醒来 10 次，但每次都从 PENDING 取"当前最新值"：
  第 1 次：remove → "v10" → 写盘
  第 2~10 次：remove 返回 null → 直接 return（重复通知被"取不到就跳过"自然吃掉）
→ 磁盘只被写 1 次
```

**为什么 `PENDING` 是 map 而不是队列**：队列语义是"每条都要写"，而配置的语义是"这个文件最终该是什么内容"。
用 map 天然表达"后写覆盖先写"，写线程不需要知道"哪条旧了"。

**为什么 `NOTIFY` 可以是弱语义**（允许重复、允许指向已消失的项）：它只负责"叫醒"，
真正的数据在 `PENDING`；叫醒多余或过期都是无害的。

---

### 4. 失败语义（逐条给结论）

| 环节 | 失败时 | 脏标记 | 返回值 / 异常 | 后果 |
|---|---|---|---|---|
| `JsonIO.serialize` | 循环引用 / 不支持的字段 | **保留** | `save` 记 ERROR 并返回 `false` | 下一轮 `tickAll` 重试 |
| `save` 参数为空 | — | 保留 | WARN + `false` | 同上 |
| `submit` 参数为空 | — | 入队失败但**已清脏** | WARN | 内容丢失 |
| `writeOne` 内 `writeString` 抛异常 | 磁盘满 / 文件被占用 / 无权限 | **已清**（`flush` 成功时就清了） | 记 ERROR + `FAILED++`，把内容放回 `PENDING` 并重新 `NOTIFY`；最多重试 `MAX_WRITE_RETRIES = 3` 次，超限记 WARN 放弃 | 重试期间内容不丢；重试预算耗尽才丢失，且一定留下 WARN |
| `awaitFile` 超时 | 写线程慢 / 卡住 | 不影响 | 记 ERROR 后返回，**不抛** | 调用方按内存内容继续（读到磁盘旧内容） |
| `flushAndShutdown` 超时 | 同上 | 不影响 | 记 ERROR 后返回 | 剩余文件交给 JVM 关机钩子 / 下次启动 |
| `writeString` 的 `ATOMIC_MOVE` 失败 | 网络盘 / FAT / Windows 占用 | 不影响 | 退回普通 `move`，内容仍完整 | 失去"不产生半截文件"的保证 |

**一句话总结失败策略**：**提交失败的脏标记会保留、下一轮 `tickAll` 重试；写入失败由写线程自己把内容
放回 `PENDING` 重试，最多三次，超限才记 WARN 放弃**。两条路径都不会静默丢内容。

---

### 5. `batch` 的语义

```
batch(action)                          // 可嵌套
  depth++
  try   { action.run() }
  finally {
    if (--depth == 0) flush();         // 只有最外层退出才提交一次
  }
```

- **块内 `flush()` 一律被压住**：否则 `RuleSwitchConfig` 一次回灌 40 条规则就会整份重写 40 次。
- **块内抛异常仍会提交**：`finally` 保证了"改了就不会丢"。
- 嵌套只有最外层提交 —— 内层 `flush` 被 `batchDepth > 0` 挡住。
- `batchDepth` 只在配置所有者（主线程）上读写。原子类型是防御性的，不是线程安全承诺：
  `flush()` 是主线程专用操作，其中的 `batchDepth.get() > 0` 就是一次普通的 check-then-act。

业务侧的用例：`RuleSwitchConfig.syncToSwitch()` 在 `batch(() -> {...})` 里回灌全部规则状态，
`RuleCache.setState` 每次都 `persist → setBindingState`，但磁盘只写一次。

---

### 6. 原子写为什么这么设计

| 设计点 | 原因 |
|---|---|
| 临时文件在**同目录** | 只有同一文件系统内才谈得上原子替换（跨盘 move 不是原子的） |
| 临时文件名带 **UUID** | 固定 `文件名.tmp` 会让两个写者（异步写线程 + 某处同步兜底）互删对方的临时文件；带 UUID 后即使出现并发写也不会互删 |
| **先写临时文件再 move** | 崩溃 / 断电时目标文件要么是旧的完整内容、要么是新的完整内容，不会半截 |
| `ATOMIC_MOVE` 失败退回普通 move | 网络盘 / FAT 不支持，Windows 上文件被占用也常见；退回后只是"不再原子"，内容仍完整 |
| `finally deleteIfExists(tmp)` | 成功 move 后临时文件已不存在（幂等）；失败时清理残骸 |
