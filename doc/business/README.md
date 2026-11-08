# VillagerDetails — Business Documentation

> For developers taking over this project: **what each rule does and roughly how it is implemented**.
> How the framework layer is written (thread pools, config pipeline, session cache, the internals of the command
> and network frameworks) lives in `doc/framework/`; this directory only gives a one-line conclusion plus a link.
>
> **The one-line boundary**: "why does the player see A instead of B" → here; "why is the code written this way
> instead of that way" → [framework docs](../framework/README.md).

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

### How to read

| If you are… | Suggested route |
|---|---|
| New to this project | [`00-overview.md`](00-overview.md) → [`01-rule-index.md`](01-rule-index.md) → pick a rule that interests you in `02-rules/` |
| Handling a bug report or a feature request | Search [`01-rule-index.md`](01-rule-index.md) for a keyword → `02-rules/<category>.md` → `03-deep-dives/` for complex rules |
| Changing the framework / adding a rule | Read [`00-overview.md`](00-overview.md) sections "What one rule is" and "Design rules and boundaries", then the [framework docs](../framework/README.md) |
| Debugging something in production | [`00-overview.md`](00-overview.md) §9 Design rules and boundaries, plus the boundary notes at the end of each chapter |

### Contents

| File | Contents |
|---|---|
| [`00-overview.md`](00-overview.md) | Positioning, where the code lives, the four pillars of the rule system (categories / state values / two permission layers / four config files), the timing of a rule change, the three wiring styles, the async boundary, the client's share, design rules and boundaries |
| [`01-rule-index.md`](01-rule-index.md) | **Index of all 40 rules** (register name / categories / default / one-liner) + category distribution check + enum constant cross-reference |
| [`04-glossary.md`](04-glossary.md) | Business glossary (framework terms are placeholders pointing at `doc/framework/`) |
| [`02-rules/settings.md`](02-rules/settings.md) | `Language`, `RuleCommand` |
| [`02-rules/fix.md`](02-rules/fix.md) | `GlobalSpeedBypass`, `HappyGhastSpeed` |
| [`02-rules/entity.md`](02-rules/entity.md) | `NoSqueeze`, `MobNoSpawn`, `PortalPiglinNoSpawn`, `ZombieVillagerHelp`, the three piglin rules |
| [`02-rules/monster.md`](02-rules/monster.md) | `NoBrainOnPortal` |
| [`02-rules/plant.md`](02-rules/plant.md) | `VillagerBoneMealFlower` |
| [`02-rules/animal.md`](02-rules/animal.md) | `InfiniteShear` |
| [`02-rules/player.md`](02-rules/player.md) | `PlayerHeadSitting`, `PlayerBlockSitting` |
| [`02-rules/villager.md`](02-rules/villager.md) | The 14 villager rules (binding tools / batch bed binding / trade refresh / trade search and payment) |
| [`02-rules/block.md`](02-rules/block.md) | The 10 block rules (four list-driven rules / TNT / box crafting / both portal-binding rules / end gravity / dirty furnace) |
| [`03-deep-dives/auto-trader.md`](03-deep-dives/auto-trader.md) | Auto trade refresh: trigger → parse → producibility → frame slicing → write back |
| [`03-deep-dives/trade-search.md`](03-deep-dives/trade-search.md) | Trade search: the write side / the read side, the five gates, flush and clear ordering |
| [`03-deep-dives/portal-binding.md`](03-deep-dives/portal-binding.md) | Portal binding: the region model, the scan budget, multi-hit arbitration, enhanced mode |
| [`03-deep-dives/tab-completion.md`](03-deep-dives/tab-completion.md) | Where completion data comes from, why it is async, why it does not implement `SessionState` |
| [`03-deep-dives/villager-binding.md`](03-deep-dives/villager-binding.md) | Name-tag tools + dual selection + POI / brain rewriting + batch matching |
| [`03-deep-dives/furnace-dirty.md`](03-deep-dives/furnace-dirty.md) | Dirty furnace: the global dirty table, per-view-distance deltas, the prune order |
| [`03-deep-dives/player-sitting.md`](03-deep-dives/player-sitting.md) | Player sitting: cleanup responsibility for `noSave` vehicles, re-sending the passenger list, swallowed false alarms |

**19 files in total**; `02-rules/` covers **40/40** of `RuleType.values()`.

### Division of labour with the framework docs

Framework docs entry point: [`doc/framework/README.md`](../framework/README.md)

| Topic | This directory | [`doc/framework/`](../framework/README.md) |
|---|---|---|
| "What is `InfiniteShear` for / roughly how is it implemented" | ✅ | ❌ |
| "`RuleType` has 10 constructor parameters / `RuleCache`'s permission chain" | Conclusion only | ✅ |
| "Which vanilla method a mixin injects into" | ✅ (one line) | ✅ (inventory: [`08-mixin-inventory.md`](../framework/08-mixin-inventory.md)) |
| "The five gates of `VillagerTradeCache`" | Conclusion + ordering | ✅ (gate-by-gate implementation) |
| "Framework terms: snapshot pool / query pool / epoch / tombstone" | Only the conclusion, in [`04-glossary.md`](04-glossary.md) | ✅ (definitions and mechanics) |

---

<a id="中文"></a>

## 中文

### 怎么读

| 你是…… | 建议路线 |
|---|---|
| 第一次接触这个项目 | [`00-overview.md`](00-overview.md) → [`01-rule-index.md`](01-rule-index.md) → 挑一条感兴趣的规则进 `02-rules/` |
| 接到一个 bug / 需求 | [`01-rule-index.md`](01-rule-index.md) 搜关键词 → `02-rules/<分类>.md` → 复杂规则再进 `03-deep-dives/` |
| 要改框架 / 加新规则 | 先读 [`00-overview.md`](00-overview.md) 的「一条规则是什么」与「设计约定与边界」，再看[框架文档](../framework/README.md) |
| 要排查线上问题 | [`00-overview.md`](00-overview.md) §9 设计约定与边界，以及每篇末尾的边界说明 |

### 目录

| 文件 | 内容 |
|---|---|
| [`00-overview.md`](00-overview.md) | 项目定位、代码在哪、规则系统的四根支柱（分类 / 状态值 / 两层权限 / 四份配置）、改规则的时序、三种接线方式、异步边界、客户端分工、设计约定与边界 |
| [`01-rule-index.md`](01-rule-index.md) | **40 条规则总索引**（注册名 / 分类 / 默认值 / 一句话）+ 分类分布核对 + 枚举常量对照 |
| [`04-glossary.md`](04-glossary.md) | 业务术语表（框架术语为占位，指向 `doc/framework/`） |
| [`02-rules/settings.md`](02-rules/settings.md) | `Language`、`RuleCommand` |
| [`02-rules/fix.md`](02-rules/fix.md) | `GlobalSpeedBypass`、`HappyGhastSpeed` |
| [`02-rules/entity.md`](02-rules/entity.md) | `NoSqueeze`、`MobNoSpawn`、`PortalPiglinNoSpawn`、`ZombieVillagerHelp`、猪灵 3 条 |
| [`02-rules/monster.md`](02-rules/monster.md) | `NoBrainOnPortal` |
| [`02-rules/plant.md`](02-rules/plant.md) | `VillagerBoneMealFlower` |
| [`02-rules/animal.md`](02-rules/animal.md) | `InfiniteShear` |
| [`02-rules/player.md`](02-rules/player.md) | `PlayerHeadSitting`、`PlayerBlockSitting` |
| [`02-rules/villager.md`](02-rules/villager.md) | 村民 14 条（绑定工具 / 批量绑床 / 交易刷新 / 交易查询与支付） |
| [`02-rules/block.md`](02-rules/block.md) | 方块 10 条（四条名单类 / TNT / 盒子合成 / 传送门绑定两条 / 末地重力 / 脏熔炉） |
| [`03-deep-dives/auto-trader.md`](03-deep-dives/auto-trader.md) | 自动刷新交易：触发 → 解析 → 可产性 → 分帧 → 写回 |
| [`03-deep-dives/trade-search.md`](03-deep-dives/trade-search.md) | 交易查询：写入侧 / 读取侧、五道闸、落盘与清空时序 |
| [`03-deep-dives/portal-binding.md`](03-deep-dives/portal-binding.md) | 传送门绑定：区域模型、扫描预算、多命中仲裁、增强模式 |
| [`03-deep-dives/tab-completion.md`](03-deep-dives/tab-completion.md) | 补全数据从哪来、为什么异步、为什么不实现 `SessionState` |
| [`03-deep-dives/villager-binding.md`](03-deep-dives/villager-binding.md) | 命名牌工具 + 双选区 + POI / brain 改写 + 批量匹配 |
| [`03-deep-dives/furnace-dirty.md`](03-deep-dives/furnace-dirty.md) | 脏熔炉：全局脏表 + 按视距增量 + prune 顺序 |
| [`03-deep-dives/player-sitting.md`](03-deep-dives/player-sitting.md) | 玩家乘坐：`noSave` 载具的清理责任、乘客表补发、误报吞掉 |

**合计 19 个文件**；`02-rules/` 覆盖 `RuleType.values()` 全部 **40/40** 条。

### 与框架文档的分工

框架文档入口：[`doc/framework/README.md`](../framework/README.md)

| 内容 | 本目录 | [`doc/framework/`](../framework/README.md) |
|---|---|---|
| 「`InfiniteShear` 是干什么的 / 大致怎么实现」 | ✅ | ❌ |
| 「`RuleType` 有 10 个构造参数 / `RuleCache` 的权限判定链」 | 只给结论 | ✅ |
| 「Mixin 注入了哪个方法」 | ✅（一句） | ✅（清单 [`08-mixin-inventory.md`](../framework/08-mixin-inventory.md)） |
| 「`VillagerTradeCache` 的 5 道闸」 | 给结论 + 时序 | ✅（逐闸实现） |
| 「框架术语：快照池 / 查询池 / 纪元 / 墓碑」 | 只在 [`04-glossary.md`](04-glossary.md) 登记结论 | ✅（定义与机制） |
