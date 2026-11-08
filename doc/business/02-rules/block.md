# Block Rules (BLOCK)

All ten rules whose primary `RuleType` category is `BLOCK`: what each one changes, how it hooks into vanilla, and where its boundaries lie.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> This file covers the **10** rules whose primary `RuleType` category is `BLOCK`.
>
> Four "list-config" rules (`BlockMiningResistance`, `BlockCollectable`, `BlockExplosionResistance`,
> `BlockStrongPower`) share one command shape (see the note at the top of [`entity.md`](entity.md)):
> `/ec c <rule> <block id> true|false` (for the numeric rules the value is the resistance number), `reset`, `show`,
> **all hard-wired to OWNERS** — these subcommands edit a persisted list (or mapping) directly, so hard-wiring
> OWNERS is deliberate and the rule's tier does not change it.
> Their config also lives in the **same-named section** of `villagerdetails/config.json`.
>
> ⚠️ **One mechanism runs through this whole page**: mining / drop rules only take effect for
> **players who have this mod installed** (checked by `BlockRuleSyncChannel.canSendTo`); clients without the mod
> keep vanilla logic — otherwise the server would change break time while the client computes vanilla, and
> blocks would visibly break too slowly or instantly.

---

#### `BlockMiningResistance` (block mining resistance)

- **Register name**: `BlockMiningResistance` (enum constant `BLOCK_MINING_RESISTANCE`)
- **Category**: `BLOCK` + `COMMAND`
- **Default value**: `false`
- **Commands**: `/ec BlockMiningResistance` (switch); `/ec c BlockMiningResistance <block id> <number>` / `reset` / `show`
- **Config class**: `BlockMiningResistanceConfig` (the `BlockMiningResistance` section of `config.json`, `block id → resistance number`)
- **Trigger**: HEAD of `BlockBehaviour#getDestroyProgress` (the overload that takes a `Player`)
- **How it works**:
  1. Filter by client side first: **a player whose client does not have this mod → straight to vanilla** (so the two sides never disagree on break time).
  2. Look up the config: **the mining-resistance override wins**; without it, fall back to the speed the "collectable" rule gives unbreakable blocks; with neither, vanilla.
  3. On a hit, compute the break progress directly and return it: `mining speed ÷ resistance ÷ penalty factor` (30 with the right tool, 100 without). The formula matches vanilla; only "resistance" is swapped for the configured value.
- **Key files**: `handler/block/miningResistance/MiningResistanceHandler.java`,
  `mixin/block/miningResistance/MiningResistanceMixin.java`, `config/impl/section/item/BlockMiningResistanceConfig.java`,
  `command/c/impl/rule/resistance/BlockMiningResistanceServerImpl.java`
- **Dependencies**: `BlockCollectable` (both share one injection point; mining resistance wins), `BlockRuleSyncChannel` (side detection)
- **Client behaviour**: the settings screen renders the `block id → number` list; the client **does not** take part in the computation — break progress is sent down by the server
- **Design rules and boundaries**: numeric input has no validator: a negative value turns break progress negative (the block can never be mined), and `0` divides by zero — confirm the number is positive before filling it in.

#### `BlockCollectable` (blocks become collectable)

- **Register name**: `BlockCollectable`
- **Category**: `BLOCK` + `COMMAND`
- **Default value**: `false`
- **Commands**: `/ec BlockCollectable` (switch); `/ec c BlockCollectable <block id> true|false` / `reset` / `show`
- **Config class**: `BlockCollectableConfig` (the `BlockCollectable` section of `config.json`, a block id list)
- **Trigger**: two injection points — `BlockBehaviour#getDestroyProgress` (so it can be mined) + `BlockStateBase#getDrops` (so it drops something)
- **How it works**:
  1. **Mineable**: if a block on the list is "unbreakable" in vanilla (`defaultDestroyTime() < 0`, such as bedrock or a monster spawner), it gets one default break time `DEFAULT_BREAKABLE_SPEED = 1.5`; blocks that could already be mined are left alone.
  2. **Drops**: when `getDrops` returns, if the vanilla drops are **empty** (no loot table, or the wrong tool), one drop of "the block itself" is added. When vanilla already drops something, the rule **does not intervene** (no double drops).
  3. When the destroyer is "a player whose client has no mod", vanilla runs: the player context is pushed into `BlockRuleContext` (a ThreadLocal) by the **version layer**'s `PlayerDestroyMixin` around `playerDestroy`, because `getDrops` itself has no player parameter.
- **Key files**: `handler/block/collectable/CollectableHandler.java`, `mixin/block/collectable/{CollectableMixin,PlayerDestroyMixin}.java`,
  `handler/block/BlockRuleContext.java`, `config/impl/section/item/BlockCollectableConfig.java`
- **Dependencies**: `BlockRuleContext` (carries the destroyer through a ThreadLocal), `BlockRuleSyncChannel`, the version-layer mixin
- **Client behaviour**: list page (same as the other three list rules)
- **Design rules and boundaries**:
  1. The injected signature is therefore written per version: there is one `PlayerDestroyMixin` under `v26.2/` and one under `v26.3/` (26.3 narrows the `playerDestroy` parameters from `(Level, Player)` to `(ServerLevel, ServerPlayer)`).
  2. Only "the block itself" is added as a drop; its original loot table is not simulated (bedrock does not drop diamonds).

#### `BlockExplosionResistance` (block explosion resistance)

- **Register name**: `BlockExplosionResistance`
- **Category**: `BLOCK` + `COMMAND`
- **Default value**: `false`
- **Commands**: `/ec BlockExplosionResistance` (switch); `/ec c BlockExplosionResistance <block id> <number>` / `reset` / `show`
- **Config class**: `BlockExplosionResistanceConfig` (same-named section of `config.json`, `block id → resistance number`)
- **Trigger**: HEAD of `ExplosionDamageCalculator#getBlockExplosionResistance`
- **How it works**: on a config hit, return the `Optional<Float>` of the "custom resistance"; air is passed straight through (no resistance is added to air).
  **No client-side fallback is needed**: explosion damage to blocks only happens on the server (`ServerExplosion`); the client neither receives nor computes it.
- **Key files**: `handler/block/explosionResistance/ExplosionResistanceHandler.java`,
  `mixin/block/explosionResistance/ExplosionResistanceMixin.java`,
  `config/impl/section/item/BlockExplosionResistanceConfig.java`,
  `command/c/impl/rule/resistance/BlockExplosionResistanceServerImpl.java`
- **Dependencies**: none (independent of mining resistance: one is mining, the other is explosions)
- **Client behaviour**: list page; no computation involvement
- **Design rules and boundaries**: the resistance only decides **whether the block survives the blast**; the drops produced *after* a block is blown up are not covered by this rule (that is `TntNoDestroyDrops`' job).

#### `BlockStrongPower` (blocks conduct redstone)

- **Register name**: `BlockStrongPower`
- **Category**: `BLOCK` + `COMMAND`
- **Default value**: `false`
- **Commands**: `/ec BlockStrongPower` (switch); `/ec c BlockStrongPower <block id> true|false` / `reset` / `show`
- **Config class**: `BlockStrongPowerConfig` (same-named section of `config.json`, a block id list)
- **Trigger**: HEAD of `BlockStateBase#isRedstoneConductor`
- **How it works**: every block on the list is judged a "redstone conductor", so like dirt / stone it can be powered by a redstone torch / repeater / comparator and passes power on to adjacent redstone wire.
  Judgement order: the rule switch first (the most common return path), then the list.
- **Key files**: `handler/block/power/StrongPowerHandler.java`,
  `mixin/block/power/BlockStateBaseConductorMixin.java`, `config/impl/section/item/BlockStrongPowerConfig.java`
- **Dependencies**: none
- **Client behaviour**: list page; redstone is computed entirely on the server
- **Design rules and boundaries**: `isRedstoneConductor` is asked per "block state", so **every state** of the same block becomes a conductor.
  Putting air / fluids on the list is pointless (they never reach this path).

#### `TntNoDestroyDrops` (TNT does not destroy dropped items)

- **Register name**: `TntNoDestroyDrops` (enum constant `TNT_NO_DESTROY_DROPS`)
- **Category**: `BLOCK`
- **Default value**: `false`
- **Commands**: `/ec TntNoDestroyDrops` (switch); **no** `/ec c` subcommand
- **Config class**: none
- **Trigger**: HEAD of `ExplosionDamageCalculator#shouldDamageEntity`
- **How it works**: three conditions must hold together — the rule is on, what gets damaged is a **dropped item** (`ItemEntity`),
  and the **direct source of this explosion is TNT** (`PrimedTnt`). Returning `false` means the item is neither damaged
  nor destroyed by this explosion. **Other explosion sources** (creeper, bed, end crystal, explosions from other mods) are unaffected.
- **Key files**: `handler/explosion/TntNoDestroyDropsHandler.java`, `mixin/block/TntNoDestroyDrops/ExplosionMixin.java`
- **Dependencies**: none
- **Client behaviour**: none (server only)
- **Design rules and boundaries**: only items **already lying on the ground at the instant of the explosion** are protected; the drops produced by the blocks the TNT itself blew up appear after the explosion, so they are not covered (they did not exist yet).

#### `BoxCrafting` (box crafting)

- **Register name**: `BoxCrafting`
- **Category**: `BLOCK`
- **Default value**: `false`
- **Commands**: `/ec BoxCrafting` (switch); **no** `/ec c` subcommand
- **Config class**: none ("one shulker box = 27 full stacks" is the code constant `BOX_SLOTS = 27`)
- **Trigger**: five injection points spread over four mixin classes — the crafting-table result preview
  (`CraftingMenu#slotChangedCraftingGrid`), crafting-table shift-click (`CraftingMenu#quickMoveStack`),
  normal click-to-take (`ResultSlot#onTake`), 2×2 inventory crafting (`InventoryMenu#quickMoveStack`),
  the crafter block (`CrafterBlock#dispenseFrom`)
- **How it works**:
  1. **Recognition**: when the slots hold shulker boxes that are "full of **one and the same** item, every slot a full stack", the box is treated as
     **27 full stacks** when matching vanilla recipes (the box contents are flattened into a virtual crafting grid and looked up).
     If any slot is short or the contents are mixed → this rule does not take over and vanilla runs (vanilla treats the box as an ordinary item, so the recipe usually does not match).
  2. **Planning**: assemble the recipe once, then scale by "how many portions of material the box holds":
     `total output = single output × material count`, then split it into "whole-box output" (27× the product's max stack) and "loose output".
  3. **Execution**: clear the material out of the input boxes and put the output into a shulker box (**the player's inventory must hold empty boxes**;
     when the input boxes run short, the shortfall is covered by empty boxes); the remaining loose product goes straight into the inventory, or is output to the container the crafter faces.
  4. **Why take over in four places**: the result is "box → box", but the vanilla result slot can hold only one `ItemStack` for the preview;
     if vanilla quick-move is not blocked, the preview is moved into the inventory first and the real output is stacked on top of it (a free extra copy).
- **Key files**: `handler/craft/BoxCraftingHandler.java` (recognition / planning / utilities),
  the version layer's `handler/craft/BoxCraftPlan.java` (execution: the crafting-table and crafter paths),
  `mixin/craft/{BoxCraftingMenuMixin,InventoryMenuMixin,ResultSlotMixin,CrafterBlockMixin,AbstractCraftingMenuAccessor}.java`
- **Dependencies**: `AbstractCraftingMenuAccessor` (`@Accessor` for `craftSlots` / `resultSlots`), `ModCodecs` / the vanilla recipe manager
- **Client behaviour**: the client **does not need to do anything** — the result-slot preview is computed on the server and pushed
  (`ClientboundContainerSetSlotPacket`)
- **Design rules and boundaries**:
  1. **An empty shulker box is required** to seal a box; with no empty box only loose product comes out (a huge amount scatters on the ground).
  2. Only the **vanilla recipe type** (`RecipeType.CRAFTING`) is recognised; smithing tables, furnaces and custom recipe machines do not take part.
  3. The output box always uses the **plain shulker box** shell, regardless of colour; the dye information of the input boxes does not follow the product.
  4. The crafter path computes one plan from "the current slot contents" and cancels vanilla's two-stage animation (`dispenseFrom` is cancelled),
     so pulsing redstone at a crafter repeatedly is one box craft per pulse.

#### `PortalBinding` (portal binding)

- **Register name**: `PortalBinding`
- **Category**: `BLOCK`
- **Default value**: `false`
- **Allowed values**: `false` / `true` / `ops` / `master` / `admin` / `owner` (permission tiers)
- **Commands**:
  ```
  /ec c PortalBinding set            cache the BB selection of the current dimension as "to be bound in this dimension"
  /ec c PortalBinding bind           both sides cached → write one binding
  /ec c PortalBinding show           list every binding (each with the #ID, a [refresh] and a [delete] button)
  /ec c PortalBinding view <id>      push one binding to the client for highlighting
  /ec c PortalBinding delete <id>    clear the cached portal coordinates on both sides first, then delete the binding
  /ec c PortalBinding refresh <id>   clear the cached portal coordinates on both sides and rescan immediately
  ```
- **Config class**: `PortalBindingConfig` (the `PortalBinding` section of `config.json`; the binding table = the two dimensions + their regions + the cached portal bottom coordinates + `#ID`)
- **Trigger**: the `teleport(...)` call inside `Entity#teleportToPortalDestination` (`@Redirect`, **in the version layer**)
- **How it works**: see [deep dive: portal binding](../03-deep-dives/portal-binding.md). Summary:
  1. **Region binding**: each binding pairs "one region in the overworld ↔ one region in the nether". `#ID` only ever grows — deleting does not recycle it,
     so `#3` in chat always means the same binding.
  2. **Finding portals**: the vanilla POI engine (the same one `PortalForcer` uses) looks for portals inside the target region; the scan tiles the region
     and has a budget cap, and when it **did not cover everything it says so** (`complete = false`) instead of guessing; a new portal is built inside the region only when the whole region really has none.
  3. **Caching**: `bind` pre-warms it (portals already in the region are resolved and cached right away), the first teleport resolves lazily and writes back,
     every teleport checks that the cached cell still is a portal (a miss triggers a rescan), and `refresh` clears the cache and rescans.
  4. **Multi-hit arbitration (option A)**: no hit → vanilla; a single hit → straight through; multiple hits → prefer the candidate in the "dimension vanilla would use",
     then take the nearest by Euclidean distance from "vanilla's theoretical landing point" (overworld ↔ nether XZ at 1:8 scale), ties broken by coordinate lexicographic order.
  5. **Landing spot**: a player lands at the centre of the portal bottom; non-players prefer one block outside the portal (so they are not stuck in a portal for 15 seconds and bounced back).
- **Key files**: `handler/binding/block/portalbinding/{PortalBindingHandler,PortalBindingService,PortalLocator,PortalBindingManager}.java`,
  `config/impl/section/item/PortalBindingConfig.java`, `command/c/impl/rule/region/PortalBindingServerImpl.java`,
  the version layer's `mixin/block/portalBinding/EntityPortalMixin.java`, `cache/impl/PortalSelectionCache.java`
- **Dependencies**:
  - `ListenerType.BLOCK_RANGE_SELECTION` (BB selection + `BBSectionStateCache`)
  - `PORTAL_BINDING_ENHANCED` (enhanced mode)
  - the client-side `PortalViewSyncChannel` (highlighting)
  - `AbstractRegionBindingCommand` (the command-tree base class; `refresh` is the one subcommand only this rule has)
- **Client behaviour**: the coordinate line in `show` is clickable to highlight; the client caches highlight boxes per dimension, so walking into that dimension shows them
- **Design rules and boundaries**:
  1. **The tier decides who may run the binding commands, not who gets force-teleported**: `EntityPortalMixin` reads the **global**
     `RuleCache.isEnabled(PORTAL_BINDING)` (no per-player permission check), because it has to cover monsters and dropped items — "monsters have no notion of permission".
     So as long as the rule is not `false`, one binding's runtime effect applies to **every entity that walks into the bound region**;
     setting the tier to `ops` only makes "change the bindings" OP-only.
  2. Both portal-binding rules are implemented **entirely in the version layers** as `mixin/block/portalBinding/EntityPortalMixin` (one copy under `v26.2/`, one under `v26.3/`):
     the two redirect to different target methods (26.3 extracted `canTeleport + teleport` into `teleportToPortalDestination`).
  3. `refresh` picks the portal nearest to the region centre; it does not guarantee the portal the player had in mind.

#### `PortalBindingEnhanced` (portal binding, enhanced)

- **Register name**: `PortalBindingEnhanced`
- **Category**: `BLOCK`
- **Default value**: `false`
- **Commands**: `/ec PortalBindingEnhanced <state>` (**a pure switch, deliberately with no command object**;
  see "Design rules and boundaries" in [`../00-overview.md`](../00-overview.md))
- **Config class**: none
- **Trigger**: the branch inside `EntityPortalMixin` that runs after the `PORTAL_BINDING` decision
- **How it works**:
  1. It is **not** a separate feature: once `PortalBinding` has resolved a target, if this rule is on as well (and the entity is a player),
     the "enhanced path" runs — the target position **no longer has to contain a portal**; instead a **standable** landing spot is searched asynchronously
     (`PortalLocator#forceSpawnAt`, which may load chunks on the spot).
  2. The enhanced path is **asynchronous**: the search spans several ticks, so the teleport is deferred into the future's completion callback;
     when no safe landing spot is found the teleport is **cancelled** and the player is told (better not to teleport than to drop someone into the void or into a block).
  3. Before queueing, the entity's `portalProcess` is cleared — otherwise the portal animation timer would let the same player queue two teleports;
     in the callback `isRemoved()` catches "replaced by another teleport in the meantime".
  4. **Only the synchronous fast path — "a portal already exists in the region" — writes back to the cache**; an asynchronous search yields ordinary ground,
     which must not be cached as a portal (otherwise the next teleport would use that ground as a portal).
- **Key files**: the version layer's `mixin/block/portalBinding/EntityPortalMixin.java` (`teleportEnhanced`),
  `handler/binding/block/portalbinding/PortalLocator.java` (`forceSpawnAt`)
- **Dependencies**: `PortalBinding` (with the main rule off, this rule is never read)
- **Client behaviour**: no channel of its own; what the player notices is "the portal works even with no portal around" or "no landing spot, so it did not go"
- **Design rules and boundaries**:
  1. **Players only** (monsters / dropped items take the synchronous path) — this is deliberate: asynchronous search plus landing-spot construction is meaningless for non-players.
  2. Enhanced mode builds no portal; it only finds a landing spot. A "portal" may still be built by `PortalBinding`'s ordinary path.

#### `EndPortalGravityBlock` (gravity blocks near the end portal)

- **Register name**: `EndPortalGravityBlock` (enum constant `ENDER_PORTAL_GRAVITY_BLOCK`)
- **Category**: `BLOCK` + `COMMAND`
- **Default value**: `false`
- **Commands**: `/ec EndPortalGravityBlock` (switch); `/ec c EndPortalGravityBlock <block id> true|false` / `reset` / `show`
- **Config class**: `EndPortalGravityBlockConfig` (same-named section of `config.json`, a block id list)
- **Trigger**: `BlockBehaviour#onPlace` (TAIL), `#neighborChanged` (HEAD), `#tick` (HEAD)
- **How it works**: "sand falls" is transplanted onto the listed blocks, but only near an end portal:
  1. **When to check**: when a block is placed or a neighbour changes, if it is within **5×5×5 of an end portal**,
     a scheduled tick 2 ticks later is registered (the same default delay sand has).
  2. **Whether it falls**: when the scheduled tick fires, everything is re-checked — the rule is on, the block is on the list, **below it is air**, and there is an end portal within the 5×5×5;
     only when all four hold does it call `FallingBlockEntity.fall`.
  3. **Blocks that already fall are untouched**: subclasses of `FallingBlock` (sand / gravel / concrete powder…) keep vanilla's own logic.
- **Key files**: `mixin/block/endPortalGravityBlock/EndPortalGravityMixin.java`,
  `config/impl/section/item/EndPortalGravityBlockConfig.java`, `command/c/impl/rule/rule/EndPortalGravityBlockServerImpl.java`
- **Dependencies**: none (pure block behaviour)
- **Client behaviour**: list page; the falling animation is synced by the vanilla entity
- **Design rules and boundaries**:
  1. The judged range is a **5×5×5 box centred on the block itself**, not "centred on the portal" —
     so a block placed one step outside the portal's edge does not fall, while one a bit further away falls as long as it is still inside that box.
  2. Once a block starts falling it becomes an ordinary `FallingBlockEntity`, and on landing it is handled by **its own block** behaviour
     (it does not "fall back" because it left the portal range).

#### `FurnaceDirtyHighlight` (highlight dirty furnaces)

- **Register name**: `FurnaceDirtyHighlight`
- **Category**: `BLOCK`
- **Default value**: `false`
- **Commands**: `/ec FurnaceDirtyHighlight` (switch); **no** `/ec c` subcommand
- **Config class**: `FurnaceDirtyHighlightConfig` (the `FurnaceDirtyHighlight` section of `config.json`)
  - `viewChunkRadius`: scan radius in chunks, default `0` = **follow the server view distance**, clamped to `[0, 32]`
  - `scanIntervalTicks`: rescan interval, default `20`, clamped to `[5, 100]`
- **Trigger**: the `END_SERVER_TICK` inspection (`FurnaceDirtyTracker#tick`) + a player crossing a chunk / changing dimension + a rule change
- **How it works**: see [deep dive: dirty furnace highlighting](../03-deep-dives/furnace-dirty.md). Summary:
  1. **Judgement** (`FurnaceDirtyScanner`): the input slot is **non-empty**, but **no recipe in this furnace's own recipe table can smelt it** → dirty.
     A plain furnace uses `SMELTING`, a blast furnace `BLASTING`, a smoker `SMOKING`; **modded furnaces are skipped outright**
     (their recipes need not hang off those three types, and guessing one would only cause false positives). The fuel slot is not inspected.
  2. **The server keeps a global dirty table** (dimension → dirty furnace coordinates) **plus each player's currently synced view-distance chunk set**;
     when a player crosses a chunk, newly entering chunks get `ADD` and leaving ones `REMOVE`, and only the changes are pushed to that player. Pruning chunks nobody watches
     has to run **after** the view records are updated — that order is deliberate, see [deep dive: dirty furnace highlighting](../03-deep-dives/furnace-dirty.md).
  3. **Periodic rescans** cover the chunks currently inside someone's view distance, and a state change is pushed only to the players **whose view distance covers that coordinate**.
  4. The benefit: the client's cached coordinate count is bounded by ≈ `view-distance chunk count × dirty furnaces per chunk`, and **it does not grow no matter how far the player flies**.
- **Key files**: `handler/block/furnaceDirty/{FurnaceDirtyTracker,FurnaceDirtyScanner}.java`,
  `config/impl/section/item/FurnaceDirtyHighlightConfig.java`,
  `network/sync/clientbound/FurnaceDirtySyncChannel.java`, `network/sync/event/FurnaceDirtySyncEvents.java`
- **Dependencies**: `FurnaceDirtySyncChannel` (incremental push), `scanIntervalTicks` / `viewChunkRadius` (throttling and view distance)
- **Client behaviour**: keeps a "dimension → dirty furnace coordinates" table and draws a highlight box around those furnaces in the world;
  when the rule is switched off the table is cleared
- **Design rules and boundaries**:
  1. **The recipe check uses the server recipe table**, and a data pack reload does **not** trigger an immediate rescan — the next inspection period handles it.
  2. Only the **three vanilla** furnace block-entity types are recognised; modded furnaces are never highlighted (deliberate, see above).
  3. An empty input slot with junk in the fuel slot → **no highlight** ("dirty" in the rule name means the input slot, and only the input slot).

---

<a id="中文"></a>

## 中文

> 本文件覆盖 `RuleType` 里主分类为 `BLOCK` 的 **10 条**规则。
>
> 四条"逐条配置类"（`BlockMiningResistance`、`BlockCollectable`、`BlockExplosionResistance`、
> `BlockStrongPower`）共用同一套命令形态（见 [`entity.md`](entity.md) 开头的说明）：
> `/ec c <规则> <方块id> true|false`（数值类规则的值就是抗性数字）、`reset`、`show`，**全部写死 OWNERS**
> —— 这些子命令直接改一份持久化的名单，写死 OWNERS 是刻意的，规则档位不会改变这一点。
> 它们的配置也全部落在 `villagerdetails/config.json` 的**同名节**里。
>
> ⚠️ **一条贯穿全篇的机制**：挖掘 / 掉落这类规则只在**装了本模组的玩家**身上生效
> （`BlockRuleSyncChannel.canSendTo` 判断），没装模组的客户端走原版逻辑 ——
> 否则服务端改了破坏时间、客户端却按原版算，会直接出现"挖不动 / 秒挖"的错位。

---

#### `BlockMiningResistance`（方块挖掘抗性）

- **注册名**：`BlockMiningResistance`（枚举常量 `BLOCK_MINING_RESISTANCE`）
- **分类**：`BLOCK` + `COMMAND`
- **默认值**：`false`
- **命令**：`/ec BlockMiningResistance`（开关）；`/ec c BlockMiningResistance <方块id> <数值>` / `reset` / `show`
- **配置类**：`BlockMiningResistanceConfig`（`config.json` 的 `BlockMiningResistance` 节，`方块id → 抗性数字`）
- **触发**：`BlockBehaviour#getDestroyProgress`（带 `Player` 参数的那个重载）的 HEAD
- **实现思路**：
  1. 先按玩家端侧过滤：**没装本模组的客户端玩家 → 直接走原版**（避免两端算出的破坏时间不一致）。
  2. 查配置：**挖掘抗性覆盖值优先**，没有再看「可采集」规则给不可破坏方块的兜底速度，都没有就走原版。
  3. 命中的话直接算出破坏进度返回：`挖掘速度 ÷ 抗性 ÷ 惩罚系数`（有正确工具 30、没有 100）。
     公式与原版一致，只是把"抗性"换成配置值。
- **关键文件**：`handler/block/miningResistance/MiningResistanceHandler.java`、
  `mixin/block/miningResistance/MiningResistanceMixin.java`、`config/impl/section/item/BlockMiningResistanceConfig.java`、
  `command/c/impl/rule/resistance/BlockMiningResistanceServerImpl.java`
- **依赖**：`BlockCollectable`（两者共用同一个注入点，挖掘抗性优先）、`BlockRuleSyncChannel`（端侧判定）
- **客户端行为**：设置界面渲染 `方块 id → 数值` 列表；客户端**不参与**计算，破坏进度由服务端下发
- **设计约定与边界**：数值输入不做校验，由填写者保证是正数 —— 负数会让破坏进度变成负数（方块永远挖不掉），`0` 会除零。

#### `BlockCollectable`（方块可采集）

- **注册名**：`BlockCollectable`
- **分类**：`BLOCK` + `COMMAND`
- **默认值**：`false`
- **命令**：`/ec BlockCollectable`（开关）；`/ec c BlockCollectable <方块id> true|false` / `reset` / `show`
- **配置类**：`BlockCollectableConfig`（`config.json` 的 `BlockCollectable` 节，方块 id 名单）
- **触发**：两个注入点 —— `BlockBehaviour#getDestroyProgress`（让它挖得动）+ `BlockStateBase#getDrops`（让它掉东西）
- **实现思路**：
  1. **挖得动**：名单里的方块如果原版"不可破坏"（`defaultDestroyTime() < 0`，比如基岩、刷怪笼），
     统一给一个默认破坏时间 `DEFAULT_BREAKABLE_SPEED = 1.5`；本来能挖的方块不动。
  2. **掉东西**：`getDrops` 返回时，如果原版掉落是**空的**（没有战利品表、或没拿对工具），
     就补一个"方块本身"的掉落。原版已有掉落时**不干预**（不会变成双倍掉落）。
  3. 破坏者是"没装模组的客户端玩家"时走原版：玩家上下文由**版本层**的 `PlayerDestroyMixin`
     在 `playerDestroy` 前后塞进 `BlockRuleContext`（ThreadLocal），因为 `getDrops` 本身没有玩家参数。
- **关键文件**：`handler/block/collectable/CollectableHandler.java`、`mixin/block/collectable/{CollectableMixin,PlayerDestroyMixin}.java`、
  `handler/block/BlockRuleContext.java`、`config/impl/section/item/BlockCollectableConfig.java`
- **依赖**：`BlockRuleContext`（ThreadLocal 传递破坏者）、`BlockRuleSyncChannel`、版本层 mixin
- **客户端行为**：列表页（同其它三条名单类规则）
- **设计约定与边界**：
  1. 注入签名因此必须按版本写：`PlayerDestroyMixin` 在 `v26.2/` 与 `v26.3/` 各一份（26.3 把 `playerDestroy` 的参数从
     `(Level, Player)` 收窄成 `(ServerLevel, ServerPlayer)`）。
  2. 只补"方块本身"的掉落，不会模拟它原本的战利品表（基岩不会掉钻石）。

#### `BlockExplosionResistance`（方块爆炸抗性）

- **注册名**：`BlockExplosionResistance`
- **分类**：`BLOCK` + `COMMAND`
- **默认值**：`false`
- **命令**：`/ec BlockExplosionResistance`（开关）；`/ec c BlockExplosionResistance <方块id> <数值>` / `reset` / `show`
- **配置类**：`BlockExplosionResistanceConfig`（`config.json` 同名节，`方块id → 抗性数字`）
- **触发**：`ExplosionDamageCalculator#getBlockExplosionResistance` 的 HEAD
- **实现思路**：命中配置就返回"自定义抗性"的 `Optional<Float>`；空气直接放行（不给空气加抗性）。
  **不需要端侧回退**：方块的爆炸破坏只发生在服务端（`ServerExplosion`），客户端拿不到也不会算。
- **关键文件**：`handler/block/explosionResistance/ExplosionResistanceHandler.java`、
  `mixin/block/explosionResistance/ExplosionResistanceMixin.java`、
  `config/impl/section/item/BlockExplosionResistanceConfig.java`、
  `command/c/impl/rule/resistance/BlockExplosionResistanceServerImpl.java`
- **依赖**：无（与挖掘抗性互不影响：一个是挖掘、一个是爆炸）
- **客户端行为**：列表页；无计算参与
- **设计约定与边界**：抗性只影响"方块会不会被炸掉"；**方块被炸掉后产生的掉落物**不受这条规则保护
  （那是 `TntNoDestroyDrops` 的事）。

#### `BlockStrongPower`（方块红石强充能）

- **注册名**：`BlockStrongPower`
- **分类**：`BLOCK` + `COMMAND`
- **默认值**：`false`
- **命令**：`/ec BlockStrongPower`（开关）；`/ec c BlockStrongPower <方块id> true|false` / `reset` / `show`
- **配置类**：`BlockStrongPowerConfig`（`config.json` 同名节，方块 id 名单）
- **触发**：`BlockStateBase#isRedstoneConductor` 的 HEAD
- **实现思路**：名单里的方块一律被判成"红石导体"，于是像泥土 / 石头一样：
  能被红石火把 / 中继器 / 比较器充能，也能把充能传给相邻的红石线。
  判定顺序：先看规则开关（最常见返回路径），再看名单。
- **关键文件**：`handler/block/power/StrongPowerHandler.java`、
  `mixin/block/power/BlockStateBaseConductorMixin.java`、`config/impl/section/item/BlockStrongPowerConfig.java`
- **依赖**：无
- **客户端行为**：列表页；红石计算完全在服务端
- **设计约定与边界**：`isRedstoneConductor` 是按"方块状态"问的，所以同一个方块的**所有状态**都会变成导体。
  名单里放空气 / 流体没意义（不会命中这条路径）。

#### `TntNoDestroyDrops`（TNT 不炸掉落物）

- **注册名**：`TntNoDestroyDrops`（枚举常量 `TNT_NO_DESTROY_DROPS`）
- **分类**：`BLOCK`
- **默认值**：`false`
- **命令**：`/ec TntNoDestroyDrops`（开关）；**无** `/ec c` 子命令
- **配置类**：无
- **触发**：`ExplosionDamageCalculator#shouldDamageEntity` 的 HEAD
- **实现思路**：三个条件同时满足才保护 —— 规则开启、被伤害的是**掉落物**（`ItemEntity`）、
  且这次爆炸的**直接来源是 TNT**（`PrimedTnt`）。返回 `false` 后掉落物在这次爆炸里
  既不受伤害、也不会被摧毁。**其它爆炸来源**（苦力怕、床、末水晶、其它模组的爆炸）不受影响。
- **关键文件**：`handler/explosion/TntNoDestroyDropsHandler.java`、`mixin/block/TntNoDestroyDrops/ExplosionMixin.java`
- **依赖**：无
- **客户端行为**：无（纯服务端）
- **设计约定与边界**：只保护**爆炸瞬间已经躺在地上的掉落物**。被自己炸掉的方块产生的掉落物是爆炸之后才出现的，
  不受这条规则保护（它们本来就还没被创建）。

#### `BoxCrafting`（盒子合成）

- **注册名**：`BoxCrafting`
- **分类**：`BLOCK`
- **默认值**：`false`
- **命令**：`/ec BoxCrafting`（开关）；**无** `/ec c` 子命令
- **配置类**：无（"一个潜影盒 = 27 个满堆"是代码常量 `BOX_SLOTS = 27`）
- **触发**：五个注入点（分属四个 mixin 类）—— 工作台结果预览（`CraftingMenu#slotChangedCraftingGrid`）、
  工作台 Shift 点击（`CraftingMenu#quickMoveStack`）、普通点击取出（`ResultSlot#onTake`）、
  物品栏 2×2 合成（`InventoryMenu#quickMoveStack`）、合成器方块（`CrafterBlock#dispenseFrom`）
- **实现思路**：
  1. **识别**：格子里放"装满**同一种**物品、且每格都是满堆"的潜影盒时，把这个盒子当成
     **27 个满堆**来匹配原版配方（把盒子内容摊平成一个虚拟合成网格去查配方）。
     任何一格不满 / 混装 → 这条规则不接管，走原版（原版会把盒子当成普通物品，配方多半不匹配）。
  2. **算方案**：配方算一次（`assemble`），再按"盒子里总共有多少份原料"放大：
     `总产出 = 单次产出 × 原料总数`，然后拆成"整盒产出"（27×产物最大堆叠）与"零散产出"。
  3. **执行**：清掉输入盒里的原料，把产出装进潜影盒（**需要玩家背包里有空盒子**，
     输入盒数不够时按缺口找空盒），剩下的零散产物直接进背包 / 输出到合成器朝向的容器。
  4. **为什么要在 4 个地方都接管**：结果是"盒子 → 盒子"，原版结果槽只能塞一个 `ItemStack` 预览，
     如果不拦住原版快速移动，预览会先被塞进背包、再叠加真实产物（等于白送一份）。
- **关键文件**：`handler/craft/BoxCraftingHandler.java`（识别 / 算方案 / 工具方法）、
  版本层 `handler/craft/BoxCraftPlan.java`（执行：工作台 / 合成器两条路径）、
  `mixin/craft/{BoxCraftingMenuMixin,InventoryMenuMixin,ResultSlotMixin,CrafterBlockMixin,AbstractCraftingMenuAccessor}.java`
- **依赖**：`AbstractCraftingMenuAccessor`（`@Accessor` 拿 `craftSlots` / `resultSlots`）、`ModCodecs`/原版配方管理器
- **客户端行为**：客户端**不需要做任何事**——结果槽的预览是服务端算好推过去的（`ClientboundContainerSetSlotPacket`）
- **设计约定与边界**：
  1. **必须有空潜影盒**才能封盒；没有空盒时只能吐散装产物（数量巨大时会掉一地）。
  2. 只认**原版配方类型**（`RecipeType.CRAFTING`）；锻造台、熔炉、自定义配方机器不参与。
  3. 产物盒固定用**普通潜影盒**外壳，不区分颜色；输入盒的染色信息不会跟着产物走。
  4. 合成器路径一次只按"当前格子内容"算一次方案并取消原版两阶段动画（`dispenseFrom` 被 cancel），
     所以对着合成器反复打红石脉冲就是一次一次的盒子合成。

#### `PortalBinding`（传送门绑定）

- **注册名**：`PortalBinding`
- **分类**：`BLOCK`
- **默认值**：`false`
- **可选值**：`false` / `true` / `ops` / `master` / `admin` / `owner`（权限档位）
- **命令**：
  ```
  /ec c PortalBinding set            把当前维度的 BB 选区缓存成"本维度待绑定"
  /ec c PortalBinding bind           两侧都缓存好 → 写入一条绑定
  /ec c PortalBinding show           列出全部绑定（每条带 #ID、[刷新]、[删除] 按钮）
  /ec c PortalBinding view <id>      把某条绑定推给客户端高亮
  /ec c PortalBinding delete <id>    先清两侧缓存的门坐标，再删除绑定
  /ec c PortalBinding refresh <id>   清掉两侧缓存的门坐标并立即重扫
  ```
- **配置类**：`PortalBindingConfig`（`config.json` 的 `PortalBinding` 节；绑定表 = 两侧维度 + 各自区域 + 缓存门底坐标 + `#ID`）
- **触发**：`Entity#teleportToPortalDestination` 里那次 `teleport(...)` 调用（`@Redirect`，**在版本层**）
- **实现思路**：见 [深度剖析：传送门绑定](../03-deep-dives/portal-binding.md)。摘要：
  1. **区域绑定**：每条绑定是"主世界一块区域 ↔ 下界一块区域"的配对，`#ID` 只增不减、删除不回收，
     所以聊天栏里的 `#3` 永远指同一条。
  2. **找门**：用原版 POI 引擎（和 `PortalForcer` 同一套）在目标区域里找门，区域扫描按瓦片铺满、
     有预算上限，**没扫全就说没扫全**（`complete = false`），不猜；整片区域确实没门才在区域内建新门。
  3. **缓存**：`bind` 时预热（区域里已有门就直接解析并缓存）、首次传送懒解析后回写、每次传送前校验缓存
     那格是不是门（失效则重扫）、`refresh` 清缓存重扫。
  4. **多命中仲裁（方案 A）**：无命中 → 走原版；单命中 → 直通；多命中 → 优先"原版对应维度"的候选，
     再按"原版理论落点"（主世界 ↔ 下界 XZ 1:8 缩放）算欧氏距离取最近，等距按坐标字典序 tie-break。
  5. **落点**：玩家落在门底中心；非玩家优先落在门外一格（避免卡在门里 15 秒被弹回）。
- **关键文件**：`handler/binding/block/portalbinding/{PortalBindingHandler,PortalBindingService,PortalLocator,PortalBindingManager}.java`、
  `config/impl/section/item/PortalBindingConfig.java`、`command/c/impl/rule/region/PortalBindingServerImpl.java`、
  版本层 `mixin/block/portalBinding/EntityPortalMixin.java`、`cache/impl/PortalSelectionCache.java`
- **依赖**：
  - `ListenerType.BLOCK_RANGE_SELECTION`（BB 选区 + `BBSectionStateCache`）
  - `PORTAL_BINDING_ENHANCED`（增强模式）
  - 客户端 `PortalViewSyncChannel`（高亮）
  - `AbstractRegionBindingCommand`（命令树基类，`refresh` 是本规则独有子命令）
- **客户端行为**：`show` 里的坐标行可点击高亮；客户端按维度缓存高亮框，走到那个维度就能看到
- **设计约定与边界**：
  1. **档位管的是"谁能跑绑定命令"，不是"谁能被强制传送"**：`EntityPortalMixin` 用的是
     **全局** `RuleCache.isEnabled(PORTAL_BINDING)`（不按玩家判权限），因为它要覆盖怪物和掉落物
     ——「怪物没有权限概念」。只要规则不是 `false`，一条绑定的运行时效果就作用于**走进绑定区域的每一个实体**；
     把档位设成 `ops` 只会让"改绑定"这件事变成 OP 专属。
  2. 两条传送门绑定规则**全在版本层**实现：`mixin/block/portalBinding/EntityPortalMixin`，`v26.2/` 与 `v26.3/` 各一份，
     两版的 `EntityPortalMixin` 重定向的目标方法不同（26.3 把 `canTeleport + teleport` 抽成了 `teleportToPortalDestination`）。
  3. `refresh` 只按"离区域中心最近"选门，不保证换成玩家想要的那扇。

#### `PortalBindingEnhanced`（传送门绑定增强版）

- **注册名**：`PortalBindingEnhanced`
- **分类**：`BLOCK`
- **默认值**：`false`
- **命令**：`/ec PortalBindingEnhanced <state>`（**纯开关，故意没有命令对象**，
  见 [`../00-overview.md`](../00-overview.md) 的「设计约定与边界」）
- **配置类**：无
- **触发**：`EntityPortalMixin` 里 `PORTAL_BINDING` 判定之后的一层分支
- **实现思路**：
  1. 它**不是**一条独立功能：`PortalBinding` 已经解析出目标之后，如果这条规则也开着（且实体是玩家），
     就走"增强路径" —— **不要求目标位置必须已有门**，改成异步找一个**站得住的落脚点**
     （`PortalLocator#forceSpawnAt`，可能现场加载区块）。
  2. 增强路径是**异步**的：搜索要跨好几个 tick，所以传送被推迟到 future 完成的回调里，
     找不到安全落脚点就**取消传送**并提示玩家（宁可不传，也不让人掉虚空 / 卡进方块）。
  3. 排队前会清掉实体的 `portalProcess`，否则过门动画重新计时会让同一个玩家排上两次传送；
     回调里再用 `isRemoved()` 兜住"期间已被另一次传送替换"的情况。
  4. **只有"区域内已有门"的同步快路径才会回写缓存**；异步搜索得到的是普通地面，
     不能当成门缓存（否则下次会把地面当门用）。
- **关键文件**：版本层 `mixin/block/portalBinding/EntityPortalMixin.java`（`teleportEnhanced`）、
  `handler/binding/block/portalbinding/PortalLocator.java`（`forceSpawnAt`）
- **依赖**：`PortalBinding`（不开主规则，这条规则永远不会被读到）
- **客户端行为**：无专属包；玩家感知到的是"周围没有门也能过去"或"找不到落脚点，没过去"
- **设计约定与边界**：
  1. **只对玩家生效**（怪物 / 掉落物走同步路径）——这是刻意的：异步搜索 + 建落脚点对非玩家没有意义。
  2. 增强模式不建门，只找落地点；"门"仍然可能由 `PortalBinding` 的普通路径建。

#### `EndPortalGravityBlock`（末地门重力方块）

- **注册名**：`EndPortalGravityBlock`（枚举常量 `ENDER_PORTAL_GRAVITY_BLOCK`）
- **分类**：`BLOCK` + `COMMAND`
- **默认值**：`false`
- **命令**：`/ec EndPortalGravityBlock`（开关）；`/ec c EndPortalGravityBlock <方块id> true|false` / `reset` / `show`
- **配置类**：`EndPortalGravityBlockConfig`（`config.json` 同名节，方块 id 名单）
- **触发**：`BlockBehaviour#onPlace`（TAIL）、`#neighborChanged`（HEAD）、`#tick`（HEAD）
- **实现思路**：把"沙子下落"这件事移植到名单方块上，但只限定在末地传送门附近：
  1. **什么时候检查**：方块被放下、邻居变化时，如果它在**末地传送门 5×5×5 范围**内，
     就注册一个 2 tick 后的计划刻（和沙子默认延迟一致）。
  2. **会不会掉**：计划刻到期时复核 —— 规则开着、方块在名单里、**下方是空气**、且 5×5×5 内有末地门，
     四个条件都成立才 `FallingBlockEntity.fall`。
  3. **本来就会掉的方块不碰**：`FallingBlock` 的子类（沙子 / 沙砾 / 混凝土粉末…）走原版自己的逻辑。
- **关键文件**：`mixin/block/endPortalGravityBlock/EndPortalGravityMixin.java`、
  `config/impl/section/item/EndPortalGravityBlockConfig.java`、`command/c/impl/rule/rule/EndPortalGravityBlockServerImpl.java`
- **依赖**：无（纯方块行为）
- **客户端行为**：列表页；下落动画由原版实体同步
- **设计约定与边界**：
  1. 判定范围是**以方块自身为中心的 5×5×5**，不是"以传送门为中心"——
     所以贴着传送门边缘外一格放的方块不会掉，而稍微远一点的只要还在那个盒子里就会掉。
  2. 方块一旦开始下落就变成普通 `FallingBlockEntity`，落地后按**它自己的方块**行为处理
     （不会因为离开了传送门范围而"落回去"）。

#### `FurnaceDirtyHighlight`（熔炉脏东西高亮）

- **注册名**：`FurnaceDirtyHighlight`
- **分类**：`BLOCK`
- **默认值**：`false`
- **命令**：`/ec FurnaceDirtyHighlight`（开关）；**无** `/ec c` 子命令
- **配置类**：`FurnaceDirtyHighlightConfig`（`config.json` 的 `FurnaceDirtyHighlight` 节）
  - `viewChunkRadius`：扫描半径（区块），默认 `0` = **跟随服务端视距**，夹在 `[0, 32]`
  - `scanIntervalTicks`：重扫间隔，默认 `20`，夹在 `[5, 100]`
- **触发**：`END_SERVER_TICK` 巡检（`FurnaceDirtyTracker#tick`）+ 玩家跨区块 / 换维度 + 规则变更
- **实现思路**：见 [深度剖析：脏熔炉高亮](../03-deep-dives/furnace-dirty.md)。摘要：
  1. **判定**（`FurnaceDirtyScanner`）：输入槽**非空**、但这只熔炉自己的配方表里**找不到能烧它的配方** → 脏。
     普通熔炉用 `SMELTING`、高炉用 `BLASTING`、烟熏炉用 `SMOKING`；**模组熔炉直接跳过**
     （配方不一定挂在这三个类型上，猜一个反而误报）。燃料槽不检测。
  2. **服务端维护全局脏表**（维度 → 脏熔炉坐标）+ **每个玩家当前已同步的视距区块集合**；
     玩家跨区块时对新进入视距的区块 `ADD`、对离开的 `REMOVE`，只把变化推给该玩家。
     摘除没人看的区块（prune）必须排在视距记录更新**之后**，这个顺序是刻意的，
     见[深度剖析：脏熔炉高亮](../03-deep-dives/furnace-dirty.md)。
  3. **周期性重扫**当前被视距覆盖的区块，发现状态变化就只推给"视距覆盖该坐标"的玩家。
  4. 好处：客户端缓存的坐标上限 ≈ `视距区块数 × 每区块脏熔炉数`，**玩家飞多远都不会涨**。
- **关键文件**：`handler/block/furnaceDirty/{FurnaceDirtyTracker,FurnaceDirtyScanner}.java`、
  `config/impl/section/item/FurnaceDirtyHighlightConfig.java`、
  `network/sync/clientbound/FurnaceDirtySyncChannel.java`、`network/sync/event/FurnaceDirtySyncEvents.java`
- **依赖**：`FurnaceDirtySyncChannel`（增量推送）、`scanIntervalTicks` / `viewChunkRadius`（节流与视距）
- **客户端行为**：维护一份"维度 → 脏熔炉坐标"的表，在世界里给这些熔炉画高亮框；
  规则关掉时清空表
- **设计约定与边界**：
  1. **配方判定用的是服务端配方表**，数据包重载后**不会立刻重扫**，要等下一次巡检周期。
  2. 只认**原版三种**熔炉方块实体类型，模组熔炉永远不高亮（这是刻意的，见上）。
  3. 输入槽为空、燃料槽是垃圾 —— **不会高亮**（规则名里的"脏"专指输入槽）。
