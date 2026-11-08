# Deep dive: auto trade refresh (`VillagerAutoTrader`)

How a name tag becomes an in-memory trade-table simulation, and why only the successful roll is ever written back to the villager.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> Related rule: [`VillagerAutoTrader`](../02-rules/villager.md#villagerautotrader自动刷新交易) ·
> Linked: [`VillagerOneTickTraderCount`](../02-rules/villager.md#villageroneticktradercount每-tick-刷新次数),
> [`VillagerAutoLockHitTrader`](../02-rules/villager.md#villagerautolockhittrader自动锁定村民交易),
> [`VillagerSilentTrader`](../02-rules/villager.md#villagersilenttrader无感刷新),
> [`TradeSearch`](../02-rules/villager.md#tradesearch村民交易查询)
>
> Code entry: `handler/entity/villager/trader/refresh/{AutoVillagerTrader,TradeRerollScheduler,TradeTableBuilder}.java`
> + version layer `{TradePoolChecker,TradeSetAdapter,TradeEnchantmentScanner}.java` + `mixin/.../autoTrader/**`

---

### What problem it solves

A player wants "a librarian that trades Mending". Vanilla only offers two routes:

1. Repeatedly **break the workstation** so the villager switches profession and trade table — once a trade has been made the table is locked forever, so only never-traded villagers work;
2. Spawn a crowd of villagers and open their screens one by one — the ones that are not the target all become waste, and the lag is bad.

Both routes share the same problem: **the player spends real time without knowing whether this profession can roll the wanted thing at all**, and the rolling pollutes the world state (a lot of entities, the world random sequence). What this rule does is:

**say "I want Mending" with a name tag, and let the server try in memory, handing only the successful table to the villager.**

---

### Overall design (five steps)

```
① Trigger      the name tag carries the target (mending / emerald / 331a) → right-click the villager
                ↓  name does not parse? pass it through as an ordinary pet name (no message)
② Parse target  first as an enchantment → then as an item → terracotta becomes a paired target ("plain + glazed")
                ↓  this profession cannot roll it? → tell the player at once, never enter simulation (-7)
③ Static producibility  read the trade registry only, compute "the lowest level that can produce it" (requiredLevel)
                ↓  locked / baby / no workstation → refuse (-1)
④ Build the task  pack {villager UUID, dimension, predicate, roll budget, random salt} into the frame-sliced scheduler
                ↓  a fixed budget per tick (VillagerOneTickTraderCount, default 500)
⑤ Write back the hit  the whole table written to the entity at once + lock the level + (optional) lock + refresh an open screen
                ↓
               also take one trade snapshot (the TradeSearch index updates immediately while it is on)
```

**The villager is not touched by a single byte during the simulation**: the `MerchantOffers` built by `TradeTableBuilder` only live inside the task object.

---

### Key trade-offs

#### ① Why not roll on the real villager

- **It pollutes the world random sequence**: vanilla `updateTrades` goes through the world random source of its `LootContext`, so rerolling hundreds of times is the same as scrambling the world's random stream;
- **The player's screen would flicker**: every roll rewrites the trade table, so anyone with the screen open sees the offers scrolling wildly;
- **It would stall the tick**: one reroll samples the whole trade set, hundreds of them are tens of milliseconds, and pushing that into one tick is a visible freeze.
- So: **build our own `LootContext` and feed it our own random source**, without touching `RandomSequences` and without touching the entity.

#### ② Why judge static producibility first

Simulating 1000 times can take tens of seconds. If "a weaponsmith simply cannot roll an enchanted book", making the player wait tens of seconds before saying so is far worse than refusing immediately. So `requiredLevel` is computed from the **read-only registry** first:

- `-1` (`NO_LEVEL`) → reply "this profession cannot roll it", **not a single simulation runs**;
- a value → use it as the "lock level" (on a hit the villager's level is pinned to it, so the trade table matches).

#### ③ Why frame slicing instead of an async thread

The simulation must **read the villager entity** (profession, level, position), build a `LootContext` (with `THIS_ENTITY`) and, after a hit, **change entity fields + send packets to the client** — all of that is main thread only. So "run one batch per tick" was chosen: correctness is preserved and the per-tick cost stays bounded. See [framework 01 · frame slicing](../../framework/01-threading.md) §4. The whole simulation runs on the main-thread frame-sliced scheduler and never enters the server's async pools.

#### ④ Why the seed carries a "random salt"

The seed is derived as `randomSalt + attempt`, where `randomSalt` is one `level.getRandom().nextLong()` taken when the task is triggered and `attempt` is the roll index inside the task:

- every trigger starts from a different point (unpredictable);
- but **the Nth attempt inside one trigger stays reproducible** (same salt + same N = same trade table), which makes troubleshooting easy.

#### ⑤ Why write back only on a hit, and why it must use an `@Accessor("offers")`

- Only the table that hit is written back; a failed task is dropped, so the villager's trades are never "damaged";
- the write-back **must not** call `AbstractVillager#overrideOffers` — in 26.x it is an **empty implementation** (same for `overrideXp`), so calling it does nothing at all. The `offers` field has to be changed directly through an `@Accessor`.
- after the write-back three things still have to happen: lock the level, (optionally) lock the trade, and push the new trades to any player with the screen open (otherwise the screen keeps the old table).

#### ⑥ Why 331a / 331b are special

These two codes ask for "diamond pickaxe + shovel + axe, each with three enchantments". The problem: **a weaponsmith's trade set contains no diamond pickaxe at all** (`weaponsmith → diamond_pickaxe = -1` in the `requiredLevel` matrix), so the "complete set" hit condition **can never hold** for it.

This route therefore switches to "allow filling in afterwards": after the budget is spent, the last result is used as a `finisher` — every diamond tool already present gets the three enchantments overwritten and **whichever piece is missing is added** (the price comes from the reference offer's maxUses/xp, falling back to `3 uses / 30 xp`). That is also why it shares one scheduler with the ordinary route: the scheduler only does "run + one of two endings (hit / exhausted)", and the fill-in strategy is a callback the task carries.

---

### Design rules and boundaries

| # | Scenario | Behaviour / reason |
|---|---|---|
| 1 | The villager is unloaded / killed / changes dimension during the task | `findVillager` looks it up by UUID every tick; if it is gone the task is cancelled with a message (**never holds a strong `Villager` reference**) |
| 2 | The same villager gets another name tag | The old task is **cancelled immediately**, only the new one remains (so two tasks never fight over one villager) |
| 3 | Too many concurrent tasks | Hard cap `MAX_ACTIVE_TASKS = 64`; beyond it the reply is `-8` ("too many tasks"), so tag spam cannot max out the CPU |
| 4 | Roll budget per task | `1000` rolls for ordinary targets, `500` for the 331 combos, **terracotta ×3 = 3000** (the paired hit probability is about 1/528) |
| 5 | Lag while rolling | Per-tick cost ≈ `tasks × rolls per tick × one simulation`; lowering `VillagerOneTickTraderCount` trades time for smoothness |
| 6 | The player still has the screen open after a hit | `sendMerchantOffers` is called to refresh it; otherwise the screen keeps the old trade table |
| 7 | Index update | `VillagerTradePipeline.captureNow` runs right after the write-back; it is a no-op while `TradeSearch` is off |
| 8 | Enchanted book hit | Only the **hit offer's** enchantment levels are overwritten to max; price / uses are untouched |
| 9 | A typo, or the name is the player's own pet name | Passed through unchanged, **no message at all**, and nothing but the name tag itself is consumed |

> About "lock" (`VillagerAutoLockHitTrader`): it does not "freeze the villager"; it ① uses up the first trade once (so nobody can buy it first) + ② pads the experience to at least 1 with `Math.max(1, currentXp)` (`villagerXp > 0` makes later reroll checks fail).
> Because it is a `Math.max`, a villager's existing experience is **never lowered**.

---

<a id="中文"></a>

## 中文

> 相关规则：[`VillagerAutoTrader`](../02-rules/villager.md#villagerautotrader自动刷新交易) ·
> 联动：[`VillagerOneTickTraderCount`](../02-rules/villager.md#villageroneticktradercount每-tick-刷新次数)、
> [`VillagerAutoLockHitTrader`](../02-rules/villager.md#villagerautolockhittrader自动锁定村民交易)、
> [`VillagerSilentTrader`](../02-rules/villager.md#villagersilenttrader无感刷新)、
> [`TradeSearch`](../02-rules/villager.md#tradesearch村民交易查询)
>
> 代码入口：`handler/entity/villager/trader/refresh/{AutoVillagerTrader,TradeRerollScheduler,TradeTableBuilder}.java`
> + 版本层 `{TradePoolChecker,TradeSetAdapter,TradeEnchantmentScanner}.java` + `mixin/.../autoTrader/**`

---

### 解决什么问题

玩家想要一只"能换经验修补的图书管理员"。原版只有两条路：

1. 反复**拆掉工作方块**让村民换职业、换交易表 —— 一旦交易过就永久锁死，只能靠没交易过的村民；
2. 刷一大堆村民，一个个开界面看 —— 找到之后其他村民全变废料，卡顿还严重。

两条路的共同问题是：**玩家花的是真实时间，却不知道"这个职业到底能不能刷出我要的东西"**，
而且刷的过程会污染世界状态（大量实体、世界随机序列）。这条规则要做的是：
**用命名牌说一句"我要经验修补"，剩下的事交给服务端在内存里试，只把成功的那一份交给村民。**

---

### 整体设计（五步）

```
① 触发        命名牌写目标（mending / 绿宝石 / 331a）→ 贴到村民身上
                ↓  解析不出名字？直接放行，当普通宠物名处理（不弹提示）
② 目标解析    先当附魔查 → 再当物品查 → 陶瓦识别成"普通 + 釉彩"成对目标
                ↓  这个职业刷不出？    → 立刻告诉玩家，不进入模拟（-7）
③ 静态可产性   只读交易注册表，算"最低几级能出"（requiredLevel）
                ↓  已锁定 / 小孩 / 无工作站 → 拒绝（-1）
④ 建任务      打包 {村民 UUID, 维度, 谓词, 上限次数, 随机盐} 丢进分帧调度器
                ↓  每 tick 跑固定预算（VillagerOneTickTraderCount，默认 500）
⑤ 命中写回    整张表一次性写回实体 + 锁等级 +（可选）锁定 + 刷新已开界面
                ↓
              顺手抓一次交易快照（TradeSearch 开着时索引立刻更新）
```

**模拟期间村民一个字节都不动**：`TradeTableBuilder` 生成的 `MerchantOffers` 只活在任务对象里。

---

### 关键取舍

#### ① 为什么不在真实村民身上直接刷

- **会污染世界随机序列**：原版 `updateTrades` 走 `LootContext` 的世界随机源，重掷几百次等于把世界随机流打乱；
- **玩家界面会狂闪**：每掷一次就改一次交易表，正开着界面的人会看到交易条目疯狂滚动；
- **会卡 tick**：一次重掷要跑完整交易集抽样，几百次是几十毫秒级，直接塞进一个 tick 就是明显的卡顿。
- 所以：**自己建 `LootContext` 并塞进自带的随机源**，不碰 `RandomSequences`，也不碰实体。

#### ② 为什么先做静态可产性判定

模拟 1000 次可能要几十秒。如果"武器匠根本刷不出附魔书"，让玩家等完几十秒才被告知，
体验比"立刻拒绝"差得多。所以先用**只读注册表**算出 `requiredLevel`：

- `-1`（`NO_LEVEL`）→ 直接回"这个职业刷不出"，**一次模拟都不跑**；
- 有值 → 把它当"锁等级"用（命中后把村民等级固定到这一级，交易表才对得上）。

#### ③ 为什么分帧而不是异步线程

模拟要**读村民实体**（职业、等级、位置）、要构造 `LootContext`（带 `THIS_ENTITY`）、
命中后还要**改实体字段 + 给客户端发包** —— 这些只能在主线程。于是选"每 tick 跑一批"：
既保证正确性，又把单 tick 耗时压到可控范围。详见[框架 01 · 分帧](../../framework/01-threading.md) §4。
整段模拟都跑在主线程的分帧调度器上，不会进服务端的异步池。

#### ④ 为什么种子要带"随机盐"

种子按 `randomSalt + attempt` 派生，其中 `randomSalt` 是任务触发时抓的一个
`level.getRandom().nextLong()`，`attempt` 是任务内的第几次尝试：

- 每次触发的起点不同（不可预测）；
- 但**单次触发内的第 N 次尝试仍然可复现**（同样的盐 + 同样的 N = 同样的交易表），排查问题很方便。

#### ⑤ 为什么命中才写回，且必须用 `@Accessor("offers")`

- 只有命中的那一份表才写回，失败的任务直接丢弃，村民的交易从未被"改坏过"；
- 写回**不能**调 `AbstractVillager#overrideOffers` —— 26.x 里它是**空实现**（`overrideXp` 同），
  调了等于什么都没发生。必须通过 `@Accessor` 直接改 `offers` 字段。
- 写回后还要做三件事：锁等级、（可选）锁定、把新交易推给正开着界面的玩家（否则界面里还是旧表）。

#### ⑥ 331a / 331b 为什么是特例

这两个暗号要的是"钻石镐 + 钻锹 + 钻斧 三件套，各带三附魔"。问题在于：
**武器匠的交易集里根本没有钻石镐**（`requiredLevel` 矩阵里 `weaponsmith → diamond_pickaxe = -1`），
所以"三件套齐全"这个命中条件对它**永远不成立**。

于是这条路线改成"允许事后补齐"：跑满次数后用最后一次结果做 `finisher` ——
把已有的钻石工具全部覆写三附魔，**缺哪件补哪件**（价格用参考条目的 maxUses/xp，
取不到就用兜底 `3 次 / 30 xp`）。这也是为什么它和普通路线共用同一个调度器：
调度器只负责"跑 + 命中/耗尽两种收尾"，补齐策略是任务自带的回调。

---

### 设计约定与边界

| # | 场景 | 行为 / 原因 |
|---|---|---|
| 1 | 任务期间村民被卸载 / 死亡 / 换维度 | `findVillager` 每 tick 用 UUID 现查，查不到就取消任务并提示（**绝不强引用 `Villager`**） |
| 2 | 同一村民再次贴命名牌 | 旧任务**立刻取消**，只留新的一个（避免两个任务抢同一只村民） |
| 3 | 同时进行的任务太多 | 上限 `MAX_ACTIVE_TASKS = 64`，超了返回 `-8`（"任务太多"），防止刷屏式命名把 CPU 顶满 |
| 4 | 单任务次数上限 | 普通目标 `1000` 次、331 组合 `500` 次、**陶瓦 ×3 = 3000 次**（成对命中概率约 1/528） |
| 5 | 刷取过程中的卡顿 | 单 tick 成本 ≈ `任务数 × 每 tick 次数 × 单次模拟`；把 `VillagerOneTickTraderCount` 调低可以换时间 |
| 6 | 命中后玩家还开着界面 | 主动 `sendMerchantOffers` 刷新；否则界面里是旧交易表 |
| 7 | 索引更新 | 写回后立刻 `VillagerTradePipeline.captureNow`；但 `TradeSearch` 关着时是空操作 |
| 8 | 附魔书命中 | 只把**命中的那一笔**的附魔等级覆写到满级，价格 / 交易次数不动 |
| 9 | 名字写错 / 是玩家自己的宠物名 | 原样放行，**不弹任何提示**，也不消耗命名牌以外的任何东西 |

> 关于"锁定"（`VillagerAutoLockHitTrader`）：它不是"把村民冻住"，而是
> ① 把第一笔交易用掉一次（别人没法先买走）+ ② 把经验用 `Math.max(1, currentXp)` 垫到至少 1（`villagerXp > 0` 会让后续刷新判定不通过）。
> 因为走的是 `Math.max`，村民原有经验**永远不会被降低**。
