# Deep dive: player riding (`PlayerHeadSitting` / `PlayerBlockSitting`)

Two rules that bypass the vanilla `noSave()` refusal, and the cleanup contract that comes with it.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> Related rules: [`PlayerHeadSitting`](../02-rules/player.md#playerheadsitting玩家头顶乘坐) ·
> [`PlayerBlockSitting`](../02-rules/player.md#playerblocksitting坐在楼梯--半砖上)
> Code entry: `handler/player/{headSitting,blockSitting}/**`, `cache/impl/{PlayerHeadSittingAllow,PlayerBlockSittingPreference}.java`,
> `mixin/player/playerHeadSitting/**`, `mixin/entity/blockSitting/SeatBuildLimitMessageMixin.java`,
> `entity/BlockSeatEntity.java`

---

### What problem it solves

Both rules make the player sit on a vehicle vanilla does not allow:

| Rule | What the vehicle is | Why vanilla refuses |
|---|---|---|
| Player head sitting | **another player** | `EntityTypes.PLAYER` is `noSave()`, so the `canSerialize()` check inside `Entity#startRiding` refuses outright (the same reason vanilla `/ride` reports `ERROR_MOUNTING_PLAYER`) |
| Sitting on stairs / slabs | the mod's invisible seat entity `BlockSeatEntity` | also `noSave()`; and stairs / slabs are not sittable blocks to begin with |

Both rules therefore share one bypass point:
`EntityStartRidingMixin` **turns that `canSerialize()` result into true under conditions** (the passenger must be a player; a player vehicle → consult the head-sitting rule; a mod seat → consult the block-sitting rule). After that,
`couldAcceptPassenger` / the stacking-cycle check / `canRide` / packet sending are all still vanilla logic.

---

### Design 1: sitting on a player's head

```
Right-click a player with an empty hand
   ↓ the entry only checks: server side / main hand / player × player / empty hand
trySit
   ├ rule switch (permission tier is decided per player)
   ├ refusal conditions: himself / spectator / already riding something / somebody on the target's head /
   │                    target dead / target sleeping / target did not allow sitting /
   │                    (optional) creative mode / stack depth over the cap
   ├ startRiding(target, force = true)  ← skips vanilla canRide/canAddPassenger (judged above)
   ├ place the rider on the head by bounding box right away (avoid one frame of clipping)
   └ swing the hand once (interaction feedback)
        ↓ afterwards
every tick: realign the seat + handle the "should get off" cases
```

**The "should get off" decision** (every tick): the rule is off / the target no longer allows it / the target changed dimension (a pending-dismount marker is recorded and handled on the next tick) / the target died / the target is sleeping / the target started sneaking / (optional) the target started riding something else.

**Three cleanup fallbacks** (this is where the rule has the most moving parts, see "why disconnecting must clean up by itself" below):

- `DISCONNECT`: tear down both directions — "he rides somebody" and "somebody rides him" — then sweep all ghost passengers on the server;
- `ENTITY_UNLOAD`: handled only when **what is unloaded is a player** (boats / minecarts and other vanilla vehicles have their own unloading mechanism, not touched);
- `JOIN`: force one cleanup of leftovers and re-send the passenger list on the next tick (the client has not finished receiving the world at the moment of joining).

---

### Design 2: sitting on stairs / slabs

```
Right-click a block with an empty hand
   ↓ entry checks: side (the client only blocks clicks outside the build height) / main hand / empty hand
trySit
   ├ rule switch + the player's own client switch (PlayerBlockSittingPreference, allowed by default)
   ├ block conditions: not spectator / riding / sneaking / clicking the bottom face; the block is in the
   │                   stairs/slabs tag and is "the lower half"; the seat surface is computable; headroom above
   ├ create BlockSeatEntity (invisible) → setPos(seat surface) → addFreshEntity
   ├ rider.startRiding(seat, force = true)
   └ push one passenger packet right away
        ↓ afterwards
every tick: rule off / player off / player gone / seat removed / block broken → stand up + teleport back to the seat surface
```

**How the seat surface is computed** (`BlockSittingSeat`): it uses the block's **own bounding box** and takes the top face of "the flat side" —
so a bottom slab and bottom stairs are at the same height (`y + 0.5`), while a top slab / upside-down stairs is `y + 1.0`.
Stairs are additionally nudged 2 pixels to the **lower side**, otherwise the back of the sitting model would touch the face of the step above.
**There is no offset config**: the position is decided entirely by the bounding box.

---

### Key trade-offs

#### ① Why the "may I sit" switch belongs to the player himself

- **Head sitting is refused by default**: it is a social feature, strangers climbing on your head at will is annoying and blocks your view.
  So the semantics of `PlayerHeadSittingAllow` are "**not in the list = not allowed**" — **players without the mod are protected by default**,
  and a server owner does not have to worry about it being abused.
- **Block sitting is allowed by default**: it only affects yourself (you are not affected by somebody else sitting on stairs),
  so `PlayerBlockSittingPreference` has the opposite semantics — "**not in the list = allowed**",
  and only players who explicitly turn it off on their client end up in the "do not want to sit" list.

Both tables are **session state** (cleared when the player quits, re-reported by the client on join) and are never persisted.

#### ② Why "a player as the vehicle" instead of drawing a mount entity of our own

A player as the vehicle gives the whole vanilla feature set for free: passenger position sync, collision, mount/dismount animation, spectator view,
and "the passenger is managed with the vehicle's chunk". The price is a pile of cleanup duties caused by `noSave()` (see the next point).
With an entity of our own, all of that would have to be reimplemented, and it would never line up with vanilla riding.

Block sitting, on the other hand, does need an entity (vanilla riding requires the vehicle to be an entity), so the mod registers an
**invisible `BlockSeatEntity`** and `discard`s it when done.

A seated player's position is placed by the server, but the movement packets still go through the vanilla movement check:
`GlobalSpeedBypass` only silences the paths it allows (the player moving on his own, and a Happy Ghast the player controls) and sitting is not one of them —
so movement warnings that should be stopped are never swallowed along with it.

#### ③ Why "disconnect / unload / rejoin — not one of the three fallbacks can be dropped"

The vehicle is **a player**, and `EntityTypes.PLAYER` is `noSave()`. Once the vehicle goes offline:

- the `passengers` entry it leaves behind is **an already removed player instance**;
- the tick loop keeps calling `snapToSeat` + `ChunkMap#move` on it — repeatedly pushing "an already removed entity" back into chunk tracking;
- the result: saving spams `Trying to save removed ... skipping`, and the client turns **invisible** because it receives a passenger list pointing at a non-existent entity id.

`DISCONNECT` is the main cleanup point (at that moment the entity is still in the world, so `stopRiding()` is legal);
`ENTITY_UNLOAD` covers "already removed"; `JOIN` covers "left over from the previous session". On top of that, the tick loop's "ghost passenger" check:
a passenger that is not online is force-`stopRiding`-ed and forgotten.

#### ④ Why the "vehicle himself" needs the passenger list re-sent

Vanilla syncs the passenger list through two routes, and **neither reaches the vehicle itself**:

- `ServerPlayer#startRiding` sends the passenger packet to **the one who mounted**;
- `ServerEntity#sendChanges` broadcasts to **the players tracking this vehicle**.

But "tracking yourself" does not exist — if the vehicle is a player, his own client never receives that packet,
and the screen shows "somebody pinned on your head without moving". So `EntityPlayerVehiclePassengersMixin`
sends one extra packet to him at the TAIL of `addPassenger` / `removePassenger`.
**The rule switch is not checked here**: when the rule is turned off the passenger is kicked off, and that moment needs the packet too, otherwise the client stays stuck.

#### ⑤ Why the "build height limit" red text has to be swallowed

This is not the mod computing a wrong position, it is a vanilla branch:

1. `ServerPlayer#startRiding` internally does `connection.teleport(...)`, which makes `awaitingPositionFromClient` non-null;
2. one client right-click on a block sends **two** `ServerboundUseItemOnPacket`s (main hand + off hand);
3. the main-hand packet seats the player; the off-hand packet arrives right after, and at that moment the server "is waiting for the teleport confirmation",
   so it falls into the "do not handle the interaction" fallback branch — and that branch sends `sendBuildLimitMessage(true, maxY)`.

So sitting down **inevitably** pops "the build height limit is 319" once, and only the seated player sees it.
`SeatBuildLimitMessageMixin` swallows that message while "sitting on a seat / within 10 ticks of sitting down or standing up" and forwards everything else unchanged.

#### ⑥ Why block seats need "continuous resend + periodic resend"

The seat entity is a mod entity, and the client's tracking of it lags, especially right after it is created.
So: **resend to the player himself for 10 consecutive ticks** after sitting down, and afterwards **resend to every player within 96 blocks once every 10 ticks**.
Without the resend you get "others see you sitting while you stand still", or the other way round.

---

### Design rules and boundaries

| # | Scenario | Note |
|---|---|---|
| 1 | Stack depth cap | `maxStackDepth` defaults to 2 and is clamped to `[2, 8]`; a value of 1 means nobody may ride and is clamped to 2 |
| 2 | Creative mode | `allowCreative` defaults to `true`; with it off, creative players cannot sit (to avoid accidental clicks) |
| 3 | The target starts riding something else | Controlled by `dismountOnTargetRide` (default `true` kicks the rider off) |
| 4 | Dimension change | A "pending dismount" marker is recorded and handled on the **next tick** (the passenger list cannot be changed in the same tick) |
| 5 | The seat block is broken | The tick loop sees the block is no longer sittable → stand up + teleport back to the seat position |
| 6 | Inventory full / seat creation failed | If `addFreshEntity` or `startRiding` fails, the seat is `discard`-ed and the click passes through silently |
| 7 | Only the "lower half" can be sat on | Top slabs, double slabs and upside-down stairs are refused; the test uses **tags + block state**, so modded blocks work too |
| 8 | Can you actually fall into the block when standing up | Standing up `teleport`s back to the seat position and zeroes momentum and fall distance (no falling into the block, no fall damage) |
| 9 | The seat is a real entity | It counts towards the entity count and is loaded / unloaded with its chunk; when the chunk unloads the tick loop stands the player up (the price of using vanilla riding) |
| 10 | The sneak key | Block sitting: hold Shift to stand up (vanilla `Player#rideTick` behaviour); head sitting: the target sneaking kicks the passenger off |

---

<a id="中文"></a>

## 中文

> 相关规则：[`PlayerHeadSitting`](../02-rules/player.md#playerheadsitting玩家头顶乘坐) ·
> [`PlayerBlockSitting`](../02-rules/player.md#playerblocksitting坐在楼梯--半砖上)
> 代码入口：`handler/player/{headSitting,blockSitting}/**`、`cache/impl/{PlayerHeadSittingAllow,PlayerBlockSittingPreference}.java`、
> `mixin/player/playerHeadSitting/**`、`mixin/entity/blockSitting/SeatBuildLimitMessageMixin.java`、
> `entity/BlockSeatEntity.java`

---

### 解决什么问题

两条规则都是"让玩家坐到一个原版不允许坐的载具上"：

| 规则 | 载具是什么 | 原版为什么不支持 |
|---|---|---|
| 玩家头顶乘坐 | **另一个玩家** | `EntityTypes.PLAYER` 是 `noSave()`，`Entity#startRiding` 里的 `canSerialize()` 检查直接拒绝（原版 `/ride` 报 `ERROR_MOUNTING_PLAYER` 也是这个原因） |
| 坐在楼梯 / 半砖上 | 模组的隐形座位实体 `BlockSeatEntity` | 同样 `noSave()`；而且楼梯 / 半砖本身不是可坐方块 |

两条规则因此共用同一个"绕过点"：
`EntityStartRidingMixin` 把那次 `canSerialize()` 的结果**按条件改成 true**（乘客必须是玩家，
载具是玩家 → 看头顶乘坐规则；载具是模组座位 → 看方块乘坐规则）。之后
`couldAcceptPassenger` / 叠罗汉环检测 / `canRide` / 发包全部还是原版逻辑。

---

### 设计一：玩家头顶乘坐

```
右键空手点玩家
   ↓ 入口只校验：服务端 / 主手 / 玩家×玩家 / 空手
trySit
   ├ 规则开关（按玩家判权限档位）
   ├ 拒坐条件：自己 / 旁观者 / 已在骑东西 / 目标头上有人 / 目标已死 / 目标在睡 /
   │            目标没允许被坐 / （可选）创造模式 / 叠罗汉超上限
   ├ startRiding(target, force = true)  ← 跳过原版 canRide/canAddPassenger（上面已自判）
   ├ 立刻按碰撞箱把乘客摆到头顶（避免第一帧穿模）
   └ 挥一下手（交互反馈）
        ↓ 之后
每 tick 巡检：重新对齐座位 + 处理"该下来了"的情况
```

**"该下来了"判定**（每 tick）：规则关了 / 目标不再允许 / 目标换维度（记一个待下坐标记，下一个 tick 处理）/ 目标死了 / 目标在睡觉 / 目标开始潜行 /（可选）目标开始骑别的东西。

**三条清理兜底**（这是本规则细节最多的地方，见下文"为什么下线必须自己清"）：

- `DISCONNECT`：把"他骑别人"和"别人骑他"两个方向都拆干净，再扫一遍全服幽灵乘客；
- `ENTITY_UNLOAD`：只在**卸载的是玩家**时处理（船 / 矿车等原版载具的卸载有自己的机制，不插手）；
- `JOIN`：强制清一次残留，并在下一个 tick 补发乘客表（进服那一刻客户端还没收完世界）。

---

### 设计二：坐在楼梯 / 半砖上

```
右键空手点方块
   ↓ 入口校验：端侧（客户端只拦"建造高度之外"的点击）/ 主手 / 空手
trySit
   ├ 规则开关 + 玩家自己的客户端开关（PlayerBlockSittingPreference，默认允许）
   ├ 方块条件：不是旁观/骑行/潜行/点底面；方块在 stairs/slabs 标签且是"下半"；
   │            座面可算；上方有空间
   ├ 生成 BlockSeatEntity（隐形）→ setPos(座面) → addFreshEntity
   ├ rider.startRiding(seat, force = true)
   └ 立刻推一次乘客包
        ↓ 之后
每 tick 巡检：规则关 / 玩家关 / 玩家没了 / 座位被移除 / 方块被挖 → 起身 + 传送回座面
```

**座面怎么算**（`BlockSittingSeat`）：用方块**自己的碰撞箱**，取"平的那一面"的顶面 ——
所以下半砖和下半楼梯一样高（`y + 0.5`），上半砖 / 上下颠倒的楼梯是 `y + 1.0`。
楼梯还会往**矮的那一侧**挪 2 像素，否则坐着模型的背会贴住上一级的立面。
**没有任何偏移配置**：位置完全由碰撞箱决定。

---

### 关键取舍

#### ① 为什么"能不能坐"的开关要放在玩家自己手里

- **头顶乘坐默认不允许**：这是个社交功能，陌生人能随便骑你头上很烦，而且会影响你的视野。
  所以 `PlayerHeadSittingAllow` 的语义是"**不在名单里 = 不允许**"——**没装模组的玩家天然被保护**，
  开服也不用担心被别人利用。
- **方块乘坐默认允许**：它只影响自己（你不会因为别人坐在楼梯上而受影响），
  所以 `PlayerBlockSittingPreference` 的语义反过来——"**不在名单里 = 允许**"，
  只有客户端明确关掉的人才会被记进"不想坐"名单。

两张表都是**会话态**（玩家退出即清，进服由客户端重新上报），不落盘。

#### ② 为什么用"玩家当载具"而不是自绘一个坐骑实体

玩家当载具能白拿原版的一整套能力：乘客位置同步、碰撞、上下坐动画、旁观者视角、
以及"乘客跟着载具一起被区块管理"。代价就是 `noSave()` 带来的一堆清理责任（见下一条）。
自绘实体的话，这些全要自己实现，而且和原版骑乘行为永远对不齐。

方块乘坐则必须用实体（原版骑乘要求载具有实体），所以模组自己注册了一个
**不可见的 `BlockSeatEntity`**，用完即 `discard`。

乘坐期间乘客的位置由服务端摆放，但移动包仍走原版校验：`GlobalSpeedBypass` 只静默它放行的两条路径
（玩家自身移动、玩家控制的乐魂），乘坐不在其中 —— 所以本该被拦下的移动告警不会被一起吞掉。

#### ③ 为什么下线 / 卸载 / 重进"三条兜底一条都不能少"

载具是**玩家**，而 `EntityTypes.PLAYER` 是 `noSave()` 的。载具一旦离线：

- 它留下的 `passengers` 条目就是一个**已经被移除的玩家实例**；
- 巡检每 tick 还会把它 `snapToSeat` + `ChunkMap#move` —— 等于把"已移除的实体"反复塞回区块追踪；
- 结果：存档时刷 `Trying to save removed ... skipping`，客户端因为收到指向不存在实体 id 的乘客表而**隐身**。

`DISCONNECT` 是最主要的清理点（这一刻实体还在世界里，`stopRiding()` 合法）；
`ENTITY_UNLOAD` 兜"被移除"；`JOIN` 兜"上一局残留"。外加巡检里的"幽灵乘客"检查：
乘客不在线就强制 `stopRiding` 并忘掉它。

#### ④ 为什么要给"载具本人"补发乘客表

原版乘客列表的同步有两条路，**都不会到达载具本人**：

- `ServerPlayer#startRiding` 把乘客包发给**骑上去的那个人**；
- `ServerEntity#sendChanges` 广播给**追踪这个载具的玩家**。

而"自己追踪自己"是不存在的 —— 载具如果是个玩家，他自己的客户端永远收不到这个包，
画面上就会出现"有人被钉在自己头上不动"。所以 `EntityPlayerVehiclePassengersMixin`
在 `addPassenger` / `removePassenger` 的 TAIL 手动补一个包给他。
**这里不判规则开关**：规则关掉时会把乘客踢下来，那一下同样需要这个包，否则客户端会一直粘着。

#### ⑤ 为什么要吞掉"建筑高度上限"那条红字

这不是模组算错坐标，而是原版的一个分支：

1. `ServerPlayer#startRiding` 内部会 `connection.teleport(...)`，把 `awaitingPositionFromClient` 置成非空；
2. 客户端一次右键方块会发**两个** `ServerboundUseItemOnPacket`（主手 + 副手）；
3. 主手那一包让人坐上去；副手那一包紧接着到达，此时"正在等传送确认"，于是走进
   "不处理交互"的兜底分支 —— 而原版在这个分支里发的是 `sendBuildLimitMessage(true, maxY)`。

所以坐上去的瞬间**必然**弹一次"建筑高度上限是 319"，而且只有坐的人能看到。
`SeatBuildLimitMessageMixin` 在"正坐在座位上 / 刚坐下或起身的 10 tick 内"把这条吃掉，
其它情况原样转发。

#### ⑥ 为什么方块座位要"连续补发 + 周期补发"

座位实体是模组实体，客户端追踪它有时间差（尤其是刚生成的那一瞬间）。
所以：坐下后**连续 10 tick 给本人补发**，之后**每 10 tick 给 96 格内的玩家补发一次**。
不补的话会出现"别人看你坐着、你自己站在原地"或反过来的错位。

---

### 设计约定与边界

| # | 场景 | 说明 |
|---|---|---|
| 1 | 叠罗汉上限 | `maxStackDepth` 默认 2、夹在 `[2, 8]`；填 1 等于谁都不许骑，会被夹到 2 |
| 2 | 创造模式 | `allowCreative` 默认 `true`；关掉后创造玩家不能坐（防误触） |
| 3 | 目标开始骑别的东西 | 由 `dismountOnTargetRide` 控制（默认 `true` 时把人踢下来） |
| 4 | 换维度 | 记一个"待下坐"标记，**下一个 tick** 处理（不能在同一 tick 里改乘客表） |
| 5 | 座位方块被挖掉 | 巡检发现方块不再是可坐类型 → 起身 + 传送回座面位置 |
| 6 | 背包满 / 座位生成失败 | `addFreshEntity` 或 `startRiding` 失败时 `discard` 座位并静默放行 |
| 7 | 只能坐"下半" | 上半砖、双半砖、上下颠倒的楼梯都不给坐；用**标签 + 属性**判定，模组方块也能坐 |
| 8 | 起身时会不会掉进方块里 | 起身时会 `teleport` 回座面位置、清零动量与摔落距离（避免掉进方块 / 摔伤） |
| 9 | 座位是真实实体 | 会计入实体数量、随区块加载卸载；区块卸载后巡检会起身（这是用原版骑乘换来的代价） |
| 10 | 潜行键 | 方块乘坐：按住 Shift 起身（原版 `Player#rideTick` 行为）；头顶乘坐：目标潜行会把乘客踢下来 |
