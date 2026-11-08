# Deep dive: trade completion data (`TradeItemCompletionCache`)

The completion data is derived from the trade registries once, kept outside the session state, and answered by the vanilla completion path.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> Related rule: [`TradeSearch`](../02-rules/villager.md#tradesearch村民交易查询) ·
> [`VillagerAutoTrader`](../02-rules/villager.md#villagerautotrader自动刷新交易) (name tag parsing shares the same alias table)
> Code entry: `cache/impl/TradeItemCompletionCache.java`,
> command side `command/c/impl/rule/villager/TradeSearchCommand.java`, config `config/impl/TradeIndexConfig.java`

---

### What problem it solves

`/ec c TradeSearch item|book <keyword>` and the name tag both need "which trade items / which enchantments can appear in this save":

- **a hard-coded list does not work**: datapacks and other mods change villager trade sets, and a hard-coded list is outdated within a year;
- **computing it at query time does not work**: walking the whole `VILLAGER_TRADE` registry and resolving every entry takes tens to a hundred milliseconds,
  and tab completion is asked on every keystroke — it can never compute on the spot.

So there has to be **completion data derived from the registry, rebuildable and manually refreshable** — that is `TradeItemCompletionCache`.

---

### Where the data comes from

```
Registry (the real source: datapacks + mods)
   VILLAGER_TRADE          every trade (what it wants / what it gives / the random enchantment pool)
   TRADE_SET               each profession's "trade set" per level
   VILLAGER_PROFESSION     profession → tradeSet for levels 1..5
        ↓  ① "narrow down" first: keep only the trades a villager can actually get
   villagerReachableTrades(): walk every profession (skipping NONE), take the tradeSets of levels 1..5,
                              then expand them into concrete trades. Any step failing → fall back to scanning the whole registry
        ↓  ② collect trade by trade
   wants (main price item) + additionalWants (second price item) + gives (result)
   gives is an enchanted book → record its enchantments; then look at the enchant_randomly candidate pool
        ↓  ③ produce completion strings
   items: id (e.g. minecraft:bookshelf) + alias (书架)
   enchantments: path (mending) + full id (minecraft:mending) + alias (经验修补)
```

Three key points:

1. **"Narrowing down" is there so players are not misled**: the full registry may carry wandering-trader trades, removed professions or mod-only trades,
   and putting them into completion means telling the player "this is searchable" when villagers never sell it.
2. **Only enchantments that "can be rolled" count**: the `enchant_randomly` candidate pool is included, the concrete random result is not —
   because the rule decides "can it be rolled at all", not "what did this particular trade roll".
3. **The alias table is the fallback**: `TradeIndexConfig` provides a two-level mapping of `trade_index.json` → built-in fallback,
   so searching "emerald", "bookshelf" or "mending" (the names players actually use) all hit.

The completion results are computed on the server and delivered through the **vanilla command completion (Brigadier suggestion)** path with the completion request;
there is no custom completion channel.

---

### When it is refreshed

| Moment | Which path | Why |
|---|---|---|
| Server start (`SERVER_STARTED`) | **synchronous** `refresh(server)` | no player is waiting for completion yet; computing it in one go is the simplest |
| Datapack reload (`END_DATA_PACK_RELOAD`) | synchronous `refresh`, and **only on success** | the registries were swapped wholesale; without clearing, the items of the old datapack would remain; on failure the previous result is kept (the old table is mostly still valid, clearing would be worse) |
| Manual trigger by a player | **asynchronous** `refreshAsync` | walking the registries is expensive and must not block the main thread; the result is swapped into the cache after switching back to the main thread |

The async path has three details:

- **debounce**: `REFRESHING` is an `AtomicBoolean`, so repeated clicks only report "computing in the background", never submit twice;
- **unlock before failure handling**: the catch block **first** does `REFRESHING.set(false)` and only then `server.execute(...)` to send the message —
  otherwise, during shutdown, the task inside `server.execute` may never run and the lock would stay taken forever;
- **pool rejection**: when `executeQuery` returns `false` (queue full), unlock immediately and report "system busy".

---

### Key trade-offs

#### ① Why only `compute()` goes to the async side

`compute()` **only reads registries and produces primitives** (`Set<Identifier>` / `Set<String>` / two counters),
it touches no `ServerPlayer`, builds no `Component` and sends no packets.
`applyResult()` writes the result into 4 `volatile` static fields on the main thread — the read side (tab completion) always sees
"one complete new table" or "one complete old table", never an intermediate state.

#### ② Why it does not implement `SessionState`

The meaning of `SessionState` is "clear it when the session ends" (disconnect / shutdown). But this data is **not session state**:

- it is derived from the **registry**, which lives outside the save (datapacks / mods), not "temporary data of this game session";
- clearing it has exactly one consequence: **the next tab completion is empty** (a completion gap) until somebody refreshes manually or reloads the datapacks;
- on shutdown it disappears with the JVM anyway, and after a restart `SERVER_STARTED` computes it again.

So it deliberately does **not** implement `SessionState`, and deliberately does **not** persist — persisting would only make "the disk holds the items of an old datapack" more likely.

#### ③ Why the completion lists are "sorted and immutable"

`applyResult` sorts with a `TreeSet` and wraps the result into an immutable collection. The reason: the order of the completion list stays stable,
so a player cycling with Tab never sees letters jump because the collection order changed; it also keeps the read side from modifying this shared collection by accident.

#### ④ What happens while the cache is empty

The command side has a fallback: while the completion data has not been computed yet, it falls back to `TradeIndexConfig#getAllSearchableNames()`
(the static alias table). So **the completion list is never completely empty**.

---

### Design rules and boundaries

| # | Scenario | Note |
|---|---|---|
| 1 | Datapack reload failed | Keep the previous result and log a WARN (neither refresh nor clear) |
| 2 | The `TRADE_SET` registry cannot be read | No profession narrowing; fall back to scanning the whole `VILLAGER_TRADE` (and say so in the log) |
| 3 | One trade fails to resolve | Only that trade is skipped, the rest are unaffected (`collectItem` / `collectEnchantments` both carry try/catch) |
| 4 | Not a single enchantment collected | Fall back to "list every enchantment in the registry" (rather too many than none) |
| 5 | It only covers **villager** trades | Wandering-trader trades are outside the narrowing, so completion never offers them — consistent with `TradeSearch`, which only collects "locked villager trades" |
| 6 | The message of a manual refresh | The three replies — done / failed / busy — are hard-coded Chinese strings (they do **not** go through the server translation table) |
| 7 | Completion and the index are not the same data | Completion is "what the registry could roll" (theory), the index is "who is actually selling in this save" (fact). They are independent; do not mix them |

---

<a id="中文"></a>

## 中文

> 相关规则：[`TradeSearch`](../02-rules/villager.md#tradesearch村民交易查询) ·
> [`VillagerAutoTrader`](../02-rules/villager.md#villagerautotrader自动刷新交易)（命名牌解析共用同一张别名表）
> 代码入口：`cache/impl/TradeItemCompletionCache.java`、
> 命令侧 `command/c/impl/rule/villager/TradeSearchCommand.java`、配置 `config/impl/TradeIndexConfig.java`

---

### 解决什么问题

`/ec c TradeSearch item|book <关键词>` 和命名牌都需要"这个存档能出现哪些交易物 / 哪些附魔"：

- **写死列表不行**：数据包、其它模组都会改村民交易集，硬编码的列表一年就过时了；
- **运行时现算不行**：遍历整个 `VILLAGER_TRADE` 注册表 + 逐条解析要几十到上百毫秒，
  Tab 补全这种每敲一个字都会问一次的场景，绝不能现算。

所以要有一份**从注册表派生、可重建、可手工刷新**的补全数据 —— 这就是 `TradeItemCompletionCache`。

---

### 数据从哪来

```
注册表（数据包 + 模组的真实来源）
   VILLAGER_TRADE          每一笔交易（要什么 / 给什么 / 附魔随机池）
   TRADE_SET               每个职业每个等级的"交易集"
   VILLAGER_PROFESSION     职业 → 1..5 级的 tradeSet
        ↓  ① 先"收口"：只保留村民真的能拿到的交易
   villagerReachableTrades()：遍历所有职业（跳过 NONE），取 1..5 级的 tradeSet，
                              再展开成具体交易。任一步失败 → 退回扫描整个注册表
        ↓  ② 逐笔收集
   wants（主价格物） + additionalWants（第二价格物） + gives（产物）
   gives 是附魔书 → 记下它带的附魔；再看 enchant_randomly 的候选池
        ↓  ③ 生成补全串
   物品：id（如 minecraft:bookshelf） + 别名（书架）
   附魔：path（mending） + 全 id（minecraft:mending） + 别名（经验修补）
```

三个关键点：

1. **"收口"是为了不误导玩家**：整个注册表里可能有流浪商人 / 已删除职业 / 模组专用交易，
   把它们放进补全等于告诉玩家"能搜到"，实际上村民根本不卖。
2. **附魔只看"能刷出的"**：`enchant_randomly` 的候选池算进去，具体随机结果不算 ——
   因为规则做的是"能不能刷出"的判断，不是"这一笔刷到了什么"。
3. **别名表兜底**：`TradeIndexConfig` 提供 `trade_index.json` → 内置兜底的两级映射，
   所以搜「绿宝石」「书架」「经验修补」都能命中（都是玩家自己会用的叫法）。

补全结果由服务端算好后，随命令补全请求按**原版补全（Brigadier suggestion）**下发，
没有自定义的补全通道。

---

### 什么时候刷新

| 时机 | 走哪条路 | 为什么 |
|---|---|---|
| 服务端启动（`SERVER_STARTED`） | **同步** `refresh(server)` | 此时没有玩家在等补全，一次算清最省事 |
| 数据包重载（`END_DATA_PACK_RELOAD`） | 同步 `refresh`，且**只在 success 时** | 注册表整体换了一份，不清就还是旧数据包的物品；失败时保持上一次结果（旧表多半仍有效，清空反而更糟） |
| 玩家手动触发 | **异步** `refreshAsync` | 遍历注册表是耗时活，不能卡主线程；结果切回主线程后再替换缓存 |

异步那条路有三个细节：

- **防抖**：`REFRESHING` 是 `AtomicBoolean`，重复点只提示"正在后台计算"，不会重复提交；
- **失败先解锁**：catch 里**先** `REFRESHING.set(false)` 再 `server.execute(...)` 发提示 ——
  否则停服过程中 `server.execute` 里的任务可能永远不执行，锁就此卡死；
- **池子拒绝**：`executeQuery` 返回 `false`（队列满）时立刻解锁并提示"系统繁忙"。

---

### 关键取舍

#### ① 为什么异步只把 `compute()` 放出去

`compute()` 里**只读注册表、只产出基础类型**（`Set<Identifier>` / `Set<String>` / 两个计数），
不碰任何 `ServerPlayer`、不构造 `Component`、不发包。
`applyResult()` 在主线程把结果写进 4 个 `volatile` 静态字段 —— 读侧（Tab 补全）永远看到的是
"一份完整的新表"或"一份完整的旧表"，不会看到中间态。

#### ② 为什么不实现 `SessionState`

`SessionState` 的语义是"会话结束要清空"（断线 / 停服）。但这份数据**不是会话态**：

- 它由**注册表**派生，而注册表是存档外的东西（数据包 / 模组），不是"玩家这一局的临时数据"；
- 清空它只有一个后果：**下一次 Tab 补全变空**（补全空窗期），直到有人手动刷新或重载数据包；
- 停服时它本来就会随 JVM 一起没，重启后 `SERVER_STARTED` 会重算。

所以它刻意**不**实现 `SessionState`，也刻意**不落盘** —— 落盘反而更容易出现"磁盘上是旧数据包的物品"这种脏数据。

#### ③ 为什么补全列表要"排序后不可变"

`applyResult` 用 `TreeSet` 排一遍再包成不可变集合。原因：补全列表的顺序稳定，
玩家按 Tab 循环时不会因为集合顺序变化而"跳字"；同时避免读侧误改这份共享集合。

#### ④ 缓存为空时怎么办

命令侧有兜底：补全数据还没算出来时，退回 `TradeIndexConfig#getAllSearchableNames()`
（静态别名表）。所以**永远不会出现"补全列表彻底是空的"**。

---

### 设计约定与边界

| # | 场景 | 说明 |
|---|---|---|
| 1 | 数据包重载失败 | 保持上一次结果并打 WARN（不刷新，也不清空） |
| 2 | 读不到 `TRADE_SET` 注册表 | 不做职业收口，退回扫描整个 `VILLAGER_TRADE`（并在日志里说明） |
| 3 | 一笔交易解析失败 | 只跳过这一笔，不影响其它（`collectItem` / `collectEnchantments` 都带 try/catch） |
| 4 | 附魔一个都没收到 | 退回"把注册表里所有附魔都列出来"（宁多勿空） |
| 5 | 只覆盖**村民**交易 | 流浪商人交易不在收口范围内，所以补全里搜不到它 —— 与 `TradeSearch` 只收"村民已锁定交易"的口径一致 |
| 6 | 手动刷新的提示 | 完成 / 失败 / 繁忙三条提示是硬编码中文串（**没有走服务端翻译表**） |
| 7 | 补全与索引不是同一份数据 | 补全是"注册表能刷出什么"（理论），索引是"存档里实际谁在卖"（事实）。两者各自独立，不要混用 |
