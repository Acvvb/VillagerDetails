# Rule framework

How a rule is turned into framework: `RuleType`'s ten parameters, the five `RuleCache` methods, the permission chain, the two validation gates, the two notification chains and the descriptor contract.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> Covers: `RuleType` · `RuleCache` · `RuleChangeService` · `RuleSwitches` · `RuleStateValidator` ·
> `RuleChangeContext` · `RuleCallbacks` / `RuleModule` / `RuleModuleRegistry` ·
> `RuleDescriptorBuilder` / `RuleDescriptor` · `RuleDataRegistry` · `SwitchComponentType` ·
> `RuleConfigKind` / `RuleDataKind`.
> The business view of "what a rule is" is in [00-overview](../business/00-overview.md) §3; this page is about **how it is implemented as framework**.

---

### 1. `RuleType`: 10 constructor parameters

| # | Parameter | Type | Business meaning | Why a `Supplier` |
|---|---|---|---|---|
| 1 | `registerName` | `String` | the name used in commands / the key on disk | — |
| 2 | `displayName` | `String` | translation key of the display name | — |
| 3 | `displayInfo` | `String` | translation key of the description | — |
| 4 | `category` | `List<RuleCategoryType>` | category (more than one is allowed) | — |
| 5 | `listenerType` | `List<ListenerType>` | which selection tool set it binds to | — |
| 6 | `commandObject` | `Supplier<RegisterServer>` | the command object of `/ec c <rule>`; `null` = switch only | ✅ **must be lazy** |
| 7 | `reloadableConfig` | `Supplier<ReloadableConfig>` | the detailed-config section; `null` = none | ✅ |
| 8 | `quickSwitches` | `Supplier<List<String>>` | all legal values (single choice) | ✅ |
| 9 | `configKind` | `RuleConfigKind` | whether it is a list-config rule | — |
| 10 | `state` | `String` | default value | — |
| 11 (overload) | `stateValidator` | `RuleStateValidator` | format / range validation of the state value | — |

**The three `Supplier`s are not a style question, they are a class-initialisation-order question**:

- `RuleType` is an enum, and `values()` is triggered over and over by `RuleCache`'s static block / `RuleDataRegistry` / `ConfigRegistry`;
- the objects behind 6/7/8 (`NoSqueezeServerImpl.INSTANCE`, `FurnaceDirtyHighlightConfig.INSTANCE`, `ServerLangConfig::preloadLanguageView`) usually read `RuleCache` or a registry back — calling `get()` during enum construction would land on a half-initialised `RuleCache`;
- so `getCommandObject()` / `getReloadableConfig()` / `getQuickSwitches()` all call `get()` **at call time**.

**The other contract methods**:

| Method | Semantics |
|---|---|
| `getDisplayName()` / `getDisplayInfo()` | go through `ServerTranslations.resolve(...)`, i.e. **server-side translation** (not a translation key sent to the client) |
| `requirement()` | delegates to `RuleCache.requirement(this)`, the admission condition of `/ec c` |
| `allRegisterNames()` | **all** register names (in enum order); `/ec <rule>` completion uses it and deliberately does not filter by "has subcommands" |
| `getRuleTypeByRegisterName(name)` | case-insensitive lookup; returns `null` when there is no match |
| `getRuleTypeListByRuleCategoryType(c)` | filter by category (a rule with several categories matches on any of them) |
| `getReloadableConfigs()` | every config instance a rule binds, `distinct()`-ed (for `RuleConfig.reloadChildren`) |

**An initialisation order you have to know**: `RuleCache`'s static block runs `for (RuleType type : values()) setState(type, type.getState())`. That block executes on the **first touch of `RuleType`** (in practice the self-check inside step 2 of server startup, `ConfigManager.registry()`); at that moment `RuleCache.server == null`, so `persist` is a no-op — **rules enter memory with their default values, and the on-disk values are replayed by `SERVER_STARTED`**.

---

### 2. `RuleCache`: five methods + two red lines

| Method | Semantics | Who may call it |
|---|---|---|
| `getState(type)` | the current state string; falls back to the default value | anyone |
| `isEnabled(type)` | anything but `false` is on (a global decision, no permission involved) | anyone |
| `isEnabled(ServerPlayer, type)` / `isEnabled(CommandSourceStack, type)` | global decision first, then **permission node + tier** must be satisfied | anyone |
| `setState(type, state)` | write memory + persist + fire callbacks / listeners | **only `RuleChangeService` and `syncRules`** |
| `persist(type, state)` | marks the config dirty through `RuleSwitchConfig.getOrCreate(server).setBindingState(...)` | internal |
| `requirement(type)` | produces the `Predicate<CommandSourceStack>` of `/ec c` | the framework (`RuleType.requirement`) |

**Red line ①**: business code **must not** call `setState` directly. The public entry point is `RuleChangeService.change(...)`, which runs `canonicalize` + `stateValidator` first; a direct call bypasses both gates and lets an illegal value into the cache.

**Red line ②**: business code **must not** edit `RuleSwitchConfig.bindingStates` directly. `getState` reads the in-memory `EnumMap`, and the two are related as "cache ↔ persistence": editing the file content is not reflected at runtime.

**The short-circuit of `setState` is part of the contract**: when the value is unchanged `setState` returns immediately — no memory write, no persist, **no callback and no listener**. That is right for "changing a rule" (idempotence), so do not try to re-apply a rule by setting the same value again; when a forced re-apply is needed, call the business-side refresh entry point (for example `HappyGhastSpeedHandler#refreshAll`) or notify the listeners explicitly. Replay (`syncRules`) goes through the same `setState`, so rules whose disk value equals the memory value do not fire callbacks during replay either.

#### The three `isEnabled` overloads and the permission chain

```
isEnabled(type)
  └─ state != "false"

isEnabled(player, type)
  ├─ isEnabled(type)
  ├─ SwitchComponentType.permissionLevelOf(state)     // false→null, true→ALL, ops→MODERATORS, …
  └─ player.createCommandSourceStack().checkPermission(node(type), level)
        node = villagerdetails:c.<register name, lower case>          // RuleCache#permissionNode

isEnabled(src, type)   as above, only with a CommandSourceStack
```

**`hasLevel(src, level)` is a different path**: it looks only at "the source's own tier" (`LevelBasedPermissionSet`) and **not at the permission node** — a permission plugin cannot relax it with a "node allow". Command admission uses exactly this one.

#### The fork in `requirement(type)`

```
if (type == null || !isEnabled(type))  → false        // a disabled rule is never usable
if (type.getConfigKind().needsSync())  → hasLevel(src, OWNERS)          // list-config rule: OWNERS hard-coded
else                                    → hasLevel(src, permissionLevelOf(state))  // everything else: by tier
```

Why list-config rules hard-code `OWNERS`: their `/ec c` **edits server configuration directly** (lists / value tables), and "the rule switch is `true`" must not mean "anyone may edit server configuration".

#### `lacksRuleCommandPermission(player)`

The inverted form of the `/ec` global permission (deliberately negative naming; every caller is a "refuse when lacking permission" site): read the state of `SETTING_EC_COMMAND_PERMISSION` → tier (fall back to `OWNERS` when it cannot be read) → decide with `PERM_BASE` (`villagerdetails:c.base`). The network side (sending descriptors / receiving rule-change packets / receiving config-edit packets) uses it too, so it shares one state with the command side's `RuleCommand#hasBasePermission`.

#### `configRuleTypes()`

The `static` block collects the rules whose `configKind.needsSync()` is true into an **immutable List** while it walks `values()`. "Should this be sent to the client" is decided in exactly one place, `RuleConfigKind.needsSync()` — the sync side no longer keeps a hand-written rule list. Note that it is frozen when `RuleCache` is initialised: **adding rules at runtime is impossible** (the enum is fixed anyway), so it is safe.

---

### 3. `RuleChangeService`: the only entry point for changing a rule

```
change(rule, rawState, source)
  ① RuleSwitches.canonicalize(rule.getQuickSwitches(), rawState)
       ├─ empty / all blank → Canonical.BadState
       ├─ custom list (a non-switch word among the candidates) → accept the trimmed text as is
       ├─ predefined list hit (case-insensitive) → return the original text from the list
       └─ no hit → Canonical.BadOption
  ② if ① is Ok: rule.getStateValidator().validate(state)
       └─ non-null → Canonical.Rejected(messageKey, args)   ← no memory write, no persist, no callback
  ③ RuleChangeContext.setSource(source)
      RuleCache.setState(rule, state)
      RuleChangeContext.clear()
```

`reset(rule, source)`: **no validation**, writes `rule.getState()` (the default value from the enum) back and returns `Ok`.

`reload(server)`: `isReloading = true` → `RuleCache.setServer(server)` → `RuleSwitchConfig.syncToSwitch()` → `false`. Callbacks read `isReloading` to skip work that must not happen during replay (such as pushing each entry to the client); both `ExchangeModule` and `HappyGhastSpeedSectionManager` read it.

**The validator of the second gate has a mixed domain**: besides finite numbers inside `[min, max]`, `RuleStateValidator.numericRange(min, max)` lets `true` / `false` through (case-insensitive) — those rules decide "is it on" by whether the state is `false`, so accepting numbers only would make the rule **impossible to turn off**. Its legal state domain is therefore the mixed set `{true, false} ∪ [min, max]`, and its javadoc says so: consumers must not call `Double.parseDouble(state)` directly and should go through `ParseUtils.toDouble(state, fallback)`, which carries a fallback. `HappyGhastSpeed` uses `numericRange(0.0, 5.0)`.

---

### 4. Two notification chains (side by side, not duplicates)

| | `RuleCallbacks` | `RuleCache.addListener` |
|---|---|---|
| Structure | `EnumMap<RuleType, List<Handler>>` — one `CopyOnWriteArrayList` per rule, so **several handlers can hang off one rule** | `CopyOnWriteArrayList<RuleChangeListener>` — a single global list |
| Registration | `RuleModule` implementations register in `RuleModuleRegistry.initAll()`; `register` de-duplicates with `contains` | any class calls `RuleCache.addListener(...)` during initialisation |
| Suits | "the business reaction of this rule" (language switch, audit log; one rule may have several modules) | "several independent concerns" (audit log, client broadcast, speed recalculation…) |
| Trigger | `fireCallbacks` first runs `RuleCallbacks.fire(...)`, then `notifyListeners(...)` | same, left column |
| Exceptions | each handler sits in its own try/catch + ERROR log, so one failing module cannot silence the others | same, left column |

**Why both exist**: the `RuleModule` system is "modular business callbacks" (a reaction registered per rule — `LanguageModule` and `ExchangeModule` may both hang off the same rule), while the framework needs "many parties subscribing to a change of one rule" (sync, audit, descriptor broadcast). The two are different semantics, and forcing them into one would lose features.

**Two guarantees behind "several modules per rule"**: `register` de-duplicates with `contains`, so registering the same handler instance twice is ignored; and `RuleModuleRegistry.initAll()` has its own `AtomicBoolean` idempotence guard, because two entry points both call it. `fire` wraps every handler in its own try/catch, so an exception from one module only logs one ERROR and never silences the other modules on the same rule.

`RuleChangeContext` is a `ThreadLocal<CommandSourceStack>` that lets a callback know "who made this change" (`ExchangeModule`'s audit log uses it to print `(by <player> / Console)`).

---

### 5. Rule descriptors

**Background**: the two categories "Server / Server config" in the config screen **cannot** be driven by the client's own enum — an older client does not have the server's new rules in its `RuleType` at all, so the entry simply would not exist.

`RuleDescriptorBuilder` is **the only** "rule semantics → outgoing data" conversion point:

| Method | Purpose |
|---|---|
| `build(ServerPlayer, String language)` | the whole descriptor set (runs `lacksRuleCommandPermission` first: no permission → send an empty table) |
| `describe(RuleType, String language)` | one rule: `registerName` / translated `name`+`info` / `categories` / `state` / `defaultState` / `enabled` / `switches` / `template` / `data` |
| `buildOptions(String registerName)` | the `id → vanilla value` list of one list-config rule (for completion + prefill); not sent when it exceeds `MAX_OPTION_IDS = 20_000` |

**Text is resolved in the client's language**: `ServerTranslations.resolve(Component, language)` — this is where "command replies follow the server language, the screen follows the client language" is implemented.

The three key trade-offs of `RuleDescriptor`:

1. `name` / `info` are **plain text already translated on the server**; the client no longer looks a translation key up in its own lang (the keys of new rules do not exist in an old client anyway);
2. `enabled` is **computed on the server**; the client does not parse state-value semantics ("is it false" style decisions exist in exactly one place);
3. `template` describes what the list looks like with **template primitives** (`plain` / `valueKind` / `idSource`), so the client does not hard-code "which rule has which kind of list".

---

### 6. `RuleDataRegistry`: the read/write table of the list-config rules

```
static initEntries():  for (RuleType rule : values())
    if (!rule.getConfigKind().needsSync()) continue;
    switch (rule.getReloadableConfig()) {
        case IdSetConfig set        → kind = configName contains "block" ? BLOCK_LIST : ENTITY_LIST
        case NumberConfigServer num → kind = BLOCK_NUMBER
        default                     → skip
    }
```

`Entry` provides: `kind`, `DataStore` (`serializer` / `deserializer` / `setter` / `remover` / `register`), and `vanillaValue(id)` (the vanilla value used to prefill a new entry — `"true"` for a plain list, the block's default value for a numeric one).

`verifyConsistency()` checks both directions at startup: **an Entry exists but the rule is not LIST** / **the rule is LIST but has no Entry** both throw.

The division of labour between `RuleConfigKind.needsSync()` and `RuleDataKind`:

| Enum | Answers | Values |
|---|---|---|
| `RuleConfigKind` | does the config content of this rule need to be synced | `NONE` / `LIST` (`needsSync()` = not NONE) |
| `RuleDataKind` | what the synced content looks like | `ENTITY_LIST` / `BLOCK_LIST` / `BLOCK_NUMBER` (`isPlainList()` tells a plain list from id→number) |

---

### 7. Class-name verification (this page)

| Claim | Verified |
|---|---|
| `RuleType` fields: `registerName` / `displayName` / `displayInfo` / `category` / `listenerType` / `commandObject` / `reloadableConfig` / `quickSwitches` / `configKind` / `state` / `stateValidator` | ✅ |
| `RuleType` overloads: 10 parameters (validator passed as `null`) / 11 parameters | ✅ |
| `RuleCache`: `rules` / `listeners` / `server` / `CONFIG_RULE_TYPES` / `configRuleTypes` / `setServer` / `getServer` / `clearServer` / `RuleChangeListener` / `addListener` / `getState` / `isEnabled`×3 / `hasLevel` / `requirement` / `permissionNode` / `lacksRuleCommandPermission` / `setState` / `syncRules` / `persist` / `fireCallbacks` / `notifyListeners` / `isEnableOfListener` | ✅ |
| `RuleChangeService`: `isReloading` / `change`×2 / `reset` / `reload` | ✅ |
| `RuleSwitches.Canonical`: `Ok` / `BadState` / `BadOption` / `Rejected`; `canonicalize` / `isEnabledState` / `colorOf` / `displayNameOf` / `currentDisplay` | ✅ |
| `RuleStateValidator`: `KEY_NOT_A_NUMBER` / `KEY_OUT_OF_RANGE` / `validate` / `ValidationError.notANumber` / `outOfRange` / `numericRange` | ✅ |
| `RuleCallbacks`: `Handler` / `HANDLERS` (`EnumMap<RuleType, List<Handler>>`) / `register` / `fire` | ✅ |
| `RuleModuleRegistry`: `MODULES = [LanguageModule, ExchangeModule]` / `initAll` | ✅ |
| `RuleDescriptorBuilder`: `MAX_OPTION_IDS = 20_000` / `build` / `describe` / `buildOptions` | ✅ |
| `RuleDescriptor`: `registerName` / `name` / `info` / `categories` / `state` / `defaultState` / `enabled` / `switches` / `template` / `data` + `Switch` / `ListTemplate` / `ValueKind` / `DataEntry` | ✅ |
| `RuleDataRegistry`: `Entry` / `DataStore` / `setFlag` / `initEntries` / `addFloatEntry` / `verifyConsistency` / `all` / `of` | ✅ |
| `RuleDataKind`: `ENTITY_LIST` / `BLOCK_LIST` / `BLOCK_NUMBER` / `isPlainList` | ✅ |
| `RuleConfigKind.needsSync()` = not `NONE` | ✅ |

---

<a id="中文"></a>

## 中文

> 覆盖：`RuleType` · `RuleCache` · `RuleChangeService` · `RuleSwitches` · `RuleStateValidator` ·
> `RuleChangeContext` · `RuleCallbacks` / `RuleModule` / `RuleModuleRegistry` ·
> `RuleDescriptorBuilder` / `RuleDescriptor` · `RuleDataRegistry` · `SwitchComponentType` ·
> `RuleConfigKind` / `RuleDataKind`。
> 业务视角的"规则是什么"在 [00-overview](../business/00-overview.md) §3；本篇讲**它怎么被实现成框架**。

---

### 1. `RuleType`：10 个构造参数

| # | 参数 | 类型 | 业务含义 | 为什么是 `Supplier` |
|---|---|---|---|---|
| 1 | `registerName` | `String` | 命令里的名字 / 落盘键 | — |
| 2 | `displayName` | `String` | 展示名的翻译键 | — |
| 3 | `displayInfo` | `String` | 描述的翻译键 | — |
| 4 | `category` | `List<RuleCategoryType>` | 分类（可多个） | — |
| 5 | `listenerType` | `List<ListenerType>` | 绑定哪套选区工具 | — |
| 6 | `commandObject` | `Supplier<RegisterServer>` | `/ec c <规则>` 的命令对象；`null` = 纯开关 | ✅ **必须懒加载** |
| 7 | `reloadableConfig` | `Supplier<ReloadableConfig>` | 细项配置节；`null` = 无 | ✅ |
| 8 | `quickSwitches` | `Supplier<List<String>>` | 全部合法取值（单选） | ✅ |
| 9 | `configKind` | `RuleConfigKind` | 是否逐条配置类 | — |
| 10 | `state` | `String` | 默认值 | — |
| 11（重载） | `stateValidator` | `RuleStateValidator` | 状态值的格式 / 范围校验 | — |

**三个 `Supplier` 的懒加载不是风格问题，是类初始化顺序问题**：

- `RuleType` 是枚举，`values()` 被 `RuleCache` 静态块 / `RuleDataRegistry` / `ConfigRegistry` 反复触发；
- 而 6/7/8 指向的对象（`NoSqueezeServerImpl.INSTANCE`、`FurnaceDirtyHighlightConfig.INSTANCE`、`ServerLangConfig::preloadLanguageView`）
  往往会反过来读 `RuleCache` 或注册表 —— 如果在枚举构造期就 `get()`，会踩到"`RuleCache` 还没初始化完"的半初始化状态；
- 所以 `getCommandObject()` / `getReloadableConfig()` / `getQuickSwitches()` 都是**调用时才 `get()`**。

**其它契约方法**：

| 方法 | 语义 |
|---|---|
| `getDisplayName()` / `getDisplayInfo()` | 走 `ServerTranslations.resolve(...)`，**服务端翻译**（不是翻译键下发） |
| `requirement()` | 委托 `RuleCache.requirement(this)`，即 `/ec c` 的准入条件 |
| `allRegisterNames()` | **全部**注册名（按枚举顺序）；`/ec <规则>` 的补全用它，故意不过滤"有没有子命令" |
| `getRuleTypeByRegisterName(name)` | 大小写不敏感查找；找不到返回 `null` |
| `getRuleTypeListByRuleCategoryType(c)` | 按分类筛选（一条规则多分类时任一命中即可） |
| `getReloadableConfigs()` | 所有规则绑定的配置实例，`distinct()` 去重（供 `RuleConfig.reloadChildren`） |

**一个必须知道的初始化时序**：`RuleCache` 的 static 块会 `for (RuleType type : values()) setState(type, type.getState())`。
这个 static 块在**第一次触碰 `RuleType` 时**执行（实际是开服第 2 步 `ConfigManager.registry()` 里的自检），
此时 `RuleCache.server == null`，所以 `persist` 是空操作 —— **规则先以默认值进入内存，磁盘值由 `SERVER_STARTED` 回灌**。

---

### 2. `RuleCache`：五个方法 + 两条红线

| 方法 | 语义 | 谁可以调 |
|---|---|---|
| `getState(type)` | 当前状态字符串；没有则回退默认值 | 任意 |
| `isEnabled(type)` | `!false` 即开启（全局判定，不看权限） | 任意 |
| `isEnabled(ServerPlayer, type)` / `isEnabled(CommandSourceStack, type)` | 先全局判定，再要求**权限节点 + 等级**达标 | 任意 |
| `setState(type, state)` | 写内存 + persist + 触发回调 / 监听器 | **只有 `RuleChangeService` 和 `syncRules`** |
| `persist(type, state)` | 经 `RuleSwitchConfig.getOrCreate(server).setBindingState(...)` 标脏 | 内部 |
| `requirement(type)` | 生成 `/ec c` 的 `Predicate<CommandSourceStack>` | 框架（`RuleType.requirement`） |

**红线 ①**：业务**不要**直接调 `setState`。对外入口是 `RuleChangeService.change(...)`，
它负责先做 `canonicalize` + `stateValidator` 两道校验；直接调会绕过校验，让非法值进入缓存。

**红线 ②**：业务**不要**直接改 `RuleSwitchConfig.bindingStates`。`getState` 读的是内存 `EnumMap`，
两者是"缓存 ↔ 持久化"的关系，直接改文件内容不会反映到运行时。

**`setState` 的短路也是契约**：值没变时 `setState` 直接返回 —— 不写内存、不落盘、**不触发回调、不通知监听器**。
这对"改规则"是合理的（幂等），所以别拿"重设同一个值"当让规则重新生效的手段；
需要强制重跑副作用时，调业务侧自己的刷新入口（例如 `HappyGhastSpeedHandler#refreshAll`）或显式通知监听器。
回灌（`syncRules`）走的是同一个 `setState`，所以"磁盘值 == 内存值"的规则在回灌时同样不会触发回调。

#### `isEnabled` 的三条重载与权限链

```
isEnabled(type)
  └─ state != "false"

isEnabled(player, type)
  ├─ isEnabled(type)
  ├─ SwitchComponentType.permissionLevelOf(state)     // false→null, true→ALL, ops→MODERATORS, …
  └─ player.createCommandSourceStack().checkPermission(node(type), level)
        node = villagerdetails:c.<注册名小写>          // RuleCache#permissionNode

isEnabled(src, type)   同上，只是用 CommandSourceStack
```

**`hasLevel(src, level)` 是另一条路**：只看"来源自身的等级"（`LevelBasedPermissionSet`），
**不看权限节点** —— 权限插件没法用一个"节点放行"把它放宽。命令准入用的就是它。

#### `requirement(type)` 的分叉

```
if (type == null || !isEnabled(type))  → false        // 规则关着一律不可用
if (type.getConfigKind().needsSync())  → hasLevel(src, OWNERS)          // 逐条配置类：写死 owner
else                                    → hasLevel(src, permissionLevelOf(state))  // 其余：按档位
```

为什么逐条配置类写死 `OWNERS`：它的 `/ec c` 是**直接改服务器配置项**（名单 / 数值表），
"规则开关是 `true`"不该等于"人人能改服务端配置"。

#### `lacksRuleCommandPermission(player)`

`/ec` 总权限的反向判定（故意用否定命名，调用方清一色是"没权限就拒绝"）：
读 `SETTING_EC_COMMAND_PERMISSION` 的状态 → 等级（读不出来退回 `OWNERS`）→ 用 `PERM_BASE`（`villagerdetails:c.base`）判。
网络侧（下发描述符 / 收改规则包 / 收配置编辑包）都用它，和命令侧 `RuleCommand#hasBasePermission` 共享同一份状态。

#### `configRuleTypes()`

`static` 块在遍历 `values()` 时顺手把 `configKind.needsSync()` 的规则收集成**不可变 List**。
"要不要下发给客户端"只由 `RuleConfigKind.needsSync()` 决定一处 —— 同步侧不再手写规则名单。
注意它在 `RuleCache` 类初始化时定死：**运行期新增规则不可能**（枚举本来就固定），所以安全。

---

### 3. `RuleChangeService`：改规则的唯一入口

```
change(rule, rawState, source)
  ① RuleSwitches.canonicalize(rule.getQuickSwitches(), rawState)
       ├─ 空 / 全空白 → Canonical.BadState
       ├─ 自定义列表（候选里有非开关词）→ 原样接受 trim 后的文本
       ├─ 预定义列表命中（忽略大小写）→ 返回列表里的原文
       └─ 都不命中 → Canonical.BadOption
  ② 若 ① 是 Ok：rule.getStateValidator().validate(state)
       └─ 非 null → Canonical.Rejected(messageKey, args)   ← 不写内存、不落盘、不触发回调
  ③ RuleChangeContext.setSource(source)
      RuleCache.setState(rule, state)
      RuleChangeContext.clear()
```

`reset(rule, source)`：**不做校验**，直接把 `rule.getState()`（枚举里的默认值）写回，返回 `Ok`。

`reload(server)`：`isReloading = true` → `RuleCache.setServer(server)` → `RuleSwitchConfig.syncToSwitch()` → `false`。
`isReloading` 给回调用来跳过"逐条下发客户端"之类不该在回灌时做的动作（`ExchangeModule` 与
`HappyGhastSpeedSectionManager` 都读它）。

**第二道关的校验器是混合域**：`RuleStateValidator.numericRange(min, max)` 除了放行 `[min, max]` 内的有限数字，
还一律放行 `true` / `false`（忽略大小写）—— 这些规则靠"状态是不是 false"判断开关，
只放行数字会让规则**关不掉**。所以它的合法状态域是混合的 `{true, false} ∪ [min, max]`，
javadoc 明确写了这一点：消费方不要直接 `Double.parseDouble(state)`，
统一走 `ParseUtils.toDouble(state, fallback)`（它带兜底）。`HappyGhastSpeed` 用的就是 `numericRange(0.0, 5.0)`。

---

### 4. 两条通知链（并存，不是重复）

| | `RuleCallbacks` | `RuleCache.addListener` |
|---|---|---|
| 结构 | `EnumMap<RuleType, List<Handler>>` —— 每条规则一个 `CopyOnWriteArrayList`，**一条规则可以挂多个** | `CopyOnWriteArrayList<RuleChangeListener>` —— 全局一个列表 |
| 注册方式 | `RuleModule` 实现类在 `RuleModuleRegistry.initAll()` 里登记；`register` 用 `contains` 去重 | 任何类在初始化时 `RuleCache.addListener(...)` |
| 适合 | "这条规则的业务反应"（语言切换、审计日志；同一条规则可以有多个模块） | "多条独立关注点"（审计日志、客户端广播、速度重算…） |
| 触发 | `fireCallbacks` 里先 `RuleCallbacks.fire(...)`，再 `notifyListeners(...)` | 同左 |
| 异常 | 每个 handler 各自 try/catch + ERROR 日志，一个模块失败不会让同规则其它模块失声 | 同左 |

**为什么会并存**：`RuleModule` 体系是"模块化业务回调"（按规则登记业务反应 —— `LanguageModule`
与 `ExchangeModule` 可以同时挂在同一条规则上），而框架需要"多方订阅同一条规则的变化"（同步、审计、描述符广播）。
两者语义不同，硬合并会丢功能。

**"一条规则多个模块"靠两条保证**：`register` 用 `contains` 去重，同一个 handler 实例重复登记会被忽略；
`RuleModuleRegistry.initAll()` 自己还有 `AtomicBoolean` 幂等守卫（两个入口点都会调它）。
`fire` 把每个 handler 包在自己的 try/catch 里，一个模块抛异常只记一条 ERROR，不会影响同一条规则上的其它模块。

`RuleChangeContext` 是一个 `ThreadLocal<CommandSourceStack>`，用来让回调知道"这次是谁改的"
（`ExchangeModule` 的审计日志靠它打 `(by 玩家名 / Console)`）。

---

### 5. 规则描述符

**背景**：配置界面里「服务器 / 服务端配置」两个分类**不能**由客户端自己的枚举驱动 ——
客户端版本旧时，服务端新增的规则在它的 `RuleType` 里根本不存在，界面里干脆没有这一条。

`RuleDescriptorBuilder` 是**唯一**的"规则语义 → 下发数据"转换点：

| 方法 | 作用 |
|---|---|
| `build(ServerPlayer, String language)` | 整份描述符（先过 `lacksRuleCommandPermission`：没权限 → 下发空表） |
| `describe(RuleType, String language)` | 单条：`registerName` / 翻译好的 `name`+`info` / `categories` / `state` / `defaultState` / `enabled` / `switches` / `template` / `data` |
| `buildOptions(String registerName)` | 某条逐条配置规则的 `id → 原版值` 清单（补全 + 预填用），超过 `MAX_OPTION_IDS = 20_000` 就不下发 |

**文字按客户端语言解析**：`ServerTranslations.resolve(Component, language)` ——
这就是"命令回复跟服务端语言、界面跟客户端语言"的实现点。

`RuleDescriptor` 的三个关键取舍：

1. `name` / `info` 是**服务端翻译好的纯文本**，客户端不再拿翻译键查自己的 lang（新规则的键在老客户端里本来就没有）；
2. `enabled` 是**服务端算好的**，客户端不解析状态值语义（"是不是 false"这类判断只存在一处）；
3. `template` 用**模板原语**描述列表长什么样（`plain` / `valueKind` / `idSource`），客户端不写死"哪条规则是哪种列表"。

---

### 6. `RuleDataRegistry`：逐条配置类的读写表

```
static initEntries():  for (RuleType rule : values())
    if (!rule.getConfigKind().needsSync()) continue;
    switch (rule.getReloadableConfig()) {
        case IdSetConfig set        → kind = configName 含 "block" ? BLOCK_LIST : ENTITY_LIST
        case NumberConfigServer num → kind = BLOCK_NUMBER
        default                     → 跳过
    }
```

`Entry` 提供：`kind`、`DataStore`（`serializer` / `deserializer` / `setter` / `remover` / `register`）、
`vanillaValue(id)`（新增条目时的原版值预填 —— 纯名单给 `"true"`，数字类从方块查默认值）。

`verifyConsistency()` 在启动期双向校验：**有 Entry 但规则不是 LIST** / **规则是 LIST 但没有 Entry** 都会抛异常。

`RuleConfigKind.needsSync()` 与 `RuleDataKind` 的分工：

| 枚举 | 回答什么 | 取值 |
|---|---|---|
| `RuleConfigKind` | 这条规则的配置内容**要不要**同步 | `NONE` / `LIST`（`needsSync()` = 非 NONE） |
| `RuleDataKind` | 同步过去的内容**长什么样** | `ENTITY_LIST` / `BLOCK_LIST` / `BLOCK_NUMBER`（`isPlainList()` 区分纯名单与 id→数字） |

---

### 7. 类名核实结果（本篇）

| 断言 | 核实 |
|---|---|
| `RuleType` 字段：`registerName` / `displayName` / `displayInfo` / `category` / `listenerType` / `commandObject` / `reloadableConfig` / `quickSwitches` / `configKind` / `state` / `stateValidator` | ✅ |
| `RuleType` 重载构造：10 参（validator 传 `null`）/ 11 参 | ✅ |
| `RuleCache`：`rules` / `listeners` / `server` / `CONFIG_RULE_TYPES` / `configRuleTypes` / `setServer` / `getServer` / `clearServer` / `RuleChangeListener` / `addListener` / `getState` / `isEnabled`×3 / `hasLevel` / `requirement` / `permissionNode` / `lacksRuleCommandPermission` / `setState` / `syncRules` / `persist` / `fireCallbacks` / `notifyListeners` / `isEnableOfListener` | ✅ |
| `RuleChangeService`：`isReloading` / `change`×2 / `reset` / `reload` | ✅ |
| `RuleSwitches.Canonical`：`Ok` / `BadState` / `BadOption` / `Rejected`；`canonicalize` / `isEnabledState` / `colorOf` / `displayNameOf` / `currentDisplay` | ✅ |
| `RuleStateValidator`：`KEY_NOT_A_NUMBER` / `KEY_OUT_OF_RANGE` / `validate` / `ValidationError.notANumber` / `outOfRange` / `numericRange` | ✅ |
| `RuleCallbacks`：`Handler` / `HANDLERS`（`EnumMap<RuleType, List<Handler>>`）/ `register` / `fire` | ✅ |
| `RuleModuleRegistry`：`MODULES = [LanguageModule, ExchangeModule]` / `initAll` | ✅ |
| `RuleDescriptorBuilder`：`MAX_OPTION_IDS = 20_000` / `build` / `describe` / `buildOptions` | ✅ |
| `RuleDescriptor`：`registerName` / `name` / `info` / `categories` / `state` / `defaultState` / `enabled` / `switches` / `template` / `data` + `Switch` / `ListTemplate` / `ValueKind` / `DataEntry` | ✅ |
| `RuleDataRegistry`：`Entry` / `DataStore` / `setFlag` / `initEntries` / `addFloatEntry` / `verifyConsistency` / `all` / `of` | ✅ |
| `RuleDataKind`：`ENTITY_LIST` / `BLOCK_LIST` / `BLOCK_NUMBER` / `isPlainList` | ✅ |
| `RuleConfigKind.needsSync()` = 非 `NONE` | ✅ |
