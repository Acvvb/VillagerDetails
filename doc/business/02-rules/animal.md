# Animals (ANIMAL)

The single `ANIMAL` rule: shearing a sheep no longer costs the sheep its wool state.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> This file covers the **1 rule** whose main category in `RuleType` is `ANIMAL`.

---

#### `InfiniteShear` (infinite sheep shearing)

- **Register name**: `InfiniteShear`
- **Category**: `ANIMAL`
- **Default value**: `false`
- **Commands**: `/ec InfiniteShear` (switch); **no** `/ec c` subcommand
- **Config class**: none
- **Trigger**: **TAIL** of `Sheep#shear` (after the whole vanilla shearing flow has run)
- **How it works**: once shearing succeeds, vanilla sets the sheep's `sheared` state to `true` (the sheep looks sheared and the wool regrow timer stops);
  here the state is set back to `false` when the method returns, so **the drops, sound effects and statistics have all run vanilla once, yet the sheep is woolled again immediately**.
  With the rule off it simply `return`s, as if this mixin did not exist.
- **Key files**: `mixin/entity/animal/SheepMixin.java`
- **Dependencies**: none
- **Client behaviour**: none (pure server-side state; the client sees "wool is back the moment it was cut")
- **Design rules and boundaries**:
  1. Shearing **does not deplete the sheep's wool state**: the rule only hooks the vanilla shear path, so death drops and dyeing / the sheep's colour are untouched, and the amount and colour of the dropped wool are still vanilla's decision.
  2. Because the state is reset, the vanilla timer that regrows wool by eating a grass block never becomes meaningful for this sheep
     (it is permanently "unsheared"), and the eat-grass-and-regrow behaviour is therefore **not** triggered over and over.
  3. A sheep that is already sheared (when the rule is switched on) does not grow wool out of nowhere: shear it once first, or wait for the vanilla regrowth.

---

<a id="中文"></a>

## 中文

> 本文件覆盖 `RuleType` 里主分类为 `ANIMAL` 的 **1 条**规则。

---

#### `InfiniteShear`（羊无限剪毛）

- **注册名**：`InfiniteShear`
- **分类**：`ANIMAL`
- **默认值**：`false`
- **命令**：`/ec InfiniteShear`（开关）；**无** `/ec c` 子命令
- **配置类**：无
- **触发**：`Sheep#shear` 的 **TAIL**（原版剪毛流程完整跑完之后）
- **实现思路**：剪毛成功后，原版会把羊的 `sheared` 状态置为 `true`（显示成无毛、羊毛不再生长计时）；
  这里在方法返回时把它改回 `false`，于是**掉落物、音效、统计都已经照原版走完一次，羊却立刻变回有毛**。
  规则关闭时直接 `return`，等于没有这个 mixin。
- **关键文件**：`mixin/entity/animal/SheepMixin.java`
- **依赖**：无
- **客户端行为**：无（纯服务端状态；客户端看到的是"剪完立刻有毛"）
- **设计约定与边界**：
  1. **剪毛不扣羊毛状态**：规则只改**剪毛**这条原版路径，不影响死亡掉落、不影响染色 / 羊的颜色；剪下来的羊毛数量与颜色仍由原版决定。
  2. 因为状态被重置，原版"羊毛靠吃草方块重新长出来"的那套计时对这只羊不再有意义
     （它永远是"未剪"状态），也因此**不会**重复触发"吃草长毛"的行为。
  3. 对已经是无毛状态的羊（规则刚开启时）不会凭空长毛，需要先剪一次或等原版长回来。
