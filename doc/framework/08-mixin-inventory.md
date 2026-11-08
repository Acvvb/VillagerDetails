# Mixin Inventory

The full inventory of injection points, grouped by target class, with accessors and invokers listed separately and every entry graded by risk.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> Source of the inventory: `v26.2/src/server/resources/villagerdetails.server.mixins.json` (45 entries) and
> `v26.3/src/server/resources/villagerdetails.server.mixins.json` (46 entries);
> the client's `villagerdetails.client.mixins.json` has an empty `client` / `mixins` array (**zero client mixins**).
>
> **40 common + 5 version-specific (+ the 1 that 26.3 alone has)**. The 6 version-specific entries are compared
> in [09](09-version-adapter.md); this chapter only lists **what is injected**.

---

### 1. The three fields of `mixins.json`

| Field | Value | Meaning |
|---|---|---|
| `required` | `true` | If any mixin fails to apply → **the game fails to start** (not a silent skip) |
| `injectors.defaultRequire` | `1` | An injection point without an explicit `require` demands "at least 1 target matched"; no match throws |
| `overwrites.requireAnnotations` | `true` | Overwriting a vanilla method must carry the `@Overwrite` annotation (this project uses no `@Overwrite`; the flag is a safety net) |

**An unlisted class is a silent no-op**: a mixin class that is not named in the json is never applied — the
code is right there, and nothing happens at runtime. So `08` (this chapter), `09` (version differences) and
the json must always change together. Keeping the two `villagerdetails.server.mixins.json` files (26.2 /
26.3), the two version-layer trees and this inventory consistent is a **manual release-checklist step** (the
checklist lives in [09](09-version-adapter.md)): there is no automatic cross-check in the build, and
compilation does not catch a divergence either, because the two same-named copies never reference each other.
The single legitimate difference between the two lists is 26.3's `SequenceFunctionMixin`.

---

### 2. Grouped by target vanilla class (the 40 common ones)

#### 2.1 `BlockBehaviour` / `BlockBehaviour.BlockStateBase`

| Mixin | Method | Injection point | Purpose | Related rule |
|---|---|---|---|---|
| `block.miningResistance.MiningResistanceMixin` | `getDestroyProgress` | HEAD / cancellable | Overrides destroy progress with the config value (the collectable fallback also lives here) | `BLOCK_MINING_RESISTANCE` |
| `block.power.BlockStateBaseConductorMixin` | `isRedstoneConductor` | HEAD / cancellable | Listed blocks conduct redstone | `BLOCK_STRONG_POWER` |
| `block.collectable.CollectableMixin` | `getDrops` | RETURN / cancellable | Adds "the block itself" when the vanilla drop list is empty | `BLOCK_COLLECTABLE` |
| `block.endPortalGravityBlock.EndPortalGravityMixin` | `onPlace`(TAIL) / `neighborChanged`(HEAD) / `tick`(HEAD) | three sites | Unsupported listed blocks inside the 5×5×5 end-portal volume fall like sand | `ENDER_PORTAL_GRAVITY_BLOCK` |

> `BlockStateBase` is an inner class of `BlockBehaviour`, so the `@Mixin` target is written `BlockBehaviour.BlockStateBase`.

#### 2.2 `ExplosionDamageCalculator`

| Mixin | Method | Injection point | Purpose | Related rule |
|---|---|---|---|---|
| `block.explosionResistance.ExplosionResistanceMixin` | `getBlockExplosionResistance` | HEAD / cancellable | Overrides block explosion resistance (air passes through) | `BLOCK_EXPLOSION_RESISTANCE` |
| `block.TntNoDestroyDrops.ExplosionMixin` | `shouldDamageEntity` | HEAD / cancellable | Items dropped by a TNT explosion take no damage | `TNT_NO_DESTROY_DROPS` |

#### 2.3 Spawn paths

| Mixin | Method | Injection point | Purpose | Related rule |
|---|---|---|---|---|
| `entity.noSpawn.EntityTypeMixin` | `create(Level, EntitySpawnRequest)` | HEAD / cancellable | Listed entities "create as nothing" (the innermost layer of every spawn path) | `MOB_NO_SPAWN` |
| `entity.noSpawn.NaturalSpawnerMixin` | `getMobForSpawn(ServerLevel, EntityType)` | HEAD / cancellable | Returns `null` early, skipping vanilla's `Can't spawn` WARN (log muting) | `MOB_NO_SPAWN` |

#### 2.4 Entities / mobs

| Mixin | Method | Injection point | Purpose | Related rule |
|---|---|---|---|---|
| `entity.noSqueeze.EntityMixin` | `isPushable` / `push(Entity)` | HEAD / cancellable ×2 | Listed entities are neither pushed nor push others (only `push(Entity)` is hooked; the knockback path is untouched) | `VILLAGER_NO_SQUEEZE` |
| `entity.noSqueeze.LivingEntityMixin` | `hurtServer` | HEAD / cancellable | Blocks squeeze damage **by damage-type id** (7 kinds) | `VILLAGER_NO_SQUEEZE` |
| `entity.animal.SheepMixin` | `shear` | TAIL | Calls `setSheared(false)` immediately after shearing | `INFINITE_SHEAR` |
| `entity.happyghast.HappyGhastSpeedMixin` | `addPassenger` / `removePassenger` | TAIL ×2 | Recomputes speed on boarding and on alighting (all logic is in the handler) | `HAPPY_GHAST_SPEED` |
| `entity.piglinTrade.PiglinAiMixin` | `pickUpItem`(HEAD) / `getBarterResponseItems`(RETURN, cancellable) / `admireGoldItem`(HEAD, cancellable) | three sites | Gold block → gold ingot + tagging; output ×9 (with reentrancy protection); admiration time × punishment | `PIGLIN_GOLD_BLOCK_MULTIPLY` / `PIGLIN_BARTER_DELAY` / `…PUNISHMENT` |
| `monster.noBrainOnPortal.MobInterfaceMixin` | `@Accessor("goalSelector")` / `@Accessor("targetSelector")` | accessor | Lets the event handler clear both selectors | `NO_BRAIN_ON_PORTAL` |
| `monster.zombieVillager.ZombieVillagerInvoker` | `@Invoker("startConverting")` | invoker | Borrows the private cure entry point (passing 0 = finish on the next tick) | `ZOMBIE_VILLAGER_HELP` |
| `entity.villager.move.BrainMixin` | `setActiveActivity` | HEAD / cancellable | Locks the activity to travelling while `DESTINATION` is set | `VILLAGER_MOVE_CONTROLLER` |
| `entity.villager.move.VillagerBrainGoalsMixin` | `registerBrainGoals` | RETURN | Registers the `GO_TO_DESTINATION` activity for villagers | `VILLAGER_MOVE_CONTROLLER` |
| `entity.villager.move.BrainInterfaceMixin` | `@Invoker("setActiveActivity")` | invoker | Used by `BrainMixin` to reset the activity (avoiding infinite recursion) | `VILLAGER_MOVE_CONTROLLER` |
| `entity.villager.sync.VillagerSyncMixin` | `tick` | RETURN | Pushes to the client only when the bed / workstation memory changed (one last-value copy each) | no rule switch (the channel's own `canSendTo` gates it) |

#### 2.5 Villager trades

| Mixin | Method | Injection point | Purpose | Related rule |
|---|---|---|---|---|
| `entity.villager.trader.autoTrader.trigger.NameTagItemMixin` | `interactLivingEntity` | RETURN | The three trigger lines after a name tag is applied (`help` / naming / 331) | `VILLAGER_AUTO_TRADER` / `ZOMBIE_VILLAGER_HELP` / `VILLAGER_SILENT_AUTO_REROLL_TRADER` |
| `entity.villager.trader.autoTrader.hardWork.MerchantOfferResetMixin` | `resetUses()` inside `Villager#restock` | `@Redirect` | Turns the used count negative after a restock (so the multiplier takes effect) | `VILLAGER_HARD_WORKING` |
| `entity.villager.trader.infiniteTrade.MerchantOfferMixin` | `increaseUses` | HEAD / cancellable | Trades never sell out | `VILLAGER_INFINITE_TRADE` |
| `entity.villager.trader.emeraldDecompose.AbstractVillagerExchangeMixin` | `getOffers` | RETURN | Rewrites the toolsmith / weaponsmith high-tier "diamond→emerald" trade into "emerald→diamond" | `VILLAGER_TOOLSMITH_EXCHANGE` |
| `entity.villager.trader.tradeSearch.TradeOpenMixin` | `setTradingPlayer` | HEAD | Captures one more trade snapshot when the screen opens (the 100-tick debounce is in business code) | `VILLAGER_TRADE_SEARCH` |
| `entity.villager.trader.autoTrader.AbstractVillagerInterfaceMixin` | `@Mutable @Accessor("offers")` | accessor | Writes the new trade table back (**cannot** use `overrideOffers`, which is an empty implementation) | `VILLAGER_AUTO_TRADER` |
| `entity.villager.trader.autoTrader.MerchantMenuInterfaceMixin` | `@Accessor("trader")` / `@Accessor("tradeContainer")` | accessor | Reads the trade screen's trader and slots | `VILLAGER_EMERALD_DECOMPOSE` and others |
| `entity.villager.trader.autoTrader.MerchantOfferInterfaceMixin` | `@Accessor("uses")` | accessor | Changes the used count (restock multiplier) | `VILLAGER_HARD_WORKING` |
| `entity.villager.trader.autoTrader.book.EnchantRandomlyFunctionMixin` | `@Accessor("options")` | accessor | Reads the `enchant_randomly` candidate pool (producibility check) | `VILLAGER_AUTO_TRADER` |
| `entity.piglinTrade.MerchantMenuProvisionMixin` | `MerchantMenu#tryMoveItems` | HEAD | Breaks a payment down into change before paying (emerald block / bookshelf) | `VILLAGER_EMERALD_DECOMPOSE` / `VILLAGER_BOOKSHELF_DECOMPOSE` |

#### 2.6 Crafting

| Mixin | Method | Injection point | Purpose | Related rule |
|---|---|---|---|---|
| `craft.BoxCraftingMenuMixin` | `slotChangedCraftingGrid` / `quickMoveStack` | HEAD / cancellable ×2 | Result-slot preview + intercepting Shift-click (so the preview is not double-applied) | `BOX_CRAFTING` |
| `craft.InventoryMenuMixin` | `quickMoveStack` | HEAD / cancellable | Same (2×2 inventory crafting) | `BOX_CRAFTING` |
| `craft.ResultSlotMixin` | `onTake` | HEAD / cancellable | Plain-click path: clears the preview, then emits the real product | `BOX_CRAFTING` |
| `craft.CrafterBlockMixin` | `dispenseFrom` | HEAD / cancellable | Crafter path: computes in one pass and cancels the two-phase animation | `BOX_CRAFTING` |
| `craft.AbstractCraftingMenuAccessor` | `@Accessor("craftSlots")` / `@Accessor("resultSlots")` | accessor | The base-class fields live on `AbstractCraftingMenu` | `BOX_CRAFTING` |

#### 2.7 Blocks / network

| Mixin | Method | Injection point | Purpose | Related rule |
|---|---|---|---|---|
| `block.portalPiglinNoSpawn.NetherPortalSpawnMixin` | `NetherPortalBlock#randomTick` | HEAD / cancellable | Portals inside a bound region no longer spawn zombified piglins | `PORTAL_PIGLIN_NO_SPAWN` |
| `fix.move.GlobalSpeedBypassMixin` | `shouldCheckPlayerMovement`(HEAD, cancellable) / `Logger.warn` in `handleMovePlayer`+`handleMoveVehicle`(`@Redirect`) | two sites | Skips the movement check + silences two log lines | `GLOBAL_SPEED_BYPASS` |
| `fix.log.EntityUUIDWarningMixin` | `PersistentEntitySectionManager#addEntityUuid` | HEAD / cancellable | Returns false straight away when the UUID already exists, suppressing the "duplicate UUID" warning (log only) | `GLOBAL_SPEED_BYPASS` |
| `entity.blockSitting.SeatBuildLimitMessageMixin` | `sendBuildLimitMessage` inside `handleUseItemOn` | `@Redirect` | Swallows the spurious "build height limit" message fired at the moment of sitting down | `PLAYER_BLOCK_SITTING` |
| `player.playerHeadSitting.EntityStartRidingMixin` | `canSerialize()` inside `startRiding(Entity, ZZ)` | `@Redirect` | Allows a "player / seat entity" to act as a vehicle | `PLAYER_HEAD_SITTING` / `PLAYER_BLOCK_SITTING` |
| `player.playerHeadSitting.EntityPlayerVehiclePassengersMixin` | `addPassenger` / `removePassenger` | TAIL ×2 | Sends the passenger list to "the player vehicle itself" (vanilla does not send it there) | `PLAYER_HEAD_SITTING` |

`EntityUUIDWarningMixin` lives in `mixin/fix/log/` (package `com.villagerdetails.mixin.fix.log`) and is listed
as `fix.log.EntityUUIDWarningMixin` in both version layers' `villagerdetails.server.mixins.json`. It only
suppresses the "duplicate UUID" warning: the return value is still `false`, exactly what vanilla produces, so
the caller's conclusion is unchanged. It reuses the `GLOBAL_SPEED_BYPASS` switch, because these warnings are
produced by high-speed movement and repeated world re-entry — which is also why grepping that switch finds the
movement injector in `fix/move` as well.

---

### 3. `@Accessor` / `@Invoker` listed separately (9 interfaces)

These **do not change vanilla behaviour**; they only expose private members. The risk level is the lowest,
but they **drift most easily between versions** (a renamed field breaks them).

| Interface | Target class | Exposed member | Purpose |
|---|---|---|---|
| `AbstractVillagerInterfaceMixin` | `AbstractVillager` | `@Mutable @Accessor("offers")` | Writes the trade table back |
| `MerchantMenuInterfaceMixin` | `MerchantMenu` | `@Accessor("trader")` / `@Accessor("tradeContainer")` | Reads the screen context |
| `MerchantOfferInterfaceMixin` | `MerchantOffer` | `@Accessor("uses")` | Changes the used count |
| `EnchantRandomlyFunctionMixin` | `EnchantRandomlyFunction` | `@Accessor("options")` | Reads the enchantment candidate pool |
| `AbstractCraftingMenuAccessor` | `AbstractCraftingMenu` | `@Accessor("craftSlots")` / `@Accessor("resultSlots")` | Crafting slots |
| `MobInterfaceMixin` | `Mob` | `@Accessor("goalSelector")` / `@Accessor("targetSelector")` | Clears AI |
| `ZombieVillagerInvoker` | `ZombieVillager` | `@Invoker("startConverting")` | Instant cure |
| `BrainInterfaceMixin` | `Brain` | `@Invoker("setActiveActivity")` | Resets the activity |
| `VillagerTradeMixin` (**version-specific**) | `VillagerTrade` | `@Accessor("wants")` / `("additionalWants")` / `("gives")` / `("givenItemModifier")`(26.3) | Feeds the list-config registry scan |

---

### 4. Risk levels (read this table before changing code)

| Level | Technique | Count | Risk |
|---|---|---|---|
| **High** | `@Redirect` (replaces a call) + `cancellable` | 4 (`resetUses` / `canSerialize` / `sendBuildLimitMessage` / `Logger.warn`×2 counted as one) | If the vanilla call site changes, `defaultRequire=1` fails startup outright; the replacement may also not be semantically equivalent |
| **Medium** | `@Inject` + `cancellable = true` (changes control flow) | about 20 | Cancelling too broadly or too narrowly is hard to notice; a `HEAD` cancel can also conflict with other mods |
| **Low** | `@Inject` without `cancellable` (side effect only) | about 10 | Adds a side effect without blocking vanilla |
| **Lowest** | `@Accessor` / `@Invoker` | 9 | Only reads / writes private members; a renamed field breaks it |

**Debugging mnemonic**: injection point not found → check whether the `refmap` was generated (a build
output); "it runs but has no effect" → first confirm the class is in the json, then confirm `defaultRequire`
reported nothing at startup.

---

### 5. Version-specific injection points (details in [09](09-version-adapter.md))

| Mixin | 26.2 | 26.3 | Difference |
|---|---|---|---|
| `block.portalBinding.EntityPortalMixin` | `@Redirect method="handlePortal"` | `@Redirect method="teleportToPortalDestination"` | 26.3 extracted `canTeleport + teleport` into a new method |
| `fix.move.VehicleMoveTrustMixin` | reads `packet.position()/yRot()/xRot()` | reads `packet.movingTo(): PositionAndRotation` | packet API change |
| `block.collectable.PlayerDestroyMixin` | `playerDestroy(Level, Player, …)` | `playerDestroy(ServerLevel, ServerPlayer, …)` | narrowed signature (`@Inject` demands an exact per-parameter match) |
| `plant.GrassBlockBoneMealMixin` | one copy | one copy | identical content, compiled once per version |
| `villager.trader.autoTrader.VillagerTradeMixin` | `givenItemModifiers` (List) | `givenItemModifier` (`Optional<Holder<…>>`) | field plural → singular + Holder |
| `villager.trader.autoTrader.SequenceFunctionMixin` | — | ✅ 26.3 only | 26.3 folds the array into a `SequenceFunction`, which has to be taken apart |

---

<a id="中文"></a>

## 中文

> 清单来源：`v26.2/src/server/resources/villagerdetails.server.mixins.json`（45 条）与
> `v26.3/src/server/resources/villagerdetails.server.mixins.json`（46 条）；
> 客户端 `villagerdetails.client.mixins.json` 的 `client` / `mixins` 都是空数组（**客户端零 mixin**）。
>
> **common 40 个 + 版本专属 5 个（+ 26.3 独有的 1 个）**。版本专属的 6 个在 [09](09-version-adapter.md) 里讲差异，本篇只列“注入了什么”。

---

### 1. `mixins.json` 的三个字段

| 字段 | 值 | 含义 |
|---|---|---|
| `required` | `true` | 任何一条 mixin 应用失败 → **游戏启动失败**（不是静默跳过） |
| `injectors.defaultRequire` | `1` | 未显式写 `require` 的注入点，默认要求“至少匹配到 1 个目标”；匹配不到就抛 |
| `overwrites.requireAnnotations` | `true` | 覆盖原版方法时必须带 `@Overwrite` 注解（本项目实际没用 `@Overwrite`，这条是保险） |

**漏登记 = 静默失效**：mixin 类不在 json 里 → 根本不会被应用，代码看着在、运行时不生效。
所以 `08`（本篇）、`09`（版本差异）与 json 必须一起改。两份 `villagerdetails.server.mixins.json`（26.2 / 26.3）、
两份版本层实现与这篇清单保持一致，是一条**人工发布 checklist**（清单在 [09](09-version-adapter.md)）：
构建里没有自动一致性校验，编译期也发现不了分叉 —— 两份同名类从不互相引用。
两份清单之间唯一允许的差异是 26.3 的 `SequenceFunctionMixin`。

---

### 2. 按目标原版类分组（common 40 个）

#### 2.1 `BlockBehaviour` / `BlockBehaviour.BlockStateBase`

| Mixin | 方法 | 注入点 | 目的 | 关联规则 |
|---|---|---|---|---|
| `block.miningResistance.MiningResistanceMixin` | `getDestroyProgress` | HEAD / cancellable | 用配置值覆盖破坏进度（可采集兜底也在这里） | `BLOCK_MINING_RESISTANCE` |
| `block.power.BlockStateBaseConductorMixin` | `isRedstoneConductor` | HEAD / cancellable | 名单方块当红石导体 | `BLOCK_STRONG_POWER` |
| `block.collectable.CollectableMixin` | `getDrops` | RETURN / cancellable | 原版掉落为空时补一个“方块自身” | `BLOCK_COLLECTABLE` |
| `block.endPortalGravityBlock.EndPortalGravityMixin` | `onPlace`(TAIL) / `neighborChanged`(HEAD) / `tick`(HEAD) | 三处 | 末地门 5×5×5 内的悬空名单方块像沙子一样下落 | `ENDER_PORTAL_GRAVITY_BLOCK` |

> `BlockStateBase` 是 `BlockBehaviour` 的内部类，`@Mixin` 目标写 `BlockBehaviour.BlockStateBase`。

#### 2.2 `ExplosionDamageCalculator`

| Mixin | 方法 | 注入点 | 目的 | 关联规则 |
|---|---|---|---|---|
| `block.explosionResistance.ExplosionResistanceMixin` | `getBlockExplosionResistance` | HEAD / cancellable | 覆盖方块爆炸抗性（空气放行） | `BLOCK_EXPLOSION_RESISTANCE` |
| `block.TntNoDestroyDrops.ExplosionMixin` | `shouldDamageEntity` | HEAD / cancellable | TNT 爆炸中的掉落物免伤 | `TNT_NO_DESTROY_DROPS` |

#### 2.3 生成路径

| Mixin | 方法 | 注入点 | 目的 | 关联规则 |
|---|---|---|---|---|
| `entity.noSpawn.EntityTypeMixin` | `create(Level, EntitySpawnRequest)` | HEAD / cancellable | 名单实体“创建成空”（所有生成路径的最内层） | `MOB_NO_SPAWN` |
| `entity.noSpawn.NaturalSpawnerMixin` | `getMobForSpawn(ServerLevel, EntityType)` | HEAD / cancellable | 提前返回 `null`，跳过原版那条 `Can't spawn` WARN（消音） | `MOB_NO_SPAWN` |

#### 2.4 实体 / 生物

| Mixin | 方法 | 注入点 | 目的 | 关联规则 |
|---|---|---|---|---|
| `entity.noSqueeze.EntityMixin` | `isPushable` / `push(Entity)` | HEAD / cancellable ×2 | 名单实体不被推、也不推别人（只认 `push(Entity)`，击退路径不动） | `VILLAGER_NO_SQUEEZE` |
| `entity.noSqueeze.LivingEntityMixin` | `hurtServer` | HEAD / cancellable | 按**伤害类型 id** 拦挤压类伤害（7 种） | `VILLAGER_NO_SQUEEZE` |
| `entity.animal.SheepMixin` | `shear` | TAIL | 剪完立刻 `setSheared(false)` | `INFINITE_SHEAR` |
| `entity.happyghast.HappyGhastSpeedMixin` | `addPassenger` / `removePassenger` | TAIL ×2 | 上下坐时重算速度（逻辑全在 Handler） | `HAPPY_GHAST_SPEED` |
| `entity.piglinTrade.PiglinAiMixin` | `pickUpItem`(HEAD) / `getBarterResponseItems`(RETURN, cancellable) / `admireGoldItem`(HEAD, cancellable) | 三处 | 金块→金锭 + 打标签；产出 ×9（有重入保护）；欣赏时长 ×惩罚 | `PIGLIN_GOLD_BLOCK_MULTIPLY` / `PIGLIN_BARTER_DELAY` / `…PUNISHMENT` |
| `monster.noBrainOnPortal.MobInterfaceMixin` | `@Accessor("goalSelector")` / `@Accessor("targetSelector")` | accessor | 让事件处理器能清空两个选择器 | `NO_BRAIN_ON_PORTAL` |
| `monster.zombieVillager.ZombieVillagerInvoker` | `@Invoker("startConverting")` | invoker | 借出私有治愈入口（传 0 = 下一 tick 完成） | `ZOMBIE_VILLAGER_HELP` |
| `entity.villager.move.BrainMixin` | `setActiveActivity` | HEAD / cancellable | `DESTINATION` 存在时把活动锁在前往目的地 | `VILLAGER_MOVE_CONTROLLER` |
| `entity.villager.move.VillagerBrainGoalsMixin` | `registerBrainGoals` | RETURN | 给村民注册 `GO_TO_DESTINATION` activity | `VILLAGER_MOVE_CONTROLLER` |
| `entity.villager.move.BrainInterfaceMixin` | `@Invoker("setActiveActivity")` | invoker | `BrainMixin` 里重设活动用（避免无限递归） | `VILLAGER_MOVE_CONTROLLER` |
| `entity.villager.sync.VillagerSyncMixin` | `tick` | RETURN | 床 / 工作站点记忆变了才推客户端（各记一份上次值） | 无规则开关（通道自带 `canSendTo`） |

#### 2.5 村民交易

| Mixin | 方法 | 注入点 | 目的 | 关联规则 |
|---|---|---|---|---|
| `entity.villager.trader.autoTrader.trigger.NameTagItemMixin` | `interactLivingEntity` | RETURN | 命名牌贴完后的三条触发线（`help` / 点名 / 331） | `VILLAGER_AUTO_TRADER` / `ZOMBIE_VILLAGER_HELP` / `VILLAGER_SILENT_AUTO_REROLL_TRADER` |
| `entity.villager.trader.autoTrader.hardWork.MerchantOfferResetMixin` | `Villager#restock` 里的 `resetUses()` | `@Redirect` | 补货后把已用次数改成负数（倍率生效） | `VILLAGER_HARD_WORKING` |
| `entity.villager.trader.infiniteTrade.MerchantOfferMixin` | `increaseUses` | HEAD / cancellable | 交易永不售罄 | `VILLAGER_INFINITE_TRADE` |
| `entity.villager.trader.emeraldDecompose.AbstractVillagerExchangeMixin` | `getOffers` | RETURN | 工具匠 / 武器匠高等级交易“钻石→绿宝石”改成“绿宝石→钻石” | `VILLAGER_TOOLSMITH_EXCHANGE` |
| `entity.villager.trader.tradeSearch.TradeOpenMixin` | `setTradingPlayer` | HEAD | 打开界面时补抓一次交易快照（100 tick 防抖在业务里） | `VILLAGER_TRADE_SEARCH` |
| `entity.villager.trader.autoTrader.AbstractVillagerInterfaceMixin` | `@Mutable @Accessor("offers")` | accessor | 写回新交易表（**不能**用 `overrideOffers`，那是空实现） | `VILLAGER_AUTO_TRADER` |
| `entity.villager.trader.autoTrader.MerchantMenuInterfaceMixin` | `@Accessor("trader")` / `@Accessor("tradeContainer")` | accessor | 读交易界面的 trader 与槽位 | `VILLAGER_EMERALD_DECOMPOSE` 等 |
| `entity.villager.trader.autoTrader.MerchantOfferInterfaceMixin` | `@Accessor("uses")` | accessor | 改已用次数（补货倍率） | `VILLAGER_HARD_WORKING` |
| `entity.villager.trader.autoTrader.book.EnchantRandomlyFunctionMixin` | `@Accessor("options")` | accessor | 读 `enchant_randomly` 的候选池（可产性判定） | `VILLAGER_AUTO_TRADER` |
| `entity.piglinTrade.MerchantMenuProvisionMixin` | `MerchantMenu#tryMoveItems` | HEAD | 付款前按配方“换开零钱”（绿宝石块 / 书架） | `VILLAGER_EMERALD_DECOMPOSE` / `VILLAGER_BOOKSHELF_DECOMPOSE` |

#### 2.6 合成

| Mixin | 方法 | 注入点 | 目的 | 关联规则 |
|---|---|---|---|---|
| `craft.BoxCraftingMenuMixin` | `slotChangedCraftingGrid` / `quickMoveStack` | HEAD / cancellable ×2 | 结果槽预览 + 拦截 Shift 点击（防 preview 叠加） | `BOX_CRAFTING` |
| `craft.InventoryMenuMixin` | `quickMoveStack` | HEAD / cancellable | 同上（2×2 物品栏合成） | `BOX_CRAFTING` |
| `craft.ResultSlotMixin` | `onTake` | HEAD / cancellable | 普通点击路径：清 preview 后发真实产物 | `BOX_CRAFTING` |
| `craft.CrafterBlockMixin` | `dispenseFrom` | HEAD / cancellable | 合成器路径：一次算完并取消两阶段动画 | `BOX_CRAFTING` |
| `craft.AbstractCraftingMenuAccessor` | `@Accessor("craftSlots")` / `@Accessor("resultSlots")` | accessor | 基类字段在 `AbstractCraftingMenu` 上 | `BOX_CRAFTING` |

#### 2.7 方块 / 网络

| Mixin | 方法 | 注入点 | 目的 | 关联规则 |
|---|---|---|---|---|
| `block.portalPiglinNoSpawn.NetherPortalSpawnMixin` | `NetherPortalBlock#randomTick` | HEAD / cancellable | 绑定区域内的门不再刷僵尸猪灵 | `PORTAL_PIGLIN_NO_SPAWN` |
| `fix.move.GlobalSpeedBypassMixin` | `shouldCheckPlayerMovement`(HEAD, cancellable) / `Logger.warn` in `handleMovePlayer`+`handleMoveVehicle`(`@Redirect`) | 两处 | 跳过位移校验 + 静默两条日志 | `GLOBAL_SPEED_BYPASS` |
| `fix.log.EntityUUIDWarningMixin` | `PersistentEntitySectionManager#addEntityUuid` | HEAD / cancellable | UUID 已存在时直接返回 false，压掉“重复 UUID”警告（只影响日志） | `GLOBAL_SPEED_BYPASS` |
| `entity.blockSitting.SeatBuildLimitMessageMixin` | `handleUseItemOn` 里的 `sendBuildLimitMessage` | `@Redirect` | 吞掉坐下瞬间误发的“建筑高度上限” | `PLAYER_BLOCK_SITTING` |
| `player.playerHeadSitting.EntityStartRidingMixin` | `startRiding(Entity, ZZ)` 里的 `canSerialize()` | `@Redirect` | 允许“玩家 / 座位实体”当载具 | `PLAYER_HEAD_SITTING` / `PLAYER_BLOCK_SITTING` |
| `player.playerHeadSitting.EntityPlayerVehiclePassengersMixin` | `addPassenger` / `removePassenger` | TAIL ×2 | 把乘客表补发给“玩家载具本人”（原版不发给他） | `PLAYER_HEAD_SITTING` |

`EntityUUIDWarningMixin` 放在 `mixin/fix/log/`（包名 `com.villagerdetails.mixin.fix.log`），
两份版本层的 `villagerdetails.server.mixins.json` 都登记为 `fix.log.EntityUUIDWarningMixin`。
它只压掉“重复 UUID”那条警告：返回值仍是 `false`，与原版完全一致，调用方拿到的结论不变。
开关复用的是 `GLOBAL_SPEED_BYPASS` —— 这类警告正是高速位移 / 反复重进世界时刷出来的，
所以 grep 这个开关时，`fix/move` 的 `GlobalSpeedBypassMixin` 里也会出现同一条规则。

---

### 3. `@Accessor` / `@Invoker` 单列（9 个接口）

这些**不改变原版行为**，只是把私有成员暴露出来。风险等级最低，但**版本间最容易漂**（字段改名就失效）。

| 接口 | 目标类 | 暴露的成员 | 用途 |
|---|---|---|---|
| `AbstractVillagerInterfaceMixin` | `AbstractVillager` | `@Mutable @Accessor("offers")` | 写回交易表 |
| `MerchantMenuInterfaceMixin` | `MerchantMenu` | `@Accessor("trader")` / `@Accessor("tradeContainer")` | 读界面上下文 |
| `MerchantOfferInterfaceMixin` | `MerchantOffer` | `@Accessor("uses")` | 改已用次数 |
| `EnchantRandomlyFunctionMixin` | `EnchantRandomlyFunction` | `@Accessor("options")` | 读附魔候选池 |
| `AbstractCraftingMenuAccessor` | `AbstractCraftingMenu` | `@Accessor("craftSlots")` / `@Accessor("resultSlots")` | 合成槽位 |
| `MobInterfaceMixin` | `Mob` | `@Accessor("goalSelector")` / `@Accessor("targetSelector")` | 清 AI |
| `ZombieVillagerInvoker` | `ZombieVillager` | `@Invoker("startConverting")` | 秒治愈 |
| `BrainInterfaceMixin` | `Brain` | `@Invoker("setActiveActivity")` | 重设活动 |
| `VillagerTradeMixin`（**版本专属**） | `VillagerTrade` | `@Accessor("wants")` / `("additionalWants")` / `("gives")` / `("givenItemModifier")`(26.3) | 补全数据扫注册表 |

---

### 4. 风险分级（改代码前先看这张表）

| 等级 | 手段 | 数量 | 风险 |
|---|---|---|---|
| **高** | `@Redirect`（换掉某个调用）+ `cancellable` | 4（`resetUses` / `canSerialize` / `sendBuildLimitMessage` / `Logger.warn`×2 算一处） | 原版调用点变了就 `defaultRequire=1` 直接启动失败；也可能“换掉后语义不等价” |
| **中** | `@Inject` + `cancellable = true`（会改控制流） | 约 20 | 取消得太宽 / 太窄都难发现；`HEAD` 取消可能与其它模组互斥 |
| **低** | `@Inject` 无 `cancellable`（只做事） | 约 10 | 只加副作用，不阻断原版 |
| **最低** | `@Accessor` / `@Invoker` | 9 | 只读/写私有成员，字段改名即失效 |

**排查口诀**：注入点找不到 → 看 `refmap` 是否生成（编译产物）；“通了但没效果” → 先确认类在 json 里、
再确认 `defaultRequire` 有没有在启动时报错。

---

### 5. 版本专属注入点（详见 [09](09-version-adapter.md)）

| Mixin | 26.2 | 26.3 | 差异 |
|---|---|---|---|
| `block.portalBinding.EntityPortalMixin` | `@Redirect method="handlePortal"` | `@Redirect method="teleportToPortalDestination"` | 26.3 把 `canTeleport + teleport` 抽成了新方法 |
| `fix.move.VehicleMoveTrustMixin` | 读 `packet.position()/yRot()/xRot()` | 读 `packet.movingTo(): PositionAndRotation` | 包 API 变化 |
| `block.collectable.PlayerDestroyMixin` | `playerDestroy(Level, Player, …)` | `playerDestroy(ServerLevel, ServerPlayer, …)` | 签名收窄（`@Inject` 要求逐参精确） |
| `plant.GrassBlockBoneMealMixin` | 一份 | 一份 | 内容相同，随版本各编一份 |
| `villager.trader.autoTrader.VillagerTradeMixin` | `givenItemModifiers`（List） | `givenItemModifier`（`Optional<Holder<…>>`） | 字段复数→单数 + Holder 化 |
| `villager.trader.autoTrader.SequenceFunctionMixin` | — | ✅ 仅 26.3 | 26.3 的数组会被折成 `SequenceFunction`，需要拆开 |
