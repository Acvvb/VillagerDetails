# Command framework

How the command tree is built from registries: the static `/ec` tree plus the enum-driven `/ec c`, the startup self-check, permission-filtered tab completion and the three base classes.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> Covers: `RuleCommand` · `CCommand` · `RegisterServer` / `RegisterTypeServer` · `AbstractRuleCommand` ·
> `AbstractRegionBindingCommand` · `AbstractBlockResistanceServerImpl` · `PermissionAwareLiteralArgumentBuilder` /
> `PermissionAwareLiteralCommandNode` · `IdSuggestionProvider` · `ReloadMapping` · `CommandConstants`.
> The **data source** of the permission decisions is in [05](05-rule-framework.md#2-rulecache五个方法--两条红线); this page only covers how commands use it.

---

### 1. Command tree: two layers from two different sources

```
/ec                                  ← static (RuleCommand.register)
 ├─ (no args)                        execute → helpRules(): one line of category buttons
 ├─ list           requires global switch
 │   ├─ (no args)                    list every rule
 │   ├─ <category>                   list by category (RuleCategoryType.getTypeByRegisterName, tolerates case / plural)
 │   ├─ enable                       filter by "enabled"
 │   └─ disable                      filter by "disabled"
 ├─ <rule>         requires global switch + suggests all register names
 │   └─ <state>    greedyString + suggests that rule's quickSwitches
 └─ c                                ← enum-driven (CCommand.buildCSubcommand)
     ├─ reload [config]              ← ReloadMapping (OWNERS hard-coded)
     ├─ <RuleType register name> ...  ← lists only rules with getCommandObject() != null
     └─ selection ...                ← ListenerType.BLOCK_RANGE_SELECTION (BBSelectionServerImpl)
```

**Why the two layers of completion are deliberately inconsistent**:

| Layer | Completion source | Why |
|---|---|---|
| `/ec <rule>` | `RuleType.allRegisterNames()` — **all 40** | `/ec <rule> <state>` is valid for every rule; listing only the rules with subcommands would make people think the others have no switch |
| `/ec c <rule>` | only rules with `getCommandObject() != null` | when `register()` is `null` there is simply no subtree to attach (for example `PortalBindingEnhanced`) |

On rules that do have commands the two names are kept identical by a **startup self-check**; the difference is by design, not a missing entry.

---

### 2. Startup self-check (`CCommand.validateSubcommands`)

| Check | Action on failure | Accident it prevents |
|---|---|---|
| The literal is empty | `IllegalStateException` (startup fails) | a command object whose `register()` returned an empty literal |
| **Duplicate literal** | `IllegalStateException` (startup fails) | Brigadier's `CommandNode#addChild` **silently merges** a node with the same name: only the callback is overwritten and the children are merged, while `requirement` is final and keeps the first registration → "some rule command mysteriously disappears / permissions are judged wrong", with **no error at all** |
| Register name ≠ literal | **WARN** only | "same thing, two names": `/ec <register name>` and `/ec c <literal>` are two different names (the historical case: `batchBedBinding` vs `VillagerBatchBedBinding`) |

`validateSubcommands` runs on **every rebuild of the command tree** (server startup, data pack reload, `/reload`), so "the command is gone after a reload" class of problem is caught by the same place.

---

### 3. Permission filtering: why Brigadier's completion had to be replaced

Brigadier's `LiteralCommandNode#listSuggestions` **does not look at the requirement**: as long as the parent node is reachable and the literal name matches, the literal is completed. A player without permission would therefore see `/ec c <rule id>`, but executing it would fail and there would be no completion behind it.

`CCommand#permissionAware(...)` replaces every `LiteralArgumentBuilder` with a version built by `PermissionAwareLiteralArgumentBuilder`:

```
PermissionAwareLiteralArgumentBuilder.literal(literal)
   .requires(source.getRequirement())
   .executes(source.getCommand())          // carried when present
   .forward(...)                            // carried when present
   .then(child)  × every child node         // moved over verbatim
         ↓ build()
PermissionAwareLiteralCommandNode#listSuggestions
   if (!canUse(source)) return Suggestions.empty();
   return super.listSuggestions(context, builder);   // execution is still guarded by the requirement
```

**"Visible in completion" and "executable" share the same `requirement`**, so both sides always agree.

**The division of labour is by design**: `/ec c <rule> ...` is attached with the rule's own `requirement` only, so a rule whose tier is `true` and which is not a list-config rule is **usable by everyone** — that is exactly what the layering means: layer 1 (`RuleCommand#hasBasePermission`) gates "may manage rules", layer 2 (the rule's own `requirement`) gates "may trigger this rule". `CCommand` only attaches the requirement and adds no check of its own; the class javadoc states this division of labour explicitly.

**The boundary of completion filtering is the first level of `/ec c`**: `permissionAware(...)` wraps only the subcommand builders attached **directly** under `/ec c`, and `copy.then(child)` moves the existing child nodes over verbatim — it does not wrap recursively. So the `/ec c <rule>` level is filtered by permission, while deeper levels (for example `/ec c TradeSearch item|book`, `/ec c PortalBinding view <id>`) are not: a player without permission can see the deep subcommand names, although executing them is still stopped by the `requirement`. That is the deliberate boundary — visibility is judged at the "rule id" level, and the usability of deeper subcommands is decided at execution time by the rule's own `requirement`.

---

### 4. Three abstract base classes: subclasses only describe the differences

| Base class | Command shape | What the subclass provides | Where the business lives |
|---|---|---|---|
| `AbstractRuleCommand` | `/ec c <rule> <id> true\|false`, `reset`, `show` | `literalName` / `argName` / `idSuggester` / `add` / `remove` / `resetConfig` / `snapshot` / 8 language keys | config classes (`IdSetConfig` subclasses) |
| `AbstractRegionBindingCommand` | `bind` / `show` / `view <id>` / `delete <id>` + (optional) `set` / `clear` + subclass-specific | `literalName` / `kNotPlayer` / `availableIds` / `bind` / `show` / `view` / `delete` | the handler of each rule |
| `AbstractBlockResistanceServerImpl` | `/ec c <rule> <block id> <resistance>`, `reset`, `show` | `commandName` / 5 language keys / `valueSuggester` / `put` / the rest of the storage | config classes (`NumberConfigServer` subclasses) |

Shared conventions:

- **The command layer hard-codes `OWNERS`** (`AbstractRuleCommand.OWNER_REQUIREMENT`, `AbstractBlockResistanceServerImpl.OWNER_REQUIREMENT`, `AbstractRuleCommand`'s `reset`/`show`); the reason is that all three command families **edit server configuration directly**. (`AbstractRegionBindingCommand` does not hard-code it: it relies on the `type.requirement()` that `CCommand` attaches.)
- **The return value of `then` must be captured** (`root = addSetCommand(root);` / `root = addExtraSubcommands(root);`): Brigadier currently mutates in place and returns `this`, but that is an implementation detail; once a wrapper layer (such as `permissionAware`) is involved, "ignoring the return value" means dropping the subcommand that was just attached.
- **The "not a player" null check and message are handled once by the base class / a helper** (`playerOrNull` + `kNotPlayer`), so business methods only take a `ServerPlayer`.
- **IDs are strings**: the command layer never parses numbers — "this ID does not exist" is handled by the handler as "not found" plus a message. `IdSuggestionProvider` holds a `Supplier<List<String>>` and re-reads it on every Tab, so after deleting `#2` it is immediately gone from completion.

**The region-binding base class carries no permission predicate of its own**: `AbstractRegionBindingCommand`'s `bind` / `show` / `view` / `delete` only do the "is this a player" null check, and admission is decided entirely by the `RuleType#requirement()` that `CCommand` attaches on the parent node — that is the single source of truth for "is the rule on / is the tier high enough". The subtree therefore **must** be attached through `CCommand`; if it is registered on its own, the caller has to add a permission predicate itself. The class javadoc says so.

---

### 5. `RuleCommand` execution details

The three outcomes of `toggleRule(ctx, state)`:

| Input | Behaviour |
|---|---|
| Register name not found | sends an "unknown rule" message, returns `0` |
| `state == null` | sends `RuleMessageComponents.ruleDetail(rule, RuleCache.getState(rule))`, returns `1` |
| a `state` is given | goes through `RuleChangeService.change`, branching on `Canonical`: `BadState` → invalid state; `BadOption` → invalid option; `Rejected` → the rule's own validation message; `Ok` → success message |

**A compile trap worth knowing**: inside that `switch` the `Rejected` branch must come before `default` (which is the cast `Ok`), otherwise it is a `ClassCastException`. The source carries a comment about it; do not reorder that `switch`.

`hasBasePermission(src)`:

```
PermissionLevel level = SwitchComponentType.permissionLevelOf(RuleCache.getState(SETTING_EC_COMMAND_PERMISSION));
return src.checkPermission(PERM_BASE, level != null ? level : PermissionLevel.OWNERS);
```

It hangs off the `/ec` root execution, `/ec list ...` and `/ec <rule>` — **not off `/ec c`** (that one uses the rule's own `requirement`).

---

### 6. `/ec c reload`

`ReloadMapping.register()`:

```
Commands.literal("reload")
  .requires(src -> src.checkPermission(PERM_BASE, OWNERS))     // hard-coded, ignores the RuleCommand rule
  .executes(reloadMappings(ctx, null))                          // → ConfigRegistry.reloadAllFromDisk
  .then(argument("config", word()).suggests(ConfigRegistry.getReloadableNames())
        .executes(reloadMappings(ctx, name)))                   // → reloadSpecificFromDisk
```

An invalid name → message + return `0`; a reload that throws → caught and `e.getMessage()` appended to the message (never bubbles into the tick). The detailed timing is in [03](03-config-lifecycle.md#7-ec-c-reload-的完整时序).

---

### 7. Class-name verification (this page)

| Claim | Verified |
|---|---|
| `RuleCommand`: `INSTANCE` / `commandHookRegistered` / `RULE_SUGGESTER` / `CATEGORY_SUGGESTER` / `STATE_SUGGESTER` / `registerHook` / `register` / `hasBasePermission` / `listRules` / `listRulesByCategory` / `listRulesByStatus` / `sendRuleList` / `toggleRule` / `helpRules` | ✅ |
| `CCommand`: `Subcommand` record / `buildCSubcommand` / `collectSubcommands` / `validateSubcommands` / `permissionAware` | ✅ |
| `RegisterServer`: constant `BLOCK_BLOCK_SECTION = "selection"` + `register()` | ✅ |
| `RegisterTypeServer`: `getCommandObject` + the default `requirement()` (returns `null`) | ✅ |
| `AbstractRuleCommand`: `OWNER_REQUIREMENT` / `VALUE_ARG = "value"` / `literalName` / `argName` / `idSuggester` / `valueSuggester` / `add` / `remove` / `resetConfig` / `snapshot` / 8 language keys / `set` / `reset` / `show` | ✅ |
| `AbstractRegionBindingCommand`: `literalName` / `kNotPlayer` / `availableIds` / `set` / `bind` / `show` / `view` / `delete` / `clear` / `addSetCommand` / `addClearCommand` / `idSuggestions` / `addExtraSubcommands` | ✅ |
| `AbstractBlockResistanceServerImpl`: `OWNER_REQUIREMENT` / `commandName` / `setKey` / `resetKey` / `showHeaderKey` / `showEmptyKey` / `invalidValueKey` / `valueSuggester` / `put` | ✅ |
| `PermissionAwareLiteralCommandNode#listSuggestions` overrides `canUse` | ✅ |
| `ReloadMapping`: `INSTANCE` / `CONFIG_SUGGESTER` / `register` / `reloadMappings` | ✅ |
| `CommandConstants`: `COMMAND_BASE = "/ec"` / `PERM_BASE = villagerdetails:c.base` / `RULE_ERROR_INVALID_STATE` / `RULE_ERROR_INVALID_OPTION` | ✅ |

---

<a id="中文"></a>

## 中文

> 覆盖：`RuleCommand` · `CCommand` · `RegisterServer` / `RegisterTypeServer` · `AbstractRuleCommand` ·
> `AbstractRegionBindingCommand` · `AbstractBlockResistanceServerImpl` · `PermissionAwareLiteralArgumentBuilder` /
> `PermissionAwareLiteralCommandNode` · `IdSuggestionProvider` · `ReloadMapping` · `CommandConstants`。
> 权限判定的**数据源**在 [05](05-rule-framework.md#2-rulecache五个方法--两条红线)，本文只讲命令怎么用它。

---

### 1. 命令树：两层，来源不同

```
/ec                                  ← 静态写死（RuleCommand.register）
 ├─ (无参)                            execute → helpRules()：发一行分类按钮
 ├─ list           requires 总闸
 │   ├─ (无参)                       列出全部规则
 │   ├─ <category>                   按分类列（RuleCategoryType.getTypeByRegisterName，容错大小写 / 复数）
 │   ├─ enable                      按"已启用"筛选
 │   └─ disable                     按"已禁用"筛选
 ├─ <rule>         requires 总闸 + suggests 全部注册名
 │   └─ <state>    greedyString + suggests 该规则的 quickSwitches
 └─ c                                ← 枚举驱动（CCommand.buildCSubcommand）
     ├─ reload [config]              ← ReloadMapping（写死 OWNERS）
     ├─ <RuleType 注册名> ...         ← 只列 getCommandObject() != null 的规则
     └─ selection ...                ← ListenerType.BLOCK_RANGE_SELECTION（BBSelectionServerImpl）
```

**两层补全为什么故意不一致**：

| 层 | 补全来源 | 为什么 |
|---|---|---|
| `/ec <rule>` | `RuleType.allRegisterNames()` —— **全部 40 条** | `/ec <规则> <状态>` 对每条规则都成立；只列"有子命令的"会让人以为别的规则没开关 |
| `/ec c <rule>` | 只有 `getCommandObject() != null` 的规则 | `register()` 为 `null` 时根本没有可挂的子树（例如 `PortalBindingEnhanced`） |

两个名字在"有命令"的规则上由**启动期自检**保证一致；差集是设计如此，不是漏项。

---

### 2. 启动期自检（`CCommand.validateSubcommands`）

| 检查 | 失败动作 | 防的事故 |
|---|---|---|
| 字面量为空 | `IllegalStateException`（启动失败） | 命令对象 `register()` 返回了空字面量 |
| **字面量重复** | `IllegalStateException`（启动失败） | Brigadier 的 `CommandNode#addChild` 遇到同名节点会**静默合并**：只覆盖回调、合并子节点，而 `requirement` 是 final、保留先注册那份 → "某条规则命令莫名消失 / 权限判错"，且**没有任何报错** |
| 注册名 ≠ 字面量 | 只打 **WARN** | "同物异名"：`/ec <注册名>` 和 `/ec c <字面量>` 是两个名字（历史事故：`batchBedBinding` vs `VillagerBatchBedBinding`） |

`validateSubcommands` 在**每次命令树重建**时都跑（服务器启动、数据包重载、`/reload`），
所以"重载后命令消失"这类问题也会被同一处抓到。

---

### 3. 权限过滤：为什么要改 Brigadier 的补全

Brigadier 的 `LiteralCommandNode#listSuggestions` **不看 requirement**：只要父节点走得到、
字面量名字对得上，它就会被补全出来。于是没权限的玩家能看到 `/ec c <规则 id>`，
但敲进去执行不了、后面也没有补全。

`CCommand#permissionAware(...)` 把每个 `LiteralArgumentBuilder` 换成
`PermissionAwareLiteralArgumentBuilder` 构建的版本：

```
PermissionAwareLiteralArgumentBuilder.literal(literal)
   .requires(source.getRequirement())
   .executes(source.getCommand())          // 有则带
   .forward(...)                            // 有则带
   .then(child)  × 每个子节点               // 原样搬运
         ↓ build()
PermissionAwareLiteralCommandNode#listSuggestions
   if (!canUse(source)) return Suggestions.empty();
   return super.listSuggestions(context, builder);   // 执行仍由 requirement 把关
```

**"补全可见"与"可执行"共用同一个 `requirement`**，两边结论一致。

**权限分工是设计如此**：`/ec c <规则> ...` 只挂规则自己的 `requirement`，
所以"档位是 `true` 且不是逐条配置类"的规则**人人可用** —— 这正是分层的本意：
第一层（`RuleCommand#hasBasePermission`）管"能不能管理规则"，
第二层（规则自己的 `requirement`）管"能不能触发这条规则"。
`CCommand` 只负责把 `requirement` 挂上去，自己不做额外校验；类注释里把这条分工写成了"设计如此"。

**补全权限的边界是 `/ec c` 的第一层**：`permissionAware(...)` 只包装**直接挂到 `/ec c` 下**的
子命令构建器，`copy.then(child)` 把原有子节点原样搬过去 —— 不递归包装。
所以 `/ec c <规则>` 这一层按权限过滤，更下一层（例如 `/ec c TradeSearch item|book`、
`/ec c PortalBinding view <id>`）不过滤：无权限玩家能看到深层子命令名，执行仍会被 `requirement` 拦住。
这是刻意的边界 —— 可见性只在"规则 id"这一层判定，深层子命令的可用性由规则自己的 `requirement` 在执行时把关。

---

### 4. 三个抽象基类：子类只描述差异

| 基类 | 命令形态 | 子类要提供 | 业务在哪 |
|---|---|---|---|
| `AbstractRuleCommand` | `/ec c <规则> <id> true\|false`、`reset`、`show` | `literalName` / `argName` / `idSuggester` / `add` / `remove` / `resetConfig` / `snapshot` / 8 个语言键 | 配置类（`IdSetConfig` 子类） |
| `AbstractRegionBindingCommand` | `bind` / `show` / `view <id>` / `delete <id>` +（可选）`set` / `clear` + 子类自定义 | `literalName` / `kNotPlayer` / `availableIds` / `bind` / `show` / `view` / `delete` | 各规则的 Handler |
| `AbstractBlockResistanceServerImpl` | `/ec c <规则> <方块id> <抗性>`、`reset`、`show` | `commandName` / 5 个语言键 / `valueSuggester` / `put` / 其余存取 | 配置类（`NumberConfigServer` 子类） |

共同约定：

- **命令层权限写死 `OWNERS`**（`AbstractRuleCommand.OWNER_REQUIREMENT`、`AbstractBlockResistanceServerImpl.OWNER_REQUIREMENT`、
  `AbstractRuleCommand` 的 `reset`/`show`）；原因是这三类命令都在**直接改服务端配置**。
  （`AbstractRegionBindingCommand` 不写死，它靠 `CCommand` 挂上的 `type.requirement()`。）
- **`then` 的返回值必须接住**（`root = addSetCommand(root);` / `root = addExtraSubcommands(root);`）：
  Brigadier 目前原地修改并返回 `this`，但这是实现细节；换了包装层（比如 `permissionAware`）时
  "忽略返回值"就等于把刚挂上的子命令丢掉。
- **"不是玩家"的判空与提示统一由基类 / helper 处理**（`playerOrNull` + `kNotPlayer`），业务方法只收 `ServerPlayer`。
- **ID 是字符串**：命令层不解析数字，"找不到这条 ID"由处理器按"查不到"处理并给提示。
  `IdSuggestionProvider` 持有 `Supplier<List<String>>`，每次 Tab 都重新取 → 删掉 `#2` 后补全里立刻没有它。

**区域绑定基类不带自己的权限谓词**：`AbstractRegionBindingCommand` 的 `bind` / `show` / `view` / `delete`
只做"是不是玩家"的判空，准入完全由 `CCommand` 在父节点上挂的 `RuleType#requirement()` 决定 ——
"规则开没开 / 档位够不够"只有那一处事实来源。所以这棵子树**必须**经 `CCommand` 挂进 `/ec c`；
若要单独注册它，调用方需自己加权限谓词。这条写在类的 javadoc 里。

---

### 5. `RuleCommand` 的执行细节

`toggleRule(ctx, state)` 的三种返回：

| 输入 | 行为 |
|---|---|
| 规则名找不到 | 发"未知规则"提示，返回 `0` |
| `state == null` | 发 `RuleMessageComponents.ruleDetail(rule, RuleCache.getState(rule))`，返回 `1` |
| 有 `state` | 走 `RuleChangeService.change`，按 `Canonical` 分支：`BadState` → 非法状态；`BadOption` → 非法选项；`Rejected` → 规则自己的校验文案；`Ok` → 成功消息 |

**一个容易踩的编译陷阱**：`switch` 里 `Rejected` 分支必须写在 `default`（`Ok` 强转）之前，
否则会 `ClassCastException`。源码里有这条注释，改这个 switch 时别动顺序。

`hasBasePermission(src)`：

```
PermissionLevel level = SwitchComponentType.permissionLevelOf(RuleCache.getState(SETTING_EC_COMMAND_PERMISSION));
return src.checkPermission(PERM_BASE, level != null ? level : PermissionLevel.OWNERS);
```

它挂在 `/ec` 根的执行、`/ec list ...`、`/ec <规则>` 三处 —— **不挂 `/ec c`**（那是规则自己的 `requirement`）。

---

### 6. `/ec c reload`

`ReloadMapping.register()`：

```
Commands.literal("reload")
  .requires(src -> src.checkPermission(PERM_BASE, OWNERS))     // 写死，不看 RuleCommand 规则
  .executes(reloadMappings(ctx, null))                          // → ConfigRegistry.reloadAllFromDisk
  .then(argument("config", word()).suggests(ConfigRegistry.getReloadableNames())
        .executes(reloadMappings(ctx, name)))                   // → reloadSpecificFromDisk
```

名字不合法 → 提示 + 返回 `0`；重载抛异常 → 捕获后把 `e.getMessage()` 拼进提示（不冒泡到 tick）。
详细时序见 [03](03-config-lifecycle.md#7-ec-c-reload-的完整时序)。

---

### 7. 类名核实结果（本篇）

| 断言 | 核实 |
|---|---|
| `RuleCommand`：`INSTANCE` / `commandHookRegistered` / `RULE_SUGGESTER` / `CATEGORY_SUGGESTER` / `STATE_SUGGESTER` / `registerHook` / `register` / `hasBasePermission` / `listRules` / `listRulesByCategory` / `listRulesByStatus` / `sendRuleList` / `toggleRule` / `helpRules` | ✅ |
| `CCommand`：`Subcommand` record / `buildCSubcommand` / `collectSubcommands` / `validateSubcommands` / `permissionAware` | ✅ |
| `RegisterServer`：常量 `BLOCK_BLOCK_SECTION = "selection"` + `register()` | ✅ |
| `RegisterTypeServer`：`getCommandObject` + 默认 `requirement()`（返回 `null`） | ✅ |
| `AbstractRuleCommand`：`OWNER_REQUIREMENT` / `VALUE_ARG = "value"` / `literalName` / `argName` / `idSuggester` / `valueSuggester` / `add` / `remove` / `resetConfig` / `snapshot` / 8 个语言键 / `set` / `reset` / `show` | ✅ |
| `AbstractRegionBindingCommand`：`literalName` / `kNotPlayer` / `availableIds` / `set` / `bind` / `show` / `view` / `delete` / `clear` / `addSetCommand` / `addClearCommand` / `idSuggestions` / `addExtraSubcommands` | ✅ |
| `AbstractBlockResistanceServerImpl`：`OWNER_REQUIREMENT` / `commandName` / `setKey` / `resetKey` / `showHeaderKey` / `showEmptyKey` / `invalidValueKey` / `valueSuggester` / `put` | ✅ |
| `PermissionAwareLiteralCommandNode#listSuggestions` 覆盖 `canUse` | ✅ |
| `ReloadMapping`：`INSTANCE` / `CONFIG_SUGGESTER` / `register` / `reloadMappings` | ✅ |
| `CommandConstants`：`COMMAND_BASE = "/ec"` / `PERM_BASE = villagerdetails:c.base` / `RULE_ERROR_INVALID_STATE` / `RULE_ERROR_INVALID_OPTION` | ✅ |
