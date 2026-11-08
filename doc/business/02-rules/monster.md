# Monsters (MONSTER)

The one monster rule: after a monster changes dimension its goal and target selectors are stripped, so it stops making decisions.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> This file covers the **1** rule whose primary category in `RuleType` is `MONSTER`.
> (`ZombieVillagerHelp` also carries the `MONSTER` category, but its main text is in [`entity.md`](entity.md).)

---

#### `NoBrainOnPortal` (no brain after a dimension change)

- **Register name**: `NoBrainOnPortal`
- **Category**: `MONSTER`
- **Default value**: `false`
- **Commands**: `/ec NoBrainOnPortal` (toggle); **no** `/ec c` subcommand
- **Config class**: none
- **Trigger**: the Fabric event `ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL` (after an entity changes
  dimension). **Not a Mixin**: this rule does not need to intercept a vanilla method, it only has to touch the entity's
  selectors at the moment "the dimension change is done".
- **How it works**:
  1. an entity finished changing dimension → continue only when **the new entity is a `Monster`** and the rule is on.
  2. strip the targets out of the monster's **two selectors** one by one: `goalSelector` (day-to-day behaviour: walking,
     attacking, hiding from the sun…) and `targetSelector` (who to attack). After that the monster still moves (physics,
     gravity and collisions work as usual) but no longer makes any decision of its own — "brainless".
  3. **Every goal has to be `removeGoal`-ed; do not call `clear()` on `getAvailableGoals()`**: vanilla `GoalSelector`
     maintains both `availableGoals` (the Set that `getAvailableGoals()` exposes) and `goalMap` (the Map it really
     iterates when ticking). Clearing only the Set leaves the goals in `goalMap` still hit on tick — as good as not
     clearing them. Snapshot with `toList()` before deleting, so mutating the collection during iteration cannot throw
     `ConcurrentModificationException`.
  4. reading those two fields needs an `@Accessor` (`MobInterfaceMixin`), because they are `protected` on `Mob`.
- **Key files**: `event/impl/register/NoBrainOnPortalHandler.java` (wiring + the clearing logic),
  `mixin/monster/noBrainOnPortal/MobInterfaceMixin.java` (the two `@Accessor`s)
- **Dependencies**: no other rules; the event is registered automatically by `ServerEventManager`'s package scan
- **Client behaviour**: none (pure server state; the client can only observe "the monster stopped moving")
- **Design rules and boundaries**:
  1. **The trigger condition really is "has changed dimension", not just "walked through a portal"**: end portals,
     cross-dimension `/tp`, being carried around by another mod all trigger it; conversely, **being pushed into a portal
     and back within the same dimension** triggers it twice as well (the second time the targets are long gone, so
     nothing happens).
  2. **Irreversible**: the removed goals do not come back. An entity reload (chunk unload and load again) or a server
     restart restores vanilla behaviour — in other words the effect of this rule only lasts until "this monster is
     unloaded".
  3. Only `Monster` counts: neutral mobs (endermen are `Monster` and do get cleared), animals, villagers, and non-Monster
     subclasses of bosses are unaffected.

---

<a id="中文"></a>

## 中文

> 本文件覆盖 `RuleType` 里主分类为 `MONSTER` 的 **1 条**规则。
> （`ZombieVillagerHelp` 也带 `MONSTER` 分类，但它的正文在 [`entity.md`](entity.md)。）

---

#### `NoBrainOnPortal`（过门失智）

- **注册名**：`NoBrainOnPortal`
- **分类**：`MONSTER`
- **默认值**：`false`
- **命令**：`/ec NoBrainOnPortal`（开关）；**无** `/ec c` 子命令
- **配置类**：无
- **触发**：Fabric 事件 `ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL`（实体换维度之后）。
  **不是 Mixin**：这条规则不需要拦截原版方法，只需要在"换完维度"这一刻动一动实体的选择器。
- **实现思路**：
  1. 实体换维度完成 → 只在**新实体是 `Monster`** 且规则开启时继续。
  2. 把怪物的**两个选择器**里的目标逐条摘掉：`goalSelector`（日常行为：走路、攻击、躲阳光…）与
     `targetSelector`（选谁当攻击目标）。清空之后怪物仍然会动（物理、重力、碰撞照常），
     但不再主动做任何决策，也就是"无脑"。
  3. **必须逐条 `removeGoal`，不能对 `getAvailableGoals()` 调 `clear()`**：原版 `GoalSelector` 内部同时维护
     `availableGoals`（Set，`getAvailableGoals()` 暴露的那个）和 `goalMap`（tick 时真正遍历的 Map）。
     只清 Set 的话 `goalMap` 里的目标照样会被 tick 命中，等于白清。
     遍历时先 `toList()` 做快照再删，避免遍历中改集合抛 `ConcurrentModificationException`。
  4. 读取这两个字段需要 `@Accessor`（`MobInterfaceMixin`），因为它们在 `Mob` 上是 `protected`。
- **关键文件**：`event/impl/register/NoBrainOnPortalHandler.java`（接线 + 清空逻辑）、
  `mixin/monster/noBrainOnPortal/MobInterfaceMixin.java`（两个 `@Accessor`）
- **依赖**：无其它规则；事件由 `ServerEventManager` 扫包自动注册
- **客户端行为**：无（纯服务端状态，客户端只能观察"怪物不动了"）
- **设计约定与边界**：
  1. **触发条件其实是"换过维度"，不只是"走传送门"**：末地传送门、跨维度 `/tp`、被其它模组搬运都会触发；
     反之，**在同一维度内被推进传送门再回来**这种也会触发两次（第二次时目标早被清空，无事发生）。
  2. **不可逆**：清掉的 goal 不会回来。实体重载（区块卸载再加载）、服务器重启后会恢复原版行为
     —— 也就是说这条规则的效果只维持到"这只怪物被卸载"为止。
  3. 只认 `Monster`：中立生物（末影人虽然属于 Monster，会被清）、动物、村民、Boss 里的非 Monster 子类不受影响。
