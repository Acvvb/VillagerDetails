# VillagerDetails — Framework Documentation

> **Audience**: senior developers who modify the framework, add new rules, or debug threading and deadlock issues.
> Scope: how the framework layer is implemented, why it is implemented that way, and which conventions it enforces.
>
> Division of labour with the business docs (`doc/business/`):
> "why does the player see A instead of B" → business docs; "why is the code written this way instead of that way" → here.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

### How to read

| If you are… | Suggested route |
|---|---|
| New to the framework layer | [`00-overview.md`](00-overview.md) (startup order + three main flows) → 01 → 02 → 03 → 04 |
| Adding a new rule | [`05-rule-framework.md`](05-rule-framework.md) → (needs a command) [`06`](06-command-framework.md) → (needs sync) [`07`](07-network-framework.md) |
| Adding an event / a receiver / a session cache | [`04-session-state.md`](04-session-state.md) (the scanning base) → the chapter for that role interface |
| Debugging "the config was not written" | [`02-config-pipeline.md`](02-config-pipeline.md) — data flow and failure semantics |
| Debugging "shutdown hangs / data was lost" | [`01-threading.md`](01-threading.md) — pool contracts and shutdown ordering |
| Debugging "the mixin is applied but nothing happens" | [`08-mixin-inventory.md`](08-mixin-inventory.md) — inventory and risk levels |
| Upgrading Minecraft | [`09-version-adapter.md`](09-version-adapter.md) — per-class differences and the upgrade order |

### Contents

| File | Contents |
|---|---|
| [`00-overview.md`](00-overview.md) | Layer diagram, startup order, five cross-cutting concerns, entry points of the three main flows, compile-time constants |
| [`01-threading.md`](01-threading.md) | The four execution lines, pool capacity and rejection policy, shutdown ordering, frame slicing, performance orders of magnitude, cross-thread conventions |
| [`02-config-pipeline.md`](02-config-pipeline.md) | The seven-stage data flow, merge and debounce, failure semantics, atomic writes, batching |
| [`03-config-lifecycle.md`](03-config-lifecycle.md) | The four config files plus section configs, observer queue and throttling, the two reload paths, reset, `ConfigRegistry` |
| [`04-session-state.md`](04-session-state.md) | The `SessionState` contract, the three scanning layers, the three moments, examples of what to implement and what not to, pollution cases |
| [`05-rule-framework.md`](05-rule-framework.md) | `RuleType`'s 10 parameters, the five `RuleCache` methods, the permission chain, the two validation gates, the two notification chains, descriptors |
| [`06-command-framework.md`](06-command-framework.md) | The static `/ec` tree plus the enum-driven `/ec c`, startup self-checks, permission filtering for completion, the three base classes |
| [`07-network-framework.md`](07-network-framework.md) | Payload type → channel → receiver → main-thread dispatch, muting, handshake, the client peer |
| [`08-mixin-inventory.md`](08-mixin-inventory.md) | 40+ injection points grouped by target class, accessors / invokers listed separately, risk levels |
| [`09-version-adapter.md`](09-version-adapter.md) | 26.2↔26.3 per-class differences, what belongs in the version layer, the upgrade order |
| [`10-glossary.md`](10-glossary.md) | **The definition of record for framework terms** (the business docs only link here) |

### Related documentation

- Business docs: [`../business/README.md`](../business/README.md) — what each rule does and how players interact with it.
- Project overview and installation: [`../../README.md`](../../README.md).

---

<a id="中文"></a>

## 中文

### 怎么读

| 你是…… | 建议路线 |
|---|---|
| 第一次进框架层 | [`00-overview.md`](00-overview.md)（初始化顺序 + 三条主线）→ 01 → 02 → 03 → 04 |
| 要加一条新规则 | [`05-rule-framework.md`](05-rule-framework.md) →（要命令）[`06`](06-command-framework.md) →（要同步）[`07`](07-network-framework.md) |
| 要加一个事件 / 收包 / 会话缓存 | [`04-session-state.md`](04-session-state.md)（扫包底座）→ 对应角色接口那篇 |
| 排查「配置没写下去」 | [`02-config-pipeline.md`](02-config-pipeline.md) —— 七段数据流与失败语义 |
| 排查「停服卡 / 丢数据」 | [`01-threading.md`](01-threading.md) —— 池契约与停服时序 |
| 排查「注入了但没效果」 | [`08-mixin-inventory.md`](08-mixin-inventory.md) —— 清单与风险分级 |
| 升 Minecraft 版本 | [`09-version-adapter.md`](09-version-adapter.md) —— 逐类差异与升级顺序 |

### 目录

| 文件 | 内容 |
|---|---|
| [`00-overview.md`](00-overview.md) | 分层图、开服初始化顺序、五个横切关注点、三条主线入口、编译期常量 |
| [`01-threading.md`](01-threading.md) | 四条执行线、池容量与拒绝策略、停服时序、分帧、性能量级、跨线程约定 |
| [`02-config-pipeline.md`](02-config-pipeline.md) | 七段数据流、合并去抖、失败语义、原子写、batch |
| [`03-config-lifecycle.md`](03-config-lifecycle.md) | 四份配置 + section 配置、观察者队列与节流、reload 两条路、reset、`ConfigRegistry` |
| [`04-session-state.md`](04-session-state.md) | `SessionState` 契约、扫包三层、三个时机、该 / 不该实现的例子、污染案例 |
| [`05-rule-framework.md`](05-rule-framework.md) | `RuleType` 10 个参数、`RuleCache` 五个方法、权限链、两道校验、两条通知链、描述符 |
| [`06-command-framework.md`](06-command-framework.md) | `/ec` 静态树 + `/ec c` 枚举驱动、启动自检、补全权限过滤、三个基类 |
| [`07-network-framework.md`](07-network-framework.md) | 包类型 → 通道 → 收包 → 主线程派发、静音、握手、客户端对端 |
| [`08-mixin-inventory.md`](08-mixin-inventory.md) | 40+ 注入点按目标类分组、accessor / invoker 单列、风险分级 |
| [`09-version-adapter.md`](09-version-adapter.md) | 26.2↔26.3 逐类差异、该不该下沉、升级顺序 |
| [`10-glossary.md`](10-glossary.md) | **框架术语定义方**（业务文档只链接这里） |

### 相关文档

- 业务文档：[`../business/README.md`](../business/README.md) —— 每条规则干什么、玩家怎么用。
- 项目总览与安装说明：[`../../README.md`](../../README.md)。
