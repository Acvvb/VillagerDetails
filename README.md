
# VillagerDetails

A mod that fixes the infuriating villager AI behavior that makes players rage.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

VillagerDetails is a Fabric mod that provides a large collection of behavior fixes and enhancements for villagers, entities, and blocks. Everything can be toggled via the `/ec` command system or the built-in config GUI (default key: `J`).

### Features

#### Settings & Fixes

| Rule | Description |
|------|-------------|
| Language | Mod language setting — controls server-side translations for UI and messages |
| RuleCommand | Global `/ec` permissions — manages permissions for all `/ec` commands (except `/ec c`) |
| GlobalSpeedBypass | Global speed bypass — bypasses server speed checks to prevent rubberbanding when moving too fast (self or riding vehicles) |
| HappyGhastSpeed | Happy Ghast flight speed — adjusts the flying speed when riding a Happy Ghast |

#### Villagers

| Rule | Description |
|------|-------------|
| VillagerBedBinding | Villager bed editor — rebind a villager's bed with a lead named `bed` |
| VillagerWorkBlockBinding | Villager work block editor — rebind the work block with a lead named `work` |
| VillagerBatchBedBinding | Batch bed binding — auto-bind villagers in an area to the nearest bed |
| VillagerInfiniteTrade | Infinite trades — villager trades never run out |
| VillagerAutoTrader | Auto trade refresh — write a target enchantment on a name tag, right-click a librarian to reroll |
| VillagerSilentTrader | Silent trade reroll — remove the name tag after rerolling |
| VillagerAutoLockHitTrader | Auto-lock trades — lock rerolled enchanted books automatically |
| VillagerGodTools | God tools — use `331a` / `331b` name tags to reroll triple-enchanted diamond tools |
| VillagerTerracottaTrader | Terracotta trader — name tag `Terracotta` rerolls both terracotta and glazed terracotta |
| VillagerHardWorking | Hard-working villagers — adjust restock amount per trade |
| VillagerMoveController | Move controller — set a destination with a lead named `move` |
| VillagerToolsmithExchange | Diamond exchange reversal — toolsmith trades emeralds for diamonds |
| VillagerBookshelfDecompose | Auto bookshelf decomposition — bookshelves auto-decompose into books when used as payment |
| VillagerEmeraldDecompose | Auto emerald block decomposition — emerald blocks auto-decompose into emeralds when used as payment |
| VillagerOneTickTraderCount | Adjust trades refreshed per tick |

#### Entities & Mobs

| Rule | Description |
|------|-------------|
| NoSqueeze | No squeeze — specified entities are immune to entity cramming damage |
| MobNoSpawn | No mob spawning — listed entity types never spawn (natural spawn, spawners, eggs, `/summon`, breeding) |
| PortalPiglinNoSpawn | No zombified piglins at bound portals — pick a nether portal region with a `tool` leash and bind it, then those portals stop spawning zombified piglins |
| PiglinBarterDelay | Piglin barter delay adjustment |
| PiglinGoldBlockMultiply | Gold block barter — piglins accept gold blocks, 9x output |
| PiglinGoldBlockMultiplyPunishment | Gold block barter punishment timer adjustment |
| NoBrainOnPortal | Portal brain loss — mobs lose their AI after passing through a nether portal |
| ZombieVillagerHelp | Instant zombie villager cure — name tag `help` cures instantly |

#### Plants & Animals

| Rule | Description |
|------|-------------|
| VillagerBoneMealFlower | Bone meal overworld flowers — spawn overworld flowers regardless of biome |
| InfiniteShear | Infinite shearing — sheep regrow wool instantly after shearing |

#### Players

| Rule | Description |
|------|-------------|
| PlayerHeadSitting | Player head sitting — empty-hand right-click another player to sit on their head; the rider stands on top of the target's bounding box (the two boxes touch, never overlap); sneak to dismount; only works on targets who enabled "Allow others to sit on my head" in their own client config; riders are dismounted when the target sneaks, sleeps, changes dimension or mounts another vehicle; stacking depth is configurable |
| PlayerBlockSitting | Sit on stairs and slabs — empty-hand right-click a **bottom slab / bottom stairs** block to sit on it (top slabs, double slabs and upside-down stairs are refused), Shift to stand up; the seat is the top of the block's flat part, so stairs and slabs sit at the same height, and stairs get nudged 2 pixels towards the low side so your back does not touch the upper step; per-player switch "Allow sitting on stairs and slabs" in the client config, which defaults to **on** and also applies to players without this mod; seats disappear when the block is broken, the rule is off or you disconnect |

#### Blocks

| Rule | Description |
|------|-------------|
| PortalBinding | Portal binding — force-bind overworld and nether selections; every binding gets a stable `#id` (`show` lists it, all commands address it by that id) |
| PortalBindingEnhanced | Enhanced portal binding **switch** (it has no subcommand of its own) — when on, `PortalBinding` skips the "a portal must already exist at target" check; toggle it with `/ec PortalBindingEnhanced <state>` |
| BlockCollectable | Configurable block collection |
| BlockMiningResistance | Configurable block mining resistance |
| BlockExplosionResistance | Configurable block explosion resistance |
| BlockStrongPower | Block strong power — configured blocks act as redstone conductors to transmit signals |
| TntNoDestroyDrops | TNT does not destroy drops |
| BoxCrafting | Box crafting — craft with shulker boxes as if they were their contents |
| EndPortalGravityBlock | End portal gravity block — configured blocks fall like sand if hanging in the 5x5x5 area of an end portal |
| FurnaceDirtyHighlight | Furnace dirty highlight — highlights furnaces whose input slot holds an item nobody can smelt in that furnace type (the fuel slot is not checked); data is synced per chunk and only kept for furnaces inside your view distance |

### Commands

Everything is done through the `/ec` command:

```
/ec                              # Show help and categories
/ec list                         # List all rules
/ec list <category>              # List rules by category
/ec list enable / disable        # Filter by state
/ec <rule>                       # Show rule details
/ec <rule> <state>               # Toggle a rule
/ec c <subcommand>               # Management subcommands (selections, bindings, config reload)
```

#### Region-binding commands

Bindings are addressed by a **stable numeric ID** (`#1`, `#2`, …), not by their position in the
list. IDs are assigned once and never reused (deleting `#2` leaves a hole), so a chat button always
points at the same binding no matter who added or removed entries in the meantime. `show` prints
every entry as `#<id>`, and every `<id>` argument has Tab completion.

```
/ec c PortalBinding set            # cache the current overworld / nether selection
/ec c PortalBinding bind           # bind both cached sides into one entry
/ec c PortalBinding show           # list entries (#id) with [Refresh] / [Delete] buttons
/ec c PortalBinding view <id>      # highlight both sides of that binding
/ec c PortalBinding delete <id>    # clear its cached portal positions, then delete it
/ec c PortalBinding refresh <id>   # re-scan both sides and rewrite the portal cache

/ec c PortalPiglinNoSpawn bind     # bind the current selection
/ec c PortalPiglinNoSpawn show     # list entries (#id)
/ec c PortalPiglinNoSpawn view <id>
/ec c PortalPiglinNoSpawn delete <id>
/ec c PortalPiglinNoSpawn clear    # wipe every region
```

`bind` only reports success when a new entry was actually written: duplicates, identical dimensions
and invalid selections report "not saved", and a half-set selection tells you to run `set` on both
sides first. Buttons rendered by an older build (`delete <id> <version>`) are rejected — run `show`
again and use the new button.

#### Other management subcommands

```
/ec c reload                       # re-read every config file from disk (OP only)
/ec c reload <name>                # same whole-file reload; <name> is only validated
/ec c VillagerBatchBedBinding      # batch-bind villagers in the current selection
/ec c selection pos1|pos2|show|clear   # BB selection tool
/ec c MobNoSpawn <entity> true|false   # per-entity list (also NoSqueeze)
/ec c BlockMiningResistance <block> <value>   # per-block values (also BlockCollectable,
                                        BlockExplosionResistance, BlockStrongPower,
                                        EndPortalGravityBlock)
```

The old literal `/ec c batchBedBinding` was renamed to `/ec c VillagerBatchBedBinding` to match the
rule name — the old spelling no longer parses.

`/ec c reload` reloads **whole files** — every section of `villagerdetails/config.json`,
`villager_details_binding.json` and `trade_index.json` — so hand edits take effect without a
restart. The freshly read content is applied in place and is *not* immediately written back over
your edits. `/ec c reload <TAB>` lists all known names: the three parent configs plus every section
name.

Additionally, `/op <player> <0-4>` overrides a player's permission level: level 4 may change anyone (including other level-4 players), while lower levels may only change players below them and only assign levels below their own; level 0 removes op. `/op <player>` itself still behaves like vanilla.

### Client Features

- **Config GUI** — press `J` (rebindable) to open the in-game config screen with toggles, render options, and key bindings
- **Villager-bed line rendering** — draw a line between a villager and its bound bed; color warns when too far
- **EB / BB selection highlight** — visualize entity-box and block-box selections
- **Portal region highlight** — visualize both sides of a bound portal pair
- **Furnace dirty highlight** — the server only syncs furnaces inside your view range (chunk ADD/REMOVE/CLEAR), so the client never accumulates data for the whole server; no distance filtering is done client-side
- **Binding IDs in chat** — portal / piglin entries show `#id`, so you can view or delete directly from the list without counting rows

### Supported Minecraft Versions

| Version | Fabric Loader | Fabric API          |
|---------|---------------|---------------------|
| 26.2    | ≥0.19.3       | ≥0.160.0+26.2       |
| 26.3    | ≥0.19.3       | ≥0.162.0+26.3       |

### Requirements

- **Java**: 25+ (GraalVM JDK 25 recommended)
- **Fabric Loader**: ≥0.19.3
- **Fabric API**: matching version

### Configuration

Server-side configuration lives in the world folder:

```
<world>/villagerdetails/config.json          # unified config: one section per rule / option
<world>/villagerdetails/trade_index.json     # name → id table used by the auto-trader
<world>/villager_details_binding.json        # rule on/off states
```

Furnace highlight section:

```json
{
  "FurnaceDirtyHighlight": {
    "viewChunkRadius": 0,
    "scanIntervalTicks": 20
  }
}
```

- `viewChunkRadius` — **scan radius in chunks** (0–32). `0` (the default) follows the server's own
  view distance (`view-distance` in `server.properties`); a positive value pins a square range of
  (2N+1)×(2N+1) chunks around each player, and only furnaces inside it are tracked / synced.
- `scanIntervalTicks` — how often tracked chunks are re-scanned (5–100). Changes inside a chunk
  (item inserted, furnace finished) are found within this interval and pushed incrementally.
- Both values are logged when loaded. With `viewChunkRadius: 0` the log line also reports the radius
  actually in effect (the server's view distance), e.g.
  `viewChunkRadius=0（跟随服务端视距(实际生效: 10 区块)）`, so you don't have to check
  `server.properties` yourself.

Portal bindings store a per-entry `id` plus a section-level `nextId` counter. If you hand-edit the
file, keep `nextId` greater than every existing id — otherwise a new binding could reuse an old
number. `/ec c reload` re-reads all of this from disk (see Commands).

The unified file also carries a top-level `__configVersion__` (currently `1.1.0`). On startup the mod
compares it with the version it expects:

- **missing, or lower than `1.1.0`** → the unified config is reset to defaults once (1.1.0 dropped
  compatibility with older formats) and the marker is rewritten;
- **lower than the current version but ≥ `1.1.0`** → registered migrations run in order, each one
  bumping the marker before the next step;
- **up to date** → nothing is rewritten.

To add a migration, implement `ConfigMigration` in the `com.villagerdetails.config.migration.version`
sub-package (class name like `V1_2_0_Migration`), register it in `MigrationManager.MIGRATIONS`, and
bump `MigrationManager.CURRENT_CONFIG_VERSION`.

### Networking notes

- Custom payloads are versioned by id and gated by `canSend` on the server, so a peer that does not
  know a channel simply never receives it — mixing versions does not disconnect anyone.
- The furnace sync payload id changed (`furnace_dirty_sync_v2`): an older client keeps working but
  shows no furnace highlight until it is updated.
- When changing wire formats for a release, bump `NetworkConstants.MIN_COMPATIBLE_VERSION` so old
  clients are rejected in the handshake instead of silently missing data.

### Building

```bash
# Build every version and collect the packages into lib/
./gradlew build            

# Build only one version's artifacts (the jar tasks are named separately)
./gradlew :v26.2:buildClientJar
./gradlew :v26.2:buildServerJar
./gradlew :v26.2:buildAllJar
```

Note: the root `build` task is what packages and collects everything. Per-version `build` only
compiles and runs checks — the default `jar` task is disabled, so use the `build*Jar` tasks above
(or `collectJars` at the root) to actually get jars.

Each version produces four artifacts, collected into `lib/<mod version>/`:

| Artifact       | Source sets            | `environment` | Use                                                       |
|----------------|------------------------|---------------|-----------------------------------------------------------|
| `-client.jar`  | main + client          | `client`      | client (single-player runs the integrated server too)      |
| `-server.jar`  | main + server          | `server`      | dedicated server (no client classes, smaller jar)          |
| `-all.jar`     | main + client + server | `*`           | one jar that works on both sides                           |
| `-sources.jar` | all sources            | —             | sources for debugging / reviewing                          |

Set `includeServerInClient=true` in `gradle.properties` if the client jar should also contain the
server source set (single-player / LAN without a separate server jar).

### Project Structure

```
├── common/          # Cross-version sources (main / client / server source sets, pulled in via srcDir)
├── v26.2/           # Minecraft 26.2 specific code and config
├── v26.3/           # Minecraft 26.3 specific code and config
├── tools/           # Helper scripts
└── lib/             # Packages collected by `collectJars` (client / server / all / sources)
```

### Developer notes

- Startup self-checks fail fast instead of silently dropping features:
  - `/ec c` refuses duplicate command literals (two rules must not register the same literal);
  - every rule's `registerName` must match its command literal (mismatch logs a WARNING);
  - rule configs must be unique instances and must not be registered in `ConfigRegistry`
    (`verifyReloadableConfigs`).
- Classpath scanning is restricted to an allow-list (`ScanService.SCAN_PACKAGES`) containing only
  the packages that hold scan-registered components (`cache.impl`, `event.impl`, `network.sync`,
  plus the client counterparts). Adding a scan-registered component package requires adding it
  there — a package outside the list logs an ERROR and yields zero classes, which the per-registry
  `checkNotEmpty` then reports.

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

#### 设置与修复

| 规则名 | 说明 |
|--------|------|
| Language | 模组语言设置 — 控制界面和消息的服务端翻译语言 |
| RuleCommand | `/ec` 全局权限管理 — 管理除 `/ec c` 以外的所有权限档位 |
| GlobalSpeedBypass | 全局移速防回弹 — 绕过服务端速度校验，防止移速过快（自身或骑乘载具）时被拉回和刷屏警告 |
| HappyGhastSpeed | 乐魂飞行速度 — 调整玩家骑乘乐魂时的飞行速度 |

#### 村民

| 规则名 | 说明 |
|--------|------|
| VillagerBedBinding | 村民床编辑器 — 使用命名为 `bed` 的拴绳修改村民绑定的床 |
| VillagerWorkBlockBinding | 村民工作方块编辑器 — 使用命名为 `work` 的拴绳修改工作方块 |
| VillagerBatchBedBinding | 批量村民床编辑器 — 区域内村民自动绑定最近的床 |
| VillagerInfiniteTrade | 无限交易 — 村民交易永不耗尽 |
| VillagerAutoTrader | 自动刷新交易 — 命名牌写目标附魔，右键图书管理员自动刷出 |
| VillagerSilentTrader | 无感交易刷新 — 刷新后自动移除命名标签 |
| VillagerAutoLockHitTrader | 自动锁定交易 — 刷新出的附魔书自动锁定 |
| VillagerGodTools | 神装工具刷新 — 使用 `331a/331b` 命名牌刷三附魔钻石工具 |
| VillagerTerracottaTrader | 陶瓦刷新 — 用陶瓦名称命名牌同时刷出陶瓦和带釉陶瓦 |
| VillagerHardWorking | 勤劳的村民 — 调整村民每次补货数量 |
| VillagerMoveController | 村民移动控制器 — 使用命名为 `move` 的拴绳设置目的地 |
| VillagerToolsmithExchange | 钻石兑换反转 — 工具匠改为绿宝石换钻石 |
| VillagerBookshelfDecompose | 书架自动拆解 — 用书架支付时自动拆成书 |
| VillagerEmeraldDecompose | 绿宝石块自动拆解 — 用绿宝石块支付时自动拆成绿宝石 |
| VillagerOneTickTraderCount | 每 tick 刷新交易次数调整 |

#### 实体与怪物

| 规则名 | 说明 |
|--------|------|
| NoSqueeze | 无挤压 — 指定实体不参与挤压、不受挤压伤害 |
| MobNoSpawn | 禁止生物生成 — 名单里的实体类型不再生成（自然生成 / 刷怪笼 / 刷怪蛋 / `/summon` / 繁殖） |
| PortalPiglinNoSpawn | 禁用地狱门刷僵尸猪灵 — 用命名为 `tool` 的拴绳圈出地狱门区域并执行绑定，该区域的地狱门不再刷出僵尸猪灵 |
| PiglinBarterDelay | 猪灵交易等待时间调整 |
| PiglinGoldBlockMultiply | 金块交易 — 猪灵愿捡金块，9 倍产出 |
| PiglinGoldBlockMultiplyPunishment | 金块交易惩罚时间调整 |
| NoBrainOnPortal | 过门失智 — 怪物穿过下界传送门后失去 AI |
| ZombieVillagerHelp | 僵尸村民秒治愈 — 贴 `help` 命名牌立刻变回村民 |

#### 植物与动物

| 规则名 | 说明 |
|--------|------|
| VillagerBoneMealFlower | 骨粉催熟主世界花 — 无视生物群系生成主世界花 |
| InfiniteShear | 无限剪羊毛 — 羊剪毛后立即恢复 |

#### 玩家

| 规则名 | 说明 |
|--------|------|
| PlayerHeadSitting | 玩家头顶乘坐 — 空手右键其他玩家坐到其头顶；乘客站在对方碰撞箱顶上（两个碰撞箱相切、不重叠）；潜行下坐；只在对方于自己客户端里开启「允许别人坐到我头上」时有效（没装 mod 的玩家默认不允许）；目标潜行 / 睡觉 / 换维度 / 骑上别的载具会自动下坐；叠加上限可配 |
| PlayerBlockSitting | 坐在楼梯 / 半砖上 — 空手右键**下半砖 / 下半楼梯**即可坐上去（上半砖、双半砖、上下颠倒的楼梯都不行），按 Shift 起身；坐面是方块平的那一面的顶面，楼梯和半砖一样高，楼梯再往矮的那一侧偏 2 个像素，免得背贴着上面那一级；每人可以在客户端配置里关掉自己的「允许坐在楼梯 / 半砖上」，该项默认开启，**没装本 mod 的玩家也按开启处理**；方块被挖掉 / 换成上半砖 / 规则关闭 / 掉线时座位自动收掉 |

#### 方块

| 规则名 | 说明 |
|--------|------|
| PortalBinding | 传送门绑定 — 主世界与地狱选区强制绑定；每条绑定都有稳定的 `#id`（`show` 显示，所有子命令按 id 操作） |
| PortalBindingEnhanced | 地狱门增强绑定**开关**（本身没有子命令）— 打开后 `PortalBinding` 不再要求目标位置已有传送门；用 `/ec PortalBindingEnhanced <状态>` 切换 |
| BlockCollectable | 方块可采集配置 |
| BlockMiningResistance | 方块挖掘抗性配置 |
| BlockExplosionResistance | 方块爆炸抗性配置 |
| BlockStrongPower | 方块红石强充能 — 配置的方块作为红石导体，传递红石信号 |
| TntNoDestroyDrops | TNT 不炸毁掉落物 |
| BoxCrafting | 盒子合成 — 潜影盒当作内容物合成 |
| EndPortalGravityBlock | 末地门重力方块 — 末地传送门 5x5x5 范围内悬空的指定方块会像沙子一样掉落 |
| FurnaceDirtyHighlight | 熔炉脏东西高亮 — 输入槽里的东西在该熔炉里没有可烧配方时高亮（燃料槽不检测）；数据按区块增量同步，客户端只保留视距内的坐标 |

### 命令

所有操作通过 `/ec` 命令完成：

```
/ec                              # 查看帮助与分类
/ec list                         # 列出全部规则
/ec list <category>              # 按分类列出
/ec list enable / disable        # 按状态筛选
/ec <rule>                       # 查看规则详情
/ec <rule> <state>               # 切换规则状态
/ec c <subcommand>               # 管理类子命令（选区、绑定、重载配置）
```

#### 区域绑定命令

绑定用**稳定的数字 ID**（`#1`、`#2`…）寻址，不再依赖“列表里第几条”。ID 只分配一次、删除后
不回收（删掉 `#2` 会留一个空洞），所以聊天栏里的按钮永远指向同一条绑定，别人中途增删也不会
点错。`show` 每条都显示成 `#<id>`，所有 `<id>` 参数都支持 Tab 补全。

```
/ec c PortalBinding set            # 缓存当前维度（主世界 / 地狱）的选区
/ec c PortalBinding bind           # 把两侧缓存绑成一条
/ec c PortalBinding show           # 列出所有绑定（#id + [刷新] / [删除] 按钮）
/ec c PortalBinding view <id>      # 高亮该绑定两侧
/ec c PortalBinding delete <id>    # 先清该绑定缓存的门坐标，再删除
/ec c PortalBinding refresh <id>   # 重扫两侧并重写门缓存

/ec c PortalPiglinNoSpawn bind     # 绑定当前选区
/ec c PortalPiglinNoSpawn show     # 列出所有区域（#id）
/ec c PortalPiglinNoSpawn view <id>
/ec c PortalPiglinNoSpawn delete <id>
/ec c PortalPiglinNoSpawn clear    # 清空全部区域
```

`bind` 只有真的写入了新记录才报成功：重复 / 两侧同维度 / 选区非法都会提示“绑定未写入”，
只 set 了一侧则提示先补齐两侧。旧版本聊天栏里的按钮（`delete <id> <版本>`）已经失效，
重新 `show` 一次用新按钮即可。

#### 其它管理子命令

```
/ec c reload                       # 从磁盘重读所有配置文件（仅 OP）
/ec c reload <name>                # 同样是整份重读；<name> 只做合法性校验
/ec c VillagerBatchBedBinding      # 把当前选区内的村民批量绑床
/ec c selection pos1|pos2|show|clear   # BB 选区工具
/ec c MobNoSpawn <实体id> true|false   # 逐条名单（NoSqueeze 同理）
/ec c BlockMiningResistance <方块id> <数值>   # 逐方块数值（BlockCollectable /
                                        BlockExplosionResistance / BlockStrongPower /
                                        EndPortalGravityBlock 同理）
```

旧写法 `/ec c batchBedBinding` 已改名为 `/ec c VillagerBatchBedBinding`（与规则名对齐），
旧拼写不再能解析。

`/ec c reload` 以**物理文件**为单位整份重读：`villagerdetails/config.json` 的所有 section、
`villager_details_binding.json` 和 `trade_index.json` 一起重新加载，手改配置不用重启就生效；
刚读进来的内容不会被立刻整份写回，你手改的值不会被覆盖。`/ec c reload <TAB>` 会列出全部可点名的
配置：3 个父配置 + 所有 section 名。

另外，`/op <玩家> <0-4>` 可以直接覆写玩家权限等级：4 级可以调整任何人（包括 4 级），其他等级只能调整比自己低的人、并且只能设置比自己低的等级；等级 0 等于取消 OP。`/op <玩家>` 本身仍是原版行为。

### 客户端功能

- **配置 GUI**：按 `J`（可自定义）打开模组配置界面，支持功能开关、渲染选项、按键绑定等
- **村民-床连线渲染**：在村民与其绑定的床之间绘制连线，距离过远时变色告警
- **EB/BB 选区高亮**：可视化实体-方块选择器与方块-方块选择器的选中区域
- **传送门区域高亮**：绑定传送门的两侧区域可视化
- **熔炉脏高亮**：服务端只同步你视距内的熔炉（按区块 ADD/REMOVE/CLEAR），客户端不会随全服数据膨胀；渲染时也不再做距离过滤
- **聊天栏里的绑定 ID**：传送门 / 猪灵区域条目都带 `#id`，可以直接从列表点高亮或删除，不用自己数第几条

### 支持的 Minecraft 版本

| 版本 | Fabric Loader | Fabric API       |
|------|---------------|------------------|
| 26.2 | ≥0.19.3       | ≥0.160.0+26.2    |
| 26.3 | ≥0.19.3       | ≥0.162.0+26.3    |

### 环境要求

- **Java**: 25+（GraalVM JDK 25 推荐）
- **Fabric Loader**: ≥0.19.3
- **Fabric API**: 对应版本

### 配置

服务端配置都在存档目录里：

```
<存档>/villagerdetails/config.json          # 统一配置：每个规则 / 选项一个 section
<存档>/villagerdetails/trade_index.json     # 自动交易用的「名字 → id」表
<存档>/villager_details_binding.json        # 规则开关状态
```

熔炉高亮 section：

```json
{
  "FurnaceDirtyHighlight": {
    "viewChunkRadius": 0,
    "scanIntervalTicks": 20
  }
}
```

- `viewChunkRadius`：**扫描半径（区块）**，0–32。填 `0`（默认）跟随服务端视距
  （`server.properties` 的 `view-distance`）；填正数则固定为玩家周围 (2N+1)×(2N+1) 个区块的
  正方形范围，只跟踪、只同步这个范围内的熔炉。
- `scanIntervalTicks`：多久重扫一遍被视距覆盖的区块（5–100）。区块内的状态变化
  （放进东西 / 烧完）会在这个间隔内被发现并增量推送。
- 两个值在加载时都会打进日志。`viewChunkRadius` 填 `0` 时日志会直接给出实际生效的区块半径
  （跟随服务端视距），例如 `viewChunkRadius=0（跟随服务端视距(实际生效: 10 区块)）`，
  不用自己去翻 `server.properties`。

传送门绑定在每条记录里存 `id`，section 里另存一个 `nextId` 计数器。手改文件时请保证
`nextId` 大于所有已有 id，否则新绑定可能复用旧号。`/ec c reload` 会从磁盘重读这些内容
（见「命令」）。

统一配置的根节点还有一个 `__configVersion__`（当前 `1.1.0`）。启动时会和代码期望的版本比对：

- **缺失，或低于 `1.1.0`** → 统一配置整份重置为默认（1.1.0 起不再兼容旧格式），并写回新版本号；
- **低于当前版本但 ≥ `1.1.0`** → 按顺序执行登记过的迁移，每步执行完立刻推进版本号；
- **已是最新** → 什么都不重写。

要新增迁移：在 `com.villagerdetails.config.migration.version` 子包下实现 `ConfigMigration`
（类名形如 `V1_2_0_Migration`），在 `MigrationManager.MIGRATIONS` 登记，并把
`MigrationManager.CURRENT_CONFIG_VERSION` 抬到新版本。

### 网络协议说明

- 自定义包按 id 区分版本，服务端发送前会过 `canSend`：对端不认识某个通道就干脆不发，
  混用新旧版本不会掉线。
- 熔炉同步的包 id 变了（`furnace_dirty_sync_v2`）：旧客户端可以正常游玩，但在升级前看不到
  熔炉高亮。
- 发布时如果改了线格式，记得把 `NetworkConstants.MIN_COMPATIBLE_VERSION` 抬到本版，
  让旧客户端在握手阶段被拒，而不是静默缺功能。

### 构建

```bash
# 构建所有版本并把产物收集到 lib/
./gradlew build            

# 仅构建指定版本的产物（打包任务是分开命名的）
./gradlew :v26.2:buildClientJar
./gradlew :v26.2:buildServerJar
./gradlew :v26.2:buildAllJar
```

注意：根项目的 `build` 任务才会打包并收集产物；子项目的 `build` 只编译 + 跑检查 ——
默认的 `jar` 任务是关掉的，真要出包请用上面的 `build*Jar` 任务（或根项目的 `collectJars`）。

每个版本产出四个产物，收集到 `lib/<模组版本>/`：

| 产物           | 源集                   | `environment` | 用途                                     |
|----------------|------------------------|---------------|------------------------------------------|
| `-client.jar`  | main + client          | `client`      | 客户端（单人存档里集成服也能跑）          |
| `-server.jar`  | main + server          | `server`      | 专用服务端（不含客户端类，体积更小）      |
| `-all.jar`     | main + client + server | `*`           | 一个包两端通用                            |
| `-sources.jar` | 全部源码               | —             | 调试 / 审阅用的源码包                     |

想让客户端包直接带上 server 源集（不单独装服务端包的单人 / 局域网场景），把
`gradle.properties` 里的 `includeServerInClient` 设为 `true`。

### 项目结构

```
├── common/          # 跨版本源码（main / client / server 三个源集，由各版本子项目 srcDir 引入）
├── v26.2/           # Minecraft 26.2 专属代码与配置
├── v26.3/           # Minecraft 26.3 专属代码与配置
├── tools/           # 辅助脚本
└── lib/             # collectJars 收集的成品包（client / server / all / sources）
```

### 开发者说明

- 启动期自检采用「直接失败」而不是静默少注册：
  - `/ec c` 会拒绝重复的命令字面量（两条规则不能注册同一个 literal）；
  - 规则的 `registerName` 必须与命令字面量一致（不一致会打 WARNING）；
  - 规则配置必须是唯一实例，且不能再注册进 `ConfigRegistry`（`verifyReloadableConfigs`）。
- 扫包有白名单（`ScanService.SCAN_PACKAGES`）：只有真正含有「扫包注册组件」的包会被索引
  （`cache.impl`、`event.impl`、`network.sync`，以及客户端对应的几个）。新增一个扫包注册中心时
  必须把它的包加进白名单 —— 不在名单里的包会打 ERROR 且扫到 0 个类，随后由各注册中心的
  `checkNotEmpty` 报出来。

### 许可证

[MIT](LICENSE)

### 作者

Acvvb

### 链接

- [GitHub Releases](https://github.com/Acvvb/VillagerDetails/releases)