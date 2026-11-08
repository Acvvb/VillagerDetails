# Deep dive: portal binding (`PortalBinding`)

How two framed regions replace vanilla "nearest portal" matching, and why the locator would rather say "I did not see the whole region" than guess.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> Related rule: [`PortalBinding`](../02-rules/block.md#portalbinding传送门绑定) ·
> [`PortalBindingEnhanced`](../02-rules/block.md#portalbindingenhanced传送门绑定增强版) ·
> Compare: [`PortalPiglinNoSpawn`](../02-rules/entity.md#portalpiglinnospawn禁用地狱门刷僵尸猪灵) (also region binding, but it only intercepts)
>
> Code entry: `handler/binding/block/portalbinding/{PortalBindingHandler,PortalBindingService,PortalLocator,PortalBindingManager}.java`,
> `config/impl/section/item/PortalBindingConfig.java`, version layer `mixin/block/portalBinding/EntityPortalMixin.java`
> (one copy in `v26.2/`, one in `v26.3/`)

---

### What problem it solves

A vanilla portal is "nearest match": enter a nether portal in the overworld and the destination is **the nearest portal around the 1:8-scaled coordinates**.
What the player wants is "these two portals must connect", e.g. the mob-farm portal always leads back to the base portal instead of to the neighbouring nether hub.

Vanilla has no way to express that correspondence, so this rule introduces **region binding**:
"this overworld region ↔ this nether region; entering a portal on this side sends you to that side".

---

### Overall design

```
① Bind (one-off setup)
   /ec c PortalBinding set         frame an overworld region → cached as "this dimension, waiting to bind"
   /ec c PortalBinding set         frame a nether region    → cached as "this dimension, waiting to bind"
   /ec c PortalBinding bind        both sides present → write one @ID-bound record
                                    and "pre-warm": whichever region already has a portal gets resolved and cached
                ↓
② Teleport (every entity walking into a portal)   EntityPortalMixin redirects the vanilla teleport(...)
   look up the binding → multi-hit arbitration → cache hit? → region scan? → only then build a portal → rebuild TeleportTransition
                ↓
③ Maintenance
   show / view / delete / refresh   list, highlight, delete, clear the cache and rescan
```

**One binding = two dimensions + two regions + one cached portal base position per side + one `#ID`.**

---

### Key trade-offs

#### ① Why "pair two sides" instead of one record per region

A binding is essentially **a pair**: overworld region A ↔ nether region B. Split into two independent records, something would have to maintain "who is paired with whom", while the player only recognises a single number in chat.
So `bind` requires both sides to be `set` first (cached in `PortalSelectionCache`) and then writes two regions + one `#ID` in one go.

`#ID`s are **monotonically increasing and never recycled on delete**, so `#3` in chat always means the same record — somebody adding or removing entries after you read the list cannot shift it, and no "list version number" validation is needed.

#### ② Why find portals with the POI engine instead of scanning blocks cell by cell

A region can be large (`128 × 64 × 128` at most). Walking a million-plus blocks on the main thread is a disaster.
Vanilla `PortalForcer` finds portals through the **POI engine** (`PoiManager`), and this code reuses the same one:

- `ensureLoadedAndValid` loads the chunks / POI sections inside the search range (so cold regions are still searchable);
- `getInSquare` filters by **X/Z** only, `radius` is a **horizontal** radius, Y is unbounded;
- `Occupancy.ANY` is mandatory — portal POIs have `maxTickets = 0`, so `HAS_SPACE` / `IS_OCCUPIED` would filter all of them out.

#### ③ Scan budget: why "rather say I did not see it all than guess"

`ensureLoadedAndValid` **loads chunks synchronously**: the bigger the radius, the more chunks the main thread has to load in one go.
So one POI call has a hard cap `POI_SCAN_RADIUS_LIMIT = 128`; a bigger region is covered with **tiles**:

| Parameter | Value | Purpose |
|---|---|---|
| `TILE` | 128 | edge length of one tile (the safe radius of a single scan) |
| `TILE_STEP` | 96 (`128 × 3/4`) | centre step between neighbouring tiles, deliberately smaller than the edge so coverage **overlaps** and no portal slips through a border |
| `MAX_TILES` | 64 | at most 64 tiles (≈1000×1000 blocks); a normal binding only uses 2×2 |
| `MAX_COLD_CHUNKS` | 256 | at most 256 unloaded chunk columns loaded synchronously per scan (a cold chunk costs generation + saving, hundreds of ms upwards) |

Over budget, **only the centre tile is scanned** and the result is marked `complete = false`. That flag matters a lot:

> `portal == null` with `complete == false` does **not** mean "there is no portal in the region", it means "I did not see the whole region".
> Only with `complete == true` and no portal found may a **new portal be built** inside the region.

That is how "do not guess" is implemented: if the scan is incomplete, return null and let the caller fall back to vanilla; never build a portal in the wrong place just because nothing was found.

#### ④ Multi-hit arbitration (option A): why "vanilla theoretical destination" instead of distance to the player

One region can hit several portals (many portals inside it, or the region spans both dimensions). The arbitration rules:

1. **no hit** → return null, the caller falls back to vanilla;
2. **single hit** → pass through (exactly equivalent to a 1:1 binding, no extra computation);
3. **several hits** → keep the candidates whose "target dimension = the vanilla counterpart dimension" (overworld ↔ nether); if none has it, fall back to all of them;
   if it still spans dimensions, take the first dimension in the list (**deterministic**, not dependent on map iteration order);
4. among the candidates take the closest by the **squared Euclidean distance** to the **vanilla theoretical destination**, **no threshold**;
5. equal distances are broken by the `pos` lexicographic order.

"Vanilla theoretical destination" = overworld ↔ nether XZ scaled by 1:8 with Y unchanged (same as `PortalForcer`); other dimensions are not scaled.
Why not "distance to the player": the player's current position has nothing to do with where vanilla would send them — only the theoretical destination makes "the binding ≈ vanilla behaviour, just a different portal" so nothing feels arbitrary.

#### ⑤ Cache: pre-warm, hit, invalidation, refresh

- **pre-warm on bind**: the target region is **loaded and already has a portal** → resolve it and write the cache (avoids a stall on the first teleport).
  **No automatic portal building** — the player only framed a region; the system should not quietly put a portal there.
- **first teleport**: the cache is empty → resolve lazily (scan / build) → write the cache back.
- **validate before every teleport**: is `getBlockState(cached)` still a portal; if not, rescan automatically (portal broken / replaced).
- **refresh**: clear the cached portal base positions on both sides, rescan immediately and write back once (the config is persisted only once),
  for the "I built a new portal after binding and want that one" case.

#### ⑥ Why enhanced mode is asynchronous

Normal mode requires "a portal already exists at the target". Enhanced mode (`PortalBindingEnhanced`, **available to players only**) skips that requirement and instead looks for a **standable foothold** — giving up the teleport if none is found. That can span several ticks
(`findSpawn` adds tickets to load chunks itself), so it goes through a `CompletableFuture`:

- the teleport is deferred to the future's completion callback; no foothold → tell the player and **do not teleport** (better not to teleport than to drop someone into the void);
- `portalProcess` is cleared before queueing: otherwise the portal animation restarts and the same player **queues the async teleport twice**;
- the callback guards with `isRemoved()` for "replaced by another teleport while waiting";
- **only the synchronous fast path "a portal already exists in the region" writes the cache**: the async `findSpawn` returns ordinary ground, and caching ground as a portal would send people into the ground next time.

`PortalBindingEnhanced` deliberately has **no command object of its own**: it would register the same Brigadier literal as `PortalBinding`, and identical literals are silently merged, so it exists only as a switch and is toggled with `/ec PortalBindingEnhanced <state>`.

#### ⑦ Why the landing position differs for players and non-players

A player lands at the **centre of the portal base**; a non-player (mob, item) preferably lands **one block outside the portal**.
The reason: a non-player never "walks out", so landing inside the portal can bounce it back after 15 seconds of vanilla judgement, oscillating back and forth.
That difference lives in `EntityPortalMixin`, not in the business layer — it depends on the entity type at the moment of teleport.

---

### Design rules and boundaries

| # | Scenario | Note |
|---|---|---|
| 1 | What the tier gates | `EntityPortalMixin` uses the **global** `isEnabled(PORTAL_BINDING)` because it has to cover mobs and items (they have no notion of permission). So **the tier only gates "who may edit bindings (command admission)", never "who may be force-teleported"** |
| 2 | Region size limit | The binding layer caps at `128 × 64 × 128`; a larger region inserted by hand-editing the config makes the scan "incomplete", and then **no new portal is built** |
| 3 | `refresh` may not pick "the one you wanted" | It only picks "closest to the region centre"; to force a specific portal, break the currently cached one, or frame the selection around the target portal |
| 4 | Enhanced mode never builds a portal | It only looks for a landing spot; the portal may still be built by the normal path |
| 5 | Version differences | `EntityPortalMixin` has **different redirect targets** in 26.2 / 26.3 (26.3 extracted `canTeleport + teleport` into `teleportToPortalDestination`); both copies have to be maintained separately |
| 6 | `portalProcess` after a teleport | Cleared explicitly after a successful teleport, otherwise a destination inside a portal is triggered again immediately by vanilla |
| 7 | No portal found / teleport failed | Always fall back to vanilla (`self.teleport(transition)`); the player is never blocked from moving |
| 8 | Difference from `PortalPiglinNoSpawn` | The latter "intercepts a random tick" (read-only judgement), the former "rewrites the destination" (resolve + maybe build); both share the region concept and `#ID`, but their config sections, selection caches and command trees are independent |

---

<a id="中文"></a>

## 中文

> 相关规则：[`PortalBinding`](../02-rules/block.md#portalbinding传送门绑定) ·
> [`PortalBindingEnhanced`](../02-rules/block.md#portalbindingenhanced传送门绑定增强版) ·
> 对比：[`PortalPiglinNoSpawn`](../02-rules/entity.md#portalpiglinnospawn禁用地狱门刷僵尸猪灵)（同样是区域绑定，但只做拦截）
>
> 代码入口：`handler/binding/block/portalbinding/{PortalBindingHandler,PortalBindingService,PortalLocator,PortalBindingManager}.java`、
> `config/impl/section/item/PortalBindingConfig.java`、版本层 `mixin/block/portalBinding/EntityPortalMixin.java`
> （`v26.2/` 与 `v26.3/` 各一份）

---

### 解决什么问题

原版传送门是"就近匹配"的：从主世界某扇门进下界，落点在**按 1:8 缩放后的坐标附近最近的那扇门**。
玩家想要的是"这两扇门必须互通"，比如刷怪塔的门固定连回基地的门，而不是连到隔壁的地狱交通枢纽。

原版没有任何手段表达这种对应关系，所以这条规则引入**区域绑定**：
"主世界这块区域 ↔ 下界这块区域，进这边的门就送到那边"。

---

### 整体设计

```
① 绑定（一次性配置）
   /ec c PortalBinding set         主世界框一块 → 缓存成"本维度待绑定"
   /ec c PortalBinding set         下界框一块   → 缓存成"本维度待绑定"
   /ec c PortalBinding bind        两侧齐了 → 写入一条 @ID 绑定的记录
                                    并「预热」：哪侧区域里已有门，就先解析并存进缓存
                ↓
② 传送（每次实体进门）            EntityPortalMixin 重定向原版那次 teleport(...)
   查绑定 → 多命中仲裁 → 缓存命中？ → 区域扫描？ → 都没有才建门 → 重建 TeleportTransition
                ↓
③ 维护
   show / view / delete / refresh   列出、高亮、删除、清缓存重扫
```

**一条绑定 = 两侧维度 + 两侧区域 + 两侧缓存的门底坐标 + 一个 `#ID`。**

---

### 关键取舍

#### ① 为什么"两侧配对"而不是一条记录一个区域

绑定关系的本质是**一对**：主世界 A 区域 ↔ 下界 B 区域。如果拆成两条独立记录，
就必须再维护"谁和谁是一对"，而玩家在聊天栏里只认一个编号。
所以 `bind` 要求两侧都先 `set`（缓存在 `PortalSelectionCache` 里），一次写入两条区域 + 一个 `#ID`。

`#ID` **只增不减、删除不回收**，因此聊天栏里的 `#3` 永远指同一条记录 —— 别人在你看完列表之后
增删条目也不会让它跑偏，不需要"列表版本号"那种校验。

#### ② 为什么用 POI 引擎找门，而不是逐格扫方块

区域可能很大（上限 `128 × 64 × 128`）。逐格遍历 100 多万个方块在主线程是灾难。
原版 `PortalForcer` 找门走的就是 **POI 引擎**（`PoiManager`），这里复用同一套：

- `ensureLoadedAndValid` 会把搜索范围内的 chunk / POI 段加载起来（所以冷区域也能查到）；
- `getInSquare` 只按 **X/Z** 过滤，`radius` 是**水平**半径，Y 不限；
- 必须用 `Occupancy.ANY` —— 传送门 POI 的 `maxTickets = 0`，用 `HAS_SPACE` / `IS_OCCUPIED` 会被全部滤掉。

#### ③ 扫描预算：为什么"宁可说没扫全，也不猜"

`ensureLoadedAndValid` 是**同步加载 chunk** 的，一次给的半径越大，主线程一次要加载的 chunk 越多。
所以单次 POI 调用有硬上限 `POI_SCAN_RADIUS_LIMIT = 128`，区域比这更大时**分片铺满**：

| 参数 | 值 | 作用 |
|---|---|---|
| `TILE` | 128 | 一片瓦片的边长（取单次扫描的安全半径） |
| `TILE_STEP` | 96（`128 × 3/4`） | 相邻瓦片中心步进，故意小于边长，让覆盖区**重叠**、边界不漏门 |
| `MAX_TILES` | 64 | 最多铺 64 片（≈1000×1000 格）；正常绑定只用 2×2 片 |
| `MAX_COLD_CHUNKS` | 256 | 一次扫描最多同步加载 256 个未加载的 chunk 列（冷 chunk 的代价是生成+落盘，几百 ms 起） |

超预算时**只扫中心一片**，并把结果标成 `complete = false`。这个标记非常关键：

> `portal == null` 且 `complete == false` ≠ "区域里没有门"，而是"我没看全"。
> 只有 `complete == true` 且没找到门，才允许在区域里**建新门**。

这就是"不猜"的落地方式：看不全就返回 null 让调用方回退原版，绝不因为没扫到就在错误的位置建门。

#### ④ 多命中仲裁（方案 A）：为什么用"原版理论落点"而不是距离玩家

同一个区域可能命中多扇门（区域里门多，或者区域跨两侧维度）。仲裁规则：

1. **无命中** → 返回 null，调用方走原版；
2. **单命中** → 直通（与 1:1 绑定完全等价，不做多余计算）；
3. **多命中** → 优先保留"目标维度 = 原版对应维度"的候选（主↔下）；该维度没有候选时退回全部；
   仍跨维度则取列表首个维度（**确定性**，不依赖 Map 遍历顺序）；
4. 候选中按「**原版理论落点**」的**欧氏距离平方**取最近，**无阈值**；
5. 等距按 `pos` 字典序 tie-break。

"原版理论落点" = 主世界 ↔ 下界 XZ 按 1:8 缩放、Y 不变（与 `PortalForcer` 一致），其它维度不缩放。
为什么不按"离玩家距离"：玩家此刻的位置和"原版会把他送到哪"没有关系 ——
用理论落点才能让"绑定的效果 ≈ 原版效果，只是换了一扇门"，玩家不会觉得莫名其妙。

#### ⑤ 缓存：预热、命中、失效、刷新

- **bind 时预热**：目标区域**已加载且已有门** → 解析并写缓存（避免首次传送卡顿）。
  **不自动建门** —— 玩家只框了块区域，系统不该顺手盖个门出来。
- **首次传送**：缓存为空 → 懒解析（扫描 / 建门）→ 回写缓存。
- **每次传送前校验**：`getBlockState(cached)` 那格还是不是门；不是就自动重扫（门被拆了 / 被替换）。
- **refresh**：清掉两侧缓存的门底坐标并立即重扫、一次性写回（配置只落一次盘），
  用于"绑完之后又建了新门、想换一扇"。

#### ⑥ 增强模式为什么是异步的

普通模式要求"目标位置已有门"。增强模式（`PortalBindingEnhanced`，**只对玩家开放**）跳过这个要求，
改成找一个**站得住的落脚点** —— 找不到就放弃传送。这件事可能跨好几个 tick
（`findSpawn` 自己会加 ticket 加载 chunk），所以走 `CompletableFuture`：

- 传送推迟到 future 完成的回调里；找不到落脚点 → 提示玩家并**不传送**（宁可不传，也不让人掉虚空）；
- 排队前清 `portalProcess`：否则过门动画重新计时，同一个玩家会**排上两次**异步传送；
- 回调里用 `isRemoved()` 兜住"等待期间已被另一次传送替换"的情况；
- **只有"区域内已有门"的同步快路径才回写缓存**：异步 `findSpawn` 给的是普通地面，
  把地面当门缓存，下一次就会往地面里送人。

`PortalBindingEnhanced` 刻意**没有自己的命令对象**：它会和 `PortalBinding` 注册同一个 Brigadier 字面量，
重复的字面量会被静默合并，所以它只以开关的形式存在，用 `/ec PortalBindingEnhanced <状态>` 切换。

#### ⑦ 为什么落点对玩家和非玩家不一样

玩家落在**门底中心**；非玩家（怪物、掉落物）优先落在**门外一格**。
原因：非玩家没有"出门"这个动作，落在门里可能卡 15 秒后被原版判定弹回去，来回震荡。
这个差异写在 `EntityPortalMixin` 里，不在业务层 —— 它依赖传送那一刻的实体类型。

---

### 设计约定与边界

| # | 场景 | 说明 |
|---|---|---|
| 1 | **档位管什么** | `EntityPortalMixin` 用的是**全局** `isEnabled(PORTAL_BINDING)`，因为要覆盖怪物 / 掉落物（它们没有权限概念）。所以**档位只 gate "谁能改绑定（命令准入）"，不 gate "谁能被强制传送"** |
| 2 | 区域大小上限 | 绑定层限制 `128 × 64 × 128`；手改配置文件塞进来的更大区域会让扫描"没扫全"，此时**不会建新门** |
| 3 | `refresh` 选的不是"你想要的那扇" | 只按"离区域中心最近"选；想强制某扇门请拆掉当前缓存的那扇，或把选区框到目标门附近 |
| 4 | 增强模式不建门 | 它只找落地点；门仍然可能由普通路径建 |
| 5 | 版本差异 | 26.2 / 26.3 的 `EntityPortalMixin` **重定向目标不同**（26.3 把 `canTeleport + teleport` 抽成了 `teleportToPortalDestination`），两份必须各自维护 |
| 6 | 传送后 `portalProcess` | 传送成功后会主动清掉，否则落点本身在门里会被原版立刻二次触发 |
| 7 | 找不到门 / 传送失败 | 一律回退原版（`self.teleport(transition)`），不阻断玩家移动 |
| 8 | 与 `PortalPiglinNoSpawn` 的区别 | 后者是"拦截随机刻"（只读判定），前者是"改写落点"（要解析 + 可能建门）；两者共用区域概念与 `#ID`，但配置节、选区缓存、命令树彼此独立 |
