package com.villagerdetails.network.channel;

import com.villagerdetails.cache.RuleCache;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 一条同步通道的<b>服务端侧发送 API</b>：发包 / 广播 / 判对方装没装。
 *
 * <p>它是 {@link ClientboundChannel}（{@code network.sync.clientbound} 目录的角色接口）的实现基类：
 * 框架扫那个目录时只认实现该接口的类，并调 {@code register()} 做自检（确认包定义已登记）。
 *
 * <p>包类型注册<b>不在这里</b> —— 那是包自己的事（{@code XxxPayload implements ClientboundPayload}）：
 * 包定义放进 {@code network.sync.payload.clientbound} / {@code .serverbound}，
 * 它自己声明的 {@code ID} + {@code CODEC} 就是登记要的全部东西。
 *
 * <p>子类（放 {@code network.sync.clientbound}，只负责「这个包怎么发」）只要：
 * 构造里 {@code super(XxxPayload.ID)}，需要的话再写自己的发包方法（例如 {@code sendBed}）。
 *
 * <p>另外两件事各有各的目录，一个类只管一件事：
 * <ul>
 *     <li>「服务端收到这个包干什么」→ {@code network.sync.receiver}（{@link ServerReceiver}）</li>
 *     <li>「事件接线（JOIN 同步、规则监听、周期任务）」→ {@code network.sync.event}（{@link SyncEvents}）</li>
 * </ul>
 *
 * <p><b>客户端侧</b>的收包 / 发包在 {@code client.network.channel.ClientReceiver}（默认方法），
 * 由客户端子类
 * {@code XxxClientChannel extends XxxChannel implements ClientReceiver<XxxPayload>} 混进来。
 */
public abstract class SyncChannel<P extends SyncPayload<P>> implements ClientboundChannel<P> {

    private static final Logger log = LogManager.getLogger(SyncChannel.class);

    /**
     * 已经报过「发包失败」的通道。
     *
     * <p>发包调用点分布在 tick / 事件回调里，一旦开始失败往往是连着失败几十次；
     * 所以每个通道只在<b>第一次</b>用 WARN，之后降 DEBUG —— 既不会把日志刷爆，
     * 也不会出现「这条包一直没发出去却查不出来」的静默故障。
     */
    private static final Set<Identifier> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();

    private final PayloadId<P> id;

    protected SyncChannel(PayloadId<P> id) {
        this.id = id;
    }

    /**
     * 这条通道的包 id（{@link ClientboundChannel#id()} 的实现）
     */
    @Override
    public final PayloadId<P> id() {
        return id;
    }

    // ==============================================================
    // 服务端发包
    // ==============================================================

    /**
     * 发给某个玩家 —— <b>对端没装本 mod / 通道被静音 / 发送过程抛异常，一律静默跳过</b>。
     *
     * <p>这里是整个服务端<b>唯一</b>的自定义包发送口：{@link ServerPlayNetworking#send} 在全项目只出现在这一处，
     * 所以「原版客户端收到未知包闪退」从结构上就不可能发生 —— 前提是别在别处直接调 Fabric 的 send。
     *
     * <p>为什么还要 try-catch：{@link #canSendTo} 判定通过到真正写进连接之间，玩家可能刚好掉线、
     * 连接可能已经关闭，这一步会抛 {@code Exception}。发一个同步包失败属于「这次没同步上」，
     * 绝不能让它顺着调用栈把 tick / 事件派发打断（那会连累同一个事件里的其它模组）。
     */
    public final void sendTo(ServerPlayer player, P payload) {
        if (payload == null) {
            return;
        }
        if (!canSendTo(player)) {
            return;
        }
        try {
            ServerPlayNetworking.send(player, payload);
        } catch (Exception e) {
            reportFailure(player, e);
        }
    }

    /**
     * 广播给所有在线玩家。
     *
     * <p>单个玩家失败不会中断循环：{@link #sendTo} 本身保证不抛异常，
     * 所以一个正在掉线的玩家不会把其余玩家的包吞掉。
     */
    public final void broadcast(P payload) {
        forEachPlayer(player -> sendTo(player, payload));
    }

    /** 遍历在线玩家（刷新命令树之类的操作用） */
    public final void forEachPlayer(Consumer<ServerPlayer> action) {
        MinecraftServer server = RuleCache.getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            action.accept(player);
        }
    }

    /**
     * 是否为<b>协议级</b>通道（版本握手、能力协商这类系统级通信）。
     *
     * <p>协议通道<b>不受客户端静音影响</b>：静音表达的是「这个功能的显示效果我不想看」，
     * 而握手是「我们双方能不能一起工作」的前提 —— 一旦握手被静音，版本协商直接失效，
     * 后面所有同步都会错位。
     *
     * <p>但协议通道<b>仍然要过</b> {@link ServerPlayNetworking#canSend}：对端没装本 mod
     * 就根本收不到自定义包，这里放行只会让原版客户端收到未知包而掉线。
     * 「无视静音」不等于「无视对端有没有装」。
     *
     * <p>默认 {@code false}（业务通道，受静音限制）。协议通道要同时做两件事：
     * <b>实现 {@link ProtocolChannel} 标记接口</b> + <b>重写本方法返回 {@code true}</b>。
     * 两者不一致会在启动自检时直接报错（见 {@link #register()}），不会留到运行期。
     *
     * <p>之所以做成钩子而不是让子类重写 {@code sendTo} / {@code canSendTo}：那两个方法是
     * {@code final} 的（统一维护异常兜底与防刷屏日志），开关是侵入性最小的扩展点。
     */
    protected boolean isProtocolChannel() {
        return false;
    }

    /**
     * 启动自检：先做父类的「包定义已登记」检查，再校验协议通道声明的一致性。
     *
     * <p><b>为什么一致性检查放在这里，而不是构造器里</b>（这是刻意的）：
     * 构造器抛异常会让 {@code public static final INSTANCE} 的类初始化失败
     * （{@code ExceptionInInitializerError}），之后<b>任何</b>地方再碰这个通道都会抛
     * {@code NoClassDefFoundError} —— 真正的报错点会漂到某个不相干的业务代码里，反而更难查。
     * 放在启动自检里，错误由 {@code ServerChannels} 汇总成一条指名道姓的 ERROR。
     *
     * @throws IllegalStateException 标记接口与 {@link #isProtocolChannel()} 的返回值不一致
     */
    @Override
    public void register() {
        ClientboundChannel.super.register();
        assertProtocolDeclaration();
    }

    /**
     * 「实现了 {@link ProtocolChannel}」与「{@link #isProtocolChannel()} 返回 true」必须一致。
     *
     * <p>两个方向都要查：
     * <ul>
     *     <li>标了却没返回 true —— 会被当成业务通道，握手包被静音吞掉（静默失效）；</li>
     *     <li>返回 true 却没标 —— 「哪些通道免静音」就没有单一事实来源，grep 不到。</li>
     * </ul>
     */
    private void assertProtocolDeclaration() {
        boolean marked = this instanceof ProtocolChannel;
        boolean hooked = isProtocolChannel();
        if (marked == hooked) {
            return;
        }
        String name = getClass().getSimpleName();
        if (marked) {
            throw new IllegalStateException(name + " 实现了 ProtocolChannel 标记接口，"
                    + "却没有重写 isProtocolChannel() 返回 true。"
                    + "它会被当成业务通道，握手包会被客户端静音设置吞掉（静默失效）。");
        }
        throw new IllegalStateException(name + " 的 isProtocolChannel() 返回 true，"
                + "但没有实现 ProtocolChannel 标记接口。协议通道必须显式标记，"
                + "否则「哪些通道免静音」就没有单一事实来源。");
    }

    /**
     * 这条包能不能发给这个玩家：装没装本 mod <b>且</b>（业务通道时）客户端没有把这条通道静音。
     *
     * <p>静音是客户端单方面的决定（{@link SyncSubscriptions}，由通用的
     * {@code sync_subscription} 包维护）：关掉之后连扫描 / 组包都省了，
     * 客户端重新打开时会再发一个「恢复」包。协议通道见 {@link #isProtocolChannel()}。
     *
     * <p><b>这个方法保证不抛异常</b>：调用方普遍写成「先 canSendTo 决定要不要组包」，
     * 一旦它抛出去，异常会落在调用方的事件回调 / tick 里。判定失败统一按「不发」处理。
     */
    public final boolean canSendTo(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        try {
            // 协议通道跳过静音检查；业务通道仍按客户端订阅决定
            if (!isProtocolChannel() && SyncSubscriptions.isMuted(player, id.id())) {
                return false;
            }
            return ServerPlayNetworking.canSend(player, id.type());
        } catch (Exception e) {
            // isProtocolChannel() 被子类写崩了也走这里：判定失败按「不发」处理，
            // 绝不让异常顺着调用栈打断 tick / 事件派发
            reportFailure(player, e);
            return false;
        }
    }

    /**
     * 发送 / 能力探测失败的统一上报：每个通道只 WARN 一次，之后 DEBUG。
     */
    private void reportFailure(ServerPlayer player, Throwable cause) {
        Identifier channelId = id.id();
        String playerName = player == null ? "<null>" : player.getName().getString();
        if (REPORTED_FAILURES.add(channelId)) {
            log.warn("同步通道 {} 发送失败（该通道此后只在 DEBUG 里提示）：玩家 {}",
                    channelId, playerName, cause);
        } else {
            log.debug("同步通道 {} 发送失败：玩家 {}", channelId, playerName);
        }
    }
}
