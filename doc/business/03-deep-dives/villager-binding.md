# Deep dive: villager binding tools (`VillagerBedBinding` / `VillagerWorkBlockBinding` / `VillagerMoveController` / `VillagerBatchBedBinding`)

How four tools edit vanilla brain memory and POI occupancy directly instead of keeping a mapping table of their own.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> Related rules: [`VillagerBedBinding`](../02-rules/villager.md#villagerbedbinding村民床编辑器) ·
> [`VillagerWorkBlockBinding`](../02-rules/villager.md#villagerworkblockbinding村民工作方块编辑器) ·
> [`VillagerMoveController`](../02-rules/villager.md#villagermovecontroller村民移动控制器) ·
> [`VillagerBatchBedBinding`](../02-rules/villager.md#villagerbatchbedbinding批量村民床编辑器)
>
> Code entry: `handler/binding/**`, `cache/impl/{EBSelectionStateCache,BBSectionStateCache}.java`,
> `event/impl/register/{EBSectionManager,BBSelectionManager}.java`, `handler/entity/villager/move/**`

---

### What problem it solves

In vanilla "which bed a villager sleeps in and which workstation it uses" is entirely assigned by the AI to the nearest candidates; the player can only influence it indirectly through **placement**:

- want to give one villager a specific bed? Break every bed nearby and leave one;
- want to change the workstation? Break the old block and wait for it to find a new one;
- want it to walk to a chosen spot? Fence a corridor out of blocks, or push it with a boat.

What these four things share (change the bed, change the workstation, make it walk there, batch-bind beds) is:
**they edit the villager's vanilla AI memory and POI occupancy**, not a mapping table of the mod's own.
So the design goal of the tools is "change it the way vanilla does, but let the player name the exact target".

---

### Overall design

```
① Tool        rename a name tag to bed / work / move + hold a lead (and sneak)
                ↓   BindingUtil.isHoldingAnyTool: rule on + lead + name match + entity type match
② Dual selection   EB selection: click the entity (villager) first, then the block (bed / workstation / destination)
                ↓   both sides present → BindHandler.chooseUtil
③ Validate + consume   peek validation (the tool is still in hand, the target really is a villager) → only then clear the selection
                ↓
④ Dispatch    VillagerBindServer.event(type) → VillagerBindHandler.bindBed / bindWorkBlock / bindMove
                ↓
⑤ Apply       change vanilla POI occupancy + write brain memory (HOME / JOB_SITE / POTENTIAL_JOB_SITE / the mod's DESTINATION)
                ↓
⑥ Feedback    one lead is consumed only when the binding succeeded; failures / duplicate bindings consume nothing
```

Batch bed binding takes another chain: `BB selection (region) → scan villagers + scan beds → nearest match → bind pair by pair → one summary message`.

---

### Key trade-offs

#### ① Why "name tag + lead" as the tool instead of a command

- What the player wants is "I am looking at this villager, this bed"; a command means typing two coordinates;
- a lead is itself the metaphor of "tying two things together", and renaming a name tag is a zero-cost "tool switch";
- the three names (`bed` / `work` / `move`) share one item, so no new item has to be registered,
  and there is no migration problem of "items in old saves becoming invalid".

The cost: **you must sneak** (`isHoldingTool` checks `isShiftKeyDown`) and the name must match exactly (case-insensitive).

#### ② Why an "entity + block" dual selection instead of a single click

One operation has to name **two objects** (which villager, which block), so it takes two steps:

- click the entity first → stored in `EBSelectionStateCache` (entity UUID);
- then click the block → completed, and the binding fires as soon as both sides are present.

**Why validation uses `peek` and not "take it out, clearing it"**: validation can fail (the tool in hand changed, the click was not a villager).
If taking it out cleared the selection, the player would have to select everything again. So:

- `peek` only looks, never takes;
- once validation passes and the flow really enters the binding, `clear` runs (so one selection cannot trigger the binding twice).

#### ③ Why only a successful bind consumes the lead

Bed binding and workstation binding both have failure paths (the block is not a bed, POI registration failed, the villager already traded, a duplicate binding …).
Charging first and validating later gives the player "the lead is gone and nothing happened". So the deduction sits at the end of `chooseUtil`
and **runs only when `bindVillager` returned true**.

#### ④ Why change vanilla POI / brain memory instead of storing a mapping

This is exactly what the vanilla AI (sleeping, working, restocking) reads:

| Bound object | Change |
|---|---|
| Bed | release the old bed's POI occupancy → make sure the new bed is registered as a POI → write the `HOME` memory |
| Work block | release the old block's POI occupancy → look up the profession by POI type → set the profession → write `JOB_SITE` / `POTENTIAL_JOB_SITE` → refresh the brain |
| Move destination | write the mod memory `DESTINATION` + `WALK_TARGET`, and lock the activity to `GO_TO_DESTINATION` |

With a mapping table of our own there would be two truths — "the mod thinks it sleeps in bed A, the vanilla AI thinks bed B" — and the observable result is a villager wandering around at night. **Editing vanilla data = only one truth.**

#### ⑤ Why the workstation binding checks "already traded"

Vanilla semantics: once a villager has traded, its profession is **locked**. Allowing the workstation to change would allow "switch profession but keep the trade table",
which would create self-contradictory villagers (farmer profession, librarian trade table).

So `bindWorkBlock` has two checks:

- the target villager **itself** already traded → refuse;
- the villager that was **on the target workstation before** already traded → refuse too (its workstation cannot be taken away).

#### ⑥ Why moving has to "lock the activity"

Setting `WALK_TARGET` alone is not enough: the villager's brain may pick a new activity every tick (idle, work, sleep),
and picking one overrides `WALK_TARGET`. So:

- give the villager an activity of the mod's own (`GO_TO_DESTINATION`);
- `BrainMixin` intercepts the HEAD of `setActiveActivity`: as long as the `DESTINATION` memory is present,
  it **forces the activity to going to the destination** (`CORE` excepted — that is the brain-stem level activity).

On arrival (within 0.7 blocks) or a timeout (60 seconds) the memory is cleared and the villager returns to its daily behaviour.

#### ⑦ Why batch bed binding limits the volume, skips unloaded chunks and collects only bed heads

- **volume limit** (`MAX_SCAN_VOLUME = 32768` = 32³): beds can only be scanned cell by cell, and a slightly larger selection is hundreds of thousands of `getBlockState` calls;
- **skip unloaded chunks**: querying an unloaded chunk triggers loading / generation, and one careless selection can freeze the server;
- **bed head only** (`BedPart.HEAD`): otherwise one bed is recorded twice and the match binds "two villagers to one bed".

Matching uses the generic matcher for **nearest pairing** (greedy nearest); batch mode disables the per-pair messages and replies with one summary.

---

### Design rules and boundaries

| # | Scenario | Note |
|---|---|---|
| 1 | No standable spot found | `VillagerBindHandler#bindMove` reports failure with `msg.villager.move.no_ground` when no ground is found, and consumes nothing (the lead stays in hand) |
| 2 | `StoodFinder` search depth | It searches at most 3 blocks down (`MAX_SEARCH_DOWN = 3`) |
| 3 | Batch matching | The greedy "nearest pairing"; with many villagers and many beds it is not globally optimal |
| 4 | Region too large | A batch selection bigger than 32³ throws; the command layer turns it into an error reply |
| 5 | Duplicate binding | Binding the same bed again reports "already bound" and returns false (**no second lead is consumed**) |
| 6 | The name must be exact | `bed` / `work` / `move` (case-insensitive, but leading / trailing spaces are part of the name) |
| 7 | Literal vs register name | A rule's command literal must match its register name; the startup self-check logs a WARN when a rule registers a different literal |
| 8 | Cross-dimension | A villager and the target block in different dimensions are refused (`checkSameDimension`), because POIs are stored **per dimension** |
| 9 | Workstation and profession | Changing the workstation changes the profession but **does not reroll the trade table** — use [`VillagerAutoTrader`](auto-trader.md) for that |

---

<a id="中文"></a>

## 中文

> 相关规则：[`VillagerBedBinding`](../02-rules/villager.md#villagerbedbinding村民床编辑器) ·
> [`VillagerWorkBlockBinding`](../02-rules/villager.md#villagerworkblockbinding村民工作方块编辑器) ·
> [`VillagerMoveController`](../02-rules/villager.md#villagermovecontroller村民移动控制器) ·
> [`VillagerBatchBedBinding`](../02-rules/villager.md#villagerbatchbedbinding批量村民床编辑器)
>
> 代码入口：`handler/binding/**`、`cache/impl/{EBSelectionStateCache,BBSectionStateCache}.java`、
> `event/impl/register/{EBSectionManager,BBSelectionManager}.java`、`handler/entity/villager/move/**`

---

### 解决什么问题

原版里"村民睡哪张床、用哪个工作方块"完全由 AI 就近分配，玩家只能靠**摆放位置**间接影响：

- 想给某只村民指定床？把附近的床全拆了，只留一张；
- 想换工作方块？把旧方块拆掉，等它自己找新的；
- 想让它走到指定位置？只能拿方块围出通道，或者用船推。

这四件事（改床、改工作方块、让它走过去、批量绑床）的共同点是：
**它们改的是村民的"原版 AI 记忆和 POI 占用"**，不是模组自己的一张映射表。
所以工具的设计目标是"像原版那样改，但让玩家能精确指定"。

---

### 整体设计

```
① 工具        命名牌改名为 bed / work / move + 拴绳（并且要蹲下）
                ↓   BindingUtil.isHoldingAnyTool：规则开着 + 拴绳 + 名字匹配 + 实体类型匹配
② 双选区      EB 选区：先点实体（村民），再点方块（床 / 工作方块 / 目标位置）
                ↓   两边齐了 → BindHandler.chooseUtil
③ 校验+消费   peek 校验（工具还在手上、目标确实是村民）→ 通过才 clear 选区
                ↓
④ 分派执行    VillagerBindServer.event(type) → VillagerBindHandler.bindBed / bindWorkBlock / bindMove
                ↓
⑤ 落地        改原版 POI 占用 + 写 brain memory（HOME / JOB_SITE / POTENTIAL_JOB_SITE / 模组 DESTINATION）
                ↓
⑥ 反馈        绑定成功才消耗 1 根拴绳；失败 / 重复绑定不消耗
```

批量绑床走另一条链：`BB 选区（区域）→ 扫村民 + 扫床 → 就近匹配 → 逐对绑床 → 一条汇总消息`。

---

### 关键取舍

#### ① 为什么用"命名牌 + 拴绳"当工具，而不是命令

- 玩家要的是"我在游戏里看着这只村民、这张床"，敲命令得打两个坐标；
- 拴绳本身就是"把两样东西连起来"的隐喻，命名牌改名则是零成本的"工具切换"；
- 三个名字（`bed` / `work` / `move`）共用一个物品，不需要注册新物品，
  也就不存在"老存档里物品失效"的迁移问题。

代价：**必须蹲下**（`isHoldingTool` 里判 `isShiftKeyDown`）、名字必须精确匹配（忽略大小写）。

#### ② 为什么是"实体 + 方块"双选区，而不是点一下就算

一次操作要指定**两个对象**（哪只村民、哪个方块），所以需要两步：

- 先点实体 → 记进 `EBSelectionStateCache`（实体 UUID）；
- 再点方块 → 补齐，两边齐了立刻触发绑定。

**为什么校验用 `peek` 而不是"取出来就清掉"**：校验可能失败（手上工具换了、点的不是村民）。
如果取出即清，玩家就得从头再选一遍。所以：

- `peek` 只看不动；
- 校验通过、确实要进入绑定流程了，才 `clear`（避免同一次选中被重复触发绑定）。

#### ③ 为什么只有成功才消耗拴绳

绑床 / 绑工作方块都有失败路径（方块不是床、POI 注册失败、村民已交易过、重复绑定……）。
如果先扣再校验，玩家会遇到"拴绳没了但什么都没发生"。所以扣减放在 `chooseUtil` 的最后，
**且只在 `bindVillager` 返回 true 时**执行。

#### ④ 为什么直接改原版 POI / brain memory，而不是自己存一份映射

原版的 AI（睡觉、上班、补货）读的就是这些：

| 绑定对象 | 改动 |
|---|---|
| 床 | 释放旧床的 POI 占用 → 确保新床已注册 POI → 写 `HOME` 记忆 |
| 工作方块 | 释放旧方块的 POI 占用 → 按 POI 类型反查职业 → 设置职业 → 写 `JOB_SITE` / `POTENTIAL_JOB_SITE` → 刷新 brain |
| 移动目标 | 写模组记忆 `DESTINATION` + `WALK_TARGET`，并把 activity 锁到 `GO_TO_DESTINATION` |

自己存一份映射表的话，会出现"模组认为它睡 A 床、原版 AI 认为它睡 B 床"的两套真相，
最终表现为村民半夜乱走。**改原版数据 = 只有一套真相。**

#### ⑤ 工作方块为什么要检查"已交易"

原版语义：村民交易过一次之后职业就**锁定**了。如果允许改工作方块，就等于允许"换职业但保留交易表"，
那会造出一堆自相矛盾的村民（职业是农民、交易表是图书管理员）。

所以 `bindWorkBlock` 有两道检查：

- 目标村民**自己**已经交易过 → 拒绝；
- 目标工作方块上**原来那个村民**已经交易过 → 也拒绝（不能把别人的工作方块抢走）。

#### ⑥ 移动为什么要"锁 activity"

只设 `WALK_TARGET` 是不够的：村民的 brain 每个 tick 都可能在挑新的 activity（闲逛、上班、睡觉），
一挑就会把 `WALK_TARGET` 顶掉。所以：

- 给村民挂一个模组自己的 activity（`GO_TO_DESTINATION`）；
- `BrainMixin` 在 `setActiveActivity` 的 HEAD 处拦截：只要 `DESTINATION` 记忆还在，
  就**强制锁在前往目的地**（`CORE` 除外，那是脑干级活动）。

到达（0.7 格内）或超时（60 秒）后清记忆，村民自然回到日常行为。

#### ⑦ 批量绑床为什么要限体积、跳过未加载区块、只收床头

- **限体积**（`MAX_SCAN_VOLUME = 32768` = 32³）：床只能逐格扫，选区稍大就是几十万次 `getBlockState`；
- **跳过未加载区块**：查未加载区块会触发加载 / 生成，一个手滑的选区就能把服务器卡死；
- **只收床头**（`BedPart.HEAD`）：不然一张床会被记两次，匹配时会"一张床绑两只村民"。

匹配用通用匹配器做**就近配对**（贪心最近），批量模式下关掉逐条消息，最后只回一条汇总。

---

### 设计约定与边界

| # | 场景 | 说明 |
|---|---|---|
| 1 | 找不到可站立位置 | `VillagerBindHandler#bindMove` 找不到落脚点时用 `msg.villager.move.no_ground` 报失败，且**不消耗任何东西**（拴绳还在手上） |
| 2 | `StoodFinder` 搜索深度 | 向下最多搜 3 格（`MAX_SEARCH_DOWN = 3`） |
| 3 | 批量匹配 | 贪心的"最近配对"，多村民多床时不保证全局最优 |
| 4 | 区域超限 | 批量绑床选区 > 32³ 会抛异常（命令层兜成错误提示） |
| 5 | 重复绑定 | 同一张床重复绑定会提示"已绑定"并返回 false（**不重复扣拴绳**） |
| 6 | 名字必须精确 | `bed` / `work` / `move`（忽略大小写，但前后空格算名字的一部分） |
| 7 | 字面量与注册名 | 规则的命令字面量必须与注册名一致；不一致时启动自检会打 WARN |
| 8 | 跨维度 | 村民与目标方块不同维度时直接拒绝（`checkSameDimension`），因为 POI 是**按维度**存的 |
| 9 | 工作方块与职业 | 换工作方块会改职业，但**不会重刷交易表** —— 想重刷请用 [`VillagerAutoTrader`](auto-trader.md) |
