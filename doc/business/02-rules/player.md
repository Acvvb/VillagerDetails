# Players (PLAYER)

The two `PLAYER` rules that let a player ride another player, and sit on stairs and slabs.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> This file covers the **2 rules** whose main category in `RuleType` is `PLAYER`.
> Both of them are "a player sitting on a player / a player sitting on a block", and both **add a vehicle vanilla does not allow**:
> `PLAYER_HEAD_SITTING` uses **a player** as the vehicle, `PLAYER_BLOCK_SITTING` uses the mod's own invisible seat entity as the vehicle.
> So both have to get around the `canSerialize()` check inside vanilla `startRiding` (both vehicles are `noSave()`).

---

#### `PlayerHeadSitting` (riding on another player's head)

- **Register name**: `PlayerHeadSitting`
- **Category**: `PLAYER`
- **Default value**: `false`
- **Commands**: `/ec PlayerHeadSitting` (switch); **no** `/ec c` subcommand; the fine-grained options live in the config section
- **Config class**: `PlayerHeadSittingConfig` (the `PlayerHeadSitting` section of `config.json`)
  - `maxStackDepth`: stacking limit, default `2`, clamped to `[2, 8]`
  - `allowCreative`: whether creative players may sit, default `true`
  - `dismountOnTargetRide`: whether the rider is kicked off when the target starts riding something else, default `true`
- **Trigger**: right-click **another player** with an empty main hand (`UseEntityCallback`; decided on the logical server only, never predicted on the client)
- **How it works**: see [deep dive: player head / block sitting](../03-deep-dives/player-sitting.md). In one sentence:
  1. The entry point only checks "side / main hand / player × player / empty hand"; every business decision lives in `trySit`;
  2. Refusal conditions: yourself / a spectator / already riding something / someone is already on the target's head / the target is dead / the target is sleeping /
     **the target has not allowed sitting** / stacking limit exceeded / (optionally) creative mode;
  3. once through, `startRiding(target, force=true)`, then immediately place the rider on top of the vehicle's bounding box and swing the hand once;
  4. from then on an `END_SERVER_TICK` check maintains it: aligning by bounding box continuously, handling every "time to get off" case,
     and clearing "ghost passengers" (an offline old player entity still listed in the vehicle's passenger table).
- **Key files**:
  - `handler/player/headSitting/PlayerHeadSittingHandler.java` — who may sit / where to put them / when to kick them off
  - `rule/playerHeadSitting/PlayerHeadSittingSeat.java` — seat position calculation (top of the vehicle's bounding box)
  - `mixin/player/playerHeadSitting/EntityStartRidingMixin.java` — bypasses `canSerialize()`
  - `mixin/player/playerHeadSitting/EntityPlayerVehiclePassengersMixin.java` — re-sends the passenger table to **the player vehicle itself**
  - `event/impl/register/PlayerHeadSittingSectionManager.java` — event wiring
  - `cache/impl/PlayerHeadSittingAllow.java` — the "others may sit on me" list
- **Dependencies**:
  - **Rules**: none
  - **Config**: `PlayerHeadSittingConfig` (the three keys above)
  - **Cache**: `PlayerHeadSittingAllow` (session state, cleared when the player leaves)
  - **Network**: `PlayerHeadSittingAllowReceiver` (the client reports its own choice)
  - **Events**: `UseEntityCallback`, `END_SERVER_TICK`, `AFTER_PLAYER_CHANGE_LEVEL`,
    `DISCONNECT`, `ENTITY_UNLOAD`, `JOIN`
- **Client behaviour**: the client has an "allow others to sit on my head" switch, which is reported once after login; **players without the mod count as not allowing**
  (so a server with this rule on still will not be sat on by strangers). Rendering is left entirely to the vanilla riding logic; the mod draws no custom seat.
- **Design rules and boundaries**:
  1. **Consent belongs to the target**: each player's own switch decides whether others may sit on their head. The switch is on by default, so a player who has the mod is open out of the box, and the client reports its own preference over
     `PlayerHeadSittingAllowReceiver` into the session-state list `PlayerHeadSittingAllow`. The server keeps no opinion of its own and offers no way to allow on someone else's behalf, so a player who never reports (no mod installed) counts as not allowing.
  2. **`PLAYER` is `noSave()`**, so cleaning up "the vehicle went offline" is the mod's own job (three fallbacks: disconnect / unload / rejoin).
     Chunk unload alone is not enough: the player entity is removed later than the chunk, the passenger entry on the vehicle may already have been saved with it,
     and after a reload the log fills with `Trying to save removed ... skipping` while the client turns invisible because it received a passenger table pointing at a non-existent entity.
  3. The target player's **own** client does not receive the re-sent passenger table by default (vanilla only sends it to passengers and spectators),
     so the mod adds one packet by hand at the TAIL of `addPassenger` / `removePassenger` —
     otherwise the target would see "someone pinned to my head, not moving" on their screen.

---

#### `PlayerBlockSitting` (sitting on stairs and slabs)

- **Register name**: `PlayerBlockSitting`
- **Category**: `PLAYER`
- **Default value**: `false`
- **Commands**: `/ec PlayerBlockSitting` (switch); **no** `/ec c` subcommand
- **Config class**: none (the seat position is decided entirely by the block's collision box; there are **no offset options at all**)
- **Trigger**: right-click **stairs / slabs** with an empty main hand (`UseBlockCallback`, executed on the logical server only;
  the client side does one extra thing: a click outside the build height fails straight away, so no packet with an abnormal coordinate is sent)
- **How it works**:
  1. **Whether you may sit**: the rule is on + **that player's own client switch** allows it (`PlayerBlockSittingPreference`,
     allowing by default; players without the mod also count as allowing) + a set of block conditions: not a spectator / not riding anything / not sneaking /
     not clicking the bottom face / the block is in the `minecraft:stairs` or `minecraft:slabs` tag and **is a bottom slab / bottom stairs** /
     there is enough room above the seat.
  2. **How to sit**: spawn an **invisible `BlockSeatEntity`** on the spot (the mod's own entity type `ModEntities.BLOCK_SEAT`),
     put the player on it; the seat is "the top face of the flat side of the block's collision box", and stairs are additionally nudged **2 pixels towards the low side**
     (otherwise the back of the sitting model clips into the lower step above the high side).
  3. **When to get up**: a per-tick check; the rule turned off / the player turned it off / the player is gone / the block was broken / the seat entity was removed
     all lead to `standUp` — which also teleports the player back to the seat position and clears momentum and fall distance, so that "getting up" never drops you into the block.
  4. **Passenger table re-send**: the seat entity is a mod entity, so the client needs time to track it; therefore the passenger packet is re-sent to the player themselves for 10 ticks in a row after sitting down,
     and afterwards once per 10 ticks to players within 96 blocks.
  5. **Swallowing a vanilla false alarm along the way**: one right-click on a block makes the client send **two** `ServerboundUseItemOnPacket`s (main hand + off hand);
     the main-hand packet sits the player down, and the off-hand packet falls into vanilla's "waiting for teleport confirmation" fallback branch,
     where vanilla sends a `sendBuildLimitMessage(true, maxY)` — what the player sees is a red "build height limit 319".
     So while sitting on a seat (and during the 10 ticks right after sitting down / getting up) that message is swallowed.
- **Key files**:
  - `handler/player/blockSitting/PlayerBlockSittingHandler.java` — decision / seat spawn / tick check / standing up
  - `rule/blockSitting/BlockSittingSeat.java` — which blocks may be sat on, seat coordinates, headroom
  - `entity/BlockSeatEntity.java` + `entity/ModEntities.java` (shared source set for both sides) — the invisible seat entity
  - `mixin/entity/blockSitting/SeatBuildLimitMessageMixin.java` — swallows the false alarm
  - `mixin/player/playerHeadSitting/EntityStartRidingMixin.java` — the same `canSerialize()` bypass point
    (the seat entity branch)
  - `event/impl/register/PlayerBlockSittingSectionManager.java` — event wiring
  - `cache/impl/PlayerBlockSittingPreference.java` — the "may I still sit" list
- **Dependencies**:
  - **Rules**: none (but it shares the `canSerialize()` bypass in `EntityStartRidingMixin` with `PlayerHeadSitting`)
  - **Config**: none
  - **Cache**: `PlayerBlockSittingPreference` (session state)
  - **Network**: `PlayerBlockSittingEnabledReceiver` (the client reports the switch)
  - **Entities**: `ModEntities.BLOCK_SEAT` (must be registered on both sides so the client can decode it)
  - **Events**: `UseBlockCallback`, `END_SERVER_TICK`, `SERVER_STOPPING`
- **Client behaviour**: the client config has a "may I sit" switch, reported once when joining a server;
  the client **also filters** clicks outside the build height itself. Rendering is left to vanilla riding; the seat entity is invisible.
- **Design rules and boundaries**:
  1. **`isSittingBlock` only accepts bottom slabs / bottom stairs**: top slabs, double slabs (which are really full blocks)
     and upside-down stairs are all refused. The check uses **tags + block state properties**, so bottom slabs / bottom stairs added by mods can be sat on too.
  2. The seat entity is a **real entity**: it counts towards the entity count and follows chunk load / unload (after an unload the tick check stands the player up).
     That is the price of using "vanilla riding" — far less work than drawing a seat yourself, but the seating position then has to be finished off by a server-side `teleport`.
  3. The seat entity is created on the server with `noSave`, so cleaning it up is the server's responsibility: a broken block, the rule switched off or a player leaving all end in `standUp` plus `discard`,
     and nothing ever lands in the save file (see [deep dive: player head / block sitting](../03-deep-dives/player-sitting.md)).
  4. Fine-grained options such as `maxStackDepth` do **not** exist here: block sitting has no config section, and every behaviour is fixed in code.

---

<a id="中文"></a>

## 中文

> 本文件覆盖 `RuleType` 里主分类为 `PLAYER` 的 **2 条**规则。
> 两条都是"玩家之间的乘坐 / 玩家坐在方块上"，都**新增了一个原版不允许的载具**：
> `PLAYER_HEAD_SITTING` 用**玩家**当载具，`PLAYER_BLOCK_SITTING` 用模组自己的隐形座位实体当载具。
> 所以两条都要绕原版 `startRiding` 里的 `canSerialize()` 检查（两个载具都是 `noSave()`）。

---

#### `PlayerHeadSitting`（玩家头顶乘坐）

- **注册名**：`PlayerHeadSitting`
- **分类**：`PLAYER`
- **默认值**：`false`
- **命令**：`/ec PlayerHeadSitting`（开关）；**无** `/ec c` 子命令；细项在配置节里改
- **配置类**：`PlayerHeadSittingConfig`（`config.json` 的 `PlayerHeadSitting` 节）
  - `maxStackDepth`：叠罗汉上限，默认 `2`，夹在 `[2, 8]`
  - `allowCreative`：创造玩家能否坐，默认 `true`
  - `dismountOnTargetRide`：目标开始骑别的东西时是否把人踢下来，默认 `true`
- **触发**：主手空手右键**另一个玩家**（`UseEntityCallback`，只在逻辑服务端判定，客户端不预测）
- **实现思路**：见 [深度剖析：玩家头顶 / 方块乘坐](../03-deep-dives/player-sitting.md)。一句话版：
  1. 入口只校验"端侧 / 主手 / 玩家×玩家 / 空手"，业务判定全在 `trySit`；
  2. 拒坐条件：自己 / 旁观者 / 已经在骑东西 / 目标头上已经有人 / 目标已死 / 目标在睡觉 /
     **目标没允许被坐** / 叠罗汉超上限 / （可选）创造模式；
  3. 通过后 `startRiding(target, force=true)`，立刻按碰撞箱把乘客摆到载具头顶，再挥一下手；
  4. 之后靠 `END_SERVER_TICK` 巡检维持：按碰撞箱持续对齐、处理各种"该下来了"的情况、
     清掉"幽灵乘客"（已下线的旧玩家实体还挂在载具的乘客表里）。
- **关键文件**：
  - `handler/player/headSitting/PlayerHeadSittingHandler.java` — 能不能坐 / 怎么摆 / 什么时候踢
  - `rule/playerHeadSitting/PlayerHeadSittingSeat.java` — 座面位置计算（载具碰撞箱顶）
  - `mixin/player/playerHeadSitting/EntityStartRidingMixin.java` — 绕过 `canSerialize()`
  - `mixin/player/playerHeadSitting/EntityPlayerVehiclePassengersMixin.java` — 把乘客表补发给**玩家载具本人**
  - `event/impl/register/PlayerHeadSittingSectionManager.java` — 事件接线
  - `cache/impl/PlayerHeadSittingAllow.java` — "允许别人坐我"名单
- **依赖**：
  - **规则**：无
  - **配置**：`PlayerHeadSittingConfig`（上面三个键）
  - **缓存**：`PlayerHeadSittingAllow`（会话态，玩家退出即清）
  - **网络**：`PlayerHeadSittingAllowReceiver`（客户端上报自己的选择）
  - **事件**：`UseEntityCallback`、`END_SERVER_TICK`、`AFTER_PLAYER_CHANGE_LEVEL`、
    `DISCONNECT`、`ENTITY_UNLOAD`、`JOIN`
- **客户端行为**：客户端有一个"允许别人坐我头上"的开关，登录后上报一次；**没装模组的玩家算不允许**
  （所以服务器开着这条规则也不会被陌生人坐）。渲染完全交给原版骑乘逻辑，模组不画自定义座位。
- **设计约定与边界**：
  1. **同意权在目标手里**：每个玩家自己的开关决定别人能不能坐到自己头上。开关默认开，所以装了模组的玩家开箱即用；
     客户端通过 `PlayerHeadSittingAllowReceiver` 上报自己的选择，写进会话态名单 `PlayerHeadSittingAllow`。
     服务端不替任何人做主，也没有"替别人允许"的入口，所以从没上报过的玩家（没装模组）一律算不允许。
  2. **`PLAYER` 是 `noSave()` 的**，所以"载具离线"必须自己清（下线 / 卸载 / 重进三条兜底）。
     只靠区块卸载兜不住：玩家实体被移除的时机更晚，载具身上那条乘客记录可能已经跟着存过一次，
     读档后会刷 `Trying to save removed ... skipping`，客户端还会因为收到指向不存在实体的乘客表而隐身。
  3. 目标玩家**自己的**客户端默认收不到乘客表补发（原版只发给乘客和旁观者），
     所以模组在 `addPassenger` / `removePassenger` 的 TAIL 手动补一个包给他 ——
     不然他画面上会看到"有人被钉在自己头上不动"。

---

#### `PlayerBlockSitting`（坐在楼梯 / 半砖上）

- **注册名**：`PlayerBlockSitting`
- **分类**：`PLAYER`
- **默认值**：`false`
- **命令**：`/ec PlayerBlockSitting`（开关）；**无** `/ec c` 子命令
- **配置类**：无（座面位置完全由方块碰撞箱决定，**没有任何偏移配置**）
- **触发**：主手空手右键**楼梯 / 半砖**（`UseBlockCallback`，只在逻辑服务端执行；
  客户端侧只多做一件事：点在建造高度之外时直接 `FAIL`，避免发出坐标异常包）
- **实现思路**：
  1. **能不能坐**：规则开启 + **该玩家自己的客户端开关**允许（`PlayerBlockSittingPreference`，
     默认允许，没装模组的玩家也算允许）+ 一串方块条件：不是旁观 / 没在骑东西 / 没潜行 /
     没点底面 / 方块在 `minecraft:stairs` 或 `minecraft:slabs` 标签里且**是下半砖 / 下半楼梯** /
     座面上方有足够空间。
  2. **怎么坐**：现场生成一个**看不见的 `BlockSeatEntity`**（模组自己的实体类型 `ModEntities.BLOCK_SEAT`），
     把玩家放上去；座面取"方块碰撞箱里平的那一面的顶面"，楼梯还会往**矮的那一侧**挪 2 像素
     （否则坐着模型的背会贴住上一级的立面）。
  3. **什么时候起来**：每 tick 巡检，规则关了 / 玩家自己关了 / 玩家没了 / 方块被挖掉 / 座位实体被移除
     都会 `standUp` —— 顺手把玩家传送回座面位置、清掉动量与摔落距离，避免"起身时掉进方块里"。
  4. **乘客表补发**：座位实体是模组实体，客户端追踪它有时间差，所以坐下后连续 10 tick 给本人补发乘客包，
     之后每 10 tick 给 96 格内的玩家补发一次。
  5. **顺手吞掉一条原版误报**：一次右键方块客户端会发**两个** `ServerboundUseItemOnPacket`（主手 + 副手），
     主手那一包让人坐上去了、副手那一包会落进原版"正在等传送确认"的兜底分支，
     于是原版发一条 `sendBuildLimitMessage(true, maxY)` —— 玩家看到的是红色"建筑高度上限 319"。
     所以坐在座位上（以及刚坐下 / 刚起身的 10 tick 内）把这条提示吞掉。
- **关键文件**：
  - `handler/player/blockSitting/PlayerBlockSittingHandler.java` — 判定 / 生成座位 / 巡检 / 起身
  - `rule/blockSitting/BlockSittingSeat.java` — 哪些方块能坐、座面坐标、headroom
  - `entity/BlockSeatEntity.java` + `entity/ModEntities.java`（两端共用源集）— 隐形座位实体
  - `mixin/entity/blockSitting/SeatBuildLimitMessageMixin.java` — 吞误报
  - `mixin/player/playerHeadSitting/EntityStartRidingMixin.java` — 同一个 `canSerialize()` 绕过点
    （座位实体那条分支）
  - `event/impl/register/PlayerBlockSittingSectionManager.java` — 事件接线
  - `cache/impl/PlayerBlockSittingPreference.java` — "我还能不能坐"名单
- **依赖**：
  - **规则**：无（但与 `PlayerHeadSitting` 共用 `EntityStartRidingMixin` 的 `canSerialize()` 绕过）
  - **配置**：无
  - **缓存**：`PlayerBlockSittingPreference`（会话态）
  - **网络**：`PlayerBlockSittingEnabledReceiver`（客户端上报开关）
  - **实体**：`ModEntities.BLOCK_SEAT`（两端都要注册，客户端才能解码）
  - **事件**：`UseBlockCallback`、`END_SERVER_TICK`、`SERVER_STOPPING`
- **客户端行为**：客户端配置里有一个"我能不能坐"的开关，进服时上报一次；
  另外客户端**自己也拦一下**建造高度之外的点击。渲染交给原版骑乘，座位实体不可见。
- **设计约定与边界**：
  1. **`isSittingBlock` 只认下半砖 / 下半楼梯**：上半砖、双半砖（其实就是完整方块）、
     上下颠倒的楼梯都不给坐。用的是**标签 + 属性**判定，所以模组加的下半砖 / 下半楼梯也能坐。
  2. 座位实体是**真实实体**：会被计入实体数量、会随区块加载 / 卸载（卸载后巡检会起身）。
     这是用"原版骑乘"换来的代价 —— 比自绘座位省事得多，但下坐位置由服务端 `teleport` 补齐。
  3. 座位实体是服务端现场生成、`noSave` 的，所以清理完全是服务端的责任：
     方块被挖、规则关掉、玩家离线都会走 `standUp` 再 `discard`，存档里永远不会留下垃圾实体
     （见 [深度剖析：玩家头顶 / 方块乘坐](../03-deep-dives/player-sitting.md)）。
  4. `maxStackDepth` 之类的细项**没有**：方块乘坐没有配置节，所有行为写死在代码里。
