# Deep dive: villager trade search (`TradeSearch`)

How a locked trade becomes an indexed record, and how a query stays off the world thread entirely.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> Related rule: [`TradeSearch`](../02-rules/villager.md#tradesearch村民交易查询)
> Code entry: `handler/entity/villager/trade/{VillagerTradePipeline,VillagerTradeSearchHandler}.java`,
> `cache/impl/VillagerTradeCache.java`, `config/impl/VillagerTradeCacheConfig.java`,
> `command/c/impl/rule/villager/TradeSearchCommand.java`, `event/impl/register/VillagerTradeSearchEvents.java`

---

### What problem it solves

A player wants to know "who on this server sells Mending / who buys bookshelves". Vanilla has no way to ask; you can only open villager screens one by one.
The most direct implementation would be "walk every villager in the world when the query runs" — but that means **walking the whole save's entity index on the main thread**; tens of thousands of villagers × a dozen offers each makes one query take hundreds of milliseconds and the server drops ticks.

This rule splits "query" into two parts that never bother each other:
**the write side** (copy the trade table out when the villager leaves view) and **the read side** (search the in-memory index).

---

### Overall design

```
Write side (produces data)                            Read side (consumes data)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Main thread: take a snapshot (deep-copied into plain data)   Main thread: /ec c TradeSearch item|book <keyword>
   ├ ENTITY_UNLOAD    (main entry)                            ├ 1 s debounce (per player)
   ├ CHUNK_UNLOAD     (fallback)                              └ submit the query task
   ├ trade screen opened (100 tick debounce)                            ↓
   ├ name tag refresh succeeded (captureNow)        Query pool (VillagerDetails-Query)
   └ villager died → remove (clear index + raise tombstone)   ├ walk the index (CHM, weakly consistent)
        ↓                                                     ├ filter tombstones again (the dead)
Snapshot pool (VillagerDetails-Snapshot)                      ├ sort + truncate to 10
   └ put(snapshot, time, epoch) → 5 gates → write index       └ produce plain SearchResult
        ↓                                                              ↓
   mark dirty → every 200 ticks (≈10 s) async flush to disk   main thread (next tick): build the chat message
   → villagerdetails/villager_trade_cache.json
```

**Persisting is the only reason a restart can still query**: memory is the only data source, the file is just its persisted copy.

The index is written by exactly three kinds of events: **chunk unload, trade-screen open (100-tick debounce) and an explicit refresh**.
There is no global sweep — a villager whose chunk never unloads is picked up at the next capture.

---

### The five gates: why one write has to pass so many checks

The writer is a worker on the thread pool, the reader / remover is the main thread, and between them sits the gap of "submit → execute".
The villager may well **die, or the world may have been cleared entirely, before the task actually runs**. So `put` passes five gates in order:

| Gate | Where | What it checks | What it stops |
|---|---|---|---|
| **0** (lock-free fast path) | top of the method | the **epoch** captured at submit time ≠ current epoch → drop | a whole batch of stale tasks from "snapshot taken → rule off / on again" |
| **1** | outside the lock | `snapshotNanos <= clearedAt` → drop | a snapshot older than the most recent full clear |
| **2** | outside the lock | tombstone time ≥ snapshot time → drop | an old snapshot that finished after the villager died (**most late writes die here**) |
| **3** | **inside the lock** | the epoch changed → drop | the world was cleared after the snapshot was taken (data from a previous session) |
| **4** | **inside the lock** | **check the tombstone once more** | the key one: the worker passes the lock-free gate 2 while "there is no tombstone yet" → the main thread `remove` takes the lock, raises the tombstone and deletes the index → only then does the worker get the lock |
| **5** | **inside the lock** | claim the "latest snapshot time" (`LAST_SNAPSHOT`, compared atomically with `compute`) | two snapshots of the same villager **arriving out of order**, the old position overwriting newer data |

**Why gates 3 / 4 / 5 must live inside the lock**: the checks outside the lock are all check-then-act. With lock-free checks only, the window "passed the check → main thread acts → now we write memory" always exists. Inside the lock there are only a few `ConcurrentHashMap` operations (microseconds), **no IO at all**, so the main thread never feels it.

**Gate 4 is the easiest to miss and the worst to drop**: without it a dead villager is written back into the index while the tombstone stays — the read side then sees "present in the index, present in the tombstone too", and no matter how it decides, it is wrong.

**Why gates 2 and 4 both check the tombstone**: gate 2 is the fast path (it keeps the vast majority of late tasks outside the lock, avoiding lock contention), gate 4 is the correctness guarantee. They are not redundant: one handles probability, the other handles ordering.

---

### Key trade-offs

#### ① Why not walk the world at query time

Walking the entity index is main-thread work, and its cost grows linearly with the save (tens of thousands of villagers). Once it became "copy one out at unload", the write is spread over "chunk unload", which is naturally low-frequency, and a query only touches memory — the complexity drops from O(world size) to O(number of index entries).

#### ② Why only villagers with "locked trades" are collected

The decision uses two version-independent pieces of evidence: `villagerXp > 0` (a trade really was completed) **or** any offer with `uses > 0`.
An unlocked villager's trade table can change at any time because of a level-up or a reroll, so collecting it would only produce data that "expires tomorrow".
**`isTrading()` is deliberately not used**: it means "a player has the screen open right now" and is almost always `false` at chunk unload.

#### ③ Why there is no "is it worth collecting" filter

This is the most counter-intuitive point. Filtering by a `trade_index.json` whitelist goes wrong:

- **False kills**: bookshelves, maps, name tags, armour and food — all normal sales — are filtered out;
- **Letting everything through**: the "buys emeralds" direction all passes anyway;
- the index ends up with nothing but "X → 1 emerald", which is *harder* to use.

So the rule is "collect whatever the villager sells", **skipping only empty offers with no result item**.
Too many entries is a **capacity** problem: it should be handled with file size / entry caps, never by deleting data.

#### ④ Why the read side filters the dead once more

A `ConcurrentHashMap` traversal is **weakly consistent**: a value in a bucket the cursor has already passed may still be the old value removed a few milliseconds ago.
So the query filters once more with the tombstone table (`removedAtView()`), which is a second `containsKey` check.
**This is not an optional optimisation**: without it the player can find a villager that was killed a moment ago.

#### ⑤ Why the snapshot pool and the query pool are separate

The two business pools have different capacities (the snapshot pool is big, the query pool is small) and different semantics:

- a lost snapshot task **loses data** (nothing will capture it again until the next unload);
- a query task is **user-retryable** (just run the command again).

With one shared queue, a player hammering the query command could push the snapshot tasks out. Separated, they no longer interfere.
(For pool capacities / rejection policies see [framework 01](../../framework/01-threading.md).)
When the pool is unavailable, that batch of snapshots is skipped with a single WARN and the next round (200 ticks later) simply tries again — the main thread never blocks on it.

#### ⑥ Why the index is persisted, and why turning the rule off does "flush first, clear later"

Without persistence the index would be empty after every restart and the player would have to walk the whole map before searching again. With it:
when the rule is turned off, the code **first calls `flush()` and only clears memory after the persisting succeeded**; if it failed, **memory is kept and the clearing is abandoned**.
The reason is simple: memory is the only data source, and the file on disk may still be a version from long ago — clearing would lose it for good.
When the rule is turned back on and memory is empty, the disk index is read back.

#### ⑦ Why a snapshot stores no display text

A snapshot stores only **scalars**: id / count / price / enchantment id + level. Names are localized by the client in its own language.
If the server built Chinese strings, an English client would show Chinese — and the server cannot know the client's language anyway.

---

### Design rules and boundaries

| # | Scenario | Note |
|---|---|---|
| 1 | A trade was just locked and the chunk never unloads | The index is written by three kinds of events: chunk unload, trade-screen open (100-tick debounce) and an explicit refresh. A villager whose chunk never unloads is indexed at the next capture; there is no global sweep |
| 2 | The villager dies | The main thread's `remove` is three atomic steps (raise the tombstone → delete the index entry → clear the snapshot time); the dirty flag is set outside the lock |
| 3 | The tombstone / snapshot-time tables grow | Every 12000 ticks (10 minutes) with ≥ 512 entries they are handed to the pool for async cleanup; tombstones expire by a 10-minute TTL, snapshot times are cleared by "is this villager still in the index" |
| 4 | World switch / shutdown | `clearSession`: after a successful flush → advance the epoch inside the lock + clear the index + clear both tables + record `clearedAt` + **clear the dirty flag** (otherwise the next tick would overwrite the file with an empty table) |
| 5 | Searching while the index is empty | Reply "index is empty" directly, never walk an empty table |
| 6 | The keyword is Chinese | The command argument must be `greedyString()` — vanilla `word()` only accepts ASCII, and `经验修补` would be reported as trailing data |
| 7 | Result order | Fixed as "dimension id → position → UUID", so the same cache gives the same order every time (the "entry N" in chat always matches) |
| 8 | Why `item` and `book` are split | For an enchanted-book trade the "keyword" is the **enchantment**, a different vocabulary from items; mixed together, searching "emerald" would be drowned by "57 emeralds + 1 book → enchanted book" |
| 9 | Query error / pool rejection | Caught by the command layer with a reply (never thrown into the tick); the player waits at most one more tick |
| 10 | Console / command block | There is no `ServerPlayer`, so the query runs synchronously and the result goes to the server log (no NPE) |

---

<a id="中文"></a>

## 中文

> 相关规则：[`TradeSearch`](../02-rules/villager.md#tradesearch村民交易查询)
> 代码入口：`handler/entity/villager/trade/{VillagerTradePipeline,VillagerTradeSearchHandler}.java`、
> `cache/impl/VillagerTradeCache.java`、`config/impl/VillagerTradeCacheConfig.java`、
> `command/c/impl/rule/villager/TradeSearchCommand.java`、`event/impl/register/VillagerTradeSearchEvents.java`

---

### 解决什么问题

玩家想知道"全服谁卖经验修补 / 谁收书架"。原版完全没法查，只能挨个开村民界面。
最直接的实现是"查询时遍历世界里的所有村民"——但那是**在主线程遍历整个存档的实体索引**，
几万只村民 × 每条十几笔交易，一次查询就是几百毫秒的卡顿，服务器直接掉 tick。

这条规则把"查询"拆成两个互不打扰的部分：
**写入侧**（村民离开视野时把交易表抄下来）和**读取侧**（查内存索引）。

---

### 整体设计

```
写入侧（产生数据）                                    读取侧（消费数据）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
主线程：抓快照（深拷贝成纯数据）              主线程：/ec c TradeSearch item|book <关键词>
   ├ ENTITY_UNLOAD   （主入口）                  ├ 1 秒防抖（同一玩家）
   ├ CHUNK_UNLOAD    （兜底）                    └ 提交查询任务
   ├ 打开交易界面（100 tick 防抖）                        ↓
   ├ 命名牌刷新成功（captureNow）            查询池（VillagerDetails-Query）
   └ 村民死亡 → remove（清索引 + 立墓碑）        ├ 遍历索引（CHM 弱一致）
        ↓                                      ├ 二次过滤墓碑（死者）
快照池（VillagerDetails-Snapshot）              ├ 排序 + 截断 10 条
   └ put(快照, 时刻, 纪元) → 5 道闸 → 写索引     └ 产出纯数据 SearchResult
        ↓                                                ↓
   标脏 → 每 200 tick（≈10 秒）异步落盘          主线程（下一 tick）：拼聊天栏消息
   → villagerdetails/villager_trade_cache.json
```

**落盘是"能重启后继续查"的唯一原因**：内存是唯一数据源，磁盘只是它的持久化副本。

索引只由三类事件写入：**区块卸载、交易界面打开（100 tick 防抖）、显式刷新**。
没有全服扫描 —— 区块一直不卸载的村民会在下一次抓取时收录。

---

### 五道闸：为什么一次写入要过这么多关

写入方是线程池里的 worker，读/删方是主线程，中间隔着"提交 → 执行"的时间差。
村民完全可能**在任务真正执行前就死了、或者世界整份清空过了**。所以 `put` 按顺序过五道闸：

| 闸 | 位置 | 判什么 | 挡住的场景 |
|---|---|---|---|
| **0**（锁外快路） | 方法开头 | 提交时抓的 **epoch** ≠ 当前 epoch → 丢 | "抓快照 → 规则关/开一轮"的整批陈旧任务 |
| **1** | 锁外 | `snapshotNanos <= clearedAt` → 丢 | 快照比"最近一次整份清空"还早 |
| **2** | 锁外 | 墓碑时间 ≥ 快照时间 → 丢 | 村民死后才跑完的旧快照（**绝大多数迟到写入死在这里**） |
| **3** | **锁内** | 纪元变了 → 丢 | 抓快照后世界被清空过（上一个会话的数据） |
| **4** | **锁内** | **再查一次墓碑** | 关键：worker 通过锁外闸 2 时"还没有墓碑" → 主线程 `remove` 拿锁、立墓碑、删索引 → worker 这时才拿到锁 |
| **5** | **锁内** | 抢占"最新快照时刻"（`LAST_SNAPSHOT`，用 `compute` 原子比较） | 同一村民的两份快照**乱序到达**，旧坐标覆盖新数据 |

**为什么闸 3/4/5 一定要在锁内**：锁外的检查都是 check-then-act。只用锁外检查时，
"通过检查 → 主线程动手 → 才写内存"这个窗口永远存在。锁内只做几次 `ConcurrentHashMap` 操作（微秒级），
**不碰 IO**，所以主线程感觉不到。

**闸 4 是最容易漏、后果最严重的一个**：没有它，死掉的村民会被写回索引，而墓碑还留着 ——
查询侧看到"索引里有、墓碑里也有"，无论怎么判都是错的。

**为什么闸 2 和闸 4 都查墓碑**：闸 2 是快路径（把绝大多数迟到任务挡在锁外，避免争锁），
闸 4 是正确性保证。两者不重复：一个管概率，一个管时序。

---

### 关键取舍

#### ① 为什么不在查询时遍历世界

遍历实体索引是主线程的活，且代价随存档规模线性增长（几万村民）。改成"卸载时抄一份"之后，
写入被摊到"区块卸载"这种天然低频的时刻，查询只碰内存 —— 复杂度从 O(世界规模) 变成 O(索引条数)。

#### ② 为什么只收"已锁定交易"的村民

判定用两条与版本无关的证据：`villagerXp > 0`（真完成过交易）**或** 任意一笔 `uses > 0`。
未锁定的村民交易表随时会因为升级 / 重刷而变，收录进来只会得到一份"明天就失效"的数据。
**刻意不判 `isTrading()`**：它表示"当前有玩家开着界面"，区块卸载时几乎永远是 `false`。

#### ③ 为什么不做「值不值得收录」的过滤

这是最反直觉的一条。按 `trade_index.json` 做白名单会直接走偏：

- **误杀**：书架、地图、命名牌、盔甲、食物这些正常出售交易全被滤掉；
- **放水**：「收购绿宝石」方向全都能过；
- 最终索引里只剩一堆「X → 1 绿宝石」，反而更难用。

所以现在的规则是"村民卖什么就收什么"，**只跳过没有结果物品的空交易**。
条目太多属于**容量问题**，应该用文件体积 / 条数上限去管，不能靠删除数据解决。

#### ④ 为什么查询侧还要再过滤一次死者

`ConcurrentHashMap` 的遍历是**弱一致**的：游标走过的 bucket，值可能还是几毫秒前刚被 `remove` 删掉的旧值。
所以查询时用墓碑表（`removedAtView()`）再做一次 `containsKey` 过滤。
**这不是可选优化**：少了它，玩家会查到一只刚被打死的村民。

#### ⑤ 为什么快照池和查询池要分开

两个业务池容量不同（快照池大、查询池小），语义也不同：

- 快照任务**丢了就是丢数据**（下次卸载才会再抓）；
- 查询任务是**用户可重试**的（再敲一次命令就行）。

合用一个队列时，玩家连按几次查询就能把快照任务挤掉。分开之后互相不干扰。
（池子的容量 / 拒绝策略见[框架 01](../../framework/01-threading.md)。）
池子不可用时这一批快照会被跳过并只打一次 WARN，下一轮（200 tick 后）继续尝试，主线程不会为它阻塞。

#### ⑥ 为什么索引要落盘，且关规则时"先存再清"

不落盘的话每次重启索引就空了，玩家得把地图跑一遍才查得到。落盘之后：
规则关掉时**先 `flush()` 提交落盘成功才清内存**；失败则**保留内存并放弃清理**。
理由很简单：内存是唯一数据源，磁盘上可能还是好久以前的旧版本 —— 清掉就真的没了。
规则重新打开时，如果内存是空的，把磁盘索引读回来。

#### ⑦ 为什么快照不存任何展示文本

快照只存 id / 数量 / 价格 / 附魔 id+等级这些**标量**，名字由客户端按自己的语言本地化。
服务端拼中文的话，英文客户端看到的就是中文；而且服务端也没法知道客户端的语言。

---

### 设计约定与边界

| # | 场景 | 说明 |
|---|---|---|
| 1 | 交易刚锁定、区块一直不卸载 | 索引由三类事件写入：区块卸载、交易界面打开（100 tick 防抖）、显式刷新。区块永不卸载的村民会在下一次抓取时收录，没有全服扫描 |
| 2 | 村民死亡 | 主线程 `remove` 三步原子（先立墓碑 → 删索引 → 清快照时刻），标脏在锁外 |
| 3 | 墓碑 / 快照时刻表膨胀 | 每 12000 tick（10 分钟）且条目数 ≥ 512 时丢给池子异步清理；墓碑按 10 分钟 TTL、快照时刻按"索引里还有没有这只村民"清 |
| 4 | 换存档 / 停服 | `clearSession`：flush 成功 → 锁内推进纪元 + 清索引 + 清两张表 + 记 `clearedAt` + **清脏标记**（不清的话下一次 tick 会拿空表覆盖磁盘） |
| 5 | 索引为空时搜 | 直接回"索引为空"提示，不遍历空表 |
| 6 | 关键词是中文 | 命令参数必须用 `greedyString()` —— 原版的 `word()` 只认 ASCII，`经验修补` 会被当成尾随数据报错 |
| 7 | 结果顺序 | 固定按「维度 id → 坐标 → UUID」排序，同一份缓存每次搜索顺序一致（聊天栏里的"第 N 条"永远对得上） |
| 8 | `item` 与 `book` 为什么要拆 | 附魔书交易的"关键词"是**附魔**，和物品不是一套词汇；混在一起时搜「绿宝石」会被一堆「57 绿宝石 + 1 书 → 附魔书」淹掉 |
| 9 | 查询异常 / 池子拒绝 | 命令层捕获后回一条提示（不抛到 tick 里）；玩家最多多等 1 tick |
| 10 | 控制台 / 命令方块执行 | 没有 `ServerPlayer`，改走同步查询，结果打到服务端日志（不 NPE） |
