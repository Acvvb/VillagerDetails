# VillagerDetails

A mod that fixes the infuriating villager AI behavior that makes players rage.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

VillagerDetails is a Fabric mod that provides a large collection of behavior fixes and enhancements for villagers, entities, and blocks. Everything can be toggled via the `/ec` command system or the built-in config GUI (default key: `J`).

### Features

#### Villagers

| Rule                       | Description                                                                                         |
|----------------------------|-----------------------------------------------------------------------------------------------------|
| VillagerBedBinding         | Villager bed editor — rebind a villager's bed with a lead named `bed`                               |
| VillagerWorkBlockBinding   | Villager work block editor — rebind the work block with a lead named `work`                         |
| VillagerBatchBedBinding    | Batch bed binding — auto-bind villagers in an area to the nearest bed                               |
| VillagerInfiniteTrade      | Infinite trades — villager trades never run out                                                     |
| VillagerAutoTrader         | Auto trade refresh — write a target enchantment on a name tag, right-click a librarian to reroll    |
| VillagerSilentTrader       | Silent trade reroll — remove the name tag after rerolling                                           |
| VillagerAutoLockHitTrader  | Auto-lock trades — lock rerolled enchanted books automatically                                      |
| VillagerGodTools           | God tools — use `331a` / `331b` name tags to reroll triple-enchanted diamond tools                  |
| VillagerTerracottaTrader   | Terracotta trader — name tag `Terracotta` rerolls both terracotta and glazed terracotta             |
| VillagerHardWorking        | Hard-working villagers — adjust restock amount per trade                                            |
| VillagerMoveController     | Move controller — set a destination with a lead named `move`                                        |
| VillagerToolsmithExchange  | Diamond exchange reversal — toolsmith trades emeralds for diamonds                                  |
| VillagerBookshelfDecompose | Auto bookshelf decomposition — bookshelves auto-decompose into books when used as payment           |
| VillagerEmeraldDecompose   | Auto emerald block decomposition — emerald blocks auto-decompose into emeralds when used as payment |
| VillagerOneTickTraderCount | Adjust trades refreshed per tick                                                                    |
| VillagerBoneMealFlower     | Bone meal overworld flowers — spawn overworld flowers regardless of biome                           |
| ZombieVillagerHelp         | Instant zombie villager cure — name tag `help` cures instantly                                      |

#### Entities

| Rule                              | Description                                                          |
|-----------------------------------|----------------------------------------------------------------------|
| NoSqueeze                         | No squeeze — specified entities are immune to entity cramming damage |
| PiglinBarterDelay                 | Piglin barter delay adjustment                                       |
| PiglinGoldBlockMultiply           | Gold block barter — piglins accept gold blocks, 9x output            |
| PiglinGoldBlockMultiplyPunishment | Gold block barter punishment timer adjustment                        |

#### Mobs

| Rule            | Description                                                                  |
|-----------------|------------------------------------------------------------------------------|
| NoBrainOnPortal | Portal brain loss — mobs lose their AI after passing through a nether portal |

#### Blocks

| Rule                     | Description                                                            |
|--------------------------|------------------------------------------------------------------------|
| PortalBinding            | Portal binding — force-bind overworld and nether selections            |
| PortalBindingEnhanced    | Enhanced portal binding — skip the "portal exists at target" check     |
| BlockCollectable         | Configurable block collection                                          |
| BlockMiningResistance    | Configurable block mining resistance                                   |
| BlockExplosionResistance | Configurable block explosion resistance                                |
| TntNoDestroyDrops        | TNT does not destroy drops                                             |
| BoxCrafting              | Box crafting — craft with shulker boxes as if they were their contents |

#### Animals

| Rule          | Description                                                    |
|---------------|----------------------------------------------------------------|
| InfiniteShear | Infinite shearing — sheep regrow wool instantly after shearing |

### Commands

Everything is done through the `/ec` command:

```
/ec                              # Show help and categories
/ec list                         # List all rules
/ec list <category>              # List rules by category
/ec list enable / disable        # Filter by state
/ec <rule>                       # Show rule details
/ec <rule> <state>               # Toggle a rule
/ec c <subcommand>               # Management subcommands (selections, bindings, etc.)
```

### Client Features

- **Config GUI** — press `J` (rebindable) to open the in-game config screen with toggles, render options, and key bindings
- **Villager-bed line rendering** — draw a line between a villager and its bound bed; color warns when too far
- **EB / BB selection highlight** — visualize entity-box and block-box selections
- **Portal region highlight** — visualize both sides of a bound portal pair

### Supported Minecraft Versions

| Version | Fabric Loader | Fabric API          |
|---------|---------------|---------------------|
| 26.2    | ≥0.19.3       | ≥0.160.0+26.2       |
| 26.3    | ≥0.19.3       | ≥0.162.0+26.3       |

### Requirements

- **Java**: 25+ (GraalVM JDK 25 recommended)
- **Fabric Loader**: ≥0.19.3
- **Fabric API**: matching version

### Building

```bash
# Build every version and collect the packages into lib/
./gradlew build            # same as ./gradlew collectJars

# Build only one version
./gradlew :v26.2:build
./gradlew :v26.3:build
```

Each version produces two packages, collected into `lib/<mod version>/`:

| Folder       | Source sets   | `environment` | Use                                              |
|--------------|---------------|---------------|--------------------------------------------------|
| `universal/` | main + client | `*`           | client (also runs on a server)                    |
| `server/`    | main only     | `*`           | dedicated server (no client classes, smaller jar) |

### Project Structure

```
├── common/          # Cross-version shared sources (not compiled standalone; pulled in via srcDir)
├── v26.2/           # Minecraft 26.2 specific code and config
├── v26.3/           # Minecraft 26.3 specific code and config
├── tools/           # Helper scripts
└── lib/             # Packages collected by `collectJars` (universal / server)
```

### License

[MIT](LICENSE)

### Author

Acvvb

### Links

- [GitHub Repository](https://github.com/Acvvb/VillagerDetails)

---

<a id="中文"></a>

## 中文

修复那些让玩家血压拉满的村民 AI 行为的模组。

### 概览

VillagerDetails 是一个 Fabric 模组，提供了大量村民、实体、方块相关的行为修复与增强功能。通过 `/ec` 命令系统或内置的配置 GUI（默认快捷键 `J`），你可以灵活开启/关闭各项规则。

### 功能列表

#### 村民

| 规则名                     | 说明                                                      |
|----------------------------|-----------------------------------------------------------|
| VillagerBedBinding         | 村民床编辑器 — 使用命名为 `bed` 的拴绳修改村民绑定的床    |
| VillagerWorkBlockBinding   | 村民工作方块编辑器 — 使用命名为 `work` 的拴绳修改工作方块 |
| VillagerBatchBedBinding    | 批量村民床编辑器 — 区域内村民自动绑定最近的床             |
| VillagerInfiniteTrade      | 无限交易 — 村民交易永不耗尽                               |
| VillagerAutoTrader         | 自动刷新交易 — 命名牌写目标附魔，右键图书管理员自动刷出   |
| VillagerSilentTrader       | 无感交易刷新 — 刷新后自动移除命名标签                     |
| VillagerAutoLockHitTrader  | 自动锁定交易 — 刷新出的附魔书自动锁定                     |
| VillagerGodTools           | 神装工具刷新 — 使用 331a/331b 命名牌刷三附魔钻石工具      |
| VillagerTerracottaTrader   | 陶瓦刷新 — 用陶瓦名称命名牌同时刷出陶瓦和带釉陶瓦         |
| VillagerHardWorking        | 勤劳的村民 — 调整村民每次补货数量                         |
| VillagerMoveController     | 村民移动控制器 — 使用命名为 `move` 的拴绳设置目的地       |
| VillagerToolsmithExchange  | 钻石兑换反转 — 工具匠改为绿宝石换钻石                     |
| VillagerBookshelfDecompose | 书架自动拆解 — 用书架支付时自动拆成书                     |
| VillagerEmeraldDecompose   | 绿宝石块自动拆解 — 用绿宝石块支付时自动拆成绿宝石         |
| VillagerOneTickTraderCount | 每 tick 刷新交易次数调整                                  |
| VillagerBoneMealFlower     | 骨粉催熟主世界花 — 无视生物群系生成主世界花               |
| ZombieVillagerHelp         | 僵尸村民秒治愈 — 贴 `help` 命名牌立刻变回村民             |

#### 实体

| 规则名                            | 说明                                      |
|-----------------------------------|-------------------------------------------|
| NoSqueeze                         | 无挤压 — 指定实体不参与挤压、不受挤压伤害 |
| PiglinBarterDelay                 | 猪灵交易等待时间调整                      |
| PiglinGoldBlockMultiply           | 金块交易 — 猪灵愿捡金块，9 倍产出         |
| PiglinGoldBlockMultiplyPunishment | 金块交易惩罚时间调整                      |

#### 怪物

| 规则名          | 说明                                   |
|-----------------|----------------------------------------|
| NoBrainOnPortal | 过门失智 — 怪物穿过下界传送门后失去 AI |

#### 方块

| 规则名                   | 说明                                        |
|--------------------------|---------------------------------------------|
| PortalBinding            | 传送门绑定 — 主世界与地狱选区强制绑定       |
| PortalBindingEnhanced    | 地狱门增强绑定 — 不检测目标位置是否有传送门 |
| BlockCollectable         | 方块可采集配置                              |
| BlockMiningResistance    | 方块挖掘抗性配置                            |
| BlockExplosionResistance | 方块爆炸抗性配置                            |
| TntNoDestroyDrops        | TNT 不炸毁掉落物                            |
| BoxCrafting              | 盒子合成 — 潜影盒当作内容物合成             |

#### 动物

| 规则名        | 说明                          |
|---------------|-------------------------------|
| InfiniteShear | 无限剪羊毛 — 羊剪毛后立即恢复 |

### 命令

所有操作通过 `/ec` 命令完成：

```
/ec                              # 查看帮助与分类
/ec list                         # 列出全部规则
/ec list <category>              # 按分类列出
/ec list enable / disable        # 按状态筛选
/ec <rule>                       # 查看规则详情
/ec <rule> <state>               # 切换规则状态
/ec c <subcommand>               # 管理类子命令（选区、绑定等）
```

### 客户端功能

- **配置 GUI**：按 `J`（可自定义）打开模组配置界面，支持功能开关、渲染选项、按键绑定等
- **村民-床连线渲染**：在村民与其绑定的床之间绘制连线，距离过远时变色告警
- **EB/BB 选区高亮**：可视化实体-方块选择器与方块-方块选择器的选中区域
- **传送门区域高亮**：绑定传送门的两侧区域可视化

### 支持的 Minecraft 版本

| 版本 | Fabric Loader | Fabric API       |
|------|---------------|------------------|
| 26.2 | ≥0.19.3       | ≥0.160.0+26.2    |
| 26.3 | ≥0.19.3       | ≥0.162.0+26.3    |

### 环境要求

- **Java**: 25+（GraalVM JDK 25 推荐）
- **Fabric Loader**: ≥0.19.3
- **Fabric API**: 对应版本

### 构建

```bash
# 构建所有版本并把产物收集到 lib/
./gradlew build            # 等价于 ./gradlew collectJars

# 仅构建指定版本
./gradlew :v26.2:build
./gradlew :v26.3:build
```

每个版本产出两个包，收集到 `lib/<模组版本>/`：

| 目录         | 内容          | `environment` | 用途                                 |
|--------------|---------------|---------------|--------------------------------------|
| `universal/` | main + client | `*`           | 客户端（放到服务端也能跑）           |
| `server/`    | 仅 main       | `*`           | 专用服务端（不含客户端类，体积更小） |

### 项目结构

```
├── common/          # 跨版本共享源码（不单独编译，由各版本子项目 srcDir 引入）
├── v26.2/           # Minecraft 26.2 专属代码与配置
├── v26.3/           # Minecraft 26.3 专属代码与配置
├── tools/           # 辅助脚本
└── lib/             # collectJars 收集的成品包（universal / server）
```

### 许可证

[MIT](LICENSE)

### 作者

Acvvb

### 链接

- [GitHub Releases](https://github.com/Acvvb/VillagerDetails/releases)