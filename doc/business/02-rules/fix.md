# Fixes (FIX)

Two server-side behaviour fixes: skipping the vanilla movement check where it is wrong, and letting a player tune the fly speed of the Happy Ghast they ride.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> This file covers the **2** rules of the `FIX` category in `RuleType`.
> Both live under `mixin/fix/` (and `mixin/entity/happyghast/`); both are **server-side behaviour fixes** and neither has a client UI.
>
> ⚠️ Category note: `HappyGhastSpeed` reads like an entity rule, but the enum files it under `FIX`; `GlobalSpeedBypass`
> is also filed under `FIX`, yet what it actually affects is the **movement check on players / vehicles**
> (`net.minecraft.server.network.ServerGamePacketListenerImpl`).

---

#### `GlobalSpeedBypass` (no snap-back when moving fast)

- **Register name**: `GlobalSpeedBypass` (enum constant `GLOBAL_SPEED_BYPASS`)
- **Category**: `FIX`
- **Default value**: `false`
- **Allowed values**: `false` / `true`
- **Commands**: `/ec GlobalSpeedBypass` (view), `/ec GlobalSpeedBypass true|false` (toggle); **no** `/ec c` subcommand
- **Config class**: none (a pure switch, no detail section)
- **Trigger**: three injection points, all on the **server-side packet receiving / movement handling** path:

  | # | Injection point | Effect |
  |---|---|---|
  | ① | `ServerGamePacketListenerImpl#shouldCheckPlayerMovement` (HEAD, cancellable) | **the player's own movement**: makes the server "skip this movement check" |
  | ② | `ServerGamePacketListenerImpl#handleMoveVehicle` (HEAD, cancellable) | **vehicle movement (the Happy Ghast the player rides and controls)**: the whole method is taken over and the client coordinates land as-is |
  | ③ | the `Logger.warn` calls in `ServerGamePacketListenerImpl#handleMovePlayer` / `handleMoveVehicle` (Redirect) | **log silencing**: swallows `moved too quickly` / `moved wrongly` |
  | ④ | `PersistentEntitySectionManager#addEntityUuid` (HEAD) | also suppresses the "duplicate UUID" warning (unrelated to movement, see "Design rules and boundaries") |

- **How it works**:
  1. **Trigger condition = rule enabled + scene check**. The scene check is a single predicate
     (`villagerdetails$isBypassAllowed()`); log silencing and the real pass-through share it, so it can never be the
     case that "the check still runs while the log is swallowed".
  2. **The player's own movement (①)**: nothing is changed; the server simply **no longer blocks** this movement —
     the two vanilla rollbacks (over the threshold → send the old position back; residual too large → snap back to the
     old position) therefore never trigger. This branch covers "the person themselves is flying": elytra, modded
     flight, creative flight.
  3. **Vehicle movement (②)**: the fallback branch of vanilla `handleMoveVehicle` is **inlined**, so silencing the log
     alone changes nothing — the snap-back still happens. This branch therefore **cancels the whole method** and lets
     `VehicleMoveTrust.apply` land the client coordinates as-is, filling in the side effects vanilla would no longer
     perform after the cancel: normalising position and rotation, on-ground state and fall damage, distance stats,
     **chunk following (`getChunkSource().move`; miss it and terrain stops loading / the player sticks in place)**,
     known movement and active time (skip those and fast flight is treated as being AFK).
     The gates are still there: coordinates containing `NaN`/`±Infinity` → hand back to vanilla (vanilla kicks);
     not on the main thread → hand back to vanilla; teleporting / client not loaded yet → do nothing.
  4. **Side effects**: while it is on, the movements above are **no longer constrained by the server-side speed check**.
     It only affects the vanilla `moved too quickly` / `moved wrongly` decision and **does not affect** external
     anti-cheat such as NoCheatPlus / Matrix — those apply their own rules. So this rule is only recommended **temporarily**
     on a controlled server, and turned off afterwards.
- **Key files**:
  - `common/src/server/java/com/villagerdetails/mixin/fix/move/GlobalSpeedBypassMixin.java` — injection points ①③
  - `common/src/server/java/com/villagerdetails/mixin/fix/log/EntityUUIDWarningMixin.java` — injection point ④ (log suppression, unrelated to movement)
  - `common/src/server/java/com/villagerdetails/handler/move/VehicleMoveTrust.java` — the shared "trust the client completely" logic for vehicles
  - `v26.2/…/mixin/fix/move/VehicleMoveTrustMixin.java`, `v26.3/…/mixin/fix/move/VehicleMoveTrustMixin.java`
    — the **two per-version copies** of injection point ② (they differ only in the packet-reading API: 26.2 uses
    `position()/yRot()/xRot()`, 26.3 uses `movingTo(): PositionAndRotation`)
  - `common/src/server/java/com/villagerdetails/handler/entity/happyghast/HappyGhastSpeedHandler.java` — the reverse hint provider
- **Dependencies**:
  - **Rules**: none (it does not depend on any rule)
  - **Depended on by**: `HappyGhastSpeed` — raise the speed without this rule and the movement is pulled back and logged
  - **Config**: none
  - **Events**: none (pure Mixin, sends no packets and registers no tick)
- **Client behaviour**: **no custom packet and no client logic**. The player notices exactly two things:
  fast movement is no longer pulled back, and those two lines stop flooding the server log.
- **Design rules and boundaries**:
  1. **The bypass scope is exactly two cases**: the player's own movement (elytra / modded flight / creative flight) and
     a Happy Ghast the player is riding and controlling. Nothing else is bypassed — boats, minecarts and other mods'
     vehicles keep the vanilla speed check, and the vehicle path additionally requires the root vehicle to be a Happy
     Ghast whose controlling passenger is the player who reported the movement.
  2. `EntityUUIDWarningMixin` **has nothing to do with movement** and lives in `mixin/fix/log/`: it suppresses the
     "duplicate entity UUID" warning, returning `false` early only when that UUID has already been loaded (the return
     value is identical to vanilla; only the WARN line is saved). It reuses the same `GlobalSpeedBypass` switch, because
     these warnings usually come from fast movement / repeated reconnects.
  3. **It turns a check off rather than adding a feature**: while it is `true`, any player (OP or not) can move at any
     speed. The tier is `ON_OFF` (`false` / `true`) and there is **no** `ops` tier — "only OPs may go fast" is not
     expressible with this rule.

---

#### `HappyGhastSpeed` (Happy Ghast fly speed)

- **Register name**: `HappyGhastSpeed` (enum constant `HAPPY_GHAST_SPEED`)
- **Category**: `FIX`
- **Default value**: `0.05` (= the vanilla Happy Ghast `FLYING_SPEED` base value, not the attribute-type global default `0.4`)
- **Allowed values**: `0.05` / `0.1` / `0.2` / `0.3` / `0.5` / `1.0`
  — they are **all numbers**, so the whole rule is classified as a "custom list" and `/ec HappyGhastSpeed <any text>`
  still reaches the validator; the real gate is the rule's own validator
  `RuleStateValidator.numericRange(0.0, 5.0)` (see "Design rules and boundaries")
- **Commands**: `/ec HappyGhastSpeed` (view), `/ec HappyGhastSpeed <number>` (change); **no** `/ec c` subcommand
- **Config class**: none (the number lives in the rule state; there is no `config.json` section)
- **Trigger**: four entry points that **must compute the same "desired value"**, which is why the logic all sits in one
  plain class (not hidden in a Mixin `@Unique` method):

  | # | Entry point | When it runs |
  |---|---|---|
  | ① | `HappyGhastSpeedMixin` (TAIL of `HappyGhast#addPassenger` / `removePassenger`) | mounting / dismounting |
  | ② | `HappyGhastSpeedHandler#tick` (`END_SERVER_TICK`, once every 20 ticks) | roll back leftover boost |
  | ③ | `RuleCache` listener (`HappyGhastSpeedSectionManager#onRuleChanged`) | the rule was changed, recompute immediately |
  | ④ | `SERVER_STOPPING` → `resetState()` | clear the static collections |

- **How it works**:
  1. **One pure function computes the desired value** (`desiredSpeed`): rule off → vanilla; **the controller is not a
     player** (nobody riding / still-timeout / harness removed / ridden by another mob) → vanilla; otherwise → the rule
     value pulled into `[0.0, 5.0]`.
  2. **"Vanilla speed" is read, not hard-coded**: it is read from `HappyGhast.createAttributes()`, i.e. the ghast's own
     `0.05`. It must not take the **type default** of `Attributes.FLYING_SPEED` (that is `0.4`, which would make the
     ghast 8× its vanilla speed). When it cannot be read, the constant `FALLBACK_DEFAULT_SPEED = 0.05` is used.
  3. **Only write when it does not match** (`setBaseValue` only when the difference > `EPSILON`), and maintain a
     `TOUCHED` set: the ghasts "we wrote a custom speed to and have not rolled back yet". **The sweep only trusts that
     set, never "the value differs from the default"** — the latter would rewrite another mod's speed for the ghast once
     every second.
  4. **Rolling back leftover boost** (entry ②): still-timeout, harness removed, seat unchanged but the controller turned
     from a player into something else — none of those fire `addPassenger` / `removePassenger`, so the 20-tick sweep
     restores vanilla. When the set is empty (the normal case) it returns at once, at zero cost.
  5. **A rule change takes effect immediately** (entry ③): it only touches ghasts "currently ridden by a player" and the
     ones in `TOUCHED`, so changing the value mid-flight needs no dismount-and-remount; during start-up / reload replay
     (`RuleChangeService.isReloading`) it is skipped.
  6. **Side effects**: the ghast's flying-speed attribute is rewritten, so it gets faster the moment someone rides it; it
     rolls back every second after the harness is removed / the controller changes. Changing it **down** works too
     (including `0.0` = pinned in place).
- **Key files**:
  - `common/src/server/java/com/villagerdetails/handler/entity/happyghast/HappyGhastSpeedHandler.java` — all the logic
  - `common/src/server/java/com/villagerdetails/mixin/entity/happyghast/HappyGhastSpeedMixin.java` — entry point ①
  - `common/src/server/java/com/villagerdetails/event/impl/register/HappyGhastSpeedSectionManager.java` — wiring for entry points ②③④
  - `common/src/server/java/com/villagerdetails/rule/type/RuleType.java` — allowed values + `numericRange(0.0, 5.0)`
- **Dependencies**:
  - **Rules**: `GlobalSpeedBypass` — **strongly recommended to enable as well**. The two rules are **independent**:
    turning on the speed without bypass still works, but fast movement is still pulled back by the server and floods
    the `moved too quickly` log, so admins easily believe they configured the number wrong.
    The code only **warns** (`warnIfBypassMissing`, once per ghast, capped at 512 lines); it **never turns bypass on by
    itself** — that would mean disabling the movement check on the player's behalf.
  - **Config**: none
  - **Events**: `END_SERVER_TICK`, `SERVER_STOPPING`, `RuleCache.addListener`
    (change notification goes through the listener list, which lets several consumers coexist on the same rule)
- **Client behaviour**: **no custom packet**. The speed is a server-authoritative attribute, so the client simply "flies
  faster"; it shows no screen and no prompt. What an admin cannot see: the server writes this attribute only while the
  controller is a player, so "the AI fly speed of an unridden ghast" is always the vanilla value.
- **Design rules and boundaries**:
  1. **The upper bound on both sides is the same**: the write side accepts `0.0 – 5.0` and the consuming side uses
     `HappyGhastSpeedHandler.MAX_SPEED = 5.0`, so `/ec HappyGhastSpeed 10` is rejected before anything is written —
     no silent clamping behind a "changed successfully" reply. `5.0` is already 100× vanilla; anything above it is far
     beyond chunk-loading speed and the player cannot control it anyway.
  2. **Out-of-range, non-numeric, `NaN` and `Infinity` are all rejected by the validator before the write**, and the
     consuming side clamps as a backstop. `0.0` is a legal value (= pin the ghast in place); negative values are
     rejected by the validator.
  3. **`NaN` / `±Infinity` get two gates**: the validator rejects them (`not_a_number`) and the consuming side blocks
     them again. Note that `clampSpeed` **must check `Double.isFinite` before clamping** — `Math.clamp(NaN, 0, 5)` still
     returns `NaN`, and writing that into an `AttributeInstance` turns the ghast's whole coordinate set into NaN. Keep
     those two lines in that order.
  4. The default value `0.05` and "the attribute-type default `0.4`" are not the same thing, see "Implementation 2".

---

### Cross references

- Rule index: [`../01-rule-index.md`](../01-rule-index.md)
- The two-layer permission model: [`../00-overview.md`](../00-overview.md) §4.3
- Terms: `tier`, `custom list rule`, `state validator` → [`../04-glossary.md`](../04-glossary.md)
- Deep dive: the threading and gate details of "trust the client completely" for vehicles stay out of the business docs;
  the injection point list is in [framework 08](../../framework/08-mixin-inventory.md)

---

<a id="中文"></a>

## 中文

> 本文件覆盖 `RuleType` 里 `FIX` 分类的 **2 条**规则。
> 两条都在 `mixin/fix/`（和 `mixin/entity/happyghast/`）下，都是**服务端行为修正**，没有客户端界面。
>
> ⚠️ 分类注记：`HappyGhastSpeed` 读起来像实体规则，枚举里归的是 `FIX`；`GlobalSpeedBypass` 虽然归 `FIX`，
> 但它实际影响的是**玩家 / 载具的位移校验**（`net.minecraft.server.network.ServerGamePacketListenerImpl`）。

---

#### `GlobalSpeedBypass`（移动提速防回弹）

- **注册名**：`GlobalSpeedBypass`（枚举常量 `GLOBAL_SPEED_BYPASS`）
- **分类**：`FIX`
- **默认值**：`false`
- **可选值**：`false` / `true`
- **命令**：`/ec GlobalSpeedBypass`（查看）、`/ec GlobalSpeedBypass true|false`（开关）；**无** `/ec c` 子命令
- **配置类**：无（一个纯开关，没有细项节）
- **触发**：三个注入点，全部在**服务端收包 / 位移处理**路径上：

  | # | 注入点 | 作用 |
  |---|---|---|
  | ① | `ServerGamePacketListenerImpl#shouldCheckPlayerMovement`（HEAD，cancellable） | **玩家自身移动**：让服务端"跳过这次位移校验" |
  | ② | `ServerGamePacketListenerImpl#handleMoveVehicle`（HEAD，cancellable） | **载具移动（玩家骑乘并操控的乐魂）**：整段接管，客户端坐标原样落地 |
  | ③ | `ServerGamePacketListenerImpl#handleMovePlayer` / `handleMoveVehicle` 里的 `Logger.warn`（Redirect） | **日志静默**：吞掉 `moved too quickly` / `moved wrongly` |
  | ④ | `PersistentEntitySectionManager#addEntityUuid`（HEAD） | 顺手压掉「重复 UUID」警告（与位移无关，见「设计约定与边界」） |

- **实现思路**：
  1. **触发条件 = 规则开启 + 场景判定**。场景判定是同一个谓词（`villagerdetails$isBypassAllowed()`），
     日志静默和真放行共用它，保证不会出现"校验照做、日志却被吞掉"。
  2. **玩家自身移动（①）**：什么都不改，只是让服务端**不再拦**这次位移 ——
     原版那两条回退（超阈值 → 把旧位置发回去；残差过大 → snap 回旧位置）因此不会触发。
     这一支覆盖"人自己在飞"的场景：鞘翅、模组飞行器、创造飞行。
  3. **载具移动（②）**：原版 `handleMoveVehicle` 的回退分支是**内联**的，光静默日志没用，回弹照旧。
     所以这条路直接**取消整个方法**，由 `VehicleMoveTrust.apply` 把客户端坐标原样落地，
     并补齐"取消之后原版就不会再做"的副作用：位置与朝向规范化落地、落地状态与摔落判定、
     里程统计、**区块跟随（`getChunkSource().move`，漏了会表现为地形不加载 / 卡在原地）**、
     known movement 与活跃时间（不补的话高速飞行会被当成挂机）。
     门禁仍然保留：坐标含 `NaN`/`±Infinity` → 交回原版（由原版踢人）；非主线程 → 交回原版；
     传送中 / 客户端还没加载完 → 什么都不做。
  4. **副作用**：开启期间，上述位移**不再受服务端移速校验约束**。它只影响原版的
     `moved too quickly` / `moved wrongly` 判定，**不影响** NoCheatPlus / Matrix 这类外部反作弊
     —— 那些按自己的规则判定。所以这条规则只建议在受控服务器**临时**开启，用完关掉。
- **关键文件**：
  - `common/src/server/java/com/villagerdetails/mixin/fix/move/GlobalSpeedBypassMixin.java` — 注入点 ①③
  - `common/src/server/java/com/villagerdetails/mixin/fix/log/EntityUUIDWarningMixin.java` — 注入点 ④（压日志，与移动无关）
  - `common/src/server/java/com/villagerdetails/handler/move/VehicleMoveTrust.java` — 载具"完全信任"的共用逻辑
  - `v26.2/…/mixin/fix/move/VehicleMoveTrustMixin.java`、`v26.3/…/mixin/fix/move/VehicleMoveTrustMixin.java`
    — 注入点 ② 的**两份版本副本**（区别只在读包的 API：26.2 是 `position()/yRot()/xRot()`，
    26.3 是 `movingTo(): PositionAndRotation`）
  - `common/src/server/java/com/villagerdetails/handler/entity/happyghast/HappyGhastSpeedHandler.java` — 反向提示方
- **依赖**：
  - **规则**：无（它不依赖任何规则）
  - **被依赖**：`HappyGhastSpeed` —— 速度调高但本规则没开时，位移会被服务端拉回并刷日志
  - **配置**：无
  - **事件**：无（纯 Mixin，不发包、不注册 tick）
- **客户端行为**：**没有自定义包，也没有客户端逻辑**。玩家能感知到的只有两件事：
  高速移动不再被拉回；服务端日志里不再刷那两条提示。
- **设计约定与边界**：
  1. **放行范围就是两种**：玩家自身移动（鞘翅 / 模组飞行器 / 创造飞行），以及玩家正在骑乘并操控的**乐魂**。
     其余一律不放行 —— 船、矿车、其它模组的载具照旧走原版移速校验；载具那条路还要求根载具就是乐魂、
     且控制者正是上报位移的这名玩家。
  2. `EntityUUIDWarningMixin` **与"移动"无关**，位于 `mixin/fix/log/`：它压的是"实体 UUID 重复"的警告，
     只在"该 UUID 已经加载过"时让原方法直接返回 `false`（返回值与原版一致，只省掉那行 WARN）。
     开关仍复用 `GlobalSpeedBypass` —— 这类警告通常由高速位移 / 反复重进触发。
  3. **它是"关掉一道校验"，不是"加一个功能"**：`true` 期间任何玩家（含非 OP）都能以任意速度位移。
     权限档位是 `ON_OFF`（`false` / `true`），**没有** `ops` 档 —— 想"只有 OP 能高速"是做不到的。

---

#### `HappyGhastSpeed`（乐魂飞行速度）

- **注册名**：`HappyGhastSpeed`（枚举常量 `HAPPY_GHAST_SPEED`）
- **分类**：`FIX`
- **默认值**：`0.05`（= 原版乐魂的 `FLYING_SPEED` 基值，不是属性类型的全局默认值 `0.4`）
- **可选值**：`0.05` / `0.1` / `0.2` / `0.3` / `0.5` / `1.0`
  —— 但它们**全是数字**，于是整条规则被判成"自定义列表"，`/ec HappyGhastSpeed 任意文本` 都会进到校验器；
  真正的门是规则自己的校验器 `RuleStateValidator.numericRange(0.0, 5.0)`（见「设计约定与边界」）
- **命令**：`/ec HappyGhastSpeed`（查看）、`/ec HappyGhastSpeed <数值>`（改）；**无** `/ec c` 子命令
- **配置类**：无（数值就存在规则状态里，没有 `config.json` 节）
- **触发**：四条入口，**必须算出同一个"期望值"**，所以逻辑全部收在一个普通类里（不藏在 Mixin 的
  `@Unique` 方法里）：

  | # | 入口 | 什么时候跑 |
  |---|---|---|
  | ① | `HappyGhastSpeedMixin`（`HappyGhast#addPassenger` / `removePassenger` 的 TAIL） | 上坐 / 下坐 |
  | ② | `HappyGhastSpeedHandler#tick`（`END_SERVER_TICK`，每 20 tick 一次） | 残留加速回滚 |
  | ③ | `RuleCache` 监听器（`HappyGhastSpeedSectionManager#onRuleChanged`） | 规则被改动，立刻重算 |
  | ④ | `SERVER_STOPPING` → `resetState()` | 清静态集合 |

- **实现思路**：
  1. **一个纯函数算期望值**（`desiredSpeed`）：规则关 → 原版；**控制者不是玩家**（无人骑 /
     still-timeout / 挽具被摘 / 被生物骑乘）→ 原版；否则 → 规则值收进 `[0.0, 5.0]`。
  2. **"原版速度"现算不硬编码**：从 `HappyGhast.createAttributes()` 里读出乐魂自己的 `0.05`。
     不能取 `Attributes.FLYING_SPEED` 的**类型默认值**（那是 `0.4`，会把乐魂变成原版 8 倍速）。
     读不到时回落常量 `FALLBACK_DEFAULT_SPEED = 0.05`。
  3. **只在"对不上"时写**（差值 > `EPSILON` 才 `setBaseValue`），并维护一个 `TOUCHED` 集合：
     记录"被我们写过自定义速度、还没回滚"的乐魂。**巡检只认这个集合，不认"值不等于默认值"**
     —— 后者会把其它模组给乐魂设的速度每秒改回去一次。
  4. **残留加速回滚**（入口 ②）：still-timeout、挽具被移除、座位没变但控制者从玩家变成别的东西，
     这些都不会触发 `addPassenger` / `removePassenger`，靠每 20 tick 巡检改回原版。
     集合为空（常态）时直接返回，零成本。
  5. **规则一改立刻生效**（入口 ③）：只动"正被玩家骑着的"和"`TOUCHED` 里的"乐魂，
     骑乘中改数值不用下坐再上坐；启动 / 重载回灌期间（`RuleChangeService.isReloading`）跳过。
  6. **副作用**：乐魂的飞行速度属性被改写，骑乘时立刻变快；取下挽具 / 控制者变化后每秒回滚。
     改**低**也生效（包括 `0.0` = 原地不动）。
- **关键文件**：
  - `common/src/server/java/com/villagerdetails/handler/entity/happyghast/HappyGhastSpeedHandler.java` — 全部逻辑
  - `common/src/server/java/com/villagerdetails/mixin/entity/happyghast/HappyGhastSpeedMixin.java` — 入口 ①
  - `common/src/server/java/com/villagerdetails/event/impl/register/HappyGhastSpeedSectionManager.java` — 入口 ②③④的接线
  - `common/src/server/java/com/villagerdetails/rule/type/RuleType.java` — 可选值 + `numericRange(0.0, 5.0)`
- **依赖**：
  - **规则**：`GlobalSpeedBypass` —— **强烈建议同时开启**。两条规则是**互相独立**的：
    只开速度不开 bypass，速度照样生效，但移速过快时仍会被服务端拉回并刷
    `moved too quickly` 日志，管理员很容易以为自己配错了数值。
    代码只会**提示**（`warnIfBypassMissing`，每个乐魂一次、上限 512 条），**绝不自动打开 bypass**
    —— 那等于替玩家关掉位移校验。
  - **配置**：无
  - **事件**：`END_SERVER_TICK`、`SERVER_STOPPING`、`RuleCache.addListener`
    （变更通知走监听器列表，同一条规则上可以有多个消费方并存）
- **客户端行为**：**没有自定义包**。速度是服务端权威属性，客户端表现为"飞得更快"；
  客户端不会有任何界面或提示。管理员看不到的是：服务端只在控制者是玩家时才写这个属性，
  所以"没人骑的乐魂 AI 飞行速度"始终是原版值。
- **设计约定与边界**：
  1. **写入侧与消费侧的上界是同一个**：写入前 `RuleStateValidator.numericRange(0.0, 5.0)`，
     消费侧 `HappyGhastSpeedHandler.MAX_SPEED = 5.0`，所以 `/ec HappyGhastSpeed 10` 在写入前就被拒，
     不会出现"提示修改成功、实际却被静默夹断"。`5.0` 已经是原版 100 倍，再往上远超区块加载速度，
     玩家自己也驾驭不了。
  2. **超范围、非数字、`NaN`、`Infinity` 一律在写入前被校验器拒掉**，消费侧再夹一次作为兜底。
     `0.0` 是合法值（= 把乐魂钉在原地），负值被校验器拒。
  3. **`NaN` / `±Infinity` 有两道关**：校验器直接拒（`not_a_number`）；消费侧再挡一次。
     注意 `clampSpeed` **必须先判 `Double.isFinite`** 再 clamp —— `Math.clamp(NaN, 0, 5)` 仍然返回
     `NaN`，真写进 `AttributeInstance` 会让乐魂坐标整片变 NaN。这两行的顺序不要调换。
  4. 默认值 `0.05` 与"属性类型默认值 `0.4`"不是一回事，见「实现思路 2」。

---

### 交叉引用

- 规则总索引：[`../01-rule-index.md`](../01-rule-index.md)
- 权限两层模型：[`../00-overview.md`](../00-overview.md) §4.3
- 术语：`档位`、`自定义列表规则`、`状态校验器` → [`../04-glossary.md`](../04-glossary.md)
- 深度剖析：载具"完全信任"的线程与门禁细节不进业务文档，注入点清单见[框架 08](../../framework/08-mixin-inventory.md)
