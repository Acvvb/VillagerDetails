# Business Glossary

The business-level terms of the mod, plus one-line business placeholders for the framework terms that are defined in `doc/framework/10-glossary.md`.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> This table only explains **business concepts** (things a player / server owner can perceive).
> **Framework terms** such as "snapshot pool", "epoch", "tombstone" and "observer queue" are only registered here with "one sentence that is enough from the business side" + a link;
> **the detailed definitions are in [`doc/framework/10-glossary.md`](../framework/10-glossary.md)** (the framework docs are the defining side).

---

### 1. Rule system

| Term | Meaning | Where it appears |
|---|---|---|
| **规则 (rule)** | One entry of the `RuleType` enum — the smallest unit of "a feature that can be switched on and off on its own". 40 of them today | [`01-rule-index.md`](01-rule-index.md) |
| **注册名 (registerName)** | The name a rule goes by inside commands, and the key it is persisted under. Example: `InfiniteShear` | the "register name" of every rule |
| **状态值 (state)** | The current value of a rule, **always a string**. `false` = off, everything else counts as on | [`00-overview.md`](00-overview.md) §4.2 |
| **档位 (permission tier)** | The level a state value names when it is `true / ops / master / admin / owner`. It is **both** the switch and "who may trigger it" | [`00-overview.md`](00-overview.md) §4.3 |
| **快捷开关 (quickSwitches)** | Every legal value of this rule (single choice — exactly the tab-completion list of `/ec <rule> <state>`) | `RuleType#getQuickSwitches` |
| **自定义列表规则 (custom list rule)** | A rule whose `quickSwitches` contain words other than on / off; in that case **any non-empty text** is accepted (e.g. `Language`, `HappyGhastSpeed`, `VillagerHardWorking`) | [`00-overview.md`](00-overview.md) §4.2 |
| **逐条配置类规则 (list-config rule)** | A rule that keeps a "list / value table" on top of its switch (`RuleConfigKind.LIST`). Its `/ec c` subcommand is **hard-wired to owner** | `NoSqueeze`, `BlockMiningResistance`, `MobNoSpawn` … |
| **状态校验器 (stateValidator)** | Rule-level format / range validation that stops illegal values before they are written. Only `HappyGhastSpeed` has one attached today | `RuleStateValidator` |
| **短路 (short-circuit, value unchanged)** | Setting a rule to the value it already has makes `setState` return early and **not a single callback fires** | [`00-overview.md`](00-overview.md) §5 |
| **权限节点 (permission node)** | The node additionally checked when a rule takes effect for a player: `villagerdetails:c.<register name in lower case>` (framework definition: [glossary · rule framework](../framework/10-glossary.md#5-规则框架)) | `RuleCache.isEnabled(player, rule)` |
| **`/ec` 总权限 (`/ec` master permission)** | The value of the `RuleCommand` rule: it decides "may see / may change rules"; the node is `villagerdetails:c.base` | [`02-rules/settings.md`](02-rules/settings.md) |
| **扫包注册 (package-scan registration)** | No manifest: event / channel implementation classes are discovered by reflecting over package names (framework definition: [package scanning](../framework/04-session-state.md#2-扫包机制三层)) | `event/impl/register/**` |
| **落盘 (persist)** | Writing in-memory state / config to the save files; asynchronous and delayed, the opposite of "side effects take effect immediately" (framework definition: [seven-stage data flow](../framework/02-config-pipeline.md#1-七段数据流)) | `RuleSwitchConfig`, `RuleConfig` |

---

### 2. Villagers and trading

| Term | Meaning | Where it appears |
|---|---|---|
| **交易表 (offers)** | All trade entries a villager currently has (`MerchantOffers`). Each entry has "cost item / result item / max uses / uses so far" | [`auto-trader.md`](03-deep-dives/auto-trader.md) |
| **交易集 / 候选池 (trade set / candidate pool)** | The vanilla candidate set defined by "profession + level"; generating a trade table draws several entries from it. The mod's simulation simply **re-draws by the same rules** | version layer `TradePoolChecker`, `TradeSetAdapter` |
| **锁定交易 (locked)** | Vanilla semantics: once a villager has traded, the whole trade table is frozen and no longer re-rolls with level. Test = `villagerXp > 0` **or** any entry with `uses > 0` | `VillagerTradePipeline#hasLockedTrades` |
| **快照 (trade snapshot)** | The main thread **deep-copies one villager's trade table into plain data** (UUID / position / dimension / item id / count / price / enchantment id + level). It holds no vanilla object at all | [`trade-search.md`](03-deep-dives/trade-search.md) |
| **索引 (trade index)** | The in-memory table of "who sells what" for the whole save (`VillagerTradeCache`); a query only reads it and never walks the world | [`trade-search.md`](03-deep-dives/trade-search.md) |
| **墓碑 (tombstone)** | The "this UUID is void" marker a villager leaves in the index after dying; it blocks **late** asynchronous writes (framework definition: [glossary · concurrency semantics](../framework/10-glossary.md#7-并发语义)) | `VillagerTradeCache.remove` |
| **纪元 (epoch)** | The number of one "whole-generation change" of the index; a snapshot taken before the change may not be written back (framework definition: [glossary · concurrency semantics](../framework/10-glossary.md#7-并发语义)) | `VillagerTradePipeline#submit` |
| **快照池 / 查询池 (snapshot pool / query pool)** | Two independent background thread pools: one only takes "index writes", the other only takes "index queries" (framework definition: [the four execution lanes](../framework/01-threading.md#1-四条执行线)) | `ServerThreadManager` |
| **可产性 (requiredLevel)** | A **read-only** judgement of "from which level on this profession can roll the target item". When the answer is "cannot be rolled" the player is told directly and no simulation starts | `TradePoolChecker.requiredLevel` |
| **命名牌触发 (name tag trigger)** | Naming a villager with a name tag = giving the mod an order. Recognised names: item id / Chinese alias, enchantment id / Chinese name, `331a`, `331b`. Anything that cannot be parsed is treated as an ordinary name and **no message is shown** | [`auto-trader.md`](03-deep-dives/auto-trader.md) |
| **331a / 331b** | The codes for rolling a fixed combination: a **weaponsmith** is rolled the "diamond pickaxe + diamond shovel + diamond axe" set, each with three enchantments. `331a` = Silk Touch (Fortune → Silk Touch), `331b` = Fortune | `AutoVillagerTrader#enqueueCombo` |
| **陶瓦成对 (paired terracotta)** | When the target says "terracotta", a hit does not mean "this terracotta appeared" but "**plain terracotta + the matching glazed terracotta appear together**" (roughly 1/528 per roll, hence the 3× task cap) | `AutoVillagerTrader#resolveTarget` |
| **命中 / 补齐 (hit / finisher)** | Hit = the result of one simulation satisfies every condition. Finisher = one pass over the whole table after a hit (or after the rolls run out), e.g. topping an enchanted book up to max level or filling in the tools the `331` set is missing | `TradeRerollScheduler.Finisher` |
| **随机盐 (randomSalt)** | A `long` drawn from the world random source the moment the player triggers a refresh, used to break "the same villager always hits on the Nth roll" | [`auto-trader.md`](03-deep-dives/auto-trader.md) |
| **分帧 (slice)** | A reroll task does not run to the end in one go: it runs a fixed budget per tick (set by `VillagerOneTickTraderCount`) and carries the rest over to the next tick (framework definition: [frame slicing](../framework/01-threading.md) §4) | `TradeRerollScheduler.runSlice` |
| **无感刷新 (silent refresh)** | Right after a refresh is triggered (when the task is **enqueued**) the custom name left by the name tag is cleared, so the player sees no trace of the trigger; when the refresh is rejected the name is kept so it can be retried (`VillagerSilentTrader`) | `NameTagItemMixin` |
| **别名表 / trade_index (alias table / trade_index)** | The "human name" mapping for items and enchantments (`绿宝石` → `minecraft:emerald`); both command tab completion and name tag parsing rely on it | `TradeIndexConfig` + `trade_index.json` |
| **补全数据 (completion data)** | The "which trade items / enchantments can appear in this save" set computed on the server, used for Tab completion | [`tab-completion.md`](03-deep-dives/tab-completion.md) |
| **空交易 (empty trade)** | A trade entry whose result item is empty; when taking a snapshot **only this kind is skipped** — there is no "is it worth indexing" filter | [`trade-search.md`](03-deep-dives/trade-search.md) |
| **制备 / 拆解 (provisioning / decomposition)** | When paying, if the player has no "small change" (emeralds / books), the mod **breaks open** the emerald blocks / bookshelves in the inventory on the spot by a fixed recipe | version layer `ItemProvisioner` |

---

### 3. Binding, selection and portals

| Term | Meaning | Where it appears |
|---|---|---|
| **BB 选区 (BB selection)** | A block-region selection: the two corners `pos1` / `pos2` (`/ec c selection pos1\|pos2\|show\|clear`, or left / right click while holding a wooden axe) | `BBSectionStateCache` |
| **EB 选区 (EB selection)** | An "entity + block" dual selection: hit the entity with the tool first, then the block; the binding fires as soon as both sides are set | `EBSelectionStateCache` |
| **绑定工具 (binding tool)** | A lead whose name tag says `bed` / `work` / `move`. The three names map to the three `BindingType`s | `BindingType` |
| **区域绑定 (region binding)** | A command shape that "selects a region first, then binds one record, then adds / removes / queries by `#ID`" (`bind` / `show` / `view` / `delete` / `set` / `clear`) | `AbstractRegionBindingCommand` |
| **两侧配对 (two-sided pairing)** | The shape only `PortalBinding` has: one `set` in the overworld, one `set` in the nether, and only `bind` actually creates an entry | [`portal-binding.md`](03-deep-dives/portal-binding.md) |
| **绑定 ID (binding ID, `#ID`)** | The auto-increment number the config layer hands out: it **only ever grows and deletion does not recycle it**. That is why `#3` in chat always means the same record | `PortalBindingConfig`, `PortalPiglinNoSpawnConfig` |
| **原版理论落点 (vanilla theoretical destination)** | Where vanilla would send someone, computed purely from coordinates without looking at the world: overworld ↔ nether scale XZ by 1:8 and leave Y unchanged | `PortalBindingService` |
| **增强模式 (enhanced mode)** | `PortalBindingEnhanced`: skips the "a portal must already exist at the target" check; with no portal it finds a foothold itself (possibly building one on the spot) | version layer `EntityPortalMixin` |
| **预热 / 刷新 (prewarm / refresh)** | Prewarm = resolve both sides' portals at `bind` time and write them into the cache (so the first teleport does not stutter); refresh = clear the cache and scan again | `PortalBindingService#cachePortal` / `refreshCachedPortals` |
| **强制传送 (forced teleport)** | When an entity that meets the rule tier (with the rule on) walks into a bound region, it **bypasses** vanilla's "nearest portal" logic and is sent straight to the bound target | [`portal-binding.md`](03-deep-dives/portal-binding.md) |
| **僵尸猪灵拦截点 (zombified piglin spawn hook)** | The "spawn a zombified piglin by chance" stretch inside a nether portal block's `randomTick`; `PortalPiglinNoSpawn` simply cuts it out | version layer `NetherPortalSpawnMixin` |

---

### 4. Other gameplay

| Term | Meaning | Where it appears |
|---|---|---|
| **挤压 (squeeze)** | When entities overlap, vanilla pushes them apart and deals cramming damage to some of them. `NoSqueeze` makes the listed entities immune to the whole thing | `SqueezeHandler` |
| **金块交易 (gold block barter)** | `PiglinGoldBlockMultiply`: when a piglin picks a gold block up it is **swapped for gold ingots on the spot and tagged with a command tag**; the output stage sees the tag and runs 9 independent rolls; the wait time is multiplied by the punishment factor | `PiglinAiMixin` |
| **载具是玩家 (the vehicle is a player)** | In `PlayerHeadSitting`, "sitting on someone's head" uses a **player** as the vehicle, and the vanilla player type is `noSave()` — so logging out / unloading has to clear the passengers itself, otherwise "Trying to save removed" is logged and the player turns invisible on the client | [`player-sitting.md`](03-deep-dives/player-sitting.md) |
| **客户端开关 (client switch)** | Whether sitting is possible under `PlayerBlockSitting` is decided by the player's **own client** preference; players without this mod count as "on" | `PlayerBlockSittingPreference` |
| **脏熔炉 (dirty furnace)** | The input slot holds something that cannot be smelted, or the fuel slot holds no valid fuel. `FurnaceDirtyHighlight` does the judgement + the highlight | [`furnace-dirty.md`](03-deep-dives/furnace-dirty.md) |
| **脏表 (dirty table)** | The server-side global table of "dimension → dirty furnace coordinates"; it only keeps chunks **covered by at least one player's view distance** | `FurnaceDirtyTracker.GLOBAL_DIRTY` |
| **视距区块 (view-distance chunk)** | The chunk range a player can see / can load; used to work out "which coordinates have to be pushed to this player" | `FurnaceDirtyHighlightConfig` |
| **服务端语言 (server language)** | The value of the `Language` rule = the language of the server translation table. `/ec` replies, rule names and descriptions are all translated on the server before they are sent | [`02-rules/settings.md`](02-rules/settings.md) |
| **客户端界面语言 (client UI language)** | The text shown in the settings screen **does not follow** the server language but the client's own UI language (the server translates by the language the client reports and then sends it down) | `ServerTranslations.resolve(Component, String)` |

---

### 5. Framework terms (defined in the framework docs)

The words in this table are **used without explanation** when they show up in the business docs, because they belong to the framework layer.
**The defining side is [`doc/framework/10-glossary.md`](../framework/10-glossary.md)**; here we only give "one sentence that is enough from the business side" + a link.

| Term | One-liner (enough from the business side) | Detailed definition |
|---|---|---|
| **主线程 / 服务端线程 (main thread / server thread)** | The only thread that may safely read or write the world and entities, and send packets | [the four execution lanes](../framework/01-threading.md#1-四条执行线) |
| **快照池 (snapshot pool)** | The background pool that only takes "trade index writes" | [the four execution lanes](../framework/01-threading.md#1-四条执行线) |
| **查询池 (query pool)** | The background pool that only takes "trade index queries" (kept apart from the snapshot pool so queries cannot push write tasks aside) | [the four execution lanes](../framework/01-threading.md#1-四条执行线) |
| **配置写线程 (config write thread)** | The single writer that owns config persistence | [seven-stage data flow](../framework/02-config-pipeline.md#1-七段数据流) |
| **纪元 (epoch)** | The index generation number; it blocks late writes from before the change | [glossary · concurrency semantics](../framework/10-glossary.md#7-并发语义) |
| **墓碑 (tombstone)** | The "void" marker of a dead villager; it blocks late asynchronous writes | [glossary · concurrency semantics](../framework/10-glossary.md#7-并发语义) |
| **观察者队列 (observer queue)** | The notification mechanism for "this config object changed and has to be persisted" | [observer queue and throttling](../framework/03-config-lifecycle.md#3-观察者队列与节流) |
| **脏标记 (dirty flag, `markDirty`)** | Tells the config framework "this data changed, persist it later" | [seven-stage data flow](../framework/02-config-pipeline.md#1-七段数据流) |
| **规则描述符 (descriptor)** | The "rule list + current state + allowed values + translated text" that is sent down to the client | [rule descriptor](../framework/05-rule-framework.md#5-规则描述符) |
| **会话缓存 (SessionState)** | The in-memory cache that has to be cleared on shutdown / disconnect | [contract](../framework/04-session-state.md#1-契约) |
| **扫包 (package scanning, `ChannelScanner`)** | Discover and register implementation classes by reflecting over package names | [package scanning](../framework/04-session-state.md#2-扫包机制三层) |
| **权限节点 (permission node)** | A permission id of the form `villagerdetails:c.<rule>` | [glossary · rule framework](../framework/10-glossary.md#5-规则框架) |

---

<a id="中文"></a>

## 中文

> 这份表只解释**业务概念**（玩家 / 服主视角能感知的东西）。
> 「快照池」「纪元」「墓碑」「观察者队列」这类**框架术语**在这里只登记"业务视角够用的一句话" + 链接，
> **详细定义在 [`doc/framework/10-glossary.md`](../framework/10-glossary.md)**（框架文档是定义方）。

---

### 1. 规则系统

| 术语 | 含义 | 出现在哪 |
|---|---|---|
| **规则（rule）** | `RuleType` 枚举里的一条，是"一个可独立开关的功能"的最小单位。当前 40 条 | [`01-rule-index.md`](01-rule-index.md) |
| **注册名（registerName）** | 规则在命令里的名字，也是落盘用的键。例：`InfiniteShear` | 每条规则的「注册名」 |
| **状态值（state）** | 规则的当前取值，**永远是一个字符串**。`false` = 关，其余都算开 | [`00-overview.md`](00-overview.md) §4.2 |
| **档位（permission tier）** | 状态值取 `true / ops / master / admin / owner` 时的那个等级。它**同时**是开关和"谁能触发" | [`00-overview.md`](00-overview.md) §4.3 |
| **快捷开关（quickSwitches）** | 这条规则的全部合法取值（单选，就是 `/ec <规则> <状态>` 的补全列表） | `RuleType#getQuickSwitches` |
| **自定义列表规则** | `quickSwitches` 里存在非开关词的规则，此时**任意非空文本**都会被接受（例：`Language`、`HappyGhastSpeed`、`VillagerHardWorking`） | [`00-overview.md`](00-overview.md) §4.2 |
| **逐条配置类规则** | 除了开关，还维护一份"名单 / 数值表"的规则（`RuleConfigKind.LIST`）。它的 `/ec c` 子命令**写死 owner** | `NoSqueeze`、`BlockMiningResistance`、`MobNoSpawn` … |
| **状态校验器（stateValidator）** | 规则级的格式 / 范围校验，在写入前拦掉非法值。目前只有 `HappyGhastSpeed` 挂了 | `RuleStateValidator` |
| **短路（值没变）** | 把规则设成它当前的值时 `setState` 会提前返回，**回调一个都不触发** | [`00-overview.md`](00-overview.md) §5 |
| **权限节点** | 规则对玩家生效时额外检查的节点：`villagerdetails:c.<注册名小写>`（框架定义：[术语表 · 规则框架](../framework/10-glossary.md#5-规则框架)） | `RuleCache.isEnabled(player, rule)` |
| **`/ec` 总权限** | `RuleCommand` 这条规则的值，决定"能不能看 / 改规则"，节点是 `villagerdetails:c.base` | [`02-rules/settings.md`](02-rules/settings.md) |
| **扫包注册** | 不写清单，按包名反射发现事件 / 通道实现类（框架定义：[扫包机制](../framework/04-session-state.md#2-扫包机制三层)） | `event/impl/register/**` |
| **落盘（persist）** | 把内存里的状态 / 配置写到存档文件；异步、滞后，和"副作用立即生效"相对（框架定义：[七段数据流](../framework/02-config-pipeline.md#1-七段数据流)） | `RuleSwitchConfig`、`RuleConfig` |

---

### 2. 村民与交易

| 术语 | 含义 | 出现在哪 |
|---|---|---|
| **交易表（offers）** | 一只村民当前的全部交易条目（`MerchantOffers`）。每笔有"价格物 / 结果物 / 可交易次数 / 已用次数" | [`auto-trader.md`](03-deep-dives/auto-trader.md) |
| **交易集 / 候选池** | 原版按"职业 + 等级"定义的交易候选集合；生成交易表时从候选里抽若干笔。模组的模拟就是**照这套规则重抽** | 版本层 `TradePoolChecker`、`TradeSetAdapter` |
| **锁定交易（locked）** | 原版语义：村民被交易过一次后整张交易表固定，不再随等级重刷。判据 = `villagerXp > 0` **或** 任意一笔 `uses > 0` | `VillagerTradePipeline#hasLockedTrades` |
| **快照（trade snapshot）** | 主线程把一只村民的交易表**深拷贝成纯数据**（UUID / 坐标 / 维度 / 物品 id / 数量 / 价格 / 附魔 id+等级）。不含任何原版对象 | [`trade-search.md`](03-deep-dives/trade-search.md) |
| **索引（trade index）** | 全存档"谁卖什么"的内存表（`VillagerTradeCache`），查询只读它，不遍历世界 | [`trade-search.md`](03-deep-dives/trade-search.md) |
| **墓碑** | 村民死亡后留在索引里的"此 UUID 已作废"标记，用来挡掉**迟到的**异步写入（框架定义：[术语表 · 并发语义](../framework/10-glossary.md#7-并发语义)） | `VillagerTradeCache.remove` |
| **纪元（epoch）** | 索引的一次"整体换代"编号；换代前抓的快照不许写回（框架定义：[术语表 · 并发语义](../framework/10-glossary.md#7-并发语义)） | `VillagerTradePipeline#submit` |
| **快照池 / 查询池** | 两个独立的后台线程池：一个专收"写索引"，一个专收"查索引"（框架定义：[四条执行线](../framework/01-threading.md#1-四条执行线)） | `ServerThreadManager` |
| **可产性（requiredLevel）** | "这个职业最低几级能刷出目标物品"的**只读**判定。结果为"刷不出"时直接告诉玩家，不进入模拟 | `TradePoolChecker.requiredLevel` |
| **命名牌触发** | 用命名牌给村民起名 = 给模组下指令。可识别的名字：物品 id / 中文别名、附魔 id / 中文名、`331a`、`331b`。解析不出来就当普通名字，**不弹任何提示** | [`auto-trader.md`](03-deep-dives/auto-trader.md) |
| **331a / 331b** | 固定组合刷取的暗号：给**武器匠**刷出"钻石镐 + 钻石锹 + 钻石斧"三件套，各带三附魔。`331a` = 精准采集（时运 → 精准），`331b` = 时运 | `AutoVillagerTrader#enqueueCombo` |
| **陶瓦成对** | 目标写"陶瓦"时，命中条件不是"出现这块陶瓦"，而是"**普通陶瓦 + 对应釉陶瓦同时出现**"（单次概率约 1/528，所以任务上限 ×3） | `AutoVillagerTrader#resolveTarget` |
| **命中 / 补齐（finisher）** | 命中 = 某次模拟的结果满足全部条件。补齐 = 命中（或次数用尽）后对整张表做一次加工，例如把附魔书升到满级、给 331 补上缺的工具 | `TradeRerollScheduler.Finisher` |
| **随机盐（randomSalt）** | 玩家触发刷新的那一刻从世界随机源抓的一个 `long`，用来打破"同一村民永远在第 N 次命中" | [`auto-trader.md`](03-deep-dives/auto-trader.md) |
| **分帧（slice）** | 一个刷取任务不一次跑完，而是每 tick 跑固定预算（由 `VillagerOneTickTraderCount` 决定），跑不完留到下一 tick（框架定义：[分帧](../framework/01-threading.md) §4） | `TradeRerollScheduler.runSlice` |
| **无感刷新** | 触发刷新后（任务**入队时**）立刻清掉命名牌留下的自定义名字，玩家看不到触发痕迹；刷新被拒绝时保留名字以便重试（`VillagerSilentTrader`） | `NameTagItemMixin` |
| **别名表 / trade_index** | 物品与附魔的"人话名字"映射（`绿宝石` → `minecraft:emerald`），命令补全与命名牌解析都靠它 | `TradeIndexConfig` + `trade_index.json` |
| **补全数据** | 服务端算好的"这个存档能出现哪些交易物 / 附魔"，给 Tab 补全用 | [`tab-completion.md`](03-deep-dives/tab-completion.md) |
| **空交易** | 结果物为空的交易条目；抓快照时**只跳过这一类**，不做"值不值得收录"的过滤 | [`trade-search.md`](03-deep-dives/trade-search.md) |
| **制备 / 拆解** | 付款时如果玩家没有"零钱"（绿宝石 / 书），模组按固定配方从背包里的绿宝石块 / 书架**现场换开** | 版本层 `ItemProvisioner` |

---

### 3. 绑定、选区与传送门

| 术语 | 含义 | 出现在哪 |
|---|---|---|
| **BB 选区** | 方块区域选区：`pos1` / `pos2` 两个角（`/ec c selection pos1\|pos2\|show\|clear`，或手持木斧左右键） | `BBSectionStateCache` |
| **EB 选区** | "实体 + 方块"双选区：先用工具点实体、再点方块，两边齐了立刻触发绑定 | `EBSelectionStateCache` |
| **绑定工具** | 命名牌写 `bed` / `work` / `move` 的拴绳。三个名字对应三种 `BindingType` | `BindingType` |
| **区域绑定** | 一种"先框区域、再绑一条记录、之后按 `#ID` 增删查"的命令形态（`bind` / `show` / `view` / `delete` / `set` / `clear`） | `AbstractRegionBindingCommand` |
| **两侧配对** | `PortalBinding` 的特有形态：主世界 `set` 一次、下界 `set` 一次，再 `bind` 才真正落一条 | [`portal-binding.md`](03-deep-dives/portal-binding.md) |
| **绑定 ID（`#ID`）** | 配置层分配的**只增不减、删除不回收**的自增编号。所以聊天栏里的 `#3` 永远指同一条记录 | `PortalBindingConfig`、`PortalPiglinNoSpawnConfig` |
| **原版理论落点** | 不查世界、纯按坐标算出的"原版会把人送到哪"：主世界 ↔ 下界 XZ 按 1:8 缩放、Y 不变 | `PortalBindingService` |
| **增强模式** | `PortalBindingEnhanced`：跳过"目标位置必须已有门"的检查，没有门就自己找落脚点（可能现场建） | 版本层 `EntityPortalMixin` |
| **预热 / 刷新** | 预热 = `bind` 时就把两侧的门解析好写进缓存（避免首次传送卡顿）；刷新 = 清掉缓存重新扫一遍 | `PortalBindingService#cachePortal` / `refreshCachedPortals` |
| **强制传送** | 规则档位达标（且规则开启）的实体走进绑定区域时，**不走**原版"最近的传送门"逻辑，直接送到绑定目标 | [`portal-binding.md`](03-deep-dives/portal-binding.md) |
| **僵尸猪灵拦截点** | 下界传送门方块 `randomTick` 里的"按概率刷僵尸猪灵"那一段；`PortalPiglinNoSpawn` 就是把它掐掉 | 版本层 `NetherPortalSpawnMixin` |

---

### 4. 其它玩法

| 术语 | 含义 | 出现在哪 |
|---|---|---|
| **挤压（squeeze）** | 实体重叠时原版会把它们推开、并对部分实体造成挤压伤害。`NoSqueeze` 让名单里的实体免疫这一套 | `SqueezeHandler` |
| **金块交易** | `PiglinGoldBlockMultiply`：猪灵捡到金块时**当场换成金锭并打一个命令标签**，产出阶段识别标签后跑 9 次独立随机；等待时长乘以惩罚倍数 | `PiglinAiMixin` |
| **载具是玩家** | `PlayerHeadSitting` 里"坐在别人头顶"用的是**玩家**当载具，而原版玩家类型是 `noSave()` —— 所以下线 / 卸载必须自己清乘客，否则会刷「Trying to save removed」并在客户端隐身 | [`player-sitting.md`](03-deep-dives/player-sitting.md) |
| **客户端开关** | `PlayerBlockSitting` 的"能不能坐"由玩家**自己客户端**的偏好决定；没装本模组的玩家按"开"算 | `PlayerBlockSittingPreference` |
| **脏熔炉** | 输入槽有烧不了的东西，或燃料槽里不是有效燃料。`FurnaceDirtyHighlight` 负责判定 + 高亮 | [`furnace-dirty.md`](03-deep-dives/furnace-dirty.md) |
| **脏表** | 服务端维护的"维度 → 脏熔炉坐标"全局表，只保留**至少有一个玩家视距覆盖**的区块 | `FurnaceDirtyTracker.GLOBAL_DIRTY` |
| **视距区块** | 玩家能看到 / 能加载的区块范围；用来把"要给这个玩家推哪些坐标"折算出来 | `FurnaceDirtyHighlightConfig` |
| **服务端语言** | `Language` 规则的值 = 服务端翻译表语言。`/ec` 的回复、规则名与描述都由服务端翻好再发 | [`02-rules/settings.md`](02-rules/settings.md) |
| **客户端界面语言** | 设置界面里显示的文字**不跟**服务端语言，跟客户端自己的界面语言（服务端按客户端报上来的语言翻译后下发） | `ServerTranslations.resolve(Component, String)` |

---

### 5. 框架术语（定义在框架文档）

下表这些词在业务文档里出现时会**直接用、不解释**，因为它们属于框架层。
**定义方是 [`doc/framework/10-glossary.md`](../framework/10-glossary.md)**，这里只给"业务视角够用的一句话" + 链接。

| 术语 | 一句话（业务视角够用的程度） | 详细定义 |
|---|---|---|
| **主线程 / 服务端线程** | 唯一能安全读写世界、实体、发包的线程 | [四条执行线](../framework/01-threading.md#1-四条执行线) |
| **快照池** | 专收"写交易索引"的后台池 | [四条执行线](../framework/01-threading.md#1-四条执行线) |
| **查询池** | 专收"查交易索引"的后台池（与快照池分开，防止查询把写任务挤掉） | [四条执行线](../framework/01-threading.md#1-四条执行线) |
| **配置写线程** | 专管配置落盘的单写者 | [七段数据流](../framework/02-config-pipeline.md#1-七段数据流) |
| **纪元（epoch）** | 索引换代编号，挡住换代前的迟到写入 | [术语表 · 并发语义](../framework/10-glossary.md#7-并发语义) |
| **墓碑** | 死亡村民的"已作废"标记，挡住迟到的异步写入 | [术语表 · 并发语义](../framework/10-glossary.md#7-并发语义) |
| **观察者队列** | 配置对象"变了要落盘"的通知机制 | [观察者队列与节流](../framework/03-config-lifecycle.md#3-观察者队列与节流) |
| **脏标记（markDirty）** | 告诉配置框架"这份数据变了，稍后落盘" | [七段数据流](../framework/02-config-pipeline.md#1-七段数据流) |
| **规则描述符（descriptor）** | 下发给客户端的"规则清单 + 当前状态 + 可选值 + 翻译好的文案" | [规则描述符](../framework/05-rule-framework.md#5-规则描述符) |
| **会话缓存（SessionState）** | 停服 / 断线时要清空的内存缓存 | [契约](../framework/04-session-state.md#1-契约) |
| **扫包（ChannelScanner）** | 按包名反射发现并注册实现类 | [扫包机制](../framework/04-session-state.md#2-扫包机制三层) |
| **权限节点** | `villagerdetails:c.<规则>` 形态的权限标识 | [术语表 · 规则框架](../framework/10-glossary.md#5-规则框架) |
