# Business Overview

What every rule does, who may use it, and how a rule change travels through the system.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> **Audience**: developers who know Java / Fabric but have never touched this project's business logic.
> This document only covers **what each rule does and roughly how it is implemented**. How the framework
> itself is written (thread pools, config pipeline, session cache, the internals of the command and network
> frameworks) lives in `doc/framework/`; here we only give a one-line conclusion plus a link when needed.
>
> Suggested reading order: this chapter → [`01-rule-index.md`](01-rule-index.md) to find your rule →
> [`02-rules/`](02-rules/) for the details → [`03-deep-dives/`](03-deep-dives/) for complex rules.

---

### 1. What this mod does

**In one sentence**: it gives the villager / trading side of Minecraft a set of **independently switchable**
rules for the scenarios where **vanilla behaves badly**, plus a complete mechanism that lets the server owner
change rules live, take effect immediately, and push the result to the client UI.

The things players reach for most often:

| Scenario | Rule | In one line |
|---|---|---|
| Roll a librarian who sells Mending | `VillagerAutoTrader` | Name tag `mending`, right-click the villager, done in tens of seconds |
| Find out who on the server sells Mending | `TradeSearch` | `/ec c TradeSearch book mending`, in-memory index only, no server lag |
| Force two portals to link to each other | `PortalBinding` | Frame a region and bind it to the other portal instead of "nearest portal" |
| Change a villager's bed / workstation, or make it walk somewhere | `VillagerBedBinding` and two more | Name tags `bed` / `work` / `move` act as tools |
| Tune blocks / entities / piglins / happy ghasts | The remaining 30-odd rules | See the [rule index](01-rule-index.md) |
| Move fast without being rubber-banded | `GlobalSpeedBypass` | Only skips the `moved too quickly / moved wrongly` check for **the player's own movement** (elytra / modded flight / creative flight) and for **a Happy Ghast the player is riding and controlling** |

The mod adds no blocks, items or dimensions; almost every rule **rewrites a small piece of behaviour inside
one vanilla method**, and with the rule off it is completely invisible.

---

### 2. Where the code lives (three things you must know before reading on)

#### 2.1 Three source sets, not one big `main`

| Source set | Path | Contains |
|---|---|---|
| Shared by both sides | `common/src/main/java/com/villagerdetails/` | Mod id, payload types, shared utilities (`JsonIO`, `ParseUtils`, `ChannelScanner`), rule state validators, DTOs |
| **Server** (the main stage of this document) | `common/src/server/java/com/villagerdetails/` | Rule definitions, business handlers, mixins, events, commands, configs, caches |
| Client | `common/src/client/java/com/villagerdetails/` | Settings screen, rendering (highlight / dirty furnace / seat), client-side switches and caches |

> So it is **normal not to find `handler/` or `mixin/` under `common/src/main`** — they live in the `server` source set.

#### 2.2 There is also a version adapter layer

```
v26.2/src/server/java/com/villagerdetails/...   ← 26.2 only
v26.3/src/server/java/com/villagerdetails/...   ← 26.3 only (one extra SequenceFunctionMixin)
```

The two version layers use **exactly the same package and class names** (for example
`handler.entity.villager.trader.refresh.TradePoolChecker`); the build script picks one copy per version.
When this document mentions such a class it always writes the package path without a `v26.2/` prefix.

**Six key implementations exist only in the version layer** and cannot be found in `common`:

| Rule | Implementation in the version layer |
|---|---|
| `PortalBinding` / `PortalBindingEnhanced` | `mixin/block/portalBinding/EntityPortalMixin` |
| `VillagerBoneMealFlower` | `mixin/plant/GrassBlockBoneMealMixin` |
| `BlockCollectable` | `mixin/block/collectable/PlayerDestroyMixin` |
| `VillagerAutoTrader` | `TradePoolChecker` / `TradeSetAdapter` / `TradeEnchantmentScanner` / `mixin/.../VillagerTradeMixin` |
| `VillagerEmeraldDecompose` / `VillagerBookshelfDecompose` | `handler/entity/villager/trader/decompose/ItemProvisioner` |
| `BoxCrafting` | `handler/craft/BoxCraftPlan` |

#### 2.3 Seven drawers of business code

| Package | What it does | Where this document covers it |
|---|---|---|
| `rule/type/` | `RuleType` (the master rule list), categories, config kinds | This chapter + the [rule index](01-rule-index.md) |
| `handler/` | The business logic itself (39 classes) | `02-rules/*`, `03-deep-dives/*` |
| `mixin/` | Injection points into vanilla classes (40) | The "key files" field of each rule; inventory in [framework 08](../framework/08-mixin-inventory.md) |
| `event/impl/register/` | Fabric event wiring (auto-registered by package scanning) | The "trigger" field of each rule |
| `command/c/impl/` | The `/ec c <rule> ...` subcommand tree | The "command" field of each rule |
| `config/impl/` | The per-rule detail config sections | The "config class" field of each rule |
| `cache/impl/` | Session state that spans ticks or threads | The "dependencies" field of each rule + [`04-glossary.md`](04-glossary.md) |

---

### 3. What "one rule" is

A rule is **one constant in the `RuleType` enum**. The enum constructor takes 10 parameters; from a business
point of view only the following matter (parameter names and `Supplier` lazy loading are framework details —
see [framework 05](../framework/05-rule-framework.md)):

| Parameter | Business meaning | Example |
|---|---|---|
| `registerName` | **Register name**, the word you type in commands | `InfiniteShear` |
| `displayName` / `displayInfo` | Translation keys for the display name / description (translated server-side, then sent down) | `setting.infinite_shear` |
| `category` | Category, possibly **several** (used by `/ec list <category>`) | `[ANIMAL]` |
| `quickSwitches` | **All legal values** of this rule (single choice) | `["false","true"]` |
| `state` | **Default value** | `"false"` |
| `commandObject` | Whether `/ec c <rule>` exists; `null` = plain switch | `null` |
| `configKind` | Whether this is a "list-config rule" (keeps a name list or value table besides the switch) | `RuleConfigKind.NONE` |
| `listenerType` | Which selection tool it binds to (region binding / entity + block binding) | `[]` |
| `reloadableConfig` | The detail config class; `null` = no extra config section | → `NoSqueezeConfig` |
| `stateValidator` | Format / range validator for the state value; `null` = no validation | `numericRange(0, 20)` |

**There are currently 40 rules.** The enum is the single source of truth: run
`RuleType.values()` (or `/ec list`) whenever you need the exact number.

---

### 4. The four pillars of the rule system

```
                    ┌──────────── player / server owner ──────────────┐
                    │  chat command /ec ...        client settings UI │
                    └───────────────┬─────────────────────────────────┘
                                    │ both are "change a rule"
                          ┌─────────▼─────────┐
                          │ RuleChangeService │  the only entry: validate, then write
                          └─────────┬─────────┘
                                    │
              ┌─────────────────────┼──────────────────────┐
              ▼                     ▼                      ▼
      ┌──────────────┐      ┌──────────────┐      ┌───────────────┐
      │   RuleCache  │      │ persist (two)│      │ callbacks +   │
      │ state in mem │      │ state / conf │      │ listeners     │
      └──────┬───────┘      └──────────────┘      └───────────────┘
             │ read
   ┌─────────┴─────────────────────────────────────────────┐
   ▼             ▼              ▼              ▼           ▼
 handler     mixin      event callbacks   network sync  tab completion / perms
```

#### 4.1 Categories

`RuleCategoryType` has 10 values: `MOD_SETTING`, `BLOCK`, `PLANT`, `ENTITY`, `VILLAGER`, `MONSTER`,
`ANIMAL`, `PLAYER`, `COMMAND`, `FIX`.

- A rule can belong to **several categories at once** (`/ec list entity` and `/ec list villager` both list `NoSqueeze`).
- `COMMAND` is not a functional domain: it exists so that `/ec list command` can show **every rule that has subcommands** in one go.
- The category name in `/ec list <category>` is **tolerant**: case-insensitive, and a trailing `s` is accepted (`players` → `player`).

#### 4.2 Switches and state values

**The state is just a string** — there is no such thing as a "boolean rule" or a "numeric rule":

| Shape | Values | When is it "on" | Example |
|---|---|---|---|
| On / off | `false` / `true` | Anything that is not `false` counts as on | `InfiniteShear` |
| Permission tier | `false` / `true` / `ops` / `master` / `admin` / `owner` | Same; the tier also decides **who may trigger it** | `PortalBinding` |
| Permission only (cannot be off) | `true` / `ops` / `master` / `admin` / `owner` | Always on | `RuleCommand` (the `/ec` master permission) |
| Numeric / text values | `50` / `100` / `500` / `1000`, `3` / `6` / `12`, language codes | Same | `VillagerOneTickTraderCount`, `PiglinBarterDelay` |

Two things that are easy to get wrong:

1. **`isEnabled` only checks "is it `false`"**. So a numeric-value rule (`VillagerHardWorking = 1`) is
   **always on**; to disable it you must set `false` explicitly (consumers then fall back to "vanilla logic").
2. **As soon as one value in `quickSwitches` is not a switch word, the whole rule is treated as a "custom list"**
   and `/ec <rule> <any text>` is accepted. The classic case is `Language` (its candidates are language codes).
   Such rules rely on their own `stateValidator` (currently only `HappyGhastSpeed` registers one).

Two validation gates apply whenever a rule changes — commands and the UI share the same code path, so there is
no "the command blocks it but the UI lets it through":

```
input → canonicalize (reject empty / match the predefined list / pass a custom list through)
      → stateValidator (format and range)
      → only then written into RuleCache (memory + persist + callbacks)
```

#### 4.3 Permissions (two layers, do not confuse them)

| Layer | Controls | Decided by | Can it be bypassed |
|---|---|---|---|
| **Layer 1**: `/ec` master permission | Whether you may use `/ec`, see / change rules, and have the client UI fetch data | The `RuleCommand` rule state tier + permission node `villagerdetails:c.base` | Setting the state to `true` makes it available to everyone |
| **Layer 2**: each rule's own tier | Whether `/ec c <rule> ...` may run; also required when the rule applies to a player | State tier + permission node `villagerdetails:c.<registerName lowercase>` | List-config rules are **hard-wired to owner**, ignoring their own tier |
| **Tool layer**: `/ec c reload` | Re-read configs from disk | Hard-wired `PERM_BASE` + `OWNERS` | No |

One-line mnemonic: **the tier is both the switch and "who may trigger it"**. With `PortalBinding` set to `ops`,
a normal player walking into a bound region still goes through the vanilla portal; only OP and above get the
forced teleport.

A permission detail that is easy to misread, and is **by design**: **`/ec c <rule> ...` only passes layer 2,
never the `RuleCommand` master switch** (`CCommand` attaches subcommands with the rule's own `requirement`
only). Therefore a rule whose tier is `true` and which is not a list-config rule also lets an ordinary player
run its `/ec c` subcommands. `/ec` itself, `/ec list ...` and `/ec <rule> [state]` all sit behind layer 1.

#### 4.4 Configuration (four files, two paths)

| File (inside the world save) | What it stores | Who writes it |
|---|---|---|
| `villager_details_binding.json` | **Rule state**: `registerName → state string` (values equal to the default are not written) | `RuleCache.setState` → `RuleSwitchConfig` |
| `villagerdetails/config.json` | **Per-rule details**: `section name → JsonObject` (`NoSqueeze`, `PortalBinding`, `PlayerHeadSitting`, `FurnaceDirtyHighlight`, …) | Each rule's config class calls `markDirty()` and the tick loop flushes it |
| `villagerdetails/trade_index.json` | The **alias table** for items / enchantments (`绿宝石` / `emerald` → `minecraft:emerald`), used by tab completion and name-tag parsing | Built-in data, editable by players |
| `villagerdetails/villager_trade_cache.json` | The **trade search index** (the on-disk part of `TradeSearch`, format version 2) | `VillagerTradeCache` marks dirty, then flushes every 200 ticks and on shutdown |

> To edit configs by hand: change them, then run `/ec c reload` (with an argument it re-reads only that file),
> or restart the server. Rule state and per-rule details are **two different files**; change rule state with
> the `/ec` commands rather than editing `binding.json` without reloading afterwards.

---

### 5. What actually happens when a rule changes

```
/ec <rule> <state>   ──┐
client UI button     ──┴──► RuleChangeService.change
                             │
                             ├─ ① canonicalize: empty / not in the candidate list → red text, nothing written
                             ├─ ② stateValidator: bad format / range → nothing written either
                             ├─ ③ RuleCache.setState
                             │     ├─ value unchanged → return early (callbacks do NOT fire either)
                             │     ├─ write memory
                             │     ├─ persist → RuleSwitchConfig (dirty flag, flushed later)
                             │     └─ fire RuleCallbacks + RuleCache listeners
                             └─ ④ per-rule side effects (immediate)
                                   · happy ghast speed: recompute ridden happy ghasts
                                   · trade search: on→load the disk index; off→flush first, then clear memory
                                   · dirty furnace: clear / start scanning
                                   · client: broadcast the new state / descriptors
```

Two ordering semantics **you must remember**:

1. **`setState` short-circuits when the value does not change**, and no callback fires. So "set the rule to its
   current value" looks successful but has no side effects at all — which is why `ServerLangConfig.reload` does
   not rely on the language callback alone (see [`02-rules/settings.md`](02-rules/settings.md)).
2. **Persisting is asynchronous and lagging, but side effects are synchronous.** A rule takes effect the moment
   it is changed; the disk catches up a bit later.

---

### 6. How rules hook into vanilla (three wiring styles)

| Style | Used for | Example |
|---|---|---|
| **Mixin injection** | Rewriting a piece of behaviour inside a vanilla method | `SheepMixin` (regrow after shearing), `MerchantOfferMixin` (`increaseUses` simply cancelled) |
| **Fabric events** | Vanilla already has the callback, so no injection | `ServerLivingEntityEvents.AFTER_DEATH` (drop the index when a villager dies), `ServerTickEvents.END_SERVER_TICK` (frame-sliced refresh) |
| **Periodic tick sweep** | State may be corrupted elsewhere and needs a periodic safety net | `HappyGhastSpeedHandler.tick` (roll back leftover speed every 20 ticks), `FurnaceDirtyScanner` |

Event registration **has no manifest**: every class under `event/impl/register` (including sub-packages) that
implements `ServerEventRegisterServer` gets its `register()` called by the package scanner. Adding a group of
events = adding one class, with no registration code to touch.
(Scanning mechanics: [framework 04](../framework/04-session-state.md) and [framework 01](../framework/01-threading.md).)

There are 40 mixins, in four groups:

1. **Behaviour interception / rewrite** (the majority): `@Inject` + `cancellable`, or `@Redirect` to swap a call;
2. **`@Accessor` / `@Invoker`**: expose vanilla private fields / methods to business code
   (`AbstractVillagerInterfaceMixin` reads and writes `offers`, `ZombieVillagerInvoker` runs the curing routine);
3. **`@Shadow`-style**: to call the original method from inside an injection (see `PiglinAiMixin`);
4. **Version-difference patches**: one copy each in `v26.2/` and `v26.3/` (see 2.2).

---

### 7. When work leaves the main thread

Only three things are asynchronous, and each has a clear "not doing so would stall the tick" reason:

| Work | Why it must be async | Details |
|---|---|---|
| Writing the trade index | Capturing a snapshot deep-copies a whole trade table; flushing serialises tens of thousands of entries | Business: [`trade-search.md`](03-deep-dives/trade-search.md); mechanics: [framework 01](../framework/01-threading.md) |
| Matching trade queries | Iterates every villager on the server × every offer, tens of milliseconds | Same as above |
| Flushing configs | Atomic file writes must not stall the tick | [framework 02](../framework/02-config-pipeline.md) |

**Trade refresh (`VillagerAutoTrader`) is not in the async pools** — it is **frame-sliced** on the main thread
with a fixed budget per tick. The reason is simple: the simulation reads villager entities, mutates entity
fields and sends packets, and all of that must happen on the main thread.
Details in [`auto-trader.md`](03-deep-dives/auto-trader.md).

---

### 8. Where the client participates

The server is the only source of truth; the client does three things: **display**, **collect the player's
intent**, and **draw highlights**.

| Capability | What the client does | Channel (`network/sync/clientbound` / `receiver`) |
|---|---|---|
| Settings UI | Renders the rule list / details / option buttons; sends changes back to the server | `RuleDescriptorSyncChannel`, `RuleDataOptionsChannel` / `RuleDataReceiver`, `ServerRuleSetReceiver` |
| "Sit on blocks" switch | The player's local intent (players without the mod count as "on") | `PlayerBlockSittingEnabledReceiver` |
| "Allow others on my head" | A switch owned by the target player | `PlayerHeadSittingAllowReceiver` |
| Selection highlight | Highlight the selected entities / blocks / regions | `EBSyncChannel`, `BBSyncChannel` / `EBClearReceiver`, `BBClearReceiver` |
| Portal view | Highlight the portal coordinates on both sides of a binding | `PortalViewSyncChannel` / `PortalViewClearReceiver` |
| Dirty furnace highlight | Maintain a coordinate table from the "view-distance chunk delta" and draw boxes | `FurnaceDirtySyncChannel` |
| Villager bed / workstation markers | Highlight the binding relationships | `VillagerBedSyncChannel`, `WorkstationSyncChannel` |
| Completion data | The server precomputes which trade items / enchantments can appear in this save; tab completion answers through the vanilla completion packet (no custom channel) | See [`tab-completion.md`](03-deep-dives/tab-completion.md) |

**Language is split, and you should know it**: `/ec` command replies use the **server language** (decided by
the `Language` rule), while text in the settings UI uses **the client's own GUI language** (the client reports
its language, the server translates into it and sends the strings down). So an admin on a Chinese server with
an English client sees "command replies in Chinese, UI in English".

---

### 9. Design rules and boundaries

These are deliberate behaviours, not open items. Read them as the contract the mod upholds.

| # | Observation | Design rule |
|---|---|---|
| 1 | Rule count | 40. `RuleType.values()` is the only source of truth; every doc, command listing and self-check derives from it |
| 2 | `/ec c PortalBindingEnhanced` does not exist | **One rule, one command object.** Both portal-binding rules would otherwise register the same subcommand literal; Brigadier silently merges duplicates and drops the permission predicate, so `PortalBindingEnhanced` deliberately has no command. Toggle it with `/ec PortalBindingEnhanced <state>` |
| 3 | `/ec Language <anything>` succeeds | `Language`'s candidates are language codes, so the rule is classified as a custom list and arbitrary text passes. An invalid code makes server replies fall back to raw translation keys — always use a code the client bundle actually ships |
| 4 | A list-config rule set to `true` still refuses ordinary players | By design: for `RuleConfigKind.LIST` the `/ec c` subcommand is hard-wired to owner, because it edits persistent name lists |
| 5 | `/ec c <rule> ...` skips the `/ec` master switch | By design: layer 1 gates "manage rules", layer 2 gates "trigger this rule". A rule with tier `true` is meant to be usable by everyone |
| 6 | `EntityPortalMixin` / `GrassBlockBoneMealMixin` / `ItemProvisioner` cannot be found in `common` | They live in the version layers (`v26.2/`, `v26.3/`); see 2.2 |
| 7 | `TradeSearch` misses a fresh trade while the chunk never unloads | Indexing is event-driven: chunk unload, trade-screen open (100-tick debounce) and explicit refresh. A villager that never unloads is picked up at the next capture, never by a global sweep |
| 8 | Setting a rule to its current value gives no feedback | `setState` short-circuits and no callback fires (see section 5). Use the business refresh path when you need a forced re-apply |
| 9 | Command replies and UI text can be in different languages | Server language vs client GUI language are two independent settings (see section 8) |

---

### 10. Document map

| File | Contents |
|---|---|
| [`01-rule-index.md`](01-rule-index.md) | Index of all 40 rules (register name / categories / default / one-liner) |
| [`02-rules/`](02-rules/) | One file per category: `settings` `fix` `entity` `monster` `plant` `animal` `player` `villager` `block` |
| [`03-deep-dives/`](03-deep-dives/) | Seven deep dives: `auto-trader` `trade-search` `portal-binding` `tab-completion` `villager-binding` `furnace-dirty` `player-sitting` |
| [`04-glossary.md`](04-glossary.md) | Business glossary (paired terracotta, 331a/331b, locked trade, snapshot, …) |

**The boundary with the framework docs** (one-line principle):

- "Why does the player see A instead of B" → **business docs** (this chapter and the ones below).
- "Why is the code written this way instead of that way" → **framework docs** ([`doc/framework/`](../framework/README.md)).

For example: this mod reads switches through `RuleCache.isEnabled(...)` — that is the business view.
How `RuleCache` routes permission nodes, persists, and notifies listeners — that is [framework 05](../framework/05-rule-framework.md).

---

<a id="中文"></a>

## 中文

> 目标读者：熟悉 Java / Fabric，但没接触过本项目业务的接手开发者。
> 本文只讲**每条规则干什么、大致怎么实现**。框架层怎么写的（线程池、配置管线、会话缓存、
> 命令 / 网络框架的内部机制）在 `doc/framework/`，本文只在需要时给一句结论 + 链接。
>
> 阅读顺序建议：本篇 → [`01-rule-index.md`](01-rule-index.md) 找你要的规则 →
> [`02-rules/`](02-rules/) 看细节 → 复杂规则再进 [`03-deep-dives/`](03-deep-dives/)。

---

### 1. 这个模组做什么

**一句话**：给 Minecraft 里「村民 / 交易」这条线上那些**原版体验极差**的场景提供一堆**可单独开关**的规则，
再加一整套让服主在线改规则、立刻生效、并同步给客户端界面的机制。

玩家视角最常用的几件事：

| 场景 | 对应规则 | 一句话 |
|---|---|---|
| 刷出「能换经验修补的图书管理员」 | `VillagerAutoTrader` | 命名牌写 `mending`，右键村民，几十秒内刷出来 |
| 查「全服谁卖经验修补」 | `TradeSearch` | `/ec c TradeSearch book mending`，只查内存索引，不卡服 |
| 让两扇门强制互传 | `PortalBinding` | 框一块区域绑到另一扇门，不走「最近的传送门」 |
| 改村民的床 / 工作方块 / 让它走过去 | `VillagerBedBinding` 等三条 | 命名牌写 `bed` / `work` / `move` 当工具用 |
| 调方块 / 实体 / 猪灵 / 乐魂的细节 | 其余 30 多条 | 见 [规则总索引](01-rule-index.md) |
| 调高移速后不被拉回 | `GlobalSpeedBypass` | 只放行**玩家自身移动**（鞘翅 / 模组飞行器 / 创造飞行）与**玩家正骑乘并操控的乐魂**的位移校验，关掉的仅是原版 `moved too quickly / moved wrongly` 判定 |

模组本体没有新增方块、物品或维度；绝大多数规则是**改写原版某个方法的一小段行为**，
关掉即完全无感。

---

### 2. 代码在哪（读本文档前必须知道的三件事）

#### 2.1 三个源集，不是一个大 `main`

| 源集 | 路径 | 装什么 |
|---|---|---|
| 两端共用 | `common/src/main/java/com/villagerdetails/` | 模组 ID、网络包类型、共用工具（`JsonIO`、`ParseUtils`、`ChannelScanner`）、规则状态校验器、DTO |
| **服务端**（本文档主战场） | `common/src/server/java/com/villagerdetails/` | 规则定义、业务处理器、Mixin、事件、命令、配置、缓存 |
| 客户端 | `common/src/client/java/com/villagerdetails/` | 设置界面、渲染（高亮 / 脏熔炉 / 座位）、客户端侧开关与缓存 |

> 所以：**在 `common/src/main` 里找不到 `handler/` 和 `mixin/` 是正常的**，它们在 `server` 源集。

#### 2.2 还有一层「版本适配」

```
v26.2/src/server/java/com/villagerdetails/...   ← 26.2 专用
v26.3/src/server/java/com/villagerdetails/...   ← 26.3 专用（多一个 SequenceFunctionMixin）
```

两个版本层**包名与类名完全相同**（例如 `handler.entity.villager.trader.refresh.TradePoolChecker`），
由构建脚本按版本挑一份编入。业务文档提到这些类时统一写包名，不写 `v26.2/` 前缀。

**有 6 处关键实现只在版本层里**，common 里看不到：

| 规则 | 版本层里的实现 |
|---|---|
| `PortalBinding` / `PortalBindingEnhanced` | `mixin/block/portalBinding/EntityPortalMixin` |
| `VillagerBoneMealFlower` | `mixin/plant/GrassBlockBoneMealMixin` |
| `BlockCollectable` | `mixin/block/collectable/PlayerDestroyMixin` |
| `VillagerAutoTrader` | `TradePoolChecker` / `TradeSetAdapter` / `TradeEnchantmentScanner` / `mixin/.../VillagerTradeMixin` |
| `VillagerEmeraldDecompose` / `VillagerBookshelfDecompose` | `handler/entity/villager/trader/decompose/ItemProvisioner` |
| `BoxCrafting` | `handler/craft/BoxCraftPlan` |

#### 2.3 业务代码的七个抽屉

| 包 | 干什么 | 本文档哪里讲 |
|---|---|---|
| `rule/type/` | `RuleType`（规则总清单）、分类、配置类型 | 本篇 + [规则总索引](01-rule-index.md) |
| `handler/` | 业务逻辑主体（39 个类） | `02-rules/*`、`03-deep-dives/*` |
| `mixin/` | 对原版类的注入点（40 个） | 每条规则的「关键文件」；清单见[框架 08](../framework/08-mixin-inventory.md) |
| `event/impl/register/` | Fabric 事件接线（扫包自动注册） | 每条规则的「触发」 |
| `command/c/impl/` | `/ec c <规则> ...` 子命令树 | 每条规则的「命令」 |
| `config/impl/` | 各规则的细项配置节 | 每条规则的「配置类」 |
| `cache/impl/` | 跨 tick / 跨线程的会话态数据 | 每条规则的「依赖」+ [`04-glossary.md`](04-glossary.md) |

---

### 3. 「一条规则」是什么

规则 = `RuleType` 枚举里的**一个常量**。枚举有 10 个构造参数，业务视角只关心下面这些
（参数名、`Supplier` 懒加载这些框架细节见[框架 05](../framework/05-rule-framework.md)）：

| 参数 | 业务含义 | 例子 |
|---|---|---|
| `registerName` | **注册名**，命令里敲的那个词 | `InfiniteShear` |
| `displayName` / `displayInfo` | 展示名 / 描述的翻译键（服务端翻译后下发） | `setting.infinite_shear` |
| `category` | 分类，可以有**多个**（`/ec list <分类>` 用它筛） | `[ANIMAL]` |
| `quickSwitches` | **这条规则的全部合法取值**（单选） | `["false","true"]` |
| `state` | **默认值** | `"false"` |
| `commandObject` | 是否有 `/ec c <规则>` 子命令；`null` = 纯开关 | `null` |
| `configKind` | 是否「逐条配置类」（除了开关还维护一份名单 / 数值表） | `RuleConfigKind.NONE` |
| `listenerType` | 绑定哪套选区工具（区域绑定 / 实体+方块绑定） | `[]` |
| `reloadableConfig` | 细项配置类；`null` = 没有额外配置节 | → `NoSqueezeConfig` |
| `stateValidator` | 状态值的格式 / 范围校验器；`null` = 不校验 | `numericRange(0, 20)` |

**当前一共 40 条规则**。`RuleType` 枚举是唯一真相来源 —— 需要确切条数时用 `RuleType.values()` 或 `/ec list`。

---

### 4. 规则系统的四根支柱

```
                    ┌────────────────── 玩家 / 服主 ──────────────────┐
                    │  聊天命令 /ec ...                客户端设置界面 │
                    └───────────────┬─────────────────────────────────┘
                                    │ 都是「改规则」
                          ┌─────────▼─────────┐
                          │ RuleChangeService │  唯一入口：先校验，再写
                          └─────────┬─────────┘
                                    │
              ┌─────────────────────┼──────────────────────┐
              ▼                     ▼                      ▼
      ┌──────────────┐      ┌──────────────┐      ┌───────────────┐
      │   RuleCache  │      │ 落盘（两份） │      │ 回调 + 监听器 │
      │ 内存里的状态 │      │ 状态 / 配置节│      │ 立即副作用    │
      └──────┬───────┘      └──────────────┘      └───────────────┘
             │ 读
   ┌─────────┴─────────────────────────────────────────────┐
   ▼             ▼              ▼              ▼           ▼
 handler     mixin        事件回调        网络同步      命令补全 / 权限
```

#### 4.1 分类

`RuleCategoryType` 一共 10 个：`MOD_SETTING`、`BLOCK`、`PLANT`、`ENTITY`、`VILLAGER`、`MONSTER`、
`ANIMAL`、`PLAYER`、`COMMAND`、`FIX`。

- 一条规则可以**同时属于多个分类**（`/ec list entity` 和 `/ec list villager` 都能列出 `NoSqueeze`）。
- `COMMAND` 不是一个独立功能域，它的作用是让 `/ec list command` 能一口气列出**所有带子命令的规则**。
- `/ec list <分类>` 的分类名**容错**：忽略大小写，且末尾多一个 `s` 也认（`players` → `player`）。

#### 4.2 开关与状态值

**状态就是一个字符串**，没有"布尔型规则"和"数字型规则"之分：

| 形态 | 取值 | 怎么算"开启" | 例子 |
|---|---|---|---|
| 一开一关 | `false` / `true` | 不是 `false` 就算开启 | `InfiniteShear` |
| 权限档位 | `false` / `true` / `ops` / `master` / `admin` / `owner` | 同上；档位同时决定**谁能触发** | `PortalBinding` |
| 纯权限（不能关） | `true` / `ops` / `master` / `admin` / `owner` | 永远开启 | `RuleCommand`（`/ec` 总权限） |
| 数字 / 文本档 | `50` / `100` / `500` / `1000`、`3` / `6` / `12`、语言码 | 同上 | `VillagerOneTickTraderCount`、`PiglinBarterDelay` |

两个容易踩的点：

1. **`isEnabled` 只看「是不是 `false`」**。所以数字档位类规则（`VillagerHardWorking = 1`）
   **永远算开启**，想关掉得显式设 `false`（此时消费方按"原版逻辑"走）。
2. **只要 `quickSwitches` 里存在一个不是开关词的取值，整条规则就被当成"自定义列表"**，
   `/ec <规则> <任意文本>` 都会被放行。典型受害者是 `Language`（候选是语言码）。
   这类规则靠自己的 `stateValidator` 兜（目前只有 `HappyGhastSpeed` 挂了校验器）。

改规则时的两道校验（命令和界面共用同一段代码，不存在"命令拦住、界面放过去"）：

```
输入 → canonicalize（空值拒 / 预定义列表匹配 / 自定义列表原样过）→ stateValidator（格式与范围）
     → 通过才写进 RuleCache（写内存 + 落盘 + 触发回调）
```

#### 4.3 权限（两层，别混）

| 层 | 管什么 | 判定 | 能绕过吗 |
|---|---|---|---|
| **第一层**：`/ec` 总权限 | 能不能用 `/ec`、能不能看 / 改规则、客户端界面能不能拉到数据 | 规则 `RuleCommand` 的状态档位 + 权限节点 `villagerdetails:c.base` | 状态填 `true` 就是人人可用 |
| **第二层**：每条规则自己的档位 | `/ec c <规则> ...` 能不能执行；对玩家生效时也要够档位 | 状态档位 + 权限节点 `villagerdetails:c.<注册名小写>` | 逐条配置类规则**写死 owner**，不看自己的档位 |
| **工具层**：`/ec c reload` | 从磁盘重读配置 | 写死 `PERM_BASE` + `OWNERS` | 不能 |

一句话记法：**档位既是开关，也是"谁能触发"**。`PortalBinding` 设成 `ops` 之后，
普通玩家走进绑定区域照常走原版传送门，只有 OP 及以上才会被强制传送。

一个容易误解、但确实是**设计如此**的权限细节：**`/ec c <规则> ...` 只过第二层，不过 `RuleCommand` 那把总闸**
（`CCommand` 挂子命令时只加规则自己的 `requirement`）。所以一条「档位 = `true` 且不是逐条配置类」
的规则，普通玩家也能执行它的 `/ec c` 子命令。而 `/ec` 本身、`/ec list ...`、`/ec <规则> [状态]`
都在第一层后面。

#### 4.4 配置（四份文件，两条路径）

| 文件（存档内） | 存什么 | 谁来写 |
|---|---|---|
| `villager_details_binding.json` | **规则状态**：`注册名 → 状态字符串`（等于默认值的不写） | `RuleCache.setState` → `RuleSwitchConfig` |
| `villagerdetails/config.json` | **各规则的细项**：`节名 → JsonObject`（`NoSqueeze`、`PortalBinding`、`PlayerHeadSitting`、`FurnaceDirtyHighlight` …） | 各规则的配置类 `markDirty()` 后由 tick 统一落盘 |
| `villagerdetails/trade_index.json` | 物品 / 附魔的**别名表**（`绿宝石` / `emerald` → `minecraft:emerald`），给命令补全和命名牌解析用 | 内置数据，玩家可改 |
| `villagerdetails/villager_trade_cache.json` | **交易查询索引**（`TradeSearch` 的落盘数据，格式版本 2） | `VillagerTradeCache` 标脏后每 200 tick / 停服写盘 |

> 想"手改配置"：改完执行 `/ec c reload`（有参数就只重读那一份），或重启服务器。
> 规则状态与细项配置是**两个文件**，改规则状态请用 `/ec` 命令，不要手改 `binding.json` 之后不重载。

---

### 5. 改一条规则时，到底发生了什么

```
/ec <规则> <状态>   ──┐
客户端界面点按钮    ──┴──► RuleChangeService.change
                             │
                             ├─ ① canonicalize：空值 / 不在候选列表 → 直接回红字，什么都不写
                             ├─ ② stateValidator：格式 / 范围不过 → 同样什么都不写
                             ├─ ③ RuleCache.setState
                             │     ├─ 值没变 → 提前返回（注意：回调也不会触发）
                             │     ├─ 写内存
                             │     ├─ persist → RuleSwitchConfig（脏标记，稍后落盘）
                             │     └─ 触发 RuleCallbacks + RuleCache 监听器
                             └─ ④ 各规则的副作用（立刻生效）
                                   · 乐魂速度：重算骑乘中的乐魂
                                   · 交易查询：关→先落盘再清内存；开→把磁盘索引读回来
                                   · 脏熔炉：清空 / 开始扫描
                                   · 客户端：广播新状态 / 新描述符
```

两条**必须记住**的时序语义：

1. **值没变时 `setState` 会短路**，回调一个都不触发。所以"把规则设成它当前的值"看起来成功了，
   但没有任何副作用 —— 这也是 `ServerLangConfig.reload` 里为什么不只靠语言回调（见
   [`02-rules/settings.md`](02-rules/settings.md)）。
2. **落盘是异步的、滞后的**，但**副作用是同步的**。玩家改完规则立刻生效，磁盘稍后跟上。

---

### 6. 规则怎么「挂」到原版上（三种接线方式）

| 方式 | 用在哪 | 例子 |
|---|---|---|
| **Mixin 注入** | 要改原版某个方法的一段行为 | `SheepMixin`（剪毛后恢复）、`MerchantOfferMixin`（`increaseUses` 直接 cancel） |
| **Fabric 事件** | 原版有现成回调就不注入了 | `ServerLivingEntityEvents.AFTER_DEATH`（村民死亡清索引）、`ServerTickEvents.END_SERVER_TICK`（分帧刷新） |
| **tick 巡检** | 状态可能被别处改坏，需要周期性兜底 | `HappyGhastSpeedHandler.tick`（每 20 tick 回滚残留加速）、`FurnaceDirtyScanner` |

事件注册**不写清单**：所有 `event/impl/register`（含子包）下实现 `ServerEventRegisterServer` 的类
会被扫包自动调 `register()`。加一组事件 = 新建一个类，不改任何注册代码。
（扫包机制的实现细节见[框架 04](../framework/04-session-state.md) 与[框架 01](../framework/01-threading.md)。）

Mixin 一共 40 个，分四类：

1. **行为拦截 / 改写**（多数）：`@Inject` + `cancellable`，或 `@Redirect` 换掉某个调用；
2. **`@Accessor` / `@Invoker`**：把原版私有字段 / 私有方法暴露给业务代码
   （`AbstractVillagerInterfaceMixin` 读写 `offers`、`ZombieVillagerInvoker` 调治愈流程）；
3. **`@Shadow` 型**：为了在注入里调原方法（见 `PiglinAiMixin`）；
4. **版本差异补丁**：只在 `v26.2/`、`v26.3/` 各一份（见 2.2）。

---

### 7. 什么时候会离开「主线程」

只有三件事会异步，全部有明确的"不做就会卡 tick"的理由：

| 事情 | 为什么必须异步 | 详见 |
|---|---|---|
| 交易索引的写入 | 抓快照要深拷贝整张交易表，落盘要序列化几万条 | 业务：[`trade-search.md`](03-deep-dives/trade-search.md)；机制：[框架 01](../framework/01-threading.md) |
| 交易查询的匹配 | 遍历全服村民 × 每笔交易，几十毫秒级 | 同上 |
| 配置落盘 | 原子写文件，不能卡 tick | [框架 02](../framework/02-config-pipeline.md) |

**交易刷新（`VillagerAutoTrader`）不在异步池里**，它在主线程**分帧**：每 tick 跑固定预算。
原因很简单 —— 模拟过程要读村民实体、要改实体字段、要发包，这些只能在主线程。
详见 [`auto-trader.md`](03-deep-dives/auto-trader.md)。

---

### 8. 客户端参与的部分

服务端是唯一的真相来源；客户端只做三件事：**显示**、**收集玩家意愿**、**画高亮**。

| 能力 | 客户端做什么 | 通道（`network/sync/clientbound` / `receiver`） |
|---|---|---|
| 设置界面 | 渲染规则列表 / 详情 / 选项按钮；改规则时发回服务端 | `RuleDescriptorSyncChannel`、`RuleDataOptionsChannel` / `RuleDataReceiver`、`ServerRuleSetReceiver` |
| 「方块乘坐」开关 | 玩家本地的意愿（没装模组的玩家按"开"算） | `PlayerBlockSittingEnabledReceiver` |
| 「允许别人坐我头顶」 | 目标玩家自己的开关 | `PlayerHeadSittingAllowReceiver` |
| 选区高亮 | 高亮玩家已选的实体 / 方块 / 区域 | `EBSyncChannel`、`BBSyncChannel` / `EBClearReceiver`、`BBClearReceiver` |
| 传送门视图 | 高亮某条绑定两侧的门坐标 | `PortalViewSyncChannel` / `PortalViewClearReceiver` |
| 脏熔炉高亮 | 按"视距区块增量"维护一份坐标表并画框 | `FurnaceDirtySyncChannel` |
| 村民床 / 工作站标记 | 高亮绑定关系 | `VillagerBedSyncChannel`、`WorkstationSyncChannel` |
| 补全数据 | 服务端算好"这个存档能出现哪些交易物 / 附魔"，Tab 补全时按原版补全包返回（不走自定义通道） | 见 [`tab-completion.md`](03-deep-dives/tab-completion.md) |

**语言分裂也要知道**：`/ec` 命令的回复走**服务端语言**（规则 `Language` 决定），
而设置界面里的文字走**客户端自己的界面语言**（客户端把语言报上来，服务端按那份语言翻译后下发）。
所以一个中文服务端 + 英文客户端的管理员，看到的是"命令回复中文、界面英文"。

---

### 9. 设计约定与边界

下面每一条都是**刻意的行为**，请当作模组遵守的契约来读。

| # | 你会观察到的现象 | 设计约定 |
|---|---|---|
| 1 | 规则条数 | 40 条。`RuleType.values()` 是唯一真相来源，所有文档、命令列表与自检都由它推导 |
| 2 | `/ec c PortalBindingEnhanced` 不存在 | **一条规则一个命令对象。** 两条传送门绑定规则若各自注册同名子命令，Brigadier 会静默合并并丢掉权限条件，因此 `PortalBindingEnhanced` 故意不给命令。开关用 `/ec PortalBindingEnhanced <状态>` |
| 3 | `/ec Language <随便写>` 也返回成功 | `Language` 的候选是语言码 → 被判成自定义列表 → 任意文本放行。写错语言码会让服务端文案退回原始翻译键，请只用客户端确实内置的语言码 |
| 4 | 逐条配置类规则设成 `true`，普通玩家还是改不了名单 | 设计如此：`RuleConfigKind.LIST` 的 `/ec c` 子命令写死 owner，因为它改的是要落盘的名单 |
| 5 | `/ec c <规则> ...` 不过 `/ec` 总闸 | 设计如此：第一层管"管理规则"，第二层管"触发这条规则"。档位 `true` 的规则本来就是对所有人可用 |
| 6 | 在 `common` 里搜 `EntityPortalMixin` / `GrassBlockBoneMealMixin` / `ItemProvisioner` 搜不到 | 它们在 `v26.2/`、`v26.3/` 版本层，见 2.2 |
| 7 | 区块一直不卸载时，`TradeSearch` 查不到刚锁定的交易 | 索引是事件驱动的：区块卸载、打开交易界面（100 tick 防抖）、显式刷新。永不卸载的村民在下一次抓取时入索引，不做全服轮询 |
| 8 | 规则设成"当前值"没有任何效果反馈 | `setState` 值没变会短路，回调不触发（见第 5 节）。需要强制重放时走业务侧的刷新入口 |
| 9 | 命令回复和界面文字的语言可以不一样 | 服务端语言与客户端界面语言是两个独立设置（见第 8 节） |

---

### 10. 文档地图

| 文件 | 内容 |
|---|---|
| [`01-rule-index.md`](01-rule-index.md) | 40 条规则总索引（注册名 / 分类 / 默认值 / 一句话） |
| [`02-rules/`](02-rules/) | 按分类逐条写：`settings` `fix` `entity` `monster` `plant` `animal` `player` `villager` `block` |
| [`03-deep-dives/`](03-deep-dives/) | 7 篇深度剖析：`auto-trader` `trade-search` `portal-binding` `tab-completion` `villager-binding` `furnace-dirty` `player-sitting` |
| [`04-glossary.md`](04-glossary.md) | 业务术语表（陶瓦成对、331a/331b、锁定交易、快照…） |

**与框架文档的边界**（一句话原则）：

- 「为什么玩家看到 A 而不是 B」→ **业务文档**（本文及以下各篇）。
- 「为什么代码这么写而不是那么写」→ **框架文档**（[`doc/framework/`](../framework/README.md)）。

例如：本模组用 `RuleCache.isEnabled(...)` 读开关 —— 这是业务视角；
`RuleCache` 内部怎么走权限节点、怎么落盘、怎么通知监听器 —— 那是[框架 05](../framework/05-rule-framework.md)。
