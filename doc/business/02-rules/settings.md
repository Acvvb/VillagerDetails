# Settings (MOD_SETTING)

Two mod-level settings rather than gameplay rules: which language the server speaks, and who is allowed to run `/ec`.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> This file covers the **2** rules of the `MOD_SETTING` category in `RuleType`.
> Strictly speaking these two are not "gameplay rules" but **the mod's own settings**: one decides which language the
> server speaks, the other decides who is allowed to run `/ec`. Neither of them has an `/ec c` subcommand; they can only
> be changed with `/ec <rule> <state>`.

---

#### `Language` (server language)

- **Register name**: `Language` (enum constant `SETTING_LANGUAGE`)
- **Category**: `MOD_SETTING`
- **Default value**: `zh_cn`
- **Allowed values**: a **live view** of the `Language.preload` array in `config.json`, default `["en_us", "zh_cn"]`
- **Commands**:
  - `/ec Language` — shows the current language plus buttons for the available languages
  - `/ec Language <language code>` — switches, e.g. `/ec Language en_us`
  - no `/ec c Language` subcommand
- **Config class**: `ServerLangConfig` (**not a separate file**; it is the `Language` section inside `villagerdetails/config.json`)
  - `preload`: array, the candidate languages (also the source of this rule's allowed values)
  - `default`: string, the fallback language (only used while "the rule value has not been applied yet")
- **Trigger**:
  1. **rule state change** → the callback registered by `LanguageModule` in `RuleCallbacks` (design intent)
  2. **server start** → `SERVER_STARTED` registered by `ConfigRegistry` → `ConfigRegistry.reloadAll` →
     `RuleConfig.reload` → `RuleConfig.reloadChildren` → `ServerLangConfig.reload`
  3. **`/ec c reload`** → `ConfigRegistry.reloadAllFromDisk` → `RuleConfig.reloadFromDisk` →
     `onDiskLoad` → the same `reloadChildren` path
  4. the translation tables themselves are loaded by `ServerTranslations.preloadAll` following the `preload` list
- **How it works**:
  1. The server **keeps its own translation table** (`ServerTranslations`) and never touches vanilla
     `Language.instance` — vanilla's is there for client-side rendering; the server cannot reach it and must not.
  2. **When the rule changes**: the callback hands the new value to `ServerTranslations.switchTo`. Switching the
     language only swaps one `volatile` reference for another immutable table; every later `resolve(...)` uses the new
     table and messages already sent do not change. So changing the rule **switches the server language immediately**,
     with no restart and no `/ec c reload` involved.
  3. **On start / reload**: `ServerLangConfig.reload()` first clears the cache, reloads every language file listed in
     `preload`, then switches once **with the rule value as the source of truth**. It deliberately does not rely on the
     rule callback alone: when the rule value has not changed, `RuleCache.setState` short-circuits (the callback does not
     fire), so if `reload()` had switched the language to `default` on the side, we would end up with "the rule says
     `en_us` while the server actually speaks `zh_cn`", and setting the same value again would be short-circuited too —
     the language could never be switched back.
  4. **Side effects**: every `/ec` reply, the category names in `/ec list`, the display names and descriptions of rules,
     and all prompt messages switch to the new language. The language of the player's own client UI has nothing to do
     with this (see "Client behaviour" below).
- **Key files**:
  - `common/src/server/java/com/villagerdetails/rule/callback/impl/LanguageModule.java` — the rule callback
  - `common/src/server/java/com/villagerdetails/config/impl/section/item/ServerLangConfig.java` — config section + reload
  - `common/src/server/java/com/villagerdetails/lang/ServerTranslations.java` — the server translation table itself
  - `common/src/server/java/com/villagerdetails/rule/type/RuleType.java` — the allowed values are `ServerLangConfig::preloadLanguageView`
- **Dependencies**:
  - **Rules**: none (it does not depend on any other rule)
  - **Config**: `ServerLangConfig` (the `Language` section of `config.json`); `ServerTranslations.preloadAll` reads its `preload`
  - **Events**: both the rule callback (`RuleCallbacks`) and the start-up replay registered in `ConfigRegistry` (`SERVER_STARTED`)
  - **Consumed by**: `RuleType#getDisplayName/getDisplayInfo`, `RuleMessageComponents`, `SendMessengerUtils` —
    every piece of server-side text in the mod
- **Client behaviour**: the settings screen turns every entry of `preload` into a button on the "language" row; clicking
  one **sends a rule-change request** (it does not switch the language locally). The text of the screen itself uses the
  **client UI language**, translated and pushed down by the server according to the language the client reports.
- **Design rules and boundaries**:
  1. This rule is a **custom list**: the candidate values are language codes, none of which is `false/true/ops/...`, and it
     carries no `stateValidator` — so `/ec Language <any text>` also returns success.
  2. **The consequence of a wrong language code**: `switchTo` tries to load that language on demand; when the jar has no
     `lang/<language code>.json`, you get an **empty translation table**, so every `translatable` degrades to its raw key
     (for example it shows `setting.infinite_shear` directly). Setting a valid language code and reloading recovers; saves
     are not damaged.
  3. The `default` in the config is **not** "the current language"; it is only the fallback used while the rule value has
     not been applied yet.

---

#### `RuleCommand` (`/ec` global permission)

- **Register name**: `RuleCommand` (enum constant `SETTING_EC_COMMAND_PERMISSION`)
- **Category**: `MOD_SETTING`
- **Default value**: `owner`
- **Allowed values**: `true` / `ops` / `master` / `admin` / `owner`
  (**there is no `false`**: this rule cannot be turned off, it only decides "how low the bar is")
- **Commands**:
  - `/ec RuleCommand` — shows the current tier and the allowed values
  - `/ec RuleCommand <true|ops|master|admin|owner>` — changes the bar
  - no `/ec c RuleCommand` subcommand
- **Config class**: none (the value lives in `villager_details_binding.json`)
- **Trigger**: the `requires` decision of the three command families `/ec` (root), `/ec list ...` and
  `/ec <rule> [state]`; the admission decision when the client UI pulls "rule descriptors", submits a
  "rule change packet" or a "config edit" request.
  **Note that `/ec c <rule> ...` does not pass this gate** (see item 4 under "Design rules and boundaries" below).
- **How it works**:
  1. state string → permission level: `true → ALL`, `ops → MODERATORS`, `master → GAMEMASTERS`,
     `admin → ADMINS`, `owner → OWNERS`; an unrecognised value **falls back to `owner`**.
  2. the decision point is the permission node `villagerdetails:c.base` (`CommandConstants.PERM_BASE`) plus that level:
     `src.checkPermission(PERM_BASE, level)`. Sources without an op level, such as command blocks / functions, count as
     `ALL`, so they work as usual when the tier is `true` and stop working at `ops` and above.
  3. **the two consumers must behave identically**:
     - command side: `RuleCommand#hasBasePermission` — attached to the execution of the `/ec` root, `/ec list` and `/ec <rule>`;
     - network side: `RuleCache#lacksRuleCommandPermission` — checked before sending descriptors, before receiving
       rule-change packets and before receiving config-edit packets.
     Both read the same state and the same node, so "may look" and "may change" always agree.
  4. it is the **first layer** of permission. The second layer is "each rule's own tier" (see
     [`../00-overview.md`](../00-overview.md) §4.3): having access to `/ec` is not enough — the rule you want to touch
     must also be on and its tier high enough.
- **Key files**:
  - `common/src/server/java/com/villagerdetails/command/RuleCommand.java` — command tree + `hasBasePermission`
  - `common/src/server/java/com/villagerdetails/cache/RuleCache.java` — `lacksRuleCommandPermission`, `requirement`
  - `common/src/server/java/com/villagerdetails/command/CommandConstants.java` — `PERM_BASE`
  - `common/src/server/java/com/villagerdetails/rule/type/RuleType.java` — allowed values are `SwitchComponentType.SETTING_PERMISSIONS`
- **Dependencies**:
  - **Rules**: none (it does not depend on any rule, but the commands of **every** rule depend on it)
  - **Config**: none
  - **Consumed by**: `RuleCommand#hasBasePermission`, `RuleCache#lacksRuleCommandPermission`,
    `RuleDescriptorBuilder` (admission before descriptors are sent) and the three receivers (`RuleDataReceiver`,
    `ServerRuleSetReceiver`, `RuleDataOptionsRequestReceiver`)
- **Client behaviour**: decides whether the client can "open the settings screen / see the rule list / submit a change".
  The server **checks again** when the packet arrives, so bypassing the screen and sending packets directly is pointless.
- **Design rules and boundaries**:
  1. setting it to `true` = **any player can see and change every rule** (including turning protective rules off); do not
     do this on a production server;
  2. `/ec c reload` does **not** look at this rule; it hard-codes `PERM_BASE` + `OWNERS`;
  3. it only governs the `/ec` command tree and does not affect vanilla or other mods' commands;
  4. **`/ec c <rule> ...` does not pass this gate**: `CCommand` attaches subcommands with only "the rule's own
     `requirement`". So for a rule with tier = `true` that is not a list-config rule (e.g. `PortalBinding` set to
     `true`), ordinary players can execute its `/ec c` subcommand too. To tighten it, set such rules to `ops` or above.

---

### Cross references

- Overview of the two-layer permission model: [`../00-overview.md`](../00-overview.md) §4.3
- The three forms a state value can take: [`../00-overview.md`](../00-overview.md) §4.2
- Terms: `tier`, `permission node`, `server language`, `client UI language` → [`../04-glossary.md`](../04-glossary.md)

---

<a id="中文"></a>

## 中文

> 本文件覆盖 `RuleType` 里 `MOD_SETTING` 分类的 **2 条**规则。
> 这两条严格来说不是"玩法规则"，而是**模组自身的设置**：一条决定服务端用什么语言说话，
> 一条决定谁有资格敲 `/ec`。它们都没有 `/ec c` 子命令，只能用 `/ec <规则> <状态>` 改。

---

#### `Language`（服务端语言）

- **注册名**：`Language`（枚举常量 `SETTING_LANGUAGE`）
- **分类**：`MOD_SETTING`
- **默认值**：`zh_cn`
- **可选值**：`config.json` 里 `Language.preload` 数组的**实时视图**，默认 `["en_us", "zh_cn"]`
- **命令**：
  - `/ec Language` — 显示当前语言 + 可选语言的按钮
  - `/ec Language <语言码>` — 切换，例如 `/ec Language en_us`
  - 无 `/ec c Language` 子命令
- **配置类**：`ServerLangConfig`（**不是独立文件**，是 `villagerdetails/config.json` 里的 `Language` 节）
  - `preload`：数组，候选语言（也是这条规则的可选值来源）
  - `default`：字符串，兜底语言（只在"规则值还没被应用"时用得上）
- **触发**：
  1. **规则状态变化** → `RuleCallbacks` 里 `LanguageModule` 注册的回调（设计意图）
  2. **服务器启动** → `ConfigRegistry` 注册的 `SERVER_STARTED` → `ConfigRegistry.reloadAll` →
     `RuleConfig.reload` → `RuleConfig.reloadChildren` → `ServerLangConfig.reload`
  3. **`/ec c reload`** → `ConfigRegistry.reloadAllFromDisk` → `RuleConfig.reloadFromDisk` →
     `onDiskLoad` → 同一条 `reloadChildren` 路径
  4. 翻译表本身的加载由 `ServerTranslations.preloadAll` 按 `preload` 列表完成
- **实现思路**：
  1. 服务端**自己维护一份翻译表**（`ServerTranslations`），完全不碰原版 `Language.instance` ——
     原版那套是给客户端渲染用的，服务端拿不到、也不该动。
  2. **改规则时**：回调把新值交给 `ServerTranslations.switchTo`。切语言只是把一个
     `volatile` 引用换成另一张不可变表，之后的每一次 `resolve(...)` 都用新表；已经发出去的消息不会变。
     所以运行中改这条规则**立刻**换语言，不需要重启，也不需要 `/ec c reload`。
  3. **启动 / 重载时**：`ServerLangConfig.reload()` 先清缓存、按 `preload` 重新加载所有语言文件，
     再**以规则值为准**切一次。这里刻意不只靠规则回调 —— 规则值没变时 `RuleCache.setState` 会短路
     （回调不触发），如果 `reload()` 顺手把语言切到了 `default`，就会出现「规则写着 `en_us`、
     实际却是 `zh_cn`」，而且用户再设一次同样的值还是被短路，**语言从此切不回来**。
  4. **副作用**：所有 `/ec` 回复、`/ec list` 的分类名、规则的展示名与描述、以及各类提示语，
     全部换成新语言。玩家自己客户端的界面语言与此**无关**（见下面「客户端行为」）。
- **关键文件**：
  - `common/src/server/java/com/villagerdetails/rule/callback/impl/LanguageModule.java` — 规则回调
  - `common/src/server/java/com/villagerdetails/config/impl/section/item/ServerLangConfig.java` — 配置节 + 重载
  - `common/src/server/java/com/villagerdetails/lang/ServerTranslations.java` — 服务端翻译表本体
  - `common/src/server/java/com/villagerdetails/rule/type/RuleType.java` — 可选值是 `ServerLangConfig::preloadLanguageView`
- **依赖**：
  - **规则**：无（它不依赖任何其它规则）
  - **配置**：`ServerLangConfig`（`config.json` 的 `Language` 节）；`ServerTranslations.preloadAll` 读它的 `preload`
  - **事件**：既走规则回调（`RuleCallbacks`），也走开服回灌（`ConfigRegistry` 注册的 `SERVER_STARTED`）
  - **被谁消费**：`RuleType#getDisplayName/getDisplayInfo`、`RuleMessageComponents`、`SendMessengerUtils` ——
    全模组的服务端文案
- **客户端行为**：设置界面把 `preload` 的每一项做成"语言"这一行的按钮，点一下就是**发一条改规则请求**
  （不是本地切语言）；界面自身的文字用**客户端界面语言**，由服务端按客户端报上来的语言翻译后下发。
- **设计约定与边界**：
  1. 这条规则是"自定义列表"：候选值是语言码，里面没有一个是 `false/true/ops/...`，它也没有 `stateValidator`，
     于是 `/ec Language 任意文本` 同样返回成功。
  2. **写错语言码的后果**：`switchTo` 会尝试按需加载该语言，jar 里没有对应 `lang/<语言码>.json`
     时会得到一张**空翻译表**，于是所有 `translatable` 都退化成 key 原文（例如直接显示
     `setting.infinite_shear`）。改成合法语言码 + 重载即可恢复，不会损坏存档。
  3. 配置里的 `default` **不是**"当前语言"，它只是规则还没被应用时的兜底。

---

#### `RuleCommand`（`/ec` 全局权限）

- **注册名**：`RuleCommand`（枚举常量 `SETTING_EC_COMMAND_PERMISSION`）
- **分类**：`MOD_SETTING`
- **默认值**：`owner`
- **可选值**：`true` / `ops` / `master` / `admin` / `owner`
  （**没有 `false`**：这条规则不能关，只能决定"门槛有多低"）
- **命令**：
  - `/ec RuleCommand` — 显示当前档位与可选值
  - `/ec RuleCommand <true|ops|master|admin|owner>` — 改门槛
  - 无 `/ec c RuleCommand` 子命令
- **配置类**：无（值就存在 `villager_details_binding.json` 里）
- **触发**：`/ec`（根）、`/ec list ...`、`/ec <规则> [状态]` 这三类命令的 `requires` 判定；
  客户端界面拉"规则描述符"、提交"改规则包"、"改配置项"请求时的准入判定。
  **注意 `/ec c <规则> ...` 不经过这道闸**（见下面「设计约定与边界」第 4 条）。
- **实现思路**：
  1. 状态字符串 → 权限等级：`true → ALL`、`ops → MODERATORS`、`master → GAMEMASTERS`、
     `admin → ADMINS`、`owner → OWNERS`；认不出来的值**退回 `owner`**。
  2. 判定点是权限节点 `villagerdetails:c.base`（`CommandConstants.PERM_BASE`）+ 上面那个等级：
     `src.checkPermission(PERM_BASE, level)`。命令方块 / 函数这类没有 op 等级的来源按 `ALL` 算，
     所以档位是 `true` 时它们照常能用，`ops` 及以上就用不了。
  3. **两个消费点必须行为一致**：
     - 命令侧：`RuleCommand#hasBasePermission` —— 挂在 `/ec` 根的执行、`/ec list`、`/ec <规则>` 上；
     - 网络侧：`RuleCache#lacksRuleCommandPermission` —— 下发描述符、收改规则包、收配置编辑包前都判它。
     两处读的是同一个状态、同一个节点，所以"能不能看"和"能不能改"永远一致。
  4. 它是**第一层**权限。第二层是"每条规则自己的档位"（见 [`../00-overview.md`](../00-overview.md) §4.3）：
     能用 `/ec` 之后，还要看你要操作的那条规则自己开没开、档位够不够。
- **关键文件**：
  - `common/src/server/java/com/villagerdetails/command/RuleCommand.java` — 命令树 + `hasBasePermission`
  - `common/src/server/java/com/villagerdetails/cache/RuleCache.java` — `lacksRuleCommandPermission`、`requirement`
  - `common/src/server/java/com/villagerdetails/command/CommandConstants.java` — `PERM_BASE`
  - `common/src/server/java/com/villagerdetails/rule/type/RuleType.java` — 可选值 `SwitchComponentType.SETTING_PERMISSIONS`
- **依赖**：
  - **规则**：无（它不依赖任何规则，但**所有**规则的命令都依赖它）
  - **配置**：无
  - **被谁消费**：`RuleCommand#hasBasePermission`、`RuleCache#lacksRuleCommandPermission`、
    `RuleDescriptorBuilder`（下发描述符前的准入）、三个收包端（`RuleDataReceiver`、`ServerRuleSetReceiver`、
    `RuleDataOptionsRequestReceiver`）
- **客户端行为**：决定客户端能不能"打开设置界面 / 看到规则列表 / 提交修改"。
  服务端会在收包时**再判一次**，所以客户端绕过界面直接发包没有意义。
- **设计约定与边界**：
  1. 设成 `true` = **任何玩家都能看和改所有规则**（包括关闭保护性规则），生产服不要这么用；
  2. `/ec c reload` **不看**这条规则，它写死 `PERM_BASE` + `OWNERS`；
  3. 它只管 `/ec` 这一棵命令树，不影响原版或其它模组的命令；
  4. **`/ec c <规则> ...` 不过这道闸**：`CCommand` 挂子命令时只加"规则自己的 `requirement`"。
     所以一条「档位 = `true`、且不是逐条配置类」的规则（例如 `PortalBinding` 设成 `true`），
     普通玩家也能执行它的 `/ec c` 子命令。想收紧就把这类规则的档位设成 `ops` 及以上。

---

### 交叉引用

- 权限的两层模型总览：[`../00-overview.md`](../00-overview.md) §4.3
- 状态的三种取值形态：[`../00-overview.md`](../00-overview.md) §4.2
- 术语：`档位`、`权限节点`、`服务端语言`、`客户端界面语言` → [`../04-glossary.md`](../04-glossary.md)
