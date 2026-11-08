# Entities (ENTITY)

The seven entity rules: squeeze protection, blocking mob creation, nether-portal piglin spawns, instant zombified-villager cures, and the piglin barter rules.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> This file covers the **7** rules whose primary category in `RuleType` is `ENTITY`.
> Two of them belong to other categories as well (`NoSqueeze` is also `VILLAGER`, `ZombieVillagerHelp` is also
> `VILLAGER` + `MONSTER`), and their main text lives here; `MobNoSpawn` / `PortalPiglinNoSpawn` additionally carry the
> `COMMAND` marker (= they have an `/ec c` subcommand).
>
> The three "list-config rules" (`NoSqueeze` / `MobNoSpawn` / `PortalPiglinNoSpawn`) share one command shape, explained
> once here and not repeated below:
> - list rules: `/ec c <rule> <id> true|false`, `/ec c <rule> reset`, `/ec c <rule> show` (**all hard-wired to OWNERS**)
> - region rules: `/ec c <rule> bind|show|view|delete|clear`, entries are referred to by `#ID`

---

#### `NoSqueeze` (no squeezing)

- **Register name**: `NoSqueeze` (enum constant `VILLAGER_NO_SQUEEZE`)
- **Category**: `ENTITY` + `VILLAGER`
- **Default value**: `false`
- **Commands**: `/ec NoSqueeze` (toggle); the list is edited with `/ec c NoSqueeze <entity id> true|false` / `reset` / `show`
- **Config class**: `NoSqueezeConfig` (the `NoSqueeze` section of `villagerdetails/config.json`, holding the entity id list)
- **Trigger**: three injection points — `Entity#isPushable`, `Entity#push(Entity)`, `LivingEntity#hurtServer`
- **How it works**: one shared decision `SqueezeHandler.isProtected(entity)` (**rule enabled + the entity type is in the
  list**), consumed in three places:
  1. `isPushable` returns `false` directly — entities in the list are not pushed by others;
  2. `push(Entity)` is cancelled wholesale — if **either side** is in the list no push happens (a protected entity does
     not push others, and is not pushed by them either);
     note that only this overload counts: `push(Vec3)` / `push(DDD)` are the **knockback** paths (explosions, the ender
     dragon, ravager roars), unrelated to squeezing, and still work as usual;
  3. `hurtServer` blocks squeeze damage by **damage type id**: `cramming`, `in_wall`, `cactus`, `sweet_berry_bush`,
     `falling_block`, `falling_stalactite`, `fly_into_wall`. Using id strings instead of type constants means a rename
     between versions cannot silently disable it.
- **Key files**: `handler/entity/noSqueeze/SqueezeHandler.java`, `mixin/entity/noSqueeze/{EntityMixin,LivingEntityMixin}.java`,
  `config/impl/section/item/NoSqueezeConfig.java`, `command/c/impl/rule/rule/NoSqueezeServerImpl.java`
- **Dependencies**: `NoSqueezeConfig` (the list) + the `IdSetConfig` base class; the client gets a mirror of the list through `BlockRuleSyncChannel`
- **Client behaviour**: the settings screen renders the list as an "entity id → yes/no" list page; changes are sent back to the server
- **Design rules and boundaries**: in old saves the list used to live **inside the rule state string** (not in a config
  section); `NoSqueezeConfig.reload` migrates those entries into the config section and turns the rule on once on the
  side. This is the only rule with a "state → config" migration.

---

#### `MobNoSpawn` (block spawning)

- **Register name**: `MobNoSpawn` (enum constant `MOB_NO_SPAWN`)
- **Category**: `ENTITY` + `COMMAND`
- **Default value**: `false`
- **Commands**: `/ec MobNoSpawn` (toggle); the list is edited with `/ec c MobNoSpawn <entity id> true|false` / `reset` / `show`
- **Config class**: `MobNoSpawnConfig` (the `MobNoSpawn` section of `config.json`)
- **Trigger**: `EntityType#create(Level, EntitySpawnRequest)` — **the innermost layer of every spawn path**
- **How it works**:
  1. The decision is `NoSpawnHandler.isBlocked(type, reason)`: **the creation reason comes first**, and only reasons that
     really count as "spawning" continue to the rule switch and the list.
  2. The block works by turning the return value of `create` into `null`. Vanilla `create` may legitimately return `null`
     anyway (`canSpawn` said no), so no extra fallback is needed — the caller simply gets no entity.
  3. **Three reasons must be let through**, otherwise existing entities would be hurt:
     `LOAD` (chunk loading / client-side rebuild), `DIMENSION_TRAVEL` (going through a portal to another dimension),
     `CONVERSION` (mob conversion, e.g. curing a zombie villager) — blocking those produces "the old entity is dropped
     while the new one is never created", i.e. a mob deleted out of thin air.
  4. In addition, `NaturalSpawner#getMobForSpawn` returns `null` early: after vanilla receives `null` it logs a
     `Can't spawn entity of type: …` WARN, so putting naturally spawning mobs such as zombies / skeletons on the list
     would flood the log. The return value is exactly vanilla's "creation failed", so behaviour is unchanged — only the
     spam stops.
- **Key files**: `handler/entity/noSpawn/NoSpawnHandler.java`, `mixin/entity/noSpawn/{EntityTypeMixin,NaturalSpawnerMixin}.java`,
  `config/impl/section/item/MobNoSpawnConfig.java`, `command/c/impl/rule/rule/MobNoSpawnServerImpl.java`
- **Dependencies**: `MobNoSpawnConfig` + `IdSetConfig`
- **Client behaviour**: a list page (same as `NoSqueeze`)
- **Design rules and boundaries**: the rule is named "NoSpawn" but it blocks **creation**, so `/summon`, spawn eggs,
  spawners, breeding, raids and structure generation are all blocked too (they all go through `create`). Only the
  load / cross-dimension / conversion reasons are let through. Mobs that already exist are unaffected — blocking
  creation never removes what is already in the world.

---

#### `PortalPiglinNoSpawn` (no zombified piglins from nether portals)

- **Register name**: `PortalPiglinNoSpawn`
- **Category**: `ENTITY` + `COMMAND`
- **Default value**: `false`
- **Commands**: `/ec PortalPiglinNoSpawn` (toggle); `/ec c PortalPiglinNoSpawn bind|show|view|delete|clear`
  (**no `set`**: this is a single-segment binding, one BB selection is bound directly)
- **Config class**: `PortalPiglinNoSpawnConfig` (the `PortalPiglinNoSpawn` section of `config.json`, the region table: dimension + two corners + `#ID`)
- **Trigger**: `NetherPortalBlock#randomTick` (HEAD, cancellable)
- **How it works**:
  1. Vanilla does exactly one thing in that random tick: when the dimension attribute `nether_portal_spawns_piglin` is
     true, the difficulty is not peaceful and a player is nearby, it spawns a zombified piglin from the bottom of the
     portal with probability `1/2000 × difficulty` (on by default in the overworld, off in the nether). Cancelling the
     whole block does **not** affect teleporting, portal shape or animation — those live in other methods.
  2. The decision is `PortalPiglinNoSpawnHandler.isBlocked(level, pos)` = rule enabled + the dimension / coordinates of
     the portal block fall inside a bound region.
  3. **Binding does not inspect the selection's contents**: whether the region contains a portal, and how large it is,
     are never checked — whatever you select is what gets stored. A region with no portal in it today is fine: portals
     built there later are governed as well.
  4. Binding the same region again returns "already exists" and **tells you the existing `#ID`** (region `equals` does
     not compare the ID).
- **Key files**: `handler/entity/piglinNoSpawn/PortalPiglinNoSpawnHandler.java`, `mixin/block/portalPiglinNoSpawn/NetherPortalSpawnMixin.java`,
  `config/impl/section/item/PortalPiglinNoSpawnConfig.java`, `command/c/impl/rule/region/PortalPiglinNoSpawnServerImpl.java`
- **Dependencies**:
  - Selection: `ListenerType.BLOCK_RANGE_SELECTION` → the selection tool `/ec c selection pos1|pos2|show|clear` (or the wooden axe's left/right click)
  - Cache: `BBSectionStateCache` (the selection), `PortalSelectionCache` (the portal-binding side, not used by this rule)
  - Client: `PortalViewSyncChannel` pushes the highlight (the `view` subcommand)
- **Client behaviour**: with `view <id>` the client caches this region per dimension and draws a box in the world (the colours follow the two portal highlight settings)
- **Design rules and boundaries**:
  1. The gate of this rule is the rule's **enabled state** (plus a region hit); the rule tier only decides who may run
     the `/ec c` command, it plays no part in "block or not".
  2. `view` reuses the highlight channel of **portal binding**, so the box it draws looks exactly like a `PortalBinding`
     region and you cannot tell "piglin region or portal binding" from it — only the command echo distinguishes them.

---

#### `ZombieVillagerHelp` (instant zombified-villager cure)

- **Register name**: `ZombieVillagerHelp` (enum constant `ZOMBIE_VILLAGER_HELP`)
- **Category**: `VILLAGER` + `MONSTER`
- **Default value**: `false`
- **Commands**: `/ec ZombieVillagerHelp` (toggle); **no** `/ec c` subcommand
- **Config class**: none
- **Trigger**: a name tag applied to a **zombie villager** (the RETURN of `interactLivingEntity` in `NameTagItemMixin`,
  and only when vanilla naming succeeded and on the server side)
- **How it works**:
  1. The name must be `help` (case-insensitive, surrounding whitespace ignored); every other name passes through
     untouched, with no prompt at all.
  2. The entity's custom name is cleared first (after the cure it is a new entity, and the name should not follow it),
     then `ZombieVillagerInvoker#villagerdetails$startConverting(playerUUID, 0)` is called —
     this sets the **remaining cure time of this zombie villager to 0**, and on the next tick vanilla's own `tick()`
     runs `finishConversion`.
  3. So the cure sound, the retention of profession / trades / reputation / experience, and the "zombie doctor" advancement
     are **exactly** those of a golden-apple cure; the `2401 + random.nextInt(3600)` duration of the vanilla golden-apple
     path is not touched at all, and other zombie villagers still cure slowly
     (the duration is an **entity instance field**, so passing 0 only affects the one that got `help`).
- **Key files**: `handler/monster/zombieVillager/ZombieVillagerCureHandler.java`,
  `mixin/monster/zombieVillager/ZombieVillagerInvoker.java` (`@Invoker`, only borrows the entry point, never changes the
  method body), `mixin/entity/villager/trader/autoTrader/trigger/NameTagItemMixin.java`
- **Dependencies**: the same name-tag injection point is the **entry of two rules** (zombie villagers take `help`,
  ordinary villagers take the trade refresh); the branches never interfere (`ZombieVillager` is not a subclass of `Villager`)
- **Client behaviour**: none (purely server side; the client only sees the cure animation / sound)
- **Design rules and boundaries**: when the rule is off, applying `help` answers with a
  `msg.zombie_villager.help_rule_disabled` prompt and **the name is not cleared** — turn the rule on and apply the same
  name again and it takes effect.

---

#### `PiglinGoldBlockMultiply` (piglin gold-block barter)

- **Register name**: `PiglinGoldBlockMultiply`
- **Category**: `ENTITY`
- **Default value**: `false`
- **Commands**: `/ec PiglinGoldBlockMultiply` (toggle); **no** `/ec c` subcommand
- **Config class**: none (the details are the next two rules)
- **Trigger**: `PiglinAi#pickUpItem` / `PiglinAi#getBarterResponseItems` / `PiglinAi#admireGoldItem`
  — **one mixin with three jobs**, tied together by one entity command tag (`VillagerDetailsGoldBlockMultiply`)
- **How it works**:
  1. **Replaced on pickup**: when a piglin picks up a gold block, the item stack on the ground is **swapped on the spot
     for the same number of gold ingots**, and the piglin is tagged. Every later piece of logic (attraction, admiring,
     output) therefore believes it is handling gold ingots, and no other vanilla branch needs changing.
  2. **Output ×9**: when `getBarterResponseItems` returns, if this piglin carries the tag → drop the tag first, then run
     the vanilla loot table **8 more times independently** and merge the results with the first. So 1 gold block = the
     output of 9 vanilla barters, each of them an independent roll.
     There is a **re-entrancy guard** (`ThreadLocal`) inside, otherwise calling the original method from the injection
     would recurse forever.
  3. **Wait time**: vanilla admires a gold ingot for a fixed 119 ticks; here it is computed from the configured seconds,
     and gold-block trades multiply it by the punishment factor (see the next two rules).
- **Key files**: `mixin/entity/piglinTrade/PiglinAiMixin.java`
- **Dependencies**: `PiglinBarterDelay` (the base number of seconds to wait), `PiglinGoldBlockMultiplyPunishment` (the wait multiplier for gold-block trades)
- **Client behaviour**: none. The client only sees "drop 1 gold block, 9 stacks of loot come out"
- **Design rules and boundaries**: the tag is consumed at **production** time. If the piglin dies / is converted into a
  zombified piglin between pickup and production, the tag disappears with the entity and is never left on another one;
  but if it picked up the gold block and was then interrupted (say a player leashed it away), the next production is ×9
  as well — the tag means "the next output is ×9", not "the gold-block one is ×9".

---

#### `PiglinBarterDelay` (piglin barter delay)

- **Register name**: `PiglinBarterDelay`
- **Category**: `ENTITY`
- **Default value**: `6` (seconds)
- **Allowed values**: `3` / `6` / `12` (**custom list**, any text can be written in)
- **Commands**: `/ec PiglinBarterDelay <seconds>`; **no** `/ec c` subcommand
- **Config class**: none
- **Trigger**: the HEAD of `PiglinAi#admireGoldItem` (`cancellable`, ordinary gold-ingot barters go through it too)
- **How it works**: takes over the duration of vanilla's "admire the gold ingot" wholesale:
  `base duration = seconds × 20 − 1` (minus 1 to match the semantics of vanilla's 119 ticks), multiplied by the punishment
  factor for gold-block trades, then written into the expiry of the `ADMIRING_ITEM` memory. The vanilla line is cancelled,
  so the two never fight.
- **Key files**: `mixin/entity/piglinTrade/PiglinAiMixin.java`
- **Dependencies**: `PiglinGoldBlockMultiplyPunishment` (only participates for gold-block trades)
- **Client behaviour**: none. All it looks like is "how long the piglin studies the gold ingot before the trade closes"
- **Design rules and boundaries**: there is no `stateValidator`, so `abc` / `0` / negative numbers are all accepted, and
  on the consuming side a `ParseUtils.toInt` failure **falls back to the default 6** (it neither clamps nor errors). So
  "I set it and nothing happened" most likely means the value was never parsed at all.

---

#### `PiglinGoldBlockMultiplyPunishment` (gold-block trade punishment)

- **Register name**: `PiglinGoldBlockMultiplyPunishment` (enum constant `PIGLIN_GOLD_BLOCK_MULTIPLY_PUNISHMENT`)
- **Category**: `ENTITY`
- **Default value**: `9`
- **Allowed values**: `1` / `3` / `9` (**custom list**)
- **Commands**: `/ec PiglinGoldBlockMultiplyPunishment <multiplier>`; **no** `/ec c` subcommand
- **Config class**: none
- **Trigger**: the same injection point as `PiglinBarterDelay`, **effective only for gold-block trades**
- **How it works**: the equivalent duration of a gold-block trade is `base duration × multiplier`
  (default `9 × 119 ≈ 1071 ticks ≈ 53 seconds`), i.e. "1 gold block buys 9 outputs but you wait 9× as long" — this is the
  balancing lever of `PiglinGoldBlockMultiply`. Ordinary gold-ingot trades are unaffected and always 1×.
- **Key files**: `mixin/entity/piglinTrade/PiglinAiMixin.java`
- **Dependencies**: `PiglinBarterDelay` (the multiplier applies to what it computes), `PiglinGoldBlockMultiply` (with gold-block trading off this rule has no effect at all)
- **Client behaviour**: none
- **Design rules and boundaries**:
  1. Its only effect is to **lengthen the wait**; it consumes no extra items and reduces no output.
  2. Same as `PiglinBarterDelay`: no validator, so an illegal value is rolled back to the default (9) by `ParseUtils.toInt`.

---

### Cross references

- Rule index: [`../01-rule-index.md`](../01-rule-index.md)
- `NoSqueeze` / `ZombieVillagerHelp` on the villager side: [`villager.md`](villager.md)
- The three trigger lines of the name tag (`help` / item enchant / `331a`·`331b`): [`../03-deep-dives/auto-trader.md`](../03-deep-dives/auto-trader.md)
- Region binding and the selection tool: [`../03-deep-dives/villager-binding.md`](../03-deep-dives/villager-binding.md)

---

<a id="中文"></a>

## 中文

> 本文件覆盖 `RuleType` 里主分类为 `ENTITY` 的 **7 条**规则。
> 其中两条也属于别的分类（`NoSqueeze` 同时是 `VILLAGER`，`ZombieVillagerHelp` 同时是 `VILLAGER` + `MONSTER`），
> 正文都写在这里；`MobNoSpawn` / `PortalPiglinNoSpawn` 额外带 `COMMAND` 标记（= 有 `/ec c` 子命令）。
>
> 三条"逐条配置类"规则（`NoSqueeze` / `MobNoSpawn` / `PortalPiglinNoSpawn`）的命令形态是统一的，
> 这里先说明一次，后面不再重复：
> - 名单类：`/ec c <规则> <id> true|false`、`/ec c <规则> reset`、`/ec c <规则> show`（**全部写死 OWNERS**）
> - 区域类：`/ec c <规则> bind|show|view|delete|clear`，条目按 `#ID` 指代

---

#### `NoSqueeze`（无挤压）

- **注册名**：`NoSqueeze`（枚举常量 `VILLAGER_NO_SQUEEZE`）
- **分类**：`ENTITY` + `VILLAGER`
- **默认值**：`false`
- **命令**：`/ec NoSqueeze`（开关）；名单用 `/ec c NoSqueeze <实体id> true|false` / `reset` / `show`
- **配置类**：`NoSqueezeConfig`（`villagerdetails/config.json` 的 `NoSqueeze` 节，存实体 id 名单）
- **触发**：三个注入点 —— `Entity#isPushable`、`Entity#push(Entity)`、`LivingEntity#hurtServer`
- **实现思路**：一条统一的判定 `SqueezeHandler.isProtected(entity)`（**规则开启 + 实体类型在名单里**），三处消费：
  1. `isPushable` 直接返回 `false` —— 名单里的实体不会被别人推；
  2. `push(Entity)` 整段取消 —— **任一方**在名单里就不发生推挤（保护实体不会去推别人，也不会被别人推）；
     注意只认这个重载：`push(Vec3)` / `push(DDD)` 是爆炸、末影龙、劫掠兽吼叫等**击退**路径，与挤压无关，仍照常生效；
  3. `hurtServer` 里按**伤害类型 id** 拦掉挤压伤害：`cramming`、`in_wall`、`cactus`、`sweet_berry_bush`、
     `falling_block`、`falling_stalactite`、`fly_into_wall`。用 id 字符串而不是类型常量，版本间改名也不会静默失效。
- **关键文件**：`handler/entity/noSqueeze/SqueezeHandler.java`、`mixin/entity/noSqueeze/{EntityMixin,LivingEntityMixin}.java`、
  `config/impl/section/item/NoSqueezeConfig.java`、`command/c/impl/rule/rule/NoSqueezeServerImpl.java`
- **依赖**：`NoSqueezeConfig`（名单）+ `IdSetConfig` 基类；客户端通过 `BlockRuleSyncChannel` 拿到名单镜像
- **客户端行为**：设置界面把名单渲染成"实体 id → 是/否"的列表页，改完发回服务端
- **设计约定与边界**：老存档的名单曾经**存在规则状态字符串里**（不是配置节），`NoSqueezeConfig.reload` 会把它们搬进配置节
  并顺手把规则打开一次；这是唯一一条带"状态 → 配置"迁移的规则。

---

#### `MobNoSpawn`（禁止生成）

- **注册名**：`MobNoSpawn`（枚举常量 `MOB_NO_SPAWN`）
- **分类**：`ENTITY` + `COMMAND`
- **默认值**：`false`
- **命令**：`/ec MobNoSpawn`（开关）；名单用 `/ec c MobNoSpawn <实体id> true|false` / `reset` / `show`
- **配置类**：`MobNoSpawnConfig`（`config.json` 的 `MobNoSpawn` 节）
- **触发**：`EntityType#create(Level, EntitySpawnRequest)` —— **所有生成路径的最内层**
- **实现思路**：
  1. 判定 `NoSpawnHandler.isBlocked(type, reason)`：**先看创建原因**，只有真正算"生成"的原因才继续判；再看规则开关与名单。
  2. 拦法是把 `create` 的返回值改成 `null`。原版 `create` 本来就可能返回 `null`（`canSpawn` 不过），
     所以不需要额外兜底，调用方自然拿不到实体。
  3. **三种原因必须放行**，否则会伤到已经存在的实体：
     `LOAD`（区块加载 / 客户端重建）、`DIMENSION_TRAVEL`（过门换维度）、`CONVERSION`（生物转化，如僵尸村民治愈）——
     拦了会出现"旧实体被丢弃、新实体没建出来"，等于凭空删掉一只生物。
  4. 另外在 `NaturalSpawner#getMobForSpawn` 提前返回 `null`：原版拿到 `null` 后会打一条
     `Can't spawn entity of type: …` 的 WARN，名单里放僵尸 / 骷髅这类会自然生成的生物时日志会被刷爆。
     返回值与原版"创建失败"完全一致，行为不变，只是不再刷屏。
- **关键文件**：`handler/entity/noSpawn/NoSpawnHandler.java`、`mixin/entity/noSpawn/{EntityTypeMixin,NaturalSpawnerMixin}.java`、
  `config/impl/section/item/MobNoSpawnConfig.java`、`command/c/impl/rule/rule/MobNoSpawnServerImpl.java`
- **依赖**：`MobNoSpawnConfig` + `IdSetConfig`
- **客户端行为**：名单列表页（同 `NoSqueeze`）
- **设计约定与边界**：名字叫 "NoSpawn"，但拦的是**创建**：`/summon`、刷怪蛋、刷怪笼、繁殖、袭击、结构生成全都走
  `create`，所以全被拦；只有加载 / 跨维度 / 转化三种原因放行。**已经存在的生物不受影响** —— 拦创建不会删掉世界里已有的东西。

---

#### `PortalPiglinNoSpawn`（禁用地狱门刷僵尸猪灵）

- **注册名**：`PortalPiglinNoSpawn`
- **分类**：`ENTITY` + `COMMAND`
- **默认值**：`false`
- **命令**：`/ec PortalPiglinNoSpawn`（开关）；`/ec c PortalPiglinNoSpawn bind|show|view|delete|clear`
  （**没有 `set`**：单段绑定，用一个 BB 选区直接 `bind`）
- **配置类**：`PortalPiglinNoSpawnConfig`（`config.json` 的 `PortalPiglinNoSpawn` 节，区域表：维度 + 两个角点 + `#ID`）
- **触发**：`NetherPortalBlock#randomTick`（HEAD，cancellable）
- **实现思路**：
  1. 原版这段随机刻只做一件事：维度属性 `nether_portal_spawns_piglin` 为真、难度不是和平、附近有玩家时，
     以 `1/2000 × 难度` 的概率从门底刷一只僵尸猪灵（主世界默认开、地狱默认关）。整段取消**不会**影响
     传送、门形状、动画 —— 那些都在别的方法里。
  2. 判定 `PortalPiglinNoSpawnHandler.isBlocked(level, pos)` = 规则开启 + 门方块所属维度/坐标落在某条绑定区域内。
  3. **绑定不检查选区内容**：区域内有没有门、区域多大，一概不看，选多大就存多大 ——
     现在区域里没门也没关系，之后建在里面的门一样受管。
  4. 重复 `bind` 同一块区域会返回"已存在"并**告诉你已有的 `#ID`**（区域 `equals` 不比较 ID）。
- **关键文件**：`handler/entity/piglinNoSpawn/PortalPiglinNoSpawnHandler.java`、`mixin/block/portalPiglinNoSpawn/NetherPortalSpawnMixin.java`、
  `config/impl/section/item/PortalPiglinNoSpawnConfig.java`、`command/c/impl/rule/region/PortalPiglinNoSpawnServerImpl.java`
- **依赖**：
  - 选区：`ListenerType.BLOCK_RANGE_SELECTION` → 选区工具 `/ec c selection pos1|pos2|show|clear`（或手持木斧左右键）
  - 缓存：`BBSectionStateCache`（选区）、`PortalSelectionCache`（传送门绑定侧的，不用于本规则）
  - 客户端：`PortalViewSyncChannel` 推高亮（`view` 子命令）
- **客户端行为**：`view <id>` 时客户端按维度缓存这条区域并在世界里画框（颜色沿用传送门高亮的两套配置）
- **设计约定与边界**：
  1. 这条规则的门禁就是规则自己的**开关**（外加区域命中）；规则档位只决定谁有资格执行 `/ec c` 命令，
     不参与"拦不拦"的判定。
  2. `view` 复用的是**传送门绑定**的高亮通道，所以画出来的框样式与 `PortalBinding` 完全一致，
     分不清"这是猪灵区域还是传送门绑定"——只能靠命令回显区分。

---

#### `ZombieVillagerHelp`（僵尸村民秒治愈）

- **注册名**：`ZombieVillagerHelp`（枚举常量 `ZOMBIE_VILLAGER_HELP`）
- **分类**：`VILLAGER` + `MONSTER`
- **默认值**：`false`
- **命令**：`/ec ZombieVillagerHelp`（开关）；**无** `/ec c` 子命令
- **配置类**：无
- **触发**：命名牌贴到**僵尸村民**上（`NameTagItemMixin` 的 `interactLivingEntity` RETURN，
  且只在原版命名成功、服务端侧执行）
- **实现思路**：
  1. 名字必须是 `help`（忽略大小写与首尾空白）；其它名字原样放行，不弹任何提示。
  2. 先把实体的自定义名字清掉（治愈后是新实体，名字不该跟着走），再调
     `ZombieVillagerInvoker#villagerdetails$startConverting(playerUUID, 0)` ——
     把这只僵尸村民**剩下的治愈时间设成 0**，下一个 tick 由原版自己的 `tick()` 走完 `finishConversion`。
  3. 所以治愈音效、职业 / 交易 / 声望 / 经验的保留、"僵尸医生"成就，全部和喂金苹果治好的效果**完全一致**；
     原版金苹果那条路的 `2401 + random.nextInt(3600)` 时长一个字没动，别的僵尸村民照旧慢慢治
     （时长是**实体实例字段**，传 0 只影响被贴了 `help` 的这一只）。
- **关键文件**：`handler/monster/zombieVillager/ZombieVillagerCureHandler.java`、
  `mixin/monster/zombieVillager/ZombieVillagerInvoker.java`（`@Invoker`，只借入口不改方法体）、
  `mixin/entity/villager/trader/autoTrader/trigger/NameTagItemMixin.java`
- **依赖**：同一个命名牌注入点是**两条规则的入口**（僵尸村民走 `help`，普通村民走刷新交易），
  分支互不干扰（`ZombieVillager` 不是 `Villager` 的子类）
- **客户端行为**：无（纯服务端；客户端只会看到治愈动画 / 音效）
- **设计约定与边界**：规则关闭时贴 `help` 会收到一条 `msg.zombie_villager.help_rule_disabled` 提示，
  **名字不会被清掉**——之后开规则再贴一次同样的名字即可生效。

---

#### `PiglinGoldBlockMultiply`（猪灵金块交易）

- **注册名**：`PiglinGoldBlockMultiply`
- **分类**：`ENTITY`
- **默认值**：`false`
- **命令**：`/ec PiglinGoldBlockMultiply`（开关）；**无** `/ec c` 子命令
- **配置类**：无（细项是下面两条规则）
- **触发**：`PiglinAi#pickUpItem` / `PiglinAi#getBarterResponseItems` / `PiglinAi#admireGoldItem`
  —— **一个 mixin 三个职责**，靠一个实体命令标签（`VillagerDetailsGoldBlockMultiply`）串起来
- **实现思路**：
  1. **拾取时替换**：猪灵捡到金块 → 地面那堆物品**当场换成等数量的金锭**，并给这只猪灵打上标签。
     所以后续所有逻辑（吸引、欣赏、产出）都以为自己在处理金锭，不需要改原版其它分支。
  2. **产出 ×9**：`getBarterResponseItems` 返回时，如果这只猪灵带着标签 → 先摘标签，再**独立跑 8 次**
     原版战利品表，和第一次的结果合并。也就是 1 个金块 = 原版 9 次交易的产出，且每次都是独立随机。
     内部有一层**重入保护**（`ThreadLocal`），否则在注入里调原方法会无限递归。
  3. **等待时长**：原版欣赏金锭固定 119 tick，这里改成按规则配的秒数算，金块交易再乘以惩罚倍数
     （见后两条）。
- **关键文件**：`mixin/entity/piglinTrade/PiglinAiMixin.java`
- **依赖**：`PiglinBarterDelay`（基础等待秒数）、`PiglinGoldBlockMultiplyPunishment`（金块交易的等待倍数）
- **客户端行为**：无。客户端只会看到"丢 1 个金块，掉出 9 份东西"
- **设计约定与边界**：标签在**产出时**消费掉。如果这只猪灵在拾取与产出之间死亡 / 被转化成僵尸猪灵，
  标签随实体一起消失，不会残留在别的实体上；但如果它捡了金块又被打断（例如被玩家拴走），
  下次产出的那次交易同样会 ×9 —— 标签是"下一笔产出 ×9"，不是"金块那一笔 ×9"。

---

#### `PiglinBarterDelay`（猪灵交易延迟）

- **注册名**：`PiglinBarterDelay`
- **分类**：`ENTITY`
- **默认值**：`6`（秒）
- **可选值**：`3` / `6` / `12`（**自定义列表**，任意文本都能写进来）
- **命令**：`/ec PiglinBarterDelay <秒>`；**无** `/ec c` 子命令
- **配置类**：无
- **触发**：`PiglinAi#admireGoldItem` 的 HEAD（`cancellable`，普通金锭交易也走这里）
- **实现思路**：整段接管原版"欣赏金锭"的时长：`基础时长 = 秒数 × 20 − 1`（减 1 对齐原版 119 tick 的语义），
  金块交易再乘惩罚倍数，最后写进 `ADMIRING_ITEM` 记忆的过期时间。原版那行代码被 cancel 掉，所以两者不会打架。
- **关键文件**：`mixin/entity/piglinTrade/PiglinAiMixin.java`
- **依赖**：`PiglinGoldBlockMultiplyPunishment`（只在金块交易时参与计算）
- **客户端行为**：无。表现只是"猪灵拿着金锭端详多久才成交"
- **设计约定与边界**：没有 `stateValidator`，写 `abc` / `0` / 负数都会被接受，消费侧 `ParseUtils.toInt` 解析失败时
  **回落到默认 6**（不是夹断、也不是报错）。所以"设了没效果"很可能是因为值压根没解析出来。

---

#### `PiglinGoldBlockMultiplyPunishment`（金块交易惩罚）

- **注册名**：`PiglinGoldBlockMultiplyPunishment`（枚举常量 `PIGLIN_GOLD_BLOCK_MULTIPLY_PUNISHMENT`）
- **分类**：`ENTITY`
- **默认值**：`9`
- **可选值**：`1` / `3` / `9`（**自定义列表**）
- **命令**：`/ec PiglinGoldBlockMultiplyPunishment <倍数>`；**无** `/ec c` 子命令
- **配置类**：无
- **触发**：与 `PiglinBarterDelay` 同一个注入点，**只在金块交易时生效**
- **实现思路**：金块交易的等值时长为 `基础时长 × 倍数`（默认 `9 × 119 ≈ 1071 tick ≈ 53 秒`），
  即"1 个金块换 9 份产出，但要多等 9 倍时间" —— 这是 `PiglinGoldBlockMultiply` 的平衡手段。
  普通金锭交易不受影响，永远是 1 倍。
- **关键文件**：`mixin/entity/piglinTrade/PiglinAiMixin.java`
- **依赖**：`PiglinBarterDelay`（乘数作用在它算出的基础上）、`PiglinGoldBlockMultiply`（不开关块交易则这条完全没有效果）
- **客户端行为**：无
- **设计约定与边界**：
  1. 它唯一的作用是**拉长等待时间**：不额外消耗物品，也不减少产出。
  2. 同 `PiglinBarterDelay`：没有校验器，非法值会被 `ParseUtils.toInt` 回落成默认值（9）。

---

### 交叉引用

- 规则总索引：[`../01-rule-index.md`](../01-rule-index.md)
- `NoSqueeze` / `ZombieVillagerHelp` 在村民侧的表现：[`villager.md`](villager.md)
- 命名牌的三条触发线（`help` / 物品附魔 / `331a`·`331b`）：[`../03-deep-dives/auto-trader.md`](../03-deep-dives/auto-trader.md)
- 区域绑定与选区工具：[`../03-deep-dives/villager-binding.md`](../03-deep-dives/villager-binding.md)
