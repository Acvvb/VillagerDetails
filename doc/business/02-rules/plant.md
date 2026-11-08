# Plants (PLANT)

The single `PLANT` rule: bone meal on a grass block can grow overworld flowers, and only there.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> This file covers the **1 rule** whose main category in `RuleType` is `PLANT`.
>
> ⚠️ **The implementation of this rule lives in the version layer**: `v26.2/` and `v26.3/` each carry a copy of `mixin/plant/GrassBlockBoneMealMixin.java`, and it cannot be found under `common`. Business documents only ever refer to it by package name.

---

#### `VillagerBoneMealFlower` (bone meal grows flowers)

- **Register name**: `VillagerBoneMealFlower` (enum constant `VILLAGER_BONE_MEAL_FLOWER`)
- **Category**: `PLANT`
- **Default value**: `false`
- **Commands**: `/ec VillagerBoneMealFlower` (switch); **no** `/ec c` subcommand
- **Config class**: none (the flower candidate list is **hard-coded** in a constant array inside the mixin; adding another flower type means changing code)
- **Trigger**: HEAD of `GrassBlock#performBonemeal` (`cancellable`)
- **How it works**:
  1. Three preconditions; if any of them fails, vanilla runs: **the rule is on**, the block is a grass block, **the dimension is the overworld**.
  2. When they all hold, `ci.cancel()` drops vanilla and our own pass runs instead:
     still the vanilla loop ("at most 128 attempts, a random offset per attempt, the block underneath must be a grass block, the target position must be free of collision boxes");
     an air position gets a feature from the **fixed overworld flower list** with probability 1/8,
     everything else places grass (`VegetationPlacements.GRASS_BONEMEAL`); a position that is already short grass gets ripened with probability 1/10.
  3. **The candidates are fixed at 5 overworld flowers** (written in `OVERWORLD_FLOWER_KEYS`):
     dandelion / poppy, flower forest (many flower types), swamp (blue orchid), plains (tulip / cornflower / oxeye daisy), meadow (cornflower / daisy / lily of the valley).
     So the effect is "bone meal produces flowers more easily, and the flower types span several biomes".
- **Key files**: `v26.2/…/mixin/plant/GrassBlockBoneMealMixin.java`, `v26.3/…/mixin/plant/GrassBlockBoneMealMixin.java`
  (the two copies are identical; one of them is compiled in with the version)
- **Dependencies**: no other rules; uses `Registries.FEATURE` / `Registries.PLACED_FEATURE` registry lookups
- **Client behaviour**: none (pure server side; the client only sees the flowers appear)
- **Design rules and boundaries**:
  1. **Grass block in the overworld only**: dirt, rooted dirt, moss blocks and the nether / end grass block variants all keep the vanilla logic.
  2. **The flower list is a code constant** with no configuration option; adding a flower means editing the mixin and recompiling.
  3. This is a **whole rewrite** rather than "a higher probability layered on top of vanilla", so a future change to the vanilla bone meal algorithm is not picked up here
     — and the copies in the two version directories have to be changed together.

---

<a id="中文"></a>

## 中文

> 本文件覆盖 `RuleType` 里主分类为 `PLANT` 的 **1 条**规则。
>
> ⚠️ **这条规则的实现在版本层**：`v26.2/` 与 `v26.3/` 各有一份 `mixin/plant/GrassBlockBoneMealMixin.java`，
> `common` 里搜不到。业务文档提到它时只写包名。

---

#### `VillagerBoneMealFlower`（骨粉催花）

- **注册名**：`VillagerBoneMealFlower`（枚举常量 `VILLAGER_BONE_MEAL_FLOWER`）
- **分类**：`PLANT`
- **默认值**：`false`
- **命令**：`/ec VillagerBoneMealFlower`（开关）；**无** `/ec c` 子命令
- **配置类**：无（花的候选列表**硬编码**在 mixin 里的常量数组，想加花丛要改代码）
- **触发**：`GrassBlock#performBonemeal` 的 HEAD（`cancellable`）
- **实现思路**：
  1. 三个前置条件，任一不满足就走原版：**规则开启**、方块是草方块、**维度是主世界**。
  2. 条件都满足就 `ci.cancel()` 掉原版，用自己的实现跑一遍：
     仍是原版那套"最多 128 次尝试、每次随机偏移、脚下必须是草方块、目标位置不能有碰撞箱"的循环；
     空气位上以 1/8 概率从**固定的主世界花丛列表**里随机选一个 feature 生成，
     其余情况放置草（`VegetationPlacements.GRASS_BONEMEAL`）；已经是短草的位置以 1/10 概率催熟它。
  3. **候选固定为 5 个主世界花丛**（写在 `OVERWORLD_FLOWER_KEYS`）：
     蒲公英 / 虞美人、繁花森林（多种花）、沼泽（兰花）、平原（郁金香 / 矢车菊 / 滨菊）、草甸（矢车菊 / 雏菊 / 铃兰）。
     所以效果是"骨粉更容易出花，且花的种类跨了几个生物群系"。
- **关键文件**：`v26.2/…/mixin/plant/GrassBlockBoneMealMixin.java`、`v26.3/…/mixin/plant/GrassBlockBoneMealMixin.java`
  （两份内容一致，随版本编入其中一份）
- **依赖**：无其它规则；用到 `Registries.FEATURE` / `Registries.PLACED_FEATURE` 的注册表查询
- **客户端行为**：无（纯服务端；客户端只看到花长出来）
- **设计约定与边界**：
  1. **只对草方块 + 主世界生效**：泥土、缠根泥土、苔藓块、下界 / 末地的草方块引用都走原版逻辑。
  2. **花丛列表是代码常量**，没有配置项；加花必须改 mixin 并重新编译。
  3. 这是**整段重写**而不是"在原版基础上加概率"，所以原版将来改了骨粉算法，这里不会自动跟上
     —— 两个版本目录的副本也要一起改。
