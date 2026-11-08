# Villager rules (VILLAGER)

The 14 rules whose main category is `VILLAGER` in `RuleType`: binding tools, batch bed binding, trade refresh, trade search and payment.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> This file covers the **14 rules** whose main category in `RuleType` is `VILLAGER` — the most complex group in the project.
> Read it in four blocks:
>
> | Block | Rules | Shared mechanism |
> |---|---|---|
> | ① Binding tools | `VillagerBedBinding`, `VillagerWorkBlockBinding`, `VillagerMoveController` | A name tag used as a tool (`bed` / `work` / `move`) + EB dual selection (entity + block) → dispatched by `BindHandler` |
> | ② Batch bed binding | `VillagerBatchBedBinding` | BB region selection + nearest-neighbour matching |
> | ③ Trade refresh | `VillagerAutoTrader`, `VillagerAutoLockHitTrader`, `VillagerSilentTrader`, `VillagerOneTickTraderCount`, `VillagerHardWorking`, `VillagerInfiniteTrade` | Name-tag trigger + frame-sliced re-rolling + trade-table rewriting |
> | ④ Trade search and payment | `TradeSearch`, `VillagerToolsmithExchange`, `VillagerEmeraldDecompose`, `VillagerBookshelfDecompose` | Trade index / in-place trade-table rewrite / prepare on payment |
>
> The two complex ones (`VillagerAutoTrader`, `TradeSearch`) only get a summary; the full text lives in the deep dives.

### ① Binding tools (three)

#### `VillagerBedBinding` (villager bed editor)

- **Register name**: `VillagerBedBinding` (enum constant `VILLAGER_BED_RESET`)
- **Category**: `VILLAGER`
- **Default value**: `false`
- **Commands**: `/ec VillagerBedBinding` (switch); **no** `/ec c` subcommand — operation goes through in-game tools
- **Config class**: none (the binding result is written into the vanilla POI and the villager's brain memory, not into mod config)
- **Trigger**: **sneak + hold a lead renamed to `bed`**, right-click the villager first and then the bed (the order may be swapped); once both sides are selected the binding happens immediately. Sneaking is a hard prerequisite (`BindHandler.isHoldingTool` checks `isShiftKeyDown`), and the tool name must be exactly `bed` (case-insensitive, but leading and trailing spaces count as part of the name).
- **How it works**:
  1. The selection tool is a "entity + block" dual selection: when `EBSectionManager` receives `UseEntity` / `UseBlock` it validates the tool first (`BindingUtil.isHoldingAnyTool`: rule on + lead + name match + entity type match), then records the selected entity / block in `EBSelectionStateCache` and pushes it to the client for highlighting.
  2. Once both sides are selected → `BindHandler.chooseUtil`: **`peek` validates first** (the tool is still in hand, the target really is a villager), and only after the check passes is the selection `clear`ed — if the check fails the selection stays, so the player can switch back to the right tool and click again to carry on binding.
  3. The real work is done by `VillagerBindHandler.bindBed`: find the **head** through `BedBlock`'s `PART` (a bed only counts once), check whether the same bed has already been bound, clear other villagers' old bindings to this bed, make sure the bed head has a bed POI registered, and finally write the villager's home memory.
  4. **Only a successful bind consumes a lead** (a failure costs nothing).
- **Key files**: `event/impl/register/EBSectionManager.java`, `handler/binding/BindHandler.java`,
  `handler/binding/BindingUtil.java`, `cache/impl/EBSelectionStateCache.java`,
  `handler/binding/entity/villager/{VillagerBindServer,VillagerBindHandler}.java`
- **Dependencies**: `BindingType.BED` (tool item / tool name / message prefix all live there), `ListenerType.ENTITY_BLOCK_SELECTION`,
  the client-side `EBSyncChannel` (highlight) and `VillagerBedSyncChannel` (binding echo)
- **Client behaviour**: the entity / block is highlighted once selected; the highlight clears itself after a successful bind or once the binding flow is entered

#### `VillagerWorkBlockBinding` (villager work-block editor)

- **Register name**: `VillagerWorkBlockBinding` (enum constant `VILLAGER_WORK_BLOCK_RESET`)
- **Category**: `VILLAGER`
- **Default value**: `false`
- **Commands**: `/ec VillagerWorkBlockBinding` (switch); **no** `/ec c` subcommand
- **Config class**: none
- **Trigger**: **sneak + hold a lead renamed to `work`**, right-click the villager + right-click the work block
- **How it works**: the tool chain is exactly the same as `VillagerBedBinding` (only `BindingType.WORK_BLOCK`, tool name `work`);
  the difference is that `bindWorkBlock` has to deal with "profession":
  1. the target block must be a **workstation POI** (bed / home style POIs are excluded);
  2. a villager that has already traded **cannot be changed** (the profession is locked), and an existing villager on the block that has traded cannot be displaced either;
  3. clear the other villagers' old occupancy of that block, release the old POI occupancy, look up the matching villager profession by POI type,
     then set the villager's profession and update the workstation and "potential workstation" memories
     (this only changes the profession / memory: the trade table **stays as it is**, and trades already sold are not re-rolled by a profession change — use `VillagerAutoTrader` to re-roll).
- **Key files**: same as `VillagerBedBinding`, with the business logic in `VillagerBindHandler#bindWorkBlock`
- **Dependencies**: `BindingType.WORK_BLOCK`, `ListenerType.ENTITY_BLOCK_SELECTION`, client `WorkstationSyncChannel`
- **Client behaviour**: selection highlight + workstation binding echo

#### `VillagerMoveController` (villager move controller)

- **Register name**: `VillagerMoveController`
- **Category**: `VILLAGER`
- **Default value**: `false`
- **Commands**: `/ec VillagerMoveController` (switch); **no** `/ec c` subcommand
- **Config class**: none
- **Trigger**: **sneak + hold a lead renamed to `move`**, right-click the villager + right-click the destination
- **How it works**:
  1. The destination is **normalised** first: `StoodFinder.find` starts at the clicked position and tries "in place → one block up → block by block downward (at most 3 blocks, `MAX_SEARCH_DOWN = 3`)"
     looking for a spot that is itself air with something to stand on below; when nothing is found `VillagerBindHandler#bindMove` returns failure and replies `msg.villager.move.no_ground`.
     A failed bind consumes neither the lead nor the name tag, so success and failure are told apart by the reply.
  2. The villager gets a **mod-owned memory** `DESTINATION` (`ModMemories`, registered into the vanilla memory-type registry),
     and the **first** time a task is issued to this villager the `GO_TO_DESTINATION` activity is attached to its Brain
     (speed 0.5, arrival radius 0.7 blocks, 60 second timeout, automatic repathing when the path is lost).
  3. As long as `DESTINATION` exists, `BrainMixin` keeps the activity **locked** on "go to destination"
     (except `CORE`), so the villager does not start wandering / working halfway; on arrival, or on timeout (a villager that is
     stuck or boxed in behaves the same way), the memory is cleared and the villager returns to its default activity.
- **Key files**: `handler/entity/villager/move/{VillagerMoveHandler,MoveToDestination,StoodFinder,ModActivities}.java`,
  `handler/entity/villager/ai/ModMemories.java`,
  `mixin/entity/villager/move/{BrainMixin,VillagerBrainGoalsMixin,BrainInterfaceMixin}.java`
- **Dependencies**: `BindingType.MOVE`, `ModMemories.DESTINATION`, the `Activity` registration (`ModActivities`),
  the `@Accessor` that reaches `Brain#setActiveActivity`
- **Client behaviour**: no dedicated packet (only the EB selection highlight)

### ② Batch bed binding (one)

#### `VillagerBatchBedBinding` (batch villager bed editor)

- **Register name**: `VillagerBatchBedBinding` (enum constant `VILLAGER_BATCH_BED_RESET`)
- **Category**: `VILLAGER` + `COMMAND`
- **Default value**: `false`
- **Commands**: `/ec VillagerBatchBedBinding` (switch); `/ec c VillagerBatchBedBinding` (**no arguments**, consumes the current BB selection)
- **Config class**: none
- **Trigger**: run the command above (the selection must contain at least one villager and one bed)
- **How it works**:
  1. Scan the two selection corners for **villagers** and **beds**: villagers are grabbed in one call with `getEntitiesOfClass`; beds can only be scanned block by block,
     so a **volume cap of 32768 blocks (32³)** keeps the server responsive, and **unloaded chunks are skipped**, **only bed heads are collected**
     (otherwise one bed would be counted twice). Exceeding the cap throws `IllegalArgumentException`, which the command layer turns into an error message.
  2. A generic matcher (`GenericMobTargetMatcher`) does the **nearest-neighbour pairing** (greedy closest pairing, which is not guaranteed to be the globally optimal
     assignment), then the bed binding runs pair by pair; in batch mode `sendMsg = false` so each villager does not add a message.
  3. At the end a single summary is returned: how many succeeded / how many villagers and beds are in the region / the selection coordinates; when nothing was paired the reply says "no match".
- **Key files**: `command/c/impl/rule/other/VillagerBatchBedBindingServerImpl.java`,
  `handler/binding/entity/villager/{VillagerAreaScanner,VillagerBindServer}.java`,
  `math/gridhashing/` (matching framework: `BindableMob` / `BindableTarget` / `VillagerBedStrategy`)
- **Dependencies**: BB selection (`BBSectionStateCache`), `VillagerBindServer.bindAreaBeds`, `VillagerBedStrategy`
- **Client behaviour**: selection highlight (`BBSyncChannel`)

### ③ Trade refresh (six)

#### `VillagerAutoTrader` (automatic trade refresh)

- **Register name**: `VillagerAutoTrader`
- **Category**: `VILLAGER`
- **Default value**: **`true`** — **the only gameplay rule in the whole project that is on by default** (defaulting to off would mean "installed, no effect")
- **Commands**: `/ec VillagerAutoTrader` (switch); **no** `/ec c` subcommand; it is triggered through a name tag
- **Config class**: none (`MAX_ATTEMPTS = 1000`, `COMBO_MAX_ATTEMPTS = 500` and the like are code constants)
- **Trigger**: **write the target on a name tag** → apply it to the villager (after the vanilla naming succeeds)
- **How it works**: see [deep dive: automatic trade refresh](../03-deep-dives/auto-trader.md). Summary:
  1. **Name parsing**: first as an enchantment (`mending` / `经验修补`), then as an item (`绿宝石` / `白釉陶瓦`);
     there are also two fixed codes `331a` / `331b`. A name that does not parse is **let through** (treated as an ordinary pet name, no message).
     `331a` / `331b` is the "all three pieces present" route: a weaponsmith cannot roll a diamond pickaxe, so the combo never holds for it,
     and that route therefore relies on **backfilling afterwards** (whatever is missing is added).
  2. **Static producibility check**: it only reads the trade registry and works out "at which minimum level this profession can roll it";
     when that cannot be worked out the player is told straight away that "this profession cannot roll it" and **no simulation is entered**.
  3. **Task creation**: "villager UUID + dimension + predicate + attempt cap + random salt" is packed into `TradeRerollScheduler`,
     which runs a fixed budget every tick (`VillagerOneTickTraderCount`) and finishes on a hit or when the attempts run out.
     Terracotta needs a "plain + glazed pair" (single-attempt probability around 1/528), so its attempt cap is separately ×3 (3000 attempts).
  4. **Write-back**: on a hit the whole trade table is written back to the entity at once (`@Accessor("offers")` — `overrideOffers`
     **cannot** be used, it is an empty implementation), the level is locked, trades are locked on demand, the new trades are pushed to the players who have the
     screen open, and then a trade-index snapshot is captured immediately.
  5. **Return value contract** (which decides the message the player gets): `-1` locked / baby / no workstation, `-2` name does not parse,
     `-3` rule off, `-4` profession unsupported (331 route), `-7` this profession cannot roll it, `-8` too many tasks (cap 64),
     `>0` task queued (the value is this round's attempt cap).
- **Key files**: `handler/entity/villager/trader/refresh/{AutoVillagerTrader,TradeRerollScheduler,TradeTableBuilder}.java`,
  version layer `handler/.../refresh/{TradePoolChecker,TradeSetAdapter,TradeEnchantmentScanner}.java`,
  `mixin/entity/villager/trader/autoTrader/**` (including the `NameTagItemMixin` trigger point and the `@Accessor` write-back point)
- **Dependencies**:
  - `VillagerOneTickTraderCount` (simulation attempts per tick)
  - `VillagerAutoLockHitTrader` (whether to lock automatically after a hit)
  - `VillagerSilentTrader` (whether to clear the name once the task is queued)
  - `TradeSearch` (index synced after a hit; when it is off, `captureNow` returns immediately)
  - `TradeIndexConfig` (alias table: name → item / enchantment id)
- **Client behaviour**: no dedicated packet. What the player sees is "the villager's trade table changed and the screen refreshes itself".

#### `VillagerAutoLockHitTrader` (lock villager trades automatically)

- **Register name**: `VillagerAutoLockHitTrader` (enum constant `VILLAGER_AUTO_LOCK_HIT_TRADER`)
- **Category**: `VILLAGER`
- **Default value**: `false`
- **Commands**: `/ec VillagerAutoLockHitTrader` (switch); **no** `/ec c` subcommand
- **Config class**: none
- **Trigger**: the moment `VillagerAutoTrader` hits and writes back
- **How it works**: while writing back it does two things — it **uses the first trade once** (`uses +1`) and it **raises the villager's experience to at least 1**
  (`setVillagerXp(Math.max(1, currentXp))`, so an existing value above 1 is never lowered). Together those two side effects are the "lock":
  - the first trade is already used → nobody else can buy it out first;
  - `villagerXp > 0` → the refresh logic's "can re-roll" check fails outright, so the same villager is not rolled a second time.
- **Key files**: `handler/entity/villager/trader/refresh/AutoVillagerTrader.java#doLockIfNeeded/lock`
- **Dependencies**: `VillagerAutoTrader` (with automatic refresh off this rule has no trigger point at all)
- **Client behaviour**: none. The player sees the first trade displayed as "used 1/max"

#### `VillagerSilentTrader` (silent refresh)

- **Register name**: `VillagerSilentTrader` (enum constant `VILLAGER_SILENT_AUTO_REROLL_TRADER`)
- **Category**: `VILLAGER`
- **Default value**: `false`
- **Commands**: `/ec VillagerSilentTrader` (switch); **no** `/ec c` subcommand
- **Config class**: none
- **Trigger**: a name-tag-driven refresh **whose task was queued successfully** (not the moment it hits)
- **How it works**: after the task is queued the villager's custom name is cleared, so the player sees no trace of "this one was given an order".
  **A rejected refresh does not clear it** (for example the name does not parse, the profession cannot roll it, too many tasks), so the player can rename on the spot and retry.
  The name is cleared **at queue time**, so a refresh that exhausts its attempts without a hit leaves the villager nameless — retrying means applying a new name tag.
- **Key files**: `mixin/entity/villager/trader/autoTrader/trigger/NameTagItemMixin.java`
- **Dependencies**: `VillagerAutoTrader`
- **Client behaviour**: none

#### `VillagerOneTickTraderCount` (refresh attempts per tick)

- **Register name**: `VillagerOneTickTraderCount`
- **Category**: `VILLAGER`
- **Default value**: `500`
- **Allowed values**: `50` / `100` / `500` / `1000` (custom list, any text can be written)
- **Commands**: `/ec VillagerOneTickTraderCount <number>`; **no** `/ec c` subcommand
- **Config class**: none
- **Trigger**: `TradeRerollScheduler.tick` — **read once per server tick** (all tasks in the same tick share this budget)
- **How it works**: the value is clamped by `Math.clamp(v, 1, 1000)` into `[1, 1000]` and then used as the budget for "**how many attempts this task may run**
  in this tick"; when the budget is used up it `break`s to the next task and the remaining attempts wait for the next tick. **There is no time valve** —
  a single slow simulation does not stop the work early, so the per-tick cost ceiling is roughly `task count × attempt count × cost of one simulation`.
  This is a **numeric-tier rule**: every allowed value is a number, so the candidates are numeric values only and `isEnabled` is always true;
  to switch the feature off, set the rule to `false` (consumers then treat it as off). Its only levers are `clamp(1, 1000)` and the "64 tasks" cap.
- **Key files**: `handler/entity/villager/trader/refresh/TradeRerollScheduler.java#attemptsPerTick`
- **Dependencies**: `VillagerAutoTrader` (its only user)
- **Client behaviour**: none

#### `VillagerHardWorking` (hard-working villager)

- **Register name**: `VillagerHardWorking`
- **Category**: `VILLAGER`
- **Default value**: `1`
- **Allowed values**: `1` / `2` / `5` (custom list)
- **Commands**: `/ec VillagerHardWorking <multiplier>`; **no** `/ec c` subcommand
- **Config class**: none
- **Trigger**: inside `Villager#restock`, the spot where vanilla calls `resetUses()` on every trade (`@Redirect`)
- **How it works**: "the used count resets to zero after a restock" becomes "the used count becomes **negative** after a restock":
  `used = −(multiplier − 1) × maxUses`. A restocked trade can then sell `multiplier × maxUses` times — multiplier 1 equals vanilla behaviour,
  and a multiplier ≤ 1 goes through the vanilla `resetUses()`. The restock amount is written through the offer's `uses` field, so a negative
  used count can appear in the GUI while the rule is active — the trade itself is correct (vanilla only compares `uses >= maxUses`).
- **Key files**: `mixin/entity/villager/trader/autoTrader/hardWork/MerchantOfferResetMixin.java`
- **Dependencies**: `MerchantOfferInterfaceMixin` (`@Accessor` on the `uses` field)
- **Client behaviour**: none. The player sees "more can be sold after a restock"; the used count in the screen is displayed as a negative number

#### `VillagerInfiniteTrade` (infinite trades)

- **Register name**: `VillagerInfiniteTrade`
- **Category**: `VILLAGER`
- **Default value**: `false`
- **Commands**: `/ec VillagerInfiniteTrade` (switch); **no** `/ec c` subcommand
- **Config class**: none
- **Trigger**: `MerchantOffer#increaseUses` (called once per completed trade)
- **How it works**: while the rule is on the call is simply `cancel`led — the used count never grows, so a trade never sells out.
  Because `uses` does not grow, vanilla's supply-and-demand price rise (which is driven by `uses`) never starts either: the player always gets
  the base price, and "restock" has no meaning for this rule.
- **Key files**: `mixin/entity/villager/trader/infiniteTrade/MerchantOfferMixin.java`
- **Dependencies**: none
- **Client behaviour**: none. The player sees trades that can always be bought again

### ④ Trade search and payment (four)

#### `TradeSearch` (villager trade search)

- **Register name**: `TradeSearch` (enum constant `VILLAGER_TRADE_SEARCH`)
- **Category**: `VILLAGER` + `COMMAND`
- **Default value**: `false`
- **Commands**:
  - `/ec TradeSearch` (switch)
  - `/ec c TradeSearch` — show usage
  - `/ec c TradeSearch item [keyword]` — search **ordinary item** trades (enchantment books excluded)
  - `/ec c TradeSearch book [keyword]` — search **enchantment book** trades; the keyword is an enchantment id / Chinese name
- **Config class**: `VillagerTradeCacheConfig` (`villagerdetails/villager_trade_cache.json`, flushed **every 200 ticks ≈ 10 seconds**);
  the alias table uses `TradeIndexConfig` (`villagerdetails/trade_index.json`)
- **Trigger**: five capture points — entity unload (`ENTITY_UNLOAD`, **the main entry**), chunk unload (`CHUNK_UNLOAD`, the fallback),
  a player opening the trade screen (`TradeOpenMixin`, 100-tick debounce), a successful name-tag refresh (`captureNow`), villager death (cache cleared).
  Indexing is therefore **event-driven** — chunk unload, trade-screen open (100-tick debounce) and the explicit refresh: a villager whose chunk
  never unloads is indexed at the next capture, and there is **no global sweep**.
- **How it works**: see [deep dive: villager trade search](../03-deep-dives/trade-search.md). Summary:
  1. **Snapshot capture**: only villagers with a **locked trade** are collected (`villagerXp > 0` or any trade with `uses > 0`);
     on the main thread the trade table is **deep-copied into pure data** (uuid / position / dimension / item id + count + price + remaining uses + enchantment id and level),
     and no vanilla object is carried into the thread pool.
  2. **No "is it worth indexing" filter**: only trades whose result item is empty are skipped — a whitelist by item id would wrongly kill
     shelf, map and name-tag trades, which are all normal sales.
  3. **Async index write**: the snapshot goes to the server thread pool, which writes it into the in-memory index (`ConcurrentHashMap`) and marks it dirty;
     the flush cadence is owned by the storage class. When the snapshot pool is unavailable a WARN is logged and the capture is skipped for that pass — the next pass retries.
  4. **Query**: `/ec c TradeSearch ...` only does "1 second debounce + submit a task" on the main thread; matching runs in the **query pool**,
     and the pure data is handed back to the main thread to assemble the chat message — the player waits one extra tick at most and the main thread is unaffected.
  5. **Consistency**: death deletes immediately (and leaves a "tombstone" to block late writes), slow writes compare timestamps to decide old against new,
     turning the rule off flushes first and then clears memory, and turning it back on reads the disk index back in.
- **Key files**: `handler/entity/villager/trade/{VillagerTradePipeline,VillagerTradeSearchHandler,TradeItemNameResolver}.java`,
  `cache/impl/VillagerTradeCache.java`, `config/impl/VillagerTradeCacheConfig.java`,
  `command/c/impl/rule/villager/TradeSearchCommand.java`, `event/impl/register/VillagerTradeSearchEvents.java`,
  `mixin/entity/villager/trader/tradeSearch/TradeOpenMixin.java`
- **Dependencies**: `TradeIndexConfig` (keyword → id), `TradeItemCompletionCache` (tab completion data),
  `ServerThreadManager` (snapshot pool / query pool), `VillagerTradeIndexJson` (JSON encoding and decoding)
- **Client behaviour**: the query result is assembled on the server and sent to chat (the coordinates are clickable); the client takes no part in matching
  and keeps no item names (the display text is localised by the client in its own language)

#### `VillagerToolsmithExchange` (villagers can exchange for diamonds)

- **Register name**: `VillagerToolsmithExchange`
- **Category**: `VILLAGER`
- **Default value**: `false`
- **Commands**: `/ec VillagerToolsmithExchange` (switch); **no** `/ec c` subcommand
- **Config class**: none
- **Trigger**: the RETURN of `AbstractVillager#getOffers` (every read of the trade table passes through it)
- **How it works**: it applies to **toolsmiths / weaponsmiths** only and scans from the **6th trade onward** (index 5),
  **replacing in place** a "diamond → emerald" entry with "emerald → diamond" (the counts are swapped and every other field is copied from the original entry),
  so the player sees "high-level trades can exchange emeralds for diamonds". The exchange is applied by offer index starting at the 6th offer (index 5) — that
  indexing is the design, implemented in `mixin/entity/villager/trader/emeraldDecompose/AbstractVillagerExchangeMixin.java` — so if the trade table order
  changes (another mod inserts entries, or the iteration order differs) other offers are affected. It also only rewrites the "diamonds for emeralds"
  combination; diamond pickaxe / diamond sword style offers are left alone.
- **Key files**: `mixin/entity/villager/trader/emeraldDecompose/AbstractVillagerExchangeMixin.java`
- **Dependencies**: none
- **Client behaviour**: none (the trade table is rewritten on the server; the client only displays it)

#### `VillagerEmeraldDecompose` (automatic emerald block decomposition)

- **Register name**: `VillagerEmeraldDecompose`
- **Category**: `VILLAGER`
- **Default value**: `false`
- **Commands**: `/ec VillagerEmeraldDecompose` (switch); **no** `/ec c` subcommand
- **Config class**: none (the recipe is hard-coded in the version layer's `ItemProvisioner`: `1 emerald block → 9 emeralds`)
- **Trigger**: the HEAD of `MerchantMenu#tryMoveItems` (the player switches / confirms inside the trade screen and the trade slots are about to be filled);
  dragging an emerald block into the payment slot by hand does **not** trigger it, because preparation only happens at this one place.
- **How it works**:
  1. Check whether the payment items this trade asks for (`itemCostA` / `itemCostB`) are the target of a "provision recipe";
  2. compute the **shortfall** = required count − (already in the inventory + already in the trade slots);
  3. only when the shortfall > 0, consume the ingredients from the inventory and put the produced items into the inventory per the recipe (when the inventory is full they drop
     at the player's feet and may be picked up by another player); over-provisioned output is not taken back and can be used by the next trade. It is **not a giveaway** —
     the emerald blocks in the inventory are really consumed, it only breaks a large denomination into change.
- **Key files**: version layer `handler/entity/villager/trader/decompose/ItemProvisioner.java`,
  `mixin/entity/piglinTrade/MerchantMenuProvisionMixin.java` (note that it lives in the `piglinTrade` package)
- **Dependencies**: `MerchantMenuInterfaceMixin` (`@Accessor` to reach the trader / trade slots),
  **the rule switch lives in the recipe** (every recipe carries its own `RuleType`, so nothing is provisioned while that rule is off)
- **Client behaviour**: none (a purely server-side inventory edit; what the client sees is "the payment items appeared by themselves")

#### `VillagerBookshelfDecompose` (automatic bookshelf decomposition)

- **Register name**: `VillagerBookshelfDecompose`
- **Category**: `VILLAGER`
- **Default value**: `false`
- **Commands**: `/ec VillagerBookshelfDecompose` (switch); **no** `/ec c` subcommand
- **Config class**: none (the recipe is hard-coded: `1 bookshelf → 3 books`)
- **Trigger / implementation / key files**: **exactly the same mechanism** as `VillagerEmeraldDecompose`
  (the same recipe table, the same injection point); only the recipe and the rule switch differ. The librarian's "book → emerald" trade
  fits it naturally, so this rule has the biggest economic impact on librarians; the two decompose rules do not interfere with each
  other when both are on (each looks up its own recipe).
- **Dependencies**: same as `VillagerEmeraldDecompose`
- **Client behaviour**: same as `VillagerEmeraldDecompose`

---

<a id="中文"></a>

## 中文

> 本文件覆盖 `RuleType` 里主分类为 `VILLAGER` 的 **14 条**规则 —— 全项目最复杂的一组。
> 分成四块读：
>
> | 块 | 规则 | 共同机制 |
> |---|---|---|
> | ① 绑定工具 | `VillagerBedBinding`、`VillagerWorkBlockBinding`、`VillagerMoveController` | 命名牌当工具（`bed` / `work` / `move`）+ EB 双选区（实体 + 方块）→ `BindHandler` 分派 |
> | ② 批量绑床 | `VillagerBatchBedBinding` | BB 区域选区 + 就近匹配 |
> | ③ 交易刷新 | `VillagerAutoTrader`、`VillagerAutoLockHitTrader`、`VillagerSilentTrader`、`VillagerOneTickTraderCount`、`VillagerHardWorking`、`VillagerInfiniteTrade` | 命名牌触发 + 分帧重掷 + 交易表改写 |
> | ④ 交易查询与支付 | `TradeSearch`、`VillagerToolsmithExchange`、`VillagerEmeraldDecompose`、`VillagerBookshelfDecompose` | 交易索引 / 交易表原地改写 / 付款时制备 |
>
> 两条复杂的（`VillagerAutoTrader`、`TradeSearch`）只给摘要，正文在深度剖析。

### ① 绑定工具（三条）

#### `VillagerBedBinding`（村民床编辑器）

- **注册名**：`VillagerBedBinding`（枚举常量 `VILLAGER_BED_RESET`）
- **分类**：`VILLAGER`
- **默认值**：`false`
- **命令**：`/ec VillagerBedBinding`（开关）；**无** `/ec c` 子命令 —— 操作靠游戏内工具
- **配置类**：无（绑定结果写进原版 POI 与村民的 brain 记忆，不是模组配置）
- **触发**：**蹲下 + 手持改名为 `bed` 的拴绳**，先右键村民、再右键床（顺序可换），两边齐了立刻绑定。
  蹲下是硬性前置条件（`BindHandler.isHoldingTool` 判 `isShiftKeyDown`）；工具名必须精确等于 `bed`
  （忽略大小写，但前后空格也算名字的一部分）。
- **实现思路**：
  1. 选区工具是"实体 + 方块"双选区：`EBSectionManager` 收到 `UseEntity` / `UseBlock` 时先校验工具
     （`BindingUtil.isHoldingAnyTool`：规则开着 + 拴绳 + 名字匹配 + 实体类型匹配），
     再把选中的实体 / 方块记进 `EBSelectionStateCache`，并推给客户端做高亮。
  2. 两边都选好 → `BindHandler.chooseUtil`：**先 `peek` 校验**（工具还在手上、目标确实是村民），
     校验通过才 `clear` 掉选区 —— 校验没过时选区留着，玩家换回正确工具再点一次就能接着绑。
  3. 真正干活的是 `VillagerBindHandler.bindBed`：先按 `BedBlock` 的 `PART` 找**床头**（一张床只认一处），
     检查是否已经绑过同一张床、把别的村民对这张床的旧绑定清掉、确保床头有床的 POI 注册，
     最后写村民的家记忆。
  4. **只有绑定成功才消耗一根拴绳**（失败了不白扣）。
- **关键文件**：`event/impl/register/EBSectionManager.java`、`handler/binding/BindHandler.java`、
  `handler/binding/BindingUtil.java`、`cache/impl/EBSelectionStateCache.java`、
  `handler/binding/entity/villager/{VillagerBindServer,VillagerBindHandler}.java`
- **依赖**：`BindingType.BED`（工具物品 / 工具名 / 消息前缀都在这里）、`ListenerType.ENTITY_BLOCK_SELECTION`、
  客户端 `EBSyncChannel`（高亮）与 `VillagerBedSyncChannel`（绑定回显）
- **客户端行为**：选中实体 / 方块后高亮；绑定成功或进入绑定流程后高亮自动清空

#### `VillagerWorkBlockBinding`（村民工作方块编辑器）

- **注册名**：`VillagerWorkBlockBinding`（枚举常量 `VILLAGER_WORK_BLOCK_RESET`）
- **分类**：`VILLAGER`
- **默认值**：`false`
- **命令**：`/ec VillagerWorkBlockBinding`（开关）；**无** `/ec c` 子命令
- **配置类**：无
- **触发**：**蹲下 + 手持改名为 `work` 的拴绳**，右键村民 + 右键工作方块
- **实现思路**：工具链与 `VillagerBedBinding` 完全同一套（只是 `BindingType.WORK_BLOCK`，工具名 `work`），
  差别在于 `bindWorkBlock` 要处理"职业"这件事：
  1. 目标方块必须是一种**工作站点 POI**（床 / 家这类 POI 会被排除）；
  2. 已经交易过的村民**不能改**（职业已锁定），工作方块上原有的村民如果交易过也不能被顶掉；
  3. 清掉该方块上其它村民的旧占用、释放旧的 POI 占用，再按 POI 类型反查对应的村民职业，
     给村民设置职业 + 更新工作站点与"潜在工作站点"记忆（改职业只改职业与记忆，交易表**保持原样**，
     已经卖过的交易不会因为换职业而重刷 —— 想重刷请用 `VillagerAutoTrader`）。
- **关键文件**：同 `VillagerBedBinding`，业务落在 `VillagerBindHandler#bindWorkBlock`
- **依赖**：`BindingType.WORK_BLOCK`、`ListenerType.ENTITY_BLOCK_SELECTION`、客户端 `WorkstationSyncChannel`
- **客户端行为**：选中高亮 + 工作站绑定回显

#### `VillagerMoveController`（村民移动控制器）

- **注册名**：`VillagerMoveController`
- **分类**：`VILLAGER`
- **默认值**：`false`
- **命令**：`/ec VillagerMoveController`（开关）；**无** `/ec c` 子命令
- **配置类**：无
- **触发**：**蹲下 + 手持改名为 `move` 的拴绳**，右键村民 + 右键目标位置
- **实现思路**：
  1. 目标坐标先**归一化**：`StoodFinder.find` 从点击位置开始，按"原地 → 上方一格 → 逐格向下（最多 3 格，`MAX_SEARCH_DOWN = 3`）"
     找一个"本身是空气、下方能站住"的位置；找不到就不动 —— `VillagerBindHandler#bindMove` 返回失败并回
     `msg.villager.move.no_ground`，而且绑定失败既不消耗拴绳、也不消耗命名牌，成功与失败由回复文案区分。
  2. 给村民写一个**模组自己的记忆** `DESTINATION`（`ModMemories`，注册进原版记忆类型注册表），
     并在**第一次**给这只村民下发任务时，把 `GO_TO_DESTINATION` 这个 activity 挂进它的 Brain
     （速度 0.5、到达判定 0.7 格、60 秒超时、丢了寻路会自动重设）。
  3. 只要 `DESTINATION` 还在，`BrainMixin` 会把活动**锁**在"前往目的地"上
     （`CORE` 除外），所以村民不会半路开始闲逛 / 干活；到达或超时（村民被卡住 / 被方块围住时也一样）
     就清掉记忆、回到默认 activity。
- **关键文件**：`handler/entity/villager/move/{VillagerMoveHandler,MoveToDestination,StoodFinder,ModActivities}.java`、
  `handler/entity/villager/ai/ModMemories.java`、
  `mixin/entity/villager/move/{BrainMixin,VillagerBrainGoalsMixin,BrainInterfaceMixin}.java`
- **依赖**：`BindingType.MOVE`、`ModMemories.DESTINATION`、`Activity` 注册（`ModActivities`）、
  `@Accessor` 拿到 `Brain#setActiveActivity` 的入口
- **客户端行为**：无专属包（只有 EB 选区高亮）

### ② 批量绑床（一条）

#### `VillagerBatchBedBinding`（批量村民床编辑器）

- **注册名**：`VillagerBatchBedBinding`（枚举常量 `VILLAGER_BATCH_BED_RESET`）
- **分类**：`VILLAGER` + `COMMAND`
- **默认值**：`false`
- **命令**：`/ec VillagerBatchBedBinding`（开关）；`/ec c VillagerBatchBedBinding`（**无参数**，吃当前 BB 选区）
- **配置类**：无
- **触发**：执行上面那条命令（要求选区里至少有一只村民和一张床）
- **实现思路**：
  1. 用选区两个角扫出**村民**和**床**：村民用 `getEntitiesOfClass` 一次抓；床只能逐格扫，
     所以加了**体积上限 32768 方块（32³）**防卡服，并且**跳过未加载的区块**、**只收床头**
     （否则一张床会被记两次）；区域超限直接抛 `IllegalArgumentException`，由命令层兜成错误提示。
  2. 用通用匹配器（`GenericMobTargetMatcher`）做**就近配对**（贪心的最近配对，不保证全局最优分配），
     再逐对执行床绑定；批量模式下 `sendMsg = false`，避免每只村民刷一条消息。
  3. 最后只回一条汇总：成功几只 / 区域内几只村民几张床 / 选区坐标；一对都没配上时给"没匹配到"。
- **关键文件**：`command/c/impl/rule/other/VillagerBatchBedBindingServerImpl.java`、
  `handler/binding/entity/villager/{VillagerAreaScanner,VillagerBindServer}.java`、
  `math/gridhashing/`（匹配框架：`BindableMob` / `BindableTarget` / `VillagerBedStrategy`）
- **依赖**：BB 选区（`BBSectionStateCache`）、`VillagerBindServer.bindAreaBeds`、`VillagerBedStrategy`
- **客户端行为**：选区高亮（`BBSyncChannel`）

### ③ 交易刷新（六条）

#### `VillagerAutoTrader`（自动刷新交易）

- **注册名**：`VillagerAutoTrader`
- **分类**：`VILLAGER`
- **默认值**：**`true`** —— **全项目唯一默认开启的玩法规则**（默认关掉等于"装了没效果"）
- **命令**：`/ec VillagerAutoTrader`（开关）；**无** `/ec c` 子命令；触发靠命名牌
- **配置类**：无（`MAX_ATTEMPTS = 1000`、`COMBO_MAX_ATTEMPTS = 500` 等是代码常量）
- **触发**：**命名牌写目标** → 贴到村民身上（原版命名成功之后）
- **实现思路**：见 [深度剖析：自动刷新交易](../03-deep-dives/auto-trader.md)。摘要：
  1. **名字解析**：先当附魔（`mending` / `经验修补`），再当物品（`绿宝石` / `白釉陶瓦`）；
     另有两个固定暗号 `331a` / `331b`。解析不出来的名字**直接放行**（当成普通宠物名，不弹提示）。
     `331a` / `331b` 走的是"三件套齐全"路线：武器匠刷不出钻石镐，三件套对它永远不成立，
     所以那条路线靠**事后补齐**（缺哪件补哪件）。
  2. **静态可产性判定**：只读交易注册表，算出"这个职业最低几级能刷出它"；算不出来就直接告诉玩家
     "这个职业刷不出"，**不进入模拟**。
  3. **建任务**：把"村民 UUID + 维度 + 谓词 + 上限次数 + 随机盐值"打包丢进 `TradeRerollScheduler`，
     由它每 tick 跑固定预算（`VillagerOneTickTraderCount`），命中或次数用尽就收尾。陶瓦需要
     "普通 + 釉彩成对"，单次概率约 1/528，所以它的次数上限单独 ×3（3000 次）。
  4. **写回**：命中后整张交易表一次性写回实体（`@Accessor("offers")`，**不能**用 `overrideOffers`，
     那是空实现）、锁等级、按需锁定、把新交易推给正开着界面的玩家、再立刻补一次交易索引快照。
  5. **返回值约定**（决定给玩家哪句提示）：`-1` 已锁定 / 小孩 / 无工作站、`-2` 名字解析不出、
     `-3` 规则未开、`-4` 职业不支持（331 路线）、`-7` 该职业刷不出、`-8` 任务太多（上限 64）、
     `>0` 任务已排队（值是本次次数上限）。
- **关键文件**：`handler/entity/villager/trader/refresh/{AutoVillagerTrader,TradeRerollScheduler,TradeTableBuilder}.java`、
  版本层 `handler/.../refresh/{TradePoolChecker,TradeSetAdapter,TradeEnchantmentScanner}.java`、
  `mixin/entity/villager/trader/autoTrader/**`（含 `NameTagItemMixin` 触发点、`@Accessor` 写回点）
- **依赖**：
  - `VillagerOneTickTraderCount`（每 tick 模拟次数）
  - `VillagerAutoLockHitTrader`（命中后是否自动锁定）
  - `VillagerSilentTrader`（入队后是否清名字）
  - `TradeSearch`（命中后同步索引；不开启则 `captureNow` 直接返回）
  - `TradeIndexConfig`（别名表：名字 → 物品 / 附魔 id）
- **客户端行为**：无专属包。玩家看到的是"村民的交易表变了、界面自动刷新"。

#### `VillagerAutoLockHitTrader`（自动锁定村民交易）

- **注册名**：`VillagerAutoLockHitTrader`（枚举常量 `VILLAGER_AUTO_LOCK_HIT_TRADER`）
- **分类**：`VILLAGER`
- **默认值**：`false`
- **命令**：`/ec VillagerAutoLockHitTrader`（开关）；**无** `/ec c` 子命令
- **配置类**：无
- **触发**：`VillagerAutoTrader` 命中并写回的那一刻
- **实现思路**：写回时顺手做两件事 —— 把**第一笔交易用一次**（`uses +1`），再把村民经验**抬到至少 1**
  （`setVillagerXp(Math.max(1, currentXp))`，原本高于 1 的经验不会被降低）。这两个副作用合起来就是"锁"：
  - 第一笔已经用过 → 别人不能先把它买走；
  - `villagerXp > 0` → 刷新逻辑的"可重掷"判定直接不通过，同一只村民不会被重复刷。
- **关键文件**：`handler/entity/villager/trader/refresh/AutoVillagerTrader.java#doLockIfNeeded/lock`
- **依赖**：`VillagerAutoTrader`（不开自动刷新的话，这条规则没有任何触发点）
- **客户端行为**：无。玩家会看到第一笔交易显示"已使用 1/max"

#### `VillagerSilentTrader`（无感刷新）

- **注册名**：`VillagerSilentTrader`（枚举常量 `VILLAGER_SILENT_AUTO_REROLL_TRADER`）
- **分类**：`VILLAGER`
- **默认值**：`false`
- **命令**：`/ec VillagerSilentTrader`（开关）；**无** `/ec c` 子命令
- **配置类**：无
- **触发**：命名牌触发刷新、且**任务成功入队**的那一瞬间（不是命中那一刻）
- **实现思路**：入队成功后把村民的自定义名字清空，玩家看不到"这只被下过指令"的痕迹。
  **刷新被拒绝时不清**（例如名字解析不出、职业刷不出、任务太多），这样玩家可以原地改名重试。
  名字是**入队时就清**的，所以刷新跑满次数仍未命中时村民已经没名字了，想重试必须重新贴一张命名牌。
- **关键文件**：`mixin/entity/villager/trader/autoTrader/trigger/NameTagItemMixin.java`
- **依赖**：`VillagerAutoTrader`
- **客户端行为**：无

#### `VillagerOneTickTraderCount`（每 tick 刷新次数）

- **注册名**：`VillagerOneTickTraderCount`
- **分类**：`VILLAGER`
- **默认值**：`500`
- **可选值**：`50` / `100` / `500` / `1000`（自定义列表，任意文本都能写）
- **命令**：`/ec VillagerOneTickTraderCount <数值>`；**无** `/ec c` 子命令
- **配置类**：无
- **触发**：`TradeRerollScheduler.tick` —— **每个服务端 tick 读一次**（同一 tick 内所有任务共用这个预算）
- **实现思路**：值被 `Math.clamp(v, 1, 1000)` 收进 `[1, 1000]`，然后作为"**每个任务**本 tick 最多跑几次"的预算；
  跑满就 `break` 去处理下一个任务，剩下的次数留到下一 tick。**没有时间阀门**——
  不会因为某一次模拟慢就提前收工，所以单 tick 的耗时上限 ≈ `任务数 × 次数 × 单次模拟成本`。
  这是**数值档位规则**的设计：候选值都是数字，所以 `isEnabled` 恒为 true；要关掉这个功能就把规则设成
  `false`（使用方按关闭处理）。它的约束力靠 `clamp(1, 1000)` 与"任务数上限 64"。
- **关键文件**：`handler/entity/villager/trader/refresh/TradeRerollScheduler.java#attemptsPerTick`
- **依赖**：`VillagerAutoTrader`（唯一的使用者）
- **客户端行为**：无

#### `VillagerHardWorking`（勤劳的村民）

- **注册名**：`VillagerHardWorking`
- **分类**：`VILLAGER`
- **默认值**：`1`
- **可选值**：`1` / `2` / `5`（自定义列表）
- **命令**：`/ec VillagerHardWorking <倍率>`；**无** `/ec c` 子命令
- **配置类**：无
- **触发**：`Villager#restock`（补货）里原版对每笔交易调 `resetUses()` 的那一处（`@Redirect`）
- **实现思路**：把"补货后已用次数归零"换成"补货后已用次数变成**负数**"：
  `用的次数 = −(倍率 − 1) × maxUses`。于是补货后可再卖 `倍率 × maxUses` 次 —— 倍率 1 等于原版行为，
  倍率 ≤ 1 时走原版 `resetUses()`。补货量是写进 offer 的 `uses` 字段的，所以规则生效时界面里可能出现负的
  已用次数 —— 交易本身是正确的（原版只比较 `uses >= maxUses`）。
- **关键文件**：`mixin/entity/villager/trader/autoTrader/hardWork/MerchantOfferResetMixin.java`
- **依赖**：`MerchantOfferInterfaceMixin`（`@Accessor` 改 `uses` 字段）
- **客户端行为**：无。玩家看到的是"补货后能卖更多次"，界面上的已用次数会显示成负数

#### `VillagerInfiniteTrade`（无限交易）

- **注册名**：`VillagerInfiniteTrade`
- **分类**：`VILLAGER`
- **默认值**：`false`
- **命令**：`/ec VillagerInfiniteTrade`（开关）；**无** `/ec c` 子命令
- **配置类**：无
- **触发**：`MerchantOffer#increaseUses`（每成交一笔调一次）
- **实现思路**：规则开着时直接 `cancel` —— 已用次数永远不增，所以交易永不售罄。
  `uses` 不增也意味着原版靠 `uses` 抬价的供需涨价机制不会启动，玩家会一直拿到基础价；
  同时"补货"对这条规则没有意义。
- **关键文件**：`mixin/entity/villager/trader/infiniteTrade/MerchantOfferMixin.java`
- **依赖**：无
- **客户端行为**：无。玩家看到的是交易永远可以继续买

### ④ 交易查询与支付（四条）

#### `TradeSearch`（村民交易查询）

- **注册名**：`TradeSearch`（枚举常量 `VILLAGER_TRADE_SEARCH`）
- **分类**：`VILLAGER` + `COMMAND`
- **默认值**：`false`
- **命令**：
  - `/ec TradeSearch`（开关）
  - `/ec c TradeSearch` — 显示用法
  - `/ec c TradeSearch item [关键词]` — 查**普通物品**交易（不含附魔书）
  - `/ec c TradeSearch book [关键词]` — 查**附魔书**交易，关键词是附魔 id / 中文名
- **配置类**：`VillagerTradeCacheConfig`（`villagerdetails/villager_trade_cache.json`，**每 200 tick ≈ 10 秒**落盘一次）；
  别名表用 `TradeIndexConfig`（`villagerdetails/trade_index.json`）
- **触发**：五个抓取时机 —— 实体卸载（`ENTITY_UNLOAD`，**主入口**）、区块卸载（`CHUNK_UNLOAD`，兜底）、
  玩家打开交易界面（`TradeOpenMixin`，100 tick 防抖）、命名牌刷新成功（`captureNow`）、村民死亡（清缓存）。
  所以索引是**事件驱动**的：区块卸载、打开交易界面（100 tick 防抖）与显式刷新；区块一直不卸载的村民
  会在下一次抓取时被索引，**没有全局扫描**。
- **实现思路**：见 [深度剖析：村民交易查询](../03-deep-dives/trade-search.md)。摘要：
  1. **抓快照**：只收「**已锁定交易**」的村民（`villagerXp > 0` 或任意一笔 `uses > 0`），
     在主线程把交易表**深拷贝成纯数据**（uuid / 坐标 / 维度 / 物品 id + 数量 + 价格 + 可交易次数 + 附魔 id 与等级），
     不带任何原版对象进线程池。
  2. **不做"值不值得收录"的过滤**：只跳过"结果物为空"的空交易 —— 按索引做白名单会把书架、地图、
     命名牌这些正常出售交易全部误杀。
  3. **异步写索引**：快照交给服务端线程池，写进内存索引（`ConcurrentHashMap`）并标脏；
     落盘节奏由存储类自己控制。快照池不可用时记一条 WARN 并跳过本次抓取，下一次抓取重试。
  4. **查询**：`/ec c TradeSearch ...` 在主线程只做"1 秒防抖 + 提交任务"，匹配在**查询池**里跑，
     产出纯数据后切回主线程拼聊天栏消息 —— 玩家只多等 1 tick，主线程完全不受影响。
  5. **一致性**：死亡立刻删（并留"墓碑"挡迟到写入）、慢写入按时间戳比新旧、
     规则关掉时先落盘再清内存、重新打开时把磁盘索引读回来。
- **关键文件**：`handler/entity/villager/trade/{VillagerTradePipeline,VillagerTradeSearchHandler,TradeItemNameResolver}.java`、
  `cache/impl/VillagerTradeCache.java`、`config/impl/VillagerTradeCacheConfig.java`、
  `command/c/impl/rule/villager/TradeSearchCommand.java`、`event/impl/register/VillagerTradeSearchEvents.java`、
  `mixin/entity/villager/trader/tradeSearch/TradeOpenMixin.java`
- **依赖**：`TradeIndexConfig`（关键词 → id）、`TradeItemCompletionCache`（Tab 补全数据）、
  `ServerThreadManager`（快照池 / 查询池）、`VillagerTradeIndexJson`（JSON 编解码）
- **客户端行为**：查询结果由服务端拼好后发聊天栏（可点击坐标）；客户端不参与匹配，
  也不维护物品名（展示文本由客户端按自己的语言本地化）

#### `VillagerToolsmithExchange`（村民可兑换钻石）

- **注册名**：`VillagerToolsmithExchange`
- **分类**：`VILLAGER`
- **默认值**：`false`
- **命令**：`/ec VillagerToolsmithExchange`（开关）；**无** `/ec c` 子命令
- **配置类**：无
- **触发**：`AbstractVillager#getOffers` 的 RETURN（每次读交易表都过一遍）
- **实现思路**：只对**工具匠 / 武器匠**生效，从**第 6 笔交易起**（下标 5）扫描，
  把「钻石 → 绿宝石」的条目**原地替换**成「绿宝石 → 钻石」（数量互换，其余字段照抄原条目）。
  所以玩家看到的是"高等级交易可以拿绿宝石换钻石"。按交易条目下标定位（第 6 笔即下标 5）就是这条规则的设计，
  实现文件是 `mixin/entity/villager/trader/emeraldDecompose/AbstractVillagerExchangeMixin.java`：
  交易表顺序一旦变化（别的模组插了条目、或遍历顺序不同），改到的就是别的交易；
  而且它只改"钻石换绿宝石"这一种组合，钻石镐 / 钻石剑那类不处理。
- **关键文件**：`mixin/entity/villager/trader/emeraldDecompose/AbstractVillagerExchangeMixin.java`
- **依赖**：无
- **客户端行为**：无（交易表在服务端就被改写了，客户端只是显示）

#### `VillagerEmeraldDecompose`（绿宝石块自动拆解）

- **注册名**：`VillagerEmeraldDecompose`
- **分类**：`VILLAGER`
- **默认值**：`false`
- **命令**：`/ec VillagerEmeraldDecompose`（开关）；**无** `/ec c` 子命令
- **配置类**：无（配方写死在版本层 `ItemProvisioner`：`1 绿宝石块 → 9 绿宝石`）
- **触发**：`MerchantMenu#tryMoveItems` 的 HEAD（玩家在交易界面里切换 / 下单，交易槽位即将被填）；
  制备只发生在这一处，**玩家手动把绿宝石块拖进付款槽不会触发**。
- **实现思路**：
  1. 看这笔交易要的付款物（`itemCostA` / `itemCostB`）是不是有"制备配方"的目标物品；
  2. 算**缺口** = 需要的数量 − （背包里已有 + 交易槽位里已有）；
  3. 缺口 > 0 时才从背包里扣原料、按配方产出成品放进背包（背包满了就掉在脚下），
     备多了不回收，下次交易还能用。它**不是白送**：会真的消耗背包里的绿宝石块（只是把"大额零钱"换开）；
     背包满时成品会掉在地上（`drop`），可能被别的玩家捡走。
- **关键文件**：版本层 `handler/entity/villager/trader/decompose/ItemProvisioner.java`、
  `mixin/entity/piglinTrade/MerchantMenuProvisionMixin.java`（注意它放在 `piglinTrade` 包里）
- **依赖**：`MerchantMenuInterfaceMixin`（`@Accessor` 拿 trader / 交易槽位）、
  **规则开关在配方里**（每条配方带自己的 `RuleType`，没开这条规则就不会制备）
- **客户端行为**：无（纯服务端改背包，客户端看到的是"付款物自动出现了"）

#### `VillagerBookshelfDecompose`（书架自动拆解）

- **注册名**：`VillagerBookshelfDecompose`
- **分类**：`VILLAGER`
- **默认值**：`false`
- **命令**：`/ec VillagerBookshelfDecompose`（开关）；**无** `/ec c` 子命令
- **配置类**：无（配方写死：`1 书架 → 3 本书`）
- **触发 / 实现思路 / 关键文件**：与 `VillagerEmeraldDecompose` **完全共用同一套机制**
  （同一张配方表、同一个注入点），差别只有配方与规则开关。图书馆员的"书 → 绿宝石"交易与它天然契合，
  所以这条规则对图书管理员的经济影响最大；两条拆解规则同时开启时互不干扰（各自查各自的配方）。
- **依赖**：同 `VillagerEmeraldDecompose`
- **客户端行为**：同 `VillagerEmeraldDecompose`
