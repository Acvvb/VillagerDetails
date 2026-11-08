# Deep dive: furnace dirty highlight (`FurnaceDirtyHighlight`)

One global dirty table on the server, per-view-distance deltas over the wire, and a prune order that cannot leak.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> Related rule: [`FurnaceDirtyHighlight`](../02-rules/block.md#furnacedirtyhighlight熔炉脏东西高亮)
> Code entry: `handler/block/furnaceDirty/{FurnaceDirtyTracker,FurnaceDirtyScanner}.java`,
> `config/impl/section/item/FurnaceDirtyHighlightConfig.java`,
> `network/sync/clientbound/FurnaceDirtySyncChannel.java`, `network/sync/event/FurnaceDirtySyncEvents.java`,
> client `client/feature/furnace/FurnaceDirtyHighlightRenderer.java`

---

### What problem it solves

"this furnace has something unsmeltable in its input slot, or is already clogged with junk" — the player has to open every furnace to find out.
What the rule does is **draw a highlight box around such a furnace on the client**, so the player notices it just by walking past.

The hard part is not "how to decide", it is **how to deliver the decision to the client**:

- the number of dirty furnaces is **unbounded** (a player can place thousands of furnaces);
- players move around, and the furnaces inside their view distance change all the time;
- broadcasting the full list every tick wastes both bandwidth and CPU.

---

### Why the earlier approach does not work

The earliest implementation was **broadcast to everyone + filter on the client**: every period each player received a full list of "his neighbourhood", and the client replaced its whole table.

Two hard problems:

1. **The payload grows with the total number of dirty furnaces**: the client can only stay correct with "replace the whole table" (otherwise the list only ever grows),
   so every update has to be complete, and the more dirty furnaces there are, the less it can keep up;
2. **The server can only scan a fixed radius**: it cannot see beyond its own radius, and it cannot bound the client cache either.

---

### The current design: a global dirty table + per-view deltas

```
Server
  GLOBAL_DIRTY: dimension → set of dirty furnace positions       ← the only truth
  VIEWS: player UUID → {dimension, centre chunk, radius, set of synced chunks}

Every tick:
  ① rule off → clear the tables + push clear (only once)
  ② for each player updateView():
       canSendTo fails (no mod / turned it off) → drop his view record and prune
       chunk not crossed / radius unchanged → do nothing (a few integer comparisons)
       dimension changed → first push "clear the previous dimension"
       chunks newly in view → scanChunk first, then push those chunks' dirty points as ADD
       chunks leaving the view → push those positions as REMOVE → then prune chunks nobody watches
  ③ every scanIntervalTicks (default 20) → rescan "the chunks currently covered by any player's view"
     and push the diff to the players watching them
```

**Benefit**: the client cache is bounded by ≈ `view chunks × dirty furnaces per chunk`, so it never grows however far the player flies;
the server keeps one dirty table and needs no per-player list.

---

### Key trade-offs

#### ① Why the judgement "only looks at the input slot, only at whether a recipe exists"

`FurnaceDirtyScanner#isInputDirty`: the input slot is non-empty, but **the furnace's own recipe table** finds no recipe that can smelt it → dirty.

- a normal furnace queries `SMELTING`, a blast furnace `BLASTING`, a smoker `SMOKING`;
- **the fuel slot does not participate** (whether the fuel is junk has nothing to do with "can this be smelted"; the "dirty" in the rule name means the input slot only);
- **modded furnaces are skipped outright**: other `AbstractFurnaceBlockEntity` subclasses do not necessarily hang their recipes on these three types,
  and guessing one would only produce false reports.

#### ② Why the scan granularity is "chunk"

`scanChunk` walks only **this chunk's own block entity table** (`chunk.getBlockEntities()`),
so the cost depends only on the number of block entities in the chunk, not on the chunk volume. That gives two advantages:

- when a player enters a new view distance, only the **newly entered chunks** have to be scanned, not the whole view distance;
- the periodic rescan can iterate "the set of covered chunks", which deduplicates naturally (one chunk watched by several players is scanned once).

#### ③ Why prune (dropping chunks nobody watches) must come after the `VIEWS` update

`pruneUntracked` decides "does any player's view distance still cover this chunk". Pruning before updating `VIEWS`
would misjudge the chunks just given up as "still watched" (because `VIEWS` still holds the old set) and the dirty table would leak.
The order of that step in the code is deliberate.

There are two more prunes for the same reason:

- **player disconnect**: after dropping his view record, immediately prune the chunks only he covered;
- **dimension change**: the old dimension is no longer covered by this player, and if he was the last one, the old dimension's dirty data has to be dropped as well.

#### ④ Why `viewChunkRadius = 0` means "follow the server view distance"

Operators usually change the view distance in `server.properties` and restart. If the mod config also pinned a number,
the two would disagree (the mod scans with 10 while the server loads with 16, and the border chunks would never be highlighted).
So `0` = read the server view distance dynamically every tick; the config log prints the "resolved effective value" too,
so it never says "log says 10, actually scanning 16".

#### ⑤ Why turning the rule off has to "clear the tables + push clear"

Stopping the scan on the server is not enough: the client still holds a table of positions and keeps drawing boxes.
So `resetAll` clears `GLOBAL_DIRTY` / `VIEWS` and pushes clear to every player with the mod installed.
When the rule is turned back on, `VIEWS` is empty → every player is treated as a "new player" and gets a full batch on the next tick.

#### ⑥ Why "players without the mod" are handled separately

When `canSendTo(player)` fails, the server **drops his view record** and prunes.
Not dropping it would cause two problems: pointlessly scanning chunks nobody watches; and when the player installs the mod later (or re-enables the display),
the stale set left in `VIEWS` would break the "newly entered view distance" judgement, which looks like "the highlight does not refresh".

---

### Design rules and boundaries

| # | Scenario | Note |
|---|---|---|
| 1 | Datapack reload | The recipe tables changed, but there is **no immediate rescan**; it waits for the next `scanIntervalTicks` period |
| 2 | Unloaded chunks | `getChunkNow` is checked before scanning, and unloaded chunks are skipped — **the scan never loads / generates a chunk** |
| 3 | Junk in the fuel slot | Not highlighted (see above) |
| 4 | Modded furnaces | Never highlighted (deliberately, to avoid false reports) |
| 5 | Chunk unload | The dirty table may briefly keep the positions of an unloaded chunk; they are dropped by prune when the player leaves the view distance / disconnects |
| 6 | Several players watching one chunk | Scanned once, the diff is pushed to every player covering it |
| 7 | Are the packet bytes stable | `scanChunk` results are sorted by position, so identical content produces identical packet bytes (easy to compare / debug) |
| 8 | Client rendering | `client/feature/furnace/FurnaceDirtyHighlightRenderer` only "draws boxes from the table" and decides nothing — 100% of the judgement is on the server |
| 9 | The moment the rule is turned off | The client receives clear, but **already drawn boxes only disappear on the next frame** (the client processes packets, it does not splice frames synchronously) |

---

<a id="中文"></a>

## 中文

> 相关规则：[`FurnaceDirtyHighlight`](../02-rules/block.md#furnacedirtyhighlight熔炉脏东西高亮)
> 代码入口：`handler/block/furnaceDirty/{FurnaceDirtyTracker,FurnaceDirtyScanner}.java`、
> `config/impl/section/item/FurnaceDirtyHighlightConfig.java`、
> `network/sync/clientbound/FurnaceDirtySyncChannel.java`、`network/sync/event/FurnaceDirtySyncEvents.java`、
> 客户端 `client/feature/furnace/FurnaceDirtyHighlightRenderer.java`

---

### 解决什么问题

"这只熔炉的输入槽里塞了烧不了的东西、或者已经被垃圾堵住了"——玩家必须打开每只熔炉才能发现。
规则要做的是**在客户端把这种熔炉框成高亮**，让玩家路过就能看见。

难点不在"怎么判定"，而在**怎么把判定结果送达客户端**：

- 脏熔炉的数量**没有上限**（玩家可以摆几千只熔炉）；
- 玩家会到处跑，视距内的熔炉随时都在变；
- 每 tick 广播全量列表 = 带宽和 CPU 双重浪费。

---

### 老方案为什么不行

最早的实现是**全服广播 + 客户端过滤**：每个周期给每个玩家发一份"他附近"的完整列表，客户端整份替换。

两个硬伤：

1. **数据量随脏熔炉总数增长**：客户端只有"整份替换"才能保证正确（否则列表只增不减），
   于是每次都得发全量，脏熔炉越多越发不动；
2. **服务端只能按固定半径扫**：扫不到视距外，也管不住客户端缓存的上限。

---

### 现在的设计：全局脏表 + 按视距增量

```
服务端
  GLOBAL_DIRTY：维度 → 脏熔炉坐标集合       ← 唯一的真相
  VIEWS：玩家 UUID → {维度, 中心区块, 半径, 已同步区块集合}

每 tick：
  ① 规则关着 → 清表 + 推 clear（只清一次）
  ② 对每个玩家 updateView()：
       canSendTo 不通过（没装模组 / 自己关了）→ 丢掉他的视距记录，并 prune
       没跨区块 / 半径没变 → 什么都不做（几次整数比较）
       换维度 → 先推「清空上一维度」的包
       新进入视距的区块 → 先 scanChunk，再把这些区块里的脏点 ADD 推给他
       离开视距的区块 → 把这些坐标 REMOVE 推给他 → 再 prune 没人看的区块
  ③ 每 scanIntervalTicks（默认 20）→ 重扫「当前被任意玩家视距覆盖的区块」，差异推给看着它的玩家
```

**收益**：客户端缓存的上限 ≈ `视距区块数 × 每区块脏熔炉数`，玩家飞多远都不会涨；
服务端只维护一份脏表，不需要为每个玩家准备一份列表。

---

### 关键取舍

#### ① 判定为什么"只看输入槽、只看有没有配方"

`FurnaceDirtyScanner#isInputDirty`：输入槽非空、但用**这只熔炉自己的配方表**找不到能烧它的配方 → 脏。

- 普通熔炉查 `SMELTING`、高炉查 `BLASTING`、烟熏炉查 `SMOKING`；
- **燃料槽不参与判定**（燃料是不是垃圾与"东西烧不烧得出来"无关，规则名里的"脏"专指输入槽）；
- **模组熔炉直接跳过**：其它 `AbstractFurnaceBlockEntity` 子类的配方不一定挂在这三个类型上，
  猜一个反而会误报。

#### ② 为什么扫描粒度是"区块"

`scanChunk` 只遍历这个区块**自己的方块实体表**（`chunk.getBlockEntities()`），
成本只和"区块里的方块实体数"有关，与区块体积无关。这带来两个好处：

- 玩家进入新视距时，只需要扫"新进入的那几个区块"，而不是整个视距；
- 周期重扫可以按"被覆盖的区块集合"来遍历，天然去重（多个玩家看同一区块只扫一次）。

#### ③ 为什么 prune（摘除没人看的区块）必须放在 `VIEWS` 更新之后

`pruneUntracked` 的判断是"还有没有玩家的视距覆盖这个区块"。如果先 prune 再更新 `VIEWS`，
就会把自己**刚刚放弃**的那批区块误判成"还有人看"（因为 `VIEWS` 里还是旧的集合），
脏表于是泄漏。代码里这一步的顺序是刻意的。

同理还有两处 prune：

- **玩家下线**：丢掉他的视距记录后立刻 prune 他独自覆盖的区块；
- **换维度**：旧维度不再由这个玩家覆盖，若他是最后一个，旧维度的脏数据也要摘掉。

#### ④ 为什么 `viewChunkRadius = 0` 表示"跟随服务端视距"

运维改视距通常改 `server.properties` 的 `view-distance`，再重启。如果模组配置里也写死一个数字，
两处就会不一致（模组按 10 扫、服务端按 16 加载，边缘区块永远不高亮）。
所以 `0` = 每 tick 动态读服务端视距；配置日志里打印的也是"解析后的实际值"，
不会出现"日志说 10、实际按 16 扫"。

#### ⑤ 为什么规则关掉要"清表 + 推 clear"

只在服务端停掉扫描是不够的：客户端手里还留着一份坐标表，会继续画框。
所以关规则时 `resetAll` 会清空 `GLOBAL_DIRTY` / `VIEWS` 并给所有装模组的玩家推 clear。
重新打开时 `VIEWS` 是空的 → 每个玩家都会被当成"新玩家"，下个 tick 整批补发。

#### ⑥ 为什么"没装模组的玩家"要单独处理

`canSendTo(player)` 不通过时，服务端会**丢掉他的视距记录**并 prune。
不丢的话会有两个问题：白扫一堆没人看的区块；玩家后来装上模组（或重新打开显示）时，
`VIEWS` 里残留的旧集合会让"新进入视距"判定失效，看起来像"高亮不刷新"。

---

### 设计约定与边界

| # | 场景 | 说明 |
|---|---|---|
| 1 | 数据包重载 | 配方表换了，但**不会立刻重扫**，要等下一次 `scanIntervalTicks` 周期 |
| 2 | 未加载的区块 | 扫描前用 `getChunkNow` 判断，未加载直接跳过 —— **不因为扫描去加载 / 生成区块** |
| 3 | 燃料槽里的垃圾 | 不会高亮（见上） |
| 4 | 模组熔炉 | 永远不高亮（刻意的，避免误报） |
| 5 | 区块卸载 | 脏表里可能短暂保留已卸载区块的坐标；玩家离开视距 / 下线时由 prune 摘掉 |
| 6 | 多个玩家看同一区块 | 只扫一次，差异推给所有覆盖它的玩家 |
| 7 | 包字节是否稳定 | `scanChunk` 结果按坐标排序，同样内容每次生成的包字节一致（便于对比 / 排查） |
| 8 | 客户端渲染 | `client/feature/furnace/FurnaceDirtyHighlightRenderer` 只负责"按表画框"，不做任何判定 —— 判定 100% 在服务端 |
| 9 | 规则关闭的瞬间 | 客户端会收到 clear，但**已经画出来的框要到下一帧才消失**（客户端按包处理，不是同步插帧） |
