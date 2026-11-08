package com.villagerdetails.cache;

import com.villagerdetails.cache.base.SessionState;
import com.villagerdetails.network.channel.ChannelScanner;
import com.villagerdetails.network.channel.SyncSubscriptions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerSessionStates {

    private static final Logger log = LogManager.getLogger(ServerSessionStates.class);

    private static final String STATE_PACKAGE = "com.villagerdetails.cache.impl";

    private static final ServerSessionStates INSTANCE = new ServerSessionStates();

    private final List<SessionState> STATES = new ArrayList<>();

    /**
     * 清单是否已经建好。单例的实例字段（不是 static），所以只需要对「这个单例」做可见性保证。
     */
    private volatile boolean registered;

    /**
     * {@link #init()} 是否已经跑过。
     *
     * <p>单独一个标志是必要的：{@code registerAll()} 早就幂等，但 {@code init()} 里那两行
     * 「订阅 Fabric 事件」过去没有任何守卫 —— 重复调用会把 DISCONNECT / SERVER_STOPPING
     * 各挂第二遍，于是每次退出都会把每个状态清两次。
     *
     * <p>用 {@link AtomicBoolean#compareAndSet} 抢占：只有抢到的那个线程注册事件，
     * 之后（含并发抢位失败）一律走 WARN。抢占本身是原子的，所以不再需要 {@code synchronized (this)}。
     */
    private final AtomicBoolean initialized = new AtomicBoolean();

    private ServerSessionStates() {
    }

    /**
     * 统一注册入口（唯一对外入口）。内部委托给 {@link #init()}。
     */
    public static void register() {
        INSTANCE.init();
    }

    /**
     * 实例侧：取包 + 订阅两个清理事件（幂等）。
     * 之所以不叫 register，是为了避免与 static register() 同名同签名而无法编译。
     *
     * <p><b>全局只该有一个直接触发源</b>（{@code VillagerDetailsServer.init}）。
     * 重复调用会走下面的 WARN —— 注意这条 WARN 只挂在 {@code init()} 上，不挂在
     * {@code registerAll()} 上：后者每次断线 / 停服都会兜底调一次，那是设计内行为，
     * 挂错位置会变成每局游戏刷一条黄色日志。
     */
    private void init() {
        if (!initialized.compareAndSet(false, true)) {
            // 单线程模型下不存在并发抢位：init() 只由启动线程调用，所以走到这里
            // 必然是真的「入口写重」，不是正常竞争 —— 统一打 WARN 才不会漏掉重复注册。
            log.warn("检测到 ServerSessionStates.register() 被重复调用，本次已忽略。"
                    + "请检查服务端入口点（VillagerDetailsServer 的 main / server 两条初始化路径）是否重复注册！");
            return;
        }

        registerAll();

        ServerPlayConnectionEvents.DISCONNECT.register((handler, _) -> {
            ServerPlayer player = handler.getPlayer();
            clearPlayer(player.getUUID());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(_ -> clearAll());
    }

    /**
     * 取包接管所有服务端会话状态（重复调用是空操作）。
     *
     * <p>这里是<b>内部兜底</b>：{@link #clearPlayer(UUID)} / {@link #clearAll()} 每次断线、停服都会调它一次。
     * 所以它的 early-return <b>刻意沉默</b> —— 防重复注册的 WARN 挂在只该被外部调一次的
     * {@link #init()} 上，挂在这里会把正常流程刷成告警。
     *
     * <p><b>不再加锁的前提</b>：它只可能从服务器主线程进来 —— {@link #init()}（入口点）/
     * {@link #clearPlayer(UUID)}（{@code DISCONNECT}）/ {@link #clearAll()}（{@code SERVER_STOPPING}）
     * 都是主线程事件，三者天然串行。{@code registered} 的 volatile 只负责发布，
     * 不负责互斥；<b>若将来出现非主线程的调用方，必须重新加锁或改成 CAS 抢占</b>，
     * 否则会重复填充 {@code STATES}。
     */
    private void registerAll() {
        if (registered) {
            return;
        }

        List<ChannelScanner.Failure> failures = new ArrayList<>();
        List<String> names = new ArrayList<>();

        int count = ChannelScanner.forEach(STATE_PACKAGE, ServerSessionStates.class, SessionState.class,
                state -> {
                    STATES.add(state);
                    // 半注册的状态不该出现在「已接入」清单里
                    names.add(state.getClass().getSimpleName());
                }, failures);

        // 手工登记的「外挂状态」：不在 cache.impl 包里（属于网络框架），但同样只活一次会话。
        // 将来若有同类状态，记得在这里补一行 —— 它不在扫包范围里，漏了不会有任何自检提醒。
        STATES.add(SyncSubscriptions.INSTANCE);
        names.add("SyncSubscriptions");

        ChannelScanner.checkNotEmpty(count, STATE_PACKAGE, "服务端状态扫包", failures);
        ChannelScanner.reportFailures(log, "服务端状态扫包", failures);

        // 最后才发布「已登记」：volatile 写与之前对 STATES 的填充构成 happens-before，
        // 看到 registered == true 的线程一定能看到完整清单。
        // 反过来（先置 registered 再填 STATES）会让别的线程遍历到一张半成品 ArrayList。
        registered = true;

        log.info("服务端缓存扫包完成：{} 个状态接入会话清理 {}", STATES.size(), names);
    }

    /**
     * 某个玩家退出：摘掉他那一份数据。
     */
    private void clearPlayer(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        registerAll();
        for (SessionState state : STATES) {
            guard(state, () -> state.clearPlayer(playerUuid));
        }
    }

    /**
     * 整份清空（服务器停止时调）。
     */
    private void clearAll() {
        registerAll();
        for (SessionState state : STATES) {
            guard(state, state::clearSession);
        }
    }

    private void guard(SessionState state, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            log.error("清理服务端会话状态 [{}] 失败", state.getClass().getSimpleName(), t);
        }
    }
}