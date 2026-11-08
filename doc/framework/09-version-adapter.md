# Version Adapter Layer

The 26.2↔26.3 per-class differences, what belongs in the version layer, the upgrade order and the manual upgrade checklist of the VillagerDetails mod.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> **Scope**: the same-named / differing classes of `v26.2/` (12 classes) and `v26.3/` (13 classes).
> Source-set splicing is done by the build scripts (`build.gradle` / `settings.gradle` pick one copy according to
> `mcVersion`); this chapter covers **Java-level differences** only, and Gradle details live in the build scripts.
> The injection-point inventory is in [08](08-mixin-inventory.md).

---

### 1. Why a version adapter layer exists

Both supported versions (26.2 / 26.3) compile against **one body of mod code**, but the vanilla API moved in
five incompatible ways between them:

| Type | Example | Can `@Accessor` / conditional compilation get around it |
|---|---|---|
| **API incompatibility** (method name / return type changed) | `ServerboundMoveVehiclePacket`: 26.2 has `position()/yRot()/xRot()`, 26.3 has `movingTo(): PositionAndRotation` | ❌ no way around it (the call site itself does not compile) |
| **Signature change** (parameter types changed) | `Block#playerDestroy`: 26.2 `(Level, Player, …)`, 26.3 `(ServerLevel, ServerPlayer, …)`; `@Inject` demands an exact per-parameter match | ❌ (`@Coerce` only covers primitives) |
| **Class / method extracted** | portal teleport: 26.3 extracts `canTeleport + teleport` into `Entity#teleportToPortalDestination` | ❌ (the `@Redirect` target changed) |
| **Field renamed / retyped** | `VillagerTrade.givenItemModifiers`(List) → `givenItemModifier`(`Optional<Holder<…>>`) | ✅ an `@Accessor` flattens it (but the accessor itself has to be written per version) |
| **Pure behaviour difference** (same method, different internals) | bone-meal flower growth | ✅ identical logic → one shared copy is possible (the project still compiles one per version) |

**Decision rule**: **does not compile → version layer; merely "want a different value / a different field
name" → stay in `common` and flatten it with an accessor.**

---

### 2. The two version layers side by side

| File (identical package name) | 26.2 | 26.3 | Difference |
|---|---|---|---|
| `handler/craft/BoxCraftPlan` | ✅ | ✅ | crafting execution (including the `Prediction`-related drop calls) — one copy per version, compared line by line during an upgrade (see 5.2) |
| `handler/entity/villager/trader/decompose/ItemProvisioner` | ✅ | ✅ | same (recipe table + `provision`'s `drop(..., Prediction.SERVER_ONLY)`) |
| `handler/entity/villager/trader/refresh/TradeEnchantmentScanner` | ✅ | ✅ | scans the enchantment candidate pool (depends on the field shape of `VillagerTrade`) |
| `handler/.../refresh/TradePoolChecker` | ✅ | ✅ | static producibility check; the 26.3 copy additionally has to handle `SequenceFunction` (see below) |
| `handler/.../refresh/TradeSetAdapter` | ✅ | ✅ | the flattening layer for `TradeSet → HolderSet<VillagerTrade>` |
| `handler/player/headSitting/PlayerHeadSittingSwing` | ✅ | ✅ | the swing API (main / off-hand parameter shape) |
| `mixin/block/collectable/PlayerDestroyMixin` | ✅ | ✅ | **narrowed signature**: `(Level,Player)` → `(ServerLevel,ServerPlayer)` |
| `mixin/block/portalBinding/EntityPortalMixin` | ✅ | ✅ | **injection point**: `handlePortal` → `teleportToPortalDestination` |
| `mixin/fix/move/VehicleMoveTrustMixin` | ✅ | ✅ | **packet API**: `position()/yRot()/xRot()` → `movingTo()` |
| `mixin/plant/GrassBlockBoneMealMixin` | ✅ | ✅ | identical content, compiled once per version |
| `mixin/villager/trader/autoTrader/VillagerTradeMixin` | ✅ | ✅ | **field shape**: `givenItemModifiers`(List) → `givenItemModifier`(`Optional<Holder<…>>`), method names unchanged |
| `mixin/villager/trader/autoTrader/SequenceFunctionMixin` | — | ✅ | **26.3 only**: the array form is folded into a `SequenceFunction` and has to be taken apart to find `enchant_randomly` |
| `client/setting/util/InputCompat` | ✅ | ✅ | client input API (unrelated to business logic, one copy per version) |

**Count**: 26.2 = 12 classes, 26.3 = 13 classes (the extra one is `SequenceFunctionMixin` alone).

`PlayerHeadSittingSwing` and `InputCompat` each carry a class javadoc naming their difference:
`swing(hand, SwingAnimation, boolean)` vs `swing(hand, boolean)`, and `InputConstants.Type.KEYBOARD` vs
`KEYSYM` with `isKeyDown` no longer taking a `Window` parameter. Reading the two copies as a pair during an
upgrade is the intended workflow: the javadoc says what changed, and 5.2 / 5.3 say which files must match and
which ones must not.

---

### 3. Per-class differences in detail (ordered by "easiest to get wrong")

#### ① `EntityPortalMixin`: the redirect target was replaced outright

```
26.2:  @Redirect(method="handlePortal",
                 target="Entity;teleport(TeleportTransition)Entity")
26.3:  @Redirect(method="teleportToPortalDestination",
                 target="Entity;teleport(TeleportTransition)Entity")
```

26.3 extracted "decide whether teleporting is allowed + call teleport" into a new method, so **the same
injection intent** lands on a different method. The logic (resolving the destination / arbitration / async
enhanced mode) is identical in both, and both delegate to `PortalBindingService` / `PortalLocator` in `common`.

#### ② `VehicleMoveTrustMixin`: the packet fields were merged

```
26.2:  Vec3 pos = packet.position();  float yRot = packet.yRot();  float xRot = packet.xRot();
26.3:  PositionAndRotation movingTo = packet.movingTo();
       Vec3 pos = movingTo.position(); yRot = movingTo.yRot(); xRot = movingTo.xRot();
```

**Only the short packet-reading part stays in the version layer; everything else is in `common`'s
`VehicleMoveTrust`** (which takes a `Vec3` plus primitives). This is the model of "the version layer is the
thinnest possible shim": any logic that does not depend on the version API never moves down.

#### ③ `PlayerDestroyMixin`: an `@Inject` signature must match parameter by parameter

26.3 narrowed the parameters of `playerDestroy` from `Level/Player` to `ServerLevel/ServerPlayer`. The
callback signature of `@Inject` must be **exactly equal, parameter by parameter**, to the target (`@Coerce`
only covers primitives), so two copies are the only option.

#### ④ `VillagerTradeMixin` + `SequenceFunctionMixin`: the field shape changed

26.3 turned `given_item_modifier` from "a list" into "a single `Optional<Holder<LootItemFunction>>`", decoded
through `Codec.either`: **an array in JSON gets folded into a `SequenceFunction`**. So "can this trade roll a
given enchantment" cannot be answered with `instanceof EnchantRandomlyFunction`; the `SequenceFunction` has to
be taken apart and its members searched. The difference is flattened by `TradePoolChecker#givenModifiers`, so
call sites are unchanged.

#### ⑤ `TradeSetAdapter` / `TradePoolChecker` / `TradeEnchantmentScanner`: why they live in the version layer

They do not touch a changed API directly, but they depend on the field shape of `VillagerTrade` (`wants` /
`additionalWants` / `gives` / `givenItemModifier`), and the accessors that read those fields are in the version
layer — so they go along. **This is a chain descent caused by "depending on a version-layer accessor"**, not an
incompatibility of their own.

---

### 4. Recommended order for upgrading to 26.4

1. **Compile `common` first**: build `common/src/{main,server,client}` against the new version and collect every
   compile error — that error list is the candidate set of "things that must move down".
2. **Decide one by one** (with the rule in §1): what an `@Accessor` can flatten stays in `common`; what does not
   compile gets a copy in `v26.4/`.
3. **Copy the mixin json**: start from `v26.3`'s copy, drop the version-specific entries that are no longer
   needed, add the new ones.
4. **Run the startup first**: `defaultRequire = 1` makes "injection point not found" show up at startup, which
   is far easier to trace than a runtime symptom.
5. **Exercise the business logic last**: walk the difference table in the version chapter one row at a time
   (especially files like `ItemProvisioner` / `BoxCraftPlan` that show no version difference yet exist once per
   version).

---

### 5. Manual upgrade checklist

The consistency between the two `villagerdetails.server.mixins.json` files, the two version layers and this
chapter is maintained **by hand**: the build has no automatic cross-check, and compilation does not catch a
divergence either, because the two same-named copies never reference each other. The price is that every
injection-point change and every version bump has to be walked through the list below — which is exactly why
it is written down here as a release-checklist step.

#### 5.1 The three things to do whenever the version layer or an injection point is touched

| # | What to do | Why |
|---|---|---|
| 1 | After changing the **injection point / target method / `cancellable`** of any mixin in `common`, look at both `villagerdetails.server.mixins.json` files | Missing an entry = a silently inert mixin (`defaultRequire=1` only crashes when "the target is not found"; a class that is not listed is not even looked up) |
| 2 | After changing any version-layer file, **change the same-named file of the other version too** | The two same-named classes never reference each other, so a divergence cannot be found at compile time |
| 3 | On a major upgrade, walk through the order in §4 (compile `common` → collect errors → move down → copy the json → run the startup first) | The compile-error list is the candidate set of "must move down" |

#### 5.2 Files that must be diffed by hand during an upgrade (5 groups that should be identical)

```
v26.2/src/server/java/com/villagerdetails/handler/craft/BoxCraftPlan.java
v26.2/src/server/java/com/villagerdetails/handler/entity/villager/trader/decompose/ItemProvisioner.java
v26.2/src/server/java/com/villagerdetails/handler/entity/villager/trader/refresh/TradeSetAdapter.java
v26.2/src/server/java/com/villagerdetails/handler/entity/villager/trader/refresh/TradeEnchantmentScanner.java
v26.2/src/server/java/com/villagerdetails/handler/entity/villager/trader/refresh/TradePoolChecker.java
        ↑ compare each of these with the same path under v26.3
```

#### 5.3 The whitelist of files that may (and must) differ (6 groups, 8 files)

| File | Difference |
|---|---|
| `mixin/block/portalBinding/EntityPortalMixin` | `@Redirect` target: `handlePortal` → `teleportToPortalDestination` |
| `mixin/fix/move/VehicleMoveTrustMixin` | packet API: `position()/yRot()/xRot()` → `movingTo()` |
| `mixin/block/collectable/PlayerDestroyMixin` | `playerDestroy` signature narrowed (`@Inject` demands an exact per-parameter match) |
| `mixin/villager/trader/autoTrader/VillagerTradeMixin` | `givenItemModifiers`(List) → `givenItemModifier`(Optional<Holder>) |
| `mixin/villager/trader/autoTrader/SequenceFunctionMixin` | **exists in 26.3 only** |
| `mixin/plant/GrassBlockBoneMealMixin` / `handler/player/headSitting/PlayerHeadSittingSwing` / `client/setting/util/InputCompat` | content / signatures follow the version; both copies must exist |

#### 5.4 How to compare the `mixins.json` files

```
v26.2/src/server/resources/villagerdetails.server.mixins.json   ← 45 entries
v26.3/src/server/resources/villagerdetails.server.mixins.json   ← 46 entries (one more: SequenceFunctionMixin)
```

Compare the `mixins` arrays of the two files with any diff tool: **the difference must fall on the single
entry that is allowed to differ**; any other difference means one version was left behind.

#### 5.5 The client has version-specific classes too

`villagerdetails.client.mixins.json` has `[]` for both `client` and `mixins`, but the client **does** have
version adaptation: `client/setting/util/InputCompat` (`Type.KEYSYM`→`KEYBOARD`, and `isKeyDown` drops the
`Window` parameter). So "the client has no mixins" ≠ "the client needs no adaptation" — do not forget the
`client/` directory under `v26.2` / `v26.3` when upgrading.

---

<a id="中文"></a>

## 中文

> 覆盖：`v26.2/`（12 个类）与 `v26.3/`（13 个类）的同名/差异类。
> 源集拼接由构建脚本完成（`build.gradle` / `settings.gradle` 按 `mcVersion` 挑一份编入），
> 本文只讲 **Java 层差异**，Gradle 细节请直接看构建脚本。
> 注入点清单见 [08](08-mixin-inventory.md)。

---

### 1. 为什么要有一层版本适配

两个支持版本（26.2 / 26.3）在**同一份 mod 代码**上编译，但原版 API 在这两个版本之间发生了
五类不可兼容的变化：

| 类型 | 例子 | 能不能用 `@Accessor` / 条件编译绕过去 |
|---|---|---|
| **API 不兼容**（方法名/返回类型变了） | `ServerboundMoveVehiclePacket`：26.2 是 `position()/yRot()/xRot()`，26.3 是 `movingTo(): PositionAndRotation` | ❌ 无法绕过（调用点本身编不过） |
| **签名变化**（参数类型变了） | `Block#playerDestroy`：26.2 `(Level, Player, …)`，26.3 `(ServerLevel, ServerPlayer, …)`；`@Inject` 要求逐参精确匹配 | ❌（`@Coerce` 只支持基本类型） |
| **类/方法被抽出去** | portal 传送：26.3 把 `canTeleport + teleport` 抽成 `Entity#teleportToPortalDestination` | ❌（`@Redirect` 的 target 变了） |
| **字段改名/改类型** | `VillagerTrade.givenItemModifiers`(List) → `givenItemModifier`(`Optional<Holder<…>>`) | ✅ 用 `@Accessor` 就能抹平（但 accessor 本身要按版本写） |
| **纯行为差异**（同一方法，内部实现不同） | 骨粉刷花 | ✅ 逻辑相同 → 可以共用一份（但项目选择各编一份） |

**判定口诀**：**编不过 → 进版本层；只是“想换个值/换个字段名” → 留在 common 用 accessor 抹平。**

---

### 2. 两个版本层的文件对照

| 文件（包名完全相同） | 26.2 | 26.3 | 差异点 |
|---|---|---|---|
| `handler/craft/BoxCraftPlan` | ✅ | ✅ | 合成执行（含 `Prediction` 相关的 drop 调用）—— 两版各一份，升级时人工逐行比对（见 5.2） |
| `handler/entity/villager/trader/decompose/ItemProvisioner` | ✅ | ✅ | 同上（配方表 + `provision` 的 `drop(..., Prediction.SERVER_ONLY)`） |
| `handler/entity/villager/trader/refresh/TradeEnchantmentScanner` | ✅ | ✅ | 扫附魔候选池（依赖 `VillagerTrade` 的字段形状） |
| `handler/.../refresh/TradePoolChecker` | ✅ | ✅ | 静态可产性判定；26.3 版需要额外处理 `SequenceFunction`（见下） |
| `handler/.../refresh/TradeSetAdapter` | ✅ | ✅ | `TradeSet → HolderSet<VillagerTrade>` 的抹平层 |
| `handler/player/headSitting/PlayerHeadSittingSwing` | ✅ | ✅ | 挥动动作的 API（主/副手参数形状） |
| `mixin/block/collectable/PlayerDestroyMixin` | ✅ | ✅ | **签名收窄**：`(Level,Player)` → `(ServerLevel,ServerPlayer)` |
| `mixin/block/portalBinding/EntityPortalMixin` | ✅ | ✅ | **注入点**：`handlePortal` → `teleportToPortalDestination` |
| `mixin/fix/move/VehicleMoveTrustMixin` | ✅ | ✅ | **包 API**：`position()/yRot()/xRot()` → `movingTo()` |
| `mixin/plant/GrassBlockBoneMealMixin` | ✅ | ✅ | 内容一致，随版本各编一份 |
| `mixin/villager/trader/autoTrader/VillagerTradeMixin` | ✅ | ✅ | **字段形状**：`givenItemModifiers`(List) → `givenItemModifier`(`Optional<Holder<…>>`)，方法名保持不变 |
| `mixin/villager/trader/autoTrader/SequenceFunctionMixin` | — | ✅ | **仅 26.3**：数组写法被折成 `SequenceFunction`，必须拆开才能找到 `enchant_randomly` |
| `client/setting/util/InputCompat` | ✅ | ✅ | 客户端输入 API（与业务无关，两版各一份） |

**计数**：26.2 = 12 个类，26.3 = 13 个类（多出的只有 `SequenceFunctionMixin`）。

`PlayerHeadSittingSwing` 与 `InputCompat` 的类注释都写明了差异点（`swing(hand, SwingAnimation, boolean)`
vs `swing(hand, boolean)`；`InputConstants.Type.KEYBOARD` vs `KEYSYM`、`isKeyDown` 去掉了 `Window` 参数）。
升级时把两份同名文件成对读是既定流程：注释说明“变了什么”，5.2 / 5.3 说明“哪些必须一致、哪些必须不一致”。

---

### 3. 逐条差异详解（按“最容易改错”排序）

#### ① `EntityPortalMixin`：重定向目标整个换了

```
26.2:  @Redirect(method="handlePortal",
                 target="Entity;teleport(TeleportTransition)Entity")
26.3:  @Redirect(method="teleportToPortalDestination",
                 target="Entity;teleport(TeleportTransition)Entity")
```

26.3 把“判断能不能传 + 调 teleport”抽成了新方法，所以**同一个注入意图**落在不同方法上。
逻辑（解析目的地 / 仲裁 / 增强模式异步）两版一致，且都委托给 common 的 `PortalBindingService` / `PortalLocator`。

#### ② `VehicleMoveTrustMixin`：包字段被合并

```
26.2:  Vec3 pos = packet.position();  float yRot = packet.yRot();  float xRot = packet.xRot();
26.3:  PositionAndRotation movingTo = packet.movingTo();
       Vec3 pos = movingTo.position(); yRot = movingTo.yRot(); xRot = movingTo.xRot();
```

**读包那一小段留在版本层，其余全部在 common 的 `VehicleMoveTrust`**（它只吃 `Vec3` + 基本类型）。
这是“版本层只做最薄的一层”的样板：凡是不依赖版本 API 的逻辑，一律不下沉。

#### ③ `PlayerDestroyMixin`：`@Inject` 的签名必须逐参精确

26.3 把 `playerDestroy` 的参数从 `Level/Player` 收窄成 `ServerLevel/ServerPlayer`。
`@Inject` 的回调签名必须与目标**逐参数精确相等**（`@Coerce` 只支持基本类型），所以只能写两份。

#### ④ `VillagerTradeMixin` + `SequenceFunctionMixin`：字段形状变了

26.3 把 `given_item_modifier` 从“列表”改成“单个 `Optional<Holder<LootItemFunction>>`”，
反序列化走 `Codec.either`：**JSON 里写数组时会被折成一个 `SequenceFunction`**。
于是“这笔交易能不能刷出某个附魔”不能直接 `instanceof EnchantRandomlyFunction`，
必须先拆 `SequenceFunction` 再找成员。差异被 `TradePoolChecker#givenModifiers` 抹平（调用点不变）。

#### ⑤ `TradeSetAdapter` / `TradePoolChecker` / `TradeEnchantmentScanner`：为什么要放在版本层

它们本身不直接碰“变化的 API”，但依赖 `VillagerTrade` 的字段形状（`wants` / `additionalWants` / `gives` /
`givenItemModifier`），而读这些字段的 accessor 在版本层 —— 所以一起放。**这是“依赖版本层 accessor”导致的连锁下沉**，
不是它们自己不可兼容。

---

### 4. 升级到 26.4 的推荐顺序

1. **先编 common**：把 `common/src/{main,server,client}` 对着新版本编一遍，收集所有编译错误
   —— 错误清单就是“必须下沉的候选”。
2. **逐个判定**（用 §1 的口诀）：能 `@Accessor` 抹平的留在 common；编不过的复制一份到 `v26.4/`。
3. **复制 mixin json**：从新版本的 `v26.3` 复制，删掉已经不需要的版本专属条目、补上新条目。
4. **先跑启动**：`defaultRequire = 1` 会让“注入点找不到”在启动时就暴露，比运行期好查。
5. **最后跑业务**：版本差异表逐条过一遍（尤其是 `ItemProvisioner` / `BoxCraftPlan` 这类“没有版本差异却各自一份”的文件）。

---

### 5. 人工升级 checklist / Manual upgrade checklist

两份 `villagerdetails.server.mixins.json`、两份版本层实现与这篇文档之间的一致性，**靠人工**维持：
构建里没有自动校验，编译期也发现不了分叉 —— 两份同名类从不互相引用。
代价是每次动注入点、每次升版本都要把下面这张清单走一遍；所以它被写成发布 checklist 的一步，
而不是“有空再说”的清理项。

#### 5.1 每次「动到版本层或注入点」都要做的三件事

| # | 要做的事 | 为什么 |
|---|---|---|
| 1 | 改 `common` 里任何 mixin 的**注入点 / 目标方法 / `cancellable`** 后，两份 `villagerdetails.server.mixins.json` 都要看一遍 | 漏登记 = mixin 静默失效（`defaultRequire=1` 只在“找不到目标”时崩，类没登记连找都不找） |
| 2 | 改版本层任一文件后，**同步改另一版本的同名文件** | 两版同名类不互相引用，编译期发现不了分叉 |
| 3 | 升级大版本时，按 §4 的顺序（先编 common → 收集错误 → 下沉 → 复制 json → 先跑启动）走一遍 | 编译错误清单就是“必须下沉”的候选列表 |

#### 5.2 升级时**必须人工 diff** 的文件（应完全一致的 5 组）

```
v26.2/src/server/java/com/villagerdetails/handler/craft/BoxCraftPlan.java
v26.2/src/server/java/com/villagerdetails/handler/entity/villager/trader/decompose/ItemProvisioner.java
v26.2/src/server/java/com/villagerdetails/handler/entity/villager/trader/refresh/TradeSetAdapter.java
v26.2/src/server/java/com/villagerdetails/handler/entity/villager/trader/refresh/TradeEnchantmentScanner.java
v26.2/src/server/java/com/villagerdetails/handler/entity/villager/trader/refresh/TradePoolChecker.java
        ↑ 与 v26.3 下同路径文件逐一对比
```

#### 5.3 **允许（也必须）不同**的文件白名单（6 组 / 8 个文件）

| 文件 | 差异点 |
|---|---|
| `mixin/block/portalBinding/EntityPortalMixin` | `@Redirect` 目标：`handlePortal` → `teleportToPortalDestination` |
| `mixin/fix/move/VehicleMoveTrustMixin` | 包 API：`position()/yRot()/xRot()` → `movingTo()` |
| `mixin/block/collectable/PlayerDestroyMixin` | `playerDestroy` 签名收窄（`@Inject` 需逐参精确） |
| `mixin/villager/trader/autoTrader/VillagerTradeMixin` | `givenItemModifiers`(List) → `givenItemModifier`(Optional<Holder>) |
| `mixin/villager/trader/autoTrader/SequenceFunctionMixin` | **仅 26.3 存在** |
| `mixin/plant/GrassBlockBoneMealMixin` / `handler/player/headSitting/PlayerHeadSittingSwing` / `client/setting/util/InputCompat` | 内容/签名随版本，两份都要在 |

#### 5.4 `mixins.json` 的对照方法

```
v26.2/src/server/resources/villagerdetails.server.mixins.json   ← 45 条
v26.3/src/server/resources/villagerdetails.server.mixins.json   ← 46 条（多 SequenceFunctionMixin）
```

用任意 diff 工具比较两份的 `mixins` 数组：**差异必须只落在“允许不同”的那 1 条**；
其它任何差异都说明有一版漏改。

#### 5.5 客户端也有版本专属类

`villagerdetails.client.mixins.json` 的 `client` / `mixins` **都是空数组**，
但客户端**仍有**版本适配：`client/setting/util/InputCompat`（`Type.KEYSYM`→`KEYBOARD`、
`isKeyDown` 去掉 `Window` 参数）。所以“客户端没有 mixin”≠“客户端不需要适配”——
升级时别忘了 `v26.2` / `v26.3` 下的 `client/` 目录。
