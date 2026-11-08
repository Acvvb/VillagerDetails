# Network Framework

Payload type to channel to receiver to main-thread dispatch, plus muting, handshake and the client peer of the VillagerDetails network layer.

**[English](#english) | [中文](#中文)**

---

<a id="english"></a>

## English

> **Scope**: `PayloadTypes` / `PayloadId` / `PayloadRegistration` / `ClientboundPayload` / `ServerboundPayload` / `SyncPayload` ·
> `ServerChannels` / `ClientboundChannel` / `ProtocolChannel` / `SyncChannel` / `ServerReceiver` / `SyncEvents` /
> `SyncSubscriptions` · `SyncProtocol` / `NetworkConstants` / `ModVersion` · `ModCodecs` / `DescriptorCodecs` ·
> plus the client peer (`ClientChannels` / `ClientReceiver` / `ClientSessionStates`).
> How the **content** of a descriptor is generated is covered in [05](05-rule-framework.md).

---

### 1. Directories and role interfaces: one class does one thing

```
network.sync.
├── payload.clientbound/   XxxPayload implements ClientboundPayload   → packet definition (ID + CODEC)
├── payload.serverbound/   XxxPayload implements ServerboundPayload   → packet definition (ID + CODEC)
├── clientbound/           XxxChannel extends SyncChannel<XxxPayload> → "how this packet is sent"
├── receiver/              XxxReceiver implements ServerReceiver<…>    → "what the server does with it"
└── event/                 XxxEvents implements SyncEvents            → event wiring (JOIN sync / rule listeners / periodic tasks)
```

Current counts: **10 channels / 11 receivers / 5 event classes / 21 payload definitions** (10 S→C + 11 C→S).

| Directory | Role interface | Registration action |
|---|---|---|
| `payload.*` | `ClientboundPayload` / `ServerboundPayload` | `static register(Class)` (called reflectively by `PayloadTypes`) |
| `clientbound` | `ClientboundChannel` | `register()` — **self-check**: is the payload definition registered (throws when missing) |
| `receiver` | `ServerReceiver` | `registerServerReceiver()` — registers a global `ServerPlayNetworking` receiver |
| `event` | `SyncEvents` | `registerEvents()` |

**Why three directories**: when a feature has to "send + receive + wire" at the same time, the three pieces stay apart, so changing one never touches another; and because each directory has its own role interface, scanning itself proves "I put the class in the right place".

---

### 2. Registration chain (the order matters)

```
VillagerDetails.onInitialize()              [main, runs on both sides]
  └─ PayloadTypes.registerAll()
       ├─ scan network.sync.payload (including sub-packages)
       ├─ for every concrete SyncPayload subclass: clientbound or serverbound?
       │     ├─ both / neither → Failure (not registered, and reported)
       │     └─ exactly one    → call that interface's register(Class)
       └─ collect the id sets of both directions → Registered(clientbound, serverbound)

VillagerDetailsServer.init()                [server]
  └─ ServerChannels.registerAll()           [idempotent, AtomicBoolean]
       ├─ PayloadTypes.registerAll()        (idempotent, types are registered first)
       ├─ walk ROLES row by row:
       │    ① network.sync.clientbound → ClientboundChannel.register()   (payload self-check)
       │    ② network.sync.receiver    → ServerReceiver.registerServerReceiver()
       │    ③ network.sync.event       → SyncEvents.registerEvents()
       └─ any failure → throw IllegalStateException, startup aborts
```

**Fail-fast**: `ServerChannels#registerAll` never logs a failed registration and carries on. A failed
`SyncChannel#register` (payload self-check, protocol-marker check) would otherwise leave behind a channel that
still exists as an `INSTANCE` while nothing ever receives its packets — `sendTo` keeps calling into it and the
symptom becomes "the packet went out but nothing happened", which is far harder to trace than a refused
startup. So the scanning layer treats channel registration as fail-fast: failures are collected, summarized
through `ChannelScanner.reportFailures`, and then the whole registration throws.

**Why payload types must be registered once on each physical side**: Fabric's `PayloadTypeRegistry` works as
"the receiving side looks the codec up by id", and client and server are two separate JVMs. Registering on one
side only → the other side receives a custom packet with no decoder and **disconnects outright**. That is why
`PayloadTypes` lives in the `main` source set and is called from both entry points.

---

### 3. Payload definitions and codecs

```
XxxPayload implements ClientboundPayload / ServerboundPayload
  ├─ public static final PayloadId<XxxPayload> ID = new PayloadId<>(VillagerDetails.id("xxx"), XxxPayload::new)  // shape
  └─ CODEC (StreamCodec): the payload declares how it is read and written
        ↓
PayloadRegistration.register(id, codec) → PayloadTypeRegistry registration
ModCodecs / DescriptorCodecs            → compound codecs shared by both sides (lists / enums / descriptor primitives)
```

**Constraints** (the easiest places to break things):

1. **A payload `ID` may be registered exactly once**: while scanning, `PayloadTypes` keeps an
   `Identifier → declaring class` map per direction and checks it before registering; two payloads declaring
   the same `Identifier` raise `IllegalStateException` and startup fails on the spot. The check exists because
   Fabric's `PayloadTypeRegistry` may **overwrite silently instead of raising**, which turns a duplicated id
   into "a packet the other side cannot decode" — a runtime symptom far more expensive to chase than one
   refused startup.
2. **A payload `CODEC` may only mention types both sides have**: `DescriptorCodecs` exists precisely so that a
   descriptor (generated on the server, rendered on the client) can be encoded and decoded from the `main`
   source set.
3. **A released payload id may not change meaning**: when the protocol shape changes, bump
   `SyncProtocol.VERSION` and add a **new payload id**; never stuff compatibility fields into an existing id
   (see §6).

---

### 4. Channels: the server's only send outlet

`SyncChannel<P>` is the base class of `ClientboundChannel<P>`, and every custom packet send funnels through
its `sendTo`:

```
sendTo(player, payload)
  ├─ payload == null            → return
  ├─ !canSendTo(player)         → return (peer lacks the mod / channel muted)
  └─ try ServerPlayNetworking.send(player, payload)
        catch Exception → reportFailure(player, e)   // never rethrown

canSendTo(player)
  ├─ player == null → false
  ├─ non-protocol channel && SyncSubscriptions.isMuted(player, id.id()) → false
  └─ ServerPlayNetworking.canSend(player, id.type())     // is the mod installed on the peer
        catch Exception → reportFailure + false

broadcast(payload)   → forEachPlayer(p -> sendTo(p, payload))   // one failure does not stop the loop
forEachPlayer(...)   → RuleCache.getServer().getPlayerList()    (returns directly when server is null)
```

Three design constraints:

1. **`ServerPlayNetworking.send` appears exactly once in the whole project, inside `sendTo`** — as long as
   nobody bypasses it, "a vanilla client crashes on an unknown packet" cannot happen.
2. **`sendTo` / `canSendTo` are `final`**: the exception fallback and the log-spam suppression are maintained
   in one place; subclasses can only change behaviour through `isProtocolChannel()`.
3. **`canSendTo` is guaranteed not to throw**: callers routinely "check whether sending is possible, then
   decide whether to build the payload"; if it threw, the exception would land in a tick or in an event
   callback. A failed check is always treated as "do not send".

#### Protocol channels (exempt from muting, not exempt from "is it installed")

```
isProtocolChannel() defaults to false (business channel, subject to muting)
a protocol channel does both: implements ProtocolChannel  +  overrides isProtocolChannel() to return true
```

When the two disagree, `register()` throws `IllegalStateException`:

- marked but not returning `true` → treated as a business channel, and the client mutes the handshake packet
  away (a silent no-op);
- returns `true` but not marked → there is no single source of truth for "which channels bypass muting"
  (grep finds nothing).

**Why protocol channels still go through `canSend`**: muting expresses "I do not want to see this feature's
display", while the handshake is the precondition for "can we work together at all"; but when the peer does
**not** have the mod it cannot receive custom packets at all, and letting them through would only disconnect
the vanilla client.

#### Failure reporting

`reportFailure` uses `Set<Identifier> REPORTED_FAILURES` to do "WARN once per channel, DEBUG afterwards". The
gain is that the log does not get flooded (send sites sit inside ticks, where failures usually arrive in runs
of dozens); the price is that "later failures stay invisible" — so the first one deserves attention.

---

### 5. Receiving: main-thread dispatch is done centrally in the framework

```java
public interface ServerReceiver<P extends SyncPayload<P>> {
    PayloadId<P> id();
    default void registerServerReceiver() {                     // ← the default implementation the framework provides
        ServerPlayNetworking.registerGlobalReceiver(id().type(),
            (payload, context) -> context.server().execute(() -> handle(context.player(), payload)));
    }
    void handle(ServerPlayer player, P payload);                // ← business code writes only this
}
```

`context.server().execute(...)` switches the callback back to the server main thread. **The framework has to
do this centrally**: Fabric runs receive callbacks on network threads (`handleClientTickEnd` / the netty
thread), and touching the world, entities or `RuleCache.setState` there is undefined behaviour.

---

### 6. Handshake and protocol version

```
client JOIN
  → C2SHandshakePayload(modVersion)                       [clientbound direction, sent by the client]
  → HandshakeReceiver.handle(player, payload)
       ├─ PlayerModVersion.INSTANCE.set(player, reported)   // recorded whether or not it is acceptable
       ├─ NetworkConstants.evaluate(reported)
       │     ├─ BLOCKED (exact blacklist, highest priority)
       │     ├─ UNPARSEABLE (the version string is not a valid semver)
       │     ├─ TOO_OLD (< MIN_COMPATIBLE_VERSION = "1.1.0")
       │     └─ OK
       └─ OK → HandshakeSyncChannel.respond(player)   // ack: the client uses it to confirm the handshake
          otherwise → chat message + disconnect (the text is resolved to plain text first, because old clients have no key)
```

Three deliberate trade-offs:

1. **The policy is a compile-time constant** (`NetworkConstants`): protocol compatibility is a release
   decision; exposing it to `json` would produce "the lower bound is written as garbage → everyone is
   rejected" or "the lower bound is missing → old clients receive packets they cannot decode".
2. **Only a lower bound plus a blacklist, no upper bound**: a newer client connecting to an older server is
   let through by default — it can work out for itself what the other side lacks.
3. **"No handshake received" does not kick anyone**: a vanilla client not sending this packet is **normal**
   (all S→C packets are already blocked in `canSendTo`), and old clients are let through as "version unknown".
   **This protocol does not enforce "the mod must be installed"**.

The `ModVersion.parse` pitfall: Fabric's `Version.parse("garbage")` **does not throw** — it parses the string
into a "string version", and such a version sorts **after** every semver, so checking only "did it throw"
would let garbage pass as "higher version, compatible". Hence the second check "is the parsed result a
`SemanticVersion`".

**`SyncProtocol.VERSION = 2`** is the shape version of the descriptor / rule-sync protocol, used in two
places: the client declares it in `RuleDescriptorHelloPayload`, and the server carries a copy inside the
descriptor packet. Two independent numbers sit side by side and neither is derived from the other:
`SyncProtocol.VERSION` describes the packet shape, while `NetworkConstants.MIN_COMPATIBLE_VERSION` is the
lower bound on the mod semver. The javadoc on `SyncProtocol` carries the release checklist: every change to
packet fields, every added or removed payload id and every change to the descriptor structure must bump
`VERSION` in the same commit; a changed shape gets a **new payload id**; an existing id never gains
compatibility fields. The server only logs a version mismatch instead of refusing the connection, so a
forgotten bump degrades into "the connection works, the fields are misaligned" — which is exactly why the rule
is written down at the definition site. The current policy is **no old-version compatibility**: v1's
`server_rule_sync` / `server_rule_request` have been removed, and a change of shape means a version bump plus
a new payload id.

---

### 7. Mute subscriptions (`SyncSubscriptions`)

```
client (SyncSubscriptionClient) turns a channel's display off locally → sends SyncSubscriptionPayload(channel, muted)
  → SyncSubscriptionReceiver → SyncSubscriptions.setMuted(player, channel, muted)
  → the server's SyncChannel.canSendTo consults it first: muted → scanning and payload building are skipped
  → player quits → clearPlayer(uuid) drops the whole record (next login defaults to "send")
```

- keys are `UUID` rather than `ServerPlayer`: player objects get rebuilt, and a UUID never holds on to an
  offline instance;
- when the set becomes empty the whole record is removed (no empty sets left behind);
- it is not in the `cache.impl` package, yet it also lives for exactly one session, so it is added to
  `ServerSessionStates` by hand — one line of the manual registration list described in
  [04](04-session-state.md#2-扫包机制三层) §2.

---

### 8. The client peer

Client registration is likewise "scan packages + fall back on failure", entered from
`VillagerDetailsClient.onInitializeClient()`:

| # | Call | What it does |
|---|---|---|
| 1 | `ClientRenderConfig.load()` | client render config (defaults are generated when the file is missing) |
| 2 | `VillagerDetailsKeyMappings.register()` | key bindings (default `J` opens the config screen) |
| 3 | `ClientSessionStates.registerAll()` | scans `client.cache.impl` (9 `SessionState` implementations) |
| 4 | `ClientPlayConnectionEvents.DISCONNECT` → `ClientSessionStates.clearAll()` | clears everything on disconnect / world switch |
| 5 | `ClientChannels.registerAll()` | scans `client.network.sync`: receivers (`ClientReceiver`) + each channel's client-side events |
| 6 | `ClientFeatureScanner.registerAll()` | scans `client.feature`: renderers / client features (10) |

The one-to-one mapping:

| Server | Client |
|---|---|
| `ServerChannels` | `ClientChannels` |
| `ServerReceiver` (receives C→S) | `ClientReceiver` (receives S→C, registers `ClientPlayNetworking` in a default method) |
| `ServerSessionStates` (scans `cache.impl`) | `ClientSessionStates` (scans `client.cache.impl`) |
| `SyncChannel.sendTo` (the only `ServerPlayNetworking.send`) | client sends go through `ClientRequests` / `SyncSubscriptionClient` |

**The client mixin list is empty** (`villagerdetails.client.mixins.json` carries `[]` for both `client` and
`mixins`): the client only renders, hosts the config screen and receives packets, and injects no vanilla code.

---

### 9. Performance order of magnitude and failure semantics

| Operation | Order of magnitude | Notes |
|---|---|---|
| `canSendTo` | sub-microsecond to a microsecond | one mute-table lookup + one Fabric capability probe; even the dirty-furnace pattern of "every tick × every player" holds up |
| `sendTo` | microseconds | build the payload + write the connection; a failure is only logged, never retried |
| full descriptor packet (`build`) | milliseconds | 40 rules × translation + list data; sent only on JOIN / rule change / request |
| `buildOptions` | milliseconds | enum registry ids (which can reach tens of thousands), capped by `MAX_OPTION_IDS = 20_000` |

Failure semantics: **if it cannot be sent, it is not sent** (no queueing, no retry, no throw). Sync is
best-effort — dropping one highlight frame does not affect correctness and the next change pushes again;
data that needs real consistency goes through the write path ([02](02-config-pipeline.md)).

---

<a id="中文"></a>

## 中文

> 覆盖：`PayloadTypes` / `PayloadId` / `PayloadRegistration` / `ClientboundPayload` / `ServerboundPayload` / `SyncPayload` ·
> `ServerChannels` / `ClientboundChannel` / `ProtocolChannel` / `SyncChannel` / `ServerReceiver` / `SyncEvents` /
> `SyncSubscriptions` · `SyncProtocol` / `NetworkConstants` / `ModVersion` · `ModCodecs` / `DescriptorCodecs` ·
> 以及客户端对端（`ClientChannels` / `ClientReceiver` / `ClientSessionStates`）。
> 描述符的**内容**怎么生成在 [05](05-rule-framework.md)。

---

### 1. 目录与角色接口：一个类只管一件事

```
network.sync.
├── payload.clientbound/   XxxPayload implements ClientboundPayload   → 包定义（ID + CODEC）
├── payload.serverbound/   XxxPayload implements ServerboundPayload   → 包定义（ID + CODEC）
├── clientbound/           XxxChannel extends SyncChannel<XxxPayload> → "这个包怎么发"
├── receiver/              XxxReceiver implements ServerReceiver<…>    → "服务端收到它干什么"
└── event/                 XxxEvents implements SyncEvents            → 事件接线（JOIN 同步 / 规则监听 / 周期任务）
```

现役数量：**通道 10 条 / 收包 11 个 / 事件 5 个 / 包定义 21 个**（10 S→C + 11 C→S）。

| 目录 | 角色接口 | 注册动作 |
|---|---|---|
| `payload.*` | `ClientboundPayload` / `ServerboundPayload` | `static register(Class)`（由 `PayloadTypes` 反射调） |
| `clientbound` | `ClientboundChannel` | `register()` —— **自检**：包定义是否已登记（缺了就抛） |
| `receiver` | `ServerReceiver` | `registerServerReceiver()` —— 注册 `ServerPlayNetworking` 全局收包 |
| `event` | `SyncEvents` | `registerEvents()` |

**为什么拆三个目录**：一个功能如果需要同时“发 + 收 + 接线”，三块分开放，改一处不会碰到另一处；
而且扫包时角色接口不同，能天然保证“我把类放对了目录”。

---

### 2. 注册链（顺序不能乱）

```
VillagerDetails.onInitialize()              [main，两端都跑]
  └─ PayloadTypes.registerAll()
       ├─ 扫 network.sync.payload（含子包）
       ├─ 每个具体 SyncPayload 子类：看实现的是 Clientbound 还是 Serverbound
       │     ├─ 两个都实现 / 都没实现 → Failure（不登记，报出来）
       │     └─ 恰好一个 → 调那个接口的 register(Class)
       └─ 记录两侧 id 集合 → Registered(clientbound, serverbound)

VillagerDetailsServer.init()                [server]
  └─ ServerChannels.registerAll()           [幂等，AtomicBoolean]
       ├─ PayloadTypes.registerAll()        （幂等，保证类型先登记）
       ├─ ROLES 逐行扫：
       │    ① network.sync.clientbound → ClientboundChannel.register()   （自检包定义）
       │    ② network.sync.receiver    → ServerReceiver.registerServerReceiver()
       │    ③ network.sync.event       → SyncEvents.registerEvents()
       └─ 任何失败 → 抛 IllegalStateException，启动中止
```

**失败即中止**：`ServerChannels#registerAll` 不会“记一条日志然后继续跑”。一次失败的
`SyncChannel#register`（包定义自检、协议标记自检）如果被吞掉，留下的是一条 `INSTANCE` 还在、
包却没人收的通道 —— 业务侧 `sendTo` 照样调用，症状变成“通了但没效果”，排查成本远高于一次启动失败。
所以扫包层把通道注册当作 fail-fast：失败项先收集、经 `ChannelScanner.reportFailures` 汇总，
整轮结束再整体抛出去。

**为什么包类型必须在物理两端各登记一次**：Fabric 的 `PayloadTypeRegistry` 是“收包方按 id 查 codec”，
客户端和服务端是两个独立 JVM。只在一端登记 → 对端收到自定义包时找不到 decoder，**直接 disconnect**。
所以 `PayloadTypes` 放在 `main` 源集、由两端入口点都调用。

---

### 3. 包定义与编解码

```
XxxPayload implements ClientboundPayload / ServerboundPayload
  ├─ public static final PayloadId<XxxPayload> ID = new PayloadId<>(VillagerDetails.id("xxx"), XxxPayload::new)  // 形如
  └─ CODEC（StreamCodec）：payload 自己声明怎么读写
        ↓
PayloadRegistration.register(id, codec) → PayloadTypeRegistry 登记
ModCodecs / DescriptorCodecs            → 两端共用的复合 codec（列表 / 枚举 / 描述符原语）
```

**约束**（改动时最容易出错的地方）：

1. **同一个 `ID` 只能登记一次**：`PayloadTypes` 扫包时按方向维护一张 `Identifier → 声明类` 的表，
   登记前显式查重；两个包定义写同一个 `Identifier` 会抛 `IllegalStateException`，启动直接失败。
   之所以要这一层，是因为 Fabric 的 `PayloadTypeRegistry` 可能**不报错而是默默覆盖** ——
   那会把故障变成一个“发出去对端解不开的包”，排查成本远高于一次启动失败。
2. **`payload` 的 CODEC 里只能出现两端都有的类型**：`DescriptorCodecs` 之所以存在，就是为了让描述符
   （服务端生成、客户端渲染）在 `main` 源集里可编解码。
3. **包 id 一旦发布不能改语义**：协议形状变了就升 `SyncProtocol.VERSION` + 加**新包 id**，
   不在同一个 id 里塞兼容字段（见 §6）。

---

### 4. 通道：服务端唯一的发送口

`SyncChannel<P>` 是 `ClientboundChannel<P>` 的基类，所有自定义包发送都收敛在它的 `sendTo` 里：

```
sendTo(player, payload)
  ├─ payload == null            → return
  ├─ !canSendTo(player)         → return（对端没装 / 被静音）
  └─ try ServerPlayNetworking.send(player, payload)
        catch Exception → reportFailure(player, e)   // 绝不外抛

canSendTo(player)
  ├─ player == null → false
  ├─ 非协议通道 && SyncSubscriptions.isMuted(player, id.id()) → false
  └─ ServerPlayNetworking.canSend(player, id.type())     // 对端装没装
        catch Exception → reportFailure + false

broadcast(payload)   → forEachPlayer(p -> sendTo(p, payload))   // 单人失败不影响循环
forEachPlayer(...)   → RuleCache.getServer().getPlayerList()    （server 为 null 直接返回）
```

三条设计约束：

1. **`ServerPlayNetworking.send` 全项目只出现在 `sendTo` 一处** —— 只要没人绕过它，原版客户端
   “收到未知包闪退”就不可能发生。
2. **`sendTo` / `canSendTo` 都是 `final`**：统一维护异常兜底与防刷屏日志，子类只能通过 `isProtocolChannel()` 改行为。
3. **`canSendTo` 保证不抛**：调用方普遍写成“先判能不能发，再决定要不要组包”；
   一旦它抛出去，异常会落在 tick / 事件回调里。判定失败统一按“不发”处理。

#### 协议通道（免静音但不能免“装没装”）

```
isProtocolChannel() 默认 false（业务通道，受静音限制）
协议通道要同时做两件事：implements ProtocolChannel  +  重写 isProtocolChannel() 返回 true
```

两者不一致会在 `register()` 时抛 `IllegalStateException`：

- 标了却没返回 `true` → 被当业务通道，握手包会被客户端静音吞掉（静默失效）；
- 返回 `true` 却没标 → “哪些通道免静音”就没有单一事实来源（grep 不到）。

**为什么协议通道仍要过 `canSend`**：静音表达的是“这个功能的显示我不想看”，
而握手是“我们能不能一起工作”的前提；但对端**没装模组**时它根本收不到自定义包，放行只会让原版客户端掉线。

#### 失败上报

`reportFailure` 用 `Set<Identifier> REPORTED_FAILURES` 做“每通道只 WARN 一次，之后 DEBUG”。
收益是日志不被刷爆（发包点分布在 tick 里，失败往往连成几十次），代价是“后续失败看不见”
—— 所以第一次必须留意。

---

### 5. 收包：主线程派发在框架层统一做

```java
public interface ServerReceiver<P extends SyncPayload<P>> {
    PayloadId<P> id();
    default void registerServerReceiver() {                     // ← 框架提供的默认实现
        ServerPlayNetworking.registerGlobalReceiver(id().type(),
            (payload, context) -> context.server().execute(() -> handle(context.player(), payload)));
    }
    void handle(ServerPlayer player, P payload);                // ← 业务只写这个
}
```

`context.server().execute(...)` 把回调切回服务端主线程。**这条必须由框架统一做**：
Fabric 的收包回调跑在网络线程上（`handleClientTickEnd` / netty 线程），
在那里碰世界、实体、`RuleCache.setState` 都是未定义行为。

---

### 6. 握手与协议版本

```
客户端 JOIN
  → C2SHandshakePayload(modVersion)                       [clientbound 方向由客户端发]
  → HandshakeReceiver.handle(player, payload)
       ├─ PlayerModVersion.INSTANCE.set(player, reported)   // 不管合不合规都记
       ├─ NetworkConstants.evaluate(reported)
       │     ├─ BLOCKED（精确拉黑，优先级最高）
       │     ├─ UNPARSEABLE（版本串不是合法 semver）
       │     ├─ TOO_OLD（< MIN_COMPATIBLE_VERSION = "1.1.0"）
       │     └─ OK
       └─ OK → HandshakeSyncChannel.respond(player)   // 回执：客户端据此确认握手完成
          否则 → 聊天提示 + disconnect（文案先 resolve 成纯文本，因为老客户端没有 key）
```

三个刻意的取舍：

1. **策略是编译期常量**（`NetworkConstants`）：协议兼容是发布决定，交给 `json` 改会出现
   “下限写成乱码 → 全员被拒”或“下限缺省 → 旧客户端收到解不开的包”。
2. **只做下限 + 黑名单，没有上限**：高版本客户端连低版本服务端默认放行 —— 它能自己判断对面缺什么。
3. **“没收到握手掌”不踢人**：原版客户端不发这个包是**正常**的（所有 S→C 包在 `canSendTo` 就被挡住了），
   老客户端也按“版本未知”放行。**本协议不做“必须装模组”的强制**。

`ModVersion.parse` 的易错点：Fabric 的 `Version.parse("垃圾串")` **不抛异常**，而是解析成“字符串版本”，
这种版本比较时排在所有 semver **之后** —— 只判“抛没抛异常”的话，一个乱码就能被当成“版本更高、兼容”。
所以必须再核一次“解析结果是不是 `SemanticVersion`”。

**协议版本 `SyncProtocol.VERSION = 2`** 是描述符 / 规则同步协议的形状版本，用在两处：客户端
`RuleDescriptorHelloPayload` 里声明、服务端描述符包里带一份。两个独立的号并列存在，互不推导：
`SyncProtocol.VERSION` 描述包形状，`NetworkConstants.MIN_COMPATIBLE_VERSION` 是模组 semver 的下限。
`SyncProtocol` 的 javadoc 里写着发布 checklist：任何“改包字段 / 增删包 id / 改描述符结构”的改动，
都必须在同一次提交里抬 `VERSION`；形状变了就加**新包 id**，已有 id 永不追加兼容字段。
服务端对版本不一致只记日志、不拒绝，所以漏抬版本会退化成“能连上、字段错位”——
这也是把这条规则写在定义处的原因。当前策略是**不做旧版本兼容**：v1 的
`server_rule_sync` / `server_rule_request` 已删除，形状一变就升版本 + 加新包 id。

---

### 7. 静音订阅（`SyncSubscriptions`）

```
客户端（SyncSubscriptionClient）本地关掉某通道显示 → 发 SyncSubscriptionPayload(channel, muted)
  → SyncSubscriptionReceiver → SyncSubscriptions.setMuted(player, channel, muted)
  → 服务端 SyncChannel.canSendTo 先查它：被静音 → 连扫描 / 组包都省了
  → 玩家退出 → clearPlayer(uuid) 丢掉整条记录（下次进服默认“要发”）
```

- 键用 `UUID` 而不是 `ServerPlayer`：玩家对象会被重建，而且 UUID 不会攥住已下线的实例；
- 集合空了就顺手删掉整条记录（不留空集合）；
- 它不在 `cache.impl` 包里，但同样只活一次会话，所以被**手工**加进 `ServerSessionStates`
  —— 即 [04](04-session-state.md#2-扫包机制三层) §2 里那份手工登记清单的一行。

---

### 8. 客户端对端

客户端的注册同样是“扫包 + 失败兜底”，入口在 `VillagerDetailsClient.onInitializeClient()`：

| 顺序 | 调用 | 内容 |
|---|---|---|
| 1 | `ClientRenderConfig.load()` | 客户端渲染配置（文件不存在时生成默认） |
| 2 | `VillagerDetailsKeyMappings.register()` | 快捷键（默认 `J` 打开配置界面） |
| 3 | `ClientSessionStates.registerAll()` | 扫 `client.cache.impl`（`SessionState` 实现 9 个） |
| 4 | `ClientPlayConnectionEvents.DISCONNECT` → `ClientSessionStates.clearAll()` | 断线 / 切世界整份清空 |
| 5 | `ClientChannels.registerAll()` | 扫 `client.network.sync`：收包器（`ClientReceiver`）+ 各通道的客户端事件 |
| 6 | `ClientFeatureScanner.registerAll()` | 扫 `client.feature`：渲染器 / 客户端功能（10 个） |

对照关系：

| 服务端 | 客户端 |
|---|---|
| `ServerChannels` | `ClientChannels` |
| `ServerReceiver`（收 C→S） | `ClientReceiver`（收 S→C，默认方法注册 `ClientPlayNetworking`） |
| `ServerSessionStates`（扫 `cache.impl`） | `ClientSessionStates`（扫 `client.cache.impl`） |
| `SyncChannel.sendTo`（唯一的 `ServerPlayNetworking.send`） | 客户端发包统一走 `ClientRequests` / `SyncSubscriptionClient` |

**客户端的 mixin 清单是空的**（`villagerdetails.client.mixins.json` 的 `client` 与 `mixins` 都是 `[]`）：
客户端只做渲染、配置界面与收包，不注入原版代码。

---

### 9. 性能量级与失败语义

| 操作 | 量级 | 说明 |
|---|---|---|
| `canSendTo` | 亚微秒~微秒 | 一次静音表查找 + Fabric 能力探测；脏熔炉那种“每 tick × 每玩家”的调用也扛得住 |
| `sendTo` | 微秒级 | 组包 + 写连接；失败只记日志不重试 |
| 描述符整包（`build`） | 毫秒级 | 40 条规则 × 翻译 + 列表数据；只在 JOIN / 规则变化 / 请求时发 |
| `buildOptions` | 毫秒级 | 枚举注册表 id（可达上万条），`MAX_OPTION_IDS = 20_000` 封顶 |

失败语义：**发不出去就算了**（不排队、不重试、不抛）。同步是“尽力而为”的——
丢一帧高亮不影响正确性，下次变化会再推；真正强一致的数据走落盘（[02](02-config-pipeline.md)）。
