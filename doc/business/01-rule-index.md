# Rule Index

The complete index of all 40 rules: register name, category, default value and a one-liner for each.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> Data source: `common/src/server/java/com/villagerdetails/rule/type/RuleType.java` (the enum declaration, read entry by entry).
> **40 rules in total.** "Default value" = the `state` argument in the enum, i.e. the state of a rule that has never been changed.
> The "one-liner" only answers "what does it do"; implementation details live in the matching category file.

---

### Contents

- [Settings](02-rules/settings.md) — 2 rules
- [Fixes](02-rules/fix.md) — 2 rules
- [Entities](02-rules/entity.md) — 7 rules
- [Mobs](02-rules/monster.md) — 1 rule
- [Plants](02-rules/plant.md) — 1 rule
- [Animals](02-rules/animal.md) — 1 rule
- [Players](02-rules/player.md) — 2 rules
- [Villagers](02-rules/villager.md) — 14 rules
- [Blocks](02-rules/block.md) — 10 rules

> Attribution rule: a rule is written up in detail only in its **primary category** file, and the other categories it belongs to just get one line (the enum categories of `NoSqueeze` are `ENTITY + VILLAGER` while its full text is in `entity.md`; `ZombieVillagerHelp` is `VILLAGER + MONSTER` with its full text also in `entity.md`).

---

### MOD_SETTING (2 rules) → [settings.md](02-rules/settings.md)

| Register name | Category | Default value | One-liner |
|---|---|---|---|
| `Language` | MOD_SETTING | `zh_cn` | The mod's "server language": decides which language all `/ec` replies and all rule names / descriptions use |
| `RuleCommand` | MOD_SETTING | `owner` | Global permission tier of the `/ec` command (who can see the rule list, who can change rules) |

### FIX (2 rules) → [fix.md](02-rules/fix.md)

| Register name | Category | Default value | One-liner |
|---|---|---|---|
| `GlobalSpeedBypass` | FIX | `false` | Turns off the server-side pull-back for "moved too quickly / wrongly", letting through only the player's own movement and a Happy Ghast the player is controlling |
| `HappyGhastSpeed` | FIX | `0.05` | Adjusts the Happy Ghast flight speed (clamped to `0.0~5.0` on the consumer side, i.e. 0~100 times vanilla) |

### ENTITY (7 rules) → [entity.md](02-rules/entity.md)

| Register name | Category | Default value | One-liner |
|---|---|---|---|
| `NoSqueeze` | ENTITY + VILLAGER | `false` | Entities on the list take no part in entity cramming and take no squeeze damage (the list is maintained with `/ec c NoSqueeze`) |
| `MobNoSpawn` | ENTITY + COMMAND | `false` | Entities on the list can no longer be **created** — natural spawn, spawners, spawn eggs, `/summon`, breeding and structure generation are all covered; already existing mobs are untouched |
| `PortalPiglinNoSpawn` | ENTITY + COMMAND | `false` | Inside a bound region, nether portals no longer spawn zombified piglins on random tick |
| `ZombieVillagerHelp` | VILLAGER + MONSTER | `false` | Put a name tag reading `help` on a zombie villager and the vanilla curing process completes at once (profession / trades / reputation are kept) |
| `PiglinGoldBlockMultiply` | ENTITY | `false` | Piglins accept gold blocks: one gold block counts as "nine pickups worth of rolls" and produces nine loot rolls |
| `PiglinBarterDelay` | ENTITY | `6` | The number of seconds a piglin spends "admiring the gold ingot" before bartering |
| `PiglinGoldBlockMultiplyPunishment` | ENTITY | `9` | Extra multiplier applied to the wait time on gold block barters (the price paid for the `PiglinGoldBlockMultiply` payoff) |

### MONSTER (1 rule) → [monster.md](02-rules/monster.md)

| Register name | Category | Default value | One-liner |
|---|---|---|---|
| `NoBrainOnPortal` | MONSTER | `false` | After a mob walks through a nether portal, every piece of AI in the `goal` and `target` selectors is cleared, leaving it "brainless" |

### PLANT (1 rule) → [plant.md](02-rules/plant.md)

| Register name | Category | Default value | One-liner |
|---|---|---|---|
| `VillagerBoneMealFlower` | PLANT | `false` | Bone-mealing a grass block additionally spreads / generates flowers |

### ANIMAL (1 rule) → [animal.md](02-rules/animal.md)

| Register name | Category | Default value | One-liner |
|---|---|---|---|
| `InfiniteShear` | ANIMAL | `false` | A sheep returns to the woolly state right after being sheared, so it can be sheared forever |

### PLAYER (2 rules) → [player.md](02-rules/player.md)

| Register name | Category | Default value | One-liner |
|---|---|---|---|
| `PlayerHeadSitting` | PLAYER | `false` | Empty-hand right-click another player to sit on their head; the rider sneaks to get down (details in the `PlayerHeadSitting` config section) |
| `PlayerBlockSitting` | PLAYER | `false` | Empty-hand right-click stairs / slabs to sit on them, Shift to get up (whether sitting is allowed is decided by the player's **own** client switch) |

### VILLAGER (14 rules) → [villager.md](02-rules/villager.md)

| Register name | Category | Default value | One-liner |
|---|---|---|---|
| `VillagerBedBinding` | VILLAGER | `false` | Use a lead named `bed` to select "villager + bed" and change the bed this villager is bound to |
| `VillagerWorkBlockBinding` | VILLAGER | `false` | Use a lead named `work` to change the work block the villager is bound to |
| `VillagerBatchBedBinding` | VILLAGER + COMMAND | `false` | One command binds every villager in range to its nearest bed |
| `VillagerAutoTrader` | VILLAGER | **`true`** | Write an item / enchantment on a name tag (`绿宝石` / `mending` / `331a`) and right-click the villager to **really re-roll** with vanilla probabilities until the target is rolled |
| `VillagerAutoLockHitTrader` | VILLAGER | `false` | Once the target is hit the villager is automatically "locked" (one use of the first trade + 1 XP), so nobody else can buy it out / re-roll it |
| `VillagerSilentTrader` | VILLAGER | `false` | After a refresh is triggered (when the task is **enqueued**) the custom name left by the name tag is cleared at once; when the refresh is rejected the name is kept so it can be renamed and retried on the spot |
| `VillagerOneTickTraderCount` | VILLAGER | `500` | How many simulations each task runs per tick during an auto refresh (`50 / 100 / 500 / 1000`) |
| `VillagerHardWorking` | VILLAGER | `1` | The "restock multiplier" when a villager restocks (1 = vanilla, 2 = it can sell twice the original amount after restocking) |
| `VillagerInfiniteTrade` | VILLAGER | `false` | Villager trades never sell out (`uses` is not incremented) |
| `TradeSearch` | VILLAGER + COMMAND | `false` | `/ec c TradeSearch item\|book <keyword>` looks up what the villagers with "locked trades" sell across the whole save |
| `VillagerMoveController` | VILLAGER | `false` | Use a lead named `move` to order a villager to walk to a given position |
| `VillagerToolsmithExchange` | VILLAGER | `false` | The high-level toolsmith / weaponsmith trades change from "diamond → emerald" to "emerald → diamond" |
| `VillagerEmeraldDecompose` | VILLAGER | `false` | Allows paying with emerald blocks; they are split into emeralds on settlement |
| `VillagerBookshelfDecompose` | VILLAGER | `false` | Allows paying with bookshelves; they are split into books on settlement |

### BLOCK (10 rules) → [block.md](02-rules/block.md)

| Register name | Category | Default value | One-liner |
|---|---|---|---|
| `BlockMiningResistance` | BLOCK + COMMAND | `false` | Blocks on the list get a custom mining resistance (the value table is maintained with `/ec c BlockMiningResistance`) |
| `BlockCollectable` | BLOCK + COMMAND | `false` | Blocks on the list become "collectable" (even ones that drop nothing in vanilla do drop) |
| `BlockExplosionResistance` | BLOCK + COMMAND | `false` | Blocks on the list get a custom explosion resistance |
| `BlockStrongPower` | BLOCK + COMMAND | `false` | Blocks on the list count as redstone conductors and take part in redstone strong powering |
| `TntNoDestroyDrops` | BLOCK | `false` | TNT explosions no longer destroy item drops lying on the ground |
| `BoxCrafting` | BLOCK | `false` | A shulker box filled with one and the same item counts as "27 full stacks" when crafting |
| `PortalBinding` | BLOCK | `false` | Binds one region to another nether portal; walking in sends you to the bound target by force (the tier = who can be teleported by force) |
| `PortalBindingEnhanced` | BLOCK | `false` | Portal binding enhancement: skips "a portal must already exist at the target"; with no portal it finds a foothold and builds one itself |
| `EndPortalGravityBlock` | BLOCK + COMMAND | `false` | Blocks on the list fall like sand when they are inside the 5×5×5 area of an end portal and left hanging in the air |
| `FurnaceDirtyHighlight` | BLOCK | `false` | When the input slot holds something this furnace type has no recipe for, the furnace is outlined on the client (the fuel slot is not checked) |

---

### Category distribution (for cross-checking)

| Category (enum) | Rules | Notes |
|---|---|---|
| `MOD_SETTING` | 2 | Strictly speaking "mod settings" rather than gameplay rules |
| `FIX` | 2 | `HappyGhastSpeed` reads like an entity rule; its enum category is `FIX` |
| `ENTITY` | 7 | |
| `MONSTER` | 1 | |
| `PLANT` | 1 | |
| `ANIMAL` | 1 | |
| `PLAYER` | 2 | |
| `VILLAGER` | 14 | The most complex category, see [villager.md](02-rules/villager.md) |
| `BLOCK` | 10 | |
| `COMMAND` | 9 | Not a functional domain: a hand-assigned marker so that `/ec list command` shows the rules the maintainers decided belong there (**counted again** on top of the categories above). Because the marker is assigned by hand, a rule can carry a `/ec c` subcommand without it — `PortalBinding` and `PortalBindingEnhanced` are tagged `BLOCK` only<br>`MobNoSpawn`, `PortalPiglinNoSpawn`, `VillagerBatchBedBinding`, `TradeSearch`, the four block list rules, `EndPortalGravityBlock` |

### Enum constant ↔ register name (for digging through the code)

For most rules the enum constant is just the register name in upper snake case (`InfiniteShear` → `INFINITE_SHEAR`).
The following ones **are not**, so a grep easily comes up empty:

| Register name | Enum constant |
|---|---|
| `Language` | `SETTING_LANGUAGE` |
| `RuleCommand` | `SETTING_EC_COMMAND_PERMISSION` |
| `NoSqueeze` | `VILLAGER_NO_SQUEEZE` |
| `VillagerBedBinding` | `VILLAGER_BED_RESET` |
| `VillagerWorkBlockBinding` | `VILLAGER_WORK_BLOCK_RESET` |
| `VillagerBatchBedBinding` | `VILLAGER_BATCH_BED_RESET` |
| `VillagerSilentTrader` | `VILLAGER_SILENT_AUTO_REROLL_TRADER` |
| `TradeSearch` | `VILLAGER_TRADE_SEARCH` |
| `EndPortalGravityBlock` | `ENDER_PORTAL_GRAVITY_BLOCK` |
| `TntNoDestroyDrops` | `TNT_NO_DESTROY_DROPS` |

### Complexity distribution (a writing convention, not a concept in the code)

| Complexity | Rules | How it is handled |
|---|---|---|
| Simple | 20 | 10~20 lines in the category file |
| Medium | 14 | 30~50 lines in the category file |
| Complex | 6 | only a summary in the category file; the full text lives in [`03-deep-dives/`](03-deep-dives/) |

Complex rules: `VillagerAutoTrader`, `TradeSearch`, `PortalBinding`, `PlayerHeadSitting`, `BoxCrafting`,
`FurnaceDirtyHighlight`.

---

<a id="中文"></a>

## 中文

> 数据来源：`common/src/server/java/com/villagerdetails/rule/type/RuleType.java`（逐条读完的枚举声明）。
> **一共 40 条**。「默认值」= 枚举里的 `state` 参数，也就是「这条规则从未被改过时」的状态。
> 「一句话说明」只回答"它干什么"，实现细节点进对应分类文件。

---

### 目录

- [设置 settings.md](02-rules/settings.md) — 2 条
- [修复 fix.md](02-rules/fix.md) — 2 条
- [实体 entity.md](02-rules/entity.md) — 7 条
- [怪物 monster.md](02-rules/monster.md) — 1 条
- [植物 plant.md](02-rules/plant.md) — 1 条
- [动物 animal.md](02-rules/animal.md) — 1 条
- [玩家 player.md](02-rules/player.md) — 2 条
- [村民 villager.md](02-rules/villager.md) — 14 条
- [方块 block.md](02-rules/block.md) — 10 条

> 归属原则：一条规则只在**主分类**文件里详细写，其它关联分类用一句话带过（`NoSqueeze` 的枚举分类是
> `ENTITY + VILLAGER`，正文在 `entity.md`；`ZombieVillagerHelp` 是 `VILLAGER + MONSTER`，正文在 `entity.md`）。

---

### MOD_SETTING（2 条）→ [settings.md](02-rules/settings.md)

| 注册名 | 分类 | 默认值 | 一句话说明 |
|---|---|---|---|
| `Language` | MOD_SETTING | `zh_cn` | 模组「服务端语言」：决定所有 `/ec` 回复、规则名 / 描述用哪种语言 |
| `RuleCommand` | MOD_SETTING | `owner` | `/ec` 命令的全局权限档位（谁能看规则列表、谁能改规则） |

### FIX（2 条）→ [fix.md](02-rules/fix.md)

| 注册名 | 分类 | 默认值 | 一句话说明 |
|---|---|---|---|
| `GlobalSpeedBypass` | FIX | `false` | 关掉「moved too quickly / wrongly」的服务端回拉，只放行玩家自身移动与被玩家控制的乐魂 |
| `HappyGhastSpeed` | FIX | `0.05` | 调乐魂的飞行速度（消费侧夹在 `0.0~5.0`，即原版 0~100 倍） |

### ENTITY（7 条）→ [entity.md](02-rules/entity.md)

| 注册名 | 分类 | 默认值 | 一句话说明 |
|---|---|---|---|
| `NoSqueeze` | ENTITY + VILLAGER | `false` | 名单里的实体不参与实体挤压、不吃挤压伤害（名单由 `/ec c NoSqueeze` 维护） |
| `MobNoSpawn` | ENTITY + COMMAND | `false` | 名单里的实体不再被**创建**（自然刷怪、刷怪笼、刷怪蛋、`/summon`、繁殖、结构生成都拦）；已存在的实体不受影响 |
| `PortalPiglinNoSpawn` | ENTITY + COMMAND | `false` | 绑定的区域里，下界传送门随机刻不再刷僵尸猪灵 |
| `ZombieVillagerHelp` | VILLAGER + MONSTER | `false` | 命名牌写 `help` 贴僵尸村民，立刻走完原版治愈流程（保留职业 / 交易 / 声望） |
| `PiglinGoldBlockMultiply` | ENTITY | `false` | 猪灵收金块：1 个金块当作"跑 9 次拾取"，产出 9 份战利品 |
| `PiglinBarterDelay` | ENTITY | `6` | 猪灵交易前"欣赏金锭"的等待秒数 |
| `PiglinGoldBlockMultiplyPunishment` | ENTITY | `9` | 金块交易时等待时长额外乘的倍数（惩罚 `PiglinGoldBlockMultiply` 的爽快感） |

### MONSTER（1 条）→ [monster.md](02-rules/monster.md)

| 注册名 | 分类 | 默认值 | 一句话说明 |
|---|---|---|---|
| `NoBrainOnPortal` | MONSTER | `false` | 怪物穿过下界传送门后，goal 与 target 两个选择器里的 AI 全部被清空，变成"无脑"状态 |

### PLANT（1 条）→ [plant.md](02-rules/plant.md)

| 注册名 | 分类 | 默认值 | 一句话说明 |
|---|---|---|---|
| `VillagerBoneMealFlower` | PLANT | `false` | 骨粉催草方块时，额外蔓延 / 生成花朵 |

### ANIMAL（1 条）→ [animal.md](02-rules/animal.md)

| 注册名 | 分类 | 默认值 | 一句话说明 |
|---|---|---|---|
| `InfiniteShear` | ANIMAL | `false` | 羊剪完毛立刻恢复有毛状态，可无限剪 |

### PLAYER（2 条）→ [player.md](02-rules/player.md)

| 注册名 | 分类 | 默认值 | 一句话说明 |
|---|---|---|---|
| `PlayerHeadSitting` | PLAYER | `false` | 空手右键其他玩家坐到他头顶；乘客潜行下坐（细项见 `PlayerHeadSitting` 配置节） |
| `PlayerBlockSitting` | PLAYER | `false` | 空手右键楼梯 / 半砖坐上去，Shift 起身（能否坐由玩家**自己**的客户端开关决定） |

### VILLAGER（14 条）→ [villager.md](02-rules/villager.md)

| 注册名 | 分类 | 默认值 | 一句话说明 |
|---|---|---|---|
| `VillagerBedBinding` | VILLAGER | `false` | 用命名为 `bed` 的拴绳选「村民 + 床」，改这只村民绑定的床 |
| `VillagerWorkBlockBinding` | VILLAGER | `false` | 用命名为 `work` 的拴绳改村民绑定的工作方块 |
| `VillagerBatchBedBinding` | VILLAGER + COMMAND | `false` | 一条指令把范围内所有村民绑到最近的床 |
| `VillagerAutoTrader` | VILLAGER | **`true`** | 命名牌写物品 / 附魔（`绿宝石` / `mending` / `331a`），右键村民按原版概率**真实重掷**到刷出目标 |
| `VillagerAutoLockHitTrader` | VILLAGER | `false` | 刷中之后自动"锁"住这只村民（第一笔交易用一次 + 给 1 点经验），防止被别人买走 / 再次重掷 |
| `VillagerSilentTrader` | VILLAGER | `false` | 触发刷新后（任务**入队时**）立刻清掉命名牌留的名字；刷新被拒绝时保留名字，方便原地改名重试 |
| `VillagerOneTickTraderCount` | VILLAGER | `500` | 自动刷新每 tick 每个任务模拟多少次（`50 / 100 / 500 / 1000`） |
| `VillagerHardWorking` | VILLAGER | `1` | 村民补货时的"恢复倍率"（1 = 原版，2 = 补货后能卖原来的两倍） |
| `VillagerInfiniteTrade` | VILLAGER | `false` | 村民的交易永不售罄（`uses` 不自增） |
| `TradeSearch` | VILLAGER + COMMAND | `false` | `/ec c TradeSearch item\|book <关键词>` 查全存档"已锁定交易"的村民卖什么 |
| `VillagerMoveController` | VILLAGER | `false` | 用命名为 `move` 的拴绳命令村民走到指定位置 |
| `VillagerToolsmithExchange` | VILLAGER | `false` | 工具匠 / 武器匠的高等级交易由「钻石 → 绿宝石」改成「绿宝石 → 钻石」 |
| `VillagerEmeraldDecompose` | VILLAGER | `false` | 允许用绿宝石块付款，结算时自动拆成绿宝石 |
| `VillagerBookshelfDecompose` | VILLAGER | `false` | 允许用书架付款，结算时自动拆成书 |

### BLOCK（10 条）→ [block.md](02-rules/block.md)

| 注册名 | 分类 | 默认值 | 一句话说明 |
|---|---|---|---|
| `BlockMiningResistance` | BLOCK + COMMAND | `false` | 名单里的方块拥有自定义挖掘抗性（数值表由 `/ec c BlockMiningResistance` 维护） |
| `BlockCollectable` | BLOCK + COMMAND | `false` | 名单里的方块变得"可采集"（连原版不掉落的也掉） |
| `BlockExplosionResistance` | BLOCK + COMMAND | `false` | 名单里的方块拥有自定义爆炸抗性 |
| `BlockStrongPower` | BLOCK + COMMAND | `false` | 名单里的方块当作红石导体，参与红石强充能 |
| `TntNoDestroyDrops` | BLOCK | `false` | TNT 爆炸不炸毁地上的掉落物 |
| `BoxCrafting` | BLOCK | `false` | 装满同一物品的潜影盒可以当"27 个满堆"参与合成 |
| `PortalBinding` | BLOCK | `false` | 把一块区域绑到另一扇下界门，进门强制送到绑定目标（档位 = 谁能被强制传送） |
| `PortalBindingEnhanced` | BLOCK | `false` | 传送门绑定增强：跳过"目标位置必须已有门"，没有门就自己找落脚点建 |
| `EndPortalGravityBlock` | BLOCK + COMMAND | `false` | 名单里的方块若在末地传送门 5×5×5 范围内且悬空，会像沙子一样下落 |
| `FurnaceDirtyHighlight` | BLOCK | `false` | 输入槽里有东西但这只熔炉烧不了它时，在客户端把这只熔炉框成高亮（不检查燃料槽） |

---

### 分类分布（核对用）

| 分类（枚举） | 规则数 | 说明 |
|---|---|---|
| `MOD_SETTING` | 2 | 严格来说是"模组设置"而非玩法规则 |
| `FIX` | 2 | `HappyGhastSpeed` 读起来像实体规则，枚举分类是 `FIX` |
| `ENTITY` | 7 | |
| `MONSTER` | 1 | |
| `PLANT` | 1 | |
| `ANIMAL` | 1 | |
| `PLAYER` | 2 | |
| `VILLAGER` | 14 | 最复杂的一类，见 [villager.md](02-rules/villager.md) |
| `BLOCK` | 10 | |
| `COMMAND` | 9 | 不是功能域：是手工指定的标记，让 `/ec list command` 列出维护者认定属于这一类的规则（与上面的分类**重复计数**）。因为是手工指定，带 `/ec c` 子命令的规则不一定带这个标记 —— `PortalBinding`、`PortalBindingEnhanced` 只归 `BLOCK`<br>`MobNoSpawn`、`PortalPiglinNoSpawn`、`VillagerBatchBedBinding`、`TradeSearch`、四条方块名单类、`EndPortalGravityBlock` |

### 枚举常量 ↔ 注册名（挖代码用）

大多数规则的枚举常量就是注册名的大写下划线形式（`InfiniteShear` → `INFINITE_SHEAR`）。
下面这些**不是**，grep 时容易扑空：

| 注册名 | 枚举常量 |
|---|---|
| `Language` | `SETTING_LANGUAGE` |
| `RuleCommand` | `SETTING_EC_COMMAND_PERMISSION` |
| `NoSqueeze` | `VILLAGER_NO_SQUEEZE` |
| `VillagerBedBinding` | `VILLAGER_BED_RESET` |
| `VillagerWorkBlockBinding` | `VILLAGER_WORK_BLOCK_RESET` |
| `VillagerBatchBedBinding` | `VILLAGER_BATCH_BED_RESET` |
| `VillagerSilentTrader` | `VILLAGER_SILENT_AUTO_REROLL_TRADER` |
| `TradeSearch` | `VILLAGER_TRADE_SEARCH` |
| `EndPortalGravityBlock` | `ENDER_PORTAL_GRAVITY_BLOCK` |
| `TntNoDestroyDrops` | `TNT_NO_DESTROY_DROPS` |

### 复杂度分布（写作口径，不是代码里的概念）

| 复杂度 | 条数 | 处理方式                                                      |
|--------|------|---------------------------------------------------------------|
| 简单   | 20   | 分类文件里 10~20 行                                           |
| 中等   | 14   | 分类文件里 30~50 行                                           |
| 复杂   | 6    | 分类文件里只给摘要，正文进 [`03-deep-dives/`](03-deep-dives) |

复杂规则：`VillagerAutoTrader`、`TradeSearch`、`PortalBinding`、`PlayerHeadSitting`、`BoxCrafting`、
`FurnaceDirtyHighlight`。
